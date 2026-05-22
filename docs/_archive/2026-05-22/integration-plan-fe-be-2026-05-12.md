# 프론트엔드(onepass-develop) ↔ 백엔드 통합 플랜

> **작성일**: 2026-05-12  
> **대상 FE**: `onepass-develop.zip` (React 18 / TypeScript)  
> **대상 BE**: `integration-sso` 모노레포 (`ido` + `q-im` + `q-sign`)  
> **브랜치**: `genspark_ai_developer` / PR #67  

---

## 1. 아키텍처 전체 구조 (확정)

```
┌──────────────────────────────────────────────────────────────────┐
│  Browser (onepass FE)                                            │
│                                                                  │
│  ┌──────────────┐  ┌──────────────────────────────────────────┐ │
│  │  beInstance  │  │          extInstance                     │ │
│  │  (X-BE-API-  │  │  (X-API-Key: EXT_API_KEY)                │ │
│  │   Key)       │  │  → EXT_API_ENDPOINT                      │ │
│  └──────┬───────┘  └──────────────────┬──────────────────────┘ │
└─────────┼──────────────────────────────┼────────────────────────┘
          │ /api/v1/auth/**              │ /api/ext/**
          ▼                              ▼
┌─────────────────────┐    ┌─────────────────────────────────────┐
│  ido (Spring Boot)  │    │   Q-IM (외부 서비스 / 내부망)       │
│  port: 9292 / 8083  │    │   /api/ext/** 구현                  │
│                     │    │                                     │
│  /api/v1/auth/**    │    │  /api/ext/clients                  │
│  /api/v1/internal/  │◄───│  /api/ext/provision/**             │
│   ↑ (Q-IM 내부호출) │    │  /api/ext/consent/**               │
│                     │    │  /api/ext/terms/**                  │
│  q-sign (인증)       │    │  /api/ext/ci/token                 │
│  port: 9090         │    │  /api/ext/members/**               │
└─────────────────────┘    │  /api/ext/enterprises/**           │
                           │  /api/ext/register/**              │
                           │  /api/ext/business/**              │
                           │  /api/ext/check-duplicate          │
                           └─────────────────────────────────────┘
```

**핵심 발견**: `extInstance`가 호출하는 `/api/ext/**`는 **Q-IM 서비스**가 직접 처리한다.  
webpack proxy 기준: `/api/ext` → `EXT_API_ENDPOINT` (기본값: `https://onepass-dev.smes.go.kr/im`)  
`beInstance`가 호출하는 `/api/**`는 → `ido` 백엔드 (기본값: `http://localhost:9292`)

---

## 2. FE ↔ BE API 완전 매핑표

### 2-A. extInstance → Q-IM API (`/api/ext/**`)

| FE 파일 | HTTP 메서드 + 경로 | Q-IM 구현 여부 | 설명 |
|---------|-------------------|---------------|------|
| `ext/clients.ts` | `GET /api/ext/clients` | ❓ 미확인 | 유관기관 클라이언트 목록 조회 |
| `ext/checkConversion.ts` (ext/) | `POST /api/ext/provision/users/check-conversion` | ❓ 미확인 | 전환 가능 여부 조회 (ext 경로) |
| `ext/checkDuplicate.ts` | `GET /api/ext/check-duplicate?type=ENT\|IND&value=...` | ❓ 미확인 | ID 중복 확인 (기업/개인) |
| `ext/termsBundle.ts` | `GET /api/ext/terms/bundle?realm=qim&client=sp-smeg&lang=ko` | ❓ 미확인 | 약관 번들 조회 |
| `ext/consent.ts` | `POST /api/ext/consent/token` | ❓ 미확인 | 동의 토큰 발급 |
| `ext/consent.ts` | `POST /api/ext/consent` | ❓ 미확인 | 동의 제출 |
| `ext/authResult.ts` | `GET /api/ext/auth-status` | ❓ 미확인 | 기업인증 상태 조회 |
| `ext/authResult.ts` | `GET /api/ext/auth-result/{txId}` | ❓ 미확인 | 기업인증 결과 조회 |
| `ext/members.ts` | `GET /api/ext/members/{mbrNo}` | ❓ 미확인 | 개인회원 조회 |
| `ext/members.ts` | `GET /api/ext/members/{mbrUuid}/affiliations` | ❓ 미확인 | 개인회원 유관서비스 목록 |
| `ext/members.ts` | `GET /api/ext/enterprises/{entMbrNo}` | ❓ 미확인 | 기업회원 조회 |
| `ext/members.ts` | `GET /api/ext/enterprises/{mbrUuid}/affiliations` | ❓ 미확인 | 기업회원 유관서비스 목록 |
| `ext/members.ts` | `POST /api/ext/provision/users/modify_local` | ❓ 미확인 | 개인회원 정보 수정 |
| `ext/members.ts` | `POST /api/ext/provision/enterprises/modify_local` | ❓ 미확인 | 기업회원 정보 수정 |
| `ext/businessStatus.ts` | `POST /api/ext/business/status` | ❓ 미확인 | 사업자 상태 조회 (국세청) |
| `ext/businessValidate.ts` | `POST /api/ext/business/validate` | ❓ 미확인 | 사업자 진위확인 |
| `provision/ciToken.ts` | `POST /api/ext/ci/token` | ❓ 미확인 | CI → ciToken(JWT) 발급 |
| `provision/checkConversion.ts` | `POST /api/ext/provision/users/check-conversion` | ❓ 미확인 | 전환 가능 여부 (provision 경로) |
| `provision/users.ts` | `POST /api/ext/provision/users` | ❓ 미확인 | 개인회원 프로비저닝 |
| `provision/enterprises.ts` | `POST /api/ext/provision/enterprises` | ❓ 미확인 | 기업회원 프로비저닝 |
| `provision/registerIndividual.ts` | `POST /api/ext/register/individual` | ❓ 미확인 | 개인회원 신규 등록 |
| `provision/registerEnterprise.ts` | `POST /api/ext/register/enterprise` | ❓ 미확인 | 기업회원 신규 등록 |
| `provision/affiliations.ts` | `POST /api/ext/provision/enterprises/{uuid}/affiliations/add` | ❓ 미확인 | 기업 유관기관 추가 |
| `provision/affiliations.ts` | `POST /api/ext/provision/users/{uuid}/affiliations/add` | ❓ 미확인 | 개인 유관기관 추가 |
| `provision/affiliations.ts` | `POST /api/ext/provision/enterprises/{uuid}/affiliations/withdraw` | ❓ 미확인 | 기업 유관기관 탈퇴 |
| `provision/affiliations.ts` | `POST /api/ext/provision/users/{uuid}/affiliations/withdraw` | ❓ 미확인 | 개인 유관기관 탈퇴 (ciToken 필요) |

> **⚠️ 중요**: 위 `/api/ext/**` API 전체가 Q-IM 서비스에서 구현되어야 한다.  
> 현재 이 리포지토리(`integration-sso`)의 **ido**, **q-im** 코드베이스에는 `/api/ext/**` 컨트롤러가 **존재하지 않음**.  
> Q-IM 팀이 별도 관리하는 외부 Q-IM 서버(`onepass-dev.smes.go.kr/im`)에 이미 구현되어 있을 가능성이 높음.

---

### 2-B. beInstance → ido BFF API (`/api/v1/auth/**`)

| FE 파일/Hook | HTTP 메서드 + 경로 | ido 구현 여부 | 설명 |
|-------------|-------------------|--------------|------|
| `usePersonalEasyAuth.ts` | `POST /api/v1/auth/oacx/access-info` | ✅ `AuthController` | OACX 간편인증 접근키/토큰 발급 |
| `usePersonalEasyAuth.ts` | `POST /api/v1/auth/oacx/easysign` | ✅ `AuthController` | OACX 간편서명 결과 처리 |
| `useNicePhoneAuth.ts` | `GET /api/v1/auth/nice/phone/url` | ✅ `AuthController` | NICE 휴대폰 인증 URL 발급 |
| `useNicePhoneAuth.ts` | `POST /api/v1/auth/nice/phone/result` | ✅ `AuthController` | NICE 휴대폰 인증 결과 조회 |
| `nice/ciCheck.ts` | `POST /api/v1/auth/nice/ci-check` | ✅ `AuthController` | CI 기반 회원 존재 확인 |
| `OacxTest/PhoneAuthTab.tsx` | `GET /api/v1/auth/nice/phone/url` | ✅ | 테스트 탭 사용 |
| `OacxTest/PersonalAuthTab.tsx` | `POST /api/v1/auth/oacx/easysign` | ✅ | 테스트 탭 사용 |

> **✅ ido BFF 쪽은 완전 구현 확인됨** (`AuthController.java` 주석 기준 6개 엔드포인트 모두 존재)

---

### 2-C. 내부 API: ido → Q-IM (서버 간 호출)

| 호출 위치 | HTTP 메서드 + 경로 | Q-IM 구현 여부 | 설명 |
|----------|-------------------|--------------|------|
| `QimClient.java` | `POST /api/v1/internal/users` | ✅ `UserController` | 사용자 등록 Upsert |
| `QimClient.java` | `GET /api/v1/internal/users/{qimUserId}` | ✅ `UserController` | 사용자 조회 |
| `QimClient.java` | `POST /api/v1/internal/member/lookup-by-ci` | ✅ `MemberLookupController` | CI로 회원 조회 |
| `QimClient.java` | `GET /api/v1/internal/member/lookup-by-hash` | ✅ `MemberLookupController` | identifierHash로 회원 조회 |

---

## 3. 페이지별 플로우 분석

### 3-A. 회원전환 플로우 (ConversionSteps) — 개인회원 기준

```
Step1: 회원유형 선택
  - URL 파라미터: redirect_uri, mbrId, return_client, userType
  - ConversionContext에 저장

Step2: 약관동의
  - API: POST /api/ext/consent/token  → consentToken 발급
  - API: GET  /api/ext/terms/bundle   → 약관 목록 (DOMPurify 렌더링)
  - API: POST /api/ext/consent        → 동의 제출 → consentEventId 저장
  - 토큰 만료 시 자동 재발급 후 1회 재시도 구현됨

Step3: 본인인증
  [개인]
  - 간편인증: usePersonalEasyAuth → beInstance /api/v1/auth/oacx/* 
    → CI 수신 후 encryptCi(AES-256-GCM) → exchangeCiToken(/api/ext/ci/token) → ciToken 저장
  - 휴대폰: useNicePhoneAuth → beInstance /api/v1/auth/nice/*
    → CI 수신 후 동일 암호화 → ciToken 저장
  - 공동인증서/Any-ID: devNoticeModal 표시 (미구현 알림)
  [기업]
  - 사업자등록번호 입력
  - 간편인증: useEzAuth (드림시큐리티 EzAuth SDK)
  - 공동인증서: devNoticeModal 표시 (미구현)

Step4: 가입현황 확인 (유관기관 선택)
  ⚠️ 하드코딩된 MOCK 데이터 사용 중 — 실 API 연동 필요
  - ConversionStep4: SYSTEMS 배열 = 하드코딩 7개 시스템
  - MOCK_MEMBER, MOCK_BUSINESS 상수 직접 사용

Step5(=실제 Step5): 계정정보 입력
  [개인] 
  - API: POST /api/ext/provision/users → 개인회원 프로비저닝
  - AccountForm: loginId, password, email
  - MemberInfoForm: name, phone
  - NotificationSettings: sms/kakao/email
  [기업]
  - API: POST /api/ext/provision/enterprises → 기업회원 프로비저닝
  - 사업자 진위확인: POST /api/ext/business/validate
  - 사업자 상태조회: POST /api/ext/business/status
  - 중복확인: GET /api/ext/check-duplicate

Step6(=실제 Step6): 유관기관 서비스 연결
  [개인] POST /api/ext/provision/users/check-conversion → 전환 가능 서비스 목록
  [기업] GET  /api/ext/clients → 전체 클라이언트 목록

Step8: 전환 완료 (성공 화면)
  - data.redirectUri → 원래 시스템으로 복귀
```

### 3-B. 신규 회원가입 플로우 (RegisterSteps)

ConversionSteps와 동일 구조, 차이점:
- URL 파라미터: `type=member|business`, `return_client`, `return_uri`
- RegisterContext 사용 (ConversionContext와 동일 필드 구조)
- Step3 ciToken 발급 flowContext: `'PROVISION_USER'` (동일)
- 최종 API:
  - 개인: `POST /api/ext/register/individual`
  - 기업: `POST /api/ext/register/enterprise`

### 3-C. 마이페이지 (Mypage)

```
Information (내정보조회)
  - useInfoStore: localStorage + context 혼합
  - API: GET /api/ext/members/{mbrNo}       (개인)
  - API: GET /api/ext/enterprises/{entMbrNo} (기업)

InformationStep2/3 (내정보 수정)
  - API: POST /api/ext/provision/users/modify_local       (개인)
  - API: POST /api/ext/provision/enterprises/modify_local (기업)

Affiliation (유관기관 관리)
  - 목록: useInfoStore의 clients[]
  - 추가: POST /api/ext/provision/{enterprises|users}/{uuid}/affiliations/add
  - 탈퇴: POST /api/ext/provision/{enterprises|users}/{uuid}/affiliations/withdraw
  - 탈퇴 시 개인회원: ciToken 필요 → usePersonalEasyAuth/useNicePhoneAuth로 재인증

Withdraw (통합회원 탈퇴)
  - 개인: ciToken 발급(flowContext: USER_WITHDRAW) 후 탈퇴 API 호출
  - 기업: 기업관리자만 가능
  ⚠️ 탈퇴 완료 API 미확인 (withdraw complete API 확인 필요)
```

### 3-D. OACX 인증 테스트 페이지 (OacxTest)

개발/QA용 테스트 페이지로 3개 탭:
- `PersonalAuthTab`: OACX EasySign 전체 흐름 테스트
- `BusinessAuthTab`: EzAuth SDK 전체 흐름 테스트  
- `PhoneAuthTab`: NICE 휴대폰 인증 전체 흐름 테스트

모두 `beInstance` (`/api/v1/auth/**`) 사용 → ido 구현 완료됨

---

## 4. 갭(Gap) 분석 — 통합을 위해 해결해야 할 문제

### 🔴 P0 — 서비스 불능 (배포 전 필수 해결)

#### GAP-01: `/api/ext/**` Q-IM 서버 연결 설정
- **문제**: FE의 `extInstance`가 `EXT_API_ENDPOINT` 환경변수 기반으로 Q-IM 외부 서버에 직접 연결
- **현재**: `.env` 기준 `https://onepass-dev.smes.go.kr/im` (개발 서버)
- **확인 필요**: Q-IM 서버에서 FE가 호출하는 26개 `/api/ext/**` 엔드포인트가 모두 구현되어 있는지
- **조치**: Q-IM 팀과 API 스펙 교차검증 필요

#### GAP-02: AES-GCM 키 공유 — CI 암호화 계약
- **문제**: FE의 `utils/crypto/aesGcm.ts`에서 `AES_GCM_KEY`(base64 32바이트)로 CI를 암호화
- **현재**: `.env`에 `AES_GCM_KEY=DL5vnfsm01CfLlycU6k8NvCDy3tpO5/MXMuqV0uMCv8=` 하드코딩
- **수신 측**: Q-IM `POST /api/ext/ci/token`에서 복호화해야 함
- **계약 확인 필요**: 
  - 동일 AES-256-GCM 키 사용 확인
  - 암호화 포맷 확인: `base64(IV(12B) || ciphertext+tag(16B))`
- **리스크**: 키 불일치 시 모든 본인인증 흐름 실패

#### GAP-03: Q-Sign `realm`, `clientId` 값 일치 확인
- **문제**: Step3에서 ciToken 발급 시 `realm: 'ucube-qsign'`, `clientId: 'onepassCli'` 하드코딩
- **현재 q-sign 설정**: `application.yml`의 realm 값과 대조 필요
- **조치**: q-sign 관리자와 값 확인 후 환경변수화

#### GAP-04: ido `X-Internal-Sig` 전달 연동 검증
- **문제**: 이전 세션에서 수정한 `BrokerService.buildInternalSig()` (HMAC-SHA256)
- **연동 확인 필요**: q-sign의 strict-mode 검증 로직과 실제로 서명이 맞는지 End-to-End 테스트 필요
- **테스트 방법**: OacxTest 페이지로 실제 인증 요청 후 ido 로그 확인

---

### 🟠 P1 — 기능 미완성 (배포 후 우선 구현)

#### GAP-05: ConversionStep4 하드코딩 → 실 API 연동
- **문제**: `ConversionStep4.tsx`의 SYSTEMS 배열이 7개 고정 하드코딩
- **원인**: `checkConversion` API 결과를 Step4에서 사용하지 않고 Step6에서 사용
- **의도된 흐름**: Step6의 `checkConversionProxy`가 실 서비스 목록 반환
- **조치**: Step4를 제거하거나 Step6의 결과를 Step4로 이동 (Q-IM 팀 협의)

#### GAP-06: 공동인증서 / Any-ID 인증 미구현
- **문제**: Step3에서 `certificate` 선택 시 `devNoticeModal` 표시 (개발 알림만)
- **영향**: 공동인증서(구 공인인증서), 금융인증서, Any-ID 인증 불가
- **백엔드 현황**: `NonOidcBrokerAdapter.java` 전체 placeholder
- **조치**: 별도 인증 SDK 연동 후 해당 Step3 분기 구현

#### GAP-07: Mypage 정보 로딩 구조 — useInfoStore localStorage 의존
- **문제**: Mypage는 `useInfoStore`(localStorage)에서 회원 정보를 로딩
- **로딩 시점**: 마이페이지 최초 진입 시 어디서 `/api/ext/members/{mbrNo}`를 호출하는지 불명확
- **확인 필요**: `MypageMember.tsx`, `MypageBusiness.tsx`에서 API 호출 후 `InfoStoreContext`에 저장하는 코드 존재 여부
- **조치**: Mypage 컨테이너 컴포넌트에서 초기 로딩 로직 구현 확인/추가

#### GAP-08: 통합회원 탈퇴 API 미확인
- **문제**: `Withdraw.tsx`, `WithdrawStep2.tsx`에서 최종 탈퇴 API를 어디에 호출하는지 불명확
- **확인 필요**: `WithdrawStep2.tsx` 파일의 실제 API 호출 코드

#### GAP-09: `nice/ciCheck.ts` → `POST /api/v1/auth/nice/ci-check` 호출 시점
- **문제**: `CiCheckRequest`에 `mbrDvsnCd: 'A101' | 'A102'` 파라미터가 있는데 ConversionSteps에서 사용 시점 불명확
- **확인 필요**: 어느 Step에서 CI 기반 회원 중복 체크를 하는지 (Step3 after ciToken 발급?)

---

### 🟡 P2 — 보안 강화 (운영 전 권고)

#### GAP-10: `AES_GCM_KEY` 환경변수 클라이언트 번들 노출
- **문제**: webpack DefinePlugin으로 `AES_GCM_KEY`가 브라우저 JS 번들에 포함됨
- **현재**: `.env`에 평문 base64 32바이트 키 노출
- **리스크**: 번들 분석 도구로 키 추출 가능 → CI 암호화 무력화 가능
- **권고 방안**: 
  - Option A: ido BFF에서 CI 암호화를 서버 사이드 처리 (FE는 평문 CI → ido → ido가 암호화 → Q-IM)
  - Option B: 세션 키 교환 방식 (공개키 암호화로 대칭키 전달)
- **단기 완화**: CSP 헤더 강화 + 번들 난독화(TerserPlugin)

#### GAP-11: `EXT_API_KEY` 클라이언트 번들 노출
- **문제**: `.env`의 `EXT_API_KEY=imk-7ddb59c9...`가 번들에 포함
- **리스크**: Q-IM API 직접 호출 가능
- **권고 방안**: ido BFF를 통한 Q-IM API 프록시 (FE → ido → Q-IM)

#### GAP-12: SKIP_AUTH=true 프로덕션 배포 금지
- **현재**: `.env`에 `SKIP_AUTH=true` 설정 → JWT 없이도 PrivateRoute 통과
- **조치**: 배포 시 반드시 제거/false 설정

#### GAP-13: ConversionContext/RegisterContext 새로고침 시 데이터 초기화
- **문제**: Context API 기반 상태 관리 → 페이지 새로고침 시 Step 데이터 전부 소실
- **리스크**: Step3 이후 새로고침 시 ciToken 소실 → Step3부터 재시작 필요
- **권고 방안**: sessionStorage fallback 추가 (ciToken만 세션 저장 후 unmount 시 삭제)

---

### 🟢 P3 — 개선 사항 (운영 안정화)

#### GAP-14: Step4 하드코딩 SYSTEMS — 실제 서비스와 불일치
- SYSTEMS 배열의 7개 시스템이 실제 `getClients()` 결과와 다를 수 있음

#### GAP-15: ConversionContext INITIAL_DATA에 MOCK 데이터 잔존
```typescript
// 현재: MOCK_MEMBER.emailId, MOCK_BUSINESS.companyName 등이 초기값
// 배포 환경에서 테스트 데이터 노출 우려
email: MOCK_MEMBER.emailId,  // ''이지만 MOCK_MEMBER.name: '홍길동'
name: MOCK_MEMBER.name,      // '홍길동' 초기값
```
- MOCK 데이터는 비워야 함 (이미 빈 문자열인 항목도 있지만 name은 '홍길동' 설정)

#### GAP-16: 에러 처리 — ErrorResponseHandler body 필드 선택적 처리
- `ErrorResponse` 타입의 `body?: string` 필드 활용이 일부 컴포넌트에서 누락
- Step2 토큰 만료 감지: `response.message?.includes('INVALID_CONSENT_TOKEN')` — 백엔드 에러 메시지 포맷 계약 필요

---

## 5. 환경변수 목록 — 배포 체크리스트

### FE 환경변수 (`.env.prod` 기준)

| 변수명 | 필수 | 설명 | 예시값 |
|--------|------|------|--------|
| `FRONTEND_API_ENDPOINT` | ✅ | 기본 axios 베이스 URL (ido BFF URL) | `https://onepass.smes.go.kr` |
| `BE_API_ENDPOINT` | ✅ | beInstance 베이스 URL (ido URL) | `https://onepass-ido.smes.go.kr` |
| `BE_API_TARGET` | ✅ (dev proxy용) | webpack proxy 타겟 | `https://onepass-ido.smes.go.kr` |
| `EXT_API_ENDPOINT` | ✅ | Q-IM 서버 URL | `https://onepass.smes.go.kr/im` |
| `EXT_API_KEY` | ✅ | Q-IM X-API-Key | 발급받은 키 |
| `BE_API_KEY` | ✅ | ido X-BE-API-Key | 발급받은 키 |
| `AES_GCM_KEY` | ✅ | CI 암호화 키 (base64 32B) | Q-IM 팀에서 공유 |
| `EASYSIGN_URL` | ✅ | OACX EasySign 팝업 URL | `https://easysign.anyid.go.kr/esign` |
| `EASYSIGN_ORIGIN` | ✅ | OACX EasySign Origin | `https://easysign.anyid.go.kr` |
| `SKIP_AUTH` | ❌ | `false` 또는 제거 (프로덕션) | `false` |
| `NODE_ENV` | ✅ | `production` | `production` |

### BE (ido) 추가 환경변수 (이전 분석에서 확인된 것 외)

| 변수명 | 필수 | 설명 |
|--------|------|------|
| `IDO_INTERNAL_SIG_SECRET` | ✅ | q-sign HMAC-SHA256 시그니처 시크릿 |
| `NICE_CLIENT_ID` | ✅ | NICE 인증 클라이언트 ID |
| `NICE_CLIENT_SECRET` | ✅ | NICE 인증 시크릿 |
| `OACX_API_KEY` | ✅ | OACX 간편인증 API 키 |

---

## 6. 통합 로드맵 — 우선순위별

### 🔴 Phase 1: 개발팀 배포 가능 상태 (즉시, ~1주)

| # | 작업 | 담당 | 산출물 |
|---|------|------|--------|
| 1 | Q-IM 팀에 FE 호출 API 목록 전달 → 구현 여부 교차검증 | 통합팀 | API 갭 리스트 |
| 2 | AES-GCM 키 계약 — FE ↔ Q-IM 동일 키/포맷 확인 | 보안팀 + Q-IM | 암호화 계약서 |
| 3 | q-sign realm/clientId FE 하드코딩 → 환경변수화 | FE 개발자 | PR |
| 4 | `SKIP_AUTH=true` 제거 (스테이징 배포 시) | DevOps | `.env.staging` |
| 5 | ido-BFF AuthController E2E 테스트 — OacxTest 페이지 사용 | QA | 테스트 결과 |
| 6 | FE 빌드 및 ido 연동 스모크 테스트 | 개발팀 | 배포 체크리스트 |

### 🟠 Phase 2: 기능 완성 (2~3주)

| # | 작업 | 담당 | 산출물 |
|---|------|------|--------|
| 1 | ConversionStep4 → `/api/ext/clients` 또는 `checkConversion` 실 연동 | FE 개발자 | PR |
| 2 | Mypage 초기 로딩: MypageMember/Business에서 회원 정보 API 호출 확인/구현 | FE 개발자 | PR |
| 3 | 통합회원 탈퇴 API 연동 (WithdrawStep2 확인) | FE 개발자 | PR |
| 4 | CI 기반 회원 중복 체크(ciCheck) 호출 시점 명확화 | FE + BE | 설계 문서 |
| 5 | ConversionContext/RegisterContext 새로고침 대응 (sessionStorage) | FE 개발자 | PR |
| 6 | MOCK 데이터 잔존 제거 (name: '홍길동' 등) | FE 개발자 | PR |

### 🟡 Phase 3: 보안 강화 (3~4주, 운영 전)

| # | 작업 | 담당 | 산출물 |
|---|------|------|--------|
| 1 | AES-GCM 키 클라이언트 번들 노출 해소 — ido BFF 프록시 방식 검토 | 아키텍트 | 설계 문서 |
| 2 | EXT_API_KEY 클라이언트 노출 해소 — ido BFF 프록시 방식 | 아키텍트 | 설계 문서 |
| 3 | CSP 헤더 강화 + 번들 난독화 | DevOps + FE | nginx 설정 |
| 4 | 공동인증서 / Any-ID 인증 연동 (NonOidcBrokerAdapter 구현) | BE 개발자 | PR |

### 🟢 Phase 4: 품질/운영 안정화 (운영 후)

| # | 작업 | 담당 |
|---|------|------|
| 1 | FE 유닛 테스트 커버리지 확대 (현재 .test.tsx 파일 일부 존재) | FE QA |
| 2 | 에러 메시지 표준화 — BE ErrorResponse ↔ FE ErrorResponseHandler | 아키텍트 |
| 3 | ErrorResponse `body` 필드 타입 일관성 정리 | FE 개발자 |
| 4 | 사업자 상태조회/진위확인 API 실 운영 연동 (국세청 OpenAPI) | Q-IM 팀 |

---

## 7. 코드 수준 주요 발견 사항 (개발팀 주의)

### 7-1. CI 암호화 흐름 (정상 구현 확인)

```
FE Step3 본인인증 성공 → result.ci (평문)
  → encryptCi(ci): AES-256-GCM, Web Crypto API, IV 12B 랜덤
  → base64(IV||ciphertext||tag)
  → result.ci = undefined (즉시 폐기) ✅
  → POST /api/ext/ci/token { encryptedCi, realm, clientId, flowContext }
  → Q-IM: ciToken(JWT) 반환
  → FE: ciToken만 Context에 보관 (CI 평문은 FE에 없음) ✅
```

### 7-2. 동의 토큰 자동 재발급 (Step2) — 잘 구현됨

```typescript
// INVALID_CONSENT_TOKEN 에러 시 토큰 재발급 후 1회 재시도
if (response.message?.includes('INVALID_CONSENT_TOKEN') || 
    errorBody?.includes('INVALID_CONSENT_TOKEN')) {
  // 재발급 → 재시도
}
// ⚠️ 백엔드 에러 응답에 'INVALID_CONSENT_TOKEN' 문자열이 반드시 포함되어야 함
```

**백엔드 Q-IM 확인 필요**: 토큰 만료/재사용 시 응답 body에 `INVALID_CONSENT_TOKEN` 포함 여부

### 7-3. Step5 기업 비밀번호 자동 생성 (주의)

```typescript
// Step5.tsx 기업 프로비저닝 시
const password = data.password || 
  `Rnd${Math.random().toString(36).slice(2, 10)}!${Math.floor(Math.random() * 90 + 10)}`;
```
- `Math.random()` = 암호학적으로 안전하지 않음
- 임시 비밀번호로 사용하고 즉시 변경 유도해야 함
- `crypto.getRandomValues()` 사용 권고

### 7-4. beInstance와 extInstance 두 개 인스턴스 인증 방식

| 인스턴스 | 헤더 | 대상 | JWT |
|---------|------|------|-----|
| `beInstance` | `X-BE-API-Key` | ido BFF | 없음 (Nginx same-origin) |
| `extInstance` | `X-API-Key` | Q-IM | 없음 (API Key) |
| `api/index.ts` (기본 axios) | `Authorization: Bearer {JWT}` | 기존 플랫폼 API | Redux store JWT |

### 7-5. DOMPurify 약관 렌더링 (보안 양호)

```typescript
dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(term.content.body) }}
```
DOMPurify 사용으로 XSS 방어 구현됨 ✅

---

## 8. 정리 — 개발팀 배포 체크리스트

```
[ ] Q-IM 팀: /api/ext/** 26개 엔드포인트 구현 확인 (API 스펙 교차검증)
[ ] Q-IM 팀: AES-256-GCM 키 공유 및 암호화 포맷 확인
[ ] Q-IM 팀: INVALID_CONSENT_TOKEN 에러 응답 포맷 확인
[ ] FE:  realm='ucube-qsign', clientId='onepassCli' 환경변수화
[ ] FE:  SKIP_AUTH=false (스테이징/프로덕션)
[ ] FE:  MOCK 데이터 name='홍길동' 제거 (ConversionContext INITIAL_DATA)
[ ] BE:  IDO_INTERNAL_SIG_SECRET 값 q-sign 동기화 (HMAC-SHA256 검증)
[ ] BE:  NICE/OACX 운영 자격증명 발급 및 환경변수 설정
[ ] QA:  OacxTest 페이지로 개인/기업/휴대폰 인증 E2E 테스트
[ ] QA:  ConversionSteps Step1→8 전체 흐름 테스트 (개인/기업 각각)
[ ] QA:  RegisterSteps 전체 흐름 테스트
[ ] QA:  Mypage 정보조회/수정/유관기관 관리/탈퇴 테스트
[ ] DevOps: AES_GCM_KEY 키 관리 방안 (Vault/KMS) 적용
[ ] DevOps: Nginx /api/ext 프록시 → Q-IM 내부망 라우팅 설정
```

---

*문서 생성: AI 코드 분석 기반, 2026-05-12*  
*다음 업데이트: Q-IM API 교차검증 완료 후*
