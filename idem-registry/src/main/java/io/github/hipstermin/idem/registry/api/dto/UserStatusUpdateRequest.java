package io.github.hipstermin.idem.registry.api.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 사용자 상태 변경 요청 DTO
 * PATCH /api/v1/internal/users/{qimUserId}/status
 */
@Getter
@Builder
@Jacksonized
public class UserStatusUpdateRequest {
    private final String newStatus;   // ACTIVE / SUSPENDED / WITHDRAWN
    private final String changedBy;   // 변경 주체 (agencyCode or SYSTEM)
    private final String reason;      // 변경 사유
}
