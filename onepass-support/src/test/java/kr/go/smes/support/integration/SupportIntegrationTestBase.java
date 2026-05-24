package kr.go.smes.support.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * onepass-support 통합 테스트 기반 클래스 — Testcontainers PostgreSQL + 실제 Flyway 마이그레이션
 *
 * <p><b>도입 배경 (위험 #2 — onepass-support 테스트 1%)</b>:
 * <ul>
 *   <li>기존 {@code OnePassSupportApplicationTests} 는 H2 PostgreSQL 호환 모드 + Flyway 비활성으로
 *       contextLoads 만 검증 — 인가 분기·답변 멱등성·티켓 큐 조회 회귀 안전망이 부재했음.</li>
 *   <li>V3 {@code create_cs_backoffice_schema.sql} 가 PostgreSQL 부분 인덱스
 *       ({@code WHERE linked_qna_id IS NOT NULL}) 를 사용하므로 H2 호환 모드로는 동등 검증 불가.</li>
 *   <li>V2 {@code COMMENT ON TABLE} 도 PostgreSQL 전용 SQL.</li>
 *   <li>JPA 의 {@code default_schema: support} 는 실제 PG 스키마 분리가 필요.</li>
 * </ul>
 *
 * <p><b>구성</b>:
 * <ul>
 *   <li>PostgreSQL 15-alpine — V1/V2/V3 Flyway 마이그레이션 자동 실행</li>
 *   <li>{@code support} 스키마는 컨테이너 초기 스크립트 (init-schema.sql) 로 사전 생성</li>
 *   <li>Singleton container — 테스트 스위트 전체에서 1회만 시작</li>
 * </ul>
 *
 * <p>ido 모듈의 {@code IntegrationTestBase} 패턴을 참조하되,
 * onepass-support 는 Redis/Kafka/WireMock 의존이 없어 PG 단일 컨테이너만 띄움.
 *
 * <p>하위 테스트는 이 클래스를 상속하여 공유 컨테이너를 자동 활용한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration-test")
@ExtendWith(SpringExtension.class)
public abstract class SupportIntegrationTestBase {

    // ── PostgreSQL 컨테이너 (V1/V2/V3 Flyway 마이그레이션 자동 실행) ──────────
    // - support 스키마는 init script 로 생성 (Flyway 가 default-schema: support 로 마이그레이션)
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("onepass_support")
                    .withUsername("onepass")
                    .withPassword("onepass")
                    .withInitScript("integration/support-init.sql")
                    .waitingFor(Wait.forListeningPort());

    static {
        // IMPORTANT:
        // Use a JVM-wide singleton container lifecycle for Spring integration tests.
        // If @Testcontainers/@Container manages lifecycle per test class, Spring may
        // reuse an ApplicationContext that still points to a now-stopped host port,
        // causing intermittent "Connection refused" failures across classes.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",                POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",           POSTGRES::getUsername);
        registry.add("spring.datasource.password",           POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",  () -> "org.postgresql.Driver");

        // JPA: schema=support 로 강제 (운영과 동일)
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "support");
        registry.add("spring.jpa.hibernate.ddl-auto",                  () -> "validate");

        // Flyway: 실제 V1/V2/V3 마이그레이션 실행 (Postgres 전용 SQL 검증)
        registry.add("spring.flyway.enabled",         () -> "true");
        registry.add("spring.flyway.schemas",         () -> "support");
        registry.add("spring.flyway.default-schema",  () -> "support");
        registry.add("spring.flyway.locations",       () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version",    () -> "0");
    }
}
