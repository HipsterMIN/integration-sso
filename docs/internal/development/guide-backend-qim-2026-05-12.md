# Q-IM 백엔드 팀 개발 가이드

> **버전**: v3.0.0 (PR #77 반영 — 2026-05-12 GAP 패치 완료 기준)  
> **최종 수정**: 2026-05-12  
> **대상**: Q-IM(Qualified Identity Manager) 외부 팀  
> **포트**: 8082 (내부), `onepass-dev.smes.go.kr/im` (운영)  
> **기술 스택**: Spring Boot / Java / PostgreSQL (Q-IM 팀 자체 관리)

---

> **⚠️ 이 문서는 Q-IM 외부 팀과의 연동 인터페이스 계약서 역할을 합니다.**  
> Q-IM 팀이 구현해야 하는 `/api/ext/**` 엔드포인트 전체 목록과 요청/응답 스펙을 정의합니다.

---

## 목차

1. [Q-IM의 역할 및 연동 구조 (v3.0)](#1-q-im의-역할-및-연동-구조)
2. [변경된 연동 방식 (v3.0)](#2-변경된-연동-방식)
3. [FE 호출 API 전체 목록 (`/api/ext/**`)](#3-fe-호출-api-전체-목록)
4. [ido → Q-IM 내부 API 목록](#4-ido--q-im-내부-api-목록)
5. [CI 암호화 계약 (필수 합의)](#5-ci-암호화-계약)
6. [동의 토큰 오류 응답 계약](#6-동의-토큰-오류-응답-계약)
7. [AES-GCM 키 관리 계약 (신규)](#7-aes-gcm-키-관리-계약-신규)
8. [Q-IM → ido 아웃바운드 API (SP 수신)](#8-q-im--ido-아웃바운드-api)
9. [통합 테스트 시나리오](#9-통합-테스트-시나리오)
10. [연동 체크리스트](#10-연동-체크리스트)

---

## 1. Q-IM의 역할 및 연동 구조

### 1.1 Q-IM(Qualified Identity Manager) 역할

Q-IM은 **식별 SoR(Source of Record)** 역할을 담당합니다.

```
┌──────────────────────────────────────────────────────────────────┐
│  Q-IM (외부 서비스 — Q-IM 팀 관리)                               │
│                                                                  │
│  ① /api/ext/**     — FE BFF 역할 (ido forward proxy 경유)       │
│  ② /api/v1/internal/** — ido → Q-IM 서버 간 내부 통신           │
│  ③ CI 저장/복호화  — AES-256-GCM 암호화된 CI 관리               │
│  ④ 회원 원장       — 개인/기업 회원 정보 관리                   │
│  ⑤ 약관/동의       — 동의 토큰 발급 및 제출 처리                │
│  ⑥ ciToken 발급    — CI → JWT ciToken 변환                      │
└──────────────────────────────────────────────────────────────────┘
```

### 1.2 v3.0 아키텍처 (변경 후)

```
FE (beApiInstance)
    │
    │  /api/ext/**  (모든 Q-IM 외부 API)
    ▼
ido :8083
    │  X-Ext-Api-Key: IDO_QIM_EXT_API_KEY  ← 서버사이드 주입 (B-5)
    ▼
Q-IM :8082 (또는 onepass-dev.smes.go.kr/im)
    │  /api/ext/**  처리
    └─ 응답 → ido → FE
```

**핵심 변경 (v3.0)**: FE가 Q-IM을 직접 호출하던 방식 → **ido forward proxy 경유**로 변경.  
이에 따라 Q-IM에서 수신하는 요청의 `X-Ext-Api-Key`는 FE가 아닌 **ido 서버**에서 주입됩니다.

---

## 2. 변경된 연동 방식

### 2.1 인증 헤더 변경

| 구분 | v2.x (이전) | v3.0 (현재) |
|------|------------|------------|
| 요청 발신자 | FE 브라우저 | ido 서버 (forward proxy) |
| `X-Ext-Api-Key` 발신 | FE 번들에 포함 | **ido 서버에서 주입** |
| Q-IM 관점 변화 | 없음 — API Key 검증 동일 |

> **Q-IM 팀 확인사항**: v3.0에서도 `X-Ext-Api-Key` 인증 방식은 동일합니다.  
> 요청 발신 IP가 FE 클라이언트 → ido 서버로 변경되므로 IP 허용 목록이 있다면 ido 서버 IP를 추가하세요.

### 2.2 CI 수신 경로 변경 (B-1)

| 구분 | v2.x | v3.0 |
|------|------|------|
| encryptedCi 발신 | FE → Q-IM 직접 (`POST /api/ext/ci/token`) | FE → **ido** → ido 복호화/재암호화 → Q-IM |
| CI 암호화 키 | FE `AES_GCM_KEY` 번들 ↔ Q-IM 동일 키 | **FE 키 = ido 키 (`FE_AES_GCM_KEY`)**, Q-IM 키 = ido-Q-IM 공유키 |

```
[v3.0 CI 교환 흐름]
FE -[AES-GCM 암호화 CI]→ ido [FE_AES_GCM_KEY로 복호화] → [QIM_AES_SHARED_KEY로 재암호화] → Q-IM
                                                           Q-IM: [QIM_AES_SHARED_KEY로 복호화] → CI 원문
```

> **⚠️ 필수 합의**: `QIM_AES_SHARED_KEY`는 **ido 팀 ↔ Q-IM 팀**이 협의 후 동일한 키 사용.  
> ido `application.yml`: `ido.qim.aes-shared-key: ${QIM_AES_SHARED_KEY:}`

---

## 3. FE 호출 API 전체 목록

> 모든 `/api/ext/**` 엔드포인트는 **Q-IM 팀이 구현**해야 합니다.  
> FE는 ido를 경유하지만 경로와 요청/응답 스펙은 Q-IM이 정의합니다.

### 3-A. 클라이언트 목록

#### GET /api/ext/clients

유관기관(클라이언트) 목록 조회. Step4에서 사용.

| 항목 | 내용 |
|------|------|
| 인증 | `X-Ext-Api-Key` |
| 파라미터 | 없음 |

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "clients": [
        {
          "id": "client-uuid-1",
          "name": "스마트공장",
          "description": "스마트공장 플랫폼"
        },
        {
          "id": "client-uuid-2",
          "name": "기업마당",
          "description": "기업지원 통합포털"
        }
      ]
    }
  }
}
```

> **FE 필드 참조**: `res.payload.data.clients[]` → `{ id, name, description }`

---

### 3-B. 약관/동의 API

#### POST /api/ext/consent/token

동의 토큰 발급. Step2 시작 시 호출.

**요청**:
```json
{
  "realm": "qim",
  "client": "sp-smeg"
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "consentToken": "eyJhbGciOiJIUzI1NiJ9..."
    }
  }
}
```

---

#### GET /api/ext/terms/bundle

약관 번들 조회. Step2 약관 목록 렌더링.

| 파라미터 | 필수 | 설명 |
|---------|:----:|------|
| `realm` | ✅ | `qim` |
| `client` | ✅ | `sp-smeg` |
| `lang` | ✅ | `ko` |

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "terms": [
        {
          "termId": "term-001",
          "title": "이용약관",
          "required": true,
          "content": {
            "body": "<p>약관 내용 HTML...</p>"
          }
        }
      ]
    }
  }
}
```

> **FE 보안 주의**: `content.body`는 DOMPurify로 sanitize 후 렌더링. XSS 인젝션 없도록 서버에서도 검증 필요.

---

#### POST /api/ext/consent

동의 제출. Step2 완료 시 호출.

**요청**:
```json
{
  "consentToken": "eyJhbGciOiJIUzI1NiJ9...",
  "agreements": [
    { "termId": "term-001", "agreed": true },
    { "termId": "term-002", "agreed": false }
  ]
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "consentEventId": "consent-event-uuid"
    }
  }
}
```

**오류 응답 (토큰 만료)**:
```json
{
  "statusCode": 400,
  "message": "INVALID_CONSENT_TOKEN",
  "payload": null
}
```

> **⚠️ FE 의존 사항**: FE Step2는 `message.includes('INVALID_CONSENT_TOKEN')` 또는 `body.includes('INVALID_CONSENT_TOKEN')`으로 토큰 만료를 감지합니다.  
> **반드시 응답 message 또는 body에 `INVALID_CONSENT_TOKEN` 문자열을 포함**해주세요.

---

### 3-C. CI Token 교환 (v3.0 변경)

#### POST /api/ext/ci/token

> **v3.0 변경**: FE가 직접 호출하던 API → ido BFF가 CI 재암호화 후 호출.  
> Q-IM은 `encryptedCi`의 암호화 키가 `QIM_AES_SHARED_KEY`로 변경됨을 인지해야 합니다.

**요청** (ido → Q-IM):
```json
{
  "encryptedCi": "base64(IV||ciphertext||tag)",
  "realm": "ucube-qsign",
  "clientId": "onepassCli",
  "flowContext": "CONVERSION"
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "ciToken": "eyJhbGciOiJIUzI1NiJ9..."
    }
  }
}
```

> **암호화 스펙**: `encryptedCi` = base64(IV(12B) || ciphertext || GCM-tag(16B))  
> 복호화 키: `QIM_AES_SHARED_KEY` (ido 팀과 합의 후 동일 키 사용)

---

### 3-D. 회원 조회 API

#### GET /api/ext/members/{mbrNo}

개인회원 조회.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "mbrNo": "MBR-0001",
      "mbrUuid": "uuid-...",
      "name": "홍길동",
      "phoneNumber": "01012341234",
      "email": "hong@example.com",
      "bzmnNm": null,
      "brno": null
    }
  }
}
```

---

#### GET /api/ext/enterprises/{entMbrNo}

기업회원 조회.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "entMbrNo": "ENT-0001",
      "mbrUuid": "uuid-...",
      "bzmnNm": "주식회사 스마트",
      "rprsvNm": "김대표",
      "brno": "123-45-67890"
    }
  }
}
```

---

#### GET /api/ext/members/{mbrUuid}/affiliations

개인회원 유관기관 목록.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "affiliations": [
        {
          "clientId": "client-uuid-1",
          "clientName": "스마트공장",
          "status": "ACTIVE"
        }
      ]
    }
  }
}
```

---

#### GET /api/ext/enterprises/{mbrUuid}/affiliations

기업회원 유관기관 목록. 응답 형식 동일.

---

### 3-E. 중복 확인 API

#### GET /api/ext/check-duplicate

아이디 중복 확인.

| 파라미터 | 필수 | 설명 |
|---------|:----:|------|
| `type` | ✅ | `IND` (개인) / `ENT` (기업) |
| `value` | ✅ | 확인할 아이디 |

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "isDuplicate": false
    }
  }
}
```

---

### 3-F. 사업자 조회 API

#### POST /api/ext/business/status

사업자 상태 조회 (국세청 OpenAPI 연동).

**요청**:
```json
{ "brno": "123-45-67890" }
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "status": "ACTIVE",
      "bzmnNm": "주식회사 스마트",
      "rprsvNm": "김대표"
    }
  }
}
```

---

#### POST /api/ext/business/validate

사업자 진위확인.

**요청**:
```json
{
  "brno": "123-45-67890",
  "rprsvNm": "김대표",
  "openDt": "20200101"
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": { "valid": true }
  }
}
```

---

### 3-G. 기업 간편인증 결과 API

#### GET /api/ext/auth-status

기업인증 상태 조회.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "txId": "tx-uuid",
      "status": "PENDING"
    }
  }
}
```

---

#### GET /api/ext/auth-result/{txId}

기업인증 결과 조회.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "txId": "tx-uuid",
      "status": "SUCCESS",
      "brno": "123-45-67890",
      "bzmnNm": "주식회사 스마트"
    }
  }
}
```

---

### 3-H. 전환 가능 여부 확인

#### POST /api/ext/provision/users/check-conversion

개인회원 전환 가능 여부 확인.

**요청**:
```json
{ "ciToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "convertible": true,
      "mbrNo": "MBR-0001",
      "name": "홍길동"
    }
  }
}
```

---

### 3-I. 프로비저닝 API

#### POST /api/ext/provision/users

개인회원 프로비저닝 (회원전환 Step5).

**요청**:
```json
{
  "ciToken": "eyJhbGciOiJIUzI1NiJ9...",
  "loginId": "hong123",
  "password": "Secure!Password1",
  "name": "홍길동",
  "phoneNumber": "01012341234",
  "email": "hong@example.com",
  "selectedClients": ["client-uuid-1"],
  "consentEventId": "consent-event-uuid",
  "smsAgree": true,
  "kakaoAgree": false,
  "emailAgree": true
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "mbrNo": "MBR-0001",
      "mbrUuid": "uuid-..."
    }
  }
}
```

---

#### POST /api/ext/provision/enterprises

기업회원 프로비저닝 (회원전환 Step5).

**요청**:
```json
{
  "ciToken": "eyJhbGciOiJIUzI1NiJ9...",
  "loginId": "company123",
  "password": "Secure!Password1",
  "bzmnNm": "주식회사 스마트",
  "rprsvNm": "김대표",
  "brno": "123-45-67890",
  "selectedClients": ["client-uuid-1"],
  "consentEventId": "consent-event-uuid"
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "entMbrNo": "ENT-0001",
      "mbrUuid": "uuid-..."
    }
  }
}
```

---

### 3-J. 신규 회원가입 API

#### POST /api/ext/register/individual

개인회원 신규 등록.

**요청**: provision/users와 동일 형식

**응답 200**: provision/users와 동일 형식

---

#### POST /api/ext/register/enterprise

기업회원 신규 등록.

**요청**: provision/enterprises와 동일 형식

**응답 200**: provision/enterprises와 동일 형식

---

### 3-K. 회원정보 수정 API

#### POST /api/ext/provision/users/modify_local

개인회원 정보 수정.

**요청**:
```json
{
  "mbrUuid": "uuid-...",
  "name": "홍길동",
  "phoneNumber": "01012341234",
  "email": "new@example.com"
}
```

---

#### POST /api/ext/provision/enterprises/modify_local

기업회원 정보 수정. 요청 형식 유사.

---

### 3-L. 유관기관 추가/탈퇴 API

#### POST /api/ext/provision/users/{uuid}/affiliations/add

개인회원 유관기관 추가.

**요청**:
```json
{ "clientId": "client-uuid-1" }
```

---

#### POST /api/ext/provision/enterprises/{uuid}/affiliations/add

기업회원 유관기관 추가.

---

#### POST /api/ext/provision/users/{uuid}/affiliations/withdraw

개인회원 유관기관 탈퇴 (ciToken 필요).

**요청**:
```json
{
  "clientId": "client-uuid-1",
  "ciToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

---

#### POST /api/ext/provision/enterprises/{uuid}/affiliations/withdraw

기업회원 유관기관 탈퇴.

---

## 4. ido → Q-IM 내부 API 목록

> `X-Internal-Api-Key` 헤더 인증 필수 (Q-IM 관리 콘솔에서 발급).

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/internal/users` | 사용자 등록 Upsert |
| `GET` | `/api/v1/internal/users/{qimUserId}` | 사용자 조회 |
| `POST` | `/api/v1/internal/member/lookup-by-ci` | CI로 회원 조회 |
| `GET` | `/api/v1/internal/member/lookup-by-hash` | identifierHash로 회원 조회 |

---

## 5. CI 암호화 계약 (필수 합의)

### 5.1 암호화 스펙

| 항목 | 값 |
|------|---|
| 알고리즘 | AES-256-GCM |
| IV | 12바이트 (랜덤, 매 요청마다 신규) |
| GCM Tag | 128비트 |
| 결과 형식 | base64(IV(12B) \|\| ciphertext \|\| GCM-tag(16B)) |

### 5.2 v3.0 키 구조

```
[FE 암호화]
FE → AES-GCM 암호화 (FE_AES_GCM_KEY)
   → encryptedCi → ido

[ido 중계]
ido → AES-GCM 복호화 (FE_AES_GCM_KEY)
   → AES-GCM 재암호화 (QIM_AES_SHARED_KEY)
   → re-encryptedCi → Q-IM

[Q-IM 처리]
Q-IM → AES-GCM 복호화 (QIM_AES_SHARED_KEY)
     → CI 원문 처리
```

### 5.3 키 협의 체크리스트

```
[ ] QIM_AES_SHARED_KEY 생성 (Q-IM 또는 ido 팀)
[ ] ido 팀에 QIM_AES_SHARED_KEY 공유 (안전한 채널 사용)
[ ] ido application.yml: ido.qim.aes-shared-key 설정 확인
[ ] QIM_AES_TRANSFORMATION 협의 (기본: AES/GCM/NoPadding)
[ ] QIM_AES_IV_LENGTH 협의 (기본: 12바이트)
[ ] 암호화/복호화 단위 테스트 교차검증
```

---

## 6. 동의 토큰 오류 응답 계약

FE Step2는 동의 토큰 만료 감지 시 자동 재발급 후 1회 재시도합니다.  
Q-IM은 다음 응답 중 하나에 `INVALID_CONSENT_TOKEN` 문자열을 반드시 포함해야 합니다.

```json
// 방법 A: message 필드
{
  "statusCode": 400,
  "message": "INVALID_CONSENT_TOKEN",
  "payload": null
}

// 방법 B: body 필드 (문자열)
{
  "statusCode": 400,
  "message": "동의 토큰이 만료되었습니다.",
  "body": "INVALID_CONSENT_TOKEN"
}
```

**FE 감지 코드 (참고)**:
```typescript
if (
  response.message?.includes('INVALID_CONSENT_TOKEN') ||
  errorBody?.includes('INVALID_CONSENT_TOKEN')
) {
  // 토큰 재발급 → 재시도
}
```

---

## 7. AES-GCM 키 관리 계약 (신규)

### 7.1 v3.0에서 달라진 점

- **v2.x**: FE 번들의 `AES_GCM_KEY` ↔ Q-IM 동일 키 (직접 계약)
- **v3.0**: FE 번들에 키 없음 → **ido 중계** → Q-IM이 `QIM_AES_SHARED_KEY`로 복호화

### 7.2 Q-IM 팀 조치 사항

1. `QIM_AES_SHARED_KEY` 생성 후 ido 팀에 안전하게 공유
2. ido 팀이 `QIM_AES_SHARED_KEY`로 재암호화한 CI를 수신하도록 복호화 로직 업데이트
3. ido 팀과 암호화 단위 테스트 교차검증 필수

---

## 8. Q-IM → ido 아웃바운드 API

Q-IM이 ido로 회원 이벤트를 전송하는 경우 사용합니다.  
`X-Qim-Inbound-Api-Key` 헤더로 인증 (ido `QIM_INBOUND_API_KEY_HASH` PBKDF2 해시).

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/qim/sp/member/register` | 회원 등록 이벤트 수신 |
| `GET` | `/api/v1/qim/sp/member/{instMbrId}` | 회원 조회 |

---

## 9. 통합 테스트 시나리오

### 9.1 회원전환 흐름 (개인회원)

```bash
# Step2: 약관 동의
POST /api/ext/consent/token → consentToken
GET  /api/ext/terms/bundle  → 약관 목록
POST /api/ext/consent       → consentEventId

# Step3: OACX 간편인증
POST /api/v1/auth/oacx/access-info (ido)
POST /api/v1/auth/oacx/easysign    (ido) → encryptedCi
POST /api/v1/auth/ci-token         (ido → Q-IM) → ciToken

# Step4: 클라이언트 목록
GET  /api/ext/clients → clients[]

# Step5: 프로비저닝
POST /api/ext/provision/users → mbrNo
```

### 9.2 INVALID_CONSENT_TOKEN 재발급 시나리오

```bash
# 1. 토큰 발급
POST /api/ext/consent/token → consentToken (A)

# 2. 토큰 만료 후 동의 제출
POST /api/ext/consent { consentToken: A }
→ 400, message: "INVALID_CONSENT_TOKEN"

# 3. FE가 자동 재발급 후 재시도 (FE 로직)
POST /api/ext/consent/token → consentToken (B)
POST /api/ext/consent { consentToken: B } → 200, consentEventId
```

---

## 10. 연동 체크리스트

```
[ ] GET  /api/ext/clients                                     구현 확인
[ ] POST /api/ext/consent/token                               구현 확인
[ ] GET  /api/ext/terms/bundle                                구현 확인
[ ] POST /api/ext/consent                                     구현 확인
[ ] POST /api/ext/ci/token                                    구현 확인 (v3.0 키 변경)
[ ] GET  /api/ext/auth-status                                 구현 확인
[ ] GET  /api/ext/auth-result/{txId}                          구현 확인
[ ] GET  /api/ext/members/{mbrNo}                             구현 확인
[ ] GET  /api/ext/enterprises/{entMbrNo}                      구현 확인
[ ] GET  /api/ext/members/{mbrUuid}/affiliations              구현 확인
[ ] GET  /api/ext/enterprises/{mbrUuid}/affiliations          구현 확인
[ ] GET  /api/ext/check-duplicate                             구현 확인
[ ] POST /api/ext/business/status                             구현 확인
[ ] POST /api/ext/business/validate                           구현 확인
[ ] POST /api/ext/provision/users/check-conversion            구현 확인
[ ] POST /api/ext/provision/users                             구현 확인
[ ] POST /api/ext/provision/enterprises                       구현 확인
[ ] POST /api/ext/register/individual                         구현 확인
[ ] POST /api/ext/register/enterprise                         구현 확인
[ ] POST /api/ext/provision/users/modify_local                구현 확인
[ ] POST /api/ext/provision/enterprises/modify_local          구현 확인
[ ] POST /api/ext/provision/users/{uuid}/affiliations/add     구현 확인
[ ] POST /api/ext/provision/enterprises/{uuid}/affiliations/add 구현 확인
[ ] POST /api/ext/provision/users/{uuid}/affiliations/withdraw 구현 확인
[ ] POST /api/ext/provision/enterprises/{uuid}/affiliations/withdraw 구현 확인

[ ] INVALID_CONSENT_TOKEN 응답 포맷 확인 (message 또는 body 포함)
[ ] QIM_AES_SHARED_KEY ↔ ido 팀 공유 및 암호화 단위 테스트 통과
[ ] /api/ext/** 요청 발신 IP 허용 목록에 ido 서버 IP 추가 (IP 화이트리스트 있는 경우)
[ ] X-Ext-Api-Key 인증 정상 동작 확인 (ido 서버에서 주입)
[ ] GET /api/ext/clients 응답 형식 FE Step4와 교차검증
```

---

*최종 수정: 2026-05-12 / PR #77 반영*  
*다음 업데이트 예정: Q-IM API 교차검증 완료 후 상세 스펙 확정*
