package kr.go.smes.ido.broker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import kr.go.smes.common.util.UuidV7;

/**
 * broker_audit_log 기록 서비스 (P1 — GAP 마감)
 *
 * <p><b>설계 원칙</b>:
 * <ol>
 *   <li>DB 직접 INSERT — Kafka 의존 없이 audit 기록 보장</li>
 *   <li>{@code @Async} 비동기 처리 — 브로커 인증 흐름 블로킹 없음</li>
 *   <li>예외는 warn 로그만 — 감사 실패가 인증 흐름을 중단하면 안 됨</li>
 * </ol>
 *
 * <p><b>호출 포인트</b>:
 * <ul>
 *   <li>{@link kr.go.smes.ido.broker.keycloak.KeycloakOidcService} — REDIRECT / CALLBACK / COMPLETE / FAIL</li>
 *   <li>{@link kr.go.smes.ido.broker.nonoidc.NonOidcAuthService} — COMPLETE / FAIL</li>
 *   <li>{@link kr.go.smes.ido.broker.nonoidc.NonOidcBrokerAdapter} — REDIRECT / CALLBACK</li>
 * </ul>
 *
 * <p><b>테이블</b>: {@code ido.broker_audit_log} (V6 마이그레이션, V10에서 인덱스 보강)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerAuditLogService {

    private final JdbcTemplate jdbcTemplate;

    // ────────────────────────────────────────────────────────────────────
    // 액션 상수 (V6 CHECK constraint 와 일치)
    // ────────────────────────────────────────────────────────────────────

    /** 사용자를 외부 IdP로 리다이렉트 */
    public static final String ACTION_REDIRECT  = "REDIRECT";
    /** 외부 IdP 콜백 수신 */
    public static final String ACTION_CALLBACK  = "CALLBACK";
    /** 인증 완료 (AuthResult 저장 후) */
    public static final String ACTION_COMPLETE  = "COMPLETE";
    /** 인증 실패 */
    public static final String ACTION_FAIL      = "FAIL";
    /** 인증 타임아웃 */
    public static final String ACTION_TIMEOUT   = "TIMEOUT";

    // ────────────────────────────────────────────────────────────────────
    // 공개 API
    // ────────────────────────────────────────────────────────────────────

    /**
     * broker_audit_log 비동기 기록
     *
     * @param entry 기록할 감사 엔트리
     */
    @Async("auditExecutor")
    public void record(AuditEntry entry) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO ido.broker_audit_log
                        (log_id, correlation_id,
                         provider_code, provider_type, provider_tx_id,
                         broker_mode, action,
                         identifier_hash, auth_level,
                         error_code, error_detail,
                         client_ip, fe_session_id,
                         created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (log_id) DO NOTHING
                    """,
                    UuidV7.generate(),
                    entry.correlationId(),
                    entry.providerCode(),
                    entry.providerType() != null ? entry.providerType() : "STANDARD_OIDC",
                    entry.providerTxId(),
                    entry.brokerMode() != null ? entry.brokerMode() : "keycloak",
                    entry.action(),
                    entry.identifierHash(),
                    entry.authLevel(),
                    entry.errorCode(),
                    truncate(entry.errorDetail(), 500),
                    entry.clientIp(),
                    entry.feSessionId()
            );
            log.debug("[BrokerAuditLogService] 기록 완료: correlationId={} action={}",
                    entry.correlationId(), entry.action());
        } catch (Exception e) {
            // 감사 로그 실패는 인증 흐름을 차단하지 않음 (비치명적)
            log.warn("[BrokerAuditLogService] 기록 실패 (비치명적): correlationId={} action={} err={}",
                    entry.correlationId(), entry.action(), e.getMessage());
        }
    }

    /**
     * COMPLETE 액션 기록 (인증 성공 완료)
     * 자주 사용되는 패턴을 위한 편의 메서드
     */
    @Async("auditExecutor")
    public void recordComplete(String correlationId, String providerCode, String providerType,
                                String providerTxId, String identifierHash, String authLevel,
                                String brokerMode, String clientIp) {
        record(AuditEntry.builder()
                .correlationId(correlationId)
                .providerCode(providerCode)
                .providerType(providerType)
                .providerTxId(providerTxId)
                .identifierHash(identifierHash)
                .authLevel(authLevel)
                .brokerMode(brokerMode)
                .action(ACTION_COMPLETE)
                .clientIp(clientIp)
                .build());
    }

    /**
     * FAIL 액션 기록 (인증 실패)
     * 자주 사용되는 패턴을 위한 편의 메서드
     */
    @Async("auditExecutor")
    public void recordFail(String correlationId, String providerCode, String providerType,
                            String brokerMode, String errorCode, String errorDetail, String clientIp) {
        record(AuditEntry.builder()
                .correlationId(correlationId)
                .providerCode(providerCode)
                .providerType(providerType)
                .brokerMode(brokerMode)
                .action(ACTION_FAIL)
                .errorCode(errorCode)
                .errorDetail(errorDetail)
                .clientIp(clientIp)
                .build());
    }

    // ────────────────────────────────────────────────────────────────────
    // 유틸
    // ────────────────────────────────────────────────────────────────────

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }

    // ────────────────────────────────────────────────────────────────────
    // 감사 엔트리 레코드
    // ────────────────────────────────────────────────────────────────────

    /**
     * broker_audit_log 기록용 DTO (불변 레코드)
     *
     * <p>Builder 패턴:
     * <pre>{@code
     * BrokerAuditLogService.AuditEntry.builder()
     *     .correlationId(correlationId)
     *     .providerCode("KAKAO_OIDC")
     *     .providerType("STANDARD_OIDC")
     *     .action(BrokerAuditLogService.ACTION_COMPLETE)
     *     .identifierHash(identifierHash)
     *     .authLevel("L2")
     *     .brokerMode("keycloak")
     *     .build()
     * }</pre>
     */
    public record AuditEntry(
            String correlationId,
            String providerCode,
            String providerType,
            String providerTxId,
            String brokerMode,
            String action,
            String identifierHash,
            String authLevel,
            String errorCode,
            String errorDetail,
            String clientIp,
            String feSessionId
    ) {
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String correlationId;
            private String providerCode;
            private String providerType;
            private String providerTxId;
            private String brokerMode = "keycloak";
            private String action;
            private String identifierHash;
            private String authLevel;
            private String errorCode;
            private String errorDetail;
            private String clientIp;
            private String feSessionId;

            private Builder() {}

            public Builder correlationId(String v)  { this.correlationId  = v; return this; }
            public Builder providerCode(String v)   { this.providerCode   = v; return this; }
            public Builder providerType(String v)   { this.providerType   = v; return this; }
            public Builder providerTxId(String v)   { this.providerTxId   = v; return this; }
            public Builder brokerMode(String v)     { this.brokerMode     = v; return this; }
            public Builder action(String v)         { this.action         = v; return this; }
            public Builder identifierHash(String v) { this.identifierHash = v; return this; }
            public Builder authLevel(String v)      { this.authLevel      = v; return this; }
            public Builder errorCode(String v)      { this.errorCode      = v; return this; }
            public Builder errorDetail(String v)    { this.errorDetail    = v; return this; }
            public Builder clientIp(String v)       { this.clientIp       = v; return this; }
            public Builder feSessionId(String v)    { this.feSessionId    = v; return this; }

            public AuditEntry build() {
                return new AuditEntry(correlationId, providerCode, providerType, providerTxId,
                        brokerMode, action, identifierHash, authLevel,
                        errorCode, errorDetail, clientIp, feSessionId);
            }
        }
    }
}
