# Outbox 패턴 완전 해설
## — 비개발자도 이해할 수 있는 데이터 흐름 안내서 —

> **대상 독자**: 기획자, PM, 운영팀, 도입 기관 담당자, 경영진  
> **목적**: 원패스 통합인증 플랫폼에서 "Outbox 패턴"이 무엇이고, 왜 쓰며, 데이터가 어디서 어디로 어떻게 흐르는지 이해한다.

---

## 목차

1. [왜 이 문서를 읽어야 하는가](#1-왜-이-문서를-읽어야-하는가)
2. [Outbox 패턴이란 무엇인가](#2-outbox-패턴이란-무엇인가)
3. [비유로 이해하기: 우체통 시스템](#3-비유로-이해하기-우체통-시스템)
4. [우리 시스템에서 어떻게 작동하는가](#4-우리-시스템에서-어떻게-작동하는가)
5. [5가지 Outbox 테이블 한눈에 보기](#5-5가지-outbox-테이블-한눈에-보기)
6. [Kafka란 무엇인가](#6-kafka란-무엇인가)
7. [전체 데이터 흐름 시나리오](#7-전체-데이터-흐름-시나리오)
8. [Outbox가 없었다면 어떤 문제가 생기는가](#8-outbox가-없었다면-어떤-문제가-생기는가)
9. [장애가 발생하면 어떻게 되는가](#9-장애가-발생하면-어떻게-되는가)
10. [운영 관점: 자주 묻는 질문](#10-운영-관점-자주-묻는-질문)

---

## 1. 왜 이 문서를 읽어야 하는가

원패스 통합인증 플랫폼은 **인증·회원·Handoff·기관 알림** 등 여러 기능이 서로 연결되어 있습니다.  
예를 들어, 사용자가 로그인에 성공하면:

- Q-Sign(인증 시스템)이 인증 완료를 기록해야 하고  
- IdO(통합인증 허브)가 그 결과를 받아 Handoff 티켓을 준비해야 하고  
- 기관(예: 민원24, 복지포털)이 "이 사람이 인증했다"는 사실을 알아야 합니다  

이 세 가지가 **동시에, 완벽하게, 빠짐없이** 이루어져야 합니다.  
이를 보장하는 핵심 기술이 바로 **Outbox 패턴**입니다.

---

## 2. Outbox 패턴이란 무엇인가

### 한 문장 정의

> **"중요한 사건이 발생했을 때, 먼저 데이터베이스에 '해야 할 일' 목록을 적어두고, 별도의 담당자가 그 목록을 보고 메시지를 전달하는 방식"**

### 핵심 원칙

| 원칙 | 설명 |
|------|------|
| **원자성(Atomicity)** | "인증 결과 저장"과 "알림 목록 등록"은 하나의 작업으로 처리됩니다. 둘 중 하나만 되거나 안 됩니다. |
| **적어도 1번 전달(At-least-once)** | 메시지는 반드시 1번 이상 전달됩니다. 간혹 중복 전달될 수 있으나, 받는 쪽에서 중복을 걸러냅니다. |
| **복구 가능성** | 네트워크 장애, 서버 재시작 등이 발생해도 목록에서 미처리 항목을 찾아 재전송합니다. |

---

## 3. 비유로 이해하기: 우체통 시스템

### 📮 우체통 비유

```
┌─────────────────────────────────────────────────────┐
│                                                     │
│   편지 쓰는 사람         우체통          배달부        │
│   (서비스 로직)         (Outbox 테이블)   (Relay)     │
│                                                     │
│   ┌─────────┐   편지 투함   ┌──────┐   편지 배달     │
│   │ 은행원  │ ──────────►  │ 📮   │ ──────────►    │
│   │(인증서비│              │우체통│               받는 사람들│
│   │  스)   │              │      │               (Kafka, 기관)│
│   └─────────┘              └──────┘                 │
│                                                     │
│   "편지 투함"과 "업무 처리"는                          │
│   같은 시간에 이루어집니다.                             │
│   배달은 나중에 됩니다.                                │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 우체통 없이 직접 전화하는 경우의 문제점

```
상황: 은행 직원이 고객 계좌를 개설하고, 동시에 고객에게 전화로 알려야 하는 경우

[문제 시나리오 1] - 직접 전화
  1. 직원: "계좌 개설 완료!" (DB 저장 성공)
  2. 직원: "고객님께 전화 중..." (전화 시도)
  3. 고객: 전화를 안 받음 (전송 실패)
  ⚠️ 결과: 계좌는 개설됐지만 고객은 모름 → 데이터 불일치!

[Outbox 사용 시]
  1. 직원: "계좌 개설 완료 + 알림 목록에 기록" (동시에 DB 저장)
  2. 배달부: "알림 목록 확인 → 고객에게 편지 발송 시도"
  3. 실패 시: "나중에 다시 시도"
  ✅ 결과: 계좌 개설과 알림은 항상 함께 처리됨!
```

---

## 4. 우리 시스템에서 어떻게 작동하는가

### 시스템 구성도

```
원패스 통합인증 플랫폼 — 전체 데이터 흐름

사용자
  │
  │ ① 인증 시도 (공동인증서, 간편인증 등)
  ▼
┌─────────────────────┐
│   Q-Sign            │  ← 인증 전문 서버
│   (인증 처리)        │
│                     │
│  인증 완료 후:       │
│  1. auth_result 저장 │
│  2. qsign.outbox   │ ← "해야 할 일" 목록에 기록
│     에 기록          │
└─────────────────────┘
         │
         │ ② Outbox Relay가 0.5초마다 읽어서 Kafka로 전송
         ▼
┌─────────────────────┐
│   Kafka             │  ← 고속 메시지 버스
│   (메시지 전달 버스)  │
│                     │
│  토픽 목록:          │
│  · qsign.auth.events│
│  · qim.user.events  │
│  · ido.handoff.events│
│  · platform.audit.log│
└─────────────────────┘
         │
         │ ③ 각 서비스가 Kafka를 구독하여 처리
         ├──────────────────────────────────────────┐
         ▼                                          ▼
┌─────────────────────┐                  ┌─────────────────────┐
│   IdO               │                  │   Q-IM              │
│   (통합인증 허브)     │                  │   (회원 관리)        │
│                     │                  │                     │
│  인증 결과 수신 후:   │                  │  회원 정보 변경 후:  │
│  · Redis 캐시 저장   │                  │  · qim.outbox에 기록 │
│  · Handoff 티켓 준비 │                  │  · Kafka로 변경 전파 │
│                     │                  │  · 68개 기관에 프로  │
│  webhook_dispatch_  │                  │    비저닝            │
│  outbox에 기록       │                  └─────────────────────┘
└─────────────────────┘
         │
         │ ④ Webhook Relay가 기관에 HTTPS 발송
         ▼
┌─────────────────────┐
│   연계 기관들        │
│   (민원24, 복지포털  │
│    등 68개 기관)     │
│                     │
│  수신하는 알림 유형:  │
│  · HANDOFF_ISSUED   │  ← 인증 완료, 티켓 발급됨
│  · HANDOFF_REVOKED  │  ← 티켓 취소됨 (보안)
│  · MEMBER_WITHDRAWN │  ← 회원 탈퇴
│  · USER_LOGOUT      │  ← 로그아웃
└─────────────────────┘
```

---

## 5. 5가지 Outbox 테이블 한눈에 보기

우리 시스템에는 목적별로 **5개의 Outbox 테이블**이 있습니다.

### 📋 Outbox 테이블 요약표

| 테이블명 | 어디에? | 누가 쓰는가 | 누가 읽는가 | 전달 목적지 | 사용 케이스 |
|---------|--------|-----------|-----------|-----------|-----------|
| `ido.outbox` | IdO DB (PostgreSQL) | KeycloakOidcService, NonOidcAuthService | IdoOutboxRelay | Kafka `qsign.auth.events` | Keycloak/비OIDC 인증 완료 알림 |
| `ido.webhook_dispatch_outbox` | IdO DB | WebhookDispatcherService | WebhookDispatchOutboxRelay | 기관 HTTPS endpoint | 기관에 Handoff/로그아웃/탈퇴 알림 |
| `ido.provisioning_outbox` | IdO DB | ProvisioningServiceImpl | ProvisioningOutboxRelay | 기관 HTTPS endpoint | 신규 회원 정보를 68개 기관에 자동 등록 |
| `q-im.outbox` | Q-IM DB (MariaDB) | Q-IM 각종 서비스 | OutboxServiceImpl (Relay) | Kafka `qim.user.events` | 회원 생성/변경/탈퇴/정지 알림 |
| `q-sign.outbox` | Q-Sign DB (PostgreSQL) | Q-Sign AuthServiceImpl | OutboxRelay | Kafka `qsign.auth.events` | Q-Sign 인증 완료/실패/잠금 알림 |

---

### 각 테이블 상세 설명

#### 1️⃣ `ido.outbox` — IdO 인증 이벤트 발행

```
[언제 쓰이는가]
사용자가 Keycloak(카카오·네이버 등 소셜 로그인) 또는 
비OIDC(공동인증서, PASS 앱) 방식으로 인증에 성공했을 때

[데이터 흐름]
Keycloak 콜백 수신
  → [같은 트랜잭션 안에서]
     ① auth_result 테이블에 인증 결과 저장
     ② ido.outbox 테이블에 "인증 완료 이벤트 발행 예정" 기록
  → [0.5초 후 자동으로]
     IdoOutboxRelay가 읽어서 Kafka qsign.auth.events로 전송

[Outbox 레코드의 상태 변화]
  PENDING → PUBLISHED (정상 발행)
  PENDING → FAILED (3회 실패 시)
```

#### 2️⃣ `ido.webhook_dispatch_outbox` — 기관 Webhook 발송

```
[언제 쓰이는가]
- 사용자 인증 후 Handoff 티켓이 발급됐을 때 → 기관에 즉시 알림
- 사용자가 로그아웃했을 때 → 연계된 모든 기관에 알림
- 사용자가 탈퇴했을 때 → 연계된 모든 기관에 알림
- Handoff 티켓이 보안상 취소됐을 때 → 해당 기관에 긴급 알림

[데이터 흐름]
HandoffEventConsumer(Kafka 수신)
  → WebhookDispatcherService.enqueueForHandoffEvent()
     ① 기관 Webhook 설정 조회 (어떤 기관에 어떤 이벤트 보낼지 필터링)
     ② webhook_dispatch_outbox에 각 기관별로 1건씩 INSERT
  → [0.5초 후 자동으로]
     WebhookDispatchOutboxRelay가 읽어서 HTTPS POST

[지수 백오프: 실패 시 재시도 간격]
  1회 실패 → 2초 후 재시도
  2회 실패 → 4초 후 재시도
  3회 실패 → FAILED (운영팀 알림)
```

#### 3️⃣ `ido.provisioning_outbox` — 기관 회원 자동 프로비저닝

```
[언제 쓰이는가]
신규 사용자가 원패스에 가입했을 때, 해당 사용자 정보를 
연계된 68개 기관 모두에 자동으로 등록(프로비저닝)해야 할 때

[특이점]
- 기관이 68개이므로, 사용자 1명 가입 시 최대 68건의 Outbox 레코드 생성
- 각 기관마다 독립적으로 재시도 (A기관 실패가 B기관에 영향 없음)
- 백오프: 1분 → 5분 → 30분 (Webhook보다 느긋하게)
- 최대 3회 실패 시 DEAD_LETTER로 전환 → 운영팀 수동 처리

[상태 이름의 차이]
  다른 Outbox: PENDING → PUBLISHED/FAILED
  이 Outbox:   PENDING → COMPLETED/DEAD_LETTER (HTTP 발송이라 다른 이름 사용)
```

#### 4️⃣ `q-im.outbox` — 회원 정보 변경 이벤트

```
[언제 쓰이는가]
Q-IM(회원 관리 시스템)에서 사용자 정보가 바뀔 때:
- 회원 가입 (USER_REGISTERED)
- 정보 변경 (USER_UPDATED)
- 탈퇴 (USER_WITHDRAWN)  
- 계정 정지 (USER_SUSPENDED)
- 계정 병합 (USER_MERGED)

[특별 기능: 스냅샷 발행]
이벤트가 10개 쌓일 때마다 자동으로 사용자 전체 정보의 
"스냅샷"을 별도 Kafka 토픽(qim.user.snapshot)에 발행.
새로 연계되는 시스템이 과거 이벤트를 일일이 재생하지 않고
최신 상태를 즉시 받을 수 있도록 함.

[기술적 특이점]
- Q-IM은 MariaDB 사용 (다른 서비스는 PostgreSQL)
- JPA(자바 ORM 프레임워크) 사용으로 더 편리한 객체 관리
- 실패 시 최대 5회 재시도 (다른 Outbox보다 더 많이)
```

#### 5️⃣ `q-sign.outbox` — 인증 이벤트 (표준 Q-Sign)

```
[언제 쓰이는가]
Q-Sign이 직접 인증 처리한 경우 (공동인증서, PASS 등):
- 인증 성공 (AUTH_COMPLETED)
- 인증 실패 (AUTH_FAILED)
- 계정 잠금 (AUTH_LOCKED: 5회 오류 등)

[파티션 키: identifierHash]
동일한 사용자의 이벤트는 항상 Kafka의 같은 파티션으로 전송됨.
→ 인증 이벤트 순서 보장 (먼저 발생한 것이 먼저 처리됨)
```

---

## 6. Kafka란 무엇인가

### 📮 Kafka = 초고속 우편 분류 센터

```
일반 메시지 전달 (직접 전화):
  발신자 → [직접 연결] → 수신자
  단점: 수신자가 바쁘면 기다려야 함, 장애 시 메시지 유실

Kafka를 쓴 메시지 전달 (우편 분류 센터):
  발신자 → [투함] → Kafka → [분류] → 수신자1
                                  → 수신자2
                                  → 수신자3
  장점: 발신자는 투함 후 바로 다음 일 처리 가능
        수신자가 느려도 메시지 유실 없음
        새 수신자 추가가 쉬움
```

### 주요 Kafka 토픽(채널) 목록

| 토픽 이름 | 비유 | 발행자 | 구독자 | 메시지 보관 |
|----------|-----|-------|-------|-----------|
| `qsign.auth.events` | "인증 완료 우편함" | Q-Sign Outbox Relay, IdO Keycloak | IdO QsignAuthEventConsumer | 1년 |
| `ido.handoff.events` | "티켓 발급 우편함" | HandoffServiceImpl | HandoffEventConsumer | 1년 |
| `qim.user.events` | "회원 변경 우편함" | Q-IM Outbox Relay | Q-Sign, IdO 등 | 영구(Compact) |
| `qim.user.snapshot` | "회원 현재 상태 요약함" | Q-IM SnapshotService | 신규 연계 시스템 | 영구(Compact) |
| `platform.audit.log` | "감사 기록함" | 모든 서비스 | AuditLogConsumer | 2년 |
| `platform.session.advisory` | "긴급 세션 종료 알림함" | SessionAdvisoryPublisher | FE 세션 Consumer | 24시간 |

---

## 7. 전체 데이터 흐름 시나리오

### 시나리오 1: 사용자 인증부터 기관 알림까지

```
[홍길동씨가 민원24에서 공동인증서로 로그인하는 과정]

Step 1. 홍길동 → 공동인증서 인증 시도
         │
         ▼
Step 2. Q-Sign: 인증 처리
         ├─ qsign.auth_result에 "인증 성공" 저장 (DB 트랜잭션 시작)
         └─ qsign.outbox에 "AUTH_COMPLETED 이벤트 발행 예정" 기록 (같은 트랜잭션 끝)
         
         ↓ (트랜잭션 커밋 완료)
         ↓ 0.5초 후...
         
Step 3. Q-Sign OutboxRelay: qsign.outbox에서 읽어서
         └─ Kafka "qsign.auth.events" 토픽에 전송
            → qsign.outbox 상태: PENDING → PUBLISHED

         ↓ (Kafka 수신 거의 즉시)

Step 4. IdO QsignAuthEventConsumer: Kafka 수신
         ├─ 중복 확인 (이미 처리한 이벤트인지)
         ├─ Redis 캐시에 인증 결과 저장 (Handoff 준비)
         └─ 감사 로그 기록

Step 5. 홍길동 브라우저: 민원24로 이동하며 Handoff 요청

Step 6. IdO HandoffServiceImpl: 
         ├─ Redis에서 인증 결과 즉시 조회 (DB 조회 없음, 초고속!)
         ├─ Handoff 티켓 생성 + DB 저장 (트랜잭션 시작)
         └─ Kafka "ido.handoff.events" 직접 발행 (트랜잭션 끝)
         
         ↓ (Kafka 수신 거의 즉시)

Step 7. IdO HandoffEventConsumer: Kafka 수신
         └─ WebhookDispatcherService.enqueueForHandoffEvent()
            ├─ 민원24 Webhook 설정 확인
            └─ webhook_dispatch_outbox에 기록 (트랜잭션)
            
         ↓ 0.5초 후...

Step 8. WebhookDispatchOutboxRelay:
         ├─ webhook_dispatch_outbox에서 읽기
         ├─ HMAC-SHA256 서명 생성 (위변조 방지)
         └─ 민원24 endpoint에 HTTPS POST
            → "홍길동씨가 ticketId=xxx로 인증했습니다"
            → webhook_dispatch_outbox 상태: PENDING → DISPATCHED

Step 9. 민원24: Webhook 수신
         ├─ HMAC 서명 검증
         └─ ticketId로 IdO에 Handoff 검증 요청
            → 홍길동씨 최종 로그인 완료! ✅
```

### 시나리오 2: 회원 탈퇴 시 전체 기관 알림

```
[홍길동씨가 원패스 회원 탈퇴하는 과정]

Step 1. 홍길동 → 탈퇴 요청

Step 2. Q-IM UserService: 
         ├─ qim_user 테이블 상태 = WITHDRAWN (트랜잭션 시작)
         └─ qim.outbox에 "USER_WITHDRAWN 이벤트" 기록 (같은 트랜잭션 끝)
         
         ↓ 0.5초 후...
         
Step 3. Q-IM OutboxServiceImpl:
         └─ Kafka "qim.user.events" 발행
         
         ↓ (동시에 여러 수신자가 각자 처리)

Step 4A. Q-Sign QimUserEventConsumer:
          └─ 홍길동 인증 잠금 처리 (더 이상 인증 불가)
          
Step 4B. IdO QimEventConsumer:
          └─ WebhookDispatcherService.enqueueForMemberWithdrawn()
             ├─ 연계된 모든 기관(최대 68개)에 대해
             └─ webhook_dispatch_outbox에 기록 (68건 INSERT)
             
         ↓ 30초 이내...

Step 5. ProvisioningOutboxRelay:
         └─ 각 기관에 HTTPS POST: "회원 탈퇴됨"
            → 각 기관의 홍길동 계정 비활성화
```

---

## 8. Outbox가 없었다면 어떤 문제가 생기는가

### ❌ 문제 시나리오: 직접 Kafka 발행 방식

```
[코드상 잘못된 방식]

1. DB에 인증 결과 저장        ← (트랜잭션 완료)
2. Kafka에 이벤트 직접 발행   ← 여기서 서버 재시작! 💥

결과:
  - DB: 인증 성공 기록 있음 ✅
  - Kafka: 이벤트 없음 ❌
  - IdO: 인증 완료 사실 모름 → Handoff 불가
  - 기관: 사용자 로그인 불가 → 민원인 불편
```

### ✅ Outbox 패턴으로 해결

```
[올바른 방식]

1. DB에 인증 결과 저장         ─┐
2. DB의 Outbox에 이벤트 기록   ─┘ (같은 트랜잭션, 둘 다 성공하거나 둘 다 실패)

↓ 서버 재시작 발생해도...

3. 재시작 후 OutboxRelay가 Outbox 확인
4. PENDING 상태 레코드 발견 → Kafka 재전송

결과:
  - DB: 인증 성공 기록 있음 ✅
  - Kafka: 재시작 후 이벤트 전송 완료 ✅
  - 모든 서비스 정상 처리 ✅
```

---

## 9. 장애가 발생하면 어떻게 되는가

### 장애 유형별 대응

#### 🔴 Kafka 브로커 장애 (Kafka 서버가 죽은 경우)

```
Outbox 레코드: PENDING 상태로 DB에 안전하게 보관
OutboxRelay: 발행 시도 → 실패 → retry_count 증가
Kafka 복구 후: PENDING 레코드를 자동으로 재전송
```

#### 🟡 기관 서버 장애 (민원24 서버가 불통인 경우)

```
webhook_dispatch_outbox: PENDING 상태 유지
재시도 스케줄: 2초 → 4초 → 8초 (지수 백오프)
3회 실패 시: FAILED 상태 → 운영팀 알림 발생
운영팀: 기관 복구 확인 후 수동 재발송 또는 상태 리셋
```

#### 🟡 프로비저닝 실패 (기관 회원 등록 실패)

```
provisioning_outbox: PENDING 상태 유지
재시도: 1분 → 5분 → 30분 (느린 백오프)
3회 실패: DEAD_LETTER 상태 → 운영팀 수동 처리 필요
```

#### ⚠️ 중복 전달 발생 시

```
원인: 재시도로 인해 동일 이벤트가 2번 전송될 수 있음
     (at-least-once 특성)

방어 방법: 수신 측 IdempotentEventStore (중복 방어 저장소)
  - eventId를 DB에 기록
  - 동일 eventId 수신 시: "이미 처리함" → 무시
  - 사용자 입장에서는 문제없음
```

---

## 10. 운영 관점: 자주 묻는 질문

### Q1: Outbox 테이블이 쌓이면 디스크가 꽉 차지 않나요?

> PUBLISHED 상태 레코드는 **30일 후 자동 삭제**됩니다 (배치 정리 작업).  
> FAILED 레코드는 운영팀이 조치 후 수동 삭제하거나, 별도 아카이브 정책 적용.  
> 정상 운영 중에는 수백~수천 건 수준으로 유지됩니다.

### Q2: Outbox 상태가 "FAILED"인 것이 있으면 어떻게 해야 하나요?

> 1. 해당 레코드의 `error_message` 컬럼 확인 (실패 원인 기재)
> 2. 원인 해결 (기관 서버 복구, 네트워크 문제 해결 등)
> 3. 상태를 `PENDING`으로 리셋 → OutboxRelay가 자동 재처리
> 4. 또는 개발팀에 문의

### Q3: Kafka와 Outbox 모두 있는 이유는?

> - **Outbox**: "DB에 저장"과 "이벤트 발행" 간의 원자성 보장 (분실 방지)
> - **Kafka**: 대용량 메시지를 여러 소비자에게 효율적으로 전달 (성능·확장성)
> - 둘의 역할이 다르므로 함께 사용합니다

### Q4: Handoff 이벤트는 Outbox를 안 쓴다고 하던데요?

> `ido.handoff.events` 토픽으로의 발행은 **직접 Kafka 전송** 방식을 사용합니다.  
> HandoffServiceImpl에서 트랜잭션 내에 Handoff 티켓 저장 + Kafka 발행을 묶어서 처리합니다.  
> 단, Handoff 이벤트를 **수신한 후** 기관에 Webhook 발송하는 과정에서는 `webhook_dispatch_outbox`를 사용합니다.

### Q5: 60,000명이 동시에 인증해도 괜찮나요?

> 네. 다음과 같이 설계되어 있습니다:
> - Kafka 12개 파티션 × 6개 Consumer 스레드 = 초당 1,200건 처리 가능
> - 인증 완료 시 Redis 캐시 사전 적재(Pre-warming) → DB 조회 부하 99% 감소
> - Outbox 릴레이가 500ms 간격으로 독립 실행 → 피크 부하 흡수
> - 각 서비스 독립 확장 가능 (Q-Sign/Q-IM/IdO 별개 배포)

---

## 부록: 용어 정리

| 용어 | 설명 |
|-----|------|
| **Outbox** | "해야 할 일 목록"이 저장되는 데이터베이스 테이블 |
| **Relay** | Outbox 목록을 읽어서 메시지를 실제로 보내는 자동 담당자 |
| **Kafka** | 초고속 메시지 분류·전달 시스템 (이벤트 버스) |
| **Topic** | Kafka의 채널. 같은 종류의 메시지가 모이는 공간 |
| **PENDING** | Outbox에 기록됐지만 아직 전송 안 됨 |
| **PUBLISHED** | Kafka 발행 성공 |
| **FAILED** | 최대 재시도 횟수 초과, 운영팀 개입 필요 |
| **DISPATCHED** | Webhook HTTPS 발송 성공 |
| **COMPLETED** | 프로비저닝 HTTP 발송 성공 |
| **DEAD_LETTER** | 프로비저닝 최대 재시도 초과, 운영팀 수동 처리 필요 |
| **at-least-once** | 최소 1번은 반드시 전달함. 간혹 중복 전달 가능 |
| **멱등성** | 같은 메시지를 여러 번 받아도 결과가 달라지지 않는 성질 |
| **HMAC-SHA256** | 메시지 위변조 여부를 확인하는 디지털 서명 방식 |
| **파티션 키** | 같은 사용자의 메시지가 같은 Kafka 파티션으로 가게 하는 식별자 |
| **지수 백오프** | 실패할수록 재시도 간격을 지수적으로 늘리는 전략 (2초→4초→8초) |
| **스냅샷** | 특정 시점의 전체 상태 사진. 이벤트를 일일이 재생하지 않고 최신 상태를 바로 얻을 수 있음 |

---

*이 문서는 원패스 통합인증 플랫폼 운영팀 및 도입 기관 담당자를 위해 작성되었습니다.*  
*기술적 세부사항은 개발자용 가이드를 참조하세요.*
