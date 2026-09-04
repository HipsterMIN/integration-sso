package kr.go.smes.authz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 인가 부여/회수 append-only 감사 엔티티. */
@Entity
@Table(name = "authz_grant_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthzGrantAuditEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 16)
    private AuditEvent event;

    @Column(name = "qim_user_id", length = 36)
    private String qimUserId;

    @Column(name = "agency_code", nullable = false, length = 50)
    private String agencyCode;

    @Column(name = "role_code", length = 64)
    private String roleCode;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "actor_ip", length = 45)
    private String actorIp;

    @Column(name = "reason")
    private String reason;

    @Column(name = "correlation_id", length = 36)
    private String correlationId;

    @Column(name = "at", nullable = false)
    private Instant at;
}
