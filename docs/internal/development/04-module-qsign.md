# 04. Q-Sign 모듈 구현 상태 (v1.9.0)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **모듈 경로**: `idem-gate/`  
> **포트**: 8081  
> **DB**: PostgreSQL (`qsign` 스키마, V1~V5)

---

## 1. 모듈 개요

Q-Sign은 **인증 SoR(Source of Record)**으로, 모든 인증 결과의 단일 진실 저장소 역할을 한다.

### 1.1 패키지 구조

```
idem-gate/src/main/java/io/github/hipstermin/idem/gate/
├── QSignApplication.java
├── api/                           # IdO 내부 API
│   ├── AuthController.java
│   └── dto/
├── application/                   # 비즈니스 로직
│   ├── AuthService.java
│   └── AuthServiceImpl.java
├── config/                        # 설정
│   ├── KafkaConsumerConfig.java
│   ├── KafkaProducerConfig.java
│   ├── KafkaTopicConfig.java
│   └── QSignWebConfig.java
├── domain/
│   └── AuthSession.java
├── infrastructure/                # JPA 구현체
│   ├── AuthResultRepository.java
│   ├── AuthResultRepositoryImpl.java  ★ v1.4.1 신규
│   ├── LockRepository.java
│   ├── LockRepositoryImpl.java        ★ v1.4.1 신규
│   └── jpa/
│       ├── entity/
│       │   ├── AuthResultJpaEntity.java  ★ v1.4.1 신규
│       │   └── AuthLockJpaEntity.java    ★ v1.4.1 신규
│       └── repository/
│           ├── AuthResultJpaRepository.java  ★ v1.4.1 신규
│           └── AuthLockJpaRepository.java    ★ v1.4.1 신규
├── keycloak/                      # Keycloak OIDC 어댑터
│   ├── KeycloakAuthUrlController.java
│   ├── KeycloakCallbackController.java
│   ├── KeycloakCallbackService.java
│   ├── KeycloakJwksVerifier.java
│   ├── KeycloakProperties.java
│   ├── KeycloakStateEntry.java
│   ├── KeycloakStateStore.java    # Redis state 관리
│   └── dto/
├── outbox/                        # Transactional Outbox
│   ├── OutboxRelay.java
│   ├── QSignOutboxRecord.java
│   └── QSignOutboxRepository.java
└── pkce/                          # PKCE ★ v1.8.0 신규
    └── PkceService.java
```

---

## 2. 핵심 기능별 구현 상태

### 2.1 Keycloak OIDC 브로커링

**상태**: ✅ 완전 구현 (v1.1.0)

**흐름**:
1. `KeycloakAuthUrlController` → Authorization URL 생성 (PKCE + state 포함)
2. Keycloak 카카오/네이버 IdP 리디렉션
3. `KeycloakCallbackController` → callback(code, state) 수신
4. `KeycloakCallbackService.handleCallback()`:
   - state 유효성 검증 (Redis TTL 300s)
   - Keycloak Token Endpoint 호출 → id_token 교환
   - `KeycloakJwksVerifier` → JWT RS256 서명 검증
   - Nonce 검증 (Replay Attack 방어)
   - identifierHash = SHA-256(sub)
   - AuthResult 저장 (`qsign.auth_result`)
   - `X-Internal-Sig` HMAC-SHA256 서명 생성
   - `POST /api/v1/oidc/complete` → IdO 알림

### 2.2 AuthResult 저장 (JPA)

**상태**: ✅ 완전 구현 (v1.4.1 신규 구현체)

| 파일 | 역할 | 버전 |
|------|------|------|
| `AuthResultRepository` | 인터페이스 | 기존 |
| `AuthResultRepositoryImpl` | JPA 구현체 | v1.4.1 ★ |
| `AuthResultJpaEntity` | JPA 엔티티 (`qsign.auth_result`) | v1.4.1 ★ |
| `AuthResultJpaRepository` | Spring Data JPA | v1.4.1 ★ |

**auth_method 저장** (`auth_method` 컬럼, V5 마이그레이션):
```java
// AuthServiceImpl — SHA-256 identifierHash 적용
String identifierHash = computeIdentifierHash(providerCode + ":" + correlationId);
// auth_method: STANDARD_OIDC_KAKAO, NON_STANDARD_PASS 등
```

### 2.3 잠금/재시도 정책

**상태**: ✅ 완전 구현 (v1.4.1)

| 파일 | 역할 |
|------|------|
| `LockRepository` | 인터페이스 |
| `LockRepositoryImpl` | JPA 구현체 |
| `AuthLockJpaEntity` | JPA 엔티티 (`qsign.auth_lock`) |

### 2.4 PKCE (RFC 7636)

**상태**: ✅ 완전 구현 (v1.8.0)

```java
// PkceService
// code_verifier 생성: 43~128자 랜덤 문자열 (Base64URL)
// code_challenge = BASE64URL(SHA256(ASCII(code_verifier)))
// code_challenge_method = S256
// KeycloakStateEntry에 codeVerifier 필드 추가
```

### 2.5 Transactional Outbox

**상태**: ✅ 완전 구현

```java
// OutboxRelay — 500ms 폴링
// PENDING → Kafka(idem.gate.auth.events) 발행 → PUBLISHED
```

### 2.6 X-Internal-Sig HMAC-SHA256 서명

**상태**: ✅ 서명 생성 완료 (v1.4.2)

```java
// KeycloakCallbackService.buildInternalSig()
private String buildInternalSig(String correlationId) {
    long epochSeconds = System.currentTimeMillis() / 1000L;
    String payload = correlationId + ":" + epochSeconds;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(
            internalSigSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
}
```

> ⚠️ **미완료**: IdO OidcCompleteController 수신 측에서 X-Internal-Sig HMAC 재계산 검증 미구현 (P1-03)

---

## 3. Flyway 마이그레이션 현황

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | `V1__create_schema.sql` | qsign 스키마 기본 테이블 (auth_result, auth_lock, auth_session, outbox_record) |
| V2 | `V2__add_audit_log.sql` | audit_log 테이블, idx_used_nonce (BUG-01 수정: IMMUTABLE 오류 제거) |
| V3 | `V3__add_oidc_session.sql` | OIDC state 세션 테이블 (keycloak_state) |
| V4 | `V4__add_processed_event.sql` | processed_event 멱등 컨슈머 테이블 |
| V5 | `V5__add_auth_method.sql` | auth_result에 auth_method 컬럼 추가 ★ v1.4.1 |

---

## 4. Keycloak 연동 설정

### 4.1 realm-export.json

```json
{
  "realm": "onepass",
  "clients": [
    {
      "clientId": "q-sign-client",
      "protocol": "openid-connect",
      "redirectUris": ["http://localhost:8081/api/v1/oidc/keycloak/callback"]
    }
  ],
  "identityProviders": [
    {
      "alias": "kakao",
      "providerId": "keycloak-oidc",
      "config": { "clientId": "${KAKAO_CLIENT_ID}" }
    }
  ]
}
```

### 4.2 application.yml 핵심 설정

```yaml
qsign:
  keycloak:
    base-url: ${KEYCLOAK_URL:http://localhost:8088}
    realm: ${KEYCLOAK_REALM:onepass}
    client-id: ${IDEM_GATE_KEYCLOAK_CLIENT_ID:q-sign-client}
    client-secret: ${IDEM_GATE_KEYCLOAK_CLIENT_SECRET:change-me}
    redirect-uri: ${IDEM_GATE_KEYCLOAK_REDIRECT_URI:http://localhost:8081/api/v1/oidc/keycloak/callback}
    idp-hint-mapping:
      kakao: social-kakao
      naver: social-naver
      pass: non-oidc-pass
      gpki: non-oidc-gpki
```

---

## 5. 잔여 미구현 항목

| ID | 항목 | 우선순위 |
|----|------|---------|
| GAP-QS-02 | AuthResult.signature 실제 EdDSA/HMAC 서명 | P2 |
| GAP-QS-03 | `qsign.processed_event` 테이블 migration + IdempotentEventStore | P3 |
| GAP-QS-04 | AuthController X-Internal-Sig 수신 측 검증 | P2 |
| P1-03 | OidcCompleteController X-Internal-Sig HMAC 재계산 | P1 |
| - | 카카오 OIDC 실 Client ID/Secret 설정 | P0 (운영 전) |
| - | 네이버 OIDC 연동 | Sprint 6 |
| - | 단위 테스트 작성 | P2 |

---

## 6. API 엔드포인트

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/api/v1/oidc/{provider}/auth-url` | 인가 URL 생성 | 없음 |
| GET | `/api/v1/oidc/keycloak/callback` | Keycloak 콜백 수신 | OAuth state |
| POST | `/api/v1/auth/from-ido` | IdO로부터 인증 입력 수신 | X-Internal-Sig |
| GET | `/actuator/health` | 헬스체크 | 없음 |

---

*다음 문서: [05-module-qim.md](05-module-qim.md)*
