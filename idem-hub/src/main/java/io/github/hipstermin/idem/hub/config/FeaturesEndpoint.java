package io.github.hipstermin.idem.hub.config;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * /actuator/features — 현재 기능 플래그 상태 조회 엔드포인트
 *
 * <p>운영 배포 후 다음 명령으로 즉시 기능 활성 여부 확인:
 * <pre>
 * curl http://localhost:8083/actuator/features | jq .
 * </pre>
 *
 * <p>응답 예시 (로컬 환경):
 * <pre>{@code
 * {
 *   "features": {
 *     "F-01_authRateLimit":   { "enabled": false, "env": "IDEM_HUB_AUTH_RL_ENABLED" },
 *     "F-02_agencyRateLimit": { "enabled": false, "env": "IDEM_HUB_RATE_LIMIT_ENABLED" },
 *     "F-03_auditKafka":      { "enabled": false, "env": "IDEM_HUB_AUDIT_KAFKA_ENABLED" },
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <p>{@code management.endpoints.web.exposure.include}에 {@code features} 추가 필요.
 */
@Component
@Endpoint(id = "features")
@RequiredArgsConstructor
public class FeaturesEndpoint {

    private final FeatureFlags featureFlags;

    @ReadOperation
    public Map<String, Object> features() {
        return Map.of("features", featureFlags.toStatusMap());
    }
}
