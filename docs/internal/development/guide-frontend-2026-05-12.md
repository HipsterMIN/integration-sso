# FE 팀 개발 가이드

> **버전**: v3.0.0 (PR #77 반영 — 2026-05-12 GAP 패치 완료 기준)  
> **최종 수정**: 2026-05-12  
> **대상**: idem-console 프론트엔드 개발팀  
> **기술 스택**: React 18 / TypeScript / webpack 5 / Redux (legacy_createStore + thunk)  
> **로컬 개발 서버**: `:3001` (webpack-dev-server)  
> **연동 BFF**: ido `:8083` / `:9292`

---

## 목차

1. [아키텍처 개요 및 변경 사항](#1-아키텍처-개요-및-변경-사항)
2. [개발 환경 설정](#2-개발-환경-설정)
3. [환경변수 전체 목록 (v3.0 최신)](#3-환경변수-전체-목록)
4. [API 인스턴스 구조 (단일화 완료)](#4-api-인스턴스-구조)
5. [인증 흐름 — SKIP_AUTH 완전 제거](#5-인증-흐름)
6. [AES-GCM 키 취득 흐름 (B-1 패치)](#6-aes-gcm-키-취득-흐름)
7. [페이지별 Context 초기값](#7-페이지별-context-초기값)
8. [Step4 클라이언트 목록 API 연동](#8-step4-클라이언트-목록-api-연동)
9. [Q-Sign realm / clientId 환경변수](#9-q-sign-realm--clientid-환경변수)
10. [디렉토리 구조 및 모듈 규칙](#10-디렉토리-구조)
11. [새 API 함수 추가 방법](#11-새-api-함수-추가-방법)
12. [빌드 및 배포 체크리스트](#12-빌드-및-배포-체크리스트)
13. [자주 묻는 오류 및 해결](#13-자주-묻는-오류-및-해결)
14. [코딩 컨벤션](#14-코딩-컨벤션)

---

## 1. 아키텍처 개요 및 변경 사항

### 1.1 v3.0 핵심 변경 요약 (PR #77 — 2026-05-12)

| 항목 | 이전 (v2.x) | 현재 (v3.0) |
|------|------------|------------|
| API 인스턴스 | `beInstance` (ido) + `extInstance` (Q-IM 직접) | **단일 `beApiInstance`** — 모두 ido BFF 경유 |
| `/api/ext/**` 라우팅 | FE → Q-IM 직접 (EXT_API_ENDPOINT) | FE → ido → Q-IM (B-5 프록시) |
| AES-GCM 키 관리 | `process.env.AES_GCM_KEY` (번들 포함) | `GET /api/v1/auth/provision/aes-gcm-key` 서버 취득 |
| `EXT_API_KEY` 관리 | FE 번들 포함 (`X-API-Key` 헤더) | ido 서버사이드 주입 (FE 미포함) |
| `SKIP_AUTH` | `true` 시 JWT 없이 Private 라우트 통과 가능 | **완전 제거** — 항상 실 API 호출 |
| Step4 시스템 목록 | 하드코딩 7개 배열 | `GET /api/ext/clients` 실 API 연동 |
| realm / clientId | `'ucube-qsign'` / `'onepassCli'` 리터럴 | `process.env.QSIGN_REALM` / `QSIGN_CLIENT_ID` |
| Context MOCK 초기값 | `name: '홍길동'`, `phoneSuffix: '12341234'` 등 | 모두 빈 문자열 |

### 1.2 전체 아키텍처 (v3.0)

```
사용자 브라우저
    │
    ▼
idem-console (React SPA, :3001 dev / Nginx prod)
    │
    │  ① beApiInstance  ─────────────────────────────┐
    │     (X-BE-API-Key 헤더)                         │
    │     /api/v1/auth/**   → ido :8083              │
    │     /api/ext/**       → ido :8083 → Q-IM       │
    │                                                 ▼
    │                                         ido :8083
    │                                          ├─ /api/v1/auth/**  (AuthController)
    │                                          ├─ /api/ext/**      (forward proxy → Q-IM)
    │                                          ├─ Q-Sign :9090     (내부 통신)
    │                                          └─ Keycloak         (내부 통신)
    │
    │  ② Redux store JWT
    │     (Authorization: Bearer)
    │     /api/v1/...  → ido :9292 (기존 플랫폼 API)
    └────────────────────────────────────────────────┘
```

### 1.3 FE가 직접 하지 않는 것

| 행위 | 이유 |
|------|------|
| Q-IM 직접 HTTP 호출 | B-5 패치: ido BFF forward proxy 경유 |
| Q-Sign 직접 HTTP 호출 | ido BFF가 중계 |
| CI 평문 저장 | Q3=B 정책 — encryptCi() 후 즉시 폐기 |
| AES-GCM 키 번들 포함 | B-1 패치: 서버 취득으로 전환 |
| EXT_API_KEY 번들 포함 | B-5 패치: ido 서버사이드 주입 |
| JWT 없이 Private 라우트 접근 | SKIP_AUTH 완전 제거 |

---

## 2. 개발 환경 설정

### 2.1 초기 설치

```bash
# Node.js 20+ 필요 (nvm 권장)
node --version   # v20.x.x 이상 확인

cd idem-console/frontend
npm install

# 환경변수 설정
cp .env.example .env
# .env 파일을 아래 섹션 3을 참고하여 로컬 값으로 편집
```

### 2.2 개발 서버 실행

```bash
cd idem-console/frontend

# 로컬 개발 서버 (webpack-dev-server, :3001)
npm run dev

# 타입 검사
npx tsc --noEmit

# 프로덕션 빌드 검증
APP_ENV=prod npm run build
```

### 2.3 ido 로컬 연동

```bash
# 로컬 ido 서버 실행 (별도 터미널)
cd <project-root>
./gradlew :idem-hub:bootRun --args='--spring.profiles.active=local'
# → http://localhost:8083 기동

# FE .env에서 ido 연결
BE_API_ENDPOINT=http://localhost:8083
BE_API_TARGET=http://localhost:8083
IDO_BASE_URL=http://localhost:8083
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

## 3. 환경변수 전체 목록

> **v3.0 변경**: `AES_GCM_KEY`, `EXT_API_ENDPOINT`, `EXT_API_KEY`, `SKIP_AUTH` **삭제됨**.  
> 로컬 `.env` 파일에서 해당 항목을 반드시 제거하세요.

### 3.1 필수 환경변수

| 변수명 | 필수 | 설명 | 로컬 예시값 |
|--------|:----:|------|------------|
| `NODE_ENV` | ✅ | 실행 환경 | `development` |
| `BE_API_ENDPOINT` | ✅ | beApiInstance 베이스 URL (ido) | `http://localhost:8083` |
| `BE_API_TARGET` | ✅ | webpack proxy 타겟 (ido, dev전용) | `http://localhost:8083` |
| `IDO_BASE_URL` | ✅ | /api/ext proxy 타겟 (dev전용) | `http://localhost:8083` |
| `BE_API_KEY` | ✅ | ido X-BE-API-Key 인증 헤더값 | ido 팀에서 발급 |
| `FRONTEND_API_ENDPOINT` | ✅ | 기존 플랫폼 axios baseURL | `http://localhost:9292` |
| `QSIGN_BASE_URL` | ✅ | Q-Sign 기본 URL | `http://localhost:9090` |
| `QSIGN_REALM` | ✅ | Q-Sign realm | `ucube-qsign` |
| `QSIGN_CLIENT_ID` | ✅ | Q-Sign clientId | `onepassCli` |
| `EASYSIGN_URL` | ✅ | OACX EasySign 팝업 URL | `https://easysign.anyid.go.kr/esign` |
| `EASYSIGN_ORIGIN` | ✅ | OACX EasySign postMessage origin | `https://easysign.anyid.go.kr` |

### 3.2 선택 환경변수

| 변수명 | 설명 |
|--------|------|
| `FARO_COLLECTOR_URL` | Grafana Faro 수집 URL |
| `FARO_TENANT_ID` | Faro 테넌트 ID |
| `SENTRY_DSN` | Sentry 오류 수집 DSN |
| `SENTRY_ORG` | Sentry 조직명 |
| `SENTRY_PROJECT_ID` | Sentry 프로젝트 ID |
| `SENTRY_AUTH_TOKEN` | Sentry 소스맵 업로드 토큰 |
| `WEBSOCKET_API_ENDPOINT` | WebSocket 베이스 URL |
| `BUNDLE_ANALYSER` | `true` 설정 시 번들 분석기 활성화 |

### 3.3 삭제된 환경변수 (v2.x → v3.0)

| 변수명 | 삭제 이유 |
|--------|----------|
| `AES_GCM_KEY` | B-1: FE 번들 노출 → 서버 취득으로 전환 |
| `EXT_API_ENDPOINT` | B-5: Q-IM 직접 호출 제거 |
| `EXT_API_KEY` | B-5: ido 서버사이드 주입으로 전환 |
| `SKIP_AUTH` | B-2: mock 분기 완전 제거 |

### 3.4 로컬 `.env` 예시 (전체)

```dotenv
# ============================================================
# idem-console 로컬 개발 환경변수 (.env)
# v3.0 — 2026-05-12 GAP 패치 기준
# ============================================================

NODE_ENV=development

# --- ido BFF 연결 ---
BE_API_ENDPOINT=http://localhost:8083
BE_API_TARGET=http://localhost:8083
IDO_BASE_URL=http://localhost:8083
BE_API_KEY=<ido 팀에서 발급받은 X-BE-API-Key>

# --- 기존 플랫폼 API ---
FRONTEND_API_ENDPOINT=http://localhost:9292

# --- Q-Sign 설정 ---
QSIGN_BASE_URL=http://localhost:9090
QSIGN_REALM=ucube-qsign
QSIGN_CLIENT_ID=onepassCli

# --- OACX / EasySign ---
EASYSIGN_URL=https://easysign.anyid.go.kr/esign
EASYSIGN_ORIGIN=https://easysign.anyid.go.kr

# --- 모니터링 (선택) ---
# FARO_COLLECTOR_URL=
# SENTRY_DSN=
```

---

## 4. API 인스턴스 구조

### 4.1 단일 `beApiInstance` (v3.0)

```
모든 FE API 호출
    │
    ├─ beApiInstance (api/beInstance.ts)
    │   baseURL: BE_API_ENDPOINT
    │   헤더: X-BE-API-Key
    │   │
    │   ├─ /api/v1/auth/**   → ido AuthController (직접 처리)
    │   └─ /api/ext/**       → ido forward proxy → Q-IM
    │
    └─ beInstance (legacy, 기존 플랫폼용)
        baseURL: FRONTEND_API_ENDPOINT
        헤더: Authorization: Bearer {JWT}
```

### 4.2 `extInstance` deprecated 처리

```typescript
// api/extInstance.ts
// B-5 패치: Q-IM 직접 호출 제거 → ido BFF 경유
// 기존 ext/* 파일 하위 호환을 위해 beApiInstance를 re-export
import { beApiInstance } from 'api/beInstance';
export { beApiInstance as default };
```

> **주의**: 신규 코드에서는 `extInstance` 대신 `beApiInstance`를 직접 import하세요.

### 4.3 webpack dev proxy 설정

```javascript
// webpack.config.js (로컬 개발 시)
proxy: {
  '/api/ext': {
    // B-5: ido(8083)가 X-Ext-Api-Key를 서버사이드 주입
    target: process.env.IDO_BASE_URL || 'http://localhost:8083',
    changeOrigin: true,
  },
  '/api': {
    target: process.env.BE_API_TARGET || 'http://localhost:9292',
    changeOrigin: true,
    onProxyReq(proxyReq) {
      proxyReq.setHeader('X-BE-API-Key', process.env.BE_API_KEY || '');
    },
  },
}
```

### 4.4 API 함수 성공/실패 판별 패턴

```typescript
import { beApiInstance } from 'api/beInstance';
import type { ApiResponse } from 'types/api';

// 성공 판별: statusCode === 200
const res = await getClients();
if (res.statusCode === 200 && res.payload?.data?.clients) {
  // 처리
} else {
  // 에러 처리
}
```

---

## 5. 인증 흐름

### 5.1 SKIP_AUTH 완전 제거 (B-2)

> PR #77에서 `SKIP_AUTH` 관련 모든 코드가 제거되었습니다.  
> **로컬 `.env`에 `SKIP_AUTH=true`가 있으면 즉시 삭제하세요.** (빌드 오류 없이 무시되지만 혼란 방지)

```typescript
// AppRoutes/utils.ts (v3.0)
// B-2: SKIP_AUTH mock 분기 완전 제거 — 항상 실제 API 호출
const [response] = await Promise.all([
  getUserApi({ userId, token: authToken }),
]);
const getUserResponse = response;
```

### 5.2 Private 라우트 인증 흐름

```
브라우저 접근
    │
    ▼
AppRoutes/Private.tsx
    │ feSessionId 쿠키 확인
    │
    ├─ 없음 → /login 리다이렉트
    │
    └─ 있음 → afterLogin()
               │
               ▼
           getUserApi({ userId, token: authToken })
               │
               ├─ 성공 → 사용자 정보 Redux store 저장 → 페이지 렌더링
               └─ 실패 → /login 리다이렉트
```

### 5.3 SLO (Single Logout) 흐름

```
사용자 로그아웃 버튼 클릭
    │
    ▼
api/utils.ts → logout()
    │
    ├─ POST /api/v1/auth/slo (ido)
    │   → Keycloak 세션 종료
    │   → Redis feSession 삭제
    │
    └─ Redux store 초기화 → /login 리다이렉트
```

---

## 6. AES-GCM 키 취득 흐름 (B-1 패치)

### 6.1 변경 배경

| 구분 | v2.x | v3.0 |
|------|------|------|
| 키 위치 | `process.env.AES_GCM_KEY` → 번들 포함 | ido 서버 `FE_AES_GCM_KEY` 환경변수 |
| 취득 방법 | 빌드 시 주입 | 런타임 `GET /api/v1/auth/provision/aes-gcm-key` |
| 보안 위험 | 번들 분석으로 키 노출 | FE 번들에 키 미포함 |

### 6.2 구현 (`utils/crypto/aesGcm.ts`)

```typescript
// ido 서버에서 AES-GCM 키 취득 (런타임)
async function fetchAesGcmKey(): Promise<string> {
  const res = await beApiInstance.get<{ aesGcmKey: string }>(
    '/api/v1/auth/provision/aes-gcm-key',
  );
  const { aesGcmKey } = res.data;
  if (!aesGcmKey) throw new Error('서버에서 AES-GCM 키를 수신하지 못했습니다');
  return aesGcmKey;
}

// CI 암호화 (Step3 본인인증 완료 후 호출)
export async function encryptCi(ciPlaintext: string): Promise<string> {
  const aesGcmKeyB64 = await fetchAesGcmKey(); // 서버에서 취득
  // ... AES-256-GCM 암호화 (Web Crypto API)
  // 반환: base64(IV(12B) || ciphertext || GCM-tag(16B))
}
```

### 6.3 CI 암호화 전체 흐름

```
Step3 본인인증 성공 → result.ci (CI 평문)
    │
    ▼
encryptCi(ci)
    ├─ GET /api/v1/auth/provision/aes-gcm-key  → aesGcmKey (Base64)
    ├─ Web Crypto API: AES-256-GCM 암호화
    ├─ IV(12B 랜덤) || ciphertext || GCM-tag
    └─ Base64 인코딩
    │
    ▼
result.ci = undefined  (CI 평문 즉시 폐기 ✅)
    │
    ▼
POST /api/v1/auth/ci-token  (ido BFF)
{ encryptedCi, realm, clientId, flowContext }
    │
    ▼
ciToken (JWT) → ConversionContext/RegisterContext에 저장
```

> **운영 필수**: ido 서버에 `FE_AES_GCM_KEY` 환경변수가 없으면 `500` 응답.  
> ido 팀에 키 설정 여부를 반드시 확인하세요.

---

## 7. 페이지별 Context 초기값

### 7.1 ConversionContext INITIAL_DATA (v3.0)

```typescript
// providers/Conversion/ConversionContext.tsx
// B-3: MOCK_MEMBER / MOCK_BUSINESS import 완전 제거
const INITIAL_DATA = {
  userType: '',
  name: '',           // 이전: MOCK_MEMBER.name ('홍길동') → 빈 문자열
  phonePrefix: '010',
  phoneSuffix: '',    // 이전: MOCK_MEMBER.phoneSuffix ('12341234') → 빈 문자열
  email: '',          // 이전: MOCK_MEMBER.emailId
  emailDomain: '',
  emailId: '',
  bzmnNm: '',         // 이전: MOCK_BUSINESS.companyName
  rprsvNm: '',
  brno: '',
  availableClients: [],
  selectedClients: [],
  ciToken: '',
  consentEventId: '',
};
```

### 7.2 RegisterContext INITIAL_DATA (v3.0)

```typescript
// providers/Register/RegisterContext.tsx
// 동일하게 MOCK 초기값 제거
const INITIAL_DATA = {
  userType: '',
  name: '',           // 빈 문자열
  phonePrefix: '010',
  phoneSuffix: '',    // 빈 문자열
  email: '',
  emailDomain: '',
  emailId: '',
  bzmnNm: '',
  rprsvNm: '',
  brno: '',
  ciToken: '',
  consentEventId: '',
};
```

> **주의**: Context는 페이지 새로고침 시 초기화됩니다.  
> `ciToken` 유실 방지를 위해 Step3 완료 후 `sessionStorage` 백업을 권장합니다 (GAP-13, 향후 개선 예정).

---

## 8. Step4 클라이언트 목록 API 연동

### 8.1 변경 내용

```typescript
// ConversionSteps/Step4.tsx (v3.0)
// 이전: 하드코딩 SYSTEMS 배열 (7개 고정)
// 현재: GET /api/ext/clients 실 API 연동

useEffect(() => {
  (async () => {
    try {
      const res = await getClients();
      if (res.statusCode === 200 && res.payload?.data?.clients) {
        updateData({ availableClients: res.payload.data.clients });
      } else {
        setLoadError(true);
      }
    } catch {
      setLoadError(true);
    }
  })();
}, []);
```

### 8.2 `getClients()` API 스펙

```
GET /api/ext/clients
헤더: X-BE-API-Key

응답 200:
{
  "statusCode": 200,
  "payload": {
    "data": {
      "clients": [
        { "id": "client-uuid", "name": "스마트공장", "description": "..." },
        ...
      ]
    }
  }
}
```

> **Q-IM 팀 확인 필요**: `/api/ext/clients` 응답 스펙이 위 형식과 일치하는지 교차검증 필요.

### 8.3 체크박스 상태 관리

```typescript
// 클라이언트 선택 → ConversionContext.selectedClients 업데이트
const handleCheck = (clientId: string, checked: boolean) => {
  const next = checked
    ? [...data.selectedClients, clientId]
    : data.selectedClients.filter((id) => id !== clientId);
  updateData({ selectedClients: next });
};
```

---

## 9. Q-Sign realm / clientId 환경변수

### 9.1 변경 파일 목록

| 파일 | 변경 내용 |
|------|----------|
| `ConversionSteps/member/Step3.tsx` | 4곳 → 환경변수 |
| `RegisterSteps/member/Step3.tsx` | 4곳 → 환경변수 |
| `Mypage/pages/AffiliationWithdrawStep1.tsx` | 1곳 → 환경변수 |

### 9.2 사용 패턴

```typescript
// 변경 전
exchangeCiToken({ realm: 'ucube-qsign', clientId: 'onepassCli', ... });

// 변경 후
exchangeCiToken({
  realm: process.env.QSIGN_REALM || 'ucube-qsign',
  clientId: process.env.QSIGN_CLIENT_ID || 'onepassCli',
  ...
});
```

> **배포 체크**: `.env.prod`에 `QSIGN_REALM`, `QSIGN_CLIENT_ID` 값이 설정되어 있는지 확인하세요.

---

## 10. 디렉토리 구조

```
idem-console/frontend/src/
├── api/
│   ├── beInstance.ts          # beApiInstance (ido BFF — 모든 API 경유)
│   ├── extInstance.ts         # deprecated: beApiInstance re-export
│   ├── ext/                   # Q-IM /api/ext/** 래퍼 함수들
│   │   ├── clients.ts         # GET /api/ext/clients
│   │   ├── consent.ts         # POST /api/ext/consent/**
│   │   ├── members.ts         # GET/POST /api/ext/members/**
│   │   └── ...
│   ├── provision/             # 프로비저닝 API 래퍼
│   │   ├── ciToken.ts         # POST /api/v1/auth/ci-token
│   │   ├── users.ts           # POST /api/ext/provision/users
│   │   └── ...
│   └── utils.ts               # logout(), getUserApi() 등
├── AppRoutes/
│   ├── Private.tsx            # SKIP_AUTH 제거됨
│   ├── utils.ts               # afterLogin() — 항상 실 API 호출
│   └── index.tsx
├── pages/
│   ├── ConversionSteps/member/
│   │   ├── Step3.tsx          # realm/clientId 환경변수화
│   │   ├── Step4.tsx          # getClients() 실 API 연동
│   │   └── ...
│   ├── RegisterSteps/member/
│   │   ├── Step3.tsx          # realm/clientId 환경변수화
│   │   └── components/
│   │       └── MemberInfoForm.tsx  # data.bzmnNm / data.brno 사용
│   └── Mypage/pages/
│       └── AffiliationWithdrawStep1.tsx  # realm/clientId 환경변수화
├── providers/
│   ├── Conversion/ConversionContext.tsx  # MOCK 초기값 제거
│   └── Register/RegisterContext.tsx      # MOCK 초기값 제거
├── utils/
│   └── crypto/
│       └── aesGcm.ts         # fetchAesGcmKey() 서버 취득
└── index.tsx                  # EXT_API_ENDPOINT Faro 참조 제거
```

---

## 11. 새 API 함수 추가 방법

### 11.1 Q-IM API 래퍼 추가 (`/api/ext/**`)

```typescript
// api/ext/newFeature.ts
import { beApiInstance } from 'api/beInstance';
import type { ApiResponse } from 'types/api';

interface NewFeatureResponse {
  data: { result: string };
}

export async function getNewFeature(): Promise<ApiResponse<NewFeatureResponse>> {
  const res = await beApiInstance.get<ApiResponse<NewFeatureResponse>>(
    '/api/ext/new-feature',
  );
  return res.data;
}
```

### 11.2 ido BFF API 래퍼 추가 (`/api/v1/auth/**`)

```typescript
// api/provision/newAuth.ts
import { beApiInstance } from 'api/beInstance';
import type { ApiResponse } from 'types/api';

export async function postNewAuth(payload: { foo: string }): Promise<ApiResponse<{ bar: string }>> {
  const res = await beApiInstance.post<ApiResponse<{ bar: string }>>(
    '/api/v1/auth/new-endpoint',
    payload,
  );
  return res.data;
}
```

---

## 12. 빌드 및 배포 체크리스트

### 12.1 로컬 개발 전

```bash
# 환경변수 파일 확인
grep -E "AES_GCM_KEY|EXT_API_KEY|SKIP_AUTH|EXT_API_ENDPOINT" .env
# → 위 변수가 있으면 삭제 (v3.0에서 제거됨)

# 타입 검사
cd idem-console/frontend && npx tsc --noEmit
```

### 12.2 스테이징/프로덕션 배포 전

```
[ ] BE_API_ENDPOINT  — ido 운영 URL 설정
[ ] BE_API_KEY       — ido X-BE-API-Key 설정
[ ] QSIGN_REALM      — Q-Sign realm 설정 (기본: ucube-qsign)
[ ] QSIGN_CLIENT_ID  — Q-Sign clientId 설정 (기본: onepassCli)
[ ] EASYSIGN_URL     — OACX EasySign 팝업 URL
[ ] EASYSIGN_ORIGIN  — OACX postMessage origin
[ ] AES_GCM_KEY      — 없어야 함 (v3.0 삭제)
[ ] EXT_API_KEY      — 없어야 함 (v3.0 삭제)
[ ] EXT_API_ENDPOINT — 없어야 함 (v3.0 삭제)
[ ] SKIP_AUTH        — 없어야 함 (v3.0 삭제)
[ ] ido 팀에 FE_AES_GCM_KEY 설정 확인 요청
[ ] npm run build 성공 확인
```

### 12.3 smoke test 시나리오

```
[ ] 로그인 → Private 라우트 정상 접근
[ ] ConversionStep4 → 클라이언트 목록 API 응답 (하드코딩 미사용)
[ ] Step3 OACX 간편인증 → CI 취득 → encryptCi() → ciToken 저장
[ ] Step3 NICE 휴대폰 인증 → 동일 흐름
[ ] ConversionStep5 → 임시 비밀번호 자동 생성 (Math.random 아닌 서버 취득)
[ ] MemberInfoForm → 기업명/사업자번호 빈 값 초기 렌더링
[ ] 로그아웃 → SLO 정상 처리
```

---

## 13. 자주 묻는 오류 및 해결

### 13.1 `GET /api/v1/auth/provision/aes-gcm-key` 500 오류

```
원인: ido 서버에 FE_AES_GCM_KEY 환경변수 미설정
해결: ido 팀에 FE_AES_GCM_KEY 환경변수 설정 요청
     (Base64 인코딩된 AES-256 32바이트 키)
```

### 13.2 `/api/ext/**` 404 오류

```
원인 1: IDO_BASE_URL이 올바른 ido 주소를 가리키지 않음
해결:   .env의 IDO_BASE_URL 확인

원인 2: ido 서버에 /api/ext 프록시 미설정
해결:   ido 팀에 /api/ext forward proxy 설정 확인 요청
```

### 13.3 `SKIP_AUTH` 관련 타입 오류

```
원인: .env에 SKIP_AUTH=true가 있으나 코드에서 참조 제거됨
해결: .env에서 SKIP_AUTH 항목 삭제 (코드 수정 불필요)
```

### 13.4 Step4 클라이언트 목록 미표시

```
원인: GET /api/ext/clients 미구현 (Q-IM 팀)
해결: Q-IM 팀에 /api/ext/clients 구현 여부 확인
     임시: loadError 상태로 에러 메시지 표시됨
```

### 13.5 `process.env.QSIGN_REALM` undefined

```
원인: .env에 QSIGN_REALM 미설정
해결: .env에 QSIGN_REALM=ucube-qsign 추가
     (|| 'ucube-qsign' fallback이 있으므로 즉각 장애는 없음)
```

---

## 14. 코딩 컨벤션

### 14.1 API 인스턴스 사용 규칙

```typescript
// ✅ 올바른 사용
import { beApiInstance } from 'api/beInstance';

// ❌ 잘못된 사용 (삭제된 환경변수 참조)
process.env.AES_GCM_KEY        // 제거됨
process.env.EXT_API_KEY        // 제거됨
process.env.EXT_API_ENDPOINT   // 제거됨
process.env.SKIP_AUTH          // 제거됨
```

### 14.2 환경변수 참조 패턴

```typescript
// ✅ 반드시 fallback 값 제공
const realm = process.env.QSIGN_REALM || 'ucube-qsign';

// ❌ fallback 없이 사용 (런타임 오류 가능)
const realm = process.env.QSIGN_REALM;
```

### 14.3 CI 처리 규칙 (Q3=B 정책)

```typescript
// ✅ CI 평문 즉시 폐기
const encrypted = await encryptCi(result.ci);
result.ci = undefined;  // 반드시 즉시 폐기

// ❌ CI 평문 저장 금지
setState({ ci: result.ci });
localStorage.setItem('ci', result.ci);
```

### 14.4 커밋 메시지 형식

```
feat(fe): 새 기능 추가
fix(fe): 버그 수정
chore(fe): 설정 변경
docs(fe): 문서 수정
refactor(fe): 리팩토링
```

---

*최종 수정: 2026-05-12 / PR #77 반영*  
*다음 업데이트 예정: Q-IM /api/ext/** 교차검증 완료 후*
