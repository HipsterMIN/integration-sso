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
        this.payloadJson    = validateJson(builder.payloadJson != null ? builder.payloadJson : "{}");
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
     * <p>payloadJson은 이미 유효한 JSON 문자열이므로 그대로 embed.
     * 단순 필드는 manual escape 적용.
     *
     * @throws IllegalArgumentException payloadJson이 유효한 JSON 객체/배열이 아닐 시
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

    /**
     * payloadJson 유효성 검증.
     *
     * <p>JSON 객체({...}) 또는 배열([...]) 형식만 허용.
     * 최소한의 bracket 균형 검사로 invalid JSON이 전체 JSON을 깨뜨리는 것을 방지.
     *
     * @param json 검증할 JSON 문자열
     * @return 유효한 경우 json 그대로 반환
     * @throws IllegalArgumentException JSON 객체/배열이 아닐 시
     */
    static String validateJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        String trimmed = json.trim();
        if ((!trimmed.startsWith("{") || !trimmed.endsWith("}"))
                && (!trimmed.startsWith("[") || !trimmed.endsWith("]"))) {
            throw new IllegalArgumentException(
                    "payloadJson must be a valid JSON object or array, got: "
                    + (trimmed.length() > 40 ? trimmed.substring(0, 40) + "..." : trimmed));
        }
        // bracket 균형 검사 (depth 검사)
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && inString) { i++; continue; }  // escape
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            if (depth < 0) throw new IllegalArgumentException(
                    "payloadJson has unbalanced brackets: " + trimmed.substring(0, Math.min(40, trimmed.length())));
        }
        if (depth != 0) throw new IllegalArgumentException(
                "payloadJson has unclosed brackets (depth=" + depth + ")");
        return trimmed;
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
