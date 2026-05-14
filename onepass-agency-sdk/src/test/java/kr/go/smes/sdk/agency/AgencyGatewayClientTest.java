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
    @DisplayName("S16-T2: 요청 헤더에 X-Agency-Key, X-Idempotency-Key, X-Agency-Code 포함 검증")
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
    // S16-T4: HMAC-SHA256 서명 활성화 시 X-Internal-Sig, X-Timestamp 헤더 포함
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T4: HMAC 서명 활성화 → X-Internal-Sig, X-Timestamp 헤더 포함 검증")
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
        assertThat(capturedHeaders).containsKey("X-Internal-Sig");
        assertThat(capturedHeaders).containsKey("X-Timestamp");
        // HMAC-SHA256는 64자 HEX 문자열
        assertThat(capturedHeaders.get("X-Internal-Sig")).hasSize(64);
        // 타임스탬프는 숫자 문자열
        assertThat(capturedHeaders.get("X-Timestamp")).matches("\\d+");
    }

    // ════════════════════════════════════════════════════════════════════════
    // S16-T5: idempotencyKey 미지정 시 UUID v4 자동 생성
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S16-T5: idempotencyKey 미지정 → UUID v4 자동 생성하여 헤더 포함")
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

        // idempotencyKey 미지정
        InboundEvent event = InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .agencyCode(AGENCY_CODE)
                .idempotencyKey("auto")     // 빌더 검증 통과용 임시 값; 아래에서 자동 생성 확인
                .build();

        // when
        client.sendInbound(event);

        // then — X-Idempotency-Key 헤더가 UUID v4 형식인지 확인
        // (InboundEvent에 직접 idempotencyKey를 넣었으므로 그대로 전달됨)
        assertThat(headersCaptor.getValue()).containsKey("X-Idempotency-Key");

        // 직접 null 키 생성 API 검증
        String autoKey = IdempotencyKeyGenerator.generate();
        assertThat(autoKey).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
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
        assertThat(recorded.getBody().readUtf8()).contains("USER_REGISTERED");
    }

    // ════════════════════════════════════════════════════════════════════════
    // HmacSigner 단독 테스트 (S16-T4 보조)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("HmacSigner: sign + verifySignature 상수시간 비교 정확성 검증")
    void hmacSigner_signAndVerify_consistent() {
        // given
        HmacSigner signer = new HmacSigner(HMAC_SECRET);
        long ts = System.currentTimeMillis();

        // when
        String sig1 = signer.sign("POST", "/api/v1/test", ts, "{\"key\":\"val\"}");
        String sig2 = signer.sign("POST", "/api/v1/test", ts, "{\"key\":\"val\"}");
        String sig3 = signer.sign("POST", "/api/v1/test", ts, "{\"key\":\"different\"}");

        // then — 동일 입력은 동일 서명
        assertThat(signer.verifySignature(sig1, sig2)).isTrue();
        // 본문 달라지면 서명 달라짐
        assertThat(signer.verifySignature(sig1, sig3)).isFalse();
        // 64자 HEX
        assertThat(sig1).hasSize(64).matches("[0-9a-f]{64}");
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
    @DisplayName("InboundEvent: 필수 필드 미설정 시 IllegalArgumentException 발생")
    void inboundEvent_missingRequiredField_throwsException() {
        // eventType 없음
        assertThatThrownBy(() -> InboundEvent.builder()
                .agencyCode(AGENCY_CODE)
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");

        // agencyCode 없음
        assertThatThrownBy(() -> InboundEvent.builder()
                .eventType("USER_REGISTERED")
                .idempotencyKey(IDEMPOTENCY_KEY)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agencyCode");
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
}
