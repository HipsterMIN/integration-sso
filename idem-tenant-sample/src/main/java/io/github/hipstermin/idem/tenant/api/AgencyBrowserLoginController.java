package io.github.hipstermin.idem.tenant.api;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.tenant.client.IdoVerifyClient;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1 <b>브라우저 Handoff 진입</b> — 코어 로그인 프런트({@code GET {hub}/api/v1/handoff/login}) 를 쓰는 기관 쪽 참조 구현.
 *
 * <pre>
 *   GET /agency/login          → 302 {hub}/api/v1/handoff/login?service={code}&amp;callback={public-url}/agency/callback&amp;state=…
 *   GET /agency/callback?ticketId=…&amp;state=…   → 서버 간 verify → 기관 세션(AGSID 쿠키) → 로그인 완료 화면
 *   GET /agency/callback?error=E-IDO-120&amp;error_description=…   → 거부 화면
 * </pre>
 * 종전 {@code POST /agency/entry} 는 기관 API 키가 필요한 서버 간 진입이라 브라우저가 직접 올 수 없었다. 이 컨트롤러는
 * 브라우저가 오는 쪽이고, verify 는 여전히 서버 간(X-Agency-Key) 이다. {@code state} 는 CSRF 방지용 — 세션에 없는 값은 거부.
 */
@Slf4j
@RestController
public class AgencyBrowserLoginController {

    private static final String COOKIE_NAME = "AGSID";
    private static final String STATE_COOKIE = "AG_LOGIN_STATE";
    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);

    private final IdoVerifyClient idoVerifyClient;
    private final AgencySessionService agencySessionService;
    private final String agencyCode;
    private final String hubBaseUrl;
    private final String publicUrl;
    private final int idleTimeoutMinutes;

    public AgencyBrowserLoginController(IdoVerifyClient idoVerifyClient,
                                        AgencySessionService agencySessionService,
                                        @Value("${idem.sample.code}") String agencyCode,
                                        @Value("${idem.sample.ido.public-url:${idem.sample.ido.base-url}}") String hubBaseUrl,
                                        @Value("${idem.sample.public-url:http://localhost:8084}") String publicUrl,
                                        @Value("${idem.sample.session.idle-timeout-minutes:30}") int idleTimeoutMinutes) {
        this.idoVerifyClient = idoVerifyClient;
        this.agencySessionService = agencySessionService;
        this.agencyCode = agencyCode;
        this.hubBaseUrl = hubBaseUrl.endsWith("/") ? hubBaseUrl.substring(0, hubBaseUrl.length() - 1) : hubBaseUrl;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    /** 기관 화면의 "Idem 으로 로그인" — hub 코어 로그인 프런트로 보낸다. */
    @GetMapping("/agency/login")
    public ResponseEntity<Void> login(@RequestParam(value = "provider", required = false) String provider,
                                      @RequestParam(value = "level", required = false) String level) {
        String state = java.util.UUID.randomUUID().toString().replace("-", "");
        String callback = publicUrl + "/agency/callback";
        StringBuilder url = new StringBuilder(hubBaseUrl).append("/api/v1/handoff/login")
                .append("?service=").append(enc(agencyCode))
                .append("&callback=").append(enc(callback))
                .append("&state=").append(state);
        if (provider != null && !provider.isBlank()) url.append("&provider=").append(enc(provider));
        if (level != null && !level.isBlank()) url.append("&level=").append(enc(level));
        ResponseCookie stateCookie = ResponseCookie.from(STATE_COOKIE, state).httpOnly(true).secure(false)
                .sameSite("Lax").path("/agency/callback").maxAge(Duration.ofMinutes(10)).build();
        HttpHeaders h = new HttpHeaders();
        h.setLocation(URI.create(url.toString()));
        h.add(HttpHeaders.SET_COOKIE, stateCookie.toString());
        h.setCacheControl("no-store");
        return ResponseEntity.status(HttpStatus.FOUND).headers(h).build();
    }

    /** hub 가 브라우저를 되돌리는 곳 — ticketId 를 서버 간 verify 하고 기관 세션을 만든다. */
    @GetMapping(value = "/agency/callback", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> callback(@RequestParam(value = "ticketId", required = false) String ticketId,
                                           @RequestParam(value = "state", required = false) String state,
                                           @RequestParam(value = "error", required = false) String error,
                                           @RequestParam(value = "error_description", required = false) String errorDescription,
                                           HttpServletRequest request) {
        String cid = CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);
        String expectedState = cookie(request, STATE_COOKIE);
        if (expectedState == null || state == null || !expectedState.equals(state)) {
            log.warn("[AgencyBrowserLogin] state 불일치 — 거부: cid={}", cid);
            return page(HttpStatus.BAD_REQUEST, "로그인 요청을 확인할 수 없습니다(state 불일치). 다시 시도하세요.", cid);
        }
        if (error != null && !error.isBlank()) {
            log.info("[AgencyBrowserLogin] Idem 거부: error={} desc={} cid={}", error, errorDescription, cid);
            return page(HttpStatus.FORBIDDEN, "Idem 이 로그인을 거부했습니다: " + error
                    + (errorDescription != null ? " — " + errorDescription : ""), cid);
        }
        if (ticketId == null || ticketId.isBlank()) {
            return page(HttpStatus.BAD_REQUEST, "ticketId 가 없습니다.", cid);
        }
        HandoffPayload payload;
        try {
            payload = idoVerifyClient.verify(ticketId, cid);
        } catch (Exception e) {
            log.error("[AgencyBrowserLogin] verify 실패: ticketId={} err={}", ticketId, e.getMessage());
            return page(HttpStatus.SERVICE_UNAVAILABLE, "Idem 검증 서비스에 접근할 수 없습니다. 잠시 후 다시 시도하세요.", cid);
        }
        HandoffPayload.HandoffState st = payload.getState();
        if (st == null || st == HandoffPayload.HandoffState.HOLD) {
            return page(HttpStatus.SERVICE_UNAVAILABLE, "인증 서비스가 일시적으로 응답하지 않습니다.", cid);
        }
        if (st == HandoffPayload.HandoffState.REJECTED || st == HandoffPayload.HandoffState.MANUAL_REVIEW) {
            return page(HttpStatus.FORBIDDEN, "로그인이 거부되었습니다(" + st + ").", cid);
        }
        if (st == HandoffPayload.HandoffState.GUEST) {
            return page(HttpStatus.OK, "기관 회원 연결이 없습니다(GUEST). 회원 가입 또는 계정 연결이 필요합니다.", cid);
        }
        AgencySessionService.SessionCreateResult created = agencySessionService.createSession(
                payload, ticketId, cid, request.getRemoteAddr(), request.getHeader("User-Agent"));
        ResponseCookie agsid = ResponseCookie.from(COOKIE_NAME, created.rawAgsid()).httpOnly(true).secure(false)
                .sameSite("Lax").path("/").maxAge(Duration.ofMinutes(idleTimeoutMinutes)).build();
        ResponseCookie clearState = ResponseCookie.from(STATE_COOKIE, "").path("/agency/callback").maxAge(0).build();
        log.info("[AgencyBrowserLogin] 로그인 완료: sessionId={} authLevel={} cid={}", created.sessionId(), created.authLevel(), cid);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, agsid.toString())
                .header(HttpHeaders.SET_COOKIE, clearState.toString())
                .contentType(HTML_UTF8)
                .body(html("로그인 완료", "Idem 브라우저 로그인으로 기관 세션이 만들어졌습니다. sessionId=" + esc(created.sessionId())
                        + ", authLevel=" + esc(String.valueOf(created.authLevel())) + ", agencySubjectId=" + esc(String.valueOf(created.agencySubjectId())), cid));
    }

    private static ResponseEntity<String> page(HttpStatus status, String message, String cid) {
        return ResponseEntity.status(status).contentType(HTML_UTF8).body(html("Idem 로그인", message, cid));
    }

    private static String html(String title, String message, String cid) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><title>" + esc(title) + "</title></head><body><h1>"
                + esc(title) + "</h1><p>" + esc(message) + "</p><p><small>추적 ID " + esc(cid) + "</small></p><p><a href=\"/\">시뮬레이터로</a></p></body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var c : request.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
