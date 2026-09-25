package io.github.hipstermin.idem.tenant.e2e;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.TestSocketUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Agency-Stub E2E 통합 테스트 기반 클래스
 *
 * <p>모듈 간 e2e 시나리오 자동화의 토대:
 * {@code agency-stub → ido → q-sign → ido → handoff → agency} 흐름을
 * 단일 SpringBoot 컨텍스트 안에서 검증한다.
 *
 * <p><b>인프라 구성</b>:
 * <ul>
 *   <li>PostgreSQL 15-alpine (Testcontainers) — agency-stub Flyway V1/V2 자동 실행</li>
 *   <li>내장 {@link MockIdoVerifyController} — 동일 JVM/포트에서 IdO Verify API 응답 모의
 *       <br>q-sign 인증 결과(qimUserId / authLevel / providerCode / authResultId)를
 *       HandoffPayload 형태로 캡슐화하여 IdoVerifyClient 에 응답</li>
 *   <li>Kafka 리스너는 application-e2e-test.yml 의 {@code listener.auto-startup=false}
 *       로 컨테이너 비기동 (메타데이터 조회 시도 차단)</li>
 *   <li>Redis 는 사용하지 않음 — 더미 호스트로 두며, e2e 흐름에서 호출 경로 없음</li>
 * </ul>
 *
 * <p><b>"ido를 자기 자신으로 가리키기" 트릭</b>:
 * 컨텍스트 부팅 전에 {@link TestSocketUtils#findAvailableTcpPort()} 로 자유 포트를 선점한 뒤,
 * {@code @DynamicPropertySource} 에서 {@code server.port} 와
 * {@code agency-stub.ido.base-url} 을 동일 포트로 묶어 주입한다.
 * <br>이로써 {@link io.github.hipstermin.idem.tenant.client.IdoVerifyClient} (bean 초기화 시점에 @Value resolve)
 * 가 호출하는 외부 IdO 가 동일 컨텍스트 내 {@link MockIdoVerifyController} 로 라우팅된다.
 *
 * <p><b>왜 별도 e2e 모듈이 아닌 agency-stub 내부에 두는가</b>:
 * 진입점(agency-stub 의 {@code AgencyEntryController})이 외부 ido HTTP 호출을 통해
 * 전체 사슬을 동기 trigger 하므로, agency-stub 의 실제 컨트롤러 + 인터셉터 + DB +
 * Resilience4j 를 그대로 가동한 상태에서 ido 응답만 모의하는 것이 가장 사실적인 e2e 다.
 * IdO 측 비즈니스 로직(HandoffService.verify 의 ticket consume / SoR 매핑)은
 * {@code ido} 모듈의 {@code HandoffIntegrationTest} 에서 이미 검증되므로, 본 e2e 는
 * 모듈 경계의 데이터 계약(HandoffPayload 직렬화 / X-Agency-Code / X-Agency-Key /
 * AGSID 발급 / 세션 라이프사이클) 회귀 방어에 집중한다.
 */
@Tag("e2e")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("e2e-test")
@Testcontainers
@ExtendWith(SpringExtension.class)
@Import({E2EIntegrationTestBase.MockIdoTestConfig.class,
         MockIdoVerifyController.class})
public abstract class E2EIntegrationTestBase {

    /**
     * 컨텍스트 부팅 전에 미리 잡아둔 자유 포트.
     * server.port 와 ido.base-url 의 포트를 일치시키기 위해 static 으로 고정.
     */
    protected static final int SHARED_PORT = TestSocketUtils.findAvailableTcpPort();

    // ── PostgreSQL 컨테이너 (싱글톤 — JVM 라이프타임 공유) ──────────────────────
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                .withDatabaseName("idem")
                .withUsername("idem")
                .withPassword("idem")
                .withInitScript("e2e/agency-stub-e2e-init.sql");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // server.port = SHARED_PORT — ido.base-url 과 동일 포트로 묶기 위해 명시 고정
        registry.add("server.port", () -> SHARED_PORT);

        // agency-stub.ido.base-url = self port → 내장 MockIdoVerifyController 가 응답
        registry.add("idem.sample.ido.base-url", () -> "http://localhost:" + SHARED_PORT);

        // PostgreSQL
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Flyway: agency-stub 자체 V1/V2 마이그레이션 실행
        registry.add("spring.flyway.enabled",          () -> "true");
        registry.add("spring.flyway.url",              POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user",             POSTGRES::getUsername);
        registry.add("spring.flyway.password",         POSTGRES::getPassword);
        registry.add("spring.flyway.schemas",          () -> "agency_stub");
        registry.add("spring.flyway.default-schema",   () -> "agency_stub");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");

        // Redis — 사용하지 않는 더미 호스트 (RedisTemplate 빈 생성은 되지만 e2e 경로에서 호출 없음)
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "16379");

        // Kafka — listener.auto-startup=false 는 yml 에서 처리
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
    }

    @TestConfiguration
    static class MockIdoTestConfig {
        /** 호출 카운터 — 시나리오 별 검증에 사용 */
        @Bean
        AtomicInteger mockIdoVerifyInvocations() {
            return new AtomicInteger(0);
        }
    }

    @Value("${local.server.port}")
    protected int port;
}
