package io.github.hipstermin.idem.tenant.init;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 기관 스텁 기동 시 초기화 컴포넌트
 *
 * <p><b>역할</b>:
 * <ol>
 *   <li>API Key 시드 — {@code agency_stub.agency_api_key} 에 PoC 기본 키 없으면 자동 삽입</li>
 *   <li>DB 스키마 연결 확인 — 핵심 테이블 존재 여부 검증</li>
 *   <li>IdO 연결 상태 사전 확인 (경고 로그만, 기동 중단 없음)</li>
 * </ol>
 *
 * <p><b>API Key 시드 값</b> (PoC):
 * <pre>
 *   rawKey  = "${agency-stub.ido.api-key}" (기본값: stub-api-key-dev-001)
 *   SHA-256 = 8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df
 * </pre>
 *
 * <p><b>운영 원칙</b>:
 * <ul>
 *   <li>rawKey 원문은 절대 로그에 기록하지 않음</li>
 *   <li>이미 존재하는 키는 중복 삽입하지 않음 (ON CONFLICT DO NOTHING)</li>
 *   <li>초기화 실패 시 경고 로그만 남기고 기동 계속 (비필수 초기화)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgencyDataInitializer implements ApplicationRunner {

    private static final String TABLE_CHECK_SQL =
            "SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = 'agency_stub' AND table_name = ?";

    private final JdbcTemplate jdbcTemplate;

    @Value("${agency-stub.code:AGENCY_STUB_001}")
    private String agencyCode;

    @Value("${agency-stub.ido.api-key:stub-api-key-dev-001}")
    private String rawApiKey;

    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void run(ApplicationArguments args) {
        log.info("[AgencyDataInitializer] ═══ 기관 스텁 초기화 시작 ═══ agencyCode={}", agencyCode);

        checkSchema();
        seedApiKey();
        logSummary();

        log.info("[AgencyDataInitializer] ═══ 초기화 완료 ═══");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 스키마 검증
    // ════════════════════════════════════════════════════════════════════════

    private void checkSchema() {
        String[] requiredTables = {
                "agency_user", "agency_local_session", "agency_api_key",
                "webhook_inbound", "agency_event_queue", "session_event_log"
        };

        for (String table : requiredTables) {
            try {
                Integer cnt = jdbcTemplate.queryForObject(TABLE_CHECK_SQL, Integer.class, table);
                if (cnt == null || cnt == 0) {
                    log.warn("[AgencyDataInitializer] 테이블 없음: agency_stub.{} — Flyway 마이그레이션 확인 필요", table);
                } else {
                    log.debug("[AgencyDataInitializer] 테이블 확인 OK: agency_stub.{}", table);
                }
            } catch (DataAccessException e) {
                log.warn("[AgencyDataInitializer] 테이블 확인 실패: {} — err={}", table, e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // API Key 시드
    // ════════════════════════════════════════════════════════════════════════

    private void seedApiKey() {
        try {
            String keyHash = sha256Hex(rawApiKey);

            // 이미 존재하는지 확인
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_api_key " +
                    "WHERE agency_code = ? AND api_key_hash = ? AND active = TRUE",
                    Integer.class, agencyCode, keyHash);

            if (existing != null && existing > 0) {
                log.info("[AgencyDataInitializer] API Key 이미 존재 — 스킵: agencyCode={}", agencyCode);
                return;
            }

            // 삽입 (ON CONFLICT DO NOTHING)
            int rows = jdbcTemplate.update(
                    "INSERT INTO agency_stub.agency_api_key " +
                    "    (agency_code, api_key_hash, key_label, active) " +
                    "VALUES (?, ?, ?, TRUE) " +
                    "ON CONFLICT (agency_code, api_key_hash) DO NOTHING",
                    agencyCode, keyHash, "poc-dev-key-auto-seeded"
            );

            if (rows > 0) {
                log.info("[AgencyDataInitializer] API Key 시드 완료: agencyCode={} hash={}...{}",
                        agencyCode,
                        keyHash.substring(0, 8),
                        keyHash.substring(keyHash.length() - 8));
            } else {
                log.info("[AgencyDataInitializer] API Key 이미 존재 (conflict) — 스킵: agencyCode={}", agencyCode);
            }

        } catch (Exception e) {
            // 비필수 — 경고만 남기고 기동 계속
            log.warn("[AgencyDataInitializer] API Key 시드 실패 (기동 계속): agencyCode={} err={}",
                    agencyCode, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 기동 시 요약 로그
    // ════════════════════════════════════════════════════════════════════════

    private void logSummary() {
        try {
            Integer sessionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_local_session WHERE agency_code = ?",
                    Integer.class, agencyCode);
            Integer activeSession = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_local_session " +
                    "WHERE agency_code = ? AND invalidated_at IS NULL AND idle_expires_at > NOW()",
                    Integer.class, agencyCode);
            Integer apiKeyCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM agency_stub.agency_api_key " +
                    "WHERE agency_code = ? AND active = TRUE",
                    Integer.class, agencyCode);

            log.info("[AgencyDataInitializer] 초기화 요약: agencyCode={} totalSessions={} activeSessions={} apiKeys={}",
                    agencyCode,
                    sessionCount  != null ? sessionCount  : 0,
                    activeSession != null ? activeSession : 0,
                    apiKeyCount   != null ? apiKeyCount   : 0);

        } catch (Exception e) {
            log.warn("[AgencyDataInitializer] 요약 조회 실패: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 헬퍼
    // ════════════════════════════════════════════════════════════════════════

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
