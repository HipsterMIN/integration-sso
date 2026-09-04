/**
 * idem-relay — Transactional Outbox 분산 릴레이 배치 서비스
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  설계 배경                                                               │
 * │  - 기존: 각 Spring Boot 앱(@Scheduled) 내부에서 인-프로세스 폴링 릴레이  │
 * │  - 신규: 독립 배치 프로세스에서 ShedLock 분산 락으로 중복 실행 방지       │
 * │                                                                         │
 * │  ShedLock 선택 근거:                                                     │
 * │  - FOR UPDATE SKIP LOCKED(DB 락) + ShedLock(프로세스 락) 이중 방어       │
 * │  - Redis 장애 시 DB 기반 ShedLock으로 폴백 가능                          │
 * │  - 배치 실행 이력(shedlock 테이블)이 자동 기록 → 운영 가시성 확보         │
 * │                                                                         │
 * │  DB 접근 전략:                                                           │
 * │  - idem-hub(PostgreSQL) / idem-registry(MariaDB) / idem-gate(PostgreSQL) 각각 독립 DS    │
 * │  - Spring Boot 멀티 DataSource 설정                                      │
 * │  - Flyway: idem-hub DS만 마이그레이션 (shedlock 테이블 V19)                   │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

// ── ShedLock 버전 (Spring 6.x / Boot 3.x 호환) ──────────────────────────────
val shedlockVersion = "6.6.1"

dependencies {
    // ── idem-common (공통 이벤트/도메인 객체) ──────────────────────────────
    implementation(project(":idem-common"))

    // ── Spring Boot Starters ────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")           // Actuator HTTP
    implementation("org.springframework.boot:spring-boot-starter-actuator")      // /actuator/health,metrics
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")      // JPA (idem-gate 엔티티)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")    // ShedLock Redis Provider

    // ── JDBC (idem-hub/idem-registry 직접 JDBC 접근 — JSONB 컨트롤) ─────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // ── DB 드라이버 ──────────────────────────────────────────────────────────
    implementation("org.postgresql:postgresql")                                   // idem-hub, idem-gate
    implementation("org.mariadb.jdbc:mariadb-java-client")                       // idem-registry

    // ── Kafka Producer ──────────────────────────────────────────────────────
    implementation("org.springframework.kafka:spring-kafka")

    // ── ShedLock Core + Providers ────────────────────────────────────────────
    // net.javacrumbs.shedlock:shedlock-spring: @SchedulerLock AOP 어노테이션
    // shedlock-provider-redis-spring: Redis 기반 분산 락 (Primary)
    // shedlock-provider-jdbc-template: JDBC 기반 분산 락 (Fallback / 병렬 설치 가능)
    implementation("net.javacrumbs.shedlock:shedlock-spring:$shedlockVersion")
    implementation("net.javacrumbs.shedlock:shedlock-provider-redis-spring:$shedlockVersion")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:$shedlockVersion")

    // ── Redisson (ShedLock Redis Provider 내부에서도 사용 가능 — 여기선 기본 Lettuce 사용) ──
    // 향후 Redisson 기반 ShedLock Provider로 교체 시: shedlock-provider-redisson 추가
    // 현재는 shedlock-provider-redis-spring(Lettuce) 사용

    // ── Flyway (shedlock 테이블 마이그레이션 — idem-hub PostgreSQL) ─────────────────
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-mysql")                                  // MariaDB 마이그레이션

    // ── Apache HttpClient 5 — PoolingConnectionManager 기반 RestTemplate ──────
    // SimpleClientHttpRequestFactory(JDK 기본)는 커넥션 풀 없음 → 기관 동시 연결 제어 불가
    // HC5 PoolingHttpClientConnectionManager로 기관별 최대 연결 수 제어
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // ── Micrometer (Prometheus — 릴레이 성공/실패 메트릭) ──────────────────────
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ── 테스트 ──────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:mariadb")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}
