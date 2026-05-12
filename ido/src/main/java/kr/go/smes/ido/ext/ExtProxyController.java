package kr.go.smes.ido.ext;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * Q-IM External API Forward Proxy Controller
 *
 * <p>FE(onepass-fe)가 Q-IM(8082)을 직접 호출하는 {@code extInstance} 구조를
 * ido(8083) 경유 방식으로 전환하기 위한 forward proxy 엔드포인트.
 *
 * <h2>보안 설계</h2>
 * <ul>
 *   <li><b>Q3=B 준수</b>: CI 관련 경로({@code /api/ext/ci/**})는 명시적으로 차단.
 *       CI 처리는 반드시 {@code POST /api/v1/auth/ci-token} 엔드포인트를 통해 처리.</li>
 *   <li><b>API Key 주입</b>: FE에 노출되지 않는 서버사이드 API Key를 X-Ext-Api-Key 헤더로 Q-IM에 전달.</li>
 *   <li><b>CORS 보호</b>: Nginx same-origin 프록시가 FE 이외의 직접 호출을 차단.</li>
 *   <li><b>hop-by-hop 헤더 제거</b>: Connection, Transfer-Encoding 등 프록시 체인에서
 *       전달 불가한 헤더는 forward 시 제외.</li>
 * </ul>
 *
 * <h2>엔드포인트 매핑</h2>
 * <pre>
 * [차단]  POST /api/ext/ci/**             → 403 Forbidden (CI 직접 전송 Q3=B 위반 차단)
 * [허용]  ANY  /api/ext/**               → Q-IM {QIM_BASE_URL}/api/ext/** forward proxy
 * </pre>
 *
 * <h2>FE 마이그레이션 가이드</h2>
 * <pre>
 * // 기존 (extInstance — Q-IM 직접 호출, 보안 위반)
 * extInstance.get('/api/ext/member/profile')
 *
 * // 변경 후 (beApiInstance — ido 경유, 보안 준수)
 * beApiInstance.get('/api/ext/member/profile')
 * </pre>
 *
 * <h2>Q-IM 서버 설정</h2>
 * <pre>
 * application.yml:
 *   ido.qim.base-url: ${QIM_BASE_URL:http://localhost:8082}
 *   ido.qim.ext-api-key: ${IDO_QIM_EXT_API_KEY:}
 * </pre>
 *
 * @see kr.go.smes.ido.config.IdoWebConfig  qimRestTemplate 빈 정의
 */
@Slf4j
@RestController
@RequestMapping("/api/ext")
public class ExtProxyController {

    /** Q-IM 외부 API 경로를 forward할 RestTemplate (커넥션 풀 전용) */
    private final RestTemplate qimRestTemplate;

    /** Q-IM 서버 Base URL (환경변수: QIM_BASE_URL, 기본: http://localhost:8082) */
    @Value("${ido.qim.base-url:http://localhost:8082}")
    private String qimBaseUrl;

    /**
     * Q-IM 외부 API 인증 키 (환경변수: IDO_QIM_EXT_API_KEY)
     *
     * <p>FE의 webpack DefinePlugin에 EXT_API_KEY가 번들 노출되는 보안 문제를 해결하기 위해
     * 서버사이드에서 X-Ext-Api-Key 헤더를 주입한다.
     * Q-IM 관리 콘솔에서 발급한 외부 API 키를 환경변수로 주입해야 한다.
     */
    @Value("${ido.qim.ext-api-key:}")
    private String extApiKey;

    /** CI 관련 경로 — Q3=B 보안 정책에 따라 forward proxy 차단 대상 */
    private static final Set<String> BLOCKED_PATH_PREFIXES = Set.of(
            "/ci/",
            "/ci"
    );

    /**
     * 응답 시 FE에 전달하지 않을 hop-by-hop 및 민감 헤더 목록
     * Connection, Transfer-Encoding, Keep-Alive 등은 프록시 체인에서 제거 필요
     */
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS = Set.of(
            "transfer-encoding",
            "connection",
            "keep-alive",
            "te",
            "trailers",
            "upgrade"
    );

    /** 요청 시 Q-IM으로 forward하지 않을 헤더 (ido 내부용 또는 hop-by-hop) */
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

    public ExtProxyController(@Qualifier("qimRestTemplate") RestTemplate qimRestTemplate) {
        this.qimRestTemplate = qimRestTemplate;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 보안 차단: CI 직접 전송 경로 (Q3=B)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CI 관련 경로 접근 차단 (Q3=B 보안 정책)
     *
     * <p>FE에서 CI를 직접 Q-IM에 전송하는 패턴을 서버사이드에서 명시적으로 차단한다.
     * CI 처리가 필요한 경우 {@code POST /api/v1/auth/ci-token} 엔드포인트를 사용해야 한다.
     *
     * @return 403 Forbidden + 보안 정책 안내 메시지
     */
    @RequestMapping("/ci/**")
    public ResponseEntity<String> blockCiDirectAccess() {
        log.warn("[EXT-PROXY][보안차단] CI 직접 전송 경로 접근 차단 — Q3=B 정책 위반. " +
                 "CI 처리는 POST /api/v1/auth/ci-token 엔드포인트를 사용하세요.");
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"CI_DIRECT_ACCESS_BLOCKED\"," +
                      "\"message\":\"CI 직접 전송은 보안 정책(Q3=B)에 의해 차단됩니다. POST /api/v1/auth/ci-token을 사용하세요.\"}");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Forward Proxy: /api/ext/** → Q-IM
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET 요청 forward proxy
     */
    @GetMapping("/**")
    public ResponseEntity<byte[]> proxyGet(HttpServletRequest request) throws IOException {
        return forward(request, HttpMethod.GET, null);
    }

    /**
     * POST 요청 forward proxy
     */
    @PostMapping("/**")
    public ResponseEntity<byte[]> proxyPost(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        return forward(request, HttpMethod.POST, body);
    }

    /**
     * PUT 요청 forward proxy
     */
    @PutMapping("/**")
    public ResponseEntity<byte[]> proxyPut(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        return forward(request, HttpMethod.PUT, body);
    }

    /**
     * PATCH 요청 forward proxy
     */
    @PatchMapping("/**")
    public ResponseEntity<byte[]> proxyPatch(HttpServletRequest request) throws IOException {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        return forward(request, HttpMethod.PATCH, body);
    }

    /**
     * DELETE 요청 forward proxy
     */
    @DeleteMapping("/**")
    public ResponseEntity<byte[]> proxyDelete(HttpServletRequest request) throws IOException {
        return forward(request, HttpMethod.DELETE, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Q-IM으로 HTTP 요청을 forward한다.
     *
     * <p>요청 흐름:
     * <ol>
     *   <li>요청 경로에서 {@code /api/ext} 접두사를 추출하여 Q-IM URL 구성</li>
     *   <li>요청 헤더를 필터링하여 forward (hop-by-hop 헤더 제외)</li>
     *   <li>서버사이드 API Key 주입 (X-Ext-Api-Key)</li>
     *   <li>Q-IM 응답을 그대로 FE에 반환 (hop-by-hop 헤더 제외)</li>
     * </ol>
     *
     * @param request    원본 FE HTTP 요청
     * @param method     HTTP 메서드
     * @param requestBody 요청 바디 (GET/DELETE는 null)
     * @return Q-IM 응답 (상태코드, 헤더, 바디 그대로 전달)
     */
    private ResponseEntity<byte[]> forward(HttpServletRequest request,
                                           HttpMethod method,
                                           byte[] requestBody) {
        // 1. Q-IM 대상 URL 구성 (/api/ext/... → {qimBaseUrl}/api/ext/...)
        String requestUri = request.getRequestURI();
        String queryString = request.getQueryString();

        String targetUrl = qimBaseUrl + requestUri;
        if (queryString != null && !queryString.isBlank()) {
            targetUrl = targetUrl + "?" + queryString;
        }

        log.info("[EXT-PROXY] forward {} {} → {}", method, requestUri, targetUrl);

        // 2. 요청 헤더 복사 (hop-by-hop 헤더 제외)
        HttpHeaders headers = buildForwardHeaders(request);

        // 3. Q-IM 응답 수신
        try {
            HttpEntity<byte[]> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<byte[]> qimResponse = qimRestTemplate.exchange(
                    URI.create(targetUrl),
                    method,
                    entity,
                    byte[].class
            );

            log.info("[EXT-PROXY] Q-IM 응답: status={} path={}", qimResponse.getStatusCode(), requestUri);

            // 4. 응답 헤더 필터링 (hop-by-hop 제거)
            HttpHeaders responseHeaders = buildResponseHeaders(qimResponse.getHeaders());

            return ResponseEntity
                    .status(qimResponse.getStatusCode())
                    .headers(responseHeaders)
                    .body(qimResponse.getBody());

        } catch (HttpStatusCodeException e) {
            // Q-IM이 4xx/5xx 반환 시 동일 상태코드로 FE에 전달
            log.warn("[EXT-PROXY] Q-IM 오류 응답: status={} path={} body={}",
                    e.getStatusCode(), requestUri, e.getResponseBodyAsString());
            HttpHeaders responseHeaders = buildResponseHeaders(e.getResponseHeaders());
            return ResponseEntity
                    .status(e.getStatusCode())
                    .headers(responseHeaders)
                    .body(e.getResponseBodyAsByteArray());

        } catch (Exception e) {
            log.error("[EXT-PROXY] Q-IM forward 실패: path={} err={}", requestUri, e.getMessage(), e);
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"error\":\"EXT_PROXY_ERROR\",\"message\":\"Q-IM 서버 통신 오류: " +
                           e.getMessage() + "\"}").getBytes());
        }
    }

    /**
     * FE 요청 헤더를 Q-IM forward용으로 필터링하여 빌드한다.
     *
     * <p>hop-by-hop 헤더를 제거하고, 서버사이드 API Key를 주입한다.
     *
     * @param request 원본 FE 요청
     * @return Q-IM으로 forward할 헤더
     */
    private HttpHeaders buildForwardHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();

        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement().toLowerCase();
                // hop-by-hop 헤더 및 내부 보안 헤더 제외
                if (EXCLUDED_REQUEST_HEADERS.contains(headerName)) {
                    continue;
                }
                // X-Ext-Api-Key는 서버사이드에서 주입하므로 FE 원본값 무시
                if ("x-ext-api-key".equals(headerName)) {
                    continue;
                }
                List<String> values = Collections.list(request.getHeaders(headerName));
                headers.addAll(headerName, values);
            }
        }

        // 서버사이드 API Key 주입 (FE 번들 노출 방지)
        if (extApiKey != null && !extApiKey.isBlank()) {
            headers.set("X-Ext-Api-Key", extApiKey);
        } else {
            log.debug("[EXT-PROXY] IDO_QIM_EXT_API_KEY 미설정 — X-Ext-Api-Key 헤더 미주입");
        }

        return headers;
    }

    /**
     * Q-IM 응답 헤더에서 hop-by-hop 헤더를 제거하여 FE에 전달할 헤더를 빌드한다.
     *
     * @param qimHeaders Q-IM 응답 헤더
     * @return FE로 전달할 헤더 (hop-by-hop 제거)
     */
    private HttpHeaders buildResponseHeaders(HttpHeaders qimHeaders) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (qimHeaders == null) {
            return responseHeaders;
        }
        qimHeaders.forEach((name, values) -> {
            if (!EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                responseHeaders.addAll(name, values);
            }
        });
        return responseHeaders;
    }
}
