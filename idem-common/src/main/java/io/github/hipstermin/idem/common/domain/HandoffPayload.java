package io.github.hipstermin.idem.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

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

    /**
     * S8-b 연합 인가: 이 Service 범위의 유효 앱 역할(idem-authz 정본, 굵은 RBAC). 세밀 집행은 기관 PEP 몫.
     * 종전에는 발급 시 암호화 티켓 안에만 실려 verify 응답에는 나가지 않았다 — 이제 응답의 정식 필드다. 없으면 빈 목록.
     */
    private final List<String> roles;

    /**
     * D3: 프로파일 {@code policy.session} 이 정한 세션 정책 — 기관이 자기 세션에 같은 상한을 적용하라는 계약.
     * Idem 쪽 FE 세션에도 같은 값이 적용된다({@code FeSessionPolicyEnforcer}). 프로파일에 없으면 null.
     */
    private final SessionPolicy sessionPolicy;

    private final Instant issuedAt;
    private final Instant expiresAt;

    /** 세션 상한 — 분 단위 유휴·절대 만료와 동시 세션 수. 어느 값이든 없으면(null) 그 항목은 상한 없음. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionPolicy(Integer idleMinutes, Integer absoluteMinutes, Integer concurrent) {
        public boolean isEmpty() { return idleMinutes == null && absoluteMinutes == null && concurrent == null; }
    }

    public enum HandoffState {
        APPROVED,
        /**
         * S8-b: <b>이 Service 에 할당되지 않은</b> 인증 사용자 — 프로파일이 셀프 가입({@code policy.assignment.selfSignup})을
         * 허용할 때만 나온다(할당 필수인데 셀프 가입도 없으면 발급 단계에서 E-IDO-120 거부). 기관은 회원 가입·계정 연결로 유도한다.
         * 주체 식별자는 해석되면 함께 실린다(PAIRWISE 는 첫 발급 시 생성) — 기관이 가입 완료 후 같은 식별자로 연결할 수 있게.
         * 레거시 의미(주체 식별자를 해석할 수 없음)는 할당 정책을 켜지 않은 프로파일에서만 남아 있다.
         */
        GUEST,
        HOLD,
        REJECTED,
        MANUAL_REVIEW
    }

    @Getter
    @Builder
    public static class SubjectIdentifier {
        /** 기관향 식별자 — 값의 종류는 {@link #subjectScheme} (S4). GUEST 는 null */
        private final String agencySubjectId;
        /** {@code agencySubjectId} 의 스킴 — 기관 프로파일 {@code identity.subjectScheme} (기본 PAIRWISE_HMAC). GUEST 는 null */
        private final SubjectScheme subjectScheme;
        /** Q-IM 내부 사용자 ID (정본) */
        private final String qimUserId;
        private final UserStatus status;
        /** S8-b: 이 Service 에 할당된 사용자인가 (idem-authz 정본). authz 없는 설치는 null */
        private final Boolean assigned;
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
