package kr.go.smes.qim.crypto;

/**
 * CI(연계정보) 암호화·복호화 서비스 인터페이스
 *
 * <p>AES-256-GCM 키 버전 관리 지원:
 * <ul>
 *   <li>암호화 출력 형식: {@code v{n}.{base64url(iv)}.{base64url(ciphertext+tag)}}</li>
 *   <li>복호화 시 버전 접두사 파싱 → 해당 버전 키 사용</li>
 *   <li>신규 암호화는 항상 현재 활성 키 버전 사용</li>
 * </ul>
 *
 * <p><b>환경변수</b>:
 * <ul>
 *   <li>{@code QIM_CI_AES_KEY_V1} — v1 키 (Base64, 32바이트)</li>
 *   <li>{@code QIM_CI_AES_KEY_V2} — v2 키 (로테이션 시 추가)</li>
 *   <li>{@code QIM_CI_CURRENT_KEY_VERSION} — 현재 활성 버전 (기본: v1)</li>
 * </ul>
 */
public interface CiCryptoService {

    /**
     * CI 암호화
     *
     * @param rawCi 평문 CI (88자 Base64 문자열)
     * @return 버전 접두사 포함 암호문 {@code v1.{iv}.{ciphertext}}
     */
    String encrypt(String rawCi);

    /**
     * CI 복호화
     *
     * @param encryptedCi 버전 접두사 포함 암호문
     * @return 평문 CI
     */
    String decrypt(String encryptedCi);

    /**
     * 암호화된 값인지 확인 (버전 접두사 존재 여부)
     */
    boolean isEncrypted(String value);
}
