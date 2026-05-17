# OnePass Agency Java Agent — 설치·운영 워크스루

> **문서 번호**: AGENT-WALKTHROUGH-001  
> **문서 버전**: v1.0.0  
> **작성일**: 2026-05-17  
> **대상 독자**: 처음 Agent를 설치하는 유관기관 시스템 관리자 / 개발자  
> **예상 소요 시간**: JEUS 환경 기준 30~60분

---

## 이 문서의 목적

이 워크스루는 OnePass Agent를 **처음 설치하는 담당자**가 단계별로 따라 할 수 있도록  
**실제 명령어와 기대 출력을 함께** 제공합니다.  
환경별로 달라지는 부분은 `[환경에 따라 수정]` 으로 표시합니다.

---

## 워크스루 목차

- [WK-01: 환경 확인 및 사전 점검](#wk-01-환경-확인-및-사전-점검)
- [WK-02: Agent JAR 및 설정 파일 배포](#wk-02-agent-jar-및-설정-파일-배포)
- [WK-03: JEUS 4/5 설치 워크스루 (JDK 1.5)](#wk-03-jeus-45-설치-워크스루-jdk-15)
- [WK-04: JEUS 6 설치 워크스루](#wk-04-jeus-6-설치-워크스루)
- [WK-05: JEUS 7/8 설치 워크스루](#wk-05-jeus-78-설치-워크스루)
- [WK-06: JEUS 8.5/9/21 설치 워크스루](#wk-06-jeus-859-21-설치-워크스루)
- [WK-07: Tomcat 설치 워크스루](#wk-07-tomcat-설치-워크스루)
- [WK-08: 동작 검증 워크스루](#wk-08-동작-검증-워크스루)
- [WK-09: 운영 모니터링 워크스루](#wk-09-운영-모니터링-워크스루)
- [WK-10: Agent 업그레이드 워크스루](#wk-10-agent-업그레이드-워크스루)
- [WK-11: Agent 제거 워크스루](#wk-11-agent-제거-워크스루)

---

## WK-01: 환경 확인 및 사전 점검

### 1-1. JDK 버전 확인

```bash
java -version
```

**예상 출력 (JEUS 4/5)**:
```
java version "1.5.0_22"
Java(TM) 2 Runtime Environment, Standard Edition (build 1.5.0_22-b03)
Java HotSpot(TM) Server VM (build 1.5.0_22-b03, mixed mode)
```

**예상 출력 (JEUS 8.5)**:
```
java version "1.8.0_292"
Java(TM) SE Runtime Environment (build 1.8.0_292-b10)
Java HotSpot(TM) 64-Bit Server VM (build 25.292-b10, mixed mode)
```

**✅ 확인 포인트**:
- JDK 1.5 환경 → JEUS 4/5 → `-javaagent:` 정적 어태치만 사용
- JDK 1.8+ 환경 → JEUS 7+ → 동적 어태치도 가능

---

### 1-2. JEUS 버전 확인

```bash
# 방법 1: JEUS 시스템 프로퍼티
java -jar $JEUS_HOME/lib/jeus.jar -version

# 방법 2: 설치 경로로 확인
ls $JEUS_HOME/

# 방법 3: jeus.home 확인
echo $JEUS_HOME
# 예: /usr/local/jeus8.5
```

**JEUS 버전 결정 기준**:
```
설치 경로에 "4" 또는 "5" 포함   → JEUS 4/5 → JEUS_LEGACY
설치 경로에 "6" 포함             → JEUS 6   → JEUS_6
설치 경로에 "7" 포함             → JEUS 7   → JEUS_7
설치 경로에 "8.5" 포함           → JEUS 8.5 → JEUS_8_5
설치 경로에 "9" 또는 "21" 포함  → JEUS 9+  → JEUS_9_PLUS
```

---

### 1-3. 네트워크 연결 확인

```bash
# OnePass 서버로의 HTTP 연결 테스트
curl -v --connect-timeout 5 https://onepass.go.kr/health

# 방화벽 차단 시:
# curl: (7) Failed to connect to onepass.go.kr port 443: Connection refused
# 또는
# curl: (28) connect() timed out!
```

**✅ 확인 포인트**:
- HTTP 응답이 와야 합니다. 타임아웃 시 방화벽 허용 요청 필요.

---

### 1-4. 사전 준비 체크리스트

```
체크   항목
────────────────────────────────────────────────────────
[ ]   JDK 버전 확인 완료
[ ]   JEUS 버전 확인 완료
[ ]   OnePass 서버 URL 수신 (행정안전부 담당자)
[ ]   API Key 수신 (행정안전부 담당자)
[ ]   방화벽 허용 완료 (WAS → OnePass 서버)
[ ]   onepass-agent-1.0.0-all.jar 파일 확보
[ ]   배포 디렉토리 쓰기 권한 보유
[ ]   WAS 재시작 가능한 유지보수 시간 확보
```

---

## WK-02: Agent JAR 및 설정 파일 배포

### 2-1. 배포 디렉토리 생성

```bash
# 배포 디렉토리 생성
sudo mkdir -p /opt/onepass/conf

# WAS 실행 계정에 소유권 부여
# [환경에 따라 수정] jeus 계정명이 다를 수 있음
sudo chown -R jeus:jeus /opt/onepass/
```

### 2-2. Agent JAR 배포

```bash
# JAR 파일 복사
sudo cp onepass-agent-1.0.0-all.jar /opt/onepass/

# 실행 불필요, 읽기 권한만
sudo chmod 644 /opt/onepass/onepass-agent-1.0.0-all.jar

# 무결성 확인 (담당자로부터 체크섬 수령 후)
sha256sum /opt/onepass/onepass-agent-1.0.0-all.jar
# 출력 예시: a3f9e2b1... /opt/onepass/onepass-agent-1.0.0-all.jar
```

### 2-3. 설정 파일 생성

```bash
# [환경에 따라 수정] YOUR_ENDPOINT, YOUR_API_KEY를 실제 값으로 교체
cat > /opt/onepass/conf/onepass-agent.properties << 'EOF'
# OnePass Agent 설정
onepass.agent.endpoint=https://onepass.go.kr
onepass.agent.api-key=op-agency-YOURCODE-YOURKEY
onepass.agent.connect-timeout-ms=5000
onepass.agent.read-timeout-ms=10000
onepass.agent.max-retry=2
onepass.agent.enabled=true
onepass.agent.log-level=INFO
EOF

# 보안: 소유자만 읽기 가능
chmod 600 /opt/onepass/conf/onepass-agent.properties

# 확인
ls -la /opt/onepass/conf/onepass-agent.properties
# 기대 출력: -rw------- 1 jeus jeus ... onepass-agent.properties
```

---

## WK-03: JEUS 4/5 설치 워크스루 (JDK 1.5)

> **환경**: JEUS 4 또는 JEUS 5, JDK 1.5  
> **위빙 방식**: Javassist (premain 정적 어태치 전용)  
> **제약**: 동적 어태치 불가 → WAS 재시작 필수

### 3-1. JEUS 기동 스크립트 백업

```bash
# 원본 백업 (변경 전 반드시!)
cp $JEUS_HOME/bin/jeusboot.sh $JEUS_HOME/bin/jeusboot.sh.backup.$(date +%Y%m%d)

# 백업 확인
ls -la $JEUS_HOME/bin/jeusboot.sh.backup.*
```

### 3-2. JVM 옵션 추가

**방법 A: jeusboot.sh 환경변수 방식**

```bash
# jeusboot.sh 열기
vi $JEUS_HOME/bin/jeusboot.sh

# 파일 상단의 JAVA_OPTS 라인 찾기 (없으면 추가)
# 아래 라인 추가:
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties"
# JEUS 자동 감지 실패 시 명시적 지정 (선택)
JAVA_OPTS="$JAVA_OPTS -Donepass.was.type=JEUS_LEGACY"
```

**방법 B: JEUS 4/5 domain.xml 수정**

```bash
# domain.xml 백업
cp $JEUS_HOME/config/domain.xml $JEUS_HOME/config/domain.xml.backup.$(date +%Y%m%d)

# domain.xml 편집 — <jvm-option> 태그 추가
vi $JEUS_HOME/config/domain.xml
```

```xml
<!-- domain.xml에 아래 내용 추가 (기존 <jvm-config> 블록 내) -->
<jvm-config>
  <!-- [기존 jvm-option들] -->
  
  <!-- OnePass Agent 추가 -->
  <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
  <jvm-option>-Donepass.was.type=JEUS_LEGACY</jvm-option>
</jvm-config>
```

### 3-3. JEUS 재시작

```bash
# JEUS 중지
echo "JEUS 중지 중..."
$JEUS_HOME/bin/jeus_stop.sh
# 또는: jeusadmin -host localhost stop DomainServer

# 10초 대기 (프로세스 완전 종료)
sleep 10

# 프로세스 확인
ps aux | grep jeus | grep -v grep
# 출력 없으면 정상 종료

# JEUS 기동
echo "JEUS 기동 중..."
$JEUS_HOME/bin/jeus_start.sh
# 또는: jeusadmin -host localhost startDAS
```

### 3-4. 기동 로그 실시간 확인

```bash
# 로그 파일 경로 (버전에 따라 다름)
tail -f $JEUS_HOME/logs/JeusServer.log | grep -E "OnePassAgent|JEUS|ERROR|WARN"
```

**✅ 정상 기동 출력**:
```
╔══════════════════════════════════════════════════════════╗
║     OnePass Agency Java Agent v1.0.0 — Starting         ║
║     © 2025 행정안전부 OnePass 통합인증 플랫폼             ║
╚══════════════════════════════════════════════════════════╝
[OnePassAgent] 초기화 시작. agentArgs=config=/opt/onepass/conf/onepass-agent.properties
[AgentConfig] 설정 파일 로드: /opt/onepass/conf/onepass-agent.properties
[AgentConfig] 설정 로드 완료: AgentConfig{endpoint='https://onepass.go.kr', apiKey='op-a****', ...}
[WasDetector] 오버라이드 적용: -Donepass.was.type=JEUS_LEGACY → JEUS 4/5 (Legacy, JDK 1.4~1.5)
[OnePassAgent] WAS 유형 감지: JEUS 4/5 (Legacy, JDK 1.4~1.5)
[JeusLegacyWeaving (JEUS 4/5, Javassist)] 설치 시작 — JEUS 4/5 (JDK 1.5 호환 Javassist 위빙)
[JavassistEngine] Transformer 등록 완료: com.tmax.jeus.web.servlet.HttpServletWrapper
[OnePassAgent] 위빙 설치 완료: JeusLegacyWeaving (JEUS 4/5, Javassist)
[OnePassAgent] 초기화 완료. WAS=JEUS 4/5 (Legacy, JDK 1.4~1.5) / 전략=JeusLegacyWeaving / endpoint=https://onepass.go.kr
```

**❌ 오류 출력 및 대응**:
```
# 오류 1: 설정 파일 없음
[WARN] [AgentConfig] 설정 파일 없음: /opt/onepass/conf/onepass-agent.properties
→ 파일 경로와 권한 확인

# 오류 2: 필수 설정 누락
[ERROR] [OnePassAgent] 설정 로드 실패 — Agent 비활성화: 필수 설정 누락: onepass.agent.endpoint
→ onepass-agent.properties에 endpoint 추가

# 오류 3: 위빙 포인트 미발견
[WARN] 모든 위빙 포인트 실패 — OnePass SSO 기능이 비활성화됩니다
→ -Donepass.was.type=JEUS_LEGACY 명시적 지정 확인
```

---

## WK-04: JEUS 6 설치 워크스루

> **환경**: JEUS 6, JDK 1.5~1.7  
> **위빙 방식**: Javassist

### 4-1. 도메인 설정 파일 확인

```bash
# JEUS 6 도메인 설정 파일 위치
ls $JEUS_HOME/domains/

# 일반적인 경로
cat $JEUS_HOME/domains/jeusdomain/config/domain.xml | head -50
```

### 4-2. domain.xml 수정

```bash
# 백업
cp $JEUS_HOME/domains/jeusdomain/config/domain.xml \
   $JEUS_HOME/domains/jeusdomain/config/domain.xml.backup.$(date +%Y%m%d)

# 수정
vi $JEUS_HOME/domains/jeusdomain/config/domain.xml
```

```xml
<!-- <server> 블록 내 <jvm-config>에 추가 -->
<server>
  <name>server1</name>
  <jvm-config>
    <jvm-option>-Xmx1024m</jvm-option>  <!-- 기존 옵션 유지 -->
    <!-- OnePass Agent 추가 -->
    <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
  </jvm-config>
</server>
```

### 4-3. 재시작 및 확인

```bash
# 도메인 관리 서버 재시작
$JEUS_HOME/bin/stopDomainAdminServer
sleep 5
$JEUS_HOME/bin/startDomainAdminServer

# 서버 재시작
$JEUS_HOME/bin/startManagedServer -server server1

# 로그 확인
grep "OnePassAgent\|Jeus6" $JEUS_HOME/domains/jeusdomain/servers/server1/logs/JeusServer.log

# 기대 출력:
# [OnePassAgent] WAS 유형 감지: JEUS 6 (JDK 1.5~1.7)
# [OnePassAgent] 위빙 설치 완료: Jeus6Weaving (JEUS 6, Javassist)
```

---

## WK-05: JEUS 7/8 설치 워크스루

> **환경**: JEUS 7 또는 8, JDK 1.6~1.8  
> **위빙 방식**: JDK 버전 자동 감지 (JDK 7: Javassist / JDK 8: byte-buddy)

### 5-1. 런타임 JDK 버전 미리 확인

```bash
java -version 2>&1 | head -1
# "1.7..." → Javassist 위빙 예상
# "1.8..." → byte-buddy 위빙 예상
```

### 5-2. domain.xml 수정

```bash
cp $JEUS_HOME/domains/jeusdomain/config/domain.xml \
   $JEUS_HOME/domains/jeusdomain/config/domain.xml.backup.$(date +%Y%m%d)

vi $JEUS_HOME/domains/jeusdomain/config/domain.xml
```

```xml
<jvm-config>
  <!-- 기존 옵션들 -->
  <!-- OnePass Agent -->
  <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
</jvm-config>
```

### 5-3. 재시작 후 엔진 선택 확인

```bash
$JEUS_HOME/bin/stopDomainAdminServer && sleep 5 && $JEUS_HOME/bin/startDomainAdminServer

grep "EngineSelector\|위빙 설치" $JEUS_HOME/domains/jeusdomain/servers/server1/logs/JeusServer.log
```

**JDK 8 환경 기대 출력**:
```
[EngineSelector] JEUS_7 + JDK 8 → BYTE_BUDDY
[OnePassAgent] 위빙 설치 완료: Jeus7PlusWeaving (JEUS 7/8, JDK 8 → byte-buddy)
```

**JDK 7 환경 기대 출력**:
```
[EngineSelector] JEUS_7 + JDK 7 → JAVASSIST (JDK 8 미만)
[OnePassAgent] 위빙 설치 완료: Jeus7PlusWeaving (JEUS 7/8, JDK 7 → Javassist폴백)
```

---

## WK-06: JEUS 8.5/9/21 설치 워크스루

> **환경**: JEUS 8.5 (JDK 8/11), JEUS 9 (JDK 11+), JEUS 21 (JDK 21+)

### 6-1. JDK 17+ 환경 추가 옵션 확인

```bash
java -version 2>&1
# JDK 17+ 이면 --add-opens 옵션 추가 필요
```

### 6-2. domain.xml 수정

```xml
<jvm-config>
  <!-- 기존 옵션들 -->
  
  <!-- OnePass Agent -->
  <jvm-option>-javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
  
  <!-- JDK 11+ 권장 옵션 -->
  <jvm-option>-XX:+EnableDynamicAgentLoading</jvm-option>
  
  <!-- JDK 17+ JPMS 열기 (필요 시) -->
  <!-- <jvm-option>--add-opens=java.base/java.lang=ALL-UNNAMED</jvm-option> -->
</jvm-config>
```

### 6-3. Jakarta EE 9+ 확인 (JEUS 9/21)

```bash
grep "jakarta\|javax\|Jeus8_5Plus" $JEUS_HOME/domains/jeusdomain/servers/server1/logs/JeusServer.log

# 기대 출력:
# [OnePassAgent] WAS 유형 감지: JEUS 9/21 (JDK 11+, Jakarta EE)
# [OnePassAgent] 위빙 설치 완료: Jeus8_5PlusWeaving (JEUS 8.5+, byte-buddy, javax+jakarta)
```

---

## WK-07: Tomcat 설치 워크스루

### 7-1. setenv.sh 생성/수정

```bash
# setenv.sh 없으면 생성
cat > $CATALINA_HOME/bin/setenv.sh << 'EOF'
#!/bin/bash
# Tomcat 환경 설정

# OnePass Agent
CATALINA_OPTS="$CATALINA_OPTS -javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/opt/onepass/conf/onepass-agent.properties"
EOF

chmod +x $CATALINA_HOME/bin/setenv.sh
```

### 7-2. Tomcat 재시작

```bash
$CATALINA_HOME/bin/shutdown.sh
sleep 5
$CATALINA_HOME/bin/startup.sh

grep "OnePassAgent\|TomcatValve" $CATALINA_HOME/logs/catalina.out
```

**기대 출력**:
```
[OnePassAgent] WAS 유형 감지: Tomcat
[OnePassAgent] 위빙 설치 완료: TomcatValveWeaving
```

---

## WK-08: 동작 검증 워크스루

### 8-1. 기본 SSO 검증 시나리오

Agent 설치 후 아래 3가지 시나리오를 순서대로 테스트합니다.

#### 시나리오 A: 토큰 없는 요청 → 401 기대

```bash
# 보호된 리소스에 토큰 없이 요청
curl -s -o /dev/null -w "%{http_code}" \
  http://[WAS주소]:[포트]/[앱컨텍스트]/[보호된URL]

# 기대 출력: 401
```

#### 시나리오 B: 만료된 토큰 → 401 기대

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -H "X-OnePass-Token: EXPIRED_TOKEN_STRING" \
  http://[WAS주소]:[포트]/[앱컨텍스트]/[보호된URL]

# 기대 출력: 401
```

#### 시나리오 C: 유효한 토큰 → 200 기대

```bash
# OnePass 서버에서 테스트용 토큰 발급 (행정안전부 담당자로부터)
VALID_TOKEN="eyJhbGc..."

curl -s -o /dev/null -w "%{http_code}" \
  -H "X-OnePass-Token: $VALID_TOKEN" \
  http://[WAS주소]:[포트]/[앱컨텍스트]/[보호된URL]

# 기대 출력: 200
```

---

### 8-2. 상세 응답 확인

```bash
# 헤더와 바디 모두 확인
curl -v \
  -H "X-OnePass-Token: INVALID_TOKEN" \
  http://[WAS주소]:[포트]/[앱컨텍스트]/[보호된URL]
```

**기대 출력 (401)**:
```
< HTTP/1.1 401 Unauthorized
< Content-Type: text/html;charset=UTF-8
< 
<!DOCTYPE html><html>...<h1>401 Unauthorized</h1>...
```

---

### 8-3. 헬스체크 확인 (WAS 기동 30초 후)

```bash
# WAS 기동 30초 후 로그 확인
grep "OnePass 서버 연결" $JEUS_HOME/logs/JeusServer.log

# 성공 시:
# [OnePassAgent] OnePass 서버 연결 확인: HTTP 200

# 실패 시 (방화벽 차단 의심):
# [WARN] [OnePassAgent] OnePass 서버 연결 실패 (비치명적): Connection refused
```

---

### 8-4. 검증 체크리스트

```
체크   항목                                              기대 결과
────────────────────────────────────────────────────────────────────────
[ ]   WAS 기동 로그에 "초기화 완료" 출력                ✅
[ ]   감지된 WAS 유형이 정확한지 확인                   ✅
[ ]   토큰 없는 요청 → HTTP 401                        ✅
[ ]   만료 토큰 요청 → HTTP 401                        ✅
[ ]   유효 토큰 요청 → HTTP 200                        ✅
[ ]   헬스체크 로그에 "HTTP 200" 출력                  ✅
```

---

## WK-09: 운영 모니터링 워크스루

### 9-1. 실시간 로그 모니터링

```bash
# OnePass Agent 관련 로그만 필터링
tail -f $JEUS_HOME/logs/JeusServer.log | grep -E "OnePassAgent|OnePass|401|403"

# 전체 오류 모니터링
tail -f $JEUS_HOME/logs/JeusServer.log | grep -E "ERROR|WARN.*OnePass"
```

---

### 9-2. 로그 패턴별 의미 해석

| 로그 패턴 | 의미 | 조치 |
|---------|------|------|
| `[OnePassAgent] 초기화 완료` | 정상 | 없음 |
| `[WARN] OnePass 서버 연결 실패` | 네트워크 문제 (개별 요청 재시도) | 방화벽 확인 |
| `[WARN] 위빙 설치 실패` | 위빙 미동작 (WAS는 정상) | WAS 유형 강제 지정 |
| `[ERROR] 설정 로드 실패` | Agent 비활성화 | 설정 파일 확인 |
| `HTTP 401` 폭증 | 토큰 만료/무효 급증 | OnePass 서버 상태 확인 |

---

### 9-3. 정기 상태 점검

```bash
#!/bin/bash
# /opt/onepass/check-agent-status.sh
# 매일 09:00 cron 실행 권장

LOG_FILE="$JEUS_HOME/logs/JeusServer.log"
TODAY=$(date +%Y-%m-%d)

echo "=== OnePass Agent 상태 점검 ($TODAY) ==="

# 1. 초기화 성공 여부
INIT=$(grep -c "초기화 완료" $LOG_FILE 2>/dev/null)
echo "초기화 성공: ${INIT}회"

# 2. 오류 횟수
ERRORS=$(grep -c "ERROR.*OnePassAgent" $LOG_FILE 2>/dev/null)
echo "오류 발생: ${ERRORS}회"

# 3. 서버 연결 실패 횟수
CONN_FAIL=$(grep -c "OnePass 서버 연결 실패" $LOG_FILE 2>/dev/null)
echo "서버 연결 실패: ${CONN_FAIL}회"

# 4. 검증 실패 (401) 횟수
AUTH_FAIL=$(grep -c "HTTP 401\|sendError(401" $LOG_FILE 2>/dev/null)
echo "인증 실패(401): ${AUTH_FAIL}회"

echo "================================"
```

---

## WK-10: Agent 업그레이드 워크스루

### 10-1. 새 버전 JAR 배포

```bash
# 기존 JAR 백업
cp /opt/onepass/onepass-agent-1.0.0-all.jar \
   /opt/onepass/onepass-agent-1.0.0-all.jar.backup.$(date +%Y%m%d)

# 새 버전 배포
cp onepass-agent-1.1.0-all.jar /opt/onepass/

# 심링크 방식 (권장 — 롤백 용이)
ln -sf /opt/onepass/onepass-agent-1.1.0-all.jar \
       /opt/onepass/onepass-agent-current.jar
```

### 10-2. domain.xml 경로 심링크 활용 시

```xml
<!-- 심링크를 사용하면 JAR 교체 시 domain.xml 수정 불필요 -->
<jvm-option>-javaagent:/opt/onepass/onepass-agent-current.jar=config=/opt/onepass/conf/onepass-agent.properties</jvm-option>
```

### 10-3. WAS 재시작 후 버전 확인

```bash
grep "OnePass Agency Java Agent v" $JEUS_HOME/logs/JeusServer.log | tail -1
# 기대: ║     OnePass Agency Java Agent v1.1.0 — Starting         ║
```

### 10-4. 롤백 절차

```bash
# 이전 버전으로 복원
ln -sf /opt/onepass/onepass-agent-1.0.0-all.jar \
       /opt/onepass/onepass-agent-current.jar

# WAS 재시작
$JEUS_HOME/bin/stopDomainAdminServer && \
$JEUS_HOME/bin/startDomainAdminServer
```

---

## WK-11: Agent 제거 워크스루

### 11-1. domain.xml에서 jvm-option 제거

```bash
vi $JEUS_HOME/domains/jeusdomain/config/domain.xml
# 아래 라인 삭제:
# <jvm-option>-javaagent:/opt/onepass/...</jvm-option>
# <jvm-option>-Donepass.was.type=...</jvm-option>
```

### 11-2. WAS 재시작

```bash
$JEUS_HOME/bin/stopDomainAdminServer && \
sleep 5 && \
$JEUS_HOME/bin/startDomainAdminServer

# Agent 미동작 확인 (로그에 배너 없음)
grep "OnePassAgent" $JEUS_HOME/logs/JeusServer.log
# 출력 없으면 정상 제거
```

### 11-3. 파일 정리

```bash
# Agent 파일 제거
rm -rf /opt/onepass/

# 확인
ls /opt/onepass/ 2>/dev/null || echo "정상 제거됨"
```

---

## 워크스루 완료 체크리스트

```
설치 완료 체크리스트:

환경 확인
[ ] JDK 버전 확인
[ ] JEUS 버전 확인
[ ] 네트워크 연결 확인

파일 배포
[ ] /opt/onepass/onepass-agent-1.0.0-all.jar 배포
[ ] /opt/onepass/conf/onepass-agent.properties 생성
[ ] 설정 파일 권한 600 설정

WAS 설정
[ ] domain.xml (또는 기동 스크립트)에 -javaagent: 추가
[ ] WAS 재시작 완료

검증
[ ] 기동 로그에 "초기화 완료" 확인
[ ] 토큰 없는 요청 → 401 확인
[ ] 유효 토큰 요청 → 200 확인
[ ] 헬스체크 로그 확인
```
