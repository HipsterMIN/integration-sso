package io.github.hipstermin.idem.authz.infrastructure;

import io.github.hipstermin.idem.authz.domain.AuthzRoleEntity;
import io.github.hipstermin.idem.authz.domain.AuthzRoleId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthzRoleRepository extends JpaRepository<AuthzRoleEntity, AuthzRoleId> {

    /** 기관 스코프 역할 카탈로그. */
    List<AuthzRoleEntity> findByAgencyCodeOrderByRoleCode(String agencyCode);
}
