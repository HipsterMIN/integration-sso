package io.github.hipstermin.idem.common.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AttributeCatalog / MaskingRule (S4)")
class AttributeCatalogTest {

    @Test
    void canonicalNamesAndLegacyAliasesResolveToSameDefinition() {
        assertThat(AttributeCatalog.find("qim_user_id")).isPresent();
        assertThat(AttributeCatalog.find("qimUserId")).contains(AttributeCatalog.find("qim_user_id").get());
        assertThat(AttributeCatalog.canonicalName("NameMasked")).contains("name_masked");
        assertThat(AttributeCatalog.find("nope")).isEmpty();
        assertThat(AttributeCatalog.unknown(List.of("email", "x_y", "birthYear"))).containsExactly("x_y");
    }

    @Test
    void subjectAttributesPointToRegistryKeySchemes() {
        AttributeDefinition email = AttributeCatalog.find("email").orElseThrow();
        assertThat(email.source()).isEqualTo(AttributeDefinition.Source.SUBJECT);
        assertThat(email.subjectScheme()).isEqualTo(SubjectScheme.EMAIL);
        assertThat(email.defaultMasking()).isEqualTo(MaskingRule.EMAIL_LOCAL);
        assertThat(AttributeCatalog.find("name_masked").orElseThrow().subjectScheme()).isNull();
        // 모든 정의의 정규 이름은 snake_case
        assertThat(AttributeCatalog.names()).allMatch(n -> n.matches("^[a-z][a-z0-9_]*$"));
    }

    @Test
    void maskingRules() {
        assertThat(MaskingRule.EMAIL_LOCAL.apply("alice@example.org")).isEqualTo("al***@example.org");
        assertThat(MaskingRule.EMAIL_LOCAL.apply("a@example.org")).isEqualTo("a***@example.org");
        assertThat(MaskingRule.LAST4.apply("01012345678")).isEqualTo("*******5678");
        assertThat(MaskingRule.LAST4.apply("123")).isEqualTo("***");
        assertThat(MaskingRule.PARTIAL.apply("홍길동")).isEqualTo("홍*동");
        assertThat(MaskingRule.PARTIAL.apply("홍")).isEqualTo("홍");
        assertThat(MaskingRule.PARTIAL.apply("홍길")).isEqualTo("홍*");
        assertThat(MaskingRule.NONE.apply("x")).isEqualTo("x");
        assertThat(MaskingRule.PRESET.apply(null)).isNull();
    }
}
