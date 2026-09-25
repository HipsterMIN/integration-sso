package io.github.hipstermin.idem.tenant.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * 표준 OIDC Relying Party — 기관이 Idem 에 붙을 때 필요한 전부를 라이브러리 없이 보여 주는 참조 구현 (S6 PR-2).
 *
 * <ul>
 *   <li>Discovery: {@code {issuer}/.well-known/openid-configuration} 에서 엔드포인트·JWKS 를 읽는다(캐시)</li>
 *   <li>Authorization Code + PKCE(S256) + state + nonce — Idem 은 PKCE 없는 요청을 거부한다</li>
 *   <li>id_token: RS256 서명(JWKS)·iss·aud·exp·nonce 검증</li>
 *   <li>userinfo: Idem 이 보태는 {@code idem_state·idem_subject·idem_roles·idem_assigned·idem_user_id} 를 읽는다</li>
 *   <li>Back-Channel Logout: {@code logout_token} 검증(events·sid)</li>
 * </ul>
 * Spring Security OAuth2 Client 를 써도 같은 결과가 나온다 — 이 클래스는 무엇이 오가는지 드러내기 위한 것이다.
 */
@Slf4j
@Component
public class OidcRelyingPartyClient {

    private final OidcRpProperties props;
    private final ObjectMapper om;
    private final RestTemplate http;
    private final SecureRandom random = new SecureRandom();
    private volatile JsonNode discovery;
    private final Map<String, PublicKey> jwks = new ConcurrentHashMap<>();

    public OidcRelyingPartyClient(OidcRpProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(props.getHttpTimeoutMs());
        f.setReadTimeout(props.getHttpTimeoutMs());
        this.http = new RestTemplate(f);
    }

    /** 인가 요청 하나의 상태 — state 키로 서버 측에 보관(쿠키에 두지 않는다). */
    @Getter
    public static class PendingLogin {
        private final String state; private final String nonce; private final String codeVerifier; private final Instant createdAt;
        PendingLogin(String state, String nonce, String codeVerifier) { this.state = state; this.nonce = nonce; this.codeVerifier = codeVerifier; this.createdAt = Instant.now(); }
        public boolean isExpired(int ttlSeconds) { return Instant.now().isAfter(createdAt.plusSeconds(ttlSeconds)); }
    }

    /** 로그인 결과 — 검증된 id_token 클레임 + userinfo. */
    public record Login(JsonNode idToken, JsonNode userInfo, String accessToken, String rawIdToken) {
        public String sub() { return idToken.path("sub").asText(null); }
        public String sid() { return idToken.path("sid").asText(null); }
        public String idemService() { return userInfo.path("idem_service").asText(idToken.path("idem_service").asText(null)); }
        public String idemState() { return userInfo.path("idem_state").asText(null); }
        public String idemSubject() { return userInfo.path("idem_subject").asText(null); }
        public String idemUserId() { return userInfo.path("idem_user_id").asText(null); }
        public String acr() { return idToken.path("acr").asText(null); }
    }

    // ── discovery ──────────────────────────────────────────────────────────

    public JsonNode discovery() {
        JsonNode d = discovery;
        if (d == null) {
            d = get(props.getIssuer() + "/.well-known/openid-configuration");
            if (!props.getIssuer().equals(d.path("issuer").asText())) {
                throw new IllegalStateException("Discovery issuer 불일치: " + d.path("issuer").asText());
            }
            discovery = d;
        }
        return d;
    }

    // ── 1. 인가 요청 ────────────────────────────────────────────────────────

    public PendingLogin newLogin() {
        return new PendingLogin(b64url(randomBytes(24)), b64url(randomBytes(16)), b64url(randomBytes(32)));
    }

    public String authorizationUrl(PendingLogin login) {
        String challenge = b64url(sha256(login.getCodeVerifier().getBytes(StandardCharsets.US_ASCII)));
        return discovery().path("authorization_endpoint").asText()
                + "?response_type=code"
                + "&client_id=" + enc(props.getClientId())
                + "&redirect_uri=" + enc(props.getRedirectUri())
                + "&scope=" + enc(String.join(" ", props.getScopes()))
                + "&state=" + enc(login.getState())
                + "&nonce=" + enc(login.getNonce())
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256";
    }

    // ── 2. 코드 교환 + 검증 + userinfo ─────────────────────────────────────

    public Login exchange(String code, PendingLogin login) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", props.getRedirectUri());
        form.add("code_verifier", login.getCodeVerifier());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        h.setBasicAuth(props.getClientId(), props.getClientSecret());
        ResponseEntity<String> resp = http.exchange(URI.create(discovery().path("token_endpoint").asText()), HttpMethod.POST,
                new HttpEntity<>(form, h), String.class);
        JsonNode token = parse(resp.getBody());
        String rawId = token.path("id_token").asText(null);
        if (rawId == null) throw new IllegalStateException("토큰 응답에 id_token 없음");
        JsonNode claims = verifyJwt(rawId);
        if (!props.getIssuer().equals(claims.path("iss").asText())) throw new IllegalStateException("id_token iss 불일치");
        if (!audienceContains(claims, props.getClientId())) throw new IllegalStateException("id_token aud 불일치");
        if (claims.path("exp").asLong(0) < Instant.now().getEpochSecond()) throw new IllegalStateException("id_token 만료");
        if (!login.getNonce().equals(claims.path("nonce").asText())) throw new IllegalStateException("nonce 불일치");
        String access = token.path("access_token").asText();
        HttpHeaders uh = new HttpHeaders();
        uh.setBearerAuth(access);
        ResponseEntity<String> ui = http.exchange(URI.create(discovery().path("userinfo_endpoint").asText()), HttpMethod.GET,
                new HttpEntity<>(uh), String.class);
        JsonNode userInfo = parse(ui.getBody());
        if (!claims.path("sub").asText().equals(userInfo.path("sub").asText())) throw new IllegalStateException("userinfo sub 불일치");
        return new Login(claims, userInfo, access, rawId);
    }

    // ── 3. RP-Initiated Logout URL ─────────────────────────────────────────

    public String endSessionUrl(String rawIdToken) {
        return discovery().path("end_session_endpoint").asText()
                + "?id_token_hint=" + enc(rawIdToken)
                + "&post_logout_redirect_uri=" + enc(props.getPostLogoutRedirectUri())
                + "&client_id=" + enc(props.getClientId());
    }

    // ── 4. Back-Channel Logout 토큰 검증 (OIDC Back-Channel Logout 1.0 §2.6) ──

    public JsonNode verifyLogoutToken(String logoutToken) {
        JsonNode c = verifyJwt(logoutToken);
        if (!props.getIssuer().equals(c.path("iss").asText())) throw new IllegalStateException("logout_token iss 불일치");
        if (!audienceContains(c, props.getClientId())) throw new IllegalStateException("logout_token aud 불일치");
        if (!c.path("events").has("http://schemas.openid.net/event/backchannel-logout")) throw new IllegalStateException("events 없음");
        if (c.has("nonce")) throw new IllegalStateException("logout_token 에 nonce 금지");
        if (!c.hasNonNull("sub") && !c.hasNonNull("sid")) throw new IllegalStateException("sub·sid 없음");
        return c;
    }

    // ── JWT (RS256, JWKS) ──────────────────────────────────────────────────

    JsonNode verifyJwt(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new IllegalStateException("JWT 형식 오류");
        JsonNode header = parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        if (!"RS256".equals(header.path("alg").asText())) throw new IllegalStateException("지원하지 않는 alg: " + header.path("alg").asText());
        PublicKey key = publicKey(header.path("kid").asText());
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) throw new IllegalStateException("JWT 서명 불일치");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("JWT 서명 검증 오류", e);
        }
        return parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private PublicKey publicKey(String kid) {
        PublicKey cached = jwks.get(kid);
        if (cached != null) return cached;
        JsonNode set = get(discovery().path("jwks_uri").asText());
        for (JsonNode k : set.path("keys")) {
            if ("RSA".equals(k.path("kty").asText())) {
                try {
                    BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
                    BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
                    jwks.put(k.path("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
                } catch (Exception ex) {
                    log.warn("[OIDC-RP] JWK 파싱 실패 kid={}", k.path("kid").asText());
                }
            }
        }
        PublicKey key = jwks.get(kid);
        if (key == null) throw new IllegalStateException("JWKS 에 kid 없음: " + kid);
        return key;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static boolean audienceContains(JsonNode claims, String clientId) {
        JsonNode aud = claims.path("aud");
        if (aud.isArray()) { for (JsonNode a : aud) if (clientId.equals(a.asText())) return true; return false; }
        return clientId.equals(aud.asText());
    }

    private JsonNode get(String url) {
        return parse(http.getForObject(URI.create(url), String.class));
    }

    private JsonNode parse(String json) {
        try { return om.readTree(json == null ? "{}" : json); } catch (Exception e) { throw new IllegalStateException("JSON 파싱 실패", e); }
    }

    private byte[] randomBytes(int n) { byte[] b = new byte[n]; random.nextBytes(b); return b; }
    static String b64url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] sha256(byte[] in) { try { return MessageDigest.getInstance("SHA-256").digest(in); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
}
