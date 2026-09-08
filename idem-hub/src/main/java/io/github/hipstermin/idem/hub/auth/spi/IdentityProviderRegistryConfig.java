package io.github.hipstermin.idem.hub.auth.spi;

import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 부팅 시 발견된 모든 {@link IdentityVerificationProvider} 빈(코어 내장 + 플러그인 AutoConfiguration)을
 * {@link IdentityProviderRegistry} 로 묶는다. 제공자가 하나도 없어도 기동은 되며(빈 레지스트리) 경고만 남긴다 —
 * 본인인증이 필요 없는 배치·테스트 구성이 있기 때문이다.
 */
@Slf4j
@Configuration
public class IdentityProviderRegistryConfig {

    @Bean
    public IdentityProviderRegistry identityProviderRegistry(ObjectProvider<IdentityVerificationProvider> providers) {
        List<IdentityVerificationProvider> list = providers.orderedStream().toList();
        IdentityProviderRegistry registry = new IdentityProviderRegistry(list);
        if (registry.isEmpty()) {
            log.warn("[IdO] 본인인증 제공자(IdentityVerificationProvider)가 없습니다 — /api/v1/auth/providers 는 빈 목록을 돌려줍니다");
        } else {
            log.info("[IdO] 본인인증 제공자 등록: {}", registry.codes());
        }
        return registry;
    }
}
