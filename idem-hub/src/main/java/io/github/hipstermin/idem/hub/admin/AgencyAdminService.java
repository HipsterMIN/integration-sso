package io.github.hipstermin.idem.hub.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.admin.dto.AgencyCreateRequest;
import io.github.hipstermin.idem.hub.admin.dto.AgencyResponse;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileMapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 Admin 서비스
 *
 * <p>기관 등록·수정·활성화·비활성화·API Key 로테이션을 처리한다.
 * 모든 작업은 audit_log에 기록된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgencyAdminService {

    private final AgencyMetaJpaRepository jpaRepository;
    private final AuditLogPublisher       auditLogPublisher;
    private final JdbcTemplate            jdbcTemplate;
    private final ObjectMapper            objectMapper;
    private final ServiceProfileMapper     serviceProfileMapper;

    /** 신규 기관 생성 시 policyVersion 기본값 (하드코딩 "1.0" 제거) */
    @Value("${idem.hub.policy.default-version:1.0}")
    private String defaultPolicyVersion;

    // ── 기관 등록 ──────────────────────────────────────────────────────────

    @Transactional
    public AgencyResponse createAgency(AgencyCreateRequest req, String adminId) {
        log.info("[Admin] 기관 등록: agencyCode={} adminId={}", req.getAgencyCode(), adminId);

        if (jpaRepository.existsById(req.getAgencyCode())) {
            throw new IllegalArgumentException("이미 등록된 기관 코드: " + req.getAgencyCode());
        }

        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder()
                .agencyCode(req.getAgencyCode())
                .officialName(req.getOfficialName())
                .minAuthLevel(req.getMinAuthLevel() != null ? req.getMinAuthLevel() : "L1")
                .policyVersion(req.getPolicyVersion() != null ? req.getPolicyVersion() : defaultPolicyVersion)
                .integrationType(parseIntegrationType(req.getIntegrationType()))
                .bridgeEndpoint(req.getBridgeEndpoint())
                .apacheGateEndpoint(req.getApacheGateEndpoint())
                .dailyLookupLimit(req.getDailyLookupLimit())
                .ssoDomain(req.getSsoDomain())
                .callbackWhitelist(toJson(req.getCallbackWhitelist()))
                .allowedAttributes(toJson(req.getAllowedAttributes()))
                .active(true)
                .build();

        serviceProfileMapper.syncProfileColumn(entity); // S2: 컬럼 → 프로파일 동기화
        jpaRepository.save(entity);

        // Webhook 설정이 있으면 등록
        if (req.getWebhookEndpoint() != null) {
            upsertWebhookConfig(req.getAgencyCode(), req.getWebhookEndpoint(),
                    Boolean.TRUE.equals(req.getWebhookEnabled()));
        }

        audit("AGENCY_CREATED", req.getAgencyCode(), null, adminId, AuditLogEvent.OUTCOME_SUCCESS);
        log.info("[Admin] 기관 등록 완료: agencyCode={}", req.getAgencyCode());
        return toResponse(entity, req.getWebhookEndpoint(), req.getWebhookEnabled());
    }

    // ── 기관 수정 ──────────────────────────────────────────────────────────

    @Transactional
    public AgencyResponse updateAgency(String agencyCode, AgencyCreateRequest req, String adminId) {
        AgencyMetaJpaEntity entity = findOrThrow(agencyCode);

        if (req.getOfficialName()     != null) entity.setOfficialName(req.getOfficialName());
        if (req.getMinAuthLevel()     != null) entity.setMinAuthLevel(req.getMinAuthLevel());
        if (req.getPolicyVersion()    != null) entity.setPolicyVersion(req.getPolicyVersion());
        if (req.getIntegrationType()  != null) entity.setIntegrationType(parseIntegrationType(req.getIntegrationType()));
        if (req.getBridgeEndpoint()   != null) entity.setBridgeEndpoint(req.getBridgeEndpoint());
        if (req.getApacheGateEndpoint() != null) entity.setApacheGateEndpoint(req.getApacheGateEndpoint());
        if (req.getDailyLookupLimit()   != null) entity.setDailyLookupLimit(req.getDailyLookupLimit());
        if (req.getSsoDomain()        != null) entity.setSsoDomain(req.getSsoDomain());
        if (req.getCallbackWhitelist() != null) entity.setCallbackWhitelist(toJson(req.getCallbackWhitelist()));
        if (req.getAllowedAttributes() != null) entity.setAllowedAttributes(toJson(req.getAllowedAttributes()));

        serviceProfileMapper.syncProfileColumn(entity); // S2: 컬럼 → 프로파일 동기화
        jpaRepository.save(entity);

        if (req.getWebhookEndpoint() != null) {
            upsertWebhookConfig(agencyCode, req.getWebhookEndpoint(),
                    Boolean.TRUE.equals(req.getWebhookEnabled()));
        }

        audit("AGENCY_UPDATED", agencyCode, null, adminId, AuditLogEvent.OUTCOME_SUCCESS);
        return toResponse(entity, req.getWebhookEndpoint(), req.getWebhookEnabled());
    }

    // ── 기관 목록 조회 ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AgencyResponse> listAgencies(int page, int size) {
        return jpaRepository.findAll(
                org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by("agencyCode")))
                .stream()
                .map(e -> toResponse(e, null, null))
                .toList();
    }

    // ── 기관 단건 조회 ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AgencyResponse getAgency(String agencyCode) {
        AgencyMetaJpaEntity entity = findOrThrow(agencyCode);
        String webhookEndpoint = queryWebhookEndpoint(agencyCode);
        return toResponse(entity, webhookEndpoint, null);
    }

    // ── 활성화 / 비활성화 ─────────────────────────────────────────────────

    @Transactional
    public void activate(String agencyCode, String adminId) {
        AgencyMetaJpaEntity entity = findOrThrow(agencyCode);
        entity.setActive(true);
        serviceProfileMapper.syncProfileColumn(entity); // S2: 컬럼 → 프로파일 동기화
        jpaRepository.save(entity);
        audit("AGENCY_ACTIVATED", agencyCode, null, adminId, AuditLogEvent.OUTCOME_SUCCESS);
        log.info("[Admin] 기관 활성화: agencyCode={}", agencyCode);
    }

    @Transactional
    public void deactivate(String agencyCode, String adminId) {
        AgencyMetaJpaEntity entity = findOrThrow(agencyCode);
        entity.setActive(false);
        serviceProfileMapper.syncProfileColumn(entity); // S2: 컬럼 → 프로파일 동기화
        jpaRepository.save(entity);
        audit("AGENCY_DEACTIVATED", agencyCode, null, adminId, AuditLogEvent.OUTCOME_SUCCESS);
        log.info("[Admin] 기관 비활성화: agencyCode={}", agencyCode);
    }

    // ── API Key 로테이션 ───────────────────────────────────────────────────

    @Transactional
    public Map<String, String> rotateApiKey(String agencyCode, String adminId) {
        AgencyMetaJpaEntity entity = findOrThrow(agencyCode);

        // 새 API Key 생성 (32바이트 랜덤)
        String newRawKey = CryptoProviders.current().randomToken(32);
        String newHash   = sha256Hex(newRawKey);

        entity.setApiKeyHash(newHash);
        serviceProfileMapper.syncProfileColumn(entity); // S2: 컬럼 → 프로파일 동기화
        jpaRepository.save(entity);

        audit("AGENCY_KEY_ROTATED", agencyCode, null, adminId, AuditLogEvent.OUTCOME_SUCCESS);
        log.info("[Admin] API Key 로테이션 완료: agencyCode={}", agencyCode);

        // 새 rawKey는 이 응답에서만 1회 반환 — 이후 조회 불가
        return Map.of(
                "agencyCode", agencyCode,
                "newApiKey",  newRawKey,
                "warning",    "이 키는 1회만 표시됩니다. 즉시 안전하게 저장하세요."
        );
    }

    // ── 변경 이력 조회 ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getHistory(String agencyCode) {
        return jdbcTemplate.queryForList("""
                SELECT history_id, agency_code, policy_version, changed_by,
                       change_reason, changed_at
                FROM ido.agency_meta_history
                WHERE agency_code = ?
                ORDER BY changed_at DESC
                LIMIT 50
                """, agencyCode);
    }

    // ── 연동 통계 조회 ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(String agencyCode) {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("agencyCode", agencyCode);

        // 티켓 발급 현황
        try {
            var ticketStats = jdbcTemplate.queryForMap("""
                    SELECT
                        COUNT(*) FILTER (WHERE state='ISSUED')   AS issued_count,
                        COUNT(*) FILTER (WHERE state='CONSUMED') AS consumed_count,
                        COUNT(*) FILTER (WHERE state='EXPIRED')  AS expired_count,
                        COUNT(*) FILTER (WHERE state='REVOKED')  AS revoked_count,
                        COUNT(*) AS total_count
                    FROM ido.handoff_audit
                    WHERE agency_code = ?
                      AND issued_at >= NOW() - INTERVAL '24 hours'
                    """, agencyCode);
            stats.put("last24h", ticketStats);
        } catch (DataAccessException e) {
            log.warn("[Admin] ticketStats 조회 실패 (비치명적): agencyCode={} error={}", agencyCode, e.getMessage());
            stats.put("last24h", Map.of("error", e.getMessage()));
        }

        // Webhook 상태
        try {
            var webhookStats = jdbcTemplate.queryForMap("""
                    SELECT
                        COUNT(*) FILTER (WHERE status='PENDING')     AS pending,
                        COUNT(*) FILTER (WHERE status='PUBLISHED')   AS published,
                        COUNT(*) FILTER (WHERE status='FAILED')      AS failed,
                        COUNT(*) FILTER (WHERE status='DEAD_LETTER') AS dead_letter
                    FROM ido.webhook_dispatch_outbox
                    WHERE agency_code = ?
                      AND created_at >= NOW() - INTERVAL '24 hours'
                    """, agencyCode);
            stats.put("webhook24h", webhookStats);
        } catch (DataAccessException e) {
            log.warn("[Admin] webhookStats 조회 실패 (비치명적): agencyCode={} error={}", agencyCode, e.getMessage());
            stats.put("webhook24h", Map.of("note", "webhook_dispatch_outbox 조회 불가"));
        }

        return stats;
    }

    // ── private ────────────────────────────────────────────────────────────

    private AgencyMetaJpaEntity findOrThrow(String agencyCode) {
        return jpaRepository.findById(agencyCode)
                .orElseThrow(() -> new IllegalArgumentException("등록되지 않은 기관: " + agencyCode));
    }

    private void upsertWebhookConfig(String agencyCode, String endpoint, boolean enabled) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.agency_webhook_config
                        (agency_code, endpoint_url, active, created_at, updated_at)
                    VALUES (?, ?, ?, NOW(), NOW())
                    ON CONFLICT (agency_code) DO UPDATE
                        SET endpoint_url = EXCLUDED.endpoint_url,
                            active = EXCLUDED.active,
                            updated_at = NOW()
                    """, agencyCode, endpoint, enabled);
        } catch (DataAccessException e) {
            log.warn("[Admin] webhook config 저장 실패 (비치명적): agencyCode={} error={}", agencyCode, e.getMessage());
        }
    }

    private String queryWebhookEndpoint(String agencyCode) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT endpoint_url FROM ido.agency_webhook_config WHERE agency_code = ?",
                    String.class, agencyCode);
        } catch (EmptyResultDataAccessException e) {
            // 정상 케이스 — webhook 미설정 기관
            return null;
        } catch (DataAccessException e) {
            log.warn("[Admin] webhookEndpoint 조회 실패 (비치명적): agencyCode={} error={}", agencyCode, e.getMessage());
            return null;
        }
    }

    private void audit(String action, String agencyCode, String resourceId,
                       String adminId, String outcome) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction(action)
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(adminId)
                    .resourceType("AGENCY")
                    .resourceId(resourceId != null ? resourceId : agencyCode)
                    .agencyCode(agencyCode)
                    .outcome(outcome)
                    .build());
        } catch (RuntimeException e) {
            log.warn("[Admin] 감사 로그 실패 (비치명적): action={} error={}", action, e.getMessage());
        }
    }

    private String sha256Hex(String input) {
        return CryptoProviders.current().sha256Hex(input);
    }

    /** 연동 유형 검증 — 미지 값은 400(E-IDO-111). null 은 DEFAULT(DIRECT). */
    private IntegrationType parseIntegrationType(String raw) {
        try {
            return IntegrationType.fromOrDefault(raw);
        } catch (IllegalArgumentException e) {
            throw new PlatformException(PlatformErrorCode.IDO_INVALID_INTEGRATION_TYPE, null, e.getMessage());
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try { return objectMapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) {
            log.warn("[Admin] JSON 직렬화 실패: type={} error={}", obj.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private AgencyResponse toResponse(AgencyMetaJpaEntity e,
                                      String webhookEndpoint, Boolean webhookEnabled) {
        return AgencyResponse.builder()
                .agencyCode(e.getAgencyCode())
                .officialName(e.getOfficialName())
                .minAuthLevel(e.getMinAuthLevel())
                .policyVersion(e.getPolicyVersion())
                .integrationType(e.getIntegrationType() != null ? e.getIntegrationType().name() : IntegrationType.DEFAULT.name())
                .bridgeEndpoint(e.getBridgeEndpoint())
                .apacheGateEndpoint(e.getApacheGateEndpoint())
                .dailyLookupLimit(e.getDailyLookupLimit())
                .ssoDomain(e.getSsoDomain())
                .callbackWhitelist(parseJsonList(e.getCallbackWhitelist()))
                .allowedAttributes(parseJsonList(e.getAllowedAttributes()))
                .webhookEndpoint(webhookEndpoint)
                .webhookEnabled(webhookEnabled)
                .active(e.isActive())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, List.class); }
        catch (JsonProcessingException e) {
            log.warn("[Admin] JSON 역직렬화 실패: json={} error={}", json, e.getMessage());
            return List.of();
        }
    }
}
