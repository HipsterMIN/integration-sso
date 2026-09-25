package io.github.hipstermin.idem.hub.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.api.dto.HandoffIssueRequest;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.handoff.HandoffIssueCommand;
import io.github.hipstermin.idem.hub.handoff.HandoffService;
import io.github.hipstermin.idem.hub.infrastructure.TicketRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * HandoffController 단위 테스트
 *
 * <p>검증 항목:
 * <ul>
 *   <li>GAP-API-02: Idempotency-Key HIT — 기존 Ticket 재반환, X-Idempotency-Replayed: true 헤더</li>
 *   <li>GAP-API-02: Idempotency-Key HIT 이나 Ticket 만료 → 재발급</li>
 *   <li>GAP-API-02: Idempotency-Key MISS → 신규 발급 + Redis 저장</li>
 *   <li>GAP-API-02: Idempotency-Key 미전달 → 단순 발급 (Redis 저장 없음)</li>
 *   <li>P1 보안: Fe-Session-Id 쿠키 없으면 PlatformException 발생</li>
 *   <li>P1 보안: FeSession 만료/없음 시 PlatformException 발생</li>
 *   <li>P1 보안: qimUserId를 FeSession에서 추출하여 HandoffIssueCommand에 설정</li>
 * </ul>
 */
@DisplayName("HandoffController — Idempotency-Key + P1 보안 단위 테스트")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandoffControllerTest {

    // ── 의존성 Mock ─────────────────────────────────────────────────────────
    @Mock private HandoffService                    handoffService;
    @Mock private FeSessionService                  feSessionService;
    @Mock private io.github.hipstermin.idem.hub.fe.session.FeSessionPolicyEnforcer feSessionPolicyEnforcer;
    @Mock private TicketRepository                  ticketRepository;
    @Mock private RedisTemplate<String, Object>     redisTemplate;
    @Mock private ValueOperations<String, Object>   valueOps;

    @InjectMocks
    private HandoffController controller;

    /** 테스트용 고정 feSessionId (HttpOnly 쿠키 값) */
    private static final String FE_SESSION_ID = "test-fe-session-id-12345";
    /** 테스트용 qimUserId (FeSession에서 서버 측 추출) */
    private static final String QIM_USER_ID   = "qim-user-001";
    /** Idempotency-Key Redis 키 접두어 */
    private static final String IDEM_PREFIX   = "idem:idempotency:handoff:";

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
    }

    // ────────────────────────────────────────────────────────────────────────
    // P1 보안 검증 — FeSession 쿠키 체크
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("P1 보안: FeSession 쿠키 검증")
    class FeSessionSecurityTest {

        @Test
        @DisplayName("Fe-Session-Id 쿠키 없으면 PlatformException(IDO_SESSION_NOT_FOUND) 발생")
        void issue_missingFeSessionCookie_throwsPlatformException() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            // 쿠키 없음

            assertThatThrownBy(() ->
                    controller.issue(null, null, makeIssueRequest(), req))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> {
                        PlatformException pe = (PlatformException) e;
                        assertThat(pe.getErrorCode()).isEqualTo(PlatformErrorCode.IDO_SESSION_NOT_FOUND);
                    });

            verify(handoffService, never()).issue(any());
        }

        @Test
        @DisplayName("Fe-Session-Id 쿠키 있으나 FeSession 없음(만료) → PlatformException 발생")
        void issue_feSessionNotFound_throwsPlatformException() {
            MockHttpServletRequest req = withFeSessionCookie();
            given(feSessionService.findById(FE_SESSION_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    controller.issue(null, null, makeIssueRequest(), req))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(e -> {
                        PlatformException pe = (PlatformException) e;
                        assertThat(pe.getErrorCode()).isEqualTo(PlatformErrorCode.IDO_SESSION_NOT_FOUND);
                    });

            verify(handoffService, never()).issue(any());
        }

        @Test
        @DisplayName("[P1] qimUserId는 request body가 아닌 FeSession 서버 측 추출값 사용")
        void issue_qimUserIdExtractedFromFeSession_notFromRequestBody() {
            // given
            MockHttpServletRequest httpReq = withFeSessionCookie();
            String sessionQimUserId = "session-qim-user-server-side";
            given(feSessionService.findById(FE_SESSION_ID))
                    .willReturn(Optional.of(makeFeSession(sessionQimUserId)));

            HandoffTicket ticket = makeTicket("ticket-001");
            ArgumentCaptor<HandoffIssueCommand> cmdCaptor = ArgumentCaptor.forClass(HandoffIssueCommand.class);
            given(handoffService.issue(cmdCaptor.capture())).willReturn(ticket);

            // when
            controller.issue(null, null, makeIssueRequest(), httpReq);

            // then: HandoffIssueCommand에 FeSession에서 추출한 qimUserId가 설정되어야 함
            HandoffIssueCommand cmd = cmdCaptor.getValue();
            assertThat(cmd.getQimUserId()).isEqualTo(sessionQimUserId);
            // request body에서 qimUserId를 주입해도 무시되어야 함 (P1 보안)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // GAP-API-02: Idempotency-Key 처리 검증
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GAP-API-02: Idempotency-Key 멱등 처리")
    class IdempotencyKeyTest {

        @BeforeEach
        void setUpFeSession() {
            given(feSessionService.findById(FE_SESSION_ID))
                    .willReturn(Optional.of(makeFeSession(QIM_USER_ID)));
        }

        @Test
        @DisplayName("Idempotency-Key MISS → 신규 Ticket 발급 + Redis에 ticketId 저장")
        void issue_idempotencyKeyMiss_issuesNewTicketAndCachesIt() {
            // given
            String idempotencyKey = "idem-key-new-001";
            String redisKey = IDEM_PREFIX + idempotencyKey;

            given(valueOps.get(redisKey)).willReturn(null);  // MISS

            HandoffTicket newTicket = makeTicket("ticket-new-001");
            given(handoffService.issue(any())).willReturn(newTicket);

            // when
            ResponseEntity<HandoffTicket> response =
                    controller.issue(null, idempotencyKey, makeIssueRequest(), withFeSessionCookie());

            // then
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(newTicket);
            assertThat(response.getHeaders().getFirst("X-Idempotency-Replayed")).isNull();

            // Redis에 ticketId 저장 확인
            verify(valueOps).set(eq(redisKey), eq("ticket-new-001"), any());
        }

        @Test
        @DisplayName("Idempotency-Key HIT + Ticket 유효 → 기존 Ticket 재반환, X-Idempotency-Replayed: true")
        void issue_idempotencyKeyHitValidTicket_returnsExistingTicket() {
            // given
            String idempotencyKey = "idem-key-hit-001";
            String redisKey = IDEM_PREFIX + idempotencyKey;
            String cachedTicketId = "ticket-existing-001";

            given(valueOps.get(redisKey)).willReturn(cachedTicketId);  // HIT

            HandoffTicket existingTicket = makeTicket(cachedTicketId);
            given(ticketRepository.findById(cachedTicketId)).willReturn(Optional.of(existingTicket));

            // when
            ResponseEntity<HandoffTicket> response =
                    controller.issue(null, idempotencyKey, makeIssueRequest(), withFeSessionCookie());

            // then
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(existingTicket);
            // X-Idempotency-Replayed 헤더 확인 (GAP-API-02 캐시 히트 표시)
            assertThat(response.getHeaders().getFirst("X-Idempotency-Replayed")).isEqualTo("true");

            // HandoffService.issue()는 호출되지 않아야 함 (기존 Ticket 재반환)
            verify(handoffService, never()).issue(any());
        }

        @Test
        @DisplayName("Idempotency-Key HIT 이나 Ticket 만료/소비됨 → HandoffService로 재발급")
        void issue_idempotencyKeyHitExpiredTicket_reissuesNewTicket() {
            // given
            String idempotencyKey = "idem-key-expired-001";
            String redisKey = IDEM_PREFIX + idempotencyKey;
            String cachedTicketId = "ticket-expired-001";

            given(valueOps.get(redisKey)).willReturn(cachedTicketId);  // HIT
            given(ticketRepository.findById(cachedTicketId))
                    .willReturn(Optional.empty());  // Ticket 만료/소비됨

            HandoffTicket reissuedTicket = makeTicket("ticket-reissued-001");
            given(handoffService.issue(any())).willReturn(reissuedTicket);

            // when
            ResponseEntity<HandoffTicket> response =
                    controller.issue(null, idempotencyKey, makeIssueRequest(), withFeSessionCookie());

            // then: 만료된 경우 재발급
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(reissuedTicket);
            assertThat(response.getHeaders().getFirst("X-Idempotency-Replayed")).isNull();

            // 재발급 후 Redis 갱신 확인
            verify(valueOps).set(eq(redisKey), eq("ticket-reissued-001"), any());
        }

        @Test
        @DisplayName("Idempotency-Key 미전달 → 단순 발급, Redis set 미호출")
        void issue_noIdempotencyKey_issuesWithoutCaching() {
            // given
            HandoffTicket ticket = makeTicket("ticket-no-idem-001");
            given(handoffService.issue(any())).willReturn(ticket);

            // when
            ResponseEntity<HandoffTicket> response =
                    controller.issue(null, null, makeIssueRequest(), withFeSessionCookie());

            // then
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(ticket);

            // Redis 조회/저장 미호출 확인
            verify(valueOps, never()).get(anyString());
            verify(valueOps, never()).set(anyString(), any(), any());
        }

        @Test
        @DisplayName("Idempotency-Key 빈 문자열 → 단순 발급 (Redis 저장 없음)")
        void issue_blankIdempotencyKey_issuesWithoutCaching() {
            // given
            HandoffTicket ticket = makeTicket("ticket-blank-idem-001");
            given(handoffService.issue(any())).willReturn(ticket);

            // when
            ResponseEntity<HandoffTicket> response =
                    controller.issue(null, "   ", makeIssueRequest(), withFeSessionCookie());

            // then
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(valueOps, never()).get(anyString());
            verify(valueOps, never()).set(anyString(), any(), any());
        }

        @Test
        @DisplayName("X-Correlation-Id 헤더 전달 시 HandoffIssueCommand correlationId에 반영")
        void issue_correlationIdHeader_propagatedToCommand() {
            // given
            String correlationId = "corr-id-test-001";
            HandoffTicket ticket = makeTicket("ticket-corr-001");
            ArgumentCaptor<HandoffIssueCommand> cmdCaptor = ArgumentCaptor.forClass(HandoffIssueCommand.class);
            given(handoffService.issue(cmdCaptor.capture())).willReturn(ticket);

            // when
            controller.issue(correlationId, null, makeIssueRequest(), withFeSessionCookie());

            // then
            assertThat(cmdCaptor.getValue().getCorrelationId()).isEqualTo(correlationId);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ────────────────────────────────────────────────────────────────────────

    /** Fe-Session-Id HttpOnly 쿠키가 포함된 MockHttpServletRequest */
    private MockHttpServletRequest withFeSessionCookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("Fe-Session-Id", FE_SESSION_ID));
        return req;
    }

    /** 테스트용 FeSession (qimUserId 포함) */
    private FeSession makeFeSession(String qimUserId) {
        return FeSession.builder()
                .feSessionId(FE_SESSION_ID)
                .qimUserId(qimUserId)
                .authResultId("auth-result-test-001")
                .authLevel("L1")
                .build();
    }

    /** 테스트용 HandoffIssueRequest */
    private HandoffIssueRequest makeIssueRequest() {
        HandoffIssueRequest req = new HandoffIssueRequest();
        ReflectionTestUtils.setField(req, "agencyCode", "AGENCY-001");
        ReflectionTestUtils.setField(req, "authResultId", "auth-result-001");
        ReflectionTestUtils.setField(req, "authLevel", "L1");
        ReflectionTestUtils.setField(req, "providerCode", "KAKAO");
        ReflectionTestUtils.setField(req, "callbackUrl", "https://agency.example.com/callback");
        return req;
    }

    /** 테스트용 HandoffTicket */
    private HandoffTicket makeTicket(String ticketId) {
        return HandoffTicket.builder()
                .ticketId(ticketId)
                .agencyCode("AGENCY-001")
                .qimUserId(QIM_USER_ID)
                .issuedAt(Instant.now())
                .build();
    }
}
