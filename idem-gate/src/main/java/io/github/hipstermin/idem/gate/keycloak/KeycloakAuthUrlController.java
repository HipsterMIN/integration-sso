package io.github.hipstermin.idem.gate.keycloak;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.gate.api.InternalSigVerifier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Keycloak Authorization URL 발급 컨트롤러
 *
 * <p>ido BrokerService(mode=qsign) 가 호출하는 엔드포인트.
 * provider 별로 {@code kc_idp_hint} 를 설정한 Keycloak Authorization URL 을 반환한다.
 *
 * <p><b>엔드포인트</b>: POST /api/v1/oidc/{provider}/auth-url
 *
 * <p>ido BrokerService 의 호출 패턴:
 * <pre>
 *   String url = qsignBaseUrl + "/api/v1/oidc/" + provider + "/auth-url";
 *   POST body: { correlationId, returnUrl, requestedLevel }
 * </pre>
 *
 * <p><b>반환 URL 검증 조건</b>:
 * <ul>
 *   <li>authorizationUrl 은 Keycloak 주소({@code keycloak.base-url/realms/...}) 로 시작해야 한다.</li>
 *   <li>kauth.kakao.com, nid.naver.com 등 외부 IdP URL 이 포함되어서는 안 된다.</li>
 *   <li>{@code kc_idp_hint} 파라미터가 반드시 포함되어야 한다.</li>
 * </ul>
 *
 * <p>호출자: ido (내부 서비스, X-Internal-Caller: ido)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/oidc/{provider}")
@RequiredArgsConstructor
public class KeycloakAuthUrlController {

    private final KeycloakStateStore   stateStore;
    private final KeycloakProperties   keycloakProperties;
    private final InternalSigVerifier  internalSigVerifier;

    /**
     * Keycloak Authorization URL 발급
     *
     * <p>처리 순서:
     * <ol>
     *   <li>state, nonce 생성 후 Redis 저장 ({@link KeycloakStateStore#create})</li>
     *   <li>Keycloak Authorization URL 조립 — kc_idp_hint 포함</li>
     *   <li>{ "authorizationUrl": "..." } 반환</li>
     * </ol>
     *
     * @param provider  URL 경로 변수 (예: kakao, naver, pass, gpki)
     * @param body      { correlationId, returnUrl, requestedLevel }
     * @return          { "authorizationUrl": "http://keycloak:.../auth?...&kc_idp_hint=social-kakao" }
     */
    @PostMapping("/auth-url")
    public ResponseEntity<Map<String, String>> issueAuthorizationUrl(
            @PathVariable String provider,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String headerCid,
            @RequestHeader(value = "X-Internal-Caller",  required = false) String caller,
            @RequestHeader(value = "X-Internal-Sig",     required = false) String internalSig,
            @RequestBody Map<String, String> body) {

        // correlationId 우선순위: body > header > ThreadLocal
        String correlationId = body.getOrDefault("correlationId",
                headerCid != null ? headerCid : CorrelationIdHolder.get());
        CorrelationIdHolder.set(correlationId);
        // S6 점검에서 발견: 헤더를 받기만 하고 검증하지 않던 내부 API — hub 는 항상 서명해 보낸다
        if (!internalSigVerifier.verify(internalSig, correlationId)) {
            log.warn("[KeycloakAuthUrl] X-Internal-Sig 검증 실패: caller={} correlationId={}", caller, correlationId);
            return ResponseEntity.status(403).body(Map.of("error", "INTERNAL_SIG_INVALID"));
        }

        String returnUrl      = body.getOrDefault("returnUrl", "");
        String requestedLevel = body.getOrDefault("requestedLevel", "L1");

        log.info("[KeycloakAuthUrl] Authorization URL 발급 요청: provider={} correlationId={} caller={}",
                provider, correlationId, caller);

        // ── 1. state/nonce 생성 및 Redis 저장 ────────────────────────────
        KeycloakStateEntry stateEntry = stateStore.create(
                correlationId, returnUrl, requestedLevel, provider);

        // ── 2. Keycloak Authorization URL 조립 ───────────────────────────
        String idpHint       = keycloakProperties.resolveIdpHint(provider);
        String authUrl       = buildKeycloakAuthUrl(stateEntry, idpHint);

        log.info("[KeycloakAuthUrl] Keycloak Authorization URL 발급 완료: correlationId={} idpHint={}",
                correlationId, idpHint);
        log.debug("[KeycloakAuthUrl] authorizationUrl={}", authUrl);

        return ResponseEntity.ok(Map.of("authorizationUrl", authUrl));
    }

    // ── 내부: Keycloak Authorization URL 조립 ──────────────────────────────

    /**
     * Keycloak Authorization URL 을 조립한다.
     *
     * <p>생성되는 URL 구조:
     * <pre>
     *   {keycloak.authorizationEndpoint}
     *     ?response_type=code
     *     &client_id={q-sign-client}
     *     &redirect_uri={idem.gate.keycloak.redirectUri}
     *     &scope=openid profile email
     *     &state={state}
     *     &nonce={nonce}
     *     &kc_idp_hint={social-kakao|social-naver|...}
     * </pre>
     *
     * @param stateEntry 생성된 state/nonce 엔트리
     * @param idpHint    Keycloak kc_idp_hint 값 (예: "social-kakao")
     * @return 완성된 Keycloak Authorization URL
     */
    private String buildKeycloakAuthUrl(KeycloakStateEntry stateEntry, String idpHint) {
        String redirectUri = encode(keycloakProperties.getRedirectUri());
        String scope       = encode("openid profile email");

        String url = keycloakProperties.authorizationEndpoint()
                + "?response_type=code"
                + "&client_id="    + keycloakProperties.getClientId()
                + "&redirect_uri=" + redirectUri
                + "&scope="        + scope
                + "&state="        + stateEntry.getState()
                + "&nonce="        + stateEntry.getNonce()
                + "&kc_idp_hint="  + encode(idpHint);
        // D3: PKCE S256 — state 에 묶인 verifier 의 challenge. 콜백 token 교환이 같은 verifier 를 낸다
        if (stateEntry.getCodeVerifier() != null) {
            url += "&code_challenge=" + codeChallengeOf(stateEntry.getCodeVerifier()) + "&code_challenge_method=S256";
        }
        return url;
    }

    /** BASE64URL(SHA-256(ASCII(code_verifier))) — RFC 7636 §4.2 */
    static String codeChallengeOf(String codeVerifier) {
        return io.github.hipstermin.idem.common.crypto.CryptoProviders.current()
                .sha256Base64Url(codeVerifier.getBytes(StandardCharsets.US_ASCII));
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
