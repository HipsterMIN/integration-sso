package kr.go.smes.ido.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.ido.audit.AuditLogPublisher;
import kr.go.smes.ido.domain.AgencyMeta;
import kr.go.smes.ido.handoff.crypto.HandoffCryptoService;
import kr.go.smes.ido.handoff.strategy.HandoffStrategy;
import kr.go.smes.ido.handoff.strategy.HandoffStrategyFactory;
import kr.go.smes.ido.handoff.validate.CallbackUrlValidator;
import kr.go.smes.ido.infrastructure.AgencyMetaRepository;
import kr.go.smes.ido.infrastructure.TicketRepository;
import kr.go.smes.ido.policy.PolicyEngine;
import kr.go.smes.ido.ratelimit.AgencyRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.inOrder;

/**
 * HandoffServiceImpl 단위 테스트
 *
 * <p>검증 범위:
 * <ul>
 *   <li>issue() — 정상 발급 / 미등록 기관 / 비활성 기관 / Rate Limit 초과 / 인증수준 미달 /
 *                  정지·탈퇴 사용자 / 점검 시간</li>
 *   <li>verify() — 정상 소비 / 만료 / 기소비(Consumed) / 취소됨(Revoked) / 기관 불일치 / 재사용 시도</li>
 *   <li>revoke() — 정상 취소 / 취소 후 Kafka 이벤트 발행</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HandoffServiceImpl 단위 테스트")
class HandoffServiceImplTest {

    // ── 의존성 Mock ─────────────────────────────────────────────────────────
    @Mock AgencyMetaRepository    agencyMetaRepository;
    @Mock TicketRepository        ticketRepository;
    @Mock PolicyEngine            policyEngine;
    @Mock HandoffCryptoService    handoffCryptoService;
    @Mock AuditLogPublisher       auditLogPublisher;
    @Mock CallbackUrlValidator    callbackUrlValidator;
    @Mock HandoffStrategyFactory  strategyFactory;
    @Mock AgencyRateLimiter       rateLimiter;
    @Mock KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    HandoffServiceImpl sut;

    // ── 공통 픽스처 ──────────────────────────────────────────────────────────
    private static final String AGENCY_CODE     = "AGENCY-001";
    private static final String QIM_USER_ID     = "qim-user-test-001";
    private static final String AUTH_RESULT_ID  = "auth-result-001";
    private static final String CORRELATION_ID  = "corr-001";
    private static final String TICKET_ID       = "ticket-001";
    private static final String REDIRECT_URI    = "https://agency.go.kr/callback";

    private AgencyMeta activeAgency;
    private HandoffIssueCommand validCommand;

    @BeforeEach
    void setUp() {
        // ObjectMapper는 실제 인스턴스로 주입 (JSON 직렬화 필요)
        sut = new HandoffServiceImpl(
                agencyMetaRepository, ticketRepository, policyEngine,
                handoffCryptoService, auditLogPublisher, callbackUrlValidator,
                strategyFactory, rateLimiter, new ObjectMapper(), kafkaTemplate
        );

        activeAgency = AgencyMeta.builder()
                .agencyCode(AGENCY_CODE)
                .officialName("테스트 기관")
                .active(true)
                .minAuthLevel(AuthResult.AuthLevel.L1)
                .callbackWhitelist(List.of(REDIRECT_URI))
                .allowedAttributes(List.of("name", "mobile"))
                .integrationType("DIRECT")
                .build();

        validCommand = HandoffIssueCommand.builder()
                .correlationId(CORRELATION_ID)
                .agencyCode(AGENCY_CODE)
                .qimUserId(QIM_USER_ID)
                .authResultId(AUTH_RESULT_ID)
                .authLevel(AuthResult.AuthLevel.L2)
                .providerCode("PASS")
                .redirectUri(REDIRECT_URI)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // issue() 테스트
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("issue() — 티켓 발급")
    class IssueTests {

        @BeforeEach
        void setUpCommonMocks() {
            given(agencyMetaRepository.findByCode(AGENCY_CODE)).willReturn(Optional.of(activeAgency));
            given(rateLimiter.tryAcquire(AGENCY_CODE)).willReturn(true);
            given(policyEngine.isUnderMaintenance(any())).willReturn(false);
            given(policyEngine.meetsMinAuthLevel(any(), any())).willReturn(true);
            given(policyEngine.resolveUserStatus(eq(QIM_USER_ID), any())).willReturn(UserStatus.ACTIVE);
            given(handoffCryptoService.encrypt(any(), any())).willReturn("encrypted-payload");
            given(handoffCryptoService.sign(any(), any(), any())).willReturn("hmac-signature");

            HandoffStrategy directStrategy = mock(HandoffStrategy.class);
            given(strategyFactory.getStrategy(any())).willReturn(directStrategy);
            willDoNothing().given(directStrategy).postIssue(any(), any(), any());
            willDoNothing().given(auditLogPublisher).publish(any());
        }

        @Test
        @DisplayName("정상 경로 — 티켓 발급 성공, ticketId/agencyCode/state 검증")
        void validIssue_returnsTicketWithIssuedState() {
            // when
            HandoffTicket ticket = sut.issue(validCommand);

            // then
            assertThat(ticket).isNotNull();
            assertThat(ticket.getAgencyCode()).isEqualTo(AGENCY_CODE);
            assertThat(ticket.getQimUserId()).isEqualTo(QIM_USER_ID);
            assertThat(ticket.getState()).isEqualTo(HandoffTicket.TicketState.ISSUED);
            assertThat(ticket.getTicketId()).isNotBlank();
            assertThat(ticket.getEncryptedPayload()).isEqualTo("encrypted-payload");
            assertThat(ticket.getSignature()).isEqualTo("hmac-signature");
            assertThat(ticket.getExpiresAt()).isAfter(Instant.now());

            // ticketRepository.save() 1회 호출
            then(ticketRepository).should(times(1)).save(any(HandoffTicket.class));
            // Kafka 이벤트 발행
            then(kafkaTemplate).should(times(1)).send(eq("ido.handoff.events"), any(), any());
        }

        @Test
        @DisplayName("기관 미등록 — AGENCY_NOT_REGISTERED 예외")
        void unknownAgency_throwsAgencyNotRegistered() {
            given(agencyMetaRepository.findByCode(AGENCY_CODE)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.AGENCY_NOT_REGISTERED);

            then(ticketRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("비활성 기관 — AGENCY_NOT_REGISTERED 예외")
        void inactiveAgency_throwsAgencyNotRegistered() {
            AgencyMeta inactiveAgency = AgencyMeta.builder()
                    .agencyCode(AGENCY_CODE).active(false)
                    .minAuthLevel(AuthResult.AuthLevel.L1)
                    .integrationType("DIRECT").build();
            given(agencyMetaRepository.findByCode(AGENCY_CODE)).willReturn(Optional.of(inactiveAgency));

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.AGENCY_NOT_REGISTERED);
        }

        @Test
        @DisplayName("Rate Limit 초과 — AGENCY_RATE_LIMIT_EXCEEDED 예외")
        void rateLimitExceeded_throwsRateLimitException() {
            given(rateLimiter.tryAcquire(AGENCY_CODE)).willReturn(false);

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.AGENCY_RATE_LIMIT_EXCEEDED);

            then(ticketRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("점검 시간 — AGENCY_MAINTENANCE 예외")
        void maintenanceWindow_throwsMaintenanceException() {
            given(policyEngine.isUnderMaintenance(any())).willReturn(true);

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.AGENCY_MAINTENANCE);
        }

        @Test
        @DisplayName("인증 수준 미달 — IDO_AUTH_LEVEL_INSUFFICIENT 예외")
        void authLevelInsufficient_throwsException() {
            given(policyEngine.meetsMinAuthLevel(any(), any())).willReturn(false);

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT);

            then(ticketRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("정지 사용자 — IM_USER_SUSPENDED 예외")
        void suspendedUser_throwsUserSuspendedException() {
            given(policyEngine.resolveUserStatus(eq(QIM_USER_ID), any()))
                    .willReturn(UserStatus.SUSPENDED);

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_USER_SUSPENDED);

            then(ticketRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("탈퇴 사용자 — IM_USER_WITHDRAWN 예외")
        void withdrawnUser_throwsUserWithdrawnException() {
            given(policyEngine.resolveUserStatus(eq(QIM_USER_ID), any()))
                    .willReturn(UserStatus.WITHDRAWN);

            assertThatThrownBy(() -> sut.issue(validCommand))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IM_USER_WITHDRAWN);
        }

        @Test
        @DisplayName("감사 로그 실패는 비치명적 — 티켓 발급 성공 유지")
        void auditLogFailure_doesNotAffectTicketIssuance() {
            willThrow(new RuntimeException("Kafka down"))
                    .given(auditLogPublisher).publish(any());

            // 감사 로그 실패해도 ticket 반환되어야 함
            HandoffTicket ticket = sut.issue(validCommand);
            assertThat(ticket).isNotNull();
            assertThat(ticket.getState()).isEqualTo(HandoffTicket.TicketState.ISSUED);
        }

        @Test
        @DisplayName("Strategy postIssue 실패는 비치명적 — 티켓은 이미 저장됨")
        void strategyPostIssueFailure_doesNotRollbackTicket() {
            HandoffStrategy failStrategy = mock(HandoffStrategy.class);
            given(strategyFactory.getStrategy(any())).willReturn(failStrategy);
            willThrow(new RuntimeException("bridge timeout"))
                    .given(failStrategy).postIssue(any(), any(), any());

            HandoffTicket ticket = sut.issue(validCommand);

            assertThat(ticket).isNotNull();
            // ticketRepository.save()는 이미 호출됨 (strategy 실패 전)
            then(ticketRepository).should(times(1)).save(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // verify() 테스트
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("verify() — 티켓 검증 및 소비")
    class VerifyTests {

        private HandoffTicket issuedTicket;

        @BeforeEach
        void setUpTicket() {
            issuedTicket = HandoffTicket.builder()
                    .ticketId(TICKET_ID)
                    .correlationId(CORRELATION_ID)
                    .agencyCode(AGENCY_CODE)
                    .qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID)
                    .authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(55))  // 아직 유효
                    .encryptedPayload("encrypted")
                    .signature("sig")
                    .build();

            willDoNothing().given(auditLogPublisher).publish(any());
            // Sprint α-2 / F4.1 — verify() 가 cryptoService.verify() 를 호출하므로
            // 정상 경로 테스트에서는 true 를 반환해야 함 (LENIENT 모드라 미사용 stub 도 무방)
            given(handoffCryptoService.verify(any(), any(), any(), any())).willReturn(true);
        }

        @Test
        @DisplayName("정상 경로 — Payload 반환 + ticketRepository.consume() 호출")
        void validVerify_consumesTicketAndReturnsPayload() {
            HandoffPayload expectedPayload = HandoffPayload.builder()
                    .subject(HandoffPayload.SubjectIdentifier.builder()
                            .agencySubjectId("subject-001")
                            .build())
                    .build();

            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(issuedTicket));
            willDoNothing().given(ticketRepository).consume(TICKET_ID);
            given(policyEngine.buildHandoffPayload(any(), any())).willReturn(expectedPayload);

            HandoffPayload result = sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID);

            assertThat(result).isEqualTo(expectedPayload);
            then(ticketRepository).should(times(1)).consume(TICKET_ID);
            then(kafkaTemplate).should(times(1)).send(eq("ido.handoff.events"), any(), any());
            // F4.1 — 서명 검증이 1회 호출되어야 함
            then(handoffCryptoService).should(times(1))
                    .verify(eq(TICKET_ID), eq(AGENCY_CODE), eq("encrypted"), eq("sig"));
        }

        @Test
        @DisplayName("존재하지 않는 티켓 — IDO_TICKET_EXPIRED 예외")
        void ticketNotFound_throwsTicketExpiredException() {
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_EXPIRED);
        }

        @Test
        @DisplayName("이미 소비된 티켓 — IDO_TICKET_CONSUMED 예외 + Reuse Kafka 이벤트")
        void consumedTicket_throwsConsumedExceptionAndPublishesReuseEvent() {
            HandoffTicket consumed = HandoffTicket.builder()
                    .ticketId(TICKET_ID).agencyCode(AGENCY_CODE).qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID).authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.CONSUMED)
                    .issuedAt(Instant.now().minusSeconds(10))
                    .expiresAt(Instant.now().plusSeconds(50))
                    .build();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(consumed));

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_CONSUMED);

            // REUSE_ATTEMPT 이벤트가 Kafka로 발행되어야 함
            then(kafkaTemplate).should(times(1)).send(eq("ido.handoff.events"), any(), any());
        }

        @Test
        @DisplayName("취소된 티켓 — IDO_TICKET_REVOKED 예외")
        void revokedTicket_throwsRevokedException() {
            HandoffTicket revoked = HandoffTicket.builder()
                    .ticketId(TICKET_ID).agencyCode(AGENCY_CODE).qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID).authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.REVOKED)
                    .issuedAt(Instant.now().minusSeconds(10))
                    .expiresAt(Instant.now().plusSeconds(50))
                    .build();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(revoked));

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_REVOKED);
        }

        @Test
        @DisplayName("만료된 티켓 — IDO_TICKET_EXPIRED 예외")
        void expiredTicket_throwsExpiredException() {
            HandoffTicket expired = HandoffTicket.builder()
                    .ticketId(TICKET_ID).agencyCode(AGENCY_CODE).qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID).authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(120))
                    .expiresAt(Instant.now().minusSeconds(60))  // 이미 만료
                    .build();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(expired));

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_EXPIRED);
        }

        @Test
        @DisplayName("기관 코드 불일치 — AGENCY_CODE_MISMATCH 예외")
        void agencyCodeMismatch_throwsMismatchException() {
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(issuedTicket));

            assertThatThrownBy(() -> sut.verify(TICKET_ID, "WRONG-AGENCY", CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.AGENCY_CODE_MISMATCH);

            // consume()은 호출되어선 안 됨
            then(ticketRepository).should(never()).consume(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sprint α-2 / F4.1 — Signature Verification Tests
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Sprint α-2 / F4.1 — Handoff verify() 서명 검증")
    class SignatureVerification {

        private HandoffTicket issuedTicket;

        @BeforeEach
        void setUpTicket() {
            issuedTicket = HandoffTicket.builder()
                    .ticketId(TICKET_ID)
                    .correlationId(CORRELATION_ID)
                    .agencyCode(AGENCY_CODE)
                    .qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID)
                    .authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(55))
                    .encryptedPayload("tampered-cipher")
                    .signature("forged-sig")
                    .build();

            willDoNothing().given(auditLogPublisher).publish(any());
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(issuedTicket));
        }

        @Test
        @DisplayName("서명 불일치 — IDO_TICKET_SIGNATURE_INVALID + SIGNATURE_INVALID 이벤트 + consume 미호출")
        void signatureMismatch_throwsSignatureInvalidAndDoesNotConsume() {
            given(handoffCryptoService.verify(eq(TICKET_ID), eq(AGENCY_CODE),
                    eq("tampered-cipher"), eq("forged-sig"))).willReturn(false);

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_SIGNATURE_INVALID);

            // F4.1 + F4.5 — 서명 실패 시 consume 호출 금지 + Q-IM 호출 금지
            then(ticketRepository).should(never()).consume(any());
            then(policyEngine).should(never()).buildHandoffPayload(any(), any());
            // SIGNATURE_INVALID Kafka 이벤트 발행 확인 (REUSE_ATTEMPT 와 동일 토픽)
            then(kafkaTemplate).should(times(1)).send(eq("ido.handoff.events"), any(), any());
        }

        @Test
        @DisplayName("encryptedPayload null — 서명 검증 실패로 간주, 401")
        void nullEncryptedPayload_throwsSignatureInvalid() {
            HandoffTicket nullPayloadTicket = HandoffTicket.builder()
                    .ticketId(TICKET_ID).agencyCode(AGENCY_CODE).qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID).authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(55))
                    .encryptedPayload(null)
                    .signature("sig")
                    .build();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(nullPayloadTicket));

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_SIGNATURE_INVALID);

            // 서명 검증 메서드는 short-circuit 으로 호출 안 됨
            then(handoffCryptoService).should(never()).verify(any(), any(), any(), any());
            then(ticketRepository).should(never()).consume(any());
        }

        @Test
        @DisplayName("signature null — 서명 검증 실패로 간주, 401")
        void nullSignature_throwsSignatureInvalid() {
            HandoffTicket nullSigTicket = HandoffTicket.builder()
                    .ticketId(TICKET_ID).agencyCode(AGENCY_CODE).qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID).authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(55))
                    .encryptedPayload("cipher")
                    .signature(null)
                    .build();
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(nullSigTicket));

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_SIGNATURE_INVALID);

            then(ticketRepository).should(never()).consume(any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sprint α-2 / F4.5 — Verify Ordering Tests (consume 은 buildPayload 성공 후)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Sprint α-2 / F4.5 — verify() 단계 순서: buildPayload → consume")
    class VerifyOrdering {

        private HandoffTicket issuedTicket;

        @BeforeEach
        void setUpTicket() {
            issuedTicket = HandoffTicket.builder()
                    .ticketId(TICKET_ID)
                    .correlationId(CORRELATION_ID)
                    .agencyCode(AGENCY_CODE)
                    .qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID)
                    .authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(55))
                    .encryptedPayload("cipher")
                    .signature("sig")
                    .build();

            willDoNothing().given(auditLogPublisher).publish(any());
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(issuedTicket));
            given(handoffCryptoService.verify(any(), any(), any(), any())).willReturn(true);
        }

        @Test
        @DisplayName("buildPayload 가 Q-IM 장애로 실패 — ticket 은 ISSUED 유지(consume 미호출, 재시도 가능)")
        void buildPayloadFails_doesNotConsumeTicket() {
            // policyEngine 이 Q-IM 장애로 예외 던짐
            willThrow(new RuntimeException("Q-IM timeout"))
                    .given(policyEngine).buildHandoffPayload(any(), any());

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(RuntimeException.class);

            // 핵심: consume 이 호출되지 않아야 ticket 이 ISSUED 그대로 → 재시도 가능
            then(ticketRepository).should(never()).consume(any());
            // HANDOFF_CONSUMED Kafka 이벤트도 발행되지 않아야 함
            // (다른 이벤트는 없으므로 0회)
            then(kafkaTemplate).should(never()).send(any(String.class), any(), any());
        }

        @Test
        @DisplayName("정상 경로 — buildPayload 성공 → consume → publish 순서")
        void successPath_buildPayloadBeforeConsume() {
            HandoffPayload expectedPayload = HandoffPayload.builder()
                    .subject(HandoffPayload.SubjectIdentifier.builder()
                            .agencySubjectId("subject-001").build())
                    .build();
            given(policyEngine.buildHandoffPayload(any(), any())).willReturn(expectedPayload);
            willDoNothing().given(ticketRepository).consume(TICKET_ID);

            HandoffPayload result = sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID);

            assertThat(result).isEqualTo(expectedPayload);

            // Mockito InOrder 로 호출 순서 검증
            var inOrder = inOrder(handoffCryptoService, policyEngine, ticketRepository, kafkaTemplate);
            inOrder.verify(handoffCryptoService).verify(any(), any(), any(), any());
            inOrder.verify(policyEngine).buildHandoffPayload(any(), any());
            inOrder.verify(ticketRepository).consume(TICKET_ID);
            inOrder.verify(kafkaTemplate).send(eq("ido.handoff.events"), any(), any());
        }

        @Test
        @DisplayName("consume 단계에서 IDO_TICKET_CONSUMED 예외 — 동시 verify race winner 패배자")
        void consumeRaceMismatch_propagatesPlatformException() {
            HandoffPayload payload = HandoffPayload.builder().build();
            given(policyEngine.buildHandoffPayload(any(), any())).willReturn(payload);
            // Lua CAS 가 mismatch 로 IDO_TICKET_CONSUMED 던짐
            willThrow(new PlatformException(PlatformErrorCode.IDO_TICKET_CONSUMED, CORRELATION_ID))
                    .given(ticketRepository).consume(TICKET_ID);

            assertThatThrownBy(() -> sut.verify(TICKET_ID, AGENCY_CODE, CORRELATION_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_CONSUMED);

            // HANDOFF_CONSUMED Kafka 이벤트는 발행되지 않아야 함
            then(kafkaTemplate).should(never()).send(any(String.class), any(), any());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // revoke() 테스트
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("revoke() — 티켓 강제 취소")
    class RevokeTests {

        private HandoffTicket issuedTicket;

        @BeforeEach
        void setUpTicket() {
            issuedTicket = HandoffTicket.builder()
                    .ticketId(TICKET_ID)
                    .correlationId(CORRELATION_ID)
                    .agencyCode(AGENCY_CODE)
                    .qimUserId(QIM_USER_ID)
                    .authResultId(AUTH_RESULT_ID)
                    .authLevel(AuthResult.AuthLevel.L2)
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(Instant.now().minusSeconds(5))
                    .expiresAt(Instant.now().plusSeconds(55))
                    .build();

            willDoNothing().given(auditLogPublisher).publish(any());
        }

        @Test
        @DisplayName("정상 취소 — ticketRepository.revoke() 호출 + HANDOFF_REVOKED Kafka 이벤트")
        void validRevoke_invokesRepositoryAndPublishesKafkaEvent() {
            willDoNothing().given(ticketRepository).revoke(eq(TICKET_ID), any());
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(issuedTicket));

            sut.revoke(TICKET_ID, "incident_containment", CORRELATION_ID);

            then(ticketRepository).should(times(1)).revoke(eq(TICKET_ID), eq("incident_containment"));
            then(kafkaTemplate).should(times(1)).send(eq("ido.handoff.events"), any(), any());
        }

        @Test
        @DisplayName("취소 후 감사 로그 기록 확인")
        void revoke_publishesAuditLog() {
            willDoNothing().given(ticketRepository).revoke(any(), any());
            given(ticketRepository.findById(TICKET_ID)).willReturn(Optional.of(issuedTicket));

            sut.revoke(TICKET_ID, "compromise", CORRELATION_ID);

            then(auditLogPublisher).should(times(1))
                    .publish(argThat(entry ->
                            "HANDOFF_REVOKED".equals(entry.eventAction())
                            && TICKET_ID.equals(entry.resourceId())
                    ));
        }
    }
}
