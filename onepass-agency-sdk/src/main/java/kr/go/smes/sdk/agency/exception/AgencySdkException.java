package kr.go.smes.sdk.agency.exception;

/**
 * OnePass Agency SDK 기본 예외
 *
 * <p>SDK에서 발생하는 모든 예외의 루트 타입.
 * {@code RuntimeException}을 상속하므로 checked exception 처리 불필요.
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 */
public class AgencySdkException extends RuntimeException {

    private final String errorCode;

    public AgencySdkException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgencySdkException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * SDK 내부 오류 코드 반환.
     *
     * @return 예: {@code "SDK_HTTP_ERROR"}, {@code "SDK_HMAC_ERROR"}, {@code "SDK_TIMEOUT"}
     */
    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "AgencySdkException[" + errorCode + "]: " + getMessage();
    }
}
