package kr.go.smes.ido.broker.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * q-sign → ido 내부 호출 DTO
 * q-sign 이 카카오 OIDC 인증 완료 후 ido 에게 FE 세션 발급을 요청할 때 사용.
 *
 * POST /api/internal/v1/oidc/complete
 */
@Getter
@NoArgsConstructor
public class OidcCompleteRequest {

    /** Q-Sign 이 발급한 AuthResult ID */
    @NotBlank
    private String authResultId;

    /** SHA-256(sub) — Q-IM 조회 키 */
    @NotBlank
    private String identifierHash;

    /** 인증 수준 (L1 / L2 / L3) */
    @NotBlank
    private String authLevel;

    /** 인증 수단 코드 (KAKAO_OIDC 등) */
    @NotBlank
    private String providerCode;

    /** 흐름 추적 ID */
    @NotBlank
    private String correlationId;

    /** 인증 완료 후 이동할 기관 returnUrl */
    private String returnUrl;
}
