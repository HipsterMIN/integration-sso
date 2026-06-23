package kr.go.smes.authz.domain;

/** 역할 부여 출처 — 감사/추적용. */
public enum GrantSource {
    /** onepass-admin 관리 콘솔(PAP). */
    CONSOLE,
    /** SCIM 2.0 Groups 동기화. */
    SCIM,
    /** 내부 API 직접 호출(ido 등). */
    API,
    /** 기관이 자기 권한을 역방향 등록(push). */
    AGENCY_PUSH
}
