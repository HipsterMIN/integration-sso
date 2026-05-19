# 금융인증서 (Financial Certificate / KFTC)

> **문서 분류**: IAM / 인증수단 상세  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 보안 담당자  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [개요](#1-개요)
2. [서비스 도메인 및 URL 구조](#2-서비스-도메인-및-url-구조)
3. [KFTC 클라우드 저장소 아키텍처](#3-kftc-클라우드-저장소-아키텍처)
4. [공동인증서 vs 금융인증서 비교](#4-공동인증서-vs-금융인증서-비교)
5. [인증 흐름 (시퀀스 다이어그램)](#5-인증-흐름-시퀀스-다이어그램)
6. [자동로그인 기능](#6-자동로그인-기능)
7. [제공 데이터 (클레임)](#7-제공-데이터-클레임)
8. [개발 연동 방법](#8-개발-연동-방법)
9. [발급 대상 및 은행별 지원 현황](#9-발급-대상-및-은행별-지원-현황)
10. [오류 코드 및 예외 처리](#10-오류-코드-및-예외-처리)

---

## 1. 개요

**금융인증서(Financial Certificate)**는 금융결제원(KFTC)이 발행하는 **클라우드 기반 PKI 인증서**다.  
기존 공동인증서가 로컬 PC에 저장되는 것과 달리, **KFTC 클라우드 서버에 인증서를 저장**하여  
기기에 관계없이 어디서든 사용할 수 있다.

```
배경:
  2020.12: 전자서명법 개정 → 금융결제원이 신규 금융인증서 서비스 시작
  기존 공동인증서(HDD 저장)의 불편함 해소
  → 스마트폰·태블릿·PC 등 멀티 디바이스 지원
  2024.06: Any-ID fincert v2.2 연동 표준화
```

### 1.1 핵심 특징

| 특징 | 내용 |
|------|------|
| **인증 등급** | **2등급** |
| **기술 기반** | X.509 PKI + 클라우드 저장소 |
| **저장 위치** | KFTC 클라우드 서버 (로컬 저장 없음) |
| **발급 기관** | 금융결제원(KFTC) — 단일 CA |
| **발급 채널** | 은행 앱 (20개사) 또는 금융결제원 홈페이지 |
| **솔루션 경로** | `crt.anyid.go.kr/fincert/v2.2/` |
| **자동로그인** | ✅ 지원 (동일 기기에서 30일) |

### 1.2 금융인증서의 시장 지위

```
금융인증서 발급 현황 (2025년 기준):
  - 누적 발급: 3,500만 건↑
  - 월 사용: 1억 5천만 건↑
  - 지원 은행: 20개 은행권 공동

  기존 공동인증서 대비:
  - USB 토큰 불필요
  - Active X / NPAPI 플러그인 불필요
  - 스마트폰 완벽 지원
```

---

## 2. 서비스 도메인 및 URL 구조

### 2.1 도메인

| 환경 | URL |
|------|-----|
| **운영** | `https://crt.anyid.go.kr` |
| **데모/테스트** | `https://demo1.anyid.go.kr/crt/` |

### 2.2 실제 인증 URL

```
https://crt.anyid.go.kr/fincert/v2.2/
  ?instt=5000000082          ← 기관코드
  &certType=FINANCIAL        ← 금융인증서 구분
  &autoLogin=Y               ← 자동로그인 활성화 (선택)
```

> **참고**: 공동인증서는 `/MagicLine4Web/v2.2/`, 금융인증서는 `/fincert/v2.2/` 경로 사용.  
> 두 인증서 모두 `crt.anyid.go.kr`을 통해 제공된다.

### 2.3 fincert v2.2 버전 특이사항

| 항목 | v1.x | v2.2 (현재) |
|------|------|------------|
| UI 프레임워크 | JSP + jQuery | **React SPA** |
| 클라우드 API | REST v1 | **REST v2 (OpenAPI 3.0)** |
| 자동로그인 | 7일 | **30일** |
| 생체인증 연동 | ❌ | ✅ (iOS/Android FIDO2) |
| 다중 기기 관리 | 수동 | **자동 동기화** |

---

## 3. KFTC 클라우드 저장소 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                 이용기관 (설치형)                                 │
│                                                                  │
│  브라우저 ─── 팝업/iframe ───▶ crt.anyid.go.kr/fincert/v2.2     │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│              crt.anyid.go.kr (fincert UI + 브로커)                │
│                                                                   │
│  ┌───────────────────────────────────────────────────────────┐   │
│  │  사용자 인증 UI (React SPA)                                │   │
│  │  1. 생년월일 입력 (6자리)                                  │   │
│  │  2. 아이디 선택 (저장된 경우) 또는 전화번호 입력             │   │
│  │  3. 비밀번호(PIN) 6자리 입력                               │   │
│  └───────────────────────────┬───────────────────────────────┘   │
│                              │ API 호출                           │
│  ┌───────────────────────────▼───────────────────────────────┐   │
│  │  KFTC 클라우드 인증 API                                    │   │
│  │  POST /fincert/api/v2/authenticate                         │   │
│  │  Body: { birthDate, userId, pin, challenge }               │   │
│  └───────────────────────────┬───────────────────────────────┘   │
└──────────────────────────────┼───────────────────────────────────┘
                              │ HTTPS (인증서 저장소 접근)
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│              KFTC 클라우드 저장소 (금융결제원 IDC)                  │
│                                                                   │
│  ┌─────────────────┐   ┌──────────────────┐   ┌──────────────┐  │
│  │  인증서 저장소   │   │  HSM (서명 처리)  │   │  CRL/OCSP   │  │
│  │  (암호화 보관)  │   │  (개인키 클라우드  │   │  폐기 목록  │  │
│  │                 │   │   에 안전히 저장)  │   │  실시간 확인 │  │
│  └─────────────────┘   └──────────────────┘   └──────────────┘  │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  은행 20개사 공동 인프라 (자금 이동 없는 인증만 처리)         │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼ CI + 서명 결과
                    ptl.anyid.go.kr (OIDC IdP)
```

### 3.1 클라우드 저장의 보안 구조

```
개인키 보호 방식:
  ① 인증서 등록 시 — 사용자 PIN(비밀번호)으로 암호화된 개인키를 KFTC HSM에 저장
  ② 인증 시 — PIN 입력 → KFTC HSM에서 개인키 복호화 → 내부 서명 수행
  ③ 결과 반환 — 서명값만 외부로 전달 (개인키는 클라우드 밖으로 절대 노출 안 됨)

PIN 정책:
  - 6자리 숫자
  - 연속 동일 숫자 3개 이상 금지 (111, 222 등)
  - 연속 숫자 금지 (123456, 654321)
  - 생년월일 패턴 금지
  - 5회 오류 시 잠김 → 은행 앱에서 재인증 필요
```

---

## 4. 공동인증서 vs 금융인증서 비교

| 비교 항목 | 공동인증서 (Joint Cert) | 금융인증서 (Financial Cert) |
|---------|----------------------|--------------------------|
| **발급 기관** | 5개 CA (CrossCert, KICA 등) | **단일 — 금융결제원(KFTC)** |
| **저장 위치** | HDD / USB / 스마트카드 / CA 클라우드 | **KFTC 클라우드 전용** |
| **발급 채널** | CA 홈페이지, 은행 창구 | **은행 앱 (모바일 전용)** |
| **발급 수수료** | 유료 (무료~4,400원) | **무료** |
| **인증 솔루션** | MagicLine4Web v2.2 | **fincert v2.2** |
| **스마트폰 지원** | 클라우드 저장 시만 가능 | **완전 지원** |
| **자동로그인** | 기관별 상이 | **30일 지원** |
| **유효기간** | 1년 (갱신 필요) | **3년** |
| **Any-ID URL** | `/MagicLine4Web/v2.2/` | `/fincert/v2.2/` |

---

## 5. 인증 흐름 (시퀀스 다이어그램)

### 5.1 표준 인증 흐름

```
사용자           브라우저(fincert UI)      crt.anyid.go.kr    KFTC클라우드    ptl.anyid.go.kr
  │                    │                       │                   │               │
  │── 금융인증서 선택 ──▶│                      │                   │               │
  │                    │── 인증 페이지 요청 ─────▶│                  │               │
  │◀── 인증 UI (팝업) ──│◀── React SPA ──────────│                  │               │
  │                    │                       │                   │               │
  │── 생년월일 입력 ─────▶│                      │                   │               │
  │── PIN 6자리 입력 ────▶│                     │                   │               │
  │                    │── 인증 요청 ───────────▶│                  │               │
  │                    │                       │── 개인키 서명 요청 ──▶│               │
  │                    │                       │   (challenge)      │               │
  │                    │                       │◀── 서명값 + 인증서 ──│               │
  │                    │                       │── OCSP 검증 ─────────(내부)         │
  │                    │                       │── CI 추출 ────────────(내부)         │
  │                    │                       │── 결과 전달 ────────────────────────▶│
  │                    │◀── Authorization Code ──────────────────────────────────────│
  │                    │── /token ────────────────────────────────────────────────▶│
  │                    │◀── ID Token(CI포함) ─────────────────────────────────────│
  │◀── 로그인 완료 ──────│                      │                   │               │
```

### 5.2 자동로그인 흐름

```
사용자           브라우저             crt.anyid.go.kr       KFTC클라우드
  │                │                      │                    │
  │                │  (이전 세션 쿠키 있음)  │                    │
  │── 금융인증서 선택 ▶│                     │                    │
  │                │── 자동로그인 쿠키 전송 ──▶│                   │
  │                │                      │── 쿠키 검증 ──────────▶│
  │                │                      │◀── 유효 (30일 이내) ───│
  │                │                      │── 서명 요청 ───────────▶│
  │                │                      │◀── 자동 서명 ──────────│
  │                │◀── 인증 성공 (PIN 입력 없음) ─│              │
  │◀── 로그인 완료 ──│                      │                    │
```

---

## 6. 자동로그인 기능

금융인증서는 **동일 기기에서 30일간 자동로그인**을 지원한다.  
사용자가 동의하면 재방문 시 PIN 입력 없이 자동 서명이 수행된다.

### 6.1 자동로그인 활성화 조건

```
조건:
  ✅ 동일 브라우저 + 동일 기기 (쿠키 기반)
  ✅ 사용자가 "자동로그인 사용" 체크박스 선택
  ✅ 30일 이내 마지막 로그인

비활성화 조건:
  ❌ 브라우저 쿠키 삭제
  ❌ 30일 초과
  ❌ KFTC에서 강제 만료 (보안 이벤트 감지 시)
  ❌ PIN 변경
  ❌ 인증서 재발급
```

### 6.2 이용기관 자동로그인 파라미터

```
Authorization 요청 시:
  &auto_login=Y              ← 자동로그인 허용
  &auto_login=N              ← 자동로그인 비허용 (매번 PIN 요구)
  &auto_login_period=30      ← 자동로그인 기간 (최대 30일)
```

> **주의**: 개인정보 처리 민감 서비스(의료, 금융거래 등)에서는 `auto_login=N` 권장.

---

## 7. 제공 데이터 (클레임)

### 7.1 ID Token 클레임 (금융인증서 인증 시)

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

  // 금융인증서 전용 클레임
  "cert_type":    "FINANCIAL",
  "cert_issuer":  "KFTC",                  // 항상 KFTC
  "cert_serial":  "0123456789ABCDEF",       // 인증서 일련번호
  "cert_expire":  "20280115",              // 유효기간 (3년)
  "auto_login":   false,                   // 자동로그인 사용 여부
  "bank_code":    "004",                   // 발급 은행 코드 (KB: 004)

  // 인증 메타
  "auth_method":  "FINANCIAL_CERT",
  "auth_level":   2,                        // 2등급
  "auth_time":    1716123456,
  "instt_cd":     "5000000082"
}
```

### 7.2 은행 코드 (bank_code)

```
은행 코드 (금융결제원 표준):
  002 = KDB 산업은행
  003 = IBK 기업은행
  004 = KB 국민은행
  007 = 수협은행
  011 = NH 농협은행
  020 = 우리은행
  023 = SC 제일은행
  027 = 씨티은행
  032 = 대구은행
  034 = 광주은행
  035 = 제주은행
  037 = 전북은행
  039 = 경남은행
  045 = 새마을금고
  048 = 신협
  071 = 우체국
  081 = 하나은행
  088 = 신한은행
  089 = K뱅크
  090 = 카카오뱅크
  092 = 토스뱅크
```

---

## 8. 개발 연동 방법

### 8.1 Authorization 요청 (금융인증서 지정)

```
GET https://ptl.anyid.go.kr/oidc/authorize
  ?response_type=code
  &client_id=YOUR_CLIENT_ID
  &redirect_uri=https%3A%2F%2Fyour-service.go.kr%2Fcallback
  &scope=openid+profile+ci
  &state=RANDOM_STATE_VALUE
  &nonce=RANDOM_NONCE_VALUE
  &auth_method=FINANCIAL_CERT              ← 금융인증서 직접 지정
  &auto_login=Y                            ← 자동로그인 허용 (선택)
  &instt=5000000082
  &code_challenge=BASE64URL_SHA256
  &code_challenge_method=S256
```

### 8.2 Spring Boot 연동 예시

```java
// FinCertAuthController.java
@GetMapping("/auth/fin-cert/initiate")
public ResponseEntity<Void> initiateFinCert(
    @RequestParam(defaultValue = "Y") String autoLogin,
    HttpSession session
) {
    String state = generateSecureRandom(32);
    String nonce = generateSecureRandom(32);
    String codeVerifier = generateCodeVerifier();
    String codeChallenge = sha256Base64Url(codeVerifier);

    session.setAttribute("oidc_state", state);
    session.setAttribute("oidc_nonce", nonce);
    session.setAttribute("pkce_verifier", codeVerifier);

    String authUrl = UriComponentsBuilder
        .fromHttpUrl(issuerUri + "/oidc/authorize")
        .queryParam("response_type", "code")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", redirectUri)
        .queryParam("scope", "openid profile ci")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("auth_method", "FINANCIAL_CERT")
        .queryParam("auto_login", autoLogin)
        .queryParam("instt", instt)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .build().toUriString();

    return ResponseEntity.status(302)
        .header("Location", authUrl)
        .build();
}
```

### 8.3 금융인증서 + 공동인증서 통합 UI

이용기관이 두 PKI 인증서를 모두 지원할 때의 UI 분기 처리:

```java
// CertAuthSelector.java — 인증서 종류 선택 처리
@GetMapping("/auth/cert/select")
public String selectCertType(Model model) {
    model.addAttribute("certTypes", List.of(
        new CertTypeOption("JOINT_CERT",    "공동인증서",  "구 공인인증서, HDD/USB 저장"),
        new CertTypeOption("FINANCIAL_CERT","금융인증서",  "KFTC 클라우드, 자동로그인 지원")
    ));
    return "cert-select";
}
```

```tsx
// CertSelectPage.tsx
function CertSelectPage() {
    return (
        <div className="cert-select">
            <button onClick={() => navigate('/auth/joint-cert/initiate')}>
                <img src="/icons/joint-cert.svg" />
                <div>
                    <strong>공동인증서</strong>
                    <small>PC/USB에 저장된 인증서</small>
                </div>
            </button>
            <button onClick={() => navigate('/auth/fin-cert/initiate?autoLogin=Y')}>
                <img src="/icons/fin-cert.svg" />
                <div>
                    <strong>금융인증서</strong>
                    <small>KFTC 클라우드 저장, 자동로그인 지원</small>
                </div>
                <span className="badge">자동로그인</span>
            </button>
        </div>
    );
}
```

### 8.4 자동로그인 세션 관리

```java
// 자동로그인 여부를 회원 프로필에 저장
@Service
public class FinCertSessionManager {

    public void processFinCertLogin(AnyIdClaims claims, HttpSession session) {
        String ci = claims.getCi();
        boolean autoLogin = Boolean.parseBoolean(claims.getAutoLogin());
        String bankCode  = claims.getBankCode(); // 발급 은행 정보 (로깅용)

        Member member = memberService.findOrCreateByCi(ci, claims);

        // 자동로그인 동의한 경우 세션 연장
        if (autoLogin) {
            session.setMaxInactiveInterval(30 * 24 * 60 * 60); // 30일
            member.setAutoLoginEnabled(true);
            member.setAutoLoginExpiry(LocalDate.now().plusDays(30));
        } else {
            session.setMaxInactiveInterval(30 * 60); // 30분 (기본)
        }

        log.info("금융인증서 로그인. ci_hash={}, bank={}, autoLogin={}",
            sha256Hex(ci), bankCode, autoLogin);

        sessionService.createSession(session, member);
    }
}
```

---

## 9. 발급 대상 및 은행별 지원 현황

### 9.1 발급 자격

```
개인 금융인증서:
  ✅ 만 14세 이상 대한민국 국민 (외국인 제한)
  ✅ 인터넷뱅킹 계좌 보유자 (발급 은행 계좌 필요)
  ✅ 발급 비용: 무료

기업 금융인증서:
  ✅ 사업자등록번호 보유 법인 또는 개인사업자
  ✅ 기업인터넷뱅킹 가입 필요
```

### 9.2 은행별 발급 지원 현황 (2025년 기준)

| 은행 | 개인 | 기업 | 앱 이름 |
|------|------|------|--------|
| KB국민은행 | ✅ | ✅ | KB스타뱅킹 |
| 신한은행 | ✅ | ✅ | 신한 쏠(SOL) |
| 우리은행 | ✅ | ✅ | 우리WON뱅킹 |
| NH농협은행 | ✅ | ✅ | NH콕뱅크 |
| 하나은행 | ✅ | ✅ | 하나1Q뱅크 |
| IBK기업은행 | ✅ | ✅ | i-ONE뱅크 |
| 카카오뱅크 | ✅ | ❌ | 카카오뱅크 |
| 토스뱅크 | ✅ | ❌ | 토스 |
| K뱅크 | ✅ | ❌ | K뱅크 |
| 우체국 | ✅ | ✅ | 포스트페이 |
| 기타 지방은행 | ✅ | ✅ | 각 은행 앱 |

### 9.3 유효기간 및 갱신

```
유효기간: 3년 (공동인증서 1년 대비 장점)
갱신 방법:
  - 만료 3개월 전부터 갱신 가능
  - 발급 은행 앱에서 온라인 갱신 (무료)
  - 갱신 시 인증서 일련번호 변경 → CI는 동일 유지
```

---

## 10. 오류 코드 및 예외 처리

### 10.1 fincert 주요 오류 코드

| 오류 코드 | 의미 | 처리 방법 |
|---------|------|---------|
| `FC_001` | 금융인증서 미발급 | "금융인증서를 먼저 발급해주세요. (은행 앱에서 발급 가능)" |
| `FC_002` | PIN 오류 (5회 초과 잠김) | "PIN 오류 5회 초과. 발급 은행 앱에서 잠금 해제 후 이용해주세요" |
| `FC_003` | 인증서 만료 (3년 경과) | "금융인증서가 만료되었습니다. 은행 앱에서 갱신해주세요" |
| `FC_004` | 인증서 폐기 | "폐기된 인증서입니다. 은행 앱에서 재발급 후 이용해주세요" |
| `FC_005` | 자동로그인 세션 만료 | PIN 재입력 화면으로 이동 |
| `FC_006` | KFTC 클라우드 서버 오류 | "잠시 후 다시 시도해주세요. 문의: 1566-2670" |
| `FC_007` | 생년월일 불일치 | "입력한 생년월일이 인증서와 일치하지 않습니다" |
| `FC_010` | fincert 시스템 오류 | 기술지원 1566-2670 문의 |

### 10.2 은행 서버 장애 시 대응

```
금융인증서는 KFTC 클라우드 의존 → KFTC 장애 시 전체 서비스 불가

권장 폴백:
  1차: 금융인증서 (FINANCIAL_CERT)
  2차: 공동인증서 (JOINT_CERT) — 로컬 저장본 사용 가능
  3차: 간편인증 (EASY_SIGN) — 카카오, 네이버 등

장애 감지 방법:
  GET https://crt.anyid.go.kr/fincert/api/v2/health → HTTP 200 확인
  응답 없거나 500 → 공동인증서 또는 간편인증 우선 안내
```

### 10.3 만료 사전 안내 구현

```java
@Service
public class FinCertExpiryChecker {

    // 로그인 후 만료 임박 안내
    public Optional<String> getExpiryWarning(AnyIdClaims claims) {
        String certExpire = claims.getCertExpire(); // "20280115"
        LocalDate expireDate = LocalDate.parse(certExpire,
            DateTimeFormatter.ofPattern("yyyyMMdd"));

        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), expireDate);

        if (daysLeft <= 0) {
            return Optional.of("금융인증서가 만료되었습니다. 발급 은행 앱에서 갱신해주세요.");
        } else if (daysLeft <= 90) {  // 3개월 이내
            return Optional.of(
                String.format("금융인증서 만료 %d일 전입니다. 미리 갱신해두세요.", daysLeft)
            );
        }
        return Optional.empty();
    }
}
```

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| 공동인증서 (MagicLine4Web) | [03-joint-cert.md](./03-joint-cert.md) |
| CI/DN 브로커링 심층 분석 | [05-ci-dn-brokering.md](./05-ci-dn-brokering.md) |
| 설치형 연동 가이드 | [06-install-type-integration.md](./06-install-type-integration.md) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
