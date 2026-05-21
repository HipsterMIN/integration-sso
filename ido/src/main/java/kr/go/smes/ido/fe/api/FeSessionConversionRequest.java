package kr.go.smes.ido.fe.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FE 회원전환 세션 발급 요청 DTO
 * POST /api/v1/fe-session/conversion
 *
 * <p>provisioning 완료 후 FE Step5가 호출하여 feSessionId 쿠키를 발급받는다.
 * {@link FeSessionCreateRequest}와 달리 authResultId가 없어도 되므로 별도 DTO로 분리.
 */
@Getter
@NoArgsConstructor
public class FeSessionConversionRequest {

    /** Q-IM 회원 UUID (개인회원 provisioning 결과) */
    private String mbrUuid;

    /** 기업 회원 번호 (기업회원 provisioning 결과) */
    private String entMbrNo;

    /**
     * 일회성 프로비저닝 토큰 (Redis SETNX 검증 — 재사용 방지)
     * provisioning API 응답의 provisioningToken 값
     */
    @NotBlank(message = "provisioningToken 은 필수")
    private String provisioningToken;

    /** 전환 완료 후 리다이렉트 URL */
    private String returnUrl;
}
