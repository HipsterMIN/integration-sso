# OnePass Agency Java Agent — 아키텍처 설계서

> **명칭 안내 (2026-09-07)** — 이 문서의 `onepass.agent.*` 설정 키, `onepass-agent.properties`, `ONEPASS_*` 환경변수, `OnePass-*` 헤더, `OnePassAgent*` 클래스명은 개명 4단계(Java 패키지·런타임 식별자) 전까지 **구명을 그대로 사용**한다. 모듈·이미지·파일 이름만 Idem 신명이다. 대응표: [docs/naming.md](../../naming.md) §3.

> **문서 번호**: AGENT-ARCH-001  
> **문서 버전**: v1.0.0  
> **작성일**: 2026-05-17  
> **최종 수정**: 2026-05-17  
> **분류**: 내부 기술 문서 (Internal Technical Document)  
> **대상 독자**: OnePass 플랫폼 아키텍트, 백엔드 개발자, 기술 검토자  

---

## 목차

1. [문서 개요](#1-문서-개요)
2. [핵심 설계 결정 (ADR)](#2-핵심-설계-결정-adr)
3. [전체 아키텍처 개요](#3-전체-아키텍처-개요)
4. [JDK 1.5 SSO 가능성 분석 및 결론](#4-jdk-15-sso-가능성-분석-및-결론)
5. [JEUS 버전별 기술 매트릭스](#5-jeus-버전별-기술-매트릭스)
6. [컴포넌트 설계](#6-컴포넌트-설계)
7. [위빙(Weaving) 엔진 설계](#7-위빙weaving-엔진-설계)
8. [초기화 흐름 (premain 라이프사이클)](#8-초기화-흐름-premain-라이프사이클)
9. [설정 체계](#9-설정-체계)
10. [보안 설계](#10-보안-설계)
11. [에러 처리 철학](#11-에러-처리-철학)
12. [패키징 및 배포 아키텍처](#12-패키징-및-배포-아키텍처)
13. [클래스로더 격리 전략](#13-클래스로더-격리-전략)
14. [성능 고려사항](#14-성능-고려사항)
15. [테스트 전략](#15-테스트-전략)

---

## 1. 문서 개요

### 1.1 목적

본 문서는 **OnePass Agency Java Agent** (`idem-agent`)의 아키텍처 설계를 상세하게 기술한다.  
이 에이전트는 유관기관 WAS(Web Application Server)에 부착되어 **코드 수정 없이** SSO(Single Sign-On)를 구현하는 자바 에이전트이다.

### 1.2 배경

한국 공공기관(유관기관)은 다음과 같은 이유로 일반 SDK 방식보다 에이전트 방식이 필요하다:

| 문제 | 설명 |
|------|------|
| **레거시 WAS** | JEUS 4/5 운영 기관은 JDK 1.5 환경이며 소스 수정이 불가능하거나 매우 어려움 |
| **운영 중단 불가** | 공공기관 시스템은 소스 배포 절차가 수개월 소요 |
| **WAS 다양성** | JEUS(버전 4~21), Tomcat, WebLogic 등 다양한 환경 혼재 |
| **담당자 부재** | 레거시 시스템의 경우 원래 개발사가 없는 경우 존재 |

### 1.3 핵심 판단: JDK 1.5에서 OnePass Agent로 SSO 가능한가?

**답: ✅ 가능하다.**

- JEUS 4/5를 사용하는 유관기관은 JDK 1.4~1.5 환경이다.
- `java.lang.instrument.Instrumentation` + `Premain-Class` (JSR-163)는 **JDK 1.5**에 도입됐다.
- `Javassist 3.x`는 **JDK 1.3+** 호환이므로 JDK 1.5에서 바이트코드 위빙이 가능하다.
- `HttpURLConnection` (JDK 1.1+), `javax.crypto.Mac` (JDK 1.4+)으로 SSO 검증 통신이 가능하다.
- 단, `agentmain()` (Attach API, JDK 1.6 도입)은 불가하므로 **정적 어태치(`-javaagent:`)만 사용**한다.

---

## 2. 핵심 설계 결정 (ADR)

### ADR-AGENT-001: Java Agent 방식 채택 이유

**결정**: SDK 라이브러리 배포가 아닌 Java Agent (`-javaagent:`) 방식 채택

**이유**:
- 유관기관 소스 코드 수정 없이 SSO 적용 가능
- WAS 재시작만으로 즉시 활성화 (빌드/배포 절차 불필요)
- `onepass.agent.enabled=false` 설정으로 즉시 비활성화 가능 (롤백 용이)

**대안 고려**:
- SDK 방식: 유관기관 소스 수정 필요 → 레거시 기관 적용 불가
- Servlet Filter 수동 등록: web.xml 수정 필요 → 역시 소스/설정 변경 필요

---

### ADR-AGENT-002: 이중 위빙 엔진 (byte-buddy + Javassist)

**결정**: 단일 fat-JAR에 byte-buddy(JDK 8+)와 Javassist(JDK 1.3+)를 동시 번들링, 런타임 JDK 버전으로 자동 선택

**이유**:
- byte-buddy 1.17.x는 JDK 8 런타임 필수 → JEUS 4/5/6 불가
- Javassist 3.x는 JDK 1.3+ 호환 → 모든 환경 커버
- 단일 JAR 파일 하나로 JEUS 4부터 21까지 전 버전 지원

**결정 매트릭스**:

```
JEUS_LEGACY (JDK 1.4~1.5) → 항상 Javassist
JEUS_6      (JDK 1.5~1.7) → 항상 Javassist (통일 정책)
JEUS_7      (JDK 1.6~1.8) → JDK < 8: Javassist / JDK ≥ 8: byte-buddy
JEUS_8      (JDK 1.7~1.8) → JDK < 8: Javassist / JDK ≥ 8: byte-buddy
JEUS_8.5+   (JDK 8+)      → 항상 byte-buddy
```

---

### ADR-AGENT-003: WAS 자동 감지 우선순위

**결정**: JEUS 감지를 타 WAS보다 최상위 우선순위로 처리

**이유**: 한국 공공기관 특화 에이전트이므로 JEUS 환경이 압도적 다수

**감지 순서**:
1. 시스템 프로퍼티 명시적 오버라이드 (`-Donepass.was.type=JEUS_LEGACY`)
2. 클래스패스 탐색 (JEUS 9 → 4/5 역순, 신→구)
3. 시스템 프로퍼티 패턴 (`jeus.home`, `jeus.version` 등)
4. 환경변수 (`JEUS_HOME`, `CATALINA_HOME` 등)
5. Fallback → `UNKNOWN` (Generic Filter 위빙)

---

### ADR-AGENT-004: 보안 실패 vs 서비스 가용성

**결정**: Agent 초기화 실패 시 WAS 기동을 막지 않음 (서비스 가용성 우선)

**이유**:
- 공공기관 시스템은 서비스 중단이 민원으로 직결
- SSO 실패는 담당자 알림 → 수동 조치 가능
- Agent 비활성화보다 서비스 다운이 더 큰 문제

**구현**: 모든 위빙 실패를 `WARN` 로그 후 계속 진행

> **⚠️ 예외**: `AgentConfig.AgentConfigException` — 필수 설정 누락 시에는 Agent를 비활성화하고 WAS 기동은 허용

---

### ADR-AGENT-005: 설정 공유 — System Property 브리지

**결정**: JEUS 4/5 ClassLoader 환경에서 `System.setProperty()` → `System.getProperty()`로 설정 공유

**이유**:
- JEUS 4/5 ClassLoader는 Agent JAR의 클래스를 직접 참조 불가
- Javassist 소스 코드 문자열로 삽입된 코드는 순수 JDK API만 사용 가능
- `System.getProperty()`는 JVM 전역으로 공유됨 (ClassLoader 경계 무관)

---

## 3. 전체 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        유관기관 JVM 프로세스                                 │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  OnePass Agency Java Agent (idem-agent-1.0.0-all.jar)           │   │
│  │                                                                     │   │
│  │  OnePassAgentMain.premain()                                         │   │
│  │       │                                                             │   │
│  │       ├─ 1. AgentConfig.load()     ← 설정 파일 / -D 옵션           │   │
│  │       ├─ 2. WasDetector.detect()   ← 클래스패스 / 시스템 프로퍼티   │   │
│  │       ├─ 3. WeavingStrategyFactory.create()                         │   │
│  │       │       │                                                     │   │
│  │       │       ├─ JEUS_LEGACY → JeusLegacyWeavingStrategy           │   │
│  │       │       ├─ JEUS_6      → Jeus6WeavingStrategy                │   │
│  │       │       ├─ JEUS_7/8    → Jeus7PlusWeavingStrategy            │   │
│  │       │       ├─ JEUS_8.5+   → Jeus8_5PlusWeavingStrategy         │   │
│  │       │       └─ TOMCAT 등   → TomcatWeavingStrategy               │   │
│  │       │                                                             │   │
│  │       └─ 4. strategy.install(inst) ← 위빙 Transformer 등록         │   │
│  │                                                                     │   │
│  │  위빙 엔진:                                                          │   │
│  │  ┌──────────────────────┐    ┌──────────────────────────────────┐  │   │
│  │  │  JavassistWeavingEngine│   │  byte-buddy AgentBuilder         │  │   │
│  │  │  (JDK 1.3+ 호환)      │   │  (JDK 8+ 전용)                   │  │   │
│  │  │  JEUS 4/5/6 전용      │   │  JEUS 7/8/8.5/9+, Tomcat 등     │  │   │
│  │  └──────────────────────┘    └──────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  유관기관 WAS (JEUS / Tomcat / ...)                                  │   │
│  │                                                                     │   │
│  │  HTTP 요청 수신                                                      │   │
│  │       │                                                             │   │
│  │       ▼  ← [위빙된 Before-Advice 코드 실행]                         │   │
│  │  ┌────────────────────────────────────────────────────────────┐    │   │
│  │  │  SSO 토큰 검증 로직 (Javassist 삽입 or byte-buddy Advice)   │    │   │
│  │  │                                                            │    │   │
│  │  │  1. X-OnePass-Token 헤더 추출                               │    │   │
│  │  │  2. OnePassHttpClient → OnePass 서버 검증 API 호출          │    │   │
│  │  │  3. 검증 실패: sendError(401) / 성공: 계속 진행             │    │   │
│  │  └────────────────────────────────────────────────────────────┘    │   │
│  │       │                                                             │   │
│  │       ▼                                                             │   │
│  │  기관 업무 로직 (변경 없음)                                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                              │  HTTP/HTTPS  │
                              ▼              ▼
              ┌───────────────────────────────────────┐
              │  OnePass 통합인증 서버                 │
              │  (SSO 토큰 발급 / 검증 API)            │
              └───────────────────────────────────────┘
```

---

## 4. JDK 1.5 SSO 가능성 분석 및 결론

### 4.1 기술 스택 호환성 검토

| 기술 요소 | 최소 JDK | JDK 1.5 가능? | 비고 |
|-----------|---------|--------------|------|
| `java.lang.instrument.Instrumentation` | **JDK 1.5** | ✅ 가능 | JSR-163 도입 |
| `premain(String, Instrumentation)` | **JDK 1.5** | ✅ 가능 | `-javaagent:` 플래그 |
| `agentmain()` (Attach API) | JDK 1.6 | ❌ 불가 | 동적 어태치 불가 |
| `Instrumentation.addTransformer()` | **JDK 1.5** | ✅ 가능 | ClassFileTransformer 등록 |
| `Instrumentation.retransformClasses()` | JDK 1.6 | ❌ 불가 | 이미 로드된 클래스 재변환 |
| `java.net.HttpURLConnection` | JDK 1.1 | ✅ 가능 | OnePass API 통신 |
| `javax.crypto.Mac` (HMAC-SHA256) | JDK 1.4 | ✅ 가능 | 요청 서명 |
| **Javassist 3.x** | **JDK 1.3** | ✅ 가능 | 바이트코드 위빙 엔진 |
| byte-buddy 1.17.x | JDK 8 (런타임) | ❌ 불가 | UnsupportedClassVersionError |

### 4.2 JDK 1.5 제약 사항과 대응책

**제약 1: Attach API 불가**
- `agentmain()` / `VirtualMachine.attach()` 사용 불가
- **대응**: `-javaagent:` 정적 어태치만 사용 (WAS 기동 스크립트에 JVM 옵션 추가)

**제약 2: byte-buddy 불가**
- byte-buddy 클래스 파일이 JDK 8+ 클래스 포맷
- JDK 1.5에서 `NoClassDefFoundError` 발생
- **대응**: Javassist 3.x로 대체 (`CtMethod.insertBefore(String sourceCode)`)

**제약 3: `retransformClasses()` 불가**
- premain 이전에 이미 로드된 클래스 재변환 불가
- **대응**: 핵심 위빙 포인트(JEUS 4/5 HttpServletWrapper)는 WAS 초기화 과정에서 로드되므로 premain이 먼저 실행되면 문제없음

**제약 4: 제네릭 / var / lambda 등 최신 Java 문법**
- Javassist 소스 코드 문자열 내에서는 JDK 1.4 수준 문법만 사용 가능
- **대응**: `System.getProperty()`, `HttpURLConnection`, `StringBuffer` 등 J2SE 1.4 API로 구현

### 4.3 결론

```
JEUS 4/5(JDK 1.5) + OnePass Agent + Javassist:
  ✅ -javaagent: 정적 어태치로 SSO 위빙 가능
  ✅ premain() 단계에서 위빙 Transformer 등록
  ✅ HttpServletWrapper#service 위빙으로 모든 HTTP 요청 인터셉트
  ✅ OnePass 서버와 HttpURLConnection으로 토큰 검증
  ✅ 검증 실패 시 HTTP 401 응답 (sendError)
```

---

## 5. JEUS 버전별 기술 매트릭스

| JEUS 버전 | 공식 지원 JDK | Servlet API | EE 표준 | JEUS 클래스 패키지 | 위빙 엔진 | 위빙 포인트 |
|-----------|-------------|------------|--------|-----------------|---------|-----------|
| **4** | JDK 1.4~1.5 | 2.3 | J2EE 1.3 | `com.tmax.jeus.*` | Javassist | `HttpServletWrapper#service` |
| **5** | JDK 1.4~1.5 | 2.4 | J2EE 1.4 | `com.tmax.jeus.*` | Javassist | `HttpServletWrapper#service` |
| **6** | JDK 1.5~1.7 | 2.5 | Java EE 5 | `com.tmaxsoft.jeus.*` | Javassist | `JeusServletHandler#service` |
| **7** | JDK 1.6~1.8 | 3.0 | Java EE 6 | `com.tmaxsoft.jeus.*` | JDK 7: Javassist / JDK 8: byte-buddy | `javax.servlet.Filter#doFilter` |
| **8** | JDK 1.7~1.8 | 3.1 | Java EE 7 | `com.tmaxsoft.jeus.*` | JDK 7: Javassist / JDK 8: byte-buddy | `javax.servlet.Filter#doFilter` |
| **8.5** | JDK 8 / 11 | 4.0 | Java EE 8 | `com.tmaxsoft.jeus.*` | byte-buddy | `javax.servlet.Filter#doFilter` |
| **9** | JDK 11+ | 5.0 | Jakarta EE 9 | `com.tmaxsoft.jeus.*` + `jakarta.*` | byte-buddy | `jakarta.servlet.Filter#doFilter` |
| **21** | JDK 21+ | 6.0 | Jakarta EE 10 | `com.tmaxsoft.jeus.*` + `jakarta.*` | byte-buddy | `jakarta.servlet.Filter#doFilter` |

### JEUS 버전 감지 단서 (클래스패스 탐색)

```
com.tmaxsoft.jeus.web.servlet.engine.JeusServletEngine9  → JEUS 9+
jakarta.servlet.ServletRequest                           → JEUS 9+ (Jakarta EE)
com.tmaxsoft.jeus.web.servlet.JeusServlet4Container      → JEUS 8.5
com.tmaxsoft.jeus.web.http2.JeusHttp2Handler             → JEUS 8.5
com.tmaxsoft.jeus.web.connector.JeusConnector8           → JEUS 8
com.tmaxsoft.jeus.web.websocket.JeusWebSocketHandler     → JEUS 8
com.tmaxsoft.jeus.web.deployer.JeusWebDeployer7          → JEUS 7
com.tmaxsoft.jeus.web.async.JeusAsyncContext             → JEUS 7
com.tmaxsoft.jeus.web.JeusWebContainer                  → JEUS 6+
com.tmaxsoft.jeus.web.servlet.JeusServletHandler         → JEUS 6+
com.tmax.jeus.web.servlet.HttpServletWrapper             → JEUS 4/5
com.tmax.jeus.util.engine.ServiceEngine                  → JEUS 4/5
```

---

## 5-A. 전체 WAS 기술 매트릭스 (27개 WasType)

> **이전 섹션 5는 JEUS 중심 매트릭스이며, 이 섹션은 27개 전체 WasType을 망라합니다.**

### 5-A.1 WasType 전체 목록

| WasType | displayName | JDK 요구 | Servlet | EE 표준 | 위빙 엔진 |
|---------|------------|---------|---------|---------|----------|
| `JEUS_LEGACY` | JEUS 4/5 (Legacy, JDK 1.4~1.5) | 1.4~1.5 | 2.3~2.4 | J2EE 1.3~1.4 | Javassist |
| `JEUS_6` | JEUS 6 (JDK 1.5~1.7) | 1.5~1.7 | 2.5 | Java EE 5 | Javassist |
| `JEUS_7` | JEUS 7 (JDK 1.6~1.8) | 1.6~1.8 | 3.0 | Java EE 6 | JDK 분기 |
| `JEUS_8` | JEUS 8 (JDK 1.7~1.8) | 1.7~1.8 | 3.1 | Java EE 7 | JDK 분기 |
| `JEUS_8_5` | JEUS 8.5 (JDK 8/11) | 8/11 | 4.0 | Java EE 8 | byte-buddy |
| `JEUS_9_PLUS` | JEUS 9/21 (JDK 11+, Jakarta EE) | 11+ | 5.0~6.0 | Jakarta EE 9~10 | byte-buddy+jakarta |
| `TOMCAT_LEGACY` | Tomcat 5.x/6.x (JDK 5~6, Servlet 2.4~2.5) | 5~6 | 2.4~2.5 | J2EE 1.4~Java EE 5 | Javassist |
| `TOMCAT_7` | Tomcat 7.x (JDK 7+, Servlet 3.0) | 7+ | 3.0 | Java EE 6 | JDK 분기 |
| `TOMCAT_8` | Tomcat 8.x/8.5 (JDK 8, Servlet 3.1) | 8 | 3.1 | Java EE 7 | byte-buddy |
| `TOMCAT_9` | Tomcat 9.x (JDK 8+, Servlet 4.0) | 8+ | 4.0 | Java EE 8 | byte-buddy |
| `TOMCAT_10_PLUS` | Tomcat 10+/11 (JDK 11+, jakarta.servlet) | 11+ | 5.0~6.1 | Jakarta EE 9~11 | byte-buddy+jakarta |
| `TOMCAT` | Tomcat (버전 미감지, 기본) | 8+ | 3.x~5.x | — | byte-buddy |
| `JBOSS_LEGACY` | JBoss AS 5/6 (JDK 6~7, Legacy) | 6~7 | 2.x~3.0 | J2EE~Java EE 6 | Javassist |
| `JBOSS` | JBoss EAP 6/7 (JDK 8+) | 8+ | 3.x~4.x | Java EE 7~8 | byte-buddy |
| `WILDFLY` | WildFly 27+ (JDK 11+, Jakarta EE) | 11+ | 5.0~6.0 | Jakarta EE 10 | byte-buddy+jakarta |
| `WEBLOGIC_LEGACY` | WebLogic 10.x/11g/12c-early (JDK 6~7) | 6~7 | 2.x~3.0 | Java EE 5~6 | Javassist |
| `WEBLOGIC` | WebLogic 12c(후기)/14c (JDK 8+) | 8+ | 3.x~4.x | Java EE 7~8 | byte-buddy |
| `WEBSPHERE_LEGACY` | WebSphere 7.x/8.x (JDK 6~7) | 6~7 | 2.x~3.0 | Java EE 5~6 | Javassist |
| `WEBSPHERE` | WebSphere Liberty / Open Liberty (JDK 8+) | 8+ | 3.1~6.0 | Java EE 7~Jakarta EE 10 | byte-buddy |
| `GLASSFISH` | GlassFish 3/4 / Payara (JDK 7~8+) | 7~8+ | 3.0~4.0 | Java EE 6~7 | byte-buddy |
| `GLASSFISH_JAKARTA` | GlassFish 6+/Payara 6+ (JDK 11+, Jakarta EE) | 11+ | 5.0~6.0 | Jakarta EE 9~10 | byte-buddy+jakarta |
| `RESIN` | Caucho Resin (JDK 6+) | 6+ | 2.4~3.1 | Java EE 5~7 | byte-buddy |
| `JETTY_LEGACY` | Jetty 7/8 (JDK 7, Servlet 3.0) | 7 | 3.0 | Java EE 6 | Javassist |
| `JETTY` | Jetty 9~11 (JDK 8~11) | 8~11 | 3.1~4.0 | Java EE 7~8 | byte-buddy |
| `JETTY_JAKARTA` | Jetty 12+ (JDK 17+, Jakarta EE 10) | 17+ | 6.0+ | Jakarta EE 10 | byte-buddy+jakarta |
| `UNDERTOW` | Undertow Standalone | 8+ | 3.x~5.x | Java EE 7~Jakarta EE 9 | byte-buddy |
| `UNKNOWN` | Unknown (Generic Fallback) | 8+ | — | — | byte-buddy (javax+jakarta) |

### 5-A.2 WasType 유틸리티 메서드 매트릭스

| WasType | isTomcat() | isJeus() | isJBoss() | isWebLogic() | isWebSphere() | isGlassFish() | isJetty() | isJakartaOnly() | isLegacyJavassist() | needsDualNamespace() |
|---------|:-----------:|:--------:|:---------:|:------------:|:-------------:|:-------------:|:---------:|:---------------:|:--------------------:|:-------------------:|
| `JEUS_LEGACY` | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `JEUS_6` | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `JEUS_7` | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `JEUS_8` | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `JEUS_8_5` | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| `JEUS_9_PLUS` | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| `TOMCAT_LEGACY` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `TOMCAT_7` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `TOMCAT_8` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `TOMCAT_9` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `TOMCAT_10_PLUS` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ |
| `TOMCAT` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `JBOSS_LEGACY` | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `JBOSS` | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `WILDFLY` | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| `WEBLOGIC_LEGACY` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `WEBLOGIC` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `WEBSPHERE_LEGACY` | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| `WEBSPHERE` | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| `GLASSFISH` | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| `GLASSFISH_JAKARTA` | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| `RESIN` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `JETTY_LEGACY` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ❌ |
| `JETTY` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| `JETTY_JAKARTA` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ |
| `UNDERTOW` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| `UNKNOWN` | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### 5-A.3 위빙 전략 매핑 테이블

```
WasType               → WeavingStrategy 구현체              → 위빙 엔진
────────────────────────────────────────────────────────────────────────────────
JEUS_LEGACY           → JeusLegacyWeavingStrategy            → Javassist
JEUS_6                → Jeus6WeavingStrategy                  → Javassist
JEUS_7, JEUS_8        → Jeus7PlusWeavingStrategy              → JDK 7: Javassist / JDK 8+: byte-buddy
JEUS_8_5, JEUS_9_PLUS → Jeus8_5PlusWeavingStrategy            → byte-buddy (javax+jakarta)
TOMCAT_LEGACY         → TomcatVersionedWeavingStrategy        → Javassist (ApplicationFilterChain)
TOMCAT_7              → TomcatVersionedWeavingStrategy        → JDK7: Javassist / JDK8+: byte-buddy
TOMCAT_8, TOMCAT_9    → TomcatVersionedWeavingStrategy        → byte-buddy (Valve + javax.Filter)
TOMCAT_10_PLUS        → TomcatVersionedWeavingStrategy        → byte-buddy (jakarta.Filter 전용)
TOMCAT                → TomcatVersionedWeavingStrategy        → byte-buddy (Valve + 이중 Filter)
JBOSS_LEGACY          → LegacyJavassistWeavingStrategy        → Javassist (javax.Filter)
WEBLOGIC_LEGACY       → LegacyJavassistWeavingStrategy        → Javassist (javax.Filter)
WEBSPHERE_LEGACY      → LegacyJavassistWeavingStrategy        → Javassist (javax.Filter)
JETTY_LEGACY          → LegacyJavassistWeavingStrategy        → Javassist (javax.Filter)
RESIN                 → LegacyJavassistWeavingStrategy        → Javassist (javax.Filter)
JBOSS, WILDFLY        → GenericFilterWeavingStrategy          → byte-buddy (javax+jakarta)
WEBLOGIC              → GenericFilterWeavingStrategy          → byte-buddy (javax+jakarta)
WEBSPHERE             → GenericFilterWeavingStrategy          → byte-buddy (javax+jakarta)
GLASSFISH             → GenericFilterWeavingStrategy          → byte-buddy (javax.Filter)
GLASSFISH_JAKARTA     → GenericFilterWeavingStrategy          → byte-buddy (jakarta.Filter)
JETTY                 → GenericFilterWeavingStrategy          → byte-buddy (javax+jakarta)
JETTY_JAKARTA         → GenericFilterWeavingStrategy          → byte-buddy (jakarta.Filter)
UNDERTOW              → GenericFilterWeavingStrategy          → byte-buddy (javax+jakarta)
UNKNOWN               → GenericFilterWeavingStrategy          → byte-buddy (Fallback)
```

### 5-A.4 javax ↔ jakarta 네임스페이스 전환점

```
Servlet 버전  5.0 (Jakarta EE 9) 이후부터 javax.servlet.* → jakarta.servlet.* 전환

javax.servlet.* 마지막 버전:
  JEUS 8.5       (Servlet 4.0)
  Tomcat 9.x     (Servlet 4.0)
  JBoss EAP 7.4  (Servlet 4.0)
  WebLogic 14c   (Servlet 4.0)
  Jetty 10.x     (Servlet 4.0)

jakarta.servlet.* 시작 버전:
  JEUS 9/21      (Servlet 5.0+)
  Tomcat 10.x+   (Servlet 5.0+)
  WildFly 27+    (Servlet 6.0)
  GlassFish 6+   (Servlet 5.0+)
  Jetty 11~12    (Servlet 5.0~6.0)

이중 지원 (needsDualNamespace=true):
  JEUS 8.5       - Servlet 4.0 이지만 Jakarta EE 8 호환 레이어 포함
  JEUS 9_PLUS    - jakarta 기본, javax 호환 레이어
  TOMCAT_10_PLUS - jakarta 전용 (javax 없음, 단 needsDualNamespace로 표시)
  WEBSPHERE      - Liberty는 javax/jakarta 모두 지원
```

---

## 6. 컴포넌트 설계

### 6.1 패키지 구조

```
kr.go.smes.agent
├── core/
│   └── OnePassAgentMain.java          ← JVM 진입점 (premain/agentmain)
├── config/
│   └── AgentConfig.java               ← 설정 로더/검증기
├── was/
│   ├── WasType.java                   ← WAS 유형 열거형
│   └── WasDetector.java               ← WAS 자동 감지기
├── weaving/
│   ├── WeavingStrategy.java           ← 위빙 전략 인터페이스
│   ├── WeavingStrategyFactory.java    ← 전략 팩토리
│   ├── WeavingListener.java           ← 위빙 이벤트 리스너
│   ├── WeavingInstallException.java   ← 위빙 설치 실패 예외
│   ├── TomcatWeavingStrategy.java     ← Tomcat Catalina Valve 위빙
│   ├── GenericFilterWeavingStrategy.java ← javax/jakarta Filter 위빙 (범용)
│   ├── engine/
│   │   └── JavassistWeavingEngine.java ← Javassist 위빙 엔진 (JDK 1.3+)
│   └── jeus/
│       ├── JeusLegacyWeavingStrategy.java  ← JEUS 4/5 (JDK 1.5 호환)
│       ├── Jeus6WeavingStrategy.java       ← JEUS 6 (Javassist)
│       ├── Jeus7PlusWeavingStrategy.java   ← JEUS 7/8 (JDK 분기)
│       ├── Jeus8_5PlusWeavingStrategy.java ← JEUS 8.5/9/21 (byte-buddy)
│       └── JeusWeavingEngineSelector.java  ← 엔진 선택기
└── http/
    └── OnePassHttpClient.java         ← HTTP 클라이언트 (HttpURLConnection)
```

### 6.2 의존성 그래프

```
OnePassAgentMain
    ├── AgentConfig
    ├── WasDetector ──────────────── WasType
    ├── WeavingStrategyFactory ───── WasType
    │       │
    │       ├── JeusLegacyWeavingStrategy
    │       │       └── JavassistWeavingEngine ← Javassist 3.x
    │       ├── Jeus6WeavingStrategy
    │       │       └── JavassistWeavingEngine
    │       ├── Jeus7PlusWeavingStrategy
    │       │       ├── JeusWeavingEngineSelector ← WasDetector.getRuntimeJdkMajor()
    │       │       ├── JavassistWeavingEngine  (JDK 7-)
    │       │       └── [byte-buddy AgentBuilder] (JDK 8+)
    │       ├── Jeus8_5PlusWeavingStrategy
    │       │       └── [byte-buddy AgentBuilder]
    │       └── TomcatWeavingStrategy / GenericFilterWeavingStrategy
    │               └── [byte-buddy AgentBuilder]
    └── OnePassHttpClient ←──────── AgentConfig
```

### 6.3 핵심 클래스 책임

#### `OnePassAgentMain`
- **역할**: JVM 진입점. `premain()` / `agentmain()` 수신 후 초기화 흐름 조율
- **설계**: `doInstall()` 공통 메서드로 추출 (테스트 가능성 확보)
- **에러 정책**: 설정 오류 → Agent 비활성화 / 위빙 오류 → 경고 후 계속

#### `AgentConfig`
- **역할**: 외부 설정 파일 / `-D` 옵션 로드 및 검증
- **우선순위**: agentArgs 파일 경로 > `-Donepass.agent.config=` > classpath 기본값 > `-D` 개별 키
- **검증**: endpoint 형식(http/https), 양수 타임아웃, 필수 키 존재

#### `WasDetector`
- **역할**: 클래스패스 탐색 / 시스템 프로퍼티 / 환경변수로 WAS 유형 자동 감지
- **설계**: 멱등(idempotent), 캐싱 없음, 예외 없이 `UNKNOWN` 반환
- **JEUS 특화**: 버전별 고유 클래스로 정밀 버전 판별 (JEUS 9 → JEUS 4/5 역순)

#### `WeavingStrategyFactory`
- **역할**: WasType → WeavingStrategy 구현체 1:1 매핑
- **설계**: switch-case, 항상 non-null 반환, JEUS를 최우선 처리

#### `JavassistWeavingEngine`
- **역할**: JDK 1.3+ 호환 바이트코드 위빙 엔진
- **설계**: ClassLoader별 ClassPool 관리, frozen CtClass detach (메모리 누수 방지)
- **안전성**: 위빙 실패 시 `null` 반환 → JVM 원본 바이트코드 사용

#### `OnePassHttpClient`
- **역할**: OnePass 서버 토큰 검증 API 호출
- **설계**: HttpURLConnection 전용, 재시도(지수 백오프), HMAC-SHA256 서명
- **JDK 호환**: JDK 1.1+ 호환 (순수 JDK API)

---

## 7. 위빙(Weaving) 엔진 설계

### 7.1 Javassist 위빙 흐름 (JDK 1.5 호환)

```
premain() 호출
    │
    ▼
JavassistWeavingEngine.install()
    │  Instrumentation.addTransformer(JeusLegacyTransformer, canRetransform=true)
    │
JVM 클래스 로딩 시점 (클래스 최초 로드)
    │
    ▼
JeusLegacyTransformer.transform(ClassLoader, className, classfileBuffer)
    │
    ├─ className == "com/tmax/jeus/web/servlet/HttpServletWrapper" ?
    │       ├─ Yes: ClassPool으로 CtClass 생성
    │       │         CtMethod#service 획득
    │       │         CtMethod.insertBefore(SSO_VERIFICATION_CODE_STRING)
    │       │         CtClass.toBytecode() → 변환된 바이트코드 반환
    │       └─ No: null 반환 (원본 바이트코드 유지)
    │
    ▼
JVM이 변환된 바이트코드로 클래스 정의
```

### 7.2 Javassist 삽입 코드 구조

```java
// Javassist가 service() 메서드 시작 직전에 삽입하는 코드 (JDK 1.4 호환 소스 문자열)
"{" +
  "String _enabled = System.getProperty(\"onepass.agent.enabled\", \"true\");" +
  "if (\"true\".equalsIgnoreCase(_enabled)) {" +
  "  javax.servlet.http.HttpServletRequest _req = " +
  "      (javax.servlet.http.HttpServletRequest)$1;" +
  "  String _token = _req.getHeader(\"X-OnePass-Token\");" +
  "  String _endpoint = System.getProperty(\"onepass.agent.endpoint\");" +
  "  String _apiKey   = System.getProperty(\"onepass.agent.api-key\");" +
  "  // HttpURLConnection으로 OnePass 검증 API 호출" +
  "  java.net.URL _url = new java.net.URL(_endpoint + \"/api/v1/agent/verify\");" +
  "  java.net.HttpURLConnection _conn = " +
  "      (java.net.HttpURLConnection)_url.openConnection();" +
  "  _conn.setRequestMethod(\"POST\");" +
  "  _conn.setRequestProperty(\"X-OnePass-Api-Key\", _apiKey);" +
  "  _conn.setRequestProperty(\"X-OnePass-Token\", _token != null ? _token : \"\");" +
  "  _conn.setConnectTimeout(5000);" +
  "  _conn.setReadTimeout(10000);" +
  "  _conn.connect();" +
  "  int _status = _conn.getResponseCode();" +
  "  if (_status == 401 || _status == 403) {" +
  "    ((javax.servlet.http.HttpServletResponse)$2).sendError(401, \"Unauthorized\");" +
  "    return;" +
  "  }" +
  "}" +
"}"
```

### 7.3 byte-buddy 위빙 흐름 (JDK 8+)

```java
// JEUS 7/8/8.5/9+ — byte-buddy AgentBuilder 방식
new AgentBuilder.Default()
    .type(named("javax.servlet.Filter")
        .or(named("jakarta.servlet.Filter")))
    .transform((builder, type, classLoader, module, protectionDomain) ->
        builder.method(named("doFilter"))
               .intercept(Advice.to(OnePassFilterAdvice.class))
    )
    .installOn(instrumentation);

// Advice 클래스
public class OnePassFilterAdvice {
    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(0) Object request,
            @Advice.Argument(1) Object response) {
        // 토큰 검증 로직
    }
}
```

### 7.4 엔진 선택 결정 트리

```
WasType + getRuntimeJdkMajor()
         │
         ├─ JEUS_LEGACY (JDK 4~5)  ────────────────── → Javassist
         │
         ├─ JEUS_6 (JDK 5~7)       ────────────────── → Javassist (통일)
         │
         ├─ JEUS_7 / JEUS_8
         │       ├─ JDK ≥ 8        ────────────────── → byte-buddy
         │       └─ JDK < 8        ────────────────── → Javassist (폴백)
         │
         ├─ JEUS_8_5                ────────────────── → byte-buddy
         │
         ├─ JEUS_9_PLUS             ────────────────── → byte-buddy (jakarta 이중)
         │
         └─ Tomcat/JBoss/WebLogic/기타 ────────────── → byte-buddy
```

---

## 8. 초기화 흐름 (premain 라이프사이클)

```
JVM 기동
  │
  ▼  (main() 이전 JVM이 자동 호출)
OnePassAgentMain.premain(agentArgs, inst)
  │
  ├─ 배너 출력 (버전 정보)
  │
  ├─ Instrumentation null 체크
  │   └─ null이면 즉시 반환 (JVM 계속 기동)
  │
  ├─ AgentConfig.load(agentArgs)
  │   ├─ agentArgs 파싱 → config= 경로 추출
  │   ├─ 파일 로드 (없으면 classpath 기본값)
  │   ├─ 시스템 프로퍼티 오버라이드 (-D 옵션)
  │   └─ 검증: endpoint, apiKey 필수 확인
  │       └─ 실패: AgentConfigException → Agent 비활성화, WAS 기동 계속
  │
  ├─ enabled 체크
  │   └─ false이면 즉시 반환 (JVM 계속 기동)
  │
  ├─ WasDetector.detect()
  │   ├─ 1. onepass.was.type 오버라이드
  │   ├─ 2. 클래스패스 탐색 (JEUS 9+ → 4/5 역순)
  │   ├─ 3. 시스템 프로퍼티 (jeus.home, jeus.version 등)
  │   ├─ 4. 환경변수 (JEUS_HOME, CATALINA_HOME 등)
  │   └─ 5. UNKNOWN (Fallback)
  │
  ├─ WeavingStrategyFactory.create(wasType, config)
  │   └─ WasType → Strategy 구현체 생성
  │
  ├─ strategy.install(inst)
  │   ├─ JeusLegacyWeavingStrategy:
  │   │   ├─ System.setProperty(endpoint, apiKey)
  │   │   └─ JavassistWeavingEngine.install() → Transformer 등록
  │   ├─ Jeus7PlusWeavingStrategy:
  │   │   └─ JeusWeavingEngineSelector.select() → Javassist or byte-buddy
  │   └─ Jeus8_5PlusWeavingStrategy:
  │       └─ byte-buddy AgentBuilder.installOn(inst)
  │   └─ 실패: WeavingInstallException → WARN 로그, WAS 기동 계속
  │
  └─ HealthCheck 데몬 스레드 시작 (30초 후 OnePass 서버 연결 확인)

WAS main() 기동 시작
  │
  ▼
HTTP 요청 처리 (위빙된 코드 실행)
```

---

## 9. 설정 체계

### 9.1 설정 우선순위

```
높음  [1] agentArgs 파일 경로
         -javaagent:agent.jar=config=/etc/onepass/agent.properties
      [2] 시스템 프로퍼티 — config 파일 경로
         -Donepass.agent.config=/etc/onepass/agent.properties
      [3] 클래스패스 기본 파일
         classpath:onepass-agent.properties
      [4] 시스템 프로퍼티 — 개별 키 오버라이드
         -Donepass.agent.endpoint=https://...
낮음
```

### 9.2 설정 키 전체 목록

| 키 | 필수 | 기본값 | 설명 |
|----|------|--------|------|
| `onepass.agent.endpoint` | **필수** | - | OnePass 인증 서버 Base URL |
| `onepass.agent.api-key` | **필수** | - | API 인증 키 (기관 발급) |
| `onepass.agent.hmac-secret` | 선택 | null | HMAC-SHA256 서명 시크릿 |
| `onepass.agent.connect-timeout-ms` | 선택 | 5000 | HTTP 연결 타임아웃 (ms) |
| `onepass.agent.read-timeout-ms` | 선택 | 10000 | HTTP 읽기 타임아웃 (ms) |
| `onepass.agent.max-retry` | 선택 | 2 | HTTP 재시도 횟수 |
| `onepass.agent.enabled` | 선택 | true | Agent 활성화 여부 |
| `onepass.agent.log-level` | 선택 | INFO | 로그 수준 (INFO/WARN/ERROR) |
| `onepass.was.type` | 선택 | 자동감지 | WAS 유형 강제 지정 |

### 9.3 WAS 타입 오버라이드 값 목록

```
-Donepass.was.type=JEUS_LEGACY    # JEUS 4/5
-Donepass.was.type=JEUS_6         # JEUS 6
-Donepass.was.type=JEUS_7         # JEUS 7
-Donepass.was.type=JEUS_8         # JEUS 8
-Donepass.was.type=JEUS_8_5       # JEUS 8.5
-Donepass.was.type=JEUS_9_PLUS    # JEUS 9/21
-Donepass.was.type=TOMCAT         # Apache Tomcat
-Donepass.was.type=JBOSS          # JBoss/WildFly
-Donepass.was.type=WEBLOGIC       # Oracle WebLogic
-Donepass.was.type=UNKNOWN        # Generic Filter (Fallback)
```

---

## 10. 보안 설계

### 10.1 API 인증 헤더 구조

```
POST /api/v1/agent/verify HTTP/1.1
X-OnePass-Api-Key: {apiKey}
X-OnePass-Timestamp: {epochSeconds}
X-OnePass-Signature: {HMAC-SHA256(apiKey:timestamp:requestBody)}
X-OnePass-Agent-Version: 1.0.0
Content-Type: application/json;charset=UTF-8

{"token": "{X-OnePass-Token 헤더 값}"}
```

### 10.2 HMAC-SHA256 서명 (선택)

- `hmac-secret` 설정 시 모든 요청에 `X-OnePass-Signature` 헤더 자동 추가
- 서명 입력: `apiKey:epochSeconds:requestBody` (UTF-8 바이트)
- `javax.crypto.Mac` (JDK 1.4+) — JDK 1.5 환경에서도 동작

### 10.3 민감 정보 보호

- 로그 출력 시 `apiKey`, `hmacSecret`은 앞 4자리 + `****` 마스킹
- `onepass-agent.properties` 파일 권한: `600` (읽기 전용, 소유자만)
- 환경변수로 전달 시 JVM 프로세스 목록에 노출되지 않도록 파일 경로 방식 권장

---

## 11. 에러 처리 철학

### 11.1 "WAS 기동을 막지 않는다" 원칙

```
에러 유형                    처리 방법
─────────────────────────────────────────────────────────
필수 설정 누락               Agent 비활성화 + WAS 기동 계속
WAS 감지 실패                UNKNOWN 반환 + Generic Filter 위빙 시도
위빙 Transformer 등록 실패   WARN 로그 + WAS 기동 계속 (SSO 미동작)
위빙 중 바이트코드 변환 실패  null 반환 (원본 바이트코드) + WAS 계속
OnePass 서버 연결 실패       WARN 로그 (비동기) + 요청 단계에서 재시도
토큰 검증 타임아웃            설정 타임아웃 후 실패 처리 (agentfail-open 정책)
```

### 11.2 Fail-Open vs Fail-Closed

현재 구현: **Fail-Open** (검증 실패 시 요청 통과)

> 보안 정책에 따라 Fail-Closed(검증 실패 시 차단)로 변경 가능.
> `onepass.agent.fail-closed=true` 설정 추가 예정 (Sprint 21).

---

## 12. 패키징 및 배포 아키텍처

### 12.1 fat-JAR 구조

```
idem-agent-1.0.0-all.jar
├── META-INF/MANIFEST.MF
│   ├── Premain-Class: kr.go.smes.agent.core.OnePassAgentMain
│   ├── Agent-Class:   kr.go.smes.agent.core.OnePassAgentMain
│   ├── Can-Redefine-Classes: true
│   └── Can-Retransform-Classes: true
│
├── kr/go/smes/agent/**         ← Agent 클래스
├── net/bytebuddy/**            ← byte-buddy (JDK 8+ 환경용)
└── javassist/**                ← Javassist (JDK 1.5 환경용)
```

**파일 크기**: ~9.7MB (byte-buddy 7.x MB + Javassist 1.5 MB + Agent 코드)

### 12.2 배포 디렉토리 구조

```
/opt/onepass/
├── idem-agent-1.0.0-all.jar    ← Agent JAR
└── conf/
    └── onepass-agent.properties   ← 설정 파일 (권한 600)
```

### 12.3 JVM 기동 옵션 추가 방식

**JEUS 4/5 (jeusadmin / jeusboot.properties)**:
```
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/onepass/idem-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties"
```

**Tomcat (catalina.sh / setenv.sh)**:
```bash
CATALINA_OPTS="$CATALINA_OPTS -javaagent:/opt/onepass/idem-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties"
```

---

## 13. 클래스로더 격리 전략

### 13.1 문제: WAS 클래스로더 계층

```
Bootstrap ClassLoader
    └── System ClassLoader
            └── WAS ClassLoader (JEUS/Tomcat 자체 클래스)
                    └── App ClassLoader (기관 웹 애플리케이션)
```

Agent JAR은 System ClassLoader에 위치하므로, WAS 전용 클래스(예: `com.tmax.jeus.*`)에 직접 접근하기 어렵다.

### 13.2 Javassist ClassPool 전략

```java
// Javassist는 위빙 대상 ClassLoader를 ClassPool에 추가하여 해결
ClassPool pool = ClassPool.getDefault();
pool.appendClassPath(new LoaderClassPath(targetClassLoader));
CtClass ctClass = pool.makeClass(classfileBuffer_InputStream);
```

### 13.3 byte-buddy 패키지 Relocation

운영 배포 시 maven-shade-plugin으로 패키지 재배치:
```
net.bytebuddy.** → kr.go.smes.agent.shaded.bytebuddy.**
javassist.**    → kr.go.smes.agent.shaded.javassist.**
```

> 현재 개발 빌드(build.gradle.kts)에서는 원래 패키지로 번들링.  
> 유관기관 WAS에 byte-buddy / Javassist가 없는 경우(대부분) 충돌 없이 동작.

---

## 14. 성능 고려사항

### 14.1 premain 단계 오버헤드

| 작업 | 소요 시간 | 영향 |
|------|---------|------|
| 설정 파일 로드 | ~5ms | 무시 가능 |
| WAS 감지 (클래스패스 탐색) | ~10~50ms | 무시 가능 |
| Javassist Transformer 등록 | ~1ms | 무시 가능 |
| byte-buddy AgentBuilder 빌드 | ~100~300ms | WAS 기동 지연 (허용 범위) |
| 비동기 헬스체크 | 0ms (daemon thread) | 없음 |

### 14.2 HTTP 요청 단계 오버헤드

각 HTTP 요청마다 OnePass 서버 검증 API 호출:
- 추가 레이턴시: 네트워크 RTT (내부망 기준 ~1~5ms)
- 타임아웃 설정: connectTimeout=5000ms, readTimeout=10000ms
- 재시도: 5xx / IOException 시 지수 백오프

> **최적화 방안 (Sprint 21 예정)**: 검증 결과 캐시 (TTL 기반) 추가

### 14.3 메모리 오버헤드

- byte-buddy AgentBuilder: ~10MB JVM heap (클래스 변환 캐시)
- Javassist ClassPool: ClassLoader별 ~1~5MB
- 전체 Agent 추가 메모리: ~20~50MB (허용 범위)

---

## 15. 테스트 전략

### 15.1 테스트 분류

| 테스트 유형 | 범위 | 도구 |
|-----------|------|------|
| Unit Test | 클래스 단위, Mock 사용 | JUnit 5, Mockito |
| Integration Test | WAS 클래스 없이 Instrumentation Mock | JUnit 5 |
| Build Verification | fat-JAR 생성, MANIFEST 확인 | Gradle |

### 15.2 JEUS 관련 테스트 현황

| 테스트 파일 | 테스트 수 | 검증 내용 |
|-----------|---------|---------|
| `WasDetectorTest` | 확장 | 버전 파싱, 시스템 프로퍼티 감지, 경로 파싱, JDK 버전 파싱 |
| `JeusWeavingEngineSelectorTest` | 32개 | WasType × JDK 버전 모든 조합 검증 |
| `WeavingStrategyFactoryJeusTest` | 신규 | JEUS 버전별 전략 클래스 매핑 + 비-JEUS 회귀 |

**전체 테스트**: 1,207개 통과 (0 failures) — 2026-05-17 기준

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 | 작성자 |
|------|------|---------|--------|
| v1.0.0 | 2026-05-17 | 최초 작성 — JEUS 버전별 위빙 전략 + JDK 1.5 SSO 분석 | OnePass 팀 |
