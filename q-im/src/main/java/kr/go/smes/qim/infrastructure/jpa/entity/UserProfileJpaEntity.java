package kr.go.smes.qim.infrastructure.jpa.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 사용자 프로필 JPA 엔터티 — MariaDB qim.user_profile 테이블 매핑
 * 설계서 §10.4 사용자 속성 저장 — PII 마스킹 필수
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      di_map, extra_attributes → JSON 칼럼 (MariaDB 10.2+ 지원)
 */
@Entity
@Table(name = "user_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileJpaEntity {

    /** qim_user_id를 PK 겸 FK로 사용 (1:1 관계) */
    @Id
    @Column(name = "qim_user_id", length = 36, nullable = false)
    private String qimUserId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "qim_user_id",
                foreignKey = @ForeignKey(name = "fk_profile_qim_user"))
    private QimUserJpaEntity user;

    /** 마스킹된 이름 (예: 홍*동) */
    @Column(name = "name_masked", length = 100)
    private String nameMasked;

    /** 마스킹된 휴대폰 번호 (예: 010-****-5678) */
    @Column(name = "mobile_masked", length = 20)
    private String mobileMasked;

    /** DOMESTIC / FOREIGN */
    @Column(name = "nationality_type", length = 10)
    private String nationalityType;

    /** CI (연계정보) — AES-256-GCM 암호화 저장 */
    @Column(name = "ci", length = 300)
    private String ci;

    /** {agencyCode: DI} 기관별 DI 맵 — JSON 컬럼 */
    @Column(name = "di_map", columnDefinition = "JSON")
    private String diMap;

    /** 출생 연도 (일/월 제외) */
    @Column(name = "birth_year")
    private Short birthYear;

    /** MALE / FEMALE / UNKNOWN */
    @Column(name = "gender", length = 10)
    private String gender;

    /** 확장 속성 (provider별) — JSON 컬럼 */
    @Column(name = "extra_attributes", columnDefinition = "JSON")
    private String extraAttributes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
