package io.github.hipstermin.idem.gate.keycloak;

import io.github.hipstermin.idem.gate.metrics.AuthMetrics;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak 세션 종료 (SLO ①) — S6 PR-2 에서 다시 씀.
 *
 * <p>종전 구현은 (a) {@code q-sign-client} 로 client_credentials 를 시도했으나 그 client 는 서비스 계정이 없고,
 * (b) {@code qimUserId} 를 Keycloak username 으로 찾았으며, (c) 비표준 {@code DELETE /users/{id}/sessions} 를 불렀다 —
 * 세 이유로 한 번도 성공할 수 없었다(2차 적대적 점검). 지금은:
 * <ul>
 *   <li>서비스 계정 {@code idem-session-manager}(realm-management view-users·manage-users) 토큰, 만료 30초 전까지 재사용</li>
 *   <li>{@code sid} 가 있으면 {@code DELETE /admin/realms/{realm}/sessions/{sid}} — 그 세션만 정확히</li>
 *   <li>없고 {@code sub}(Keycloak 사용자 UUID) 만 있으면 {@code POST /admin/realms/{realm}/users/{sub}/logout} — 사용자 전체</li>
 * </ul>
 * Keycloak 은 세션이 끝나면 그 세션에 참여한 client(기관 RP 의 {@code backchannel.logout.url}, gate 자신의 수신기)에
 * Back-Channel Logout 을 보낸다 — 그래서 이 한 호출로 RP 세션까지 정리된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakLogoutService {

    private final KeycloakProperties keycloakProperties;
    private final RestTemplate restTemplate;
    private final AuthMetrics authMetrics;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    /** 결과: {@code REVOKED_SESSION}(sid 로) · {@code REVOKED_USER}(sub 로 전체) · {@code NOT_FOUND} · {@code SKIPPED} · {@code FAILED}. */
    public enum Outcome { REVOKED_SESSION, REVOKED_USER, NOT_FOUND, SKIPPED, FAILED }

    public Outcome revoke(String sub, String sid, String correlationId) {
        boolean hasSid = sid != null && !sid.isBlank();
        boolean hasSub = sub != null && !sub.isBlank();
        if (!hasSid && !hasSub) {
            log.warn("[KeycloakLogout] sub·sid 모두 없음 — 세션 종료 스킵: correlationId={}", correlationId);
            return Outcome.SKIPPED;
        }
        try {
            String token = serviceAccountToken();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.set("X-Correlation-Id", correlationId);
            String base = keycloakProperties.getBaseUrl() + "/admin/realms/" + keycloakProperties.getRealm();
            if (hasSid) {
                ResponseEntity<Void> resp = restTemplate.exchange(URI.create(base + "/sessions/" + sid), HttpMethod.DELETE,
                        new HttpEntity<>(headers), Void.class);
                if (!resp.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("status " + resp.getStatusCode());
                authMetrics.incrementSloKeycloakSuccess();
                log.info("[KeycloakLogout] 세션 종료(sid): correlationId={}", correlationId);
                return Outcome.REVOKED_SESSION;
            }
            ResponseEntity<Void> resp = restTemplate.exchange(URI.create(base + "/users/" + sub + "/logout"), HttpMethod.POST,
                    new HttpEntity<>(headers), Void.class);
            if (!resp.getStatusCode().is2xxSuccessful()) throw new IllegalStateException("status " + resp.getStatusCode());
            authMetrics.incrementSloKeycloakSuccess();
            log.info("[KeycloakLogout] 사용자 세션 전체 종료(sub): correlationId={}", correlationId);
            return Outcome.REVOKED_USER;
        } catch (HttpClientErrorException.NotFound e) {
            // 이미 끝난 세션 — 목표 상태와 같으므로 성공으로 센다
            authMetrics.incrementSloKeycloakSuccess();
            log.info("[KeycloakLogout] Keycloak 에 세션 없음(이미 종료): sid={} correlationId={}", hasSid, correlationId);
            return Outcome.NOT_FOUND;
        } catch (Exception e) {
            authMetrics.incrementSloKeycloakFailure();
            log.warn("[KeycloakLogout] Keycloak 세션 종료 실패 (비치명적): sid={} correlationId={} cause={}", hasSid, correlationId, e.getMessage());
            return Outcome.FAILED;
        }
    }

    /** 종전 시그니처 호환 — sub 만으로 사용자 전체 종료. */
    public void revokeKeycloakSession(String sub, String correlationId) {
        revoke(sub, null, correlationId);
    }

    @SuppressWarnings("unchecked")
    synchronized String serviceAccountToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) return cachedToken;
        KeycloakProperties.SessionManager sm = keycloakProperties.getSessionManager();
        if (sm.getClientSecret() == null || sm.getClientSecret().isBlank()) {
            throw new IllegalStateException("KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET 이 설정되지 않아 Keycloak 세션을 끊을 수 없습니다");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", sm.getClientId());
        form.add("client_secret", sm.getClientSecret());
        ResponseEntity<Map> resp = restTemplate.exchange(URI.create(keycloakProperties.tokenEndpoint()), HttpMethod.POST,
                new HttpEntity<>(form, headers), Map.class);
        if (resp.getBody() == null || resp.getBody().get("access_token") == null) {
            throw new IllegalStateException("세션 관리 서비스 계정 토큰 발급 실패: " + resp.getStatusCode());
        }
        long expiresIn = resp.getBody().get("expires_in") instanceof Number n ? n.longValue() : 60L;
        cachedToken = resp.getBody().get("access_token").toString();
        cachedTokenExpiry = Instant.now().plusSeconds(Math.max(1, expiresIn - 30));
        return cachedToken;
    }
}
