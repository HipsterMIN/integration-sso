package kr.go.smes.authz.infrastructure;

import kr.go.smes.authz.domain.AuthzGrantAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuthzGrantAuditRepository extends JpaRepository<AuthzGrantAuditEntity, UUID> {
}
