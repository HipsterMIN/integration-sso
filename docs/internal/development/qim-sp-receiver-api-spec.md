# IdO Q-IM SP 수신 API 명세서

> **문서 분류**: API 명세서 (API Specification)  
> **버전**: v1.0.0  
> **최종 수정**: 2026-05-07  
> **근거 문서**: Q-IM 유관기관 SP 개발자 API 명세서 v1.52  
> **대상 독자**: IdO 백엔드 개발자, Q-IM 개발팀 협의 담당자  
> **관련 모듈**: `ido` (port 8083)  
> **구현 클래스**: `QimSpReceiverController`, `QimSpReceiverService`

---

## 개요

Q-IM은 SP(Service Provider)에게 3종의 수신 endpoint 구현을 요구한다.  
본 프로젝트에서는 **IdO가 해당 SP 역할을 대리**한다.  
Q-IM이 IdO를 호출하면 IdO는 내부 처리 후 EDA(Kafka)를 통해 유관 시스템에 전파한다.

```
Q-IM ──► IdO (SP 수신 API) ──► Outbox ──► Kafka ──► 유관기관 Adapter
```

---

## 목차

1. [공통 규약](#1-공통-규약)
2. [회원 조회 수신 (MEMBER_QUERY)](#2-회원-조회-수신-member_query)
3. [회원 등록 수신 (MEMBER_REGISTER)](#3-회원-등록-수신-member_register)
4. [회원 탈퇴 수신 (MEMBER_WITHDRAW)](#4-회원-탈퇴-수신-member_withdraw)
5. [오류 응답 규약](#5-오류-응답-규약)
6. [멱등성 처리](#6-멱등성-처리)
7. [시험 환경 지원](#7-시험-환경-지원)
8. [구현 체크리스트](#8-구현-체크리스트)

---

## 1. 공통 규약

### 1.1 Base URL

```
# 로컬 개발
http://localhost:8083/api/qim/sp/v1

# Q-IM 콘솔 등록 URL (운영)
https://{ido-host}/api/qim/sp/v1
```

### 1.2 인증

모든 수신 API는 Q-IM이 IdO에 발급한 **API Key**로 호출자를 식별한다.

```http
X-API-Key: {Q-IM이 발급한 API Key}
```

IdO는 수신 시 `ido.qim.inbound-api-key-hash` 설정값과 PBKDF2 비교로 검증한다.  
불일치 시 즉시 **401 AUTH_INVALID_CREDENTIALS** 반환.

### 1.3 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-API-Key` | ✅ | Q-IM 발급 API Key |
| `Content-Type: application/json` | ✅ | JSON 요청 |
| `Idempotency-Key` | ✅ | 멱등 처리 키 (UUID, 재호출 시 동일 값) |
| `X-Correlation-Id` | 권장 | 추적 ID (없으면 IdO가 생성) |

### 1.4 응답 봉투 (Q-IM v1.52 표준 준수)

```json
// 성공
{
  "success": true,
  "data": { /* 응답 본문 */ },
  "message": "처리 완료"
}

// 실패
{
  "success": false,
  "errorCode": "ERROR_CODE",
  "message": "오류 메시지",
  "details": { "field": "오류 상세" }
}
```

### 1.5 민감정보 암호화 (encCi)

CI(연계정보) 등 민감 정보는 **Q-IM 발급 AES 공유키**로 암호화되어 전송된다.  
IdO는 `ido.qim.aes-shared-key` 설정값으로 복호화 후 처리한다.

```
암호화 방식: AES-256-CBC (Q-IM 팀 확인 필요 ← [결정 필요 #1])
IV 전달 방식: Q-IM 팀 확인 필요 ← [결정 필요 #1]
복호화 유틸: AesSharedKeyDecryptor.decrypt(encCi, aesSharedKey)
```

---

## 2. 회원 조회 수신 (MEMBER_QUERY)

### 2.1 개요

Q-IM이 전환/탈퇴 처리 전 해당 SP(IdO)에 회원 존재 여부를 확인한다.  
주로 **전환 신청** 시 모든 활성 SP에 병렬로 호출된다.

### 2.2 엔드포인트

```
POST /api/qim/sp/v1/member/query
```

### 2.3 요청

```http
POST /api/qim/sp/v1/member/query
X-API-Key: {key}
Content-Type: application/json
Idempotency-Key: {uuid}
X-Correlation-Id: {uuid}

{
  "encCi"  : "AES암호화된CI값",     // 개인회원
  "brno"   : "1234567890",         // 기업회원 (encCi 없을 때)
  "regType": "IND"                  // IND(개인) | ENT(기업)
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `encCi` | String | IND 시 필수 | AES 암호화된 본인인증 CI |
| `brno` | String | ENT 시 필수 | 사업자등록번호 |
| `regType` | String | ✅ | `IND`(개인) 또는 `ENT`(기업) |

### 2.4 응답

```json
// 회원 존재
{
  "success": true,
  "data": {
    "exists"    : true,
    "instMbrId" : "550e8400-e29b-41d4-a716-446655440000"
  },
  "message": "조회 완료"
}

// 회원 미존재
{
  "success": true,
  "data": {
    "exists"    : false,
    "instMbrId" : null
  },
  "message": "조회 완료"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `exists` | Boolean | 회원 존재 여부 |
| `instMbrId` | String\|null | SP 내부 식별자 (= `qimUserId`). 미존재 시 null |

### 2.5 처리 로직

```
1. API Key 검증
2. Idempotency-Key 중복 확인
3. encCi AES 복호화 → identifierHash = SHA-256(plainCi)
   (ENT: brno → SHA-256(brno) 사용)
4. ido.inst_mbr_id_mapping 에서 identifierHash 기반 조회
5. 응답 반환
6. Outbox 적재 없음 (조회만이므로 이벤트 불필요)
```

### 2.6 시퀀스 다이어그램

```
Q-IM                   IdO (QimSpReceiverController)
  │                              │
  │ POST /member/query           │
  │─────────────────────────────►│
  │                              │ API Key 검증
  │                              │ Idempotency 확인
  │                              │ AES 복호화 → identifierHash
  │                              │ inst_mbr_id_mapping 조회
  │◄─────────────────────────────│
  │ {exists, instMbrId}          │
```

---

## 3. 회원 등록 수신 (MEMBER_REGISTER)

### 3.1 개요

Q-IM이 회원을 저장한 후 SP(IdO)에 회원 정보를 통보한다.  
`regMode=NEW`(신규) 또는 `regMode=TRANSFER`(전환) 두 가지가 있다.

### 3.2 엔드포인트

```
POST /api/qim/sp/v1/member/register
```

### 3.3 요청

```http
POST /api/qim/sp/v1/member/register
X-API-Key: {key}
Content-Type: application/json
Idempotency-Key: {uuid}
X-Correlation-Id: {uuid}

{
  "mbrNo"     : "QIM-20260507-000001",
  "mbrUuid"   : "550e8400-e29b-41d4-a716-446655440000",
  "regMode"   : "NEW",
  "encCi"     : "AES암호화된CI",
  "mbrNm"     : "홍길동",
  "phone"     : "01012345678",
  "email"     : "hong@example.com",
  "addr"      : "서울시 강남구",
  "birthDate" : "19900101",
  "gender"    : "M",
  "nationality": "DOMESTIC",
  "notiPrefs" : {
    "sms"  : true,
    "email": true,
    "push" : false
  }
}
```

**기업회원 추가 필드**:

```json
{
  "entMbrNo"  : "ENT-20260507-000001",
  "entMbrUuid": "uuid",
  "regMode"   : "NEW",
  "entNm"     : "중소기업 주식회사",
  "rprsNm"    : "김대표",
  "brno"      : "1234567890",
  "pic"       : {
    "name" : "이담당",
    "phone": "01098765432",
    "email": "pic@corp.com"
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `mbrNo` / `entMbrNo` | String | ✅ | Q-IM 회원 식별번호 |
| `mbrUuid` / `entMbrUuid` | String | ✅ | Q-IM 회원 UUID |
| `regMode` | String | ✅ | `NEW`(신규) \| `TRANSFER`(전환) |
| `encCi` | String | 개인 ✅ | AES 암호화된 CI |
| `mbrNm` / `entNm` | String | ✅ | 성명 / 사업체명 |
| `phone` | String | ✅ | 연락처 |
| `email` | String | 권장 | 이메일 |
| `brno` | String | 기업 ✅ | 사업자등록번호 |
| `notiPrefs` | Object | 선택 | 알림 채널 설정 |

### 3.4 응답

```json
{
  "success": true,
  "data": {
    "instMbrId"    : "550e8400-e29b-41d4-a716-446655440000",
    "registeredAt" : "2026-05-07T11:00:00+09:00"
  },
  "message": "등록 완료"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `instMbrId` | String | SP 내부 식별자 (= `qimUserId`). Q-IM이 이후 모든 호출에서 매핑 기준으로 사용 |
| `registeredAt` | String | ISO-8601 등록 시각 |

### 3.5 처리 로직

```
1. API Key 검증
2. Idempotency-Key 중복 확인
   → FOUND: 저장된 응답 JSON 재반환 (처리 없이)
3. AES 복호화(encCi) → identifierHash 재계산
4. instMbrId 결정:
   a. ido.inst_mbr_id_mapping에서 mbrUuid 조회
   b. 없으면 신규 생성 (instMbrId = qimUserId = UUID)
5. ido.inst_mbr_id_mapping 저장 (트랜잭션)
6. ido.sp_receiver_idempotency 저장 (응답 JSON 포함)
7. Outbox 적재 → qim.sp.member.events 비동기 발행
   eventType: QIM_MEMBER_REGISTERED | QIM_MEMBER_TRANSFERRED
8. 응답 반환 (instMbrId 포함)
```

### 3.6 TRANSFER 모드 처리

```
regMode=TRANSFER 수신 시:
  - 기존 instMbrId가 ido.inst_mbr_id_mapping에 존재하면 재사용
  - 없으면 신규 생성 (신규 가입과 동일 처리)
  - Outbox 이벤트: eventType=QIM_MEMBER_TRANSFERRED
```

### 3.7 멱등 처리 예시

```
1차 호출: Idempotency-Key=key-001 → 처리 완료 → 응답 저장
2차 호출: Idempotency-Key=key-001 → 저장된 응답 재반환 (DB 재처리 없음)
```

---

## 4. 회원 탈퇴 수신 (MEMBER_WITHDRAW)

### 4.1 개요

이용자 본인 또는 운영자 주도 탈퇴 시 Q-IM이 모든 활성 SP에 탈퇴를 전파한다.  
이미 탈퇴된 회원에 대한 재호출은 **성공으로 처리** (멱등).

### 4.2 엔드포인트

```
POST /api/qim/sp/v1/member/withdraw
```

### 4.3 요청

```http
POST /api/qim/sp/v1/member/withdraw
X-API-Key: {key}
Content-Type: application/json
Idempotency-Key: {uuid}
X-Correlation-Id: {uuid}

{
  "encCi"          : "AES암호화된CI",
  "mbrId"          : "550e8400-e29b-41d4-a716-446655440000",
  "mbrUuid"        : "550e8400-e29b-41d4-a716-446655440000",
  "withdrawalReason": "USER_REQUEST"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `encCi` | String | ✅ | AES 암호화된 CI |
| `mbrId` | String | ✅ | SP 자체 회원 식별자 (= instMbrId) |
| `mbrUuid` | String | ✅ | Q-IM 회원 UUID |
| `withdrawalReason` | String | ✅ | `USER_REQUEST` \| `ADMIN_FORCE` \| `DORMANT` |

### 4.4 응답

```json
// 탈퇴 처리 완료
{
  "success": true,
  "data": {
    "withdrawnAt": "2026-05-07T11:05:00+09:00"
  },
  "message": "탈퇴 처리 완료"
}

// 이미 탈퇴된 회원 (멱등 처리)
{
  "success": true,
  "data": {
    "withdrawnAt": "2026-05-06T09:00:00+09:00"
  },
  "message": "이미 탈퇴 처리된 회원입니다"
}
```

### 4.5 처리 로직

```
1. API Key 검증
2. Idempotency-Key 중복 확인
   → FOUND: 저장된 응답 재반환
3. instMbrId(= mbrId) 로 ido.inst_mbr_id_mapping 조회
   → NOT FOUND: 404 반환 (매핑 없는 탈퇴는 거부)
4. qimUserId 기반 FeSession 전체 무효화
   (FeSessionService.invalidateByQimUserId)
5. ido.inst_mbr_id_mapping 상태를 WITHDRAWN으로 업데이트
6. ido.sp_receiver_idempotency 저장
7. Outbox 적재 → qim.sp.member.events 발행
   eventType: QIM_MEMBER_WITHDRAWN
8. 응답 반환
```

### 4.6 ALREADY_WITHDRAWN 멱등 처리

```java
// Q-IM 명세 §4.4: 이미 탈퇴 처리된 회원에 대한 재호출은 성공으로 처리
if (mapping.getStatus() == MappingStatus.WITHDRAWN) {
    return SpWithdrawResponse.alreadyWithdrawn(mapping.getWithdrawnAt());
}
```

---

## 5. 오류 응답 규약

### 5.1 HTTP 상태코드 및 오류코드

| HTTP | 오류코드 | 설명 | 처리 방법 |
|------|---------|------|----------|
| 400 | `SP_INPUT_INVALID` | 필수 필드 누락 / 형식 오류 | Q-IM 요청 수정 후 재시도 |
| 401 | `SP_AUTH_INVALID` | API Key 불일치 | Q-IM 관리 콘솔에서 키 확인 |
| 404 | `SP_MEMBER_NOT_FOUND` | 탈퇴 요청 대상 회원 없음 | Q-IM에 매핑 없는 탈퇴 거부 |
| 409 | `SP_MEMBER_DUPLICATE` | 이미 등록된 회원 (등록 수신) | Idempotency-Key 확인 |
| 422 | `SP_DECRYPT_FAILED` | AES 복호화 실패 | 공유키 일치 여부 확인 |
| 500 | `SP_INTERNAL_ERROR` | IdO 내부 오류 | Q-IM이 재시도 (Circuit Breaker 주의) |
| 503 | `SP_UNAVAILABLE` | IdO 일시 불가 | 250ms 후 재시도 |

> **주의**: IdO가 5xx 응답 시 Q-IM 측 Circuit Breaker가 개방될 수 있음.  
> 내부 처리 실패는 가능하면 202 Accepted + Outbox 비동기로 처리한다.

### 5.2 오류 응답 예시

```json
// 401 API Key 불일치
{
  "success": false,
  "errorCode": "SP_AUTH_INVALID",
  "message": "API Key 인증에 실패했습니다.",
  "details": {}
}

// 422 복호화 실패
{
  "success": false,
  "errorCode": "SP_DECRYPT_FAILED",
  "message": "encCi 복호화에 실패했습니다. AES 공유키를 확인해주세요.",
  "details": {
    "hint": "키 회전 직후라면 SP 측 키 재배포가 필요합니다."
  }
}
```

---

## 6. 멱등성 처리

### 6.1 구현 방법

```
저장소: ido.sp_receiver_idempotency (DB 테이블)
TTL   : 7일 (배치로 만료 레코드 삭제)
키    : Idempotency-Key 헤더 값

처리 흐름:
  1. SELECT * FROM ido.sp_receiver_idempotency WHERE idempotency_key = :key
  2. FOUND  → response_json 컬럼에서 JSON 파싱하여 즉시 반환
  3. NOT FOUND → 신규 처리 후 결과를 response_json에 저장
```

### 6.2 테이블 스키마

```sql
CREATE TABLE ido.sp_receiver_idempotency (
    idempotency_key  VARCHAR(200)  NOT NULL,
    endpoint         VARCHAR(50)   NOT NULL,  -- QUERY | REGISTER | WITHDRAW
    response_json    TEXT          NOT NULL,  -- 저장된 응답 JSON
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_sp_receiver_idempotency PRIMARY KEY (idempotency_key)
);
CREATE INDEX idx_sp_idempotency_expires ON ido.sp_receiver_idempotency (expires_at);
```

---

## 7. 시험 환경 지원

Q-IM 명세 §2.4에 따라 다음 테스트 시나리오 헤더를 지원한다.

| `X-Test-Scenario` 값 | IdO 동작 |
|---------------------|---------|
| `fail` | 의도된 실패 응답 (500) |
| `timeout` | 3초 지연 후 응답 |
| `partial` | 등록만 성공, Outbox 발행 건너뜀 |
| `retry` | 첫 호출 412 반환, 재호출 정상 |

> **운영 주의**: `X-Test-Scenario` 헤더는 `spring.profiles.active=test` 환경에서만 처리.  
> 운영 프로파일에서는 이 헤더를 무시하는 `TestScenarioFilter`를 적용한다.

---

## 8. 구현 체크리스트

| 항목 | 담당 클래스 | 상태 |
|------|-----------|------|
| `POST /api/qim/sp/v1/member/query` 구현 | `QimSpReceiverController` | 🔄 구현 중 |
| `POST /api/qim/sp/v1/member/register` 구현 | `QimSpReceiverController` | 🔄 구현 중 |
| `POST /api/qim/sp/v1/member/withdraw` 구현 | `QimSpReceiverController` | 🔄 구현 중 |
| API Key 검증 (`QimApiKeyValidator`) | `QimSpReceiverService` | 🔄 구현 중 |
| AES 복호화 (`AesSharedKeyDecryptor`) | `AesSharedKeyDecryptor` | 🔄 구현 중 |
| Idempotency 저장/조회 | `SpReceiverIdempotencyStore` | 🔄 구현 중 |
| `instMbrId` 매핑 조회/생성 | `InstMbrIdMappingRepository` | 🔄 구현 중 |
| Outbox 발행 (`qim.sp.member.events`) | `IdoOutboxRelay` 재사용 | 🔄 구현 중 |
| DB Migration V4 | `V4__add_qim_sp_receiver.sql` | 🔄 구현 중 |
| `X-Test-Scenario` 헤더 처리 | `TestScenarioFilter` | ⏳ 예정 |
| Q-IM 관리 콘솔 endpoint 등록 | 운영팀 | ⏳ 예정 |
| API Key / AES 공유키 교환 | Q-IM 팀 협의 | ⏳ 합의 필요 |
