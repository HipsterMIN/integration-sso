package io.github.hipstermin.idem.hub.retention;

import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.metrics.SloMetrics;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개인정보 보존 기간 만료 시 자동 파기 스케줄러
 * 설계서 §15.2 개인정보 보호 / Sprint 2 P1-04
 *
 * <p>QimSpMemberEventHandler.onMemberWithdrawn()의 {@code [Phase 3 TODO]}를 실제 구현한다.
 *
 * <p><b>파기 대상</b>:
 * <ul>
 *   <li>status = 'WITHDRAWN' 이고 withdrawn_at 이 보존 기간(기본 1년) 경과한 회원</li>
 *   <li>inst_mbr_id_mapping 의 identifier_hash, mbr_uuid (PII 역추적 불가) NULL 처리</li>
 *   <li>auth_result 테이블의 해당 qimUserId 관련 PII 컬럼 NULL 처리</li>
 * </ul>
 *
 * <p><b>F-11 On/Off 제어 (이중 안전장치)</b>:
 * <pre>
 * IDEM_HUB_RETENTION_ENABLED=false (기본) → @Scheduled 실행되어도 즉시 return (데이터 변경 없음)
 * IDEM_HUB_RETENTION_ENABLED=true          → 실행 허용
 *
 * IDEM_HUB_RETENTION_DRY_RUN=true (기본)   → 대상 조회·로그만, 실제 DELETE/UPDATE 없음
 * IDEM_HUB_RETENTION_DRY_RUN=false         → 실제 파기 실행 (영구 삭제 — 복구 불가)
 * </pre>
 *
 * <p><b>🔴 운영 적용 체크리스트</b>:
 * <ol>
 *   <li>법무팀 보존 기간 확정 후 {@code IDEM_HUB_RETENTION_DAYS} 설정</li>
 *   <li>스테이징에서 {@code IDEM_HUB_RETENTION_DRY_RUN=true}로 대상 확인</li>
 *   <li>운영 적용 시 {@code IDEM_HUB_RETENTION_ENABLED=true, IDEM_HUB_RETENTION_DRY_RUN=false}</li>
 * </ol>
 *
 * <p><b>법적 주의사항</b>:
 * 보존 기간 365일은 임시값. 법무팀 검토 후 확정 필요.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalDataRetentionScheduler {

    // ── F-11 On/Off 플래그 (이중 안전장치) ──────────────────────────────────

    /**
     * 파기 스케줄러 활성 여부.
     * 기본 false — 명시적으로 true 설정 시에만 실행 (개발 DB 실수 삭제 방지).
     */
    @Value("${idem.hub.retention.enabled:${IDEM_HUB_RETENTION_ENABLED:false}}")
    private boolean retentionEnabled;

    /**
     * Dry-run 모드.
     * true(기본): 대상 조회 및 로그만, 실제 DELETE/UPDATE 없음.
     * false: 실제 파기 실행 (운영에서만 false 설정).
     */
    @Value("${idem.hub.retention.dry-run:${IDEM_HUB_RETENTION_DRY_RUN:true}}")
    private boolean dryRun;

    /** 보존 기간 (일). 기본 365일 = 1년. 법무팀 확정 전 임시값. */
    @Value("${idem.hub.retention.personal-data-days:${IDEM_HUB_RETENTION_DAYS:365}}")
    private int retentionDays;

    /** 회당 처리 건수 한도 (DB 부하 분산) */
    @Value("${idem.hub.retention.batch-size:${IDEM_HUB_RETENTION_BATCH_SIZE:100}}")
    private int batchSize;

    private final JdbcTemplate        jdbcTemplate;
    private final AuditLogPublisher   auditLogPublisher;
    private final SloMetrics          sloMetrics;

    // ════════════════════════════════════════════════════════════════════════

    /**
     * 매일 02:00 개인정보 파기 실행 (idem.hub.zone 기준, D3)
     *
     * <p>cron = "0 0 2 * * *" — 매일 새벽 2시
     *
     * <p><b>실행 조건</b>:
     * {@code IDEM_HUB_RETENTION_ENABLED=true} AND (@{code @Scheduled} 트리거)
     * → {@code IDEM_HUB_RETENTION_DRY_RUN=true}면 조회·로그만
     * → {@code IDEM_HUB_RETENTION_DRY_RUN=false}면 실제 파기
     */
    @Scheduled(cron = "${idem.hub.retention.cron:0 0 2 * * *}", zone = "${idem.hub.zone:UTC}")
    public void executeRetentionPolicy() {

        // ── F-11 Guard: enabled 체크 ─────────────────────────────────────
        if (!retentionEnabled) {
            log.debug("[RetentionScheduler] DISABLED — 개인정보 파기 비활성 (IDEM_HUB_RETENTION_ENABLED=false)");
            return;
        }

        Instant retentionCutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        if (dryRun) {
            log.info("[RetentionScheduler][DRY-RUN] 개인정보 파기 시뮬레이션 시작: " +
                     "retentionDays={} cutoff={} — 실제 삭제 없음 (IDEM_HUB_RETENTION_DRY_RUN=true)",
                    retentionDays, retentionCutoff);
            List<String> candidates = findExpiredWithdrawnMembers(retentionCutoff);
            log.info("[RetentionScheduler][DRY-RUN] 파기 대상 {}건 (실제 삭제 없음). " +
                     "운영 적용 시 IDEM_HUB_RETENTION_DRY_RUN=false 설정 필요.",
                    candidates.size());
            if (!candidates.isEmpty()) {
                log.info("[RetentionScheduler][DRY-RUN] 대상 instMbrId 샘플 (최대 5개): {}",
                        candidates.stream().limit(5).toList());
            }
            return;
        }

        // ── 실제 파기 실행 (dry-run=false) ──────────────────────────────
        log.info("[RetentionScheduler] 개인정보 파기 스케줄 시작: retentionDays={} cutoff={}",
                retentionDays, retentionCutoff);

        List<String> expiredInstMbrIds = findExpiredWithdrawnMembers(retentionCutoff);

        if (expiredInstMbrIds.isEmpty()) {
            log.info("[RetentionScheduler] 파기 대상 없음");
            return;
        }

        log.info("[RetentionScheduler] 파기 대상 {}건 처리 시작", expiredInstMbrIds.size());

        int successCount = 0;
        int failCount    = 0;

        for (String instMbrId : expiredInstMbrIds) {
            try {
                purgePersonalData(instMbrId);
                successCount++;
                sloMetrics.incrementPurgeSuccess(1);
            } catch (Exception e) {
                failCount++;
                sloMetrics.incrementPurgeFailed();
                log.error("[RetentionScheduler] 파기 실패 (개별 오류 — 계속 처리): instMbrId={} cause={}",
                        instMbrId, e.getMessage());
            }
        }

        log.info("[RetentionScheduler] 개인정보 파기 완료: 성공={} 실패={} 총={}",
                successCount, failCount, expiredInstMbrIds.size());

        publishRetentionAuditLog(successCount, failCount, retentionCutoff);
    }

    // ── private ─────────────────────────────────────────────────────────────

    /**
     * 보존 기간 만료된 탈퇴 회원 instMbrId 목록 조회
     */
    private List<String> findExpiredWithdrawnMembers(Instant retentionCutoff) {
        String sql = """
                SELECT inst_mbr_id
                FROM idem_hub.inst_mbr_id_mapping
                WHERE status = 'WITHDRAWN'
                  AND withdrawn_at IS NOT NULL
                  AND withdrawn_at < ?
                ORDER BY withdrawn_at ASC
                LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, String.class,
                java.sql.Timestamp.from(retentionCutoff), batchSize);
    }

    /**
     * 개별 회원 개인정보 파기 (단일 트랜잭션)
     */
    @Transactional
    public void purgePersonalData(String instMbrId) {
        int updatedMapping = jdbcTemplate.update("""
                UPDATE idem_hub.inst_mbr_id_mapping
                SET identifier_hash = NULL,
                    mbr_uuid        = NULL,
                    updated_at      = NOW()
                WHERE inst_mbr_id = ?
                  AND status = 'WITHDRAWN'
                """, instMbrId);

        if (updatedMapping == 0) {
            log.warn("[RetentionScheduler] inst_mbr_id_mapping 파기 대상 없음: instMbrId={}" +
                     " (이미 파기 또는 상태 불일치)", instMbrId);
        }

        String qimUserIdSql = "SELECT qim_user_id FROM idem_hub.inst_mbr_id_mapping WHERE inst_mbr_id = ?";
        List<String> qimUserIds = jdbcTemplate.queryForList(qimUserIdSql, String.class, instMbrId);

        int deletedAuthResults = 0;
        for (String qimUserId : qimUserIds) {
            deletedAuthResults += jdbcTemplate.update("""
                    DELETE FROM idem_hub.auth_result
                    WHERE qim_user_id = ?
                    """, qimUserId);
        }

        log.info("[RetentionScheduler] 파기 완료: instMbrId={} mappingUpdated={} authResultDeleted={}",
                instMbrId, updatedMapping, deletedAuthResults);
    }

    private void publishRetentionAuditLog(int successCount, int failCount, Instant cutoff) {
        try {
            auditLogPublisher.publish(
                    AuditLogPublisher.AuditEntry.builder()
                            .eventCategory(AuditLogEvent.CATEGORY_MEMBER)
                            .eventAction("PERSONAL_DATA_RETENTION_PURGE")
                            .actorType(AuditLogEvent.ACTOR_SYSTEM)
                            .actorId("RetentionScheduler")
                            .resourceType("PERSONAL_DATA")
                            .resourceId("batch")
                            .correlationId(UuidV7.generate())
                            .outcome(failCount == 0
                                    ? AuditLogEvent.OUTCOME_SUCCESS
                                    : AuditLogEvent.OUTCOME_PARTIAL)
                            .outcomeDetail("success=" + successCount + " fail=" + failCount)
                            .metadata(Map.of(
                                    "cutoff",       cutoff.toString(),
                                    "successCount", String.valueOf(successCount),
                                    "failCount",    String.valueOf(failCount),
                                    "dryRun",       String.valueOf(dryRun)
                            ))
                            .build());
        } catch (Exception e) {
            log.warn("[RetentionScheduler] 감사 로그 기록 실패 (비치명적): {}", e.getMessage());
        }
    }
}
