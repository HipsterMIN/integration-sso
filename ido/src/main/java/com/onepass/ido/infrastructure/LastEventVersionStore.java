package com.onepass.ido.infrastructure;

/**
 * 사용자별 마지막 처리된 이벤트 버전 저장소
 * 설계서 11.5.3절 — optimistic-lock 기반 순서 역전 방지
 */
public interface LastEventVersionStore {
    Long get(String qimUserId);
    void put(String qimUserId, Long version);
}
