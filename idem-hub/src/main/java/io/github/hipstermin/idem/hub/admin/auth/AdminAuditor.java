package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 관리 행위 감사 — 분류 ADMIN, 주체 유형 ADMIN (execution-plan P1 §3.2 "모든 관리 행위 감사"). */
@Component
@RequiredArgsConstructor
public class AdminAuditor {

    public static final String CATEGORY_ADMIN = "ADMIN";
    public static final String ACTOR_ADMIN = "ADMIN";

    private final AuditLogPublisher publisher;

    public void record(String action, String actorUsername, String resourceType, String resourceId,
                       String ip, String outcome, String detail, Map<String, Object> metadata) {
        publisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(CATEGORY_ADMIN)
                .eventAction(action)
                .actorType(ACTOR_ADMIN)
                .actorId(actorUsername)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .sourceIp(ip)
                .outcome(outcome)
                .outcomeDetail(detail)
                .metadata(metadata)
                .build());
    }

    public void success(String action, String actor, String resourceType, String resourceId, String ip) {
        record(action, actor, resourceType, resourceId, ip, AuditLogEvent.OUTCOME_SUCCESS, null, null);
    }

    public void failure(String action, String actor, String resourceType, String resourceId, String ip, String detail) {
        record(action, actor, resourceType, resourceId, ip, AuditLogEvent.OUTCOME_FAILURE, detail, null);
    }
}
