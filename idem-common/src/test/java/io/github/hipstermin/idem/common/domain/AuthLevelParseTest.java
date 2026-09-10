package io.github.hipstermin.idem.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S3 어휘 통일 — 정규 L1/L2/L3 외에 구 LOW/MEDIUM/HIGH·acr 숫자를 호환 해석한다. */
@DisplayName("AuthResult.AuthLevel.parse / meets")
class AuthLevelParseTest {

    @Test
    void canonical_legacy_andAcr_areParsed() {
        assertThat(AuthResult.AuthLevel.parse("L2")).contains(AuthResult.AuthLevel.L2);
        assertThat(AuthResult.AuthLevel.parse(" l3 ")).contains(AuthResult.AuthLevel.L3);
        assertThat(AuthResult.AuthLevel.parse("LOW")).contains(AuthResult.AuthLevel.L1);
        assertThat(AuthResult.AuthLevel.parse("medium")).contains(AuthResult.AuthLevel.L2);
        assertThat(AuthResult.AuthLevel.parse("HIGH")).contains(AuthResult.AuthLevel.L3);
        assertThat(AuthResult.AuthLevel.parse("1")).contains(AuthResult.AuthLevel.L1);
        assertThat(AuthResult.AuthLevel.parse("3")).contains(AuthResult.AuthLevel.L3);
    }

    @Test
    void unknown_isEmpty_andDefaultApplies() {
        assertThat(AuthResult.AuthLevel.parse("CONV")).isEmpty();
        assertThat(AuthResult.AuthLevel.parse(null)).isEmpty();
        assertThat(AuthResult.AuthLevel.parse("")).isEmpty();
        assertThat(AuthResult.AuthLevel.parseOrDefault("CONV", AuthResult.AuthLevel.L1)).isEqualTo(AuthResult.AuthLevel.L1);
    }

    @Test
    void meets_isOrdinalComparison_nullRequiredMeansNoRequirement() {
        assertThat(AuthResult.AuthLevel.L2.meets(AuthResult.AuthLevel.L2)).isTrue();
        assertThat(AuthResult.AuthLevel.L3.meets(AuthResult.AuthLevel.L1)).isTrue();
        assertThat(AuthResult.AuthLevel.L1.meets(AuthResult.AuthLevel.L2)).isFalse();
        assertThat(AuthResult.AuthLevel.L1.meets(null)).isTrue();
    }
}
