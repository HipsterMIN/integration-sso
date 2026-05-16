# 가이드 04: 회원 전환 데이터 흐름 — 순번 다이어그램

| 항목 | 내용 |
|------|------|
| **문서 ID** | GUIDE-004 |
| **제목** | 유관기관 → OnePass 회원 전환 전체 데이터 흐름 (순번 다이어그램) |
| **대상 독자** | OnePass FE/BE 개발팀, 유관기관 개발팀, 보안 검토자, 아키텍처 검토자 |
| **최종 갱신** | 2026-05-16 (v0.8.9) |
| **관련 문서** | [GUIDE-001](./01-agency-conversion-url-flow.md) · [GUIDE-002](./02-conversion-param-security.md) · [GUIDE-003](./03-conversion-launch-sample.md) |

> **범례**  
> ① ~ ㉞ — 순번 (브라우저 행위 포함 전체 순서)  
> `[현재]` — 현재 구현 동작  
> `[권장]` — GUIDE-002 적용 후 개선 동작  
> ⚠️ — 버그 또는 미설정 항목

---

## 다이어그램 1: 전체 흐름 개요 (아스키 아트)

```
┌──────────────┐    ┌──────────────────────────────────────────────────────────────┐    ┌────────────────┐
│ 기관 서버/FE  │    │                    OnePass 시스템                             │    │  외부 인증     │
│              │    │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐   │    │  서비스        │
│              │    │  │ onepass-fe   │  │  IdO :8083   │  │  Q-IM :8082      │   │    │                │
│              │    │  │              │  │              │  │                  │   │    │ ┌────────────┐  │
│              │    │  │  Step1~Step8 │  │  BE API      │  │  회원 데이터     │   │    │ │NICE 본인인증│  │
│              │    │  │              │  │              │  │                  │   │    │ └────────────┘  │
└──────────────┘    │  └──────────────┘  └──────────────┘  └──────────────────┘   │    │ ┌────────────┐  │
                    │                            │                  │              │    │ │국세청 진위  │  │
                    │                       ┌──────────┐      ┌──────────┐        │    │ └────────────┘  │
                    │                       │  Redis   │      │PostgreSQL│        │    └────────────────┘
                    │                       │(Session) │      │  (DB)    │        │
                    │                       └──────────┘      └──────────┘        │
                    └──────────────────────────────────────────────────────────────┘

  ① 기관이 전환 URL 구성 후 사용자 브라우저 리다이렉트
  ② ~ ⑧ OnePass FE step1~step3 (파라미터 검증 + 약관 + 본인인증)
  ⑨ ~ ⑯ OnePass FE step4~step6 (기관 연결 + 계정 생성 + 연결 확인)
  ⑰ ~ ㉓ OnePass FE step8 + 기관 복귀 (완료 화면 + redirect_uri)
  ㉔ ~ ㉘ 기관 서버: Handoff Ticket 검증 (서버-서버)
```

---

## 다이어그램 2: 상세 시퀀스 — 현재 방식 (레거시 평문 파라미터)

```mermaid
sequenceDiagram
    autonumber
    actor User as 사용자 (Browser)
    participant AgencyFE as 기관 시스템
    participant OnePassFE as onepass-fe
    participant IdO as IdO BE (:8083)
    participant QIM as Q-IM BE (:8082)
    participant Redis as Redis
    participant NICE as NICE 본인인증
    participant NTS as 국세청 (기업전용)

    Note over AgencyFE: [전제] 기관 자체 로그인 완료 상태

    %% ── Phase 1: 진입 ─────────────────────────────────────────────────
    rect rgb(255, 248, 225)
        Note over User,IdO: Phase 1: 전환 진입 (Step1)

        AgencyFE->>User: ① 302 redirect<br/>GET /conversion/step1<br/>?redirect_uri=https://www.bizinfo.go.kr/cb<br/>&mbrId=BIZ_USER_001<br/>&return_client=sp-bizinfo<br/>&userType=IND
        Note right of AgencyFE: ⚠️ 평문 파라미터 — 위변조 가능<br/>(D-1, D-2, D-3 보안 이슈)

        User->>OnePassFE: ② GET /conversion/step1?redirect_uri=...&mbrId=...

        OnePassFE->>OnePassFE: ③ URL 파라미터 파싱<br/>· redirect_uri, mbrId, return_client, userType<br/>· return_client 없으면 → missingParams 모달
        Note right of OnePassFE: [현재] 평문 값 그대로 ConversionContext 저장<br/>[권장] POST /api/v1/conversion/init 호출

        OnePassFE->>User: ④ 회원유형 선택 UI 표시<br/>(userType=IND → 개인 사전 선택)
        User->>OnePassFE: ⑤ 개인/기업 선택 후 다음 클릭
    end

    %% ── Phase 2: 약관 동의 ────────────────────────────────────────────
    rect rgb(232, 245, 233)
        Note over User,IdO: Phase 2: 약관 동의 (Step2)

        User->>OnePassFE: ⑥ GET /conversion-member/step2
        OnePassFE->>IdO: ⑦ POST /api/v1/consent/issue<br/>{ flowContext: 'MEMBER_CONVERSION' }
        IdO-->>OnePassFE: ⑧ { consentEventId: 12345 }
        OnePassFE->>OnePassFE: consentEventId → ConversionContext 저장
        User->>OnePassFE: ⑨ 약관 전체 동의 후 다음 클릭
        OnePassFE->>IdO: ⑩ POST /api/v1/consent/submit<br/>{ consentEventId: 12345, agreed: true }
        IdO-->>OnePassFE: ⑪ 200 OK
    end

    %% ── Phase 3: 본인인증 ─────────────────────────────────────────────
    rect rgb(227, 242, 253)
        Note over User,NICE: Phase 3: 본인인증 (Step3) — 개인회원 기준

        User->>OnePassFE: ⑫ GET /conversion-member/step3
        OnePassFE->>IdO: ⑬ GET /api/v1/auth/nice/phone/url<br/>?returnUrl={step3_callback_url}
        IdO->>NICE: ⑭ requestAuthUrl(accessToken, requestNo, returnUrl)
        NICE-->>IdO: ⑮ { authUrl, requestNo }
        IdO-->>OnePassFE: ⑯ { authUrl, requestNo }
        OnePassFE->>User: ⑰ NICE 인증 팝업 또는 리다이렉트

        User->>NICE: ⑱ 실명 + 휴대폰 인증 완료
        NICE->>OnePassFE: ⑲ 인증 결과 콜백 (CI 포함)

        OnePassFE->>IdO: ⑳ POST /api/v1/auth/ci-token<br/>{ encCi: AES-GCM-암호화된-CI }
        Note right of OnePassFE: CI 자체는 FE에 평문 저장 안 함
        IdO-->>OnePassFE: ㉑ { ciToken: 'JWT-CI-참조토큰', mbrUuid: '...' }
        OnePassFE->>OnePassFE: ciToken, mbrUuid → ConversionContext 저장
    end

    %% ── Phase 4: 기관 연결 안내 ───────────────────────────────────────
    rect rgb(243, 229, 245)
        Note over User,IdO: Phase 4: 기관 연결 안내 (Step4) — 읽기전용

        User->>OnePassFE: ㉒ GET /conversion-member/step4
        Note right of OnePassFE: 2026-05-15 장관 지시:<br/>기관 선택 UI 폐기 → 읽기전용 안내<br/>selectedClients = [] (CI 기반 자동 연결)
        OnePassFE->>User: ㉓ 연결될 기관 안내 화면 (선택 불가)
        User->>OnePassFE: ㉔ 다음 클릭
    end

    %% ── Phase 5: 계정 생성 ────────────────────────────────────────────
    rect rgb(255, 235, 238)
        Note over User,QIM: Phase 5: 계정 정보 입력 + 계정 생성 (Step5)

        User->>OnePassFE: ㉕ GET /conversion-member/step5
        User->>OnePassFE: ㉖ 로그인ID, 비밀번호, 이메일, 전화번호 입력
        OnePassFE->>QIM: ㉗ POST /api/ext/provision/users<br/>{ ciToken, memberName, loginId, password,<br/>  clients: [], indvMblTelno, indvEmlAddr, ... }
        QIM->>QIM: ㉘ CI 검증 + 중복 회원 확인
        QIM-->>OnePassFE: ㉙ { mbrNo, mbrUuid, provisioningToken }
        OnePassFE->>OnePassFE: mbrNo, mbrUuid, provisioningToken → ConversionContext
    end

    %% ── Phase 6: 기관 연결 확인 ───────────────────────────────────────
    rect rgb(224, 247, 250)
        Note over User,QIM: Phase 6: 기관 연결 확인 (Step6)

        User->>OnePassFE: ㉚ GET /conversion-member/step6
        OnePassFE->>QIM: ㉛ POST /api/ext/provision/users/check-conversion<br/>{ ciToken, mbrId, mbrUuid }
        QIM-->>OnePassFE: ㉜ { linkedClients: [...] }
        OnePassFE->>User: ㉝ 연결된 기관 목록 표시 (ServiceListModal)
        User->>OnePassFE: ㉞ 완료 클릭
    end

    %% ── Phase 7: 완료 + 기관 복귀 ────────────────────────────────────
    rect rgb(255, 243, 205)
        Note over User,AgencyFE: Phase 7: 완료 + 기관 복귀 (Step8)

        User->>OnePassFE: ㉟ GET /conversion-member/step8
        OnePassFE->>OnePassFE: ㊱ isSafeRedirectUri(redirectUri) 검증
        Note right of OnePassFE: ⚠️ [현재 버그 B-1]<br/>*.smes.go.kr만 허용<br/>→ www.bizinfo.go.kr 차단됨!<br/><br/>[수정 후]<br/>환경변수 기반 허용 도메인 검증

        OnePassFE->>User: ㊲ 302 redirect<br/>https://www.bizinfo.go.kr/cb<br/>?ticketId=HT-xxxx&status=success

        User->>AgencyFE: ㊳ GET /cb?ticketId=HT-xxxx&status=success
        AgencyFE->>IdO: ㊴ POST /api/v1/handoff/verify<br/>Headers: X-Agency-Code, X-Api-Key<br/>Body: { ticketId: 'HT-xxxx' }
        IdO-->>AgencyFE: ㊵ { qimUserId, authLevel, authResultId, ... }
        AgencyFE->>AgencyFE: ㊶ 기관 세션 생성 → 서비스 이용 시작
    end
```

---

## 다이어그램 3: Phase별 데이터 상태 추적

```
사용자 브라우저 ConversionContext 상태 변화 (React In-Memory):

Phase 1 완료 후:
┌────────────────────────────────────────────────────┐
│ ConversionContext                                  │
│  redirectUri   = "https://www.bizinfo.go.kr/cb"   │ ← URL 파라미터에서
│  mbrId         = "BIZ_USER_001"                   │ ← URL 파라미터에서
│  initialClientId = "sp-bizinfo"                   │ ← URL 파라미터에서
│  memberType    = "member"                          │ ← userType=IND
└────────────────────────────────────────────────────┘

Phase 2 완료 후:
┌────────────────────────────────────────────────────┐
│ ConversionContext                                  │
│  ... (이전 값 유지)                               │
│  consentEventId = 12345                            │ ← IdO 발급
└────────────────────────────────────────────────────┘

Phase 3 완료 후:
┌────────────────────────────────────────────────────┐
│ ConversionContext                                  │
│  ... (이전 값 유지)                               │
│  ciToken   = "eyJhbGci..."                         │ ← IdO CI 참조토큰
│  mbrUuid   = "qim-uuid-xxx"                        │ ← Q-IM 발급
│  birthDate = "19900101"                            │ ← NICE 인증 결과
│  ※ CI 원문은 FE에 저장되지 않음 (보안)            │
└────────────────────────────────────────────────────┘

Phase 4 완료 후:
┌────────────────────────────────────────────────────┐
│ ConversionContext                                  │
│  ... (이전 값 유지)                               │
│  selectedClients = []    ← 항상 빈 배열 (장관 지시 2026-05-15)
└────────────────────────────────────────────────────┘

Phase 5 완료 후:
┌────────────────────────────────────────────────────┐
│ ConversionContext                                  │
│  ... (이전 값 유지)                               │
│  mbrNo            = "MBR_20260516_001"             │ ← Q-IM 발급
│  mbrUuid          = "qim-uuid-xxx"                 │
│  provisioningToken = "prov-token-xxx"              │ ← Q-IM 발급
└────────────────────────────────────────────────────┘
```

---

## 다이어그램 4: 검증 레이어별 데이터 흐름

```
redirect_uri 검증 3-레이어 상세 흐름:

┌─────────────────────────────────────────────────────────────────────────────┐
│ LAYER 1: FE Step8.tsx isSafeRedirectUri()                                   │
│                                                                             │
│ 입력: ConversionContext.redirectUri                                         │
│       = "https://www.bizinfo.go.kr/callback"                                │
│                                                                             │
│ 현재 로직:                                                                  │
│   url.hostname.endsWith('.smes.go.kr')                                      │
│   → "www.bizinfo.go.kr".endsWith('.smes.go.kr') = false                    │
│   → ❌ 차단됨 (버그 B-1)                                                    │
│                                                                             │
│ 수정 후 로직 (Option B):                                                    │
│   REACT_APP_REDIRECT_ALLOWED_ORIGINS 기반 origin 매칭                       │
│   → "https://www.bizinfo.go.kr" in allowedOrigins = true                   │
│   → ✅ 통과                                                                 │
└─────────────────────────────────────────────────────────────────────────────┘
                           ↓ (수정 후 통과)
┌─────────────────────────────────────────────────────────────────────────────┐
│ LAYER 2: BE FeSessionServiceImpl.isValidReturnUrl()                         │
│          ← 브로커 인증 완료 후 returnUrl 검증 시 사용                       │
│                                                                             │
│ 입력: returnUrl = "https://www.bizinfo.go.kr/callback"                      │
│                                                                             │
│ 현재 설정 (미설정 — 버그 B-2):                                              │
│   allowed-return-urls:                                                      │
│     - https://agency-a.example.com   ← 더미값                              │
│     - https://agency-b.example.com   ← 더미값                              │
│   → ❌ "https://www.bizinfo.go.kr" 없음 → 검증 실패                         │
│                                                                             │
│ 수정 후 설정:                                                               │
│   allowed-return-urls:                                                      │
│     - https://www.bizinfo.go.kr      ← 실제 기관 URL                       │
│     - https://www.sbiz.or.kr         ← 실제 기관 URL                       │
│     - ... (68개 기관 전체)                                                  │
│   → ✅ "https://www.bizinfo.go.kr".startsWith(...) = true                  │
└─────────────────────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ LAYER 3: BE CallbackUrlValidator (Handoff Ticket 발급 시)                   │
│          ← 기관이 POST /api/v1/handoff/issue 호출 시 사용                  │
│                                                                             │
│ 입력: callbackUrl = "https://www.bizinfo.go.kr/callback"                    │
│ 출처: agency_meta.callback_whitelist (DB, 기관별 설정)                      │
│                                                                             │
│ BIZINFO_001 설정 예시:                                                      │
│   callback_whitelist = [                                                    │
│     "https://www.bizinfo.go.kr/callback",                                   │
│     "https://www.bizinfo.go.kr/mypage/onepass-linked"                       │
│   ]                                                                         │
│                                                                             │
│ 매칭 로직:                                                                  │
│   1. 완전일치 → ✅                                                          │
│   2. 와일드카드(*.bizinfo.go.kr) → ✅                                      │
│   3. 접두사 + 경로구분자 확인 → ✅                                         │
│                                                                             │
│ → ✅ 통과 (구조 정상, DB 등록만 하면 됨)                                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 다이어그램 5: signed_request 방식 도입 후 개선 흐름

```mermaid
sequenceDiagram
    autonumber
    actor User as 사용자 (Browser)
    participant AgencyServer as 기관 서버
    participant OnePassFE as onepass-fe
    participant IdO as IdO BE (:8083)

    Note over AgencyServer: [권장 방식] JWT Signed Request

    AgencyServer->>AgencyServer: ① JWT 페이로드 구성<br/>{ sub: 'BIZINFO_001', mbrId: '...', redirectUri: '...', exp: now+300 }
    AgencyServer->>AgencyServer: ② HMAC-SHA256 서명<br/>(OnePass 발급 API Key 사용)
    AgencyServer->>User: ③ 302 redirect<br/>GET /conversion/step1<br/>?signed_request={JWT}<br/>&agency_code=BIZINFO_001
    Note right of AgencyServer: mbrId, redirectUri가 JWT 내부에 은닉됨<br/>서명으로 변조 감지 가능

    User->>OnePassFE: ④ GET /conversion/step1?signed_request=...&agency_code=...

    OnePassFE->>IdO: ⑤ POST /api/v1/conversion/init<br/>{ signedRequest: JWT, agencyCode: 'BIZINFO_001' }

    IdO->>IdO: ⑥ agency_meta에서 API Key Hash 조회
    IdO->>IdO: ⑦ JWT 서명 검증 (HMAC-SHA256)
    IdO->>IdO: ⑧ exp 유효성 검증 (5분 이내)
    IdO->>IdO: ⑨ redirectUri → callback_whitelist 검증
    IdO->>IdO: ⑩ ConversionSession 생성 → Redis 저장 (TTL 30분)
    IdO-->>OnePassFE: ⑪ { conversionSessionId: 'cs-uuid', userType: 'IND', expiresAt: ... }

    OnePassFE->>OnePassFE: ⑫ conversionSessionId → ConversionContext 저장
    Note right of OnePassFE: mbrId, redirectUri는 FE에 저장 안 함<br/>→ 서버 세션에서 관리

    OnePassFE->>User: ⑬ 회원유형 선택 UI (step2로 진행)

    Note over User,IdO: 이후 step2~step8은 conversionSessionId를<br/>API 요청마다 포함 → BE가 redirectUri 서버 측 조회
```

---

## 다이어그램 6: Handoff Ticket 발급·검증 상세 (㊴ ~ ㊶ 확대)

```
[기관 FE 브라우저]                [기관 서버]                    [IdO BE]
        │                              │                              │
        │  ㊳ redirect_uri 도달         │                              │
        │  GET /cb?ticketId=HT-xxxx    │                              │
        │─────────────────────────────▶│                              │
        │                              │                              │
        │                              │  ㊴ POST /api/v1/handoff/verify
        │                              │  Header: X-Agency-Code: BIZINFO_001
        │                              │          X-Api-Key: {기관 API Key}
        │                              │  Body: { ticketId: "HT-xxxx" }
        │                              │─────────────────────────────▶│
        │                              │                              │
        │                              │                        ┌─────┴──────────────────────┐
        │                              │                        │ ⑮ Ticket 조회 (Redis)       │
        │                              │                        │ ⑯ 기관 코드 일치 검증       │
        │                              │                        │ ⑰ Ticket 상태 = ISSUED?     │
        │                              │                        │ ⑱ Ticket 소비 (1회성)       │
        │                              │                        │    → 상태: CONSUMED         │
        │                              │                        └─────┬──────────────────────┘
        │                              │                              │
        │                              │  ㊵ 200 OK                  │
        │                              │  {                           │
        │                              │    qimUserId: "qim-xxx",     │
        │                              │    authLevel: "L2",          │
        │                              │    authResultId: "ar-xxx",   │
        │                              │    mbrUuid: "...",           │
        │                              │    agencyCode: "BIZINFO_001" │
        │                              │  }                           │
        │                              │◀─────────────────────────────│
        │                              │                              │
        │                        ┌─────┴───────────────────────────┐  │
        │                        │ ㊶ 기관 세션 생성               │  │
        │                        │    agencyUserId = qimUserId      │  │
        │                        │    authLevel 기록               │  │
        │                        │    기관 서비스 접근 허용         │  │
        │                        └─────┬───────────────────────────┘  │
        │                              │                              │
        │  ㊷ 기관 서비스 정상 응답     │                              │
        │◀─────────────────────────────│                              │
```

---

## 요약 — 순번별 핵심 동작 표

| 순번 | 주체 | 동작 | 상태/데이터 변화 |
|---|---|---|---|
| ① | 기관 서버 | 전환 URL 구성 → 302 redirect | - |
| ② | 브라우저 | `/conversion/step1` GET 요청 | - |
| ③ | FE Step1 | URL 파라미터 파싱 + 검증 | ConversionContext: `redirectUri`, `mbrId`, `initialClientId` |
| ④ | FE Step1 | 회원유형 선택 UI | `memberType` 설정 |
| ⑤ | 사용자 | 회원유형 선택 + 다음 | - |
| ⑥ | 브라우저 | `/conversion-member/step2` GET | - |
| ⑦⑧ | FE→IdO | 약관 동의 토큰 발급 | `consentEventId` |
| ⑨⑩⑪ | FE→IdO | 약관 동의 제출 | - |
| ⑫ | 브라우저 | `/conversion-member/step3` GET | - |
| ⑬~⑯ | FE→IdO→NICE | NICE 인증 URL 발급 | - |
| ⑰⑱ | 사용자→NICE | 실명 + 휴대폰 인증 | - |
| ⑲~㉑ | NICE→FE→IdO | CI 취득 → ciToken 발급 | `ciToken`, `mbrUuid` |
| ㉒㉓㉔ | 브라우저→FE | Step4 기관 연결 안내 (읽기전용) | `selectedClients=[]` |
| ㉕㉖ | 브라우저→사용자 | Step5 계정 정보 입력 | - |
| ㉗~㉙ | FE→QIM | `provisionUser()` API 호출 | `mbrNo`, `provisioningToken` |
| ㉚~㉜ | 브라우저→FE→QIM | Step6 `checkConversionProxy()` | 연결 기관 확인 |
| ㉝㉞ | FE→사용자 | ServiceListModal + 완료 | - |
| ㉟ | 브라우저 | `/conversion-member/step8` GET | - |
| ㊱ | FE Step8 | `isSafeRedirectUri()` 검증 | ⚠️ **현재 버그**: `*.smes.go.kr`만 통과 |
| ㊲ | 브라우저 | `redirect_uri`로 302 이동 | ⚠️ **버그 시**: `/login` 폴백 |
| ㊳ | 브라우저 | 기관 `redirect_uri` GET (`?ticketId=...`) | - |
| ㊴ | 기관 서버 | POST `/api/v1/handoff/verify` | Ticket 1회 소비 |
| ㊵ | IdO | Handoff Payload 반환 | `qimUserId`, `authLevel`, ... |
| ㊶ | 기관 서버 | 기관 세션 생성 | 서비스 이용 시작 |

---

> **이전 문서**: [GUIDE-003: 기관 오픈 URL 샘플](./03-conversion-launch-sample.md)  
> **전체 인덱스**: [Wiki INDEX](../INDEX.md)
