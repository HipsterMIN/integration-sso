package kr.go.smes.agency.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.go.smes.agency.client.IdoVerifyClient;
import kr.go.smes.agency.session.AgencySessionService;
import kr.go.smes.agency.session.AgencySessionService.SessionCreateResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.util.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * 기관 진입 컨트롤러 — OIDC 클라이언트 완전 구현
 *
 * <p>설계서 §14.2 / §15.1 참조.
 *
 * <p><b>처리 흐름</b>:
 * <pre>
 *   POST /agency/entry?ticketId=...
 *     │
 *     ├─ [1] AgencyApiKeyInterceptor — X-Agency-Code / X-Agency-Key 검증 (인터셉터 레이어)
 *     │
 *     ├─ [2] IdoVerifyClient.verify() — POST {ido.base-url}/api/v1/handoff/verify
 *     │         - Resilience4j CircuitBreaker "ido-verify" (실패율 50% → 10s OPEN)
 *     │         - Retry 2회 (5xx / 네트워크 오류만)
 *     │         - 4xx → 즉시 REJECTED 반환 (재시도 없음)
 *     │         - CB OPEN / Retry 소진 → HOLD 상태 fallback
 *     │
 *     ├─ [3] HandoffState 검증
 *     │         APPROVED  → 계속
 *     │         HOLD      → 503 (일시 오류, 재시도 유도)
 *     │         REJECTED  → 403
 *     │
 *     ├─ [4] Session Fixation 방지 — 기존 AGSID 쿠키 DB 무효화 후 삭제
 *     │
 *     ├─ [5] AgencySessionService.createSession() — DB INSERT + 사용자 관리
 *     │         rawAGSID = SecureRandom 192-bit → Base64URL
 *     │         DB 저장 = SHA-256(rawAGSID)  ← 원문 미저장
 *     │
 *     └─ [6] AGSID 쿠키 발급 (Secure / HttpOnly / SameSite=Strict)
 * </pre>
 *
 * <p><b>보안 체크리스트</b> (설계서 §15.1):
 * <ul>
 *   <li>[v] API Key 검증 — AgencyApiKeyInterceptor (DB SHA-256 비교)</li>
 *   <li>[v] IdO Verify 실제 HTTP POST 호출 — IdoVerifyClient</li>
 *   <li>[v] CircuitBreaker / Retry — Resilience4j 적용</li>
 *   <li>[v] Verify 응답 캐시 금지 — 1회성 소비</li>
 *   <li>[v] Session Fixation 방지 — 기존 쿠키 무효화 후 재발급</li>
 *   <li>[v] AGSID 엔트로피 ≥128bit — SecureRandom 192-bit Base64URL</li>
 *   <li>[v] AGSID DB 저장 = SHA-256 해시 (원문 미저장)</li>
 *   <li>[v] Secure / HttpOnly / SameSite=Strict 쿠키</li>
 *   <li>[v] correlationId 전 계층 전파</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/agency/entry")
@RequiredArgsConstructor
public class AgencyEntryController {

    private static final String COOKIE_NAME = "AGSID";

    private final IdoVerifyClient      idoVerifyClient;
    private final AgencySessionService agencySessionService;

    @Value("${agency-stub.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    // ══════════════════════════════════════════════════════════════════════
    // 기관 진입 — Handoff Ticket 검증 → 기관 세션 생성
    // ══════════════════════════════════════════════════════════════════════

    /**
     * POST /agency/entry?ticketId={ticketId}
     *
     * <p>AgencyApiKeyInterceptor 를 통과한 요청만 도달.
     *
     * @param correlationId X-Correlation-Id 헤더 (없으면 신규 생성)
     * @param ticketId      Handoff Ticket ID (필수)
     * @param request       Servlet request (IP / User-Agent / AGSID 쿠키 추출)
     * @param response      Servlet response (AGSID 쿠키 발급)
     */
    @PostMapping
    public ResponseEntity<?> enter(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestParam String ticketId,
            HttpServletRequest  request,
            HttpServletResponse response) {

        String cid = (correlationId != null && !correlationId.isBlank())
                ? correlationId
                : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        String ipAddress = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        log.info("[AgencyEntry] 진입 요청: ticketId={} correlationId={} ip={}", ticketId, cid, ipAddress);

        // ① IdO Verify API 실제 호출 (Resilience4j CB + Retry 적용)
        HandoffPayload payload;
        try {
            payload = idoVerifyClient.verify(ticketId, cid);
        } catch (Exception e) {
            log.error("[AgencyEntry] Verify 호출 예외: ticketId={} err={}", ticketId, e.getMessage(), e);
            return ResponseEntity.status(503)
                    .body(Map.of(
                            "error",         "VERIFY_UNAVAILABLE",
                            "correlationId", cid,
                            "message",       "IdO Verify 서비스에 일시적으로 접근할 수 없습니다. 잠시 후 다시 시도해주세요."
                    ));
        }

        // ② HandoffState 검증
        HandoffPayload.HandoffState state = payload.getState();
        if (state == null) {
            log.error("[AgencyEntry] state null 응답: ticketId={}", ticketId);
            return ResponseEntity.status(502).body(Map.of("error", "INVALID_VERIFY_RESPONSE", "correlationId", cid));
        }

        switch (state) {
            case HOLD -> {
                log.warn("[AgencyEntry] HOLD 상태 — 일시 오류: ticketId={} correlationId={}", ticketId, cid);
                return ResponseEntity.status(503)
                        .body(Map.of(
                                "error",         "SERVICE_TEMPORARILY_UNAVAILABLE",
                                "correlationId", cid,
                                "message",       "인증 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요."
                        ));
            }
            case REJECTED, MANUAL_REVIEW -> {
                log.warn("[AgencyEntry] Handoff 거부: state={} ticketId={} correlationId={}", state, ticketId, cid);
                return ResponseEntity.status(403)
                        .body(Map.of(
                                "error",         "HANDOFF_REJECTED",
                                "state",         state.name(),
                                "correlationId", cid
                        ));
            }
            case GUEST -> {
                // 기관 매핑 없는 인증 사용자 — Q-IM UUID는 있지만 기관 회원 연결 없음
                // 기관은 이 응답을 받아 신규 회원 가입 유도 또는 제한된 게스트 접근을 제공해야 한다.
                String guestQimUserId = payload.getSubject() != null ? payload.getSubject().getQimUserId() : null;
                log.info("[AgencyEntry] GUEST — 기관 매핑 없음: ticketId={} qimUserId={} correlationId={}",
                        ticketId, guestQimUserId, cid);
                return ResponseEntity.status(200)
                        .body(Map.of(
                                "state",         "GUEST",
                                "qimUserId",     guestQimUserId != null ? guestQimUserId : "",
                                "correlationId", cid,
                                "message",       "기관 회원 연결이 없습니다. 회원 가입 또는 계정 연결이 필요합니다."
                        ));
            }
            case APPROVED -> log.info("[AgencyEntry] APPROVED 확인: ticketId={}", ticketId);
            default -> {
                log.error("[AgencyEntry] 알 수 없는 state={}: ticketId={}", state, ticketId);
                return ResponseEntity.status(502).body(Map.of("error", "UNKNOWN_STATE", "correlationId", cid));
            }
        }

        // ③ Session Fixation 방지 — 기존 AGSID 쿠키 DB 무효화
        String existingAgsid = extractCookieValue(request, COOKIE_NAME);
        if (existingAgsid != null && !existingAgsid.isBlank()) {
            log.info("[AgencyEntry] 기존 AGSID 무효화 (Session Fixation 방지): correlationId={}", cid);
            agencySessionService.invalidateByAgsid(existingAgsid, "SESSION_FIXATION_PREVENTION", cid);
            clearCookie(COOKIE_NAME, response);
        }

        // ④ 기관 로컬 세션 생성 (DB 저장 + AGSID 해시 저장)
        SessionCreateResult result;
        try {
            result = agencySessionService.createSession(payload, ticketId, cid, ipAddress, userAgent);
        } catch (Exception e) {
            log.error("[AgencyEntry] 세션 생성 실패: ticketId={} err={}", ticketId, e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "SESSION_CREATE_FAILED", "correlationId", cid));
        }

        // ⑤ AGSID 쿠키 발급 (rawAGSID — DB 에는 SHA-256 해시만 저장)
        ResponseCookie agsidCookie = ResponseCookie.from(COOKIE_NAME, result.rawAgsid())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ofMinutes(idleTimeoutMinutes))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, agsidCookie.toString());

        log.info("[AgencyEntry] 세션 발급 완료: sessionId={} authLevel={} correlationId={}",
                result.sessionId(), result.authLevel(), cid);

        return ResponseEntity.ok(Map.of(
                "agencyUserId",    result.agencyUserId(),
                "agencySubjectId", result.agencySubjectId(),
                "authLevel",       result.authLevel(),
                "correlationId",   cid,
                "sessionId",       result.sessionId()
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 세션 상태 조회 (내부 헬스체크 / 기관 앱 연동용)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * GET /agency/entry/session — 현재 AGSID 세션 유효성 확인
     *
     * <p>기관 앱이 세션 상태를 확인할 때 사용.
     * 유효한 세션이면 sliding window 연장.
     */
    @GetMapping("/session")
    public ResponseEntity<?> sessionInfo(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest  request,
            HttpServletResponse response) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        String rawAgsid = extractCookieValue(request, COOKIE_NAME);

        if (rawAgsid == null || rawAgsid.isBlank()) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "NO_SESSION", "correlationId", cid));
        }

        Optional<java.util.Map<String, Object>> sessionOpt = agencySessionService.findValidSession(rawAgsid);
        if (sessionOpt.isEmpty()) {
            clearCookie(COOKIE_NAME, response);
            return ResponseEntity.status(401)
                    .body(Map.of("error", "SESSION_EXPIRED_OR_INVALID", "correlationId", cid));
        }

        // Sliding Window 연장
        agencySessionService.touchSession(rawAgsid);

        var session = sessionOpt.get();
        return ResponseEntity.ok(Map.of(
                "sessionId",    orEmpty(session.get("session_id")),
                "authLevel",    orEmpty(session.get("auth_level")),
                "qimUserId",    orEmpty(session.get("qim_user_id")),
                "userStatus",   orEmpty(session.get("user_status")),
                "createdAt",    orEmpty(session.get("created_at")),
                "correlationId", cid
        ));
    }

    /**
     * DELETE /agency/entry/session — 기관 로그아웃
     */
    @DeleteMapping("/session")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            HttpServletRequest  request,
            HttpServletResponse response) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        String rawAgsid = extractCookieValue(request, COOKIE_NAME);

        if (rawAgsid != null) {
            agencySessionService.invalidateByAgsid(rawAgsid, "USER_LOGOUT", cid);
        }
        clearCookie(COOKIE_NAME, response);

        log.info("[AgencyEntry] 로그아웃: correlationId={}", cid);
        return ResponseEntity.ok(Map.of("status", "LOGGED_OUT", "correlationId", cid));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ══════════════════════════════════════════════════════════════════════

    private String extractCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private void clearCookie(String name, HttpServletResponse response) {
        ResponseCookie clear = ResponseCookie.from(name, "")
                .maxAge(0)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());
    }

    /** X-Forwarded-For → RemoteAddr 순서로 실제 클라이언트 IP 추출 */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String orEmpty(Object o) {
        return o != null ? o.toString() : "";
    }
}
