package io.github.hipstermin.idem.hub.broker.keycloak.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.broker.keycloak.KeycloakProperties;
import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcRpProperties;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Keycloak Admin REST 최소 클라이언트 (S6) — client 프로비저닝에 필요한 호출만.
 *
 * <p>인증은 {@code idem-provisioner} 서비스 계정의 {@code client_credentials} 토큰이며 만료 30초 전까지 재사용한다.
 * 실패는 전부 {@link KeycloakAdminException} 으로 올린다(fail-closed 는 호출자 책임). 관리자 비밀번호는 쓰지 않는다 —
 * 설치자의 {@code KEYCLOAK_ADMIN_PASSWORD} 와 앱이 쓰는 자격을 분리한다.
 *
 * <p>1.2 — Keycloak 26.8.2 의 관리 API 강화 두 가지를 오류 문구로 알린다(조치는 {@code scripts/upgrade/keycloak-realm-1.2.sh}):
 * <ul>
 *   <li><b>401</b>: 토큰에 client scope {@code kc-admin-api} 가 없으면 관리 API 가 받지 않는다. 캐시한 토큰도 버린다 — 운영자가 Keycloak 쪽을
 *       고치면 재기동·만료 대기 없이 다음 호출이 새 토큰으로 간다.</li>
 *   <li><b>403</b>: {@code manage-realm} 이 없는 관리자(이 서비스 계정은 {@code manage-clients} 만)는 realm 속성
 *       {@code allowedProtocolMapperTypesForClientAdministrators} 에 적힌 protocol mapper 종류만 다룰 수 있다 — 기관 client 생성이 mapper 를 넣는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class KeycloakAdminClient {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** 관리 API 401 에 붙이는 조치 안내 — 콘솔(E-IDO-122 문구)과 로그에 그대로 보인다. */
    static final String UNAUTHORIZED_HINT = " — Keycloak 이 서비스 계정 토큰을 거부했다. Keycloak 26.8.2 이상은 관리 API 토큰에 client scope"
            + " 'kc-admin-api' 가 있어야 한다: 기존 realm 은 scripts/upgrade/keycloak-realm-1.2.sh 를 한 번 실행한다(새 설치는 realm import 가 넣는다)";

    /** 관리 API 403 에 붙이는 조치 안내 — 역할이 모자라거나, Keycloak 26.8.2 이상에서 protocol mapper 허용 목록이 비어 있다. */
    static final String FORBIDDEN_HINT = " — 서비스 계정에 이 작업의 권한이 없다. realm-management 역할(manage-clients·view-clients)을 확인하고, Keycloak 26.8.2 이상이면"
            + " realm 속성 'allowedProtocolMapperTypesForClientAdministrators' 에 hub 가 쓰는 mapper 종류가 있어야 한다(비어 있으면 mapper 를 넣는 client 생성이 거부된다):"
            + " 기존 realm 은 scripts/upgrade/keycloak-realm-1.2.sh 를 한 번 실행한다(새 설치는 realm import 가 넣는다)";

    private final RestTemplate restTemplate;
    private final KeycloakProperties keycloak;
    private final OidcRpProperties props;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public KeycloakAdminClient(@Qualifier("keycloakAdminRestTemplate") RestTemplate restTemplate,
                               KeycloakProperties keycloak, OidcRpProperties props, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.keycloak = keycloak;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ── clients ────────────────────────────────────────────────────────────

    /** {@code clientId}(사람이 보는 ID) 로 client 를 찾는다 — 반환 맵의 {@code id} 가 Keycloak 내부 UUID. */
    public Optional<Map<String, Object>> findClientByClientId(String clientId) {
        String body = exchange(HttpMethod.GET, adminBase() + "/clients?clientId=" + clientId + "&first=0&max=2", null);
        List<Map<String, Object>> found = read(body, LIST_OF_MAP);
        return found.stream().filter(c -> clientId.equals(c.get("clientId"))).findFirst();
    }

    /** client 생성 → Keycloak 내부 UUID (Location 헤더 마지막 세그먼트). */
    public String createClient(Map<String, Object> representation) {
        ResponseEntity<String> resp = exchangeRaw(HttpMethod.POST, adminBase() + "/clients", representation);
        URI location = resp.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakAdminException("Keycloak client 생성 응답에 Location 이 없음: status=" + resp.getStatusCode());
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** 부분 갱신 — 보낸 항목만 바뀐다(attributes·protocolMappers 를 보내지 않으면 유지). */
    public void updateClient(String uuid, Map<String, Object> representation) {
        exchange(HttpMethod.PUT, adminBase() + "/clients/" + uuid, representation);
    }

    public List<Map<String, Object>> getProtocolMappers(String uuid) {
        String body = exchange(HttpMethod.GET, adminBase() + "/clients/" + uuid + "/protocol-mappers/models", null);
        return read(body, LIST_OF_MAP);
    }

    public void addProtocolMapper(String uuid, Map<String, Object> mapper) {
        exchange(HttpMethod.POST, adminBase() + "/clients/" + uuid + "/protocol-mappers/models", mapper);
    }

    public String getClientSecret(String uuid) {
        String body = exchange(HttpMethod.GET, adminBase() + "/clients/" + uuid + "/client-secret", null);
        return secretValue(body);
    }

    public String regenerateClientSecret(String uuid) {
        String body = exchange(HttpMethod.POST, adminBase() + "/clients/" + uuid + "/client-secret", null);
        return secretValue(body);
    }

    // ── internals ──────────────────────────────────────────────────────────

    String adminBase() {
        return keycloak.getBaseUrl() + "/admin/realms/" + keycloak.getRealm();
    }

    private String secretValue(String body) {
        Object v = read(body, MAP).get("value");
        if (v == null || v.toString().isBlank()) throw new KeycloakAdminException("Keycloak client-secret 응답에 value 가 없음");
        return v.toString();
    }

    private <T> T read(String body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body == null ? "null" : body, type);
        } catch (Exception e) {
            throw new KeycloakAdminException("Keycloak 응답 파싱 실패", e);
        }
    }

    private String exchange(HttpMethod method, String url, Object body) {
        return exchangeRaw(method, url, body).getBody();
    }

    private ResponseEntity<String> exchangeRaw(HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        String token = accessToken();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), method, new HttpEntity<>(body, headers), String.class);
            if (resp.getStatusCode().value() == 401) {
                // 토큰이 통하지 않는다 — 캐시를 버려 다음 호출이 새 토큰으로 가게 한다(만료까지 같은 401 을 되풀이하지 않는다)
                invalidateToken(token);
                throw new KeycloakAdminException("Keycloak Admin " + method + " " + url + " → " + resp.getStatusCode() + UNAUTHORIZED_HINT);
            }
            if (resp.getStatusCode().value() == 403) {
                throw new KeycloakAdminException("Keycloak Admin " + method + " " + url + " → " + resp.getStatusCode() + FORBIDDEN_HINT);
            }
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new KeycloakAdminException("Keycloak Admin " + method + " " + url + " → " + resp.getStatusCode());
            }
            return resp;
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak Admin " + method + " " + url + " 실패: " + e.getMessage(), e);
        }
    }

    /** 거절당한 토큰이 아직 캐시에 있으면 버린다 — 다음 {@link #accessToken()} 이 새로 받는다(그 사이 다른 스레드가 받은 새 토큰은 건드리지 않는다). */
    synchronized void invalidateToken(String rejected) {
        if (rejected != null && rejected.equals(cachedToken)) {
            cachedToken = null;
            cachedTokenExpiry = Instant.EPOCH;
        }
    }

    /** 서비스 계정 토큰 — 만료 30초 전까지 재사용. 비밀 미설정이면 호출하지 않고 바로 실패. */
    synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) return cachedToken;
        String secret = props.getProvisioner().getClientSecret();
        if (secret == null || secret.isBlank()) {
            throw new KeycloakAdminException("KEYCLOAK_PROVISIONER_CLIENT_SECRET 이 설정되지 않아 Keycloak 프로비저닝을 할 수 없습니다");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.getProvisioner().getClientId());
        form.add("client_secret", secret);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(keycloak.tokenEndpoint()), HttpMethod.POST,
                    new HttpEntity<>(form, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new KeycloakAdminException("Keycloak 프로비저너 토큰 발급 실패: " + resp.getStatusCode());
            }
            Map<String, Object> token = read(resp.getBody(), MAP);
            Object access = token.get("access_token");
            if (access == null) throw new KeycloakAdminException("Keycloak 프로비저너 토큰 응답에 access_token 없음");
            long expiresIn = token.get("expires_in") instanceof Number n ? n.longValue() : 60L;
            cachedToken = access.toString();
            cachedTokenExpiry = Instant.now().plusSeconds(Math.max(1, expiresIn - 30));
            return cachedToken;
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak 프로비저너 토큰 발급 실패: " + e.getMessage(), e);
        }
    }
}
