package io.github.hipstermin.idem.gate.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.gate.infrastructure.jpa.entity.AuthResultJpaEntity;
import io.github.hipstermin.idem.gate.infrastructure.jpa.repository.AuthResultJpaRepository;
import io.github.hipstermin.idem.gate.outbox.QSignOutboxRecord;
import io.github.hipstermin.idem.gate.outbox.QSignOutboxRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * gate {@code auth_result} 저장을 <b>실제 PostgreSQL</b>(Flyway 마이그레이션 적용)에서 검사한다.
 *
 * <p>1.1.4 G2-10(Keycloak 26 시험 중 발견): {@code AuthResultJpaEntity.claims} 가 {@code jsonb} 열에 String 으로 바인딩되어
 * PostgreSQL 이 "column \"claims\" is of type jsonb but expression is of type character varying" 으로 INSERT 를 거부했다(값이 null 이어도) —
 * Keycloak 중계 로그인의 인증 결과 저장이 1.0.0 부터 항상 실패했다. 단위 테스트는 H2·Mock 이라 잡지 못했다.
 * {@code @Tag("integration")} — {@code ./gradlew :idem-gate:integrationTest}(Docker 필요), 단위 테스트(test)에서는 제외.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("gate auth_result — PostgreSQL jsonb 바인딩 통합 테스트")
class AuthResultJpaPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("idem").withUsername("idem").withPassword("idem")
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=idem_gate");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");   // V1~: idem_gate 스키마·auth_result(claims JSONB)·idp_provider 시드
    }

    @Autowired AuthResultJpaRepository repository;
    @Autowired QSignOutboxRepository outboxRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("claims 가 null 이어도, JSON 문자열이어도 INSERT 된다 (jsonb 바인딩)")
    void insertWithNullAndJsonClaims() {
        repository.saveAndFlush(entity("ar-it-null", "KAKAO_OIDC", null));
        repository.saveAndFlush(entity("ar-it-json", "KAKAO_OIDC", "{\"masked\":true,\"name\":\"홍*동\"}"));

        assertThat(jdbcTemplate.queryForObject("SELECT claims FROM idem_gate.auth_result WHERE auth_result_id = 'ar-it-null'", String.class)).isNull();
        String json = jdbcTemplate.queryForObject("SELECT claims::text FROM idem_gate.auth_result WHERE auth_result_id = 'ar-it-json'", String.class);
        assertThat(json).contains("\"masked\"").contains("true").contains("홍*동");
        assertThat(jdbcTemplate.queryForObject("SELECT pg_typeof(claims)::text FROM idem_gate.auth_result WHERE auth_result_id = 'ar-it-json'", String.class)).isEqualTo("jsonb");
        assertThat(repository.findById("ar-it-json")).get().extracting(AuthResultJpaEntity::getClaims).asString().contains("\"masked\"");
    }

    @Test
    @DisplayName("V6: Keycloak 중계가 만드는 provider_code(KEYCLOAK_OIDC·PASS_OIDC·GPKI_OIDC)가 idp_provider 에 있어 FK 를 지난다")
    void keycloakRelayProviderCodesExist() {
        assertThat(jdbcTemplate.queryForList("SELECT provider_code FROM idem_gate.idp_provider WHERE active ORDER BY 1", String.class))
                .contains("KAKAO_OIDC", "NAVER_OIDC", "KEYCLOAK_OIDC", "PASS_OIDC", "GPKI_OIDC");
        repository.saveAndFlush(entity("ar-it-kc", "KEYCLOAK_OIDC", null));   // 종전: fk_auth_result_provider 위반
        assertThat(repository.findById("ar-it-kc")).isPresent();
    }

    @Test
    @DisplayName("outbox.payload(jsonb) 도 같은 바인딩으로 INSERT 된다 — 인증 결과와 한 트랜잭션이라 여기서 깨지면 auth_result 도 롤백됐다")
    void outboxPayloadIsJsonb() {
        outboxRepository.saveAndFlush(QSignOutboxRecord.builder()
                .eventId("ev-it-1").eventType("AUTH_COMPLETED").partitionKey("hash-ev-it-1").aggregateId("ar-it-ev-1")
                .eventVersion(1L).payload("{\"eventType\":\"AUTH_COMPLETED\",\"authLevel\":\"L1\"}").topic("idem.gate.auth.events").build());
        assertThat(jdbcTemplate.queryForObject("SELECT payload->>'eventType' FROM idem_gate.outbox WHERE event_id = 'ev-it-1'", String.class))
                .isEqualTo("AUTH_COMPLETED");
    }

    private static AuthResultJpaEntity entity(String id, String providerCode, String claims) {
        return AuthResultJpaEntity.builder()
                .authResultId(id)
                .correlationId("cid-" + id)
                .authLevel("L1")
                .providerCode(providerCode)          // idp_provider 시드 (FK): V1 KAKAO_OIDC…, V6 KEYCLOAK_OIDC…
                .providerTxId("kc-" + id)
                .identifierHash("hash-" + id)
                .verificationResult("SUCCESS")
                .requestedAt(Instant.now())
                .authenticatedAt(Instant.now())
                .claims(claims)
                .build();
    }
}
