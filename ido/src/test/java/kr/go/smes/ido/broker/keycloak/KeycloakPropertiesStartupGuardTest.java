package kr.go.smes.ido.broker.keycloak;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ido 측 KeycloakProperties 부팅 가드 단위 테스트 — Sprint γ-1 / F2.1.
 *
 * <p>q-sign 측 {@code KeycloakPropertiesStartupGuardTest} 와 동일 패턴.
 * ido 의 KeycloakProperties 도 {@code @PostConstruct validateClientSecret()} 가
 * placeholder / 빈 값 / 짧은 secret 을 거부하는지 검증한다.
 *
 * <p>{@code ido.broker.mode=qsign} 인 경우에도 KeycloakProperties Bean 자체는
 * 컨테이너가 생성하므로(@ConfigurationProperties + @Component) 부팅 가드는 항상 동작한다.
 * 따라서 테스트/로컬 환경에서는 {@code ido.keycloak.allow-empty-client-secret=true} 가 필수.
 */
@DisplayName("ido KeycloakProperties — 부팅 가드 (Sprint γ-1 / F2.1)")
class KeycloakPropertiesStartupGuardTest {

    private KeycloakProperties newProps(String secret, boolean allowEmpty) {
        KeycloakProperties p = new KeycloakProperties();
        p.setClientSecret(secret);
        ReflectionTestUtils.setField(p, "allowEmptyClientSecret", allowEmpty);
        return p;
    }

    @Nested
    @DisplayName("validateClientSecret() — 거부")
    class Reject {

        @Test
        @DisplayName("secret null + allow-empty=false → IllegalStateException")
        void nullSecret_throws() {
            KeycloakProperties p = newProps(null, false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEYCLOAK_CLIENT_SECRET");
        }

        @Test
        @DisplayName("secret 빈 문자열 + allow-empty=false → IllegalStateException")
        void blankSecret_throws() {
            KeycloakProperties p = newProps("   ", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEYCLOAK_CLIENT_SECRET");
        }

        @Test
        @DisplayName("secret = 'change-me' → placeholder 차단")
        void placeholderChangeMe_throws() {
            KeycloakProperties p = newProps("change-me", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("placeholder");
        }

        @Test
        @DisplayName("secret 7자 (8자 미만) → 길이 미달 차단")
        void tooShort_throws() {
            KeycloakProperties p = newProps("abcdefg", false);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("짧습니다");
        }
    }

    @Nested
    @DisplayName("validateClientSecret() — 통과")
    class Accept {

        @Test
        @DisplayName("escape hatch (allow-empty=true) + 빈 secret → 통과")
        void emptyWithEscapeHatch_passes() {
            KeycloakProperties p = newProps("", true);
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("운영 수준 secret (32자) → 통과")
        void productionGradeSecret_passes() {
            KeycloakProperties p = newProps("a1b2c3d4e5f6g7h8i9j0klmnopqrstuv", false);
            assertThatCode(() -> ReflectionTestUtils.invokeMethod(p, "validateClientSecret"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("기본 clientSecret = 빈 문자열")
    void defaultClientSecretIsEmpty() {
        KeycloakProperties p = new KeycloakProperties();
        assertThat(p.getClientSecret()).isEmpty();
    }
}
