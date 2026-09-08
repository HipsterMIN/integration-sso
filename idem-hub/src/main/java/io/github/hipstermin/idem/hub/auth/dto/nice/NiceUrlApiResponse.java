package io.github.hipstermin.idem.hub.auth.dto.nice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * NICE 인증 URL 발급 API 응답 DTO
 *
 * <p>NICE IDO 인증 서버({@code https://auth.niceid.co.kr/ido/intc/v1.0/auth/url})의
 * 표준창 URL 발급 응답을 매핑한다.
 *
 * <p><b>내부 처리 전용</b> — {@code NiceApiClient.requestAuthUrl()}에서만 사용.
 * FE에는 {@code NicePhoneAuthUrlResponse}로 가공하여 반환.
 *
 * <p><b>세션 저장 항목:</b>
 * {@code requestNo}(또는 응답의 {@code request_no})를 키로,
 * {@code transactionId}를 값으로 {@code NiceAuthSessionStore}에 저장.
 * 인증 완료 후 결과 조회 시 {@code transactionId}를 찾아 NICE API에 전달.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NiceUrlApiResponse {

    /** NICE 결과 코드 ("0000": 성공) */
    @JsonProperty("result_code")
    private String resultCode;

    /** NICE 결과 메시지 */
    @JsonProperty("result_message")
    private String resultMessage;

    /**
     * NICE가 반환하는 요청 번호
     *
     * <p>ido가 생성한 requestNo를 NICE 서버가 확인 후 반환하는 값.
     * 일반적으로 요청한 requestNo와 동일하지만 NICE 응답값 우선 사용.
     * {@code NiceAuthSessionStore}의 키로 사용.
     */
    @JsonProperty("request_no")
    private String requestNo;

    /**
     * NICE 표준창 인증 URL
     *
     * <p>FE에서 이 URL로 팝업을 열어 사용자 인증을 진행.
     * 단기 유효 URL — 발급 후 수 분 내 사용 필요.
     */
    @JsonProperty("auth_url")
    private String authUrl;

    /**
     * NICE 트랜잭션 ID
     *
     * <p>결과 조회 시 NICE API에 전달해야 하는 식별자.
     * {@code NiceAuthSessionStore}에 requestNo 키로 저장됨.
     * FE에는 노출되지 않음.
     */
    @JsonProperty("transaction_id")
    private String transactionId;
}
