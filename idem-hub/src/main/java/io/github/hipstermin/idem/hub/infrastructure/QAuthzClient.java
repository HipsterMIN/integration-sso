package io.github.hipstermin.idem.hub.infrastructure;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Q-Authz 내부 API 클라이언트 — 연합 인가 역할 조회.
 *
 * <p>토큰(CAST/Handoff) 발급 시 사용자×대상기관의 유효 역할을 조회해
 * 토큰 {@code roles[]} 클레임에 임베드한다. 플랫폼은 굵은 RBAC 역할만 배송하고,
 * 세밀한 권한 해석/집행은 대상 기관(또는 후속 L3 PDP)의 책임이다.
 *
 * <h3>가용성 정책 — D2 fail-secure</h3>
 * <p>{@code idem.hub.authz.enabled=true}(기본) 이면 조회 실패는 <b>거부</b>({@link PlatformErrorCode#IDEM_HUB_AUTHZ_UNAVAILABLE}, 503) 다 —
 * 종전에는 장애 시 빈 역할을 돌려 "역할 없음" 과 "인가 서비스 다운" 을 소비측이 구분할 수 없었다.
 * Idem IM(authz) 없이 SSO 만 쓰는 설치는 {@code idem.hub.authz.enabled=false} 로 <b>명시</b>해야 하며, 그때는 항상 빈 역할(L0)이다.
 *
 * @see io.github.hipstermin.idem.hub.infrastructure.QimClientImpl 동일 HTTP 클라이언트 패턴
 */
@Slf4j
@Component
public class QAuthzClient {

    private final RestTemplate qAuthzRestTemplate;

    @Value("${idem.hub.authz.base-url:http://localhost:8086}")
    private String qAuthzBaseUrl;

    /**
     * q-authz 내부 API 키 — {@code IDEM_HUB_AUTHZ_INTERNAL_API_KEY} 환경변수 주입.
     * D2: enabled 인데 비어 있으면 부팅 차단 (allow-empty-api-key 는 로컬·테스트 전용).
     */
    @Value("${idem.hub.authz.internal-api-key:}")
    private String qAuthzInternalApiKey;

    /** false 면 authz 를 호출하지 않고 항상 빈 역할 — SSO 단독 설치용 명시적 스위치 (조용한 폴백 아님) */
    @Value("${idem.hub.authz.enabled:true}")
    private boolean enabled = true;

    /** D2: enabled 인데 내부 API 키가 비면 부팅 차단 (로컬·테스트에서만 true) */
    @Value("${idem.hub.authz.allow-empty-api-key:false}")
    private boolean allowEmptyApiKey;

    @jakarta.annotation.PostConstruct
    void validateConfiguration() {
        if (enabled && (qAuthzInternalApiKey == null || qAuthzInternalApiKey.isBlank()) && !allowEmptyApiKey) {
            throw new IllegalStateException("[QAuthzClient] idem.hub.authz.enabled=true 인데 IDEM_HUB_AUTHZ_INTERNAL_API_KEY 가 비어 있습니다. "
                    + "키를 주입하거나, authz 없는 SSO 단독 설치면 idem.hub.authz.enabled=false 로 명시하십시오. "
                    + "로컬·테스트에서만 idem.hub.authz.allow-empty-api-key=true 로 우회 가능합니다.");
        }
        if (!enabled) {
            log.warn("[QAuthzClient] idem.hub.authz.enabled=false — 연합 인가 조회 없이 항상 빈 역할(L0) 로 동작합니다");
        }
    }

    public QAuthzClient(@Qualifier("qAuthzRestTemplate") RestTemplate qAuthzRestTemplate) {
        this.qAuthzRestTemplate = qAuthzRestTemplate;
    }

    /**
     * 사용자×기관의 유효 역할 코드 목록 조회.
     * {@code GET /api/v1/internal/authz/users/{qimUserId}/effective-roles?agencyCode=...}
     *
     * @return 역할 코드 목록(ACTIVE·만료 미경과). 미부여·authz 비활성 시 빈 리스트(절대 null 아님).
     * @throws PlatformException {@link PlatformErrorCode#IDEM_HUB_AUTHZ_UNAVAILABLE} — authz 장애·비정상 응답 (D2 fail-secure)
     */
    @SuppressWarnings("unchecked")
    public List<String> getEffectiveRoles(String qimUserId, String agencyCode, String correlationId) {
        if (qimUserId == null || qimUserId.isBlank() || agencyCode == null || agencyCode.isBlank()) {
            return Collections.emptyList();
        }
        if (!enabled) {
            return Collections.emptyList();
        }
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(qAuthzBaseUrl)
                    .path("/api/v1/internal/authz/users/{qimUserId}/effective-roles")
                    .queryParam("agencyCode", agencyCode)
                    .buildAndExpand(qimUserId)
                    .toUriString();

            ResponseEntity<Map> response = qAuthzRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object rolesObj = response.getBody().get("roles");
                if (rolesObj instanceof List<?> list) {
                    return list.stream().filter(String.class::isInstance)
                            .map(String.class::cast).toList();
                }
            }
            log.error("[QAuthzClient] 역할 조회 비정상 응답 → 거부: status={} user={} agency={}",
                    response.getStatusCode(), qimUserId, agencyCode);
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 비정상 응답: " + response.getStatusCode());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            // D2 fail-secure: 인가 서비스 장애를 "역할 없음" 으로 위장하지 않는다
            log.error("[QAuthzClient] 역할 조회 실패 → 거부: user={} agency={} err={}", qimUserId, agencyCode, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 조회 실패: " + e.getMessage());
        }
    }

    /**
     * S8-b: 할당 여부 + 유효 역할을 한 번에 — {@code GET /api/v1/internal/authz/users/{id}/access?agencyCode=} (읽기 전용, verify 경로).
     * 장애·비정상 응답은 {@link PlatformErrorCode#IDEM_HUB_AUTHZ_UNAVAILABLE} (fail-secure).
     * {@code idem.hub.authz.enabled=false} 면 {@link ServiceAccess#disabled()} — 할당 필수 정책은 그 자체로 거부된다.
     */
    public ServiceAccess getServiceAccess(String qimUserId, String agencyCode, String correlationId) {
        return getServiceAccess(qimUserId, agencyCode, null, correlationId);
    }

    /**
     * 1.1: 발급 경로의 접근 <b>평가</b> — {@code attributes} 가 있으면 {@code POST /users/{id}/access} 로 보내 authz 가
     * 직접 할당이 없을 때 그룹·속성 규칙을 평가해 할당을 실체화한다(source=RULE). {@code attributes} 가 null 이면 종전 GET(읽기 전용).
     *
     * <p>attributes 는 <b>비-PII 발급 컨텍스트</b>(authLevel·providerCode)만 싣는다 — 프로파일 identity 속성(이름 등)은 보내지 않는다.
     */
    @SuppressWarnings("unchecked")
    public ServiceAccess getServiceAccess(String qimUserId, String agencyCode, Map<String, String> attributes,
                                          String correlationId) {
        if (!enabled) {
            return ServiceAccess.disabled();
        }
        if (qimUserId == null || qimUserId.isBlank() || agencyCode == null || agencyCode.isBlank()) {
            return new ServiceAccess(true, false, null, List.of());
        }
        try {
            ResponseEntity<Map> response;
            if (attributes == null) {
                String url = UriComponentsBuilder
                        .fromHttpUrl(qAuthzBaseUrl)
                        .path("/api/v1/internal/authz/users/{qimUserId}/access")
                        .queryParam("agencyCode", agencyCode)
                        .buildAndExpand(qimUserId)
                        .toUriString();
                response = qAuthzRestTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), Map.class);
            } else {
                String url = UriComponentsBuilder
                        .fromHttpUrl(qAuthzBaseUrl)
                        .path("/api/v1/internal/authz/users/{qimUserId}/access")
                        .buildAndExpand(qimUserId)
                        .toUriString();
                HttpHeaders headers = buildHeaders(correlationId);
                headers.setContentType(MediaType.APPLICATION_JSON);
                Map<String, Object> body = Map.of("agencyCode", agencyCode, "attributes", attributes);
                response = qAuthzRestTemplate.exchange(
                        url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
            }
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> body = response.getBody();
                Object rolesObj = body.get("roles");
                List<String> roles = rolesObj instanceof List<?> list
                        ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                        : List.of();
                boolean assigned = Boolean.TRUE.equals(body.get("assigned"));
                Object src = body.get("assignmentSource");
                return new ServiceAccess(true, assigned, src instanceof String st ? st : null, roles);
            }
            log.error("[QAuthzClient] 접근 정보 조회 비정상 응답 → 거부: status={} user={} agency={}",
                    response.getStatusCode(), qimUserId, agencyCode);
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 비정상 응답: " + response.getStatusCode());
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[QAuthzClient] 접근 정보 조회 실패 → 거부: user={} agency={} err={}", qimUserId, agencyCode, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "authz 조회 실패: " + e.getMessage());
        }
    }

    /**
     * 1.1: 인가 이벤트 피드 — {@code GET /api/v1/internal/authz/events?afterCreatedAt=&afterEventId=&limit=}.
     * {@code (createdAt, eventId)} 키셋 이후의 이벤트를 생성순으로. authz 비활성이면 빈 목록, 장애는 예외(폴러가 다음 주기에 재시도).
     */
    public List<AuthzEventRecord> fetchAssignmentEvents(java.time.Instant afterCreatedAt, String afterEventId,
                                                        int limit, String correlationId) {
        if (!enabled) {
            return Collections.emptyList();
        }
        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl(qAuthzBaseUrl)
                    .path("/api/v1/internal/authz/events")
                    .queryParam("afterCreatedAt", afterCreatedAt != null ? afterCreatedAt.toString() : java.time.Instant.EPOCH.toString())
                    .queryParam("afterEventId", afterEventId != null ? afterEventId : "")
                    .queryParam("limit", limit)
                    .build(true).toUriString();
            ResponseEntity<String> response = qAuthzRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                        "인가 이벤트 피드 응답 이상: " + response.getStatusCode());
            }
            return eventFeedMapper.readValue(response.getBody(),
                    eventFeedMapper.getTypeFactory().constructCollectionType(List.class, AuthzEventRecord.class));
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId,
                    "인가 이벤트 피드 조회 실패: " + e.getMessage());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper eventFeedMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 1.1 SCIM: 사용자의 유효 할당 Service 코드 목록 — {@code GET /users/{id}/assignments}. authz 비활성이면 빈 목록, 장애는 예외. */
    @SuppressWarnings("unchecked")
    public List<String> listUserAssignedAgencies(String qimUserId, String correlationId) {
        if (!enabled || qimUserId == null || qimUserId.isBlank()) return List.of();
        String url = qAuthzBaseUrl + "/api/v1/internal/authz/users/" + qimUserId + "/assignments";
        ResponseEntity<List> res = qAuthzRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), List.class);
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId, "authz 비정상 응답: " + res.getStatusCode());
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object o : res.getBody()) {
            if (o instanceof Map<?, ?> m && m.get("agencyCode") instanceof String a) out.add(a);
        }
        return out;
    }

    /** 1.1 SCIM 전체 동기화: Service 의 ACTIVE 할당 사용자 id 페이지 — {@code GET /agencies/{code}/assignments?page&size}. */
    @SuppressWarnings("unchecked")
    public AssignmentPage listAgencyAssignments(String agencyCode, int page, int size, String correlationId) {
        if (!enabled) return new AssignmentPage(List.of(), false);
        String url = qAuthzBaseUrl + "/api/v1/internal/authz/agencies/" + agencyCode + "/assignments?page=" + page + "&size=" + size;
        ResponseEntity<List> res = qAuthzRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(buildHeaders(correlationId)), List.class);
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId, "authz 비정상 응답: " + res.getStatusCode());
        }
        List<String> users = new java.util.ArrayList<>();
        for (Object o : res.getBody()) {
            if (o instanceof Map<?, ?> m && m.get("qimUserId") instanceof String u) users.add(u);
        }
        boolean hasNext = "true".equalsIgnoreCase(res.getHeaders().getFirst("X-Has-Next"));
        return new AssignmentPage(users, hasNext);
    }

    public record AssignmentPage(List<String> qimUserIds, boolean hasNext) {}

    private HttpHeaders buildHeaders(String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId != null ? correlationId : "");
        if (qAuthzInternalApiKey != null && !qAuthzInternalApiKey.isBlank()) {
            headers.set("X-Internal-Api-Key", qAuthzInternalApiKey);
        }
        return headers;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1.1.1 G1-3 — 관리 콘솔 할당 관리 (hub 관리 API → authz 내부 API). authz 4xx 는 상태 그대로(E-IDO-127/128/129), 장애는 E-IDO-117
    // ═══════════════════════════════════════════════════════════════════════

    public record AssignmentRecord(String qimUserId, String agencyCode, String status, String source, String grantedAt, String grantedBy, String expiresAt) {}
    public record AssignmentListPage(List<AssignmentRecord> items, long total, boolean hasNext) {}
    public record RoleRecord(String agencyCode, String roleCode, String name, String description, boolean assignable, String createdAt) {}
    public record UserRoleRecord(String id, String qimUserId, String agencyCode, String roleCode, String status, String grantedAt, String grantedBy, String expiresAt, String source) {}

    /** Service 의 할당 목록(상태 ACTIVE 순, 페이지) — authz {@code GET /agencies/{code}/assignments} */
    public AssignmentListPage listAgencyAssignmentRecords(String agencyCode, int page, int size, String correlationId) {
        ResponseEntity<List<Map<String, Object>>> res = adminExchange(HttpMethod.GET,
                "/api/v1/internal/authz/agencies/" + agencyCode + "/assignments?page=" + page + "&size=" + size, null, correlationId, null);
        List<AssignmentRecord> items = new java.util.ArrayList<>();
        for (Map<String, Object> m : res.getBody() == null ? List.<Map<String, Object>>of() : res.getBody()) items.add(toAssignment(m));
        long total = items.size();
        try { total = Long.parseLong(String.valueOf(res.getHeaders().getFirst("X-Total-Count"))); } catch (NumberFormatException ignore) { /* 헤더 없음 */ }
        return new AssignmentListPage(items, total, "true".equalsIgnoreCase(res.getHeaders().getFirst("X-Has-Next")));
    }

    /** 직접 할당(source=CONSOLE) — authz {@code POST /assignments} */
    public AssignmentRecord assign(String agencyCode, String qimUserId, String grantedBy, java.time.Instant expiresAt, String reason, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qimUserId", qimUserId); body.put("agencyCode", agencyCode); body.put("grantedBy", grantedBy);
        if (expiresAt != null) body.put("expiresAt", expiresAt.toString());
        body.put("source", "CONSOLE"); if (reason != null) body.put("reason", reason);
        return toAssignment(adminExchangeMap(HttpMethod.POST, "/api/v1/internal/authz/assignments", body, correlationId, null));
    }

    /** 할당 해제 — authz {@code DELETE /assignments} */
    public void unassign(String agencyCode, String qimUserId, String revokedBy, String reason, String correlationId) {
        adminExchange(HttpMethod.DELETE, UriComponentsBuilder.fromPath("/api/v1/internal/authz/assignments")
                .queryParam("qimUserId", qimUserId).queryParam("agencyCode", agencyCode).queryParam("revokedBy", revokedBy)
                .queryParamIfPresent("reason", java.util.Optional.ofNullable(reason)).build().encode().toUriString(), null, correlationId, null);
    }

    /** 역할 카탈로그 — authz {@code GET /roles?agencyCode} */
    public List<RoleRecord> listRoles(String agencyCode, String correlationId) {
        ResponseEntity<List<Map<String, Object>>> res = adminExchange(HttpMethod.GET, "/api/v1/internal/authz/roles?agencyCode=" + agencyCode, null, correlationId, null);
        List<RoleRecord> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : res.getBody() == null ? List.<Map<String, Object>>of() : res.getBody()) out.add(toRole(m));
        return out;
    }

    /** 역할 만들기 — authz {@code POST /roles} (X-Actor = 관리자) */
    public RoleRecord createRole(String agencyCode, String roleCode, String name, String description, String actor, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agencyCode", agencyCode); body.put("roleCode", roleCode); body.put("name", name); if (description != null) body.put("description", description);
        return toRole(adminExchangeMap(HttpMethod.POST, "/api/v1/internal/authz/roles", body, correlationId, actor));
    }

    /** 사용자의 역할 부여 내역 — authz {@code GET /users/{id}/roles?agencyCode} */
    public List<UserRoleRecord> listUserRoles(String qimUserId, String agencyCode, String correlationId) {
        ResponseEntity<List<Map<String, Object>>> res = adminExchange(HttpMethod.GET,
                "/api/v1/internal/authz/users/" + qimUserId + "/roles?agencyCode=" + agencyCode, null, correlationId, null);
        List<UserRoleRecord> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : res.getBody() == null ? List.<Map<String, Object>>of() : res.getBody()) out.add(toUserRole(m));
        return out;
    }

    /** 역할 부여(source=CONSOLE) — authz {@code POST /grants} */
    public UserRoleRecord grantRole(String agencyCode, String qimUserId, String roleCode, String grantedBy, java.time.Instant expiresAt, String reason, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("qimUserId", qimUserId); body.put("agencyCode", agencyCode); body.put("roleCode", roleCode); body.put("grantedBy", grantedBy);
        if (expiresAt != null) body.put("expiresAt", expiresAt.toString());
        body.put("source", "CONSOLE"); if (reason != null) body.put("reason", reason);
        return toUserRole(adminExchangeMap(HttpMethod.POST, "/api/v1/internal/authz/grants", body, correlationId, null));
    }

    /** 역할 회수 — authz {@code DELETE /grants} */
    public void revokeRole(String agencyCode, String qimUserId, String roleCode, String revokedBy, String reason, String correlationId) {
        adminExchange(HttpMethod.DELETE, UriComponentsBuilder.fromPath("/api/v1/internal/authz/grants")
                .queryParam("qimUserId", qimUserId).queryParam("agencyCode", agencyCode).queryParam("roleCode", roleCode).queryParam("revokedBy", revokedBy)
                .queryParamIfPresent("reason", java.util.Optional.ofNullable(reason)).build().encode().toUriString(), null, correlationId, null);
    }

    // ── 내부 ──
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static AssignmentRecord toAssignment(Map<String, Object> m) {
        return new AssignmentRecord(str(m.get("qimUserId")), str(m.get("agencyCode")), str(m.get("status")), str(m.get("source")),
                str(m.get("grantedAt")), str(m.get("grantedBy")), str(m.get("expiresAt")));
    }
    private static RoleRecord toRole(Map<String, Object> m) {
        return new RoleRecord(str(m.get("agencyCode")), str(m.get("roleCode")), str(m.get("name")), str(m.get("description")),
                Boolean.TRUE.equals(m.get("assignable")), str(m.get("createdAt")));
    }
    private static UserRoleRecord toUserRole(Map<String, Object> m) {
        return new UserRoleRecord(str(m.get("id")), str(m.get("qimUserId")), str(m.get("agencyCode")), str(m.get("roleCode")), str(m.get("status")),
                str(m.get("grantedAt")), str(m.get("grantedBy")), str(m.get("expiresAt")), str(m.get("source")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> adminExchangeMap(HttpMethod method, String path, Object body, String correlationId, String actor) {
        ResponseEntity<Map> res = adminExchangeRaw(method, path, body, correlationId, actor, Map.class);
        return res.getBody() == null ? Map.of() : (Map<String, Object>) res.getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<List<Map<String, Object>>> adminExchange(HttpMethod method, String path, Object body, String correlationId, String actor) {
        ResponseEntity<List> res = adminExchangeRaw(method, path, body, correlationId, actor, List.class);
        return ResponseEntity.status(res.getStatusCode()).headers(res.getHeaders()).body((List<Map<String, Object>>) res.getBody());
    }

    private <T> ResponseEntity<T> adminExchangeRaw(HttpMethod method, String path, Object body, String correlationId, String actor, Class<T> type) {
        if (!enabled) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId, "idem.hub.authz.enabled=false — 할당 관리를 쓸 수 없습니다");
        }
        HttpHeaders headers = buildHeaders(correlationId);
        if (actor != null) headers.set("X-Actor", actor);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<T> res = qAuthzRestTemplate.exchange(qAuthzBaseUrl + path, method, new HttpEntity<>(body, headers), type);
            if (!res.getStatusCode().is2xxSuccessful()) {
                throw rejected(res.getStatusCode().value(), String.valueOf(res.getBody()), correlationId);
            }
            return res;
        } catch (HttpStatusCodeException e) {
            throw rejected(e.getStatusCode().value(), e.getResponseBodyAsString(), correlationId);
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            log.error("[QAuthzClient] 관리 호출 실패: {} {} err={}", method, path, e.getMessage());
            throw new PlatformException(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE, correlationId, "authz 호출 실패: " + e.getMessage());
        }
    }

    /** authz 응답 상태 → 플랫폼 오류 (404/409/그 밖 4xx 는 거부로, 5xx 는 장애로). detail 에 authz 본문({error, message}) */
    private static PlatformException rejected(int status, String body, String correlationId) {
        String detail = body == null ? "" : (body.length() > 300 ? body.substring(0, 300) : body);
        PlatformErrorCode code = status == 404 ? PlatformErrorCode.IDO_AUTHZ_NOT_FOUND
                : status == 409 ? PlatformErrorCode.IDO_AUTHZ_CONFLICT
                : status >= 400 && status < 500 ? PlatformErrorCode.IDO_AUTHZ_REJECTED
                : PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE;
        return new PlatformException(code, correlationId, "authz " + status + " " + detail);
    }
}
