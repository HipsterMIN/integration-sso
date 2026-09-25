package io.github.hipstermin.idem.registry.kr.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.registry.crypto.PiiMaskingService;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.UserProfileJpaRepository;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberConversionRequest;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberConversionService;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberConversionServiceImpl;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberJpaRepository;
import io.github.hipstermin.idem.registry.kr.biz.BizMemberResult;
import io.github.hipstermin.idem.registry.kr.config.KrRegistryEditionConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * KR 에디션 — 기업회원 전환 E2E (Testcontainers PostgreSQL). 코어 {@code QimLifecycleIntegrationTest} 의 S9-1~S9-5
 * (S8-a 에서 이동). 코어 V1 + KR V1000_1 마이그레이션이 함께 적용되는지도 여기서 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        BizMemberConversionServiceImpl.class,
        PiiMaskingService.class,
        KrRegistryEditionConfig.class,
        com.fasterxml.jackson.databind.ObjectMapper.class
})
@DisabledIfEnvironmentVariable(named = "DOCKER_UNAVAILABLE", matches = "true")
@DisplayName("KR 에디션 — 기업회원 전환 E2E (Testcontainers PostgreSQL)")
class KrBizMemberLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("idem")
                    .withUsername("idem")
                    .withPassword("idem")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      () -> POSTGRES.getJdbcUrl() + "&currentSchema=idem_registry");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired QimUserJpaRepository       userRepository;
    @Autowired UserProfileJpaRepository   profileRepository;
    @Autowired BizMemberJpaRepository     bizMemberRepository;
    @Autowired BizMemberConversionService bizMemberConversionService;
    @Autowired JdbcTemplate               jdbcTemplate;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private static final String CORR = "kr-it-corr-001";

    private String createActiveUser(String qimUserId) {
        QimUserJpaEntity user = new QimUserJpaEntity();
        user.setQimUserId(qimUserId);
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEventVersion(1L);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.saveAndFlush(user);
        return qimUserId;
    }

    @BeforeEach
    void cleanUp() {
        bizMemberRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("S9-0: 코어 V1 뒤에 KR V1000.1 이 적용된다 (flyway_schema_history)")
    void s9_0_krMigrationApplied() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).contains("1", "1000.1");
        assertThat(versions.indexOf("1000.1")).isGreaterThan(versions.indexOf("1"));
    }

    @Test
    @DisplayName("S9-1: KR 마이그레이션 후 biz_member 테이블 존재 확인")
    void s9_1_bizMemberTableExists() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = current_schema() AND TABLE_NAME = 'biz_member'",
                String.class);

        assertThat(tables)
                .as("KR V1000_1 마이그레이션 후 biz_member 테이블이 존재해야 합니다.")
                .containsExactly("biz_member");
    }

    @Test
    @DisplayName("S9-2: 기업회원 전환 → biz_member INSERT → 조회")
    void s9_2_bizMemberConversionFullFlow() {
        // 준비
        String qimUserId = createActiveUser("user-s9-001");

        BizMemberConversionRequest req = BizMemberConversionRequest.builder()
                .qimUserId(qimUserId)
                .bizRegNo("123-45-67890")          // 하이픈 포함 형식 → 정규화
                .companyName("주식회사 테스트")
                .repName("홍길동")
                .bizType("소프트웨어 개발")
                .build();

        // 기업회원 전환 실행
        BizMemberResult result = bizMemberConversionService.convert(req, CORR);

        // 반환값 검증
        assertThat(result.getQimUserId()).isEqualTo(qimUserId);
        assertThat(result.getBizRegNo()).isEqualTo("1234567890"); // 정규화된 10자리
        assertThat(result.getCompanyName()).isEqualTo("주식회사 테스트");
        assertThat(result.getBizStatus()).isEqualTo("ACTIVE");
        assertThat(result.getConvertedAt()).isNotNull();

        // DB 조회 검증
        BizMemberResult found = bizMemberConversionService.findByQimUserId(qimUserId, CORR);
        assertThat(found.getBizRegNo()).isEqualTo("1234567890");
        assertThat(found.getCompanyName()).isEqualTo("주식회사 테스트");

        // biz_member 테이블 직접 확인 — save() 가 persist(미flush) 이고 findByQimUserId 는 1차 캐시(findById)라
        // JDBC 로 보기 전에 명시적 flush 가 필요하다 (운영은 트랜잭션 커밋 시 flush)
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM biz_member WHERE qim_user_id = ?",
                Integer.class, qimUserId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("S9-3: 동일 qimUserId 중복 전환 시도 → IM_BIZ_REG_DUPLICATE")
    void s9_3_duplicateConversionBlocked() {
        String qimUserId = createActiveUser("user-s9-002");

        BizMemberConversionRequest firstReq = BizMemberConversionRequest.builder()
                .qimUserId(qimUserId)
                .bizRegNo("9876543210")
                .companyName("주식회사 첫번째")
                .build();
        bizMemberConversionService.convert(firstReq, CORR);

        // 같은 qimUserId로 재전환 시도
        BizMemberConversionRequest secondReq = BizMemberConversionRequest.builder()
                .qimUserId(qimUserId)
                .bizRegNo("1111111111")
                .companyName("주식회사 두번째")
                .build();
        assertThatThrownBy(() -> bizMemberConversionService.convert(secondReq, CORR))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IM_BIZ_REG_DUPLICATE);
    }

    @Test
    @DisplayName("S9-4: 사업자등록번호 중복 — 다른 qimUserId가 이미 등록한 번호 → IM_BIZ_REG_DUPLICATE")
    void s9_4_duplicateBizRegNoBlocked() {
        String user1 = createActiveUser("user-s9-003");
        String user2 = createActiveUser("user-s9-004");

        // user1이 먼저 등록
        BizMemberConversionRequest req1 = BizMemberConversionRequest.builder()
                .qimUserId(user1)
                .bizRegNo("5555555555")
                .companyName("주식회사 A")
                .build();
        bizMemberConversionService.convert(req1, CORR);

        // user2가 동일 사업자등록번호로 시도 → 차단
        BizMemberConversionRequest req2 = BizMemberConversionRequest.builder()
                .qimUserId(user2)
                .bizRegNo("5555555555")
                .companyName("주식회사 B")
                .build();
        assertThatThrownBy(() -> bizMemberConversionService.convert(req2, CORR))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IM_BIZ_REG_DUPLICATE);
    }

    @Test
    @DisplayName("S9-5: 사업자등록번호 형식 오류 → IM_BIZ_REG_INVALID")
    void s9_5_invalidBizRegNoFormat() {
        String qimUserId = createActiveUser("user-s9-005");

        BizMemberConversionRequest invalidReq = BizMemberConversionRequest.builder()
                .qimUserId(qimUserId)
                .bizRegNo("INVALID-FORMAT")
                .companyName("주식회사 오류")
                .build();

        assertThatThrownBy(() -> bizMemberConversionService.convert(invalidReq, CORR))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IM_BIZ_REG_INVALID);
    }

}
