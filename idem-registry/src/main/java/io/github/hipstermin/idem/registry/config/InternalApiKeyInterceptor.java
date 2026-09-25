package io.github.hipstermin.idem.registry.config;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Q-IM 내부 API 키 검증 인터셉터
 *
 * <p>{@code /api/v1/internal/**} 경로는 IdO 서버만 호출 가능한 내부 전용 엔드포인트.
 * 요청 헤더 {@code X-Internal-Api-Key}를 환경변수 {@code IDEM_REGISTRY_INTERNAL_API_KEY}와 비교하여
 * 불일치·누락 시 즉시 {@code 401 Unauthorized}를 반환한다.
 *
 * <h3>보안 설계</h3>
 * <ul>
 *   <li>상수 시간 비교({@link #secureEquals}): 타이밍 공격 방지</li>
 *   <li>키 미설정 방어: {@code IDEM_REGISTRY_INTERNAL_API_KEY}가 빈 값이면 요청을 전면 거부
 *       → 운영 환경에서 환경변수 미설정 시 실수로 API가 노출되는 경우를 차단</li>
 *   <li>Actuator / Swagger 경로 제외: {@link QimWebMvcConfig}에서 경로 매핑으로 제어</li>
 * </ul>
 *
 * <h3>환경변수</h3>
 * <pre>
 *   IDEM_REGISTRY_INTERNAL_API_KEY=&lt;32자 이상 랜덤 문자열&gt;
 * </pre>
 * application.yml 참고:
 * <pre>
 *   qim:
 *     security:
 *       internal-api-key: ${IDEM_REGISTRY_INTERNAL_API_KEY:}
 * </pre>
 *
 * <p><b>P2 보안 수정</b>: 기존 UserController는 {@code X-Internal-Api-Key} 헤더를
 * 파라미터로만 받고 실제 검증을 수행하지 않았음 → 이 인터셉터로 일괄 검증 추가.
 */
@Slf4j
@Component
public class InternalApiKeyInterceptor implements HandlerInterceptor {

    private static final String HEADER_INTERNAL_API_KEY = "X-Internal-Api-Key";

    /**
     * 환경변수 {@code IDEM_REGISTRY_INTERNAL_API_KEY}로 주입되는 내부 API 키.
     * 기본값은 빈 문자열 — 운영 환경에서 반드시 주입해야 함.
     */
    private final String internalApiKey;

    private static final java.util.Set<String> HARDENED = java.util.Set.of("prod", "stage");

    /** 테스트·수동 구성용 — 프로파일 강화 검사 없음 */
    public InternalApiKeyInterceptor(String internalApiKey) {
        this(internalApiKey, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InternalApiKeyInterceptor(
            @Value("${idem.registry.security.internal-api-key:}") String internalApiKey,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        this.internalApiKey = internalApiKey;

        // D2 fail-secure: 운영·스테이지에서 키 미설정이면 기동 거부 (런타임은 어차피 전면 401 이지만 사고를 부팅 시점에 드러낸다)
        boolean hardened = java.util.Arrays.stream(activeProfiles.split(",")).map(String::trim).anyMatch(HARDENED::contains);
        if (hardened && (internalApiKey == null || internalApiKey.isBlank())) {
            throw new IllegalStateException("[InternalApiKeyInterceptor] IDEM_REGISTRY_INTERNAL_API_KEY 미설정 — 운영·스테이지에서는 기동을 거부합니다");
        }
        // 애플리케이션 기동 시점에 키 미설정 경고
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.warn("[InternalApiKeyInterceptor] IDEM_REGISTRY_INTERNAL_API_KEY 미설정 — " +
                     "모든 /api/v1/internal/** 요청이 401로 거부됩니다. " +
                     "운영 환경에서 반드시 환경변수를 설정하십시오.");
        } else {
            log.info("[InternalApiKeyInterceptor] 내부 API 키 검증 인터셉터 활성화");
        }
    }

    /**
     * {@code X-Internal-Api-Key} 헤더를 검증한다.
     *
     * <p>검증 실패 조건:
     * <ol>
     *   <li>서버 키({@code internalApiKey})가 설정되지 않음 (빈 문자열)</li>
     *   <li>요청 헤더가 없거나 비어 있음</li>
     *   <li>헤더 값이 서버 키와 일치하지 않음 (상수 시간 비교)</li>
     * </ol>
     *
     * @return {@code true} 검증 통과, {@code false} 거부(응답 커밋됨)
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 1. 서버 키 미설정 → 전면 거부 (설정 오류 방지)
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.error("[InternalApiKeyInterceptor] 서버 키 미설정 — 요청 거부: method={} uri={}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, "Internal API key not configured on server");
            return false;
        }

        String requestKey = request.getHeader(HEADER_INTERNAL_API_KEY);

        // 2. 헤더 누락
        if (requestKey == null || requestKey.isBlank()) {
            log.warn("[InternalApiKeyInterceptor] {} 헤더 누락 — 요청 거부: method={} uri={}",
                    HEADER_INTERNAL_API_KEY, request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, "Missing " + HEADER_INTERNAL_API_KEY + " header");
            return false;
        }

        // 3. 키 불일치 (상수 시간 비교)
        if (!secureEquals(internalApiKey, requestKey)) {
            log.warn("[InternalApiKeyInterceptor] API 키 불일치 — 요청 거부: method={} uri={}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, "Invalid " + HEADER_INTERNAL_API_KEY);
            return false;
        }

        return true;
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * 401 응답을 JSON 형식으로 작성한다.
     *
     * <p>Spring의 {@link org.springframework.web.servlet.DispatcherServlet}이
     * 응답을 가로채지 않도록 {@link HttpServletResponse#setStatus}와
     * {@link HttpServletResponse#getWriter()}를 직접 사용한다.
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }

    /**
     * 타이밍 공격 방지를 위한 상수 시간 문자열 비교.
     *
     * <p>{@code MessageDigest.isEqual}을 활용하여 두 문자열의 바이트 배열을
     * 항상 동일한 시간으로 비교한다 (길이가 달라도 전체를 순회).
     */
    private static boolean secureEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actualBytes   = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return CryptoProviders.current().constantTimeEquals(expectedBytes, actualBytes);
    }
}
