package io.github.hipstermin.idem.tenant.e2e;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.UserStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IdO {@code /api/v1/handoff/verify} 응답을 e2e 테스트 컨텍스트 내에서 모의하는 컨트롤러.
 *
 * <p>실제로 agency-stub 의 {@link io.github.hipstermin.idem.tenant.client.IdoVerifyClient} 가 호출하는
 * 외부 IdO 엔드포인트와 동일한 URL/메서드/응답 스키마를 흉내내어, agency-stub 의 진입
 * 컨트롤러부터 세션 생성까지 전 흐름을 검증한다.
 *
 * <p>q-sign → ido 흐름의 결과물(qimUserId / authLevel / providerCode / authResultId)을
 * {@link HandoffPayload} 의 subject + authContext 에 임베드하여 반환함으로써
 * "agency-stub → ido → q-sign → ido → handoff → agency" 사슬의 데이터 계약을 표현한다.
 *
 * <p><b>시나리오 제어</b>:
 * 테스트는 {@link #programTicket(String, ScenarioOutcome)} 으로 특정 ticketId 의
 * 응답 시나리오를 사전 등록한다. 등록되지 않은 ticketId 는 기본 APPROVED 응답.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handoff")
@RequiredArgsConstructor
@Profile("e2e-test")
public class MockIdoVerifyController {

    /** ticketId → 시나리오 매핑 (테스트마다 programTicket 으로 등록) */
    private static final Map<String, ScenarioOutcome> TICKET_PROGRAMS = new ConcurrentHashMap<>();

    /** ticketId → 호출 횟수 (재시도 시나리오 검증용) */
    private static final Map<String, Integer> TICKET_CALL_COUNTS = new ConcurrentHashMap<>();

    /** 마지막 verify 호출에 첨부된 X-Agency-Code / X-Agency-Key (보안 헤더 전파 검증) */
    private static volatile String lastAgencyCode;
    private static volatile String lastAgencyKey;
    private static volatile String lastCorrelationId;

    private final AtomicInteger mockIdoVerifyInvocations;

    @PostMapping("/verify")
    public ResponseEntity<HandoffPayload> verify(
            @RequestHeader(value = "X-Agency-Code", required = false) String agencyCode,
            @RequestHeader(value = "X-Agency-Key",  required = false) String agencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody VerifyRequest req) {

        mockIdoVerifyInvocations.incrementAndGet();
        lastAgencyCode    = agencyCode;
        lastAgencyKey     = agencyKey;
        lastCorrelationId = correlationId;

        String ticketId = req.ticketId();
        TICKET_CALL_COUNTS.merge(ticketId, 1, Integer::sum);

        ScenarioOutcome scenario = TICKET_PROGRAMS.getOrDefault(ticketId, ScenarioOutcome.defaultApproved());
        log.info("[MockIdoVerifyController] verify 호출 — ticketId={} scenario={} agencyCode={} cid={}",
                ticketId, scenario.kind(), agencyCode, correlationId);

        return switch (scenario.kind()) {
            case APPROVED      -> ResponseEntity.ok(buildApprovedPayload(ticketId, agencyCode, correlationId, scenario));
            case REJECTED      -> ResponseEntity.ok(buildStatePayload(ticketId, agencyCode, correlationId,
                                                                     HandoffPayload.HandoffState.REJECTED));
            case HOLD          -> ResponseEntity.ok(buildStatePayload(ticketId, agencyCode, correlationId,
                                                                     HandoffPayload.HandoffState.HOLD));
            case GUEST         -> ResponseEntity.ok(buildGuestPayload(ticketId, agencyCode, correlationId, scenario));
            case HTTP_500      -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            case HTTP_404      -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            case HTTP_403      -> ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            case HTTP_409      -> ResponseEntity.status(HttpStatus.CONFLICT).build();
        };
    }

    // ── 응답 빌더 ─────────────────────────────────────────────────────────────

    private HandoffPayload buildApprovedPayload(String ticketId, String agencyCode, String cid, ScenarioOutcome s) {
        return HandoffPayload.builder()
                .ticketId(ticketId)
                .correlationId(cid)
                .agencyCode(agencyCode)
                .policyVersion("1.0")
                .state(HandoffPayload.HandoffState.APPROVED)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .agencySubjectId(s.agencySubjectId())
                        .qimUserId(s.qimUserId())
                        .status(UserStatus.ACTIVE)
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(s.authLevel())
                        .providerCode(s.providerCode())
                        .authenticatedAt(Instant.now())
                        .authResultId(s.authResultId())
                        .build())
                .attributes(Map.of(
                        "name_masked",   "홍*동",
                        "mobile_masked", "010-****-1234"
                ))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(120))
                .build();
    }

    private HandoffPayload buildGuestPayload(String ticketId, String agencyCode, String cid, ScenarioOutcome s) {
        return HandoffPayload.builder()
                .ticketId(ticketId)
                .correlationId(cid)
                .agencyCode(agencyCode)
                .state(HandoffPayload.HandoffState.GUEST)
                .subject(HandoffPayload.SubjectIdentifier.builder()
                        .qimUserId(s.qimUserId())
                        .agencySubjectId(null)   // GUEST: 기관 매핑 없음
                        .status(UserStatus.ACTIVE)
                        .build())
                .authContext(HandoffPayload.AuthContext.builder()
                        .authLevel(s.authLevel())
                        .providerCode(s.providerCode())
                        .authenticatedAt(Instant.now())
                        .authResultId(s.authResultId())
                        .build())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(120))
                .build();
    }

    private HandoffPayload buildStatePayload(String ticketId, String agencyCode, String cid,
                                             HandoffPayload.HandoffState state) {
        return HandoffPayload.builder()
                .ticketId(ticketId)
                .correlationId(cid)
                .agencyCode(agencyCode)
                .state(state)
                .issuedAt(Instant.now())
                .build();
    }

    // ── 테스트 제어 API ──────────────────────────────────────────────────────

    /** ticketId 의 응답 시나리오를 사전 등록 */
    public static void programTicket(String ticketId, ScenarioOutcome scenario) {
        TICKET_PROGRAMS.put(ticketId, scenario);
    }

    /** 등록된 시나리오를 모두 지움 (@BeforeEach 에서 호출) */
    public static void resetPrograms() {
        TICKET_PROGRAMS.clear();
        TICKET_CALL_COUNTS.clear();
        lastAgencyCode = null;
        lastAgencyKey  = null;
        lastCorrelationId = null;
    }

    public static int callCountFor(String ticketId) {
        return TICKET_CALL_COUNTS.getOrDefault(ticketId, 0);
    }

    public static String lastAgencyCode()    { return lastAgencyCode; }
    public static String lastAgencyKey()     { return lastAgencyKey; }
    public static String lastCorrelationId() { return lastCorrelationId; }

    // ── DTO / 시나리오 모델 ──────────────────────────────────────────────────

    public record VerifyRequest(String ticketId) {}

    public enum ScenarioKind {
        APPROVED, REJECTED, HOLD, GUEST,
        HTTP_500, HTTP_404, HTTP_403, HTTP_409
    }

    /**
     * 시나리오 모델: q-sign 인증 결과(qimUserId/authLevel/providerCode/authResultId)와
     * ido handoff 상태(state)를 한 묶음으로 표현.
     */
    public record ScenarioOutcome(
            ScenarioKind          kind,
            String                qimUserId,
            String                agencySubjectId,
            AuthResult.AuthLevel  authLevel,
            String                providerCode,
            String                authResultId
    ) {
        public static ScenarioOutcome defaultApproved() {
            String qim = "qim-" + UUID.randomUUID();
            return new ScenarioOutcome(
                    ScenarioKind.APPROVED,
                    qim,
                    "agency-subject-" + UUID.randomUUID(),
                    AuthResult.AuthLevel.L2,
                    "NICE",
                    "auth-result-" + UUID.randomUUID()
            );
        }

        public static ScenarioOutcome approvedFor(String qimUserId, AuthResult.AuthLevel level, String provider) {
            return new ScenarioOutcome(
                    ScenarioKind.APPROVED,
                    qimUserId,
                    "agency-subject-" + UUID.randomUUID(),
                    level,
                    provider,
                    "auth-result-" + UUID.randomUUID()
            );
        }

        public static ScenarioOutcome rejected() {
            return new ScenarioOutcome(ScenarioKind.REJECTED, null, null, null, null, null);
        }

        public static ScenarioOutcome hold() {
            return new ScenarioOutcome(ScenarioKind.HOLD, null, null, null, null, null);
        }

        public static ScenarioOutcome guestFor(String qimUserId, AuthResult.AuthLevel level) {
            return new ScenarioOutcome(
                    ScenarioKind.GUEST,
                    qimUserId,
                    null,
                    level,
                    "NICE",
                    "auth-result-" + UUID.randomUUID()
            );
        }

        public static ScenarioOutcome http500() {
            return new ScenarioOutcome(ScenarioKind.HTTP_500, null, null, null, null, null);
        }
    }
}
