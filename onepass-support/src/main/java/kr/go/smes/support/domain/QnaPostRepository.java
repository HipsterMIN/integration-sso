package kr.go.smes.support.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QnaPostRepository extends JpaRepository<QnaPostEntity, UUID> {

    @Query("""
        select q
          from QnaPostEntity q
         where q.useYn = 'Y'
           and (:tenantId is null or q.tenantId = :tenantId)
           and (:agencyId is null or q.agencyId = :agencyId)
           and (q.secretYn = 'N' or (:writerUserId is not null and q.writerUserId = :writerUserId))
         order by q.frstRegistPnttm desc
        """)
    List<QnaPostEntity> findVisibleForUser(
            @Param("tenantId") String tenantId,
            @Param("agencyId") String agencyId,
            @Param("writerUserId") String writerUserId
    );

    @Query("""
        select q
          from QnaPostEntity q
         where q.useYn = 'Y'
           and (:tenantId is null or q.tenantId = :tenantId)
           and (:agencyId is null or q.agencyId = :agencyId)
         order by q.frstRegistPnttm desc
        """)
    List<QnaPostEntity> findAllForAdmin(
            @Param("tenantId") String tenantId,
            @Param("agencyId") String agencyId
    );

    Optional<QnaPostEntity> findByQnaIdAndUseYn(UUID qnaId, String useYn);
}

