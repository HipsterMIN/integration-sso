# Any-ID 정부 통합인증 — 전체 개요

> **문서 분류**: IAM (Identity & Access Management) 연동 가이드  
> **버전**: v1.1.0  
> **작성일**: 2026-05-19  
> **최종 수정**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 아키텍트, 보안 담당자  
> **관련 서비스**: `ido` (port 8083), `q-sign`, `onepass-fe`

---

## 목차

1. [Any-ID란](#1-any-id란)
2. [URL 구조 해부](#2-url-구조-해부)
3. [아키텍처 전체 구조](#3-아키텍처-전체-구조)
4. [구축 방식 비교: 중계형 vs 설치형](#4-구축-방식-비교-중계형-vs-설치형)
5. [인증수단 전체 목록](#5-인증수단-전체-목록)
6. [인증 등급 체계](#6-인증-등급-체계)
7. [OIDC Authorization Code Flow 요약](#7-oidc-authorization-code-flow-요약)
8. [OnePass 플랫폼 연동 구조](#8-onepass-플랫폼-연동-구조)
9. [환경별 엔드포인트 정리](#9-환경별-엔드포인트-정리)
10. [Quick Reference — 개발자 체크리스트](#10-quick-reference--개발자-체크리스트)
11. [문서 구성 안내](#11-문서-구성-안내)
12. [연락처 및 참고 리소스](#12-연락처-및-참고-리소스)

---

## 1. Any-ID란

**Any-ID (정부 통합인증)**는 행정안전부가 2024년 6월부터 운영하는 범정부 공통 인증 인프라다.  
모바일 신분증·간편인증·공동인증서·금융인증서·민간 ID 등 **5종의 인증수단**을 표준화된 OIDC 기반 API로 제공하며, 한 번의 인증으로 여러 정부 누리집을 이용하는 **SSO(Single Sign-On)** 를 지원한다.

```
법적 근거:
  전자정부법 제10조 (전자정부서비스 본인확인)
  전자정부법 시행령 제12조 (개정 2024-05-21, 대통령령 제34518호)
  → Any-ID가 제공하는 CI/주민번호를 공식 본인확인 수단으로 인정
```

### 1.1 핵심 특징

| 특징 | 내용 |
|------|------|
| **단일 인증** | 1회 인증으로 연계된 전 서비스 SSO |
| **다양한 수단** | 5종 인증수단 + 11개 이상의 민간인증앱 |
| **CI 브로커링** | 인증수단에 무관하게 CI(연계정보) 표준 제공 |
| **등급 제어** | 기관별 보안정책에 따른 인증 등급 강제 가능 |
| **2025년 기준** | 60개 이상 공공 서비스 적용, 계속 확산 중 |

### 1.2 관련 기관 적용 사례

| 기관 | 특이사항 |
|------|---------|
| **Q-Net (한국산업인력공단)** | SSO On/Off 토글 제공, 자격시험 고유정보 추가 가입 유지 |
| **정부24 (범정부 통합창구)** | 초기 시범 적용, SSO 허브 역할 |
| **대법원 가족관계 시스템** | 사법부 최초 연계 |
| **한국장학재단** | 2등급 이상 인증수단만 SSO 허용 |
| **경찰청, 권익위, 국토부** | 단계적 확산 적용 중 |

---

## 2. URL 구조 해부

스크린샷에서 확인된 실제 도메인 구조:

```
이용기관 (예: q-net.or.kr)
    │
    └──▶ ptl.anyid.go.kr          ← Any-ID 통합플랫폼 (OIDC IdP / SSO 포털)
              │
              ├──▶ mid.anyid.go.kr        ← 모바일 신분증 (VRS 서비스)
              ├──▶ easysign.anyid.go.kr   ← 간편인증 (민간인증서 브로커)
              └──▶ crt.anyid.go.kr        ← 공동인증서 + 금융인증서
```

### 2.1 도메인별 역할

| 도메인 | 역할 | 실제 URL 예시 |
|--------|------|--------------|
| `ptl.anyid.go.kr` | **통합플랫폼** — OIDC Authorization Server, SSO 세션, 사용자 등록 | `ptl.anyid.go.kr/anyid/user/idv/itg/trms?srvcNo=5000000084&userSeCd=01` |
| `mid.anyid.go.kr` | **모바일 신분증** — VRS(Verifiable Request Service), PUSH/QR 인증 | `mid.anyid.go.kr/vrs/v2.1/index.jsp?instt=5000000082#/push` |
| `easysign.anyid.go.kr` | **간편인증** — 민간 인증서 브로커 (카카오·네이버·KB 등) | `easysign.anyid.go.kr/esign/` |
| `crt.anyid.go.kr` | **공동/금융 인증서** — MagicLine4Web, KFTC 클라우드 저장소 | `crt.anyid.go.kr/MagicLine4Web/v2.2/share.jsp` |

### 2.2 핵심 파라미터

| 파라미터 | 설명 | 예시 |
|---------|------|------|
| `instt` | **기관코드** — 이용기관 식별자, 인증수단 팝업 UI 결정에 사용 | `5000000082` |
| `srvcNo` | **서비스번호** — 하나의 기관이 여러 서비스를 등록할 때 구분 | `5000000084` |
| `#/push` `#/qr` | 모바일 신분증 인증방식 해시 라우트 | `#/push` (PUSH 알림) |

---

## 3. 아키텍처 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│                     이용기관 서버 (설치형)                        │
│                                                                  │
│  ┌────────────────────┐    ┌──────────────────────────────┐     │
│  │  서비스 애플리케이션  │    │    Any-ID Module (설치)       │     │
│  │   (ido 서비스)      │◄──│  - OIDC RP                   │     │
│  │                    │    │  - SSO 세션 관리               │     │
│  │  CI 기반 회원 처리  │    │  - CI/DN 파싱·매핑            │     │
│  └────────┬───────────┘    └──────────────┬───────────────┘     │
└───────────┼────────────────────────────────┼────────────────────┘
            │                                │ OIDC Authorization Code Flow
            │                                ▼
┌───────────▼────────────────────────────────────────────────────┐
│                 ptl.anyid.go.kr (Any-ID 통합플랫폼)              │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ OIDC IdP │  │ SSO 세션  │  │ 사용자   │  │ JWK 키   │       │
│  │ 엔진     │  │ 관리      │  │ 등록 DB  │  │ 관리     │       │
│  └────┬─────┘  └──────────┘  └──────────┘  └──────────┘       │
│       │ 인증수단 라우팅 (instt 기반)                               │
└───────┼────────────────────────────────────────────────────────┘
        │
   ┌────┴──────────────────────────────────┐
   │          인증수단 브로커 레이어          │
   ├──────────────┬────────────┬────────────┤
   ▼              ▼            ▼            ▼
mid.anyid      easysign     crt.anyid    crt.anyid
(모바일신분증)  (간편인증)   (공동인증서)  (금융인증서)
   │              │            │            │
   ▼              ▼            ▼            ▼
행안부VRS       카카오/네이버  CA(MagicLine) KFTC클라우드
검증서버        KB/NH/토스    PKI 인증서    저장소
               (본인확인기관)
```

---

## 4. 구축 방식 비교: 중계형 vs 설치형

| 항목 | 중계형 (클라우드 SaaS) | 설치형 (On-Premise) |
|------|----------------------|---------------------|
| **인프라 위치** | 행안부/공급사 IDC | 기관 내부 서버 |
| **운영 주체** | 공급사 | 기관 자체 |
| **초기 비용** | 없음 | 라이선스 + 서버 구축 |
| **운영 비용** | 건당 과금 (트래픽 기반) | 유지보수 계약 (고정비) |
| **버전 관리** | 자동 업데이트 | 수동 패치 |
| **커스터마이징** | 제한적 | 폭넓은 수정 가능 |
| **확장성** | 탄력적 (공급사 책임) | 자체 인프라 증설 |
| **도입 기간** | 수 주 (계약 후 설정) | 수 개월 (설치·테스트) |
| **적합 조건** | 소규모, 신속 도입 | **월 수백만 건↑, 폐쇄망** |

> **본 프로젝트 선택: 설치형**  
> 회원 수가 많아 건당 과금 모델보다 고정비 구조가 TCO 면에서 유리하고,  
> 기관 내부 정책상 CI 등 민감 데이터를 외부 경유 없이 처리해야 하기 때문이다.

### 4.1 설치형 구성 컴포넌트

```
기관 서버에 설치되는 컴포넌트:
├── Any-ID SSO Agent (WAR/JAR)    ← OIDC RP 기능 + 세션 관리
├── Any-ID Key Manager             ← 서명키 관리 (JWK, RSA-2048+)
├── Any-ID Admin Console           ← 이용기관 관리자 웹 화면
├── Any-ID DB Schema               ← 사용자 매핑 테이블 (Flyway)
└── Reverse Proxy 설정             ← HTTPS 종단 + CORS
```

---

## 5. 인증수단 전체 목록

| # | 수단 | 분류 | 서비스 도메인 | 상세 문서 |
|---|------|------|-------------|---------|
| 1 | **모바일 신분증** | 공공 (행안부) | `mid.anyid.go.kr` | [01-mobile-id.md](./01-mobile-id.md) |
| 2 | **간편인증** (민간인증서) | 민간 11종↑ | `easysign.anyid.go.kr` | [02-easy-sign.md](./02-easy-sign.md) |
| 3 | **공동인증서** | 공공 PKI | `crt.anyid.go.kr/MagicLine4Web` | [03-joint-cert.md](./03-joint-cert.md) |
| 4 | **금융인증서** | 금융결제원 | `crt.anyid.go.kr/fincert` | [04-fin-cert.md](./04-fin-cert.md) |
| 5 | **민간 ID** | 네이버·카카오·토스 | `ptl.anyid.go.kr` | - (3등급, SSO 제한) |

---

## 6. 인증 등급 체계

Any-ID는 인증 강도에 따라 3단계 등급을 정의한다.  
기관은 서비스 보안 정책에 따라 **최소 요구 등급**을 설정할 수 있다.

```
1등급 (최고 강도)
    └── 모바일 신분증
         · 행안부 발행 DID 기반 VC (Verifiable Credential)
         · 주민등록증·운전면허증·여권 원본 검증
         · 실시간 행안부 VRS 서버 검증

2등급 (표준 강도)
    ├── 공동인증서 (구 공인인증서)
    │    · PKI 전자서명, Subject DN에서 CI 추출
    ├── 금융인증서
    │    · 금융결제원(KFTC) 클라우드 PKI
    └── 간편인증 (민간인증서)
         · 방통위 허가 본인확인기관의 민간 PKI/FIDO2
         · 카카오·네이버·KB·NH·토스·PASS 등

3등급 (간이 강도)
    └── 민간 ID (소셜 로그인)
         · 네이버·카카오·토스 계정
         · SSO 허용 여부는 기관 정책에 따라 제한 가능
```

> **실무 주의**: 한국장학재단 사례처럼 일부 기관은 **2등급 이상만 SSO 세션 수락**한다.  
> 3등급 민간 ID로 로그인한 세션은 해당 기관에서 재인증 요구될 수 있다.

---

## 7. OIDC Authorization Code Flow 요약

Any-ID는 **OIDC Authorization Code Flow** 를 기반으로 동작한다.

```
사용자                이용기관                   ptl.anyid.go.kr              인증수단
  │                      │                            │                         │
  │── 로그인 클릭 ────────▶│                            │                         │
  │                      │── Authorization Request ──▶│                         │
  │                      │   (client_id, scope,        │                         │
  │                      │    state, nonce, instt)      │                         │
  │◀──────── 인증 팝업 리다이렉트 ─────────────────────│                         │
  │                      │                            │── 인증수단 라우팅 ──────▶│
  │                      │                            │◀─ 인증결과 + CI ─────────│
  │── 인증 완료 ──────────────────────────────────────▶│                         │
  │                      │◀── Authorization Code ─────│                         │
  │                      │── Token Request ───────────▶│ (서버 간 통신)           │
  │                      │◀── ID Token (CI 포함) ──────│                         │
  │                      │                            │                         │
  │                      │  [CI로 회원 조회·생성]       │                         │
  │◀── 로그인 완료 ────────│                            │                         │
```

**핵심 토큰 클레임** (ID Token / UserInfo):

```jsonc
{
  "iss":         "https://ptl.anyid.go.kr",
  "sub":         "anyid-uuid-xxxx",        // Any-ID 내부 식별자
  "aud":         "YOUR_CLIENT_ID",
  "ci":          "88바이트_CI값",           // ★ 연계정보 (가장 중요)
  "name":        "홍길동",
  "birthdate":   "19900101",
  "gender":      "1",                      // 1=남, 2=여
  "phone_number":"01012341234",
  "auth_method": "EASY_SIGN",              // 사용된 인증수단
  "auth_level":  2,                        // 인증 등급
  "instt_cd":    "5000000082"             // 기관코드
}
```

---

## 8. OnePass 플랫폼 연동 구조

본 프로젝트(OnePass 통합인증 플랫폼)는 Any-ID를 **외부 OIDC IdP**로 연동하며,  
내부적으로 `ido` 서비스(IdO — Identity Orchestrator)가 OIDC RP(Relying Party) 역할을 담당한다.

### 8.1 서비스 역할 매핑

```
[사용자 브라우저]
      │
      ▼
[onepass-fe :3000] ── React SPA, beInstance (X-BE-API-Key 헤더)
      │ API 요청
      ▼
[ido :8083] ── Identity Orchestrator
      │  ┌─────────────────────────────────────────────┐
      │  │  Any-ID 연동 모듈 (OIDC RP)                  │
      │  │  - AnyIdAuthController    ← 인증 시작        │
      │  │  - AnyIdCallbackController ← 콜백 처리       │
      │  │  - AnyIdOidcService       ← 토큰 교환        │
      │  │  - AnyIdJwtVerifier       ← ID Token 검증    │
      │  └─────────────────────────────────────────────┘
      │  OIDC Authorization Code Flow (with PKCE)
      ▼
[ptl.anyid.go.kr] ── Any-ID 통합플랫폼 (외부)
      │  ID Token (CI 포함)
      ▼
[q-sign :8081] ── Q-Sign (Auth Session 관리)
      │  AUTH_COMPLETED 이벤트
      ▼
[Kafka Topic: qsign.auth.events] ── 이벤트 버스
      │
      ▼
[q-im :8082] ── Q Identity Manager (회원 관리)
      │  CI 기반 회원 조회·생성·갱신
      ▼
[PostgreSQL + Redis] ── 데이터 레이어
```

### 8.2 OnePass 내부 브로커링 모드

`ido`는 3가지 브로커링 모드를 지원하며, `IDO_BROKER_MODE` 환경변수로 전환한다:

| 모드 | 환경변수 | 설명 | Any-ID 연동 |
|------|---------|------|------------|
| `qsign` | `IDO_BROKER_MODE=qsign` | q-sign이 Keycloak을 통해 처리 (기본) | Keycloak ↔ Any-ID OIDC 브리지 |
| `keycloak` | `IDO_BROKER_MODE=keycloak` | ido가 Keycloak Token Endpoint 직접 호출 | Keycloak을 Any-ID IdP로 등록 |
| `anyid` | `IDO_BROKER_MODE=anyid` | **ido가 Any-ID를 직접 호출** | ★ 설치형 연동 (본 문서 해당) |

> **현재 프로젝트**: `anyid` 모드 — `ido`가 `ptl.anyid.go.kr`의 OIDC Endpoint를 직접 호출한다.

### 8.3 Kafka 이벤트 흐름

Any-ID 인증 완료 후 내부 이벤트 버스를 통해 회원 데이터가 전파된다:

```
ptl.anyid.go.kr → ID Token (CI, auth_level, auth_method)
    │
    ▼
ido (AnyIdCallbackController)
    │ CI 추출 + SHA-256 해시
    │ AUTH_COMPLETED 이벤트 발행
    ▼
Kafka: qsign.auth.events
  {
    "type":       "AUTH_COMPLETED",
    "ciHash":     "SHA-256(CI + salt)",
    "authLevel":  2,
    "authMethod": "EASY_SIGN",
    "provider":   "KAKAO",
    "insttCd":    "5000000082",
    "correlationId": "uuid-xxxx"
  }
    │
    ▼
QsignAuthEventConsumer (q-sign)
    │ 세션 발급 (CAST Token + Redis)
    ▼
QimUserEventConsumer (q-im)
    │ 회원 조회·생성·갱신 (ci_hash 기준)
    ▼
AgencyProvisioningService
    │ 68개 기관 병렬 HTTPS 알림 (Virtual Thread)
    ▼
Transactional Outbox → Kafka: qim.user.events
```

### 8.4 Any-ID 인증 완료 후 세션 발급 흐름

```java
// ido: AnyIdCallbackController.java 핵심 흐름
@GetMapping("/callback/anyid")
public ResponseEntity<?> handleAnyIdCallback(
    @RequestParam String code,
    @RequestParam String state,
    HttpServletRequest request
) {
    // 1. state/nonce 검증 (Redis 1회 소비)
    OidcStateEntry entry = stateStore.consumeAndDelete(state)
        .orElseThrow(() -> new SecurityException("CSRF 위험: state 불일치"));

    // 2. Token 교환 (서버 간 통신)
    AnyIdTokenResponse tokens = oidcService.exchangeToken(
        code, entry.getCodeVerifier()
    );

    // 3. ID Token 검증 (JWK 서명 + iss/aud/exp/nonce)
    AnyIdClaims claims = jwtVerifier.verify(
        tokens.getIdToken(), entry.getNonce()
    );

    // 4. CI 해시 생성 + 회원 처리
    String ciHash = ciHashEncoder.encode(claims.getCi());
    Member member = memberService.findOrCreateByCi(ciHash, claims);

    // 5. AUTH_COMPLETED 이벤트 발행 → Kafka
    authEventPublisher.publish(AuthCompletedEvent.of(member, claims));

    // 6. 세션 쿠키 발급 (Redis 저장)
    String sessionId = sessionService.createSession(member, claims);

    return ResponseEntity.status(302)
        .header("Set-Cookie", buildSecureCookie("SESSION_ID", sessionId))
        .header("Location", "/dashboard")
        .build();
}
```

---

## 9. 환경별 엔드포인트 정리

### 9.1 Any-ID 플랫폼 엔드포인트

| 환경 | 기본 URL | 용도 |
|------|---------|------|
| **운영** | `https://ptl.anyid.go.kr` | 실 서비스 |
| **데모/검증** | `https://demo1.anyid.go.kr` | 개발·테스트 (가상 CI) |

### 9.2 OIDC Discovery 엔드포인트

```
# Well-known 설정 (OpenID Connect Discovery)
GET https://ptl.anyid.go.kr/.well-known/openid-configuration

# JWK (JSON Web Key Set) — ID Token 서명 검증용
GET https://ptl.anyid.go.kr/.well-known/jwks.json

# Authorization Endpoint
GET https://ptl.anyid.go.kr/oidc/authorize

# Token Endpoint
POST https://ptl.anyid.go.kr/oidc/token

# UserInfo Endpoint
GET https://ptl.anyid.go.kr/oidc/userinfo
Authorization: Bearer {access_token}

# Logout Endpoint (SLO)
GET https://ptl.anyid.go.kr/oidc/logout
  ?id_token_hint={id_token}
  &post_logout_redirect_uri={uri}
  &state={state}
```

### 9.3 인증수단별 진입 URL

| 인증수단 | 운영 URL | 비고 |
|---------|---------|------|
| **모바일 신분증 (PUSH)** | `https://mid.anyid.go.kr/vrs/v2.1/index.jsp?instt={instt}#/push` | PUSH 알림 방식 |
| **모바일 신분증 (QR)** | `https://mid.anyid.go.kr/vrs/v2.1/index.jsp?instt={instt}#/qr` | QR 스캔 방식 |
| **간편인증 (전체 목록)** | `https://easysign.anyid.go.kr/esign/?instt={instt}&srvcNo={srvcNo}` | 인증서 선택 UI |
| **간편인증 (직접 지정)** | `https://easysign.anyid.go.kr/esign/?instt={instt}&provider=KAKAO` | 카카오 직접 |
| **공동인증서** | `https://crt.anyid.go.kr/MagicLine4Web/v2.2/share.jsp?instt={instt}` | MagicLine4Web v2.2 |
| **금융인증서** | `https://crt.anyid.go.kr/fincert/v2.2/?instt={instt}&autoLogin=Y` | KFTC 클라우드 |

### 9.4 Q-Net 실제 파라미터 (참고)

```
기관코드 (instt):    5000000082
서비스번호 (srvcNo): 5000000084
서비스 명칭:         Q-Net (한국산업인력공단)
SSO On/Off:         On (자격시험 외 일반 서비스)
최소 인증 등급:      2등급 이상 (1·2등급 수락)
CI 범위:            ci (필수), profile, phone
```

---

## 10. Quick Reference — 개발자 체크리스트

### 10.1 Any-ID 연동 시작 전 필수 확인

```
기관 등록 확인
  □ 행안부 이용기관 등록 완료 (공문 제출)
  □ client_id, client_secret 수령 완료
  □ instt (기관코드), srvcNo (서비스번호) 수령 완료
  □ redirect_uri ptl.anyid.go.kr 화이트리스트 등록 요청

인프라 확인
  □ HTTPS 도메인 및 TLS 인증서 준비
  □ 방화벽 아웃바운드 오픈:
      ptl.anyid.go.kr  :443 (OIDC)
      mid.anyid.go.kr  :443 (모바일 신분증)
      easysign.anyid.go.kr :443 (간편인증)
      crt.anyid.go.kr  :443 (공동/금융 인증서)
  □ Redis 클러스터 준비 (state/nonce/세션 저장)

환경변수 설정
  □ ANYID_CLIENT_ID=...
  □ ANYID_CLIENT_SECRET=...   ← Vault/KMS에서 주입
  □ ANYID_INSTT=5000000082
  □ ANYID_SRVC_NO=5000000084
  □ CI_HASH_SALT=...           ← 고정 Salt (절대 변경 금지)
```

### 10.2 개발 구현 체크리스트

```
Authorization 요청
  □ state: 32자 이상 cryptographically random (CSRF 방어)
  □ nonce: 32자 이상 cryptographically random (Replay 방어)
  □ PKCE: code_verifier (43-128자) + code_challenge (S256)
  □ state/nonce/verifier를 Redis에 저장 (TTL 10분)

Callback 처리
  □ state 값 Redis에서 1회 소비 후 삭제
  □ nonce ID Token 클레임과 비교 검증
  □ PKCE code_verifier 토큰 교환 시 포함

ID Token 검증 (6개 항목 필수)
  □ iss === "https://ptl.anyid.go.kr"
  □ aud === client_id
  □ exp > 현재 시각
  □ nonce === 저장한 nonce
  □ JWK 서명 검증 (/.well-known/jwks.json)
  □ ci 클레임 길이 === 88 바이트

CI 처리
  □ CI 원문 로그 출력 금지
  □ ci_hash = SHA-256(CI + CI_HASH_SALT)로 DB 저장
  □ CI_HASH_SALT는 Vault/KMS에서 관리
  □ 만 14세 미만 birthdate 검증 후 차단
```

### 10.3 운영 환경 주요 모니터링 지표

| 메트릭 | 정상 기준 | 알림 조건 |
|--------|---------|---------|
| Any-ID 로그인 성공률 | > 98% | < 95% 5분 지속 |
| Token 교환 응답시간 (p95) | < 1,000ms | > 3,000ms |
| state 불일치 오류 | < 0.1% | > 1% |
| CI 클레임 누락 | 0건 | 1건 이상 |
| Any-ID Health Check | HTTP 200 | HTTP non-200 |
| Redis 세션 저장 실패 | 0건 | 1건 이상 |

### 10.4 인증수단별 auth_method 값 참조표

| 인증수단 | `auth_method` 클레임 값 | `auth_level` |
|---------|----------------------|-------------|
| 모바일 신분증 | `MOBILE_ID` | `1` |
| 간편인증 — 카카오 | `EASY_SIGN` + `easy_sign_provider: KAKAO` | `2` |
| 간편인증 — 네이버 | `EASY_SIGN` + `easy_sign_provider: NAVER` | `2` |
| 간편인증 — PASS | `EASY_SIGN` + `easy_sign_provider: PASS` | `2` |
| 공동인증서 | `JOINT_CERT` | `2` |
| 금융인증서 | `FINANCIAL_CERT` | `2` |
| 민간 ID | `SOCIAL_ID` | `3` |

---

## 11. 문서 구성 안내

```
wiki/iam/
├── 00-overview.md              ← 이 파일 (전체 개요 + 아키텍처)
├── 01-mobile-id.md             ← 모바일 신분증 (mid.anyid.go.kr)
├── 02-easy-sign.md             ← 간편인증 (easysign.anyid.go.kr)
├── 03-joint-cert.md            ← 공동인증서 (MagicLine4Web)
├── 04-fin-cert.md              ← 금융인증서 (KFTC 클라우드)
├── 05-ci-dn-brokering.md       ← CI/DN 브로커링 심층 분석
├── 06-install-type-integration.md ← 설치형 연동 개발 전체 가이드
└── 07-sso-session.md           ← SSO 세션·등급 관리
```

| 문서 | 대상 독자 | 핵심 내용 |
|------|---------|---------|
| **00-overview** | 전체 | 아키텍처, 등급체계, OIDC 흐름 요약 |
| **01-mobile-id** | 개발자 | VRS v2.1 API, PUSH/QR, 지원 앱 |
| **02-easy-sign** | 개발자 | 민간 11종, 동의 항목, 입력 필드 |
| **03-joint-cert** | 개발자 | MagicLine4Web v2.2, DN 파싱 |
| **04-fin-cert** | 개발자 | KFTC 클라우드, 자동로그인 |
| **05-ci-dn-brokering** | 아키텍트/개발자 | CI 정의, DN→CI 파싱, 암호화 저장 |
| **06-install-type-integration** | 개발자/PM | 등록 절차, OIDC 설정, Spring 코드 |
| **07-sso-session** | 개발자/운영 | SSO On/Off, 세션 만료, SLO |

---

## 12. 연락처 및 참고 리소스

| 항목 | 내용 |
|------|------|
| **기술지원센터** | ☎ **1566-2670** (평일 09:00~18:00, 점심 12:00~13:00 제외) |
| **주소** | 서울 마포구 월드컵북로6길 49 4층 |
| **공식 포털** | https://www.anyid.go.kr |
| **이용기관 플랫폼** | https://ptl.anyid.go.kr |
| **개발/데모 환경** | https://demo1.anyid.go.kr |
| **행안부 소개 페이지** | https://www.mois.go.kr — 정부통합인증(Any-ID) |

> **⚠️ 중요**: 설치형 API 명세서(연동규격서), SDK, 테스트 계정은 공개 문서로 배포되지 않는다.  
> 반드시 **기술지원센터(1566-2670) 공문/협약 후 수령** 해야 한다.

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
