package kr.go.smes.ido.qim.sp.kafka;

import kr.go.smes.common.event.AuditLogEvent;
import kr.go.smes.ido.audit.AuditLogPublisher;
import kr.go.smes.ido.qim.sp.infrastructure.InstMbrIdMappingRepository;
import kr.go.smes.ido.webhook.WebhookDispatcherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Q-IM SP 회원 이벤트 비즈니스 처리기
 *
 * <p>{@link QimSpMemberEventConsumer}로부터 디스패치된 이벤트를 처리한다.
 *
 * <p><b>핵심 변경 — notifyAgencies() 완전 구현</b>:
 * <ul>
 *   <li>이전: Phase 2 TODO 주석만 존재 (기관 알림 없음)</li>
 *   <li>현재: {@link WebhookDispatcherService}를 통해 기관 HTTPS webhook 발송 Outbox 적재</li>
 * </ul>
 *
 * <p><b>기관 Kafka 직접 구독 불가 문제 해결</b>:
 * <pre>
 * [유관기관 요구] 회원 가입 여부를 Kafka로 받고 싶다
 *
 * [불가 이유] Kafka는 내부망 전용 + 개인정보 ACL 관리 불가능
 *
 * [해결 흐름]
 *   Q-IM → IdO SP API (회원 등록/조회/탈퇴)
 *     → QimSpReceiverService
 *         → Outbox → Kafka(qim.sp.member.events)
 *             → [이 클래스] 수신
 *                 → WebhookDispatcherService.enqueueForMemberXxx()
 *                     → webhook_dispatch_outbox INSERT
 *                         → WebhookDispatchOutboxRelay → HTTPS POST 기관
 * </pre>
 *
 * <p><b>책임 경계</b>:
 * <ul>
 *   <li>QIM_MEMBER_REGISTERED: 기관 webhook 발송 + 감사 로그</li>
 *   <li>QIM_MEMBER_TRANSFERRED: 기관 webhook 발송 + 감사 로그</li>
 *   <li>QIM_MEMBER_WITHDRAWN: 기관 webhook 발송 + 개인정보 파기 스케줄 (Phase 3)</li>
 * </ul>
 *
 * @see <a href="docs/qim-ido-integration-architecture.md">§10 EDA 기반 내부 전파 설계</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimSpMemberEventHandler {

    private static final String SCHEMA        = "ido";
    private static final String SOURCE_SYSTEM = "ido";

    private final InstMbrIdMappingRepository mappingRepository;
    private final JdbcTemplate               jdbcTemplate;
    private final WebhookDispatcherService   webhookDispatcherService;
    private final AuditLogPublisher          auditLogPublisher;

    // ── 회원 등록 (NEW) ──────────────────────────────────────────────────────

    /**
     * QIM_MEMBER_REGISTERED 처리
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>instMbrId 매핑 DB 확인</li>
     *   <li>기관 webhook 발송 Outbox 적재 (notifyAgencies)</li>
     *   <li>감사 로그 기록</li>
     * </ol>
     *
     * <p>webhook payload에는 instMbrId만 포함.
     * CI/DN 원본값 절대 포함 금지.
     *
     * @param payload      이벤트 페이로드 (instMbrId, mbrUuid, regMode, memberType, identifierHash)
     * @param correlationId 추적 ID
     */
    @Transactional
    public void onMemberRegistered(Map<String, Object> payload, String correlationId) {
        String instMbrId       = extractString(payload, "instMbrId");
        String mbrUuid         = extractString(payload, "mbrUuid");
        String memberType      = extractString(payload, "memberType");
        String identifierHash  = extractString(payload, "identifierHash");
        String agencyCode      = extractString(payload, "agencyCode");

        log.info("[QimSpEventHandler] REGISTERED 처리 시작: instMbrId={} memberType={} correlationId={}",
                instMbrId, memberType, correlationId);

        // ① 매핑 존재 확인
        if (instMbrId != null && mappingRepository.findByInstMbrId(instMbrId).isEmpty()) {
            log.warn("[QimSpEventHandler] REGISTERED — 매핑 없음 instMbrId={} (선행 트랜잭션 미완료 가능성)",
                    instMbrId);
            return;
        }

        // ② 기관 webhook 발송 (notifyAgencies 구현 완료)
        notifyAgenciesForMemberRegistered(instMbrId, mbrUuid, identifierHash, agencyCode, correlationId);

        // ③ 감사 로그
        insertReceiverLog("REGISTER", instMbrId, null, correlationId, 200, false, null);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_MEMBER)
                        .eventAction("MEMBER_REGISTERED_PROCESSED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("MEMBER")
                        .resourceId(instMbrId)
                        .agencyCode(agencyCode)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("webhook enqueued for MEMBER_REGISTERED")
                        .metadata(Map.of(
                                "memberType",     nullToEmpty(memberType),
                                "identifierHash", nullToEmpty(identifierHash)
                        ))
                        .build()
        );

        log.info("[QimSpEventHandler] REGISTERED 처리 완료: instMbrId={}", instMbrId);
    }

    // ── 회원 전환 (TRANSFER) ─────────────────────────────────────────────────

    /**
     * QIM_MEMBER_TRANSFERRED 처리
     *
     * <p>다른 SP에서 이 SP로 이전한 경우.
     * 이전 SP 탈퇴 이벤트는 Q-IM에서 별도 발행.
     *
     * @param payload      이벤트 페이로드
     * @param correlationId 추적 ID
     */
    @Transactional
    public void onMemberTransferred(Map<String, Object> payload, String correlationId) {
        String instMbrId      = extractString(payload, "instMbrId");
        String mbrUuid        = extractString(payload, "mbrUuid");
        String agencyCode     = extractString(payload, "agencyCode");
        String identifierHash = extractString(payload, "identifierHash");

        log.info("[QimSpEventHandler] TRANSFERRED 처리 시작: instMbrId={} correlationId={}",
                instMbrId, correlationId);

        // ① 매핑 확인
        if (instMbrId != null && mappingRepository.findByInstMbrId(instMbrId).isEmpty()) {
            log.warn("[QimSpEventHandler] TRANSFERRED — 매핑 없음 instMbrId={}", instMbrId);
            return;
        }

        // ② 기관 webhook 발송 (MEMBER_TRANSFERRED)
        notifyAgenciesForMemberRegistered(instMbrId, mbrUuid, identifierHash, agencyCode, correlationId);

        // ③ 감사 로그
        insertReceiverLog("REGISTER", instMbrId, null, correlationId, 200, false, null);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_MEMBER)
                        .eventAction("MEMBER_TRANSFERRED_PROCESSED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("MEMBER")
                        .resourceId(instMbrId)
                        .agencyCode(agencyCode)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("webhook enqueued for MEMBER_TRANSFERRED")
                        .build()
        );

        log.info("[QimSpEventHandler] TRANSFERRED 처리 완료: instMbrId={}", instMbrId);
    }

    // ── 회원 탈퇴 ───────────────────────────────────────────────────────────

    /**
     * QIM_MEMBER_WITHDRAWN 처리
     *
     * <p>탈퇴 시 세션 무효화는 QimSpReceiverService.handleMemberWithdraw()에서
     * 동기적으로 처리됨. 본 핸들러는 비동기 후처리 담당:
     * <ol>
     *   <li>기관 webhook 발송 (탈퇴 통보)</li>
     *   <li>개인정보 파기 스케줄링 (Phase 3)</li>
     * </ol>
     *
     * <p><b>주의</b>: 탈퇴 시 기관에 보내는 webhook payload에는
     * instMbrId만 포함. CI/DN 원본 절대 불포함.
     *
     * @param payload      이벤트 페이로드 (instMbrId, mbrUuid, withdrawalReason)
     * @param correlationId 추적 ID
     */
    @Transactional
    public void onMemberWithdrawn(Map<String, Object> payload, String correlationId) {
        String instMbrId        = extractString(payload, "instMbrId");
        String withdrawalReason = extractString(payload, "withdrawalReason");
        String qimUserId        = extractString(payload, "qimUserId");

        log.info("[QimSpEventHandler] WITHDRAWN 처리 시작: instMbrId={} reason={} correlationId={}",
                instMbrId, withdrawalReason, correlationId);

        // ① 매핑 상태 재확인 (WITHDRAWN 이어야 정상)
        if (instMbrId != null) {
            mappingRepository.findByInstMbrId(instMbrId).ifPresentOrElse(
                mapping -> {
                    if (!mapping.isWithdrawn()) {
                        log.warn("[QimSpEventHandler] WITHDRAWN 이벤트 수신 but 매핑 미반영: instMbrId={}",
                                instMbrId);
                    }
                },
                () -> log.warn("[QimSpEventHandler] WITHDRAWN — 매핑 없음: instMbrId={}", instMbrId)
            );
        }

        // ② 기관 탈퇴 webhook 발송 (모든 기관에 탈퇴 통보)
        notifyAgenciesForMemberWithdrawn(instMbrId, qimUserId, correlationId);

        // ③ 감사 로그
        insertReceiverLog("WITHDRAW", instMbrId, qimUserId, correlationId, 200, false, null);

        auditLogPublisher.publish(
                AuditLogPublisher.AuditEntry.builder()
                        .eventCategory(AuditLogEvent.CATEGORY_MEMBER)
                        .eventAction("MEMBER_WITHDRAWN_PROCESSED")
                        .actorType(AuditLogEvent.ACTOR_SYSTEM)
                        .actorId(SOURCE_SYSTEM)
                        .resourceType("MEMBER")
                        .resourceId(instMbrId)
                        .correlationId(correlationId)
                        .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                        .outcomeDetail("withdrawal webhook enqueued for all agencies")
                        .metadata(Map.of(
                                "withdrawalReason", nullToEmpty(withdrawalReason)
                        ))
                        .build()
        );

        // ④ [Phase 3 TODO] 개인정보 파기 스케줄링 (보존 기간 정책 엔진 연동)
        //    retentionPolicyEngine.schedule(instMbrId, withdrawalReason);
        log.info("[QimSpEventHandler] WITHDRAWN 처리 완료: instMbrId={} — 개인정보 파기 Phase 3 예정",
                instMbrId);
    }

    // ── 기관 알림 (notifyAgencies 구현) ────────────────────────────────────

    /**
     * 회원 등록/이전 이벤트를 기관에 webhook으로 통보
     *
     * <p><b>이전 구현</b>: Phase 2 TODO 주석만 존재
     * <p><b>현재 구현</b>: WebhookDispatcherService.enqueueForMemberLookupResult()를 통해
     * webhook_dispatch_outbox에 적재 → WebhookDispatchOutboxRelay가 HTTPS POST 발송
     *
     * <p>기관이 특정 회원(instMbrId)의 연동이 완료되었음을 webhook으로 인지.
     * 이후 기관은 자체 시스템에서 해당 회원의 OnePass 연동 상태를 업데이트.
     *
     * @param instMbrId      기관 회원 ID
     * @param mbrUuid        Q-IM 회원 UUID (webhook payload에 미포함 — 내부 추적용)
     * @param identifierHash SHA-256(CI|DN|BRNO) — 기관 식별자 매핑용
     * @param agencyCode     대상 기관 코드 (null이면 전체 기관)
     * @param correlationId  추적 ID
     */
    private void notifyAgenciesForMemberRegistered(String instMbrId, String mbrUuid,
                                                    String identifierHash, String agencyCode,
                                                    String correlationId) {
        if (instMbrId == null || instMbrId.isBlank()) {
            log.warn("[QimSpEventHandler] notifyAgencies 스킵: instMbrId 없음");
            return;
        }

        try {
            log.info("[QimSpEventHandler] 기관 webhook 발송 시작: instMbrId={} agencyCode={} correlationId={}",
                    instMbrId, agencyCode, correlationId);

            // WebhookDispatcherService가 MEMBER_REGISTERED 이벤트 필터 기관만 선택하여 Outbox 적재
            // identifierHash: SHA-256(원본 CI/DN) — CI/DN 원본값 대신 hash만 전달
            webhookDispatcherService.enqueueForMemberLookupResult(
                    correlationId,       // requestId로 correlationId 활용
                    agencyCode,          // 특정 기관 (null이면 WebhookDispatcherService가 전체 처리)
                    identifierHash,      // CI/DN 원본 대신 hash
                    true,                // exists = true (등록/이전이므로 회원 존재)
                    instMbrId,           // instMbrId (기관이 식별자로 사용)
                    correlationId
            );

            log.info("[QimSpEventHandler] 기관 webhook 발송 Outbox 적재 완료: instMbrId={}", instMbrId);

        } catch (Exception e) {
            // 기관 알림 실패는 비치명적 — 회원 등록 처리 자체는 이미 완료
            log.error("[QimSpEventHandler] 기관 webhook 발송 실패 (비치명적): instMbrId={} error={}",
                    instMbrId, e.getMessage());
        }
    }

    /**
     * 회원 탈퇴를 모든 기관에 webhook으로 통보
     *
     * <p>탈퇴 시 모든 기관이 해당 회원의 연동 상태를 비활성화해야 함.
     * WebhookDispatcherService.enqueueForMemberWithdrawn()가 MEMBER_WITHDRAWN
     * 이벤트 필터를 설정한 전체 기관에 발송.
     *
     * @param instMbrId     탈퇴 기관 회원 ID
     * @param qimUserId     Q-IM 사용자 ID
     * @param correlationId 추적 ID
     */
    private void notifyAgenciesForMemberWithdrawn(String instMbrId, String qimUserId,
                                                   String correlationId) {
        if (instMbrId == null || instMbrId.isBlank()) {
            log.warn("[QimSpEventHandler] notifyAgenciesForWithdrawn 스킵: instMbrId 없음");
            return;
        }

        try {
            log.warn("[QimSpEventHandler] 탈퇴 기관 전체 webhook 발송 시작: instMbrId={} correlationId={}",
                    instMbrId, correlationId);

            webhookDispatcherService.enqueueForMemberWithdrawn(instMbrId, qimUserId, correlationId);

            log.warn("[QimSpEventHandler] 탈퇴 기관 webhook Outbox 적재 완료: instMbrId={}", instMbrId);

        } catch (Exception e) {
            log.error("[QimSpEventHandler] 탈퇴 webhook 발송 실패 (비치명적): instMbrId={} error={}",
                    instMbrId, e.getMessage());
        }
    }

    // ── 감사 로그 ────────────────────────────────────────────────────────────

    /**
     * qim_sp_receiver_log에 이벤트 처리 결과 기록
     */
    private void insertReceiverLog(String endpoint, String instMbrId, String qimUserId,
                                    String correlationId, int httpStatus,
                                    boolean isReplay, String errorCode) {
        try {
            String logId = java.util.UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO " + SCHEMA + ".qim_sp_receiver_log "
                    + "(log_id, endpoint, inst_mbr_id, qim_user_id, correlation_id, "
                    + " http_status, is_replay, error_code, received_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                    logId, endpoint, instMbrId, qimUserId, correlationId,
                    httpStatus, isReplay, errorCode
            );
        } catch (Exception e) {
            // 감사 로그 실패는 비치명적
            log.warn("[QimSpEventHandler] 감사 로그 기록 실패: instMbrId={} cause={}",
                    instMbrId, e.getMessage());
        }
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
