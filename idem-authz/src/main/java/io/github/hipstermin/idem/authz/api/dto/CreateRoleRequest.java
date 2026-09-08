package io.github.hipstermin.idem.authz.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 역할 카탈로그 생성 요청. */
public record CreateRoleRequest(
        @NotBlank(message = "agencyCode는 필수입니다.")
        String agencyCode,
        @NotBlank(message = "roleCode는 필수입니다.")
        String roleCode,
        String name,
        String description
) {}
