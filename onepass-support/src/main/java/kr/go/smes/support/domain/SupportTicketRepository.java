package kr.go.smes.support.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketRepository extends JpaRepository<SupportTicketEntity, UUID> {

    @Query("""
        select t
          from SupportTicketEntity t
         where t.useYn = 'Y'
           and (:statusCode is null or t.statusCode = :statusCode)
           and (:channelCode is null or t.channelCode = :channelCode)
           and (:assignedAgentId is null or t.assignedAgentId = :assignedAgentId)
           and (:tenantId is null or t.tenantId = :tenantId)
           and (:agencyId is null or t.agencyId = :agencyId)
         order by t.lastUpdtPnttm desc
        """)
    List<SupportTicketEntity> findBackofficeQueue(
            @Param("statusCode") String statusCode,
            @Param("channelCode") String channelCode,
            @Param("assignedAgentId") String assignedAgentId,
            @Param("tenantId") String tenantId,
            @Param("agencyId") String agencyId
    );

    Optional<SupportTicketEntity> findByTicketIdAndUseYn(UUID ticketId, String useYn);

    Optional<SupportTicketEntity> findByLinkedQnaIdAndUseYn(UUID linkedQnaId, String useYn);
}
