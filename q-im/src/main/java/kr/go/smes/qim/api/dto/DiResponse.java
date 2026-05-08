package kr.go.smes.qim.api.dto;

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
}
