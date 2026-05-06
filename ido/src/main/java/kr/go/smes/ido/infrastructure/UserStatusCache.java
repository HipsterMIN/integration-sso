package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.domain.UserStatus;

import java.util.Optional;

/**
 * Q-IM 사용자 상태 캐시 인터페이스 (IdO 내부 캐시)
 * 설계서 11.5절 참조
 * - TTL ≤5분 (성능 최적화 용도, 정본 아님)
 * - Q-IM 변경 이벤트 수신 시 즉시 무효화
 * - 캐시 키: qimUserId
 */
public interface UserStatusCache {
    Optional<UserStatus> get(String qimUserId);
    void put(String qimUserId, UserStatus status);
    void invalidate(String qimUserId);
}
