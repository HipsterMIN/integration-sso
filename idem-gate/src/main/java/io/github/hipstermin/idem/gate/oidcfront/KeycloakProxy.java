package io.github.hipstermin.idem.gate.oidcfront;

import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak 투명 프록시 (S6) — 브라우저·RP 서버가 보내는 요청을 헤더·본문 그대로 내부 Keycloak 에 전달하고 응답을 돌려준다.
 *
 * <p>규칙:
 * <ul>
 *   <li>hop-by-hop 헤더와 {@code Host} 는 떼고, {@code X-Forwarded-Host/Proto/For/Port} 를 공개 URL 기준으로 채운다
 *       (Keycloak 은 {@code KC_PROXY=edge} 로 이를 신뢰한다)</li>
 *   <li>302 는 따라가지 않고 그대로 돌려준다 — 로그인 화면·브로커 리다이렉트는 브라우저가 따라가야 한다</li>
 *   <li>{@code Location} 에 내부 Keycloak 주소가 남아 있으면 공개 베이스로 바꾼다({@code KC_HOSTNAME_URL} 미설정 방어)</li>
 *   <li>Keycloak 이 닿지 않으면 502 — 로그인 경로에서 조용히 성공한 척하지 않는다</li>
 * </ul>
 */
@Slf4j
@Component
public class KeycloakProxy {

    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "transfer-encoding", "te",
            "trailers", "upgrade", "proxy-authorization", "proxy-authenticate", "host", "content-length");

    private final RestTemplate restTemplate;
    private final KeycloakProperties keycloak;
    private final OidcFrontProperties props;

    public KeycloakProxy(@Qualifier("keycloakProxyRestTemplate") RestTemplate restTemplate,
                         KeycloakProperties keycloak, OidcFrontProperties props) {
        this.restTemplate = restTemplate;
        this.keycloak = keycloak;
        this.props = props;
    }

    /** 요청 경로·쿼리를 그대로 Keycloak 베이스에 붙여 전달한다. */
    public ResponseEntity<byte[]> forward(HttpServletRequest request, byte[] body) {
        String query = request.getQueryString();
        if (query == null && "GET".equalsIgnoreCase(request.getMethod()) && !request.getParameterMap().isEmpty()) {
            // 서블릿 컨테이너에 따라 queryString 이 비어 있을 수 있다 — 파라미터 맵에서 복원
            StringBuilder sb = new StringBuilder();
            request.getParameterMap().forEach((k, vs) -> {
                for (String v : vs) {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(java.net.URLEncoder.encode(k, java.nio.charset.StandardCharsets.UTF_8)).append('=')
                      .append(java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8));
                }
            });
            query = sb.toString();
        }
        String target = keycloak.getBaseUrl() + request.getRequestURI() + (query != null && !query.isEmpty() ? "?" + query : "");
        return forwardTo(request, HttpMethod.valueOf(request.getMethod()), target, body);
    }

    public ResponseEntity<byte[]> forwardTo(HttpServletRequest request, HttpMethod method, String targetUrl, byte[] body) {
        HttpHeaders headers = forwardHeaders(request);
        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(URI.create(targetUrl), method,
                    new HttpEntity<>(body, headers), byte[].class);
            return ResponseEntity.status(resp.getStatusCode()).headers(responseHeaders(resp.getHeaders())).body(resp.getBody());
        } catch (Exception e) {
            log.error("[OIDC-FRONT] Keycloak 전달 실패: {} {} err={}", method, request.getRequestURI(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"temporarily_unavailable\",\"error_description\":\"E-IDO-116 IdP 연결 실패\"}".getBytes());
        }
    }

    HttpHeaders forwardHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                String lower = name.toLowerCase(Locale.ROOT);
                if (HOP_BY_HOP.contains(lower) || lower.startsWith("x-forwarded-")) continue;
                headers.addAll(name, Collections.list(request.getHeaders(name)));
            }
        }
        URI pub = URI.create(props.publicBase());
        headers.set("X-Forwarded-Host", pub.getPort() > 0 ? pub.getHost() + ":" + pub.getPort() : pub.getHost());
        headers.set("X-Forwarded-Proto", pub.getScheme());
        headers.set("X-Forwarded-Port", String.valueOf(pub.getPort() > 0 ? pub.getPort() : ("https".equals(pub.getScheme()) ? 443 : 80)));
        String remote = request.getRemoteAddr();
        if (remote != null) headers.set("X-Forwarded-For", remote);
        return headers;
    }

    HttpHeaders responseHeaders(HttpHeaders kc) {
        HttpHeaders out = new HttpHeaders();
        if (kc == null) return out;
        String internalBase = keycloak.getBaseUrl();
        kc.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower)) return;
            if ("location".equals(lower)) {
                List<String> rewritten = values.stream()
                        .map(v -> v.startsWith(internalBase) ? props.publicBase() + v.substring(internalBase.length()) : v).toList();
                out.addAll(name, rewritten);
            } else {
                out.addAll(name, values);
            }
        });
        return out;
    }
}
