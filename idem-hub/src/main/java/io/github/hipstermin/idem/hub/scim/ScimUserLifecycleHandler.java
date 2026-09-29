package io.github.hipstermin.idem.hub.scim;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 1.1 registry 사용자 정지·탈퇴 → 할당된 SCIM 기관에 비활성/삭제 적재.
 * 기관 목록은 authz(유효 할당)에서, 기관향 식별자는 프로파일 스킴으로 푼다. 어느 단계든 실패하면 로그만 남긴다 —
 * registry 이벤트 처리(캐시 무효화·세션 종료)를 SCIM 때문에 되돌리지 않는다. 남은 불일치는 관리자의 전체 동기화로 메운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScimUserLifecycleHandler {

    private final QAuthzClient qAuthzClient;
    private final ServiceProfileService serviceProfileService;
    private final PolicyEngine policyEngine;
    private final ScimOutboxService outboxService;

    /** @return 적재한 op 수 (-1 = 조회 실패) */
    public int onTerminal(String qimUserId, UserEvent event, String correlationId) {
        boolean withdrawn = UserEvent.TYPE_WITHDRAWN.equals(event.getEventType())
                || UserStatus.WITHDRAWN.name().equals(event.getUserStatus());
        List<String> agencies;
        try {
            agencies = qAuthzClient.listUserAssignedAgencies(qimUserId, correlationId);
        } catch (Exception e) {
            log.warn("[ScimLifecycle] 할당 기관 조회 실패 — SCIM 전파 생략(전체 동기화로 보정): user={} err={}", qimUserId, e.getMessage());
            return -1;
        }
        int n = 0;
        for (String agency : agencies) {
            Optional<ServiceProfile> profile = serviceProfileService.find(agency);
            ServiceProfile.Scim scim = profile.map(ScimOutboxService::scimOf).orElse(null);
            if (scim == null || !scim.enabledOrFalse()) continue;
            try {
                String subject = policyEngine.resolveAgencySubjectId(profile.get(), qimUserId, agency, correlationId);
                n += outboxService.onUserTerminal(profile.get(), subject, withdrawn, event.getEventId(), event.getEventType(), correlationId);
            } catch (Exception e) {
                log.warn("[ScimLifecycle] 기관 {} 전파 실패(전체 동기화로 보정): user={} err={}", agency, qimUserId, e.getMessage());
            }
        }
        if (n > 0) log.info("[ScimLifecycle] 사용자 {} → SCIM {}건 적재 (withdrawn={})", qimUserId, n, withdrawn);
        return n;
    }
}
