package kr.go.smes.qim.consent;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 동의 요청 DTO
 * POST /api/v1/internal/users/{qimUserId}/consents
 */
@Getter
@Builder
@Jacksonized
public class ConsentRequest {

    /**
     * 동의할 버전 ID (consent_version.version_id)
     * null이면 해당 유형의 최신 ACTIVE 버전으로 자동 선택
     */
    private final String versionId;

    /** 동의 유형 (TERMS_OF_SERVICE / PRIVACY_POLICY / THIRD_PARTY_SHARE / MARKETING) */
    private final String consentType;

    /** 동의 경로 (WEB_SIGNUP / APP_SIGNUP / RE_CONSENT / AGENCY_API) */
    private final String agreedVia;

    /** 동의 IP (감사 추적용, nullable) */
    private final String clientIp;

    /** 전체 흐름 추적 ID */
    private final String correlationId;
}
