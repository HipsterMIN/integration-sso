# ADR-2026-004: 자체 SSO 보유 기관 연동 패턴

**상태**: Accepted  
**작성일**: 2026-05-16  
**작성자**: Integration SSO 아키텍처 팀  
**결정자**: PM, 아키텍트  
**관련 코드**: `idem-hub/src/main/java/kr/go/smes/idem-hub/domain/AgencyMeta.java` (integrationType: INTERNAL_SSO)

---

## 배경 및 문제

68개 유관기관 중 일부는 자체 SSO(Single Sign-On) 시스템을 이미 운영하고 있다.  
이 기관들을 OnePass SSO 허브에 통합할 때 다음 충돌이 발생한다:

1. **이중 SSO 문제**: 기관 내부 사용자는 이미 기관 SSO로 인증 → OnePass 추가 로그인 요구 시 UX 저하
2. **세션 동기화 문제**: OnePass 로그아웃이 기관 SSO 세션에 전파되어야 함 (SLO)
3. **사용자 매핑 문제**: OnePass mbrId ↔ 기관 내부 userId 간 매핑 필요
4. **신뢰 방향 문제**: OnePass(IdP)와 기관 SSO 중 누가 최종 IdP인가?

---

## 결정

**OnePass를 상위 IdP로, 기관 SSO를 SP(Service Provider)로 구성한다.**  
기관 SSO는 OnePass에 인증을 위임하는 OIDC Identity Provider Brokering 패턴을 사용한다.

---

## 고려한 대안

### 대안 A: 기관 SSO를 IdP로, OnePass를 SP로 (기각)

```
기관 SSO (IdP) → OnePass (SP)
```

**기각 이유**:  
- 68개 기관 각각이 IdP가 되면 OnePass는 68개 신뢰 관계 관리 필요  
- CI 기반 사용자 동기화가 불가능 (각 기관 SSO가 CI를 제공하지 않음)  
- 감리 기준: 중앙집중형 인증 허브 요건 위반

### 대안 B: 기관 SSO 완전 대체 (기각)

```
기관 SSO 폐기 → OnePass만 사용
```

**기각 이유**:  
- 기관 내부 시스템 연계 복잡도 과대  
- 기관 자율성 침해  
- 단기 실현 불가 (기관 내부 결정 필요)

### 대안 C: Bridge 서버 경유 (부분 수용)

```
기관 SSO → Bridge → OnePass
```

**부분 수용**: OIDC를 지원하지 않는 레거시 기관 SSO(SAML 2.0 전용)에만 적용.

### 대안 D (채택): Keycloak Identity Brokering

```
기관 사용자 → 기관 SSO → [OnePass/Keycloak에 OIDC 위임] → 인증 완료 → 기관 SSO
```

---

## 선택된 패턴 상세

### 패턴 D-1: OIDC 지원 기관 SSO (권장)

```
┌─────────────────────────────────────────────────────────────┐
│ 기관 내부                                                     │
│  ┌──────────┐    ①인증요청    ┌─────────────┐               │
│  │사용자 브라│ ────────────→  │ 기관 SSO    │               │
│  │우저       │                │ (OIDC SP)   │               │
│  └──────────┘                └──────┬──────┘               │
│                                     │ ②OIDC 위임            │
└─────────────────────────────────────│────────────────────────┘
                                      │
                              ③Authorization Request
                                      ↓
                         ┌────────────────────────┐
                         │  OnePass (Keycloak)    │
                         │  Q-Sign / OIDC IdP     │
                         │                        │
                         │  Identity Brokering:   │
                         │  agency-sso → OIDC     │
                         └────────────┬───────────┘
                                      │ ④사용자 인증
                                      │ (OnePass 로그인 화면 또는 기존 세션)
                                      ↓
                              [인증 완료]
                                      │ ⑤OIDC Token 발급
                                      ↓
                         ┌────────────────────────┐
                         │  기관 SSO              │
                         │  내부 세션 발급         │
                         └────────────────────────┘
```

#### Keycloak Identity Provider 설정

```json
// Keycloak Admin API: POST /admin/realms/{realm}/identity-provider/instances
{
  "alias": "{AGENCY_CODE}-sso",
  "displayName": "{기관명} SSO 연동",
  "providerId": "oidc",
  "enabled": true,
  "trustEmail": false,
  "storeToken": false,
  "addReadTokenRoleOnCreate": false,
  "firstBrokerLoginFlowAlias": "first broker login",
  "config": {
    "clientId": "onepass-broker-{AGENCY_CODE}",
    "clientSecret": "{AGENCY_SSO_CLIENT_SECRET}",
    "authorizationUrl": "https://sso.{agency}.go.kr/auth",
    "tokenUrl": "https://sso.{agency}.go.kr/token",
    "userInfoUrl": "https://sso.{agency}.go.kr/userinfo",
    "logoutUrl": "https://sso.{agency}.go.kr/logout",
    "defaultScope": "openid profile",
    "syncMode": "IMPORT",
    "validateSignature": "true",
    "useJwksUrl": "true",
    "jwksUrl": "https://sso.{agency}.go.kr/.well-known/jwks.json"
  }
}
```

#### AgencyMeta 설정

```sql
-- ido.agency_meta 테이블
INSERT INTO ido.agency_meta (
  agency_code, official_name, min_auth_level, integration_type,
  sso_domain, callback_whitelist, active
) VALUES (
  'AGENCY_A', '중소기업진흥공단', 'L1', 'INTERNAL_SSO',
  '.kotech.or.kr',  -- 기관 SSO 쿠키 도메인
  '["https://portal.kotech.or.kr/onepass/callback"]',
  true
);
```

---

### 패턴 D-2: SAML 2.0 전용 레거시 기관 SSO (Bridge 경유)

OIDC를 지원하지 않는 기관 SSO는 Keycloak의 SAML Identity Provider Mapper 활용:

```
기관 SSO (SAML 2.0 IdP)
         ↕ SAML Assertion
Keycloak SAML Identity Provider
         ↕ OIDC Token (내부 변환)
OnePass 인증 흐름
```

#### Keycloak SAML IdP 설정

```json
{
  "alias": "{AGENCY_CODE}-saml",
  "providerId": "saml",
  "config": {
    "singleSignOnServiceUrl": "https://sso.{agency}.go.kr/saml/sso",
    "singleLogoutServiceUrl": "https://sso.{agency}.go.kr/saml/slo",
    "nameIDPolicyFormat": "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent",
    "wantAuthnRequestsSigned": "true",
    "signingCertificate": "{AGENCY_SSO_SIGNING_CERT}"
  }
}
```

---

## Single Logout (SLO) 처리

### Back-channel Logout (RFC 8414 — 권장)

```
사용자 → OnePass 로그아웃
OnePass (Keycloak) → [backchannel_logout_uri] 호출 (각 기관 SSO)
기관 SSO → logout_token(JWT) 검증 → 내부 세션 종료
```

```java
// 기관 SSO Back-channel Logout Endpoint 예시 (기관 측 구현 사양)
POST /onepass/backchannel_logout
Content-Type: application/x-www-form-urlencoded

logout_token={JWT}

// logout_token Claims (Keycloak 자동 생성):
// {
//   "iss": "https://onepass.smes.go.kr/realms/smeg",
//   "sub": "{onepass-user-id}",
//   "sid": "{session-id}",
//   "events": { "http://schemas.openid.net/event/backchannel-logout": {} },
//   "exp": 1716000000
// }
```

### Front-channel Logout (OIDC RP-Initiated Logout — Fallback)

Back-channel을 지원하지 않는 기관 SSO용:

```html
<!-- Keycloak이 로그아웃 시 iframe 방식으로 기관 SSO 세션 종료 -->
<iframe src="https://sso.{agency}.go.kr/logout?redirect_uri=..."></iframe>
```

---

## AgencyMeta INTERNAL_SSO 필드 확장 권고

현재 `ssoDomain` 필드(단순 도메인 문자열)를 OIDC 메타데이터 URL로 확장 권고:

```java
// AgencyMeta.java 확장 권고 (현재: ssoDomain = ".agency.go.kr")
// 변경 후: ssoDomain = "https://sso.agency.go.kr/.well-known/openid-configuration"

/** 
 * INTERNAL_SSO 타입에서 사용.
 * OIDC Discovery URL (기관 SSO의 /.well-known/openid-configuration)
 * 또는 SSO 공유 도메인 (레거시 쿠키 기반 시 ".agency.go.kr" 형식)
 */
private final String ssoDomain;
```

**DB 마이그레이션 필요**:
```sql
-- V{next}__extend_agency_meta_sso_domain.sql
ALTER TABLE ido.agency_meta 
  ADD COLUMN IF NOT EXISTS sso_oidc_discovery_url VARCHAR(500),
  ADD COLUMN IF NOT EXISTS sso_type VARCHAR(20) DEFAULT 'COOKIE';
-- sso_type: COOKIE(레거시), OIDC(표준), SAML(레거시SAML)
```

---

## 기관 우선순위 결정 기준

| 점수 | 기준 |
|------|------|
| +3 | OIDC Back-channel Logout 지원 |
| +2 | OIDC 표준 (RFC 6749/7519) 완전 지원 |
| +2 | 사용자 규모 1만명 이상 |
| +1 | SAML 2.0 지원 (Bridge 경유 가능) |
| -1 | 자체 인증 프로토콜 사용 (연동 복잡도 상승) |
| -2 | 인메모리 세션 관리 (SLO 불가) |

---

## 결정 결과 및 영향

### 영향을 받는 컴포넌트

| 컴포넌트 | 변경 내용 |
|---------|---------|
| `AgencyMeta` (도메인) | `ssoDomain` 필드 의미 확장 |
| `AgencyMetaJpaEntity` | `sso_oidc_discovery_url`, `sso_type` 컬럼 추가 |
| Keycloak (Q-Sign) | Identity Provider 인스턴스 기관별 추가 |
| IdO Handoff 흐름 | INTERNAL_SSO 타입 기관은 Handoff 대신 Keycloak Brokering 사용 |

### 구현 제약

1. **기관 SSO 측 변경 필요**: OIDC Client 등록 (ClientID, ClientSecret, RedirectURI 제공)
2. **네트워크 방화벽**: OnePass → 기관 SSO 간 HTTPS 443 포트 개방 필요
3. **인증서**: 기관 SSO 서버 인증서 유효성 확인 필요

---

## 참조

- RFC 6749: The OAuth 2.0 Authorization Framework
- RFC 8414: OAuth 2.0 Authorization Server Metadata
- OpenID Connect Back-Channel Logout 1.0
- Keycloak Identity Brokering 공식 문서
- `idem-hub/src/main/java/kr/go/smes/idem-hub/domain/AgencyMeta.java` (integrationType 필드)
- `docs/internal/architecture/oidc-brokering-design.md`
