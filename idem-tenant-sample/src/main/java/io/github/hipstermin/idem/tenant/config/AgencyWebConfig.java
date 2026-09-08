package io.github.hipstermin.idem.tenant.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Agency-Stub 웹 설정
 * - IdO Verify API 호출용 RestTemplate
 * - 공유 ObjectMapper
 * - AgencyApiKeyInterceptor 등록 (IdO Verify 호출 보호)
 */
@Configuration
@EnableScheduling
public class AgencyWebConfig implements WebMvcConfigurer {

    @Value("${agency-stub.ido.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${agency-stub.ido.read-timeout-ms:5000}")
    private int readTimeoutMs;

    private final AgencyApiKeyInterceptor agencyApiKeyInterceptor;

    public AgencyWebConfig(AgencyApiKeyInterceptor agencyApiKeyInterceptor) {
        this.agencyApiKeyInterceptor = agencyApiKeyInterceptor;
    }

    /**
     * IdO Verify API 호출 전용 RestTemplate
     * connect 3s / read 5s
     */
    @Bean("idoRestTemplate")
    public RestTemplate idoRestTemplate(ObjectMapper objectMapper) {
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        return new RestTemplateBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .additionalMessageConverters(converter)
                .build();
    }

    /**
     * 공유 ObjectMapper — JavaTimeModule 등록, 미지 필드 허용
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * IdO → 기관 엔드포인트를 제외한 /agency/entry, /api/v1/events/poll 에만
     * Agency API Key 인터셉터 적용 (Webhook 수신 경로는 HMAC 서명으로 별도 검증)
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(agencyApiKeyInterceptor)
                .addPathPatterns("/agency/entry/**")
                .excludePathPatterns(
                        "/api/v1/webhook/**",    // IdO → 기관 webhook (HMAC 검증)
                        "/api/v1/events/**",     // 폴링 API (내부 서비스 호출)
                        "/actuator/**"
                );
    }
}
