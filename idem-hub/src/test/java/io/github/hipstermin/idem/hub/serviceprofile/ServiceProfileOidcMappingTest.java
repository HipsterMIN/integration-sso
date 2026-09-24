package io.github.hipstermin.idem.hub.serviceprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S6: {@code protocol.oidc} 는 컬럼이 없어 프로파일 JSON 에서만 온다 — 왕복(파싱 → 컬럼 투영 → 합성)에서 잃지 않아야 한다. */
@DisplayName("ServiceProfileMapper — protocol.oidc 왕복 보존 (S6)")
class ServiceProfileOidcMappingTest {

    private final ObjectMapper om = new ObjectMapper();
    private final ServiceProfileMapper mapper = new ServiceProfileMapper(om);

    @Test
    @DisplayName("파싱한 oidc 블록이 applyToEntity → fromEntity 를 거쳐도 그대로 남는다")
    void oidc_survivesRoundTrip() throws Exception {
        ServiceProfile parsed = mapper.parse(om.readTree("""
                {"schemaVersion":1,
                 "service":{"code":"AG_OIDC","name":"OIDC 기관","status":"ACTIVE"},
                 "protocol":{"type":"OIDC_RP",
                             "oidc":{"redirectUris":["https://rp.example.org/login/oauth2/code/idem"],
                                     "postLogoutRedirectUris":["https://rp.example.org/"],
                                     "backchannelLogoutUri":"https://rp.example.org/bc-logout",
                                     "clientAuthMethod":"CLIENT_SECRET_POST"}},
                 "policy":{"minAuthLevel":"L1"}}
                """));
        assertThat(parsed.protocol().type()).isEqualTo(IntegrationType.OIDC_RP);
        assertThat(parsed.protocol().oidc().clientAuthMethodOrDefault()).isEqualTo("CLIENT_SECRET_POST");

        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder().agencyCode("AG_OIDC").build();
        mapper.applyToEntity(parsed, entity);
        assertThat(entity.getIntegrationType()).isEqualTo(IntegrationType.OIDC_RP);

        ServiceProfile merged = mapper.fromEntity(entity, parsed);
        assertThat(merged.protocol().oidc()).isNotNull();
        assertThat(merged.protocol().oidc().redirectUris()).containsExactly("https://rp.example.org/login/oauth2/code/idem");
        assertThat(merged.protocol().oidc().backchannelLogoutUri()).isEqualTo("https://rp.example.org/bc-logout");
        assertThat(mapper.toJson(merged)).contains("\"oidc\"");
    }

    @Test
    @DisplayName("oidc 가 없는 프로파일은 합성 결과에도 oidc 가 없고, clientAuthMethod 기본은 CLIENT_SECRET_BASIC 이다")
    void defaults() {
        ServiceProfile.Oidc oidc = ServiceProfile.Oidc.builder().redirectUris(java.util.List.of("https://x/cb")).build();
        assertThat(oidc.clientAuthMethodOrDefault()).isEqualTo(ServiceProfile.Oidc.AUTH_BASIC);
        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder().agencyCode("AG").integrationType(IntegrationType.DIRECT).build();
        assertThat(mapper.fromEntity(entity, null).protocol().oidc()).isNull();
        assertThat(IntegrationType.OIDC_RP.usesHandoff()).isFalse();
        assertThat(IntegrationType.DIRECT.usesHandoff()).isTrue();
    }
}
