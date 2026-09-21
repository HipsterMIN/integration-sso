package io.github.hipstermin.idem.registry.infrastructure.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import lombok.AccessLevel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

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
public class UserProfileJpaEntity implements Persistable<String> {

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

    /** 주체 식별자 스킴 (S4): CI / EMAIL / PHONE / EXTERNAL_SUB — V8 */
    @Column(name = "subject_scheme", length = 20)
    private String subjectScheme;

    /** 주체 키 — 암호화 저장 (CI 스킴이면 {@link #ci} 와 같은 값) — V8 */
    @Column(name = "subject_key", length = 512)
    private String subjectKey;

    /** {agencyCode: DI} 기관별 DI 맵 — JSON 컬럼 */
    @JdbcTypeCode(SqlTypes.JSON) // PostgreSQL jsonb — String 을 JSON 타입으로 바인딩 (D1)
    @Column(name = "di_map", columnDefinition = "jsonb")
    private String diMap;

    /** 출생 연도 (일/월 제외) */
    @Column(name = "birth_year")
    private Short birthYear;

    /** MALE / FEMALE / UNKNOWN */
    @Column(name = "gender", length = 10)
    private String gender;

    /** 확장 속성 (provider별) — JSON 컬럼 */
    @JdbcTypeCode(SqlTypes.JSON) // PostgreSQL jsonb — String 을 JSON 타입으로 바인딩 (D1)
    @Column(name = "extra_attributes", columnDefinition = "jsonb")
    private String extraAttributes;

    // ── P3-05: 미성년자/보호자 ────────────────────────────────────────────────

    /**
     * 14세 미만 여부 — birth_year 기준으로 등록 시 자동 판정 (P3-05)
     * 보호자 동의 완료 전까지 일부 서비스 제한
     */
    @Column(name = "is_minor", nullable = false)
    private Boolean isMinor;

    /**
     * 보호자 qim_user_id — isMinor=true인 경우에만 설정됨
     * FK → qim_user.qim_user_id
     */
    @Column(name = "guardian_qim_user_id", length = 36)
    private String guardianQimUserId;

    /**
     * 보호자 동의 완료 시각 (UTC) — null이면 아직 미동의.
     * DB 컬럼: {@code DATETIME(6)}, JDBC serverTimezone=UTC 설정으로 UTC 숫자 그대로 저장.
     */
    @Column(name = "guardian_consent_at")
    private Instant guardianConsentAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ── Spring Data 신규 판정 (Hibernate 6.6 @MapsId merge 회귀 대응) ──────────
    /**
     * 할당 ID 엔터티는 Spring Data {@code save()} 가 {@code merge} 로 가는데, Hibernate 6.6 은
     * DB 에 행이 없는 {@code @MapsId} 자식의 merge 에 {@code StaleObjectStateException} 을 던진다
     * ({@code MapsIdPersistRegressionTest}). {@link Persistable#isNew()} 가 true 이면 {@code save()} 가
     * {@code persist} 를 호출하므로 신규 저장이 INSERT 로 간다. 로드·저장 후에는 false 로 바뀐다.
     */
    @Transient
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean isNew = true;

    @Override
    public String getId() { return qimUserId; }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { isNew = false; }
}
