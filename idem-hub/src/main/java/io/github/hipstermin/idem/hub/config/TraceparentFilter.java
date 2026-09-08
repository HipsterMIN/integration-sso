package io.github.hipstermin.idem.hub.config;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
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
 *   <li>X-Correlation-Id 헤더 추출 (플랫폼 내부 식별자)</li>
 *   <li>둘 다 없으면 새로 생성하여 CorrelationIdHolder 에 저장</li>
 *   <li>응답 헤더에 X-Correlation-Id 및 traceparent 포함</li>
 *   <li>요청 처리 완료 후 ThreadLocal 정리</li>
 * </ol>
 */
@Slf4j
@Component
public class TraceparentFilter extends OncePerRequestFilter {

    private static final String TRACEPARENT_HEADER   = "traceparent";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String TRACE_VERSION        = "00";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 1. traceparent 헤더 추출 (W3C: 00-{traceId}-{parentId}-{flags})
            String traceparent = request.getHeader(TRACEPARENT_HEADER);
            String correlationId = request.getHeader(CORRELATION_ID_HEADER);

            // 2. X-Correlation-Id 결정
            if (correlationId == null || correlationId.isBlank()) {
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

            log.debug("[TraceparentFilter] correlationId={} traceparent={}", correlationId, traceparent);

            filterChain.doFilter(request, response);

        } finally {
            // 6. ThreadLocal 정리 (메모리 누수 방지)
            CorrelationIdHolder.clear();
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
