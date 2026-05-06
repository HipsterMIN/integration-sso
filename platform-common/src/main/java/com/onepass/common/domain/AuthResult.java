package com.onepass.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 인증 결과 표준 객체 (Q-Sign SoR)
 * 설계서 9.3절 참조
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResult {

    /** 인증 결과 고유 ID (Q-Sign 발급) */
    private final String authResultId;

    /** 전체 흐름 추적 키 */
    private final String correlationId;

    /** 인증 수준: L1 / L2 / L3 */
    private final AuthLevel authLevel;

    /** 인증 수단 코드 (KAKAO_OIDC / PASS / FINANCIAL_CERT 등) */
    private final String providerCode;

    /** 외부 사업자 트랜잭션 ID */
    private final String providerTxId;

    /** 식별자 해시 (Q-IM 조회 키) */
    private final String identifierHash;

    /** 인증 시각 */
    private final Instant authenticatedAt;

    /** 인증 결과: SUCCESS / FAIL */
    private final VerificationResult verificationResult;

    /** 세션 참조 ID */
    private final String sessionRef;

    /** Q-Sign 서명 (EdDSA / HMAC-SHA256) */
    private final String signature;

    public enum AuthLevel {
        L1, L2, L3
    }

    public enum VerificationResult {
        SUCCESS, FAIL
    }
}
