package kr.go.smes.ido.auth.dto.nice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * NICE 인증 결과 조회 API 응답 DTO
 *
 * <p>NICE IDO 인증 서버({@code https://auth.niceid.co.kr/ido/intc/v1.0/auth/result})의
 * 인증 결과 조회 응답을 매핑한다.
 *
 * <p><b>내부 처리 전용</b> — {@code NiceApiClient.requestAuthResult()}에서만 사용.
 * FE에는 {@code NicePhoneAuthResultResponse}로 가공하여 반환.
 *
 * <p><b>복호화 프로세스:</b>
 * <ol>
 *   <li>{@code integrityValue}를 HMAC-SHA256으로 검증 (무결성 확인)</li>
 *   <li>PBKDF2로 AES 키 파생 ({@code NiceCryptoUtil.deriveKey()})</li>
 *   <li>AES-GCM으로 {@code encData} 복호화 ({@code NiceCryptoUtil.aesGcmDecrypt()})</li>
 *   <li>복호화된 JSON을 Map으로 파싱 → {@code NicePhoneAuthResultResponse}로 변환</li>
 * </ol>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NiceResultApiResponse {

    /** NICE 결과 코드 ("0000": 성공) */
    @JsonProperty("result_code")
    private String resultCode;

    /** NICE 결과 메시지 */
    @JsonProperty("result_message")
    private String resultMessage;

    /**
     * AES-GCM 암호화된 인증 결과 데이터 (Base64 URL-safe 인코딩)
     *
     * <p>복호화 후 JSON 형태의 인증 결과 (name, birthdate, gender, ci, di 등).
     * 형식: {@code Base64URL(IV[16bytes] + CipherText)}
     * {@code NiceCryptoUtil.aesGcmDecrypt()}로 복호화.
     */
    @JsonProperty("enc_data")
    private String encData;

    /**
     * HMAC-SHA256 무결성 검증값 (Base64 URL-safe 인코딩)
     *
     * <p>데이터 무결성 확인용. {@code encData}에 대한 HMAC-SHA256 해시.
     * 복호화 전 반드시 검증해야 함 — 불일치 시 {@code DataIntegrityException} 발생.
     */
    @JsonProperty("integrity_value")
    private String integrityValue;
}
