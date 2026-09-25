package io.github.hipstermin.idem.gate.oidcfront;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** hub {@code /api/internal/v1/oidc-rp/access} 의 판정 — Handoff 와 같은 어휘. Redis 에 JSON 으로 캐시된다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessDecision(boolean allowed, String denyCode, String denyMessage, String rule, String serviceCode,
                             String qimUserId, String state, String agencySubjectId, String subjectScheme,
                             List<String> roles, Boolean assigned, String authLevel, String providerCode,
                             SessionPolicy sessionPolicy) {

    /** D3: 프로파일 {@code policy.session} — userinfo {@code idem_session_policy} 로 RP 에 전달된다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SessionPolicy(Integer idleMinutes, Integer absoluteMinutes, Integer concurrent) {}

    public static AccessDecision denied(String code, String message) {
        return new AccessDecision(false, code, message, null, null, null, null, null, null, null, null, null, null, null);
    }
}
