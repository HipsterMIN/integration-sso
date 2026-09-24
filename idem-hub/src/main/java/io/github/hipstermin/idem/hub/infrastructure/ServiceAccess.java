package io.github.hipstermin.idem.hub.infrastructure;

import java.util.List;

/**
 * S8-b: idem-authz 가 돌려주는 "이 사용자의 이 Service 접근 정보" — 할당 여부 + 유효 앱 역할.
 *
 * @param authzEnabled  {@code ido.q-authz.enabled=false}(authz 없는 SSO 단독 설치) 면 false — 할당을 평가할 수 없다
 * @param assigned      Service 에 할당된 사용자인가 (authz 정본)
 * @param assignmentSource 할당 출처 (CONSOLE/SCIM/API/AGENCY_PUSH/ROLE_GRANT/SELF_SIGNUP), 미할당이면 null
 * @param roles         유효 앱 역할 코드 (정렬), 없으면 빈 목록
 */
public record ServiceAccess(boolean authzEnabled, boolean assigned, String assignmentSource, List<String> roles) {

    public ServiceAccess {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** authz 비활성 설치 — 할당 불명·역할 없음. */
    public static ServiceAccess disabled() {
        return new ServiceAccess(false, false, null, List.of());
    }
}
