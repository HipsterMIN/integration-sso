package io.github.hipstermin.idem.common.crypto;

import io.github.hipstermin.idem.common.crypto.jca.JcaCryptoProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * {@link CryptoProvider} 빈 — 모듈이 다른 구현(KCMVP 어댑터)을 빈으로 두면 그것을 쓰고, 없으면 JCA.
 * 어느 쪽이든 {@link CryptoProviders#install(CryptoProvider)} 로 정적 접근점에도 반영한다.
 */
@Slf4j
@AutoConfiguration
public class CryptoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CryptoProvider.class)
    public CryptoProvider cryptoProvider() {
        return new JcaCryptoProvider();
    }

    @Bean
    public CryptoProviderInstaller cryptoProviderInstaller(CryptoProvider provider) {
        return new CryptoProviderInstaller(provider);
    }

    /** 빈 초기화 시점에 정적 접근점을 갱신한다. */
    public static final class CryptoProviderInstaller {
        CryptoProviderInstaller(CryptoProvider provider) {
            CryptoProviders.install(provider);
            log.info("[Idem] CryptoProvider = {}", provider.providerName());
        }
    }
}
