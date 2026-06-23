package kr.go.smes.authz.infrastructure;

import kr.go.smes.authz.domain.AssignmentStatus;
import kr.go.smes.authz.domain.AuthzUserRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthzUserRoleRepository extends JpaRepository<AuthzUserRoleEntity, UUID> {

    Optional<AuthzUserRoleEntity> findByQimUserIdAndAgencyCodeAndRoleCode(
            String qimUserId, String agencyCode, String roleCode);

    /** 사용자×기관의 특정 상태 부여 목록(토큰 클레임 발급 핵심 경로). */
    List<AuthzUserRoleEntity> findByQimUserIdAndAgencyCodeAndStatus(
            String qimUserId, String agencyCode, AssignmentStatus status);

    /** 기관 스코프: 특정 역할 보유자 목록. */
    List<AuthzUserRoleEntity> findByAgencyCodeAndRoleCodeAndStatus(
            String agencyCode, String roleCode, AssignmentStatus status);
}
