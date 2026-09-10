package io.github.hipstermin.idem.hub.auth.dto.im;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Q-IM CI 조회 결과 DTO
 *
 * <p>Q-IM {@code POST /api/v1/internal/users/find-by-ci} 응답을 역직렬화한다.
 *
 * <p><b>Q-IM 응답 예시:</b>
 * <pre>
 * {
 *   "qimUserId": "qim-user-a1b2c3d4",
 *   "status": "ACTIVE",
 *   "memberType": "A101",
 *   "indvlMbrId": "honggildong",
 *   "cmpMbrId": null
 * }
 * </pre>
 *
 * <p><b>보안 주의:</b>
 * CI 자체는 이 응답에 포함되지 않는다.
 * Q-IM은 CI 해시로 사용자를 식별하며, 원본 CI는 Q-IM 내부에만 저장(AES-256-GCM 암호화).
 *
 * @see io.github.hipstermin.idem.hub.auth.port.ImApiOutPort#findByCi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QimMemberInfo {

    /**
     * Q-IM 내부 사용자 ID
     */
    @JsonProperty("qimUserId")
    private String qimUserId;

    /**
     * Q-IM 사용자 상태 ({@code ACTIVE}, {@code SUSPENDED}, {@code WITHDRAWN})
     */
    @JsonProperty("status")
    private String status;

    /**
     * 회원 유형 코드 ({@code A101}: 개인, {@code A102}: 기업)
     */
    @JsonProperty("memberType")
    private String memberType;

    /**
     * 개인회원 아이디 (개인 회원인 경우 — {@code memberType=A101})
     *
     * <p>{@code AuthService.checkNiceCi()} 응답의 {@code indvlMbrId} 필드에 매핑됨.
     */
    @JsonProperty("indvlMbrId")
    private String indvlMbrId;

    /**
     * 기업회원 아이디 (기업 회원인 경우 — {@code memberType=A102})
     *
     * <p>{@code AuthService.checkNiceCi()} 응답의 {@code cmpMbrId} 필드에 매핑됨.
     */
    @JsonProperty("cmpMbrId")
    private String cmpMbrId;
}
