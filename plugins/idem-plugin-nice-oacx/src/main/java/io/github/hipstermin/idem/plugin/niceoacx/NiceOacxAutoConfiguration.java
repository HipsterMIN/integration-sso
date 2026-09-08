package io.github.hipstermin.idem.plugin.niceoacx;

import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * NICE/OACX 플러그인 자동 구성.
 *
 * <p>활성화: {@code idem.plugins.nice-oacx.enabled=true}. 골격 단계에서는 {@link NicePhoneGateway} 빈이 있을 때만
 * NICE 제공자를 등록한다(게이트웨이 구현은 P2 본작업에서 idem-hub 로부터 이동). OACX 제공자는 클래스패스에
 * SDK 진입 클래스({@code OACX.OacxUtil})가 있을 때만 등록된다.
 */
@AutoConfiguration
@EnableConfigurationProperties(NiceOacxPluginProperties.class)
@ConditionalOnProperty(prefix = "idem.plugins.nice-oacx", name = "enabled", havingValue = "true")
public class NiceOacxAutoConfiguration {

    @Bean
    @ConditionalOnBean(NicePhoneGateway.class)
    @ConditionalOnMissingBean(name = "nicePhoneIdentityVerificationProvider")
    public IdentityVerificationProvider nicePhoneIdentityVerificationProvider(NicePhoneGateway gateway,
                                                                              NiceOacxPluginProperties props) {
        return new NicePhoneIdentityVerificationProvider(gateway, NiceEzAuthWidget.descriptor(props.getWidgetScriptUrl()));
    }

    @Bean
    @ConditionalOnClass(name = "OACX.OacxUtil")
    @ConditionalOnMissingBean(name = "oacxEasySignIdentityVerificationProvider")
    public IdentityVerificationProvider oacxEasySignIdentityVerificationProvider(ObjectProvider<Object> ignored) {
        // oacx 패키지는 SDK 가 있을 때만 컴파일되므로 리플렉션으로 생성한다 (SDK 부재 빌드에서도 이 클래스는 컴파일됨)
        try {
            return (IdentityVerificationProvider) Class
                    .forName("io.github.hipstermin.idem.plugin.niceoacx.oacx.OacxEasySignIdentityVerificationProvider")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OACX SDK 는 있으나 oacx 패키지가 빌드에 포함되지 않음 — vendorLibsDir 설정 확인", e);
        }
    }
}
