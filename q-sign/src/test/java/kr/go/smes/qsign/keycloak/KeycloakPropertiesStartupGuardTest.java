package kr.go.smes.qsign.keycloak;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KeycloakProperties 부팅 가드 단위 테스트 — Sprint γ-1 / F2.1
 *
 * <p>{@code @PostConstruct validateClientSecret()} 가 다음을 정확히 거부하는지 검증:
 * <ul>
 *   <li>{@code clientSecret} 이 null/blank 이고 escape hatch 도 미설정</li>
 *   <li>{@code clientSecret} 이 알려진 placeholder ({@code change-me} 등)</li>
 *   <li>{@code clientSecret} 이 8자 미만</li>
 * </ul>
 *
 * <p>그리고 통과시키는지 검증:
 * <ul>
 *   <li>{@code allowEmptyClientSecret=true} + 비어있는 secret (테스트/로컬 한정)</li>
 *   <li>충분히 긴 무작위 secret</li>
 * </ul>
 */
@DisplayName("KeycloakProperties — 부팅 가드 (Sprint γ-1 / F2.1)")
class KeycloakPropertiesStartupGuardTest {

    private KeycloakProperties props;

    // ── 검증 실패 케이스 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateClientSecret() — 거부")
    class Reject {

        @Test
        @DisplayName("client-secret null + allow-empty=false → IllegalStateException")
        void nullSecret_throws() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret(null);
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QSIGN_KEYCLOAK_CLIENT_SECRET");
        }

        @Test
        @DisplayName("client-secret 빈 문자열 + allow-empty=false → IllegalStateException")
        void blankSecret_throws() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("   ");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QSIGN_KEYCLOAK_CLIENT_SECRET");
        }

        @Test
        @DisplayName("client-secret = 'change-me' → placeholder 차단")
        void placeholderChangeMe_throws() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("change-me");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("client-secret = 'CHANGE-ME' (대소문자 무관) → placeholder 차단")
        void placeholderCaseInsensitive_throws() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("CHANGE-ME");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("client-secret = 'secret' → placeholder 차단")
        void placeholderSecret_throws() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("secret");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("client-secret 7자 (8자 미만) → 길이 미달 차단")
        void tooShort_throws() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("abcdefg");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("짧습니다");
        }
    }

    // ── 검증 통과 케이스 ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateClientSecret() — 통과")
    class Accept {

        @Test
        @DisplayName("escape hatch (allow-empty=true) + 빈 secret → 통과 (테스트/로컬)")
        void emptyWithEscapeHatch_passes() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", true);

            assertThatCode(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("escape hatch + null secret → 통과")
        void nullWithEscapeHatch_passes() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret(null);
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", true);

            assertThatCode(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("운영 수준 secret (32자 무작위) → 통과")
        void productionGradeSecret_passes() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("a1b2c3d4e5f6g7h8i9j0klmnopqrstuv");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatCode(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("정확히 8자 boundary → 통과 (placeholder 아닌 경우)")
        void exactlyEightChars_passes() {
            KeycloakProperties p = new KeycloakProperties();
            p.setClientSecret("xY9!aB3z");
            ReflectionTestUtils.setField(p, "allowEmptyClientSecret", false);

            assertThatCode(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .doesNotThrowAnyException();
        }
    }

    // ── 기본값 확인 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("KeycloakProperties 기본 clientSecret = 빈 문자열 (P2 수정 유지)")
    void defaultClientSecretIsEmpty() {
        KeycloakProperties p = new KeycloakProperties();
        assertThat(p.getClientSecret()).isEmpty();
    }
}
