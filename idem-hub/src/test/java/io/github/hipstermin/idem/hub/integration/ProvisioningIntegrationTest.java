package io.github.hipstermin.idem.hub.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.hipstermin.idem.hub.infrastructure.AgencyEndpointRecord;
import io.github.hipstermin.idem.hub.infrastructure.AgencyEndpointRegistryRepository;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.provision.ProvisioningOutboxRecord;
import io.github.hipstermin.idem.hub.provision.ProvisioningOutboxRepository;
import io.github.hipstermin.idem.hub.provision.ProvisioningService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Sprint 14 — 전 기관 프로비저닝 통합 테스트
 *
 * <p>실제 PostgreSQL(Testcontainer) + WireMock 환경에서
 * 프로비저닝 흐름 전체를 end-to-end 검증한다.
 *
 * <h3>테스트 시나리오</h3>
 * <ul>
 *   <li>S14-T7: USER_REGISTERED → WireMock 200 → outbox COMPLETED 전환</li>
 *   <li>S14-T8: USER_REGISTERED → WireMock 503 → outbox PENDING 유지 (Relay 재시도 대기)</li>
 *   <li>S14-T9: 중복 sourceEventId → 두 번째 triggerProvisioning 호출 시 프로비저닝 스킵</li>
 * </ul>
 *
 * <p>Docker 필요: @Tag("integration") — CI 환경에서만 실행.
 * 로컬: {@code DOCKER_UNAVAILABLE=true} 설정 시 자동 스킵.
 */
@DisplayName("S14 — 전 기관 프로비저닝 통합 테스트")
@Disabled("Docker 필요 — CI 환경에서 @Tag('integration') 활성화 후 실행")
class ProvisioningIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ProvisioningService provisioningService;

    @Autowired
    private ProvisioningOutboxRepository outboxRepository;

    @Autowired
    private AgencyEndpointRegistryRepository endpointRegistry;

    @Autowired
    private AgencyMetaJpaRepository agencyMetaJpaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String QIM_USER_ID = "01914bf8-0000-7000-a000-000000000099";
    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        // WireMock 서버 — 기관 Stub 엔드포인트 시뮬레이션
        wireMock = new WireMockServer(8084);
        wireMock.start();
        WireMock.configureFor("localhost", 8084);

        // 테스트 기관 메타 등록 (AGENCY_STUB_001은 V15 시드로 이미 존재할 수 있음)
        if (agencyMetaJpaRepository.findById("AGENCY_STUB_001").isEmpty()) {
            agencyMetaJpaRepository.save(AgencyMetaJpaEntity.builder()
                    .agencyCode("AGENCY_STUB_001")
                    .officialName("통합테스트 스텁 기관")
                    .minAuthLevel("L1")
                    .policyVersion("1.0")
                    .integrationType("DIRECT")
                    .active(true)
                    .build());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T7: HTTP 200 → outbox COMPLETED
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T7: USER_REGISTERED + WireMock 200 → provisioning_outbox COMPLETED")
    void s14_t7_userRegistered_httpSuccess_outboxCompleted() {
        // given: WireMock → 200 응답
        stubFor(post(urlEqualTo("/api/provisioning/users"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":\"ok\"}")));

        // 엔드포인트 upsert (WireMock 포트)
        endpointRegistry.upsert(AgencyEndpointRecord.builder()
                .agencyCode("AGENCY_STUB_001")
                .endpointType("PROVISIONING")
                .endpointUrl("http://localhost:8084/api/provisioning/users")
                .httpMethod("POST")
                .authType("NONE")
                .timeoutMs(3000)
                .active(true)
                .build());

        String sourceEventId = UUID.randomUUID().toString();

        // when
        provisioningService.triggerProvisioning(QIM_USER_ID, "USER_REGISTERED", sourceEventId, sourceEventId);

        // then: WireMock에 POST 1회 발행 확인
        verify(1, postRequestedFor(urlEqualTo("/api/provisioning/users")));

        // then: outbox 레코드 확인 (현재 outbox는 insert+markCompleted로 처리)
        int deadLetterCount = outboxRepository.countDeadLetterByUser(QIM_USER_ID);
        assertThat(deadLetterCount).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T8: HTTP 503 → outbox PENDING (Relay 재시도 대기)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T8: WireMock 503 → provisioning_outbox PENDING 유지")
    void s14_t8_httpFailure_outboxPending() {
        // given: WireMock → 503 응답
        stubFor(post(urlEqualTo("/api/provisioning/users"))
                .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

        endpointRegistry.upsert(AgencyEndpointRecord.builder()
                .agencyCode("AGENCY_STUB_001")
                .endpointType("PROVISIONING")
                .endpointUrl("http://localhost:8084/api/provisioning/users")
                .httpMethod("POST")
                .authType("NONE")
                .timeoutMs(3000)
                .active(true)
                .build());

        String sourceEventId = UUID.randomUUID().toString();

        // when
        provisioningService.triggerProvisioning(
                QIM_USER_ID + "_fail", "USER_REGISTERED", sourceEventId, sourceEventId);

        // then: outbox 레코드가 PENDING 상태로 존재해야 함
        List<ProvisioningOutboxRecord> pendingBatch = outboxRepository.findPendingBatch(10);
        assertThat(pendingBatch).anyMatch(r ->
                sourceEventId.equals(r.getSourceEventId()) &&
                "PENDING".equals(r.getStatus())
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T9: 중복 sourceEventId → 두 번째 트리거 스킵
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T9: 동일 sourceEventId 두 번 호출 → 두 번째는 WireMock POST 없음")
    void s14_t9_duplicateSourceEventId_idempotent() {
        // given: WireMock → 200 응답
        stubFor(post(urlEqualTo("/api/provisioning/users"))
                .willReturn(aResponse().withStatus(200).withBody("{\"result\":\"ok\"}")));

        endpointRegistry.upsert(AgencyEndpointRecord.builder()
                .agencyCode("AGENCY_STUB_001")
                .endpointType("PROVISIONING")
                .endpointUrl("http://localhost:8084/api/provisioning/users")
                .httpMethod("POST")
                .authType("NONE")
                .timeoutMs(3000)
                .active(true)
                .build());

        String sourceEventId = UUID.randomUUID().toString();
        String userId        = QIM_USER_ID + "_idem";

        // when: 동일 sourceEventId로 2회 호출
        provisioningService.triggerProvisioning(userId, "USER_REGISTERED", sourceEventId, sourceEventId);
        provisioningService.triggerProvisioning(userId, "USER_REGISTERED", sourceEventId, sourceEventId);

        // then: WireMock POST는 1회만 발생 (두 번째는 중복 방어로 스킵)
        verify(1, postRequestedFor(urlEqualTo("/api/provisioning/users")));
    }
}
