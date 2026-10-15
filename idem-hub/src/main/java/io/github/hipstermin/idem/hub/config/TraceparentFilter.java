package io.github.hipstermin.idem.hub.config;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * traceparent / X-Correlation-Id 헤더 전파 필터
 * 설계서 §17.5 / §24.4.2 참조 (GAP-API-03)
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>요청에서 traceparent 헤더 추출 (W3C Trace Context)</li>
 *   <li>X-Correlation-Id 헤더 추출 (플랫폼 내부 식별자) — <b>규격 검사</b>(아래)</li>
 *   <li>둘 다 없으면 새로 생성하여 CorrelationIdHolder 에 저장</li>
 *   <li>응답 헤더에 X-Correlation-Id 및 traceparent 포함</li>
 *   <li>요청 처리 완료 후 ThreadLocal 정리</li>
 * </ol>
 *
 * <p><b>1.1.2 G2-8 (성능 측정 중 발견)</b>: 호출자가 보낸 {@code X-Correlation-Id} 를 그대로 믿지 않는다. 규격은
 * {@code [A-Za-z0-9._:-]} 1~36자 — 저장 열({@code handoff_audit.correlation_id}·{@code audit_log.correlation_id} 등 VARCHAR(36))과
 * 로그 안전 문자 집합이다. 규격 밖이면 새 id 로 바꾸고, 응답 헤더·{@code @RequestHeader("X-Correlation-Id")} 를 읽는 컨트롤러·감사가
 * 모두 그 id 를 본다({@link CorrelationIdRequestWrapper}). 종전에는 긴 값이 감사 INSERT 를 깨뜨려 감사 이력이 유실되고(WAL 재생도
 * 제약 위반으로 폐기) 같은 트랜잭션의 outbox 적재까지 실패했다 — 외부 호출자가 헤더 하나로 감사를 비울 수 있었다.
 */
@Slf4j
@Component
public class TraceparentFilter extends OncePerRequestFilter {

    private static final String TRACEPARENT_HEADER   = "traceparent";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String TRACE_VERSION        = "00";

    /** 허용 길이 — 저장 열 VARCHAR(36). UUID·{@code k6-smoke-001}·{@code install-smoke-<epoch>} 같은 값은 그대로 통과한다 */
    static final int CORRELATION_ID_MAX_LENGTH = 36;
    private static final Pattern CORRELATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1," + CORRELATION_ID_MAX_LENGTH + "}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 1. traceparent 헤더 추출 (W3C: 00-{traceId}-{parentId}-{flags})
            String traceparent = request.getHeader(TRACEPARENT_HEADER);
            String incoming = request.getHeader(CORRELATION_ID_HEADER);

            // 2. X-Correlation-Id 결정 — 호출자 값은 규격 안일 때만 쓴다
            String correlationId = null;
            boolean replaced = false;
            if (incoming != null) {
                if (isValidCorrelationId(incoming)) {
                    correlationId = incoming;
                } else {
                    replaced = true;   // 빈 값 포함. 값은 로그에 남기지 않는다(길이만) — 규격 밖 문자열일 수 있다
                }
            }
            if (correlationId == null) {
                if (traceparent != null && !traceparent.isBlank()) {
                    // traceparent에서 traceId 추출 (00-{traceId}-{parentId}-{flags})
                    correlationId = extractTraceId(traceparent);
                } else {
                    correlationId = CorrelationIdHolder.generate();
                }
            }

            // 3. traceparent 없으면 신규 생성
            if (traceparent == null || traceparent.isBlank()) {
                traceparent = buildTraceparent(correlationId);
            }

            // 4. ThreadLocal 저장
            CorrelationIdHolder.set(correlationId);

            // 5. 응답 헤더에 포함 (§24.4.2: 로그 추적 가능)
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            response.setHeader(TRACEPARENT_HEADER, traceparent);

            if (replaced) {
                log.warn("[TraceparentFilter] X-Correlation-Id 가 규격 밖(길이 {}, 허용 1~{}자 [A-Za-z0-9._:-]) — 새 id 로 대체: {} uri={}",
                        incoming.length(), CORRELATION_ID_MAX_LENGTH, correlationId, request.getRequestURI());
            }
            log.debug("[TraceparentFilter] correlationId={} traceparent={}", correlationId, traceparent);

            // 6. 대체했으면 컨트롤러(@RequestHeader)도 대체된 값을 보게 한다
            filterChain.doFilter(replaced ? new CorrelationIdRequestWrapper(request, correlationId) : request, response);

        } finally {
            // 7. ThreadLocal 정리 (메모리 누수 방지)
            CorrelationIdHolder.clear();
        }
    }

    /** 규격: {@code [A-Za-z0-9._:-]} 1~36자 */
    static boolean isValidCorrelationId(String value) {
        return value != null && CORRELATION_ID_PATTERN.matcher(value).matches();
    }

    /** 대체한 X-Correlation-Id 를 요청 헤더로도 내보내는 래퍼 — 다른 헤더는 원본 그대로 */
    static final class CorrelationIdRequestWrapper extends HttpServletRequestWrapper {
        private final String correlationId;

        CorrelationIdRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            return CORRELATION_ID_HEADER.equalsIgnoreCase(name) ? correlationId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return CORRELATION_ID_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(correlationId))
                    : super.getHeaders(name);
        }
    }

    /**
     * W3C traceparent에서 traceId(32 hex chars) 추출
     * 형식: {version}-{traceId}-{parentId}-{flags}
     */
    private String extractTraceId(String traceparent) {
        try {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                // 하이픈 삽입하여 UUID 형식으로 변환
                String hex = parts[1];
                return hex.substring(0, 8) + "-"
                        + hex.substring(8, 12) + "-"
                        + hex.substring(12, 16) + "-"
                        + hex.substring(16, 20) + "-"
                        + hex.substring(20);
            }
        } catch (Exception e) {
            log.debug("[TraceparentFilter] traceparent 파싱 실패, 신규 correlationId 생성: {}", e.getMessage());
        }
        return CorrelationIdHolder.generate();
    }

    /**
     * W3C traceparent 생성: 00-{traceId}-{spanId}-01
     */
    private String buildTraceparent(String correlationId) {
        String traceId = correlationId.replace("-", "").toLowerCase();
        if (traceId.length() < 32) {
            traceId = String.format("%32s", traceId).replace(' ', '0');
        } else if (traceId.length() > 32) {
            traceId = traceId.substring(0, 32);
        }
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return TRACE_VERSION + "-" + traceId + "-" + spanId + "-01";
    }
}
