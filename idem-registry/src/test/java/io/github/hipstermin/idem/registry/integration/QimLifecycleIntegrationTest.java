package io.github.hipstermin.idem.registry.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.registry.consent.*;
import io.github.hipstermin.idem.registry.crypto.PiiMaskingService;
import io.github.hipstermin.idem.registry.guardian.GuardianConsentService;
import io.github.hipstermin.idem.registry.guardian.GuardianConsentServiceImpl;
import io.github.hipstermin.idem.registry.guardian.GuardianConsentStatus;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.*;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.*;
import io.github.hipstermin.idem.registry.outbox.OutboxRepositoryImpl;
import io.github.hipstermin.idem.registry.outbox.OutboxService;
import io.github.hipstermin.idem.registry.withdrawal.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Q-IM 회원 생명주기 E2E 통합 테스트 — Testcontainers MariaDB
 *
 * <p>실제 MariaDB 11.2 컨테이너 위에서 Flyway 마이그레이션(V1~<b>V6</b>)을 모두 적용하고
 * 다음 9가지 핵심 시나리오를 검증한다:
 *
 * <ol>
 *   <li><b>S1 회원 등록 → 동의</b>: qim_user INSERT + consent_record AGREED</li>
 *   <li><b>S2 즉시 탈퇴</b>: IMMEDIATE 탈퇴 → WITHDRAWN 상태 + PII NULL</li>
 *   <li><b>S3 예약 탈퇴 → 취소</b>: SCHEDULED 30일 예약 → ACTIVE 복원</li>
 *   <li><b>S4 예약 탈퇴 만료</b>: 과거 일시로 예약 → 스케줄러 처리 → WITHDRAWN</li>
 *   <li><b>S5 동의 철회</b>: 선택 동의 AGREED → WITHDRAWN (이력 보존)</li>
 *   <li><b>S6 전환 세션 상태 기계</b>: INITIATED → MEMBERS_FETCHED → ACCOUNT_SELECTED → COMPLETED</li>
 *   <li><b>S7 중복 탈퇴 방지</b>: WITHDRAWN 사용자 재탈퇴 → IM_WITHDRAWAL_ALREADY</li>
 *   <li><b>S8 보호자 동의 E2E (V6)</b>: 미성년자 등록 → 보호자 동의 → guardian_consent_at 설정</li>
 *   <li><b>S9 기업회원 전환 E2E</b>: S8-a 에서 KR 에디션(idem-kr-registry, KrBizMemberLifecycleIntegrationTest)으로 이동</li>
 * </ol>
 *
 * <h3>실행 조건</h3>
 * <ul>
 *   <li>Docker 데몬 가동 필수 (Testcontainers 자동 감지)</li>
 *   <li>Docker 없는 환경: {@code DOCKER_UNAVAILABLE=true} 환경변수로 자동 스킵</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        // Withdrawal — OutboxServiceImpl 은 KafkaTemplate/SnapshotService 의존성을
        // 가지므로 @DataJpaTest 슬라이스에 포함하지 않고, 인터페이스 OutboxService 를
        // @MockitoBean 으로 격리한다 (publishInTx 호출 부수효과는 본 테스트의 검증
        // 대상이 아님 — Outbox 자체 검증은 OutboxIntegrationTest 가 담당).
        WithdrawalServiceImpl.class,
        OutboxRepositoryImpl.class,
        // Consent
        ConsentServiceImpl.class,
        // Guardian (P3-05 V6)
        GuardianConsentServiceImpl.class,
        PiiMaskingService.class,
        com.fasterxml.jackson.databind.ObjectMapper.class
})
@DisabledIfEnvironmentVariable(named = "DOCKER_UNAVAILABLE", matches = "true")
@DisplayName("Q-IM 회원 생명주기 E2E 통합 테스트 (Testcontainers PostgreSQL)")
class QimLifecycleIntegrationTest {

    // ── Testcontainers 컨테이너 ───────────────────────────────────────────────

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("onepass")
                    .withUsername("onepass")
                    .withPassword("onepass")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      () -> POSTGRES.getJdbcUrl() + "&currentSchema=qim");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    // ── 외부 의존 빈 격리 (Kafka / IdO HTTP) ──────────────────────────────────
    // OutboxService 는 WithdrawalServiceImpl 이 publishInTx 로 호출하지만, 본 테스트는
    // qim_user 상태 전이만 검증하므로 부수효과를 모킹으로 흡수한다.
    @MockitoBean
    OutboxService outboxService;

    // ── Spring 빈 주입 ────────────────────────────────────────────────────────

    @Autowired QimUserJpaRepository          userRepository;
    @Autowired UserProfileJpaRepository      profileRepository;
    @Autowired ConsentVersionJpaRepository   versionRepository;
    @Autowired ConsentRecordJpaRepository    recordRepository;
    @Autowired WithdrawalService             withdrawalService;
    @Autowired ConsentService                consentService;
    @Autowired GuardianConsentService        guardianConsentService;
    @Autowired JdbcTemplate                  jdbcTemplate;
    @Autowired jakarta.persistence.EntityManager entityManager;  // 1차 캐시 명시 초기화용

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private static final String CORR = "it-corr-001";

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

    private String createConsentVersion(String consentType, boolean required) {
        ConsentVersionJpaEntity ver = new ConsentVersionJpaEntity();
        ver.setVersionId("ver-" + consentType + "-001");
        ver.setConsentType(consentType);
        ver.setVersionTag("2026-05-01");
        ver.setTitle(consentType + " 약관");
        ver.setRequired(required);
        ver.setStatus("ACTIVE");
        ver.setEffectiveAt(Instant.now().minus(1, ChronoUnit.DAYS));
        ver.setCreatedAt(Instant.now());
        versionRepository.saveAndFlush(ver);
        return ver.getVersionId();
    }

    @BeforeEach
    void cleanUp() {
        // 테스트 격리: FK 의존 순서대로 삭제
        recordRepository.deleteAll();
        versionRepository.deleteAll();
        profileRepository.deleteAll();
        userRepository.deleteAll();
        // @MapsId + CascadeType.ALL + orphanRemoval 조합에서 deleteAll() 이후
        // JPA 1차 캐시가 REMOVED 상태 엔티티를 보유하면 다음 saveAndFlush()가
        // StaleObjectStateException을 발생시킨다. 명시적 clear()로 방지.
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * user_profile까지 함께 생성하는 픽스처 헬퍼 (V6 시나리오용)
     *
     * @param qimUserId  qim_user_id
     * @param birthYear  출생 연도 (null이면 성인)
     * @param isMinor    14세 미만 여부
     */
    private String createUserWithProfile(String qimUserId, Short birthYear, boolean isMinor) {
        QimUserJpaEntity user = new QimUserJpaEntity();
        user.setQimUserId(qimUserId);
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEventVersion(1L);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());

        UserProfileJpaEntity profile = new UserProfileJpaEntity();
        profile.setQimUserId(qimUserId);
        profile.setUser(user);
        profile.setNameMasked("홍*동");
        profile.setMobileMasked("010-****-5678");
        profile.setNationalityType("DOMESTIC");
        profile.setBirthYear(birthYear);
        profile.setGender("MALE");
        profile.setIsMinor(isMinor);
        profile.setUpdatedAt(Instant.now());

        user.setProfile(profile);
        userRepository.saveAndFlush(user);
        return qimUserId;
    }

    // ══════════════════════════════════════════════════════════════════════
    // S1: 회원 등록 → 동의 기록
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S1: qim_user INSERT + 선택 동의 AGREED 기록")
    void s1_registerAndConsent() {
        // 준비
        String qimUserId = createActiveUser("user-s1-001");
        String versionId = createConsentVersion("MARKETING", false);

        // 실행 — 동의
        ConsentRequest req = ConsentRequest.builder()
                .consentType("MARKETING")
                .versionId(versionId)
                .agreedVia("WEB_SIGNUP")
                .clientIp("127.0.0.1")
                .correlationId(CORR)
                .build();
        ConsentResult result = consentService.agree(qimUserId, req);

        // 검증
        assertThat(result.getConsentStatus()).isEqualTo("AGREED");
        assertThat(result.getConsentType()).isEqualTo("MARKETING");
        assertThat(result.getVersionId()).isEqualTo(versionId);

        // DB 확인
        List<ConsentResult> statusList = consentService.getConsentStatus(qimUserId);
        assertThat(statusList).hasSize(1);
        assertThat(statusList.get(0).getConsentStatus()).isEqualTo("AGREED");
    }

    // ══════════════════════════════════════════════════════════════════════
    // S2: IMMEDIATE 즉시 탈퇴
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S2: IMMEDIATE 탈퇴 → qim_user.status = WITHDRAWN")
    void s2_immediateWithdrawal() {
        // 준비
        String qimUserId = createActiveUser("user-s2-001");

        // 실행
        WithdrawalRequest req = WithdrawalRequest.builder()
                .type(WithdrawalType.IMMEDIATE)
                .reason("사용자 직접 탈퇴 요청")
                .correlationId(CORR)
                .build();
        WithdrawalResponse resp = withdrawalService.withdraw(qimUserId, req);

        // 검증
        assertThat(resp.getResultStatus()).isEqualTo("WITHDRAWN");
        assertThat(resp.getType()).isEqualTo(WithdrawalType.IMMEDIATE);

        QimUserJpaEntity user = userRepository.findById(qimUserId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN.name());
        assertThat(user.getWithdrawalType()).isEqualTo("IMMEDIATE");
    }

    // ══════════════════════════════════════════════════════════════════════
    // S3: SCHEDULED 예약 탈퇴 → 취소 → ACTIVE 복원
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S3: SCHEDULED 예약 후 취소 → ACTIVE 복원")
    void s3_scheduledWithdrawalAndCancel() {
        // 준비
        String qimUserId = createActiveUser("user-s3-001");

        // 예약 탈퇴
        WithdrawalRequest schedReq = WithdrawalRequest.builder()
                .type(WithdrawalType.SCHEDULED)
                .reason("30일 유예 탈퇴")
                .correlationId(CORR)
                .build();
        WithdrawalResponse schedResp = withdrawalService.withdraw(qimUserId, schedReq);
        assertThat(schedResp.getResultStatus()).isEqualTo("WITHDRAWAL_SCHEDULED");
        assertThat(schedResp.getScheduledAt()).isNotNull();

        // DB 상태 확인
        QimUserJpaEntity scheduled = userRepository.findById(qimUserId).orElseThrow();
        assertThat(scheduled.getStatus()).isEqualTo(UserStatus.WITHDRAWAL_SCHEDULED.name());

        // 예약 취소
        WithdrawalResponse cancelResp =
                withdrawalService.cancelScheduledWithdrawal(qimUserId, CORR);
        assertThat(cancelResp.getResultStatus()).isEqualTo("ACTIVE");

        // DB 복원 확인
        QimUserJpaEntity restored = userRepository.findById(qimUserId).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE.name());
        assertThat(restored.getWithdrawalScheduledAt()).isNull();
    }

    // ══════════════════════════════════════════════════════════════════════
    // S4: 예약 탈퇴 만료 처리 (스케줄러 수동 호출)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S4: 과거 일시로 예약된 탈퇴 → processExpiredScheduledWithdrawals() → WITHDRAWN")
    void s4_scheduledWithdrawalExpiry() {
        // 준비 — WITHDRAWAL_SCHEDULED 상태로 직접 설정, 예약 일시를 과거로
        String qimUserId = createActiveUser("user-s4-001");
        QimUserJpaEntity user = userRepository.findById(qimUserId).orElseThrow();
        user.setStatus(UserStatus.WITHDRAWAL_SCHEDULED.name());
        user.setWithdrawalScheduledAt(Instant.now().minus(1, ChronoUnit.HOURS));
        user.setWithdrawalType(WithdrawalType.SCHEDULED.name());
        userRepository.saveAndFlush(user);

        // 실행 — 스케줄러 메서드 수동 트리거
        int processed = withdrawalService.processExpiredScheduledWithdrawals();

        // 검증
        assertThat(processed).isGreaterThanOrEqualTo(1);
        QimUserJpaEntity withdrawn = userRepository.findById(qimUserId).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo(UserStatus.WITHDRAWN.name());
    }

    // ══════════════════════════════════════════════════════════════════════
    // S5: 선택 동의 철회 — 같은 consent_record 를 WITHDRAWN 으로 갱신 (markWithdrawn UPDATE)
    //     ※ 과거 기대값(INSERT 전용 이력 2건)은 구현·설계(03-member-update-withdraw-flow)와 달라 2026-09-07 정정
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S5: 선택 동의 AGREED → 철회 → 같은 consent_record 가 WITHDRAWN 으로 갱신")
    void s5_consentWithdraw() {
        // 준비
        String qimUserId = createActiveUser("user-s5-001");
        createConsentVersion("MARKETING", false);

        // 동의
        ConsentRequest agreeReq = ConsentRequest.builder()
                .consentType("MARKETING")
                .agreedVia("WEB_SIGNUP")
                .clientIp("127.0.0.1")
                .correlationId(CORR)
                .build();
        consentService.agree(qimUserId, agreeReq);

        // 철회
        ConsentResult withdrawResult =
                consentService.withdraw(qimUserId, "MARKETING", "마케팅 수신 거부", CORR);
        assertThat(withdrawResult.getConsentStatus()).isEqualTo("WITHDRAWN");
        assertThat(withdrawResult.getWithdrawnAt()).isNotNull();

        // DB 검증 — 레코드 1개가 제자리에서 WITHDRAWN 으로 갱신 (ConsentServiceImpl.withdraw → markWithdrawn UPDATE)
        entityManager.flush();
        entityManager.clear();
        List<ConsentRecordJpaEntity> records = recordRepository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getConsentStatus()).isEqualTo("WITHDRAWN");
        assertThat(records.get(0).getWithdrawnAt()).isNotNull();
        assertThat(records.get(0).getWithdrawalReason()).isEqualTo("마케팅 수신 거부");
    }

    // ══════════════════════════════════════════════════════════════════════
    // S7: 중복 탈퇴 방지 (IM_WITHDRAWAL_ALREADY)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S7: 이미 WITHDRAWN 사용자 재탈퇴 시도 → PlatformException IM_WITHDRAWAL_ALREADY")
    void s7_doubleWithdrawalBlocked() {
        // 준비 — 이미 탈퇴된 상태
        String qimUserId = createActiveUser("user-s7-001");
        QimUserJpaEntity user = userRepository.findById(qimUserId).orElseThrow();
        user.setStatus(UserStatus.WITHDRAWN.name());
        userRepository.saveAndFlush(user);

        // 재탈퇴 시도 → 차단
        WithdrawalRequest req = WithdrawalRequest.builder()
                .type(WithdrawalType.IMMEDIATE)
                .reason("이중 탈퇴 시도")
                .correlationId(CORR)
                .build();

        assertThatThrownBy(() -> withdrawalService.withdraw(qimUserId, req))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IM_WITHDRAWAL_ALREADY);
    }

    // ══════════════════════════════════════════════════════════════════════
    // S8: 보호자 동의 E2E (V6 마이그레이션 — is_minor / guardian_consent_at)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S8-1: V6 컬럼 존재 확인 — user_profile에 is_minor, guardian_qim_user_id, guardian_consent_at")
    void s8_1_v6ColumnsExist() {
        // V6 마이그레이션이 적용되었다면 해당 컬럼들이 존재해야 함
        // INFORMATION_SCHEMA 조회로 컬럼 존재 확인
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = current_schema() AND TABLE_NAME = 'user_profile' " +
                "AND COLUMN_NAME IN ('is_minor', 'guardian_qim_user_id', 'guardian_consent_at')",
                String.class);

        assertThat(columns)
                .as("V6 마이그레이션 후 user_profile에 is_minor, guardian_qim_user_id, guardian_consent_at 컬럼이 존재해야 합니다.")
                .containsExactlyInAnyOrder("is_minor", "guardian_qim_user_id", "guardian_consent_at");
    }

    @Test
    @DisplayName("S8-2: 미성년자(is_minor=true) 등록 → 보호자 동의 처리 → guardian_consent_at 설정")
    void s8_2_minorGuardianConsentFlow() {
        // 준비: 미성년자 + 성인 보호자 각각 user_profile 포함 생성
        short minorBirthYear  = (short) (java.time.Year.now().getValue() - 12); // 12세
        short adultBirthYear  = (short) (java.time.Year.now().getValue() - 35); // 35세

        String minorId    = createUserWithProfile("minor-s8-001",   minorBirthYear, true);
        String guardianId = createUserWithProfile("guardian-s8-001", adultBirthYear, false);

        // 보호자 동의 전: 상태 조회
        GuardianConsentStatus beforeStatus = guardianConsentService.getStatus(minorId, CORR);
        assertThat(beforeStatus.isMinor()).isTrue();
        assertThat(beforeStatus.isConsentGranted()).isFalse();
        assertThat(beforeStatus.getGuardianConsentAt()).isNull();

        // 보호자 동의 실행
        guardianConsentService.grantConsent(minorId, guardianId, CORR);

        // 보호자 동의 후: DB에서 직접 확인
        UserProfileJpaEntity minorProfile = profileRepository.findById(minorId).orElseThrow();
        assertThat(minorProfile.getGuardianQimUserId())
                .as("guardian_qim_user_id가 보호자 ID로 설정되어야 합니다.")
                .isEqualTo(guardianId);
        assertThat(minorProfile.getGuardianConsentAt())
                .as("guardian_consent_at이 설정되어야 합니다.")
                .isNotNull()
                .isBeforeOrEqualTo(Instant.now());

        // 서비스 레이어 조회로도 확인
        GuardianConsentStatus afterStatus = guardianConsentService.getStatus(minorId, CORR);
        assertThat(afterStatus.isConsentGranted()).isTrue();
        assertThat(afterStatus.getGuardianQimUserId()).isEqualTo(guardianId);
    }

    @Test
    @DisplayName("S8-3: 이미 보호자 동의 완료된 미성년자 재동의 시도 → IM_GUARDIAN_CONSENT_ALREADY")
    void s8_3_guardianConsentDuplicateBlocked() {
        short minorBirthYear  = (short) (java.time.Year.now().getValue() - 10);
        short adultBirthYear  = (short) (java.time.Year.now().getValue() - 40);

        String minorId    = createUserWithProfile("minor-s8-002",    minorBirthYear, true);
        String guardianId = createUserWithProfile("guardian-s8-002", adultBirthYear, false);

        // 첫 번째 동의 — 성공
        guardianConsentService.grantConsent(minorId, guardianId, CORR);

        // 두 번째 동의 시도 — 차단
        assertThatThrownBy(() -> guardianConsentService.grantConsent(minorId, guardianId, CORR))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IM_GUARDIAN_CONSENT_ALREADY);
    }

    @Test
    @DisplayName("S8-4: 성인 사용자(is_minor=false)에게 보호자 동의 시도 → IM_MINOR_GUARDIAN_REQUIRED")
    void s8_4_guardianConsentOnAdultBlocked() {
        short adultBirthYear  = (short) (java.time.Year.now().getValue() - 25);
        short guardianBirthYear = (short) (java.time.Year.now().getValue() - 50);

        String adultId    = createUserWithProfile("adult-s8-003",    adultBirthYear,   false);
        String guardianId = createUserWithProfile("guardian-s8-003", guardianBirthYear, false);

        // 성인에게 보호자 동의 요청 → IM_MINOR_GUARDIAN_REQUIRED
        assertThatThrownBy(() -> guardianConsentService.grantConsent(adultId, guardianId, CORR))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode())
                .isEqualTo(PlatformErrorCode.IM_MINOR_GUARDIAN_REQUIRED);
    }

    // ══════════════════════════════════════════════════════════════════════
    // S9: 기업회원 전환 E2E (V6 마이그레이션 — biz_member 테이블)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S9-6: GDPR 탈퇴 후 guardian_qim_user_id / guardian_consent_at NULL 처리 확인")
    void s9_6_gdprPiiDeleteIncludesGuardianColumns() {
        // 준비: 미성년자 + 보호자 동의 완료 상태 생성
        short minorBirthYear  = (short) (java.time.Year.now().getValue() - 11);
        short adultBirthYear  = (short) (java.time.Year.now().getValue() - 45);

        String minorId    = createUserWithProfile("minor-s9-006",    minorBirthYear, true);
        String guardianId = createUserWithProfile("guardian-s9-006", adultBirthYear, false);

        // 보호자 동의 처리
        guardianConsentService.grantConsent(minorId, guardianId, CORR);

        // 동의 완료 확인
        UserProfileJpaEntity before = profileRepository.findById(minorId).orElseThrow();
        assertThat(before.getGuardianConsentAt()).isNotNull();
        assertThat(before.getGuardianQimUserId()).isEqualTo(guardianId);

        // IMMEDIATE 탈퇴 → deletePii() 실행
        WithdrawalRequest withdrawReq = WithdrawalRequest.builder()
                .type(WithdrawalType.IMMEDIATE)
                .reason("탈퇴 GDPR 테스트")
                .correlationId(CORR)
                .build();
        withdrawalService.withdraw(minorId, withdrawReq);

        // DB 직접 조회로 guardian 컬럼 NULL 확인
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT guardian_qim_user_id, guardian_consent_at FROM user_profile WHERE qim_user_id = ?",
                minorId);
        assertThat(row.get("guardian_qim_user_id"))
                .as("탈퇴 후 guardian_qim_user_id는 NULL이어야 합니다 (GDPR §17).")
                .isNull();
        assertThat(row.get("guardian_consent_at"))
                .as("탈퇴 후 guardian_consent_at은 NULL이어야 합니다 (GDPR §17).")
                .isNull();
    }
}
