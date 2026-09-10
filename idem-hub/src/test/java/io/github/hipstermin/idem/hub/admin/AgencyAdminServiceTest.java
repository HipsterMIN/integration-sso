package io.github.hipstermin.idem.hub.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.dto.AgencyCreateRequest;
import io.github.hipstermin.idem.hub.admin.dto.AgencyResponse;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S1 범용화 — 기관 등록·수정 시 연동 유형은 {@link IntegrationType} 으로 검증되고,
 * APACHE_GATE 엔드포인트는 전용 컬럼에 저장된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgencyAdminService — 연동 유형 검증·엔드포인트 분리")
class AgencyAdminServiceTest {

    @Mock AgencyMetaJpaRepository jpaRepository;
    @Mock AuditLogPublisher       auditLogPublisher;
    @Mock JdbcTemplate            jdbcTemplate;

    private AgencyAdminService service;

    @BeforeEach
    void setUp() {
        service = new AgencyAdminService(jpaRepository, auditLogPublisher, jdbcTemplate, new ObjectMapper(),
                new ServiceProfileMapper(new ObjectMapper()));
        lenient().when(jpaRepository.save(any(AgencyMetaJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("미지의 integrationType 은 400(E-IDO-111) 로 거부하고 저장하지 않는다")
    void createAgency_unknownIntegrationType_rejected() {
        AgencyCreateRequest req = AgencyCreateRequest.builder()
                .agencyCode("AG_X").officialName("X").integrationType("WEBHOOK").build();

        assertThatThrownBy(() -> service.createAgency(req, "admin"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_INVALID_INTEGRATION_TYPE))
                .hasMessageContaining("WEBHOOK");

        verify(jpaRepository, never()).save(any());
    }

    @Test
    @DisplayName("integrationType 미지정이면 DEFAULT(DIRECT)")
    void createAgency_noIntegrationType_defaultsToDirect() {
        AgencyCreateRequest req = AgencyCreateRequest.builder()
                .agencyCode("AG_D").officialName("D").build();

        AgencyResponse res = service.createAgency(req, "admin");

        assertThat(res.getIntegrationType()).isEqualTo("DIRECT");
    }

    @Test
    @DisplayName("APACHE_GATE 는 apacheGateEndpoint 전용 필드에 저장되고 bridgeEndpoint 와 섞이지 않는다")
    void createAgency_apacheGate_storesDedicatedEndpoint() {
        AgencyCreateRequest req = AgencyCreateRequest.builder()
                .agencyCode("AG_G").officialName("G")
                .integrationType(" apache_gate ")
                .apacheGateEndpoint("https://gw.example.org/internal/sso-session")
                .build();

        AgencyResponse res = service.createAgency(req, "admin");

        ArgumentCaptor<AgencyMetaJpaEntity> saved = ArgumentCaptor.forClass(AgencyMetaJpaEntity.class);
        verify(jpaRepository).save(saved.capture());
        assertThat(saved.getValue().getIntegrationType()).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(saved.getValue().getApacheGateEndpoint()).isEqualTo("https://gw.example.org/internal/sso-session");
        assertThat(saved.getValue().getBridgeEndpoint()).isNull();
        assertThat(res.getIntegrationType()).isEqualTo("APACHE_GATE");
        assertThat(res.getApacheGateEndpoint()).isEqualTo("https://gw.example.org/internal/sso-session");
    }

    @Test
    @DisplayName("수정 시에도 미지의 integrationType 은 거부한다")
    void updateAgency_unknownIntegrationType_rejected() {
        AgencyMetaJpaEntity existing = AgencyMetaJpaEntity.builder()
                .agencyCode("AG_U").officialName("U").integrationType(IntegrationType.DIRECT).active(true).build();
        given(jpaRepository.findById("AG_U")).willReturn(java.util.Optional.of(existing));
        AgencyCreateRequest req = AgencyCreateRequest.builder().integrationType("SAML").build();

        assertThatThrownBy(() -> service.updateAgency("AG_U", req, "admin"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("SAML");
        verify(jpaRepository, never()).save(any());
    }
}
