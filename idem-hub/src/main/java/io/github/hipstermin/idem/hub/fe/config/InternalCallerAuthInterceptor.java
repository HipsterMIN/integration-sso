package io.github.hipstermin.idem.hub.fe.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * F4.8 — 내부 호출자(서비스간) 인증 인터셉터 (Sprint β-2)
 *
 * <h3>배경</h3>
 * <p>{@link io.github.hipstermin.idem.hub.fe.api.FeSessionController#createSession} 은 본래
 * Q-Sign → IdO 콜백 경로에서만 호출되어야 하지만, 코드상 어떤 인증/인가 검증도 수행하지 않아
 * 네트워크 격리가 깨지는 순간 임의 사용자의 세션이 발급되는 결함이 있었다
 * (04_handoff_flow.md F4.8 — Risk Score 10).
 *
 * <h3>설계</h3>
 * <ol>
 *   <li>요청 헤더 {@code X-Internal-Caller}(식별자, e.g. {@code q-sign}) +
 *       {@code X-Internal-Api-Key}(공유 시크릿 평문) 검증.</li>
 *   <li>시크릿은 application.yml {@code ido.internal.callers.{caller}=<key>} 로 주입.
 *       운영은 K8s Secret / Vault → 환경변수.</li>
 *   <li>비교는 {@link MessageDigest#isEqual} 상수시간.</li>
 *   <li>실패 시 401 + {@code {error, message}} JSON 응답.</li>
 *   <li>성공 시 Request Attribute {@link #ATTR_VALIDATED_CALLER} 에 caller 식별자 저장.</li>
 * </ol>
 *
 * <h3>부팅 검증 (fail-fast)</h3>
 * <ul>
 *   <li>{@code ido.internal.callers} 가 비어 있는데 {@code ido.internal.allow-empty-callers=false}
 *       이면 {@code @PostConstruct} 단계에서 {@link IllegalStateException} 발생.</li>
 *   <li>운영 환경에서 환경변수 누락 시 CrashLoopBackOff 로 즉시 인지.</li>
 *   <li>테스트/로컬은 {@code ido.internal.allow-empty-callers=true} 로 escape.</li>
 *   <li>α-3 {@code F4.3 ValidateSigningSecret} 와 동일 패턴.</li>
 * </ul>
 *
 * <h3>메트릭</h3>
 * <pre>
 * ido_internal_caller_auth_total{result="valid|missing|invalid|caller_unknown"}
 * </pre>
 *
 * <h3>등록 경로</h3>
 * <p>{@link IdoWebMvcConfig#addInterceptors} 에서
 * {@code POST /api/v1/fe-session} + {@code POST /api/v1/fe-session/conversion}
 * 에 적용. {@code GET /api/v1/fe-session/check} 와 {@code POST /api/v1/fe-session/logout}
 * 은 사용자(쿠키 보유자)가 직접 호출하므로 본 인터셉터 대상이 아니다.
 *
 * @see io.github.hipstermin.idem.hub.fe.api.FeSessionController
 * @see docs/analysis/sso-im-readiness/04_handoff_flow.md (F4.8)
 */
@Slf4j
@Component
public class InternalCallerAuthInterceptor implements HandlerInterceptor {

    /** Request Attribute 키 — 컨트롤러가 검증된 caller 식별자를 조회할 때 사용 */
    public static final String ATTR_VALIDATED_CALLER = "validatedInternalCaller";

    public static final String HEADER_CALLER  = "X-Internal-Caller";
    public static final String HEADER_API_KEY = "X-Internal-Api-Key";

    /** 메트릭 이름 (Prometheus 노출 시 {@code ido_internal_caller_auth_total}) */
    public static final String METRIC_NAME = "ido.internal.caller.auth.total";
    public static final String TAG_RESULT  = "result";

    public static final String RESULT_VALID          = "valid";
    public static final String RESULT_MISSING        = "missing";
    public static final String RESULT_INVALID        = "invalid";
    public static final String RESULT_CALLER_UNKNOWN = "caller_unknown";

    private final InternalCallersProperties properties;
    private final MeterRegistry registry;

    public InternalCallerAuthInterceptor(InternalCallersProperties properties,
                                         MeterRegistry registry) {
        this.properties = properties;
        this.registry   = registry;
        // 시계열 즉시 노출
        for (String r : new String[] {
                RESULT_VALID, RESULT_MISSING, RESULT_INVALID, RESULT_CALLER_UNKNOWN }) {
            Counter.builder(METRIC_NAME)
                    .description("내부 호출자(서비스간) 인증 결과 카운터 (F4.8)")
                    .tag(TAG_RESULT, r)
                    .register(registry);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 부팅 검증 (fail-fast)
    // ════════════════════════════════════════════════════════════════════

    @PostConstruct
    void validateCallers() {
        Map<String, String> callers = properties.getCallers();
        boolean allowEmpty           = properties.isAllowEmptyCallers();

        if (callers == null || callers.isEmpty()) {
            if (!allowEmpty) {
                throw new IllegalStateException(
                        "[F4.8] ido.internal.callers 가 비어 있습니다. " +
                        "운영에서는 최소 1개 caller(API key) 설정이 필요합니다. " +
                        "테스트 전용 escape: ido.internal.allow-empty-callers=true");
            }
            log.warn("[InternalCallerAuth] ido.internal.callers 비어 있음 (allow-empty-callers=true 로 통과). " +
                     "운영 환경에서는 절대 사용 금지.");
            return;
        }

        // 빈 키 검출
        for (Map.Entry<String, String> e : callers.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                if (!allowEmpty) {
                    throw new IllegalStateException(
                            "[F4.8] ido.internal.callers." + e.getKey() +
                            " 가 빈 문자열입니다. 환경변수 주입을 확인하세요.");
                }
                log.warn("[InternalCallerAuth] ido.internal.callers.{} 빈 값 (allow-empty-callers=true 로 통과)",
                         e.getKey());
            }
        }

        Set<String> names = new TreeSet<>(callers.keySet());
        log.info("[InternalCallerAuth] 등록된 내부 호출자({}건): {}", names.size(), names);
    }

    // ════════════════════════════════════════════════════════════════════
    // HandlerInterceptor
    // ════════════════════════════════════════════════════════════════════

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest  request,
            @NonNull HttpServletResponse response,
            @NonNull Object              handler) throws Exception {

        String caller = trimToNull(request.getHeader(HEADER_CALLER));
        String apiKey = trimToNull(request.getHeader(HEADER_API_KEY));

        // ① 두 헤더 모두 필수
        if (caller == null || apiKey == null) {
            log.warn("[InternalCallerAuth] 필수 헤더 누락: caller-present={} key-present={} uri={}",
                     caller != null, apiKey != null, request.getRequestURI());
            increment(RESULT_MISSING);
            sendUnauthorized(response, "MISSING_INTERNAL_CREDENTIALS",
                    "X-Internal-Caller 및 X-Internal-Api-Key 헤더가 필요합니다.");
            return false;
        }

        // ② caller 등록 여부
        Map<String, String> callers = properties.getCallers();
        String expected = (callers != null) ? callers.get(caller) : null;
        if (expected == null || expected.isBlank()) {
            log.warn("[InternalCallerAuth] 등록되지 않은 caller: caller={} uri={}",
                     caller, request.getRequestURI());
            increment(RESULT_CALLER_UNKNOWN);
            // 정보 노출 방지를 위해 INVALID 와 동일한 응답 메시지
            sendUnauthorized(response, "INVALID_INTERNAL_CREDENTIALS",
                    "내부 호출자 인증에 실패했습니다.");
            return false;
        }

        // ③ 상수시간 비교 (apiKey 평문 → 등록된 평문 비교)
        if (!constantTimeEquals(apiKey, expected)) {
            log.warn("[InternalCallerAuth] API Key 불일치: caller={} uri={}",
                     caller, request.getRequestURI());
            increment(RESULT_INVALID);
            sendUnauthorized(response, "INVALID_INTERNAL_CREDENTIALS",
                    "내부 호출자 인증에 실패했습니다.");
            return false;
        }

        // ④ 성공 — Request Attribute 저장 (감사 로그용)
        request.setAttribute(ATTR_VALIDATED_CALLER, caller);
        increment(RESULT_VALID);
        log.debug("[InternalCallerAuth] 인증 성공: caller={} uri={}", caller, request.getRequestURI());
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private void increment(String result) {
        registry.counter(METRIC_NAME, TAG_RESULT, result).increment();
    }

    /** 상수시간 비교 (타이밍 공격 방지) */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void sendUnauthorized(HttpServletResponse response,
                                  String errorCode,
                                  String message) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"error\":\"" + errorCode + "\",\"message\":\"" +
                message.replace("\"", "'") + "\"}"
        );
    }

    // ════════════════════════════════════════════════════════════════════
    // Properties 바인딩
    // ════════════════════════════════════════════════════════════════════

    /**
     * {@code ido.internal} 프로퍼티 바인딩.
     *
     * <pre>
     * ido:
     *   internal:
     *     callers:
     *       q-sign:       ${IDO_INTERNAL_API_KEY_QSIGN:}
     *       outbox-relay: ${IDO_INTERNAL_API_KEY_OUTBOX:}
     *     allow-empty-callers: false   # 테스트 전용 escape
     * </pre>
     */
    @Configuration
    @ConfigurationProperties(prefix = "ido.internal")
    public static class InternalCallersProperties {
        private Map<String, String> callers = new HashMap<>();
        private boolean allowEmptyCallers = false;

        public Map<String, String> getCallers() {
            return callers != null ? callers : Collections.emptyMap();
        }
        public void setCallers(Map<String, String> callers) {
            this.callers = callers;
        }
        public boolean isAllowEmptyCallers() {
            return allowEmptyCallers;
        }
        public void setAllowEmptyCallers(boolean allowEmptyCallers) {
            this.allowEmptyCallers = allowEmptyCallers;
        }
    }
}
