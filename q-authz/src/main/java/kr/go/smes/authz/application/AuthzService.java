package kr.go.smes.authz.application;

import kr.go.smes.authz.api.AuthzErrorCode;
import kr.go.smes.authz.api.AuthzException;
import kr.go.smes.authz.api.dto.CreateRoleRequest;
import kr.go.smes.authz.api.dto.GrantRoleRequest;
import kr.go.smes.authz.domain.AssignmentStatus;
import kr.go.smes.authz.domain.AuditEvent;
import kr.go.smes.authz.domain.AuthzRoleEntity;
import kr.go.smes.authz.domain.AuthzRoleId;
import kr.go.smes.authz.domain.AuthzUserRoleEntity;
import kr.go.smes.authz.domain.GrantSource;
import kr.go.smes.authz.infrastructure.AuthzRoleRepository;
import kr.go.smes.authz.infrastructure.AuthzUserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 연합 인가 핵심 서비스 — 역할 카탈로그 + 사용자 역할 부여(grant/revoke) SoR.
 *
 * <p>설계 원칙(부여는 중앙·해석은 지역)에 따라 본 서비스는 "부여" 상태만 관리한다.
 * 권한 의미 해석/리소스 결정은 기관(또는 후속 L3 PDP)의 책임이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthzService {

    private final AuthzRoleRepository     roleRepository;
    private final AuthzUserRoleRepository userRoleRepository;
    private final AuthzAuditService       auditService;

    // ── 역할 카탈로그 ──────────────────────────────────────────────────────────

    @Transactional
    public AuthzRoleEntity createRole(CreateRoleRequest req, String actor, String actorIp) {
        AuthzRoleId id = new AuthzRoleId(req.agencyCode(), req.roleCode());
        if (roleRepository.existsById(id)) {
            throw new AuthzException(AuthzErrorCode.ROLE_ALREADY_EXISTS,
                    "이미 존재하는 역할: " + req.agencyCode() + ":" + req.roleCode());
        }
        AuthzRoleEntity role = AuthzRoleEntity.builder()
                .agencyCode(req.agencyCode())
                .roleCode(req.roleCode())
                .name(req.name())
                .description(req.description())
                .assignable(true)
                .createdAt(Instant.now())
                .createdBy(actor)
                .build();
        roleRepository.save(role);
        auditService.record(AuditEvent.ROLE_CREATED, null, req.agencyCode(), req.roleCode(),
                actor, actorIp, null, null);
        return role;
    }

    @Transactional(readOnly = true)
    public List<AuthzRoleEntity> listRoles(String agencyCode) {
        return roleRepository.findByAgencyCodeOrderByRoleCode(agencyCode);
    }

    // ── 사용자 역할 부여/회수 ──────────────────────────────────────────────────

    /**
     * 역할 부여(멱등). 동일 (user, agency, role)에 ACTIVE가 있으면 그대로 반환하고,
     * REVOKED/EXPIRED 상태면 재활성화한다.
     */
    @Transactional
    public AuthzUserRoleEntity grantRole(GrantRoleRequest req, String actorIp, String correlationId) {
        // 1. 역할 존재 + 부여 가능 검증 (DB FK와 이중 방어)
        AuthzRoleEntity role = roleRepository
                .findById(new AuthzRoleId(req.agencyCode(), req.roleCode()))
                .orElseThrow(() -> new AuthzException(AuthzErrorCode.ROLE_NOT_FOUND,
                        "역할 없음: " + req.agencyCode() + ":" + req.roleCode()));
        if (!role.isAssignable()) {
            throw new AuthzException(AuthzErrorCode.ROLE_NOT_ASSIGNABLE);
        }

        GrantSource source = parseSource(req.source());

        // 2. 기존 부여 upsert
        AuthzUserRoleEntity entity = userRoleRepository
                .findByQimUserIdAndAgencyCodeAndRoleCode(req.qimUserId(), req.agencyCode(), req.roleCode())
                .orElse(null);

        if (entity != null && entity.getStatus() == AssignmentStatus.ACTIVE) {
            // 멱등: 이미 활성 — 만료/출처만 갱신
            entity.setExpiresAt(req.expiresAt());
            entity.setSource(source);
            userRoleRepository.save(entity);
            log.info("[q-authz] 부여 멱등(이미 ACTIVE) user={} agency={} role={}",
                    req.qimUserId(), req.agencyCode(), req.roleCode());
            return entity;
        }

        if (entity == null) {
            entity = AuthzUserRoleEntity.builder()
                    .id(UUID.randomUUID())
                    .qimUserId(req.qimUserId())
                    .agencyCode(req.agencyCode())
                    .roleCode(req.roleCode())
                    .build();
        }
        // 신규 또는 재활성화
        entity.setStatus(AssignmentStatus.ACTIVE);
        entity.setGrantedAt(Instant.now());
        entity.setGrantedBy(req.grantedBy());
        entity.setExpiresAt(req.expiresAt());
        entity.setSource(source);
        entity.setRevokedAt(null);
        entity.setRevokedBy(null);
        userRoleRepository.save(entity);

        auditService.record(AuditEvent.GRANT, req.qimUserId(), req.agencyCode(), req.roleCode(),
                req.grantedBy(), actorIp, req.reason(), correlationId);
        return entity;
    }

    /** 역할 회수. 멱등 — 부여 내역이 없으면 404. */
    @Transactional
    public void revokeRole(String qimUserId, String agencyCode, String roleCode,
                           String revokedBy, String actorIp, String reason, String correlationId) {
        AuthzUserRoleEntity entity = userRoleRepository
                .findByQimUserIdAndAgencyCodeAndRoleCode(qimUserId, agencyCode, roleCode)
                .orElseThrow(() -> new AuthzException(AuthzErrorCode.ASSIGNMENT_NOT_FOUND));

        if (entity.getStatus() != AssignmentStatus.REVOKED) {
            entity.setStatus(AssignmentStatus.REVOKED);
            entity.setRevokedAt(Instant.now());
            entity.setRevokedBy(revokedBy);
            userRoleRepository.save(entity);
        }
        auditService.record(AuditEvent.REVOKE, qimUserId, agencyCode, roleCode,
                revokedBy, actorIp, reason, correlationId);
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    /** 사용자×기관의 ACTIVE 부여 목록(만료 미경과 포함 — 만료 전이는 스케줄러 후속 증분). */
    @Transactional(readOnly = true)
    public List<AuthzUserRoleEntity> listUserRoles(String qimUserId, String agencyCode) {
        return userRoleRepository.findByQimUserIdAndAgencyCodeAndStatus(
                qimUserId, agencyCode, AssignmentStatus.ACTIVE);
    }

    /**
     * 토큰 클레임용 유효 역할 코드 목록. ACTIVE이면서 만료 미경과인 것만.
     * ido(Claim Issuer)가 Handoff/CAST 발급 시 호출한다.
     */
    @Transactional(readOnly = true)
    public List<String> effectiveRoleCodes(String qimUserId, String agencyCode) {
        Instant now = Instant.now();
        return userRoleRepository
                .findByQimUserIdAndAgencyCodeAndStatus(qimUserId, agencyCode, AssignmentStatus.ACTIVE)
                .stream()
                .filter(e -> e.isEffectiveAt(now))
                .map(AuthzUserRoleEntity::getRoleCode)
                .sorted()
                .toList();
    }

    // ── private ───────────────────────────────────────────────────────────────

    private GrantSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) return GrantSource.API;
        try {
            return GrantSource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GrantSource.API;
        }
    }
}
