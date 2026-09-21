package io.github.hipstermin.idem.hub.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기관 Webhook 발송 핵심 서비스
 *
 * <p><b>책임</b>:
 * <ol>
 *   <li>기관 webhook 설정 조회 ({@code ido.agency_webhook_config})</li>
 *   <li>기관별 이벤트 필터 적용 (event_type_filter 매칭)</li>
 *   <li>{@code ido.webhook_dispatch_outbox} 에 발송 레코드 적재</li>
 *   <li>HMAC-SHA256 서명 생성 (X-Webhook-Signature 헤더)</li>
 * </ol>
 *
 * <p><b>실제 HTTP 발송은 {@link WebhookDispatchOutboxRelay}가 담당</b>.
 * 이 서비스는 Outbox 적재만 수행 — 트랜잭션 경계 내 at-least-once 보장.
 *
 * <p><b>이벤트 흐름</b>:
 * <pre>
 * Kafka(ido.handoff.events) → HandoffEventConsumer
 *       → WebhookDispatcherService.enqueueForAllAgencies()
 *             → ido.webhook_dispatch_outbox INSERT (트랜잭션)
 *                   → WebhookDispatchOutboxRelay (500ms 폴링)
 *                         → HTTPS POST 기관 endpoint
 * </pre>
 *
 * <p><b>보안 원칙</b>:
 * <ul>
 *   <li>webhook payload에 qimUserId 원본 금지 → agencySubjectId 사용</li>
 *   <li>CI/DN 원본값 포함 금지</li>
 *   <li>HMAC-SHA256 서명으로 위·변조 방지</li>
 *   <li>기관별 독립 signing secret → 한 기관 key 유출이 타 기관에 영향 없음</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDispatcherService {

    private static final String SOURCE_SYSTEM = "ido";

    private final JdbcTemplate      jdbcTemplate;
    private final ObjectMapper       objectMapper;
    private final AuditLogPublisher  auditLogPublisher;

    @Value("${ido.webhook.default-max-retry:3}")
    private int defaultMaxRetry;

    /**
     * Sprint α-3 / F4.3 — Webhook 기본 서명 시크릿 (운영 환경에서 강제 주입)
     *
     * <p><b>변경 이력</b>:
     * <ul>
     *   <li>이전: 기본값 {@code "poc-webhook-secret-change-in-production"} 하드코딩 → 운영에 PoC 시크릿이 그대로 흘러갈 위험</li>
     *   <li>현재: 기본값 제거 → 미설정 시 {@link #validateSigningSecret()}가 부팅 단계에서 fail-fast</li>
     * </ul>
     *
     * <p>운영 배포 시 반드시 환경변수/Vault에서 {@code IDO_WEBHOOK_SIGNING_SECRET} 주입.
     * 테스트/로컬 등 fallback 시크릿이 정당하게 필요한 환경에서는
     * {@code ido.webhook.allow-empty-secret=true}를 설정하면 부팅 검증을 우회한다.
     */
    @Value("${ido.webhook.signing-secret:}")
    private String defaultSigningSecret;

    /**
     * Sprint α-3 / F4.3 — Webhook signing secret 비어 있는 상태 허용 여부 (escape hatch).
     *
     * <p>기본 {@code false} — 운영/스테이징 부팅 시 비어 있으면 즉시 실패.
     * 테스트 컨텍스트({@code application-integration-test.yml})에서만 {@code true}로 활성화.
     */
    @Value("${ido.webhook.allow-empty-secret:false}")
    private boolean allowEmptySecret;

    /** 플랫폼 API 버전 헤더값 (하드코딩 "1.0" 제거) */
    @Value("${ido.platform-version:1.0}")
    private String platformVersion;

    /**
     * Sprint α-3 / F4.3 — 부팅 시 webhook signing secret 강제 검증.
     *
     * <p>{@code ido.webhook.signing-secret} 미설정 + escape hatch 비활성 시
     * Spring 컨텍스트 초기화 단계에서 {@link IllegalStateException}을 던져
     * PoC 기본 시크릿이 운영에 누설되는 사고를 사전 차단한다.
     *
     * <p>분석 문서: docs/analysis/sso-im-readiness/04_handoff_flow.md (F4.3)
     */
    @PostConstruct
    void validateSigningSecret() {
        boolean blank = (defaultSigningSecret == null || defaultSigningSecret.isBlank());
        if (blank && !allowEmptySecret) {
            throw new IllegalStateException(
                "[F4.3 Guard] ido.webhook.signing-secret 미설정 — 운영 환경 부팅 차단. " +
                "환경변수 IDO_WEBHOOK_SIGNING_SECRET 또는 Vault 주입 필수. " +
                "테스트 컨텍스트에서만 ido.webhook.allow-empty-secret=true 허용."
            );
        }
        if (blank) {
            log.warn("[WebhookDispatcher][F4.3] signing-secret 비어 있음 — allow-empty-secret=true 활성. " +
                     "이 모드는 테스트 전용이며 운영 배포 금지.");
        } else {
            log.info("[WebhookDispatcher][F4.3] signing-secret 주입 확인 완료 (len={})", defaultSigningSecret.length());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 공개 API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handoff 이벤트를 수신하는 모든 활성 기관에 webhook 발송 큐 등록
     *
     * <p>대상 기관 선택 기준:
     * <ul>
     *   <li>{@code agency_meta.webhook_enabled = true}</li>
     *   <li>이벤트 타입이 기관 {@code event_type_filter}와 매칭 (filter null = 전체 허용)</li>
     *   <li>동일 source_event_id + agency_code 조합이 아직 없는 경우 (중복 방지)</li>
     * </ul>
     *
     * @param handoffEvent  Kafka에서 수신한 HandoffEvent
     * @param correlationId 추적 ID
     */
    @Transactional
    public void enqueueForHandoffEvent(HandoffEvent handoffEvent, String correlationId) {
        String eventType    = handoffEvent.getEventType();
        String sourceEventId = handoffEvent.getEventId();
        String agencyCode   = handoffEvent.getAgencyCode();

        log.info("[WebhookDispatcher] Handoff 이벤트 수신: eventType={} agencyCode={} sourceEventId={}",
                eventType, agencyCode, sourceEventId);

        // ① 대상 기관 목록 조회 (webhook_enabled + event_type_filter 적용)
        List<AgencyWebhookConfig> targets = findWebhookTargets(eventType, agencyCode);

        if (targets.isEmpty()) {
            log.debug("[WebhookDispatcher] webhook 대상 기관 없음: eventType={} agencyCode={}",
                    eventType, agencyCode);
            return;
        }

        // ② 각 기관별 Outbox 적재
        int enqueued = 0;
        for (AgencyWebhookConfig config : targets) {
            try {
                String payload = buildHandoffWebhookPayload(handoffEvent, config, correlationId);
                boolean inserted = insertOutbox(
                        config, sourceEventId, eventType,
                        "ido.handoff.events", payload, correlationId
                );
                if (inserted) enqueued++;
            } catch (Exception e) {
                log.error("[WebhookDispatcher] Outbox 적재 실패: agencyCode={} sourceEventId={} error={}",
                        config.agencyCode(), sourceEventId, e.getMessage());
            }
        }

        log.info("[WebhookDispatcher] Outbox 적재 완료: eventType={} 총{}건 대상 {}건 등록",
                eventType, targets.size(), enqueued);

        // ③ 감사 로그
        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_WEBHOOK)
                        .eventAction("WEBHOOK_ENQUEUED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("TICKET")
                        .resourceId(handoffEvent.getTicketId())
                        .agencyCode(agencyCode)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("enqueued=" + enqueued + "/" + targets.size())
                        .metadata(Map.of(
                                "eventType",     eventType,
                                "sourceEventId", sourceEventId,
                                "enqueuedCount", enqueued
                        ))
                        .build()
        );
    }

    /**
     * 회원 조회 결과를 특정 기관에 webhook 발송 큐 등록
     *
     * <p>유관기관이 CI/DN으로 회원 가입 여부를 조회한 결과를
     * 비동기 webhook으로 push.
     *
     * @param requestId      원본 조회 요청 ID
     * @param agencyCode     결과를 받을 기관 코드
     * @param identifierHash SHA-256(CI|DN|BRNO)
     * @param exists         회원 존재 여부
     * @param instMbrId      회원 ID (없으면 null)
     * @param correlationId  추적 ID
     */
    @Transactional
    public void enqueueForMemberLookupResult(String requestId, String agencyCode,
                                              String identifierHash, boolean exists,
                                              String instMbrId, String correlationId) {
        List<AgencyWebhookConfig> targets = findWebhookTargets("MEMBER_LOOKUP_RESULT", agencyCode);
        if (targets.isEmpty()) {
            log.info("[WebhookDispatcher] MEMBER_LOOKUP_RESULT webhook 대상 없음: agencyCode={}", agencyCode);
            return;
        }

        AgencyWebhookConfig config = targets.get(0);
        try {
            String payload = buildMemberLookupPayload(
                    requestId, identifierHash, exists, instMbrId, correlationId
            );
            insertOutbox(config, requestId, "MEMBER_LOOKUP_RESULT",
                    "ido.member.lookup.requests", payload, correlationId);
            log.info("[WebhookDispatcher] MEMBER_LOOKUP_RESULT Outbox 등록: agencyCode={} exists={}",
                    agencyCode, exists);
        } catch (Exception e) {
            log.error("[WebhookDispatcher] MEMBER_LOOKUP_RESULT Outbox 실패: agencyCode={} error={}",
                    agencyCode, e.getMessage());
        }
    }

    /**
     * 회원 탈퇴 이벤트를 모든 기관에 webhook 발송 큐 등록
     */
    @Transactional
    /**
     * SLO (Single Logout) 기관 Webhook 발송 — Outbox 적재
     * Sprint 2 P1-03
     *
     * <p>feSession 만료 후 연계 기관 전체에 USER_LOGOUT 이벤트 전파.
     * WebhookDispatchOutboxRelay가 HTTPS POST로 기관별 endpoint에 비동기 발송.
     *
     * @param instMbrId    기관 회원 ID
     * @param qimUserId    Q-IM 사용자 ID (로그 추적용)
     * @param correlationId 추적 ID
     */
    public void enqueueForUserLogout(String instMbrId, String qimUserId, String correlationId) {
        List<AgencyWebhookConfig> targets = findWebhookTargets("USER_LOGOUT", null);
        if (targets.isEmpty()) {
            log.debug("[WebhookDispatcher] USER_LOGOUT Webhook 대상 없음: qimUserId={}", qimUserId);
            return;
        }

        String sourceEventId = UuidV7.generate();
        for (AgencyWebhookConfig config : targets) {
            try {
                String payload = buildUserLogoutPayload(instMbrId, correlationId);
                insertOutbox(config, sourceEventId, "USER_LOGOUT",
                        "ido.session.events", payload, correlationId);
            } catch (Exception e) {
                log.error("[WebhookDispatcher] USER_LOGOUT Outbox 실패: agencyCode={} error={}",
                        config.agencyCode(), e.getMessage());
            }
        }
        log.info("[WebhookDispatcher] USER_LOGOUT Outbox 적재 완료: qimUserId={} targets={}",
                qimUserId, targets.size());
    }

    public void enqueueForMemberWithdrawn(String instMbrId, String qimUserId,
                                           String correlationId) {
        List<AgencyWebhookConfig> targets = findWebhookTargets("MEMBER_WITHDRAWN", null);
        if (targets.isEmpty()) return;

        String sourceEventId = UuidV7.generate();
        for (AgencyWebhookConfig config : targets) {
            try {
                String payload = buildMemberWithdrawnPayload(instMbrId, correlationId);
                insertOutbox(config, sourceEventId, "MEMBER_WITHDRAWN",
                        "qim.sp.member.events", payload, correlationId);
            } catch (Exception e) {
                log.error("[WebhookDispatcher] MEMBER_WITHDRAWN Outbox 실패: agencyCode={} error={}",
                        config.agencyCode(), e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HMAC 서명 생성 (WebhookDispatchOutboxRelay 에서도 호출)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * HMAC-SHA256 서명 생성
     *
     * <p>기관 webhook 수신측 검증 방법:
     * <pre>
     * expectedSig = HmacSHA256(payload, rawSecret)
     * if (X-Webhook-Signature != "sha256=" + hex(expectedSig)) → 거부
     * </pre>
     *
     * @param payload      발송 payload (UTF-8 바이트)
     * @param rawSecret    서명 비밀키 원본 (Base64 디코딩 전)
     * @return "sha256=" + HEX(HMAC-SHA256)
     */
    public String computeHmacSignature(String payload, String rawSecret) {
        // Sprint α-3 / F4.3 — fallback to defaultSigningSecret 제거.
        // 호출측(WebhookDispatchOutboxRelay 등)이 반드시 기관별 raw secret을 전달해야 한다.
        // rawSecret이 비어 있으면 fail-fast하여 "PoC 기본 시크릿으로 서명되는 사고"를 봉쇄.
        if (rawSecret == null || rawSecret.isBlank()) {
            // allow-empty-secret 모드(테스트 전용)인 경우에 한해 defaultSigningSecret로 우회 허용
            if (allowEmptySecret && defaultSigningSecret != null && !defaultSigningSecret.isBlank()) {
                log.warn("[WebhookDispatcher][F4.3] rawSecret 누락 → allow-empty-secret 모드에서 default fallback 사용 (테스트 전용).");
                rawSecret = defaultSigningSecret;
            } else {
                throw new IllegalArgumentException(
                    "[F4.3 Guard] webhook rawSecret 누락 — 기관별 signing_secret 미설정. " +
                    "agency_webhook_config.signing_secret_hash 컬럼 확인 필요."
                );
            }
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rawSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + bytesToHex(digest);
        } catch (IllegalArgumentException e) {
            throw e;   // F4.3 가드는 그대로 전파
        } catch (Exception e) {
            log.error("[WebhookDispatcher] HMAC 서명 생성 실패: {}", e.getMessage());
            return "sha256=error";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 내부 구현
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * webhook 대상 기관 목록 조회
     * agencyCode 지정 시 해당 기관만, null이면 webhook_enabled 전체 기관
     */
    @SuppressWarnings("unchecked")
    private List<AgencyWebhookConfig> findWebhookTargets(String eventType, String agencyCode) {
        String sql;
        Object[] params;

        if (agencyCode != null) {
            sql = """
                    SELECT wc.agency_code, wc.endpoint_url,
                           wc.signing_secret_hash, wc.connect_timeout_ms, wc.read_timeout_ms,
                           wc.max_retry_count, wc.retry_backoff_ms, wc.event_type_filter::text
                    FROM ido.agency_webhook_config wc
                    JOIN ido.agency_meta am ON am.agency_code = wc.agency_code
                    WHERE wc.active = TRUE
                      AND am.active = TRUE
                      AND am.webhook_enabled = TRUE
                      AND wc.agency_code = ?
                    """;
            params = new Object[]{ agencyCode };
        } else {
            sql = """
                    SELECT wc.agency_code, wc.endpoint_url,
                           wc.signing_secret_hash, wc.connect_timeout_ms, wc.read_timeout_ms,
                           wc.max_retry_count, wc.retry_backoff_ms, wc.event_type_filter::text
                    FROM ido.agency_webhook_config wc
                    JOIN ido.agency_meta am ON am.agency_code = wc.agency_code
                    WHERE wc.active = TRUE
                      AND am.active = TRUE
                      AND am.webhook_enabled = TRUE
                    """;
            params = new Object[]{};
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
        List<AgencyWebhookConfig> result = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String filterJson = (String) row.get("event_type_filter");
            if (!matchesEventTypeFilter(eventType, filterJson)) {
                log.debug("[WebhookDispatcher] 이벤트 필터 미매칭: agencyCode={} eventType={}",
                        row.get("agency_code"), eventType);
                continue;
            }

            result.add(new AgencyWebhookConfig(
                    (String) row.get("agency_code"),
                    (String) row.get("endpoint_url"),
                    (String) row.get("signing_secret_hash"),
                    toInt(row.get("connect_timeout_ms"), 3000),
                    toInt(row.get("read_timeout_ms"), 8000),
                    toInt(row.get("max_retry_count"), defaultMaxRetry),
                    toInt(row.get("retry_backoff_ms"), 1000)
            ));
        }
        return result;
    }

    /**
     * event_type_filter JSON 배열과 이벤트 타입 매칭
     * filter == null → 전체 허용
     */
    @SuppressWarnings("unchecked")
    private boolean matchesEventTypeFilter(String eventType, String filterJson) {
        if (filterJson == null || filterJson.isBlank() || filterJson.equals("null")) {
            return true;  // filter 없음 → 전체 허용
        }
        try {
            List<String> allowed = objectMapper.readValue(filterJson, List.class);
            return allowed.contains(eventType);
        } catch (Exception e) {
            // D2 fail-secure: 손상된 필터는 "전체 허용" 이 아니라 "발송 안 함" — 기관이 구독하지 않은 이벤트 유출 방지
            log.error("[WebhookDispatcher] event_type_filter 파싱 실패 → 해당 기관 발송 보류: {}", filterJson);
            return false;
        }
    }

    /**
     * webhook_dispatch_outbox INSERT
     *
     * @return true = 신규 삽입, false = 중복 (이미 등록)
     */
    private boolean insertOutbox(AgencyWebhookConfig config,
                                  String sourceEventId, String sourceEventType,
                                  String sourceTopic, String payloadJson,
                                  String correlationId) {
        try {
            // next_retry_at = NOW() (즉시 발송 시도)
            int updated = jdbcTemplate.update("""
                    INSERT INTO ido.webhook_dispatch_outbox (
                        dispatch_id, agency_code, endpoint_url,
                        source_event_id, source_event_type, source_topic,
                        correlation_id, payload, status,
                        retry_count, max_retry, next_retry_at, created_at
                    ) VALUES (?,?,?,?,?,?,?,?::jsonb,'PENDING',0,?,NOW(),NOW())
                    ON CONFLICT (source_event_id, agency_code) DO NOTHING
                    """,
                    UuidV7.generate(),
                    config.agencyCode(),
                    config.endpointUrl(),
                    sourceEventId, sourceEventType, sourceTopic,
                    correlationId,
                    payloadJson,
                    config.maxRetry()
            );
            if (updated == 0) {
                log.debug("[WebhookDispatcher] 중복 Outbox 스킵: agencyCode={} sourceEventId={}",
                        config.agencyCode(), sourceEventId);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("[WebhookDispatcher] Outbox INSERT 실패: agencyCode={} error={}",
                    config.agencyCode(), e.getMessage());
            throw new RuntimeException("Webhook Outbox 적재 실패", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Payload 빌더 (개인정보 마스킹 원칙 적용)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handoff 이벤트 webhook payload 구성
     *
     * <p>qimUserId 원본 포함 금지.
     * 기관은 ticketId, agencyCode, eventType, correlationId 로 이벤트를 식별.
     */
    private String buildHandoffWebhookPayload(HandoffEvent event,
                                               AgencyWebhookConfig config,
                                               String correlationId) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId",       event.getEventId());
        payload.put("eventType",     event.getEventType());
        payload.put("agencyCode",    event.getAgencyCode());
        payload.put("ticketId",      event.getTicketId());
        payload.put("ticketState",   event.getTicketState());
        payload.put("correlationId", correlationId);
        payload.put("occurredAt",    event.getOccurredAt() != null
                ? event.getOccurredAt().toString() : Instant.now().toString());

        // REVOKED 이벤트에만 사유 포함
        if (HandoffEvent.TYPE_HANDOFF_REVOKED.equals(event.getEventType())
                && event.getRevokeReason() != null) {
            payload.put("revokeReason", event.getRevokeReason());
        }

        // 플랫폼 메타
        payload.put("platformVersion", platformVersion);
        payload.put("sourceSystem",    "ido");

        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 회원 조회 결과 payload
     * identifierHash만 포함 — CI/DN 원본값 절대 포함 금지
     */
    private String buildMemberLookupPayload(String requestId, String identifierHash,
                                             boolean exists, String instMbrId,
                                             String correlationId) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId",      requestId);
        payload.put("eventType",      "MEMBER_LOOKUP_RESULT");
        payload.put("identifierHash", identifierHash);
        payload.put("exists",         exists);
        if (exists && instMbrId != null) {
            payload.put("instMbrId", instMbrId);
        }
        payload.put("correlationId", correlationId);
        payload.put("occurredAt",    Instant.now().toString());
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 회원 탈퇴 통보 payload
     */
    private String buildUserLogoutPayload(String instMbrId,
                                          String correlationId) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType",     "USER_LOGOUT");
        payload.put("instMbrId",     instMbrId);
        payload.put("correlationId", correlationId);
        payload.put("occurredAt",    Instant.now().toString());
        return objectMapper.writeValueAsString(payload);
    }

    private String buildMemberWithdrawnPayload(String instMbrId,
                                                String correlationId) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        // QIM-OUTBOX-SPEC-001: MEMBER_WITHDRAWN (명칭 동일, 접두사 QIM_ 제거)
        payload.put("eventType",     "MEMBER_WITHDRAWN");
        payload.put("instMbrId",     instMbrId);
        payload.put("correlationId", correlationId);
        payload.put("occurredAt",    Instant.now().toString());
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * 회원 등록/전환 이벤트를 기관 webhook으로 통보하는 Outbox 적재
     *
     * <p>QIM-OUTBOX-SPEC-001 4종 이벤트(BIZ_MEMBER_CONVERTED 등)에 의해
     * {@link io.github.hipstermin.idem.hub.qim.sp.kafka.QimSpMemberEventHandler}에서 호출된다.
     * webhook payload의 eventType은 발신 이벤트 타입 그대로 전달하여
     * 기관이 BIZ/PERSONAL 여부를 직접 구분할 수 있도록 한다.
     *
     * @param instMbrId     기관 회원 ID
     * @param qimUserId     Q-IM 사용자 ID
     * @param eventType     BIZ_MEMBER_CONVERTED | BIZ_MEMBER_REGISTERED
     *                      | PERSONAL_MEMBER_CONVERTED | PERSONAL_MEMBER_REGISTERED
     * @param correlationId 추적 ID
     */
    @Transactional
    public void enqueueForMemberProvisioned(String instMbrId, String qimUserId,
                                             String eventType, String correlationId) {
        // webhook_config의 event_type_filter에 신규 이벤트 타입 등록 필요
        List<AgencyWebhookConfig> targets = findWebhookTargets(eventType, null);
        if (targets.isEmpty()) {
            log.info("[WebhookDispatcher] {} webhook 대상 없음: instMbrId={}", eventType, instMbrId);
            return;
        }

        String sourceEventId = UuidV7.generate();
        int enqueued = 0;
        for (AgencyWebhookConfig config : targets) {
            try {
                String payload = buildMemberProvisionedPayload(instMbrId, eventType, correlationId);
                boolean inserted = insertOutbox(config, sourceEventId, eventType,
                        "qim.user.events", payload, correlationId);
                if (inserted) enqueued++;
            } catch (Exception e) {
                log.error("[WebhookDispatcher] {} Outbox 실패: agencyCode={} error={}",
                        eventType, config.agencyCode(), e.getMessage());
            }
        }
        log.info("[WebhookDispatcher] {} Outbox 적재 완료: instMbrId={} targets={} enqueued={}",
                eventType, instMbrId, targets.size(), enqueued);
    }

    private String buildMemberProvisionedPayload(String instMbrId,
                                                  String eventType,
                                                  String correlationId) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType",     eventType);
        payload.put("instMbrId",     instMbrId);
        payload.put("correlationId", correlationId);
        payload.put("occurredAt",    Instant.now().toString());
        return objectMapper.writeValueAsString(payload);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════════════════════════════

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private String bytesToHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2]     = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    private int toInt(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 내부 VO
    // ═══════════════════════════════════════════════════════════════════════

    /** DB에서 읽은 기관 webhook 설정 */
    record AgencyWebhookConfig(
            String agencyCode,
            String endpointUrl,
            String signingSecretHash,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxRetry,
            int retryBackoffMs
    ) {}
}
