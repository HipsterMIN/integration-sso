package io.github.hipstermin.idem.gate.oidcfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hipstermin.idem.gate.keycloak.KeycloakJwksVerifier;
import io.github.hipstermin.idem.gate.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.gate.keycloak.dto.KeycloakIdTokenClaims;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * 표준 OIDC 경로의 정책 강제 지점 (S6).
 *
 * <p>Keycloak 이 토큰을 내준 직후, RP 에 돌려주기 전에 hub 에 "이 사용자가 이 서비스에 들어가도 되는가" 를 묻는다.
 * 거부면 토큰을 돌려주지 않고 Keycloak 세션도 끊는다(refresh_token 로그아웃, 최선 노력). 허용이면 판정을 캐시해
 * userinfo 응답에 Idem 클레임({@code idem_*})을 보탠다. Keycloak 이 외부에 노출되지 않는 한 이 지점은 우회할 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OidcRpPolicyGate {

    private final KeycloakJwksVerifier jwksVerifier;
    private final HubAccessClient hubAccessClient;
    private final HubSessionClient hubSessionClient;
    private final AccessDecisionCache cache;
    private final KeycloakProperties keycloak;
    private final OidcFrontProperties props;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /** 토큰 응답 판정 결과. {@code decision.allowed()} 가 false 면 호출자가 OAuth 오류로 바꾼다. */
    public record TokenOutcome(AccessDecision decision, String clientId, String sub) {}

    /**
     * @param requestedClientId 토큰 요청의 client_id (본문 또는 Basic 인증)
     * @param tokenJson        Keycloak 200 응답 본문
     */
    public TokenOutcome onTokenIssued(String requestedClientId, String tokenJson, String correlationId) {
        JsonNode token;
        try {
            token = objectMapper.readTree(tokenJson);
        } catch (Exception e) {
            return new TokenOutcome(AccessDecision.denied("E-IDO-116", "토큰 응답 파싱 실패"), requestedClientId, null);
        }
        String jwt = firstText(token, "id_token", "access_token");
        if (jwt == null) {
            return new TokenOutcome(AccessDecision.denied("E-IDO-116", "토큰 응답에 id_token/access_token 없음"), requestedClientId, null);
        }
        KeycloakIdTokenClaims claims;
        try {
            claims = jwksVerifier.verify(jwt, correlationId);
        } catch (Exception e) {
            log.error("[OIDC-FRONT] 발급 토큰 서명 검증 실패 → 거부: cid={} err={}", correlationId, e.getMessage());
            return new TokenOutcome(AccessDecision.denied("E-IDO-116", "발급 토큰 검증 실패"), requestedClientId, null);
        }
        String clientId = claims.getAuthorizedParty() != null ? claims.getAuthorizedParty() : requestedClientId;
        if (!props.isProvisionedClient(clientId)) {
            return new TokenOutcome(AccessDecision.denied("E-IDO-123", "Idem 이 프로비저닝한 client 가 아님: " + clientId), clientId, claims.getSubject());
        }
        AccessDecision decision = hubAccessClient.evaluate(clientId, claims.getSubject(), claims.getIdentityProvider(),
                claims.getAcr(), claims.getSessionId(), correlationId);
        if (decision.allowed()) {
            long ttl = Math.min(props.getDecisionCacheTtlSeconds(), token.path("expires_in").asLong(props.getDecisionCacheTtlSeconds()));
            cache.put(clientId, claims.getSubject(), decision, ttl);
        } else {
            cache.evict(clientId, claims.getSubject());
        }
        return new TokenOutcome(decision, clientId, claims.getSubject());
    }

    /** 거부 시 Keycloak 세션 정리 — refresh_token 로그아웃(최선 노력, 실패해도 거부는 유지). */
    public void revokeIssuedTokens(String tokenJson, String clientId, String clientAuthHeader, String clientSecret) {
        try {
            JsonNode token = objectMapper.readTree(tokenJson);
            String refresh = token.path("refresh_token").asText(null);
            if (refresh == null) return;
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("refresh_token", refresh);
            form.add("client_id", clientId);
            if (clientSecret != null) form.add("client_secret", clientSecret);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (clientAuthHeader != null) h.set(HttpHeaders.AUTHORIZATION, clientAuthHeader);
            restTemplate.exchange(URI.create(keycloak.getBaseUrl() + "/realms/" + keycloak.getRealm() + "/protocol/openid-connect/logout"),
                    HttpMethod.POST, new HttpEntity<>(form, h), String.class);
        } catch (Exception e) {
            log.warn("[OIDC-FRONT] 거부 후 Keycloak 세션 정리 실패 (거부는 유지): {}", e.getMessage());
        }
    }

    /**
     * userinfo 보강 — bearer access_token 의 {@code azp/sub} 로 캐시된 판정을 찾고(없으면 hub 재판정) {@code idem_*} 클레임을 보탠다.
     * 판정이 거부면 empty → 호출자가 403.
     */
    public Optional<String> enrichUserInfo(String bearerJwt, String userInfoJson, String correlationId) {
        JsonNode payload = unverifiedPayload(bearerJwt);
        String clientId = payload != null ? payload.path("azp").asText(null) : null;
        String sub = payload != null ? payload.path("sub").asText(null) : null;
        if (clientId == null || sub == null) {
            log.warn("[OIDC-FRONT] userinfo: access_token 에 azp/sub 없음 — 보강 없이 거부 cid={}", correlationId);
            return Optional.empty();
        }
        AccessDecision decision = cache.get(clientId, sub).orElseGet(() -> {
            AccessDecision fresh = hubAccessClient.evaluate(clientId, sub, payload.path("identity_provider").asText(null),
                    payload.path("acr").asText(null), payload.path("sid").asText(null), correlationId);
            if (fresh.allowed()) cache.put(clientId, sub, fresh, props.getDecisionCacheTtlSeconds());
            return fresh;
        });
        if (!decision.allowed()) return Optional.empty();
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(userInfoJson);
            node.put("idem_service", decision.serviceCode());
            node.put("idem_state", decision.state());
            if (decision.agencySubjectId() != null) {
                node.put("idem_subject", decision.agencySubjectId());
                node.put("idem_subject_scheme", decision.subjectScheme());
            }
            node.set("idem_roles", objectMapper.valueToTree(decision.roles() != null ? decision.roles() : List.of()));
            if (decision.assigned() != null) node.put("idem_assigned", decision.assigned());
            if (decision.qimUserId() != null) node.put("idem_user_id", decision.qimUserId());
            if (decision.sessionPolicy() != null) node.set("idem_session_policy", objectMapper.valueToTree(decision.sessionPolicy()));
            return Optional.of(objectMapper.writeValueAsString(node));
        } catch (Exception e) {
            log.error("[OIDC-FRONT] userinfo 보강 실패 → 거부: cid={} err={}", correlationId, e.getMessage());
            return Optional.empty();
        }
    }

    /** RP-Initiated Logout(S6 PR-2): id_token_hint 를 검증해 sub·sid 를 얻고, 판정 캐시를 비우고 hub 에 알린다. 실패는 로그만. */
    public void onRpInitiatedLogout(String idTokenHint, String correlationId) {
        try {
            KeycloakIdTokenClaims claims = jwksVerifier.verify(idTokenHint, correlationId);
            int evicted = cache.evictBySub(claims.getSubject());
            int expired = hubSessionClient.notifyIdpLogout(claims.getSubject(), claims.getSessionId(), "RP_INITIATED_LOGOUT", correlationId);
            log.info("[OIDC-FRONT] RP-Initiated Logout: client={} sid={} cacheEvicted={} feExpired={} cid={}",
                    claims.getAuthorizedParty(), claims.getSessionId() != null, evicted, expired, correlationId);
        } catch (Exception e) {
            log.warn("[OIDC-FRONT] id_token_hint 검증 실패 — Idem 쪽 정리 없이 Keycloak 에 전달: cid={} err={}", correlationId, e.getMessage());
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = n.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** Keycloak 이 이미 검증한 bearer 의 payload — 서명은 보지 않는다(userinfo 200 이 검증의 증거). */
    JsonNode unverifiedPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return null;
            return objectMapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
