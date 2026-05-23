package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "support_ticket")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SupportTicketEntity {

    @Id
    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "channel_cd", nullable = false, length = 30)
    private String channelCode;

    @Column(name = "ticket_stts_cd", nullable = false, length = 30)
    private String statusCode;

    @Column(name = "priority_cd", nullable = false, length = 30)
    private String priorityCode;

    @Column(name = "category_cd", length = 80)
    private String categoryCode;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "agency_id", length = 64)
    private String agencyId;

    @Column(name = "ticket_ttl", nullable = false, length = 200)
    private String title;

    @Column(name = "requester_nm", length = 80)
    private String requesterName;

    @Column(name = "requester_phone", length = 120)
    private String requesterPhone;

    @Column(name = "requester_email", length = 120)
    private String requesterEmail;

    @Column(name = "assigned_agent_id", length = 64)
    private String assignedAgentId;

    @Column(name = "linked_qna_id")
    private UUID linkedQnaId;

    @Column(name = "callback_required_yn", nullable = false, length = 1)
    private String callbackRequiredYn;

    @Column(name = "callback_due_at")
    private Instant callbackDueAt;

    @Column(name = "first_response_due_at")
    private Instant firstResponseDueAt;

    @Column(name = "resolution_due_at")
    private Instant resolutionDueAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn;

    @Column(name = "frst_regist_pnttm", nullable = false)
    private Instant frstRegistPnttm;

    @Column(name = "frst_register_id", nullable = false, length = 64)
    private String frstRegisterId;

    @Column(name = "last_updt_pnttm", nullable = false)
    private Instant lastUpdtPnttm;

    @Column(name = "last_updusr_id", nullable = false, length = 64)
    private String lastUpdusrId;

    @Builder.Default
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL)
    @OrderBy("frstRegistPnttm ASC")
    private List<SupportTicketEventEntity> events = new ArrayList<>();
}
