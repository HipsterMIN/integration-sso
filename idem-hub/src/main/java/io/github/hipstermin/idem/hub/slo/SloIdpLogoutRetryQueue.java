package io.github.hipstermin.idem.hub.slo;

import io.github.hipstermin.idem.common.util.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 1.1: SLO IdP 세션 종료 재시도 큐 적재 ({@code idem_hub.slo_idp_logout_retry}). 적재 실패는 삼키고 WARN — SLO 자체를 막지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SloIdpLogoutRetryQueue {

    private final JdbcTemplate jdbcTemplate;

    @Value("${idem.hub.slo.retry.max-retry:5}")
    private int maxRetry = 5;

    @Value("${idem.hub.slo.retry.backoff-base-seconds:10}")
    private int backoffBaseSeconds = 10;

    /** @return 적재된 retry_id, 실패면 null */
    public String enqueue(String feSessionId, String qimUserId, String idpSub, String idpSid, String correlationId, String lastError) {
        String id = UuidV7.generate();
        try {
            jdbcTemplate.update("""
                    INSERT INTO idem_hub.slo_idp_logout_retry
                        (retry_id, fe_session_id, qim_user_id, idp_sub, idp_sid, correlation_id,
                         status, retry_count, max_retry, next_retry_at, last_error, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NOW() + (? * INTERVAL '1 second'), ?, NOW(), NOW())
                    """,
                    id, feSessionId, qimUserId, idpSub, idpSid, correlationId, maxRetry, backoffBaseSeconds, truncate(lastError));
            log.warn("[SLO] IdP 세션 종료 실패 → 재시도 큐 적재: retryId={} qimUserId={} cid={} err={}", id, qimUserId, correlationId, lastError);
            return id;
        } catch (Exception e) {
            log.error("[SLO] 재시도 큐 적재 실패(비치명적 — Keycloak 세션이 남을 수 있다): qimUserId={} cid={} err={}", qimUserId, correlationId, e.getMessage());
            return null;
        }
    }

    static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
