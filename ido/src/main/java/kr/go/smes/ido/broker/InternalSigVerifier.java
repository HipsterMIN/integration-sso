package kr.go.smes.ido.broker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * X-Internal-Sig HMAC-SHA256 수신 측 검증기
 * 설계서 §9.4 — Q-Sign → IdO 내부 서명 검증 규칙
 *
 * <p><b>서명 페이로드 규칙</b>:
 * <pre>
 *   payload  = "{correlationId}:{epochSeconds}"
 *   sig      = HMAC-SHA256(payload, sharedSecret) → Hex 문자열
 * </pre>
 *
 * <p><b>검증 절차</b>:
 * <ol>
 *   <li>X-Internal-Sig 헤더가 null 이거나 비어 있으면 → 거부</li>
 *   <li>수신된 서명에서 타임스탬프를 역산할 수 없으므로,
 *       현재 시각 기준 ±{@code ttlSeconds}초 범위 내 epochSeconds 후보를 전수 검사</li>
 *   <li>하나라도 HMAC 일치 → 검증 통과</li>
 *   <li>모두 불일치 → 거부 (401 / 403)</li>
 * </ol>
 *
 * <p><b>왜 전수 검사인가</b>:
 * 서명 페이로드에 epochSeconds가 포함되어 있으나 서명 수신 측은 발신 측이
 * 사용한 epochSeconds 값을 알 수 없다. 따라서 ±TTL 범위 안의 모든 초를
 * 재계산하여 하나라도 일치하면 유효 서명으로 판정한다.
 * (네트워크 지연 + 서버 간 시계 편차 허용)
 */
@Slf4j
@Component
public class InternalSigVerifier {

    /** HMAC-SHA256 알고리즘 상수 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Q-Sign 과 동일한 공유 비밀키 (설계서 §9.4) */
    @Value("${ido.qsign.internal-sig-secret:ido-internal-secret}")
    private String sigSecret;

    /** 타임스탬프 유효 범위 (초, 양방향): 기본 60초 */
    @Value("${ido.qsign.internal-sig-ttl-seconds:60}")
    private int ttlSeconds;

    /**
     * X-Internal-Sig 서명 검증
     *
     * @param receivedSig   X-Internal-Sig 헤더 값
     * @param correlationId X-Correlation-Id 또는 요청 내 correlationId
     * @return true = 서명 유효 / false = 거부
     */
    public boolean verify(String receivedSig, String correlationId) {
        if (receivedSig == null || receivedSig.isBlank()) {
            log.warn("[InternalSigVerifier] X-Internal-Sig 헤더 없음 correlationId={}", correlationId);
            return false;
        }
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("[InternalSigVerifier] correlationId 없음 — 서명 검증 불가");
            return false;
        }

        long nowEpochSeconds = System.currentTimeMillis() / 1000L;

        // ±ttlSeconds 범위 내 epochSeconds 후보를 전수 검사
        for (long delta = -ttlSeconds; delta <= ttlSeconds; delta++) {
            long candidate = nowEpochSeconds + delta;
            try {
                String expected = computeHmac(correlationId, candidate);
                if (expected.equalsIgnoreCase(receivedSig)) {
                    if (Math.abs(delta) > 10) {
                        log.debug("[InternalSigVerifier] 서명 유효 (시계 편차 {}초) correlationId={}",
                                delta, correlationId);
                    }
                    return true;
                }
            } catch (Exception e) {
                log.error("[InternalSigVerifier] HMAC 계산 오류 delta={} correlationId={}",
                        delta, correlationId, e);
                return false;
            }
        }

        log.warn("[InternalSigVerifier] X-Internal-Sig 서명 불일치 — ±{}초 범위 검증 실패 correlationId={}",
                ttlSeconds, correlationId);
        return false;
    }

    /**
     * HMAC-SHA256("{correlationId}:{epochSeconds}", secret) → Hex 문자열
     */
    private String computeHmac(String correlationId, long epochSeconds) throws Exception {
        String payload = correlationId + ":" + epochSeconds;
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(
                sigSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(rawHmac);
    }
}
