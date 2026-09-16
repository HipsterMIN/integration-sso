package io.github.hipstermin.idem.plugin.niceoacx.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * NICE 인증 서버 Access Token 발급 API 응답 DTO
 *
 * <p>NICE IDO 인증 서버({@code https://auth.niceid.co.kr/ido/intc/v1.0/auth/token})의
 * Access Token 발급 응답을 매핑한다.
 *
 * <p><b>내부 처리 전용</b> — {@code NiceApiClient.fetchAccessToken()}에서만 사용.
 * FE에 직접 노출되지 않음.
 *
 * <p><b>NICE 토큰 만료 처리:</b>
 * {@code expiresIn}은 epoch milliseconds 값이며 {@code NiceTokenStore}에 저장 후
 * 만료 60초 전부터 자동 재발급 로직이 동작함.
 *
 * <p><b>암호화 키 파생 재료:</b>
 * {@code ticket}과 {@code iterators}는 PBKDF2 키 파생에 사용되므로
 * {@code NiceTokenStore}에 함께 저장해야 함.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NiceTokenApiResponse {

    /** NICE 결과 코드 ("0000": 성공) */
    @JsonProperty("result_code")
    private String resultCode;

    /** NICE 결과 메시지 */
    @JsonProperty("result_message")
    private String resultMessage;

    /**
     * Access Token
     *
     * <p>이후 URL 발급, 결과 조회 API의 Bearer 토큰으로 사용.
     */
    @JsonProperty("access_token")
    private String accessToken;

    /**
     * 토큰 만료 시각 (epoch milliseconds)
     *
     * <p>NICE는 만료 시각을 epoch millis로 반환.
     * {@code NiceTokenStore}에 저장하여 유효성 검사에 사용.
     */
    @JsonProperty("expires_in")
    private long expiresIn;

    /** 토큰 타입 (보통 "Bearer") */
    @JsonProperty("token_type")
    private String tokenType;

    /**
     * PBKDF2 반복 횟수
     *
     * <p>인증 결과 복호화 키 파생 시 PBKDF2WithHmacSHA256 반복 횟수로 사용.
     * {@code NiceCryptoUtil.deriveKey()} 호출 시 전달.
     */
    private int iterators;

    /**
     * NICE 티켓 (암호화 키 파생 입력값)
     *
     * <p>인증 결과 복호화 키 파생 시 PBKDF2 패스워드로 사용.
     * {@code NiceCryptoUtil.deriveKey()} 호출 시 전달.
     * <b>민감 정보:</b> 로그에 출력하지 말 것.
     */
    private String ticket;
}
