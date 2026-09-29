package io.github.hipstermin.idem.hub.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 1.1 registry 정지·탈퇴 → 할당된 SCIM 기관에만 전파, 조회 실패는 삼킨다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScimUserLifecycleHandlerTest {

    @Mock QAuthzClient authz;
    @Mock ServiceProfileService profiles;
    @Mock PolicyEngine policyEngine;
    @Mock ScimOutboxService outbox;
    @InjectMocks ScimUserLifecycleHandler sut;

    private static ServiceProfile profile(String code, boolean scim) {
        return ServiceProfile.builder().service(ServiceProfile.Service.builder().code(code).name(code).build())
                .protocol(ServiceProfile.Protocol.builder().scim(scim ? ServiceProfile.Scim.builder().enabled(true)
                        .baseUrl("https://a.example/scim/v2").credentialRef("secrets/agency/" + code + "/scim-token").build() : null).build()).build();
    }

    private static UserEvent event(String type, String status) {
        return UserEvent.builder().eventId("ev-1").eventType(type).qimUserId("u1").userStatus(status).build();
    }

    @Test
    @DisplayName("할당된 기관 중 SCIM 켜진 곳만, 탈퇴는 withdrawn=true 로")
    void fanOut() {
        when(authz.listUserAssignedAgencies("u1", "cid")).thenReturn(List.of("AG_SCIM", "AG_PLAIN"));
        when(profiles.find("AG_SCIM")).thenReturn(Optional.of(profile("AG_SCIM", true)));
        when(profiles.find("AG_PLAIN")).thenReturn(Optional.of(profile("AG_PLAIN", false)));
        when(policyEngine.resolveAgencySubjectId(any(), eq("u1"), eq("AG_SCIM"), anyString())).thenReturn("pw-1");
        when(outbox.onUserTerminal(any(), eq("pw-1"), eq(true), eq("ev-1"), eq(UserEvent.TYPE_WITHDRAWN), eq("cid"))).thenReturn(1);

        assertThat(sut.onTerminal("u1", event(UserEvent.TYPE_WITHDRAWN, "WITHDRAWN"), "cid")).isEqualTo(1);
        verify(policyEngine, never()).resolveAgencySubjectId(any(), any(), eq("AG_PLAIN"), any());
    }

    @Test
    @DisplayName("authz 조회 실패 → -1, 예외 없음(등록 이벤트 처리는 계속)")
    void authzFailureSwallowed() {
        when(authz.listUserAssignedAgencies(anyString(), anyString())).thenThrow(new RuntimeException("down"));
        assertThat(sut.onTerminal("u1", event(UserEvent.TYPE_SUSPENDED, "SUSPENDED"), "cid")).isEqualTo(-1);
        verify(outbox, never()).onUserTerminal(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any());
    }
}
