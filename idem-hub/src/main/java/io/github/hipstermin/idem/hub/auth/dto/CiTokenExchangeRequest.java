package io.github.hipstermin.idem.hub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * CI → ciToken 교환 요청 DTO
 *
 * <p>{@code POST /api/v1/auth/ci-token} 엔드포인트의 요청 바디.
 *
 * <p><b>보안 설계 (Q3=B)</b>:
 * CI(연계정보)는 FE에서 AES-GCM으로 암호화하여 전송한다.
 * ido BE가 복호화 후 재암호화(Q-IM 공유키)하여 Q-IM에 전달한다.
 * 응답으로는 ciToken(불투명 식별자)만 반환되며, CI 원문은 응답에 포함되지 않는다.
 *
 * <p><b>FE 호출 예시</b>:
 * <pre>
 * // aesGcm.ts로 암호화 후 beApiInstance로 ido에 전송
 * const encryptedCi = await encryptAesGcm(rawCi, process.env.AES_GCM_KEY);
 * const { data } = await beApiInstance.post('/api/v1/auth/ci-token', {
 *   encryptedCi,
 *   mbrDvsnCd: 'A101'
 * });
 * const ciToken = data.ciToken; // Q-IM이 발급한 불투명 토큰
 * </pre>
 *
 * @see io.github.hipstermin.idem.hub.auth.service.AuthService#exchangeCiToken
 */
@Getter
@NoArgsConstructor
@ToString(exclude = "encryptedCi")  // CI 로그 마스킹
public class CiTokenExchangeRequest {

    /**
     * AES-GCM으로 암호화된 CI (FE에서 암호화 후 전송)
     *
     * <p>FE의 {@code aesGcm.ts}에서 {@code AES_GCM_KEY}로 암호화한 CI 값.
     * ido는 동일한 키로 복호화 후 Q-IM 공유키로 재암호화하여 Q-IM에 전달한다.
     *
     * <p>형식: {@code Base64(IV[12bytes] || CipherText+Tag)} (AES-GCM)
     */
    @NotBlank(message = "encryptedCi는 필수입니다.")
    private String encryptedCi;

    /**
     * 회원 구분 코드
     * <ul>
     *   <li>{@code A101} — 개인회원</li>
     *   <li>{@code A102} — 기업회원</li>
     * </ul>
     */
    @NotBlank(message = "mbrDvsnCd는 필수입니다.")
    @Size(min = 4, max = 4, message = "mbrDvsnCd는 4자리여야 합니다.")
    private String mbrDvsnCd;

    /**
     * 사업자등록번호 (기업회원 A102 전용, 개인회원은 null)
     *
     * <p>기업회원인 경우 identifierHash 계산 시 CI 외 사업자번호도 활용한다.
     */
    private String bizno;
}
