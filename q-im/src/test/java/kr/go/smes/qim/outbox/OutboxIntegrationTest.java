package kr.go.smes.qim.outbox;

import kr.go.smes.qim.infrastructure.jpa.entity.OutboxJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.OutboxJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Outbox Repository 통합 테스트 — Testcontainers (MariaDB)
 *
 * <p>실제 MariaDB 컨테이너 기동 후 JPA/Flyway 마이그레이션을 그대로 적용하여
 * PENDING → PUBLISHED / FAILED → PENDING 전환 사이클을 검증한다.
 *
 * <p>검증 항목:
 * <ul>
 *   <li>save() → findPending() 기본 CRUD 사이클</li>
 *   <li>markPublished() → status=PUBLISHED, published_at 설정</li>
 *   <li>markFailed() → status=FAILED, retryCount+1, errorMessage 저장</li>
 *   <li>markPending() → FAILED→PENDING 복구, errorMessage 초기화</li>
 *   <li>findRetryable() → retryCount < maxRetry 인 FAILED만 반환</li>
 *   <li>멱등성 — 동일 eventId 중복 save 거부</li>
 *   <li>선입선출 — findPending 결과가 created_at ASC 정렬</li>
 * </ul>
 *
 * <p><b>Testcontainers 전략</b>:
 * {@code @Container} 정적 필드로 선언하여 클래스 전체 라이프사이클 공유(reuse).
 * {@link DynamicPropertySource}로 DataSource URL 동적 주입.
 */
/**
 * Testcontainers 기반 통합 테스트 — Docker 데몬 필요.
 *
 * <p>CI/로컬 Docker 환경에서 실행 가능. Docker 없는 샌드박스에서는
 * {@code DOCKER_UNAVAILABLE=true} 환경변수 설정으로 자동 스킵.
 *
 * <pre>
 *   # CI에서 통합 테스트 실행:
 *   ./gradlew :q-im:test
 *
 *   # Docker 없는 환경에서 단위 테스트만 실행:
 *   DOCKER_UNAVAILABLE=true ./gradlew :q-im:test
 * </pre>
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxRepositoryImpl.class)
@DisabledIfEnvironmentVariable(named = "DOCKER_UNAVAILABLE", matches = "true")
class OutboxIntegrationTest {

    @Container
    static final MariaDBContainer<?> MARIA_DB =
            new MariaDBContainer<>("mariadb:11.2")
                    .withDatabaseName("qim")
                    .withUsername("qim")
                    .withPassword("qim_test_pw")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      MARIA_DB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIA_DB::getUsername);
        registry.add("spring.datasource.password", MARIA_DB::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.MariaDBDialect");
    }

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxJpaRepository jpaRepository;

    @BeforeEach
    void cleanUp() {
        jpaRepository.deleteAll();
    }

    // ════════════════════════════════════════════════════════════════════════
    // save() + findPending()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("save() + findPending()")
    class SaveAndFindPending {

        @Test
        @DisplayName("저장 후 findPending()에서 PENDING 레코드가 조회되어야 한다")
        void saveAndFindPending() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);

            List<OutboxRecord> pending = outboxRepository.findPending(10);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getEventId()).isEqualTo(record.getEventId());
            assertThat(pending.get(0).getStatus()).isEqualTo(OutboxRecord.OutboxStatus.PENDING);
        }

        @Test
        @DisplayName("PUBLISHED 레코드는 findPending()에서 반환되지 않는다")
        void publishedNotInPending() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);
            outboxRepository.markPublished(record.getEventId());

            List<OutboxRecord> pending = outboxRepository.findPending(10);
            assertThat(pending).isEmpty();
        }

        @Test
        @DisplayName("FAILED 레코드는 findPending()에서 반환되지 않는다")
        void failedNotInPending() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);
            outboxRepository.markFailed(record.getEventId(), "Kafka timeout");

            List<OutboxRecord> pending = outboxRepository.findPending(10);
            assertThat(pending).isEmpty();
        }

        @Test
        @DisplayName("findPending limit 적용 — 2개 저장 후 limit=1이면 1개만 반환")
        void findPendingRespectsLimit() {
            outboxRepository.save(buildRecord("USER_REGISTERED", 1L));
            outboxRepository.save(buildRecord("USER_REGISTERED", 2L));

            List<OutboxRecord> pending = outboxRepository.findPending(1);
            assertThat(pending).hasSize(1);
        }

        @Test
        @DisplayName("선입선출 — created_at ASC 정렬 보장")
        void findPendingOrderedByCreatedAt() throws InterruptedException {
            OutboxRecord first  = buildRecord("USER_REGISTERED",  1L);
            outboxRepository.save(first);
            Thread.sleep(10); // created_at 차이 보장
            OutboxRecord second = buildRecord("MAPPING_ADDED", 2L);
            outboxRepository.save(second);

            List<OutboxRecord> pending = outboxRepository.findPending(10);
            assertThat(pending.get(0).getEventId()).isEqualTo(first.getEventId());
            assertThat(pending.get(1).getEventId()).isEqualTo(second.getEventId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // markPublished()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markPublished()")
    class MarkPublished {

        @Test
        @DisplayName("markPublished() → status=PUBLISHED, published_at 설정")
        void markPublishedSetsStatus() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);

            outboxRepository.markPublished(record.getEventId());

            OutboxJpaEntity entity = jpaRepository.findById(record.getEventId()).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo("PUBLISHED");
            assertThat(entity.getPublishedAt()).isNotNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // markFailed()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markFailed()")
    class MarkFailed {

        @Test
        @DisplayName("markFailed() → status=FAILED, retryCount+1, errorMessage 저장")
        void markFailedIncrementsRetryCount() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);

            outboxRepository.markFailed(record.getEventId(), "Connection refused");

            OutboxJpaEntity entity = jpaRepository.findById(record.getEventId()).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo("FAILED");
            assertThat(entity.getRetryCount()).isEqualTo((short) 1);
            assertThat(entity.getErrorMessage()).isEqualTo("Connection refused");
        }

        @Test
        @DisplayName("연속 2회 markFailed() → retryCount=2")
        void twoFailsIncrementsTwice() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);

            outboxRepository.markFailed(record.getEventId(), "err-1");
            outboxRepository.markFailed(record.getEventId(), "err-2");

            OutboxJpaEntity entity = jpaRepository.findById(record.getEventId()).orElseThrow();
            assertThat(entity.getRetryCount()).isEqualTo((short) 2);
            assertThat(entity.getErrorMessage()).isEqualTo("err-2");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // markPending() — FAILED → PENDING 복구
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("markPending() — FAILED → PENDING 복구")
    class MarkPending {

        @Test
        @DisplayName("markPending() → status=PENDING, errorMessage=null")
        void markPendingRestoresStatus() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);
            outboxRepository.markFailed(record.getEventId(), "Kafka down");

            outboxRepository.markPending(record.getEventId());

            OutboxJpaEntity entity = jpaRepository.findById(record.getEventId()).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo("PENDING");
            assertThat(entity.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("PENDING 복구 후 findPending()에서 다시 조회 가능")
        void pendingRestorationAppearsInFindPending() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);
            outboxRepository.markFailed(record.getEventId(), "error");
            outboxRepository.markPending(record.getEventId());

            List<OutboxRecord> pending = outboxRepository.findPending(10);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).getEventId()).isEqualTo(record.getEventId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // findRetryable()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findRetryable()")
    class FindRetryable {

        @Test
        @DisplayName("retryCount < maxRetry 인 FAILED 레코드만 반환")
        void findRetryableReturnsEligibleFailed() {
            // retryCount=1 (maxRetry=5 미만) → 재시도 대상
            OutboxRecord eligible = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(eligible);
            outboxRepository.markFailed(eligible.getEventId(), "err");

            // retryCount=5 (maxRetry=5 도달) → 영구 FAILED
            OutboxRecord exhausted = buildRecord("MAPPING_ADDED", 2L);
            outboxRepository.save(exhausted);
            for (int i = 0; i < 5; i++) {
                outboxRepository.markFailed(exhausted.getEventId(), "err-" + i);
            }

            List<OutboxRecord> retryable = outboxRepository.findRetryable((short) 5, 10);

            assertThat(retryable).hasSize(1);
            assertThat(retryable.get(0).getEventId()).isEqualTo(eligible.getEventId());
        }

        @Test
        @DisplayName("PENDING / PUBLISHED 레코드는 findRetryable()에서 반환되지 않는다")
        void pendingAndPublishedNotRetryable() {
            OutboxRecord pending   = buildRecord("USER_REGISTERED", 1L);
            OutboxRecord published = buildRecord("MAPPING_ADDED", 2L);
            outboxRepository.save(pending);
            outboxRepository.save(published);
            outboxRepository.markPublished(published.getEventId());

            List<OutboxRecord> retryable = outboxRepository.findRetryable((short) 5, 10);
            assertThat(retryable).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 멱등성 — 중복 eventId 거부
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("멱등성 — 중복 eventId 거부")
    class Idempotency {

        @Test
        @DisplayName("동일 eventId로 두 번 save() → 예외 발생 (PK 중복)")
        void duplicateEventIdThrowsException() {
            OutboxRecord record = buildRecord("USER_REGISTERED", 1L);
            outboxRepository.save(record);

            assertThatThrownBy(() -> outboxRepository.save(record))
                    .isInstanceOf(Exception.class)
                    .as("동일 eventId 중복 저장은 PK 위반으로 거부되어야 함");
        }
    }

    // ── private 헬퍼 ──────────────────────────────────────────────────────────

    private OutboxRecord buildRecord(String eventType, Long version) {
        return OutboxRecord.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .partitionKey(UUID.randomUUID().toString())
                .eventVersion(version)
                .payload("{\"qimUserId\":\"test\",\"eventType\":\"" + eventType + "\"}")
                .status(OutboxRecord.OutboxStatus.PENDING)
                .retryCount((short) 0)
                .createdAt(Instant.now())
                .build();
    }
}
