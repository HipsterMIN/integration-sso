package kr.go.smes.ido.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IdO Web / HTTP 클라이언트 + 캐시 설정 (문서 §7.4)
 *
 * <p>Keycloak JWKS 공개키 캐시(keycloakJwks)를 포함한
 * RestTemplate, ObjectMapper, CacheManager Bean 등록.
 *
 * <p>캐시 전략:
 * <ul>
 *   <li>{@code keycloakJwks} : Keycloak JWKS 공개키 — kid 기반 캐시 (TTL 1시간)</li>
 *   <li>ConcurrentMapCacheManager 사용 — TTL 지원을 위해 Caffeine 권장 (실운영 시 교체)</li>
 * </ul>
 */
@Configuration
@EnableCaching
@EnableScheduling
public class IdoWebConfig {

    @Value("${ido.qsign.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${ido.qsign.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${ido.keycloak.jwks-cache-ttl-seconds:3600}")
    private long jwksCacheTtlSeconds;

    // ── HTTP 클라이언트 ────────────────────────────────────────────────────

    @Value("${ido.qim.connect-timeout-ms:3000}")
    private int qimConnectTimeoutMs;

    @Value("${ido.qim.read-timeout-ms:5000}")
    private int qimReadTimeoutMs;

    /**
     * 공통 RestTemplate — q-sign, Keycloak 내부 HTTP 통신
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * Q-IM 전용 RestTemplate (§11.5.4 QimClientImpl 의존)
     * - 별도 타임아웃 설정 (connect 3s / read 5s)
     */
    @Bean
    public RestTemplate qimRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(qimConnectTimeoutMs);
        factory.setReadTimeout(qimReadTimeoutMs);
        return new RestTemplate(factory);
    }

    // ── JSON 직렬화 ───────────────────────────────────────────────────────

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    // ── 캐시 설정 ─────────────────────────────────────────────────────────

    /**
     * CacheManager — Keycloak JWKS 공개키 캐시 포함
     *
     * <p>PoC: ConcurrentMapCacheManager 사용 (TTL 미지원 — 주기적 수동 만료).
     * 실운영: Caffeine CacheManager + expireAfterWrite(1h) 로 교체 권장.
     *
     * <p>캐시 이름:
     * <ul>
     *   <li>{@code keycloakJwks} — Keycloak JWKS RSA 공개키 (kid → RSAPublicKey)</li>
     * </ul>
     */
    @Bean
    public CacheManager cacheManager() {
        TtlConcurrentMapCacheManager manager = new TtlConcurrentMapCacheManager(
                jwksCacheTtlSeconds, "keycloakJwks");
        return manager;
    }

    // ── TTL 지원 CacheManager (PoC 간이 구현) ─────────────────────────────

    /**
     * ConcurrentMapCacheManager에 TTL을 간이 지원하는 구현.
     *
     * <p>캐시에 저장된 항목을 주기적으로 만료시키기 위해
     * ScheduledExecutorService로 ttl 초마다 전체 캐시를 무효화.
     *
     * <p>실운영에서는 Caffeine 또는 Redis 기반 CacheManager로 교체.
     */
    static class TtlConcurrentMapCacheManager extends ConcurrentMapCacheManager {

        private final long ttlSeconds;

        TtlConcurrentMapCacheManager(long ttlSeconds, String... cacheNames) {
            super(cacheNames);
            this.ttlSeconds = ttlSeconds;
            scheduleEviction();
        }

        private void scheduleEviction() {
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cache-evict-thread");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                    () -> getCacheNames().forEach(name -> {
                        org.springframework.cache.Cache cache = getCache(name);
                        if (cache != null) cache.clear();
                    }),
                    ttlSeconds, ttlSeconds, TimeUnit.SECONDS
            );
        }
    }
}
