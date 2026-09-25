package io.github.hipstermin.idem.common.messaging;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

/**
 * Kafka 가 꺼졌을 때 파생 기본값을 한 곳에서 넣는다 (D1-b).
 *
 * <p>application.yml 로딩 뒤(최하 우선순위)에 실행되며, 속성 소스는 {@code systemEnvironment} 바로 아래에 둔다 —
 * 즉 각 모듈의 yml 기본값은 덮어쓰되, 명령행·환경변수로 준 값은 여전히 이긴다.
 *
 * <p>넣는 값: 리스너 자동 시작·토픽 검사 해제, Kafka 전용 릴레이(gate·registry·relay·hub qim-outbox) 정지,
 * 감사 Kafka 발행 정지. hub 의 {@code ido.outbox} 릴레이(F-13)는 끄지 않는다 — Kafka 대신 프로세스 내 배달로 동작한다.
 */
public class KafkaOptionalEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "idemKafkaDisabledDefaults";

    static final Map<String, Object> DISABLED_DEFAULTS = Map.ofEntries(
            // Spring Kafka 공통
            Map.entry("spring.kafka.listener.auto-startup", "false"),
            Map.entry("spring.kafka.listener.missing-topics-fatal", "false"),
            Map.entry("spring.kafka.admin.auto-create", "false"),
            Map.entry("spring.kafka.admin.fail-fast", "false"),
            Map.entry("spring.kafka.producer.properties.max.block.ms", "1000"),
            // idem-hub
            Map.entry("ido.qim-outbox.relay-enabled", "false"),   // F-30 (qim.user.events → Kafka, 폐기 예정 SP 경로)
            Map.entry("ido.audit.kafka-publish-enabled", "false"), // F-03 감사는 DB 저장(F-04)만
            Map.entry("ido.qim-events.poll.enabled", "true"),      // D3: registry 이벤트 피드 폴링으로 상태 변경 전파
            // idem-gate
            Map.entry("qsign.outbox.relay-enabled", "false"),
            // idem-registry
            Map.entry("qim.outbox.relay-enabled", "false"),
            // idem-relay (제품 밖 — Kafka 릴레이 잡 전부 정지, webhook HTTP 릴레이는 유지)
            Map.entry("batch.relay.ido.kafka.enabled", "false"),
            Map.entry("batch.relay.ido.qim.enabled", "false"),
            Map.entry("batch.relay.qim.kafka.enabled", "false"),
            Map.entry("batch.relay.qsign.kafka.enabled", "false"),
            Map.entry("batch.relay.authz.kafka.enabled", "false")
    );

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (KafkaOptional.isEnabled(environment)) {
            return;
        }
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        MapPropertySource defaults = new MapPropertySource(PROPERTY_SOURCE_NAME, DISABLED_DEFAULTS);
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, defaults);
        } else {
            sources.addLast(defaults);
        }
    }
}
