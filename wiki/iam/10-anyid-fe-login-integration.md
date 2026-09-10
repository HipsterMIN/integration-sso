# IAM-10: onepass-fe Any-ID 로그인 화면 연동 가이드

> **대상 독자**: 프론트엔드 개발자, fullstack 개발자  
> **선행 문서**: [IAM-09: Any-ID 설치형 SDK 통합 가이드](./09-anyid-sdk-integration.md)  
> **적용 버전**: anyid-auth-sdk-1.0.19 (JS SDK 포함), onepass-fe React/TypeScript  
> **기관**: 중소기업기술정보진흥원 (기관번호 #311, srvc_no=`1000001157`)  
> **최종 업데이트**: 2026-05-19

---

## 목차

1. [현황 분석 — 지금 무슨 상태인가?](#1-현황-분석--지금-무슨-상태인가)
2. [Q-Net 화면 분석 — 목표 UX](#2-q-net-화면-분석--목표-ux)
3. [전체 아키텍처 — 데이터 흐름](#3-전체-아키텍처--데이터-흐름)
4. [구현 방법 선택 — A안 vs B안](#4-구현-방법-선택--a안-vs-b안)
5. [Step 1: JS SDK 정적 파일 배치](#5-step-1-js-sdk-정적-파일-배치)
6. [Step 2: useAnyIdAuth 훅 신규 생성](#6-step-2-useanyidauth-훅-신규-생성)
7. [Step 3: AnyIdLoginModal 컴포넌트 신규 생성](#7-step-3-anyidloginmodal-컴포넌트-신규-생성)
8. [Step 4: Login.tsx 수정 — Any-ID 버튼 실제 연결](#8-step-4-logintsx-수정--any-id-버튼-실제-연결)
9. [Step 5: 환경변수 및 webpack 설정 추가](#9-step-5-환경변수-및-webpack-설정-추가)
10. [Step 6: anyidAdaptor 콜백과 BE 연동](#10-step-6-anyidadaptor-콜백과-be-연동)
11. [Step 7: 인증 완료 → Keycloak 세션 연결](#11-step-7-인증-완료--keycloak-세션-연결)
12. [전체 시퀀스 다이어그램](#12-전체-시퀀스-다이어그램)
13. [환경별 설정 체크리스트](#13-환경별-설정-체크리스트)
14. [트러블슈팅 & FAQ](#14-트러블슈팅--faq)

---

## 1. 현황 분석 — 지금 무슨 상태인가?

### 1.1 현재 onepass-fe Login.tsx 상태

`idem-console/frontend/src/pages/Login/index.tsx`의 인증수단 목록을 보면:

```tsx
// ✅ 실제 동작 — NICE 휴대폰 인증
<button onClick={startPhoneAuth} disabled={phoneAuthBusy}>
  <strong>휴대폰 인증</strong>
</button>

// ✅ 실제 동작 — OACX EasySign 간편인증서 (팝업 방식)
<button onClick={startEasyAuth}>
  <strong>개인 간편인증서</strong>
</button>

// ❌ 미구현 — 개발 중 모달만 표시
<button onClick={(): void => setDevNoticeModal(true)}>
  <strong>공동인증서</strong>
</button>

// ❌ 미구현 — 개발 중 모달만 표시  ← 이것이 우리가 구현할 대상
<button onClick={(): void => setDevNoticeModal(true)}>
  <img src={IMAGES.RENEWAL_CERT_ANY} alt="" aria-hidden="true" />
  <strong>Any-ID</strong>
</button>
```

**문제**: Any-ID 버튼을 클릭하면 `setDevNoticeModal(true)`만 실행되어 "서비스 준비 중" 모달이 표시됨.  
`AnyidC.LOAD_MODULE()` 초기화 코드가 없으므로, 인증 UI 자체가 렌더링되지 않는 상태.

### 1.2 미구현인 이유

Any-ID SDK의 JS 파일(`manifest.js`, `vendor.js`, `app.js`)은 일반 npm 패키지가 아니라 **기관 서버에서 정적 파일로 서빙되어야 하는 번들**이다.  
`AuthResourceInstall.zip`의 `webapp/` 폴더에 포함된 이 파일들을 서버에 배치하고,  
`AnyidC.LOAD_MODULE()`를 호출하는 코드를 React 컴포넌트에 추가해야 비로소 동작한다.

### 1.3 다른 인증수단과의 차이

| 인증수단 | 방식 | 상태 |
|---------|------|------|
| 휴대폰 인증 (NICE) | `useNicePhoneAuth` 훅 → NICE API 팝업 | ✅ 구현됨 |
| 개인 간편인증서 (OACX) | `usePersonalEasyAuth` 훅 → EasySign 팝업 → `postMessage` | ✅ 구현됨 |
| 공동인증서 | 미구현 | ❌ |
| **Any-ID** | `AnyidC.LOAD_MODULE()` → 인라인 UI 렌더링 → 콜백 | ❌ 구현 필요 |

---

## 2. Q-Net 화면 분석 — 목표 UX

### 2.1 Q-Net이 보여주는 것

첨부된 Q-Net 스크린샷에서 확인한 UI 구조:

```
┌─────────────────────────────────────────────────────┐
│  로그인 방식을 선택해주세요.                            │
│  정부 통합로그인은 한 번의 로그인으로...               │
│                                                     │
│  [정부 통합로그인  ●  사용]  ← anyidtoggle 토글       │
│  이미 정부 통합인증(Any-ID) 사용자이신가요? 사용자 등록  │
│                                                     │
│  ┌──────────────┐ ┌──────────────┐                  │
│  │ 📱 모바일신분증 │ │ 📲 간편인증   │                  │
│  │             │ │             │                  │
│  └──────────────┘ └──────────────┘                  │
│  ┌──────────────┐ ┌──────────────┐                  │
│  │ 🔐 공동인증서  │ │ 🏦 금융인증서  │                  │
│  │             │ │             │                  │
│  └──────────────┘ └──────────────┘                  │
│                                                     │
│  이용기관 로그인                                      │
│  [디지털원패스] [아이디 로그인] [비회원 로그인]          │
└─────────────────────────────────────────────────────┘
```

### 2.2 이 화면의 기술적 정체

Q-Net이 보여주는 저 UI는 **`AnyidC.LOAD_MODULE()`이 `<div id="anyidc">` 컨테이너에 렌더링한 결과**다.  
SDK가 로드되면서 JSON 설정(`config.anyidc.json`)에 정의된 인증수단 목록을 읽어 카드 UI를 자동으로 그린다.

SDK 샘플(`webapp/sample/login.jsp`)의 핵심 코드:

```html
<!-- 1. SDK JS 파일 로드 -->
<link href="/anyid/css/app.css" rel="stylesheet">
<script src="/anyid/js/manifest.js"></script>
<script src="/anyid/js/vendor.js"></script>
<script src="/anyid/js/app.js"></script>

<!-- 2. anyidAdaptor 정의 (JSP include) -->
<jsp:include page="anyidAdaptor.jsp" />

<!-- 3. 두 개의 빈 컨테이너 -->
<div id="anyidtoggle"></div>   ← 정부 통합로그인 토글
<div id="anyidc"></div>        ← 인증수단 카드 목록
```

```javascript
// 4. DOM 로드 후 SDK 초기화
document.addEventListener("DOMContentLoaded", function() {
    AnyidC.LOAD_MODULE({
        cfg: "/config/config.anyidc.json",   // 인증수단 목록 JSON
        txId: params.get("tx"),              // 거래ID
        tag: params.get("tx"),               // 거래태그
        lvl: 3,                              // 인증수준 (1~3)
        bypass: ssoByPass,                   // SSO 우회 여부 (0/1)
        theme: "4.1.0",                      // UI 테마
        toggle: true,                        // 토글 버튼 표시 여부
        success: function(data) {
            anyidAdaptor.success(data);      // 인증 성공 콜백
        },
        fail: function(err) { console.log(err); },
        log: function(data) { console.log(data); }
    });
});
```

### 2.3 success 콜백이 받는 data 구조

인증 성공 시 `anyidAdaptor.success(data)`로 넘어오는 `data` 객체:

```javascript
{
  ssob: "eyJhbGciOiJBUklBL...",  // ARIA-CBC-256 암호화된 인증 결과
  txId: "20260519-abc123",        // 거래ID
  tag: "20260519-abc123",         // 거래태그 (txId와 동일)
  userSeCd: "IND",                // 사용자 구분 (IND=개인, ENT=기업)
  afData: null,                   // 부가 데이터
  useSso: true                    // SSO 경유 여부
}
```

---

## 3. 전체 아키텍처 — 데이터 흐름

### 3.1 현재 (미구현) vs 목표 (구현 후)

```
[현재]
Login.tsx의 Any-ID 버튼
    └─ onClick → setDevNoticeModal(true) → "서비스 준비 중" 모달


[목표 — 구현 후]
Login.tsx의 Any-ID 버튼
    └─ onClick → AnyIdLoginModal 열기
                    │
                    ▼
        <div id="anyidtoggle"></div>  ← 토글 버튼
        <div id="anyidc"></div>       ← AnyidC.LOAD_MODULE() 렌더링
                    │
          사용자가 인증수단 선택
          (모바일신분증/간편인증/공동인증서/금융인증서)
                    │ 인증 완료
                    ▼
        anyidAdaptor.success(data)
            │
            ├─ [SSO 모드] anyidAdaptor.ssoLogin()
            │       └─ GET /api/v1/anyid/oidc/ssoLogin?data={base64}
            │               └─ AnyIdController → AnyIdSsobService.decryptSsob()
            │               └─ NonOidcAuthService.processAuth()
            │               └─ FeSessionService.create() → fe_session 쿠키
            │               └─ Keycloak redirect → 로그인 완료
            │
            └─ [기관 자체 로그인 모드] anyidAdaptor.orgLogin(data)
                    └─ POST /api/v1/anyid/{provider}/ssob
                            └─ AnyIdController → AnyIdSsobService.decryptSsob()
                            └─ NonOidcAuthService.processAuth()
                            └─ FeSessionService.create() → fe_session 쿠키
                            └─ easyAuthFormRef.submit() → Keycloak action_url POST
```

### 3.2 integration-sso의 역할

```
onepass-fe (React SPA)
    │ SDK JS 파일을 /anyid/js/ 경로로 요청
    ▼
ido 모듈 (Spring Boot, 포트 8083)
    │ /anyid/js/** → 정적 파일 서빙
    │ /config/config.anyidc.json → 인증수단 목록 서빙
    │
    │ POST /api/v1/anyid/{provider}/ssob → AnyIdController
    │ GET /api/v1/anyid/oidc/ssoLogin   → AnyIdController
    ▼
KMS (https://www.anyid.dev:8119)
    ARIA-CBC-256 복호화 → {ci, authLvl, name, brdt}
```

---

## 4. 구현 방법 선택 — A안 vs B안

### 방법 A: 모달 레이어에 SDK 렌더링 (권장)

Any-ID 버튼 클릭 → 현재 Login.tsx 위에 모달/오버레이로 `AnyidC.LOAD_MODULE()` UI 표시

```
Login.tsx (배경)
    ├─ 기존 로그인 폼
    └─ AnyIdLoginModal (오버레이)
           ├─ <div id="anyidtoggle">
           └─ <div id="anyidc">  ← SDK가 여기에 카드 UI 렌더링
```

**장점**: 
- 기존 Login.tsx 변경 최소화
- `actionUrl` 등 기존 상태값 유지 가능
- 취소 시 로그인 화면 복귀 자연스러움

**단점**:
- SDK가 렌더링할 때 `document.getElementById('anyidc')` 기준으로 DOM 접근 → 모달이 마운트된 후 SDK 초기화 필요

### 방법 B: 전용 라우트 페이지 (별도 페이지)

`/anyid-login` 라우트를 새로 만들어, Q-Net처럼 전체 페이지를 Any-ID 전용으로 사용

```
/login → Login.tsx (Any-ID 버튼 → /anyid-login으로 navigate)
/anyid-login → AnyIdLoginPage.tsx (AnyidC.LOAD_MODULE() 전용 페이지)
```

**장점**:
- SDK와의 충돌 없음 (전용 DOM 환경)
- 추후 확장 용이

**단점**:
- `actionUrl` 등 Login 상태를 URL 파라미터로 전달해야 함
- 추가 라우트/컴포넌트 파일 필요

### ✅ 이 가이드에서 채택: 방법 A (모달 방식)

기존 코드 변경 최소, `actionUrl` 컨텍스트 유지가 가능하여 권장.  
단, SDK 초기화 타이밍 주의 필요 (Step 7 참조).

---

## 5. Step 1: JS SDK 정적 파일 배치

### 5.1 SDK 파일 위치 확인

`AuthResourceInstall.zip`을 해제하면 다음 구조가 있다:

```
AuthResourceInstall/
└── webapp/
    ├── anyid/
    │   ├── css/
    │   │   └── app.css        ← SDK UI 스타일시트
    │   └── js/
    │       ├── manifest.js    ← SDK 모듈 매니페스트
    │       ├── vendor.js      ← SDK 서드파티 번들
    │       └── app.js         ← SDK 코어 (AnyidC 전역 객체 포함)
    ├── config/
    │   └── config.anyidc.json ← 인증수단 목록 (이미 integration-sso에 적용됨)
    └── sample/
        └── login.jsp          ← 참고용 샘플
```

### 5.2 ido 모듈에 정적 파일 서빙 설정 추가

> **참고**: Spring Boot는 기본적으로 `src/main/resources/static/` 하위를 정적으로 서빙한다.

**파일 복사**:
```bash
# integration-sso/ido 모듈 기준
mkdir -p idem-hub/src/main/resources/static/anyid/css
mkdir -p idem-hub/src/main/resources/static/anyid/js

# AuthResourceInstall에서 복사
cp /home/user/anyid-sdk/AuthResourceInstall/webapp/anyid/css/app.css \
   idem-hub/src/main/resources/static/anyid/css/

cp /home/user/anyid-sdk/AuthResourceInstall/webapp/anyid/js/manifest.js \
   /home/user/anyid-sdk/AuthResourceInstall/webapp/anyid/js/vendor.js \
   /home/user/anyid-sdk/AuthResourceInstall/webapp/anyid/js/app.js \
   idem-hub/src/main/resources/static/anyid/js/
```

### 5.3 config.anyidc.json 서빙 확인

`idem-hub/src/main/resources/static/config/config.anyidc.json`을 만들거나,  
`idem-hub/src/main/resources/config/anyid/config.anyidc.json`을 Spring MVC 컨트롤러로 노출한다.

> **이미 완료**: IAM-09 작업에서 `AnyIdController`에 config 조회 엔드포인트가 구현되어 있으므로  
> `cfg: "/api/v1/anyid/config"` 경로로 사용하거나, 정적 파일로 직접 서빙한다.

**정적 서빙 방식 (간단)**:
```bash
mkdir -p idem-hub/src/main/resources/static/config
cp idem-hub/src/main/resources/config/anyid/config.anyidc.json \
   idem-hub/src/main/resources/static/config/config.anyidc.json
```

이렇게 하면 `GET /config/config.anyidc.json` 요청에 자동 응답한다.

### 5.4 onepass-fe webpack proxy 설정 추가

개발 환경에서 `/anyid/**` 경로를 ido(8083)로 프록시:

**파일**: `idem-console/frontend/webpack.config.js`

```javascript
// 기존 proxy 설정에 추가
proxy: {
    '/api/ext': {
        target: process.env.IDO_BASE_URL || 'http://localhost:8083',
        changeOrigin: true,
        secure: false,
    },
    // ↓ 추가
    '/anyid': {
        target: process.env.IDO_BASE_URL || 'http://localhost:8083',
        changeOrigin: true,
        secure: false,
    },
    // ↓ 추가 (config.anyidc.json 서빙)
    '/config': {
        target: process.env.IDO_BASE_URL || 'http://localhost:8083',
        changeOrigin: true,
        secure: false,
    },
    '/api': {
        target: process.env.BE_API_TARGET || 'http://localhost:9292',
        // ...
    },
}
```

### 5.5 index.html에 SDK 스크립트 태그 추가

`idem-console/frontend/public/index.html` (또는 `index.ejs`):

```html
<head>
  <!-- 기존 태그들 -->
  
  <!-- Any-ID SDK CSS -->
  <link href="/anyid/css/app.css" rel="stylesheet">
</head>
<body>
  <div id="root"></div>
  
  <!-- Any-ID SDK JS (defer로 비동기 로드) -->
  <script defer src="/anyid/js/manifest.js"></script>
  <script defer src="/anyid/js/vendor.js"></script>
  <script defer src="/anyid/js/app.js"></script>
</body>
```

> **주의**: `defer` 속성으로 SDK를 비동기 로드한다.  
> 따라서 React 컴포넌트에서 `AnyidC`를 사용할 때는 `typeof window.AnyidC !== 'undefined'` 체크가 필요하다.

---

## 6. Step 2: useAnyIdAuth 훅 신규 생성

다른 인증수단(`usePersonalEasyAuth`, `useNicePhoneAuth`)과 동일한 패턴으로 훅을 만든다.

**파일**: `idem-console/frontend/src/hooks/useAnyIdAuth.ts`

```typescript
/**
 * Any-ID 정부 통합인증 SDK 훅
 *
 * 흐름:
 * 1. startAuth() → 모달 열기 → AnyidC.LOAD_MODULE() 초기화
 * 2. 사용자 인증수단 선택 → SDK가 anyidAdaptor.success(data) 콜백 호출
 * 3. 콜백에서 orgLogin() 또는 ssoLogin() 분기
 * 4. orgLogin: POST /api/v1/anyid/{provider}/ssob → CI 추출 → onSuccess 콜백
 * 5. ssoLogin: GET /api/v1/anyid/oidc/ssoLogin?data={base64} → 서버 리다이렉트
 */

import beInstance from 'api/beInstance';
import { useCallback, useEffect, useRef, useState } from 'react';

// SDK JS가 로드한 전역 객체 타입 선언
declare global {
  interface Window {
    AnyidC: {
      LOAD_MODULE: (options: AnyidcModuleOptions) => void;
    };
    anyidAdaptor: AnyidAdaptor;
  }
}

interface AnyidcModuleOptions {
  cfg: string;        // config.anyidc.json 경로
  txId: string;       // 거래 ID
  tag: string;        // 거래 태그 (= txId)
  lvl: number;        // 인증 수준 (1=L1, 2=L2, 3=L3)
  bypass: number;     // SSO 우회 여부 (0=SSO사용, 1=기관자체)
  theme: string;      // UI 테마 버전
  toggle: boolean;    // 정부 통합로그인 토글 버튼 표시 여부
  success: (data: AnyidSuccessData) => void;
  fail: (err: unknown) => void;
  log?: (data: unknown) => void;
}

interface AnyidAdaptor {
  ssoByPass: string | number;
  certData: AnyidSuccessData | null;
  agencyContextPath: string;
  orgLogin: (data: AnyidSuccessData) => void;
  ssoLogin: () => void;
  userCheck: () => void;
  success: (data: AnyidSuccessData) => void;
}

export interface AnyidSuccessData {
  ssob: string;        // ARIA-CBC-256 암호화 인증 결과
  txId: string;        // 거래 ID
  tag: string;         // 거래 태그
  userSeCd: string;    // 사용자 구분 (IND/ENT)
  afData: string | null;
  useSso: boolean;     // SSO 경유 여부
}

export interface AnyIdAuthResult {
  resultCode: string;  // "2000" = 성공
  resultMsg: string;
  ci?: string;         // 연계정보 (암호화)
  name?: string;
  birthday?: string;
}

/** 서버에서 발급한 txId를 가져오는 API (BE에서 거래 시작 API 구현 필요) */
const fetchTxId = async (): Promise<string> => {
  try {
    const res = await beInstance.post<{ txId: string }>('/api/v1/anyid/txId');
    return res.data.txId;
  } catch {
    // txId API 미구현 시 클라이언트에서 임시 생성 (개발 단계)
    return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
  }
};

interface UseAnyIdAuthOptions {
  /** Keycloak action_url — POST 전송 대상 */
  actionUrl: string | null;
  /** 인증 수준 (기본값: 2, L2) */
  authLevel?: number;
  /** SSO 우회 여부 (0=SSO경유, 1=기관자체로그인) */
  bypass?: number;
}

interface UseAnyIdAuthReturn {
  /** 인증 진행 중 여부 */
  busy: boolean;
  /** 모달 표시 여부 */
  showModal: boolean;
  /** Any-ID 인증 시작 (버튼 onClick에 연결) */
  startAuth: () => Promise<void>;
  /** 모달 닫기 */
  closeModal: () => void;
  /** SDK 초기화를 위해 모달이 마운트된 후 호출 */
  initSdk: () => void;
  /** 인증 완료 후 받은 encCi (easyAuthFormRef.submit()에 사용) */
  encCi: string;
}

function useAnyIdAuth(
  onSuccess: (result: AnyIdAuthResult) => void,
  onError: (message: string) => void,
  options: UseAnyIdAuthOptions,
): UseAnyIdAuthReturn {
  const { actionUrl, authLevel = 2, bypass = 0 } = options;

  const [busy, setBusy] = useState(false);
  const [showModal, setShowModal] = useState(false);
  const [encCi, setEncCi] = useState('');

  const txIdRef = useRef<string>('');
  const callbacksRef = useRef({ onSuccess, onError });

  useEffect(() => {
    callbacksRef.current = { onSuccess, onError };
  }, [onSuccess, onError]);

  /** 모달 닫기 & 상태 초기화 */
  const closeModal = useCallback((): void => {
    setShowModal(false);
    setBusy(false);
  }, []);

  /**
   * anyidAdaptor.orgLogin 구현 — 기관 자체 로그인 모드
   * ssob + tag를 BE에 POST → CI 추출 → 폼 제출
   */
  const handleOrgLogin = useCallback(async (data: AnyidSuccessData): Promise<void> => {
    try {
      const res = await beInstance.post<AnyIdAuthResult>(
        `/api/v1/anyid/ssob`,
        {
          ssob: data.ssob,
          tag: data.tag || txIdRef.current,
          txId: data.txId,
        },
      );

      const result = res.data;
      if (result.resultCode === '2000' && result.ci) {
        setEncCi(result.ci);
        callbacksRef.current.onSuccess(result);
      } else {
        callbacksRef.current.onError(`Any-ID 인증 실패: ${result.resultMsg}`);
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      callbacksRef.current.onError(msg);
    } finally {
      closeModal();
    }
  }, [closeModal]);

  /**
   * anyidAdaptor.ssoLogin 구현 — SSO 경유 로그인 모드
   * Base64 인코딩된 payload를 GET 파라미터로 ido에 전송
   * ido가 Keycloak 세션을 생성하고 returnUri로 리다이렉트
   */
  const handleSsoLogin = useCallback((certData: AnyidSuccessData): void => {
    const payload = {
      txId: certData.txId,
      ssob: certData.ssob,
      userSeCd: certData.userSeCd,
      afData: certData.afData,
    };
    const encodedString = btoa(JSON.stringify(payload));
    // ido 모듈의 GET /api/v1/anyid/oidc/ssoLogin 으로 리다이렉트
    window.location.href = `/api/v1/anyid/oidc/ssoLogin?data=${encodedString}`;
  }, []);

  /**
   * SDK 초기화
   * 모달의 #anyidc 컨테이너가 DOM에 마운트된 후 호출해야 한다.
   */
  const initSdk = useCallback((): void => {
    // SDK JS가 아직 로드되지 않은 경우 대기
    if (typeof window.AnyidC === 'undefined') {
      console.warn('[AnyId] AnyidC SDK가 아직 로드되지 않았습니다. 재시도합니다.');
      const retryTimer = setTimeout(() => initSdk(), 300);
      return () => clearTimeout(retryTimer);
    }

    window.AnyidC.LOAD_MODULE({
      cfg: '/config/config.anyidc.json',
      txId: txIdRef.current,
      tag: txIdRef.current,
      lvl: authLevel,
      bypass: bypass,
      theme: '4.1.0',
      toggle: true,
      success: (data: AnyidSuccessData) => {
        // anyidAdaptor.success() 로직 재구현
        if (bypass !== 0 || !data.useSso) {
          // SSO 우회 또는 SSO 미사용 → 기관 자체 로그인
          void handleOrgLogin(data);
        } else {
          // SSO 경유
          handleSsoLogin(data);
        }
      },
      fail: (err: unknown) => {
        console.error('[AnyId] 인증 실패:', err);
        callbacksRef.current.onError('Any-ID 인증에 실패했습니다. 다시 시도해주세요.');
        closeModal();
      },
      log: (data: unknown) => {
        if (process.env.NODE_ENV === 'development') {
          console.log('[AnyId SDK]', data);
        }
      },
    });
  }, [authLevel, bypass, handleOrgLogin, handleSsoLogin, closeModal]);

  /** Any-ID 인증 시작 */
  const startAuth = useCallback(async (): Promise<void> => {
    if (busy) return;
    setBusy(true);

    // txId 발급 (BE API 또는 임시 생성)
    txIdRef.current = await fetchTxId();

    // 모달 표시 → initSdk는 모달 마운트 후 호출됨
    setShowModal(true);
  }, [busy]);

  return { busy, showModal, startAuth, closeModal, initSdk, encCi };
}

export default useAnyIdAuth;
```

---

## 7. Step 3: AnyIdLoginModal 컴포넌트 신규 생성

**파일**: `idem-console/frontend/src/components/AnyIdLoginModal/index.tsx`

```tsx
/**
 * Any-ID 로그인 모달
 *
 * 내부에 SDK가 렌더링할 두 개의 컨테이너를 포함:
 * - #anyidtoggle: 정부 통합로그인 토글
 * - #anyidc: 인증수단 카드 목록 (AnyidC.LOAD_MODULE이 채움)
 */

import { useEffect } from 'react';

interface AnyIdLoginModalProps {
  isOpen: boolean;
  onClose: () => void;
  onInit: () => void;  // 컨테이너 마운트 후 SDK 초기화 트리거
}

function AnyIdLoginModal({ isOpen, onClose, onInit }: AnyIdLoginModalProps): JSX.Element | null {
  // 모달이 열릴 때마다 SDK를 초기화
  useEffect(() => {
    if (!isOpen) return;

    // DOM에 #anyidc가 실제로 존재하는지 확인 후 초기화
    // requestAnimationFrame으로 한 프레임 대기해 DOM 렌더링 보장
    const raf = requestAnimationFrame(() => {
      onInit();
    });
    return () => cancelAnimationFrame(raf);
  }, [isOpen, onInit]);

  if (!isOpen) return null;

  return (
    <div
      className="modal-overlay anyid-login-modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Any-ID 정부 통합로그인"
    >
      <div className="anyid-login-modal-content">
        {/* 헤더 */}
        <div className="anyid-modal-header">
          <h2 className="anyid-modal-title">로그인 방식을 선택해주세요.</h2>
          <p className="anyid-modal-desc">
            정부 통합로그인은 한 번의 로그인으로 연계된 모든 공공 웹서비스를 이용할 수 있는
            인증 서비스입니다.
          </p>
          <button
            type="button"
            className="btn-close"
            aria-label="모달 닫기"
            onClick={onClose}
          >
            <i className="icon ico-close" aria-hidden="true" />
          </button>
        </div>

        {/* SDK 렌더링 컨테이너 */}
        <div className="anyid-modal-body">
          {/* 정부 통합로그인 토글 — SDK가 채움 */}
          <div id="anyidtoggle" className="anyid-toggle-wrap"></div>
          {/* 인증수단 카드 목록 — SDK가 채움 */}
          <div id="anyidc" className="anyid-module-wrap"></div>
        </div>
      </div>
    </div>
  );
}

export default AnyIdLoginModal;
```

**파일**: `idem-console/frontend/src/components/AnyIdLoginModal/AnyIdLoginModal.styles.scss`

```scss
// Any-ID 로그인 모달 스타일
// SDK가 렌더링하는 #anyidc, #anyidtoggle의 레이아웃을 감싸는 래퍼 스타일

.anyid-login-modal-overlay {
  position: fixed;
  inset: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.anyid-login-modal-content {
  background: #fff;
  border-radius: 12px;
  width: 100%;
  max-width: 680px;
  max-height: 90vh;
  overflow-y: auto;
  position: relative;
  padding: 0;

  @media (max-width: 768px) {
    max-width: 100%;
    max-height: 100vh;
    border-radius: 0;
  }
}

.anyid-modal-header {
  padding: 28px 28px 0;
  position: relative;

  .anyid-modal-title {
    font-size: 1.25rem;
    font-weight: 700;
    margin-bottom: 8px;
  }

  .anyid-modal-desc {
    font-size: 0.875rem;
    color: #666;
    margin-bottom: 16px;
    line-height: 1.5;
  }

  .btn-close {
    position: absolute;
    top: 20px;
    right: 20px;
    background: none;
    border: none;
    cursor: pointer;
    padding: 4px;
    color: #333;

    &:hover {
      opacity: 0.7;
    }
  }
}

.anyid-modal-body {
  padding: 0 28px 28px;
}

.anyid-toggle-wrap {
  margin-bottom: 16px;
}

.anyid-module-wrap {
  min-height: 280px;  // SDK가 카드를 채울 공간 확보
}
```

---

## 8. Step 4: Login.tsx 수정 — Any-ID 버튼 실제 연결

### 8.1 변경 전후 비교

**변경 전** (`Login.tsx` line 580~600 근처):
```tsx
// import 없음, useAnyIdAuth 훅 없음

// 버튼 onClick
onClick={(): void => setDevNoticeModal(true)}
```

**변경 후**:

```tsx
// 1. import 추가 (파일 상단)
import useAnyIdAuth, { AnyIdAuthResult } from 'hooks/useAnyIdAuth';
import AnyIdLoginModal from 'components/AnyIdLoginModal';
```

```tsx
// 2. 훅 사용 — 기존 usePersonalEasyAuth 선언 아래에 추가
//    (encCi state는 기존 것을 재사용)

const handleAnyIdSuccess = useCallback(
  (result: AnyIdAuthResult): void => {
    if (result.resultCode === '2000' && result.ci) {
      setEncCi(result.ci);  // 기존 easyAuthFormRef를 재사용
    } else {
      setErrorTopText('Any-ID 인증 오류');
      setErrorTitle(result.resultMsg);
      setErrorMessage(result.resultMsg);
      setErrorModal(true);
    }
  },
  [],
);

const handleAnyIdError = useCallback(
  (message: string): void => {
    setErrorTopText('Any-ID 인증 오류');
    setErrorTitle('인증에 실패하였습니다');
    setErrorMessage(message);
    setErrorModal(true);
  },
  [],
);

const { busy: anyIdBusy, showModal: anyIdModal, startAuth: startAnyIdAuth, closeModal: closeAnyIdModal, initSdk: initAnyIdSdk } =
  useAnyIdAuth(handleAnyIdSuccess, handleAnyIdError, {
    actionUrl,
    authLevel: 2,  // L2 — 모바일신분증/간편인증 수준
    bypass: 0,     // 0 = SSO 경유 모드
  });
```

```tsx
// 3. JSX — 기존 Any-ID 버튼 수정
// 변경 전:
<button
  type="button"
  className="btn"
  aria-label="Any-ID로 로그인"
  onClick={(): void => setDevNoticeModal(true)}
>

// 변경 후:
<button
  type="button"
  className="btn"
  aria-label="Any-ID 정부 통합로그인"
  onClick={startAnyIdAuth}
  disabled={anyIdBusy}
>
```

```tsx
// 4. JSX — AnyIdLoginModal 추가 (기존 Modal 컴포넌트들 아래)
{/* Any-ID 로그인 모달 */}
<AnyIdLoginModal
  isOpen={anyIdModal}
  onClose={closeAnyIdModal}
  onInit={initAnyIdSdk}
/>
```

### 8.2 완전한 Login.tsx 수정 코드 (diff 형식)

```diff
// 파일: idem-console/frontend/src/pages/Login/index.tsx

  import './Login.styles.scss';
  
  import Modal from 'components/KrdsModal';
+ import AnyIdLoginModal from 'components/AnyIdLoginModal';
  import IMAGES from 'constants/images';
  import ROUTES from 'constants/routes';
  import type { EzAuthBizResult } from 'hooks/useEzAuth';
  import useEzAuth from 'hooks/useEzAuth';
  import useNicePhoneAuth, { NicePhoneAuthResult } from 'hooks/useNicePhoneAuth';
  import usePersonalEasyAuth, { EasysignResult } from 'hooks/usePersonalEasyAuth';
+ import useAnyIdAuth, { AnyIdAuthResult } from 'hooks/useAnyIdAuth';
  import history from 'lib/history';
  import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';

  // ... (기존 코드 유지)

  // 기존 휴대폰 인증 훅 선언 아래에 추가:
+ // Any-ID 정부 통합인증 훅
+ const handleAnyIdSuccess = useCallback(
+   (result: AnyIdAuthResult): void => {
+     if (result.resultCode === '2000' && result.ci) {
+       setEncCi(result.ci);
+     } else {
+       setErrorTopText('Any-ID 인증 오류');
+       setErrorTitle(result.resultMsg);
+       setErrorMessage(result.resultMsg);
+       setErrorModal(true);
+     }
+   },
+   [],
+ );
+ 
+ const handleAnyIdError = useCallback(
+   (message: string): void => {
+     setErrorTopText('Any-ID 인증 오류');
+     setErrorTitle('인증에 실패하였습니다');
+     setErrorMessage(message);
+     setErrorModal(true);
+   },
+   [],
+ );
+ 
+ const {
+   busy: anyIdBusy,
+   showModal: anyIdModal,
+   startAuth: startAnyIdAuth,
+   closeModal: closeAnyIdModal,
+   initSdk: initAnyIdSdk,
+ } = useAnyIdAuth(handleAnyIdSuccess, handleAnyIdError, {
+   actionUrl,
+   authLevel: 2,
+   bypass: 0,
+ });

  // JSX return 블록 내부:
  // 기존 devNoticeModal Modal 아래에 추가:
+ <AnyIdLoginModal
+   isOpen={anyIdModal}
+   onClose={closeAnyIdModal}
+   onInit={initAnyIdSdk}
+ />

  // Any-ID 버튼:
- onClick={(): void => setDevNoticeModal(true)}
+ onClick={startAnyIdAuth}
+ disabled={anyIdBusy}
```

---

## 9. Step 5: 환경변수 및 webpack 설정 추가

### 9.1 .env 파일 추가 항목

**파일**: `idem-console/frontend/.env` (또는 `.env.local`)

```bash
# 기존 환경변수
EASYSIGN_URL=https://easysign.anyid.go.kr/esign
EASYSIGN_ORIGIN=https://easysign.anyid.go.kr

# Any-ID SDK 설정 추가
IDO_BASE_URL=http://localhost:8083

# Any-ID SSO 설정
# bypass=0: SSO 경유 로그인 (행안부 Any-ID SSO 서버 경유)
# bypass=1: 기관 자체 로그인 (ssob를 직접 POST 처리)
ANYID_BYPASS=0

# Any-ID 인증 수준
# 1=L1 (소셜/간편), 2=L2 (모바일신분증/금융인증서), 3=L3 (공동인증서)
ANYID_AUTH_LEVEL=2
```

### 9.2 webpack.config.js 수정

```javascript
// idem-console/frontend/webpack.config.js
// DefinePlugin에 환경변수 추가

plugins: [
  new webpack.DefinePlugin({
    'process.env.EASYSIGN_URL': JSON.stringify(process.env.EASYSIGN_URL || ''),
    'process.env.EASYSIGN_ORIGIN': JSON.stringify(process.env.EASYSIGN_ORIGIN || ''),
    // 추가
    'process.env.ANYID_BYPASS': JSON.stringify(process.env.ANYID_BYPASS || '0'),
    'process.env.ANYID_AUTH_LEVEL': JSON.stringify(process.env.ANYID_AUTH_LEVEL || '2'),
    'process.env.IDO_BASE_URL': JSON.stringify(process.env.IDO_BASE_URL || ''),
  }),
  // ... 기존 플러그인들
]
```

---

## 10. Step 6: anyidAdaptor 콜백과 BE 연동

### 10.1 orgLogin 모드 (bypass=1, 또는 SSO 미사용)

`useAnyIdAuth.ts`의 `handleOrgLogin()`이 다음 API를 호출한다:

```
POST /api/v1/anyid/ssob
Content-Type: application/json

{
  "ssob": "eyJhbGciOiJBUklBL...",
  "tag": "20260519-abc123",
  "txId": "20260519-abc123"
}
```

**ido 모듈의 기존 엔드포인트** (`AnyIdController.java`):

```java
// IAM-09에서 이미 구현됨
@PostMapping("/{provider}/ssob")
public ResponseEntity<?> handleSsob(
    @PathVariable String provider,
    @RequestBody AnyIdSsobRequest request,
    HttpServletResponse response
) {
    // 1. AnyIdSsobService.decryptSsob() → CI 추출
    // 2. NonOidcAuthService.processAuth() → auth_result DB + Kafka
    // 3. FeSessionService.create() → fe_session 쿠키
    // 4. { resultCode: "2000", ci: encCi } 반환
}
```

> **⚠️ 경로 정렬**: FE는 `/api/v1/anyid/ssob`로 요청하지만,  
> 기존 BE는 `/api/v1/anyid/{provider}/ssob` (provider 포함)로 구현되어 있다.  
> 두 가지 방법:
> 1. FE에서 provider를 포함한 경로로 요청 (`/api/v1/anyid/any/ssob`)
> 2. BE에 provider 없는 엔드포인트 추가 (`@PostMapping("/ssob")`)
>
> **권장**: ssob 복호화는 provider 무관(tag로 구분)하므로 BE에 provider-agnostic 엔드포인트 추가.

### 10.2 BE에 통합 ssob 엔드포인트 추가

**파일**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/broker/anyid/AnyIdController.java`

기존 `@PostMapping("/{provider}/ssob")` 외에 다음 추가:

```java
/**
 * Any-ID 통합 ssob 복호화 엔드포인트 (provider 무관)
 * FE의 useAnyIdAuth.handleOrgLogin()에서 호출
 */
@PostMapping("/ssob")
public ResponseEntity<AnyIdSsobResponse> handleSsobUnified(
    @RequestBody AnyIdSsobRequest request,
    HttpServletResponse response
) {
    // provider를 "any"로 고정하거나, tag/txId로 provider 판별
    return handleSsob("any", request, response);
}
```

### 10.3 ssoLogin 모드 (bypass=0, SSO 경유)

`useAnyIdAuth.ts`의 `handleSsoLogin()`이 직접 브라우저 리다이렉트를 수행한다:

```javascript
// FE에서 실행
const payload = { txId, ssob, userSeCd, afData };
window.location.href = `/api/v1/anyid/oidc/ssoLogin?data=${btoa(JSON.stringify(payload))}`;
```

**ido 모듈의 기존 엔드포인트** (`AnyIdController.java`):

```java
// IAM-09에서 이미 구현됨
@GetMapping("/oidc/ssoLogin")
public ResponseEntity<?> handleOidcSsoLogin(
    @RequestParam String data,
    HttpServletResponse response
) {
    // 1. Base64 decode → { txId, ssob, userSeCd, afData }
    // 2. AnyIdSsobService.decryptSsob(ssob, tag) → CI
    // 3. NonOidcAuthService.processAuth()
    // 4. FeSessionService.create() → fe_session 쿠키 Set-Cookie
    // 5. Keycloak action_url로 redirect
}
```

---

## 11. Step 7: 인증 완료 → Keycloak 세션 연결

### 11.1 orgLogin 완료 후 처리

`useAnyIdAuth.onSuccess(result)` → `Login.tsx`의 `handleAnyIdSuccess()` → `setEncCi(result.ci)`

```tsx
// Login.tsx — 기존 코드 (변경 불필요)
// 간편인증/휴대폰인증과 동일한 form submit 방식 재사용

// 간편인증 결과 수신 → form POST 전송
useEffect(() => {
  if (encCi && actionUrl && easyAuthFormRef.current) {
    easyAuthFormRef.current.submit();  // ← Any-ID 성공 시에도 동일하게 동작
  }
}, [encCi, actionUrl]);
```

```html
<!-- easyAuthFormRef가 가리키는 숨겨진 폼 (변경 불필요) -->
<form
  ref={easyAuthFormRef}
  method="POST"
  action={actionUrl || ''}
  style={{ display: 'none' }}
>
  <input type="hidden" name="loginType" value="IND_CI" />
  <input type="hidden" name="encCi" value={encCi} />  <!-- CI가 여기 채워짐 -->
</form>
```

**흐름**:
```
handleOrgLogin() → POST /api/v1/anyid/ssob → { resultCode: "2000", ci: "encrypted_ci..." }
     ↓
setEncCi("encrypted_ci...")
     ↓
useEffect [encCi, actionUrl] 트리거
     ↓
easyAuthFormRef.current.submit()
     ↓
POST {action_url} loginType=IND_CI&encCi=encrypted_ci...
     ↓
Keycloak이 처리 → 로그인 완료 → returnUri로 리다이렉트
```

### 11.2 ssoLogin 완료 후 처리

브라우저가 `/api/v1/anyid/oidc/ssoLogin?data=...`로 이동하면,  
ido 서버가 처리 완료 후 Keycloak `action_url`로 POST redirect한다.

```
브라우저 GET /api/v1/anyid/oidc/ssoLogin?data={base64}
     ↓
ido: Base64 decode → decryptSsob → processAuth → FeSession 쿠키
     ↓
ido: response.sendRedirect(keycloakActionUrl + "?loginType=IND_CI&encCi=...")
     ↓
Keycloak: 세션 생성 → returnUri로 리다이렉트
     ↓
사용자: 원래 서비스 페이지로 복귀
```

### 11.3 txId API 구현 (BE)

현재 `useAnyIdAuth.ts`의 `fetchTxId()`는 `/api/v1/anyid/txId`를 호출한다.  
이 API가 없으면 클라이언트 생성 txId(임시)를 사용한다.

**권장**: ido에 txId 발급 API 추가:

```java
// AnyIdController.java에 추가
@PostMapping("/txId")
public ResponseEntity<Map<String, String>> issueTxId() {
    // 고유한 거래 ID 생성 (날짜 + UUID 조합)
    String txId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    return ResponseEntity.ok(Map.of("txId", txId));
}
```

---

## 12. 전체 시퀀스 다이어그램

### 12.1 orgLogin 모드 (기관 자체 로그인)

```
사용자       onepass-fe (Login.tsx)    ido (8083)           KMS          Keycloak
  │                 │                     │                  │              │
  │  Any-ID 버튼 클릭 │                     │                  │              │
  │──────────────►  │                     │                  │              │
  │                 │ startAnyIdAuth()    │                  │              │
  │                 │ POST /api/v1/anyid/txId               │              │
  │                 │────────────────────►│                  │              │
  │                 │◄────────────────────│ {txId}           │              │
  │                 │                     │                  │              │
  │                 │ setShowModal(true)  │                  │              │
  │                 │ AnyIdLoginModal 마운트                 │              │
  │                 │ requestAnimationFrame → initSdk()     │              │
  │                 │ AnyidC.LOAD_MODULE({cfg, txId, lvl})  │              │
  │                 │────────────────────►│                  │              │
  │                 │ GET /config/config.anyidc.json        │              │
  │                 │◄────────────────────│ 인증수단 목록      │              │
  │                 │                     │                  │              │
  │  인증수단 선택   │                     │                  │              │
  │  (모바일신분증)  │                     │                  │              │
  │──────────────►  │                     │                  │              │
  │                 │ 인증 진행 (SDK 내부 처리)               │              │
  │  인증 완료       │                     │                  │              │
  │──────────────►  │                     │                  │              │
  │                 │ success(data)       │                  │              │
  │                 │ handleOrgLogin(data)│                  │              │
  │                 │ POST /api/v1/anyid/ssob               │              │
  │                 │────────────────────►│                  │              │
  │                 │                     │ AnyidCertRef     │              │
  │                 │                     │ .decryptSsob()   │              │
  │                 │                     │─────────────────►│              │
  │                 │                     │◄─────────────────│ {ci, authLvl}│
  │                 │                     │ NonOidcAuthService.processAuth()│
  │                 │                     │ FeSessionService.create()       │
  │                 │◄────────────────────│ {resultCode:"2000", ci:"..."}  │
  │                 │ setEncCi(ci)        │                  │              │
  │                 │ easyAuthFormRef.submit()              │              │
  │                 │ POST {action_url}   │                  │              │
  │                 │ loginType=IND_CI   │                  │              │
  │                 │                     │                  │             ►│
  │                 │                     │                  │              │ 세션 생성
  │◄────────────────│                     │                  │              │
  │  returnUri로 리다이렉트                                                  │
```

### 12.2 ssoLogin 모드 (SSO 경유)

```
사용자       onepass-fe              ido (8083)          Any-ID SSO      Keycloak
  │                 │                   │                    │               │
  │  (인증 완료)    │                   │                    │               │
  │──────────────►  │                   │                    │               │
  │                 │ success(data)     │                    │               │
  │                 │ {useSso: true}    │                    │               │
  │                 │ handleSsoLogin()  │                    │               │
  │                 │ window.location.href =               │               │
  │                 │ /api/v1/anyid/oidc/ssoLogin?data={b64}               │
  │──────────────►  │                   │                    │               │
  │                 │ GET /api/v1/anyid/oidc/ssoLogin       │               │
  │                 │──────────────────►│                    │               │
  │                 │                   │ Base64 decode      │               │
  │                 │                   │ decryptSsob()      │               │
  │                 │                   │ processAuth()      │               │
  │                 │                   │ FeSession 쿠키     │               │
  │                 │                   │ redirect(action_url)              │
  │◄────────────────│                   │                    │               │
  │  GET action_url │                   │                    │              ►│
  │                 │                   │                    │               │ 세션 생성
  │◄────────────────│                   │                    │               │
  │  returnUri로 리다이렉트                                                   │
```

---

## 13. 환경별 설정 체크리스트

### 13.1 개발 환경 체크리스트

```bash
# ✅ 1. SDK 정적 파일 배치 확인
ls idem-hub/src/main/resources/static/anyid/js/
# → manifest.js  vendor.js  app.js

ls idem-hub/src/main/resources/static/anyid/css/
# → app.css

ls idem-hub/src/main/resources/static/config/
# → config.anyidc.json

# ✅ 2. ido 서버 기동 확인
curl http://localhost:8083/anyid/js/app.js | head -5
# → SDK JS 응답 확인

curl http://localhost:8083/config/config.anyidc.json
# → {"list":[...], "organization":{...}} 확인

# ✅ 3. FE webpack proxy 확인 (FE 기동 후)
curl http://localhost:3301/anyid/js/app.js | head -5
# → proxy를 통해 ido로 포워딩되는지 확인

# ✅ 4. AnyidC 전역 객체 확인 (브라우저 콘솔)
# /login 접속 후:
# > typeof window.AnyidC  → "object" (SDK 로드 성공)
# > typeof window.AnyidC  → "undefined" (SDK 로드 실패 → 경로 확인)
```

### 13.2 운영 환경 배포 체크리스트

| 항목 | 확인 방법 | 예상 결과 |
|------|----------|----------|
| SDK JS 파일 배포 | `curl https://onepass.smes.go.kr/anyid/js/app.js` | 200 응답 |
| config.anyidc.json | `curl https://onepass.smes.go.kr/config/config.anyidc.json` | JSON 응답 |
| ssob POST 엔드포인트 | `curl -X POST /api/v1/anyid/ssob` | 인증 오류 (정상 동작) |
| kdist-api.json 경로 | ido 로그에서 `@PostConstruct` 성공 확인 | `kdistAbsPath` 로그 출력 |
| KMS 연결 | ido 로그에서 AnyidCertRef 호출 성공 | ssob 복호화 성공 |
| CSP 헤더 | Any-ID SDK가 로드하는 외부 URL 허용 필요 | `*.anyid.go.kr` 허용 |

### 13.3 config.anyidc.json 인증수단별 경로 매핑

```json
{
  "list": [
    {
      "name": "mip-install",
      "level": 2,
      "urls": {
        "config": "/api/v1/anyid/mip/config",
        "extract": "/api/v1/anyid/mobile-id/ssob"
      }
    },
    {
      "name": "fincert-install",
      "level": 2,
      "urls": {
        "accInfo": "/api/v1/anyid/financial-cert/accInfo",
        "extract": "/api/v1/anyid/financial-cert/ssob"
      }
    },
    {
      "name": "anysignlite-install",
      "level": 2,
      "urls": {
        "accInfo": "/api/v1/anyid/joint-cert/accInfo",
        "extract": "/api/v1/anyid/joint-cert/ssob"
      }
    },
    {
      "name": "esign-install",
      "level": 2,
      "urls": {
        "config": "/api/v1/anyid/easy-sign/esign-config",
        "extract": "/api/v1/anyid/easy-sign/ssob"
      }
    },
    {
      "name": "social-relay",
      "level": 3,
      "urls": {
        "provider": "https://www.anyid.dev:1443/pid/auth.do",
        "extract": "/api/v1/anyid/pid/ssob"
      }
    }
  ],
  "organization": {
    "srvcNo": "1000001157",
    "agencyCode": "1000001157",
    "agencyName": "중소벤처24기업마당"
  }
}
```

> **중요**: `"extract"` URL은 SDK가 인증 후 자동으로 POST 요청을 보내는 경로다.  
> 이 경로들이 ido의 Spring Controller에 매핑되어 있어야 한다.  
> (IAM-09에서 이미 구현 완료)

---

## 14. 트러블슈팅 & FAQ

### Q1. `window.AnyidC is not defined` 오류

**원인**: SDK JS 파일(`app.js`)이 로드되기 전에 React 컴포넌트가 마운트됨.

**해결**:
1. `public/index.html`에 `<script defer src="/anyid/js/app.js">` 확인
2. `useAnyIdAuth.ts`의 `initSdk()` 내 재시도 로직 확인:
   ```typescript
   if (typeof window.AnyidC === 'undefined') {
     const retryTimer = setTimeout(() => initSdk(), 300);
     return () => clearTimeout(retryTimer);
   }
   ```
3. 브라우저 Network 탭에서 `/anyid/js/app.js` 요청 상태 확인 (404 → proxy 설정 오류)

### Q2. `#anyidc` 컨테이너에 아무것도 렌더링되지 않음

**원인 A**: `config.anyidc.json` 로드 실패

**확인**:
```bash
curl http://localhost:8083/config/config.anyidc.json
```
→ 404 응답 시 정적 파일 경로 확인

**원인 B**: `txId` 형식 오류

**확인**: SDK가 요구하는 txId 형식이 있는지 `config.anyidc.etc.json` 참조.  
임시 생성 txId(`Date.now()` 기반)가 유효하지 않으면 BE에서 발급받아야 함.

**원인 C**: 모달이 렌더링되기 전에 `AnyidC.LOAD_MODULE()`이 호출됨

**해결**: `AnyIdLoginModal`의 `useEffect` 내 `requestAnimationFrame` 사용 확인.

### Q3. ssob 복호화 실패 (`AnyidCertRef.decryptSsob()` 오류)

**원인 A**: `kdist-api.json` 경로 오류

**확인**:
```bash
# ido 서버 로그에서:
grep "kdistAbsPath" idem-hub/logs/*.log
# → 경로가 출력되는지, 해당 파일이 존재하는지 확인
```

**원인 B**: BouncyCastle 라이브러리 충돌

**확인**:
```bash
# idem-hub/libs/에 bcprov-jdk15to18 JAR가 없는지 확인 (있으면 제거)
ls idem-hub/libs/ | grep bc
# → anyid-bc-ref-1.0.2.jar 만 있어야 함
```

### Q4. SSO 모드에서 `ssoLogin` 후 무한 루프

**원인**: `oidc/ssoLogin` GET 요청 후 리다이렉트 URL이 다시 `/login`으로 돌아옴.

**확인**:
1. `action_url` 파라미터가 올바른지 확인 (`/login?action_url=...` 형식)
2. ido의 `handleOidcSsoLogin()`이 올바른 Keycloak URL로 리다이렉트하는지 로그 확인
3. `encCi`가 비어있는 경우 Keycloak이 다시 로그인 페이지로 보낼 수 있음

### Q5. 모바일에서 인증수단 UI가 잘림

**원인**: `.anyid-login-modal-content`의 `max-height` 제한.

**해결**: `AnyIdLoginModal.styles.scss`에서 모바일 미디어 쿼리 조정:
```scss
@media (max-width: 768px) {
  .anyid-login-modal-content {
    max-height: 100dvh;  // dvh 사용 (모바일 뷰포트 대응)
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
  }
}
```

### Q6. 토글 OFF(기관 자체 로그인) 시 `orgLogin`이 호출되지 않음

**원인**: `bypass` 파라미터 설정 확인.

`AnyidC.LOAD_MODULE()`의 `bypass: 0`이면 SDK가 SSO 모드로 동작,  
`bypass: 1`이면 항상 orgLogin 모드로 동작.  
토글 UI에서 사용자가 OFF하면 `useSso: false`로 오는데, 이 경우 `handleOrgLogin()`이 호출되어야 한다.

`useAnyIdAuth.ts`의 success 콜백에서:
```typescript
if (bypass !== 0 || !data.useSso) {
  void handleOrgLogin(data);  // ← 기관 자체 로그인
} else {
  handleSsoLogin(data);       // ← SSO 경유
}
```

### Q7. `AnyidAdaptor is not defined` 또는 `anyidAdaptor.ssoByPass` 오류

**원인**: SDK JS 내부적으로 `anyidAdaptor` 전역 객체를 참조하는데,  
JSP 환경(`anyidAdaptor.jsp` include)에서는 자동으로 정의되지만  
React 환경에서는 직접 정의해야 할 수 있다.

**해결**: `useAnyIdAuth.ts`의 훅이 `anyidAdaptor`를 직접 구현하므로  
`window.anyidAdaptor`를 설정할 필요가 없다.  
만약 SDK 내부에서 `anyidAdaptor`를 호출하는 경우:

```typescript
// useAnyIdAuth.ts의 initSdk() 내부, LOAD_MODULE 호출 전에 추가
window.anyidAdaptor = {
  ssoByPass: bypass,
  certData: null,
  agencyContextPath: '',
  orgLogin: handleOrgLogin,
  ssoLogin: () => handleSsoLogin(window.anyidAdaptor.certData!),
  userCheck: () => { /* SSO 사용자 체크 로직 */ },
  success: (data) => {
    window.anyidAdaptor.certData = data;
    if (bypass !== 0 || !data.useSso) {
      window.anyidAdaptor.orgLogin(data);
    } else {
      window.anyidAdaptor.userCheck();
    }
  },
};
```

---

## 부록 A: 파일 수정 요약

| 파일 | 변경 유형 | 주요 내용 |
|------|----------|----------|
| `idem-hub/src/main/resources/static/anyid/css/app.css` | **신규** | SDK CSS 정적 파일 |
| `idem-hub/src/main/resources/static/anyid/js/manifest.js` | **신규** | SDK JS 파일 |
| `idem-hub/src/main/resources/static/anyid/js/vendor.js` | **신규** | SDK JS 파일 |
| `idem-hub/src/main/resources/static/anyid/js/app.js` | **신규** | SDK 코어 (`AnyidC` 전역 객체) |
| `idem-hub/src/main/resources/static/config/config.anyidc.json` | **신규** | 인증수단 목록 정적 서빙 |
| `idem-hub/src/main/java/.../AnyIdController.java` | **수정** | `POST /ssob` (provider 무관) 엔드포인트 추가, `POST /txId` 추가 |
| `idem-console/frontend/public/index.html` | **수정** | SDK CSS/JS 태그 추가 (`defer`) |
| `idem-console/frontend/webpack.config.js` | **수정** | `/anyid`, `/config` proxy 추가 |
| `idem-console/frontend/.env` | **수정** | `ANYID_BYPASS`, `ANYID_AUTH_LEVEL` 추가 |
| `idem-console/frontend/src/hooks/useAnyIdAuth.ts` | **신규** | Any-ID 인증 커스텀 훅 |
| `idem-console/frontend/src/components/AnyIdLoginModal/index.tsx` | **신규** | SDK 렌더링 모달 컴포넌트 |
| `idem-console/frontend/src/components/AnyIdLoginModal/AnyIdLoginModal.styles.scss` | **신규** | 모달 스타일 |
| `idem-console/frontend/src/pages/Login/index.tsx` | **수정** | Any-ID 버튼 실제 연결, 훅/모달 추가 |

---

## 부록 B: 핵심 개념 정리

### AnyidC.LOAD_MODULE() 파라미터 의미

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `cfg` | `string` | `config.anyidc.json` 경로. SDK가 이 파일을 읽어 인증수단 목록을 그린다. |
| `txId` | `string` | 거래 ID. 각 인증 세션의 고유 식별자. ssob 복호화 시 tag로도 사용. |
| `tag` | `string` | 거래 태그. 통상 txId와 동일하게 설정. |
| `lvl` | `number` | 인증 수준 (1=낮음/소셜, 2=중간/신분증, 3=높음/공동인증서). 이 수준 이상의 인증수단만 표시. |
| `bypass` | `number` | 0=SSO 경유, 1=기관 자체 로그인. Q-Net은 0. |
| `theme` | `string` | SDK UI 테마 버전. `"4.1.0"` 고정. |
| `toggle` | `boolean` | 정부 통합로그인 토글 버튼 표시 여부. Q-Net처럼 토글을 보이려면 `true`. |
| `success` | `function` | 인증 성공 콜백. `data` 객체에 `ssob`, `txId`, `userSeCd`, `useSso` 포함. |
| `fail` | `function` | 인증 실패 콜백. |
| `log` | `function` | SDK 내부 로그 콜백 (옵션). |

### ssob vs CI 차이

| 구분 | ssob | CI (연계정보) |
|------|------|-------------|
| **형태** | ARIA-CBC-256 암호화 JSON 문자열 | SHA-256 해시 (88자) |
| **주체** | FE가 BE로 전송 | BE에서 ssob 복호화 후 추출 |
| **내용** | `{ci, authLvl, name, brdt}` 암호화본 | 사용자 고유 식별자 (주민번호 대체) |
| **사용처** | AnyIdSsobService.decryptSsob() 입력 | NonOidcAuthService.processAuth() 입력 |

### 인증수단 코드 → provider 매핑

| SDK `name` | 화면 표시 | AnyIdController `provider` |
|-----------|---------|--------------------------|
| `mip-install` | 모바일 신분증 | `mobile-id` |
| `fincert-install` | 금융인증서 | `financial-cert` |
| `anysignlite-install` | 공동인증서 | `joint-cert` |
| `esign-install` | 간편인증 (카카오/네이버/PASS) | `easy-sign` |
| `social-relay` | 소셜/민간 ID | `pid` |
