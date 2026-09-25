# OnePass 통합인증 플랫폼 — 전체 API 레퍼런스

> **버전**: v3.0.0 (PR #77 반영 — 2026-05-12 GAP 패치 완료 기준)  
> **최종 수정**: 2026-05-12  
> **기준 커밋**: `b8e95d4`

---

## 목차

- [공통 규약](#0-공통-규약)
- [1. ido API (:8083)](#1-ido-api-8083)
  - [1-A. 본인인증 BFF (`/api/v1/auth/**`)](#1-a-본인인증-bff)
  - [1-B. FE 프로비저닝 보조 API](#1-b-fe-프로비저닝-보조-api)
  - [1-C. CI 토큰 교환 (Q3=B)](#1-c-ci-토큰-교환)
  - [1-D. Q-IM Forward Proxy (`/api/ext/**`) (B-5)](#1-d-q-im-forward-proxy)
  - [1-E. FE 세션 API (`/api/v1/fe-session/**`)](#1-e-fe-세션-api)
  - [1-F. SLO API (`/api/v1/slo/**`)](#1-f-slo-api)
  - [1-G. Handoff API (`/api/v1/handoff/**`)](#1-g-handoff-api)
  - [1-H. 브로커 API (`/api/v1/broker/**`)](#1-h-브로커-api)
  - [1-I. 기관 이벤트 API (`/api/v1/agency/**`)](#1-i-기관-이벤트-api)
  - [1-J. Admin API (`/api/v1/admin/**`)](#1-j-admin-api)
  - [1-K. 회원 조회 API (`/api/v1/member/**`)](#1-k-회원-조회-api)
  - [1-L. Q-IM SP 수신 API (`/api/qim/sp/v1/**`)](#1-l-q-im-sp-수신-api)
- [2. Q-IM External API (`/api/ext/**`)](#2-q-im-external-api)
  - [2-A. 클라이언트 조회](#2-a-클라이언트-조회)
  - [2-B. 약관 / 동의](#2-b-약관--동의)
  - [2-C. CI 토큰 교환](#2-c-ci-토큰-교환)
  - [2-D. 회원 조회 / 수정](#2-d-회원-조회--수정)
  - [2-E. 중복 확인](#2-e-중복-확인)
  - [2-F. 사업자 조회](#2-f-사업자-조회)
  - [2-G. 기업인증 결과](#2-g-기업인증-결과)
  - [2-H. 프로비저닝](#2-h-프로비저닝)
  - [2-I. 신규 회원가입](#2-i-신규-회원가입)
  - [2-J. 유관기관 관리](#2-j-유관기관-관리)
- [3. Q-Sign API (:8081)](#3-q-sign-api-8081)
- [4. 에러 코드 목록](#4-에러-코드-목록)
- [5. 변경 이력](#5-변경-이력)

---

## 0. 공통 규약

### 0.1 서비스별 기본 URL

| 서비스 | 로컬 URL | 설명 |
|--------|---------|------|
| **ido** | `http://localhost:8083` | FE BFF + 오케스트레이터. FE가 유일하게 직접 통신하는 서버 |
| **Q-IM** (via ido) | `http://localhost:8083/api/ext/**` | ido forward proxy 경유. FE는 직접 접근 불가 |
| **Q-Sign** | `http://localhost:8081` | 인증 SoR. ido 내부 통신 전용 |
| **Q-IM** (internal) | `http://localhost:8082` | 식별 SoR. ido 내부 통신 전용 |

> **v3.0 핵심**: FE는 **모든 API를 `http://localhost:8083`(ido) 하나로** 호출합니다.  
> `/api/ext/**`도 ido가 Q-IM으로 forward proxy합니다 (B-5 보안 패치).

### 0.2 FE Axios 인스턴스별 라우팅

| 인스턴스 | 파일 | 헤더 | 대상 경로 |
|---------|------|------|---------|
| `beApiInstance` | `api/beInstance.ts` | `X-BE-API-Key` | `/api/v1/auth/**`, `/api/ext/**` |
| `beInstance` (legacy) | `api/beInstance.ts` | `X-BE-API-Key` | 기존 플랫폼 API |
| `extInstance` | `api/extInstance.ts` | *(beApiInstance re-export)* | 하위호환용 — 신규 코드 사용 금지 |

### 0.3 공통 응답 형식 (Q-IM /api/ext/**)

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
  "message": "INVALID_CONSENT_TOKEN",
  "payload": null
}
```

### 0.4 공통 요청 헤더

| 헤더 | 대상 | 필수 | 설명 |
|------|------|:----:|------|
| `Content-Type: application/json` | 전체 | ✅ | |
| `X-BE-API-Key` | ido `/api/**` | ✅ | ido 서버 인증 키 |
| `X-Ext-Api-Key` | Q-IM `/api/ext/**` | ✅ | **ido 서버가 주입** (FE 미설정) |
| `X-Internal-Api-Key` | Q-IM 내부 API | ✅ | ido → Q-IM 서버 간 인증 |
| `X-Correlation-Id` | 전체 | 권장 | 없으면 서버 UUID 자동 생성 |

### 0.5 보안 정책 참조

| 코드 | 내용 |
|------|------|
| **B-1** | AES-GCM 키 FE 번들 미포함 → `GET /api/v1/auth/provision/aes-gcm-key` 런타임 취득 |
| **B-5** | EXT_API_KEY FE 번들 미포함 → ido forward proxy가 서버사이드 주입 |
| **Q3=B** | CI(연계정보) FE 미반환 — ido에서 처리 후 ciToken만 FE에 전달 |

---

## 1. ido API (:8083)

### 1-A. 본인인증 BFF

**Base Path**: `/api/v1/auth`  
**인증**: `X-BE-API-Key`

---

#### GET /api/v1/auth/nice/phone/url

NICE 휴대폰 본인인증 URL 발급.

| 파라미터 | 위치 | 필수 | 설명 |
|---------|------|:----:|------|
| `returnUrl` | query | 선택 | 인증 완료 후 리다이렉트 URL |

**응답 200**:
```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "authUrl": "https://nice.checkplus.co.kr/...",
  "requestNo": "REQ-20260512-001"
}
```

**오류 코드**:
| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `5001` | NICE URL 발급 실패 |
| `5000` | 서버 내부 오류 |

**FE 사용 패턴**:
```typescript
const { data } = await beApiInstance.get('/api/v1/auth/nice/phone/url', {
  params: { returnUrl: window.location.href },
});
// data.authUrl로 팝업 오픈 → 완료 후 requestNo로 결과 조회
window.open(data.authUrl, 'niceAuth', 'width=500,height=600');
```

---

#### POST /api/v1/auth/nice/phone/result

NICE 팝업 완료 후 인증 결과 조회. CI는 응답에 포함되지 않음 (Q3=B).

**요청 본문**:
```json
{
  "web_transaction_id": "WEB-TXN-001",
  "request_no": "REQ-20260512-001"
}
```

**응답 200**:
```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "resultData": {
    "name": "홍길동",
    "birthdate": "19900101",
    "gender": "M",
    "nationalInfo": "0",
    "di": "DI-BASE64-HASH",
    "mobileCo": "SKT",
    "mobileNo": "01012341234"
  }
}
```

> **Q3=B**: `ci` 필드는 응답에 없음. CI는 ido 내부에서만 처리.

**오류 코드**:
| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `4000` | 파라미터 오류 (request_no 누락, 세션 만료) |
| `5002` | NICE 결과 조회 실패 |
| `5003` | 무결성 검증 실패 |
| `5000` | 서버 내부 오류 |

---

#### POST /api/v1/auth/nice/ci-check

CI 기반 회원 존재 확인 (조회 전용).

**요청 본문 (개인회원)**:
```json
{
  "ci": "CI-PLAINTEXT-VALUE",
  "mbrDvsnCd": "A101",
  "indvlMbrNm": "홍길동",
  "indvlMbrId": "hong123"
}
```

**요청 본문 (기업회원)**:
```json
{
  "ci": "CI-PLAINTEXT-VALUE",
  "mbrDvsnCd": "A102",
  "cmpMbrId": "company123",
  "bizno": "1234567890"
}
```

**응답 200**:
```json
{
  "resultCode": "2000",
  "result": true,
  "indvlMbrId": "hong123"
}
```

**오류 코드**:
| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `4000` | 파라미터 오류 (ci 누락, mbrDvsnCd 잘못됨, bizno 누락) |

---

#### POST /api/v1/auth/oacx/access-info

OACX 간편서명 초기화 정보 발급.

**요청 본문**:
```json
"simpleAuth"
```
> Content-Type: `application/json`, 값은 plain JSON string.

**응답 200**:
```json
{
  "resultCode": "2000",
  "fn": "simpleAuth",
  "accKey": "OACX-ACC-KEY",
  "accToken": "OACX-ACC-TOKEN"
}
```

**오류 코드**:
| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `5001` | OACX 접근정보 발급 실패 |

**FE 사용 패턴**:
```typescript
const { data } = await beApiInstance.post(
  '/api/v1/auth/oacx/access-info',
  JSON.stringify('simpleAuth'),
  { headers: { 'Content-Type': 'application/json' } },
);
OACXsdk.init({ fn: data.fn, accKey: data.accKey, accToken: data.accToken });
```

---

#### POST /api/v1/auth/oacx/easysign

OACX 간편서명 완료 콜백 처리. CI는 응답에 없음 (Q3=B).

**요청 본문**:
```json
{
  "fn": "authComplete",
  "status": "success",
  "res": { "resultCode": "200", "resultData": "..." }
}
```

**응답 200**:
```json
{
  "resultCode": "2000",
  "name": "홍길동",
  "birthday": "19900101",
  "phone": "01012341234"
}
```

> **Q3=B**: `ci` 필드는 응답에서 제외 (`@JsonInclude(NON_NULL)`).

**오류 코드**:
| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `4000` | fn이 "authComplete"가 아님 |
| `4001` | OACX resultCode ≠ "200" (인증 실패/취소) |
| `5002` | JWT 복호화 실패 |

---

#### POST /api/v1/auth/callback

기업 간편인증 콜백 수신 (Q2=B). 현재 FE에서 미사용 — 향후 기업인증 구현 시 사용.

**요청 본문**:
```json
{
  "txId": "TX-001",
  "tokenId": "TOKEN-001",
  "siteInfo": { "siteId": "SITE-001" },
  "userToken": "USER-TOKEN",
  "hubToken": "HUB-TOKEN"
}
```

**응답 200**:
```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "resultData": {
    "name": "주식회사 스마트",
    "businessNumber": "123-45-67890",
    "birth": "20200101",
    "phone": "01012341234",
    "bizOpendt": "20200101"
  }
}
```

---

### 1-B. FE 프로비저닝 보조 API

---

#### GET /api/v1/auth/provision/temp-password

CSPRNG 기반 임시 비밀번호 생성. Step5 기업 프로비저닝 시 사용.

**응답 200**:
```json
{ "tempPassword": "aB3#Kp9!mZ2@" }
```

> **v3.0 변경**: 이전 버전 응답 키 `"password"` → `"tempPassword"` 수정.  
> FE Step5.tsx: `pwRes.data.tempPassword` 로 참조.

**비밀번호 정책**:
- 대문자·소문자·숫자·특수문자 각 2개 이상
- 기본 12자
- `SecureRandom` 기반 CSPRNG (Math.random 미사용)

---

#### GET /api/v1/auth/provision/aes-gcm-key ⭐ v3.0 신규

FE AES-GCM 암호화 키 런타임 제공 (B-1 보안 패치).

**응답 200**:
```json
{ "aesGcmKey": "base64EncodedAES256Key==" }
```

**응답 500** (FE_AES_GCM_KEY 환경변수 미설정):
```json
{
  "timestamp": "2026-05-12T00:00:00Z",
  "status": 500,
  "error": "Internal Server Error",
  "message": "서버 설정 오류: FE_AES_GCM_KEY가 설정되지 않았습니다."
}
```

> **운영 필수**: `FE_AES_GCM_KEY` 미설정 시 Step3 본인인증 전체 불능.  
> FE `utils/crypto/aesGcm.ts`의 `fetchAesGcmKey()`가 호출함.  
> 키 형식: Base64 인코딩된 AES-256 32바이트 키.

---

### 1-C. CI 토큰 교환

---

#### POST /api/v1/auth/ci-token

FE에서 AES-GCM 암호화한 CI를 ido가 복호화→Q-IM 재암호화→ciToken 반환 (Q3=B).

**요청 본문**:
```json
{
  "encryptedCi": "base64(IV(12B)||ciphertext||GCM-tag(16B))",
  "realm": "ucube-qsign",
  "clientId": "onepassCli",
  "flowContext": "CONVERSION"
}
```

| 필드 | 필수 | 설명 |
|------|:----:|------|
| `encryptedCi` | ✅ | AES-256-GCM 암호화된 CI. base64(IV\|\|ciphertext\|\|tag) |
| `realm` | ✅ | Q-Sign realm. 환경변수 `QSIGN_REALM` (기본: `ucube-qsign`) |
| `clientId` | ✅ | Q-Sign clientId. 환경변수 `QSIGN_CLIENT_ID` (기본: `onepassCli`) |
| `flowContext` | 선택 | `CONVERSION` / `REGISTER` |

**응답 200**:
```json
{
  "resultCode": "2000",
  "ciToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

**오류 코드**:
| resultCode | 설명 |
|-----------|------|
| `2000` | 성공 |
| `4000` | 파라미터 오류 |
| `4010` | AES-GCM 복호화 실패 (키 불일치) |
| `5000` | FE_AES_GCM_KEY 미설정 |
| `5010` | Q-IM 연동 실패 |

**CI 암호화 흐름**:
```
encryptCi(ci)                               // aesGcm.ts
  1. GET /api/v1/auth/provision/aes-gcm-key → aesGcmKey
  2. Web Crypto API AES-256-GCM 암호화
  3. base64(IV(12B) || ciphertext || GCM-tag(16B))
  → encryptedCi

POST /api/v1/auth/ci-token { encryptedCi, realm, clientId }
  ido: AES-GCM 복호화 (FE_AES_GCM_KEY)
     → QIM_AES_SHARED_KEY로 재암호화
     → Q-IM /api/ext/ci/token 호출
  Q-IM: 복호화(IDEM_REGISTRY_AES_SHARED_KEY) → ciToken 발급
  ← { ciToken }
```

---

### 1-D. Q-IM Forward Proxy

**Base Path**: `/api/ext/**`  
**인증**: `X-BE-API-Key` (FE→ido), ido가 `X-Ext-Api-Key` 자동 주입 (B-5)

> Q-IM `/api/ext/**` 전체를 ido가 forward proxy합니다.  
> 각 엔드포인트 스펙은 [섹션 2. Q-IM External API](#2-q-im-external-api)를 참조하세요.

**특수 처리**:

```
/api/ext/ci/**  →  POST /api/v1/auth/ci-token 으로 내부 재라우팅
                   (ido가 CI 복호화/재암호화 후 Q-IM 전달 — Q3=B)
/api/ext/**     →  Q-IM base-url + 동일 경로 forward
```

---

### 1-E. FE 세션 API

**Base Path**: `/api/v1/fe-session`

---

#### GET /api/v1/fe-session/check

딥링크 진입 시 기존 FE 세션 유효성 확인.

| 파라미터 | 위치 | 필수 | 설명 |
|---------|------|:----:|------|
| `returnUrl` | query | 선택 | 리다이렉트 URL (화이트리스트 검증) |
| `feSessionId` | cookie | 선택 | FE 세션 쿠키 |

**응답 200 (유효)**:
```json
{
  "code": "SESSION_VALID",
  "returnUrl": "https://example.go.kr/callback",
  "expiresAt": "2026-05-12T01:00:00Z"
}
```

**응답 200 (만료/없음)**:
```json
{ "code": "SESSION_NOT_FOUND" }
```

**응답 400 (returnUrl 불허)**:
```json
{ "code": "INVALID_RETURN_URL", "message": "허용되지 않은 returnUrl" }
```

---

#### POST /api/v1/fe-session/logout

FE 세션 로그아웃 (feSessionId 쿠키 제거).

**응답 204**: No Content

---

#### POST /api/v1/fe-session

FE 세션 생성 (Q-Sign → ido 내부 호출).

**요청 본문**:
```json
{
  "authResultId": "AUTH-RESULT-UUID",
  "correlationId": "CORR-ID",
  "returnUrl": "https://example.go.kr/callback"
}
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "feSessionId": "FE-SESSION-UUID",
    "returnUrl": "https://example.go.kr/callback",
    "expiresAt": "2026-05-12T01:00:00Z"
  }
}
```

---

### 1-F. SLO API

**Base Path**: `/api/v1/slo`

---

#### POST /api/v1/slo/initiate

SLO(Single Logout) 시작. FE 로그아웃 버튼 클릭 시 호출.

**헤더**: `feSessionId` 쿠키 (없어도 204 반환 — 멱등)

**처리 순서**:
1. `feSessionId` 쿠키로 Redis 세션 즉시 삭제
2. Q-Sign → Keycloak 세션 종료 (비치명적)
3. 기관 로그아웃 Webhook Outbox 적재 (비치명적)
4. 감사 로그 기록
5. `feSessionId` 쿠키 `Clear-Site-Data` 제거

**응답 204**: No Content

**FE 사용 패턴**:
```typescript
// api/utils.ts
await beApiInstance.post('/api/v1/slo/initiate');
// → Redux store 초기화 → /login 리다이렉트
```

---

### 1-G. Handoff API

**Base Path**: `/api/v1/handoff`

---

#### POST /api/v1/handoff/issue

Handoff Ticket 발급 (기관 인증 결과 전달용).

**요청 본문**:
```json
{
  "agencyCode": "AGENCY-001",
  "correlationId": "CORR-ID",
  "authResultId": "AUTH-RESULT-UUID",
  "returnUrl": "https://agency.go.kr/callback"
}
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "ticketId": "TICKET-UUID",
    "handoffUrl": "https://agency.go.kr/callback?ticket=TICKET-UUID",
    "expiresAt": "2026-05-12T00:05:00Z"
  }
}
```

---

#### POST /api/v1/handoff/verify

Handoff Ticket 검증 (기관 서버에서 호출).

**요청 본문**:
```json
{ "ticketId": "TICKET-UUID" }
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "agencySubjectId": "AGENCY-SUBJECT-ID-HMAC",
    "mbrDvsnCd": "A101",
    "returnUrl": "https://agency.go.kr/callback"
  }
}
```

---

#### DELETE /api/v1/handoff/{ticketId}

Handoff Ticket 폐기.

**응답 204**: No Content

---

### 1-H. 브로커 API

**Base Path**: `/api/v1/broker`

---

#### GET /api/v1/broker/{provider}/authorize

OIDC 인증 URL 생성 (provider: `keycloak`, `kakao` 등).

| 파라미터 | 위치 | 필수 |
|---------|------|:----:|
| `returnUrl` | query | 선택 |
| `correlationId` | query | 선택 |

**응답 302**: Keycloak Authorization URL로 리다이렉트

---

#### GET /api/v1/broker/kakao/authorize

Kakao OIDC 인증 URL 생성 (별도 처리).

**응답 302**: Kakao OAuth URL로 리다이렉트

---

### 1-I. 기관 이벤트 API

**Base Path**: `/api/v1/agency`

---

#### GET /api/v1/agency/events

기관별 폴링 이벤트 조회.

| 파라미터 | 위치 | 필수 | 설명 |
|---------|------|:----:|------|
| `agencyCode` | query | ✅ | 기관 코드 |
| `since` | query | 선택 | ISO8601 타임스탬프 (이후 이벤트만) |

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "events": [
      {
        "dispatchId": "DISPATCH-UUID",
        "eventType": "AUTH_COMPLETE",
        "payload": { ... },
        "createdAt": "2026-05-12T00:00:00Z"
      }
    ]
  }
}
```

---

#### POST /api/v1/agency/events/{dispatchId}/read

이벤트 읽음 처리.

**응답 200**:
```json
{ "code": "SUCCESS" }
```

---

### 1-J. Admin API

**Base Path**: `/api/v1/admin/agencies`

---

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/admin/agencies` | 기관 등록 |
| `GET` | `/api/v1/admin/agencies` | 기관 목록 조회 |
| `GET` | `/api/v1/admin/agencies/{agencyCode}` | 기관 상세 조회 |
| `PUT` | `/api/v1/admin/agencies/{agencyCode}` | 기관 정보 수정 |
| `POST` | `/api/v1/admin/agencies/{agencyCode}/activate` | 기관 활성화 |
| `POST` | `/api/v1/admin/agencies/{agencyCode}/deactivate` | 기관 비활성화 |
| `POST` | `/api/v1/admin/agencies/{agencyCode}/rotate-key` | 기관 키 갱신 |
| `GET` | `/api/v1/admin/agencies/{agencyCode}/history` | 기관 이력 조회 |

---

### 1-K. 회원 조회 API

**Base Path**: `/api/v1/member`

---

#### POST /api/v1/member/lookup

CI 기반 회원 조회.

**요청 본문**:
```json
{ "ci": "CI-PLAINTEXT" }
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "mbrNo": "MBR-0001",
    "mbrUuid": "uuid-...",
    "mbrDvsnCd": "A101"
  }
}
```

---

#### GET /api/v1/member/lookup/hash

identifierHash 기반 회원 조회.

| 파라미터 | 위치 | 필수 |
|---------|------|:----:|
| `hash` | query | ✅ |

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": { "mbrNo": "MBR-0001", "mbrUuid": "uuid-..." }
}
```

---

### 1-L. Q-IM SP 수신 API

**Base Path**: `/api/qim/sp/v1` (Q-IM → ido 아웃바운드)  
**인증**: `X-Qim-Inbound-Api-Key` (PBKDF2 해시 검증)

---

#### POST /api/qim/sp/v1/member/query

Q-IM에서 회원 조회 이벤트 수신.

**요청 본문**:
```json
{ "instMbrId": "INST-MBR-001" }
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": { "mbrNo": "MBR-0001" }
}
```

---

#### POST /api/qim/sp/v1/member/register

Q-IM에서 회원 등록 이벤트 수신.

**요청 본문**:
```json
{
  "instMbrId": "INST-MBR-001",
  "mbrDvsnCd": "A101",
  "name": "홍길동"
}
```

**응답 200**: `{ "code": "SUCCESS" }`

---

#### POST /api/qim/sp/v1/member/withdraw

Q-IM에서 회원 탈퇴 이벤트 수신.

**응답 200**: `{ "code": "SUCCESS" }`

---

## 2. Q-IM External API

> **경로**: `/api/ext/**`  
> **접근 방법**: FE → ido (X-BE-API-Key) → Q-IM (X-Ext-Api-Key, ido 자동 주입)  
> **인증**: ido가 `X-Ext-Api-Key: IDEM_HUB_REGISTRY_EXT_API_KEY` 자동 주입 (B-5)  
> **공통 응답 형식**: `{ statusCode, payload: { data } }` 또는 `{ statusCode, message }`

### 2-A. 클라이언트 조회

---

#### GET /api/ext/clients

유관기관(클라이언트) 목록 조회. ConversionStep4에서 사용.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "clients": [
        { "id": "client-uuid", "name": "스마트공장", "description": "스마트공장 플랫폼" },
        { "id": "client-uuid-2", "name": "기업마당", "description": "기업지원 통합포털" }
      ]
    }
  }
}
```

---

### 2-B. 약관 / 동의

---

#### POST /api/ext/consent/token

동의 토큰 발급. Step2 시작 시 호출.

**요청 본문**:
```json
{ "realm": "qim", "client": "sp-smeg" }
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": { "data": { "consentToken": "eyJ..." } }
}
```

---

#### GET /api/ext/terms/bundle

약관 번들 조회.

| 파라미터 | 필수 | 기본값 |
|---------|:----:|--------|
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
          "content": { "body": "<p>약관 HTML...</p>" }
        }
      ]
    }
  }
}
```

---

#### POST /api/ext/consent

동의 제출. Step2 완료 시 호출.

**요청 본문**:
```json
{
  "consentToken": "eyJ...",
  "agreements": [
    { "termId": "term-001", "agreed": true }
  ]
}
```

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": { "data": { "consentEventId": "consent-event-uuid" } }
}
```

**응답 400 (토큰 만료)** ⚠️ FE 감지 필수:
```json
{
  "statusCode": 400,
  "message": "INVALID_CONSENT_TOKEN"
}
```

> FE Step2는 `message.includes('INVALID_CONSENT_TOKEN')` 감지 후 자동 재발급+재시도.  
> Q-IM은 반드시 `message` 또는 `body` 필드에 `INVALID_CONSENT_TOKEN` 문자열을 포함해야 함.

---

### 2-C. CI 토큰 교환

---

#### POST /api/ext/ci/token

> **v3.0**: FE에서 직접 호출 불가 → ido가 `/api/ext/ci/**`를 `/api/v1/auth/ci-token`으로 내부 재라우팅.  
> 아래 스펙은 **ido → Q-IM** 서버 간 호출 기준.

**요청 본문 (ido → Q-IM)**:
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
  "payload": { "data": { "ciToken": "eyJ..." } }
}
```

> 암호화 키: `IDEM_REGISTRY_AES_SHARED_KEY` (ido-Q-IM 합의 키)

---

### 2-D. 회원 조회 / 수정

---

#### GET /api/ext/members/{mbrNo}

개인회원 상세 조회.

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
      "email": "hong@example.com"
    }
  }
}
```

---

#### GET /api/ext/enterprises/{entMbrNo}

기업회원 상세 조회.

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

개인회원 유관기관 목록 조회.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": {
      "affiliations": [
        { "clientId": "client-uuid", "clientName": "스마트공장", "status": "ACTIVE" }
      ]
    }
  }
}
```

---

#### GET /api/ext/enterprises/{mbrUuid}/affiliations

기업회원 유관기관 목록. 응답 형식 동일.

---

#### POST /api/ext/provision/users/modify_local

개인회원 정보 수정.

**요청 본문**:
```json
{
  "mbrUuid": "uuid-...",
  "name": "홍길동",
  "phoneNumber": "01012341234",
  "email": "new@example.com"
}
```

**응답 200**: `{ "statusCode": 200, "payload": { "data": {} } }`

---

#### POST /api/ext/provision/enterprises/modify_local

기업회원 정보 수정. 요청/응답 형식 유사.

---

### 2-E. 중복 확인

---

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
  "payload": { "data": { "isDuplicate": false } }
}
```

---

### 2-F. 사업자 조회

---

#### POST /api/ext/business/status

사업자 상태 조회 (국세청 OpenAPI).

**요청 본문**: `{ "brno": "123-45-67890" }`

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

**요청 본문**:
```json
{ "brno": "123-45-67890", "rprsvNm": "김대표", "openDt": "20200101" }
```

**응답 200**:
```json
{ "statusCode": 200, "payload": { "data": { "valid": true } } }
```

---

### 2-G. 기업인증 결과

---

#### GET /api/ext/auth-status

기업인증 상태 조회.

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": { "data": { "txId": "tx-uuid", "status": "PENDING" } }
}
```

---

#### GET /api/ext/auth-result/{txId}

기업인증 완료 결과 조회.

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

### 2-H. 프로비저닝

---

#### POST /api/ext/provision/users/check-conversion

개인회원 전환 가능 여부 확인.

**요청 본문**: `{ "ciToken": "eyJ..." }`

**응답 200**:
```json
{
  "statusCode": 200,
  "payload": {
    "data": { "convertible": true, "mbrNo": "MBR-0001", "name": "홍길동" }
  }
}
```

---

#### POST /api/ext/provision/users

개인회원 프로비저닝 (회원전환 Step5).

**요청 본문**:
```json
{
  "ciToken": "eyJ...",
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
  "payload": { "data": { "mbrNo": "MBR-0001", "mbrUuid": "uuid-..." } }
}
```

---

#### POST /api/ext/provision/enterprises

기업회원 프로비저닝 (회원전환 Step5).

**요청 본문**:
```json
{
  "ciToken": "eyJ...",
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
  "payload": { "data": { "entMbrNo": "ENT-0001", "mbrUuid": "uuid-..." } }
}
```

---

### 2-I. 신규 회원가입

---

#### POST /api/ext/register/individual

개인회원 신규 등록. 요청/응답은 `POST /api/ext/provision/users`와 동일.

---

#### POST /api/ext/register/enterprise

기업회원 신규 등록. 요청/응답은 `POST /api/ext/provision/enterprises`와 동일.

---

### 2-J. 유관기관 관리

---

#### POST /api/ext/provision/users/{uuid}/affiliations/add

개인회원 유관기관 추가.

**요청 본문**: `{ "clientId": "client-uuid" }`

**응답 200**: `{ "statusCode": 200 }`

---

#### POST /api/ext/provision/enterprises/{uuid}/affiliations/add

기업회원 유관기관 추가. 동일 형식.

---

#### POST /api/ext/provision/users/{uuid}/affiliations/withdraw

개인회원 유관기관 탈퇴 (ciToken 필요).

**요청 본문**:
```json
{ "clientId": "client-uuid", "ciToken": "eyJ..." }
```

**응답 200**: `{ "statusCode": 200 }`

---

#### POST /api/ext/provision/enterprises/{uuid}/affiliations/withdraw

기업회원 유관기관 탈퇴.

**요청 본문**: `{ "clientId": "client-uuid" }`

**응답 200**: `{ "statusCode": 200 }`

---

## 3. Q-Sign API (:8081)

> Q-Sign은 **ido 내부 통신 전용**입니다. FE에서 직접 접근하지 않습니다.

**Base Path**: `/api/v1/auth`, `/api/v1/internal/session`

| 메서드 | 경로 | 설명 | 호출자 |
|--------|------|------|--------|
| `POST` | `/api/v1/auth/oidc` | OIDC 인증 토큰 처리 | ido |
| `POST` | `/api/v1/auth/broker-input` | 브로커 인증 입력 처리 | ido |
| `GET` | `/api/v1/auth/{authResultId}` | 인증 결과 조회 | ido |
| `POST` | `/api/v1/internal/session/logout` | Keycloak 세션 종료 (SLO) | ido |

**내부 인증**:
- `X-Internal-Sig` HMAC-SHA256 서명 (`IDEM_HUB_INTERNAL_SIG_SECRET`)
- Nonce + Timestamp 포함 (재전송 공격 방지)

---

## 4. 에러 코드 목록

### 4-1. ido NICE/OACX 공통 코드

| 코드 | 설명 |
|------|------|
| `2000` | 성공 |
| `4000` | 파라미터 오류 |
| `4001` | 인증 실패/취소 |
| `4010` | 복호화 실패 |
| `5000` | 서버 내부 오류 / 환경변수 미설정 |
| `5001` | 외부 API (NICE/OACX) 발급 실패 |
| `5002` | 외부 API 결과 조회/복호화 실패 |
| `5003` | 무결성 검증 실패 |
| `5010` | Q-IM 연동 실패 |

### 4-2. ido FE 세션 코드

| 코드 | 설명 |
|------|------|
| `SESSION_VALID` | 세션 유효 |
| `SESSION_NOT_FOUND` | 세션 없음 또는 만료 |
| `INVALID_RETURN_URL` | 화이트리스트 미포함 returnUrl |

### 4-3. Q-IM 동의 토큰 코드

| 코드 | 설명 |
|------|------|
| `INVALID_CONSENT_TOKEN` | 동의 토큰 만료 또는 무효 — FE가 자동 재발급 후 재시도 |

---

## 5. 변경 이력

| 버전 | 날짜 | PR | 변경 내용 |
|------|------|-----|---------|
| v3.0.0 | 2026-05-12 | #77 | **B-1** `GET /api/v1/auth/provision/aes-gcm-key` 신규 엔드포인트 추가 |
| v3.0.0 | 2026-05-12 | #77 | **B-1** `GET /api/v1/auth/provision/temp-password` 응답 키 `"password"` → `"tempPassword"` 수정 |
| v3.0.0 | 2026-05-12 | #77 | **B-5** `/api/ext/**` forward proxy 구조 확립 — FE가 직접 Q-IM 호출 불가 |
| v3.0.0 | 2026-05-12 | #77 | **Q3=B** `POST /api/v1/auth/ci-token` 경로 확정 — FE `extInstance.ci/token` 직접 호출 제거 |
| v2.x | 2026-05-11 | #76 | Sprint 10: SLO, useAuthState, ErrorBoundary, Mypage 회원정보 수정 |
| v1.x | 2026-05-09 | #75 | NICE/OACX BFF, CI 토큰 교환, Q-IM internal API |

---

*최종 수정: 2026-05-12 / PR #77 반영*  
*다음 업데이트 예정: Q-IM /api/ext/** 구현 확인 후 응답 스펙 확정*
