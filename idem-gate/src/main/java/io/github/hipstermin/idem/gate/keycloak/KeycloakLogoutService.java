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
 *
 * <p>1.2: 관리 API 가 401 이면 캐시한 토큰을 버리고 조치를 로그에 남긴다 — Keycloak 26.8.2 부터 관리 API 는 토큰에 client scope
 * {@code kc-admin-api} 를 요구한다({@code scripts/upgrade/keycloak-realm-1.2.sh}). 운영자가 고치면 재기동·만료 대기 없이 다음 호출
 * (hub 의 재시도 큐 포함)이 새 토큰으로 간다.
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
        String token = null;
        try {
            token = serviceAccountToken();
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
        } catch (HttpClientErrorException.Unauthorized e) {
            // 토큰이 통하지 않는다 — 캐시를 버려 다음 호출(hub 재시도 포함)이 새 토큰으로 가게 한다. 토큰 발급 자체의 401(비밀 불일치)도 여기로 온다(버릴 토큰 없음)
            invalidateToken(token);
            authMetrics.incrementSloKeycloakFailure();
            log.error("[KeycloakLogout] Keycloak 이 401 로 거부 — 세션을 끊지 못했다: sid={} correlationId={}. 서비스 계정 {} 의 client secret 과, "
                    + "Keycloak 26.8.2 이상이면 client scope 'kc-admin-api'(기존 realm 은 scripts/upgrade/keycloak-realm-1.2.sh 를 한 번 실행)를 확인하세요",
                    hasSid, correlationId, keycloakProperties.getSessionManager().getClientId());
            return Outcome.FAILED;
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

    /** 거절당한 토큰이 아직 캐시에 있으면 버린다 — 다음 {@link #serviceAccountToken()} 이 새로 받는다(그 사이 다른 스레드가 받은 새 토큰은 건드리지 않는다). */
    synchronized void invalidateToken(String rejected) {
        if (rejected != null && rejected.equals(cachedToken)) {
            cachedToken = null;
            cachedTokenExpiry = Instant.EPOCH;
        }
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
