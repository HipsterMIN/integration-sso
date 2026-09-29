package io.github.hipstermin.idem.hub.scim;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 1.1 SCIM 2.0 아웃바운드 — 도메인 변경을 {@code idem_hub.scim_outbox} 행으로 바꾼다 (실제 HTTP 는 {@link ScimOutboxRelay}).
 *
 * <p>연산(op) 다섯 개뿐이다: {@code ENSURE_USER}(있으면 active=true 갱신, 없으면 생성) · {@code DEACTIVATE_USER} · {@code DELETE_USER}
 * · {@code ADD_GROUP_MEMBER} · {@code REMOVE_GROUP_MEMBER}. 기관에는 <b>기관향 식별자(agencySubjectId)</b>만 간다 — SCIM
 * {@code externalId} 와 {@code userName} 이 그것이고, qimUserId·PII 는 싣지 않는다.
 *
 * <p>되돌이(loop) 방지: 기관이 authz 인바운드 SCIM({@code /scim/v2/Groups}) 으로 만든 변경은 actor 가 {@code SCIM} 이라
 * 그 기관으로 다시 밀어내지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScimOutboxService {

    public static final String OP_ENSURE_USER = "ENSURE_USER";
    public static final String OP_DEACTIVATE_USER = "DEACTIVATE_USER";
    public static final String OP_DELETE_USER = "DELETE_USER";
    public static final String OP_ADD_GROUP_MEMBER = "ADD_GROUP_MEMBER";
    public static final String OP_REMOVE_GROUP_MEMBER = "REMOVE_GROUP_MEMBER";
    static final String INBOUND_SCIM_ACTOR = "SCIM";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${idem.hub.scim.max-retry:5}")
    private int defaultMaxRetry;

    /**
     * 할당·역할 변경(authz 이벤트) → SCIM op. 프로파일에 SCIM 이 꺼져 있으면 0.
     *
     * @param change ASSIGNED·UNASSIGNED·ASSIGNMENT_EXPIRED·ROLE_GRANTED·ROLE_REVOKED·ROLE_EXPIRED
     * @return 적재한 op 수
     */
    public int onAssignmentChanged(ServiceProfile profile, String agencySubjectId, String change, String roleCode,
                                   String actor, String sourceEventId, String sourceEventType, String correlationId) {
        ServiceProfile.Scim scim = scimOf(profile);
        if (scim == null || !scim.enabledOrFalse()) return 0;
        if (agencySubjectId == null || agencySubjectId.isBlank()) {
            log.info("[ScimOutbox] 기관향 식별자 없음(GUEST) — 프로비저닝 생략: agency={} change={} eventId={}",
                    profile.service().code(), change, sourceEventId);
            return 0;
        }
        if (INBOUND_SCIM_ACTOR.equalsIgnoreCase(actor)) {
            log.debug("[ScimOutbox] 기관 인바운드 SCIM 이 만든 변경 — 되돌이 방지 생략: agency={} change={} eventId={}",
                    profile.service().code(), change, sourceEventId);
            return 0;
        }
        String agency = profile.service().code();
        int n = 0;
        switch (change) {
            case "ASSIGNED" -> n += insert(agency, OP_ENSURE_USER, agencySubjectId, null, Map.of("active", true), sourceEventId, sourceEventType, correlationId);
            case "UNASSIGNED", "ASSIGNMENT_EXPIRED" -> {
                String policy = scim.onUnassignOrDefault();
                if (ServiceProfile.Scim.DEACTIVATE.equals(policy)) n += insert(agency, OP_DEACTIVATE_USER, agencySubjectId, null, Map.of("active", false), sourceEventId, sourceEventType, correlationId);
                else if (ServiceProfile.Scim.DELETE.equals(policy)) n += insert(agency, OP_DELETE_USER, agencySubjectId, null, null, sourceEventId, sourceEventType, correlationId);
            }
            case "ROLE_GRANTED" -> {
                n += insert(agency, OP_ENSURE_USER, agencySubjectId, null, Map.of("active", true), sourceEventId, sourceEventType, correlationId);
                if (scim.groupsEnabled() && roleCode != null) n += insert(agency, OP_ADD_GROUP_MEMBER, agencySubjectId, roleCode, null, sourceEventId, sourceEventType, correlationId);
            }
            case "ROLE_REVOKED", "ROLE_EXPIRED" -> {
                if (scim.groupsEnabled() && roleCode != null) n += insert(agency, OP_REMOVE_GROUP_MEMBER, agencySubjectId, roleCode, null, sourceEventId, sourceEventType, correlationId);
            }
            default -> log.warn("[ScimOutbox] 알 수 없는 change={} — 생략: eventId={}", change, sourceEventId);
        }
        return n;
    }

    /** registry 사용자 상태(정지·탈퇴) → 할당된 SCIM 기관마다 비활성/삭제. */
    public int onUserTerminal(ServiceProfile profile, String agencySubjectId, boolean withdrawn,
                              String sourceEventId, String sourceEventType, String correlationId) {
        ServiceProfile.Scim scim = scimOf(profile);
        if (scim == null || !scim.enabledOrFalse() || agencySubjectId == null || agencySubjectId.isBlank()) return 0;
        String policy = withdrawn ? scim.onWithdrawOrDefault() : ServiceProfile.Scim.DEACTIVATE;
        String agency = profile.service().code();
        if (ServiceProfile.Scim.DELETE.equals(policy)) {
            return insert(agency, OP_DELETE_USER, agencySubjectId, null, null, sourceEventId, sourceEventType, correlationId);
        }
        if (ServiceProfile.Scim.DEACTIVATE.equals(policy)) {
            return insert(agency, OP_DEACTIVATE_USER, agencySubjectId, null, Map.of("active", false), sourceEventId, sourceEventType, correlationId);
        }
        return 0;
    }

    /** 전체 동기화(재조정): 사용자 하나를 ENSURE_USER + 역할 그룹 멤버십으로 적재. sourceEventId 는 동기화 실행 id + 사용자. */
    public int enqueueFullSyncUser(ServiceProfile profile, String agencySubjectId, List<String> roles,
                                   String syncId, String correlationId) {
        ServiceProfile.Scim scim = scimOf(profile);
        if (scim == null || !scim.enabledOrFalse() || agencySubjectId == null) return 0;
        String agency = profile.service().code();
        String sourceEventId = UuidV7.generate();
        int n = insert(agency, OP_ENSURE_USER, agencySubjectId, null, Map.of("active", true, "syncId", syncId), sourceEventId, "SCIM_FULL_SYNC", correlationId);
        if (scim.groupsEnabled() && roles != null) {
            for (String r : roles) n += insert(agency, OP_ADD_GROUP_MEMBER, agencySubjectId, r, Map.of("syncId", syncId), sourceEventId, "SCIM_FULL_SYNC", correlationId);
        }
        return n;
    }

    static ServiceProfile.Scim scimOf(ServiceProfile profile) {
        return profile != null && profile.protocol() != null ? profile.protocol().scim() : null;
    }

    private int insert(String agency, String op, String subjectId, String roleCode, Map<String, Object> payload,
                       String sourceEventId, String sourceEventType, String cid) {
        try {
            String json = payload == null ? null : objectMapper.writeValueAsString(new LinkedHashMap<>(payload));
            int rows = jdbcTemplate.update("""
                    INSERT INTO idem_hub.scim_outbox (scim_id, agency_code, op, agency_subject_id, role_code, payload,
                        source_event_id, source_event_type, correlation_id, status, retry_count, max_retry, created_at)
                    VALUES (?,?,?,?,?,?::jsonb,?,?,?,'PENDING',0,?,NOW())
                    ON CONFLICT (source_event_id, agency_code, op, role_code) DO NOTHING
                    """, UuidV7.generate(), agency, op, subjectId, roleCode, json, sourceEventId, sourceEventType, cid, defaultMaxRetry);
            if (rows > 0) log.info("[ScimOutbox] 적재: agency={} op={} role={} eventId={}", agency, op, roleCode, sourceEventId);
            return rows;
        } catch (Exception e) {
            log.error("[ScimOutbox] 적재 실패: agency={} op={} eventId={} err={}", agency, op, sourceEventId, e.getMessage());
            throw new IllegalStateException("SCIM 아웃박스 적재 실패", e);
        }
    }
}
