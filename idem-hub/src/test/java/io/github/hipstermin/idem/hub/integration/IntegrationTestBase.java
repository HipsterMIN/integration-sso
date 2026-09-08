package io.github.hipstermin.idem.hub.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * S8-T4 | Testcontainers 통합 테스트 기반 클래스
 *
 * <p>공유 인프라:
 * <ul>
 *   <li>PostgreSQL 15-alpine — Flyway 마이그레이션 자동 실행</li>
 *   <li>Redis 7-alpine — AgencyRateLimiter · TicketRepository · Idempotency 저장소</li>
 *   <li>WireMock — NICE 외부 API HTTP Mock 서버</li>
 * </ul>
 *
 * <p>컨테이너는 {@code @Container + static} 선언으로 테스트 스위트 실행 동안
 * 단 한 번만 시작/종료 (Singleton 패턴 — 빠른 실행 보장).
 *
 * <p>하위 테스트 클래스는 이 클래스를 상속하여 공유 인프라를 자동으로 활용한다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@ExtendWith(SpringExtension.class)
public abstract class IntegrationTestBase {

    // ── PostgreSQL 컨테이너 (Flyway 마이그레이션 포함) ──────────────────────
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("onepass")
                    .withUsername("onepass")
                    .withPassword("onepass")
                    .withInitScript("integration/init-schema.sql")
                    .waitingFor(Wait.forListeningPort());

    // ── Redis 컨테이너 ────────────────────────────────────────────────────────
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    // ── WireMock 서버 (NICE 외부 API Mock) ───────────────────────────────────
    static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.wireMockConfig().dynamicPort()
        );
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    // ── Spring 동적 프로퍼티 주입 ─────────────────────────────────────────────
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",  POSTGRES::getUsername);
        registry.add("spring.datasource.password",  POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");

        // Flyway: 실제 마이그레이션 실행
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration");

        // Redis
        registry.add("spring.data.redis.host",
                () -> REDIS.getHost());
        registry.add("spring.data.redis.port",
                () -> REDIS.getMappedPort(6379).toString());

        // WireMock → NICE API 기본 URL 오버라이드
        registry.add("ido.nice.api-base-url",
                () -> "http://localhost:" + wireMockServer.port());
        registry.add("ido.nice.access-token-url",
                () -> "http://localhost:" + wireMockServer.port() + "/v1/token");

        // Kafka 비활성화 (통합 테스트 범위 외)
        registry.add("spring.kafka.bootstrap-servers",  () -> "localhost:19092");
        registry.add("ido.audit.kafka-publish-enabled", () -> "false");

        // Rate Limiter 활성화 (Redis Testcontainer 대상)
        registry.add("ido.rate-limit.enabled",           () -> "true");
        registry.add("ido.rate-limit.default-tps",       () -> "200");
        registry.add("ido.rate-limit.default-daily-limit", () -> "1000000");
    }
}
