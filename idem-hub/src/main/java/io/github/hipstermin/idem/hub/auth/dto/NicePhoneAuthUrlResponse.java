package io.github.hipstermin.idem.hub.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NICE 휴대폰 본인인증 URL 발급 응답 DTO
 *
 * <p>NICE 통합인증 표준창 URL을 발급하여 FE에 반환한다.
 * FE는 이 URL로 팝업을 열어 사용자가 NICE 인증을 진행하도록 한다.
 *
 * <p><b>API 경로:</b> {@code GET /api/v1/auth/nice/phone/url?returnUrl=...}
 *
 * <p><b>결과 코드 정의:</b>
 * <ul>
 *   <li>{@code 2000} — 성공 (authUrl, requestNo 포함)</li>
 *   <li>{@code 5000} — 내부 처리 오류</li>
 *   <li>{@code 5001} — NICE 인증 URL 발급 실패</li>
 * </ul>
 *
 * <p><b>FE 성공 응답 예시:</b>
 * <pre>
 * {
 *   "resultCode": "2000",
 *   "resultMsg": "성공",
 *   "authUrl": "https://nice.checkplus.co.kr/...",
 *   "requestNo": "REQ_20240510123456abc123def"
 * }
 * </pre>
 *
 * <p><b>FE 사용 방법:</b>
 * <pre>
 * // 1. URL 발급
 * const { authUrl, requestNo } = await getNicePhoneAuthUrl(returnUrl);
 *
 * // 2. 팝업 오픈
 * const popup = window.open(authUrl, 'niceAuth', 'width=500,height=600');
 *
 * // 3. postMessage 수신 (팝업 → 부모창)
 * window.addEventListener('message', (event) => {
 *   const { web_transaction_id, request_no } = event.data;
 *   // 4. 결과 조회 → POST /api/v1/auth/nice/phone/result
 * });
 * </pre>
 *
 * @see NicePhoneAuthResultRequest
 * @see NicePhoneAuthResultResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NicePhoneAuthUrlResponse {

    /** 결과 코드 (2000: 성공, 5000/5001: 오류) */
    private String resultCode;

    /** 결과 메시지 */
    private String resultMsg;

    /**
     * NICE 통합인증 표준창 URL
     *
     * <p>성공 시에만 포함. FE에서 이 URL로 팝업을 열어 인증 진행.
     * 일정 시간(수 분) 후 만료되므로 발급 즉시 사용해야 함.
     */
    private String authUrl;

    /**
     * 요청 번호 (request_no)
     *
     * <p>ido 내부에서 생성한 고유 요청 식별자.
     * 형식: {@code REQ_yyyyMMddHHmmss + 12자리 UUID 일부}
     * 인증 완료 후 결과 조회 시 필수 ({@code POST /api/v1/auth/nice/phone/result}).
     * FE는 이 값을 저장해두었다가 팝업 완료 후 결과 조회 시 전달해야 함.
     */
    private String requestNo;
}
