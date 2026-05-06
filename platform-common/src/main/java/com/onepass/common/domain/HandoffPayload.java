package com.onepass.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

/**
 * Handoff Verify 응답 표준 페이로드
 * 설계서 16.5절 참조 — 기관향 Projection (정본=Q-IM)
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HandoffPayload {

    private final String ticketId;
    private final String correlationId;
    private final String agencyCode;
    private final String policyVersion;
    private final HandoffState state;

    /** Subject Identifier (기관향 Projection) */
    private final SubjectIdentifier subject;

    /** 인증 컨텍스트 */
    private final AuthContext authContext;

    /** 기관별 허용 속성 필터링 결과 */
    private final Map<String, Object> attributes;

    private final Instant issuedAt;
    private final Instant expiresAt;

    public enum HandoffState {
        APPROVED,
        HOLD,
        REJECTED,
        MANUAL_REVIEW
    }

    @Getter
    @Builder
    public static class SubjectIdentifier {
        /** Q-IM 기반 기관향 식별자 */
        private final String agencySubjectId;
        /** Q-IM 내부 사용자 ID (정본) */
        private final String qimUserId;
        private final UserStatus status;
    }

    @Getter
    @Builder
    public static class AuthContext {
        private final AuthResult.AuthLevel authLevel;
        private final String providerCode;
        private final Instant authenticatedAt;
        private final String authResultId;
    }
}
