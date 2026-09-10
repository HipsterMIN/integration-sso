package io.github.hipstermin.idem.authz.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 사용자 역할 부여 요청.
 *
 * @param qimUserId  대상 사용자(q-im 정체성)
 * @param agencyCode 기관 코드(테넌트)
 * @param roleCode   부여할 역할 코드(해당 기관 카탈로그에 존재해야 함)
 * @param grantedBy  부여 주체(관리자/기관운영자/시스템 식별자)
 * @param expiresAt  한시 권한 만료 시각(NULL=무기한)
 * @param source     부여 출처(CONSOLE/SCIM/API/AGENCY_PUSH) — 미지정 시 API
 * @param reason     감사 사유
 */
public record GrantRoleRequest(
        @NotBlank(message = "qimUserId는 필수입니다.")
        String qimUserId,
        @NotBlank(message = "agencyCode는 필수입니다.")
        String agencyCode,
        @NotBlank(message = "roleCode는 필수입니다.")
        String roleCode,
        @NotBlank(message = "grantedBy는 필수입니다.")
        String grantedBy,
        Instant expiresAt,
        String source,
        String reason
) {}
