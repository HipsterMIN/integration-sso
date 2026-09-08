package io.github.hipstermin.idem.authz.application;

import io.github.hipstermin.idem.authz.api.AuthzErrorCode;
import io.github.hipstermin.idem.authz.api.AuthzException;
import io.github.hipstermin.idem.authz.api.dto.CreateRoleRequest;
import io.github.hipstermin.idem.authz.api.dto.GrantRoleRequest;
import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuditEvent;
import io.github.hipstermin.idem.authz.domain.AuthzRoleEntity;
import io.github.hipstermin.idem.authz.domain.AuthzRoleId;
import io.github.hipstermin.idem.authz.domain.AuthzUserRoleEntity;
import io.github.hipstermin.idem.authz.domain.GrantSource;
import io.github.hipstermin.idem.authz.infrastructure.AuthzRoleRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzUserRoleRepository;
import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AuthzOutboxService      outboxService;

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
        // 회수 전파 기반: 부여 이벤트를 같은 TX로 아웃박스 적재
        outboxService.publishInTx(AuthorizationEvent.granted(
                req.qimUserId(), req.agencyCode(), req.roleCode(),
                req.grantedBy(), req.expiresAt(), source.name(), req.reason(), correlationId));
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
            // 실제 전이 시에만 회수 이벤트 발행(반복 호출 시 중복 방지)
            outboxService.publishInTx(AuthorizationEvent.revoked(
                    qimUserId, agencyCode, roleCode, revokedBy, reason, correlationId));
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

    // ── 만료 전이 ───────────────────────────────────────────────────────────

    /**
     * 만료 경과 부여를 ACTIVE → EXPIRED로 전이하고 EXPIRE 감사를 남긴다.
     * 스케줄러가 한 페이지(batchSize)씩 호출한다(대량 만료 시 폭주 방지).
     *
     * @return 이번 호출에서 만료 처리한 건수
     */
    @Transactional
    public int expireOverdue(Instant now, int batchSize) {
        var page = userRoleRepository.findByStatusAndExpiresAtNotNullAndExpiresAtBefore(
                AssignmentStatus.ACTIVE, now, org.springframework.data.domain.PageRequest.of(0, batchSize));
        for (AuthzUserRoleEntity e : page.getContent()) {
            e.setStatus(AssignmentStatus.EXPIRED);
            userRoleRepository.save(e);
            auditService.record(AuditEvent.EXPIRE, e.getQimUserId(), e.getAgencyCode(), e.getRoleCode(),
                    "SYSTEM", null, "expires_at 경과 자동 만료", null);
            outboxService.publishInTx(AuthorizationEvent.expired(
                    e.getQimUserId(), e.getAgencyCode(), e.getRoleCode(), "expires_at 경과 자동 만료"));
        }
        if (!page.isEmpty()) {
            log.info("[q-authz] 만료 전이 {}건 처리 (잔여 추정 hasNext={})",
                    page.getNumberOfElements(), page.hasNext());
        }
        return page.getNumberOfElements();
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
