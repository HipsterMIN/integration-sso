package io.github.hipstermin.idem.plugin.niceoacx;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * NICE/OACX 플러그인 자동 구성 (S5a — 본작업).
 *
 * <p>활성화: {@code idem.plugins.nice-oacx.enabled=true}. 코어가 주는 빈(RedisTemplate<String,String>·RedissonClient·
 * ObjectMapper)을 받아 NICE 제공자({@code NICE_PHONE})를 등록한다. OACX 제공자({@code OACX_EASYSIGN})는 클래스패스에
 * SDK 진입 클래스({@code OACX.OacxUtil})가 있을 때만 등록된다(oacx 패키지는 SDK 부재 시 컴파일 제외).
 * 자격증명 부팅 검증은 prod 프로파일에서만.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({NiceOacxPluginProperties.class, NiceProperties.class, OacxProperties.class})
@ConditionalOnProperty(prefix = "idem.plugins.nice-oacx", name = "enabled", havingValue = "true")
public class NiceOacxAutoConfiguration {

    /**
     * 기본 게이트웨이 구성 — 사용자가 {@link NicePhoneGateway} 빈을 직접 주지 않을 때만 NICE API 클라이언트·저장소·서비스를 만든다.
     * (테스트·다른 구현 대체 시에는 게이트웨이 빈만 등록하면 된다)
     */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(NicePhoneGateway.class)
    static class DefaultGatewayConfiguration {

        @Bean(NiceWebClientConfig.NICE_WEB_CLIENT)
        @ConditionalOnMissingBean(name = NiceWebClientConfig.NICE_WEB_CLIENT)
        WebClient niceWebClient(NiceProperties props) {
            return NiceWebClientConfig.niceWebClient(props);
        }

        @Bean
        @ConditionalOnMissingBean
        NiceApiClient niceApiClient(@org.springframework.beans.factory.annotation.Qualifier(NiceWebClientConfig.NICE_WEB_CLIENT) WebClient niceWebClient,
                                    NiceProperties props) {
            return new NiceApiClient(niceWebClient, props);
        }

        @Bean
        @ConditionalOnMissingBean
        NiceTokenStore niceTokenStore(RedisTemplate<String, String> stringRedisTemplate) {
            return new NiceTokenStore(stringRedisTemplate);
        }

        @Bean
        @ConditionalOnMissingBean
        NiceAuthSessionStore niceAuthSessionStore(RedisTemplate<String, String> stringRedisTemplate) {
            return new NiceAuthSessionStore(stringRedisTemplate);
        }

        @Bean
        NicePhoneGateway nicePhoneGateway(NiceApiClient niceApiClient, NiceTokenStore tokenStore,
                                          NiceAuthSessionStore sessionStore, ObjectMapper objectMapper,
                                          NiceProperties props, RedissonClient redissonClient) {
            return new NicePhoneService(niceApiClient, tokenStore, sessionStore, objectMapper, props, redissonClient);
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = "nicePhoneIdentityVerificationProvider")
    public IdentityVerificationProvider nicePhoneIdentityVerificationProvider(NicePhoneGateway gateway,
                                                                              NiceOacxPluginProperties props) {
        log.info("[NicePlugin] NICE_PHONE 제공자 등록 (EzAuth 위젯 {})", props.getWidgetScriptUrl());
        return new NicePhoneIdentityVerificationProvider(gateway, NiceEzAuthWidget.descriptor(props.getWidgetScriptUrl()));
    }

    @Bean
    @ConditionalOnClass(name = "OACX.OacxUtil")
    @ConditionalOnMissingBean(name = "oacxEasySignIdentityVerificationProvider")
    public IdentityVerificationProvider oacxEasySignIdentityVerificationProvider(OacxProperties oacxProps, ObjectMapper objectMapper) {
        // oacx 패키지는 SDK 가 있을 때만 컴파일되므로 리플렉션으로 생성한다 (SDK 부재 빌드에서도 이 클래스는 컴파일됨)
        try {
            Object adapter = Class.forName("io.github.hipstermin.idem.plugin.niceoacx.oacx.OacxClientAdapter")
                    .getDeclaredConstructor(OacxProperties.class).newInstance(oacxProps);
            Class<?> providerClass = Class.forName("io.github.hipstermin.idem.plugin.niceoacx.oacx.OacxEasySignIdentityVerificationProvider");
            log.info("[NicePlugin] OACX_EASYSIGN 제공자 등록 (SDK 발견)");
            return (IdentityVerificationProvider) providerClass
                    .getDeclaredConstructor(adapter.getClass(), ObjectMapper.class).newInstance(adapter, objectMapper);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OACX SDK 는 있으나 oacx 패키지가 빌드에 포함되지 않음 — vendorLibsDir 설정 확인", e);
        }
    }

    /** prod 부팅 검증 — 플러그인이 켜졌는데 자격증명이 없으면 기동 차단 (allow-missing-credentials 로 우회). */
    @Bean
    @Profile("prod")
    public Object niceCredentialsCheck(NiceProperties nice, OacxProperties oacx,
                                       @Value("${ido.auth.allow-missing-credentials:false}") boolean allowMissing) {
        boolean oacxAvailable;
        try { Class.forName("OACX.OacxUtil"); oacxAvailable = true; } catch (ClassNotFoundException e) { oacxAvailable = false; }
        NiceCredentialsValidator.validate(nice, oacx, oacxAvailable, allowMissing);
        return new Object();
    }
}
