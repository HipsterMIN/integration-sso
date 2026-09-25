package io.github.hipstermin.idem.hub.infrastructure;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * idem_hub.agency_endpoint_registry 테이블 레코드 도메인 객체
 *
 * <p>복합 PK: (agencyCode, endpointType)
 * JdbcTemplate RowMapper에서 매핑하여 사용.
 */
@Value
@Builder
public class AgencyEndpointRecord {

    /** 기관 코드 (FK → idem_hub.agency_meta.agency_code) */
    String agencyCode;

    /**
     * 엔드포인트 타입
     * PROVISIONING / CAST_VERIFY / WEBHOOK / STATUS / GATEWAY_INBOUND
     */
    String endpointType;

    /** 실제 API URL (HTTPS 필수 — 운영 환경) */
    String endpointUrl;

    /** HTTP 메서드 (POST / PUT / PATCH / GET) */
    @Builder.Default
    String httpMethod = "POST";

    /**
     * 인증 방식
     * API_KEY / MTLS / HMAC / NONE
     */
    @Builder.Default
    String authType = "API_KEY";

    /**
     * K8s Secret 경로 (평문 자격증명 저장 금지)
     * 예: "secrets/agency/AGENCY_001/api-key"
     * null이면 인증 없음 (authType=NONE)
     */
    String authCredentialRef;

    /** 요청 타임아웃 (ms), 기본 5000ms */
    @Builder.Default
    int timeoutMs = 5000;

    /** 활성 여부 */
    @Builder.Default
    boolean active = true;

    /** 운영 메모 */
    String note;

    /** 생성 시각 */
    Instant createdAt;

    /** 마지막 수정 시각 */
    Instant updatedAt;
}
