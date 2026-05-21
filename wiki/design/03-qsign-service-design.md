# Q-Sign (Q Signature) 서비스 상세 설계서

| 항목 | 내용 |
|------|------|
| **서비스명** | Q-Sign (Q Signature) |
| **포트** | 8081 |
| **역할** | 인증 세션 생성·관리, OIDC 브로커, PKCE, Keycloak 연동, 인증 결과 발행 |
| **기술 스택** | Spring Boot 3.2, JDK 21, PostgreSQL, Redis, Kafka |
| **최종 갱신** | 2026-05-15 (v0.8.8) |

---

## 1. 개요

Q-Sign은 **인증 세션 관리 서비스**다. 사용자의 로그인 시작부터 인증 완료까지의 세션을 관리하고, 다양한 IdP(Keycloak, NICE, OACX)의 인증 결과를 수집하여 IdO로 전달한다. OIDC Discovery 엔드포인트를 제공하여 표준 OIDC 클라이언트와 호환된다.

```
FE Login → Q-Sign(:8081)
               │
       ┌───────┴──────────┐
   Keycloak OIDC    NICE/OACX 인증
       │                  │
   OIDC Callback    Auth Result
       │                  │
       └───────┬──────────┘
               │
         AuthResultRepository (DB)
               │
           OutboxRelay → Kafka qsign.auth.events → IdO
```

---

## 2. 패키지 구조

```
kr.go.smes.qsign
├── api/                    # REST API 컨트롤러
│   ├── AuthController.java         ← 인증 시작·콜백
│   ├── InternalSessionController.java  ← 내부 세션 조회
│   ├── InternalSigVerifier.java    ← 내부 서명 검증
│   └── OidcDiscoveryController.java    ← /.well-known/openid-configuration
├── application/            # 애플리케이션 서비스
│   ├── AuthService.java
│   └── AuthServiceImpl.java
├── keycloak/               # Keycloak OIDC 연동
│   ├── KeycloakAuthUrlController.java  ← /keycloak/auth URL 생성
│   ├── KeycloakCallbackController.java ← /keycloak/callback 처리
│   ├── KeycloakCallbackService.java
│   ├── KeycloakJwksVerifier.java       ← ID Token 검증
│   ├── KeycloakLogoutService.java
│   ├── KeycloakStateStore.java         ← state 파라미터 Redis 저장
│   └── KeycloakProperties.java
├── outbox/                 # Outbox 릴레이
│   ├── OutboxRelay.java            ← Kafka 발행 릴레이
│   └── QSignOutboxRepository.java
├── kafka/                  # Kafka 소비
│   ├── QimUserEventConsumer.java   ← Q-IM 이벤트 소비 (세션 무효화 등)
│   └── IdempotentEventStore.java
├── pkce/                   # PKCE (Proof Key for Code Exchange)
│   └── PkceService.java
├── metrics/                # 인증 지표
│   └── AuthMetrics.java
├── domain/                 # 도메인 모델
│   └── AuthSession.java
└── infrastructure/         # JPA 레포지토리
    ├── AuthResultRepository.java
    └── LockRepository.java
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 인증 세션 생명주기

```
1. FE → Q-Sign: POST /api/v1/auth/start
   → AuthSession 생성 (Redis, TTL 10분)
   → state, nonce 생성 → PKCE code_challenge 계산

2. Q-Sign → FE: IdP 인증 URL 반환 (Keycloak or NICE or OACX)

3. 사용자 → IdP: 인증 수행

4. IdP → Q-Sign: 콜백 (code or result)
   → 인증 결과 검증
   → AuthResult DB 저장
   → Outbox 발행 (qsign.auth.events)

5. Q-Sign → IdO(Kafka): 인증 완료 이벤트
   → IdO: Handoff 처리 or Q-IM 회원 조회
```

### 3.2 Keycloak OIDC 연동

```java
// KeycloakCallbackService.java
public void processCallback(String code, String state) {
    // 1. state 검증 (CSRF 방지) → KeycloakStateStore(Redis) 조회
    KeycloakStateEntry stateEntry = stateStore.pop(state);

    // 2. Authorization Code → Token Exchange
    KeycloakTokenResponse tokens = keycloakClient.exchangeCode(code);

    // 3. ID Token 검증 (KeycloakJwksVerifier)
    KeycloakIdTokenClaims claims = jwksVerifier.verify(tokens.getIdToken());

    // 4. AuthResult 저장 + Outbox 발행
    authResultRepository.save(claims);
    outboxService.publishAuthEvent(claims.getSub(), "KEYCLOAK_AUTH_COMPLETE");
}
```

### 3.3 PKCE (Proof Key for Code Exchange)

```java
// PkceService.java
// code_verifier: 43-128자 랜덤 문자열
// code_challenge: BASE64URL(SHA256(code_verifier))
// method: S256 (Plain 미지원)
```

### 3.4 AuthMetrics — 인증 지표

```java
// AuthMetrics.java (Micrometer)
// auth.success.count: 인증 성공 수
// auth.failure.count: 인증 실패 수 (reason 태그)
// auth.duration.seconds: 인증 소요 시간
// → /actuator/prometheus 노출 → Grafana 대시보드
```

### 3.5 Outbox → Kafka 발행

```java
// OutboxRelay.java (@Scheduled, 5초 주기)
// 1. qsign.outbox에서 PENDING 배치 조회
// 2. Kafka qsign.auth.events 토픽 발행
// 3. 성공 → COMPLETED, 실패 → 지수 백오프 재시도
```

---

## 4. DB 스키마 (Q-Sign)

| 테이블 | 용도 |
|--------|------|
| `qsign.auth_result` | 인증 결과 저장 (Keycloak/NICE/OACX) |
| `qsign.auth_lock` | 중복 처리 방지 락 |
| `qsign.outbox` | Kafka 발행 Outbox |
| `qsign.processed_event` | Kafka 멱등성 (중복 소비 방지) |
| `qsign.oidc_session` | OIDC 세션 (state, nonce) |

### Flyway 버전 현황 (Q-Sign)

| 버전 | 내용 |
|------|------|
| V1 | 기본 스키마 (auth_result, 인증 세션) |
| V2 | 감사 로그 |
| V3 | OIDC 세션 (state, nonce) |
| V4 | Processed Event (멱등성) |
| V5 | 인증 수단 확장 |

---

## 5. OIDC Discovery 엔드포인트

```json
GET /.well-known/openid-configuration
{
    "issuer": "https://onepass.smes.go.kr",
    "authorization_endpoint": "/oauth2/authorize",
    "token_endpoint": "/oauth2/token",
    "jwks_uri": "/oauth2/jwks",
    "response_types_supported": ["code"],
    "grant_types_supported": ["authorization_code"],
    "code_challenge_methods_supported": ["S256"]
}
```

---

## 6. 보안 설계

| 항목 | 처리 방식 |
|------|----------|
| CSRF 방지 | state 파라미터 (Redis, 1회 사용) |
| 코드 도용 방지 | PKCE S256 (code_verifier 검증) |
| ID Token 검증 | Keycloak JWKS 공개키로 서명 검증 |
| 중복 처리 방지 | auth_lock 테이블 + 분산 락 |
| 세션 만료 | Redis TTL 자동 만료 |
