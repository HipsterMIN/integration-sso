package io.github.hipstermin.idem.hub.crypto;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.crypto.kms.KmsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Handoff Ticket AES-256 키 버전 로테이션 스케줄러
 *
 * <p><b>설계서 §16.4.2 키 관리 정책</b>:
 * <ul>
 *   <li>로테이션 주기: 90일 (운영 설정 가능)</li>
 *   <li>Grace Period: 24시간 (구 키로 암호화된 Ticket 복호화 허용)</li>
 *   <li>키 버전: v1, v2, ... (단조 증가)</li>
 *   <li>신규 암호화는 항상 현재 활성 버전 사용</li>
 *   <li>복호화는 버전 접두사로 적절한 키 선택</li>
 * </ul>
 *
 * <p><b>운영 주의</b>: 실제 키는 Vault/AWS KMS에서 관리.
 * 이 스케줄러는 로테이션 이벤트 감지 + 캐시 무효화 담당.
 * 실제 키 생성은 KMS에 위임 (이 구현은 PoC/개발 환경용 자체 생성 포함).
 *
 * <p><b>Redis 키</b>:
 * <pre>
 *   ido:crypto:aes:current-version    → "v1" 또는 "v2"
 *   ido:crypto:aes:version:{vN}       → Base64 AES 키 (운영: Vault에서 주입)
 *   ido:crypto:aes:rotate-lock        → 분산 잠금 (중복 로테이션 방지)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffKeyRotationScheduler {

    private static final String CURRENT_VERSION_KEY = "idem:crypto:aes:current-version";
    private static final String VERSION_KEY_PREFIX  = "idem:crypto:aes:version:";
    private static final String ROTATE_LOCK_KEY     = "idem:crypto:aes:rotate-lock";

    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate                  jdbcTemplate;
    private final AuditLogPublisher             auditLogPublisher;
    private final KeyVersionRegistry            keyVersionRegistry;
    private final KmsClient                     kmsClient;

    @Value("${idem.hub.ticket.key-rotation-days:90}")
    private int keyRotationDays;

    @Value("${idem.hub.ticket.key-grace-period-hours:24}")
    private int keyGracePeriodHours;

    @Value("${idem.hub.crypto.rotation-enabled:true}")
    private boolean rotationEnabled;

    /**
     * 매 시간 로테이션 필요 여부 확인
     * 실제 로테이션: DB에 기록된 key_rotated_at + rotationDays 이후
     */
    @Scheduled(cron = "${idem.hub.crypto.rotation-check-cron:0 0 * * * *}")   // 매 시간 정각
    public void checkAndRotate() {
        if (!rotationEnabled) {
            log.debug("[KeyRotation] 키 로테이션 비활성화 — 스킵");
            return;
        }

        // 분산 잠금 획득 (Redis SET NX — 10분 TTL)
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                ROTATE_LOCK_KEY, "locked", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("[KeyRotation] 다른 인스턴스가 로테이션 처리 중 — 스킵");
            return;
        }

        try {
            checkRotationNeeded();
        } finally {
            redisTemplate.delete(ROTATE_LOCK_KEY);
        }
    }

    /**
     * 현재 활성 키 버전 조회 (Redis 캐시)
     */
    public String getCurrentVersion() {
        Object val = redisTemplate.opsForValue().get(CURRENT_VERSION_KEY);
        if (val != null) return val.toString();
        // 캐시 미스 → DB에서 조회 후 캐시 설정
        String version = queryCurrentVersionFromDb();
        redisTemplate.opsForValue().set(CURRENT_VERSION_KEY, version, Duration.ofHours(1));
        return version;
    }

    /**
     * 수동 로테이션 트리거 (Admin API 호출용)
     *
     * @param adminId  실행 관리자 ID
     * @return 새 키 버전 정보
     */
    public Map<String, Object> triggerManualRotation(String adminId) {
        log.warn("[KeyRotation] 수동 키 로테이션 시작: adminId={}", adminId);
        return performRotation(adminId, "MANUAL_ROTATION");
    }

    // ── private ──────────────────────────────────────────────────────────────

    private void checkRotationNeeded() {
        try {
            Instant lastRotated = queryLastRotationTime();
            if (lastRotated == null) {
                log.info("[KeyRotation] 최초 키 로테이션 실행");
                performRotation("SYSTEM", "INITIAL_SETUP");
                return;
            }

            long daysSinceRotation = Duration.between(lastRotated, Instant.now()).toDays();
            if (daysSinceRotation >= keyRotationDays) {
                log.info("[KeyRotation] 로테이션 주기 도달: {}일 경과 (기준: {}일)",
                        daysSinceRotation, keyRotationDays);
                performRotation("SYSTEM", "SCHEDULED_ROTATION");
            } else {
                log.debug("[KeyRotation] 로테이션 불필요: {}일 경과 (기준: {}일까지)",
                        daysSinceRotation, keyRotationDays);
            }
        } catch (Exception e) {
            log.error("[KeyRotation] 로테이션 확인 오류", e);
        }
    }

    private Map<String, Object> performRotation(String adminId, String reason) {
        try {
            // 1. 새 키 생성 (AES-256 = 32 bytes, SecureRandom)
            byte[] newKeyBytes = CryptoProviders.current().randomBytes(32);

            // 2. KMS로 DEK 암호화 — provider에 따라 자동 선택
            //    Vault:  "vault:v1:AABB..." (Transit ciphertext)
            //    Local:  Base64 평문 (KMS Off 모드, 개발 전용)
            //    NHN:    NHN SKM ENVELOPE 모드 ciphertext
            String encryptedKeyMaterial = kmsClient.encrypt(newKeyBytes);
            log.info("[KeyRotation] KMS 암호화 완료: provider={} reason={}",
                     kmsClient.providerName(), reason);

            // 3. 현재 버전 조회 + 다음 버전 계산
            String currentVersion = queryCurrentVersionFromDb();
            int    currentNum     = Integer.parseInt(currentVersion.replaceAll("[^0-9]", ""));
            String newVersion     = "v" + (currentNum + 1);

            // 4. DB에 신규 키 버전 기록 (ido.crypto_key_registry)
            saveKeyVersion(newVersion, encryptedKeyMaterial, adminId, reason);

            // 5. 활성 버전 갱신
            updateCurrentVersion(newVersion);

            // 6. Redis 캐시 무효화 + 인메모리 캐시 초기화
            redisTemplate.delete(CURRENT_VERSION_KEY);
            redisTemplate.opsForValue().set(CURRENT_VERSION_KEY, newVersion, Duration.ofHours(1));
            keyVersionRegistry.evictCache();

            // 7. 감사 로그
            auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                    .eventCategory(AuditLogEvent.CATEGORY_SYSTEM)
                    .eventAction("AES_KEY_ROTATED")
                    .actorType(AuditLogEvent.ACTOR_SYSTEM)
                    .actorId(adminId)
                    .resourceType("CRYPTO_KEY")
                    .resourceId(newVersion)
                    .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                    .build());

            log.info("[KeyRotation] 키 로테이션 완료: {} → {} (reason={})",
                    currentVersion, newVersion, reason);

            return Map.of(
                    "previousVersion", currentVersion,
                    "newVersion",      newVersion,
                    "rotatedAt",       Instant.now().toString(),
                    "adminId",         adminId,
                    "reason",          reason
            );

        } catch (Exception e) {
            log.error("[KeyRotation] 키 로테이션 실패: adminId={} reason={}", adminId, reason, e);
            throw new RuntimeException("AES 키 로테이션 실패", e);
        }
    }

    private String queryCurrentVersionFromDb() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT key_version FROM ido.crypto_key_registry " +
                    "WHERE key_type = 'HANDOFF_AES' AND active = TRUE " +
                    "ORDER BY created_at DESC LIMIT 1",
                    String.class);
        } catch (Exception e) {
            log.warn("[KeyRotation] DB 키 버전 조회 실패 — v1 기본값 사용: {}", e.getMessage());
            return "v1";
        }
    }

    private Instant queryLastRotationTime() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT MAX(created_at) FROM ido.crypto_key_registry " +
                    "WHERE key_type = 'HANDOFF_AES'",
                    Instant.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveKeyVersion(String version, String encryptedKeyMaterial, String adminId, String reason) {
        // 이전 활성 키를 INACTIVE 처리 (Grace Period 동안은 복호화 가능)
        jdbcTemplate.update(
                "UPDATE ido.crypto_key_registry SET active = FALSE, grace_until = ? " +
                "WHERE key_type = 'HANDOFF_AES' AND active = TRUE",
                Instant.now().plus(Duration.ofHours(keyGracePeriodHours)));

        // 신규 키 등록 — encryptedKeyMaterial은 KmsClient.encrypt()가 반환한 값
        // Vault: "vault:v1:AABB...", Local(Off): Base64 평문, NHN: SKM ciphertext
        jdbcTemplate.update(
                "INSERT INTO ido.crypto_key_registry " +
                "(key_type, key_version, key_material_encrypted, active, created_by, rotation_reason, created_at) " +
                "VALUES ('HANDOFF_AES', ?, ?, TRUE, ?, ?, NOW())",
                version,
                encryptedKeyMaterial,
                adminId,
                reason);
    }

    private void updateCurrentVersion(String version) {
        try {
            jdbcTemplate.update(
                    "UPDATE ido.crypto_key_registry SET current_flag = FALSE " +
                    "WHERE key_type = 'HANDOFF_AES'");
            jdbcTemplate.update(
                    "UPDATE ido.crypto_key_registry SET current_flag = TRUE " +
                    "WHERE key_type = 'HANDOFF_AES' AND key_version = ?",
                    version);
        } catch (Exception e) {
            log.warn("[KeyRotation] current_flag 갱신 실패 (비치명적): {}", e.getMessage());
        }
    }
}
