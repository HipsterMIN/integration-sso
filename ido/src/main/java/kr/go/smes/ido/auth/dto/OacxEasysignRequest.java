package kr.go.smes.ido.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * OACX 간편서명 콜백 요청 DTO
 *
 * <p>OACX SDK 간편서명 완료 후 FE가 전달하는 콜백 데이터.
 * OACX JS SDK의 콜백 함수 인자({@code callbackData})를 그대로 담는 구조.
 *
 * <p><b>API 경로:</b> {@code POST /api/v1/auth/oacx/easysign}
 *
 * <p><b>FE 요청 예시:</b>
 * <pre>
 * {
 *   "fn": "authComplete",
 *   "status": "success",
 *   "res": {
 *     "resultCode": "200",
 *     "encData": "eyJhbGci..."
 *   }
 * }
 * </pre>
 *
 * <p><b>필드 유효성 규칙:</b>
 * <ul>
 *   <li>{@code fn} — 반드시 {@code "authComplete"} 이어야 함</li>
 *   <li>{@code status} — 성공 시 {@code "success"}</li>
 *   <li>{@code res.resultCode} — OACX 내부 결과 코드, {@code "200"} 이어야 성공</li>
 *   <li>{@code res.encData} — OACX SDK가 서명한 JWT 암호화 데이터 (서버에서 복호화)</li>
 * </ul>
 *
 * <p><b>OACX SDK 연동 흐름:</b>
 * <ol>
 *   <li>{@code POST /api/v1/auth/oacx/access-info} → fn, accKey, accToken 수신</li>
 *   <li>OACX JS SDK 초기화 및 간편서명 실행</li>
 *   <li>SDK 콜백 발생 → 이 DTO를 body로 {@code POST /api/v1/auth/oacx/easysign} 호출</li>
 * </ol>
 *
 * @see OacxEasysignResponse
 * @see OacxAccessInfoResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OacxEasysignRequest {

    /**
     * OACX 기능 코드 (Function Name)
     *
     * <p>간편서명 완료 콜백에서는 항상 {@code "authComplete"}.
     * 이 값이 아닐 경우 서버에서 4000 에러 반환.
     */
    private String fn;

    /**
     * 처리 상태
     *
     * <p>성공: {@code "success"}, 실패/취소: 그 외 값
     */
    private String status;

    /**
     * OACX SDK 콜백 결과 맵
     *
     * <p>OACX JS SDK가 콜백으로 전달하는 원본 결과 객체.
     * 주요 키:
     * <ul>
     *   <li>{@code resultCode} — OACX 내부 코드, {@code "200"} 이어야 성공</li>
     *   <li>{@code encData} — JWT 암호화 데이터 (서버에서 SDK를 통해 복호화)</li>
     * </ul>
     */
    private Map<String, Object> res;
}
