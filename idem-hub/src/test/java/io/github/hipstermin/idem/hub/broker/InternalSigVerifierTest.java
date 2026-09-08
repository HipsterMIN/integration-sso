package io.github.hipstermin.idem.hub.broker;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InternalSigVerifier — HMAC-SHA256 내부 서명 검증 단위 테스트
 *
 * 검증 항목:
 *   1. 올바른 서명 검증 성공
 *   2. 잘못된 서명 거부
 *   3. 빈 서명/correlationId 거부
 *   4. 비밀키 미설정 시 즉시 거부
 *   5. 타임스탬프 범위 내 서명 검증 (시계 편차 허용)
 *   6. TTL 초과 서명 거부
 */
@DisplayName("InternalSigVerifier — HMAC-SHA256 서명 검증")
class InternalSigVerifierTest {

    private static final String SECRET     = "test-secret-key-for-unit-test-32chars";
    private static final String HMAC_ALGO  = "HmacSHA256";

    private InternalSigVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new InternalSigVerifier();
        ReflectionTestUtils.setField(verifier, "sigSecret", SECRET);
        ReflectionTestUtils.setField(verifier, "ttlSeconds", 60);
        verifier.validateSigSecret(); // @PostConstruct 수동 호출
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 정상 서명 검증
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify() — 현재 시각 서명 → true")
    void verify_validCurrentTimeSig_returnsTrue() throws Exception {
        String correlationId = "test-corr-id-12345";
        long now = System.currentTimeMillis() / 1000L;
        String sig = computeHmac(correlationId, now);

        assertThat(verifier.verify(sig, correlationId)).isTrue();
    }

    @Test
    @DisplayName("verify() — TTL 범위 내 과거 서명(30초 전) → true")
    void verify_sigFromPast30s_returnsTrue() throws Exception {
        String correlationId = "test-corr-id-past";
        long thirtySecsAgo = System.currentTimeMillis() / 1000L - 30;
        String sig = computeHmac(correlationId, thirtySecsAgo);

        assertThat(verifier.verify(sig, correlationId)).isTrue();
    }

    @Test
    @DisplayName("verify() — TTL 범위 내 미래 서명(30초 후) → true (시계 편차 허용)")
    void verify_sigFromFuture30s_returnsTrue() throws Exception {
        String correlationId = "test-corr-id-future";
        long thirtySecsAhead = System.currentTimeMillis() / 1000L + 30;
        String sig = computeHmac(correlationId, thirtySecsAhead);

        assertThat(verifier.verify(sig, correlationId)).isTrue();
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 잘못된 서명 거부
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify() — 다른 비밀키로 생성된 서명 → false")
    void verify_wrongSecretSig_returnsFalse() throws Exception {
        String correlationId = "test-corr-id-wrong";
        long now = System.currentTimeMillis() / 1000L;
        String sigWithWrongSecret = computeHmacWithSecret(correlationId, now, "wrong-secret");

        assertThat(verifier.verify(sigWithWrongSecret, correlationId)).isFalse();
    }

    @Test
    @DisplayName("verify() — TTL 초과 서명(90초 전) → false")
    void verify_expiredSig_returnsFalse() throws Exception {
        String correlationId = "test-corr-id-expired";
        long ninetySecsAgo = System.currentTimeMillis() / 1000L - 90;
        String expiredSig = computeHmac(correlationId, ninetySecsAgo);

        assertThat(verifier.verify(expiredSig, correlationId)).isFalse();
    }

    @Test
    @DisplayName("verify() — 다른 correlationId 서명 → false")
    void verify_wrongCorrelationIdSig_returnsFalse() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String sig = computeHmac("original-corr-id", now);

        // 다른 correlationId로 검증 → 불일치
        assertThat(verifier.verify(sig, "tampered-corr-id")).isFalse();
    }

    @Test
    @DisplayName("verify() — 변조된 서명 문자 → false")
    void verify_tamperedSig_returnsFalse() throws Exception {
        String correlationId = "test-corr-tampered";
        long now = System.currentTimeMillis() / 1000L;
        String validSig = computeHmac(correlationId, now);

        // 마지막 두 문자 변조
        String tamperedSig = validSig.substring(0, validSig.length() - 2) + "ff";

        assertThat(verifier.verify(tamperedSig, correlationId)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 3. null/빈 입력 거부
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify() — null 서명 → false")
    void verify_nullSig_returnsFalse() {
        assertThat(verifier.verify(null, "corr-id")).isFalse();
    }

    @Test
    @DisplayName("verify() — 빈 서명 → false")
    void verify_emptySig_returnsFalse() {
        assertThat(verifier.verify("", "corr-id")).isFalse();
    }

    @Test
    @DisplayName("verify() — null correlationId → false")
    void verify_nullCorrelationId_returnsFalse() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String sig = computeHmac("corr", now);
        assertThat(verifier.verify(sig, null)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 비밀키 미설정 시 즉시 거부
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("verify() — 비밀키 미설정(빈 문자열) → 즉시 false")
    void verify_emptySecret_returnsFalse() throws Exception {
        InternalSigVerifier noSecretVerifier = new InternalSigVerifier();
        ReflectionTestUtils.setField(noSecretVerifier, "sigSecret", "");
        ReflectionTestUtils.setField(noSecretVerifier, "ttlSeconds", 60);

        long now = System.currentTimeMillis() / 1000L;
        String sig = computeHmac("corr", now);

        assertThat(noSecretVerifier.verify(sig, "corr")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────

    private String computeHmac(String correlationId, long epochSeconds) throws Exception {
        return computeHmacWithSecret(correlationId, epochSeconds, SECRET);
    }

    private String computeHmacWithSecret(String correlationId, long epochSeconds,
                                          String secret) throws Exception {
        String payload = correlationId + ":" + epochSeconds;
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(rawHmac);
    }
}
