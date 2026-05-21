# OnePass Agent — JEUS 버전별 SSO 심층 기술 노트

> **문서 번호**: AGENT-JEUS-001  
> **문서 버전**: v1.0.0  
> **작성일**: 2026-05-17  
> **분류**: 내부 기술 문서 (Internal Technical Document)  
> **대상 독자**: OnePass 플랫폼 아키텍트, 시니어 개발자  

---

## 1. 왜 JEUS인가 — 한국 공공기관 WAS 현황

### 1.1 공공기관 WAS 점유율

한국 공공기관 WAS 환경을 분석하면:

| WAS | 점유율 (추정) | 비고 |
|-----|-------------|------|
| **JEUS** | ~65% | 국내 공공기관 표준 WAS |
| Tomcat (단독/임베디드) | ~20% | Spring Boot 마이그레이션 증가 |
| WebLogic | ~8% | 중앙행정기관 일부 |
| JBoss/WildFly | ~5% | 지자체 일부 |
| 기타 | ~2% | IBM WebSphere 등 |

**JEUS가 주류인 이유**:
- 행정자치부 표준 WAS 인증 취득 (ISO, TTA 인증)
- 한국형 클러스터링/HA 요구사항 충족
- JEUS 자체가 국내 소프트웨어 (TmaxSoft) → 국산 SW 우대 정책

### 1.2 버전별 실제 운영 현황 (2026년 기준 추정)

| JEUS 버전 | 도입 연도 | 아직 운영 중 (추정) | 비고 |
|-----------|---------|-----------------|------|
| JEUS 4 | 2002 | 소수 기관 (5% 미만) | **JDK 1.5, 완전 레거시** |
| JEUS 5 | 2005 | 소수 기관 (5% 미만) | JDK 1.5 |
| JEUS 6 | 2008 | ~10% | JDK 1.6~1.7 |
| JEUS 7 | 2012 | ~25% | JDK 1.7~1.8 |
| JEUS 8 | 2015 | ~30% | JDK 1.8, 주류 |
| JEUS 8.5 | 2018 | ~20% | JDK 11 지원 |
| JEUS 9/21 | 2021+ | ~10% | Jakarta EE 9+ |

> **핵심**: JEUS 4/5는 소수지만 여전히 운영 중이며,  
> 이 환경에서 JDK 1.5 제약으로 인한 SSO 적용 어려움이 존재함.

---

## 2. JEUS 버전별 내부 아키텍처 변천사

### 2.1 JEUS 4/5 (2002~2007): 단일 서버 아키텍처

```
JEUS 4/5 구조:
┌─────────────────────────────────────────┐
│  JeusMain (메인 서버 프로세스)            │
│    └── com.tmax.jeus.server.JeusMain    │
│                                         │
│  HTTP 처리 경로:                          │
│  JeusMain                               │
│    └── HttpServletWrapper#service()     │ ← 위빙 포인트
│            └── 서블릿 invoke             │
└─────────────────────────────────────────┘

패키지: com.tmax.jeus.*  (Tmax 시절 네임스페이스)
Servlet: 2.3/2.4  (Filter는 있으나 역할 제한적)
클러스터: 별도 jeus_cl (클러스터 데몬)
```

**위빙 포인트 선택 이유**: `HttpServletWrapper#service()`  
- Servlet 2.3/2.4에서 Filter Chain은 옵션이므로 서블릿 래퍼 레벨이 더 안정적
- `javax.servlet.Filter#doFilter()`도 있지만 JEUS 4에서는 항상 통하는 경로가 아님

---

### 2.2 JEUS 6 (2008~2012): 멀티 엔진 아키텍처 도입

```
JEUS 6 구조:
┌─────────────────────────────────────────────────┐
│  DAS (Domain Admin Server)                      │
│    └── com.tmaxsoft.jeus.domain.*  ← 신 패키지   │
│                                                 │
│  Managed Server (멀티 인스턴스)                   │
│    └── WebEngine                                │
│         └── JeusServletHandler#service()        │ ← 위빙 포인트
│               └── Filter Chain → 서블릿         │
└─────────────────────────────────────────────────┘

패키지: com.tmaxsoft.jeus.* (TmaxSoft 분사 후 신 패키지)
Servlet: 2.5 (Filter Chain 강화)
```

**중요 변화**: `com.tmax.jeus.*` → `com.tmaxsoft.jeus.*`  
JEUS 6에서 TmaxSoft가 Tmax에서 분사하면서 패키지가 완전히 변경됨.  
이로 인해 JEUS 4/5와 JEUS 6의 위빙 전략이 별도로 필요함.

---

### 2.3 JEUS 7 (2012~2016): Servlet 3.0 표준 준수

```
JEUS 7 구조:
┌─────────────────────────────────────────────────────┐
│  WebEngine (Servlet 3.0)                            │
│    └── javax.servlet.Filter#doFilter()              │ ← 위빙 포인트
│          └── FilterChain                            │
│               └── 서블릿                            │
│                                                     │
│  신규: Async Servlet (Servlet 3.0)                  │
│  신규: @WebServlet, @WebFilter 어노테이션 지원        │
└─────────────────────────────────────────────────────┘
```

**위빙 포인트 변경 이유**:  
Servlet 3.0 표준이 강화되면서 `javax.servlet.Filter`가 모든 요청의 입구가 됨.  
JEUS 7부터는 Filter를 위빙하는 것이 가장 안정적인 방법.

---

### 2.4 JEUS 8/8.5 (2015~2021): Java EE 7/8 완전 지원

```
JEUS 8.5 구조:
┌─────────────────────────────────────────────────────┐
│  WebEngine (Servlet 4.0, HTTP/2 지원)               │
│    └── javax.servlet.Filter#doFilter()              │ ← 위빙 포인트
│         (HTTP/2 Push Promise 포함)                  │
│                                                     │
│  신규: HTTP/2 (JeusHttp2Handler)                    │
│  신규: WebSocket (JeusWebSocketHandler)              │
│  신규: JAX-RS 2.1 통합                              │
└─────────────────────────────────────────────────────┘
```

---

### 2.5 JEUS 9/21 (2021+): Jakarta EE 9+ 마이그레이션

```
JEUS 9 구조:
┌─────────────────────────────────────────────────────┐
│  WebEngine (Servlet 5.0, Jakarta EE 9)              │
│    └── jakarta.servlet.Filter#doFilter()            │ ← 위빙 포인트
│         (javax 완전 제거)                            │
│                                                     │
│  변경: javax.servlet.* → jakarta.servlet.*          │
│  변경: javax.persistence.* → jakarta.persistence.*  │
│  변경: javax.inject.* → jakarta.inject.*            │
└─────────────────────────────────────────────────────┘
```

**중요**: JEUS 9에서는 `javax.servlet.*` 패키지가 완전히 제거됨.  
Agent는 `jakarta.servlet.Filter`를 위빙해야 하므로 이중 위빙 전략 필요.

---

## 3. JDK 1.5 SSO 구현 — 심층 분석

### 3.1 JSR-163: Java SE 5.0 Instrumentation API

JSR-163은 JDK 1.5에서 도입된 `java.lang.instrument` 패키지로,  
JVM 레벨에서 클래스 파일을 조작할 수 있는 표준 API이다.

```
JDK 1.5에서 가능한 것:
  java.lang.instrument.Instrumentation      → 클래스 변환기 등록
  java.lang.instrument.ClassFileTransformer → 클래스 로드 시 바이트코드 변환
  Premain-Class MANIFEST 속성               → -javaagent: 정적 어태치

JDK 1.6에서 추가된 것:
  Instrumentation.retransformClasses()      → 이미 로드된 클래스 재변환
  VirtualMachine.attach()                  → 동적 어태치 (Attach API)
  agentmain()                              → 동적 주입 진입점
```

### 3.2 JDK 1.5에서 Agent가 동작하는 메커니즘

```
JVM 기동 순서 (JDK 1.5 + -javaagent:):

1. JVM 초기화 (Bootstrap ClassLoader, System ClassLoader)
   │
2. -javaagent: 옵션 파싱
   Agent JAR의 META-INF/MANIFEST.MF에서 Premain-Class 읽기
   │
3. Agent JAR 로드 (System ClassLoader에 추가)
   │
4. premain(String agentArgs, Instrumentation inst) 호출
   → JavassistWeavingEngine.install() → addTransformer() 등록
   │
5. main() 클래스 로드 시작 (WAS 메인 진입점)
   │
6. 클래스 로드 이벤트 발생 (예: com.tmax.jeus.web.servlet.HttpServletWrapper)
   → ClassFileTransformer.transform() 호출
   → Javassist로 바이트코드 수정 → 반환
   │
7. JVM이 수정된 바이트코드로 HttpServletWrapper 클래스 정의
   │
8. 이후 모든 HTTP 요청 → 수정된 service() 실행 → OnePass 검증
```

### 3.3 JDK 1.5에서 불가능한 것과 대안

| 필요 기능 | JDK 1.5 제약 | 대안 |
|---------|------------|------|
| 이미 로드된 클래스 재변환 | `retransformClasses()` JDK 1.6+ | premain 초기화 후 클래스 로드 순서 보장 |
| 동적 어태치 | Attach API JDK 1.6+ | `-javaagent:` 정적 어태치만 사용 |
| byte-buddy 사용 | byte-buddy JDK 8 필요 | Javassist 3.x (JDK 1.3+ 호환) |
| 람다, 제네릭 (위빙 코드) | 위빙된 코드는 JVM 1.5 실행 | Javassist 소스 문자열에 JDK 1.4 문법만 |
| Java NIO / CompletableFuture | 없거나 제한적 | `HttpURLConnection` (JDK 1.1+) |

### 3.4 Javassist 소스 코드 문자열 제약

Javassist `insertBefore(String src)`는 소스 코드를 문자열로 받아 컴파일한다.  
이 컴파일은 **Javassist 내장 Java 컴파일러**로 처리되며, **JDK 1.4 수준 문법**만 지원한다.

```java
// ✅ JDK 1.4 호환 문법 (사용 가능)
StringBuffer sb = new StringBuffer();
sb.append("hello").append(" world");
Integer intVal = new Integer(42);        // autoboxing: JDK 1.5
Object obj = (Object) someRef;
if (x instanceof String) { ... }
try { ... } catch (Exception e) { ... }

// ❌ JDK 5+ 문법 (Javassist 컴파일러 미지원)
for (String s : list) { }              // enhanced for: JDK 5
List<String> list = new ArrayList<>();  // 제네릭: JDK 5
String.format("%s %s", a, b);           // format: JDK 5 (메서드는 가능)

// ❌ JDK 8+ 문법
list.stream().filter(s -> s.length() > 0);  // lambda/stream
Optional<String> opt = Optional.of(s);      // Optional
```

---

## 4. JEUS 버전별 위빙 전략 상세

### 4.1 JeusLegacyWeavingStrategy (JEUS 4/5)

#### 위빙 포인트 우선순위

```
1순위: com.tmax.jeus.web.servlet.HttpServletWrapper#service
       → JEUS 4/5 HTTP 서블릿 처리의 핵심 클래스
       
2순위: jeus.servlet.JeusServletEngine#service  
       → 모든 JEUS 버전에 존재하는 공통 내부 엔진
       → 1순위 미발견 시 폴백
       
3순위: 위빙 포기 + WARN 로그
       → WAS는 정상 기동, SSO 미동작
```

#### service() 메서드 시그니처

```java
// JEUS 4/5 HttpServletWrapper.service()
// javax.servlet.Servlet#service(ServletRequest, ServletResponse) 구현
public void service(javax.servlet.ServletRequest request,
                    javax.servlet.ServletResponse response)
    throws javax.servlet.ServletException, java.io.IOException;

// $1 = request (ServletRequest)
// $2 = response (ServletResponse)
// HttpServletRequest/Response로 캐스팅 필요
```

#### 설정 공유 메커니즘 (System Property Bridge)

```
OnePassAgentMain (JDK 8 클래스)
    │  System.setProperty("onepass.agent.endpoint", "https://...")
    │  System.setProperty("onepass.agent.api-key", "key123")
    │
    ▼ (JVM 전역 공유, ClassLoader 경계 무관)
    
JEUS 4/5 ClassLoader 환경에서 실행되는 Javassist 삽입 코드:
    String endpoint = System.getProperty("onepass.agent.endpoint");
    String apiKey   = System.getProperty("onepass.agent.api-key");
```

이 방식이 필요한 이유:
- JEUS 4/5의 웹 ClassLoader는 Agent JAR의 클래스를 볼 수 없음
- Agent 클래스를 Bootstrap Classpath에 추가하면 JDK 내장 클래스 오염 위험
- `System.getProperty()`는 JVM 프로세스 전역으로 ClassLoader와 무관하게 공유됨

---

### 4.2 Jeus6WeavingStrategy (JEUS 6)

#### 설계 결정: Javassist 통일

JEUS 6은 JDK 1.5~1.7을 지원한다. JDK 1.6/1.7 환경이라면 byte-buddy를 쓸 수도 있지만,  
**Javassist로 통일**하는 이유:

1. JEUS 6 운영 기관의 실제 JDK 버전이 1.5인 경우가 많음 (업그레이드 미진행)
2. JDK 버전을 정확히 파악하기 어려운 경우 Javassist가 안전
3. 위빙 코드의 일관성 유지 (JEUS 6 운영팀에게 하나의 메커니즘만 설명)

#### 위빙 포인트

```
com.tmaxsoft.jeus.web.servlet.JeusServletHandler#service
  → JEUS 6 신 패키지의 서블릿 핸들러
  → Servlet 2.5 표준 진입점

대체: jeus.servlet.JeusServletEngine#service
  → JEUS 4~6 공통 엔진 클래스
```

---

### 4.3 Jeus7PlusWeavingStrategy (JEUS 7/8) — JDK 자동 분기

```
JDK 버전 감지 → 엔진 선택

JDK major 파싱 ("1.8.0_292" → 8, "1.7.0_261" → 7):
  - "1.X.Y..." 형식: X를 major로 사용
  - "X.Y.Z..." 형식 (JDK 9+): X를 major로 사용

결정:
  major >= 8: byte-buddy 위빙 (javax.servlet.Filter#doFilter)
  major <  8: Javassist 위빙 (JeusServletHandler#service 폴백)
```

**JDK 7에서 Javassist 폴백이 필요한 실제 사례**:
```
공공기관 X: JEUS 7 (Fix3) + JDK 1.7.0_201 (오라클 지원 종료 후 업그레이드 미진행)
→ byte-buddy: UnsupportedClassVersionError 발생
→ Javassist 폴백: 정상 동작
```

---

### 4.4 Jeus8_5PlusWeavingStrategy (JEUS 8.5/9/21)

#### Jakarta EE 이중 위빙의 필요성

JEUS 9는 Jakarta EE 9를 채택하면서 `javax.*` 패키지를 완전 제거했다.  
단, 마이그레이션 기간 중 일부 기관이 JEUS 9 위에 구 코드를 실행하는 경우가 있어  
`javax.servlet.Filter`와 `jakarta.servlet.Filter` 양쪽을 위빙한다.

```java
// 이중 위빙 구현 (Jeus8_5PlusWeavingStrategy 핵심)
AgentBuilder builder = new AgentBuilder.Default()
    .with(new WeavingListener(log));

// javax 위빙 (JEUS 8.5 + JEUS 9 마이그레이션 호환)
try {
    builder = builder
        .type(isSubTypeOf(named("javax.servlet.Filter")))
        .transform((b, t, cl, m, pd) ->
            b.method(named("doFilter")).intercept(Advice.to(JavaxFilterAdvice.class)));
    log.println("[Jeus8_5Plus] javax.servlet.Filter 위빙 등록");
} catch (NoClassDefFoundError e) {
    // javax.servlet이 없는 환경 (순수 Jakarta EE): 무시
    log.println("[Jeus8_5Plus] javax.servlet 없음 (Jakarta EE 전용 환경) — 건너뜀");
}

// jakarta 위빙 (JEUS 9+ 필수)
try {
    builder = builder
        .type(isSubTypeOf(named("jakarta.servlet.Filter")))
        .transform((b, t, cl, m, pd) ->
            b.method(named("doFilter")).intercept(Advice.to(JakartaFilterAdvice.class)));
    log.println("[Jeus8_5Plus] jakarta.servlet.Filter 위빙 등록");
} catch (NoClassDefFoundError e) {
    // jakarta.servlet이 없는 환경: 무시
    log.println("[Jeus8_5Plus] jakarta.servlet 없음 — 건너뜀");
}

builder.installOn(inst);
```

---

## 5. 위빙 포인트 선택 기준

### 5.1 좋은 위빙 포인트의 조건

```
✅ 조건 1: 모든 HTTP 요청이 반드시 통과하는 지점
✅ 조건 2: 요청/응답 객체(HttpServletRequest/Response)에 접근 가능
✅ 조건 3: 메서드 반환 전에 조기 반환(sendError + return)이 가능
✅ 조건 4: 특정 JEUS 버전 이후 변경되지 않는 안정적인 클래스
✅ 조건 5: ClassLoader가 접근 가능한 경로
```

### 5.2 JEUS 버전별 최적 위빙 포인트 결정 이유

| JEUS | 포인트 | 이유 |
|------|-------|------|
| 4/5 | `HttpServletWrapper#service` | Servlet 2.3/2.4: Filter 필수 아님 → 서블릿 래퍼가 더 안정적 |
| 6 | `JeusServletHandler#service` | 신 패키지의 표준 서블릿 핸들러 |
| 7+ | `javax.servlet.Filter#doFilter` | Servlet 3.0: Filter가 표준 진입점이 됨 |
| 9+ | `jakarta.servlet.Filter#doFilter` | jakarta 네임스페이스 |

---

## 6. 클래스로더 계층 분석

### 6.1 JEUS 4/5 ClassLoader 계층

```
Bootstrap ClassLoader (JDK rt.jar)
    └── Extension ClassLoader
            └── System ClassLoader
                    ├── Agent ClassLoader (onepass-agent-all.jar)
                    │       ← OnePassAgentMain, JavassistWeavingEngine 등
                    │
                    └── JEUS Server ClassLoader
                            ├── JEUS 코어 (jeus.jar, com.tmax.jeus.*)
                            └── App ClassLoader (웹 앱 클래스)
```

**문제**: Agent ClassLoader와 JEUS Server ClassLoader는 독립적.  
→ Agent가 `com.tmax.jeus.web.servlet.HttpServletWrapper`를 볼 수 없음  
→ Javassist ClassPool에 JEUS ClassLoader를 추가해야 해결

```java
// 해결: ClassFileTransformer.transform()에서 ClassLoader 활용
ClassPool pool = ClassPool.getDefault();
pool.appendClassPath(new LoaderClassPath(classLoaderOfTargetClass));
// classLoaderOfTargetClass = transform() 파라미터로 전달된 loader
```

### 6.2 JEUS 9+ ClassLoader 계층 (OSGi 유사 구조)

```
Bootstrap ClassLoader
    └── System ClassLoader
            └── Jeus Server ClassLoader
                    ├── Jakarta EE API (jakarta.servlet.*)
                    ├── JEUS 코어 (com.tmaxsoft.jeus.*)
                    └── WebApp ClassLoader
                            └── 기관 웹 앱 클래스
```

byte-buddy는 `AgentBuilder`가 ClassLoader 계층을 자동으로 처리하므로  
JEUS 9+에서는 ClassPool 수동 관리가 불필요하다.

---

## 7. 성능 영향 분석

### 7.1 Javassist vs byte-buddy 성능 비교

| 항목 | Javassist | byte-buddy |
|------|---------|-----------|
| **위빙 초기화 시간** | ~1~5ms per class | ~5~20ms per class (AgentBuilder 빌드 포함) |
| **런타임 오버헤드** | 소스 코드 해석 → 약간 느림 | ASM 직접 조작 → 매우 빠름 |
| **메모리 사용** | ClassPool ~1~5MB | byte-buddy 캐시 ~10MB |
| **JDK 호환성** | JDK 1.3+ | JDK 8+ |

**결론**: JEUS 7/8 JDK 8 환경에서는 byte-buddy가 성능이 더 좋지만,  
JDK 1.5/1.7 환경에서는 Javassist만이 선택지다.

### 7.2 HTTP 요청당 추가 레이턴시

```
OnePass 검증 API 호출 레이턴시 (내부망 기준):

연결 설정(TCP):     ~0.5~1ms
TLS 핸드셰이크:    ~2~5ms (HTTPS)
OnePass 서버 처리:  ~0.5~2ms
네트워크 왕복:      ~0.5~2ms
총 추가 레이턴시:   ~3~10ms per request
```

**최적화 방향 (미구현)**:
1. 토큰 검증 결과 캐시 (TTL 60초): 반복 요청의 경우 0ms
2. Connection Pool (HttpURLConnection 재사용): TCP 연결 비용 절감
3. 비동기 검증 + 응답 스트리밍: 처리량 향상

---

## 8. 보안 고려사항

### 8.1 System Property 브리지 보안 위험

JEUS 4/5에서 `System.setProperty()`로 API Key를 공유할 때의 위험:

```
위험 1: System.getProperty() 읽기
        → JVM 내 모든 코드가 API Key를 읽을 수 있음
        → JEUS 4/5 환경에서는 이를 방지하기 어려움
        → 완화책: JVM 기동 후 API Key를 메모리에서 지우는 방법 (미구현)

위험 2: JVM 인수 목록 노출
        → ps aux 등으로 -D 옵션 확인 가능
        → 완화책: 설정 파일 사용 (=config=/opt/.../agent.properties)

위험 3: 힙 덤프를 통한 API Key 추출
        → HeapDump에 System Properties 포함됨
        → 완화책: JVM 메모리 덤프 접근 제한 (운영 정책)
```

### 8.2 HMAC 서명 JDK 1.4+ 호환

```java
// javax.crypto.Mac (JDK 1.4+) — HMAC-SHA256 구현
// JEUS 4/5에서도 사용 가능

Mac mac = Mac.getInstance("HmacSHA256");
SecretKeySpec keySpec = new SecretKeySpec(
    secret.getBytes("UTF-8"), "HmacSHA256");
mac.init(keySpec);
byte[] hmacBytes = mac.doFinal(message.getBytes("UTF-8"));

// Base64 인코딩 (JDK 1.4에는 java.util.Base64 없음)
// Javassist 소스 코드에서는 별도 구현 필요:
// DatatypeConverter.printHexBinary() (JAXB, JDK 1.6+이라 사용 불가)
// → 직접 hex 변환 구현
StringBuffer hex = new StringBuffer();
for (int i = 0; i < hmacBytes.length; i++) {
    String h = Integer.toHexString(0xFF & hmacBytes[i]);
    if (h.length() == 1) hex.append('0');
    hex.append(h);
}
```

---

## 9. 알려진 엣지 케이스

### 9.1 JEUS 클러스터 환경

JEUS 클러스터에서 각 노드에 Agent가 독립적으로 설치됨.  
노드 간 세션 공유는 OnePass 서버에서 처리 (Agent 무관).

```
JEUS Cluster:
  ├── Node 1 (Agent 설치됨) → OnePass 서버
  ├── Node 2 (Agent 설치됨) → OnePass 서버
  └── Node 3 (Agent 설치됨) → OnePass 서버
  
Load Balancer → 어느 노드로 오든 동일하게 토큰 검증
```

**주의**: 모든 노드에 동일한 `onepass-agent.properties` 배포 필요.

### 9.2 JEUS WebAdmin 콘솔 (관리 포트)

JEUS WebAdmin 콘솔 요청도 Agent 위빙의 영향을 받을 수 있음.  
관리 콘솔 경로를 SSO 예외 처리해야 함.

```
현재 미구현 → 임시 해결:
  -Donepass.agent.enabled=false 후 관리 콘솔 작업
  또는
  관리 콘솔 전용 서버에 Agent 미설치
```

### 9.3 JEUS 4/5 Hot Deploy

JEUS 4/5에서 웹 앱 Hot Deploy 시, 새로 로드된 클래스에 Transformer가 재적용됨.  
JDK 1.5에서 `retransformClasses()`는 불가하지만, 새 클래스 로드 이벤트에는 Transformer가 정상 동작.

```
Hot Deploy:
  웹 앱 언배포 → 클래스 언로드
  웹 앱 배포   → 클래스 로드 (이벤트 발생)
  → ClassFileTransformer.transform() 호출 → 정상 위빙
```

---

## 10. 참고 자료

| 문서 | 설명 |
|------|------|
| JSR-163 | Java SE 5.0 Platform Monitoring and Management |
| Javassist API | https://www.javassist.org/tutorial/tutorial.html |
| byte-buddy 공식 문서 | https://bytebuddy.net/tutorial |
| JEUS 공식 문서 | https://technet.tmaxsoft.com/jeus |
| Jakarta EE 마이그레이션 가이드 | https://jakarta.ee/blogs/migration/ |

---

## 변경 이력

| 버전 | 날짜 | 내용 |
|------|------|------|
| v1.0.0 | 2026-05-17 | 최초 작성 |
