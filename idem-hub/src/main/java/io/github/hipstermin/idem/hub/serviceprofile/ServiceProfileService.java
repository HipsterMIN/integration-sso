package io.github.hipstermin.idem.hub.serviceprofile;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcRpClientProvisioner;
import io.github.hipstermin.idem.hub.tenant.TenantJpaRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service Profile 서비스 (S2) — 기관 설정의 단일 쓰기 경로.
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
public class ServiceProfileService {

    public static final String AUDIT_PROFILE_CREATED = "TENANT_PROFILE_CREATED";
    public static final String AUDIT_PROFILE_UPDATED = "TENANT_PROFILE_UPDATED";

    private final AgencyMetaJpaRepository jpaRepository;
    private final TenantJpaRepository     tenantRepository;
    private final ServiceProfileMapper     mapper;
    private final ServiceProfileValidator  validator;
    private final JdbcTemplate            jdbcTemplate;
    private final AuditLogPublisher       auditLogPublisher;
    private final OidcRpClientProvisioner oidcRpProvisioner;

    @Transactional(readOnly = true)
    public ServiceProfile get(String serviceCode) {
        AgencyMetaJpaEntity entity = jpaRepository.findById(serviceCode)
                .orElseThrow(() -> new PlatformException(PlatformErrorCode.AGENCY_NOT_REGISTERED, null,
                        "등록되지 않은 기관: " + serviceCode));
        return mapper.fromEntity(entity, mapper.existingProfile(entity));
    }

    @Transactional(readOnly = true)
    public Optional<ServiceProfile> find(String serviceCode) {
        return jpaRepository.findById(serviceCode).map(e -> mapper.fromEntity(e, mapper.existingProfile(e)));
    }

    /**
     * 프로파일 전체 치환(PUT). 부분 수정은 GET → 수정 → PUT 으로 한다.
     *
     * @param serviceCode 경로의 기관 코드 — 본문의 {@code tenant.code} 와 같아야 한다
     * @return 저장된 프로파일 (컬럼 투영 후 다시 합성한 값)
     */
    @Transactional
    public ServiceProfile put(String serviceCode, JsonNode body, String adminId, String changeReason, String correlationId) {
        validator.validateOrThrow(body, correlationId);
        ServiceProfile requested = mapper.parse(body);
        if (!serviceCode.equals(requested.service().code())) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, correlationId,
                    "경로의 기관 코드(" + serviceCode + ")와 본문 service.code(" + requested.service().code() + ")가 다릅니다");
        }

        String tenantCode = requested.service().tenantOrDefault();
        if (!tenantRepository.existsById(tenantCode)) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE, correlationId,
                    "등록되지 않은 Tenant: service.tenant=" + tenantCode);
        }

        Optional<AgencyMetaJpaEntity> existing = jpaRepository.findById(serviceCode);
        boolean created = existing.isEmpty();
        AgencyMetaJpaEntity entity = existing.orElseGet(() -> AgencyMetaJpaEntity.builder().agencyCode(serviceCode).build());

        // 변경 전 스냅샷 (신규면 요청 본문 자체)
        String previousSnapshot = created
                ? mapper.toJson(requested)
                : Optional.ofNullable(entity.getProfile())
                          .orElseGet(() -> mapper.toJson(mapper.fromEntity(entity, null)));

        IntegrationType previousType = entity.getIntegrationType();
        mapper.applyToEntity(requested, entity);
        AgencyMetaJpaEntity saved = jpaRepository.save(entity);

        // S6: OIDC_RP 면 같은 트랜잭션에서 Keycloak client 를 맞춘다 — 실패(E-IDO-122)는 저장을 되돌린다.
        // OIDC_RP 가 아니고 이전에도 아니었으면 Keycloak 을 부르지 않는다(Keycloak 없는 설치본·테스트 무영향)
        if (requested.protocol().type() == IntegrationType.OIDC_RP || previousType == IntegrationType.OIDC_RP) {
            oidcRpProvisioner.sync(requested, adminId, correlationId);
        }

        recordHistory(serviceCode, saved.getPolicyVersion(), previousSnapshot, adminId,
                changeReason != null ? changeReason : (created ? "프로파일 신규 등록" : "프로파일 갱신"));
        audit(created ? AUDIT_PROFILE_CREATED : AUDIT_PROFILE_UPDATED, serviceCode, adminId);
        log.info("[ServiceProfile] {}: serviceCode={} type={} adminId={}", created ? "신규" : "갱신",
                serviceCode, saved.getIntegrationType(), adminId);

        return mapper.fromEntity(saved, mapper.existingProfile(saved));
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void recordHistory(String serviceCode, String policyVersion, String snapshot, String adminId, String reason) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.agency_meta_history
                        (history_id, agency_code, policy_version, snapshot, changed_by, change_reason)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?)
                    """,
                    UUID.randomUUID().toString(), serviceCode,
                    policyVersion != null ? policyVersion : "1.0",
                    snapshot, adminId, reason);
        } catch (RuntimeException e) {
            // 이력은 감사 보조 — 본 저장을 막지 않되 반드시 남긴다 (CC P1 에서 유실 방지 강화)
            log.error("[ServiceProfile] 이력 저장 실패: serviceCode={} err={}", serviceCode, e.getMessage());
        }
    }

    private void audit(String action, String serviceCode, String adminId) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction(action)
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(adminId)
                    .resourceType("TENANT_PROFILE")
                    .resourceId(serviceCode)
                    .agencyCode(serviceCode)
                    .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[ServiceProfile] 감사 로그 실패 (비치명적): action={} err={}", action, e.getMessage());
        }
    }
}
