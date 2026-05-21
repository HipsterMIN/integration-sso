# 간편인증 (EasySign / 민간인증서)

> **문서 분류**: IAM / 인증수단 상세  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 프론트엔드 개발자  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [개요](#1-개요)
2. [서비스 도메인 및 URL 구조](#2-서비스-도메인-및-url-구조)
3. [지원 민간인증서 11종](#3-지원-민간인증서-11종)
4. [인증 아키텍처: CI 브로커링 구조](#4-인증-아키텍처-ci-브로커링-구조)
5. [인증 흐름 (시퀀스 다이어그램)](#5-인증-흐름-시퀀스-다이어그램)
6. [동의 항목 및 제공 데이터](#6-동의-항목-및-제공-데이터)
7. [UI 입력 필드 설계](#7-ui-입력-필드-설계)
8. [개발 연동 방법](#8-개발-연동-방법)
9. [인증서별 특이사항](#9-인증서별-특이사항)
10. [오류 코드 및 예외 처리](#10-오류-코드-및-예외-처리)

---

## 1. 개요

**간편인증(EasySign)**은 방통위 허가를 받은 **본인확인기관의 민간 PKI/FIDO2 인증서**를 Any-ID가 브로커링하여 제공하는 **2등급** 인증수단이다.

```
법적 근거:
  전기통신사업법 제23조의3 (본인확인서비스 허가)
  방송통신위원회 고시 2021-59호 (본인확인기관 지정)
  → 카카오, 네이버, KB, NH, 토스, PASS 등 11개사 허가
```

### 1.1 핵심 특징

| 특징 | 내용 |
|------|------|
| **인증 등급** | **2등급** (본인확인기관 검증) |
| **CI 출처** | 본인확인기관 → 행안부 CI 변환 |
| **지원 종류** | 방통위 허가 민간인증서 **11종+** |
| **인증 방식** | 앱 PUSH / SMS OTP / 생체인증 (앱별 상이) |
| **사용자 경험** | 별도 USB 키 없음, 스마트폰 앱으로 완결 |
| **브로커 도메인** | `easysign.anyid.go.kr` |

### 1.2 모바일 신분증 vs 간편인증

| 비교 항목 | 모바일 신분증 (1등급) | 간편인증 (2등급) |
|-----------|-------------------|---------------|
| CI 신뢰도 | 행안부 주민등록 원장 직접 연결 | 본인확인기관 중개 (1단계 더 경유) |
| 신분증 확인 | ✅ 실물 신분증 연동 | ❌ (본인확인만) |
| 분실 신고 반영 | ✅ 실시간 | ❌ |
| 사용 편의성 | 전화번호 입력 / QR 스캔 | **앱 선택 후 인증** (더 간편) |
| 보급률 | 신분증 앱 설치 필요 | 카카오톡 등 **기존 앱** 활용 가능 |

---

## 2. 서비스 도메인 및 URL 구조

### 2.1 도메인

| 환경 | URL |
|------|-----|
| **운영** | `https://easysign.anyid.go.kr` |
| **데모/테스트** | `https://demo1.anyid.go.kr/easysign/` |

### 2.2 실제 인증 진입 URL

```
https://easysign.anyid.go.kr/esign/
  ?instt=5000000082          ← 기관코드
  &srvcNo=5000000084         ← 서비스번호
  &authType=EASY_SIGN        ← 인증수단 구분
```

### 2.3 인증서 선택 파라미터

```
easysign.anyid.go.kr/esign/
  ?provider=KAKAO            ← 특정 인증서 직접 지정 (선택)
  &instt=5000000082
```

| `provider` 값 | 인증서 |
|-------------|--------|
| `KAKAO` | 카카오 인증서 |
| `NAVER` | 네이버 인증서 |
| `KB` | KB국민 인증서 |
| `NH` | 농협 인증서 |
| `TOSS` | 토스 인증서 |
| `PASS` | PASS 인증서 |
| (생략) | 전체 목록 표시 |

---

## 3. 지원 민간인증서 11종

방통위 지정 본인확인기관이 발행하는 민간인증서 목록 (2025년 기준):

| # | 인증서명 | 발행사 | 기반 기술 | 비고 |
|---|---------|--------|---------|------|
| 1 | **카카오 인증서** | 카카오 | PKI + FIDO2 | 카카오톡 앱 내장 |
| 2 | **네이버 인증서** | 네이버 | PKI + FIDO2 | 네이버 앱 내장 |
| 3 | **KB국민 인증서** | KB국민은행 | PKI | KB스타뱅킹 앱 |
| 4 | **농협 인증서** | NH농협은행 | PKI | 농협 앱 |
| 5 | **토스 인증서** | 비바리퍼블리카 | FIDO2 | 토스 앱 |
| 6 | **PASS 인증서** | SKT/KT/LGU+ (3사 공동) | PKI + FIDO2 | PASS 앱 |
| 7 | **신한 인증서** | 신한은행 | PKI | 신한 쏠(SOL) 앱 |
| 8 | **하나 인증서** | 하나은행 | PKI | 하나1Q 앱 |
| 9 | **우리 인증서** | 우리은행 | PKI | 우리WON뱅킹 앱 |
| 10 | **페이코 인증서** | NHN | PKI + OTP | 페이코 앱 |
| 11 | **삼성패스** | 삼성전자 | FIDO2 + 삼성Knox | 삼성 스마트폰 전용 |

> **⚠️ 목록 변동**: 본인확인기관 신규 허가/취소에 따라 변동 가능.  
> 최신 목록: 방통위 공고 또는 https://www.anyid.go.kr 확인

### 3.1 인증서별 기술 분류

| 기술 기반 | 해당 인증서 | 특징 |
|---------|-----------|------|
| **PKI (전자서명)** | 카카오, 네이버, KB, NH, 신한, 하나, 우리 | 앱에 저장된 인증서로 전자서명 수행 |
| **FIDO2 (생체인증)** | 토스, 삼성패스 | 디바이스 생체정보로 인증 (서버에 생체정보 없음) |
| **PKI + FIDO2** | 카카오, 네이버, PASS | PKI 기반 + 생체인증 활성화 |
| **OTP 보완** | 페이코 | PKI + SMS OTP 2단계 |

---

## 4. 인증 아키텍처: CI 브로커링 구조

간편인증은 **2단계 브로커링**을 거쳐 CI를 획득한다:

```
┌─────────────────────────────────────────────────────────────────┐
│                     이용기관                                     │
│    브라우저 ──── 팝업/리다이렉트 ────▶ easysign.anyid.go.kr      │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│              easysign.anyid.go.kr (EasySign 브로커)               │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │               인증서 선택 UI                                │  │
│  │    [카카오] [네이버] [KB] [토스] [PASS] [NH] ...           │  │
│  └──────────────────────────┬─────────────────────────────────┘  │
│                             │ 선택                                │
│                             ▼                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │            민간인증서 SDK 직접 호출                          │  │
│  │     카카오 API / 네이버 API / FIDO2 검증 서버 ...           │  │
│  └──────────────────────────┬─────────────────────────────────┘  │
│                             │ 인증 성공 + 사용자 식별자            │
│                             ▼                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │        본인확인기관 CI 요청                                  │  │
│  │    카카오/네이버/통신사 → 행안부 CI 변환 API 호출            │  │
│  └──────────────────────────┬─────────────────────────────────┘  │
└────────────────────────────┼─────────────────────────────────────┘
                             │ CI(88바이트) + auth_method
                             ▼
              ptl.anyid.go.kr (OIDC IdP)
                             │
                             ▼ ID Token (CI 포함)
                       이용기관 서버
```

### 4.1 CI 브로커링 경로 비교

| 인증서 | CI 획득 경로 |
|--------|-----------|
| 카카오 인증서 | 카카오 → 나이스평가정보(본인확인기관) → 행안부 CI API |
| 네이버 인증서 | 네이버 → SCI평가정보(본인확인기관) → 행안부 CI API |
| PASS 인증서 | SKT/KT/LGU+ → 한국정보인증(본인확인기관) → 행안부 CI API |
| KB/NH/토스/신한 등 | 금융결제원(KFTC) 연계 → 행안부 CI API |

> **핵심**: 어떤 인증서를 선택하든 Any-ID가 CI를 정규화하여 동일한 클레임으로 전달.  
> 이용기관은 **인증서 종류에 무관하게 동일한 CI 값**을 받는다.

---

## 5. 인증 흐름 (시퀀스 다이어그램)

### 5.1 카카오 인증서 기준 전체 흐름

```
사용자         이용기관 브라우저    easysign.anyid.go.kr    카카오 서버     행안부CI서버
  │                  │                    │                   │              │
  │── 간편인증 클릭 ──▶│                    │                   │              │
  │                  │── 팝업 오픈 ────────▶│                   │              │
  │                  │                    │ (인증서 목록 표시)  │              │
  │── 카카오 선택 ─────────────────────────▶│                   │              │
  │                  │                    │── 카카오 인증 시작 ─▶│              │
  │◀─── 카카오앱 PUSH ──────────────────────────────────────────│              │
  │── 생체인증 확인 ───────────────────────────────────────────▶│              │
  │                  │                    │◀── 인증 성공 ────────│              │
  │                  │                    │── CI 요청 ───────────────────────▶│
  │                  │                    │◀── CI(88바이트) ──────────────────│
  │                  │                    │ (ptl.anyid.go.kr로 전달)          │
  │                  │◀── Authorization Code ─│                   │              │
  │                  │── /token 요청 ──────────────────────────────             │
  │                  │◀── ID Token (ci 포함) ──                                 │
  │◀── 로그인 완료 ────│                    │                   │              │
```

### 5.2 SMS OTP 방식 (PASS 인증서)

```
사용자         이용기관 브라우저    easysign.anyid.go.kr    PASS 서버    통신사 SMS
  │                  │                    │                   │           │
  │── PASS 선택 ──────────────────────────▶│                   │           │
  │                  │                    │── 전화번호 입력 UI  │           │
  │── 전화번호 입력 ───────────────────────▶│                   │           │
  │                  │                    │── 인증 요청 ────────▶│           │
  │                  │                    │                   │── SMS 발송 ▶│
  │◀─── SMS OTP 수신 ──────────────────────────────────────────────────────│
  │── OTP 입력 ────────────────────────────▶│                   │           │
  │                  │                    │── OTP 검증 ─────────▶│           │
  │                  │                    │◀── 인증 성공 ─────────│           │
  │                  │◀── 인증 완료 ────────│                   │           │
```

---

## 6. 동의 항목 및 제공 데이터

### 6.1 사용자 동의 화면 구성

easysign.anyid.go.kr은 인증 전 아래 항목을 표시하고 동의를 받는다:

```
┌──────────────────────────────────────────┐
│          본인확인 동의                    │
│                                          │
│  서비스명: Q-Net (한국산업인력공단)        │
│                                          │
│  수집·이용 항목 (필수)                    │
│  ☑ 연계정보(CI) — 본인 식별 목적          │
│  ☑ 이름, 생년월일 — 회원 정보 확인        │
│  ☑ 휴대전화번호 — 알림 서비스             │
│                                          │
│  수집·이용 항목 (선택)                    │
│  ☐ 주소 — 배송 서비스 (선택 시 제공)      │
│                                          │
│  [ 전체 동의 ]  [ 필수만 동의 ]           │
└──────────────────────────────────────────┘
```

### 6.2 동의 항목 상세

| 항목 | 필수/선택 | 클레임 | 설명 |
|------|---------|--------|------|
| 연계정보(CI) | **필수** | `ci` | 88바이트, 회원 식별 핵심 |
| 이름 | **필수** | `name` | 한글 이름 |
| 생년월일 | **필수** | `birthdate` | YYYYMMDD |
| 성별 | 필수 | `gender` | 1=남, 2=여 |
| 휴대전화번호 | 필수 (서비스별) | `phone_number` | 010XXXXXXXX |
| 주소 | 선택 | `address` | 도로명 주소 |
| 이메일 | 선택 | `email` | 인증서 등록 이메일 |

### 6.3 ID Token 클레임 (간편인증 기준)

```jsonc
{
  "iss":          "https://ptl.anyid.go.kr",
  "sub":          "anyid-uuid-xxxx",
  "aud":          "YOUR_CLIENT_ID",
  "iat":          1716123456,
  "exp":          1716127056,
  "nonce":        "제출한_nonce값",

  // Any-ID 확장 클레임
  "ci":           "ABCdef123...총88바이트",  // ★ 연계정보
  "name":         "홍길동",
  "birthdate":    "19900115",               // YYYYMMDD
  "gender":       "1",                      // 1=남, 2=여
  "phone_number": "01012345678",
  "email":        "user@kakao.com",         // 선택 동의 시

  // 간편인증 전용 메타
  "auth_method":  "EASY_SIGN",             // 인증수단
  "auth_level":   2,                        // 2등급
  "easy_sign_provider": "KAKAO",            // 사용된 민간인증서
  "auth_time":    1716123456,
  "instt_cd":     "5000000082"
}
```

---

## 7. UI 입력 필드 설계

### 7.1 인증 진입 방식 비교

| 방식 | 설명 | 권장 환경 |
|------|------|---------|
| **인증서 목록 표시** | easysign UI에서 인증서 선택 | 일반 (기본값) |
| **특정 인증서 직접 진입** | `provider=KAKAO` 파라미터로 카카오 직접 | 특정 인증서 유도 시 |
| **팝업 방식** | `window.open()` 팝업 오픈 | PC 웹 |
| **리다이렉트 방식** | 페이지 전환 | 모바일 웹 |

### 7.2 권장 팝업 크기

| 화면 | 권장 크기 |
|------|---------|
| 인증서 선택 목록 | `width=640, height=720` |
| 카카오/네이버 (PUSH 전용) | `width=480, height=600` |
| SMS OTP 입력 | `width=480, height=500` |

### 7.3 JavaScript 연동 예시

```javascript
// 간편인증 팝업 오픈
function openEasySign(provider = null) {
    const baseUrl = 'https://easysign.anyid.go.kr/esign/';
    const params = new URLSearchParams({
        instt: '5000000082',
        srvcNo: '5000000084',
        state: generateRandomState(),
        nonce: generateRandomNonce(),
        callback: `${window.location.origin}/callback/easysign`
    });
    if (provider) {
        params.set('provider', provider); // 특정 인증서 직접 지정
    }

    const popup = window.open(
        `${baseUrl}?${params}`,
        'easySignAuth',
        'width=640,height=720,scrollbars=yes,resizable=no'
    );

    // 팝업 메시지 수신
    window.addEventListener('message', (event) => {
        if (event.origin !== 'https://easysign.anyid.go.kr') return;
        if (event.data.type === 'EASY_SIGN_COMPLETE') {
            // code, state 수신 후 서버로 전달
            handleAuthCallback(event.data.code, event.data.state);
            popup.close();
        }
    });
}
```

---

## 8. 개발 연동 방법

### 8.1 Authorization 요청 (간편인증 지정)

```
GET https://ptl.anyid.go.kr/oidc/authorize
  ?response_type=code
  &client_id=YOUR_CLIENT_ID
  &redirect_uri=https%3A%2F%2Fyour-service.go.kr%2Fcallback
  &scope=openid+profile+phone+ci
  &state=RANDOM_STATE_VALUE
  &nonce=RANDOM_NONCE_VALUE
  &auth_method=EASY_SIGN                  ← 간편인증 직접 지정
  &easy_sign_provider=KAKAO               ← 특정 인증서 (선택)
  &instt=5000000082
  &srvcNo=5000000084
  &code_challenge=BASE64URL_SHA256
  &code_challenge_method=S256
```

### 8.2 Spring Boot 연동 코드

```java
// EasySignAuthController.java
@GetMapping("/auth/easy-sign/initiate")
public ResponseEntity<Void> initiateEasySign(
    @RequestParam(required = false) String provider,  // KAKAO, NAVER 등 (선택)
    HttpSession session
) {
    String state = generateSecureRandom(32);
    String nonce = generateSecureRandom(32);
    String codeVerifier = generateCodeVerifier();
    String codeChallenge = sha256Base64Url(codeVerifier);

    session.setAttribute("oidc_state", state);
    session.setAttribute("oidc_nonce", nonce);
    session.setAttribute("pkce_verifier", codeVerifier);

    UriComponentsBuilder builder = UriComponentsBuilder
        .fromHttpUrl(issuerUri + "/oidc/authorize")
        .queryParam("response_type", "code")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", redirectUri)
        .queryParam("scope", "openid profile phone ci")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("auth_method", "EASY_SIGN")
        .queryParam("instt", instt)
        .queryParam("srvcNo", srvcNo)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256");

    // 특정 인증서 직접 지정 (선택)
    if (StringUtils.hasText(provider)) {
        builder.queryParam("easy_sign_provider", provider);
    }

    return ResponseEntity.status(302)
        .header("Location", builder.build().toUriString())
        .build();
}

// Callback 처리
@GetMapping("/callback/anyid")
public ResponseEntity<?> handleCallback(
    @RequestParam String code,
    @RequestParam String state,
    HttpSession session
) {
    // state 검증
    validateState(state, (String) session.getAttribute("oidc_state"));

    // Token 교환
    AnyIdTokenResponse tokens = anyIdClient.exchangeToken(
        code, (String) session.getAttribute("pkce_verifier")
    );

    // ID Token 파싱
    AnyIdClaims claims = jwtParser.parseAndVerify(
        tokens.getIdToken(),
        (String) session.getAttribute("oidc_nonce")
    );

    // easy_sign_provider 확인
    String provider = claims.getEasySignProvider(); // "KAKAO", "NAVER" 등
    int authLevel   = claims.getAuthLevel();         // 2
    String ci       = claims.getCi();

    Member member = memberService.findOrCreateByCi(ci, claims);
    sessionService.createSession(session, member);

    return ResponseEntity.ok(LoginResponse.of(member));
}
```

### 8.3 인증서 선택 UI 렌더링 예시 (React)

```tsx
// EasySignButtons.tsx
const PROVIDERS = [
    { code: 'KAKAO',  label: '카카오 인증서',   logo: '/icons/kakao.svg'  },
    { code: 'NAVER',  label: '네이버 인증서',   logo: '/icons/naver.svg'  },
    { code: 'TOSS',   label: '토스 인증서',     logo: '/icons/toss.svg'   },
    { code: 'PASS',   label: 'PASS 인증서',     logo: '/icons/pass.svg'   },
    { code: 'KB',     label: 'KB국민 인증서',   logo: '/icons/kb.svg'     },
    { code: 'NH',     label: '농협 인증서',     logo: '/icons/nh.svg'     },
];

function EasySignButtons() {
    const handleSelect = (provider: string) => {
        openEasySign(provider); // 위의 JavaScript 함수 호출
    };

    return (
        <div className="easy-sign-grid">
            {PROVIDERS.map(p => (
                <button key={p.code} onClick={() => handleSelect(p.code)}>
                    <img src={p.logo} alt={p.label} />
                    <span>{p.label}</span>
                </button>
            ))}
            <button onClick={() => handleSelect(null)}>
                전체 인증서 목록 보기
            </button>
        </div>
    );
}
```

---

## 9. 인증서별 특이사항

### 9.1 카카오 인증서

```
✅ 카카오톡 앱에 내장 — 별도 앱 설치 불필요
✅ 가장 높은 보급률 (국민 80%+ 사용)
⚠️ 카카오 서버 장애 시 인증 불가 — 폴백 안내 필요
⚠️ 카카오톡 미사용 기기(PC 앱, 노령층) 대응 고려
인증 방식: 카카오톡 알림 → 6자리 PIN 또는 지문
```

### 9.2 네이버 인증서

```
✅ 네이버 앱에 내장 — 별도 앱 설치 불필요
✅ 높은 보급률
⚠️ 네이버 앱 최신 버전 필요 (구버전 미지원)
인증 방식: 네이버 앱 PUSH → 패턴/생체인증
```

### 9.3 PASS 인증서 (SKT/KT/LGU+)

```
✅ 이동통신사 3사 공용
✅ SMS OTP 폴백 지원 (스마트폰 미사용자 대응)
⚠️ PASS 앱 별도 설치 필요
⚠️ 국제 로밍 시 SMS 지연 가능
인증 방식: PASS 앱 PUSH 또는 SMS OTP
```

### 9.4 토스 인증서

```
✅ FIDO2 기반 — 서버에 키 저장 없음 (보안 강점)
✅ 비대면 계좌 개설 연계
⚠️ 토스 앱 + 계정 생성 필요
인증 방식: 생체인증 (지문/Face ID) 전용
```

### 9.5 KB국민 인증서 / NH농협 인증서

```
✅ 금융 앱에 통합 — 금융인증서와 함께 사용 가능
✅ 자동로그인 기능 지원 (KB, NH 앱 설정)
⚠️ 해당 은행 계좌 보유 고객 대상
인증 방식: 앱 PUSH → 지문/Face ID
```

### 9.6 삼성패스

```
✅ 삼성 스마트폰 기본 탑재
✅ 삼성Knox 하드웨어 보안 모듈 기반
❌ 삼성 기기 전용 (애플, 기타 안드로이드 미지원)
인증 방식: 지문 또는 홍채 (기기별 상이)
```

---

## 10. 오류 코드 및 예외 처리

### 10.1 간편인증 주요 오류

| 오류 코드 | 의미 | 처리 방법 |
|---------|------|---------|
| `ESIGN_001` | 앱 미설치 또는 미가입 | "해당 앱을 설치하거나 다른 인증서를 이용해주세요" |
| `ESIGN_002` | 인증 시간 초과 (3분) | "인증 시간이 초과되었습니다. 다시 시도해주세요" |
| `ESIGN_003` | 사용자 취소 | 로그인 화면으로 복귀 |
| `ESIGN_004` | 동의 거부 | "서비스 이용을 위해 필수 동의가 필요합니다" |
| `ESIGN_005` | CI 변환 실패 (본인확인기관 오류) | 다른 인증서 시도 안내 |
| `ESIGN_010` | EasySign 서버 오류 | 잠시 후 재시도 안내, 기술지원 1566-2670 |
| `ESIGN_011` | 특정 인증서 서버 장애 | "다른 인증서를 이용해주세요" 팝업 표시 |

### 10.2 폴백 전략

```
권장 폴백 흐름:
  1차: 사용자 선호 인증서 (카카오/네이버)
  2차: 오류 발생 시 → "다른 인증서 선택" 유도
  3차: 모든 간편인증 실패 → 공동인증서 또는 금융인증서로 안내
  4차: 극단적 장애 → 기술지원 연락처(1566-2670) 표시
```

### 10.3 기관코드 미등록 오류

```
증상: instt 파라미터 전달 시 "등록되지 않은 기관" 오류
원인: 행안부에 기관코드 미등록 또는 잘못된 기관코드
해결: ptl.anyid.go.kr 이용기관 관리 콘솔에서 기관코드 확인
     → 기술지원센터(1566-2670) 문의
```

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| 모바일 신분증 (1등급) | [01-mobile-id.md](./01-mobile-id.md) |
| 공동인증서 | [03-joint-cert.md](./03-joint-cert.md) |
| CI/DN 브로커링 | [05-ci-dn-brokering.md](./05-ci-dn-brokering.md) |
| 설치형 연동 가이드 | [06-install-type-integration.md](./06-install-type-integration.md) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
