package io.github.hipstermin.idem.hub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantProfileService — 검증·투영·이력")
class TenantProfileServiceTest {

    @Mock AgencyMetaJpaRepository jpaRepository;
    @Mock JdbcTemplate            jdbcTemplate;
    @Mock AuditLogPublisher       auditLogPublisher;

    private final ObjectMapper om = new ObjectMapper();
    private TenantProfileService service;

    @BeforeEach
    void setUp() {
        service = new TenantProfileService(jpaRepository, new TenantProfileMapper(om), new TenantProfileValidator(),
                jdbcTemplate, auditLogPublisher);
        lenient().when(jpaRepository.save(any(AgencyMetaJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private JsonNode body(String code, String extra) throws Exception {
        return om.readTree("{\"schemaVersion\":1,\"tenant\":{\"code\":\"" + code + "\",\"name\":\"기관\"},"
                + "\"protocol\":{\"type\":\"BRIDGE\",\"endpoints\":{\"bridge\":\"https://b.example.org/push\"}},"
                + "\"policy\":{\"minAuthLevel\":\"L2\",\"policyVersion\":\"1.1\"}" + extra + "}");
    }

    @Test
    @DisplayName("스키마 위반이면 저장하지 않고 400(E-IDO-113)")
    void put_invalid_rejected() throws Exception {
        JsonNode bad = om.readTree("{\"schemaVersion\":1,\"tenant\":{\"code\":\"AG\"}}");

        assertThatThrownBy(() -> service.put("AG", bad, "admin", null, "cid"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_INVALID_TENANT_PROFILE));
        verify(jpaRepository, never()).save(any());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("경로 코드와 본문 tenant.code 가 다르면 400")
    void put_codeMismatch_rejected() throws Exception {
        assertThatThrownBy(() -> service.put("AG_A", body("AG_B", ""), "admin", null, "cid"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("AG_A").hasMessageContaining("AG_B");
        verify(jpaRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규 기관: 프로파일만으로 생성되고 컬럼이 투영되며 이력 1건이 남는다")
    void put_newTenant_createsAndProjects() throws Exception {
        given(jpaRepository.findById("AG_NEW")).willReturn(Optional.empty());

        TenantProfile result = service.put("AG_NEW", body("AG_NEW", ",\"ui\":{\"brandName\":\"신규\"}"), "admin", "onboard", "cid");

        ArgumentCaptor<AgencyMetaJpaEntity> saved = ArgumentCaptor.forClass(AgencyMetaJpaEntity.class);
        verify(jpaRepository).save(saved.capture());
        AgencyMetaJpaEntity e = saved.getValue();
        assertThat(e.getAgencyCode()).isEqualTo("AG_NEW");
        assertThat(e.getIntegrationType()).isEqualTo(IntegrationType.BRIDGE);
        assertThat(e.getBridgeEndpoint()).isEqualTo("https://b.example.org/push");
        assertThat(e.getMinAuthLevel()).isEqualTo("L2");
        assertThat(e.getPolicyVersion()).isEqualTo("1.1");
        assertThat(e.getProfile()).contains("\"brandName\":\"신규\"");
        assertThat(e.getProfileSchemaVersion()).isEqualTo(1);

        verify(jdbcTemplate).update(anyString(), any(), eq("AG_NEW"), eq("1.1"), anyString(), eq("admin"), eq("onboard"));
        verify(auditLogPublisher).publish(any(AuditLogPublisher.AuditEntry.class));
        assertThat(result.tenant().code()).isEqualTo("AG_NEW");
        assertThat(result.ui().brandName()).isEqualTo("신규");
    }

    @Test
    @DisplayName("기존 기관: 변경 전 프로파일이 이력 스냅샷으로 남는다")
    void put_existingTenant_recordsPreviousSnapshot() throws Exception {
        AgencyMetaJpaEntity existing = AgencyMetaJpaEntity.builder()
                .agencyCode("AG_OLD").officialName("옛 기관").minAuthLevel("L1").policyVersion("1.0")
                .integrationType(IntegrationType.DIRECT).active(true)
                .profile("{\"schemaVersion\":1,\"tenant\":{\"code\":\"AG_OLD\",\"name\":\"옛 기관\"},\"protocol\":{\"type\":\"DIRECT\"},\"policy\":{\"minAuthLevel\":\"L1\"}}")
                .build();
        given(jpaRepository.findById("AG_OLD")).willReturn(Optional.of(existing));

        service.put("AG_OLD", body("AG_OLD", ""), "admin", null, "cid");

        ArgumentCaptor<Object> snapshot = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(anyString(), any(), eq("AG_OLD"), eq("1.1"), snapshot.capture(), eq("admin"), eq("프로파일 갱신"));
        assertThat(snapshot.getValue().toString()).contains("\"name\":\"옛 기관\"").contains("\"type\":\"DIRECT\"");
        assertThat(existing.getIntegrationType()).isEqualTo(IntegrationType.BRIDGE);
        assertThat(existing.getOfficialName()).isEqualTo("기관");
    }

    @Test
    @DisplayName("없는 기관 조회는 AGENCY_NOT_REGISTERED")
    void get_missing_throws() {
        given(jpaRepository.findById("NOPE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("NOPE"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                        .isEqualTo(PlatformErrorCode.AGENCY_NOT_REGISTERED));
    }
}
