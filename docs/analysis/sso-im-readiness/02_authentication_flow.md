# Phase 2 — 인증 플로우 검증 (Q-Sign)

> **목적**: 한 명의 사용자가 로그인 시도할 때, 어디서 막힐 수 있는가 / 어디서 잘못된 응답을 받을 수 있는가를 코드 레벨로 추적한다.

---

## 1. Q-Sign 인증 엔드포인트 매트릭스

| 엔드포인트 | 메서드 | 인증 흐름 | 호출 주체 |
|----------|--------|----------|----------|
| `POST /api/v1/auth/oidc` | OIDC (idToken 직접 입력) | **흐름 B 레거시** — broker-input 대체 경로? | (확인 필요) |
| `POST /api/v1/auth/broker-input` | NonOIDC normalize (PASS/GPKI) | **흐름 B** | IdO NonOidcBrokerService |
| `GET /api/v1/auth/{authResultId}` | AuthResult 조회 | 후속 조회 | IdO 핸드오프 발급 시 |
| `POST /api/v1/oidc/{provider}/auth-url` | Keycloak 인증 URL 생성 | **흐름 A 진입** | FE (또는 IdO) |
| `GET /api/v1/oidc/keycloak/callback` | Keycloak code/state 콜백 | **흐름 A 완결** | Keycloak (302) |
| `POST /api/v1/internal/session/logout` | SLO Keycloak 세션 종료 | 내부 | IdO SloServiceImpl |
| `GET /.well-known/openid-configuration` | OIDC Discovery | 외부 RP가 OP로 인식 | 외부 클라이언트 |
| `*/protocol/openid-connect/*` (6개) | Keycloak Proxy | OIDC 표준 엔드포인트 | 외부 RP |

---

## 2. 흐름 A — Keycloak Broker 콜백 인증 (소셜/GPKI/PASS 등)

### 시퀀스
```
[브라우저] → FE
    → POST /api/v1/oidc/{provider}/auth-url  (q-sign KeycloakAuthUrlController)
    → state/nonce 생성 + Redis 저장 + returnUrl 기록
    → Keycloak authorize URL 반환
[브라우저] → Keycloak /auth (Keycloak이 카카오/네이버/PASS 등 내부 처리)
[브라우저] ← Keycloak 302 → GET /api/v1/oidc/keycloak/callback?code=...&state=...
    → KeycloakCallbackController.keycloakCallback()
    → KeycloakCallbackService.handleCallback(code, state)
       ├─ stateStore.consumeAndValidate(state)  (Redis 1회 소비)
       ├─ POST Keycloak /token  (authorization_code 교환)
       ├─ KeycloakJwksVerifier.verify(idToken)  (JWKS RS256 서명 검증)
       ├─ nonce 검증 (replay 방어)
       ├─ audience/exp 검증
       ├─ identifierHash = SHA-256(sub)
       ├─ providerCode 역매핑 ("social-kakao" → "KAKAO_OIDC")
       ├─ LockRepository.isLocked() 확인
       ├─ AuthResult + Outbox 저장 (단일 @Transactional)
       └─ POST ido /api/internal/v1/oidc/complete  (FE 세션 발급 요청 + X-Internal-Sig)
    ← redirect URL 반환 → 302 → 기관 returnUrl
```

### 검증된 강점 ✅
1. **JWKS 서명 검증** — Keycloak 응답 idToken을 RS256으로 검증
2. **state 1회 소비** — Redis CSRF 방어 (`stateStore.consumeAndValidate`)
3. **nonce 검증** — replay attack 방어
4. **PII 비보관 원칙** — sub 원문은 SHA-256 직후 GC 대상, 평문 미저장
5. **HMAC-SHA256 내부 서명** — q-sign → ido 호출 시 X-Internal-Sig 첨부
6. **Outbox 패턴** — AuthResult와 인증 이벤트가 단일 트랜잭션으로 저장됨

### 🔴 발견된 문제 (운영 적합성 우려)

#### **F2.1 [Critical] `idem.gate.hub.internal-sig-secret` 기본값이 코드에 존재**
`KeycloakCallbackService.java:80`:
```java
@Value("${idem.gate.hub.internal-sig-secret:ido-internal-secret}")
private String internalSigSecret;
```
- 환경변수 누락 시 **`"ido-internal-secret"`** 평문 기본값으로 fallback.
- 이 값으로 HMAC을 만들면 IdO 수신 측의 `InternalSigVerifier`도 동일 기본값을 갖는 경우 검증을 통과해버린다.
- `InternalSigVerifier`에서는 기동 시 INSECURE_DEFAULT 감지 로그를 남기지만, `KeycloakCallbackService.buildInternalSig()` 에서는 그런 경고 없이 작동.
- **운영 위험**: `IDEM_HUB_INTERNAL_SIG_SECRET` ENV 누락 시 q-sign이 위조된 자기-신호로 ido를 호출 가능. **즉시 수정 필요**.

#### **F2.2 [High] ido 알림 실패 시 사용자가 보는 화면이 깨질 수 있음**
`KeycloakCallbackService.notifyIdoAndGetRedirect()`:
```java
} catch (Exception e) {
    log.error(...);
    return "/error?code=IDO_NOTIFY_FAILED&cid=" + correlationId;
}
```
- IdO `/api/internal/v1/oidc/complete` 호출 실패 시 사용자가 `/error?code=IDO_NOTIFY_FAILED`로 떨어짐.
- **AuthResult는 이미 DB에 저장된 상태** — 즉 q-sign 측에서는 인증 성공, ido 측에서는 세션 발급 실패. **상태 불일치**.
- 사용자가 재로그인을 시도하면 `state` 다른 값으로 다시 들어오므로 정상 처리되지만, 이전 AuthResult는 고아 레코드로 남음. (감사 로그는 정합)
- 사용자가 경험할 수 있는 시나리오: "로그인 됐다는 메시지를 봤는데 다시 로그인 화면이 뜸". 운영 콜센터 클레임 가능성.
- **보완 권장**: ido 알림 실패 시 자동 재시도(1~2회) 또는 명시적 보상 트랜잭션.

#### **F2.3 [High] `/api/v1/auth/oidc` (레거시 경로) 의 보안 차이**
`AuthController.authenticateOidc()`:
- **X-Internal-Sig 검증이 없음** (`broker-input` 만 검증)
- idToken을 그대로 받아서 서명 검증 없이 페이로드만 파싱하여 sub 사용 (`AuthServiceImpl.extractSubFromIdToken`).
- 주석에 "Keycloak 흐름이 아닌 흐름 B의 NonOidc broker-input"이라고 명시되어 있지만, **실제로는 누구나 호출 가능한 외부 엔드포인트로 노출**됨 (Spring Security가 `/api/v1/auth/**`를 어떻게 보호하는지는 후속 확인 필요).
- **만약 인증 미들웨어가 없다면**: 공격자가 임의의 idToken을 만들어서 호출 → identifierHash가 SHA-256(공격자가 만든 sub)으로 계산되어 AuthResult 발급 → IdO 핸드오프 입력으로 사용 가능.
- **즉시 검증 필요**: q-sign에 외부 진입점에 대한 인증 미들웨어가 있는가?

#### **F2.4 [Medium] state Redis 의존도 — Redis 장애 시 인증 자체 불가**
`KeycloakStateStore`(별도 클래스 — 본 Phase에서 코드 미열람)가 Redis에 state를 저장.
- Redis 장애 시 `stateStore.consumeAndValidate()` 실패 → 모든 Keycloak 콜백 인증 실패.
- **회고 §2**: Redis는 "세션 손실, 배치 중복 가능 / 캐시 — Sentinel 권장" 평가였지만, 실제로는 **인증 진입에 필수**.
- 회고 §2의 "Redis: 캐시 — Sentinel 권장"은 부정확. Redis = **인증 진입 1차 의존성** 으로 격상 필요.

#### **F2.5 [Medium] providerCode 역매핑 fallback이 추측 기반**
`KeycloakCallbackService.resolveProviderCode()`:
```java
return stripped + "_OIDC";  // 예: "social-foo" → "FOO_OIDC"
```
- Keycloak이 새로운 identity_provider를 추가하면 (예: `social-toss`) 코드 수정 없이 `"TOSS_OIDC"`라는 providerCode가 자동 생성됨.
- 이게 IdO 측 핸드오프 정책(`PolicyService`)이나 메트릭 라벨에 사전 등록되어 있지 않으면 cardinality 폭증 + 정책 미적용 가능.
- **운영 위험**: 운영자가 "갑자기 모르는 providerCode가 메트릭에 나타남"을 발견.

---

## 3. 흐름 B — NonOIDC broker-input (PASS/GPKI 정규화 입력)

### 시퀀스
```
[브라우저] → FE → 본인확인 진입
[브라우저] → IdO /api/v1/auth/nice/phone/* (또는 OACX)
    → IdO가 NICE CI 응답 수신
    → IdO가 CI → identifierHash(SHA-256(CI)) 계산
[IdO] → POST q-sign /api/v1/auth/broker-input
    + Header: X-Internal-Sig (HMAC)
    + Body: IdOAuthInputRequest { providerCode, providerTxId, identifierHash, providerVerified=true, claims }
    → AuthController.authenticateFromBroker()
       ├─ InternalSigVerifier.verify(sig, cid)
       │   └─ ±60초 epochSeconds 전수 검사, HMAC-SHA256 비교
       └─ AuthServiceImpl.issueFromIdOAuthInput(input)
           ├─ providerVerified=false면 거부
           ├─ LockRepository.isLocked() 확인
           ├─ AuthResult 저장
           └─ Kafka 발행 (idem.gate.auth.events)
```

### 검증된 강점 ✅
1. **X-Internal-Sig 검증** — `InternalSigVerifier`가 strict 모드 보장. PoC 경로 제거됨.
2. **`providerVerified=false` 거부** — IdO가 명시적으로 검증 완료 표기한 경우만 처리
3. **시계 편차 ±60초 허용** — 분산 환경에서 합리적
4. **identifierHash 책임 분리** — IdO가 계산 → Q-Sign은 저장만 (CI 원문이 Q-Sign에 절대 도달하지 않음)

### 🔴 발견된 문제

#### **F2.6 [Critical] `providerVerified=true`만 신뢰하는 모델은 IdO가 깨지면 무력화됨**
```java
if (!input.isProviderVerified()) {
    throw ...IDP_RESPONSE_INVALID...
}
```
- Q-Sign은 IdO가 `providerVerified=true`로 보냈다는 사실 자체만 검증.
- **IdO에 NICE CI 실패 처리 버그가 있어 `providerVerified=true`를 잘못 보내면** Q-Sign은 그대로 인증 성공 처리.
- 다층 방어가 약함. 후속 Phase 4에서 IdO 측 `providerVerified` 결정 로직 확인 필요.

#### **F2.7 [High] Kafka 발행 실패 시 silent fail (트랜잭션 외 발행)**
`AuthServiceImpl.issueFromIdOAuthInput()`:
```java
authResultRepository.save(result);
publishAuthEvent(result, AuthEvent.TYPE_AUTH_COMPLETED);  // Kafka 직접 발행
```
- DB 저장 후 `kafkaTemplate.send()` 직접 호출 — **Outbox 패턴 미적용**.
- Kafka 장애 시 AuthResult는 저장되지만 이벤트는 손실 가능.
- 비교: `KeycloakCallbackService` (흐름 A)는 Outbox에 저장하고 batch가 릴레이 — **흐름 A/B 일관성 결여**.
- **운영 위험**: NICE CI 인증 후 IdO가 인증 결과를 다시 q-sign에서 조회는 정상이지만, 다른 시스템이 `idem.gate.auth.events` 토픽을 구독한다면 일부 이벤트 누락. (q-im에 `QimUserEventConsumer`는 q-im → q-sign 방향이고, q-sign → 외부 구독자가 있는지 후속 확인 필요)

#### **F2.8 [Low] Lock 카운터가 증가만 하고 자동 잠금 트리거가 없음**
`LockRepositoryImpl`:
- `incrementAttempt()`는 attemptCount를 1씩 증가시키지만 `maxAttempts`(기본 5) 도달 시 자동 `lock()` 호출 로직이 없음.
- 호출자(`AuthServiceImpl` 등)가 명시적으로 `lock()`을 호출해야 함.
- 코드 인덱싱 결과: **`incrementAttempt`를 호출하는 곳이 보이지 않음** (별도 검증 필요).
- **운영 위험**: 무한 인증 실패 재시도가 자동 차단되지 않음 → brute-force 가능성.

---

## 4. OIDC Discovery + Keycloak Proxy

### 검증된 강점 ✅
1. **OIDC 표준 준수** — `/.well-known/openid-configuration`이 RFC 8414 형식
2. **PKCE S256만 허용** — `code_challenge_methods_supported: ["S256"]` (plain 제거됨)
3. **Hop-by-hop 헤더 필터** — Keycloak Proxy 시 표준 hop-by-hop 헤더 제거

### 🔴 발견된 문제

#### **F2.9 [High] OIDC Discovery 문서가 Keycloak URL을 직접 노출**
`OidcDiscoveryController.getOpenIdConfiguration()`:
```java
metadata.put("authorization_endpoint", keycloakProperties.authorizationEndpoint());
metadata.put("token_endpoint", keycloakProperties.tokenEndpoint());
```
- 클라이언트가 `well-known`을 조회하면 **Keycloak의 내부 URL이 그대로 노출**됨.
- Keycloak이 사설망 IP나 내부 도메인으로 설정되어 있으면 외부 클라이언트가 도달 불가.
- **운영 위험**: prod 환경에서 OIDC Discovery 사용 시 클라이언트가 작동 안 함. q-sign proxy 엔드포인트를 노출하도록 변경 필요.

#### **F2.10 [Medium] OidcDiscoveryController에 인증·인가 검증이 없음**
- `/.well-known/openid-configuration` + Keycloak Proxy 6개 엔드포인트는 **인증 없이 누구나 호출 가능**.
- Proxy 엔드포인트는 Keycloak이 자체 인증을 하므로 보안 영향은 제한적.
- 단, **DDoS 우려**: 공격자가 `/protocol/openid-connect/token` 을 부하 인가, q-sign이 매번 Keycloak으로 forward → Keycloak 다운 가능. **Rate Limit이 q-sign에 적용되어 있는가** 후속 확인 필요.

---

## 5. SLO (Single Logout)

### 시퀀스
```
[FE/IdO] → POST ido /api/v1/slo/initiate
[IdO] → POST q-sign /api/v1/internal/session/logout
    + X-Internal-Sig
    + Body: { sub, correlationId }
    → InternalSessionController.logoutSession()
       ├─ InternalSigVerifier.verify(sig)
       └─ KeycloakLogoutService.revokeKeycloakSession(sub, cid)
```

### 🔴 발견된 문제

#### **F2.11 [High] SLO 실패 응답이 "비치명적"으로 분류됨 — 사용자 인지 못함**
주석 `InternalSessionController:46`:
> "204 No Content — 세션 종료 성공 또는 비치명적 실패 (Keycloak 불응 시에도 204)"

- Keycloak 통신 실패 시에도 204 반환 → 사용자는 "로그아웃 됨"으로 인지.
- 하지만 Keycloak 세션은 여전히 살아있음 → 다른 SSO 기관으로 가면 자동 로그인 됨.
- **개인정보보호 위험**: 사용자가 "로그아웃 했다"고 믿었는데 다른 기관에서는 여전히 로그인 상태.
- **보완 권장**: SLO 실패는 4xx 또는 5xx로 명시적 응답 + 사용자에게 "Keycloak 세션 종료 실패, 브라우저 닫고 다시 시도" 안내.

---

## 6. Phase 2 종합 — 인증 본질 리스크

| ID | 우려 | 심각도 | 본질 위협 | 즉시 조치 |
|-----|------|--------|----------|----------|
| **F2.1** | `internal-sig-secret` 기본값 `"ido-internal-secret"` 하드코딩 | **Critical** | 인증 위조 가능 | ENV 미설정 시 기동 거부 (fail-fast) |
| **F2.6** | `providerVerified=true` 단일 신호 신뢰 | **Critical** | IdO 버그 시 인증 우회 | IdO 측 검증 강화 (Phase 4) |
| **F2.2** | IdO 알림 실패 시 고아 AuthResult | High | 사용자 혼란 | 자동 재시도 또는 보상 |
| **F2.3** | `/api/v1/auth/oidc` 외부 노출 의심 | High | 임의 AuthResult 발급 가능 | Security 설정 검증 (P5) |
| **F2.7** | Kafka 직접 send (Outbox 미적용) | High | 이벤트 손실 | Outbox로 이관 |
| **F2.9** | Discovery에 Keycloak 내부 URL 노출 | High | 외부 RP 작동 불가 | q-sign proxy URL로 변경 |
| **F2.11** | SLO 실패 silent fail (204) | High | 로그아웃 미완료 | 명시적 4xx |
| **F2.4** | Redis 장애 시 모든 Keycloak 인증 불가 | Medium | 가용성 | Sentinel/Cluster + 우아한 디그레이드 |
| **F2.5** | providerCode fallback 추측 | Medium | 메트릭 cardinality | whitelist 검증 |
| **F2.10** | Discovery 엔드포인트 Rate Limit 없음 | Medium | DDoS via proxy | Rate Limit 적용 확인 |
| **F2.8** | Lock 자동 트리거 없음 | Low | brute-force | maxAttempts 도달 시 자동 lock |

### 가장 위험한 시나리오 3가지

**🚨 시나리오 A — 운영 배포 직후 인증 위조**:
prod 환경에서 `IDEM_HUB_INTERNAL_SIG_SECRET` ENV를 깜빡 누락하면, `KeycloakCallbackService`는 `"ido-internal-secret"` 평문으로 HMAC 생성. IdO 측 `InternalSigVerifier`가 동일 기본값을 사용 중이면 검증 통과. **공격자가 이 사실을 알고 직접 IdO 엔드포인트를 호출하면 인증 위조 가능**.

**🚨 시나리오 B — Redis 장애 시 전면 인증 중단**:
Redis 단일 노드 + Sentinel 미구성 상태에서 메모리 압박이나 네트워크 단절 발생. state Redis 없음 → **모든 Keycloak Callback이 401**. 운영자는 PR-B2-new에서 Redis 알람을 유령 알람으로 제거했으므로 **알람 없이 인증 전면 중단** 발생 가능. (현재 `IdoServiceDown`은 발화되지만 원인이 Redis라는 정보 없음.)

**🚨 시나리오 C — 사용자가 로그아웃 했다고 믿지만 Keycloak 세션은 살아있음**:
사용자가 기관 A에서 로그아웃 → IdO SLO → Q-Sign /internal/session/logout → Keycloak 통신 실패 → 204 반환. 사용자는 다른 PC에서 기관 B 접속 → Keycloak 자동 로그인 → 사용자 의도 위반.

---

## 7. 다음 Phase로 이어지는 질문

- **Phase 3 (Q-IM)**: `identifierHash`로 q-im 회원을 조회할 때, 등록되지 않은 사용자는 어떻게 처리되는가? CI 미등록 사용자가 인증 후 핸드오프까지 가능한가?
- **Phase 4 (IdO)**: `providerVerified=true` 신호를 IdO가 어떻게 결정하는가? NICE CI 응답을 어떻게 검증하는가?
- **Phase 5 (KMS/PII)**: identifierHash 외에 CI/이름/생년월일 원문이 어디까지 흘러가는가? KMS 미설정 시 어떻게 동작하는가?
