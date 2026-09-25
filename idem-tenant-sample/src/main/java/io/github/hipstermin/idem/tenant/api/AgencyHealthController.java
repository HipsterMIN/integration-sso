package io.github.hipstermin.idem.tenant.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * 기관 스텁 진단 / 헬스체크 컨트롤러
 *
 * <p><b>목적</b>: 운영자 및 테스트팀이 agency-stub 의 상태를
 * 단일 엔드포인트로 확인할 수 있게 합니다.
 *
 * <p><b>엔드포인트</b>:
 * <ul>
 *   <li>{@code GET /api/v1/health}         — 전체 헬스 요약 (OK / DEGRADED / DOWN)</li>
 *   <li>{@code GET /api/v1/health/db}      — DB 연결 및 핵심 테이블 상태</li>
 *   <li>{@code GET /api/v1/health/ido}     — IdO 서비스 연결 상태</li>
 *   <li>{@code GET /api/v1/health/webhook} — Webhook 수신 현황 (최근 1시간)</li>
 *   <li>{@code GET /api/v1/health/queue}   — 이벤트 큐 현황 (미배달 수)</li>
 * </ul>
 *
 * <p><b>응답 상태</b>:
 * <ul>
 *   <li>{@code "status": "UP"}       — 정상</li>
 *   <li>{@code "status": "DEGRADED"} — 일부 기능 저하 (DB OK, IdO 연결 불가 등)</li>
 *   <li>{@code "status": "DOWN"}     — 주요 기능 불가 (DB 연결 실패 등)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
public class AgencyHealthController {

    private final JdbcTemplate jdbcTemplate;

    @Value("${idem.sample.code:AGENCY_STUB_001}")
    private String agencyCode;

    @Value("${idem.sample.ido.base-url:http://localhost:8083}")
    private String idoBaseUrl;

    // ════════════════════════════════════════════════════════════════════════
    // 전체 헬스 요약
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/health
     * 전체 헬스 요약 — DB + IdO + 큐 상태 집계
     */
    @GetMapping
    public ResponseEntity<?> overall() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service",    "idem-tenant-sample");
        result.put("agencyCode", agencyCode);
        result.put("timestamp",  Instant.now().toString());

        boolean dbOk  = checkDbQuick();
        boolean idoOk = checkIdoQuick();

        Map<String, String> components = new LinkedHashMap<>();
        components.put("db",  dbOk  ? "UP" : "DOWN");
        components.put("hub", idoOk ? "UP" : "DEGRADED");
        result.put("components", components);

        String overallStatus;
        if (!dbOk) {
            overallStatus = "DOWN";
        } else if (!idoOk) {
            overallStatus = "DEGRADED";
        } else {
            overallStatus = "UP";
        }
        result.put("status", overallStatus);

        int httpStatus = overallStatus.equals("DOWN") ? 503 : 200;
        return ResponseEntity.status(httpStatus).body(result);
    }

    // ════════════════════════════════════════════════════════════════════════
    // DB 진단
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/health/db
     * DB 연결 + 핵심 테이블 레코드 수 조회
     */
    @GetMapping("/db")
    public ResponseEntity<?> db() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        try {
            // 기본 연결 테스트
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            result.put("connection", "OK");

            // 테이블별 레코드 수
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("agency_user",          safeCount("SELECT COUNT(*) FROM agency_stub.agency_user WHERE agency_code = ?", agencyCode));
            counts.put("agency_local_session",  safeCount("SELECT COUNT(*) FROM agency_stub.agency_local_session WHERE agency_code = ?", agencyCode));
            counts.put("active_sessions",       safeCount("SELECT COUNT(*) FROM agency_stub.agency_local_session WHERE agency_code = ? AND invalidated_at IS NULL AND idle_expires_at > NOW()", agencyCode));
            counts.put("agency_api_key",        safeCount("SELECT COUNT(*) FROM agency_stub.agency_api_key WHERE agency_code = ? AND active = TRUE", agencyCode));
            counts.put("webhook_inbound_total", safeCount("SELECT COUNT(*) FROM agency_stub.webhook_inbound WHERE agency_code = ?", agencyCode));
            counts.put("webhook_inbound_1h",    safeCount("SELECT COUNT(*) FROM agency_stub.webhook_inbound WHERE agency_code = ? AND received_at > NOW() - INTERVAL '1 hour'", agencyCode));
            counts.put("event_queue_pending",   safeCount("SELECT COUNT(*) FROM agency_stub.agency_event_queue WHERE agency_code = ? AND delivered = FALSE", agencyCode));
            result.put("tableCounts", counts);
            result.put("status", "UP");

        } catch (Exception e) {
            log.warn("[AgencyHealth] DB 진단 실패: {}", e.getMessage());
            result.put("connection", "FAILED");
            result.put("error",      e.getMessage());
            result.put("status",     "DOWN");
            return ResponseEntity.status(503).body(result);
        }

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════════
    // IdO 연결 진단
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/health/ido
     * IdO actuator/health 를 조회해 연결 상태 확인
     */
    @GetMapping("/ido")
    public ResponseEntity<?> ido() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idoBaseUrl", idoBaseUrl);
        result.put("timestamp",  Instant.now().toString());

        String healthUrl = idoBaseUrl + "/actuator/health";
        long startMs = System.currentTimeMillis();

        try {
            RestTemplate rt = new RestTemplate();
            var resp = rt.getForEntity(healthUrl, Map.class);
            long elapsed = System.currentTimeMillis() - startMs;

            result.put("status",    "UP");
            result.put("httpCode",  resp.getStatusCode().value());
            result.put("elapsedMs", elapsed);

            if (resp.getBody() != null) {
                result.put("idoStatus", resp.getBody().get("status"));
            }

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[AgencyHealth] IdO 연결 실패: url={} err={}", healthUrl, e.getMessage());
            result.put("status",    "UNREACHABLE");
            result.put("error",     e.getMessage());
            result.put("elapsedMs", elapsed);
            return ResponseEntity.status(503).body(result);
        }

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Webhook 수신 현황
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/health/webhook
     * 최근 1시간 Webhook 수신 통계
     */
    @GetMapping("/webhook")
    public ResponseEntity<?> webhook() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        try {
            List<Map<String, Object>> byStatus = jdbcTemplate.queryForList(
                    "SELECT processing_status, COUNT(*) AS cnt " +
                    "FROM agency_stub.webhook_inbound " +
                    "WHERE agency_code = ? AND received_at > NOW() - INTERVAL '1 hour' " +
                    "GROUP BY processing_status",
                    agencyCode);
            result.put("last1hByStatus", byStatus);

            List<Map<String, Object>> byType = jdbcTemplate.queryForList(
                    "SELECT source_event_type, COUNT(*) AS cnt " +
                    "FROM agency_stub.webhook_inbound " +
                    "WHERE agency_code = ? AND received_at > NOW() - INTERVAL '1 hour' " +
                    "GROUP BY source_event_type ORDER BY cnt DESC",
                    agencyCode);
            result.put("last1hByType", byType);

            // 서명 실패 수
            long sigFail = jdbcTemplate.queryForList(
                    "SELECT 1 FROM agency_stub.webhook_inbound " +
                    "WHERE agency_code = ? AND signature_valid = FALSE " +
                    "  AND received_at > NOW() - INTERVAL '1 hour'",
                    agencyCode).size();
            result.put("signatureFailures1h", sigFail);

            result.put("status", "OK");

        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error",   e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 이벤트 큐 현황
    // ════════════════════════════════════════════════════════════════════════

    /**
     * GET /api/v1/health/queue
     * 이벤트 큐 미배달 수 + 우선순위별 분포
     */
    @GetMapping("/queue")
    public ResponseEntity<?> queue() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        try {
            long pending = safeCount(
                    "SELECT COUNT(*) FROM agency_stub.agency_event_queue " +
                    "WHERE agency_code = ? AND delivered = FALSE", agencyCode);
            result.put("pendingCount", pending);

            List<Map<String, Object>> byPriority = jdbcTemplate.queryForList(
                    "SELECT priority, COUNT(*) AS cnt " +
                    "FROM agency_stub.agency_event_queue " +
                    "WHERE agency_code = ? AND delivered = FALSE " +
                    "GROUP BY priority ORDER BY priority",
                    agencyCode);
            result.put("pendingByPriority", byPriority);

            List<Map<String, Object>> byType = jdbcTemplate.queryForList(
                    "SELECT event_type, COUNT(*) AS cnt " +
                    "FROM agency_stub.agency_event_queue " +
                    "WHERE agency_code = ? AND delivered = FALSE " +
                    "GROUP BY event_type ORDER BY cnt DESC LIMIT 10",
                    agencyCode);
            result.put("pendingByType", byType);

            result.put("status", pending > 100 ? "DEGRADED" : "OK");

        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error",   e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private boolean checkDbQuick() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkIdoQuick() {
        try {
            RestTemplate rt = new RestTemplate();
            var resp = rt.getForEntity(idoBaseUrl + "/actuator/health", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    private long safeCount(String sql, Object... args) {
        try {
            Long cnt = jdbcTemplate.queryForObject(sql, Long.class, args);
            return cnt != null ? cnt : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
