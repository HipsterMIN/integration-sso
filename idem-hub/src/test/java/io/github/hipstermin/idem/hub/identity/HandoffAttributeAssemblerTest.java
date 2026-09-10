package io.github.hipstermin.idem.hub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.MaskingRule;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HandoffAttributeAssembler — 프로파일 identity 계약으로 속성 조립 (S4)")
class HandoffAttributeAssemblerTest {

    @Mock QimClient qimClient;

    private static final Instant ISSUED = Instant.parse("2026-09-10T01:02:03Z");

    private HandoffAttributeAssembler assembler() {
        SubjectIdentifierResolver resolver = new SubjectIdentifierResolver(List.of(
                new CoreSubjectSchemes().emailSubjectScheme(qimClient)));
        return new HandoffAttributeAssembler(qimClient, resolver);
    }

    private static HandoffTicket ticket() {
        return HandoffTicket.builder().ticketId("t1").correlationId("c1").agencyCode("AG").qimUserId("u1")
                .authResultId("ar1").authLevel(AuthResult.AuthLevel.L2).state(HandoffTicket.TicketState.ISSUED)
                .issuedAt(ISSUED).expiresAt(ISSUED.plusSeconds(60)).build();
    }

    private static ServiceProfile.Identity identity(List<ServiceProfile.AttributeSelection> sel, Map<String, String> mapping) {
        return new ServiceProfile.Identity(null, sel, mapping);
    }

    @Test
    @DisplayName("선언이 없으면 빈 맵 — 최소 권한. registry 조회도 없다")
    void noSelection_emptyMap() {
        assertThat(assembler().assemble(null, ticket(), "c1")).isEmpty();
        assertThat(assembler().assemble(identity(List.of(), null), ticket(), "c1")).isEmpty();
        then(qimClient).should(never()).getUserById(any(), any());
    }

    @Test
    @DisplayName("TICKET 속성은 조회 없이 채워지고, 별칭(camelCase)으로 선언하면 출력 키도 별칭 — 종전 페이로드 호환")
    void ticketAttributes_legacyAliasKeepsWireKey() {
        Map<String, Object> out = assembler().assemble(identity(List.of(
                ServiceProfile.AttributeSelection.of("qimUserId"),
                ServiceProfile.AttributeSelection.of("auth_level"),
                ServiceProfile.AttributeSelection.of("authenticated_at")), null), ticket(), "c1");

        assertThat(out).containsEntry("qimUserId", "u1").containsEntry("auth_level", "L2")
                .containsEntry("authenticated_at", ISSUED.toString());
        then(qimClient).should(never()).getUserById(any(), any());
    }

    @Test
    @DisplayName("PROFILE 속성은 registry 를 1회만 조회하고, attributeMapping 이 출력 키를 바꾼다")
    void profileAttributes_fetchedOnce_mapped() {
        Map<String, Object> profile = new HashMap<>();
        profile.put("nameMasked", "홍*동");
        profile.put("birthYear", 1990);
        given(qimClient.getUserById("u1", "c1")).willReturn(profile);

        Map<String, Object> out = assembler().assemble(identity(List.of(
                ServiceProfile.AttributeSelection.of("name_masked"),
                ServiceProfile.AttributeSelection.of("birth_year"),
                ServiceProfile.AttributeSelection.of("gender")),           // 값 없음 → 생략
                Map.of("name_masked", "userNm")), ticket(), "c1");

        assertThat(out).containsEntry("userNm", "홍*동").containsEntry("birth_year", 1990).doesNotContainKey("gender");
        then(qimClient).should().getUserById("u1", "c1");
    }

    @Test
    @DisplayName("SUBJECT 속성(email)은 스킴 조회로 채우고 카탈로그 기본 마스킹(EMAIL_LOCAL)이 적용된다; masking:NONE 이면 원문")
    void subjectAttribute_defaultMasking_andOverride() {
        given(qimClient.getSubjectKey("u1", SubjectScheme.EMAIL, "c1")).willReturn(Optional.of("alice@example.org"));

        Map<String, Object> masked = assembler().assemble(identity(List.of(
                ServiceProfile.AttributeSelection.of("email")), null), ticket(), "c1");
        Map<String, Object> raw = assembler().assemble(identity(List.of(
                new ServiceProfile.AttributeSelection("email", null, MaskingRule.NONE)), Map.of("email", "mail")), ticket(), "c1");

        assertThat(masked).containsEntry("email", "al***@example.org");
        assertThat(raw).containsEntry("mail", "alice@example.org");
    }

    @Test
    @DisplayName("required:true 인 속성을 얻지 못하면 E-IDO-114 로 거부한다")
    void requiredMissing_rejects() {
        given(qimClient.getSubjectKey("u1", SubjectScheme.EMAIL, "c1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> assembler().assemble(identity(List.of(
                new ServiceProfile.AttributeSelection("email", true, null)), null), ticket(), "c1"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IDO_REQUIRED_ATTRIBUTE_MISSING);
    }

    @Test
    @DisplayName("registry 프로필 조회가 실패(null)하면 속성 없음으로 삼키지 않고 IDO_QIM_UNREACHABLE (F4.6)")
    void profileFetchFailure_failsClosed() {
        given(qimClient.getUserById("u1", "c1")).willReturn(null);

        assertThatThrownBy(() -> assembler().assemble(identity(List.of(
                ServiceProfile.AttributeSelection.of("name_masked")), null), ticket(), "c1"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE);
    }

    @Test
    @DisplayName("카탈로그에 없는 이름은 무시된다(레거시 컬럼 값 방어)")
    void unknownName_ignored() {
        Map<String, Object> out = assembler().assemble(identity(List.of(
                ServiceProfile.AttributeSelection.of("not_in_catalog"),
                ServiceProfile.AttributeSelection.of("agency_code")), null), ticket(), "c1");
        assertThat(out).containsOnlyKeys("agency_code");
    }
}
