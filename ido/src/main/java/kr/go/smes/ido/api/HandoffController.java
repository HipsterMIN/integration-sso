package kr.go.smes.ido.api;

import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.AuthResult;
import kr.go.smes.common.util.CorrelationIdHolder;
import kr.go.smes.ido.api.dto.HandoffIssueRequest;
import kr.go.smes.ido.api.dto.HandoffVerifyRequest;
import kr.go.smes.ido.handoff.HandoffIssueCommand;
import kr.go.smes.ido.handoff.HandoffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.Duration;
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
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Handoff Ticket 발급
     * POST /api/v1/handoff/issue
     * §17.5 Idempotency-Key 헤더 지원 — 재시도 시 중복 Ticket 발급 방지 (GAP-API-02)
     */
    @PostMapping("/issue")
    public ResponseEntity<HandoffTicket> issue(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "Idempotency-Key",  required = false) String idempotencyKey,
            @Valid @RequestBody HandoffIssueRequest req) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        // §17.5 Idempotency-Key 멱등 처리
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String redisKey = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            Object cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                log.info("[HandoffController] Idempotency-Key 캐시 HIT — 이전 응답 반환: key={} cid={}",
                        idempotencyKey, cid);
                // 캐시된 응답은 ticketId 문자열로 저장 → 재발급 없이 204 반환
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
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

        // Idempotency-Key 캐시 저장 (TTL 1일)
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
