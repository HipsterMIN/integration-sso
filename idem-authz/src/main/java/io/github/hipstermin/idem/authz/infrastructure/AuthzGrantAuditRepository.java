package io.github.hipstermin.idem.authz.infrastructure;

import io.github.hipstermin.idem.authz.domain.AuthzGrantAuditEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthzGrantAuditRepository extends JpaRepository<AuthzGrantAuditEntity, UUID> {
}
