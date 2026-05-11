# SMEP 통합플랫폼 풀스택 심층 분석 및 integration-sso 전향 가이드

**문서번호**: MIG-2026-002  
**작성일**: 2026-05-11  
**버전**: v1.0.0  
**분류**: 기술 분석 / 내부 기밀  
**대상**: 개발팀 / 기술 아키텍트 / 프로젝트 관리자

---

## 목차

1. [분석 요약 (Executive Summary)](#1-분석-요약)
2. [분석 대상 소스 구성](#2-분석-대상-소스-구성)
3. [프론트엔드(FE) 인증 구조 완전 해부](#3-프론트엔드fe-인증-구조-완전-해부)
4. [백엔드(BE) 인증 구조 완전 해부](#4-백엔드be-인증-구조-완전-해부)
5. [FE ↔ BE 연동 흐름 전체 도식화](#5-fe--be-연동-흐름-전체-도식화)
6. [시연용 코드 완전 목록 (FE + BE 합산)](#6-시연용-코드-완전-목록-fe--be-합산)
7. [현재 상태의 운영 불가 근거](#7-현재-상태의-운영-불가-근거)
8. [integration-sso 전향 후 완성 상태 명세](#8-integration-sso-전향-후-완성-상태-명세)
9. [FE 변경 작업 항목 상세화](#9-fe-변경-작업-항목-상세화)
10. [BE 변경 작업 항목 상세화](#10-be-변경-작업-항목-상세화)
11. [전향 후 FE ↔ BE 연동 흐름 명세](#11-전향-후-fe--be-연동-흐름-명세)
12. [위험 관리 및 전환 로드맵](#12-위험-관리-및-전환-로드맵)

---

## 1. 분석 요약

### 핵심 결론

SMEP 통합플랫폼(중소벤처24)의 현재 소스코드(BE: Spring Boot 3.x / FE: React + Vite)는 **시연 전용 구현 상태**로, 운영 배포가 불가능하다. FE와 BE 양단에 걸쳐 총 **12개의 시연용 특이점**이 코드에 명시적으로 박혀 있으며, 이 중 **8개는 보안 취약점**에 해당한다.

**현재 상태 요약:**

| 계층 | 시연용 특이점 수 | 보안 취약점 수 | 운영 가능 여부 |
|------|--------------|--------------|------------|
| Frontend (React) | 7개 | 5개 | ❌ 불가 |
| Backend (Spring Boot) | 5개 | 3개 | ❌ 불가 |
| **합계** | **12개** | **8개** | ❌ **불가** |

**integration-sso 전향 결론:**  
OnePass(중기원패스) 플랫폼인 `integration-sso` v2.3.0은 현재 SMEP이 정식 구현해야 할 인증 연동 규격을 **완성된 형태**로 보유하고 있다. SMEP을 integration-sso 규격에 맞게 전환함으로써 보안성, 확장성, 운영 안정성을 모두 확보할 수 있다.

---

## 2. 분석 대상 소스 구성

### 2.1 SMEP 백엔드 (`smep-be-develop.zip`)

```
smep-be/
├── src/main/java/kr/go/smes/
│   ├── account/                    # 인증 핵심 모듈
│   │   ├── api/                    # REST 컨트롤러 (5개)
│   │   │   ├── KeycloakController.java      # OnePass/Keycloak 연동 엔드포인트
│   │   │   ├── SsoAuthController.java       # @Profile("local") 전용
│   │   │   ├── MockAuthController.java      # @Profile("local") 전용
│   │   │   ├── OidcController.java          # /api/me 사용자 정보
│   │   │   └── AuthController.java         # /api/v1/auth/login (더미 토큰)
│   │   ├── config/
│   │   │   └── SecurityConfig.java          # Spring Security 설정
│   │   ├── jwt/                    # JWT 계층
│   │   │   ├── AccountJwtProvider.java      # 3가지 토큰 발급
│   │   │   ├── JwtAuthenticationFilter.java # JWT 검증 필터
│   │   │   └── AccountTokenContext.java     # 개인/기업 컨텍스트
│   │   └── service/                # 서비스 계층 (9개)
│   │       ├── impl/AuthServiceImpl.java    # 더미 토큰 반환 ← 핵심 시연용
│   │       ├── KeycloakTokenService.java    # code→token 교환
│   │       ├── KeycloakLocalLoginService.java # UUID→sc_mbrm 브리지
│   │       ├── KeycloakAccessTokenClaimExtractor.java # 서명검증 없음
│   │       └── SsoStateStore.java           # local- 바이패스 존재
│   └── qim/                        # Q-IM inbound 모듈
│       ├── api/QimMemberController.java
│       └── service/
│           ├── QimIdentityDecoder.java      # AES-256-GCM 미구현
│           ├── QimMemberQueryService.java
│           ├── QimMemberRegisterService.java
│           └── QimMemberWithdrawService.java
└── src/main/resources/
    ├── application.yml              # jwt.secret 환경변수 필수
    ├── application-dev.yml          # dev SSO/Q-IM 설정
    └── mappers/                     # MyBatis XML (DEMO TEMP SQL 포함)
```

**분석된 Java 파일**: 31개  
**분석된 설정/SQL 파일**: 6개  
**총 분석 파일**: 37개 (전체 650개 중 인증 관련 핵심 파일 선별)

### 2.2 SMEP 프론트엔드 (`smep-ufe-develop.zip`)

```
smep-ufe/
├── src/
│   ├── App.jsx                     # 앱 루트 (AI 환경 설정 하드코딩)
│   ├── main.jsx                    # 앱 진입점
│   ├── context/
│   │   └── AuthContext.jsx         # "로그인 시뮬레이션" 주석
│   ├── store/
│   │   └── useAuthStore.jsx        # Zustand + sessionStorage persist
│   ├── lib/
│   │   ├── apiClient.js            # VITE_API_CONTEXT 기반 API 클라이언트
│   │   └── companyProfiles.js      # 8개 회사 하드코딩 데이터 ← 핵심 시연용
│   ├── utils/
│   │   └── keycloakGetAuthCode.js  # CSRF state 비활성화 ← 핵심 시연용
│   ├── pages/
│   │   ├── Login.jsx               # ID/PW 로그인 (BE 더미 토큰에 연결)
│   │   └── SSOLogin.jsx            # 하드코딩 드롭다운 로그인 ← 핵심 시연용
│   │   └── onepass/
│   │       ├── OnePassSsoCallback.jsx       # state 검증 비활성화
│   │       ├── OnePassSsoLogout.jsx         # 로그아웃 콜백 (정상)
│   │       └── OnepassLoginConversionModal.jsx # 전환 모달 (정상)
│   ├── components/ui/
│   │   ├── Header.jsx              # 세션 타이머, OnePass/로컬 로그인 버튼
│   │   └── header/
│   │       └── HeaderUserMenu.jsx  # 사용자 메뉴 (하드코딩 외부 URL 포함)
│   ├── hooks/
│   │   └── usePopupCommunication.js # AI 채팅 팝업 통신 (인증과 무관)
│   └── routes/
│       ├── index.jsx               # AppRouter (메뉴 기반 동적 라우팅)
│       └── staticRoutes.jsx        # 정적 라우트 정의
├── vite.config.js                  # Vite 프록시 설정 (localhost:8081)
└── package.json                    # React 18 / Zustand / Axios 등
```

**분석된 FE 파일**: 18개 (전체 인증 관련 핵심 파일 전수 분석 완료)

---

## 3. 프론트엔드(FE) 인증 구조 완전 해부

### 3.1 인증 상태 관리 — Zustand Store

**파일**: `src/store/useAuthStore.jsx`

```javascript
// 핵심 상태 구조
const useAuthStore = create(
  persist(
    (set) => ({
      isLogin: false,
      token: null,           // SMEP 자체 JWT (BE에서 발급)
      refreshToken: null,    // 갱신 토큰
      user: null,            // 개인 회원 정보
      currentMode: null,     // 'INDIVIDUAL' | 'CORPORATE'
      currentCompany: null,  // 현재 기업 컨텍스트
      linkedCompanies: [],   // 연결된 기업 목록
      contextRole: null,     // 역할
      companyProfile: null,  // AI 채팅에 사용하는 기업 프로파일
      // ...
    }),
    {
      name: 'auth-storage',
      storage: createJSONStorage(() => sessionStorage), // ← sessionStorage 사용
    }
  )
);

// 탭 간 로그아웃 동기화
const channel = new BroadcastChannel('auth_channel');
channel.onmessage = (event) => {
  if (event.data.type === 'LOGOUT') {
    useAuthStore.getState().logout();
  }
};
```

**평가**:
- sessionStorage를 사용하므로 브라우저 탭이 닫히면 토큰이 소멸함 (보안 측면 적절)
- BroadcastChannel을 통한 탭 간 로그아웃 동기화는 올바른 구현
- `companyProfile`이 AI 채팅 컴포넌트(`ProgramChatProvider`)에 직접 주입됨 — 실회원 연동 시 자동 동작

### 3.2 API 클라이언트 — 자동 토큰 주입

**파일**: `src/lib/apiClient.js`

```javascript
const BASE_URL = import.meta.env.VITE_API_CONTEXT || '';
// Bearer 토큰 자동 주입 인터셉터
instance.interceptors.request.use((config) => {
  const { token } = useAuthStore.getState();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
```

**Vite 프록시 설정** (`vite.config.js`):
```javascript
// 개발 환경에서만 프록시 활성화
// /home-dev/api/... → localhost:8081/api/... 로 전달
proxy: {
  [apiPrefix]: {
    target: apiHost,  // VITE_API_HOST || 'http://localhost:8081'
    rewrite: (path) => path.replace(...)
  }
}
```

**평가**: 토큰 자동 주입 로직은 정상. `VITE_API_HOST`가 환경변수로 분리되어 있어 운영 환경 전환 가능.

### 3.3 인증 컨텍스트

**파일**: `src/context/AuthContext.jsx`

```javascript
// "로그인 시뮬레이션" 주석이 명시됨
const login = useCallback(async (username, password, type = 'INDIVIDUAL') => {
  // 로그인 시뮬레이션 - 실제 구현: API 요청 후 토큰 받기
  const response = await apiClient.post('/api/v1/auth/login', { username, password, type });
  // response = { accessToken: "dummy-access-token", refreshToken: "dummy-refresh-token" }
  const profileResponse = await apiClient.get('/api/v1/account/me');
  storeLogin({ token, refreshToken, profile });
}, []);
```

**평가**: 구조는 정상이나, `POST /api/v1/auth/login` BE 응답이 더미 토큰. `/api/v1/account/me`도 더미 데이터를 반환.

### 3.4 로그인 페이지들

#### (A) 일반 ID/PW 로그인 — `Login.jsx`

```
흐름: 사용자 입력 → POST /api/v1/auth/login → BE: dummy-access-token 반환
     → GET /api/v1/account/me → 더미 프로파일 반환 → useAuthStore.login()
```

- UI는 완성 (INDIVIDUAL/CORPORATE 탭 선택, 유효성 검사)
- BE `AuthServiceImpl.login()` = `return new TokenResponse("dummy-access-token", "dummy-refresh-token")`
- SNS 로그인 버튼 주석처리됨 (`{/* SNS 로그인 */}`)

#### (B) SSO 팝업 로그인 — `SSOLogin.jsx` ← **시연용 증거 #1**

```javascript
// 8개 회사 하드코딩 드롭다운
<select value={lgnId} onChange={lgnIdChange}>
  <option value="01">그린푸드 영농조합법인</option>
  <option value="02">테크스타트 주식회사</option>
  ...8개...
</select>

const handleClick = async () => {
  // API 호출 완전 없음
  const companyProfile = getCompanyProfileByBizNo(brno); // 로컬 파일에서 읽음
  login({ profile: companyProfile });                     // 토큰 없이 로그인!
  window.opener.postMessage({ type: 'LOGIN_SUCCESS', data: { brno, ... } }, origin);
  window.close();
  // 주석처리된 실제 API 호출:
  // const response = await apiClient.post('/api/v1/account/scenario-login', body);
};
```

**위험 등급**: 🔴 CRITICAL — 토큰 없이 로그인 상태가 되므로 인증 우회 가능

#### (C) OnePass(Keycloak) 인가 URL 생성 — `keycloakGetAuthCode.js` ← **시연용 증거 #2**

```javascript
// CSRF state 비활성화 (임시 연동 계약 주석 명시)
// const state = crypto.randomUUID();
// sessionStorage.setItem('keycloak_state', state);

export function onePassGetAuthCode() {
  let params = new URLSearchParams({
    client_id: CLIENT_ID,   // 'smes-tipa-01' 하드코딩
    redirect_uri: REDIRECT_SSO_URI,  // 'https://www.smes.go.kr/home-dev/sso' 하드코딩
    response_type: 'code',
    scope: 'openid',
    // state: state,  // ← 비활성화됨
  });
  window.location.href = authUrl;
}
```

**위험 등급**: 🔴 HIGH — CSRF 공격에 무방비, redirect_uri 하드코딩으로 환경별 배포 불가

### 3.5 OnePass SSO 콜백 처리 — `OnePassSsoCallback.jsx` ← **시연용 증거 #3**

```javascript
// 임시 연동 계약: 프론트 state 검증 비활성화
// const KEYCLOAK_STATE_KEY = 'keycloak_state';

const callbackState = {
  stateValidationBypassed: true,  // ← 명시적으로 bypass 선언
  // hasState: Boolean(state),    // 주석처리
  // stateMatches: ...,           // 주석처리
};

// state 검증 로직 전체 주석처리됨
/*
if (!state || state !== savedState) {
  navigate('/service/login', { replace: true });
  return;
}
*/
```

**케이스 분기**:
- **Case 1 (비로그인)**: `POST /api/v1/auth/keycloak/callback/local-login` → accessToken + refreshToken → `/api/v1/account/me` → useAuthStore 저장
- **Case 2 (기존 로그인)**: `POST /api/v1/auth/keycloak/callback` → 성공 확인 후 홈으로 이동

**평가**: 흐름 자체는 정확하게 설계되어 있으나 state 검증 없음 = CSRF 취약점

### 3.6 헤더 인증 버튼 구조 — `Header.jsx` + `HeaderUserMenu.jsx`

**미로그인 상태**:
```
[중기원패스 통합로그인] [로그인] [회원가입]
         ↓                ↓         ↓
  onePassGetAuthCode()  /service/login  onepass-dev 외부 링크
  (CSRF state 없음)    (더미 토큰 연결)  (하드코딩 URL)
```

**로그인 상태**:
```
[중기원패스 통합로그인] [마이 비즈니스] [MM:SS 연장] [로그아웃]
         ↓                                              ↓
  항상 같은 진입점                           POST /api/v1/auth/keycloak/logout
                                             → logoutUrl 반환 → 외부 리다이렉트
```

**세션 타이머 구현**: JWT `exp` claim 클라이언트 파싱 → 1초 폴링 → 만료 시 자동 로그아웃 (정상 구현)

**`handleIntegratedLogin()`** (팝업 방식):
- `GET /api/v1/auth/login-url` → MockAuthController 또는 정식 BE → 팝업 열기
- `postMessage` 수신 → `LOGIN_SUCCESS` 이벤트 처리
- `@Profile("local")`에서만 응답하는 BE에 의존

**하드코딩 발견**:
```javascript
// HeaderUserMenu.jsx - M&A 시스템 외부 링크 하드코딩
const url = 'https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/...';
// Header.jsx - OnePass 회원가입 URL 하드코딩
const onePassJoinUrl = 'https://onepass-dev.smes.go.kr/register/step1?type=member&return_client=smes-tipa-01';
```

### 3.7 라우팅 구조

**파일**: `src/routes/staticRoutes.jsx`

```javascript
export const staticRoutes = [
  {
    element: <MenuProviderOnly />,
    children: [
      { path: '/', element: <MainPage /> },
      { path: '/service/SSO-login', element: <SSOLogin /> },    // 시연용 SSO 팝업
      { path: '/sso', element: <OnePassSsoCallback /> },         // OnePass 콜백
      { path: '/sso-logout', element: <OnePassSsoLogout /> },   // 로그아웃 콜백
    ],
  },
  {
    element: <SubpageLayoutWithMenu />,
    children: [
      { path: '/service/login', element: <Login /> },            // 일반 로그인
    ],
  },
];
```

**인증 가드 없음**: 어떤 라우트에도 `PrivateRoute` 또는 `RequireAuth` 패턴이 없음. 로그인 없이 모든 페이지 접근 가능.

### 3.8 앱 루트 구조 — `App.jsx`

```javascript
// AI 서버 API 키 하드코딩 ← 추가 시연용 증거
const AI_CONFIGS = {
  prod: {
    url: 'https://www.smes-tipa.go.kr/aiax-dev/v1',
    key: 'sk-F4E9gAEtT-5NKFuPIiDnT3UoNyXqXSwOFqcfp__CUDY',  // ← 하드코딩 API Key
  },
  dev: {
    url: 'https://ax.llmonx.kr:28443/v1',
    key: 'sk-dSXsb0I7zcjxqr23mwYsjJoFFpCfvjg5LHkwaf-CP0s',  // ← 하드코딩 API Key
  }
};
```

**위험 등급**: 🟡 MEDIUM — AI 서비스 API 키 노출로 무단 사용 가능성

---

## 4. 백엔드(BE) 인증 구조 완전 해부

### 4.1 Spring Security 설정 — `SecurityConfig.java`

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/**").permitAll() // ← 전체 URL 오픈 (개발완료 및 운영반영시 수정)
    // 위 설정으로 아래 규칙들은 사실상 무효
    .requestMatchers("/api/v1/menu").permitAll()
    .requestMatchers("/api/v1/board/**").permitAll()
    .requestMatchers("/api/ciw-im/**").permitAll()
    .anyRequest().authenticated()
)
```

**위험 등급**: 🔴 CRITICAL — `/api/v1/**` permitAll로 모든 비즈니스 API가 인증 없이 접근 가능

**주석 증거**:
```java
// 개발완료 및 운영반영시 수정
// oauth2Login(...) — 주석처리됨
```

### 4.2 JWT 필터 — `JwtAuthenticationFilter.java`

```java
@Override
protected void doFilterInternal(HttpServletRequest request, ...) {
    String token = resolveToken(request);
    if (StringUtils.hasText(token) && jwtProvider.validateToken(token)) {
        Authentication auth = getAuthentication(token);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    filterChain.doFilter(request, response); // ← 항상 계속 진행
}
```

**구조적 문제**: `validateToken` 실패해도 `filterChain.doFilter()`가 실행됨. `permitAll()`과 조합되면 토큰 없이 모든 요청 처리 가능.

### 4.3 인증 서비스 — `AuthServiceImpl.java` ← **시연용 증거 #4**

```java
@Override
public TokenResponse login(LoginRequest loginRequest) {
    // TODO: Keycloak과 연동하여 실제 인증을 처리하고 JWT를 발급받는 로직 구현
    // 지금은 임시로 더미 토큰을 반환합니다.
    log.debug("Username: {}", loginRequest.getUsername());
    log.debug("Password: {}", loginRequest.getPassword());
    return new TokenResponse("dummy-access-token", "dummy-refresh-token");
}
```

**위험 등급**: 🔴 CRITICAL — 어떤 ID/PW 조합도 로그인 성공, 실제 인증 없음

### 4.4 Keycloak 인증 계층 (부분 구현)

`KeycloakController.java` → `KeycloakTokenService.java` → `KeycloakLocalLoginService.java`는 **정식 흐름이 설계**되어 있다:

```
code (from OnePass) → KeycloakTokenService.exchangeCode() → Keycloak access_token
                   → KeycloakLocalLoginService.login() → UUID claim 추출
                   → sc_mbrm.uuid 조회 → SMEP 자체 JWT 발급
```

**문제점**:
1. `KeycloakAccessTokenClaimExtractor` — 서명 검증 없이 Base64 디코딩만 수행
2. `SsoStateStore` — `state.startsWith("local-")` 시 검증 bypass (운영 제거 필요)
3. `application-dev.yml` — `client-secret` 하드코딩 (Git에 평문 노출)

### 4.5 Q-IM Inbound 계층 — 부분 완성

```
/api/ciw-im/member/{query|register|withdraw}
     ↓
QimMemberController → QimInboundAuthenticationService (X-API-Key 검증)
     ↓
QimIdentityDecoder.decode(encCi) → AES-256-GCM 미구현 (bypass 상태)
     ↓
QimMemberQueryService / QimMemberRegisterService / QimMemberWithdrawService
```

**주목할 구현 완성도**:
- `QimMemberRegisterService` — `allocateNextMemberNo()` YYYYMMDD+8자리 순번, 트랜잭션 완전 구현
- `QimMemberWithdrawService` — UUID 검증, 멱등 처리, 기업담당자 체크 완성
- **미구현**: `QimIdentityDecoder.decodeEncryptedCi()` — `return Optional.of(ci)` (평문 bypass)

---

## 5. FE ↔ BE 연동 흐름 전체 도식화

### 5.1 현재(시연용) 로그인 흐름

#### 흐름 A: SSO 팝업 로그인 (시연 전용)

```
사용자
  │
  ├─ [중기원패스 통합로그인] 버튼 클릭 (Header.jsx)
  │         │
  │         ├─ handleIntegratedLogin() → GET /api/v1/auth/login-url
  │         │         │
  │         │         └─ MockAuthController(@Profile local) → loginUrl 반환
  │         │                    또는 실패 시 fallback → /service/SSO-login 팝업
  │         │
  │         └─ 팝업창 (/service/SSO-login)
  │                   │
  │                   └─ SSOLogin.jsx
  │                           │
  │                           ├─ 8개 회사 드롭다운 선택
  │                           ├─ companyProfiles.js에서 로컬 데이터 읽기
  │                           ├─ useAuthStore.login({ profile }) ← 토큰 없이!
  │                           └─ postMessage(LOGIN_SUCCESS) → 부모창
  │
  └─ 부모창 handleLoginMessage() 수신
            ├─ data.brno 있으면 → login({ profile: data }) ← 또 토큰 없이!
            └─ token 있으면 → GET /api/v1/account/me → login()
```

**⚠️ 치명적 문제**: 토큰 없이 `isLogin=true` 상태. API 요청 시 `Authorization: Bearer null` 전송.

#### 흐름 B: 일반 ID/PW 로그인

```
사용자
  │
  ├─ /service/login 페이지 (Login.jsx)
  │         │
  │         ├─ POST /api/v1/auth/login { username, password, type }
  │         │         │
  │         │         └─ AuthServiceImpl.login() → "dummy-access-token" 반환
  │         │
  │         ├─ GET /api/v1/account/me (Authorization: Bearer dummy-access-token)
  │         │         │
  │         │         └─ JwtAuthenticationFilter → validateToken("dummy-access-token") = false
  │         │                    → filterChain 계속 진행 (permitAll)
  │         │                    → OidcController.me() → 더미 프로파일 반환
  │         │
  │         └─ useAuthStore.login({ token: "dummy-access-token", profile: 더미 })
  │
  └─ 이후 모든 API 요청: Authorization: Bearer dummy-access-token
            → BE: validateToken = false → permitAll → 정상 처리
```

#### 흐름 C: OnePass(Keycloak) 직접 연동 (정식 흐름 — 일부 구현됨)

```
사용자
  │
  ├─ [중기원패스 통합로그인] 버튼 (Header.jsx)
  │         │
  │         └─ onePassGetAuthCode() (keycloakGetAuthCode.js)
  │                   │
  │                   ├─ state 생성 안 함 (CSRF 취약점)
  │                   └─ redirect: https://isso-dev.smes.go.kr/qsign/realms/...
  │                              → 사용자 OnePass 로그인
  │                              → redirect_uri: https://www.smes.go.kr/home-dev/sso?code=XXX
  │
  ├─ /sso?code=XXX 도착 (OnePassSsoCallback.jsx)
  │         │
  │         ├─ state 검증 없음 (bypass)
  │         ├─ isLogin 체크:
  │         │         ├─ 비로그인: POST /api/v1/auth/keycloak/callback/local-login { code }
  │         │         │         → KeycloakTokenService.exchangeCode() → Keycloak token
  │         │         │         → KeycloakAccessTokenClaimExtractor → UUID (서명 검증 없음)
  │         │         │         → sc_mbrm.uuid 조회 → SMEP JWT 발급
  │         │         │         → accessToken + refreshToken 반환
  │         │         │         → GET /api/v1/account/me → 프로파일
  │         │         │         → useAuthStore.login()
  │         │         └─ 로그인: POST /api/v1/auth/keycloak/callback { code }
  │         │                   → Keycloak token 교환 → 성공 확인 → 홈 이동
  │         │
  │         └─ navigate('/')
  │
  └─ 로그아웃 (Header.jsx)
            ├─ POST /api/v1/auth/keycloak/logout → logoutUrl 반환
            ├─ useAuthStore.logout()
            └─ window.location.href = logoutUrl (외부 OnePass 로그아웃)
```

**이 흐름이 정식 설계의 핵심**이며, `state` 검증과 `JWT 서명 검증`만 복원하면 보안 완성.

---

## 6. 시연용 코드 완전 목록 (FE + BE 합산)

### FE 시연용 특이점 7개

| # | 파일 | 내용 | 위험도 |
|---|------|------|--------|
| FE-1 | `SSOLogin.jsx` | 8개 회사 하드코딩, API 없이 로컬 데이터로 로그인, 토큰 없음 | 🔴 CRITICAL |
| FE-2 | `keycloakGetAuthCode.js` | CSRF state 생성·저장·검증 전체 비활성화 | 🔴 HIGH |
| FE-3 | `OnePassSsoCallback.jsx` | state 검증 전체 주석처리 (`stateValidationBypassed: true`) | 🔴 HIGH |
| FE-4 | `companyProfiles.js` | 0000000001~5 더미 사업자번호, 실제 데이터 없음 | 🟡 MEDIUM |
| FE-5 | `Header.jsx` | `onepass-dev.smes.go.kr` 개발 URL 하드코딩 | 🟡 MEDIUM |
| FE-6 | `HeaderUserMenu.jsx` | M&A 시스템 URL 하드코딩 (`isso-dev.smes.go.kr`) | 🟡 MEDIUM |
| FE-7 | `App.jsx` | AI 서비스 API 키 소스코드 하드코딩 | 🟡 MEDIUM |

### BE 시연용 특이점 5개

| # | 파일 | 내용 | 위험도 |
|---|------|------|--------|
| BE-1 | `AuthServiceImpl.java` | `"dummy-access-token"` 반환, 실제 인증 없음 | 🔴 CRITICAL |
| BE-2 | `SecurityConfig.java` | `/api/v1/**` 전체 permitAll — 모든 API 인증 우회 | 🔴 CRITICAL |
| BE-3 | `SsoStateStore.java` | `state.startsWith("local-")` 시 검증 bypass | 🔴 HIGH |
| BE-4 | `QimIdentityDecoder.java` | AES-256-GCM 미구현, 평문 CI 그대로 반환 | 🟡 MEDIUM |
| BE-5 | `application-dev.yml` | Keycloak client-secret, Q-IM API Key Git 평문 노출 | 🟡 MEDIUM |

### 추가 확인된 @Profile("local") 전용 코드

| 파일 | 역할 |
|------|------|
| `MockAuthController.java` | local 전용 로그인 URL + 콜백 시뮬레이션 |
| `SsoAuthController.java` | local 전용 팝업 콜백 처리 |
| `MockSsoClient.java` | local 전용 SSO 클라이언트 모의 구현 |

**결론**: 총 **12개 시연용 특이점** 중 **5개는 즉시 운영 배포 시 보안 사고 수준**의 취약점이다.

---

## 7. 현재 상태의 운영 불가 근거

### 7.1 인증 메커니즘 부재

현재 시스템은 "로그인"이라는 사용자 액션과 "인증된 세션"이라는 보안 상태가 **완전히 분리**되어 있다:

```
시연 상태의 인증 = 사용자 선택(드롭다운) → 로컬 상태 변경
실제 인증 = 사용자 신원 확인 → 서버 측 세션/토큰 발급 → 상태 보장
```

구체적 증거:
1. `SSOLogin.jsx`: `login({ profile: companyProfile })` — `token: undefined`
2. `AuthServiceImpl.login()`: `return new TokenResponse("dummy-access-token", ...)`
3. `SecurityConfig`: `.requestMatchers("/api/v1/**").permitAll()`

이 세 가지가 조합되면: **어떤 사용자도 어떤 API도 인증 없이 호출 가능**하다.

### 7.2 CSRF 공격 취약성

```
공격 시나리오:
1. 악의적 사이트 A가 onePass 인가 URL을 생성 (state 없음)
2. 사용자가 자신의 브라우저에서 로그인 (state 검증 없으므로 통과)
3. 공격자가 생성한 code로 /sso?code=공격자코드 요청
4. SMEP은 공격자 코드로 OnePass 토큰 교환 → 공격자 계정으로 로그인됨
```

FE의 `keycloakGetAuthCode.js`와 `OnePassSsoCallback.jsx` 모두 state를 처리하지 않으므로, **CSRF Login Attack**에 완전히 노출.

### 7.3 개인정보 처리 불가 — CI/UUID 연동 미완성

Q-IM inbound의 `QimIdentityDecoder`가 AES-256-GCM 복호화 미구현 상태. `sc_mbrm` DB에서 CI 기반 회원 조회가 실제로는 평문 CI를 사용하므로, **암호화된 CI가 전달될 경우 조회 실패**.

### 7.4 시크릿 관리 미비

```yaml
# application-dev.yml — Git에 평문 노출
keycloak:
  client-secret: QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF
qim.inbound:
  api-key: imk-XRw22gijwk3uEQtAV-9wC93RHncDFBaRhryRqsQcZMA
  aes-shared-key: KBXiNF4G2cCWah8z+NGUoMEk11bSk+Kgx8Cc+8tFp2Y=
```

```javascript
// App.jsx — 소스코드에 AI API 키 하드코딩
key: 'sk-F4E9gAEtT-5NKFuPIiDnT3UoNyXqXSwOFqcfp__CUDY'
```

운영 환경에서 이 값들이 그대로 사용될 경우 Git 히스토리 통해 영구 노출.

### 7.5 인증 가드 미구현

FE 라우터에 `ProtectedRoute` 패턴 없음. 로그인 페이지 이외의 모든 페이지가 비인증 사용자에게 노출됨. 인증이 필요한 `/mb(마이 비즈니스)` 경로도 무보호 상태.

---

## 8. integration-sso 전향 후 완성 상태 명세

### 8.1 integration-sso 아키텍처 개요

```
integration-sso v2.3.0 (Sprint 10 완료, 397 테스트 통과)

모듈 구성:
├── q-sign (Port 8081) — OnePass OIDC Authorization Code Flow
├── q-im   (Port 8082) — Identity Management API
├── ido    (Port 8083) — Identity Orchestrator (IdO) ← 핵심
└── agency-stub (Port 8084) — 연동 기관 시뮬레이터
```

**핵심 설계 원칙 — ADR-001 (IdO 완전 중재 패턴)**:

모든 인증 흐름은 `ido` 모듈이 중재. SMEP은 IdO와 표준화된 Handoff Ticket으로 통신.

```
[SMEP FE] ──HTTP──> [SMEP BE] ──Handoff Ticket──> [IdO:8083]
                                                         │
                                              ┌──────────┴──────────┐
                                          [Q-Sign:8081]        [Q-IM:8082]
                                          OnePass OIDC       Identity Mgmt
```

### 8.2 Handoff Ticket 규격

```
형식: v{n}.{iv(B64)}.{ciphertext(B64)}
암호화: AES-256-GCM + HMAC-SHA256
생성: SMEP BE → IdO 요청 시 ticket 발급
검증: IdO → HMAC 검증 → AES 복호화 → 페이로드 추출
```

**만료**: 단일 사용 (one-time), TTL 설정 가능

### 8.3 전향 후 FE 완성 상태

| 현재 (시연) | 전향 후 (정식) |
|------------|--------------|
| `SSOLogin.jsx` 하드코딩 드롭다운 | 삭제 — OnePass가 SSO 진입점 |
| `companyProfiles.js` 로컬 더미 데이터 | 삭제 — `GET /api/v1/account/me` 실데이터 |
| state 없는 `onePassGetAuthCode()` | `crypto.randomUUID()` state 생성·검증 복원 |
| `OnePassSsoCallback.jsx` state bypass | state 검증 로직 활성화 |
| AI API Key 하드코딩 | `VITE_AI_API_KEY` 환경변수로 분리 |
| 인증 가드 없음 | `ProtectedRoute` 컴포넌트 추가 |
| 더미 토큰으로 로그인 | OnePass JWT 기반 실토큰 |

### 8.4 전향 후 BE 완성 상태

| 현재 (시연) | 전향 후 (정식) |
|------------|--------------|
| `"dummy-access-token"` 반환 | OnePass token → UUID → SMEP JWT 발급 |
| `/api/v1/**` permitAll | 경로별 인증 세분화 |
| state "local-" bypass | SsoStateStore 운영 코드로 교체 |
| Keycloak token 서명 검증 없음 | JWKS 엔드포인트 기반 서명 검증 |
| AES-256-GCM 복호화 미구현 | IdO의 Q-IM과 연동하여 암호화 CI 처리 |
| `@Profile("local")` 전용 코드 | 운영 환경에서 비활성화 |

### 8.5 전향 후 인증 흐름 (완성 목표)

```
[사용자] → [중기원패스 통합로그인 클릭]
    │
    ├─ FE: crypto.randomUUID() 생성 → sessionStorage 저장
    ├─ FE: Keycloak 인가 URL 생성 (state 포함)
    └─ 외부 OnePass 로그인 페이지 이동
              │
              └─ OnePass 인증 성공 → redirect_uri?code=XXX&state=YYY
                          │
                          └─ /sso?code=XXX&state=YYY
                                    │
                                    ├─ FE: state 검증 (sessionStorage 비교)
                                    ├─ FE: POST /api/v1/auth/keycloak/callback/local-login {code}
                                    │         │
                                    │         └─ BE: Keycloak token 교환 (JWKS 서명 검증)
                                    │                    → UUID claim 추출
                                    │                    → sc_mbrm.uuid 조회
                                    │                    → SMEP JWT 발급
                                    │                    → { accessToken, refreshToken } 반환
                                    │
                                    ├─ FE: GET /api/v1/account/me → 실회원 프로파일
                                    └─ FE: useAuthStore.login() → 완전한 인증 상태
```

---

## 9. FE 변경 작업 항목 상세화

### 9.1 보안 수정 (즉시 필요)

#### 작업 FE-SEC-001: CSRF state 복원
**파일**: `src/utils/keycloakGetAuthCode.js`

```javascript
// 제거할 주석 해제
export function onePassGetAuthCode() {
  const state = crypto.randomUUID();                        // ← 활성화
  sessionStorage.setItem('keycloak_state', state);          // ← 활성화
  
  let params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_SSO_URI,
    response_type: 'code',
    scope: 'openid',
    state: state,                                           // ← 활성화
  });
  window.location.href = authUrl;
}
```

**공수**: 1시간 (주석 해제 + 테스트)

#### 작업 FE-SEC-002: 콜백 state 검증 복원
**파일**: `src/pages/onepass/OnePassSsoCallback.jsx`

```javascript
const KEYCLOAK_STATE_KEY = 'keycloak_state';               // ← 주석 해제

// state 검증 로직 복원
if (!state || state !== savedState) {
  window.sessionStorage.removeItem(KEYCLOAK_STATE_KEY);
  navigate('/service/login', { replace: true });
  return;
}
window.sessionStorage.removeItem(KEYCLOAK_STATE_KEY);
```

**공수**: 2시간 (주석 해제 + 검증 로직 확인 + 테스트)

#### 작업 FE-SEC-003: 더미 SSO 로그인 페이지 제거
**파일**: `src/pages/SSOLogin.jsx`

- 페이지 자체 제거 또는 운영 배포 라우트에서 제외
- `staticRoutes.jsx`에서 `/service/SSO-login` 라우트 제거
- `companyProfiles.js` 파일 삭제 또는 테스트 전용으로 이동

**공수**: 2시간

#### 작업 FE-SEC-004: AI API 키 환경변수 분리
**파일**: `src/App.jsx`

```javascript
// 하드코딩 제거
const AI_CONFIGS = {
  prod: {
    url: import.meta.env.VITE_AI_PROD_URL,
    key: import.meta.env.VITE_AI_PROD_KEY,   // ← 환경변수로
  },
  dev: {
    url: import.meta.env.VITE_AI_DEV_URL,
    key: import.meta.env.VITE_AI_DEV_KEY,    // ← 환경변수로
  }
};
```

**공수**: 2시간 + `.env.production` 설정

### 9.2 기능 개선 (정식 전환 시)

#### 작업 FE-FEAT-001: 인증 가드(ProtectedRoute) 추가

```javascript
// src/routes/ProtectedRoute.jsx (신규)
function ProtectedRoute({ children }) {
  const { isLogin, token } = useAuthStore();
  if (!isLogin || !token) {
    return <Navigate to="/service/login" replace />;
  }
  return children;
}

// staticRoutes.jsx 수정
{ path: '/mb', element: <ProtectedRoute><MyBusiness /></ProtectedRoute> }
```

**공수**: 4시간

#### 작업 FE-FEAT-002: 하드코딩 URL 환경변수 분리

```javascript
// keycloakGetAuthCode.js
const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL;
const CLIENT_ID    = import.meta.env.VITE_KEYCLOAK_CLIENT_ID;
const REDIRECT_SSO_URI = import.meta.env.VITE_REDIRECT_SSO_URI;

// Header.jsx
const onePassJoinUrl = import.meta.env.VITE_ONEPASS_JOIN_URL;
```

**공수**: 3시간 + 환경별 `.env` 파일 정비

#### 작업 FE-FEAT-003: 토큰 갱신 인터셉터 강화

```javascript
// apiClient.js — 401 응답 시 자동 갱신
instance.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      const { refreshToken, setToken, setRefreshToken, logout } = useAuthStore.getState();
      if (refreshToken) {
        try {
          const res = await instance.post('/api/v1/account/refresh', { refreshToken });
          setToken(res.data.accessToken);
          setRefreshToken(res.data.refreshToken);
          error.config.headers.Authorization = `Bearer ${res.data.accessToken}`;
          return instance.request(error.config);
        } catch {
          logout();
        }
      }
    }
    return Promise.reject(error);
  }
);
```

**공수**: 4시간

### 9.3 FE 변경 작업 요약

| ID | 작업명 | 우선순위 | 공수 | 담당 |
|----|--------|---------|------|------|
| FE-SEC-001 | CSRF state 복원 | 🔴 즉시 | 1h | FE 개발자 |
| FE-SEC-002 | 콜백 state 검증 복원 | 🔴 즉시 | 2h | FE 개발자 |
| FE-SEC-003 | 더미 SSO 페이지 제거 | 🔴 즉시 | 2h | FE 개발자 |
| FE-SEC-004 | AI API 키 환경변수 분리 | 🟡 단기 | 2h | FE 개발자 |
| FE-FEAT-001 | 인증 가드 추가 | 🟡 단기 | 4h | FE 개발자 |
| FE-FEAT-002 | 하드코딩 URL 환경변수화 | 🟡 단기 | 3h | FE 개발자 |
| FE-FEAT-003 | 토큰 갱신 인터셉터 | 🟢 중기 | 4h | FE 개발자 |
| **합계** | | | **18h** | |

---

## 10. BE 변경 작업 항목 상세화

### 10.1 보안 수정 (즉시 필요)

#### 작업 BE-SEC-001: SecurityConfig 인증 세분화

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/**").permitAll()
    .requestMatchers("/api/v1/auth/**").permitAll()      // 인증 엔드포인트만 개방
    .requestMatchers("/api/ciw-im/**").permitAll()       // Q-IM (별도 API Key 검증)
    .requestMatchers("/api/v1/juso/**").permitAll()      // 주소검색
    .requestMatchers("/api/v1/menu").permitAll()         // 메뉴 데이터
    .requestMatchers("/api/v1/board/**").permitAll()     // 공개 게시판
    .requestMatchers("/api/v1/search/**").permitAll()    // 공개 검색
    .requestMatchers("/api/v1/**").authenticated()       // ← 나머지 인증 필수로 전환
    .anyRequest().authenticated()
)
// 제거: .requestMatchers("/api/v1/**").permitAll()
```

**공수**: 4시간 (변경 후 엔드포인트별 영향도 검토 포함)

#### 작업 BE-SEC-002: AuthServiceImpl 더미 토큰 제거

```java
@Override
public TokenResponse login(LoginRequest loginRequest) {
    // 기존: return new TokenResponse("dummy-access-token", "dummy-refresh-token");
    
    // 전향 후: OnePass를 통한 인증으로 대체
    // 로컬 ID/PW 로그인은 Keycloak Resource Owner Password 방식 또는 제거
    throw new BusinessException(CommonErrorCode.UNAUTHORIZED, 
        "직접 로그인은 지원하지 않습니다. 중기원패스로 로그인하세요.");
}
```

**공수**: 2시간

#### 작업 BE-SEC-003: SsoStateStore local- bypass 제거

```java
public boolean validateState(String state) {
    // 제거할 코드:
    // if (state.startsWith("local-")) {
    //     return true; // 운영에서 제거 필요
    // }
    
    String savedState = redisTemplate.opsForValue().get(STATE_PREFIX + state);
    return StringUtils.hasText(savedState);
}
```

**공수**: 1시간

#### 작업 BE-SEC-004: Keycloak 토큰 서명 검증 추가

```java
// KeycloakAccessTokenClaimExtractor.java 수정
// 현재: Base64 디코딩만 수행 (서명 검증 없음)
// 전향 후: JWKS 기반 서명 검증

// 의존성 추가 (build.gradle)
implementation 'com.nimbusds:nimbus-jose-jwt:9.37.3'

// JWKS URI: ${keycloak.server-url}/realms/${keycloak.realm}/protocol/openid-connect/certs
JWKSet jwkSet = JWKSet.load(new URL(jwksUri));
RSAKey rsaKey = (RSAKey) jwkSet.getKeyByKeyId(kid);
JWSVerifier verifier = new RSASSAVerifier(rsaKey);
SignedJWT signedJWT = SignedJWT.parse(accessToken);
boolean valid = signedJWT.verify(verifier);
```

**공수**: 8시간 (구현 + 테스트)

#### 작업 BE-SEC-005: Q-IM CI 암호화 복호화 구현

```java
// QimIdentityDecoder.java
public Optional<String> decodeEncryptedCi(String encCi) {
    // 현재: return Optional.of(ci); // bypass
    
    // 전향 후:
    if (!hasValidAes256Key()) {
        throw new IllegalStateException("AES key not configured");
    }
    byte[] keyBytes = Base64.getDecoder().decode(aesSharedKey);
    SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
    // AES-256-GCM 복호화 구현
    ...
    return Optional.of(plainCi);
}
```

**공수**: 6시간 (구현 + 벡터 테스트)

### 10.2 시크릿 관리 개선

#### 작업 BE-CFG-001: application-dev.yml 시크릿 제거

```yaml
# 현재 (위험)
keycloak:
  client-secret: QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF

# 전향 후
keycloak:
  client-secret: ${KEYCLOAK_CLIENT_SECRET}  # ← 환경변수만
```

Git 히스토리 정리: `git filter-repo` 또는 `BFG Repo Cleaner` 사용 필요

**공수**: 4시간 (Git 히스토리 정리 포함)

### 10.3 BE 변경 작업 요약

| ID | 작업명 | 우선순위 | 공수 | 담당 |
|----|--------|---------|------|------|
| BE-SEC-001 | SecurityConfig 인증 세분화 | 🔴 즉시 | 4h | BE 개발자 |
| BE-SEC-002 | 더미 토큰 제거 | 🔴 즉시 | 2h | BE 개발자 |
| BE-SEC-003 | SsoStateStore bypass 제거 | 🔴 즉시 | 1h | BE 개발자 |
| BE-SEC-004 | Keycloak 서명 검증 추가 | 🔴 단기 | 8h | BE 개발자 |
| BE-SEC-005 | Q-IM CI 복호화 구현 | 🟡 단기 | 6h | BE 개발자 |
| BE-CFG-001 | 시크릿 환경변수화 | 🔴 즉시 | 4h | DevOps |
| **합계** | | | **25h** | |

---

## 11. 전향 후 FE ↔ BE 연동 흐름 명세

### 11.1 완성 목표 인증 플로우

```
┌─────────────────────────────────────────────────────────────┐
│                    SMEP 통합플랫폼 (전향 후)                  │
│                                                             │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────────┐  │
│  │  SMEP FE │    │   SMEP BE    │    │   OnePass IDaaS  │  │
│  │ (React)  │    │ (Spring Boot)│    │ (integration-sso)│  │
│  └────┬─────┘    └──────┬───────┘    └────────┬─────────┘  │
│       │                 │                      │            │
│  [1] 로그인 클릭         │                      │            │
│       │ state 생성       │                      │            │
│       │ sessionStorage  │                      │            │
│       │─────────────────────────────────────>  │            │
│       │         [2] OnePass 로그인 페이지       │            │
│       │                 │                      │            │
│       │ [3] 인증 완료    │                      │            │
│       │<─────────────────────────────────────  │            │
│       │ code + state    │                      │            │
│       │                 │                      │            │
│  [4] state 검증         │                      │            │
│       │                 │                      │            │
│       │─── POST /api/v1/auth/keycloak/callback/local-login ─>│
│       │                 │ {code}               │            │
│       │                 │                      │            │
│       │            [5] Keycloak token 교환      │            │
│       │                 │──────── token_endpoint ─────────>  │
│       │                 │<──────── access_token ────────── │  │
│       │                 │                      │            │
│       │            [6] JWKS 서명 검증           │            │
│       │                 │──── /certs ─────────>│            │
│       │                 │<─── JWKS ──────────  │            │
│       │                 │                      │            │
│       │            [7] UUID claim 추출          │            │
│       │                 │                      │            │
│       │            [8] sc_mbrm.uuid 조회        │            │
│       │                 │──── DB 조회 ──────>  │            │
│       │                 │                      │            │
│       │            [9] SMEP JWT 발급            │            │
│       │<── { accessToken, refreshToken } ──────│            │
│       │                 │                      │            │
│  [10] GET /api/v1/account/me                   │            │
│       │────────────────>│                      │            │
│       │<─── 실회원 프로파일 ────────────────────│            │
│       │                 │                      │            │
│  [11] 상태 저장          │                      │            │
│  useAuthStore.login()   │                      │            │
│  (token + profile)      │                      │            │
└─────────────────────────────────────────────────────────────┘
```

### 11.2 로그아웃 플로우

```
사용자 → [로그아웃] 클릭 (Header.jsx)
    │
    ├─ POST /api/v1/auth/keycloak/logout → { logoutUrl }
    ├─ useAuthStore.logout() → sessionStorage 초기화
    ├─ BroadcastChannel: LOGOUT 메시지 → 모든 탭 동기화
    └─ window.location.href = logoutUrl (OnePass 세션 종료)
              │
              └─ OnePass 로그아웃 처리
                         │
                         └─ redirect → /sso-logout (OnePassSsoLogout.jsx)
                                    → sessionStorage.removeItem('keycloak_state')
                                    → logout() (중복 방지)
                                    → navigate('/')
```

### 11.3 토큰 갱신 플로우

```
API 요청 → 401 응답 (토큰 만료)
    │
    └─ apiClient.js 인터셉터 (전향 후 추가)
              │
              ├─ refreshToken 있음 → POST /api/v1/account/refresh { refreshToken }
              │         │
              │         ├─ 성공: setToken(newAccessToken), setRefreshToken(newRefreshToken)
              │         │        원래 요청 재시도
              │         └─ 실패: logout() → /service/login
              │
              └─ refreshToken 없음 → logout() → /service/login

Header.jsx 세션 타이머 (이미 구현됨):
    JWT exp claim 클라이언트 파싱 → 1초 폴링
    만료 전 [연장] 버튼 → POST /api/v1/account/refresh
    만료 시 자동 logout() + alert
```

---

## 12. 위험 관리 및 전환 로드맵

### 12.1 전환 로드맵 (4주 계획)

```
Week 1: 보안 수정 (즉시 처리 필수)
  ├─ FE: CSRF state 복원 (FE-SEC-001, 002)
  ├─ BE: SecurityConfig 인증 세분화 (BE-SEC-001)
  ├─ BE: 더미 토큰 제거 (BE-SEC-002)
  ├─ BE: state bypass 제거 (BE-SEC-003)
  └─ BE: application-dev.yml 시크릿 제거 (BE-CFG-001)

Week 2: 정식 연동 구현
  ├─ BE: Keycloak JWKS 서명 검증 (BE-SEC-004)
  ├─ BE: Q-IM CI 복호화 (BE-SEC-005)
  └─ FE: 더미 SSO 페이지 제거 (FE-SEC-003)

Week 3: FE 기능 개선
  ├─ FE: 인증 가드 추가 (FE-FEAT-001)
  ├─ FE: 환경변수 정비 (FE-FEAT-002, FE-SEC-004)
  └─ FE: 토큰 갱신 인터셉터 (FE-FEAT-003)

Week 4: 통합 테스트 및 검증
  ├─ E2E 인증 흐름 테스트
  ├─ 보안 취약점 재검증
  ├─ 운영 환경 배포 준비
  └─ 문서화 완료
```

### 12.2 전환 시 핵심 의존성

```
SMEP 전향을 위해 반드시 확보해야 할 항목:

□ OnePass(중기원패스) 운영 Client ID / Client Secret
□ Keycloak JWKS URI (운영 환경)
□ Q-IM AES-256-GCM Shared Key (운영용 신규 발급)
□ sc_mbrm DB 접속 정보 (운영 환경)
□ Redis 운영 인스턴스 (SsoStateStore용)
□ JWT_SECRET_KEY 운영 환경변수 설정
□ OnePass 콜백 URI 운영 등록 (https://[운영도메인]/sso)
```

### 12.3 롤백 계획

| 단계 | 롤백 방법 | 소요 시간 |
|------|---------|---------|
| Week 1 보안 수정 후 | Git revert (단, 시연 환경에서만) | 1시간 |
| Week 2 정식 연동 후 | Feature flag로 더미 모드 전환 | 2시간 |
| Week 3 FE 개선 후 | 이전 빌드 재배포 | 30분 |

---

## 부록 A: 분석된 파일 전체 목록

### SMEP FE — 분석 완료 (18개)
| 파일 | 분석 결과 |
|------|---------|
| `src/App.jsx` | AI API Key 하드코딩 발견 |
| `src/context/AuthContext.jsx` | "로그인 시뮬레이션" 주석 |
| `src/store/useAuthStore.jsx` | Zustand sessionStorage, BroadcastChannel — 정상 |
| `src/lib/apiClient.js` | Bearer 자동 주입 — 정상 |
| `src/lib/companyProfiles.js` | 8개 회사 더미 데이터 — 운영 제거 필요 |
| `src/utils/keycloakGetAuthCode.js` | CSRF state 비활성화 — 즉시 수정 필요 |
| `src/pages/Login.jsx` | 더미 토큰 BE 연결 |
| `src/pages/SSOLogin.jsx` | 핵심 시연용 — 운영 제거 필요 |
| `src/pages/onepass/OnePassSsoCallback.jsx` | state bypass — 수정 필요 |
| `src/pages/onepass/OnePassSsoLogout.jsx` | 정상 구현 |
| `src/pages/onepass/OnepassLoginConversionModal.jsx` | 정상 구현 |
| `src/components/ui/Header.jsx` | 하드코딩 URL — 환경변수화 필요 |
| `src/components/ui/header/HeaderUserMenu.jsx` | M&A URL 하드코딩 |
| `src/hooks/usePopupCommunication.js` | AI 팝업 통신 — 인증 무관, 정상 |
| `src/routes/index.jsx` | 동적 라우팅 — 정상 |
| `src/routes/staticRoutes.jsx` | 인증 가드 없음 |
| `vite.config.js` | 프록시 설정 — 정상 |
| `package.json` | 의존성 정상 |

### SMEP BE — 분석 완료 (37개)
(MIG-2026-001 참조)

---

## 부록 B: integration-sso 대응 규격 참조

| SMEP 요구사항 | integration-sso 대응 모듈 |
|-------------|------------------------|
| OnePass 인가 코드 교환 | `q-sign` Authorization Code Flow |
| 회원 등록/조회/탈퇴 | `q-im` Identity Management API |
| IdO 중재 패턴 | `ido` Identity Orchestrator |
| Handoff Ticket | `ido/HandoffTicketService` (AES-256-GCM) |
| CSRF state 관리 | `q-sign/OidcStateStore` (Redis) |
| JWKS 검증 | `q-sign/JwksVerifier` |

---

*이 문서는 MIG-2026-002로 관리되며, SMEP 통합플랫폼 개발팀 내부 배포용입니다.*  
*문서 최종 수정: 2026-05-11*
