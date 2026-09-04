package kr.go.smes.authz.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 인가 아웃박스 리포지토리. q-authz는 적재(INSERT)만 담당하고,
 * 상태 전이(PUBLISHED/FAILED)는 outbox-relay-batch가 JDBC로 수행한다.
 */
public interface AuthzOutboxRepository extends JpaRepository<AuthzOutboxEntity, String> {
}
