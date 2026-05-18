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
 * <h3>인바운드 요청 헤더</h3>
 * <ul>
 *   <li>{@code X-Agency-Key}     — API Key (서버 SHA-256 해시 검증)</li>
 *   <li>{@code X-Agency-Code}    — 기관 코드</li>
 *   <li>{@code X-Idempotency-Key} — 멱등성 키 (24h TTL)</li>
 *   <li>{@code X-Event-Type}     — 이벤트 타입 (서버 라우팅에 사용)</li>
 *   <li>{@code X-Correlation-ID} — 요청 추적 ID</li>
 *   <li>{@code X-Internal-Sig}   — HMAC-SHA256 서명 (signRequests=true 시)</li>
 * </ul>
 *
 * <h3>HMAC 서명 알고리즘 (Sprint 17 Phase 4 강제화)</h3>
 * <pre>
 * 서명 페이로드  = "{agencyCode}:{idempotencyKey}:{epochSeconds}"
 * X-Internal-Sig = HEX( HMAC-SHA256(hmacSecret, 서명페이로드) )
 * </pre>
 *
 * <h3>기본 사용 예시 (JDK 8+)</h3>
 * <pre>{@code
 * // 1. 클라이언트 생성 (기본 HttpURLConnection 사용)
 * AgencyGatewayClient client = AgencyGatewayClient.builder()
 *     .baseUrl("http://localhost:8083")   // IdO 서버 URL (포트 8083)
 *     .apiKey("stub-api-key-dev")         // X-Agency-Key 헤더로 전송됨
 *     .agencyCode("AGENCY_STUB_001")
 *     .build();
 *
 * // 2. 인바운드 이벤트 전송 (기관 → OnePass)
 * InboundEvent event = InboundEvent.builder()
 *     .eventType("USER_REGISTERED")
 *     .agencyCode("AGENCY_STUB_001")
 *     .idempotencyKey(IdempotencyKeyGenerator.generateWithPrefix("AGENCY_STUB_001"))
 *     .payloadJson("{\"action\":\"sync\"}")
 *     .build();
 * GatewayResponse response = client.sendInbound(event);
 *
 * // 3. 기관 연동 상태 조회
 * // ⚠️ triggerOutbound()는 내부 운영자 전용 — 기관 개발자는 sendInbound() 만 사용할 것
 * GatewayResponse status = client.getStatus("AGENCY_STUB_001");
 * }</pre>
 *
 * <h3>HMAC 서명 활성화 예시 (Sprint 17 Phase 4 대비)</h3>
 * <pre>{@code
 * AgencyGatewayClient client = AgencyGatewayClient.builder()
 *     .baseUrl("http://localhost:8083")
 *     .apiKey("stub-api-key-dev")
 *     .agencyCode("AGENCY_STUB_001")
 *     .hmacSecret("agency-hmac-shared-secret")   // 기관별 독립 HMAC 키 (API Key와 별개)
 *     .signRequests(true)
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
    private static final String HDR_API_KEY        = "X-Agency-Key";
    private static final String HDR_AGENCY_CODE    = "X-Agency-Code";
    private static final String HDR_IDEMPOTENCY    = "X-Idempotency-Key";
    private static final String HDR_CORRELATION_ID = "X-Correlation-ID";  // 서버 AgencyGatewayController와 일치
    private static final String HDR_INTERNAL_SIG   = "X-Internal-Sig";
    private static final String HDR_EVENT_TYPE     = "X-Event-Type";      // 서버 eventType 라우팅용

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
     * <p>이벤트 타입({@link InboundEvent#getEventType()})은 JSON body의
     * {@code "event_type"} 필드와 {@code X-Event-Type} 헤더 양쪽에 전송된다.
     * 서버는 {@code X-Event-Type} 헤더를 이벤트 라우팅에 사용한다.
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
        Map<String, String> headers = buildInboundHeaders(
                idempotencyKey, correlationId, event.getEventType());
        // HMAC 서명: agencyCode + idempotencyKey + epochSeconds
        addHmacSignatureIfEnabled(headers, resolveAgencyCode(event.getAgencyCode()), idempotencyKey);
        return httpAdapter.execute("POST", url, headers, body);
    }

    /**
     * [내부 운영자 전용] 아웃바운드 알림 트리거
     *
     * <p><b>⚠️ 기관 개발자는 이 메서드를 사용하지 마십시오.</b>
     *
     * <p>{@code PATCH /api/v1/agency/gateway/outbound/notify} 엔드포인트는
     * OnePass <b>내부 운영자 대시보드 및 배치 작업 전용</b>으로 설계된 경로입니다.
     * Kubernetes IngressRule에 의해 외부 트래픽이 차단되어 있으므로,
     * 기관 시스템에서 이 메서드를 호출하면 네트워크 레이어에서 거부됩니다.
     *
     * <p>기관 시스템이 OnePass로 이벤트를 전송할 때는
     * {@link #sendInbound(InboundEvent)} 를 사용하십시오.
     *
     * <pre>{@code
     * // ❌ 기관 개발자는 사용 금지
     * // client.triggerOutbound(request);
     *
     * // ✅ 기관 이벤트 전송은 sendInbound() 사용
     * GatewayResponse response = client.sendInbound(event);
     * }</pre>
     *
     * @param request 아웃바운드 알림 요청 정보
     * @return {@link GatewayResponse} (내부망에서는 200 OK, 외부망에서는 네트워크 차단)
     * @deprecated 이 엔드포인트는 OnePass 내부 운영자 전용입니다.
     *             기관 시스템 연동에는 {@link #sendInbound(InboundEvent)} 를 사용하세요.
     */
    @Deprecated
    public GatewayResponse triggerOutbound(OutboundNotifyRequest request) {
        validateNotNull(request, "request");
        String body           = request.toJsonString();
        String idempotencyKey = resolveIdempotencyKey(request.getIdempotencyKey());
        String correlationId  = resolveCorrelationId(request.getCorrelationId());
        String url            = baseUrl + PATH_OUTBOUND;
        Map<String, String> headers = buildBaseHeaders(idempotencyKey, correlationId);
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
        Map<String, String> headers = buildBaseHeaders(null, null);
        return httpAdapter.execute("GET", url, headers, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 내부 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 인바운드 전용 요청 헤더 구성 (X-Event-Type 포함)
     *
     * <p>헤더 목록:
     * <ul>
     *   <li>{@code Content-Type: application/json;charset=UTF-8}</li>
     *   <li>{@code Accept: application/json}</li>
     *   <li>{@code X-Agency-Key} — HandoffAgencyKeyInterceptor SHA-256 검증</li>
     *   <li>{@code X-Agency-Code} — 기관 코드</li>
     *   <li>{@code X-Idempotency-Key} — 멱등성 키</li>
     *   <li>{@code X-Correlation-ID} — 요청 추적 ID (서버와 동일 헤더명)</li>
     *   <li>{@code X-Event-Type} — 이벤트 타입 (서버 라우팅 필수)</li>
     * </ul>
     */
    private Map<String, String> buildInboundHeaders(String idempotencyKey,
                                                     String correlationId,
                                                     String eventType) {
        Map<String, String> headers = buildBaseHeaders(idempotencyKey, correlationId);
        // X-Event-Type: 서버 AgencyGatewayController.receiveInbound()가 이 헤더로 eventType을 읽음
        // null이면 서버 기본값 "CUSTOM"으로 처리됨 — 명시적으로 설정 권장
        if (eventType != null && !eventType.isEmpty()) {
            headers.put(HDR_EVENT_TYPE, eventType);
        }
        return headers;
    }

    /**
     * 공통 기본 요청 헤더 구성
     *
     * @param idempotencyKey 멱등성 키 (null 허용)
     * @param correlationId  요청 추적 ID (null 허용)
     * @return 헤더 맵 (LinkedHashMap — 삽입 순서 유지)
     */
    private Map<String, String> buildBaseHeaders(String idempotencyKey, String correlationId) {
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
        return headers;
    }

    /**
     * HMAC-SHA256 서명 헤더 추가 (signRequests=true 시)
     *
     * <p>서명 알고리즘: {@code HmacSigner#sign(agencyCode, idempotencyKey, epochSeconds)}
     * <pre>
     * 페이로드       = "{agencyCode}:{idempotencyKey}:{epochSeconds}"
     * X-Internal-Sig = HEX( HMAC-SHA256(hmacSecret, 페이로드) )
     * </pre>
     *
     * <p>서버({@code HmacSignatureFilter})는 ±60초 범위의 epochSeconds를 전수 검사한다.
     * {@code X-Timestamp} 헤더는 서버가 사용하지 않으므로 전송하지 않는다.
     *
     * @param headers        추가 대상 헤더 맵
     * @param agencyCode     서명에 포함할 기관 코드
     * @param idempotencyKey 서명에 포함할 멱등성 키
     */
    private void addHmacSignatureIfEnabled(Map<String, String> headers,
                                            String agencyCode,
                                            String idempotencyKey) {
        if (signRequests && hmacSigner != null) {
            // epochSeconds: 서버 HmacSignatureFilter와 동일한 단위 (초)
            long epochSeconds = System.currentTimeMillis() / 1000L;
            String sig = hmacSigner.sign(agencyCode, idempotencyKey, epochSeconds);
            headers.put(HDR_INTERNAL_SIG, sig);
        }
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

    /**
     * 인바운드 이벤트의 agencyCode 결정 — 이벤트 값 우선, 없으면 클라이언트 설정값
     */
    private String resolveAgencyCode(String eventAgencyCode) {
        return (eventAgencyCode != null && !eventAgencyCode.isEmpty())
               ? eventAgencyCode
               : this.agencyCode;
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
         * IdO 서버 베이스 URL (필수)
         *
         * <p>이 SDK는 {@code ido} 모듈(기본 포트 8083)의 Gateway API를 직접 호출한다.
         * {@code onepass-fe}(UI 서버)나 외부 시연 URL이 아님에 주의.
         *
         * @param baseUrl 예: {@code "http://localhost:8083"} (로컬),
         *                    {@code "http://ido:8083"} (Docker Compose),
         *                    {@code "http://ido-service:8083"} (k8s 내부)
         *                    — 트레일링 슬래시 불필요
         */
        public Builder baseUrl(String baseUrl) {
            if (baseUrl == null || baseUrl.isEmpty()) {
                throw new IllegalArgumentException("baseUrl must not be blank");
            }
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * X-Agency-Key 인증 키 (필수)
         *
         * <p>OnePass 관리자가 기관에 발급한 API 키.
         * IdO {@code HandoffAgencyKeyInterceptor}가 SHA-256(rawKey)를
         * {@code ido.agency_meta.api_key_hash}와 상수시간 비교하여 검증한다.
         * 평문을 그대로 전송하면 서버가 해싱하여 비교.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 기관 코드 (X-Agency-Code 헤더 자동 설정)
         *
         * <p>지정 시 모든 요청에 {@code X-Agency-Code} 헤더가 추가된다.
         * HMAC 서명 활성화 시 서명 페이로드에도 사용된다.
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
         * HMAC-SHA256 서명 비밀키 설정 (Sprint 17 Phase 4 강제화 대비)
         *
         * <p><b>중요:</b> 이 키는 {@code X-Agency-Key}(API Key)와 <b>별개의 비밀키</b>이다.
         * 서버의 {@code AgencyHmacKeyStore}에 agencyCode별로 등록된 HMAC 전용 키를
         * OnePass 관리자로부터 수령하여 설정한다.
         *
         * <p>설정 후 {@link #signRequests(boolean)}을 {@code true}로 변경해야 서명이 활성화된다.
         *
         * @param hmacSecret 기관별 HMAC 공유 비밀키 (UTF-8 문자열, 길이 제한 없음)
         */
        public Builder hmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
            return this;
        }

        /**
         * HMAC-SHA256 서명 활성화 여부 (기본: {@code false})
         *
         * <p>{@code true}로 설정하면 모든 인바운드 요청에 {@code X-Internal-Sig} 헤더가 추가된다.
         * {@link #hmacSecret(String)} 설정이 선행되어야 한다.
         *
         * <p><b>⚠️ Sprint 17 Phase 4 전환 시 필수화:</b><br>
         * {@code IDO_HMAC_SIG_REQUIRED=true} 설정 후에는 X-Internal-Sig 헤더가 없으면
         * 모든 인바운드 요청이 401로 거부된다. Phase 4 진입 전에 반드시 서명을 활성화하고
         * Staging 환경에서 검증해야 한다.
         *
         * @see HmacSigner
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
            if (apiKey == null || apiKey.isEmpty()) {
                throw new AgencySdkException("SDK_CONFIG_ERROR",
                        "apiKey는 필수입니다. OnePass 관리자로부터 발급받은 X-Agency-Key를 설정하세요.");
            }
            if (signRequests && (hmacSecret == null || hmacSecret.isEmpty())) {
                throw new AgencySdkException("SDK_CONFIG_ERROR",
                        "signRequests=true 설정 시 hmacSecret이 필요합니다." +
                        " OnePass 관리자로부터 기관별 HMAC 비밀키를 수령하여 설정하세요.");
            }
            return new AgencyGatewayClient(this);
        }
    }
}
