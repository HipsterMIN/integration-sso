package io.github.hipstermin.idem.hub.policy.rule;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.time.Instant;
import java.util.function.Supplier;
import lombok.Builder;

/**
 * 정책 평가 컨텍스트 (S3).
 *
 * @param serviceCode   기관 코드 — {@code profile} 이 null 이면 엔진이 이 코드로 프로파일을 읽는다
 * @param profile      기관 프로파일 (정책의 단일 원천). null 허용 — 엔진이 채운다
 * @param authLevel    요청의 인증수준
 * @param providerCode 요청의 본인인증 제공자 코드 (없을 수 있음)
 * @param userStatus   사용자 상태 지연 공급자 — Q-IM 조회 비용 때문에 필요할 때만 호출. null 이면 USER_STATUS 규칙은 SKIP
 * @param now          평가 시각 (시뮬레이션에서 임의 시각 지정 가능)
 * @param correlationId 상관관계 ID
 */
@Builder(toBuilder = true)
public record PolicyContext(
        String serviceCode,
        ServiceProfile profile,
        AuthResult.AuthLevel authLevel,
        String providerCode,
        Supplier<UserStatus> userStatus,
        Instant now,
        String correlationId) {

    public PolicyContext {
        if (now == null) now = Instant.now();
    }

    /** 프로파일의 policy 블록 — 없으면 null. */
    public ServiceProfile.Policy policy() {
        return profile != null ? profile.policy() : null;
    }
}
