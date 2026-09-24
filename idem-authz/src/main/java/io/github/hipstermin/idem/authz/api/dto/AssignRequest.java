package io.github.hipstermin.idem.authz.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/** S8-b 할당 요청 — 사용자를 Service(기관) 에 할당한다. */
public record AssignRequest(
        @NotBlank(message = "qimUserId는 필수입니다.")
        String qimUserId,
        @NotBlank(message = "agencyCode는 필수입니다.")
        String agencyCode,
        @NotBlank(message = "grantedBy는 필수입니다.")
        String grantedBy,
        Instant expiresAt,
        /** CONSOLE/SCIM/API/AGENCY_PUSH/SELF_SIGNUP (기본 API) */
        String source,
        String reason
) {}
