package io.github.hipstermin.idem.hub.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * S8-T4 | Testcontainers 통합 테스트 기반 클래스
 *
 * <p>공유 인프라:
 * <ul>
 *   <li>PostgreSQL 15-alpine — Flyway 마이그레이션 자동 실행</li>
 *   <li>Redis 7-alpine — AgencyRateLimiter · TicketRepository · Idempotency 저장소</li>
 *   <li>WireMock — NICE 외부 API · Q-IM(idem-registry) HTTP Mock 서버</li>
 * </ul>
 *
 * <p><b>싱글턴 패턴(JVM 당 1회 기동)</b>: 컨테이너와 WireMock 은 static 초기화 블록에서 시작하고 JVM 종료 시 정리한다.
 * {@code @Container}/{@code @BeforeAll}·{@code @AfterAll} 로 관리하면 <em>테스트 클래스마다</em> 종료·재기동되는데,
 * Spring 테스트 컨텍스트는 클래스 간에 캐시되므로 두 번째 클래스부터 이미 죽은 DB·Redis·WireMock 포트를 바라보게 된다
 * (2026-09-08 전체 실행에서 Q-IM 스텁 connection refused 로 발견).
 *
 * <p>하위 테스트 클래스는 이 클래스를 상속하여 공유 인프라를 자동으로 활용한다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@ExtendWith(SpringExtension.class)
public abstract class IntegrationTestBase {

    // ── PostgreSQL 컨테이너 (Flyway 마이그레이션 포함) ──────────────────────
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("idem")
                    .withUsername("idem")
                    .withPassword("idem")
                    .withInitScript("integration/init-schema.sql")
                    .waitingFor(Wait.forListeningPort());

    // ── Redis 컨테이너 ────────────────────────────────────────────────────────
    @SuppressWarnings("resource")
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    // ── WireMock 서버 (NICE 외부 API · Q-IM Mock) ────────────────────────────
    protected static final WireMockServer wireMockServer =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        POSTGRES.start();
        REDIS.start();
        wireMockServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            wireMockServer.stop();
            REDIS.stop();
            POSTGRES.stop();
        }));
    }

    // ── Kafka 프로듀서 Mock ──────────────────────────────────────────────────
    // idoProducerFactory 는 transaction-id-prefix 가 고정된 트랜잭셔널 프로듀서라서, 브로커가 없으면
    // @Transactional 서비스 안의 kafkaTemplate.send() 가 initTransactions() 에서 max.block.ms(기본 60초)만큼
    // 요청 스레드를 붙잡는다. 통합 테스트는 Kafka 범위 밖이므로 템플릿을 Mock 으로 바꾸고 완료된 Future 를 돌려준다.
    @MockitoBean(name = "idoKafkaTemplate")
    protected KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void stubKafkaTemplate() {
        lenient().when(kafkaTemplate.send(anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(kafkaTemplate.send(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ── S7: 관리자 세션 (통합 테스트는 실제 로그인·TOTP 를 거친다) ───────────────
    private final java.util.Map<String, org.springframework.http.HttpHeaders> adminHeaderCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 부트스트랩 관리자 세션 쿠키 + CSRF 헤더. 컨텍스트(포트)마다 한 번 로그인한다 */
    protected org.springframework.http.HttpHeaders adminHeaders(org.springframework.boot.test.web.client.TestRestTemplate rest, String baseUrl) {
        return adminHeaders(rest.getRestTemplate(), baseUrl);
    }

    /** 4xx 를 예외로 던지지 않는 RestTemplate 용 */
    protected org.springframework.http.HttpHeaders adminHeaders(org.springframework.web.client.RestOperations rest, String baseUrl) {
        return adminHeaderCache.computeIfAbsent(baseUrl, u -> AdminTestSupport.headers(rest, u));
    }

    protected org.springframework.http.HttpHeaders withAdmin(org.springframework.http.HttpHeaders h,
                                                             org.springframework.web.client.RestOperations rest, String baseUrl) {
        h.addAll(adminHeaders(rest, baseUrl));
        return h;
    }

    /** 기존 헤더에 관리자 세션을 얹는다 */
    protected org.springframework.http.HttpHeaders withAdmin(org.springframework.http.HttpHeaders h,
                                                             org.springframework.boot.test.web.client.TestRestTemplate rest, String baseUrl) {
        h.addAll(adminHeaders(rest, baseUrl));
        return h;
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
        registry.add("idem.hub.auth.nice.base-url",            // S5a: 플러그인 NiceProperties
                () -> "http://localhost:" + wireMockServer.port());
        registry.add("idem.hub.nice.api-base-url",
                () -> "http://localhost:" + wireMockServer.port());
        registry.add("idem.hub.nice.access-token-url",
                () -> "http://localhost:" + wireMockServer.port() + "/v1/token");

        // Q-IM(idem-registry) → WireMock (Handoff 발급 경로의 사용자 상태 조회 등을 스텁으로 응답)
        registry.add("idem.hub.registry.base-url",
                () -> "http://localhost:" + wireMockServer.port());

        // Kafka 비활성화 (통합 테스트 범위 외)
        registry.add("spring.kafka.bootstrap-servers",  () -> "localhost:19092");
        registry.add("idem.hub.audit.kafka-publish-enabled", () -> "false");

        // Rate Limiter 활성화 (Redis Testcontainer 대상)
        registry.add("idem.hub.rate-limit.enabled",           () -> "true");
        registry.add("idem.hub.rate-limit.default-tps",       () -> "200");
        registry.add("idem.hub.rate-limit.default-daily-limit", () -> "1000000");
    }
}
