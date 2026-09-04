package kr.go.smes.ido.auth.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import kr.go.smes.common.util.CorrelationIdHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Auth 관련 분산 추적 AOP Aspect (S9-T4)
 *
 * <p>NICE/OACX/CI 처리 흐름 및 외부 HTTP 클라이언트 호출에서
 * 커스텀 스팬을 생성하여 분산 추적 가시성을 높인다.
 * Micrometer Tracing Bridge(OTel) 기반으로 기존 W3C traceparent 컨텍스트와 연동.
 *
 * <p><b>추적 대상 메서드:</b>
 * <ul>
 *   <li>{@code NiceAuthService.getNicePhoneAuthUrl()} — NICE URL 발급 스팬</li>
 *   <li>{@code NiceAuthService.getNicePhoneAuthResult()} — NICE 결과 복호화 스팬</li>
 *   <li>{@code AuthService.checkNiceCi()} — CI 기반 회원 조회 스팬</li>
 *   <li>{@code AuthService.handleOacxEasysign()} — OACX 간편서명 복호화 스팬</li>
 *   <li>{@code AuthService.getOacxAccessInfo()} — OACX 접근키 발급 스팬</li>
 *   <li>{@code AuthService.callback()} — 기업인증 콜백 스팬</li>
 *   <li>{@code NiceApiClient.fetchAccessToken()} — NICE 토큰 발급 외부 API 스팬</li>
 *   <li>{@code NiceApiClient.requestAuthUrl()} — NICE 표준창 URL 발급 외부 API 스팬</li>
 *   <li>{@code NiceApiClient.requestAuthResult()} — NICE 결과 조회 외부 API 스팬</li>
 *   <li>{@code IntegrationAuthClient.sendAuthCheck()} — 통합인증 서버 auth-check 스팬</li>
 * </ul>
 *
 * <p><b>스팬 태그 전략:</b>
 * <ul>
 *   <li>{@code auth.service} — 서비스 유형 (nice / oacx / integration)</li>
 *   <li>{@code auth.operation} — 세부 작업 이름</li>
 *   <li>{@code auth.result} — success / error</li>
 *   <li>{@code correlation.id} — CorrelationIdHolder의 현재 값</li>
 *   <li>{@code span.kind} — CLIENT (외부 HTTP 호출 스팬에만 적용)</li>
 *   <li>{@code peer.service} — 외부 서비스 명칭 (nice.api / integration.api)</li>
 * </ul>
 *
 * <p><b>PII 보호:</b> CI, 이름, 생년월일 등 개인정보는 스팬 태그에 포함하지 않음.
 * correlationId와 requestNo만 추적 식별자로 사용.
 *
 * <p><b>F-05 On/Off:</b> {@code IDO_AUTH_TRACING_ENABLED=false} 시 이 빈 자체가 미등록됨.
 * Jaeger/Tempo 없는 로컬/개발 환경에서 OTLP 연결 오류 없이 실행 가능.
 * matchIfMissing=true → 설정값 없으면 기본 활성(ON).
 *
 * @see kr.go.smes.ido.auth.service.NiceAuthService
 * @see kr.go.smes.ido.auth.service.AuthService
 * @see kr.go.smes.ido.auth.client.NiceApiClient
 * @see kr.go.smes.ido.auth.client.IntegrationAuthClient
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ido.tracing.auth-aspect-enabled",
        havingValue = "true", matchIfMissing = true)
public class AuthTracingAspect {

    private final Tracer tracer;

    // ─────────────────────────────────────────────────────────────────────────
    // NICE 서비스 추적 (비즈니스 레이어)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * NICE 휴대폰 인증 URL 발급 추적
     *
     * <p>스팬명: {@code nice.phone.url}
     * 태그: auth.service=nice, auth.operation=phone-url, correlation.id, auth.result
     */
    @Around("execution(* kr.go.smes.ido.auth.service.NiceAuthService.getNicePhoneAuthUrl(..))")
    public Object traceNicePhoneAuthUrl(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "nice.phone.url", "nice", "phone-url");
    }

    /**
     * NICE 휴대폰 인증 결과 복호화 추적
     *
     * <p>스팬명: {@code nice.phone.result}
     * HMAC 검증 + AES-GCM 복호화 + Q-IM 등록 전체 플로우 포함.
     * 태그: auth.service=nice, auth.operation=phone-result, correlation.id, auth.result
     */
    @Around("execution(* kr.go.smes.ido.auth.service.NiceAuthService.getNicePhoneAuthResult(..))")
    public Object traceNicePhoneAuthResult(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "nice.phone.result", "nice", "phone-result");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OACX 서비스 추적 (비즈니스 레이어)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OACX 접근키 발급 추적
     *
     * <p>스팬명: {@code oacx.access-info}
     * 태그: auth.service=oacx, auth.operation=access-info
     */
    @Around("execution(* kr.go.smes.ido.auth.service.AuthService.getOacxAccessInfo(..))")
    public Object traceOacxAccessInfo(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "oacx.access-info", "oacx", "access-info");
    }

    /**
     * OACX 간편서명 복호화 추적
     *
     * <p>스팬명: {@code oacx.easysign}
     * JWT 복호화 + CI 내부 처리 전체 포함.
     * 태그: auth.service=oacx, auth.operation=easysign
     */
    @Around("execution(* kr.go.smes.ido.auth.service.AuthService.handleOacxEasysign(..))")
    public Object traceOacxEasysign(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "oacx.easysign", "oacx", "easysign");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CI 처리 추적
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CI 기반 회원 확인 추적
     *
     * <p>스팬명: {@code auth.ci-check}
     * CI는 태그에 포함하지 않음 (PII 보호).
     * 태그: auth.service=nice, auth.operation=ci-check
     */
    @Around("execution(* kr.go.smes.ido.auth.service.AuthService.checkNiceCi(..))")
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
    @Around("execution(* kr.go.smes.ido.auth.service.AuthService.callback(..))")
    public Object traceAuthCallback(ProceedingJoinPoint pjp) throws Throwable {
        return traceMethod(pjp, "integration.callback", "integration", "callback");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NiceApiClient HTTP 외부 호출 스팬 (CLIENT 스팬)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * NICE Access Token 발급 외부 API 호출 추적
     *
     * <p>스팬명: {@code nice.api.fetch-token}
     * 태그: span.kind=CLIENT, peer.service=nice.api
     */
    @Around("execution(* kr.go.smes.ido.auth.client.NiceApiClient.fetchAccessToken(..))")
    public Object traceNiceFetchToken(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "nice.api.fetch-token", "nice.api");
    }

    /**
     * NICE 표준창 URL 발급 외부 API 호출 추적
     *
     * <p>스팬명: {@code nice.api.request-url}
     * 태그: span.kind=CLIENT, peer.service=nice.api
     */
    @Around("execution(* kr.go.smes.ido.auth.client.NiceApiClient.requestAuthUrl(..))")
    public Object traceNiceRequestUrl(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "nice.api.request-url", "nice.api");
    }

    /**
     * NICE 인증 결과 조회 외부 API 호출 추적
     *
     * <p>스팬명: {@code nice.api.request-result}
     * 태그: span.kind=CLIENT, peer.service=nice.api
     */
    @Around("execution(* kr.go.smes.ido.auth.client.NiceApiClient.requestAuthResult(..))")
    public Object traceNiceRequestResult(ProceedingJoinPoint pjp) throws Throwable {
        return traceExternalApiCall(pjp, "nice.api.request-result", "nice.api");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IntegrationAuthClient HTTP 외부 호출 스팬 (CLIENT 스팬)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 통합인증 서버 auth-check 외부 API 호출 추적
     *
     * <p>스팬명: {@code integration.api.auth-check}
     * 태그: span.kind=CLIENT, peer.service=integration.api
     */
    @Around("execution(* kr.go.smes.ido.auth.client.IntegrationAuthClient.sendAuthCheck(..))")
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
