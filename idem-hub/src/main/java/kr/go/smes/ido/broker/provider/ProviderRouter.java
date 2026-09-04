package kr.go.smes.ido.broker.provider;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * provider_type 기반 런타임 라우팅 서비스 (P1 — GAP 마감)
 *
 * <p>providerCode → ProviderConfig.providerType → 처리 경로 결정:
 * <ul>
 *   <li>{@code STANDARD_OIDC} / {@code SEMI_STANDARD_OIDC}
 *       → {@link BrokerRoute#KEYCLOAK_RELAY} — KeycloakOidcService 경로</li>
 *   <li>{@code NON_STANDARD}
 *       → {@link BrokerRoute#DIRECT_BROKER} — NonOidcBrokerAdapter 경로</li>
 * </ul>
 *
 * <p>provider_config 에 설정 없으면 providerCode suffix 기반 휴리스틱으로 fallback.
 *
 * <p><b>사용 예시</b>:
 * <pre>{@code
 * BrokerRoute route = providerRouter.resolve("KAKAO_OIDC", correlationId);
 * if (route.isKeycloakRelay()) {
 *     // keycloakProperties.buildAuthorizationUrl(...) 호출
 * } else {
 *     // nonOidcBrokerAdapter.initiateAuth(...) 호출
 * }
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderRouter {

    private final ProviderConfigRepository providerConfigRepository;

    // ────────────────────────────────────────────────────────────────────
    // 공개 API
    // ────────────────────────────────────────────────────────────────────

    /**
     * providerCode → BrokerRoute 결정
     *
     * @param providerCode  인증 수단 코드
     * @param correlationId 흐름 추적 ID (로그/에러 컨텍스트)
     * @return 결정된 라우팅 경로
     * @throws PlatformException provider가 비활성화된 경우
     */
    public BrokerRoute resolve(String providerCode, String correlationId) {
        // 1. DB에서 provider 설정 조회
        return providerConfigRepository.findByCode(providerCode)
                .map(cfg -> {
                    if (!cfg.isActive()) {
                        log.warn("[ProviderRouter] 비활성 provider: {} correlationId={}",
                                providerCode, correlationId);
                        throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE,
                                correlationId, "provider " + providerCode + " 비활성화");
                    }
                    BrokerRoute route = fromProviderType(cfg.getProviderType());
                    log.debug("[ProviderRouter] 라우팅 결정: provider={} type={} route={}",
                            providerCode, cfg.getProviderType(), route);
                    return route;
                })
                .orElseGet(() -> {
                    // 2. DB 설정 없으면 providerCode 휴리스틱 fallback
                    BrokerRoute fallback = heuristicRoute(providerCode);
                    log.info("[ProviderRouter] provider_config 없음 — 휴리스틱 fallback: provider={} route={}",
                            providerCode, fallback);
                    return fallback;
                });
    }

    /**
     * provider_config 에서 로드한 ProviderType → BrokerRoute 변환
     */
    public BrokerRoute fromProviderType(ProviderConfig.ProviderType providerType) {
        if (providerType == null) return BrokerRoute.KEYCLOAK_RELAY;
        return switch (providerType) {
            case STANDARD_OIDC, SEMI_STANDARD_OIDC -> BrokerRoute.KEYCLOAK_RELAY;
            case NON_STANDARD                      -> BrokerRoute.DIRECT_BROKER;
        };
    }

    /**
     * providerCode suffix 기반 휴리스틱 라우팅 (provider_config 없을 때 fallback)
     * - "_OIDC" suffix → KEYCLOAK_RELAY
     * - 그 외           → DIRECT_BROKER
     */
    private BrokerRoute heuristicRoute(String providerCode) {
        if (providerCode == null) return BrokerRoute.KEYCLOAK_RELAY;
        String upper = providerCode.toUpperCase();
        if (upper.endsWith("_OIDC") || upper.contains("KEYCLOAK") || upper.contains("NAVER")
                || upper.contains("KAKAO")) {
            return BrokerRoute.KEYCLOAK_RELAY;
        }
        return BrokerRoute.DIRECT_BROKER;
    }

    // ────────────────────────────────────────────────────────────────────
    // BrokerRoute 열거형
    // ────────────────────────────────────────────────────────────────────

    /**
     * 브로커 처리 경로 (provider_type → routing 결과)
     */
    public enum BrokerRoute {
        /**
         * Keycloak OIDC relay 경로
         * → {@link kr.go.smes.ido.broker.keycloak.KeycloakOidcService}
         */
        KEYCLOAK_RELAY,

        /**
         * 비OIDC 직접 처리 경로
         * → {@link kr.go.smes.ido.broker.nonoidc.NonOidcBrokerAdapter}
         */
        DIRECT_BROKER;

        public boolean isKeycloakRelay() { return this == KEYCLOAK_RELAY; }
        public boolean isDirectBroker()  { return this == DIRECT_BROKER; }
    }
}
