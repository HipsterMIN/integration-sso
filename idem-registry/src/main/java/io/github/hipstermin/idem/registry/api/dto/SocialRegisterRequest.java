package io.github.hipstermin.idem.registry.api.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 소셜 계정 등록 요청 DTO
 * POST /api/v1/internal/users/register-social
 *
 * <p>Keycloak 소셜 로그인(카카오·네이버·PASS 등) 콜백 후
 * IdO가 Q-IM에 소셜 계정을 최초 등록할 때 사용한다.
 * CI가 없는 소셜 전용 경로이므로 {@code rawCi} 필드를 포함하지 않는다.
 *
 * <p>저장 전략:
 * <ul>
 *   <li>{@code identifierHash} = SHA-256(sub) — PII 비보관 원칙, sub 원문 비저장</li>
 *   <li>{@code providerCode} = KAKAO_OIDC / NAVER_OIDC / PASS_OIDC 등</li>
 *   <li>두 값의 복합 키로 {@code auth_mean_mapping} 레코드 생성</li>
 * </ul>
 */
@Getter
@Builder
@Jacksonized
public class SocialRegisterRequest {

    /**
     * SHA-256(Keycloak sub) — 소셜 계정 식별자 해시
     * sub 원문은 IdO에서 해시 후 파기, Q-IM은 hash만 보관
     */
    private final String identifierHash;

    /**
     * 소셜 인증 제공자 코드 (KAKAO_OIDC / NAVER_OIDC / PASS_OIDC / GPKI_OIDC 등)
     */
    private final String providerCode;

    /** 전체 흐름 추적 ID */
    private final String correlationId;
}
