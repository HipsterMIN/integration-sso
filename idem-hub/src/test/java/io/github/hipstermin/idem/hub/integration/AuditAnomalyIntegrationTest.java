package io.github.hipstermin.idem.hub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.audit.anomaly.AnomalyRules;
import io.github.hipstermin.idem.hub.audit.anomaly.AuditAnomalyScorer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 1.1 감사 이상 탐지(관찰 모드) — 감사 행 적재 → 점수기(커서·배치) → 플래그 → 관리 API 목록·통계 → 검토 → 감사.
 * 스케줄러는 꺼져 있고(IntegrationTestBase) scoreOnce() 를 직접 부른다. 첫 호출은 커서를 현재 최대 audit_id 에 맞추고 0 을 돌려준다(소급 없음).
 */
@DisplayName("1.1 감사 이상 탐지 — 커서·규칙·플래그·관리 API·검토 통합 테스트")
class AuditAnomalyIntegrationTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditAnomalyScorer scorer;
    @Autowired ObjectMapper om;

    private String url(String p) { return "http://localhost:" + port + p; }

    private void audit(String cat, String action, String actorType, String actor, String agency, String ip, String outcome, String detail, Instant at) {
        jdbc.update("INSERT INTO idem_hub.audit_log (audit_id, event_category, event_action, actor_type, actor_id, agency_code, source_ip, "
                        + "correlation_id, source_system, outcome, outcome_detail, metadata, kafka_published, occurred_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,false,?)",
                UuidV7.generate(), cat, action, actorType, actor, agency, ip, "it-anomaly", "idem-hub", outcome, detail, Timestamp.from(at));
    }

    @Test
    @DisplayName("로그인 실패 버스트 → 플래그 1건(축당 하나) · 재검증 반복 → TICKET_REPLAY · 목록/통계 · 검토 → 정밀도 · 감사 ANOMALY_REVIEWED")
    void endToEnd() throws Exception {
        // 커서 초기화 — 지금까지의 감사 행은 소급하지 않는다
        scorer.scoreOnce();
        jdbc.update("DELETE FROM idem_hub.audit_anomaly_flag");
        Instant base = Instant.now().minusSeconds(60);

        // ① 같은 계정의 로그인 실패 7건(10분 창) — 임계 5 를 넘는 6·7번째 행이 후보지만 플래그는 첫 초과에서 한 번만
        for (int i = 0; i < 7; i++) audit("ADMIN", "ADMIN_LOGIN_FAILED", "ADMIN", "it-burst", null, "10.9.9.9", "FAILURE", "bad password", base.plusSeconds(i));
        // ② 기관 AG_REPLAY 의 티켓 재검증 실패 3건
        for (int i = 0; i < 3; i++) audit("HANDOFF", "HANDOFF_VERIFIED", "AGENCY", null, "AG_REPLAY_IT", null, "FAILURE", "TICKET_CONSUMED", base.plusSeconds(10 + i));
        // ③ 정상 행 — 플래그 없음
        audit("HANDOFF", "HANDOFF_VERIFIED", "AGENCY", null, "AG_REPLAY_IT", null, "SUCCESS", null, base.plusSeconds(20));

        int scanned = scorer.scoreOnce();
        assertThat(scanned).isEqualTo(11);
        assertThat(scorer.scoreOnce()).isZero();   // 커서가 앞으로 갔다

        List<Map<String, Object>> flags = jdbc.queryForList("SELECT rule, severity, score, subject, review FROM idem_hub.audit_anomaly_flag ORDER BY rule");
        assertThat(flags).hasSize(2);
        assertThat(flags).extracting(f -> f.get("rule")).containsExactly(AnomalyRules.R_LOGIN_BURST, AnomalyRules.R_TICKET_REPLAY);
        Map<String, Object> burst = flags.get(0);
        assertThat(burst.get("subject")).isEqualTo("it-burst");
        assertThat(((Number) burst.get("score")).intValue()).isEqualTo(50);      // 5번째 실패에서 count=5 → 50 (MEDIUM)
        assertThat(burst.get("severity")).isEqualTo("MEDIUM");
        assertThat(flags.get(1).get("severity")).isEqualTo("HIGH");
        Map<String, Object> cursor = jdbc.queryForMap("SELECT scanned_total, last_occurred_at FROM idem_hub.audit_anomaly_cursor WHERE id = 1");
        assertThat(((Number) cursor.get("scanned_total")).longValue()).isGreaterThanOrEqualTo(11);
        assertThat(cursor.get("last_occurred_at")).isNotNull();

        // 관리 API — 목록(규칙 필터)·통계
        HttpHeaders h = withAdmin(new HttpHeaders(), rest, url(""));
        ResponseEntity<String> list = rest.exchange(url("/api/v1/admin/anomalies?rule=TICKET_REPLAY&review=UNREVIEWED"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        JsonNode lj = om.readTree(list.getBody());
        assertThat(lj.path("total").asInt()).isEqualTo(1);
        String flagId = lj.path("items").get(0).path("flagId").asText();
        assertThat(lj.path("items").get(0).path("agencyCode").asText()).isEqualTo("AG_REPLAY_IT");
        assertThat(lj.path("items").get(0).path("details").asText().replace(" ", "")).contains("\"count\":3");   // jsonb 는 공백을 넣어 돌려준다

        ResponseEntity<String> stats = rest.exchange(url("/api/v1/admin/anomalies/stats?days=7"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(stats.getStatusCode().value()).isEqualTo(200);
        JsonNode sj = om.readTree(stats.getBody());
        JsonNode replayStat = null;
        for (JsonNode r : sj.path("byRule")) if (AnomalyRules.R_TICKET_REPLAY.equals(r.path("rule").asText())) replayStat = r;
        assertThat(replayStat).isNotNull();
        assertThat(replayStat.path("total").asLong()).isEqualTo(1);
        assertThat(replayStat.path("unreviewed").asLong()).isEqualTo(1);
        assertThat(replayStat.path("precision").isNull()).isTrue();
        assertThat(sj.path("cursor").path("scannedTotal").asLong()).isGreaterThanOrEqualTo(11);

        // 검토 → 정밀도 1.0 → 감사 ANOMALY_REVIEWED
        HttpHeaders jh = new HttpHeaders(); jh.addAll(h); jh.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> rv = rest.exchange(url("/api/v1/admin/anomalies/" + flagId + "/review"), HttpMethod.POST,
                new HttpEntity<>("{\"verdict\":\"TRUE_POSITIVE\",\"note\":\"재사용 시도 확인\"}", jh), String.class);
        assertThat(rv.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> bad = rest.exchange(url("/api/v1/admin/anomalies/" + flagId + "/review"), HttpMethod.POST,
                new HttpEntity<>("{\"verdict\":\"MAYBE\"}", jh), String.class);
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        JsonNode sj2 = om.readTree(rest.exchange(url("/api/v1/admin/anomalies/stats?days=7"), HttpMethod.GET, new HttpEntity<>(h), String.class).getBody());
        for (JsonNode r : sj2.path("byRule")) if (AnomalyRules.R_TICKET_REPLAY.equals(r.path("rule").asText())) {
            assertThat(r.path("truePositive").asLong()).isEqualTo(1);
            assertThat(r.path("precision").asDouble()).isEqualTo(1.0);
        }
        Thread.sleep(1500);   // 감사 발행은 비동기
        Long reviewed = jdbc.queryForObject("SELECT COUNT(*) FROM idem_hub.audit_log WHERE event_action = 'ANOMALY_REVIEWED' AND resource_id = ?", Long.class, flagId);
        assertThat(reviewed).isEqualTo(1);
    }
}
