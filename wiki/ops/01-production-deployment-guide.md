# 통합인증 플랫폼 (OnePass) — 운영 배포 주의사항 및 체크리스트

> **버전**: v1.0 | **최종 갱신**: 2026-05-17 | **대상**: DevOps / 운영팀 / 개발팀

---

## 목차

1. [배포 전 필수 확인 사항](#1-배포-전-필수-확인-사항)
2. [서비스별 환경 변수 체크리스트](#2-서비스별-환경-변수-체크리스트)
3. [데이터베이스 마이그레이션 주의사항](#3-데이터베이스-마이그레이션-주의사항)
4. [Kafka 토픽 사전 생성](#4-kafka-토픽-사전-생성)
5. [Redis 연결 및 ShedLock 초기화](#5-redis-연결-및-shedlock-초기화)
6. [outbox-relay-batch 배포 절차 (ShedLock 이관)](#6-outbox-relay-batch-배포-절차-shedlock-이관)
7. [mTLS 클라이언트 인증서 설정](#7-mtls-클라이언트-인증서-설정)
8. [K8s Secret 등록 가이드](#8-k8s-secret-등록-가이드)
9. [Feature Flag 관리 (이관 스위치)](#9-feature-flag-관리-이관-스위치)
10. [Flyway 마이그레이션 순서 보장](#10-flyway-마이그레이션-순서-보장)
11. [헬스체크 및 Readiness Probe](#11-헬스체크-및-readiness-probe)
12. [Prometheus 메트릭 및 AlertManager 설정](#12-prometheus-메트릭-및-alertmanager-설정)
13. [배포 후 검증 절차](#13-배포-후-검증-절차)
14. [롤백 절차](#14-롤백-절차)
15. [운영 중 장애 대응 매뉴얼](#15-운영-중-장애-대응-매뉴얼)

---

## 1. 배포 전 필수 확인 사항

### 1.1 배포 순서 원칙

> **⚠️ 반드시 순서를 지킬 것. 순서 위반 시 서비스 장애 발생 가능.**

```
1. DB 마이그레이션 (Flyway)
2. Kafka 토픽 사전 생성
3. Redis 연결 확인
4. 백엔드 서비스 배포 (ido → q-im → q-sign 순)
5. outbox-relay-batch 배포
6. 프론트엔드 배포 (onepass-fe)
7. Feature Flag 전환 (기존 인-프로세스 릴레이 → 배치 서비스)
8. 배포 후 검증
```

### 1.2 배포 금지 시간대

| 시간대 | 사유 |
|--------|------|
| 평일 09:00~18:00 | 업무 시간 — 기관 사용자 활성 시간 |
| 민원 처리 마감 전 1시간 | 처리 중 트랜잭션 중단 위험 |
| 공휴일 전날 17:00~익일 09:00 | 야간 배치 실행 시간 중복 |

**권장 배포 시간**: 평일 01:00~04:00 (트래픽 최저 시간대)

### 1.3 배포 전 인프라 상태 확인

```bash
# PostgreSQL (ido) 연결 확인
psql -h $IDO_DB_HOST -U $IDO_DB_USERNAME -d $IDO_DB_NAME -c "SELECT 1"

# MariaDB (q-im) 연결 확인
mysql -h $QIM_DB_HOST -u $QIM_DB_USERNAME -p$QIM_DB_PASSWORD $QIM_DB_NAME -e "SELECT 1"

# PostgreSQL (q-sign) 연결 확인
psql -h $QSIGN_DB_HOST -U $QSIGN_DB_USERNAME -d $QSIGN_DB_NAME -c "SELECT 1"

# Redis 연결 확인
redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD PING

# Kafka 브로커 연결 확인
kafka-broker-api-versions.sh --bootstrap-server $KAFKA_SERVERS
```

---

## 2. 서비스별 환경 변수 체크리스트

### 2.1 ido 서비스 필수 환경 변수

```yaml
# 데이터베이스
IDO_DB_HOST: <PostgreSQL 호스트>
IDO_DB_PORT: 5432
IDO_DB_NAME: onepass
IDO_DB_USERNAME: <DB 사용자>
IDO_DB_PASSWORD: <DB 비밀번호>  # K8s Secret

# Redis
REDIS_HOST: <Redis 호스트>
REDIS_PORT: 6379
REDIS_PASSWORD: <Redis 비밀번호>  # K8s Secret

# Kafka
KAFKA_SERVERS: <Kafka 브로커 주소>

# Feature Flags (배포 초기: true / 이관 후: false)
IDO_OUTBOX_RELAY_ENABLED: "true"          # 이관 후 false
IDO_QIM_OUTBOX_RELAY_ENABLED: "true"      # 이관 후 false
IDO_PROVISIONING_RELAY_ENABLED: "true"    # 이관 후 false
IDO_WEBHOOK_RELAY_ENABLED: "true"         # 이관 후 false

# 플랫폼 설정
IDO_PLATFORM_VERSION: "1.0"
IDO_WEBHOOK_SIGNING_SECRET: <32자 이상 HMAC 서명 키>  # K8s Secret

# 기관 API 자격증명 (K8s Secret envFrom 마운트)
# 형식: SECRETS_AGENCY_{기관코드}_{자격증명유형}
# 예: SECRETS_AGENCY_AGENCY_001_API_KEY=<실제 API 키>
#     SECRETS_AGENCY_AGENCY_003_HMAC_SECRET=<HMAC 시크릿>
```

### 2.2 outbox-relay-batch 서비스 필수 환경 변수

```yaml
# 데이터베이스 — 3개 DataSource 모두 필요
IDO_DB_HOST: <PostgreSQL 호스트>
IDO_DB_PORT: 5432
IDO_DB_NAME: onepass
IDO_DB_USERNAME: <DB 사용자>
IDO_DB_PASSWORD: <DB 비밀번호>

QIM_DB_HOST: <MariaDB 호스트>
QIM_DB_PORT: 3306
QIM_DB_NAME: qim
QIM_DB_USERNAME: <DB 사용자>
QIM_DB_PASSWORD: <DB 비밀번호>

QSIGN_DB_HOST: <PostgreSQL 호스트>
QSIGN_DB_PORT: 5432
QSIGN_DB_NAME: onepass
QSIGN_DB_USERNAME: <DB 사용자>
QSIGN_DB_PASSWORD: <DB 비밀번호>

# Redis (ShedLock Primary Provider)
REDIS_HOST: <Redis 호스트>
REDIS_PORT: 6379
REDIS_PASSWORD: <Redis 비밀번호>

# Kafka
KAFKA_SERVERS: <Kafka 브로커 주소>

# Webhook 서명 키 (ido 서비스와 동일한 값)
IDO_WEBHOOK_SIGNING_SECRET: <동일한 HMAC 서명 키>

# mTLS 클라이언트 인증서 (MTLS 기관이 있는 경우 필수)
BATCH_MTLS_KEYSTORE_BASE64: <PKCS12 KeyStore Base64>  # K8s Secret
BATCH_MTLS_KEYSTORE_PASS: <KeyStore 비밀번호>         # K8s Secret

# 기관 자격증명 (ido 서비스와 동일한 Secret 공유 또는 별도 등록)
# SECRETS_AGENCY_*: K8s Secret envFrom 마운트

# Job 활성화 플래그 (기본값 true)
BATCH_IDO_KAFKA_RELAY_ENABLED: "true"
BATCH_IDO_QIM_RELAY_ENABLED: "true"
BATCH_QIM_KAFKA_RELAY_ENABLED: "true"
BATCH_QSIGN_KAFKA_RELAY_ENABLED: "true"
BATCH_PROVISIONING_RELAY_ENABLED: "true"
BATCH_WEBHOOK_RELAY_ENABLED: "true"
```

### 2.3 q-im 서비스 필수 환경 변수

```yaml
QIM_DB_HOST: <MariaDB 호스트>
QIM_DB_PORT: 3306
QIM_DB_NAME: qim
QIM_DB_USERNAME: <DB 사용자>
QIM_DB_PASSWORD: <DB 비밀번호>
KAFKA_SERVERS: <Kafka 브로커 주소>
```

### 2.4 q-sign 서비스 필수 환경 변수

```yaml
QSIGN_DB_HOST: <PostgreSQL 호스트>
QSIGN_DB_PORT: 5432
QSIGN_DB_NAME: onepass
QSIGN_DB_USERNAME: <DB 사용자>
QSIGN_DB_PASSWORD: <DB 비밀번호>
KAFKA_SERVERS: <Kafka 브로커 주소>
```

---

## 3. 데이터베이스 마이그레이션 주의사항

### 3.1 Flyway 마이그레이션 현황

| 모듈 | 최종 버전 | DataSource | 비고 |
|------|-----------|-----------|------|
| ido | V18 | ido (PostgreSQL) | V18: event_type CHECK 제약 |
| q-sign | V1 | qsign (PostgreSQL) | outbox 테이블 초기화 |
| outbox-relay-batch | V19 | ido (PostgreSQL) | shedlock 테이블 추가 |

### 3.2 V19 마이그레이션 내용 (`ido.shedlock`)

```sql
-- idem-relay/src/main/resources/db/migration/V19__add_shedlock_table.sql
CREATE TABLE IF NOT EXISTS ido.shedlock (
    name       VARCHAR(64)                  NOT NULL,
    lock_until TIMESTAMP(3) WITH TIME ZONE  NOT NULL,
    locked_at  TIMESTAMP(3) WITH TIME ZONE  NOT NULL,
    locked_by  VARCHAR(255)                 NOT NULL,
    PRIMARY KEY (name)
);
CREATE INDEX IF NOT EXISTS idx_shedlock_lock_until ON ido.shedlock (lock_until);
```

> **주의**: V19는 `outbox-relay-batch` 최초 기동 시 자동 적용됨.
> ido 서비스가 먼저 배포된 상태에서 배치 서비스를 배포하면 정상 적용됨.

### 3.3 마이그레이션 실패 시 복구

```bash
# Flyway 상태 확인
./gradlew :idem-hub:flywayInfo --no-daemon

# 실패한 마이그레이션 확인 후 수동 수정
psql -h $IDO_DB_HOST -U $IDO_DB_USERNAME -d $IDO_DB_NAME -c \
  "SELECT version, description, success FROM ido.flyway_schema_history ORDER BY version DESC LIMIT 10;"

# 실패한 마이그레이션 삭제 후 재시도 (주의: 운영 환경에서 신중히)
psql -h $IDO_DB_HOST -U $IDO_DB_USERNAME -d $IDO_DB_NAME -c \
  "DELETE FROM ido.flyway_schema_history WHERE success = false;"
```

### 3.4 마이그레이션 사전 검증

```bash
# 운영 배포 전 Staging 환경에서 반드시 검증
./gradlew :idem-relay:flywayInfo --no-daemon \
  -Pbatch.datasource.ido.jdbc-url="jdbc:postgresql://$IDO_DB_HOST:5432/onepass"
```

---

## 4. Kafka 토픽 사전 생성

> **⚠️ 토픽이 없으면 Producer 기동 시 `UnknownTopicOrPartitionException` 발생**

### 4.1 필수 토픽 목록

| 토픽 이름 | 파티션 수 | 복제 인수 | 생성 모듈 | 비고 |
|-----------|-----------|-----------|-----------|------|
| `qim.user.events` | 3 | 3 | ido → q-im | 사용자 이벤트 |
| `qsign.auth.events` | 3 | 3 | q-sign → consumer | 서명 인증 이벤트 |
| `ido.outbox.events` | 3 | 3 | ido → consumer | IdO 아웃박스 이벤트 |

### 4.2 토픽 생성 스크립트

```bash
#!/bin/bash
BOOTSTRAP=$KAFKA_SERVERS
REPLICATION=3    # 운영: 3, 개발: 1

create_topic() {
  local topic=$1
  local partitions=${2:-3}
  echo "토픽 생성: $topic (파티션: $partitions, 복제: $REPLICATION)"
  kafka-topics.sh \
    --bootstrap-server $BOOTSTRAP \
    --create \
    --topic $topic \
    --partitions $partitions \
    --replication-factor $REPLICATION \
    --config retention.ms=604800000 \
    --config min.insync.replicas=2 \
    --if-not-exists
}

create_topic "qim.user.events" 3
create_topic "qsign.auth.events" 3
create_topic "ido.outbox.events" 3

# 생성 확인
kafka-topics.sh --bootstrap-server $BOOTSTRAP --list | grep -E "qim|qsign|ido"
```

### 4.3 Kafka Producer 멱등성 및 acks 설정 확인

```bash
# 운영 Kafka 브로커 멱등성(idempotence) 지원 확인
kafka-configs.sh --bootstrap-server $BOOTSTRAP \
  --describe --entity-type brokers --entity-name 0 | grep "enable.idempotence"

# 필요 시 브로커 설정 (운영 환경 기본값: true)
# enable.idempotence=true
# acks=all
# min.insync.replicas=2
```

---

## 5. Redis 연결 및 ShedLock 초기화

### 5.1 ShedLock 이중화 구조

```
[Primary]  Redis LockProvider    → 정상 운영 시 사용
[Fallback] JDBC LockProvider     → Redis 장애 시 자동 Fallback
           (ido.shedlock 테이블)
```

> **중요**: `ShedLockConfig`에서 `@Primary`는 Redis Provider에만 적용.
> Redis 연결 실패 시 **자동으로 JDBC로 전환되지 않음** — 수동 설정 변경 필요.
> 현재 구현은 Bean 선택을 통한 주 Provider 지정 방식.

### 5.2 Redis 장애 시 수동 JDBC Fallback 전환

```bash
# Redis 장애 확인
redis-cli -h $REDIS_HOST -p $REDIS_PORT PING

# JDBC Fallback으로 전환 (application.yml or 환경변수)
# outbox-relay-batch Pod 환경변수 추가:
SHEDLOCK_USE_JDBC_FALLBACK: "true"  # ShedLockConfig.java에 조건 추가 필요 (TODO)
```

> **현재 구현 한계**: Redis 장애 시 자동 Fallback이 아닌 수동 전환.
> 향후 개선: Redis 헬스체크 → 실패 시 jdbcLockProvider로 자동 전환 구현 권장.

### 5.3 Redis Lettuce 풀 설정 최적화

```yaml
# outbox-relay-batch application.yml 기본값
spring.data.redis.lettuce.pool:
  max-active: 5    # ShedLock 전용 — 락 획득/해제만 사용
  max-idle: 3
  min-idle: 1
  max-wait: 1000ms # Redis 연결 대기 최대 1초
```

---

## 6. outbox-relay-batch 배포 절차 (ShedLock 이관)

### 6.1 이관 흐름

```
[이관 전]
ido Pod     → @Scheduled: IdoOutboxRelay (500ms 주기)
ido Pod     → @Scheduled: QimOutboxRelay (1000ms 주기)
ido Pod     → @Scheduled: ProvisioningOutboxRelay (30s 주기)
ido Pod     → @Scheduled: WebhookDispatchOutboxRelay (500ms 주기)
q-im Pod    → @Scheduled: OutboxServiceImpl (500ms 주기)
q-sign Pod  → @Scheduled: OutboxRelay (500ms 주기)

[이관 후]
outbox-relay-batch Pod  → ShedLock 기반 분산 릴레이 (모든 릴레이 통합)
idem-hub/idem-registry/q-sign Pod     → Feature Flag false (릴레이 비활성)
```

### 6.2 이관 단계별 절차

#### Step 1: outbox-relay-batch 배포 (Feature Flag 비활성 유지)

```bash
# outbox-relay-batch 배포 시 ido 서비스 Feature Flag는 아직 true 상태 유지
# → 이관 기간 중 인-프로세스 릴레이 + 배치 릴레이 동시 실행
# → FOR UPDATE SKIP LOCKED로 중복 처리 방지 (레코드 레벨)
# → ShedLock으로 배치 인스턴스 간 중복 방지 (프로세스 레벨)

kubectl apply -f k8s/outbox-relay-batch-deployment.yml
kubectl rollout status deployment/outbox-relay-batch -n production
```

#### Step 2: 배치 서비스 정상 동작 확인 (최소 10분 모니터링)

```bash
# 배치 서비스 로그 확인
kubectl logs -l app=outbox-relay-batch -n production -f | \
  grep -E "SUCCESS|RETRY|DEAD_LETTER|ERROR|ShedLock"

# Prometheus 메트릭 확인 (outbox-relay-batch 정상 처리 여부)
curl -s http://outbox-relay-batch:8090/actuator/prometheus | \
  grep -E "batch_relay_(ido|qim|qsign|provisioning|webhook)"

# 예상 메트릭 (10분 내 0이 아니어야 함)
# batch_relay_ido_kafka_success_total > 0
# batch_relay_provisioning_success_total >= 0  (처리 건이 없으면 0도 정상)
```

#### Step 3: Feature Flag 순차 비활성화

> **순서**: provisioning → webhook → qim → ido (영향도 낮은 것부터)

```bash
# 3-1. Provisioning 릴레이 비활성 (ido 서비스)
kubectl set env deployment/ido \
  IDO_PROVISIONING_RELAY_ENABLED=false -n production
kubectl rollout status deployment/ido -n production

# 30초 대기 후 배치에서 처리되는지 확인
sleep 30
kubectl logs -l app=outbox-relay-batch -n production \
  | grep "ProvisioningRelayJob" | tail -5

# 3-2. Webhook 릴레이 비활성
kubectl set env deployment/ido \
  IDO_WEBHOOK_RELAY_ENABLED=false -n production

# 3-3. QIM Kafka 릴레이 비활성
kubectl set env deployment/ido \
  IDO_QIM_OUTBOX_RELAY_ENABLED=false -n production

# 3-4. IDO Kafka 릴레이 비활성
kubectl set env deployment/ido \
  IDO_OUTBOX_RELAY_ENABLED=false -n production

# 3-5. q-im Kafka 릴레이 비활성 (q-im 서비스)
kubectl set env deployment/q-im \
  QIM_OUTBOX_RELAY_ENABLED=false -n production

# 3-6. q-sign Kafka 릴레이 비활성 (q-sign 서비스)
kubectl set env deployment/q-sign \
  QSIGN_OUTBOX_RELAY_ENABLED=false -n production
```

#### Step 4: 이관 완료 검증

```bash
# ido, q-im, q-sign 서비스에서 릴레이 로그가 더 이상 나오지 않는지 확인
kubectl logs -l app=ido -n production --since=2m | grep "OutboxRelay" | wc -l
# → 0이어야 함

# 배치 서비스에서 릴레이 처리 중인지 확인
kubectl logs -l app=outbox-relay-batch -n production --since=2m | \
  grep -E "IdoKafkaRelayJob|QimKafkaRelayJob|QSignKafkaRelayJob" | wc -l
# → 0 이상 (PENDING 레코드 있으면 처리 로그, 없으면 로그 없음 정상)
```

---

## 7. mTLS 클라이언트 인증서 설정

### 7.1 MTLS 기관 확인

```sql
-- MTLS 인증 방식을 사용하는 기관 목록 조회
SELECT agency_code, endpoint_url, auth_type, auth_credential_ref, active
FROM ido.agency_endpoint_registry
WHERE auth_type = 'MTLS' AND active = TRUE;
```

### 7.2 PKCS12 KeyStore 생성

```bash
# 기존 PEM/CRT 파일로 PKCS12 생성
openssl pkcs12 -export \
  -in client-cert.pem \
  -inkey client-key.pem \
  -certfile ca-chain.pem \
  -out client-keystore.p12 \
  -name "onepass-batch-client" \
  -passout pass:$KEYSTORE_PASSWORD

# 검증
keytool -list -v -keystore client-keystore.p12 \
  -storetype PKCS12 -storepass $KEYSTORE_PASSWORD | \
  grep -E "Alias|Valid from|Serial|Owner"
```

### 7.3 Base64 인코딩 및 K8s Secret 등록

```bash
# PKCS12 → Base64
KEYSTORE_B64=$(base64 -w0 client-keystore.p12)

# K8s Secret 생성
kubectl create secret generic batch-mtls-cert \
  --from-literal=BATCH_MTLS_KEYSTORE_BASE64="$KEYSTORE_B64" \
  --from-literal=BATCH_MTLS_KEYSTORE_PASS="$KEYSTORE_PASSWORD" \
  -n production

# Secret 검증
kubectl get secret batch-mtls-cert -n production -o jsonpath='{.data.BATCH_MTLS_KEYSTORE_BASE64}' | \
  base64 -d | openssl pkcs12 -info -noout -passin pass:$KEYSTORE_PASSWORD
```

### 7.4 deployment.yml envFrom 추가

```yaml
# k8s/outbox-relay-batch-deployment.yml
spec:
  containers:
  - name: outbox-relay-batch
    envFrom:
    - secretRef:
        name: batch-ido-db-credentials
        optional: false
    - secretRef:
        name: batch-mtls-cert
        optional: true    # MTLS 기관 없으면 optional: true
    - secretRef:
        name: ido-agency-credentials
        optional: true
```

### 7.5 인증서 만료 관리

```bash
# 현재 KeyStore 인증서 만료일 확인
kubectl get secret batch-mtls-cert -n production \
  -o jsonpath='{.data.BATCH_MTLS_KEYSTORE_BASE64}' | \
  base64 -d | \
  openssl pkcs12 -nokeys -passin pass:$KEYSTORE_PASSWORD 2>/dev/null | \
  openssl x509 -noout -enddate

# Prometheus 인증서 만료 모니터링 예시 (certmanager 사용 시)
# 또는 Cronjob으로 만료일 30일 전 Slack 알림 발송
```

---

## 8. K8s Secret 등록 가이드

### 8.1 필수 Secret 목록

| Secret 이름 | 포함 키 | 마운트 대상 | 비고 |
|-------------|---------|------------|------|
| `ido-db-credentials` | `IDO_DB_PASSWORD` | ido | |
| `qim-db-credentials` | `QIM_DB_PASSWORD` | q-im, batch | |
| `qsign-db-credentials` | `QSIGN_DB_PASSWORD` | q-sign, batch | |
| `redis-credentials` | `REDIS_PASSWORD` | ido, batch | |
| `kafka-credentials` | `KAFKA_SASL_PASSWORD` (옵션) | ido, q-im, q-sign, batch | SASL 사용 시 |
| `ido-webhook-secret` | `IDO_WEBHOOK_SIGNING_SECRET` | ido, batch | 동일 값 공유 |
| `ido-agency-credentials` | `SECRETS_AGENCY_*` | ido, batch | 기관별 자격증명 |
| `batch-mtls-cert` | `BATCH_MTLS_KEYSTORE_BASE64`, `BATCH_MTLS_KEYSTORE_PASS` | batch | MTLS 기관 있을 때 |

### 8.2 기관 자격증명 Secret 등록 예시

```bash
# API_KEY 기관
kubectl create secret generic ido-agency-credentials \
  --from-literal=SECRETS_AGENCY_AGENCY_001_API_KEY="<실제 API 키>" \
  --from-literal=SECRETS_AGENCY_AGENCY_002_API_KEY="<실제 API 키>" \
  -n production --dry-run=client -o yaml | kubectl apply -f -

# HMAC 기관 (32자 이상 시크릿)
kubectl patch secret ido-agency-credentials \
  -n production \
  --patch='{"stringData":{"SECRETS_AGENCY_AGENCY_003_HMAC_SECRET":"<HMAC 시크릿>"}}'

# 등록된 자격증명 확인 (값은 노출 안 됨)
kubectl get secret ido-agency-credentials -n production \
  -o jsonpath='{.data}' | python3 -c "import sys,json; d=json.load(sys.stdin); [print(k) for k in d.keys()]"
```

---

## 9. Feature Flag 관리 (이관 스위치)

### 9.1 Feature Flag 전체 목록

| 환경 변수 | 기본값 | 서비스 | 설명 |
|-----------|--------|--------|------|
| `IDO_OUTBOX_RELAY_ENABLED` | `true` | ido | IdO Kafka 릴레이 인-프로세스 활성 |
| `IDO_QIM_OUTBOX_RELAY_ENABLED` | `true` | ido | QIM Kafka 릴레이 인-프로세스 활성 |
| `IDO_PROVISIONING_RELAY_ENABLED` | `true` | ido | Provisioning HTTP 릴레이 인-프로세스 활성 |
| `IDO_WEBHOOK_RELAY_ENABLED` | `true` | ido | Webhook HTTP 릴레이 인-프로세스 활성 |
| `BATCH_IDO_KAFKA_RELAY_ENABLED` | `true` | batch | 배치 서비스 IdO Kafka 릴레이 활성 |
| `BATCH_IDO_QIM_RELAY_ENABLED` | `true` | batch | 배치 서비스 QIM Kafka 릴레이 활성 |
| `BATCH_QIM_KAFKA_RELAY_ENABLED` | `true` | batch | 배치 서비스 QIM(MariaDB) 릴레이 활성 |
| `BATCH_QSIGN_KAFKA_RELAY_ENABLED` | `true` | batch | 배치 서비스 QSign 릴레이 활성 |
| `BATCH_PROVISIONING_RELAY_ENABLED` | `true` | batch | 배치 서비스 Provisioning 릴레이 활성 |
| `BATCH_WEBHOOK_RELAY_ENABLED` | `true` | batch | 배치 서비스 Webhook 릴레이 활성 |

### 9.2 이관 전후 Feature Flag 상태

| 단계 | ido (기존) | batch (신규) | 비고 |
|------|-----------|-------------|------|
| 배포 초기 | 모두 `true` | 모두 `true` | 이중 실행 — FOR UPDATE SKIP LOCKED로 중복 방지 |
| 안정화 확인 후 | 모두 `false` | 모두 `true` | 완전 이관 완료 |

---

## 10. Flyway 마이그레이션 순서 보장

### 10.1 마이그레이션 실행 주체

```
ido 서비스 기동 시  → V1~V18 자동 적용
outbox-relay-batch 기동 시 → V19 자동 적용 (baselineVersion=18)
```

### 10.2 동시 기동 방지

```bash
# ido 서비스 완전 기동 확인 후 배치 서비스 기동
kubectl wait deployment/ido \
  --for=condition=Available \
  --timeout=300s \
  -n production

# ido 서비스 Flyway 마이그레이션 완료 확인
kubectl logs -l app=ido -n production --since=5m | \
  grep "Flyway.*successfully applied\|No migration necessary"
```

### 10.3 마이그레이션 중복 실행 방지

- Flyway는 `flyway_schema_history` 테이블로 중복 방지.
- `IF NOT EXISTS`를 V19 DDL에 포함 → 재실행 안전.
- 다중 배치 Pod 기동 시에도 Flyway 내부 락으로 단일 실행 보장.

---

## 11. 헬스체크 및 Readiness Probe

### 11.1 outbox-relay-batch 헬스체크 엔드포인트

```
GET http://outbox-relay-batch:8090/actuator/health
GET http://outbox-relay-batch:8090/actuator/health/liveness
GET http://outbox-relay-batch:8090/actuator/health/readiness
```

### 11.2 K8s 프로브 설정 예시

```yaml
# k8s/outbox-relay-batch-deployment.yml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8090
  initialDelaySeconds: 30
  periodSeconds: 30
  failureThreshold: 3
  timeoutSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8090
  initialDelaySeconds: 20
  periodSeconds: 10
  failureThreshold: 3
  timeoutSeconds: 5

# Graceful Shutdown 설정 (application.yml에 지정됨)
# server.shutdown: graceful
# spring.lifecycle.timeout-per-shutdown-phase: 30s
```

### 11.3 헬스체크 컴포넌트

Spring Boot Actuator 헬스체크는 다음 컴포넌트를 자동 포함:
- `db` — idem-hub/qim/qsign DataSource 커넥션 확인
- `redis` — Lettuce 커넥션 확인
- `diskSpace` — 디스크 공간 확인

---

## 12. Prometheus 메트릭 및 AlertManager 설정

### 12.1 핵심 메트릭 목록

| 메트릭 이름 | 타입 | 의미 | 경보 임계값 |
|------------|------|------|-----------|
| `batch_relay_ido_kafka_success_total` | Counter | IdO Kafka 릴레이 성공 수 | — |
| `batch_relay_ido_kafka_failure_total` | Counter | IdO Kafka 릴레이 실패 수 | 1분 내 5건 이상 WARN |
| `batch_relay_ido_kafka_dead_letter_total` | Counter | IdO Kafka DEAD_LETTER 수 | 1건 이상 CRITICAL |
| `batch_relay_provisioning_success_total` | Counter | Provisioning 릴레이 성공 수 | — |
| `batch_relay_provisioning_retry_total` | Counter | Provisioning 릴레이 재시도 수 | 30분 내 10건 이상 WARN |
| `batch_relay_provisioning_dead_letter_total` | Counter | Provisioning DEAD_LETTER 수 | 1건 이상 CRITICAL |
| `batch_relay_webhook_success_total` | Counter | Webhook 릴레이 성공 수 | — |
| `batch_relay_webhook_failure_total` | Counter | Webhook 릴레이 실패 수 | 1분 내 5건 이상 WARN |
| `batch_relay_webhook_dead_letter_total` | Counter | Webhook DEAD_LETTER 수 | 1건 이상 CRITICAL |

### 12.2 Prometheus Scrape 설정

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'outbox-relay-batch'
    scrape_interval: 15s
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['outbox-relay-batch:8090']
    relabel_configs:
      - target_label: application
        replacement: outbox-relay-batch
```

### 12.3 AlertManager 규칙 예시

```yaml
# alertmanager-rules.yml
groups:
  - name: outbox-relay-batch
    rules:
      - alert: OutboxDeadLetterDetected
        expr: increase(batch_relay_ido_kafka_dead_letter_total[5m]) > 0
        for: 0m
        labels:
          severity: critical
        annotations:
          summary: "Outbox DEAD_LETTER 발생"
          description: "5분 내 IdO Kafka DEAD_LETTER {{ $value }}건 발생. DB 확인 필요."

      - alert: ProvisioningRelayHighRetry
        expr: increase(batch_relay_provisioning_retry_total[30m]) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Provisioning 릴레이 재시도 급증"
          description: "30분 내 재시도 {{ $value }}건. 기관 서버 상태 확인 필요."

      - alert: BatchRelayPodNotRunning
        expr: up{job="outbox-relay-batch"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "outbox-relay-batch Pod 다운"
          description: "배치 릴레이 서비스가 응답하지 않음. 즉시 확인 필요."

      - alert: OutboxPendingBacklog
        expr: |
          # DB 직접 쿼리 메트릭 (pg_exporter 필요)
          pg_query_rows{query="pending_outbox_count"} > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Outbox 미처리 레코드 급증"
          description: "PENDING 레코드 {{ $value }}건 이상. 릴레이 지연 확인 필요."
```

### 12.4 DEAD_LETTER 모니터링 쿼리 (PostgreSQL)

```sql
-- ido.outbox DEAD_LETTER 현황 확인
SELECT event_type, COUNT(*) as dead_count, MAX(updated_at) as last_dead
FROM ido.outbox
WHERE status = 'DEAD_LETTER'
GROUP BY event_type
ORDER BY dead_count DESC;

-- 최근 1시간 내 DEAD_LETTER
SELECT id, event_type, error_message, retry_count, updated_at
FROM ido.outbox
WHERE status = 'DEAD_LETTER'
  AND updated_at >= NOW() - INTERVAL '1 hour'
ORDER BY updated_at DESC
LIMIT 20;

-- Provisioning DEAD_LETTER
SELECT agency_code, event_type, error_message, retry_count, last_attempted_at
FROM ido.provisioning_outbox
WHERE status = 'DEAD_LETTER'
ORDER BY last_attempted_at DESC
LIMIT 20;
```

---

## 13. 배포 후 검증 절차

### 13.1 기능 검증 체크리스트

```
[ ] 사용자 로그인 → Kafka 이벤트 발행 확인 (qim.user.events)
[ ] 사용자 등록 → Provisioning 이벤트 발행 확인
[ ] Webhook 설정 기관 → Webhook 수신 확인
[ ] ShedLock 테이블 확인 (락 정상 갱신)
[ ] Prometheus 메트릭 수집 확인
[ ] Outbox PENDING 레코드 0으로 수렴 확인
```

### 13.2 ShedLock 정상 동작 확인

```sql
-- ShedLock 최근 락 획득/해제 이력 확인
SELECT name, lock_until, locked_at, locked_by
FROM ido.shedlock
ORDER BY locked_at DESC;

-- 예상 결과: 각 Job별 lock_until이 과거 시간으로 갱신되어야 함
-- (현재 실행 중인 Job은 lock_until이 미래)
```

### 13.3 Outbox PENDING 레코드 수렴 확인

```sql
-- 처리 대기 레코드 확인 (배포 후 수분 내 0이 되어야 정상)
SELECT status, COUNT(*) FROM ido.outbox GROUP BY status;
SELECT status, COUNT(*) FROM ido.provisioning_outbox GROUP BY status;
SELECT status, COUNT(*) FROM ido.webhook_dispatch_outbox GROUP BY status;
SELECT status, COUNT(*) FROM qsign.outbox GROUP BY status;
```

### 13.4 Kafka Consumer Lag 확인

```bash
# Consumer Group Lag 확인
kafka-consumer-groups.sh \
  --bootstrap-server $KAFKA_SERVERS \
  --describe \
  --all-groups | grep -E "qim.user.events|qsign.auth.events|ido.outbox"

# LAG이 지속적으로 증가하면 Consumer 장애 의심
```

---

## 14. 롤백 절차

### 14.1 outbox-relay-batch 롤백

```bash
# outbox-relay-batch 이전 버전으로 롤백
kubectl rollout undo deployment/outbox-relay-batch -n production
kubectl rollout status deployment/outbox-relay-batch -n production

# ido 서비스 Feature Flag 재활성화 (인-프로세스 릴레이 복구)
kubectl set env deployment/ido \
  IDO_OUTBOX_RELAY_ENABLED=true \
  IDO_QIM_OUTBOX_RELAY_ENABLED=true \
  IDO_PROVISIONING_RELAY_ENABLED=true \
  IDO_WEBHOOK_RELAY_ENABLED=true \
  -n production
```

### 14.2 Flyway 마이그레이션 롤백

> **⚠️ Flyway는 롤백을 지원하지 않음 (Community Edition)**
> DDL 변경은 수동 롤백 필요

```sql
-- V19 shedlock 테이블 롤백 (outbox-relay-batch 미사용 시)
-- 주의: 실행 중인 ShedLock 세션 없음을 확인 후 실행
DROP TABLE IF EXISTS ido.shedlock;
DROP INDEX IF EXISTS ido.idx_shedlock_lock_until;

-- flyway_schema_history에서 V19 삭제 (재적용 방지)
DELETE FROM ido.flyway_schema_history WHERE version = '19';
```

### 14.3 롤백 판단 기준

| 증상 | 판단 | 조치 |
|------|------|------|
| Outbox PENDING 레코드 급증 (1000건 이상) | 롤백 검토 | Feature Flag 재활성 먼저 시도 |
| ShedLock 테이블 Lock 해제 안 됨 | JDBC 수동 삭제 | `DELETE FROM ido.shedlock` |
| Kafka Consumer Lag 급증 | 즉시 확인 | Consumer 상태 및 배치 로그 확인 |
| DEAD_LETTER 100건 이상 급증 | 즉시 롤백 | 배치 버그 가능성 |
| outbox-relay-batch CrashLoopBackOff | 즉시 롤백 | 이전 버전으로 복구 |

---

## 15. 운영 중 장애 대응 매뉴얼

### 15.1 ShedLock 데드락 (Pod 강제 종료로 락 미해제)

```sql
-- 락 강제 해제 (lockAtMostFor 시간 전에 수동 처리 필요 시)
-- 주의: 실행 중인 Job 없음을 확인 후 실행
UPDATE ido.shedlock
SET lock_until = NOW() - INTERVAL '1 second'
WHERE name = 'ido-kafka-relay'  -- 대상 Job 명
  AND lock_until > NOW();        -- 현재 잠긴 것만

-- 전체 잠금 강제 해제 (긴급 시)
UPDATE ido.shedlock SET lock_until = NOW() - INTERVAL '1 second';
```

### 15.2 DEAD_LETTER 레코드 재처리

```sql
-- DEAD_LETTER → PENDING 복구 (재시도 횟수 초기화)
UPDATE ido.outbox
SET status = 'PENDING',
    retry_count = 0,
    next_retry_at = NULL,
    error_message = 'MANUAL_RETRY'
WHERE status = 'DEAD_LETTER'
  AND id IN ('id1', 'id2');  -- 재처리할 레코드 ID 목록

-- Provisioning DEAD_LETTER 재처리
UPDATE ido.provisioning_outbox
SET status = 'PENDING',
    retry_count = 0,
    next_retry_at = NULL
WHERE status = 'DEAD_LETTER'
  AND agency_code = 'AGENCY_001';  -- 특정 기관만 재처리
```

### 15.3 기관 서버 장애 시 Provisioning 일시 중단

```bash
# 해당 기관 endpoint를 임시 비활성화
psql -h $IDO_DB_HOST -U $IDO_DB_USERNAME -d $IDO_DB_NAME -c "
UPDATE ido.agency_endpoint_registry
SET active = FALSE
WHERE agency_code = 'AGENCY_001'
  AND endpoint_type = 'PROVISIONING';"

# 기관 서버 복구 후 재활성화
psql -h $IDO_DB_HOST -U $IDO_DB_USERNAME -d $IDO_DB_NAME -c "
UPDATE ido.agency_endpoint_registry
SET active = TRUE
WHERE agency_code = 'AGENCY_001'
  AND endpoint_type = 'PROVISIONING';"
```

### 15.4 outbox-relay-batch OOM 발생 시

```bash
# JVM 메모리 덤프 수집 (진단용)
kubectl exec -it $(kubectl get pod -l app=outbox-relay-batch -n production \
  -o jsonpath='{.items[0].metadata.name}') -n production -- \
  jcmd 1 VM.heap_dump /tmp/heap.hprof

kubectl cp production/$(kubectl get pod -l app=outbox-relay-batch -n production \
  -o jsonpath='{.items[0].metadata.name}'):/tmp/heap.hprof ./heap.hprof

# JVM 힙 크기 조정 (deployment.yml)
# env:
#   - name: JAVA_OPTS
#     value: "-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

---

## 부록

### A. 서비스 포트 목록

| 서비스 | 포트 | 프로토콜 |
|--------|------|---------|
| ido | 8080 | HTTP |
| q-im | 8081 | HTTP |
| q-sign | 8082 | HTTP |
| outbox-relay-batch | 8090 | HTTP (Actuator only) |

### B. 환경별 설정 차이

| 항목 | 개발 | 스테이징 | 운영 |
|------|------|---------|------|
| Kafka 복제 인수 | 1 | 3 | 3 |
| DB 풀 크기 (batch) | 2 | 3 | 5 |
| ShedLock lockAtMostFor | 10s | 10s | 10s |
| ShedLock Provider | JDBC (Redis 없는 경우) | Redis+JDBC | Redis+JDBC |
| Feature Flag (인-프로세스) | true | false (이관 후) | false (이관 후) |
| mTLS KeyStore | 없음 | 테스트 인증서 | 운영 인증서 |
| DEAD_LETTER 알림 | 없음 | Slack | PagerDuty + Slack |

### C. 연락처 및 에스컬레이션

| 역할 | 담당 | 연락처 |
|------|------|--------|
| 인프라/K8s | 운영팀 | — |
| DB 관리 | DBA | — |
| 기관 연동 | 대외협력팀 | — |
| 개발 지원 | 개발팀 | — |

---

*본 문서는 통합인증 플랫폼 운영 배포 시 필수 확인 사항을 정리한 문서입니다.*
*운영 환경 변경 시 즉시 갱신 바랍니다.*
