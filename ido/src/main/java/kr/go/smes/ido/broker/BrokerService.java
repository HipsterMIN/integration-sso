package kr.go.smes.ido.broker;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.broker.keycloak.KeycloakProperties;
import kr.go.smes.ido.broker.state.IdoOidcStateStore;
import kr.go.smes.ido.broker.state.IdoOidcStateEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * ido BrokerService — OIDC Authorization URL 발급 (문서 §6, §7)
 *
 * <p>{@code ido.broker.mode} 설정에 따라 두 가지 모드로 동작:
 *
 * <ul>
 *   <li>{@code qsign} (기본): 기존 모드 — q-sign에 URL 발급 위임
 *       <pre>FE → ido → q-sign POST /api/v1/oidc/kakao/auth-url → Kakao</pre>
 *   </li>
 *   <li>{@code keycloak}: 신규 모드 — ido가 직접 Keycloak URL 생성
 *       <pre>FE → ido → Keycloak GET /realms/onepass/protocol/openid-connect/auth?kc_idp_hint=social-kakao</pre>
 *   </li>
 * </ul>
 *
 * <p>Keycloak 모드 전환 시 {@code ido.broker.mode=keycloak} 으로 설정만 변경하면 됨.
 * 코드 변경 없이 두 모드를 스위칭할 수 있도록 설계.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerService {

    /** 브로커 모드: "qsign" | "keycloak" */
    @Value("${ido.broker.mode:qsign}")
    private String brokerMode;

    // ── q-sign 모드용 설정 ────────────────────────────────────────────────
    private final RestTemplate restTemplate;

    @Value("${ido.qsign.base-url:http://localhost:8081}")
    private String qsignBaseUrl;

    @Value("${ido.qsign.internal-sig-ttl-seconds:60}")
    private int internalSigTtl;

    // ── Keycloak 모드용 컴포넌트 ──────────────────────────────────────────
    private final KeycloakProperties keycloakProperties;
    private final IdoOidcStateStore   idoOidcStateStore;

    // ─────────────────────────────────────────────────────────────────────

    /**
     * provider에 대한 Authorization URL 반환 (모드 자동 분기)
     *
     * @param provider       인증 수단 (kakao / naver 등)
     * @param correlationId  흐름 추적 ID
     * @param returnUrl      인증 완료 후 이동할 기관 URL
     * @param requestedLevel 요청 인증 수준 (L1/L2/L3)
     * @return 브라우저가 리다이렉트할 Authorization URL
     */
    public String buildAuthorizationUrl(String provider,
                                        String correlationId,
                                        String returnUrl,
                                        String requestedLevel) {
        log.info("[BrokerService] buildAuthorizationUrl: mode={} provider={} correlationId={}",
                brokerMode, provider, correlationId);

        return switch (brokerMode) {
            case "keycloak" -> buildKeycloakAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
            case "qsign"    -> buildQsignAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
            default -> {
                log.warn("[BrokerService] 알 수 없는 브로커 모드: {} — qsign 폴백", brokerMode);
                yield buildQsignAuthorizationUrl(provider, correlationId, returnUrl, requestedLevel);
            }
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // Keycloak 모드 (문서 §7 — ido가 직접 Keycloak URL 생성)
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
        // state + nonce 생성 → Redis 저장 (CSRF/replay 방지)
        IdoOidcStateEntry entry = idoOidcStateStore.create(
                correlationId,
                returnUrl,
                requestedLevel,
                provider,
                keycloakProperties.getStateTtlSeconds()
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
                .build(false)   // 이미 인코딩된 값 그대로 사용
                .toUriString();

        log.info("[BrokerService][keycloak] Authorization URL 생성: correlationId={} provider={} idpHint={}",
                correlationId, provider, idpHint);
        return authUrl;
    }

    // ══════════════════════════════════════════════════════════════════════
    // q-sign 모드 (기존 방식 — q-sign에 위임)
    // ══════════════════════════════════════════════════════════════════════

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
        headers.set("X-Internal-Caller", "ido");
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
     * 내부 서비스 간 단순 서명 (PoC 수준)
     * 실운영: HMAC-SHA256(correlationId + timestamp, sharedSecret)
     */
    private String buildInternalSig(String correlationId) {
        String safe = correlationId.replace("-", "");
        return "sig-" + (safe.length() >= 8 ? safe.substring(0, 8) : safe);
    }
}
