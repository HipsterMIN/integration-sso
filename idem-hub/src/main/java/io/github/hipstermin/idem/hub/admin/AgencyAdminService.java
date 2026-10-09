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
import io.github.hipstermin.idem.hub.webhook.WebhookSigningSecrets;
import java.util.LinkedHashMap;
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
    private final WebhookSigningSecrets    webhookSigningSecrets;   // 1.1.1 G1-4

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
        jpaRepository.saveAndFlush(entity);   // 1.1.1 G1-4: 아래 JDBC INSERT(FK → agency_meta)보다 먼저 행이 있어야 한다 — 종전에는 FK 위반이 경고로 삼켜졌다

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

    // ── 웹훅 서명 비밀 (1.1.1 G1-4, 플랜 §2.4) ────────────────────────────────

    /**
     * 웹훅 서명 비밀 회전 — 32바이트 난수를 만들어 KMS 로 봉인해 저장하고(해시는 지문용) 원문은 이 응답에서만 한 번 돌려준다.
     * 구 비밀은 즉시 무효(병행 기간 없음) — 기관 수신기에 먼저 신·구 둘 다 받게 해 두면 무중단. 엔드포인트가 없는 기관은 404(E-IDO-126).
     */
    @Transactional
    public Map<String, Object> rotateWebhookSecret(String agencyCode, String adminId) {
        findOrThrow(agencyCode);
        Integer configured = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idem_hub.agency_webhook_config WHERE agency_code = ?", Integer.class, agencyCode);
        if (configured == null || configured == 0) {
            throw new PlatformException(PlatformErrorCode.IDO_WEBHOOK_NOT_CONFIGURED, null,
                    "webhookEndpoint 를 먼저 등록하세요 (PUT /api/v1/admin/agencies/" + agencyCode + "): " + agencyCode);
        }
        String rawSecret = CryptoProviders.current().randomToken(32);
        String sealed    = webhookSigningSecrets.seal(rawSecret);
        String hash      = sha256Hex(rawSecret);
        jdbcTemplate.update("""
                UPDATE idem_hub.agency_webhook_config
                   SET signing_secret_sealed = ?, signing_secret_hash = ?, secret_rotated_at = NOW(), updated_at = NOW()
                 WHERE agency_code = ?
                """, sealed, hash, agencyCode);

        audit("WEBHOOK_SECRET_ROTATED", agencyCode, null, adminId, AuditLogEvent.OUTCOME_SUCCESS);
        log.info("[Admin] 웹훅 서명 비밀 회전: agencyCode={} fingerprint={}", agencyCode, WebhookSigningSecrets.fingerprint(hash));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agencyCode", agencyCode);
        out.put("signingSecret", rawSecret);
        out.put("fingerprint", WebhookSigningSecrets.fingerprint(hash));
        out.put("warning", "이 비밀은 1회만 표시됩니다. 기관 수신기에 즉시 전달하세요 — 구 비밀은 바로 무효입니다.");
        return out;
    }

    /** 웹훅 설정 상태 — 비밀 원문은 없다(봉인 여부·지문·회전 시각만). 설정이 없는 기관은 configured=false */
    @Transactional(readOnly = true)
    public Map<String, Object> webhookStatus(String agencyCode) {
        findOrThrow(agencyCode);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agencyCode", agencyCode);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.endpoint_url, c.active, (c.signing_secret_sealed IS NOT NULL) AS sealed,
                       c.signing_secret_hash, c.secret_rotated_at, c.updated_at, am.webhook_enabled
                  FROM idem_hub.agency_webhook_config c
                  JOIN idem_hub.agency_meta am ON am.agency_code = c.agency_code
                 WHERE c.agency_code = ?
                """, agencyCode);
        if (rows.isEmpty()) {
            out.put("configured", false);
            return out;
        }
        Map<String, Object> r = rows.get(0);
        boolean sealed = Boolean.TRUE.equals(r.get("sealed"));
        String hash = (String) r.get("signing_secret_hash");
        out.put("configured", true);
        out.put("endpointUrl", r.get("endpoint_url"));
        out.put("active", r.get("active"));
        out.put("webhookEnabled", r.get("webhook_enabled"));
        out.put("hasSecret", sealed || (hash != null && !hash.isBlank()));
        out.put("sealed", sealed);
        // 지문은 봉인된 행에서만 — 봉인 전(1.0.x) 행의 hash 컬럼은 원문이라 일부라도 내보내지 않는다
        out.put("fingerprint", sealed ? WebhookSigningSecrets.fingerprint(hash) : null);
        out.put("secretRotatedAt", r.get("secret_rotated_at"));
        out.put("updatedAt", r.get("updated_at"));
        return out;
    }

    // ── 변경 이력 조회 ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getHistory(String agencyCode) {
        return jdbcTemplate.queryForList("""
                SELECT history_id, agency_code, policy_version, changed_by,
                       change_reason, changed_at
                FROM idem_hub.agency_meta_history
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
                    FROM idem_hub.handoff_audit
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
                    FROM idem_hub.webhook_dispatch_outbox
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

    /**
     * 웹훅 엔드포인트 등록 — 서명 비밀은 여기서 만들지 않는다(회전 API). 1.1.1 G1-4: 종전에는 signing_secret_hash NOT NULL 때문에
     * 이 INSERT 가 조용히 실패해 관리 API 로 만든 기관은 웹훅 설정이 없었고, agency_meta.webhook_enabled 도 바뀌지 않아 발송 대상이 되지 않았다.
     */
    private void upsertWebhookConfig(String agencyCode, String endpoint, boolean enabled) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO idem_hub.agency_webhook_config
                        (agency_code, endpoint_url, active, created_at, updated_at)
                    VALUES (?, ?, ?, NOW(), NOW())
                    ON CONFLICT (agency_code) DO UPDATE
                        SET endpoint_url = EXCLUDED.endpoint_url,
                            active = EXCLUDED.active,
                            updated_at = NOW()
                    """, agencyCode, endpoint, enabled);
            jdbcTemplate.update("UPDATE idem_hub.agency_meta SET webhook_enabled = ?, webhook_endpoint = ? WHERE agency_code = ?",
                    enabled, endpoint, agencyCode);
        } catch (DataAccessException e) {
            log.error("[Admin] webhook config 저장 실패: agencyCode={} error={}", agencyCode, e.getMessage());
            throw e;
        }
    }

    private String queryWebhookEndpoint(String agencyCode) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT endpoint_url FROM idem_hub.agency_webhook_config WHERE agency_code = ?",
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
