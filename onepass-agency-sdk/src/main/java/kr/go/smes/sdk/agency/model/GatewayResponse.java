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

    @Override
    public String toString() {
        return "GatewayResponse{httpStatus=" + httpStatus
                + ", correlationId=" + correlationId + "}";
    }
}
