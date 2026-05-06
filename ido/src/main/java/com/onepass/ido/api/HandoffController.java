package com.onepass.ido.api;

import com.onepass.common.domain.HandoffPayload;
import com.onepass.common.domain.HandoffTicket;
import com.onepass.common.domain.AuthResult;
import com.onepass.common.util.CorrelationIdHolder;
import com.onepass.ido.api.dto.HandoffIssueRequest;
import com.onepass.ido.api.dto.HandoffVerifyRequest;
import com.onepass.ido.handoff.HandoffIssueCommand;
import com.onepass.ido.handoff.HandoffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * IdO Handoff Issue / Verify API
 * 설계서 17.2 / 16.6절 참조
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handoff")
@RequiredArgsConstructor
public class HandoffController {

    private final HandoffService handoffService;

    /**
     * Handoff Ticket 발급
     * POST /api/v1/handoff/issue
     */
    @PostMapping("/issue")
    public ResponseEntity<HandoffTicket> issue(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Valid @RequestBody HandoffIssueRequest req) {

        String cid = correlationId != null ? correlationId : CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

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
