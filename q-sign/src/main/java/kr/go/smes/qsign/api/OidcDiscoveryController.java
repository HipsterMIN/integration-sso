package kr.go.smes.qsign.api;

import jakarta.servlet.http.HttpServletRequest;
import kr.go.smes.qsign.keycloak.KeycloakProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * OIDC Discovery 및 Keycloak Proxy 컨트롤러
 *
 * <p>Q-Sign이 OIDC Provider로 동작할 수 있도록 표준 OIDC Discovery 문서와
 * Keycloak OIDC 엔드포인트에 대한 투명 프록시를 제공한다.
 *
 * <h2>제공 엔드포인트</h2>
 * <pre>
 * GET  /.well-known/openid-configuration          — OIDC Discovery 문서 (q-sign 기준)
 * GET  /protocol/openid-connect/auth              → Keycloak Authorization Endpoint proxy
 * POST /protocol/openid-connect/token             → Keycloak Token Endpoint proxy
 * GET  /protocol/openid-connect/userinfo          → Keycloak UserInfo Endpoint proxy
 * GET  /protocol/openid-connect/certs             → Keycloak JWKS Endpoint proxy
 * POST /protocol/openid-connect/logout            → Keycloak Logout Endpoint proxy
 * </pre>
 *
 * <h2>OIDC Discovery 문서 구조</h2>
 * <p>{@code GET /.well-known/openid-configuration} 응답은 RFC 8414 준수.
 * issuer는 {@code qsign.keycloak.base-url/realms/{realm}}을 사용하며,
 * 실제 엔드포인트는 Keycloak 서버 URL을 그대로 사용한다.
 * (클라이언트는 q-sign에 직접 붙을 필요 없이 Keycloak을 통해 처리)
 *
 * <h2>Keycloak Proxy 설계</h2>
 * <p>FE 또는 서드파티가 q-sign을 OIDC Provider로 인식하고 엔드포인트를 호출할 경우,
 * 이 컨트롤러가 Keycloak으로 요청을 투명하게 중계한다.
 * 이를 통해 Keycloak URL 변경 시 클라이언트 설정을 변경하지 않아도 된다.
 *
 * <h2>KeycloakProperties 의존성</h2>
 * <ul>
 *   <li>{@code tokenEndpoint()} — Keycloak Token Endpoint</li>
 *   <li>{@code authorizationEndpoint()} — Keycloak Authorization Endpoint</li>
 *   <li>{@code jwksUri()} — Keycloak JWKS URI</li>
 *   <li>{@code baseUrl + "/realms/" + realm} — Keycloak Issuer URL</li>
 * </ul>
 *
 * @see KeycloakProperties
 * @see kr.go.smes.qsign.config.QSignWebConfig  restTemplate 빈 정의
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OidcDiscoveryController {

    private final KeycloakProperties keycloakProperties;
    private final RestTemplate restTemplate;

    /** hop-by-hop 응답 헤더 (프록시 체인에서 제거 필요) */
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS = Set.of(
            "transfer-encoding",
            "connection",
            "keep-alive",
            "te",
            "trailers",
            "upgrade"
    );

    /** hop-by-hop 요청 헤더 (프록시 체인에서 제거 필요) */
    private static final Set<String> EXCLUDED_REQUEST_HEADERS = Set.of(
            "host",
            "connection",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailers",
            "upgrade",
            "proxy-authorization",
            "proxy-authenticate"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // OIDC Discovery 문서
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OIDC Discovery 문서 반환 (RFC 8414)
     *
     * <p>이 엔드포인트는 q-sign을 OIDC Provider로 등록한 클라이언트가
     * Provider 메타데이터를 조회할 때 사용한다.
     *
     * <p><b>Issuer 구성</b>: {@code {keycloak.baseUrl}/realms/{realm}}
     * Keycloak의 issuer와 동일하게 설정하여 ID Token 검증이 정상 동작하도록 한다.
     *
     * <p><b>실제 엔드포인트</b>: 모든 엔드포인트는 Keycloak 서버 URL을 그대로 노출.
     * q-sign의 proxy 엔드포인트를 노출하려면 아래 URL을 q-sign 기준으로 변경.
     *
     * @return OIDC Provider Metadata (RFC 8414 준수)
     */
    @GetMapping("/.well-known/openid-configuration")
    public ResponseEntity<Map<String, Object>> getOpenIdConfiguration() {
        String issuer = keycloakProperties.getBaseUrl()
                + "/realms/" + keycloakProperties.getRealm();

        log.info("[OIDC-DISCOVERY] Discovery 문서 요청: issuer={}", issuer);

        Map<String, Object> metadata = new LinkedHashMap<>();

        // 필수 필드 (RFC 8414 §2)
        metadata.put("issuer", issuer);
        metadata.put("authorization_endpoint", keycloakProperties.authorizationEndpoint());
        metadata.put("token_endpoint", keycloakProperties.tokenEndpoint());
        metadata.put("userinfo_endpoint", issuer + "/protocol/openid-connect/userinfo");
        metadata.put("jwks_uri", keycloakProperties.jwksUri());
        metadata.put("end_session_endpoint", issuer + "/protocol/openid-connect/logout");
        metadata.put("registration_endpoint", issuer + "/clients-registrations/openid-connect");

        // 지원 스펙
        metadata.put("response_types_supported", List.of("code", "none", "id_token", "token",
                "id_token token", "code id_token", "code token", "code id_token token"));
        metadata.put("subject_types_supported", List.of("public", "pairwise"));
        metadata.put("id_token_signing_alg_values_supported", List.of("PS384", "ES384", "RS384",
                "HS256", "HS512", "ES256", "RS256", "HS384", "ES512", "PS256", "PS512", "RS512"));
        metadata.put("userinfo_signing_alg_values_supported", List.of("PS384", "ES384", "RS384",
                "RS256", "ES256", "RS512", "ES512", "PS256", "PS512", "none"));
        metadata.put("token_endpoint_auth_methods_supported",
                List.of("private_key_jwt", "client_secret_basic", "client_secret_post",
                        "tls_client_auth", "client_secret_jwt"));
        metadata.put("claims_supported", List.of("aud", "sub", "iss", "auth_time", "name",
                "given_name", "family_name", "preferred_username", "email", "acr"));
        metadata.put("grant_types_supported", List.of("authorization_code", "implicit",
                "refresh_token", "password", "client_credentials",
                "urn:ietf:params:oauth:grant-type:device_code",
                "urn:openid:params:grant-type:ciba"));
        metadata.put("scopes_supported", List.of("openid", "profile", "email",
                "address", "phone", "offline_access", "microprofile-jwt", "roles", "web-origins"));
        metadata.put("code_challenge_methods_supported", List.of("plain", "S256"));
        metadata.put("request_parameter_supported", true);
        metadata.put("request_uri_parameter_supported", true);
        metadata.put("require_request_uri_registration", true);
        metadata.put("tls_client_certificate_bound_access_tokens", true);
        metadata.put("backchannel_logout_supported", true);
        metadata.put("backchannel_logout_session_supported", true);
        metadata.put("frontchannel_logout_supported", true);
        metadata.put("frontchannel_logout_session_supported", true);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(metadata);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keycloak OIDC Proxy 엔드포인트
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Keycloak Authorization Endpoint Proxy
     *
     * <p>{@code GET /protocol/openid-connect/auth} →
     * {@code GET {keycloak.baseUrl}/realms/{realm}/protocol/openid-connect/auth}
     */
    @GetMapping("/protocol/openid-connect/auth")
    public ResponseEntity<byte[]> proxyAuth(HttpServletRequest request) throws IOException {
        return forwardToKeycloak(request, HttpMethod.GET,
                keycloakProperties.authorizationEndpoint(), null);
    }

    /**
     * Keycloak Token Endpoint Proxy
     *
     * <p>{@code POST /protocol/openid-connect/token} →
     * {@code POST {keycloak.baseUrl}/realms/{realm}/protocol/openid-connect/token}
     */
    @PostMapping("/protocol/openid-connect/token")
    public ResponseEntity<byte[]> proxyToken(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        return forwardToKeycloak(request, HttpMethod.POST,
                keycloakProperties.tokenEndpoint(), body);
    }

    /**
     * Keycloak UserInfo Endpoint Proxy
     *
     * <p>{@code GET /protocol/openid-connect/userinfo} →
     * {@code GET {keycloak.baseUrl}/realms/{realm}/protocol/openid-connect/userinfo}
     */
    @GetMapping("/protocol/openid-connect/userinfo")
    public ResponseEntity<byte[]> proxyUserInfo(HttpServletRequest request) throws IOException {
        return forwardToKeycloak(request, HttpMethod.GET,
                keycloakProperties.getBaseUrl() + "/realms/" + keycloakProperties.getRealm()
                        + "/protocol/openid-connect/userinfo", null);
    }

    /**
     * Keycloak JWKS (Public Keys) Proxy
     *
     * <p>{@code GET /protocol/openid-connect/certs} →
     * {@code GET {keycloak.baseUrl}/realms/{realm}/protocol/openid-connect/certs}
     */
    @GetMapping("/protocol/openid-connect/certs")
    public ResponseEntity<byte[]> proxyCerts(HttpServletRequest request) throws IOException {
        return forwardToKeycloak(request, HttpMethod.GET,
                keycloakProperties.jwksUri(), null);
    }

    /**
     * Keycloak Logout Endpoint Proxy
     *
     * <p>{@code POST /protocol/openid-connect/logout} →
     * {@code POST {keycloak.baseUrl}/realms/{realm}/protocol/openid-connect/logout}
     */
    @PostMapping("/protocol/openid-connect/logout")
    public ResponseEntity<byte[]> proxyLogout(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        return forwardToKeycloak(request, HttpMethod.POST,
                keycloakProperties.getBaseUrl() + "/realms/" + keycloakProperties.getRealm()
                        + "/protocol/openid-connect/logout", body);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Keycloak으로 HTTP 요청을 forward한다.
     *
     * <p>요청 헤더를 필터링(hop-by-hop 제거)하여 Keycloak에 전달하고,
     * Keycloak 응답을 그대로 클라이언트에 반환한다.
     *
     * @param request      원본 HTTP 요청
     * @param method       HTTP 메서드
     * @param targetUrl    Keycloak 대상 URL
     * @param requestBody  요청 바디 (POST/PUT은 StreamUtils로 읽은 바이트, GET은 null)
     * @return Keycloak 응답 (상태코드, 헤더, 바디 그대로 전달)
     */
    private ResponseEntity<byte[]> forwardToKeycloak(HttpServletRequest request,
                                                      HttpMethod method,
                                                      String targetUrl,
                                                      byte[] requestBody) {
        // 쿼리 스트링 포함
        String queryString = request.getQueryString();
        String fullUrl = (queryString != null && !queryString.isBlank())
                ? targetUrl + "?" + queryString
                : targetUrl;

        log.info("[OIDC-PROXY] {} {} → {}", method, request.getRequestURI(), fullUrl);

        // 요청 헤더 복사 (hop-by-hop 제거)
        HttpHeaders headers = buildForwardHeaders(request);

        try {
            HttpEntity<byte[]> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<byte[]> kcResponse = restTemplate.exchange(
                    URI.create(fullUrl),
                    method,
                    entity,
                    byte[].class
            );

            log.debug("[OIDC-PROXY] Keycloak 응답: status={}", kcResponse.getStatusCode());
            HttpHeaders responseHeaders = buildResponseHeaders(kcResponse.getHeaders());
            return ResponseEntity
                    .status(kcResponse.getStatusCode())
                    .headers(responseHeaders)
                    .body(kcResponse.getBody());

        } catch (HttpStatusCodeException e) {
            log.warn("[OIDC-PROXY] Keycloak 오류 응답: status={} path={} body={}",
                    e.getStatusCode(), request.getRequestURI(), e.getResponseBodyAsString());
            HttpHeaders responseHeaders = buildResponseHeaders(e.getResponseHeaders());
            return ResponseEntity
                    .status(e.getStatusCode())
                    .headers(responseHeaders)
                    .body(e.getResponseBodyAsByteArray());

        } catch (Exception e) {
            log.error("[OIDC-PROXY] Keycloak forward 실패: path={} err={}",
                    request.getRequestURI(), e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"OIDC_PROXY_ERROR\",\"message\":\"Keycloak 통신 오류: " +
                           e.getMessage() + "\"}").getBytes());
        }
    }

    /**
     * 요청 헤더를 Keycloak forward용으로 필터링하여 빌드한다.
     *
     * @param request 원본 HTTP 요청
     * @return Keycloak으로 forward할 헤더 (hop-by-hop 제거)
     */
    private HttpHeaders buildForwardHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement().toLowerCase();
                if (EXCLUDED_REQUEST_HEADERS.contains(headerName)) {
                    continue;
                }
                List<String> values = Collections.list(request.getHeaders(headerName));
                headers.addAll(headerName, values);
            }
        }
        return headers;
    }

    /**
     * Keycloak 응답 헤더에서 hop-by-hop 헤더를 제거하여 빌드한다.
     *
     * @param kcHeaders Keycloak 응답 헤더
     * @return 클라이언트로 전달할 헤더 (hop-by-hop 제거)
     */
    private HttpHeaders buildResponseHeaders(HttpHeaders kcHeaders) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (kcHeaders == null) {
            return responseHeaders;
        }
        kcHeaders.forEach((name, values) -> {
            if (!EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                responseHeaders.addAll(name, values);
            }
        });
        return responseHeaders;
    }
}
