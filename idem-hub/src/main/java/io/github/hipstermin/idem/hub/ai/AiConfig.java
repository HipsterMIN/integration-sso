package io.github.hipstermin.idem.hub.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** 1.1 AI 운영 보조 — LLM 호출용 RestTemplate. 응답이 수십 초 걸리므로 내부 서비스·SCIM 타임아웃과 분리한다. */
@Configuration
public class AiConfig {

    @Bean("aiRestTemplate")
    public RestTemplate aiRestTemplate(AiProperties props) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        return new RestTemplate(factory);
    }
}
