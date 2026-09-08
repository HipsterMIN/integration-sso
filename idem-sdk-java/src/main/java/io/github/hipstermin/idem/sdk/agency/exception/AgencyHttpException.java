package io.github.hipstermin.idem.sdk.agency.exception;

/**
 * HTTP 통신 오류 예외
 *
 * <p>OnePass 서버가 4xx/5xx 응답을 반환하거나, 네트워크 연결 자체가 실패한 경우 발생.
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 */
public class AgencyHttpException extends AgencySdkException {

    private final int httpStatus;
    private final String responseBody;

    /**
     * HTTP 에러 응답 (4xx / 5xx)
     *
     * @param httpStatus   HTTP 상태 코드 (예: 409, 503)
     * @param responseBody 서버 응답 본문 (디버그용)
     */
    public AgencyHttpException(int httpStatus, String responseBody) {
        super("SDK_HTTP_ERROR",
                "HTTP " + httpStatus + " from OnePass server: " + truncate(responseBody, 200));
        this.httpStatus   = httpStatus;
        this.responseBody = responseBody;
    }

    /**
     * 네트워크 레벨 오류 (connect timeout, read timeout, DNS 실패 등)
     *
     * @param message 설명 메시지
     * @param cause   원인 예외
     */
    public AgencyHttpException(String message, Throwable cause) {
        super("SDK_HTTP_IO_ERROR", message, cause);
        this.httpStatus   = -1;
        this.responseBody = null;
    }

    /** @return HTTP 상태 코드, 네트워크 오류 시 -1 */
    public int getHttpStatus() {
        return httpStatus;
    }

    /** @return 서버 응답 본문 (최대 200자 truncate), 네트워크 오류 시 null */
    public String getResponseBody() {
        return responseBody;
    }

    /** HTTP 상태 코드가 서버 오류(5xx)인지 확인 */
    public boolean isServerError() {
        return httpStatus >= 500;
    }

    /** HTTP 상태 코드가 클라이언트 오류(4xx)인지 확인 */
    public boolean isClientError() {
        return httpStatus >= 400 && httpStatus < 500;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
