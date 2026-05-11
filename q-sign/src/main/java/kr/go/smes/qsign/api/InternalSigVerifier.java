package kr.go.smes.qsign.api;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * GAP-QS-04: X-Internal-Sig HMAC-SHA256 수신 측 검증기 (Q-Sign 모듈)
 * 설계서 §9.4 / §11.7 — IdO → Q-Sign 내부 서명 검증 규칙
 *
 * <p>IdO가 {@code POST /api/v1/auth/broker-input} 호출 시 첨부하는
 * {@code X-Internal-Sig} 헤더를 검증한다.
 *
 * <p><b>서명 페이로드 규칙</b>:
 * <pre>
 *   payload  = "{correlationId}:{epochSeconds}"
 *   sig      = HMAC-SHA256(payload, sharedSecret) → Hex 문자열
 * </pre>
 *
 * <p><b>검증 절차</b>:
 * <ol>
 *   <li>X-Internal-Sig 헤더가 null 이거나 비어 있으면 → 거부 (false)</li>
 *   <li>현재 시각 기준 ±{@code ttlSeconds}초 범위의 epochSeconds 후보를 전수 검사</li>
 *   <li>하나라도 HMAC 일치 → 검증 통과 (true)</li>
 *   <li>모두 불일치 → 거부 (false)</li>
 * </ol>
 *
 * <p>PoC에서 IdO {@code BrokerService.buildInternalSig()} 는 단순 접두어 방식이므로,
 * q-sign 모드(PoC)에서는 서명 검증을 건너뛸 수 있도록 {@code strict-mode} 설정을 제공한다.
 * Keycloak 모드(운영)에서는 {@code KeycloakCallbackService}가 정식 HMAC 서명을 생성하므로
 * 검증이 항상 수행된다.
 */
@Slf4j
@Component
public class InternalSigVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final String INSECURE_DEFAULT = "ido-internal-secret";
    private static final int    MIN_SECRET_LENGTH = 32;

    /**
     * IdO 와 공유하는 비밀키 (docker-compose: IDO_INTERNAL_SIG_SECRET)
     * 설계서 §9.4 / Q-Sign application.yml: qsign.ido.internal-sig-secret
     * 환경변수 IDO_INTERNAL_SIG_SECRET 필수 설정 (기본값 없음)
     */
    @Value("${qsign.ido.internal-sig-secret:}")
    private String sigSecret;

    /**
     * 기동 시 내부 서명 비밀키 및 strict-mode 보안 검증
     *
     * <p><b>[P1 강화]</b> strict-mode=false + sigSecret 미설정 조합은 SSO 내부 API를
     * 완전히 무방비 상태로 노출한다. 해당 조합을 감지하면 ERROR 레벨로 경고하여
     * 운영 배포 전 반드시 수정하도록 유도한다.
     */
    @PostConstruct
    void validateSigSecret() {
        if (sigSecret == null || sigSecret.isBlank()) {
            log.error("[QSign-InternalSigVerifier][P1-보안경고] IDO_INTERNAL_SIG_SECRET 환경변수 미설정. " +
                      "내부 서명 검증이 모든 요청에 대해 실패합니다. 즉시 설정하세요.");
            if (!strictMode) {
                log.error("[QSign-InternalSigVerifier][P1-치명적경고] strict-mode=false + sigSecret 미설정 조합 감지! " +
                          "/api/v1/auth/broker-input 엔드포인트가 서명 검증 없이 모든 요청을 허용합니다. " +
                          "운영 배포 전 QSIGN_INTERNAL_SIG_STRICT=true 및 IDO_INTERNAL_SIG_SECRET 설정 필수!");
            }
        } else if (INSECURE_DEFAULT.equals(sigSecret)) {
            log.error("[QSign-InternalSigVerifier][P1-보안경고] IDO_INTERNAL_SIG_SECRET가 기본값('ido-internal-secret')입니다. " +
                      "운영 환경에서는 반드시 최소 32자 이상의 무작위 비밀값으로 교체하세요.");
        } else if (sigSecret.length() < MIN_SECRET_LENGTH) {
            log.warn("[QSign-InternalSigVerifier][P1-보안경고] IDO_INTERNAL_SIG_SECRET 길이 부족: 현재={}자, 권장={}자 이상.",
                     sigSecret.length(), MIN_SECRET_LENGTH);
        }

        if (!strictMode) {
            log.warn("[QSign-InternalSigVerifier][P1-경고] strict-mode=false 로 기동 중입니다. " +
                     "X-Internal-Sig 불일치 시 경고만 출력하고 요청을 허용합니다. " +
                     "운영 배포 전 반드시 QSIGN_INTERNAL_SIG_STRICT=true 로 설정하세요. " +
                     "현재 strictMode={}", strictMode);
        } else {
            log.info("[QSign-InternalSigVerifier] strict-mode=true — X-Internal-Sig 검증 활성화됨.");
        }
    }

    /**
     * 타임스탬프 유효 범위 (초, 양방향): 기본 60초
     */
    @Value("${qsign.ido.internal-sig-ttl-seconds:60}")
    private int ttlSeconds;

    /**
     * strict-mode=false 이면 서명 검증을 경고 로그로만 처리하고 통과시킨다.
     * PoC 환경(broker.mode=qsign)에서 IdO가 단순 접두어 서명을 보내는 경우 허용.
     * 운영 환경에서는 반드시 true 로 설정해야 한다.
     */
    @Value("${qsign.ido.internal-sig-strict-mode:true}")
    private boolean strictMode;

    /**
     * X-Internal-Sig 서명 검증
     *
     * @param receivedSig   X-Internal-Sig 헤더 값
     * @param correlationId 요청의 correlationId
     * @return true = 서명 유효 또는 non-strict-mode / false = 서명 거부
     */
    public boolean verify(String receivedSig, String correlationId) {
        if (receivedSig == null || receivedSig.isBlank()) {
            log.warn("[QSign-InternalSigVerifier] X-Internal-Sig 헤더 없음 correlationId={}", correlationId);
            return !strictMode; // strict=false → PoC 허용, strict=true → 거부
        }
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("[QSign-InternalSigVerifier] correlationId 없음 — 서명 검증 불가");
            return !strictMode;
        }
        // 비밀키 미설정 시 즉시 거부 (strict 여부 무관)
        if (sigSecret == null || sigSecret.isBlank()) {
            log.error("[QSign-InternalSigVerifier] sigSecret 미설정 — 모든 내부 서명 검증 거부");
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
                        log.debug("[QSign-InternalSigVerifier] 서명 유효 (시계 편차 {}초) correlationId={}",
                                delta, correlationId);
                    }
                    return true;
                }
            } catch (Exception e) {
                log.error("[QSign-InternalSigVerifier] HMAC 계산 오류 delta={} correlationId={}",
                        delta, correlationId, e);
                return false;
            }
        }

        if (strictMode) {
            log.warn("[QSign-InternalSigVerifier] X-Internal-Sig 서명 불일치 — ±{}초 범위 검증 실패 correlationId={}",
                    ttlSeconds, correlationId);
            return false;
        } else {
            // non-strict 모드: PoC에서 단순 접두어 서명 허용 (경고만 출력)
            log.warn("[QSign-InternalSigVerifier] X-Internal-Sig 불일치이나 non-strict 모드로 통과 correlationId={}",
                    correlationId);
            return true;
        }
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
