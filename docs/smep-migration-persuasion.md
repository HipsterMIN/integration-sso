# SMEP → integration-sso 전향 설득 보고서

**문서 ID**: PRP-2026-001 v2 (로그인 흐름 정밀 재분석 반영)  
**작성일**: 2026-05-11  
**목적**: SMEP 통합플랫폼의 integration-sso 전향 필요성을 기술적 근거와 함께 의사결정권자에게 제시  
**개정 이유**: v1에서 SSOLogin.jsx를 메인 로그인으로 오분류한 오류 정정. FE·BE 취약점 재분류.

---

## 요약 (Executive Summary)

SMEP 통합플랫폼은 **현재 프로덕션 배포 불가 상태**다. 인증 시스템의 핵심인 백엔드가 고정된 더미 토큰을 반환하고 있으며, Spring Security는 모든 경로를 인증 없이 허용하고 있다. OAuth CSRF 방어 코드는 비활성화되어 있다.

반면 integration-sso는 ADR-001 IdO 완전 중재 패턴, AES-256-GCM 핸드오프 티켓, 397개 테스트 통과라는 검증된 인증 인프라를 보유하고 있다.

**SMEP FE의 재사용률은 높다.** 메인 로그인 UI(`Login.jsx`), 인증 상태 관리(`useAuthStore`), 비밀번호 관리 컴포넌트 등은 BE 교체 후 즉시 활용 가능한 수준으로 완성되어 있다. **전향에 필요한 FE 작업은 최소화**되며, 핵심 교체 대상은 BE 인증 로직이다.

---

## 1. 현재 상태 진단 — "무엇이 문제인가"

### 1.1 실제 동작 중인 시스템

`https://www.smes.go.kr/home-dev/service/login` 화면은 **개인 회원 / 기업 회원 탭 + ID/PW 입력 폼** 구조다. 이것이 `Login.jsx`이며, 실제 메인 로그인 페이지다.

사용자가 이 화면에서 어떤 ID/PW를 입력하든 로그인에 성공한다. 이유는 간단하다: **백엔드가 항상 더미 토큰을 반환**하기 때문이다.

```java
// BE AuthServiceImpl.java — 현재 구현
@Override
public LoginResponse login(LoginRequest request) {
    // TODO: 실제 사용자 인증 로직 구현 예정
    return LoginResponse.builder()
        .accessToken("dummy-access-token")   // 어떤 입력에도 동일한 값 반환
        .refreshToken("dummy-refresh-token")
        .build();
    // 비밀번호 검증: 없음 / DB 조회: 없음 / JWT 생성: 없음
}
```

### 1.2 Spring Security 전체 비활성화

```java
// BE SecurityConfig.java — 현재 구현
http.authorizeHttpRequests()
    .anyRequest().permitAll()  // 모든 API 경로 인증 없이 허용
    .and().csrf().disable();
```

인증 토큰이 없어도, 만료되어도, 위조되어도 모든 API에 접근할 수 있다.

### 1.3 OAuth CSRF 방어 코드가 주석처리된 채로 배포 중

```javascript
// FE keycloakGetAuthCode.js — 현재 구현
// ⚠️ CSRF 방어 코드 비활성화 상태
// const state = crypto.randomUUID();
// sessionStorage.setItem('keycloak_state', state);

// OnePassSsoCallback.jsx — state 검증 전체 주석처리
// stateValidationBypassed: true
```

OAuth 2.0 RFC 6749 §10.12가 명시한 CSRF 방어가 코드 레벨에서 작성되었으나 주석처리된 채로 배포되고 있다.

### 1.4 프론트엔드 인증 가드(ProtectedRoute) 부재

```javascript
// staticRoutes.jsx — 현재 구현
// 마이페이지 경로에 인증 게이트 없음
// 비인증 사용자가 /my-business/password 에 직접 URL 입력 시:
// → 로그인 리다이렉트 없음
// → VerifyPassword 컴포넌트가 렌더링됨 (loginId = 빈 값)
```

---

## 2. "FE 드롭다운이 메인 로그인" 오해에 대한 정정

이전 분석에서 `SSOLogin.jsx` (`/service/SSO-login`)를 "메인 로그인 페이지"로 잘못 분류한 오류가 있었다. 이를 명확히 정정한다.

### SSOLogin.jsx의 실제 위치

```
Header.jsx
  ↓ [통합 로그인 버튼 클릭]
handleIntegratedLogin()
  ↓ GET /api/v1/auth/login-url → MockAuthController(@Profile local)
  ↓ 응답 없음 또는 loginUrl=null 시
  ↓ window.open('.../service/SSO-login', 팝업창)  ← SSOLogin.jsx 호출
```

`SSOLogin.jsx`는 **로컬 개발 환경에서 외부 SSO 연동이 없을 때만** 팝업으로 열리는 fallback 데모 페이지다. 프로덕션 환경에서는 외부 SSO URL로 리다이렉트된다.

### 실제 메인 로그인: Login.jsx

실제 서비스 화면(`https://www.smes.go.kr/home-dev/service/login`)은 `Login.jsx`이며 **ID/PW 폼** 구조다. FE 코드 자체는 정상적인 프로덕션 UI 패턴을 따른다. 문제는 FE가 아니라 **BE의 더미 토큰 반환**이다.

```
[수정 전] FE-1 취약점: SSOLogin.jsx 드롭다운이 메인 로그인 ← 오분류
[수정 후] BE-1 취약점: AuthServiceImpl 더미 토큰 무조건 반환 ← 실제 핵심 문제
```

---

## 3. 취약점 분류표 (v2 정정)

| 순위 | ID | 위치 | 설명 | 위험도 |
|------|----|------|------|--------|
| 1 | BE-1 | AuthServiceImpl | 더미 토큰 무조건 반환 | 🔴 Critical |
| 2 | BE-2 | SecurityConfig | anyRequest().permitAll() | 🔴 Critical |
| 3 | SEC-1 | keycloakGetAuthCode.js | OAuth CSRF state 비활성화 | 🔴 Critical |
| 4 | SEC-2 | OnePassSsoCallback.jsx | state 검증 주석처리 | 🔴 Critical |
| 5 | FE-2 | staticRoutes.jsx | ProtectedRoute 없음 | 🔴 High |
| 6 | BE-3 | AccountController | 더미 프로파일 반환 | 🔴 Critical |
| 7 | FE-1 | Login.jsx | FE 입력 검증 없음 (빈 값 API 호출) | 🟡 Medium |
| 8 | SEC-3 | Header.jsx | 클라이언트 JWT 파싱 세션 타이머 | 🟡 Medium |
| 9 | FE-3 | App.jsx | AI API Key 소스코드 하드코딩 | 🟡 Medium |

---

## 4. integration-sso가 해결하는 것

### 4.1 BE-1, BE-2 — 실 토큰 발급 + 인가 처리

```
현재: AuthServiceImpl → "dummy-access-token" 고정 반환
전향: integration-sso ido 모듈 → ADR-001 IdO 완전 중재 패턴으로 실 JWT 발급

현재: SecurityConfig.anyRequest().permitAll()
전향: Spring Security + JWT 필터 체인으로 모든 보호 경로 인가 검증
```

### 4.2 SEC-1, SEC-2 — CSRF 방어 즉시 복원 가능

주목할 점은 이미 코드가 작성되어 있다는 것이다:

```javascript
// keycloakGetAuthCode.js — 주석만 해제하면 됨
const state = crypto.randomUUID();           // 이미 작성됨
sessionStorage.setItem('keycloak_state', state);  // 이미 작성됨
```

```javascript
// OnePassSsoCallback.jsx — 주석만 해제하면 됨
// state 검증 로직 이미 작성 완료, stateValidationBypassed: true 플래그만 제거
```

**CSRF 방어 복원에 필요한 작업: 주석 해제 2개.**

### 4.3 Handoff Ticket — 기업 연동 보안 강화

```
현재: companyProfiles.js (8개 더미 기업 데이터) → SSOLogin.jsx에서 직접 주입
전향: AES-256-GCM + HMAC-SHA256 Handoff Ticket → 기업 컨텍스트 안전 전달
```

---

## 5. FE 재사용 가능 자산 목록

integration-sso 전향 시 **재사용 가능한 FE 코드**가 상당히 많다. 이는 마이그레이션 비용을 크게 낮춘다.

### 5.1 메인 로그인 UI — Login.jsx ✅ 재사용

```javascript
// 재사용 가능한 이유:
// 1. UI 구조: 개인/기업 탭 + ID/PW 폼 — 정상적인 프로덕션 패턴
// 2. handleClick(): POST /api/v1/auth/login 호출 구조 유지
// 3. Enter 키 처리, 한글 조합 방지 (isComposing) — 완성
// 4. 라우터 state로 loginType 전달 기능 — 완성

// 필요한 추가 작업:
// - 빈 값 입력 검증 추가 (약 5줄)
// - BE가 실 토큰을 반환하면 나머지는 자동으로 동작
```

### 5.2 인증 상태 관리 — useAuthStore.jsx ✅ 재사용

```javascript
// 재사용 가능한 이유:
// - Zustand + sessionStorage persist 구조: 표준
// - BroadcastChannel 탭 동기화: 완성
// - token, refreshToken, user, currentMode, linkedCompanies: 구조 적절

// 전향 후 token 필드에 실 JWT가 저장되면 모든 의존 컴포넌트가 정상 동작
```

### 5.3 API 클라이언트 — apiClient.js ✅ 재사용

```javascript
// Bearer 자동 주입 인터셉터: 구조 완성
// 필요 추가: 401 응답 자동 로그인 리다이렉트 인터셉터 (약 10줄)
```

### 5.4 비밀번호 관리 — VerifyPassword.jsx, PasswordChange.jsx ✅ 재사용

```javascript
// VerifyPassword.jsx:
// - sessionStorage 기반 검증 상태 관리: 완성
// - 경로 이탈 시 상태 초기화: 완성
// - BE /api/v1/account/password/verify 실 연동 후 즉시 동작

// PasswordChange.jsx:
// - 비밀번호 복잡도 검증 로직: 완성 (영문+숫자+특수 2종 이상, 8-20자)
// - 변경 후 자동 로그아웃: 보안 관점 올바른 패턴
// - BE /api/v1/account/password 실 연동 후 즉시 동작
```

### 5.5 기업 정보 — memberUtils.js, CompanyDetail.jsx ✅ 재사용

```javascript
// API 호출 구조 완성: /api/v1/member/corporate/me, /api/v1/member/individual/me 등
// BE DB 연동 완료 후 FE 변경 없이 동작
```

### 5.6 OnePass SSO — keycloakGetAuthCode.js, OnePassSsoCallback.jsx ✅ 재사용

```javascript
// 핵심 로직 완성, CSRF 주석만 해제하면 됨
// integration-sso의 Keycloak 엔드포인트로 상수값만 변경
```

---

## 6. 전향에 필요한 실제 작업량 추정

| 작업 | 범위 | 예상 공수 |
|------|------|----------|
| BE 더미 토큰 → ido 실 토큰 교체 | AuthServiceImpl + SecurityConfig | 2주 |
| account/me DB 연동 | AccountController + Repository | 1주 |
| FE CSRF state 주석 해제 | 파일 2개, 5줄 미만 | 0.5일 |
| FE ProtectedRoute 구현 | staticRoutes.jsx + 1개 컴포넌트 | 1일 |
| FE 입력 검증 (Login.jsx) | 5줄 추가 | 0.5일 |
| BE 비밀번호 관리 DB 연동 | 2개 API | 1주 |
| BE 회원 정보 DB 연동 | 10개 API | 2주 |
| App.jsx API Key 환경변수 이동 | 환경변수 파일 수정 | 0.5일 |
| SSOLogin.jsx 제거 | 파일 삭제 | 0.5일 |
| **합계** | | **약 6~8주** |

**FE 작업만 보면**: CSRF 주석 해제 + ProtectedRoute + 입력 검증 = **약 2일**. FE는 거의 완성 상태다.

---

## 7. 전향하지 않을 경우의 리스크

### 7.1 프로덕션 배포 불가

현재 상태로 프로덕션 배포 시:
- 어떤 ID/PW로도 로그인 성공 → 계정 개념 무의미
- 모든 API 무인가 접근 가능 → 데이터 유출
- OAuth CSRF 공격에 노출 → 세션 하이재킹

### 7.2 기술 부채 누적

더미 토큰 기반으로 프론트엔드 개발을 계속할수록:
- `decodeJwtPayload` 결과가 null (더미 토큰은 유효한 JWT가 아님) → VerifyPassword.jsx의 defaultLoginId 오동작
- Header.jsx 세션 타이머 비정상 동작 (더미 토큰에 exp 없음)
- 기업 연동 컨텍스트 관련 코드의 신뢰성 저하

### 7.3 재작업 비용 증가

지금 FE가 더미 데이터 기반으로 추가 기능을 구현할수록, 나중에 실 인증으로 전환할 때 FE도 함께 수정해야 하는 범위가 늘어난다.

---

## 8. 권고 사항

### 즉시 실행 가능 (0.5일)
1. `keycloakGetAuthCode.js` state 파라미터 주석 해제
2. `OnePassSsoCallback.jsx` state 검증 주석 해제
3. `App.jsx` AI API Key 환경변수 이동

### 단기 (2주 이내)
4. `Login.jsx` 빈 값 입력 검증 추가
5. `staticRoutes.jsx` ProtectedRoute 구현
6. integration-sso ido 모듈을 SMEP BE와 연결 (AuthServiceImpl 교체)

### 중기 (6~8주)
7. `SecurityConfig` JWT 필터 체인 적용
8. `account/me` 실 DB 연동
9. 비밀번호 관리 API 실 연동
10. 회원 정보 API 실 연동
11. `SSOLogin.jsx` + `companyProfiles.js` 프로덕션 빌드에서 제거

---

## 9. 결론

SMEP 통합플랫폼의 프론트엔드는 **예상보다 완성도가 높다**. 메인 로그인 UI, 인증 상태 관리, 비밀번호 관리 컴포넌트, 기업 정보 페이지 — 모두 BE 교체 후 즉시 활용 가능한 수준으로 구현되어 있다.

문제의 핵심은 **BE 인증 미구현**이다. `AuthServiceImpl`이 더미 토큰을 반환하고, `SecurityConfig`가 모든 경로를 개방한 채로는 아무리 FE를 다듬어도 프로덕션 배포가 불가능하다.

integration-sso는 이 gap을 정확히 채운다. ADR-001 IdO 완전 중재 패턴, AES-256-GCM Handoff Ticket, 397개 테스트라는 검증된 인증 인프라를 SMEP BE에 연결하면, FE는 최소한의 수정(CSRF 주석 해제 2개, ProtectedRoute 추가, 입력 검증 보완)만으로 운용 가능한 시스템이 된다.

**전향 권고**: BE 인증 교체를 최우선으로, FE CSRF 수정을 병행하여 Phase 1을 4주 내 완료.

---

*문서 ID: PRP-2026-001 v2 | 작성일: 2026-05-11 | 관련 문서: MIG-2026-002 v2*
