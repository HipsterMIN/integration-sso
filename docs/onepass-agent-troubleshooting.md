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
