# Phase 5 — Privacy / KMS / Audit 심층 분석

**분석 일자**: 2026-05-22
**분석 범위**: 키 관리(KMS) 통합, 개인정보 비저장 원칙, 감사 로그(audit log) 무결성/추적성
**기준 브랜치**: `shipster` @ `90956a7`

---

## 0. 분석 대상

| # | 파일 | 라인 | 책임 |
|---|------|------|------|
| 1 | `idem-hub/.../crypto/kms/KmsClient.java` | 104 | KMS 인터페이스 (decrypt/encrypt/isHealthy/providerName) |
| 2 | `idem-hub/.../crypto/kms/VaultKmsClient.java` | 502 | HashiCorp Vault Transit 실제 연동 |
| 3 | `idem-hub/.../crypto/kms/LocalKmsClient.java` | 80+ | KMS off — Base64 패스스루 (matchIfMissing=true) |
| 4 | `idem-hub/.../crypto/kms/NoOpKmsClient.java` | 70 | provider=noop 명시적 KMS off |
| 5 | `idem-hub/.../crypto/kms/NhnKmsClient.java` | (확인 필요) | NHN Cloud SKM 연동 (레거시) |
| 6 | `idem-hub/.../crypto/kms/AnyIdKmsClient.java` | (확인 필요) | AnyID KMS |
| 7 | `idem-hub/.../infrastructure/health/VaultKmsHealthIndicator.java` | (확인 필요) | Vault 헬스 체크 |
| 8 | `idem-hub/.../metrics/KmsHealthMetrics.java` | (확인 필요) | KMS Gauge 메트릭 (PR-B1) |
| 9 | `idem-hub/.../handoff/crypto/HandoffCryptoService.java` | 262 | AES-256-GCM + HMAC-SHA256 + KeyVersionRegistry |
| 10 | `idem-hub/.../audit/AuditLogPublisher.java` | 318 | DB 우선 + Kafka 비동기 감사 로그 |
| 11 | `idem-registry/.../crypto/CiCryptoServiceImpl.java` | 145 | Q-IM CI 암호화 (Phase 3 에서 분석) |

---

## 1. Findings

### F5.1 [**Critical**] `LocalKmsClient` 의 `matchIfMissing=true` — 환경변수 누락 시 자동 KMS off (평문 키 사용)

**증거** — `LocalKmsClient.java:46`:
```java
@ConditionalOnProperty(
    prefix = "ido.kms",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true   // ← ido.kms.enabled 가 명시되지 않으면 LocalKmsClient 활성화
)
public class LocalKmsClient implements KmsClient {
```

**문제 — 운영 진입 직전 관점**:
- Helm chart, K8s ConfigMap, application-prod.yml 어디든 한 곳에서 `IDO_KMS_ENABLED` 환경변수가 누락되면 → LocalKmsClient 활성화 → 모든 키가 Base64 평문으로 DB 저장 + 디코딩만 수행 → **KMS 보호 0**.
- "운영자가 깜빡할 수 있는 단 한 글자"가 보안 전체를 뒤집음.
- 실제 application-prod.yml 또는 Helm values 파일을 확인하지 못한 상태로 운영 진입하면 위험.

**부가 증거** — 주석 (라인 36-39):
```
보안 경고: 이 구현체는 키 재료를 암호화하지 않으므로
운영·스테이징 환경에서 절대 사용 금지.
ido.kms.enabled=false 설정은 개발·테스트 환경으로 제한한다.
```
→ 의도는 분명하나 **fail-safe 설계가 정반대** (missing → off). 진정한 fail-safe 는 missing → exception 또는 missing → 가장 안전한 옵션 (Vault 시도).

**개선안**:
1. `matchIfMissing = false` 로 변경 → `ido.kms.enabled` 미설정 시 Spring 컨텍스트 기동 실패 (KmsClient bean 부재).
2. 또는 `@PostConstruct` 에서 운영 프로파일(`@Profile("prod")`) 활성 시 `LocalKmsClient` 가 선택되면 즉시 fail-fast (`IllegalStateException`).
3. Health check 에 "KMS provider != local" 검증 추가 — Liveness 실패.
4. Helm chart `values.yaml.example` 에 `IDO_KMS_ENABLED: "true"` 강제 표시 + `helm lint` 시 누락 검출.

---

### F5.2 [**Critical**] Vault 토큰 미획득 시 startup 차단 부재 — 토큰 없이도 서비스 기동

**증거** — `VaultKmsClient.java:198-215` `@PostConstruct init()`:
```java
this.clientToken = acquireToken();
if (clientToken == null || clientToken.isBlank()) {
    log.error("[KMS-Vault] 토큰 획득 실패. auth-method={} ...");
    // ← 로그만 찍고 예외 throw 하지 않음 → Spring context 정상 기동
}
```

**문제**:
- AppRole/Kubernetes 인증 실패 (잘못된 role-id, expired secret-id, SA 토큰 파일 누락) 시 → clientToken=null → 로그 ERROR 만 남기고 **bean 정상 생성**.
- 실제 첫 decrypt/encrypt 호출 시점에 `X-Vault-Token` 헤더 없이 요청 → 401 → `KmsDecryptException` throw → 사용자 인증 흐름 차단.
- 운영자는 "기동 성공"으로 오해 + traffic 진입 후에야 발견 → 사용자 차단 incident.

**개선안**:
```java
if (clientToken == null || clientToken.isBlank()) {
    throw new IllegalStateException("[KMS-Vault] 토큰 획득 실패. 서비스 기동 차단.");
}
// + 즉시 한 번의 health check 호출로 실제 통신 가능성 확인
if (!isHealthy()) {
    throw new IllegalStateException("[KMS-Vault] Vault 서버 도달 불가.");
}
```

---

### F5.3 [**Critical**] Vault 토큰 만료 자동 갱신 부재 — TTL 만료 후 인증 실패 누적

**증거** — `VaultKmsClient.java:190-192`:
```java
/**
 * 런타임에 획득한 클라이언트 토큰.
 * AppRole/K8s 인증은 만료 TTL이 있으므로 필요 시 갱신해야 하지만,
 * 여기서는 기동 시 1회 획득 + 만료 시 재시도(retry on 403) 전략을 사용한다.
 */
private volatile String clientToken;
```

**문제**:
- AppRole token TTL 기본 1시간 (Vault 가이드 라인 81 `token_ttl=1h`).
- 1시간 후 모든 KMS 호출이 403 → `TokenExpiredException` → `acquireToken()` 재호출 → 새 토큰 획득.
- **하지만**: 만료 시점부터 재획득 완료까지의 모든 동시 요청이 401/403 실패 → 사용자 차단.
- 또한 `acquireToken()` 자체가 실패하면 (Vault 일시 장애) volatile clientToken=null → 후속 요청 전부 실패.
- 만료 직전 백그라운드 갱신 (token lookup-self + renew-self) 미구현.

**개선안**:
1. `@Scheduled(fixedDelay=30min)` 백그라운드 작업으로 토큰 lifetime 의 50% 시점에 `POST /v1/auth/token/renew-self` 호출.
2. Spring Vault (`org.springframework.vault:spring-vault-core`) 라이브러리 사용 — 토큰 라이프사이클 자동 관리.
3. 토큰 만료 시 retry exponential backoff (1s, 2s, 4s).

---

### F5.4 [**Critical**] Audit log 의 `actor_id` 에 qimUserId 평문 저장 — PII 비저장 원칙 위반

**증거 1** — `AuditLogPublisher.java:26`:
```
개인정보(CI/DN/이름) 포함 금지 — identifierHash, agencyCode만 허용
```

**증거 2** — `HandoffServiceImpl.java:247-248` `auditIssue()`:
```java
.actorType(AuditLogEvent.ACTOR_USER)
.actorId(cmd.getQimUserId())   // ← qimUserId 평문
```

**증거 3** — `AuditLogPublisher.insertAuditLog` SQL (라인 111-128):
```sql
INSERT INTO ido.audit_log (
    audit_id, event_category, event_action,
    actor_type, actor_id,    -- ← qimUserId 평문 컬럼
    ...
)
```

**문제 분석**:
- 주석은 "CI/DN/이름 포함 금지"만 명시 — qimUserId 는 회색 영역.
- qimUserId 자체는 UUID 라 직접 PII 가 아니지만, **qimUserId ↔ 실명/CI 매핑 테이블(Q-IM qim_user)** 이 있는 상태에서 audit_log + qim_user JOIN 시 누가 어떤 행위를 했는지 즉시 식별 가능 → **간접 식별자 = 개인정보보호법상 가명정보**.
- Kafka 토픽 `platform.audit.log` 에도 동일 데이터 전송 — Kafka cluster 침해 시 단일 지점 노출.
- 4년 보관 가정 시 (개인정보보호법 감사 로그 표준) 누적 데이터량 대비 노출 risk.

**개선안**:
1. `actor_id` 컬럼에 qimUserId 대신 **identifierHash (SHA-256(qimUserId))** 저장 → 역추적 가능하지만 직접 식별 불가.
2. qimUserId 원본은 별도 보호된 mapping 테이블에서 관리 → audit_log + mapping JOIN 권한을 분리.
3. Kafka 토픽 발행 시도 qimUserId hash 화.
4. Q-IM 의 `auth_mean_mapping`, `qim_user.identifier_hash` 정책과 일관성 확보.

---

### F5.5 [**High**] `HandoffCryptoService.verify()` 가 dead code — Phase 4 F4.1 의 KMS 측면 부작용

**증거**: Phase 4 F4.1 참조. 여기서는 KMS 관점의 부작용 강조:
- KeyVersionRegistry 의 HMAC 키가 매번 rotate 되어도 그 효과를 **verify 호출이 0건** 이므로 검증 시점에 받지 못함.
- 즉 키 로테이션의 비용만 발생하고 보안 이득은 발급 시점 한 번만 (Redis 저장된 ticket 의 signature 가 rotate 이후에도 유효 — 단, signature가 verify되지 않으므로 무용지물).

**개선안**: F4.1 의 즉각 수정.

---

### F5.6 [**High**] `HandoffCryptoService.decrypt` 의 레거시 폴백 — 버전 접두사 없는 암호문을 받아들이는 정책

**증거** — `HandoffCryptoService.java:107-112`:
```java
if (isVersioned(encryptedPayload)) {
    return decryptVersioned(encryptedPayload, aad);
} else {
    log.warn("[HandoffCrypto] 레거시 포맷 암호문 복호화 시도 (버전 접두사 없음)");
    return decryptLegacy(encryptedPayload, aad);
}
```

**문제**:
- 레거시 폴백이 영구적으로 활성 — 어떤 시점에 제거할 계획이 코드에 없음.
- 공격자가 일부러 버전 접두사 없는 형식으로 ciphertext 를 만들면 fallback 경로로 진입 → fallback 키가 약하거나 키 로테이션 정책 미적용 시 약한 키로 복호화 시도.
- WARN 로그는 모니터링되지 않으면 무시됨.

**개선안**:
1. Feature flag `IDO_LEGACY_CRYPTO_ALLOWED` 도입 — 기본 false, 운영 진입 후 일정 기간만 true.
2. `decryptLegacy` 호출 시 카운터 `ido_handoff_legacy_decrypt_total{aad}` 증가.
3. 운영 60일 후 카운터 0 확인 → 코드 제거.

---

### F5.7 [**High**] `AuditLogPublisher.publish()` 가 `@Async` — DB 저장도 비동기 → 서비스 응답 전에 audit log 미저장 시 사후 추적 불가

**증거** — `AuditLogPublisher.java:75-76`:
```java
@Async("auditExecutor")
public void publish(AuditEntry entry) {
```

**문제**:
- 메서드 전체가 `@Async` → DB INSERT 도 별도 스레드에서 실행.
- Critical security event (예: `TICKET_CONSUMED` REUSE_ATTEMPT, `AUTH_FAILED`, `RATE_LIMIT_EXCEEDED`) 가 DB 저장 전에 서비스 응답이 클라이언트로 반환됨.
- audit Executor 의 큐가 가득 차거나 thread pool exhaustion 시 → audit log 손실 → "이 사용자가 정말 이 행위를 했는가?" 사후 검증 불가.
- F-04 `dbSaveEnabled=true` 라도 비동기로 인한 손실 가능성 존재.

**개선안**:
1. **DB 저장만 동기**, Kafka 발행만 비동기 분리:
   ```java
   public void publish(AuditEntry entry) {
       if (dbSaveEnabled) insertAuditLog(...);  // 동기
       if (kafkaPublishEnabled) publishToKafkaAsync(...);  // 비동기
   }
   ```
2. Critical event 는 별도 메서드 `publishCritical()` 로 동기 DB 저장 강제.
3. auditExecutor 큐 길이 메트릭 + Grafana 알람.

---

### F5.8 [**High**] Audit log retry scheduler 의 LIMIT 200 — Kafka 장기 장애 시 재발행 적체

**증거** — `AuditLogPublisher.java:275-289`:
```java
@Scheduled(fixedDelayString = "${ido.audit.retry-interval-ms:600000}")  // 10분
public void retryKafkaPublish() {
    ...
    var unpublished = jdbcTemplate.queryForList("""
            SELECT ... LIMIT 200
            """);
```

**문제**:
- Kafka 가 1시간 down 동안 시간당 1000건 audit 발생 → 1000건 unpublished 누적.
- retry 가 10분마다 200건만 처리 → 1000건 ÷ 200건/10분 = 50분 + Kafka 복구 후에도 50분 더 누적분 처리.
- 그 사이 새로 생성된 audit 도 함께 경쟁 → 후입선출이 아닌 `ORDER BY occurred_at ASC` 이므로 오래된 것 우선 — 정상이나 처리 속도가 발생 속도를 따라잡지 못하면 영원히 backlog.

**개선안**:
1. Kafka 정상 복귀 시 burst 모드 (LIMIT 5000) 활성화 — Kafka 복구 감지 후 일시적으로 batch 증가.
2. Prometheus 메트릭: `ido_audit_unpublished_total` Gauge — 임계치 알람.
3. Kafka outbox pattern 으로 일관성 — 현재는 DB 저장과 Kafka 발행이 트랜잭션 분리.

---

### F5.9 [**High**] `AuditLogPublisher` 의 `dbSaveEnabled=false` 운영 진입 위험 — Feature Flag 가 컴플라이언스 우회 가능

**증거** — `AuditLogPublisher.java:65-67`:
```java
// ⚠️ 운영에서 false 금지 — 컴플라이언스(개인정보보호법) 위반 가능
@Value("${ido.audit.db-save-enabled:${IDO_AUDIT_DB_ENABLED:true}}")
private boolean dbSaveEnabled;
```

**문제**:
- 주석에 명시 "운영에서 false 금지" — 하지만 코드상 강제 차단은 없음.
- 운영 환경에서 `IDO_AUDIT_DB_ENABLED=false` 가 누군가 잘못 설정하면 즉시 모든 audit 누락 → 사후 발견 시 컴플라이언스 위반 (개인정보보호법 §28, §29).
- F4 보안 가이드라인은 "운영 환경에서 dbSaveEnabled 강제 true" 메커니즘 부재.

**개선안**:
```java
@PostConstruct
void verifyProdConfiguration() {
    if (env.acceptsProfiles(Profiles.of("prod")) && !dbSaveEnabled) {
        throw new IllegalStateException(
            "운영 환경에서 IDO_AUDIT_DB_ENABLED=false 설정 금지");
    }
}
```

---

### F5.10 [**High**] Vault `Transit decrypt` 에 AAD/context 미사용 — 키 노출 시 모든 ciphertext 즉시 복호화 가능

**증거** — `VaultKmsClient.java:360-371` `buildTransitBody`:
```java
if ("encrypt".equals(operation)) {
    return "{\"plaintext\":\"" + plainBase64 + "\"}";
}
```

**문제**:
- Vault Transit 은 `context` 파라미터를 통해 derived key (per-context unique key) 를 지원.
- 현재 구현은 `context` 없이 plaintext 만 보냄 → 모든 암호문이 같은 키로 복호화 가능.
- 만약 attacker 가 Vault 키 (`ido-handoff-key`) 에 일시 접근하면 **모든 과거 ciphertext** 즉시 평문화 가능.
- Vault context 사용 시: encrypt(plaintext, context=ticketId) → 같은 키지만 context 별 derived key → ticketId 모르면 복호화 불가.

**개선안**:
```java
return "{\"plaintext\":\"" + plainBase64 + "\", \"context\":\"" + base64(context) + "\"}";
```
context = ticketId 또는 agencyCode 사용.

---

### F5.11 [**Medium**] `KmsClient.providerName()` 의 메트릭/로그 노출 — KMS 종류 정보 leak

**증거**: 여러 KmsClient 구현체가 `providerName()` 으로 "vault" / "noop" / "local" / "nhn" / "any-id" 반환. 로그/메트릭에 그대로 노출.

**평가**:
- 운영 모니터링에 필요 — 정상.
- 단, Prometheus `/actuator/prometheus` 가 외부 노출되어 있으면 공격자가 "이 시스템은 Vault 사용" 같은 정찰 정보 획득.

**개선안**: Prometheus endpoint 는 내부망 전용 + Grafana proxy.

---

### F5.12 [**Medium**] `HandoffCryptoService` 의 환경변수 직접 의존 — KMS Off 모드에서 `IDO_HANDOFF_AES_KEY` 평문 환경변수

**증거** — 주석 (라인 33-34):
```
운영 시 IDO_HANDOFF_AES_KEY / IDO_HANDOFF_HMAC_KEY 환경변수 교체 필수.
두 키 모두 32바이트(256-bit) Base64URL 인코딩 문자열이어야 합니다.
```

**문제**:
- 환경변수에 32-byte 키 평문 (Base64URL) 저장 → K8s Secret 으로 주입.
- K8s Secret 은 etcd 에 base64 저장 (실질적 평문). RBAC 미흡 시 secret read 권한자 = 키 입수.
- KMS On 모드 (Vault) 에서는 KeyVersionRegistry → KmsClient.decrypt() 로 키 가져오지만, KMS Off 모드 (LocalKmsClient) 에서는 환경변수 직접 사용 가정.
- KeyVersionRegistry 의 실제 키 조회 경로 확인 필요 (별도 파일 분석 권장).

**개선안**:
1. KMS Off 모드 운영 금지 — F5.1 fail-fast 와 연계.
2. K8s Secret 대신 Vault Agent Injector 또는 CSI Secret Store 사용.

---

### F5.13 [**Medium**] Vault 인증 실패 시 토큰 재시도 — 1회만 시도 후 포기

**증거** — `VaultKmsClient.java:309-317`:
```java
private String callTransit(String operation, String ciphertext, String plainBase64) {
    try {
        return doCallTransit(operation, ciphertext, plainBase64);
    } catch (TokenExpiredException e) {
        log.info("[KMS-Vault] 토큰 만료 — 재획득 후 재시도");
        this.clientToken = acquireToken();
        return doCallTransit(operation, ciphertext, plainBase64);  // ← 두 번째 시도가 또 실패하면 그대로 예외
    }
}
```

**문제**:
- 두 번째 시도가 실패하면 `TokenExpiredException` 가 catch 되지 않음 → upstream 으로 전파.
- 의도된 동작이나, `acquireToken()` 이 일시적으로 실패한 후 다음 호출에서는 정상 동작할 수 있는데 한 번의 실패가 사용자 차단으로 이어짐.

**개선안**:
- 토큰 재획득에 retry exponential backoff (max 3 attempts).
- Circuit breaker 적용 — Vault 장애 시 KMS 호출 fast-fail + graceful degradation.

---

### F5.14 [**Medium**] `AuditLogPublisher` 의 metadata JSON 직렬화 실패 시 그냥 null 저장

**증거** — `AuditLogPublisher.java:186-194`:
```java
private String toJson(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) return null;
    try {
        return objectMapper.writeValueAsString(metadata);
    } catch (JsonProcessingException e) {
        log.warn("[AuditLogPublisher] metadata 직렬화 실패: {}", e.getMessage());
        return null;
    }
}
```

**문제**:
- metadata 에 직렬화 불가 객체 (예: 순환 참조, 비표준 클래스) 가 들어가면 → null 저장.
- 그 audit 이벤트는 outcome=SUCCESS 로 기록되지만 metadata=null → 사후 추적 시 핵심 정보 손실.
- 호출자는 metadata 가 누락된 줄 모름.

**개선안**:
- 직렬화 실패 시 outcome 에 부가 정보 표기: `{ "metadata_serialization_failed": "<exception class>" }` 라도 저장.
- 또는 직렬화 실패 자체를 audit 이벤트로 발행.

---

### F5.15 [**Low**] `VaultKmsClient.buildTransitBody` 의 JSON 수동 조합 — escape 누락 가능

**증거** — `VaultKmsClient.java:361-362, 369`:
```java
return "{\"plaintext\":\"" + plainBase64 + "\"}";
return "{\"ciphertext\":\"" + ciphertext + "\"}";
```

**문제**:
- 정상 케이스에서는 plainBase64 와 ciphertext 가 모두 base64 alphabet (영문/숫자/`+/=` 또는 `-_`) 이므로 escape 불필요.
- 그러나 잘못된 입력 (예: ciphertext 에 `"` 또는 backslash 포함) 시 JSON 깨짐 → Vault 가 400 반환.
- 입력 검증 없이 곧바로 문자열 concat — 일반적 보안 권고 위반.

**개선안**: `ObjectMapper.writeValueAsString(Map.of("plaintext", plainBase64))` 사용.

---

### F5.16 [**Low**] `LocalKmsClient` 의 `decodeBase64Flexible` — URL-safe 와 standard Base64 모두 허용

**증거** — `NoOpKmsClient.java:44-49` (LocalKmsClient 도 유사):
```java
String normalized = encryptedKeyBase64
        .replace('-', '+').replace('_', '/');
int pad = normalized.length() % 4;
if (pad == 2) normalized += "==";
else if (pad == 3) normalized += "=";
```

**평가**:
- 호환성을 위한 유연한 처리 — 정상.
- 단, 형식 검증 없이 수동 padding 보정 → 의도하지 않은 입력에 대해 silent recovery → 디버깅 어려움.

**개선안**: 형식이 명확한 입력만 받도록 strict 모드 + 위반 시 명시적 예외.

---

## 2. Catastrophic Scenarios

### Scenario M: K8s ConfigMap 에서 `IDO_KMS_ENABLED` 누락 → 모든 키 평문 운영

1. Helm chart 업그레이드 중 `IDO_KMS_ENABLED` 환경변수 정의 누락 (typo, 또는 chart 분기 시 leak).
2. 새 IdO Pod 기동 시 LocalKmsClient 활성 (matchIfMissing=true).
3. 새 key version 생성 시 LocalKmsClient.encrypt() = Base64 인코딩만 → DB `key_material_encrypted` 에 평문 Base64 키 저장.
4. 운영자가 Pod 정상 기동을 확인하고 release 승인.
5. 며칠 후 (또는 수개월 후) DB 침해 시 모든 키 즉시 입수 → handoff ticket, CI, DI 모두 평문화.

**현재 방어선**: 0 (`matchIfMissing=true` + startup health check 미존재).

---

### Scenario N: Vault 1시간 장애 → AppRole 토큰 만료 시점 사용자 차단 + audit log 손실

1. Vault 서버 unsealed 상태 유지 작업 (자가 호스팅) 또는 HCP Vault 일시 장애.
2. AppRole 토큰 TTL=1h 만료.
3. IdO 의 모든 handoff verify 가 KmsDecryptException → 사용자 차단.
4. 동시에 audit log retry scheduler 도 KMS 호출 없으니 정상이지만, 만약 audit 본문에 ciphertext 가 있다면 (현재 코드상 없음) 같이 실패.
5. Vault 복구 후 IdO 의 첫 호출이 토큰 재획득 시도 → 또 다른 transient 실패 → 부분적 차단 연장.

**완화 가능 수단**: F5.3 백그라운드 토큰 갱신.

---

### Scenario O: Audit log Kafka 1일 down + DB save 정상 → 다음날 복구 시 retry scheduler backlog

1. Kafka cluster 1일 (24h) 장애. DB INSERT 는 정상 계속.
2. 1일 동안 100,000건 audit 누적 (`kafka_published=false`).
3. Kafka 복구 후 retry scheduler 가 10분마다 200건 = 시간당 1,200건 처리 → 100,000 / 1,200 = **83시간 = 3.5일** 소요.
4. 그 사이 새 audit 도 발생 → backlog 정상화 불가능.
5. 다운스트림 (SIEM, log archive) 가 Kafka 만 consume 하면 3.5일간 사후 분석 누락.

**완화**: F5.8 burst 모드.

---

### Scenario P: actor_id 평문 + DB 침해 = 즉시 사용자 행위 식별

1. ido.audit_log DB read 권한자 (DBA, BI팀, 외주 컨설턴트) 가 SQL 한 줄로 `SELECT actor_id, event_action, occurred_at FROM ido.audit_log` 실행.
2. qimUserId 평문이라 즉시 사용자 식별 (qim_user 테이블 JOIN 으로 실명/CI 추적 가능).
3. 4년 보관된 누적 audit 가 한꺼번에 노출.

**컴플라이언스**:
- 개인정보보호법 §29 안전조치 의무 — "암호화" 적용 의무 (가명처리만으로는 부족할 수 있음).
- GDPR Article 32 — Pseudonymisation. qimUserId 는 가명정보로 충분하나, Q-IM mapping 테이블 접근 권한 분리 필수.

**완화**: F5.4 identifierHash 사용.

---

## 3. Positive Verifications

| # | 항목 | 평가 |
|---|------|------|
| ✅ V5.1 | `VaultKmsClient` 의 Kubernetes SA 토큰 자동 사용 | K8s 운영 최적 |
| ✅ V5.2 | Vault `min_decryption_version` + `auto_rotate_period=2160h` 가이드 | 키 로테이션 표준 |
| ✅ V5.3 | `HandoffCryptoService` 의 AES-256-GCM (FIPS 권장) + 128-bit auth tag | 표준 강도 |
| ✅ V5.4 | GCM AAD = ticketId | ciphertext 와 ticketId 바인딩 → swap 공격 방지 |
| ✅ V5.5 | KeyVersionRegistry 분리 — 키 버전 별 추적 가능 | 로테이션 인프라 |
| ✅ V5.6 | `HandoffCryptoService.sign/verify` 의 `MessageDigestUtil.safeEquals` | timing attack 방어 |
| ✅ V5.7 | `AuditLogPublisher` 의 DB 우선 + Kafka 후행 패턴 | Kafka 장애 시 audit 보존 |
| ✅ V5.8 | retry scheduler 의 `LIMIT 200 ORDER BY occurred_at ASC` | FIFO 보장 |
| ✅ V5.9 | Vault 헬스 체크 별도 분리 (`VaultKmsHealthIndicator`) | Spring Boot Actuator 통합 |
| ✅ V5.10 | KmsHealthMetrics (PR-B1) 의 Gauge 메트릭 | 운영 모니터링 가능 |
| ✅ V5.11 | `AuditLogEvent.OUTCOME_SUCCESS / OUTCOME_FAILURE` 상수화 | 일관된 추적 |
| ✅ V5.12 | audit log Kafka 발행 실패가 서비스 차단 안 함 | non-blocking 정상 |
| ✅ V5.13 | `KmsDecryptException`/`KmsEncryptException` 분리 | 호출자 분기 가능 |

---

## 4. 종합 평가

### KMS 인프라 성숙도: ⚠️ 좋으나 fail-safe 부재
- Vault Transit 통합, Kubernetes 인증, 키 버전 관리 등 핵심 인프라는 잘 갖춰짐.
- 그러나 **fail-safe 설계가 정반대** (matchIfMissing=true) — 운영 진입 직전 환경변수 검증 강화 필수.
- 토큰 라이프사이클 자동 관리 미구현.

### 개인정보 비저장 원칙: ⚠️ qimUserId 평문 audit log 의 회색 영역
- 주석은 "CI/DN/이름 금지"만 명시하나 실제 qimUserId 평문 저장.
- 가명정보 처리는 충족하나 4년 누적 시 위험 증가.
- 컴플라이언스 자문 + 정책 명확화 필요.

### Audit log 추적성: ⚠️ @Async 의 양면성
- DB 우선 + Kafka 후행 패턴은 우수.
- 그러나 `@Async` 가 DB 저장까지 비동기로 만들어 critical event 손실 가능.

### 운영 진입 차단 사유: ✗
- **F5.1** (matchIfMissing fail-safe 정반대) + **F5.2** (Vault 토큰 미획득 시 startup 차단 부재) + **F5.4** (qimUserId 평문) — 운영 진입 직전 즉시 보완 필요.

---

## 5. 다음 단계 — Phase 6 입력

Phase 6 (`06_e2e_scenarios.md`) 에서:
- 전체 SSO 플로우 (Q-Sign → IdO → Q-IM → 기관) 의 E2E 시나리오 시뮬레이션
- Phase 2~5 에서 발견된 결함이 실제 사용자 케이스에서 어떻게 발현되는지 통합
- 정상 흐름 vs 부분 실패 시나리오 vs 보안 침해 시나리오 분류
- 운영 진입 차단 시나리오 목록 작성

**Phase 5 분석 종료**.
**결론**: KMS/Privacy/Audit 영역에서 **4개 Critical + 6개 High** 결함 확인. 운영 진입 차단 사유 누적.
