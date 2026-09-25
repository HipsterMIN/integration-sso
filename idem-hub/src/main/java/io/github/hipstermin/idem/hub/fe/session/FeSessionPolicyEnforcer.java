package io.github.hipstermin.idem.hub.fe.session;

import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.hub.policy.PolicyEngineImpl;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D3: 프로파일 {@code policy.session} 을 Idem FE 세션에 실제로 적용한다.
 *
 * <p>종전에는 세션 정책이 프로파일에 매핑만 되고 어디에도 집행되지 않았다. Handoff 발급(= 사용자가 그 Service 로 넘어가는 순간)에
 * 그 Service 의 상한으로 FE 세션을 조인다 — 유휴·절대 만료는 더 짧은 쪽으로만 바뀌고(늘리지 않는다), 동시 세션 수를 넘는
 * 오래된 세션은 만료한다. 페이로드에도 같은 값이 실려 기관이 자기 세션에 적용한다.
 *
 * <p>실패는 발급을 되돌리지 않는다(세션은 이미 서버가 쥐고 있고, 정책 적용 실패는 로그로 남긴다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeSessionPolicyEnforcer {

    private final ServiceProfileService serviceProfileService;
    private final FeSessionService feSessionService;

    /** @return 적용된 정책 (프로파일에 없으면 null) */
    public HandoffPayload.SessionPolicy applyForService(String feSessionId, String serviceCode, String correlationId) {
        if (feSessionId == null || feSessionId.isBlank() || serviceCode == null) return null;
        ServiceProfile profile = serviceProfileService.find(serviceCode).orElse(null);
        HandoffPayload.SessionPolicy policy = PolicyEngineImpl.sessionPolicyOf(profile);
        if (policy == null) return null;
        try {
            feSessionService.applySessionPolicy(feSessionId, policy.idleMinutes(), policy.absoluteMinutes(), policy.concurrent());
            log.info("[FeSessionPolicy] 세션 정책 적용: service={} idle={} absolute={} concurrent={} cid={}",
                    serviceCode, policy.idleMinutes(), policy.absoluteMinutes(), policy.concurrent(), correlationId);
        } catch (Exception e) {
            log.error("[FeSessionPolicy] 세션 정책 적용 실패(발급은 유지): service={} feSessionId={} cid={} err={}",
                    serviceCode, feSessionId, correlationId, e.getMessage());
        }
        return policy;
    }
}
