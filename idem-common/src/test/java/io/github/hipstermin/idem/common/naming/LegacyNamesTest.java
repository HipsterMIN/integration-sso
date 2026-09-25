package io.github.hipstermin.idem.common.naming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("개명 4b — 구 이름 대응표와 호환 계층")
class LegacyNamesTest {

    @Test
    @DisplayName("설정 키: 접두와 모듈 지칭이 함께 바뀐다")
    void mapKey() {
        assertThat(LegacyNames.mapKey("ido.ticket.aes-key")).isEqualTo("idem.hub.ticket.aes-key");
        assertThat(LegacyNames.mapKey("ido.qim.base-url")).isEqualTo("idem.hub.registry.base-url");
        assertThat(LegacyNames.mapKey("ido.q-authz.internal-api-key")).isEqualTo("idem.hub.authz.internal-api-key");
        assertThat(LegacyNames.mapKey("ido.qsign.base-url")).isEqualTo("idem.hub.gate.base-url");
        assertThat(LegacyNames.mapKey("ido.qim-events.poll.enabled")).isEqualTo("idem.hub.registry-events.poll.enabled");
        assertThat(LegacyNames.mapKey("ido.qim-outbox.relay.enabled")).isEqualTo("idem.hub.registry-outbox.relay.enabled");
        assertThat(LegacyNames.mapKey("ido.kafka.topic-qim-user-events")).isEqualTo("idem.hub.kafka.topic-registry-user-events");
        assertThat(LegacyNames.mapKey("ido.internal.callers.q-sign")).isEqualTo("idem.hub.internal.callers.idem-gate");
        assertThat(LegacyNames.mapKey("ido.internal.callers.outbox-relay")).isEqualTo("idem.hub.internal.callers.idem-relay");
        assertThat(LegacyNames.mapKey("ido.admin.mfa.required")).isEqualTo("idem.hub.admin.mfa.required");
        assertThat(LegacyNames.mapKey("qsign.ido.base-url")).isEqualTo("idem.gate.hub.base-url");
        assertThat(LegacyNames.mapKey("qsign.oidc-front.issuer")).isEqualTo("idem.gate.oidc-front.issuer");
        assertThat(LegacyNames.mapKey("qim.crypto.ci.current-version")).isEqualTo("idem.registry.crypto.ci.current-version");
        assertThat(LegacyNames.mapKey("authz.expiry.batch-size")).isEqualTo("idem.authz.expiry.batch-size");
        assertThat(LegacyNames.mapKey("batch.datasource.qim.url")).isEqualTo("idem.relay.datasource.registry.url");
        assertThat(LegacyNames.mapKey("batch.datasource.ido.url")).isEqualTo("idem.relay.datasource.hub.url");
        assertThat(LegacyNames.mapKey("batch.relay.qsign.enabled")).isEqualTo("idem.relay.jobs.gate.enabled");
        assertThat(LegacyNames.mapKey("batch.relay.webhook.enabled")).isEqualTo("idem.relay.jobs.webhook.enabled");
        assertThat(LegacyNames.mapKey("batch.http.mtls.enabled")).isEqualTo("idem.relay.http.mtls.enabled");
        assertThat(LegacyNames.mapKey("agency-stub.protocol")).isEqualTo("idem.sample.protocol");
        // 구 키가 아니면 그대로
        assertThat(LegacyNames.mapKey("idem.plugins.mock-auth.enabled")).isEqualTo("idem.plugins.mock-auth.enabled");
        assertThat(LegacyNames.mapKey("spring.batch.job.enabled")).isEqualTo("spring.batch.job.enabled");
        assertThat(LegacyNames.isLegacyKey("idem.hub.x")).isFalse();
    }

    @Test
    @DisplayName("환경변수: 접두와 모듈 지칭이 함께 바뀐다")
    void mapEnv() {
        assertThat(LegacyNames.mapEnv("IDO_HANDOFF_AES_KEY")).isEqualTo("IDEM_HUB_HANDOFF_AES_KEY");
        assertThat(LegacyNames.mapEnv("IDO_QIM_INTERNAL_API_KEY")).isEqualTo("IDEM_HUB_REGISTRY_INTERNAL_API_KEY");
        assertThat(LegacyNames.mapEnv("IDO_QAUTHZ_ENABLED")).isEqualTo("IDEM_HUB_AUTHZ_ENABLED");
        assertThat(LegacyNames.mapEnv("IDO_INTERNAL_API_KEY_QSIGN")).isEqualTo("IDEM_HUB_INTERNAL_API_KEY_GATE");
        assertThat(LegacyNames.mapEnv("IDO_INTERNAL_API_KEY_OUTBOX")).isEqualTo("IDEM_HUB_INTERNAL_API_KEY_RELAY");
        assertThat(LegacyNames.mapEnv("QAUTHZ_BASE_URL")).isEqualTo("IDEM_HUB_AUTHZ_BASE_URL");
        assertThat(LegacyNames.mapEnv("QSIGN_KEYCLOAK_CLIENT_SECRET")).isEqualTo("IDEM_GATE_KEYCLOAK_CLIENT_SECRET");
        assertThat(LegacyNames.mapEnv("QIM_DI_SECRET")).isEqualTo("IDEM_REGISTRY_DI_SECRET");
        assertThat(LegacyNames.mapEnv("AUTHZ_INTERNAL_API_KEY")).isEqualTo("IDEM_AUTHZ_INTERNAL_API_KEY");
        assertThat(LegacyNames.mapEnv("BATCH_IDO_QIM_BATCH_SIZE")).isEqualTo("IDEM_RELAY_HUB_REGISTRY_BATCH_SIZE");
        assertThat(LegacyNames.mapEnv("BATCH_QSIGN_KAFKA_MAX_RETRY")).isEqualTo("IDEM_RELAY_GATE_KAFKA_MAX_RETRY");
        assertThat(LegacyNames.mapEnv("BATCH_WEBHOOK_RELAY_ENABLED")).isEqualTo("IDEM_RELAY_WEBHOOK_RELAY_ENABLED");
        assertThat(LegacyNames.mapEnv("IDEM_ADMIN_BOOTSTRAP_PASSWORD")).isEqualTo("IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD");
        assertThat(LegacyNames.mapEnv("IDEM_HUB_HANDOFF_AES_KEY")).isEqualTo("IDEM_HUB_HANDOFF_AES_KEY");
        assertThat(LegacyNames.mapEnv("KEYCLOAK_CLIENT_SECRET")).isEqualTo("KEYCLOAK_CLIENT_SECRET");
        assertThat(LegacyNames.isLegacyEnv("DB_HOST")).isFalse();
    }

    @Test
    @DisplayName("호환 계층: 구 환경변수·구 키가 새 이름으로 보이고, 새 이름이 있으면 새 이름이 이긴다")
    void postProcessor() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("IDO_HANDOFF_AES_KEY", "legacy-aes", "QIM_DI_SECRET", "legacy-di", "IDEM_REGISTRY_DI_SECRET", "new-di", "DB_HOST", "db")));
        env.getPropertySources().addLast(new MapPropertySource("external-yml",
                Map.of("ido.ticket.ttl-seconds", "77", "qsign.ido.base-url", "http://legacy", "idem.gate.hub.base-url", "http://new")));

        new LegacyNamesEnvironmentPostProcessor(new DeferredLogs()).postProcessEnvironment(env, null);

        assertThat(env.getProperty("IDEM_HUB_HANDOFF_AES_KEY")).isEqualTo("legacy-aes");
        assertThat(env.getProperty("IDEM_REGISTRY_DI_SECRET")).as("새 이름이 실제 환경에 있으면 그대로").isEqualTo("new-di");
        assertThat(env.getProperty("idem.hub.ticket.ttl-seconds")).isEqualTo("77");
        assertThat(env.getProperty("idem.gate.hub.base-url")).as("새 키가 있으면 구 키 무시").isEqualTo("http://new");
        assertThat(env.resolvePlaceholders("${IDEM_HUB_HANDOFF_AES_KEY:none}")).isEqualTo("legacy-aes");
        // yml 자리표시자 없이 프로퍼티 이름으로 직접 읽는 곳(느슨한 대응): idem.hub.handoff.aes-key ← IDEM_HUB_HANDOFF_AES_KEY ← IDO_HANDOFF_AES_KEY
        assertThat(env.getProperty("idem.hub.handoff.aes-key")).as("실기동에서 CastKeyConfig 가 이렇게 읽는다").isEqualTo("legacy-aes");
        // 두 번 실행해도 중복 소스를 만들지 않는다
        new LegacyNamesEnvironmentPostProcessor(new DeferredLogs()).postProcessEnvironment(env, null);
        assertThat(env.getPropertySources().stream().filter(p -> p.getName().equals(LegacyNamesEnvironmentPostProcessor.ENV_SOURCE)).count()).isEqualTo(1);
    }
}
