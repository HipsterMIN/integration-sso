# 전체 API 레퍼런스

> **버전**: v3.0.0 (PR #77 반영 — 2026-05-12 GAP 패치 완료 기준)  
> **최종 수정**: 2026-05-12  
> **대상**: 전체 개발팀 (FE / ido BE / Q-IM BE / 인프라)

---

## 목차

1. [공통 규약](#1-공통-규약)
2. [ido API — `/api/v1/auth/**`](#2-ido-api--apiv1auth)
   - [NICE 휴대폰 인증](#21-nice-휴대폰-인증)
   - [OACX 간편인증](#22-oacx-간편인증)
   - [CI 토큰 교환](#23-ci-토큰-교환)
   - [Provision 유틸리티](#24-provision-유틸리티)
   - [기업 간편인증 콜백](#25-기업-간편인증-콜백)
3. [ido API — 세션/Handoff/SLO](#3-ido-api--세션handoffslo)
   - [FE 세션](#31-fe-세션)
   - [Handoff](#32-handoff)
   - [SLO](#33-slo)
4. [Q-IM External API — `/api/ext/**`](#4-q-im-external-api--apiext)
   - [클라이언트 목록](#41-클라이언트-목록)
   - [약관/동의](#42-약관동의)
   - [CI Token 교환](#43-ci-token-교환)
   - [회원 조회](#44-회원-조회)
   - [중복 확인](#45-중복-확인)
   - [사업자 조회](#46-사업자-조회)
   - [기업인증 결과](#47-기업인증-결과)
   - [전환 가능 여부](#48-전환-가능-여부)
   - [프로비저닝 (회원전환)](#49-프로비저닝-회원전환)
   - [신규 회원가입](#410-신규-회원가입)
   - [회원정보 수정](#411-회원정보-수정)
   - [유관기관 관리](#412-유관기관-관리)
5. [ido → Q-IM 내부 API — `/api/v1/internal/**`](#5-ido--q-im-내부-api)
6. [에러 코드 및 응답 형식](#6-에러-코드-및-응답-형식)
7. [v3.0 변경 이력](#7-v30-변경-이력)

---

## 1. 공통 규약

### 1.1 서비스 기본 URL

| 서비스 | 로컬 URL | 운영 URL |
|--------|---------|---------|
| ido (FE BFF) | `http://localhost:8083` | `https://onepass-ido.smes.go.kr` |
| Q-Sign | `http://localhost:8081` | 내부망 전용 |
| Q-IM | `http://localhost:8082` | `https://onepass.smes.go.kr/im` (내부) |
| FE (개발) | `http://localhost:3001` | `https://onepass.smes.go.kr` |

> **v3.0 핵심**: FE는 모든 API를 **ido(8083) 하나**를 통해 호출합니다.  
> `/api/ext/**`도 ido가 Q-IM으로 forward proxy합니다.

### 1.2 요청 헤더

| 헤더 | 적용 대상 | 설명 |
|------|---------|------|
| `Content-Type: application/json` | 모든 POST/PUT | |
| `X-BE-API-Key: <key>` | FE → ido | FE beApiInstance 인증 |
| `X-Internal-Api-Key: <key>` | ido → Q-IM (내부) | 서버 간 통신 인증 |
| `X-Ext-Api-Key: <key>` | ido → Q-IM (외부) | forward proxy 시 서버가 주입 |
| `X-Correlation-Id: <uuid>` | 모든 API | 없으면 서버가 UUID 생성 |

### 1.3 표준 응답 형식

```json
// 성공
{
  "statusCode": 200,
  "payload": {
    "data": { ... }
  }
}

// 실패
{
  "statusCode": 400,
  "message": "오류 설명",
  "payload": null
}
```

> **NICE / OACX 응답**: 일부 엔드포인트는 `resultCode` / `resultMsg` 형식 사용 (NICE API 래퍼 특성)

### 1.4 HTTP 상태 코드

| 코드 | 의미 |
|------|------|
| `200` | 성공 |
| `400` | 잘못된 요청 (유효성 검사 실패, 토큰 만료 등) |
| `401` | 인증 실패 (API Key 오류) |
| `404` | 리소스 없음 |
| `500` | 서버 내부 오류 (환경변수 미설정 포함) |

---

## 2. ido API — `/api/v1/auth/**`

> **Base**: `http://localhost:8083/api/v1/auth`  
> **인증**: `X-BE-API-Key` (Nginx same-origin proxy)

---

### 2.1 NICE 휴대폰 인증

#### GET /api/v1/auth/nice/phone/url

NICE 휴대폰 본인인증 팝업 URL 발급.

**Query Parameters**

| 파라미터 | 필수 | 타입 | 설명 |
|---------|:----:|------|------|
| `returnUrl` | ✅ | string | 인증 완료 후 리다이렉트 URL |

**응답 200**

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "authUrl": "https://nice.checkplus.co.kr/CheckPlusSafeModel/service.cb?...",
  "requestNo": "REQ-2026051200001"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `resultCode` | string | `"2000"` = 성공, `"5001"` = URL 발급 실패 |
| `authUrl` | string | NICE 표준창 팝업 URL |
| `requestNo` | string | 결과 조회 시 필수 — 반드시 보관 |

**FE 사용 패턴**:
```typescript
const { data } = await beApiInstance.get('/api/v1/auth/nice/phone/url', {
  params: { returnUrl: window.location.href }
});
if (data.resultCode === '2000') {
  window.open(data.authUrl, 'niceAuth', 'width=500,height=600');
  // 인증 완료 후 data.requestNo로 결과 조회
}
```

---

#### POST /api/v1/auth/nice/phone/result

NICE 휴대폰 인증 결과 조회 + CI 처리 (Q3=B — CI는 FE에 미반환).

**요청 본문**

```json
{
  "requestNo": "REQ-2026051200001",
  "returnUrl": "https://www.example.com/callback"
}
```

**응답 200**

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "name": "홍길동",
  "birthDate": "19900101",
  "gender": "M",
  "mobileNo": "01012341234",
  "mobileCarrier": "SKT"
}
```

> **보안**: `ci` 필드는 응답에 포함되지 않습니다 (Q3=B 정책).  
> CI는 ido 내부에서 Q-IM으로만 전달됩니다.

---

#### POST /api/v1/auth/nice/ci-check

CI 기반 회원 존재 확인 (조회 전용 — 등록 없음).

**요청 본문**

```json
{
  "requestNo": "REQ-2026051200001"
}
```

**응답 200**

```json
{
  "exists": true,
  "memberType": "INDIVIDUAL",
  "mbrNo": "MBR-0001"
}
```

---

### 2.2 OACX 간편인증

#### POST /api/v1/auth/oacx/access-info

OACX 간편인증 접근키 및 토큰 발급.

**요청 본문**

```json
{
  "userId": "user-uuid",
  "returnUrl": "https://www.example.com/callback"
}
```

**응답 200**

```json
{
  "resultCode": "0000",
  "resultMsg": "성공",
  "accessKey": "OACX-ACCESS-KEY-...",
  "token": "OACX-TOKEN-..."
}
```

---

#### POST /api/v1/auth/oacx/easysign

OACX 간편서명 결과 처리 + CI 처리 (Q3=B).

**요청 본문**

```json
{
  "accessKey": "OACX-ACCESS-KEY-...",
  "token": "OACX-TOKEN-...",
  "signedData": "base64EncodedSignedData"
}
```

**응답 200**

```json
{
  "resultCode": "0000",
  "resultMsg": "성공",
  "name": "홍길동",
  "birthDate": "19900101",
  "gender": "M",
  "mobileNo": "01012341234"
}
```

> **보안**: CI는 ido 내부 처리 후 Q-IM으로 전달. FE 응답에 미포함.

---

### 2.3 CI 토큰 교환

#### POST /api/v1/auth/ci-token

FE에서 AES-GCM 암호화한 CI를 ido가 Q-IM에 전달하여 ciToken(JWT) 발급.

**요청 본문**

```json
{
  "encryptedCi": "base64(IV(12B)||ciphertext||GCM-tag(16B))",
  "realm": "ucube-qsign",
  "clientId": "onepassCli",
  "flowContext": "CONVERSION"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `encryptedCi` | string | AES-256-GCM 암호화된 CI (FE_AES_GCM_KEY로 암호화) |
| `realm` | string | Q-Sign realm (환경변수: `QSIGN_REALM`) |
| `clientId` | string | Q-Sign clientId (환경변수: `QSIGN_CLIENT_ID`) |
| `flowContext` | string | `CONVERSION` / `REGISTER` / `MYPAGE` |

**응답 200**

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

**FE CI 암호화 흐름**:
```typescript
// 1. 서버에서 AES-GCM 키 취득 (B-1)
const keyRes = await beApiInstance.get('/api/v1/auth/provision/aes-gcm-key');
const aesGcmKey = keyRes.data.aesGcmKey;

// 2. AES-256-GCM 암호화 (Web Crypto API)
const encryptedCi = await encryptCi(ci, aesGcmKey);

// 3. ciToken 발급 요청
const { data } = await beApiInstance.post('/api/v1/auth/ci-token', {
  encryptedCi,
  realm: process.env.QSIGN_REALM || 'ucube-qsign',
  clientId: process.env.QSIGN_CLIENT_ID || 'onepassCli',
  flowContext: 'CONVERSION'
});
const { ciToken } = data.payload.data;
```

---

### 2.4 Provision 유틸리티

#### GET /api/v1/auth/provision/aes-gcm-key

FE AES-GCM 암호화 키 제공 **(B-1 신규 — v3.0)**.

> FE 번들에 키를 포함하지 않고 런타임에 ido 서버에서 취득.

**요청**: 파라미터 없음

**응답 200**

```json
{
  "aesGcmKey": "base64EncodedAES256Key=="
}
```

**응답 500 (FE_AES_GCM_KEY 미설정)**

```json
{
  "timestamp": "2026-05-12T00:00:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "서버 설정 오류: FE_AES_GCM_KEY가 설정되지 않았습니다."
}
```

> **운영 필수**: ido 서버에 `FE_AES_GCM_KEY` 환경변수 설정 필요.  
> 미설정 시 Step3 본인인증 전체 불능.

---

#### GET /api/v1/auth/provision/temp-password

임시 비밀번호 생성 (CSPRNG 기반). Step5 기업회원 프로비저닝 시 사용.

**응답 200**

```json
{
  "tempPassword": "Xk9mP2qR"
}
```

> **v3.0 변경**: 반환 키가 `"password"` → `"tempPassword"`로 수정됨.  
> FE `Step5.tsx`의 `pwRes.data.tempPassword` 참조와 일치.

---

### 2.5 기업 간편인증 콜백

#### POST /api/v1/auth/callback

기업 간편인증(드림시큐리티 EzAuth) 콜백 수신 처리 (Q2=B).

**요청 본문**

```json
{
  "txId": "tx-uuid",
  "status": "SUCCESS",
  "authData": "base64EncodedAuthData"
}
```

**응답 200**

```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "txId": "tx-uuid",
      "processed": true
    }
  }
}
```

---

## 3. ido API — 세션/Handoff/SLO

### 3.1 FE 세션

#### POST /api/v1/fe-session/create

인증 완료 후 FE 세션 생성.

**요청 본문**

```json
{
  "authResultId": "string",
  "correlationId": "string",
  "returnUrl": "https://example-agency.go.kr/callback"
}
```

**응답 200**

```json
{
  "code": "SUCCESS",
  "data": {
    "feSessionId": "uuid",
    "returnUrl": "https://example-agency.go.kr/callback",
    "expiresAt": "2026-05-12T01:00:00Z"
  }
}
```

---

#### GET /api/v1/fe-session/validate

FE 세션 유효성 검증. `feSessionId` 쿠키 자동 포함.

**응답 200**

```json
{
  "code": "SUCCESS",
  "data": {
    "valid": true,
    "expiresAt": "2026-05-12T01:00:00Z"
  }
}
```

---

### 3.2 Handoff

#### POST /api/v1/handoff/issue

기관 핸드오프 티켓 발급.

**요청 본문**

```json
{
  "agencyId": "agency-uuid",
  "returnUrl": "https://agency.go.kr/callback",
  "subjectAttributes": {
    "name": "홍길동",
    "instMbrId": "IM-0001"
  }
}
```

**응답 200**

```json
{
  "code": "SUCCESS",
  "data": {
    "ticket": "encrypted-handoff-ticket",
    "expiresAt": "2026-05-12T00:05:00Z"
  }
}
```

---

#### POST /api/v1/handoff/verify

핸드오프 티켓 검증 (기관 서버에서 호출).

**요청 본문**

```json
{
  "ticket": "encrypted-handoff-ticket"
}
```

**응답 200**

```json
{
  "code": "SUCCESS",
  "data": {
    "agencySubjectId": "AGENCY-SUB-001",
    "attributes": {
      "name": "홍길동"
    },
    "issuedAt": "2026-05-12T00:00:00Z"
  }
}
```

---

### 3.3 SLO

#### POST /api/v1/auth/slo (또는 `/api/v1/fe-session/slo`)

FE → ido SLO 요청. Keycloak 세션 종료 + Redis feSession 삭제.

**요청 본문**

```json
{
  "feSessionId": "uuid"
}
```

**응답 200**

```json
{
  "code": "SUCCESS",
  "data": { "loggedOut": true }
}
```

---

## 4. Q-IM External API — `/api/ext/**`

> **경로**: FE → ido (forward proxy) → Q-IM  
> **인증**: `X-Ext-Api-Key` (ido 서버가 주입 — FE 미포함)  
> **구현 책임**: Q-IM 팀

---

### 4.1 클라이언트 목록

#### GET /api/ext/clients

유관기관 클라이언트 목록 조회. FE Step4에서 사용.

**응답 200**

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

---

### 4.2 약관/동의

#### POST /api/ext/consent/token

동의 세션 토큰 발급. Step2 시작 시 호출.

**요청 본문**

```json
{
  "realm": "qim",
  "client": "sp-smeg"
}
```

**응답 200**

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

약관 목록 조회.

**Query Parameters**

| 파라미터 | 필수 | 설명 |
|---------|:----:|------|
| `realm` | ✅ | `qim` |
| `client` | ✅ | `sp-smeg` |
| `lang` | ✅ | `ko` |

**응답 200**

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
            "body": "<p>약관 내용 HTML (DOMPurify sanitize 후 렌더링)</p>"
          }
        }
      ]
    }
  }
}
```

---

#### POST /api/ext/consent

약관 동의 제출.

**요청 본문**

```json
{
  "consentToken": "eyJhbGciOiJIUzI1NiJ9...",
  "agreements": [
    { "termId": "term-001", "agreed": true },
    { "termId": "term-002", "agreed": false }
  ]
}
```

**응답 200**

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

**응답 400 (토큰 만료 — 필수)**

```json
{
  "statusCode": 400,
  "message": "INVALID_CONSENT_TOKEN",
  "payload": null
}
```

> **⚠️ 계약 사항**: FE가 `message` 또는 `body`에 `"INVALID_CONSENT_TOKEN"` 포함 여부로 자동 재발급 처리.

---

### 4.3 CI Token 교환

#### POST /api/ext/ci/token

암호화된 CI → ciToken(JWT) 교환.

> **v3.0**: FE 직접 호출 → ido 경유. `encryptedCi`의 암호화 키가 `QIM_AES_SHARED_KEY`로 변경됨.

**요청 본문** (ido → Q-IM)

```json
{
  "encryptedCi": "base64(IV(12B)||ciphertext||GCM-tag(16B))",
  "realm": "ucube-qsign",
  "clientId": "onepassCli",
  "flowContext": "CONVERSION"
}
```

**응답 200**

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

---

### 4.4 회원 조회

#### GET /api/ext/members/{mbrNo}

개인회원 정보 조회.

**응답 200**

```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "mbrNo": "MBR-0001",
      "mbrUuid": "uuid-...",
      "name": "홍길동",
      "phoneNumber": "01012341234",
      "email": "hong@example.com"
    }
  }
}
```

---

#### GET /api/ext/enterprises/{entMbrNo}

기업회원 정보 조회.

**응답 200**

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

**응답 200**

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

### 4.5 중복 확인

#### GET /api/ext/check-duplicate

아이디/정보 중복 확인.

**Query Parameters**

| 파라미터 | 필수 | 설명 |
|---------|:----:|------|
| `type` | ✅ | `IND` (개인) / `ENT` (기업) |
| `value` | ✅ | 확인할 값 |

**응답 200**

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

### 4.6 사업자 조회

#### POST /api/ext/business/status

사업자 상태 조회 (국세청 OpenAPI).

**요청 본문**

```json
{ "brno": "123-45-67890" }
```

**응답 200**

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

**요청 본문**

```json
{
  "brno": "123-45-67890",
  "rprsvNm": "김대표",
  "openDt": "20200101"
}
```

**응답 200**

```json
{
  "statusCode": 200,
  "payload": {
    "data": { "valid": true }
  }
}
```

---

### 4.7 기업인증 결과

#### GET /api/ext/auth-status

기업인증 진행 상태 조회.

**응답 200**

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

**상태값**: `PENDING` / `SUCCESS` / `FAILED`

---

#### GET /api/ext/auth-result/{txId}

기업인증 최종 결과 조회.

**응답 200**

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

### 4.8 전환 가능 여부

#### POST /api/ext/provision/users/check-conversion

개인회원 OnePass 전환 가능 여부 확인.

**요청 본문**

```json
{ "ciToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

**응답 200**

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

### 4.9 프로비저닝 (회원전환)

#### POST /api/ext/provision/users

개인회원 프로비저닝. 회원전환 Step5.

**요청 본문**

```json
{
  "ciToken": "eyJhbGciOiJIUzI1NiJ9...",
  "loginId": "hong123",
  "password": "Secure!Password1",
  "name": "홍길동",
  "phoneNumber": "01012341234",
  "email": "hong@example.com",
  "selectedClients": ["client-uuid-1", "client-uuid-2"],
  "consentEventId": "consent-event-uuid",
  "smsAgree": true,
  "kakaoAgree": false,
  "emailAgree": true
}
```

**응답 200**

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

기업회원 프로비저닝. 회원전환 Step5.

**요청 본문**

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

**응답 200**

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

### 4.10 신규 회원가입

#### POST /api/ext/register/individual

개인회원 신규 등록 (전환 아닌 신규). 요청/응답 형식은 provision/users와 동일.

---

#### POST /api/ext/register/enterprise

기업회원 신규 등록. 요청/응답 형식은 provision/enterprises와 동일.

---

### 4.11 회원정보 수정

#### POST /api/ext/provision/users/modify_local

개인회원 정보 수정.

**요청 본문**

```json
{
  "mbrUuid": "uuid-...",
  "name": "홍길동",
  "phoneNumber": "01012341234",
  "email": "new@example.com"
}
```

**응답 200**

```json
{
  "statusCode": 200,
  "payload": {
    "data": { "updated": true }
  }
}
```

---

#### POST /api/ext/provision/enterprises/modify_local

기업회원 정보 수정. 요청/응답 형식 유사.

---

### 4.12 유관기관 관리

#### POST /api/ext/provision/users/{uuid}/affiliations/add

개인회원 유관기관 추가.

**요청 본문**

```json
{ "clientId": "client-uuid-1" }
```

**응답 200**

```json
{
  "statusCode": 200,
  "payload": {
    "data": { "added": true }
  }
}
```

---

#### POST /api/ext/provision/enterprises/{uuid}/affiliations/add

기업회원 유관기관 추가. 요청/응답 동일.

---

#### POST /api/ext/provision/users/{uuid}/affiliations/withdraw

개인회원 유관기관 탈퇴. `ciToken` 필수.

**요청 본문**

```json
{
  "clientId": "client-uuid-1",
  "ciToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**응답 200**

```json
{
  "statusCode": 200,
  "payload": {
    "data": { "withdrawn": true }
  }
}
```

---

#### POST /api/ext/provision/enterprises/{uuid}/affiliations/withdraw

기업회원 유관기관 탈퇴. 요청/응답 동일.

---

## 5. ido → Q-IM 내부 API

> **Base**: `http://q-im:8082/api/v1/internal`  
> **인증**: `X-Internal-Api-Key` 필수  
> **호출 주체**: ido 서버만 호출 가능 (FE 직접 호출 불가)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/users` | 사용자 등록 Upsert |
| `GET` | `/users/{qimUserId}` | 사용자 조회 |
| `POST` | `/member/lookup-by-ci` | CI(Hash)로 회원 조회 |
| `GET` | `/member/lookup-by-hash` | identifierHash로 회원 조회 |

### POST /api/v1/internal/users

**요청 본문**

```json
{
  "qimUserId": "Q-IM-UUID",
  "identifierHash": "sha256HexString",
  "name": "홍길동",
  "attributes": {
    "memberType": "INDIVIDUAL"
  }
}
```

---

### POST /api/v1/internal/member/lookup-by-ci

**요청 본문**

```json
{
  "ciHash": "sha256(ci)HexString"
}
```

**응답 200**

```json
{
  "found": true,
  "member": {
    "mbrNo": "MBR-0001",
    "mbrUuid": "uuid-...",
    "memberType": "INDIVIDUAL"
  }
}
```

---

## 6. 에러 코드 및 응답 형식

### 6.1 ido 에러 코드

| 코드 | HTTP | 설명 |
|------|------|------|
| `E-IDO-101` | 400 | Handoff Ticket 만료 |
| `E-IDO-102` | 401 | API Key 인증 실패 |
| `E-IDO-201` | 500 | FE_AES_GCM_KEY 미설정 |
| `E-IDO-301` | 502 | NICE API 호출 실패 |
| `E-IDO-302` | 502 | OACX API 호출 실패 |
| `E-IDO-401` | 502 | Q-IM forward proxy 실패 |

### 6.2 Q-IM 약관 에러 코드 (계약)

| 코드/문자열 | 설명 |
|------------|------|
| `INVALID_CONSENT_TOKEN` | 동의 토큰 만료/무효 — FE 자동 재발급 트리거 |
| `TERM_NOT_FOUND` | 약관 없음 |
| `ALREADY_CONSENTED` | 이미 동의 완료 |

### 6.3 NICE 결과 코드

| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `5001` | URL 발급 실패 |
| `5000` | 내부 오류 |

---

## 7. v3.0 변경 이력

### 신규 엔드포인트

| 엔드포인트 | 설명 | PR |
|-----------|------|---|
| `GET /api/v1/auth/provision/aes-gcm-key` | FE AES-GCM 키 서버 제공 (B-1) | #77 |

### 변경된 엔드포인트

| 엔드포인트 | 변경 내용 | PR |
|-----------|---------|---|
| `GET /api/v1/auth/provision/temp-password` | 반환 키 `"password"` → `"tempPassword"` | #77 |
| `POST /api/v1/auth/ci-token` | Q-Sign realm/clientId 환경변수화 | #77 |
| `POST /api/ext/ci/token` | CI 암호화 키 변경 (FE → ido 경유) | #77 |

### 제거된 기능

| 항목 | 설명 | PR |
|------|------|---|
| `SKIP_AUTH` 분기 | AppRoutes/utils.ts mock 분기 제거 | #77 |
| FE 직접 Q-IM 호출 | extInstance → beApiInstance (ido 경유) | #76 |
| FE 번들 `AES_GCM_KEY` | 서버 취득으로 전환 (B-1) | #76 |
| FE 번들 `EXT_API_KEY` | ido 서버사이드 주입 (B-5) | #76 |

---

*최종 수정: 2026-05-12 / PR #77 반영*  
*다음 업데이트 예정: Q-IM /api/ext/** 스펙 교차검증 완료 후 응답 형식 확정*
