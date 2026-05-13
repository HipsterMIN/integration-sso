package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 기업회원 JPA 엔터티 — MariaDB qim.biz_member 테이블 매핑
 * 설계서 §P3-06 기업회원 전환 참조
 */
@Entity
@Table(name = "biz_member")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BizMemberJpaEntity {

    /** qim_user_id를 PK 겸 FK로 사용 (개인→기업 전환) */
    @Id
    @Column(name = "qim_user_id", length = 36, nullable = false)
    private String qimUserId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "qim_user_id",
                foreignKey = @ForeignKey(name = "fk_biz_member_qim_user"))
    private QimUserJpaEntity user;

    /** 사업자등록번호 — 숫자만 10자리 (예: "1234567890") */
    @Column(name = "biz_reg_no", length = 20, nullable = false, unique = true)
    private String bizRegNo;

    /** 법인/상호명 */
    @Column(name = "company_name", length = 200, nullable = false)
    private String companyName;

    /** 대표자명 마스킹 */
    @Column(name = "rep_name_masked", length = 100)
    private String repNameMasked;

    /** 업태 */
    @Column(name = "biz_type", length = 50)
    private String bizType;

    /** ACTIVE / SUSPENDED / CLOSED */
    @Column(name = "biz_status", length = 20, nullable = false)
    private String bizStatus;

    /** 사업자등록번호 인증 완료 시각 */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 기업회원 전환 시각 */
    @Column(name = "converted_at", nullable = false)
    private LocalDateTime convertedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (convertedAt == null) convertedAt = now;
        updatedAt = now;
        if (bizStatus == null) bizStatus = "ACTIVE";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
