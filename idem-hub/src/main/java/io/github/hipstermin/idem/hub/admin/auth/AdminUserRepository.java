package io.github.hipstermin.idem.hub.admin.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUserEntity, String> {
    Optional<AdminUserEntity> findByUsername(String username);
    long countByRoleAndStatus(AdminRole role, AdminStatus status);
    List<AdminUserEntity> findAllByOrderByUsernameAsc();
}
