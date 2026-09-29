package io.github.hipstermin.idem.authz.infrastructure;

import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** S8-b 할당 저장소. */
public interface AuthzAssignmentRepository extends JpaRepository<AuthzAssignmentEntity, UUID> {
    Optional<AuthzAssignmentEntity> findByQimUserIdAndAgencyCode(String qimUserId, String agencyCode);
    List<AuthzAssignmentEntity> findByQimUserIdAndStatus(String qimUserId, AssignmentStatus status);
    Page<AuthzAssignmentEntity> findByStatusAndExpiresAtNotNullAndExpiresAtBefore(
            AssignmentStatus status, Instant cutoff, Pageable pageable);
    /** 1.1: 규칙이 실체화한 할당 — 규칙 비활성화 시 회수 대상 */
    List<AuthzAssignmentEntity> findByRuleIdAndStatus(UUID ruleId, AssignmentStatus status);
    /** 1.1 SCIM 아웃바운드: 한 Service 의 할당 목록(전체 동기화·재조정) — 페이지 */
    Page<AuthzAssignmentEntity> findByAgencyCodeAndStatusOrderByGrantedAtAsc(String agencyCode, AssignmentStatus status, Pageable pageable);
}
