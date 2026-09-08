package io.github.hipstermin.idem.authz.api.dto;

import io.github.hipstermin.idem.authz.domain.AuthzRoleEntity;
import java.time.Instant;

/** 역할 카탈로그 응답. */
public record RoleResponse(
        String agencyCode,
        String roleCode,
        String name,
        String description,
        boolean assignable,
        Instant createdAt
) {
    public static RoleResponse from(AuthzRoleEntity e) {
        return new RoleResponse(
                e.getAgencyCode(), e.getRoleCode(), e.getName(),
                e.getDescription(), e.isAssignable(), e.getCreatedAt());
    }
}
