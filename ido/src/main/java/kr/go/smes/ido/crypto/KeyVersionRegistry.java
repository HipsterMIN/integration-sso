package kr.go.smes.ido.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AES/HMAC 키 버전 레지스트리
 *
 * <p>설계서 §16.4.1 — Handoff Ticket 암호화 키 버전 관리
 *
 * <p><b>키 해석 우선순위</b>:
 * <ol>
 *   <li>인메모리 캐시 (ConcurrentHashMap, 최소 지연)</li>
 *   <li>Redis (분산 캐시, TTL 1시간)</li>
 *   <li>DB {@code ido.crypto_key_registry} (영구 저장)</li>
 *   <li>Spring 환경 프로퍼티 {@code ido.ticket.aes-key} (폴백 / 개발 환경)</li>
 * </ol>
 *
 * <p><b>Redis 키 구조</b>:
 * <pre>
 *   ido:crypto:aes:current-version   → "v1" | "v2" | ...
 *   ido:crypto:aes:version:{vN}      → Base64(32-byte AES key material)
 *   ido:crypto:hmac:current-version  → "v1" | "v2" | ...
 *   ido:crypto:hmac:version:{vN}     → Base64(32-byte HMAC key material)
 * </pre>
 *
 * <p><b>스레드 안전</b>: ConcurrentHashMap + volatile 사용. {@link #evictCache()} 는
 * {@link HandoffKeyRotationScheduler} 로테이션 완료 후 호출됩니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyVersionRegistry {

    // ── Redis 키 상수 ─────────────────────────────────────────────────────
    private static final String AES_CURRENT_VERSION_KEY  = "ido:crypto:aes:current-version";
    private static final String AES_VERSION_KEY_PREFIX   = "ido:crypto:aes:version:";
    private static final String HMAC_CURRENT_VERSION_KEY = "ido:crypto:hmac:current-version";
    private static final String HMAC_VERSION_KEY_PREFIX  = "ido:crypto:hmac:version:";

    // ── 인메모리 캐시 TTL ─────────────────────────────────────────────────
    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate                  jdbcTemplate;

    // ── 폴백 프로퍼티 (개발/테스트 환경) ─────────────────────────────────
    @Value("${ido.ticket.aes-key:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
    private String fallbackAesKeyBase64;

    @Value("${ido.ticket.hmac-key:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=}")
    private String fallbackHmacKeyBase64;

    @Value("${ido.crypto.current-version:v1}")
    private String configuredVersion;

    // ── 인메모리 캐시 ────────────────────────────────────────────────────
    private final Map<String, CachedKey> aesKeyCache  = new ConcurrentHashMap<>();
    private final Map<String, CachedKey> hmacKeyCache = new ConcurrentHashMap<>();
    private volatile String cachedAesCurrentVersion;
    private volatile Instant aesVersionCachedAt;
    private volatile String cachedHmacCurrentVersion;
    private volatile Instant hmacVersionCachedAt;

    // ── 공개 API ─────────────────────────────────────────────────────────

    /**
     * 현재 활성 AES 키 버전 문자열 반환 (예: "v1", "v2")
     */
    public String currentAesVersion() {
        return resolveCurrentVersion(KeyType.AES);
    }

    /**
     * 현재 활성 HMAC 키 버전 문자열 반환 (예: "v1", "v2")
     */
    public String currentHmacVersion() {
        return resolveCurrentVersion(KeyType.HMAC);
    }

    /**
     * 버전 문자열에 해당하는 AES 키 재료 반환
     *
     * @param version 키 버전 ("v1", "v2", ...)
     * @return 32바이트 AES-256 키
     * @throws KeyNotFoundException 버전에 해당하는 키가 없을 때
     */
    public byte[] resolveAesKey(String version) {
        return resolveKey(version, KeyType.AES);
    }

    /**
     * 버전 문자열에 해당하는 HMAC 키 재료 반환
     *
     * @param version 키 버전 ("v1", "v2", ...)
     * @return 32바이트 HMAC-SHA256 키
     * @throws KeyNotFoundException 버전에 해당하는 키가 없을 때
     */
    public byte[] resolveHmacKey(String version) {
        return resolveKey(version, KeyType.HMAC);
    }

    /**
     * 암호화된 값이 버전 접두사 포맷인지 확인
     * 포맷: {@code v{n}.{base64url_iv}.{base64url_ct}}
     */
    public boolean isVersioned(String value) {
        return value != null && value.matches("^v\\d+\\..+\\..+$");
    }

    /**
     * 로테이션 후 인메모리 캐시 전체 초기화
     * {@link HandoffKeyRotationScheduler#performRotation} 완료 시 호출
     */
    public void evictCache() {
        aesKeyCache.clear();
        hmacKeyCache.clear();
        cachedAesCurrentVersion  = null;
        aesVersionCachedAt       = null;
        cachedHmacCurrentVersion = null;
        hmacVersionCachedAt      = null;
        log.info("[KeyVersionRegistry] 인메모리 키 캐시 초기화 완료");
    }

    // ── 초기화 ────────────────────────────────────────────────────────────

    /**
     * 애플리케이션 시작 시 현재 버전 Warm-up (Redis 캐시 확인)
     */
    @PostConstruct
    void init() {
        try {
            String aesVer  = resolveCurrentVersion(KeyType.AES);
            String hmacVer = resolveCurrentVersion(KeyType.HMAC);
            log.info("[KeyVersionRegistry] 초기화 완료 — AES: {}, HMAC: {}", aesVer, hmacVer);
        } catch (Exception e) {
            log.warn("[KeyVersionRegistry] 초기화 경고 (폴백 사용): {}", e.getMessage());
        }
    }

    // ── 내부 구현 ─────────────────────────────────────────────────────────

    private String resolveCurrentVersion(KeyType keyType) {
        // 1. 인메모리 캐시 (TTL 5분)
        if (keyType == KeyType.AES) {
            if (cachedAesCurrentVersion != null && aesVersionCachedAt != null
                    && Instant.now().isBefore(aesVersionCachedAt.plus(LOCAL_CACHE_TTL))) {
                return cachedAesCurrentVersion;
            }
        } else {
            if (cachedHmacCurrentVersion != null && hmacVersionCachedAt != null
                    && Instant.now().isBefore(hmacVersionCachedAt.plus(LOCAL_CACHE_TTL))) {
                return cachedHmacCurrentVersion;
            }
        }

        // 2. Redis
        String redisKey = keyType == KeyType.AES ? AES_CURRENT_VERSION_KEY : HMAC_CURRENT_VERSION_KEY;
        try {
            Object val = redisTemplate.opsForValue().get(redisKey);
            if (val != null) {
                String version = val.toString();
                cacheCurrentVersion(keyType, version);
                return version;
            }
        } catch (Exception e) {
            log.warn("[KeyVersionRegistry] Redis 현재 버전 조회 실패 ({}): {}", keyType, e.getMessage());
        }

        // 3. DB
        String keyTypeStr = keyType == KeyType.AES ? "HANDOFF_AES" : "HANDOFF_HMAC";
        try {
            String version = jdbcTemplate.queryForObject(
                    "SELECT key_version FROM ido.crypto_key_registry " +
                    "WHERE key_type = ? AND active = TRUE AND current_flag = TRUE " +
                    "LIMIT 1",
                    String.class, keyTypeStr);
            if (version != null) {
                // Redis에 캐시
                try {
                    redisTemplate.opsForValue().set(redisKey, version, Duration.ofHours(1));
                } catch (Exception ignored) { /* Redis 실패 무시 */ }
                cacheCurrentVersion(keyType, version);
                return version;
            }
        } catch (Exception e) {
            log.warn("[KeyVersionRegistry] DB 현재 버전 조회 실패 ({}): {}", keyType, e.getMessage());
        }

        // 4. 폴백: Spring 프로퍼티 설정값
        log.warn("[KeyVersionRegistry] {} 버전 조회 실패 — 폴백: {}", keyType, configuredVersion);
        return configuredVersion;
    }

    private byte[] resolveKey(String version, KeyType keyType) {
        Map<String, CachedKey> cache = keyType == KeyType.AES ? aesKeyCache : hmacKeyCache;

        // 1. 인메모리 캐시
        CachedKey cached = cache.get(version);
        if (cached != null && !cached.isExpired()) {
            return cached.keyBytes;
        }

        // 2. Redis
        String prefix = keyType == KeyType.AES ? AES_VERSION_KEY_PREFIX : HMAC_VERSION_KEY_PREFIX;
        try {
            Object val = redisTemplate.opsForValue().get(prefix + version);
            if (val != null) {
                byte[] keyBytes = decodeBase64(val.toString());
                cache.put(version, new CachedKey(keyBytes));
                return keyBytes;
            }
        } catch (Exception e) {
            log.warn("[KeyVersionRegistry] Redis 키 조회 실패 ({}, {}): {}", keyType, version, e.getMessage());
        }

        // 3. DB (key_material_encrypted 컬럼 — 운영: KMS 복호화 필요, 개발: Base64 원문)
        String keyTypeStr = keyType == KeyType.AES ? "HANDOFF_AES" : "HANDOFF_HMAC";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT key_material_encrypted FROM ido.crypto_key_registry " +
                    "WHERE key_type = ? AND key_version = ? " +
                    "AND (active = TRUE OR (grace_until IS NOT NULL AND grace_until > NOW()))",
                    keyTypeStr, version);
            if (!rows.isEmpty()) {
                Object material = rows.get(0).get("key_material_encrypted");
                if (material != null) {
                    byte[] keyBytes = decodeBase64(material.toString());
                    cache.put(version, new CachedKey(keyBytes));
                    return keyBytes;
                }
            }
        } catch (Exception e) {
            log.warn("[KeyVersionRegistry] DB 키 재료 조회 실패 ({}, {}): {}", keyType, version, e.getMessage());
        }

        // 4. 폴백: 환경 프로퍼티 (v1에만 적용)
        if ("v1".equals(version)) {
            String fallback = keyType == KeyType.AES ? fallbackAesKeyBase64 : fallbackHmacKeyBase64;
            log.warn("[KeyVersionRegistry] {} {} 키 폴백 사용 (환경 프로퍼티)", keyType, version);
            byte[] keyBytes = decodeBase64(fallback);
            cache.put(version, new CachedKey(keyBytes));
            return keyBytes;
        }

        throw new KeyNotFoundException(String.format(
                "%s 암호화 키 버전 '%s'을 찾을 수 없습니다.", keyType, version));
    }

    private void cacheCurrentVersion(KeyType keyType, String version) {
        if (keyType == KeyType.AES) {
            cachedAesCurrentVersion = version;
            aesVersionCachedAt      = Instant.now();
        } else {
            cachedHmacCurrentVersion = version;
            hmacVersionCachedAt      = Instant.now();
        }
    }

    private byte[] decodeBase64(String b64) {
        // URL-safe / standard / padding 모두 허용
        String std = b64.replace('-', '+').replace('_', '/');
        int pad = std.length() % 4;
        if (pad == 2) std += "==";
        else if (pad == 3) std += "=";
        return Base64.getDecoder().decode(std);
    }

    // ── 내부 타입 ─────────────────────────────────────────────────────────

    private enum KeyType { AES, HMAC }

    /** 인메모리 캐시 엔트리 (5분 TTL) */
    private static final class CachedKey {
        final byte[]  keyBytes;
        final Instant cachedAt;

        CachedKey(byte[] keyBytes) {
            this.keyBytes = keyBytes;
            this.cachedAt = Instant.now();
        }

        boolean isExpired() {
            return Instant.now().isAfter(cachedAt.plus(LOCAL_CACHE_TTL));
        }
    }

    /** 키 버전을 찾을 수 없을 때 발생하는 예외 */
    public static class KeyNotFoundException extends RuntimeException {
        public KeyNotFoundException(String message) {
            super(message);
        }
    }
}
