package kr.go.smes.qim.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.qim.api.dto.UserRegisterRequest;
import kr.go.smes.qim.api.dto.UserResponse;
import kr.go.smes.qim.crypto.CiCryptoService;
import kr.go.smes.qim.crypto.PiiMaskingService;
import kr.go.smes.qim.identity.DiGenerationService;
import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.entity.UserProfileJpaEntity;
import kr.go.smes.qim.infrastructure.jpa.repository.QimUserJpaRepository;
import kr.go.smes.qim.outbox.OutboxService;
import kr.go.smes.common.event.UserEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * UserRegistrationServiceImpl 단위 테스트 — 사용자 등록 Upsert + 탈퇴 PII 삭제 검증
 *
 * <p>커버 케이스:
 * <ul>
 *   <li>registerOrGet() — 기존 사용자 반환(isNew=false)</li>
 *   <li>registerOrGet() — 신규 사용자 생성(isNew=true, CI 암호화, PII 마스킹)</li>
 *   <li>registerOrGet() — 동시 생성 충돌(DataIntegrityViolationException) → 기존 사용자 반환</li>
 *   <li>registerOrGet() — CI 없는 요청도 정상 처리</li>
 *   <li>withdraw() — PII 삭제(UPDATE user_profile NULL) + WITHDRAWN 상태 + Outbox 발행</li>
 *   <li>updateStatus() — 상태 변경 + 이력 기록 + Outbox 발행</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserRegistrationServiceImpl — 사용자 등록·탈퇴")
class UserRegistrationServiceImplTest {

    @Mock private QimUserJpaRepository  userRepository;
    @Mock private CiCryptoService       ciCryptoService;
    @Mock private PiiMaskingService     piiMaskingService;
    @Mock private DiGenerationService   diGenerationService;
    @Mock private OutboxService         outboxService;
    @Mock private JdbcTemplate          jdbcTemplate;

    @InjectMocks
    private UserRegistrationServiceImpl service;

    // ObjectMapper는 실제 인스턴스 주입 (@InjectMocks가 처리 못하는 경우 직접 주입)
    @BeforeEach
    void injectObjectMapper() {
        try {
            var field = UserRegistrationServiceImpl.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(service, new ObjectMapper());
        } catch (Exception e) {
            // objectMapper 필드가 없는 경우 무시
        }
    }

    private static final String HASH         = "sha256hashOfIdentifier0000000001";
    private static final String PROVIDER_CODE = "KAKAO_OIDC";
    private static final String RAW_CI        = "dGVzdENJdmFsdWVGb3JUZXNOaW5n";
    private static final String ENC_CI        = "v1.ivBase64.ctBase64";
    private static final String MASKED_NAME   = "홍*동";
    private static final String MASKED_MOBILE = "010-****-5678";

    // ── registerOrGet() ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("registerOrGet() — Upsert 동작")
    class RegisterOrGet {

        @Test
        @DisplayName("이미 존재하는 사용자 → 기존 사용자 반환, isNew=false")
        void registerOrGet_existingUser_returnsExisting() {
            QimUserJpaEntity existing = buildUser("qim-001", "ACTIVE");
            given(userRepository.findByIdentifierHash(HASH))
                    .willReturn(Optional.of(existing));

            UserResponse response = service.registerOrGet(buildRequest(RAW_CI));

            assertThat(response.getQimUserId()).isEqualTo("qim-001");
            assertThat(response.isNew()).isFalse();
            // 신규 생성 로직(save)은 호출되지 않아야 함
            then(userRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("신규 사용자 — CI 암호화, PII 마스킹, JPA save, Outbox 발행")
        void registerOrGet_newUser_createsWithEncryptedCiAndMaskedPii() {
            given(userRepository.findByIdentifierHash(HASH)).willReturn(Optional.empty());
            given(ciCryptoService.encrypt(RAW_CI)).willReturn(ENC_CI);
            given(piiMaskingService.maskName("홍길동")).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile("01012345678")).willReturn(MASKED_MOBILE);
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UserResponse response = service.registerOrGet(buildRequest(RAW_CI));

            assertThat(response.isNew()).isTrue();
            // CI 암호화 호출 확인
            then(ciCryptoService).should().encrypt(RAW_CI);
            // PII 마스킹 호출 확인
            then(piiMaskingService).should().maskName("홍길동");
            then(piiMaskingService).should().maskMobile("01012345678");
            // JPA save 호출 확인
            then(userRepository).should().save(any(QimUserJpaEntity.class));
            // Outbox(Kafka) 발행 확인
            then(outboxService).should().publishInTx(any(UserEvent.class));
        }

        @Test
        @DisplayName("rawCi 없는 요청 — CI 암호화 건너뜀, 정상 생성")
        void registerOrGet_noCi_createsWithoutCiEncryption() {
            given(userRepository.findByIdentifierHash(HASH)).willReturn(Optional.empty());
            given(piiMaskingService.maskName(any())).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile(any())).willReturn(MASKED_MOBILE);
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UserRegisterRequest reqNoCi = UserRegisterRequest.builder()
                    .identifierHash(HASH)
                    .providerCode(PROVIDER_CODE)
                    .rawName("홍길동")
                    .rawMobile("01012345678")
                    .correlationId("cid-001")
                    .build();

            UserResponse response = service.registerOrGet(reqNoCi);

            assertThat(response.isNew()).isTrue();
            // CI 암호화는 호출되지 않아야 함
            then(ciCryptoService).should(never()).encrypt(any());
        }

        @Test
        @DisplayName("동시 생성 충돌(DataIntegrityViolationException) → 기존 사용자 반환")
        void registerOrGet_concurrentConflict_returnsExistingUser() {
            QimUserJpaEntity existing = buildUser("qim-002", "ACTIVE");
            given(userRepository.findByIdentifierHash(HASH))
                    // 첫 번째 조회: 없음 (새로 생성 시도)
                    .willReturn(Optional.empty())
                    // 두 번째 조회(충돌 후 재조회): 있음
                    .willReturn(Optional.of(existing));
            given(ciCryptoService.encrypt(any())).willReturn(ENC_CI);
            given(piiMaskingService.maskName(any())).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile(any())).willReturn(MASKED_MOBILE);
            // save 시 충돌 예외 발생
            given(userRepository.save(any()))
                    .willThrow(DataIntegrityViolationException.class);

            UserResponse response = service.registerOrGet(buildRequest(RAW_CI));

            // 충돌 후 재조회로 기존 사용자 반환
            assertThat(response.getQimUserId()).isEqualTo("qim-002");
            assertThat(response.isNew()).isFalse();
        }

        @Test
        @DisplayName("신규 사용자 상태는 ACTIVE")
        void registerOrGet_newUser_statusIsActive() {
            given(userRepository.findByIdentifierHash(HASH)).willReturn(Optional.empty());
            given(ciCryptoService.encrypt(any())).willReturn(ENC_CI);
            given(piiMaskingService.maskName(any())).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile(any())).willReturn(MASKED_MOBILE);

            ArgumentCaptor<QimUserJpaEntity> captor = ArgumentCaptor.forClass(QimUserJpaEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            service.registerOrGet(buildRequest(RAW_CI));

            assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("birthYear 기준 13세(현재연도-13) → isMinor=true로 프로필 저장 (P3-05)")
        void registerOrGet_minorBirthYear_isMinorTrue() {
            // 현재 연도 기준 13세: 14세 미만이므로 isMinor=true 기대
            short minorBirthYear = (short) (java.time.Year.now().getValue() - 13);

            given(userRepository.findByIdentifierHash(HASH)).willReturn(Optional.empty());
            given(ciCryptoService.encrypt(any())).willReturn(ENC_CI);
            given(piiMaskingService.maskName(any())).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile(any())).willReturn(MASKED_MOBILE);

            ArgumentCaptor<QimUserJpaEntity> captor = ArgumentCaptor.forClass(QimUserJpaEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            UserRegisterRequest minorReq = UserRegisterRequest.builder()
                    .identifierHash(HASH)
                    .providerCode(PROVIDER_CODE)
                    .rawCi(RAW_CI)
                    .rawName("홍길동")
                    .rawMobile("01012345678")
                    .nationalityType("DOMESTIC")
                    .birthYear(minorBirthYear)
                    .gender("MALE")
                    .correlationId("cid-minor-001")
                    .build();

            service.registerOrGet(minorReq);

            QimUserJpaEntity saved = captor.getValue();
            assertThat(saved.getProfile()).isNotNull();
            assertThat(saved.getProfile().getIsMinor())
                    .as("14세 미만 사용자는 isMinor=true로 저장되어야 합니다.")
                    .isTrue();
        }

        @Test
        @DisplayName("birthYear 기준 성인(현재연도-20) → isMinor=false로 프로필 저장")
        void registerOrGet_adultBirthYear_isMinorFalse() {
            short adultBirthYear = (short) (java.time.Year.now().getValue() - 20);

            given(userRepository.findByIdentifierHash(HASH)).willReturn(Optional.empty());
            given(ciCryptoService.encrypt(any())).willReturn(ENC_CI);
            given(piiMaskingService.maskName(any())).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile(any())).willReturn(MASKED_MOBILE);

            ArgumentCaptor<QimUserJpaEntity> captor = ArgumentCaptor.forClass(QimUserJpaEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            UserRegisterRequest adultReq = UserRegisterRequest.builder()
                    .identifierHash(HASH)
                    .providerCode(PROVIDER_CODE)
                    .rawCi(RAW_CI)
                    .rawName("홍길동")
                    .rawMobile("01012345678")
                    .nationalityType("DOMESTIC")
                    .birthYear(adultBirthYear)
                    .gender("MALE")
                    .correlationId("cid-adult-001")
                    .build();

            service.registerOrGet(adultReq);

            QimUserJpaEntity saved = captor.getValue();
            assertThat(saved.getProfile()).isNotNull();
            assertThat(saved.getProfile().getIsMinor())
                    .as("성인 사용자는 isMinor=false로 저장되어야 합니다.")
                    .isFalse();
        }

        @Test
        @DisplayName("birthYear null → isMinor=false로 처리 (MinorGuardianPolicy.isMinor 기본값)")
        void registerOrGet_nullBirthYear_isMinorFalse() {
            given(userRepository.findByIdentifierHash(HASH)).willReturn(Optional.empty());
            given(piiMaskingService.maskName(any())).willReturn(MASKED_NAME);
            given(piiMaskingService.maskMobile(any())).willReturn(MASKED_MOBILE);

            ArgumentCaptor<QimUserJpaEntity> captor = ArgumentCaptor.forClass(QimUserJpaEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            UserRegisterRequest nullBirthReq = UserRegisterRequest.builder()
                    .identifierHash(HASH)
                    .providerCode(PROVIDER_CODE)
                    .rawName("홍길동")
                    .rawMobile("01012345678")
                    .nationalityType("DOMESTIC")
                    .correlationId("cid-null-birth-001")
                    .build();

            service.registerOrGet(nullBirthReq);

            QimUserJpaEntity saved = captor.getValue();
            assertThat(saved.getProfile()).isNotNull();
            assertThat(saved.getProfile().getIsMinor())
                    .as("birthYear null이면 isMinor=false(기본값)이어야 합니다.")
                    .isFalse();
        }
    }

    // ── withdraw() ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withdraw() — 탈퇴 처리 & PII 삭제")
    class Withdraw {

        @Test
        @DisplayName("탈퇴 시 status=WITHDRAWN, withdrawnAt 설정, JPA save 호출")
        void withdraw_setsWithdrawnStatusAndTimestamp() {
            QimUserJpaEntity user = buildUser("qim-001", "ACTIVE");
            given(userRepository.findById("qim-001")).willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.withdraw("qim-001", "USER_REQUEST");

            assertThat(user.getStatus()).isEqualTo("WITHDRAWN");
            assertThat(user.getWithdrawnAt()).isNotNull();
            assertThat(user.getWithdrawalReason()).isEqualTo("USER_REQUEST");
        }

        @Test
        @DisplayName("탈퇴 시 PII 삭제 SQL 실행 — user_profile UPDATE 호출")
        void withdraw_executesPiiDeletionSql() {
            QimUserJpaEntity user = buildUser("qim-001", "ACTIVE");
            given(userRepository.findById("qim-001")).willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.withdraw("qim-001", "USER_REQUEST");

            // JdbcTemplate.update() 호출 확인 (PII NULL 처리 SQL)
            then(jdbcTemplate).should(atLeastOnce())
                    .update(argThat((String sql) -> sql.contains("user_profile")),
                            eq("qim-001"));
        }

        @Test
        @DisplayName("탈퇴 시 Outbox(Kafka) UserEvent 발행")
        void withdraw_publishesUserWithdrawnEvent() {
            QimUserJpaEntity user = buildUser("qim-001", "ACTIVE");
            given(userRepository.findById("qim-001")).willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.withdraw("qim-001", "USER_REQUEST");

            then(outboxService).should().publishInTx(argThat(
                    event -> event instanceof UserEvent
            ));
        }

        @Test
        @DisplayName("존재하지 않는 qimUserId → IllegalArgumentException")
        void withdraw_unknownUser_throwsIllegalArgumentException() {
            given(userRepository.findById("unknown")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.withdraw("unknown", "reason"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown");
        }
    }

    // ── updateStatus() ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateStatus() — 상태 변경")
    class UpdateStatus {

        @Test
        @DisplayName("ACTIVE → SUSPENDED 상태 변경 + 이력 기록 SQL + Outbox 발행")
        void updateStatus_activeToSuspended_updatesAndPublishes() {
            QimUserJpaEntity user = buildUser("qim-001", "ACTIVE");
            given(userRepository.findById("qim-001")).willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            // 이력 기록 SQL — update()는 int 반환이므로 willReturn(1) 사용
            given(jdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

            service.updateStatus("qim-001", "SUSPENDED", "ADMIN", "정책 위반");

            assertThat(user.getStatus()).isEqualTo("SUSPENDED");
            then(outboxService).should().publishInTx(any(UserEvent.class));
        }

        @Test
        @DisplayName("상태 이력 기록 SQL 실패 — 비치명적, 서비스 계속 동작")
        void updateStatus_statusHistoryFails_nonFatal() {
            QimUserJpaEntity user = buildUser("qim-001", "ACTIVE");
            given(userRepository.findById("qim-001")).willReturn(Optional.of(user));
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            // 이력 SQL 예외 발생 — 비치명적이어야 함
            given(jdbcTemplate.update(anyString(), (Object[]) any()))
                    .willThrow(new RuntimeException("DB 이력 기록 실패"));

            // 예외 전파 없이 정상 완료
            assertThatCode(() -> service.updateStatus("qim-001", "SUSPENDED", "ADMIN", "reason"))
                    .doesNotThrowAnyException();
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private UserRegisterRequest buildRequest(String rawCi) {
        return UserRegisterRequest.builder()
                .identifierHash(HASH)
                .providerCode(PROVIDER_CODE)
                .rawCi(rawCi)
                .rawName("홍길동")
                .rawMobile("01012345678")
                .nationalityType("DOMESTIC")
                .gender("MALE")
                .correlationId("cid-test-001")
                .build();
    }

    private QimUserJpaEntity buildUser(String id, String status) {
        UserProfileJpaEntity profile = UserProfileJpaEntity.builder()
                .qimUserId(id)
                .nameMasked(MASKED_NAME)
                .mobileMasked(MASKED_MOBILE)
                .nationalityType("DOMESTIC")
                .updatedAt(Instant.now())
                .build();

        QimUserJpaEntity entity = QimUserJpaEntity.builder()
                .qimUserId(id)
                .status(status)
                .eventVersion(1L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        entity.setProfile(profile);
        profile.setUser(entity);
        return entity;
    }
}
