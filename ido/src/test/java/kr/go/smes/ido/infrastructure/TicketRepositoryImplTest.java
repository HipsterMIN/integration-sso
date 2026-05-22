package kr.go.smes.ido.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Sprint α-2 / F4.2 — TicketRepositoryImpl.consume() 의 Lua atomic CAS 분기 검증.
 *
 * <p>실제 Lua 스크립트의 atomic 성은 Testcontainers(Redis) 기반 통합 테스트에서 검증되어야 함.
 * 본 클래스는 {@code redisTemplate.execute(script, keys, args)} 의 반환값에 따라
 * {@code consume()} 이 올바르게 분기 처리하는지 단위 테스트 수준에서 검증함.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TicketRepositoryImpl — F4.2 Atomic CAS consume()")
class TicketRepositoryImplTest {

    @Mock RedisTemplate<String, Object> redisTemplate;
    @Mock ValueOperations<String, Object> valueOps;
    @Mock JdbcTemplate jdbcTemplate;

    private TicketRepositoryImpl sut;
    private ObjectMapper objectMapper;

    private static final String TICKET_ID = "ticket-cas-001";
    private static final String AGENCY_CODE = "AGENCY-001";
    private static final String QIM_USER_ID = "qim-user-cas-001";
    private static final String KEY = "ido:ticket:" + TICKET_ID;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // Instant 직렬화

        sut = new TicketRepositoryImpl(redisTemplate, jdbcTemplate, objectMapper);

        given(redisTemplate.opsForValue()).willReturn(valueOps);
    }

    private HandoffTicket issuedTicketFixture() {
        return HandoffTicket.builder()
                .ticketId(TICKET_ID)
                .correlationId("corr-cas-001")
                .agencyCode(AGENCY_CODE)
                .qimUserId(QIM_USER_ID)
                .authResultId("auth-cas-001")
                .authLevel(AuthResult.AuthLevel.L2)
                .state(HandoffTicket.TicketState.ISSUED)
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(55))
                .encryptedPayload("enc")
                .signature("sig")
                .build();
    }

    private String issuedJson() throws Exception {
        return objectMapper.writeValueAsString(issuedTicketFixture());
    }

    private String stateJson(HandoffTicket.TicketState state) throws Exception {
        HandoffTicket t = HandoffTicket.builder()
                .ticketId(TICKET_ID)
                .correlationId("corr-cas-001")
                .agencyCode(AGENCY_CODE)
                .qimUserId(QIM_USER_ID)
                .authResultId("auth-cas-001")
                .authLevel(AuthResult.AuthLevel.L2)
                .state(state)
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(55))
                .encryptedPayload("enc")
                .signature("sig")
                .build();
        return objectMapper.writeValueAsString(t);
    }

    // ════════════════════════════════════════════════════════════════════════
    // F4.2 — Atomic CAS 분기 처리
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("F4.2 — Lua CAS 반환값별 동작")
    class CasBranchHandling {

        @Test
        @DisplayName("Lua=1 (성공) — 정상 완료, 감사 이력 갱신 호출")
        void casSuccess_completesAndUpdatesAudit() throws Exception {
            given(valueOps.get(KEY)).willReturn(issuedJson());
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                    .willReturn(1L);

            sut.consume(TICKET_ID);

            // 감사 이력 업데이트 SQL 1회 호출
            then(jdbcTemplate).should(times(1)).update(
                    org.mockito.ArgumentMatchers.anyString(), eq("CONSUMED"), eq(TICKET_ID));
        }

        @Test
        @DisplayName("Lua=0 (키 없음) — IDO_TICKET_EXPIRED")
        void casKeyMissing_throwsExpired() throws Exception {
            // 사전 GET 단계에서 이미 null 이면 빠른 실패 경로로 expired
            given(valueOps.get(KEY)).willReturn(null);

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_EXPIRED);

            // Lua 호출 자체가 일어나지 않음
            then(redisTemplate).should(never()).execute(any(RedisScript.class), anyList(), any());
            // 감사 이력 갱신도 호출 안 됨
            then(jdbcTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("사전 검사에서 CONSUMED 감지 — IDO_TICKET_CONSUMED (Lua 미호출)")
        void preCheckConsumed_throwsConsumedWithoutLua() throws Exception {
            given(valueOps.get(KEY)).willReturn(stateJson(HandoffTicket.TicketState.CONSUMED));

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_CONSUMED);

            then(redisTemplate).should(never()).execute(any(RedisScript.class), anyList(), any());
        }

        @Test
        @DisplayName("사전 검사에서 REVOKED 감지 — IDO_TICKET_REVOKED (Lua 미호출)")
        void preCheckRevoked_throwsRevokedWithoutLua() throws Exception {
            given(valueOps.get(KEY)).willReturn(stateJson(HandoffTicket.TicketState.REVOKED));

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_REVOKED);

            then(redisTemplate).should(never()).execute(any(RedisScript.class), anyList(), any());
        }

        @Test
        @DisplayName("Race condition — 사전 검사 통과 후 Lua 가 CONSUMED 반환 → IDO_TICKET_CONSUMED")
        void casRaceMismatchConsumed_throwsConsumed() throws Exception {
            // 사전 GET 시점에는 ISSUED 였지만, Lua 실행 직전 다른 winner 가 이미 CONSUMED 로 전이
            given(valueOps.get(KEY)).willReturn(issuedJson());
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                    .willReturn("CONSUMED");

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_CONSUMED);

            // 감사 이력은 갱신되지 않아야 함 (CAS 실패했으므로)
            then(jdbcTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Race condition — 사전 검사 통과 후 Lua 가 REVOKED 반환 → IDO_TICKET_REVOKED")
        void casRaceMismatchRevoked_throwsRevoked() throws Exception {
            given(valueOps.get(KEY)).willReturn(issuedJson());
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                    .willReturn("REVOKED");

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_REVOKED);

            then(jdbcTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Race condition — Lua 실행 직전 TTL 만료로 키 사라짐 → IDO_TICKET_EXPIRED")
        void casRaceMissingKey_throwsExpired() throws Exception {
            given(valueOps.get(KEY)).willReturn(issuedJson());
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                    .willReturn(0L);

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(PlatformException.class)
                    .extracting(e -> ((PlatformException) e).getErrorCode())
                    .isEqualTo(PlatformErrorCode.IDO_TICKET_EXPIRED);

            then(jdbcTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Lua 가 PARSE_ERROR 반환 — RuntimeException (운영 알람 대상)")
        void casParseError_throwsRuntimeException() throws Exception {
            given(valueOps.get(KEY)).willReturn(issuedJson());
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                    .willReturn("PARSE_ERROR");

            assertThatThrownBy(() -> sut.consume(TICKET_ID))
                    .isInstanceOf(RuntimeException.class);

            then(jdbcTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Lua 호출에 ISSUED 마커가 정확히 전달되는지 확인 (스크립트 호출 인자 검증)")
        void luaScriptInvokedWithIssuedStateMarker() throws Exception {
            given(valueOps.get(KEY)).willReturn(issuedJson());
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                    .willReturn(1L);

            sut.consume(TICKET_ID);

            // ARGV[1]="ISSUED", ARGV[4]="\"state\":\"ISSUED\"" 인지 검증
            ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg3 = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> arg4 = ArgumentCaptor.forClass(Object.class);
            then(redisTemplate).should().execute(
                    any(RedisScript.class),
                    eq(List.of(KEY)),
                    arg1.capture(), arg2.capture(), arg3.capture(), arg4.capture());

            org.assertj.core.api.Assertions.assertThat(arg1.getValue()).isEqualTo("ISSUED");
            org.assertj.core.api.Assertions.assertThat(arg4.getValue()).isEqualTo("\"state\":\"ISSUED\"");
            // arg2 는 CONSUMED JSON (state 마커 검증)
            org.assertj.core.api.Assertions.assertThat(String.valueOf(arg2.getValue()))
                    .contains("\"state\":\"CONSUMED\"");
        }
    }
}
