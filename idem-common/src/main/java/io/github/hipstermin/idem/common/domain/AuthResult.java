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

    /**
     * 인증수준 — 플랫폼 정규 어휘는 {@code L1 < L2 < L3} 하나뿐이다 (S3 어휘 통일).
     * 종전 CAST 토큰·FE 세션이 쓰던 {@code LOW/MEDIUM/HIGH} 와 Keycloak {@code acr} 숫자는 {@link #parse} 가 호환 해석한다.
     */
    public enum AuthLevel {
        L1, L2, L3;

        /** 요구 수준 이상인가. */
        public boolean meets(AuthLevel required) {
            return required == null || ordinal() >= required.ordinal();
        }

        /** L1/L2/L3 · LOW/MEDIUM/HIGH · 1/2/3 (대소문자·공백 무시). 그 외는 empty. */
        public static java.util.Optional<AuthLevel> parse(String raw) {
            if (raw == null) return java.util.Optional.empty();
            return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "L1", "LOW", "1"    -> java.util.Optional.of(L1);
                case "L2", "MEDIUM", "2" -> java.util.Optional.of(L2);
                case "L3", "HIGH", "3"   -> java.util.Optional.of(L3);
                default -> java.util.Optional.empty();
            };
        }

        public static AuthLevel parseOrDefault(String raw, AuthLevel fallback) {
            return parse(raw).orElse(fallback);
        }
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
