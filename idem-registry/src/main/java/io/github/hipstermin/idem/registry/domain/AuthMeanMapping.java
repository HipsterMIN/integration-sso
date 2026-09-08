package io.github.hipstermin.idem.registry.domain;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 인증수단 매핑 (Q-IM SoR)
 * 설계서 10.2 / 10.3절 참조
 *
 * 매핑 정책:
 *   - 동일 사용자에 다중 인증수단 결합 허용
 *   - 서로 다른 사용자가 동일 인증수단에 묶이는 것 금지
 *   - 인증수단 변경/해지 시 매핑 이력 보존
 */
@Getter
@Builder
public class AuthMeanMapping {

    private final String mappingId;
    private final String qimUserId;

    /** 인증수단 제공자 코드 (KAKAO_OIDC / PASS / FINANCIAL_CERT 등) */
    private final String providerCode;

    /** 식별자 해시 (외부 IdP 제공 식별자의 해시 — PII 비노출) */
    private final String identifierHash;

    /** 매핑 상태: ACTIVE / REVOKED */
    private final MappingStatus status;

    private final Instant linkedAt;
    private final Instant revokedAt;

    public enum MappingStatus {
        ACTIVE,
        REVOKED
    }
}
