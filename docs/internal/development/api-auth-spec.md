# 본인인증 API 명세서

> **대상**: onepass-fe 프론트엔드 개발팀  
> **버전**: v1.0.0 (S7-T2 구현)  
> **작성일**: 2026-05-10  
> **API 기준 서버**: ido (port 8083)  
> **Base Path**: `/api/v1/auth`

---

## 목차

1. [개요](#1-개요)
2. [공통 사항](#2-공통-사항)
3. [NICE 휴대폰 본인인증 API](#3-nice-휴대폰-본인인증-api)
   - 3.1 [NICE 인증 URL 발급](#31-nice-인증-url-발급)
   - 3.2 [NICE 인증 결과 조회](#32-nice-인증-결과-조회)
   - 3.3 [NICE CI 회원 확인](#33-nice-ci-회원-확인)
4. [OACX 전자서명 간편서명 API](#4-oacx-전자서명-간편서명-api)
   - 4.1 [OACX 접근키/토큰 발급](#41-oacx-접근키토큰-발급)
   - 4.2 [OACX 간편서명 결과 처리](#42-oacx-간편서명-결과-처리)
5. [기업 간편인증 API (향후 구현)](#5-기업-간편인증-api-향후-구현)
6. [FE 연동 플로우 다이어그램](#6-fe-연동-플로우-다이어그램)
7. [공통 결과 코드](#7-공통-결과-코드)
8. [에러 처리 가이드](#8-에러-처리-가이드)
9. [보안 고려사항](#9-보안-고려사항)
10. [개발/테스트 환경 설정](#10-개발테스트-환경-설정)

---

## 1. 개요

본 문서는 onepass-fe React SPA가 본인인증 기능을 구현하기 위해 호출하는 **ido BFF API** 명세를 정의합니다.

### 지원 인증 방식

| 인증 방식 | 설명 | 구현 상태 |
|-----------|------|-----------|
| **NICE 휴대폰 본인인증** | NICE IDO 표준창 팝업 방식 | ✅ 구현 완료 |
| **OACX 전자서명 간편서명** | OACX SDK 팝업 방식 (네이버/토스/PASS 등) | ✅ 구현 완료 |
| **기업 간편인증** | 통합인증 서버 콜백 방식 | ⏳ 향후 구현 (엔드포인트만 준비) |

### 설계 핵심 결정사항

> **Q3=B: CI(연계정보)는 FE에 반환하지 않음**
>
> CI는 주민등록번호 기반 개인식별정보(PII)로, 브라우저에 노출될 경우 XSS 등 보안 취약점으로 개인정보 유출 위험이 있습니다.  
> **CI는 서버 내부에서만 처리**하며, FE 응답 DTO에는 CI 필드가 없습니다.  
> CI 기반 회원 조회는 별도 `/nice/ci-check` API를 통해 처리하세요.

---

## 2. 공통 사항

### 2.1 요청/응답 형식

```
Content-Type: application/json
Accept: application/json
```

### 2.2 인증 (Authentication)

**별도 인증 토큰 불필요**

- 개발 환경: FE webpack dev-server proxy가 `/api` → `http://localhost:8083` 전달 (same-origin)
- 운영 환경: Nginx가 FE와 ido를 동일 origin으로 서빙 (same-origin)
- 브라우저 쿠키(`feSessionId`)는 `withCredentials: true` 설정 시 자동 포함

```typescript
// beApiInstance.ts — 기존 설정 유지 (추가 설정 불필요)
import axios from 'axios';
export const beApiInstance = axios.create({
  baseURL: process.env.BE_API_ENDPOINT,
  headers: { 'X-BE-API-Key': process.env.BE_API_KEY },
  withCredentials: true
});
```

### 2.3 공통 응답 구조

모든 API는 항상 **HTTP 200**을 반환하며, 성공/실패는 `resultCode`로 구분합니다.

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "resultData": { ... }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `resultCode` | string | 결과 코드 (공통 코드표 참조) |
| `resultMsg` | string | 결과 메시지 (사용자 표시용 아님 — 내부 로그/디버깅용) |
| `resultData` | object \| null | 결과 데이터 (성공 시에만 포함, API마다 구조 다름) |

---

## 3. NICE 휴대폰 본인인증 API

### 3.1 NICE 인증 URL 발급

NICE 통합인증 표준창 URL을 발급합니다. FE에서 이 URL로 팝업을 열어 사용자가 휴대폰 본인인증을 진행합니다.

#### Request

```
GET /api/v1/auth/nice/phone/url
```

| 파라미터 | 위치 | 타입 | 필수 | 설명 |
|----------|------|------|------|------|
| `returnUrl` | QueryString | string | ❌ | 인증 완료 후 NICE 표준창이 리다이렉트할 URL. 미입력 시 서버 기본값 사용. |

#### Response (성공)

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "authUrl": "https://nice.checkplus.co.kr/CheckPlusSafeModel/service.cb?token=...",
  "requestNo": "REQ_20240510123456a1b2c3d4e5f6"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `authUrl` | string | NICE 표준창 URL. 이 URL로 팝업을 열어 인증 진행. 단기 유효(발급 후 수분 내 사용 필요). |
| `requestNo` | string | ido가 생성한 고유 요청 번호. **반드시 저장 필요** — 인증 결과 조회 시 전달. 형식: `REQ_yyyyMMddHHmmss + 12자리 UUID` |

#### Response (실패)

```json
{
  "resultCode": "5001",
  "resultMsg": "NICE 인증 URL 발급 실패: ..."
}
```

#### FE 구현 예시

```typescript
// hooks/useNicePhoneAuth.ts

const startNiceAuth = async (returnUrl?: string) => {
  const { data } = await beApiInstance.get<NicePhoneAuthUrlResponse>(
    '/api/v1/auth/nice/phone/url',
    { params: returnUrl ? { returnUrl } : undefined }
  );
  
  if (data.resultCode !== '2000') {
    throw new Error(`NICE URL 발급 실패: ${data.resultMsg}`);
  }
  
  // requestNo 반드시 저장 (인증 결과 조회 시 필요)
  const { authUrl, requestNo } = data;
  sessionStorage.setItem('niceRequestNo', requestNo);
  
  // 팝업 오픈 (크기/위치는 UX 정책에 따라 조정)
  window.open(authUrl, 'nicePhoneAuth', 'width=500,height=600,scrollbars=yes');
};
```

---

### 3.2 NICE 인증 결과 조회

NICE 표준창에서 인증 완료 후 postMessage로 받은 `web_transaction_id`와 저장해둔 `request_no`로 인증 결과를 조회하고 복호화합니다.

#### Request

```
POST /api/v1/auth/nice/phone/result
Content-Type: application/json
```

```json
{
  "web_transaction_id": "WEB_20240510123456_abc123",
  "request_no": "REQ_20240510123456a1b2c3d4e5f6"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `web_transaction_id` | string | ✅ | NICE 표준창이 postMessage로 전달하는 웹 트랜잭션 ID |
| `request_no` | string | ✅ | 3.1 URL 발급 응답의 `requestNo` 값 (저장해두었다가 전달) |

#### Response (성공)

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "resultData": {
    "name": "홍길동",
    "birthdate": "19900101",
    "gender": "1",
    "nationalInfo": "0",
    "di": "ABCDEF0123456789ABCDEF0123456789ABCDEF01234567890123456789012345",
    "mobileCo": "SKT",
    "mobileNo": "01012345678"
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `resultData.name` | string | 이름 |
| `resultData.birthdate` | string | 생년월일 (YYYYMMDD, 예: "19900101") |
| `resultData.gender` | string | 성별 (`"0"`: 여성, `"1"`: 남성) |
| `resultData.nationalInfo` | string | 내/외국인 (`"0"`: 내국인, `"1"`: 외국인) |
| `resultData.di` | string | DI(중복가입확인정보) — 사이트별 식별자 (64자) |
| `resultData.mobileCo` | string | 통신사 (`SKT`, `KT`, `LGU+`, `SKT알뜰폰`, `KT알뜰폰`, `LGU+알뜰폰`) |
| `resultData.mobileNo` | string | 휴대폰 번호 (하이픈 없음, 예: "01012345678") |

> ⚠️ **CI 미포함 안내**: 응답에 `ci` 필드가 없습니다. 이는 의도적인 설계 결정(Q3=B)입니다.  
> CI(연계정보)는 개인식별정보(PII)로 FE 브라우저에 노출하지 않으며, 회원 조회/등록은 **3.3 CI 확인 API**를 통해 서버-서버 간 처리합니다.

#### Response (실패)

| resultCode | 상황 |
|-----------|------|
| `4000` | request_no 누락, 세션 만료(10분 초과), 잘못된 request_no |
| `5002` | NICE 결과 조회 실패 |
| `5003` | 데이터 무결성 검증 실패 (위변조 의심 — 재시도 금지) |
| `5000` | 내부 오류 |

#### FE 구현 예시

```typescript
// hooks/useNicePhoneAuth.ts

const handleNiceMessage = async (event: MessageEvent) => {
  // NICE 팝업 origin 검증 필수
  if (!event.origin.includes('nice.checkplus.co.kr')) return;
  
  const { web_transaction_id, request_no } = event.data;
  const storedRequestNo = sessionStorage.getItem('niceRequestNo');
  
  // request_no 일치 확인 (CSRF 방어)
  if (request_no !== storedRequestNo) {
    console.error('request_no 불일치 — 보안 위협 가능성');
    return;
  }
  
  const { data } = await beApiInstance.post<NicePhoneAuthResultResponse>(
    '/api/v1/auth/nice/phone/result',
    { web_transaction_id, request_no }
  );
  
  if (data.resultCode !== '2000') {
    // 사용자 친화적 에러 메시지 표시
    setError(getErrorMessage(data.resultCode));
    return;
  }
  
  const { name, birthdate, gender, di, mobileCo, mobileNo } = data.resultData!;
  // CI는 응답에 없음 — 회원 가입/로그인 처리는 ci-check API 사용
  
  sessionStorage.removeItem('niceRequestNo');
  
  // 다음 단계 처리 (회원 가입, 로그인 등)
  await processNiceAuthResult({ name, birthdate, gender, di, mobileCo, mobileNo });
};

window.addEventListener('message', handleNiceMessage);
```

---

### 3.3 NICE CI 회원 확인

NICE 인증 결과에서 획득한 CI(연계정보)로 기존 회원 조회 또는 신규 등록을 처리합니다.

> ⚠️ **현재 구현 상태**: IM API 미연동으로 파라미터 검증 후 성공 반환만 구현됨.  
> **S7-T6 작업에서 실제 회원 조회/등록 로직이 구현될 예정**입니다.

#### Request

```
POST /api/v1/auth/nice/ci-check
Content-Type: application/json
```

**개인회원(A101) 예시:**
```json
{
  "ci": "ABCDEF0123456789...(88자 CI)",
  "mbrDvsnCd": "A101",
  "indvlMbrNm": "홍길동",
  "indvlMbrId": "hong123"
}
```

**기업회원(A102) 예시:**
```json
{
  "ci": "ABCDEF0123456789...(88자 CI)",
  "mbrDvsnCd": "A102",
  "cmpMbrId": "company-001",
  "bizno": "1234567890"
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `ci` | string | ✅ | NICE 인증 결과 CI (88자) |
| `mbrDvsnCd` | string | ✅ | 회원구분코드 (`A101`: 개인, `A102`: 기업) |
| `cmpMbrId` | string | A102 선택 | 기업회원 아이디 |
| `bizno` | string | **A102 필수** | 사업자등록번호 (10자리, 하이픈 없음) |
| `indvlMbrNm` | string | A101 선택 | 개인회원 이름 |
| `indvlMbrId` | string | A101 선택 | 개인회원 아이디 |

#### Response (성공)

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "result": true,
  "indvlMbrId": "hong123"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `result` | boolean | `true`: 처리 성공 (기존 회원 또는 신규 등록) |
| `indvlMbrId` | string | 기존 개인회원 아이디 (조회된 경우만 포함, TODO: S7-T6) |
| `cmpMbrId` | string | 기존 기업회원 아이디 (조회된 경우만 포함, TODO: S7-T6) |

#### Response (실패)

| resultCode | 상황 |
|-----------|------|
| `4000` | ci 누락, mbrDvsnCd 오류, bizno 누락(A102) |

---

## 4. OACX 전자서명 간편서명 API

### OACX 인증 전체 플로우

```
FE                         ido                          OACX 서버
|                           |                               |
| POST /oacx/access-info    |                               |
|-------------------------->|                               |
|                           | OacxUtil.getAccessInfo()      |
|                           |------------------------------>|
|                           | <- {accKey, accToken}         |
| <- {fn, accKey, accToken} |                               |
|                           |                               |
| OACXsdk.init(...)         |                               |
| OACXsdk.open()            |                               |
| [사용자 간편서명]           |                               |
|                           |                               |
| SDK 콜백 (fn, status, res)|                               |
|                           |                               |
| POST /oacx/easysign       |                               |
|-------------------------->|                               |
|                           | OacxUtil.jwtDecryptResult()   |
|                           |------------------------------>|
|                           | <- 복호화된 사용자 정보          |
| <- {name, birthday, phone}|                               |
| (CI 미포함 — Q3=B)         |                               |
```

---

### 4.1 OACX 접근키/토큰 발급

OACX JS SDK 초기화에 필요한 `accKey`와 `accToken`을 발급합니다.

#### Request

```
POST /api/v1/auth/oacx/access-info
Content-Type: application/json
```

**Request Body** (plain JSON string):
```json
"simpleAuth"
```

> ⚠️ **Content-Type 주의**: body는 JSON string입니다. axios에서 객체를 전달하면 안 됩니다.

```typescript
// 올바른 호출 방법
await beApiInstance.post('/api/v1/auth/oacx/access-info', '"simpleAuth"');
// 또는
await beApiInstance.post('/api/v1/auth/oacx/access-info', JSON.stringify('simpleAuth'));
```

#### Response (성공)

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "fn": "simpleAuth",
  "accKey": "abc123xyz...",
  "accToken": "eyJhbGci..."
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `fn` | string | OACX 기능 코드 (요청한 값 그대로 반환) |
| `accKey` | string | OACX SDK 초기화용 접근키 |
| `accToken` | string | OACX SDK 초기화용 접근토큰 (단기 유효 — 발급 즉시 사용) |

#### Response (실패)

| resultCode | 상황 |
|-----------|------|
| `5001` | OACX SDK 오류 또는 provider key 미설정 |

#### FE 구현 예시

```typescript
// hooks/usePersonalEasyAuth.ts

const startOacxAuth = async () => {
  const { data } = await beApiInstance.post<OacxAccessInfoResponse>(
    '/api/v1/auth/oacx/access-info',
    JSON.stringify('simpleAuth')
  );
  
  if (data.resultCode !== '2000') {
    throw new Error(`OACX 접근정보 발급 실패: ${data.resultMsg}`);
  }
  
  const { fn, accKey, accToken } = data;
  
  // OACX JS SDK 초기화 및 실행
  // (OACXsdk는 OACX 운영사 제공 JS SDK — 별도 import 필요)
  OACXsdk.init({ fn, accKey, accToken });
  OACXsdk.open(async (callbackData: OacxCallbackData) => {
    await handleOacxCallback(callbackData);
  });
};
```

---

### 4.2 OACX 간편서명 결과 처리

OACX JS SDK 간편서명 완료 콜백 데이터를 수신하여 JWT를 복호화하고 사용자 정보를 반환합니다.

#### Request

```
POST /api/v1/auth/oacx/easysign
Content-Type: application/json
```

```json
{
  "fn": "authComplete",
  "status": "success",
  "res": {
    "resultCode": "200",
    "encData": "eyJhbGci..."
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `fn` | string | ✅ | OACX SDK 콜백 기능 코드 (반드시 `"authComplete"`) |
| `status` | string | ✅ | 처리 상태 (`"success"`: 성공) |
| `res` | object | ✅ | OACX SDK 콜백 결과 객체 (SDK가 전달하는 원본 그대로 전달) |
| `res.resultCode` | string | ✅ | OACX 내부 결과 코드 (`"200"`: 성공) |
| `res.encData` | string | ✅ | JWT 암호화 데이터 (서버에서 복호화) |

> 💡 **FE 구현 팁**: OACX SDK 콜백 인자(`callbackData`)를 수정 없이 그대로 body로 전달하면 됩니다.

#### Response (성공)

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "name": "홍길동",
  "birthday": "19900101",
  "phone": "01012345678"
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `name` | string | 이름 (OACX provider별 키 이름 차이를 서버에서 통일 처리) |
| `birthday` | string | 생년월일 (YYYYMMDD, provider에 따라 null 가능) |
| `phone` | string | 휴대폰 번호 (하이픈 없음) |

> ⚠️ **CI 미포함 안내**: 응답에 `ci` 필드가 없습니다. 이는 의도적인 설계 결정(Q3=B)입니다.

#### Response (실패)

| resultCode | 상황 |
|-----------|------|
| `4000` | `fn`이 `"authComplete"`가 아님 |
| `4001` | OACX `res.resultCode`가 `"200"`이 아님 (인증 실패/취소) |
| `5002` | JWT 복호화 실패 |

#### OACX Provider별 지원 정보

| Provider | name 키 | phone 키 | CI 제공 |
|----------|---------|---------|---------|
| 네이버 | `name` | `phone` | ✅ |
| 토스 | `name` | `phone` | ✅ |
| 드림 | `name` | `phone` | ✅ |
| 뱅크샐러드 | `name` | `phone` | ✅ |
| PASS (SKT/KT/LGU+) | `userNm` | `phoneNo` | ✅ |

> Provider별 키 이름 차이는 서버에서 자동으로 통일 처리합니다. FE는 항상 `name`, `phone` 필드를 사용하면 됩니다.

#### FE 구현 예시

```typescript
// hooks/usePersonalEasyAuth.ts

const handleOacxCallback = async (callbackData: OacxCallbackData) => {
  // SDK 콜백 데이터를 그대로 전달
  const { data } = await beApiInstance.post<OacxEasysignResponse>(
    '/api/v1/auth/oacx/easysign',
    callbackData  // { fn, status, res } — 수정 없이 그대로
  );
  
  if (data.resultCode !== '2000') {
    if (data.resultCode === '4001') {
      // 사용자가 인증을 취소함
      console.info('사용자가 간편서명을 취소했습니다');
    } else {
      setError(`간편서명 처리 실패: ${data.resultCode}`);
    }
    return;
  }
  
  const { name, birthday, phone } = data;
  // CI는 응답에 없음 (Q3=B 보안 정책)
  
  // 다음 단계 처리
  await processEasyAuthResult({ name, birthday, phone });
};
```

---

## 5. 기업 간편인증 API (향후 구현)

> ⚠️ **현재 FE에서 호출하지 않음** — 향후 FE 기업인증 Step3 구현 시 사용 예정

### 5.1 기업 간편인증 콜백 수신

#### Request

```
POST /api/v1/auth/callback
Content-Type: application/json
```

```json
{
  "siteInfo": { "siteId": "site-001" },
  "txId": "TXN_20240510_ABC123",
  "tokenId": "TOKEN_ABC123",
  "userToken": "user-token-value",
  "hubToken": "hub-token-value"
}
```

#### Response (성공)

```json
{
  "resultCode": "2000",
  "resultMsg": "성공",
  "resultData": {
    "name": "홍길동",
    "businessNumber": "1234567890",
    "birth": "19700101",
    "phone": "01012345678",
    "bizOpendt": "20000101"
  }
}
```

---

## 6. FE 연동 플로우 다이어그램

### NICE 휴대폰 본인인증 플로우

```
FE (React SPA)                 ido (8083)              NICE 서버
│                               │                          │
│ 1. GET /nice/phone/url        │                          │
│──────────────────────────────>│                          │
│                               │ 2. POST /auth/token      │
│                               │─────────────────────────>│
│                               │ <─ {accessToken, ticket} │
│                               │ 3. POST /auth/url        │
│                               │─────────────────────────>│
│                               │ <─ {authUrl, txnId}      │
│                               │ Redis: save(reqNo, txnId)│
│ <─ {authUrl, requestNo}       │                          │
│                               │                          │
│ 4. window.open(authUrl)       │                          │
│ [팝업: 사용자 휴대폰 인증]      │                          │
│                               │                          │
│ 5. postMessage(web_txn_id)    │                          │
│                               │                          │
│ 6. POST /nice/phone/result    │                          │
│──────────────────────────────>│                          │
│   { web_transaction_id,       │ 7. Redis: find(reqNo)    │
│     request_no }              │ 8. POST /auth/result     │
│                               │─────────────────────────>│
│                               │ <─ {encData, integrity}  │
│                               │ 9. PBKDF2 키파생         │
│                               │ 10. HMAC 무결성 검증     │
│                               │ 11. AES-GCM 복호화       │
│ <─ { resultData:              │                          │
│      name, birthdate,         │                          │
│      di, mobileCo, mobileNo } │                          │
│   (CI 미포함 — Q3=B)          │                          │
│                               │                          │
│ 12. POST /nice/ci-check       │                          │
│   (CI는 FE에서 직접 보관하지   │                          │
│    않고, 서버가 내부 처리)      │                          │
```

### OACX 간편서명 플로우

```
FE (React SPA)                 ido (8083)              OACX 서버
│                               │                          │
│ 1. POST /oacx/access-info     │                          │
│──────────────────────────────>│                          │
│                               │ 2. OacxUtil.getAccessInfo│
│                               │─────────────────────────>│
│                               │ <─ {accKey, accToken}    │
│ <─ {fn, accKey, accToken}     │                          │
│                               │                          │
│ 3. OACXsdk.init(fn,accKey,accToken)                       │
│ 4. OACXsdk.open()                                         │
│ [팝업: 사용자 간편서명]                                    │
│                               │                          │
│ 5. SDK 콜백 (fn,status,res)   │                          │
│                               │                          │
│ 6. POST /oacx/easysign        │                          │
│──────────────────────────────>│                          │
│                               │ 7. OacxUtil.jwtDecrypt   │
│                               │─────────────────────────>│
│                               │ <─ {name, phone, ci, ...}│
│                               │ 8. CI 내부 처리 (미반환)  │
│ <─ {name, birthday, phone}    │                          │
│   (CI 미포함 — Q3=B)          │                          │
```

---

## 7. 공통 결과 코드

### 성공 코드

| 코드 | 의미 |
|------|------|
| `2000` | 성공 |

### 클라이언트 오류 코드 (4xxx)

| 코드 | 의미 | 조치 |
|------|------|------|
| `4000` | 요청 파라미터 오류 (누락, 잘못된 값) | `resultMsg` 확인 후 요청 수정 |
| `4001` | 인증 실패 또는 사용자 취소 | 사용자에게 재시도 안내 |

### 서버 오류 코드 (5xxx)

| 코드 | 의미 | 조치 |
|------|------|------|
| `5000` | 내부 처리 오류 | 잠시 후 재시도 또는 백엔드 팀 문의 |
| `5001` | 외부 서버(NICE/OACX) 응답 없음 | 잠시 후 재시도 |
| `5002` | 외부 서버 결과 조회 실패 | 처음부터 재시도 |
| `5003` | 데이터 무결성 검증 실패 | **재시도 금지** — 보안 사고 가능성, 운영팀 즉시 보고 |

---

## 8. 에러 처리 가이드

### FE 권장 에러 처리 패턴

```typescript
// utils/authErrorHandler.ts

export const getAuthErrorMessage = (resultCode: string): string => {
  switch (resultCode) {
    case '4000': return '입력 정보를 확인해주세요.';
    case '4001': return '인증이 취소되었습니다. 다시 시도해주세요.';
    case '5000': return '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.';
    case '5001': return '인증 서버 연결에 실패했습니다. 잠시 후 다시 시도해주세요.';
    case '5002': return '인증 결과 조회에 실패했습니다. 처음부터 다시 시도해주세요.';
    case '5003': return '보안 오류가 발생했습니다. 고객센터에 문의해주세요.';
    default: return '알 수 없는 오류가 발생했습니다.';
  }
};

// NICE 인증 결과 처리 예시
const processNiceResult = async (request: NicePhoneAuthResultRequest) => {
  try {
    const { data } = await beApiInstance.post<NicePhoneAuthResultResponse>(
      '/api/v1/auth/nice/phone/result',
      request
    );
    
    if (data.resultCode !== '2000') {
      // 5003은 재시도 안내 없이 고객센터 안내
      if (data.resultCode === '5003') {
        alert('보안 오류가 발생했습니다. 고객센터(1588-XXXX)에 문의해주세요.');
        return;
      }
      throw new Error(getAuthErrorMessage(data.resultCode));
    }
    
    return data.resultData;
  } catch (error) {
    // HTTP 레벨 오류 처리 (네트워크 오류 등)
    if (axios.isAxiosError(error) && !error.response) {
      throw new Error('네트워크 연결을 확인해주세요.');
    }
    throw error;
  }
};
```

---

## 9. 보안 고려사항

### 9.1 CI(연계정보) 처리 원칙

- **CI는 FE에 반환하지 않습니다** (Q3=B 보안 결정)
- CI는 주민등록번호를 해시한 88자 개인식별정보로, 브라우저 메모리/localStorage에 저장 금지
- 회원 조회/등록이 필요한 경우 `/nice/ci-check` API를 통해 서버에서 처리

### 9.2 NICE 팝업 메시지 검증

```typescript
// NICE 팝업 postMessage 검증 필수
window.addEventListener('message', (event: MessageEvent) => {
  // 1. origin 검증 (NICE 공식 도메인만 허용)
  const ALLOWED_NICE_ORIGINS = [
    'https://nice.checkplus.co.kr',
    'https://nice.checkplus.co.kr:443'
  ];
  if (!ALLOWED_NICE_ORIGINS.some(origin => event.origin.startsWith(origin))) {
    console.warn('허용되지 않은 origin의 메시지:', event.origin);
    return;
  }
  
  // 2. request_no 검증 (세션에 저장한 값과 비교)
  const storedRequestNo = sessionStorage.getItem('niceRequestNo');
  if (event.data.request_no !== storedRequestNo) {
    console.error('request_no 불일치 — CSRF 또는 피싱 가능성');
    return;
  }
  
  // 3. 처리 후 세션 데이터 삭제
  sessionStorage.removeItem('niceRequestNo');
});
```

### 9.3 민감 정보 로깅 금지

```typescript
// ❌ 절대 금지: CI, 생년월일, 주민번호 등 PII 로깅
console.log('인증 결과:', JSON.stringify(resultData)); // 금지

// ✅ 권장: 필요한 정보만 최소 로깅
console.log('인증 성공: name=', resultData.name, 'mobileCo=', resultData.mobileCo);
```

---

## 10. 개발/테스트 환경 설정

### 10.1 로컬 개발 환경

```bash
# onepass-fe .env 설정 확인
cat frontend/.env
# BE_API_TARGET=http://localhost:8083  → ido BFF

# webpack proxy 확인 — /api 경로는 ido(8083)로 전달됨
# frontend/webpack.config.js 참조
```

### 10.2 NICE 테스트 계정 설정 (백엔드 팀 문의)

```bash
# ido application-local.yml 또는 환경변수 설정
export NICE_CLIENT_ID=<NICE 테스트 클라이언트 ID>
export NICE_CLIENT_SECRET=<NICE 테스트 클라이언트 시크릿>
export NICE_RETURN_URL=http://localhost:3000/otp/auth-result
```

### 10.3 OACX 테스트 설정 (백엔드 팀 문의)

```bash
export OACX_PROVIDER_KEY_PATH=/path/to/oacx-provider-key.json
export OACX_DEBUG_MODE=true  # 로컬 개발환경에서만 true
```

### 10.4 TypeScript 타입 정의 (FE 팀 참고)

```typescript
// types/api/auth/index.ts

// NICE 인증 URL 발급
export interface NicePhoneAuthUrlRequest {
  returnUrl?: string;
}
export interface NicePhoneAuthUrlResponse {
  resultCode: string;
  resultMsg: string;
  authUrl?: string;
  requestNo?: string;
}

// NICE 인증 결과 조회
export interface NicePhoneAuthResultRequest {
  web_transaction_id: string;
  request_no: string;
}
export interface NicePhoneAuthResultData {
  name: string;
  birthdate: string;
  gender: '0' | '1';         // 0: 여성, 1: 남성
  nationalInfo: '0' | '1';  // 0: 내국인, 1: 외국인
  di: string;
  mobileCo: string;
  mobileNo: string;
  // ci 필드 없음 (Q3=B 보안 정책)
}
export interface NicePhoneAuthResultResponse {
  resultCode: string;
  resultMsg: string;
  resultData?: NicePhoneAuthResultData;
}

// NICE CI 확인
export interface CiCheckRequest {
  ci: string;
  mbrDvsnCd: 'A101' | 'A102';
  cmpMbrId?: string;
  bizno?: string;
  indvlMbrNm?: string;
  indvlMbrId?: string;
}
export interface CiCheckResponse {
  resultCode: string;
  resultMsg: string;
  result?: boolean;
  indvlMbrId?: string;
  cmpMbrId?: string;
}

// OACX 접근정보 발급
export interface OacxAccessInfoResponse {
  resultCode: string;
  resultMsg: string;
  fn?: string;
  accKey?: string;
  accToken?: string;
}

// OACX 간편서명 결과
export interface OacxEasysignRequest {
  fn: string;
  status: string;
  res: {
    resultCode: string;
    encData: string;
    [key: string]: unknown;
  };
}
export interface OacxEasysignResponse {
  resultCode: string;
  resultMsg: string;
  name?: string;
  birthday?: string;
  phone?: string;
  // ci 필드 없음 (Q3=B 보안 정책)
}

// 기업인증 콜백 (향후 구현)
export interface AuthCallbackRequest {
  siteInfo?: { siteId: string };
  txId?: string;
  tokenId?: string;
  userToken?: string;
  hubToken?: string;
}
export interface AuthCallbackResponse {
  resultCode: string;
  resultMsg: string;
  resultData?: {
    name?: string;
    businessNumber?: string;
    birth?: string;
    phone?: string;
    bizOpendt?: string;
  };
}
```

---

## 변경 이력

| 버전 | 날짜 | 작성자 | 변경 내용 |
|------|------|--------|-----------|
| v1.0.0 | 2026-05-10 | genspark_ai_developer | S7-T2 최초 작성 — NICE/OACX API 명세 |

---

> **문의**: 본 API 관련 문의사항은 백엔드 개발팀에 문의하세요.  
> **Swagger UI**: `http://localhost:8083/swagger-ui.html` (로컬 개발 시)
