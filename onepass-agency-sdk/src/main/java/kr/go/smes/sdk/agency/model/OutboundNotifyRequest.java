package kr.go.smes.sdk.agency.model;

/**
 * OnePass → 기관 아웃바운드 알림 발송 요청 모델
 *
 * <p>OnePass {@code PATCH /api/v1/agency/gateway/outbound/notify} 엔드포인트로
 * 전송할 페이로드를 표현한다.
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 *
 * <pre>{@code
 * OutboundNotifyRequest req = OutboundNotifyRequest.builder()
 *     .agencyCode("MOIS")
 *     .eventType("USER_PROVISIONED")
 *     .payload("{\"status\":\"ok\"}")
 *     .idempotencyKey("uuid-v7-here")
 *     .build();
 * }</pre>
 */
public final class OutboundNotifyRequest {

    private final String agencyCode;
    private final String eventType;
    private final String payload;
    private final String idempotencyKey;
    private final String correlationId;

    private OutboundNotifyRequest(Builder builder) {
        if (builder.agencyCode == null || builder.agencyCode.isEmpty()) {
            throw new IllegalArgumentException("agencyCode must not be blank");
        }
        if (builder.eventType == null || builder.eventType.isEmpty()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        this.agencyCode     = builder.agencyCode;
        this.eventType      = builder.eventType;
        this.payload        = builder.payload != null ? builder.payload : "{}";
        this.idempotencyKey = builder.idempotencyKey;
        this.correlationId  = builder.correlationId;
    }

    public String getAgencyCode()     { return agencyCode; }
    public String getEventType()      { return eventType; }
    public String getPayload()        { return payload; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCorrelationId()  { return correlationId; }

    /** JSON 직렬화 (zero-dependency, JDK 8 호환) */
    public String toJsonString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"agency_code\":").append(jsonStr(agencyCode)).append(",");
        sb.append("\"event_type\":").append(jsonStr(eventType)).append(",");
        sb.append("\"payload\":").append(payload).append(",");
        if (idempotencyKey != null) {
            sb.append("\"idempotency_key\":").append(jsonStr(idempotencyKey)).append(",");
        }
        if (correlationId != null) {
            sb.append("\"correlation_id\":").append(jsonStr(correlationId)).append(",");
        }
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append("}");
        return sb.toString();
    }

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
        return "OutboundNotifyRequest{agencyCode=" + agencyCode
                + ", eventType=" + eventType
                + ", idempotencyKey=" + idempotencyKey + "}";
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String agencyCode;
        private String eventType;
        private String payload;
        private String idempotencyKey;
        private String correlationId;

        private Builder() {}

        public Builder agencyCode(String agencyCode)         { this.agencyCode = agencyCode; return this; }
        public Builder eventType(String eventType)           { this.eventType = eventType; return this; }
        public Builder payload(String payload)               { this.payload = payload; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder correlationId(String correlationId)   { this.correlationId = correlationId; return this; }

        public OutboundNotifyRequest build() { return new OutboundNotifyRequest(this); }
    }
}
