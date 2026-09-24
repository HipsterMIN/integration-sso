package io.github.hipstermin.idem.hub.broker.dto;

import io.github.hipstermin.idem.common.identity.SubjectScheme;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * q-sign → ido 내부 호출 DTO
 * q-sign 이 카카오 OIDC 인증 완료 후 ido 에게 FE 세션 발급을 요청할 때 사용.
 *
 * POST /api/internal/v1/oidc/complete
 *
 * <p><b>P0 수정 (v0.8.7)</b>: {@code ci} 필드 추가.
 * Q-Sign이 OIDC 인증 완료 후 본인인증 CI(연계정보)를 포함하여 전송하면
 * IdO가 {@code QimClient.findByCi()} 를 통해 실제 {@code qimUserId} 를 조회한다.
 *
 * <p><b>필드 역할</b>:
 * <ul>
 *   <li>{@code ci}            — 본인인증 CI(연계정보). Q-IM 조회 키 (필수, 운영 환경)</li>
 *   <li>{@code subjectScheme}/{@code subjectKey} — 주체 스킴·키 (S8-a; {@code ci} 는 CI 별칭). 구 memberType (INDIVIDUAL / CORPORATION). findByCi 파라미터</li>
 *   <li>{@code identifierHash} — SHA-256(sub). 레거시/로깅 목적 유지 (qimUserId 대용 사용 중단)</li>
 * </ul>
 */
@Getter
@NoArgsConstructor
public class OidcCompleteRequest {

    /** Q-Sign 이 발급한 AuthResult ID */
    @NotBlank
    private String authResultId;

    /**
     * 본인인증 CI(연계정보) — Q-IM 사용자 조회 키 (P0 수정 추가)
     *
     * <p>Q-Sign OIDC 완료 후 NICE/OACX 등 본인인증을 거쳐 발급된 CI.
     * IdO는 이 값으로 {@code QimClient.findByCi()} 를 호출하여 실제 {@code qimUserId} 를 획득한다.
     *
     * <p>CI가 없는 경우(소셜 로그인 전용, 본인인증 미수행): null 허용.
     * → null 이면 identifierHash 기반 임시 식별자로 폴백하거나 회원가입 흐름으로 전환.
     */
    private String ci;

    /**
     * S8-a: 주체 스킴 (CI·EMAIL·PHONE·EXTERNAL_SUB …). 없으면 {@code ci} 가 있을 때 CI 로 간주한다.
     */
    private String subjectScheme;

    /** S8-a: 주체 키. 없으면 {@code ci} 를 쓴다. */
    private String subjectKey;


    /** SHA-256(sub) — 레거시/로깅 목적 유지 (qimUserId 대용 사용 중단됨) */
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

    /** 스킴 중립 해석 — {@code subjectScheme} 우선, 구 {@code ci} 필드는 CI 스킴 별칭. */
    public SubjectScheme resolvedSubjectScheme() {
        if (subjectScheme != null && !subjectScheme.isBlank()) {
            return SubjectScheme.valueOf(subjectScheme.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return (ci != null && !ci.isBlank()) ? SubjectScheme.CI : null;
    }

    public String resolvedSubjectKey() {
        if (subjectKey != null && !subjectKey.isBlank()) return subjectKey;
        return ci;
    }
}
