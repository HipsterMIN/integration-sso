package io.github.hipstermin.idem.hub.infrastructure.jpa.entity;

import io.github.hipstermin.idem.hub.domain.IntegrationType;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 기관 메타 JPA 엔터티 — ido.agency_meta 테이블 매핑
 * 설계서 §11.2 / §11.3 기관 정책 SoR
 *
 * [DB] PostgreSQL — ido 스키마
 */
@Entity
@Table(name = "agency_meta", schema = "ido")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgencyMetaJpaEntity {

    @Id
    @Column(name = "agency_code", length = 50, nullable = false)
    private String agencyCode;

    @Column(name = "official_name", length = 200, nullable = false)
    private String officialName;

    /** 최소 인증 수준: L1 / L2 / L3 */
    @Column(name = "min_auth_level", length = 10, nullable = false)
    private String minAuthLevel;

    /** 현재 정책 버전 — Handoff Payload policyVersion 에 반영 */
    @Column(name = "policy_version", length = 20, nullable = false)
    private String policyVersion;

    /** PBKDF2(apiKey) 해시 — 평문 저장 금지 */
    @Column(name = "api_key_hash", length = 300)
    private String apiKeyHash;

    /**
     * 허용된 콜백 URL 화이트리스트 (JSONB → TEXT 로 저장, 도메인에서 파싱)
     * e.g. ["https://agency-a.example.com/callback"]
     */
    @JdbcTypeCode(SqlTypes.JSON) // PostgreSQL jsonb 컬럼에 String 을 varchar 로 바인딩하면 42804 오류 — JSON 타입으로 바인딩
    @Column(name = "callback_whitelist", columnDefinition = "jsonb")
    private String callbackWhitelist;

    /**
     * 기관이 조회 허용한 사용자 속성 목록 (JSONB)
     * e.g. ["name_masked","mobile_masked"]
     */
    @JdbcTypeCode(SqlTypes.JSON) // PostgreSQL jsonb 컬럼에 String 을 varchar 로 바인딩하면 42804 오류 — JSON 타입으로 바인딩
    @Column(name = "allowed_attributes", columnDefinition = "jsonb")
    private String allowedAttributes;

    /**
     * 점검 시간대 (JSONB)
     * e.g. [{"dayOfWeek":"MON","startTime":"02:00","endTime":"04:00"}]
     */
    @JdbcTypeCode(SqlTypes.JSON) // PostgreSQL jsonb 컬럼에 String 을 varchar 로 바인딩하면 42804 오류 — JSON 타입으로 바인딩
    @Column(name = "maintenance_windows", columnDefinition = "jsonb")
    private String maintenanceWindows;

    /** 연동 유형 — DB CHECK(chk_integration_type) 와 IntegrationType 열거형은 같은 값 집합 */
    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", length = 20, nullable = false)
    private IntegrationType integrationType;

    /** BRIDGE 전용 — Bridge 서버 Payload 푸시 엔드포인트 */
    @Column(name = "bridge_endpoint", length = 500)
    private String bridgeEndpoint;

    /** APACHE_GATE 전용 — 게이트웨이 세션 사전 등록 URL (V20, S1 에서 bridge_endpoint 와 분리) */
    @Column(name = "apache_gate_endpoint", length = 500)
    private String apacheGateEndpoint;

    @Column(name = "sso_domain", length = 200)
    private String ssoDomain;

    /** 일별 조회 한도 (agency_rate_limit 보조) — Tenant Profile limits.daily 의 투영 */
    @Column(name = "daily_lookup_limit")
    private Integer dailyLookupLimit;

    /**
     * Tenant Profile 원문 (S2). 낱개 컬럼은 이 문서의 투영이다 — 쓰기 경로는 반드시
     * TenantProfileMapper.applyToEntity / syncProfileColumn 을 거쳐 둘을 일치시킨다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", columnDefinition = "jsonb")
    private String profile;

    @Column(name = "profile_schema_version")
    private Integer profileSchemaVersion;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 기본 policyVersion — 하드코딩 방지: AgencyMetaRepositoryImpl / AgencyAdminService 에서 설정값 주입 */
    public static final String DEFAULT_POLICY_VERSION = "1.0"; // fallback only

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (minAuthLevel == null) minAuthLevel = "L1";
        if (policyVersion == null) policyVersion = DEFAULT_POLICY_VERSION;
        if (integrationType == null) integrationType = IntegrationType.DEFAULT;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
