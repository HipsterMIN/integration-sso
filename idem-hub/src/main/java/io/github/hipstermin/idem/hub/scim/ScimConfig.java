package io.github.hipstermin.idem.hub.scim;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 1.1 SCIM 클라이언트용 RestTemplate — SCIM 은 PATCH 를 쓰는데 {@code HttpURLConnection}(webhookRestTemplate) 은 PATCH 를 못 보낸다.
 * JDK {@link HttpClient} 기반, 기관 외부 호출이라 내부 서비스 타임아웃과 분리한다.
 */
@Configuration
public class ScimConfig {

    @Bean("scimRestTemplate")
    public RestTemplate scimRestTemplate(@Value("${idem.hub.scim.connect-timeout-ms:3000}") long connectMs,
                                         @Value("${idem.hub.scim.read-timeout-ms:8000}") long readMs) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(readMs));
        return new RestTemplate(factory);
    }
}
