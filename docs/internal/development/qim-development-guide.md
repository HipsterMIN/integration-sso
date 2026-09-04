# Q-IM 개발 가이드 — Q-IM 팀 내부 정본

> **문서 분류**: 내부 개발 가이드 (Internal Development Guide)  
> **버전**: v1.1.0  
> **최초 작성**: 2026-05-10 | **최종 수정**: 2026-05-10  
> **대상 독자**: Q-IM 개발팀  
> **배포 범위**: Q-IM 팀 내부 한정 — 외부 기관 미공개  
> **근거 문서**:  
> - `docs/qim-ido-integration-architecture.md` (ADR-001 — IdO 완전 중재 패턴)  
> - `docs/qim-sp-receiver-api-spec.md` (Q-IM SP 수신 API 명세서 v1.0.0)  
> - Q-IM 유관기관 SP 개발자 API 명세서 v1.52  
> - 현재 구현된 `q-im` 모듈 소스 코드 기준

> **v1.1.0 변경 이력**  
> - §3.2, §4, §5.3, §6, §7.3, §8.3, §10, 부록 A.2, 부록 C 수정  
> - **핵심 변경**: Q-IM → IdO 동기 HTTP 아웃바운드 방식 → `qim.user.events` Kafka Consumer 방식으로 아키텍처 정정  
> - PII Soft Delete 정책 논의 항목 신규 추가

---

## 목차

1. [이 문서의 목적](#1-이-문서의-목적)
2. [Q-IM 정본(SoR) 역할 정의](#2-q-im-정본sor-역할-정의)
3. [스크린샷 해석 — "지금 보이는 화면이 최종 구조가 아닙니다"](#3-스크린샷-해석)
4. [전체 아키텍처 — Q-IM ↔ IdO ↔ 유관기관 통신 구조](#4-전체-아키텍처)
5. [API 전체 분류표 — 구현 완료 / 미구현 / IdO 담당](#5-api-전체-분류표)
6. [Q-IM이 개발해야 할 것 vs IdO가 개발해야 할 것](#6-역할-분리-개발-책임-분담표)
7. [현재 구현 상태 상세](#7-현재-구현-상태-상세)
8. [생산 수준 필수 체크리스트](#8-생산-수준-필수-체크리스트)
9. [Q-IM ↔ IdO 협의 필요 항목](#9-q-im--ido-협의-필요-항목)
10. [개발 로드맵 — 권장 진행 순서](#10-개발-로드맵)
11. [부록 A — API 요청/응답 상세](#부록-a--api-요청응답-상세)
12. [부록 B — DB 스키마 현황](#부록-b--db-스키마-현황)
13. [부록 C — 환경변수 목록](#부록-c--환경변수-목록)

---

## 1. 이 문서의 목적

**이 문서는 Q-IM 개발팀에게 두 가지를 명확히 전달하기 위해 작성되었습니다.**

### 1.1 지금 테스트 중인 화면은 임시 구조입니다

Q-IM 팀이 시연을 위해 직접 유관기관에 `POST /api/ciw-im/member/register` (MEMBER_REGISTER)를 발송하고 있는 것을 확인했습니다. **이 직접 호출 방식은 최종 아키텍처가 아닙니다.** 테스트 시연이 완료된 후에는 이 역할을 **IdO가 완전히 대리**하게 됩니다.

### 1.2 Q-IM은 "정본(SoR)"만 집중합니다

Q-IM은 회원 원장(Source of Record)입니다. 유관기관과의 연계, 외부 노출, SP 관리는 모두 IdO가 전담합니다. Q-IM 개발팀은 **내부 데이터 정합성과 이벤트 발행**에만 집중하면 됩니다.

---

## 2. Q-IM 정본(SoR) 역할 정의

### 2.1 Q-IM이 보유하는 것 (단독 소유)

| 데이터 | 저장 위치 | 보안 등급 | 설명 |
|--------|-----------|-----------|------|
| **CI (연계정보)** | `user_profile.ci` | 🔴 최고기밀 | AES-256-GCM 암호화 저장. **절대 외부 노출 금지** |
| **rawName (마스킹 전)** | 수신 즉시 파기 | 🔴 최고기밀 | Q-IM 수신 직후 마스킹 처리, 원문은 메모리에서 즉시 삭제 |
| **rawMobile (마스킹 전)** | 수신 즉시 파기 | 🔴 최고기밀 | 동일 |
| **qimUserId** | `qim_user.qim_user_id` | 🔴 내부 기밀 | Q-IM 내부 UUID. 외부에 직접 노출 금지 (instMbrId로 간접 참조) |
| **DI (중복가입확인정보)** | `user_profile.di_map` | 🟠 민감 | 기관별 HMAC-SHA256. `{agencyCode: DI}` JSON 형태 |
| **identifierHash** | `auth_mean_mapping.identifier_hash` | 🟡 내부 공유 가능 | SHA-256(rawCi) — CI 없이 식별자 역할 |
| **회원 상태 이력** | `user_status_history` | 🟡 내부 전용 | ACTIVE/SUSPENDED/WITHDRAWN 변경 이력 |

### 2.2 Q-IM이 절대 하지 않아야 할 것

```
❌ CI 평문을 API 응답 또는 로그에 포함하는 것
❌ qimUserId를 유관기관 API에 직접 노출하는 것
❌ 유관기관 정보시스템에 직접 HTTP 호출하는 것 (→ IdO 위임)
❌ 기관별 비즈니스 로직을 Q-IM 내부에 구현하는 것
❌ MEMBER_REGISTER / MEMBER_QUERY / MEMBER_WITHDRAW를 유관기관에 직접 발송하는 것
❌ IdO SP 수신 API를 동기 HTTP로 직접 호출하는 것 (→ Kafka Outbox 비동기로 대체)
```

### 2.3 Q-IM이 해야 할 것 (핵심 책임)

```
✅ 회원 원장 관리: 등록(Upsert), 상태 변경, 탈퇴 처리
✅ CI AES-256-GCM 암호화 저장 및 키 버전 관리
✅ PII 마스킹: nameMasked, mobileMasked
✅ DI 생성: HMAC-SHA256(agencyCode:qimUserId, diSecret)
✅ identifierHash 저장: SHA-256(rawCi)
✅ Transactional Outbox → Kafka 비동기 이벤트 발행 (qim.user.events, qim.user.snapshot)
   → 이 이벤트를 IdO가 Consumer로 구독하여 유관기관에 전파함. Q-IM은 HTTP 아웃바운드 불필요
✅ IdO에서 호출하는 내부 API 유지 (/api/v1/internal/**)
✅ Q-IM 관리 콘솔에서 IdO를 SP로 등록하고 API Key 발급
```

---

## 3. 스크린샷 해석

### 3.1 현재 테스트 화면 해석

테스트 모니터링 화면에서 확인된 로그:

```
방향    메서드  경로                              기관코드          결과
OUT     POST   /api/ciw-im/member/register      smes-tipa-01      500 FAIL   ← ❌ 직접 호출
OUT     POST   /api/ciw-im/member/register      smes-sbiz-01      200 OK     ← ❌ 직접 호출
OUT     POST   /api/ciw-im/member/register      smes-biz24-01     200 OK     ← ❌ 직접 호출
OUT     POST   /api/ciw-im/member/register      smes-pass-01      200 OK     ← ❌ 직접 호출
IN      POST   /api/ext/provision/enterprises   (중기원패스)        201 OK
```

**현재 문제**: Q-IM이 유관기관(`smes-tipa-01`, `smes-sbiz-01` 등)에 **직접** MEMBER_REGISTER를 발송하고 있습니다.

- `smes-tipa-01` (통합플랫폼 중소벤처24): **500 FAIL** — 이 기관 장애가 Q-IM 로그에 직접 노출되고 있음
- 이 직접 발송 구조는 **테스트 시연용 임시 구조**입니다
- **최종 아키텍처에서는 Q-IM이 유관기관을 전혀 알 필요가 없습니다**

### 3.2 최종 아키텍처에서 동일한 흐름이 어떻게 달라지는가

```
현재 (임시 구조 — 직접 HTTP 호출):
  Q-IM ──HTTP POST──► smes-tipa-01  (500 FAIL → Q-IM 로그에 오염)
  Q-IM ──HTTP POST──► smes-sbiz-01  (200 OK)
  Q-IM ──HTTP POST──► smes-biz24-01 (200 OK)
  Q-IM ──HTTP POST──► smes-pass-01  (200 OK)

  문제: 개별 기관 장애가 Q-IM 트랜잭션에 직접 영향. 기관 수만큼 HTTP 클라이언트 관리.

최종 아키텍처 (Kafka EDA — Q-IM은 이벤트만 발행):
  Q-IM ──Outbox──► qim.user.events (Kafka) ──► IdO Consumer ──► agency-adapter
                                                                ├─► smes-tipa-01
                                                                ├─► smes-sbiz-01
                                                                ├─► smes-biz24-01
                                                                └─► smes-pass-01

  이점:
  ① Q-IM은 Kafka 발행만 성공하면 끝 — 기관별 장애에 완전 무관
  ② smes-tipa-01 500 FAIL은 IdO의 Circuit Breaker가 격리
  ③ Q-IM에 HTTP 클라이언트 코드 추가 불필요 — 기존 Outbox 재사용
  ④ 기관 추가/제거 시 Q-IM 코드 수정 없음
```

**Q-IM의 역할은 `qim.user.events` 토픽에 이벤트를 발행하는 것으로 끝납니다.**  
IdO가 해당 토픽을 구독(Consume)하여 유관기관 전파를 담당합니다.

### 3.3 IN 방향 해석

```
IN POST /api/ext/provision/enterprises (중기원패스 → Q-IM): 201 OK
```
이것은 **올바른 방향**입니다. 중기원패스(→ IdO → Q-IM 내부 API)를 통한 기업 프로비저닝 요청이 정상 수신된 것입니다. 이 방향은 유지됩니다.

---

## 4. 전체 아키텍처

### 4.1 최종 목표 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────┐
│                     외부 / 유관기관 영역                               │
│                                                                     │
│  사용자 브라우저                유관기관 정보시스템                      │
│  (onepass-fe)                  smes-tipa-01 (통합플랫폼 중소벤처24)    │
│                                smes-sbiz-01 (중소기업 플랫폼)          │
│                                smes-biz24-01 (비즈24)                │
│                                smes-pass-01 (중기원패스)              │
└────────────────┬───────────────────────┬────────────────────────────┘
                 │ BFF API               │ Handoff Ticket / SSO
                 ▼                       ▲
┌─────────────────────────────────────────────────────────────────────┐
│                       IdO (port 8083)                               │
│               [Q-IM의 SP 역할 대리 — Full Mediation Pattern]          │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  qim.user.events Consumer (Q-IM 이벤트 수신)                  │  │
│  │  QIM_USER_REGISTERED  → instMbrId 매핑 생성 → 유관기관 전파    │  │
│  │  QIM_USER_WITHDRAWN   → FeSession 무효화 → 유관기관 전파       │  │
│  │  QIM_USER_SUSPENDED   → 상태 동기화                           │  │
│  └──────────────────────────────┬───────────────────────────────┘  │
│                                 │                                   │
│  ┌──────────────────────────────▼───────────────────────────────┐  │
│  │  IdO 내부 처리 인프라                                           │  │
│  │  Outbox → Kafka    FeSession(Redis)   AgencyMeta              │  │
│  │  PolicyEngine      instMbrId 매핑     IdempotencyStore        │  │
│  └──────────────────────────────┬───────────────────────────────┘  │
│                                 │ 유관기관 비동기 전파                 │
└─────────────────────────────────┼───────────────────────────────────┘
                                  │ Kafka: qim.sp.member.events
                                  │
        ┌─────────────────────────┘
        │
        ▼  agency-adapter Consumer (Circuit Breaker 격리)
   smes-tipa-01 / smes-sbiz-01 / smes-biz24-01 / smes-pass-01
        (개별 기관 장애가 Q-IM 및 IdO 핵심 흐름에 영향 없음)

┌─────────────────────────────────────────────────────────────────────┐
│                 Q-IM (port 8082) [내부 격리 — 정본]                   │
│                                                                     │
│  IdO가 호출하는 내부 API (HTTP REST ← 단방향)                          │
│  /api/v1/internal/users/**  ◄─── IdO → Q-IM                        │
│  /api/v1/internal/member/**                                         │
│  /api/v1/users/{qimUserId}    (QimStatusController)                 │
│                                                                     │
│  Kafka 이벤트 발행 (Transactional Outbox → Relay)                    │
│  qim.user.events  ──────────────────────────────────────────────►   │
│  qim.user.snapshot ─────────────────────────────────────────────►   │
│                                                                     │
│  ※ Q-IM은 IdO 또는 유관기관에 직접 HTTP 호출하지 않습니다               │
│     모든 아웃바운드는 Kafka Outbox를 통한 비동기 이벤트로 처리됩니다       │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 통신 방향 요약

| 방향 | 경로 | 프로토콜 | 인증 | 설명 |
|------|------|---------|------|------|
| **IdO → Q-IM (내부 API)** | `/api/v1/internal/users` 등 | HTTP REST | `X-Internal-Api-Key` | 회원 등록/조회/탈퇴/DI 생성 |
| **IdO → Q-IM (상태 조회)** | `GET /api/v1/users/{qimUserId}` | HTTP REST | `X-Internal-Api-Key` | FeSession 발급 전 상태 확인 |
| **Q-IM → Kafka (이벤트 발행)** | `qim.user.events` | Kafka | 내부 브로커 | 회원 변경 이벤트 발행 (유일한 아웃바운드) |
| **Q-IM → Kafka (스냅샷)** | `qim.user.snapshot` | Kafka | 내부 브로커 | 10이벤트마다 전체 상태 스냅샷 |
| **IdO ← Kafka (이벤트 소비)** | `qim.user.events` | Kafka | 내부 브로커 | IdO가 Q-IM 이벤트를 Consumer로 수신 |
| **IdO → Kafka (유관기관 전파)** | `qim.sp.member.events` | Kafka | 내부 브로커 | IdO → agency-adapter Consumer |

> **핵심 원칙**: Q-IM의 유일한 아웃바운드는 **Kafka 이벤트 발행**입니다.  
> Q-IM은 IdO를 포함한 어떤 외부 시스템에도 HTTP 호출을 하지 않습니다.

---

## 5. API 전체 분류표

### 5.1 Q-IM 내부 API (IdO → Q-IM 호출) — **현재 구현 완료**

> **이 API들은 Q-IM 팀이 이미 구현 완료한 상태입니다. 인터페이스 유지가 중요합니다.**

| 엔드포인트 | 메서드 | 구현 클래스 | 상태 | 설명 |
|-----------|--------|-----------|------|------|
| `/api/v1/internal/users` | POST | `UserController` | ✅ 완료 | 회원 등록/조회 (Upsert) |
| `/api/v1/internal/users/{qimUserId}` | GET | `UserController` | ✅ 완료 | qimUserId로 조회 |
| `/api/v1/internal/users/by-hash` | GET | `UserController` | ✅ 완료 | identifierHash로 조회 |
| `/api/v1/internal/users/{qimUserId}/status` | PATCH | `UserController` | ✅ 완료 | 상태 변경 (ACTIVE/SUSPENDED/WITHDRAWN) |
| `/api/v1/internal/users/{qimUserId}` | DELETE | `UserController` | ✅ 완료 | 탈퇴 처리 (PII 삭제) |
| `/api/v1/internal/users/{qimUserId}/di` | GET | `UserController` | ✅ 완료 | 기관별 DI 조회/생성 |
| `/api/v1/internal/users/{qimUserId}/status-check` | GET | `UserController` | ✅ 완료 | 상태 조회 보조 |
| `/api/v1/internal/member/lookup-by-ci` | POST | `MemberLookupController` | ✅ 완료 | 암호화 CI → 사용자 조회 |
| `/api/v1/internal/member/lookup-by-hash` | GET | `MemberLookupController` | ✅ 완료 | identifierHash 직접 조회 |

**보안 요구사항**: 모든 내부 API는 `X-Internal-Api-Key` 헤더 검증 필수 (`InternalApiKeyInterceptor`)

### 5.2 Q-IM 상태 조회 API (IdO QimClient 호출) — **현재 구현 완료**

| 엔드포인트 | 메서드 | 구현 클래스 | 상태 | 설명 |
|-----------|--------|-----------|------|------|
| `/api/v1/users/{qimUserId}` | GET | `QimStatusController` | ✅ 완료 | IdO PolicyEngine → QimClientImpl 호출 |

> **주의**: `/api/v1/internal/users/{qimUserId}/status-check`(내부 API)와 경로가 다릅니다.  
> `QimStatusController`는 별도 매핑입니다.

### 5.3 Q-IM 이벤트 발행 (Kafka) — **현재 구현 완료, 유지/고도화 필요**

> **이것이 Q-IM의 유일한 아웃바운드 채널입니다.**  
> IdO가 이 토픽을 Consumer로 구독하여 유관기관 전파를 처리합니다.  
> Q-IM은 별도의 HTTP 아웃바운드 클라이언트를 구현할 필요가 없습니다.

| 토픽 | 파티션 키 | 현재 상태 | 이벤트 유형 | 설명 |
|------|----------|---------|-----------|------|
| `qim.user.events` | `qimUserId` | ✅ 완료 | `USER_UPDATED` | 신규 가입 / 정보 변경 |
| `qim.user.events` | `qimUserId` | ✅ 완료 | `USER_SUSPENDED` | 계정 정지 |
| `qim.user.events` | `qimUserId` | ✅ 완료 | `USER_WITHDRAWN` | 탈퇴 처리 |
| `qim.user.snapshot` | `qimUserId` | ✅ 완료 | `USER_SNAPSHOT` | 10이벤트마다 전체 상태 스냅샷 |

**현재 구현된 이벤트 발행 흐름**:
```
UserRegistrationServiceImpl.registerOrGet()
  └─► outboxService.publishInTx(UserEvent)  // 동일 트랜잭션 내 Outbox INSERT
        └─► [500ms 스케줄러] relayPendingEvents()
              └─► kafkaTemplate.send("qim.user.events", qimUserId, event)
                    └─► 성공: PUBLISHED 갱신 + 스냅샷 트리거(10이벤트마다)
                    └─► 실패: FAILED 갱신 → 30초 후 relayFailedEvents() 재시도
                              최대 5회 실패 시 영구 FAILED → 운영팀 수동 조치
```

**IdO 측 구현 필요**: IdO가 `qim.user.events` 토픽을 구독하는 Consumer 구현

```java
// IdO 측 Consumer 예시 (Q-IM 팀 구현 불필요)
@KafkaListener(topics = "qim.user.events", groupId = "ido-qim-consumer")
public void onQimUserEvent(UserEvent event, Acknowledgment ack) {
    switch (event.getEventType()) {
        case "USER_UPDATED"   -> handleRegister(event);   // instMbrId 매핑 생성
        case "USER_WITHDRAWN" -> handleWithdraw(event);   // FeSession 무효화 + 유관기관 전파
        case "USER_SUSPENDED" -> handleSuspend(event);    // 상태 동기화
        case "USER_SNAPSHOT"  -> handleSnapshot(event);   // 초기 상태 복원용
    }
    ack.acknowledge();
}
```

### 5.4 Q-IM SP 수신 API — IdO가 구현, Q-IM 관리 콘솔 등록 필요

> Q-IM v1.52 명세에 따라 SP(IdO)는 수신 엔드포인트 3종을 구현해야 합니다.  
> **구현 주체는 IdO 팀입니다.** Q-IM은 이 엔드포인트 URL을 Q-IM 관리 콘솔에 등록하면 됩니다.

| Q-IM 발신 시나리오 | IdO 수신 엔드포인트 | IdO 구현 상태 | Q-IM 콘솔 등록 |
|---------------|----------------|------------|--------------|
| 전환 신청 전 조회 | `POST /api/qim/sp/v1/member/query` | 🔄 구현 중 | ⏳ 등록 필요 |
| 신규/전환 회원 통보 | `POST /api/qim/sp/v1/member/register` | 🔄 구현 중 | ⏳ 등록 필요 |
| 탈퇴 전파 | `POST /api/qim/sp/v1/member/withdraw` | 🔄 구현 중 | ⏳ 등록 필요 |

> **Q-IM 팀 액션**: Q-IM 관리 콘솔에서 IdO를 SP로 등록하고, 위 URL 3개를 각 이벤트에 매핑합니다.  
> URL 확정은 §9 협의 필요 항목 참조.

### 5.5 Q-IM 외부 수신 API (외부 → Q-IM)

| 엔드포인트 | 메서드 | 호출자 | 상태 | 설명 |
|-----------|--------|--------|------|------|
| `/api/ext/provision/enterprises` | POST | 중기원패스(→IdO→Q-IM) | ✅ 동작 확인 | 기업 프로비저닝 |
| `/api/ext/v1/member/register` | POST | IdO | 🔄 확인 필요 | IdO → Q-IM 회원 등록 요청 |
| `/api/ext/v1/member/query` | POST | IdO | 🔄 확인 필요 | IdO → Q-IM 회원 조회 요청 |
| `/api/ext/v1/member/withdraw` | POST | IdO | 🔄 확인 필요 | IdO → Q-IM 탈퇴 요청 |

> 이 API들은 Q-IM이 직접 유관기관과 통신하는 채널이 아닙니다. 항상 IdO를 거쳐서만 Q-IM에 도달합니다.

---

## 6. 역할 분리 개발 책임 분담표

### 6.1 한눈에 보는 책임 분리

```
┌─────────────────────────────────────────────────────────────────┐
│                      Q-IM 팀 개발 범위                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  내부 원장 API 유지 (/api/v1/internal/**)             ✅ 완료      │
│  CI AES-256-GCM 암호화/복호화/키 버전 관리             ✅ 완료      │
│  DI HMAC-SHA256 생성 (기관별 독립)                    ✅ 완료      │
│  PII 마스킹 / 삭제 처리                               ✅ 완료      │
│  Kafka Outbox 이벤트 발행 (qim.user.events)          ✅ 완료      │
│  Kafka Snapshot 발행 (qim.user.snapshot)             ✅ 완료      │
│  회원 상태 이력 관리                                   ✅ 완료      │
│                                                                 │
│  Q-IM 관리 콘솔 IdO SP 등록                           ⚠️ 미완료    │
│    - IdO를 SP로 등록 (clientId 발급)                             │
│    - API Key 발급 후 IdO 팀에 전달                               │
│    - SP 수신 엔드포인트 URL 3개 등록                              │
│                                                                 │
│  PII 삭제 정책 법무팀 협의                              ⚠️ 검토 필요  │
│    - 현재: 탈퇴 즉시 Hard Delete (GDPR §17)                      │
│    - 검토: 30일 Soft Delete 후 일괄 파기 (CS 대응 목적)            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      IdO 팀 개발 범위                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  qim.user.events Kafka Consumer 구현                🔄 구현 중    │
│    - USER_UPDATED  → instMbrId 매핑 생성                         │
│    - USER_WITHDRAWN → FeSession 무효화 + 유관기관 전파            │
│    - USER_SUSPENDED → 상태 동기화                                │
│                                                                 │
│  SP 수신 API 3종 구현 (Q-IM 명세 §2.4 준수)          🔄 구현 중    │
│    POST /api/qim/sp/v1/member/query                             │
│    POST /api/qim/sp/v1/member/register                          │
│    POST /api/qim/sp/v1/member/withdraw                          │
│                                                                 │
│  instMbrId 매핑 저장 (ido.inst_mbr_id_mapping)      🔄 구현 중    │
│  Idempotency Store (ido.sp_receiver_idempotency)    🔄 구현 중    │
│  Kafka 발행 (qim.sp.member.events → agency-adapter) 🔄 구현 중    │
│                                                                 │
│  Q-IM SP 콘솔 등록 (운영팀 협력)                      ⏳ 협의 필요  │
│  API Key 교환 (Q-IM ↔ IdO 양방향)                   ⏳ 협의 필요  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 상세 책임 분담표

| 기능 | Q-IM 팀 | IdO 팀 | 우선순위 |
|------|---------|--------|---------|
| 회원 원장 CRUD | ✅ 담당 | — | P0 |
| CI 암호화 (Q-IM 내부 키) | ✅ 담당 | — | P0 |
| DI 생성 | ✅ 담당 | — | P0 |
| PII 마스킹/삭제 | ✅ 담당 | — | P0 |
| Kafka 이벤트 발행 (`qim.user.events`) | ✅ 담당 (완료) | Consumer 구현 | P0 |
| Kafka 이벤트 소비 (`qim.user.events`) | ❌ 불필요 | ✅ 담당 (신규) | P0 |
| IdO SP 수신 API 구현 | ❌ 불필요 | ✅ 담당 | P0 |
| Q-IM → IdO HTTP 아웃바운드 | ❌ **구현 금지** | ❌ 불필요 | — |
| instMbrId 저장 | — | ✅ 담당 (Consumer 처리 후) | P0 |
| 유관기관 직접 연동 | ❌ 금지 | ✅ 담당 | — |
| 기관별 비즈니스 로직 | ❌ 금지 | ✅ 담당 | — |
| Circuit Breaker (기관별 장애 격리) | — | ✅ 담당 | P1 |
| API Key 관리 (Q-IM 콘솔) | ✅ Q-IM 발급 → IdO 전달 | ✅ IdO 키 보관 | P0 |
| PII 삭제 정책 확정 | ✅ 법무팀 협의 | 결과 수신 | P1 |

---

## 7. 현재 구현 상태 상세

### 7.1 구현 완료 — Q-IM 내부 API

**`UserController.java`** — 7개 내부 엔드포인트 완전 구현

```java
// 인증: X-Internal-Api-Key 헤더 검증 (InternalApiKeyInterceptor)
// 기본 경로: /api/v1/internal/users

POST   /api/v1/internal/users                          → registerOrGet()   // Upsert, isNew로 201/200 구분
GET    /api/v1/internal/users/{qimUserId}              → getUser()         // 전체 프로필 반환 (CI 미포함)
GET    /api/v1/internal/users/by-hash                  → getUserByHash()   // identifierHash 기반
PATCH  /api/v1/internal/users/{qimUserId}/status       → updateStatus()    // ACTIVE/SUSPENDED 전환
DELETE /api/v1/internal/users/{qimUserId}              → withdraw()        // PII 삭제
GET    /api/v1/internal/users/{qimUserId}/di           → getDi()           // 기관별 DI 조회/생성
GET    /api/v1/internal/users/{qimUserId}/status-check → getUserStatus()   // 상태만 빠르게 조회
```

**`MemberLookupController.java`** — CI 기반 조회

```java
POST /api/v1/internal/member/lookup-by-ci    // encCi 수신 → 복호화 → identifierHash → 사용자 조회
GET  /api/v1/internal/member/lookup-by-hash  // identifierHash 직접 조회
```

**`QimStatusController.java`** — IdO QimClientImpl 전용

```java
GET /api/v1/users/{qimUserId}
// 응답: { "qimUserId": "...", "status": "ACTIVE|SUSPENDED|WITHDRAWN", "updatedAt": "..." }
// → IdO PolicyEngineImpl이 이 경로를 호출함
// ※ /api/v1/internal/users/{qimUserId}/status-check 와 경로 다름 — 혼동 주의
```

### 7.2 구현 완료 — 핵심 서비스 레이어

**`UserRegistrationServiceImpl.java`**:
- `registerOrGet()`: identifierHash 기반 Upsert. UUID v7 사용 (UUID v4 금지)
- `updateStatus()`: 상태 변경 + 이력 기록 + Kafka Outbox 발행
- `withdraw()`: PII 삭제(`deletePii`) + Outbox 발행

**`OutboxServiceImpl.java`** — Transactional Outbox 완전 구현:
- `publishInTx()`: 호출자 트랜잭션 내 PENDING 레코드 INSERT (이벤트 유실 방지)
- `relayPendingEvents()`: 500ms 스케줄러 — PENDING → Kafka 발행 → PUBLISHED
- `relayFailedEvents()`: 30초 스케줄러 — FAILED(retry < 5) → PENDING 복구 → 재발행
- 발행 실패 시 실제 예외 메시지를 `error_message` 컬럼에 저장 (운영 디버깅)

**`SnapshotServiceImpl.java`** — Compacted Snapshot 완전 구현:
- 10이벤트마다 `qim.user.snapshot` 토픽에 전체 상태 스냅샷 발행
- `snapshot_meta` 테이블로 중복 발행 방지 (UNIQUE 제약)
- 스냅샷 실패는 비치명적 — Outbox 발행 흐름과 분리

**`CiCryptoServiceImpl.java`**:
- AES-256-GCM 암호화. 출력: `v{n}.{base64url(iv)}.{base64url(ciphertext+tag)}`
- 키 버전 관리: `QIM_CI_AES_KEY_V1`, `QIM_CI_AES_KEY_V2`, `QIM_CI_CURRENT_VERSION`
- `isEncrypted()`: `^v\d+\..+\..+$` 패턴 판별

**`DiGenerationService.java`**:
- `HMAC-SHA256(agencyCode:qimUserId, diSecret)` → Base64URL
- `di_map` JSON에 `{agencyCode: DI}` 형태로 저장
- 기관별 독립 DI — 기관 간 DI 공유 절대 금지

### 7.3 추가 구현 필요 항목 (Q-IM 팀)

```
❶ Q-IM 관리 콘솔 SP 등록
   - IdO를 SP로 등록 (clientId 발급)
   - API Key 발급 후 IdO 팀에 전달
   - SP 수신 엔드포인트 URL 3개 등록 (§9 협의 필요 항목 참조)

❷ Bean Validation 전 DTO 적용 (§8.1 참조)
   - UserRegisterRequest, UserStatusUpdateRequest 등

❸ CI 암호화 키 환경변수 기동 시 검증 (§8.1 참조)
   - 32바이트 미만이면 앱 기동 중단

❹ PII 삭제 정책 확정 후 코드 반영 (§8.3 참조)
   - 현재: 즉시 Hard Delete
   - 검토 방향: Soft Delete + 30일 배치 파기
```

---

## 8. 생산 수준 필수 체크리스트

### 8.1 🔴 P0 — 운영 출시 전 필수 (차단 항목)

| 항목 | 현재 상태 | 구현 방법 |
|------|-----------|----------|
| **Bean Validation** | ⚠️ 미확인 | `@NotBlank`, `@Size`, `@Pattern`을 DTO에 추가. `@Valid` 검증 실패 시 400 반환 |
| **X-Internal-Api-Key 인터셉터** | ✅ 구현됨 | `InternalApiKeyInterceptor` — 키 불일치 시 401 반환 확인 |
| **CI 암호화 키 환경변수 검증** | ⚠️ 확인 필요 | 앱 기동 시 `QIM_CI_AES_KEY_V1`이 32바이트 Base64인지 검증. 실패 시 즉시 기동 중단 |
| **DI Secret 환경변수 검증** | ⚠️ 확인 필요 | `QIM_DI_SECRET`이 최소 32자 이상인지 검증. 운영 환경에서 기본값 사용 금지 |
| **Outbox 트랜잭션 정합성** | ✅ 구현됨 | `publishInTx()` — 동일 트랜잭션 내 보장. DB 커밋 후 이벤트 유실 없음 확인 |
| **UUID v7 사용** | ✅ 구현됨 | `UuidV7.generate()` — UUID v4 혼용 코드 없는지 전수 확인 |
| **identifierHash 인덱스** | ✅ V3 migration | `auth_mean_mapping.identifier_hash` 인덱스 존재 확인 |
| **CI 평문 로그 차단** | ⚠️ 확인 필요 | `log.info("rawCi={}", req.getRawCi())` 형태 코드 전수 검색 및 제거 |

### 8.2 🟠 P1 — 1개월 내 필수

| 항목 | 설명 |
|------|------|
| **Resilience4j Rate Limiter** | `/api/v1/internal/**` 엔드포인트에 Rate Limit 적용. 오남용 방지 |
| **CI 키 로테이션 무중단 처리** | `QIM_CI_CURRENT_VERSION` 변경 시 구버전 키로 복호화 → 신버전 키로 재암호화 배치 |
| **감사 로그** | 회원 등록/탈퇴/상태 변경 시 `who/what/when/why` 감사 로그 적재. PII 미포함 필수 |
| **Flyway 마이그레이션 테스트** | `H2(MODE=MariaDB)`로 `V1`, `V3` 스키마 통합 테스트. CI/CD 파이프라인 포함 |
| **Outbox DLQ 처리** | maxRetry(5회) 도달 시 운영팀 알림 + DLQ(`qim.user.events.dlq`) 이관 자동화 |
| **`crypto_key_version` 테이블 동기화** | `V3` 마이그레이션으로 추가된 테이블. 실제 키 버전과 DB 메타가 일치하는지 검증 |
| **수평 확장 시 Outbox 릴레이 중복 방지** | Q-IM 인스턴스 2개 이상 실행 시 `relayPendingEvents()` 중복 발행 가능. 분산 잠금(Redis) 필요 |

### 8.3 🟡 P2 — 분기 내 필요

| 항목 | 설명 |
|------|------|
| **KMS 연동** | `QIM_CI_AES_KEY_*` 를 환경변수 평문에서 AWS KMS / HashiCorp Vault로 이관 |
| **OpenTelemetry 트레이싱** | `correlationId`를 OTel `trace_id`로 연결. Jaeger/Grafana 연동 |
| **PII Soft Delete 정책 도입 검토** | 아래 상세 설명 참조 |
| **Snapshot 토픽 컴팩션 운영 절차** | `qim.user.snapshot` 컴팩션 주기 모니터링 및 Consumer 복구 절차 문서화 |

#### PII Soft Delete 정책 검토 (권장)

현재 Q-IM은 탈퇴 즉시 PII를 `NULL` 처리합니다 (Hard Delete, GDPR §17 준수). 이 방식은 규정상 올바르나, **실제 서비스 운영에서 다음 문제가 발생합니다**:

```
현실적 시나리오:
  사용자 A: "방금 실수로 탈퇴 버튼을 눌렀어요. 복구해 주세요."
  현재 응답: "불가능합니다. PII가 이미 삭제되었습니다."

결과: CS(고객응대) 불가, 서비스 불만 급증
```

**권장 방향 (법무팀 협의 필요)**:

```
변경 전 (현재 — Hard Delete):
  탈퇴 확정 즉시 → user_profile PII NULL 처리

변경 후 (Soft Delete + 지연 파기):
  1단계: 탈퇴 확정 → status = WITHDRAWN, withdrawn_at = NOW()
          PII는 즉시 삭제하지 않고 보존 (30일)
  2단계: [Spring Batch, 매일 새벽]
          SELECT * FROM qim_user
          WHERE status = 'WITHDRAWN'
            AND withdrawn_at < NOW() - INTERVAL 30 DAY
          → 해당 user_profile PII NULL 처리 (실제 파기)

효과:
  ✅ 30일 내 복구 요청 → status = ACTIVE 복원으로 CS 대응 가능
  ✅ 30일 경과 후 자동 파기 → GDPR §17 의무 이행 (합리적 유예기간)
  ✅ 법적 보존 의무(7년) 데이터는 별도 아카이빙 테이블로 관리
```

> **주의**: 30일 유예기간이 규정상 허용되는지는 **반드시 법무팀 확인 필요**. 개인정보보호법, GDPR 해석에 따라 즉시 삭제가 강제될 수 있습니다.

### 8.4 보안 강화 체크리스트

```
□ CI 평문이 절대 로그에 출력되지 않는지 확인
  → log.info("rawCi={}", req.getRawCi())  ← 이런 코드 grep으로 전수 확인 후 제거

□ API 응답에 CI 컬럼이 포함되지 않는지 확인
  → UserResponse에 'ci' 필드 없음 ✅

□ 내부 API가 외부 LB에 노출되지 않는지 확인
  → /api/v1/internal/** 를 외부 Load Balancer가 라우팅하지 않도록 네트워크 정책 설정

□ DI Secret이 환경변수로 주입되는지 확인
  → application.yml 하드코딩 절대 금지

□ qimUserId가 외부 API 응답에 직접 노출되지 않는지 확인
  → 외부에는 instMbrId(= qimUserId)만 IdO를 통해 간접 노출

□ Q-IM이 유관기관 또는 IdO에 동기 HTTP 호출하는 코드가 없는지 확인
  → grep -rn "RestTemplate\|WebClient\|HttpClient\|FeignClient" idem-registry/src/
  → 검색 결과 0건이어야 정상 ✅
```

---

## 9. Q-IM ↔ IdO 협의 필요 항목

> **다음 항목은 Q-IM 팀과 IdO 팀이 함께 결정해야 합니다.**

| 우선순위 | 항목 | 현재 상황 | 결정 필요 내용 |
|---------|------|-----------|---------------|
| 🔴 P0 | **API Key 교환** | 미진행 | Q-IM이 IdO를 SP로 등록할 때 발급하는 API Key를 IdO 팀에 전달. 양방향 확인 |
| 🔴 P0 | **SP endpoint URL 확정** | `/api/qim/sp/v1/member/*` 예정 | Q-IM 관리 콘솔 등록 전 URL 확정 (운영 도메인 포함) |
| 🔴 P0 | **instMbrId 정책 확인** | `instMbrId = qimUserId` 설계 | Q-IM 명세가 별도 형식을 요구하는지 확인 |
| 🔴 P0 | **`qim.user.events` 이벤트 스키마 공유** | Q-IM 측 `UserEvent` DTO 기준 | IdO Consumer가 역직렬화할 수 있도록 스키마 문서 또는 공통 DTO 공유 |
| 🔴 P0 | **IdO Consumer groupId 확정** | 미결정 | `ido-qim-consumer` 또는 협의된 groupId — Kafka 파티션 할당에 영향 |
| 🟡 P1 | **Idempotency-Key 보관 기간** | 7일 예정 | Q-IM 측 재판단 기간과 맞춰야 함 |
| 🟡 P1 | **AES 공유키 정책 (encCi)** | Q-IM v1.52 명세 기준 필요 시 | Q-IM SP 명세에서 encCi 암호화를 요구하는 경우에만 협의. 현재 Kafka Consumer 방식에서는 rawCi 전송 없음 → 협의 범위 최소화 |
| 🟡 P1 | **PII Soft Delete 정책 결론** | 법무팀 협의 예정 | Q-IM 탈퇴 이벤트 발행 시점과 IdO FeSession 무효화 시점 조율 필요 |
| 🟢 P2 | **Snapshot 토픽 초기 로딩 전략** | 미결정 | IdO Consumer 신규 배포 시 `qim.user.snapshot` 기반 초기 상태 복원 절차 |

---

## 10. 개발 로드맵

### Phase 1 — 연동 준비 (Q-IM 팀 + IdO 팀 병렬, 1주)

```
Q-IM 팀 액션:
  ❶ Q-IM 관리 콘솔 IdO SP 등록
     - clientId 발급
     - API Key 발급 → IdO 팀에 전달
     - SP 수신 엔드포인트 URL 3개 등록 (IdO 팀과 URL 사전 확정)

  ❷ qim.user.events 이벤트 스키마 문서 작성 또는 UserEvent DTO 공유
     - 현재: platform-common 모듈의 UserEvent 클래스 기준
     - IdO 팀이 Consumer 역직렬화에 사용

  ❸ Bean Validation 전 DTO 추가
     - UserRegisterRequest 필수 필드 @NotBlank 추가
     - @Valid 검증 실패 시 GlobalExceptionHandler 400 반환 확인

IdO 팀 액션 (참고):
  ❶ qim.user.events Consumer 구현
     - USER_UPDATED → instMbrId 매핑 생성
     - USER_WITHDRAWN → FeSession 무효화 + 유관기관 전파 이벤트 발행
  ❷ SP 수신 API 3종 구현 (QimSpReceiverController)
```

### Phase 2 — 통합 테스트 (Q-IM + IdO 팀 공동, 1주)

```
목표: Q-IM Kafka 이벤트 → IdO Consumer → 유관기관 전파 전체 흐름 E2E 검증

❶ 신규 가입 시나리오
   IdO.registerUser() → Q-IM.registerOrGet() → Outbox → qim.user.events
   → IdO Consumer → instMbrId 매핑 생성 → qim.sp.member.events → agency-adapter

❷ 탈퇴 시나리오
   IdO.withdrawUser() → Q-IM.withdraw() → Outbox → qim.user.events(USER_WITHDRAWN)
   → IdO Consumer → FeSession 무효화 → 유관기관 전파

❸ Outbox 장애 복구 검증
   Kafka 일시 중단 → FAILED 전환 → Kafka 복구 → relayFailedEvents() 재발행 → 정상 처리

❹ 장애 격리 검증
   smes-tipa-01 응답 지연 → IdO Circuit Breaker 개방 → Q-IM 정상 동작 확인
   (Q-IM Kafka 발행은 기관 장애와 무관하게 성공)
```

### Phase 3 — 운영 수준 안정화 (Q-IM + IdO 팀, 1주)

```
목표: 생산 배포 준비

❶ CI 키 로테이션 무중단 테스트
❷ Outbox 재시도 최대 한도(5회) 도달 시 DLQ 이관 + 운영 알림 자동화
❸ 수평 확장 시 Outbox 릴레이 중복 발행 방지 (Redis 분산 잠금)
❹ 감사 로그 적재 검증 (PII 미포함 확인)
❺ 운영 환경변수 K8s Secret 마이그레이션
❻ PII Soft Delete 정책 법무팀 결론 반영 (확정 시)
❼ 최종 E2E 테스트 (Q-IM 팀 + IdO 팀 + 유관기관 대표 1개)
```

---

## 부록 A — API 요청/응답 상세

### A.1 Q-IM 내부 API — 회원 등록 요청 (IdO → Q-IM)

```http
POST /api/v1/internal/users
X-Internal-Api-Key: {내부 시스템 키}
X-Correlation-Id: {uuid}
Content-Type: application/json

{
  "authResultId"    : "AUTH-20260510-000001",
  "identifierHash"  : "sha256hex...(64자)",
  "providerCode"    : "NICE",
  "authLevel"       : "LEVEL2",
  "rawCi"           : "CI 평문...",      ← Q-IM에서 AES-256-GCM 암호화 후 즉시 파기
  "rawName"         : "홍길동",           ← Q-IM에서 마스킹 후 저장
  "rawMobile"       : "01012345678",     ← Q-IM에서 마스킹 후 저장
  "nationalityType" : "DOMESTIC",
  "gender"          : "M",
  "birthYear"       : 1990,
  "correlationId"   : "uuid"
}
```

**응답** (신규: 201, 기존: 200):
```json
{
  "qimUserId"      : "018f3c7e-...(UUID v7)",
  "status"         : "ACTIVE",
  "nameMasked"     : "홍*동",
  "mobileMasked"   : "010****5678",
  "nationalityType": "DOMESTIC",
  "birthYear"      : 1990,
  "gender"         : "M",
  "isNew"          : true,
  "createdAt"      : "2026-05-10T10:00:00Z",
  "updatedAt"      : "2026-05-10T10:00:00Z"
}
```

> ⚠️ **CI 응답 미포함**: `qimUserId`와 마스킹된 정보만 반환. CI 원문 및 암호화 값 절대 미포함.

### A.2 Q-IM 이벤트 발행 — `qim.user.events` (Kafka)

> **Q-IM의 유일한 아웃바운드입니다. HTTP가 아닌 Kafka 이벤트입니다.**  
> IdO Consumer가 이 이벤트를 수신하여 instMbrId 매핑 생성 및 유관기관 전파를 처리합니다.

```json
// 이벤트 타입: USER_UPDATED (신규 가입 시)
// 토픽: qim.user.events | 파티션 키: qimUserId
{
  "eventId"      : "018f3c7e-...(UUID v7)",
  "eventType"    : "USER_UPDATED",
  "sourceSystem" : "q-im",
  "correlationId": "uuid",
  "qimUserId"    : "018f3c7e-...",
  "eventVersion" : 1,
  "status"       : "ACTIVE",
  "reason"       : "USER_REGISTERED",
  "needsSync"    : false,
  "occurredAt"   : "2026-05-10T10:00:00Z"
}
```

```json
// 이벤트 타입: USER_WITHDRAWN (탈퇴 시)
{
  "eventId"      : "018f3c7e-...",
  "eventType"    : "USER_WITHDRAWN",
  "sourceSystem" : "q-im",
  "correlationId": "uuid",
  "qimUserId"    : "018f3c7e-...",
  "eventVersion" : 3,
  "status"       : "WITHDRAWN",
  "reason"       : "USER_REQUEST",
  "needsSync"    : true,
  "occurredAt"   : "2026-05-10T10:05:00Z"
}
```

**Outbox 발행 보장 흐름**:
```
[Q-IM DB 트랜잭션]
  INSERT qim_user ...
  INSERT outbox (status=PENDING, payload=UserEvent JSON)
  COMMIT
     │
     ▼ [500ms 스케줄러]
  relayPendingEvents()
     │──► kafkaTemplate.send("qim.user.events", qimUserId, event)
     │      ├─ 성공: UPDATE outbox SET status=PUBLISHED
     │      │        + 스냅샷 트리거 (10이벤트마다)
     │      └─ 실패: UPDATE outbox SET status=FAILED, retry_count+1
     │
     ▼ [30초 스케줄러]
  relayFailedEvents()  ← retry_count < 5 인 FAILED 재처리
     └─ maxRetry 도달 시 영구 FAILED → 운영팀 수동 조치
```

### A.3 DI 조회/생성 (IdO → Q-IM)

```http
GET /api/v1/internal/users/{qimUserId}/di?agencyCode=smes-sbiz-01
X-Internal-Api-Key: {내부 키}
```

```json
{
  "qimUserId"  : "018f3c7e-...",
  "agencyCode" : "smes-sbiz-01",
  "di"         : "Base64URL(HMAC-SHA256(smes-sbiz-01:018f3c7e-..., diSecret))",
  "isNew"      : false
}
```

---

## 부록 B — DB 스키마 현황

### B.1 핵심 테이블 구조

```sql
-- 회원 원장 (마스터)
qim_user (
    qim_user_id         VARCHAR(36) PK,     -- UUID v7
    identifier_hash     VARCHAR(64) UNIQUE,  -- SHA-256(rawCi), 검색 인덱스
    status              VARCHAR(20),         -- ACTIVE / SUSPENDED / WITHDRAWN
    provider_code       VARCHAR(20),         -- NICE / OACX
    auth_level          VARCHAR(20),
    event_version       INT DEFAULT 0,       -- Kafka 이벤트 버전 (낙관적 잠금 기준)
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    withdrawn_at        TIMESTAMP,           -- 탈퇴 시각 (Soft Delete 정책 도입 시 활용)
    withdrawal_reason   VARCHAR(100)
)

-- PII 프로필 (별도 테이블 — 탈퇴 시 NULL 처리 또는 Soft Delete)
user_profile (
    qim_user_id         VARCHAR(36) PK FK,
    ci                  VARCHAR(512),        -- AES-256-GCM 암호화: v{n}.{iv}.{ct}
    name_masked         VARCHAR(50),         -- "홍*동"
    mobile_masked       VARCHAR(20),         -- "010****5678"
    nationality_type    VARCHAR(20),
    gender              CHAR(1),
    birth_year          SMALLINT,
    di_map              TEXT                 -- JSON: {"agencyCode": "DI값", ...}
)

-- 인증 수단 매핑
auth_mean_mapping (
    id                  BIGINT PK,
    qim_user_id         VARCHAR(36) FK,
    identifier_hash     VARCHAR(64),         -- 인덱스 있음 (V3 추가)
    provider_code       VARCHAR(20),
    auth_result_id      VARCHAR(100),
    created_at          TIMESTAMP
)

-- 상태 변경 이력
user_status_history (
    id                  BIGINT PK,
    qim_user_id         VARCHAR(36),
    old_status          VARCHAR(20),
    new_status          VARCHAR(20),
    changed_by          VARCHAR(100),
    reason              VARCHAR(200),
    created_at          TIMESTAMP
)

-- Kafka Outbox (Transactional Outbox Pattern)
outbox (
    id                  BIGINT PK,
    aggregate_type      VARCHAR(50),
    aggregate_id        VARCHAR(36),
    event_type          VARCHAR(50),
    payload             TEXT,                -- JSON (UserEvent)
    status              VARCHAR(20),         -- PENDING / PUBLISHED / FAILED
    retry_count         INT DEFAULT 0,       -- 최대 5회 재시도
    error_message       TEXT,               -- 실패 시 실제 예외 메시지 저장
    created_at          TIMESTAMP,
    published_at        TIMESTAMP
)

-- CI 암호화 키 버전 메타 (V3 추가)
crypto_key_version (
    id                  INT PK,
    version             INT UNIQUE,          -- 1, 2, ...
    is_current          BOOLEAN,
    created_at          TIMESTAMP,
    description         VARCHAR(200)
)

-- Snapshot 발행 이력 (중복 방지)
snapshot_meta (
    snapshot_id         VARCHAR(36) PK,      -- UUID v7
    qim_user_id         VARCHAR(36),
    snapshot_version    BIGINT,
    topic               VARCHAR(100),
    status              VARCHAR(20),         -- PUBLISHED / FAILED
    UNIQUE (qim_user_id, snapshot_version)   -- 중복 발행 방지
)
```

---

## 부록 C — 환경변수 목록

### C.1 Q-IM 서비스 환경변수

| 환경변수 | 필수 | 설명 | 예시 형식 |
|---------|------|------|---------|
| `QIM_CI_AES_KEY_V1` | ✅ | CI 암호화 키 v1 (32바이트 Base64) | `Base64(32bytes)` |
| `QIM_CI_AES_KEY_V2` | 로테이션 시 | CI 암호화 키 v2 | 동일 |
| `QIM_CI_CURRENT_VERSION` | ✅ | 현재 사용 키 버전 (`1` 또는 `2`) | `1` |
| `QIM_DI_SECRET` | ✅ | DI HMAC-SHA256 시크릿 (최소 32자) | `임의 난수 문자열` |
| `QIM_INTERNAL_API_KEY` | ✅ | IdO → Q-IM 내부 API 검증 키 | `임의 난수 문자열` |
| `SPRING_DATASOURCE_URL` | ✅ | MariaDB 연결 URL | `jdbc:mariadb://localhost:3306/qim` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | ✅ | Kafka 브로커 주소 | `localhost:9092` |

> **v1.0.0 대비 변경**: `QIM_IDO_SP_URL`, `QIM_IDO_API_KEY`, `QIM_IDO_AES_SHARED_KEY` 환경변수 제거.  
> Q-IM이 IdO에 HTTP 직접 호출하지 않으므로 이 환경변수들은 불필요합니다.

### C.2 운영 환경 보안 요구사항

```
□ 모든 Secret 계열 환경변수는 K8s Secret / Vault에서 주입
□ application.yml에 하드코딩 절대 금지
□ 기본값(default) 사용 금지 — 운영 환경에서 미설정 시 앱 기동 실패 처리
□ CI 키는 반드시 32바이트 (256bit) 이상 — 기동 시 자동 검증
□ DI Secret은 최소 32자 이상, 충분한 엔트로피 보장
□ QIM_INTERNAL_API_KEY는 Q-IM과 IdO 팀 간 안전한 채널(Vault)로 교환
```

---

*이 문서는 `docs/qim-ido-integration-architecture.md`, `docs/qim-sp-receiver-api-spec.md` 및 현재 구현된 `q-im` 모듈 소스코드를 기반으로 작성되었습니다.*  
*v1.0.0 최초 작성: 2026-05-10 | v1.1.0 수정: 2026-05-10*
