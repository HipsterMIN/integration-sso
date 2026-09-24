package io.github.hipstermin.idem.hub.kr.auth.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * CI → ciToken 교환 응답 DTO
 *
 * <p>{@code POST /api/v1/auth/ci-token} 엔드포인트의 응답 바디.
 *
 * <p><b>응답 코드</b>:
 * <ul>
 *   <li>{@code 2000} — 성공 (ciToken 포함)</li>
 *   <li>{@code 4000} — 파라미터 오류 (encryptedCi 누락, mbrDvsnCd 잘못됨)</li>
 *   <li>{@code 4010} — CI 복호화 실패 (AES-GCM 키 불일치 또는 형식 오류)</li>
 *   <li>{@code 5010} — Q-IM CI 등록/조회 실패</li>
 *   <li>{@code 5000} — 내부 오류</li>
 * </ul>
 *
 * <p><b>보안 설계 (Q3=B)</b>:
 * CI 원문은 이 응답에 포함되지 않는다.
 * ciToken은 Q-IM이 발급한 불투명 식별자로, FE는 이 토큰만 보유한다.
 * 이후 세션에서 Q-IM 조회 시 ciToken을 사용하면 CI 원문 없이 회원을 식별할 수 있다.
 *
 * @see io.github.hipstermin.idem.hub.kr.auth.service.AuthService#exchangeCiToken
 */
@Getter
@Builder
public class CiTokenExchangeResponse {

    /**
     * 응답 코드
     * <ul>
     *   <li>{@code 2000} — 성공</li>
     *   <li>{@code 4000} — 파라미터 오류</li>
     *   <li>{@code 4010} — CI 복호화 실패</li>
     *   <li>{@code 5010} — Q-IM 연동 실패</li>
     *   <li>{@code 5000} — 내부 오류</li>
     * </ul>
     */
    private final String resultCode;

    /** 응답 메시지 */
    private final String resultMsg;

    /**
     * Q-IM이 발급한 CI 기반 불투명 토큰 (성공 시만 포함)
     *
     * <p>FE는 이 토큰을 세션에 보관하고, 이후 Q-IM 조회 시 사용한다.
     * CI 원문 대신 이 토큰을 사용하므로 FE에 CI가 노출되지 않는다.
     */
    private final String ciToken;

    /**
     * Q-IM 내부 사용자 ID (디버깅/감사 목적, 선택적 포함)
     *
     * <p>Q-IM에서 이미 등록된 사용자인 경우 기존 qimUserId를 반환.
     * 신규 등록인 경우 새로 발급된 qimUserId를 반환.
     * 운영 환경에서 FE에 노출하지 않으려면 {@code null}로 설정.
     */
    private final String qimUserId;
}
