package kr.go.smes.ido.provision.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * 기관 프로비저닝 HTTP 요청 페이로드
 *
 * <p>PII 최소화 원칙 (설계서 §14.4.3):
 * 실명·전화번호 평문 절대 금지. qimUserId + SHA-256 해시 + 가입일만 포함.
 * 기관이 매핑이 필요하면 CAST Token을 통해 직접 인증 후 조회해야 함.
 *
 * <p>기관 API가 수신하는 JSON 구조 예시:
 * <pre>
 * {
 *   "onepass_user_id": "01914bf8-...",
 *   "event_type": "USER_REGISTERED",
 *   "identity_hash": "sha256:abc123...",
 *   "registered_at": "2026-05-13T12:00:00Z",
 *   "idempotency_key": "01914bf9-...",
 *   "correlation_id": "01914bfa-..."
 * }
 * </pre>
 */
@Value
@Builder
public class ProvisioningRequest {

    /** OnePass(IdO) 사용자 고유 식별자 (UUID v7) */
    @JsonProperty("onepass_user_id")
    String qimUserId;

    /** 이벤트 타입 (USER_REGISTERED / BIZ_CONVERTED / USER_UPDATED / USER_WITHDRAWN) */
    @JsonProperty("event_type")
    String eventType;

    /**
     * 식별 해시 (SHA-256, 평문 PII 대체)
     * 기관이 자체 DB와 매핑할 때 사용.
     * 형식: "sha256:" + hex(SHA256(qimUserId + ":" + registeredAt.toEpochMilli()))
     */
    @JsonProperty("identity_hash")
    String identityHash;

    /** 가입/전환 시각 (ISO 8601 UTC) */
    @JsonProperty("registered_at")
    Instant registeredAt;

    /** 멱등성 키 (UUID v7) — provisioning_outbox.idempotency_key와 동일 */
    @JsonProperty("idempotency_key")
    String idempotencyKey;

    /** 흐름 추적 ID (X-Correlation-ID 전파) */
    @JsonProperty("correlation_id")
    String correlationId;

    /** 프로비저닝 스키마 버전 (하위 호환용) */
    @JsonProperty("schema_version")
    @Builder.Default
    String schemaVersion = "1.0";
}
