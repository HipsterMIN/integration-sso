# IdO 백엔드 팀 개발 가이드

> **버전**: v2.3.0 (Sprint 10 기준)  
> **최종 수정**: 2026-05-11  
> **대상**: IdO 모듈 백엔드 개발팀  
> **포트**: 8083  
> **기술 스택**: Spring Boot 3.5 / Java 21 / Gradle / PostgreSQL / Redis / Kafka

---

## 목차

1. [모듈 개요 및 책임 경계](#1-모듈-개요-및-책임-경계)
2. [개발 환경 설정](#2-개발-환경-설정)
3. [패키지 구조 및 설계 원칙](#3-패키지-구조-및-설계-원칙)
4. [Feature Flag 개발 가이드](#4-feature-flag-개발-가이드)
5. [SLO API 상세 (Sprint 10 완성)](#5-slo-api-상세)
6. [NICE/OACX 본인인증 BFF (S7-T2)](#6-niceoacx-본인인증-bff)
7. [회원정보 수정 API 연동](#7-회원정보-수정-api-연동)
8. [AES 키 로테이션](#8-aes-키-로테이션)
9. [Handoff 발급/검증 흐름](#9-handoff-발급검증-흐름)
10. [감사 로그 (Audit Log)](#10-감사-로그)
11. [Rate Limiting](#11-rate-limiting)
12. [Kafka 이벤트 처리](#12-kafka-이벤트-처리)
13. [테스트 작성 가이드](#13-테스트-작성-가이드)
14. [자주 발생하는 오류 및 해결](#14-자주-발생하는-오류-및-해결)
15. [코딩 컨벤션 및 체크리스트](#15-코딩-컨벤션-및-체크리스트)

---

## 1. 모듈 개요 및 책임 경계

### IdO의 역할

IdO(Identity Orchestrator)는 **정책 오케스트레이터 + FE BFF** 역할을 담당합니다.

```
┌─────────────────────────────────────────────────────────────┐
│  IdO :8083 — 유일한 외부 공개 서비스                          │
│                                                             │
│  ① FE BFF        — feSessionId 쿠키, ReturnUrl 검증         │
│  ② 정책 엔진      — 속성 필터링, agencySubjectId HMAC        │
│  ③ Handoff       — 발급/검증 + AES-256-GCM 암호화           │
│  ④ 본인인증 BFF   — NICE/OACX 중계 (S7-T2)                  │
│  ⑤ SLO           — FE 로그아웃 수신, Keycloak 전파 (S10)    │
│  ⑥ Webhook       — 기관 이벤트 Push + Outbox Relay          │
│  ⑦ 감사 로그      — Kafka 발행 + DB 저장 (이중화)            │
└─────────────────────────────────────────────────────────────┘
```

### IdO가 직접 하지 않는 것 (SoR 분리)

| 행위 | 담당 모듈 | 이유 |
|------|---------|------|
| Keycloak 세션 종료 | Q-Sign (`/api/v1/internal/slo`) | 인증 SoR |
| CI 저장/복호화 | Q-IM (`/api/v1/internal/member/*`) | 식별 SoR |
| JWT 토큰 발급 | Q-Sign | 인증 SoR |
| 회원 원장 관리 | Q-IM | 식별 SoR |

---

## 2. 개발 환경 설정

### 로컬 실행 (Docker 없이)

```bash
# 1. Feature Flag 모두 OFF (외부 의존성 없이 기동)
export DOCKER_UNAVAILABLE=true

# 2. ido만 컴파일 검증
DOCKER_UNAVAILABLE=true ./gradlew :ido:compileJava --no-daemon -q

# 3. 로컬 프로파일로 실행
./gradlew :ido:bootRun --args='--spring.profiles.active=local'
```

### application-local.yml 필수 설정

```yaml
# ido/src/main/resources/application-local.yml
ido:
  auth-rl:
    enabled: false           # IP Rate Limit 끄기 (로컬 반복 테스트)
  audit:
    kafka-enabled: false     # Kafka 없을 때
    db-enabled: false        # DB 감사 로그 선택적
  redisson:
    enabled: false           # Redis 없을 때 반드시 끄기 (없으면 기동 실패)
  security-headers:
    enabled: false           # CSP 헤더 끄기 (FE 개발 편의)
  tracing:
    auth-aspect-enabled: false  # OTel 없을 때
  outbox-relay:
    enabled: false
  webhook-relay:
    enabled: false
  retention:
    enabled: false           # 개인정보 파기 — 로컬에서는 절대 켜지 마세요!

spring:
  kafka:
    bootstrap-servers: localhost:9092  # Kafka 없으면 application-local.yml에서 비활성화
```

### 환경변수 전체 목록

```bash
# 필수 (운영)
NICE_CLIENT_ID=<NICE 클라이언트 ID>
NICE_CLIENT_SECRET=<NICE 클라이언트 시크릿>
NICE_RETURN_URL=https://www.smes.go.kr/otp/auth-result
OACX_PROVIDER_KEY_PATH=/secrets/oacx-provider-keys.json
INTEGRATION_AUTH_BASE_URL=https://auth-server.smes.go.kr

# Feature Flag 환경변수 (K8s ConfigMap)
IDO_AUTH_RL_ENABLED=true          # IP Auth Rate Limiting
IDO_RATE_LIMIT_ENABLED=true       # 기관별 Rate Limiting
IDO_AUDIT_DB_ENABLED=true         # 감사 로그 DB 저장
IDO_TRACING_AUTH_ASPECT_ENABLED=true  # OTel AOP 추적
IDO_REDISSON_ENABLED=true         # Redisson 분산 락
IDO_SECURITY_HEADERS_ENABLED=true # 보안 응답 헤더
IDO_RETENTION_ENABLED=false       # 개인정보 파기 (운영에서만 true)
IDO_RETENTION_DRY_RUN=false       # true이면 실제 삭제 안 함
IDO_RETENTION_PERSONAL_DATA_DAYS=90
IDO_RETENTION_BATCH_SIZE=100
IDO_OUTBOX_RELAY_ENABLED=true
IDO_WEBHOOK_RELAY_ENABLED=true
```

---

## 3. 패키지 구조 및 설계 원칙

```
ido/src/main/java/kr/go/smes/ido/
├── admin/              # 기관 Admin API (CRUD)
├── api/                # Handoff + 기관 이벤트 폴링
│   └── handoff/        # POST /api/v1/handoff/issue, verify
├── auth/               # NICE/OACX 본인인증 BFF (S7-T2)
│   ├── client/         #   외부 서비스 WebClient
│   ├── config/         #   AuthProperties, WebClientConfig
│   ├── controller/     #   AuthController (6개 엔드포인트)
│   ├── dto/            #   요청/응답 DTO
│   ├── service/        #   NiceAuthService, AuthService
│   ├── store/          #   NiceTokenStore, NiceAuthSessionStore (Redis)
│   └── util/           #   NiceCryptoUtil (PBKDF2+HMAC+AES-GCM)
├── broker/             # IdP 브로커 + OIDC 처리
├── config/             # 전역 설정 (Security, CORS, Filter)
├── crypto/             # KeyVersionRegistry, 키 로테이션 스케줄러
├── fe/                 # FE 세션 관리
│   └── session/        #   FeSessionController, FeSessionService
├── handoff/            # Handoff 핵심 로직
│   ├── crypto/         #   HandoffCryptoService (AES-256-GCM)
│   └── strategy/       #   HandoffStrategy 패턴 (4종)
├── kafka/              # Kafka 이벤트 컨슈머
├── metrics/            # Micrometer 메트릭
├── policy/             # PolicyEngine (속성 필터링)
├── qim/                # Q-IM 내부 API 클라이언트 (ImApiOutPort)
├── ratelimit/          # Redis Lua 슬라이딩 윈도우
├── slo/                # SLO API ← Sprint 10 FE 연동 완성
│   ├── SloController.java    # POST /api/v1/slo/initiate
│   ├── SloService.java
│   └── SloServiceImpl.java
└── webhook/            # Webhook Push + Outbox Relay
```

### 핵심 설계 원칙

1. **평탄화 계층 구조** — 헥사고날 미적용, 기능별 패키지 분리
2. **SoR 분리 원칙** — CI/회원 원장은 Q-IM, 인증은 Q-Sign에 위임
3. **비치명적 처리** — SLO, Webhook 실패 시 예외 삼킴 + 로그 (전체 흐름 중단 방지)
4. **멱등성 설계** — SLO, Handoff verify 모두 중복 호출 시 동일 결과 보장

---

## 4. Feature Flag 개발 가이드

> 전체 18개 Flag 상세: [`docs/FEATURE_FLAGS.md`](../FEATURE_FLAGS.md)

### 새 Feature Flag 추가 방법

**패턴 A: `@Value` + Guard (런타임 조건 분기)**

```java
// 서비스 클래스에 직접 추가
@Service
@RequiredArgsConstructor
@Slf4j
public class NewFeatureServiceImpl implements NewFeatureService {

    @Value("${ido.new-feature.enabled:false}")  // 로컬 기본값 false
    private boolean enabled;

    @Override
    public void execute() {
        if (!enabled) {
            log.debug("[NewFeature] 기능 비활성화 — ido.new-feature.enabled=false");
            return;
        }
        // 실제 로직
    }
}
```

**패턴 B: `@ConditionalOnProperty` (빈 등록 자체 제어)**

```java
// 외부 의존성이 있는 빈(Redis, Kafka 필수 등)에 사용
@Configuration
@ConditionalOnProperty(
    name = "ido.redisson.enabled",
    havingValue = "true",
    matchIfMissing = false  // 기본: 빈 미등록
)
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() { ... }
}
```

### K8s ConfigMap 반영

`infra/k8s/configmaps/ido-configmap.yml`에 환경변수 추가:

```yaml
# infra/k8s/configmaps/ido-configmap.yml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ido-config
data:
  # 기존 Flag ...
  IDO_NEW_FEATURE_ENABLED: "false"   # 신규 추가
```

`application.yml`에 바인딩:

```yaml
ido:
  new-feature:
    enabled: ${IDO_NEW_FEATURE_ENABLED:false}
```

---

## 5. SLO API 상세

> **Sprint 10 완성**: 백엔드 SLO는 Sprint 2에서 구현됐고, Sprint 10에서 FE 연동 완료.

### API 명세

```
POST /api/v1/slo/initiate
Cookie: feSessionId=<세션 ID>
Content-Type: application/json (body 불필요)

응답:
  204 No Content — 성공 (feSessionId 없어도 204 — 멱등성)
  Set-Cookie: feSessionId=; Max-Age=0; HttpOnly; Secure; SameSite=Strict
```

### 처리 흐름

```java
// SloController.java
@PostMapping("/initiate")
public ResponseEntity<Void> initiate(
        @CookieValue(value = COOKIE_NAME, required = false) String feSessionId,
        HttpServletResponse response) {

    String correlationId = CorrelationIdHolder.get();

    // 1. feSessionId 없으면 멱등성 처리 (이미 로그아웃 상태)
    if (feSessionId == null || feSessionId.isBlank()) {
        log.info("[SLO] feSessionId 없음, 멱등 처리 cid={}", correlationId);
        return ResponseEntity.noContent().build();
    }

    // 2. feSession Redis 삭제
    FeSession session = feSessionService.expire(feSessionId);

    // 3. SLO 실행 (비치명적 — 실패해도 204 반환)
    sloService.executeSlo(session, correlationId);

    // 4. 쿠키 제거
    ResponseCookie clearCookie = ResponseCookie.from(COOKIE_NAME, "")
        .maxAge(0).httpOnly(true).secure(true).sameSite("Strict").path("/").build();
    response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

    // 5. 메트릭 기록
    sloMetrics.recordSuccess();

    return ResponseEntity.noContent().build();
}
```

### SloServiceImpl — executeSlo()

```java
@Override
public void executeSlo(FeSession session, String correlationId) {
    // ① Keycloak 세션 종료 (비치명적)
    try {
        qSignClient.callEndSession(session.getKeycloakSessionId(), correlationId);
    } catch (Exception e) {
        log.warn("[SLO] Keycloak end_session 실패 (무시) cid={} err={}", correlationId, e.getMessage());
    }

    // ② 기관 로그아웃 Webhook Outbox 적재 (비치명적)
    try {
        webhookOutboxService.enqueueLogout(session.getAgencyId(), correlationId);
    } catch (Exception e) {
        log.warn("[SLO] Webhook Outbox 적재 실패 (무시) cid={} err={}", correlationId, e.getMessage());
    }

    // ③ 감사 로그 기록
    auditLogService.record(AuditLogEvent.sloInitiated(session, correlationId));
}
```

### 수정/보완 가이드

**SLO에 새 단계 추가 시**:
1. `SloServiceImpl.executeSlo()` 메서드 내 `// ③` 이후에 try-catch 블록으로 추가
2. 반드시 비치명적으로 처리 — `try { ... } catch (Exception e) { log.warn(...); }` 패턴
3. `sloMetrics`에 관련 카운터/타이머 추가
4. 단위 테스트 작성 (`SloServiceImplTest.java`)

**Keycloak end_session URL 변경 시**:
```yaml
# application.yml
ido:
  slo:
    keycloak-end-session-url: ${KEYCLOAK_END_SESSION_URL:http://localhost:8080/realms/onepass/protocol/openid-connect/logout}
```

---

## 6. NICE/OACX 본인인증 BFF

> 전체 API 명세: [`docs/api-auth-spec.md`](../api-auth-spec.md)

### 패키지 구조

```
ido/src/main/java/kr/go/smes/ido/auth/
├── client/
│   ├── NiceApiClient.java           # NICE IDO API (Access Token, URL발급, 결과조회)
│   ├── OacxClient.java              # OACX SDK v1.3.2 래퍼
│   └── IntegrationAuthClient.java   # 통합인증 서버 WebClient
├── config/
│   ├── AuthProperties.java          # @ConfigurationProperties("ido.auth")
│   └── AuthWebClientConfig.java     # WebClient @Bean (niceWebClient, oacxWebClient)
├── controller/
│   └── AuthController.java          # 6개 REST 엔드포인트
├── dto/                             # 13개 DTO
├── service/
│   ├── NiceAuthService.java         # NICE 휴대폰 인증 전체 플로우
│   └── AuthService.java            # OACX 간편서명, 기업인증 콜백, CI 확인
├── store/
│   ├── NiceTokenStore.java          # Redis — NICE Access Token 캐시
│   └── NiceAuthSessionStore.java    # Redis — NICE 인증 세션 (TTL 10분)
└── util/
    └── NiceCryptoUtil.java          # PBKDF2(512bit) → HMAC-SHA256 → AES-256-GCM
```

### NICE 암호화 처리 순서

```java
// NiceCryptoUtil — NICE IDO 스펙 준수
// 1. PBKDF2WithHmacSHA256 키 파생 (512bit)
String keyString = NiceCryptoUtil.deriveKey(ticket, transactionId, iterators);

// 2. HMAC 키 추출 (인덱스 48~79, 32바이트)
String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);

// 3. HMAC-SHA256 무결성 검증
String computedHmac = NiceCryptoUtil.hmacSha256Base64Url(encData, hmacKey);
// → integrityValue와 불일치 시 DataIntegrityException 발생

// 4. AES 키 추출 (인덱스 0~31, 32바이트)
byte[] aesKey = NiceCryptoUtil.extractAesKey(keyString);

// 5. AES-256-GCM 복호화 (앞 12바이트: GCM IV, 나머지: 암호문+인증태그)
String plainText = NiceCryptoUtil.aesGcmDecrypt(aesKey, encDataBase64Url);
```

### PII 보호 원칙 (Q3=B)

```java
// OacxEasysignResponse.java — CI는 null로 설정, JSON 직렬화 시 제외
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OacxEasysignResponse {
    private String name;
    private String birthday;
    private String phone;
    private String ci;    // 항상 null로 반환 (Q3=B 정책)
}
```

**주의**: CI를 FE에 반환하도록 코드를 수정하면 안 됩니다. CI는 백엔드 내부 처리 전용입니다.

### 새 본인인증 방식 추가 가이드

1. `auth/client/` — 새 인증 서버용 WebClient 빈 추가 (`AuthWebClientConfig.java`)
2. `auth/dto/` — 요청/응답 DTO 추가
3. `auth/service/` — 새 인증 서비스 구현
4. `auth/controller/AuthController.java` — 엔드포인트 추가 (Javadoc 필수)
5. `docs/api-auth-spec.md` — API 명세 업데이트
6. `AuthProperties.java` — 환경변수 바인딩 추가

---

## 7. 회원정보 수정 API 연동

> FE의 `InformationStep3.tsx`가 호출하는 외부 API (Sprint 10에서 FE 연동 완성)

### 외부 API 명세 (Q-IM 노출 엔드포인트)

```
# 개인회원 정보 수정
PATCH /api/ext/members/{mbrNo}
Content-Type: application/json
X-API-Key: <ext API 키>

Body (UpdateMemberRequest):
{
    "memberName": "홍길동",        // 필수
    "phone": "010-1234-5678",     // 선택
    "email": "hong@example.com"   // 선택
}

응답: 200 OK + MemberResponse

# 기업회원 정보 수정
PATCH /api/ext/enterprises/{entMbrNo}
Content-Type: application/json
X-API-Key: <ext API 키>

Body (UpdateEnterpriseRequest):
{
    "bzmnNm": "테스트 주식회사",    // 필수
    "rprsvNm": "홍길동",           // 필수
    "rprsTelno": "02-1234-5678",  // 선택
    "email": "info@example.com"   // 선택
}

응답: 200 OK + EnterpriseResponse
```

### 전화번호/이메일 포맷 규칙

- **전화번호**: `{지역코드}-{중간}-{끝}` 형식 필수 (`010-1234-5678`, `02-123-4567`)
- **이메일**: RFC 5321 표준 (`user@example.com`)
- 포맷 위반 시 400 Bad Request 반환 (Bean Validation)

### 백엔드 수정이 필요한 경우

**수정 가능한 필드 추가 시**:
1. `UpdateMemberRequest` / `UpdateEnterpriseRequest` DTO 필드 추가
2. Q-IM 서비스 레이어에서 해당 필드 처리 로직 추가
3. FE의 `types/api/ext/members.ts` 타입 업데이트 (FE 팀 협업)
4. Flyway 마이그레이션 추가 (컬럼 추가 시)

---

## 8. AES 키 로테이션

> Sprint 5에서 완성. Handoff Payload 암호화에 사용.

### 키 버전 포맷

```
{평문} → {v{n}.{iv(Base64)}.{ciphertext(Base64)}}

예시:
v1.abc123def456.zyxwvutsrqp...
└─┘ └──────────┘ └──────────┘
버전  GCM IV(12B)  암호문+인증태그
```

### 키 로테이션 스케줄러

```java
// HandoffKeyRotationScheduler.java
@Scheduled(cron = "0 0 2 */90 * *")  // 90일마다 새벽 2시
public void rotate() {
    // Redisson 분산 락으로 다중 Pod에서 한 번만 실행
    RLock lock = redissonClient.getLock("handoff:key-rotation:lock");
    if (!lock.tryLock()) return;
    try {
        keyVersionRegistry.generateNewVersion();
        log.info("[KeyRotation] 새 버전 생성 완료: v{}", keyVersionRegistry.getCurrentVersion());
    } finally {
        lock.unlock();
    }
}
```

### 긴급 키 교체 방법

```bash
# 1. Admin API로 수동 로테이션 트리거 (운영 환경)
curl -X POST http://localhost:8083/api/admin/crypto/rotate-key \
  -H "X-Admin-Key: ${ADMIN_API_KEY}"

# 2. 로테이션 후 이전 버전 복호화 가능 여부 확인
# KeyVersionRegistry가 모든 버전 키를 메모리+Redis에 유지
# 구버전 Ticket은 해당 버전 키로 자동 복호화됨
```

---

## 9. Handoff 발급/검증 흐름

### 발급 (POST /api/v1/handoff/issue)

```
기관 → IdO: POST /api/v1/handoff/issue
  {
    "agencyCode": "AGENCY_001",
    "returnUrl": "https://agency.go.kr/callback",
    "requestedAttributes": ["name", "phone"]
  }
  Header: X-Agency-Api-Key: <키>

IdO 처리:
  1. API Key 검증 (PBKDF2 상수시간 비교)
  2. 기관별 Rate Limit 체크 (Redis Lua)
  3. PolicyEngine — 속성 필터링 (허용된 속성만)
  4. HandoffCryptoService — AES-256-GCM 암호화 (v{n}.{iv}.{ct})
  5. Handoff Ticket 생성 (UUID v7)
  6. ido.handoff.events Kafka 발행 (Outbox)
  
기관 ← IdO: { "ticketId": "019...", "ticket": "v1.abc..." }
```

### 검증 (POST /api/v1/handoff/verify)

```
기관 → IdO: POST /api/v1/handoff/verify
  {
    "ticketId": "019...",
    "ticket": "v1.abc..."
  }

IdO 처리:
  1. Ticket 복호화 (버전 감지 → 해당 버전 키)
  2. 만료 시간 검증 (3분 이내)
  3. 1회 소비 처리 (Redis SET NX)
  4. HandoffPayload 반환
  
기관 ← IdO: { "mbrNo": "...", "name": "홍길동", "phone": "010-****-5678" }
```

### 새 HandoffStrategy 추가

```java
// handoff/strategy/ 디렉토리에 구현체 추가
@Component
public class NewHandoffStrategy implements HandoffStrategy {

    @Override
    public boolean supports(String agencyType) {
        return "NEW_TYPE".equals(agencyType);
    }

    @Override
    public HandoffPayload buildPayload(FeSession session, Agency agency) {
        // 전략별 페이로드 구성
    }
}
// → HandoffStrategyRouter에 자동 등록 (List<HandoffStrategy> 주입)
```

---

## 10. 감사 로그 (Audit Log)

### 이벤트 발행 방법

```java
// AuditLogService 주입 후 사용
auditLogService.record(
    AuditLogEvent.builder()
        .eventType(AuditEventType.SLO_INITIATED)
        .correlationId(correlationId)
        .mbrNo(session.getMbrNo())
        .agencyCode(session.getAgencyCode())
        .timestamp(Instant.now())
        .build()
);
```

### 이중화 처리 (Kafka + DB)

```java
// F-03: Kafka 발행 (IDO_AUDIT_KAFKA_ENABLED)
// F-04: DB 저장 (IDO_AUDIT_DB_ENABLED)
// 두 옵션 모두 OFF여도 앱은 정상 동작
```

### 새 감사 이벤트 타입 추가

1. `AuditEventType` 열거형에 새 타입 추가
2. `AuditLogEvent`에 팩토리 메서드 추가
3. Flyway 마이그레이션으로 `audit_log` 테이블 `event_type` ENUM 확장 (PostgreSQL에서는 ALTER TYPE)
4. `docs/FEATURE_FLAGS.md` 업데이트 (F-03/F-04 관련)

---

## 11. Rate Limiting

### 두 가지 Rate Limiter

| 종류 | 환경변수 | 대상 | 알고리즘 |
|------|---------|------|---------|
| 기관별 RL | `IDO_RATE_LIMIT_ENABLED` | 기관 API Key | Redis Lua 슬라이딩 윈도우 |
| IP Auth RL | `IDO_AUTH_RL_ENABLED` | 본인인증 IP | Redis Lua 슬라이딩 윈도우 |

### Rate Limit 초과 시 응답

```json
HTTP 429 Too Many Requests
{
    "error": "RATE_LIMIT_EXCEEDED",
    "message": "요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.",
    "retryAfterSeconds": 60
}
```

### 한도 조정 방법

```yaml
# application.yml
ido:
  rate-limit:
    agency:
      tps: 100          # TPS 한도
      daily-quota: 10000  # 일별 쿼터
    auth-rl:
      per-minute: 30    # IP당 분당 요청 수
      window-seconds: 60
```

---

## 12. Kafka 이벤트 처리

### 컨슈머 종류

| 컨슈머 | 토픽 | 처리 내용 |
|--------|------|---------|
| `AuthEventConsumer` | `qsign.auth.events` | 인증 성공 이벤트 → 세션 생성 |
| `HandoffEventConsumer` | `ido.handoff.events` | Handoff 이벤트 → Webhook 트리거 |
| `SessionAdvisoryConsumer` | `platform.session.advisory` | 세션 무효화 지시 |
| `QimUserEventConsumer` | `qim.user.events` | Q-IM 회원 이벤트 동기화 |

### Outbox 패턴 (멱등성 보장)

```java
// IdempotentEventStore로 중복 처리 방지
@KafkaListener(topics = "qsign.auth.events")
public void consume(AuthEvent event) {
    if (idempotentEventStore.isProcessed(event.getEventId())) {
        log.debug("[AuthEvent] 이미 처리된 이벤트 무시: {}", event.getEventId());
        return;
    }
    // 처리 로직
    idempotentEventStore.markProcessed(event.getEventId());
}
```

### DLQ 처리 (현재 미구현 — GAP-IDO-09)

```java
// TODO: DLQ 전략 구현 필요
// 현재: 예외 발생 시 Kafka 재시도 (기본 3회) 후 로그만 기록
// 목표: *.dlq 토픽으로 이동 + 운영팀 알림
```

---

## 13. 테스트 작성 가이드

### 테스트 구조

```
ido/src/test/java/kr/go/smes/ido/
├── auth/
│   ├── NiceAuthServiceTest.java      # NICE 인증 서비스 (12개)
│   ├── NiceCryptoUtilTest.java       # 암호화 유틸 (10개)
│   └── OacxClientTest.java          # OACX 클라이언트 (10개)
├── handoff/
│   └── HandoffServiceImplTest.java   # Handoff (18개)
├── slo/
│   └── SloServiceImplTest.java       # SLO (기존)
└── webhook/
    └── WebhookDispatcherServiceTest.java  # Webhook (33개)
```

### 테스트 작성 패턴

```java
@ExtendWith(MockitoExtension.class)
class SloServiceImplTest {

    @InjectMocks
    private SloServiceImpl sloService;

    @Mock
    private QSignClient qSignClient;

    @Mock
    private WebhookOutboxService webhookOutboxService;

    @Mock
    private AuditLogService auditLogService;

    @Test
    @DisplayName("정상 SLO — Keycloak + Webhook + 감사로그 모두 호출")
    void executeSlo_success() {
        // given
        FeSession session = FeSession.builder()
            .keycloakSessionId("kc-session-1")
            .agencyId("AGENCY_001")
            .mbrNo("MBR-001")
            .build();

        // when
        sloService.executeSlo(session, "corr-001");

        // then
        verify(qSignClient).callEndSession("kc-session-1", "corr-001");
        verify(webhookOutboxService).enqueueLogout("AGENCY_001", "corr-001");
        verify(auditLogService).record(any(AuditLogEvent.class));
    }

    @Test
    @DisplayName("Keycloak 호출 실패해도 SLO 완료 (비치명적)")
    void executeSlo_keycloakFail_continuesGracefully() {
        // given
        doThrow(new RuntimeException("Keycloak down"))
            .when(qSignClient).callEndSession(anyString(), anyString());

        FeSession session = FeSession.builder().keycloakSessionId("kc-1").build();

        // when: 예외 없이 완료되어야 함
        assertDoesNotThrow(() -> sloService.executeSlo(session, "corr-001"));

        // then: Webhook과 감사로그는 여전히 호출됨
        verify(webhookOutboxService).enqueueLogout(any(), any());
        verify(auditLogService).record(any());
    }
}
```

---

## 14. 자주 발생하는 오류 및 해결

### 오류 1: 앱 기동 실패 — Redisson 빈 미발견

```
Error: No qualifying bean of type 'RedissonClient' available
```

**원인**: `IDO_REDISSON_ENABLED=true`인데 Redis가 없거나 연결 실패  
**해결**: 로컬에서는 `IDO_REDISSON_ENABLED=false` (또는 `application-local.yml`에 설정)

```yaml
ido:
  redisson:
    enabled: false
```

---

### 오류 2: NICE 암호화 검증 실패

```
DataIntegrityException: HMAC 검증 실패
```

**원인**: NICE API Access Token 만료 후 캐시 미갱신 (60초 여유 재발급 로직 확인)  
**해결**: `NiceTokenStore.ensureValidToken()` 호출 흐름 점검

```java
// NiceAuthService.java — 토큰 갱신 로직
private String getValidAccessToken() {
    return niceTokenStore.getToken()
        .orElseGet(() -> {
            String newToken = niceApiClient.issueAccessToken();
            niceTokenStore.store(newToken, expiresIn - 60);  // 60초 여유
            return newToken;
        });
}
```

---

### 오류 3: Rate Limit 자기 IP 차단

**원인**: 개발 환경에서 `IDO_AUTH_RL_ENABLED=true`로 반복 API 호출  
**해결**: 로컬에서는 반드시 `IDO_AUTH_RL_ENABLED=false`

---

### 오류 4: Kafka 연결 오류가 로그를 덮음

```
ERROR o.a.k.c.NetworkClient - Connection failed to broker...
```

**원인**: Kafka가 없는 환경에서 감사 로그 발행 시도  
**해결**: `IDO_AUDIT_KAFKA_ENABLED=false`로 설정

---

### 오류 5: SLO 후 FE 세션이 남아있음

**원인**: FE가 `Logout()` 대신 직접 localStorage를 클리어하여 SLO API를 호출하지 않음  
**해결**: FE 코드에서 반드시 `import { Logout } from 'api/utils'` 사용 (Sprint 10에서 수정 완료)

---

## 15. 코딩 컨벤션 및 체크리스트

### 필수 체크리스트 (PR 전)

```
□ Feature Flag 추가 시 K8s ConfigMap에 환경변수 추가했는가?
□ 새 외부 호출은 비치명적(try-catch)으로 처리했는가?
□ CI를 FE 응답에 포함하지 않았는가? (@JsonInclude(NON_NULL))
□ Redis Key는 네임스페이스를 포함하는가? (예: "slo:session:{id}")
□ Kafka 이벤트는 멱등성을 보장하는가? (IdempotentEventStore)
□ 새 API는 Javadoc을 작성했는가? (Controller 메서드 수준)
□ 단위 테스트를 추가했는가? (최소 성공/실패 2개)
□ application-local.yml 예시를 업데이트했는가?
□ docs/FEATURE_FLAGS.md를 업데이트했는가? (Flag 추가 시)
```

### 금지 사항

```
✗ CI(Connecting Information) FE 응답에 포함 절대 금지
✗ 외부 호출 없이 Handoff Ticket 생성 금지 (Policy 우회)
✗ 비동기 처리 없이 Webhook 동기 발송 금지 (Outbox 패턴 필수)
✗ feSessionId 쿠키를 URL 파라미터로 전달 금지
✗ `@ConditionalOnProperty` 없이 Redis/Kafka 필수 빈 등록 금지
```

### 로깅 컨벤션

```java
// 구조화 로그 — correlationId 항상 포함
log.info("[SLO] 처리 완료: mbrNo={} agencyCode={} cid={}", 
    session.getMbrNo(), session.getAgencyCode(), correlationId);

// 비치명적 오류 — WARN 레벨
log.warn("[SLO] Keycloak 호출 실패 (무시): cid={} err={}", correlationId, e.getMessage());

// 디버그 — Feature Flag 상태
log.debug("[FeatureFlag] Redisson 비활성화 상태");
```

---

> **문서 버전**: v2.3.0 | **최종 수정**: 2026-05-11  
> 추가 문의: [`docs/FEATURE_FLAGS.md`](../FEATURE_FLAGS.md), [`docs/api-auth-spec.md`](../api-auth-spec.md) 참조
