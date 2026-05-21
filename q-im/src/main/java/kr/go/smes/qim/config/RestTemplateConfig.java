package kr.go.smes.qim.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Q-IM RestTemplate 설정
 *
 * <p>AgencyMemberLookupServiceImpl 에서 사용하는 HTTP 클라이언트.
 * 기관별 타임아웃은 AgencyRegistration.timeoutMs 로 개별 관리하되,
 * RestTemplate 레벨 기본 타임아웃도 설정하여 방어층을 중첩한다.
 */
@Configuration
public class RestTemplateConfig {

    @Value("${qim.agency.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${qim.agency.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
