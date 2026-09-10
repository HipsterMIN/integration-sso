package io.github.hipstermin.idem.hub.auth.tracing;

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
 * 본인인증 흐름 분산 추적 AOP (S9-T4, S5a 에서 벤더 무관으로 정리).
 *
 * <p>스팬:
 * <ul>
 *   <li>{@code auth.provider.initiate / complete} — SPI 컨트롤러({@code /api/v1/auth/providers/{code}/…})</li>
 *   <li>{@code auth.ci-check} · {@code integration.callback} · {@code integration.auth-check}</li>
 * </ul>
 * 벤더 API 호출 스팬(NICE 토큰·URL·결과)은 플러그인이 필요하면 자체 계측한다.
 * 비활성화: {@code ido.tracing.auth-aspect-enabled=false}.
 */
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ido.tracing.auth-aspect-enabled",
        havingValue = "true", matchIfMissing = true)
public class AuthTracingAspect {

    private final Tracer tracer;

    // ─────────────────────────────────────────────────────────────────────────
    // 본인인증 SPI 추적 (벤더 무관, S5a) — 제공자 코드는 경로 변수로 스팬 속성에 싣는다
    // ─────────────────────────────────────────────────────────────────────────
    @Around("execution(* io.github.hipstermin.idem.hub.auth.spi.IdentityVerificationController.initiate(..))")
    public Object traceProviderInitiate(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "auth.provider.initiate", "spi", "initiate");
    }

    @Around("execution(* io.github.hipstermin.idem.hub.auth.spi.IdentityVerificationController.complete(..))")
    public Object traceProviderComplete(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "auth.provider.complete", "spi", "complete");
    }

    /**
     * CI 기반 회원 확인 추적
     *
     * <p>스팬명: {@code auth.ci-check}
     * CI는 태그에 포함하지 않음 (PII 보호).
     * 태그: auth.service=nice, auth.operation=ci-check
     */
    @Around("execution(* io.github.hipstermin.idem.hub.auth.service.AuthService.checkNiceCi(..))")
    public Object traceNiceCiCheck(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "auth.ci-check", "nice", "ci-check");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 기업인증 콜백 추적
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 기업인증 콜백 처리 추적
     *
     * <p>스팬명: {@code integration.callback}
     * 태그: auth.service=integration, auth.operation=callback
     */
    @Around("execution(* io.github.hipstermin.idem.hub.auth.service.AuthService.callback(..))")
    public Object traceAuthCallback(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "integration.callback", "integration", "callback");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 외부 HTTP 호출 스팬 (CLIENT 스팬) — 코어가 직접 부르는 통합인증 서버만. 벤더 API 호출 추적은 플러그인 몫
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 통합인증 서버 auth-check 외부 API 호출 추적
     *
     * <p>스팬명: {@code integration.api.auth-check}
     * 태그: span.kind=CLIENT, peer.service=integration.api
     */
    @Around("execution(* io.github.hipstermin.idem.hub.auth.client.IntegrationAuthClient.sendAuthCheck(..))")
    public Object traceIntegrationAuthCheck(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "integration.api.auth-check", "integration.api");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 공통 추적 메서드
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 비즈니스 레이어 메서드 실행을 스팬으로 감싸는 공통 로직
     *
     * <p>현재 활성 스팬의 자식 스팬으로 생성하여 분산 추적 계층 구조 유지.
     * {@code Tracer}가 null이거나 스팬 생성 실패 시 원본 메서드를 그대로 실행
     * (추적 실패가 비즈니스 실패로 전파되지 않음).
     *
     * @param pjp       AOP Join Point
     * @param spanName  스팬 이름
     * @param service   auth.service 태그 값
     * @param operation auth.operation 태그 값
     * @return 원본 메서드 반환값
     * @throws Throwable 원본 메서드 예외 재전파
     */
    private Object traceMethod(ProceedingJoinPoint pjp, String spanName,
                                String service, String operation) throws Throwable {
        if (tracer == null) {
            return pjp.proceed();
        }

        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("auth.service", service)
                .tag("auth.operation", operation)
                .tag("correlation.id", String.valueOf(CorrelationIdHolder.get()))
                .start();

        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            Object result = pjp.proceed();
            span.tag("auth.result", "success");
            return result;
        } catch (Throwable t) {
            span.tag("auth.result", "error");
            span.tag("error.type", t.getClass().getSimpleName());
            // 에러 메시지 단축 (PII 포함 가능성 방지: 앞 100자만)
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

    /**
     * 외부 HTTP 클라이언트 호출을 CLIENT 스팬으로 감싸는 공통 로직
     *
     * <p>span.kind=CLIENT 태그로 분산 추적 토폴로지에서 외부 호출임을 명시.
     * {@code Tracer}가 null인 경우 원본 메서드를 그대로 실행.
     *
     * @param pjp         AOP Join Point
     * @param spanName    스팬 이름
     * @param peerService peer.service 태그 값 (호출 대상 외부 서비스명)
     * @return 원본 메서드 반환값
     * @throws Throwable 원본 메서드 예외 재전파
     */
    private Object traceExternalApiCall(ProceedingJoinPoint pjp,
                                         String spanName,
                                         String peerService) throws Throwable {
        if (tracer == null) {
            return pjp.proceed();
        }

        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("span.kind", "CLIENT")
                .tag("peer.service", peerService)
                .tag("correlation.id", String.valueOf(CorrelationIdHolder.get()))
                .start();

        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            Object result = pjp.proceed();
            span.tag("auth.result", "success");
            return result;
        } catch (Throwable t) {
            span.tag("auth.result", "error");
            span.tag("error.type", t.getClass().getSimpleName());
            // 에러 메시지 단축 (PII 포함 가능성 방지: 앞 100자만)
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
