package io.github.hipstermin.idem.authz.api.dto;

import java.util.List;

/**
 * S8-b: hub 가 발급 시 한 번에 읽는 접근 정보 — 할당 여부 + 유효 역할.
 * {@code effective-roles} 는 호환용으로 남긴다.
 */
public record ServiceAccessResponse(
        String qimUserId,
        String agencyCode,
        boolean assigned,
        String assignmentSource,
        List<String> roles
) {}
