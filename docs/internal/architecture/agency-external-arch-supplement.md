# 유관기관 외부망 배치 설계 보완서

**문서 번호**: ARCH-SUPP-001  
**작성일**: 2026-05-08  
**대상 버전**: v1.4.2+  
**관련 문서**: README.md, _archive/2026-05-22/internal/analysis/operational-readiness-analysis-v2.md, _archive/2026-05-22/internal/analysis/eda-master-arch-gap-analysis-v0.8.md  
**작성 배경**: README.md 아키텍처 구성도에서 `agency-stub`이 내부망(`onepass-net`)으로 잘못 표현된 것을 발견, 전체 설계 재검토 및 보완

---

## 1. 문제 요약

### 1.1 발견된 설계 오류

이전 README.md의 아키텍처 구성도는 다음과 같이 표현되어 있었습니다.

```
# ❌ 잘못된 구성도 (수정 전)
                        │ HTTP (내부망)
        ┌───────────────┼───────────────┐
        ▼               ▼               ▼
┌───────────────┐ ┌───────────────┐ ┌──────────────────┐
│  q-sign :8081 │ │  q-im   :8082 │ │agency-stub :8084 │  ← ❌ 내부망!
│  인증 SoR      │ │  식별 SoR      │ │기관 로컬 세션 Stub │
└───────────────┘ └───────────────┘ └──────────────────┘
```

### 1.2 위반된 설계 원칙

| 원칙 | 위반 내용 |
|------|-----------|
| **기관 외부망 원칙** | 모든 유관기관은 외부망에 위치해야 하나, 내부망(동일 Docker 네트워크)으로 배치됨 |
| **Kafka 내부망 격리** | 외부 기관이 내부 Kafka를 직접 구독 — 심각한 보안 위반 |
| **API 경계 명확성** | 기관과 플랫폼 간 통신은 반드시 공개 API(HTTPS)를 통해서만 수행되어야 함 |
| **네트워크 분리** | 내부 토픽(`ido.handoff.events`, `platform.session.advisory`)이 외부에 노출됨 |

### 1.3 영향을 받는 컴포넌트

| 컴포넌트 | 문제 | 심각도 |
|----------|------|--------|
| `agency-stub` Docker 배치 | onepass-net 내부에 포함됨 | 설계 오류 |
| `HandoffEventConsumer.java` | 내부 Kafka(`ido.handoff.events`) 직접 구독 | PoC 편의성 코드 (운영 불가) |
| `HandoffEventConsumer.java` | `platform.session.advisory` 직접 구독 | PoC 편의성 코드 (운영 불가) |
| `application.yml` (agency-stub) | `KAFKA_SERVERS` 환경변수로 내부 Kafka 연결 | PoC 편의성 설정 |
| `returnUrl` 화이트리스트 | `http://localhost:8084` 포함 (기관을 내부로 취급) | 설계 미스 |

---

## 2. 올바른 아키텍처 원칙

### 2.1 망 분리 원칙

```
══════════════════════════════════════════════════════════════
  외부망 (External Network / Internet / 기관 전용망)
══════════════════════════════════════════════════════════════

  ┌─────────────────────────────────────────────────────┐
  │  유관기관 시스템 (기관 A · 기관 B · agency-stub PoC)    │
  │                                                     │
  │  - 자체 DB, 자체 세션 관리                             │
  │  - 내부 Kafka 접근 불가                               │
  │  - OnePass 플랫폼과는 HTTPS API로만 통신               │
  │  - 기관 인증: X-Agency-Code + API Key (또는 mTLS)     │
  └───────────────────────────┬─────────────────────────┘
                              │ HTTPS (공개 API)
══════════════════════════════╪═══════════════════════════════
  내부망 (Internal Network — onepass-net)
══════════════════════════════╪═══════════════════════════════
                              ▼
                  ┌───────────────────────┐
                  │      IdO :8083        │
                  │  (공개 기관 API 엔드포인트)│
                  │  POST /handoff/verify  │
                  │  (X-Agency-Code 검증)  │
                  └───────────┬───────────┘
                              │ (내부망)
                  ┌───────────┼───────────┐
                  ▼           ▼           ▼
             q-sign        q-im       Kafka
             (내부망)       (내부망)    (내부망)
```

### 2.2 기관 연동의 유일한 진입점: IdO Handoff Verify API

```
기관이 사용할 수 있는 IdO API (공개 — HTTPS):
  POST   /api/v1/handoff/verify
  DELETE /api/v1/handoff/{ticketId}     (긴급 revoke 요청, P2)

기관이 접근할 수 없는 것:
  × 내부 Kafka 토픽 (ido.handoff.events, platform.session.advisory)
  × 내부 REST API (/api/v1/fe-session/**, 기타 내부 엔드포인트)
  × 내부 DB (PostgreSQL/MariaDB/Redis)
  × q-sign, q-im 서비스 직접 호출
```

---

## 3. 현재 PoC 구현과 운영 설계 간 차이

### 3.1 Kafka 구독 문제

#### 현재 PoC 구현 (agency-stub)

```java
// ❌ PoC 한정 코드 — 실 운영에서 사용 불가
@KafkaListener(
    topics = "ido.handoff.events",        // 내부 Kafka 직접 구독
    groupId = "agency-stub-consumer-handoff"
)
public void consumeHandoff(...) {
    // REVOKED 수신 → 기관 세션 무효화
}

@KafkaListener(
    topics = "platform.session.advisory", // 내부 Kafka 직접 구독
    groupId = "agency-stub-consumer-advisory"
)
public void consumeAdvisory(...) {
    // qimUserId 기준 일괄 무효화
}
```

**문제**: 외부 기관이 내부 Kafka 브로커에 직접 접근 → 내부망 전체 노출 위험

#### 운영 설계 대안 (3가지 옵션)

| 옵션 | 메커니즘 | 장점 | 단점 | 권장 여부 |
|------|----------|------|------|-----------|
| **A. Webhook Push** | IdO가 REVOKED 이벤트 발생 시 기관의 Webhook URL로 HTTP POST | 실시간, 표준적 | 기관 Webhook 서버 필요, 재전송 보장 필요 | ✅ **권장 (운영)** |
| **B. Polling API** | 기관이 주기적으로 IdO의 상태 확인 API 호출 | 단순, 기관 부담 적음 | 지연 발생 (polling 주기), 트래픽 증가 | ✅ **권장 (PoC 대안)** |
| **C. SSE/WebSocket** | IdO가 기관에 Server-Sent Events 스트림 제공 | 실시간 | 연결 유지 복잡성 | △ 중대형 기관만 |

### 3.2 세션 무효화 처리 흐름 (운영 설계)

#### 옵션 A: Webhook Push (권장)

```
┌─────────────────┐    Kafka Consumer     ┌─────────────────────┐
│  Internal Kafka  │ ──────────────────►  │   IdO :8083         │
│  ido.handoff    │   (내부 처리)           │   WebhookDispatcher │
│  .events        │                       │   (내부 컴포넌트)      │
└─────────────────┘                       └──────────┬──────────┘
                                                     │ HTTPS POST
                                                     │ (재시도, 지수 백오프)
                                          ┌──────────▼──────────┐
                                          │  기관 Webhook Server │
                                          │  POST /onepass/hook  │
                                          │  {event: REVOKED,    │
                                          │   ticketId: ...,     │
                                          │   agencyCode: ...}   │
                                          └─────────────────────┘

기관 메타(agency_meta.callback_whitelist)에 Webhook URL 사전 등록 필수
HMAC-SHA256 서명으로 이벤트 진위 검증
```

#### 옵션 B: Polling API (PoC 임시 대안)

```
기관이 주기적으로 호출:
  GET /api/v1/agency/events?since={timestamp}&agencyCode={code}
  Authorization: X-Agency-Key {apiKey}

응답:
  {
    "events": [
      {"type": "TICKET_REVOKED", "ticketId": "...", "occurredAt": "..."},
      {"type": "USER_ADVISORY",  "qimUserId": "...", "occurredAt": "..."}
    ]
  }
```

### 3.3 agency-stub Docker 배치 수정 방향

#### 현재 PoC (잘못된 구성)
```yaml
# ❌ 수정 전: agency-stub이 onepass-net 내부에 포함됨
agency-stub:
  networks:
    onepass-net:                     # ← 내부망 직접 접근 가능
      ipv4_address: 172.20.0.25
  environment:
    KAFKA_SERVERS: kafka:29092       # ← 내부 Kafka 직접 연결
```

#### 수정 방향 (PoC 격리 구성)
```yaml
# ✅ 수정 후: agency-stub은 독립 네트워크 또는 host 모드
agency-stub:
  networks:
    agency-net:                      # ← 별도 네트워크 (내부망과 분리)
  environment:
    IDO_BASE_URL: http://host.docker.internal:8083   # 또는 공개 IP
    # KAFKA_SERVERS: 제거 (직접 구독 불가)

networks:
  agency-net:
    driver: bridge
    # onepass-net과 연결 없음
```

> **PoC 현실적 타협**: 로컬 개발에서는 `localhost:8083`으로 IdO 호출.  
> docker-compose 상에서는 `--profile agency` 별도 프로파일로 분리.

---

## 4. 코드 레벨 분석

### 4.1 AgencyEntryController — 설계 적합성

```java
// ✅ 올바른 패턴: IdO Verify API를 HTTP 호출로 처리
private HandoffPayload callIdoVerify(String ticketId, String correlationId) {
    // RestTemplate으로 IdO HTTPS API 호출
    // 현재는 Stub 반환 (TODO: 실제 구현 필요)
}
```

`AgencyEntryController`의 흐름은 올바릅니다:
- 기관이 ticketId를 받음 → IdO `/handoff/verify` 호출 → AGSID 발급
- 이 흐름은 실 운영 설계와 완전히 일치

**현재 미구현 사항 (P1)**:
- `callIdoVerify` 내부에서 실제 RestTemplate HTTP 호출 대신 Stub 반환 중
- API Key 헤더 (`X-Agency-Key`) 전송 미구현
- Ticket 서명 검증 미구현

### 4.2 HandoffEventConsumer — 운영 불가 코드 명시

```java
/**
 * ⚠️ PoC 전용 — 실 운영에서는 사용 불가
 *
 * 이 클래스는 PoC 시뮬레이션을 위해 내부 Kafka를 직접 구독합니다.
 * 실 운영에서 기관은 내부 Kafka에 직접 접근할 수 없습니다.
 *
 * 운영 대체 방안:
 *   1. Webhook: IdO → 기관 Webhook URL (POST)
 *   2. Polling: 기관 → IdO 이벤트 조회 API (GET)
 *
 * 설계서 참조: docs/agency-external-arch-supplement.md §3.2
 */
@Component
public class HandoffEventConsumer {
    // ... 현재 구현은 PoC 시뮬레이션 목적
}
```

### 4.3 application.yml 수정 사항

```yaml
# PoC에서 agency-stub/src/main/resources/application.yml에
# 다음 주석 추가 필요:

spring:
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
    # ⚠️ PoC ONLY: 실 운영에서 외부 기관은 내부 Kafka에 연결 불가
    # 운영 시 이 설정은 제거되며, 기관 세션 무효화는 Webhook/Polling으로 처리
```

---

## 5. 운영 설계 목표 (To-Be)

### 5.1 기관 연동 인터페이스 전체 목록

| API | Method | 경로 | 인증 | 설명 |
|-----|--------|------|------|------|
| Handoff Verify | POST | `/api/v1/handoff/verify` | X-Agency-Code + X-Agency-Key | 티켓 1회 소비, 페이로드 반환 |
| Handoff Revoke (선택) | DELETE | `/api/v1/handoff/{ticketId}` | X-Agency-Code + X-Agency-Key | 기관측 긴급 revoke |
| 이벤트 폴링 (P1) | GET | `/api/v1/agency/events` | X-Agency-Key | Webhook 대안 |
| 헬스체크 | GET | `/api/v1/agency/ping` | X-Agency-Key | 연결 확인 |

### 5.2 Webhook 디스패처 설계 (P1 구현 대상)

```
IdO 내부 컴포넌트: WebhookDispatcherService

트리거 조건:
  1. HandoffTicket REVOKED (qim_suspend, compromise, policy_rollback)
  2. SessionAdvisoryEvent MANDATORY_SECURITY (qimUserId 기준)

처리 흐름:
  1. agency_meta에서 callback_whitelist[0] (Webhook URL) 조회
  2. HMAC-SHA256 서명 생성 (X-OnePass-Signature 헤더)
  3. HTTP POST with retry (Resilience4j retry, 최대 3회)
  4. 실패 시 DLQ 발행 (platform.webhook.dlq)
  5. webhook_dispatch_log 테이블에 감사 기록

보안:
  - 기관 Webhook URL은 agency_meta.callback_whitelist에 사전 등록
  - HTTPS only (운영 강제)
  - 서명 검증은 기관 측에서 수행 (문서 제공)
```

### 5.3 데이터 흐름도 (운영 목표)

```
[인증 완료]
    │
    ▼
Q-Sign → outbox → Kafka(qsign.auth.events)
    │                       │
    │                       ▼
    │              IdO Kafka Consumer
    │                       │
    │                       ▼
    │              HandoffTicket 발급
    │                       │
    │              [기관이 ticketId 수신]
    │                       │
    │               기관 → IdO Verify API (HTTPS)
    │                       │
    │               IdO → HandoffPayload 반환
    │                       │
    │               기관 → 로컬 세션(AGSID) 발급
    │
[보안 이벤트 발생]
    │
    ▼
IdO → Kafka(ido.handoff.events: REVOKED)  ← 내부
    │
    ▼
IdO WebhookDispatcher → 기관 Webhook URL (HTTPS) ← 외부
    │
    ▼
기관 → 로컬 세션 무효화 (자율 처리)
```

---

## 6. PoC 구현 현황 vs 운영 설계 갭

> **최종 업데이트**: 2026-05-08 — v1.5.0 P1 항목 구현 완료

| 항목 | 현재 상태 | 운영 목표 | 우선순위 | 구현 위치 |
|------|-----------|-----------|---------|-----------|
| 기관 → IdO Verify 호출 | ✅ 구조 완성 (Stub 반환) | RestTemplate 실제 호출 | P1 | `AgencyEntryController.callIdoVerify()` |
| 기관 API Key 검증 | ⚠️ 미구현 (TODO) | X-Agency-Key 헤더 검증 | P1 | `HandoffController` 인터셉터 |
| Ticket 서명 검증 (기관 측) | ⚠️ 미구현 | HMAC-SHA256 검증 | P1 | `AgencyEntryController` |
| agency-stub Kafka 직접 구독 | ⚠️ PoC 편의 코드 | 운영 시 제거 | P2 | `HandoffEventConsumer.java` |
| **Webhook 디스패처** | ✅ **구현 완료** (v1.5.0) | IdO 내부 컴포넌트 | P1 | `ido/webhook/` 패키지 |
| **Webhook Outbox Relay** | ✅ **구현 완료** (v1.5.0) | 지수 백오프 재시도 | P1 | `WebhookDispatchOutboxRelay` |
| **Handoff 이벤트 → Webhook** | ✅ **구현 완료** (v1.5.0) | Kafka→HTTPS 브리지 | P1 | `HandoffEventConsumer` |
| **Redis Pre-warming** | ✅ **구현 완료** (v1.5.0) | 60k 부하 흡수 | P1 | `AuthResultCacheService` |
| **감사 로그 (Audit)** | ✅ **구현 완료** (v1.5.0) | DB + Kafka 이중 기록 | P1 | `AuditLogPublisher` |
| **DB 마이그레이션 V7** | ✅ **구현 완료** (v1.5.0) | 4개 신규 테이블 | P1 | `V7__add_webhook_and_audit.sql` |
| 이벤트 폴링 API | ❌ 미구현 | GET `/api/v1/agency/events` | P2 | `ido/api/AgencyEventController` |
| agency-stub Docker 격리 | ❌ onepass-net 포함 | 별도 네트워크 또는 host | P2 | `docker-compose.yml` |
| mTLS 기관 인증 | ❌ 미구현 (API Key 대체) | 클라이언트 인증서 검증 | P3 | Nginx/Gateway 레벨 |

---

## 7. v1.5.0 구현 상세 — 유관기관 외부 연동 인프라

> v1.5.0(PR #16)에서 구현된 외부 기관 Webhook 연동 전체 스택을 기록한다.

### 7.1 핵심 컴포넌트 목록

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `WebhookDispatcherService` | `ido.webhook` | Outbox 적재 (HandoffEvent / MemberLookup / MemberWithdrawn) |
| `WebhookDispatchOutboxRelay` | `ido.webhook` | 500ms 폴링 → HTTPS POST → 지수 백오프 재시도 |
| `HandoffEventConsumer` | `ido.kafka` | `ido.handoff.events` 수신 → WebhookDispatcherService 호출 |
| `QsignAuthEventConsumer` | `ido.kafka` | `qsign.auth.events` 수신 → Redis Pre-warming + Advisory |
| `AuthResultCacheService` | `ido.burst` | Redis `ido:auth_result:{correlationId}` TTL 300s |
| `SessionAdvisoryPublisher` | `ido.burst` | `platform.session.advisory` 발행 + Outbox 폴백 |
| `AuditLogPublisher` | `ido.audit` | DB 기록 후 `platform.audit.log` Kafka 비동기 발행 |
| `AsyncConfig` | `ido.config` | `auditExecutor` (4/16/10000) / `webhookExecutor` (4/20/5000) |

### 7.2 DB 스키마 변경 (V7)

```
V7__add_webhook_and_audit.sql 추가 테이블:
  ido.agency_webhook_config    — 기관별 endpoint / HMAC 시크릿 / 재시도 정책
  ido.webhook_dispatch_outbox  — at-least-once HTTPS 발송 Outbox
  ido.audit_log                — 플랫폼 감사 로그 (2년 보존)
  ido.member_lookup_request    — CI/DN 해시 기반 회원 조회 요청 추적

추가 컬럼:
  ido.agency_meta.webhook_enabled   BOOLEAN DEFAULT FALSE
  ido.agency_meta.webhook_endpoint  VARCHAR(500)
```

### 7.3 Kafka 토픽 확장 (60,000명 대응)

```
qsign.auth.events      파티션 12 / RF 3 / ISR 2  (신규)
qim.sp.member.events   파티션 6  / RF 3 / ISR 2  (신규)
ido.handoff.events     파티션 12 / RF 3 / ISR 2  (6→12 확장)
platform.session.advisory 파티션 12 / RF 3 / ISR 2 (6→12 확장)
platform.audit.log     파티션 6  / RF 3 / ISR 2  (기존 유지)

컨슈머 concurrency:
  qsign.auth.events    → 6 스레드 (Pre-warming 병렬도)
  ido.handoff.events   → 6 스레드 (Webhook 큐잉 병렬도)
  qim.user.events      → 3 스레드 (캐시 갱신)
  platform.advisory    → 3 스레드 (FE 세션 처리)
  qim.sp.member.events → 2 스레드 (SP 회원 이벤트)
```

### 7.4 완성된 이벤트 흐름도

```
[60,000명 동시 인증]
        │
        ▼
Q-Sign AUTH_COMPLETED
  → Kafka(qsign.auth.events)
        │
        ▼
IdO QsignAuthEventConsumer [concurrency=6]
  → AuthResultCacheService.preWarm()
      └─► Redis TTL 300s  ← DB 폭발 차단 (30MB / 60k entries)
        │
        ▼
사용자 → 기관 콜백 페이지 이동
        │
        ▼
IdO HandoffServiceImpl.issue()
  → Kafka(ido.handoff.events: HANDOFF_ISSUED)
        │
        ▼
IdO HandoffEventConsumer [concurrency=6]
  → WebhookDispatcherService.enqueueForHandoffEvent()
      └─► INSERT webhook_dispatch_outbox (PENDING)
            │
            ▼ (500ms 폴링)
        WebhookDispatchOutboxRelay
          └─► HTTPS POST → 기관 Webhook URL (외부망)
                └─► 2xx: DISPATCHED
                └─► 실패: 지수 백오프 재시도 (2s / 4s / 8s → FAILED)

[AUTH_LOCKED 보안 이벤트]
        │
        ▼
IdO QsignAuthEventConsumer
  → authResultCacheService.invalidate()
  → SessionAdvisoryPublisher.publishAuthLocked()
      └─► Kafka(platform.session.advisory: MANDATORY_SECURITY_TERMINATE)
            └─► FeAdvisoryConsumer → FE 세션 강제 무효화
```

---

## 8. 즉시 적용 사항 (코드 주석 보완 — 완료)

v1.5.0에서 다음 코드 주석 보완이 완료되었습니다:

1. `HandoffEventConsumer.java` (IdO) — 기관 Kafka 직접 접근 불가 이유 및 Webhook 브리지 설계 Javadoc 추가 ✅
2. `QimSpMemberEventHandler.java` — `notifyAgencies()` Phase 2 TODO → WebhookDispatcherService 완전 구현 ✅
3. `QsignAuthEventConsumer.java` — Redis Pre-warming 60k 시나리오 설계 주석 추가 ✅

**미완료 (다음 PR 대상)**:
- `agency-stub/HandoffEventConsumer.java` — PoC 한정 Javadoc (클래스 레벨) 추가
- `agency-stub/application.yml` — Kafka 설정 블록 경고 주석

---

## 9. 결론

### 핵심 원칙 재확인

> **유관기관은 항상 외부망에 존재한다.**  
> 기관과 OnePass 플랫폼 간의 유일한 통신 채널은 **IdO 공개 API (HTTPS)** 및 **Webhook** 이다.  
> 내부 Kafka, 내부 DB, 내부 서비스에 대한 기관의 직접 접근은 **절대 허용하지 않는다.**

### agency-stub의 올바른 역할 정의

`agency-stub`은 **PoC 시뮬레이터**입니다:
- 실제 유관기관이 구현해야 할 `IdO Verify API 호출` 흐름을 시뮬레이션
- 기관 로컬 세션(AGSID) 관리 로직 시연
- **Kafka 직접 구독은 PoC 편의 코드 — 운영에서는 Webhook으로 대체 (v1.5.0 구현 완료)**

### 남은 단계 (v1.6.0 목표)

1. **P1**: `AgencyEntryController.callIdoVerify()` 실제 RestTemplate 구현
2. **P1**: 기관 API Key 검증 인터셉터 (`X-Agency-Key` 헤더)
3. **P2**: 이벤트 폴링 API (`GET /api/v1/agency/events`) 구현
4. **P2**: agency-stub docker-compose 격리 (별도 네트워크 또는 host 모드)
5. **P2**: `returnUrl` 화이트리스트에서 `http://localhost:8084` 제거
6. **P3**: mTLS 기관 인증 (Nginx/Gateway 레벨)

---

*초기 작성: genspark_ai_developer / 트리거: README 아키텍처 다이어그램 검토*  
*v1.5.0 업데이트: 2026-05-08 — Webhook 연동 인프라 전체 구현 완료*  
*관련 PR: genspark_ai_developer → main*
