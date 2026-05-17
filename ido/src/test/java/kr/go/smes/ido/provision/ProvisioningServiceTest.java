package kr.go.smes.ido.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import kr.go.smes.ido.infrastructure.AgencyEndpointRecord;
import kr.go.smes.ido.infrastructure.AgencyEndpointRegistryRepository;
import kr.go.smes.ido.provision.dto.ProvisioningEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * ProvisioningService 단위 테스트 — S14-T1~T4
 *
 * <p>테스트 시나리오:
 * <ul>
 *   <li>S14-T1: USER_REGISTERED 이벤트 → 활성 기관 HTTP POST 발행 + outbox INSERT + markCompleted</li>
 *   <li>S14-T2: HTTP 실패 시 outbox PENDING 유지 (markCompleted 미호출)</li>
 *   <li>S14-T3: 중복 sourceEventId → 프로비저닝 스킵</li>
 *   <li>S14-T4: Feature Flag disabled → 즉시 리턴 (HTTP 미호출)</li>
 *   <li>S14-T5: 활성 엔드포인트 없으면 즉시 리턴</li>
 *   <li>S14-T6: ProvisioningOutboxRelay — COMPLETED 전환 확인</li>
 * </ul>
 *
 * <p>Sprint 17 생성자 변경 반영:
 * ProvisioningServiceImpl 생성자에 AgencyCredentialStore, mtlsRestTemplate 파라미터 추가됨.
 * 테스트 엔드포인트는 모두 authType=NONE 이므로 credentialStore 및 mtlsRestTemplate은
 * 실제 호출되지 않음 — lenient Mock으로 주입하여 스터빙 불필요.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Sprint 14: ProvisioningService 단위 테스트")
class ProvisioningServiceTest {

    @Mock private AgencyEndpointRegistryRepository endpointRegistry;
    @Mock private ProvisioningOutboxRepository      outboxRepository;
    @Mock private RestTemplate                      restTemplate;
    /**
     * Sprint 17 추가 — MTLS 전용 RestTemplate.
     * 테스트 엔드포인트는 authType=NONE 이므로 이 mock은 호출되지 않음.
     * lenient 모드(@MockitoSettings)로 불필요 스터빙 경고 없이 주입 가능.
     */
    @Mock private RestTemplate                      mtlsRestTemplate;
    /**
     * Sprint 17 추가 — 기관 자격증명 저장소.
     * authType=NONE 엔드포인트 테스트이므로 실제 호출 없음.
     * lenient 모드로 주입.
     */
    @Mock private AgencyCredentialStore             credentialStore;

    private ProvisioningServiceImpl sut;
    // JavaTimeModule 등록 — Instant 직렬화 지원
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // 테스트용 상수
    private static final String QIM_USER_ID    = "01914bf8-0000-7000-a000-000000000001";
    private static final String SOURCE_EVENT_ID = UUID.randomUUID().toString();
    private static final String CORRELATION_ID  = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        // Sprint 17 생성자 서명:
        // ProvisioningServiceImpl(
        //   AgencyEndpointRegistryRepository, ProvisioningOutboxRepository,
        //   ObjectMapper, AgencyCredentialStore,
        //   RestTemplate restTemplate, RestTemplate mtlsRestTemplate)
        sut = new ProvisioningServiceImpl(
                endpointRegistry,
                outboxRepository,
                objectMapper,
                credentialStore,
                restTemplate,
                mtlsRestTemplate
        );
        // Feature Flag: 기본값 true (enabled), dry-run: false (실제 발행 테스트)
        ReflectionTestUtils.setField(sut, "provisioningEnabled", true);
        ReflectionTestUtils.setField(sut, "provisioningDryRun",  false);
        ReflectionTestUtils.setField(sut, "globalTimeoutMs", 0);
        ReflectionTestUtils.setField(sut, "maxParallelAgencies", 100);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T1: USER_REGISTERED → 성공 HTTP → outbox INSERT + markCompleted
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T1: USER_REGISTERED — 활성 기관 HTTP POST 성공 → outbox INSERT + markCompleted")
    void s14_t1_userRegistered_success() {
        // given
        AgencyEndpointRecord endpoint = buildEndpoint("AGENCY_A", "http://agency-a.test/provisioning");
        String outboxId = "test-outbox-id-001";
        when(endpointRegistry.findAllActiveByType("PROVISIONING")).thenReturn(List.of(endpoint));
        when(outboxRepository.countBySourceEventId(SOURCE_EVENT_ID)).thenReturn(0);
        when(outboxRepository.insert(any())).thenReturn(1);
        when(outboxRepository.findIdByIdempotencyKeyAndAgency(anyString(), anyString())).thenReturn(outboxId);
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("OK"));

        // when
        sut.triggerProvisioning(QIM_USER_ID, "USER_REGISTERED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then
        verify(endpointRegistry).findAllActiveByType("PROVISIONING");
        verify(outboxRepository).insert(argThat(r ->
                "AGENCY_A".equals(r.getAgencyCode()) &&
                "USER_REGISTERED".equals(r.getEventType()) &&
                SOURCE_EVENT_ID.equals(r.getSourceEventId())
        ));
        verify(restTemplate).postForEntity(
                eq("http://agency-a.test/provisioning"), any(HttpEntity.class), eq(String.class));
        // HTTP 성공 시 markCompleted 호출 확인
        verify(outboxRepository).markCompleted(outboxId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T2: HTTP 실패 → outbox PENDING 유지 (markCompleted 미호출)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T2: HTTP 실패(503) → markCompleted 호출 없음 (PENDING으로 Relay 재시도 대기)")
    void s14_t2_httpFailure_remainsPending() {
        // given
        AgencyEndpointRecord endpoint = buildEndpoint("AGENCY_B", "http://agency-b.test/provisioning");
        when(endpointRegistry.findAllActiveByType("PROVISIONING")).thenReturn(List.of(endpoint));
        when(outboxRepository.countBySourceEventId(SOURCE_EVENT_ID)).thenReturn(0);
        // HTTP 503 응답 → 실패 분기
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("error"));
        // outbox insert + findId 는 실제 호출될 수도 있으므로 lenient 처리
        lenient().when(outboxRepository.insert(any())).thenReturn(1);
        lenient().when(outboxRepository.findIdByIdempotencyKeyAndAgency(anyString(), anyString()))
                .thenReturn("test-outbox-id-002");

        // when
        sut.triggerProvisioning(QIM_USER_ID, "USER_REGISTERED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then: markCompleted 는 절대 호출되지 않아야 함 — PENDING 상태로 Relay 재시도 대기
        verify(outboxRepository, never()).markCompleted(anyString());
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T3: 중복 sourceEventId → 프로비저닝 스킵
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T3: 중복 sourceEventId → 프로비저닝 스킵 (HTTP 미호출)")
    void s14_t3_duplicateSourceEventId_skip() {
        // given: 동일 sourceEventId로 이미 레코드 존재
        when(outboxRepository.countBySourceEventId(SOURCE_EVENT_ID)).thenReturn(3);

        // when
        sut.triggerProvisioning(QIM_USER_ID, "USER_REGISTERED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then: 엔드포인트 조회 + HTTP 발행 모두 일어나지 않아야 함
        verify(endpointRegistry, never()).findAllActiveByType(anyString());
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
        verify(outboxRepository, never()).insert(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T4: Feature Flag disabled → 즉시 리턴
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T4: Feature Flag disabled → 즉시 리턴 (아무것도 호출 없음)")
    void s14_t4_featureFlagDisabled_skip() {
        // given: provisioningEnabled = false
        ReflectionTestUtils.setField(sut, "provisioningEnabled", false);

        // when
        sut.triggerProvisioning(QIM_USER_ID, "USER_REGISTERED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then: 아무 인프라도 호출되지 않아야 함
        verifyNoInteractions(endpointRegistry);
        verifyNoInteractions(outboxRepository);
        verifyNoInteractions(restTemplate);
        verifyNoInteractions(mtlsRestTemplate);
        verifyNoInteractions(credentialStore);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T5: 활성 엔드포인트 없으면 즉시 리턴
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T5: 활성 PROVISIONING 엔드포인트 없음 → outbox INSERT 없이 리턴")
    void s14_t5_noActiveEndpoints_skip() {
        // given
        when(outboxRepository.countBySourceEventId(SOURCE_EVENT_ID)).thenReturn(0);
        when(endpointRegistry.findAllActiveByType("PROVISIONING")).thenReturn(List.of());

        // when
        sut.triggerProvisioning(QIM_USER_ID, "BIZ_CONVERTED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then
        verify(outboxRepository, never()).insert(any());
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T6: BIZ_CONVERTED 이벤트 타입 페이로드 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T6: BIZ_CONVERTED → outbox event_type=BIZ_CONVERTED 기록")
    void s14_t6_bizConverted_eventType() {
        // given
        AgencyEndpointRecord endpoint = buildEndpoint("AGENCY_C", "http://agency-c.test/provisioning");
        when(endpointRegistry.findAllActiveByType("PROVISIONING")).thenReturn(List.of(endpoint));
        when(outboxRepository.countBySourceEventId(SOURCE_EVENT_ID)).thenReturn(0);
        when(outboxRepository.insert(any())).thenReturn(1);
        when(outboxRepository.findIdByIdempotencyKeyAndAgency(anyString(), anyString()))
                .thenReturn("test-outbox-id-006");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("OK"));

        // when
        sut.triggerProvisioning(QIM_USER_ID, "BIZ_CONVERTED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then: outbox insert 시 event_type = BIZ_CONVERTED
        ArgumentCaptor<ProvisioningOutboxRecord> captor =
                ArgumentCaptor.forClass(ProvisioningOutboxRecord.class);
        verify(outboxRepository).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("BIZ_CONVERTED");
        assertThat(captor.getValue().getQimUserId()).isEqualTo(QIM_USER_ID);
        assertThat(captor.getValue().getAgencyCode()).isEqualTo("AGENCY_C");
        assertThat(captor.getValue().getSourceEventId()).isEqualTo(SOURCE_EVENT_ID);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S14-T7: dry-run 모드 → HTTP 미호출
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S14-T7: dry-run 모드 활성화 → HTTP 미호출, outbox INSERT 없음")
    void s14_t7_dryRunMode_noHttpCall() {
        // given: dry-run = true (운영 전 관찰 기간)
        ReflectionTestUtils.setField(sut, "provisioningDryRun", true);
        AgencyEndpointRecord endpoint = buildEndpoint("AGENCY_D", "http://agency-d.test/provisioning");
        when(outboxRepository.countBySourceEventId(SOURCE_EVENT_ID)).thenReturn(0);
        when(endpointRegistry.findAllActiveByType("PROVISIONING")).thenReturn(List.of(endpoint));

        // when
        sut.triggerProvisioning(QIM_USER_ID, "USER_REGISTERED", SOURCE_EVENT_ID, CORRELATION_ID);

        // then: dry-run 시 실제 HTTP 호출 없어야 함
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
        verify(mtlsRestTemplate, never()).postForEntity(anyString(), any(), any());
        verify(outboxRepository, never()).insert(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    private AgencyEndpointRecord buildEndpoint(String agencyCode, String url) {
        return AgencyEndpointRecord.builder()
                .agencyCode(agencyCode)
                .endpointType("PROVISIONING")
                .endpointUrl(url)
                .httpMethod("POST")
                .authType("NONE")
                .timeoutMs(3000)
                .active(true)
                .build();
    }
}
