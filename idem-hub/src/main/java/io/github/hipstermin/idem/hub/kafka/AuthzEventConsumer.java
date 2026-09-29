package io.github.hipstermin.idem.hub.kafka;

import io.github.hipstermin.idem.common.event.AuthorizationEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 1.1: authz 할당·역할 변경 이벤트({@code idem.authz.assignment.events}) 소비 — 기관 웹훅 {@code ASSIGNMENT_CHANGED} 적재 + 감사.
 *
 * <p>Idem 은 발급 시점마다 authz 를 실시간 조회하므로(캐시 없음) 여기서 Idem 자신의 상태를 바꿀 것은 없다. 할 일은 <b>기관에 알리는 것</b>이다 —
 * 기관은 Handoff 로 만든 자기 세션을 끊거나(회수·해제·만료), 가입 유도를 멈추거나(할당) 할 수 있다. OIDC_RP 기관은 다음 userinfo 가 403 이 된다.
 *
 * <p>페이로드에는 {@code qimUserId} 를 싣지 않고 프로파일 스킴의 기관별 식별자를 해석해 싣는다. 해석 실패(registry 장애)는 예외로 올려
 * 폴러가 다음 주기에 재시도한다. 프로파일이 없는 기관(삭제·미등록)은 통보할 곳이 없으므로 SKIPPED 로 기록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthzEventConsumer {

    static final String CONSUMER_GROUP = "idem-hub-authz-consumer";

    private final IdempotentEventStore   idempotentEventStore;
    private final ServiceProfileService  serviceProfileService;
    private final PolicyEngine           policyEngine;
    private final WebhookDispatcherService webhookDispatcherService;
    private final AuditLogPublisher      auditLogPublisher;
    private final io.github.hipstermin.idem.hub.scim.ScimOutboxService scimOutboxService;

    /** @return 처리 결과 코드 (OK / SKIPPED / DUP) — 테스트·로그용 */
    public String handle(AuthorizationEvent event) {
        String eventId = event.getEventId();
        String type    = event.getEventType();
        String agency  = event.getAgencyCode();
        String user    = event.getQimUserId();
        String cid     = event.getCorrelationId() != null ? event.getCorrelationId() : eventId;

        if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
            log.debug("[AuthzEventConsumer] 이미 처리됨 → 스킵: eventId={}", eventId);
            return "DUP";
        }
        if (agency == null || agency.isBlank() || user == null || user.isBlank()) {
            log.warn("[AuthzEventConsumer] agencyCode/qimUserId 없음 → 스킵: eventId={} type={}", eventId, type);
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, type, "SKIPPED");
            return "SKIPPED";
        }

        Optional<ServiceProfile> profile = serviceProfileService.find(agency);
        if (profile.isEmpty()) {
            log.info("[AuthzEventConsumer] 프로파일 없는 기관 → 통보 대상 없음: agency={} eventId={}", agency, eventId);
            idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, type, "SKIPPED");
            return "SKIPPED";
        }

        // 기관별 식별자 — registry 장애면 PlatformException 이 올라가 폴러가 재시도한다 (qimUserId 로 대체하지 않는다)
        String agencySubjectId = policyEngine.resolveAgencySubjectId(profile.get(), user, agency, cid);
        String change = changeOf(type);

        int enqueued = webhookDispatcherService.enqueueForAssignmentChanged(
                agency, agencySubjectId, change, event.getRoleCode(), event.getOccurredAt(), eventId, cid);
        // 1.1 SCIM 아웃바운드 — 프로파일에 켜진 기관만, 기관 인바운드 SCIM(actor=SCIM)이 만든 변경은 되돌이 방지
        int scim = scimOutboxService.onAssignmentChanged(profile.get(), agencySubjectId, change, event.getRoleCode(),
                event.getActor(), eventId, type, cid);

        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory("AUTHZ")
                .eventAction("ASSIGNMENT_CHANGED")
                .actorType("SYSTEM")
                .actorId(event.getActor() != null ? event.getActor() : "idem-authz")
                .resourceType("SERVICE_ASSIGNMENT")
                .resourceId(agency + ":" + (agencySubjectId != null ? agencySubjectId : "-"))
                .agencyCode(agency)
                .correlationId(cid)
                .metadata(Map.of(
                        "change", change,
                        "roleCode", event.getRoleCode() != null ? event.getRoleCode() : "",
                        "accessLoss", event.isAccessLoss(),
                        "webhookTargets", enqueued,
                        "scimOps", scim,
                        "sourceEventId", eventId))
                .build());

        idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP, type, "OK");
        log.info("[AuthzEventConsumer] 처리: type={} agency={} change={} webhook={} eventId={}", type, agency, change, enqueued, eventId);
        return "OK";
    }

    /** 기관 웹훅의 {@code change} 값 — 이벤트 유형의 접두 차이를 감춘다. */
    static String changeOf(String eventType) {
        if (eventType == null) return "UNKNOWN";
        return switch (eventType) {
            case AuthorizationEvent.TYPE_GRANTED            -> "ROLE_GRANTED";
            case AuthorizationEvent.TYPE_REVOKED            -> "ROLE_REVOKED";
            case AuthorizationEvent.TYPE_EXPIRED            -> "ROLE_EXPIRED";
            case AuthorizationEvent.TYPE_ASSIGNED           -> "ASSIGNED";
            case AuthorizationEvent.TYPE_UNASSIGNED         -> "UNASSIGNED";
            case AuthorizationEvent.TYPE_ASSIGNMENT_EXPIRED -> "ASSIGNMENT_EXPIRED";
            default -> eventType;
        };
    }
}
