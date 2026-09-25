package io.github.hipstermin.idem.hub.broker.provider;

import lombok.Builder;
import lombok.Getter;

/**
 * IdP 사업자 설정 도메인 모델
 *
 * <p>idem_hub.provider_config 테이블 (V3 생성, V6에서 provider_type 추가)에서 로드.
 * provider_type 을 기반으로 런타임 라우팅 전략을 결정한다.
 *
 * <p><b>provider_type 분류</b>:
 * <ul>
 *   <li>{@code STANDARD_OIDC}      — RFC 준수 OIDC (Keycloak relay 포함, 카카오·네이버 등)</li>
 *   <li>{@code SEMI_STANDARD_OIDC} — OIDC 부분 준수 (국내 간편인증 등)</li>
 *   <li>{@code NON_STANDARD}       — 독자 프로토콜 (PASS, GPKI, 금융인증서 등)</li>
 * </ul>
 */
@Getter
@Builder
public class ProviderConfig {

    /** 인증 수단 코드 (PK) — KAKAO_OIDC, PASS, FINANCIAL_CERT 등 */
    private final String providerCode;

    /** 화면 표시 이름 */
    private final String displayName;

    /** 인증 수준: L1 / L2 / L3 */
    private final String authLevel;

    /**
     * 브로커 처리 모드
     * - keycloak   : Keycloak OIDC relay 경로 (KeycloakOidcService)
     * - nonoidc    : 비OIDC 직접 처리 경로 (NonOidcBrokerAdapter)
     * - agency_stub: 테스트/PoC용 Agency Stub
     */
    private final String brokerMode;

    /**
     * IdP 힌트 (Keycloak identity_provider_hint 파라미터 값)
     * STANDARD_OIDC / SEMI_STANDARD_OIDC 에서만 사용
     * 예: social-kakao, social-naver
     */
    private final String idpHint;

    /**
     * provider 유형 분류 — 런타임 라우팅 결정 기준
     * V6 마이그레이션에서 provider_config 에 추가됨
     */
    private final ProviderType providerType;

    /** 활성 여부 */
    private final boolean active;

    // ── ProviderType 열거형 ──────────────────────────────────────────────

    public enum ProviderType {
        /** RFC 준수 OIDC — Keycloak relay */
        STANDARD_OIDC,
        /** OIDC 부분 준수 — 국내 간편인증 등 */
        SEMI_STANDARD_OIDC,
        /** 독자 프로토콜 — PASS / GPKI / 금융인증서 */
        NON_STANDARD;

        public static ProviderType fromString(String value) {
            if (value == null) return STANDARD_OIDC;
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return STANDARD_OIDC;
            }
        }

        /**
         * Keycloak relay 경로 사용 여부
         * STANDARD_OIDC 또는 SEMI_STANDARD_OIDC → Keycloak 경유
         */
        public boolean usesKeycloakRelay() {
            return this == STANDARD_OIDC || this == SEMI_STANDARD_OIDC;
        }

        /**
         * 비OIDC 직접 처리 경로 사용 여부
         */
        public boolean usesDirectBroker() {
            return this == NON_STANDARD;
        }
    }
}
