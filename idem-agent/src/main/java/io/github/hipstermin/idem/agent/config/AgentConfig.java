package io.github.hipstermin.idem.agent.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Properties;

/**
 * OnePass Agent 외부 설정 로더 및 검증기.
 *
 * <h2>설정 우선순위 (높음 → 낮음)</h2>
 * <ol>
 *   <li>agentArgs — {@code -javaagent:onepass-agent.jar=config=/path/to/onepass-agent.properties}</li>
 *   <li>시스템 프로퍼티 — {@code -Donepass.agent.config=/path/to/...}</li>
 *   <li>클래스패스 기본값 — {@code onepass-agent.properties} (classpath root)</li>
 * </ol>
 *
 * <h2>필수 설정 키</h2>
 * <pre>
 *   onepass.agent.endpoint          OnePass 인증 서버 Base URL
 *   onepass.agent.api-key           API 인증 키 (기관 발급)
 * </pre>
 *
 * <h2>선택 설정 키 (기본값 포함)</h2>
 * <pre>
 *   onepass.agent.connect-timeout-ms   HTTP 연결 타임아웃 (기본 5000ms)
 *   onepass.agent.read-timeout-ms      HTTP 읽기 타임아웃 (기본 10000ms)
 *   onepass.agent.max-retry            HTTP 재시도 횟수 (기본 2)
 *   onepass.agent.enabled              Agent 활성화 여부 (기본 true)
 *   onepass.agent.log-level            로그 수준 INFO|WARN|ERROR (기본 INFO)
 *   onepass.agent.hmac-secret          HMAC-SHA256 서명 시크릿 (선택)
 * </pre>
 *
 * <h2>JDK 8 호환</h2>
 * {@link java.util.Properties}, {@link java.io.FileInputStream}만 사용.
 * try-with-resources(JDK 7+) 사용.
 */
public final class AgentConfig {

    // ── 설정 키 상수 ────────────────────────────────────────────────────────────
    public static final String KEY_ENDPOINT            = "onepass.agent.endpoint";
    public static final String KEY_API_KEY             = "onepass.agent.api-key";
    public static final String KEY_HMAC_SECRET         = "onepass.agent.hmac-secret";
    public static final String KEY_CONNECT_TIMEOUT_MS  = "onepass.agent.connect-timeout-ms";
    public static final String KEY_READ_TIMEOUT_MS     = "onepass.agent.read-timeout-ms";
    public static final String KEY_MAX_RETRY           = "onepass.agent.max-retry";
    public static final String KEY_ENABLED             = "onepass.agent.enabled";
    public static final String KEY_LOG_LEVEL           = "onepass.agent.log-level";

    // ── agentArgs 파라미터 키 ───────────────────────────────────────────────────
    private static final String AGENT_ARG_CONFIG = "config";

    // ── 시스템 프로퍼티 키 ─────────────────────────────────────────────────────
    private static final String SYS_PROP_CONFIG = "onepass.agent.config";

    // ── 클래스패스 기본 파일명 ──────────────────────────────────────────────────
    private static final String CLASSPATH_DEFAULT = "onepass-agent.properties";

    // ── 기본값 ──────────────────────────────────────────────────────────────────
    private static final int    DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int    DEFAULT_READ_TIMEOUT_MS    = 10_000;
    private static final int    DEFAULT_MAX_RETRY          = 2;
    private static final String DEFAULT_LOG_LEVEL          = "INFO";

    // ── 필드 ────────────────────────────────────────────────────────────────────
    private final String  endpoint;
    private final String  apiKey;
    private final String  hmacSecret;       // nullable — HMAC 미사용 기관 지원
    private final int     connectTimeoutMs;
    private final int     readTimeoutMs;
    private final int     maxRetry;
    private final boolean enabled;
    private final String  logLevel;

    private AgentConfig(
            String endpoint,
            String apiKey,
            String hmacSecret,
            int    connectTimeoutMs,
            int    readTimeoutMs,
            int    maxRetry,
            boolean enabled,
            String  logLevel) {
        this.endpoint         = endpoint;
        this.apiKey           = apiKey;
        this.hmacSecret       = hmacSecret;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs    = readTimeoutMs;
        this.maxRetry         = maxRetry;
        this.enabled          = enabled;
        this.logLevel         = logLevel;
    }

    // ── 팩토리 메서드 ────────────────────────────────────────────────────────────

    /**
     * agentArgs 문자열을 파싱하고 설정을 로드한다.
     *
     * <p>agentArgs 형식: {@code key1=value1,key2=value2,...}
     * 예: {@code config=/etc/onepass/onepass-agent.properties}
     *
     * @param agentArgs -javaagent 옵션에서 전달된 인수 문자열 (null 허용)
     * @param log       로그 출력 스트림
     * @return 파싱·검증 완료된 {@link AgentConfig}
     * @throws AgentConfigException 필수 설정 누락 또는 값 형식 오류
     */
    public static AgentConfig load(String agentArgs, PrintStream log) {
        Properties props = new Properties();

        // ── 1. agentArgs에서 config 경로 추출 ──────────────────────────────────
        String configPath = parseAgentArg(agentArgs, AGENT_ARG_CONFIG);

        // ── 2. 시스템 프로퍼티 fallback ─────────────────────────────────────────
        if (configPath == null || configPath.isEmpty()) {
            configPath = System.getProperty(SYS_PROP_CONFIG);
        }

        // ── 3. 파일 또는 클래스패스에서 Properties 로드 ─────────────────────────
        if (configPath != null && !configPath.isEmpty()) {
            loadFromFile(configPath, props, log);
        } else {
            loadFromClasspath(props, log);
        }

        // ── 4. 시스템 프로퍼티로 개별 키 오버라이드 ─────────────────────────────
        //     (운영 환경에서 파일 없이 -D 옵션만으로 설정 가능하게)
        overrideFromSystemProperties(props);

        // ── 5. 검증 및 객체 생성 ─────────────────────────────────────────────────
        return build(props, log);
    }

    // ── 게터 ─────────────────────────────────────────────────────────────────────

    /** OnePass 인증 서버 Base URL (ex: {@code https://sso.example.org}) */
    public String endpoint() { return endpoint; }

    /** API 인증 키 */
    public String apiKey() { return apiKey; }

    /** HMAC-SHA256 서명 시크릿 (null이면 HMAC 미사용) */
    public String hmacSecret() { return hmacSecret; }

    /** HTTP 연결 타임아웃 (ms) */
    public int connectTimeoutMs() { return connectTimeoutMs; }

    /** HTTP 읽기 타임아웃 (ms) */
    public int readTimeoutMs() { return readTimeoutMs; }

    /** HTTP 재시도 횟수 */
    public int maxRetry() { return maxRetry; }

    /** Agent 활성화 여부 (false이면 위빙 건너뜀) */
    public boolean isEnabled() { return enabled; }

    /** 로그 수준 (INFO / WARN / ERROR) */
    public String logLevel() { return logLevel; }

    /** HMAC 시크릿이 설정됐는지 확인 */
    public boolean isHmacEnabled() {
        return hmacSecret != null && !hmacSecret.isEmpty();
    }

    /**
     * 설정 요약 문자열 (민감 정보는 마스킹).
     * premain 단계 로그 출력에 사용.
     */
    @Override
    public String toString() {
        return "AgentConfig{"
                + "endpoint='" + endpoint + '\''
                + ", apiKey='" + mask(apiKey) + '\''
                + ", hmacEnabled=" + isHmacEnabled()
                + ", connectTimeoutMs=" + connectTimeoutMs
                + ", readTimeoutMs=" + readTimeoutMs
                + ", maxRetry=" + maxRetry
                + ", enabled=" + enabled
                + ", logLevel='" + logLevel + '\''
                + '}';
    }

    // ── 내부 구현 ────────────────────────────────────────────────────────────────

    /**
     * agentArgs에서 특정 키의 값을 파싱한다.
     * 형식: {@code key1=value1,key2=value2}
     */
    static String parseAgentArg(String agentArgs, String key) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return null;
        }
        // 쉼표로 분리된 key=value 쌍 순회
        for (String pair : agentArgs.split(",")) {
            pair = pair.trim();
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq).trim();
            if (key.equals(k)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** 파일시스템에서 Properties 로드 */
    private static void loadFromFile(String path, Properties props, PrintStream log) {
        File file = new File(path);
        if (!file.exists()) {
            log.println("[WARN] [AgentConfig] 설정 파일 없음: " + path
                    + " — 클래스패스 기본값 또는 시스템 프로퍼티로 전환");
            loadFromClasspath(props, log);
            return;
        }
        if (!file.isFile() || !file.canRead()) {
            throw new AgentConfigException("설정 파일을 읽을 수 없습니다: " + path);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
            log.println("[AgentConfig] 설정 파일 로드: " + file.getAbsolutePath());
        } catch (IOException e) {
            throw new AgentConfigException("설정 파일 읽기 실패: " + path, e);
        }
    }

    /** 클래스패스에서 Properties 로드 */
    private static void loadFromClasspath(Properties props, PrintStream log) {
        InputStream is = AgentConfig.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_DEFAULT);
        if (is == null) {
            // 클래스패스에도 없음 — 시스템 프로퍼티 단독 사용
            log.println("[WARN] [AgentConfig] 클래스패스에 " + CLASSPATH_DEFAULT
                    + " 없음 — 시스템 프로퍼티(-D) 전용 설정 모드");
            return;
        }
        try (InputStream stream = is) {
            props.load(stream);
            log.println("[AgentConfig] 클래스패스 기본 설정 로드: " + CLASSPATH_DEFAULT);
        } catch (IOException e) {
            throw new AgentConfigException("클래스패스 설정 로드 실패", e);
        }
    }

    /**
     * 시스템 프로퍼티(-D)로 Properties를 오버라이드.
     * 파일 설정보다 -D 옵션이 우선하도록 보장.
     */
    private static void overrideFromSystemProperties(Properties props) {
        overrideKey(props, KEY_ENDPOINT);
        overrideKey(props, KEY_API_KEY);
        overrideKey(props, KEY_HMAC_SECRET);
        overrideKey(props, KEY_CONNECT_TIMEOUT_MS);
        overrideKey(props, KEY_READ_TIMEOUT_MS);
        overrideKey(props, KEY_MAX_RETRY);
        overrideKey(props, KEY_ENABLED);
        overrideKey(props, KEY_LOG_LEVEL);
    }

    private static void overrideKey(Properties props, String key) {
        String val = System.getProperty(key);
        if (val != null && !val.isEmpty()) {
            props.setProperty(key, val);
        }
    }

    /** Properties → AgentConfig 변환 + 검증 */
    private static AgentConfig build(Properties props, PrintStream log) {
        // ── 필수 항목 검증 ─────────────────────────────────────────────────────
        String endpoint = require(props, KEY_ENDPOINT);
        String apiKey   = require(props, KEY_API_KEY);

        // endpoint 형식 검증 (http/https)
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new AgentConfigException(
                    KEY_ENDPOINT + " 은 http:// 또는 https://로 시작해야 합니다: " + endpoint);
        }
        // endpoint trailing slash 정규화
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }

        // ── 선택 항목 ──────────────────────────────────────────────────────────
        String  hmacSecret       = props.getProperty(KEY_HMAC_SECRET);    // nullable
        int     connectTimeoutMs = parseInt(props, KEY_CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
        int     readTimeoutMs    = parseInt(props, KEY_READ_TIMEOUT_MS,    DEFAULT_READ_TIMEOUT_MS);
        int     maxRetry         = parseInt(props, KEY_MAX_RETRY,          DEFAULT_MAX_RETRY);
        boolean enabled          = parseBoolean(props, KEY_ENABLED, true);
        String  logLevel         = parseLogLevel(props.getProperty(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL));

        // ── 범위 검증 ──────────────────────────────────────────────────────────
        if (connectTimeoutMs <= 0) throw new AgentConfigException(KEY_CONNECT_TIMEOUT_MS + " 은 양수여야 합니다");
        if (readTimeoutMs    <= 0) throw new AgentConfigException(KEY_READ_TIMEOUT_MS    + " 은 양수여야 합니다");
        if (maxRetry < 0)          throw new AgentConfigException(KEY_MAX_RETRY          + " 은 0 이상이어야 합니다");

        AgentConfig config = new AgentConfig(
                endpoint, apiKey, hmacSecret,
                connectTimeoutMs, readTimeoutMs, maxRetry,
                enabled, logLevel);

        log.println("[AgentConfig] 설정 로드 완료: " + config);
        return config;
    }

    /** 필수 키 조회 — 없으면 AgentConfigException */
    private static String require(Properties props, String key) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) {
            throw new AgentConfigException("필수 설정 누락: " + key
                    + " — 설정 파일 또는 -D" + key + "=<값> 으로 지정하세요");
        }
        return val.trim();
    }

    private static int parseInt(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new AgentConfigException(key + " 은 정수여야 합니다: " + val);
        }
    }

    private static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
        String val = props.getProperty(key);
        if (val == null || val.trim().isEmpty()) return defaultValue;
        return "true".equalsIgnoreCase(val.trim());
    }

    private static String parseLogLevel(String value) {
        String upper = value.toUpperCase();
        if ("INFO".equals(upper) || "WARN".equals(upper) || "ERROR".equals(upper)) {
            return upper;
        }
        throw new AgentConfigException(KEY_LOG_LEVEL + " 은 INFO|WARN|ERROR 중 하나여야 합니다: " + value);
    }

    /** API 키 등 민감 정보 마스킹 (앞 4자리 + ***) */
    static String mask(String value) {
        if (value == null || value.length() <= 4) return "****";
        return value.substring(0, 4) + "****";
    }

    // ── 내부 예외 클래스 ──────────────────────────────────────────────────────────

    /**
     * Agent 설정 로드/검증 실패 시 던지는 RuntimeException.
     *
     * <p>{@code premain()} 단계에서 이 예외가 잡히지 않으면 JVM이 시작을 중단하므로,
     * 호출자({@link io.github.hipstermin.idem.agent.core.OnePassAgentMain})에서 적절히 처리해야 한다.
     */
    public static final class AgentConfigException extends RuntimeException {
        public AgentConfigException(String message) {
            super(message);
        }
        public AgentConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
