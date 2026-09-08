package io.github.hipstermin.idem.hub.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Handoff Ticket 발급 요청 DTO
 *
 * <p><b>v2.4.0 P1 보안 수정</b>: {@code qimUserId} 필드 제거.
 * <p>기존: FE가 request body에 {@code qimUserId}를 직접 전달 → 위변조 가능.
 * <p>변경: 서버가 {@code Fe-Session-Id} HttpOnly 쿠키로 {@link io.github.hipstermin.idem.hub.fe.session.FeSession}을
 * 조회하여 {@code qimUserId}를 서버 측에서 추출 (사용자 임의 조작 불가).
 */
@Getter
@NoArgsConstructor
public class HandoffIssueRequest {
    @NotBlank private String agencyCode;
    // qimUserId 제거 — v2.4.0 P1: 서버 측 feSession 쿠키 조회로 변경 (HandoffController 참고)
    @NotBlank private String authResultId;
    @NotBlank private String authLevel;
    @NotBlank private String providerCode;
    private String callbackUrl;
}
