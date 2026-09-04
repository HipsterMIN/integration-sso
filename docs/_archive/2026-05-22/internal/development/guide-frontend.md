# FE 팀 개발 가이드

> **버전**: v2.3.0 (Sprint 10 기준)  
> **최종 수정**: 2026-05-11  
> **대상**: onepass-fe 프론트엔드 개발팀  
> **포트**: 3001 (dev: Vite dev server)  
> **기술 스택**: React 18 / TypeScript / Vite / Redux (legacy_createStore + thunk)

---

## 목차

1. [프로젝트 개요 및 아키텍처](#1-프로젝트-개요-및-아키텍처)
2. [개발 환경 설정](#2-개발-환경-설정)
3. [디렉토리 구조 및 모듈 규칙](#3-디렉토리-구조-및-모듈-규칙)
4. [상태 관리 (Redux + 편의 훅)](#4-상태-관리)
5. [API 레이어 설계](#5-api-레이어-설계)
6. [인증 흐름 및 SLO (Sprint 10)](#6-인증-흐름-및-slo)
7. [useAuthState 훅 (Sprint 10 신규)](#7-useauthstate-훅)
8. [ErrorBoundary (Sprint 10 신규)](#8-errorboundary)
9. [마이페이지 — 회원정보 수정 (Sprint 10)](#9-마이페이지--회원정보-수정)
10. [MypageSideNav 로그아웃 버튼 (Sprint 10)](#10-mypagesidenav-로그아웃-버튼)
11. [타입 시스템 설계](#11-타입-시스템-설계)
12. [새 API 함수 추가 방법](#12-새-api-함수-추가-방법)
13. [새 마이페이지 섹션 추가 방법](#13-새-마이페이지-섹션-추가-방법)
14. [TypeScript 컴파일 검증](#14-typescript-컴파일-검증)
15. [자주 발생하는 오류 및 해결](#15-자주-발생하는-오류-및-해결)
16. [코딩 컨벤션 및 체크리스트](#16-코딩-컨벤션-및-체크리스트)

---

## 1. 프로젝트 개요 및 아키텍처

### 1.1 FE의 역할

onepass-fe는 **OnePass 통합인증 플랫폼의 SPA** 입니다.  
IdO(8083)만을 BFF로 사용하며, Q-IM/Q-Sign과는 직접 통신하지 않습니다.

```
사용자 브라우저
    │
    ▼
onepass-fe (React SPA, :3001)
    │
    │  ① feSessionId 쿠키 자동 포함
    │  ② extInstance (JWT Bearer)
    ▼
IdO :8083  ─→  Q-IM :8082  (내부 통신)
              Q-Sign :8081  (내부 통신)
              Keycloak      (내부 통신)
```

### 1.2 FE가 직접 하지 않는 것

| 행위 | 이유 |
|------|------|
| Q-IM 직접 호출 | 내부 API (X-Internal-Api-Key 필요) |
| Q-Sign 직접 호출 | 내부 API |
| Keycloak 직접 호출 | IdO BFF가 중계 |
| CI(주민번호급) 수신/저장 | Q3=B 정책 — FE 미반환 |
| 세션 무효화 직접 처리 | SLO API(IdO)가 Redis/Keycloak 정리 |

---

## 2. 개발 환경 설정

### 2.1 초기 설치

```bash
cd idem-console/frontend

# Node.js 20+ 필요 (nvm 권장)
node --version  # v20.x.x

npm install

# 환경 변수 설정
cp .env.example .env.local
```

### 2.2 .env.local 예시

```bash
# .env.local (Git 커밋 금지)
VITE_API_BASE_URL=http://localhost:8083
VITE_EXT_API_BASE_URL=http://localhost:8083
VITE_APP_ENV=development
```

### 2.3 개발 서버 실행

```bash
cd idem-console/frontend

# Vite dev server (HMR 지원)
npm run dev
# → http://localhost:3001

# TypeScript 타입 검사 (빌드 전 반드시 실행)
npx tsc --noEmit

# 프로덕션 빌드
npm run build
npm run preview  # 빌드 결과 미리보기
```

### 2.4 IDE 권장 설정 (VS Code)

```json
// .vscode/settings.json
{
  "typescript.tsdk": "node_modules/typescript/lib",
  "editor.formatOnSave": true,
  "editor.defaultFormatter": "esbenp.prettier-vscode",
  "eslint.validate": ["typescript", "typescriptreact"]
}
```

---

## 3. 디렉토리 구조 및 모듈 규칙

### 3.1 전체 구조

```
idem-console/frontend/src/
├── api/                        # API 클라이언트
│   ├── ErrorResponseHandler.ts # 에러 응답 정규화
│   ├── beInstance.ts           # 내부 axios 인스턴스
│   ├── extInstance.ts          # 외부(ext) axios 인스턴스
│   ├── feSession.ts            # feSession / SLO API ★ Sprint 10
│   ├── utils.ts                # Logout / clearLocalAuthState ★ Sprint 10
│   ├── browser/                # localStorage / cookie 유틸
│   ├── ext/
│   │   └── members.ts          # 회원/기업 CRUD API ★ Sprint 10
│   ├── features/               # 기능별 API (licenses 등)
│   └── ...
├── components/                 # 공통 컴포넌트
│   ├── ErrorBoundary/          # ★ Sprint 10 신규
│   │   └── index.tsx
│   ├── MypageSideNav/          # ★ Sprint 10 로그아웃 버튼 추가
│   │   └── index.tsx
│   ├── KrdsModal/              # KRDS 디자인 시스템 모달
│   ├── MypageContent/
│   └── ...
├── hooks/                      # 커스텀 훅
│   ├── useAuthState.ts         # ★ Sprint 10 신규 (인증 상태 + SLO)
│   └── ...
├── pages/                      # 페이지 컴포넌트
│   └── Mypage/
│       └── pages/
│           ├── InformationStep3.tsx  # ★ Sprint 10 실제 API 연동
│           ├── useInfoStore.ts       # 회원정보 Context 스토어
│           └── routes.ts
├── store/                      # Redux 스토어
│   ├── index.ts
│   └── reducers/
│       └── app.ts              # isLoggedIn, user 상태
├── types/                      # TypeScript 타입 정의
│   ├── api/
│   │   ├── index.ts            # SuccessResponse, ErrorResponse
│   │   └── ext/
│   │       └── members.ts      # ★ Sprint 10 UpdateMemberRequest 등
│   ├── common/
│   │   └── index.ts            # SuccessStatusCode = 200 | 201
│   └── reducer/
│       └── app.ts              # User, AppState 타입
└── constants/
    ├── routes.ts               # ROUTES 상수
    └── localStorage.ts         # LOCALSTORAGE 키 상수
```

### 3.2 모듈 추가 규칙

| 위치 | 용도 | 예시 |
|------|------|------|
| `api/ext/` | IdO ext API 호출 함수 | `members.ts`, `settings.ts` |
| `api/` (루트) | 세션/인증 관련 | `feSession.ts`, `utils.ts` |
| `hooks/` | 재사용 커스텀 훅 | `useAuthState.ts` |
| `components/` | 공통 UI 컴포넌트 | `ErrorBoundary/`, `Modal.tsx` |
| `pages/` | 라우트별 페이지 | `Mypage/pages/` |
| `types/api/ext/` | API 요청/응답 타입 | `members.ts` |

---

## 4. 상태 관리

### 4.1 Redux 구조 (legacy_createStore + thunk)

기존 Redux 구조를 그대로 유지합니다. Zustand/Recoil 등 신규 라이브러리는 도입하지 않습니다.

```typescript
// store/reducers/app.ts 핵심 상태
interface AppReducerState {
    isLoggedIn: boolean;
    user: User | null;
    org: Org[];
    role: string | null;
    // ...
}

// 액션 타입 (types/actions/app.ts)
LOGGED_IN                           // 로그인 상태 변경
UPDATE_USER                         // 사용자 정보 업데이트
UPDATE_ORG                          // 조직 정보 업데이트
UPDATE_USER_ACCESS_REFRESH_ACCESS_TOKEN  // JWT 토큰 업데이트
UPDATE_USER_ORG_ROLE               // 조직 역할 업데이트
```

### 4.2 useSelector 사용 패턴

```typescript
import { useSelector } from 'react-redux';
import type { AppState } from 'store/reducers';

// ✅ 권장: useAuthState 훅 사용 (Sprint 10 신규)
import useAuthState from 'hooks/useAuthState';
const { isLoggedIn, user, logout } = useAuthState();

// ✅ 직접 selector 사용 (특수한 경우)
const isLoggedIn = useSelector((state: AppState) => state.app.isLoggedIn);
const user = useSelector((state: AppState) => state.app.user);
```

### 4.3 InfoStoreContext (마이페이지 전용)

마이페이지 섹션은 별도의 Context(`InfoStoreContext`)를 사용합니다.

```typescript
// useInfoStore.ts에서 제공하는 인터페이스
interface InfoStoreContextType {
    member: MemberInfo;      // 개인회원 정보
    business: BusinessInfo;  // 기업회원 정보
    updateMember: (data: Partial<MemberInfo>) => void;    // 낙관적 업데이트
    updateBusiness: (data: Partial<BusinessInfo>) => void; // 낙관적 업데이트
}

// 사용법 (마이페이지 컴포넌트 내부)
const { member, business, updateMember, updateBusiness } = useInfoStore();
```

**`MemberInfo` 필드 구조 (useInfoStore.ts)**

```typescript
interface MemberInfo {
    id: string;           // 로그인 ID
    name: string;         // 이름
    phone1: string;       // "010"
    phone2: string;       // "1234"
    phone3: string;       // "5678"
    email1: string;       // "user"
    email2: string;       // "example.com"
    mbrNo: string;        // 회원번호 (API 호출 키)
    mbrSttsCd: string;    // 상태코드
    mbrSttsNm: string;    // 상태명
    ssoLastLoginDt: string;
    clients: MemberClient[];
}
```

---

## 5. API 레이어 설계

### 5.1 두 가지 Axios 인스턴스

```typescript
// api/beInstance.ts — 내부 BE API (JWT Bearer)
// Authorization: Bearer <accessJwt>
const beInstance = axios.create({ baseURL: VITE_API_BASE_URL });

// api/extInstance.ts — ext API (feSessionId 쿠키 기반)
// withCredentials: true → 자동으로 feSessionId 쿠키 포함
const extInstance = axios.create({
    baseURL: VITE_EXT_API_BASE_URL,
    withCredentials: true,
});
```

### 5.2 통일된 응답 타입 (types/api/index.ts)

```typescript
// SuccessStatusCode는 200 | 201만 허용 (204 불가!)
type SuccessStatusCode = 200 | 201;

interface SuccessResponse<T> {
    statusCode: SuccessStatusCode;
    error: null;
    message: string;
    payload: T;
}

interface ErrorResponse {
    statusCode: number;
    error: string;
    message: string;
    payload: null;
}
```

> ⚠️ **중요**: `statusCode: 204`를 반환하면 TypeScript 타입 오류 발생.  
> SLO처럼 서버가 204를 반환해도 FE에서는 `statusCode: 200`으로 매핑합니다.  
> (`api/feSession.ts`에서 `statusCode: 200` 반환 — Sprint 10 수정사항)

### 5.3 ErrorResponseHandler

```typescript
// api/ErrorResponseHandler.ts
export function ErrorResponseHandler(error: AxiosError): ErrorResponse {
    if (error.response) {
        return {
            statusCode: error.response.status,
            error: String(error.response.data?.error ?? 'API_ERROR'),
            message: String(error.response.data?.message ?? '요청 처리 중 오류가 발생했습니다.'),
            payload: null,
        };
    }
    return {
        statusCode: 0,
        error: 'NETWORK_ERROR',
        message: '네트워크 오류가 발생했습니다.',
        payload: null,
    };
}
```

### 5.4 API 함수 성공/실패 판별 패턴

```typescript
// ✅ 권장 패턴: error 필드로 판별
const result = await updateMember(mbrNo, requestBody);

if (result.error !== null) {
    // 실패 처리
    setErrorModal({ open: true, message: result.message || '오류가 발생했습니다.' });
    return;
}

// 성공 처리
// result.payload에 응답 데이터 있음
updateMemberStore({ name: result.payload.data.memberName });
```

---

## 6. 인증 흐름 및 SLO

### 6.1 SLO(Single Logout) 전체 흐름

```
사용자: 로그아웃 버튼 클릭
    │
    ▼
MypageSideNav → logout() (useAuthState 제공)
    │
    ▼
api/utils.ts: Logout()
    │
    ├─① initiateSlo() — POST /api/v1/slo/initiate (best-effort)
    │      ↓
    │   IdO: feSession Redis 삭제
    │      → q-sign: Keycloak 세션 종료
    │      → 기관 Webhook Outbox 적재
    │      → 감사 로그 기록
    │
    ├─② clearLocalAuthState() — 항상 실행 (SLO 성공·실패 무관)
    │      → localStorage 키 삭제
    │      → Redux store 초기화
    │
    └─③ history.push(ROUTES.LOGIN) — 로그인 페이지 이동
```

### 6.2 api/utils.ts Logout 구현 (Sprint 10)

```typescript
// api/utils.ts

/**
 * 로컬 상태 초기화 — Redux + localStorage 클리어
 * SLO 성공·실패 무관하게 항상 실행 (best-effort 정책)
 */
const clearLocalAuthState = (): void => {
    // localStorage 클리어
    deleteLocalStorageKey(LOCALSTORAGE.AUTH_TOKEN);
    deleteLocalStorageKey(LOCALSTORAGE.IS_LOGGED_IN);
    deleteLocalStorageKey(LOCALSTORAGE.IS_IDENTIFIED_USER);
    deleteLocalStorageKey(LOCALSTORAGE.REFRESH_AUTH_TOKEN);
    deleteLocalStorageKey(LOCALSTORAGE.LOGGED_IN_USER_EMAIL);
    deleteLocalStorageKey(LOCALSTORAGE.LOGGED_IN_USER_NAME);
    deleteLocalStorageKey(LOCALSTORAGE.CHAT_SUPPORT);

    // Redux store 초기화
    store.dispatch({ type: LOGGED_IN, payload: { isLoggedIn: false } });
    store.dispatch({ type: UPDATE_USER_ORG_ROLE, payload: { org: null, role: null } });
    store.dispatch({ type: UPDATE_USER, payload: { ...EMPTY_USER } });
    store.dispatch({ type: UPDATE_USER_ACCESS_REFRESH_ACCESS_TOKEN,
                    payload: { accessJwt: '', refreshJwt: '' } });
    store.dispatch({ type: UPDATE_ORG, payload: { org: [] } });
};

/**
 * 전체 로그아웃 (SLO — best-effort)
 * API 실패 시에도 로컬 정리 + 로그인 이동 보장
 */
export const Logout = (): void => {
    // best-effort: 실패해도 catch로 삼킴
    initiateSlo().catch(() => {});
    clearLocalAuthState();
    history.push(ROUTES.LOGIN);
};
```

### 6.3 feSession.ts — initiateSlo

```typescript
// api/feSession.ts
export const initiateSlo = async (): Promise<
    SuccessResponse<null> | ErrorResponse
> => {
    try {
        await axios.post('/api/v1/slo/initiate', {}, { withCredentials: true });
        return {
            statusCode: 200,  // ← 서버는 204 반환하지만 타입 호환 위해 200
            error: null,
            message: 'SLO completed',
            payload: null,
        };
    } catch (error) {
        return ErrorResponseHandler(error as AxiosError);
    }
};
```

---

## 7. useAuthState 훅

### 7.1 개요 (Sprint 10 신규)

Redux 스토어의 인증 상태를 편리하게 접근하고, SLO 로그아웃을 통합하는 훅입니다.

```typescript
// hooks/useAuthState.ts
export interface AuthState {
    isLoggedIn: boolean;       // Redux 기반 로그인 상태
    user: User | null;         // 로그인한 사용자 정보
    email: string;             // 편의 접근자
    name: string;              // 편의 접근자
    orgId: string;             // 조직 ID
    logout: () => void;        // SLO 통합 로그아웃
}

export function useAuthState(): AuthState {
    const isLoggedIn = useSelector((state: AppState) => state.app.isLoggedIn);
    const user = useSelector((state: AppState) => state.app.user ?? null);

    return {
        isLoggedIn,
        user,
        email: user?.email ?? '',
        name: user?.name ?? '',
        orgId: user?.orgId ?? '',
        logout: Logout,
    };
}
```

### 7.2 사용 예시

```typescript
// 기본 사용
import useAuthState from 'hooks/useAuthState';

function MyComponent(): JSX.Element {
    const { isLoggedIn, user, name, logout } = useAuthState();

    if (!isLoggedIn) {
        return <div>로그인이 필요합니다.</div>;
    }

    return (
        <div>
            <p>안녕하세요, {name}님</p>
            <button onClick={logout}>로그아웃</button>
        </div>
    );
}

// 조건부 렌더링
function Header(): JSX.Element {
    const { isLoggedIn, email } = useAuthState();
    return (
        <header>
            {isLoggedIn ? <span>{email}</span> : <a href="/login">로그인</a>}
        </header>
    );
}
```

### 7.3 AuthState 확장 방법

새 인증 상태 정보가 필요한 경우:

```typescript
// 1. User 타입 확장 (types/reducer/app.ts)
interface User {
    // 기존 필드들...
    newField: string;  // ← 새 필드 추가
}

// 2. Redux 액션에서 업데이트 (UPDATE_USER 액션 처리)
// 로그인 성공 시 dispatch({ type: UPDATE_USER, payload: { newField: 'value' } })

// 3. useAuthState 반환값에 추가 (hooks/useAuthState.ts)
export interface AuthState {
    // 기존 필드들...
    newField: string;  // ← 추가
}

export function useAuthState(): AuthState {
    // ...
    return {
        // 기존 반환값들...
        newField: user?.newField ?? '',  // ← 추가
    };
}
```

---

## 8. ErrorBoundary

### 8.1 개요 (Sprint 10 신규)

React 렌더링 오류를 격리하여 전체 앱이 흰 화면이 되는 것을 방지합니다.

```typescript
// components/ErrorBoundary/index.tsx
interface Props {
    children: ReactNode;
    /** 커스텀 폴백 UI (미제공 시 ErrorBoundaryFallback 사용) */
    fallback?: ReactNode;
    /** 에러 발생 시 콜백 (로깅·모니터링) */
    onError?: (error: Error, info: ErrorInfo) => void;
}
```

### 8.2 사용 패턴

```tsx
import ErrorBoundary from 'components/ErrorBoundary';

// 1. 기본 사용 (기본 폴백 UI)
<ErrorBoundary>
    <SomePage />
</ErrorBoundary>

// 2. 커스텀 폴백
<ErrorBoundary fallback={<p className="error-msg">이 섹션을 불러올 수 없습니다.</p>}>
    <SomeSection />
</ErrorBoundary>

// 3. 에러 콜백 (Sentry 등 외부 모니터링)
<ErrorBoundary
    onError={(error, info) => {
        Sentry.captureException(error, { extra: info });
    }}
>
    <CriticalPage />
</ErrorBoundary>

// 4. 마이페이지 섹션별 격리 (권장)
<ErrorBoundary fallback={<p>회원정보를 불러올 수 없습니다.</p>}>
    <InformationStep3 />
</ErrorBoundary>
```

### 8.3 ErrorBoundary와 async 에러

> ⚠️ **주의**: ErrorBoundary는 **렌더링 중 발생한 동기 에러**만 포착합니다.  
> `async/await` API 호출 오류는 ErrorBoundary가 잡지 않습니다.

```typescript
// ❌ ErrorBoundary가 포착하지 못함
const handleSubmit = async () => {
    const result = await updateMember(mbrNo, body);
    throw new Error('API 오류');  // ← 렌더링 중 아님
};

// ✅ 올바른 처리: try-catch로 직접 처리
const handleSubmit = async () => {
    try {
        const result = await updateMember(mbrNo, body);
        if (result.error !== null) {
            setErrorModal({ open: true, message: result.message });
        }
    } catch {
        setErrorModal({ open: true, message: '일시적인 오류가 발생했습니다.' });
    }
};
```

---

## 9. 마이페이지 — 회원정보 수정

### 9.1 Sprint 10 변경사항 요약

`InformationStep3.tsx`의 `handleSubmit`이 **개발 안내 모달**에서 **실제 API 호출**로 전면 교체되었습니다.

| 항목 | Sprint 9 이전 | Sprint 10 이후 |
|------|--------------|----------------|
| 저장 버튼 동작 | `setDevNoticeModal(true)` — 개발 중 안내만 표시 | `updateMember()` / `updateEnterprise()` 실제 API 호출 |
| 성공 처리 | 없음 | Context 낙관적 업데이트 + INFORMATION 페이지 이동 |
| 실패 처리 | 없음 | 에러 모달 표시 |
| 중복 제출 방지 | 없음 | `isSubmitting` 상태, 버튼 disabled |

### 9.2 폼 데이터 수집 방식 (비제어 컴포넌트 + FormData)

```typescript
// InformationStep3.tsx — formRef를 통한 FormData 수집
const formRef = useRef<HTMLFormElement>(null);

const handleSubmit = async (): Promise<void> => {
    if (isSubmitting || !formRef.current) return;
    const fd = new FormData(formRef.current);
    const get = (name: string): string =>
        (fd.get(name) as string | null)?.trim() ?? '';

    // 폼 필드 값 수집
    const memberName = get('name');      // input[name="name"]
    const phone1 = get('phone1');        // input[name="phone1"]
    const phone2 = get('phone2');        // input[name="phone2"]
    const email1 = get('email1');        // input[name="email1"]
    const email2 = get('email2');        // input[name="email2"]
    // ...
};
```

### 9.3 헬퍼 함수 (buildPhoneNumber / buildEmail)

```typescript
// InformationStep3.tsx 내부 헬퍼 함수

/**
 * 지역코드 + 뒷번호 → "010-1234-5678" 형식
 * 둘 중 하나라도 비어있으면 undefined 반환
 */
function buildPhoneNumber(prefix: string, rest: string): string | undefined {
    const p = prefix.trim();
    const r = rest.trim().replace(/-/g, '');
    if (!p || !r) return undefined;
    const mid = r.length > 7 ? r.slice(0, 4) : r.slice(0, 3);
    const last = r.slice(mid.length);
    return last ? `${p}-${mid}-${last}` : undefined;
}

/**
 * 이메일 로컬 + 도메인 → "user@example.com" 형식
 * 둘 중 하나라도 비어있으면 undefined 반환
 */
function buildEmail(local: string, domain: string): string | undefined {
    const l = local.trim();
    const d = domain.trim();
    if (!l || !d) return undefined;
    return `${l}@${d}`;
}
```

### 9.4 개인회원 수정 handleSubmit 전체 흐름

```typescript
const handleSubmit = async (): Promise<void> => {
    if (isSubmitting || !formRef.current) return;
    const fd = new FormData(formRef.current);
    const get = (name: string): string =>
        (fd.get(name) as string | null)?.trim() ?? '';

    setIsSubmitting(true);

    try {
        // ── 개인회원 수정 ─────────────────────────────────────
        const memberName = get('name');
        if (!memberName) {
            setErrorModal({ open: true, message: '이름은 필수 항목입니다.' });
            return;
        }

        const phone = buildPhoneNumber(get('phone1'), get('phone2'));
        const email = buildEmail(get('email1'), get('email2'));

        const result = await updateMember(member.mbrNo, {
            memberName,
            ...(phone && { phone }),
            ...(email && { email }),
        });

        if (result.error !== null) {
            setErrorModal({
                open: true,
                message: result.message || '회원정보 수정에 실패했습니다.',
            });
            return;
        }

        // 낙관적 Context 업데이트 (API 성공 후 즉시 UI 반영)
        const [email1, email2] = email
            ? email.split('@')
            : [member.email1, member.email2];
        const [phone1, , phone2] = phone
            ? phone.split('-')
            : [member.phone1, member.phone2, member.phone3];

        updateMemberStore({
            name: memberName,
            email1: email1 ?? member.email1,
            email2: email2 ?? member.email2,
            phone1: phone1 ?? member.phone1,
            // ...
        });

        // 성공 후 INFORMATION 페이지 이동
        history.push(infoRoute);

    } catch {
        setErrorModal({ open: true, message: '일시적인 오류가 발생했습니다.' });
    } finally {
        setIsSubmitting(false);
    }
};
```

### 9.5 isSubmitting 중복 제출 방지

```tsx
// 저장 버튼 disabled 처리
<button
    type="button"
    onClick={handleSubmit}
    disabled={isSubmitting}
    className={`btn large primary ${isSubmitting ? 'disabled' : ''}`}
>
    {isSubmitting ? '저장 중...' : '저장'}
</button>

// 이전 버튼도 비활성화
<button
    type="button"
    onClick={() => history.goBack()}
    disabled={isSubmitting}
    className="btn large secondary"
>
    이전
</button>
```

### 9.6 InfoStoreContext 낙관적 업데이트 패턴

```typescript
// API 성공 후 즉시 UI 반영 (서버 재조회 없이)
// useInfoStore.ts의 updateMember/updateBusiness 함수가 localStorage도 동기화

// 개인회원 업데이트
updateMemberStore({
    name: memberName,
    email1: emailLocal,
    email2: emailDomain,
    phone1: '010',
    phone2: '1234',
    phone3: '5678',
});

// 기업회원 업데이트
updateBusiness({
    company_name: bzmnNm,
    name: rprsvNm,
    email1: emailLocal,
    email2: emailDomain,
    tel1: '02',
    tel2: '1234',
    tel3: '5678',
});
```

### 9.7 새 필드 추가 방법

회원정보 수정에 새 필드를 추가할 때 수정해야 할 파일 목록:

```
1. types/api/ext/members.ts
   → UpdateMemberRequest 또는 UpdateEnterpriseRequest에 선택적 필드 추가

2. api/ext/members.ts
   → updateMember() / updateEnterprise()는 body를 그대로 전달하므로 수정 불필요

3. pages/Mypage/pages/InformationStep3.tsx
   → 해당 form input에 name 속성 확인
   → handleSubmit의 get('field_name') 호출로 값 수집
   → API 호출 body에 새 필드 포함

4. pages/Mypage/pages/useInfoStore.ts
   → MemberInfo / BusinessInfo 인터페이스에 필드 추가
   → updateMemberStore() 호출 시 새 필드 포함

5. 백엔드 (Q-IM 팀과 협의)
   → UpdateMemberRequest DTO에 동일 필드 추가
   → user_profile 테이블에 컬럼 추가 (Flyway)
```

---

## 10. MypageSideNav 로그아웃 버튼

### 10.1 Sprint 10 변경사항

`MypageSideNav`에 실제 로그아웃 버튼 UI가 추가되었습니다.

```tsx
// components/MypageSideNav/index.tsx (핵심 부분)
import useAuthState from 'hooks/useAuthState';

function MypageSideNav({ section, memberType }: MypageSideNavProps): JSX.Element {
    const { logout } = useAuthState();
    // ...

    return (
        <div className="sub-nav">
            {/* ... 기존 네비게이션 ... */}

            {/* Sprint 10 신규: 로그아웃 버튼 */}
            <div className="nav-logout">
                <button
                    type="button"
                    className="btn logout-btn"
                    onClick={logout}
                    aria-label="로그아웃"
                >
                    <span className="material-symbols-outlined">logout</span>
                    로그아웃
                </button>
            </div>
        </div>
    );
}
```

### 10.2 로그아웃 UI 커스터마이징

```tsx
// 로그아웃 버튼 스타일 변경이 필요한 경우
// components/MypageSideNav/index.tsx 수정

// 확인 모달 추가 (선택)
const handleLogout = (): void => {
    if (window.confirm('로그아웃 하시겠습니까?')) {
        logout();
    }
};

// ← logout()을 직접 호출 대신 handleLogout 사용
<button onClick={handleLogout}>로그아웃</button>
```

---

## 11. 타입 시스템 설계

### 11.1 SuccessStatusCode 제약

```typescript
// types/common/index.ts
export type SuccessStatusCode = 200 | 201;
// ← 204는 허용하지 않음!

// types/api/index.ts
export interface SuccessResponse<T> {
    statusCode: SuccessStatusCode;  // 200 또는 201만 가능
    error: null;
    message: string;
    payload: T;
}
```

**타입 오류 예시 및 해결**:

```typescript
// ❌ 타입 오류 — 204는 SuccessStatusCode 아님
return {
    statusCode: 204,  // Type '204' is not assignable to type 'SuccessStatusCode'
    error: null,
    message: 'SLO completed',
    payload: null,
};

// ✅ 수정 — 200으로 변경
return {
    statusCode: 200,
    error: null,
    message: 'SLO completed',
    payload: null,
};
```

### 11.2 회원 타입 계층

```typescript
// types/api/ext/members.ts

// 조회 응답 타입
MemberData        → MemberResponse (success + data)
EnterpriseData    → EnterpriseResponse

// 수정 요청 타입 (Sprint 10 신규)
UpdateMemberRequest     // { memberName, phone?, email? }
UpdateEnterpriseRequest // { bzmnNm, rprsvNm, rprsTelno?, email? }

// UI 상태 타입 (useInfoStore.ts)
MemberInfo        // 개인회원 UI 상태 (phone1/phone2/phone3로 분리)
BusinessInfo      // 기업회원 UI 상태 (tel1/tel2/tel3로 분리)
```

### 11.3 타입 가드 패턴

```typescript
// API 응답이 성공인지 실패인지 판별
function isSuccessResponse<T>(
    res: SuccessResponse<T> | ErrorResponse
): res is SuccessResponse<T> {
    return res.error === null;
}

// 사용 예
const result = await updateMember(mbrNo, body);
if (isSuccessResponse(result)) {
    // result.payload 타입이 MemberResponse로 자동 추론
    updateMemberStore({ name: result.payload.data.memberName });
} else {
    // result.message, result.statusCode 접근
    setErrorModal({ open: true, message: result.message });
}
```

---

## 12. 새 API 함수 추가 방법

### 12.1 ext API 추가 패턴 (IdO 외부 API)

```typescript
// 1. types/api/ext/ 에 요청/응답 타입 추가
// types/api/ext/settings.ts (예시)
export interface UpdateSettingsRequest {
    theme: 'light' | 'dark';
    language: 'ko' | 'en';
}

export interface SettingsData {
    theme: string;
    language: string;
    updatedAt: string;
}

export interface SettingsResponse {
    success: boolean;
    data: SettingsData;
}

// 2. api/ext/ 에 API 함수 추가
// api/ext/settings.ts
import { ErrorResponseHandler } from 'api/ErrorResponseHandler';
import extInstance from 'api/extInstance';
import { AxiosError } from 'axios';
import { ErrorResponse, SuccessResponse } from 'types/api';
import type { UpdateSettingsRequest, SettingsResponse } from 'types/api/ext/settings';

export const updateSettings = async (
    mbrNo: string,
    body: UpdateSettingsRequest,
): Promise<SuccessResponse<SettingsResponse> | ErrorResponse> => {
    try {
        const response = await extInstance.patch(`/api/ext/members/${mbrNo}/settings`, body);
        return {
            statusCode: 200,
            error: null,
            message: 'success',
            payload: response.data,
        };
    } catch (error) {
        return ErrorResponseHandler(error as AxiosError);
    }
};

// 3. 컴포넌트에서 사용
import { updateSettings } from 'api/ext/settings';

const result = await updateSettings(mbrNo, { theme: 'dark', language: 'ko' });
if (result.error !== null) { /* 에러 처리 */ }
```

### 12.2 내부 BE API 추가 패턴 (beInstance 사용)

```typescript
// api/features/admin.ts (예시: beInstance 사용)
import beInstance from 'api/beInstance';

export const getAdminStats = async (): Promise<...> => {
    try {
        const response = await beInstance.get('/api/v1/admin/stats');
        return { statusCode: 200, error: null, message: 'success', payload: response.data };
    } catch (error) {
        return ErrorResponseHandler(error as AxiosError);
    }
};
```

---

## 13. 새 마이페이지 섹션 추가 방법

### 13.1 라우트 추가 (routes.ts)

```typescript
// pages/Mypage/pages/routes.ts
export type MypageSection =
    | 'information'
    | 'affiliation'
    | 'password'
    | 'withdraw'
    | 'new-section'; // ← 새 섹션 추가

export function getMypageRoute(
    memberType: MypageMemberType,
    section: MypageSection,
): string {
    const prefix = memberType === 'business' ? 'business' : 'member';
    return `/mypage/${prefix}/${section}`;
}
```

### 13.2 ROUTES 상수 추가 (constants/routes.ts)

```typescript
// constants/routes.ts
const ROUTES = {
    // 기존 라우트들...
    MYPAGE_MEMBER_NEW_SECTION: '/mypage/member/new-section',
    MYPAGE_BUSINESS_NEW_SECTION: '/mypage/business/new-section',
};
```

### 13.3 컴포넌트 파일 생성

```typescript
// pages/Mypage/pages/NewSection.tsx
import { useInfoStore } from './useInfoStore';
import { useMypageType } from 'components/MypageLayout';

function NewSection(): JSX.Element {
    const memberType = useMypageType();
    const { member, business } = useInfoStore();

    return (
        <div>
            {/* 섹션 내용 */}
        </div>
    );
}

export default NewSection;
```

### 13.4 SideNav에 메뉴 추가

```typescript
// components/MypageSideNav/index.tsx
const SECTION_LABEL: Record<MypageSection, string> = {
    information: '나의 정보',
    affiliation: '유관기관 서비스 관리',
    password: '비밀번호 수정',
    withdraw: '통합회원 탈퇴',
    'new-section': '새 섹션',  // ← 추가
};
```

---

## 14. TypeScript 컴파일 검증

### 14.1 검증 방법

```bash
cd idem-console/frontend

# 전체 타입 검사 (빌드 없이)
npx tsc --noEmit

# 특정 파일만 검사
npx tsc --noEmit src/api/feSession.ts src/hooks/useAuthState.ts

# 검사 결과 파일로 저장
npx tsc --noEmit 2>&1 | tee /tmp/ts-errors.txt
```

### 14.2 Pre-existing 오류 목록 (Sprint 10 이전부터 존재)

다음 오류들은 **환경 문제**로, Sprint 10 작업과 무관합니다.  
신규 기능 개발 시 이 오류들이 새로 추가되지 않았는지 확인하세요.

```
Cannot find module 'react-redux'    → 기존 환경 문제 (Vite 빌드에서는 정상)
Cannot find module 'react-i18next'  → 기존 환경 문제
Cannot find module 'sentry'         → 기존 환경 문제
```

### 14.3 Sprint 10 추가 파일 관련 검증 포인트

```bash
# Sprint 10에서 추가/수정한 파일 타입 검증
npx tsc --noEmit \
  src/types/api/ext/members.ts \
  src/api/ext/members.ts \
  src/api/feSession.ts \
  src/api/utils.ts \
  src/hooks/useAuthState.ts \
  src/components/ErrorBoundary/index.tsx \
  src/components/MypageSideNav/index.tsx \
  src/pages/Mypage/pages/InformationStep3.tsx
```

### 14.4 타입 오류 빠른 해결 가이드

| 오류 유형 | 원인 | 해결 |
|---------|------|------|
| `Type '204' is not assignable to type 'SuccessStatusCode'` | statusCode에 204 사용 | `200`으로 변경 |
| `Property 'X' does not exist on type 'MemberInfo'` | MemberInfo에 필드 없음 | `useInfoStore.ts`에 필드 추가 |
| `Argument of type 'string \| undefined' is not assignable to 'string'` | 옵셔널 값 미처리 | `?? ''` 또는 null 체크 추가 |
| `Cannot find module 'api/ext/members'` | 경로 별칭 오류 | `tsconfig.json`의 paths 확인 |

---

## 15. 자주 발생하는 오류 및 해결

### Case 1: API 호출 후 에러 모달이 뜨지 않음

**원인**: `result.error !== null` 체크가 빠진 경우

```typescript
// ❌ 문제
const result = await updateMember(mbrNo, body);
history.push(infoRoute);  // 오류 무시

// ✅ 수정
const result = await updateMember(mbrNo, body);
if (result.error !== null) {
    setErrorModal({ open: true, message: result.message });
    return;
}
history.push(infoRoute);
```

### Case 2: FormData에서 값을 가져오지 못함

**원인**: `input[name]` 속성이 없거나 폼 ref가 연결되지 않음

```tsx
// ✅ 확인사항
<form ref={formRef}>
    {/* name 속성 필수! */}
    <input name="name" defaultValue={member.name} />
    <input name="phone1" defaultValue={member.phone1} />
</form>

// FormData에서 값 가져오기
const fd = new FormData(formRef.current!);
const name = (fd.get('name') as string | null)?.trim() ?? '';
```

### Case 3: 낙관적 업데이트 후 페이지 이동 시 이전 값 표시

**원인**: `useInfoStore`의 `updateMember()`가 localStorage를 동기화하지 않은 경우

```typescript
// useInfoStore.ts의 updateMember가 localStorage도 저장하는지 확인
const updateMember = (data: Partial<MemberInfo>): void => {
    setMember(prev => {
        const next = { ...prev, ...data };
        saveToStorage({ member: next, business });  // ← localStorage 동기화
        return next;
    });
};
```

### Case 4: SLO 후 로그인 페이지로 이동하지 않음

**원인**: `history.push`가 React Router와 연결되지 않은 경우

```typescript
// api/utils.ts의 history 임포트 확인
import history from 'lib/history';
// lib/history.ts: createBrowserHistory() 로 생성되어야 함

// App.tsx/Router에서 동일한 history 객체 사용 확인
<Router history={history}>
```

### Case 5: isSubmitting이 false로 돌아오지 않음

**원인**: `finally` 블록 미사용으로 에러 시 isSubmitting이 true로 고착

```typescript
// ❌ 문제 — 에러 발생 시 isSubmitting이 true로 고착
setIsSubmitting(true);
const result = await updateMember(mbrNo, body);
setIsSubmitting(false);  // 에러 throw 시 도달 불가

// ✅ 수정 — finally 블록 사용
setIsSubmitting(true);
try {
    const result = await updateMember(mbrNo, body);
    // ...
} catch {
    setErrorModal({ open: true, message: '오류가 발생했습니다.' });
} finally {
    setIsSubmitting(false);  // 항상 실행
}
```

---

## 16. 코딩 컨벤션 및 체크리스트

### 16.1 컴포넌트 작성 규칙

```typescript
// ✅ 함수형 컴포넌트 (named function 선언 방식)
function MyComponent({ prop1, prop2 }: Props): JSX.Element {
    return <div />;
}
export default MyComponent;

// ✅ JSX.Element 반환 타입 명시
// ✅ Props 인터페이스 별도 선언

// ❌ 피해야 할 패턴
const MyComponent = () => <div />;  // 화살표 함수 컴포넌트 지양
export default function() { ... }   // 익명 함수 export 금지
```

### 16.2 API 호출 규칙

```typescript
// ✅ try-catch-finally 패턴
// ✅ finally에서 isSubmitting = false
// ✅ result.error !== null로 실패 판별
// ✅ ErrorResponseHandler로 오류 정규화
// ✅ statusCode: 200 | 201 만 사용 (204 금지)

// ❌ 금지
// throw new Error() — 대신 setErrorModal 사용
// statusCode: 204
// 직접 console.error로 오류 처리 (에러 모달 사용)
```

### 16.3 PR 제출 전 체크리스트

```
□ npx tsc --noEmit 실행 — 신규 타입 오류 없음 확인
□ 새 API 함수는 try-catch로 ErrorResponseHandler 처리
□ 성공 응답에 statusCode: 200 또는 201 사용 (204 금지)
□ 비동기 함수에서 isSubmitting 패턴 + finally 사용
□ useAuthState 훅을 통해 인증 상태 접근 (직접 useSelector 최소화)
□ 새 타입은 types/api/ext/ 또는 types/common/에 추가
□ 컴포넌트에서 직접 API URL 하드코딩 금지 (api/ 레이어 사용)
□ ErrorBoundary로 새 페이지/섹션 감싸기 (렌더링 오류 격리)
□ FormData 수집 시 input[name] 속성 확인
□ 낙관적 업데이트 후 Context 동기화 확인
```

### 16.4 절대 금지 사항

```
❌ CI, 주민번호 등 PII 원본 데이터를 FE에서 저장/표시
❌ feSessionId 쿠키를 JavaScript로 직접 읽기 (HttpOnly)
❌ 내부 API(X-Internal-Api-Key) 직접 호출
❌ statusCode: 204 반환 (SuccessStatusCode 타입 위반)
❌ redux store.dispatch를 컴포넌트에서 직접 호출 (훅 사용)
❌ localStorage에 JWT 토큰 외 민감 정보 저장
❌ API 함수 안에서 window.alert() 호출
❌ 에러를 무시하고 계속 진행 (에러 모달 표시 필수)
```

---

*이전 문서: [guide-backend-qim.md](guide-backend-qim.md)*  
*다음 문서: [guide-infra.md](guide-infra.md)*  
*관련 문서: [07-module-frontend.md](07-module-frontend.md) · [09-api-spec.md](09-api-spec.md)*
