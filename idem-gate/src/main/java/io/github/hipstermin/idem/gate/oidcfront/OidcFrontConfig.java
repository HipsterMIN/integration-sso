package io.github.hipstermin.idem.gate.oidcfront;

import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/** 프록시 전용 RestTemplate — 302 를 따라가지 않고, 4xx/5xx 도 그대로 돌려준다(RP·브라우저가 받아야 할 응답). */
@Configuration
public class OidcFrontConfig {

    @Bean("keycloakProxyRestTemplate")
    public RestTemplate keycloakProxyRestTemplate(OidcFrontProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(props.getProxyConnectTimeoutMs());
        factory.setReadTimeout(props.getProxyReadTimeoutMs());
        RestTemplate rt = new RestTemplate(factory);
        rt.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse response) { return false; }
        });
        return rt;
    }
}
