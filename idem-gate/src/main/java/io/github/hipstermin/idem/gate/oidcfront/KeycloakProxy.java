package io.github.hipstermin.idem.gate.oidcfront;

import io.github.hipstermin.idem.common.web.RequestPath;
import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    /**
     * 요청 경로·쿼리를 Keycloak 베이스에 붙여 전달한다.
     *
     * <p>1.0.1 (3차 점검 H2): 원본 URI 가 정규형이 아니거나({@code ..}·{@code %2e%2e}·{@code ;}·{@code //}) 정규화한 경로가
     * {@code /realms/<realm>/…}·{@code /resources/…} 밖이면 Keycloak 에 보내지 않고 400 으로 끝낸다. 종전에는 원본을 그대로 붙여
     * {@code /resources/../admin/master/console/} 가 Keycloak 관리 콘솔에, {@code /realms/idem/../idem/protocol/…/token} 이 프런트의
     * client 허용 목록·정책 판정을 건너뛰어 도달했다.
     */
    public ResponseEntity<byte[]> forward(HttpServletRequest request, byte[] body) {
        String raw = request.getRequestURI();
        Optional<String> canonical = RequestPath.canonicalIfSafe(raw);
        if (canonical.isEmpty() || !allowedUpstreamPath(canonical.get())) {
            log.warn("[OIDC-FRONT] 프록시 경로 거부: {} {}", request.getMethod(), raw);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"invalid_request\",\"error_description\":\"invalid path\"}".getBytes());
        }
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
        String target = keycloak.getBaseUrl() + canonical.get() + (query != null && !query.isEmpty() ? "?" + query : "");
        return forwardTo(request, HttpMethod.valueOf(request.getMethod()), target, body);
    }

    /** Keycloak 으로 넘길 수 있는 경로 — 설정된 realm 아래와 정적 자원뿐. 관리 콘솔·다른 realm·동적 등록은 안 된다. */
    boolean allowedUpstreamPath(String path) {
        String realmRoot = "/realms/" + keycloak.getRealm();
        if (path.contains("/clients-registrations/") || path.contains("/protocol/openid-connect/registrations")) return false;
        return path.equals(realmRoot) || path.startsWith(realmRoot + "/") || path.startsWith("/resources/");
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

    /**
     * Keycloak 이 준 Location 이 내부 Keycloak 주소면 공개 gate 주소로 바꾼다. 문자열 접두 비교만 하면 Keycloak 이
     * {@code localhost} 로 답할 때 {@code 127.0.0.1} 로 설정된 내부 주소와 어긋나 내부 URL 이 그대로 새어 나갔다(3차 점검 H2) —
     * 호스트(루프백은 서로 같다고 본다)·포트를 비교한다.
     */
    String rewriteLocation(String location, String internalBase) {
        if (location == null) return null;
        if (location.startsWith(internalBase)) return props.publicBase() + location.substring(internalBase.length());
        try {
            URI l = URI.create(location);
            URI b = URI.create(internalBase);
            if (l.getHost() == null || b.getHost() == null) return location;
            boolean sameHost = l.getHost().equalsIgnoreCase(b.getHost()) || (isLoopback(l.getHost()) && isLoopback(b.getHost()));
            if (!sameHost || effectivePort(l) != effectivePort(b)) return location;
            String rest = (l.getRawPath() == null ? "" : l.getRawPath())
                    + (l.getRawQuery() != null ? "?" + l.getRawQuery() : "")
                    + (l.getRawFragment() != null ? "#" + l.getRawFragment() : "");
            return props.publicBase() + rest;
        } catch (Exception e) {
            return location;
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private static int effectivePort(URI u) {
        return u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
    }

    HttpHeaders responseHeaders(HttpHeaders kc) {
        HttpHeaders out = new HttpHeaders();
        if (kc == null) return out;
        String internalBase = keycloak.getBaseUrl();
        kc.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower)) return;
            if ("location".equals(lower)) {
                List<String> rewritten = values.stream().map(v -> rewriteLocation(v, internalBase)).toList();
                out.addAll(name, rewritten);
            } else {
                out.addAll(name, values);
            }
        });
        return out;
    }
}
