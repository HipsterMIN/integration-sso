# SDK/Agent 수정 보완 플랜 — 자체 SSO 보유 유관기관 연동 지원

> **명칭 안내 (2026-09-07)** — 이 문서의 `onepass.agent.*` 설정 키, `onepass-agent.properties`, `ONEPASS_*` 환경변수, `OnePass-*` 헤더, `OnePassAgent*` 클래스명은 개명 4단계(Java 패키지·런타임 식별자) 전까지 **구명을 그대로 사용**한다. 모듈·이미지·파일 이름만 Idem 신명이다. 대응표: [docs/naming.md](../../naming.md) §3.

> **문서 유형**: 내부 개발 계획서 (기술 검토 후 확정 필요)
> **작성일**: 2026-05-17
> **작성자**: AI Developer (코드 분석 기반)
> **검토 대상**: 플랫폼 팀 리드, SDK 담당자, Agent 담당자
> **브랜치**: `shipster`

---

## 0. 배경 및 목적

### 0.1 현황 분석 요약

코드 기반 분석 결과, 현재 OnePass 연동 체계는 **표준 기관**(SSO 없음, API만 구현)을 주요 대상으로 설계되어 있다. 자체 SSO/IM을 보유한 유관기관이 증가함에 따라 아래 두 컴포넌트의 보완이 필요하다.

| 컴포넌트 | 현재 상태 | 보완 필요 사항 |
|---------|----------|-------------|
| `idem-sdk-java` | Gateway 이벤트 송수신 전용 | Handoff Ticket 검증, 회원 전환 지원 |
| `idem-agent` | Authorization 헤더 Bearer 토큰만 추출 | 쿠키/파라미터 토큰 추출 전략 확장 |

### 0.2 자체 SSO 기관의 연동 가능성 결론

| 조건 | 결론 | 근거 |
|------|------|------|
| 기관이 2개 API 구현 | **가능** | `lookup` + `link` API → `agency_user.qim_user_id` 매핑 |
| CI 소급 수집 | **불필요** | 전환 시 본인인증(NICE/PASS) 1회로 해결 |
| identifierHash 역방향 조회 | **불가** | SHA-256 단방향 — 행정적 이슈, 코드 변경으로 해결 불가 |
| 기존 회원 부분 실패 | **허용** (주의 필요) | `AgencyMemberLookupServiceImpl` partial-failure 설계 |

---

## 1. SDK 수정 보완 계획

### 1.1 현재 SDK 기능 범위

```
AgencyGatewayClient
├── sendInbound(InboundEvent)       ← 기관 → OnePass 이벤트 전송
├── triggerOutbound(OutboundNotifyRequest) ← Webhook 트리거
└── getStatus(agencyCode)           ← 연동 상태 조회

모델
├── InboundEvent                    ← 이벤트 DTO
├── OutboundNotifyRequest           ← 아웃바운드 요청 DTO
└── GatewayResponse                 ← raw JSON body 응답 (파싱 없음)
```

### 1.2 누락된 기능 분석

#### [GAP-1] Handoff Ticket 검증 클라이언트 없음

**현황**: `idem-hub/HandoffController`에 `/api/v1/handoff/verify` API가 있지만, SDK에 이를 호출하는 메서드가 없다.

**영향**: 자체 SSO 기관이 OnePass 인증 완료 후 HandoffTicket을 검증하려면 직접 HTTP 호출 코드를 작성해야 함.

```java
// 현재 AgencyGatewayClient에 없는 경로
// PATH_HANDOFF_VERIFY = "/api/v1/handoff/verify"  ← 누락
```

#### [GAP-2] 회원 전환 API 호출 지원 없음

**현황**: Q-IM의 회원 전환 5단계(`INITIATED → COMPLETED`)를 트리거하는 SDK 메서드가 없다.

**영향**: 자체 SSO 기관이 회원 전환 흐름을 시작할 때 SDK 없이 직접 REST 호출.

#### [GAP-3] GatewayResponse body 파싱 없음

**현황**: `GatewayResponse.body`는 raw JSON 문자열. 기관이 응답을 파싱하려면 Jackson 등을 직접 사용해야 함.

```java
// 현재: raw JSON만 반환
public String getBody() { return body; }

// 필요: 구조화된 필드 접근 (외부 JSON 라이브러리 없이)
public String getBodyField(String key) { ... } // 단순 파싱 헬퍼
```

#### [GAP-4] SPI 인터페이스 없음 (자체 SSO 기관 커스터마이징)

**현황**: 기관이 SDK를 확장할 수 있는 SPI(Service Provider Interface)가 없어, 자체 SSO 세션 처리 로직을 삽입하기 어렵다.

---

### 1.3 SDK 수정 항목 (우선순위별)

#### [P1] HandoffVerifyClient 추가 ← **즉시 구현 권장**

**파일**: `idem-sdk-java/src/main/java/io/github/hipstermin/idem/sdk/agency/HandoffVerifyClient.java` (신규)

```java
/**
 * Handoff Ticket 검증 클라이언트
 *
 * <p>OnePass 인증 완료 후 IdO가 발급한 HandoffTicket을 검증하고
 * HandoffPayload(사용자 정보)를 수신한다.
 *
 * <h3>Handoff 흐름</h3>
 * <pre>
 *   사용자 브라우저   →  원패스 IdO          →  기관 콜백 URL
 *   [인증 완료]         [HandoffTicket 발급]    [ticketId 수신]
 *                                                    ↓
 *                        [POST /api/v1/handoff/verify ← SDK 호출]
 *                        [HandoffPayload 반환]
 *                            ↓
 *                        [기관 세션 생성]
 * </pre>
 *
 * <h3>사용 예시</h3>
 * <pre>{@code
 * HandoffVerifyClient handoff = HandoffVerifyClient.builder()
 *     .baseUrl("http://ido:8083")
 *     .apiKey("agency-api-key")
 *     .agencyCode("AGENCY_001")
 *     .build();
 *
 * HandoffPayload payload = handoff.verify(ticketId);
 * String userId = payload.getSubject();      // OnePass 사용자 식별자
 * String identifierHash = payload.getIdentifierHash(); // SHA-256(CI) - 회원 전환용
 * }</pre>
 */
public final class HandoffVerifyClient {
    private static final String PATH_HANDOFF_VERIFY = "/api/v1/handoff/verify";
    // ... Builder 패턴, AgencyHttpAdapter 재사용
    
    public HandoffPayload verify(String ticketId) { ... }
    public HandoffPayload verify(String ticketId, String agencyCode) { ... }
}
```

**신규 모델**:

```java
// idem-sdk-java/.../model/HandoffVerifyRequest.java
public final class HandoffVerifyRequest {
    private final String ticketId;
    private final String agencyCode;
    // toJsonString() — 외부 JSON 라이브러리 없이 수동 직렬화
}

// idem-sdk-java/.../model/HandoffPayload.java
public final class HandoffPayload {
    private final String subject;         // OnePass 사용자 식별자 (sub claim)
    private final String identifierHash;  // SHA-256(CI) — 회원 전환 시 기관 조회 키
    private final String agencyCode;
    private final String issuedAt;        // ISO-8601
    private final String expiresAt;       // ISO-8601 (기본 5분)
    private final Map<String, String> claims; // 추가 클레임 (선택)
    
    // JSON 파싱: 외부 의존성 없이 단순 정규식/String 파싱
    public static HandoffPayload fromJson(String json) { ... }
}
```

**구현 원칙**:
- `AgencyHttpAdapter` 재사용 — HttpURLConnection 기본, OkHttp 교체 가능
- Java 8+ 호환 유지
- 외부 JSON 라이브러리 의존성 ZERO
- HMAC 서명 선택적 지원 (기존 `HmacSigner` 재사용)

---

#### [P2] AgencyGatewayClient.verifyHandoff() 통합 메서드 추가 ← **P1 완료 후**

**파일**: `AgencyGatewayClient.java` 수정

```java
// AgencyGatewayClient에 추가할 메서드
/**
 * Handoff Ticket 검증 (편의 메서드)
 *
 * <p>HandoffVerifyClient를 별도 생성하지 않고 기존 클라이언트 인스턴스에서 호출 가능.
 * baseUrl, apiKey, agencyCode는 클라이언트 설정값을 그대로 사용한다.
 *
 * @param ticketId HandoffTicket ID (기관 콜백 URL 파라미터로 수신)
 * @return HandoffPayload — 사용자 정보 + identifierHash
 */
public HandoffPayload verifyHandoff(String ticketId) { ... }

// PATH 상수 추가
private static final String PATH_HANDOFF_VERIFY = "/api/v1/handoff/verify";
```

---

#### [P3] GatewayResponse body 파싱 헬퍼 추가 ← **독립적으로 구현 가능**

**파일**: `GatewayResponse.java` 수정

```java
// 추가할 메서드들
/**
 * body JSON에서 특정 최상위 필드의 문자열 값을 추출한다.
 *
 * <p>외부 JSON 라이브러리 없이 단순 패턴 매칭으로 구현.
 * 중첩 객체나 배열이 필요한 경우 {@link #getBody()}로 raw JSON을 직접 파싱하세요.
 *
 * @param key JSON 최상위 필드명
 * @return 필드 값 문자열, 없으면 null
 */
public String getBodyField(String key) { ... }

/**
 * body JSON에서 특정 필드의 정수 값을 추출한다.
 */
public Integer getBodyFieldInt(String key) { ... }

/**
 * 성공 응답의 body를 HandoffPayload로 역직렬화한다.
 * HTTP 상태가 2xx가 아니면 AgencySdkException을 던진다.
 */
public HandoffPayload toHandoffPayload() { ... }
```

---

#### [P4] MemberConversionAdapter SPI 인터페이스 ← **자체 SSO 기관 전용, 낮은 우선순위**

**파일**: `idem-sdk-java/src/main/java/io/github/hipstermin/idem/sdk/agency/spi/MemberConversionAdapter.java` (신규)

```java
/**
 * 회원 전환 SPI (Service Provider Interface)
 *
 * <p>자체 SSO 기관이 이 인터페이스를 구현하면, SDK가 회원 전환 흐름의
 * 기관 측 로직을 자동으로 호출한다.
 *
 * <h3>구현 가이드</h3>
 * <ul>
 *   <li>{@link #lookupMember(String)} — identifierHash로 기존 회원 조회</li>
 *   <li>{@link #linkMember(String, String)} — qim_user_id 매핑 저장</li>
 * </ul>
 *
 * <h3>자체 SSO 기관 DB 스키마 요구사항</h3>
 * <pre>
 * -- 기존 회원 테이블에 컬럼 추가
 * ALTER TABLE {기관_회원_테이블}
 *   ADD COLUMN ci_hash    VARCHAR(300),  -- SHA-256(CI) = identifierHash
 *   ADD COLUMN qim_user_id VARCHAR(36);   -- OnePass Q-IM 사용자 ID
 * CREATE INDEX idx_ci_hash ON {기관_회원_테이블}(ci_hash);
 * </pre>
 */
public interface MemberConversionAdapter {

    /**
     * identifierHash(SHA-256(CI))로 기존 회원을 조회한다.
     *
     * @param identifierHash SHA-256(CI) — HandoffPayload.getIdentifierHash()로 수신
     * @return 조회된 회원 정보, 없으면 null (ACTIVE 상태만 반환 권장)
     */
    AgencyMemberInfo lookupMember(String identifierHash);

    /**
     * 회원에 Q-IM 사용자 ID를 매핑한다.
     *
     * @param identifierHash SHA-256(CI)
     * @param qimUserId      OnePass Q-IM 사용자 ID (UUID 형식)
     * @return 성공 여부
     */
    boolean linkMember(String identifierHash, String qimUserId);
}

// 함께 추가할 DTO
public final class AgencyMemberInfo {
    private final String agencyUserId;  // 기관 내부 사용자 ID
    private final String agencyCode;
    private final String status;        // ACTIVE / SUSPENDED / WITHDRAWN
    private final String lastLoginAt;   // ISO-8601
    // ...
}
```

---

### 1.4 SDK 수정 영향 범위

| 수정 항목 | 영향 범위 | 하위 호환성 |
|----------|----------|-----------|
| `HandoffVerifyClient` (신규) | 신규 클래스 추가 | **완전 하위 호환** |
| `AgencyGatewayClient.verifyHandoff()` | 메서드 추가 | **완전 하위 호환** |
| `GatewayResponse` 파싱 헬퍼 | 메서드 추가 | **완전 하위 호환** |
| `MemberConversionAdapter` SPI | 신규 패키지 추가 | **완전 하위 호환** |
| `HandoffPayload` 모델 | 신규 클래스 추가 | **완전 하위 호환** |

> **결론**: 모든 수정 항목이 기존 코드에 영향을 주지 않는 순수 추가(addition)이므로, 기존 기관의 재컴파일 없이 SDK 버전 업그레이드만으로 적용 가능하다.

---

## 2. Agent 수정 보완 계획

### 2.1 현재 Agent 토큰 추출 로직

```java
// GenericFilterWeavingStrategy.java (현재)
private static String extractBearerToken(Object request) {
    try {
        String auth = (String) request.getClass()
                .getMethod("getHeader", String.class)
                .invoke(request, "Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return auth.substring(7).trim();
    } catch (Exception e) {
        return null;
    }
}
```

**문제**: `Authorization: Bearer <token>` 헤더만 지원. 자체 SSO를 보유한 기관은 토큰을 쿠키나 커스텀 헤더에 실어 전송하는 경우가 많음.

### 2.2 Agent 수정 항목 (우선순위별)

#### [PA-1] 토큰 추출 전략 멀티-소스 지원 ← **즉시 구현 권장**

**파일**: `GenericFilterWeavingStrategy.java` 수정, `AgentConfig.java` 수정

**추가 설정 키** (`onepass-agent.properties`):

```properties
# 토큰 추출 소스 (기본: header)
# 가능한 값: header | cookie | param | header,cookie | header,cookie,param
onepass.agent.token-source=header

# 쿠키 기반 토큰 추출 시 쿠키 이름 (token-source에 cookie 포함 시 필수)
onepass.agent.token-cookie-name=ONEPASS_TOKEN

# 파라미터 기반 토큰 추출 시 파라미터 이름 (token-source에 param 포함 시 필수)
onepass.agent.token-param-name=access_token

# 커스텀 헤더 이름 (기본: Authorization, Bearer 접두사 제거)
onepass.agent.token-header-name=Authorization
```

**코드 변경**:

```java
// AgentConfig.java 추가 필드
public static final String KEY_TOKEN_SOURCE      = "onepass.agent.token-source";
public static final String KEY_TOKEN_COOKIE_NAME = "onepass.agent.token-cookie-name";
public static final String KEY_TOKEN_PARAM_NAME  = "onepass.agent.token-param-name";
public static final String KEY_TOKEN_HEADER_NAME = "onepass.agent.token-header-name";

// GenericFilterWeavingStrategy.java 수정
// extractBearerToken() → extractToken() 으로 rename + 전략 분기

private static String extractToken(Object request, AgentConfig cfg) {
    String sources = cfg.tokenSource(); // "header,cookie,param" 등
    
    // 우선순위: header → cookie → param (설정 순서 기반)
    for (String source : sources.split(",")) {
        String token = null;
        switch (source.trim().toLowerCase()) {
            case "header": token = extractFromHeader(request, cfg.tokenHeaderName()); break;
            case "cookie": token = extractFromCookie(request, cfg.tokenCookieName()); break;
            case "param":  token = extractFromParam(request, cfg.tokenParamName()); break;
        }
        if (token != null && !token.isEmpty()) return token;
    }
    return null;
}

private static String extractFromHeader(Object request, String headerName) {
    try {
        String auth = (String) request.getClass()
                .getMethod("getHeader", String.class)
                .invoke(request, headerName);
        if (auth == null) return null;
        // "Authorization" 헤더면 "Bearer " 접두사 제거
        if ("Authorization".equalsIgnoreCase(headerName) && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return auth.trim();
    } catch (Exception e) { return null; }
}

private static String extractFromCookie(Object request, String cookieName) {
    try {
        Object[] cookies = (Object[]) request.getClass()
                .getMethod("getCookies")
                .invoke(request);
        if (cookies == null) return null;
        for (Object cookie : cookies) {
            String name = (String) cookie.getClass().getMethod("getName").invoke(cookie);
            if (cookieName.equals(name)) {
                return (String) cookie.getClass().getMethod("getValue").invoke(cookie);
            }
        }
        return null;
    } catch (Exception e) { return null; }
}

private static String extractFromParam(Object request, String paramName) {
    try {
        return (String) request.getClass()
                .getMethod("getParameter", String.class)
                .invoke(request, paramName);
    } catch (Exception e) { return null; }
}
```

---

#### [PA-2] 자체 SSO 기관 bypass URI 화이트리스트 설정화 ← **즉시 구현 권장**

**현황**: `shouldBypass(uri)` 메서드가 하드코딩된 경로 목록으로 바이패스를 결정한다. 자체 SSO 기관은 SSO 관련 엔드포인트(`/sso/**`, `/saml/**`)를 추가로 바이패스해야 할 수 있다.

**추가 설정 키**:

```properties
# 바이패스 URI 패턴 (쉼표 구분, Ant 패턴 지원)
# 기본값: /actuator/**,/health,/favicon.ico
onepass.agent.bypass-uris=/actuator/**,/health,/favicon.ico,/sso/**,/saml/**
```

**코드 변경**:

```java
// AgentConfig.java 추가
public static final String KEY_BYPASS_URIS = "onepass.agent.bypass-uris";
private final String[] bypassUris; // 설정에서 파싱한 바이패스 패턴 배열

// GenericFilterWeavingStrategy.java 수정
private static boolean shouldBypass(String uri, AgentConfig cfg) {
    // 1. 설정 기반 바이패스 패턴 체크
    for (String pattern : cfg.bypassUris()) {
        if (matchesPattern(uri, pattern)) return true;
    }
    // 2. 정적 파일 확장자 체크 (기존 로직 유지)
    return isStaticResource(uri);
}

// 단순 Ant 패턴 매칭 (/** 지원, 외부 라이브러리 없이)
private static boolean matchesPattern(String uri, String pattern) {
    if (pattern.endsWith("/**")) {
        String prefix = pattern.substring(0, pattern.length() - 3);
        return uri.startsWith(prefix);
    }
    return uri.equals(pattern);
}
```

---

#### [PA-3] Agent 설정 검증 강화 ← **낮은 우선순위**

**현황**: `AgentConfig`에 `token-source`, `bypass-uris` 등 신규 키 추가 시 검증 로직도 함께 확장해야 함.

**추가 내용**:
- `token-source` 허용값 검증: `header`, `cookie`, `param` 이외 값 입력 시 경고 로그 + `header` fallback
- `bypass-uris` 패턴 형식 검증 (빈 문자열, 특수문자 처리)

---

### 2.3 Agent 수정 영향 범위

| 수정 항목 | 영향 범위 | 하위 호환성 |
|----------|----------|-----------|
| `token-source` 기본값 `header` | 기존 동작 동일 | **완전 하위 호환** |
| `extractBearerToken()` → `extractToken()` | Advice 내부 로직 | **완전 하위 호환** (외부 API 없음) |
| `bypass-uris` 설정화 | 기존 하드코딩 패턴 유지 + 확장 | **완전 하위 호환** |

> **결론**: 신규 설정 키를 추가하지 않으면 기존 기관의 동작이 100% 동일하게 유지된다.

---

## 3. 구현 일정 및 우선순위

### 3.1 즉시 구현 (Sprint 현재)

| 항목 | 예상 공수 | 담당 |
|------|----------|------|
| [SDK-P1] `HandoffVerifyClient` + `HandoffPayload` 신규 | 2일 | SDK 담당자 |
| [SDK-P2] `AgencyGatewayClient.verifyHandoff()` | 0.5일 | SDK 담당자 |
| [SDK-P3] `GatewayResponse` body 파싱 헬퍼 | 0.5일 | SDK 담당자 |
| [PA-1] Agent 토큰 추출 멀티-소스 | 1.5일 | Agent 담당자 |
| [PA-2] bypass-uris 설정화 | 0.5일 | Agent 담당자 |

### 3.2 다음 Sprint

| 항목 | 예상 공수 | 담당 |
|------|----------|------|
| [SDK-P4] `MemberConversionAdapter` SPI | 1.5일 | SDK 담당자 |
| [PA-3] Agent 설정 검증 강화 | 0.5일 | Agent 담당자 |
| 통합 테스트 (agency-stub 활용) | 1일 | QA |

### 3.3 전체 타임라인

```
Week 1 (현재)
├── SDK: HandoffVerifyClient + verifyHandoff() + body 파싱 헬퍼
├── Agent: 토큰 추출 멀티-소스 + bypass-uris 설정화
└── 단위 테스트 작성 (131개 기존 테스트 유지 + 신규 추가)

Week 2
├── SDK: MemberConversionAdapter SPI
├── Agent: 설정 검증 강화
├── agency-stub Profile("bridge") 연동 통합 테스트
└── 문서 배포 (개발자 / SSO 담당자 / 운영 가이드)

Week 3
├── Staging 환경 검증 (자체 SSO 기관 1곳 파일럿)
└── PR 최종 머지 및 배포
```

---

## 4. 테스트 전략

### 4.1 SDK 테스트

```java
// HandoffVerifyClientTest.java (신규)
@Test void verify_정상_응답_HandoffPayload_파싱() { ... }
@Test void verify_만료된_티켓_AgencyHttpException_발생() { ... }
@Test void verify_ticketId_null_AgencySdkException_발생() { ... }
@Test void verifyHandoff_AgencyGatewayClient_통합() { ... }

// GatewayResponseTest.java (추가)
@Test void getBodyField_문자열_필드_추출() { ... }
@Test void getBodyField_존재하지_않는_키_null_반환() { ... }
@Test void toHandoffPayload_성공_역직렬화() { ... }
```

### 4.2 Agent 테스트

```java
// TokenExtractionTest.java (신규)
@Test void extractToken_헤더_전략_Bearer_추출() { ... }
@Test void extractToken_쿠키_전략_쿠키값_추출() { ... }
@Test void extractToken_파라미터_전략_쿼리스트링_추출() { ... }
@Test void extractToken_헤더_없으면_쿠키_fallback() { ... }

// BypassUriTest.java (추가)
@Test void shouldBypass_설정_패턴_와일드카드_매칭() { ... }
@Test void shouldBypass_sso_경로_바이패스() { ... }
```

### 4.3 통합 테스트 (agency-stub 활용)

- `@Profile("bridge")` MockSsoSessionController로 자체 SSO 시뮬레이션
- HandoffTicket 발급 → SDK `verifyHandoff()` → `HandoffPayload` 수신 전체 흐름 검증

---

## 5. 결정 필요 사항 (팀 검토 요청)

| # | 항목 | 옵션 A | 옵션 B | 권고 |
|---|------|--------|--------|------|
| D1 | `HandoffVerifyClient` 위치 | 별도 클래스 | `AgencyGatewayClient` 내부 | 별도 클래스 (SRP) |
| D2 | body JSON 파싱 방식 | 단순 String 파싱 | 경량 JSON 파서(GSON Lite) 내장 | 단순 String 파싱 (의존성 ZERO 원칙) |
| D3 | `token-source` 기본값 | `header` (현재 동작) | `header,cookie` | `header` (하위 호환) |
| D4 | `MemberConversionAdapter` 구현 위치 | SDK 내 기본 구현 제공 | 기관이 전적으로 구현 | 기관 전적 구현 (유연성) |
| D5 | Agent bypass 패턴 언어 | Ant 패턴 (`/**`) | 정규식 | Ant 패턴 (단순, Java 8 호환) |

---

## 6. 리스크 및 완화 방안

| 리스크 | 발생 가능성 | 영향도 | 완화 방안 |
|--------|-----------|--------|---------|
| Agent Reflection ClassLoader 격리 이슈 (쿠키 추출 신규) | 중 | 중 | agency-stub 통합 테스트로 사전 검증 |
| HandoffPayload JSON 파싱 오류 (서버 응답 스키마 변경) | 저 | 고 | `getBody()` raw 접근 fallback 항상 유지 |
| `token-source=cookie` 설정 시 세션 하이재킹 위험 | 저 | 고 | HTTPS 강제, SameSite=Strict 쿠키 정책 문서화 |
| 자체 SSO 기관 DB CI 컬럼 소급 추가 난항 | 고 | 중 | 전환 희망자만 점진적 CI 수집 가이드 문서화 |

---

## 부록 A. 코드 기반 분석 참조

| 분석 파일 | 핵심 발견 사항 |
|-----------|-------------|
| `GenericFilterWeavingStrategy.java` L198-208 | `extractBearerToken()` — Authorization 헤더만 지원 |
| `AgentConfig.java` L42-50 | 현재 설정 키 목록 — `token-source` 없음 |
| `AgencyGatewayClient.java` L89-92 | PATH 상수 — `PATH_HANDOFF_VERIFY` 없음 |
| `GatewayResponse.java` L46-58 | `body`는 raw JSON만 반환, 파싱 메서드 없음 |
| `HandoffController.java` | `/api/v1/handoff/verify` 서버 API 존재 확인 |
| `ConversionSessionServiceImpl.java` | 5단계 전환 세션 — SDK에 대응 메서드 없음 |

---

*이 플랜은 코드 분석을 기반으로 작성된 초안입니다. 구현 시작 전 팀 검토 및 결정 필요 사항(섹션 5) 합의가 선행되어야 합니다.*
