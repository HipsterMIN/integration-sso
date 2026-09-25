package io.github.hipstermin.idem.gate.pkce;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PkceService 단위 테스트 — RFC 7636 완전 준수 검증
 *
 * <p>Redis 의존성은 {@link RedisTemplate} Mock으로 대체.
 * {@link ReflectionTestUtils}로 @Value 필드(challengeTtlSeconds, pkceEnabled) 주입.
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>generateCodeVerifier() → 길이 43~128자, RFC 7636 §4.1 허용 문자</li>
 *   <li>generateCodeChallenge() → SHA-256 S256 결정적 출력, 라운드트립</li>
 *   <li>storeChallenge() → Redis set 호출, PKCE 비활성 시 스킵, null 입력 예외</li>
 *   <li>verifyCodeVerifier() → 정상/만료/변조/plain/비활성 케이스</li>
 *   <li>hasPkceParams() → null/blank/유효 값 분기</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PkceServiceTest {

    private static final String KEY_PREFIX = "idem:gate:pkce:challenge:";

    /** RFC 7636 §4.1 허용 문자: A-Z a-z 0-9 - . _ ~ */
    private static final Pattern RFC7636_CHARS = Pattern.compile("[A-Za-z0-9\\-._~]+");

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private PkceService pkceService;

    @BeforeEach
    void setUp() {
        pkceService = new PkceService(redisTemplate);
        ReflectionTestUtils.setField(pkceService, "challengeTtlSeconds", 300L);
        ReflectionTestUtils.setField(pkceService, "pkceEnabled", true);
    }

    // ════════════════════════════════════════════════════════════════════════
    // generateCodeVerifier()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateCodeVerifier()")
    class GenerateCodeVerifier {

        @Test
        @DisplayName("생성된 code_verifier는 43~128자 범위여야 한다 (RFC 7636 §4.1)")
        void lengthWithin43To128() {
            String verifier = pkceService.generateCodeVerifier();
            assertThat(verifier.length())
                    .as("code_verifier 길이는 43~128자이어야 함")
                    .isGreaterThanOrEqualTo(43)
                    .isLessThanOrEqualTo(128);
        }

        @Test
        @DisplayName("생성된 code_verifier는 RFC 7636 §4.1 허용 문자만 포함해야 한다")
        void onlyAllowedCharacters() {
            // 64바이트 Base64URL(withoutPadding)은 86자; '=' padding 없이 A-Z/a-z/0-9/-/_만 사용
            for (int i = 0; i < 20; i++) {
                String verifier = pkceService.generateCodeVerifier();
                assertThat(verifier)
                        .as("code_verifier에 허용되지 않는 문자 포함 (시도 %d)", i)
                        .matches(RFC7636_CHARS);
            }
        }

        @Test
        @DisplayName("호출마다 다른 값이 생성되어야 한다 (Salt 무작위성)")
        void uniqueness() {
            Set<String> verifiers = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                verifiers.add(pkceService.generateCodeVerifier());
            }
            assertThat(verifiers).as("50회 생성 중 중복 발생").hasSize(50);
        }

        @Test
        @DisplayName("64바이트 원천 → Base64URL(withoutPadding) = 86자")
        void exactLength86Chars() {
            // 64 bytes × (4/3) = 85.33 → withoutPadding → 86자 (Base64URL)
            String verifier = pkceService.generateCodeVerifier();
            assertThat(verifier.length()).as("64바이트 Base64URL은 86자여야 함").isEqualTo(86);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // generateCodeChallenge()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateCodeChallenge()")
    class GenerateCodeChallenge {

        @Test
        @DisplayName("같은 verifier → 항상 동일한 challenge (결정적 출력)")
        void deterministicOutput() {
            String verifier = pkceService.generateCodeVerifier();
            String c1 = pkceService.generateCodeChallenge(verifier);
            String c2 = pkceService.generateCodeChallenge(verifier);
            assertThat(c1).isEqualTo(c2);
        }

        @Test
        @DisplayName("RFC 7636 §4.2 S256 — BASE64URL(SHA-256(ASCII(verifier)))")
        void sha256S256Compliance() throws Exception {
            String verifier = pkceService.generateCodeVerifier();

            // 직접 계산
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);

            String actual = pkceService.generateCodeChallenge(verifier);
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("verifier가 null이면 PkceException 발생")
        void nullVerifierThrowsException() {
            assertThatThrownBy(() -> pkceService.generateCodeChallenge(null))
                    .isInstanceOf(PkceService.PkceException.class);
        }

        @Test
        @DisplayName("verifier 길이 42자(범위 미만)이면 PkceException 발생")
        void tooShortVerifierThrowsException() {
            String shortVerifier = "A".repeat(42);
            assertThatThrownBy(() -> pkceService.generateCodeChallenge(shortVerifier))
                    .isInstanceOf(PkceService.PkceException.class)
                    .hasMessageContaining("43~128");
        }

        @Test
        @DisplayName("verifier 길이 129자(범위 초과)이면 PkceException 발생")
        void tooLongVerifierThrowsException() {
            String longVerifier = "A".repeat(129);
            assertThatThrownBy(() -> pkceService.generateCodeChallenge(longVerifier))
                    .isInstanceOf(PkceService.PkceException.class)
                    .hasMessageContaining("43~128");
        }

        @Test
        @DisplayName("다른 verifier → 다른 challenge")
        void differentVerifierDifferentChallenge() {
            String v1 = pkceService.generateCodeVerifier();
            String v2 = pkceService.generateCodeVerifier();
            // v1 ≠ v2 이어야 하지만, 극히 낮은 확률로 동일 가능 → 재시도 없이 간단 검증
            assertThat(pkceService.generateCodeChallenge(v1))
                    .isNotEqualTo(pkceService.generateCodeChallenge(v2));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // storeChallenge()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("storeChallenge()")
    class StoreChallenge {

        @BeforeEach
        void mockValueOps() {
            given(redisTemplate.opsForValue()).willReturn(valueOps);
        }

        @Test
        @DisplayName("정상 호출 시 Redis set(key, value, TTL) 이 호출되어야 한다")
        void normalStoreCallsRedisSet() {
            String state = "test-state-001";
            String challenge = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

            pkceService.storeChallenge(state, challenge, "S256");

            String expectedKey = KEY_PREFIX + state;
            String expectedValue = "S256:" + challenge;
            then(valueOps).should().set(
                    eq(expectedKey),
                    eq(expectedValue),
                    eq(Duration.ofSeconds(300L)));
        }

        @Test
        @DisplayName("state가 null이면 PkceException 발생")
        void nullStateThrowsException() {
            assertThatThrownBy(() -> pkceService.storeChallenge(null, "challenge", "S256"))
                    .isInstanceOf(PkceService.PkceException.class)
                    .hasMessageContaining("필수");
        }

        @Test
        @DisplayName("codeChallenge가 null이면 PkceException 발생")
        void nullChallengeThrowsException() {
            assertThatThrownBy(() -> pkceService.storeChallenge("state", null, "S256"))
                    .isInstanceOf(PkceService.PkceException.class)
                    .hasMessageContaining("필수");
        }

        @Test
        @DisplayName("pkceEnabled=false이면 Redis 호출 없이 스킵")
        void disabledPkceSkipsRedis() {
            ReflectionTestUtils.setField(pkceService, "pkceEnabled", false);

            pkceService.storeChallenge("state", "challenge", "S256");

            then(redisTemplate).shouldHaveNoInteractions();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // verifyCodeVerifier()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("verifyCodeVerifier()")
    class VerifyCodeVerifier {

        @BeforeEach
        void mockValueOps() {
            given(redisTemplate.opsForValue()).willReturn(valueOps);
        }

        @Test
        @DisplayName("정상 S256 라운드트립 — verifier → challenge 검증 성공")
        void validS256RoundTrip() {
            String state = "state-roundtrip";
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateCodeChallenge(verifier);
            String storedValue = "S256:" + challenge;

            given(valueOps.get(KEY_PREFIX + state)).willReturn(storedValue);
            given(redisTemplate.delete(KEY_PREFIX + state)).willReturn(true);

            boolean result = pkceService.verifyCodeVerifier(state, verifier);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Redis에 값이 없으면(만료) PkceException 발생")
        void expiredChallengeThrowsException() {
            String state = "state-expired";
            given(valueOps.get(KEY_PREFIX + state)).willReturn(null);

            assertThatThrownBy(() -> pkceService.verifyCodeVerifier(state, "any-verifier"))
                    .isInstanceOf(PkceService.PkceException.class)
                    .hasMessageContaining("만료");
        }

        @Test
        @DisplayName("변조된 code_verifier는 PkceException 발생 — 코드 탈취 의심")
        void tamperedVerifierThrowsException() {
            String state = "state-tampered";
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateCodeChallenge(verifier);
            String storedValue = "S256:" + challenge;

            given(valueOps.get(KEY_PREFIX + state)).willReturn(storedValue);
            given(redisTemplate.delete(KEY_PREFIX + state)).willReturn(true);

            // 다른 verifier로 검증 시도
            String differentVerifier = pkceService.generateCodeVerifier();
            assertThatThrownBy(() -> pkceService.verifyCodeVerifier(state, differentVerifier))
                    .isInstanceOf(PkceService.PkceException.class)
                    .hasMessageContaining("검증 실패");
        }

        @Test
        @DisplayName("1회성 검증 — 성공 후 Redis에서 키가 삭제되어야 한다")
        void deletedAfterVerification() {
            String state = "state-oneshot";
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateCodeChallenge(verifier);
            String storedValue = "S256:" + challenge;

            given(valueOps.get(KEY_PREFIX + state)).willReturn(storedValue);
            given(redisTemplate.delete(KEY_PREFIX + state)).willReturn(true);

            pkceService.verifyCodeVerifier(state, verifier);

            then(redisTemplate).should().delete(KEY_PREFIX + state);
        }

        @Test
        @DisplayName("plain method — code_verifier == code_challenge 직접 비교")
        void plainMethodDirectComparison() {
            String state = "state-plain";
            String plainSecret = "A".repeat(43); // 최소 길이 plain verifier
            String storedValue = "plain:" + plainSecret; // plain 방식: verifier == challenge

            given(valueOps.get(KEY_PREFIX + state)).willReturn(storedValue);
            given(redisTemplate.delete(KEY_PREFIX + state)).willReturn(true);

            boolean result = pkceService.verifyCodeVerifier(state, plainSecret);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("pkceEnabled=false이면 항상 true 반환 (Redis 호출 없음)")
        void disabledPkceAlwaysTrue() {
            ReflectionTestUtils.setField(pkceService, "pkceEnabled", false);

            boolean result = pkceService.verifyCodeVerifier("any-state", "any-verifier");
            assertThat(result).isTrue();
            then(redisTemplate).shouldHaveNoInteractions();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // hasPkceParams()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("hasPkceParams()")
    class HasPkceParams {

        @Test
        @DisplayName("null 입력 → false")
        void nullReturnsFalse() {
            assertThat(pkceService.hasPkceParams(null)).isFalse();
        }

        @Test
        @DisplayName("빈 문자열 → false")
        void blankReturnsFalse() {
            assertThat(pkceService.hasPkceParams("")).isFalse();
            assertThat(pkceService.hasPkceParams("   ")).isFalse();
        }

        @Test
        @DisplayName("유효한 challenge 값 → true")
        void validChallengeReturnsTrue() {
            assertThat(pkceService.hasPkceParams("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                    .isTrue();
        }
    }
}
