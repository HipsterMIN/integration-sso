package io.github.hipstermin.idem.authz.config;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * q-authz 내부 API 키 검증 인터셉터.
 *
 * <p>{@code /api/v1/internal/**} 는 ido(PEP/Claim Issuer)·onepass-admin(PAP)만 호출 가능.
 * 헤더 {@code X-Internal-Api-Key}를 {@code IDEM_AUTHZ_INTERNAL_API_KEY}와 상수시간 비교한다.
 *
 * <p><b>fail-closed</b>: 서버 키 미설정 시 모든 내부 요청을 401로 거부 — 설정 누락으로
 * 인가 API가 무방비 노출되는 사고를 차단한다.
 */
@Slf4j
@Component
public class InternalApiKeyInterceptor implements HandlerInterceptor {

    private static final String HEADER_INTERNAL_API_KEY = "X-Internal-Api-Key";

    private final String internalApiKey;

    private static final java.util.Set<String> HARDENED = java.util.Set.of("prod", "stage");

    /** 테스트·수동 구성용 — 프로파일 강화 검사 없음 */
    public InternalApiKeyInterceptor(String internalApiKey) {
        this(internalApiKey, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InternalApiKeyInterceptor(
            @Value("${idem.authz.security.internal-api-key:}") String internalApiKey,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        this.internalApiKey = internalApiKey;
        // D2 fail-secure: 운영·스테이지에서 키 미설정이면 기동 거부
        boolean hardened = java.util.Arrays.stream(activeProfiles.split(",")).map(String::trim).anyMatch(HARDENED::contains);
        if (hardened && (internalApiKey == null || internalApiKey.isBlank())) {
            throw new IllegalStateException("[InternalApiKeyInterceptor] IDEM_AUTHZ_INTERNAL_API_KEY 미설정 — 운영·스테이지에서는 기동을 거부합니다");
        }
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.warn("[InternalApiKeyInterceptor] IDEM_AUTHZ_INTERNAL_API_KEY 미설정 — " +
                     "모든 /api/v1/internal/** 요청이 401로 거부됩니다. 운영 환경에서 반드시 설정하십시오.");
        } else {
            log.info("[InternalApiKeyInterceptor] 내부 API 키 검증 인터셉터 활성화");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.error("[InternalApiKeyInterceptor] 서버 키 미설정 — 요청 거부: {} {}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, "Internal API key not configured on server");
            return false;
        }

        String requestKey = request.getHeader(HEADER_INTERNAL_API_KEY);
        if (requestKey == null || requestKey.isBlank()) {
            writeUnauthorized(response, "Missing " + HEADER_INTERNAL_API_KEY + " header");
            return false;
        }
        if (!secureEquals(internalApiKey, requestKey)) {
            log.warn("[InternalApiKeyInterceptor] API 키 불일치 — 요청 거부: {} {}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorized(response, "Invalid " + HEADER_INTERNAL_API_KEY);
            return false;
        }
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}");
        response.getWriter().flush();
    }

    private static boolean secureEquals(String expected, String actual) {
        byte[] e = expected.getBytes(StandardCharsets.UTF_8);
        byte[] a = actual.getBytes(StandardCharsets.UTF_8);
        return CryptoProviders.current().constantTimeEquals(e, a);
    }
}
