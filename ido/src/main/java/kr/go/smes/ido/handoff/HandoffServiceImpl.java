package kr.go.smes.ido.handoff;

import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.HandoffEvent;
import kr.go.smes.ido.domain.AgencyMeta;
import kr.go.smes.ido.infrastructure.AgencyMetaRepository;
import kr.go.smes.ido.infrastructure.TicketRepository;
import kr.go.smes.ido.policy.PolicyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Handoff 서비스 구현체
 * 설계서 11.4 / 16장 참조
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffServiceImpl implements HandoffService {

    private static final String TOPIC_HANDOFF = "ido.handoff.events";
    private static final String SOURCE_SYSTEM = "ido";
    private static final long   TICKET_TTL_SEC = 60L;

    private final AgencyMetaRepository agencyMetaRepository;
    private final TicketRepository     ticketRepository;
    private final PolicyEngine         policyEngine;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    @Transactional
    public HandoffTicket issue(HandoffIssueCommand cmd) {
        log.info("[IdO] Handoff Issue 시작 correlationId={} agency={}", cmd.getCorrelationId(), cmd.getAgencyCode());

        // 1. 기관코드 등록 여부 검증
        AgencyMeta agency = agencyMetaRepository.findByCode(cmd.getAgencyCode())
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.AGENCY_NOT_REGISTERED, cmd.getCorrelationId()));

        if (!agency.isActive()) {
            throw new PlatformException(PlatformErrorCode.AGENCY_NOT_REGISTERED, cmd.getCorrelationId());
        }

        // 2. 점검 시간 차단
        if (policyEngine.isUnderMaintenance(agency)) {
            throw new PlatformException(PlatformErrorCode.AGENCY_MAINTENANCE, cmd.getCorrelationId());
        }

        // 3. 기관 최소 인증수준 충족 검증
        if (!policyEngine.meetsMinAuthLevel(cmd.getAuthLevel(), agency.getMinAuthLevel())) {
            throw new PlatformException(PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT, cmd.getCorrelationId());
        }

        // 4. Q-IM 사용자 상태 확인 (캐시 우선, 캐시 미스 시 Q-IM 직접 조회)
        UserStatus userStatus = policyEngine.resolveUserStatus(cmd.getQimUserId(), cmd.getCorrelationId());
        if (userStatus == UserStatus.SUSPENDED) {
            throw new PlatformException(PlatformErrorCode.IM_USER_SUSPENDED, cmd.getCorrelationId());
        }
        if (userStatus == UserStatus.WITHDRAWN) {
            throw new PlatformException(PlatformErrorCode.IM_USER_WITHDRAWN, cmd.getCorrelationId());
        }

        // 5. Ticket 발급 (AEAD 암호화 + 서명)
        Instant now = Instant.now();
        HandoffTicket ticket = HandoffTicket.builder()
                .ticketId(UUID.randomUUID().toString())
                .correlationId(cmd.getCorrelationId())
                .agencyCode(cmd.getAgencyCode())
                .qimUserId(cmd.getQimUserId())
                .authResultId(cmd.getAuthResultId())
                .authLevel(cmd.getAuthLevel())
                .state(HandoffTicket.TicketState.ISSUED)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(TICKET_TTL_SEC))
                // TODO: 실제 AEAD 암호화 적용
                .encryptedPayload("TODO:ENCRYPTED")
                // TODO: HMAC-SHA256 서명 적용
                .signature("TODO:SIGNATURE")
                .build();

        ticketRepository.save(ticket);

        // 6. Handoff Issue 이벤트 발행
        publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_ISSUED, ticket, null);

        log.info("[IdO] Handoff Ticket 발급 ticketId={}", ticket.getTicketId());
        return ticket;
    }

    @Override
    @Transactional
    public HandoffPayload verify(String ticketId, String agencyCode, String correlationId) {
        log.info("[IdO] Handoff Verify 요청 ticketId={} agency={}", ticketId, agencyCode);

        HandoffTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IDO_TICKET_EXPIRED, correlationId));

        // 상태 검증
        if (ticket.getState() == HandoffTicket.TicketState.CONSUMED) {
            // 재사용 시도 감사 로그
            publishReuseAttemptEvent(ticket, correlationId);
            throw new PlatformException(PlatformErrorCode.IDO_TICKET_CONSUMED, correlationId);
        }
        if (ticket.getState() == HandoffTicket.TicketState.REVOKED) {
            throw new PlatformException(PlatformErrorCode.IDO_TICKET_REVOKED, correlationId);
        }
        if (ticket.isExpired()) {
            throw new PlatformException(PlatformErrorCode.IDO_TICKET_EXPIRED, correlationId);
        }

        // 기관코드 일치 검증 (설계서 16.11절)
        if (!ticket.getAgencyCode().equals(agencyCode)) {
            throw new PlatformException(PlatformErrorCode.AGENCY_CODE_MISMATCH, correlationId);
        }

        // consumeOnce — CONSUMED 상태로 전이
        ticketRepository.consume(ticketId);
        publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_CONSUMED, ticket, null);

        // Subject Identifier Projection 생성 (기관향, 정본=Q-IM)
        HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId);

        log.info("[IdO] Handoff Verify 성공 ticketId={} agency={}", ticketId, agencyCode);
        return payload;
    }

    @Override
    @Transactional
    public void revoke(String ticketId, String revokeReason, String correlationId) {
        log.warn("[IdO] Ticket Revoke ticketId={} reason={}", ticketId, revokeReason);

        ticketRepository.revoke(ticketId, revokeReason);
        HandoffTicket ticket = ticketRepository.findById(ticketId).orElseThrow();
        publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_REVOKED, ticket, revokeReason);
    }

    // ── private ─────────────────────────────────────────────────────────────

    private void publishHandoffEvent(String type, HandoffTicket ticket, String revokeReason) {
        HandoffEvent event = new HandoffEvent(
                type, SOURCE_SYSTEM,
                ticket.getCorrelationId(), ticket.getQimUserId(), 1L,
                ticket.getTicketId(), ticket.getAgencyCode(),
                ticket.getAuthResultId(), ticket.getState().name(), revokeReason);
        kafkaTemplate.send(TOPIC_HANDOFF, ticket.getQimUserId(), event);
    }

    private void publishReuseAttemptEvent(HandoffTicket ticket, String correlationId) {
        HandoffEvent event = new HandoffEvent(
                HandoffEvent.TYPE_REUSE_ATTEMPT, SOURCE_SYSTEM,
                correlationId, ticket.getQimUserId(), 1L,
                ticket.getTicketId(), ticket.getAgencyCode(),
                ticket.getAuthResultId(), "REUSE_ATTEMPT", null);
        kafkaTemplate.send(TOPIC_HANDOFF, ticket.getQimUserId(), event);
    }
}
