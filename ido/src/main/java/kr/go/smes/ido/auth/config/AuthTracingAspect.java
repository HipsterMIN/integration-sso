package kr.go.smes.ido.auth.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import kr.go.smes.common.util.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Auth 서비스 OTel 분산 추적 Aspect (S9-T4)
 *
 * <p><b>추적 대상:</b>
 * <ul>
 *   <li>{@code NiceAuthService.getNicePhoneAuthUrl()} — NICE URL 발급 스팬</li>
 *   <li>{@code NiceAuthService.getNicePhoneAuthResult()} — NICE 결과 조회 + CI 처리 스팬</li>
 *   <li>{@code AuthService.checkNiceCi()} — CI 기반 회원 확인 스팬</li>
 *   <li>{@code AuthService.handleOacxEasysign()} — OACX 간편서명 스팬</li>
 *   <li>{@code NiceApiClient.*} — NICE 외부 API 호출 스팬 (HTTP 호출 단위)</li>
 *   <li>{@code IntegrationAuthClient.*} — 통합인증 서버 HTTP 호출 스팬</li>
 * </ul>
 *
 * <p><b>스팬 명명 규칙:</b>
 * <pre>
 * ido.auth.nice.phone-url          — NICE URL 발급
 * ido.auth.nice.phone-result       — NICE 결과 복호화
 * ido.auth.nice.ci-check           — CI 기반 회원 확인
 * ido.auth.oacx.easysign           — OACX 간편서명
 * ido.auth.nice.api.fetch-token    — NICE 토큰 발급 API
 * ido.auth.nice.api.request-url    — NICE URL 발급 API
 * ido.auth.nice.api.request-result — NICE 결과 조회 API
 * ido.auth.integration.auth-check  — 통합인증 서버 auth-check
 * </pre>
 *
 * <p><b>스팬 태그 정책 (PII 보호):</b>
 * <ul>
 *   <li>correlationId, requestNo, provider: 허용 (저카디널리티, 비PII)</li>
 *   <li>CI, DI, name, birthdate, phone: 절대 금지 (PII)</li>
 *   <li>오류 시: error=true, error.message (스택 트레이스 제외)</li>
 * </ul>
 *
 * <p><b>Micrometer Tracing vs OTel API:</b>
 * Spring Boot 3.x는 {@code Tracer}가 Micrometer Tracing Bridge를 통해
 * OTel SDK로 연결된다. 직접 OTel API를 사용하지 않아도 됨.
 *
 * @see NiceAuthService
 * @see AuthService
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuthTracingAspect {

    private final Tracer tracer;
    private final ObservationRegistry observationRegistry;

    // ─── NICE Auth Service 스팬 ───────────────────────────────────────────────

    /**
     * NICE 휴대폰 인증 URL 발급 스팬
     * 메서드: NiceAuthService.getNicePhoneAuthUrl()
     */
    @Around("execution(* kr.go.smes.ido.auth.service.NiceAuthService.getNicePhoneAuthUrl(..))")
    public Object traceNicePhoneAuthUrl(ProceedingJoinPoint pjp) throws Throwable {
        return traceAuthOperation(pjp, "ido.auth.nice.phone-url", "nice");
    }

    /**
     * NICE 인증 결과 조회 + CI 처리 스팬
     * 메서드: NiceAuthService.getNicePhoneAuthResult()
     */
    @Around("execution(* kr.go.smes.ido.auth.service.NiceAuthService.getNicePhoneAuthResult(..))")
    public Object traceNicePhoneAuthResult(ProceedingJoinPoint pjp) throws Throwable {
        return traceAuthOperation(pjp, "ido.auth.nice.phone-result", "nice");
    }

    // ─── Auth Service 스팬 ────────────────────────────────────────────────────

    /**
     * NICE CI 기반 회원 확인 스팬
     * 메서드: AuthService.checkNiceCi()
     */
    @Around("execution(* kr.go.smes.ido.auth.service.AuthService.checkNiceCi(..))")
    public Object traceNiceCiCheck(ProceedingJoinPoint pjp) throws Throwable {
        return traceAuthOperation(pjp, "ido.auth.nice.ci-check", "nice");
    }

    /**
     * OACX 간편서명 처리 스팬
     * 메서드: AuthService.handleOacxEasysign()
     */
    @Around("execution(* kr.go.smes.ido.auth.service.AuthService.handleOacxEasysign(..))")
    public Object traceOacxEasysign(ProceedingJoinPoint pjp) throws Throwable {
        return traceAuthOperation(pjp, "ido.auth.oacx.easysign", "oacx");
    }

    // ─── NiceApiClient HTTP 호출 스팬 ─────────────────────────────────────────

    /**
     * NICE Access Token 발급 API 스팬
     * 메서드: NiceApiClient.fetchAccessToken()
     */
    @Around("execution(* kr.go.smes.ido.auth.client.NiceApiClient.fetchAccessToken(..))")
    public Object traceNiceFetchToken(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "ido.auth.nice.api.fetch-token", "nice.api");
    }

    /**
     * NICE 표준창 URL 발급 API 스팬
     * 메서드: NiceApiClient.requestAuthUrl()
     */
    @Around("execution(* kr.go.smes.ido.auth.client.NiceApiClient.requestAuthUrl(..))")
    public Object traceNiceRequestUrl(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "ido.auth.nice.api.request-url", "nice.api");
    }

    /**
     * NICE 인증 결과 조회 API 스팬
     * 메서드: NiceApiClient.requestAuthResult()
     */
    @Around("execution(* kr.go.smes.ido.auth.client.NiceApiClient.requestAuthResult(..))")
    public Object traceNiceRequestResult(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "ido.auth.nice.api.request-result", "nice.api");
    }

    // ─── IntegrationAuthClient HTTP 호출 스팬 ────────────────────────────────

    /**
     * 통합인증 서버 auth-check 스팬
     * 메서드: IntegrationAuthClient.sendAuthCheck()
     */
    @Around("execution(* kr.go.smes.ido.auth.client.IntegrationAuthClient.sendAuthCheck(..))")
    public Object traceIntegrationAuthCheck(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "ido.auth.integration.auth-check", "integration.api");
    }

    // ─── 공통 스팬 헬퍼 ──────────────────────────────────────────────────────

    /**
     * Auth 서비스 레이어 메서드 추적 공통 로직
     *
     * <p>비즈니스 스팬: correlationId, provider 태그 추가.
     * PII 데이터(CI/DI/이름/전화번호)는 절대 태그에 포함하지 않음.
     */
    private Object traceAuthOperation(ProceedingJoinPoint pjp,
                                       String spanName,
                                       String provider) throws Throwable {
        Span span = tracer.nextSpan().name(spanName).start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            // 저카디널리티 태그만 허용 (PII 금지)
            span.tag("auth.provider", provider);
            span.tag("correlation.id", CorrelationIdHolder.get() != null
                    ? CorrelationIdHolder.get() : "unknown");
            span.event("auth.start");

            Object result = pjp.proceed();

            span.event("auth.complete");
            return result;
        } catch (Throwable t) {
            span.tag("error", "true");
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
     * 외부 API 클라이언트 HTTP 호출 추적 공통 로직
     *
     * <p>외부 시스템 스팬: span.kind=CLIENT, peer.service 태그 추가.
     */
    private Object traceExternalApiCall(ProceedingJoinPoint pjp,
                                         String spanName,
                                         String peerService) throws Throwable {
        Span span = tracer.nextSpan()
                .name(spanName)
                .tag("span.kind", "CLIENT")
                .start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("peer.service", peerService);
            span.tag("correlation.id", CorrelationIdHolder.get() != null
                    ? CorrelationIdHolder.get() : "unknown");

            Object result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            span.tag("error", "true");
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
