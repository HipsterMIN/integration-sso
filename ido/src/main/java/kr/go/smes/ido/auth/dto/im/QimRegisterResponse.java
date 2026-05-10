package kr.go.smes.ido.auth.dto.im;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Q-IM 사용자 등록 API 응답 DTO
 *
 * <p>Q-IM {@code POST /api/v1/internal/users/register} 응답을 역직렬화한다.
 *
 * <p><b>Q-IM 응답 예시 (신규 등록):</b>
 * <pre>
 * {
 *   "qimUserId": "qim-user-a1b2c3d4",
 *   "status": "ACTIVE",
 *   "isNew": true,
 *   "message": "사용자 등록 완료"
 * }
 * </pre>
 *
 * <p><b>Q-IM 응답 예시 (기존 회원):</b>
 * <pre>
 * {
 *   "qimUserId": "qim-user-a1b2c3d4",
 *   "status": "ACTIVE",
 *   "isNew": false,
 *   "message": "기존 사용자 확인"
 * }
 * </pre>
 *
 * @see kr.go.smes.ido.auth.port.ImApiOutPort#register
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QimRegisterResponse {

    /**
     * Q-IM 내부 사용자 ID
     *
     * <p>CI 해시 기반으로 Q-IM이 생성/할당한 식별자.
     * 이후 {@code QimClient.getUserStatus()}, {@code getDi()} 호출 시 사용.
     */
    @JsonProperty("qimUserId")
    private String qimUserId;

    /**
     * Q-IM 사용자 상태
     *
     * <p>가능한 값: {@code ACTIVE}, {@code SUSPENDED}, {@code WITHDRAWN}
     */
    @JsonProperty("status")
    private String status;

    /**
     * 신규 등록 여부
     *
     * <p>{@code true}: CI가 Q-IM에 처음 등록됨 (신규 사용자)<br>
     * {@code false}: CI가 이미 등록된 사용자 (기존 회원, 인증 시각만 갱신)
     */
    @JsonProperty("isNew")
    private Boolean isNew;

    /** Q-IM 처리 결과 메시지 (로그용) */
    @JsonProperty("message")
    private String message;
}
