package io.github.hipstermin.idem.tenant.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.tenant.session.AgencySessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기관 샘플의 표준 OIDC 로그인 경로 (S6 PR-2) — {@code agency-stub.protocol=OIDC_RP} 일 때 쓴다.
 *
 * <pre>
 * GET  /agency/oidc/login              → Idem authorization_endpoint 로 302 (PKCE·state·nonce)
 * GET  /agency/oidc/callback           → code 교환 → id_token·userinfo 검증 → 기관 세션(AGSID) 생성
 * GET  /agency/oidc/logout             → 기관 세션 종료 + Idem end_session_endpoint 로 302 (RP-Initiated Logout)
 * POST /agency/oidc/backchannel-logout → Idem(Keycloak) 이 보내는 logout_token → 그 sid 의 기관 세션 종료
 * </pre>
 * Handoff 진입(`/agency/entry`)과 세션 저장소·쿠키는 같다 — 기관 코드에서 바뀌는 것은 "어떻게 들어오느냐" 뿐이다.
 */
@Slf4j
@RestController
@RequestMapping("/agency/oidc")
@RequiredArgsConstructor
public class OidcLoginController {

    static final String COOKIE_NAME = "AGSID";
    static final String OIDC_TICKET_PREFIX = "";   // agency_local_session.ticket_id(36자) 에 Keycloak sid(UUID) 를 그대로 남겨 Back-Channel Logout 이 찾는다

    private final OidcRelyingPartyClient rp;
    private final OidcRpProperties props;
    private final AgencySessionService sessions;

    @Value("${agency-stub.protocol:HANDOFF}")
    private String protocol;

    @Value("${agency-stub.session.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    private final Map<String, OidcRelyingPartyClient.PendingLogin> pending = new ConcurrentHashMap<>();
    private final Map<String, String> idTokenBySession = new ConcurrentHashMap<>();

    @GetMapping("/login")
    public ResponseEntity<?> login() {
        if (!"OIDC_RP".equalsIgnoreCase(protocol)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "PROTOCOL_MISMATCH",
                    "message", "agency-stub.protocol=OIDC_RP 일 때만 표준 OIDC 로그인을 쓴다 (현재 " + protocol + ")"));
        }
        OidcRelyingPartyClient.PendingLogin p = rp.newLogin();
        pending.entrySet().removeIf(e -> e.getValue().isExpired(props.getStateTtlSeconds()));
        pending.put(p.getState(), p);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(rp.authorizationUrl(p))).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error,
                                      @RequestParam(name = "error_description", required = false) String errorDescription,
                                      HttpServletRequest request) {
        String cid = CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);
        if (error != null) {
            log.warn("[OIDC-RP] 인가 오류: error={} desc={} cid={}", error, errorDescription, cid);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", error, "error_description", errorDescription == null ? "" : errorDescription, "correlationId", cid));
        }
        OidcRelyingPartyClient.PendingLogin p = state == null ? null : pending.remove(state);
        if (p == null || p.isExpired(props.getStateTtlSeconds())) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_STATE", "correlationId", cid));
        }
        OidcRelyingPartyClient.Login login;
        try {
            login = rp.exchange(code, p);
        } catch (Exception e) {
            // Idem 정책 거부는 토큰 교환에서 403 access_denied 로 온다 — error_description 첫 토큰이 E-IDO 코드
            log.warn("[OIDC-RP] 토큰 교환 실패: cid={} err={}", cid, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "TOKEN_EXCHANGE_FAILED", "message", e.getMessage(), "correlationId", cid));
        }
        if (!"APPROVED".equals(login.idemState())) {
            // GUEST(미할당 셀프가입) 는 기관 정책에 따라 가입 유도 등으로 처리 — 샘플은 403
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "NOT_APPROVED", "idem_state", String.valueOf(login.idemState()), "correlationId", cid));
        }
        HandoffPayload payload = toPayload(login, cid);
        String ticketRef = OIDC_TICKET_PREFIX + (login.sid() != null ? login.sid() : login.sub());
        AgencySessionService.SessionCreateResult created = sessions.createSession(payload, ticketRef, cid,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        idTokenBySession.put(created.sessionId(), login.rawIdToken());
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, created.rawAgsid()).httpOnly(true).secure(false)
                .sameSite("Lax").path("/").maxAge(idleTimeoutMinutes * 60L).build();
        log.info("[OIDC-RP] 로그인 완료: service={} state={} sid={} sessionId={} cid={}", login.idemService(), login.idemState(), login.sid() != null, created.sessionId(), cid);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "LOGGED_IN", "protocol", "OIDC_RP", "sessionId", created.sessionId(),
                        "agencySubjectId", String.valueOf(created.agencySubjectId()), "idemState", login.idemState(),
                        "authLevel", created.authLevel(), "correlationId", cid));
    }

    @GetMapping("/logout")
    public ResponseEntity<?> logout(@CookieValue(name = COOKIE_NAME, required = false) String agsid) {
        String cid = CorrelationIdHolder.generate();
        String rawIdToken = null;
        if (agsid != null) {
            Optional<Map<String, Object>> s = sessions.findValidSession(agsid);
            if (s.isPresent()) {
                Object sessionId = s.get().containsKey("session_id") ? s.get().get("session_id") : s.get().get("sessionId");
                rawIdToken = idTokenBySession.remove(String.valueOf(sessionId));
            }
            sessions.invalidateByAgsid(agsid, "USER_LOGOUT", cid);
        }
        ResponseCookie clear = ResponseCookie.from(COOKIE_NAME, "").maxAge(0).path("/").build();
        if (rawIdToken == null) {
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.SET_COOKIE, clear.toString())
                    .location(URI.create(props.getPostLogoutRedirectUri())).build();
        }
        // RP-Initiated Logout: Idem 이 Keycloak 세션을 끝내고, 그 세션에 참여한 다른 RP 에 Back-Channel Logout 을 보낸다
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.SET_COOKIE, clear.toString())
                .location(URI.create(rp.endSessionUrl(rawIdToken))).build();
    }

    @PostMapping(value = "/backchannel-logout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> backchannelLogout(@RequestParam("logout_token") String logoutToken) {
        String cid = CorrelationIdHolder.generate();
        JsonNode claims;
        try {
            claims = rp.verifyLogoutToken(logoutToken);
        } catch (Exception e) {
            log.warn("[OIDC-RP] logout_token 거부: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_logout_token"));
        }
        String sid = claims.path("sid").asText(null);
        String sub = claims.path("sub").asText(null);
        int n = sid != null ? sessions.invalidateByTicketId(OIDC_TICKET_PREFIX + sid, "BACKCHANNEL_LOGOUT", cid)
                            : sessions.invalidateByTicketId(OIDC_TICKET_PREFIX + sub, "BACKCHANNEL_LOGOUT", cid);
        log.info("[OIDC-RP] Back-Channel Logout 수신: sid={} invalidated={} cid={}", sid != null, n, cid);
        return ResponseEntity.ok(Map.of("invalidated", n));
    }

    /** userinfo 의 idem_* 를 Handoff 어설션과 같은 모양으로 — 세션 저장 코드가 프로토콜을 몰라도 되게. */
    static HandoffPayload toPayload(OidcRelyingPartyClient.Login login, String cid) {
        AuthResult.AuthLevel level = AuthResult.AuthLevel.parse(acrToLevel(login.acr())).orElse(AuthResult.AuthLevel.L1);
        return HandoffPayload.builder()
                .correlationId(cid)
                .agencyCode(login.idemService())
                .state(HandoffPayload.HandoffState.APPROVED)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(login.idemSubject())
                        .qimUserId(login.idemUserId())
                        .build())
                .authContext(HandoffPayload.AuthContext.builder().authLevel(level).authenticatedAt(Instant.now()).build())
                .issuedAt(Instant.now())
                .build();
    }

    private static String acrToLevel(String acr) {
        if (acr == null) return "L1";
        return switch (acr) { case "2" -> "L2"; case "3" -> "L3"; default -> "L1"; };
    }
}
