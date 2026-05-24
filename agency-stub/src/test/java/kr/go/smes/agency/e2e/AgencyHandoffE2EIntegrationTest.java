package kr.go.smes.agency.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.agency.e2e.MockIdoVerifyController.ScenarioOutcome;
import kr.go.smes.common.domain.AuthResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agency-Stub ↔ IdO ↔ Q-Sign ↔ Handoff 모듈 간 E2E 통합 테스트.
 *
 * <p>검증 대상 사슬:
 * <pre>
 *   [1] agency-stub /agency/entry?ticketId=...
 *           ↓ (X-Agency-Code / X-Agency-Key 인증 후)
 *   [2] IdoVerifyClient ── POST → /api/v1/handoff/verify
 *           ↓ (MockIdoVerifyController 가 q-sign 인증 결과를 임베드한 HandoffPayload 반환)
 *   [3] HandoffPayload state/authLevel 분기 처리
 *           ↓
 *   [4] AgencySessionService.createSession (DB INSERT)
 *           ↓
 *   [5] AGSID 쿠키 발급 + 응답 (agencyUserId / authLevel / sessionId)
 *           ↓
 *   [6] GET /agency/entry/session — 발급된 AGSID 로 세션 검증
 * </pre>
 *
 * <p>각 시나리오는 실제 PostgreSQL(Testcontainers) + agency-stub 의 모든 컴포넌트를
 * 가동한 상태에서 수행되며, ido 응답만 동일 컨텍스트 내 {@link MockIdoVerifyController}
 * 로 라우팅된다 (self port trick — {@link AgencyStubE2EPortRegistry}).
 */
@DisplayName("E2E | agency-stub → ido → q-sign → ido → handoff → agency 흐름")
class AgencyHandoffE2EIntegrationTest extends E2EIntegrationTestBase {

    private static final String AGENCY_CODE   = "AGENCY_STUB_001";
    private static final String AGENCY_KEY    = "e2e-test-agency-key";   // application-e2e-test.yml 와 일치
    private static final String COOKIE_NAME   = "AGSID";
    private static final String ENTRY_PATH    = "/agency/entry";
    private static final String SESSION_PATH  = "/agency/entry/session";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper     objectMapper;
    @Autowired private JdbcTemplate     jdbcTemplate;

    @BeforeEach
    void resetMockIdo() {
        MockIdoVerifyController.resetPrograms();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. 정상 흐름 — Happy Path (APPROVED)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("APPROVED — 정상 SSO 흐름 한 컷")
    class ApprovedFlow {

        @Test
        @DisplayName("agency 진입 → ido verify(APPROVED) → 세션 생성 → AGSID 발급 → 세션 조회 성공")
        void fullChain_approved_endToEnd() {
            // ── given: q-sign 이 NICE 로 L2 인증을 완료한 사용자의 ticket ──────────
            String ticketId = "ticket-e2e-" + UUID.randomUUID();
            String qimUserId = "qim-user-" + UUID.randomUUID();
            ScenarioOutcome scenario =
                    ScenarioOutcome.approvedFor(qimUserId, AuthResult.AuthLevel.L2, "NICE");
            MockIdoVerifyController.programTicket(ticketId, scenario);

            // ── when [1~5]: agency 진입 ─────────────────────────────────────────
            ResponseEntity<String> entryResp = postEntry(ticketId, "cid-approved-1");

            // ── then [1~5]: 200 + AGSID 쿠키 + 본문 필드 ────────────────────────
            assertThat(entryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            String agsid = extractAgsidCookie(entryResp);
            assertThat(agsid).as("AGSID 쿠키 발급").isNotBlank();

            Map<String, Object> body = parseBody(entryResp);
            assertThat(body)
                    .containsKey("agencyUserId")
                    .containsKey("agencySubjectId")
                    .containsKey("sessionId")
                    .containsEntry("authLevel", "L2")
                    .containsEntry("correlationId", "cid-approved-1");

            // ido verify 가 정확히 1회 호출되었고, X-Agency-Code/Key 가 전파되었음
            assertThat(MockIdoVerifyController.callCountFor(ticketId)).isEqualTo(1);
            assertThat(MockIdoVerifyController.lastAgencyCode()).isEqualTo(AGENCY_CODE);
            assertThat(MockIdoVerifyController.lastAgencyKey()).isEqualTo(AGENCY_KEY);
            assertThat(MockIdoVerifyController.lastCorrelationId()).isEqualTo("cid-approved-1");

            // DB 사이드 이펙트: agency_local_session 1행 (agency_user 와 JOIN 으로 qimUserId 검증)
            Integer sessionRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_local_session s " +
                    "INNER JOIN agency_stub.agency_user u ON s.agency_user_id = u.agency_user_id " +
                    "WHERE u.agency_code = ? AND u.qim_user_id = ? AND s.invalidated_at IS NULL",
                    Integer.class, AGENCY_CODE, qimUserId);
            assertThat(sessionRows).isEqualTo(1);

            // ── when [6]: 세션 조회 ─────────────────────────────────────────────
            ResponseEntity<String> sessionResp = getSession(agsid, "cid-approved-2");

            // ── then [6]: 200 + qimUserId 일치 ──────────────────────────────────
            assertThat(sessionResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> sessionBody = parseBody(sessionResp);
            assertThat(sessionBody)
                    .containsEntry("authLevel", "L2")
                    .containsEntry("qimUserId", qimUserId);
        }

        @Test
        @DisplayName("동일 ticketId 두 번 호출 → 두 번째도 200 (ido 가 멱등 응답 — 새 AGSID 세션 발급)")
        void approved_repeatedTicket_issuesFreshSession() {
            String ticketId = "ticket-repeat-" + UUID.randomUUID();
            ScenarioOutcome scenario = ScenarioOutcome.approvedFor(
                    "qim-repeat-" + UUID.randomUUID(),
                    AuthResult.AuthLevel.L1, "NICE");
            MockIdoVerifyController.programTicket(ticketId, scenario);

            ResponseEntity<String> first  = postEntry(ticketId, "cid-rep-1");
            ResponseEntity<String> second = postEntry(ticketId, "cid-rep-2");

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

            // 두 번 호출되었음 (idempotency 책임은 ido 측, agency-stub 는 매번 새 세션 발급)
            assertThat(MockIdoVerifyController.callCountFor(ticketId)).isEqualTo(2);

            String agsid1 = extractAgsidCookie(first);
            String agsid2 = extractAgsidCookie(second);
            assertThat(agsid1).isNotBlank();
            assertThat(agsid2).isNotBlank();
            // 두 번째 호출에서도 새 AGSID 가 발급되어야 한다 (rotation)
            assertThat(agsid1).isNotEqualTo(agsid2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. 거절 / 일시 오류 흐름
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("REJECTED / HOLD — 부정/일시 오류 분기")
    class FailureFlow {

        @Test
        @DisplayName("ido verify(REJECTED) → 403 + 세션 미발급")
        void rejected_returns403_noSession() {
            String ticketId = "ticket-rejected-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId, ScenarioOutcome.rejected());

            ResponseEntity<String> resp = postEntry(ticketId, "cid-rej-1");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            Map<String, Object> body = parseBody(resp);
            assertThat(body)
                    .containsEntry("error", "HANDOFF_REJECTED")
                    .containsEntry("state", "REJECTED");

            // 쿠키 미발급
            assertThat(extractAgsidCookie(resp)).isNull();

            // DB 세션 미생성 — 본 ticketId 로 만들어진 세션 행은 0건이어야 한다
            Integer thisTicketRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_local_session WHERE ticket_id = ?",
                    Integer.class, ticketId);
            assertThat(thisTicketRows).isZero();
        }

        @Test
        @DisplayName("ido verify(HOLD) → 503 + 재시도 안내 + 세션 미발급")
        void hold_returns503_noSession() {
            String ticketId = "ticket-hold-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId, ScenarioOutcome.hold());

            ResponseEntity<String> resp = postEntry(ticketId, "cid-hold-1");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            Map<String, Object> body = parseBody(resp);
            assertThat(body).containsEntry("error", "SERVICE_TEMPORARILY_UNAVAILABLE");
            assertThat(extractAgsidCookie(resp)).isNull();
        }

        @Test
        @DisplayName("ido 가 5xx 반환 (Retry/Fallback 동작) → 503 VERIFY_UNAVAILABLE 또는 HOLD fallback")
        void ido5xx_triggersFallback_503() {
            String ticketId = "ticket-5xx-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId, ScenarioOutcome.http500());

            ResponseEntity<String> resp = postEntry(ticketId, "cid-5xx-1");

            // IdoVerifyClient 의 fallback 이 HOLD payload 를 반환하면 AgencyEntryController 가 503 변환
            // 또는 CB OPEN 이전에 예외 전파되면 503 VERIFY_UNAVAILABLE
            assertThat(resp.getStatusCode().value()).isEqualTo(503);
            assertThat(extractAgsidCookie(resp)).isNull();

            // 호출은 1회 (max-attempts=1) — 재시도 폭주 방지를 위해 e2e 프로파일은 1회로 제한
            assertThat(MockIdoVerifyController.callCountFor(ticketId)).isEqualTo(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. GUEST 흐름 (기관 매핑 없는 인증 사용자)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GUEST — Q-IM UUID 만 있고 기관 매핑이 없는 경우")
    class GuestFlow {

        @Test
        @DisplayName("ido verify(GUEST) → 200 GUEST 응답 + 쿠키 미발급 + 회원 연결 안내")
        void guest_returns200WithoutCookie() {
            String ticketId = "ticket-guest-" + UUID.randomUUID();
            String qimUserId = "qim-guest-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId,
                    ScenarioOutcome.guestFor(qimUserId, AuthResult.AuthLevel.L1));

            ResponseEntity<String> resp = postEntry(ticketId, "cid-guest-1");

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> body = parseBody(resp);
            assertThat(body)
                    .containsEntry("state", "GUEST")
                    .containsEntry("qimUserId", qimUserId);
            // AGSID 미발급 — GUEST 는 기관 세션을 만들지 않음
            assertThat(extractAgsidCookie(resp)).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. 인증/인가 헤더 검증 — API Key 가드
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agency API Key 인터셉터 — 인증 헤더 가드")
    class ApiKeyGuard {

        @Test
        @DisplayName("X-Agency-Key 누락 → 401 MISSING_API_KEY + ido 호출 없음")
        void missingApiKey_returns401_noIdoCall() {
            String ticketId = "ticket-noauth-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId, ScenarioOutcome.defaultApproved());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Agency-Code", AGENCY_CODE);
            // X-Agency-Key 의도적으로 누락
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl() + ENTRY_PATH + "?ticketId=" + ticketId,
                    HttpMethod.POST,
                    new HttpEntity<>("", headers),
                    String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(resp.getBody()).contains("MISSING_API_KEY");
            // 인터셉터에서 차단되어 ido 호출 발생 안 함
            assertThat(MockIdoVerifyController.callCountFor(ticketId)).isZero();
        }

        @Test
        @DisplayName("잘못된 X-Agency-Key → 401 INVALID_API_KEY + ido 호출 없음")
        void invalidApiKey_returns401() {
            String ticketId = "ticket-wrongkey-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId, ScenarioOutcome.defaultApproved());

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Agency-Code", AGENCY_CODE);
            headers.set("X-Agency-Key",  "absolutely-wrong-key-zzz");
            ResponseEntity<String> resp = restTemplate.exchange(
                    baseUrl() + ENTRY_PATH + "?ticketId=" + ticketId,
                    HttpMethod.POST,
                    new HttpEntity<>("", headers),
                    String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(resp.getBody()).contains("INVALID_API_KEY");
            assertThat(MockIdoVerifyController.callCountFor(ticketId)).isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. 세션 라이프사이클 — Session Fixation 방지 + 로그아웃
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("세션 라이프사이클 — Fixation 방지 + 로그아웃")
    class SessionLifecycle {

        @Test
        @DisplayName("기존 AGSID 가진 사용자가 새 ticket 으로 재진입 → 기존 세션 무효화 + 새 AGSID 발급")
        void reentry_invalidatesOldSession() {
            // 첫 진입 — 세션 1 발급
            String ticket1 = "ticket-fix-1-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticket1,
                    ScenarioOutcome.approvedFor("qim-fix-" + UUID.randomUUID(),
                            AuthResult.AuthLevel.L1, "NICE"));
            ResponseEntity<String> first = postEntry(ticket1, "cid-fix-1");
            String agsid1 = extractAgsidCookie(first);
            assertThat(agsid1).isNotBlank();

            // 두 번째 진입 — 기존 AGSID 쿠키를 제출하면 무효화되고 새 AGSID 발급
            String ticket2 = "ticket-fix-2-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticket2,
                    ScenarioOutcome.approvedFor("qim-fix-2-" + UUID.randomUUID(),
                            AuthResult.AuthLevel.L2, "NICE"));

            HttpHeaders headers = baseEntryHeaders("cid-fix-2");
            headers.add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + agsid1);
            ResponseEntity<String> second = restTemplate.exchange(
                    baseUrl() + ENTRY_PATH + "?ticketId=" + ticket2,
                    HttpMethod.POST,
                    new HttpEntity<>("", headers),
                    String.class);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            String agsid2 = extractAgsidCookie(second);
            assertThat(agsid2).isNotBlank().isNotEqualTo(agsid1);

            // 기존 AGSID 로 세션 조회 → 401 (무효화됨)
            ResponseEntity<String> oldSession = getSession(agsid1, "cid-fix-3");
            assertThat(oldSession.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            // 새 AGSID 로 세션 조회 → 200
            ResponseEntity<String> newSession = getSession(agsid2, "cid-fix-4");
            assertThat(newSession.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("로그아웃 후 동일 AGSID 로 세션 조회 → 401")
        void logout_invalidatesSession() {
            String ticketId = "ticket-logout-" + UUID.randomUUID();
            MockIdoVerifyController.programTicket(ticketId,
                    ScenarioOutcome.approvedFor("qim-logout-" + UUID.randomUUID(),
                            AuthResult.AuthLevel.L1, "NICE"));
            ResponseEntity<String> entryResp = postEntry(ticketId, "cid-logout-1");
            String agsid = extractAgsidCookie(entryResp);
            assertThat(agsid).isNotBlank();

            // 로그아웃
            HttpHeaders headers = baseEntryHeaders("cid-logout-2");
            headers.add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + agsid);
            ResponseEntity<String> logoutResp = restTemplate.exchange(
                    baseUrl() + SESSION_PATH,
                    HttpMethod.DELETE,
                    new HttpEntity<>(headers),
                    String.class);
            assertThat(logoutResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(parseBody(logoutResp)).containsEntry("status", "LOGGED_OUT");

            // 동일 AGSID 로 세션 조회 → 401
            ResponseEntity<String> sessionAfter = getSession(agsid, "cid-logout-3");
            assertThat(sessionAfter.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HTTP 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpHeaders baseEntryHeaders(String correlationId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Agency-Code", AGENCY_CODE);
        h.set("X-Agency-Key",  AGENCY_KEY);
        if (correlationId != null) {
            h.set("X-Correlation-Id", correlationId);
        }
        return h;
    }

    private ResponseEntity<String> postEntry(String ticketId, String correlationId) {
        HttpHeaders headers = baseEntryHeaders(correlationId);
        return restTemplate.exchange(
                baseUrl() + ENTRY_PATH + "?ticketId=" + ticketId,
                HttpMethod.POST,
                new HttpEntity<>("", headers),
                String.class);
    }

    private ResponseEntity<String> getSession(String agsid, String correlationId) {
        HttpHeaders headers = baseEntryHeaders(correlationId);
        if (agsid != null) {
            headers.add(HttpHeaders.COOKIE, COOKIE_NAME + "=" + agsid);
        }
        return restTemplate.exchange(
                baseUrl() + SESSION_PATH,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private String extractAgsidCookie(ResponseEntity<?> resp) {
        var setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) return null;
        for (String sc : setCookies) {
            if (sc.startsWith(COOKIE_NAME + "=")) {
                String value = sc.substring((COOKIE_NAME + "=").length());
                int sep = value.indexOf(';');
                String v = (sep > 0) ? value.substring(0, sep) : value;
                if (v.isBlank()) return null;   // clearCookie 인 경우 빈 값
                return v;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(ResponseEntity<String> resp) {
        try {
            String body = resp.getBody();
            if (body == null || body.isBlank()) return Map.of();
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("응답 JSON 파싱 실패: " + resp.getBody(), e);
        }
    }
}
