package io.github.hipstermin.idem.gate.oidcfront;

import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * OIDC 프런트 (S6) — gate 가 Keycloak 을 숨기고 표준 OIDC Provider 로 선다.
 *
 * <pre>
 * GET  /.well-known/openid-configuration                     정직한 Discovery (issuer = 공개 gate URL/realms/{realm})
 * GET  /realms/{realm}/.well-known/openid-configuration      〃 (표준 위치)
 * GET  /realms/{realm}/protocol/openid-connect/auth          사전검사(Idem client·code·PKCE S256) → Keycloak
 * POST /realms/{realm}/protocol/openid-connect/token         Keycloak → hub 정책 판정 → 허용이면 그대로, 거부면 403 access_denied
 * *    /realms/{realm}/protocol/openid-connect/userinfo      Keycloak → idem_* 클레임 보강(거부면 403)
 * *    /realms/{realm}/**, /resources/**                     로그인 화면·브로커·정적 자원 투명 프록시
 * </pre>
 *
 * <p>Keycloak 은 컨테이너 안에서만 열려 있고 브라우저·RP 는 이 프런트만 본다. {@code KC_HOSTNAME_URL} 이 issuer 와 같아
 * id_token 의 {@code iss} 가 공개 URL 로 나온다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OidcFrontController {

    private final KeycloakProperties keycloak;
    private final OidcFrontProperties props;
    private final KeycloakProxy proxy;
    private final OidcRpPolicyGate policyGate;

    // ── Discovery ──────────────────────────────────────────────────────────

    @GetMapping({"/.well-known/openid-configuration", "/realms/{realm}/.well-known/openid-configuration"})
    public ResponseEntity<Map<String, Object>> discovery(@PathVariable(required = false) String realm) {
        if (realm != null && !realm.equals(keycloak.getRealm())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(OidcDiscovery.build(props.getIssuer()));
    }

    // ── Authorization endpoint — 사전검사 후 전달 ───────────────────────────

    @GetMapping("/realms/{realm}/protocol/openid-connect/auth")
    public ResponseEntity<byte[]> authorize(@PathVariable String realm, HttpServletRequest request) {
        if (!realm.equals(keycloak.getRealm())) return notFound();
        String clientId = request.getParameter("client_id");
        if (!props.isProvisionedClient(clientId)) {
            return oauthError(HttpStatus.BAD_REQUEST, "unauthorized_client", "Idem 이 프로비저닝한 client 만 접근할 수 있습니다");
        }
        if (!"code".equals(request.getParameter("response_type"))) {
            return oauthError(HttpStatus.BAD_REQUEST, "unsupported_response_type", "response_type=code 만 지원합니다");
        }
        String challenge = request.getParameter("code_challenge");
        if (challenge == null || challenge.isBlank() || !"S256".equals(request.getParameter("code_challenge_method"))) {
            return oauthError(HttpStatus.BAD_REQUEST, "invalid_request", "PKCE(code_challenge, code_challenge_method=S256)가 필요합니다");
        }
        return proxy.forward(request, null);
    }

    // ── Token endpoint — 발급 뒤 정책 판정 ──────────────────────────────────

    @PostMapping("/realms/{realm}/protocol/openid-connect/token")
    public ResponseEntity<byte[]> token(@PathVariable String realm, HttpServletRequest request) throws IOException {
        if (!realm.equals(keycloak.getRealm())) return notFound();
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        Map<String, String> form = parseForm(body);
        String grant = form.get("grant_type");
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String clientId = Optional.ofNullable(form.get("client_id")).orElseGet(() -> basicClientId(authHeader));
        String cid = Optional.ofNullable(request.getHeader("X-Correlation-Id")).orElse(UUID.randomUUID().toString());

        if (!props.isProvisionedClient(clientId)) {
            return oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", "Idem 이 프로비저닝한 client 만 토큰을 받을 수 있습니다");
        }
        ResponseEntity<byte[]> kc = proxy.forward(request, body);
        boolean issuesTokens = "authorization_code".equals(grant) || "refresh_token".equals(grant);
        if (!kc.getStatusCode().is2xxSuccessful() || !issuesTokens || kc.getBody() == null) {
            return kc;   // Keycloak 의 오류(invalid_grant 등)는 그대로
        }
        String tokenJson = new String(kc.getBody(), StandardCharsets.UTF_8);
        OidcRpPolicyGate.TokenOutcome outcome = policyGate.onTokenIssued(clientId, tokenJson, cid);
        if (!outcome.decision().allowed()) {
            log.warn("[OIDC-FRONT] 토큰 발급 거부: client={} code={} cid={} — {}", outcome.clientId(),
                    outcome.decision().denyCode(), cid, outcome.decision().denyMessage());
            policyGate.revokeIssuedTokens(tokenJson, outcome.clientId(), authHeader, form.get("client_secret"));
            HttpStatus status = "E-IDO-116".equals(outcome.decision().denyCode()) || "E-IDO-117".equals(outcome.decision().denyCode())
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            String error = status == HttpStatus.SERVICE_UNAVAILABLE ? "temporarily_unavailable" : "access_denied";
            return oauthError(status, error, outcome.decision().denyCode() + " " + safe(outcome.decision().denyMessage()));
        }
        return kc;
    }

    // ── UserInfo — Idem 클레임 보강 ──────────────────────────────────────────

    @RequestMapping(value = "/realms/{realm}/protocol/openid-connect/userinfo", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> userInfo(@PathVariable String realm, HttpServletRequest request) throws IOException {
        if (!realm.equals(keycloak.getRealm())) return notFound();
        byte[] body = "POST".equals(request.getMethod()) ? StreamUtils.copyToByteArray(request.getInputStream()) : null;
        ResponseEntity<byte[]> kc = proxy.forward(request, body);
        if (!kc.getStatusCode().is2xxSuccessful() || kc.getBody() == null) return kc;
        String bearer = bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearer == null) return kc;
        String cid = Optional.ofNullable(request.getHeader("X-Correlation-Id")).orElse(UUID.randomUUID().toString());
        Optional<String> enriched = policyGate.enrichUserInfo(bearer, new String(kc.getBody(), StandardCharsets.UTF_8), cid);
        if (enriched.isEmpty()) {
            return oauthError(HttpStatus.FORBIDDEN, "access_denied", "이 서비스에 대한 접근이 허용되지 않습니다");
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setCacheControl("no-store");
        return ResponseEntity.ok().headers(h).body(enriched.get().getBytes(StandardCharsets.UTF_8));
    }

    // ── 나머지 Keycloak 경로 — 투명 프록시 ───────────────────────────────────

    @RequestMapping(value = {"/realms/{realm}/**"}, method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> realmPassthrough(@PathVariable String realm, HttpServletRequest request) throws IOException {
        if (!realm.equals(keycloak.getRealm())) return notFound();
        if (request.getRequestURI().contains("/clients-registrations/") || request.getRequestURI().contains("/protocol/openid-connect/registrations")) {
            return oauthError(HttpStatus.NOT_FOUND, "not_found", "동적 등록은 지원하지 않습니다 — client 는 Idem 이 프로비저닝합니다");
        }
        byte[] body = "POST".equals(request.getMethod()) ? StreamUtils.copyToByteArray(request.getInputStream()) : null;
        return proxy.forward(request, body);
    }

    @RequestMapping(value = "/resources/**", method = RequestMethod.GET)
    public ResponseEntity<byte[]> resources(HttpServletRequest request) {
        return proxy.forward(request, null);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static Map<String, String> parseForm(byte[] body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.length == 0) return out;
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static String basicClientId(String authHeader) {
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6).trim()), StandardCharsets.UTF_8);
            int i = decoded.indexOf(':');
            return URLDecoder.decode(i > 0 ? decoded.substring(0, i) : decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static String bearer(String authHeader) {
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authHeader.substring(7).trim();
    }

    private static ResponseEntity<byte[]> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    private static ResponseEntity<byte[]> oauthError(HttpStatus status, String error, String description) {
        String json = "{\"error\":\"" + error + "\",\"error_description\":\"" + safe(description) + "\"}";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setCacheControl("no-store");
        return ResponseEntity.status(status).headers(h).body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
