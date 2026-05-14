package kr.go.smes.ido.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.go.smes.ido.config.FeatureFlags;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * F-26: X-Internal-Sig HMAC-SHA256 인바운드 서명 검증 필터 (Sprint 17)
 *
 * <h3>적용 경로</h3>
 * <pre>
 *   POST /api/v1/agency/gateway/inbound/event
 * </pre>
 * 그 외 경로는 즉시 통과(pass-through).
 *
 * <h3>동작 원리</h3>
 * <ol>
 *   <li>F-26({@code IDO_HMAC_SIG_REQUIRED=false}) → 헤더 없어도 통과 (Phase 1~3)</li>
 *   <li>F-26({@code IDO_HMAC_SIG_REQUIRED=true})  → X-Internal-Sig 필수 검증 (Phase 4)</li>
 *   <li>서명 페이로드: {@code "{agencyCode}:{idempotencyKey}:{epochSeconds}"}</li>
 *   <li>허용 타임스탬프 편차: ±{@link #TTL_SECONDS}초 (네트워크 지연 + 시계 편차 허용)</li>
 *   <li>기관별 독립 HMAC 키: {@link AgencyHmacKeyStore}에서 조회</li>
 *   <li>상수시간 비교({@link MessageDigest#isEqual})로 타이밍 공격 방어</li>
 * </ol>
 *
 * <h3>서명 생성 예시 (연동 기관 측 코드)</h3>
 * <pre>{@code
 * // 서명 페이로드: "{agencyCode}:{idempotencyKey}:{epochSeconds}"
 * long epoch   = Instant.now().getEpochSecond();
 * String payload = agencyCode + ":" + idempotencyKey + ":" + epoch;
 * String sig   = hmacSha256Hex(payload, sharedSecret);
 * // 헤더: X-Internal-Sig: {sig}
 * }</pre>
 *
 * <h3>F-26 활성화 체크리스트 (Phase 4 진입 전 필수)</h3>
 * <ol>
 *   <li>모든 연동 기관 SDK 버전 ≥ 1.3.0 배포 확인</li>
 *   <li>기관별 HMAC 키가 K8s Secret {@code ido-gateway-hmac-keys}에 등록 완료</li>
 *   <li>Staging 환경에서 최소 48시간 서명 검증 통과 확인</li>
 *   <li>F-26=true 전환 후 5분 이내 401 급증 시 즉시 false 롤백</li>
 * </ol>
 *
 * @see AgencyHmacKeyStore
 * @see FeatureFlags#isHmacSigRequired()
 * @see docs/features/F-26-hmac-sig.md
 */
@Slf4j
@Component
@Order(10)   // SecurityHeadersFilter(1) 이후, 인터셉터 이전
@RequiredArgsConstructor
public class HmacSignatureFilter extends OncePerRequestFilter {

    // ── 헤더 상수 ─────────────────────────────────────────────────────────
    static final String HEADER_INTERNAL_SIG    = "X-Internal-Sig";
    static final String HEADER_AGENCY_CODE     = "X-Agency-Code";
    static final String HEADER_IDEMPOTENCY_KEY = "X-Idempotency-Key";

    /** 서명 타임스탬프 유효 범위(초, 양방향). 기본 60초 */
    static final int TTL_SECONDS = 60;

    /** HMAC-SHA256 알고리즘 상수 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 인바운드 이벤트 수신 경로 */
    private static final String INBOUND_PATH = "/api/v1/agency/gateway/inbound/event";

    private final FeatureFlags        featureFlags;
    private final AgencyHmacKeyStore  hmacKeyStore;

    // ════════════════════════════════════════════════════════════════════════
    // Filter 실행
    // ════════════════════════════════════════════════════════════════════════

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain         chain)
            throws ServletException, IOException {

        // ① 대상 경로 + POST 메서드가 아니면 즉시 통과
        if (!isInboundEvent(request)) {
            chain.doFilter(request, response);
            return;
        }

        // ② F-26 OFF → 헤더 있으면 검증, 없으면 통과 (소프트 모드)
        if (!featureFlags.isHmacSigRequired()) {
            String sig = request.getHeader(HEADER_INTERNAL_SIG);
            if (sig == null || sig.isBlank()) {
                // 헤더 없음 — 통과하되 경고 로그 (Phase 4 준비 유도)
                log.debug("[HmacFilter] X-Internal-Sig 헤더 없음 (F-26=false, 소프트 모드) — 통과. " +
                          "Phase 4 전환 전 기관 SDK 업데이트 필요.");
                chain.doFilter(request, response);
                return;
            }
            // 헤더가 있으면 검증 시도 (있는 경우만 검사)
            if (!doVerify(request, response, sig)) return;
            chain.doFilter(request, response);
            return;
        }

        // ③ F-26 ON → X-Internal-Sig 필수
        String sig = request.getHeader(HEADER_INTERNAL_SIG);
        if (sig == null || sig.isBlank()) {
            log.warn("[HmacFilter] X-Internal-Sig 헤더 누락 (F-26=true 필수화). " +
                     "agencyCode={} idempotencyKey={}",
                     request.getHeader(HEADER_AGENCY_CODE),
                     request.getHeader(HEADER_IDEMPOTENCY_KEY));
            sendUnauthorized(response, "MISSING_HMAC_SIGNATURE",
                    "X-Internal-Sig 헤더가 필요합니다. (F-26 HMAC 서명 필수화 활성 중)");
            return;
        }

        if (!doVerify(request, response, sig)) return;
        chain.doFilter(request, response);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 서명 검증 핵심 로직
    // ════════════════════════════════════════════════════════════════════════

    /**
     * HMAC-SHA256 서명 검증.
     *
     * <p>페이로드: {@code "{agencyCode}:{idempotencyKey}:{epochSeconds}"}
     * ±{@value #TTL_SECONDS}초 범위의 epochSeconds 후보를 전수 검사.
     *
     * @return true = 검증 통과 / false = 거부(401 응답 전송 완료)
     */
    private boolean doVerify(HttpServletRequest  request,
                              HttpServletResponse response,
                              String              receivedSig) throws IOException {

        String agencyCode     = request.getHeader(HEADER_AGENCY_CODE);
        String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY_KEY);

        // agencyCode 없으면 키 조회 불가 → 거부
        if (agencyCode == null || agencyCode.isBlank()) {
            log.warn("[HmacFilter] X-Agency-Code 헤더 없음 — HMAC 키 조회 불가");
            sendUnauthorized(response, "MISSING_AGENCY_CODE",
                    "X-Agency-Code 헤더가 필요합니다.");
            return false;
        }

        // 기관별 HMAC 키 조회
        String secret = hmacKeyStore.findSecret(agencyCode);
        if (secret == null || secret.isBlank()) {
            log.warn("[HmacFilter] 기관 HMAC 키 미등록: agencyCode={}", agencyCode);
            sendUnauthorized(response, "HMAC_KEY_NOT_FOUND",
                    "해당 기관의 HMAC 서명 키가 등록되지 않았습니다. agencyCode=" + agencyCode);
            return false;
        }

        // ±TTL 범위 epochSeconds 전수 검사
        long nowEpoch = System.currentTimeMillis() / 1000L;
        String safeIdempotencyKey = (idempotencyKey != null) ? idempotencyKey : "";

        for (long delta = -TTL_SECONDS; delta <= TTL_SECONDS; delta++) {
            long candidate = nowEpoch + delta;
            try {
                String expected = computeHmac(agencyCode, safeIdempotencyKey, candidate, secret);
                // 상수시간 비교 (타이밍 공격 방지)
                if (constantTimeEquals(expected, receivedSig)) {
                    if (Math.abs(delta) > 10) {
                        log.debug("[HmacFilter] 서명 유효 (시계 편차 {}초): agencyCode={}", delta, agencyCode);
                    }
                    return true;
                }
            } catch (Exception e) {
                log.error("[HmacFilter] HMAC 계산 오류: agencyCode={} delta={} err={}",
                          agencyCode, delta, e.getMessage());
                sendUnauthorized(response, "HMAC_COMPUTE_ERROR", "서명 검증 처리 중 오류가 발생했습니다.");
                return false;
            }
        }

        log.warn("[HmacFilter] X-Internal-Sig 서명 불일치 — ±{}초 범위 전수 검증 실패: agencyCode={} idempotencyKey={}",
                 TTL_SECONDS, agencyCode, safeIdempotencyKey);
        sendUnauthorized(response, "INVALID_HMAC_SIGNATURE",
                "X-Internal-Sig 서명이 유효하지 않습니다. (타임스탬프 편차 초과 또는 키 불일치)");
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // private: 유틸리티
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 대상 요청 여부 판별.
     * POST /api/v1/agency/gateway/inbound/event 만 검증 대상.
     */
    private boolean isInboundEvent(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) &&
               INBOUND_PATH.equals(request.getRequestURI());
    }

    /**
     * HMAC-SHA256("{agencyCode}:{idempotencyKey}:{epochSeconds}", secret) → 소문자 Hex
     */
    private String computeHmac(String agencyCode,
                                String idempotencyKey,
                                long   epochSeconds,
                                String secret) throws Exception {
        String payload = agencyCode + ":" + idempotencyKey + ":" + epochSeconds;
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(rawHmac);
    }

    /**
     * 상수시간 문자열 비교 (타이밍 공격 방지).
     *
     * <p>{@link MessageDigest#isEqual}은 두 배열 길이가 달라도 일정 시간을
     * 소비하므로 길이 차이를 이용한 타이밍 공격을 방지합니다.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.toLowerCase().getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    /**
     * 401 Unauthorized JSON 응답 전송.
     *
     * <p>응답 바디에 errorCode/message를 포함하여 기관 개발팀이
     * 오류 원인을 진단할 수 있도록 합니다.
     * rawKey/secret은 절대 포함하지 않습니다.
     */
    private void sendUnauthorized(HttpServletResponse response,
                                  String errorCode,
                                  String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"error\":\"%s\",\"message\":\"%s\",\"hint\":\"X-Internal-Sig 서명 방법: docs/features/F-26-hmac-sig.md\"}",
                errorCode, message.replace("\"", "'")));
    }
}
