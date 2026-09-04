# OnePass Agency Java Agent — 내부 개발자 기술 레퍼런스

> **문서 번호**: AGENT-DEVREF-001  
> **문서 버전**: v1.0.0  
> **작성일**: 2026-05-17  
> **분류**: 내부 전용 (Internal Only)  
> **대상 독자**: OnePass 플랫폼 팀 개발자 (신규 입사자, 에이전트 모듈 기여자)  

---

## 목차

1. [개발 환경 설정](#1-개발-환경-설정)
2. [모듈 구조 및 빌드](#2-모듈-구조-및-빌드)
3. [핵심 클래스 코딩 가이드](#3-핵심-클래스-코딩-가이드)
4. [새 WAS 유형 추가 방법](#4-새-was-유형-추가-방법)
5. [새 JEUS 버전 지원 추가](#5-새-jeus-버전-지원-추가)
6. [Javassist 위빙 코드 작성 가이드](#6-javassist-위빙-코드-작성-가이드)
7. [byte-buddy Advice 작성 가이드](#7-byte-buddy-advice-작성-가이드)
8. [테스트 작성 가이드](#8-테스트-작성-가이드)
9. [fat-JAR 빌드 및 검증](#9-fat-jar-빌드-및-검증)
10. [코드 품질 기준](#10-코드-품질-기준)
11. [알려진 기술 부채](#11-알려진-기술-부채)
12. [릴리즈 절차](#12-릴리즈-절차)

---

## 1. 개발 환경 설정

### 1.1 최소 요구사항

```
JDK 17+ (빌드용)
Gradle 8.x (wrapper 사용: ./gradlew)
IntelliJ IDEA 2024+ 또는 VS Code + Extension Pack for Java
```

> **⚠️ 주의**: 빌드 JDK와 컴파일 타겟이 다릅니다.  
> **빌드**: JDK 17+로 실행  
> **컴파일 타겟**: `--release 8` (JDK 8 소스 호환)

### 1.2 초기 셋업

```bash
# 저장소 클론
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso

# 빌드 확인
./gradlew :idem-agent:build

# 테스트 실행
./gradlew :idem-agent:test

# fat-JAR 빌드
./gradlew :idem-agent:agentJar
ls -la idem-agent/build/libs/idem-agent-*-all.jar
```

### 1.3 IntelliJ 설정

```
File > Project Structure > Modules
  > onepass-agent
    > Sources > Language Level: 8
    > Dependencies: JDK 17 (SDK)

Run/Debug Configuration:
  VM options: -javaagent:idem-agent/build/libs/idem-agent-1.0.0-all.jar
              =config=idem-agent/src/test/resources/onepass-agent.properties
```

---

## 2. 모듈 구조 및 빌드

### 2.1 멀티 모듈 구조 내 위치

```
integration-sso/
├── build.gradle.kts           ← 루트 빌드 (Spring BOM 포함)
├── settings.gradle.kts        ← 모듈 등록
│
├── idem-agent/             ← ⭐ 이 모듈 (완전 독립)
│   ├── build.gradle.kts       ← 스프링 없음, JDK 8 타겟
│   └── src/
│       ├── main/java/kr/go/smes/agent/
│       └── test/java/kr/go/smes/agent/
│
├── onepass-qim/               ← Spring Boot 모듈 (무관)
├── onepass-ido/               ← Spring Boot 모듈 (무관)
└── ...
```

### 2.2 build.gradle.kts 핵심 이해

```kotlin
// ── 핵심 제약: Spring/Lombok 완전 배제 ──
configurations.all {
    exclude(group = "org.springframework.boot")
    exclude(group = "org.springframework")
    exclude(group = "org.projectlombok")
}

// ── JDK 8 소스 호환 컴파일 ──
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--release", "8"))
}

// ── fat-JAR: byte-buddy + Javassist 번들링 ──
val agentJar by tasks.registering(Jar::class) {
    // MANIFEST에 Premain-Class 등록 (이것이 없으면 Agent 동작 안 함)
    manifest {
        attributes(
            "Premain-Class" to "kr.go.smes.agent.core.OnePassAgentMain",
            "Agent-Class"   to "kr.go.smes.agent.core.OnePassAgentMain",
            "Can-Redefine-Classes"    to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
    // byte-buddy와 Javassist JAR 언팩 포함
    from({
        configurations["runtimeClasspath"]
            .filter { f -> f.name.contains("byte-buddy") || f.name.contains("javassist") }
            .map    { f -> zipTree(f) }
    })
}
```

### 2.3 주요 Gradle Task

```bash
# 컴파일만
./gradlew :idem-agent:compileJava

# 테스트 실행 (재실행 강제)
./gradlew :idem-agent:test --rerun-tasks

# fat-JAR 빌드
./gradlew :idem-agent:agentJar

# MANIFEST 확인
jar tf idem-agent/build/libs/idem-agent-1.0.0-all.jar | grep MANIFEST
jar xf idem-agent/build/libs/idem-agent-1.0.0-all.jar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF

# byte-buddy 포함 확인
jar tf idem-agent/build/libs/idem-agent-1.0.0-all.jar | grep "bytebuddy" | wc -l
# 수백 개 출력되어야 정상

# Javassist 포함 확인
jar tf idem-agent/build/libs/idem-agent-1.0.0-all.jar | grep "javassist" | wc -l
```

---

## 3. 핵심 클래스 코딩 가이드

### 3.1 코딩 규칙 (엄격 준수)

```java
// ✅ 허용: JDK 8 이하 문법
List<String> list = new ArrayList<String>();
for (String s : list) { ... }
final String val = (val != null) ? val : "default";

// ❌ 금지: JDK 9+ 문법
var list = new ArrayList<String>();    // var: JDK 10+
Map.of("k", "v");                      // 팩토리: JDK 9+
"""text block""";                       // text block: JDK 15+
if (obj instanceof String s) { }       // pattern: JDK 16+

// ❌ 금지: Spring / Lombok
@Component, @Service, @Autowired       // Spring
@Data, @Slf4j, @Builder                // Lombok
```

### 3.2 로그 출력 규칙

```java
// Agent는 SLF4J/Log4j 없음 — PrintStream(System.err) 사용
// 모든 public 메서드는 PrintStream log 파라미터 받기

public static WasType detect(PrintStream log) {
    logInfo(log, "[WasDetector] 감지 시작");
    // ...
}

// 내부 헬퍼 (null 안전)
private static void logInfo(PrintStream log, String msg) {
    if (log != null) log.println(msg);
}

private static void logWarn(PrintStream log, String msg) {
    if (log != null) log.println("[WARN] " + msg);
}
```

### 3.3 예외 처리 규칙

```java
// premain 단계에서는 모든 예외를 잡아야 함
// (Uncaught exception → JVM 종료 → WAS 기동 실패)

@Override
public void install(Instrumentation inst) {
    try {
        // 위빙 로직
    } catch (WeavingInstallException e) {
        // 이 예외는 호출자(OnePassAgentMain)에서 처리
        throw e;
    } catch (Throwable t) {
        // 그 외 모든 예외: 로그만 남기고 계속
        log.println("[WARN] 위빙 실패 (비치명적): " + t.getMessage());
        // throw 금지
    }
}
```

---

## 4. 새 WAS 유형 추가 방법

새로운 WAS(예: IBM WebSphere)를 지원하려면 아래 4단계를 따릅니다.

### Step 1: `WasType.java`에 enum 상수 추가

```java
// WasType.java
/** IBM WebSphere Application Server */
WEBSPHERE("WebSphere"),
```

### Step 2: `WasDetector.java`에 감지 로직 추가

```java
// WasDetector.java

// 클래스패스 탐색용 상수 추가
private static final String CLS_WEBSPHERE =
    "com.ibm.websphere.runtime.Application";

// detect() 메서드 내 탐색 추가
if (isClassPresent(CLS_WEBSPHERE)) {
    logInfo(log, "[WasDetector] WebSphere 클래스 감지");
    return WasType.WEBSPHERE;
}

// 시스템 프로퍼티 탐색 추가
if (System.getProperty("was.install.root") != null) {
    logInfo(log, "[WasDetector] WebSphere 시스템 프로퍼티 감지");
    return WasType.WEBSPHERE;
}

// parseOverride() 내 추가
case "WEBSPHERE": return WasType.WEBSPHERE;
```

### Step 3: 위빙 전략 클래스 생성

```java
// kr.go.smes.agent.weaving.WebSphereWeavingStrategy.java
package kr.go.smes.agent.weaving;

public final class WebSphereWeavingStrategy implements WeavingStrategy {

    private static final String STRATEGY_NAME = "WebSphereWeaving";
    private final AgentConfig config;
    private final PrintStream log;

    public WebSphereWeavingStrategy(AgentConfig config, PrintStream log) {
        this.config = config;
        this.log = log;
    }

    @Override
    public void install(Instrumentation inst) throws WeavingInstallException {
        // byte-buddy로 javax.servlet.Filter 위빙
        // (GenericFilterWeavingStrategy 참고)
    }

    @Override
    public String name() { return STRATEGY_NAME; }
}
```

### Step 4: `WeavingStrategyFactory.java`에 case 추가

```java
// WeavingStrategyFactory.java
case WEBSPHERE:
    strategy = new WebSphereWeavingStrategy(config, log);
    break;
```

### Step 5: 테스트 작성

```java
// WasDetectorTest.java에 추가
@Test
void detectWebSphere_byClasspath() {
    System.setProperty(OVERRIDE_PROP, "WEBSPHERE");
    assertEquals(WasType.WEBSPHERE, WasDetector.detect(log));
}

// WeavingStrategyFactoryTest.java에 추가
@Test
void createForWebSphere_returnsWebSphereStrategy() {
    WeavingStrategy strategy = WeavingStrategyFactory.create(
        WasType.WEBSPHERE, config, log);
    assertInstanceOf(WebSphereWeavingStrategy.class, strategy);
}
```

---

## 4-A. LegacyJavassistWeavingStrategy 패턴 가이드

> **대상 WAS**: JBoss AS 5/6, WebLogic 10.x/11g/12c 초기, WebSphere 7/8, Jetty 7/8, Resin  
> **공통점**: JDK 6~7 환경에서 byte-buddy 불가 → Javassist로 `javax.servlet.Filter#doFilter()` 위빙

### 4-A.1 패턴 개요

`LegacyJavassistWeavingStrategy`는 레거시 WAS(JDK 6~7) 공통 위빙 전략입니다.  
WAS 종류에 무관하게 `javax.servlet.Filter#doFilter()`를 단일 위빙 포인트로 사용합니다.

```java
// 사용 패턴 (WeavingStrategyFactory)
case JBOSS_LEGACY:
case WEBLOGIC_LEGACY:
case WEBSPHERE_LEGACY:
case JETTY_LEGACY:
case RESIN:
    strategy = new LegacyJavassistWeavingStrategy(wasType, config, log);
    break;
```

### 4-A.2 핵심 구현 구조

```java
public final class LegacyJavassistWeavingStrategy implements WeavingStrategy {

    private static final String FILTER_JAVAX = "javax.servlet.Filter";
    private static final String DO_FILTER    = "doFilter";

    @Override
    public void install(Instrumentation inst) {
        // 1. System Property Bridge (JDK 1.4 호환 방식으로 설정 공유)
        System.setProperty("onepass.agent.endpoint", config.endpoint());
        System.setProperty("onepass.agent.api-key",  config.apiKey());
        System.setProperty("onepass.agent.enabled",  String.valueOf(config.isEnabled()));
        System.setProperty("onepass.was.type.detected", wasType.name());

        // 2. Javassist 소스코드 문자열 생성 (JDK 1.4 호환 문법)
        String beforeCode = buildFilterBeforeCode();

        // 3. JavassistWeavingEngine 생성 및 설치
        JavassistWeavingEngine engine = new JavassistWeavingEngine(log);
        final String capturedBeforeCode = beforeCode;
        
        try {
            engine.install(inst,
                    new JavassistWeavingEngine.JavassistClassFileTransformer(log) {
                        @Override
                        public String targetClassName() { return FILTER_JAVAX; }

                        @Override
                        public String targetMethodName() { return DO_FILTER; }

                        @Override
                        public String buildInsertBeforeSource(
                                String targetClassName, String targetMethodName) {
                            return capturedBeforeCode;
                        }
                    });
        } catch (Exception e) {
            // Fail-Open: 위빙 실패 시 GenericFilterWeavingStrategy(byte-buddy) 폴백 시도
            new GenericFilterWeavingStrategy(config, log).install(inst);
        }
    }
}
```

### 4-A.3 Fail-Open 폴백 흐름

```
LegacyJavassistWeavingStrategy.install()
    │
    ├── Javassist 위빙 성공 → javax.servlet.Filter.doFilter() 위빙 완료
    │
    └── Javassist 위빙 실패 (예외 발생)
            │
            └── GenericFilterWeavingStrategy.install() 시도 (byte-buddy)
                    │
                    ├── byte-buddy 성공 → javax+jakarta 이중 위빙
                    │
                    └── byte-buddy도 실패 → 로그 출력 후 Agent 비활성 (WAS 기동 계속)
```

### 4-A.4 WAS별 특이사항 대응

**JBoss AS 5/6 — 복잡한 클래스로더 계층**:
```java
// JBoss는 내부 Tomcat(jboss-web)을 사용하므로
// javax.servlet.Filter가 jboss-web ClassLoader에 속함
// ClassPool에 로더를 추가하는 JavassistClassFileTransformer가 자동 처리
```

**WebLogic — FilteringClassLoader**:
```java
// 위빙 성공해도 WAS 애플리케이션에서 Agent 클래스가 안 보일 수 있음
// weblogic.xml에 prefer-application-packages 설정 필요 (운영 가이드 참고)
```

**WebSphere — IBM J9 JVM**:
```java
// IBM J9는 JVM TI 동작이 HotSpot과 다름
// Javassist는 J9에서도 정상 동작 (byte-buddy는 불안정)
// -Donepass.was.type=WEBSPHERE_LEGACY로 강제 지정 가능
```

---

## 4-B. TomcatVersionedWeavingStrategy 내부 구조 가이드

> **대상**: Tomcat 버전별(LEGACY/7/8/9/10+) 분기 위빙 전략

### 4-B.1 버전별 분기 구조

```java
public void install(Instrumentation inst) {
    int jdkMajor = WasDetector.getRuntimeJdkMajor();

    switch (tomcatVersion) {
        case TOMCAT_LEGACY:
            installLegacyJavassist(inst, jdkMajor);  // Javassist → ApplicationFilterChain
            break;
        case TOMCAT_7:
            installTomcat7(inst, jdkMajor);           // JDK 분기: Javassist or byte-buddy
            break;
        case TOMCAT_8:
        case TOMCAT_9:
            installTomcat8And9(inst);                 // byte-buddy: Valve + javax.Filter
            break;
        case TOMCAT_10_PLUS:
            installTomcat10Plus(inst);                // byte-buddy: jakarta.Filter 전용
            break;
        default:
            installTomcatDefault(inst);               // byte-buddy: Valve + 이중 Filter
    }
}
```

### 4-B.2 위빙 엔진 선택 기준

| Tomcat 버전 | JDK | 위빙 엔진 | 위빙 포인트 | 이중 위빙 |
|------------|-----|---------|-----------|---------|
| 5.x/6.x | 5~6 | Javassist | `ApplicationFilterChain.internalDoFilter()` | ❌ |
| 7.x | 7 | Javassist | `StandardContextValve.invoke()` | ❌ |
| 7.x | 8+ | byte-buddy | Catalina Valve | ❌ |
| 8.x/8.5 | 8 | byte-buddy | Catalina Valve + javax.Filter | ✅ |
| 9.x | 8+ | byte-buddy | Catalina Valve + javax.Filter | ✅ |
| 10.x/11 | 11+ | byte-buddy | jakarta.Filter 전용 | ❌ |
| (미감지) | 8+ | byte-buddy | Catalina Valve + javax.Filter + jakarta.Filter | ✅✅ |

### 4-B.3 System Property Bridge 패턴

Javassist로 삽입된 코드는 Agent 클래스를 직접 참조할 수 없으므로,  
`System.setProperty()`로 설정을 공유하고 삽입 코드에서 `System.getProperty()`로 읽습니다.

```java
// Agent 쪽 (JDK 8+에서 실행)
System.setProperty("onepass.agent.endpoint", config.endpoint());
System.setProperty("onepass.agent.api-key",  config.apiKey());
System.setProperty("onepass.agent.enabled",  String.valueOf(config.isEnabled()));

// Javassist 삽입 코드 (WAS JDK 환경에서 실행)
String beforeCode =
    "String _endpoint = System.getProperty(\"onepass.agent.endpoint\", \"\");" +
    "String _apiKey   = System.getProperty(\"onepass.agent.api-key\", \"\");" +
    "String _enabled  = System.getProperty(\"onepass.agent.enabled\", \"false\");";
```

### 4-B.4 Advice 공유 상태 관리

byte-buddy Advice 클래스는 static 필드로 공유 상태를 관리합니다:

```java
// TomcatVersionedWeavingStrategy 내부
static volatile OnePassHttpClient sharedHttpClient;  // volatile → 가시성 보장
static volatile AgentConfig       sharedConfig;
static volatile PrintStream        sharedLog;

// install() 시점에 초기화
public void install(Instrumentation inst) {
    sharedHttpClient = new OnePassHttpClient(config);
    sharedConfig     = config;
    sharedLog        = log;
    // ...
}

// Advice 클래스에서 참조
public static class TomcatValveAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response) {
        AgentConfig cfg = TomcatVersionedWeavingStrategy.sharedConfig;
        if (cfg == null || !cfg.isEnabled()) return;
        // ...
    }
}
```

> **⚠️ 주의**: `suppress = Throwable.class`로 Advice 예외를 자동 억제합니다.  
> Advice에서 예외가 발생해도 WAS 요청 처리는 계속됩니다(Fail-Open).

---


---

## 5. 새 JEUS 버전 지원 추가

JEUS 22 (가상)처럼 새 버전이 나올 때 대응하는 방법:

### Step 1: `WasType.java` 수정

```java
// 기존 JEUS_9_PLUS를 분리하거나 새 상수 추가
JEUS_9_PLUS("JEUS 9/21/22 (JDK 11+, Jakarta EE)"),
// 또는
JEUS_22("JEUS 22 (JDK 21+, Jakarta EE 11)"),
```

### Step 2: `WasDetector.java`에 감지 클래스 추가

```java
// JEUS 22 고유 클래스 (예상)
private static final String CLS_JEUS22_ENGINE =
    "com.tmaxsoft.jeus.web.servlet.engine.JeusServletEngine22";

// detectJeusByClasspath() 내 최상단에 추가 (역순 탐색 유지)
if (isClassPresent(CLS_JEUS22_ENGINE)) {
    logInfo(log, "[WasDetector] JEUS 22 클래스 감지");
    return WasType.JEUS_9_PLUS; // 또는 JEUS_22
}
```

### Step 3: `JeusWeavingEngineSelector.java` 업데이트

```java
if (wasType == WasType.JEUS_22) {
    logInfo(log, "[EngineSelector] JEUS_22 + JDK " + jdkMajor + " → BYTE_BUDDY");
    return EngineType.BYTE_BUDDY;
}
```

---

## 6. Javassist 위빙 코드 작성 가이드

### 6.1 기본 원칙

Javassist `insertBefore()` 소스 코드 문자열 내에서는:
- **JDK 1.4 수준 API만 사용** (제네릭, lambda, var 등 금지)
- **Agent 클래스 직접 참조 금지** (ClassLoader 분리)
- **System.getProperty()로 설정 읽기** (Agent → JVM 전역)
- **모든 예외 Throwable로 캐치** (위빙된 코드의 예외가 업무 로직을 막으면 안 됨)

### 6.2 소스 코드 문자열 작성 패턴

```java
// Javassist 소스 코드 문자열 — JDK 1.4 호환
private static String buildInsertBeforeSource() {
    return "{"
        // ── 활성화 여부 확인 ──────────────────────────────────
        + "  String _enabled = System.getProperty(\"onepass.agent.enabled\", \"true\");"
        + "  if (!\"true\".equalsIgnoreCase(_enabled)) return;"
        
        // ── 설정 값 읽기 (System.getProperty) ────────────────
        + "  String _endpoint = System.getProperty(\"onepass.agent.endpoint\");"
        + "  String _apiKey   = System.getProperty(\"onepass.agent.api-key\");"
        + "  if (_endpoint == null || _apiKey == null) return;"
        
        // ── 요청 객체 캐스팅 ($1 = 첫 번째 파라미터) ──────────
        + "  javax.servlet.http.HttpServletRequest _req ="
        + "      (javax.servlet.http.HttpServletRequest) $1;"
        + "  javax.servlet.http.HttpServletResponse _res ="
        + "      (javax.servlet.http.HttpServletResponse) $2;"
        
        // ── 토큰 추출 ────────────────────────────────────────
        + "  String _token = _req.getHeader(\"X-OnePass-Token\");"
        
        // ── 검증 API 호출 ─────────────────────────────────────
        + "  try {"
        + "    java.net.URL _url = new java.net.URL(_endpoint + \"/api/v1/agent/verify\");"
        + "    java.net.HttpURLConnection _conn ="
        + "        (java.net.HttpURLConnection) _url.openConnection();"
        + "    _conn.setRequestMethod(\"POST\");"
        + "    _conn.setRequestProperty(\"X-OnePass-Api-Key\", _apiKey);"
        + "    _conn.setRequestProperty(\"X-OnePass-Token\","
        + "        _token != null ? _token : \"\");"
        + "    _conn.setRequestProperty(\"Content-Type\", \"application/json\");"
        + "    _conn.setConnectTimeout(5000);"
        + "    _conn.setReadTimeout(10000);"
        + "    _conn.setDoOutput(true);"
        + "    _conn.connect();"
        + "    int _status = _conn.getResponseCode();"
        
        // ── 검증 실패: 401 반환 ──────────────────────────────
        + "    if (_status == 401 || _status == 403) {"
        + "      _res.sendError(401, \"OnePass: Unauthorized\");"
        + "      return;"  // ← service() 메서드 조기 반환
        + "    }"
        + "    _conn.disconnect();"
        + "  } catch (Throwable _t) {"
        
        // ── 예외 시 Fail-Open (업무 로직 계속) ───────────────
        + "    System.err.println(\"[OnePassAgent] 검증 실패 (Fail-Open): \" + _t.getMessage());"
        + "  }"
        + "}";
}
```

### 6.3 Javassist 특수 변수

```
$0          this (대상 메서드의 this 객체)
$1, $2, ... 메서드 파라미터 (1번부터 시작)
$args       Object[] 모든 파라미터
$_          반환값 (insertAfter에서 사용)
$type       반환 타입 Class 객체
$$          모든 파라미터를 다른 메서드에 전달 시
```

### 6.4 ClassFileTransformer 구현 패턴

```java
public abstract static class JavassistClassFileTransformer
    implements ClassFileTransformer {

    @Override
    public final byte[] transform(
            ClassLoader loader,
            String className,           // 슬래시 구분자: "com/example/Foo"
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {

        // 타겟 클래스 이름을 슬래시→점 변환 후 비교
        String dotName = className.replace('/', '.');
        if (!targetClassName().equals(dotName)) {
            return null;  // 관심 없는 클래스: null 반환 (원본 유지)
        }

        try {
            // ClassPool은 ClassLoader별로 생성
            ClassPool pool = ClassPool.getDefault();
            pool.appendClassPath(new LoaderClassPath(loader));

            CtClass ctClass = pool.makeClass(
                new java.io.ByteArrayInputStream(classfileBuffer));

            if (ctClass.isFrozen()) {
                ctClass.defrost();
            }

            try {
                // 메서드 찾기 + 위빙
                for (CtMethod method : ctClass.getDeclaredMethods()) {
                    if (shouldWeaveMethod(method)) {
                        method.insertBefore(buildInsertBeforeSource(
                            targetClassName(), method.getName()));
                    }
                }
                return ctClass.toBytecode();

            } finally {
                ctClass.detach();  // ← 메모리 누수 방지 필수!
            }

        } catch (Throwable t) {
            System.err.println("[Javassist] 변환 실패: " + t.getMessage());
            return null;  // 원본 바이트코드 유지
        }
    }
}
```

---

## 7. byte-buddy Advice 작성 가이드

### 7.1 기본 Advice 패턴

```java
// AgentBuilder + Advice 패턴
new AgentBuilder.Default()
    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
    .with(new WeavingListener(log))
    // 타입 매처: javax.servlet.Filter 구현체
    .type(isSubTypeOf(named("javax.servlet.Filter")))
    .transform((builder, typeDescription, classLoader, module, pd) ->
        builder.method(named("doFilter").and(takesArguments(3)))
               .intercept(Advice.to(OnePassFilterAdvice.class))
    )
    .installOn(inst);
```

### 7.2 Advice 클래스 작성

```java
public class OnePassFilterAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Argument(0) Object requestObj,
            @Advice.Argument(1) Object responseObj) {

        // byte-buddy Advice는 AgentConfig를 직접 참조 가능
        // (같은 ClassLoader에서 실행되지 않을 수 있으므로 System.getProperty 사용 권장)
        String enabled = System.getProperty("onepass.agent.enabled", "true");
        if (!"true".equalsIgnoreCase(enabled)) return;

        try {
            javax.servlet.http.HttpServletRequest req =
                (javax.servlet.http.HttpServletRequest) requestObj;
            javax.servlet.http.HttpServletResponse res =
                (javax.servlet.http.HttpServletResponse) responseObj;

            String token = req.getHeader("X-OnePass-Token");
            // ... 검증 로직
        } catch (Throwable t) {
            System.err.println("[OnePassAdvice] Fail-Open: " + t.getMessage());
        }
    }
}
```

### 7.3 Jakarta 이중 위빙 (JEUS 9+)

```java
// javax와 jakarta 모두 위빙하는 패턴
AgentBuilder builder = new AgentBuilder.Default()
    .with(new WeavingListener(log));

// javax.servlet.Filter (JEUS 8.5 이하)
builder = builder
    .type(isSubTypeOf(named("javax.servlet.Filter")))
    .transform((b, t, cl, m, pd) ->
        b.method(named("doFilter")).intercept(Advice.to(JavaxFilterAdvice.class)));

// jakarta.servlet.Filter (JEUS 9+)
builder = builder
    .type(isSubTypeOf(named("jakarta.servlet.Filter")))
    .transform((b, t, cl, m, pd) ->
        b.method(named("doFilter")).intercept(Advice.to(JakartaFilterAdvice.class)));

builder.installOn(inst);
```

---

## 8. 테스트 작성 가이드

### 8.1 테스트 파일 위치 규칙

```
idem-agent/src/test/java/kr/go/smes/agent/
├── config/
│   └── AgentConfigTest.java          ← AgentConfig 단위 테스트
├── core/
│   └── OnePassAgentMainTest.java     ← doInstall() Mock Instrumentation 테스트
├── was/
│   └── WasDetectorTest.java          ← 감지 로직 + 시스템 프로퍼티 테스트
├── http/
│   └── OnePassHttpClientTest.java    ← HttpURLConnection Mock 테스트
└── weaving/
    └── jeus/
        ├── JeusWeavingEngineSelectorTest.java  ← EngineType 조합 테스트
        └── WeavingStrategyFactoryJeusTest.java ← Factory 라우팅 테스트
```

### 8.2 시스템 프로퍼티 테스트 패턴 (중요!)

```java
// 시스템 프로퍼티를 사용하는 테스트는 반드시 @AfterEach에서 정리
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WasDetectorTest {

    private static final Set<String> CLEANUP_PROPS = new HashSet<>(Arrays.asList(
        "onepass.was.type",
        "jeus.home",
        "jeus.server.name",
        "jeus.engine.name",
        "jeus.version"
    ));

    @AfterEach
    void cleanupSystemProps() {
        for (String key : CLEANUP_PROPS) {
            System.clearProperty(key);
        }
    }

    @Test
    void detectJeusLegacy_viaSystemProperty() {
        System.setProperty("jeus.home", "/opt/jeus4");
        System.setProperty("jeus.version", "4.2.0");

        WasType type = WasDetector.detect(System.err);

        assertEquals(WasType.JEUS_LEGACY, type);
    }
}
```

### 8.3 파라미터화 테스트 패턴

```java
// WasType × JDK 버전 조합 검증
@ParameterizedTest
@CsvSource({
    "JEUS_LEGACY, 4,  JAVASSIST",
    "JEUS_LEGACY, 5,  JAVASSIST",
    "JEUS_LEGACY, 8,  JAVASSIST",    // LEGACY는 항상 Javassist
    "JEUS_6,      5,  JAVASSIST",
    "JEUS_6,      7,  JAVASSIST",
    "JEUS_7,      7,  JAVASSIST",
    "JEUS_7,      8,  BYTE_BUDDY",
    "JEUS_8,      7,  JAVASSIST",
    "JEUS_8,      8,  BYTE_BUDDY",
    "JEUS_8_5,    8,  BYTE_BUDDY",
    "JEUS_9_PLUS, 11, BYTE_BUDDY",
    "JEUS_9_PLUS, 21, BYTE_BUDDY",
})
void selectEngineType(
        String wasTypeName,
        int jdkMajor,
        String expectedEngine) {

    WasType wasType = WasType.valueOf(wasTypeName);
    EngineType expected = EngineType.valueOf(expectedEngine);

    EngineType actual = JeusWeavingEngineSelector.selectWithJdk(
        wasType, jdkMajor, null);

    assertEquals(expected, actual,
        () -> "wasType=" + wasType + ", jdk=" + jdkMajor);
}
```

### 8.4 테스트 자원 관리

```
test/resources/
└── onepass-agent.properties   ← 테스트용 설정 파일
    (endpoint=http://localhost, api-key=test-key)
```

```java
// 테스트용 AgentConfig 로드 패턴
AgentConfig config = AgentConfig.load(
    "config=idem-agent/src/test/resources/onepass-agent.properties",
    System.err
);
```

---

## 9. fat-JAR 빌드 및 검증

### 9.1 빌드 명령

```bash
./gradlew :idem-agent:agentJar

# 출력 파일
ls -lh idem-agent/build/libs/
# idem-agent-1.0.0-all.jar (약 9.7MB)
```

### 9.2 빌드 검증 체크리스트

```bash
JAR_FILE="idem-agent/build/libs/idem-agent-1.0.0-all.jar"

# 1. MANIFEST 필수 항목
jar xf "$JAR_FILE" META-INF/MANIFEST.MF -C /tmp/
cat /tmp/META-INF/MANIFEST.MF
# 확인: Premain-Class, Agent-Class, Can-Retransform-Classes: true

# 2. Agent 클래스 존재
jar tf "$JAR_FILE" | grep "OnePassAgentMain"
# 기대: kr/go/smes/agent/core/OnePassAgentMain.class

# 3. byte-buddy 번들링 확인
jar tf "$JAR_FILE" | grep "bytebuddy" | wc -l
# 기대: 수백 개 (일반적으로 500+)

# 4. Javassist 번들링 확인
jar tf "$JAR_FILE" | grep "javassist" | wc -l
# 기대: 수백 개

# 5. Spring 클래스 없음 확인
jar tf "$JAR_FILE" | grep "springframework"
# 기대: 출력 없음

# 6. 파일 크기 확인
ls -lh "$JAR_FILE"
# 기대: 8~11MB 범위
```

### 9.3 MANIFEST 전체 내용 예시

```
Manifest-Version: 1.0
Premain-Class: kr.go.smes.agent.core.OnePassAgentMain
Agent-Class: kr.go.smes.agent.core.OnePassAgentMain
Can-Redefine-Classes: true
Can-Retransform-Classes: true
Boot-Class-Path: 
Implementation-Title: OnePass Agency Java Agent
Implementation-Version: 1.0.0
```

---

## 10. 코드 품질 기준

### 10.1 PR 머지 조건

```
✅ 테스트 통과: 전체 1,207+ 개 (0 failures)
✅ agentJar 빌드 성공 (fat-JAR 생성)
✅ MANIFEST 필수 항목 존재
✅ Spring 의존성 없음
✅ JDK 8 소스 호환 컴파일
✅ 새 기능에 대한 단위 테스트 작성
✅ 위빙 전략 추가 시 Factory 라우팅 테스트 작성
```

### 10.2 클래스 설계 규칙

```
- 모든 유틸리티 클래스: final + private 생성자
- Instrumentation 사용 클래스: 단일 책임 원칙
- ClassFileTransformer: 실패 시 반드시 null 반환 (원본 유지)
- 시스템 프로퍼티 읽기: null 방어 + 기본값 제공
- 위빙 오류: WeavingInstallException or Throwable catch + 로그
```

### 10.3 Javassist 메모리 누수 방지

```java
// 반드시 finally 블록에서 detach() 호출
CtClass ctClass = null;
try {
    ctClass = pool.makeClass(...);
    // 위빙 작업
    return ctClass.toBytecode();
} catch (Throwable t) {
    return null;
} finally {
    if (ctClass != null) {
        ctClass.detach();  // ← 이게 없으면 ClassPool 메모리 누수
    }
}
```

---

## 11. 알려진 기술 부채

| ID | 설명 | 우선순위 | Sprint |
|----|------|---------|--------|
| D-AGENT-01 | Agent 클래스 자체가 JDK 8+ 필요 (JEUS 4/5 JDK 1.5에 직접 적용 불가) | High | Sprint 21 |
| D-AGENT-02 | Fail-Open 정책만 지원 (Fail-Closed 옵션 필요) | High | Sprint 21 |
| D-AGENT-03 | 토큰 검증 결과 캐시 미지원 (매 요청마다 API 호출) | Medium | Sprint 22 |
| D-AGENT-04 | byte-buddy 패키지 relocation 미적용 (유관기관 충돌 가능) | Medium | Sprint 22 |
| D-AGENT-05 | Javassist 소스 코드 문자열 단위 테스트 없음 | Low | Sprint 23 |
| D-AGENT-06 | Agent 버전 자동 업데이트 메커니즘 없음 | Low | Sprint 24 |

### D-AGENT-01 해결 방향 (JDK 1.5 직접 지원)

현재 구조:
```
[JDK 1.5 JEUS 4/5 JVM] ← Agent가 JDK 8 클래스파일 → ❌ UnsupportedClassVersionError
```

해결 방향 1: `--release 5` 컴파일 (Gradle 별도 태스크)
```kotlin
val agentJarLegacy by tasks.registering(Jar::class) {
    // Agent 핵심 클래스만 JDK 5 타겟으로 컴파일
    // Javassist만 번들링 (byte-buddy 제외)
}
```

해결 방향 2: Thin Agent (프록시 방식)
```
[JDK 1.5 JEUS 4/5 JVM]
  └── onepass-agent-legacy-1.0.0.jar (JDK 1.5 컴파일)
        → System.setProperty로 설정
        → Javassist 위빙
        → HttpURLConnection API 호출
```

---

## 12. 릴리즈 절차

### 12.1 버전 관리

```kotlin
// build.gradle.kts (루트)
version = "1.0.1"  // 버전 업데이트
```

### 12.2 릴리즈 체크리스트

```
[ ] 버전 번호 업데이트 (build.gradle.kts)
[ ] AGENT_BANNER 버전 업데이트 (OnePassAgentMain.java)
[ ] CHANGELOG 업데이트
[ ] 전체 테스트 통과 확인
[ ] fat-JAR 빌드 및 체크섬 생성
[ ] GitHub Release 태그 생성
[ ] 릴리즈 노트 작성
[ ] 유관기관 담당자에게 배포 공지
```

### 12.3 fat-JAR 체크섬 생성

```bash
./gradlew :idem-agent:agentJar

JAR="idem-agent/build/libs/onepass-agent-$(./gradlew properties -q | grep "version:" | awk '{print $2}')-all.jar"
sha256sum "$JAR" > "${JAR}.sha256"
cat "${JAR}.sha256"
```
