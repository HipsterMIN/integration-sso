# OnePass Agency Java Agent — 트러블슈팅 가이드

> **문서 번호**: AGENT-TROUBLESHOOT-001  
> **문서 버전**: v1.0.0  
> **작성일**: 2026-05-17  
> **대상 독자**: 유관기관 시스템 관리자, OnePass 지원팀  

---

## 트러블슈팅 분류 체계

```
증상으로 찾기:
  TS-01: WAS가 기동되지 않는다
  TS-02: WAS는 기동되지만 SSO가 동작하지 않는다
  TS-03: 모든 요청이 401을 반환한다
  TS-04: 일부 요청만 401을 반환한다
  TS-05: WAS가 느려졌다 (성능 저하)
  TS-06: JEUS 버전 감지가 잘못됐다
  TS-07: 로그에 WARN/ERROR가 반복된다
  TS-08: JDK 버전 관련 오류
  TS-09: 클래스로더 관련 오류
  TS-10: Agent 업그레이드 후 문제
```

---

## TS-01: WAS가 기동되지 않는다

### 증상
```
JEUS 기동 후 즉시 종료됨
또는
기동 로그에 Agent 관련 오류 후 프로세스 중단
```

### 진단 절차

**Step 1: 오류 메시지 확인**
```bash
# 기동 로그에서 ERROR 패턴 검색
grep "ERROR\|Exception\|Error" $JEUS_HOME/logs/JeusServer.log | head -30
```

---

#### TS-01-A: UnsupportedClassVersionError

**증상**:
```
java.lang.UnsupportedClassVersionError: 
kr/go/smes/agent/core/OnePassAgentMain : 
Unsupported major.minor version 52.0
```

**원인**: JDK 1.5 환경에서 JDK 8로 컴파일된 Agent 클래스를 로드 시도

**해결**: 이 오류가 발생하면 Agent JAR 버전 문제입니다.
- Agent JAR가 `--release 8` 옵션으로 컴파일됐는지 확인
- 담당자에게 JDK 1.5 호환 버전 요청

> **참고**: `onepass-agent-1.0.0-all.jar`는 `--release 8` (JDK 8 소스 호환) 컴파일로  
> JDK 8+ 환경에서 실행됩니다. JDK 1.5/1.6/1.7 환경에서 Agent 클래스 자체가 로드되려면  
> `--release 5`로 재컴파일이 필요합니다. → **현재 미지원, Sprint 계획 예정**

> ⚠️ **현재 지원 범위**: OnePass Agent 클래스 자체는 JDK 8+ 환경에서 실행됩니다.  
> "JDK 1.5 SSO 가능"이란 의미는, JDK 8 환경에서도 Javassist로 위빙된 코드가  
> JEUS 4/5 위의 JDK 1.5 JVM에서 삽입 코드를 실행할 수 있다는 것입니다.  
> **JEUS 4/5 + JDK 1.5에 Agent 직접 적용 시**: Agent JVM을 JDK 8+로,  
> JEUS 4/5 JVM은 JDK 1.5로 분리하거나, 별도 프록시 에이전트 방식 사용.  
> 자세한 내용은 `AGENT-JEUS-001` 문서 참조.

---

#### TS-01-B: AgentConfigException (필수 설정 누락)

**증상**:
```
[ERROR] [OnePassAgent] 설정 로드 실패 — Agent 비활성화: 필수 설정 누락: onepass.agent.endpoint
```

**원인**: `onepass-agent.properties` 파일이 없거나 필수 키가 없음

**해결**:
```bash
# 1. 파일 존재 확인
ls -la /opt/onepass/conf/onepass-agent.properties

# 2. 파일 내용 확인
cat /opt/onepass/conf/onepass-agent.properties

# 3. 필수 키 존재 확인
grep -E "onepass.agent.endpoint|onepass.agent.api-key" /opt/onepass/conf/onepass-agent.properties

# 4. 시스템 프로퍼티로 임시 설정 (파일 없이)
# domain.xml의 jvm-option에 추가:
# <jvm-option>-Donepass.agent.endpoint=https://onepass.go.kr</jvm-option>
# <jvm-option>-Donepass.agent.api-key=YOUR_KEY</jvm-option>
```

---

#### TS-01-C: JAR 파일 손상 또는 없음

**증상**:
```
Error occurred during initialization of VM
Could not find or load main class ...
또는
java.util.zip.ZipException: invalid entry size
```

**해결**:
```bash
# JAR 파일 무결성 확인
java -jar /opt/onepass/onepass-agent-1.0.0-all.jar 2>&1 | head -5

# MANIFEST 확인
jar tf /opt/onepass/onepass-agent-1.0.0-all.jar | grep "Premain\|MANIFEST"
# 기대 출력: META-INF/MANIFEST.MF

# MANIFEST 내용 확인
jar xf /opt/onepass/onepass-agent-1.0.0-all.jar META-INF/MANIFEST.MF -C /tmp/
cat /tmp/META-INF/MANIFEST.MF
# Premain-Class: kr.go.smes.agent.core.OnePassAgentMain 가 있어야 함
```

---

## TS-02: WAS는 기동되지만 SSO가 동작하지 않는다

### 증상
- WAS 정상 기동
- 토큰 없는 요청에도 401이 아닌 200 반환
- Agent 초기화 로그는 있지만 위빙이 동작하지 않음

### TS-02-A: 위빙 포인트 미발견

**증상**:
```
[WARN] [JeusLegacyWeaving] 모든 위빙 포인트 실패 — OnePass SSO 기능이 비활성화됩니다
```

**원인**: JEUS 버전이 잘못 감지됐거나 위빙 대상 클래스가 다른 경로에 있음

**해결**:
```bash
# 1. 감지된 WAS 유형 확인
grep "WAS 유형 감지" $JEUS_HOME/logs/JeusServer.log

# 2. 실제 JEUS 버전과 불일치 시 강제 지정
# domain.xml에 추가:
# <jvm-option>-Donepass.was.type=JEUS_LEGACY</jvm-option>  (JEUS 4/5)
# <jvm-option>-Donepass.was.type=JEUS_6</jvm-option>       (JEUS 6)
# <jvm-option>-Donepass.was.type=JEUS_7</jvm-option>       (JEUS 7)
# 등

# 3. JEUS 클래스 존재 확인
java -cp "$JEUS_HOME/lib/*" -e "Class.forName(\"com.tmax.jeus.web.servlet.HttpServletWrapper\")" \
  2>/dev/null && echo "클래스 존재" || echo "클래스 없음"
```

---

### TS-02-B: Agent 비활성화 상태

**증상**:
```
[OnePassAgent] onepass.agent.enabled=false — 위빙 건너뜀
```

**해결**:
```bash
# 설정 파일 확인
grep "enabled" /opt/onepass/conf/onepass-agent.properties
# onepass.agent.enabled=false → true로 변경

# 또는 시스템 프로퍼티 확인
# domain.xml에 -Donepass.agent.enabled=false 있으면 제거
```

---

### TS-02-C: 위빙은 됐지만 검증 API 호출 실패

**증상**:
- 위빙은 성공 (로그 확인)
- 그러나 토큰 없는 요청이 통과됨
- 로그에 `연결 실패` 또는 타임아웃

**해결**:
```bash
# 1. OnePass 서버 연결 직접 테스트
curl -v --max-time 10 https://onepass.go.kr/health

# 2. 타임아웃 단서
grep "타임아웃\|timeout\|timed out" $JEUS_HOME/logs/JeusServer.log

# 3. 타임아웃 설정 확인 (너무 짧으면 늘리기)
grep "timeout" /opt/onepass/conf/onepass-agent.properties

# 4. Fail-Open 정책 확인 (현재 기본값: 검증 실패 시 통과)
# → 검증 실패 시 차단하려면 담당자에게 문의 (설정 예정)
```

---

## TS-03: 모든 요청이 401을 반환한다

### 증상
- 유효한 토큰으로 요청해도 401
- 또는 갑자기 모든 요청이 401

### TS-03-A: OnePass 서버 장애

**진단**:
```bash
# OnePass 서버 상태 확인
curl -v --max-time 5 https://onepass.go.kr/health

# Agent 로그에서 서버 오류 확인
grep "HTTP 5\|서버 오류\|500\|503" $JEUS_HOME/logs/JeusServer.log
```

**해결**: OnePass 운영팀에 서버 상태 확인 요청

---

### TS-03-B: API Key 만료 또는 무효

**진단**:
```bash
# API 검증 직접 테스트
curl -v -X POST \
  -H "X-OnePass-Api-Key: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"token":"TEST"}' \
  https://onepass.go.kr/api/v1/agent/verify

# 403 응답이면 API Key 문제
```

**해결**: 행정안전부 담당자에게 API Key 갱신 요청

---

### TS-03-C: 시스템 시간 동기화 문제

**원인**: HMAC 서명의 Timestamp가 현재 시간과 많이 차이날 때

**진단**:
```bash
# 현재 서버 시간 확인
date

# NTP 동기화 상태 확인
timedatectl status | grep "synchronized"
```

**해결**:
```bash
# NTP 동기화
ntpdate -u pool.ntp.org
# 또는
chronyc makestep
```

---

## TS-04: 일부 요청만 401을 반환한다

### TS-04-A: 특정 URL 패턴만 차단됨

**증상**: `/api/*` 경로는 401, `/public/*` 경로는 200

**원인**: 위빙이 일부 서블릿에만 적용됨 (정상 동작일 수 있음)

**확인**: 어떤 경로가 보호 대상인지 OnePass 서버 설정 확인

---

### TS-04-B: 간헐적 401 (타임아웃 기반)

**증상**: 대부분은 통과, 간헐적으로 401 발생

**원인**: OnePass 서버 응답 지연 → 타임아웃 → 기본 Fail-Open 동작

**진단**:
```bash
# 타임아웃 로그 확인
grep "타임아웃\|timeout" $JEUS_HOME/logs/JeusServer.log | wc -l

# 타임아웃 설정 조정
cat /opt/onepass/conf/onepass-agent.properties
# onepass.agent.read-timeout-ms=10000 → 20000으로 늘리기
```

---

## TS-05: WAS가 느려졌다 (성능 저하)

### TS-05-A: Agent 오버헤드 측정

```bash
# Agent 없이 응답 시간 측정 (비활성화 후)
# onepass.agent.enabled=false 설정 → WAS 재시작 → 측정
ab -n 100 -c 10 http://localhost:8080/your-app/api/test

# Agent 활성화 후 측정
# onepass.agent.enabled=true → WAS 재시작 → 측정
ab -n 100 -c 10 http://localhost:8080/your-app/api/test
```

**예상 오버헤드**: 내부망 기준 1~5ms/요청

---

### TS-05-B: byte-buddy 초기화 지연

**증상**: WAS 기동이 수 분 이상 소요

**진단**:
```bash
# premain 시작 ~ 완료 시간 차이 확인
grep "초기화 시작\|초기화 완료" $JEUS_HOME/logs/JeusServer.log
```

**해결**: 비정상적으로 길면(5분+) 담당자에게 문의

---

## TS-06: JEUS 버전 감지가 잘못됐다

### 증상
```
[WasDetector] Tomcat 클래스 감지 (JEUS 환경인데 잘못 감지)
또는
[OnePassAgent] WAS 유형 감지: Unknown
```

### 진단 및 해결

```bash
# 1. 현재 클래스패스에 JEUS 클래스가 있는지 확인
java -verbose:class 2>&1 | grep "tmax\|tmaxsoft" | head -10

# 2. JEUS 시스템 프로퍼티 확인
java -XshowSettings:all 2>&1 | grep "jeus"

# 3. 강제 지정 (가장 확실한 해결)
# domain.xml에 추가:
```

```xml
<!-- JEUS 버전별 강제 지정 -->
<!-- JEUS 4 -->  <jvm-option>-Donepass.was.type=JEUS_LEGACY</jvm-option>
<!-- JEUS 5 -->  <jvm-option>-Donepass.was.type=JEUS_LEGACY</jvm-option>
<!-- JEUS 6 -->  <jvm-option>-Donepass.was.type=JEUS_6</jvm-option>
<!-- JEUS 7 -->  <jvm-option>-Donepass.was.type=JEUS_7</jvm-option>
<!-- JEUS 8 -->  <jvm-option>-Donepass.was.type=JEUS_8</jvm-option>
<!-- JEUS 8.5 --> <jvm-option>-Donepass.was.type=JEUS_8_5</jvm-option>
<!-- JEUS 9+ --> <jvm-option>-Donepass.was.type=JEUS_9_PLUS</jvm-option>
```

---

## TS-07: 로그에 WARN/ERROR가 반복된다

### TS-07-A: 헬스체크 실패 반복

**증상**:
```
[WARN] [OnePassAgent] OnePass 서버 연결 실패 (비치명적): Connection refused
(30분마다 반복)
```

**원인**: 헬스체크가 WAS 기동 30초 후 1회만 실행 → 이후 반복 없음  
만약 반복된다면 개별 요청의 검증 실패

**해결**: OnePass 서버 연결 확인

---

### TS-07-B: 재변환(retransform) 실패

**증상**:
```
[WARN] [JavassistEngine] 이미 로드된 클래스 재변환 실패
```

**원인**: premain 이전에 이미 로드된 클래스에 대한 재변환 시도 (JDK 1.5 제약)

**영향**: premain이 충분히 빨리 실행됐으면 영향 없음  
(위빙 대상 클래스는 일반적으로 WAS 초기화 중 로드되므로 문제없음)

**해결**: 무시 가능. 지속 문제 시 담당자 문의.

---

## TS-08: JDK 버전 관련 오류

### TS-08-A: JDK 17+ 모듈 시스템 경고

**증상**:
```
WARNING: A terminally deprecated method in java.lang.System has been called
WARNING: Please consider reporting this to the maintainers of ...
```

**해결**:
```xml
<jvm-option>-XX:+EnableDynamicAgentLoading</jvm-option>
<jvm-option>-Djdk.instrument.traceUsage=false</jvm-option>
```

---

### TS-08-B: NoClassDefFoundError (byte-buddy 관련)

**증상**:
```
java.lang.NoClassDefFoundError: net/bytebuddy/agent/builder/AgentBuilder
```

**원인**: byte-buddy가 fat-JAR에 포함되지 않은 버전

**해결**:
```bash
# fat-JAR에 byte-buddy 포함됐는지 확인
jar tf /opt/onepass/onepass-agent-1.0.0-all.jar | grep "bytebuddy" | head -5
# 출력 없으면 fat-JAR 재빌드 필요 → 담당자 문의
```

---

## TS-09: 클래스로더 관련 오류

### TS-09-A: ClassNotFoundException (Javassist ClassPool 오류)

**증상**:
```
[WARN] [JavassistEngine] ClassPool 생성 실패: 
javassist.NotFoundException: com.tmax.jeus.web.servlet.HttpServletWrapper
```

**원인**: JEUS 4/5 클래스가 Agent의 ClassPool 범위 밖

**해결**:
```bash
# JEUS lib 디렉토리에 jeus.jar가 있는지 확인
ls $JEUS_HOME/lib/ | grep jeus

# JEUS_HOME이 올바른지 확인
echo $JEUS_HOME
# 기대: /usr/local/jeus4 (또는 실제 경로)
```

**에스컬레이션**: JEUS 4/5 lib 경로를 Agent에 전달하는 옵션 추가 필요 → 담당자 문의

---

## TS-10: Agent 업그레이드 후 문제

### TS-10-A: 구버전 캐시 문제

**해결**:
```bash
# JVM 클래스 캐시 제거 (있는 경우)
rm -rf $JEUS_HOME/tmp/classCache/
rm -rf $JEUS_HOME/tmp/jit/

# 완전 재시작
$JEUS_HOME/bin/stopDomainAdminServer
sleep 10
$JEUS_HOME/bin/startDomainAdminServer
```

---

### TS-10-B: 새 버전 설정 형식 변경

**해결**: 릴리즈 노트에서 설정 변경사항 확인 → 설정 파일 업데이트

---

## 빠른 진단 스크립트

```bash
#!/bin/bash
# /opt/onepass/diagnose.sh
# OnePass Agent 빠른 진단 스크립트

echo "======================================"
echo " OnePass Agent 빠른 진단"
echo "======================================"

# 1. JAR 파일 존재
echo -n "[1] Agent JAR 파일: "
[ -f /opt/onepass/onepass-agent-1.0.0-all.jar ] && echo "✅ 존재" || echo "❌ 없음"

# 2. 설정 파일 존재
echo -n "[2] 설정 파일: "
[ -f /opt/onepass/conf/onepass-agent.properties ] && echo "✅ 존재" || echo "❌ 없음"

# 3. 설정 파일 권한
echo -n "[3] 설정 파일 권한: "
PERM=$(stat -c "%a" /opt/onepass/conf/onepass-agent.properties 2>/dev/null)
[ "$PERM" = "600" ] && echo "✅ 600 (안전)" || echo "⚠️  $PERM (600 권장)"

# 4. 필수 설정 키
echo -n "[4] onepass.agent.endpoint 설정: "
grep -q "onepass.agent.endpoint" /opt/onepass/conf/onepass-agent.properties 2>/dev/null \
  && echo "✅ 있음" || echo "❌ 없음"

echo -n "[5] onepass.agent.api-key 설정: "
grep -q "onepass.agent.api-key" /opt/onepass/conf/onepass-agent.properties 2>/dev/null \
  && echo "✅ 있음" || echo "❌ 없음"

# 5. JDK 버전
echo -n "[6] JDK 버전: "
java -version 2>&1 | head -1

# 6. OnePass 서버 연결
echo -n "[7] OnePass 서버 연결: "
ENDPOINT=$(grep "onepass.agent.endpoint" /opt/onepass/conf/onepass-agent.properties \
  2>/dev/null | cut -d= -f2)
if [ -n "$ENDPOINT" ]; then
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    --max-time 5 "$ENDPOINT/health" 2>/dev/null)
  [ "$HTTP_STATUS" = "200" ] && echo "✅ HTTP $HTTP_STATUS" || echo "⚠️  HTTP $HTTP_STATUS"
else
  echo "❌ endpoint 미설정"
fi

# 7. JEUS 기동 로그 확인
if [ -n "$JEUS_HOME" ]; then
  LOG="$JEUS_HOME/logs/JeusServer.log"
  echo -n "[8] Agent 초기화 성공: "
  grep -q "초기화 완료" "$LOG" 2>/dev/null && echo "✅" || echo "❌ (로그 확인 필요)"
  
  echo -n "[9] WARN/ERROR 횟수: "
  COUNT=$(grep -cE "ERROR|WARN.*OnePass" "$LOG" 2>/dev/null)
  echo "$COUNT 건"
fi

echo "======================================"
echo "진단 완료"
```

---

## 지원 요청 시 수집 정보

OnePass 지원팀에 문의할 때 아래 정보를 함께 제공하면 빠른 해결이 가능합니다:

```bash
#!/bin/bash
# 지원 정보 수집 스크립트
OUTPUT="/tmp/onepass-support-$(date +%Y%m%d-%H%M%S).txt"

{
  echo "=== 시스템 정보 ==="
  uname -a
  java -version 2>&1
  echo "JEUS_HOME=$JEUS_HOME"
  
  echo ""
  echo "=== Agent 설정 (민감 정보 제외) ==="
  # API Key는 마스킹
  sed 's/api-key=.*/api-key=****/' /opt/onepass/conf/onepass-agent.properties 2>/dev/null
  sed 's/hmac-secret=.*/hmac-secret=****/' /opt/onepass/conf/onepass-agent.properties 2>/dev/null
  
  echo ""
  echo "=== 최근 Agent 로그 (마지막 200줄) ==="
  grep "OnePassAgent\|WasDetector\|EngineSelector\|ERROR\|WARN" \
    "$JEUS_HOME/logs/JeusServer.log" 2>/dev/null | tail -200
    
} > "$OUTPUT"

echo "지원 정보가 수집됐습니다: $OUTPUT"
echo "이 파일을 지원팀에 전달해 주세요."
```


---

## TS-11: IBM WebSphere IBM J9 JVM 관련 이슈

### 증상

```
[WARN] [LegacyJavassist] WEBSPHERE_LEGACY Javassist 위빙 실패: ...
또는
byte-buddy AgentBuilder에서 ClassDefinitionException 발생
```

### 원인

IBM WebSphere Application Server(특히 7.x/8.x)는 IBM J9 JVM을 사용합니다.  
IBM J9 JVM은 Oracle HotSpot JVM과 JVM TI(Tool Interface) 구현이 다르므로 byte-buddy 동작에 차이가 있습니다.

### 진단

```bash
# JVM 종류 확인
java -version
# IBM J9 출력 예시:
# java version "1.8.0_391"
# IBM J9 VM (build 2.9, JRE 1.8.0 AIX amd64-64 Compressed References ...)

# JVM 타입 식별
java -XshowSettings:all 2>&1 | grep -i "vm.name"
```

### 해결 방법

**Step 1: WebSphere Legacy(Javassist) 위빙 강제 지정**

```bash
# JVM 옵션에 추가
-Donepass.was.type=WEBSPHERE_LEGACY

# WebSphere Liberty인 경우:
# $WLP_HOME/usr/servers/<server-name>/jvm.options에 추가
-Donepass.was.type=WEBSPHERE_LEGACY
-javaagent:/opt/onepass/onepass-agent-all.jar=config=/opt/onepass/onepass-agent.properties
```

기동 로그 확인:
```
[LegacyJavassist] WEBSPHERE_LEGACY Javassist 위빙 시작
[LegacyJavassist] 위빙 포인트: javax.servlet.Filter#doFilter
[LegacyJavassist] WEBSPHERE_LEGACY 위빙 설치 완료
```

**Step 2: OSGi 번들 ClassLoader 이슈 (WebSphere Liberty)**

WebSphere Liberty는 OSGi 기반으로 각 번들마다 독립 ClassLoader를 사용합니다.  
Agent가 특정 번들의 클래스를 찾지 못할 수 있습니다.

```bash
# server.xml에 패키지 가시성 설정 추가
```

```xml
<!-- $WLP_HOME/usr/servers/<server-name>/server.xml -->
<server>
  <!-- OnePass Agent 클래스 번들 간 공유 -->
  <classloading apiTypeVisibility="spec,ibm-api,api,third-party"/>
  
  <webApplication location="your-app.war">
    <classloader delegation="parentFirst"/>
  </webApplication>
</server>
```

**Step 3: IBM J9 JVM에서 -Xshareclasses 비활성화**

```bash
# JVM 옵션에 추가 (공유 클래스 캐시가 Agent와 충돌하는 경우)
-Xshareclasses:none
```

### 추가 참고

| WebSphere 유형 | WasType | 위빙 엔진 | 권장 조치 |
|---------------|---------|----------|---------|
| WAS 7.x/8.x (IBM J9) | `WEBSPHERE_LEGACY` | Javassist | 자동 (또는 `-Donepass.was.type=WEBSPHERE_LEGACY`) |
| Liberty (HotSpot) | `WEBSPHERE` | byte-buddy | 자동 적용 |
| Liberty (IBM J9) | `WEBSPHERE` | byte-buddy | 문제 시 `WEBSPHERE_LEGACY` 강제 |
| Open Liberty (JDK 17+) | `WEBSPHERE` | byte-buddy | 자동 적용 |

---

## TS-12: Oracle WebLogic FilteringClassLoader 이슈

### 증상

```
[WARN] [GenericFilterAdvice] 토큰 검증 중 예외: ClassNotFoundException: kr.go.smes.agent.*
또는
Agent 클래스가 WAS 애플리케이션에서 보이지 않는 현상
```

### 원인

Oracle WebLogic Server는 `FilteringClassLoader`를 사용하여 특정 패키지를 애플리케이션으로부터 격리합니다.  
이로 인해 Agent 클래스(`kr.go.smes.agent.*`)나 byte-buddy 클래스(`net.bytebuddy.*`)가  
WAS 애플리케이션 ClassLoader에서 보이지 않을 수 있습니다.

### 진단

```bash
# WebLogic 버전 확인
grep "WebLogic" $WL_HOME/server/lib/weblogic.jar 2>/dev/null || echo "WL_HOME 확인 필요"

# 웹로직 로그에서 ClassLoader 오류 확인
grep -i "ClassNotFoundException\|NoClassDefFoundError\|FilteringClassLoader" $DOMAIN_HOME/servers/*/logs/*.log
```

### 해결 방법

**Step 1: weblogic.xml에 패키지 필터 설정**

```xml
<!-- WEB-INF/weblogic.xml -->
<weblogic-web-app xmlns="http://xmlns.oracle.com/weblogic/weblogic-web-app">
  <container-descriptor>
    <!-- OnePass Agent 클래스가 앱에서 보이도록 허용 -->
    <prefer-application-packages>
      <package-name>kr.go.smes.agent.*</package-name>
      <package-name>net.bytebuddy.*</package-name>
      <package-name>javassist.*</package-name>
    </prefer-application-packages>
  </container-descriptor>
</weblogic-web-app>
```

**Step 2: weblogic-application.xml 전역 설정**

EAR 배포 시:
```xml
<!-- META-INF/weblogic-application.xml -->
<weblogic-application xmlns="http://xmlns.oracle.com/weblogic/weblogic-application">
  <prefer-application-packages>
    <package-name>kr.go.smes.agent.*</package-name>
    <package-name>net.bytebuddy.*</package-name>
  </prefer-application-packages>
</weblogic-application>
```

**Step 3: WebLogic 버전 강제 지정**

WAS 감지가 잘못된 경우:
```bash
# JVM 옵션에 추가
-Donepass.was.type=WEBLOGIC         # 12c 후기/14c (JDK 8+)
-Donepass.was.type=WEBLOGIC_LEGACY  # 10.x/11g/12c 초기 (JDK 6~7)
```

**Step 4: WebLogic 12c에서 JDK 버전 혼용 주의**

WebLogic 12.1.x는 JDK 7 기반이지만 일부 환경에서 JDK 8로 실행됩니다.  
이 경우 Agent는 `WEBLOGIC_LEGACY`(Javassist)를 선택하지만, JDK 8에서는 `WEBLOGIC`(byte-buddy)도 가능합니다.

```bash
# JDK 8 + WebLogic 12c 초기 조합에서 강제 byte-buddy 사용
-Donepass.was.type=WEBLOGIC
```

### WebLogic FilteringClassLoader 동작 원리

```
[WebLogic ClassLoader 계층]

Bootstrap ClassLoader
    └── System ClassLoader
            └── WebLogic Boot ClassLoader
                    └── WebLogic Domain ClassLoader (서버 레벨)
                            └── Application ClassLoader ← 애플리케이션 코드
                                    └── Web Application ClassLoader ← WAR 내부

FilteringClassLoader: 상위 → 하위 로딩 시 특정 패키지를 필터링
OnePass Agent JAR는 System ClassLoader 레벨에 있어야 함
```

**Agent JAR 배치 권장 위치**:
```bash
# WebLogic 서버 공유 라이브러리에 추가
cp onepass-agent-all.jar $WL_HOME/server/lib/

# 또는 DOMAIN_HOME/lib/에 추가 (도메인 레벨 공유)
cp onepass-agent-all.jar $DOMAIN_HOME/lib/
```

---

## TS-13: WasDetector 6단계 디버깅 가이드

### WAS 감지 단계별 디버그 방법

Agent가 WAS를 잘못 감지하거나 `UNKNOWN`을 반환하는 경우, 각 단계별로 디버그할 수 있습니다.

**Step 1: 시스템 프로퍼티 오버라이드 확인**

```bash
# 로그에서 1단계 오버라이드 메시지 확인
grep "오버라이드 적용" <WAS_LOG_FILE>
# 예: [WasDetector] 오버라이드 적용: -Donepass.was.type=TOMCAT_9 → Tomcat 9.x
```

**Step 2: 클래스패스 탐색 결과 확인**

```bash
# 로그에서 2단계 클래스패스 감지 메시지 확인
grep "클래스 감지\|공통 클래스 감지" <WAS_LOG_FILE>
# 예: [WasDetector] Tomcat 9 감지 (javax.servlet.http.HttpServletMapping - Servlet 4.0)
```

**클래스패스에 WAS 클래스가 없는 경우** (임베디드 서버 등):
```bash
# JVM 클래스패스 출력
java -verbose:class -cp . TestClass 2>&1 | grep "jeus\|catalina\|weblogic\|jboss" | head -20
```

**Step 3: 시스템 프로퍼티 확인**

```bash
# 현재 JVM 시스템 프로퍼티 확인
# WAS 기동 스크립트에 임시 추가:
-XshowSettings:properties

# 주요 감지 키:
# jeus.home, jeus.version, catalina.home, catalina.base
# weblogic.Name, jboss.home.dir, jboss.server.base.dir
# was.install.root, resin.home, com.sun.aas.instanceRoot
```

**Step 4: 환경 변수 확인**

```bash
# WAS 관련 환경 변수 확인
env | grep -E "JEUS_HOME|CATALINA_HOME|JBOSS_HOME|WL_HOME|WAS_HOME|RESIN_HOME"
```

**Step 5: JVM 인수 / 클래스패스 문자열 스캔**

WasDetector 5단계: `sun.java.command`와 `java.class.path` 패턴 분석.

```bash
# WAS 프로세스 정보에서 클래스패스 확인
ps aux | grep java | grep -E "catalina|jeus|weblogic|jboss"
```

**Step 6: 파일시스템 힌트**

```bash
# WasDetector 6단계: /opt, /usr/local 내 WAS 홈 디렉토리 탐색
ls /opt/tomcat* /opt/jeus* /opt/jboss* /usr/local/tomcat* 2>/dev/null
```

### 강제 오버라이드 정리

```bash
# WAS별 권장 WasType 값
# JEUS 계열
-Donepass.was.type=JEUS_LEGACY      # JEUS 4/5 (JDK 1.5)
-Donepass.was.type=JEUS_6           # JEUS 6
-Donepass.was.type=JEUS_7           # JEUS 7
-Donepass.was.type=JEUS_8           # JEUS 8
-Donepass.was.type=JEUS_8_5         # JEUS 8.5
-Donepass.was.type=JEUS_9_PLUS      # JEUS 9/21

# Tomcat 계열
-Donepass.was.type=TOMCAT_LEGACY    # Tomcat 5/6
-Donepass.was.type=TOMCAT_7         # Tomcat 7
-Donepass.was.type=TOMCAT_8         # Tomcat 8/8.5
-Donepass.was.type=TOMCAT_9         # Tomcat 9
-Donepass.was.type=TOMCAT_10_PLUS   # Tomcat 10/10.1/11

# JBoss/WildFly
-Donepass.was.type=JBOSS_LEGACY     # JBoss AS 5/6
-Donepass.was.type=JBOSS            # JBoss EAP 7
-Donepass.was.type=WILDFLY          # WildFly 27+

# WebLogic
-Donepass.was.type=WEBLOGIC_LEGACY  # 10.x/11g/12c 초기
-Donepass.was.type=WEBLOGIC         # 12c 후기/14c

# WebSphere
-Donepass.was.type=WEBSPHERE_LEGACY # 7.x/8.x (IBM J9)
-Donepass.was.type=WEBSPHERE        # Liberty/Open Liberty

# 기타
-Donepass.was.type=GLASSFISH        # GlassFish 3/4, Payara 5
-Donepass.was.type=GLASSFISH_JAKARTA # GlassFish 6+, Payara 6+
-Donepass.was.type=RESIN            # Caucho Resin
-Donepass.was.type=JETTY_LEGACY     # Jetty 7/8
-Donepass.was.type=JETTY            # Jetty 9~11
-Donepass.was.type=JETTY_JAKARTA    # Jetty 12+
-Donepass.was.type=UNDERTOW         # Undertow Standalone
-Donepass.was.type=UNKNOWN          # Generic Fallback (모든 Servlet WAS)
```

---

## 오류 코드 참조표

| 오류 메시지 키워드 | 원인 | 즉각 조치 |
|------------|------|---------|
| `필수 설정 누락` | endpoint 또는 api-key 없음 | 설정 파일 확인 |
| `설정 파일을 읽을 수 없습니다` | 파일 권한 문제 | chmod 600 |
| `모든 위빙 포인트 실패` | WAS 버전 감지 오류 | -Donepass.was.type 강제 지정 |
| `Transformer 등록 실패` | Instrumentation 오류 | JDK 버전 확인 |
| `Connection refused` | OnePass 서버 연결 불가 | 방화벽 확인 |
| `Read timed out` | 타임아웃 | read-timeout-ms 증가 |
| `UnsupportedClassVersionError` | JDK 버전 불일치 | JDK 버전 확인 |
| `NoClassDefFoundError: bytebuddy` | fat-JAR 문제 | JAR 무결성 확인 |
| `NotFoundException` | ClassPool 클래스 미발견 | JEUS 유형 강제 지정 |

---

*문의: OnePass 행정안전부 통합인증 플랫폼 지원팀 | onepass-support@go.kr*
