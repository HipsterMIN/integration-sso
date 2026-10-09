package io.github.hipstermin.idem.hub.webhook;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.hub.crypto.kms.KmsClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 1.1.1 G1-4 — 기관 웹훅 서명 비밀의 봉인·복호화 ({@code agency_webhook_config.signing_secret_sealed}).
 *
 * <ul>
 *   <li>{@link #seal} — 회전 때 원문을 {@link KmsClient#encrypt} 로 봉인한다(provider 에 따라 local 마스터 키 / Vault / NHN envelope).
 *       NHN SECRET 모드는 encrypt 를 지원하지 않으므로 회전 API 를 쓸 수 없다(envelope 모드 또는 local/vault).</li>
 *   <li>{@link #resolve} — 발송 때 봉인값을 풀어 원문을 돌려준다. 봉인값 기준으로 캐시(회전하면 봉인값이 바뀌어 캐시 키가 달라진다).
 *       봉인값이 없고 종전 컬럼에 값이 있으면 그것을 원문으로 본다(1.0.x 호환 — 첫 기동의 {@link #sealLegacyRows} 가 끝나면 쓰이지 않는다).</li>
 *   <li>{@link #sealLegacyRows} — 기동 뒤 한 번, 봉인값이 없는 행의 종전 원문을 봉인하고 {@code signing_secret_hash} 를 진짜 해시로 바꾼다.</li>
 * </ul>
 */
@Slf4j
@Component
public class WebhookSigningSecrets {

    private static final int CACHE_MAX = 1_000;

    private final KmsClient kms;
    private final JdbcTemplate jdbc;
    private final boolean sealLegacyOnBoot;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public WebhookSigningSecrets(KmsClient kms, JdbcTemplate jdbc,
                                 @Value("${idem.hub.webhook.seal-legacy-on-boot:true}") boolean sealLegacyOnBoot) {
        this.kms = kms;
        this.jdbc = jdbc;
        this.sealLegacyOnBoot = sealLegacyOnBoot;
    }

    /** 원문 → KMS 봉인값 */
    public String seal(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) throw new IllegalArgumentException("빈 서명 비밀은 봉인할 수 없다");
        return kms.encrypt(rawSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** 지문 — SHA-256 hex 의 앞 8자 (원문은 드러나지 않는다) */
    public static String fingerprint(String sha256Hex) {
        return sha256Hex == null || sha256Hex.length() < 8 ? null : sha256Hex.substring(0, 8);
    }

    /**
     * 발송용 원문. {@code sealed} 가 있으면 복호화(캐시), 없으면 {@code legacyRaw}(1.0.x 가 원문을 두던 컬럼 값), 둘 다 없으면 null.
     */
    public String resolve(String sealed, String legacyRaw) {
        if (sealed != null && !sealed.isBlank()) {
            String cached = cache.get(sealed);
            if (cached != null) return cached;
            String raw = new String(kms.decrypt(sealed), StandardCharsets.UTF_8);
            if (cache.size() >= CACHE_MAX) cache.clear();
            cache.put(sealed, raw);
            return raw;
        }
        if (legacyRaw != null && !legacyRaw.isBlank()) {
            return legacyRaw;
        }
        return null;
    }

    /** 1.0.x 행(원문이 signing_secret_hash 에 있는 행)을 봉인한다 — 멱등, 실패해도 기동은 막지 않는다 */
    @EventListener(ApplicationReadyEvent.class)
    public void sealLegacyRows() {
        if (!sealLegacyOnBoot) return;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT agency_code, signing_secret_hash FROM idem_hub.agency_webhook_config "
                    + "WHERE signing_secret_sealed IS NULL AND signing_secret_hash IS NOT NULL");
            int n = 0;
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("agency_code");
                String raw = (String) row.get("signing_secret_hash");
                if (raw == null || raw.isBlank()) continue;
                int updated = jdbc.update(
                        "UPDATE idem_hub.agency_webhook_config SET signing_secret_sealed = ?, signing_secret_hash = ?, updated_at = NOW() "
                        + "WHERE agency_code = ? AND signing_secret_sealed IS NULL",
                        seal(raw), CryptoProviders.current().sha256Hex(raw), code);
                n += updated;
            }
            if (n > 0) log.info("[Idem 웹훅] 1.0.x 서명 비밀 {}건을 KMS({})로 봉인했다 — signing_secret_hash 는 이제 해시다", n, kms.providerName());
        } catch (RuntimeException e) {
            log.warn("[Idem 웹훅] 종전 서명 비밀 봉인 실패(기동은 계속, 발송은 종전 값으로): {}", e.getMessage());
        }
    }
}
