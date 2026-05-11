# SMEP 통합플랫폼 풀스택 마이그레이션 분석 보고서

**문서 ID**: MIG-2026-002 (v2 — 로그인 흐름 정밀 재분석 반영)  
**작성일**: 2026-05-11  
**작성 목적**: SMEP(중소벤처기업 통합플랫폼) FE·BE 소스코드 정밀 분석 → integration-sso 기반 전향 마이그레이션 기술 근거 확보  
**분석 대상**: `smep-ufe-develop.zip` (FE React 소스), `smep-be` (BE Spring Boot 소스)  
**개정 이유**: v1 문서에서 `SSOLogin.jsx`를 메인 로그인 페이지로 오분류한 오류 전면 정정 (실제 메인 로그인은 `Login.jsx`)

---

## 목차

1. [시스템 개요](#1-시스템-개요)
2. [기술 스택](#2-기술-스택)
3. [프론트엔드 아키텍처 분석](#3-프론트엔드-아키텍처-분석)
   - 3.1 디렉터리 구조
   - 3.2 라우팅 구조 전체 지도
   - 3.3 인증 상태 관리 (useAuthStore)
   - 3.4 API 클라이언트 (apiClient.js)
4. [로그인 흐름 정밀 분석](#4-로그인-흐름-정밀-분석) ★ 핵심 재분석 섹션
   - 4.1 [메인 로그인] Login.jsx — `/service/login`
   - 4.2 [OnePass SSO 로그인] keycloakGetAuthCode.js + OnePassSsoCallback.jsx
   - 4.3 [통합로그인 팝업 Fallback] SSOLogin.jsx — `/service/SSO-login` ← 이전 오분류 항목
   - 4.4 로그인 흐름 비교표
5. [마이페이지 인증 의존 페이지 분석](#5-마이페이지-인증-의존-페이지-분석)
6. [헤더 & 세션 관리 분석](#6-헤더--세션-관리-분석)
7. [백엔드 인증 구현 분석](#7-백엔드-인증-구현-분석)
8. [보안 취약점 종합](#8-보안-취약점-종합)
9. [integration-sso 전향 적합성 평가](#9-integration-sso-전향-적합성-평가)
10. [마이그레이션 로드맵](#10-마이그레이션-로드맵)
11. [결론](#11-결론)

---

## 1. 시스템 개요

SMEP(중소벤처기업 통합플랫폼)은 정책금융, 사업 공고, 기업 회원 정보 등 중소벤처기업 지원 서비스를 제공하는 포털 시스템이다. 현재 운용 중인 시스템은 **개발·시연(develop) 단계**로, 프로덕션 배포를 위한 인증 인프라가 미완성 상태이다.

실제 서비스 URL인 `https://www.smes.go.kr/home-dev/service/login`의 화면은 **개인 회원 / 기업 회원 탭 + ID/PW 입력 폼** 구조이며, 이것이 `Login.jsx`에 해당한다.

```
현재 인증 구조 요약
─────────────────────────────────────────────────────────
[경로 1] /service/login          → Login.jsx        (메인 ID/PW 로그인)
[경로 2] 헤더 OnePass 버튼       → OnePass SSO       (외부 Keycloak 리다이렉트)
[경로 3] /service/SSO-login      → SSOLogin.jsx      (팝업 fallback — 데모 전용)
[경로 4] /sso                    → OnePassSsoCallback (Keycloak 콜백 처리)
─────────────────────────────────────────────────────────
```

---

## 2. 기술 스택

### 프론트엔드

| 항목 | 버전 | 비고 |
|------|------|------|
| React | 18.x | |
| Vite | 5.x | 빌드 도구, 개발 프록시 |
| Zustand | 4.x | 전역 상태 관리 |
| React Router | 7.x | 동적 + 정적 라우팅 |
| Axios | 최신 | apiClient.js 래퍼 |
| jose / jwt-decode | — | JWT 파싱 (클라이언트 사이드) |

### 백엔드

| 항목 | 버전 | 비고 |
|------|------|------|
| Spring Boot | 3.x | |
| Java | 21 | |
| MyBatis | XML Mapper | |
| PostgreSQL | — | `sc_mbrm` 스키마 |
| Spring Security | — | permitAll 전체 개방 |

---

## 3. 프론트엔드 아키텍처 분석

### 3.1 디렉터리 구조

```
src/
├── App.jsx                          # 최상위 — Provider 체인 + AppRouter
├── routes/
│   ├── index.jsx                    # AppRouter — 동적+정적 라우트 병합
│   ├── staticRoutes.jsx             # 정적 라우트 정의
│   ├── dynamicRoutes.jsx            # 메뉴 API 기반 동적 라우트
│   └── autoRoutes.jsx               # 퍼블리싱 자동 라우트
├── store/
│   ├── useAuthStore.jsx             # Zustand 인증 상태 (핵심)
│   └── useMenuStore.js              # 메뉴 트리 상태
├── lib/
│   ├── apiClient.js                 # Axios 래퍼 — Bearer 자동 주입
│   └── companyProfiles.js           # 8개 더미 기업 프로파일 (SSOLogin 전용)
├── utils/
│   └── keycloakGetAuthCode.js       # OnePass/Keycloak 인가 코드 요청
├── context/
│   └── AuthContext.jsx              # "로그인 시뮬레이션" 주석 포함
├── components/ui/
│   ├── Header.jsx                   # 세션 타이머, 로그인/로그아웃 버튼
│   └── header/HeaderUserMenu.jsx   # M&A URL 하드코딩
└── pages/
    ├── Login.jsx                    # ★ 실제 메인 로그인 페이지 (ID/PW 폼)
    ├── SSOLogin.jsx                 # 팝업 fallback 데모 페이지
    ├── onepass/
    │   ├── OnePassSsoCallback.jsx   # Keycloak 콜백 처리
    │   ├── OnePassSsoLogout.jsx     # 로그아웃 콜백
    │   └── OnepassLoginConversionModal.jsx
    └── my-business/
        ├── PasswordChange.jsx       # 비밀번호 변경 (POST /api/v1/account/password)
        ├── VerifyPassword.jsx       # 비밀번호 재확인 컴포넌트 (로그인 게이트)
        ├── UI_USR_R_480.jsx         # 마이페이지 대시보드 (이미지 렌더링만)
        └── member/
            ├── CompanyDetail.jsx    # 기업 기본/상세 정보 (GET /api/v1/member/corporate/me)
            ├── CompanyEdit.jsx      # 기업 정보 수정 (POST /api/v1/member/corporate/me)
            ├── ReassignOwner.jsx    # 소유자 재할당
            └── memberUtils.js      # API 호출 유틸리티
```

### 3.2 라우팅 구조 전체 지도

`staticRoutes.jsx` 기준 정적 라우트:

```javascript
// MenuProviderOnly 레이아웃 (메뉴 컨텍스트만)
{ path: '/' }                         → MainPage.jsx
{ path: '/service/ai-chat' }          → AiChat.jsx
{ path: '/service/SSO-login' }        → SSOLogin.jsx      ← 팝업 fallback (데모)
{ path: '/service/intg-search-route-test' } → IntegratedSearchRouteTest.jsx
{ path: '/sso' }                      → OnePassSsoCallback.jsx
{ path: '/sso-logout' }               → OnePassSsoLogout.jsx

// SubpageLayoutWithMenu 레이아웃 (헤더+사이드바+메뉴)
{ path: '/service/login' }            → Login.jsx          ← 실제 메인 로그인

// 퍼블리싱 라우트
{ path: 'publishing/*' }              → 자동 생성

// 404
{ path: '*' }                         → 404 페이지
```

**핵심 관찰**:
- `Login.jsx`는 `SubpageLayoutWithMenu` 레이아웃 아래 배치 → 헤더·사이드바 포함, 정식 페이지 구조
- `SSOLogin.jsx`는 `MenuProviderOnly` 레이아웃 아래 배치 → 팝업 전용 경량 구조
- **인증 가드(ProtectedRoute)가 없음** → 마이페이지 등 보호 경로에 비인증 사용자도 접근 가능

동적 라우트는 `useMenuStore`가 서버에서 메뉴 트리를 받아와 `generateDynamicRoutes()`로 생성된다. `currentMode`(개인/기업) 변경 시 `resetMenu()` 호출로 메뉴 트리 재로드.

### 3.3 인증 상태 관리 (useAuthStore)

```javascript
// src/store/useAuthStore.jsx
// Zustand + sessionStorage persist
const useAuthStore = create(
  persist({
    token: null,           // JWT 액세스 토큰
    refreshToken: null,    // 리프레시 토큰 (Header 세션 타이머용)
    user: null,            // 프로파일 객체 (loginId, name, etc.)
    currentMode: 'INDIVIDUAL' | 'CORPORATE',  // 현재 회원 유형
    currentCompany: null,  // 현재 선택 기업
    linkedCompanies: [],   // 연결 기업 목록
    companyProfile: null,  // 기업 프로파일

    login(payload)   // token + refreshToken + profile 저장
    logout()         // 상태 초기화 + BroadcastChannel 발송
    setMode(mode)    // 개인/기업 전환
  },
  { name: 'auth-storage', storage: sessionStorage })
);
```

**BroadcastChannel**: `auth_channel`로 탭 간 로그아웃 동기화. 한 탭에서 로그아웃하면 모든 탭이 동시에 로그아웃된다.

**토큰 기반 로그인 판단**: 시스템 전반에서 다음 패턴으로 로그인 여부를 확인한다:

```javascript
// 예: UI_USR_R_031.jsx (정책금융 상세)
const authToken = useAuthStore((state) => state.token);
const isLoggedIn = Boolean(authToken); // token이 있으면 로그인으로 간주
```

이 패턴은 BE가 더미 토큰을 반환하는 현재 구조에서도 `isLoggedIn = true`가 성립하기 때문에, **토큰의 진위와 무관하게 기능이 활성화**된다.

### 3.4 API 클라이언트 (apiClient.js)

```javascript
// src/lib/apiClient.js
import axios from 'axios';
const baseURL = import.meta.env.VITE_API_CONTEXT || '';

const instance = axios.create({ baseURL });

// 요청 인터셉터: useAuthStore.token → Authorization: Bearer {token}
instance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
```

- 모든 API 요청에 Bearer 토큰 자동 첨부
- 응답 인터셉터에서 401 처리 또는 자동 갱신 로직 **미구현**
- `VITE_API_CONTEXT` 환경변수로 프록시 경로 제어 (vite.config.js: `/api → http://localhost:8080`)

---

## 4. 로그인 흐름 정밀 분석

> **v1 → v2 핵심 수정 사항**  
> v1 문서는 `SSOLogin.jsx`를 "FE-1 핵심 시연용 증거 — 메인 로그인 드롭다운"으로 첫 번째 취약점으로 분류했다.  
> 이는 **오분류**다. `SSOLogin.jsx`는 `/service/SSO-login` 경로의 별도 팝업 fallback 페이지이며,  
> 실제 메인 로그인 페이지는 `/service/login`의 `Login.jsx`(ID/PW 폼)이다.

### 4.1 [메인 로그인] Login.jsx — `/service/login` ★ 실제 운용 중인 메인 로그인

**실제 서비스 URL**: `https://www.smes.go.kr/home-dev/service/login`  
**컴포넌트 내부 이름**: `UI_USR_R_002`

#### UI 구조

```
┌─────────────────────────────────────────────────────┐
│  로그인 방식을 선택해주세요.                          │
│  ┌──────────────┐  ┌──────────────┐                 │
│  │  개인 회원  │  │  기업 회원  │  ← 탭 버튼       │
│  └──────────────┘  └──────────────┘                 │
│  [아이디 입력란]                                     │
│  [비밀번호 입력란]           ← type="password"      │
│  ☐ 아이디 저장              ← 기능 미구현(체크만)   │
│  [로그인 버튼]                                       │
│  아이디 찾기 | 비밀번호 찾기 | 회원가입  ← 링크 #  │
└─────────────────────────────────────────────────────┘

주석처리된 미구현 항목:
- SNS 로그인 (구글, 카카오, 네이버)
- 기타 로그인 방법 (휴대폰 인증, 공동인증서, 간편인증, Any-ID)
```

#### 전체 로그인 흐름 (코드 레벨)

```
[사용자] ID 입력 + PW 입력 + [로그인] 클릭
         │
         ▼
handleClick() — Login.jsx:32
         │
         ▼
apiClient.post('/api/v1/auth/login', {
  id: loginId,
  password: password,
  type: loginType  // 'INDIVIDUAL' | 'CORPORATE'
})
         │
         ▼ (BE: AuthServiceImpl.login())
BE 응답: { accessToken: "dummy-access-token", refreshToken: "..." }
         │
         ▼
accessToken 추출 — Login.jsx:39
  const accessToken = response.accessToken || response.data?.accessToken;
  if (!accessToken) throw new Error('Access token is missing');
         │
         ▼
apiClient.get('/api/v1/account/me', { token: accessToken })
         │
         ▼ (BE: 더미 프로파일 반환)
         │
         ▼
useAuthStore.login({ token: accessToken, refreshToken, profile })
  → sessionStorage 'auth-storage'에 persist
  → BroadcastChannel 'auth_channel' 동기화
         │
         ▼
navigate('/') — 홈으로 이동
```

#### 입력 검증 (FE 수준)

```javascript
// 현재 Login.jsx의 FE 입력 검증: 없음
// handleClick() 호출 → 빈 값이어도 즉시 POST 요청 발생
// BE에서 더미 토큰을 무조건 반환하므로 어떤 값을 입력해도 로그인 성공
```

**시연용 취약점 FE-1 (정정)**: 메인 로그인의 시연용 증거는 FE의 드롭다운이 아닌, **BE `AuthServiceImpl`의 더미 토큰 무조건 반환**이다. FE의 ID/PW 폼 자체 구조는 정상적인 프로덕션 UI 패턴을 따른다.

#### Enter 키 제출 처리

```javascript
// Login.jsx:56 — Enter 키 로그인 지원 (한글 조합 중 Enter 방지 포함)
const handleEnterSubmit = (event) => {
  if (event.key !== 'Enter' || event.nativeEvent?.isComposing) return;
  event.preventDefault();
  handleClick();
};
```

#### 초기 loginType 결정

```javascript
// Login.jsx:13 — 라우터 state로 로그인 타입 전달 가능
const [loginType, setLoginType] = useState(
  location.state?.loginType || LOGIN_TYPE_INDIVIDUAL,
);
// → 기업회원 로그인 탭을 기본으로 열어 진입시킬 수 있음 (navigate('/service/login', { state: { loginType: 'CORPORATE' } }))
```

#### 탭 전환 동작

```javascript
// Login.jsx:24 — 기업 회원 탭 전환 시 PW 초기화, INDIVIDUAL 전환 시 ID만 초기화
const handleLoginTypeChange = (nextType) => {
  setLoginType(nextType);
  setLoginId('');
  if (nextType === LOGIN_TYPE_CORPORATE) {
    setPassword('');
  }
};
```

---

### 4.2 [OnePass SSO 로그인] keycloakGetAuthCode.js + OnePassSsoCallback.jsx

헤더의 **"중기원패스 통합 로그인"** 버튼(`handleOnePassIntegratedLogin`)이 트리거하는 외부 SSO 흐름이다.

#### 인가 코드 요청 — keycloakGetAuthCode.js

```javascript
// src/utils/keycloakGetAuthCode.js
export const onePassGetAuthCode = () => {
  const KEYCLOAK_SERVER    = 'https://keycloak.server.url/...';
  const KEYCLOAK_CLIENT_ID = 'smep-client';
  const REDIRECT_SSO_URI   = 'https://www.smes.go.kr/home-dev/sso'; // ← 하드코딩

  // ⚠️ CSRF 방어 state 파라미터 비활성화 (주석처리)
  // const state = crypto.randomUUID();
  // sessionStorage.setItem('keycloak_state', state);

  const params = new URLSearchParams({
    client_id: KEYCLOAK_CLIENT_ID,
    redirect_uri: REDIRECT_SSO_URI,
    response_type: 'code',
    scope: 'openid',
    // state: state  ← 비활성화
  });
  window.location.href = `${KEYCLOAK_SERVER}/auth?${params}`;
};
```

**보안 취약점 SEC-1**: `state` 파라미터 비활성화 → **CSRF 취약** (OAuth 2.0 RFC 6749 §10.12 위반)

#### 콜백 처리 — OnePassSsoCallback.jsx `/sso`

```javascript
// 공통: state 검증 전체 주석처리
// stateValidationBypassed: true

// Case 1 — 비로그인 상태에서 SSO 진입
apiClient.post('/api/v1/auth/keycloak/callback/local-login', {
  code: queryParams.get('code'),
  redirectUri: REDIRECT_SSO_URI,
})
→ 실 토큰 발급 → useAuthStore.login() → navigate('/')

// Case 2 — 이미 로그인 상태에서 SSO 진입 (기업 연동)
apiClient.post('/api/v1/auth/keycloak/callback', {
  code: queryParams.get('code'),
  state: queryParams.get('state'),
})
→ navigate('/')
```

**보안 취약점 SEC-2**: `/sso` 콜백에서 state 파라미터 검증 전체 주석처리

---

### 4.3 [통합로그인 팝업 Fallback] SSOLogin.jsx — `/service/SSO-login`

> ⚠️ **v1 오분류 정정**: 이 페이지는 메인 로그인이 아닌 **팝업 fallback 전용 데모 페이지**이다.

#### 호출 경로

`SSOLogin.jsx`는 직접 탐색되지 않는다. 헤더의 `handleIntegratedLogin()` → `GET /api/v1/auth/login-url` API 실패 시 fallback으로 팝업을 여는 경로에서만 호출된다:

```javascript
// Header.jsx — handleIntegratedLogin()
const openFallback = (url) => window.open(url, '_blank', 'width=600,height=400');

try {
  const response = await apiClient.get('/api/v1/auth/login-url');
  const loginUrl = response.data?.loginUrl;
  if (loginUrl) {
    window.location.href = loginUrl;   // 정상: 외부 SSO URL로 이동
  } else {
    openFallback(`${basePath}service/SSO-login`); // fallback: 팝업
  }
} catch {
  openFallback(`${basePath}service/SSO-login`);   // 오류 시 fallback: 팝업
}
```

`GET /api/v1/auth/login-url`은 BE `MockAuthController`(`@Profile("local")`로 로컬 전용)가 응답한다. 즉, **로컬 개발 환경에서만 이 팝업이 동작**하는 구조이며, 프로덕션 환경에서는 외부 SSO URL로 리다이렉트된다.

#### SSOLogin.jsx 내부 구조

```javascript
// src/pages/SSOLogin.jsx — UI_USR_R_002 (Login.jsx와 동일 컴포넌트 이름 충돌!)
import { getCompanyProfileByBizNo } from '../lib/companyProfiles.js';

// UI: 드롭다운으로 회사 선택 (하드코딩된 01~08 옵션)
// 선택 → brno 매핑 → getCompanyProfileByBizNo(brno) → 더미 프로파일
// → useAuthStore.login(더미 데이터) → navigate('/') via window.opener.postMessage
```

```javascript
// companyProfiles.js — SSOLogin.jsx 전용 더미 데이터
const companyProfiles = [
  { brno: '0000000001', companyName: '테크스타트', ... },
  { brno: '0000000002', companyName: '그린에너지', ... },
  // ... 총 8개 (brno 0000000001 ~ 0000000008 + 2개 실제 번호 포함)
];
```

**FE 시연용 특징 정정**:
- `SSOLogin.jsx`는 "외부 SSO 연동이 없는 환경에서 드롭다운으로 기업을 선택해 로그인 상태를 시뮬레이션하는 개발용 fallback"
- 실제 프로덕션 흐름과 무관 → **개발 편의 도구로 재분류**
- 메인 로그인의 시연용 증거는 **BE의 더미 토큰 반환** (`AuthServiceImpl`)

---

### 4.4 로그인 흐름 비교표

| 항목 | Login.jsx | OnePass SSO | SSOLogin.jsx |
|------|-----------|-------------|--------------|
| 경로 | `/service/login` | 헤더 버튼 → 외부 URL | `/service/SSO-login` |
| 트리거 | 직접 URL 진입 | `handleOnePassIntegratedLogin()` | `handleIntegratedLogin()` fallback |
| UI | ID/PW 폼 | 외부 Keycloak 화면 | 기업 드롭다운 |
| 인증 방식 | POST `/api/v1/auth/login` | Keycloak 인가 코드 흐름 | 더미 데이터 직접 주입 |
| BE 응답 | 더미 토큰 (AuthServiceImpl) | 실 토큰 (keycloak callback) | 없음 (FE 로컬) |
| 사용 환경 | 운용 중 | OnePass 연동 완료 시 | 로컬 개발 전용 |
| 프로덕션 필요성 | ✅ 핵심 (또는 SSO 대체) | ✅ 목표 | ❌ 개발 도구 |

---

## 5. 마이페이지 인증 의존 페이지 분석

### 5.1 VerifyPassword.jsx — 비밀번호 재확인 컴포넌트

```javascript
// src/pages/my-business/VerifyPassword.jsx
// 역할: 보호 페이지 접근 전 비밀번호 재확인 게이트 (컴포넌트 래퍼 방식)

const authUser = useAuthStore((state) => state.user);
const authToken = useAuthStore((state) => state.token);
const tokenPayload = decodeJwtPayload(authToken); // JWT 클라이언트 파싱
const tokenLoginId = tokenPayload?.login_id;      // 토큰에서 loginId 추출

// 기본값 결정 우선순위: JWT payload → authUser.loginId → authUser.username
const defaultLoginId = tokenLoginId || authUser?.loginId || authUser?.username || '';
```

**검증 흐름**:
```
비밀번호 입력 → POST /api/v1/account/password/verify → normalizeVerifyResult(result.data)
                                                          ↓ true
                                               sessionStorage에 검증 상태 저장
                                               (키: verify-password:{menuId}:{successPath})
                                                          ↓
                                               isVerified = true → children 렌더링
```

**보안 특징**:
- 세션스토리지 기반 검증 상태 → 탭 닫기 시 소멸 (합리적)
- 경로 이탈 시 자동 상태 초기화 (`cleanup useEffect`)
- loginId 필드는 `disabled` 처리 — 사용자가 변경 불가
- **문제**: `decodeJwtPayload`가 서명 검증 없이 클라이언트에서 실행됨. BE 더미 토큰은 유효한 JWT가 아닐 수 있어 `tokenLoginId`가 null이 될 가능성

**결론**: VerifyPassword 자체 로직은 프로덕션 수준으로 완성도 높음. BE 더미 토큰 교체 후 정상 동작 예상.

### 5.2 PasswordChange.jsx — 비밀번호 변경

**컴포넌트 이름**: `UI_USR_R_420`  
**경로**: `/my-business/password` (동적 라우트 추정)

```javascript
const logout = useAuthStore((state) => state.logout);

// 비밀번호 변경 후 자동 로그아웃 → /service/login 이동
await apiClient.post('/api/v1/account/password', {
  currentPassword,
  newPassword,
});
alert('비밀번호가 변경되었습니다. 다시 로그인해 주세요.');
logout();
navigate('/service/login');
```

**입력 검증 (FE 레벨 완성)**:
```
- 현재/새/확인 비밀번호 필수 입력
- 새 PW ≠ 현재 PW
- 영문/숫자/특수문자 중 2가지 이상 조합 (8~20자)
- 허용 특수문자: !@#$%^&*()=_+-
- 새 PW = 확인 PW 일치 확인
```

**에러 메시지 처리**:
```javascript
const resolvePasswordChangeErrorMessage = (error) =>
  error?.data?.message ||
  error?.data?.error?.message ||
  error?.message ||
  '비밀번호 변경에 실패했습니다.';
```

**결론**: FE 검증 로직 완성. 비밀번호 변경 성공 후 즉시 로그아웃 처리는 보안 관점에서 올바른 패턴.

### 5.3 CompanyDetail.jsx — 기업 기본/상세정보 (UI_USR_R_450)

```javascript
// src/pages/my-business/member/CompanyDetail.jsx

// 코드 레벨 주석: "로그인/store 정리 전까지 기업정보 화면은 전달된 회원번호가 없으면
//                  임시 폴백 회원번호로 진입을 보장한다."
const UI_USR_R_450 = () => {
  // useAuthStore 미사용 — 직접 token 참조 없음
  // API 호출: fetchCorporateMemberDetail(apiClient) → GET /api/v1/member/corporate/me
  // memberUtils.js의 fetchCorporateMemberDetail이 apiClient 사용
  // → apiClient는 자동으로 Bearer 토큰 첨부
```

**API 호출 목록** (memberUtils.js):

| 함수 | API | HTTP | 용도 |
|------|-----|------|------|
| `fetchCorporateMemberDetail` | `/api/v1/member/corporate/me` | GET | 기업 기본정보 |
| `fetchKsicTopLevelOptions` | `/api/v1/ksic/top-level` | GET | 산업 분류 코드 |
| `fetchCorporateMemberCodeOptions` | 공통 코드 API | GET | 기업 규모/근로자/매출 코드 |
| `updateCorporateMemberDetail` | `/api/v1/member/corporate/me` | POST | 기업 상세정보 수정 |
| `updateCorporateMemberInfo` | `/api/v1/member/corporate/me/member-info` | POST | 회원 정보+동의 저장 |
| `fetchIndividualMemberDetail` | `/api/v1/member/individual/me` | GET | 개인 기본정보 |
| `updateIndividualMemberInfo` | `/api/v1/member/individual/me/member-info` | POST | 개인 정보+동의 저장 |
| `resolveCorporateMemberNo` | `/api/v1/member/corporate/resolve/{brno}` | GET | 사업자등록번호로 회원번호 조회 |
| `fetchKedCorpInfo` | `/api/v1/member/corporate/me/ked-info` | GET | KED 기업정보 |
| `fetchMemberInfoReceptionAgreements` | `/api/v1/member/common/me/info-reception-agreements` | GET | 정보수신 동의 |
| `fetchCorporateManagerContact` | `/api/v1/member/corporate/me/contacts/manager` | GET | 담당자 연락처 |

**문제점**: `ProtectedRoute` 미적용으로 비인증 사용자도 이 경로로 직접 접근하면 API 오류(401 또는 빈 응답)만 발생할 뿐 로그인 페이지로 리다이렉트되지 않는다.

### 5.4 UI_USR_R_480.jsx — 마이페이지 대시보드

```javascript
// src/pages/my-business/UI_USR_R_480.jsx
// 실제 내용: 이미지 한 장 렌더링만 (캡처 이미지 정적 표시)
import captureImg from '@styles/img/capture1.png';
return <img src={captureImg} alt="대시보드" />;
```

**결론**: UI 퍼블리싱 단계 코드. 인증 로직 없음. 추후 실제 대시보드 컴포넌트로 교체 필요.

---

## 6. 헤더 & 세션 관리 분석

### 6.1 세션 타이머 (Header.jsx)

```javascript
// 1초 폴링으로 JWT exp 체크
useEffect(() => {
  const timer = setInterval(() => {
    const { token, refreshToken } = useAuthStore.getState();
    if (!token || !refreshToken) return;

    const payload = parseJwt(token);       // 클라이언트 사이드 JWT 파싱 (서명 검증 없음)
    const exp = payload?.exp;
    if (!exp) return;

    const remaining = exp * 1000 - Date.now();
    if (remaining <= 0) {
      handleLogout(); // 만료 시 로그아웃
    }
    // TODO: remaining <= 5분 시 갱신 요청 로직 미구현
  }, 1000);
  return () => clearInterval(timer);
}, []);
```

**취약점 SEC-3**: 클라이언트 JWT 파싱으로 세션 만료 처리. 서버 측 토큰 유효성 검증 없음. 더미 토큰 사용 시 `exp`가 없거나 임의 값이므로 타이머가 정상 작동하지 않는다.

### 6.2 로그아웃 흐름 (Header.jsx)

```javascript
// handleLogout()
await apiClient.post('/api/v1/auth/keycloak/logout');  // BE: 세션 무효화 시도
const logoutUrl = response?.data?.logoutUrl;
useAuthStore.getState().logout();                       // FE 상태 초기화
if (logoutUrl) window.location.href = logoutUrl;       // Keycloak 로그아웃 URL 이동
```

### 6.3 handleIntegratedLogin() vs handleOnePassIntegratedLogin()

```
handleIntegratedLogin():
  → GET /api/v1/auth/login-url (MockAuthController, @Profile local)
  → 성공: window.location.href = loginUrl (외부 SSO)
  → 실패/없음: window.open('.../service/SSO-login', 팝업) ← SSOLogin.jsx 호출

handleOnePassIntegratedLogin():
  → onePassGetAuthCode() 직접 호출 (Keycloak으로 리다이렉트)
  → CSRF state 비활성화 상태
```

---

## 7. 백엔드 인증 구현 분석

### 7.1 SecurityConfig — 전체 경로 개방

```java
// SecurityConfig.java
http.authorizeHttpRequests()
    .anyRequest().permitAll()  // 모든 경로 인증 없이 허용
    .and().csrf().disable();
```

**취약점 SEC-4**: Spring Security가 실질적으로 비활성화 상태. JWT 토큰이 없어도, 만료되어도, 위조되어도 모든 API 접근 가능.

### 7.2 AuthServiceImpl — 더미 토큰 반환

```java
// AuthServiceImpl.java
@Override
public LoginResponse login(LoginRequest request) {
    // TODO: 실제 사용자 인증 로직 구현 예정
    return LoginResponse.builder()
        .accessToken("dummy-access-token")   // ← 고정 더미 토큰
        .refreshToken("dummy-refresh-token")
        .build();
    // 실제 DB 조회, 비밀번호 검증, JWT 생성 — 없음
}
```

**시연용 취약점 BE-1** (FE-1 정정 후): 어떤 ID/PW 조합으로도 로그인 성공. BE가 메인 시연용 증거.

### 7.3 account/me API — 더미 프로파일

```java
// AccountController.java — 추정
@GetMapping("/api/v1/account/me")
public AccountProfile getMe() {
    return AccountProfile.builder()
        .loginId("demo-user")
        .name("데모 사용자")
        // ... 더미 데이터
        .build();
}
```

### 7.4 PasswordChange API — 더미 처리 추정

```java
// POST /api/v1/account/password
// 현재: 더미 성공 응답 반환 (실제 DB 변경 없음)
// POST /api/v1/account/password/verify  
// 현재: 어떤 비밀번호든 true 반환 가능성 높음
```

### 7.5 MockAuthController — 로컬 개발 전용

```java
// @Profile("local") — 로컬 환경에서만 활성화
@GetMapping("/api/v1/auth/login-url")
public ResponseEntity<Map<String, String>> getLoginUrl() {
    return ResponseEntity.ok(Map.of("loginUrl", "http://localhost:..."));
    // 반환된 loginUrl이 null이거나 응답 실패 시 → FE에서 SSOLogin.jsx 팝업 fallback
}
```

---

## 8. 보안 취약점 종합

### 취약점 목록 (v2 정정)

| ID | 위치 | 설명 | 위험도 | 분류 |
|----|------|------|--------|------|
| BE-1 | AuthServiceImpl | 더미 토큰 무조건 반환 — 모든 ID/PW 로그인 성공 | 🔴 Critical | 미구현 |
| BE-2 | SecurityConfig | anyRequest().permitAll() — Spring Security 비활성화 | 🔴 Critical | 미구현 |
| BE-3 | AccountController | 더미 프로파일 반환 — 실 사용자 데이터 없음 | 🔴 Critical | 미구현 |
| SEC-1 | keycloakGetAuthCode.js | OAuth state 파라미터 비활성화 (CSRF 취약) | 🔴 Critical | 보안 결함 |
| SEC-2 | OnePassSsoCallback.jsx | state 검증 전체 주석처리 | 🔴 Critical | 보안 결함 |
| SEC-3 | Header.jsx | 클라이언트 사이드 JWT 파싱으로 세션 만료 처리 | 🟡 Medium | 설계 결함 |
| FE-1 *(정정)* | Login.jsx | FE 입력 검증 없음 — 빈 ID/PW도 API 호출됨 | 🟡 Medium | 미완성 |
| FE-2 | staticRoutes.jsx | ProtectedRoute 없음 — 마이페이지 비인증 접근 가능 | 🔴 High | 미구현 |
| FE-3 | App.jsx | AI API Key 2개 소스코드 하드코딩 | 🟡 Medium | 보안 결함 |
| FE-4 | HeaderUserMenu.jsx | M&A URL 하드코딩 | 🟢 Low | 운영 문제 |
| FE-5 | AuthContext.jsx | "로그인 시뮬레이션" 주석 — 개발 의도 노출 | 🟢 Low | 정보 노출 |
| FE-6 | VerifyPassword.jsx | decodeJwtPayload 서명 검증 없음 | 🟡 Medium | 설계 결함 |

> **v1 오류 수정**:  
> v1의 "FE-1: SSOLogin.jsx 드롭다운 메인 로그인" 항목은 삭제.  
> SSOLogin.jsx는 로컬 개발용 팝업 fallback이며 프로덕션 보안 취약점이 아님.  
> 실제 FE-1은 "Login.jsx FE 입력 검증 없음"으로 재분류.

### 8.1 취약점 근거 코드 — 핵심 2개

#### BE-1: 더미 토큰 (AuthServiceImpl)
```java
return LoginResponse.builder()
    .accessToken("dummy-access-token")  // 어떤 입력에도 동일 토큰 반환
    .refreshToken("dummy-refresh-token")
    .build();
```

#### FE-2: ProtectedRoute 없음 (staticRoutes.jsx)
```javascript
// 마이페이지 경로에 인증 게이트 없음
// 비인증 사용자가 /my-business/password 에 직접 접근하면:
// - FE: VerifyPassword 컴포넌트가 렌더링됨 (loginId가 빈 값)
// - 빈 ID로 POST /api/v1/account/password/verify 가능
```

---

## 9. integration-sso 전향 적합성 평가

### 9.1 integration-sso 아키텍처 요약

```
integration-sso v2.3.0 / Sprint 10
─────────────────────────────────────────────────────────
모듈:
  q-sign   (8081) — 전자서명 / 공개키 관리
  q-im     (8082) — 아이덴티티 관리
  ido      (8083) — IdO 중재자 (ADR-001: 완전 중재 패턴)
  agency-stub (8084) — 기관 스텁

핵심 패턴:
  ADR-001: IdO 완전 중재 — 모든 인증 요청이 ido를 통과
  Handoff Ticket: AES-256-GCM + HMAC-SHA256 서명
  397개 테스트 통과
```

### 9.2 SMEP → integration-sso 매핑

| SMEP 현재 | integration-sso 대응 | 전환 복잡도 |
|-----------|---------------------|------------|
| `AuthServiceImpl` 더미 토큰 | `ido` 실 JWT 발급 | 🟡 중 |
| `SecurityConfig.permitAll()` | Spring Security + JWT 필터 | 🟡 중 |
| `keycloakGetAuthCode.js` state 비활성화 | state 파라미터 재활성화 | 🟢 저 |
| `OnePassSsoCallback.jsx` state 검증 bypass | 검증 로직 주석 해제 | 🟢 저 |
| `ProtectedRoute` 없음 | 인증 가드 추가 | 🟢 저 |
| `Header.jsx` 클라이언트 JWT 파싱 | `/api/v1/auth/validate` 서버 검증 | 🟡 중 |
| `PasswordChange` 더미 BE | 실 DB 비밀번호 변경 | 🔴 고 |
| `companyProfiles.js` 더미 데이터 | DB 기업 연동 쿼리 | 🔴 고 |

### 9.3 FE 전향 시 보존 가능 코드

```
✅ 재사용 가능 (구조 정상):
  - Login.jsx UI 구조 (ID/PW 폼, 탭 전환, Enter 처리)
  - useAuthStore.jsx (BroadcastChannel 포함)
  - apiClient.js (Bearer 자동 주입)
  - VerifyPassword.jsx (검증 로직 완성)
  - PasswordChange.jsx (FE 검증 완성)
  - CompanyDetail.jsx / memberUtils.js (API 구조 정상)
  - OnePassSsoCallback.jsx (state 검증 주석만 해제하면 됨)
  - keycloakGetAuthCode.js (state 재활성화만 필요)

❌ 교체/삭제 필요:
  - AuthServiceImpl 더미 토큰 로직
  - SecurityConfig.permitAll()
  - companyProfiles.js 더미 데이터 (SSOLogin.jsx와 함께)
  - App.jsx 하드코딩 API Key (환경변수 이동)
  - Header.jsx 세션 타이머 클라이언트 파싱 로직
```

### 9.4 마이그레이션 우선순위 판단

```
1순위 (보안): BE-1, BE-2 — 더미 토큰·permitAll 제거 → integration-sso ido 실 토큰
2순위 (보안): SEC-1, SEC-2 — CSRF state 재활성화 (코드 주석 해제만으로 가능)
3순위 (기능): FE-2 — ProtectedRoute 구현 (useAuthStore.token 기반)
4순위 (기능): FE-1 — Login.jsx 입력 검증 추가
5순위 (인프라): BE-3 — account/me 실 DB 연동
```

---

## 10. 마이그레이션 로드맵

### Phase 1 — 인증 핵심 교체 (4주)

```
Sprint 1: BE 인증 교체
  - AuthServiceImpl: 더미 → integration-sso ido JWT 발급
  - SecurityConfig: permitAll() → JWT 필터 체인
  - account/me: 더미 → DB 실 프로파일 조회

Sprint 2: FE CSRF 수정 + ProtectedRoute
  - keycloakGetAuthCode.js: state 파라미터 재활성화 (주석 해제)
  - OnePassSsoCallback.jsx: state 검증 주석 해제
  - staticRoutes.jsx: ProtectedRoute 컴포넌트 추가
  - Login.jsx: 빈 값 검증 추가
```

### Phase 2 — 기능 완성 (4주)

```
Sprint 3: 비밀번호 관리 BE 연동
  - POST /api/v1/account/password: 실 DB 변경
  - POST /api/v1/account/password/verify: 실 DB 검증

Sprint 4: 회원 정보 BE 연동
  - GET/POST /api/v1/member/corporate/me: DB 연동
  - GET/POST /api/v1/member/individual/me: DB 연동
  - 공통 코드 API 연동
```

### Phase 3 — 보안 강화 (2주)

```
Sprint 5: 보안 강화
  - App.jsx API Key 환경변수 이동
  - Header.jsx 세션 타이머 서버 검증 방식 전환
  - 401 응답 자동 로그인 리다이렉트 구현 (apiClient.js 인터셉터)
  - SSOLogin.jsx + companyProfiles.js 제거 (프로덕션 빌드)
```

---

## 11. 결론

SMEP 통합플랫폼의 프론트엔드 인증 구조는 **올바른 설계 패턴을 따르고 있으나 BE가 미완성**이다.

### 핵심 발견 사항 (v2 정정 포함)

1. **실제 메인 로그인**: `Login.jsx` (`/service/login`) — ID/PW 탭 폼, 정상적인 프로덕션 UI 구조
2. **`SSOLogin.jsx`는 개발용 팝업 fallback**: `/service/SSO-login` 경로, 로컬 개발 환경에서만 `handleIntegratedLogin()` fallback으로 동작. 프로덕션 취약점이 아닌 개발 편의 도구.
3. **메인 취약점은 BE**: `AuthServiceImpl` 더미 토큰 + `SecurityConfig.permitAll()` — FE ID/PW 폼이 아무 값이나 받아도 BE가 무조건 성공 응답
4. **FE 재사용률 높음**: Login.jsx, useAuthStore, VerifyPassword, PasswordChange 등 핵심 FE 코드는 BE 교체 후 그대로 활용 가능
5. **CSRF 수정은 주석 해제 수준**: keycloakGetAuthCode.js와 OnePassSsoCallback.jsx의 state 처리는 코드가 이미 작성되어 있고 주석처리만 된 상태 → 즉시 복원 가능
6. **ProtectedRoute 부재**: 마이페이지 전체 경로가 인증 없이 접근 가능 — 추가 구현 필요

integration-sso의 ADR-001 IdO 완전 중재 패턴은 SMEP의 이중 인증 구조(로컬 ID/PW + OnePass SSO)를 통합 처리하기에 적합하다. FE 재작업 최소화(CSRF 주석 해제, ProtectedRoute 추가, 입력 검증 보완)와 BE 전면 교체(더미 → 실 인증)를 병행하는 전략이 최적이다.

---

*문서 ID: MIG-2026-002 v2 | 분석 기준일: 2026-05-11 | 다음 리뷰: Phase 1 Sprint 1 완료 후*
