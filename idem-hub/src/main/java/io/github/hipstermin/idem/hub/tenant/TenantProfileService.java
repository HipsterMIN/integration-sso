package io.github.hipstermin.idem.hub.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant Profile 서비스 (S2) — 기관 설정의 단일 쓰기 경로.
 *
 * <ul>
 *   <li>{@link #get} — 저장된 프로파일이 있으면 컬럼 값과 합쳐(컬럼 우선) 돌려주고, 없으면 컬럼에서 합성</li>
 *   <li>{@link #put} — 스키마 검증 → 컬럼 투영 → 프로파일 저장 → {@code agency_meta_history} 스냅샷 → 감사.
 *       기관이 없으면 새로 만든다 (프로파일만으로 온보딩)</li>
 * </ul>
 *
 * <p>API 키는 프로파일에 포함하지 않는다 — 발급·회전은 기존 {@code rotate-key} 경로.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProfileService {

    public static final String AUDIT_PROFILE_CREATED = "TENANT_PROFILE_CREATED";
    public static final String AUDIT_PROFILE_UPDATED = "TENANT_PROFILE_UPDATED";

    private final AgencyMetaJpaRepository jpaRepository;
    private final TenantProfileMapper     mapper;
    private final TenantProfileValidator  validator;
    private final JdbcTemplate            jdbcTemplate;
    private final AuditLogPublisher       auditLogPublisher;

    @Transactional(readOnly = true)
    public TenantProfile get(String tenantCode) {
        AgencyMetaJpaEntity entity = jpaRepository.findById(tenantCode)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.AGENCY_NOT_REGISTERED, null,
                        "등록되지 않은 기관: " + tenantCode));
        return mapper.fromEntity(entity, mapper.existingProfile(entity));
    }

    @Transactional(readOnly = true)
    public Optional<TenantProfile> find(String tenantCode) {
        return jpaRepository.findById(tenantCode).map(e -> mapper.fromEntity(e, mapper.existingProfile(e)));
    }

    /**
     * 프로파일 전체 치환(PUT). 부분 수정은 GET → 수정 → PUT 으로 한다.
     *
     * @param tenantCode 경로의 기관 코드 — 본문의 {@code tenant.code} 와 같아야 한다
     * @return 저장된 프로파일 (컬럼 투영 후 다시 합성한 값)
     */
    @Transactional
    public TenantProfile put(String tenantCode, JsonNode body, String adminId, String changeReason, String correlationId) {
        validator.validateOrThrow(body, correlationId);
        TenantProfile requested = mapper.parse(body);
        if (!tenantCode.equals(requested.tenant().code())) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, correlationId,
                    "경로의 기관 코드(" + tenantCode + ")와 본문 tenant.code(" + requested.tenant().code() + ")가 다릅니다");
        }

        Optional<AgencyMetaJpaEntity> existing = jpaRepository.findById(tenantCode);
        boolean created = existing.isEmpty();
        AgencyMetaJpaEntity entity = existing.orElseGet(() -> AgencyMetaJpaEntity.builder().agencyCode(tenantCode).build());

        // 변경 전 스냅샷 (신규면 요청 본문 자체)
        String previousSnapshot = created
                ? mapper.toJson(requested)
                : Optional.ofNullable(entity.getProfile())
                          .orElseGet(() -> mapper.toJson(mapper.fromEntity(entity, null)));

        mapper.applyToEntity(requested, entity);
        AgencyMetaJpaEntity saved = jpaRepository.save(entity);

        recordHistory(tenantCode, saved.getPolicyVersion(), previousSnapshot, adminId,
                changeReason != null ? changeReason : (created ? "프로파일 신규 등록" : "프로파일 갱신"));
        audit(created ? AUDIT_PROFILE_CREATED : AUDIT_PROFILE_UPDATED, tenantCode, adminId);
        log.info("[TenantProfile] {}: tenantCode={} type={} adminId={}", created ? "신규" : "갱신",
                tenantCode, saved.getIntegrationType(), adminId);

        return mapper.fromEntity(saved, mapper.existingProfile(saved));
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void recordHistory(String tenantCode, String policyVersion, String snapshot, String adminId, String reason) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.agency_meta_history
                        (history_id, agency_code, policy_version, snapshot, changed_by, change_reason)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?)
                    """,
                    UUID.randomUUID().toString(), tenantCode,
                    policyVersion != null ? policyVersion : "1.0",
                    snapshot, adminId, reason);
        } catch (RuntimeException e) {
            // 이력은 감사 보조 — 본 저장을 막지 않되 반드시 남긴다 (CC P1 에서 유실 방지 강화)
            log.error("[TenantProfile] 이력 저장 실패: tenantCode={} err={}", tenantCode, e.getMessage());
        }
    }

    private void audit(String action, String tenantCode, String adminId) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction(action)
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(adminId)
                    .resourceType("TENANT_PROFILE")
                    .resourceId(tenantCode)
                    .agencyCode(tenantCode)
                    .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[TenantProfile] 감사 로그 실패 (비치명적): action={} err={}", action, e.getMessage());
        }
    }
}
