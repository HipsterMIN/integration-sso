package io.github.hipstermin.idem.hub.broker;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.spi.broker.DirectBrokerAdapter;
import io.github.hipstermin.idem.hub.broker.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.hub.broker.provider.ProviderConfigRepository;
import io.github.hipstermin.idem.hub.broker.provider.ProviderRouter;
import io.github.hipstermin.idem.hub.broker.state.IdoOidcStateEntry;
import io.github.hipstermin.idem.hub.broker.state.IdoOidcStateStore;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * ido BrokerService — Authorization URL 발급 (provider 코드 기반 2-레이어 라우팅)
 *
 * <h2>라우팅 아키텍처</h2>
 *
 * <p>인증 수단은 두 가지 기준으로 분기된다:
 *
 * <h3>Layer 1 — provider 유형 분기 (ProviderRouter)</h3>
 * <p>provider 코드의 {@code provider_type}을 보고 처리 경로를 결정한다:
 * <ul>
 *   <li><b>표준 OIDC</b> ({@code STANDARD_OIDC} / {@code SEMI_STANDARD_OIDC}):
 *       카카오·네이버 등 RFC 준수 소셜 로그인 → <b>Layer 2</b>로 진입
 *   </li>
 *   <li><b>비표준 OIDC</b> ({@code NON_STANDARD}):
 *       PASS·공동인증서·금융인증서·설치형 벤더 인증수단 → <b>ido 직접 처리</b>
 *       (플러그인 {@link DirectBrokerAdapter} 또는 코어 NonOidcBrokerAdapter)
 *   </li>
 * </ul>
 *
 * <h3>Layer 2 — 표준 OIDC 백엔드 선택 (idem.hub.broker.mode)</h3>
 * <p>표준 OIDC의 경우에만 {@code IDEM_HUB_BROKER_MODE}로 처리 백엔드를 선택한다:
 * <ul>
 *   <li>{@code qsign} (기본): q-sign에 URL 발급 위임
 *       <pre>FE → ido → q-sign POST /api/v1/oidc/{provider}/auth-url → 카카오/네이버</pre>
 *   </li>
 *   <li>{@code keycloak}: ido가 직접 Keycloak URL 생성
 *       <pre>FE → ido → Keycloak GET /realms/onepass/protocol/openid-connect/auth?kc_idp_hint=social-kakao</pre>
 *   </li>
 * </ul>
 *
 * <h3>비표준 OIDC 처리 경로 (IDEM_HUB_BROKER_MODE와 무관)</h3>
 * <ul>
 *   <li>플러그인 직접 브로커 (예: KR 에디션 idem-plugin-anyid 의 모바일신분증·간편인증·공동인증서·금융인증서·민간ID):
 *       <pre>FE → ido → DirectBrokerAdapter(플러그인) → 벤더 인증 서버</pre>
 *       어댑터 선택: provider_config.broker_mode == adapter.id() → 없으면 adapter.supports(code)
 *   </li>
 *   <li>기타 비표준 (PASS 등):
 *       <pre>FE → ido → NonOidcBrokerAdapter (비OIDC 직접 브로커링)</pre>
 *   </li>
 * </ul>
 *
 * <h3>환경변수 의미 정리</h3>
 * <pre>
 * IDEM_HUB_BROKER_MODE=qsign     → 카카오·네이버(표준 OIDC)를 q-sign으로 처리 (기본)
 * IDEM_HUB_BROKER_MODE=keycloak  → 카카오·네이버(표준 OIDC)를 Keycloak으로 처리
 *
 * (플러그인 직접 브로커 인증수단은 IDEM_HUB_BROKER_MODE 값과 무관하게 항상 해당 어댑터로 처리)
 * </pre>
 *
 * @see ProviderRouter
 * @see DirectBrokerAdapter
 * @see io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcBrokerAdapter
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerService {

    /**
     * 표준 OIDC 처리 백엔드 선택 (Layer 2):
     * <ul>
     *   <li>"qsign"    — q-sign에 위임 (기본값)</li>
     *   <li>"keycloak" — Keycloak 직접 연동</li>
     * </ul>
     *
     * <p>이 값은 <b>표준 OIDC(카카오·네이버 등)에만 적용</b>된다.
     * 비표준 OIDC(플러그인 직접 브로커 인증수단, PASS 등)는 provider_type 기반으로 별도 처리된다.
     */
    @Value("${idem.hub.broker.mode:qsign}")
    private String brokerMode;

    // ── q-sign 모드용 설정 ────────────────────────────────────────────────
    private final RestTemplate restTemplate;

    @Value("${idem.hub.gate.base-url:http://localhost:8081}")
    private String qsignBaseUrl;

    @Value("${idem.hub.gate.internal-sig-ttl-seconds:60}")
    private int internalSigTtl;

    /**
     * IdO → Q-Sign 내부 서명 HMAC 시크릿
     * 환경변수: IDEM_HUB_INTERNAL_SIG_SECRET
     */
    @Value("${idem.hub.gate.internal-sig-secret:}")
    private String internalSigSecret;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @PostConstruct
    void validateInternalSigConfig() {
        if (internalSigSecret == null || internalSigSecret.isBlank()) {
            log.error("[BrokerService][보안경고] IDEM_HUB_INTERNAL_SIG_SECRET 미설정 — " +
                      "q-sign 모드에서 모든 내부 서명이 빈 시크릿으로 생성되어 검증 실패합니다. " +
                      "운영 환경에서 반드시 IDEM_HUB_INTERNAL_SIG_SECRET 환경변수를 설정하세요.");
        } else {
            log.info("[BrokerService] X-Internal-Sig HMAC-SHA256 서명 활성화됨.");
        }
    }

    // ── Keycloak 모드용 컴포넌트 ──────────────────────────────────────────
    private final KeycloakProperties keycloakProperties;
    private final IdoOidcStateStore  idoOidcStateStore;

    // ── 플러그인 직접 브로커 어댑터 (S5b) — 없으면 빈 목록 ────────────────
    private final List<DirectBrokerAdapter> directBrokerAdapters;

    // ── provider 라우팅 (Layer 1) ─────────────────────────────────────────
    private final ProviderRouter           providerRouter;
    private final ProviderConfigRepository providerConfigRepository;

    // ─────────────────────────────────────────────────────────────────────

    /**
     * provider에 대한 Authorization URL 반환 (2-레이어 라우팅)
     *
     * <p><b>Layer 1</b>: {@link ProviderRouter}로 provider 유형 판별
     * <ul>
     *   <li>표준 OIDC → Layer 2 (brokerMode 기반 백엔드 선택)</li>
     *   <li>비표준 OIDC → 플러그인 직접 브로커 또는 NonOidc 직접 처리</li>
     * </ul>
     *
     * <p><b>Layer 2</b> (표준 OIDC 전용): {@code idem.hub.broker.mode}로 백엔드 선택
     * <ul>
     *   <li>qsign    → q-sign에 위임</li>
     *   <li>keycloak → Keycloak 직접 연동</li>
     * </ul>
     *
     * @param provider       인증 수단 코드 (kakao / 플러그인 인증수단 코드 등)
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준 (L1/L2/L3)
     * @return 브라우저가 리다이렉트할 Authorization URL
     */
    public String buildAuthorizationUrl(String provider,
                                        String correlationId,
                                        String returnUrl,
                                        String requestedLevel) {
        log.info("[BrokerService] buildAuthorizationUrl: provider={} correlationId={}",
                provider, correlationId);

        // ── Layer 1: provider 유형 판별 ──────────────────────────────────
        ProviderRouter.BrokerRoute route = providerRouter.resolve(provider, correlationId);

        if (route.isDirectBroker()) {
            // 비표준 OIDC — IDEM_HUB_BROKER_MODE와 무관하게 ido 직접 처리
            return buildNonOidcAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
        }

        // ── Layer 2: 표준 OIDC 백엔드 선택 (brokerMode) ──────────────────
        log.info("[BrokerService] 표준 OIDC 처리: mode={} provider={} correlationId={}",
                brokerMode, provider, correlationId);

        return switch (brokerMode) {
            case "keycloak" -> buildKeycloakAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
            case "qsign"    -> buildQsignAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
            default -> {
                log.warn("[BrokerService] 알 수 없는 broker.mode: {} — qsign 폴백 (표준 OIDC)", brokerMode);
                yield buildQsignAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Layer 1 — 비표준 OIDC 직접 처리 (IDEM_HUB_BROKER_MODE 무관)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 비표준 OIDC provider 처리 — provider 코드 기반 어댑터 선택
     *
     * <p>플러그인 {@link DirectBrokerAdapter} 가 맡는 인증수단이면 그 어댑터로 위임하고,
     * 아니면 NonOidcBrokerAdapter(PASS 등)로 처리한다
     * (현재는 {@link io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcBrokerController}가 별도 경로 보유).
     */
    private String buildNonOidcAuthorizationUrl(String provider,
                                                 String correlationId,
                                                 String returnUrl,
                                                 String requestedLevel) {
        String upper = provider.toUpperCase().replace("-", "_");

        Optional<DirectBrokerAdapter> adapter = resolveDirectBrokerAdapter(upper, correlationId);
        if (adapter.isPresent()) {
            log.info("[BrokerService][nonoidc→{}] 플러그인 직접 브로커: provider={} correlationId={}",
                    adapter.get().id(), provider, correlationId);
            return adapter.get().buildAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
        }

        // NonOidc 기타 (PASS 등) — NonOidcBrokerController 경로로 리다이렉트 URL 반환
        log.info("[BrokerService][nonoidc] 비표준 OIDC 직접 처리: provider={} correlationId={}",
                provider, correlationId);
        return buildNonOidcInitiateUrl(provider, returnUrl, correlationId, requestedLevel);
    }

    /**
     * provider 를 맡을 플러그인 직접 브로커 어댑터 선택
     *
     * <p>판별 순서:
     * <ol>
     *   <li>DB {@code provider_config.broker_mode} 가 어댑터 {@link DirectBrokerAdapter#id()} 와 같으면 그 어댑터</li>
     *   <li>DB 설정이 없거나 조회 실패면 {@link DirectBrokerAdapter#supports(String)} 휴리스틱</li>
     * </ol>
     */
    Optional<DirectBrokerAdapter> resolveDirectBrokerAdapter(String providerCode, String correlationId) {
        if (directBrokerAdapters == null || directBrokerAdapters.isEmpty()) return Optional.empty();

        String brokerMode = null;
        try {
            brokerMode = providerConfigRepository.findByCode(providerCode)
                    .map(cfg -> cfg.getBrokerMode())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[BrokerService] provider_config 조회 실패 — 휴리스틱 사용: provider={} correlationId={}",
                    providerCode, correlationId);
        }
        if (brokerMode != null && !brokerMode.isBlank()) {
            String mode = brokerMode.toLowerCase();
            Optional<DirectBrokerAdapter> byMode = directBrokerAdapters.stream()
                    .filter(a -> mode.equals(a.id()))
                    .findFirst();
            if (byMode.isPresent()) return byMode;
        }
        return directBrokerAdapters.stream()
                .filter(a -> a.supports(providerCode))
                .findFirst();
    }

    /**
     * NonOidc 시작 URL 생성 — NonOidcBrokerController 경로로 라우팅
     *
     * <p>PASS 등 비표준 OIDC는 {@code /api/v1/broker/{provider}/nonoidc/initiate}
     * 경로를 통해 {@link io.github.hipstermin.idem.hub.broker.nonoidc.NonOidcBrokerController}가 처리.
     */
    private String buildNonOidcInitiateUrl(String provider, String returnUrl,
                                            String correlationId, String requestedLevel) {
        String path = "/api/v1/broker/" + provider.toLowerCase().replace("_", "-") + "/nonoidc/initiate";
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path)
                .queryParam("requestedLevel", requestedLevel != null ? requestedLevel : "L1");
        if (returnUrl != null && !returnUrl.isBlank()) {
            builder.queryParam("returnUrl", returnUrl);
        }
        String url = builder.build(false).toUriString();
        log.info("[BrokerService][nonoidc] 시작 URL: {} correlationId={}", url, correlationId);
        return url;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Layer 2 — 표준 OIDC 처리 (Keycloak / q-sign)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Keycloak Authorization URL 직접 생성 (문서 §7.2)
     *
     * <p>생성되는 URL 예시:
     * <pre>
     *   http://keycloak:8088/realms/onepass/protocol/openid-connect/auth
     *     ?response_type=code
     *     &client_id=ido-client
     *     &redirect_uri=http://localhost:8083/api/v1/broker/callback
     *     &scope=openid profile email
     *     &state={32자 UUID}
     *     &nonce={32자 UUID}
     *     &kc_idp_hint=social-kakao
     * </pre>
     *
     * <p>state/nonce는 Redis에 TTL과 함께 저장되어 콜백 수신 시 검증.
     */
    private String buildKeycloakAuthorizationUrl(String provider,
                                                  String correlationId,
                                                  String returnUrl,
                                                  String requestedLevel) {
        IdoOidcStateEntry entry = idoOidcStateStore.create(
                correlationId, returnUrl, requestedLevel,
                provider, keycloakProperties.getStateTtlSeconds()
        );

        String idpHint = keycloakProperties.resolveIdpHint(provider);

        String authUrl = UriComponentsBuilder
                .fromUriString(keycloakProperties.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id",     keycloakProperties.getClientId())
                .queryParam("redirect_uri",  keycloakProperties.getRedirectUri())
                .queryParam("scope",         "openid profile email")
                .queryParam("state",         entry.getState())
                .queryParam("nonce",         entry.getNonce())
                .queryParam("kc_idp_hint",   idpHint)
                .build(false)
                .toUriString();

        log.info("[BrokerService][keycloak] Authorization URL 생성: correlationId={} provider={} idpHint={}",
                correlationId, provider, idpHint);
        return authUrl;
    }

    /**
     * q-sign에 Authorization URL 발급 위임 (기존 방식)
     *
     * <p>q-sign이 state/nonce 생성, Redis 저장, Kakao URL 조립을 담당.
     * q-sign POST /api/v1/oidc/{provider}/auth-url → { authorizationUrl: "..." }
     */
    @SuppressWarnings("unchecked")
    private String buildQsignAuthorizationUrl(String provider,
                                               String correlationId,
                                               String returnUrl,
                                               String requestedLevel) {
        String url = qsignBaseUrl + "/api/v1/oidc/" + provider + "/auth-url";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id",  correlationId);
        headers.set("X-Internal-Caller", "idem-hub");
        headers.set("X-Internal-Sig",    buildInternalSig(correlationId));

        Map<String, String> body = Map.of(
                "correlationId",  correlationId,
                "returnUrl",      returnUrl != null ? returnUrl : "",
                "requestedLevel", requestedLevel != null ? requestedLevel : "L1"
        );

        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            if (resp.getBody() == null || !resp.getBody().containsKey("authorizationUrl")) {
                throw new PlatformException(PlatformErrorCode.IDP_RESPONSE_INVALID, correlationId,
                        "q-sign 응답에 authorizationUrl 없음");
            }
            String authUrl = (String) resp.getBody().get("authorizationUrl");
            log.info("[BrokerService][qsign] Authorization URL 수신: correlationId={}", correlationId);
            return authUrl;

        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BrokerService][qsign] q-sign 호출 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }

    /**
     * IdO → Q-Sign 내부 서명 생성 (HMAC-SHA256)
     *
     * <p>서명 페이로드: {@code "{correlationId}:{epochSeconds}"}
     * q-sign 수신 측은 ±60초 범위의 epochSeconds를 전수 검사.
     *
     * @param correlationId 흐름 추적 ID
     * @return HMAC-SHA256 서명 HEX 문자열
     */
    private String buildInternalSig(String correlationId) {
        if (internalSigSecret == null || internalSigSecret.isBlank()) {
            // D2 fail-secure: 서명 없이 내부 호출을 보내지 않는다 (부팅 시 FailSecureBootGuard 가 이미 막지만 이중 방어)
            throw new PlatformException(PlatformErrorCode.IDO_DEPENDENCY_UNAVAILABLE, correlationId,
                    "IDEM_HUB_INTERNAL_SIG_SECRET 미설정 — 내부 서명 불가");
        }
        try {
            long epochSeconds = System.currentTimeMillis() / 1000L;
            String payload = correlationId + ":" + epochSeconds;
            return CryptoProviders.current().hmacSha256Hex(internalSigSecret.getBytes(StandardCharsets.UTF_8), payload);
        } catch (Exception e) {
            log.error("[BrokerService] X-Internal-Sig HMAC 생성 실패: correlationId={}", correlationId, e);
            throw new PlatformException(PlatformErrorCode.IDP_PROVIDER_UNAVAILABLE, correlationId, e);
        }
    }
}
