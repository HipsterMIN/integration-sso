package kr.go.smes.ido.retention;

import kr.go.smes.ido.audit.AuditLogPublisher;
import kr.go.smes.common.event.AuditLogEvent;
import kr.go.smes.ido.metrics.SloMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import kr.go.smes.common.util.UuidV7;

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
 * <p><b>정책</b>:
 * <ul>
 *   <li>매일 새벽 02:00 (Asia/Seoul) 실행</li>
 *   <li>보존 기간: {@code ido.retention.personal-data-days} (기본 365일)</li>
 *   <li>파기 단위: 한 트랜잭션 내 최대 100건 (부하 분산)</li>
 *   <li>파기 실패 시 개별 건 오류 로그만 기록 — 전체 중단 없음</li>
 * </ul>
 *
 * <p><b>법적 주의사항</b>:
 * 보존 기간 365일은 임시값. 법무팀 검토 후 {@code ido.retention.personal-data-days} 설정값 확정 필요.
 * 파기 대상 선정 기준(withdrawn_at 기준 또는 updated_at 기준)도 법무팀 지침에 따라 조정.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalDataRetentionScheduler {

    /** 보존 기간 (일). 기본 365일 = 1년. 법무팀 확정 전 임시값. */
    @Value("${ido.retention.personal-data-days:365}")
    private int retentionDays;

    /** 회당 처리 건수 한도 (DB 부하 분산) */
    @Value("${ido.retention.batch-size:100}")
    private int batchSize;

    private final JdbcTemplate        jdbcTemplate;
    private final AuditLogPublisher   auditLogPublisher;
    private final SloMetrics          sloMetrics;

    // ════════════════════════════════════════════════════════════════════════

    /**
     * 매일 02:00 개인정보 파기 실행 (Asia/Seoul)
     *
     * <p>cron = "0 0 2 * * *" — 매일 새벽 2시
     * 파기 대상: WITHDRAWN 상태 + withdrawn_at < (현재 - retentionDays)
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void executeRetentionPolicy() {
        Instant retentionCutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
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

        // 감사 로그 (전체 처리 결과)
        publishRetentionAuditLog(successCount, failCount, retentionCutoff);
    }

    // ── private ─────────────────────────────────────────────────────────────

    /**
     * 보존 기간 만료된 탈퇴 회원 instMbrId 목록 조회
     *
     * <p>status = 'WITHDRAWN' AND withdrawn_at &lt; :cutoff
     * ORDER BY withdrawn_at ASC LIMIT :batchSize (선입선출)
     */
    private List<String> findExpiredWithdrawnMembers(Instant retentionCutoff) {
        String sql = """
                SELECT inst_mbr_id
                FROM ido.inst_mbr_id_mapping
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
     *
     * <p>파기 항목:
     * <ol>
     *   <li>inst_mbr_id_mapping — identifier_hash, mbr_uuid NULL 처리 (역추적 차단)</li>
     *   <li>auth_result — qimUserId 관련 레코드 삭제 (PoC 한정; 운영 시 legal hold 확인 필요)</li>
     * </ol>
     *
     * <p>inst_mbr_id 자체는 감사 추적을 위해 유지 (삭제 안 함).
     * 단, PII/CI 원본을 복원할 수 있는 필드만 NULL 처리.
     */
    @Transactional
    public void purgePersonalData(String instMbrId) {
        // ① inst_mbr_id_mapping — PII 역추적 필드 NULL 처리
        int updatedMapping = jdbcTemplate.update("""
                UPDATE ido.inst_mbr_id_mapping
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

        // ② auth_result — 해당 회원 레코드 삭제
        //    (법무팀 지침에 따라 soft-delete 또는 anonymize로 변경 가능)
        String qimUserIdSql = "SELECT qim_user_id FROM ido.inst_mbr_id_mapping WHERE inst_mbr_id = ?";
        List<String> qimUserIds = jdbcTemplate.queryForList(qimUserIdSql, String.class, instMbrId);

        int deletedAuthResults = 0;
        for (String qimUserId : qimUserIds) {
            deletedAuthResults += jdbcTemplate.update("""
                    DELETE FROM ido.auth_result
                    WHERE qim_user_id = ?
                    """, qimUserId);
        }

        log.info("[RetentionScheduler] 파기 완료: instMbrId={} mappingUpdated={} authResultDeleted={}",
                instMbrId, updatedMapping, deletedAuthResults);
    }

    // ── 감사 로그 ─────────────────────────────────────────────────────────────

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
                                    "cutoff",      cutoff.toString(),
                                    "successCount", String.valueOf(successCount),
                                    "failCount",   String.valueOf(failCount)
                            ))
                            .build());
        } catch (Exception e) {
            log.warn("[RetentionScheduler] 감사 로그 기록 실패 (비치명적): {}", e.getMessage());
        }
    }
}
