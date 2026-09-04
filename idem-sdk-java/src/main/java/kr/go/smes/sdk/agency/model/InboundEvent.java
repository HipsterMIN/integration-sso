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
 * <h3>idempotencyKey 자동 생성 (GAP-4)</h3>
 * <p>{@code idempotencyKey}를 지정하지 않으면 SDK가 UUID v4를 자동 생성한다.
 * 동일 이벤트를 안전하게 재전송하려면 반드시 같은 키를 명시해야 한다.
 *
 * <pre>{@code
 * // 1) idempotencyKey 명시 — 재전송 시 멱등성 보장
 * InboundEvent event = InboundEvent.builder()
 *     .eventType("USER_REGISTERED")
 *     .idempotencyKey("550e8400-e29b-41d4-a716-446655440000")
 *     .agencyCode("MOIS")
 *     .payloadJson("{\"action\":\"sync\"}")
 *     .correlationId("req-001")
 *     .build();
 *
 * // 2) idempotencyKey 생략 — SDK가 UUID v4 자동 생성 (단발성 이벤트에 편리)
 * InboundEvent event2 = InboundEvent.builder()
 *     .eventType("USER_REGISTERED")
 *     .agencyCode("MOIS")
 *     .payloadJson("{\"action\":\"sync\"}")
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
        if (builder.agencyCode == null || builder.agencyCode.isEmpty()) {
            throw new IllegalArgumentException("agencyCode must not be blank");
        }
        this.eventType      = builder.eventType;
        // GAP-4: idempotencyKey가 null/빈 값이면 UUID v4 자동 생성
        this.idempotencyKey = (builder.idempotencyKey != null && !builder.idempotencyKey.isEmpty())
                              ? builder.idempotencyKey
                              : kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator.generate();
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
     * payloadJson 유효성 검증 (GAP-5 강화).
     *
     * <p>JSON 객체({...}) 또는 배열([...]) 형식만 허용.
     * bracket 균형 검사와 함께 최상위 bracket이 닫힌 뒤 trailing garbage가 없는지 확인.
     *
     * <p><b>GAP-5 수정 전 허용되던 케이스 (현재 모두 거부):</b>
     * <ul>
     *   <li>{@code {}garbage}   — 최상위 닫힌 후 후행 문자 존재</li>
     *   <li>{@code {}[]}       — 연속된 최상위 JSON 값</li>
     *   <li>{@code {}}          — depth=0 종료 후 빈 괄호 연속</li>
     * </ul>
     *
     * @param json 검증할 JSON 문자열
     * @return 유효한 경우 json 그대로 반환
     * @throws IllegalArgumentException JSON 객체/배열이 아니거나 trailing garbage 존재 시
     */
    static String validateJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        String trimmed = json.trim();
        char first = trimmed.charAt(0);
        char last  = trimmed.charAt(trimmed.length() - 1);

        // 시작/끝 문자 1차 검사 (빠른 사전 필터)
        if ((first != '{' || last != '}') && (first != '[' || last != ']')) {
            throw new IllegalArgumentException(
                    "payloadJson must be a valid JSON object or array, got: "
                    + (trimmed.length() > 40 ? trimmed.substring(0, 40) + "..." : trimmed));
        }

        // GAP-5: bracket depth 검사 + depth=0 도달 시점 추적
        int  depth       = 0;
        int  closedAtIdx = -1;   // 최초로 depth=0이 된 인덱스 (시작 bracket 제외)
        boolean inString = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);

            // 이스케이프 처리: 문자열 내부 백슬래시 → 다음 문자 건너뜀
            if (c == '\\' && inString) { i++; continue; }

            // 문자열 토글
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;

            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException(
                            "payloadJson has unbalanced brackets: "
                            + trimmed.substring(0, Math.min(40, trimmed.length())));
                }
                // 최상위 bracket이 처음 닫힌 위치 기록 (depth=0 → 이후 trailing이 있으면 오류)
                if (depth == 0 && closedAtIdx == -1) {
                    closedAtIdx = i;
                }
            }

            // GAP-5 핵심: 최상위 닫힘 후 비공백 문자 발견 → {}garbage 같은 케이스 차단
            if (closedAtIdx != -1 && i > closedAtIdx && !Character.isWhitespace(c)) {
                throw new IllegalArgumentException(
                        "payloadJson has trailing content after closing bracket: "
                        + trimmed.substring(0, Math.min(40, trimmed.length())));
            }
        }

        if (depth != 0) {
            throw new IllegalArgumentException(
                    "payloadJson has unclosed brackets (depth=" + depth + ")");
        }
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
        /**
         * 멱등성 키 설정 (선택).
         *
         * <p>설정하지 않으면 {@link kr.go.smes.sdk.agency.idempotency.IdempotencyKeyGenerator#generate()}로
         * UUID v4를 자동 생성한다.
         * <b>동일 이벤트를 안전하게 재전송할 때는 반드시 동일한 키를 명시적으로 지정해야 한다.</b>
         *
         * @param idempotencyKey 멱등성 키 (null/빈 문자열 허용 — null 시 UUID 자동 생성)
         */
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder agencyCode(String agencyCode)         { this.agencyCode = agencyCode; return this; }
        public Builder payloadJson(String payloadJson)       { this.payloadJson = payloadJson; return this; }
        public Builder correlationId(String correlationId)   { this.correlationId = correlationId; return this; }

        public InboundEvent build() {
            return new InboundEvent(this);
        }
    }
}
