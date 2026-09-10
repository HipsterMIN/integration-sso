package io.github.hipstermin.idem.registry.domain;

import io.github.hipstermin.idem.common.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Q-IM 사용자 정본 (Source of Record)
 * 설계서 10.2절 참조
 *
 * 핵심 원칙:
 *   - 이 엔터티가 전체 플랫폼에서 사용자 정본의 단일 출처
 *   - 기관이 수신하는 agencySubjectId는 Q-IM 기반 "기관향 Projection"이며 정본이 아님
 */
@Getter
@Builder
public class QimUser {

    /** Q-IM 발급 사용자 내부 ID (정본 식별자) */
    private final String qimUserId;

    /** 사용자 상태 */
    private final UserStatus status;

    /** 다중 인증수단 매핑 목록 */
    private final List<AuthMeanMapping> authMeanMappings;

    /** 사용자 속성 */
    private final UserProfile profile;

    /** 정본 버전 (optimistic-lock / eventVersion 기준) */
    private final long eventVersion;

    private final Instant createdAt;
    private final Instant updatedAt;

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isUsable() {
        return status == UserStatus.ACTIVE;
    }
}
