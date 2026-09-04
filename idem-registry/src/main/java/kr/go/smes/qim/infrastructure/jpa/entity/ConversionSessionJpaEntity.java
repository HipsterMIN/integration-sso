package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 통합계정 전환 세션 JPA 엔터티 — {@code qim.conversion_session} 테이블 매핑
 *
 * <p>CI 기반으로 68개 유관 시스템의 기존 회원을 찾아
 * 단일 Q-IM 통합계정(UUID)으로 연결하는 상태 기계.
 *
 * <p><b>상태 전이</b>:
 * <pre>
 * INITIATED ──→ MEMBERS_FETCHED ──→ ACCOUNT_SELECTED ──→ LINKING ──→ COMPLETED
 *     │               │                    │                 │
 *     └───────────────┴────────────────────┴─────────────────┴──→ CANCELLED
 *                                                                  EXPIRED (TTL 만료)
 * </pre>
 *
 * <p>P2 §12.1 통합계정 UUID 생성 및 연결 대상 선택 흐름 참조.
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
@Entity
@Table(name = "conversion_session",
       indexes = {
           @Index(name = "idx_conv_session_qim_user",  columnList = "qim_user_id"),
           @Index(name = "idx_conv_session_status",    columnList = "status"),
           @Index(name = "idx_conv_session_expires",   columnList = "expires_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversionSessionJpaEntity {

    @Id
    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    /** 세션 소유 사용자 (Q-IM 신규 UUID) */
    @Column(name = "qim_user_id", length = 36, nullable = false)
    private String qimUserId;

    /**
     * 세션 상태
     * INITIATED       — 전환 시작 (CI 확보 완료)
     * MEMBERS_FETCHED — 유관 시스템 회원 목록 조회 완료
     * ACCOUNT_SELECTED— 사용자가 연결 대상 계정 선택 완료
     * LINKING         — 계정 연결 진행 중
     * COMPLETED       — 전환 완료
     * CANCELLED       — 사용자 취소
     * EXPIRED         — TTL 만료 (미처리)
     */
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private String status = "INITIATED";

    /**
     * 유관 시스템 회원 조회 결과 JSON
     * [{agencyCode, memberId, memberName(masked), linkedAt}, ...]
     * MEMBERS_FETCHED 단계에서 설정.
     */
    @Column(name = "candidate_members_json", columnDefinition = "JSON")
    private String candidateMembersJson;

    /**
     * 사용자가 선택한 연결 대상 기관 코드 목록 JSON (["GOV_A", "GOV_B"])
     * ACCOUNT_SELECTED 단계에서 설정.
     */
    @Column(name = "selected_agency_codes_json", columnDefinition = "JSON")
    private String selectedAgencyCodesJson;

    /**
     * 연결 완료된 기관 코드 목록 JSON
     * COMPLETED 단계에서 설정.
     */
    @Column(name = "linked_agency_codes_json", columnDefinition = "JSON")
    private String linkedAgencyCodesJson;

    /** 취소/만료 사유 */
    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    /** 세션 만료 일시 (기본 TTL: 30분) */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = "INITIATED";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
