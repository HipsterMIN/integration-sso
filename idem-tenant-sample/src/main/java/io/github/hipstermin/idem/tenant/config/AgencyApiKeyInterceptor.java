package io.github.hipstermin.idem.tenant.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 기관 → IdO 호출 보호 인터셉터
 *
 * <p>X-Agency-Code + X-Agency-Key 헤더를 검증하여
 * 등록되지 않은 기관 또는 유효하지 않은 API Key의 접근을 차단.
 *
 * <p><b>검증 흐름</b>:
 * <pre>
 * 요청 헤더
 *   X-Agency-Code: AGENCY_STUB_001
 *   X-Agency-Key:  stub-api-key-dev-001  (원문)
 *
 * 검증 로직:
 *   1. X-Agency-Code 존재 확인
 *   2. X-Agency-Key 존재 확인
 *   3. SHA-256(rawKey) 계산
 *   4. agency_api_key 테이블에서 agency_code + key_hash + active 조회
 *   5. 만료 여부 (expires_at) 확인
 *   6. 실패 시 401 반환
 * </pre>
 *
 * <p><b>보안 원칙</b>:
 * - rawApiKey 원문은 로그에 절대 기록하지 않음
 * - 타이밍 공격 방지: SHA-256 비교는 MessageDigest.isEqual() 대신 DB 조회로 처리
 * - PoC에서는 단순 DB 조회; 운영에서는 Vault/KMS 연동 권장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgencyApiKeyInterceptor implements HandlerInterceptor {

    private static final String HEADER_AGENCY_CODE = "X-Agency-Code";
    private static final String HEADER_AGENCY_KEY  = "X-Agency-Key";

    private final JdbcTemplate jdbcTemplate;

    @Value("${agency-stub.code:AGENCY_STUB_001}")
    private String myAgencyCode;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String agencyCode = request.getHeader(HEADER_AGENCY_CODE);
        String rawApiKey  = request.getHeader(HEADER_AGENCY_KEY);

        if (agencyCode == null || agencyCode.isBlank()) {
            log.warn("[AgencyApiKeyInterceptor] X-Agency-Code 헤더 누락: uri={}",
                    request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("{\"error\":\"MISSING_AGENCY_CODE\"}");
            return false;
        }

        if (rawApiKey == null || rawApiKey.isBlank()) {
            log.warn("[AgencyApiKeyInterceptor] X-Agency-Key 헤더 누락: agencyCode={} uri={}",
                    agencyCode, request.getRequestURI());
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.getWriter().write("{\"error\":\"MISSING_API_KEY\"}");
            return false;
        }

        // SHA-256(rawApiKey) 계산
        String keyHash = sha256Hex(rawApiKey);

        // DB 조회: agency_code + key_hash 매칭 + 활성 + 미만료
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(1)
                    FROM   agency_stub.agency_api_key
                    WHERE  agency_code  = ?
                      AND  api_key_hash = ?
                      AND  active       = TRUE
                      AND  (expires_at IS NULL OR expires_at > NOW())
                      AND  revoked_at IS NULL
                    """,
                    Integer.class, agencyCode, keyHash);

            if (count == null || count == 0) {
                log.warn("[AgencyApiKeyInterceptor] API Key 인증 실패: agencyCode={} prefix={}",
                        agencyCode, mask(rawApiKey));
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"INVALID_API_KEY\"}");
                return false;
            }

        } catch (Exception e) {
            log.error("[AgencyApiKeyInterceptor] API Key 검증 중 오류: agencyCode={} err={}",
                    agencyCode, e.getMessage());
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.getWriter().write("{\"error\":\"AUTH_CHECK_FAILED\"}");
            return false;
        }

        log.debug("[AgencyApiKeyInterceptor] API Key 인증 성공: agencyCode={}", agencyCode);
        // 검증된 agencyCode를 요청 속성으로 전달 (컨트롤러에서 사용)
        request.setAttribute("validatedAgencyCode", agencyCode);
        return true;
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** API Key 앞 6자만 표시 (로그용) */
    private String mask(String key) {
        if (key == null || key.length() <= 6) return "***";
        return key.substring(0, 6) + "***";
    }
}
