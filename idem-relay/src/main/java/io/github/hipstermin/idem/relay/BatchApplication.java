package io.github.hipstermin.idem.relay;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox Relay 배치 서비스 — 독립 Spring Boot 프로세스
 *
 * <h2>설계 원칙</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  기존 구조 (인-프로세스 폴링)                                                  │
 * │  ido / q-im / q-sign                                                          │
 * │  └─ @Scheduled 500ms 폴링 → Kafka/HTTP 발행                                   │
 * │     ⚠️ 문제: 메인 앱 DB 커넥션 풀 공유 / 스케일아웃 불가                         │
 * │                                                                               │
 * │  신규 구조 (별도 배치 프로세스)                                                  │
 * │  outbox-relay-batch (이 서비스)                                                 │
 * │  └─ @SchedulerLock (ShedLock) 기반 분산 락 → 단일 인스턴스만 실행 보장           │
 * │  └─ FOR UPDATE SKIP LOCKED → DB 레코드 단위 중복 처리 방지 (이중 방어)           │
 * │  └─ ido.outbox / qim.outbox / qsign.outbox 각각 독립 JdbcTemplate 접근         │
 * │  └─ Redis ShedLock (Primary) + JDBC ShedLock (Fallback) 이중화                │
 * └──────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>ShedLock 동작 원리</h2>
 * <ol>
 *   <li>{@code @SchedulerLock(name = "ido-kafka-relay", lockAtMostFor = "10s")}
 *       선언 시 해당 Job이 실행되는 동안 Redis(또는 DB)에 락 레코드를 생성</li>
 *   <li>다른 인스턴스가 동일 시각에 스케줄 실행을 시도하면 락 획득 실패 → 스킵</li>
 *   <li>{@code lockAtMostFor}: 비정상 종료 시 최대 보유 시간 — 이 시간이 지나면 자동 해제</li>
 *   <li>{@code lockAtLeastFor}: 빠른 완료 후 다른 인스턴스의 즉시 실행 방지 (중복 실행 간격 보장)</li>
 * </ol>
 *
 * <h2>배포 방식</h2>
 * <pre>
 * # Docker / K8s — replicas: 2 이상으로 HA 구성 가능
 * # ShedLock이 단일 실행을 보장하므로 다중 Pod 배포 시 자동 페일오버
 * java -jar outbox-relay-batch.jar --spring.profiles.active=prod
 * </pre>
 *
 * <h2>운영 주의사항</h2>
 * <ul>
 *   <li>기존 ido/q-im/q-sign의 인-프로세스 @Scheduled 릴레이는
 *       이 서비스 배포 후 Feature Flag으로 비활성화할 것
 *       (IDO_OUTBOX_RELAY_ENABLED=false, IDO_QIM_OUTBOX_RELAY_ENABLED=false 등)</li>
 *   <li>Redis 장애 시 JDBC ShedLock으로 자동 폴백 → ido DB의 shedlock 테이블 사용</li>
 *   <li>이 배치 서비스 자체가 다운되면 outbox PENDING 레코드가 적체됨 →
 *       K8s liveness/readiness probe + HPA 설정 필수</li>
 * </ul>
 *
 * @see io.github.hipstermin.idem.relay.config.ShedLockConfig
 * @see io.github.hipstermin.idem.relay.config.BatchDataSourceConfig
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10s")
public class BatchApplication {

    public static void main(String[] args) {
        log.info("========================================================");
        log.info("  OnePass Outbox Relay Batch Service starting...");
        log.info("  Lock Provider: Redis (Primary) + JDBC (Fallback)");
        log.info("========================================================");
        SpringApplication.run(BatchApplication.class, args);
    }
}
