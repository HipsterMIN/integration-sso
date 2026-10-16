package io.github.hipstermin.idem.gate.infrastructure.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Q-Sign auth_result 테이블 JPA 엔티티
 * 설계서 §9.3 — 인증 결과 SoR (System of Record)
 */
@Entity
@Table(name = "auth_result", schema = "idem_gate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResultJpaEntity {

    @Id
    @Column(name = "auth_result_id", length = 36, nullable = false, updatable = false)
    private String authResultId;

    @Column(name = "correlation_id", length = 36, nullable = false, updatable = false)
    private String correlationId;

    @Column(name = "auth_level", length = 10, nullable = false)
    private String authLevel;

    @Column(name = "provider_code", length = 50, nullable = false, updatable = false)
    private String providerCode;

    @Column(name = "provider_tx_id", length = 300)
    private String providerTxId;

    /** SHA-256(CI or idToken.sub) — PII 비보관 원칙 */
    @Column(name = "identifier_hash", length = 300, nullable = false, updatable = false)
    private String identifierHash;

    @Column(name = "verification_result", length = 20, nullable = false)
    private String verificationResult;  // SUCCESS / FAIL / BLOCKED

    @Column(name = "fail_reason", length = 100)
    private String failReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "authenticated_at")
    private Instant authenticatedAt;

    /**
     * JSON — PII 마스킹 후 저장. 1.1.4 G2-10: {@code jsonb} 열에 String 을 그대로 바인딩하면 PostgreSQL 이
     * "column is of type jsonb but expression is of type character varying" 으로 INSERT 를 거부한다(값이 null 이어도) —
     * Keycloak 중계 로그인의 auth_result 저장이 항상 실패했다. JSON 타입 코드로 바인딩한다({@code ?::jsonb}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "claims", columnDefinition = "jsonb")
    private String claims;

    @Column(name = "internal_signature", columnDefinition = "text")
    private String internalSignature;  // HMAC-SHA256 서명

    @Column(name = "session_ref", length = 36)
    private String sessionRef;

    /** authMethod — GAP-QS-01 추가 컬럼 (V3 migration 이전에는 null 허용) */
    @Column(name = "auth_method", length = 30)
    private String authMethod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null)   createdAt   = Instant.now();
        if (requestedAt == null) requestedAt = Instant.now();
    }
}
