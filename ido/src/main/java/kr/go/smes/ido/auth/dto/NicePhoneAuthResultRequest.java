package kr.go.smes.ido.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NICE 휴대폰 본인인증 결과 조회 요청 DTO
 *
 * <p>NICE 인증 팝업 완료 후, FE에서 인증 결과를 조회하기 위해 전달하는 식별자 쌍.
 *
 * <p><b>API 경로:</b> {@code POST /api/v1/auth/nice/phone/result}
 *
 * <p><b>FE 호출 시나리오:</b>
 * <ol>
 *   <li>FE가 {@code GET /api/v1/auth/nice/phone/url}로 인증 URL 발급</li>
 *   <li>팝업에서 NICE 인증 완료 → postMessage로 {@code web_transaction_id}, {@code request_no} 수신</li>
 *   <li>FE가 이 DTO를 body에 담아 결과 조회 요청</li>
 * </ol>
 *
 * <p><b>FE 요청 예시:</b>
 * <pre>
 * {
 *   "web_transaction_id": "WEB_20240510123456_abc123",
 *   "request_no": "REQ_20240510123456abc123def"
 * }
 * </pre>
 *
 * @see NicePhoneAuthResultResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NicePhoneAuthResultRequest {

    /**
     * NICE 팝업 완료 후 수신한 웹 트랜잭션 ID
     *
     * <p>NICE 인증 팝업이 postMessage로 전달하는 {@code web_transaction_id} 값.
     * NICE 결과 API 호출 시 필수.
     */
    @NotBlank(message = "web_transaction_id는 필수입니다")
    @Size(max = 100, message = "web_transaction_id는 100자를 초과할 수 없습니다")
    @JsonProperty("web_transaction_id")
    private String webTransactionId;

    /**
     * ido가 URL 발급 시 생성한 요청 번호
     *
     * <p>{@code GET /api/v1/auth/nice/phone/url} 응답의 {@code requestNo} 값.
     * Redis 세션 조회 키로 사용됨. 필수.
     */
    @NotBlank(message = "request_no는 필수입니다")
    @Size(max = 80, message = "request_no는 80자를 초과할 수 없습니다")
    @JsonProperty("request_no")
    private String requestNo;
}
