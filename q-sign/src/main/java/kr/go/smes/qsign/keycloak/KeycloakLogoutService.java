package kr.go.smes.qsign.keycloak;

import kr.go.smes.qsign.metrics.AuthMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Keycloak 세션 강제 종료 서비스 (SLO — Single Logout)
 * 설계서 §13.3 / Sprint 2 P1-02
 *
 * <p><b>SLO 흐름</b>:
 * <pre>
 *   IdO SloController
 *     → POST /api/v1/internal/session/logout  (q-sign InternalSessionController)
 *       → KeycloakLogoutService.revokeKeycloakSession(sub, correlationId)
 *           ① Client Credentials로 Admin Access Token 취득
 *           ② GET /admin/realms/{realm}/users?username={sub} → userId 조회
 *           ③ DELETE /admin/realms/{realm}/users/{userId}/sessions → 세션 일괄 종료
 * </pre>
 *
 * <p><b>Keycloak Service Account 권한 필요</b>:
 * {@code realm-management} 클라이언트의 {@code manage-users}, {@code view-users} 롤 할당 필요.
 *
 * <p><b>비치명적 처리</b>:
 * Keycloak 세션 종료 실패 시 로그 경고만 출력하고 SLO 흐름을 중단하지 않는다.
 * feSession 만료와 기관 Webhook 발송은 별도 처리됨.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakLogoutService {

    private final KeycloakProperties keycloakProperties;
    private final RestTemplate restTemplate;
    private final AuthMetrics authMetrics;

    /**
     * Keycloak 내 해당 사용자의 모든 활성 세션 강제 종료
     *
     * <p>Keycloak Admin REST API:
     * <ol>
     *   <li>Client Credentials Grant → admin_access_token 취득</li>
     *   <li>GET  /admin/realms/{realm}/users?username={sub} → userId 조회</li>
     *   <li>DELETE /admin/realms/{realm}/users/{userId}/sessions → 세션 일괄 종료</li>
     * </ol>
     *
     * @param sub           Keycloak sub (ID Token sub claim = Keycloak user ID 또는 preferred_username)
     * @param correlationId 추적 ID
     */
    public void revokeKeycloakSession(String sub, String correlationId) {
        if (sub == null || sub.isBlank()) {
            log.warn("[KeycloakLogout] sub가 null/blank — 세션 종료 스킵: correlationId={}", correlationId);
            return;
        }

        try {
            String adminToken = obtainAdminToken(correlationId);
            String userId = resolveUserId(sub, adminToken, correlationId);
            if (userId != null) {
                deleteUserSessions(userId, adminToken, correlationId);
                authMetrics.incrementSloKeycloakSuccess();
                log.info("[KeycloakLogout] 세션 종료 완료: sub={} userId={} correlationId={}",
                        sub, userId, correlationId);
            } else {
                authMetrics.incrementSloKeycloakFailure();
                log.warn("[KeycloakLogout] Keycloak 사용자 조회 실패 — sub={} correlationId={}",
                        sub, correlationId);
            }
        } catch (Exception e) {
            // 비치명적 — SLO 흐름 계속 진행
            authMetrics.incrementSloKeycloakFailure();
            log.warn("[KeycloakLogout] Keycloak 세션 종료 실패 (비치명적): sub={} correlationId={} cause={}",
                    sub, correlationId, e.getMessage());
        }
    }

    // ── private ─────────────────────────────────────────────────────────────

    /**
     * Client Credentials Grant로 Admin Access Token 취득
     *
     * <p>POST {baseUrl}/realms/{realm}/protocol/openid-connect/token
     * grant_type=client_credentials &amp; client_id &amp; client_secret
     */
    @SuppressWarnings("unchecked")
    private String obtainAdminToken(String correlationId) {
        String tokenEndpoint = keycloakProperties.tokenEndpoint();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("X-Correlation-Id", correlationId);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type",    "client_credentials");
        body.add("client_id",     keycloakProperties.getClientId());
        body.add("client_secret", keycloakProperties.getClientSecret());

        ResponseEntity<Map> resp = restTemplate.exchange(
                tokenEndpoint, HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        if (resp.getBody() == null || !resp.getBody().containsKey("access_token")) {
            throw new IllegalStateException("[KeycloakLogout] admin access_token 취득 실패");
        }
        return (String) resp.getBody().get("access_token");
    }

    /**
     * sub (preferred_username 또는 userId) 기반 Keycloak userId 조회
     *
     * <p>GET /admin/realms/{realm}/users?username={sub}&exact=true
     * 결과가 없거나 여러 건이면 null 반환.
     */
    @SuppressWarnings("unchecked")
    private String resolveUserId(String sub, String adminToken, String correlationId) {
        String baseUrl  = keycloakProperties.getBaseUrl();
        String realm    = keycloakProperties.getRealm();
        String usersUrl = baseUrl + "/admin/realms/" + realm + "/users?username=" +
                          sub + "&exact=true&max=1";

        HttpHeaders headers = buildAdminHeaders(adminToken, correlationId);

        try {
            ResponseEntity<List> resp = restTemplate.exchange(
                    usersUrl, HttpMethod.GET,
                    new HttpEntity<>(headers),
                    List.class);

            if (resp.getBody() == null || resp.getBody().isEmpty()) return null;
            Map<String, Object> user = (Map<String, Object>) resp.getBody().get(0);
            return (String) user.get("id");
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /**
     * userId에 해당하는 모든 Keycloak 세션 삭제
     *
     * <p>DELETE /admin/realms/{realm}/users/{userId}/sessions
     */
    private void deleteUserSessions(String userId, String adminToken, String correlationId) {
        String baseUrl     = keycloakProperties.getBaseUrl();
        String realm       = keycloakProperties.getRealm();
        String sessionsUrl = baseUrl + "/admin/realms/" + realm +
                             "/users/" + userId + "/sessions";

        HttpHeaders headers = buildAdminHeaders(adminToken, correlationId);

        restTemplate.exchange(
                sessionsUrl, HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class);

        log.debug("[KeycloakLogout] DELETE sessions: userId={} correlationId={}", userId, correlationId);
    }

    private HttpHeaders buildAdminHeaders(String adminToken, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.set("X-Correlation-Id", correlationId);
        return headers;
    }
}
