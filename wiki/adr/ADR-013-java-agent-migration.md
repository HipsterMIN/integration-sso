# ADR-013: Java Agent 기반 전환 기술 제언

> **상태**: ✅ **방법 B 적용 완료** (Sprint 18, 2026-05-17)  
> **작성일**: 2026-05-17  
> **작성자**: OnePass 개발팀  
> **결정 필요일**: Sprint 19 (2026-06 예정) — OpenTelemetry Agent 도입 여부

---

## 배경 및 현황

### 현재 상황

테스트 실행 시 다음 경고가 지속 발생합니다:

```
Mockito is currently self-attaching to enable the inline-mock-maker.
This will no longer work in future releases of the JDK.
Please add Mockito as an agent to your build as described in Mockito's documentation.

WARNING: A Java agent has been loaded dynamically
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

이는 JDK 21+에서 Java Agent의 **동적 로딩(Dynamic Loading)**이 기본적으로 차단될 예정임을 알리는 경고입니다.  
JDK 24부터 `--enable-dynamic-agent-loading` 없이는 완전히 차단됩니다.

### 영향 범위

```
현재 영향:
  - Mockito inline-mock-maker (테스트 시 final 클래스 mock)
  - ByteBuddy 동적 Agent 로딩

미래 영향 (JDK 24+):
  - 경고 → 오류로 전환 → 빌드 실패 가능
  - CI/CD 파이프라인 영향
```

---

## 제언 1: 즉시 조치 — Mockito Agent 명시적 설정

### 권장 조치 (Sprint 18)

#### 방법 A: build.gradle JVM 인수 추가 (빠른 적용)

```groovy
// 각 모듈의 build.gradle 또는 루트 build.gradle
test {
    jvmArgs(
        '-XX:+EnableDynamicAgentLoading',  // 현재 경고 억제 (임시)
        '-Djdk.instrument.traceUsage=false' // 추가 진단 메시지 억제
    )
}
```

#### 방법 B: -javaagent 명시적 설정 (권장 — 근본 해결)

```groovy
// build.gradle
configurations {
    mockitoAgent
}

dependencies {
    // 기존 mockito 의존성 유지
    testImplementation 'org.mockito:mockito-core:5.x.x'
    
    // Agent 전용 설정 (인라인 mock 필요 시)
    mockitoAgent("org.mockito:mockito-core:5.x.x") { transitive = false }
}

test {
    jvmArgs("-javaagent:${configurations.mockitoAgent.asPath}")
}
```

**효과**: Dynamic Loading 없이 Mockito inline-mock-maker가 정상 작동합니다.

---

## 제언 2: Java Agent 기반 관찰성 강화 (중장기)

### 배경

현재 OnePass 플랫폼의 관찰성(Observability) 구현:

```
현재:
  - Micrometer + Prometheus (메트릭)
  - SLF4J + Logback (로그)
  - 수동 @Timed, Counter (계측 포인트)

개선 가능 영역:
  - 분산 추적 (Distributed Tracing) 미완
  - 자동 계측 (Auto-Instrumentation) 미적용
  - APM 연동 없음
```

### OpenTelemetry Java Agent 도입 검토

**OpenTelemetry Java Agent**는 `-javaagent` 방식으로 주입되어  
코드 수정 없이 자동으로 분산 추적, 메트릭, 로그를 계측합니다.

```yaml
# K8s Deployment 설정 예시
spec:
  containers:
  - name: ido
    image: onepass-ido:latest
    env:
    - name: JAVA_TOOL_OPTIONS
      value: >-
        -javaagent:/otel/opentelemetry-javaagent.jar
        -Dotel.service.name=onepass-ido
        -Dotel.exporter.otlp.endpoint=http://otel-collector:4317
        -Dotel.resource.attributes=deployment.environment=production
```

**자동 계측 대상** (코드 변경 없음):
- Spring MVC 요청/응답 추적
- JDBC 쿼리 추적 (ido PostgreSQL, q-im MariaDB)
- Kafka producer/consumer 추적
- RestTemplate HTTP 호출 추적 (프로비저닝 기관 요청 포함)
- Redis 명령 추적 (ShedLock, FeSession)

**기대 효과**:
```
Before: correlationId 수동 전파 → 로그 검색으로 추적
After:  W3C Trace Context (traceparent 헤더) 자동 전파
        → Jaeger/Tempo UI에서 전체 요청 트레이스 시각화
        → 기관 → IdO → Q-IM → Kafka → 배치 → 기관 전체 흐름 추적
```

### 도입 시 고려사항

| 항목 | 내용 |
|------|------|
| **성능 오버헤드** | 약 2-5% CPU 증가 (허용 수준) |
| **메모리** | Agent 자체 약 50-100MB 추가 |
| **K8s Init Container** | Agent JAR을 `/otel/` 볼륨에 마운트 |
| **기존 Micrometer 충돌** | OTel ↔ Micrometer Bridge 필요 (otel-micrometer-bridge) |
| **로그 MDC 통합** | `otel.logs.exporter=otlp` + MDC 자동 전파 설정 |

---

## 제언 3: Java Agent vs Spring AOP — 선택 기준

현재 플랫폼에서 횡단관심사(Cross-Cutting Concerns) 처리 방식 비교:

### 현재 방식: Spring AOP

```java
// 현재 구현 (AOP 기반)
@Aspect
@Component
public class ProvisioningAuditAspect {
    @Around("execution(* kr.go.smes.ido.provision.*.*(..))")
    public Object auditProvisioning(ProceedingJoinPoint pjp) throws Throwable {
        // 감사 로그 자동 기록
    }
}
```

**장점**: Spring 컨텍스트 내 완전 통합, 의존성 주입 가능  
**단점**: Spring Bean만 적용, 바이트코드 수준 계측 불가

### Java Agent 방식 (제언)

**적합한 경우**:
1. **관찰성 전용** (OpenTelemetry): 코드 수정 없이 모든 계층 자동 추적
2. **보안 정책 주입** (Java Security Manager 대체): 정책 파일 기반 접근 제어
3. **동적 기능 토글** (Feature Flag Agent): 런타임 바이트코드 교체 (OpenFeature Agent)

**OnePass에 권장하는 Agent 활용 방향**:

```
단기 (Sprint 18~19):
  ✅ Mockito Agent 명시적 설정 (방법 B)
  ✅ OpenTelemetry Agent 파일럿 (Staging 환경)

중기 (Sprint 20~):
  🔶 OpenTelemetry 운영 적용
  🔶 Jaeger/Tempo 분산 추적 대시보드 구축

장기:
  🔲 OpenFeature Agent (기능 토글 Agent 기반 전환 검토)
  🔲 보안 정책 엔진 (정책 외부화 검토)
```

---

## 결정 사항 (요청)

스프린트 19 아키텍처 리뷰에서 다음을 결정해주시기 바랍니다:

1. **즉시**: Mockito Agent 명시적 설정 (방법 A 또는 B)  
   → 현재 경고 해결, JDK 24 대비

2. **중기**: OpenTelemetry Java Agent 도입 여부  
   → 분산 추적 필요성 + 성능 오버헤드 허용 여부 판단

3. **장기**: Java Agent 기반 기능 토글 전환 여부  
   → 현재 `@Value` + Feature Flag vs Agent 기반 동적 교체

---

## 참고 자료

- [JEP 451: Prepare to Disallow the Dynamic Loading of Agents](https://openjdk.org/jeps/451)
- [JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472)
- [Mockito Documentation: JVM Agent](https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3)
- [OpenTelemetry Java Agent](https://opentelemetry.io/docs/zero-code/java/agent/)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
