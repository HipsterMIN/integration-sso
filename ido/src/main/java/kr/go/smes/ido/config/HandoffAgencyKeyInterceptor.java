package kr.go.smes.ido.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * IdO Handoff Verify/Issue API — X-Agency-Key 검증 인터셉터
 *
 * <p>모든 기관이 {@code POST /api/v1/handoff/verify} 를 호출할 때
 * {@code X-Agency-Code} 와 {@code X-Agency-Key} 헤더를 검증합니다.
 *
 * <p><b>검증 순서</b>:
 * <ol>
 *   <li>헤더 존재 여부 확인 (X-Agency-Code, X-Agency-Key 필수)</li>
 *   <li>SHA-256(rawKey) 를 {@code ido.agency_meta.api_key_hash} 와 상수시간 비교</li>
 *   <li>기관 레코드 {@code active = true} 확인</li>
 *   <li>실패 시 401 반환 — rawKey 는 절대 로그에 기록하지 않음</li>
 * </ol>
 *
 * <p><b>보안 원칙</b>:
 * <ul>
 *   <li>rawKey 평문은 절대 로그·DB에 저장하지 않음</li>
 *   <li>DB 비교는 {@link MessageDigest#isEqual} 상수시간 비교로 타이밍 공격 방지</li>
 *   <li>키 해시 불일치 시에도 동일한 에러 메시지 반환 (정보 노출 방지)</li>
 *   <li>검증 성공 시 agencyCode 를 Request Attribute 에 저장 → 컨트롤러에서 활용</li>
 * </ul>
 *
 * <p><b>등록 경로</b>: {@link IdoWebMvcConfig} 에서
 * {@code /api/v1/handoff/verify} 와 {@code /api/v1/handoff/issue} 에 적용.
 *
 * @see IdoWebMvcConfig
 * @see kr.go.smes.ido.api.HandoffController
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffAgencyKeyInterceptor implements HandlerInterceptor {

    /** Request Attribute 키 — 이후 컨트롤러에서 agencyCode 추출용 */
    public static final String ATTR_VALIDATED_AGENCY_CODE = "validatedAgencyCode";

    private static final String HEADER_AGENCY_CODE = "X-Agency-Code";
    private static final String HEADER_AGENCY_KEY  = "X-Agency-Key";

    private final JdbcTemplate jdbcTemplate;

    // ════════════════════════════════════════════════════════════════════════
    // HandlerInterceptor
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull Object              handler) throws Exception {

        String agencyCode = request.getHeader(HEADER_AGENCY_CODE);
        String rawKey     = request.getHeader(HEADER_AGENCY_KEY);

        // ① 헤더 존재 확인
        if (isBlank(agencyCode) || isBlank(rawKey)) {
            log.warn("[HandoffAgencyKeyInterceptor] 필수 헤더 누락: {}-present={} {}-present={} uri={}",
                    HEADER_AGENCY_CODE, !isBlank(agencyCode),
                    HEADER_AGENCY_KEY,  !isBlank(rawKey),
                    request.getRequestURI());
            sendUnauthorized(response, "MISSING_AGENCY_CREDENTIALS");
            return false;
        }

        // ② SHA-256(rawKey) 계산
        String incomingHash = sha256Hex(rawKey);

        // ③ DB 조회 — agency_meta 에서 api_key_hash + active 확인
        //    존재하지 않거나 inactive 이면 null 반환
        String storedHash = queryStoredHash(agencyCode);

        // ④ 상수시간 비교 (타이밍 공격 방지)
        if (storedHash == null || !constantTimeEquals(incomingHash, storedHash)) {
            log.warn("[HandoffAgencyKeyInterceptor] API Key 검증 실패: agencyCode={} uri={}",
                    agencyCode, request.getRequestURI());
            sendUnauthorized(response, "INVALID_AGENCY_CREDENTIALS");
            return false;
        }

        // ⑤ 성공 — Request Attribute 에 검증된 agencyCode 저장
        request.setAttribute(ATTR_VALIDATED_AGENCY_CODE, agencyCode);
        log.debug("[HandoffAgencyKeyInterceptor] API Key 검증 성공: agencyCode={} uri={}",
                agencyCode, request.getRequestURI());
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    /**
     * ido.agency_meta 에서 api_key_hash 조회.
     * active = TRUE 인 레코드만 대상.
     *
     * @return SHA-256 hex 문자열, 없거나 inactive 이면 null
     */
    private String queryStoredHash(String agencyCode) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT api_key_hash FROM ido.agency_meta " +
                    "WHERE agency_code = ? AND active = TRUE " +
                    "LIMIT 1",
                    String.class,
                    agencyCode
            );
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            log.warn("[HandoffAgencyKeyInterceptor] agency_meta 레코드 없음 또는 inactive: agencyCode={}", agencyCode);
            return null;
        } catch (Exception e) {
            log.error("[HandoffAgencyKeyInterceptor] DB 조회 오류: agencyCode={} err={}", agencyCode, e.getMessage());
            return null;
        }
    }

    /**
     * SHA-256(input) → 소문자 HEX 64자
     */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 상수시간 문자열 비교 (타이밍 공격 방지).
     *
     * <p>두 문자열의 길이가 다르면 false 를 즉시 반환하지 않고
     * 동일 길이(64바이트)로 패딩해 비교합니다.
     */
    private boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    private void sendUnauthorized(HttpServletResponse response, String errorCode) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"" + errorCode + "\",\"message\":\"Agency authentication failed\"}"
        );
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
