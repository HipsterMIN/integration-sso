package kr.go.smes.authz.api.dto;

import kr.go.smes.authz.domain.AuthzUserRoleEntity;

import java.time.Instant;

/** 사용자 역할 부여 응답. */
public record UserRoleResponse(
        String id,
        String qimUserId,
        String agencyCode,
        String roleCode,
        String status,
        Instant grantedAt,
        String grantedBy,
        Instant expiresAt,
        String source
) {
    public static UserRoleResponse from(AuthzUserRoleEntity e) {
        return new UserRoleResponse(
                e.getId().toString(), e.getQimUserId(), e.getAgencyCode(), e.getRoleCode(),
                e.getStatus().name(), e.getGrantedAt(), e.getGrantedBy(),
                e.getExpiresAt(), e.getSource().name());
    }
}
