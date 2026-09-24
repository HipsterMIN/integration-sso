package io.github.hipstermin.idem.hub.broker.keycloak.admin;

import io.github.hipstermin.idem.hub.protocol.oidcrp.OidcRpProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/** Keycloak Admin 전용 RestTemplate — 짧은 타임아웃, 4xx/5xx 를 예외 대신 상태로 돌려 호출자가 메시지를 만든다. */
@Configuration
public class KeycloakAdminConfig {

    @Bean("keycloakAdminRestTemplate")
    public RestTemplate keycloakAdminRestTemplate(OidcRpProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getProvisioner().getConnectTimeoutMs());
        factory.setReadTimeout(props.getProvisioner().getReadTimeoutMs());
        RestTemplate rt = new RestTemplate(factory);
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return rt;
    }
}
