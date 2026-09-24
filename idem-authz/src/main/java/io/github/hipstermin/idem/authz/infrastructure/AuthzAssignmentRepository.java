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
}
