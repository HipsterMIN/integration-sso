package io.github.hipstermin.idem.hub.admin.auth;

/** 관리자 계정 상태 — LOCKED 는 실패 임계치, DISABLED 는 관리자가 내린 비활성. */
public enum AdminStatus {
    ACTIVE, LOCKED, DISABLED
}
