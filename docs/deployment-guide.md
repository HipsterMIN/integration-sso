# OnePass 통합인증 플랫폼 — 운영 배포 가이드

> **문서 버전**: v1.0  
> **최종 수정**: 2026-05-14  
> **적용 범위**: Sprint 14 (전 기관 프로비저닝) · Sprint 15 (양방향 Gateway API) · Sprint 16 (Java SDK)  
> **대상 독자**: OnePass 운영팀, 유관기관(Agency) 기술 담당자, 인프라 엔지니어

---

## 목차

1. [아키텍처 개요](#1-아키텍처-개요)
2. [사전 요구사항](#2-사전-요구사항)
3. [OnePass 서버 측 배포 절차](#3-onepass-서버-측-배포-절차)
   - 3.1 [빌드 및 컨테이너 이미지 생성](#31-빌드-및-컨테이너-이미지-생성)
   - 3.2 [데이터베이스 마이그레이션 (Flyway)](#32-데이터베이스-마이그레이션-flyway)
   - 3.3 [K8s Secret/ConfigMap 업데이트](#33-k8s-secretconfigmap-업데이트)
   - 3.4 [기관 엔드포인트 레지스트리 등록](#34-기관-엔드포인트-레지스트리-등록)
   - 3.5 [K8s 롤링 배포](#35-k8s-롤링-배포)
   - 3.6 [배포 후 검증](#36-배포-후-검증)
4. [유관기관(Agency) 클라이언트 연동 가이드](#4-유관기관agency-클라이언트-연동-가이드)
   - 4.1 [연동 전 준비 사항](#41-연동-전-준비-사항)
   - 4.2 [API Key 발급 및 등록 절차](#42-api-key-발급-및-등록-절차)
   - 4.3 [Java SDK 연동 (onepass-agency-sdk)](#43-java-sdk-연동-onepass-agency-sdk)
   - 4.4 [인바운드 엔드포인트 구현 (기관 Webhook 수신)](#44-인바운드-엔드포인트-구현-기관-webhook-수신)
   - 4.5 [아웃바운드 이벤트 전송 (기관 → OnePass)](#45-아웃바운드-이벤트-전송-기관--onepass)
   - 4.6 [연동 상태 확인](#46-연동-상태-확인)
5. [API 레퍼런스](#5-api-레퍼런스)
   - 5.1 [인바운드 이벤트 수신](#51-인바운드-이벤트-수신)
   - 5.2 [아웃바운드 알림 발송 트리거](#52-아웃바운드-알림-발송-트리거)
   - 5.3 [기관 연동 상태 조회](#53-기관-연동-상태-조회)
   - 5.4 [프로비저닝 이벤트 수신 (기관 서버)](#54-프로비저닝-이벤트-수신-기관-서버)
6. [멱등성 처리 가이드](#6-멱등성-처리-가이드)
7. [보안 요구사항](#7-보안-요구사항)
8. [장애/실패 시나리오 및 해결 방법](#8-장애실패-시나리오-및-해결-방법)
   - 8.1 [프로비저닝 실패](#81-프로비저닝-실패)
   - 8.2 [인바운드 이벤트 처리 실패](#82-인바운드-이벤트-처리-실패)
   - 8.3 [API Key 인증 실패](#83-api-key-인증-실패)
   - 8.4 [멱등성 충돌 (409 Conflict)](#84-멱등성-충돌-409-conflict)
   - 8.5 [기관 엔드포인트 응답 없음 (타임아웃)](#85-기관-엔드포인트-응답-없음-타임아웃)
   - 8.6 [Redis 장애](#86-redis-장애)
   - 8.7 [Flyway 마이그레이션 실패](#87-flyway-마이그레이션-실패)
   - 8.8 [DEAD_LETTER 누적 처리](#88-dead_letter-누적-처리)
9. [운영 모니터링](#9-운영-모니터링)
10. [롤백 절차](#10-롤백-절차)
11. [환경변수 전체 목록](#11-환경변수-전체-목록)

---

## 1. 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        OnePass 통합인증 플랫폼 (IdO)                      │
│                                                                         │
│  ┌──────────────┐    ┌─────────────────────┐    ┌──────────────────┐   │
│  │ Q-IM (회원)  │───▶│  QimEventConsumer   │───▶│ProvisioningService│  │
│  │ Kafka 이벤트 │    │  USER_REGISTERED    │    │Virtual Thread 68  │  │
│  └──────────────┘    │  BIZ_CONVERTED      │    │parallel HTTP POST │  │
│                      └─────────────────────┘    └────────┬─────────┘  │
│                                                           │             │
│                      ┌─────────────────────┐    ┌────────▼─────────┐  │
│                      │  ProvisioningOutbox  │◀───│provisioning_outbox│  │
│                      │  Relay (@Scheduled) │    │PENDING/COMPLETED  │  │
│                      │  지수백오프 1→5→30분  │    │DEAD_LETTER       │  │
│                      └─────────────────────┘    └──────────────────┘  │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                  AgencyGatewayController                         │  │
│  │  POST /inbound/event ◀── 기관 전송 (X-Api-Key 인증)              │  │
│  │  PATCH /outbound/notify ──▶ 기관 WEBHOOK 발송                   │  │
│  │  GET  /status/{agencyCode} ──▶ 연동 상태 조회                   │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
          ▲  ▼ HTTPS (mTLS, Sprint 17)
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────────────┐
│   기관 A (MOIS)  │    │  기관 B (NTS)   │    │  기관 C (...)  × 68개   │
│  - Webhook 수신  │    │  - Webhook 수신  │    │                         │
│  - 이벤트 전송   │    │  - 이벤트 전송   │    │                         │
└─────────────────┘    └─────────────────┘    └─────────────────────────┘
```

### 핵심 컴포넌트

| 컴포넌트 | 역할 | Sprint |
|---------|------|--------|
| `ProvisioningService` | 회원가입/전환 시 68개 기관 병렬 HTTP 전송 | 14 |
| `ProvisioningOutboxRelay` | 실패 건 지수 백오프 재시도 (1→5→30분) | 14 |
| `agency_endpoint_registry` | 68개 기관 API 엔드포인트 레지스트리 | 14 |
| `AgencyGatewayController` | 양방향 이벤트 게이트웨이 | 15 |
| `GatewayIdempotencyStore` | Redis SET NX 24h 중복 방어 | 15 |
| `onepass-agency-sdk` | 기관 연동용 Java SDK (JDK 8+) | 16 |

---

## 2. 사전 요구사항

### OnePass 서버 인프라

| 항목 | 최소 요구사항 | 권장 |
|------|-------------|------|
| JDK | 21 (Virtual Thread 필수) | 21 LTS |
| PostgreSQL | 15+ | 16 |
| Redis | 6.2+ | 7.2 |
| Kafka | 2.8+ | 3.6 |
| Kubernetes | 1.26+ | 1.29+ |
| 메모리 (Pod) | 512Mi | 1Gi |
| CPU (Pod) | 250m | 500m |

### 유관기관 클라이언트

| 항목 | 최소 요구사항 | 비고 |
|------|-------------|------|
| JDK (SDK 사용 시) | **8+** | JDK 버전 무관 |
| HTTPS 인증서 | TLS 1.2+ | 운영 필수 |
| 방화벽 | OnePass IP 허용 | 아웃바운드 443 |
| Webhook 엔드포인트 | HTTPS URL 등록 | 인바운드 수신용 |

---

## 3. OnePass 서버 측 배포 절차

### 3.1 빌드 및 컨테이너 이미지 생성

```bash
# 1. 프로젝트 루트에서 ido 모듈 빌드
cd /path/to/onepass-platform

./gradlew :ido:bootJar --no-daemon \
  -x test \
  -Dspring.profiles.active=prod

# 빌드 산출물 확인
ls -lh ido/build/libs/ido-*.jar

# 2. Docker 이미지 빌드
docker build \
  --build-arg JAR_FILE=ido/build/libs/ido-0.1.0-SNAPSHOT.jar \
  --build-arg SPRING_PROFILES_ACTIVE=prod \
  -t registry.smes.go.kr/onepass/ido:$(git rev-parse --short HEAD) \
  -f ido/Dockerfile .

# 3. 이미지 레지스트리 푸시
docker push registry.smes.go.kr/onepass/ido:$(git rev-parse --short HEAD)
```

> ⚠️ **주의**: `ido:test` 는 DB/Redis/Kafka 없이도 통과하지만, 배포 전 반드시 `DOCKER_UNAVAILABLE=false ./gradlew :ido:test` 로 전체 테스트를 실행하세요.

---

### 3.2 데이터베이스 마이그레이션 (Flyway)

Sprint 14/15 배포 시 **Flyway V15 → V16** 마이그레이션이 자동 실행됩니다.  
애플리케이션 기동 전 아래 사항을 확인하세요.

#### 마이그레이션 대상 테이블

| 버전 | 생성 테이블 | Sprint |
|------|------------|--------|
| V15 | `ido.agency_endpoint_registry`, `ido.provisioning_outbox` | 14 |
| V16 | `ido.gateway_inbound_audit`, `ido.gateway_outbound_audit` | 15 |

#### 사전 수동 검증 (권장)

```sql
-- 현재 Flyway 적용 버전 확인
SELECT version, description, installed_on, success
FROM ido.flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 5;

-- V15 마이그레이션 후 테이블 확인
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'ido'
  AND table_name IN (
    'agency_endpoint_registry',
    'provisioning_outbox',
    'gateway_inbound_audit',
    'gateway_outbound_audit'
  );

-- 예상 결과: 4개 테이블 모두 존재
```

#### Flyway 수동 실행 (비상 시)

```bash
# Flyway CLI를 통한 수동 마이그레이션
flyway \
  -url="jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}?currentSchema=ido" \
  -user="${DB_USERNAME}" \
  -password="${DB_PASSWORD}" \
  -locations="filesystem:ido/src/main/resources/db/migration" \
  -schemas=ido \
  migrate

# 마이그레이션 상태 확인
flyway info
```

---

### 3.3 K8s Secret/ConfigMap 업데이트

#### ConfigMap 업데이트 (프로비저닝 + 게이트웨이 Feature Flags)

`infra/k8s/configmaps/ido-configmap.yml`에 아래 항목을 **추가**합니다:

```yaml
data:
  # ── Sprint 14: 전 기관 프로비저닝 Feature Flags ───────────────────────────
  IDO_PROVISIONING_ENABLED: "true"           # 프로비저닝 기능 On/Off
  IDO_PROVISIONING_RELAY_ENABLED: "true"     # Outbox Relay 활성화
  IDO_PROVISIONING_RELAY_INTERVAL_MS: "30000" # Relay 폴링 주기 (30초)
  IDO_PROVISIONING_BATCH_SIZE: "50"          # 한 번에 처리할 아웃박스 건수
  IDO_PROVISIONING_MAX_PARALLEL_AGENCIES: "100" # 최대 병렬 기관 수
  IDO_PROVISIONING_TIMEOUT_MS: "0"           # 0이면 기관별 설정 사용

  # ── Sprint 15: Gateway API Feature Flags ──────────────────────────────────
  IDO_GATEWAY_INBOUND_ENABLED: "true"
  IDO_GATEWAY_OUTBOUND_ENABLED: "true"
```

```bash
# ConfigMap 적용
kubectl apply -f infra/k8s/configmaps/ido-configmap.yml -n smes

# 적용 확인
kubectl get configmap ido-config -n smes -o yaml | grep -A 20 "PROVISIONING"
```

#### Secret 업데이트 (기관 API Key 해시 저장은 DB 직접 관리)

> 기관 API Key는 K8s Secret이 아니라 **`ido.agency_meta.api_key_hash`** 테이블에 SHA-256 해시로 저장합니다.  
> Secret 교체 절차는 [§4.2 API Key 발급 및 등록 절차](#42-api-key-발급-및-등록-절차)를 참조하세요.

---

### 3.4 기관 엔드포인트 레지스트리 등록

배포 후 각 기관의 API 엔드포인트를 `ido.agency_endpoint_registry` 테이블에 등록합니다.

#### 기관 메타 등록 (agency_meta)

```sql
-- 기관 메타 등록 (API Key는 SHA-256 해시로만 저장)
-- 원문 API Key: openssl rand -hex 32 로 생성 → 기관에 전달
-- 해시 생성: echo -n "원문키" | sha256sum

INSERT INTO ido.agency_meta (
    agency_code,
    agency_name,
    api_key_hash,      -- SHA-256(원문_API_Key) — 원문 절대 저장 금지
    active,
    created_at
) VALUES (
    'MOIS',            -- 행정안전부
    '행정안전부',
    'e3b0c44298fc1c149afb...',  -- 실제 해시값으로 교체
    TRUE,
    NOW()
) ON CONFLICT (agency_code) DO UPDATE
    SET api_key_hash = EXCLUDED.api_key_hash,
        updated_at   = NOW();
```

#### 기관 엔드포인트 등록 (agency_endpoint_registry)

```sql
-- 기관별 엔드포인트 등록 (모든 운영 URL은 HTTPS 필수)
INSERT INTO ido.agency_endpoint_registry (
    agency_code,
    endpoint_type,
    endpoint_url,
    http_method,
    auth_type,
    auth_credential_ref,   -- K8s Secret 경로 (평문 저장 금지)
    timeout_ms,
    is_active,
    note
) VALUES
    -- PROVISIONING: 회원가입/전환 알림 수신
    ('MOIS', 'PROVISIONING',
     'https://api.mois.go.kr/onepass/provisioning/users',
     'POST', 'API_KEY',
     'k8s://smes/mois-api-credentials',  -- K8s Secret 참조
     5000, TRUE, '행정안전부 프로비저닝 수신 엔드포인트'),

    -- WEBHOOK: 양방향 이벤트 수신
    ('MOIS', 'WEBHOOK',
     'https://api.mois.go.kr/onepass/webhook',
     'POST', 'HMAC',
     'k8s://smes/mois-hmac-secret',
     5000, TRUE, '행정안전부 Webhook 수신 엔드포인트'),

    -- GATEWAY_INBOUND: 기관 → OnePass 이벤트 전송
    ('MOIS', 'GATEWAY_INBOUND',
     'https://onepass.go.kr/api/v1/agency/gateway/inbound/event',
     'POST', 'API_KEY',
     NULL,
     5000, TRUE, 'OnePass Gateway 인바운드 URL (기관에 제공)'),

    -- STATUS: 헬스체크
    ('MOIS', 'STATUS',
     'https://api.mois.go.kr/health',
     'GET', 'NONE',
     NULL,
     2000, TRUE, '행정안전부 헬스체크')

ON CONFLICT (agency_code, endpoint_type) DO UPDATE
    SET endpoint_url        = EXCLUDED.endpoint_url,
        auth_credential_ref = EXCLUDED.auth_credential_ref,
        is_active           = EXCLUDED.is_active,
        updated_at          = NOW();
```

#### 등록 검증

```sql
-- 등록된 엔드포인트 확인
SELECT
    aer.agency_code,
    am.agency_name,
    aer.endpoint_type,
    aer.endpoint_url,
    aer.auth_type,
    aer.is_active,
    aer.timeout_ms
FROM ido.agency_endpoint_registry aer
JOIN ido.agency_meta am ON am.agency_code = aer.agency_code
WHERE aer.is_active = TRUE
ORDER BY aer.agency_code, aer.endpoint_type;
```

---

### 3.5 K8s 롤링 배포

```bash
# 1. 새 이미지 태그로 Deployment 업데이트
IMAGE_TAG=$(git rev-parse --short HEAD)

kubectl set image deployment/ido \
  ido=registry.smes.go.kr/onepass/ido:${IMAGE_TAG} \
  -n smes

# 2. 롤링 업데이트 모니터링
kubectl rollout status deployment/ido -n smes --timeout=300s

# 3. Pod 상태 확인
kubectl get pods -n smes -l app=ido -w

# 4. 기동 후 Flyway 마이그레이션 로그 확인
kubectl logs -n smes -l app=ido --since=5m | grep -E "Flyway|migration|V15|V16"

# 기대 로그:
# Successfully applied 2 migrations to schema "ido" (execution time 00:01.234s)
# Current version of schema "ido": 16
```

#### 배포 전략: Zero-Downtime Rolling Update

```yaml
# infra/k8s/deployments/ido-deployment.yml 확인 사항
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0   # 항상 최소 replicas 수 유지
    maxSurge: 1         # 최대 1개 추가 Pod 허용
```

> **DB 마이그레이션과 Rolling Update 순서**  
> Flyway는 애플리케이션 기동 시 자동 실행됩니다.  
> 신구 버전이 동시에 기동될 수 있으므로, **V15/V16 마이그레이션은 하위 호환성**을 유지합니다 (기존 테이블 구조 변경 없음, 신규 테이블 추가만).

---

### 3.6 배포 후 검증

#### Actuator Health Check

```bash
# Pod IP 또는 Service를 통한 헬스 확인
kubectl exec -n smes deploy/ido -- \
  curl -s http://localhost:8083/actuator/health | jq .

# 기대 응답:
# {
#   "status": "UP",
#   "components": {
#     "db": { "status": "UP" },
#     "redis": { "status": "UP" },
#     "kafka": { "status": "UP" }
#   }
# }
```

#### 프로비저닝 Outbox 릴레이 확인

```bash
# 릴레이 로그 확인
kubectl logs -n smes deploy/ido | grep "ProvisioningOutboxRelay"

# 기대 로그:
# [ProvisioningOutboxRelay] relay 시작: enabled=true batchSize=50
# [ProvisioningOutboxRelay] 처리 완료: processed=0 (PENDING 없음)
```

#### Gateway API 동작 확인

```bash
# 상태 조회 테스트 (AGENCY_STUB_001 — 개발용 시드 데이터)
curl -s https://onepass.go.kr/api/v1/agency/gateway/status/AGENCY_STUB_001 \
  -H "X-Agency-Code: AGENCY_STUB_001" \
  -H "X-Agency-Key: your-test-api-key" | jq .
```

---

## 4. 유관기관(Agency) 클라이언트 연동 가이드

### 4.1 연동 전 준비 사항

기관 담당자는 연동 전 아래 항목을 OnePass 운영팀에 제출해야 합니다.

#### 제출 서류 체크리스트

| 항목 | 내용 | 필수 여부 |
|------|------|---------|
| 기관 코드 신청 | 영문 대문자/숫자/언더스코어 (최대 50자) | ✅ 필수 |
| 프로비저닝 수신 URL | `https://` 필수, TLS 1.2+ | ✅ 필수 |
| Webhook 수신 URL | `https://` 필수 | ✅ 필수 (양방향 사용 시) |
| 방화벽 허용 IP 신청 | OnePass 서버 NAT IP 목록 | ✅ 필수 |
| 담당자 연락처 | 장애 대응 연락처 (24시간) | ✅ 필수 |
| 테스트 환경 URL | 개발/스테이징 URL | 권장 |

#### OnePass 운영팀에서 기관으로 제공하는 정보

| 항목 | 예시 |
|------|------|
| 기관 코드 | `MOIS` |
| API Key (원문) | `a1b2c3d4...` (32바이트 hex, **채널 외 전달 금지**) |
| OnePass Gateway URL | `https://onepass.go.kr/api/v1/agency/gateway` |
| HMAC 공유 비밀키 (선택) | Sprint 17 이후 의무화 예정 |
| OnePass 서버 IP 목록 | 방화벽 허용용 |

---

### 4.2 API Key 발급 및 등록 절차

#### OnePass 운영팀 처리 절차

```bash
# 1. API Key 생성 (원문, 32바이트 hex)
AGENCY_API_KEY=$(openssl rand -hex 32)
echo "기관에 전달할 원문 Key: ${AGENCY_API_KEY}"

# 2. SHA-256 해시 계산
AGENCY_API_KEY_HASH=$(echo -n "${AGENCY_API_KEY}" | sha256sum | awk '{print $1}')
echo "DB 저장 해시: ${AGENCY_API_KEY_HASH}"

# 3. DB에 해시만 저장 (원문 절대 저장 금지)
psql -U onepass -d onepass_db << EOF
INSERT INTO ido.agency_meta (agency_code, agency_name, api_key_hash, active)
VALUES ('MOIS', '행정안전부', '${AGENCY_API_KEY_HASH}', TRUE)
ON CONFLICT (agency_code) DO UPDATE
  SET api_key_hash = EXCLUDED.api_key_hash,
      updated_at   = NOW();
EOF

# 4. 기관에 원문 Key 보안 채널로 전달 (암호화된 메일, 보안 메신저 등)
# ⚠️  API Key 원문은 이 순간 이후 절대 저장하지 않음
unset AGENCY_API_KEY
```

#### API Key 교체 (분기별 권장)

```bash
# 1. 새 Key 생성
NEW_KEY=$(openssl rand -hex 32)
NEW_HASH=$(echo -n "${NEW_KEY}" | sha256sum | awk '{print $1}')

# 2. DB 업데이트 (즉시 효력 발생)
psql -c "UPDATE ido.agency_meta SET api_key_hash='${NEW_HASH}', updated_at=NOW() WHERE agency_code='MOIS'"

# 3. 기관에 새 Key 전달 → 기관이 SDK/설정 업데이트 완료 확인
# 4. 이전 Key는 즉시 무효화됨
unset NEW_KEY
```

---

### 4.3 Java SDK 연동 (onepass-agency-sdk)

#### Gradle 의존성 추가

```groovy
// build.gradle (Groovy DSL) — JDK 8+ 모두 지원
dependencies {
    implementation 'kr.go.smes:onepass-agency-sdk:0.1.0-SNAPSHOT'

    // HTTP 어댑터 선택 (하나만 선택, 미선택 시 JDK HttpURLConnection 사용)
    // OkHttp3 사용 시:
    // implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    // Apache HC5 사용 시:
    // implementation 'org.apache.httpcomponents.client5:httpclient5:5.3.1'
}
```

```kotlin
// build.gradle.kts (Kotlin DSL)
dependencies {
    implementation("kr.go.smes:onepass-agency-sdk:0.1.0-SNAPSHOT")
}
```

#### Maven 의존성 추가

```xml
<dependency>
    <groupId>kr.go.smes</groupId>
    <artifactId>onepass-agency-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

#### 클라이언트 초기화

```java
import kr.go.smes.sdk.agency.AgencyGatewayClient;
import kr.go.smes.sdk.agency.http.OkHttpAgencyAdapter;

// ── 방법 1: 기본 (JDK HttpURLConnection, 의존성 없음) ─────────────────────
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://onepass.go.kr")          // OnePass 서버 URL
    .apiKey("a1b2c3d4...")                      // 발급받은 API Key 원문
    .agencyCode("MOIS")                        // 기관 코드
    .connectTimeoutMs(5_000)                   // 연결 타임아웃 5초
    .readTimeoutMs(30_000)                     // 읽기 타임아웃 30초
    .build();

// ── 방법 2: OkHttp3 어댑터 (커넥션 풀 + HTTP/2) ──────────────────────────
OkHttpClient okHttp = new OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
    .build();

AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://onepass.go.kr")
    .apiKey(System.getenv("ONEPASS_API_KEY"))  // 환경변수에서 로드 권장
    .agencyCode("MOIS")
    .httpAdapter(new OkHttpAgencyAdapter(okHttp))
    .build();

// ── 방법 3: Spring RestTemplate 어댑터 (Spring 환경) ──────────────────────
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://onepass.go.kr")
    .apiKey(System.getenv("ONEPASS_API_KEY"))
    .agencyCode("MOIS")
    .httpAdapter((method, url, headers, body) -> {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
        ResponseEntity<String> resp = restTemplate.exchange(
            url, HttpMethod.resolve(method), entity, String.class);
        return GatewayResponse.of(
            resp.getStatusCode().value(), resp.getBody(), null, null);
    })
    .build();
```

> ⚠️ **보안 주의사항**: API Key 원문을 코드에 하드코딩하지 마세요.  
> `System.getenv()`, Spring `@Value`, AWS Secrets Manager, Vault 등 외부 주입 방식을 사용하세요.

#### Spring Bean 등록 예시

```java
@Configuration
public class OnePassSdkConfig {

    @Value("${onepass.base-url}")
    private String baseUrl;

    @Value("${onepass.api-key}")  // application.yml 또는 환경변수에서 주입
    private String apiKey;

    @Value("${onepass.agency-code}")
    private String agencyCode;

    @Bean
    @ConditionalOnMissingBean
    public AgencyGatewayClient agencyGatewayClient() {
        return AgencyGatewayClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .agencyCode(agencyCode)
            .connectTimeoutMs(5_000)
            .readTimeoutMs(30_000)
            .build();
    }
}
```

```yaml
# application.yml (기관 서버)
onepass:
  base-url: https://onepass.go.kr
  api-key: ${ONEPASS_API_KEY}          # 환경변수 또는 K8s Secret
  agency-code: MOIS
```

---

### 4.4 인바운드 엔드포인트 구현 (기관 Webhook 수신)

OnePass에서 기관으로 이벤트를 발송할 때, 기관 서버는 아래 스펙의 엔드포인트를 구현해야 합니다.

#### 기관 서버 Webhook 수신 엔드포인트 스펙

```
POST {기관_WEBHOOK_URL}
Content-Type: application/json
X-Onepass-Event-Type: {이벤트_타입}
X-Idempotency-Key: {UUID_v7}
X-Correlation-Id: {추적_ID}
X-Internal-Sig: {HMAC-SHA256_서명}  ← Sprint 17 이후 필수
```

#### 기관 서버 구현 예시 (Spring Boot)

```java
@RestController
@RequestMapping("/onepass/webhook")
public class OnePassWebhookController {

    private static final String SECRET = System.getenv("ONEPASS_HMAC_SECRET");

    @PostMapping
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody String payload,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Onepass-Event-Type") String eventType,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Internal-Sig", required = false) String signature) {

        // 1. 멱등성 키 중복 체크 (DB 또는 Redis)
        if (isDuplicate(idempotencyKey)) {
            return ResponseEntity.ok().build();  // 이미 처리됨 → 200으로 응답 (재전송 방지)
        }

        // 2. HMAC 서명 검증 (Sprint 17 이후 필수, 현재 선택)
        if (SECRET != null && signature != null) {
            if (!verifySignature(payload, signature)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }

        // 3. 이벤트 처리
        switch (eventType) {
            case "USER_PROVISIONED":
                handleUserProvisioned(payload, correlationId);
                break;
            case "NOTIFY_USER":
                handleNotifyUser(payload, correlationId);
                break;
            default:
                // 알 수 없는 이벤트 타입: 200으로 응답 (재전송 방지)
                log.warn("알 수 없는 이벤트 타입: {}", eventType);
        }

        // 4. 멱등성 키 저장 (24시간 이내 중복 수신 방지)
        markProcessed(idempotencyKey);

        return ResponseEntity.ok().build();  // 반드시 200 응답
    }

    private boolean verifySignature(String payload, String signature) {
        // HmacSigner를 직접 사용하거나 javax.crypto로 구현
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                computedHex.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
```

#### 기관 서버가 반드시 지켜야 할 사항

| 항목 | 요구사항 |
|------|---------|
| **응답 코드** | 정상 처리: `200 OK` / 처리 중 오류: `500` → OnePass가 재전송 |
| **응답 시간** | 5초 이내 (타임아웃 기본값, 엔드포인트 등록 시 변경 가능) |
| **멱등성** | 동일 `X-Idempotency-Key` 재수신 시 `200`으로 응답 (중복 처리 금지) |
| **HTTPS** | 운영 환경 필수 (자체 서명 인증서 불가) |
| **PII 처리** | 수신한 페이로드의 개인정보는 기관 내 데이터 처리 방침에 따라 처리 |

#### 프로비저닝 이벤트 페이로드 예시

```json
{
  "onepass_user_id": "01914bf9-a5f2-7000-b000-000000000001",
  "event_type": "USER_REGISTERED",
  "identity_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "registered_at": "2026-05-14T10:30:00Z",
  "idempotency_key": "01914bf9-a5f2-7000-b000-000000000002",
  "correlation_id": "01914bf9-a5f2-7000-b000-000000000003",
  "schema_version": "1"
}
```

> 🔒 **PII 정책**: `identity_hash` 는 `SHA-256(qimUserId:registeredAtEpochMilli)` 값으로, **실명/전화번호 평문은 절대 포함되지 않습니다**.

---

### 4.5 아웃바운드 이벤트 전송 (기관 → OnePass)

기관에서 OnePass로 사용자 정보 변경 등의 이벤트를 전송합니다.

#### SDK를 사용한 이벤트 전송

```java
@Service
public class OnePassEventPublisher {

    private final AgencyGatewayClient gatewayClient;

    public OnePassEventPublisher(AgencyGatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    /**
     * 사용자 정보 변경 이벤트 전송
     */
    public void notifyUserUpdated(String agencyUserId, String changeType) {
        // 1. 멱등성 키 생성 (재시도 시 동일 키 사용)
        String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");

        // 2. PII 최소화된 페이로드 구성 (실명/전화 제외)
        String payload = String.format(
            "{\"agency_user_id\":\"%s\",\"change_type\":\"%s\",\"changed_at\":\"%s\"}",
            agencyUserId, changeType, Instant.now().toString()
        );

        InboundEvent event = InboundEvent.builder()
            .eventType("AGENCY_USER_UPDATED")
            .agencyCode("MOIS")
            .idempotencyKey(idempotencyKey)
            .payloadJson(payload)
            .correlationId(MDC.get("traceId"))  // 분산 추적 연동
            .build();

        try {
            GatewayResponse response = gatewayClient.sendInbound(event);

            if (response.isSuccess()) {
                log.info("OnePass 이벤트 전송 성공: eventType=AGENCY_USER_UPDATED " +
                         "correlationId={}", response.getCorrelationId());
            }
        } catch (AgencyHttpException e) {
            if (e.isIdempotencyConflict()) {
                // 409: 이미 처리된 이벤트 — 무시
                log.info("멱등성 충돌 (이미 처리됨): idempotencyKey={}", idempotencyKey);
            } else if (e.isServerError()) {
                // 5xx: 재시도 필요
                log.error("OnePass 서버 오류 (재시도 필요): status={}", e.getHttpStatus());
                scheduleRetry(event);  // 재시도 큐에 등록
            } else {
                // 4xx: 요청 오류 — 재시도 불필요
                log.error("요청 오류 (재시도 불필요): status={} body={}",
                    e.getHttpStatus(), e.getResponseBody());
            }
        }
    }

    private void scheduleRetry(InboundEvent event) {
        // 기관 자체 재시도 로직 구현
        // 권장: Outbox 패턴 또는 메시지 큐 (Kafka, SQS 등)
    }
}
```

#### 지원하는 이벤트 타입

| 이벤트 타입 | 설명 | 페이로드 필수 필드 |
|------------|------|-----------------|
| `AGENCY_USER_UPDATED` | 기관 사용자 정보 변경 | `agency_user_id`, `change_type` |
| `AGENCY_USER_WITHDRAWN` | 기관 사용자 탈퇴 | `agency_user_id`, `withdrawn_at` |
| `AGENCY_USER_REGISTERED` | 기관 신규 사용자 | `agency_user_id`, `registered_at` |
| `AGENCY_BIZ_CONVERTED` | 기관 기업회원 전환 | `agency_user_id`, `biz_no` |
| `CUSTOM` | 기관 정의 이벤트 | (자유 형식) |

---

### 4.6 연동 상태 확인

```java
// SDK를 통한 연동 상태 조회
GatewayResponse status = gatewayClient.getStatus("MOIS");

if (status.isSuccess()) {
    System.out.println("연동 상태: " + status.getBody());
    // 응답 예시:
    // {
    //   "agencyCode": "MOIS",
    //   "agencyName": "행정안전부",
    //   "active": true,
    //   "activeEndpoints": 4,
    //   "pendingProvisioning": 0,
    //   "deadLetterProvisioning": 0,
    //   "unprocessedInbound": 0,
    //   "lastInboundAt": "2026-05-14T10:30:00Z",
    //   "lastOutboundAt": "2026-05-14T10:25:00Z"
    // }
}
```

---

## 5. API 레퍼런스

### 5.1 인바운드 이벤트 수신

기관에서 OnePass로 이벤트를 전송합니다.

```
POST https://onepass.go.kr/api/v1/agency/gateway/inbound/event
```

#### 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `Content-Type` | ✅ | `application/json;charset=UTF-8` |
| `X-Agency-Code` | ✅ | 기관 코드 (예: `MOIS`) |
| `X-Agency-Key` | ✅ | API Key 원문 (서버에서 SHA-256 비교) |
| `X-Idempotency-Key` | ✅ | UUID v7 (24시간 내 재전송 시 동일 키 사용) |
| `X-Event-Type` | ✅ | 이벤트 타입 (예: `AGENCY_USER_UPDATED`) |
| `X-Correlation-Id` | 선택 | 요청 추적 ID |
| `X-Internal-Sig` | 선택 | HMAC-SHA256 서명 (Sprint 17 이후 필수) |

#### 요청 바디

```json
{
  "agency_user_id": "MOIS-USER-00001",
  "change_type": "EMAIL_UPDATED",
  "changed_at": "2026-05-14T10:30:00Z"
}
```

> ⚠️ **PII 주의**: 페이로드에 실명, 전화번호, 주민등록번호 등 개인식별정보 평문을 포함하지 마세요.

#### 응답

| HTTP 코드 | 설명 | 바디 예시 |
|---------|------|---------|
| `202 Accepted` | 수신 성공 (비동기 처리) | `{"status":"RECEIVED","idempotency_key":"..."}` |
| `400 Bad Request` | X-Idempotency-Key 누락 | `{"error":"MISSING_IDEMPOTENCY_KEY"}` |
| `401 Unauthorized` | API Key 불일치 | `{"error":"INVALID_AGENCY_CREDENTIALS"}` |
| `409 Conflict` | 중복 이벤트 | `{"error":"PROV_IDEMPOTENCY_CONFLICT"}` |
| `422 Unprocessable` | 이벤트 처리 거부 | `{"error":"PROV_INBOUND_REJECTED"}` |

---

### 5.2 아웃바운드 알림 발송 트리거

OnePass에서 기관 WEBHOOK으로 이벤트를 발송합니다 (내부 서비스 전용).

```
PATCH https://onepass.go.kr/api/v1/agency/gateway/outbound/notify
```

> 🔒 **내부 전용**: 이 엔드포인트는 Kubernetes IngressRule로 외부 노출이 차단됩니다. OnePass 내부 서비스(운영 대시보드, 배치 작업)만 호출 가능합니다.

#### 요청 바디

```json
{
  "agency_code": "MOIS",
  "event_type": "NOTIFY_USER",
  "payload": "{\"onepass_user_id\":\"01914bf9-...\",\"message\":\"계정 연동 완료\"}",
  "idempotency_key": "01914bf9-a5f2-7000-b000-000000000001",
  "correlation_id": "01914bf9-a5f2-7000-b000-000000000002"
}
```

#### 응답

| HTTP 코드 | 설명 |
|---------|------|
| `200 OK` | 발송 완료 (`http_status`: 기관 응답 코드 포함) |
| `404 Not Found` | WEBHOOK 엔드포인트 미등록 |

---

### 5.3 기관 연동 상태 조회

```
GET https://onepass.go.kr/api/v1/agency/gateway/status/{agencyCode}
```

#### 응답 예시

```json
{
  "agencyCode": "MOIS",
  "agencyName": "행정안전부",
  "active": true,
  "activeEndpoints": 4,
  "pendingProvisioning": 2,
  "deadLetterProvisioning": 0,
  "unprocessedInbound": 0,
  "lastInboundAt": "2026-05-14T10:30:00.000Z",
  "lastOutboundAt": "2026-05-14T10:25:00.000Z",
  "queriedAt": "2026-05-14T10:35:00.000Z"
}
```

---

### 5.4 프로비저닝 이벤트 수신 (기관 서버)

OnePass가 기관 서버의 `PROVISIONING` 엔드포인트로 전송하는 요청 스펙입니다.

```
POST {기관_PROVISIONING_URL}
Content-Type: application/json
X-Idempotency-Key: {UUID_v7}
X-Correlation-Id: {추적_ID}
```

#### 요청 바디

```json
{
  "onepass_user_id": "01914bf9-a5f2-7000-b000-000000000001",
  "event_type": "USER_REGISTERED",
  "identity_hash": "e3b0c44298fc1c149afb...",
  "registered_at": "2026-05-14T10:30:00Z",
  "idempotency_key": "01914bf9-a5f2-7000-b000-000000000002",
  "correlation_id": "01914bf9-a5f2-7000-b000-000000000003",
  "schema_version": "1"
}
```

#### 기관 서버 응답 규칙

| 상황 | 응답 코드 | 설명 |
|------|---------|------|
| 정상 처리 | `200` or `201` or `202` | 성공 처리 → OnePass `COMPLETED` 기록 |
| 중복 수신 | `200` | 멱등 처리 완료 → `COMPLETED` 기록 |
| 일시적 오류 | `5xx` | OnePass가 지수 백오프 재시도 |
| 영구 오류 | `4xx` | OnePass가 `DEAD_LETTER` 기록 후 재시도 중단 |

---

## 6. 멱등성 처리 가이드

### 멱등성 키 생성 규칙

```
형식: UUID v7 (시간 정렬 가능, RFC 9562)
생성: IdempotencyKeyGenerator.generate()  // SDK 제공
TTL: 24시간 (Redis) + DB UNIQUE 제약 (영구)
```

### SDK 자동 생성 vs. 수동 지정

```java
// 방법 1: SDK 자동 생성 (idempotencyKey 미지정 시)
// → 재시도 시 다른 키가 생성되어 중복 방지 불가 → 지속적 재시도에 부적합
InboundEvent event = InboundEvent.builder()
    .eventType("AGENCY_USER_UPDATED")
    .agencyCode("MOIS")
    // idempotencyKey 미지정 → SDK가 UUID v4 자동 생성
    .build();

// 방법 2: 수동 지정 (권장 — 재시도 보장)
// → 동일 비즈니스 이벤트에 대해 항상 동일 키 사용
String idempotencyKey = IdempotencyKeyGenerator.generateWithPrefix("MOIS");
// DB 또는 캐시에 저장 → 재시도 시 동일 키 재사용

InboundEvent event = InboundEvent.builder()
    .eventType("AGENCY_USER_UPDATED")
    .agencyCode("MOIS")
    .idempotencyKey(idempotencyKey)  // 동일 키로 최대 3회 재시도
    .build();
```

### 멱등성 충돌 처리 흐름

```
기관 → POST /inbound/event (idempotencyKey=K1)
  ↓
OnePass Redis: K1 존재? 
  YES → 409 Conflict 반환 (정상, 중복 방지 성공)
  NO  → Redis K1 SET NX (24h TTL)
         ↓
         DB INSERT (UNIQUE 제약)
           충돌 → Redis 키 해제 + 409 반환
           성공 → 이벤트 처리 → 200 응답
```

---

## 7. 보안 요구사항

### 기관 서버 보안 체크리스트

| 항목 | 요구사항 | 검증 방법 |
|------|---------|---------|
| **HTTPS 필수** | TLS 1.2+ (TLS 1.3 권장) | `openssl s_client -connect {url}:443` |
| **API Key 관리** | 환경변수/Secret Manager 사용, 코드 하드코딩 금지 | 코드 리뷰 |
| **IP 화이트리스트** | OnePass 서버 IP만 허용 (Webhook 수신 포트) | 방화벽 정책 확인 |
| **PII 최소화** | 페이로드에 실명/전화 평문 금지 | 전송 페이로드 검토 |
| **응답 시간** | Webhook 수신 5초 이내 응답 | 부하 테스트 |
| **중복 처리 방지** | 멱등성 키 기반 중복 체크 구현 | 통합 테스트 |
| **로그 PII 제거** | 수신 페이로드 로그 시 민감정보 마스킹 | 로그 감사 |

### API Key 보안 원칙

```
✅  환경변수로 주입: ONEPASS_API_KEY=...
✅  K8s Secret으로 관리: kubectl create secret generic onepass-creds ...
✅  AWS Secrets Manager / HashiCorp Vault 연동
✅  분기별 Key 교체 (30~90일 주기)

❌  application.properties에 하드코딩
❌  Git 저장소에 커밋
❌  로그 파일에 출력
❌  API 응답에 포함
```

### HMAC-SHA256 서명 (Sprint 17 의무화 예정)

```java
// 기관 서버에서 서명 검증 구현 예시
public boolean verifyOnePassSignature(
        String method, String path, String timestampMs,
        String body, String receivedSig) {

    String sharedSecret = System.getenv("ONEPASS_HMAC_SECRET");
    HmacSigner signer = new HmacSigner(sharedSecret);

    // 서버가 생성한 서명 계산
    long ts = Long.parseLong(timestampMs);
    String expectedSig = signer.sign(method, path, ts, body);

    // 타임스탬프 유효성 검사 (5분 이내)
    long now = System.currentTimeMillis();
    if (Math.abs(now - ts) > 5 * 60 * 1000) {
        log.warn("서명 타임스탬프 만료: ts={}", ts);
        return false;
    }

    return signer.verifySignature(receivedSig, expectedSig);
}
```

---

## 8. 장애/실패 시나리오 및 해결 방법

### 8.1 프로비저닝 실패

#### 증상
- 기관 서버가 5xx 응답을 반환하거나 타임아웃이 발생합니다.
- `provisioning_outbox` 테이블의 `status = 'PENDING'` 건수가 증가합니다.

#### 원인 진단

```sql
-- PENDING 상태 아웃박스 조회
SELECT
    agency_code,
    event_type,
    retry_count,
    next_retry_at,
    error_message,
    created_at
FROM ido.provisioning_outbox
WHERE status = 'PENDING'
  AND next_retry_at <= NOW()
ORDER BY next_retry_at ASC
LIMIT 20;

-- 기관별 실패 통계
SELECT
    agency_code,
    COUNT(*) as pending_count,
    MAX(retry_count) as max_retry,
    MIN(next_retry_at) as earliest_retry,
    MAX(error_message) as last_error
FROM ido.provisioning_outbox
WHERE status IN ('PENDING', 'DEAD_LETTER')
GROUP BY agency_code
ORDER BY pending_count DESC;
```

#### 해결 방법

**방법 1: 자동 복구 (권장)**
```
ProvisioningOutboxRelay가 다음 스케줄(30초 간격)에 자동 재시도합니다.
- 1차 실패 → 1분 후 재시도
- 2차 실패 → 5분 후 재시도
- 3차 실패 → 30분 후 재시도
- 3회 초과 → DEAD_LETTER (수동 처리 필요)
```

**방법 2: 특정 기관 수동 재스케줄**
```sql
-- 특정 기관의 PENDING 건 즉시 재시도 스케줄로 변경
UPDATE ido.provisioning_outbox
SET next_retry_at = NOW(),
    retry_count = 0,
    error_message = NULL
WHERE agency_code = 'MOIS'
  AND status = 'PENDING';
```

**방법 3: 기관 엔드포인트 일시 비활성화 (점검 중)**
```sql
-- 점검 중인 기관 비활성화 (새 프로비저닝 발생 시 SKIP)
UPDATE ido.agency_endpoint_registry
SET is_active = FALSE, updated_at = NOW()
WHERE agency_code = 'MOIS'
  AND endpoint_type = 'PROVISIONING';

-- 점검 완료 후 재활성화
UPDATE ido.agency_endpoint_registry
SET is_active = TRUE, updated_at = NOW()
WHERE agency_code = 'MOIS'
  AND endpoint_type = 'PROVISIONING';
```

**방법 4: Feature Flag로 프로비저닝 일시 중단**
```bash
# ConfigMap 즉시 업데이트 (재배포 불필요)
kubectl patch configmap ido-config -n smes \
  --patch '{"data":{"IDO_PROVISIONING_ENABLED":"false"}}'

# Pod 재시작으로 반영
kubectl rollout restart deployment/ido -n smes

# 복구 후 재활성화
kubectl patch configmap ido-config -n smes \
  --patch '{"data":{"IDO_PROVISIONING_ENABLED":"true"}}'
kubectl rollout restart deployment/ido -n smes
```

---

### 8.2 인바운드 이벤트 처리 실패

#### 증상
- `gateway_inbound_audit` 테이블의 `status = 'REJECTED'` 건수가 증가합니다.
- 기관이 `422 Unprocessable` 응답을 수신합니다.

#### 진단

```sql
-- REJECTED 이벤트 조회
SELECT
    agency_code,
    event_type,
    idempotency_key,
    error_message,
    received_at
FROM ido.gateway_inbound_audit
WHERE status = 'REJECTED'
ORDER BY received_at DESC
LIMIT 20;
```

#### 해결 방법

```sql
-- 처리 실패한 이벤트 수동 재처리를 위해 RECEIVED 상태로 재설정
UPDATE ido.gateway_inbound_audit
SET status = 'RECEIVED',
    error_message = NULL,
    processed_at = NULL
WHERE id = '{특정_이벤트_ID}'
  AND status = 'REJECTED';
-- 이후 별도 재처리 배치 또는 수동 API 호출로 처리
```

---

### 8.3 API Key 인증 실패

#### 증상
- 기관이 `401 Unauthorized` 응답을 수신합니다.
- 에러 바디: `{"error":"INVALID_AGENCY_CREDENTIALS"}`

#### 원인 및 해결

| 원인 | 확인 방법 | 해결 방법 |
|------|---------|---------|
| API Key 오기입 | 기관 설정 확인 | 기관이 SDK/설정 파일의 Key 값 재확인 |
| Key 교체 후 미반영 | 기관 서버 배포 이력 | 새 Key로 SDK 재설정 후 배포 |
| 기관 코드 불일치 | X-Agency-Code 헤더 확인 | 올바른 기관 코드로 수정 |
| agency_meta 미등록 | DB 조회 | OnePass 운영팀에 기관 등록 요청 |
| active=false | DB 조회 | DB에서 active=true로 변경 |

```sql
-- 기관 메타 확인
SELECT agency_code, agency_name, active,
       LEFT(api_key_hash, 8) || '...' as key_hash_preview
FROM ido.agency_meta
WHERE agency_code = 'MOIS';

-- 기관 활성화
UPDATE ido.agency_meta
SET active = TRUE, updated_at = NOW()
WHERE agency_code = 'MOIS';
```

#### 기관 디버깅 (SDK 사용 시)

```java
try {
    GatewayResponse response = client.sendInbound(event);
} catch (AgencyHttpException e) {
    if (e.getHttpStatus() == 401) {
        System.err.println("인증 실패! 확인사항:");
        System.err.println("1. API Key 원문이 정확한가? (공백/개행 포함 여부 확인)");
        System.err.println("2. X-Agency-Code가 등록된 코드와 일치하는가?");
        System.err.println("3. 기관 계정이 활성 상태인가? (OnePass 운영팀 문의)");
    }
}
```

---

### 8.4 멱등성 충돌 (409 Conflict)

#### 증상
- `409 Conflict` 응답 수신
- 에러 바디: `{"error":"PROV_IDEMPOTENCY_CONFLICT"}`

#### 해석

409는 **오류가 아닙니다**. 이미 처리된 요청에 동일한 멱등성 키로 재전송한 것입니다.  
정상 처리된 것으로 간주하고 요청을 성공으로 처리하면 됩니다.

#### 올바른 처리 코드

```java
try {
    GatewayResponse response = client.sendInbound(event);
    // 처리 성공
} catch (AgencyHttpException e) {
    if (e.getHttpStatus() == 409) {
        // ✅ 정상 — 이미 처리된 이벤트
        // 멱등성 키를 '처리 완료'로 마킹하고 다음으로 진행
        log.info("이미 처리된 이벤트 (멱등성 보장): idempotencyKey={}", idempotencyKey);
        return; // 성공으로 처리
    }
    // 다른 오류는 재시도 로직
    throw e;
}
```

#### 새 이벤트인데 409가 발생하는 경우

```sql
-- Redis 키 확인 (24시간 TTL)
-- Redis CLI: KEYS gateway:inbound:idempotent:{idempotencyKey}

-- DB에서 동일 키 조회
SELECT id, status, received_at, error_message
FROM ido.gateway_inbound_audit
WHERE idempotency_key = '{문제의_키}';
```

만약 DB에서도 해당 키를 찾을 수 없는데 409가 반환된다면:
1. Redis가 만료 전 키를 보유 중 → 24시간 후 자동 해소
2. 다른 인스턴스에서 처리 중 → 잠시 후 재시도

---

### 8.5 기관 엔드포인트 응답 없음 (타임아웃)

#### 증상
- 프로비저닝 로그에 `Connect timed out` 또는 `Read timed out` 발생
- PENDING 건수가 증가하고 retry_count가 올라감

#### 진단

```bash
# OnePass 서버에서 기관 엔드포인트 헬스 확인
kubectl exec -n smes deploy/ido -- \
  curl -v --max-time 5 https://api.mois.go.kr/health

# 응답 없음 시:
# - 기관 서버 점검 여부 확인
# - 방화벽 규칙 확인
# - DNS 해석 확인
```

#### 해결 방법

```sql
-- 1. 해당 기관 타임아웃 값 증가 (일시적 허용)
UPDATE ido.agency_endpoint_registry
SET timeout_ms = 15000, updated_at = NOW()  -- 15초로 증가
WHERE agency_code = 'MOIS'
  AND endpoint_type = 'PROVISIONING';

-- 2. 기관 장애 장기화 시 비활성화 후 복구 시 재활성화
UPDATE ido.agency_endpoint_registry
SET is_active = FALSE, updated_at = NOW(),
    note = '장애 비활성화 2026-05-14 기준'
WHERE agency_code = 'MOIS';
```

---

### 8.6 Redis 장애

#### 증상
- `GatewayIdempotencyStore.tryAcquireInbound()` 호출 시 예외 발생
- 로그: `[GatewayIdempotency] Redis 연결 오류`

#### 동작 원칙

Redis 장애 시 **DB UNIQUE 제약이 2차 방어선**으로 동작합니다.  
멱등성이 완전히 무너지지 않으며, DB INSERT 시 충돌이 발생하면 `DuplicateKeyException`으로 처리됩니다.

#### 모니터링

```bash
# Redis 연결 상태 확인
kubectl exec -n smes deploy/ido -- \
  curl -s http://localhost:8083/actuator/health/redis | jq .

# 기대: {"status": "UP"}
# 장애: {"status": "DOWN", "details": {...}}
```

#### Redis 복구 후 처리

Redis 복구 후 별도 작업 불필요합니다.  
24시간 TTL 키는 자동 재생성되며, DB UNIQUE 제약이 중복을 방지합니다.

---

### 8.7 Flyway 마이그레이션 실패

#### 증상
- 애플리케이션 기동 실패
- 로그: `FlywayException: Validate failed: ...`

#### 원인 및 해결

| 원인 | 증상 | 해결 방법 |
|------|------|---------|
| 체크섬 불일치 | `checksum mismatch` | SQL 파일 변경 금지, 원복 |
| 스키마 잠금 | `Unable to acquire Flyway advisory lock` | `flyway_schema_history` 잠금 해제 |
| 이미 존재하는 테이블 | `Table already exists` | `IF NOT EXISTS` 확인 (이미 적용됨) |
| DB 권한 부족 | `permission denied` | DB 사용자 권한 확인 |

```sql
-- Flyway 잠금 해제 (비상 시)
DELETE FROM ido.flyway_schema_history
WHERE description = 'flyway_lock';

-- 또는
SELECT pg_advisory_unlock_all();

-- 체크섬 불일치 수동 해결 (절대 운영 SQL 수정 금지!)
-- 이미 적용된 버전 수동 복구 (체크섬만 갱신)
UPDATE ido.flyway_schema_history
SET checksum = {올바른_체크섬}
WHERE version = '15';
```

> ⚠️ **주의**: 운영 SQL 마이그레이션 파일(`V*.sql`)은 **절대 수정하지 마세요**.  
> 수정이 필요하면 새 버전(`V17__...sql`)으로 작성합니다.

---

### 8.8 DEAD_LETTER 누적 처리

#### 증상
- `status = 'DEAD_LETTER'` 건수가 지속적으로 증가합니다.
- 알림: Grafana 알림 `provisioning_dead_letter > 0`

#### 진단

```sql
-- DEAD_LETTER 상세 조회
SELECT
    po.agency_code,
    am.agency_name,
    po.qim_user_id,
    po.event_type,
    po.retry_count,
    po.error_message,
    po.created_at,
    po.last_attempted_at
FROM ido.provisioning_outbox po
JOIN ido.agency_meta am ON am.agency_code = po.agency_code
WHERE po.status = 'DEAD_LETTER'
ORDER BY po.created_at DESC
LIMIT 50;
```

#### 해결 절차

**Step 1: 근본 원인 파악**

```bash
# 기관 엔드포인트 실제 연결 테스트
kubectl exec -n smes deploy/ido -- \
  curl -v -X POST https://api.mois.go.kr/onepass/provisioning/users \
  -H "Content-Type: application/json" \
  -d '{"test": true}'
```

**Step 2: 기관 문제 해결 확인 후 수동 재처리**

```sql
-- 특정 기관 DEAD_LETTER 재시도 허용 (retry_count 초기화)
UPDATE ido.provisioning_outbox
SET status = 'PENDING',
    retry_count = 0,
    error_message = NULL,
    next_retry_at = NOW()
WHERE agency_code = 'MOIS'
  AND status = 'DEAD_LETTER'
  AND created_at >= NOW() - INTERVAL '7 days';

-- 재처리 확인
SELECT COUNT(*) as pending_count
FROM ido.provisioning_outbox
WHERE agency_code = 'MOIS' AND status = 'PENDING';
```

**Step 3: 재처리 불가능한 건 아카이빙**

```sql
-- 오래된 DEAD_LETTER 별도 테이블에 아카이빙 후 삭제
INSERT INTO ido.provisioning_outbox_archive
SELECT *, NOW() as archived_at
FROM ido.provisioning_outbox
WHERE status = 'DEAD_LETTER'
  AND last_attempted_at < NOW() - INTERVAL '30 days';

DELETE FROM ido.provisioning_outbox
WHERE status = 'DEAD_LETTER'
  AND last_attempted_at < NOW() - INTERVAL '30 days';
```

---

## 9. 운영 모니터링

### 핵심 지표 (Prometheus / Grafana)

| 지표 | 경보 임계값 | 의미 |
|------|------------|------|
| `provisioning_outbox_pending_total` | > 100 (5분 지속) | 프로비저닝 백로그 누적 |
| `provisioning_outbox_dead_letter_total` | > 0 | 기관 연동 영구 실패 |
| `gateway_inbound_rejected_total` | > 10/분 | 인바운드 이벤트 처리 오류 |
| `gateway_outbound_failed_total` | > 5/분 | 아웃바운드 발송 실패 |
| `agency_endpoint_response_time_p99` | > 3000ms | 기관 응답 지연 |

### 운영 쿼리 대시보드

```sql
-- 실시간 프로비저닝 현황
SELECT
    status,
    COUNT(*) as count,
    MIN(created_at) as oldest
FROM ido.provisioning_outbox
WHERE created_at >= NOW() - INTERVAL '24 hours'
GROUP BY status;

-- 기관별 연동 건강도 (최근 1시간)
SELECT
    po.agency_code,
    COUNT(*) FILTER (WHERE po.status = 'COMPLETED') as completed,
    COUNT(*) FILTER (WHERE po.status = 'PENDING') as pending,
    COUNT(*) FILTER (WHERE po.status = 'DEAD_LETTER') as dead_letter,
    ROUND(
        COUNT(*) FILTER (WHERE po.status = 'COMPLETED') * 100.0 /
        NULLIF(COUNT(*), 0), 2
    ) as success_rate_pct
FROM ido.provisioning_outbox po
WHERE po.created_at >= NOW() - INTERVAL '1 hour'
GROUP BY po.agency_code
ORDER BY success_rate_pct ASC;

-- 인바운드/아웃바운드 처리량 (최근 1시간)
SELECT
    'inbound' as direction,
    COUNT(*) as total,
    COUNT(*) FILTER (WHERE status = 'PROCESSED') as success,
    COUNT(*) FILTER (WHERE status = 'REJECTED') as rejected
FROM ido.gateway_inbound_audit
WHERE received_at >= NOW() - INTERVAL '1 hour'

UNION ALL

SELECT
    'outbound' as direction,
    COUNT(*) as total,
    COUNT(*) FILTER (WHERE status = 'DELIVERED') as success,
    COUNT(*) FILTER (WHERE status = 'FAILED') as rejected
FROM ido.gateway_outbound_audit
WHERE sent_at >= NOW() - INTERVAL '1 hour';
```

### 알림 설정 (Alertmanager)

```yaml
# infra/monitoring/alertmanager/rules/onepass-provisioning.yml
groups:
  - name: onepass-provisioning
    rules:
      - alert: ProvisioningDeadLetterDetected
        expr: sum(provisioning_outbox_dead_letter_total) > 0
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "프로비저닝 DEAD_LETTER 발생"
          description: "{{ $value }}건의 프로비저닝이 최대 재시도를 초과했습니다."

      - alert: ProvisioningBacklogHigh
        expr: sum(provisioning_outbox_pending_total) > 100
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "프로비저닝 백로그 과다"
          description: "PENDING 프로비저닝이 {{ $value }}건 누적되었습니다."
```

---

## 10. 롤백 절차

### 롤백 트리거 조건

- 배포 후 5분 이내 에러율 > 1%
- `provisioning_outbox_pending_total` 급격한 증가
- Health Check 연속 3회 실패

### 롤백 실행

```bash
# 1. 이전 버전으로 즉시 롤백
kubectl rollout undo deployment/ido -n smes

# 2. 롤백 상태 확인
kubectl rollout status deployment/ido -n smes

# 3. 롤백 후 로그 확인
kubectl logs -n smes -l app=ido --since=5m | grep -E "ERROR|WARN"
```

### DB 롤백 (Flyway 다운그레이션)

> ⚠️ **중요**: Flyway 무료 버전은 다운그레이션을 지원하지 않습니다.  
> V15/V16에서 생성한 테이블은 `DROP` 후 이전 버전 애플리케이션을 기동합니다.

```sql
-- 비상 시 수동 롤백 (데이터 손실 주의!)
-- 반드시 백업 후 실행

-- V16 롤백
DROP TABLE IF EXISTS ido.gateway_outbound_audit;
DROP TABLE IF EXISTS ido.gateway_inbound_audit;
DELETE FROM ido.flyway_schema_history WHERE version IN ('16');

-- V15 롤백 (V16 롤백 후)
DROP TABLE IF EXISTS ido.provisioning_outbox;
DROP TABLE IF EXISTS ido.agency_endpoint_registry;
DELETE FROM ido.flyway_schema_history WHERE version IN ('15');
```

---

## 11. 환경변수 전체 목록

### OnePass 서버 환경변수

| 환경변수 | 기본값 | 필수 | 설명 |
|---------|-------|------|------|
| `DB_HOST` | `localhost` | ✅ | PostgreSQL 호스트 |
| `DB_PORT` | `5432` | - | PostgreSQL 포트 |
| `DB_NAME` | `onepass` | ✅ | 데이터베이스 명 |
| `DB_USERNAME` | `onepass` | ✅ | DB 사용자 |
| `DB_PASSWORD` | - | ✅ | DB 비밀번호 |
| `REDIS_HOST` | `localhost` | ✅ | Redis 호스트 |
| `REDIS_PORT` | `6379` | - | Redis 포트 |
| `REDIS_PASSWORD` | - | - | Redis 비밀번호 |
| `KAFKA_SERVERS` | `localhost:9092` | ✅ | Kafka Bootstrap |
| `IDO_PROVISIONING_ENABLED` | `true` | - | 프로비저닝 On/Off |
| `IDO_PROVISIONING_RELAY_ENABLED` | `true` | - | Outbox Relay On/Off |

### 기관 클라이언트 환경변수

| 환경변수 | 예시 | 설명 |
|---------|------|------|
| `ONEPASS_BASE_URL` | `https://onepass.go.kr` | OnePass 서버 URL |
| `ONEPASS_API_KEY` | `a1b2c3d4...` | 발급받은 API Key 원문 |
| `ONEPASS_AGENCY_CODE` | `MOIS` | 기관 코드 |
| `ONEPASS_HMAC_SECRET` | `secret...` | HMAC 공유 비밀키 (선택) |
| `ONEPASS_CONNECT_TIMEOUT_MS` | `5000` | 연결 타임아웃 (ms) |
| `ONEPASS_READ_TIMEOUT_MS` | `30000` | 읽기 타임아웃 (ms) |

---

## 부록: 빠른 참조 (Quick Reference)

### 기관 연동 체크리스트

```
□ OnePass 운영팀에서 기관 코드 및 API Key 수령
□ agency_meta + agency_endpoint_registry 등록 요청
□ 기관 서버 방화벽에 OnePass IP 허용 (인바운드 443)
□ Webhook 수신 엔드포인트 구현 및 HTTPS 인증서 설치
□ onepass-agency-sdk 의존성 추가
□ API Key 환경변수로 주입 (코드 하드코딩 금지)
□ AgencyGatewayClient Bean 등록
□ 멱등성 키 저장소 구현 (DB 또는 Redis)
□ Webhook 수신 핸들러 구현 (200 응답, 5초 이내)
□ 개발 환경 통합 테스트 완료
□ 운영 배포 후 /status/{agencyCode} 조회로 연동 확인
```

### 장애 대응 에스컬레이션

| 단계 | 담당 | SLA |
|------|------|-----|
| 1차 | 기관 자체 기술팀 | 30분 |
| 2차 | OnePass 운영팀 (`onepass-ops@smes.go.kr`) | 2시간 |
| 3차 | OnePass 개발팀 긴급 대응 | 4시간 |
| 4차 | 긴급 장애 대응 (24/7) | 즉시 |

### 주요 명령어 요약

```bash
# 프로비저닝 상태 확인
kubectl exec -n smes deploy/ido -- \
  psql -c "SELECT status, COUNT(*) FROM ido.provisioning_outbox GROUP BY status"

# 릴레이 강제 트리거 (Feature Flag 토글)
kubectl patch configmap ido-config -n smes \
  --patch '{"data":{"IDO_PROVISIONING_RELAY_ENABLED":"false"}}'
kubectl rollout restart deployment/ido -n smes

# 기관 연동 상태 API 조회
curl -s https://onepass.go.kr/api/v1/agency/gateway/status/MOIS \
  -H "X-Agency-Code: MOIS" -H "X-Agency-Key: {apikey}" | jq .

# 배포 롤백
kubectl rollout undo deployment/ido -n smes
```
