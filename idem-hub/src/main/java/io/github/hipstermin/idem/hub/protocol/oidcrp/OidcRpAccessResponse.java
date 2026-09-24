package io.github.hipstermin.idem.hub.protocol.oidcrp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import java.util.List;

/**
 * 표준 OIDC 경로의 접근 판정 — Handoff verify 응답과 같은 어휘({@code state}·{@code agencySubjectId}·{@code roles}·{@code assigned}).
 * 거부여도 HTTP 200 으로 내려가며 gate 가 OAuth 오류로 바꾼다. {@code denyCode} 는 플랫폼 코드(E-IDO-1xx).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OidcRpAccessResponse(boolean allowed, String denyCode, String denyMessage, String rule,
                                   String serviceCode, String qimUserId, HandoffPayload.HandoffState state,
                                   String agencySubjectId, String subjectScheme, List<String> roles, Boolean assigned,
                                   String authLevel, String providerCode) {

    public static OidcRpAccessResponse denied(PlatformErrorCode code, String message, String rule, String serviceCode) {
        return new OidcRpAccessResponse(false, code.getCode(), message, rule, serviceCode,
                null, null, null, null, null, null, null, null);
    }
}
