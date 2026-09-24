package io.github.hipstermin.idem.hub.kr.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NICE 본인인증 CI 확인 응답 DTO
 *
 * <p>CI 기반 회원 조회/매칭 결과를 반환한다.
 *
 * <p><b>결과 코드 정의:</b>
 * <ul>
 *   <li>{@code 2000} — 성공 (CI 확인 완료)</li>
 *   <li>{@code 4000} — 요청 파라미터 오류 (ci 누락, mbrDvsnCd 잘못됨 등)</li>
 * </ul>
 *
 * <p><b>FE 응답 예시 (성공):</b>
 * <pre>
 * {
 *   "resultCode": "2000",
 *   "resultMsg": "성공",
 *   "result": true,
 *   "indvlMbrId": "honggildong"  // 기존 회원인 경우
 * }
 * </pre>
 *
 * <p><b>FE 응답 예시 (실패):</b>
 * <pre>
 * {
 *   "resultCode": "4000",
 *   "resultMsg": "ci 누락"
 * }
 * </pre>
 *
 * <p><b>보안 정책:</b>
 * CI 자체는 응답에 포함되지 않음. 회원 조회 결과(존재 여부, ID)만 반환.
 *
 * @see CiCheckRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CiCheckResponse {

    /** 결과 코드 (2000: 성공, 4000: 파라미터 오류) */
    private String resultCode;

    /** 결과 메시지 */
    private String resultMsg;

    /**
     * CI 매칭 결과
     *
     * <p>true: Q-IM에 CI가 등록된 기존 회원 존재<br>
     * false: 처리 실패 또는 선행 인증 이력 없음 (resultCode != 2000일 때는 null)
     *
     * <p>Q-IM CI 조회는 {@link io.github.hipstermin.idem.hub.kr.auth.service.AuthService#checkNiceCi}에서
     * {@link io.github.hipstermin.idem.hub.kr.auth.port.ImApiOutPort#findByCi}를 통해 실제 구현됨 (S7-T6 완료).
     */
    private Boolean result;

    /**
     * 개인회원 아이디 (기존 회원이 CI로 조회될 때 반환)
     *
     * <p>Q-IM {@link io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo#getIndvlMbrId}에서 추출.
     * 미등록 사용자이거나 기업회원(A102)인 경우 null.
     */
    private String indvlMbrId;

    /**
     * 기업회원 아이디 (기존 기업회원이 CI로 조회될 때 반환)
     *
     * <p>Q-IM {@link io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo#getCmpMbrId}에서 추출.
     * 미등록 사용자이거나 개인회원(A101)인 경우 null.
     */
    private String cmpMbrId;
}
