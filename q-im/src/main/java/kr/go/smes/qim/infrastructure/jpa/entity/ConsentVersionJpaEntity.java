package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 동의 버전 JPA 엔터티 — {@code qim.consent_version} 테이블 매핑
 *
 * <p>관리자가 배포하는 동의 약관의 버전 정보.
 * 사용자는 항상 최신 {@code ACTIVE} 버전에 동의해야 한다.
 *
 * <p>P2 §12.3 개인정보 동의 기록 참조.
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
@Entity
@Table(name = "consent_version",
       indexes = {
           @Index(name = "idx_consent_ver_type_status",
                  columnList = "consent_type, status"),
           @Index(name = "idx_consent_ver_effective",
                  columnList = "effective_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsentVersionJpaEntity {

    @Id
    @Column(name = "version_id", length = 36, nullable = false)
    private String versionId;

    /**
     * 동의 유형
     * TERMS_OF_SERVICE   — 이용약관
     * PRIVACY_POLICY     — 개인정보 처리방침
     * THIRD_PARTY_SHARE  — 제3자 정보 제공 동의
     * MARKETING          — 마케팅 정보 수신 동의 (선택)
     */
    @Column(name = "consent_type", length = 50, nullable = false)
    private String consentType;

    /** 버전 식별자 (예: "2026-05-01", "v3.2") */
    @Column(name = "version_tag", length = 50, nullable = false)
    private String versionTag;

    /** 약관 제목 */
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    /** 약관 전문 URL (또는 내용 직접 저장) */
    @Column(name = "content_url", length = 500)
    private String contentUrl;

    /** 필수 동의 여부 (false = 선택 동의) */
    @Column(name = "required", nullable = false)
    @Builder.Default
    private boolean required = true;

    /** 버전 상태: DRAFT / ACTIVE / SUPERSEDED */
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private String status = "DRAFT";

    /** 적용 시작 일시 */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    /** 적용 종료 일시 (SUPERSEDED 시 설정) */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = "DRAFT";
    }
}
