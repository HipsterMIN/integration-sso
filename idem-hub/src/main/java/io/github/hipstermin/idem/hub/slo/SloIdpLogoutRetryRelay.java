package io.github.hipstermin.idem.hub.slo;

import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1.1: {@code idem_hub.slo_idp_logout_retry} 의 PENDING 을 지수 백오프로 재시도한다 ({@code WebhookDispatchOutboxRelay} 와 같은 SKIP LOCKED 방식).
 * 성공 → DONE, 실패 → retry_count+1 · next_retry_at = now + base·2^retry, max_retry 초과 → FAILED + 감사 {@code SLO_IDP_LOGOUT_FAILED}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "idem.hub.slo.retry.enabled", havingValue = "true", matchIfMissing = true)
public class SloIdpLogoutRetryRelay {

    private final JdbcTemplate jdbcTemplate;
    private final IdpSessionRevoker revoker;
    private final AuditLogPublisher auditLogPublisher;

    @Value("${idem.hub.slo.retry.batch-size:50}")
    private int batchSize = 50;

    @Value("${idem.hub.slo.retry.backoff-base-seconds:10}")
    private int backoffBaseSeconds = 10;

    @Scheduled(fixedDelayString = "${idem.hub.slo.retry.interval-ms:5000}", initialDelayString = "${idem.hub.slo.retry.initial-delay-ms:20000}")
    public void relay() {
        try {
            int n = relayOnce();
            if (n > 0) log.info("[SloRetryRelay] 재시도 {}건 처리", n);
        } catch (Exception e) {
            log.warn("[SloRetryRelay] 주기 실패(다음 주기에 재시도): {}", e.getMessage());
        }
    }

    /** @return 이번 주기에 시도한 건수 */
    @Transactional
    public int relayOnce() {
        List<Map<String, Object>> due = jdbcTemplate.queryForList("""
                SELECT retry_id, fe_session_id, qim_user_id, idp_sub, idp_sid, correlation_id, retry_count, max_retry
                FROM idem_hub.slo_idp_logout_retry
                WHERE status = 'PENDING' AND next_retry_at <= NOW()
                ORDER BY next_retry_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, batchSize);
        for (Map<String, Object> row : due) {
            attempt(row);
        }
        return due.size();
    }

    void attempt(Map<String, Object> row) {
        String id     = (String) row.get("retry_id");
        String user   = (String) row.get("qim_user_id");
        String cid    = row.get("correlation_id") != null ? (String) row.get("correlation_id") : id;
        int retry     = ((Number) row.get("retry_count")).intValue();
        int maxRetry  = ((Number) row.get("max_retry")).intValue();

        IdpSessionRevoker.Result r = revoker.revoke((String) row.get("idp_sub"), (String) row.get("idp_sid"), user, cid);
        if (r.success()) {
            jdbcTemplate.update("UPDATE idem_hub.slo_idp_logout_retry SET status='DONE', done_at=NOW(), updated_at=NOW(), last_error=NULL WHERE retry_id=?", id);
            log.info("[SloRetryRelay] IdP 세션 종료 성공(재시도 {}회째): qimUserId={} outcome={} cid={}", retry + 1, user, r.outcome(), cid);
            return;
        }
        int next = retry + 1;
        if (next >= maxRetry) {
            jdbcTemplate.update("UPDATE idem_hub.slo_idp_logout_retry SET status='FAILED', retry_count=?, updated_at=NOW(), last_error=? WHERE retry_id=?",
                    next, SloIdpLogoutRetryQueue.truncate(r.error()), id);
            log.error("[SloRetryRelay] IdP 세션 종료 최종 실패({}회) — Keycloak 세션이 남아 있을 수 있다: qimUserId={} cid={} err={}", next, user, cid, r.error());
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory("SESSION").eventAction("SLO_IDP_LOGOUT_FAILED")
                    .actorType("SYSTEM").actorId("idem-hub")
                    .resourceType("FE_SESSION").resourceId((String) row.get("fe_session_id"))
                    .correlationId(cid)
                    .metadata(Map.of("qimUserId", user, "retries", next, "lastError", r.error() != null ? r.error() : ""))
                    .build());
            return;
        }
        long delay = (long) backoffBaseSeconds << next;   // base·2^next
        jdbcTemplate.update("UPDATE idem_hub.slo_idp_logout_retry SET retry_count=?, next_retry_at=NOW() + (? * INTERVAL '1 second'), updated_at=NOW(), last_error=? WHERE retry_id=?",
                next, delay, SloIdpLogoutRetryQueue.truncate(r.error()), id);
        log.warn("[SloRetryRelay] IdP 세션 종료 재시도 실패({}/{}) — {}초 뒤: qimUserId={} cid={} err={}", next, maxRetry, delay, user, cid, r.error());
    }
}
