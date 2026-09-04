# 가이드 01: 유관기관 → 통합인증(OnePass) 회원 전환 URL 플로우

| 항목 | 내용 |
|------|------|
| **문서 ID** | GUIDE-001 |
| **제목** | 유관기관 자체 로그인 후 OnePass 회원 전환 URL 플로우 분석 |
| **대상 독자** | 유관기관 개발팀, OnePass FE/BE 개발팀, 보안 검토자 |
| **최종 갱신** | 2026-05-16 (v0.8.9) |
| **관련 문서** | [GUIDE-002](./02-conversion-param-security.md) · [GUIDE-003](./03-conversion-launch-sample.md) · [GUIDE-004](./04-conversion-data-flow-diagram.md) |
| **관련 파일** | `idem-console/.../ConversionSteps/member/Step1.tsx` ~ `Step8.tsx` · `idem-hub/.../fe/session/FeSessionServiceImpl.java` · `idem-hub/.../handoff/validate/CallbackUrlValidator.java` |

> **⚠️ 중요**: 이 문서는 현재 코드의 버그를 포함한 실제 동작을 분석하고 수정 방향을 기술합니다.  
> **다른 점**과 **틀린 점**을 명시적으로 구분합니다.

---

## 1. 전제 — 이 플로우가 필요한 이유

OnePass(중기원패스)는 중소벤처기업부 산하 **68개 유관기관**의 SSO(Single Sign-On) 플랫폼입니다.  
각 유관기관은 **자체 회원 시스템**을 보유하며, 자체 로그인 후 OnePass 통합인증으로 **회원을 전환**할 수 있어야 합니다.

```
[기관 A: www.bizinfo.go.kr]  → 자체 로그인 완료 → OnePass 회원 전환 시작
[기관 B: www.sbiz.or.kr]    → 자체 로그인 완료 → OnePass 회원 전환 시작
[기관 C: www.fanfan.or.kr]  → 자체 로그인 완료 → OnePass 회원 전환 시작
        ...68개 기관 모두 서로 다른 도메인
```

---

## 2. 진입 URL 구조 (유관기관이 구성)

유관기관은 자체 로그인 완료 후 아래 URL로 사용자를 **302 리다이렉트**합니다.

```
https://onepass.smes.go.kr/conversion/step1
  ?redirect_uri={전환완료_후_돌아갈_기관URL}
  &mbrId={기관_회원_ID}
  &return_client={기관_client_id}
  &userType={ENT|IND}
```

### 파라미터 상세

| 파라미터 | 필수 | 설명 | 현재 보안 수준 |
|---|---|---|---|
| `redirect_uri` | ✅ 필수 | 전환 완료 후 돌아갈 **기관 URL** | ⚠️ 평문 노출 — 조작 가능 |
| `mbrId` | ✅ 필수 | **기관 측 회원 ID** (기관 DB 식별자) | ⚠️ 평문 노출 — 타 회원 ID 입력 가능 |
| `return_client` | ✅ 필수 | IdO HandoffController용 **기관 client_id** | ⚠️ 평문 노출 — 위조 가능 |
| `userType` | ✅ 필수¹ | 회원 유형 강제 지정 | ✅ 서버 검증 없으나 내부 분기만 사용 |

> ¹ `userType` 미전달 시 사용자가 step1에서 개인/기업을 직접 선택합니다.

### `return_client` 미전달 시 동작

```typescript
// Step1.tsx L36-39
if (redirectUri && mbrId && clientId) {
    updateData({ redirectUri, mbrId, initialClientId: clientId });
} else {
    setMissingParams(true);  // → 오류 모달 + history.back()
}
```

**→ `return_client` 없으면 step1에서 즉시 차단, 전환 불가**

---

## 3. OnePass FE 단계별 URL 시퀀스

### 3.1 라우트 구조

```
/conversion/step1                        ← 공통 진입 (개인/기업 선택)
    │
    ├─ userType=IND (개인)
    │   ├─ /conversion-member/step2      ← 약관 동의
    │   ├─ /conversion-member/step3      ← 본인인증
    │   ├─ /conversion-member/step4      ← 기관 연결 안내
    │   ├─ /conversion-member/step5      ← 계정 정보 입력
    │   ├─ /conversion-member/step6      ← 기관 연결 확인
    │   └─ /conversion-member/step8      ← 완료 + redirect_uri 이동
    │
    └─ userType=ENT (기업)
        ├─ /conversion-business/step2
        ├─ /conversion-business/step3
        ├─ /conversion-business/step4
        ├─ /conversion-business/step5
        ├─ /conversion-business/step6
        └─ /conversion-business/step8
```

> **step7 없음**: 설계 상 step7 번호는 사용되지 않습니다 (step6 → step8 직접 이동).

### 3.2 단계별 상세

| 단계 | URL | 주요 동작 | 저장 데이터 |
|---|---|---|---|
| **Step1** | `/conversion/step1` | URL 파라미터 파싱, 회원유형 선택 | `redirectUri`, `mbrId`, `initialClientId` |
| **Step2** | `/conversion-{type}/step2` | 약관 동의 (`flowContext: 'MEMBER_CONVERSION'`) | `consentEventId` |
| **Step3** | `/conversion-{type}/step3` | 본인인증 → CI 취득 → `ciToken` 발급 | `ciToken`, `mbrUuid`, `birthDate` |
| **Step4** | `/conversion-{type}/step4` | ~~기관 선택~~ → **CI 자동연결 안내** (읽기전용) | `selectedClients=[]` |
| **Step5** | `/conversion-{type}/step5` | 계정정보 입력 → `provisionUser()` | `mbrNo`, `mbrUuid`, `provisioningToken` |
| **Step6** | `/conversion-{type}/step6` | `checkConversionProxy()` → 연결 기관 확인 | — |
| **Step8** | `/conversion-{type}/step8` | 완료 화면 → `redirect_uri`로 이동 | — |

> **Step4 변경 이력**: 2026-05-15 장관 지시로 기관 선택 UI 폐기.  
> CI(연계정보) 기반 자동 기관 연결로 전환. `selectedClients`는 항상 빈 배열(`[]`).

### 3.3 라우트 접근 권한

모든 전환 라우트는 `isPrivate: false` — 미로그인 상태에서 접근 가능합니다.  
(전환 플로우 자체가 인증 수단이므로 사전 로그인 불필요)

---

## 4. 검증 체계 — 3개 레이어

현재 시스템은 `redirect_uri` / `returnUrl` 검증을 **3개의 독립된 레이어**에서 수행합니다.

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: FE Step8.tsx — isSafeRedirectUri()                │
│  Layer 2: BE FeSessionServiceImpl — isValidReturnUrl()      │
│  Layer 3: BE CallbackUrlValidator — HandoffTicket 발급 시   │
└─────────────────────────────────────────────────────────────┘
```

### Layer 1 — FE Step8.tsx `isSafeRedirectUri()`

```typescript
// 현재 코드 (버그 있음)
function isSafeRedirectUri(uri: string): boolean {
    try {
        const url = new URL(uri);
        return url.protocol === 'https:' && (
            url.hostname.endsWith('.smes.go.kr') ||
            url.hostname === 'smes.go.kr'
        );
    } catch {
        return false;
    }
}
```

| redirect_uri | 현재 결과 | 올바른 결과 |
|---|---|---|
| `https://www.bizinfo.go.kr/callback` | ❌ 차단 → `/login` | ✅ 허용해야 함 |
| `https://www.sbiz.or.kr/mypage` | ❌ 차단 → `/login` | ✅ 허용해야 함 |
| `https://fanfan.or.kr/done` | ❌ 차단 → `/login` | ✅ 허용해야 함 |
| `https://onepass.smes.go.kr/done` | ✅ 허용 | ✅ 허용 |
| `http://evil.com/phish` | ❌ 차단 | ❌ 차단 |

**→ `*.smes.go.kr` 하드코딩으로 68개 기관 중 `smes.go.kr` 서브도메인이 아닌 모든 기관이 차단됩니다.**

### Layer 2 — BE `FeSessionServiceImpl.isValidReturnUrl()`

```java
// FeSessionServiceImpl.java L137-141
@Value("${ido.fe.allowed-return-urls:}")
private List<String> allowedReturnUrls;

public boolean isValidReturnUrl(String returnUrl) {
    if (returnUrl == null || returnUrl.isBlank()) return false;
    return allowedReturnUrls.stream()
            .anyMatch(allowed -> returnUrl.startsWith(allowed.trim()));
}
```

```yaml
# application.yml 현재 값 (PoC 더미 — 운영 미적용)
ido:
  fe:
    allowed-return-urls:
      - https://agency-a.example.com
      - https://agency-b.example.com
      - http://localhost:8084
      - http://localhost:3000
      - http://localhost:3001
```

**→ 실제 기관 URL이 단 하나도 등록되어 있지 않습니다. 브로커 인증 후 returnUrl 검증 시 모든 기관이 거부됩니다.**

### Layer 3 — BE `CallbackUrlValidator` (HandoffTicket 발급)

```java
// CallbackUrlValidator.java — agency_meta.callback_whitelist DB 기반
// 매칭 규칙 (우선순위 순):
// 1. 완전 일치: "https://www.bizinfo.go.kr/callback"
// 2. 와일드카드: "*.bizinfo.go.kr"
// 3. 접두사: "https://www.bizinfo.go.kr" → 해당 도메인의 모든 경로 허용
```

**→ 이 레이어는 구조적으로 올바릅니다. DB `agency_meta.callback_whitelist`에 기관별로 등록하면 됩니다.**

---

## 5. 버그 vs 미설정 구분

> **"다른 점"과 "틀린 점"을 명시적으로 구분합니다.**

### ❌ 틀린 것 (버그 — 즉시 수정 필요)

| # | 위치 | 내용 | 영향 |
|---|---|---|---|
| B-1 | `Step8.tsx` `isSafeRedirectUri()` | `*.smes.go.kr` 하드코딩 | 68개 기관 중 `smes.go.kr` 외 기관 **전부 전환 완료 불가** |
| B-2 | `application.yml` `allowed-return-urls` | PoC 더미 URL만 등록 | 브로커 인증 후 기관 returnUrl **전부 검증 실패** |

### ⚠️ 다른 것 (설계 차이 — 의도적이나 위험)

| # | 위치 | 내용 | 위험도 |
|---|---|---|---|
| D-1 | `Step1.tsx` URL 파라미터 | `mbrId` 평문 노출 — 기관이 타 회원 ID 주입 가능 | 중간 |
| D-2 | `Step1.tsx` URL 파라미터 | `redirect_uri` 평문 노출 — 피싱 URL 주입 후 Layer 1 우회 시도 가능 | 중간 |
| D-3 | `Step1.tsx` URL 파라미터 | `return_client` 평문 노출 — 타 기관 client_id 위조 가능 | 높음 |
| D-4 | `ConversionContext` | 메모리 내 평문 상태 관리 — 페이지 새로고침 시 전환 상태 소실 | 낮음 |

### ✅ 올바른 것 (유지)

| # | 위치 | 내용 |
|---|---|---|
| O-1 | `HandoffController` | `Fe-Session-Id` 쿠키 기반 서버 측 qimUserId 추출 (P1 보안 수정 완료) |
| O-2 | `CallbackUrlValidator` | DB `agency_meta.callback_whitelist` 기반 검증 (구조 정상) |
| O-3 | `Step1.tsx` | `return_client` 필수 검증 (없으면 즉시 차단) |
| O-4 | 전체 라우트 | `isPrivate: false` (전환 플로우 진입 장벽 없음 — 의도된 설계) |

---

## 6. 수정 방향 요약

수정 상세는 [GUIDE-002](./02-conversion-param-security.md)를 참조하십시오.

```
[즉시 수정 — B-1]
  Step8.tsx isSafeRedirectUri()
  → BE API /api/v1/conversion/validate-redirect 위임
    또는 환경변수 REACT_APP_REDIRECT_ALLOWED_ORIGINS 주입

[즉시 수정 — B-2]
  application.yml ido.fe.allowed-return-urls
  → 실제 68개 기관 URL 등록 (환경변수 또는 DB 기반으로 전환)

[권장 수정 — D-1, D-2, D-3]
  URL 파라미터 JWT 서명 방식으로 전환
  → 유관기관이 OnePass와 공유한 비밀키로 파라미터 서명
  → Step1에서 서명 검증 후 ConversionContext 저장
```

---

> **다음 문서**: [GUIDE-002: 전환 파라미터 보안 (암호화 대안)](./02-conversion-param-security.md)
