package io.github.hipstermin.idem.authz.infrastructure;

import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuthzUserRoleEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthzUserRoleRepository extends JpaRepository<AuthzUserRoleEntity, UUID> {

    Optional<AuthzUserRoleEntity> findByQimUserIdAndAgencyCodeAndRoleCode(
            String qimUserId, String agencyCode, String roleCode);

    /** 사용자×기관의 특정 상태 부여 목록(토큰 클레임 발급 핵심 경로). */
    List<AuthzUserRoleEntity> findByQimUserIdAndAgencyCodeAndStatus(
            String qimUserId, String agencyCode, AssignmentStatus status);

    /** 기관 스코프: 특정 역할 보유자 목록. */
    List<AuthzUserRoleEntity> findByAgencyCodeAndRoleCodeAndStatus(
            String agencyCode, String roleCode, AssignmentStatus status);

    /**
     * 만료 대상 스캔 — 지정 상태이면서 expires_at이 기준 시각 이전인 부여.
     * 만료 전이 스케줄러가 페이지 단위로 처리한다.
     */
    Page<AuthzUserRoleEntity> findByStatusAndExpiresAtNotNullAndExpiresAtBefore(
            AssignmentStatus status, Instant cutoff, Pageable pageable);
}
