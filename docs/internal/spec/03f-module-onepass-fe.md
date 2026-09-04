# 03-F. onepass-fe 모듈 상세 명세 — 데이터 흐름 정본

> **기준 버전**: v1.9.3+  
> **기준 커밋**: `50954de` (main)  
> **최종 갱신**: 2026-06-02  
> **모듈 경로**: `idem-console/frontend/`  
> **런타임**: Node 18+ (빌드) / Nginx 1.25 (런타임, 포트 **8080**)  
> **분석 범위**: `onepass-fe`만 — Q-IM / Q-Sign / IdO 내부 처리는 의도적 블랙박스

---

## 0. 한 줄 정리

> **onepass-fe = "유관기관 ↔ IdO(게이트웨이) 사이의 React SPA + Nginx 정적 호스트"**  
> URL 쿼리 / 외부 SDK 콜백 / Keycloak form-POST 를 입력으로 받아, **React Context + Redux + LocalStorage + 짧은 메모리** 4계층 상태에 보관하면서, **단일 베이스 URL(IdO 게이트웨이) 로 만든 axios 인스턴스 군**을 통해 IdO 로 내보낸다.  
> **CI 평문은 절대 영속 저장하지 않는다** (`ciToken` JWT 참조 토큰만 메모리에 둠).
>
> **🔒 단일 채널 헌법 (ADR-008)**: onepass-fe 는 **Q-IM / Q-Sign / agency-stub 등 어떤 백엔드도 직접 호출하지 않는다**. 모든 외부 호출은 IdO 게이트웨이 단일 채널을 거친다. (코드 측 명명 정합화 — `BE_API_*` → `IDO_API_*` — 는 **Phase 2 에서 완료**: PR #203, 커밋 `a7065ae`. 한 페이즈 동안 구 명칭 fallback 유지. 02-architecture.md ADR-008 / 09-gap-and-roadmap.md §6.A.1 참조.)

---

## 1. 진입(Inbound) 표면 — 외부로부터 받는 것

### 1.1 정적 자산 서빙 (Nginx)

`idem-console/frontend/conf/default.conf`:

| 항목 | 값 |
|------|-----|
| Listen | `8080` |
| Document Root | `/usr/share/nginx/html` |
| SPA fallback | `try_files $uri $uri/ /index.html` — 모든 미매칭 URL → React Router로 위임 |
| Body limit | `client_max_body_size 24M` |
| Header buffer | `large_client_header_buffers 8 128k` (대형 쿼리 대비) |
| **IdO reverse proxy** | **없음** — Nginx는 순수 정적 서빙만 (dev 시 webpack-dev-server 가 `/api → IdO` 프록시 담당) |
| OpenTelemetry `/v1/traces` 프록시 | **현재 주석 처리됨** (FE 트레이스 미수집 상태) |

> **핵심**: onepass-fe Nginx 는 IdO 를 프록시하지 않는다 (prod). 모든 IdO 호출은 **브라우저 → IdO 게이트웨이(별도 호스트)** 로 CORS 직접 요청. Q-IM / Q-Sign 으로의 직접 호출은 **존재하지 않는다** (ADR-008).

### 1.2 URL 쿼리 파라미터 — 유관기관 진입의 1차 인터페이스

#### (A) 전환(Conversion) 진입 — `/conversion/step1`

`pages/ConversionSteps/member/Step1.tsx`:

**신규 흐름** (PR #196 — onepass-be가 발급한 JWT 단일 파라미터):
```
?signed_request=<JWT>
```
- onepass-fe는 JWT를 **파싱·검증하지 않는다**. 그대로 `POST /api/v1/conversion/init`으로 forward
- 응답으로 `conversion_session_id`, `user_type` (`INDIVIDUAL` | `ENTERPRISE`) 수신
- → Context 의 `conversionSessionId`, `memberType` 으로 저장

**레거시 흐름** (URL에 평문 4개 파라미터):
```
?redirect_uri=<url>&mbrId=<old-system-user-id>&return_client=<sso-client-id>&userType=ENT|IND
```
- 4개 모두 Context에 즉시 저장. 누락 시 `missingParams` 모달 → 뒤로가기

#### (B) 로그인 진입 — `/login`

`pages/Login/hooks.ts > useKeycloakParams()`:

| 쿼리 키 | 의미 |
|---------|------|
| `action_url` | Keycloak action form-POST URL. `<form action={actionUrl}>` 으로 그대로 사용 |
| `error`, `code` | Keycloak 인증 결과 (에러 모달 표시용) |
| `return_uri` | 인증 후 돌아갈 유관기관 URL. `origin + 1단 path` 만 추출해서 "홈" 링크로 변환 |
| `return_client` | SSO 클라이언트 ID. LocalStorage `LOGGED_IN_RETURN_CLIENT` 에 저장 |

### 1.3 외부 SDK 콜백 — window 전역 함수로 들어오는 입력

| SDK | 정적 파일 위치 | 콜백 페이로드 |
|-----|---------------|---------------|
| **EzAuth** (기업 간편인증) | `public/ezauth/js/*` + `EzauthChild.html` | `window.EzAuth.makeEzauthSimple(opt, cb)` → `{resultCode, resultMsg, result:{name, businessNumber, birth, phone, bizOpendt}, txId, tokenId}` |
| **NICE 휴대폰 인증** | (외부 NICE 스크립트 동적 로드) | `{resultCode, resultMsg, ci, name, phone, birthday}` |
| **개인 간편인증 (EasySign)** | 위와 동일 SDK 경로 | `EasysignResult { resultCode, resultMsg, ci, name, birthday, phone }` |

> **중요**: 콜백은 DOM 이벤트가 아니라 **window 전역 함수 호출** 로 들어옴. onepass-fe는 이걸 React state 로 끌어올린 뒤 즉시 BE에 송신하거나 폐기한다.

### 1.4 HTTP 응답 인터셉터 — 간접 입력 (자동 동작)

`api/index.ts` axios 인터셉터:

- **401 Unauthorized** 수신 시 → LocalStorage `REFRESH_AUTH_TOKEN` 으로 자동 재로그인 시도 → 성공 시 원 요청 재시도, 실패 시 `Logout()` → `/login` 강제 이동
- `request` 인터셉터가 모든 요청에 `Authorization: Bearer <accessJwt>` 헤더 자동 부착

---

## 2. 상태(State) 표면 — 받은 것을 보관하는 4 계층

### 2.1 React Component useState — 가장 짧은 라이프타임

가장 민감한 데이터가 여기에 들어감 (페이지 떠나면 즉시 휘발):

| 데이터 | 위치 | 라이프타임 |
|--------|------|-----------|
| `encCi` (CI 또는 암호화 CI) | `Login.tsx` | 사용자 입력 ~ form submit |
| `password` 입력값 | `Login.tsx`, Conversion/Register Step5 | 입력 ~ submit |
| **CI 평문** | `ConversionStep3.tsx` SDK 콜백 직후 | **수 ms** — `encryptCi()` await 완료 즉시 `result.ci = undefined` |
| 모달 상태, 폼 검증 결과 | 각 Step | 페이지 단위 |

### 2.2 React Context — 전환 세션 동안만 유지

`providers/Conversion/ConversionContext.tsx`:

```typescript
interface ConversionData {
  // Step1 입력 (URL 쿼리 또는 conversionInit 응답)
  memberType: 'member' | 'business'
  initialClientId: string             // ← return_client 쿼리
  redirectUri: string                 // ← redirect_uri 쿼리 (Step8 복귀처)
  mbrId: string                       // ← mbrId 쿼리 (구 시스템 사용자 ID)
  conversionSessionId?: string        // ← BE 발급 (signed_request 처리 후)

  // Step3 인증 결과 (CI 평문은 절대 저장 안 함)
  ciToken: string                     // ← BE 발급 JWT 참조 토큰만 보관
  birthDate: string
  brno: string                        // 사업자번호 (기업 흐름)

  // Step5 입력 폼
  loginId, email, emailDomain, phone, telPrefix, telSuffix
  bzmnNm, rprsvNm, startDt, password  // 기업
  name, phonePrefix, phoneSuffix       // 개인
  notifications: Record<string, boolean>

  // Step6 서비스 연결
  selectedClients: string[]
  availableClients: Client[]

  // 프로비저닝 결과
  entMbrNo, mbrNo, mbrUuid, provisioningToken
  consentEventId?: number             // Step2 약관동의 결과
}
```

> **휘발성 보장**: 이 Context 는 페이지 새로고침 시 사라진다 (sessionStorage 백업 없음). 의도된 동작.

### 2.3 Redux Store — 로그인 세션 동안 유지

`store/` (types/actions/app.ts 기준):

- `app.user`: `{ ROLE, email, name, orgId, orgName, profilePictureURL, userId, userFlags, accessJwt, refreshJwt }`
- `app.isLoggedIn`, `app.isUserFetching`, `app.isUserFetchingError`
- `app.org`: 소속 조직 목록

### 2.4 LocalStorage — 영구 보관

`constants/localStorage.ts` 키 + `api/utils.ts > Logout()` 가 명시적으로 지우는 키들:

| 키 | 용도 |
|----|-----|
| `AUTH_TOKEN` | accessJwt |
| `REFRESH_AUTH_TOKEN` | refreshJwt (자동 재로그인용) |
| `IS_LOGGED_IN` | `'true'` / 부재 |
| `IS_IDENTIFIED_USER` | 본인인증 완료 플래그 |
| `LOGGED_IN_USER_EMAIL`, `LOGGED_IN_USER_NAME` | 표시용 |
| `CHAT_SUPPORT` | Intercom 등 외부 서비스 식별자 |
| `LOGGED_IN_RETURN_CLIENT` | 로그인 후 복귀 클라이언트 ID |

> **명시적 분리**: CI 평문, ciToken, password 는 **LocalStorage 에 저장하지 않는다**.

---

## 3. 송신(Outbound) 표면 — 외부로 내보내는 것

> **단일 채널 원칙**: 본 절의 모든 axios 인스턴스의 baseURL 은 **정확히 1 개 호스트 (IdO 게이트웨이)** 를 가리킨다. 명명도 코드 레벨에서 정합화됨 (Phase 2 완료, PR #203 / `a7065ae`): 변수 `IDO_API_*`, axios 식별자 `idoInstance` / `idoApiInstance`, 헤더 `X-IDO-API-Key`. `.env` 의 `IDO_API_TARGET=https://onepass-ido-dev.smes.go.kr` 로 단일 호스트 직접 확인 가능. 구 명칭 (`BE_API_*` / `X-BE-API-Key` / `beInstance` / `extInstance`) 은 코드에서 제거되었으며, 환경변수 차원에서만 한 페이즈 동안 fallback 유지 (`process.env.IDO_API_* || process.env.BE_API_*`).

### 3.1 IdO 호출 — axios 인스턴스

#### (A) `idoInstance` / `idoApiInstance` — `api/idoInstance.ts`

```typescript
const IDO_BASE_URL: string =
  process.env.IDO_API_ENDPOINT || process.env.BE_API_ENDPOINT || '';
const IDO_API_KEY: string =
  process.env.IDO_API_KEY || process.env.BE_API_KEY || '';

const idoInstance = axios.create({
  baseURL: IDO_BASE_URL,                                 // ← IdO 게이트웨이
  headers: {
    'Content-Type': 'application/json',
    'X-IDO-API-Key': IDO_API_KEY,                        // ← IdO 가 검증하는 FE 식별 키
  },
});
export default idoInstance;
export const idoApiInstance = axios.create({ /* 동일 설정 */ });
```

> **명명 정합 완료 (Phase 2, PR #203 / `a7065ae`)**: 구 식별자 `beInstance` / `beApiInstance` / 구 환경변수 `BE_API_*` / 구 헤더 `X-BE-API-Key` 는 코드에서 모두 제거됨. 환경변수만 한 페이즈 동안 fallback 유지 (위 코드의 `||` 라인). 단일 채널 헌법 ADR-008 / 09-gap-and-roadmap.md §6.A.1 참조.

#### (B) ~~`extInstance`~~ — **제거됨** (Phase 2, SEC-IDO-06)

과거 `api/extInstance.ts` 는 B-5 보안 패치 이후 `beApiInstance` 의 단순 re-export 셸이었으며, grep 으로 사용처 0건 확인 후 **Phase 2 에서 파일 자체 삭제** (PR #203 / `a7065ae`). 신규 코드는 `idoApiInstance` 를 직접 import 한다.

> **참고 — B-5 보안 패치의 잔향**: 패치 이전에는 FE 가 Q-IM 을 직접 호출하는 `extInstance` 경로가 존재했고, 패치 이후 `beApiInstance` 의 re-export 셸로 축약되었으며, 최종적으로 Phase 2 에서 코드 자체가 삭제되었다. 즉 "FE → Q-IM 직접" 의 흔적은 코드 차원에서 완전히 사라진 상태.

> **B-5 패치의 의의**: 이 패치는 본 문서의 단일 채널 원칙(ADR-008) 을 **코드에 강제** 하는 사건이었다. 이전에는 FE 가 Q-IM 을 직접 호출하는 경로가 존재했으나, B-5 이후 모든 `/api/ext/**` 트래픽이 IdO `ExtProxyController` 를 경유하도록 전환되었고, Phase 2 (PR #203) 에서 셸 잔재까지 제거되어 코드 레벨에서도 단일 채널이 강제된다.

#### (C) `instance` (default) + V2/V3/V4/Gateway — `api/index.ts`

- baseURL: `${ENVIRONMENT.baseURL}${apiV1}` 등
- request 인터셉터: `Authorization: Bearer <accessJwt>` 자동 부착
- response 인터셉터: 401 시 refresh → 원 요청 재시도

### 3.2 호출 엔드포인트 인벤토리

#### 전환 세션 / 외부 진입 관련 — `api/conversion/`, `api/ext/`, `api/provision/`, `api/nice/`

| 메서드 | 경로 | 호출 위치 |
|--------|-----|----------|
| `POST` | `/api/v1/conversion/init` | Step1 — `signed_request` JWT 처리 |
| `POST` | `/api/v1/ext/ci/token` | Step3 — `encryptedCi → ciToken` 교환 |
| `POST` | `/api/v1/auth/nice/ci-check` | NICE CI 중복 확인 |
| `GET` | `/api/v1/ext/clients` | Step6 — 유관기관 목록 |
| `GET` | `/api/v1/ext/terms/bundle?realm=&client=&lang=` | Step2 — 약관 묶음 |
| `POST` | `/api/v1/ext/consent/token` | Step2 — 동의 토큰 발급 |
| `POST` | `/api/v1/ext/consent` | Step2 — 동의 제출 |
| `GET` | `/api/v1/ext/check-duplicate?...` | Step5 — loginId/email 중복 |
| `POST` | `/api/v1/ext/business/status` | Step3 — 기업 사업자 상태 조회 |
| `POST` | `/api/v1/ext/provision/users/check-conversion` | Step6 — 다음 클릭 시 (개인) |
| `POST` | `/api/v1/ext/provision/enterprises/check-conversion` | Step6 — 다음 클릭 시 (기업) |
| `GET` | `/api/v1/ext/auth-status` | 인증 상태 폴링 |
| `GET` | `/api/v1/ext/auth-result/{txId}` | tx 결과 |
| `GET` | `/api/v1/ext/members/{mbrNo}` | 개인회원 조회 |
| `GET` | `/api/v1/ext/members/{mbrUuid}/affiliations` | 유관서비스 목록 |
| `GET` | `/api/v1/ext/enterprises/{entMbrNo}` | 기업회원 조회 |
| `GET` | `/api/v1/ext/enterprises/{mbrUuid}/affiliations` | 기업 유관서비스 |
| `POST` | `/api/v1/ext/provision/users/modify_local` | 개인 정보 수정 |
| `POST` | `/api/v1/ext/provision/enterprises/modify_local` | 기업 정보 수정 |

#### 일반 사용자/계정 — `api/user/`, `api/account/`

| 메서드 | 경로 | 호출 위치 |
|--------|-----|----------|
| `POST` | `{baseURL}/api/v1/login` | 로그인 (`api/user/login.ts`) |
| `GET` | `{baseURL}/api/v1/loginPrecheck?email=&ref=` | 로그인 사전 검증 |
| `POST` | `{baseURL}/api/v1/register` | 자체 가입 |
| 다수 | `{baseURL}/api/v1/user/*`, `org/*`, `invite/*`, `roles/*` | 인증 후 사용 |
| 다수 | `findLoginId`, `passwordChange`, `resetPassword` | 계정 복구 |

### 3.3 IdO 외 외부 호스트로의 송신

> 아래 경로는 **API 호출이 아닌 브라우저 차원의 form-POST / location 이동 / SDK 자체 통신** 으로, axios 단일 채널 원칙(ADR-008) 의 적용 대상이 아니다. 그러나 그 결과 데이터(예: SDK 콜백) 의 **API 송신은 반드시 IdO 단일 채널** 을 거친다.

| 대상 | 트리거 | 데이터 |
|------|-------|-------|
| **Keycloak** | Login 페이지 `<form action={actionUrl} method="POST">` 직접 submit | `username`/`password` 또는 `bizNo`+`encCi` hidden field |
| **유관기관 콜백 URL** | `ConversionStep8` 의 `window.location.href = data.redirectUri` | URL 이동만 (body 없음) |
| **NICE / EzAuth 서버** | SDK 내부 자체 호출 | onepass-fe 코드 비관여 |

### 3.4 환경변수 — 빌드 시 번들에 박히는 값

| 변수 | 용도 | 비고 |
|------|-----|------|
| `IDO_API_ENDPOINT` / `IDO_API_TARGET` | `idoInstance` baseURL / dev proxy 타겟 | 필수. IdO 게이트웨이 endpoint (예: `https://onepass-ido-dev.smes.go.kr`). **Phase 2 (PR #203) 에서 `BE_API_*` 로부터 rename 완료.** 구 명칭은 webpack DefinePlugin + 런타임 코드에서 한 페이즈 동안 fallback 으로만 인식 |
| `IDO_API_KEY` | `X-IDO-API-Key` 헤더 값 | ⚠️ **FE 번들에 노출됨** — DevTools로 추출 가능. **IdO** 의 origin/CORS/Rate-Limit/API 키 화이트리스트가 실질 방어선. **Phase 2 (PR #203) 에서 `BE_API_KEY` 로부터 rename 완료.** 구 명칭 fallback 동일 |
| `FRONTEND_API_ENDPOINT` | `ENVIRONMENT.baseURL` (axios `instance`) | IdO 의 또 다른 별칭. Phase 2 範圍 외 (별도 후속 정리 대상) |
| `WEBSOCKET_API_ENDPOINT` | (WebSocket용, 현재 사용 미확인) | |
| `AES_GCM_KEY` | `utils/crypto/aesGcm.ts` 가 사용하는 **CI 암호화 키 (base64)** | ⚠️ **FE 번들에 박힘** — 클라이언트가 키를 들고 암호화 후 IdO → Q-IM 으로 송신. Q-IM 에서 같은 키로 복호화. 키 노출 위협 큼 — 별도 보안 검토 필요 |
| `SKIP_AUTH` | Private route 우회 (개발용) | ⛔ prod에서 절대 `true` 금지 |
| `NODE_ENV` | `loginPrecheck` 등 dev 폴백 분기 | |

---

## 4. 종단 데이터 흐름 — 유관기관 사용자 진입 시 전형 시나리오

대표 케이스: **유관기관 시스템에서 "통합ID 전환" 버튼 클릭 → onepass-fe 진입 → 전환 완료 → 유관기관 복귀**

```
[유관기관 시스템]
    │
    │ 1. window.location =
    │    https://onepass.smes.go.kr/conversion/step1?signed_request=<JWT>
    │    (또는 레거시: ?redirect_uri=&mbrId=&return_client=&userType=)
    ▼
[Nginx :8080  ← onepass-fe 정적 호스트]
    │ 2. try_files 미스 → /index.html 반환 (React SPA 부트)
    ▼
[React Router → ConversionStep1]
    │ 3. URLSearchParams.get('signed_request')
    │    POST {IdO}/api/v1/conversion/init  { signed_request }      ┐
    │    Headers: Content-Type:application/json, X-IDO-API-Key:<env> │  모든 호출은
    │                                                                │  IdO 게이트웨이
    ▼                                                                │  단일 채널
┌──────────────────────────────────────────────────────────────────┐│  (ADR-008)
│  IdO 게이트웨이 (BFF + Gateway + Orchestrator)                    │┘
│   ├─ FeSessionController        feSessionId 발급/검증            │
│   ├─ ExtProxyController         /api/ext/** → Q-IM forward proxy │
│   │                              (서버측 X-Ext-Api-Key 주입,      │
│   │                              /api/ext/ci/** Q3=B 차단)        │
│   └─ Orchestrator               q-sign-init → quick-status → ... │
└──────────────────────────────────────────────────────────────────┘
    │ ◀── { conversion_session_id, user_type, expires_at }
    │ 4. updateData({ conversionSessionId, memberType }) → Context 저장
    │    (레거시 흐름이면 redirectUri / mbrId / initialClientId 도 함께)
    ▼
[Step2: 약관]   ※ 아래 모든 /api/v1/** 는 IdO 단일 채널 호출
    │ GET  /api/v1/ext/terms/bundle?realm=qim&client=sp-smeg&lang=ko
    │ POST /api/v1/ext/consent/token  { ... }
    │ POST /api/v1/ext/consent        { ... }
    │ updateData({ consentEventId })
    ▼
[Step3: 본인/기업 인증]                            ⚠️ 가장 민감
    │ - useState 'authType' = phone | app | certificate | anyid
    │ - SDK 콜백 진입:
    │     window.EzAuth.makeEzauthSimple(...)  →  { ci, name, phone, ... }
    │   또는 NICE phone, EasySign
    │
    │ ★ CI 평문 처리 (수 ms 라이프타임)
    │   const encrypted = await encryptCi(result.ci)   // AES-GCM, IV 12B
    │   result.ci = undefined                          // 평문 즉시 폐기
    │
    │ POST /api/v1/ext/ci/token  { encryptedCi, realm, clientId, flowContext,
    │                              name, birthDate, gender, phone }
    │ ◀── { ciToken (JWT), mbrUuid }
    │
    │ updateData({ ciToken, mbrUuid, birthDate, ... })
    │ ※ Context 에는 ciToken 만 들어감. CI 평문은 어디에도 안 남음.
    ▼
[Step4: 추가 정보] (Context 폼 입력만)
    ▼
[Step5: 계정 폼]
    │ GET  /api/v1/ext/check-duplicate?...        (loginId/email 중복)
    │ POST /api/v1/ext/provision/users(또는 enterprises)/...
    │ updateData({ mbrNo, entMbrNo, provisioningToken })
    ▼
[Step6: 유관시스템 서비스 선택]
    │ GET  /api/v1/ext/clients
    │   ◀── { clients, groups, businessTypes }
    │   setClients(...) ; if (initialClientId 매칭) → selectedClients 기본 1개
    │ 체크박스 조작 → updateData({ selectedClients })
    │ "연결하기" 클릭:
    │   POST /api/v1/ext/provision/users/check-conversion
    │        { mbrId, ci: ciToken, targetClientId: selectedClients }
    │   (또는 enterprises: { brno, targetClientId })
    ▼
[Step8: 완료 화면]
    │ "유관기관 서비스로 이동하기" 클릭:
    │   window.location.href = data.redirectUri
    │   (Context 의 redirectUri 가 비어있으면 → /login 폴백)
    ▼
[유관기관 시스템으로 복귀]
```

---

## 5. onepass-fe 의 책임 (DO)

| 책임 | 위치 | 비고 |
|------|------|------|
| ✅ URL 쿼리 (`signed_request` / `redirect_uri` / `action_url`) 파싱·라우팅 | `Step1.tsx`, `Login/hooks.ts` | 파싱만. **JWT 서명 검증은 안 함** — BE 책임 |
| ✅ React Context 로 다단계 사이 상태 운반 | `providers/Conversion/ConversionContext.tsx` | 세션 휘발성 |
| ✅ Redux + LocalStorage 로그인 세션 유지 + 401 자동 refresh | `api/index.ts` 인터셉터, `store/`, `api/utils.ts > Logout` | |
| ✅ 외부 SDK(EzAuth / NICE / EasySign) 통합 및 콜백 결과 정규화 | `hooks/useEzAuth.ts`, `useNicePhoneAuth.ts`, `usePersonalEasyAuth.ts`, `public/ezauth/*` | |
| ✅ **CI 평문 즉시 폐기 + AES-GCM 암호화** | `utils/crypto/aesGcm.ts`, `Step3.tsx` | 평문은 메모리에 ms 단위 |
| ✅ Keycloak 로그인 form-POST 빌드 (`action_url` 사용) | `Login/index.tsx` (form.current.submit) | |
| ✅ axios 인스턴스 표준화 + 일관 에러 핸들링 | `api/idoInstance.ts`, `api/index.ts`, `api/ErrorResponseHandler.ts` | |
| ✅ 다국어 (i18n) | `public/locales/{ko,en,jp}` | |
| ✅ 최종 redirect (`window.location.href = data.redirectUri`) | `Step8.tsx` | 유관기관 복귀의 유일한 출구 |

## 6. onepass-fe 의 비책임 (DO NOT — 다른 모듈에 위임)

| 비책임 | 위임처 |
|--------|--------|
| ❌ **Q-IM / Q-Sign / agency-stub 직접 호출** | **IdO 단일 채널** (ADR-008) — 모든 외부 호출은 IdO `/api/**` 경유 |
| ❌ CI 복호화·DI 생성·HMAC | **Q-IM** (IdO 가 encryptedCi 를 Q-IM 으로 forward) |
| ❌ JWT (`signed_request`, `ciToken`) 서명 검증 | IdO / Q-IM |
| ❌ Keycloak 토큰 발급 / 세션 관리 | **Keycloak + Q-Sign** (FE 는 form-POST 만 수행) |
| ❌ 유관기관 SSO 클라이언트 메타데이터 마스터 | **Q-IM** (`/api/v1/ext/clients` 응답이 정본, IdO 가 proxy) |
| ❌ 약관 본문·버전 관리 | **Q-IM** (`/api/v1/ext/terms/bundle`, IdO 가 proxy) |
| ❌ 사용자 DB 영속 / 중복 판정 | **Q-IM** (`check-duplicate`, `check-conversion`, IdO 가 proxy) |
| ❌ 감사 로그 적재 | IdO 측 미들웨어 + BE 측 (`platform.audit.log`) |
| ❌ Nginx 가 IdO/Q-IM 을 프록시 (prod) | 현재 없음 — 브라우저가 IdO 에 CORS 직접 호출 |
| ❌ API 키 (`X-Ext-Api-Key` 등 외부 키) 보유 | **IdO** 가 서버측 주입 (FE 번들에 절대 미포함) — `ExtProxyController` |

> **🔒 단일 채널 헌법 (ADR-008)**:
> onepass-fe 는 **Q-IM / Q-Sign / agency-stub 등 어떤 백엔드도 직접 호출하지 않는다**.
> 모든 외부 호출은 **IdO 게이트웨이 단일 채널** 을 거친다.
> 향후 도입될 `onepass-admin` 등 신규 FE 도 동일 원칙을 따르며, FE 가 1 개에서 N 개로 늘어나도 BE 노출 표면은 불변이다 (IdO 의 "BE 보호 불변식").
>
> **Q-IM 책임 헌장 정합**: `03c-qim-responsibility-charter.md` §6 / §6.5 에 따라, Q-IM 은 사람-대상 UI 면을 영구히 갖지 않으며, 운영자 콘솔도 영구히 금지된다. 사람-대상 화면은 **FE 군(현재 `onepass-fe`, 향후 `onepass-admin` 등)** 이 호스트하고, 그 FE 군은 **IdO 단일 채널** 을 통해서만 백엔드와 통신한다. 본 문서는 그 중 `onepass-fe` 측 단면이다.

---

## 7. 보안·운영 위험 표면 (FYI)

| ID | 표면 | 위치 | 영향 | 권고 |
|----|------|------|------|------|
| FE-RISK-01 | `X-IDO-API-Key`, `AES_GCM_KEY` 가 **JS 번들에 평문** | `idoInstance.ts`, `utils/crypto/aesGcm.ts` | DevTools 로 누구나 추출 가능 | IdO 의 origin / CORS / Rate-Limit 가 실질 방어. 키 회전 절차 정립 |
| FE-RISK-02 | LocalStorage 에 `accessJwt`, `refreshJwt` 평문 저장 | `api/utils.ts > Logout` 키 목록 | XSS 1회 → 토큰 탈취 | httpOnly Cookie 전환 검토 |
| FE-RISK-03 | 401 자동 재시도 — race 시 무한 루프 가능성 | `api/index.ts > interceptorRejected` | DoS-like | 재시도 카운터 + circuit breaker |
| FE-RISK-04 | `SKIP_AUTH=true` 환경변수가 Private route 우회 | `AppRoutes/Private.tsx` | 인증 우회 | prod 빌드에서 정의 자체 차단 |
| FE-RISK-05 | `loginPrecheck` dev 모드 폴백 | `api/user/loginPrecheck.ts` | dev 응답을 prod 에서 오용 가능 | 빌드 시점 분기 검증 강화 |
| FE-RISK-06 | SPA 클라이언트 라우팅 — 권한 외 페이지도 일단 React 가 받음 | `AppRoutes/Private.tsx` | 정보 노출 | 서버사이드 보호 보완 |
| FE-RISK-07 | OpenTelemetry 프록시 주석 처리 | `conf/default.conf` | 현재 FE 트레이스 미수집 | DNS 검증 후 활성화 |

---

## 8. 라우트 인벤토리 (`constants/routes.ts` 발췌)

| 카테고리 | 경로 |
|---------|------|
| 진입 | `/login`, `/conversion/step1`, `/register/step1`, `/register-minor/step1` |
| 전환 (개인) | `/conversion-member/step2 ~ step6` |
| 전환 (기업) | `/conversion-business/step2 ~ step6` |
| 가입 (개인) | `/register-member/step2 ~ step6` |
| 가입 (기업) | `/register-business/step2 ~ step6` |
| 마이페이지 (개인) | `/mypage-member`, `/mypage-member/information/step2~3`, `/affiliation/{add,withdraw}/step1~2`, `/password/step2`, `/withdraw/step2`, `/withdraw/{complete,fail}` |
| 마이페이지 (기업) | `/mypage-business` 동일 구조 |
| 계정 복구 | `/find-id`, `/find-id-result`, `/find-id-not-found`, `/find-password`, `/find-password-new` |
| 정책 | `/use-terms`, `/privacy` |
| 시스템 | `/something-went-wrong`, `/un-authorized`, `/not-found`, `/oacx-test` |

---

## 9. 텍스트 다이어그램 — 전체 표면 요약

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          BROWSER (사용자)                                │
│                                                                          │
│  ┌──────────────┐   ┌─────────────────┐   ┌──────────────────────────┐  │
│  │ URL params   │   │ window.EzAuth   │   │ form.submit(actionUrl)   │  │
│  │ signed_req…  │   │ NICE / EasySign │   │ → Keycloak               │  │
│  └──────┬───────┘   └────────┬────────┘   └────────────▲─────────────┘  │
│         │ inbound            │ callback                │ outbound       │
│         ▼                    ▼                         │                │
│  ┌─────────────────────────────────────────────────────┴─────────────┐  │
│  │             onepass-fe React SPA (Nginx :8080)                    │  │
│  │             (FE 군 멤버 — 향후 onepass-admin 등 추가 가능)        │  │
│  │                                                                    │  │
│  │  STATE LAYERS                                                      │  │
│  │  ├─ React useState           → CI 평문(수 ms), password            │  │
│  │  ├─ Conversion Context       → ciToken, mbrUuid, brno,             │  │
│  │  │                             redirectUri, mbrId, selectedClients │  │
│  │  ├─ Redux Store              → user, accessJwt, refreshJwt, org    │  │
│  │  └─ LocalStorage             → AUTH_TOKEN, REFRESH_AUTH_TOKEN,     │  │
│  │                                IS_LOGGED_IN, USER_EMAIL/NAME       │  │
│  │                                                                    │  │
│  │  CRYPTO BOUNDARY                                                   │  │
│  │  utils/crypto/aesGcm.ts                                            │  │
│  │  ┌────────────────────────────────────────────────────────┐        │  │
│  │  │ encryptCi(plain) = base64( IV(12) || ct || tag(16) )   │        │  │
│  │  │ key = process.env.AES_GCM_KEY (FE bundle)              │        │  │
│  │  │ then: result.ci = undefined  ← 평문 즉시 폐기          │        │  │
│  │  └────────────────────────────────────────────────────────┘        │  │
│  │                                                                    │  │
│  │  HTTP CLIENTS  (baseURL = IdO 단일 채널; ADR-008)                  │  │
│  │  ├─ idoInstance       + X-IDO-API-Key 헤더 (Phase 2, PR #203 완료)     │  │
│  │  ├─ instance (api/)   + Authorization: Bearer <accessJwt>          │  │
│  │  │                    + 401 시 refresh 후 재시도                    │  │
│  │  └─ ~~extInstance~~    Phase 2 (SEC-IDO-06) 에서 파일 자체 삭제      │  │
│  └──────────────────────────────┬─────────────────────────────────────┘  │
│                                 │ axios → CORS direct (IdO Origin only)  │
└─────────────────────────────────┼────────────────────────────────────────┘
                                  ▼
              ┌───────────────────────────────────────────────────┐
              │  IdO (게이트웨이 + BFF + Orchestrator)            │
              │  ─────────────────────────────────────────────    │
              │  · FeSessionController   feSessionId 발급/검증    │
              │  · ExtProxyController    /api/ext/** → Q-IM 프록시│
              │                          (X-Ext-Api-Key 서버 주입,│
              │                           /api/ext/ci/** 차단)    │
              │  · Orchestrator          다단 흐름 일괄 지휘      │
              │  · 18 controllers — 전부 @RestController          │
              │    (static/anyid/* 은 SDK 자산만, HTML 없음)      │
              └────────┬──────────────────────────────────┬───────┘
                       │ 서버↔서버 (FE 비관여)            │
                       ▼                                  ▼
               ┌──────────────┐                  ┌──────────────┐
               │  Q-IM (8082) │                  │ Q-Sign / KC  │
               └──────────────┘                  └──────────────┘
               ↑ onepass-fe 는 IdO 까지만 관여. 그 너머는 블랙박스.
               ↑ FE 가 N 개로 늘어도(BE 보호 불변식) 이 그림의 IdO↓
                  부분은 변하지 않는다.
```

---

## 10. 변경 추적 트리거

본 문서를 갱신해야 하는 사건:

1. **새 URL 쿼리 파라미터 추가/변경** (`signed_request` 외 신규) → §1.2 갱신
2. **새 외부 SDK 통합** → §1.3 + §3.3 갱신
3. **새 BE 엔드포인트 호출 추가** → §3.2 인벤토리 갱신
4. **State 계층 변화** (예: sessionStorage 도입, Context 필드 추가) → §2 갱신
5. **신규 환경변수** → §3.4 갱신 + FE-RISK 표 검토
6. **새 라우트 추가** → §8 갱신

---

## 11. 함께 읽기

- [`02-architecture.md`](02-architecture.md) **ADR-008** — **FE 군 ↔ IdO 단일 채널 헌법**. 본 문서의 모든 "단일 채널" 서술의 출처. 3 단 명제(책임 종류 → 모듈군 → 단일 게이트웨이), Option A/B 인증 모델, onepass-admin 도입 체크리스트 포함
- [`02-architecture.md`](02-architecture.md) **ADR-002** — onepass-fe 순수 React SPA 전환. ADR-008 의 직접 선조
- [`03c-qim-responsibility-charter.md`](03c-qim-responsibility-charter.md) §6 / §6.5 — Q-IM 의 UI 영구 금지선. FE 군이 사람-대상 화면을 호스트하는 이유
- [`03d-module-ido.md`](03d-module-ido.md) — onepass-fe 가 호출하는 IdO 엔드포인트의 BE 측 구현
- [`09-gap-and-roadmap.md`](09-gap-and-roadmap.md) **SEC-IDO-*** — Phase 2 (rename `BE_*` → `IDO_*`, **PR #203 완료**) + Phase 3 (`onepass-admin` 준비 체크리스트) 백로그
- [`idem-console/DEVELOPMENT.md`](../../../idem-console/DEVELOPMENT.md) — 본 문서가 "데이터 흐름의 정본"이라면, DEVELOPMENT.md 는 "개발자 온보딩 & 운영 가이드"
- [`07-security.md`](07-security.md) — 전 모듈 보안 정책 (FE 위험 표면 참조)
- 코드 증빙: `idem-hub/.../ExtProxyController.java`, `idem-hub/.../FeSessionController.java`, `idem-console/frontend/.env` (`IDO_API_TARGET=onepass-ido-*`), `idem-console/frontend/webpack.config.js` (dev proxy `/api → IdO`), `idem-console/frontend/src/api/idoInstance.ts` (Phase 2 신설)

---

*최종 검토: 2026-06-02 / onepass-fe 코드 직접 분석 기반 (Step1 ~ Step8, api/, utils/, hooks/, AppRoutes/, providers/, store/)*
