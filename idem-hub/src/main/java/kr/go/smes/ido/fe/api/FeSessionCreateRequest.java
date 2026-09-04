package kr.go.smes.ido.fe.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FE 세션 발급 요청 DTO
 * POST /api/v1/fe-session
 *
 * <p>Q-Sign 인증 완료 후 IdO 내부에서 호출.
 */
@Getter
@NoArgsConstructor
public class FeSessionCreateRequest {

    @NotBlank(message = "qimUserId 는 필수")
    private String qimUserId;

    @NotBlank(message = "authResultId 는 필수")
    private String authResultId;

    @NotBlank(message = "authLevel 은 필수")
    @Pattern(regexp = "L[123]", message = "authLevel 은 L1/L2/L3 중 하나")
    private String authLevel;

    /** 딥링크 복귀 URL (nullable — 없으면 기본 화면으로 이동) */
    private String returnUrl;
}
