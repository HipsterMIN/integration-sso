package kr.go.smes.ido.crypto;

import kr.go.smes.ido.crypto.kms.KmsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link KeyVersionRegistry#validateFallbackKeys()} 부팅 검증 회귀 가드
 * (Sprint γ-3 / F3.3 후속).
 *
 * <p>핵심 정책:
 * <ul>
 *   <li>{@code ido.ticket.aes-key} / {@code ido.ticket.hmac-key} 가 비어있으면 부팅 차단</li>
 *   <li>특히 legacy default {@code "AAAA...="} (32바이트 0x00) 명시적 거부</li>
 *   <li>Base64 디코드 후 정확히 32바이트(AES-256 / HMAC-SHA256) 이어야 함</li>
 *   <li>{@code allowEmptyFallbackKeys=true} escape hatch 가 있을 때만 빈 값 허용</li>
 * </ul>
 *
 * <p>실제 KeyVersionRegistry 의 다른 동작(Redis/DB/KMS 폴백) 은 테스트하지 않는다
 * — 본 클래스는 폴백 키 검증 단독 회귀 가드.
 */
@DisplayName("KeyVersionRegistry — 폴백 키 부팅 검증 (@PostConstruct validateFallbackKeys)")
class KeyVersionRegistryStartupGuardTest {

    /** AES-256 / HMAC-SHA256 양쪽 모두 32바이트 키 필요 */
    private static final String VALID_32B_KEY = Base64.getEncoder()
            .encodeToString("validKey32Bytes_AAAAAAAAAAAAAAAA".getBytes()); // 길이 32

    /** 다른 32바이트 키 — AES/HMAC 동시 검증 시 구분용 */
    private static final String VALID_32B_KEY_2 = Base64.getEncoder()
            .encodeToString("validKey32Bytes_BBBBBBBBBBBBBBBB".getBytes()); // 길이 32

    /** γ-2 이전까지 application.yml 에 박혀있던 legacy default */
    private static final String LEGACY_DEFAULT = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @SuppressWarnings("unchecked")
    private KeyVersionRegistry freshInstance(String aesKey, String hmacKey, boolean allowEmpty) {
        KeyVersionRegistry registry = new KeyVersionRegistry(
                mock(RedisTemplate.class),
                mock(JdbcTemplate.class),
                mock(KmsClient.class));
        ReflectionTestUtils.setField(registry, "fallbackAesKeyBase64",  aesKey);
        ReflectionTestUtils.setField(registry, "fallbackHmacKeyBase64", hmacKey);
        ReflectionTestUtils.setField(registry, "configuredVersion",     "v1");
        ReflectionTestUtils.setField(registry, "allowEmptyFallbackKeys", allowEmpty);
        return registry;
    }

    // ── Reject ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("부팅 차단 — 폴백 키가 안전하지 않을 때")
    class Reject {

        @Test
        @DisplayName("AES 폴백 키가 null + allow-empty=false → IllegalStateException")
        void aesKey_null_blockBoot() {
            KeyVersionRegistry registry = freshInstance(null, VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ido.ticket.aes-key")
                    .hasMessageContaining("IDO_HANDOFF_AES_KEY");
        }

        @Test
        @DisplayName("HMAC 폴백 키가 blank + allow-empty=false → IllegalStateException")
        void hmacKey_blank_blockBoot() {
            KeyVersionRegistry registry = freshInstance(VALID_32B_KEY, "   ", false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ido.ticket.hmac-key")
                    .hasMessageContaining("IDO_HANDOFF_HMAC_KEY");
        }

        @Test
        @DisplayName("AES 폴백 키가 legacy default 'AAAA...=' (32바이트 0x00) → IllegalStateException")
        void aesKey_legacyZeroKeyDefault_blockBoot() {
            KeyVersionRegistry registry = freshInstance(LEGACY_DEFAULT, VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder")
                    .hasMessageContaining("ido.ticket.aes-key");
        }

        @Test
        @DisplayName("HMAC 폴백 키가 legacy default 'AAAA...=' → IllegalStateException")
        void hmacKey_legacyZeroKeyDefault_blockBoot() {
            KeyVersionRegistry registry = freshInstance(VALID_32B_KEY, LEGACY_DEFAULT, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder")
                    .hasMessageContaining("ido.ticket.hmac-key");
        }

        @Test
        @DisplayName("AES 폴백 키가 'change-me' placeholder → IllegalStateException")
        void aesKey_changeMePlaceholder_blockBoot() {
            KeyVersionRegistry registry = freshInstance("change-me", VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("AES 폴백 키가 유효한 Base64 가 아니면 → IllegalStateException")
        void aesKey_invalidBase64_blockBoot() {
            KeyVersionRegistry registry = freshInstance("not!valid@base64#!!", VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Base64");
        }

        @Test
        @DisplayName("AES 폴백 키가 16바이트(AES-128) → IllegalStateException")
        void aesKey_short16Bytes_blockBoot() {
            String aes128 = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
            KeyVersionRegistry registry = freshInstance(aes128, VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32바이트");
        }

        @Test
        @DisplayName("HMAC 폴백 키가 48바이트(오버사이즈) → IllegalStateException")
        void hmacKey_long48Bytes_blockBoot() {
            String over = Base64.getEncoder()
                    .encodeToString("0123456789abcdef0123456789abcdef0123456789abcdef".getBytes()); // 48
            KeyVersionRegistry registry = freshInstance(VALID_32B_KEY, over, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32바이트")
                    .hasMessageContaining("ido.ticket.hmac-key");
        }
    }

    // ── Accept ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("부팅 통과 — 폴백 키가 모두 안전할 때")
    class Accept {

        @Test
        @DisplayName("AES + HMAC 모두 유효한 32바이트 키 → 통과")
        void bothValidKeys_pass() {
            KeyVersionRegistry registry = freshInstance(VALID_32B_KEY, VALID_32B_KEY_2, false);
            assertThatCode(registry::validateFallbackKeys).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Base64URL(-/_) 형식 32바이트 키도 통과")
        void base64UrlKeys_pass() {
            byte[] raw = new byte[32];
            for (int i = 0; i < 32; i++) raw[i] = (byte)(0xF0 + i);
            String b64url = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            KeyVersionRegistry registry = freshInstance(b64url, b64url, false);
            assertThatCode(registry::validateFallbackKeys).doesNotThrowAnyException();
        }
    }

    // ── Escape Hatch ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("Escape Hatch — allow-empty-fallback-keys=true")
    class EscapeHatch {

        @Test
        @DisplayName("allow-empty=true + 두 키 모두 빈 값 → 통과 (로컬/테스트 전용)")
        void allowEmpty_bothBlank_pass() {
            KeyVersionRegistry registry = freshInstance("", "", true);
            assertThatCode(registry::validateFallbackKeys).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allow-empty=true 라도 placeholder 키는 여전히 차단 (escape hatch 무관)")
        void allowEmpty_doesNotBypassPlaceholder() {
            KeyVersionRegistry registry = freshInstance(LEGACY_DEFAULT, VALID_32B_KEY_2, true);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("allow-empty=true 라도 잘못된 길이의 키는 여전히 차단")
        void allowEmpty_doesNotBypassWrongLength() {
            String aes128 = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
            KeyVersionRegistry registry = freshInstance(aes128, VALID_32B_KEY_2, true);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32바이트");
        }
    }

    // ── 회귀 가드 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("회귀 가드 — yml default 재실수 방지")
    class RegressionGuard {

        @Test
        @DisplayName("FORBIDDEN_PLACEHOLDERS 에 legacy 32바이트 0x00 키 (소문자 변형) 포함 확인")
        void forbiddenPlaceholders_includesLegacyZeroKey_caseInsensitive() {
            // legacy default 의 소문자 변형도 차단되어야 함 (대소문자 무시 비교 검증)
            String lowerVariant = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=";
            KeyVersionRegistry registry = freshInstance(lowerVariant, VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("AES 키만 placeholder 면 AES 슬롯에서 즉시 차단 (HMAC 슬롯 도달 전)")
        void aesPlaceholder_failsBeforeHmacValidation() {
            // HMAC 가 정상이어도 AES 가 placeholder 면 차단되어야 함
            KeyVersionRegistry registry = freshInstance(LEGACY_DEFAULT, VALID_32B_KEY_2, false);
            assertThatThrownBy(registry::validateFallbackKeys)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ido.ticket.aes-key"); // AES 슬롯에서 실패
        }
    }
}
