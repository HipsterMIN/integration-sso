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
 */
@Slf4j
@Component
public class KeycloakAdminClient {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

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
        headers.setBearerAuth(accessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> resp = restTemplate.exchange(URI.create(url), method, new HttpEntity<>(body, headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new KeycloakAdminException("Keycloak Admin " + method + " " + url + " → " + resp.getStatusCode());
            }
            return resp;
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Keycloak Admin " + method + " " + url + " 실패: " + e.getMessage(), e);
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
