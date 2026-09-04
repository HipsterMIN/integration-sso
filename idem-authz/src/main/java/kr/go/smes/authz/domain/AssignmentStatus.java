package kr.go.smes.authz.domain;

/** 사용자 역할 부여 상태. */
public enum AssignmentStatus {
    /** 유효 — 토큰 클레임에 포함됨. */
    ACTIVE,
    /** 회수됨 — 관리자/기관운영자/시스템이 명시적으로 박탈. */
    REVOKED,
    /** 만료됨 — expires_at 경과로 스케줄러가 전이. */
    EXPIRED
}
