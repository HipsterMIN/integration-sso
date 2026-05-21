# OnePass 자체 SSO 기관 연동 — 운영 가이드

> **대상 독자**: 운영 개발자, 인프라 엔지니어, SRE
> **버전**: v1.0 (2026-05-17)
> **전제**: 개발자 레퍼런스(`docs/sso-agency-developer-guide.md`) 숙지 필요

---

## 목차

1. [컴포넌트 배포 구조](#1-컴포넌트-배포-구조)
2. [Agent 배포 및 관리](#2-agent-배포-및-관리)
3. [SDK 기반 서비스 배포](#3-sdk-기반-서비스-배포)
4. [모니터링 — 핵심 메트릭 및 알람](#4-모니터링--핵심-메트릭-및-알람)
5. [장애 시나리오 및 대응 절차](#5-장애-시나리오-및-대응-절차)
6. [롤링 배포 및 Agent 무중단 처리](#6-롤링-배포-및-agent-무중단-처리)
7. [설정 변경 관리](#7-설정-변경-관리)
8. [보안 운영 절차](#8-보안-운영-절차)
9. [로그 분석 및 문제 진단](#9-로그-분석-및-문제-진단)
10. [긴급 대응 런북](#10-긴급-대응-런북)

---

## 1. 컴포넌트 배포 구조

### 1.1 전체 배포 토폴로지

```
┌─────────────────────────────────────────────────────────────────┐
│                         기관 인프라                               │
│                                                                   │
│  ┌─────────────────┐     ┌──────────────────────────────────┐    │
│  │  기관 SSO 서버   │     │      기관 애플리케이션 서버          │    │
│  │  (기존 운영)     │     │                                  │    │
│  │                 │     │  [JVM Process]                   │    │
│  │  LDAP/SAML/     │     │    ├── 기관 애플리케이션 WAR/JAR  │    │
│  │  OAuth 기반     │     │    └── onepass-agent.jar (주입됨) │    │
│  └─────────────────┘     │                                  │    │
│                           │  onepass-agent.properties        │    │
│                           │  (설정 파일 — 외부 마운트)        │    │
│                           └─────────────────┬────────────────┘    │
│                                             │                     │
└─────────────────────────────────────────────┼─────────────────────┘
                                              │ HTTPS
                                              ▼
                               ┌──────────────────────────┐
                               │  OnePass IdO 서버          │
                               │  ido.onepass.go.kr:443    │
                               └──────────┬───────────────┘
                                          │
                               ┌──────────▼───────────────┐
                               │  Q-IM 서버                │
                               │  (회원 전환 관리)          │
                               └──────────────────────────┘
```

### 1.2 컴포넌트 버전 매트릭스

| 컴포넌트 | 현재 버전 | 최소 요구 JDK | 비고 |
|---------|---------|-------------|------|
| `onepass-agent.jar` | 1.0.0 | JDK 8 | byte-buddy 위빙 |
| `onepass-agency-sdk` | 1.0.0 | JDK 8 | 런타임 의존성 ZERO |
| agency-stub (테스트용) | 1.0.0 | JDK 17 | 운영에 배포하지 않음 |

### 1.3 포트 및 엔드포인트

| 서비스 | 포트 | 프로토콜 | 용도 |
|-------|------|---------|------|
| IdO (OnePass) | 443 | HTTPS | Handoff 발급/검증, Gateway API |
| Q-IM (OnePass) | 443 | HTTPS | 회원 전환 API |
| 기관 API 서버 | 443 | HTTPS | lookup/link API (인바운드) |

---

## 2. Agent 배포 및 관리

### 2.1 Agent 파일 배치

```bash
# 권장 디렉토리 구조
/opt/onepass/
├── onepass-agent.jar          # Agent JAR (버전 관리)
└── onepass-agent-{version}.jar  # 버전별 보관

/etc/onepass/
└── onepass-agent.properties   # 설정 파일 (외부 마운트, 비밀정보 포함)

/var/log/onepass/
└── onepass-agent.log          # Agent 로그 (stdout 리다이렉트)
```

```bash
# 디렉토리 생성 및 권한 설정
sudo mkdir -p /opt/onepass /etc/onepass /var/log/onepass
sudo chown -R {WAS사용자}:{WAS그룹} /opt/onepass /etc/onepass /var/log/onepass
sudo chmod 750 /etc/onepass          # 설정 파일 디렉토리 — 그룹 접근만 허용
sudo chmod 640 /etc/onepass/*.properties  # API 키 보호
```

### 2.2 설정 파일 관리

```properties
# /etc/onepass/onepass-agent.properties

# ── 필수 설정 ──────────────────────────────────────────────────────
onepass.agent.endpoint=https://ido.onepass.go.kr
onepass.agent.api-key=${ONEPASS_API_KEY}  # 환경변수 참조 권장

# ── 타임아웃 설정 ────────────────────────────────────────────────────
onepass.agent.connect-timeout-ms=5000
onepass.agent.read-timeout-ms=10000
onepass.agent.max-retry=2

# ── 운영 설정 ────────────────────────────────────────────────────────
onepass.agent.enabled=true
onepass.agent.log-level=WARN        # 운영 환경: WARN (INFO는 로그 과다)

# ── 바이패스 URI (운영 환경에 맞게 조정) ────────────────────────────
# 현재 버전(v1.0): 하드코딩됨 (향후 v1.1에서 설정 가능)
# /actuator/**, /health, /favicon.ico, 정적 파일 확장자

# ── HMAC 서명 (Sprint 17 Phase 4 이후 필수) ─────────────────────────
# onepass.agent.hmac-secret=${ONEPASS_HMAC_SECRET}
```

> **보안 주의**: `api-key`와 `hmac-secret`을 평문으로 파일에 저장하지 말 것. 환경변수, Vault, AWS Secrets Manager 등을 활용하세요.

### 2.3 JVM 인수 설정 방법별

**Tomcat (setenv.sh)**:
```bash
# /opt/tomcat/bin/setenv.sh
export ONEPASS_API_KEY="$(cat /run/secrets/onepass_api_key)"

JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/onepass/onepass-agent.jar=config=/etc/onepass/onepass-agent.properties \
  -Donepass.agent.endpoint=https://ido.onepass.go.kr"
```

**JEUS (startDomainAdminServer.sh)**:
```xml
<!-- domain.xml JVM 설정 -->
<jvm-option>-javaagent:/opt/onepass/onepass-agent.jar=config=/etc/onepass/onepass-agent.properties</jvm-option>
```

**JBoss/WildFly (standalone.conf)**:
```bash
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/onepass/onepass-agent.jar=config=/etc/onepass/onepass-agent.properties"
```

**WebLogic (setDomainEnv.sh)**:
```bash
JAVA_OPTIONS="${JAVA_OPTIONS} -javaagent:/opt/onepass/onepass-agent.jar=config=/etc/onepass/onepass-agent.properties"
```

**Docker 컨테이너**:
```dockerfile
FROM {기관_베이스_이미지}

# Agent 복사
COPY onepass-agent.jar /opt/onepass/onepass-agent.jar

# 환경변수로 설정 주입
ENV ONEPASS_API_KEY=""
ENV ONEPASS_AGENT_ENDPOINT="https://ido.onepass.go.kr"

# JVM 옵션에 Agent 추가
ENV JAVA_OPTS="-javaagent:/opt/onepass/onepass-agent.jar \
               -Donepass.agent.endpoint=${ONEPASS_AGENT_ENDPOINT} \
               -Donepass.agent.api-key=${ONEPASS_API_KEY}"
```

**Kubernetes (Deployment)**:
```yaml
spec:
  containers:
  - name: agency-app
    image: agency-app:latest
    env:
    - name: ONEPASS_API_KEY
      valueFrom:
        secretKeyRef:
          name: onepass-secrets
          key: api-key
    - name: JAVA_OPTS
      value: >-
        -javaagent:/opt/onepass/onepass-agent.jar
        -Donepass.agent.endpoint=https://ido.onepass.go.kr
        -Donepass.agent.api-key=$(ONEPASS_API_KEY)
    volumeMounts:
    - name: onepass-agent
      mountPath: /opt/onepass
  volumes:
  - name: onepass-agent
    configMap:
      name: onepass-agent-jar
```

### 2.4 Agent 시작 확인

```bash
# Agent 로드 확인 (WAS 시작 로그에서 확인)
grep "OnePassAgent" /var/log/tomcat/catalina.out
# 기대 출력:
# [OnePassAgent] premain 시작
# [AgentConfig] 설정 로드 완료: AgentConfig{endpoint='https://...', ...}
# [GenericServletFilterWeaving] byte-buddy AgentBuilder 설치 시작
# [GenericServletFilterWeaving] 설치 완료 (javax + jakarta 이중 지원)

# Agent 활성화 여부 확인
grep "AgentConfig" /var/log/tomcat/catalina.out | grep "enabled=true"
```

---

## 3. SDK 기반 서비스 배포

### 3.1 설정 외재화

SDK 설정을 소스코드에 하드코딩하지 말고, 외부 설정 파일 또는 환경변수를 사용합니다.

**Spring Boot application.yml**:
```yaml
onepass:
  ido:
    base-url: ${ONEPASS_IDO_BASE_URL:https://ido.onepass.go.kr}
  agency:
    api-key: ${ONEPASS_AGENCY_API_KEY}
    code: ${ONEPASS_AGENCY_CODE:AGENCY_001}
    hmac-secret: ${ONEPASS_HMAC_SECRET:}
    sign-requests: ${ONEPASS_SIGN_REQUESTS:false}
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
```

### 3.2 배포 전 확인 사항

```bash
# SDK 연결 상태 확인 (getStatus API)
curl -X GET https://ido.onepass.go.kr/api/v1/agency/gateway/status/AGENCY_001 \
  -H "X-Agency-Key: ${ONEPASS_API_KEY}" \
  -H "X-Agency-Code: AGENCY_001"

# 기대 응답: {"status": "ACTIVE", "agencyCode": "AGENCY_001", ...}
```

### 3.3 SDK 버전 업그레이드 절차

```bash
# 1. 스테이징 환경 먼저 적용
./gradlew dependencies | grep onepass-agency-sdk

# 2. 주요 변경사항 확인 (CHANGELOG 참조)
# - 하위 호환성 확인 필수
# - API Key / HMAC 설정 변경 여부 확인

# 3. 단위 테스트 실행
./gradlew test --tests "*AgencyGateway*" --tests "*HandoffVerify*"

# 4. 운영 배포 (롤링)
```

---

## 4. 모니터링 — 핵심 메트릭 및 알람

### 4.1 기관 측 모니터링 항목

| 메트릭 | 임계치 | 알람 수준 | 조치 |
|-------|--------|---------|------|
| lookup API 응답시간 | > 10초 | WARN | DBA 확인, 인덱스 점검 |
| lookup API 응답시간 | > 14초 | CRITICAL | 즉시 대응 (Q-IM 타임아웃 15초) |
| lookup API 에러율 | > 1% | WARN | 로그 분석 |
| lookup API 에러율 | > 5% | CRITICAL | 서비스 점검 |
| link API 에러율 | > 0.1% | WARN | 로그 분석, DB 확인 |
| ci_hash 컬럼 NULL 비율 | 신규 가입자 > 5% | INFO | CI 수집 로직 점검 |

### 4.2 Prometheus 메트릭 설정 예시 (Spring Boot Actuator)

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: agency-onepass-api
```

```java
// lookup API 메트릭 계측
@PostMapping("/lookup")
public ResponseEntity<MemberLookupResponse> lookup(@RequestBody MemberLookupRequest req) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
        // ... 비즈니스 로직
        sample.stop(Timer.builder("onepass.lookup.duration")
            .tag("agency", agencyCode)
            .tag("result", found ? "found" : "not_found")
            .register(meterRegistry));
        return ResponseEntity.ok(response);
    } catch (Exception e) {
        meterRegistry.counter("onepass.lookup.error",
            "agency", agencyCode, "error", e.getClass().getSimpleName()).increment();
        throw e;
    }
}
```

### 4.3 Grafana 대시보드 — 권장 패널

```
Row 1: 연동 상태 개요
  - lookup API 성공률 (%) — 목표: 99.9%
  - link API 성공률 (%) — 목표: 99.9%
  - lookup API P95 응답시간 (ms) — 목표: < 5000ms
  - 전환 완료 건수 (일/주/월)

Row 2: 회원 전환 현황
  - 전환 진행 중 세션 수 (상태별)
  - 전환 완료율 (%) 
  - 전환 실패 원인 분포

Row 3: Agent 상태
  - 토큰 검증 요청/분 (TPS)
  - 401 응답 발생 빈도
  - Agent 예외 발생 로그 (warn 이상)
```

### 4.4 알람 설정 예시 (Alertmanager)

```yaml
# prometheus-alerts.yml
groups:
- name: onepass-agency-alerts
  rules:
  - alert: OnePassLookupHighLatency
    expr: histogram_quantile(0.95, rate(onepass_lookup_duration_bucket[5m])) > 10
    for: 2m
    labels:
      severity: warning
    annotations:
      summary: "lookup API P95 응답시간 10초 초과"
      description: "Q-IM 타임아웃(15초) 위험. DBA에게 인덱스 점검 요청하세요."

  - alert: OnePassLookupCriticalLatency
    expr: histogram_quantile(0.95, rate(onepass_lookup_duration_bucket[5m])) > 14
    for: 1m
    labels:
      severity: critical
    annotations:
      summary: "lookup API P95 응답시간 14초 초과 — Q-IM 타임아웃 임박"
      description: "즉시 대응. 회원 전환 기능 비활성화 고려."

  - alert: OnePassLinkErrorRate
    expr: rate(onepass_lookup_error_total[5m]) / rate(onepass_lookup_duration_count[5m]) > 0.05
    for: 3m
    labels:
      severity: critical
    annotations:
      summary: "link API 에러율 5% 초과"
```

---

## 5. 장애 시나리오 및 대응 절차

### 5.1 시나리오 1: OnePass IdO 서버 다운

**영향**: 
- 원패스로 로그인 불가
- 회원 전환 불가
- Agent 토큰 검증 실패 → **Fail-Open** 정책으로 기관 서비스는 정상 작동 (토큰 없으면 통과)

**대응**:
```bash
# 1. OnePass 서버 상태 확인
curl -I https://ido.onepass.go.kr/actuator/health
# 또는
curl -I https://status.onepass.go.kr

# 2. Agent 임시 비활성화 (Fail-Open이지만 불필요한 오류 로그 방지)
# 방법 A: 설정 파일 변경 후 WAS 재시작
sed -i 's/onepass.agent.enabled=true/onepass.agent.enabled=false/' \
    /etc/onepass/onepass-agent.properties
# WAS 재시작 필요 (설정 파일 변경은 재시작 없이 즉시 반영 안 됨)

# 방법 B: JVM 시스템 프로퍼티 동적 변경 (WAS 지원 시)
# -Donepass.agent.enabled=false 추가 후 재시작

# 3. 사용자 공지
# "원패스 로그인 기능이 일시 중단되었습니다. 기관 아이디/비밀번호로 로그인하세요."
```

**복구**:
```bash
# OnePass 서버 정상화 확인 후
curl https://ido.onepass.go.kr/api/v1/agency/gateway/status/AGENCY_001 \
    -H "X-Agency-Key: ${ONEPASS_API_KEY}"

# Agent 재활성화
sed -i 's/onepass.agent.enabled=false/onepass.agent.enabled=true/' \
    /etc/onepass/onepass-agent.properties
# WAS 재시작
```

### 5.2 시나리오 2: lookup API 타임아웃 (Q-IM 연결 실패)

**현상**: Q-IM 서버 로그에 기관 lookup API 15초 초과 기록, 해당 기관 사용자 전환 실패

**진단**:
```sql
-- lookup API 쿼리 실행 계획 확인
EXPLAIN ANALYZE
SELECT agency_user_id, status, last_login_at
FROM agency_user
WHERE agency_subject_id = 'test-hash' AND status = 'ACTIVE';

-- 인덱스 존재 확인
SHOW INDEX FROM agency_user WHERE Key_name LIKE '%subject%';
-- 없으면: CREATE INDEX idx_agency_subject ON agency_user(agency_subject_id, status);

-- 테이블 통계 최신화
ANALYZE TABLE agency_user;

-- 실행 중인 락 확인
SHOW PROCESSLIST;
SELECT * FROM INFORMATION_SCHEMA.INNODB_LOCKS;
```

**즉각 조치**:
```bash
# 인덱스 없는 경우 즉시 생성 (테이블 크기에 따라 수 분 소요)
mysql -u root -p << 'EOF'
CREATE INDEX idx_agency_subject_status
ON agency_user(agency_subject_id, status);
EOF

# 인덱스 생성 진행 상황 모니터링
SHOW PROCESSLIST;
```

### 5.3 시나리오 3: API 키 유출 의심

**즉각 조치 (1시간 이내)**:

```bash
# 1. 원패스 연동 지원팀에 즉시 연락
# "기관 코드 AGENCY_001의 API 키 즉시 비활성화 요청"

# 2. 현재 API 키를 사용한 비정상 호출 로그 확인
grep "X-Agency-Key: ${OLD_API_KEY}" /var/log/access.log | tail -1000

# 3. Agent 비활성화 (새 키 발급 전까지)
echo "onepass.agent.enabled=false" >> /etc/onepass/onepass-agent.properties
# WAS 재시작

# 4. 새 API 키 수령 후 교체
sed -i "s/onepass.agent.api-key=.*/onepass.agent.api-key=${NEW_API_KEY}/" \
    /etc/onepass/onepass-agent.properties
# WAS 재시작

# 5. SDK 설정도 동일하게 교체
# 환경변수 업데이트 후 서비스 재시작
```

### 5.4 시나리오 4: link API 중복 실행으로 qim_user_id 덮어쓰기

**현상**: 동일한 `identifierHash`에 다른 `qim_user_id`가 저장되어 사용자 연결 오류

**진단**:
```sql
-- 중복 qim_user_id 확인
SELECT qim_user_id, COUNT(*) as cnt
FROM agency_user
WHERE qim_user_id IS NOT NULL
GROUP BY qim_user_id HAVING cnt > 1;

-- 특정 사용자 히스토리 확인 (감사 로그가 있는 경우)
SELECT * FROM agency_user_audit_log
WHERE agency_subject_id = 'hash-value'
ORDER BY changed_at DESC;
```

**예방 조치** (link API 멱등성 강화):
```java
// 기존 qim_user_id가 있으면 덮어쓰지 않도록 처리
@Modifying
@Query(value = """
    UPDATE agency_user
    SET qim_user_id = :qimUserId, updated_at = NOW()
    WHERE agency_subject_id = :identifierHash
      AND agency_code = :agencyCode
      AND (qim_user_id IS NULL OR qim_user_id = :qimUserId)
    """, nativeQuery = true)
int linkQimUserIdempotent(
    @Param("qimUserId") String qimUserId,
    @Param("identifierHash") String identifierHash,
    @Param("agencyCode") String agencyCode);
```

### 5.5 시나리오 5: Agent 위빙 실패 (byte-buddy 오류)

**현상**: WAS 시작 로그에 `WeavingInstallException` 발생

**진단**:
```bash
grep "WeavingInstallException\|byte-buddy\|OnePassAgent" /var/log/tomcat/catalina.out

# 일반적인 원인:
# 1. JDK 버전 불일치 (JDK 7 이하 사용)
java -version

# 2. 이미 다른 Agent가 Filter 클래스를 위빙 중
grep "javaagent" /proc/{PID}/cmdline | tr '\0' '\n'

# 3. 보안 매니저가 위빙 차단
grep "SecurityManager\|AccessControlException" /var/log/tomcat/catalina.out
```

**조치**:
```bash
# Fail-Open 정책 확인 — Agent 위빙 실패 시 서비스는 정상 작동해야 함
# (Agent 없이 기관 서비스가 동작하는지 확인)
curl -I https://{기관서버}/health

# Agent 완전 제거 후 재기동
# setenv.sh에서 -javaagent 옵션 제거 후 WAS 재시작
# → 서비스 정상화 후 Agent 문제 별도 분석
```

---

## 6. 롤링 배포 및 Agent 무중단 처리

### 6.1 Agent 버전 업그레이드 절차

Agent JAR는 JVM 시작 시 단 1회 로드된다. 업그레이드는 반드시 WAS 재시작이 필요하다.

```bash
# 1. 새 Agent JAR 배포
cp onepass-agent-{new-version}.jar /opt/onepass/
ln -sf /opt/onepass/onepass-agent-{new-version}.jar /opt/onepass/onepass-agent.jar

# 2. 헬스체크 URL 확인
curl -I https://{기관서버}/health

# 3. 롤링 재시작 (L4/L7 로드밸런서 연동)
# 노드별로 순차 재시작
for NODE in node1 node2 node3; do
    echo "=== $NODE 재시작 시작 ==="
    # LB에서 해당 노드 제외
    {LB 관리 명령}  
    
    # WAS 재시작
    ssh $NODE "sudo systemctl restart tomcat"
    
    # 헬스체크 통과 대기
    until curl -sf https://$NODE/health; do sleep 5; done
    
    # LB 복귀
    {LB 관리 명령}
    echo "=== $NODE 재시작 완료 ==="
    sleep 30  # 다음 노드 전 안정화 대기
done

# 4. Agent 로드 확인
for NODE in node1 node2 node3; do
    ssh $NODE "grep 'GenericServletFilterWeaving.*설치 완료' /var/log/tomcat/catalina.out | tail -1"
done
```

### 6.2 SDK 버전 업그레이드 (Zero-Downtime)

SDK는 애플리케이션 JAR 내에 포함되므로, 애플리케이션 롤링 배포로 처리한다.

```bash
# 1. 스테이징 환경 검증 (필수)
./gradlew test --tests "*AgencyGateway*"

# 2. 카나리 배포 (트래픽 5% → 30% → 100%)
# 쿠버네티스 카나리:
kubectl set image deployment/agency-app \
    agency-app=agency-app:{new-version} --record

# 3. 에러율 모니터링
watch -n 5 'curl -s http://prometheus/api/v1/query?query=rate(onepass_lookup_error_total[5m])'

# 4. 정상 확인 후 전체 배포
```

---

## 7. 설정 변경 관리

### 7.1 API 키 교체 절차

```bash
# 1. 사전 확인: 현재 키가 정상 동작하는지 확인
curl https://ido.onepass.go.kr/api/v1/agency/gateway/status/AGENCY_001 \
    -H "X-Agency-Key: ${CURRENT_API_KEY}" | jq '.status'

# 2. 새 키 유효성 확인 (교체 전 병행 테스트)
curl https://ido.onepass.go.kr/api/v1/agency/gateway/status/AGENCY_001 \
    -H "X-Agency-Key: ${NEW_API_KEY}" | jq '.status'

# 3. 설정 파일 업데이트 (Agent)
sudo sed -i "s/^onepass.agent.api-key=.*/onepass.agent.api-key=${NEW_API_KEY}/" \
    /etc/onepass/onepass-agent.properties

# 4. 환경변수 업데이트 (SDK)
# AWS: aws secretsmanager update-secret ...
# Vault: vault kv put secret/onepass api-key="${NEW_API_KEY}"

# 5. 롤링 재시작 (Agent는 재시작 필요, SDK 환경변수는 재시작 필요)
# 섹션 6.1 절차 따름

# 6. 구 키 비활성화 (원패스 관리자에게 요청)
```

### 7.2 bypass-uris 추가 (현재 v1.0: 하드코딩, v1.1: 설정 가능)

**현재 버전(v1.0)에서 임시 우회 방법**:
```java
// 기관 필터에서 Agent 위빙 이전에 특정 URI를 early-return
// (Agent는 Servlet Filter를 위빙하므로, 더 이른 지점에서 처리)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PreAgentBypassFilter extends OncePerRequestFilter {
    
    private static final List<String> BYPASS_PREFIXES = Arrays.asList(
        "/sso/", "/saml/", "/oauth/", "/actuator/"
    );
    
    @Override
    protected void doFilterInternal(HttpServletRequest req, 
                                     HttpServletResponse res, 
                                     FilterChain chain) {
        String uri = req.getRequestURI();
        if (BYPASS_PREFIXES.stream().anyMatch(uri::startsWith)) {
            // Agent가 위빙하는 Filter 이전에 처리
            chain.doFilter(req, res);
            return;
        }
        chain.doFilter(req, res);
    }
}
```

---

## 8. 보안 운영 절차

### 8.1 API 키 보관 원칙

```
✅ 권장:
  - AWS Secrets Manager / HashiCorp Vault에 저장
  - 환경변수로 주입 (컨테이너/프로세스 레벨)
  - 설정 파일은 600 권한 (소유자만 읽기)
  - API 키 로테이션: 6개월마다

❌ 금지:
  - 소스코드에 하드코딩
  - Git 저장소에 커밋
  - 이메일/메신저로 공유
  - 로그에 출력 (AgentConfig.toString()은 마스킹하지만 주의)
```

### 8.2 정기 보안 점검 항목

```
월간 점검:
□ API 키 노출 여부 (Git 이력 포함)
  git log --all -S "api-key" -- *.properties *.yml *.yaml
□ lookup/link API 비정상 호출 패턴 확인
□ Q-IM → 기관 API 호출 IP 허용 목록 최신화

분기 점검:
□ API 키 로테이션 실행
□ HMAC 비밀키 로테이션 (사용 시)
□ TLS 인증서 만료일 확인 (갱신 30일 전 준비)
□ Agent JAR 체크섬 검증
```

### 8.3 API 키 체크섬 검증

```bash
# Agent JAR 무결성 검증
sha256sum /opt/onepass/onepass-agent.jar
# → 배포 시 제공된 체크섬과 비교

# 예: 배포 매니페스트에서 체크섬 가져오기
EXPECTED_CHECKSUM=$(curl -sf https://releases.onepass.go.kr/agent/1.0.0/SHA256SUMS)
ACTUAL_CHECKSUM=$(sha256sum /opt/onepass/onepass-agent.jar | awk '{print $1}')
[ "${ACTUAL_CHECKSUM}" = "${EXPECTED_CHECKSUM}" ] && echo "OK" || echo "CHECKSUM MISMATCH!"
```

---

## 9. 로그 분석 및 문제 진단

### 9.1 Agent 로그 패턴

```bash
# Agent 정상 시작 확인
grep -E "\[OnePassAgent\]|\[AgentConfig\]|\[GenericServletFilter" /var/log/tomcat/catalina.out | head -20

# 토큰 검증 실패 (정상 사용자 차단 여부 확인)
grep "인증 실패 → HTTP 401" /var/log/tomcat/catalina.out | wc -l
grep "인증 실패 → HTTP 401" /var/log/tomcat/catalina.out | awk '{print $NF}' | sort | uniq -c

# 토큰 검증 중 예외 (Agent 오류)
grep "\[WARN\] \[GenericFilterAdvice\]" /var/log/tomcat/catalina.out | tail -50

# 실시간 모니터링
tail -f /var/log/tomcat/catalina.out | grep -E "OnePassAgent|401|WARN.*Advice"
```

### 9.2 lookup/link API 접근 로그

```bash
# Q-IM 서버의 기관 API 호출 로그 (기관 웹서버 액세스 로그)
grep "POST /api/v1/members/lookup" /var/log/nginx/access.log | \
    awk '{print $7, $9}' | sort | uniq -c

# 응답시간 분포 확인 (Nginx $request_time)
grep "POST /api/v1/members/lookup" /var/log/nginx/access.log | \
    awk '{print $NF}' | sort -n | \
    awk 'BEGIN{sum=0;cnt=0}{sum+=$1;cnt++}END{print "avg:", sum/cnt, "max:", $1}'

# 에러 응답 패턴
grep "POST /api/v1/members/" /var/log/nginx/access.log | \
    grep -v " 200 \| 201 \| 202 " | tail -100
```

### 9.3 회원 전환 상태 조회

```sql
-- 전환 진행 현황 (agency_user 기준)
SELECT 
    CASE 
        WHEN qim_user_id IS NOT NULL THEN '전환완료'
        WHEN ci_hash IS NOT NULL THEN '전환대기(ci_hash있음)'
        ELSE '미전환'
    END AS conversion_status,
    COUNT(*) AS user_count,
    ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER(), 2) AS pct
FROM agency_user
WHERE status = 'ACTIVE'
GROUP BY 1;

-- 최근 전환 완료 목록
SELECT agency_user_id, updated_at
FROM agency_user
WHERE qim_user_id IS NOT NULL
ORDER BY updated_at DESC
LIMIT 20;

-- 전환 실패 의심 (ci_hash 있고 qim_user_id 없는 30일 초과)
SELECT COUNT(*)
FROM agency_user
WHERE ci_hash IS NOT NULL
  AND qim_user_id IS NULL
  AND updated_at < NOW() - INTERVAL 30 DAY;
```

---

## 10. 긴급 대응 런북

### 런북 A: 원패스 로그인 전체 불가

```
증상: 사용자들이 원패스 로그인 버튼 클릭 시 오류 발생

1. 원패스 서버 상태 확인 (30초)
   curl -I https://ido.onepass.go.kr/health
   → 200이면 기관 측 문제, 5xx면 원패스 서버 장애

2a. 원패스 서버 장애 시:
   - 원패스 운영팀 On-call 연락 (비상 연락처 확인)
   - 기관 웹사이트 공지: "원패스 로그인 일시 중단, 기관 아이디로 로그인하세요"
   - Agent enabled=false 설정 (불필요 오류 로그 방지)

2b. 기관 측 문제 시:
   - 기관 콜백 URL 정상 여부 확인
   - 방화벽 인바운드 규칙 확인
   - TLS 인증서 만료 확인: openssl s_client -connect {기관도메인}:443
   - DNS 확인: nslookup {기관도메인}

3. 복구 후 확인:
   - 실제 계정으로 원패스 로그인 E2E 테스트
   - 모니터링 대시보드 정상화 확인
   - 인시던트 레포트 작성 (24시간 이내)
```

### 런북 B: 회원 전환 기능 전체 불가

```
증상: 원패스 계정 연결 시도 시 항상 실패

1. 기관 lookup API 직접 테스트 (1분)
   curl -X POST https://{기관도메인}/api/v1/members/lookup \
     -H "Content-Type: application/json" \
     -H "X-Agency-Key: ${API_KEY}" \
     -d '{"identifierHash":"test-hash","agencyCode":"AGENCY_001"}'
   → 응답시간, HTTP 상태 코드 확인

2. DB 연결 확인
   mysql -u {user} -p -e "SELECT 1" {db_name}
   → DB 장애면 DBA 즉시 연락

3. 인덱스 확인
   SHOW INDEX FROM agency_user WHERE Key_name LIKE '%subject%';
   → 없으면: CREATE INDEX idx_agency_subject ON agency_user(agency_subject_id, status);

4. Q-IM 서버에서 기관 API 호출 가능한지 네트워크 확인
   → 방화벽 팀: Q-IM 서버 IP가 기관 API 서버 인바운드 허용 목록에 있는지

5. 임시 조치: 회원 전환 기능 화면에서 비활성화 (토글)
   → 원패스 로그인 + 기관 기본 로그인은 영향 없음
```

### 런북 C: Agent 401 응답 폭증

```
증상: 갑자기 대량의 HTTP 401 응답, 사용자 서비스 장애

1. 최근 변경사항 확인 (5분)
   - API 키 변경 여부
   - Agent JAR 버전 변경 여부
   - onepass-agent.properties 변경 이력

2. Agent 즉시 비활성화 (Fail-Open 전환)
   echo "onepass.agent.enabled=false" >> /etc/onepass/onepass-agent.properties
   # WAS 재시작 → 서비스 정상화 우선

3. 원인 분석 (서비스 정상화 후)
   grep "인증 실패 → HTTP 401" catalina.out | head -20
   # → upstream status 코드 확인 (원패스 서버 응답 코드)

4. API 키 유효성 재확인
   curl https://ido.onepass.go.kr/api/v1/agency/gateway/status/AGENCY_001 \
     -H "X-Agency-Key: ${CURRENT_API_KEY}"

5. 정상화 후 Agent 재활성화 + 모니터링 강화
```

---

*다음 검토 예정: 2026-08-17*

*운영 이슈 접수: 내부 이슈 트래커 ONEPASS-OPS 프로젝트*

*OnePass 운영팀 긴급 연락: On-call 로테이션 스케줄 참고*
