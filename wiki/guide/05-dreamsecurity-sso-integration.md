# 드림시큐리티 SSO 연동 통합 가이드

> **문서 버전**: v1.0.0  
> **최초 작성**: 2026-05-16  
> **작성 배경**: 공공기관 다수가 드림시큐리티 SSO를 자체 IAM으로 운영 중이며, 이들이 원패스(OnePass/IdO)를 도입할 때 필요한 브릿지 어댑터 구조 및 운영 가이드 수립  
> **대상 독자**: 유관기관 기술 담당자, 원패스 통합 PM, 보안 담당자

---

## 목차

1. [개요 및 배경](#1-개요-및-배경)
2. [현황 파악 — 코드베이스 갭 분석](#2-현황-파악--코드베이스-갭-분석)
3. [대책 ①: INTERNAL_SSO 어댑터 연동 전략](#3-대책--internal_sso-어댑터-연동-전략)
4. [대책 ②: 사용자 식별자 매핑 설계](#4-대책--사용자-식별자-매핑-설계)
5. [대책 ③: SLO 동기화 — 드림시큐리티 세션 종료 연동](#5-대책--slo-동기화--드림시큐리티-세션-종료-연동)
6. [대책 ④: 회원 전환/통합 정책](#6-대책--회원-전환통합-정책)
7. [기관별 도입 유형 판단 매트릭스](#7-기관별-도입-유형-판단-매트릭스)
8. [운영 배포 체크리스트](#8-운영-배포-체크리스트)
9. [FAQ / 자주 묻는 질문](#9-faq--자주-묻는-질문)

---

## 1. 개요 및 배경

### 1.1 두 SSO 시스템의 성격 차이

| 구분 | 드림시큐리티 SSO | 원패스(OnePass/IdO) |
|------|-----------------|-------------------|
| **대상** | 기관 내부 직원·내부 시스템 | 대국민 외부 서비스 이용자 |
| **프로토콜** | 세션/쿠키 기반 (Proprietary SAML/커스텀) | OIDC (OpenID Connect) 기반 |
| **인증 수단** | 사원증, 공인인증서, 내부 ID | CI/휴대폰 본인확인, 민간인증(카카오·PASS 등) |
| **식별자** | 기관 사번(직원번호), 내부 User ID | qimUserId (UUID), CI (연계정보) |
| **세션 저장** | 드림시큐리티 SSO 서버 (쿠키/JSESSIONID) | Redis (feSessionId JWT) |
| **로그아웃** | 드림시큐리티 SLO API 호출 | IdO POST /api/v1/slo/initiate |
| **IAM 통합** | 기관 Active Directory·LDAP | Q-IM (Qualification Identity Manager) |

### 1.2 브릿지 어댑터가 필요한 이유

```
[외부 대국민 서비스]          [기관 내부망]
  원패스 인증 완료               드림시큐리티 SSO 세션
   ↓ qimUserId                  ↓ JSESSIONID / AGSID
   원패스 Handoff Ticket    →  기관 내부 서비스 접근
                           ↑
                    브릿지 어댑터 필요
                 (Ticket → SSO 세션 변환)
```

원패스 인증으로 발급된 `Handoff Ticket`은 기관 내부망의 드림시큐리티 세션과 직접 호환되지 않는다. **브릿지 어댑터**는 두 시스템 사이에서 세션 변환과 식별자 매핑을 처리하는 핵심 컴포넌트다.

### 1.3 코드베이스 현황 요약

이미 구현된 4가지 Handoff 전략:

| 전략 | 클래스 | 대상 환경 |
|------|--------|----------|
| `DIRECT` | `DirectHandoffStrategy` | 기관이 IdO API 직접 호출 (기본) |
| `BRIDGE` | `BridgeHandoffStrategy` | 폐쇄망 기관, Bridge 서버 경유 |
| `APACHE_GATE` | `ApacheGateHandoffStrategy` | Apache httpd + mod_auth 레거시 |
| **`INTERNAL_SSO`** | **`InternalSsoHandoffStrategy`** | **드림시큐리티 같은 내부 SSO 연동** ← 핵심 |

**핵심 발견**: `INTERNAL_SSO` 전략이 이미 구현되어 있어 드림시큐리티 SSO 연동의 기반 인프라가 존재한다. **갭 분석 후 설정값 추가와 기관별 DB 등록만으로 연동 가능**하다.

---

## 2. 현황 파악 — 코드베이스 갭 분석

### 2.1 INTERNAL_SSO 전략 구현 현황

#### 기존 코드 동작 방식 (`InternalSsoHandoffStrategy.java`)

```
[Handoff Ticket 발급]
  → InternalSsoHandoffStrategy.postIssue() 자동 호출
      ① agencyMetaRepository.findByCode(agencyCode)
            → DB: agency_meta.sso_domain 조회
      ② POST {ssoDomain}/internal/sso-session
            Headers: X-Agency-Code, X-Correlation-Id, X-Source-System: "ido"
            Body: {
              ticketId,
              qimUserId,
              authLevel,  ← L1/L2/L3
              expiresAt,
              correlationId
            }
      ③ 드림시큐리티 SSO 서버 → 세션 PRE_REGISTERED 상태로 임시 저장
      ④ 사용자 콜백 도달 → /internal/sso-session/{ticketId}/activate
            → ACTIVATED → SSO 쿠키(JSESSIONID) 자동 발급
```

#### 현재 구현 완료 항목 ✅

- [x] `InternalSsoHandoffStrategy` Push 로직 구현 완료
- [x] `AgencyMetaJpaEntity`: `sso_domain` VARCHAR(200) 컬럼 존재
- [x] `AgencyMeta` 도메인 객체: `ssoDomain` 필드 존재
- [x] `HandoffStrategyFactory`: `INTERNAL_SSO` → 전략 자동 라우팅
- [x] `MockSsoSessionController` (`@Profile("bridge")`): 드림시큐리티 서버 Mock 구현
- [x] `AgencyPatternSsoTest`: 4개 시나리오 테스트 통과

#### 현재 미완료 / 갭(Gap) 항목 ⚠️

| # | 갭 항목 | 영향 | 우선순위 |
|---|---------|------|---------|
| G-1 | `agency_meta` DB에 드림시큐리티 기관 레코드 미등록 (`integration_type='INTERNAL_SSO'`, `sso_domain` 미설정) | **운영 불가** | P0 |
| G-2 | 드림시큐리티 측 `/internal/sso-session` 엔드포인트 실제 구현 확인 필요 (현재 Mock만 존재) | **연동 불가** | P0 |
| G-3 | qimUserId ↔ 기관 사번 매핑 테이블 없음 (현재 `instMbrId = qimUserId` 1:1 매핑) | **식별자 불일치** | P1 |
| G-4 | SLO 시 드림시큐리티 세션 개별 종료 미구현 (`enqueueForUserLogout()`은 Webhook Outbox 방식이나 드림시큐리티 SLO API 직접 호출 구조 없음) | **로그아웃 연동 불완전** | P1 |
| G-5 | `InternalSsoHandoffStrategy.postIssue()` 실패 시 Retry 미구현 (현재: exception → warn log → 무시) | **세션 사전 등록 누락 가능** | P2 |
| G-6 | 드림시큐리티 `/internal/sso-session` 인증 방식 미정의 (현재 X-Agency-Code 헤더만 사용, 서명 검증 없음) | **보안 취약** | P1 |
| G-7 | 회원 전환 시 드림시큐리티 IAM 계정 연결 정책 미정의 (GUEST 상태 처리) | **UX 단절** | P2 |

### 2.2 SLO 흐름 현황

```
현재 SLO 흐름:
  POST /api/v1/slo/initiate
    ① feSession Redis 즉시 만료
    ② revokeKeycloakSessionSafely()  ← Q-Sign 경유
    ③ enqueueForUserLogout()         ← Webhook Outbox (기관에 USER_LOGOUT 이벤트 전달)
    ④ feSessionId 쿠키 제거

드림시큐리티 SLO 연동 갭:
  - ③에서 Webhook으로 USER_LOGOUT 이벤트 전달 시
    드림시큐리티 기관의 webhook 수신 후 자체 SLO 처리해야 함
  - 드림시큐리티가 JSESSIONID 쿠키를 자체 만료시키는 것은 기관 책임
  - 원패스 → 드림시큐리티 직접 SLO API 호출은 내부망 접근 필요 (제약)
```

### 2.3 식별자 매핑 현황

```
현재: instMbrId = qimUserId (UUID 1:1 매핑, InstMbrIdMapping.java)

드림시큐리티 연동 시 필요:
  원패스 qimUserId (UUID)
    ↕ 매핑 테이블 필요
  드림시큐리티 기관 사번 / 내부 User ID (예: "A2024123456")

현재 매핑 테이블 없음 → G-3 갭
```

---

## 3. 대책 ①: INTERNAL_SSO 어댑터 연동 전략

### 3.1 연동 전체 흐름 (완성 목표)

```
[사용자 - 대국민]
    |
    | 1. 원패스 로그인 (휴대폰/CI 인증)
    ↓
[원패스 IdO]
    |
    | 2. Handoff Ticket 발급
    | 3. POST {ssoDomain}/internal/sso-session  ← INTERNAL_SSO 전략 자동 실행
    |    Body: { ticketId, qimUserId, authLevel, expiresAt }
    |    Headers: X-Agency-Code, X-Correlation-Id, X-Handoff-Sig (HMAC-SHA256)  ← G-6 보완
    ↓
[드림시큐리티 SSO 서버 - 기관 내부망]
    |
    | 4. 세션 PRE_REGISTERED 저장 (ticketId 키)
    | 5. qimUserId → 사번 역방향 매핑 조회  ← G-3 보완
    ↓
    | 6. 사용자 브라우저 → 기관 콜백 URL 도달
    |    GET https://agency.go.kr/sso-callback?ticket_id={ticketId}
    ↓
[기관 진입 컨트롤러]
    |
    | 7. IdO /api/v1/handoff/verify 호출 (Handoff Ticket 검증)
    | 8. APPROVED → /internal/sso-session/{ticketId}/activate 호출
    ↓
[드림시큐리티 SSO 서버]
    |
    | 9. PRE_REGISTERED → ACTIVATED
    | 10. JSESSIONID 쿠키 발급 (기관 내부 세션)
    ↓
[기관 내부 서비스] ← 사용자 정상 접근 완료
```

### 3.2 agency_meta DB 등록 — 즉시 조치 필요 (G-1 해소)

드림시큐리티 SSO 기관을 연동하려면 `ido.agency_meta` 테이블에 다음 레코드를 등록해야 한다.

```sql
-- 드림시큐리티 SSO 기관 등록 예시
-- 기관마다 agency_code와 sso_domain 값이 다름
INSERT INTO ido.agency_meta (
    agency_code,
    official_name,
    min_auth_level,
    policy_version,
    api_key_hash,          -- PBKDF2(apiKey) — K8s Secret에서 별도 등록
    callback_whitelist,    -- JSON 배열
    allowed_attributes,
    integration_type,      -- ← 핵심: INTERNAL_SSO
    sso_domain,            -- ← 드림시큐리티 SSO 서버 내부 URL
    active,
    created_at,
    updated_at
) VALUES (
    'AGENCY_ABC',
    '○○청',
    'L2',                   -- 최소 인증 수준 (공공기관은 보통 L2 이상)
    '1.0',
    'PBKDF2_HASH_HERE',
    '["https://www.agency-abc.go.kr/onepass/callback"]',
    '["name_masked","mobile_masked"]',
    'INTERNAL_SSO',         -- 드림시큐리티 SSO 연동 유형
    'https://sso-internal.agency-abc.go.kr',  -- 드림시큐리티 SSO 내부 엔드포인트
    true,
    NOW(),
    NOW()
);
```

> ⚠️ **주의**: `sso_domain`은 기관 내부망 엔드포인트로, 원패스 서버가 해당 IP/도메인에 접근 가능한지 네트워크 정책 확인 필수.

### 3.3 보안 강화: X-Handoff-Sig 헤더 추가 (G-6 해소)

현재 `InternalSsoHandoffStrategy`는 X-Agency-Code 헤더만으로 기관 SSO 서버를 호출한다. 내부망이라도 HMAC-SHA256 서명을 추가해야 한다.

#### `InternalSsoHandoffStrategy` 수정 방향

```java
// 현재 (미서명)
headers.set("X-Agency-Code",    agencyCode);
headers.set("X-Correlation-Id", correlationId);
headers.set("X-Source-System",  "ido");

// 목표 (HMAC-SHA256 서명 추가)
headers.set("X-Agency-Code",    agencyCode);
headers.set("X-Correlation-Id", correlationId);
headers.set("X-Source-System",  "ido");
headers.set("X-Handoff-Sig",    buildHmacSig(ticketId, correlationId, agencyCode));
// X-Handoff-Sig = "sha256=" + HMAC-SHA256(ticketId:correlationId:agencyCode, sharedSecret)
// sharedSecret = K8s Secret에서 기관별 독립 관리
```

드림시큐리티 SSO 서버는 수신 시 `X-Handoff-Sig` 검증 후 세션 등록을 수락해야 한다.

### 3.4 Retry 보완 (G-5 해소)

현재 `postIssue()` 실패는 비치명적으로 처리(warn log → 무시)된다. 드림시큐리티 SSO 연동에서는 세션 사전 등록 실패 시 사용자가 로그인 불가 상태가 될 수 있으므로 Retry 전략이 필요하다.

**단기 대책**: Resilience4j Retry 어노테이션 적용 (기관별 설정)

```java
@Retry(name = "internalSsoHandoff", fallbackMethod = "postIssueFallback")
@Override
public void postIssue(HandoffTicket ticket, HandoffPayload payload, String correlationId) {
    // ... 기존 로직
}

// Fallback: 실패 시 Kafka Dead Letter Queue에 적재 → 수동 재처리
private void postIssueFallback(HandoffTicket ticket, HandoffPayload payload,
                                String correlationId, Exception ex) {
    log.error("[InternalSsoStrategy] 최대 재시도 초과 — DLQ 적재: ticketId={} agency={}",
              ticket.getTicketId(), ticket.getAgencyCode());
    // TODO: DLQ or Alert 발송
}
```

**application.yml 설정**:
```yaml
resilience4j:
  retry:
    instances:
      internalSsoHandoff:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
          - org.springframework.web.client.HttpServerErrorException
```

---

## 4. 대책 ②: 사용자 식별자 매핑 설계

### 4.1 현황 및 문제

현재 `InstMbrIdMapping`은 `instMbrId = qimUserId` (UUID 1:1)로 설계되어 있다.  
드림시큐리티 기관에서는 기관 내부 사번(예: `A2024123456`)이 핵심 식별자이며, 원패스 `qimUserId`와 별도 매핑이 필요하다.

```
원패스 qimUserId: "01234567-89ab-cdef-0123-456789abcdef"  (UUID)
드림시큐리티 사번:  "A2024123456"  (기관 자체 포맷)
연계정보(CI):      "abc...xyz"    (공통 연계 키)
```

### 4.2 매핑 방법 — 3가지 옵션

#### 옵션 A: CI 기반 역방향 조회 (권장)

원패스는 인증 시 CI(연계정보)를 취득한다. 기관은 기존 사용자 DB에서 CI로 사번을 역조회한다.

```
원패스 인증 → CI 취득
     ↓
기관 사용자 DB: SELECT emp_id FROM users WHERE ci_hash = SHA256(CI)
     ↓
  찾음 → 기존 직원 → INTERNAL_SSO 세션 발급 (사번 포함)
  못찾음 → GUEST → 계정 연결 절차 안내
```

**구현 위치**: `AgencyEntryController.java` GUEST case 처리 분기가 이미 존재한다.

```java
// AgencyEntryController.java의 GUEST 케이스 (기존 코드)
case GUEST -> ResponseEntity.status(200).body(Map.of(
    "state", "GUEST",
    "qimUserId", guestQimUserId,
    "message", "기관 회원 연결이 없습니다. 회원 가입 또는 계정 연결이 필요합니다."
));
```

→ 드림시큐리티 연동 기관에서는 이 GUEST 응답을 받으면 **기관 자체 계정 연결 UI**로 유도해야 한다.

#### 옵션 B: qimUserId ↔ 사번 명시적 매핑 테이블 (필요 시)

CI가 없거나 기관 DB에 CI가 없는 경우:

```sql
-- 기관별 식별자 매핑 테이블 (신규 생성 필요)
CREATE TABLE IF NOT EXISTS ido.agency_user_id_mapping (
    mapping_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agency_code     VARCHAR(50)  NOT NULL,
    qim_user_id     VARCHAR(100) NOT NULL,  -- 원패스 UUID
    agency_user_id  VARCHAR(200) NOT NULL,  -- 기관 사번 / 내부 ID
    ci_hash         VARCHAR(64),            -- SHA-256(CI) — 조회용
    linked_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (agency_code, qim_user_id),
    UNIQUE (agency_code, agency_user_id)
);
CREATE INDEX idx_agency_user_id_mapping_ci ON ido.agency_user_id_mapping(ci_hash);
```

#### 옵션 C: 드림시큐리티 SSO 서버 자체 매핑 (기관 자체 처리)

드림시큐리티 SSO 서버가 원패스로부터 받은 `qimUserId`와 내부 사번을 자체적으로 매핑.  
원패스 서버는 `qimUserId`만 전달하고, 기관 SSO 서버가 나머지 처리.

> **권장**: 옵션 A (CI 기반) + 옵션 C (기관 자체 처리) 조합.  
> 원패스 서버에 기관별 사번 데이터를 저장하지 않아 **데이터 최소화 원칙** 준수.

### 4.3 `InternalSsoHandoffStrategy` — qimUserId 전달 보완

현재 `postIssue()`에서 드림시큐리티 SSO 서버로 전달하는 Body:

```json
{
  "ticketId":      "ticket-uuid",
  "qimUserId":     "qim-user-uuid",
  "authLevel":     "L2",
  "expiresAt":     "2026-05-16T12:00:00Z",
  "correlationId": "cid-uuid"
}
```

드림시큐리티 SSO 서버는 이 `qimUserId`를 받아 자체 사번 매핑 후 JSESSIONID 쿠키를 발급한다. **추가 가능한 필드**:

```json
{
  "ticketId":      "ticket-uuid",
  "qimUserId":     "qim-user-uuid",
  "authLevel":     "L2",
  "expiresAt":     "2026-05-16T12:00:00Z",
  "correlationId": "cid-uuid",
  "agencySubjectId": "기관별 고유 식별자 (optional)",
  "authProvider":  "KAKAO | PASS | MOBILE_OTP"
}
```

---

## 5. 대책 ③: SLO 동기화 — 드림시큐리티 세션 종료 연동

### 5.1 현재 SLO 흐름 재확인

```
POST /api/v1/slo/initiate (SloController)
  ① feSession Redis 즉시 삭제
  ② Q-Sign → Keycloak 세션 종료 (HMAC-SHA256 서명)
  ③ WebhookDispatcherService.enqueueForUserLogout()
       → webhook_dispatch_outbox INSERT
       → WebhookDispatchOutboxRelay → HTTPS POST 기관 endpoint
  ④ feSessionId 쿠키 Max-Age=0 제거
```

### 5.2 드림시큐리티 연동 시 SLO 처리 방안

**방안 A: Webhook 기반 (현재 구현 활용) — 권장**

```
원패스 SLO 시작
  ↓
③ enqueueForUserLogout(instMbrId, qimUserId, cid)
  → USER_LOGOUT 이벤트 → Webhook Outbox
  → HTTPS POST 드림시큐리티 Webhook Endpoint
       Body: {
         eventType: "USER_LOGOUT",
         instMbrId: "qim-user-uuid",
         correlationId: "cid",
         occurredAt: "2026-05-16T..."
       }
       Headers: X-Webhook-Signature: sha256=HMAC(payload, signingSecret)
  ↓
드림시큐리티 Webhook 수신
  → qimUserId 기반 사번 역조회
  → JSESSIONID / 내부 세션 일괄 만료
  → 기관 내부 SLO 완료
```

드림시큐리티 측에서 `USER_LOGOUT` Webhook 이벤트를 수신하고 처리하는 **수신 핸들러**를 구현해야 한다.

**방안 B: 직접 SLO API 호출 (내부망 필요)**

원패스 서버가 드림시큐리티 SSO의 로그아웃 API를 직접 호출. 내부망 접근이 가능한 경우에만 사용.

```java
// SloServiceImpl에 추가 가능한 메서드 (옵션)
private void revokeDreamSecuritySessionSafely(String qimUserId, String ssoDomain, String correlationId) {
    try {
        String url = ssoDomain + "/internal/sso-session/logout";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agency-Code", agencyCode);
        headers.set("X-Handoff-Sig", buildHmacSig(qimUserId, correlationId));
        
        restTemplate.exchange(url, HttpMethod.POST,
            new HttpEntity<>(Map.of("qimUserId", qimUserId, "correlationId", correlationId), headers),
            Void.class);
    } catch (Exception e) {
        log.warn("[SLO] 드림시큐리티 세션 종료 실패 (비치명적): qimUserId={} cause={}", qimUserId, e.getMessage());
    }
}
```

### 5.3 드림시큐리티 기관 Webhook 등록 — 운영 DB 설정

```sql
-- 드림시큐리티 기관 Webhook 설정 등록
INSERT INTO ido.agency_webhook_config (
    agency_code,
    endpoint_url,
    signing_secret_hash,   -- HMAC 서명 비밀키 해시
    connect_timeout_ms,
    read_timeout_ms,
    max_retry_count,
    retry_backoff_ms,
    event_type_filter,     -- NULL = 전체, 또는 특정 이벤트만
    active
) VALUES (
    'AGENCY_ABC',
    'https://sso-internal.agency-abc.go.kr/webhook/onepass',  -- 드림시큐리티 Webhook 수신 URL
    'SIGNING_SECRET_HASH',
    3000,
    8000,
    3,
    1000,
    '["USER_LOGOUT","MEMBER_WITHDRAWN"]',  -- SLO 관련 이벤트만 구독
    true
);
```

### 5.4 드림시큐리티 Webhook 수신 핸들러 (기관 구현 가이드)

드림시큐리티 기관 측에서 구현해야 할 Webhook 수신 로직:

```java
// 기관 측 구현 예시 (드림시큐리티 SSO 서버 내부)
@PostMapping("/webhook/onepass")
public ResponseEntity<Void> receiveOnepassWebhook(
        @RequestHeader("X-Webhook-Signature") String signature,
        @RequestBody String rawPayload) {
    
    // 1. HMAC-SHA256 서명 검증
    String expected = "sha256=" + hmacSha256(rawPayload, ONEPASS_WEBHOOK_SECRET);
    if (!MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
        return ResponseEntity.status(401).build();
    }
    
    // 2. 이벤트 파싱
    Map<String, Object> event = objectMapper.readValue(rawPayload, Map.class);
    String eventType = (String) event.get("eventType");
    String instMbrId = (String) event.get("instMbrId");  // = qimUserId
    
    // 3. USER_LOGOUT 처리
    if ("USER_LOGOUT".equals(eventType)) {
        // qimUserId → 사번 매핑 조회
        String empId = userMappingRepository.findEmpIdByQimUserId(instMbrId);
        if (empId != null) {
            // 드림시큐리티 SSO 세션 강제 만료
            dreamSecuritySsoService.invalidateSessionByEmpId(empId);
        }
    }
    
    return ResponseEntity.ok().build();
}
```

---

## 6. 대책 ④: 회원 전환/통합 정책

### 6.1 시나리오별 처리 정책

| 시나리오 | 상황 | 처리 방안 |
|---------|------|----------|
| **A. 기존 기관 회원** | 직원이 원패스 인증 후 기관 내부 서비스 접근 | CI 기반 사번 조회 → 자동 연결 → INTERNAL_SSO 세션 발급 |
| **B. 신규 직원** | 원패스 인증 성공, 기관 DB에 CI 없음 | GUEST 상태 → 기관 HR 시스템 연동 후 계정 생성 |
| **C. 퇴직자/휴직자** | 원패스 인증 성공, 기관 계정 비활성 | REJECTED / HOLD 상태 → 기관 정책에 따라 처리 |
| **D. 외부 민원인** | 대국민 서비스 접근 (직원 아님) | 드림시큐리티 SSO 불필요 → DIRECT 전략 사용 |

### 6.2 GUEST 상태 처리 상세 (G-7 해소)

`AgencyEntryController`의 GUEST 케이스가 발생할 때 드림시큐리티 기관의 처리 흐름:

```
GUEST 응답 수신 (AgencyEntryController)
  {
    "state": "GUEST",
    "qimUserId": "uuid",
    "message": "기관 회원 연결이 없습니다."
  }
  ↓
기관 자체 판단:
  - 직원 시스템인 경우 → HR 연동 페이지 안내
    "귀하의 원패스 계정이 기관 계정과 연결되지 않았습니다.
     인사팀에 CI 등록을 요청하거나, 관리자에게 문의하세요."
  - 대국민 서비스인 경우 → 일반 회원가입 안내
  ↓
계정 연결 완료 후 → 재인증 플로우
```

### 6.3 회원 전환 절차 (기존 ConversionInit API 활용)

기존 `POST /api/v1/conversion/init` API가 이미 구현되어 있으므로, 드림시큐리티 기관의 회원 전환에도 동일하게 활용 가능하다.

```
기관 측 회원 전환 플로우:
  1. 기관 관리자 시스템: JWT Signed Request 생성
     payload: { agencyCode, redirectUri, mbrId(사번), userType }
     서명: HMAC-SHA256 with API Key
  2. POST /api/v1/conversion/init
  3. ConversionSession 생성 (Redis TTL 30분)
  4. 사용자 → 원패스 인증 (휴대폰 본인확인)
  5. CI 기반 기존 원패스 계정 조회 또는 신규 생성
  6. qimUserId ↔ 기관 사번 매핑 저장
  7. INTERNAL_SSO 세션 자동 발급
```

### 6.4 기관별 전환 우선순위 권장

| 우선순위 | 기관 유형 | 권장 도입 방식 |
|---------|---------|--------------|
| 1순위 | 대국민 서비스 + 드림시큐리티 SSO | DIRECT + INTERNAL_SSO 병렬 (서비스별 구분) |
| 2순위 | 직원 전용 내부 시스템 | INTERNAL_SSO 단독 |
| 3순위 | 폐쇄망 + 드림시큐리티 SSO | BRIDGE + INTERNAL_SSO 조합 |

---

## 7. 기관별 도입 유형 판단 매트릭스

```
기관 환경 질문지:

Q1. 대국민 서비스입니까, 직원 전용 시스템입니까?
    → 대국민: DIRECT 우선 검토
    → 직원 전용: INTERNAL_SSO 검토

Q2. 드림시큐리티 SSO가 운영 중입니까?
    → 예: INTERNAL_SSO
    → 아니오: DIRECT

Q3. 기관 서버가 인터넷에서 직접 접근 가능합니까?
    → 예: DIRECT 가능
    → 아니오 (폐쇄망): BRIDGE 필수

Q4. Apache/Nginx 게이트웨이를 사용 중입니까?
    → 예 (레거시): APACHE_GATE 고려

Q5. 드림시큐리티 SSO 서버가 원패스 서버 IP를 허용할 수 있습니까?
    → 예: INTERNAL_SSO (Push 방식)
    → 아니오: BRIDGE 경유 후 드림시큐리티 연동
```

### 판단 결과 매핑표

| Q1 | Q2 | Q3 | Q4 | Q5 | 권장 전략 |
|----|----|----|----|----|----------|
| 대국민 | 아니오 | 예 | - | - | DIRECT |
| 대국민 | 예 | 예 | - | 예 | DIRECT + INTERNAL_SSO |
| 직원 | 예 | 예 | - | 예 | INTERNAL_SSO |
| 직원 | 예 | 아니오 | - | - | BRIDGE → INTERNAL_SSO |
| 직원 | 예 | 예 | 예 | 예 | APACHE_GATE → INTERNAL_SSO |

---

## 8. 운영 배포 체크리스트

### 8.1 원패스 서버 측 설정 (운영 수동 작업)

- [ ] **`ido.agency_meta` DB 등록** (기관별)
  - `agency_code`, `official_name`, `integration_type = 'INTERNAL_SSO'`, `sso_domain` 입력
  - `api_key_hash` = PBKDF2(발급한 API Key) 등록
  - `callback_whitelist` = 기관 콜백 URL JSON 배열
  
- [ ] **K8s Secret 등록** (기관별 API Key)
  ```bash
  kubectl create secret generic agency-api-key-AGENCY_CODE \
    --from-literal=api-key="<생성한 API Key>" \
    -n ido-prod
  ```

- [ ] **Webhook 설정 등록** (`ido.agency_webhook_config`)
  - `endpoint_url` = 드림시큐리티 Webhook 수신 URL
  - `event_type_filter` = `["USER_LOGOUT","MEMBER_WITHDRAWN"]`
  - `signing_secret_hash` 등록

- [ ] **네트워크 방화벽 규칙** — 원패스 서버 → 드림시큐리티 `sso_domain` 포트 443 허용

- [ ] **Resilience4j Retry 설정** (`application.yml` `internalSsoHandoff` 인스턴스 추가)

### 8.2 드림시큐리티 기관 측 구현 필요

- [ ] `/internal/sso-session` 엔드포인트 구현 (PRE_REGISTERED 세션 저장)
- [ ] `/internal/sso-session/{ticketId}/activate` 엔드포인트 구현
- [ ] `/webhook/onepass` Webhook 수신 핸들러 구현 (USER_LOGOUT 처리)
- [ ] `X-Handoff-Sig` HMAC-SHA256 서명 검증 로직 구현
- [ ] qimUserId ↔ 기관 사번 매핑 테이블 구성
- [ ] GUEST 상태 수신 시 계정 연결 UI 구현

### 8.3 테스트 시나리오 (필수)

| # | 시나리오 | 예상 결과 |
|---|---------|----------|
| T-1 | INTERNAL_SSO 세션 사전 등록 성공 | 201 PRE_REGISTERED |
| T-2 | 사용자 콜백 도달 → 활성화 | 200 ACTIVATED + JSESSIONID 쿠키 |
| T-3 | 잘못된 X-Handoff-Sig | 401 Unauthorized |
| T-4 | ssoDomain 미설정 기관 | warn log + 스킵 (Ticket은 정상 발급) |
| T-5 | SLO 후 Webhook USER_LOGOUT 수신 | 드림시큐리티 세션 만료 |
| T-6 | GUEST 상태 응답 처리 | 계정 연결 안내 UI 표시 |
| T-7 | 드림시큐리티 SSO 서버 장애 (Retry 3회 실패) | Fallback DLQ 적재 + Alert |

---

## 9. FAQ / 자주 묻는 질문

### Q. 드림시큐리티 SSO가 SAML 기반인데 원패스(OIDC)와 어떻게 연동합니까?

SAML ↔ OIDC 직접 프로토콜 변환은 복잡하다. 원패스의 `INTERNAL_SSO` 전략은 **프로토콜 변환 없이** 동작한다:

1. 원패스 OIDC 인증 완료 → Handoff Ticket 발급
2. 드림시큐리티 SSO 서버에 Ticket 메타데이터만 Push (세션 사전 등록)
3. 드림시큐리티가 자체 세션 발급 (SAML 세션 유지)

→ 프로토콜 변환 없이 "세션 사전 등록 + 활성화" 방식으로 연동.

### Q. 드림시큐리티 SSO 서버에 어떤 수정이 필요합니까?

드림시큐리티 SSO 서버에 **2개 엔드포인트 추가**가 최소 요건:

1. `POST /internal/sso-session` — 원패스로부터 세션 사전 등록 수신
2. `GET /internal/sso-session/{ticketId}/activate` — 사용자 도달 시 활성화

두 API는 드림시큐리티 SSO 서버의 기존 세션 관리 API에 어댑터 레이어로 추가하는 방식이 가장 간단하다.

### Q. 원패스 서버가 기관 내부망에 접근할 수 없는 경우는?

`BRIDGE` 전략을 먼저 사용하여 폐쇄망 Bridge 서버를 경유한다. Bridge 서버가 드림시큐리티 SSO 서버에 세션 등록을 중계하는 구조로 설계한다.

```
원패스 → BRIDGE 서버 (DMZ) → 드림시큐리티 SSO (내부망)
```

### Q. 기관이 여러 개의 드림시큐리티 SSO 서버를 운영하는 경우?

`agency_meta.sso_domain`은 단일 값이다. 여러 SSO 서버가 있는 경우:

- **방법 1**: 기관 코드를 구분 (`AGENCY_ABC_SSO1`, `AGENCY_ABC_SSO2`)
- **방법 2**: 기관 측 로드밸런서 뒤에 단일 엔드포인트 노출

### Q. JTI 재사용 방지가 미구현(P1 과제)인데 INTERNAL_SSO 연동에 영향이 있습니까?

`InternalSsoHandoffStrategy.postIssue()`는 Handoff Ticket 발급 직후 1회만 호출된다. JTI 재사용 방지 미구현은 **별도 JWT 기반 API (ConversionInit)에 해당**하며, INTERNAL_SSO Handoff 흐름과는 직접 연관이 없다. 단, ConversionInit API 사용 시 JTI 재사용 방지 구현이 완료될 때까지 운영 사용에 주의가 필요하다.

### Q. 드림시큐리티 SSO 연동 기관 수 파악 방법은?

현재 파악 안 된 상황. 다음 방법으로 확인 권장:

```sql
-- 현재 등록된 기관 중 INTERNAL_SSO 유형 조회
SELECT agency_code, official_name, sso_domain, active
FROM ido.agency_meta
WHERE integration_type = 'INTERNAL_SSO'
ORDER BY agency_code;

-- 전체 기관 연동 유형 현황
SELECT integration_type, COUNT(*) as cnt
FROM ido.agency_meta
WHERE active = TRUE
GROUP BY integration_type;
```

기관 담당자 설문 또는 공공기관 SSO 도입 현황 파악을 통해 드림시큐리티 사용 기관 목록 확보 권장.

---

## 참고 문서

- `wiki/guide/01-agency-conversion-url-flow.md` — URL 플로우 분석
- `wiki/guide/02-conversion-param-security.md` — JWT HS256 보안
- `wiki/guide/03-conversion-launch-sample.md` — 기관 연동 샘플 코드
- `wiki/guide/04-conversion-data-flow-diagram.md` — 데이터 플로우 다이어그램
- `wiki/design/01-ido-service-design.md` — IdO 서비스 설계서 (§7, §8.4 참조)
- `InternalSsoHandoffStrategy.java` — INTERNAL_SSO 전략 구현 (설계서 §8.4)
- `MockSsoSessionController.java` — 드림시큐리티 SSO 서버 Mock
- `AgencyPatternSsoTest.java` — INTERNAL_SSO 패턴 테스트 4종

---

*문서 끝 — v1.0.0 / 2026-05-16*
