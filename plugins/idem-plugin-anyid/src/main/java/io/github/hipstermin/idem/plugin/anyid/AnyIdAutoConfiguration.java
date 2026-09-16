package io.github.hipstermin.idem.plugin.anyid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.spi.broker.BrokerAuthCompletion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/**
 * Any-ID 설치형 플러그인 자동 설정 (S5b, {@code docs/vendor-plugin-plan.md} P3).
 *
 * <p>활성화: {@code idem.plugins.anyid.enabled=true} (기본값은 플러그인 기본 설정 파일이 true — 현행 KR 배포 호환.
 * 코어 에디션은 {@code IDEM_PLUGINS_ANYID_ENABLED=false}). 켜져 있어도 운영기관 식별자({@code ANYID_SRVC_NO} 등)가 비어 있으면
 * 브로커 진입은 503 으로 거부된다.
 *
 * <p>코어에서 받는 것: {@link BrokerAuthCompletion}(인증 결과 확정·FE 세션), {@link ObjectMapper}, {@link ResourceLoader}.
 * 코어에 주는 것: {@link io.github.hipstermin.idem.common.spi.broker.DirectBrokerAdapter}(AnyID 인증 시작 URL),
 * {@code /api/v1/anyid/*} 컨트롤러.
 *
 * <p>SDK({@code kr.or.anyid.util.AnyidCertRef})가 클래스패스에 있을 때만 {@link SsobDecryptor} 기본 구현이 등록된다.
 * 없으면 ssob 복호화 엔드포인트는 503 을 돌려주고 나머지(인증 시작·txId 발급·설정 조회)는 동작한다.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "idem.plugins.anyid", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AnyIdProperties.class)
public class AnyIdAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AnyIdBrokerAdapter anyIdBrokerAdapter(AnyIdProperties properties, ObjectMapper objectMapper) {
        return new AnyIdBrokerAdapter(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ido.anyid.kms", name = "app-key")
    @ConditionalOnMissingBean
    public AnyIdKmsClient anyIdKmsClient(AnyIdProperties properties, ObjectMapper objectMapper) {
        return new AnyIdKmsClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AnyIdController anyIdController(AnyIdBrokerAdapter adapter,
                                           ObjectProvider<SsobDecryptor> ssobDecryptor,
                                           BrokerAuthCompletion completion,
                                           AnyIdProperties properties,
                                           ObjectMapper objectMapper) {
        if (ssobDecryptor.getIfAvailable() == null) {
            log.warn("[idem-plugin-anyid] AnyID SDK 가 클래스패스에 없어 ssob 복호화 엔드포인트는 503 을 돌려줍니다 "
                    + "(vendor-libs 에 anyid-auth-util-sdk-*.jar 공급 필요)");
        }
        return new AnyIdController(adapter, ssobDecryptor, completion, properties, objectMapper);
    }

    /**
     * SDK 가 있을 때만 — {@code sdk} 패키지는 SDK 부재 시 컴파일에서 빠지므로 클래스 이름으로만 조건을 걸고
     * 구현체도 리플렉션으로 만든다(이 클래스가 {@code sdk} 패키지를 컴파일 시점에 참조하면 SDK 없는 빌드가 깨진다).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "kr.or.anyid.util.AnyidCertRef")
    static class SdkConfiguration {

        static final String IMPL = "io.github.hipstermin.idem.plugin.anyid.sdk.AnyIdSdkSsobDecryptor";

        @Bean
        @ConditionalOnMissingBean(SsobDecryptor.class)
        public SsobDecryptor anyIdSdkSsobDecryptor(AnyIdProperties properties, ObjectMapper objectMapper,
                                                   ResourceLoader resourceLoader) {
            try {
                return (SsobDecryptor) Class.forName(IMPL)
                        .getConstructor(AnyIdProperties.class, ObjectMapper.class, ResourceLoader.class)
                        .newInstance(properties, objectMapper, resourceLoader);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("AnyID SDK 는 있으나 플러그인이 sdk 패키지 없이 빌드됨 — vendor-libs 를 두고 다시 빌드하세요", e);
            }
        }
    }
}
