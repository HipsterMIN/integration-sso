package io.github.hipstermin.idem.registry.api.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * DI 조회/생성 응답 DTO
 * GET /api/v1/internal/users/{qimUserId}/di?agencyCode=
 */
@Getter
@Builder
public class DiResponse {
    private final String qimUserId;
    private final String agencyCode;
    private final String di;
    private final boolean isNew; // true = 신규 생성
    /** S4: 이 값의 스킴 — 항상 PAIRWISE_HMAC (기관별 가명) */
    @lombok.Builder.Default
    private final String scheme = "PAIRWISE_HMAC";
}
