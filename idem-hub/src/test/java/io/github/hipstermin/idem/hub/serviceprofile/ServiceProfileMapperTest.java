package io.github.hipstermin.idem.hub.serviceprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.identity.MaskingRule;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceProfileMapper — 컬럼 ↔ 프로파일 투영")
class ServiceProfileMapperTest {

    private final ServiceProfileMapper mapper = new ServiceProfileMapper(new ObjectMapper());

    private static AgencyMetaJpaEntity entity() {
        return AgencyMetaJpaEntity.builder()
                .agencyCode("AG_MAP").officialName("매핑 기관")
                .minAuthLevel("L2").policyVersion("1.4")
                .integrationType(IntegrationType.APACHE_GATE)
                .apacheGateEndpoint("https://gw.example.org/sso")
                .callbackWhitelist("[\"https://a.example.org/cb\"]")
                .allowedAttributes("[\"name_masked\"]")
                .maintenanceWindows("[{\"dayOfWeek\":\"SUN\",\"startTime\":\"01:00\",\"endTime\":\"02:00\"}]")
                .dailyLookupLimit(5000)
                .active(false)
                .build();
    }

    @Test
    @DisplayName("컬럼 → 프로파일: 컬럼 값이 그대로 실린다")
    void fromEntity_projectsColumns() {
        ServiceProfile p = mapper.fromEntity(entity(), null);

        assertThat(p.schemaVersion()).isEqualTo(1);
        assertThat(p.service().code()).isEqualTo("AG_MAP");
        assertThat(p.service().status()).isEqualTo(ServiceProfile.ServiceStatus.INACTIVE);
        assertThat(p.protocol().type()).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(p.protocol().endpoints().apacheGate()).isEqualTo("https://gw.example.org/sso");
        assertThat(p.protocol().endpoints().callbackWhitelist()).containsExactly("https://a.example.org/cb");
        assertThat(p.identity().attributeNames()).containsExactly("name_masked");
        assertThat(p.policy().minAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(p.policy().policyVersion()).isEqualTo("1.4");
        assertThat(p.policy().maintenance()).singleElement()
                .satisfies(w -> assertThat(w.dayOfWeek()).isEqualTo("SUN"));
        assertThat(p.limits().daily()).isEqualTo(5000);
    }

    @Test
    @DisplayName("컬럼 → 프로파일 합성 시 프로파일에만 있는 항목(ui·session·security·mapping·tps)은 보존된다")
    void fromEntity_preservesProfileOnlyFields() {
        ServiceProfile existing = ServiceProfile.builder()
                .schemaVersion(1)
                .service(new ServiceProfile.Service("AG_MAP", "옛 이름", ServiceProfile.ServiceStatus.ACTIVE))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.DIRECT)
                        .security(new ServiceProfile.Security(true, List.of("10.0.0.0/8"))).build())
                .identity(new ServiceProfile.Identity(SubjectScheme.EMAIL,
                        List.of(new ServiceProfile.AttributeSelection("name_masked", true, MaskingRule.NONE)),
                        java.util.Map.of("name_masked", "userNm")))
                .policy(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L1)
                        .allowedProviders(List.of("NICE")).session(new ServiceProfile.Session(30, 480, 1)).build())
                .limits(new ServiceProfile.Limits(50, 1))
                .ui(new ServiceProfile.Ui("기관 A", null, "ko"))
                .build();

        ServiceProfile merged = mapper.fromEntity(entity(), existing);

        // 컬럼이 진실인 항목은 컬럼 값
        assertThat(merged.service().name()).isEqualTo("매핑 기관");
        assertThat(merged.protocol().type()).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(merged.policy().minAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(merged.limits().daily()).isEqualTo(5000);
        // 프로파일에만 있는 항목은 보존
        assertThat(merged.protocol().security().mtlsRequired()).isTrue();
        assertThat(merged.identity().attributeMapping()).containsEntry("name_masked", "userNm");
        // S4: 스킴·속성 옵션(required·masking)도 프로파일에만 있으므로 보존된다
        assertThat(merged.identity().subjectScheme()).isEqualTo(SubjectScheme.EMAIL);
        assertThat(merged.identity().attributes()).singleElement()
                .satisfies(sel -> { assertThat(sel.isRequired()).isTrue(); assertThat(sel.masking()).isEqualTo(MaskingRule.NONE); });
        assertThat(merged.policy().allowedProviders()).containsExactly("NICE");
        assertThat(merged.policy().session().idleMinutes()).isEqualTo(30);
        assertThat(merged.limits().tps()).isEqualTo(50);
        assertThat(merged.ui().brandName()).isEqualTo("기관 A");
    }

    @Test
    @DisplayName("프로파일 → 컬럼: 투영되고 profile 원문이 함께 저장된다")
    void applyToEntity_projectsToColumns() {
        ServiceProfile p = ServiceProfile.builder()
                .schemaVersion(1)
                .service(new ServiceProfile.Service("AG_NEW", "새 기관", ServiceProfile.ServiceStatus.ACTIVE))
                .protocol(ServiceProfile.Protocol.builder().type(IntegrationType.BRIDGE)
                        .endpoints(ServiceProfile.Endpoints.builder().bridge("https://b.example.org/push")
                                .callbackWhitelist(List.of("https://b.example.org/cb")).build()).build())
                .identity(new ServiceProfile.Identity(null, ServiceProfile.Identity.selectionsOf(List.of("name_masked", "mobile_masked")), null))
                .policy(ServiceProfile.Policy.builder().minAuthLevel(AuthResult.AuthLevel.L3).policyVersion("2.0")
                        .maintenance(List.of(new ServiceProfile.MaintenanceWindow("MON", "02:00", "04:00"))).build())
                .limits(new ServiceProfile.Limits(null, 777))
                .ui(new ServiceProfile.Ui("B", null, null))
                .build();
        AgencyMetaJpaEntity e = AgencyMetaJpaEntity.builder().agencyCode("AG_NEW").build();

        mapper.applyToEntity(p, e);

        assertThat(e.getOfficialName()).isEqualTo("새 기관");
        assertThat(e.isActive()).isTrue();
        assertThat(e.getIntegrationType()).isEqualTo(IntegrationType.BRIDGE);
        assertThat(e.getBridgeEndpoint()).isEqualTo("https://b.example.org/push");
        assertThat(e.getApacheGateEndpoint()).isNull();
        assertThat(e.getCallbackWhitelist()).contains("https://b.example.org/cb");
        assertThat(e.getAllowedAttributes()).contains("name_masked").contains("mobile_masked");
        assertThat(e.getMinAuthLevel()).isEqualTo("L3");
        assertThat(e.getPolicyVersion()).isEqualTo("2.0");
        assertThat(e.getMaintenanceWindows()).contains("\"dayOfWeek\":\"MON\"");
        assertThat(e.getDailyLookupLimit()).isEqualTo(777);
        assertThat(e.getProfile()).contains("\"brandName\":\"B\"");
        assertThat(e.getProfileSchemaVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("syncProfileColumn: 컬럼을 고친 뒤 호출하면 프로파일이 따라오고 프로파일 전용 항목은 남는다")
    void syncProfileColumn_keepsProfileOnlyFields() {
        AgencyMetaJpaEntity e = entity();
        e.setProfile("{\"schemaVersion\":1,\"service\":{\"code\":\"AG_MAP\",\"name\":\"옛\"},"
                + "\"protocol\":{\"type\":\"DIRECT\"},\"policy\":{\"minAuthLevel\":\"L1\"},\"ui\":{\"brandName\":\"보존\"}}");
        e.setOfficialName("바뀐 이름");

        mapper.syncProfileColumn(e);

        ServiceProfile synced = mapper.fromJson(e.getProfile());
        assertThat(synced.service().name()).isEqualTo("바뀐 이름");
        assertThat(synced.protocol().type()).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(synced.ui().brandName()).isEqualTo("보존");
    }

    @Test
    @DisplayName("JSON 왕복: parse(toNode(p)) 는 p 와 같다")
    void jsonRoundTrip() {
        ServiceProfile p = mapper.fromEntity(entity(), null);
        assertThat(mapper.parse(mapper.toNode(p))).isEqualTo(p);
        assertThat(mapper.fromJson(mapper.toJson(p))).isEqualTo(p);
    }
}
