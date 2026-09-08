package io.github.hipstermin.idem.hub.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.gateway.dto.InboundGatewayEvent;
import io.github.hipstermin.idem.hub.gateway.dto.OutboundNotifyRequest;
import io.github.hipstermin.idem.hub.infrastructure.AgencyEndpointRecord;
import io.github.hipstermin.idem.hub.infrastructure.AgencyEndpointRegistryRepository;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.provision.ProvisioningOutboxRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * AgencyGatewayService 단위 테스트 — S15-T1~T6
 *
 * <p>테스트 시나리오:
 * <ul>
 *   <li>S15-T1: 인바운드 정상 수신 → RECEIVED INSERT + PROCESSED 갱신</li>
 *   <li>S15-T2: 인바운드 중복 idempotencyKey → PROV_IDEMPOTENCY_CONFLICT(403) 예외</li>
 *   <li>S15-T3: 인바운드 DB INSERT 0 (race condition) → 예외 + Redis 키 해제</li>
 *   <li>S15-T4: 아웃바운드 발송 성공 → WEBHOOK POST + gateway_outbound_audit DELIVERED</li>
 *   <li>S15-T5: 아웃바운드 WEBHOOK 엔드포인트 없음 → PROV_AGENCY_ENDPOINT_NOT_FOUND</li>
 *   <li>S15-T6: 아웃바운드 중복 idempotencyKey → 발송 스킵 (0 반환)</li>
 * </ul>
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("Sprint 15: AgencyGatewayService 단위 테스트")
class AgencyGatewayServiceTest {

    @Mock private GatewayIdempotencyStore          idempotencyStore;
    @Mock private GatewayInboundRepository         inboundRepository;
    @Mock private GatewayOutboundRepository        outboundRepository;
    @Mock private AgencyEndpointRegistryRepository endpointRegistry;
    @Mock private AgencyMetaRepository             agencyMetaRepository;
    @Mock private ProvisioningOutboxRepository     provisioningOutboxRepository;
    @Mock private RestTemplate                     restTemplate;
    @Mock private AgencyHmacKeyStore               hmacKeyStore;

    private AgencyGatewayServiceImpl sut;
    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    private static final String AGENCY_CODE      = "AGENCY_TEST_001";
    private static final String IDEMPOTENCY_KEY  = UUID.randomUUID().toString();
    private static final String CORRELATION_ID   = UUID.randomUUID().toString();
    private static final String PAYLOAD_JSON     = "{\"agency_user_id\":\"abc123\"}";

    @BeforeEach
    void setUp() {
        sut = new AgencyGatewayServiceImpl(
                idempotencyStore, inboundRepository, outboundRepository,
                endpointRegistry, agencyMetaRepository, provisioningOutboxRepository,
                restTemplate, objectMapper, hmacKeyStore);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S15-T1: 인바운드 정상 수신 → RECEIVED INSERT + PROCESSED
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S15-T1: 인바운드 정상 수신 → INSERT(RECEIVED) + markProcessed 호출")
    void s15_t1_inbound_success() {
        // given
        when(idempotencyStore.tryAcquireInbound(IDEMPOTENCY_KEY)).thenReturn(true);
        when(inboundRepository.insert(any())).thenReturn(1);

        InboundGatewayEvent event = buildInboundEvent("AGENCY_USER_UPDATED");

        // when
        sut.receiveInbound(event);

        // then
        verify(inboundRepository).insert(argThat(r ->
                AGENCY_CODE.equals(r.getAgencyCode()) &&
                "AGENCY_USER_UPDATED".equals(r.getEventType()) &&
                IDEMPOTENCY_KEY.equals(r.getIdempotencyKey())
        ));
        verify(inboundRepository).markProcessed(IDEMPOTENCY_KEY);
        verify(inboundRepository, never()).markRejected(any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // S15-T2: 중복 idempotencyKey → PROV_IDEMPOTENCY_CONFLICT 예외
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S15-T2: Redis 중복 idempotencyKey → PROV_IDEMPOTENCY_CONFLICT 예외")
    void s15_t2_inbound_duplicate_redis() {
        // given: Redis에서 이미 처리된 키
        when(idempotencyStore.tryAcquireInbound(IDEMPOTENCY_KEY)).thenReturn(false);

        InboundGatewayEvent event = buildInboundEvent("AGENCY_USER_UPDATED");

        // when/then
        assertThatThrownBy(() -> sut.receiveInbound(event))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> {
                    PlatformException pex = (PlatformException) ex;
                    assertThat(pex.getErrorCode()).isEqualTo(PlatformErrorCode.PROV_IDEMPOTENCY_CONFLICT);
                });

        // INSERT는 호출되지 않아야 함
        verify(inboundRepository, never()).insert(any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // S15-T3: DB INSERT 0 (race condition) → 예외 + Redis 키 해제
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S15-T3: DB INSERT 0 (race condition) → PROV_IDEMPOTENCY_CONFLICT + Redis 해제")
    void s15_t3_inbound_db_race_condition() {
        // given: Redis 통과했으나 DB UNIQUE 충돌
        when(idempotencyStore.tryAcquireInbound(IDEMPOTENCY_KEY)).thenReturn(true);
        when(inboundRepository.insert(any())).thenReturn(0);

        InboundGatewayEvent event = buildInboundEvent("AGENCY_USER_UPDATED");

        // when/then
        assertThatThrownBy(() -> sut.receiveInbound(event))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> {
                    PlatformException pex = (PlatformException) ex;
                    assertThat(pex.getErrorCode()).isEqualTo(PlatformErrorCode.PROV_IDEMPOTENCY_CONFLICT);
                });

        // Redis 키 수동 해제 확인
        verify(idempotencyStore).releaseInbound(IDEMPOTENCY_KEY);
    }

    // ─────────────────────────────────────────────────────────────────────
    // S15-T4: 아웃바운드 발송 성공 → DELIVERED INSERT
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S15-T4: 아웃바운드 HTTP 발송 성공 → gateway_outbound_audit DELIVERED")
    void s15_t4_outbound_success() {
        // given
        AgencyEndpointRecord endpoint = buildWebhookEndpoint();
        when(idempotencyStore.tryAcquireOutbound(anyString(), eq(AGENCY_CODE))).thenReturn(true);
        when(endpointRegistry.findByAgencyAndType(AGENCY_CODE, "WEBHOOK"))
                .thenReturn(Optional.of(endpoint));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("OK"));

        OutboundNotifyRequest request = OutboundNotifyRequest.builder()
                .agencyCode(AGENCY_CODE)
                .eventType("NOTIFY_USER")
                .payloadJson(PAYLOAD_JSON)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .correlationId(CORRELATION_ID)
                .build();

        // when
        int httpStatus = sut.sendOutbound(request);

        // then
        assertThat(httpStatus).isEqualTo(200);
        verify(outboundRepository).insert(
                eq(AGENCY_CODE), eq("NOTIFY_USER"), eq(IDEMPOTENCY_KEY),
                eq("http://agency-test.go.kr/webhook"), eq(200),
                anyString(), eq(CORRELATION_ID), eq(true)
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // S15-T5: WEBHOOK 엔드포인트 없음 → PROV_AGENCY_ENDPOINT_NOT_FOUND
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S15-T5: WEBHOOK 엔드포인트 없음 → PROV_AGENCY_ENDPOINT_NOT_FOUND 예외")
    void s15_t5_outbound_no_endpoint() {
        // given
        when(idempotencyStore.tryAcquireOutbound(anyString(), eq(AGENCY_CODE))).thenReturn(true);
        when(endpointRegistry.findByAgencyAndType(AGENCY_CODE, "WEBHOOK"))
                .thenReturn(Optional.empty());

        OutboundNotifyRequest request = OutboundNotifyRequest.builder()
                .agencyCode(AGENCY_CODE)
                .eventType("NOTIFY_USER")
                .payloadJson(PAYLOAD_JSON)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .correlationId(CORRELATION_ID)
                .build();

        // when/then
        assertThatThrownBy(() -> sut.sendOutbound(request))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> {
                    PlatformException pex = (PlatformException) ex;
                    assertThat(pex.getErrorCode()).isEqualTo(PlatformErrorCode.PROV_AGENCY_ENDPOINT_NOT_FOUND);
                });

        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // S15-T6: 아웃바운드 중복 idempotencyKey → 발송 스킵
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("S15-T6: 아웃바운드 중복 idempotencyKey → HTTP 발송 스킵 (0 반환)")
    void s15_t6_outbound_duplicate() {
        // given: Redis에서 이미 발송된 키
        when(idempotencyStore.tryAcquireOutbound(anyString(), eq(AGENCY_CODE))).thenReturn(false);

        OutboundNotifyRequest request = OutboundNotifyRequest.builder()
                .agencyCode(AGENCY_CODE)
                .eventType("NOTIFY_USER")
                .payloadJson(PAYLOAD_JSON)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .correlationId(CORRELATION_ID)
                .build();

        // when
        int result = sut.sendOutbound(request);

        // then: 0 반환, HTTP 발송 없음
        assertThat(result).isZero();
        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
        verify(outboundRepository, never()).insert(any(), any(), any(), any(), anyInt(), any(), any(), anyBoolean());
    }

    // ─────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────────────────────────────

    private InboundGatewayEvent buildInboundEvent(String eventType) {
        return InboundGatewayEvent.builder()
                .agencyCode(AGENCY_CODE)
                .eventType(eventType)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadJson(PAYLOAD_JSON)
                .sourceIp("10.0.0.1")
                .correlationId(CORRELATION_ID)
                .build();
    }

    private AgencyEndpointRecord buildWebhookEndpoint() {
        return AgencyEndpointRecord.builder()
                .agencyCode(AGENCY_CODE)
                .endpointType("WEBHOOK")
                .endpointUrl("http://agency-test.go.kr/webhook")
                .httpMethod("POST")
                .authType("NONE")
                .timeoutMs(5000)
                .active(true)
                .build();
    }
}
