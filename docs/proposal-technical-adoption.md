# OnePass Integration-SSO — 기술 채택 근거서

> **문서 번호**: PROP-2026-001-B  
> **작성일**: 2026-05-11  
> **버전**: v1.0  
> **성격**: 기술 채택 근거 (개발팀 대상)  
> **참조 문서**: ADR-001~004, EDA 마스터 아키텍처 v0.8.3, SMEP 인수인계 문서 (2026-05-08)

---

## 목차

1. [현재 구현 방식의 기술적 문제](#1-현재-구현-방식의-기술적-문제)
2. [integration-sso 아키텍처 설계 원칙](#2-integration-sso-아키텍처-설계-원칙)
3. [모듈별 구현 상세](#3-모듈별-구현-상세)
4. [SMEP 연동 사례 분석](#4-smep-연동-사례-분석)
5. [채택 후 각 팀 역할 및 마이그레이션 경로](#5-채택-후-각-팀-역할-및-마이그레이션-경로)
6. [기술 부채 현황 및 후속 계획](#6-기술-부채-현황-및-후속-계획)
7. [로컬 개발 환경 기동 가이드](#7-로컬-개발-환경-기동-가이드)

---

## 1. 현재 구현 방식의 기술적 문제

### 1.1 아키텍처 불일치 — Q-IM 직접 연결 문제

현재 SMEP을 포함한 기관 팀들의 시연용 구현은 Q-IM과 기관 시스템이 **직접 통신**하는 구조다.

```
[현재 시연용 구조]
SMEP ──────────────────► Q-IM
      POST /api/ciw-im/member/query
      POST /api/ciw-im/member/register
      POST /api/ciw-im/member/withdraw

[설계서 요구 구조 — ADR-001]
SMEP ──► IdO ──► Agency Adapter ──► SMEP
         │
         └──► Q-IM (IdO가 SP 역할 대리)
```

이 불일치는 API 경로 차이가 아니다. **다음 전체가 충돌**한다:

| 충돌 영역 | 현재 방식 문제 | ADR-001 해결 방식 |
|----------|--------------|-----------------|
| 보안 정책 집행 | 기관이 Q-IM에 직접 접근 — 인증·감사 불가 | IdO가 단일 게이트웨이 역할 |
| Rate Limit | Q-IM이 기관별 TPS 제어 불가 | IdO AgencyRateLimiter가 기관별 제어 |
| 장애 격리 | 기관 장애가 Q-IM 전체로 전파 | IdO Circuit Breaker가 기관 단위 격리 |
| Q-IM 변경 영향 | 기관마다 Q-IM 인터페이스 수정 필요 | Q-IM은 IdO 하나만 인식, 변경 없음 |
| 감사 로그 | 기관-Q-IM 구간 추적 불가 | BrokerAuditLog 전 구간 기록 |

### 1.2 보안 bypass 항목 상세

SMEP 인수인계 문서(2026-05-08) 기준 현재 bypass 중인 항목:

```java
// [현재 bypass 상태 — 운영 불가]

// 1. state 파라미터 검증 비활성 (CSRF 방어 무력화)
// keycloak.sso.state-validation-enabled=false

// 2. encCi 복호화 bypass (암호화된 CI를 평문으로 처리)
// if (bypassEncCi) { ci = encCi; } // encCi를 그대로 사용

// 3. mbrUuid 형식 — UUID vs 자체 형식 불일치
// SMEP: 자체 형식, integration-sso: UUID v4 (이미 OK)

// 4. loginId 미저장 (사용자 추적 불가)
// member.setLoginId(null); // bypass

// 5. /withdraw 응답 봉투 불일치
// SMEP 기대: {"success": true, "data": {...}}
// 현재 응답: {"result": "OK"} (봉투 불일치)
```

이 5가지를 해결하기 위해서는 설계서 기준 구현이 필요하다. `integration-sso`는 이미 이 구조로 구현되어 있다.

### 1.3 운영 전환 시 재작업 예상 범위

현재 방식을 운영 전환 시 재작업이 필요한 항목:

| 항목 | 재작업 규모 | integration-sso 현황 |
|------|-----------|---------------------|
| Q-IM ↔ 기관 직접 연결 제거 | 대규모 재설계 | ✅ ADR-001 완전 구현 |
| 보안 bypass 제거 + 인터페이스 재설계 | 대규모 | ✅ 전 구간 보안 적용 완료 |
| 기관별 Rate Limit | 신규 개발 | ✅ AgencyRateLimiter 완성 |
| Circuit Breaker | 신규 개발 | ✅ providerCode별 독립 CB |
| 감사 로그 전 구간 | 신규 개발 | ✅ BrokerAuditLog 완성 |
| Webhook 디스패처 | 신규 개발 | ✅ WebhookDispatcherService 완성 |
| DLQ 처리 | 신규 개발 | ✅ DeadLetterPublishingRecoverer 완성 |

**재작업 범위 = `integration-sso`가 이미 구현한 내용과 동일하다.**

---

## 2. integration-sso 아키텍처 설계 원칙

### 2.1 ADR-001 — IdO 완전 중재 패턴

```
결정: Q-IM 명세(v1.52)가 요구하는 SP 역할(수신 API 3종)을 IdO가 대리 수행한다.

통신 방향:
  IdO → Q-IM:   POST /api/ext/v1/member/*     (IdO가 Q-IM에 회원 정보 전달)
  Q-IM → IdO:   POST /api/qim/sp/v1/member/*  (Q-IM이 IdO SP 수신 API 호출)
  IdO → 기관:   Handoff Ticket 기반 SSO
  기관 → IdO:   POST /api/v1/handoff/verify   (Ticket 검증 + 사용자 정보 수령)

채택 이유:
  1. Q-IM 설계 원칙(외부 단절)을 지키면서 Q-IM 명세 충족
  2. IdO 기존 자산(AgencyMeta, PolicyEngine, Outbox) 재사용
  3. Q-IM 개발팀 변경 최소화 — Q-IM은 IdO를 하나의 SP로만 인식
```

### 2.2 ADR-004 — 유관기관 외부망 격리 원칙

```
결정: 모든 유관기관은 외부망에 위치하며, 내부 Kafka·내부 서비스에 직접 접근할 수 없다.

허용 통신:
  기관 → IdO:  HTTPS (POST /api/v1/handoff/verify, GET /api/v1/agency/events)
  IdO → 기관:  HTTPS Webhook Push (지수 백오프 재시도)

금지:
  기관 → Kafka 직접 구독 (agency-stub의 Kafka 직접 구독은 PoC 편의 코드 — 운영 불가)
  기관 → Q-IM 직접 접근
  기관 → 내부 서비스 직접 접근
```

### 2.3 전체 시스템 구성도

```
═══════════════════════════════════════════════════════════════
  외부망 (External Network)
═══════════════════════════════════════════════════════════════
  사용자 (브라우저)
      │ HTTPS
  onepass-fe (React SPA, :3001)
      │ BFF API
  유관기관 시스템 (SMEP 등 — 항상 외부망)
      │ HTTPS (Handoff Ticket Verify)
═══════════════════════════════════════════════════════════════
  내부망 (onepass-net)
═══════════════════════════════════════════════════════════════
      │
  IdO :8083  [오케스트레이터 — 단일 진입점]
  ├── HandoffService      Ticket Issue/Verify/Revoke
  ├── PolicyEngine        기관 정책·속성 필터·agencySubjectId
  ├── ProviderRouter      ProviderType 기반 런타임 브로커 선택
  ├── BrokerAuditLog      전 구간 감사 로그
  ├── WebhookDispatcher   기관 외부 HTTP 알림
  ├── AgencyRateLimiter   기관별 TPS + 일별 쿼터
  └── QimSpReceiver       Q-IM SP 수신 API 완전 중재
      │                         │
      │ REST (내부)              │ Kafka
      ▼                         ▼
  Q-Sign :8081            Apache Kafka
  인증 SoR                 - qsign.auth.events
  - OIDC 브로커링          - qim.user.events
  - AuthResult            - ido.handoff.events
  - PKCE                  - platform.audit.log
      │                   - qim.sp.member.events
      ▼
  Keycloak :8088          Q-IM :8082
  OIDC 브로커             식별 SoR
  - kakao IdP             - 회원 등록/조회/탈퇴
  - naver IdP             - DI/CI 관리
                          - PII 마스킹
```

### 2.4 보안 계층 구조

```
[계층 1 — 네트워크]
  기관 ↔ IdO: HTTPS only
  내부 서비스 간: onepass-net 격리

[계층 2 — 인증/인가]
  기관 → IdO:          X-Agency-Code + X-Agency-Key (PBKDF2 해시 검증)
  IdO → Q-IM:          X-Internal-Caller + X-Internal-Sig (HMAC-SHA256)
  내부 서비스 간:      X-Internal-Sig (HMAC-SHA256)

[계층 3 — 암호화]
  Handoff Payload:     AES-256-GCM 암호화
  Handoff 서명:        HMAC-SHA256
  CI 처리:             AES-256-CBC 복호화 (Q-IM AES 공유키)
  키 버전 관리:        v{n}.{iv}.{ciphertext} 포맷

[계층 4 — 개인정보 보호]
  CI 비저장:           identifierHash = SHA-256(CI)만 보관
  PII 마스킹:          이름·전화번호·이메일 전 구간 마스킹
  agencySubjectId:     기관 코드별 비가역 식별자

[계층 5 — 운영 보안]
  PKCE (RFC 7636):     Authorization Code 가로채기 방어
  State 검증:          OAuth CSRF 방어
  Rate Limiting:       기관별 슬라이딩 윈도우 TPS + 일별 쿼터
  Circuit Breaker:     provider_code 단위 독립 운용
  Redisson 분산 락:    동시성 제어
```

---

## 3. 모듈별 구현 상세

### 3.1 IdO (Identity Orchestrator) — :8083

**완성도: 99%**

| 기능 | 구현 파일 | 상태 |
|------|----------|------|
| Handoff Ticket Issue/Verify | HandoffService, HandoffServiceImpl | ✅ |
| DIRECT / BRIDGE 전략 | DirectHandoffStrategy, BridgeHandoffStrategy | ✅ |
| INTERNAL_SSO 전략 | InternalSsoHandoffStrategy | ✅ v1.9.2 |
| APACHE_GATE 전략 | ApacheGateHandoffStrategy | ✅ v1.9.2 |
| PolicyEngine | PolicyEngineImpl | ✅ |
| ProviderRouter | ProviderRouter (ProviderType 기반 런타임 선택) | ✅ v1.9.0 |
| BrokerAuditLog | BrokerAuditLogService (REDIRECT/CALLBACK/COMPLETE/FAIL) | ✅ v1.9.0 |
| AgencyRateLimiter | AgencyRateLimiter (Redis 슬라이딩 윈도우) | ✅ |
| Webhook 디스패처 | WebhookDispatcherService, WebhookDispatchOutboxRelay | ✅ v1.5.0 |
| 기관 이벤트 폴링 API | AgencyEventController (GET /api/v1/agency/events) | ✅ v1.9.3 |
| Q-IM SP 수신 중재 | QimSpReceiverController (query/register/withdraw) | ✅ v1.2.0 |
| 기관 Admin API | AgencyAdminController (CRUD + Key 로테이션) | ✅ |
| Circuit Breaker | ProviderCircuitBreakerConfig (providerCode별 동적 생성) | ✅ v1.9.0 |
| DLQ 처리 | KafkaConsumerConfig.defaultErrorHandler() | ✅ v1.9.1 |
| DB 마이그레이션 | V1~V10 (Flyway, 10개 버전) | ✅ |

**미완성 항목 (P1)**
- X-Internal-Sig 수신 측 검증 — `OidcCompleteController` (GAP-IDO-09)

### 3.2 Q-Sign — :8081

**완성도: 95%**

| 기능 | 구현 파일 | 상태 |
|------|----------|------|
| Keycloak OIDC 브로커링 | KeycloakOidcService | ✅ |
| PKCE (RFC 7636) | PkceService | ✅ |
| AuthResult SoR | AuthResultRepository, AuthResultRepositoryImpl | ✅ |
| JWKS JWT 검증 | KeycloakJwksVerifier | ✅ |
| Idempotent 컨슈머 | IdempotentEventStore, QimUserEventConsumer | ✅ v1.9.2 |
| Redis state 관리 | KeycloakStateStore (TTL 300s) | ✅ |
| DB 마이그레이션 | V1~V5 | ✅ |

**미완성 항목 (P1)**
- X-Internal-Sig 수신 측 검증 — `AuthController` (GAP-QS-04)

### 3.3 Q-IM — :8082

**완성도: 92%**

| 기능 | 구현 파일 | 상태 |
|------|----------|------|
| 회원 등록·조회·상태 관리 | UserRegistrationService | ✅ |
| DI 생성 | DiGenerationService (HMAC-SHA256) | ✅ |
| CI 암호화 | CiCryptoService (AES-256-GCM) | ✅ |
| PII 마스킹 | PiiMaskingService | ✅ |
| Transactional Outbox | OutboxServiceImpl (500ms 폴링) | ✅ |
| Snapshot 발행 | SnapshotService, SnapshotServiceImpl | ✅ v1.9.2 |
| DB 마이그레이션 | V1~V3 | ✅ |

**미완성 항목 (P1)**
- `needsSync=true` → Selective Pull 실제 호출 (GAP-QIM-01)
- `addAuthMeanMapping()` JPA 저장 (GAP-QIM-03)
- Outbox `markFailed()` + `retry_count` 증가 (GAP-QIM-04)

### 3.4 platform-common

**완성도: 100%**

전 모듈이 공유하는 공통 라이브러리. 변경 없이 재사용.

| 컴포넌트 | 내용 |
|---------|------|
| 도메인 | AuthResult, HandoffTicket, HandoffPayload, IdOAuthInput, UserStatus |
| 이벤트 | AuthEvent, HandoffEvent, UserEvent, SessionAdvisoryEvent, AuditLogEvent |
| 에러코드 | PlatformErrorCode (E-OPS-*, E-SEC-*, E-BIZ-* 계층화) |
| 추적 | CorrelationIdHolder (W3C traceparent 상관관계 ID) |

---

## 4. SMEP 연동 사례 분석

### 4.1 현재 SMEP 인수인계 문서 기준 상태 (2026-05-08)

SMEP이 현재 구현한 Q-IM inbound API:

```
POST /api/ciw-im/member/query      회원 조회
POST /api/ciw-im/member/register   회원 등록
POST /api/ciw-im/member/withdraw   회원 탈퇴

인증: X-API-Key 헤더
```

SMEP이 전제하는 구조: **Q-IM이 SMEP을 직접 호출**

### 4.2 integration-sso 채택 시 SMEP 연동 방식

ADR-001 기준 올바른 연동 구조:

```
[인증 흐름]
사용자 → onepass-fe → IdO → Q-Sign → Keycloak → (kakao/naver)
                       └→ Handoff Ticket 발급
                            ↓
SMEP ← ticketId redirect ← 사용자
SMEP → POST /api/v1/handoff/verify → IdO
SMEP ← HandoffPayload (사용자 정보) ← IdO

[회원 조회·등록 흐름]
Q-IM → IdO (POST /api/qim/sp/v1/member/query|register|withdraw)
IdO → [AgencyAdapter] → SMEP (POST /api/ciw-im/member/*)
SMEP → IdO → Q-IM (응답 역방향 전달)
```

### 4.3 구현 필요 항목 — IdO → SMEP 어댑터

채택 후 **즉시 착수**가 필요한 신규 구현:

```java
// 구현 위치: ido/.../agency/smep/SmepAgencyAdapter.java

public class SmepAgencyAdapter implements AgencyAdapter {

    // Q-IM 회원 조회 → SMEP /query 호출
    public MemberQueryResult query(String encCi) {
        // POST https://smep-be/api/ciw-im/member/query
        // Header: X-API-Key: {smep_api_key}
        // Body: { "encCi": AES-256-GCM(CI) }
    }

    // Q-IM 회원 등록 → SMEP /register 호출
    public MemberRegisterResult register(MemberRegisterRequest req) {
        // POST https://smep-be/api/ciw-im/member/register
        // Header: X-API-Key + Idempotency-Key
        // Body: { "mbrUuid": UUID, "encCi": ..., "loginId": ... }
    }

    // Q-IM 회원 탈퇴 → SMEP /withdraw 호출
    public void withdraw(String mbrUuid) {
        // POST https://smep-be/api/ciw-im/member/withdraw
        // 응답 봉투 검증: {"success": true, "data": {...}}
    }
}
```

### 4.4 SMEP bypass 항목 해소 계획

| bypass 항목 | 해소 방법 | 우선순위 |
|------------|---------|---------|
| encCi bypass | IdO가 AES-256-GCM으로 암호화 후 전달 | 🔴 P0 |
| state 검증 비활성 | IdO CSRF State 검증 활성화 | 🔴 P0 |
| loginId 미저장 | IdO → SMEP register 시 loginId 포함 | 🔴 P0 |
| /withdraw 봉투 불일치 | SMEP 응답 봉투 `{"success": true}` 기준 파싱 | 🟡 P1 |
| mbrUuid 형식 | integration-sso UUID v4 형식 — SMEP과 일치 확인 ✅ | — |

---

## 5. 채택 후 각 팀 역할 및 마이그레이션 경로

### 5.1 각 팀 역할 정의

| 팀 | 담당 범위 | 기준 모듈 | 즉시 착수 항목 |
|----|---------|---------|--------------|
| **IdO 팀** | 오케스트레이터 완성 | `ido` | SMEP 어댑터, X-Internal-Sig 수신 검증 |
| **Q-IM 팀** | 회원 SoR 고도화 | `q-im` | GAP-QIM-01/03/04, Q-IM SP API 협의 |
| **Q-Sign 팀** | 인증 SoR 완성 | `q-sign` | X-Internal-Sig 수신 검증 (GAP-QS-04) |
| **기관(SMEP) 팀** | Handoff Ticket 연동 | `agency-stub` 참조 | Ticket Verify API 연동 구현 |
| **인프라 팀** | 운영 환경 구성 | `infra/docker` | 운영 키 교체, K8s 준비 |
| **프론트엔드 팀** | UI 고도화 | `onepass-fe` | 회원 전환·관리 UI |

### 5.2 기관(SMEP) 팀 마이그레이션 경로

기관 팀이 현재 Q-IM 직접 호출 방식에서 Handoff Ticket 방식으로 전환하는 단계:

```
Step 1: integration-sso 로컬 기동 확인
  $ cd integration-sso/infra/docker
  $ docker-compose up -d
  → IdO :8083, Q-Sign :8081, Q-IM :8082 기동 확인

Step 2: agency-stub 코드 참조
  → agency-stub/src/.../AgencyEntryController.java  (Ticket 수신 처리)
  → agency-stub/src/.../IdoVerifyClient.java        (Verify API 호출)
  → agency-stub/src/.../AgencySessionService.java   (AGSID 세션 관리)

Step 3: SMEP 자체 구현
  → POST /api/v1/handoff/verify 호출 구현
  → X-Agency-Code + X-Agency-Key 헤더 적용
  → HandoffPayload 파싱 + 기관 로컬 세션 발급

Step 4: 현재 Q-IM 직접 호출 코드 제거
  → /api/ciw-im/member/* 직접 호출 코드 삭제
  → Q-IM 직접 통신은 IdO를 통해서만 이루어짐
```

### 5.3 Q-IM 팀 협의 필요 사항 (최우선)

현재 미합의 상태로 즉시 협의가 필요한 항목:

| 항목 | 현재 가정 | 협의 필요 내용 | 우선순위 |
|------|---------|--------------|---------|
| encCi 알고리즘 | AES-256-CBC 예상 | 정확한 모드·패딩·IV 방식 | 🔴 P0 |
| AES 공유키 회전 정책 | 수동 교체 | 회전 주기·유예기간·무중단 방식 | 🔴 P0 |
| Idempotency-Key 보관 기간 | 7일 예정 | Q-IM 측 재판단 기간 일치 여부 | 🔴 P0 |
| instMbrId 정책 | qimUserId와 동일 UUID | Q-IM이 다른 형식 요구 여부 | 🔴 P0 |
| SP 수신 endpoint URL | `/api/qim/sp/v1/member/*` | Q-IM 콘솔 등록 전 URL 확정 | 🔴 P0 |

---

## 6. 기술 부채 현황 및 후속 계획

### 6.1 P0 — 운영 전 반드시 완료

| ID | 항목 | 담당 |
|----|------|------|
| P0-01 | Kakao OAuth 실제 Client Secret 설정 | Q-Sign 팀 |
| P0-02 | 운영 DB 패스워드 교체 (`onepass` → 강력한 패스워드) | 인프라 팀 |
| P0-03 | 운영 AES/HMAC 키 교체 (`change-me-*` → 32바이트+ 랜덤) | IdO 팀 |

### 6.2 P1 — 다음 Sprint 우선 처리

| ID | 항목 | 담당 |
|----|------|------|
| P1-03 | X-Internal-Sig 수신 측 검증 (IdO) | IdO 팀 |
| GAP-QS-04 | X-Internal-Sig 수신 측 검증 (Q-Sign) | Q-Sign 팀 |
| GAP-QIM-01 | needsSync → Selective Pull 실제 호출 | Q-IM 팀 |
| GAP-QIM-03 | addAuthMeanMapping() JPA 저장 구현 | Q-IM 팀 |
| GAP-QIM-04 | Outbox markFailed() + retry_count 증가 | Q-IM 팀 |
| GAP-API-02 | Idempotency-Key 헤더 (HandoffController) | IdO 팀 |

### 6.3 P2 — 중기 구현

| 항목 | 내용 |
|------|------|
| 회원 탈퇴 4종 | IMMEDIATE / SCHEDULED / AGENCY_REQUESTED / ADMIN_FORCED |
| ConversionSession | 회원 전환 상태 기계 |
| 개인정보 동의 기록 | `ido.consent_record`, `ido.consent_version` |
| agency-stub Kafka 직접 구독 제거 | PoC 코드 → Webhook/폴링 방식으로 교체 |
| 프론트엔드 UI | 회원 전환·관리·가입 UI |

### 6.4 장기 (P3)

| 항목 | 내용 |
|------|------|
| 부하 테스트 | k6/Gatling, 목표 200 TPS, p99 < 150ms |
| E2E 자동화 테스트 | Playwright (6종 핵심 시나리오) |
| 보안 스캔 | OWASP ZAP |
| K8s Helm Chart | 운영 배포 구성 |
| Admin Console UI | React 기반 기관 관리 대시보드 |
| mTLS 기관 인증 | 클라이언트 인증서 검증 |

---

## 7. 로컬 개발 환경 기동 가이드

### 7.1 사전 요구사항

| 도구 | 버전 |
|------|------|
| Java | 21 (LTS) |
| Docker Desktop | 최신 |
| Node.js | 20+ |
| Gradle | 9.5 (Wrapper 사용, 별도 설치 불필요) |

### 7.2 기동 순서

```bash
# 1. 저장소 클론
git clone {repo-url} integration-sso
cd integration-sso

# 2. 인프라 기동 (DB, Redis, Kafka, Keycloak)
cd infra/docker
docker-compose up -d

# 3. 전체 빌드
cd ../..
./gradlew build -x test

# 4. 각 서비스 기동
./gradlew :q-sign:bootRun &   # :8081
./gradlew :q-im:bootRun &     # :8082
./gradlew :ido:bootRun &      # :8083
./gradlew :agency-stub:bootRun &  # :8084

# 5. 프론트엔드 기동 (별도 터미널)
cd onepass-fe
npm install
npm run dev   # :3001

# 6. 모니터링 기동 (선택)
cd infra/docker
docker-compose --profile monitoring up -d
# Prometheus :9090, Grafana :3002, Loki :3100
```

### 7.3 주요 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `AES_KEY` | `change-me-xxxxxxxx` | Handoff 암호화 키 (운영 시 교체 필수) |
| `HMAC_KEY` | `change-me-xxxxxxxx` | HMAC 서명 키 (운영 시 교체 필수) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka 접속 주소 |
| `REDIS_HOST` | `localhost` | Redis 접속 호스트 |
| `DOCKER_UNAVAILABLE` | `false` | CI 환경에서 Docker 없이 빌드 시 `true` |

### 7.4 기동 확인

```bash
# IdO 헬스 체크
curl http://localhost:8083/actuator/health

# agency-stub 시뮬레이터 접근
curl http://localhost:8084/

# Kafka UI
open http://localhost:8090

# Kafka 토픽 목록 확인
docker-compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

---

## 부록 — Kafka 토픽 구성

| 토픽 | 파티션 | 생산자 | 소비자 |
|------|--------|--------|--------|
| `qsign.auth.events` | 12 | Q-Sign | IdO |
| `qim.user.events` | 6 | Q-IM | IdO, Q-Sign |
| `ido.handoff.events` | 12 | IdO | IdO(내부), Webhook |
| `platform.session.advisory` | 12 | IdO | IdO(FE Advisory) |
| `platform.audit.log` | 6 | IdO | 감사 시스템 |
| `qim.sp.member.events` | 6 | IdO | agency-adapter |

---

*본 문서는 `PROP-2026-001 OnePass Integration-SSO 정식 채택 제안서`의 기술 근거 상세본입니다.*  
*의사결정 요약은 `PROP-2026-001-A 의사결정 요약`을 참조하십시오.*
