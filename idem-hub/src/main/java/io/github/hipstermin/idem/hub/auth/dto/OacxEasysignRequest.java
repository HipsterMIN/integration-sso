package io.github.hipstermin.idem.hub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OACX 간편서명 콜백 요청 DTO (S9-T3: Bean Validation 적용)
 *
 * <p>OACX SDK 간편서명 완료 후 FE가 전달하는 콜백 데이터.
 * OACX JS SDK의 콜백 함수 인자({@code callbackData})를 그대로 담는 구조.
 *
 * <p><b>API 경로:</b> {@code POST /api/v1/auth/oacx/easysign}
 *
 * <p><b>필드 유효성 규칙:</b>
 * <ul>
 *   <li>{@code fn} — 필수. 반드시 {@code "authComplete"} 이어야 함 (서비스 레이어에서 추가 검증)</li>
 *   <li>{@code res} — 필수 (null 불가). SDK 콜백 결과 맵</li>
 * </ul>
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
    @NotBlank(message = "fn은 필수입니다")
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
    @NotNull(message = "res는 필수입니다")
    private Map<String, Object> res;
}
