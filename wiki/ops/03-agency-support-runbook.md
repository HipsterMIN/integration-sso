# 유관기관 민원 대응 Runbook

> **버전**: v0.9.0 | **작성일**: 2026-05-17 | **대상**: OnePass 운영팀 / 기술지원팀  
> **긴급 연락**: `#onepass-ops` Slack 채널 | On-call 로테이션: ops/on-call-schedule.md

---

## 목차

1. [민원 분류 체계](#1-민원-분류-체계)
2. [우선순위별 대응 절차](#2-우선순위별-대응-절차)
3. [시나리오별 진단 가이드](#3-시나리오별-진단-가이드)
4. [공통 진단 명령](#4-공통-진단-명령)
5. [에스컬레이션 경로](#5-에스컬레이션-경로)
6. [기관별 특이사항](#6-기관별-특이사항)

---

## 1. 민원 분류 체계

### 1.1 심각도 분류

| 등급 | 기준 | 대응 목표 시간 | 예시 |
|------|------|:-----------:|------|
| 🔴 **P1 - Critical** | 전체 서비스 중단 / 데이터 손실 위험 | **15분 이내** | 인증 플로우 전체 장애, DB 연결 불가 |
| 🟠 **P2 - High** | 특정 기관 서비스 불가 / 사용자 다수 영향 | **1시간 이내** | 특정 기관 Handoff 실패, 프로비저닝 중단 |
| 🟡 **P3 - Medium** | 부분 기능 저하 / 소수 사용자 영향 | **4시간 이내** | 인증 지연, 특정 이벤트 전달 실패 |
| 🟢 **P4 - Low** | 문의 / 설정 안내 / 개선 요청 | **다음 영업일** | SDK 사용법 문의, 설정값 확인 요청 |

### 1.2 민원 유형 분류

```
A. 인증 장애
   A-01: 로그인 불가 (외부 IdP 연동 문제)
   A-02: Handoff 티켓 발급 실패
   A-03: CAST Token 검증 실패
   A-04: 세션 만료 과다 (FeSession)

B. 프로비저닝 이상
   B-01: 회원가입 후 기관 알림 미도달
   B-02: 프로비저닝 중복 발송
   B-03: DEAD_LETTER 증가

C. Gateway/API 오류
   C-01: 401 Unauthorized (API Key 오류)
   C-02: 403 Forbidden (Rate Limit)
   C-03: 400 Bad Request (HMAC 서명 불일치)
   C-04: 503 Service Unavailable (서버 과부하)

D. 데이터 정합성
   D-01: 회원 정보 불일치 (OnePass vs 기관)
   D-02: 이벤트 순서 역전 (Kafka 컨슈머 lag)
   D-03: 탈퇴 처리 미반영

E. SDK/연동 문제
   E-01: SDK 버전 호환성 문제
   E-02: mTLS 인증서 만료
   E-03: 웹훅 수신 실패
```

---

## 2. 우선순위별 대응 절차

### 2.1 P1 대응 (15분 이내)

```
[즉시 조치]
1. Prometheus/Grafana 알림 확인 → 영향 범위 파악
2. #onepass-ops Slack 채널에 인시던트 선언
   형식: "[P1] {영향 서비스}: {증상 요약} | 담당: {이름} | 시작: {HH:MM}"
3. 서비스 헬스체크 즉시 확인:

[헬스체크 명령]
curl -s https://onepass-gateway.go.kr/actuator/health | jq .
kubectl get pods -n production -l app=ido
kubectl get pods -n production -l app=q-im
kubectl get pods -n production -l app=outbox-relay-batch

[롤백 준비]
# 최근 배포 이력 확인
kubectl rollout history deployment/ido -n production
# 이전 버전으로 즉시 롤백 (판단 후)
kubectl rollout undo deployment/ido -n production

[30분 내 보고]
- 영향 기관/사용자 수
- 발생 원인 (임시)
- 조치 내용
- 복구 예상 시간
```

### 2.2 P2 대응 (1시간 이내)

```
[초기 진단 (10분)]
1. 해당 기관 코드 확인 (X-Agency-Code)
2. 시나리오별 진단 가이드(섹션 3) 따라 원인 분류
3. ido, q-im 최근 에러 로그 확인

[임시 조치 (30분)]
- 특정 기관 Rate Limit 조정 (if 403)
- 기관 Outbox PENDING 강제 재처리 (if B-01)
- 기관 API Key 재발급 (if C-01)

[기관 측 통보]
- 발생 시각, 영향 범위, 조치 내용, 예상 완료 시각
- 이메일 + 기관 담당자 직통 연락
```

---

## 3. 시나리오별 진단 가이드

### 3.1 A-01: 로그인 불가

**증상**: 사용자가 OnePass 로그인 후 기관 서비스 접근 불가

**진단 단계**:
```bash
# 1. Q-Sign 인증 세션 확인
kubectl logs -n production deployment/q-sign --since=30m | grep "ERROR\|WARN" | tail -50

# 2. IdO 인증 플로우 로그 확인
kubectl logs -n production deployment/ido --since=30m | grep "AuthService\|HandoffService\|ERROR" | tail -50

# 3. Redis FeSession 확인 (기관 코드로 검색)
kubectl exec -it -n production redis-primary-0 -- redis-cli \
  --scan --pattern "fe:session:*" | head -10

# 4. Keycloak 연동 상태 확인
curl -s http://keycloak:8080/health/ready
```

**원인별 조치**:

| 원인 | 증상 | 조치 |
|------|------|------|
| 외부 IdP(NICE/OACX) 장애 | Q-Sign 로그에 외부 API 타임아웃 | IdP 상태 페이지 확인, 임시 공지 |
| Keycloak 연동 오류 | `KeycloakOidcService` 오류 로그 | Keycloak 헬스체크, 토큰 재발급 |
| FeSession Redis 장애 | Redis 연결 오류 | Redis Primary 상태 확인 |
| Callback URL 오류 | `CallbackUrlValidatorTest` 패턴 불일치 | 기관 URL 등록 확인 |

---

### 3.2 A-02: Handoff 티켓 발급 실패

**증상**: 기관에서 401/403 응답 또는 티켓 검증 실패

**진단 단계**:
```bash
# 1. HandoffController 로그 확인
kubectl logs -n production deployment/ido --since=1h | \
  grep "HandoffController\|HandoffService\|CAST" | tail -50

# 2. Redis Idempotency-Key 캐시 확인
kubectl exec -it -n production redis-primary-0 -- redis-cli \
  get "ido:idempotency:handoff:{idempotency_key}"

# 3. ido.handoff_ticket 테이블 확인
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT ticket_id, agency_code, status, issued_at, expires_at 
   FROM handoff_ticket 
   WHERE issued_at > NOW() - INTERVAL '1 hour' 
   ORDER BY issued_at DESC LIMIT 20;"
```

**원인별 조치**:

| 원인 | 증상 | 조치 |
|------|------|------|
| CAST Token 만료 (5분) | `exp` 클레임 만료 | 기관 측 콜백 처리 지연 확인 (5분 내 처리 필요) |
| CAST Token 서명 불일치 | Ed25519 검증 실패 | 기관 측 공개키 갱신 여부 확인 |
| FeSession 없음 | `IDO_SESSION_NOT_FOUND` | 사용자 세션 재인증 안내 |
| 기관 코드 불일치 | `aud` 클레임 미일치 | 기관 등록 설정 확인 |

---

### 3.3 B-01: 프로비저닝 미도달

**증상**: 회원가입 후 기관에 알림이 오지 않음

**진단 단계**:
```bash
# 1. provisioning_outbox 상태 확인
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT id, agency_code, status, retry_count, next_retry_at, created_at
   FROM provisioning_outbox
   WHERE qim_user_id = '{qimUserId}'
   ORDER BY created_at DESC;"

# 2. ProvisioningRelayJob 로그 확인
kubectl logs -n production deployment/outbox-relay-batch --since=2h | \
  grep "ProvisioningRelayJob\|ERROR" | tail -100

# 3. ShedLock 상태 확인
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT name, lock_until, locked_at, locked_by 
   FROM shedlock 
   WHERE name LIKE '%provisioning%';"

# 4. Prometheus 메트릭 확인
curl -s http://outbox-relay-batch:8090/actuator/prometheus | \
  grep "relay_"
```

**상태별 조치**:

| 상태 | 의미 | 조치 |
|------|------|------|
| PENDING (retry_count < 3) | 재시도 대기 중 | 정상. next_retry_at 이후 자동 재시도 |
| PENDING (next_retry_at 과거) | 배치 중단 의심 | outbox-relay-batch Pod 상태 확인 |
| FAILED | 기관 HTTP 오류 | 기관 엔드포인트 상태 확인 |
| DEAD_LETTER | 3회 초과 실패 | 수동 재처리 (4번 항목 참조) |

**DEAD_LETTER 수동 재처리**:
```sql
-- 특정 기관의 DEAD_LETTER 상태를 PENDING으로 리셋
UPDATE provisioning_outbox
SET status = 'PENDING',
    retry_count = 0,
    next_retry_at = NOW(),
    error_message = error_message || ' | MANUAL_RESET: ' || NOW()
WHERE agency_code = '{AGENCY_CODE}'
  AND status = 'DEAD_LETTER'
  AND created_at > NOW() - INTERVAL '7 days';
-- 반드시 영향 행 수 확인 후 실행 (WHERE 조건 검토)
```

---

### 3.4 C-01: 401 Unauthorized

**증상**: 기관 SDK에서 401 응답

**진단 단계**:
```bash
# 1. 기관 API Key 해시 확인
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT agency_code, api_key_hash, active, created_at 
   FROM agency_meta 
   WHERE agency_code = '{AGENCY_CODE}';"

# 2. 최근 인증 실패 로그
kubectl logs -n production deployment/ido --since=30m | \
  grep "HandoffAgencyKeyInterceptor\|API_KEY\|UNAUTHORIZED" | tail -30
```

**API Key 재발급 절차**:
```bash
# 새 API Key 생성
NEW_KEY=$(openssl rand -hex 32)
NEW_KEY_HASH=$(echo -n "$NEW_KEY" | sha256sum | cut -d' ' -f1)

# DB 업데이트
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "UPDATE agency_meta 
   SET api_key_hash = '$NEW_KEY_HASH', updated_at = NOW()
   WHERE agency_code = '{AGENCY_CODE}';"

# 기관에 새 API Key 전달 (보안 채널: 이메일 + 전화 확인)
echo "새 API Key: $NEW_KEY"
echo "기관 측 SDK 설정: .apiKey(\"$NEW_KEY\")"
# ⚠️ 위 echo 명령은 로그에 남지 않도록 주의 — 터미널에서만 확인
```

---

### 3.5 C-03: 400 Bad Request (HMAC 서명 불일치)

**증상**: `X-Internal-Sig` 헤더 검증 실패

**진단 단계**:
```bash
# HMAC 서명 필터 로그
kubectl logs -n production deployment/ido --since=30m | \
  grep "HmacSignatureFilter\|HMAC\|signature" | tail -30
```

**주요 원인 및 조치**:

| 원인 | 확인 방법 | 조치 |
|------|---------|------|
| 기관 서버 시간 오차 (±5분 초과) | 기관 NTP 설정 확인 | 기관 측 NTP 동기화 |
| HMAC Secret 불일치 | `AgencyHmacKeyStore` 조회 | Secret 재발급 및 갱신 |
| 서명 페이로드 형식 오류 | SDK 버전 확인 | SDK 최신 버전 업데이트 |
| epochSeconds 단위 오류 (ms vs s) | SDK 로그 확인 | SDK `HmacSigner` 코드 확인 |

---

### 3.6 B-03: DEAD_LETTER 증가 알림

**Prometheus 알림 규칙** (설정 필요):
```yaml
# prometheus-rules.yml
- alert: ProvisioningDeadLetterHigh
  expr: increase(relay_dead_letter_total[5m]) > 10
  for: 1m
  labels:
    severity: warning
  annotations:
    summary: "프로비저닝 DEAD_LETTER {{ $value }}건 발생"
    description: "5분 내 10건 초과 DEAD_LETTER. outbox-relay-batch 로그 확인 필요"
```

**진단 및 조치**:
```bash
# 기관별 DEAD_LETTER 현황
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT agency_code, COUNT(*) as dead_count, 
          MAX(created_at) as latest
   FROM provisioning_outbox
   WHERE status = 'DEAD_LETTER'
     AND created_at > NOW() - INTERVAL '24 hours'
   GROUP BY agency_code
   ORDER BY dead_count DESC;"

# 특정 기관 엔드포인트 상태 확인
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT agency_code, endpoint_url, auth_type, active, timeout_ms
   FROM agency_endpoint_registry
   WHERE endpoint_type = 'PROVISIONING'
     AND agency_code = '{AGENCY_CODE}';"

# 기관 엔드포인트 비활성화 (지속 장애 시 임시 조치)
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "UPDATE agency_endpoint_registry
   SET active = false, updated_at = NOW()
   WHERE agency_code = '{AGENCY_CODE}'
     AND endpoint_type = 'PROVISIONING';"
-- ⚠️ 비활성화 후 기관 측 복구 확인 → 재활성화 필요
```

---

### 3.7 E-02: mTLS 인증서 만료

**증상**: 특정 MTLS 기관 프로비저닝 실패 + SSL 오류 로그

**진단 단계**:
```bash
# 1. 인증서 만료 확인 (기관 측 서버 인증서)
echo | openssl s_client -connect {AGENCY_ENDPOINT}:443 2>/dev/null | \
  openssl x509 -noout -dates

# 2. 클라이언트 인증서(PKCS12) 만료 확인
KEYSTORE_B64=$(kubectl get secret ido-mtls-keystore -n production \
  -o jsonpath='{.data.BATCH_MTLS_KEYSTORE_BASE64}')
echo "$KEYSTORE_B64" | base64 -d > /tmp/keystore.p12
openssl pkcs12 -in /tmp/keystore.p12 -nokeys -passin pass:{PASS} | \
  openssl x509 -noout -dates
rm /tmp/keystore.p12  # 반드시 삭제

# 3. 배치 로그에서 SSL 오류 확인
kubectl logs -n production deployment/outbox-relay-batch --since=1h | \
  grep "SSL\|TLS\|certificate\|mTLS\|PKCS12" | tail -30
```

**인증서 갱신 절차**:
```bash
# 새 PKCS12 KeyStore 생성 (기관 CA 인증서 필요)
openssl pkcs12 -export \
  -in client.crt -inkey client.key \
  -out new_keystore.p12 \
  -passout pass:{NEW_PASSWORD}

# Base64 인코딩
NEW_KEYSTORE_B64=$(base64 -w0 new_keystore.p12)

# K8s Secret 업데이트
kubectl create secret generic ido-mtls-keystore \
  --from-literal=BATCH_MTLS_KEYSTORE_BASE64="$NEW_KEYSTORE_B64" \
  --from-literal=BATCH_MTLS_KEYSTORE_PASS="{NEW_PASSWORD}" \
  -n production \
  --dry-run=client -o yaml | kubectl apply -f -

# outbox-relay-batch 재시작 (새 인증서 로드)
kubectl rollout restart deployment/outbox-relay-batch -n production
kubectl rollout status deployment/outbox-relay-batch -n production
```

---

### 3.8 D-01: 회원 정보 불일치

**증상**: 기관에서 표시하는 회원 정보 ≠ OnePass 회원 정보

**진단 단계**:
```bash
# 1. Q-IM 회원 조회
kubectl exec -it -n production qim-db-0 -- psql -U qim -c \
  "SELECT qim_user_id, status, auth_level, created_at, updated_at
   FROM qim_user
   WHERE qim_user_id = '{QIM_USER_ID}';"

# 2. 이벤트 흐름 확인 (Kafka Consumer Lag)
kubectl exec -it -n production kafka-0 -- kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group ido-consumer-group | grep qim

# 3. provisioning_outbox 이벤트 타입 확인
kubectl exec -it -n production ido-db-0 -- psql -U ido -c \
  "SELECT id, agency_code, event_type, status, created_at
   FROM provisioning_outbox
   WHERE qim_user_id = '{QIM_USER_ID}'
   ORDER BY created_at DESC;"
```

**PII 최소화 원칙 준수 확인**:
```
기관에 전달되는 필드:
  ✅ qimUserId (익명 식별자)
  ✅ eventType (USER_REGISTERED 등)
  ✅ identityHash (SHA-256 파생값)
  ✅ registeredAt (가입 시각)
  ✅ idempotencyKey (멱등성 키)
  ✅ correlationId (추적 ID)
  
  ❌ 실명 (전달 금지)
  ❌ 전화번호 (전달 금지)  
  ❌ CI 평문 (전달 금지)
  
기관에서 실명/전화번호를 요청하는 경우:
→ 별도 개인정보 처리 동의 절차 및 개인정보 처리방침 검토 필요
→ OnePass 법무팀 에스컬레이션
```

---

## 4. 공통 진단 명령

### 4.1 서비스 헬스체크

```bash
# 전체 서비스 상태
for svc in ido q-im q-sign outbox-relay-batch; do
  echo -n "$svc: "
  kubectl get pods -n production -l app=$svc \
    --no-headers | awk '{print $3}' | sort | uniq -c
done

# 액추에이터 헬스
for port in ido:8083 q-im:8082 q-sign:8081; do
  name=$(echo $port | cut -d: -f1)
  p=$(echo $port | cut -d: -f2)
  echo -n "$name: "
  kubectl exec -n production deploy/$name -- curl -s http://localhost:$p/actuator/health | jq .status
done

# Feature Flag 상태 확인
kubectl exec -n production deploy/ido -- \
  curl -s http://localhost:8083/actuator/features | jq .
```

### 4.2 로그 실시간 모니터링

```bash
# 에러 로그 실시간
kubectl logs -n production -l app=ido -f | grep -E "ERROR|WARN" &
kubectl logs -n production -l app=outbox-relay-batch -f | grep -E "ERROR|DEAD_LETTER" &

# 특정 기관 관련 로그
kubectl logs -n production -l app=ido --since=1h | \
  grep "{AGENCY_CODE}" | tail -50

# 상관관계 추적 (correlationId 기반)
kubectl logs -n production -l app=ido --since=1h | \
  grep "{CORRELATION_ID}"
kubectl logs -n production -l app=q-im --since=1h | \
  grep "{CORRELATION_ID}"
```

### 4.3 DB 상태 점검 쿼리

```sql
-- [ido DB] 최근 1시간 프로비저닝 현황
SELECT 
  status,
  COUNT(*) as cnt,
  AVG(retry_count) as avg_retry
FROM provisioning_outbox
WHERE created_at > NOW() - INTERVAL '1 hour'
GROUP BY status;

-- [ido DB] 기관별 엔드포인트 현황
SELECT 
  agency_code, 
  endpoint_type,
  auth_type,
  active,
  timeout_ms
FROM agency_endpoint_registry
ORDER BY agency_code, endpoint_type;

-- [ido DB] ShedLock 활성 락 목록
SELECT name, lock_until, locked_by
FROM shedlock
WHERE lock_until > NOW();

-- [q-im DB] Kafka Outbox 미발행 건수
SELECT COUNT(*) as pending_count
FROM outbox
WHERE status = 'PENDING';
```

### 4.4 Kafka Consumer Lag 확인

```bash
# Consumer Group Lag 전체
kubectl exec -n production kafka-0 -- kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --all-groups 2>/dev/null | \
  awk 'NR==1 || /LAG/'

# 특정 토픽 메시지 확인
kubectl exec -n production kafka-0 -- kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic qim.user.events \
  --from-beginning \
  --max-messages 5 \
  --timeout-ms 5000
```

---

## 5. 에스컬레이션 경로

### 5.1 에스컬레이션 트리

```
기관 담당자 문의
    │
    ▼
[1차] OnePass 기술지원팀
  - 표준 민원 처리 (P3/P4)
  - 설정 안내, 문서 제공
  - 대응 시간: 영업일 기준 1~4시간
    │
    ├─ P1/P2 발생 시
    │
    ▼
[2차] OnePass 운영팀 (On-call)
  - 서비스 상태 진단
  - 임시 조치 (K8s 재시작, DB 쿼리)
  - Slack #onepass-ops + 전화
    │
    ├─ 코드 수정 필요 시
    │
    ▼
[3차] 개발팀 On-call
  - 긴급 핫픽스
  - DB 스키마 롤백
  - Kafka 오프셋 재설정
    │
    ├─ 개인정보 관련 이슈
    │
    ▼
[4차] 법무/보안팀
  - PII 관련 사고 대응
  - 보안 취약점 패치
  - 감사 기관 보고
```

### 5.2 기관 측 에스컬레이션 요청 시 필수 정보

기관에서 지원 요청 시 아래 정보를 반드시 수집:

```
□ 기관 코드 (X-Agency-Code)
□ 발생 시각 (UTC 또는 KST 명시)
□ 영향 범위 (사용자 수 / 서비스 범위)
□ 오류 메시지 / HTTP 응답 코드
□ correlationId (요청 추적용, 있는 경우)
□ idempotencyKey (멱등성 키, 있는 경우)
□ qimUserId (회원 식별자, 있는 경우)
□ SDK 버전 (onepass-agency-sdk version)
□ 기관 서버 환경 (JDK 버전, Spring Boot 버전)
□ 재현 방법 (가능한 경우)
```

---

## 6. 기관별 특이사항

> 이 섹션은 운영팀이 기관별 연동 경험을 축적하면서 작성합니다.

### 6.1 일반 주의사항

- **mTLS 기관**: 인증서 만료일 90일 전 기관에 갱신 요청 (캘린더 알림 설정 권장)
- **HMAC 기관**: 기관 서버 시간 오차가 5분 초과 시 서명 실패 → NTP 동기화 확인
- **Rate Limit**: 기본 슬라이딩 윈도우 설정 초과 기관 → `agency_rate_limit_config` 조정
- **타임아웃 민감 기관**: `agency_endpoint_registry.timeout_ms` 개별 조정 가능

### 6.2 프로비저닝 Feature Flag 현황

```
현재 (v0.9.0): IDO_PROVISIONING_ENABLED=false
→ 프로비저닝 미발생 상태
→ 기관으로부터 "알림이 안 온다"는 민원은 Feature Flag 안내로 처리

Phase 2-A 전환 후:
→ dry-run 중 → 실제 HTTP 발송 없음
→ 로그에서 "DRY-RUN 완료" 확인 가능

Phase 2-B 전환 후:
→ 실제 발송 시작
→ 민원 증가 예상 → 본 Runbook 적극 활용
```

---

## 부록: 빠른 참조 카드

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  OnePass 민원 대응 빠른 참조
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  [로그 확인]
  kubectl logs -n production deploy/ido --since=30m | grep ERROR
  kubectl logs -n production deploy/outbox-relay-batch --since=1h | grep "DEAD_LETTER\|ERROR"

  [헬스체크]
  curl -s http://ido:8083/actuator/health | jq .
  curl -s http://ido:8083/actuator/features | jq .

  [프로비저닝 상태]
  psql: SELECT status, COUNT(*) FROM provisioning_outbox 
        WHERE created_at > NOW() - INTERVAL '1h' GROUP BY status;

  [ShedLock 확인]
  psql: SELECT name, lock_until FROM shedlock WHERE lock_until > NOW();

  [Prometheus 메트릭]
  curl http://outbox-relay-batch:8090/actuator/prometheus | grep relay_

  [에스컬레이션]
  P1/P2 → Slack #onepass-ops + 전화 On-call
  P3    → Slack #onepass-support
  P4    → 이메일 onepass-support@smes.go.kr

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```
