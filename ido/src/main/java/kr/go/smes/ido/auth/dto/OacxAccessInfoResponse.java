package kr.go.smes.ido.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OACX 전자서명 접근 정보 응답 DTO
 *
 * <p>OACX SDK를 통해 발급한 접근키/접근토큰을 FE에 반환한다.
 * FE는 이 값으로 OACX 간편서명 SDK를 초기화한다.
 *
 * <p><b>API 경로:</b> {@code POST /api/v1/auth/oacx/access-info}
 *
 * <p><b>Request Body:</b> {@code "simpleAuth"} (plain string, Content-Type: application/json)
 *
 * <p><b>결과 코드 정의:</b>
 * <ul>
 *   <li>{@code 2000} — 성공 (fn, accKey, accToken 포함)</li>
 *   <li>{@code 5001} — OACX 접근정보 조회 실패</li>
 * </ul>
 *
 * <p><b>FE 성공 응답 예시:</b>
 * <pre>
 * {
 *   "resultCode": "2000",
 *   "resultMsg": "성공",
 *   "fn": "simpleAuth",
 *   "accKey": "...",
 *   "accToken": "..."
 * }
 * </pre>
 *
 * <p><b>FE 사용 방법:</b>
 * <pre>
 * // 1. 접근 정보 발급
 * const { fn, accKey, accToken } = await getOacxAccessInfo("simpleAuth");
 *
 * // 2. OACX SDK 초기화 (OACX 제공 JS SDK 사용)
 * OACXsdk.init({ fn, accKey, accToken });
 *
 * // 3. 간편서명 실행 (팝업)
 * OACXsdk.open((callbackData) => {
 *   // POST /api/v1/auth/oacx/easysign 으로 callbackData 전달
 * });
 * </pre>
 *
 * @see OacxEasysignRequest
 * @see OacxEasysignResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OacxAccessInfoResponse {

    /** 결과 코드 (2000: 성공, 5001: OACX 오류) */
    private String resultCode;

    /** 결과 메시지 */
    private String resultMsg;

    /**
     * OACX 기능 코드 (Function Name)
     *
     * <p>요청 시 전달한 fn 값을 그대로 반환. OACX SDK 초기화에 사용.
     * 현재 사용 값: {@code "simpleAuth"}
     */
    private String fn;

    /**
     * OACX 접근키 (Access Key)
     *
     * <p>OACX SDK가 요구하는 접근 인증 키. OACX SDK 초기화 필수.
     */
    private String accKey;

    /**
     * OACX 접근 토큰 (Access Token)
     *
     * <p>OACX SDK가 요구하는 접근 인증 토큰. OACX SDK 초기화 필수.
     * 단기 유효 토큰 — 발급 후 즉시 사용해야 함.
     */
    private String accToken;
}
