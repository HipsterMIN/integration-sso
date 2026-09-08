package io.github.hipstermin.idem.hub.crypto.kms;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * LocalKmsClient 단위 테스트
 *
 * <p>KMS Off 모드(IDO_KMS_ENABLED=false) 동작 검증:
 * <ul>
 *   <li>encrypt: Base64 인코딩만 수행</li>
 *   <li>decrypt: Base64 디코딩만 수행 (encrypt와 라운드트립 검증)</li>
 *   <li>isHealthy: 항상 true</li>
 *   <li>providerName: "local"</li>
 * </ul>
 */
@DisplayName("LocalKmsClient — KMS Off 모드 단위 테스트")
class LocalKmsClientTest {

    private LocalKmsClient client;

    @BeforeEach
    void setUp() {
        // 비-prod 프로파일 환경에서 LocalKmsClient 생성 (Sprint α-1 F5.1)
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");
        client = new LocalKmsClient(env);
        // allowInProd 기본값 false 보장 (단위 테스트는 부팅 가드 별도 검증)
    }

    // ══════════════════════════════════════════════════════════════════════
    // encrypt / decrypt 라운드트립
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("encrypt → decrypt 라운드트립")
    class RoundTrip {

        @Test
        @DisplayName("AES-256 키 32바이트 라운드트립 성공")
        void aes256RoundTrip() {
            // given
            byte[] originalKey = new byte[32];
            new SecureRandom().nextBytes(originalKey);

            // when
            String encrypted = client.encrypt(originalKey);
            byte[] decrypted = client.decrypt(encrypted);

            // then
            assertThat(decrypted).isEqualTo(originalKey);
        }

        @Test
        @DisplayName("HMAC-SHA256 키 32바이트 라운드트립 성공")
        void hmac256RoundTrip() {
            // given
            byte[] originalKey = new byte[32];
            new SecureRandom().nextBytes(originalKey);

            // when
            String encrypted = client.encrypt(originalKey);
            byte[] decrypted = client.decrypt(encrypted);

            // then
            assertThat(decrypted)
                .describedAs("encrypt() → decrypt() 결과가 원본과 일치해야 함")
                .isEqualTo(originalKey);
        }

        @Test
        @DisplayName("encrypt 결과는 유효한 Base64 문자열이어야 함")
        void encryptReturnsBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);

            // when
            String encrypted = client.encrypt(key);

            // then
            assertThatCode(() -> Base64.getDecoder().decode(encrypted))
                .describedAs("encrypt() 결과는 Base64 디코딩 가능해야 함")
                .doesNotThrowAnyException();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // decrypt — 입력 형식 허용성
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("decrypt — Base64 입력 유연성")
    class DecryptFormats {

        @Test
        @DisplayName("표준 Base64 (+ /) 디코딩 성공")
        void standardBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String standard = Base64.getEncoder().encodeToString(key);

            // when / then
            assertThat(client.decrypt(standard)).isEqualTo(key);
        }

        @Test
        @DisplayName("URL-safe Base64 (- _) 디코딩 성공")
        void urlSafeBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(key);

            // URL-safe 문자가 있는지 확인 (랜덤에 따라 없을 수도 있음)
            // when / then
            assertThat(client.decrypt(urlSafe)).isEqualTo(key);
        }

        @Test
        @DisplayName("패딩 없는 Base64 디코딩 성공")
        void noPaddingBase64() {
            // given
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            String noPadding = Base64.getEncoder().withoutPadding().encodeToString(key);

            // when / then
            assertThat(client.decrypt(noPadding)).isEqualTo(key);
        }

        @Test
        @DisplayName("잘못된 Base64 입력 시 KmsDecryptException 발생")
        void invalidBase64ThrowsException() {
            // given
            String invalid = "!@#$%^&*()_NOT_BASE64";

            // when / then
            assertThatThrownBy(() -> client.decrypt(invalid))
                .isInstanceOf(KmsClient.KmsDecryptException.class)
                .hasMessageContaining("Base64 디코딩 실패");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 메타데이터
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("providerName = 'local'")
    void providerName() {
        assertThat(client.providerName()).isEqualTo("local");
    }

    @Test
    @DisplayName("isHealthy = true (항상)")
    void isHealthy() {
        assertThat(client.isHealthy()).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // F5.1 — 부팅 가드 (failFastIfProdLike) 회귀 테스트
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("F5.1 부팅 가드 — 운영 프로파일에서 활성화 거부")
    class ProdGuard {

        private LocalKmsClient buildClient(String[] activeProfiles, boolean allowInProd) throws Exception {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles(activeProfiles);
            LocalKmsClient c = new LocalKmsClient(env);
            // @Value 주입을 시뮬레이션 (단위 테스트)
            Field f = LocalKmsClient.class.getDeclaredField("allowInProd");
            f.setAccessible(true);
            f.setBoolean(c, allowInProd);
            return c;
        }

        @Test
        @DisplayName("active=prod → IllegalStateException으로 startup 차단")
        void prodProfile_rejected() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"prod"}, false);

            assertThatThrownBy(c::failFastIfProdLike)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("운영 의심 프로파일")
                .hasMessageContaining("prod")
                .hasMessageContaining("F5.1 Guard");
        }

        @Test
        @DisplayName("active=stage → IllegalStateException으로 startup 차단")
        void stageProfile_rejected() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"stage"}, false);

            assertThatThrownBy(c::failFastIfProdLike)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stage")
                .hasMessageContaining("F5.1 Guard");
        }

        @Test
        @DisplayName("active=production (대소문자/별칭) → 차단")
        void productionAlias_rejected() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"PRODUCTION"}, false);

            assertThatThrownBy(c::failFastIfProdLike)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("F5.1 Guard");
        }

        @Test
        @DisplayName("active=prod + allow-in-prod=true → 경고만 출력, 통과 (escape hatch)")
        void prodProfile_allowedExplicitly() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"prod"}, true);

            assertThatCode(c::failFastIfProdLike)
                .describedAs("escape hatch 활성 시 startup 진행")
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("active=local → 통과")
        void localProfile_passes() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"local"}, false);

            assertThatCode(c::failFastIfProdLike).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("active=dev → 통과")
        void devProfile_passes() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"dev"}, false);

            assertThatCode(c::failFastIfProdLike).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("active=test → 통과 (CI 단위테스트)")
        void testProfile_passes() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"test"}, false);

            assertThatCode(c::failFastIfProdLike).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("active 비어있음 → 통과 (default profile)")
        void noActiveProfile_passes() throws Exception {
            LocalKmsClient c = buildClient(new String[]{}, false);

            assertThatCode(c::failFastIfProdLike).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("active=prod,custom 다중 프로파일 → 차단 (prod 포함)")
        void multiProfileWithProd_rejected() throws Exception {
            LocalKmsClient c = buildClient(new String[]{"custom", "prod", "extra"}, false);

            assertThatThrownBy(c::failFastIfProdLike)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("F5.1 Guard");
        }
    }
}
