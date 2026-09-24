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
            // 회원 전환 세션 (ConversionInitService)
            Map.entry("ido.conversion.session-ttl-minutes", "30"),
            Map.entry("ido.conversion.signed-request-max-age-minutes", "5"),
            // FE AES-GCM 복호화 키 (CI 토큰 교환) — SMES FE webpack DefinePlugin 값과 동일해야 한다
            Map.entry("ido.fe-aes-gcm-key", "${FE_AES_GCM_KEY:}"),
            // 통합인증(기업인증) 서버
            Map.entry("ido.auth.integration.base-url", "${INTEGRATION_AUTH_BASE_URL:}"),
            Map.entry("ido.auth.integration.timeout-seconds", "${INTEGRATION_AUTH_TIMEOUT_SECONDS:10}"),
            // 회원구분코드 (MemberDivisionPolicy)
            Map.entry("ido.qim.member-division-codes", "A101,A102"),
            Map.entry("ido.qim.corporate-division-codes", "A102"),
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
        sources.addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
    }
}
