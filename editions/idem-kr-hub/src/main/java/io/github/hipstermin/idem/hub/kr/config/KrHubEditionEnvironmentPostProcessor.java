package io.github.hipstermin.idem.hub.kr.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * KR 에디션 기본 설정 — 코어 hub 의 application.yml 에서 뺀 SMES 전용 키를 최하위 우선순위로 공급한다.
 * 설치자의 환경변수·application.yml 이 항상 이긴다. (spring.factories 로 등록, S8-a)
 */
public class KrHubEditionEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "idemKrHubEditionDefaults";

    static final Map<String, Object> DEFAULTS = Map.ofEntries(
            // D3: KR 벤더 플러그인은 이 에디션의 기본이 켜짐 — 환경변수로 끌 수 있다 (코어 yml 기본값은 false)
            Map.entry("idem.plugins.nice-oacx.enabled", "${IDEM_PLUGINS_NICE_OACX_ENABLED:true}"),
            Map.entry("idem.plugins.anyid.enabled", "${IDEM_PLUGINS_ANYID_ENABLED:true}"),
            // D3: 비OIDC 직접 브로커 사업자(코어는 빈 목록) — PoC 자리표시 URL, 운영은 환경변수로 덮는다
            Map.entry("idem.hub.broker.nonoidc.providers.PASS.initiate-url", "${KR_NONOIDC_PASS_URL:https://pass.example.com/auth?callback={callbackUrl}&cid={correlationId}}"),
            Map.entry("idem.hub.broker.nonoidc.providers.PASS.auth-level", "L2"),
            Map.entry("idem.hub.broker.nonoidc.providers.FINANCIAL_CERT.initiate-url", "${KR_NONOIDC_FINANCIAL_CERT_URL:https://financial-cert.example.com/auth?cid={correlationId}}"),
            Map.entry("idem.hub.broker.nonoidc.providers.FINANCIAL_CERT.auth-level", "L3"),
            Map.entry("idem.hub.broker.nonoidc.providers.FINANCIAL_CERT.tx-prefix", "FCERT"),
            Map.entry("idem.hub.broker.nonoidc.providers.GPKI.initiate-url", "${KR_NONOIDC_GPKI_URL:https://gpki.example.org/auth?cid={correlationId}}"),
            Map.entry("idem.hub.broker.nonoidc.providers.GPKI.auth-level", "L3"),
            Map.entry("idem.hub.broker.nonoidc.providers.JOINT_CERT.initiate-url", "${KR_NONOIDC_JOINT_CERT_URL:https://joint-cert.example.com/auth?cid={correlationId}}"),
            Map.entry("idem.hub.broker.nonoidc.providers.JOINT_CERT.auth-level", "L3"),
            Map.entry("idem.hub.broker.nonoidc.providers.JOINT_CERT.tx-prefix", "JCERT"),
            // 회원 전환 세션 (ConversionInitService)
            Map.entry("idem.hub.conversion.session-ttl-minutes", "30"),
            Map.entry("idem.hub.conversion.signed-request-max-age-minutes", "5"),
            // FE AES-GCM 복호화 키 (CI 토큰 교환) — SMES FE webpack DefinePlugin 값과 동일해야 한다
            Map.entry("idem.hub.fe-aes-gcm-key", "${FE_AES_GCM_KEY:}"),
            // 통합인증(기업인증) 서버
            Map.entry("idem.hub.auth.integration.base-url", "${INTEGRATION_AUTH_BASE_URL:}"),
            Map.entry("idem.hub.auth.integration.timeout-seconds", "${INTEGRATION_AUTH_TIMEOUT_SECONDS:10}"),
            // 회원구분코드 (MemberDivisionPolicy)
            Map.entry("idem.hub.registry.member-division-codes", "A101,A102"),
            Map.entry("idem.hub.registry.corporate-division-codes", "A102"),
            // resilience4j — 통합인증 서버 클라이언트 (IntegrationAuthClient). retry 인스턴스는 정의하지 않는다:
            // base(configs.default) 위에 인스턴스를 얹으면 resilience4j 2.2 가 "intervalFunction was configured twice" 로
            // 기동을 거부한다(코어 yml 의 다른 인스턴스는 base 와 같은 파일에서 한 번에 바인딩되어 피해 간다). 기본 retry 설정을 쓴다.
            Map.entry("resilience4j.circuitbreaker.instances.integration-auth-client.sliding-window-size", "10"),
            Map.entry("resilience4j.circuitbreaker.instances.integration-auth-client.failure-rate-threshold", "50"),
            Map.entry("resilience4j.circuitbreaker.instances.integration-auth-client.slow-call-duration-threshold", "8s"),
            Map.entry("resilience4j.circuitbreaker.instances.integration-auth-client.wait-duration-in-open-state", "60s"),
            Map.entry("resilience4j.circuitbreaker.instances.integration-auth-client.permitted-number-of-calls-in-half-open-state", "3"),
            Map.entry("resilience4j.circuitbreaker.instances.integration-auth-client.minimum-number-of-calls", "5"),
            Map.entry("resilience4j.timelimiter.instances.integration-auth-client.timeout-duration", "12s")
    );

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        // D3: 코어 application.yml 의 기본값(예: 플러그인 false)보다 앞, 환경변수·명령행보다는 뒤 — KafkaOptionalEnvironmentPostProcessor 와 같은 자리
        MapPropertySource defaults = new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS);
        if (sources.contains(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, defaults);
        } else {
            sources.addLast(defaults);
        }
    }
}
