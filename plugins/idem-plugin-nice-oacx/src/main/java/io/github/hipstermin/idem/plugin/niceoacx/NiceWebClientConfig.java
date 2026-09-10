package io.github.hipstermin.idem.plugin.niceoacx;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** NICE API WebClient 생성 (S5a — 종전 idem-hub {@code AuthWebClientConfig} 의 NICE 부분). */
@Slf4j
public final class NiceWebClientConfig {

    public static final String NICE_WEB_CLIENT = "niceWebClient";
    private static final String NICE_DEV_LANG_HEADER = "X-Tntc-DevLang";
    private static final String NICE_DEV_LANG_VALUE = "Linux/Java";

    private NiceWebClientConfig() {}

    public static WebClient niceWebClient(NiceProperties props) {
        log.info("[NicePlugin] NICE WebClient 초기화 — baseUrl={}, timeout={}s", props.getBaseUrl(), props.getTimeoutSeconds());
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));
        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(NICE_DEV_LANG_HEADER, NICE_DEV_LANG_VALUE)
                .filter(ExchangeFilterFunction.ofRequestProcessor(req -> {
                    log.info("[NicePlugin-Outbound] {} {}", req.method(), req.url());
                    return Mono.just(req);
                }))
                .build();
    }
}
