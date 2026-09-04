package kr.go.smes.sdk.agency.model;

/**
 * OnePass Gateway API 공통 응답 래퍼
 *
 * <p>SDK 모든 API 호출의 반환 타입.
 * HTTP 상태 코드, 응답 본문(raw JSON), 요청 ID를 포함한다.
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 *
 * <pre>{@code
 * GatewayResponse response = client.sendInbound(event);
 * if (response.isSuccess()) {
 *     System.out.println("수신 완료: " + response.getCorrelationId());
 * } else {
 *     System.err.println("오류 " + response.getHttpStatus() + ": " + response.getBody());
 * }
 * }</pre>
 */
public final class GatewayResponse {

    private final int    httpStatus;
    private final String body;
    private final String correlationId;
    private final String requestId;

    private GatewayResponse(int httpStatus, String body,
                             String correlationId, String requestId) {
        this.httpStatus    = httpStatus;
        this.body          = body;
        this.correlationId = correlationId;
        this.requestId     = requestId;
    }

    // ── 팩토리 메서드 ──────────────────────────────────────────────────────────

    public static GatewayResponse of(int httpStatus, String body,
                                      String correlationId, String requestId) {
        return new GatewayResponse(httpStatus, body, correlationId, requestId);
    }

    public static GatewayResponse success(String body, String correlationId) {
        return new GatewayResponse(200, body, correlationId, null);
    }

    // ── 접근자 ────────────────────────────────────────────────────────────────

    /** HTTP 상태 코드 반환 (200, 202, 409 등) */
    public int getHttpStatus() { return httpStatus; }

    /** 응답 본문 (raw JSON 문자열) */
    public String getBody() { return body; }

    /** 서버에서 반환한 X-Correlation-Id 헤더 값 */
    public String getCorrelationId() { return correlationId; }

    /** 서버에서 반환한 X-Request-Id 헤더 값 */
    public String getRequestId() { return requestId; }

    /**
     * 성공 응답 여부 (2xx 범위).
     *
     * @return {@code httpStatus >= 200 && httpStatus < 300}
     */
    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300;
    }

    /**
     * 멱등성 충돌 여부 (409 Conflict).
     * 이미 처리된 idempotency-key로 재요청한 경우.
     */
    public boolean isIdempotencyConflict() {
        return httpStatus == 409;
    }

    // ── GAP-2: JSON 응답 파싱 헬퍼 ────────────────────────────────────────────

    /**
     * 응답 본문 JSON에서 최상위 문자열/숫자 필드 값 추출 (외부 라이브러리 없음).
     *
     * <p>서버 응답 예시:
     * <pre>
     * {"status":"accepted","requestId":"abc-123","code":202}
     * </pre>
     *
     * <p>사용 예시:
     * <pre>{@code
     * GatewayResponse resp = client.sendInbound(event);
     * String status    = resp.getBodyField("status");     // "accepted"
     * String requestId = resp.getBodyField("requestId");  // "abc-123"
     * String code      = resp.getBodyField("code");       // "202"
     * String missing   = resp.getBodyField("no_such");    // null
     * }</pre>
     *
     * <p><b>제약:</b> 최상위(depth=1) 문자열/숫자 스칼라 필드만 추출 가능.
     * 중첩 객체({...}), 배열([...]) 값은 null 반환.
     * JSON 파서 없이 순수 정규표현식 기반으로 구현 (JDK 8+, Android 호환).
     *
     * @param key JSON 최상위 필드명 (대소문자 구분)
     * @return 필드 값 문자열 (따옴표 제외), 필드 없거나 body가 null이면 {@code null}
     */
    public String getBodyField(String key) {
        if (body == null || body.isEmpty() || key == null) return null;
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return null;

        // 문자열 값: "key":"value" 패턴
        // 패턴: "key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"
        String quotedPattern = "\"" + escapeRegex(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
        java.util.regex.Matcher stringMatcher =
                java.util.regex.Pattern.compile(quotedPattern).matcher(trimmed);
        if (stringMatcher.find()) {
            // 이스케이프 시퀀스 복원 (\\n → \n 등)
            return unescapeJson(stringMatcher.group(1));
        }

        // 숫자/불리언/null 값: "key":value 패턴
        String scalarPattern = "\"" + escapeRegex(key) + "\"\\s*:\\s*([0-9a-zA-Z.+\\-]+)";
        java.util.regex.Matcher scalarMatcher =
                java.util.regex.Pattern.compile(scalarPattern).matcher(trimmed);
        if (scalarMatcher.find()) {
            String raw = scalarMatcher.group(1).trim();
            // 뒤에 붙은 불필요한 문자 제거 (콤마, 괄호 등)
            return raw.replaceAll("[,}\\]\\s]+$", "");
        }

        return null;
    }

    /** 정규표현식 특수문자 이스케이프 (키 이름에 점, 괄호 등 포함될 경우 대비) */
    private static String escapeRegex(String s) {
        return s.replaceAll("([\\[\\]{}()*+?.^$|\\\\])", "\\\\$1");
    }

    /** JSON 이스케이프 시퀀스 복원 (\\n, \\t, \\", \\\\ 등) */
    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n",  "\n")
                .replace("\\r",  "\r")
                .replace("\\t",  "\t");
    }

    @Override
    public String toString() {
        return "GatewayResponse{httpStatus=" + httpStatus
                + ", correlationId=" + correlationId + "}";
    }
}
