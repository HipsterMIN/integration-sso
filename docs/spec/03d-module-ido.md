# 03-D. IdO 모듈 상세 명세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **모듈 경로**: `ido/`  
> **포트**: 8083  
> **DB**: PostgreSQL (`ido` 스키마, V1~V10)  
> **완성도**: 99%

---

## 1. 모듈 역할

IdO(Identity Orchestrator)는 통합인증 플랫폼의 **정책 오케스트레이터**이자 **FE BFF(Backend for Frontend)**다.

### 1.1 핵심 기능 목록

| 기능 군 | 설명 |
|--------|------|
| **FE BFF** | feSessionId 쿠키 발급·갱신·만료, ReturnUrl 화이트리스트 검증 |
| **IdP 브로커** | Q-Sign 또는 Keycloak으로 인증 위임, 인증 완료 콜백 처리 |
| **Handoff 발급/검증** | 유관기관용 암호화 티켓 발급(AES-256-GCM) + HMAC-SHA256 서명 |
| **Policy Engine** | Q-IM 상태 확인, 인증 수준 검증, 속성 필터, 점검 시간 판단 |
| **Webhook 발송** | 유관기관으로 이벤트 Push (Outbox 패턴) |
| **기관 이벤트 폴링 API** | ★ v1.9.3 — `GET /api/v1/agency/events` |
| **Provider 라우팅** | ★ v1.9.0 — providerType 기반 런타임 분기 |
| **Admin API** | 기관 CRUD, API 키 로테이션, 통계 |
| **Rate Limiter** | Redis 기반 기관별 TPS·일일 쿼터 |
| **Crypto 스케줄러** | AES 키 버전 로테이션 (90일) |

---

## 2. 패키지 구조 (전체)

```
ido/src/main/java/kr/go/smes/ido/
├── IdoApplication.java
├── admin/                              # 기관 Admin API
│   ├── AgencyAdminController.java      # /api/v1/admin/agencies/**
│   ├── AgencyAdminService.java
│   └── dto/
├── api/                                # Handoff + 기관 이벤트 공개 API
│   ├── GlobalExceptionHandler.java
│   ├── HandoffController.java          # /api/v1/handoff/**
│   ├── AgencyEventController.java      # ★ v1.9.3 /api/v1/agency/events
│   └── dto/
│       ├── AgencyEventResponse.java    # ★ v1.9.3
│       └── AgencyEventListResponse.java # ★ v1.9.3
├── audit/
│   └── AuditLogPublisher.java          # platform.audit.log 게시
├── broker/                             # IdP 브로커
│   ├── BrokerAuditLogService.java      # ★ v1.9.0 — broker_audit_log 비동기 INSERT
│   ├── BrokerController.java
│   ├── BrokerService.java
│   ├── keycloak/
│   │   ├── KeycloakCallbackController.java
│   │   ├── KeycloakJwksVerifier.java
│   │   ├── KeycloakOidcService.java
│   │   └── KeycloakProperties.java
│   ├── nonoidc/
│   │   ├── NonOidcAuthCommand.java
│   │   ├── NonOidcAuthService.java
│   │   ├── NonOidcBrokerAdapter.java
│   │   └── NonOidcBrokerController.java
│   └── provider/                       # ★ v1.9.0 — Provider 라우팅
│       ├── ProviderCircuitBreakerConfig.java
│       ├── ProviderConfig.java
│       ├── ProviderConfigRepository.java
│       └── ProviderRouter.java
├── burst/
│   ├── AuthResultCacheService.java
│   └── SessionAdvisoryPublisher.java
├── config/
│   ├── AsyncConfig.java
│   ├── HandoffAgencyKeyInterceptor.java # X-Agency-Key SHA-256 검증
│   ├── IdoWebConfig.java               # RestTemplate, ObjectMapper 빈
│   ├── KafkaConsumerConfig.java
│   ├── KafkaTopicConfig.java
│   ├── RedisConfig.java                # v1.9.0: provider-config 캐시 추가
│   └── TraceparentFilter.java          # W3C traceparent 전파
├── crypto/
│   └── HandoffKeyRotationScheduler.java
├── domain/
│   └── AgencyMeta.java                 # v1.8.0: integrationType, bridgeEndpoint 추가
├── fe/
│   ├── api/FeSessionController.java
│   ├── config/IdoWebMvcConfig.java     # v1.9.3: /api/v1/agency/** 등록
│   ├── kafka/FeAdvisoryConsumer.java
│   └── session/
├── handoff/
│   ├── HandoffIssueCommand.java
│   ├── HandoffService.java
│   ├── HandoffServiceImpl.java
│   ├── crypto/HandoffCryptoService.java
│   └── strategy/
│       ├── HandoffStrategy.java        # 인터페이스
│       ├── HandoffStrategyFactory.java
│       ├── DirectHandoffStrategy.java
│       ├── BridgeHandoffStrategy.java
│       ├── InternalSsoHandoffStrategy.java # ★ v1.9.2
│       └── ApacheGateHandoffStrategy.java  # ★ v1.9.2
├── infrastructure/
│   ├── AgencyMetaRepositoryImpl.java
│   ├── HandoffTicketRepositoryImpl.java
│   ├── QimClient.java
│   └── QimClientImpl.java
├── kafka/
│   ├── HandoffEventConsumer.java
│   ├── QimEventConsumer.java
│   ├── QimSpMemberEventConsumer.java
│   └── QsignAuthEventConsumer.java
├── memberlookup/
│   ├── MemberLookupController.java
│   └── MemberLookupService.java
├── policy/
│   ├── PolicyEngine.java
│   └── PolicyEngineImpl.java
├── ratelimit/
│   └── AgencyRateLimiter.java          # Redis Lua 스크립트 기반
├── webhook/
│   ├── AgencyEventQueryService.java    # ★ v1.9.3
│   ├── AgencyEventQueryServiceImpl.java # ★ v1.9.3
│   ├── WebhookDispatcherService.java
│   └── WebhookDispatchOutboxRelay.java
└── qim/
    ├── QimSpReceiverController.java
    └── OutboxOutboxServiceImpl.java
```

---

## 3. Handoff 발급/검증 흐름

### 3.1 발급 (POST /api/v1/handoff/issue)

```
① HandoffAgencyKeyInterceptor
   - X-Agency-Code, X-Agency-Key 존재 확인
   - SHA-256(rawKey) 계산 → ido.agency_meta.api_key_hash 비교 (상수시간)

② HandoffServiceImpl.issue()
   Rate Limit 체크 (AgencyRateLimiter: TPS + 일일 쿼터)
   → 콜백 URL 화이트리스트 검증 (CallbackUrlValidator)
   → 점검 시간 판단 (PolicyEngineImpl)
   → Q-IM 사용자 상태 확인 (SUSPENDED/WITHDRAWN → 거부)
   → 최소 인증 수준 검증

③ HandoffCryptoService.encrypt(payload)
   AES-256-GCM + HandoffCryptoService.sign(payload)
   HMAC-SHA256 서명

④ HandoffTicket INSERT (TTL 60초)
   Kafka HANDOFF_ISSUED 게시

⑤ HandoffStrategy 실행 (HandoffStrategyFactory.getStrategy(integrationType))
   - DIRECT: 아무 작업 없음
   - BRIDGE: POST {bridgeEndpoint}/api/handoff/push
   - INTERNAL_SSO: POST {ssoDomain}/internal/sso-session
   - APACHE_GATE: Apache mod_auth 헤더 사전 Push
```

### 3.2 검증 (POST /api/v1/handoff/verify)

```
① 티켓 조회 (Redis)
② 상태 확인: CONSUMED/REVOKED → 에러 (E-IDO-102/103)
③ 만료 확인: expiresAt < now → E-IDO-101
④ 기관 코드 일치 확인 → E-IDO-104
⑤ 티켓 CONSUMED 처리
⑥ PolicyEngine.buildHandoffPayload() → HandoffPayload 구성
⑦ HANDOFF_CONSUMED Kafka 게시
```

---

## 4. Provider 라우팅 (GAP-IDO, v1.9.0)

```java
// ProviderConfig
public record ProviderConfig(
    String providerCode,
    String providerType,   // STANDARD_OIDC | SEMI_STANDARD_OIDC | NON_STANDARD
    String displayName,
    boolean active
) {}

// ProviderRouter
public BrokerMode route(String providerCode) {
    ProviderConfig cfg = providerConfigRepository.findByCode(providerCode); // Redis 캐시 60분
    return switch (cfg.providerType()) {
        case "STANDARD_OIDC", "SEMI_STANDARD_OIDC" -> BrokerMode.KEYCLOAK_RELAY;
        case "NON_STANDARD"                        -> BrokerMode.DIRECT_BROKER;
        default -> BrokerMode.KEYCLOAK_RELAY;
    };
}

// ProviderCircuitBreakerConfig: provider_code 단위 동적 CB 생성
// 설정은 ido.provider_circuit_config 테이블에서 로드
```

---

## 5. Broker Audit Log (v1.9.0)

```java
// BrokerAuditLogService (@Async — auditExecutor 풀)
public void log(String correlationId, String providerCode,
                String action,        // REDIRECT|CALLBACK|COMPLETE|FAIL|TIMEOUT
                String clientIp, String result) {
    // INSERT INTO ido.broker_audit_log ...
}
```

| 기록 시점 | 액션 | 담당 클래스 |
|---------|------|-----------|
| 브로커 리다이렉트 | REDIRECT | `BrokerController` |
| OIDC 콜백 수신 | CALLBACK | `KeycloakCallbackController` |
| 인증 완료 | COMPLETE | `KeycloakOidcService`, `NonOidcAuthService` |
| 인증 실패 | FAIL | `KeycloakCallbackController` |
| 타임아웃 | TIMEOUT | Resilience4j CB 이벤트 핸들러 |

---

## 6. Rate Limiter (AgencyRateLimiter)

Redis Lua 스크립트 기반 슬라이딩 윈도우.

```
Redis Key 패턴:
  TPS  → ido:rl:tps:{agencyCode}:{epochSecond}    (TTL 2s)
  일일  → ido:rl:daily:{agencyCode}:{yyyyMMdd}     (TTL 25h)

기본 설정:
  tps_limit   = 200 req/s (ido.agency_rate_limit_config 테이블 오버라이드 가능)
  daily_limit = 1,000,000 req/day

실패 시 동작: Redis 오류 발생 시 fail-open (허용)
```

---

## 7. 기관 이벤트 폴링 API (v1.9.3)

```
GET /api/v1/agency/events
  Header: X-Agency-Code, X-Agency-Key  (기존 인터셉터 재사용)
  Query:
    limit    (1~100, 기본 20)
    eventType (선택 필터)
    since    (ISO-8601 커서, 선택)
  Response 200:
  {
    "events": [
      {
        "dispatchId": "uuid",
        "eventType": "HANDOFF_ISSUED|USER_UPDATED|...",
        "payload": { ... },
        "status": "PENDING|DISPATCHED",
        "createdAt": "2026-05-09T00:00:00Z",
        "dispatchedAt": "2026-05-09T00:00:01Z"
      }
    ],
    "count": 5,
    "hasMore": false,
    "polledAt": "2026-05-09T01:00:00Z",
    "queryInfo": { "limit": 20, "since": null, "eventType": null }
  }

POST /api/v1/agency/events/{dispatchId}/read
  Response 204: 읽음 처리
  Response 404: 없거나 권한 없음 (보안: 정보 노출 방지)
```

**구현 특성**:
- 데이터 소스: `webhook_dispatch_outbox` 재활용 (신규 테이블 없음)
- `since` 커서: `created_at ASC + created_at > ?` 조건으로 연속 폴링 중복 방지
- 소유권 검증: `markAsRead()`에서 `agency_code = ?` AND 조건
- 멱등: 이미 DISPATCHED 레코드 re-mark 시 404 반환

---

## 8. Handoff Strategy Pattern (v1.8.0 / v1.9.2)

| Strategy | integrationType | 동작 |
|----------|----------------|------|
| `DirectHandoffStrategy` | `DIRECT` | 아무 작업 없음 (기관이 직접 verify 호출) |
| `BridgeHandoffStrategy` | `BRIDGE` | Bridge 서버로 티켓 Push |
| `InternalSsoHandoffStrategy` | `INTERNAL_SSO` | SSO 도메인으로 세션 사전 등록 |
| `ApacheGateHandoffStrategy` | `APACHE_GATE` | Apache mod_auth 호환 헤더 Push |

---

## 9. 주요 설정 (application.yml)

```yaml
server.port: 8083

ido:
  policy:
    default-version: "1.0"          # 외부화 (하드코딩 제거 v1.9.0)
  platform-version: "1.0"          # 외부화
  handoff:
    ticket-ttl-sec: 60
    aes-key: ${IDO_HANDOFF_AES_KEY}
    hmac-secret: ${IDO_HANDOFF_HMAC_SECRET}
  broker:
    mode: qsign                    # qsign | keycloak
  internal-sig:
    secret: ${IDO_INTERNAL_SIG_SECRET}
  rate-limit:
    default-tps: 200
    default-daily: 1000000
  kafka:
    partition-count-main: 12
    partition-count-dlq: 6
    replication-factor: 1          # PoC: 1, 운영: 3
```

---

## 10. 미구현 항목 (P1)

| ID | 항목 | 우선순위 |
|----|------|---------|
| P1-03 | `X-Internal-Sig` 수신 측 검증 (`OidcCompleteController`에 HMAC-SHA256 + ±60s) | P1 |
| GAP-IDO-09 | Kafka DLQ `DeadLetterPublishingRecoverer` 완전 연결 | P1 |
| GAP-API-02 | `Idempotency-Key` 헤더 (HandoffController 중복 발급 방지) | P1 |
| GAP-API-04 | `Retry-After` 헤더 (`E-OPS-901` + `GlobalExceptionHandler`) | P1 |

---

*다음 문서: [03e-module-agency-stub.md](03e-module-agency-stub.md)*
