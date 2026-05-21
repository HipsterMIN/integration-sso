# IdO 기능 플래그(Feature Flags) 완전 가이드

> **버전**: v2.2.0 (Sprint 9 기준)  
> **대상**: IdO 개발팀 전원  
> **목적**: 모든 선택적 기능의 On/Off 방법, 효과, 환경별 권장 설정을 단일 문서로 제공

---

## 목차

1. [개요 — 왜 On/Off가 필요한가](#1-개요)
2. [전체 기능 플래그 빠른 참조표](#2-전체-기능-플래그-빠른-참조표)
3. [환경별 권장 설정 매트릭스](#3-환경별-권장-설정-매트릭스)
4. [기능별 상세 설명](#4-기능별-상세-설명)
   - [F-01 IP 기반 Auth Rate Limiting](#f-01-ip-기반-auth-rate-limiting)
   - [F-02 기관별 Rate Limiting](#f-02-기관별-rate-limiting)
   - [F-03 감사 로그 Kafka 발행](#f-03-감사-로그-kafka-발행)
   - [F-04 감사 로그 DB 저장](#f-04-감사-로그-db-저장)
   - [F-05 OTel 분산 추적 AOP](#f-05-otel-분산-추적-aop)
   - [F-06 Resilience4j CB+Retry (NICE)](#f-06-resilience4j-cbretry-nice)
   - [F-07 Resilience4j CB+Retry (통합인증)](#f-07-resilience4j-cbretry-통합인증)
   - [F-08 Redisson 분산 락](#f-08-redisson-분산-락)
   - [F-09 NHN Cloud SKM KMS](#f-09-nhn-cloud-skm-kms)
   - [F-10 보안 응답 헤더 필터](#f-10-보안-응답-헤더-필터)
   - [F-11 개인정보 파기 스케줄러](#f-11-개인정보-파기-스케줄러)
   - [F-12 Handoff 키 로테이션 스케줄러](#f-12-handoff-키-로테이션-스케줄러)
   - [F-13 IdO Outbox Relay](#f-13-ido-outbox-relay)
   - [F-14 Webhook Outbox Relay](#f-14-webhook-outbox-relay)
   - [F-15 CORS 제어](#f-15-cors-제어)
   - [F-16 Bean Validation](#f-16-bean-validation-끄면-안-됨)
   - [F-17 Traceparent 상관관계 ID 필터](#f-17-traceparent-상관관계-id-필터)
   - [F-18 SP 수신 감사 로그](#f-18-sp-수신-감사-로그)
5. [application-local.yml 완성본](#5-application-localyml-완성본)
6. [K8s ConfigMap 환경변수 목록](#6-k8s-configmap-환경변수-목록)
7. [런타임 현재 상태 확인 방법](#7-런타임-현재-상태-확인-방법)
8. [자주 묻는 질문 (FAQ)](#8-자주-묻는-질문)

---

## 1. 개요

### 왜 On/Off가 필요한가?

IdO 서비스는 **환경에 따라 모든 외부 의존성이 갖춰지지 않을 수 있다**.  
기능 플래그 없이 모든 기능이 항상 ON이면 다음 문제가 발생한다:

| 상황 | 문제 |
|------|------|
| 로컬 개발 (Redis 없음) | Redisson 락 빈 초기화 실패 → **앱 기동 자체 불가** |
| 로컬 개발 (Kafka 없음) | 감사 로그 발행 매 요청마다 오류 로그 → 실제 오류 묻힘 |
| 로컬 개발 (OTel 없음) | Jaeger exporter 연결 시도 → 불필요한 오류/지연 |
| 개발 DB에서 파기 스케줄러 실행 | 테스트 데이터 **실수로 영구 삭제** |
| 개발 환경에서 IP Rate Limit ON | 반복 테스트 중 **본인 IP 차단** |
| FE 개발자 API 테스트 | CSP 헤더로 **응답 파싱 차단** |

### 설계 원칙

```
1. 모든 선택적 기능은 환경변수 하나로 On/Off 가능
2. 로컬/개발 기본값은 "안전한 OFF" — 운영 기본값은 "보호된 ON"
3. Off 시에도 의미있는 로그를 남겨 "왜 동작 안 하는지" 즉시 파악 가능
4. 절대 끄면 안 되는 기능(Bean Validation 등)은 플래그를 두지 않음
5. 한 환경변수가 두 기능을 동시에 제어하는 것 금지 (독립성 원칙)
```

---

## 2. 전체 기능 플래그 빠른 참조표

| ID | 기능명 | 환경변수 | 기본값(앱) | 위험도 | 관련 클래스 |
|----|--------|---------|-----------|--------|------------|
| F-01 | IP Auth Rate Limiting | `IDO_AUTH_RL_ENABLED` | `true` | 🔴 HIGH | `AuthRateLimitInterceptor` |
| F-02 | 기관별 Rate Limiting | `IDO_RATE_LIMIT_ENABLED` | `true` | 🟡 MED | `AgencyRateLimiter` |
| F-03 | 감사로그 Kafka 발행 | `IDO_AUDIT_KAFKA_ENABLED` | `true` | 🟡 MED | `AuditLogPublisher` |
| F-04 | 감사로그 DB 저장 | `IDO_AUDIT_DB_ENABLED` | `true` | 🟡 MED | `AuditLogPublisher` |
| F-05 | OTel AOP 분산 추적 | `IDO_AUTH_TRACING_ENABLED` | `true` | 🟡 MED | `AuthTracingAspect` |
| F-06 | Resilience4j CB (NICE) | yml 설정 | `true` | 🟡 MED | `NiceApiClient` |
| F-07 | Resilience4j CB (통합인증) | yml 설정 | `true` | 🟡 MED | `IntegrationAuthClient` |
| F-08 | Redisson 분산 락 | `IDO_REDISSON_ENABLED` | `true` | 🔴 HIGH | `RedissonConfig`, `NiceAuthService` |
| F-09 | NHN Cloud SKM KMS | `@Profile("prod")` 자동 | prod 자동 | 🟢 LOW | `NhnKmsClient` / `NoOpKmsClient` |
| F-10 | 보안 응답 헤더 | `IDO_SECURITY_HEADERS_ENABLED` | `true` | 🟡 MED | `SecurityHeadersFilter` |
| F-11 | 개인정보 파기 스케줄러 | `IDO_RETENTION_ENABLED` | `false` | 🔴 HIGH | `PersonalDataRetentionScheduler` |
| F-11b | 파기 dry-run 모드 | `IDO_RETENTION_DRY_RUN` | `true` | 🔴 HIGH | `PersonalDataRetentionScheduler` |
| F-12 | Handoff 키 로테이션 | `IDO_CRYPTO_ROTATION_ENABLED` | `true` | 🟡 MED | `HandoffKeyRotationScheduler` |
| F-13 | IdO Outbox Relay | `IDO_OUTBOX_RELAY_ENABLED` | `true` | 🟡 MED | `IdoOutboxRelay` |
| F-14 | Webhook Outbox Relay | `IDO_WEBHOOK_RELAY_ENABLED` | `true` | 🟡 MED | `WebhookDispatchOutboxRelay` |
| F-15 | CORS | `IDO_CORS_ENABLED` | `true` | 🟢 LOW | `IdoWebMvcConfig` |
| F-16 | Bean Validation | **끄면 안 됨** | 항상 ON | — | `AuthController` |
| F-17 | Traceparent 필터 | **끄면 안 됨** | 항상 ON | — | `TraceparentFilter` |
| F-18 | SP 수신 감사로그 | `IDO_QIM_RECEIVER_AUDIT` | `true` | 🟢 LOW | `QimSpReceiverService` |

---

## 3. 환경별 권장 설정 매트릭스

> ✅ ON (활성) | ❌ OFF (비활성) | 🔵 NOOP (코드 실행하되 실제 동작 없음)

| 기능 | 로컬 개발 | 개발 서버 | 스테이징 | 운영 |
|------|-----------|-----------|---------|------|
| **F-01** IP Auth RL | ❌ | ❌ | ✅ | ✅ |
| **F-02** 기관 RL | ❌ | ✅ | ✅ | ✅ |
| **F-03** 감사 Kafka | ❌ | ✅ | ✅ | ✅ |
| **F-04** 감사 DB | ❌ | ✅ | ✅ | ✅ |
| **F-05** OTel 추적 | ❌ | ✅ | ✅ | ✅ |
| **F-06** CB (NICE) | ❌ | ✅ | ✅ | ✅ |
| **F-07** CB (통합인증) | ❌ | ✅ | ✅ | ✅ |
| **F-08** Redisson 락 | ❌ | ✅ | ✅ | ✅ |
| **F-09** KMS | 🔵 NoOp | 🔵 NoOp | 🔵 NoOp | ✅ NHN SKM |
| **F-10** 보안 헤더 | ❌ | ✅ | ✅ | ✅ |
| **F-11** 파기 스케줄러 | ❌ | ❌ | 🔵 dry-run | ✅ |
| **F-11b** 파기 dry-run | ✅ | ✅ | ✅ | ❌ |
| **F-12** 키 로테이션 | ❌ | ✅ | ✅ | ✅ |
| **F-13** Outbox Relay | ❌ | ✅ | ✅ | ✅ |
| **F-14** Webhook Relay | ❌ | ✅ | ✅ | ✅ |
| **F-15** CORS | ✅ | ✅ | ✅ | ✅ |
| **F-16** Validation | ✅ | ✅ | ✅ | ✅ |
| **F-17** Traceparent | ✅ | ✅ | ✅ | ✅ |
| **F-18** SP 감사 | ❌ | ✅ | ✅ | ✅ |

---

## 4. 기능별 상세 설명

---

### F-01 IP 기반 Auth Rate Limiting

**관련 클래스**: `kr.go.smes.ido.ratelimit.AuthRateLimitInterceptor`  
**등록 경로**: `IdoWebMvcConfig` → `/api/v1/auth/**` 인터셉터

#### 기능 설명

`/api/v1/auth/**` 경로(NICE 본인인증 URL 발급, 결과 조회, OACX 접근키, 콜백 등)에 대해  
**클라이언트 IP 단위**로 3중 제한을 적용한다.

```
[요청] → ① TPS 검사 (초당) → ② 분당 검사 → ③ 일별 검사 → [통과/차단]
```

| 윈도우 | 기본 한도 | 환경변수 |
|--------|---------|---------|
| 초당 (TPS) | 20 req/s | `IDO_AUTH_RL_TPS` |
| 분당 | 100 req/min | `IDO_AUTH_RL_PER_MIN` |
| 일별 | 1,000 req/day | `IDO_AUTH_RL_DAILY` |

**IP 추출 우선순위**: `X-Forwarded-For` (첫 번째) → `X-Real-IP` → `RemoteAddr`

#### ON 효과

- Redis Lua 스크립트로 **원자적** INCR+EXPIRE 처리 (Race Condition 없음)
- 한도 초과 시 `HTTP 429` + `Retry-After` + `X-RateLimit-*` 헤더 반환
- 정상 요청에도 `X-RateLimit-Limit/Remaining` 헤더 첨부
- Redis 장애 시 **fail-open** (허용) → 서비스 가용성 우선
- NICE 인증 API 무차별 대입 공격, DoS 방어

#### OFF 효과

- 인터셉터 `preHandle()` 즉시 `true` 반환 → 제한 없이 통과
- Redis 호출 전혀 없음 → Redis 없는 환경에서도 안전
- Rate Limit 관련 응답 헤더 미전송
- 로그에 `[AuthRateLimit] DISABLED — 모든 요청 허용` 출력

#### 설정 방법

```yaml
# application.yml (기본)
ido:
  auth:
    rate-limit:
      enabled: ${IDO_AUTH_RL_ENABLED:true}
      tps: ${IDO_AUTH_RL_TPS:20}
      per-minute: ${IDO_AUTH_RL_PER_MIN:100}
      daily: ${IDO_AUTH_RL_DAILY:1000}
```

```bash
# 환경변수로 OFF
IDO_AUTH_RL_ENABLED=false

# 한도만 완화 (OFF 대신 권장)
IDO_AUTH_RL_TPS=100
IDO_AUTH_RL_PER_MIN=1000
IDO_AUTH_RL_DAILY=100000
```

#### ⚠️ 주의사항

> **F-01(IP Auth RL)과 F-02(기관 RL)는 환경변수가 완전히 독립적이다.**  
> 이전 버전에서 `IDO_RATE_LIMIT_ENABLED` 하나를 공유했으나 v2.2.0에서 분리됨.  
> IP RL: `IDO_AUTH_RL_ENABLED` / 기관 RL: `IDO_RATE_LIMIT_ENABLED`

> **왜 개발 환경에서 OFF를 권장하는가?**  
> 로컬에서 `/api/v1/auth/**` 반복 테스트 시 자신의 `127.0.0.1`이 차단될 수 있음.  
> 특히 단위 테스트, Postman 반복 호출 시 TPS 20을 쉽게 초과.

---

### F-02 기관별 Rate Limiting

**관련 클래스**: `kr.go.smes.ido.ratelimit.AgencyRateLimiter`  
**사용처**: `HandoffService`, `HandoffController` 등 기관 인증 흐름

#### 기능 설명

SMES에 연동된 **기관(agencyCode) 단위**로 TPS/일별 요청 수를 제한한다.  
기관별로 `agency_meta.daily_lookup_limit`을 통해 개별 한도 설정 가능.

```
Redis 키: ido:rl:tps:{agencyCode}:{epochSecond}   → TTL 2s
          ido:rl:daily:{agencyCode}:{yyyyMMdd}     → TTL 25h
```

#### ON 효과

- 기관이 계약 한도를 초과하면 `429 Too Many Requests` 반환
- 특정 기관 오류/남용이 다른 기관에 영향 없도록 격리
- 기관별 사용량 Redis에 실시간 집계

#### OFF 효과

- 모든 기관 제한 해제 → 제한 없이 Handoff 처리
- Redis 호출 없음
- 개발/테스트 환경에서 불필요한 `429` 방지

#### 설정 방법

```yaml
ido:
  rate-limit:
    enabled: ${IDO_RATE_LIMIT_ENABLED:true}
    default-tps: ${IDO_RATE_LIMIT_DEFAULT_TPS:200}
    default-daily-limit: ${IDO_RATE_LIMIT_DEFAULT_DAILY:1000000}
```

```bash
IDO_RATE_LIMIT_ENABLED=false
```

---

### F-03 감사 로그 Kafka 발행

**관련 클래스**: `kr.go.smes.ido.audit.AuditLogPublisher`  
**토픽**: `platform.audit.log`

#### 기능 설명

F-04(DB 저장)와 독립적으로, 감사 로그를 Kafka에 **비동기 발행**한다.  
Kafka를 통해 중앙 감사 시스템(SIEM, 로그 집계 서비스)으로 전달.

```
[publish() 호출]
  → @Async("auditExecutor") 비동기 처리
    → ① DB 저장 (F-04)
    → ② kafkaPublishEnabled=true → Kafka 발행
         발행 성공: audit_log.kafka_published = TRUE
         발행 실패: kafka_published = FALSE (재처리 스케줄러 대상)
```

#### ON 효과

- 매 감사 이벤트를 `platform.audit.log` 토픽으로 비동기 전송
- 파티션 키: `agencyCode` (없으면 `"ido"`)
- 발행 실패 시 10분 주기 재처리 스케줄러가 재발행 시도
- Kafka 장애는 **서비스 흐름 차단하지 않음** (비치명적)

#### OFF 효과

- Kafka 발행 코드 전체 건너뜀 → Kafka 없는 환경에서 연결 오류 없음
- DB 저장(F-04)은 별도 플래그로 독립 제어
- 재처리 스케줄러(`retryKafkaPublish`)도 즉시 종료
- `audit_log.kafka_published`는 항상 `FALSE` 유지 (Kafka 비활성 표시)

#### 설정 방법

```yaml
ido:
  audit:
    kafka-publish-enabled: ${IDO_AUDIT_KAFKA_ENABLED:true}
```

```bash
IDO_AUDIT_KAFKA_ENABLED=false
```

> **개발 환경에서 OFF 권장 이유**:  
> Kafka 없이 실행 시 매 요청마다 Kafka 연결 시도 → `WARN` 로그 폭발.  
> 실제 비즈니스 오류가 감사 로그 오류에 묻혀 디버깅 어려움.

---

### F-04 감사 로그 DB 저장

**관련 클래스**: `kr.go.smes.ido.audit.AuditLogPublisher`  
**테이블**: `ido.audit_log`

#### 기능 설명

모든 감사 이벤트를 **PostgreSQL DB에 먼저 저장**한다.  
DB 저장이 선행되므로 Kafka 발행 실패 시에도 감사 기록이 보존된다.

#### ON 효과

- 모든 인증 이벤트(NICE URL 발급, 결과 조회, 콜백, OACX, CI 조회 등)가 `ido.audit_log` 테이블에 기록
- Kafka 장애 시 DB에서 재처리 가능한 안전망 역할
- 컴플라이언스(개인정보보호법, 전자서명법) 감사 추적 충족

#### OFF 효과

- DB INSERT 건너뜀 → `ido.audit_log` 테이블 불필요
- Kafka 발행(F-03)도 실질적으로 의미 없어짐
- 로컬 개발 시 `ido.audit_log` 테이블 없어도 오류 없음

#### 설정 방법

```yaml
ido:
  audit:
    db-save-enabled: ${IDO_AUDIT_DB_ENABLED:true}
```

```bash
IDO_AUDIT_DB_ENABLED=false
```

> **⚠️ 운영 환경에서 OFF 금지**:  
> 감사 로그 DB 저장 비활성화는 개인정보보호법 위반 가능성이 있음.  
> 운영에서는 반드시 `true` 유지. 스테이징 이상에서도 `true` 권장.

---

### F-05 OTel 분산 추적 AOP

**관련 클래스**: `kr.go.smes.ido.auth.tracing.AuthTracingAspect`  
**의존성**: Micrometer Tracing + OTel SDK + OTLP Exporter

#### 기능 설명

NICE/OACX/CI 인증 핵심 메서드에 **AOP 커스텀 스팬**을 자동 삽입한다.  
Spring의 자동 HTTP 트레이싱과 별도로, 비즈니스 로직 수준의 추적 가시성 제공.

**추적 대상 메서드** (8개):
```
NiceAuthService.getNicePhoneAuthUrl()       → 스팬: nice.phone.url
NiceAuthService.getNicePhoneAuthResult()    → 스팬: nice.phone.result
NiceAuthService.ensureAccessToken()         → 스팬: nice.token.ensure
AuthService.callback()                      → 스팬: auth.callback
AuthService.checkNiceCi()                  → 스팬: auth.ci.check
AuthService.handleOacxEasysign()           → 스팬: oacx.easysign
AuthService.getOacxAccessInfo()            → 스팬: oacx.access.info
KeyVersionRegistry.lookupKey()             → 스팬: crypto.key.lookup
```

**스팬 태그**: `auth.service`, `auth.operation`, `auth.result`, `correlation.id`  
**PII 보호**: CI, 이름, 생년월일 절대 포함하지 않음

#### ON 효과

- 각 메서드 진입/종료 시 자동으로 OTel 스팬 생성 및 종료
- `auth.result=success/error` 태그로 Jaeger/Tempo에서 실패 추적 가능
- 예외 발생 시 스팬에 오류 기록 후 예외 재던짐 (원본 동작 보존)
- OTLP Exporter(`management.otlp.tracing.endpoint`)로 전송

#### OFF 효과

- AOP Aspect 빈 자체가 Spring Context에 등록되지 않음 (`@ConditionalOnProperty`)
- 메서드 호출 오버헤드 완전 제거
- Jaeger/Tempo 없는 환경에서 불필요한 연결 시도 없음
- Spring 기본 HTTP 트레이싱(`/actuator/httptrace`)은 별도이므로 영향 없음

#### 설정 방법

```yaml
ido:
  tracing:
    auth-aspect-enabled: ${IDO_AUTH_TRACING_ENABLED:true}
```

```bash
IDO_AUTH_TRACING_ENABLED=false
```

> **개발 환경에서 OFF 권장 이유**:  
> Jaeger 없이 OTLP exporter가 연결 실패를 반복 → 불필요한 로그.  
> AOP 프록시 추가로 인한 약간의 성능 오버헤드 제거.

---

### F-06 Resilience4j CB+Retry (NICE)

**관련 클래스**: `kr.go.smes.ido.auth.client.NiceApiClient`  
**CB 인스턴스명**: `nice-api-client`

#### 기능 설명

NICE 인증 서버 연동에 **Circuit Breaker + Retry + TimeLimiter** 3중 보호를 적용한다.

```
[NICE API 호출]
  → TimeLimiter(12s) 초과 시 TimeoutException
  → Retry: 최대 2회 재시도 (Exponential Backoff: 500ms → 1000ms)
  → CircuitBreaker: 실패율 50% 초과 시 OPEN (30s 대기)
  → OPEN 상태 → fallback 즉시 반환
```

**fallback 동작**:
- `getAccessToken` fallback: `NiceTokenApiResponse` (빈 토큰)
- `requestAuthUrl` fallback: `NiceUrlApiResponse` (실패 표시)
- `getPhoneAuthResult` fallback: `NiceResultApiResponse` (실패 표시)

#### ON 효과

- NICE 서버 장애 시 30초 동안 추가 호출 차단 → 서버 부하 감소
- 간헐적 5xx 오류를 최대 2회 재시도로 자동 복구
- 12초 내 응답 없으면 강제 타임아웃 → 스레드 고갈 방지
- `/actuator/circuitbreakers`에서 상태 실시간 조회 가능

#### OFF 효과

- NICE API 호출 실패 시 즉시 예외 전파 → 상위 핸들러에서 처리
- 재시도 없음 → 간헐적 오류 시 바로 실패 응답 반환
- NICE 계약 전 개발 단계에서 불필요한 재시도 없음
- CB가 OPEN되어 모든 요청이 fallback 반환하는 오해 방지

#### 설정 방법 (yml override로 비활성화)

```yaml
# application-local.yml (로컬 오버라이드)
resilience4j:
  circuitbreaker:
    instances:
      nice-api-client:
        sliding-window-size: 100      # 사실상 CB 동작 안 함
        failure-rate-threshold: 100   # 100% 실패해야 OPEN
        minimum-number-of-calls: 100  # 100건 쌓여야 집계
  retry:
    instances:
      nice-api-client:
        max-attempts: 1               # 재시도 없음
```

> **참고**: Resilience4j는 `enabled=false` 속성을 공식 지원하지 않음.  
> 위와 같이 임계값을 완화하거나 `application-local.yml`에서 느슨하게 재정의.

---

### F-07 Resilience4j CB+Retry (통합인증)

**관련 클래스**: `kr.go.smes.ido.auth.client.IntegrationAuthClient`  
**CB 인스턴스명**: `integration-auth-client`

#### 기능 설명

기업 간편인증 콜백(`POST /api/v1/auth/callback`) 처리 시 통합인증 서버 호출에  
Circuit Breaker + Retry 보호를 적용한다.

**fallback**: `AuthCheckResponse` (인증 실패 표시)

#### ON 효과

- 통합인증 서버 장애 시 60초 대기 후 복구 시도 (NICE보다 긴 대기 — 복구 시간 고려)
- Retry 2회 → Exponential Backoff 500ms, 1000ms
- 서비스 장애가 사용자 요청 스레드 고갈로 번지는 것 방지

#### OFF 효과

- 통합인증 서버 호출 실패 시 즉시 예외 전파
- 현재 FE 미호출 상태이므로 개발 단계에서는 OFF가 현실적

#### 설정 방법

F-06과 동일 패턴, `integration-auth-client` 인스턴스명으로 yml 오버라이드.

---

### F-08 Redisson 분산 락

**관련 클래스**: `kr.go.smes.ido.config.RedissonConfig`, `kr.go.smes.ido.auth.service.NiceAuthService`

#### 기능 설명

K8s 다중 Pod 환경에서 **NICE Access Token 갱신이 중복 실행**되는 것을 방지한다.

```
문제: Pod-A와 Pod-B가 동시에 ensureAccessToken() 호출
     → NICE 서버에 토큰 발급 요청 2건 동시 발생
     → NICE Rate Limit 초과 위험

해결: Redis 분산 락 (ido:lock:nice-token-refresh)
     → 먼저 락 획득한 Pod만 발급, 다른 Pod는 대기 후 캐시 값 사용
```

**Lock 설정**: tryLock 대기 3초 / 만료 10초 / WatchDog 자동 갱신

#### ON 효과

- 전체 Pod에서 NICE 토큰 갱신이 단 한 번만 실행됨 (중복 제거)
- 락 대기 중 토큰이 갱신된 경우 기존 토큰 재사용
- WatchDog: 락 보유 중 Pod 비정상 종료 시 10초 후 자동 락 해제 (데드락 방지)
- `RedissonClient` 빈이 Spring Context에 등록됨

#### OFF 효과

- `RedissonClient` 빈 미등록 → Redisson 라이브러리 로드되지 않음
- Redis 없는 환경에서도 **앱 정상 기동** (가장 큰 장점)
- `ensureAccessToken()`은 JVM 내 `synchronized` 블록만으로 동작
  → 단일 Pod 환경에서는 충분, 다중 Pod에서는 중복 발급 가능
- Redis 연결이 `spring.data.redis`만 사용하므로 Redisson 전용 연결 없음

#### 설정 방법

```yaml
ido:
  redisson:
    enabled: ${IDO_REDISSON_ENABLED:true}
```

```bash
# 로컬 개발 (Redis 없음)
IDO_REDISSON_ENABLED=false
```

```java
// RedissonConfig에 @ConditionalOnProperty 적용
@Configuration
@ConditionalOnProperty(name = "ido.redisson.enabled", havingValue = "true", matchIfMissing = true)
public class RedissonConfig { ... }
```

> **⚠️ 운영 멀티 Pod 환경에서 OFF 금지**:  
> 단일 Pod 운영 시에는 OFF 가능하나, K8s HPA로 2개 이상 Pod 운영 시 반드시 ON.  
> OFF 상태에서 중복 토큰 발급 → NICE Rate Limit 위험.

---

### F-09 NHN Cloud SKM KMS

**관련 클래스**: `kr.go.smes.ido.crypto.kms.NhnKmsClient`, `kr.go.smes.ido.crypto.kms.NoOpKmsClient`

#### 기능 설명

Handoff Ticket 암복호화 키 재료를 **NHN Cloud Secure Key Manager(SKM)**로 보호한다.

```
prod 프로파일:   NhnKmsClient  → SKM REST API 호출 (실제 KMS)
non-prod 프로파일: NoOpKmsClient → Base64 decode/encode 패스스루 (KMS 없이 동작)
```

**SKM 두 가지 모드**:
| 모드 | 동작 | DB 저장 내용 |
|------|------|------------|
| `secret` (기본) | `GET /secrets/{keyId}` → 키 재료 조회 | SKM keyId |
| `envelope` | `POST /symmetric-keys/{keyId}/decrypt` → DEK 복호화 | 암호화된 DEK |

#### ON 효과 (NhnKmsClient — prod)

- DB의 `key_material_encrypted` 컬럼 값이 SKM으로 복호화됨
- 키 재료가 코드/DB에 평문 노출되지 않음 (키 재료 보호)
- `isHealthy()` 10분 주기 헬스 체크 → SKM 접근 불가 시 조기 감지
- `providerName()` = `"nhn-skm"` (로그로 확인 가능)

#### NOOP 효과 (NoOpKmsClient — non-prod)

- `decrypt(encryptedKeyBase64)` → Base64 디코딩만 수행 (키 재료 = 환경변수에서 직접 주입)
- `encrypt(plainKeyBytes)` → Base64 인코딩만 수행
- `isHealthy()` 항상 `true`
- SKM API 호출 전혀 없음 → 개발 환경에서 NHN Cloud 계정 불필요
- `providerName()` = `"noop"` (로그로 확인 가능)

#### 설정 방법

```bash
# 프로파일로 자동 전환 — 별도 환경변수 불필요
SPRING_PROFILES_ACTIVE=prod       # → NhnKmsClient
SPRING_PROFILES_ACTIVE=local      # → NoOpKmsClient
SPRING_PROFILES_ACTIVE=dev        # → NoOpKmsClient
```

```yaml
# prod 환경 추가 필요 설정
ido:
  kms:
    nhn:
      endpoint: ${NHN_SKM_ENDPOINT:https://api-keymanager.nhncloudservice.com}
      appkey: ${NHN_SKM_APPKEY}         # 필수
      mode: ${NHN_SKM_MODE:secret}
      aes-key-id: ${NHN_SKM_AES_KEY_ID}  # 필수
      hmac-key-id: ${NHN_SKM_HMAC_KEY_ID} # 필수
```

---

### F-10 보안 응답 헤더 필터

**관련 클래스**: `kr.go.smes.ido.config.SecurityHeadersFilter`

#### 기능 설명

모든 HTTP 응답에 **보안 헤더**를 자동 삽입한다.

| 헤더 | 목적 |
|------|------|
| `Content-Security-Policy` | XSS 방어 — 허가된 출처만 리소스 로드 |
| `X-Content-Type-Options: nosniff` | MIME 스니핑 공격 방지 |
| `X-Frame-Options: DENY` | Clickjacking 방어 |
| `Strict-Transport-Security` | HTTPS 강제 (max-age=1년) |
| `Referrer-Policy` | 외부 요청 시 Origin만 전달 (PII 보호) |
| `Permissions-Policy` | 불필요한 브라우저 기능 비활성화 |
| `Cache-Control: no-store` | API 응답 캐시 비활성화 |

#### ON 효과

- 모든 `/api/**`, `/fe/**` 응답에 위 헤더 자동 포함
- 브라우저 기반 XSS, Clickjacking, MIME 스니핑 공격 방어
- HTTPS 아닌 경로에서도 HSTS 헤더 전송 (브라우저 캐싱)

#### OFF 효과

- 필터 빈 자체가 등록되지 않음 → 응답 헤더 없음
- FE 개발자가 `localhost`에서 API 테스트 시 CSP 관련 오류 없음
- Postman, curl 등 도구 테스트 시 헤더 노이즈 없음

#### 설정 방법

```yaml
ido:
  security-headers:
    enabled: ${IDO_SECURITY_HEADERS_ENABLED:true}
```

```bash
IDO_SECURITY_HEADERS_ENABLED=false
```

> **CSP와 FE 개발 충돌 주의**:  
> CSP가 ON이면 브라우저가 허용되지 않은 출처의 리소스를 차단.  
> FE 개발 중 `localhost:3000`에서 API 호출 시 CSP 에러가 발생할 수 있음.  
> `ido.csp.allowed-origin` 설정 또는 헤더 OFF로 해결.

---

### F-11 개인정보 파기 스케줄러

**관련 클래스**: `kr.go.smes.ido.retention.PersonalDataRetentionScheduler`

#### 기능 설명

**매일 새벽 02:00(Asia/Seoul)**에 실행되어 보존 기간이 만료된 회원의 개인정보를 파기한다.

**파기 대상**:
- `status = 'WITHDRAWN'` 이고 `withdrawn_at < (현재 - retentionDays)`
- `inst_mbr_id_mapping.identifier_hash`, `mbr_uuid` → `NULL` 처리
- `auth_result` 테이블의 PII 컬럼 → `NULL` 처리

#### ON + dry-run=true 효과 (스테이징 권장)

- 파기 대상 조회 및 로그 출력
- **실제 DELETE/UPDATE 없음** — 어떤 데이터가 파기될지만 확인
- 로그 예시: `[RetentionScheduler][DRY-RUN] 파기 대상 15건 (실제 삭제 없음)`

#### ON + dry-run=false 효과 (운영)

- 파기 대상 데이터 **실제 NULL 처리** (영구 삭제 — 복구 불가)
- 파기 건수만큼 감사 로그 발행 (`PERSONAL_DATA_PURGED`)
- 배치당 최대 `batchSize`건 처리 (기본 100건)

#### OFF 효과

- `@Scheduled` 메서드 즉시 반환 (DB 조회조차 하지 않음)
- 개발/로컬 DB의 테스트 데이터 실수 삭제 방지
- 로그에 `[RetentionScheduler] DISABLED — 개인정보 파기 비활성` 출력

#### 설정 방법

```yaml
ido:
  retention:
    enabled: ${IDO_RETENTION_ENABLED:false}       # 기본 OFF (안전)
    dry-run: ${IDO_RETENTION_DRY_RUN:true}        # 기본 dry-run (안전)
    personal-data-days: ${IDO_RETENTION_DAYS:365} # 보존 기간 (일)
    batch-size: ${IDO_RETENTION_BATCH_SIZE:100}
```

```bash
# 운영 설정
IDO_RETENTION_ENABLED=true
IDO_RETENTION_DRY_RUN=false
IDO_RETENTION_DAYS=365

# 스테이징 검증
IDO_RETENTION_ENABLED=true
IDO_RETENTION_DRY_RUN=true    # 실제 삭제 없이 대상 확인만
```

> **🔴 매우 중요**:  
> `dry-run=false`는 데이터를 **영구적으로 복구 불가 삭제**한다.  
> 처음 운영 적용 시 반드시 `dry-run=true`로 1회 이상 검증 후 전환.  
> 법무팀 확정 보존 기간(현재 365일 임시값) 적용 후 활성화.

---

### F-12 Handoff 키 로테이션 스케줄러

**관련 클래스**: `kr.go.smes.ido.crypto.HandoffKeyRotationScheduler`

#### 기능 설명

Handoff Ticket 암복호화에 사용하는 **AES-256 키를 90일 주기로 자동 교체**한다.

```
매 시간 정각 실행 → DB의 key_material 만료 여부 확인
→ 만료된 경우: 새 키 생성 → DB 저장 → Redis 캐시 무효화
→ Grace Period(24h): 구 키로 암호화된 Ticket도 24시간 복호화 허용
```

#### ON 효과

- 키 유출 시 피해 범위를 90일로 제한 (제한적 손상)
- 새 키 생성 후 Redisson 락으로 중복 로테이션 방지 (다중 Pod)
- 키 로테이션 이벤트 감사 로그 발행

#### OFF 효과

- 키가 영구 고정 → 환경변수 `IDO_HANDOFF_AES_KEY` 값 계속 사용
- 스케줄러 실행 없음 → CPU/DB 부하 없음
- 개발 환경에서 키 변경으로 인한 테스트 불안정성 방지

#### 설정 방법

```yaml
ido:
  crypto:
    rotation-enabled: ${IDO_CRYPTO_ROTATION_ENABLED:true}
    rotation-check-cron: "0 0 * * * *"   # 매 시간 정각
```

```bash
IDO_CRYPTO_ROTATION_ENABLED=false
```

---

### F-13 IdO Outbox Relay

**관련 클래스**: `kr.go.smes.ido.infrastructure.outbox.IdoOutboxRelay`  
**테이블**: `ido.outbox`

#### 기능 설명

`KeycloakOidcService`와 `NonOidcAuthService`에서 Kafka 즉시 발행이 실패한 경우  
`ido.outbox` 테이블에 `PENDING` 레코드를 남기는데, 이를 **500ms 주기로 폴링**하여 재발행한다.

```
[인증 이벤트 발생]
  → Kafka 즉시 발행 시도
  → 실패: ido.outbox INSERT (PENDING)
  → IdoOutboxRelay (500ms 폴링)
    → SELECT FOR UPDATE SKIP LOCKED (동시성 안전)
    → Kafka 재발행
    → 성공: status=PUBLISHED
    → max_retry 초과: status=FAILED
```

#### ON 효과

- Kafka 일시 장애 시에도 인증 이벤트 **at-least-once** 보장
- 다중 Pod 환경에서 `SKIP LOCKED`로 중복 처리 방지
- 재발행 실패 건은 `FAILED` 상태로 관리 → 수동 처리 가능

#### OFF 효과

- `@Scheduled` 메서드 즉시 반환 → 폴링 없음
- 500ms마다 `SELECT PENDING` 쿼리 없음 → DB 부하 없음
- Kafka 없는 로컬 환경에서 연결 오류 로그 없음
- `ido.outbox` 테이블이 없어도 오류 없음

#### 설정 방법

```yaml
ido:
  outbox:
    relay-enabled: ${IDO_OUTBOX_RELAY_ENABLED:true}
    relay-interval-ms: 500
    batch-size: 100
    max-retry: 3
```

```bash
IDO_OUTBOX_RELAY_ENABLED=false
```

---

### F-14 Webhook Outbox Relay

**관련 클래스**: `kr.go.smes.ido.webhook.WebhookDispatchOutboxRelay`  
**테이블**: `ido.webhook_dispatch_outbox`

#### 기능 설명

기관 외부 엔드포인트(HTTPS Webhook)로 이벤트를 발송하는 **Outbox Relay**.  
500ms 주기로 `PENDING` 레코드를 조회하여 기관 Webhook URL에 HTTP POST.

```
[기관 이벤트 발생]
  → webhook_dispatch_outbox INSERT (PENDING)
  → WebhookDispatchOutboxRelay (500ms 폴링)
    → HTTP POST → 기관 endpoint
    → 200~299: DISPATCHED
    → 4xx/5xx: retry_count++, Exponential Backoff
    → max_retry 초과: FAILED
```

#### ON 효과

- 기관이 등록한 Webhook URL로 실시간 이벤트 전달
- HMAC-SHA256 서명으로 변조 방지 (`X-Webhook-Signature` 헤더)
- 발송 실패 시 지수 백오프 재시도 → 일시적 기관 서버 장애 대응

#### OFF 효과

- 폴링 중지 → 기관 Webhook 발송 없음
- `webhook_dispatch_outbox` 테이블 PENDING 레코드 쌓임 (나중에 ON 시 일괄 처리)
- 기관 Webhook 서버 없는 개발 환경에서 연결 오류 없음
- DB 500ms 폴링 부하 없음

#### 설정 방법

```yaml
ido:
  webhook:
    relay-enabled: ${IDO_WEBHOOK_RELAY_ENABLED:true}
    relay-interval-ms: 500
    relay-batch-size: 50
    max-retry: 3
```

```bash
IDO_WEBHOOK_RELAY_ENABLED=false
```

---

### F-15 CORS 제어

**관련 클래스**: `kr.go.smes.ido.fe.config.IdoWebMvcConfig`

#### 기능 설명

React FE(`localhost:3000`, `localhost:3001`)에서 IdO API를 직접 호출할 수 있도록  
**Cross-Origin Resource Sharing** 헤더를 설정한다.

#### ON 효과

- 설정된 Origin에서 오는 브라우저 요청에 `Access-Control-Allow-Origin` 응답
- `allowedMethods`, `allowedHeaders`, `allowCredentials` 포함
- Preflight(OPTIONS) 요청에 204 응답

#### OFF 효과

- CORS 헤더 없음 → 브라우저에서 직접 API 호출 불가 (curl/서버간 호출은 무관)
- Nginx 등 게이트웨이에서 CORS 처리하는 경우 OFF 가능

#### 설정 방법

```yaml
ido:
  cors:
    enabled: ${IDO_CORS_ENABLED:true}
    allowed-origins:
      - http://localhost:3000
      - http://localhost:3001
      - ${CORS_ORIGIN_PROD:}
```

---

### F-16 Bean Validation (끄면 안 됨)

**관련 클래스**: `kr.go.smes.ido.auth.controller.AuthController`  
**어노테이션**: `@Validated`, `@Valid`, `@NotBlank`, `@Size`, `@Pattern`

#### 기능 설명

클라이언트 입력값을 **서비스 레이어 진입 전**에 검증한다.

**검증 대상 DTO**:
- `NicePhoneAuthResultRequest`: `tokenVersionId`, `encData`, `integrityValue` 형식 검증
- `CiCheckRequest`: `ciValue` 길이/패턴 검증
- `OacxEasysignRequest`: `fn`, `data` 필수 값 검증

#### 왜 끄면 안 되는가?

```
1. 보안: 악의적 입력(SQL Injection, XSS 페이로드 등)이 서비스 레이어까지 전달됨
2. 안정성: 잘못된 타입/형식의 데이터로 NullPointerException 등 예상치 못한 오류
3. 표준: Spring의 핵심 보안 기능 — 비활성화는 보안 감사 위반
4. 코드 의존성: 서비스 레이어가 검증 없이 동작하도록 설계되지 않음
```

**이 기능에는 On/Off 플래그를 두지 않는다.**

---

### F-17 Traceparent 상관관계 ID 필터

**관련 클래스**: `kr.go.smes.ido.config.TraceparentFilter`

#### 기능 설명

모든 HTTP 요청에서 **W3C `traceparent`와 `X-Correlation-Id`를 추출하거나 신규 생성**하여  
요청 처리 전체 생명주기 동안 ThreadLocal에 보관한다.

```
요청 수신
  → traceparent 헤더 추출 (없으면 신규 UUID 생성)
  → X-Correlation-Id 추출 (없으면 traceparent traceId 사용)
  → CorrelationIdHolder.set(correlationId)
  → 응답 헤더에 X-Correlation-Id 포함
  → 요청 완료 후 ThreadLocal.remove() (메모리 누수 방지)
```

#### 왜 끄면 안 되는가?

```
1. 장애 대응: correlationId 없으면 특정 요청의 로그 추적 불가
2. 무해함: 헤더 없을 때 신규 생성 → 부작용 없음
3. OTel 연동: AuthTracingAspect가 correlationId를 스팬 태그에 사용
4. 감사 로그: AuditEntry.correlationId 필드에 연결됨
```

**이 기능에는 On/Off 플래그를 두지 않는다.**

---

### F-18 SP 수신 감사 로그

**관련 클래스**: `kr.go.smes.ido.qim.sp.service.QimSpReceiverService`

#### 기능 설명

Q-IM이 IdO SP 수신 API를 호출할 때(회원 등록/수정/탈퇴) **감사 로그를 남기는 기능**.  
F-03/F-04(전체 감사 로그)와는 독립적인 별도 플래그.

#### 설정 방법

```yaml
ido:
  qim:
    receiver-audit-enabled: ${IDO_QIM_RECEIVER_AUDIT:true}
```

```bash
IDO_QIM_RECEIVER_AUDIT=false
```

---

## 5. application-local.yml 완성본

로컬 개발 환경에서 **모든 외부 의존성 없이 안전하게** 앱을 실행하기 위한 최종 오버라이드 파일.

```yaml
# ============================================================
# IdO local 프로파일 — 로컬 개발 환경 안전 기본값
# 목적: 외부 의존성(Redis/Kafka/OTel/SKM) 없이도 앱 기동 가능
# 사용법: --spring.profiles.active=local
# ============================================================

spring:
  kafka:
    bootstrap-servers: localhost:9092
    listener:
      missing-topics-fatal: false        # Kafka 없어도 기동 허용
    consumer:
      properties:
        metadata.max.age.ms: 5000
  flyway:
    enabled: true
    connect-retries: 3
    connect-retries-interval: 3s

# ── Feature Flags: 로컬 안전 기본값 ─────────────────────────────────────
ido:

  # F-08: Redisson 분산 락 OFF → Redis 없어도 앱 기동 가능
  redisson:
    enabled: false

  # F-01: IP Auth Rate Limiting OFF → 반복 테스트 시 자기 IP 차단 방지
  auth:
    rate-limit:
      enabled: false
    # NICE/OACX 로컬 테스트 설정
    nice:
      client-id: ${NICE_CLIENT_ID:}
      client-secret: ${NICE_CLIENT_SECRET:}
      return-url: ${NICE_RETURN_URL:http://localhost:3000/otp/auth-result}
      timeout-seconds: 15
    oacx:
      provider-key-path: ${OACX_PROVIDER_KEY_PATH:}
      debug-mode: true
    integration:
      base-url: ${INTEGRATION_AUTH_BASE_URL:http://localhost:9292}
      timeout-seconds: 15

  # F-02: 기관 Rate Limiting OFF → 로컬 테스트 중 기관 제한 없음
  rate-limit:
    enabled: false

  # F-03: Kafka 감사 로그 발행 OFF → Kafka 없어도 오류 없음
  # F-04: DB 감사 로그 저장 OFF → audit_log 테이블 없어도 오류 없음
  audit:
    kafka-publish-enabled: false
    db-save-enabled: false
    retry-interval-ms: 3600000          # 로컬에서 재처리 주기 1시간으로 늘림

  # F-11: 개인정보 파기 스케줄러 OFF → 개발 DB 데이터 보호
  retention:
    enabled: false
    dry-run: true                        # 이중 안전장치

  # F-12: 키 로테이션 OFF → 개발 중 키 변경으로 인한 테스트 불안정성 방지
  crypto:
    rotation-enabled: false

  # F-13: IdO Outbox Relay OFF → Kafka 없는 환경 500ms 폴링 오류 없음
  outbox:
    relay-enabled: false

  # F-14: Webhook Relay OFF → 기관 endpoint 없는 로컬 환경
  webhook:
    relay-enabled: false

  # F-10: 보안 헤더 OFF → FE 개발 중 CSP 오류 없음 (필요 시 ON으로 변경)
  security-headers:
    enabled: false

  # F-18: SP 수신 감사 로그 OFF
  qim:
    aes-shared-key: bG9jYWwtdGVzdC1zZWNyZXQtMzJieXRlcy1wYWRkZWQ=
    receiver-audit-enabled: false

  # F-05: OTel 추적 OFF → Jaeger 없는 로컬 환경
  tracing:
    auth-aspect-enabled: false

  agency-subject-secret: local-test-secret-32-bytes-padding

# ── Resilience4j 로컬 완화 설정 (F-06, F-07) ─────────────────────────────
# NICE/통합인증 서버 없는 로컬에서 CB가 즉시 OPEN되는 것 방지
resilience4j:
  circuitbreaker:
    instances:
      nice-api-client:
        minimum-number-of-calls: 100    # 100건 쌓여야 CB 집계 시작
        failure-rate-threshold: 100     # 100% 실패해야 OPEN
        sliding-window-size: 100
      integration-auth-client:
        minimum-number-of-calls: 100
        failure-rate-threshold: 100
        sliding-window-size: 100
  retry:
    instances:
      nice-api-client:
        max-attempts: 1                 # 재시도 없음 (로컬 테스트 속도 향상)
      integration-auth-client:
        max-attempts: 1

# ── 로컬 로그 레벨 ───────────────────────────────────────────────────────
logging:
  level:
    org.springframework.kafka: ERROR
    org.apache.kafka: ERROR
    kr.go.smes.ido.auth: DEBUG          # 인증 흐름 상세 로그
    kr.go.smes.ido.crypto: DEBUG        # 암호화 디버그
    kr.go.smes.ido.ratelimit: DEBUG     # Rate Limit 디버그
```

---

## 6. K8s ConfigMap 환경변수 목록

`infra/k8s/configmaps/ido-configmap.yml`에 반영할 비민감 Feature Flag 환경변수 전체 목록.

```yaml
# Feature Flags
IDO_AUTH_RL_ENABLED: "true"
IDO_AUTH_RL_TPS: "20"
IDO_AUTH_RL_PER_MIN: "100"
IDO_AUTH_RL_DAILY: "1000"

IDO_RATE_LIMIT_ENABLED: "true"
IDO_RATE_LIMIT_DEFAULT_TPS: "200"
IDO_RATE_LIMIT_DEFAULT_DAILY: "1000000"

IDO_AUDIT_KAFKA_ENABLED: "true"
IDO_AUDIT_DB_ENABLED: "true"

IDO_AUTH_TRACING_ENABLED: "true"

IDO_REDISSON_ENABLED: "true"

IDO_SECURITY_HEADERS_ENABLED: "true"

IDO_RETENTION_ENABLED: "false"           # 운영 전환 시 별도 패치
IDO_RETENTION_DRY_RUN: "true"
IDO_RETENTION_DAYS: "365"

IDO_CRYPTO_ROTATION_ENABLED: "true"

IDO_OUTBOX_RELAY_ENABLED: "true"
IDO_WEBHOOK_RELAY_ENABLED: "true"

IDO_QIM_RECEIVER_AUDIT: "true"
```

---

## 7. 런타임 현재 상태 확인 방법

앱 실행 중 어떤 기능이 활성화되어 있는지 확인하는 방법.

### 7-1. Actuator Features 엔드포인트

```bash
curl http://localhost:8083/actuator/features | jq .
```

**예시 응답**:
```json
{
  "features": {
    "authRateLimit": { "enabled": false, "env": "IDO_AUTH_RL_ENABLED" },
    "agencyRateLimit": { "enabled": false, "env": "IDO_RATE_LIMIT_ENABLED" },
    "auditKafka": { "enabled": false, "env": "IDO_AUDIT_KAFKA_ENABLED" },
    "auditDb": { "enabled": false, "env": "IDO_AUDIT_DB_ENABLED" },
    "authTracing": { "enabled": false, "env": "IDO_AUTH_TRACING_ENABLED" },
    "redissonLock": { "enabled": false, "env": "IDO_REDISSON_ENABLED" },
    "securityHeaders": { "enabled": false, "env": "IDO_SECURITY_HEADERS_ENABLED" },
    "retentionJob": { "enabled": false, "env": "IDO_RETENTION_ENABLED" },
    "retentionDryRun": { "enabled": true, "env": "IDO_RETENTION_DRY_RUN" },
    "cryptoRotation": { "enabled": false, "env": "IDO_CRYPTO_ROTATION_ENABLED" },
    "outboxRelay": { "enabled": false, "env": "IDO_OUTBOX_RELAY_ENABLED" },
    "webhookRelay": { "enabled": false, "env": "IDO_WEBHOOK_RELAY_ENABLED" },
    "kmsProvider": { "provider": "noop", "env": "SPRING_PROFILES_ACTIVE" }
  }
}
```

### 7-2. 애플리케이션 시작 로그

앱 기동 시 `FeatureFlags` 빈이 현재 설정값을 INFO 레벨로 출력:

```
[FeatureFlags] 현재 기능 활성화 상태:
  F-01 authRateLimit       = false  (IDO_AUTH_RL_ENABLED)
  F-02 agencyRateLimit     = false  (IDO_RATE_LIMIT_ENABLED)
  F-03 auditKafka          = false  (IDO_AUDIT_KAFKA_ENABLED)
  F-04 auditDb             = false  (IDO_AUDIT_DB_ENABLED)
  F-05 authTracing         = false  (IDO_AUTH_TRACING_ENABLED)
  F-08 redissonLock        = false  (IDO_REDISSON_ENABLED)
  F-09 kmsProvider         = noop   (Spring Profile: local)
  F-10 securityHeaders     = false  (IDO_SECURITY_HEADERS_ENABLED)
  F-11 retentionJob        = false  (IDO_RETENTION_ENABLED)
  F-11b retentionDryRun   = true   (IDO_RETENTION_DRY_RUN)
  F-12 cryptoRotation      = false  (IDO_CRYPTO_ROTATION_ENABLED)
  F-13 outboxRelay         = false  (IDO_OUTBOX_RELAY_ENABLED)
  F-14 webhookRelay        = false  (IDO_WEBHOOK_RELAY_ENABLED)
  F-18 spReceiverAudit     = false  (IDO_QIM_RECEIVER_AUDIT)
```

### 7-3. 개별 기능 확인

```bash
# Circuit Breaker 상태
curl http://localhost:8083/actuator/circuitbreakers | jq .

# Health (전체)
curl http://localhost:8083/actuator/health | jq .

# 환경변수 확인 (Spring Cloud Config 또는 /env)
curl http://localhost:8083/actuator/env | jq '.propertySources[] | select(.name | contains("applicationConfig"))'
```

---

## 8. 자주 묻는 질문

**Q1. 로컬에서 앱 기동이 안 돼요. `RedissonClient` 오류가 납니다.**

> `IDO_REDISSON_ENABLED=false` 설정 또는 `application-local.yml`의 `ido.redisson.enabled: false` 확인.  
> `--spring.profiles.active=local` 프로파일로 실행 시 자동 OFF.

**Q2. 반복 테스트 중 429 오류가 납니다.**

> `IDO_AUTH_RL_ENABLED=false`로 IP Rate Limit 비활성화.  
> 또는 `IDO_AUTH_RL_TPS=1000`, `IDO_AUTH_RL_PER_MIN=10000`으로 한도 상향.

**Q3. 로컬에서 감사 로그 관련 오류가 계속 납니다.**

> `IDO_AUDIT_KAFKA_ENABLED=false`와 `IDO_AUDIT_DB_ENABLED=false` 모두 설정.  
> `ido.audit_log` 테이블 없는 환경에서는 DB 저장도 OFF해야 오류 없음.

**Q4. OTel exporter 연결 오류가 나는데 무시해도 되나요?**

> `IDO_AUTH_TRACING_ENABLED=false`로 AOP Aspect OFF.  
> Spring Boot 자체 OTel 자동 설정(`management.otlp.tracing`)도 별도 비활성화 필요:  
> `management.tracing.enabled=false` 또는 `management.otlp.tracing.endpoint=` (빈 값)

**Q5. 개인정보 파기 스케줄러가 실수로 실행될까봐 걱정됩니다.**

> 기본값이 `IDO_RETENTION_ENABLED=false`이므로 명시적으로 `true`로 설정하지 않으면 절대 실행되지 않음.  
> 추가로 `IDO_RETENTION_DRY_RUN=true` 이중 안전장치 → 실제 삭제는 dry-run=false여야만 가능.

**Q6. Circuit Breaker가 OPEN 상태가 되어 모든 NICE 요청이 fallback 반환합니다.**

> `curl http://localhost:8083/actuator/circuitbreakers`로 상태 확인.  
> OPEN 상태라면 30초 대기 후 HALF_OPEN → 탐침 요청 성공 시 CLOSED 복귀.  
> 즉시 초기화: `curl -X POST http://localhost:8083/actuator/circuitbreakers/nice-api-client/reset` (Resilience4j Actuator 활성화 필요).  
> 개발 환경에서는 `application-local.yml`의 CB 완화 설정으로 사실상 비활성화.

**Q7. KMS 관련 설정 없이 prod 프로파일 사용하면 어떻게 되나요?**

> `NhnKmsClient`가 등록되고 `NHN_SKM_APPKEY`가 비어있으면 모든 키 복호화 실패.  
> 운영 배포 전 반드시 K8s Secret에 `NHN_SKM_APPKEY`, `NHN_SKM_AES_KEY_ID`, `NHN_SKM_HMAC_KEY_ID` 주입 필수.

**Q8. Webhook Relay가 OFF이면 기관이 이벤트를 못 받는 건가요?**

> Webhook 발송은 멈추지만 `webhook_dispatch_outbox` 테이블에 PENDING으로 쌓임.  
> Relay를 다시 ON하면 쌓인 PENDING 레코드부터 순서대로 처리됨 (데이터 유실 없음).  
> 단, max_retry 설정값이 지나면 FAILED 처리되어 수동 재처리 필요.

---

> **문서 최종 업데이트**: 2026-05-11 (Sprint 9 기준)  
> **문의**: IdO 개발팀 백엔드 채널
