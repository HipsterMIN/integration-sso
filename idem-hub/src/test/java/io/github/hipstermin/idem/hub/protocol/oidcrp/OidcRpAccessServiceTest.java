package io.github.hipstermin.idem.hub.protocol.oidcrp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.broker.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo;
import io.github.hipstermin.idem.hub.infrastructure.QimRegisterResponse;
import io.github.hipstermin.idem.hub.infrastructure.ServiceAccess;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.policy.rule.PolicyEvaluation;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OidcRpAccessService — 표준 OIDC 토큰 교환 시점의 정책 강제 (S6)")
class OidcRpAccessServiceTest {

    @Mock ServiceProfileService profiles;
    @Mock QimClient qim;
    @Mock QAuthzClient authz;
    @Mock PolicyEngine engine;
    OidcRpProperties props = new OidcRpProperties();
    KeycloakProperties kc = new KeycloakProperties();
    OidcRpAccessService sut;

    @BeforeEach
    void setUp() {
        sut = new OidcRpAccessService(props, profiles, kc, qim, authz, engine);
        given(profiles.find("AG1")).willReturn(Optional.of(profile("AG1", IntegrationType.OIDC_RP, null)));
        QimMemberInfo info = mock(QimMemberInfo.class);
        given(info.getQimUserId()).willReturn("qim-1");
        given(qim.findBySocialSub(eq("kc-sub"), anyString(), anyString())).willReturn(Optional.of(info));
        given(engine.evaluate(any(), eq(true))).willReturn(PolicyEvaluation.allowedAll());
        given(engine.resolveAgencySubjectId(any(), eq("qim-1"), eq("AG1"), anyString())).willReturn("pairwise-1");
        given(engine.resolveUserStatus(eq("qim-1"), anyString())).willReturn(UserStatus.ACTIVE);
        given(authz.getServiceAccess("qim-1", "AG1", "cid")).willReturn(new ServiceAccess(true, true, "CONSOLE", List.of("VIEWER")));
    }

    private static ServiceProfile profile(String code, IntegrationType type, ServiceProfile.Assignment assignment) {
        return ServiceProfile.builder()
                .schemaVersion(1)
                .service(new ServiceProfile.Service(code, "기관", ServiceProfile.ServiceStatus.ACTIVE, null))
                .protocol(ServiceProfile.Protocol.builder().type(type)
                        .oidc(ServiceProfile.Oidc.builder().redirectUris(List.of("https://rp/cb")).build()).build())
                .policy(ServiceProfile.Policy.builder().assignment(assignment).build())
                .build();
    }

    private static OidcRpAccessRequest req(String clientId, String idp, String acr) {
        return new OidcRpAccessRequest(clientId, "kc-sub", idp, acr, "sid-1", "cid");
    }

    @Test
    @DisplayName("허용: registry 기존 사용자 → 정책 ALLOW → APPROVED + agencySubjectId + roles + assigned, 인증 컨텍스트는 Keycloak 클레임에서")
    void allowed_existingUser() {
        OidcRpAccessResponse r = sut.evaluate(req("idem-svc-AG1", "social-kakao", "2"), "cid");

        assertThat(r.allowed()).isTrue();
        assertThat(r.serviceCode()).isEqualTo("AG1");
        assertThat(r.qimUserId()).isEqualTo("qim-1");
        assertThat(r.state()).isEqualTo(HandoffPayload.HandoffState.APPROVED);
        assertThat(r.agencySubjectId()).isEqualTo("pairwise-1");
        assertThat(r.subjectScheme()).isEqualTo("PAIRWISE_HMAC");
        assertThat(r.roles()).containsExactly("VIEWER");
        assertThat(r.assigned()).isTrue();
        assertThat(r.providerCode()).isEqualTo("KAKAO_OIDC");
        assertThat(r.authLevel()).isEqualTo("L2");

        ArgumentCaptor<PolicyContext> ctx = ArgumentCaptor.forClass(PolicyContext.class);
        verify(engine).evaluate(ctx.capture(), eq(true));
        assertThat(ctx.getValue().serviceCode()).isEqualTo("AG1");
        assertThat(ctx.getValue().providerCode()).isEqualTo("KAKAO_OIDC");
        assertThat(ctx.getValue().authLevel().name()).isEqualTo("L2");
        assertThat(ctx.getValue().userStatus().get()).isEqualTo(UserStatus.ACTIVE);
        assertThat(ctx.getValue().serviceAccess().get().assigned()).isTrue();
        verify(qim, never()).registerSocialUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("registry 에 없는 사용자는 (sub, providerCode) 로 등록한다 — OIDC_RP 는 콜백을 거치지 않아 여기가 첫 만남")
    void registersUnknownUser() {
        given(qim.findBySocialSub(eq("kc-sub"), anyString(), anyString())).willReturn(Optional.empty());
        QimRegisterResponse reg = mock(QimRegisterResponse.class);
        given(reg.getQimUserId()).willReturn("qim-1");
        given(qim.registerSocialUser(eq("kc-sub"), eq("KAKAO_OIDC"), anyString(), anyString())).willReturn(reg);

        OidcRpAccessResponse r = sut.evaluate(req("idem-svc-AG1", "social-kakao", null), "cid");
        assertThat(r.allowed()).isTrue();
        assertThat(r.authLevel()).isEqualTo("L1");
        verify(qim).registerSocialUser(eq("kc-sub"), eq("KAKAO_OIDC"), anyString(), eq("cid"));
    }

    @Test
    @DisplayName("Idem 이 프로비저닝하지 않은 client·OIDC_RP 가 아닌 프로파일·비활성 서비스는 거부")
    void unknownClientOrProfile() {
        assertThat(sut.evaluate(req("q-sign-client", null, null), "cid").denyCode()).isEqualTo("E-IDO-123");

        given(profiles.find("AG2")).willReturn(Optional.of(profile("AG2", IntegrationType.DIRECT, null)));
        assertThat(sut.evaluate(req("idem-svc-AG2", null, null), "cid").denyCode()).isEqualTo("E-IDO-123");

        given(profiles.find("AG3")).willReturn(Optional.empty());
        assertThat(sut.evaluate(req("idem-svc-AG3", null, null), "cid").denyCode()).isEqualTo("E-IDO-123");
        verify(qim, never()).findBySocialSub(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("정책 DENY 는 규칙의 코드 그대로 거부한다 (예: ASSIGNMENT → E-IDO-120)")
    void policyDeny() {
        given(engine.evaluate(any(), eq(true))).willReturn(new PolicyEvaluation(List.of(
                PolicyDecision.deny("ASSIGNMENT", "미할당", "ASSIGNMENT_REQUIRED", PlatformErrorCode.IDO_ASSIGNMENT_REQUIRED))));
        OidcRpAccessResponse r = sut.evaluate(req("idem-svc-AG1", "social-kakao", "1"), "cid");
        assertThat(r.allowed()).isFalse();
        assertThat(r.denyCode()).isEqualTo("E-IDO-120");
        assertThat(r.rule()).isEqualTo("ASSIGNMENT");
        assertThat(r.qimUserId()).isNull();
    }

    @Test
    @DisplayName("할당 필수 + 미할당(selfSignup 통과) → GUEST, authz 비활성 → E-IDO-117")
    void assignmentStates() {
        given(profiles.find("AG1")).willReturn(Optional.of(profile("AG1", IntegrationType.OIDC_RP, new ServiceProfile.Assignment(true, true))));
        given(authz.getServiceAccess("qim-1", "AG1", "cid")).willReturn(new ServiceAccess(true, false, null, List.of()));
        OidcRpAccessResponse guest = sut.evaluate(req("idem-svc-AG1", "social-kakao", "1"), "cid");
        assertThat(guest.allowed()).isTrue();
        assertThat(guest.state()).isEqualTo(HandoffPayload.HandoffState.GUEST);
        assertThat(guest.assigned()).isFalse();

        given(authz.getServiceAccess("qim-1", "AG1", "cid")).willReturn(ServiceAccess.disabled());
        OidcRpAccessResponse denied = sut.evaluate(req("idem-svc-AG1", "social-kakao", "1"), "cid");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.denyCode()).isEqualTo("E-IDO-117");
    }

    @Test
    @DisplayName("registry·authz 장애는 거부다 (E-IDO-116 / 규칙이 던진 코드) — 허용으로 새지 않는다")
    void dependencyFailures_deny() {
        given(qim.findBySocialSub(eq("kc-sub"), anyString(), anyString())).willThrow(new RuntimeException("registry down"));
        assertThat(sut.evaluate(req("idem-svc-AG1", null, null), "cid").denyCode()).isEqualTo("E-IDO-116");

        QimMemberInfo info = mockInfo();
        given(qim.findBySocialSub(eq("kc-sub"), anyString(), anyString())).willReturn(Optional.of(info));
        given(engine.evaluate(any(), eq(true))).willThrow(new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, "cid"));
        assertThat(sut.evaluate(req("idem-svc-AG1", null, null), "cid").denyCode()).isEqualTo("E-IDO-117");
    }

    private static QimMemberInfo mockInfo() {
        QimMemberInfo info = mock(QimMemberInfo.class);
        given(info.getQimUserId()).willReturn("qim-1");
        return info;
    }
}
