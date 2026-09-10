package io.github.hipstermin.idem.hub.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.HandoffPayload;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.event.HandoffEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.handoff.crypto.HandoffCryptoService;
import io.github.hipstermin.idem.hub.handoff.strategy.HandoffStrategyFactory;
import io.github.hipstermin.idem.hub.handoff.validate.CallbackUrlValidator;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.QAuthzClient;
import io.github.hipstermin.idem.hub.infrastructure.TicketRepository;
import io.github.hipstermin.idem.hub.policy.PolicyEngine;
import io.github.hipstermin.idem.hub.policy.rule.PolicyContext;
import io.github.hipstermin.idem.hub.policy.rule.PolicyDecision;
import io.github.hipstermin.idem.hub.ratelimit.AgencyRateLimiter;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    /** 연합 인가 — 기관 스코프 역할 조회(fail-open) */
    private final QAuthzClient            qAuthzClient;

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

            // 3~5. 정책 평가 (S3 — 프로파일의 규칙 집합: MAINTENANCE → MIN_AUTH_LEVEL → ALLOWED_PROVIDERS → USER_STATUS)
            PolicyContext policyContext = PolicyContext.builder()
                    .tenantCode(cmd.getAgencyCode())
                    .authLevel(cmd.getAuthLevel())
                    .providerCode(cmd.getProviderCode())
                    .userStatus(() -> policyEngine.resolveUserStatus(cmd.getQimUserId(), cmd.getCorrelationId()))
                    .correlationId(cmd.getCorrelationId())
                    .build();
            PolicyDecision denial = policyEngine.evaluate(policyContext, true).firstDenial().orElse(null);
            if (denial != null) {
                auditIssue(cmd, null, AuditLogEvent.OUTCOME_FAILURE, denial.auditReason(), null);
                throw new PlatformException(denial.errorCode(), cmd.getCorrelationId(), denial.reason());
            }

            // 6. Ticket 발급
            Instant now      = Instant.now();
            String  ticketId = UuidV7.generate();
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
            IntegrationType integrationType = agency.getIntegrationType();
            if (integrationType != IntegrationType.DIRECT) {
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

    /**
     * Handoff Ticket 검증 + 소비 (Sprint α-2 / F4.1 + F4.5 적용)
     *
     * <p><b>단계 순서 (F4.5)</b> — 재시도 가능성 보장:
     * <ol>
     *   <li>Ticket 조회 + 상태/만료/기관 일치 검증</li>
     *   <li>HMAC-SHA256 서명 검증 + AAD 바인딩 검증 (F4.1)
     *       — encryptedPayload 와 ticketId AAD 가 발급 시점과 동일한지 확인</li>
     *   <li>{@code policyEngine.buildHandoffPayload()} — Q-IM HTTP 호출 가능 (실패 시 ticket 유지)</li>
     *   <li>{@code ticketRepository.consume()} — atomic CAS (F4.2) 로 ISSUED→CONSUMED</li>
     *   <li>Kafka {@code HANDOFF_CONSUMED} 이벤트 발행</li>
     * </ol>
     *
     * <p><b>F4.5 (비-원자 다단계) 해결</b>: 기존에는 consume → publish → buildPayload 순서였기에
     * Q-IM HTTP 실패 시 ticket 이 영구 CONSUMED 상태로 남아 사용자가 차단되었음.
     * 이제 buildPayload 가 먼저 실행되므로, Q-IM 실패 시 ticket 은 ISSUED 그대로 → 재시도 가능.
     * buildPayload ↔ consume 사이의 race window 는 F4.2 의 atomic CAS 가 차단.
     *
     * <p><b>F4.1 (dead code) 해결</b>: 기존 코드는 {@code cryptoService.verify()} 를 호출하지 않아
     * Redis 침해 또는 직렬화 오류로 변조된 payload 가 그대로 기관에 전달될 수 있었음.
     * 이제 buildPayload 직전에 서명을 검증하여 변조 차단.
     */
    @Override
    @Transactional
    public HandoffPayload verify(String ticketId, String agencyCode, String correlationId) {
        log.info("[IdO] Handoff Verify 요청 ticketId={} agency={}", ticketId, agencyCode);

        try {
            // ── ① Ticket 조회 + 사전 상태 검증 ──────────────────────────────────
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

            // ── ② HMAC 서명 검증 (F4.1) ─────────────────────────────────────────
            // - encryptedPayload 또는 signature 가 null 인 경우도 검증 실패로 처리
            //   (Issue 시 항상 set 되므로 정상 ticket 은 통과)
            if (ticket.getEncryptedPayload() == null || ticket.getSignature() == null
                    || !handoffCryptoService.verify(
                            ticketId, agencyCode,
                            ticket.getEncryptedPayload(), ticket.getSignature())) {
                publishSignatureInvalidEvent(ticket, correlationId);
                auditVerify(ticketId, agencyCode, correlationId,
                        AuditLogEvent.OUTCOME_FAILURE, "SIGNATURE_INVALID");
                log.error("[IdO] Handoff Verify 서명 검증 실패 ticketId={} agency={} corr={}",
                        ticketId, agencyCode, correlationId);
                throw new PlatformException(PlatformErrorCode.IDO_TICKET_SIGNATURE_INVALID, correlationId);
            }

            // ── ③ Payload 빌드 — Q-IM HTTP 호출 가능 (F4.5: consume 보다 먼저) ───
            // - 여기서 예외가 발생하면 ticket 은 ISSUED 그대로 → 사용자 재시도 가능
            //   (기존 코드는 이미 consume 후라서 영구 차단 발생)
            HandoffPayload payload = policyEngine.buildHandoffPayload(ticket, correlationId);

            // ── ④ Atomic consume (F4.2) ───────────────────────────────────────
            // - ISSUED → CONSUMED CAS. 동시 verify 시 한쪽만 성공.
            // - 실패 시 PlatformException(IDO_TICKET_CONSUMED/EXPIRED/...) throw.
            ticketRepository.consume(ticketId);

            // ── ⑤ Kafka HANDOFF_CONSUMED 이벤트 ────────────────────────────────
            publishHandoffEvent(HandoffEvent.TYPE_HANDOFF_CONSUMED, ticket, null);

            // ── ⑥ 감사 로그 — 검증 성공 ────────────────────────────────────────
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
            // 연합 인가: 기관 스코프 유효 역할(fail-open — 장애 시 빈 역할).
            // 플랫폼은 굵은 RBAC 역할만 배송, 세밀한 집행은 기관 PEP가 수행.
            payload.put("roles",        qAuthzClient.getEffectiveRoles(
                    cmd.getQimUserId(), cmd.getAgencyCode(), cmd.getCorrelationId()));
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

    /**
     * Sprint α-2 / F4.1 — Handoff verify 시 서명 검증 실패 이벤트 발행.
     * 감사 + 운영 알람 연계용 (SIEM 에서 다회 발생 시 기관 차단/조사 트리거).
     */
    private void publishSignatureInvalidEvent(HandoffTicket ticket, String correlationId) {
        try {
            HandoffEvent event = new HandoffEvent(
                    HandoffEvent.TYPE_SIGNATURE_INVALID, SOURCE_SYSTEM,
                    correlationId, ticket.getQimUserId(), 1L,
                    ticket.getTicketId(), ticket.getAgencyCode(),
                    ticket.getAuthResultId(), "SIGNATURE_INVALID", null);
            kafkaTemplate.send(TOPIC_HANDOFF, ticket.getQimUserId(), event);
        } catch (Exception e) {
            // Kafka 실패는 검증 결과(401) 자체를 막지 않음 — 감사 로그로 fallback
            log.warn("[HandoffSvc] SIGNATURE_INVALID 이벤트 발행 실패 (비치명적): {}", e.getMessage());
        }
    }
}
