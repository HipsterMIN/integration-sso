package io.github.hipstermin.idem.common.naming;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 개명 4b(S9, {@code docs/naming.md} §3) — 구 런타임 식별자 → 새 이름 대응.
 *
 * <p>설정 키: {@code ido.*}→{@code idem.hub.*}, {@code qsign.*}→{@code idem.gate.*}, {@code qim.*}→{@code idem.registry.*},
 * {@code authz.*}→{@code idem.authz.*}, {@code batch.*}→{@code idem.relay.*}, {@code agency-stub.*}→{@code idem.sample.*}
 * (+ 모듈 안의 구 모듈 지칭 {@code qim→registry}, {@code q-authz→authz}, {@code qsign→gate}, {@code ido→hub}).
 * 환경변수: {@code IDO_*}→{@code IDEM_HUB_*}, {@code QSIGN_*}→{@code IDEM_GATE_*}, {@code QIM_*}→{@code IDEM_REGISTRY_*},
 * {@code AUTHZ_*}→{@code IDEM_AUTHZ_*}, {@code BATCH_*}→{@code IDEM_RELAY_*} (+ 같은 모듈 지칭 치환).
 *
 * <p>{@link LegacyNamesEnvironmentPostProcessor} 가 이 표로 구 이름을 1 릴리스 동안 받아 준다. 코드·설정·설치본은 새 이름만 쓴다
 * ({@code NamingGuardTest}). 이 클래스는 순수 함수라 스크립트(개명 패스)와 문서 표의 단일 원천이다.
 */
public final class LegacyNames {

    private LegacyNames() {}

    /** 설정 키 접두 → 새 접두 (앞에서부터 첫 일치) */
    private static final Map<String, String> KEY_PREFIX = new LinkedHashMap<>();
    /** 접두 치환 뒤 적용하는 모듈 지칭 치환 */
    private static final Map<String, String> KEY_ALIAS = new LinkedHashMap<>();
    /** 환경변수 별칭(구체적인 것 먼저) */
    private static final Map<String, String> ENV_ALIAS = new LinkedHashMap<>();
    /** 환경변수 접두 */
    private static final Map<String, String> ENV_PREFIX = new LinkedHashMap<>();

    static {
        KEY_PREFIX.put("ido.", "idem.hub.");
        KEY_PREFIX.put("qsign.", "idem.gate.");
        KEY_PREFIX.put("qim.", "idem.registry.");
        KEY_PREFIX.put("authz.", "idem.authz.");
        KEY_PREFIX.put("batch.", "idem.relay.");
        KEY_PREFIX.put("agency-stub.", "idem.sample.");

        KEY_ALIAS.put("idem.hub.qim-outbox.", "idem.hub.registry-outbox.");
        KEY_ALIAS.put("idem.hub.qim-events.", "idem.hub.registry-events.");
        KEY_ALIAS.put("idem.hub.qim.", "idem.hub.registry.");
        KEY_ALIAS.put("idem.hub.q-authz.", "idem.hub.authz.");
        KEY_ALIAS.put("idem.hub.qsign.", "idem.hub.gate.");
        KEY_ALIAS.put("idem.hub.kafka.topic-qim-", "idem.hub.kafka.topic-registry-");
        KEY_ALIAS.put("idem.hub.internal.callers.q-sign", "idem.hub.internal.callers.idem-gate");
        KEY_ALIAS.put("idem.hub.internal.callers.outbox-relay", "idem.hub.internal.callers.idem-relay");
        KEY_ALIAS.put("idem.gate.ido.", "idem.gate.hub.");
        KEY_ALIAS.put("idem.relay.datasource.qsign.", "idem.relay.datasource.gate.");
        KEY_ALIAS.put("idem.relay.datasource.qim.", "idem.relay.datasource.registry.");
        KEY_ALIAS.put("idem.relay.datasource.ido.", "idem.relay.datasource.hub.");
        KEY_ALIAS.put("idem.relay.relay.qsign.", "idem.relay.jobs.gate.");
        KEY_ALIAS.put("idem.relay.relay.qim.", "idem.relay.jobs.registry.");
        KEY_ALIAS.put("idem.relay.relay.ido.", "idem.relay.jobs.hub.");
        KEY_ALIAS.put("idem.relay.relay.", "idem.relay.jobs.");

        ENV_ALIAS.put("IDEM_ADMIN_", "IDEM_HUB_ADMIN_");   // S7 에서 잠깐 쓰인 이름 — hub 설정이라 IDEM_HUB_ 아래로
        ENV_ALIAS.put("IDO_QIM_", "IDEM_HUB_REGISTRY_");
        ENV_ALIAS.put("IDO_QAUTHZ_", "IDEM_HUB_AUTHZ_");
        ENV_ALIAS.put("IDO_INTERNAL_API_KEY_QSIGN", "IDEM_HUB_INTERNAL_API_KEY_GATE");
        ENV_ALIAS.put("IDO_INTERNAL_API_KEY_OUTBOX", "IDEM_HUB_INTERNAL_API_KEY_RELAY");
        ENV_ALIAS.put("QAUTHZ_BASE_URL", "IDEM_HUB_AUTHZ_BASE_URL");
        ENV_ALIAS.put("BATCH_IDO_QIM_", "IDEM_RELAY_HUB_REGISTRY_");
        ENV_ALIAS.put("BATCH_IDO_", "IDEM_RELAY_HUB_");
        ENV_ALIAS.put("BATCH_QIM_", "IDEM_RELAY_REGISTRY_");
        ENV_ALIAS.put("BATCH_QSIGN_", "IDEM_RELAY_GATE_");

        ENV_PREFIX.put("IDO_", "IDEM_HUB_");
        ENV_PREFIX.put("QSIGN_", "IDEM_GATE_");
        ENV_PREFIX.put("QIM_", "IDEM_REGISTRY_");
        ENV_PREFIX.put("AUTHZ_", "IDEM_AUTHZ_");
        ENV_PREFIX.put("BATCH_", "IDEM_RELAY_");
    }

    private static final Pattern LEGACY_KEY = Pattern.compile("^(ido|qsign|qim|authz|batch|agency-stub)\\.");
    private static final Pattern LEGACY_ENV = Pattern.compile("^(IDO|QSIGN|QIM|AUTHZ|BATCH|QAUTHZ)_[A-Z0-9_]+$|^IDEM_ADMIN_(BOOTSTRAP_USERNAME|BOOTSTRAP_PASSWORD|SECRET_KEY|ALLOW_DERIVED_SECRET_KEY|SESSION_IDLE_MINUTES|SESSION_ABSOLUTE_MINUTES|COOKIE_SECURE|MFA_REQUIRED|MFA_ISSUER)$");

    public static boolean isLegacyKey(String key) {
        return key != null && LEGACY_KEY.matcher(key).find();
    }

    public static boolean isLegacyEnv(String name) {
        return name != null && LEGACY_ENV.matcher(name).matches();
    }

    /** 구 설정 키 → 새 키. 구 키가 아니면 그대로 */
    public static String mapKey(String key) {
        if (!isLegacyKey(key)) return key;
        String out = key;
        for (Map.Entry<String, String> e : KEY_PREFIX.entrySet()) {
            if (out.startsWith(e.getKey())) { out = e.getValue() + out.substring(e.getKey().length()); break; }
        }
        for (Map.Entry<String, String> e : KEY_ALIAS.entrySet()) {
            if (out.startsWith(e.getKey())) { out = e.getValue() + out.substring(e.getKey().length()); break; }
        }
        return out;
    }

    /** 구 환경변수 이름 → 새 이름. 구 이름이 아니면 그대로 */
    public static String mapEnv(String name) {
        if (!isLegacyEnv(name)) return name;
        for (Map.Entry<String, String> e : ENV_ALIAS.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue() + name.substring(e.getKey().length());
        }
        for (Map.Entry<String, String> e : ENV_PREFIX.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue() + name.substring(e.getKey().length());
        }
        return name;
    }
}
