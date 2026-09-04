package kr.go.smes.sdk.agency;

import kr.go.smes.sdk.agency.exception.AgencyHttpException;
import kr.go.smes.sdk.agency.exception.AgencySdkException;
import kr.go.smes.sdk.agency.http.AgencyHttpAdapter;
import kr.go.smes.sdk.agency.http.ApacheHttpAgencyAdapter;
import kr.go.smes.sdk.agency.http.OkHttpAgencyAdapter;
import okhttp3.OkHttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;
import kr.go.smes.sdk.agency.model.GatewayResponse;
import kr.go.smes.sdk.agency.model.InboundEvent;
import kr.go.smes.sdk.agency.model.OutboundNotifyRequest;
import kr.go.smes.sdk.agency.security.HmacSigner;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgencyGatewayClient 단위/통합 테스트 S16-T1~T8
 *
 * <p>MockWebServer(OkHttp)를 사용하여 실제 HTTP 서버 없이 검증.
 * JDK 버전 프리 SDK 특성상 모든 어댑터가 동일한 계약을 만족하는지 확인.
 */
@ExtendWith(MockitoExtension.class)
class AgencyGatewayClientTest {

    // ── 공통 테스트 상수 ──────────────────────────────────────────────────────
    private static final String AGENCY_CODE    = "MOIS";
    private static final String API_KEY        = "test-api-key-12345";
    private static final String IDEMPOTENCY_KEY = "test-idempotency-key-001";
    private static final String HMAC_SECRET    = "super-secret-key-for-testing";

    @Mock
    private AgencyHttpAdapter mockAdapter;

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T1: 정상 인바운드 이벤트 전송 → 202 Accepted
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T1: sendInbound 정상 호출 → HTTP 202 Accepted 반환")
    void s16T1_sendInbound_success_returns202() {
        // given
        GatewayResponse expectedResponse = GatewayResponse.of(202, "{\"status\":\"accepted\"}",
                "corr-001", "req-001");
        when(mockAdapter.execute(eq("POST"), contains("/inbound/event"), any(), any()))
                .thenReturn(expectedResponse);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(mockAdapter)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadJson("{\"action\":\"sync\"}")
                .correlationId("corr-001")
                .build();

        // when
        GatewayResponse response = client.sendInbound(event);

        // then
        assertThat(response.getHttpStatus()).isEqualTo(202);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCorrelationId()).isEqualTo("corr-001");
        verify(mockAdapter).execute(eq("POST"),
                eq("https://onepass.go.kr/api/v1/agency/gateway/inbound/event"),
                any(), any());
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T2: X-Agency-Key, X-Idempotency-Key 헤더 정확히 설정되는지 검증
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T2: 요청 헤더에 X-Agency-Key, X-Idempotency-Key, X-Agency-Code, X-Event-Type 포함 검증")
    void s16T2_headers_containRequiredKeys() {
        // given
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        when(mockAdapter.execute(any(), any(), headersCaptor.capture(), any()))
                .thenReturn(GatewayResponse.of(202, "{}", null, null));

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(mockAdapter)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("BIZ_CONVERTED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when
        client.sendInbound(event);

        // then
        Map<String, String> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders).containsEntry("X-Agency-Key", API_KEY);
        assertThat(capturedHeaders).containsEntry("X-Agency-Code", AGENCY_CODE);
        assertThat(capturedHeaders).containsEntry("X-Idempotency-Key", IDEMPOTENCY_KEY);
        assertThat(capturedHeaders).containsKey("Content-Type");
        assertThat(capturedHeaders.get("Content-Type")).contains("application/json");
        // X-Event-Type: 서버 AgencyGatewayController.receiveInbound()가 이 헤더로 eventType 라우팅
        assertThat(capturedHeaders).containsEntry("X-Event-Type", "BIZ_CONVERTED");
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T3: 409 Conflict → AgencyHttpException 발생 + isIdempotencyConflict 동작 확인
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T3: 서버 409 응답 → AgencyHttpException 발생, isClientError=true")
    void s16T3_server409_throwsAgencyHttpException() {
        // given
        when(mockAdapter.execute(any(), any(), any(), any()))
                .thenThrow(new AgencyHttpException(409, "{\"error\":\"DUPLICATE\"}"));

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .httpAdapter(mockAdapter)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when & then
        assertThatThrownBy(() -> client.sendInbound(event))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException httpEx = (AgencyHttpException) ex;
                    assertThat(httpEx.getHttpStatus()).isEqualTo(409);
                    assertThat(httpEx.isClientError()).isTrue();
                    assertThat(httpEx.isServerError()).isFalse();
                    assertThat(httpEx.getErrorCode()).isEqualTo("SDK_HTTP_ERROR");
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T4: HMAC-SHA256 서명 활성화 시 X-Internal-Sig 헤더 포함
    //         (서버 HmacSignatureFilter 페이로드: {agencyCode}:{idempotencyKey}:{epochSeconds})
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T4: HMAC 서명 활성화 → X-Internal-Sig 헤더 포함, X-Timestamp는 미포함 (서버 미사용)")
    void s16T4_hmacSigningEnabled_includesSignatureHeaders() {
        // given
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        when(mockAdapter.execute(any(), any(), headersCaptor.capture(), any()))
                .thenReturn(GatewayResponse.of(202, "{}", null, null));

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(mockAdapter)
                .hmacSecret(HMAC_SECRET)
                .signRequests(true)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when
        client.sendInbound(event);

        // then
        Map<String, String> capturedHeaders = headersCaptor.getValue();
        // X-Internal-Sig: HMAC-SHA256 서명값 (64자 소문자 HEX)
        assertThat(capturedHeaders).containsKey("X-Internal-Sig");
        assertThat(capturedHeaders.get("X-Internal-Sig")).hasSize(64)
                .matches("[0-9a-f]{64}");
        // X-Timestamp: 서버 HmacSignatureFilter가 사용하지 않으므로 포함하지 않음
        assertThat(capturedHeaders).doesNotContainKey("X-Timestamp");
        // 서명 페이로드 검증: {AGENCY_CODE}:{IDEMPOTENCY_KEY}:{epochSeconds}
        HmacSigner signer = new HmacSigner(HMAC_SECRET);
        long epochSeconds = System.currentTimeMillis() / 1000L;
        // ±2초 내의 epochSeconds 후보 중 하나가 일치해야 함 (시계 지연 허용)
        String capturedSig = capturedHeaders.get("X-Internal-Sig");
        boolean verified = false;
        for (long delta = -2; delta <= 2; delta++) {
            String expected = signer.sign(AGENCY_CODE, IDEMPOTENCY_KEY, epochSeconds + delta);
            if (signer.verifySignature(capturedSig, expected)) {
                verified = true;
                break;
            }
        }
        assertThat(verified).as("HMAC 서명이 서버 알고리즘({agencyCode}:{idempotencyKey}:{epochSeconds})과 일치해야 함").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T5: idempotencyKey 미지정 시 UUID v4 자동 생성
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T5: idempotencyKey 미지정 → UUID v4 자동 생성하여 헤더 포함 (GAP-4 반영)")
    void s16T5_noIdempotencyKey_autoGeneratesUuid() {
        // given
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        when(mockAdapter.execute(any(), any(), headersCaptor.capture(), any()))
                .thenReturn(GatewayResponse.of(202, "{}", null, null));

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .httpAdapter(mockAdapter)
                .build();

        // GAP-4: idempotencyKey 완전 생략 (기존에는 IllegalArgumentException 발생했지만 이제 UUID 자동 생성)
        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                // .idempotencyKey() 생략 — SDK가 UUID v4 자동 생성
                .build();

        // when
        client.sendInbound(event);

        // then — X-Idempotency-Key 헤더가 UUID v4 형식인지 확인
        Map<String, String> captured = headersCaptor.getValue();
        assertThat(captured).containsKey("X-Idempotency-Key");
        String autoKey = captured.get("X-Idempotency-Key");
        assertThat(autoKey).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

        // IdempotencyKeyGenerator 직접 검증
        String generatedKey = IdempotencyKeyGenerator.generate();
        assertThat(generatedKey).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T6: triggerOutbound 정상 호출 → 200 OK
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T6: triggerOutbound 정상 호출 → PATCH /outbound/notify, HTTP 200 OK")
    void s16T6_triggerOutbound_success_returns200() {
        // given
        GatewayResponse expectedResponse = GatewayResponse.of(200, "{\"delivered\":true}", null, null);
        ArgumentCaptor<String> methodCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> urlCaptor    = ArgumentCaptor.forClass(String.class);
        when(mockAdapter.execute(methodCaptor.capture(), urlCaptor.capture(), any(), any()))
                .thenReturn(expectedResponse);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(mockAdapter)
                .build();

        OutboundNotifyRequest request = OutboundNotifyRequest.builder()
                .agencyCode(AGENCY_CODE)
                .eventType("USER_PROVISIONED")
                .payload("{\"status\":\"ok\"}")
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when
        GatewayResponse response = client.triggerOutbound(request);

        // then
        assertThat(response.getHttpStatus()).isEqualTo(200);
        assertThat(response.isSuccess()).isTrue();
        assertThat(methodCaptor.getValue()).isEqualTo("PATCH");
        assertThat(urlCaptor.getValue()).endsWith("/outbound/notify");
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T7: getStatus 호출 → GET /status/{agencyCode}
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T7: getStatus 호출 → GET /status/{agencyCode} 경로 검증")
    void s16T7_getStatus_callsCorrectPath() {
        // given
        String statusJson = "{\"agencyCode\":\"MOIS\",\"active\":true,\"pendingProvisioning\":0}";
        ArgumentCaptor<String> urlCaptor    = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> methodCaptor = ArgumentCaptor.forClass(String.class);
        when(mockAdapter.execute(methodCaptor.capture(), urlCaptor.capture(), any(), isNull()))
                .thenReturn(GatewayResponse.of(200, statusJson, null, null));

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("https://onepass.go.kr")
                .apiKey(API_KEY)
                .httpAdapter(mockAdapter)
                .build();

        // when
        GatewayResponse response = client.getStatus("MOIS");

        // then
        assertThat(response.isSuccess()).isTrue();
        assertThat(methodCaptor.getValue()).isEqualTo("GET");
        assertThat(urlCaptor.getValue())
                .isEqualTo("https://onepass.go.kr/api/v1/agency/gateway/status/MOIS");
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T8: MockWebServer 실제 HTTP 왕복 테스트 (HttpUrlConnectionAdapter)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T8: HttpUrlConnectionAdapter로 MockWebServer 실제 HTTP 왕복 — POST 202 확인")
    void s16T8_httpUrlConnectionAdapter_realHttpRoundTrip() throws InterruptedException {
        // given — MockWebServer에 202 응답 등록
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Correlation-Id", "mock-corr-001")
                .setBody("{\"status\":\"accepted\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        // HttpUrlConnectionAdapter 사용 (JDK 내장, zero-dep)
        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .connectTimeoutMs(3_000)
                .readTimeoutMs(5_000)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadJson("{\"action\":\"test\"}")
                .correlationId("test-corr")
                .build();

        // when
        GatewayResponse response = client.sendInbound(event);

        // then — 응답 검증
        assertThat(response.getHttpStatus()).isEqualTo(202);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getBody()).contains("accepted");
        assertThat(response.getCorrelationId()).isEqualTo("mock-corr-001");

        // 서버 수신 요청 검증
        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/api/v1/agency/gateway/inbound/event");
        assertThat(recorded.getHeader("X-Agency-Key")).isEqualTo(API_KEY);
        assertThat(recorded.getHeader("X-Idempotency-Key")).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(recorded.getHeader("X-Agency-Code")).isEqualTo(AGENCY_CODE);
        // X-Event-Type: 서버 eventType 라우팅 헤더 (GAP-3 수정 검증)
        assertThat(recorded.getHeader("X-Event-Type")).isEqualTo("USER_REGISTERED");
        // X-Timestamp: 서버가 사용하지 않으므로 전송하지 않음 (GAP-1 수정 검증)
        assertThat(recorded.getHeader("X-Timestamp")).isNull();
        assertThat(recorded.getBody().readUtf8()).contains("USER_REGISTERED");
    }

    // ════════════════════════════════════════════════════════════════════════
    // HmacSigner 단독 테스트 (S16-T4 보조)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("HmacSigner: sign(agencyCode, idempotencyKey, epochSeconds) + verifySignature 정확성 검증")
    void hmacSigner_signAndVerify_consistent() {
        // given
        HmacSigner signer = new HmacSigner(HMAC_SECRET);
        long epochSeconds = System.currentTimeMillis() / 1000L;

        // when — 서버 HmacSignatureFilter와 동일한 알고리즘
        // 페이로드: "{agencyCode}:{idempotencyKey}:{epochSeconds}"
        String sig1 = signer.sign(AGENCY_CODE, IDEMPOTENCY_KEY, epochSeconds);
        String sig2 = signer.sign(AGENCY_CODE, IDEMPOTENCY_KEY, epochSeconds);
        // 다른 입력 → 다른 서명
        String sig3 = signer.sign("OTHER_CODE", IDEMPOTENCY_KEY, epochSeconds);
        String sig4 = signer.sign(AGENCY_CODE, "other-key", epochSeconds);

        // then — 동일 입력은 동일 서명
        assertThat(signer.verifySignature(sig1, sig2)).isTrue();
        // 입력 달라지면 서명 달라짐
        assertThat(signer.verifySignature(sig1, sig3)).isFalse();
        assertThat(signer.verifySignature(sig1, sig4)).isFalse();
        // 64자 소문자 HEX
        assertThat(sig1).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("HmacSigner: 서버 알고리즘과 동일한 서명 생성 검증 (서버 HmacSignatureFilter 재현)")
    void hmacSigner_matchesServerAlgorithm() throws Exception {
        // 서버 HmacSignatureFilter.computeHmac()를 직접 재현하여 SDK와 비교
        // payload = "{agencyCode}:{idempotencyKey}:{epochSeconds}"
        long   epochSeconds   = 1715641234L;   // 고정값으로 재현성 보장
        String secret         = HMAC_SECRET;

        // SDK 서명 생성
        HmacSigner signer = new HmacSigner(secret);
        String sdkSig     = signer.sign(AGENCY_CODE, IDEMPOTENCY_KEY, epochSeconds);

        // 서버 결과 직접 계산 (서버 코드와 동일 로직)
        String payload = AGENCY_CODE + ":" + IDEMPOTENCY_KEY + ":" + epochSeconds;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.Charset.forName("UTF-8")), "HmacSHA256"));
        byte[] rawHmac = mac.doFinal(payload.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        for (byte b : rawHmac) sb.append(String.format("%02x", b & 0xFF));
        String serverExpectedSig = sb.toString();

        // SDK가 생성한 서명이 서버가 검증할 값과 정확히 일치해야 함
        assertThat(sdkSig).isEqualTo(serverExpectedSig);
    }

    @Test
    @DisplayName("Builder: baseUrl 미설정 시 AgencySdkException 발생")
    void builder_missingBaseUrl_throwsSdkException() {
        assertThatThrownBy(() -> AgencyGatewayClient.builder()
                .apiKey(API_KEY)
                .build())
                .isInstanceOf(AgencySdkException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    @DisplayName("Builder: apiKey 미설정 시 AgencySdkException 발생")
    void builder_missingApiKey_throwsSdkException() {
        assertThatThrownBy(() -> AgencyGatewayClient.builder()
                .baseUrl("http://localhost:8083")
                .build())
                .isInstanceOf(AgencySdkException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    @DisplayName("Builder: signRequests=true + hmacSecret 미설정 시 AgencySdkException 발생")
    void builder_signRequestsWithoutSecret_throwsSdkException() {
        assertThatThrownBy(() -> AgencyGatewayClient.builder()
                .baseUrl("http://localhost:8083")
                .apiKey(API_KEY)
                .signRequests(true)
                .build())
                .isInstanceOf(AgencySdkException.class)
                .hasMessageContaining("hmacSecret");
    }

    @Test
    @DisplayName("InboundEvent: 필수 필드(eventType, agencyCode) 미설정 시 IllegalArgumentException 발생 — idempotencyKey는 GAP-4로 선택")
    void inboundEvent_missingRequiredField_throwsException() {
        // eventType 없음 → 필수
        assertThatThrownBy(() -> InboundEvent.builder()
                .agencyCode(AGENCY_CODE)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        // agencyCode 없음 → 필수
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agencyCode");

        // GAP-4: idempotencyKey 생략 → UUID 자동 생성, 예외 없음
        assertThatCode(() -> InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .build())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("InboundEvent.toJsonString(): payloadJson 올바르게 embed")
    void inboundEvent_toJsonString_embedsPayload() {
        // given
        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadJson("{\"foo\":\"bar\"}")
                .correlationId("corr-xyz")
                .build();

        // when
        String json = event.toJsonString();

        // then
        assertThat(json).contains("\"event_type\":\"USER_REGISTERED\"");
        assertThat(json).contains("\"agency_code\":\"MOIS\"");
        assertThat(json).contains("\"idempotency_key\":\"test-idempotency-key-001\"");
        assertThat(json).contains("\"payload\":{\"foo\":\"bar\"}");   // embed (not string)
        assertThat(json).contains("\"correlation_id\":\"corr-xyz\"");
    }

    @Test
    @DisplayName("IdempotencyKeyGenerator: UUID v4 형식 + 접두사 포함 + 시퀀스 모두 검증")
    void idempotencyKeyGenerator_allStrategies() {
        String uuid       = IdempotencyKeyGenerator.generate();
        String prefixed   = IdempotencyKeyGenerator.generateWithPrefix("AGENCY_STUB_001");
        String sequential = IdempotencyKeyGenerator.generateSequential("AGENCY_STUB_001");

        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(prefixed).startsWith("AGENCY_STUB_001-");
        assertThat(sequential).startsWith("AGENCY_STUB_001-");
        assertThat(IdempotencyKeyGenerator.constantTimeEquals(uuid, uuid)).isTrue();
        assertThat(IdempotencyKeyGenerator.constantTimeEquals(uuid, prefixed)).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════════
    // P0 검증: X-Agency-Key 헤더명 일치 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P0: SDK가 X-Agency-Key 헤더를 전송하는지 확인 — HandoffAgencyKeyInterceptor 일치")
    void p0_agencyKeyHeader_isXAgencyKey_notXApiKey() {
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        when(mockAdapter.execute(any(), any(), headersCaptor.capture(), any()))
                .thenReturn(GatewayResponse.of(202, "{}", null, null));

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl("http://localhost:8083")
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(mockAdapter)
                .build();

        client.sendInbound(InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build());

        Map<String, String> headers = headersCaptor.getValue();
        assertThat(headers).containsKey("X-Agency-Key");         // 서버 인터셉터가 기대하는 헤더
        assertThat(headers).doesNotContainKey("X-Api-Key");       // 올린 이름은 없어야 함
        assertThat(headers).containsEntry("X-Agency-Key", API_KEY);
    }

    // ════════════════════════════════════════════════════════════════════════
    // P1: OkHttpAgencyAdapter MockWebServer 실제 HTTP 왕복 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-OkHttp: OkHttpAgencyAdapter MockWebServer POST → 202, X-Agency-Key 헤더 확인")
    void p1_okHttpAdapter_post_202() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("X-Correlation-Id", "okhttp-corr-001")
                .setBody("{\"status\":\"accepted\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(new OkHttpAgencyAdapter(new OkHttpClient()))
                .build();

        GatewayResponse response = client.sendInbound(InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadJson("{\"source\":\"okhttp-test\"}")
                .build());

        assertThat(response.getHttpStatus()).isEqualTo(202);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCorrelationId()).isEqualTo("okhttp-corr-001");

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("X-Agency-Key")).isEqualTo(API_KEY);  // P0 일치점 확인
        assertThat(recorded.getHeader("X-Agency-Code")).isEqualTo(AGENCY_CODE);
        assertThat(recorded.getHeader("X-Idempotency-Key")).isEqualTo(IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("P1-OkHttp: OkHttpAgencyAdapter PATCH outbound → 200")
    void p1_okHttpAdapter_patch_200() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"delivered\":true}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(new OkHttpAgencyAdapter(new OkHttpClient()))
                .build();

        GatewayResponse response = client.triggerOutbound(OutboundNotifyRequest.builder()
                .agencyCode(AGENCY_CODE)
                .eventType("USER_PROVISIONED")
                .payload("{\"status\":\"ok\"}")
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build());

        assertThat(response.isSuccess()).isTrue();
        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded.getMethod()).isEqualTo("PATCH");
        assertThat(recorded.getPath()).endsWith("/outbound/notify");
    }

    @Test
    @DisplayName("P1-OkHttp: OkHttpAgencyAdapter 409 → AgencyHttpException isClientError")
    void p1_okHttpAdapter_409_throwsException() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(409)
                .setBody("{\"error\":\"DUPLICATE\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .httpAdapter(new OkHttpAgencyAdapter(new OkHttpClient()))
                .build();

        assertThatThrownBy(() -> client.sendInbound(InboundEvent.builder()
                .eventType("EVT").agencyCode(AGENCY_CODE).idempotencyKey(IDEMPOTENCY_KEY).build()))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException e = (AgencyHttpException) ex;
                    assertThat(e.getHttpStatus()).isEqualTo(409);
                    assertThat(e.isClientError()).isTrue();
                });
    }

    // ════════════════════════════════════════════════════════════════════════
    // P1: ApacheHttpAgencyAdapter MockWebServer 실제 HTTP 왕복 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1-Apache: ApacheHttpAgencyAdapter POST → 202, X-Agency-Key 헤더 확인")
    void p1_apacheAdapter_post_202() throws InterruptedException, IOException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("X-Correlation-Id", "apache-corr-001")
                .setBody("{\"status\":\"accepted\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
        ApacheHttpAgencyAdapter adapter = new ApacheHttpAgencyAdapter(HttpClients.createDefault());

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(adapter)
                .build();

        GatewayResponse response = client.sendInbound(InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .payloadJson("{\"source\":\"apache-test\"}")
                .build());
        adapter.close();

        assertThat(response.getHttpStatus()).isEqualTo(202);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCorrelationId()).isEqualTo("apache-corr-001");

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getHeader("X-Agency-Key")).isEqualTo(API_KEY);  // P0 일치점 확인
        assertThat(recorded.getHeader("X-Agency-Code")).isEqualTo(AGENCY_CODE);
    }

    @Test
    @DisplayName("P1-Apache: ApacheHttpAgencyAdapter GET getStatus → 200, 경로 확인")
    void p1_apacheAdapter_getStatus_200() throws InterruptedException, IOException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"agencyCode\":\"AGENCY_STUB_001\",\"active\":true}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
        ApacheHttpAgencyAdapter adapter = new ApacheHttpAgencyAdapter(HttpClients.createDefault());

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .httpAdapter(adapter)
                .build();

        GatewayResponse response = client.getStatus("AGENCY_STUB_001");
        adapter.close();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getBody()).contains("AGENCY_STUB_001");

        RecordedRequest recorded = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).endsWith("/status/AGENCY_STUB_001");
    }

    @Test
    @DisplayName("P1-Apache: ApacheHttpAgencyAdapter 503 → AgencyHttpException isServerError")
    void p1_apacheAdapter_503_throwsException() throws IOException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"SERVICE_UNAVAILABLE\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        // 재시도/타임아웃 비활성화 — 503에서 재시도하지 않도록
        RequestConfig noRetryConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(5))
                .build();
        ApacheHttpAgencyAdapter adapter = new ApacheHttpAgencyAdapter(
                HttpClientBuilder.create()
                        .setDefaultRequestConfig(noRetryConfig)
                        .disableRedirectHandling()
                        .disableAutomaticRetries()
                        .build()
        );

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .httpAdapter(adapter)
                .build();

        assertThatThrownBy(() -> client.sendInbound(InboundEvent.builder()
                .eventType("EVT").agencyCode(AGENCY_CODE).idempotencyKey(IDEMPOTENCY_KEY).build()))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException e = (AgencyHttpException) ex;
                    assertThat(e.getHttpStatus()).isEqualTo(503);
                    assertThat(e.isServerError()).isTrue();
                    assertThat(e.isClientError()).isFalse();
                });

        adapter.close();
    }

    // ════════════════════════════════════════════════════════════════════════
    // P2: payloadJson 유효성 검증 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P2: payloadJson 유효한 JSON 객체/배열 통과")
    void p2_payloadJson_valid_passes() {
        assertThatNoException().isThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG").idempotencyKey("k1")
                .payloadJson("{\"foo\":\"bar\"}")
                .build());
        assertThatNoException().isThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG").idempotencyKey("k2")
                .payloadJson("[{\"id\":1},{\"id\":2}]")
                .build());
        // null → {} 기본값
        assertThatNoException().isThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG").idempotencyKey("k3")
                .build());
    }

    @Test
    @DisplayName("P2: payloadJson 비정상 JSON 입력 → IllegalArgumentException")
    void p2_payloadJson_invalid_throwsException() {
        // 일반 문자열 (객체 아님)
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG").idempotencyKey("k4")
                .payloadJson("not-a-json")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadJson");
        // bracket 불균형
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG").idempotencyKey("k5")
                .payloadJson("{\"foo\":\"bar\"")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
        // OutboundNotifyRequest도 동일
        assertThatThrownBy(() -> OutboundNotifyRequest.builder()
                .agencyCode("AG").eventType("EVT")
                .payload("plain string")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadJson");
    }

    // ════════════════════════════════════════════════════════════════════════
    // GAP-2: GatewayResponse.getBodyField() 파싱 헬퍼 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GAP-2: getBodyField() — 문자열/숫자/불리언 필드 추출, 없는 필드는 null")
    void gap2_gatewayResponse_getBodyField_parsesScalarFields() {
        // given — 서버 응답과 유사한 JSON 구조
        GatewayResponse resp = GatewayResponse.of(202,
                "{\"status\":\"accepted\",\"requestId\":\"abc-123\",\"code\":202,\"active\":true}",
                "corr-001", "req-001");

        // when & then — 문자열 값
        assertThat(resp.getBodyField("status")).isEqualTo("accepted");
        assertThat(resp.getBodyField("requestId")).isEqualTo("abc-123");

        // 숫자 값 (문자열로 반환)
        assertThat(resp.getBodyField("code")).isEqualTo("202");

        // 불리언 값
        assertThat(resp.getBodyField("active")).isEqualTo("true");

        // 존재하지 않는 필드 → null
        assertThat(resp.getBodyField("missing")).isNull();
        assertThat(resp.getBodyField(null)).isNull();
    }

    @Test
    @DisplayName("GAP-2: getBodyField() — body가 null/빈값이면 null 반환")
    void gap2_getBodyField_nullOrEmptyBody_returnsNull() {
        GatewayResponse nullBody  = GatewayResponse.of(200, null, null, null);
        GatewayResponse emptyBody = GatewayResponse.of(200, "",   null, null);
        GatewayResponse arrayBody = GatewayResponse.of(200, "[1,2,3]", null, null);

        assertThat(nullBody.getBodyField("status")).isNull();
        assertThat(emptyBody.getBodyField("status")).isNull();
        // 배열 응답은 객체 파싱 불가 → null
        assertThat(arrayBody.getBodyField("status")).isNull();
    }

    @Test
    @DisplayName("GAP-2: getBodyField() — 이스케이프 포함 문자열 값 정상 복원")
    void gap2_getBodyField_unescapesStringValue() {
        GatewayResponse resp = GatewayResponse.of(200,
                "{\"message\":\"hello\\nworld\",\"path\":\"/api/v1\"}",
                null, null);

        assertThat(resp.getBodyField("message")).isEqualTo("hello\nworld");
        assertThat(resp.getBodyField("path")).isEqualTo("/api/v1");
    }

    // ════════════════════════════════════════════════════════════════════════
    // GAP-3: HttpUrlConnectionAdapter 재시도 로직 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GAP-3: 5xx 응답 → 재시도 후 성공 (MockWebServer 실제 HTTP)")
    void gap3_retryOn5xx_succeedsOnSecondAttempt() throws Exception {
        // given — 첫 번째 503, 두 번째 202
        mockWebServer.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(202)
                .setHeader("X-Correlation-Id", "retry-corr-001")
                .setBody("{\"status\":\"accepted\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        // baseDelayMs=1ms로 테스트 속도 확보
        kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter adapter =
                new kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter(3_000, 5_000, 3, 1L);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(adapter)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when
        GatewayResponse response = client.sendInbound(event);

        // then — 재시도 후 202 성공
        assertThat(response.getHttpStatus()).isEqualTo(202);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCorrelationId()).isEqualTo("retry-corr-001");

        // 총 2번 요청이 왔는지 확인
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("GAP-3: 5xx 응답 maxRetries 소진 → AgencyHttpException 발생 (마지막 5xx 상태코드)")
    void gap3_retryExhausted_throwsLastException() {
        // given — 모든 응답 502
        mockWebServer.enqueue(new MockResponse().setResponseCode(502).setBody("{\"error\":\"bad-gateway\"}"));
        mockWebServer.enqueue(new MockResponse().setResponseCode(502).setBody("{\"error\":\"bad-gateway\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        // maxRetries=1 (총 2회 시도), baseDelay=1ms
        kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter adapter =
                new kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter(3_000, 5_000, 1, 1L);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(adapter)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when & then — 재시도 소진 후 AgencyHttpException 발생
        assertThatThrownBy(() -> client.sendInbound(event))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException httpEx = (AgencyHttpException) ex;
                    assertThat(httpEx.getHttpStatus()).isEqualTo(502);
                    assertThat(httpEx.isServerError()).isTrue();
                });

        // 총 2번 요청 확인 (1 + 1회 재시도)
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("GAP-3: 4xx 응답 → 재시도 없이 즉시 AgencyHttpException 발생")
    void gap3_noRetryOn4xx_immediateException() {
        // given — 400 응답 (재시도 없어야 함)
        mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad-request\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
        kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter adapter =
                new kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter(3_000, 5_000, 3, 1L);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(adapter)
                .build();

        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build();

        // when & then — 4xx는 즉시 예외, 재시도 없음
        assertThatThrownBy(() -> client.sendInbound(event))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException httpEx = (AgencyHttpException) ex;
                    assertThat(httpEx.getHttpStatus()).isEqualTo(400);
                    assertThat(httpEx.isClientError()).isTrue();
                });

        // 재시도 없이 딱 1번만 요청
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // GAP-4: InboundEvent.idempotencyKey optional 전환 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GAP-4: idempotencyKey 미설정 시 UUID v4 자동 생성 — 빌드 예외 없음")
    void gap4_idempotencyKey_omitted_autoGeneratesUuid() {
        // given — idempotencyKey 없이 빌드 (기존에는 IllegalArgumentException 발생)
        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                // .idempotencyKey() 생략
                .build();

        // then — 자동 생성된 UUID v4 확인
        String autoKey = event.getIdempotencyKey();
        assertThat(autoKey).isNotNull().isNotEmpty();
        assertThat(autoKey).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("GAP-4: 두 번 빌드하면 서로 다른 idempotencyKey 자동 생성")
    void gap4_idempotencyKey_eachBuild_generatesDistinctKey() {
        InboundEvent event1 = InboundEvent.builder()
                .eventType("EVT").agencyCode(AGENCY_CODE).build();
        InboundEvent event2 = InboundEvent.builder()
                .eventType("EVT").agencyCode(AGENCY_CODE).build();

        // UUID가 매번 다르게 생성되는지 확인
        assertThat(event1.getIdempotencyKey()).isNotEqualTo(event2.getIdempotencyKey());
    }

    @Test
    @DisplayName("GAP-4: 명시적 idempotencyKey는 자동 생성보다 우선 적용")
    void gap4_idempotencyKey_explicit_overridesAutoGeneration() {
        InboundEvent event = InboundEvent.builder()
                .eventType("EVT")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey("my-fixed-key-001")
                .build();

        assertThat(event.getIdempotencyKey()).isEqualTo("my-fixed-key-001");
    }

    // ════════════════════════════════════════════════════════════════════════
    // GAP-5: validateJson() {}garbage 차단 강화 테스트
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("GAP-5: validateJson() 트레일링 garbage 차단 — 다양한 패턴 검증")
    void gap5_validateJson_trailingGarbage_isRejected() {
        // 케이스 A: {}garbage — {로 시작하나 e로 끝남 → 1차 필터(start/end 불일치)에서 차단
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{}garbage")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadJson must be a valid JSON object or array");

        // 케이스 B: {}[] — {로 시작하나 ]로 끝남 → 1차 필터에서 차단
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{}[]")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadJson must be a valid JSON object or array");

        // 케이스 C: {"a":1}{"b":2} — {로 시작 }로 끝나지만 첫 depth=0 이후 { 문자 등장 → trailing content 차단
        // GAP-5 핵심 수정으로 차단되는 케이스 (기존에는 통과됐음)
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{\"a\":1}{\"b\":2}")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing content");

        // 케이스 D: {"key":"val"}  {"extra":"obj"} — 첫 닫힘 후 공백+추가 객체 → trailing content 차단
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{\"key\":\"val\"} {\"extra\":\"obj\"}")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing content");
    }

    @Test
    @DisplayName("GAP-5: validateJson() 정상 케이스 — 중첩 객체/배열/문자열 내 특수문자 허용")
    void gap5_validateJson_validCases_pass() {
        // 단순 객체
        assertThatCode(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{\"key\":\"value\"}")
                .build()).doesNotThrowAnyException();

        // 중첩 객체
        assertThatCode(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{\"outer\":{\"inner\":\"val\"}}")
                .build()).doesNotThrowAnyException();

        // 문자열 내 중괄호 포함 (이스케이프 아닌 경우)
        assertThatCode(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{\"msg\":\"hello {world}\"}")
                .build()).doesNotThrowAnyException();

        // 빈 배열
        assertThatCode(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("[]")
                .build()).doesNotThrowAnyException();

        // 후행 공백은 허용 (trim 이후 처리)
        assertThatCode(() -> InboundEvent.builder()
                .eventType("EVT").agencyCode("AG")
                .payloadJson("{\"k\":\"v\"}   ")
                .build()).doesNotThrowAnyException();
    }

    // ════════════════════════════════════════════════════════════════════════
    // S17: OkHttpAgencyAdapter 재시도 + shouldRetryOn 테스트
    // ════════════════════════════════════════════════════════════════════════

    /**
     * S17-T1: 5xx 응답 후 재시도하여 202 성공
     *
     * <p>첫 번째 시도 503 → 두 번째 시도 202.
     * baseDelayMs=1ms로 테스트 속도 확보.
     */
    @Test
    @DisplayName("S17-T1: OkHttpAgencyAdapter — 5xx 응답 후 재시도 성공 (MockWebServer)")
    void s17T1_okHttp_retryOn5xx_succeedsOnSecondAttempt() throws Exception {
        // given — 첫 번째 503, 두 번째 202
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("{\"error\":\"unavailable\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("X-Correlation-Id", "okhttp-retry-corr-001")
                .setBody("{\"status\":\"accepted\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");

        OkHttpClient fastClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        // maxRetries=3, baseDelayMs=1ms (테스트 속도용)
        OkHttpAgencyAdapter adapter = new OkHttpAgencyAdapter(fastClient, 3, 1L);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(adapter)
                .build();

        // when
        GatewayResponse response = client.sendInbound(InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build());

        // then — 재시도 후 202 성공
        assertThat(response.getHttpStatus()).isEqualTo(202);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getCorrelationId()).isEqualTo("okhttp-retry-corr-001");
        // 총 2번 요청 (1회 실패 + 1회 성공)
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    /**
     * S17-T2: maxRetries 소진 → 마지막 5xx 상태코드로 AgencyHttpException
     *
     * <p>maxRetries=1 → 총 2번 시도, 모두 502.
     */
    @Test
    @DisplayName("S17-T2: OkHttpAgencyAdapter — 5xx maxRetries 소진 → AgencyHttpException(502)")
    void s17T2_okHttp_retryExhausted_throwsLastException() {
        // given — 모두 502
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(502)
                .setBody("{\"error\":\"bad-gateway\"}"));
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(502)
                .setBody("{\"error\":\"bad-gateway\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
        // maxRetries=1 (총 2회), baseDelayMs=1ms
        OkHttpAgencyAdapter adapter = new OkHttpAgencyAdapter(new OkHttpClient(), 1, 1L);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .agencyCode(AGENCY_CODE)
                .httpAdapter(adapter)
                .build();

        // when & then
        assertThatThrownBy(() -> client.sendInbound(InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build()))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException httpEx = (AgencyHttpException) ex;
                    assertThat(httpEx.getHttpStatus()).isEqualTo(502);
                    assertThat(httpEx.isServerError()).isTrue();
                });

        // 총 2번 요청 (1 + 1회 재시도)
        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
    }

    /**
     * S17-T3: 4xx 응답 → 재시도 없이 즉시 AgencyHttpException
     *
     * <p>400은 클라이언트 오류이므로 재시도해선 안 된다.
     */
    @Test
    @DisplayName("S17-T3: OkHttpAgencyAdapter — 4xx 응답은 재시도 없이 즉시 예외")
    void s17T3_okHttp_noRetryOn4xx_immediateException() {
        // given — 400 한 번만 등록
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\":\"bad-request\"}"));

        String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
        // maxRetries=3이지만 4xx는 재시도해선 안 됨
        OkHttpAgencyAdapter adapter = new OkHttpAgencyAdapter(new OkHttpClient(), 3, 1L);

        AgencyGatewayClient client = AgencyGatewayClient.builder()
                .baseUrl(baseUrl)
                .apiKey(API_KEY)
                .httpAdapter(adapter)
                .build();

        // when & then
        assertThatThrownBy(() -> client.sendInbound(InboundEvent.builder()
                .eventType("EVT").agencyCode(AGENCY_CODE).idempotencyKey(IDEMPOTENCY_KEY).build()))
                .isInstanceOf(AgencyHttpException.class)
                .satisfies(ex -> {
                    AgencyHttpException httpEx = (AgencyHttpException) ex;
                    assertThat(httpEx.getHttpStatus()).isEqualTo(400);
                    assertThat(httpEx.isClientError()).isTrue();
                });

        // 재시도 없이 딱 1번만 요청
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    /**
     * S17-T4: {@code shouldRetryOn()} 분류 정확성 단위 테스트
     *
     * <p>타임아웃/인터럽트는 재시도 불가, 그 외 IOException은 재시도 가능.
     */
    @Test
    @DisplayName("S17-T4: OkHttpAgencyAdapter.shouldRetryOn() — 타임아웃·인터럽트는 false, 나머지는 true")
    void s17T4_shouldRetryOn_classifiesCorrectly() {
        // 타임아웃 → InterruptedIOException 서브클래스 → false
        assertThat(OkHttpAgencyAdapter.shouldRetryOn(new SocketTimeoutException("read timeout")))
                .as("SocketTimeoutException은 재시도 불가").isFalse();

        // InterruptedIOException 직접 → false
        assertThat(OkHttpAgencyAdapter.shouldRetryOn(new InterruptedIOException("interrupted")))
                .as("InterruptedIOException은 재시도 불가").isFalse();

        // 일반 연결 오류 → true
        assertThat(OkHttpAgencyAdapter.shouldRetryOn(new java.net.ConnectException("Connection refused")))
                .as("ConnectException은 재시도 가능").isTrue();

        // DNS 오류 → true
        assertThat(OkHttpAgencyAdapter.shouldRetryOn(new java.net.UnknownHostException("unknown host")))
                .as("UnknownHostException은 재시도 가능").isTrue();

        // 일반 IOException → true
        assertThat(OkHttpAgencyAdapter.shouldRetryOn(new IOException("connection reset by peer")))
                .as("일반 IOException은 재시도 가능").isTrue();
    }
}
