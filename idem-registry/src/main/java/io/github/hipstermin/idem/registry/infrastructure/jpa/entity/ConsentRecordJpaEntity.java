package io.github.hipstermin.idem.registry.infrastructure.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/**
 * 개인정보 동의 기록 JPA 엔터티 — {@code qim.consent_record} 테이블 매핑
 *
 * <p>사용자가 특정 {@link ConsentVersionJpaEntity}에 동의/철회한 이력을 저장.
 * AGREED / WITHDRAWN 상태로 추적하며, 새로운 동의 시 신규 레코드를 INSERT(이력 보존).
 *
 * <p>P2 §12.3 개인정보 동의 기록 / GDPR §7 동의 요건 참조.
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
@Entity
@Table(name = "consent_record",
       indexes = {
           @Index(name = "idx_consent_record_user_type",
                  columnList = "qim_user_id, consent_type"),
           @Index(name = "idx_consent_record_user_version",
                  columnList = "qim_user_id, version_id")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsentRecordJpaEntity {

    @Id
    @Column(name = "record_id", length = 36, nullable = false)
    private String recordId;

    /** 동의한 사용자 ID */
    @Column(name = "qim_user_id", length = 36, nullable = false)
    private String qimUserId;

    /** 동의 버전 ID (consent_version.version_id FK) */
    @Column(name = "version_id", length = 36, nullable = false)
    private String versionId;

    /**
     * 동의 유형 (consent_version.consent_type 비정규화 복사 — 조회 편의)
     * TERMS_OF_SERVICE / PRIVACY_POLICY / THIRD_PARTY_SHARE / MARKETING
     */
    @Column(name = "consent_type", length = 50, nullable = false)
    private String consentType;

    /**
     * 동의 상태
     * AGREED    — 동의 완료
     * WITHDRAWN — 동의 철회 (선택 동의만 허용)
     */
    @Column(name = "consent_status", length = 20, nullable = false)
    @Builder.Default
    private String consentStatus = "AGREED";

    /** 동의 경로 (WEB_SIGNUP / APP_SIGNUP / RE_CONSENT / AGENCY_API 등) */
    @Column(name = "agreed_via", length = 50)
    private String agreedVia;

    /** 동의 IP (감사 추적) */
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    /** 동의 일시 */
    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    /** 철회 일시 (consentStatus=WITHDRAWN 시 설정) */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    /** 철회 사유 */
    @Column(name = "withdrawal_reason", length = 200)
    private String withdrawalReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (agreedAt == null) agreedAt = Instant.now();
        if (consentStatus == null) consentStatus = "AGREED";
    }
}
