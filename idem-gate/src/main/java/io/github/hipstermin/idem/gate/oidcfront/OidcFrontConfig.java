package io.github.hipstermin.idem.gate.oidcfront;

import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/** 프록시 전용 RestTemplate — 302 를 따라가지 않고, 4xx/5xx 도 그대로 돌려준다(RP·브라우저가 받아야 할 응답). 1.0.1: 공개 프런트 레이트리밋 필터 등록. */
@Configuration
public class OidcFrontConfig {

    /** {@code /realms/**} IP 레이트리밋 (3차 점검 M17) — 보안 헤더 필터(@Order(1))보다 앞. */
    @Bean
    public FilterRegistrationBean<OidcFrontRateLimitFilter> oidcFrontRateLimitFilter(StringRedisTemplate redis, OidcFrontProperties props) {
        FilterRegistrationBean<OidcFrontRateLimitFilter> reg = new FilterRegistrationBean<>(new OidcFrontRateLimitFilter(redis, props));
        reg.addUrlPatterns("/realms/*");
        reg.setOrder(0);
        reg.setName("oidcFrontRateLimitFilter");
        return reg;
    }

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
