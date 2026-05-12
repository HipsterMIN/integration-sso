# 시연 프로젝트 기술 현황 검토 보고서

> **회의 일시**: 2026-05-13 10:30  
> **문서 번호**: TECH-EVIDENCE-2026-05-13  
> **작성일**: 2026-05-12  
> **버전**: v1.0  
> **성격**: 기술 현황 검토 — 코드 레벨 근거 기반  
> **목적**: 정식 프로젝트 착수 전 현황 공유 및 방향 정렬

---

## 문서 목적

본 문서는 2026년 5월 13일 10:30 회의에서 **각 개발팀 및 관계자들과 현황을 공유**하고, 향후 정식 프로젝트 방향에 대해 기술적 근거를 바탕으로 논의하기 위한 자료입니다.

현재 시연 목적으로 구축된 `onepass-fe` / `onepass-be` 프로젝트(이하 "시연 프로젝트")는 개념 검증(PoC) 수준으로 의도적으로 제작된 것입니다. 본 문서는 이를 부정적으로 평가하는 것이 아니라, **시연 프로젝트의 현재 기술 상태를 정확히 파악하고** 정식 운영 시스템으로 전환하기 위해 어떤 작업이 필요한지를 코드 레벨에서 투명하게 공유하기 위한 목적으로 작성되었습니다.

---

## 목차

1. [시연 프로젝트 현황 요약](#1-시연-프로젝트-현황-요약)
2. [근거 ① Q-IM 직접 호출 구조 — FE가 ID 관리 서비스를 직접 바라봄](#2-근거--q-im-직접-호출-구조--fe가-id-관리-서비스를-직접-바라봄)
3. [근거 ② onepass-fe 시연용 코드 항목](#3-근거--onepass-fe-시연용-코드-항목)
4. [근거 ③ q-sign OIDC 브로커 역할 미구현](#4-근거--q-sign-oidc-브로커-역할-미구현)
5. [근거 ④ onepass-be(ido) BFF 범위 제한](#5-근거--onepass-beido-bff-범위-제한)
6. [통합 비교표 — 시연 프로젝트 vs integration-sso](#6-통합-비교표--시연-프로젝트-vs-integration-sso)
7. [정식 착수를 위한 권고사항](#7-정식-착수를-위한-권고사항)

---

## 1. 시연 프로젝트 현황 요약

### 1.1 모노레포 구성

```
integration-sso/
├── onepass-fe/          ← React 18/TypeScript 프론트엔드
│   └── frontend/
├── ido/                 ← BFF (Backend For Frontend) — Spring Boot
├── q-im/                ← ID 관리 서비스 — Spring Boot
├── q-sign/              ← 인증 처리 서비스 — Spring Boot + Keycloak
└── platform-common/     ← 공통 유틸리티
```

### 1.2 현황 한눈에 보기

| 항목 | 시연 프로젝트 현 상태 | 정식 운영 요구 수준 |
|------|------------------|-----------------|
| FE → 인증 경로 | Q-IM 직접 호출 26개 API | ido BFF 단일 경유 |
| 임시 비밀번호 생성 | `Math.random()` (FE 클라이언트) | CSPRNG / 서버 발급 |
| 인증 우회 스위치 | `SKIP_AUTH=true` (.env 활성) | 해당 스위치 제거 |
| AES 암호화 키 | `.env` 평문 번들 노출 | 서버사이드 키 관리(Vault/KMS) |
| OIDC 브로커 흐름 | Keycloak에 위임(q-sign은 콜백 처리만) | 표준 OIDC 브로커 전면 구현 |
| 기업인증 진위확인 | 코드 내 임시 스킵 처리 | 정식 API 연동 |
| Mock 데이터 | 폼 초기값에 MOCK_MEMBER 잔존 | 완전 제거 |

---

## 2. 근거 ① Q-IM 직접 호출 구조 — FE가 ID 관리 서비스를 직접 바라봄

### 2.1 이중 Axios 인스턴스 구조

`onepass-fe`는 백엔드와의 통신에 **두 개의 Axios 인스턴스**를 사용합니다.

```
파일: onepass-fe/frontend/src/api/extInstance.ts
```

```typescript
// extInstance — Q-IM을 직접 호출하는 인스턴스
const extInstance = axios.create({
    baseURL: process.env.EXT_API_ENDPOINT || '',  // → Q-IM 서버 주소
    headers: {
        'Content-Type': 'application/json',
        'X-API-Key': process.env.EXT_API_KEY || '',
    },
});
```

```
파일: onepass-fe/frontend/webpack.config.js (proxy 설정)
```

```javascript
proxy: {
    '/api/ext': {
        target: process.env.EXT_API_ENDPOINT || 'https://onepass-dev.smes.go.kr/im',
        // ↑ EXT_API_ENDPOINT = Q-IM 서버 직접 연결
    },
    '/api': {
        target: process.env.BE_API_TARGET || 'http://localhost:9292',
        // ↑ ido BFF 연결
    },
}
```

즉, **FE는 현재 두 개의 백엔드 서버에 동시에 의존**합니다. `/api/ext/**` 경로는 ido를 거치지 않고 Q-IM 서버로 직접 전달됩니다.

### 2.2 Q-IM 직접 호출 API 목록 (26개)

아래는 현재 `extInstance`를 통해 Q-IM 서버를 **직접** 호출하는 API 전체 목록입니다.

```
파일 위치: onepass-fe/frontend/src/api/ext/ 및 provision/ 디렉토리
```

| # | HTTP | 경로 | 기능 | 소스 파일 |
|---|------|------|------|----------|
| 1 | GET | `/api/ext/clients` | 연결 서비스 목록 조회 | `ext/clients.ts` |
| 2 | GET | `/api/ext/check-duplicate` | ID 중복 확인 | `ext/checkDuplicate.ts` |
| 3 | GET | `/api/ext/terms/bundle` | 약관 번들 조회 | `ext/termsBundle.ts` |
| 4 | POST | `/api/ext/consent/token` | 동의 토큰 발급 | `ext/consent.ts` |
| 5 | POST | `/api/ext/consent` | 동의 제출 | `ext/consent.ts` |
| 6 | GET | `/api/ext/auth-status` | 기업인증 상태 조회 | `ext/authResult.ts` |
| 7 | GET | `/api/ext/auth-result/{txId}` | 기업인증 결과 조회 | `ext/authResult.ts` |
| 8 | POST | `/api/ext/business/status` | 사업자 상태 조회(국세청) | `ext/businessStatus.ts` |
| 9 | POST | `/api/ext/business/validate` | 사업자 진위확인 | `ext/businessValidate.ts` |
| 10 | GET | `/api/ext/members/{mbrNo}` | 개인회원 조회 | `ext/members.ts` |
| 11 | GET | `/api/ext/members/{uuid}/affiliations` | 개인 유관서비스 목록 | `ext/members.ts` |
| 12 | GET | `/api/ext/enterprises/{entMbrNo}` | 기업회원 조회 | `ext/members.ts` |
| 13 | GET | `/api/ext/enterprises/{uuid}/affiliations` | 기업 유관서비스 목록 | `ext/members.ts` |
| 14 | POST | `/api/ext/provision/users/modify_local` | 개인회원 정보 수정 | `ext/members.ts` |
| 15 | POST | `/api/ext/provision/enterprises/modify_local` | 기업회원 정보 수정 | `ext/members.ts` |
| 16 | POST | `/api/ext/provision/users/check-conversion` | 전환 가능 여부 확인 | `ext/checkConversion.ts` |
| 17 | POST | `/api/ext/ci/token` | CI → ciToken(JWT) 발급 | `provision/ciToken.ts` |
| 18 | POST | `/api/ext/provision/users/check-conversion` | 전환 가능 여부 확인 | `provision/checkConversion.ts` |
| 19 | POST | `/api/ext/provision/users` | 개인회원 프로비저닝 | `provision/users.ts` |
| 20 | POST | `/api/ext/provision/enterprises` | 기업회원 프로비저닝 | `provision/enterprises.ts` |
| 21 | POST | `/api/ext/register/individual` | 개인회원 신규 등록 | `provision/registerIndividual.ts` |
| 22 | POST | `/api/ext/register/enterprise` | 기업회원 신규 등록 | `provision/registerEnterprise.ts` |
| 23 | POST | `/api/ext/provision/enterprises/{uuid}/affiliations/add` | 기업 유관기관 추가 | `provision/affiliations.ts` |
| 24 | POST | `/api/ext/provision/users/{uuid}/affiliations/add` | 개인 유관기관 추가 | `provision/affiliations.ts` |
| 25 | POST | `/api/ext/provision/enterprises/{uuid}/affiliations/withdraw` | 기업 유관기관 탈퇴 | `provision/affiliations.ts` |
| 26 | POST | `/api/ext/provision/users/{uuid}/affiliations/withdraw` | 개인 유관기관 탈퇴(ciToken 필요) | `provision/affiliations.ts` |

**핵심 관찰**: 위 26개 API 중 인증, 회원 등록, 프로비저닝, 약관, 동의, 기업인증, 탈퇴에 이르는 **핵심 업무 흐름 전체**가 Q-IM 직접 호출로 이루어집니다. ido BFF는 이 흐름에서 제외되어 있습니다.

### 2.3 아키텍처 구조 비교

```
[현재 시연 프로젝트 구조]

                      ┌─────────────────────────┐
                      │    Browser (FE)          │
                      │                          │
  /api/v1/auth/**     │  beInstance → ido BFF    │
  ← 6개 엔드포인트     │                          │
                      │  extInstance → Q-IM      │
  /api/ext/**         │  ← 26개 엔드포인트        │
  (핵심 업무 전체)     └─────────────────────────┘
                             │               │
                             ▼               ▼
                          [ido]           [Q-IM] ← 실질적 전면
                       (NICE/OACX 인증)  (회원관리 전담)


[integration-sso 목표 구조]

                      ┌─────────────────────────┐
                      │    Browser (FE)          │
                      │                          │
  /api/v1/**          │  단일 인스턴스 → ido BFF  │
  ← 모든 요청          │                          │
                      └─────────────────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────┐
                    │        ido BFF            │
                    │  ┌─────────┐ ┌─────────┐ │
                    │  │ 인증흐름 │ │ Q-IM 연동│ │
                    │  │ 보안정책 │ │ 감사로그 │ │
                    │  └─────────┘ └─────────┘ │
                    └──────────────────────────┘
                              │
                              ▼
                           [Q-IM]
                       (ido를 통해서만 접근)
```

---

## 3. 근거 ② onepass-fe 시연용 코드 항목

### 3.1 `SKIP_AUTH=true` — 인증 전체 우회

```
파일: onepass-fe/frontend/.env
```

```bash
# 인증 스킵 (개발용 true, 운영 false)
SKIP_AUTH=true
```

이 값이 `true`이면 아래 코드에서 **모든 인증 검사가 우회**됩니다.

```
파일: onepass-fe/frontend/src/AppRoutes/Private.tsx
```

```typescript
// 라인 67: 로그인 미완료여도 SKIP_AUTH가 true면 로그인 페이지로 리다이렉트 안 함
if (!isLoggedIn && process.env.SKIP_AUTH !== 'true') {
    history.push(ROUTES.LOGIN, { from: pathname });
}

// 라인 109: SKIP_AUTH=true면 인증 처리 자체를 건너뜀
if (process.env.SKIP_AUTH === 'true') {
    dispatch({ type: UPDATE_USER_IS_FETCH, payload: { isUserFetching: false } });
    return;  // ← 인증 없이 바로 return
}

// 라인 130: skipAuth 플래그가 컴포넌트 렌더링 조건 제어
const skipAuth = process.env.SKIP_AUTH === 'true';
```

**시사점**: 현재 `.env`에 `SKIP_AUTH=true`가 활성화된 상태에서 시연이 진행될 경우, 인증 흐름이 정상 동작하는 것처럼 보이더라도 실제로는 인증 검사를 거치지 않습니다.

중요한 점은, webpack의 `DefinePlugin`이 이 값을 **빌드 시점에 JS 번들에 인라인으로 삽입**하므로, 빌드된 산출물에도 이 설정이 그대로 반영됩니다.

```javascript
// webpack.config.js — DefinePlugin 설정
new webpack.DefinePlugin({
    'process.env': JSON.stringify({
        // ...
        SKIP_AUTH: process.env.SKIP_AUTH,   // ← 빌드 시 번들에 인라인 삽입
        AES_GCM_KEY: process.env.AES_GCM_KEY,  // ← 암호화 키도 동일하게 번들 삽입
    }),
}),
```

### 3.2 `AES_GCM_KEY` 평문 번들 노출

```
파일: onepass-fe/frontend/.env
```

```bash
# AES-256-GCM 키 (Q-IM CI 암호화용, base64 32바이트)
AES_GCM_KEY=DL5vnfsm01CfLlycU6k8NvCDy3tpO5/MXMuqV0uMCv8=
```

이 키는 CI(연계정보) 암호화에 사용되는 AES-256-GCM 키입니다. 현재 다음과 같이 FE 코드에서 직접 사용됩니다.

```
파일: onepass-fe/frontend/src/utils/crypto/aesGcm.ts
```

```typescript
// 환경변수에서 키를 직접 읽어서 사용
const AES_GCM_KEY = process.env.AES_GCM_KEY || '';

export async function encryptCi(ciPlaintext: string): Promise<string> {
    if (!AES_GCM_KEY) {
        throw new Error('AES_GCM_KEY 환경변수가 설정되지 않았습니다');
    }

    const keyBytes = base64ToBytes(AES_GCM_KEY);
    // ... AES-GCM 암호화 수행
}
```

```
파일 사용 위치:
- onepass-fe/frontend/src/pages/ConversionSteps/member/Step3.tsx (라인 42, 101)
- onepass-fe/frontend/src/pages/RegisterSteps/member/Step3.tsx (라인 53, 109)
- onepass-fe/frontend/src/pages/Mypage/pages/AffiliationWithdrawStep1.tsx (라인 159)
```

**구체적 보안 위험**:
- webpack `DefinePlugin`이 빌드 시점에 이 키를 JS 번들에 **평문으로 삽입**합니다.
- 배포된 웹페이지의 JS 파일을 브라우저 개발자 도구에서 열면 이 키가 문자열로 노출됩니다.
- 클라이언트사이드 암호화는 키 노출 시 암호화 자체가 무의미합니다.

> **참고**: CI(연계정보)는 개인을 고유하게 식별하는 민감 정보입니다. CI 암호화 키가 노출되면, 제3자가 CI 값을 복호화하거나 임의의 CI를 암호화하여 시스템에 주입할 수 있는 위험이 있습니다.

### 3.3 `Math.random()` — 기업 임시 비밀번호 생성

```
파일: onepass-fe/frontend/src/pages/ConversionSteps/member/Step5.tsx (라인 73–76)
```

```typescript
// 기업회원 프로비저닝 시 비밀번호가 없으면 임시 비밀번호를 FE에서 직접 생성
const password =
    data.password ||
    `Rnd${Math.random().toString(36).slice(2, 10)}!${Math.floor(
        Math.random() * 90 + 10,
    )}`;
```

`Math.random()`은 JavaScript 내장 함수로, **암호학적으로 안전한 난수 생성기(CSPRNG)가 아닙니다**. 브라우저 환경에서의 난수 예측 가능성, 동일 시드 재현 가능성 등의 문제로 보안 자격증명 생성에는 부적합합니다.

> **참고**: 이 이슈는 이미 BE 수준에서 개선 완료되었습니다. `GET /api/v1/auth/provision/temp-password` 엔드포인트가 `java.security.SecureRandom` 기반 CSPRNG로 서버사이드 임시 비밀번호를 발급합니다. FE가 이 엔드포인트를 사용하도록 연결하는 작업이 남아 있습니다.

### 3.4 `INITIAL_DATA` — Mock 데이터 폼 초기값 잔존

```
파일: onepass-fe/frontend/src/providers/Conversion/ConversionContext.tsx (라인 69–97)
```

```typescript
import { MOCK_BUSINESS, MOCK_MEMBER } from 'constants/mockData';

const INITIAL_DATA: ConversionData = {
    memberType: 'member',
    // ...
    email:       MOCK_MEMBER.emailId,      // ← Mock 데이터
    emailDomain: MOCK_MEMBER.emailDomain,  // ← Mock 데이터
    bzmnNm:      MOCK_BUSINESS.companyName,// ← Mock 데이터
    rprsvNm:     MOCK_BUSINESS.repName,    // ← Mock 데이터
    name:        MOCK_MEMBER.name,         // ← '홍길동' 등 Mock 이름
    phonePrefix: MOCK_MEMBER.phonePrefix,  // ← Mock 전화번호 접두사
    phoneSuffix: MOCK_MEMBER.phoneSuffix,  // ← Mock 전화번호
    // ...
};
```

```
파일: onepass-fe/frontend/src/constants/mockData.ts
```

```typescript
export const MOCK_MEMBER = {
    name: '홍길동',
    phonePrefix: '010',
    phoneSuffix: '12341234',
    // ...
} as const;
```

폼 초기값이 Mock 데이터로 채워진 채로 시작되므로, 시연 중 사용자가 필드를 비우지 않으면 Mock 값이 그대로 전송될 수 있습니다.

### 3.5 기업인증 진위확인 임시 스킵

```
파일: onepass-fe/frontend/src/pages/ConversionSteps/member/Step5.tsx (라인 46)
파일: onepass-fe/frontend/src/pages/ConversionSteps/member/components/AccountForm.tsx (라인 66, 73–74)
```

```typescript
// Step5.tsx — 기업인증 완료 후 설립일 조건 주석 처리
// TODO: 기업인증 구현 후 !data.startDt 조건 복원
if (!data.bzmnNm || !data.rprsvNm || !data.brno || ...)
// ↑ data.startDt 체크가 주석 처리됨

// AccountForm.tsx — 진위확인 API 호출 임시 스킵
// 필수값 체크 (설립일은 기업인증 미구현으로 임시 제외)
// TODO: 기업인증 구현 후 진위확인 API 호출 활성화
// ① 진위확인 — 임시 스킵 (기업인증 미구현으로 설립일 미확보)
```

기업인증 진위확인 흐름이 현재 코드상 스킵되어 있습니다.

### 3.6 기타 TODO/임시 처리 항목 요약

```
파일 내 주석 기준 — grep 결과
```

| 위치 | 주석 내용 |
|------|----------|
| `NavItem.tsx:95` | `TODO 데모 완료 후 임시 비활성화 제거필요` |
| `utils/app.ts:18` | `2026.04.15 임시주석: 타 환경 배포를 위해 기준 도메인 체크 비활성화` |
| `PasswordStep1.tsx:19` | `TODO: API 배포 후 복원 — 인증 성공 시 다음 단계로 이동` |
| `Withdraw.tsx:17` | `TODO: API 배포 후 복원 — goNext 로 다음 단계 이동` |

---

## 4. 근거 ③ q-sign OIDC 브로커 역할 미구현

### 4.1 현재 q-sign의 역할

q-sign은 **OIDC 브로커**가 아닌 **Keycloak 콜백 수신 및 인증 결과 저장** 역할을 합니다.

표준 OIDC 브로커는 다음 엔드포인트를 직접 노출해야 합니다:

| 표준 OIDC 엔드포인트 | q-sign 구현 여부 | 비고 |
|--------------------|----------------|------|
| `GET /.well-known/openid-configuration` | ❌ 미구현 | OIDC Discovery |
| `GET /authorize` | ❌ 미구현 | 인증 코드 발급 |
| `POST /token` | ❌ 미구현 | 토큰 교환 |
| `GET /userinfo` | ❌ 미구현 | 사용자 정보 |
| `GET /jwks` | ❌ 미구현 | 공개키 노출 |
| `POST /introspect` | ❌ 미구현 | 토큰 검증 |

현재 q-sign이 구현하는 엔드포인트:

```
파일: q-sign/src/main/java/kr/go/smes/qsign/api/AuthController.java
```

```java
@RequestMapping("/api/v1/auth")
public class AuthController {
    // POST /api/v1/auth/oidc       — 직접 OIDC 경로 (코드 내 '현재 사용되지 않음' 명시)
    // POST /api/v1/auth/broker-input — 비OIDC 인증 정규화 입력 (내부 전용)
    // GET  /api/v1/auth/{authResultId} — 인증 결과 조회
}
```

### 4.2 코드 내 '미사용' 명시

```
파일: q-sign/src/main/java/kr/go/smes/qsign/application/AuthServiceImpl.java (라인 44–57)
```

```java
/**
 * OIDC 인증 결과 발급 (레거시 경로 — /api/v1/auth/oidc 엔드포인트)
 *
 * <p>Keycloak 브로커 흐름(흐름 A)에서는 이 메서드가 호출되지 않는다.
 * Keycloak 흐름은 {@code KeycloakCallbackService.handleCallback()} 이 직접 처리한다.
 * 이 메서드는 흐름 B(NonOidc broker-input 경로)에서만 사용된다.
 *
 * <p>이 경로로 idToken 이 전달되는 경우는 Keycloak 이 아닌 직접 OIDC 연동 시나리오로,
 * 현 설계에서는 사용되지 않는다. 하위 호환성을 위해 메서드는 유지한다.
 *
 * ...
 * ⚠️  이 경로가 실제 운용될 경우 반드시 idToken 파싱 후 SHA-256(sub) 로 교체해야 한다.
 */
```

### 4.3 현재 실제 인증 흐름

```
[현재 구현된 흐름]

  FE
  │
  └──→ Keycloak (인증 담당)
           │ 인증 완료
           └──→ q-sign/callback (결과 수신 및 저장)
                      │
                      └──→ ido /api/internal/v1/oidc/complete (세션 발급 요청)
```

```
[표준 OIDC 브로커 흐름 목표]

  외부 서비스(SP)
  │
  └──→ q-sign /.well-known/openid-configuration 탐색
           │
           └──→ q-sign /authorize (인증 요청)
                      │
                      └──→ IdP (Keycloak/PASS/GPKI 등)
                                 │ 인증 완료
                                 └──→ q-sign /callback
                                            │ 토큰 교환
                                            └──→ 외부 서비스에 id_token 발급
```

**핵심**: 현재 q-sign은 **OIDC 프로토콜의 Provider(OP) 역할**을 하지 않습니다. 외부 서비스가 q-sign에 OIDC 인증을 요청할 수 있는 표준 인터페이스가 없습니다.

### 4.4 PoC 모드 허용 코드

```
파일: q-sign/src/main/java/kr/go/smes/qsign/api/InternalSigVerifier.java (라인 34–35, 99, 115)
```

```java
// PoC에서 IdO BrokerService.buildInternalSig() 는 단순 접두어 방식이므로,
// q-sign 모드(PoC)에서는 서명 검증을 건너뛸 수 있도록 strict-mode 설정을 제공한다.

// PoC 환경(broker.mode=qsign)에서 IdO가 단순 접두어 서명을 보내는 경우 허용.
return !strictMode; // strict=false → PoC 허용, strict=true → 거부
```

내부 서명 검증 자체에도 PoC용 허용 모드(`strict-mode=false`)가 존재합니다.

---

## 5. 근거 ④ onepass-be(ido) BFF 범위 제한

### 5.1 현재 ido가 담당하는 인증 엔드포인트

```
파일: ido/src/main/java/kr/go/smes/ido/auth/controller/AuthController.java
```

```java
@RequestMapping("/api/v1/auth")
public class AuthController {
    // ① GET  /nice/phone/url        — NICE 휴대폰 인증 URL 발급
    // ② POST /nice/phone/result     — NICE 휴대폰 인증 결과 수신
    // ③ POST /nice/ci-check         — CI 기반 회원 존재 확인 (조회 전용)
    // ④ POST /oacx/access-info      — OACX 간편인증 접근키/토큰 발급
    // ⑤ POST /oacx/easysign         — OACX 간편서명 결과 처리
    // ⑥ GET  /provision/temp-password — CSPRNG 임시 비밀번호 발급 (신규 추가)
    // ⑦ POST /callback              — 인증 콜백
}
```

이 7개 엔드포인트는 **NICE/OACX 본인인증 흐름**에 특화되어 있으며, 나머지 회원 관리·프로비저닝·약관·동의 등 핵심 업무는 ido를 통하지 않고 FE가 Q-IM에 직접 호출합니다.

### 5.2 ido가 처리하지 않는 영역

현재 ido를 경유하지 않는 영역:

| 기능 카테고리 | FE 직접 호출 여부 |
|-------------|----------------|
| 회원 조회 / 수정 | Q-IM 직접 |
| 프로비저닝 (등록) | Q-IM 직접 |
| 약관 동의 처리 | Q-IM 직접 |
| 클라이언트 목록 조회 | Q-IM 직접 |
| ID 중복 확인 | Q-IM 직접 |
| CI 토큰 발급 | Q-IM 직접 |
| 기업인증 조회 | Q-IM 직접 |
| 유관기관 추가/탈퇴 | Q-IM 직접 |

---

## 6. 통합 비교표 — 시연 프로젝트 vs integration-sso

| 검토 항목 | 시연 프로젝트 현 상태 | integration-sso 목표 | 상태 |
|----------|------------------|-------------------|------|
| **아키텍처 단일화** | FE → ido + FE → Q-IM 이중 경로 | FE → ido BFF → Q-IM 단일 경로 | 🔴 재설계 필요 |
| **인증 우회 스위치** | `SKIP_AUTH=true` 활성 | 해당 스위치 제거 | 🔴 제거 필요 |
| **암호화 키 관리** | `AES_GCM_KEY` .env 평문 번들 | 서버사이드 Vault/KMS 관리 | 🔴 구조 변경 필요 |
| **임시 비밀번호 생성** | `Math.random()` FE 클라이언트 생성 | `SecureRandom` 서버 발급 완료 ✅ | 🟡 FE 연결 작업 필요 |
| **Mock 데이터** | 폼 초기값에 MOCK_MEMBER 잔존 | 완전 제거 | 🟡 정리 필요 |
| **OIDC 브로커 표준화** | Keycloak 위임, q-sign은 콜백 처리만 | q-sign이 표준 OIDC OP 역할 | 🔴 설계·구현 필요 |
| **내부 서명 검증** | `strict-mode=false` PoC 허용 | `strict-mode=true` 운영 전환 | 🟡 설정 변경 필요 |
| **기업인증 진위확인** | 코드 내 임시 스킵 | 정식 API 연동 | 🔴 구현 필요 |
| **감사 로그** | Q-IM 직접 호출 구간 미기록 | ido BrokerAuditLog 전 구간 기록 | 🔴 구조 변경 후 자동 확보 |
| **Rate Limiting** | 기관별 제어 불가 | ido AgencyRateLimiter 기관별 제어 | 🔴 구조 변경 후 자동 확보 |
| **CI 보안 (Q3=B)** | FE에서 CI 직접 암호화·전송 | CI FE 미반환, BE 내부 처리만 ✅ | 🔴 FE 구조 변경 필요 |
| **컴파일·테스트** | — | BE 전체 BUILD SUCCESSFUL, 212 테스트 통과 ✅ | ✅ BE 준비 완료 |

**범례**: ✅ 완료 · 🟡 부분 완료 또는 소규모 작업 필요 · 🔴 설계/구현 필요

---

## 7. 정식 착수를 위한 권고사항

### 7.1 요약

시연 프로젝트는 개념 검증(PoC) 목적으로 개발된 것으로, 이 목적에서는 충분히 역할을 했습니다. 다만, 정식 운영 시스템으로의 전환을 위해서는 아래 영역에서 **설계 기반의 재구현**이 필요합니다.

`integration-sso` 모노레포의 BE 코드(`ido`, `q-im`, `q-sign`)는 이미 운영 수준의 구조를 갖추고 있습니다. BE를 기준점으로 삼아 FE 구조를 정렬하고, OIDC 브로커 표준화를 진행하는 것이 가장 효율적인 경로입니다.

### 7.2 단계별 권고사항

**1단계 — FE 구조 정렬** (최우선)
- `extInstance` 직접 호출 26개 API를 ido BFF 경유로 전환
- `SKIP_AUTH` 스위치 제거
- `AES_GCM_KEY` FE 번들 노출 제거 → 서버사이드 CI 처리로 전환 (integration-sso Q3=B 원칙 적용)
- `Math.random()` 임시 비밀번호 → `GET /api/v1/auth/provision/temp-password` 연결

**2단계 — q-sign OIDC 브로커 표준화**
- `/.well-known/openid-configuration` 등 표준 OIDC 엔드포인트 구현
- 외부 서비스(SP)가 q-sign을 OIDC Provider로 직접 연동할 수 있는 인터페이스 확보

**3단계 — 운영 환경 전환**
- `strict-mode=true` 활성화 (내부 서명 검증 강화)
- Mock 데이터 완전 제거
- 기업인증 진위확인 흐름 구현

### 7.3 참고 — BE 현재 준비 상태

integration-sso BE는 다음 항목이 이미 완료된 상태입니다.

| 항목 | 상태 |
|------|------|
| 전체 모듈 컴파일 | ✅ BUILD SUCCESSFUL |
| 전체 테스트 | ✅ 212개 테스트, 0 failures |
| CI 보안(Q3=B) | ✅ CI FE 미반환, BE 내부 처리 |
| CSPRNG 임시 비밀번호 API | ✅ `GET /api/v1/auth/provision/temp-password` |
| CI 조회 전용 전환 | ✅ 미등록 CI = 4040 응답, 신규 등록 없음 |
| correlationId UUID화 | ✅ `UUID.randomUUID()` 적용 |
| Redisson 분산 락 | ✅ K8s 다중 Pod 안전 |
| Kafka Outbox 패턴 | ✅ 구현 완료 |
| Dockerfile (전 모듈) | ✅ 멀티스테이지 빌드, non-root |

---

## 부록 — 코드 파일 참조 목록

| 근거 | 파일 경로 | 핵심 코드/설정 |
|------|----------|--------------|
| Q-IM 직접 호출 | `onepass-fe/frontend/src/api/extInstance.ts` | `baseURL: EXT_API_ENDPOINT (Q-IM 주소)` |
| Proxy 설정 | `onepass-fe/frontend/webpack.config.js` | `/api/ext` → Q-IM, `/api` → ido |
| SKIP_AUTH 우회 | `onepass-fe/frontend/.env` | `SKIP_AUTH=true` |
| SKIP_AUTH 코드 | `onepass-fe/frontend/src/AppRoutes/Private.tsx:67,109,130` | `process.env.SKIP_AUTH === 'true'` |
| AES_GCM_KEY 노출 | `onepass-fe/frontend/.env` | `AES_GCM_KEY=DL5vnfsm01Cf...` |
| AES_GCM_KEY 번들 삽입 | `onepass-fe/frontend/webpack.config.js` | `DefinePlugin` 내 `AES_GCM_KEY` |
| AES_GCM_KEY 사용 | `onepass-fe/frontend/src/utils/crypto/aesGcm.ts:10` | `const AES_GCM_KEY = process.env.AES_GCM_KEY` |
| Math.random() | `onepass-fe/frontend/src/pages/ConversionSteps/member/Step5.tsx:73–76` | 기업 임시 비밀번호 생성 |
| Mock 데이터 | `onepass-fe/frontend/src/providers/Conversion/ConversionContext.tsx:69–97` | `INITIAL_DATA` 내 MOCK_MEMBER/MOCK_BUSINESS |
| q-sign 미사용 OIDC 경로 | `q-sign/src/main/java/.../AuthServiceImpl.java:44–57` | `현 설계에서는 사용되지 않는다` 주석 |
| q-sign OIDC 엔드포인트 부재 | `q-sign/src/main/java/` (전체) | `/.well-known`, `/authorize`, `/token` 없음 |
| PoC 모드 서명 우회 | `q-sign/src/main/java/.../InternalSigVerifier.java:115` | `return !strictMode` |
| ido 인증 엔드포인트 | `ido/src/main/java/.../AuthController.java` | 7개 엔드포인트 (NICE/OACX 특화) |

---

*본 문서는 코드베이스를 직접 분석한 결과를 바탕으로 작성되었습니다. 각 근거의 파일 경로와 코드 위치를 직접 확인하실 수 있습니다.*

*작성: 2026-05-12 | 문서 버전: v1.0 | 브랜치: `genspark_ai_developer`*
