package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "support_ticket_event")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SupportTicketEventEntity {

    @Id
    @Column(name = "ticket_event_id", nullable = false)
    private UUID ticketEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicketEntity ticket;

    @Column(name = "event_type_cd", nullable = false, length = 40)
    private String eventTypeCode;

    @Column(name = "visibility_cd", nullable = false, length = 20)
    private String visibilityCode;

    @Column(name = "event_cn")
    private String content;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn;

    @Column(name = "frst_regist_pnttm", nullable = false)
    private Instant frstRegistPnttm;

    @Column(name = "frst_register_id", nullable = false, length = 64)
    private String frstRegisterId;
}
