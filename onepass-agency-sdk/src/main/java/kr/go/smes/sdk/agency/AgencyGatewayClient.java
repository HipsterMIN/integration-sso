package kr.go.smes.sdk.agency;

import kr.go.smes.sdk.agency.exception.AgencySdkException;
import kr.go.smes.sdk.agency.http.AgencyHttpAdapter;
import kr.go.smes.sdk.agency.http.HttpUrlConnectionAdapter;
import kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator;
import kr.go.smes.sdk.agency.model.GatewayResponse;
import kr.go.smes.sdk.agency.model.InboundEvent;
import kr.go.smes.sdk.agency.model.OutboundNotifyRequest;
import kr.go.smes.sdk.agency.security.HmacSigner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OnePass Agency SDK 메인 클라이언트
 *
 * <p>기관 시스템에서 OnePass Gateway API를 호출할 때 사용하는 진입점.
 * <b>Builder 패턴</b>으로 생성하며, 생성된 인스턴스는 <b>스레드 안전(thread-safe)</b>하다.
 *
 * <h3>JDK 버전 프리 설계</h3>
 * <ul>
 *   <li>Java 8 바이트코드 타겟 — Android / Spring Boot 2.x / JDK 8~21 모두 지원</li>
 *   <li>런타임 의존성 ZERO — {@code HttpURLConnection} 기본 구현 (JDK 내장)</li>
 *   <li>OkHttp3 / Apache HttpClient 5.x 어댑터 선택적 교체 가능</li>
 * </ul>
 *
 * <h3>기본 사용 예시 (JDK 8+)</h3>
 * <pre>{@code
 * // 1. 클라이언트 생성 (기본 HttpURLConnection 사용)
 * AgencyGatewayClient client = AgencyGatewayClient.builder()
 *     .baseUrl("https://onepass.go.kr")
 *     .apiKey("your-x-api-key")
 *     .agencyCode("MOIS")
 *     .build();
 *
 * // 2. 인바운드 이벤트 전송 (기관 → OnePass)
 * InboundEvent event = InboundEvent.builder()
 *     .eventType("USER_REGISTERED")
 *     .agencyCode("MOIS")
 *     .idempotencyKey(IdempotencyKeyGenerator.generateWithPrefix("MOIS"))
 *     .payloadJson("{\"action\":\"sync\"}")
 *     .build();
 * GatewayResponse response = client.sendInbound(event);
 *
 * // 3. 아웃바운드 알림 요청 (OnePass → 기관 Webhook 트리거)
 * OutboundNotifyRequest notify = OutboundNotifyRequest.builder()
 *     .agencyCode("MOIS")
 *     .eventType("USER_PROVISIONED")
 *     .payload("{\"status\":\"ok\"}")
 *     .build();
 * GatewayResponse notifyResp = client.triggerOutbound(notify);
 *
 * // 4. 기관 연동 상태 조회
 * GatewayResponse status = client.getStatus("MOIS");
 * }</pre>
 *
 * <h3>OkHttp3 교체 예시</h3>
 * <pre>{@code
 * OkHttpClient okHttp = new OkHttpClient.Builder()
 *     .connectTimeout(5, TimeUnit.SECONDS)
 *     .readTimeout(30, TimeUnit.SECONDS)
 *     .build();
 *
 * AgencyGatewayClient client = AgencyGatewayClient.builder()
 *     .baseUrl("https://onepass.go.kr")
 *     .apiKey("your-api-key")
 *     .httpAdapter(new OkHttpAgencyAdapter(okHttp))
 *     .build();
 * }</pre>
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 */
public final class AgencyGatewayClient {

    // ── API 경로 상수 ──────────────────────────────────────────────────────────
    private static final String PATH_INBOUND      = "/api/v1/agency/gateway/inbound/event";
    private static final String PATH_OUTBOUND     = "/api/v1/agency/gateway/outbound/notify";
    private static final String PATH_STATUS       = "/api/v1/agency/gateway/status/";

    // ── 헤더 이름 상수 ─────────────────────────────────────────────────────────
    private static final String HDR_CONTENT_TYPE   = "Content-Type";
    private static final String HDR_ACCEPT         = "Accept";
    private static final String HDR_API_KEY        = "X-Api-Key";
    private static final String HDR_AGENCY_CODE    = "X-Agency-Code";
    private static final String HDR_IDEMPOTENCY    = "X-Idempotency-Key";
    private static final String HDR_CORRELATION_ID = "X-Correlation-Id";
    private static final String HDR_INTERNAL_SIG   = "X-Internal-Sig";
    private static final String HDR_TIMESTAMP      = "X-Timestamp";

    // ── 설정 필드 ──────────────────────────────────────────────────────────────
    private final String            baseUrl;
    private final String            apiKey;
    private final String            agencyCode;
    private final AgencyHttpAdapter httpAdapter;
    private final HmacSigner        hmacSigner;      // null이면 서명 비활성화
    private final boolean           signRequests;
    private final String            defaultCorrelationIdPrefix;

    private AgencyGatewayClient(Builder builder) {
        this.baseUrl                    = trimTrailingSlash(builder.baseUrl);
        this.apiKey                     = builder.apiKey;
        this.agencyCode                 = builder.agencyCode;
        this.httpAdapter                = builder.httpAdapter != null
                                          ? builder.httpAdapter
                                          : new HttpUrlConnectionAdapter(
                                                  builder.connectTimeoutMs,
                                                  builder.readTimeoutMs);
        this.hmacSigner                 = (builder.hmacSecret != null && !builder.hmacSecret.isEmpty())
                                          ? new HmacSigner(builder.hmacSecret)
                                          : null;
        this.signRequests               = builder.signRequests && this.hmacSigner != null;
        this.defaultCorrelationIdPrefix = builder.agencyCode != null ? builder.agencyCode : "SDK";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 인바운드 이벤트 전송 (기관 → OnePass)
     *
     * <p>{@code POST /api/v1/agency/gateway/inbound/event}
     *
     * <p>idempotencyKey가 지정되지 않으면 자동으로 UUID v4를 생성한다.
     *
     * @param event 전송할 인바운드 이벤트
     * @return {@link GatewayResponse} (성공 시 202 Accepted)
     * @throws kr.go.smes.sdk.agency.exception.AgencyHttpException HTTP 오류 시
     */
    public GatewayResponse sendInbound(InboundEvent event) {
        validateNotNull(event, "event");
        String body            = event.toJsonString();
        String idempotencyKey  = resolveIdempotencyKey(event.getIdempotencyKey());
        String correlationId   = resolveCorrelationId(event.getCorrelationId());
        String url             = baseUrl + PATH_INBOUND;
        Map<String, String> headers = buildHeaders("POST", PATH_INBOUND, body,
                                                    idempotencyKey, correlationId);
        return httpAdapter.execute("POST", url, headers, body);
    }

    /**
     * 아웃바운드 알림 발송 요청 (OnePass → 기관 Webhook 트리거)
     *
     * <p>{@code PATCH /api/v1/agency/gateway/outbound/notify}
     *
     * <p>이 메서드는 OnePass 서버에게 기관 Webhook을 발송하도록 <b>지시</b>한다.
     * 실제 기관 Webhook 호출은 OnePass 서버가 내부적으로 처리한다.
     *
     * @param request 아웃바운드 알림 요청 정보
     * @return {@link GatewayResponse} (성공 시 200 OK)
     */
    public GatewayResponse triggerOutbound(OutboundNotifyRequest request) {
        validateNotNull(request, "request");
        String body           = request.toJsonString();
        String idempotencyKey = resolveIdempotencyKey(request.getIdempotencyKey());
        String correlationId  = resolveCorrelationId(request.getCorrelationId());
        String url            = baseUrl + PATH_OUTBOUND;
        Map<String, String> headers = buildHeaders("PATCH", PATH_OUTBOUND, body,
                                                    idempotencyKey, correlationId);
        return httpAdapter.execute("PATCH", url, headers, body);
    }

    /**
     * 기관 연동 상태 조회
     *
     * <p>{@code GET /api/v1/agency/gateway/status/{agencyCode}}
     *
     * @param agencyCode 조회할 기관 코드 (null 시 클라이언트 기본 agencyCode 사용)
     * @return {@link GatewayResponse} (성공 시 200 OK, body는 GatewayStatusResponse JSON)
     */
    public GatewayResponse getStatus(String agencyCode) {
        String code = (agencyCode != null && !agencyCode.isEmpty())
                      ? agencyCode
                      : this.agencyCode;
        if (code == null || code.isEmpty()) {
            throw new AgencySdkException("SDK_MISSING_AGENCY", "agencyCode가 지정되지 않았습니다.");
        }
        String url  = baseUrl + PATH_STATUS + code;
        Map<String, String> headers = buildHeaders("GET", PATH_STATUS + code, null,
                                                    null, null);
        return httpAdapter.execute("GET", url, headers, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 공통 요청 헤더 구성
     *
     * <p>헤더 목록:
     * <ul>
     *   <li>{@code Content-Type: application/json;charset=UTF-8}</li>
     *   <li>{@code Accept: application/json}</li>
     *   <li>{@code X-Api-Key}: API 키 (SHA-256 검증은 서버에서 수행)</li>
     *   <li>{@code X-Agency-Code}: 기관 코드</li>
     *   <li>{@code X-Idempotency-Key}: 멱등성 키 (있는 경우)</li>
     *   <li>{@code X-Correlation-Id}: 요청 추적 ID</li>
     *   <li>{@code X-Internal-Sig}: HMAC-SHA256 서명 (signRequests=true 시)</li>
     *   <li>{@code X-Timestamp}: 서명 타임스탬프 epoch ms (서명 활성화 시)</li>
     * </ul>
     */
    private Map<String, String> buildHeaders(String method, String path, String body,
                                              String idempotencyKey, String correlationId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HDR_CONTENT_TYPE, "application/json;charset=UTF-8");
        headers.put(HDR_ACCEPT,       "application/json");

        if (apiKey != null) {
            headers.put(HDR_API_KEY, apiKey);
        }
        if (agencyCode != null) {
            headers.put(HDR_AGENCY_CODE, agencyCode);
        }
        if (idempotencyKey != null) {
            headers.put(HDR_IDEMPOTENCY, idempotencyKey);
        }
        if (correlationId != null) {
            headers.put(HDR_CORRELATION_ID, correlationId);
        }

        // HMAC-SHA256 서명 (Sprint 17 강제화 대비)
        if (signRequests && hmacSigner != null) {
            long timestampMs = System.currentTimeMillis();
            String sig       = hmacSigner.sign(method, path, timestampMs, body);
            headers.put(HDR_INTERNAL_SIG, sig);
            headers.put(HDR_TIMESTAMP,    String.valueOf(timestampMs));
        }

        return headers;
    }

    /** idempotencyKey가 null/빈 문자열이면 UUID v4 자동 생성 */
    private static String resolveIdempotencyKey(String key) {
        return (key != null && !key.isEmpty()) ? key : IdempotencyKeyGenerator.generate();
    }

    /** correlationId가 null이면 UUID v4 자동 생성 */
    private String resolveCorrelationId(String correlationId) {
        return (correlationId != null && !correlationId.isEmpty())
               ? correlationId
               : defaultCorrelationIdPrefix + "-" + IdempotencyKeyGenerator.generate();
    }

    private static void validateNotNull(Object value, String name) {
        if (value == null) {
            throw new AgencySdkException("SDK_NULL_PARAM", name + "은(는) null일 수 없습니다.");
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) throw new IllegalArgumentException("baseUrl must not be null");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Builder
    // ════════════════════════════════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link AgencyGatewayClient} 빌더
     *
     * <p>필수: {@link #baseUrl(String)}, {@link #apiKey(String)}
     */
    public static final class Builder {

        // 필수 필드
        private String baseUrl;
        private String apiKey;

        // 선택 필드
        private String            agencyCode;
        private AgencyHttpAdapter httpAdapter;
        private String            hmacSecret;
        private boolean           signRequests    = false;
        private int               connectTimeoutMs = 5_000;
        private int               readTimeoutMs    = 30_000;

        private Builder() {}

        /**
         * OnePass 서버 베이스 URL (필수)
         *
         * @param baseUrl 예: {@code "https://onepass.go.kr"} (트레일링 슬래시 불필요)
         */
        public Builder baseUrl(String baseUrl) {
            if (baseUrl == null || baseUrl.isEmpty()) {
                throw new IllegalArgumentException("baseUrl must not be blank");
            }
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * X-Api-Key 인증 키 (필수)
         *
         * <p>OnePass 관리자가 기관에 발급한 API 키.
         * SHA-256 해시 검증은 서버에서 수행하므로 평문 전송.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 기관 코드 (X-Agency-Code 헤더 자동 설정)
         *
         * <p>지정 시 모든 요청에 {@code X-Agency-Code} 헤더가 추가된다.
         * 기관 인증서버 사이드 필터링에 사용.
         */
        public Builder agencyCode(String agencyCode) {
            this.agencyCode = agencyCode;
            return this;
        }

        /**
         * HTTP 어댑터 교체 (선택)
         *
         * <p>기본값: {@link HttpUrlConnectionAdapter} (JDK 내장, 의존성 없음)<br>
         * 교체 예:
         * <ul>
         *   <li>{@code new OkHttpAgencyAdapter(okHttpClient)} — OkHttp3</li>
         *   <li>{@code new ApacheHttpAgencyAdapter(apacheClient)} — Apache HC5</li>
         *   <li>람다 구현 — Spring {@code RestTemplate} 등 커스텀 연동</li>
         * </ul>
         */
        public Builder httpAdapter(AgencyHttpAdapter httpAdapter) {
            this.httpAdapter = httpAdapter;
            return this;
        }

        /**
         * HMAC-SHA256 서명 비밀키 설정 (Sprint 17 강제화 대비)
         *
         * <p>설정하면 {@link #signRequests(boolean)}을 {@code true}로 변경해야 서명이 활성화된다.
         *
         * @param hmacSecret OnePass 서버와 사전 합의한 공유 비밀키
         */
        public Builder hmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
            return this;
        }

        /**
         * HMAC-SHA256 서명 활성화 여부 (기본: false)
         *
         * <p>{@code true}로 설정하면 모든 요청에 {@code X-Internal-Sig} 헤더가 추가된다.
         * {@link #hmacSecret(String)} 설정이 선행되어야 한다.
         */
        public Builder signRequests(boolean signRequests) {
            this.signRequests = signRequests;
            return this;
        }

        /**
         * 연결 타임아웃 설정 (기본: 5,000ms)
         *
         * <p>{@link #httpAdapter(AgencyHttpAdapter)}로 커스텀 어댑터를 주입한 경우
         * 이 설정은 무시된다 (어댑터 자체 타임아웃 적용).
         */
        public Builder connectTimeoutMs(int connectTimeoutMs) {
            if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connectTimeoutMs must be > 0");
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /**
         * 읽기 타임아웃 설정 (기본: 30,000ms)
         */
        public Builder readTimeoutMs(int readTimeoutMs) {
            if (readTimeoutMs <= 0) throw new IllegalArgumentException("readTimeoutMs must be > 0");
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        /** {@link AgencyGatewayClient} 인스턴스 생성 */
        public AgencyGatewayClient build() {
            if (baseUrl == null || baseUrl.isEmpty()) {
                throw new AgencySdkException("SDK_CONFIG_ERROR", "baseUrl은 필수입니다.");
            }
            return new AgencyGatewayClient(this);
        }
    }
}
