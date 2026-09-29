# Idem 자체 SSO 기관 연동 — 개발자 레퍼런스

> **명칭 안내** — 제품명은 **Idem**(구 OnePass·원패스, 2026-09-04 개명)이다. API 경로·오류 코드(`E-IDO-1xx`, `E-AGENCY-3xx`, `/api/v1/admin/agencies` 등)는 1.0 에서 **동결**됐고 개명은 2.0 이다(`CHANGELOG.md` [1.0.0]). 대응표: [docs/naming.md](naming.md) §3.

> **버전**: v2.0 (2026-09-27, Idem 1.0.1 기준 전면 재작성). v1.0(2026-05-17)의 회원 조회·매핑 API(`/api/v1/members/lookup`·`/link`), `ci_hash`·`qim_user_id` 컬럼, `identifierHash`(SHA-256(CI)) 기반 회원 전환은 **0.x 설계**이며 범용화 S4b(2026-09-10)에서 제거됐다. 이 문서에는 더 이상 없다.
> **대상 독자**: 연동기관 백엔드 개발자, 운영기관의 연동 담당 개발자
> **관련 코드**: 참조 기관 앱 `idem-tenant-sample`(표준 RP + Handoff 수신), `idem-sdk-java`(Java 8+, 런타임 의존성 0)

---

## 목차

1. [개요 및 아키텍처](#1-개요-및-아키텍처)
2. [핵심 개념](#2-핵심-개념)
3. [연동 방식 선택](#3-연동-방식-선택)
4. [기관이 받는 값과 준비할 것](#4-기관이-받는-값과-준비할-것)
5. [옵션 C — 표준 OIDC(OIDC_RP) 구현](#5-옵션-c--표준-oidcoidc_rp-구현)
6. [옵션 A·B — Handoff 티켓 구현](#6-옵션-ab--handoff-티켓-구현)
7. [기존 계정 연결 — agencySubjectId](#7-기존-계정-연결--agencysubjectid)
8. [상태 변경 수신 — 웹훅·이벤트 피드·Back-Channel Logout](#8-상태-변경-수신--웹훅이벤트-피드back-channel-logout)
9. [Java SDK 의 역할](#9-java-sdk-의-역할)
10. [Java Agent — 1.0 에서의 상태](#10-java-agent--10-에서의-상태)
11. [오류 처리 원칙](#11-오류-처리-원칙)
12. [테스트 환경](#12-테스트-환경)
13. [API 레퍼런스 요약](#13-api-레퍼런스-요약)

---

## 1. 개요 및 아키텍처

### 1.1 1.0 연동 전체 흐름

Idem 은 기관 회원 DB 를 **조회하거나 등록하지 않는다**. 기관이 Idem 에서 받는 것은 인증 결과 하나(어설션)뿐이고, 기관은 그 안의 **기관별 식별자**(`agencySubjectId`)로 자기 계정을 찾거나 만든다.

```
┌──────────────────────────────────────────────────────────────────────┐
│                           사용자 브라우저                              │
└──────────┬───────────────────────────────────────────┬───────────────┘
           │ ① "Idem 으로 로그인"                        │ ⑤ 기관 세션 완료
           ▼                                           ▲
┌────────────────────────────┐   ④ 어설션            ┌─────────────────────────────┐
│  Idem SSO                  │──────────────────────▶│  기관 애플리케이션            │
│  idem-gate  (:8081, 공개)   │  · 옵션 C: id_token · userinfo(idem_*)             │
│    /realms/idem OIDC 프런트 │  · 옵션 A: POST /api/v1/handoff/verify 응답        │
│    (Keycloak 은 뒤에 숨김)  │                       │  · agencySubjectId 로 계정 매칭│
│  idem-hub   (:8083, 공개)   │◀──────────────────────│  · 자체 세션 생성            │
│    ② 본인확인·정책 판정      │  ③ 토큰 교환 / 티켓 검증│  · (선택) 웹훅·이벤트 수신    │
│    ③ 할당·인증수준·한도      │                       └─────────────────────────────┘
└──────────┬─────────────────┘
           │ 내부 API
┌──────────▼─────────────────┐
│  Idem IM  registry · authz  │   동일인 식별 · 기관별 가명 ID · 역할·할당
└────────────────────────────┘
```

기관이 열어야 하는 인바운드 엔드포인트는 **선택 사항**뿐이다: 웹훅 수신, Back-Channel Logout 수신, (BRIDGE·APACHE_GATE·INTERNAL_SSO 유형의) 티켓 푸시 수신.

### 1.2 컴포넌트와 포트

| 컴포넌트 | 기관이 보는 주소 | 용도 |
|---|---|---|
| idem-gate | `{IDEM_PUBLIC_URL_GATE}` (443) | 표준 OIDC 발급자(issuer `{gate}/realms/idem`), 로그인 화면, Back-Channel Logout 송신 |
| idem-hub | `{IDEM_PUBLIC_URL_HUB}` (443) | Handoff verify, CAST, 이벤트 피드, 게이트웨이 API, 웹훅 송신 |
| Keycloak | 없음 | gate 뒤에 숨김. 기관은 접근하지 않는다 |
| 관리 콘솔 | 운영기관 전용 | 기관 프로파일·client secret·API 키 관리 |

---

## 2. 핵심 개념

| 개념 | 설명 |
|---|---|
| **서비스 프로파일** | 기관 하나 = JSON 문서 하나. 프로토콜(`protocol.type`), 식별자 스킴, 정책(인증수준·세션·할당), 한도가 여기 있다. 운영기관이 관리 API `PUT /api/v1/admin/services/{code}/profile` 로 저장한다(`docs/onboarding-guide.md`) |
| **`agencySubjectId`** | 기관별 식별자. 기본 스킴 `PAIRWISE_HMAC` — 같은 사람이라도 기관마다 값이 다르고, 두 기관이 대조해도 결합되지 않는다. 기관 DB 의 연결 키다(§7). 스킴은 프로파일 `identity.subjectScheme`(PAIRWISE_HMAC·PLATFORM_ID·CI·EMAIL·PHONE·EXTERNAL_SUB) |
| **어설션** | 옵션 C 에서는 id_token·userinfo 의 `idem_*` 클레임, 옵션 A 에서는 `HandoffPayload`. 어휘는 같다: 상태·식별자·속성·역할·인증수준·세션 정책 |
| **상태(state)** | `APPROVED` 허용 · `GUEST` 인증은 됐으나 이 서비스에 미할당(프로파일이 `policy.assignment.selfSignup` 을 허용할 때만) · `HOLD` Idem 쪽 일시 오류(재시도) · `REJECTED` 거부 · `MANUAL_REVIEW` |
| **인증수준** | `L1`(ID/PW·소셜) · `L2`(휴대폰 본인확인 등) · `L3`(전자서명). 프로파일 `policy.minAuthLevel` 미달이면 발급 단계에서 거부 |
| **역할·할당** | `idem-authz` 가 정본. 어설션의 `roles`(옵션 C 는 `idem_roles`)와 `assigned` 는 굵은 RBAC 이며 세밀한 권한 집행은 기관 몫 |
| **세션 정책** | 프로파일 `policy.session`(유휴·절대·동시). 어설션의 `sessionPolicy` 로 전달되며 기관 세션에도 같은 상한을 적용하라는 계약이다 |
| **CI** | 연계정보는 Idem 회원원장 밖으로 나가지 않는다. 기관은 CI 를 받지도, 보관할 필요도 없다 |

---

## 3. 연동 방식 선택

| 방식 | 프로파일 `protocol.type` | 기관이 구현하는 것 | 권장 |
|---|---|---|---|
| **옵션 C 표준 OIDC** | `OIDC_RP` | OIDC 라이브러리 설정, 콜백에서 `idem_subject` 로 계정 매칭, (선택) BCL 수신 | **기본 권장**. 표준 라이브러리만으로 끝난다 |
| **옵션 A Handoff DIRECT** | `DIRECT` | 콜백에서 `ticketId` 받아 `POST /api/v1/handoff/verify` 호출, 세션 생성 | 자체 SSO 와 병행, 완전 분리 |
| **옵션 A Handoff BRIDGE** | `BRIDGE` | Idem 이 티켓을 푸시하는 브리지 엔드포인트(`{bridge}/api/handoff/push`) | 기관 코드 수정 최소화 |
| **옵션 A Handoff APACHE_GATE** | `APACHE_GATE` | 웹서버 단에서 헤더(`X-Remote-User` 등)를 받는 게이트 엔드포인트 | 앱 수정 없이 |
| **옵션 B Handoff INTERNAL_SSO** | `INTERNAL_SSO` | 기관 SSO 의 `{ssoDomain}/internal/sso-session` 수신 → 기관 SSO 세션 발급 | Idem 인증 후 기관 SSO 위임 |
| SAML SP | — | — | 1.0 미포함(설계만, `docs/saml-sp-design.md`). OIDC 브리지로 |

`docs/sso-agency-integration-guide.md` §3 의 옵션 A·B·C 와 같은 구분이다.

---

## 4. 기관이 받는 값과 준비할 것

### 4.1 운영기관에서 받는 값

| 값 | 옵션 | 발급처 | 비고 |
|---|---|---|---|
| 기관 코드 (`X-Agency-Code`) | 전부 | 프로파일 `service.code` | 대문자·숫자·밑줄 |
| OIDC issuer · client_id · client_secret | C | 프로파일 저장 시 client `idem-svc-{code}` 자동 생성, secret 은 `POST …/{code}/oidc-client/secret` 회전 응답에 **한 번만** 노출 | Discovery `{issuer}/.well-known/openid-configuration` |
| 기관 API 키 (`X-Agency-Key`) | A·B, 이벤트 피드, SDK | `POST /api/v1/admin/agencies/{code}/rotate-key` 응답에 **한 번만** 노출(서버는 해시만 저장) | Handoff verify·issue, `/api/v1/agency/**` 전부에 필요 |
| 웹훅 서명 비밀 | 웹훅 수신 시 | 운영기관 | `X-Webhook-Signature` 검증용 |
| HMAC 서명 비밀 (`X-Internal-Sig`) | 게이트웨이 API 사용 시 | 운영기관 | `IDEM_HUB_HMAC_SIG_REQUIRED=true` 인 설치본에서만 필수 |
| CAST 공개키 | 기관 간 SSO 상대만 | `GET /api/v1/agency/cast/public-key` | Ed25519 |

### 4.2 기관이 운영기관에 알려 줄 값

| 값 | 옵션 | 프로파일 위치 |
|---|---|---|
| 콜백/리다이렉트 URI | C | `protocol.oidc.redirectUris`, `postLogoutRedirectUris` |
| Back-Channel Logout URI | C | `protocol.oidc.backchannelLogoutUri` — **공개 http(s) 호스트만** 허용(내부 IP·localhost 거부) |
| 콜백 화이트리스트 | A | `protocol.endpoints.callbackWhitelist` — 여기 없는 `returnUrl` 은 400 `INVALID_RETURN_URL` / `E-AGENCY-304` |
| 브리지·아파치게이트·SSO 진입 | A·B | `protocol.endpoints.bridge` · `apacheGate` · `ssoDomain` · `ssoEntry` |
| 웹훅 수신 URL | 선택 | 기관 등록 API `webhookEndpoint`(`POST/PUT /api/v1/admin/agencies`) |
| 필요한 속성 | 전부 | `identity.attributes[]`(이메일·마스킹 성명 등). 요청하지 않은 속성은 오지 않는다 |
| 허용 IP·mTLS | 선택 | `protocol.security.ipAllowlist`, `mtlsRequired` |

### 4.3 개발팀 체크리스트

```
필수:
□ 계정 연결 키 컬럼 추가 — idem_subject_id VARCHAR(128), 인덱스, (agency_code 가 여럿이면 복합 유니크)  §7
□ 로그인 완료 처리 — 옵션 C: OIDC 콜백 / 옵션 A: ticketId → verify      §5·§6
□ 상태별 분기 — APPROVED / GUEST / HOLD / REJECTED                       §6.4
□ 세션 정책 반영 — sessionPolicy 의 유휴·절대·동시 상한                   §6.5

선택:
□ 웹훅 수신 (탈퇴·로그아웃·티켓 취소 반영)                                §8.1
□ 이벤트 피드 폴링 (웹훅 대신 또는 보완)                                   §8.2
□ Back-Channel Logout 수신 (옵션 C)                                       §8.3
□ SDK 로 연동 상태 조회                                                   §9
```

---

## 5. 옵션 C — 표준 OIDC(OIDC_RP) 구현

### 5.1 규격

| 항목 | 값 |
|---|---|
| issuer | `{IDEM_PUBLIC_URL_GATE}/realms/idem` |
| 흐름 | Authorization Code + **PKCE S256 필수**. implicit·password·device 없음 |
| client 인증 | `client_secret_basic`(기본) 또는 `client_secret_post` (프로파일 `protocol.oidc.clientAuthMethod`) |
| 서명 | RS256, JWKS 는 Discovery 의 `jwks_uri` |
| id_token·access_token 클레임 | `identity_provider`(본인확인 경로), `acr`(인증수준 L1~L3), `idem_service` |
| userinfo 클레임 | `idem_state`(APPROVED·GUEST), `idem_subject`(= agencySubjectId), `idem_subject_scheme`, `idem_roles`, `idem_assigned`, `idem_user_id`, 프로파일이 허용한 속성 |
| 정책 판정 시점 | **토큰 교환**. 거부면 `403 access_denied` 로 끝나고 토큰은 남지 않는다. `error_description` 첫 토큰이 사유 코드(`E-IDO-120` 미할당, `E-AGENCY-305` 점검 등) |
| 한도 | 프로파일 `limits.tps/daily` 초과 시 토큰 교환 `429 temporarily_unavailable` + `Retry-After` |
| 로그아웃 | RP-Initiated(`end_session_endpoint`, `id_token_hint` 필수) + Back-Channel Logout(`logout_token` POST, `sid`) |

### 5.2 Spring Security 설정 예

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          idem:
            client-id: idem-svc-AGENCY_001
            client-secret: ${IDEM_OIDC_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/idem"
            scope: openid
            client-authentication-method: client_secret_basic
        provider:
          idem:
            issuer-uri: https://idem-gate.example.go.kr/realms/idem   # Discovery 로 나머지 자동
```

```java
@Configuration
public class IdemOidcSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AgencyAccountLinker linker) throws Exception {
        http.oauth2Login(o -> o
                // PKCE S256 — Spring Security 6.x 는 public/confidential 모두 PKCE 를 붙일 수 있다
                .authorizationEndpoint(a -> a.authorizationRequestResolver(pkceResolver(http)))
                .userInfoEndpoint(u -> u.oidcUserService(linker))      // userinfo 의 idem_* 로 계정 매칭
                .failureHandler((req, res, ex) -> {
                    // 토큰 교환 403 access_denied → ex.getMessage() 에 "E-IDO-120 …" 같은 사유가 온다
                    res.sendRedirect("/login?idem_error=" + URLEncoder.encode(ex.getMessage(), UTF_8));
                }))
            .logout(l -> l.logoutSuccessHandler(oidcLogoutHandler()));   // RP-Initiated Logout
        return http.build();
    }
}
```

`AgencyAccountLinker` 는 `OidcUserService` 를 감싸 `idem_state` 가 `GUEST` 면 가입·연결 화면으로, `APPROVED` 면 `idem_subject` 로 기관 계정을 찾아 principal 을 만든다(§7). 사용자 상태·할당이 나중에 바뀌면 다음 userinfo 호출이 403 이 되므로, 장기 세션은 주기적으로 userinfo 를 다시 부르거나 Back-Channel Logout(§8.3)을 받는다.

### 5.3 참조 구현

`idem-tenant-sample` 의 `idem.sample.protocol=OIDC_RP` 경로(`/agency/oidc/login·callback·logout·backchannel-logout`, `OidcRelyingPartyClient`)가 라이브러리 없이 필요한 검증 전부(state·nonce·PKCE·iss·aud·exp·JWKS·sid)를 보여 준다. 운영기관 설치 확인 절차는 `docs/install.md` §5.1.

---

## 6. 옵션 A·B — Handoff 티켓 구현

### 6.1 흐름

```
사용자 브라우저
  │ ① 기관 화면 "Idem 으로 로그인" → Idem 로그인 화면 (returnUrl = 기관 콜백, callbackWhitelist 안이어야 함)
  ▼
Idem (gate 로그인 → hub 정책 판정 → 티켓 발급)
  │ ② 발급된 티켓을 프로파일 유형대로 전달
  │    DIRECT      : 브라우저가 콜백으로 복귀, ticketId 전달 → 기관이 verify
  │    BRIDGE      : hub → POST {bridge}/api/handoff/push  (X-Agency-Code, X-Handoff-Signature)
  │    APACHE_GATE : hub → POST {apacheGate}  (X-Remote-User, X-Auth-Level, X-Handoff-Token, X-Session-Expiry, X-Agency-Code)
  │    INTERNAL_SSO: hub → POST {ssoDomain}/internal/sso-session  (X-Agency-Code, X-Correlation-Id, X-Source-System)
  ▼
기관 서버
  │ ③ POST {hub}/api/v1/handoff/verify   — 서버 간, X-Agency-Code + X-Agency-Key
  │ ④ HandoffPayload 수신 → state 분기 → agencySubjectId 로 계정 매칭 → 세션 생성
  ▼
사용자 브라우저 (로그인 완료)
```

- 티켓은 **60초·1회 소비**, AES-256-GCM 암호화 + HMAC 서명이다. verify 가 성공하면 즉시 소비되고 두 번째 verify 는 `409 E-IDO-102` 다.
- **브라우저 진입 (1.1 코어 로그인 프런트)**: 기관 화면의 "Idem 으로 로그인" 은 아래 URL 로 보낸다. Idem 이 로그인·발급을 모두 처리하고 브라우저를 기관 콜백으로 되돌린다. 기관은 **verify 만** 하면 된다(기관 API 키는 브라우저 어디에도 없다).

  ```
  GET {hub}/api/v1/handoff/login?service={기관코드}&callback={콜백 URL, callbackWhitelist 안}&state={CSRF 용 불투명 값}
                                [&provider={인증 제공자 코드}][&level=L1|L2|L3]
  → 성공: 302 {callback}?ticketId=…&state=…          → 기관 서버가 verify (§6.2)
  → 정책 거부: 302 {callback}?error=E-IDO-120&error_description=…&state=…   (미할당·상태·점검 등 — §6.4 와 같은 코드)
  → 기관·콜백 자체가 잘못됐으면 Idem 오류 화면(403/404/400) — 콜백으로 되돌리지 않는다
  ```
  `state` 는 기관이 세션·쿠키에 둔 값과 콜백에서 비교한다(참조 구현 `idem-tenant-sample` `/agency/login` → `/agency/callback`). 제공자를 지정하지 않으면 Idem 이 하나면 자동, 여럿이면 선택 화면을 보인다. 운영기관이 자기 로그인 화면을 쓰고 싶으면 같은 URL 계약을 자기 프런트에서 제공하면 된다.
- 발급 API(`POST /api/v1/handoff/issue`)는 운영기관 프런트가 직접 쓰는 경로로 남아 있다 — Idem 로그인 세션 쿠키(`feSessionId`)와 기관 API 키를 **함께** 요구하고, `X-Agency-Code` 와 본문 `agencyCode` 는 같아야 한다(다르면 `403 E-AGENCY-302`). (1.1 정정: 1.0.1 까지 이 API 는 `Fe-Session-Id` 라는 어느 쪽도 발급하지 않는 쿠키 이름을 읽어 브라우저에서 항상 `E-IDO-107` 이었고, "시뮬레이터·D-10 으로 브라우저 경로가 검증돼 있다" 는 종전 서술은 부정확했다 — 시뮬레이터는 서버 간 발급, D-10 은 API 검증이다. 브라우저 경로는 1.1 부터 시험 항목 D-16 과 설치본 스모크 ⑦b 로 검증된다.)

### 6.2 verify 요청

```
POST {IDEM_PUBLIC_URL_HUB}/api/v1/handoff/verify
Content-Type: application/json
X-Agency-Code: AGENCY_001
X-Agency-Key: {기관 API 키}
X-Correlation-Id: {추적 ID, 선택 — 없으면 서버가 생성}

{"ticketId": "…"}
```

### 6.3 verify 응답 — HandoffPayload

```json
{
  "ticketId": "…",
  "correlationId": "…",
  "agencyCode": "AGENCY_001",
  "policyVersion": "v3",
  "state": "APPROVED",
  "subject": {
    "agencySubjectId": "pw_9f3a…",        // 기관별 식별자 — 계정 연결 키 (GUEST 는 null 일 수 있음)
    "subjectScheme": "PAIRWISE_HMAC",
    "qimUserId": "550e8400-…",           // Idem 내부 ID — 저장하지 말 것 (연결 키 아님)
    "status": "ACTIVE",
    "assigned": true                      // authz 없는 설치는 null
  },
  "authContext": { "authLevel": "L2", "providerCode": "NICE_PHONE", "authenticatedAt": "…", "authResultId": "…" },
  "attributes": { "email": "user@x.org", "nameMasked": "홍*동" },   // 프로파일 identity.attributes 로 허용한 것만
  "roles": ["MEMBER"],                   // 이 서비스 범위의 앱 역할 (없으면 [])
  "sessionPolicy": { "idleMinutes": 30, "absoluteMinutes": 480, "concurrent": 1 },   // 프로파일에 없으면 없음
  "issuedAt": "…",
  "expiresAt": "…"
}
```

### 6.4 상태 분기

| `state` | 뜻 | 기관 처리 |
|---|---|---|
| `APPROVED` | 정책 통과 | `agencySubjectId` 로 계정 매칭 → 세션 생성 |
| `GUEST` | 인증은 됐으나 이 서비스에 **미할당**(프로파일 `policy.assignment.selfSignup=true` 일 때만 나옴) | 가입·계정 연결 화면으로. 식별자가 실려 오면 가입 완료 뒤 같은 값으로 연결 |
| `HOLD` | Idem 쪽 일시 오류 | 잠시 뒤 재시도 안내. 티켓은 소비되지 않았을 수 있으나 60초 안에 끝내야 한다 |
| `REJECTED` · `MANUAL_REVIEW` | 거부 | 로그인 실패 화면. `correlationId` 를 표시해 문의에 쓰게 한다 |

### 6.5 기관 서버 구현 예 (Java, 라이브러리 없이)

```java
@GetMapping("/idem/callback")
public String callback(@RequestParam String ticketId, HttpServletRequest req, HttpServletResponse res) throws Exception {
    String cid = UUID.randomUUID().toString();
    HttpURLConnection c = (HttpURLConnection) new URL(hubBaseUrl + "/api/v1/handoff/verify").openConnection();
    c.setRequestMethod("POST");
    c.setRequestProperty("Content-Type", "application/json");
    c.setRequestProperty("X-Agency-Code", agencyCode);
    c.setRequestProperty("X-Agency-Key", apiKey);          // 로그에 남기지 말 것
    c.setRequestProperty("X-Correlation-Id", cid);
    c.setConnectTimeout(3_000); c.setReadTimeout(5_000);
    c.setDoOutput(true);
    try (OutputStream os = c.getOutputStream()) { os.write(("{\"ticketId\":\"" + ticketId + "\"}").getBytes(UTF_8)); }

    int status = c.getResponseCode();
    if (status == 409 || status == 410) return "redirect:/login?error=ticket";        // E-IDO-102 소비됨 / 101·103 만료·취소
    if (status == 401) throw new IllegalStateException("기관 API 키 거부 — 운영 확인");  // INVALID_AGENCY_CREDENTIALS / E-IDO-108
    if (status == 429) return "redirect:/login?error=busy";                           // E-AGENCY-306 한도
    if (status >= 500) return "redirect:/login?error=idem_unavailable";

    HandoffPayload p = objectMapper.readValue(c.getInputStream(), HandoffPayload.class);
    switch (p.state()) {
        case "APPROVED" -> {
            AgencyUser u = accountLinker.findOrLink(p.subject().agencySubjectId(), p.attributes());   // §7
            agencySession.create(u, p.sessionPolicy(), p.authContext().authLevel(), cid);            // 유휴·절대·동시 상한 적용
            return "redirect:/main";
        }
        case "GUEST" -> { req.getSession().setAttribute("idemGuest", p.subject().agencySubjectId()); return "redirect:/signup/link"; }
        case "HOLD"  -> { return "redirect:/login?error=retry&cid=" + cid; }
        default      -> { return "redirect:/login?error=denied&cid=" + cid; }
    }
}
```

`idem-tenant-sample` 의 `AgencyEntryController` + `IdoVerifyClient`(Resilience4j 서킷브레이커 `ido-verify`: 실패율 50% → 10초 OPEN → HOLD 처리)가 같은 흐름의 참조 구현이다.

### 6.6 세션 정책

`sessionPolicy` 가 오면 기관 세션도 같은 상한을 지킨다. 참조 구현 기본값은 유휴 30분·절대 8시간, 쿠키 `HttpOnly·Secure·SameSite=Strict`. Idem 쪽 로그인 세션에도 같은 값이 적용된다.

### 6.7 CAST — 기관 간 이동

A 기관에 로그인한 사용자를 B 기관으로 재로그인 없이 보낼 때. Idem 로그인 세션(브라우저)에서 `POST /api/v1/agency/cast/issue?targetAgency=B` → 응답의 `formHtml`(castToken 을 hidden 필드로 POST 자동 제출)로 B 의 `ssoEntry` 에 전달 → B 서버가 `POST /api/v1/agency/cast/verify {castToken, targetAgencyCode}` → 응답의 `handoffTicket` 을 §6.2 대로 verify. Ed25519 서명, 만료 5분, jti 원자 소비. 공개키는 `GET /api/v1/agency/cast/public-key`.

---

## 7. 기존 계정 연결 — agencySubjectId

### 7.1 원칙

연결은 **기관이, 첫 로그인 때** 한다. Idem 은 기관 DB 를 모른다.

```
첫 로그인:
  Idem → 기관: agencySubjectId = "pw_9f3a…", attributes = { email: … }
  기관:  idem_subject_id = "pw_9f3a…" 인 계정이 있나?
           ├─ 있음 → 로그인
           └─ 없음 → 연결 기준(이메일·사번 등 고유 속성)으로 기존 계정 탐색
                      ├─ 찾음 → idem_subject_id 기록 (자동 연결)
                      └─ 못 찾음 → 기존 아이디/비밀번호 1회 입력(수동 연결) 또는 신규 계정
이후 로그인:
  idem_subject_id 로 즉시 매칭
```

### 7.2 스키마

```sql
ALTER TABLE {기관_회원_테이블}
    ADD COLUMN idem_subject_id VARCHAR(128) NULL;          -- Idem 이 준 기관별 식별자
CREATE UNIQUE INDEX uk_member_idem_subject ON {기관_회원_테이블} (idem_subject_id);
-- 기관 코드가 여럿인 시스템이면 (agency_code, idem_subject_id) 복합 유니크
```

`qimUserId` 는 Idem 내부 ID 라 **저장하지 않는다**. 프로파일 `identity.subjectScheme` 을 바꾸면 값이 달라지므로 운영 중 스킴 변경은 재연결을 뜻한다.

### 7.3 연결 로직 예

```java
@Transactional
public AgencyUser findOrLink(String subjectId, Map<String, Object> attrs) {
    return repo.findByIdemSubjectId(subjectId).orElseGet(() -> {
        Optional<AgencyUser> byEmail = Optional.ofNullable((String) attrs.get("email")).flatMap(repo::findByEmail);
        if (byEmail.isPresent() && byEmail.get().getIdemSubjectId() == null) {     // 이미 다른 subject 에 연결된 계정은 덮어쓰지 않는다
            byEmail.get().setIdemSubjectId(subjectId);
            return byEmail.get();
        }
        throw new NeedsManualLink(subjectId);   // 수동 연결 화면으로
    });
}
```

- 멱등: 같은 사용자의 재로그인은 같은 계정이어야 한다. 이미 `idem_subject_id` 가 있는 계정에 다른 값을 쓰지 않는다.
- 해제: `idem_subject_id` 를 NULL 로. 다음 Idem 로그인 때 §7.1 이 다시 돈다.
- 일회성 이관: 기존 회원이 많으면 운영기관의 `scripts/kr-member-import`(KR 에디션) 또는 오프라인 매핑으로 사전 연결.

---

## 8. 상태 변경 수신 — 웹훅·이벤트 피드·Back-Channel Logout

Idem 은 로그아웃·탈퇴·티켓 취소 같은 변화를 기관에 **밀어 주거나(웹훅) 가져가게(이벤트 피드)** 한다. Kafka 는 필요 없다(hub 가 아웃박스를 DB 폴링으로 처리).

### 8.1 웹훅 (Idem → 기관)

운영기관이 기관 등록에 `webhookEndpoint` 를 넣으면 hub 가 HTTPS POST 한다.

```
POST {webhookEndpoint}
Content-Type: application/json
X-Webhook-Signature: sha256={HEX(HmacSHA256(timestamp + "." + body, signingSecret))}
X-Webhook-Timestamp: {epoch seconds}
X-Correlation-Id: …
X-Source-System: idem-hub
X-Platform-Version: 1.0.1

{"eventId":"…","eventType":"HANDOFF_REVOKED","agencyCode":"AGENCY_001","ticketId":"…","ticketState":"REVOKED",
 "revokeReason":"ADMIN","correlationId":"…","occurredAt":"…","platformVersion":"1.0.1","sourceSystem":"idem-hub"}
```

검증 순서: ① `|now - X-Webhook-Timestamp| ≤ 300초` ② `expected = "sha256=" + HEX(HmacSHA256(timestamp + "." + rawBody, secret))` 를 상수 시간 비교 ③ `eventId` 로 중복 제거 ④ 200 응답. 실패는 hub 가 재시도한다. 페이로드에는 `qimUserId` 원본이 없고 개인정보는 마스킹돼 있다. 참조 구현: `idem-tenant-sample` `WebhookInboundController`(`POST /api/v1/webhook/inbound`).

이벤트 유형: `HANDOFF_ISSUED` · `HANDOFF_REVOKED` · `USER_LOGOUT` · `MEMBER_WITHDRAWN` · **`ASSIGNMENT_CHANGED`**(1.1). (`MEMBER_LOOKUP_RESULT` 는 0.x 잔재로 1.0 에서 발생하지 않는다.)

`ASSIGNMENT_CHANGED`(1.1) — 이 기관에 대한 사용자의 할당·역할이 바뀌었을 때. `change` 는 `ASSIGNED` · `UNASSIGNED` · `ASSIGNMENT_EXPIRED` · `ROLE_GRANTED` · `ROLE_REVOKED` · `ROLE_EXPIRED`, 역할 변경이면 `roleCode` 가 함께 온다. `ASSIGNED` 는 운영기관의 직접 할당뿐 아니라 규칙 할당(1.1 PR-2, 예: "인증수준 L2 이상이면 이 서비스 사용자") 이 로그인 시점에 실체화될 때도 온다 — 기관 처리는 같다. 기관은 `agencySubjectId` 로 자기 계정을 찾아 `UNASSIGNED`·`ASSIGNMENT_EXPIRED`·`ROLE_REVOKED` 면 그 사용자의 기관 세션을 끊거나 권한을 낮춘다(Idem 은 발급 시점에만 판정하므로 이미 만든 기관 세션은 기관이 끝내야 한다). 옵션 C 기관은 다음 userinfo 호출이 403 이 되므로 이 웹훅은 보완 수단이다.

```json
{"eventId":"…","eventType":"ASSIGNMENT_CHANGED","agencyCode":"AGENCY_001","agencySubjectId":"pw_9f3a…",
 "change":"UNASSIGNED","occurredAt":"…","correlationId":"…","platformVersion":"1.0.1","sourceSystem":"idem-hub"}
```

### 8.2 이벤트 피드 (기관 → Idem, 폴링)

웹훅을 열 수 없는 기관은 가져간다.

```
GET {hub}/api/v1/agency/events?limit=20&since=2026-09-27T00:00:00Z&eventType=USER_LOGOUT
X-Agency-Code · X-Agency-Key · X-Correlation-Id(선택)
→ { "events":[{ "dispatchId":"…","eventType":"…","agencyCode":"…","payload":{…},"status":"PENDING" }],
    "count":1, "hasMore":false, "polledAt":"…" }

POST {hub}/api/v1/agency/events/{dispatchId}/read      — 처리 완료 표시
```

`polledAt` 을 다음 `since` 로 쓰고, `hasMore=true` 면 이어서 폴링한다. 참조: `AgencyEventPollingController`(`/api/v1/events/poll`).

### 8.3 Back-Channel Logout (옵션 C)

프로파일 `protocol.oidc.backchannelLogoutUri` 로 `logout_token`(JWT, `application/x-www-form-urlencoded`)이 POST 된다. 검증: 서명(JWKS)·`iss`·`aud`(client_id)·`iat`·`jti` 1회·`events` 클레임, 그리고 `sid` 로 기관 세션을 끊는다. Idem 쪽 세션 종료(관리자·다른 서비스의 로그아웃)도 같은 경로로 온다. 참조: `OidcLoginController.backchannelLogout`.

---

## 9. Java SDK 의 역할

`idem-sdk-java`(Maven `io.github.hipstermin.idem:idem-sdk-java:1.0.1`, Java 8+, 런타임 의존성 0)는 **게이트웨이 API 클라이언트**다. Handoff verify 는 들어 있지 않다(§6.5 처럼 직접 호출).

| 메서드 | 경로 | 1.0.1 기본 설치본에서 |
|---|---|---|
| `getStatus(agencyCode)` | `GET /api/v1/agency/gateway/status/{code}` | 동작. 연동 상태(활성 여부·마지막 수신 시각) 조회 |
| `sendInbound(event)` | `POST /api/v1/agency/gateway/inbound/event` | `IDEM_HUB_GATEWAY_INBOUND_ENABLED=false`(기본)면 `503 FEATURE_DISABLED` |
| `triggerOutbound(req)` (`@Deprecated`) | `PATCH /api/v1/agency/gateway/outbound/notify` | `IDEM_HUB_GATEWAY_OUTBOUND_ENABLED=false`(기본)면 `503 FEATURE_DISABLED` |

HMAC 서명(`signRequests(true)`)은 `X-Internal-Sig = HMAC-SHA256("{agencyCode}:{idempotencyKey}:{epochSeconds}")`, 서버 허용 오차 ±60초. `IDEM_HUB_HMAC_SIG_REQUIRED=true` 인 설치본에서만 필수다. 사용법은 `docs/idem-sdk-java-usage-guide.md`, README 는 `idem-sdk-java/README.md`.

---

## 10. Java Agent — 1.0 에서의 상태

`idem-agent`(`-javaagent`)는 요청의 `Authorization: Bearer` 토큰을 `POST {endpoint}/api/v1/agency/token/verify` 로 검증하는 구조인데, **이 엔드포인트를 제공하는 서버가 Idem 1.0.x 에 없다**(hub·gate 어디에도 없고 저장소 이력에도 없다). 테스트베드(`idem-agent-testbed`)는 `mock-onepass-server` 로만 검증돼 있다. 따라서 1.0 에서 에이전트는 **운영 연동 수단이 아니다**. 레거시 WAS 는 옵션 A(콜백 서블릿 하나 + verify 호출) 또는 옵션 C 로 붙인다. 에이전트가 Idem 1.0 어설션을 검증하려면 hub 쪽 검증 API 와 브라우저 토큰 발급 경로가 함께 필요하며 이는 1.x 과제다(`docs/idem-agent-integration-guide.md` 머리 안내).

---

## 11. 오류 처리 원칙

- **Idem 은 fail-closed** 다. 의존 장애 시 허용이 아니라 거부(`HOLD`·5xx)한다. 기관은 그때 자체 로그인으로 우회하도록 화면을 설계한다. "토큰이 없으면 통과" 같은 fail-open 은 1.0 어디에도 없다.
- **재시도**: verify 5xx·타임아웃은 짧게 재시도하되 티켓 60초를 넘기지 않는다. 서킷브레이커를 두면 Idem 장애 때 기관 서비스가 느려지지 않는다.
- **상관관계 ID**: 모든 호출에 `X-Correlation-Id` 를 넣고 사용자에게 보이는 오류 화면에도 표시한다. 운영기관 감사 조회의 키다.

| HTTP | 코드 | 뜻 | 기관 처리 |
|---|---|---|---|
| 401 | `MISSING_AGENCY_CREDENTIALS` · `INVALID_AGENCY_CREDENTIALS` | 헤더 누락 · API 키 불일치/기관 비활성 | 설정·키 회전 확인 (운영 가이드 §3) |
| 401 | `E-IDO-108` | 티켓 서명 검증 실패 | 재로그인 |
| 403 | `E-AGENCY-301` · `302` · `304` | 미등록 기관 · 코드 불일치 · 콜백 미허용 | 프로파일 확인 |
| 403 | `access_denied` + `E-IDO-120` (OIDC) | 미할당 | 가입·할당 안내 |
| 404 | `E-AGENCY-307` | 기관 없음/비활성 | 운영기관 확인 |
| 409 | `E-IDO-102` | 티켓 이미 소비 | 재로그인(재사용 공격 의심 시 로그) |
| 410 | `E-IDO-101` · `103` | 티켓 만료 · 취소 | 재로그인 |
| 429 | `E-AGENCY-306` / `temporarily_unavailable` | 프로파일 한도 초과 | `Retry-After` 뒤 재시도, 한도 상향 협의 |
| 503 | `E-AGENCY-305` | 기관 점검 시간 | 점검 안내 |
| 503 | `FEATURE_DISABLED` | 게이트웨이 API 꺼짐 | 운영기관과 플래그 협의 |

---

## 12. 테스트 환경

```bash
# 운영기관 설치본(compose) + 참조 기관 앱
docker compose -f infra/docker/compose.install.yml up -d          # docs/install.md
AGENCY_PROTOCOL=OIDC_RP AGENCY_OIDC_CLIENT_ID=idem-svc-AGENCY_B AGENCY_OIDC_CLIENT_SECRET=… \
  IDEM_OIDC_ISSUER=http://localhost:8081/realms/idem ./gradlew :idem-tenant-sample:bootRun     # :8084

# 옵션 C 확인: http://localhost:8084/agency/oidc/login → 로그인 → /agency/oidc/logout
# 옵션 A 확인(시뮬레이터, 기관 API 키 필요):
curl -X POST http://localhost:8084/api/v1/simulator/run          # 발급 → 검증 → 재검증(409) 시나리오
curl -X POST 'http://localhost:8084/api/v1/simulator/verify?ticketId=…'
# 유형별 수신 Mock: /mock/bridge/api/handoff/push · /mock/apache-gate · /internal/sso-session
# 웹훅 수신 확인: POST /api/v1/webhook/inbound (서명·타임스탬프 검증 포함)
```

Mock 본인확인 제공자로 코어 흐름을 돌리는 절차는 `docs/install.md` §5(설치 검증 뒤 반드시 끈다). 시험 항목표는 `docs/manuals/test-items.md`.

---

## 13. API 레퍼런스 요약

### 13.1 기관이 호출하는 Idem API

| API | 메서드·경로 | 인증 | 비고 |
|---|---|---|---|
| OIDC Discovery | `GET {gate}/realms/idem/.well-known/openid-configuration` | 없음 | 옵션 C |
| OIDC authorize/token/userinfo/end_session | Discovery 참조 | client 인증 + PKCE | 옵션 C |
| Handoff 브라우저 진입 (1.1) | `GET {hub}/api/v1/handoff/login?service=&callback=&state=` | 없음(브라우저) | 옵션 A·B — 로그인 → 발급 → `callback?ticketId=` |
| Handoff 검증 | `POST {hub}/api/v1/handoff/verify` | `X-Agency-Code` + `X-Agency-Key` | 옵션 A·B, 1회 소비 |
| CAST 검증 | `POST {hub}/api/v1/agency/cast/verify` | 기관 키 | 기관 간 SSO |
| CAST 공개키 | `GET {hub}/api/v1/agency/cast/public-key` | 없음 | |
| 이벤트 피드 | `GET {hub}/api/v1/agency/events` · `POST …/{dispatchId}/read` | 기관 키 | 폴링 |
| 연동 상태 | `GET {hub}/api/v1/agency/gateway/status/{code}` | 기관 키 | SDK `getStatus` |
| 게이트웨이 이벤트 | `POST …/gateway/inbound/event` · `PATCH …/gateway/outbound/notify` | 기관 키 (+HMAC) | 기본 꺼짐 |

### 13.2 기관이 구현하는 엔드포인트

| 엔드포인트 | 언제 | 호출자 | 검증 |
|---|---|---|---|
| OIDC 콜백 (`redirect_uri`) | 옵션 C | 브라우저 | state·nonce·PKCE·id_token |
| Back-Channel Logout URI | 옵션 C, 선택 | idem-gate/Keycloak | `logout_token` 서명·`sid` |
| Handoff 콜백 (`callbackWhitelist`) | DIRECT | 브라우저 | `state` 비교 → `ticketId` → verify; `error` 파라미터면 거부 화면 (1.1) |
| `{bridge}/api/handoff/push` | BRIDGE | idem-hub | `X-Handoff-Signature`(HMAC) |
| `{apacheGate}` | APACHE_GATE | idem-hub | 헤더 `X-Remote-User`·`X-Auth-Level`·`X-Handoff-Token`·`X-Session-Expiry` |
| `{ssoDomain}/internal/sso-session` | INTERNAL_SSO | idem-hub | `X-Agency-Code`·`X-Source-System` |
| 웹훅 수신 (`webhookEndpoint`) | 선택 | idem-hub | `X-Webhook-Signature`·`X-Webhook-Timestamp` ±5분 |

---

*이 문서는 `idem-hub`(`HandoffController`·`AgencyEventController`·`CrossAgencySsoController`·`WebhookDispatchOutboxRelay`), `idem-gate`(OIDC 프런트), `idem-tenant-sample`, `idem-sdk-java`, `idem-agent` 1.0.1 소스를 기준으로 작성했다. 코드와 문서가 다르면 코드가 우선한다.*
