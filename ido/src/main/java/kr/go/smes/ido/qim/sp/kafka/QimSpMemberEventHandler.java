package kr.go.smes.ido.qim.sp.kafka;

import kr.go.smes.ido.domain.AgencyMeta;
import kr.go.smes.ido.qim.sp.infrastructure.InstMbrIdMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Q-IM SP 회원 이벤트 비즈니스 처리기
 *
 * {@link QimSpMemberEventConsumer}로부터 디스패치된 이벤트를 처리한다.
 *
 * <p><b>책임 경계</b>:
 * <ul>
 *   <li>QIM_MEMBER_REGISTERED / TRANSFERRED: 유관기관 어댑터 알림 준비, 정책 엔진 캐시 갱신</li>
 *   <li>QIM_MEMBER_WITHDRAWN: 세션 무효화 확인, 유관기관 탈퇴 전파 이벤트 발행 준비</li>
 * </ul>
 *
 * <p><b>PoC 단계 구현 범위</b>:
 * <ul>
 *   <li>구조 및 로깅 완비</li>
 *   <li>유관기관(agency) 실제 알림은 Phase 2에서 AgencyAdapterService 연동 예정</li>
 *   <li>감사 로그 {@code ido.qim_sp_receiver_log} 는 Controller 레이어에서 처리</li>
 * </ul>
 *
 * @see <a href="docs/qim-ido-integration-architecture.md">§10 EDA 기반 내부 전파 설계</a>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QimSpMemberEventHandler {

    private static final String SCHEMA = "ido";

    private final InstMbrIdMappingRepository mappingRepository;
    private final JdbcTemplate jdbcTemplate;

    // ── 회원 등록 (NEW) ──────────────────────────────────────────────────────

    /**
     * QIM_MEMBER_REGISTERED 처리
     *
     * <p>처리 흐름:
     * <ol>
     *   <li>instMbrId 매핑 DB 확인 (이미 QimSpReceiverService에서 저장됨)</li>
     *   <li>유관기관 노티 대상 목록 조회 (agency_meta.qim_sp_notified=true)</li>
     *   <li>[Phase 2] AgencyAdapterService를 통한 유관기관 알림 발행</li>
     * </ol>
     *
     * @param payload      이벤트 페이로드 (instMbrId, mbrUuid, regMode, memberType, identifierHash)
     * @param correlationId 추적 ID
     */
    @Transactional
    public void onMemberRegistered(Map<String, Object> payload, String correlationId) {
        String instMbrId  = extractString(payload, "instMbrId");
        String mbrUuid    = extractString(payload, "mbrUuid");
        String memberType = extractString(payload, "memberType");

        log.info("[QimSpEventHandler] REGISTERED 처리 시작 instMbrId={} memberType={} correlationId={}",
                instMbrId, memberType, correlationId);

        // ① 매핑 존재 확인 — 없으면 경고 후 종료 (선행 트랜잭션이 롤백됐을 가능성)
        if (instMbrId != null && mappingRepository.findByInstMbrId(instMbrId).isEmpty()) {
            log.warn("[QimSpEventHandler] REGISTERED — 매핑 없음 instMbrId={} (선행 트랜잭션 미완료 가능성)",
                    instMbrId);
            return;
        }

        // ② 감사 로그 기록
        insertReceiverLog("REGISTER", instMbrId, null, correlationId, 200, false, null);

        // ③ [Phase 2 TODO] 유관기관 어댑터 알림
        //    notifyAgencies(instMbrId, mbrUuid, "MEMBER_REGISTERED", correlationId);
        log.info("[QimSpEventHandler] REGISTERED 처리 완료 — 유관기관 알림 Phase 2 예정. instMbrId={}",
                instMbrId);
    }

    // ── 회원 전환 (TRANSFER) ─────────────────────────────────────────────────

    /**
     * QIM_MEMBER_TRANSFERRED 처리
     *
     * <p>TRANSFER는 기존 SP에서 이 SP로 이전한 경우이므로
     * 이전 SP 탈퇴 처리 여부 확인이 필요하다. (Phase 2 구현)
     *
     * @param payload      이벤트 페이로드
     * @param correlationId 추적 ID
     */
    @Transactional
    public void onMemberTransferred(Map<String, Object> payload, String correlationId) {
        String instMbrId = extractString(payload, "instMbrId");
        String mbrUuid   = extractString(payload, "mbrUuid");

        log.info("[QimSpEventHandler] TRANSFERRED 처리 시작 instMbrId={} correlationId={}",
                instMbrId, correlationId);

        // ① 매핑 확인
        if (instMbrId != null && mappingRepository.findByInstMbrId(instMbrId).isEmpty()) {
            log.warn("[QimSpEventHandler] TRANSFERRED — 매핑 없음 instMbrId={}", instMbrId);
            return;
        }

        // ② 감사 로그
        insertReceiverLog("REGISTER", instMbrId, null, correlationId, 200, false, null);

        // ③ [Phase 2 TODO] 이전 SP 탈퇴 + 유관기관 전환 알림
        //    notifyAgencies(instMbrId, mbrUuid, "MEMBER_TRANSFERRED", correlationId);
        log.info("[QimSpEventHandler] TRANSFERRED 처리 완료 — TRANSFER 후처리 Phase 2 예정. instMbrId={}",
                instMbrId);
    }

    // ── 회원 탈퇴 ───────────────────────────────────────────────────────────

    /**
     * QIM_MEMBER_WITHDRAWN 처리
     *
     * <p>탈퇴 세션 무효화는 QimSpReceiverService.handleMemberWithdraw() 에서
     * 동기적으로 처리됨. 본 핸들러는 비동기 후처리(유관기관 전파)를 담당한다.
     *
     * @param payload      이벤트 페이로드 (instMbrId, mbrUuid, withdrawalReason)
     * @param correlationId 추적 ID
     */
    @Transactional
    public void onMemberWithdrawn(Map<String, Object> payload, String correlationId) {
        String instMbrId       = extractString(payload, "instMbrId");
        String withdrawalReason = extractString(payload, "withdrawalReason");

        log.info("[QimSpEventHandler] WITHDRAWN 처리 시작 instMbrId={} reason={} correlationId={}",
                instMbrId, withdrawalReason, correlationId);

        // ① 매핑 상태 재확인 (WITHDRAWN 이어야 정상)
        if (instMbrId != null) {
            mappingRepository.findByInstMbrId(instMbrId).ifPresentOrElse(
                mapping -> {
                    if (!mapping.isWithdrawn()) {
                        log.warn("[QimSpEventHandler] WITHDRAWN 이벤트 수신했으나 매핑 상태 미반영 instMbrId={}",
                                instMbrId);
                    }
                },
                () -> log.warn("[QimSpEventHandler] WITHDRAWN — 매핑 없음 instMbrId={}", instMbrId)
            );
        }

        // ② 감사 로그
        insertReceiverLog("WITHDRAW", instMbrId, null, correlationId, 200, false, null);

        // ③ [Phase 3 TODO] 개인정보 파기 스케줄링 (보존 기간 정책 엔진 연동)
        //    retentionPolicyEngine.schedule(instMbrId, withdrawalReason);
        // ④ [Phase 3 TODO] 유관기관 탈퇴 전파
        //    notifyAgencies(instMbrId, null, "MEMBER_WITHDRAWN", correlationId);
        log.info("[QimSpEventHandler] WITHDRAWN 처리 완료 — 개인정보 파기/유관기관 전파 Phase 3 예정. instMbrId={}",
                instMbrId);
    }

    // ── 감사 로그 ────────────────────────────────────────────────────────────

    /**
     * qim_sp_receiver_log 에 이벤트 처리 결과 기록
     * 멱등 재호출도 포함 (is_replay 구분)
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
            // 감사 로그 실패는 비치명적 — 서비스 처리 중단하지 않음
            log.warn("[QimSpEventHandler] 감사 로그 기록 실패 (비치명적) instMbrId={} cause={}",
                    instMbrId, e.getMessage());
        }
    }

    // ── 유틸 ────────────────────────────────────────────────────────────────

    private String extractString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
