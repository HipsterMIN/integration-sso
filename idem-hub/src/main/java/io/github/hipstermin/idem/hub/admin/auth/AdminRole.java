package io.github.hipstermin.idem.hub.admin.auth;

/**
 * 관리자 역할 (S7, execution-plan P1 §3.1 — 최소 3역할).
 * <ul>
 *   <li>{@code SYSTEM_ADMIN} — 전부. 관리자 계정·테넌트 관리는 이 역할만</li>
 *   <li>{@code POLICY_ADMIN} — Service(기관) 온보딩·프로파일·OIDC client·정책 시뮬레이션. 관리자 계정은 읽기만</li>
 *   <li>{@code AUDITOR}      — 읽기 전용(감사 조회 포함). 자기 비밀번호 변경·로그아웃만 쓰기</li>
 * </ul>
 */
public enum AdminRole {
    SYSTEM_ADMIN, POLICY_ADMIN, AUDITOR
}
