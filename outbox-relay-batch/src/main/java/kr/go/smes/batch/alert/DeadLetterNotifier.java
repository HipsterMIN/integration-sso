package kr.go.smes.batch.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEAD_LETTER 전환 알림 발송기 — Slack / PagerDuty 웹훅 지원
 *
 * <h2>설계 원칙</h2>
 * <ul>
 *   <li>알림 실패는 절대 서비스 흐름을 차단하지 않음 (비치명적)</li>
 *   <li>Slack / PagerDuty 모두 비활성화 시 로그만 남기고 통과</li>
 *   <li>두 채널 독립 발송 — 하나 실패해도 나머지 시도</li>
 *   <li>RestTemplate은 별도 Bean 없이 단순 new RestTemplate() 사용 (알림 전용 단순 호출)</li>
 * </ul>
 *
 * <h2>Slack 설정</h2>
 * Incoming Webhook URL: {@code BATCH_ALERT_SLACK_WEBHOOK_URL} 환경변수 주입
 * <pre>
 * batch:
 *   alert:
 *     slack:
 *       webhook-url: ${BATCH_ALERT_SLACK_WEBHOOK_URL:}
 *       enabled: ${BATCH_ALERT_SLACK_ENABLED:false}
 * </pre>
 *
 * <h2>PagerDuty 설정</h2>
 * Events API v2 사용: {@code BATCH_ALERT_PAGERDUTY_ROUTING_KEY} 환경변수 주입
 * <pre>
 * batch:
 *   alert:
 *     pagerduty:
 *       routing-key: ${BATCH_ALERT_PAGERDUTY_ROUTING_KEY:}
 *       enabled: ${BATCH_ALERT_PAGERDUTY_ENABLED:false}
 * </pre>
 *
 * <h2>사용 예시</h2>
 * <pre>{@code
 * notifier.notifyDeadLetter("agency-001", "prov-id-xyz", "5xx:503:Service Unavailable", 5);
 * }</pre>
 */
@Slf4j
@Component
public class DeadLetterNotifier {

    private static final String PAGERDUTY_EVENTS_URL =
            "https://events.pagerduty.com/v2/enqueue";

    private static final DateTimeFormatter KST_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    // ── Slack 설정 ────────────────────────────────────────────────────────────

    @Value("${batch.alert.slack.webhook-url:}")
    private String slackWebhookUrl;

    @Value("${batch.alert.slack.enabled:false}")
    private boolean slackEnabled;

    // ── PagerDuty 설정 ────────────────────────────────────────────────────────

    @Value("${batch.alert.pagerduty.routing-key:}")
    private String pagerdutyRoutingKey;

    @Value("${batch.alert.pagerduty.enabled:false}")
    private boolean pagerdutyEnabled;

    // ── 알림 발송 (공통 진입점) ───────────────────────────────────────────────

    /**
     * DEAD_LETTER 전환 알림 발송
     *
     * @param agencyCode  기관 코드
     * @param recordId    provisioning_outbox.id
     * @param errorReason 에러 원인 문자열 (escalateOrDead에서 전달)
     * @param retryCount  최종 retry_count
     */
    public void notifyDeadLetter(String agencyCode, String recordId,
                                 String errorReason, int retryCount) {
        String timestamp = KST_FMT.format(Instant.now());

        log.error("[DeadLetterNotifier] ☠️ DEAD_LETTER 알림 발송: agency={} id={} retry={} error={}",
                agencyCode, recordId, retryCount, errorReason);

        // 두 채널 독립 발송 (하나 실패해도 나머지 시도)
        sendSlack(agencyCode, recordId, errorReason, retryCount, timestamp);
        sendPagerDuty(agencyCode, recordId, errorReason, retryCount, timestamp);
    }

    // ── Slack ─────────────────────────────────────────────────────────────────

    private void sendSlack(String agencyCode, String recordId,
                           String errorReason, int retryCount, String timestamp) {
        if (!slackEnabled || slackWebhookUrl == null || slackWebhookUrl.isBlank()) {
            log.debug("[DeadLetterNotifier] Slack 알림 비활성 또는 URL 미설정 — 건너뜀");
            return;
        }

        try {
            Map<String, Object> payload = buildSlackPayload(
                    agencyCode, recordId, errorReason, retryCount, timestamp);

            RestTemplate rt = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = rt.postForEntity(
                    slackWebhookUrl,
                    new HttpEntity<>(payload, headers),
                    String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[DeadLetterNotifier] Slack 알림 발송 완료: agency={} id={}", agencyCode, recordId);
            } else {
                log.warn("[DeadLetterNotifier] Slack 알림 실패: status={}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.warn("[DeadLetterNotifier] Slack 알림 발송 오류 (비치명적): agency={} error={}",
                    agencyCode, e.getMessage());
        }
    }

    /**
     * Slack Block Kit 메시지 구성
     *
     * <pre>
     * ☠️ [DEAD_LETTER] 프로비저닝 전달 최종 실패
     * ─────────────────────────────────────
     * 기관코드  : AGENCY_001
     * 레코드 ID : prov-uuid-xxx
     * 에러 원인 : 5xx:503:Service Unavailable
     * 재시도 횟수: 5회
     * 발생 시각  : 2026-05-17 14:30:00 (KST)
     * ─────────────────────────────────────
     * 조치 필요: ido.provisioning_outbox WHERE id='...' 확인
     * </pre>
     */
    private Map<String, Object> buildSlackPayload(String agencyCode, String recordId,
                                                   String errorReason, int retryCount,
                                                   String timestamp) {
        String headerText = "☠️ [DEAD_LETTER] 프로비저닝 전달 최종 실패";
        String body = String.format(
                "*기관 코드*: `%s`\n" +
                "*레코드 ID*: `%s`\n" +
                "*에러 원인*: `%s`\n" +
                "*재시도 횟수*: %d회\n" +
                "*발생 시각*: %s (KST)",
                agencyCode, recordId, errorReason, retryCount, timestamp);
        String footer = String.format(
                ":wrench: 조치 필요: `ido.provisioning_outbox WHERE id = '%s'` 확인", recordId);

        Map<String, Object> headerSection = new LinkedHashMap<>();
        headerSection.put("type", "header");
        headerSection.put("text", Map.of("type", "plain_text", "text", headerText, "emoji", true));

        Map<String, Object> bodySection = new LinkedHashMap<>();
        bodySection.put("type", "section");
        bodySection.put("text", Map.of("type", "mrkdwn", "text", body));

        Map<String, Object> divider = Map.of("type", "divider");

        Map<String, Object> footerSection = new LinkedHashMap<>();
        footerSection.put("type", "section");
        footerSection.put("text", Map.of("type", "mrkdwn", "text", footer));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("blocks", List.of(headerSection, divider, bodySection, divider, footerSection));
        return payload;
    }

    // ── PagerDuty ─────────────────────────────────────────────────────────────

    private void sendPagerDuty(String agencyCode, String recordId,
                               String errorReason, int retryCount, String timestamp) {
        if (!pagerdutyEnabled || pagerdutyRoutingKey == null || pagerdutyRoutingKey.isBlank()) {
            log.debug("[DeadLetterNotifier] PagerDuty 알림 비활성 또는 Routing Key 미설정 — 건너뜀");
            return;
        }

        try {
            Map<String, Object> payload = buildPagerDutyPayload(
                    agencyCode, recordId, errorReason, retryCount, timestamp);

            RestTemplate rt = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = rt.postForEntity(
                    PAGERDUTY_EVENTS_URL,
                    new HttpEntity<>(payload, headers),
                    String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("[DeadLetterNotifier] PagerDuty 인시던트 생성 완료: agency={} id={}", agencyCode, recordId);
            } else {
                log.warn("[DeadLetterNotifier] PagerDuty 알림 실패: status={}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.warn("[DeadLetterNotifier] PagerDuty 알림 발송 오류 (비치명적): agency={} error={}",
                    agencyCode, e.getMessage());
        }
    }

    /**
     * PagerDuty Events API v2 페이로드 구성
     *
     * <p>severity=critical, dedup_key로 동일 레코드 중복 인시던트 방지.
     *
     * @see <a href="https://developer.pagerduty.com/api-reference/b3A6Mjc0ODI2Nw-send-an-event-to-pager-duty">
     *     PagerDuty Events API v2</a>
     */
    private Map<String, Object> buildPagerDutyPayload(String agencyCode, String recordId,
                                                       String errorReason, int retryCount,
                                                       String timestamp) {
        // dedup_key: 동일 레코드 재호출 시 중복 인시던트 생성 방지
        String dedupKey = "provisioning-dead-letter:" + recordId;

        Map<String, Object> pdPayload = new LinkedHashMap<>();
        pdPayload.put("agency_code",  agencyCode);
        pdPayload.put("record_id",    recordId);
        pdPayload.put("error_reason", errorReason);
        pdPayload.put("retry_count",  retryCount);
        pdPayload.put("occurred_at",  timestamp);
        pdPayload.put("action_required",
                "ido.provisioning_outbox WHERE id='" + recordId + "' 확인 후 수동 처리 또는 재큐잉 필요");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("routing_key",  pagerdutyRoutingKey);
        event.put("event_action", "trigger");
        event.put("dedup_key",    dedupKey);
        event.put("payload", Map.of(
                "summary",   String.format("[DEAD_LETTER] 프로비저닝 전달 실패 — agency=%s id=%s",
                        agencyCode, recordId),
                "source",    "outbox-relay-batch",
                "severity",  "critical",
                "timestamp", timestamp,
                "custom_details", pdPayload
        ));
        event.put("links", List.of(
                Map.of("href", "https://onepass.go.kr/ops/provisioning-outbox",
                       "text", "OnePass 운영 대시보드")
        ));

        return event;
    }
}
