package io.github.hipstermin.idem.common.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SubjectScheme — 정규화·identifierHash 규칙 (S4)")
class SubjectSchemeTest {

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ciAndExternalSub_hashRawValue_forBackwardCompatibility() throws Exception {
        assertThat(SubjectScheme.CI.identifierHash(" ci-value ")).isEqualTo(sha256Hex("ci-value"));
        assertThat(SubjectScheme.EXTERNAL_SUB.identifierHash("kakao-sub-1")).isEqualTo(sha256Hex("kakao-sub-1"));
    }

    @Test
    void emailAndPhone_areNormalizedAndNamespaced() throws Exception {
        assertThat(SubjectScheme.EMAIL.identifierHash("  Alice@Example.ORG ")).isEqualTo(sha256Hex("EMAIL:alice@example.org"));
        assertThat(SubjectScheme.PHONE.identifierHash("+82 10-1234-5678")).isEqualTo(sha256Hex("PHONE:821012345678"));
        assertThat(SubjectScheme.EMAIL.normalizeKey("A@B.C")).isEqualTo("a@b.c");
    }

    @Test
    void derivedSchemes_haveNoHash_andCiIsNotTenantSelectable() {
        assertThatThrownBy(() -> SubjectScheme.PAIRWISE_HMAC.identifierHash("x")).isInstanceOf(IllegalStateException.class);
        assertThat(SubjectScheme.PAIRWISE_HMAC.isDerived()).isTrue();
        assertThat(SubjectScheme.CI.isTenantSelectable()).isFalse();
        assertThat(SubjectScheme.EMAIL.isTenantSelectable()).isTrue();
        assertThat(SubjectScheme.DEFAULT).isEqualTo(SubjectScheme.PAIRWISE_HMAC);
        assertThatThrownBy(() -> SubjectScheme.EMAIL.identifierHash("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_isLenient() {
        assertThat(SubjectScheme.parse(" email ")).contains(SubjectScheme.EMAIL);
        assertThat(SubjectScheme.parse("nope")).isEmpty();
        assertThat(SubjectScheme.parse(null)).isEmpty();
    }
}
