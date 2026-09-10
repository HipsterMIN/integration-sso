package io.github.hipstermin.idem.hub.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubjectIdentifierResolver / CoreSubjectSchemes (S4)")
class SubjectIdentifierResolverTest {

    @Mock QimClient qimClient;

    private SubjectIdentifierResolver resolver() {
        CoreSubjectSchemes core = new CoreSubjectSchemes();
        return new SubjectIdentifierResolver(List.of(core.pairwiseHmacSubjectScheme(qimClient),
                core.platformIdSubjectScheme(), core.emailSubjectScheme(qimClient),
                core.phoneSubjectScheme(qimClient), core.externalSubSubjectScheme(qimClient)));
    }

    private static SubjectResolutionContext ctx() {
        return SubjectResolutionContext.builder().qimUserId("u1").tenantCode("AG").correlationId("c1").build();
    }

    @Test
    @DisplayName("PAIRWISE_HMAC 은 registry DI, 없으면 empty(GUEST)")
    void pairwise_usesDi() {
        given(qimClient.getDi("u1", "AG", "c1")).willReturn("di-1");
        assertThat(resolver().resolve(SubjectScheme.PAIRWISE_HMAC, ctx())).contains("di-1");
        given(qimClient.getDi("u1", "AG", "c1")).willReturn(" ");
        assertThat(resolver().resolve(SubjectScheme.PAIRWISE_HMAC, ctx())).isEmpty();
    }

    @Test
    @DisplayName("PLATFORM_ID 는 qimUserId 그대로, EMAIL 은 registry 주체 키")
    void platformId_andRegistryKey() {
        assertThat(resolver().resolve(SubjectScheme.PLATFORM_ID, ctx())).contains("u1");
        given(qimClient.getSubjectKey("u1", SubjectScheme.EMAIL, "c1")).willReturn(Optional.of("a@b.c"));
        assertThat(resolver().resolve(SubjectScheme.EMAIL, ctx())).contains("a@b.c");
        assertThat(resolver().supports(SubjectScheme.PHONE)).isTrue();
    }

    @Test
    @DisplayName("구현이 없는 스킴은 fail-closed(E-IDO-115), 중복 구현은 기동 거부")
    void unsupported_failsClosed_duplicateRejected() {
        SubjectIdentifierResolver only = new SubjectIdentifierResolver(List.of(new CoreSubjectSchemes().platformIdSubjectScheme()));
        assertThatThrownBy(() -> only.resolve(SubjectScheme.EMAIL, ctx()))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IDO_SUBJECT_SCHEME_UNSUPPORTED);

        CoreSubjectSchemes core = new CoreSubjectSchemes();
        assertThatThrownBy(() -> new SubjectIdentifierResolver(List.of(core.platformIdSubjectScheme(), core.platformIdSubjectScheme())))
                .isInstanceOf(IllegalStateException.class);
    }
}
