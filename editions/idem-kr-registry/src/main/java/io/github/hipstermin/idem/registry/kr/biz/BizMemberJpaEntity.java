package io.github.hipstermin.idem.registry.kr.biz;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import lombok.AccessLevel;
import org.springframework.data.domain.Persistable;

/**
 * 기업회원 JPA 엔터티 — MariaDB qim.biz_member 테이블 매핑
 * 설계서 §P3-06 기업회원 전환 참조
 *
 * <p><b>시간 컬럼 정책</b>:<br>
 * 모든 시각은 {@link Instant}(UTC epoch)로 관리한다.
 * DB 컬럼 타입은 {@code DATETIME(6)}이지만, JDBC {@code serverTimezone=UTC} 설정과
 * Hibernate {@code hibernate.jdbc.time_zone=UTC} 설정에 의해 드라이버가
 * Instant 값을 UTC 숫자로 변환하여 저장한다.
 * MariaDB {@code DATETIME}은 TZ 변환을 수행하지 않으므로
 * UTC 숫자가 그대로 저장/조회된다.
 *
 * <p>이전에 {@code LocalDateTime.now()}를 사용했을 때 JVM TZ(UTC) 값이 저장됐다면
 * 저장된 숫자 자체는 동일하므로 데이터 마이그레이션 없이 정상 동작한다.
 * 단, JVM TZ가 KST였던 환경에서 저장된 레코드는 9시간 편차가 발생하므로
 * V7 마이그레이션 주석을 참고한다.
 */
@Entity
@Table(name = "biz_member")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BizMemberJpaEntity implements Persistable<String> {

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

    /**
     * 사업자등록번호 인증 완료 시각 (UTC).
     * null 허용 — 미인증 상태.
     */
    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * 기업회원 전환 시각 (UTC).
     * {@code @PrePersist}에서 자동 설정.
     */
    @Column(name = "converted_at", nullable = false)
    private Instant convertedAt;

    /** 최종 수정 시각 (UTC). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();   // UTC epoch — JVM/DB TZ 무관
        if (convertedAt == null) convertedAt = now;
        updatedAt = now;
        if (bizStatus == null) bizStatus = "ACTIVE";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();     // UTC epoch — JVM/DB TZ 무관
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
