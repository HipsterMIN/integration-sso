# Q-IM 개발 가이드 — Q-IM 팀 내부 정본

> **문서 분류**: 내부 개발 가이드 (Internal Development Guide)  
> **버전**: v1.0.0  
> **최종 수정**: 2026-05-10  
> **대상 독자**: Q-IM 개발팀  
> **배포 범위**: Q-IM 팀 내부 한정 — 외부 기관 미공개  
> **근거 문서**:  
> - `docs/qim-ido-integration-architecture.md` (ADR-001 — IdO 완전 중재 패턴)  
> - `docs/qim-sp-receiver-api-spec.md` (Q-IM SP 수신 API 명세서 v1.0.0)  
> - Q-IM 유관기관 SP 개발자 API 명세서 v1.52  
> - 현재 구현된 `q-im` 모듈 소스 코드 기준

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

Q-IM 팀이 시연을 위해 직접 유관기관에 `POST /api/ciw-im/member/register` (MEMBER_REGISTER) 를 발송하고 있는 것을 확인했습니다. **이 직접 호출 방식은 최종 아키텍처가 아닙니다.** 테스트 시연이 완료된 후에는 이 역할을 **IdO가 완전히 대리**하게 됩니다.

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
❌ CI 평문을 API 응답에 포함하는 것
❌ qimUserId를 유관기관 API에 직접 노출하는 것
❌ 유관기관 정보시스템에 직접 HTTP 호출하는 것 (→ IdO 위임)
❌ 기관별 비즈니스 로직을 Q-IM 내부에 구현하는 것
❌ MEMBER_REGISTER / MEMBER_QUERY / MEMBER_WITHDRAW를 유관기관에 직접 발송하는 것
```

### 2.3 Q-IM이 해야 할 것 (핵심 책임)

```
✅ 회원 원장 관리: 등록(Upsert), 상태 변경, 탈퇴 처리
✅ CI AES-256-GCM 암호화 저장 및 키 버전 관리
✅ PII 마스킹: nameMasked, mobileMasked
✅ DI 생성: HMAC-SHA256(agencyCode:qimUserId, diSecret)
✅ identifierHash 저장: SHA-256(rawCi)
✅ Transactional Outbox → Kafka 이벤트 발행 (qim.user.events, qim.user.snapshot)
✅ IdO에서 호출하는 내부 API 유지 (/api/v1/internal/**)
✅ IdO SP 수신 API 3종에 대한 "SP 호출" 기능 구현 (Q-IM → IdO 아웃바운드)
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

- `smes-tipa-01` (통합플랫폼 중소벤처24): **500 FAIL** — 현재 해당 기관 연동 실패 상태
- 이 직접 발송 구조는 **테스트 시연용 임시 구조**입니다

### 3.2 최종 아키텍처에서 동일한 흐름이 어떻게 달라지는가

```
현재 (임시 구조):
  Q-IM ──직접──► smes-tipa-01 (500 FAIL)
  Q-IM ──직접──► smes-sbiz-01 (200 OK)
  Q-IM ──직접──► smes-biz24-01 (200 OK)

최종 아키텍처:
  Q-IM ──► IdO (POST /api/qim/sp/v1/member/register) ─► Kafka ─► agency-adapter ─► smes-tipa-01
                                                                                   ─► smes-sbiz-01
                                                                                   ─► smes-biz24-01
```

**핵심 차이점**:
- Q-IM은 `IdO` 하나에만 MEMBER_REGISTER를 발송합니다 (SP 1개만 인식)
- IdO가 내부적으로 Kafka를 통해 모든 유관기관에 비동기 전파합니다
- `smes-tipa-01` 500 FAIL 같은 개별 기관 장애는 IdO Circuit Breaker가 격리합니다
- Q-IM은 기관별 장애에 영향받지 않습니다

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
│  │  Q-IM SP 수신 API (Q-IM → IdO 아웃바운드 수신)                 │  │
│  │  POST /api/qim/sp/v1/member/query     ← MEMBER_QUERY 수신    │  │
│  │  POST /api/qim/sp/v1/member/register  ← MEMBER_REGISTER 수신 │  │
│  │  POST /api/qim/sp/v1/member/withdraw  ← MEMBER_WITHDRAW 수신 │  │
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
        ▼  agency-adapter Consumer
   smes-tipa-01 / smes-sbiz-01 / smes-biz24-01 ...
        (Circuit Breaker 격리 — 개별 기관 장애가 Q-IM에 영향 없음)

                                  │
┌─────────────────────────────────┼───────────────────────────────────┐
│                 Q-IM (port 8082) [내부 격리 — 정본]                   │
│                                 │                                   │
│  IdO가 호출하는 내부 API            ▲                                 │
│  /api/v1/internal/users/**  ◄───┘  (IdO → Q-IM HTTP REST)          │
│  /api/v1/internal/member/**                                         │
│  /api/v1/users/{qimUserId}    (QimStatusController)                 │
│                                                                     │
│  Q-IM → IdO 아웃바운드 (MEMBER_REGISTER 등)                           │
│  POST {ido-host}/api/qim/sp/v1/member/register  ──────────────────► │
│                                                                     │
│  Kafka 발행 (이벤트 스트림)                                            │
│  qim.user.events ─────────────────────────────────────────────────► │
│  qim.user.snapshot ───────────────────────────────────────────────► │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 통신 방향 요약

| 방향 | 경로 | 인증 | 설명 |
|------|------|------|------|
| **IdO → Q-IM (내부 API)** | `POST /api/v1/internal/users` 등 | `X-Internal-Api-Key` | 회원 등록/조회/탈퇴/DI 생성 |
| **IdO → Q-IM (상태 조회)** | `GET /api/v1/users/{qimUserId}` | `X-Internal-Api-Key` | FeSession 발급 전 상태 확인 |
| **Q-IM → IdO (SP 수신)** | `POST /api/qim/sp/v1/member/register` 등 | `X-API-Key` (Q-IM 발급) | Q-IM 아웃바운드 전파 |
| **Q-IM → Kafka** | `qim.user.events` | 내부 브로커 | 이벤트 스트림 발행 |
| **IdO → Kafka** | `qim.sp.member.events` | 내부 브로커 | 유관기관 전파 이벤트 |

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
| `/api/v1/internal/users/{qimUserId}` | DELETE | `UserController` | ✅ 완료 | 탈퇴 처리 (PII 즉시 삭제) |
| `/api/v1/internal/users/{qimUserId}/di` | GET | `UserController` | ✅ 완료 | 기관별 DI 조회/생성 |
| `/api/v1/internal/users/{qimUserId}/status-check` | GET | `UserController` | ✅ 완료 | 상태 조회 보조 |
| `/api/v1/internal/member/lookup-by-ci` | POST | `MemberLookupController` | ✅ 완료 | 암호화 CI → 사용자 조회 |
| `/api/v1/internal/member/lookup-by-hash` | GET | `MemberLookupController` | ✅ 완료 | identifierHash 직접 조회 |

**보안 요구사항**: 모든 내부 API는 `X-Internal-Api-Key` 헤더 검증 필수 (`InternalApiKeyInterceptor`)

### 5.2 Q-IM 상태 조회 API (IdO QimClient 호출) — **현재 구현 완료**

| 엔드포인트 | 메서드 | 구현 클래스 | 상태 | 설명 |
|-----------|--------|-----------|------|------|
| `/api/v1/users/{qimUserId}` | GET | `QimStatusController` | ✅ 완료 | IdO PolicyEngine → QimClientImpl 호출 |

> **주의**: `/api/v1/internal/users/{qimUserId}/status-check` (내부 API) 와 경로가 다릅니다.  
> `QimStatusController`는 별도 매핑입니다. 혼동하지 않도록 주의.

### 5.3 Q-IM → IdO SP 수신 API (Q-IM 아웃바운드) — **IdO가 구현, Q-IM이 호출**

> **Q-IM 팀이 구현할 필요 없습니다. IdO 팀이 수신 엔드포인트를 구현합니다.**  
> **Q-IM 팀은 이 엔드포인트를 "호출"하는 클라이언트 코드를 구현해야 합니다.**

| Q-IM 발신 이벤트 | IdO 수신 엔드포인트 | IdO 구현 상태 | Q-IM 클라이언트 상태 | 트리거 |
|---------------|----------------|------------|-----------------|--------|
| MEMBER_QUERY | `POST /api/qim/sp/v1/member/query` | 🔄 구현 중 | ⚠️ 미구현 | 전환 신청 전 조회 |
| MEMBER_REGISTER | `POST /api/qim/sp/v1/member/register` | 🔄 구현 중 | ⚠️ **우선 구현 필요** | 신규/전환 회원 저장 완료 후 |
| MEMBER_WITHDRAW | `POST /api/qim/sp/v1/member/withdraw` | 🔄 구현 중 | ⚠️ 미구현 | 탈퇴 확정 후 |

**Q-IM 팀이 구현해야 할 클라이언트 코드 (예시)**:
```java
// Q-IM 아웃바운드 클라이언트 — IdO SP 수신 API 호출
@Component
public class IdoSpClient {

    // IdO SP 수신 엔드포인트 URL
    // 로컬: http://localhost:8083/api/qim/sp/v1/member/register
    // 운영: https://ido.smes.go.kr/api/qim/sp/v1/member/register
    @Value("${qim.ido.sp-url}")
    private String idoSpBaseUrl;

    @Value("${qim.ido.api-key}")  // IdO가 Q-IM에 발급한 API Key
    private String apiKey;

    public SpRegisterResponse sendMemberRegister(SpRegisterRequest request) {
        // POST {idoSpBaseUrl}/member/register
        // Header: X-API-Key: {apiKey}
        // Header: Idempotency-Key: {uuid}
        // Header: X-Correlation-Id: {correlationId}
        // Body: {mbrNo, mbrUuid, regMode, encCi, mbrNm, phone, ...}
        // 응답: {instMbrId, registeredAt} → instMbrId 를 반드시 저장!
    }
}
```

> **instMbrId 저장 필수**: IdO가 MEMBER_REGISTER 응답으로 반환하는 `instMbrId`를 Q-IM이 내부적으로 보관해야 합니다. 이후 MEMBER_QUERY, MEMBER_WITHDRAW 요청 시 Q-IM이 `mbrId = instMbrId`를 포함하여 IdO를 호출합니다.

### 5.4 Q-IM 외부 수신 API (외부 → Q-IM) — **구현 대상**

| 엔드포인트 | 메서드 | 호출자 | 상태 | 설명 |
|-----------|--------|--------|------|------|
| `/api/ext/provision/enterprises` | POST | 중기원패스(→IdO→Q-IM) | ✅ 동작 확인 | 기업 프로비저닝 |
| `/api/ext/v1/member/register` | POST | IdO | 🔄 확인 필요 | IdO → Q-IM 회원 등록 요청 |
| `/api/ext/v1/member/query` | POST | IdO | 🔄 확인 필요 | IdO → Q-IM 회원 조회 요청 |
| `/api/ext/v1/member/withdraw` | POST | IdO | 🔄 확인 필요 | IdO → Q-IM 탈퇴 요청 |

> **이 API들은 Q-IM이 직접 유관기관과 통신하는 채널이 아닙니다.**  
> 항상 IdO를 거쳐서만 Q-IM에 도달합니다.

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
│  PII 마스킹 / GDPR §17 삭제                          ✅ 완료      │
│  Kafka Outbox 이벤트 발행                            ✅ 완료      │
│  회원 상태 이력 관리                                   ✅ 완료      │
│                                                                 │
│  IdO SP 수신 API 호출 클라이언트 구현                  ⚠️ 미구현     │
│    - IdoSpClient (MEMBER_REGISTER 호출)                          │
│    - IdoSpClient (MEMBER_QUERY 호출)                             │
│    - IdoSpClient (MEMBER_WITHDRAW 호출)                          │
│    - instMbrId 저장 및 매핑 관리                                   │
│                                                                 │
│  encCi 생성: IdO AES 공유키로 CI 암호화하여 전송       ⚠️ 미구현     │
│    (Q-IM이 IdO에 보낼 때 IdO 공유키로 암호화 필요)                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      IdO 팀 개발 범위                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  SP 수신 API 3종 구현                                🔄 구현 중    │
│    POST /api/qim/sp/v1/member/query                             │
│    POST /api/qim/sp/v1/member/register                          │
│    POST /api/qim/sp/v1/member/withdraw                          │
│                                                                 │
│  instMbrId 매핑 생성/저장 (ido.inst_mbr_id_mapping)  🔄 구현 중    │
│  Idempotency Store (ido.sp_receiver_idempotency)    🔄 구현 중    │
│  AES 공유키 복호화 (encCi → identifierHash)          🔄 구현 중    │
│  Kafka 발행 (qim.sp.member.events)                  🔄 구현 중    │
│  유관기관 agency-adapter Consumer                    🔄 구현 중    │
│                                                                 │
│  Q-IM SP 클라이언트 등록 (Q-IM 관리 콘솔)              ⏳ 협의 필요  │
│  API Key 교환 (Q-IM ↔ IdO 양방향)                   ⏳ 협의 필요  │
│  AES 공유키 교환 (encCi 암복호화용)                   ⏳ 협의 필요  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 상세 책임 분담표

| 기능 | Q-IM 팀 | IdO 팀 | 우선순위 |
|------|---------|--------|---------|
| 회원 원장 CRUD | ✅ 담당 | — | P0 |
| CI 암호화 (Q-IM 내부 키) | ✅ 담당 | — | P0 |
| CI 암호화 (IdO 공유키 — encCi 생성) | ✅ 담당 (신규 구현 필요) | AES 공유키 제공 | P0 |
| DI 생성 | ✅ 담당 | — | P0 |
| PII 마스킹/삭제 | ✅ 담당 | — | P0 |
| Kafka 이벤트 발행 (qim.user.events) | ✅ 담당 | Consumer 구현 | P0 |
| IdO SP 수신 API 구현 | — | ✅ 담당 | P0 |
| Q-IM → IdO 아웃바운드 클라이언트 | ✅ 담당 (신규 구현 필요) | 엔드포인트 제공 | P0 |
| instMbrId 저장 | ✅ 담당 (Q-IM 내 매핑 보관) | ✅ 담당 (IdO 매핑 원본) | P0 |
| 유관기관 직접 연동 | ❌ 금지 | ✅ 담당 | — |
| 기관별 비즈니스 로직 | ❌ 금지 | ✅ 담당 | — |
| Circuit Breaker (기관별 장애 격리) | — | ✅ 담당 | P1 |
| API Key 관리 (운영) | ✅ Q-IM 콘솔 관리 | ✅ IdO 키 보관 | P0 |
| AES 공유키 교환 | ✅ Q-IM 측 발급/제공 | ✅ IdO 측 보관/복호화 | P0 |

---

## 7. 현재 구현 상태 상세

### 7.1 구현 완료 — Q-IM 내부 API

**`UserController.java`** — 7개 내부 엔드포인트 완전 구현

```java
// 인증: X-Internal-Api-Key 헤더 검증 (InternalApiKeyInterceptor)
// 기본 경로: /api/v1/internal/users

POST   /api/v1/internal/users                         → registerOrGet()   // Upsert, isNew로 201/200 구분
GET    /api/v1/internal/users/{qimUserId}             → getUser()         // 전체 프로필 반환 (CI 미포함)
GET    /api/v1/internal/users/by-hash                 → getUserByHash()   // identifierHash 기반
PATCH  /api/v1/internal/users/{qimUserId}/status      → updateStatus()    // ACTIVE/SUSPENDED 전환
DELETE /api/v1/internal/users/{qimUserId}             → withdraw()        // PII 즉시 삭제
GET    /api/v1/internal/users/{qimUserId}/di          → getDi()           // 기관별 DI 조회/생성
GET    /api/v1/internal/users/{qimUserId}/status-check → getUserStatus()  // 상태만 빠르게 조회
```

**`MemberLookupController.java`** — CI 기반 조회

```java
POST /api/v1/internal/member/lookup-by-ci   // encCi 수신 → 복호화 → identifierHash → 사용자 조회
GET  /api/v1/internal/member/lookup-by-hash // identifierHash 직접 조회
```

**`QimStatusController.java`** — IdO QimClientImpl 전용

```java
GET /api/v1/users/{qimUserId}
// 응답: { "qimUserId": "...", "status": "ACTIVE|SUSPENDED|WITHDRAWN", "updatedAt": "..." }
// → IdO PolicyEngineImpl이 이 경로를 호출함
```

### 7.2 구현 완료 — 핵심 서비스 레이어

**`UserRegistrationServiceImpl.java`**:
- `registerOrGet()`: identifierHash 기반 Upsert. UUID v7 사용 (UUID v4 금지)
- `updateStatus()`: 상태 변경 + 이력 기록 + Kafka Outbox 발행
- `withdraw()`: PII 즉시 삭제(deletePii) + GDPR §17 준수 + Outbox 발행

**`CiCryptoServiceImpl.java`**:
- AES-256-GCM 암호화. 출력: `v{n}.{base64url(iv)}.{base64url(ciphertext+tag)}`
- 키 버전 관리: `QIM_CI_AES_KEY_V1`, `QIM_CI_AES_KEY_V2`, `QIM_CI_CURRENT_VERSION`
- `isEncrypted()`: `^v\d+\..+\..+$` 패턴 판별

**`DiGenerationService.java`**:
- `HMAC-SHA256(agencyCode:qimUserId, diSecret)` → Base64URL
- `di_map` JSON에 `{agencyCode: DI}` 형태로 저장
- 기관별 독립 DI — 기관 간 DI 공유 절대 금지

### 7.3 미구현 — Q-IM 팀이 구현해야 할 항목

```
❶ IdO SP 수신 API 호출 클라이언트 (IdoSpClient)
   - MEMBER_REGISTER: 회원 저장 완료 후 IdO에 통보
   - MEMBER_QUERY: 전환 신청 전 IdO에 조회
   - MEMBER_WITHDRAW: 탈퇴 확정 후 IdO에 통보
   - instMbrId 응답값 저장 로직

❷ encCi 생성 로직 (IdO AES 공유키 기반)
   - Q-IM이 IdO에 MEMBER_REGISTER 발송 시 CI를 IdO AES 공유키로 암호화
   - IdO 내부 AES 키(QIM_CI_AES_KEY_*)와 다른 별도 공유키 사용

❸ Q-IM 관리 콘솔 SP 등록
   - IdO를 SP로 등록 (clientId 발급)
   - API Key 발급 및 IdO 팀에 전달
   - AES 공유키 교환
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
| **PII 즉시 삭제 검증** | ✅ 구현됨 | `deletePii()` — `user_profile` 컬럼 NULL 처리 확인 |
| **UUID v7 사용** | ✅ 구현됨 | `UuidV7.generate()` — UUID v4 혼용 주의 |
| **Transactional Outbox 정합성** | ✅ 구현됨 | `publishInTx()` — 동일 트랜잭션 내 보장 확인 |
| **identifierHash 인덱스** | ✅ V3 migration | `auth_mean_mapping.identifier_hash` 인덱스 존재 확인 |

### 8.2 🟠 P1 — 1개월 내 필수

| 항목 | 설명 |
|------|------|
| **Resilience4j Rate Limiter** | `/api/v1/internal/**` 엔드포인트에 Rate Limit 적용. 오남용 방지 |
| **CI 키 로테이션 무중단 처리** | `QIM_CI_CURRENT_VERSION` 변경 시 구버전 키로 복호화 → 신버전 키로 재암호화 배치 |
| **감사 로그** | 회원 등록/탈퇴/상태 변경 시 `who/what/when/why` 감사 로그 적재. PII 미포함 필수 |
| **Flyway 마이그레이션 테스트** | `H2 in-memory`로 `V1`, `V3` 스키마 통합 테스트. CI/CD 파이프라인 포함 |
| **Kafka Consumer DLQ 처리** | `qim.user.events` 발행 실패 시 DLQ(`qim.user.events.dlq`) 이관 + 알림 |
| **`crypto_key_version` 테이블 동기화** | `V3` 마이그레이션으로 추가된 테이블. 실제 키 버전과 DB 메타가 일치하는지 검증 |

### 8.3 🟡 P2 — 분기 내 필요

| 항목 | 설명 |
|------|------|
| **KMS 연동** | `QIM_CI_AES_KEY_*` 를 환경변수 평문에서 AWS KMS / HashiCorp Vault로 이관 |
| **OpenTelemetry 트레이싱** | `correlationId`를 OTel `trace_id`로 연결. Jaeger/Grafana 연동 |
| **수평 확장 검증** | 복수 Q-IM 인스턴스 실행 시 Outbox 릴레이 중복 방지 (분산 잠금 필요) |
| **데이터 보존 정책** | 탈퇴 후 7년 보존 (법적 의무) vs GDPR §17 즉시 삭제의 균형 — 법무팀 협의 필요 |
| **Snapshot 토픽 관리** | `qim.user.snapshot` 컴팩션 정책 및 소비자 복구 절차 문서화 |

### 8.4 보안 강화 체크리스트

```
□ CI 평문이 절대 로그에 출력되지 않는지 확인
  → log.info("rawCi={}", req.getRawCi())  ← 이런 코드 절대 금지

□ API 응답에 CI 컬럼이 포함되지 않는지 확인
  → UserResponse에 'ci' 필드 없음 확인 ✅

□ 내부 API가 외부 방화벽 뒤에 있는지 확인
  → /api/v1/internal/** 를 외부 LB가 라우팅하지 않도록 설정

□ DI Secret이 환경변수로 주입되는지 확인
  → application.yml에 하드코딩 절대 금지

□ qimUserId가 외부 API 응답에 직접 노출되지 않는지 확인
  → 외부에는 instMbrId (= qimUserId) 만 IdO를 통해 간접 노출
```

---

## 9. Q-IM ↔ IdO 협의 필요 항목

> **다음 항목은 Q-IM 팀과 IdO 팀이 함께 결정해야 합니다. 이 결정 없이는 SP 수신 API 연동을 시작할 수 없습니다.**

| 우선순위 | 항목 | 현재 상황 | 결정 필요 내용 |
|---------|------|-----------|---------------|
| 🔴 P0 | **encCi 암호화 알고리즘** | AES-256-CBC 가정 | 정확한 모드(CBC/GCM), 패딩, IV 전달 방식 확정 필요 |
| 🔴 P0 | **AES 공유키 교환** | 미진행 | Q-IM 팀 → IdO 팀에 공유키 전달 방식, 관리 절차 |
| 🔴 P0 | **API Key 교환 (양방향)** | 미진행 | Q-IM이 IdO를 호출할 때 쓸 키, IdO가 Q-IM을 호출할 때 쓸 키 |
| 🔴 P0 | **instMbrId 정책 확인** | `instMbrId = qimUserId` 설계 | Q-IM이 별도 형식을 요구하는지 확인 |
| 🔴 P0 | **SP endpoint URL 확정** | `/api/qim/sp/v1/member/*` 예정 | Q-IM 관리 콘솔 등록 전 URL 확정 |
| 🟡 P1 | **Idempotency-Key 보관 기간** | 7일 예정 | Q-IM 측 재판단 기간과 맞춰야 함 |
| 🟡 P1 | **412 PROV_LOCKED 재시도 상한** | IdO 3회 재시도 예정 | Q-IM 분산 잠금 최대 유지 시간 확인 필요 |
| 🟡 P1 | **AES 공유키 로테이션 정책** | 미결정 | 회전 주기, 유예기간, 무중단 교체 방식 |

---

## 10. 개발 로드맵

### Phase 1 — Q-IM 아웃바운드 클라이언트 구현 (Q-IM 팀, 1주)

```
목표: Q-IM이 IdO SP 수신 API를 호출할 수 있도록

❶ IdO SP 호출 클라이언트 구현
   - IdoSpClient (Spring HTTP Client / WebClient)
   - MEMBER_REGISTER 호출 및 instMbrId 저장
   - MEMBER_QUERY 호출
   - MEMBER_WITHDRAW 호출

❷ encCi 생성 유틸 구현
   - IdO AES 공유키로 CI 암호화 (전송 시)
   - Q-IM 내부 저장용 암호화와 구분 필요

❸ instMbrId 저장 테이블 설계
   - qim_user ↔ inst_mbr_id 매핑 컬럼 또는 별도 테이블

❹ Q-IM 관리 콘솔에 IdO SP 등록
   - API Key 발급 → IdO 팀에 전달
   - Endpoint URL 등록
```

### Phase 2 — 통합 테스트 (Q-IM + IdO 팀 공동, 1주)

```
목표: Q-IM ↔ IdO 전체 흐름 E2E 검증

❶ 신규 가입 시나리오
   IdO.registerUser() → Q-IM.registerOrGet() → Q-IM.sendMemberRegister() → IdO 수신 → instMbrId 반환

❷ 탈퇴 시나리오
   사용자 탈퇴 → Q-IM.withdraw() → Q-IM.sendMemberWithdraw() → IdO 수신 → FeSession 무효화

❸ Idempotency 검증
   동일 Idempotency-Key 재전송 → 저장된 응답 재반환 확인

❹ 장애 격리 검증
   smes-tipa-01 응답 지연 → IdO Circuit Breaker 개방 → Q-IM 정상 동작 확인
```

### Phase 3 — 운영 수준 안정화 (Q-IM + IdO 팀, 1주)

```
목표: 생산 배포 준비

❶ Bean Validation 전 엔드포인트 적용
❷ 감사 로그 적재 검증
❸ CI 키 로테이션 무중단 테스트
❹ Kafka Consumer 장애 시나리오 (DLQ 처리)
❺ 운영 환경변수 K8s Secret 마이그레이션
❻ 최종 E2E 테스트 (Q-IM 팀 + IdO 팀 + 유관기관 대표 1개)
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
  "qimUserId"     : "018f3c7e-...(UUID v7)",
  "status"        : "ACTIVE",
  "nameMasked"    : "홍*동",
  "mobileMasked"  : "010****5678",
  "nationalityType": "DOMESTIC",
  "birthYear"     : 1990,
  "gender"        : "M",
  "isNew"         : true,
  "createdAt"     : "2026-05-10T10:00:00Z",
  "updatedAt"     : "2026-05-10T10:00:00Z"
}
```

> ⚠️ **CI 응답 미포함**: `qimUserId`와 마스킹된 정보만 반환. CI 원문 및 암호화 값 절대 미포함.

### A.2 Q-IM 아웃바운드 — MEMBER_REGISTER (Q-IM → IdO)

```http
POST /api/qim/sp/v1/member/register
X-API-Key: {Q-IM이 IdO에 발급한 API Key}
Idempotency-Key: {uuid — 재전송 시 동일 값 유지}
X-Correlation-Id: {uuid}
Content-Type: application/json

{
  "mbrNo"     : "QIM-20260510-000001",
  "mbrUuid"   : "018f3c7e-...",
  "regMode"   : "NEW",
  "encCi"     : "IdO AES 공유키로 암호화된 CI",  ← Q-IM 내부 암호화 키와 다른 키!
  "mbrNm"     : "홍길동",
  "phone"     : "01012345678",
  "email"     : "hong@example.com",
  "birthDate" : "19900101",
  "gender"    : "M",
  "nationality": "DOMESTIC",
  "notiPrefs" : { "sms": true, "email": true, "push": false }
}
```

**응답 (IdO 반환)**:
```json
{
  "success"  : true,
  "data"     : {
    "instMbrId"   : "018f3c7e-...(= qimUserId)",
    "registeredAt": "2026-05-10T10:00:00+09:00"
  },
  "message"  : "등록 완료"
}
```

> **Q-IM 팀 주의**: `instMbrId` 값을 Q-IM 내부에 반드시 저장해야 합니다. 이후 MEMBER_QUERY/MEMBER_WITHDRAW 요청 시 `mbrId = instMbrId`를 포함해야 합니다.

### A.3 DI 조회/생성 (IdO → Q-IM)

```http
GET /api/v1/internal/users/{qimUserId}/di?agencyCode=smes-sbiz-01
X-Internal-Api-Key: {내부 키}
```

```json
{
  "qimUserId"  : "018f3c7e-...",
  "agencyCode" : "smes-sbiz-01",
  "di"         : "Base64URL(HMAC-SHA256...)",
  "isNew"      : false
}
```

---

## 부록 B — DB 스키마 현황

### B.1 핵심 테이블 구조

```sql
-- 회원 원장 (마스터)
qim_user (
    qim_user_id         VARCHAR(36) PK,   -- UUID v7
    identifier_hash     VARCHAR(64) UNIQUE, -- SHA-256(rawCi), 검색 인덱스
    status              VARCHAR(20),        -- ACTIVE / SUSPENDED / WITHDRAWN
    provider_code       VARCHAR(20),        -- NICE / OACX
    auth_level          VARCHAR(20),
    event_version       INT DEFAULT 0,      -- Kafka 이벤트 버전
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    withdrawn_at        TIMESTAMP,
    withdrawal_reason   VARCHAR(100)
)

-- PII 프로필 (별도 테이블 — 탈퇴 시 NULL 처리)
user_profile (
    qim_user_id         VARCHAR(36) PK FK,
    ci                  VARCHAR(512),       -- AES-256-GCM 암호화: v{n}.{iv}.{ct}
    name_masked         VARCHAR(50),        -- "홍*동"
    mobile_masked       VARCHAR(20),        -- "010****5678"
    nationality_type    VARCHAR(20),
    gender              CHAR(1),
    birth_year          SMALLINT,
    di_map              TEXT                -- JSON: {"agencyCode": "DI값", ...}
)

-- 인증 수단 매핑
auth_mean_mapping (
    id                  BIGINT PK,
    qim_user_id         VARCHAR(36) FK,
    identifier_hash     VARCHAR(64),        -- 인덱스 있음 (V3 추가)
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
    payload             TEXT,               -- JSON
    status              VARCHAR(20),        -- PENDING / PUBLISHED
    retry_count         INT DEFAULT 0,
    created_at          TIMESTAMP,
    published_at        TIMESTAMP
)

-- CI 암호화 키 버전 메타 (V3 추가)
crypto_key_version (
    id                  INT PK,
    version             INT UNIQUE,         -- 1, 2, ...
    is_current          BOOLEAN,
    created_at          TIMESTAMP,
    description         VARCHAR(200)
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
| `QIM_IDO_SP_URL` | ✅ | IdO SP 수신 API 기본 URL | `https://ido.smes.go.kr/api/qim/sp/v1` |
| `QIM_IDO_API_KEY` | ✅ | Q-IM → IdO 호출 API Key | IdO 팀에서 발급 |
| `QIM_IDO_AES_SHARED_KEY` | ✅ | encCi 생성용 IdO AES 공유키 | IdO 팀에서 제공 |
| `SPRING_DATASOURCE_URL` | ✅ | MariaDB 연결 URL | `jdbc:mariadb://localhost:3306/qim` |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | ✅ | Kafka 브로커 주소 | `localhost:9092` |

### C.2 운영 환경 보안 요구사항

```
□ 모든 Secret 계열 환경변수는 K8s Secret / Vault에서 주입
□ application.yml에 하드코딩 절대 금지
□ 기본값(default) 사용 금지 — 운영 환경에서 미설정 시 기동 실패 처리
□ CI 키는 최소 32바이트 (256bit) 보장
□ DI Secret은 최소 32자 이상, 충분한 엔트로피 보장
```

---

*이 문서는 `docs/qim-ido-integration-architecture.md`, `docs/qim-sp-receiver-api-spec.md` 및 현재 구현된 `q-im` 모듈 소스코드를 기반으로 작성되었습니다.*  
*최종 수정: 2026-05-10 | 버전: v1.0.0*
