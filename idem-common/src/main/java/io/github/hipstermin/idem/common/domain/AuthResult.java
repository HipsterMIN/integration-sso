package io.github.hipstermin.idem.common.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

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

    /**
     * 인증 방법 분류 코드 (설계서 §24.4.1 — auth_method 저장 규칙)
     * STANDARD_OIDC_*  : RFC-준수 OIDC 사업자 (Keycloak relay 포함)
     * SEMI_STANDARD_OIDC_* : OIDC 부분 준수 (국내 간편인증 등)
     * NON_STANDARD_*   : 독자 프로토콜 (PASS, GPKI, 금융인증서 등)
     */
    private final String authMethod;

    public enum AuthLevel {
        L1, L2, L3
    }

    public enum VerificationResult {
        SUCCESS, FAIL
    }

    /**
     * providerCode → authMethod 분류 규칙 (설계서 §24.4.1)
     * 호출부(AuthServiceImpl / KeycloakCallbackService 등)에서 사용
     */
    public static String resolveAuthMethod(String providerCode) {
        if (providerCode == null) return "NON_STANDARD_UNKNOWN";
        String upper = providerCode.toUpperCase();
        if (upper.endsWith("_OIDC")) {
            // 카카오·네이버 등 Keycloak을 통한 표준 OIDC
            return "STANDARD_OIDC_" + upper;
        }
        if (upper.equals("NAVER_LOGIN") || upper.equals("KAKAO_LOGIN")) {
            // 비표준 소셜 로그인 (OIDC 부분 준수)
            return "SEMI_STANDARD_OIDC_" + upper;
        }
        // PASS, GPKI, FINANCIAL_CERT, JOINT_CERT 등 독자 프로토콜
        return "NON_STANDARD_" + upper;
    }
}
