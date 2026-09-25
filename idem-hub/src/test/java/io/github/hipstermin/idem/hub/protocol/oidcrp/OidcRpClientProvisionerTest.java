package io.github.hipstermin.idem.hub.protocol.oidcrp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.broker.keycloak.admin.KeycloakAdminClient;
import io.github.hipstermin.idem.hub.broker.keycloak.admin.KeycloakAdminException;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OidcRpClientProvisioner — Service Profile → Keycloak client (S6)")
class OidcRpClientProvisionerTest {

    @Mock KeycloakAdminClient keycloak;
    @Mock AuditLogPublisher audit;
    OidcRpProperties props = new OidcRpProperties();
    OidcRpClientProvisioner sut;

    @BeforeEach
    void setUp() {
        props.setIssuer("https://sso.example.org/realms/idem");
        sut = new OidcRpClientProvisioner(keycloak, props, audit);
    }

    private static ServiceProfile oidcProfile(String code) {
        return ServiceProfile.builder()
                .schemaVersion(1)
                .service(new ServiceProfile.Service(code, "OIDC 기관", ServiceProfile.ServiceStatus.ACTIVE, null))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.OIDC_RP)
                        .oidc(ServiceProfile.Oidc.builder()
                                .redirectUris(List.of("https://rp.example.org/login/oauth2/code/idem"))
                                .postLogoutRedirectUris(List.of("https://rp.example.org/", "https://rp.example.org/bye"))
                                .backchannelLogoutUri("https://rp.example.org/bc-logout")
                                .build())
                        .build())
                .policy(ServiceProfile.Policy.builder().build())
                .build();
    }

    private static ServiceProfile directProfile(String code) {
        return ServiceProfile.builder()
                .schemaVersion(1)
                .service(new ServiceProfile.Service(code, "기관", ServiceProfile.ServiceStatus.ACTIVE, null))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT).build())
                .policy(ServiceProfile.Policy.builder().build())
                .build();
    }

    @Test
    @DisplayName("신규: 고정 규칙(confidential·code only·PKCE S256·fullScope=false)과 프로파일 값으로 client 를 만들고 매퍼를 싣는다")
    @SuppressWarnings("unchecked")
    void create_appliesFixedRulesAndProfile() {
        given(keycloak.findClientByClientId("idem-svc-AG1")).willReturn(Optional.empty());
        given(keycloak.createClient(any())).willReturn("uuid-1");
        given(keycloak.getProtocolMappers("uuid-1")).willReturn(List.of(
                Map.of("name", "idem-identity-provider"), Map.of("name", "idem-service")));

        OidcClientStatus status = sut.sync(oidcProfile("AG1"), "admin", "cid");

        ArgumentCaptor<Map<String, Object>> rep = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).createClient(rep.capture());
        Map<String, Object> r = rep.getValue();
        assertThat(r).containsEntry("clientId", "idem-svc-AG1").containsEntry("publicClient", false)
                .containsEntry("standardFlowEnabled", true).containsEntry("implicitFlowEnabled", false)
                .containsEntry("directAccessGrantsEnabled", false).containsEntry("serviceAccountsEnabled", false)
                .containsEntry("fullScopeAllowed", false).containsEntry("enabled", true);
        assertThat((List<String>) r.get("redirectUris")).containsExactly("https://rp.example.org/login/oauth2/code/idem");
        Map<String, String> attrs = (Map<String, String>) r.get("attributes");
        assertThat(attrs).containsEntry("pkce.code.challenge.method", "S256")
                .containsEntry("idem.service.code", "AG1")
                .containsEntry("post.logout.redirect.uris", "https://rp.example.org/##https://rp.example.org/bye")
                .containsEntry("backchannel.logout.url", "https://rp.example.org/bc-logout")
                .containsEntry("backchannel.logout.session.required", "true");
        List<Map<String, Object>> mappers = (List<Map<String, Object>>) r.get("protocolMappers");
        assertThat(mappers).extracting(m -> m.get("name")).containsExactly("idem-identity-provider", "idem-service");
        verify(keycloak, never()).addProtocolMapper(anyString(), any());

        assertThat(status.provisioned()).isTrue();
        assertThat(status.enabled()).isTrue();
        assertThat(status.issuer()).isEqualTo("https://sso.example.org/realms/idem");
        assertThat(status.discoveryUrl()).endsWith("/.well-known/openid-configuration");
        verify(audit).publish(any());
    }

    @Test
    @DisplayName("기존 client: 부분 갱신(PUT)하고 빠진 매퍼만 추가한다")
    void update_existingClient_addsMissingMappers() {
        given(keycloak.findClientByClientId("idem-svc-AG2")).willReturn(Optional.of(Map.of("id", "uuid-2", "enabled", true)));
        given(keycloak.getProtocolMappers("uuid-2")).willReturn(List.of(Map.of("name", "idem-identity-provider")));

        sut.sync(oidcProfile("AG2"), "admin", "cid");

        verify(keycloak).updateClient(eq("uuid-2"), any());
        verify(keycloak, never()).createClient(any());
        ArgumentCaptor<Map<String, Object>> added = ArgumentCaptor.forClass(Map.class);
        verify(keycloak).addProtocolMapper(eq("uuid-2"), added.capture());
        assertThat(added.getValue()).containsEntry("name", "idem-service");
    }

    @Test
    @DisplayName("OIDC_RP 에서 벗어난 프로파일: client 를 지우지 않고 비활성으로 남긴다")
    void leavingOidcRp_disablesClient() {
        given(keycloak.findClientByClientId("idem-svc-AG3")).willReturn(Optional.of(Map.of("id", "uuid-3", "enabled", true,
                "redirectUris", List.of("https://x/cb"))));

        OidcClientStatus status = sut.sync(directProfile("AG3"), "admin", "cid");

        verify(keycloak).updateClient("uuid-3", Map.of("enabled", false));
        assertThat(status.provisioned()).isTrue();
        assertThat(status.enabled()).isFalse();
        assertThat(status.redirectUris()).containsExactly("https://x/cb");
    }

    @Test
    @DisplayName("OIDC_RP 가 아니고 client 도 없으면 아무것도 하지 않는다")
    void nonOidc_noClient_noop() {
        given(keycloak.findClientByClientId("idem-svc-AG4")).willReturn(Optional.empty());
        OidcClientStatus status = sut.sync(directProfile("AG4"), "admin", "cid");
        assertThat(status.provisioned()).isFalse();
        verify(keycloak, never()).updateClient(anyString(), any());
        verify(keycloak, never()).createClient(any());
    }

    @Test
    @DisplayName("Keycloak 실패는 E-IDO-122 — 호출자가 프로파일 저장을 되돌린다")
    void keycloakFailure_isProvisionFailed() {
        given(keycloak.findClientByClientId("idem-svc-AG5")).willThrow(new KeycloakAdminException("boom"));
        assertThatThrownBy(() -> sut.sync(oidcProfile("AG5"), "admin", "cid"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IDO_OIDC_PROVISION_FAILED);
    }

    @Test
    @DisplayName("프로비저닝이 꺼진 설치본에서 OIDC_RP 저장은 E-IDO-122 (조용히 저장하지 않는다)")
    void disabled_rejectsOidcRp() {
        props.setEnabled(false);
        assertThatThrownBy(() -> sut.sync(oidcProfile("AG6"), "admin", "cid"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("idem.hub.oidc-rp.enabled");
        verify(keycloak, never()).findClientByClientId(anyString());
    }

    @Test
    @DisplayName("secret 회전: client 가 있으면 새 값을 한 번 돌려주고, 없으면 E-IDO-123")
    void rotateSecret() {
        given(keycloak.findClientByClientId("idem-svc-AG7")).willReturn(Optional.of(Map.of("id", "uuid-7")));
        given(keycloak.regenerateClientSecret("uuid-7")).willReturn("s3cr3t-new");
        OidcClientSecret secret = sut.rotateSecret("AG7", "admin", "cid");
        assertThat(secret.clientId()).isEqualTo("idem-svc-AG7");
        assertThat(secret.clientSecret()).isEqualTo("s3cr3t-new");

        given(keycloak.findClientByClientId("idem-svc-NOPE")).willReturn(Optional.empty());
        assertThatThrownBy(() -> sut.rotateSecret("NOPE", "admin", "cid"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IDO_OIDC_CLIENT_UNKNOWN);
    }

    @Test
    @DisplayName("clientId ↔ serviceCode 는 접두 규칙으로 서로 바뀐다")
    void clientIdMapping() {
        assertThat(props.clientIdFor("AG_X")).isEqualTo("idem-svc-AG_X");
        assertThat(props.serviceCodeFor("idem-svc-AG_X")).contains("AG_X");
        assertThat(props.serviceCodeFor("idem-gate")).isEmpty();
        assertThat(props.serviceCodeFor("idem-svc-")).isEmpty();
        assertThat(props.serviceCodeFor(null)).isEmpty();
    }
}
