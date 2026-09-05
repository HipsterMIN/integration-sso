package kr.go.smes.plugin.mockauth;

import kr.go.smes.common.spi.identity.IdentityVerificationProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Mock 본인인증 플러그인 자동 구성.
 *
 * <p>활성화 조건: {@code idem.plugins.mock-auth.enabled=true} (환경변수 {@code IDEM_PLUGINS_MOCKAUTH_ENABLED=true}).
 * 기본값은 비활성이며, 코어 CI(k6 스모크)와 로컬 개발에서만 켠다.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "idem.plugins.mock-auth", name = "enabled", havingValue = "true")
public class MockAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "mockIdentityVerificationProvider")
    public IdentityVerificationProvider mockIdentityVerificationProvider() {
        return new MockIdentityVerificationProvider();
    }
}
