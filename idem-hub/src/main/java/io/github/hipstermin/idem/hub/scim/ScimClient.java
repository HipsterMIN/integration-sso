package io.github.hipstermin.idem.hub.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * 1.1 기관 SCIM 2.0 서버 클라이언트 — 표준 부분집합만 쓴다(RFC 7644).
 *
 * <ul>
 *   <li>{@code GET  {base}/Users?filter=externalId eq "x"} → {@code Resources[0].id}</li>
 *   <li>{@code POST {base}/Users} {schemas, externalId, userName, active}</li>
 *   <li>{@code PATCH {base}/Users/{id}} {Operations:[{op:replace, path:active, value}]}</li>
 *   <li>{@code DELETE {base}/Users/{id}}</li>
 *   <li>{@code GET  {base}/Groups?filter=displayName eq "role"} / {@code POST {base}/Groups} / {@code PATCH {base}/Groups/{id}} members add·remove</li>
 * </ul>
 * 인증은 {@code Authorization: Bearer <token>}. 기관이 filter 를 지원하지 않으면(400·501) 동기화할 수 없다 — 릴레이가 FAILED 로 남긴다.
 */
@Slf4j
@Component
public class ScimClient {

    static final String SCHEMA_USER  = "urn:ietf:params:scim:schemas:core:2.0:User";
    static final String SCHEMA_GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    static final String SCHEMA_PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /** PATCH 를 쓰므로 HttpURLConnection 기반(webhookRestTemplate)이 아니라 JDK HttpClient 기반 템플릿을 받는다({@link ScimConfig}). */
    public ScimClient(@Qualifier("scimRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 기관 연결 정보 — 릴레이가 프로파일·자격증명에서 만든다. */
    public record Target(String baseUrl, String bearerToken, String correlationId) {
        String url(String path) {
            String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return b + path;
        }
    }

    /** 기관 응답 오류 — HTTP 상태를 릴레이가 재시도/실패 판정에 쓴다. */
    public static class ScimException extends RuntimeException {
        private final int status;
        public ScimException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    // ── Users ──────────────────────────────────────────────────────────────

    public Optional<String> findUserId(Target t, String externalId) {
        JsonNode list = get(t, "/Users?filter=" + enc("externalId eq \"" + externalId + "\""));
        return firstId(list);
    }

    /** 있으면 active=true 로 갱신, 없으면 생성. @return 기관 쪽 id */
    public String ensureUser(Target t, String externalId, boolean active) {
        Optional<String> id = findUserId(t, externalId);
        if (id.isPresent()) {
            patchActive(t, "/Users/" + id.get(), active);
            return id.get();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(SCHEMA_USER));
        body.put("externalId", externalId);
        body.put("userName", externalId);
        body.put("active", active);
        JsonNode created = send(t, HttpMethod.POST, "/Users", body);
        String newId = created != null ? created.path("id").asText(null) : null;
        return newId != null ? newId : findUserId(t, externalId).orElse(null);
    }

    /** 사용자가 없으면 아무것도 하지 않는다(멱등). */
    public boolean deactivateUser(Target t, String externalId) {
        Optional<String> id = findUserId(t, externalId);
        if (id.isEmpty()) return false;
        patchActive(t, "/Users/" + id.get(), false);
        return true;
    }

    public boolean deleteUser(Target t, String externalId) {
        Optional<String> id = findUserId(t, externalId);
        if (id.isEmpty()) return false;
        try {
            send(t, HttpMethod.DELETE, "/Users/" + id.get(), null);
        } catch (ScimException e) {
            if (e.status() != 404) throw e;   // 이미 없음 = 멱등
        }
        return true;
    }

    // ── Groups ─────────────────────────────────────────────────────────────

    /** 그룹(displayName = roleCode) 을 찾거나 만들고 멤버를 넣는다. 사용자가 기관에 없으면 먼저 만든다. */
    public void addGroupMember(Target t, String roleCode, String externalId) {
        String userId = ensureUser(t, externalId, true);
        String groupId = findGroupId(t, roleCode).orElseGet(() -> createGroup(t, roleCode));
        patchMembers(t, groupId, "add", userId);
    }

    /** 그룹이나 사용자가 없으면 아무것도 하지 않는다(멱등). */
    public boolean removeGroupMember(Target t, String roleCode, String externalId) {
        Optional<String> groupId = findGroupId(t, roleCode);
        Optional<String> userId = findUserId(t, externalId);
        if (groupId.isEmpty() || userId.isEmpty()) return false;
        patchMembers(t, groupId.get(), "remove", userId.get());
        return true;
    }

    Optional<String> findGroupId(Target t, String displayName) {
        return firstId(get(t, "/Groups?filter=" + enc("displayName eq \"" + displayName + "\"")));
    }

    private String createGroup(Target t, String displayName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(SCHEMA_GROUP));
        body.put("displayName", displayName);
        JsonNode created = send(t, HttpMethod.POST, "/Groups", body);
        String id = created != null ? created.path("id").asText(null) : null;
        if (id == null) id = findGroupId(t, displayName).orElse(null);
        if (id == null) throw new ScimException(502, "그룹 생성 응답에 id 없음: " + displayName);
        return id;
    }

    private void patchMembers(Target t, String groupId, String op, String userId) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", op);
        if ("remove".equals(op)) {
            operation.put("path", "members[value eq \"" + userId + "\"]");
        } else {
            operation.put("path", "members");
            operation.put("value", List.of(Map.of("value", userId)));
        }
        send(t, HttpMethod.PATCH, "/Groups/" + groupId, Map.of("schemas", List.of(SCHEMA_PATCH), "Operations", List.of(operation)));
    }

    private void patchActive(Target t, String path, boolean active) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("op", "replace");
        operation.put("path", "active");
        operation.put("value", active);
        send(t, HttpMethod.PATCH, path, Map.of("schemas", List.of(SCHEMA_PATCH), "Operations", List.of(operation)));
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private JsonNode get(Target t, String path) {
        return send(t, HttpMethod.GET, path, null);
    }

    private JsonNode send(Target t, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.bearerToken());
        h.setAccept(List.of(SCIM_JSON, MediaType.APPLICATION_JSON));
        h.set("X-Correlation-Id", t.correlationId() != null ? t.correlationId() : "");
        HttpEntity<?> entity;
        if (body != null) {
            h.setContentType(SCIM_JSON);
            try {
                entity = new HttpEntity<>(objectMapper.writeValueAsString(body), h);
            } catch (Exception e) {
                throw new ScimException(500, "SCIM 본문 직렬화 실패: " + e.getMessage());
            }
        } else {
            entity = new HttpEntity<>(h);
        }
        try {
            // 문자열 URL 은 템플릿으로 다시 인코딩된다(%20 → %2520) — 완성된 URI 로 보낸다
            ResponseEntity<String> res = restTemplate.exchange(java.net.URI.create(t.url(path)), method, entity, String.class);
            int status = res.getStatusCode().value();
            if (status < 200 || status >= 300) throw new ScimException(status, "SCIM 응답 " + status);
            String text = res.getBody();
            if (text == null || text.isBlank()) return null;
            return objectMapper.readTree(text);
        } catch (HttpStatusCodeException e) {
            throw new ScimException(e.getStatusCode().value(), "SCIM " + method + " " + path + " → " + e.getStatusCode().value()
                    + (e.getResponseBodyAsString().isBlank() ? "" : " " + truncate(e.getResponseBodyAsString(), 200)));
        } catch (ScimException e) {
            throw e;
        } catch (Exception e) {
            throw new ScimException(0, "SCIM " + method + " " + path + " 연결 실패: " + e.getMessage());
        }
    }

    private static Optional<String> firstId(JsonNode list) {
        if (list == null) return Optional.empty();
        JsonNode resources = list.path("Resources");
        if (!resources.isArray() || resources.isEmpty()) return Optional.empty();
        String id = resources.get(0).path("id").asText(null);
        return Optional.ofNullable(id);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
