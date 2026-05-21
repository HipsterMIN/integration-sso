package kr.go.smes.ido.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 분산 락 클라이언트 설정 (S9-T1)
 *
 * <p><b>도입 배경 — P0 문제 해결:</b>
 * {@code NiceAuthService.ensureAccessToken()}은 기존에 {@code synchronized}로
 * 단일 JVM 내 중복 발급을 방지했으나, K8s 다중 Pod 환경에서는 두 Pod가 동시에
 * NICE 서버에 토큰 발급 요청을 보내 Rate Limit 초과 위험이 있었다.
 *
 * <p><b>해결책 — Redisson 분산 락:</b>
 * <ul>
 *   <li>Redis 기반 분산 락으로 전체 Pod에서 한 번에 하나의 토큰 갱신만 허용</li>
 *   <li>tryLock(3s 대기, 10s 만료): 3초 내 락 획득 못하면 이미 다른 Pod가 갱신 중 → 기다렸다 재확인</li>
 *   <li>Watch Dog 패턴: 락 보유 중 서비스 종료 시 10초 후 자동 해제로 데드락 방지</li>
 * </ul>
 *
 * <p><b>F-08 On/Off 제어:</b>
 * <pre>
 * IDO_REDISSON_ENABLED=true  (기본) → RedissonClient 빈 등록, 분산 락 활성
 * IDO_REDISSON_ENABLED=false         → RedissonClient 빈 미등록, NoOpRedissonClient 사용
 *                                      Redis 없는 로컬 환경에서도 앱 정상 기동 가능
 * </pre>
 *
 * <p><b>OFF 시 동작:</b>
 * {@code NiceAuthService.ensureAccessToken()}은 JVM 내 {@code synchronized}만으로 동작.
 * 단일 Pod 환경에서는 충분하나, K8s 다중 Pod에서는 중복 발급 가능성 있음.
 *
 * <p><b>⚠️ 운영 멀티 Pod 주의:</b>
 * HPA로 2개 이상 Pod 운영 시 반드시 {@code IDO_REDISSON_ENABLED=true} 유지.
 *
 * <p><b>Key 네이밍:</b>
 * <pre>
 * ido:lock:nice-token-refresh  — NICE Access Token 갱신 락 (단일)
 * ido:lock:key-rotation        — AES 키 로테이션 스케줄러 락 (다중 Pod 중복 실행 방지)
 * </pre>
 *
 * @see kr.go.smes.ido.auth.service.NiceAuthService
 * @see kr.go.smes.ido.config.NoOpRedissonConfig
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "ido.redisson.enabled", havingValue = "true", matchIfMissing = true)
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * RedissonClient 빈 등록
     *
     * <p>단일 서버 모드 (Single 모드)로 설정.
     * K8s 환경에서 Redis Sentinel/Cluster를 사용하는 경우 이 빈을 Sentinel/Cluster 모드로 교체.
     *
     * <p>Lettuce와 공존: Spring Data Redis의 Lettuce 연결 풀과 Redisson 연결 풀은 독립적.
     * Redisson은 자체 Netty 이벤트 루프를 사용하므로 Lettuce 연결에 영향 없음.
     *
     * @return 분산 락에 사용할 RedissonClient
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        String address = "redis://" + redisHost + ":" + redisPort;
        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10)
                .setConnectTimeout(3000)
                .setTimeout(2000)
                .setRetryAttempts(3)
                .setRetryInterval(500);

        if (redisPassword != null && !redisPassword.isBlank()) {
            singleServerConfig.setPassword(redisPassword);
        }

        log.info("[RedissonConfig] RedissonClient 초기화 완료 (분산 락 활성): address={}", address);
        return Redisson.create(config);
    }
}
