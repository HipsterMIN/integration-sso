package io.github.hipstermin.idem.common.crypto;

/** {@link CryptoProvider} 연산 실패 (알고리즘 미지원·키 형식 오류·복호화 실패 등). */
public class CryptoException extends RuntimeException {

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
