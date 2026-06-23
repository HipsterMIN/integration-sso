package kr.go.smes.authz.infrastructure;

import kr.go.smes.authz.domain.AuthzRoleEntity;
import kr.go.smes.authz.domain.AuthzRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuthzRoleRepository extends JpaRepository<AuthzRoleEntity, AuthzRoleId> {

    /** 기관 스코프 역할 카탈로그. */
    List<AuthzRoleEntity> findByAgencyCodeOrderByRoleCode(String agencyCode);
}
