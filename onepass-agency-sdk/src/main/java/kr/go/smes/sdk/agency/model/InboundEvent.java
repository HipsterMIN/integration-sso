package kr.go.smes.sdk.agency.model;

/**
 * 기관 → OnePass 인바운드 이벤트 전송 요청 모델
 *
 * <p>OnePass {@code POST /api/v1/agency/gateway/inbound/event} 엔드포인트로
 * 전송할 페이로드를 표현한다.
 *
 * <p><b>불변(Immutable) 설계:</b> 모든 필드는 생성자에서 설정 후 변경 불가.
 * Java 8 호환을 위해 {@code record} 대신 일반 final 클래스를 사용.
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 *
 * <pre>{@code
 * InboundEvent event = InboundEvent.builder()
 *     .eventType("USER_REGISTERED")
 *     .idempotencyKey("550e8400-e29b-41d4-a716-446655440000")
 *     .agencyCode("MOIS")
 *     .payloadJson("{\"action\":\"sync\"}")
 *     .correlationId("req-001")
 *     .build();
 * }</pre>
 */
public final class InboundEvent {

    private final String eventType;
    private final String idempotencyKey;
    private final String agencyCode;
    private final String payloadJson;
    private final String correlationId;

    private InboundEvent(Builder builder) {
        if (builder.eventType == null || builder.eventType.isEmpty()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (builder.idempotencyKey == null || builder.idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (builder.agencyCode == null || builder.agencyCode.isEmpty()) {
            throw new IllegalArgumentException("agencyCode must not be blank");
        }
        this.eventType      = builder.eventType;
        this.idempotencyKey = builder.idempotencyKey;
        this.agencyCode     = builder.agencyCode;
        this.payloadJson    = builder.payloadJson != null ? builder.payloadJson : "{}";
        this.correlationId  = builder.correlationId;
    }

    public String getEventType()      { return eventType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAgencyCode()     { return agencyCode; }
    public String getPayloadJson()    { return payloadJson; }
    public String getCorrelationId()  { return correlationId; }

    /**
     * JSON 직렬화 (외부 라이브러리 없이 순수 JDK로 처리).
     *
     * <p>payloadJson은 이미 JSON 문자열이므로 그대로 embed.
     * 단순 필드는 manual escape 적용.
     */
    public String toJsonString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event_type\":").append(jsonStr(eventType)).append(",");
        sb.append("\"idempotency_key\":").append(jsonStr(idempotencyKey)).append(",");
        sb.append("\"agency_code\":").append(jsonStr(agencyCode)).append(",");
        sb.append("\"payload\":").append(payloadJson).append(",");
        if (correlationId != null) {
            sb.append("\"correlation_id\":").append(jsonStr(correlationId)).append(",");
        }
        // trailing comma 제거
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append("}");
        return sb.toString();
    }

    /** JSON 문자열 이스케이프 헬퍼 */
    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\")
                           .replace("\"", "\\\"")
                           .replace("\n", "\\n")
                           .replace("\r", "\\r")
                           .replace("\t", "\\t") + "\"";
    }

    @Override
    public String toString() {
        return "InboundEvent{eventType=" + eventType
                + ", agencyCode=" + agencyCode
                + ", idempotencyKey=" + idempotencyKey + "}";
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String eventType;
        private String idempotencyKey;
        private String agencyCode;
        private String payloadJson;
        private String correlationId;

        private Builder() {}

        public Builder eventType(String eventType)           { this.eventType = eventType; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder agencyCode(String agencyCode)         { this.agencyCode = agencyCode; return this; }
        public Builder payloadJson(String payloadJson)       { this.payloadJson = payloadJson; return this; }
        public Builder correlationId(String correlationId)   { this.correlationId = correlationId; return this; }

        public InboundEvent build() {
            return new InboundEvent(this);
        }
    }
}
