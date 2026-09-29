package io.github.hipstermin.idem.tenant.scim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1 기관 쪽 <b>SCIM 2.0 서버 참조 구현</b>(메모리) — Idem 의 SCIM 아웃바운드가 부르는 부분집합만 구현한다.
 *
 * <pre>
 *   GET    /scim/v2/Users?filter=externalId eq "x"      GET    /scim/v2/Groups?filter=displayName eq "ROLE"
 *   POST   /scim/v2/Users {externalId,userName,active}   POST   /scim/v2/Groups {displayName}
 *   PATCH  /scim/v2/Users/{id} {Operations:[{op:replace,path:active,value}]}
 *   PATCH  /scim/v2/Groups/{id} {Operations:[{op:add,path:members,value:[{value}]}|{op:remove,path:members[value eq "id"]}]}
 *   DELETE /scim/v2/Users/{id}
 * </pre>
 * 인증: {@code Authorization: Bearer {idem.sample.scim.token}}. 실제 기관은 이 계약을 자기 사용자 디렉터리에 맞춰 구현한다.
 */
@Slf4j
@RestController
@RequestMapping("/scim/v2")
public class ScimServerController {

    private static final Pattern FILTER = Pattern.compile("^(\\w+)\\s+eq\\s+\"([^\"]*)\"$");
    private static final Pattern MEMBER_REMOVE = Pattern.compile("^members\\[value eq \"([^\"]+)\"\\]$");

    private final String token;
    private final Map<String, Map<String, Object>> users = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> groups = new ConcurrentHashMap<>();

    public ScimServerController(@Value("${idem.sample.scim.token:stub-scim-token-dev}") String token) {
        this.token = token;
    }

    // ── Users ──────────────────────────────────────────────────────────────

    @GetMapping("/Users")
    public ResponseEntity<?> listUsers(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestParam(value = "filter", required = false) String filter) {
        if (!authorized(auth)) return unauthorized();
        return ResponseEntity.ok(list(filtered(users, filter)));
    }

    @PostMapping("/Users")
    public ResponseEntity<?> createUser(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody Map<String, Object> body) {
        if (!authorized(auth)) return unauthorized();
        String ext = (String) body.get("externalId");
        if (ext != null && !filtered(users, "externalId eq \"" + ext + "\"").isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "409", "detail", "externalId 중복"));
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", UUID.randomUUID().toString());
        u.put("externalId", ext);
        u.put("userName", body.getOrDefault("userName", ext));
        u.put("active", Boolean.TRUE.equals(body.getOrDefault("active", true)));
        users.put((String) u.get("id"), u);
        log.info("[SCIM-Server] 사용자 생성: externalId={} id={}", ext, u.get("id"));
        return ResponseEntity.status(HttpStatus.CREATED).body(u);
    }

    @PatchMapping("/Users/{id}")
    public ResponseEntity<?> patchUser(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!authorized(auth)) return unauthorized();
        Map<String, Object> u = users.get(id);
        if (u == null) return notFound();
        for (Map<String, Object> op : operations(body)) {
            if ("replace".equalsIgnoreCase(String.valueOf(op.get("op"))) && "active".equals(op.get("path"))) {
                u.put("active", Boolean.TRUE.equals(op.get("value")));
            }
        }
        log.info("[SCIM-Server] 사용자 갱신: id={} active={}", id, u.get("active"));
        return ResponseEntity.ok(u);
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<?> deleteUser(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id) {
        if (!authorized(auth)) return unauthorized();
        if (users.remove(id) == null) return notFound();
        groups.values().forEach(g -> members(g).removeIf(m -> id.equals(m.get("value"))));
        log.info("[SCIM-Server] 사용자 삭제: id={}", id);
        return ResponseEntity.noContent().build();
    }

    // ── Groups ─────────────────────────────────────────────────────────────

    @GetMapping("/Groups")
    public ResponseEntity<?> listGroups(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestParam(value = "filter", required = false) String filter) {
        if (!authorized(auth)) return unauthorized();
        return ResponseEntity.ok(list(filtered(groups, filter)));
    }

    @PostMapping("/Groups")
    public ResponseEntity<?> createGroup(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody Map<String, Object> body) {
        if (!authorized(auth)) return unauthorized();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", UUID.randomUUID().toString());
        g.put("displayName", body.get("displayName"));
        g.put("members", new ArrayList<Map<String, Object>>());
        groups.put((String) g.get("id"), g);
        log.info("[SCIM-Server] 그룹 생성: displayName={} id={}", g.get("displayName"), g.get("id"));
        return ResponseEntity.status(HttpStatus.CREATED).body(g);
    }

    @PatchMapping("/Groups/{id}")
    public ResponseEntity<?> patchGroup(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable String id, @RequestBody Map<String, Object> body) {
        if (!authorized(auth)) return unauthorized();
        Map<String, Object> g = groups.get(id);
        if (g == null) return notFound();
        for (Map<String, Object> op : operations(body)) {
            String verb = String.valueOf(op.get("op")).toLowerCase();
            String path = String.valueOf(op.get("path"));
            if ("add".equals(verb) && "members".equals(path) && op.get("value") instanceof List<?> vals) {
                for (Object v : vals) {
                    if (v instanceof Map<?, ?> m && m.get("value") instanceof String uid
                            && members(g).stream().noneMatch(x -> uid.equals(x.get("value")))) {
                        members(g).add(new LinkedHashMap<>(Map.of("value", uid)));
                    }
                }
            } else if ("remove".equals(verb)) {
                Matcher m = MEMBER_REMOVE.matcher(path);
                if (m.matches()) members(g).removeIf(x -> m.group(1).equals(x.get("value")));
            }
        }
        log.info("[SCIM-Server] 그룹 갱신: id={} members={}", id, members(g).size());
        return ResponseEntity.ok(g);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private boolean authorized(String auth) {
        return auth != null && auth.equals("Bearer " + token);
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "401", "detail", "Bearer 토큰 필요"));
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "404"));
    }

    private static List<Map<String, Object>> filtered(Map<String, Map<String, Object>> store, String filter) {
        if (filter == null || filter.isBlank()) return new ArrayList<>(store.values());
        Matcher m = FILTER.matcher(filter.trim());
        if (!m.matches()) return List.of();
        return store.values().stream().filter(r -> m.group(2).equals(String.valueOf(r.get(m.group(1))))).toList();
    }

    private static Map<String, Object> list(List<Map<String, Object>> resources) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"));
        out.put("totalResults", resources.size());
        out.put("startIndex", 1);
        out.put("itemsPerPage", resources.size());
        out.put("Resources", resources);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operations(Map<String, Object> body) {
        Object ops = body.get("Operations");
        return ops instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> members(Map<String, Object> g) {
        return (List<Map<String, Object>>) g.computeIfAbsent("members", k -> new ArrayList<Map<String, Object>>());
    }
}
