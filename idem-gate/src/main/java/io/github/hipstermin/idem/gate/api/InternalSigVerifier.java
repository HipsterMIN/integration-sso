package io.github.hipstermin.idem.gate.api;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 * <p><b>운영 정책</b>: 서명 검증은 항상 strict 모드로 동작합니다.
 * PoC용 non-strict 코드 경로는 운영 이관 전 완전히 제거되었습니다.
 * IDEM_HUB_INTERNAL_SIG_SECRET 환경변수를 반드시 설정하세요.
 * ⚠️ [REQUIRES_MANUAL] IDEM_HUB_INTERNAL_SIG_SECRET: openssl rand -hex 32
 */
@Slf4j
@Component
public class InternalSigVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final String INSECURE_DEFAULT = "ido-internal-secret";
    private static final int    MIN_SECRET_LENGTH = 32;

    /**
     * IdO 와 공유하는 비밀키 (docker-compose: IDEM_HUB_INTERNAL_SIG_SECRET)
     * 설계서 §9.4 / Q-Sign application.yml: idem.gate.hub.internal-sig-secret
     * 환경변수 IDEM_HUB_INTERNAL_SIG_SECRET 필수 설정 (기본값 없음)
     */
    @Value("${idem.gate.hub.internal-sig-secret:}")
    private String sigSecret;

    /**
     * 기동 시 내부 서명 비밀키 보안 검증
     *
     * <p>서명 검증은 항상 strict 모드로 동작합니다. non-strict 경로는 제거되었습니다.
     * IDEM_HUB_INTERNAL_SIG_SECRET 미설정 시 모든 내부 API 호출이 거부됩니다.
     */
    /** D2 fail-secure: 운영·스테이지 프로파일에서는 비밀키 미설정·기본값이면 기동을 거부한다 */
    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    private static final java.util.Set<String> HARDENED = java.util.Set.of("prod", "stage");

    @PostConstruct
    void validateSigSecret() {
        String profiles = activeProfiles == null ? "" : activeProfiles;
        boolean hardened = java.util.Arrays.stream(profiles.split(",")).map(String::trim).anyMatch(HARDENED::contains);
        boolean missing  = sigSecret == null || sigSecret.isBlank();
        boolean insecure = INSECURE_DEFAULT.equals(sigSecret);
        if (hardened && (missing || insecure)) {
            throw new IllegalStateException("[QSign-InternalSigVerifier] IDEM_HUB_INTERNAL_SIG_SECRET "
                    + (missing ? "미설정" : "이 공개 기본값") + " — 운영·스테이지에서는 기동을 거부합니다 (openssl rand -hex 32 로 생성해 주입)");
        }
        if (missing) {
            log.error("[QSign-InternalSigVerifier][P1-보안경고] IDEM_HUB_INTERNAL_SIG_SECRET 환경변수 미설정. " +
                      "⚠️ [REQUIRES_MANUAL] 모든 내부 서명 검증이 실패합니다. 즉시 설정하세요: openssl rand -hex 32");
        } else if (insecure) {
            log.error("[QSign-InternalSigVerifier][P1-보안경고] IDEM_HUB_INTERNAL_SIG_SECRET가 기본값('ido-internal-secret')입니다. " +
                      "운영 환경에서는 반드시 최소 32자 이상의 무작위 비밀값으로 교체하세요.");
        } else if (sigSecret.length() < MIN_SECRET_LENGTH) {
            log.warn("[QSign-InternalSigVerifier][P1-보안경고] IDEM_HUB_INTERNAL_SIG_SECRET 길이 부족: 현재={}자, 권장={}자 이상.",
                     sigSecret.length(), MIN_SECRET_LENGTH);
        } else {
            log.info("[QSign-InternalSigVerifier] strict 모드 — X-Internal-Sig HMAC-SHA256 검증 활성화됨.");
        }
    }

    /**
     * 타임스탬프 유효 범위 (초, 양방향): 기본 60초
     */
    @Value("${idem.gate.hub.internal-sig-ttl-seconds:60}")
    private int ttlSeconds;

    // NOTE: strict-mode 설정 항목 제거됨 — 서명 검증은 항상 strict 모드로 동작
    // PoC용 idem.gate.hub.internal-sig-strict-mode 설정이 application.yml에 존재하는 경우 제거 가능

    /**
     * X-Internal-Sig 서명 검증
     *
     * @param receivedSig   X-Internal-Sig 헤더 값
     * @param correlationId 요청의 correlationId
     * @return true = 서명 유효 또는 non-strict-mode / false = 서명 거부
     */
    public boolean verify(String receivedSig, String correlationId) {
        // strict 모드: 서명 없음 → 즉시 거부
        if (receivedSig == null || receivedSig.isBlank()) {
            log.warn("[QSign-InternalSigVerifier] X-Internal-Sig 헤더 없음 — 거부 correlationId={}", correlationId);
            return false;
        }
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("[QSign-InternalSigVerifier] correlationId 없음 — 서명 검증 불가 → 거부");
            return false;
        }
        // 비밀키 미설정 시 즉시 거부
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
                if (CryptoProviders.current().constantTimeEquals(expected, receivedSig.toLowerCase())) { // D2-b: 상수 시간 비교
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

        // strict 모드: 불일치 → 거부 (non-strict 경로 제거됨)
        log.warn("[QSign-InternalSigVerifier] X-Internal-Sig 서명 불일치 — ±{}초 범위 검증 실패 → 거부 correlationId={}",
                ttlSeconds, correlationId);
        return false;
    }

    /**
     * HMAC-SHA256("{correlationId}:{epochSeconds}", secret) → Hex 문자열
     */
    private String computeHmac(String correlationId, long epochSeconds) throws Exception {
        String payload = correlationId + ":" + epochSeconds;
        return CryptoProviders.current().hmacSha256Hex(sigSecret.getBytes(StandardCharsets.UTF_8), payload);
    }
}
