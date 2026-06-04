# OnePass FE (onepass-fe) — 프론트엔드 개발 가이드

> **버전**: 1.1.0 | **최종 갱신**: 2026-06-02 | **대상**: OnePass FE 개발팀  
> **브랜치**: `genspark_ai_developer` → main

> 📘 **함께 읽기 (정본 문서)**  
> 본 문서가 **"어떻게 개발/실행하는가"** 를 다룬다면, 데이터 흐름·아키텍처의 정본은 별도 문서입니다:
> - **[docs/internal/spec/02-architecture.md](../docs/internal/spec/02-architecture.md) ADR-008** — **FE 군(群) ↔ IdO 단일 채널 헌법**. onepass-fe 는 Q-IM / Q-Sign 등 BE 모듈을 **직접 호출하지 않는다**. 모든 외부 호출은 IdO 게이트웨이 단일 채널 경유. (코드 측 명명 정합화는 **Phase 2 에서 완료** — PR #203, 커밋 `a7065ae`: `BE_API_*` → `IDO_API_*`, `beInstance` → `idoInstance`, `X-BE-API-Key` → `X-IDO-API-Key`. 한 페이즈 동안 환경변수만 fallback 유지. [docs/internal/spec/09-gap-and-roadmap.md](../docs/internal/spec/09-gap-and-roadmap.md) §6.A.1 SEC-IDO-* 참조)
> - **[docs/internal/spec/03f-module-onepass-fe.md](../docs/internal/spec/03f-module-onepass-fe.md)** — onepass-fe 진입(Inbound) / 상태(State 4계층) / 송신(Outbound) 표면, CI 평문 처리 경로, IdO 엔드포인트 인벤토리, 위험 표면 (FE-RISK-01~07)
> - **[docs/internal/spec/03c-qim-responsibility-charter.md](../docs/internal/spec/03c-qim-responsibility-charter.md)** §6 / §6.5 — Q-IM 의 사람-대상 UI 영구 금지선. **모든 사람-대상 화면은 FE 군(현재 `onepass-fe`, 향후 `onepass-admin` 등) 이 호스트하며, FE 군은 IdO 단일 채널로만 통신**.

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [현재 구현 상태](#2-현재-구현-상태)
3. [미구현 기능 목록 및 우선순위](#3-미구현-기능-목록-및-우선순위)
4. [개발 방향 및 로드맵](#4-개발-방향-및-로드맵)
5. [기술 스택 및 아키텍처](#5-기술-스택-및-아키텍처)
6. [환경 설정 가이드](#6-환경-설정-가이드)
7. [로컬 개발 실행 가이드](#7-로컬-개발-실행-가이드)
8. [API 연동 계약](#8-api-연동-계약)
9. [핵심 컴포넌트 & 훅 패턴 가이드](#9-핵심-컴포넌트--훅-패턴-가이드)
10. [devNoticeModal 제거 전략](#10-devnoticemodal-제거-전략)
11. [코딩 컨벤션](#11-코딩-컨벤션)
12. [테스트 전략](#12-테스트-전략)
13. [보안 고려사항](#13-보안-고려사항)

---

## 1. 프로젝트 개요

**OnePass FE**는 중기원패스 통합로그인 서비스의 프론트엔드입니다.  
Keycloak(Q-Sign) 기반 OIDC 인증, 다단계 회원전환/회원가입 폼, 마이페이지를 포함합니다.

### 서비스 흐름 요약

```
사용자 → Keycloak(Q-Sign) 로그인 요청
  → actionUrl 파라미터를 가진 /login 페이지 렌더링
  → 개인/기업 탭에서 인증 수행
  → form.submit() → Keycloak
  → 서비스(중기원, TIPS 등) redirect
```

### 주요 페이지 목록

| 경로 | 페이지 | 설명 |
|------|--------|------|
| `/login` | Login | Keycloak 연동 로그인 (ID/PW, CI, 기업) |
| `/conversion/member/step1~8` | ConversionSteps(member) | 개인 통합회원 전환 7단계 |
| `/conversion/business/step2~6` | ConversionSteps(business) | 기업 통합회원 전환 5단계 |
| `/register/member/step1~6` | RegisterSteps(member) | 개인 신규 회원가입 6단계 |
| `/register/business/step2~6` | RegisterSteps(business) | 기업 신규 회원가입 5단계 |
| `/mypage/member/*` | Mypage(member) | 개인 마이페이지 |
| `/mypage/business/*` | Mypage(business) | 기업 마이페이지 |
| `/reset-password` | ResetPassword | 비밀번호 초기화 |

---

## 2. 현재 구현 상태

### 2.1 페이지별 완성도

| 페이지 | 완성도 | 비고 |
|--------|--------|------|
| **Login — ID/PW 로그인** | ✅ 95% | actionUrl form POST 완성. 아이디/비밀번호 찾기 버튼 핸들러 없음 |
| **Login — CI 로그인 (NICE 휴대폰)** | ✅ 90% | `useNicePhoneAuth` 완성. NICE 백엔드 연동 필요 |
| **Login — CI 로그인 (EasySign)** | ✅ 90% | `usePersonalEasyAuth` 완성. EASYSIGN_URL 설정 필요 |
| **Login — 공동인증서 로그인** | ❌ 0% | `devNoticeModal` 처리 — 미구현 |
| **Login — Any-ID 로그인** | ❌ 0% | `devNoticeModal` 처리 — 미구현 |
| **Login — 기업 간편인증 (EzAuth)** | ✅ 85% | `useEzAuth` 완성. EzAuth SDK 로드 확인 필요 |
| **ConversionSteps — member Step1** | ✅ 90% | 통합회원 전환 안내 |
| **ConversionSteps — member Step2** | ✅ 85% | 약관 동의 (termsBundle API 연동) |
| **ConversionSteps — member Step3** | 🔶 60% | NICE/EasySign 완성. 공동인증서/Any-ID = `devNoticeModal` |
| **ConversionSteps — member Step4** | ✅ 80% | 서비스 연결 (clients API) |
| **ConversionSteps — member Step5** | 🔶 70% | AccountForm 완성. 기업 진위확인 API 주석 처리 |
| **ConversionSteps — member Step6** | ✅ 80% | 알림 설정 |
| **ConversionSteps — member Step8** | ✅ 80% | 완료 화면 |
| **ConversionSteps — business Step2~6** | 🔶 65% | 기업인증 설립일 미확보 → 진위확인 스킵 |
| **RegisterSteps — member Step1~6** | 🔶 65% | Step3 동일 문제 (공동인증서/Any-ID 미구현) |
| **RegisterSteps — business Step2~6** | 🔶 55% | 기업 회원가입 흐름 미검증 |
| **Mypage — 내 정보** | 🔶 70% | 조회 완성. 수정 기능 일부 `devNoticeModal` |
| **Mypage — 비밀번호 변경 (MemberAuth)** | ❌ 30% | 인증 후 다음 단계 이동 주석. `devNoticeModal` 처리 |
| **Mypage — 비밀번호 변경 (BusinessAuth)** | 🔶 50% | EzAuth 연동. API 연동 미완성 |
| **Mypage — 유관기관 서비스** | 🔶 60% | 개인회원 `devNoticeModal`. 기업회원 일부 구현 |
| **Mypage — 유관기관 추가 (개인)** | ❌ 0% | 전체 `devNoticeModal` |
| **Mypage — 유관기관 추가 (기업)** | 🔶 50% | EzAuth 연동. 공동인증서 `devNoticeModal` |
| **Mypage — 유관기관 탈퇴** | 🔶 50% | 개인 공동인증서 `devNoticeModal` |
| **Mypage — 통합회원 탈퇴** | ❌ 20% | 인증 후 다음 단계 이동 주석. `devNoticeModal` |
| **ErrorBoundary / 에러 페이지** | 🔶 60% | Sentry 연동. CSP 미설정 |

---

### 2.2 `devNoticeModal` 발생 위치 전체 목록

> 이 항목이 있는 기능은 **"서비스 준비 중" 모달만 표시**되며 실제 기능이 없습니다.

| 파일 | 기능 | 우선순위 |
|------|------|----------|
| `pages/Login/index.tsx` (L565, L585) | 개인 공동인증서, Any-ID 로그인 | P2 |
| `pages/Login/index.tsx` (L713) | 기업 공동인증서 로그인 | P2 |
| `pages/ConversionSteps/member/Step3.tsx` (L264, L354) | 전환 Step3 공동인증서, Any-ID 본인인증 | P2 |
| `pages/RegisterSteps/member/Step3.tsx` (L200, L226, L259, L337) | 가입 Step3 공동인증서, Any-ID, cert, easy | P2 |
| `pages/Mypage/pages/Affiliation.tsx` (L50) | 개인 유관기관 탈퇴 API | P1 |
| `pages/Mypage/pages/AffiliationAddStep1.tsx` (L67) | 개인 유관기관 추가 인증 | P1 |
| `pages/Mypage/pages/AffiliationAddStep1.tsx` (L224, L278) | 기업 유관기관 추가 공동인증서 | P2 |
| `pages/Mypage/pages/AffiliationWithdrawStep1.tsx` (L63) | 개인 유관기관 탈퇴 공동인증서 | P2 |
| `pages/Mypage/pages/InformationStep2.tsx` (L92, L150, L262) | 개인/기업 내 정보 수정 공동인증서 | P2 |
| `pages/Mypage/pages/InformationStep3.tsx` (L434) | 기업 CI 변경 공동인증서 | P2 |
| `pages/Mypage/pages/PasswordStep1.tsx` (L26, L38, L92, L150, L243, L257) | 비밀번호 변경 전체 인증 | P1 |
| `pages/Mypage/pages/Withdraw.tsx` (L140) | 통합회원 탈퇴 버튼 | P1 |

---

### 2.3 TODO 주석 목록

| 파일 | 내용 | 우선순위 |
|------|------|----------|
| `pages/ConversionSteps/member/components/AccountForm.tsx:73` | 기업 진위확인 API 호출 활성화 | P1 |
| `pages/ConversionSteps/member/Step5.tsx:46` | 기업인증 후 `!data.startDt` 조건 복원 | P1 |
| `pages/Mypage/pages/Affiliation.tsx:49` | 개인회원 유관기관 API 배포 후 복원 | P1 |
| `pages/Mypage/pages/PasswordStep1.tsx:19` | 비밀번호 변경 — 인증 성공 시 다음 단계 이동 활성화 | P1 |
| `pages/Mypage/pages/PasswordStep1.tsx:209` | 비밀번호 변경 기업 — goNext 다음 단계 이동 | P1 |
| `pages/Mypage/pages/Withdraw.tsx:17` | 통합회원 탈퇴 — 다음 단계 이동 활성화 | P1 |
| `container/SideNav/NavItem/NavItem.tsx:40,82` | 비활성화 메뉴 제거 | P3 |
| `container/TopNav/DateTimeSelectionV2` | 시간 설정 개발 요청 미완성 | P3 |

---

## 3. 미구현 기능 목록 및 우선순위

### P1 — 즉시 구현 (핵심 운영 기능)

#### P1-1: 마이페이지 비밀번호 변경 (완전 미동작)
- **파일**: `pages/Mypage/pages/PasswordStep1.tsx`
- **문제**: 인증(EasySign/NICE) 성공 후 `history.push(onNext)` 가 주석 처리됨
- **필요 작업**:
  - 백엔드 비밀번호 변경 API 배포 확인
  - `handleEasyAuthSuccess`, `handlePhoneAuthSuccess` 내 `history.push(onNext)` 주석 해제
  - `devNoticeModal` 대신 정상 플로우로 교체

#### P1-2: 마이페이지 통합회원 탈퇴 (완전 미동작)
- **파일**: `pages/Mypage/pages/Withdraw.tsx`
- **문제**: 탈퇴 버튼 클릭 시 `devNoticeModal`만 표시
- **필요 작업**:
  - `const nextRoute = getMypageRoute(memberType, 'WITHDRAW_STEP2')` 주석 해제
  - 탈퇴 인증 → 확인 → 완료 단계 정상 연결

#### P1-3: 마이페이지 개인 유관기관 서비스 탈퇴 API
- **파일**: `pages/Mypage/pages/Affiliation.tsx`
- **문제**: 개인 회원의 유관기관 탈퇴 버튼 → `devNoticeModal`
- **필요 작업**:
  - 개인 유관기관 탈퇴 API (`DELETE /api/ext/provision/affiliations/{clientId}`) 배포 후
  - `handleWithdraw()` 내 `devNoticeModal` 제거, 실제 탈퇴 플로우 복원

#### P1-4: 기업 진위확인 API 활성화
- **파일**: `pages/ConversionSteps/member/components/AccountForm.tsx:73`
- **문제**: 기업 사업자 진위확인(`businessValidate`) 호출 코드가 전체 주석 처리
- **필요 작업**:
  - ConversionSteps Step3 (기업인증) 완성 시 `data.startDt` 확보
  - `accountForm.tsx` 주석 코드 블록 복원
  - `Step5.tsx`의 `!data.startDt` 조건 복원

#### P1-5: 마이페이지 개인 유관기관 추가
- **파일**: `pages/Mypage/pages/AffiliationAddStep1.tsx`
- **문제**: 개인 회원의 유관기관 추가 인증 전체 `devNoticeModal`
- **필요 작업**: 개인 CI 기반 유관기관 추가 플로우 구현

---

### P2 — 단기 구현 (시연 품질 향상)

#### P2-1: 공동인증서 인증 (로그인/전환/가입)
- **영향 파일**: Login, ConversionSteps Step3, RegisterSteps Step3, Mypage 다수
- **필요 인프라**: 공동인증서(구 공인인증서) 연동 SDK 또는 API
- **접근 방법**: KICA / YESSIGN / CROSSCERT 중 택일, 백엔드 서명 검증 API 필요

#### P2-2: Any-ID 로그인
- **영향 파일**: Login, ConversionSteps Step3, RegisterSteps Step3
- **필요 인프라**: `https://www.anyid.go.kr` 연동 (행안부 공공 DID)
- **현황**: EASYSIGN_URL처럼 별도 SDK 팝업 방식 예상

#### P2-3: 아이디 찾기 / 비밀번호 찾기
- **파일**: `pages/Login/index.tsx` (L457~L468)
- **문제**: 버튼 렌더링만 존재, `onClick` 핸들러 없음
- **필요 작업**:
  - 아이디 찾기: 이메일/휴대폰 인증 → 마스킹된 ID 반환 UI
  - 비밀번호 찾기: 이메일 인증 → 임시 비밀번호 발송 또는 링크 방식
  - 백엔드 API: `POST /api/ext/members/find-id`, `POST /api/ext/members/find-password`

#### P2-4: 마이페이지 내 정보 수정 공동인증서 인증
- **파일**: `pages/Mypage/pages/InformationStep2.tsx`, `InformationStep3.tsx`

#### P2-5: 마이페이지 유관기관 공동인증서 인증
- **파일**: `pages/Mypage/pages/AffiliationAddStep1.tsx`, `AffiliationWithdrawStep1.tsx`

---

### P3 — 중장기 개선 (품질/운영)

#### P3-1: ErrorBoundary 강화
- **현황**: `pages/ErrorBoundaryFallback` 존재. Sentry 연동. CSP 미설정
- **개선**: `window.onerror` 글로벌 핸들러, 네트워크 에러 retry 로직

#### P3-2: CSP (Content Security Policy) 헤더 설정
- **파일**: `frontend/conf/default.conf` (Nginx)
- **현황**: CSP 없음 — XSS 위험 존재
- **적용 항목**: `script-src`, `connect-src`, `frame-src` 최소 권한 설정

#### P3-3: SideNav 비활성 메뉴 정리
- **파일**: `container/SideNav/NavItem/NavItem.tsx`
- **현황**: TODO 주석으로 비활성화 메뉴 임시 유지

#### P3-4: 접근성(a11y) 개선
- **현황**: `aria-label`, `role` 다수 적용됨. 키보드 내비게이션 미검증

#### P3-5: i18n 한국어 고정 제거
- **현황**: 텍스트 하드코딩. `i18-generate-hash.js` 존재하나 미활성

---

## 4. 개발 방향 및 로드맵

### 4.1 단기 목표 (FE Sprint 1 — 2주)

```
FE-S1-T1: P1-1 비밀번호 변경 플로우 완성
FE-S1-T2: P1-2 통합회원 탈퇴 플로우 완성  
FE-S1-T3: P1-3 개인 유관기관 탈퇴 API 연동
FE-S1-T4: P1-4 기업 진위확인 API 활성화 (Step3 설립일 확보 선행)
FE-S1-T5: P2-3 아이디/비밀번호 찾기 UI + API 연동
```

### 4.2 중기 목표 (FE Sprint 2 — 2주)

```
FE-S2-T1: P2-1 공동인증서 연동 (SDK 선정 → 백엔드 협의)
FE-S2-T2: P2-2 Any-ID 연동
FE-S2-T3: P1-5 개인 유관기관 추가 플로우
FE-S2-T4: CSP 헤더 적용
FE-S2-T5: 전체 devNoticeModal 제거 완료
```

### 4.3 장기 목표 (FE Sprint 3~)

```
FE-S3-T1: ErrorBoundary 강화 + 글로벌 에러 처리
FE-S3-T2: 접근성 전면 검증 (WCAG 2.1 AA)
FE-S3-T3: 테스트 커버리지 80% 이상 (현재 미측정)
FE-S3-T4: 성능 최적화 (번들 분석, Code Splitting)
```

### 4.4 devNoticeModal 제거 완료 기준

모든 `setDevNoticeModal(true)` 호출이 실제 기능 구현으로 교체될 때  
`devNoticeModal` 상태 변수 자체를 파일에서 제거해야 합니다.

---

## 5. 기술 스택 및 아키텍처

### 5.1 코어 의존성

| 패키지 | 버전 | 용도 |
|--------|------|------|
| React | 18.2.x | UI 렌더링 |
| TypeScript | 4.x | 타입 안전성 |
| Webpack | 5.x | 번들링 |
| Ant Design | 5.17.0 | UI 컴포넌트 (ConfigProvider 테마 적용) |
| react-router-dom | v5 + v5-compat | 라우팅 (history 기반) |
| react-query | 3.39.3 | 서버 상태 관리 |
| Redux + redux-thunk | — | 전역 상태 (로그인 상태 등) |
| axios | — | HTTP 클라이언트 (2-인스턴스 구조) |

### 5.2 Axios 2-인스턴스 구조

```
┌─────────────────────────────────────────────────────────┐
│                    FE (3301포트)                         │
│                                                         │
│  idoInstance (X-IDO-API-Key)  →  /api  → ido:8083      │
│  (ADR-008 단일 채널 — Phase 2, PR #203 정합 완료)        │
│                                                         │
│  ※ 과거 extInstance (FE → Q-IM 직접) 는 B-5 보안 패치   │
│     로 IdO forward proxy 경유로 전환된 뒤,              │
│     Phase 2 (SEC-IDO-06) 에서 파일 자체 삭제됨.         │
└─────────────────────────────────────────────────────────┘
```

**idoInstance** — `src/api/idoInstance.ts` (Phase 2, PR #203 / `a7065ae`)
- 대상: IdO 게이트웨이 (로컬 dev proxy: `ido:8083`, 운영: `https://onepass-ido-*.smes.go.kr`)
- 헤더: `X-IDO-API-Key: ${IDO_API_KEY}`
- 용도: 모든 IdO 호출 — NICE 인증, OACX EasySign, ext/* (구 Q-IM forward proxy), provision/*, conversion/* 등 단일 채널
- 호환: `process.env.IDO_API_KEY || process.env.BE_API_KEY` — 한 페이즈 동안 구 명칭 환경변수 fallback 유지

**~~extInstance~~** — Phase 2 (SEC-IDO-06) 에서 파일 삭제됨.
- 과거 `src/api/extInstance.ts` 는 B-5 보안 패치 이후 `beApiInstance` 의 단순 re-export 셸이었으며, grep 으로 사용처 0건 확인 후 삭제.
- 신규 코드는 `idoApiInstance` 를 직접 import 한다.

### 5.3 Webpack Proxy 3-레벨

```javascript
// webpack.config.js (Phase 2, PR #203 정합 완료)
proxy: {
  '/api': { target: IDO_API_TARGET || BE_API_TARGET || 'http://localhost:9292' },
          // ↑ ADR-008 단일 채널 — IdO 게이트웨이 단일 호스트
  '/bizezauth-api-dev': { target: 'https://www.smes.go.kr' },  // EzAuth (브라우저 차원 form-POST 보조)
  '/faro':    { target: 'https://faro.smes-tipa.go.kr' },      // Grafana Faro 텔레메트리
}
// ※ 과거 '/api/ext' → Q-IM 직접 proxy 라인은 B-5 패치로 제거됨 — /api/ext/** 도 IdO 단일 채널 경유.
```

### 5.4 Provider 계층 구조

```
AppProvider
  ├── QueryClientProvider (react-query)
  ├── Redux Store
  ├── ConversionProvider  ← 통합전환 7단계 전역 상태
  ├── RegisterProvider    ← 신규가입 6단계 전역 상태
  └── Router (react-router-dom v5)
```

### 5.5 인증 방법별 구현 현황

| 인증 수단 | 훅 | 상태 |
|-----------|-----|------|
| NICE 휴대폰 본인인증 | `useNicePhoneAuth` | ✅ 구현 완료 |
| OACX EasySign (개인 간편) | `usePersonalEasyAuth` | ✅ 구현 완료 |
| EzAuth SDK (기업 간편) | `useEzAuth` | ✅ 구현 완료 |
| 공동인증서 | — | ❌ 미구현 |
| Any-ID | — | ❌ 미구현 |

---

## 6. 환경 설정 가이드

### 6.1 환경 변수 목록 (`.env`)

> **보안 주의**: `.env` 파일은 `.gitignore`에 포함됩니다. `.env.example`을 참조하세요.

```env
# ─── IdO 게이트웨이 (ADR-008 단일 채널) — Phase 2, PR #203 정합 완료 ──
IDO_API_TARGET=http://localhost:8083    # IdO 게이트웨이 주소 (로컬: 8083, 운영: https://onepass-ido-*.smes.go.kr)
IDO_API_ENDPOINT=                       # idoInstance baseURL (없으면 빈값 = 상대경로 → dev proxy 경유)
IDO_API_KEY=bek-xxxxxxxxxxxxxxxx        # X-IDO-API-Key 헤더값

# (구 명칭 BE_API_TARGET / BE_API_ENDPOINT / BE_API_KEY 는 한 페이즈 동안만
#  fallback 으로 인식됨. 다음 페이즈에서 제거 예정.)
# ※ 과거 EXT_API_ENDPOINT / EXT_API_KEY (Q-IM 직접) 는 B-5 패치로 IdO forward proxy 경유로 전환,
#   Phase 2 에서 코드 자체 삭제. .env 항목도 더 이상 필요 없음.

# ─── 인증 관련 ─────────────────────────────────────
SKIP_AUTH=true                         # 개발 시 true → 로그인 없이 접근 가능
AES_GCM_KEY=DL5vnfsm01Cf...=          # CI AES-256-GCM 암호화 키 (Base64)
EASYSIGN_URL=https://easysign.anyid.go.kr/esign  # OACX EasySign 팝업 URL
EASYSIGN_ORIGIN=https://easysign.anyid.go.kr     # postMessage origin

# ─── 관측성 ────────────────────────────────────────
FARO_COLLECTOR_URL=/faro/collect       # Grafana Faro 수집 URL
```

### 6.2 환경별 설정 파일

```
frontend/
├── .env                  # 로컬 개발용 (gitignore)
├── .env.example          # 예제 (git에 포함)
├── .env.dev              # 개발 서버용 (gitignore)
└── .env.prod             # 운영 서버용 (gitignore)
```

### 6.3 Node.js 버전

```bash
# .nvmrc 참조
cat frontend/.nvmrc   # Node 18.x 권장
nvm use               # .nvmrc 자동 적용
```

---

## 7. 로컬 개발 실행 가이드

### 7.1 초기 설정

```bash
# 1. Node 버전 확인
cd onepass-fe/frontend
nvm use   # .nvmrc 기반

# 2. 패키지 설치
yarn install   # 또는 npm install

# 3. 환경 변수 복사
cp .env.example .env
# .env 편집: IDO_API_TARGET / IDO_API_ENDPOINT / IDO_API_KEY 설정 (Phase 2, PR #203)
```

### 7.2 개발 서버 실행

```bash
# 개발 모드 (portFinderSync — 3301 자동 배정)
yarn start
# → http://localhost:3301

# 프로덕션 빌드
yarn build

# 테스트 실행
yarn test

# 린팅
yarn lint
```

### 7.3 백엔드 없이 개발 (SKIP_AUTH=true)

```env
# .env
SKIP_AUTH=true
```

`SKIP_AUTH=true` 설정 시 Keycloak `actionUrl` 없이도 로그인 페이지가 렌더링됩니다.  
단, form.submit() 시 실제 Keycloak 연동은 불가능합니다.

### 7.4 Gradle 통합 실행 (백엔드 포함)

```bash
# 루트 디렉토리에서
./gradlew :onepass-fe:frontendDev
# → Webpack dev server 포트 3301에서 실행
# → Proxy를 통해 IDO_API_TARGET(8083, IdO 게이트웨이) 자동 연결
```

---

## 8. API 연동 계약

### 8.1 idoInstance API (IdO 게이트웨이 — ADR-008 단일 채널)

> Base URL: `IDO_API_TARGET` (로컬: `http://localhost:8083`, 운영: `https://onepass-ido-*.smes.go.kr`)  
> 헤더: `X-IDO-API-Key: ${IDO_API_KEY}`  
> Phase 2 (PR #203 / `a7065ae`) 명명 정합 완료. 구 명칭 `BE_API_*` / `X-BE-API-Key` / `beInstance` 는 코드에서 제거됨.

#### NICE 휴대폰 인증

```typescript
// GET /api/v1/auth/nice/phone/url
// Request Query: { returnUrl: string }
// Response:
interface NiceAuthUrlResponse {
  resultCode: string;  // '2000' = 성공
  resultMsg: string;
  authUrl?: string;    // NICE 표준창 URL
  requestNo?: string;  // 요청 번호
}

// POST /api/v1/auth/nice/phone/result
// Request:
interface NiceResultRequest {
  web_transaction_id: string;
  request_no?: string;
}
// Response:
interface NiceAuthResultResponse {
  resultCode: string;  // '2000' = 성공
  resultMsg: string;
  resultData?: {
    name?: string;
    birthdate?: string;
    ci?: string;         // CI (암호화 후 즉시 폐기)
    di?: string;
    mobileNo?: string;
    mobileCo?: string;
  };
}
```

#### OACX EasySign (개인 간편인증)

```typescript
// POST /api/v1/auth/oacx/access-info
// Request Body: 'simpleAuth' (string)
// Response:
interface AccessInfoResponse {
  resultCode: string;  // '2000' = 성공
  fn: string | null;
  accKey: string | null;
  accToken: string | null;
}

// POST /api/v1/auth/oacx/easysign
// Request Body: EasySign postMessage 원문 (string)
// Response: EasysignResult
interface EasysignResult {
  resultCode: string;
  resultMsg: string;
  ci?: string;
  name?: string;
  birthday?: string;
  phone?: string;
}
```

---

### 8.2 IdO `/api/ext/**` API (구 Q-IM EXT — IdO forward proxy 경유)

> Base URL: `IDO_API_TARGET` (§8.1 과 동일 단일 채널)  
> Webpack Proxy: `/api` → IdO (`/api/ext/**` 포함, 별도 proxy 없음)  
> 헤더: `X-IDO-API-Key: ${IDO_API_KEY}` (FE → IdO)  
> IdO 측에서 `ExtProxyController` 가 서버사이드 `X-Ext-Api-Key` 를 주입하여 Q-IM 으로 forward — FE 번들에 Q-IM 키 노출 0  
> **Phase 2 (PR #203 / `a7065ae`)**: 과거 `extInstance` / `EXT_API_*` 별도 채널은 **제거되었고**, 본 절의 모든 호출은 `idoInstance` 단일 채널로 통합됨.

#### CI 토큰 발급

```typescript
// POST /api/ext/ci/token
// 파일: src/api/provision/ciToken.ts

interface CiTokenRequest {
  encryptedCi: string;      // AES-256-GCM 암호화된 CI
  realm: string;            // 'ucube-qsign'
  clientId: string;         // 'onepassCli'
  flowContext: string;      // 'PROVISION_USER' | 'REGISTER_USER'
}

interface CiTokenResponse {
  data: {
    ciToken: string;        // JWT — 이후 요청에 포함
    mbrUuid: string;        // 회원 UUID
  }
}
```

#### 회원 조회 / 가입

```typescript
// POST /api/ext/provision/members  (개인 통합전환)
// POST /api/ext/provision/enterprises  (기업 통합전환)
// POST /api/ext/register/members  (개인 신규가입)
// POST /api/ext/register/enterprises  (기업 신규가입)
// 파일: src/api/provision/*.ts, src/api/ext/*.ts 참조
```

#### 중복 확인

```typescript
// POST /api/ext/members/check-duplicate
// 파일: src/api/ext/checkDuplicate.ts
interface CheckDuplicateRequest {
  type: 'IND' | 'ENT';
  value: string;  // IND: loginId, ENT: brno
}
interface CheckDuplicateResult {
  data: { exists: boolean; message: string }
}
```

#### 사업자 상태 조회 / 진위확인

```typescript
// POST /api/ext/business/status
// 파일: src/api/ext/businessStatus.ts
interface BusinessStatusRequest { bNo: string; }

// POST /api/ext/business/validate
// 파일: src/api/ext/businessValidate.ts
interface BusinessValidateRequest {
  bNo: string;
  startDt: string;        // YYYY-MM-DD
  representativeName: string;
  companyName: string;
}
interface BusinessValidateResult {
  data?: { valid: boolean; validMsg?: string; bStt?: string }
}
```

#### Clients (서비스 목록) / Consent

```typescript
// GET /api/ext/clients
// 파일: src/api/ext/clients.ts
// Response: Client[]

// POST /api/ext/consent
// 파일: src/api/ext/consent.ts
```

#### 미구현 / 미배포 API (P1~P2 구현 시 필요)

```typescript
// 아이디 찾기 (P2-3)
// POST /api/ext/members/find-id
// { email?: string; phone?: string }

// 비밀번호 찾기 (P2-3)
// POST /api/ext/members/find-password
// { loginId: string; email: string }

// 비밀번호 변경 (P1-1) — 현재 API 미배포
// PUT /api/ext/members/password
// { ciToken: string; newPassword: string }

// 통합회원 탈퇴 (P1-2) — 현재 API 미배포
// DELETE /api/ext/members/{mbrUuid}
// { ciToken: string; reason?: string }

// 개인 유관기관 탈퇴 (P1-3) — 현재 API 미배포
// DELETE /api/ext/provision/affiliations/{clientId}
// { ciToken: string }
```

---

## 9. 핵심 컴포넌트 & 훅 패턴 가이드

### 9.1 인증 훅 사용 패턴

#### NICE 휴대폰 인증

```tsx
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';

const handlePhoneAuthSuccess = useCallback((result: NicePhoneAuthResult): void => {
  if (result.resultCode !== '2000') {
    setFailedModal(true);
    return;
  }
  // CI 처리 후 다음 단계
  (async () => {
    const encrypted = await encryptCi(result.ci!);
    result.ci = undefined; // CI 평문 즉시 폐기
    const tokenResponse = await exchangeCiToken({ encryptedCi: encrypted, ... });
    if (tokenResponse.statusCode === 200) {
      updateData({ ciToken: tokenResponse.payload.data.ciToken });
      history.push(nextRoute);
    }
  })().catch(() => setFailedModal(true));
}, []);

const { busy, startAuth: startPhoneAuth } = useNicePhoneAuth(
  handlePhoneAuthSuccess,
  () => setFailedModal(true),
);

// 사용
<button disabled={busy} onClick={() => startPhoneAuth()}>
  휴대폰 인증
</button>
```

#### OACX EasySign (개인 간편인증)

```tsx
import usePersonalEasyAuth from 'hooks/usePersonalEasyAuth';
// EASYSIGN_URL, EASYSIGN_ORIGIN 환경변수 필요

const { busy, startAuth } = usePersonalEasyAuth(
  handleEasyAuthSuccess,  // EasysignResult 수신
  () => setFailedModal(true),
);

// 팝업 차단 주의: 반드시 사용자 클릭 이벤트에서 호출
<button onClick={() => startAuth()}>간편인증</button>
```

#### EzAuth SDK (기업 간편인증)

```tsx
import useEzAuth from 'hooks/useEzAuth';
// public/ezauth/js/EzAuth.bundle.js 로드 필요 (index.html <script>)

const { loading, startAuth: startEzAuth } = useEzAuth(
  (result?: EzAuthBizResult) => {
    if (!result) { setFailedModal(true); return; }
    updateData({ brno: result.businessNumber || '' });
    history.push(nextRoute);
  },
  (errno: number) => {
    if (errno === 302) return; // 사용자 취소 — 무시
    setFailedModal(true);
  },
);

// 사업자번호를 옵션으로 전달
<button disabled={loading} onClick={() => startEzAuth(brno)}>
  사업자 간편인증
</button>
```

---

### 9.2 ConversionProvider / RegisterProvider 상태 관리

```tsx
// ConversionContext 사용
import { useConversion } from 'providers/Conversion/ConversionContext';

const { data, updateData } = useConversion();

// 상태 업데이트 (부분 업데이트 지원)
updateData({
  ciToken: 'jwt-...',
  name: '홍길동',
  phone: '01012345678',
});

// 주요 필드
// data.memberType: 'member' | 'business'
// data.ciToken: CI 참조 토큰 (CI 평문은 저장하지 않음)
// data.brno: 사업자등록번호 (기업)
// data.selectedClients: 선택된 서비스 목록
// data.redirectUri: 대상 시스템 콜백 URL
```

---

### 9.3 KrdsModal 컴포넌트 패턴

```tsx
import Modal from 'components/KrdsModal';

// 기본 사용
<Modal
  id="modal-unique-id"
  isOpen={isOpen}
  onClose={() => setIsOpen(false)}
  topText="안내"
  title="모달 제목"
  size="small"  // 'small' | 'medium' (기본값)
  buttons={[
    {
      label: '닫기',
      variant: 'tertiary',
      onClick: () => setIsOpen(false),
    },
    {
      label: '확인',
      variant: 'primary',
      onClick: handleConfirm,
    },
  ]}
>
  <p>모달 내용</p>
</Modal>
```

---

### 9.4 CI 암호화 패턴

```typescript
// src/utils/crypto/aesGcm.ts
import { encryptCi } from 'utils/crypto/aesGcm';

// CI 수신 즉시 암호화하고 평문 폐기
const encrypted = await encryptCi(result.ci);
result.ci = undefined; // 반드시 undefined로 설정

// 암호화된 CI를 Q-IM에 전송하여 ciToken(JWT) 발급
const tokenResponse = await exchangeCiToken({
  encryptedCi: encrypted,
  realm: 'ucube-qsign',
  clientId: 'onepassCli',
  flowContext: 'PROVISION_USER',  // 또는 'REGISTER_USER'
});
```

> **보안 원칙**: CI 평문은 FE 메모리에 1ms도 보관하지 않습니다.  
> 암호화 즉시 원본을 `undefined`로 폐기합니다.

---

### 9.5 라우팅 패턴

```typescript
// ConversionSteps 라우팅
import { getConversionRoute } from '../routes';
// getConversionRoute(step: 1~8, memberType: 'member' | 'business')
// → '/conversion/member/step1' 등

// RegisterSteps 라우팅
import { getRegisterRoute } from '../routes';

// history 기반 이동
import history from 'lib/history';
history.push(getConversionRoute(4, memberType));
history.replace('/login');
```

---

## 10. devNoticeModal 제거 전략

### 10.1 단계별 제거 계획

각 파일에서 `devNoticeModal` 제거 시 아래 절차를 따릅니다:

```tsx
// Before (미구현 상태)
const [devNoticeModal, setDevNoticeModal] = useState(false);

<button onClick={() => setDevNoticeModal(true)}>공동인증서</button>

<Modal isOpen={devNoticeModal} ...>
  <p>현재 개발 중인 기능입니다.</p>
</Modal>
```

```tsx
// After (구현 완료 상태)
// 1. devNoticeModal 상태 변수 제거
// 2. onClick에 실제 인증 핸들러 연결
// 3. 미구현 Modal 블록 제거

const { startAuth: startCertAuth } = useCertAuth(
  handleCertAuthSuccess,
  () => setFailedModal(true),
);

<button onClick={() => startCertAuth()}>공동인증서</button>
```

### 10.2 전체 제거 완료 체크리스트

```bash
# devNoticeModal이 0건이 되어야 배포 준비 완료
grep -rn "setDevNoticeModal" src/ | wc -l
# → 0
```

---

## 11. 코딩 컨벤션

### 11.1 파일 구조

```
src/
├── api/           # API 호출 함수 (파일명 = 기능명, camelCase)
│   ├── idoInstance.ts  # IdO 게이트웨이 axios 인스턴스 (ADR-008 단일 채널 — Phase 2, PR #203)
│   ├── ext/            # idoInstance 사용 API — IdO forward proxy 경유 (구 Q-IM EXT)
│   ├── provision/      # 회원 provision API (idoInstance)
│   └── nice/           # NICE 인증 API (idoInstance)
├── components/    # 재사용 컴포넌트
├── constants/     # 상수 (images, mockData 등)
├── hooks/         # 커스텀 훅 (use* 접두사)
├── pages/         # 페이지 컴포넌트 (라우팅 단위)
├── providers/     # Context Provider
├── types/         # TypeScript 타입 정의
│   └── api/       # API 요청/응답 타입
└── utils/         # 유틸리티 함수
    └── crypto/    # CI 암호화 유틸
```

### 11.2 네이밍 규칙

```typescript
// 컴포넌트: PascalCase
function LoginPage(): JSX.Element {}

// 훅: useCamelCase
function useNicePhoneAuth() {}

// API 함수: camelCase
async function exchangeCiToken() {}

// 타입/인터페이스: PascalCase
interface NiceAuthUrlResponse {}

// 상수: UPPER_SNAKE_CASE
const LOGIN_ID_REGEX = /^[a-z0-9_.]+$/;

// 이벤트 핸들러: handle* 접두사
const handlePhoneAuthSuccess = useCallback(() => {}, []);
```

### 11.3 커밋 메시지 컨벤션 (commitlint)

```
feat(login): 아이디 찾기 기능 구현
fix(mypage): 비밀번호 변경 다음 단계 이동 버그 수정
refactor(step3): devNoticeModal 제거 — 공동인증서 연동
docs(fe): DEVELOPMENT.md 업데이트
test(hooks): useNicePhoneAuth 단위 테스트 추가
```

---

## 12. 테스트 전략

### 12.1 현재 테스트 파일

```bash
# 테스트 파일 존재 현황
find src -name "*.test.*" | sort
```

현재 `ConversionSteps/member/Step*.test.tsx` 스켈레톤 테스트 파일 존재.  
`hooks/useScrollToTop/useScrollToTop.test.ts` 등 일부 구현됨.

### 12.2 테스트 실행

```bash
yarn test                 # 전체 테스트
yarn test --watch         # 감시 모드
yarn test --coverage      # 커버리지 리포트
```

### 12.3 인증 훅 테스트 가이드

```typescript
// useNicePhoneAuth 테스트 예시
import { renderHook, act } from '@testing-library/react-hooks';
import useNicePhoneAuth from 'hooks/useNicePhoneAuth';

// idoInstance.get/post 모킹 필요 (Phase 2, PR #203)
jest.mock('api/idoInstance', () => ({
  get: jest.fn(),
  post: jest.fn(),
}));

test('startAuth → NICE URL 발급 성공 → 팝업 오픈', async () => {
  // ...
});
```

### 12.4 필수 테스트 작성 대상 (P1)

| 대상 | 우선순위 |
|------|----------|
| `useNicePhoneAuth` | P1 |
| `usePersonalEasyAuth` | P1 |
| `useEzAuth` | P1 |
| `exchangeCiToken` | P1 |
| `encryptCi` | P1 |
| `AccountForm` 중복확인 로직 | P2 |
| `ConversionSteps Step3` 렌더링 | P2 |

---

## 13. 보안 고려사항

### 13.1 CI (연계정보) 처리 원칙

```
✅ 올바른 처리
1. NICE/EasySign에서 CI 수신
2. 즉시 AES-256-GCM 암호화 (encryptCi)
3. 원본 CI = undefined (평문 폐기)
4. 암호화된 CI를 BE에 전달 → ciToken(JWT) 발급
5. 이후 ciToken만 사용 (FE 메모리에 유지)
6. 페이지 이동/세션 종료 시 ciToken 자동 소멸

❌ 절대 금지
- CI 평문을 localStorage/sessionStorage에 저장
- CI를 URL 파라미터로 전달
- CI를 console.log
```

### 13.2 환경 변수 보안

```
✅ git에 포함 (.env.example)
   - 키 이름 목록
   - 형식 예시 (실제값 X)

❌ git에 포함 금지 (.gitignore에 추가됨)
   - .env
   - .env.dev
   - .env.prod
   - API 키 실제 값
```

### 13.3 CSP 설정 (미구현 — P3)

```nginx
# frontend/conf/default.conf 에 추가 필요
add_header Content-Security-Policy "
  default-src 'self';
  script-src 'self' https://ezauth.smes.go.kr;
  connect-src 'self' https://onepass-dev.smes.go.kr;
  frame-src 'self' https://easysign.anyid.go.kr;
  img-src 'self' data:;
" always;
```

### 13.4 팝업 보안

- NICE: `postMessage` origin 검증 (`window.location.origin`)
- EasySign: `EASYSIGN_ORIGIN` 환경변수로 origin 검증
- EzAuth: `window.EzAuth` SDK 콜백 (SDK 자체 보안)

---

## 부록: 관련 링크

| 항목 | URL |
|------|-----|
| GitHub Repository | https://github.com/HipsterMIN/integration-sso |
| PR #41 (FE 소스 통합) | https://github.com/HipsterMIN/integration-sso/pull/41 |
| Q-Sign (Keycloak) 관리 | 내부망 접근 |
| NICE 본인인증 개발자 가이드 | https://www.niceid.co.kr |
| OACX EasySign | https://easysign.anyid.go.kr |
| Grafana Faro | https://faro.smes-tipa.go.kr |

---

*이 문서는 FE 개발팀이 실제 소스코드 분석을 기반으로 작성되었습니다.  
기능 구현 완료 시 해당 항목을 업데이트해 주세요.*
