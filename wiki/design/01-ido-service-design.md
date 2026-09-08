# IdO (Identity Orchestrator) 서비스 상세 설계서

| 항목 | 내용 |
|------|------|
| **서비스명** | IdO (Identity Orchestrator) |
| **포트** | 8083 |
| **역할** | 인증 오케스트레이션, 기관 Handoff, 프로비저닝, Gateway, 감사 |
| **기술 스택** | Spring Boot 3.2, JDK 21, PostgreSQL, Redis, Kafka |
| **최종 갱신** | 2026-05-15 (v0.8.8) |

---

## 1. 개요

IdO는 통합인증 플랫폼의 **중앙 오케스트레이터**다. 직접 인증 로직을 갖지 않고, 다양한 IdP(NICE, OACX, EzAuth, Keycloak)의 인증 결과를 수신하여 회원 정보 조회·등록·핸드오프·프로비저닝 등 플랫폼 비즈니스 로직을 처리한다.

```
외부 IdP ──► Q-Sign(:8081) ──► IdO(:8083) ──► Q-IM(:8082)
                                    │
                              기관 시스템 × 68
```

---

## 2. 패키지 구조

```
io.github.hipstermin.idem.hub
├── auth/                   # 인증 결과 수신·처리
│   ├── controller/         # AuthController (NICE, OACX, ci-token 등)
│   ├── service/            # AuthService, NiceAuthService
│   ├── client/             # NiceApiClient, OacxClient, IntegrationAuthClient
│   ├── store/              # NiceAuthSessionStore, NiceTokenStore (Redis)
│   ├── audit/              # AuthAuditService
│   ├── tracing/            # AuthTracingAspect (Micrometer)
│   └── dto/                # 요청/응답 DTO
├── broker/                 # IdP 브로커 (OIDC, non-OIDC)
│   ├── keycloak/           # Keycloak OIDC 콜백·검증
│   ├── nonoidc/            # 비OIDC IdP 중개
│   └── provider/           # ProviderRouter, ProviderConfig
├── gateway/                # Agency Gateway (Inbound/Outbound)
│   ├── AgencyGatewayController.java
│   ├── HmacSignatureFilter.java  ← HMAC-SHA256 서명 검증
│   └── AgencyHmacKeyStore.java
├── handoff/                # Handoff 티켓 발급·검증
│   └── strategy/           # 4가지 Handoff 전략
├── provision/              # 기관 프로비저닝
│   ├── ProvisioningService.java
│   ├── ProvisioningServiceImpl.java  ← Virtual Thread 68 기관 병렬
│   ├── ProvisioningOutboxRelay.java
│   └── dto/ProvisioningEventType.java
├── kafka/                  # Kafka Consumer (QimEvent, QsignAuth)
├── qim/sp/                 # Q-IM SP Receiver (기관 → Q-IM 이벤트 수신)
├── sso/                    # Cross-Agency SSO (CAST Token)
├── webhook/                # Webhook 발송 Outbox 릴레이
├── slo/                    # SLO 지표 수집·조회
├── ratelimit/              # Rate Limiting (Agency, Auth)
├── fe/                     # FE 세션 관리
├── admin/                  # 기관 관리자 API
├── ext/                    # ExtProxyController (FE → Q-IM Forward Proxy)
└── config/                 # 설정 (Kafka, Redis, Security 등)
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 AuthController — 인증 수신 API

**엔드포인트 목록**:

| HTTP | 경로 | 설명 |
|------|------|------|
| POST | `/api/v1/auth/callback` | Q-Sign 인증 완료 콜백 수신 |
| POST | `/api/v1/auth/ci-token` | CI AES-GCM → ciToken(JWT) 교환 |
| POST | `/api/v1/auth/nice/ci-check` | NICE CI 기반 회원 존재 확인 |
| GET  | `/api/v1/auth/nice/phone-auth-url` | NICE 휴대폰 인증 URL 생성 |
| POST | `/api/v1/auth/nice/phone-auth-result` | NICE 인증 결과 수신 |
| GET  | `/api/v1/auth/oacx/access-info` | OACX 접근 정보 조회 |
| POST | `/api/v1/auth/oacx/easysign` | OACX 간편인증 결과 처리 |

**AuthService 데이터 흐름**:
```
AuthController.ciTokenExchange(encryptedCi)
  → AesSharedKeyDecryptor.decrypt(encryptedCi)  ← AES-GCM 공유 키
  → Q-IM CI 조회 (ImApiOutAdapter → QimClient)
  → ciToken(JWT) 발급
  → 응답
```

### 3.2 ProvisioningServiceImpl — 전 기관 병렬 프로비저닝

```java
// 핵심 설계
@Override
public void triggerProvisioning(String qimUserId, String eventType, ...) {
    // 1. Feature Flag 가드 (F-20: IDO_PROVISIONING_ENABLED)
    // 2. Dry-run 모드 (F-22: IDO_PROVISIONING_DRY_RUN)
    // 3. 중복 트리거 방지 (sourceEventId 기반)
    // 4. 활성 PROVISIONING 엔드포인트 전체 조회 (최대 68개)
    // 5. JDK 21 Virtual Thread 병렬 HTTP POST
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        // 68개 기관 동시 발송
    }
    // 6. 성공 → COMPLETED, 실패 → PENDING (ProvisioningOutboxRelay 재시도)
}
```

**Feature Flags**:
| 플래그 | 기본값 | 설명 |
|--------|--------|------|
| `IDO_PROVISIONING_ENABLED` | `false` | 프로비저닝 On/Off |
| `IDO_PROVISIONING_DRY_RUN` | `true` | HTTP 미발행 관찰 모드 |

### 3.3 HmacSignatureFilter — HMAC-SHA256 Gateway 인증

```java
// 타이밍 공격 방어 핵심
if (!MessageDigest.isEqual(
        received.getBytes(StandardCharsets.UTF_8),
        expected.getBytes(StandardCharsets.UTF_8))) {
    sendUnauthorized(response);
}
```

**검증 순서**:
1. `X-Hmac-Timestamp` 유효성 (±5분)
2. `X-Hmac-Nonce` 중복 검사 (Redis)
3. HMAC-SHA256 서명 재계산 + 상수 시간 비교

### 3.4 HandoffService — 4가지 Handoff 전략

```java
// HandoffStrategyFactory.java
switch (agencyType) {
    case "APACHE_GATE" -> new ApacheGateHandoffStrategy();
    case "BRIDGE"      -> new BridgeHandoffStrategy();
    case "DIRECT"      -> new DirectHandoffStrategy();
    case "INTERNAL_SSO"-> new InternalSsoHandoffStrategy();
}
```

| 전략 | 설명 |
|------|------|
| `ApacheGate` | Apache SSO 게이트웨이 경유 |
| `Bridge` | 중간 브릿지 서버 경유 |
| `Direct` | 기관에 직접 Redirect |
| `InternalSso` | CAST Token 기반 내부 SSO |

### 3.5 QimEventConsumer — Kafka 이벤트 소비

```java
@KafkaListener(topics = "qim.user.events",
               containerFactory = "qimMemberListenerContainerFactory")
public void onQimEvent(ConsumerRecord<String, Map<String, Object>> record) {
    String eventType = (String) record.value().get("eventType");

    // 프로비저닝 트리거 (4종)
    if (isProvisioningTriggerEvent(eventType)) {
        provisioningService.triggerProvisioning(qimUserId, eventType, ...);
    }
    // 탈퇴 처리
    if ("MEMBER_WITHDRAWN".equals(eventType)) { ... }
}
```

### 3.6 SloController — SLO 지표 조회

```
GET /internal/slo/metrics
  → SloService.collect()
    → Micrometer MeterRegistry 집계
    → 인증 성공률, P99 응답시간, 에러율 반환
```

---

## 4. DB 스키마 (IdO — 주요 테이블)

| 테이블 | 용도 |
|--------|------|
| `ido.outbox` | Kafka 발행 Outbox (감사 이벤트) |
| `ido.provisioning_outbox` | 기관 HTTP 발송 Outbox (V18 CHECK 제약) |
| `ido.fe_session` | FE 세션 정보 |
| `ido.ticket` | Handoff 티켓 |
| `ido.agency_meta` | 기관 메타 정보 (HMAC 키 포함) |
| `ido.agency_endpoint_registry` | 기관 HTTPS 엔드포인트 목록 |
| `ido.broker_audit_log` | 인증 브로커 감사 로그 |
| `ido.gateway_inbound_audit` | Agency Gateway 수신 감사 |
| `ido.webhook_dispatch_outbox` | Webhook 발송 Outbox |
| `ido.processed_event` | Kafka 멱등성 (중복 소비 방지) |
| `ido.crypto_key_registry` | 암호화 키 버전 레지스트리 |
| `ido.auth_result` | 인증 결과 캐시 |

---

## 5. 환경 변수 / 설정

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `QIM_BASE_URL` | `http://localhost:8082` | Q-IM 서버 URL |
| `IDO_QIM_EXT_API_KEY` | - | Q-IM External API Key |
| `IDO_PROVISIONING_ENABLED` | `false` | 프로비저닝 활성화 |
| `IDO_PROVISIONING_DRY_RUN` | `true` | Dry-run 모드 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka 브로커 |
| `NICE_API_BASE_URL` | - | NICE API 기본 URL |
| `OACX_BASE_URL` | - | OACX API URL |

---

## 6. 모니터링 엔드포인트

| 경로 | 설명 |
|------|------|
| `GET /actuator/health` | 헬스체크 |
| `GET /actuator/metrics` | Micrometer 지표 |
| `GET /actuator/prometheus` | Prometheus 스크레이핑 |
| `GET /internal/slo/metrics` | SLO 지표 (내부용) |
| `GET /internal/features` | Feature Flag 상태 |

---

## 7. 유관기관 회원 전환 API (v0.8.9 신규)

> **Sprint 17+ 구현** — 유관기관 자체 로그인 완료 후 OnePass 회원 전환 플로우 진입을 위한 JWT Signed Request 기반 보안 인프라.

### 7.1 ConversionInit API

| 항목 | 내용 |
|------|------|
| **엔드포인트** | `POST /api/v1/conversion/init` |
| **인증** | 불필요 (JWT signed_request 내에 기관 서명 포함) |
| **컨트롤러** | `ConversionInitController` |
| **서비스** | `ConversionInitService` |

#### 요청 (Request Body)

```json
{
  "signedRequest": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJCSVpJTkZPXzAwMSIsIm1icklkIjoiQklaX1VTRVJfMDAxIiwicmVkaXJlY3RVcmkiOiJodHRwczovL3d3dy5iaXppbmZvLmdvLmtyL2NhbGxiYWNrIiwicmV0dXJuQ2xpZW50Ijoic3AtYml6aW5mbyIsInVzZXJUeXBlIjoiSU5EIiwiaWF0IjoxNzE2MTIzNDU2LCJleHAiOjE3MTYxMjM3NTZ9.xxxsignature",
  "agencyCode": "BIZINFO_001"
}
```

#### JWT 페이로드 구조

```json
{
  "sub":         "BIZINFO_001",                           // 기관 코드 (agencyCode와 일치 필수)
  "mbrId":       "BIZ_USER_001",                         // 기관 회원 ID (서버 측 보관)
  "redirectUri": "https://www.bizinfo.go.kr/callback",   // 전환 완료 후 복귀 URL (서버 측 보관)
  "returnClient":"sp-bizinfo",                            // OnePass client_id
  "userType":    "IND",                                   // "ENT" | "IND" | null(사용자 선택)
  "iat":         1716123456,                              // 발급 시각 (epoch seconds)
  "exp":         1716123756,                              // 만료 (발급 후 5분 이내)
  "jti":         "uuid-v4"                                // 재사용 방지 nonce (선택, 단기 P1 과제)
}
```

#### 응답 (200 OK)

```json
{
  "conversion_session_id": "a1b2c3d4-e5f6-...",   // Redis TTL 30분
  "user_type": "IND",
  "expires_at": "2026-05-16T10:30:00Z"
}
```

#### 에러 코드

| 에러 코드 | HTTP | 원인 |
|-----------|------|------|
| `E-AGENCY-307` | 404 | agencyCode에 해당하는 기관 없음 또는 비활성 |
| `E-AGENCY-303` | 401 | 기관 API Key 미등록 (K8s Secret 미설정) |
| `E-CONV-601` | 401 | JWT HMAC-SHA256 서명 검증 실패 또는 sub ≠ agencyCode |
| `E-CONV-602` | 410 | JWT exp 만료 (발급 후 5분 초과) |
| `E-AGENCY-306` | 403 | redirectUri가 agency_meta.callback_whitelist에 없음 |

### 7.2 검증 처리 순서 (ConversionInitService)

```
① agencyMetaRepository.findByCode(agencyCode).filter(active)   → E-AGENCY-307
② credentialStore.findSecret("secrets/agency/{CODE}/api-key")   → E-AGENCY-303
③ JWT 서명 검증 (HMAC-SHA256, jjwt 0.12.6) + sub = agencyCode  → E-CONV-601
④ JWT iat + 5분 > now()                                         → E-CONV-602
⑤ callbackUrlValidator.validate(redirectUri, whitelist)         → E-AGENCY-306
⑥ ConversionSession → Redis TTL 30분 저장
```

### 7.3 ConversionSession (Redis 저장 객체)

| 필드 | 타입 | 설명 |
|------|------|------|
| `sessionId` | UUID | Redis 키 접미사: `conversion:session:{sessionId}` |
| `agencyCode` | String | 기관 코드 |
| `mbrId` | String | **서버 측 보관 — FE URL 미노출** |
| `redirectUri` | String | **서버 측 보관 — FE URL 미노출** |
| `returnClient` | String | Handoff client_id |
| `userType` | String | `"IND"` / `"ENT"` / `null` |
| `createdAt` | Instant | 세션 생성 시각 |
| `expiresAt` | Instant | 세션 만료 시각 (createdAt + 30분) |

### 7.4 Open Redirect 방어 3-레이어

| 레이어 | 위치 | 구현 |
|--------|------|------|
| **L1 (FE)** | `Step8.tsx isSafeRedirectUri()` | `REACT_APP_REDIRECT_ALLOWED_ORIGINS` 환경변수 기반 와일드카드 매칭 |
| **L2 (BE)** | `FeSessionServiceImpl.isValidReturnUrl()` | `ido.fe.allowed-return-urls` YAML (환경변수 `${ALLOWED_URL_*}`) |
| **L3 (BE)** | `CallbackUrlValidator.validate()` | `agency_meta.callback_whitelist` PostgreSQL JSONB — 완전일치/와일드카드/접두사 |

> **참조**: GUIDE-001~004 상세 가이드 → `wiki/guide/` 디렉토리
