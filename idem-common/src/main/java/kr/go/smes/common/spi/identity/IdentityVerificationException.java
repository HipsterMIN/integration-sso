package kr.go.smes.common.spi.identity;

/**
 * 본인인증 실패. 제공자 구현이 벤더 오류·검증 실패·만료 등을 이 예외로 통일해 던진다.
 * 코어(컨트롤러)는 이를 플랫폼 오류 응답으로 변환한다.
 */
public class IdentityVerificationException extends RuntimeException {

    private final String providerCode;
    private final String reasonCode;

    public IdentityVerificationException(String providerCode, String reasonCode, String message) {
        this(providerCode, reasonCode, message, null);
    }

    public IdentityVerificationException(String providerCode, String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
        this.reasonCode = reasonCode;
    }

    public String getProviderCode() { return providerCode; }

    /** 제공자별 사유 코드 (예: NICE resultCode). 없으면 null. */
    public String getReasonCode() { return reasonCode; }
}
