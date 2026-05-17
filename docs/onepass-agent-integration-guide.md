# OnePass Agency Java Agent — 유관기관 개발자 통합 가이드

> **문서 번호**: AGENT-GUIDE-001  
> **문서 버전**: v1.1.0  
> **작성일**: 2026-05-17  
> **최종 수정**: 2026-05-17 — Tomcat 버전별 상세 가이드, WebSphere/WebLogic 설치 가이드 추가  
> **대상 독자**: 유관기관 개발자 / 시스템 관리자 / 기술 담당자  
> **사전 조건**: OnePass 행정안전부 담당자로부터 API Key를 발급받은 상태

---

## 목차

1. [OnePass Agent란 무엇인가?](#1-onepass-agent란-무엇인가)
2. [지원 환경 및 호환성 매트릭스](#2-지원-환경-및-호환성-매트릭스)
3. [사전 준비](#3-사전-준비)
4. [JEUS 버전별 설치 가이드](#4-jeus-버전별-설치-가이드)
   - 4.1 [JEUS 4/5 (JDK 1.5 레거시 환경)](#41-jeus-45-jdk-15-레거시-환경)
   - 4.2 [JEUS 6 (JDK 1.5~1.7)](#42-jeus-6-jdk-15-17)
   - 4.3 [JEUS 7/8 (JDK 1.6~1.8)](#43-jeus-78-jdk-16-18)
   - 4.4 [JEUS 8.5 (JDK 8/11)](#44-jeus-85-jdk-811)
   - 4.5 [JEUS 9/21 (JDK 11+, Jakarta EE)](#45-jeus-921-jdk-11-jakarta-ee)
5. [Tomcat 버전별 설치 가이드](#5-tomcat-버전별-설치-가이드)
   - 5.1 [Tomcat 5.x/6.x (JDK 5~6, 레거시)](#51-tomcat-5x6x-jdk-5~6-레거시)
   - 5.2 [Tomcat 7.x (JDK 7, Servlet 3.0)](#52-tomcat-7x-jdk-7-servlet-30)
   - 5.3 [Tomcat 8.x/8.5 (JDK 8, Servlet 3.1)](#53-tomcat-8x85-jdk-8-servlet-31)
   - 5.4 [Tomcat 9.x (JDK 8+, Servlet 4.0)](#54-tomcat-9x-jdk-8-servlet-40)
   - 5.5 [Tomcat 10+/11 (JDK 11+, Jakarta EE)](#55-tomcat-1011-jdk-11-jakarta-ee)
   - 5.6 [Spring Boot Embedded Tomcat](#56-spring-boot-embedded-tomcat)
6. [JBoss/WildFly 설치 가이드](#6-jbosswildfly-설치-가이드)
7. [Oracle WebLogic 설치 가이드](#7-oracle-weblogic-설치-가이드)
8. [IBM WebSphere 설치 가이드](#8-ibm-websphere-설치-가이드)
9. [설정 파일 전체 옵션](#9-설정-파일-전체-옵션)
10. [동작 검증 방법](#10-동작-검증-방법)
11. [SSO 흐름 이해](#11-sso-흐름-이해)
12. [보안 요구사항](#12-보안-요구사항)
13. [운영 중 설정 변경](#13-운영-중-설정-변경)
14. [제거(Uninstall)](#14-제거uninstall)
15. [FAQ](#15-faq)

---

## 1. OnePass Agent란 무엇인가?

**OnePass Agency Java Agent**는 유관기관 WAS(Web Application Server)에 **소스 코드 수정 없이** OnePass 통합인증(SSO)을 적용하는 자바 에이전트입니다.

### 1.1 동작 원리

```
사용자 브라우저
      │
      │ HTTP 요청 (X-OnePass-Token 헤더 포함)
      ▼
유관기관 WAS (JEUS / Tomcat 등)
      │
      │ ← [Agent가 투명하게 삽입한 코드]
      │   X-OnePass-Token 헤더 추출
      │   OnePass 서버 검증 API 호출 (내부망)
      │   검증 실패 → HTTP 401 반환
      │   검증 성공 → 아래 계속 진행
      │
      ▼
기관 업무 로직 (변경 없음)
```

### 1.2 핵심 특징

| 특징 | 설명 |
|------|------|
| **코드 수정 없음** | 유관기관 업무 코드 수정 불필요 |
| **JDK 1.5부터 지원** | JEUS 4/5 레거시 환경에서도 SSO 가능 |
| **단일 JAR** | JEUS 4~21 전 버전 + Tomcat 등 하나의 JAR로 지원 |
| **즉시 비활성화** | `onepass.agent.enabled=false` 설정 → 재시작 시 즉시 비활성화 |
| **서비스 안전** | Agent 오류 발생 시 WAS 기동을 막지 않음 |

---

## 2. 지원 환경 및 호환성 매트릭스

### 2.1 JEUS 버전별 지원 현황

| JEUS 버전 | JDK 버전 | Servlet | 지원 여부 | 위빙 방식 |
|-----------|---------|---------|---------|---------|
| JEUS 4 | JDK 1.4~1.5 | 2.3 | ✅ **지원** | Javassist (정적 어태치만 가능) |
| JEUS 5 | JDK 1.4~1.5 | 2.4 | ✅ **지원** | Javassist (정적 어태치만 가능) |
| JEUS 6 | JDK 1.5~1.7 | 2.5 | ✅ **지원** | Javassist |
| JEUS 7 | JDK 1.6~1.8 | 3.0 | ✅ **지원** | JDK 버전 자동 선택 |
| JEUS 8 | JDK 1.7~1.8 | 3.1 | ✅ **지원** | JDK 버전 자동 선택 |
| JEUS 8.5 | JDK 8 / 11 | 4.0 | ✅ **지원** | byte-buddy |
| JEUS 9 | JDK 11+ | 5.0 | ✅ **지원** | byte-buddy (Jakarta) |
| JEUS 21 | JDK 21+ | 6.0 | ✅ **지원** | byte-buddy (Jakarta) |

### 2.2 Tomcat 버전별 지원 현황

| Tomcat 버전 | JDK 요구 | Servlet | 위빙 방식 | 위빙 포인트 |
|------------|---------|---------|----------|-----------|
| Tomcat 5.x | JDK 5~6 | 2.4 | Javassist | `ApplicationFilterChain.internalDoFilter()` |
| Tomcat 6.x | JDK 5~6 | 2.5 | Javassist | `ApplicationFilterChain.internalDoFilter()` |
| Tomcat 7.x | JDK 7+ | 3.0 | JDK 7: Javassist / JDK 8+: byte-buddy | `StandardContextValve.invoke()` |
| Tomcat 8.x/8.5 | JDK 8+ | 3.1 | byte-buddy | `StandardHostValve.invoke()` + javax.Filter |
| Tomcat 9.x | JDK 8+ | 4.0 | byte-buddy | `StandardHostValve.invoke()` + javax.Filter |
| Tomcat 10.x | JDK 11+ | 5.0 | byte-buddy | `jakarta.servlet.Filter.doFilter()` |
| Tomcat 10.1+ | JDK 11+ | 6.0 | byte-buddy | `jakarta.servlet.Filter.doFilter()` |
| Tomcat 11 | JDK 21+ | 6.1 | byte-buddy | `jakarta.servlet.Filter.doFilter()` |

> **⚠️ Tomcat 10+ 주의사항**: `javax.servlet.*`이 완전히 제거됩니다. Agent가 `jakarta.servlet.Filter`만 위빙합니다. 이전 Tomcat 9용 설정을 그대로 사용해도 Agent 자체는 자동 감지로 올바른 위빙을 적용합니다.

### 2.3 기타 WAS 지원 현황

| WAS | 버전 | JDK 요구 | 위빙 방식 | 특이사항 |
|-----|------|---------|----------|---------|
| JBoss AS | 5/6 (레거시) | JDK 6~7 | Javassist | `jboss-web` 클래스로더 계층 주의 |
| JBoss EAP | 7.x | JDK 8+ | byte-buddy | Undertow 내장 |
| WildFly | 27+ | JDK 11+ | byte-buddy (jakarta) | Jakarta EE 10 |
| WebLogic | 10.x/11g/12c 초기 | JDK 6~7 | Javassist | FilteringClassLoader 주의 |
| WebLogic | 12c(후기)/14c | JDK 8+ | byte-buddy | FilteringClassLoader 주의 |
| WebSphere | 7.x/8.x | JDK 6~7 | Javassist | IBM J9 JVM 특화 주의 |
| WebSphere Liberty | Liberty/Open | JDK 8+ | byte-buddy | OSGi 번들 ClassLoader |
| GlassFish | 3/4, Payara 5 | JDK 7~8+ | byte-buddy | Grizzly NIO 기반 |
| GlassFish | 6+, Payara 6+ | JDK 11+ | byte-buddy (jakarta) | — |
| Resin | 3/4 | JDK 6+ | byte-buddy | 공공기관 간혹 사용 |
| Jetty | 7/8 | JDK 7 | Javassist | `org.mortbay.jetty` 패키지 |
| Jetty | 9~11 | JDK 8~11 | byte-buddy | — |
| Jetty | 12+ | JDK 17+ | byte-buddy (jakarta) | — |
| Undertow | Standalone | JDK 8+ | byte-buddy | JBoss/WildFly 내장과 구분 |

### 2.3 JEUS 4/5 (JDK 1.5) 제약사항

> **중요**: JEUS 4/5를 사용하는 JDK 1.5 환경에서는 아래 제약이 있습니다.

| 기능 | 가능 여부 | 이유 |
|------|---------|------|
| `-javaagent:` 정적 어태치 | ✅ 가능 | JSR-163, JDK 1.5 도입 |
| 동적 어태치 (운영 중 주입) | ❌ 불가 | Attach API는 JDK 1.6+ |
| byte-buddy 위빙 엔진 | ❌ 불가 | byte-buddy는 JDK 8+ 필요 |
| Javassist 위빙 엔진 | ✅ 가능 | JDK 1.3+ 호환 |
| HTTP 통신 (검증 API) | ✅ 가능 | HttpURLConnection (JDK 1.1+) |
| HMAC-SHA256 서명 | ✅ 가능 | javax.crypto.Mac (JDK 1.4+) |

---

## 3. 사전 준비

### 3.1 필수 정보 확인

OnePass 행정안전부 담당자로부터 다음 정보를 사전에 발급받아야 합니다:

```
✅ OnePass 서버 URL: https://onepass.go.kr  (내부망 IP일 수 있음)
✅ API Key: 기관별 고유 키 (예: op-agency-{기관코드}-{랜덤})
✅ (선택) HMAC Secret: 요청 서명용 시크릿 키
```

### 3.2 네트워크 방화벽 확인

유관기관 WAS 서버에서 OnePass 서버로의 HTTP 통신이 허용되어야 합니다:

```
유관기관 WAS 서버 (아웃바운드)
    → OnePass 서버 IP:포트 (TCP)
    방향: 단방향 아웃바운드
    포트: 443 (HTTPS) 또는 담당자가 지정한 포트
```

### 3.3 Agent JAR 파일 확보

```bash
# 파일명 확인
ls -la onepass-agent-1.0.0-all.jar
# 예상 크기: ~9.7MB

# 배포 디렉토리 생성 및 배치
mkdir -p /opt/onepass/conf
cp onepass-agent-1.0.0-all.jar /opt/onepass/

# 설정 파일 생성 (아래 4절 참조)
```

---

## 4. JEUS 버전별 설치 가이드

### 4.1 JEUS 4/5 (JDK 1.5 레거시 환경)

> **⚠️ 중요**: JEUS 4/5는 JDK 1.5 환경입니다. **반드시 정적 어태치** (`-javaagent:` 플래그)만 사용하세요.  
> 동적 어태치(운영 중 주입)는 JDK 1.6+에서만 가능합니다.

#### Step 1: 설정 파일 생성

```bash
cat > /opt/onepass/conf/onepass-agent.properties << 'EOF'
# OnePass Agent 설정 — JEUS 4/5 (JDK 1.5 환경)
# =============================================

# [필수] OnePass 인증 서버 URL
onepass.agent.endpoint=https://onepass.go.kr

# [필수] 기관 API Key (행정안전부 담당자로부터 발급)
onepass.agent.api-key=op-agency-YOUR_AGENCY_CODE-YOUR_KEY

# [선택] HMAC 서명 시크릿 (보안 강화 옵션)
# onepass.agent.hmac-secret=YOUR_HMAC_SECRET

# [선택] HTTP 타임아웃 설정
onepass.agent.connect-timeout-ms=5000
onepass.agent.read-timeout-ms=10000
onepass.agent.max-retry=2

# [선택] JEUS 4/5 명시적 지정 (자동 감지 실패 시)
# -Donepass.was.type=JEUS_LEGACY 로도 지정 가능
EOF

# 설정 파일 권한 보안 설정 (소유자만 읽기)
chmod 600 /opt/onepass/conf/onepass-agent.properties
```

#### Step 2: JEUS 기동 스크립트에 JVM 옵션 추가

JEUS 4/5는 버전에 따라 기동 스크립트 위치가 다릅니다.

**방법 A: `DAS_OPTS` 환경변수 사용 (권장)**
```bash
# /etc/profile.d/jeus-agent.sh 또는 jeus 기동 스크립트 상단에 추가
export DAS_OPTS="$DAS_OPTS -javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties"
```

**방법 B: JEUS 도메인 설정 파일 수정**
```xml
<!-- domain.xml (또는 jeusboot.properties) -->
<domain>
  <server-group>
    <server-config>
      <jvm-config>
        <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
        <!-- JEUS 4/5가 WAS 유형으로 자동 감지 안 될 경우 명시적 지정 -->
        <jvm-option>-Donepass.was.type=JEUS_LEGACY</jvm-option>
      </jvm-config>
    </server-config>
  </server-group>
</domain>
```

**방법 C: jeusadmin CLI**
```
jeusadmin> modify JeusServer -jvmopts "-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties"
```

#### Step 3: JEUS 재시작

```bash
# JEUS 중지
jeus_stop.sh
# 또는
jeusadmin -host localhost stop DomainServer

# JEUS 기동
jeus_start.sh
# 또는  
jeusadmin -host localhost startDAS
```

#### Step 4: 기동 로그 확인

```bash
# JEUS 로그에서 Agent 초기화 확인
grep -i "OnePassAgent" $JEUS_HOME/logs/JeusServer.log

# 정상 초기화 시 출력 예시:
# ╔══════════════════════════════════════════════════════════╗
# ║     OnePass Agency Java Agent v1.0.0 — Starting         ║
# ╚══════════════════════════════════════════════════════════╝
# [OnePassAgent] WAS 유형 감지: JEUS 4/5 (Legacy, JDK 1.4~1.5)
# [OnePassAgent] 위빙 설치 완료: JeusLegacyWeaving (JEUS 4/5, Javassist)
# [OnePassAgent] 초기화 완료. WAS=JEUS 4/5 (Legacy, JDK 1.4~1.5)
```

---

### 4.2 JEUS 6 (JDK 1.5~1.7)

#### Step 1: 설정 파일 생성

```properties
# /opt/onepass/conf/onepass-agent.properties
onepass.agent.endpoint=https://onepass.go.kr
onepass.agent.api-key=op-agency-YOUR_AGENCY_CODE-YOUR_KEY
onepass.agent.connect-timeout-ms=5000
onepass.agent.read-timeout-ms=10000
```

#### Step 2: JEUS 6 도메인 설정

JEUS 6은 도메인 구조를 사용합니다. `domain.xml`에서 JVM 옵션을 추가합니다:

```xml
<!-- $JEUS_HOME/domains/jeusdomain/config/domain.xml -->
<domain>
  <servers>
    <server>
      <name>server1</name>
      <jvm-config>
        <jvm-option>
          -javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties
        </jvm-option>
      </jvm-config>
    </server>
  </servers>
</domain>
```

#### Step 3: 재시작 및 확인

```bash
# 도메인 관리 서버 재시작
$JEUS_HOME/bin/stopDomainAdminServer
$JEUS_HOME/bin/startDomainAdminServer

# 관리형 서버 재시작
$JEUS_HOME/bin/startManagedServer -server server1

# 로그 확인
grep "OnePassAgent" $JEUS_HOME/domains/jeusdomain/servers/server1/logs/JeusServer.log
# 기대 출력:
# [OnePassAgent] WAS 유형 감지: JEUS 6 (JDK 1.5~1.7)
# [OnePassAgent] 위빙 설치 완료: Jeus6Weaving (JEUS 6, Javassist)
```

---

### 4.3 JEUS 7/8 (JDK 1.6~1.8)

JEUS 7/8은 **런타임 JDK 버전을 자동 감지**하여 최적 위빙 엔진을 선택합니다:
- JDK 1.6~1.7 환경: Javassist 위빙
- JDK 1.8 환경: byte-buddy 위빙

#### Step 1: 설정 파일 생성

```properties
# /opt/onepass/conf/onepass-agent.properties
onepass.agent.endpoint=https://onepass.go.kr
onepass.agent.api-key=op-agency-YOUR_AGENCY_CODE-YOUR_KEY
onepass.agent.hmac-secret=YOUR_HMAC_SECRET
onepass.agent.connect-timeout-ms=5000
onepass.agent.read-timeout-ms=10000
onepass.agent.max-retry=2
```

#### Step 2: JEUS 7/8 도메인 설정

```xml
<!-- $JEUS_HOME/domains/jeusdomain/config/domain.xml -->
<domain>
  <servers>
    <server>
      <name>server1</name>
      <jvm-config>
        <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
        <!-- JDK 1.7 환경에서 byte-buddy 오류 시 강제 Javassist 지정 -->
        <!-- <jvm-option>-Donepass.was.type=JEUS_7</jvm-option> -->
      </jvm-config>
    </server>
  </servers>
</domain>
```

#### Step 3: 재시작 및 확인

```bash
# JEUS 도메인 재시작
$JEUS_HOME/bin/stopDomainAdminServer && $JEUS_HOME/bin/startDomainAdminServer

# 로그에서 JDK 기반 엔진 선택 확인
grep -E "EngineSelector|위빙 설치" $JEUS_HOME/domains/jeusdomain/servers/server1/logs/JeusServer.log

# JDK 8 환경 예시 출력:
# [EngineSelector] JEUS_7 + JDK 8 → BYTE_BUDDY
# [OnePassAgent] 위빙 설치 완료: Jeus7PlusWeaving (JEUS 7, byte-buddy)

# JDK 7 환경 예시 출력:
# [EngineSelector] JEUS_7 + JDK 7 → JAVASSIST (JDK 8 미만)
# [OnePassAgent] 위빙 설치 완료: Jeus7PlusWeaving (JEUS 7, Javassist폴백)
```

---

### 4.4 JEUS 8.5 (JDK 8/11)

JEUS 8.5는 HTTP/2와 Servlet 4.0을 지원하며, byte-buddy 위빙을 사용합니다.

#### Step 1: 설정 파일

```properties
# /opt/onepass/conf/onepass-agent.properties
onepass.agent.endpoint=https://onepass.go.kr
onepass.agent.api-key=op-agency-YOUR_AGENCY_CODE-YOUR_KEY
onepass.agent.hmac-secret=YOUR_HMAC_SECRET
```

#### Step 2: JEUS 8.5 JVM 설정

```xml
<jvm-config>
  <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
  <!-- JDK 11에서 동적 에이전트 로딩 경고 억제 -->
  <jvm-option>-XX:+EnableDynamicAgentLoading</jvm-option>
</jvm-config>
```

---

### 4.5 JEUS 9/21 (JDK 11+, Jakarta EE)

JEUS 9/21은 Jakarta EE 9+를 사용하므로 `jakarta.servlet.Filter`를 위빙합니다.

> **참고**: JEUS 9은 `javax.servlet.*` 대신 `jakarta.servlet.*` 패키지를 사용합니다.  
> Agent는 두 패키지를 자동으로 이중 위빙합니다.

#### Step 1: 설정 파일

```properties
# /opt/onepass/conf/onepass-agent.properties
onepass.agent.endpoint=https://onepass.go.kr
onepass.agent.api-key=op-agency-YOUR_AGENCY_CODE-YOUR_KEY
onepass.agent.hmac-secret=YOUR_HMAC_SECRET
onepass.agent.log-level=INFO
```

#### Step 2: JVM 설정 (JDK 17+는 추가 옵션 필요)

```xml
<jvm-config>
  <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
  <!-- JDK 17+ JPMS(모듈 시스템) 열기 -->
  <jvm-option>--add-opens=java.base/java.lang=ALL-UNNAMED</jvm-option>
  <jvm-option>--add-opens=java.base/java.util=ALL-UNNAMED</jvm-option>
  <jvm-option>-XX:+EnableDynamicAgentLoading</jvm-option>
</jvm-config>
```

#### Step 3: 로그 확인

```bash
# 기대 출력:
# [OnePassAgent] WAS 유형 감지: JEUS 9/21 (JDK 11+, Jakarta EE)
# [OnePassAgent] 위빙 설치 완료: Jeus8_5PlusWeaving (JEUS 9+, byte-buddy, javax+jakarta)
```

---

## 5. Tomcat 버전별 설치 가이드

### 5.1 Tomcat 5.x/6.x (JDK 5~6, 레거시)

> **위빙 방식**: Javassist / **위빙 포인트**: `ApplicationFilterChain.internalDoFilter()`

```bash
# $CATALINA_HOME/bin/setenv.sh (없으면 생성)
export CATALINA_OPTS="$CATALINA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"

# ⚠️ 동적 어태치(agentmain) 불가 — 반드시 정적 어태치(-javaagent)만 사용
# WAS 재시작 필수
```

**기동 로그 확인**:
```
[OnePassAgent] WAS 유형 감지: Tomcat 5.x/6.x (JDK 5~6, Servlet 2.4~2.5)
[OnePassAgent] 위빙 설치 완료: TomcatVersionedWeaving (TOMCAT_LEGACY)
[TomcatWeaving] Tomcat 5/6 Javassist 위빙 시작 (JDK 5)
```

**주의사항**:
- `APR(Apache Portable Runtime)` 커넥터 사용 시 AJP 요청이 Filter 체인을 우회할 수 있음
- JDK 5~6 환경이므로 byte-buddy 사용 불가, Javassist 위빙 자동 적용

---

### 5.2 Tomcat 7.x (JDK 7, Servlet 3.0)

> **위빙 방식**: JDK 7 → Javassist / JDK 8+ → byte-buddy  
> **위빙 포인트**: `StandardContextValve.invoke()` (JDK 7) / Catalina Valve (JDK 8+)

```bash
# $CATALINA_HOME/bin/setenv.sh
export CATALINA_OPTS="$CATALINA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**JDK 버전 확인**:
```bash
java -version
# JDK 7: Javassist 위빙 → StandardContextValve.invoke() 대상
# JDK 8+: byte-buddy 위빙 → Catalina Valve 대상
```

**기동 로그**:
```
# JDK 7 환경:
[TomcatWeaving] Tomcat 7 + JDK 7 → Javassist StandardContextValve 위빙
# JDK 8 환경:
[TomcatWeaving] Tomcat 7 + JDK 8+ → byte-buddy Catalina Valve 위빙
```

---

### 5.3 Tomcat 8.x/8.5 (JDK 8, Servlet 3.1)

> **위빙 방식**: byte-buddy (Catalina Valve + javax.servlet.Filter 이중)  
> **위빙 포인트**: `StandardHostValve.invoke()` + `javax.servlet.Filter.doFilter()`

```bash
# $CATALINA_HOME/bin/setenv.sh
export CATALINA_OPTS="$CATALINA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: Tomcat 8.x/8.5 (JDK 8, Servlet 3.1)
[TomcatWeaving] Tomcat 8/9 byte-buddy 위빙 (Valve + javax.servlet.Filter)
[TomcatWeaving] Catalina Valve 위빙 완료
[TomcatWeaving] javax.servlet.Filter 위빙 완료
```

**Spring Boot 1.x/2.x Embedded Tomcat (Tomcat 8.5 기반)**:
```bash
java \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties \
  -jar your-spring-boot-app.jar
```

---

### 5.4 Tomcat 9.x (JDK 8+, Servlet 4.0)

> **위빙 방식**: byte-buddy (Catalina Valve + javax.servlet.Filter 이중)  
> **javax 네임스페이스 마지막 버전** — Tomcat 9가 `javax.servlet.*` 최종 지원

```bash
# $CATALINA_HOME/bin/setenv.sh
export CATALINA_OPTS="$CATALINA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: Tomcat 9.x (JDK 8+, Servlet 4.0)
[TomcatWeaving] Tomcat 8/9 byte-buddy 위빙 (Valve + javax.servlet.Filter)
[OnePassAgent] 위빙 설치 완료: TomcatVersionedWeaving (TOMCAT_9)
```

---

### 5.5 Tomcat 10+/11 (JDK 11+, Jakarta EE)

> **⚠️ 중요**: Tomcat 10부터 `javax.servlet.*`이 **완전히 제거**됩니다.  
> Agent가 `jakarta.servlet.Filter`**만** 위빙합니다. javax 위빙 시도 불필요.

**버전별 JDK 요구사항**:
```
Tomcat 10.0/10.1: JDK 11+
Tomcat 11:        JDK 21+
```

```bash
# $CATALINA_HOME/bin/setenv.sh
export CATALINA_OPTS="$CATALINA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: Tomcat 10+/11 (JDK 11+, jakarta.servlet)
[TomcatWeaving] Tomcat 10+ byte-buddy 위빙 (jakarta.servlet.Filter 전용)
[TomcatWeaving] Tomcat 10+ jakarta.servlet.Filter 위빙 완료
```

**업그레이드 주의사항**:
```
Tomcat 9 → Tomcat 10 업그레이드 시:
- 업무 코드의 import javax.servlet.* → import jakarta.servlet.* 변경 필요
- Agent 설정 파일은 변경 없음 (자동 감지)
```

---

### 5.6 Spring Boot Embedded Tomcat

Spring Boot 버전에 따라 내장 Tomcat 버전이 다릅니다:

| Spring Boot | 내장 Tomcat | Servlet | WasType |
|-------------|-----------|---------|---------|
| 1.x | 8.5.x | 3.1 | `TOMCAT_8` |
| 2.x | 9.0.x | 4.0 | `TOMCAT_9` |
| 3.x | 10.1.x | 6.0 | `TOMCAT_10_PLUS` |

```bash
# JAR 실행 시
java \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties \
  -jar your-spring-boot-app.jar

# systemd 서비스 파일 예시
[Service]
Environment=JAVA_OPTS="-javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
ExecStart=/usr/bin/java $JAVA_OPTS -jar /opt/app/app.jar
```

---

## 6. JBoss/WildFly 설치 가이드

### 6.1 JBoss EAP 7.x (JDK 8+)

```bash
# $JBOSS_HOME/bin/standalone.conf (또는 domain.conf)
JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"

# 또는 domain.xml의 JVM 설정에 추가
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: JBoss EAP 6/7 (JDK 8+)
[OnePassAgent] 위빙 설치 완료: GenericServletFilterWeaving
```

### 6.2 WildFly 27+ (JDK 11+, Jakarta EE)

```bash
# $WILDFLY_HOME/bin/standalone.conf
JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: WildFly 27+ (JDK 11+, Jakarta EE)
[OnePassAgent] 위빙 설치 완료: GenericServletFilterWeaving (javax + jakarta 이중 지원)
```

---

## 7. Oracle WebLogic 설치 가이드

> **⚠️ WebLogic Filtering ClassLoader 주의**: WebLogic은 특정 패키지를 필터링하는 클래스로더를 사용합니다. Agent 클래스와 WAS 클래스 간 가시성 이슈가 발생할 수 있습니다.

### 7.1 WebLogic 12c(후기)/14c (JDK 8+)

```bash
# $DOMAIN_HOME/bin/setDomainEnv.sh
JAVA_OPTIONS="${JAVA_OPTIONS} \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**WebLogic 클래스로더 설정** (`weblogic.xml` 추가):
```xml
<weblogic-web-app>
  <container-descriptor>
    <!-- OnePass Agent 클래스가 WAS 내부에서 보이도록 필터 제외 -->
    <prefer-application-packages>
      <package-name>kr.go.smes.agent.*</package-name>
    </prefer-application-packages>
  </container-descriptor>
</weblogic-web-app>
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: WebLogic 12c(후기)/14c (JDK 8+)
[OnePassAgent] 위빙 설치 완료: GenericServletFilterWeaving
```

### 7.2 WebLogic 10.x/11g/12c 초기 (JDK 6~7, 레거시)

```bash
# $DOMAIN_HOME/bin/setDomainEnv.sh
JAVA_OPTIONS="${JAVA_OPTIONS} \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: WebLogic 10.x/11g/12c-early (JDK 6~7)
[LegacyJavassist] WEBLOGIC_LEGACY Javassist 위빙 시작
```

**문제 발생 시 수동 지정**:
```bash
# JVM 옵션에 추가
-Donepass.was.type=WEBLOGIC         # 12c 후기/14c
-Donepass.was.type=WEBLOGIC_LEGACY  # 10.x/11g/12c 초기
```

---

## 8. IBM WebSphere 설치 가이드

> **⚠️ IBM J9 JVM 특화 주의**: IBM WebSphere는 기본적으로 Oracle HotSpot이 아닌 IBM J9 JVM을 사용합니다. JVM TI(Tool Interface) 동작이 HotSpot과 다를 수 있습니다.

### 8.1 WebSphere Liberty / Open Liberty (JDK 8+)

```bash
# server.xml의 jvmOptions 또는 jvm.options 파일에 추가
# $WLP_HOME/usr/servers/<server-name>/jvm.options
-javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties
```

**또는 server.env 파일**:
```bash
# $WLP_HOME/usr/servers/<server-name>/server.env
JVM_ARGS=-javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: WebSphere Liberty / Open Liberty (JDK 8+)
[OnePassAgent] 위빙 설치 완료: GenericServletFilterWeaving (javax + jakarta 이중 지원)
```

### 8.2 WebSphere Application Server 7/8 (JDK 6~7, 레거시)

```bash
# WebSphere 관리 콘솔 → 서버 → JVM 설정 → 일반 JVM 인수 추가:
-javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties
```

**또는 startServer.sh 스크립트 수정**:
```bash
# $WAS_HOME/bin/startServer.sh
JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties"
```

**기동 로그**:
```
[OnePassAgent] WAS 유형 감지: WebSphere 7.x/8.x (JDK 6~7)
[LegacyJavassist] WEBSPHERE_LEGACY Javassist 위빙 시작
[LegacyJavassist] 위빙 포인트: javax.servlet.Filter#doFilter
```

**IBM J9 JVM 특이사항**:
```
IBM J9 JVM에서 byte-buddy 1.17.x는 JVM TI 인터페이스 차이로 인해
동작이 다를 수 있습니다. WebSphere Legacy(7/8)는 Javassist가 적용되므로
이 문제를 우회합니다.
WebSphere Liberty(JDK 8+)에서 byte-buddy 사용 시 문제가 발생하면:
-Donepass.was.type=WEBSPHERE_LEGACY 로 Javassist fallback을 강제 지정합니다.
```

---

## 9. 설정 파일 전체 옵션

```properties
# ============================================================
# OnePass Agency Java Agent — 설정 파일 전체 옵션
# ============================================================

# ── 필수 설정 ──────────────────────────────────────────────
# OnePass 인증 서버 Base URL (http:// 또는 https://)
onepass.agent.endpoint=https://onepass.go.kr

# 기관 API Key (행정안전부 OnePass 담당자로부터 발급)
onepass.agent.api-key=op-agency-YOURCODE-YOURKEY


# ── HTTP 통신 설정 ─────────────────────────────────────────
# HTTP 연결 타임아웃 (밀리초, 기본: 5000)
onepass.agent.connect-timeout-ms=5000

# HTTP 읽기 타임아웃 (밀리초, 기본: 10000)
onepass.agent.read-timeout-ms=10000

# HTTP 재시도 횟수 (기본: 2, 5xx 서버 오류 시 재시도)
onepass.agent.max-retry=2


# ── 보안 설정 ──────────────────────────────────────────────
# HMAC-SHA256 서명 시크릿 (선택, 미설정 시 서명 없이 전송)
# 설정 시 모든 API 요청에 X-OnePass-Signature 헤더 자동 추가
onepass.agent.hmac-secret=


# ── Agent 동작 제어 ────────────────────────────────────────
# Agent 활성화 여부 (기본: true)
# false로 설정 후 WAS 재시작하면 Agent 기능 완전 비활성화
onepass.agent.enabled=true

# 로그 수준 (INFO / WARN / ERROR, 기본: INFO)
onepass.agent.log-level=INFO
```

---

## 10. 동작 검증 방법

### 7.1 기동 로그 확인 (가장 기본)

WAS 기동 후 로그에서 아래 패턴을 찾습니다:

```
✅ 정상 초기화:
[OnePassAgent] 초기화 완료. WAS={WAS유형} / 전략={전략명} / endpoint={URL}

❌ 설정 오류:
[ERROR] [OnePassAgent] 설정 로드 실패 — Agent 비활성화: 필수 설정 누락: onepass.agent.endpoint

⚠️ 위빙 실패 (WAS는 기동됨):
[WARN] [OnePassAgent] 위빙 설치 실패: ...
```

### 7.2 토큰 없는 요청 테스트

```bash
# X-OnePass-Token 헤더 없이 요청 → 401 기대
curl -v http://your-agency-was:8080/your-app/protected-resource

# 기대 응답:
# HTTP/1.1 401 Unauthorized
```

### 7.3 유효한 토큰으로 요청 테스트

```bash
# OnePass 서버에서 발급한 유효 토큰으로 요청 → 200 기대
curl -v \
  -H "X-OnePass-Token: YOUR_VALID_TOKEN" \
  http://your-agency-was:8080/your-app/protected-resource

# 기대 응답:
# HTTP/1.1 200 OK
```

### 7.4 헬스체크 API (비동기, WAS 기동 30초 후)

Agent는 WAS 기동 30초 후 OnePass 서버 연결을 확인합니다:

```
# 로그 확인
[OnePassAgent] OnePass 서버 연결 확인: HTTP 200
```

---

## 11. SSO 흐름 이해

### 8.1 전체 SSO 흐름

```
[사용자]           [유관기관 WAS]          [OnePass 서버]
   │                     │                       │
   │─ 로그인 요청 ──────→│                       │
   │                     │─ OnePass 로그인 리다이렉트
   │                     │                       │
   │←──── OnePass 로그인 페이지 리다이렉트 ──────│
   │                     │                       │
   │─ 로그인 (ID/PW) ─────────────────────────→│
   │                     │                       │
   │←── X-OnePass-Token (JWT) 발급 ─────────────│
   │                     │                       │
   │─ 업무 페이지 요청  →│                       │
   │  (X-OnePass-Token   │                       │
   │   헤더 포함)         │                       │
   │                     │─ 토큰 검증 API 호출 →│
   │                     │                       │
   │                     │← HTTP 200 (검증 성공)─│
   │                     │                       │
   │←── 업무 응답 ───────│                       │
```

### 8.2 토큰 검증 실패 흐름

```
[사용자]           [유관기관 WAS]          [OnePass 서버]
   │                     │                       │
   │─ 업무 페이지 요청  →│                       │
   │  (토큰 없거나 만료)  │                       │
   │                     │─ 토큰 검증 API 호출 →│
   │                     │                       │
   │                     │← HTTP 401 (토큰 무효)─│
   │                     │                       │
   │←── HTTP 401 반환 ───│                       │
   │   (업무 로직 미실행) │                       │
```

---

## 12. 보안 요구사항

### 9.1 필수 보안 조치

```
✅ 설정 파일 권한
   chmod 600 /opt/onepass/conf/onepass-agent.properties
   → 소유자(WAS 구동 계정)만 읽기 가능

✅ Agent JAR 무결성 확인
   sha256sum onepass-agent-1.0.0-all.jar
   → OnePass 담당자로부터 제공된 체크섬과 일치해야 함

✅ 내부망 전용 통신
   유관기관 WAS → OnePass 서버는 내부망(방화벽 허용) 경유

✅ HTTPS 사용
   onepass.agent.endpoint=https://onepass.go.kr (TLS 필수)
```

### 9.2 API Key 보안

```
❌ 하지 말 것:
   - 소스 코드에 API Key 하드코딩
   - 설정 파일을 형상관리(Git)에 커밋

✅ 권장:
   - 별도 보안 파일(/opt/onepass/conf/)에 저장
   - 또는 -D 시스템 프로퍼티로 전달 (JVM 프로세스 목록 노출 주의)
   - 정기 API Key 갱신 (분기 1회 권장)
```

---

## 13. 운영 중 설정 변경

> **주의**: 설정 변경은 WAS 재시작 후 적용됩니다.

### 10.1 Agent 비활성화 (긴급 시)

```properties
# onepass-agent.properties 수정
onepass.agent.enabled=false
```
```bash
# WAS 재시작
jeus_stop.sh && jeus_start.sh

# 확인
grep "enabled=false" $JEUS_HOME/logs/JeusServer.log
# [OnePassAgent] onepass.agent.enabled=false — 위빙 건너뜀
```

### 10.2 타임아웃 조정

OnePass 서버 응답이 느린 경우:

```properties
onepass.agent.connect-timeout-ms=10000
onepass.agent.read-timeout-ms=30000
onepass.agent.max-retry=3
```

---

## 14. 제거(Uninstall)

### Step 1: JVM 옵션에서 -javaagent 제거

```xml
<!-- domain.xml에서 아래 라인 제거 -->
<jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=...</jvm-option>
```

### Step 2: WAS 재시작

```bash
jeus_stop.sh && jeus_start.sh
```

### Step 3: 파일 정리 (선택)

```bash
rm -rf /opt/onepass/
```

---

## 15. FAQ

**Q1. JEUS 4를 사용하고 있는데 JDK 1.5입니다. SSO Agent가 정말 동작하나요?**

A: 네, 가능합니다. `java.lang.instrument.Instrumentation`은 JDK 1.5에 도입됐고(JSR-163), Javassist 3.x는 JDK 1.3+를 지원합니다. 단, `-javaagent:` 정적 어태치만 가능하고, 동적 어태치(운영 중 주입)는 JDK 1.6+에서만 가능합니다.

---

**Q2. Agent 설치 후 WAS가 정상 기동되지 않습니다.**

A: 아래 순서로 점검하세요:
1. 로그에서 `[ERROR] [OnePassAgent]` 패턴 검색
2. 설정 파일 경로와 권한 확인 (`chmod 600`)
3. `endpoint`, `api-key` 필수값 존재 확인
4. 임시 비활성화: JVM 옵션에 `-Donepass.agent.enabled=false` 추가 후 재시작

---

**Q3. WAS는 기동되는데 SSO가 동작하지 않습니다.**

A:
1. 로그에서 `[WARN] [OnePassAgent] 모든 위빙 포인트 실패` 확인
2. WAS 유형이 정확히 감지됐는지 확인 (`[OnePassAgent] WAS 유형 감지:` 로그)
3. 강제 지정 시도: `-Donepass.was.type=JEUS_LEGACY` (JEUS 4/5 경우)
4. 방화벽 확인: WAS → OnePass 서버 아웃바운드 허용 여부

---

**Q4. 업무 시스템 성능에 영향이 있나요?**

A: 각 HTTP 요청마다 OnePass 서버 검증 API를 호출합니다. 내부망 기준 추가 레이턴시는 약 1~5ms입니다. connect-timeout과 read-timeout을 적절히 설정하여 최악의 경우에도 15초 이내에 처리됩니다.

---

**Q5. HMAC 시크릿은 필수인가요?**

A: 선택 사항입니다. 설정하지 않으면 `X-OnePass-Signature` 헤더 없이 API Key만으로 인증합니다. 보안 수준을 높이려면 HMAC 시크릿을 설정하세요.

---

**Q6. 기존에 Servlet Filter나 보안 모듈이 있는데 충돌하지 않나요?**

A: Agent는 기존 Filter Chain 앞단에서 동작하므로 충돌 가능성이 낮습니다. 단, 기존 Filter에서 이미 401을 내보내는 경우 Agent의 검증 결과와 중복 처리될 수 있습니다. OnePass 담당자에게 문의하세요.

---

*문의: OnePass 행정안전부 통합인증 플랫폼 운영팀*  
*이메일: onepass-support@go.kr*
