package io.github.hipstermin.idem.authz.domain;

/** 할당 출처 (S8-b). {@link GrantSource} 와 달리 역할 부여·셀프 가입으로 생긴 할당을 구분한다. */
public enum AssignmentSource {
    CONSOLE,
    SCIM,
    API,
    AGENCY_PUSH,
    /** 역할 부여(grantRole)로 자동 생성 */
    ROLE_GRANT,
    /** 기관이 셀프 가입 완료를 보고 (프로파일 policy.assignment.selfSignup) */
    SELF_SIGNUP
}
