package io.github.hipstermin.idem.gate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InternalSigVerifier 단위 테스트 — HMAC-SHA256 X-Internal-Sig 검증 규칙 회귀 방어
 *
 * <p>설계서 §9.4 / §11.7 — IdO → Q-Sign 내부 서명 검증
 * <pre>
 *   payload = "{correlationId}:{epochSeconds}"
 *   sig     = HMAC-SHA256(payload, sharedSecret) → Hex 문자열
 *   window  = ±ttlSeconds (default 60)
 * </pre>
 *
 * <p>{@link ReflectionTestUtils}로 @Value 필드(sigSecret, ttlSeconds) 주입.
 * 의도적으로 Spring context 를 띄우지 않음 — verify() 의 순수 로직 단위 테스트.
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>정상: 올바른 secret + 현재 epoch → true</li>
 *   <li>시계 편차 ±10초 이내 → true (debug 로그 없음)</li>
 *   <li>시계 편차 ±10초 초과 ±ttl 이내 → true (debug 로그)</li>
 *   <li>시계 편차 ±ttl 초과 → false</li>
 *   <li>null/blank receivedSig → false (fail-safe)</li>
 *   <li>null/blank correlationId → false (fail-safe)</li>
 *   <li>sigSecret 미설정 → false (fail-safe)</li>
 *   <li>위조 sig (다른 secret) → false</li>
 *   <li>HMAC payload 형식 "{correlationId}:{epochSeconds}" 회귀 방어</li>
 *   <li>대소문자 무관 일치 (equalsIgnoreCase) — Hex 표기 차이 허용</li>
 * </ul>
 */
class InternalSigVerifierTest {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 32자 이상 — INSECURE_DEFAULT/길이부족 경고 트리거 없는 정상 키 */
    private static final String VALID_SECRET   = "production-grade-secret-key-with-sufficient-entropy-0123456789";
    private static final String FOREIGN_SECRET = "different-attacker-controlled-secret-key-which-must-not-match-00";
    private static final int    DEFAULT_TTL    = 60;

    private InternalSigVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new InternalSigVerifier();
        ReflectionTestUtils.setField(verifier, "sigSecret", VALID_SECRET);
        ReflectionTestUtils.setField(verifier, "ttlSeconds", DEFAULT_TTL);
    }

    // ------------------------------------------------------------------
    // 정상 흐름
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("정상 검증 흐름")
    class HappyPath {

        @Test
        @DisplayName("올바른 secret + 현재 epochSeconds 로 생성된 sig → true")
        void validSigAtCurrentEpoch_returnsTrue() {
            String correlationId = "corr-001";
            long now = System.currentTimeMillis() / 1000L;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + now);

            boolean result = verifier.verify(sig, correlationId);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("HMAC payload 형식은 '{correlationId}:{epochSeconds}' 이어야 한다 (회귀 방어)")
        void payloadFormatIsCorrelationIdColonEpoch() {
            String correlationId = "corr-format-check";
            long now = System.currentTimeMillis() / 1000L;

            // 정확한 형식만 통과해야 한다
            String correctSig = hmacHex(VALID_SECRET, correlationId + ":" + now);
            assertThat(verifier.verify(correctSig, correlationId)).isTrue();

            // 잘못된 구분자(공백) → 거부
            String wrongDelimiterSig = hmacHex(VALID_SECRET, correlationId + " " + now);
            assertThat(verifier.verify(wrongDelimiterSig, correlationId)).isFalse();

            // 순서 바뀐 형식 → 거부
            String wrongOrderSig = hmacHex(VALID_SECRET, now + ":" + correlationId);
            assertThat(verifier.verify(wrongOrderSig, correlationId)).isFalse();
        }

        @Test
        @DisplayName("Hex 대문자 표기도 허용한다 (equalsIgnoreCase)")
        void uppercaseHexIsAccepted() {
            String correlationId = "corr-hex-case";
            long now = System.currentTimeMillis() / 1000L;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + now).toUpperCase();

            boolean result = verifier.verify(sig, correlationId);

            assertThat(result).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 시계 편차 (±ttlSeconds window)
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("시계 편차 윈도우 (±ttlSeconds)")
    class ClockSkewWindow {

        @Test
        @DisplayName("시계 편차 +10초 이내 → true")
        void skewWithinTenSecondsForward_returnsTrue() {
            String correlationId = "corr-skew-10s-fwd";
            long futureEpoch = (System.currentTimeMillis() / 1000L) + 10;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + futureEpoch);

            assertThat(verifier.verify(sig, correlationId)).isTrue();
        }

        @Test
        @DisplayName("시계 편차 -10초 이내 → true")
        void skewWithinTenSecondsBackward_returnsTrue() {
            String correlationId = "corr-skew-10s-bwd";
            long pastEpoch = (System.currentTimeMillis() / 1000L) - 10;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + pastEpoch);

            assertThat(verifier.verify(sig, correlationId)).isTrue();
        }

        @Test
        @DisplayName("시계 편차 +30초 (10초 초과 ttl 이내) → true")
        void skewBeyondTenSecondsButWithinTtl_returnsTrue() {
            String correlationId = "corr-skew-30s";
            long futureEpoch = (System.currentTimeMillis() / 1000L) + 30;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + futureEpoch);

            assertThat(verifier.verify(sig, correlationId)).isTrue();
        }

        @Test
        @DisplayName("시계 편차 +59초 (ttl 경계 안쪽) → true")
        void skewAtTtlBoundaryInside_returnsTrue() {
            String correlationId = "corr-skew-59s";
            long futureEpoch = (System.currentTimeMillis() / 1000L) + 59;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + futureEpoch);

            assertThat(verifier.verify(sig, correlationId)).isTrue();
        }

        @Test
        @DisplayName("시계 편차 +120초 (ttl 초과) → false")
        void skewBeyondTtl_returnsFalse() {
            String correlationId = "corr-skew-120s";
            long farFutureEpoch = (System.currentTimeMillis() / 1000L) + 120;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + farFutureEpoch);

            assertThat(verifier.verify(sig, correlationId)).isFalse();
        }

        @Test
        @DisplayName("시계 편차 -300초 (ttl 한참 초과) → false")
        void skewLongBeforeTtl_returnsFalse() {
            String correlationId = "corr-skew-neg-300s";
            long farPastEpoch = (System.currentTimeMillis() / 1000L) - 300;
            String sig = hmacHex(VALID_SECRET, correlationId + ":" + farPastEpoch);

            assertThat(verifier.verify(sig, correlationId)).isFalse();
        }

        @Test
        @DisplayName("ttlSeconds 가 좁혀지면 그에 맞춰 거부 범위가 좁아진다")
        void customTtlNarrowsAcceptanceWindow() {
            ReflectionTestUtils.setField(verifier, "ttlSeconds", 5);
            String correlationId = "corr-custom-ttl";

            // ttl=5초 → +3초는 통과, +10초는 거부
            long now = System.currentTimeMillis() / 1000L;
            String inSig  = hmacHex(VALID_SECRET, correlationId + ":" + (now + 3));
            String outSig = hmacHex(VALID_SECRET, correlationId + ":" + (now + 10));

            assertThat(verifier.verify(inSig, correlationId)).isTrue();
            assertThat(verifier.verify(outSig, correlationId)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Fail-safe (입력 검증 / 비밀키 미설정)
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Fail-safe 거부 분기")
    class FailSafe {

        @Test
        @DisplayName("receivedSig 가 null 이면 false (HMAC 계산 시도 없음)")
        void nullReceivedSig_returnsFalse() {
            assertThat(verifier.verify(null, "corr-null-sig")).isFalse();
        }

        @Test
        @DisplayName("receivedSig 가 공백이면 false")
        void blankReceivedSig_returnsFalse() {
            assertThat(verifier.verify("   ", "corr-blank-sig")).isFalse();
        }

        @Test
        @DisplayName("receivedSig 가 빈 문자열이면 false")
        void emptyReceivedSig_returnsFalse() {
            assertThat(verifier.verify("", "corr-empty-sig")).isFalse();
        }

        @Test
        @DisplayName("correlationId 가 null 이면 false")
        void nullCorrelationId_returnsFalse() {
            long now = System.currentTimeMillis() / 1000L;
            String sig = hmacHex(VALID_SECRET, "anything:" + now);

            assertThat(verifier.verify(sig, null)).isFalse();
        }

        @Test
        @DisplayName("correlationId 가 공백이면 false")
        void blankCorrelationId_returnsFalse() {
            long now = System.currentTimeMillis() / 1000L;
            String sig = hmacHex(VALID_SECRET, " :" + now);

            assertThat(verifier.verify(sig, "   ")).isFalse();
        }

        @Test
        @DisplayName("sigSecret 미설정(null) → 어떤 sig 도 거부 (fail-safe)")
        void nullSigSecret_returnsFalse() {
            ReflectionTestUtils.setField(verifier, "sigSecret", null);

            String correlationId = "corr-no-secret";
            long now = System.currentTimeMillis() / 1000L;
            // 클라이언트가 어떤 값을 보내든 거부되어야 함
            String anySig = hmacHex(VALID_SECRET, correlationId + ":" + now);

            assertThat(verifier.verify(anySig, correlationId)).isFalse();
        }

        @Test
        @DisplayName("sigSecret 미설정(blank) → 어떤 sig 도 거부")
        void blankSigSecret_returnsFalse() {
            ReflectionTestUtils.setField(verifier, "sigSecret", "   ");

            String correlationId = "corr-blank-secret";
            long now = System.currentTimeMillis() / 1000L;
            String anySig = hmacHex(VALID_SECRET, correlationId + ":" + now);

            assertThat(verifier.verify(anySig, correlationId)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 위조 / 변조 거부
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("위조 / 변조 거부")
    class Tampering {

        @Test
        @DisplayName("다른 secret 으로 생성한 sig → false")
        void forgedSigWithDifferentSecret_returnsFalse() {
            String correlationId = "corr-forge-secret";
            long now = System.currentTimeMillis() / 1000L;
            String forgedSig = hmacHex(FOREIGN_SECRET, correlationId + ":" + now);

            assertThat(verifier.verify(forgedSig, correlationId)).isFalse();
        }

        @Test
        @DisplayName("다른 correlationId 로 생성한 sig → false (요청-서명 바인딩)")
        void mismatchedCorrelationId_returnsFalse() {
            long now = System.currentTimeMillis() / 1000L;
            // 공격자가 다른 correlationId 로 sig 를 생성한 뒤 검증 요청은 별도 correlationId 로 보냄
            String attackerSig = hmacHex(VALID_SECRET, "victim-corr-id:" + now);

            assertThat(verifier.verify(attackerSig, "different-corr-id")).isFalse();
        }

        @Test
        @DisplayName("랜덤 hex 문자열 → false")
        void randomHexString_returnsFalse() {
            String randomHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

            assertThat(verifier.verify(randomHex, "corr-random")).isFalse();
        }

        @Test
        @DisplayName("hex 가 아닌 임의 문자열 → false (HMAC 일치 가능성 없음)")
        void nonHexGarbage_returnsFalse() {
            assertThat(verifier.verify("not-a-real-signature", "corr-garbage")).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // @PostConstruct 보안 검증 (validateSigSecret)
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("기동 시 보안 검증 (validateSigSecret)")
    class StartupValidation {

        @Test
        @DisplayName("validateSigSecret() 은 예외를 던지지 않는다 (경고 로그만)")
        void validateSigSecret_doesNotThrow_evenIfSecretInsecure() {
            // 1) 정상 secret
            ReflectionTestUtils.setField(verifier, "sigSecret", VALID_SECRET);
            ReflectionTestUtils.invokeMethod(verifier, "validateSigSecret");

            // 2) 미설정 (null)
            ReflectionTestUtils.setField(verifier, "sigSecret", null);
            ReflectionTestUtils.invokeMethod(verifier, "validateSigSecret");

            // 3) 미설정 (blank)
            ReflectionTestUtils.setField(verifier, "sigSecret", "");
            ReflectionTestUtils.invokeMethod(verifier, "validateSigSecret");

            // 4) INSECURE_DEFAULT
            ReflectionTestUtils.setField(verifier, "sigSecret", "ido-internal-secret");
            ReflectionTestUtils.invokeMethod(verifier, "validateSigSecret");

            // 5) 길이 부족 (<32)
            ReflectionTestUtils.setField(verifier, "sigSecret", "short-key");
            ReflectionTestUtils.invokeMethod(verifier, "validateSigSecret");

            // 모든 분기에서 예외 없이 통과 (경고/오류 로그만 발생)
            // 별도 assert 없음 — invokeMethod 가 예외를 던지면 테스트가 실패
        }
    }

    // ------------------------------------------------------------------
    // 테스트 헬퍼
    // ------------------------------------------------------------------

    /**
     * 운영 코드와 동일한 HMAC-SHA256 hex 계산을 테스트 측에서 재현.
     * (Reference implementation — InternalSigVerifier.computeHmac 와 동일한 출력)
     */
    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패 (테스트 헬퍼)", e);
        }
    }
}
