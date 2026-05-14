# F-05: OTel 분산 추적 AOP

> **환경변수**: `IDO_AUTH_TRACING_ENABLED`  
> **기본값**: `true`  
> **Spring 프로퍼티**: `ido.tracing.auth-aspect-enabled`  
> **소스**: `ido/src/main/java/kr/go/smes/ido/auth/AuthTracingAspect.java`

---

## 1. 이 기능은 무엇인가?

**OpenTelemetry(OTel) 분산 추적**을 Auth 레이어에 AOP 방식으로 삽입합니다.  
`AuthTracingAspect` 빈이 등록되면, `@AuthService`가 붙은 메서드 실행마다 자동으로 Span이 생성되어 Jaeger/Tempo 등 추적 백엔드로 전송됩니다.

```
HTTP 요청 수신 (W3C TraceContext 전파)
  │
  ▼
AuthTracingAspect (AOP Around)
  │
  ├─ Span 시작: "auth.verify" / "auth.issue-token" 등
  ├─ 태그 설정: agencyCode, userId, endpoint
  │
  ▼
실제 Auth 서비스 메서드 실행
  │
  └─ Span 종료 (성공/실패 기록)
        │
        └─→ Jaeger / Grafana Tempo 전송
```

---

## 2. 동작 원리 — 빈 조건부 등록

F-05는 AOP Aspect 자체의 **빈 등록 여부**를 제어합니다.  
`false`로 설정 시 `AuthTracingAspect` 빈이 Spring 컨텍스트에 등록되지 않으므로, 코드 경로에 전혀 진입하지 않습니다.

```java
// AuthTracingAspect.java
@Aspect
@Component
@ConditionalOnFeatureFlag("authTracing")  // F-05=false → 빈 자체 미등록
@RequiredArgsConstructor
public class AuthTracingAspect {

    private final FeatureFlags featureFlags;
    private final Tracer tracer;

    @Around("@annotation(kr.go.smes.ido.auth.TraceAuth)")
    public Object traceAuth(ProceedingJoinPoint pjp) throws Throwable {
        Span span = tracer.spanBuilder(pjp.getSignature().getName()).startSpan();
        try (Scope scope = span.makeCurrent()) {
            Object result = pjp.proceed();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
```

---

## 3. Span 생성 대상 메서드

| Span 이름 | 대상 메서드 | 태그 |
|----------|-----------|------|
| `auth.verify-token` | `AuthService.verifyToken()` | `userId`, `agencyCode` |
| `auth.issue-token` | `AuthService.issueHandoffToken()` | `userId`, `tokenType` |
| `auth.validate-agency-key` | `AgencyKeyValidator.validate()` | `agencyCode`, `keyType` |
| `auth.nice-verify` | `NiceVerificationService.verify()` | `userId`, `verifyType` |

---

## 4. false 설정 가능한 경우

**OTel Collector 미구성 환경** (로컬 개발, 일부 테스트 환경):
```bash
IDO_AUTH_TRACING_ENABLED=false  # OTel Collector 없는 환경
```

**성능 측정 목적** — AOP 오버헤드 측정 시 일시적으로 false:
```bash
# 벤치마크: tracing=false 기준값 측정
IDO_AUTH_TRACING_ENABLED=false

# 벤치마크: tracing=true 오버헤드 측정
IDO_AUTH_TRACING_ENABLED=true
```

---

## 5. OTel 설정

```yaml
# application.yml
management:
  tracing:
    sampling:
      probability: 1.0   # 운영: 0.1 (10% 샘플링), 개발: 1.0 (전체)

spring:
  application:
    name: ido-gateway

# OTel Collector 엔드포인트
OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
OTEL_SERVICE_NAME: ido-gateway
```

---

## 6. 트레이스 조회 예시

```bash
# Jaeger UI에서 서비스 조회
curl "http://jaeger:16686/api/traces?service=ido-gateway&operation=auth.verify-token&limit=20"

# Grafana Tempo 조회 (TraceQL)
{ .service.name = "ido-gateway" && .span.name = "auth.verify-token" && duration > 200ms }
```

---

## 7. 연관 기능

| 기능 | 관계 |
|------|------|
| [F-04 감사 DB](F-04-audit-db.md) | 감사 로그에 `trace_id` 컬럼으로 연결 |
| [F-26 HMAC 서명](F-26-hmac-sig.md) | HMAC 검증 실패도 Span에 기록 |

---

## 연관 문서
- [FeatureFlags.java](../../ido/src/main/java/kr/go/smes/ido/config/FeatureFlags.java)
- [OTel Java Agent 설정](https://opentelemetry.io/docs/instrumentation/java/automatic/)
