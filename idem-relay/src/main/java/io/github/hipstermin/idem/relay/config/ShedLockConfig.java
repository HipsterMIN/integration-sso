package io.github.hipstermin.idem.relay.config;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock 분산 락 Provider 설정
 *
 * <h2>이중화 전략</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  Primary: Redis ShedLock Provider                                    │
 * │  - RedisConnectionFactory(Lettuce) 기반                              │
 * │  - Redis 키: shedlock:{lockName}                                     │
 * │  - TTL: lockAtMostFor 값으로 자동 만료 → 서비스 장애 시 자동 해제    │
 * │  - 성능 우수: 네트워크 RTT ~1ms 내외                                 │
 * │                                                                      │
 * │  Fallback: JDBC ShedLock Provider                                    │
 * │  - ido PostgreSQL의 shedlock 테이블 사용                             │
 * │  - Redis 장애 시 이 Provider로 수동 전환 (환경변수 LOCK_PROVIDER)     │
 * │  - 실행 이력이 DB에 영구 기록 → 운영 감사에 유리                      │
 * └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>lockAtMostFor 설계 기준</h2>
 * <pre>
 * Job명                | 배치 주기 | lockAtMostFor | lockAtLeastFor
 * ─────────────────────┼──────────┼───────────────┼───────────────
 * ido-kafka-relay      | 500ms    | 10s           | 400ms
 * qim-kafka-relay      | 500ms    | 10s           | 400ms
 * qsign-kafka-relay    | 500ms    | 10s           | 400ms
 * ido-provisioning-    | 30s      | 60s           | 25s
 * ido-webhook-relay    | 500ms    | 10s           | 400ms
 * </pre>
 *
 * lockAtMostFor을 배치 주기의 20배로 설정하는 이유:
 * - 배치 Job이 100건 처리 × HTTP 최대 응답 3s = 최대 300s 가능 (프로비저닝)
 * - 단, 정상 운영 시 훨씬 빠름 → 여유 있는 값 설정이 안전
 *
 * <h2>ShedLock 테이블 구조 (V19 마이그레이션)</h2>
 * <pre>
 * CREATE TABLE idem.hub.shedlock (
 *   name       VARCHAR(64)  NOT NULL,   -- Job 이름
 *   lock_until TIMESTAMP    NOT NULL,   -- lockAtMostFor 기준 만료 시각
 *   locked_at  TIMESTAMP    NOT NULL,   -- 락 획득 시각
 *   locked_by  VARCHAR(255) NOT NULL,   -- Pod 호스트명
 *   PRIMARY KEY (name)
 * );
 * </pre>
 */
@Slf4j
@Configuration
public class ShedLockConfig {

    /**
     * Redis 기반 LockProvider (Primary)
     *
     * <p>Redis 키 네임스페이스: {@code shedlock:}
     * Spring Boot auto-configured RedisConnectionFactory(Lettuce)를 재사용하므로
     * 추가 Redis 연결 풀 없이 동작.
     *
     * <p>Redis 장애 시: {@code RedisLockProvider.obtainLock()} 이 예외를 던져
     * ShedLock이 Job 실행을 방어적으로 스킵 (null-safe 기본 동작).
     * → JDBC Provider로 수동 전환 필요 (IDEM_RELAY_LOCK_PROVIDER=jdbc 환경변수)
     */
    @Bean
    @Primary
    public LockProvider redisLockProvider(RedisConnectionFactory connectionFactory) {
        log.info("[ShedLock] Redis LockProvider 초기화 (Primary)");
        return new RedisLockProvider(connectionFactory, "shedlock");
    }

    /**
     * JDBC 기반 LockProvider (Fallback)
     *
     * <p>ido PostgreSQL의 {@code idem.hub.shedlock} 테이블을 사용.
     * Redis 장애 시 이 Bean 이름을 @Primary로 교체하거나,
     * Spring Profile/환경변수로 Provider 전환 구성 가능.
     *
     * <p>JDBC Provider 특징:
     * <ul>
     *   <li>실행 이력이 DB에 영구 보관 → 운영 감사 용이</li>
     *   <li>Redis보다 레이턴시 높음 (~5~10ms)</li>
     *   <li>DB 부하 소량 증가 (INSERT/UPDATE per lock acquire)</li>
     * </ul>
     *
     * <p>JdbcTemplateLockProvider 설정:
     * <ul>
     *   <li>tablePrefix: "ido." — shedlock 테이블이 ido 스키마에 있음</li>
     *   <li>usingDbTime(): DB 서버 시각 사용 → Pod 간 시각 차이 방지</li>
     * </ul>
     */
    @Bean(name = "jdbcLockProvider")
    public LockProvider jdbcLockProvider(
            @Qualifier("idoDataSource") DataSource idoDataSource) {
        log.info("[ShedLock] JDBC LockProvider 초기화 (Fallback — ido.shedlock)");
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(idoDataSource))
                        .withTableName("ido.shedlock")
                        .usingDbTime()   // DB 서버 시각 기준 — NTP 불일치 방어
                        .build()
        );
    }
}
