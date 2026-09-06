package kr.go.smes.common.spi.identity;

import kr.go.smes.common.domain.AuthResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityProviderRegistryTest {

    private static IdentityVerificationProvider provider(String code) {
        return new IdentityVerificationProvider() {
            @Override public String code() { return code; }
            @Override public AuthResult.AuthLevel level() { return AuthResult.AuthLevel.L1; }
            @Override public VerificationStart initiate(VerificationRequest request) {
                return new VerificationStart(code, "tx", null, Map.of());
            }
            @Override public VerifiedIdentity complete(VerificationCallback callback) {
                return new VerifiedIdentity(code, "tx", "subject", "name", null, null, null, null,
                        AuthResult.AuthLevel.L1, Instant.EPOCH, null);
            }
        };
    }

    @Test
    @DisplayName("코드는 대소문자·공백을 무시하고 조회된다")
    void findNormalizesCode() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(provider("mock")));
        assertThat(registry.find("MOCK")).isPresent();
        assertThat(registry.find(" mock ")).isPresent();
        assertThat(registry.codes()).containsExactly("MOCK");
    }

    @Test
    @DisplayName("중복 코드는 부팅 실패로 드러난다")
    void duplicateCodeFails() {
        assertThatThrownBy(() -> new IdentityProviderRegistry(List.of(provider("A"), provider("a"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("미등록 코드는 IdentityVerificationException(PROVIDER_UNKNOWN)")
    void requireUnknown() {
        IdentityProviderRegistry registry = IdentityProviderRegistry.empty();
        assertThat(registry.isEmpty()).isTrue();
        assertThatThrownBy(() -> registry.require("NOPE"))
                .isInstanceOf(IdentityVerificationException.class)
                .satisfies(e -> assertThat(((IdentityVerificationException) e).getReasonCode()).isEqualTo("PROVIDER_UNKNOWN"));
    }

    @Test
    @DisplayName("레코드는 null 맵을 빈 맵으로 정규화한다")
    void recordsNormalizeNullMaps() {
        assertThat(new VerificationRequest("c", "u", null).params()).isEmpty();
        assertThat(new VerificationCallback("P", "t", null, null).params()).isEmpty();
        assertThat(new AuthWidgetDescriptor("/x.js", "X", null).initParams()).isEmpty();
    }
}
