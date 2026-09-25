package io.github.hipstermin.idem.hub.fe.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeSessionPolicyEnforcer — Handoff 발급 시 프로파일 세션 정책 적용")
class FeSessionPolicyEnforcerTest {

    @Mock ServiceProfileService profiles;
    @Mock FeSessionService sessions;

    private static ServiceProfile profile(ServiceProfile.Session s) {
        return ServiceProfile.builder().schemaVersion(1)
                .service(new ServiceProfile.Service("AG1", "기관", ServiceProfile.ServiceStatus.ACTIVE))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                .policy(ServiceProfile.Policy.builder().session(s).build()).build();
    }

    @Test
    void appliesProfileSession() {
        given(profiles.find("AG1")).willReturn(Optional.of(profile(new ServiceProfile.Session(20, 240, 2))));
        FeSessionPolicyEnforcer sut = new FeSessionPolicyEnforcer(profiles, sessions);
        HandoffPayload.SessionPolicy applied = sut.applyForService("fe-1", "AG1", "cid");
        assertThat(applied).isEqualTo(new HandoffPayload.SessionPolicy(20, 240, 2));
        verify(sessions).applySessionPolicy("fe-1", 20, 240, 2);
    }

    @Test
    void noSessionBlockDoesNothing() {
        given(profiles.find("AG1")).willReturn(Optional.of(profile(null)));
        FeSessionPolicyEnforcer sut = new FeSessionPolicyEnforcer(profiles, sessions);
        assertThat(sut.applyForService("fe-1", "AG1", "cid")).isNull();
        assertThat(sut.applyForService(null, "AG1", "cid")).isNull();
        verify(sessions, never()).applySessionPolicy(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("적용 실패는 삼킨다 — 발급을 되돌리지 않는다")
    void failureSwallowed() {
        given(profiles.find("AG1")).willReturn(Optional.of(profile(new ServiceProfile.Session(20, null, null))));
        given(sessions.applySessionPolicy(anyString(), any(), any(), any())).willThrow(new IllegalStateException("redis down"));
        FeSessionPolicyEnforcer sut = new FeSessionPolicyEnforcer(profiles, sessions);
        assertThat(sut.applyForService("fe-1", "AG1", "cid")).isNotNull();
    }
}
