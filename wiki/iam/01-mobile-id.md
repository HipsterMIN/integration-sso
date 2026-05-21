# 모바일 신분증 인증 (Mobile ID)

> **문서 분류**: IAM / 인증수단 상세  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 보안 담당자  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [개요](#1-개요)
2. [서비스 도메인 및 URL 구조](#2-서비스-도메인-및-url-구조)
3. [VRS 아키텍처](#3-vrs-아키텍처)
4. [인증 방식: PUSH vs QR](#4-인증-방식-push-vs-qr)
5. [지원 신분증 및 앱 목록](#5-지원-신분증-및-앱-목록)
6. [인증 흐름 (시퀀스 다이어그램)](#6-인증-흐름-시퀀스-다이어그램)
7. [제공 데이터 (클레임)](#7-제공-데이터-클레임)
8. [개발 연동 방법](#8-개발-연동-방법)
9. [오류 코드 및 예외 처리](#9-오류-코드-및-예외-처리)
10. [보안 고려사항](#10-보안-고려사항)

---

## 1. 개요

**모바일 신분증**은 행정안전부가 발행하는 **DID(분산신원) 기반 디지털 신분증**으로,  
Any-ID가 제공하는 **1등급(최고 강도)** 인증수단이다.

```
법적 근거:
  주민등록법 제24조의2 (모바일 주민등록증)
  도로교통법 제87조의2 (모바일 운전면허증)
  여권법 시행령 제14조의2 (모바일 여권, 2025년~)
  → 실물 신분증과 동일한 법적 효력 인정
```

### 1.1 핵심 특징

| 특징 | 내용 |
|------|------|
| **인증 등급** | **1등급** (Any-ID 최고 강도) |
| **기술 기반** | DID(분산신원) + VC(Verifiable Credential) |
| **검증 방식** | 실시간 행안부 VRS 서버 검증 (위·변조 불가) |
| **인증 채널** | PUSH 알림 / QR코드 스캔 (2가지) |
| **오프라인 지원** | BLE 기반 오프라인 검증 (일부 앱) |
| **지원 신분증** | 주민등록증, 운전면허증, 여권 (2025년~) |

### 1.2 다른 인증수단과의 차이점

| 비교 항목 | 모바일 신분증 | 간편인증 | 공동인증서 |
|-----------|-------------|---------|-----------|
| 인증 등급 | **1등급** | 2등급 | 2등급 |
| CI 출처 | 행안부 DI → CI 변환 | 본인확인기관 | PKI DN → CI 변환 |
| 얼굴 인증 | 일부 앱 지원 | ❌ | ❌ |
| 분실 신고 반영 | ✅ 실시간 | ❌ | ❌ |
| 주민등록번호 확인 | ✅ (기관 설정에 따라) | ❌ | 일부 |

---

## 2. 서비스 도메인 및 URL 구조

### 2.1 도메인

| 환경 | URL |
|------|-----|
| **운영** | `https://mid.anyid.go.kr` |
| **데모/테스트** | `https://demo1.anyid.go.kr/mid/` |

### 2.2 실제 인증 진입 URL (스크린샷 확인)

```
https://mid.anyid.go.kr/vrs/v2.1/index.jsp
  ?instt=5000000082          ← 기관코드 (Q-Net: 5000000082)
  #/push                     ← 인증방식 해시 라우트
```

### 2.3 해시 라우트별 초기 화면

| 해시 라우트 | 진입 화면 | 설명 |
|------------|----------|------|
| `#/push` | PUSH 알림 전송 화면 | 전화번호 입력 → 앱으로 PUSH 발송 |
| `#/qr` | QR 코드 표시 화면 | 브라우저에 QR 표시 → 앱 스캔 |
| `#/` (기본) | PUSH 화면 | 기본값 |

### 2.4 핵심 파라미터

| 파라미터 | 필수 | 설명 | 예시 |
|---------|------|------|------|
| `instt` | ✅ | 기관코드 — VRS 검증 시 발급 기관 식별 | `5000000082` |
| `callback` | 권장 | 인증 완료 후 콜백 URL | `https://q-net.or.kr/sso/mid/callback` |
| `state` | 권장 | CSRF 방어용 랜덤 값 | `a3f9b1...` |
| `nonce` | 권장 | Replay Attack 방어용 랜덤 값 | `b7c2d4...` |

---

## 3. VRS 아키텍처

**VRS(Verifiable Request Service)**는 모바일 신분증의 VC를 검증하는 행안부 공식 서비스다.

```
┌─────────────────────────────────────────────────────────────────┐
│                    이용기관 (설치형)                              │
│                                                                  │
│   브라우저 ──── iframe/팝업 ────▶ mid.anyid.go.kr/vrs/v2.1      │
│                                         │                        │
│   이용기관 서버 ◀── PUSH/QR 결과 ────────│                        │
│   (CI + 클레임)                          │                        │
└──────────────────────────────────────────┼──────────────────────┘
                                           │ VP(Verifiable Presentation) 검증
                                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              행안부 VRS 검증 서버                                  │
│                                                                   │
│   ┌────────────────┐   ┌────────────────┐   ┌────────────────┐  │
│   │ VC 진위 검증    │   │ DI → CI 변환   │   │ 신분증 상태    │  │
│   │ (서명 검증)    │   │ (행안부 DI를   │   │ 확인          │  │
│   │               │   │  CI 88바이트로)  │   │ (분실·갱신)   │  │
│   └────────────────┘   └────────────────┘   └────────────────┘  │
│                                                                   │
│   ┌────────────────────────────────────────────────────────────┐ │
│   │  행안부 신분증 DB  ←────  신분증 발급 원장 (실시간 연동)     │ │
│   └────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### 3.1 VRS v2.1 버전 특이사항

| 항목 | v2.0 | v2.1 (현재) |
|------|------|------------|
| 인증 프로토콜 | 커스텀 HTTP | **OIDC 호환 토큰** |
| VP 전달 방식 | 직접 전송 | **DIDComm v2 기반** |
| 오프라인 지원 | BLE 실험적 | **BLE GA (정식 지원)** |
| 여권 지원 | ❌ | ✅ (2025년~) |
| 다중 신분증 | ❌ | ✅ |

---

## 4. 인증 방식: PUSH vs QR

### 4.1 PUSH 알림 방식

```
사용자 흐름:
  1. 브라우저에서 전화번호 입력 (010-XXXX-XXXX)
  2. VRS 서버 → 스마트폰으로 PUSH 알림 발송
  3. 사용자 앱에서 알림 탭 → 신분증 제시 화면 진입
  4. 생체인증(지문/Face ID) 또는 PIN으로 본인 확인
  5. 앱이 VP(Verifiable Presentation) 생성 → VRS 서버 전송
  6. VRS 서버 검증 완료 → 브라우저로 결과 전달
```

```
장점: 전화번호만 입력하면 됨, 모바일 환경 최적
단점: 앱 알림 수신 가능 환경 필요, 전화번호 입력 필수
```

### 4.2 QR 코드 방식

```
사용자 흐름:
  1. 브라우저에 QR 코드 표시 (30초 유효)
  2. 사용자 앱으로 QR 코드 스캔
  3. 앱에서 생체인증(지문/Face ID) 또는 PIN으로 본인 확인
  4. 앱이 VP 생성 → VRS 서버 전송
  5. VRS 서버 검증 완료 → 브라우저로 결과 전달
```

```
장점: 전화번호 입력 불필요, 키오스크·PC 환경에 적합
단점: QR 스캔 가능한 모바일 기기 필요, 화면 공유 시 보안 위험
```

### 4.3 방식 선택 가이드

| 환경 | 권장 방식 | 이유 |
|------|---------|------|
| PC 웹 브라우저 | QR | 모바일 없이도 동작, 화면 크기 충분 |
| 모바일 웹 브라우저 | PUSH | 전화번호 자동완성, 동일 기기에서 처리 |
| 태블릿 | QR | 앱과 브라우저 분리 환경 |
| 키오스크 | QR | 별도 모바일 기기로 스캔 |

---

## 5. 지원 신분증 및 앱 목록

### 5.1 지원 신분증 종류

| 신분증 | 발행 기관 | 지원 시작 | 비고 |
|--------|---------|---------|------|
| **모바일 주민등록증** | 행정안전부 | 2024.06 | 만 17세 이상 발급 가능 |
| **모바일 운전면허증** | 경찰청 | 2024.06 | 도로교통법 개정 이후 |
| **모바일 여권** | 외교부 | 2025.03~ | 단계적 도입 중 |
| 국가유공자증 | 국가보훈부 | 2025.06~ | 예정 |

### 5.2 지원 앱 (2025년 기준, 7개)

| # | 앱 이름 | 발행 기관 | 플랫폼 | 특이사항 |
|---|---------|---------|--------|---------|
| 1 | **정부24** | 행정안전부 | iOS/Android | 범용 행정 앱, 모바일 주민등록증 포함 |
| 2 | **모바일 운전면허증** | 경찰청 | iOS/Android | 운전면허증 전용 앱 |
| 3 | **PASS** (SKT/KT/LGU+) | 통신 3사 | iOS/Android | 이동통신사 본인확인 통합 앱 |
| 4 | **카카오톡** | 카카오 | iOS/Android | 카카오 지갑 연동 (2025년~) |
| 5 | **네이버** | 네이버 | iOS/Android | 네이버 인증서 통합 (2025년~) |
| 6 | **신한 쏠(SOL)** | 신한은행 | iOS/Android | 금융인증서 통합 |
| 7 | **KB스타뱅킹** | KB국민은행 | iOS/Android | 금융인증서 통합 |

> **⚠️ 주의**: 앱 지원 목록은 행안부 공지에 따라 변동될 수 있음.  
> 최신 목록: https://www.anyid.go.kr → 지원 앱 안내 참조

---

## 6. 인증 흐름 (시퀀스 다이어그램)

### 6.1 PUSH 방식 전체 흐름

```
사용자             브라우저(VRS UI)        mid.anyid.go.kr       행안부VRS서버        사용자 앱
  │                     │                       │                     │                 │
  │── 전화번호 입력 ─────▶│                       │                     │                 │
  │                     │── PUSH 요청 ──────────▶│                     │                 │
  │                     │                       │── VP 요청 ──────────▶│                 │
  │                     │                       │                     │── PUSH 알림 ────▶│
  │                     │  (폴링 대기)            │                     │                 │
  │                     │                       │                     │◀── VP 전송 ──────│
  │                     │                       │                     │  (생체인증 완료)   │
  │                     │                       │◀── 검증 결과 CI ─────│                 │
  │                     │◀─── 인증 성공 ──────────│                     │                 │
  │◀─── 완료 화면 ────────│                       │                     │                 │
```

### 6.2 QR 방식 전체 흐름

```
사용자             브라우저(VRS UI)        mid.anyid.go.kr       행안부VRS서버        사용자 앱
  │                     │                       │                     │                 │
  │── QR 방식 선택 ──────▶│                       │                     │                 │
  │                     │── QR 생성 요청 ─────────▶│                     │                 │
  │                     │◀── QR 토큰 ─────────────│                     │                 │
  │◀─── QR 코드 표시 ─────│                       │                     │                 │
  │                     │  (30초 유효, 자동 갱신)   │                     │                 │
  │── 앱으로 QR 스캔 ─────────────────────────────────────────────────────────────────────▶│
  │                     │                       │                     │◀── QR 토큰 검증 ──│
  │                     │                       │                     │── VP 서명 요청 ──▶│
  │                     │                       │                     │◀── VP 전송 ───────│
  │                     │                       │◀── 검증 결과 CI ─────│                 │
  │                     │◀─── 인증 성공 ──────────│                     │                 │
  │◀─── 완료 화면 ────────│                       │                     │                 │
```

### 6.3 Any-ID OIDC 연계 후 최종 흐름

```
VRS 인증 완료
    │
    ▼ (mid.anyid.go.kr → ptl.anyid.go.kr로 결과 전달)
ptl.anyid.go.kr (OIDC IdP)
    │ Authorization Code 발급
    ▼
이용기관 Callback Endpoint
    │ POST /token → ID Token 수신
    ▼
ID Token 파싱 → CI(88바이트) 추출
    │
    ▼
회원 조회 (CI 기반) → 세션 발급
```

---

## 7. 제공 데이터 (클레임)

### 7.1 ID Token 클레임 (모바일 신분증 인증 시)

```jsonc
{
  // 표준 OIDC 클레임
  "iss":          "https://ptl.anyid.go.kr",
  "sub":          "anyid-uuid-xxxx-xxxx",
  "aud":          "YOUR_CLIENT_ID",
  "iat":          1716123456,
  "exp":          1716127056,
  "nonce":        "제출한_nonce값",

  // Any-ID 확장 클레임
  "ci":           "ABCdef123...총88바이트",   // ★ 연계정보 (핵심)
  "di":           "DI값_행안부신분증DI",        // 행안부 DI (내부 식별자)
  "name":         "홍길동",
  "birthdate":    "19900115",               // YYYYMMDD
  "gender":       "1",                      // 1=남, 2=여
  "phone_number": "01012345678",

  // 신분증 전용 클레임
  "id_type":      "RESIDENT_CARD",          // 신분증 종류
  "id_expire":    "20300115",               // 유효기간
  "address":      "서울시 마포구...",         // (기관 요청 시)
  "face_verified": false,                   // 얼굴 인증 여부

  // 인증 메타
  "auth_method":  "MOBILE_ID",             // 인증수단
  "auth_level":   1,                        // ★ 1등급
  "auth_time":    1716123456,
  "instt_cd":     "5000000082"             // 기관코드
}
```

### 7.2 클레임 제공 범위 설정

클레임 제공 범위는 `scope` 파라미터 + 기관 협약 시 설정:

| scope 값 | 제공 클레임 |
|---------|-----------|
| `openid` | `sub`, `iss`, `aud`, `iat`, `exp` |
| `openid profile` | + `name`, `birthdate`, `gender` |
| `openid phone` | + `phone_number` |
| `openid address` | + `address` (협약 필요) |
| `openid ci` | + `ci` (**필수 협약 항목**) |
| `openid id_info` | + `id_type`, `id_expire` |

> **⚠️ CI 클레임 사용 시**: 반드시 행안부와 개인정보 처리 협약 체결 후 scope에 `ci` 추가 가능.

### 7.3 신분증 종류 코드

| `id_type` 값 | 신분증 종류 |
|-------------|-----------|
| `RESIDENT_CARD` | 모바일 주민등록증 |
| `DRIVER_LICENSE` | 모바일 운전면허증 |
| `PASSPORT` | 모바일 여권 (2025년~) |
| `VETERANS_CERT` | 국가유공자증 (예정) |

---

## 8. 개발 연동 방법

### 8.1 설치형 연동 사전 준비

```
1. Any-ID 이용기관 등록
   ├── 행안부 제출: 기관 정보, 서비스 목적, 개인정보 처리방침
   ├── 발급: client_id, client_secret, instt (기관코드)
   └── 협약: CI 수집 동의, scope 목록 확인

2. 인프라 설정
   ├── HTTPS 필수 (callback URI)
   ├── 도메인 화이트리스트 등록 (ptl.anyid.go.kr에 callback URI 등록)
   └── 방화벽 오픈: ptl.anyid.go.kr, mid.anyid.go.kr (443/TCP 아웃바운드)
```

### 8.2 OIDC Authorization 요청

```
GET https://ptl.anyid.go.kr/oidc/authorize
  ?response_type=code
  &client_id=YOUR_CLIENT_ID
  &redirect_uri=https%3A%2F%2Fyour-service.go.kr%2Fcallback
  &scope=openid+profile+phone+ci
  &state=RANDOM_STATE_VALUE          ← CSRF 방어 (32자 이상)
  &nonce=RANDOM_NONCE_VALUE          ← Replay 방어
  &auth_method=MOBILE_ID             ← 모바일 신분증 직접 지정
  &instt=5000000082                  ← 기관코드
  &code_challenge=BASE64URL_SHA256   ← PKCE (권장)
  &code_challenge_method=S256
```

### 8.3 Token 교환

```http
POST https://ptl.anyid.go.kr/oidc/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic BASE64(client_id:client_secret)

grant_type=authorization_code
&code=AUTHORIZATION_CODE
&redirect_uri=https://your-service.go.kr/callback
&code_verifier=PKCE_CODE_VERIFIER    ← PKCE 사용 시
```

**응답 예시:**

```json
{
  "access_token": "eyJhbGc...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "id_token": "eyJhbGc...",           // CI 포함
  "refresh_token": "dGhpcyBp..."
}
```

### 8.4 Spring Boot 구현 예시

```java
// application.yml
anyid:
  client-id: ${ANYID_CLIENT_ID}
  client-secret: ${ANYID_CLIENT_SECRET}
  issuer-uri: https://ptl.anyid.go.kr
  redirect-uri: https://your-service.go.kr/callback/anyid
  scope: openid,profile,phone,ci
  instt: "5000000082"
  auth-method: MOBILE_ID

// MobileIdAuthController.java
@GetMapping("/auth/mobile-id/initiate")
public ResponseEntity<Void> initiateMobileIdAuth(HttpSession session) {
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
        .queryParam("scope", "openid profile phone ci")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("auth_method", "MOBILE_ID")
        .queryParam("instt", instt)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .build().toUriString();

    return ResponseEntity.status(302)
        .header("Location", authUrl)
        .build();
}

// MobileIdCallbackController.java
@GetMapping("/callback/anyid")
public ResponseEntity<?> handleCallback(
    @RequestParam String code,
    @RequestParam String state,
    HttpSession session
) {
    // 1. state 검증
    String savedState = (String) session.getAttribute("oidc_state");
    if (!savedState.equals(state)) {
        throw new SecurityException("State mismatch — CSRF 가능성");
    }

    // 2. Token 교환
    AnyIdTokenResponse tokens = anyIdClient.exchangeToken(
        code,
        (String) session.getAttribute("pkce_verifier")
    );

    // 3. ID Token 파싱 + nonce 검증
    AnyIdClaims claims = jwtParser.parseAndVerify(
        tokens.getIdToken(),
        (String) session.getAttribute("oidc_nonce")
    );

    // 4. CI 기반 회원 조회 or 생성
    String ci = claims.getCi();                    // 88바이트
    int authLevel = claims.getAuthLevel();         // 1 (모바일 신분증)
    String authMethod = claims.getAuthMethod();    // "MOBILE_ID"

    Member member = memberService.findOrCreateByCi(ci, claims);
    sessionService.createSession(session, member);

    return ResponseEntity.ok(LoginResponse.of(member));
}
```

### 8.5 CI 기반 회원 매핑 로직

```java
@Service
@Transactional
public class MobileIdMemberService {

    public Member findOrCreateByCi(String ci, AnyIdClaims claims) {
        // CI는 그대로 저장하지 않고 해시로 저장 (개인정보 보호)
        String ciHash = sha256Hex(ci);

        return memberRepository.findByCiHash(ciHash)
            .map(member -> {
                // 기존 회원 — 정보 갱신 (이름, 전화번호)
                member.updateFromAnyId(claims);
                return member;
            })
            .orElseGet(() -> {
                // 신규 회원 생성
                Member newMember = Member.createFromAnyId(ciHash, claims);
                return memberRepository.save(newMember);
            });
    }
}
```

---

## 9. 오류 코드 및 예외 처리

### 9.1 VRS 오류 코드

| 오류 코드 | 의미 | 처리 방법 |
|---------|------|---------|
| `VRS_001` | 신분증 미발급 또는 앱 미설치 | "모바일 신분증 앱을 먼저 설치해주세요" 안내 |
| `VRS_002` | 신분증 만료 | "신분증이 만료되었습니다. 재발급 후 이용해주세요" |
| `VRS_003` | 분실 신고된 신분증 | "분실 신고된 신분증입니다. 경찰서에 문의해주세요" |
| `VRS_004` | PUSH 알림 수신 실패 (앱 알림 차단) | "앱 알림 설정을 확인하거나 QR 방식을 이용해주세요" |
| `VRS_005` | QR 코드 만료 (30초 초과) | "QR 코드가 만료되었습니다. 새로고침 후 다시 시도해주세요" |
| `VRS_006` | 생체인증 실패 (5회 초과) | "생체인증에 실패했습니다. 잠시 후 다시 시도해주세요" |
| `VRS_010` | VRS 서버 오류 | 행안부 VRS 장애 — 기술지원센터 1566-2670 문의 |
| `VRS_TIMEOUT` | 인증 시간 초과 (3분) | "인증 시간이 초과되었습니다. 처음부터 다시 시도해주세요" |

### 9.2 OIDC 레벨 오류

| 오류 | 의미 | 처리 |
|------|------|------|
| `access_denied` | 사용자가 인증 취소 | 로그인 페이지로 리다이렉트 |
| `invalid_request` | 파라미터 오류 (state, nonce 누락 등) | 요청 파라미터 확인 |
| `server_error` | Any-ID 플랫폼 오류 | 재시도 or 기술지원 문의 |

---

## 10. 보안 고려사항

### 10.1 필수 보안 구현

```
✅ state 파라미터: CSRF 방어 — 32자 이상 cryptographically random
✅ nonce 파라미터: ID Token Replay Attack 방어
✅ PKCE (code_challenge): Authorization Code 탈취 방어
✅ state/nonce Redis 또는 세션 저장 (1회 소비 후 삭제)
✅ ID Token 서명 검증: ptl.anyid.go.kr JWK 엔드포인트 사용
✅ audience 검증: aud === client_id
✅ issuer 검증: iss === "https://ptl.anyid.go.kr"
✅ exp 검증: 만료 토큰 거부
```

### 10.2 CI 취급 주의사항

```
⚠️ CI는 개인정보(준개인정보)로 분류
⚠️ DB 저장 시 반드시 해시(SHA-256) 또는 암호화 저장
⚠️ 로그에 CI 원문 출력 금지
⚠️ 내부 서비스 간 전달 시 암호화 채널(TLS 1.2+) 필수
⚠️ 만 14세 미만은 CI 수집 제한 (아동 개인정보 보호법)
```

### 10.3 QR 방식 추가 보안

```
⚠️ QR 유효시간: 30초 (서버에서 강제 만료)
⚠️ 화면 공유 환경(스트리밍, 원격 데스크탑)에서 QR 방식 사용 주의
⚠️ QR 토큰은 1회 사용 후 무효화 (서버 처리)
```

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| 간편인증 (2등급) | [02-easy-sign.md](./02-easy-sign.md) |
| CI/DN 브로커링 | [05-ci-dn-brokering.md](./05-ci-dn-brokering.md) |
| 설치형 연동 가이드 | [06-install-type-integration.md](./06-install-type-integration.md) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
