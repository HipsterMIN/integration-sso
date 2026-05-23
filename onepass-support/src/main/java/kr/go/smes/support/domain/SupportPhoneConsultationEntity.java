package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "support_phone_consultation")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SupportPhoneConsultationEntity {

    @Id
    @Column(name = "phone_consultation_id", nullable = false)
    private UUID phoneConsultationId;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "call_direction_cd", nullable = false, length = 20)
    private String callDirectionCode;

    @Column(name = "call_started_at")
    private Instant callStartedAt;

    @Column(name = "call_ended_at")
    private Instant callEndedAt;

    @Column(name = "caller_phone", length = 120)
    private String callerPhone;

    @Column(name = "identity_verified_yn", nullable = false, length = 1)
    private String identityVerifiedYn;

    @Column(name = "call_summary_cn", nullable = false, length = 1000)
    private String summary;

    @Column(name = "call_detail_cn")
    private String details;

    @Column(name = "requested_action_cn", length = 1000)
    private String requestedAction;

    @Column(name = "callback_required_yn", nullable = false, length = 1)
    private String callbackRequiredYn;

    @Column(name = "callback_due_at")
    private Instant callbackDueAt;

    @Column(name = "frst_regist_pnttm", nullable = false)
    private Instant frstRegistPnttm;

    @Column(name = "frst_register_id", nullable = false, length = 64)
    private String frstRegisterId;
}
