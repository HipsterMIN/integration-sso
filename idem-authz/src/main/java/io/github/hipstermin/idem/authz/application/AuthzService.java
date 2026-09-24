package io.github.hipstermin.idem.authz.application;

import io.github.hipstermin.idem.authz.api.AuthzErrorCode;
import io.github.hipstermin.idem.authz.api.AuthzException;
import io.github.hipstermin.idem.authz.api.dto.AssignRequest;
import io.github.hipstermin.idem.authz.api.dto.CreateRoleRequest;
import io.github.hipstermin.idem.authz.api.dto.GrantRoleRequest;
import io.github.hipstermin.idem.authz.domain.AssignmentSource;
import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuditEvent;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity;
import io.github.hipstermin.idem.authz.domain.AuthzRoleEntity;
import io.github.hipstermin.idem.authz.domain.AuthzRoleId;
import io.github.hipstermin.idem.authz.domain.AuthzUserRoleEntity;
import io.github.hipstermin.idem.authz.domain.GrantSource;
import io.github.hipstermin.idem.authz.infrastructure.AuthzAssignmentRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzRoleRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzUserRoleRepository;
import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private final AuthzAssignmentRepository assignmentRepository;   // S8-b

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
        // S8-b: 역할이 있으면 그 Service 의 사용자다 — 할당이 없으면 자동 생성(source=ROLE_GRANT)
        ensureAssigned(req.qimUserId(), req.agencyCode(), req.grantedBy(), actorIp, correlationId);
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
        // S8-b: 한시 할당도 같은 주기로 만료
        var assignments = assignmentRepository.findByStatusAndExpiresAtNotNullAndExpiresAtBefore(
                AssignmentStatus.ACTIVE, now, org.springframework.data.domain.PageRequest.of(0, batchSize));
        for (AuthzAssignmentEntity a : assignments.getContent()) {
            a.setStatus(AssignmentStatus.EXPIRED);
            assignmentRepository.save(a);
            auditService.record(AuditEvent.UNASSIGN, a.getQimUserId(), a.getAgencyCode(), null,
                    "SYSTEM", null, "expires_at 경과 자동 만료(할당)", null);
        }
        if (!page.isEmpty() || !assignments.isEmpty()) {
            log.info("[q-authz] 만료 전이 역할 {}건·할당 {}건 처리 (잔여 추정 hasNext={})",
                    page.getNumberOfElements(), assignments.getNumberOfElements(), page.hasNext() || assignments.hasNext());
        }
        return page.getNumberOfElements() + assignments.getNumberOfElements();
    }

    // ── private ───────────────────────────────────────────────────────────────

    // ── S8-b 할당 ────────────────────────────────────────────────────────────

    /** 사용자를 Service 에 할당한다 — 멱등(이미 ACTIVE 면 만료·출처·사유만 갱신). REVOKED/EXPIRED 는 재활성화. */
    @Transactional
    public AuthzAssignmentEntity assign(AssignRequest req, String actorIp, String correlationId) {
        AssignmentSource source = parseAssignmentSource(req.source());
        AuthzAssignmentEntity entity = assignmentRepository
                .findByQimUserIdAndAgencyCode(req.qimUserId(), req.agencyCode())
                .orElse(null);
        if (entity != null && entity.getStatus() == AssignmentStatus.ACTIVE) {
            entity.setExpiresAt(req.expiresAt());
            entity.setSource(source);
            entity.setReason(req.reason());
            assignmentRepository.save(entity);
            log.info("[q-authz] 할당 멱등(이미 ACTIVE) user={} agency={}", req.qimUserId(), req.agencyCode());
            return entity;
        }
        if (entity == null) {
            entity = AuthzAssignmentEntity.builder()
                    .id(UUID.randomUUID())
                    .qimUserId(req.qimUserId())
                    .agencyCode(req.agencyCode())
                    .build();
        }
        entity.setStatus(AssignmentStatus.ACTIVE);
        entity.setSource(source);
        entity.setGrantedAt(Instant.now());
        entity.setGrantedBy(req.grantedBy());
        entity.setExpiresAt(req.expiresAt());
        entity.setReason(req.reason());
        entity.setRevokedAt(null);
        entity.setRevokedBy(null);
        assignmentRepository.save(entity);
        auditService.record(AuditEvent.ASSIGN, req.qimUserId(), req.agencyCode(), null,
                req.grantedBy(), actorIp, req.reason(), correlationId);
        return entity;
    }

    /** 할당 해제 — 역할 부여는 건드리지 않는다(역할은 남지만 미할당이라 발급이 거부된다). */
    @Transactional
    public void unassign(String qimUserId, String agencyCode, String revokedBy, String actorIp,
                         String reason, String correlationId) {
        AuthzAssignmentEntity entity = assignmentRepository
                .findByQimUserIdAndAgencyCode(qimUserId, agencyCode)
                .orElseThrow(() -> new AuthzException(AuthzErrorCode.ASSIGNMENT_NOT_FOUND,
                        "할당 없음: " + qimUserId + "@" + agencyCode));
        if (entity.getStatus() != AssignmentStatus.REVOKED) {
            entity.setStatus(AssignmentStatus.REVOKED);
            entity.setRevokedAt(Instant.now());
            entity.setRevokedBy(revokedBy);
            assignmentRepository.save(entity);
        }
        auditService.record(AuditEvent.UNASSIGN, qimUserId, agencyCode, null,
                revokedBy, actorIp, reason, correlationId);
    }

    /** 유효 할당 (ACTIVE 이고 만료 전). */
    @Transactional(readOnly = true)
    public Optional<AuthzAssignmentEntity> effectiveAssignment(String qimUserId, String agencyCode) {
        Instant now = Instant.now();
        return assignmentRepository.findByQimUserIdAndAgencyCode(qimUserId, agencyCode)
                .filter(a -> a.isEffectiveAt(now));
    }

    private void ensureAssigned(String qimUserId, String agencyCode, String actor, String actorIp, String correlationId) {
        if (effectiveAssignment(qimUserId, agencyCode).isPresent()) return;
        assign(new AssignRequest(qimUserId, agencyCode, actor, null,
                AssignmentSource.ROLE_GRANT.name(), "역할 부여로 자동 할당"), actorIp, correlationId);
    }

    private AssignmentSource parseAssignmentSource(String raw) {
        if (raw == null || raw.isBlank()) return AssignmentSource.API;
        try {
            return AssignmentSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "source 값이 유효하지 않습니다: " + raw);
        }
    }

    private GrantSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) return GrantSource.API;
        try {
            return GrantSource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return GrantSource.API;
        }
    }
}
