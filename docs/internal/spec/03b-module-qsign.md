# 03-B. Q-Sign 모듈 상세 명세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **모듈 경로**: `idem-gate/`  
> **포트**: 8081  
> **DB**: PostgreSQL (`qsign` 스키마, V1~V5)  
> **완성도**: 95%

---

## 1. 모듈 역할

Q-Sign은 **인증(Authentication) Source of Record**다. 외부 IdP(Keycloak, 비OIDC 수단)를 통해 사용자의 실제 인증을 수행하고, 결과를 `qsign.auth_result`에 저장하며 Kafka로 게시한다. **신원 저장·관리는 Q-IM의 영역이다.**

### 1.1 핵심 책임 분리

| Q-Sign 담당 | Q-Sign 비담당 |
|------------|--------------|
| 인증 수단별 OIDC 브로커링 | 회원 원장 (→ Q-IM) |
| JWT 검증 (JJWT) | 정책 판단 (→ IdO) |
| PKCE(RFC 7636) 구현 | Handoff 발급 (→ IdO) |
| auth_result 저장 | 유관기관 연동 (→ IdO) |
| Kafka Outbox 게시 | |

---

## 2. 패키지 구조

```
idem-gate/src/main/java/io/github/hipstermin/idem/gate/
├── QSignApplication.java
├── auth/
│   ├── AuthController.java              # POST /api/v1/auth/complete (내부)
│   └── dto/
├── broker/                              # Keycloak OIDC 브로커
│   ├── BrokerController.java            # GET /api/v1/broker/authorize
│   ├── BrokerService.java               # 브로커 모드 분기
│   ├── keycloak/
│   │   ├── KeycloakCallbackService.java # OIDC 콜백 처리
│   │   ├── KeycloakJwksVerifier.java    # JWKS 검증
│   │   └── KeycloakProperties.java      # @ConfigurationProperties
│   └── dto/
├── config/
│   ├── KafkaTopicConfig.java            # 토픽 설정 (피크 60K 설계)
│   ├── QSignWebConfig.java              # RestTemplate 빈
│   └── ResilienceConfig.java            # Resilience4j 설정
├── infrastructure/
│   ├── jpa/
│   │   └── entity/AuthResultJpaEntity.java
│   └── repository/
│       └── AuthResultRepositoryImpl.java
├── kafka/
│   ├── IdempotentEventStore.java        # ★ v1.9.2 GAP-QS-03
│   ├── QimUserEventConsumer.java        # ★ v1.9.2 GAP-QS-03
│   └── OutboxRelayScheduler.java
└── pkce/
    └── PkceService.java                 # ★ v1.8.0 RFC 7636
```

---

## 3. OIDC 브로커 흐름 (Keycloak 모드)

```
사용자 브라우저
    ↓ GET /api/v1/broker/authorize?providerCode=KAKAO_OIDC&...
BrokerController → BrokerService.buildAuthorizationUrl()
    ↓  [mode=keycloak]
KeycloakProperties.buildAuthorizationUrl()
  - state / nonce 생성 → Redis 저장 (TTL 300s)
  - PKCE code_verifier 생성 → PkceService.storeChallenge()
    ↓  redirect
Keycloak (IdP 브로커) → 카카오/네이버/PASS 등
    ↓  callback
GET /api/v1/oidc/keycloak/callback?code=...&state=...
KeycloakCallbackService.handleCallback()
  1. state CSRF 검증 (Redis 조회)
  2. PkceService.verifyCodeVerifier()
  3. code → token (Keycloak token endpoint)
  4. JWT 파싱 / nonce 검증 / audience 검증
  5. identifierHash = SHA-256(sub)
  6. providerCode 결정 (kc_idp_hint 역매핑)
  7. auth_result INSERT
  8. outbox INSERT + Kafka 게시 (AUTH_COMPLETED)
  9. IdO feSession 쿠키 생성 요청
```

---

## 4. PKCE 서비스 (PkceService)

RFC 7636 전체 구현.

```java
// 코드 검증자 생성: 64 byte → Base64URL
String verifier = pkceService.generateCodeVerifier();

// 코드 챌린지 생성: S256 (SHA-256)
String challenge = pkceService.generateCodeChallenge(verifier);

// Redis 저장: key = "qsign:pkce:challenge:{state}" (TTL 300s)
pkceService.storeChallenge(state, challenge, "S256");

// 콜백 검증 (상수시간 비교)
boolean ok = pkceService.verifyCodeVerifier(state, verifier);
```

**설정**:

```yaml
qsign:
  pkce:
    enabled: true          # 기본값: true
    challenge-ttl-sec: 300
```

---

## 5. Kafka Outbox 패턴

### 5.1 생산 흐름

1. `KeycloakCallbackService` / `NonOidcAuthService`에서 auth_result INSERT와 동일 트랜잭션에서 `qsign.outbox_record` INSERT  
2. `OutboxRelayScheduler` (500ms 주기)가 `PENDING` 레코드를 읽어 `KafkaTemplate`으로 게시  
3. 게시 성공 → `PUBLISHED`로 상태 업데이트  
4. 최대 3회 재시도, 초과 시 `FAILED`

### 5.2 KafkaTopicConfig 설계

```
qsign.auth.events       파티션 12  보존 1h    LZ4 압축  최대 1MB
qsign.auth.events.dlq   파티션 6   보존 7d
```

> **피크 설계**: 60K 사용자 동시 접속 기준.  
> 파티션은 증가만 가능, 감소 불가.

---

## 6. 멱등 이벤트 소비자 (GAP-QS-03, v1.9.2)

`qim.user.events` 토픽 소비 시 `qsign.processed_event`를 통해 중복 처리 방지.

```java
// IdempotentEventStore
@Transactional
public boolean tryMarkProcessed(String eventId, long eventVersion) {
    // INSERT INTO qsign.processed_event ... ON CONFLICT DO NOTHING
    // INSERT INTO qsign.last_event_version ... ON CONFLICT DO UPDATE
    return rowsInserted > 0;
}

// QimUserEventConsumer
@KafkaListener(topics = "qim.user.events", groupId = "q-sign-consumer")
public void consume(ConsumerRecord<String, String> record) {
    // 6단계 멱등 처리:
    // 1. eventId 파싱
    // 2. tryMarkProcessed() 실패 → 중복 → skip
    // 3. USER_SUSPENDED → auth_lock 강제 잠금
    // 4. USER_WITHDRAWN → auth_lock 영구 잠금
    // 5. 상태 기록
    // 6. 커밋
}
```

---

## 7. DB 스키마 (qsign 스키마)

| Flyway 버전 | 파일 | 주요 변경 |
|------------|------|----------|
| V1 | `V1__create_schema.sql` | auth_result, auth_lock, outbox_record, provider_config 기본 |
| V2 | `V2__add_audit_log.sql` | audit_log, used_nonce |
| V3 | `V3__add_keycloak_session.sql` | keycloak_session_log, oidc_nonce_used |
| V4 | `V4__add_idempotent.sql` | last_event_version, processed_event |
| V5 | `V5__add_auth_method.sql` | auth_result.auth_method 컬럼 추가 |

---

## 8. 주요 설정 (application.yml)

```yaml
server.port: 8081

spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?currentSchema=qsign
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate.default_schema: qsign

resilience4j:
  circuitbreaker:
    instances:
      idp-broker:
        slidingWindowSize: 20
        failureRateThreshold: 50
        slowCallDurationThreshold: 3s
        waitDurationInOpenState: 30s
  retry:
    instances:
      idp-broker:
        maxAttempts: 3
        waitDuration: 500ms

qsign:
  keycloak:
    base-url: http://localhost:8085
    realm: onepass
    client-id: q-sign-client
    client-secret: ${QSIGN_KEYCLOAK_CLIENT_SECRET}
    redirect-uri: http://localhost:8081/api/v1/oidc/keycloak/callback
    state-ttl-seconds: 300
    idp-hint-mapping:
      kakao: social-kakao
      naver: social-naver
      pass: social-pass
      gpki: social-gpki
  auth-levels:
    KAKAO_OIDC: L1
    NAVER_OIDC: L1
    PASS_OIDC: L2
    PASS: L2
    FINANCIAL_CERT: L3
    GPKI_OIDC: L3
    GPKI: L3
```

---

## 9. 미구현 항목 (P1)

| ID | 항목 | 우선순위 |
|----|------|---------|
| GAP-QS-04 | `X-Internal-Sig` 수신 검증 (`AuthController`에 HMAC-SHA256 재계산 + ±60s 타임스탬프 검증) | P1 |

---

*다음 문서: [03c-module-qim.md](03c-module-qim.md)*
