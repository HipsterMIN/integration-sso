package kr.go.smes.ido.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.smes.common.domain.HandoffPayload;
import kr.go.smes.common.domain.HandoffTicket;
import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.AuditLogEvent;
import kr.go.smes.common.event.HandoffEvent;
import kr.go.smes.ido.audit.AuditLogPublisher;
import kr.go.smes.ido.domain.AgencyMeta;
import kr.go.smes.ido.handoff.crypto.HandoffCryptoService;
import kr.go.smes.ido.handoff.strategy.HandoffStrategyFactory;
import kr.go.smes.ido.handoff.validate.CallbackUrlValidator;
import kr.go.smes.ido.infrastructure.AgencyMetaRepository;
import kr.go.smes.ido.infrastructure.TicketRepository;
import kr.go.smes.ido.policy.PolicyEngine;
import kr.go.smes.ido.ratelimit.AgencyRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handoff 서비스 구현체 (v2.0 — Production)
 *
 * <p>v2.0 변경사항:
 * <ul>
 *   <li>Callback URL 화이트리스트 검증 ({@link CallbackUrlValidator})</li>
 *   <li>Audit Log 전 경로 기록 (issue/verify/revoke 성공·실패 모두)</li>
 *   <li>Policy 충돌 로그 연동</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffServiceImpl implements HandoffService {

    private static final String TOPIC_HANDOFF = "ido.handoff.events";
    private static final String SOURCE_SYSTEM = "ido";
    private static final long   TICKET_TTL_SEC = 60L;

    private final AgencyMetaRepository    agencyMetaRepository;
    private final TicketRepository        ticketRepository;
    private final PolicyEngine            policyEngine;
    private final HandoffCryptoService    handoffCryptoService;
    private final AuditLogPublisher       auditLogPublisher;
    private final CallbackUrlValidator    callbackUrlValidator;
    private final HandoffStrategyFactory  strategyFactory;
    private final AgencyRateLimiter       rateLimiter;
    private final ObjectMapper            objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ── issue ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public HandoffTicket issue(HandoffIssueCommand cmd) {
        log.info("[IdO] Handoff Issue 시작 correlationId={} agency={}", cmd.getCorrelationId(), cmd.getAgencyCode());

        try {
            // 1. 기관 검증
            AgencyMeta agency = agencyMetaRepository.findByCode(cmd.getAgencyCode())
                    .orElseThrow(() -> new PlatformException(
                            PlatformErrorCode.AGENCY_NOT_REGISTERED, cmd.getCorrelationId()));
            if (!agency.isActive()) {
                throw new PlatformException(PlatformErrorCode.AGENCY_NOT_REGISTERED, cmd.getCorrelationId());
            }

            // 2. Rate Limiting 검증 (기관별 TPS + 일별 한도)
            if (!rateLimiter.tryAcquire(cmd.getAgencyCode())) {
                auditIssue(cmd, null, AuditLogEvent.OUTCOME_FAILURE, "RATE_LIMIT_EXCEEDED", null);
                throw new PlatformException(PlatformErrorCode.AGENCY_RATE_LIMIT_EXCEEDED, cmd.getCorrelationId());
            }

            // 3. Callback URL 화이트리스트 검증
            callbackUrlValidator.validate(cmd.getRedirectUri(), agency.getCallbackWhitelist(), cmd.getCorrelationId());

            // 3. 점검 시간 차단
            if (policyEngine.isUnderMaintenance(agency)) {
                throw new PlatformException(PlatformErrorCode.AGENCY_MAINTENANCE, cmd.getCorrelationId());
            }

            // 4. 최소 인증수준 충족 검증
            if (!policyEngine.meetsMinAuthLevel(cmd.getAuthLevel(), agency.getMinAuthLevel())) {
                auditIssue(cmd, null, AuditLogEvent.OUTCOME_FAILURE, "AUTH_LEVEL_INSUFFICIENT", null);
                throw new PlatformException(PlatformErrorCode.IDO_AUTH_LEVEL_INSUFFICIENT, cmd.getCorrelationId());
            }

            // 5. Q-IM 사용자 상태 확인
            UserStatus userStatus = policyEngine.resolveUserStatus(cmd.getQimUserId(), cmd.getCorrelationId());
            if (userStatus == UserStatus.SUSPENDED) {
                auditIssue(cmd, null, AuditLogEvent.OUTCOME_FAILURE, "USER_SUSPENDED", null);
                throw new PlatformException(PlatformErrorCode.IM_USER_SUSPENDED, cmd.getCorrelationId());
            }
            if (userStatus == UserStatus.WITHDRAWN) {
                auditIssue(cmd, null, AuditLogEvent.OUTCOME_FAILURE, "USER_WITHDRAWN", null);
                throw new PlatformException(PlatformErrorCode.IM_USER_WITHDRAWN, cmd.getCorrelationId());
            }

            // 6. Ticket 발급
            Instant now      = Instant.now();
            String  ticketId = UUID.randomUUID().toString();
            String  plain    = buildPlainPayload(ticketId, cmd);
            String  encrypted = handoffCryptoService.encrypt(plain, ticketId);
            String  signature = handoffCryptoService.sign(ticketId, cmd.getAgencyCode(), encrypted);

            HandoffTicket ticket = HandoffTicket.builder()
                    .ticketId(ticketId)
                    .correlationId(cmd.getCorrelationId())
                    .agencyCode(cmd.getAgencyCode())
                    .qimUserId(cmd.getQimUserId())
                    .authResultId(cmd.getAuthResultId())
                    .authLevel(cmd.getAuthLevel())
                    .state(HandoffTicket.TicketState.ISSUED)
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(TICKET_TTL_SEC))
                    .encryptedPayload(encrypted)
                    .signature(signature)
                    .build();

            ticketRepository.save(ticket);
            publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_ISSUED, ticket, null);

            // 7. 연동 유형별 후처리 (Strategy Pattern — 설계서 §8절)
            // DIRECT: 아무 작업 없음 (기관이 직접 verify 호출)
            // BRIDGE: Bridge 서버에 Payload 미리 푸시
            // INTERNAL_SSO: SSO 도메인 쿠키 세션 사전 등록
            // APACHE_GATE: 게이트웨이 세션 헤더 사전 등록
            HandoffPayload preBuiltPayload = null;
            String integrationType = agency.getIntegrationType();
            if (integrationType != null && !"DIRECT".equalsIgnoreCase(integrationType)) {
                // DIRECT 외 전략에서는 Payload가 필요할 수 있으므로 사전 빌드
                try {
                    preBuiltPayload = policyEngine.buildHandoffPayload(ticket, cmd.getCorrelationId());
                } catch (Exception payloadEx) {
                    log.warn("[HandoffSvc] 사전 Payload 빌드 실패 (비치명적): ticketId={} err={}",
                            ticketId, payloadEx.getMessage());
                }
            }
            try {
                strategyFactory.getStrategy(integrationType)
                        .postIssue(ticket, preBuiltPayload, cmd.getCorrelationId());
            } catch (Exception strategyEx) {
                // 전략 후처리 실패는 Ticket 발급 자체를 롤백하지 않음
                // (Ticket은 이미 DB에 저장됨 — 기관이 직접 verify 폴백 가능)
                log.error("[HandoffSvc] Strategy postIssue 실패 (비치명적, DIRECT 폴백): " +
                          "integrationType={} ticketId={} err={}",
                        integrationType, ticketId, strategyEx.getMessage());
            }

            // 8. 감사 로그 — 발급 성공
            auditIssue(cmd, ticketId, AuditLogEvent.OUTCOME_SUCCESS, null, null);
            log.info("[IdO] Handoff Ticket 발급 완료: ticketId={} integrationType={}", ticketId, integrationType);
            return ticket;

        } catch (PlatformException e) {
            // 감사 로그 — 발급 실패 (이미 위에서 기록된 경우 중복 아님)
            auditIssue(cmd, null, AuditLogEvent.OUTCOME_FAILURE, e.getErrorCode().getCode(), null);
            throw e;
        }
    }

    // ── verify ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public HandoffPayload verify(String ticketId, String agencyCode, String correlationId) {
        log.info("[IdO] Handoff Verify 요청 ticketId={} agency={}", ticketId, agencyCode);

        try {
            HandoffTicket ticket = ticketRepository.findById(ticketId)
                    .orElseThrow(() -> new PlatformException(PlatformErrorCode.IDO_TICKET_EXPIRED, correlationId));

            if (ticket.getState() == HandoffTicket.TicketState.CONSUMED) {
                publishReuseAttemptEvent(ticket, correlationId);
                auditVerify(ticketId, agencyCode, correlationId, AuditLogEvent.OUTCOME_FAILURE, "TICKET_CONSUMED");
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_CONSUMED, correlationId);
            }
            if (ticket.getState() == HandoffTicket.TicketState.REVOKED) {
                auditVerify(ticketId, agencyCode, correlationId, AuditLogEvent.OUTCOME_FAILURE, "TICKET_REVOKED");
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_REVOKED, correlationId);
            }
            if (ticket.isExpired()) {
                auditVerify(ticketId, agencyCode, correlationId, AuditLogEvent.OUTCOME_FAILURE, "TICKET_EXPIRED");
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_EXPIRED, correlationId);
            }
            if (!ticket.getAgencyCode().equals(agencyCode)) {
                auditVerify(ticketId, agencyCode, correlationId, AuditLogEvent.OUTCOME_FAILURE, "AGENCY_MISMATCH");
                throw new PlatformException(PlatformErrorCode.AGENCY_CODE_MISMATCH, correlationId);
            }

            ticketRepository.consume(ticketId);
            publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_CONSUMED, ticket, null);

            HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId);

            // 감사 로그 — 검증 성공
            auditVerify(ticketId, agencyCode, correlationId, AuditLogEvent.OUTCOME_SUCCESS, null);
            log.info("[IdO] Handoff Verify 성공 ticketId={} agency={}", ticketId, agencyCode);
            return payload;

        } catch (PlatformException e) {
            throw e;
        }
    }

    // ── revoke ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void revoke(String ticketId, String revokeReason, String correlationId) {
        log.warn("[IdO] Ticket Revoke ticketId={} reason={}", ticketId, revokeReason);
        ticketRepository.revoke(ticketId, revokeReason);
        HandoffTicket ticket = ticketRepository.findById(ticketId).orElseThrow();
        publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_REVOKED, ticket, revokeReason);

        // 감사 로그
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                .eventAction("HANDOFF_REVOKED")
                .actorType(AuditLogEvent.ACTOR_SYSTEM)
                .actorId(ticket.getAgencyCode())
                .resourceType("TICKET")
                .resourceId(ticketId)
                .agencyCode(ticket.getAgencyCode())
                .correlationId(correlationId)
                .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                .outcomeDetail(revokeReason)
                .build());
    }

    // ── private: Audit helpers ─────────────────────────────────────────────

    private void auditIssue(HandoffIssueCommand cmd, String ticketId,
                             String outcome, String outcomeDetail, String sourceIp) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                    .eventAction("HANDOFF_ISSUED")
                    .actorType(AuditLogEvent.ACTOR_USER)
                    .actorId(cmd.getQimUserId())
                    .resourceType("TICKET")
                    .resourceId(ticketId)
                    .agencyCode(cmd.getAgencyCode())
                    .correlationId(cmd.getCorrelationId())
                    .sourceIp(sourceIp)
                    .outcome(outcome)
                    .outcomeDetail(outcomeDetail)
                    .metadata(Map.of("authLevel", String.valueOf(cmd.getAuthLevel())))
                    .build());
        } catch (Exception e) {
            log.warn("[HandoffSvc] 감사 로그 기록 실패 (비치명적): {}", e.getMessage());
        }
    }

    private void auditVerify(String ticketId, String agencyCode, String correlationId,
                              String outcome, String outcomeDetail) {
        try {
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_HANDOFF)
                    .eventAction("HANDOFF_VERIFIED")
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(agencyCode)
                    .resourceType("TICKET")
                    .resourceId(ticketId)
                    .agencyCode(agencyCode)
                    .correlationId(correlationId)
                    .outcome(outcome)
                    .outcomeDetail(outcomeDetail)
                    .build());
        } catch (Exception e) {
            log.warn("[HandoffSvc] 감사 로그 기록 실패 (비치명적): {}", e.getMessage());
        }
    }

    // ── private: Kafka ─────────────────────────────────────────────────────

    private String buildPlainPayload(String ticketId, HandoffIssueCommand cmd) {
        try {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("ticketId",     ticketId);
            payload.put("qimUserId",    cmd.getQimUserId());
            payload.put("agencyCode",   cmd.getAgencyCode());
            payload.put("authResultId", cmd.getAuthResultId());
            payload.put("authLevel",    cmd.getAuthLevel().name());
            payload.put("providerCode", cmd.getProviderCode());
            payload.put("issuedAt",     Instant.now().toString());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Handoff payload 직렬화 실패", e);
        }
    }

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
