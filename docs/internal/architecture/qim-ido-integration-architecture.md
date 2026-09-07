# Q-IM ↔ IdO 연동 아키텍처 설계서

> **문서 분류**: 아키텍처 설계 (Architecture Design)  
> **버전**: v1.0.0  
> **최종 수정**: 2026-05-07  
> **근거 문서**:  
> - Q-IM 유관기관 SP 개발자 API 명세서 v1.52  
> - Q-IM 연동 갭 분석 및 개발 협의 요청서 v1.0  
> - 중기원패스 프로세스 설계서 v0.9  
> - 통합인증 플랫폼 EDA 마스터 아키텍처 설계서  
> **대상 독자**: 아키텍트, 백엔드 개발자, Q-IM 협의 담당자

---

## 목차

1. [배경 및 아키텍처 결정](#1-배경-및-아키텍처-결정)
2. [IdO 완전 중재 패턴 (Full Mediation Pattern)](#2-ido-완전-중재-패턴)
3. [연동 책임 경계](#3-연동-책임-경계)
4. [IdO → Q-IM 호출 흐름 (SP→Q-IM)](#4-ido--q-im-호출-흐름)
5. [Q-IM → IdO 아웃바운드 흐름 (Q-IM→SP)](#5-q-im--ido-아웃바운드-흐름)
6. [식별자 매핑 체계 (instMbrId / qimUserId / localMemberId)](#6-식별자-매핑-체계)
7. [보안 규약 (API Key / AES 공유키 / 헤더)](#7-보안-규약)
8. [멱등성 및 TCC 처리 규칙](#8-멱등성-및-tcc-처리-규칙)
9. [오류 처리 및 재시도 정책](#9-오류-처리-및-재시도-정책)
10. [EDA 기반 내부 전파 설계](#10-eda-기반-내부-전파-설계)
11. [Circuit Breaker 및 장애 대응](#11-circuit-breaker-및-장애-대응)
12. [Q-IM 팀과 합의해야 할 결정 항목](#12-q-im-팀과-합의해야-할-결정-항목)
13. [구현 로드맵](#13-구현-로드맵)

---

## 1. 배경 및 아키텍처 결정

### 1.1 문제 상황

| 구분 | 내용 |
|------|------|
| **Q-IM 명세 현실** | Q-IM v1.52는 SP(Service Provider)에게 수신 API 3종 구현을 강제하는 **외부 연동 계약서** 역할을 함 |
| **설계 정책** | Q-IM은 외부와 최대한 단절되어야 함. 유관기관과의 연계는 **IdO에 일임** |
| **개발 현황** | Q-IM 개발팀은 시연 준비로 정식 개발 여력 없음. PoC 코드베이스를 실운영 수준으로 격상하는 것이 목표 |
| **기존 자산** | Outbox, FeSession, AgencyMeta, PolicyEngine, NonOidcBroker 등 핵심 인프라 기구현 |

### 1.2 아키텍처 결정 (ADR-001): IdO 완전 중재 패턴

**Q-IM 명세가 요구하는 SP 역할(수신 API 구현)을 IdO가 대리 수행한다.**

```
Q-IM 명세서 관점:    SP = 중기원패스 유관기관 정보시스템
현재 아키텍처 결정:  SP = IdO (Q-IM과 외부 세계 사이의 완전 중재자)
```

**결정 근거**:
- Q-IM 설계 정책(외부 단절)을 지키면서도 Q-IM 명세를 충족
- IdO가 이미 보유한 AgencyMeta(기관 메타), PolicyEngine(정책), Outbox(EDA), FeSession 재사용
- Q-IM 개발팀 변경 최소화 (Q-IM은 IdO를 하나의 SP로만 인식)
- 외부 유관기관들은 Q-IM을 직접 알 필요 없이 IdO API만 사용

---

## 2. IdO 완전 중재 패턴

### 2.1 전체 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────┐
│                    외부 / 유관기관 영역                              │
│                                                                   │
│   idem-console ─────────┐  agency-A ────────────────────────┐      │
│   (React SPA)         │  agency-B  (유관기관 정보시스템)      │      │
│                       │  agency-C ────────────────────────┘      │
└───────────────────────┼──────────────────────────────────────────┘
                        │ BFF / 브로커 API          ↑ Handoff Ticket
                        ▼                          │
┌─────────────────────────────────────────────────────────────────┐
│                        IdO (port 8083)                           │
│                    [Q-IM의 SP 역할 대리]                           │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │ Q-IM SP 수신 API (Q-IM → IdO 아웃바운드)                  │    │
│  │  POST /api/qim/sp/v1/member/query                        │    │
│  │  POST /api/qim/sp/v1/member/register                     │    │
│  │  POST /api/qim/sp/v1/member/withdraw                     │    │
│  └──────────────────────────────┬──────────────────────────┘    │
│                                  │ 내부 처리 후 EDA 발행           │
│  ┌─────────────────────────────▼──────────────────────────┐    │
│  │ 핵심 인프라                                               │    │
│  │  Outbox → Kafka     AgencyMeta     PolicyEngine          │    │
│  │  FeSession/Redis    instMbrId 매핑  IdempotencyStore     │    │
│  └─────────────────────────────┬──────────────────────────┘    │
└────────────────────────────────┼────────────────────────────────┘
                                  │ Q-IM 호출 (SP→Q-IM)
                                  │ HTTP REST
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Q-IM (port 8082) [내부 격리]                      │
│   /api/ext/v1/member/register   /api/ext/v1/member/sync          │
│   /api/ext/v1/member/withdraw   /api/ext/v1/member/query         │
│                                                                   │
│   qim.user.events ──────────────────────────────────────────►   │
│   qim.conversion.events ─ Kafka ─► IdO Consumer                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 통신 방향 요약

| 방향 | 경로 | 프로토콜 | 설명 |
|------|------|---------|------|
| **IdO → Q-IM** | `ido.qim.base-url` | HTTP REST | 회원 등록/조회/상태 변경 (SP가 Q-IM 호출) |
| **Q-IM → IdO** | `ido.qim.sp-receiver-url` | HTTP REST | Q-IM 아웃바운드 (Q-IM이 SP 수신 API 호출) |
| **Q-IM → IdO** | Kafka `qim.user.events` | Kafka | 사용자 이벤트 스트림 (기존 구조 유지) |
| **IdO → 유관기관** | `ido.agency_meta.bridge_endpoint` | HTTP REST | Handoff Ticket 기반 SSO |

---

## 3. 연동 책임 경계

### 3.1 역할 분담표

| 역할 | 담당 | 비고 |
|------|------|------|
| Q-IM SP 클라이언트 등록 | **IdO 운영팀** | Q-IM 관리 콘솔에서 `clientId` 발급 받아야 함 |
| API Key 보관 | **IdO** (`ido.qim.api-key`) | K8s Secret / Vault 관리 |
| AES 공유키 보관 및 복호화 | **IdO** (`ido.qim.aes-shared-key`) | encCi 복호화 담당 |
| SP 수신 endpoint 3종 구현 | **IdO** | `QimSpReceiverController` |
| endpoint URL Q-IM 콘솔 등록 | **운영팀** | `https://{ido-host}/api/qim/sp/v1/member/{query\|register\|withdraw}` |
| instMbrId 생성 및 반환 | **IdO** | `qimUserId` = instMbrId (1:1 매핑) |
| 회원 정보 내부 전파 | **IdO** | Kafka `qim.sp.member.events` 발행 |
| 유관기관 동기화 | **IdO** | agency-stub / 실 기관 Adapter |
| Q-IM 비즈니스 로직 | **Q-IM** | IdO는 수신/전달만. 비즈니스 로직 미포함 |

### 3.2 Q-IM이 인식하는 IdO (SP 관점)

```yaml
# Q-IM 관리 콘솔 기준으로 IdO는 하나의 SP
clientId: idem-hub-sp
spName: 중기원패스 통합인증 플랫폼 IdO
endpoints:
  MEMBER_QUERY:    https://ido.smes.go.kr/api/qim/sp/v1/member/query
  MEMBER_REGISTER: https://ido.smes.go.kr/api/qim/sp/v1/member/register
  MEMBER_WITHDRAW: https://ido.smes.go.kr/api/qim/sp/v1/member/withdraw
status: READY
```

---

## 4. IdO → Q-IM 호출 흐름

### 4.1 회원 등록 흐름 (신규 가입 / 전환)

```
idem-console                    IdO                        Q-IM
    │                          │                           │
    │ POST /api/v1/conversion/  │                           │
    │     initiate              │                           │
    │──────────────────────────►│                           │
    │                          │ 1. ConversionSession 생성  │
    │                          │    (Redis TTL=600s)        │
    │                          │                           │
    │ [본인인증 완료]             │                           │
    │──────────────────────────►│                           │
    │                          │ 2. CI 복호화               │
    │                          │    identifierHash 생성     │
    │                          │                           │
    │                          │ 3. POST /api/ext/v1/member/│
    │                          │       register             │
    │                          │──────────────────────────►│
    │                          │                           │ 4. Q-IM 회원 저장
    │                          │◄──────────────────────────│
    │                          │ {mbrUuid, mbrNo, ...}     │
    │                          │                           │
    │                          │ 5. instMbrId 매핑 저장     │
    │                          │    (ido.inst_mbr_id_mapping)
    │                          │                           │
    │                          │ 6. Kafka 발행              │
    │                          │    qim.sp.member.events   │
    │◄──────────────────────────│                           │
    │ {qimUserId, feSessionId}  │                           │
```

### 4.2 IdO → Q-IM API 엔드포인트 (SP가 Q-IM 호출)

> Q-IM `QimClient.java`에서 호출. 현재 `getUserStatus()`만 구현됨 → 확장 필요.

```
# SP→Q-IM 방향 (IdO가 호출)
POST {qim.base-url}/api/ext/v1/member/register   # 회원 등록 요청
POST {qim.base-url}/api/ext/v1/member/query      # 회원 조회 요청
POST {qim.base-url}/api/ext/v1/member/withdraw   # 탈퇴 요청
GET  {qim.base-url}/api/v1/users/{qimUserId}     # 상태 조회 (기존 구현 ✅)
GET  {qim.base-url}/api/v1/users/by-hash         # 해시 조회  (기존 구현 ✅)
```

**공통 헤더 (IdO → Q-IM)**:

```http
X-API-Key: {ido.qim.api-key}
Content-Type: application/json
X-Correlation-Id: {correlationId}
Idempotency-Key: {UUID}   ← TCC phase ≠ auto 시 필수
X-Txn-Id: {txnId}         ← TCC commit/cancel 단계
```

---

## 5. Q-IM → IdO 아웃바운드 흐름

### 5.1 Q-IM이 IdO SP 수신 API를 호출하는 시나리오

| 시나리오 | Q-IM 발신 API | IdO 수신 endpoint | 트리거 |
|---------|--------------|-----------------|--------|
| 신규 가입 확정 | MEMBER_REGISTER 송신 | `POST /api/qim/sp/v1/member/register` | Q-IM이 회원 저장 후 SP에 통보 |
| 전환 신청 조회 | MEMBER_QUERY 송신 | `POST /api/qim/sp/v1/member/query` | 전환 전 기존 회원 여부 확인 |
| 탈퇴 전파 | MEMBER_WITHDRAW 송신 | `POST /api/qim/sp/v1/member/withdraw` | 이용자/운영자 탈퇴 확정 시 |

### 5.2 Q-IM 아웃바운드 수신 처리 흐름

```
Q-IM                          IdO (QimSpReceiverController)
  │                                      │
  │ POST /api/qim/sp/v1/member/register  │
  │ X-API-Key: {shared-key}              │
  │ Idempotency-Key: {uuid}              │
  │──────────────────────────────────────►
  │                                      │ 1. API Key 검증
  │                                      │ 2. Idempotency-Key 중복 확인
  │                                      │    (ido.sp_receiver_idempotency)
  │                                      │ 3. AES 공유키로 encCi 복호화
  │                                      │ 4. instMbrId 매핑 조회/생성
  │                                      │    (ido.inst_mbr_id_mapping)
  │                                      │ 5. Kafka 발행
  │                                      │    qim.sp.member.events
  │◄──────────────────────────────────────
  │ {success:true, data:{instMbrId:...}} │
```

### 5.3 멱등성 처리 규칙

```
동일 Idempotency-Key 재호출 → ido.sp_receiver_idempotency 테이블에서
  FOUND  → 저장된 응답 JSON 그대로 재반환 (DB 재처리 없음)
  NOT_FOUND → 신규 처리 후 결과 저장 (TTL=7일)
```

---

## 6. 식별자 매핑 체계

### 6.1 식별자 종류와 역할

| 식별자 | 소유자 | 의미 | 저장 위치 |
|--------|--------|------|----------|
| `qimUserId` | Q-IM | Q-IM 내부 UUID (master_member_uuid) | `qim.qim_user.qim_user_id` |
| `instMbrId` | **IdO** | Q-IM에 반환하는 SP 내부 식별자 = `qimUserId` | `ido.inst_mbr_id_mapping` |
| `identifierHash` | IdO/Q-IM | SHA-256(CI) — 복호화 불가 | `qim.auth_mean_mapping` |
| `localMemberId` | 유관기관 | 유관기관 자체 ID | agency 측 DB |
| `mbrUuid` | Q-IM | Q-IM이 발행하는 회원 UUID | `qim.qim_user` |

### 6.2 instMbrId 설계 원칙

**IdO는 `instMbrId = qimUserId`로 1:1 매핑한다.**

```
이유:
  - qimUserId가 이미 전역 유일 UUID이므로 별도 ID 체계 불필요
  - Q-IM이 후속 송수신에서 instMbrId를 매핑 기준으로 사용할 때
    IdO는 이를 그대로 qimUserId로 역매핑 가능
  - ido.inst_mbr_id_mapping 테이블을 통해 향후 확장 시 별도 ID 체계로 전환 가능
```

```sql
-- ido.inst_mbr_id_mapping
-- Q-IM이 발행한 mbrUuid 와 IdO의 qimUserId(=instMbrId) 매핑
SELECT inst_mbr_id, qim_user_id, mbrUuid, registered_at
FROM ido.inst_mbr_id_mapping
WHERE inst_mbr_id = :instMbrId;
```

### 6.3 식별자 흐름 다이어그램

```
본인인증(CI 획득)
      │
      ▼
identifierHash = SHA-256(CI)  ←─ IdO/Q-IM 공통 계산
      │
      ▼
Q-IM.registerUser(identifierHash)
      │ 반환
      ▼
qimUserId (UUID) ←─ Q-IM이 발행
      │
      ▼
IdO 저장:
  instMbrId = qimUserId   ← Q-IM 아웃바운드 응답에 포함
  mbrUuid = Q-IM 내부 UUID ← 별도 보관
      │
      ▼
Q-IM 후속 호출:
  instMbrId 포함 → IdO가 qimUserId로 역매핑
```

---

## 7. 보안 규약

### 7.1 API Key 운영 (Q-IM↔IdO)

```
발급 주체: Q-IM 운영팀 → IdO 운영팀에 전달
보관 위치: IdO application.yml → ido.qim.api-key (Vault/K8s Secret)
검증 방법: X-API-Key 헤더 값을 PBKDF2 해시로 비교
         (ido.agency_meta.api_key_hash 컬럼 활용)

Q-IM → IdO 방향: Q-IM이 IdO에 발급받은 키를 X-API-Key로 전송
IdO → Q-IM 방향: IdO가 Q-IM에서 발급받은 키를 X-API-Key로 전송
```

```java
// IdO SP 수신 API 검증 로직
@Component
public class QimApiKeyValidator {
    // ido.qim.inbound-api-key-hash: Q-IM이 IdO 호출 시 사용하는 키의 해시
    // ido.qim.api-key: IdO가 Q-IM 호출 시 사용하는 키
}
```

### 7.2 AES 공유키 (encCi 복호화)

```
목적: Q-IM이 CI 등 민감정보를 AES 암호화하여 전송
     IdO는 동일 공유키로 복호화

알고리즘: AES-256-CBC (Q-IM 명세 준수, 모드/패딩은 Q-IM 팀 확인 필요) ← [결정 필요 #1]
키 길이: 256-bit (32 bytes)
보관: ido.qim.aes-shared-key → Vault/K8s Secret
회전: Q-IM 운영팀이 주도, IdO 운영팀에 사전 통보 필요 ← [결정 필요 #2]
```

### 7.3 내부 서비스 인증 (IdO → Q-IM)

기존 `X-Internal-Sig` 헤더 체계 유지 (q-sign 방식과 동일):

```http
X-Internal-Caller: ido
X-Internal-Sig: HMAC-SHA256({timestamp}:{method}:{path}, signingKey)
X-Correlation-Id: {uuid}
```

### 7.4 헤더 전체 목록

| 헤더 | 방향 | 필수 | 설명 |
|------|------|------|------|
| `X-API-Key` | 양방향 | ✅ | 인증 키 |
| `X-Correlation-Id` | 양방향 | ✅ | 추적 ID |
| `Idempotency-Key` | IdO → Q-IM | TCC≠auto | 멱등 처리 키 |
| `X-Txn-Id` | IdO → Q-IM | TCC commit/cancel | 트랜잭션 ID |
| `X-QIM-Client-Id` | IdO → Q-IM | ✅ | Q-IM 등록 clientId |

---

## 8. 멱등성 및 TCC 처리 규칙

### 8.1 IdO → Q-IM 호출 시 TCC 전략

```
기본 전략: TCC phase = auto
이유:
  - IdO가 단일 오케스트레이터로서 전체 흐름 관리
  - prepare/commit/cancel 분리가 필요한 장거리 분산 트랜잭션은
    Outbox → Kafka → Consumer 패턴으로 대체
  - 단순 등록/탈퇴는 auto + Idempotency-Key 조합으로 충분

TCC prepare/commit/cancel 사용 시점:
  - 기업회원 전환 + 국세청 진위 확인 동시 수행 등 다단계 검증 필요 시
  - Phase 1 MVP에서는 auto 우선 적용
```

### 8.2 Idempotency-Key 생성 규칙

```
format: {correlationId}-{operationType}-{timestamp}
예시:   550e8400-e29b-41d4-a716-446655440000-REGISTER-1746615600

저장:
  IdO → Q-IM 방향: IdO가 생성하여 헤더에 포함
  Q-IM → IdO 방향: Q-IM이 생성, IdO는 ido.sp_receiver_idempotency에 저장

보관 기간: 7일 (Q-IM과 합의 필요 ← [결정 필요 #3])
```

### 8.3 412 PROV_LOCKED 처리

```
Q-IM 분산 잠금 점유 중일 때 412 반환
IdO 처리 정책:
  1. 250ms 대기 (Retry-After 헤더 참조)
  2. 동일 Idempotency-Key로 재시도
  3. 최대 3회 (설정: ido.qim.locked-retry-max=3)
  4. 3회 초과 시 Outbox에 PENDING으로 저장 → 비동기 재처리
```

### 8.4 409 낙관적 잠금 충돌 처리

```
Q-IM 409 충돌 수신 시:
  1. Q-IM에서 해당 회원 최신 ver 조회 (GET /api/v1/users/{qimUserId})
  2. 최신 ver으로 재요청
  3. 2회 이상 충돌 시 → DLQ(ido.handoff.events.dlq) 이관 + 알림
```

---

## 9. 오류 처리 및 재시도 정책

### 9.1 Q-IM 오류코드 ↔ IdO 내부 오류코드 매핑

| Q-IM 코드 | HTTP | IdO 내부 처리 | 설명 |
|-----------|------|--------------|------|
| `AUTH_INVALID_CREDENTIALS` | 401 | `IDO_QIM_AUTH_FAILED` | API Key 만료/오류 → 키 재발급 필요 |
| `AUTH_TOKEN_EXPIRED` | 401 | `IDO_QIM_AUTH_FAILED` | 토큰 만료 → 재인증 |
| `PROV_INPUT_INVALID` | 400 | `IDO_QIM_INVALID_REQUEST` | 입력값 오류 → 로그 후 DLQ |
| `PROV_DUPLICATE_LOGIN_ID` | 409 | `IDO_QIM_DUPLICATE` | 중복 ID → 사용자 재입력 유도 |
| `PROV_LOCKED` | 412 | `IDO_QIM_LOCKED` | 분산 잠금 → 250ms 후 재시도 |
| `AGENCY_HTTP_5XX` | 5xx | `IDO_QIM_UNAVAILABLE` | Q-IM 서버 오류 → Circuit Breaker |
| `AGENCY_TIMEOUT` | - | `IDO_QIM_TIMEOUT` | 타임아웃 → Resilience4j 처리 |
| `AGENCY_CB_OPEN` | - | `IDO_QIM_CB_OPEN` | Circuit Breaker → Stale Cache |

### 9.2 Q-IM → IdO 수신 API 오류 응답

IdO가 Q-IM 아웃바운드를 수신할 때 5xx 응답 시 Q-IM이 재시도함.
따라서 **IdO SP 수신 API는 반드시 멱등하게 구현**해야 함.

```
Q-IM 재시도 트리거: IdO 수신 API가 5xx 또는 timeout 반환
IdO의 의무:
  - 같은 Idempotency-Key 재수신 → 저장된 응답 재반환 (DB 재처리 없음)
  - 200 응답이 가능한 수준으로 최대한 빠르게 처리
  - 비동기 작업(유관기관 전파 등)은 Kafka로 위임
```

### 9.3 AGENCY_CB_OPEN 처리 (Circuit Breaker 개방)

```
Q-IM이 IdO를 Circuit Breaker 개방 처리 → 일정 기간 수신 차단
예방 방법:
  1. IdO SP 수신 API 응답 시간 < 3초 유지
  2. DB/Redis 작업 비동기화 (Kafka 위임)
  3. 장애 시 503이 아닌 202 Accepted로 응답 후 비동기 처리
```

---

## 10. EDA 기반 내부 전파 설계

### 10.1 IdO 내부 이벤트 토픽 신규 추가

| 토픽 | 생산자 | 소비자 | 용도 | 파티션 키 |
|------|--------|--------|------|----------|
| `qim.sp.member.events` | IdO | agency-adapter, 감사 시스템 | Q-IM 회원 등록/탈퇴 전파 | `qimUserId` |
| `qim.sp.member.events.dlq` | IdO | 운영 알림 | 처리 실패 이벤트 | `qimUserId` |
| `ido.qim.outbound.events` | IdO | IdO (내부) | IdO → Q-IM 호출 결과 추적 | `correlationId` |

### 10.2 `qim.sp.member.events` 이벤트 페이로드

```json
{
  "eventId": "uuid",
  "eventType": "QIM_MEMBER_REGISTERED",
  "sourceSystem": "ido",
  "correlationId": "uuid",
  "qimUserId": "uuid",
  "instMbrId": "uuid",
  "mbrUuid": "q-im-mbrUuid",
  "regMode": "NEW",
  "memberType": "PERSONAL",
  "identifierHash": "sha256hex...",
  "version": 1,
  "occurredAt": "2026-05-07T10:00:00Z"
}
```

```json
{
  "eventId": "uuid",
  "eventType": "QIM_MEMBER_WITHDRAWN",
  "sourceSystem": "ido",
  "correlationId": "uuid",
  "qimUserId": "uuid",
  "instMbrId": "uuid",
  "withdrawalReason": "USER_REQUEST",
  "version": 3,
  "occurredAt": "2026-05-07T10:05:00Z"
}
```

### 10.3 IdO 내부 처리 흐름 (SP 수신 후)

```
Q-IM 아웃바운드 수신
      │
      ▼
QimSpReceiverService
  ① Idempotency-Key 중복 확인 (ido.sp_receiver_idempotency)
  ② API Key 검증 (PBKDF2 비교)
  ③ encCi → AES 복호화 → identifierHash 재계산 (검증용)
  ④ instMbrId 매핑 조회/생성 (ido.inst_mbr_id_mapping)
  ⑤ 처리 결과 응답 (instMbrId 포함)
  ⑥ Outbox 적재 (qim.sp.member.events 발행)
      │
      ▼
IdoOutboxRelay
  - 500ms 주기 배치
  - PENDING → Kafka 발행 → PUBLISHED
  - 실패 시 retry_count++ (max=3)
      │
      ▼
qim.sp.member.events
  └─► agency-adapter Consumer (유관기관 동기화)
  └─► 감사 로그 Consumer
```

---

## 11. Circuit Breaker 및 장애 대응

### 11.1 IdO → Q-IM 방향 (기존 `qim-client` Circuit Breaker 활용)

```yaml
# 기존 application.yml에 이미 설정됨
resilience4j:
  circuitbreaker:
    instances:
      qim-client:
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
```

**Circuit OPEN 시 Fallback**:
```
OPEN 상태:
  1. 마지막 캐시(UserStatusCache)에서 qimUserId 상태 조회
  2. 캐시 미존재 → E-IDO-106 (IDO_QIM_UNREACHABLE) 반환
  3. 신규 등록 요청 → Outbox PENDING 저장 → Q-IM 복구 후 비동기 재처리
```

### 11.2 Q-IM → IdO 방향 (IdO가 수신 API 느려지면 Q-IM이 CB 개방)

**Q-IM Circuit Breaker 개방 방지 전략**:

```
SP 수신 API 응답 SLA: < 500ms (목표), < 3000ms (최대)

구현 방법:
  1. DB 쓰기(Idempotency, 매핑)는 @Transactional 내 최소화
  2. Kafka 발행은 Outbox를 통한 비동기 처리
  3. AES 복호화 실패 시 즉시 400 반환 (재시도 유도하지 않음)
  4. Q-IM API Key 검증은 인메모리 캐시 활용
```

### 11.3 Stale Cache 장애 모드

```
Q-IM 완전 장애 시 IdO 동작:
  1. Circuit OPEN → UserStatusCache.getStale(qimUserId) 조회
  2. Stale TTL: 1800초 (설정: ido.qim.stale-cache-ttl-seconds=1800)
  3. 새 회원 등록: Outbox PENDING → Q-IM 복구 후 배치 재처리
  4. FeSession 발급: 기존 qimUserId가 있으면 허용
  5. 장애 감사: ido.fallback.events 토픽 발행
```

---

## 12. Q-IM 팀과 합의해야 할 결정 항목

> 이 표를 Q-IM 팀과의 기술 협의 자료로 직접 사용한다.

| No | 항목 | 우리(IdO) 안 | Q-IM 확인 필요 | 우선순위 |
|----|------|------------|--------------|---------|
| **1** | **encCi 알고리즘/패딩** | AES-256-CBC 예상 | 정확한 모드/패딩/IV 전달 방식 확인 | 🔴 P0 |
| **2** | **AES 공유키 회전 정책** | 운영팀 수동 교체 가능 | 회전 주기, 유예기간, 무중단 교체 방식 | 🔴 P0 |
| **3** | **Idempotency-Key 보관 기간** | 7일 예정 | Q-IM 측 재판단 기간과 일치시켜야 함 | 🔴 P0 |
| **4** | **instMbrId 정책** | qimUserId와 동일 UUID 사용 | Q-IM이 다른 형식을 요구하는지 확인 | 🔴 P0 |
| **5** | **SP 수신 endpoint URL** | `/api/qim/sp/v1/member/*` | Q-IM 콘솔 등록 전 URL 확정 | 🔴 P0 |
| **6** | **412 재시도 상한** | 3회 + Outbox fallback | Q-IM 측 최대 잠금 유지 시간 | 🟡 P1 |
| **7** | **CONVERSION/PROVISION API 의무 여부** | 현재 구현 미포함 | 선택 API 구현 필요 여부 결정 | 🟡 P1 |
| **8** | **SP-initiated 운영 허용** | PoC에서 가능성 열어둠 | 정식 운영 허용 여부 | 🟡 P1 |
| **9** | **기업회원 brno 암호화 여부** | encCi와 동일 AES 가정 | brno 암호화 적용 여부 확인 | 🟡 P1 |
| **10** | **v1.52와 모 문서(v2.8) 우선순위** | v1.52 기준으로 구현 | 상충 시 어느 문서 우선인지 | 🟢 P2 |

---

## 13. 구현 로드맵

### 13.1 Phase 1 — Q-IM SP 수신 API 구현 (ido 모듈, 1주)

```
신규 구현:
  ✅ QimSpReceiverController (POST 3종)
  ✅ QimSpReceiverService (멱등성, AES 복호화, instMbrId 매핑)
  ✅ InstMbrIdMappingRepository
  ✅ SpReceiverIdempotencyStore
  ✅ AesSharedKeyDecryptor (공유키 복호화 유틸)
  ✅ DB Migration V4 (inst_mbr_id_mapping, sp_receiver_idempotency)
  ✅ application.yml 설정 추가 (qim.sp-receiver, qim.inbound-api-key-hash)

기존 재사용:
  ✅ IdoOutboxRelay (Kafka 발행 비동기)
  ✅ AgencyMeta (API Key 해시 검증)
  ✅ PolicyEngine (instMbrId 매핑 정책)
```

### 13.2 Phase 2 — IdO → Q-IM 확장 (QimClient 확장, 1주)

```
  - QimClient에 register/withdraw/query 메서드 추가
  - TCC Idempotency-Key 생성 유틸
  - 412/409 재시도 로직
  - ConversionSession 연동
```

### 13.3 Phase 3 — 완전 통합 검증 (1주)

```
  - agency-stub에 Q-IM 아웃바운드 Mock 추가
  - 전체 흐름 E2E 테스트 (가입→전환→탈퇴)
  - Circuit Breaker / Stale Cache 장애 시나리오 검증
  - Q-IM 팀과 실 연동 테스트
```

---

## 부록 A. 현재 구현 자산 활용 매핑

| 기존 자산 | 위치 | Phase 1 활용 방법 |
|----------|------|-----------------|
| `IdoOutboxRelay` | idem-hub/infrastructure/outbox | SP 수신 후 `qim.sp.member.events` 발행 |
| `AgencyMetaRepository` | idem-hub/infrastructure | `api_key_hash` 컬럼으로 Q-IM API Key 검증 |
| `IdempotentEventStore` | idem-hub/kafka | SP 수신 멱등성 기반 참고 |
| `FeSessionServiceImpl` | idem-hub/fe/session | 회원 등록 후 FeSession 발급 |
| `PolicyEngineImpl` | idem-hub/policy | instMbrId 매핑 정책 위임 |
| `NonOidcAuthService` | idem-hub/broker/nonoidc | CI 획득 후 identifierHash 생성 패턴 재사용 |

## 부록 B. Q-IM SP 수신 API 응답 봉투 (v1.52 표준)

```json
// 성공
{
  "success": true,
  "data": { /* 본문 */ },
  "message": "처리 완료"
}

// 실패
{
  "success": false,
  "errorCode": "DOMAIN_ERROR_CODE",
  "message": "오류 메시지",
  "details": { }
}
```

## 부록 C. 시험 환경 헤더

Q-IM 시험 환경에서 IdO SP 수신 API 동작 강제:

```http
X-Test-Scenario: fail     # 의도된 실패 응답
X-Test-Scenario: timeout  # 응답 지연
X-Test-Scenario: partial  # 일부 항목만 처리
X-Test-Scenario: retry    # 재시도 유도
```
> 이 헤더는 시험 환경 전용. 운영 배포 시 필터에서 제거 필수.
