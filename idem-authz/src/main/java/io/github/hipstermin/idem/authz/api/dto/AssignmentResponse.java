package io.github.hipstermin.idem.authz.api.dto;

import io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity;
import java.time.Instant;

/** S8-b 할당 응답. */
public record AssignmentResponse(
        String qimUserId,
        String agencyCode,
        String status,
        String source,
        Instant grantedAt,
        String grantedBy,
        Instant expiresAt
) {
    public static AssignmentResponse from(AuthzAssignmentEntity e) {
        return new AssignmentResponse(e.getQimUserId(), e.getAgencyCode(), e.getStatus().name(),
                e.getSource().name(), e.getGrantedAt(), e.getGrantedBy(), e.getExpiresAt());
    }
}
