package kr.go.smes.ido.crypto;

import kr.go.smes.ido.crypto.kms.KmsClient;
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
import java.util.Set;
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

    /** AES-256 / HMAC-SHA256 키 모두 32바이트(256-bit) 키 재료를 요구 */
    private static final int REQUIRED_KEY_BYTES = 32;

    /**
     * 부팅을 차단해야 하는 폴백 키 placeholder 목록 (Sprint γ-3 / F3.3 후속).
     *
     * <p>특히 {@code "AAAA...="} (32바이트 0x00 키 Base64) 는 γ-2 이전까지
     * {@code application.yml} 의 {@code ido.ticket.aes-key} / {@code ido.ticket.hmac-key}
     * default 로 박혀있던 값이다. Redis/DB 폴백 경로가 모두 실패해 폴백 프로퍼티가
     * 사용되는 순간(운영에서도 발생 가능), 이 값이 실제 암호화/서명 키로 동작하면
     * Handoff Ticket 전체가 사실상 평문이 된다.
     *
     * <p>대소문자·공백 무시 비교. γ-3 PR 이후 재실수를 영구 차단.
     */
    private static final Set<String> FORBIDDEN_PLACEHOLDERS = Set.of(
            "change-me",
            "changeme",
            "default",
            "secret",
            "test",
            // 32바이트 0x00 키 (legacy default — γ-3 이전 application.yml line 605/606)
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa="
    );

    private final RedisTemplate<String, Object> redisTemplate;
    private final JdbcTemplate                  jdbcTemplate;
    private final KmsClient                     kmsClient;

    // ── 폴백 프로퍼티 (개발/테스트 환경) ─────────────────────────────────
    @Value("${ido.ticket.aes-key:}")
    private String fallbackAesKeyBase64;

    @Value("${ido.ticket.hmac-key:}")
    private String fallbackHmacKeyBase64;

    @Value("${ido.crypto.current-version:v1}")
    private String configuredVersion;

    /**
     * 로컬·테스트 전용 escape hatch (Sprint γ-3 / F3.3 후속).
     * <p>{@code true} 일 때만 폴백 키 누락 / 빈 값이 허용된다. 운영에서는 절대 사용 금지.
     */
    @Value("${ido.ticket.allow-empty-fallback-keys:false}")
    private boolean allowEmptyFallbackKeys;

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
     * 애플리케이션 시작 시:
     * <ol>
     *   <li><b>폴백 키 안전성 검증</b> (Sprint γ-3 / F3.3 후속) — 운영에서 Redis/DB 가
     *       일시 장애일 때 폴백 프로퍼티가 진짜 키로 사용되므로, 빈 값 또는 placeholder
     *       (예: 과거 default {@code "AAAA...="}) 가 주입되면 부팅을 차단한다.</li>
     *   <li>현재 버전 Warm-up (Redis 캐시 확인) — 기존 동작 유지, 실패해도 로그 경고만</li>
     * </ol>
     *
     * <p>{@code ido.ticket.allow-empty-fallback-keys=true} 가 명시되면 폴백 키 검증을 건너뛴다
     * (로컬/단위·통합 테스트 한정).
     */
    @PostConstruct
    void init() {
        validateFallbackKeys();

        try {
            String aesVer  = resolveCurrentVersion(KeyType.AES);
            String hmacVer = resolveCurrentVersion(KeyType.HMAC);
            log.info("[KeyVersionRegistry] 초기화 완료 — AES: {}, HMAC: {}", aesVer, hmacVer);
        } catch (Exception e) {
            log.warn("[KeyVersionRegistry] 초기화 경고 (폴백 사용): {}", e.getMessage());
        }
    }

    /**
     * Spring 부팅 시 폴백 키({@code ido.ticket.aes-key} / {@code ido.ticket.hmac-key}) 안전성 검증.
     *
     * <p>검증 항목 (AES / HMAC 각각):
     * <ol>
     *   <li>null/blank 거부 (escape hatch 시 우회)</li>
     *   <li>{@link #FORBIDDEN_PLACEHOLDERS} 포함 거부 (escape hatch 와 무관하게 항상 차단)</li>
     *   <li>Base64 디코드 가능성</li>
     *   <li>디코드 결과 정확히 32바이트(AES-256 / HMAC-SHA256) 검증</li>
     * </ol>
     *
     * <p>검증 실패 시 {@link IllegalStateException} → ApplicationContext 초기화 중단
     * → 컨테이너 CrashLoopBackOff 로 즉시 인지.
     *
     * <p>주의: 이 검증은 <b>폴백 경로의 안전성</b> 만 보장한다. Redis/DB/KMS 경로는
     * 별도 단계에서 검증되며, 본 메서드 실패와 무관하게 동작한다.
     */
    void validateFallbackKeys() {
        validateOneFallbackKey("ido.ticket.aes-key",  fallbackAesKeyBase64,  "AES",  "IDO_HANDOFF_AES_KEY");
        validateOneFallbackKey("ido.ticket.hmac-key", fallbackHmacKeyBase64, "HMAC", "IDO_HANDOFF_HMAC_KEY");
    }

    private void validateOneFallbackKey(String propertyKey, String keyB64, String keyKind, String envVar) {
        if (keyB64 == null || keyB64.isBlank()) {
            if (allowEmptyFallbackKeys) {
                log.warn("[KeyVersionRegistry] {} 폴백 키가 비어있지만 allow-empty-fallback-keys=true 로 우회 "
                        + "(로컬/테스트 전용). 운영 환경에서는 절대 허용 금지.", propertyKey);
                return;
            }
            throw new IllegalStateException(
                    "[KeyVersionRegistry] " + propertyKey + " 폴백 " + keyKind + " 키가 설정되지 않았습니다. "
                            + "환경변수 " + envVar + " 를 32바이트 Base64 키로 주입하십시오 "
                            + "(생성: openssl rand -base64 32). "
                            + "로컬·테스트에서만 ido.ticket.allow-empty-fallback-keys=true 로 우회 가능합니다.");
        }

        String normalized = keyB64.trim().toLowerCase();
        if (FORBIDDEN_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "[KeyVersionRegistry] " + propertyKey + " 에 placeholder 값('" + keyB64 + "')이 설정되어 있습니다. "
                            + "이 값은 과거 default 또는 더미 키로 운영에 사용해서는 안 됩니다. "
                            + "Redis/DB 폴백 경로가 실패하면 이 값이 실제 " + keyKind + " 키로 사용되어 "
                            + "Handoff Ticket 전체가 평문이 됩니다. "
                            + "openssl rand -base64 32 로 생성한 32바이트 무작위 키를 주입하십시오.");
        }

        byte[] decoded;
        try {
            decoded = decodeBase64(keyB64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "[KeyVersionRegistry] " + propertyKey + " 가 유효한 Base64 가 아닙니다. "
                            + "표준 Base64 또는 Base64URL 형식의 32바이트 키를 주입하십시오.", e);
        }

        if (decoded.length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(
                    "[KeyVersionRegistry] " + propertyKey + " 디코드 결과가 " + decoded.length + " 바이트입니다. "
                            + keyKind + " (AES-256 / HMAC-SHA256) 은 정확히 " + REQUIRED_KEY_BYTES + "바이트 키를 요구합니다. "
                            + "openssl rand -base64 32 로 32바이트 키를 생성하여 주입하십시오.");
        }

        log.info("[KeyVersionRegistry] 폴백 {} 키 부팅 검증 통과 — 길이={}바이트", keyKind, decoded.length);
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

        // 3. DB (key_material_encrypted 컬럼 — KMS로 복호화, S9-T8)
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
                    // S9-T8: KmsClient를 통해 Envelope Encryption 복호화
                    // - NoOpKmsClient (dev): Base64 디코딩만 수행
                    // - AwsKmsClient (prod): AWS KMS Decrypt API 호출
                    byte[] keyBytes = kmsClient.decrypt(material.toString());
                    log.debug("[KeyVersionRegistry] KMS 복호화 완료: provider={} keyType={} version={}",
                            kmsClient.providerName(), keyType, version);
                    cache.put(version, new CachedKey(keyBytes));
                    return keyBytes;
                }
            }
        } catch (KmsClient.KmsDecryptException e) {
            log.error("[KeyVersionRegistry] KMS 복호화 실패 ({}, {}): {}", keyType, version, e.getMessage());
            // KMS 실패 시 v1 폴백으로 진행 (아래 폴백 코드로 fall-through)
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
