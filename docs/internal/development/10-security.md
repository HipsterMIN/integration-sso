# 10. 보안 구현 현황 (Security)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09

---

## 1. 보안 구현 전체 현황

| 영역 | 항목 | 상태 | 위치 |
|------|------|------|------|
| **인증** | PKCE (RFC 7636) | ✅ | `PkceService` (Q-Sign) |
| **인증** | JWT RS256 서명 검증 | ✅ | `KeycloakJwksVerifier` |
| **인증** | Nonce 검증 (Replay Attack 방어) | ✅ | `KeycloakCallbackService` step 4 |
| **인증** | CSRF State 검증 (1회 소비) | ✅ | `KeycloakStateStore` (Redis TTL 300s) |
| **인증** | 기관 API Key 검증 | ✅ | `HandoffAgencyKeyInterceptor` |
| **인증** | Q-IM SP API Key 검증 | ✅ | `QimSpReceiverService` |
| **암호화** | Handoff Payload AES-256-GCM | ✅ | `HandoffCryptoService` |
| **암호화** | Handoff 서명 HMAC-SHA256 | ✅ | `HandoffCryptoService` |
| **암호화** | CI 복호화 AES-256-CBC | ✅ | `AesSharedKeyDecryptor` |
| **암호화** | CI 저장 AES-256-GCM | ✅ | `CiCryptoServiceImpl` (Q-IM) |
| **암호화** | 내부 서명 HMAC-SHA256 생성 | ✅ | `KeycloakCallbackService.buildInternalSig()` |
| **암호화** | 내부 서명 HMAC-SHA256 **수신 측 검증** | ⚠️ | `OidcCompleteController` — P1-03 미구현 |
| **개인정보** | identifierHash = SHA-256(CI/sub) | ✅ | `KeycloakCallbackService`, `AesSharedKeyDecryptor` |
| **개인정보** | PII 마스킹 (이름/전화/이메일) | ✅ | `PiiMaskingService` (Q-IM) |
| **개인정보** | agencySubjectId HMAC 비가역 | ✅ | `PolicyEngineImpl.generateAgencySubjectId()` |
| **개인정보** | 속성 필터링 (allowedAttributes) | ✅ | `PolicyEngineImpl.buildHandoffPayload()` |
| **Rate Limiting** | 기관별 TPS + 일별 쿼터 | ✅ | `AgencyRateLimiter` (Redis 슬라이딩 윈도우) |
| **Circuit Breaker** | provider_code 단위 CB | ✅ | `ProviderCircuitBreakerConfig` |
| **Circuit Breaker** | keycloak-client CB | ✅ | Resilience4j 설정 |
| **Circuit Breaker** | qim-client CB | ✅ | Resilience4j 설정 |
| **Kafka 보안** | Outbox 멱등 컨슈머 | ✅ | `IdempotentEventStore` |
| **Kafka 보안** | DLQ 전략 | ❌ | `KafkaConsumerConfig` — GAP-IDO-09 미구현 |
| **망 분리** | 기관 외부망 격리 원칙 명문화 | ✅ | `docs/agency-external-arch-supplement.md` |
| **망 분리** | 내부 Kafka 외부 노출 차단 | ⚠️ | agency-stub PoC 직접 구독 중 (P2) |
| **추적** | W3C traceparent 전파 | ✅ | `TraceparentFilter` |

---

## 2. 암호화 구현 상세

### 2.1 Handoff Payload 암호화 (AES-256-GCM)

```java
// HandoffCryptoService.encrypt()
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

// 저장 형식: v{n}.{base64url(iv)}.{base64url(ciphertext)}
```

### 2.2 Handoff 서명 (HMAC-SHA256)

```java
// HandoffCryptoService.sign()
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(hmacSecret.getBytes(), "HmacSHA256"));
byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
```

### 2.3 agencySubjectId 생성 (HMAC-SHA256)

```java
// PolicyEngineImpl.generateAgencySubjectId()
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(agencySubjectSecret.getBytes(UTF_8), "HmacSHA256"));
byte[] hmac = mac.doFinal((qimUserId + ":" + agencyCode).getBytes(UTF_8));
return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
```

특징:
- 기관 코드별 독립 생성 (같은 사용자도 기관마다 다른 값)
- 비가역 (원본 qimUserId 복원 불가)
- 기관에게 사용자 CI/sub 미노출

### 2.4 identifierHash

```java
// SHA-256(CI 또는 idToken.sub)
MessageDigest digest = MessageDigest.getInstance("SHA-256");
byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
return HexFormat.of().formatHex(hash);
```

### 2.5 CI 암호화 (Q-IM)

```java
// CiCryptoServiceImpl
// 저장 형식: v1.{base64url(iv)}.{base64url(ciphertext)}
// AES-256-GCM, 12바이트 랜덤 IV
```

### 2.6 내부 서명 (Q-Sign → IdO)

```java
// KeycloakCallbackService.buildInternalSig()
// payload = correlationId + ":" + epochSeconds
// HMAC-SHA256(payload, IDEM_HUB_INTERNAL_SIG_SECRET)
// 헤더: X-Internal-Sig: {hexHmac}
// ⚠️ 수신 측(OidcCompleteController) 검증 미구현 — P1-03
```

---

## 3. 키 관리

### 3.1 키 목록

| 키 | 용도 | 환경변수 | 저장 위치 |
|----|------|---------|---------|
| AES-256-GCM 키 | Handoff Payload 암호화 | `IDEM_HUB_HANDOFF_AES_KEY` | K8s Secret / Vault |
| HMAC-SHA256 시크릿 | Handoff 서명 | `IDO_HANDOFF_HMAC_SECRET` | K8s Secret / Vault |
| 내부 서명 시크릿 | X-Internal-Sig | `IDEM_HUB_INTERNAL_SIG_SECRET` | K8s Secret / Vault |
| agencySubject 시크릿 | agencySubjectId HMAC | `IDEM_HUB_AGENCY_SUBJECT_SECRET` | K8s Secret / Vault |
| Q-IM AES 공유키 | encCi 복호화 | `IDEM_REGISTRY_AES_SHARED_KEY` | K8s Secret / Vault |
| CI 암호화 키 (Q-IM) | CI 저장 | `IDEM_REGISTRY_CI_AES_KEY_V1` | K8s Secret / Vault |
| DI 생성 시크릿 | DI HMAC | `IDEM_REGISTRY_DI_SECRET` | K8s Secret / Vault |

### 3.2 키 버전 관리

`HandoffKeyRotationScheduler` — AES 키 버전 로테이션:
- 현재 활성 키: `ido.handoff_key_registry.is_active = true`
- 신규 암호화: 항상 최신 버전 키 사용
- 복호화: 버전 파싱 후 해당 버전 키 사용 (하위 호환 보장)
- 로테이션 절차: 신규 키 `ido.handoff_key_registry` 삽입 → `is_active` 전환

---

## 4. Rate Limiting 구현

### 4.1 AgencyRateLimiter (Redis 슬라이딩 윈도우)

```java
// TPS 제한 (1초 윈도우)
String tpsKey = "idem:rate-limit:" + agencyCode + ":tps";
Long tpsCount = redisTemplate.opsForValue().increment(tpsKey);
redisTemplate.expire(tpsKey, 1, TimeUnit.SECONDS);
if (tpsCount > maxTps) throw new RateLimitException("TPS 초과");

// 일별 쿼터 제한
String dailyKey = "idem:rate-limit:" + agencyCode + ":daily:" + LocalDate.now();
Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
redisTemplate.expire(dailyKey, 24, TimeUnit.HOURS);
if (dailyCount > dailyQuota) throw new RateLimitException("일별 쿼터 초과");
```

**응답**: HTTP 429 + `Retry-After` 헤더

### 4.2 기관별 설정

`ido.agency_meta` 테이블:
- `daily_lookup_limit`: 기관별 일일 조회 한도 (기본 10,000건)

`application.yml`:
```yaml
resilience4j:
  ratelimiter:
    instances:
      default-agency:
        limit-for-period: 1000
        limit-refresh-period: 1s
        timeout-duration: 0s
```

---

## 5. Circuit Breaker 구현

### 5.1 provider_code 단위 동적 CB

```java
// ProviderCircuitBreakerConfig.getOrCreate()
// 1. ido.provider_circuit_config DB 조회
// 2. 없으면 circuitBreakerRegistry.getDefaultConfig() 상속
// 3. "provider-{providerCode.toLowerCase()}" 이름으로 CB 등록
// 4. ConcurrentHashMap 캐시 (TTL: idem.hub.provider.circuit-cache-ttl-seconds)
```

### 5.2 기본 CB 설정 (application.yml)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      keycloak-client:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
        permitted-number-of-calls-in-half-open-state: 3
      qim-client:
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
```

### 5.3 Fallback 전략

```
CB OPEN 시:
  1. UserStatusCache(Redis)에서 qimUserId 상태 조회 (Stale TTL 1800s)
  2. 캐시 미존재 → E-IDO-106 (IDO_QIM_UNREACHABLE) 반환
  3. 신규 등록 요청 → Outbox PENDING 저장 → 복구 후 비동기 재처리
```

---

## 6. 감사 로그

### 6.1 Broker Audit Log (`ido.broker_audit_log`)

v1.9.0에서 완전 구현. 브로커 구간 전체 감사 기록.

| 액션 | 트리거 | 클래스 |
|------|--------|--------|
| CALLBACK | Keycloak 콜백 수신 | `KeycloakCallbackController` |
| COMPLETE | 인증 성공 완료 | `KeycloakOidcService`, `NonOidcAuthService` |
| FAIL | 인증 실패 | `KeycloakCallbackController` |

### 6.2 Audit Log (`ido.audit_log`)

플랫폼 전반 감사 로그 (v1.5.0~).

```java
// AuditLogPublisher
// @Async("auditExecutor")
// - DB(ido.audit_log) 동기 기록
// - Kafka(platform.audit.log) 비동기 발행
```

---

## 7. 미구현 보안 항목 (우선순위별)

| ID | 항목 | 우선순위 | 영향 |
|----|------|---------|------|
| P1-03 | X-Internal-Sig 수신 측 검증 | P1 | 내부 API 보안 취약 (PoC 수용 가능) |
| GAP-IDO-09 | Kafka DLQ DeadLetterPublishingRecoverer | P2 | 최대 재시도 초과 이벤트 유실 |
| GAP-QS-02 | AuthResult.signature 실제 서명 | P2 | X-Internal-Sig 연동 불완전 |
| - | Callback URL 검증 플래그 (`callback_validation_enabled`) | P2 | PoC 하위호환 버퍼 |
| - | mTLS 기관 인증 | P3 | Nginx/Gateway 레벨, 장기 목표 |
| DEBT-01 | HashiCorp Vault / AWS KMS 연동 | v3.0 | 환경변수 키 → 전용 KMS |

---

## 8. 보안 체크리스트

| 항목 | 상태 | 비고 |
|------|------|------|
| PII 비보관 (identifierHash만 저장) | ✅ | SHA-256(CI/sub) |
| CI 암호화 저장 (Q-IM) | ✅ | AES-256-GCM |
| Handoff 암호화+서명 | ✅ | AES-256-GCM + HMAC-SHA256 |
| 내부 서명 생성 | ✅ | HMAC-SHA256 |
| 내부 서명 수신 측 검증 | ⚠️ | P1-03 |
| Nonce 검증 (Replay Attack) | ✅ | |
| CSRF State 검증 | ✅ | Redis 1회 소비 |
| JWT 서명 검증 (RS256) | ✅ | JWKS 기반 |
| PKCE (code_challenge) | ✅ | S256 방식 |
| Rate Limiting | ✅ | Redis 슬라이딩 윈도우 |
| 기관 API Key 검증 | ✅ | PBKDF2 해시 비교 |
| Circuit Breaker | ✅ | provider_code 단위 |
| Kafka 멱등 컨슈머 | ✅ | processed_event 테이블 |
| DLQ 전략 | ❌ | GAP-IDO-09 |
| 기관 외부망 격리 원칙 | ✅ (문서화) | 실 격리 P2 |

---

*다음 문서: [11-infrastructure.md](11-infrastructure.md)*
