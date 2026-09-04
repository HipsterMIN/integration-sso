# 03. IdO 모듈 구현 상태 (v1.9.0)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **모듈 경로**: `idem-hub/`  
> **포트**: 8083  
> **DB**: PostgreSQL (`ido` 스키마, V1~V10)

---

## 1. 모듈 개요

IdO(Identity Orchestrator)는 통합인증 플랫폼의 **핵심 오케스트레이터**로, 모든 인증·정책·핸드오프·기관 연동을 총괄한다.

### 1.1 패키지 구조

```
idem-hub/src/main/java/kr/go/smes/idem-hub/
├── IdoApplication.java
├── admin/                         # 기관 Admin API
│   ├── AgencyAdminController.java
│   ├── AgencyAdminService.java
│   └── dto/
├── api/                           # 핸드오프 공개 API
│   ├── GlobalExceptionHandler.java
│   ├── HandoffController.java
│   └── dto/
├── audit/                         # 감사 로그
│   └── AuditLogPublisher.java
├── broker/                        # 브로커 구간
│   ├── BrokerAuditLogService.java  ★ v1.9.0 신규
│   ├── BrokerController.java
│   ├── BrokerService.java
│   ├── keycloak/                  # Keycloak OIDC 어댑터
│   │   ├── KeycloakCallbackController.java
│   │   ├── KeycloakJwksVerifier.java
│   │   ├── KeycloakOidcService.java
│   │   ├── KeycloakProperties.java
│   │   └── dto/
│   ├── nonoidc/                   # 비OIDC 어댑터
│   │   ├── NonOidcAuthCommand.java
│   │   ├── NonOidcAuthService.java
│   │   ├── NonOidcBrokerAdapter.java
│   │   └── NonOidcBrokerController.java
│   ├── provider/                  # Provider 설정 관리 ★ v1.9.0 신규
│   │   ├── ProviderCircuitBreakerConfig.java
│   │   ├── ProviderConfig.java
│   │   ├── ProviderConfigRepository.java
│   │   └── ProviderRouter.java
│   └── state/
├── burst/                         # Redis Pre-warming, Advisory
│   ├── AuthResultCacheService.java
│   └── SessionAdvisoryPublisher.java
├── config/                        # 설정
│   ├── AsyncConfig.java
│   ├── HandoffAgencyKeyInterceptor.java
│   ├── IdoWebConfig.java
│   ├── KafkaConsumerConfig.java
│   ├── KafkaTopicConfig.java
│   ├── RedisConfig.java
│   └── TraceparentFilter.java
├── crypto/                        # 암호화
│   └── HandoffKeyRotationScheduler.java
├── domain/
│   └── AgencyMeta.java
├── fe/                            # FE 세션 관리
│   ├── api/FeSessionController.java
│   ├── config/IdoWebMvcConfig.java
│   ├── kafka/FeAdvisoryConsumer.java
│   └── session/
├── handoff/                       # Handoff Ticket
│   ├── HandoffIssueCommand.java
│   ├── HandoffService.java
│   ├── HandoffServiceImpl.java
│   ├── crypto/HandoffCryptoService.java
│   ├── strategy/                  # ★ v1.8.0 신규
│   │   ├── HandoffStrategy.java
│   │   ├── HandoffStrategyFactory.java
│   │   ├── DirectHandoffStrategy.java
│   │   └── BridgeHandoffStrategy.java
│   └── validate/CallbackUrlValidator.java
├── infrastructure/                # JPA 구현체
│   ├── AgencyMetaRepository.java
│   ├── AgencyMetaRepositoryImpl.java
│   ├── LastEventVersionStore.java
│   ├── LastEventVersionStoreImpl.java
│   ├── QimClient.java
│   ├── QimClientImpl.java
│   ├── TicketRepository.java
│   ├── TicketRepositoryImpl.java
│   ├── UserStatusCache.java
│   ├── UserStatusCacheImpl.java
│   ├── jpa/entity/AgencyMetaJpaEntity.java
│   ├── jpa/repository/AgencyMetaJpaRepository.java
│   └── outbox/
├── kafka/                         # Kafka Consumer
│   ├── HandoffEventConsumer.java
│   ├── IdempotentEventStore.java
│   ├── QimEventConsumer.java
│   └── QsignAuthEventConsumer.java
├── memberlookup/                  # 회원 조회 API
│   ├── MemberLookupController.java
│   └── MemberLookupService.java
├── policy/                        # 정책 엔진
│   ├── PolicyEngine.java
│   └── PolicyEngineImpl.java
├── qim/                           # Q-IM SP 중재
│   ├── crypto/AesSharedKeyDecryptor.java
│   └── sp/
│       ├── api/QimSpReceiverController.java
│       ├── api/dto/
│       ├── domain/InstMbrIdMapping.java
│       ├── infrastructure/
│       ├── kafka/
│       └── service/QimSpReceiverService.java
├── ratelimit/                     # Rate Limiting
│   └── AgencyRateLimiter.java
└── webhook/                       # Webhook 디스패처
    ├── WebhookDispatcherService.java
    └── WebhookDispatchOutboxRelay.java
```

---

## 2. 핵심 기능별 구현 상태

### 2.1 Handoff Ticket (Issue/Verify/Revoke)

**상태**: ✅ 완전 구현 (v1.5.0)

| 기능 | 클래스 | 상태 |
|------|--------|------|
| Ticket 발급 | `HandoffServiceImpl.issue()` | ✅ |
| Ticket 검증 | `HandoffServiceImpl.verify()` | ✅ |
| Ticket 취소 | `HandoffServiceImpl.revoke()` | ✅ |
| AES-256-GCM 암호화 | `HandoffCryptoService` | ✅ |
| HMAC-SHA256 서명 | `HandoffCryptoService` | ✅ |
| Redis 저장 | `TicketRepositoryImpl` | ✅ |
| 키 버전 로테이션 | `HandoffKeyRotationScheduler` | ✅ |

**Handoff Ticket 발급 SQL** (AES-256-GCM 암호화 적용):
```java
// HandoffCryptoService.encrypt()
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
byte[] encrypted = cipher.doFinal(plaintext.getBytes());
return Base64.getUrlEncoder().encodeToString(encrypted);
```

### 2.2 Handoff 전략 (Strategy Pattern)

**상태**: ✅ 구현 완료 (v1.8.0)

| 전략 | 클래스 | integration_type | 상태 |
|------|--------|-----------------|------|
| Direct | `DirectHandoffStrategy` | DIRECT | ✅ |
| Bridge | `BridgeHandoffStrategy` | BRIDGE | ✅ |
| Internal SSO | (예정) | INTERNAL_SSO | ❌ P2 |
| Apache Gate | (예정) | APACHE_GATE | ❌ P2 |

### 2.3 PolicyEngine (정책 엔진)

**상태**: ✅ 완전 구현 (v1.8.0)

| 기능 | 상태 | 비고 |
|------|------|------|
| 속성 필터링 (`allowedAttributes`) | ✅ | 기관별 허용 속성만 반환 |
| `agencySubjectId` 생성 | ✅ | HMAC-SHA256(qimUserId:agencyCode) |
| `policyVersion` 외부화 | ✅ | `${ido.policy.default-version:1.0}` |
| Callback URL 화이트리스트 | ✅ | `CallbackUrlValidator` |

```java
// PolicyEngineImpl.generateAgencySubjectId()
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(agencySubjectSecret.getBytes(), "HmacSHA256"));
byte[] hmac = mac.doFinal((qimUserId + ":" + agencyCode).getBytes(UTF_8));
return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
```

### 2.4 Broker Audit Log (브로커 감사 로그)

**상태**: ✅ 완전 구현 (v1.9.0)

| 액션 | 기록 위치 | 상태 |
|------|---------|------|
| REDIRECT (인증 시작) | (향후 BrokerController 연결) | 🟡 |
| CALLBACK (콜백 수신) | `KeycloakCallbackController` | ✅ |
| COMPLETE (인증 완료) | `KeycloakOidcService`, `NonOidcAuthService` | ✅ |
| FAIL (인증 실패) | `KeycloakCallbackController` | ✅ |
| TIMEOUT | (향후 연결) | 🟡 |

```java
// BrokerAuditLogService
@Async("auditExecutor")
public void recordComplete(String correlationId, String providerCode,
    String providerType, String sub, String identifierHash,
    String authLevel, String brokerMode, String errorCode) {
    // INSERT INTO ido.broker_audit_log ...
}
```

### 2.5 Provider 런타임 라우팅

**상태**: ✅ 완전 구현 (v1.9.0)

```java
// ProviderRouter.resolve()
public BrokerRoute resolve(String providerCode, String correlationId) {
    return providerConfigRepository.findByCode(providerCode)
        .map(cfg -> fromProviderType(cfg.getProviderType()))
        .orElseGet(() -> heuristicRoute(providerCode));
}

public enum BrokerRoute { KEYCLOAK_RELAY, DIRECT_BROKER }
```

| ProviderType | BrokerRoute | 설명 |
|-------------|-------------|------|
| STANDARD_OIDC | KEYCLOAK_RELAY | Keycloak 릴레이 사용 |
| SEMI_STANDARD_OIDC | KEYCLOAK_RELAY | Keycloak 릴레이 사용 |
| NON_STANDARD | DIRECT_BROKER | 직접 브로커 어댑터 사용 |

### 2.6 Resilience4j Circuit Breaker (provider_code 단위)

**상태**: ✅ 완전 구현 (v1.9.0)

```java
// ProviderCircuitBreakerConfig.getOrCreate()
public CircuitBreaker getOrCreate(String providerCode) {
    return cbCache.computeIfAbsent(providerCode, code -> {
        CircuitBreakerConfig config = loadFromDb(code)
            .orElse(circuitBreakerRegistry.getDefaultConfig());
        return circuitBreakerRegistry.circuitBreaker(
            "provider-" + code.toLowerCase(), config);
    });
}
```

**DB 기반 동적 설정** (`ido.provider_circuit_config` 테이블):
```sql
SELECT sliding_window_size, failure_rate_threshold, wait_duration_seconds, ...
FROM ido.provider_circuit_config
WHERE provider_code = :providerCode
```

### 2.7 Rate Limiting

**상태**: ✅ 완전 구현 (v1.8.0)

```java
// AgencyRateLimiter — Redis 슬라이딩 윈도우
// TPS 제한 (1초 윈도우)
Long tpsCount = redisTemplate.opsForValue().increment(tpsKey);
if (tpsCount > maxTps) throw RateLimitException("TPS 초과");

// 일별 쿼터 제한
Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
if (dailyCount > dailyQuota) throw RateLimitException("일별 쿼터 초과");
```

### 2.8 Webhook 디스패처

**상태**: ✅ 완전 구현 (v1.5.0)

| 컴포넌트 | 역할 | 상태 |
|----------|------|------|
| `WebhookDispatcherService` | Outbox 적재 (HandoffEvent/MemberWithdrawn) | ✅ |
| `WebhookDispatchOutboxRelay` | 500ms 폴링 → HTTPS POST → 지수 백오프 재시도 | ✅ |
| V7 마이그레이션 | `agency_webhook_config`, `webhook_dispatch_outbox` 테이블 | ✅ |

**platformVersion 외부화** (v1.9.0):
```java
@Value("${ido.platform-version:1.0}")
private String platformVersion;
// payload.put("platformVersion", platformVersion); // 하드코딩 제거
```

### 2.9 Q-IM SP 수신 중재

**상태**: ✅ 완전 구현 (v1.2.0)

| 엔드포인트 | 클래스 | 상태 |
|-----------|--------|------|
| `POST /api/qim/sp/v1/member/query` | `QimSpReceiverController` | ✅ |
| `POST /api/qim/sp/v1/member/register` | `QimSpReceiverController` | ✅ |
| `POST /api/qim/sp/v1/member/withdraw` | `QimSpReceiverController` | ✅ |

### 2.10 Admin API

**상태**: ✅ 완전 구현 (v1.8.0)

| 엔드포인트 | 설명 | 상태 |
|-----------|------|------|
| `POST /admin/v1/agencies` | 신규 기관 등록 | ✅ |
| `GET /admin/v1/agencies` | 기관 목록 조회 | ✅ |
| `GET /admin/v1/agencies/{code}` | 기관 상세 조회 | ✅ |
| `PUT /admin/v1/agencies/{code}` | 기관 정보 수정 | ✅ |
| `POST /admin/v1/agencies/{code}/activate` | 기관 활성화 | ✅ |
| `POST /admin/v1/agencies/{code}/deactivate` | 기관 비활성화 | ✅ |
| `POST /admin/v1/agencies/{code}/rotate-key` | API Key 로테이션 | ✅ |

### 2.11 W3C Traceparent 전파

**상태**: ✅ 완전 구현 (기존)

```java
// TraceparentFilter.java
// W3C Trace Context traceparent 헤더 파싱 및 전파
// - traceparent: {version}-{trace-id}-{parent-id}-{flags}
// - CorrelationIdHolder에 저장 후 모든 서비스 레이어에 전파
```

### 2.12 AuditLogPublisher (감사 로그)

**상태**: ✅ 완전 구현 (v1.5.0)

```java
// AuditLogPublisher — DB 기록 + Kafka 비동기 발행
// @Async("auditExecutor") 비동기 처리
// ido.audit_log 테이블 + platform.audit.log Kafka 토픽
```

---

## 3. 잔여 미구현 항목

| ID | 항목 | 우선순위 | 비고 |
|----|------|---------|------|
| P1-03 | X-Internal-Sig 수신 측 검증 | P1 | `OidcCompleteController` HMAC 재계산 미구현 |
| P1-05 | 이벤트 폴링 API | P2 | `GET /api/v1/agency/events` |
| P2 | INTERNAL_SSO HandoffStrategy | P2 | `InternalSsoHandoffStrategy` 미구현 |
| P2 | APACHE_GATE HandoffStrategy | P2 | `ApacheGateHandoffStrategy` 미구현 |
| P2 | Idempotency-Key HandoffController | P2 | 중복 Ticket 발급 방지 |
| P2 | Retry-After 헤더 | P2 | `GlobalExceptionHandler` E-OPS-901 |
| P3 | Micrometer 커스텀 메트릭 | P3 | Handoff 성공률, CB 상태 계측 |
| P3 | E2E 자동화 테스트 | P3 | Playwright or RestAssured |

---

## 4. 환경 변수 목록

```yaml
# application.yml 핵심 설정
ido:
  platform-version: ${IDO_PLATFORM_VERSION:1.0}
  policy:
    default-version: ${IDO_DEFAULT_POLICY_VERSION:1.0}
  provider:
    circuit-cache-ttl-seconds: ${IDO_PROVIDER_CIRCUIT_CACHE_TTL:3600}
  broker:
    mode: ${IDO_BROKER_MODE:keycloak}  # keycloak | qsign
  handoff:
    aes-key: ${IDO_HANDOFF_AES_KEY}
    hmac-secret: ${IDO_HANDOFF_HMAC_SECRET}
  qim:
    base-url: ${QIM_BASE_URL:http://localhost:8082}
    api-key: ${QIM_API_KEY}
    inbound-api-key-hash: ${QIM_INBOUND_API_KEY_HASH}
    aes-shared-key: ${QIM_AES_SHARED_KEY}
  internal-sig-secret: ${IDO_INTERNAL_SIG_SECRET}
  agency-subject-secret: ${IDO_AGENCY_SUBJECT_SECRET}

resilience4j:
  circuitbreaker:
    instances:
      keycloak-client:
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
      qim-client:
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
```

---

*다음 문서: [04-module-qsign.md](04-module-qsign.md)*
