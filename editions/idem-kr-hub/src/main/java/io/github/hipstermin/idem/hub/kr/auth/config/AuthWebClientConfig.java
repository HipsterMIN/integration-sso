package io.github.hipstermin.idem.hub.kr.auth.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * NICE/OACX/통합인증 연동 WebClient 빈 설정
 *
 * <p>Q4=A 결정: {@code spring-webflux} + {@code reactor-netty-http} 라이브러리만 추가.
 * {@code spring-boot-starter-webflux} 미사용으로 Netty 서버로 전환되지 않음.
 * 기존 Tomcat 서블릿 컨테이너 유지.
 *
 * <p><b>Spring Boot WebApplicationType 결정 로직:</b>
 * <pre>
 * WebApplicationType.deduceFromClasspath():
 *   if DispatcherServlet.class exists → SERVLET (Tomcat 유지)
 *   if reactive dispatcher exists AND servlet dispatcher missing → REACTIVE (Netty 전환)
 * → ido는 DispatcherServlet 존재 → 항상 SERVLET
 * </pre>
 *
 * <p><b>WebClient 빈 목록:</b>
 * <ul>
 *   <li>{@code integrationAuthWebClient} — 통합인증 서버 전용 (기업인증 auth-check)</li>
 * </ul>
 *
 * <p>벤더 본인인증(NICE 본인확인·OACX 간편인증)의 WebClient 는 S5a 부터 각 플러그인이 스스로 만든다
 * ({@code plugins/idem-plugin-nice-oacx}).
 *
 * <p><b>기존 RestTemplate과의 공존:</b>
 * {@code IdoWebConfig}의 {@code restTemplate}, {@code qimRestTemplate},
 * {@code webhookRestTemplate} 빈과 충돌 없음.
 * WebClient는 통합인증 전용, RestTemplate은 Q-Sign/Q-IM/Webhook 전용으로 역할 분리.
 *
 * @see AuthProperties
 * @see io.github.hipstermin.idem.hub.kr.auth.client.IntegrationAuthClient
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthWebClientConfig {

    /**
     * 통합인증 WebClient Bean 이름 상수
     *
     * <p>{@code IntegrationAuthClient}에서 {@code @Qualifier(AuthWebClientConfig.INTEGRATION_AUTH_WEB_CLIENT)}로 주입.
     */
    public static final String INTEGRATION_AUTH_WEB_CLIENT = "integrationAuthWebClient";

    /** 통합인증 서버 WebClient — base URL·타임아웃은 {@code idem.hub.auth.integration.*} */
    @Bean(INTEGRATION_AUTH_WEB_CLIENT)
    public WebClient integrationAuthWebClient(AuthProperties props) {
        String baseUrl = props.integration().baseUrl();
        int timeoutSeconds = props.integration().timeoutSeconds();
        log.info("[AuthWebClientConfig] 통합인증 WebClient 초기화 — baseUrl={}, timeout={}s",
                baseUrl, timeoutSeconds);
        return baseBuilder(baseUrl, timeoutSeconds).build();
    }

    /**
     * 공통 WebClient.Builder 생성
     *
     * <p>모든 외부 인증 API 클라이언트에 공통 적용되는 설정:
     * <ul>
     *   <li>Reactor Netty HTTP 클라이언트 (Netty 서버 아님 — 클라이언트 전용)</li>
     *   <li>커넥션 타임아웃: 5초 고정</li>
     *   <li>응답 타임아웃: 설정값 기준</li>
     *   <li>요청 로깅 필터: 아웃바운드 HTTP 요청 메서드/URL 로그</li>
     * </ul>
     *
     * @param baseUrl        Base URL
     * @param timeoutSeconds 응답 타임아웃 (초)
     * @return WebClient.Builder
     */
    private WebClient.Builder baseBuilder(String baseUrl, int timeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .filter(logOutboundRequest());
    }

    /**
     * 아웃바운드 HTTP 요청 로깅 ExchangeFilterFunction
     *
     * <p>외부 인증 서버 호출 시 메서드, URL, 헤더를 INFO 레벨로 기록.
     * Authorization 헤더 값은 보안상 마스킹되지 않으므로 운영 환경 로그 수준 주의.
     * 운영 환경에서는 로그 레벨을 WARN 이상으로 설정하여 민감 정보 노출 방지.
     */
    private ExchangeFilterFunction logOutboundRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            log.info("[Auth-Outbound] {} {} | headers={}", req.method(), req.url(), req.headers());
            return Mono.just(req);
        });
    }
}
