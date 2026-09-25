package io.github.hipstermin.idem.hub.kr.auth.tracing;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * KR 에디션 OTel 추적 — NICE CI 조회·기업인증 콜백·통합인증 서버 호출. (S8-a: 코어 {@code AuthTracingAspect} 에서 분리)
 */
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "idem.hub.tracing.auth-aspect-enabled", havingValue = "true", matchIfMissing = true)
public class KrAuthTracingAspect {

    private final Tracer tracer;

    @Around("execution(* io.github.hipstermin.idem.hub.kr.auth.service.AuthService.checkNiceCi(..))")
    public Object traceNiceCiCheck(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp, "auth.ci-check", "nice", "ci-check", false);
    }

    @Around("execution(* io.github.hipstermin.idem.hub.kr.auth.service.AuthService.callback(..))")
    public Object traceAuthCallback(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp, "integration.callback", "integration", "callback", false);
    }

    @Around("execution(* io.github.hipstermin.idem.hub.kr.auth.client.IntegrationAuthClient.sendAuthCheck(..))")
    public Object traceIntegrationAuthCheck(ProceedingJoinPoint pjp) throws Throwable {
        return trace(pjp, "integration.api.auth-check", "integration.api", null, true);
    }

    private Object trace(ProceedingJoinPoint pjp, String spanName, String service, String operation, boolean client)
            throws Throwable {
        if (tracer == null) {
            return pjp.proceed();
        }
        Span span = tracer.nextSpan().name(spanName)
                .tag("correlation.id", String.valueOf(CorrelationIdHolder.get()));
        if (client) {
            span.tag("span.kind", "CLIENT").tag("peer.service", service);
        } else {
            span.tag("auth.service", service).tag("auth.operation", operation);
        }
        span.start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            Object result = pjp.proceed();
            span.tag("auth.result", "success");
            return result;
        } catch (Throwable t) {
            span.tag("auth.result", "error");
            span.tag("error.type", t.getClass().getSimpleName());
            String msg = t.getMessage() != null
                    ? t.getMessage().substring(0, Math.min(t.getMessage().length(), 100))
                    : "unknown";
            span.tag("error.message", msg);
            span.error(t);
            throw t;
        } finally {
            span.end();
        }
    }
}
