# 03 · Module Deep Dive — IdO (Identity DMZ Orchestrator)

> 원천: `/home/user/webapp/ido/` · 정본 스펙 `docs/internal/spec/03d-module-ido.md` · V19 최신 마이그레이션.
> 파일 수 270 (모듈 중 최대) · 컨트롤러 19개 · Kafka 컨슈머 4개 · 서브패키지 27개.

---

## 1. 삼각 요약

| 항목 | 값 |
|---|---|
| 포트 | 8083 |
| DB | PostgreSQL 16 (schema: `ido`) |
| 마이그레이션 | V1 ~ V19 (`V19__anyid_provider_config.sql` 최신) |
| 역할 | P (Portal) / E (External) / G (Gateway) / T (Token) — 4역할 겸직 |
| 프레임워크 | Spring Boot 3, JPA, Kafka Consumer, Redisson, Resilience4j |
| 파일 수 | 270 (M5 최대) |

---

## 2. 서브패키지 27개 총람

| # | 패키지 | 역할 | 대표 클래스 |
|---|---|---|---|
| 1 | `admin` | 기관 어드민 API | `AgencyAdminController` |
| 2 | `api` | Portal API 진입점 | `HandoffController`, `AgencyEventController`, `GlobalExceptionHandler` |
| 3 | `audit` | 감사 로그 발행 | `AuditLogPublisher` |
| 4 | `auth` | 인증 컨트롤러 | `AuthController` |
| 5 | `broker` | OIDC 브로커·Callback | `BrokerController`, `KeycloakCallbackController`, `NonOidcBrokerController`, `AnyIdController`, `InternalSigVerifier` |
| 6 | `burst` | 트래픽 폭주 처리 | (관찰 필요) |
| 7 | `config` | Bean 설정 | Redisson, Resilience4j, Kafka, Security |
| 8 | `conversion` | 회원 전환 (guest→member) | `ConversionInitController`, `ConversionSessionController` |
| 9 | `crypto` | Handoff AES-256-GCM | `HandoffCrypto`, KMS 클라이언트 |
| 10 | `domain` | 도메인 엔티티 | (다수) |
| 11 | **`ext`** | **/api/ext PEP 프록시** | **`ExtProxyController`** |
| 12 | `fe` | FE 세션 API | `FeSessionController` |
| 13 | `gateway` | 기관→IdO Inbound | `AgencyGatewayController` |
| 14 | `handoff` | Handoff 발급/검증 | `HandoffServiceImpl`, `strategy/*` (4전략) |
| 15 | `infrastructure` | 외부 HTTP 클라이언트 | **`QAuthzClient`** (★신설), `NiceApiClient`, `IntegrationAuthClient` |
| 16 | `kafka` | 이벤트 컨슈머 | `HandoffEventConsumer`, `QimEventConsumer`, `QsignAuthEventConsumer`, `IdempotentEventStore` |
| 17 | `memberlookup` | 회원 조회 | `MemberLookupController` |
| 18 | `metrics` | Micrometer 지표 | Prometheus exporter |
| 19 | `policy` | 인가 정책 | (관찰 필요) |
| 20 | `provision` | 프로비저닝 Outbox | `ProvisioningOutboxRelay`, `ProvisioningService*` |
| 21 | `qim` | Q-IM SP 리시버 | `QimSpReceiverController` (SP → IdO 역방향) |
| 22 | `ratelimit` | Rate Limiter | `AuthRateLimitInterceptor`, `AgencyRateLimiter` |
| 23 | `retention` | 개인정보 파기 스케줄러 | (F-11) |
| 24 | `slo` | SLO/SLA 지표 | `SloController` |
| 25 | `sso` | Cast Token + Cross Agency SSO | `CastTokenServiceImpl`, `CrossAgencySsoController`, `CastKeyConfig` |
| 26 | `webhook` | 웹훅 Outbox | (F-14) |

---

## 3. 컨트롤러 19개 상세 매핑

| 컨트롤러 | Base Path | 역할군 | 인증 | 비고 |
|---|---|---|---|---|
| `AgencyAdminController` | `/api/v1/admin/agencies` | 관리 | Keycloak | 기관 CRUD |
| `AgencyEventController` | `/api/v1/agencies/{code}/events` | G | HMAC | 기관 이벤트 접수 |
| `HandoffController` | `/api/v1/handoff` | P/T | Cookie(fe-session-id) | Handoff 발급/검증, Idempotency-Key TTL 1일 |
| `AuthController` | `/api/v1/auth` | P/T | 다양 | 로그인 진입점 |
| `BrokerController` | `/broker` | T | OIDC state | OIDC 콜백 브로커 |
| `OidcCompleteController` | `/broker/oidc/complete` | T | State + PKCE | OIDC 완료 |
| `AnyIdController` | `/broker/anyid` | T | Provider 종속 | AnyId 통합 (NICE/KMC 등) |
| `KeycloakCallbackController` | `/broker/keycloak/callback` | T | Keycloak | Keycloak Brokering 콜백 |
| `NonOidcBrokerController` | `/broker/non-oidc` | T | 커스텀 | 비 OIDC 프로바이더 |
| `ConversionInitController` | `/api/v1/conversion/init` | P | Cookie | 전환 개시 |
| `ConversionSessionController` | `/api/v1/conversion/session` | P | Cookie | 전환 세션 |
| **`ExtProxyController`** ★ | `/api/ext/**` | **E (PEP)** | Cookie + AnthiSpoof | FE→기관 프록시, X-Authz-\* 재주입 |
| `FeSessionController` | `/api/v1/fe-session` | P | 최초 진입 | fe-session-id 발급 |
| `AgencyGatewayController` | `/api/v1/gateway/**` | G | HMAC + 서명 검증 | 기관→IdO Inbound |
| `MemberLookupController` | `/api/v1/members/lookup` | P | Cookie + 정책 | 회원 조회 |
| `QimSpReceiverController` | `/api/v1/qim/sp/**` | G (Kafka 대체 경로) | HMAC | Q-IM → SP 이벤트 수신 |
| `SloController` | `/actuator/slo` | Ops | 내부 | SLO 조회 |
| **`CrossAgencySsoController`** | `/api/v1/sso/cross` | T | Cast Token | 기관 간 SSO |
| `GlobalExceptionHandler` | — | Cross-cut | — | `PlatformErrorCode` 매핑 |

**관찰**: `/api/ext/**` (ExtProxyController) 는 ADR-008 헌법의 핵심 게이트. FE 는 이 경로 외로 다른 백엔드에 접근할 수 없다.

---

## 4. Kafka 컨슈머 4개

| 클래스 | 구독 토픽 | 컨슈머 그룹 | 목적 |
|---|---|---|---|
| `HandoffEventConsumer` | `ido.handoff.events` | `ido-handoff-cg` | Handoff 발급/검증 이벤트 감사 |
| `QimEventConsumer` | `qim.user.events`, `qim.user.snapshot` | `ido-qim-cg` | Q-IM 회원 변경 반영 |
| `QsignAuthEventConsumer` | `qsign.auth.events` | `ido-qsign-cg` | Q-Sign 인증 결과 → IdO 세션 갱신 |
| `IdempotentEventStore` | (공통) | — | processed_event 테이블 기반 중복 방지 |

**중복 방지 원장** (`V5__add_processed_event.sql`):
```sql
CREATE TABLE ido.processed_event (
  event_id VARCHAR(64) PRIMARY KEY,
  topic    VARCHAR(128),
  processed_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 5. 마이그레이션 계보 V1 ~ V19

| V | 파일 | 요지 |
|---|---|---|
| V1 | `create_schema.sql` | 기본 스키마 (session, handoff, audit) |
| V2 | `add_fe_session.sql` | fe-session-id 도입 |
| V3 | `add_keycloak_auth.sql` | Keycloak 연동 컬럼 |
| V4 | `add_qim_sp_receiver.sql` | Q-IM SP 수신 |
| V5 | `add_processed_event.sql` | 멱등 처리 원장 |
| V6 | `add_broker_audit_log.sql` | 브로커 감사 |
| V7 | `add_webhook_and_audit.sql` | Webhook + 감사 확장 |
| V8 | `seed_agency_api_key_and_fix_webhook.sql` | 시드 데이터 |
| V9 | `add_crypto_key_registry_and_rate_limit.sql` | 키 레지스트리 + 레이트리밋 |
| V10 | `extend_auth_result_and_provider_routing.sql` | AuthResult 확장 + Provider 라우팅 |
| V11 | `add_slo_retention_config.sql` | SLO/파기 설정 |
| V12 | `add_mfa_aal_schema.sql` | MFA/AAL |
| V13 | `seed_agency_pattern_scenarios.sql` | 기관 시나리오 시드 |
| V14 | `add_cast_token_and_sso_session_link.sql` | **Cast Token + SSO 세션 링크** |
| V15 | `add_provisioning_outbox_and_agency_endpoint.sql` | 프로비저닝 Outbox |
| V16 | `add_gateway_inbound_audit.sql` | Gateway 감사 |
| V17 | `add_outbox_next_retry_at.sql` | Outbox 재시도 스케줄 |
| V18 | `update_event_type_constraints.sql` | 이벤트 타입 제약 |
| V19 | `anyid_provider_config.sql` | **AnyId Provider 설정** (최신) |

---

## 6. 신규 파이프라인 (PR #205 반영)

### 6.1 IdO 신규 파일
```
ido/src/main/java/kr/go/smes/ido/
  infrastructure/QAuthzClient.java              [NEW 104 lines]  — q-authz HTTP 클라이언트
  ext/ExtProxyController.java                   [MOD]            — anti-spoofing + X-Authz-* 재주입
  handoff/HandoffServiceImpl.java               [MOD line 340~]  — payload.put("roles", ...)
  sso/CastTokenServiceImpl.java                 [MOD line 108]   — claim(CLAIM_ROLES, roles)
```

### 6.2 `QAuthzClient.java` 요지 (104 lines)
```java
@Component
public class QAuthzClient {
    private final RestClient restClient;
    private final String internalApiKey;
    private final String baseUrl;

    public List<String> getEffectiveRoles(String qimUserId, String scope, String agencyCode) {
        try {
            String url = baseUrl + "/api/v1/internal/authz/users/" + qimUserId
                       + "/effective-roles?agency=" + agencyCode;
            AuthzResponse resp = restClient.get()
                .uri(url)
                .header("X-Internal-Api-Key", internalApiKey)
                .retrieve()
                .body(AuthzResponse.class);
            return resp != null ? resp.roles() : List.of();
        } catch (Exception e) {
            log.warn("[q-authz] fail-open, empty roles. qimUserId={}, err={}",
                     qimUserId, e.getMessage());
            return List.of();      // ★fail-open
        }
    }
}
```

### 6.3 `ExtProxyController.java` 핵심
```java
private static final Set<String> SPOOFABLE_AUTHZ_HEADERS = Set.of(
    "x-authz-user", "x-authz-roles", "x-authz-scope"
);
private static final String AUTHZ_SCOPE = "PLATFORM";
private static final String HEADER_AUTHZ_USER   = "X-Authz-User";
private static final String HEADER_AUTHZ_ROLES  = "X-Authz-Roles";
private static final String HEADER_AUTHZ_SCOPE  = "X-Authz-Scope";

@RequestMapping("/api/ext/**")
public ResponseEntity<byte[]> proxy(HttpServletRequest req, ...) {
    // 1) FE가 위조 주입한 X-Authz-* 헤더 제거
    HttpHeaders sanitized = removeSpoofable(req);

    // 2) 세션 → qimUserId 결정
    String qimUserId = feSessionService.resolveQimUserId(feSessionCookie);

    // 3) q-authz 조회
    List<String> roles = qAuthzClient.getEffectiveRoles(
        qimUserId, AUTHZ_SCOPE, tenantAgencyCode);

    // 4) 서버가 재주입
    sanitized.set(HEADER_AUTHZ_USER,  qimUserId);
    sanitized.set(HEADER_AUTHZ_ROLES, String.join(",", roles));
    sanitized.set(HEADER_AUTHZ_SCOPE, AUTHZ_SCOPE);

    // 5) upstream 프록시
    return httpClient.exchange(sanitized, body, ...);
}
```

---

## 7. Handoff Strategy 4종 (V14 정본)

### 7.1 클래스 계보
```
ido/handoff/strategy/
  HandoffStrategy.java             (interface)
  DirectHandoffStrategy.java       — 표준
  BridgeHandoffStrategy.java       — 브리지 (SPA↔IdO↔SP)
  InternalSsoHandoffStrategy.java  — 자체 SSO 보유 기관 (ADR-2026-004)
  ApacheGateHandoffStrategy.java   — Apache 앞단 (레거시)
```

### 7.2 페이로드 (`HandoffPayload` from platform-common)
```java
public record HandoffPayload(
    String qimUserId,
    String agencyCode,
    List<String> roles,            // ★PR #205 이후 추가
    Instant issuedAt,
    Instant expiresAt,
    String issuer,
    Map<String, Object> extras
) {}
```

### 7.3 발급 흐름
```
FE ─(handoff 요청)→ HandoffController
      │
      ▼
HandoffService.issue()
      ├─ qAuthzClient.getEffectiveRoles(qimUserId, "PLATFORM", agency)  ← 신규
      ├─ buildPlainPayload() with roles[]
      ├─ HandoffCrypto.encrypt(payload)  (AES-256-GCM)
      ├─ storeIdempotency(key, ttl=1day)
      └─ record audit
```

### 7.4 검증 흐름
```
SP ─(handoff 토큰)→ HandoffController.verify()
      ├─ HandoffCrypto.decrypt()
      ├─ validate issuer / expiry / nonce
      ├─ record verify audit
      └─ return HandoffPayload (with roles[])   ← SP는 roles 활용 가능
```

---

## 8. 보안 자원 (7 계층 매핑)

| 계층 | IdO 자원 |
|---|---|
| 네트워크 | 내부 네트워크에 배치 (DMZ 뒤) |
| WAF | (외부 관리) |
| Rate Limit | `AuthRateLimitInterceptor` (F-01) + `AgencyRateLimiter` (F-02) |
| 세션 | fe-session-id 쿠키 (HttpOnly, Secure, SameSite=Lax) |
| 서명 | `InternalSigVerifier` (기관→IdO HMAC), `HandoffCrypto` (AES-256-GCM) |
| 감사 | `AuditLogPublisher` (F-03/F-04) — Kafka + DB 이중 |
| **인가** | **`QAuthzClient` + `ExtProxyController.SPOOFABLE_AUTHZ_HEADERS`** ★신설 |

---

## 9. 관찰된 결함·리스크

| # | 항목 | 위험 | 근거 |
|---|---|---|---|
| L1 | `QAuthzClient` fail-open 정책 | 🔴 HIGH | 예외 시 empty roles |
| L2 | 27 서브패키지 크기 — 응집도 저하 우려 | 🟡 MED | 모듈 크기 |
| L3 | V19 신규 anyid_provider_config 스펙 문서 반영 확인 필요 | 🟡 MED | 정본 05 |
| L4 | ExtProxyController 프록시 대상 upstream 정의 위치 | 🟡 MED | 소스 확인 |
| L5 | Kafka 컨슈머 4개 — DLQ 재처리 정책 표준화 | 🟡 MED | spec 06 |
| L6 | `SPOOFABLE_AUTHZ_HEADERS` 대소문자 취급 (HTTP 헤더 case-insensitive) | 🟢 LOW | 소스 |
| L7 | Idempotency-Key TTL 1일 — 리플레이 방지 강도 재검토 | 🟡 MED | HandoffController head |

---

## 10. 참조
- 소스: `/home/user/webapp/ido/src/main/`
- 스펙: `docs/internal/spec/03d-module-ido.md`
- Feature Flags: `docs/internal/architecture/FEATURE_FLAGS.md` (F-01~F-18)
- 신규 통합점: §06 `06_module_qauthz.md` §6
