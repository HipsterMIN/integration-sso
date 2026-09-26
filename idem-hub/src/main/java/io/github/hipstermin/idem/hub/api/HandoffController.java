package io.github.hipstermin.idem.hub.api;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.api.dto.HandoffIssueRequest;
import io.github.hipstermin.idem.hub.api.dto.HandoffVerifyRequest;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.handoff.HandoffIssueCommand;
import io.github.hipstermin.idem.hub.handoff.HandoffService;
import io.github.hipstermin.idem.hub.infrastructure.TicketRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * IdO Handoff Issue / Verify API
 * 설계서 17.2 / 16.6절 참조
 *
 * <p><b>v2.4.0 P1 보안 수정</b>: Handoff 발급 시 {@code qimUserId}를 FE request body가 아닌
 * 서버 측 {@code Fe-Session-Id} HttpOnly 쿠키로 조회하도록 변경.
 * FE가 임의의 qimUserId를 전달하여 타 사용자 권한을 탈취하는 공격을 차단.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handoff")
@RequiredArgsConstructor
public class HandoffController {

    private static final String IDEMPOTENCY_KEY_PREFIX = "idem:idempotency:handoff:";
    private static final Duration IDEMPOTENCY_TTL       = Duration.ofDays(1);
    /** FE 세션 쿠키명 — §12.3 설계서 참조 */
    private static final String FE_SESSION_COOKIE_NAME  = "Fe-Session-Id";

    private final HandoffService handoffService;
    private final FeSessionService feSessionService;
    private final io.github.hipstermin.idem.hub.fe.session.FeSessionPolicyEnforcer feSessionPolicyEnforcer;
    private final TicketRepository ticketRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Handoff Ticket 발급
     * POST /api/v1/handoff/issue
     *
     * <p>§17.5 GAP-API-02: Idempotency-Key 헤더 지원 — 재시도 시 중복 Ticket 발급 방지.
     *
     * <p><b>v2.4.0 P1 보안 수정</b>: {@code qimUserId}를 FE request body가 아닌
     * {@code Fe-Session-Id} HttpOnly 쿠키로 조회하여 서버 측에서 추출.
     * FE가 임의의 qimUserId를 주입하는 공격 차단.
     *
     * <p><b>Idempotency-Key 처리 흐름</b>:
     * <ol>
     *   <li>헤더 수신 시 Redis에 "{@code ido:idempotency:handoff:{key}}" 키로 ticketId 조회</li>
     *   <li>HIT (ticketId 존재) → Redis TicketRepository에서 원본 HandoffTicket 조회</li>
     *   <li>Ticket이 아직 유효(ISSUED 상태)이면 200 + 원본 응답 재반환 (완전 멱등)</li>
     *   <li>Ticket이 만료/소비/없음이면 새로 발급 (재시도 허용)</li>
     *   <li>MISS → 신규 발급 후 ticketId를 Redis에 TTL 1일로 저장</li>
     * </ol>
     *
     * @return 200 OK + HandoffTicket (신규 발급 또는 기존 Ticket 재반환)
     */
    @PostMapping("/issue")
    public ResponseEntity<HandoffTicket> issue(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "Idempotency-Key",  required = false) String idempotencyKey,
            @Valid @RequestBody HandoffIssueRequest req,
            HttpServletRequest httpRequest) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        // ── P1 보안 수정: feSession 쿠키에서 qimUserId 서버 측 추출 ──────────────────
        String feSessionId = extractCookieValue(httpRequest, FE_SESSION_COOKIE_NAME);
        if (feSessionId == null || feSessionId.isBlank()) {
            log.warn("[HandoffController][P1] Fe-Session-Id 쿠키 없음 — 인증 세션 없는 Handoff 요청 거부: cid={}", cid);
            throw new PlatformException(PlatformErrorCode.IDO_SESSION_NOT_FOUND, cid);
        }
        FeSession feSession = feSessionService.findById(feSessionId)
                .orElseThrow(() -> {
                    log.warn("[HandoffController][P1] feSession 없음 또는 만료 — feSessionId={} cid={}", feSessionId, cid);
                    return new PlatformException(PlatformErrorCode.IDO_SESSION_NOT_FOUND, cid);
                });
        String qimUserId = feSession.getQimUserId();
        log.debug("[HandoffController][P1] feSession 서버 조회 완료: qimUserId={} feSessionId={} cid={}",
                qimUserId, feSessionId, cid);
        // ── P1 끝 ────────────────────────────────────────────────────────────────────

        // §17.5 GAP-API-02: Idempotency-Key 멱등 처리 — 기존 Ticket 재반환
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            Object cachedTicketId = redisTemplate.opsForValue().get(redisKey);
            if (cachedTicketId != null) {
                String ticketId = cachedTicketId.toString();
                log.info("[HandoffController] Idempotency-Key HIT — ticketId={} 재조회 시도: key={} cid={}",
                        ticketId, idempotencyKey, cid);

                // Redis에서 원본 HandoffTicket 조회 (TTL 내 유효 여부 확인)
                Optional<HandoffTicket> existing = ticketRepository.findById(ticketId);
                if (existing.isPresent()) {
                    log.info("[HandoffController] Idempotency-Key HIT — 기존 Ticket 재반환: ticketId={} cid={}",
                            ticketId, cid);
                    // 응답 헤더에 캐시 히트 여부 명시
                    return ResponseEntity.ok()
                            .header("X-Idempotency-Replayed", "true")
                            .body(existing.get());
                } else {
                    // Ticket 만료/소비됨 → 새로 발급 (idempotency 키는 갱신)
                    log.info("[HandoffController] Idempotency-Key HIT 이나 Ticket 만료 — 재발급: ticketId={} cid={}",
                            ticketId, cid);
                }
            }
        }

        HandoffIssueCommand cmd = HandoffIssueCommand.builder()
                .correlationId(cid)
                .agencyCode(req.getAgencyCode())
                .qimUserId(qimUserId)   // P1: feSession에서 서버 측 추출한 qimUserId
                .authResultId(req.getAuthResultId())
                .authLevel(AuthResult.AuthLevel.valueOf(req.getAuthLevel()))
                .providerCode(req.getProviderCode())
                .callbackUrl(req.getCallbackUrl())
                // P3 보안 수정: redirectUri를 callbackUrl과 동일하게 세팅.
                // HandoffServiceImpl이 callbackUrlValidator.validate(cmd.getRedirectUri(), ...)를
                // 호출하므로 redirectUri가 null이면 화이트리스트 검증이 스킵된다.
                .redirectUri(req.getCallbackUrl())
                .build();

        HandoffTicket ticket = handoffService.issue(cmd);

        // D3: 프로파일 policy.session 을 이 FE 세션에 적용 (발급 실패가 아니므로 예외는 안에서 삼킨다)
        feSessionPolicyEnforcer.applyForService(feSessionId, req.getAgencyCode(), cid);

        // Idempotency-Key 캐시 저장 (TTL 1일) — 신규 발급 또는 재발급 모두
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            redisTemplate.opsForValue().set(redisKey, ticket.getTicketId(), IDEMPOTENCY_TTL);
        }

        return ResponseEntity.ok(ticket);
    }

    /**
     * Handoff Ticket 검증 및 소비 (1회성 consumeOnce)
     * POST /api/v1/handoff/verify
     * 설계서 16.6절 — 기관이 호출, mTLS or 강한 API Key 필수
     */
    @PostMapping("/verify")
    public ResponseEntity<HandoffPayload> verify(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader("X-Agency-Code") String agencyCode,
            @Valid @RequestBody HandoffVerifyRequest req) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        HandoffPayload payload = handoffService.verify(req.getTicketId(), agencyCode, cid);
        return ResponseEntity.ok(payload);
    }

    // ── private ─────────────────────────────────────────────────────────────

    /**
     * HttpServletRequest 쿠키 배열에서 특정 이름의 쿠키 값 추출.
     * 쿠키가 없거나 해당 이름이 없으면 null 반환.
     */
    private String extractCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> cookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    /**
     * Ticket 강제 취소 (보안 운영용 — 관리자 세션 필수, {@code AdminAuthFilter} 가 DELETE 를 보호 경로로 본다)
     * DELETE /api/v1/handoff/{ticketId}
     *
     * <p>1.0.1 (3차 점검 H1): {@link io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal} 인자를 받아 필터를 지나친 요청도 401 로 끝난다.
     */
    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable String ticketId,
            @RequestParam String revokeReason,
            io.github.hipstermin.idem.hub.admin.auth.AdminPrincipal admin) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        log.info("[Handoff] 강제 취소 ticket={} by={} cid={}", ticketId, admin.username(), cid);
        handoffService.revoke(ticketId, revokeReason, cid);
        return ResponseEntity.noContent().build();
    }
}
