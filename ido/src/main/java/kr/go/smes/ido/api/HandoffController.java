package kr.go.smes.ido.api;

import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.api.dto.HandoffIssueRequest;
import kr.go.smes.ido.api.dto.HandoffVerifyRequest;
import kr.go.smes.ido.handoff.HandoffIssueCommand;
import kr.go.smes.ido.handoff.HandoffService;
import kr.go.smes.ido.infrastructure.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * IdO Handoff Issue / Verify API
 * 설계서 17.2 / 16.6절 참조
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handoff")
@RequiredArgsConstructor
public class HandoffController {

    private static final String IDEMPOTENCY_KEY_PREFIX = "ido:idempotency:handoff:";
    private static final Duration IDEMPOTENCY_TTL       = Duration.ofDays(1);

    private final HandoffService handoffService;
    private final TicketRepository ticketRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Handoff Ticket 발급
     * POST /api/v1/handoff/issue
     *
     * <p>§17.5 GAP-API-02: Idempotency-Key 헤더 지원 — 재시도 시 중복 Ticket 발급 방지.
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
            @Valid @RequestBody HandoffIssueRequest req) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

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
                .qimUserId(req.getQimUserId())
                .authResultId(req.getAuthResultId())
                .authLevel(AuthResult.AuthLevel.valueOf(req.getAuthLevel()))
                .providerCode(req.getProviderCode())
                .callbackUrl(req.getCallbackUrl())
                .build();

        HandoffTicket ticket = handoffService.issue(cmd);

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

    /**
     * Ticket 강제 취소 (보안 운영용)
     * DELETE /api/v1/handoff/{ticketId}
     */
    @DeleteMapping("/{ticketId}")
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @PathVariable String ticketId,
            @RequestParam String revokeReason) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        handoffService.revoke(ticketId, revokeReason, cid);
        return ResponseEntity.noContent().build();
    }
}
