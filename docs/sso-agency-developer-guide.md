# OnePass 자체 SSO 기관 연동 — 개발자 레퍼런스

> **대상 독자**: 유관기관 백엔드 개발자, 플랫폼 연동 담당 개발자
> **버전**: v1.0 (2026-05-17)
> **관련 SDK**: `onepass-agency-sdk` (Java 8+, 런타임 의존성 ZERO)
> **관련 Agent**: `onepass-agent` (byte-buddy/Javassist 위빙)

---

## 목차

1. [개요 및 아키텍처](#1-개요-및-아키텍처)
2. [일반 기관 vs 자체 SSO 기관 차이](#2-일반-기관-vs-자체-sso-기관-차이)
3. [기관이 구현해야 하는 2개 API](#3-기관이-구현해야-하는-2개-api)
4. [DB 스키마 — 자체 SSO 기관 추가 컬럼](#4-db-스키마--자체-sso-기관-추가-컬럼)
5. [SDK 설치 및 기본 설정](#5-sdk-설치-및-기본-설정)
6. [Handoff Ticket 검증 구현](#6-handoff-ticket-검증-구현)
7. [회원 전환 흐름 구현](#7-회원-전환-흐름-구현)
8. [Agent 설치 및 토큰 추출 설정](#8-agent-설치-및-토큰-추출-설정)
9. [identifierHash 처리 상세](#9-identifierhash-처리-상세)
10. [에러 처리 및 부분 실패 허용 패턴](#10-에러-처리-및-부분-실패-허용-패턴)
11. [테스트 환경 및 통합 검증](#11-테스트-환경-및-통합-검증)
12. [API 레퍼런스 요약](#12-api-레퍼런스-요약)

---

## 1. 개요 및 아키텍처

### 1.1 OnePass 연동 전체 흐름

```
┌─────────────────────────────────────────────────────────────────────┐
│                        사용자 브라우저                                │
└──────────────────┬──────────────────────────────────┬───────────────┘
                   │ ①로그인 요청                       │ ⑥기관 세션 완료
                   ▼                                   ▲
┌──────────────────────────────┐       ┌───────────────────────────────┐
│     OnePass IdO 서버          │       │   유관기관 애플리케이션          │
│  (Identity Orchestrator)     │       │   (자체 SSO 기관)              │
│                              │       │                               │
│  ②원패스 인증 처리             │       │  ⑤HandoffTicket 검증           │
│  ③HandoffTicket 발급          │──────▶│  → HandoffPayload 수신         │
│  GET {기관콜백}?ticket={id}   │       │  → 기관 세션 생성               │
└──────────────────────────────┘       │                               │
                                       │  [선택] 회원 전환               │
┌──────────────────────────────┐       │  ④ POST /api/v1/members/lookup │
│     Q-IM 서버                 │◀──────│  → identifierHash 조회         │
│  (사용자 관리)                 │       │  ④ POST /api/v1/members/link   │
│                              │──────▶│  → qim_user_id 매핑 저장       │
└──────────────────────────────┘       └───────────────────────────────┘
```

### 1.2 핵심 개념

| 개념 | 설명 |
|------|------|
| **HandoffTicket** | OnePass 인증 완료 후 발급되는 단기 토큰 (기본 5분 유효). 기관 콜백 URL에 `?ticket={ticketId}` 형태로 전달됨 |
| **HandoffPayload** | HandoffTicket 검증 성공 시 반환되는 사용자 정보 객체. `subject`(사용자 ID), `identifierHash` 포함 |
| **identifierHash** | `SHA-256(CI)`로 계산된 사용자 식별자. CI(연계정보)는 주민번호 기반 NICE/PASS 인증 시 발급. 원패스가 기관 회원 조회 시 사용하는 유일 키 |
| **agency_subject_id** | `agency_user` 테이블에서 `identifierHash`를 저장하는 컬럼명 |
| **qim_user_id** | OnePass Q-IM 시스템의 사용자 UUID. 회원 전환 완료 시 기관 DB에 저장 |

---

## 2. 일반 기관 vs 자체 SSO 기관 차이

### 2.1 연동 방식 비교

| 항목 | 일반 기관 | 자체 SSO 기관 |
|------|----------|-------------|
| 로그인 처리 | OnePass가 전담 | 기관 내부 SSO가 처리 + OnePass 인증 병행 |
| 세션 관리 | OnePass HandoffPayload → 기관 세션 | 기관 SSO 세션 유지 (OnePass는 검증만) |
| 회원 전환 | Agent JWT 검증으로 기본 지원 | lookup/link API 추가 구현 필요 |
| Agent 토큰 소스 | Authorization 헤더 Bearer | 쿠키 또는 커스텀 헤더 가능 |
| DB 변경 | 불필요 (기본 지원) | `ci_hash`, `qim_user_id` 컬럼 추가 |

### 2.2 자체 SSO 기관의 "완전한 연동 불가능" 케이스

아래 3가지는 코드 변경으로 해결할 수 없는 한계다.

1. **CI 수집 이력 없는 기존 회원 소급 처리 불가**
   - 기관이 과거에 NICE/PASS 본인인증을 수행했어도, CI를 별도로 저장하지 않았다면 `identifierHash` 역산 불가
   - 해결책: 전환 희망자가 재본인인증 시 CI 1회 수집

2. **identifierHash → 기관 내부 ID 역방향 조회 불가**
   - SHA-256은 단방향 함수. `identifierHash`로부터 원본 CI나 기관 ID를 복원할 수 없음
   - 해결책: 기관 DB에 `ci_hash` 컬럼 추가 후 전환 시 매핑

3. **부분 실패 허용 구조**
   - Q-IM은 68개 기관을 병렬 조회하며, 일부 기관이 응답 실패해도 다른 기관 결과로 전환 완료
   - 기관 API가 타임아웃(15초 초과)이면 해당 기관 회원은 연결 실패로 처리됨

---

## 3. 기관이 구현해야 하는 2개 API

자체 SSO 기관을 포함한 **모든 유관기관**이 구현해야 하는 API. `agency-stub` 모듈의 `AgencyMemberLookupController.java`가 레퍼런스 구현체다.

### 3.1 POST /api/v1/members/lookup — 회원 조회

```
POST https://{기관도메인}/api/v1/members/lookup
Content-Type: application/json
X-Agency-Key: {기관_API_키}
```

**요청 본문**:
```json
{
  "identifierHash": "a3f2c8d1...",  // SHA-256(CI), 64자리 16진수
  "agencyCode": "AGENCY_001"
}
```

**응답 — 회원 존재 (200 OK)**:
```json
{
  "found": true,
  "agencyUserId": "user-uuid-1234",   // 기관 내부 사용자 ID
  "status": "ACTIVE",                  // ACTIVE | SUSPENDED | WITHDRAWN
  "lastLoginAt": "2026-05-10T09:30:00Z",
  "createdAt": "2024-03-15T10:00:00Z"
}
```

**응답 — 회원 없음 (200 OK)**:
```json
{
  "found": false
}
```

**레퍼런스 구현 (Java Spring Boot)**:
```java
@RestController
@RequestMapping("/api/v1/members")
public class AgencyMemberLookupController {

    @PostMapping("/lookup")
    public ResponseEntity<MemberLookupResponse> lookup(
            @RequestBody MemberLookupRequest req) {
        
        // identifierHash = agency_subject_id와 동일한 값
        Optional<AgencyUser> user = agencyUserRepository
                .findByAgencySubjectIdAndStatus(req.getIdentifierHash(), "ACTIVE");
        
        if (user.isEmpty()) {
            return ResponseEntity.ok(MemberLookupResponse.notFound());
        }
        
        AgencyUser u = user.get();
        return ResponseEntity.ok(MemberLookupResponse.found(
            u.getAgencyUserId(),
            u.getStatus(),
            u.getLastLoginAt(),
            u.getCreatedAt()
        ));
    }
}
```

> **⚠️ 주의**: `identifierHash`는 기관 DB의 `agency_subject_id`(또는 `ci_hash`) 컬럼과 매핑된다. 조회 전에 인덱스가 생성되어 있어야 한다 (섹션 4 참조).

### 3.2 POST /api/v1/members/link — 회원 매핑

```
POST https://{기관도메인}/api/v1/members/link
Content-Type: application/json
X-Agency-Key: {기관_API_키}
```

**요청 본문**:
```json
{
  "identifierHash": "a3f2c8d1...",
  "qimUserId": "550e8400-e29b-41d4-a716-446655440000",  // OnePass Q-IM UUID
  "agencyCode": "AGENCY_001"
}
```

**응답 (200 OK)**:
```json
{
  "linked": true,
  "agencyUserId": "user-uuid-1234"
}
```

**레퍼런스 구현**:
```java
@PostMapping("/link")
public ResponseEntity<MemberLinkResponse> link(
        @RequestBody MemberLinkRequest req) {
    
    int updated = agencyUserRepository.linkQimUser(
        req.getQimUserId(),
        req.getIdentifierHash(),
        req.getAgencyCode()
    );
    
    if (updated == 0) {
        return ResponseEntity.ok(MemberLinkResponse.failed("USER_NOT_FOUND"));
    }
    return ResponseEntity.ok(MemberLinkResponse.success());
}

// Repository (JPA Native Query)
@Modifying
@Query(value = """
    UPDATE agency_user
    SET qim_user_id = :qimUserId, updated_at = NOW()
    WHERE agency_subject_id = :identifierHash
      AND agency_code = :agencyCode
    """, nativeQuery = true)
int linkQimUser(@Param("qimUserId") String qimUserId,
                @Param("identifierHash") String identifierHash,
                @Param("agencyCode") String agencyCode);
```

### 3.3 API 보안 요구사항

| 요구사항 | 설명 |
|---------|------|
| **HTTPS 전용** | 모든 API 호출은 TLS 1.2+ |
| **X-Agency-Key 검증** | OnePass가 전송하는 요청에 포함된 API 키 검증 |
| **타임아웃 준수** | `lookup` 응답 15초 이내 (Q-IM 데드라인) |
| **멱등성** | `link` API는 동일한 `(identifierHash, qimUserId)` 재호출에 멱등 응답 |

---

## 4. DB 스키마 — 자체 SSO 기관 추가 컬럼

### 4.1 표준 agency_user 테이블 구조

```sql
-- 기존 테이블 구조 (변경 없음)
CREATE TABLE agency_user (
    agency_user_id     VARCHAR(36)  NOT NULL,   -- 기관 내부 PK (UUID)
    agency_subject_id  VARCHAR(300) NOT NULL,   -- SHA-256(CI) = identifierHash
    qim_user_id        VARCHAR(36),             -- Q-IM 매핑 컬럼 (NULL = 미연결)
    agency_code        VARCHAR(50)  NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at      TIMESTAMP,
    
    PRIMARY KEY (agency_user_id),
    UNIQUE KEY uk_agency_subject (agency_subject_id, agency_code),
    INDEX idx_qim_user_id (qim_user_id)
);
```

### 4.2 자체 SSO 기관의 기존 회원 테이블에 컬럼 추가

기존에 자체 회원 테이블이 있는 경우 아래 컬럼을 추가한다.

```sql
-- 기존 기관 회원 테이블 마이그레이션
ALTER TABLE {기관_회원_테이블}
    ADD COLUMN ci_hash     VARCHAR(300) COMMENT 'SHA-256(CI) = OnePass identifierHash',
    ADD COLUMN qim_user_id VARCHAR(36)  COMMENT 'OnePass Q-IM 사용자 UUID';

-- 조회 성능을 위한 인덱스 (lookup API 타임아웃 방지)
CREATE INDEX idx_ci_hash     ON {기관_회원_테이블} (ci_hash);
CREATE INDEX idx_qim_user_id ON {기관_회원_테이블} (qim_user_id);
```

### 4.3 컬럼 상세 설명

| 컬럼 | 타입 | Nullable | 설명 |
|------|------|----------|------|
| `ci_hash` | VARCHAR(300) | Y | SHA-256(CI) = OnePass `identifierHash`. 전환 희망자가 본인인증 시 최초 1회 저장 |
| `qim_user_id` | VARCHAR(36) | Y | OnePass Q-IM UUID. `link` API 호출 시 저장. NULL이면 OnePass 미연결 |

> **CI 소급 수집 불필요**: 기존 모든 회원의 CI를 일괄 수집할 필요가 없다. 원패스 전환을 원하는 사용자가 **본인인증(NICE/PASS)을 수행하는 시점에 1회** `ci_hash`를 저장하면 된다.

---

## 5. SDK 설치 및 기본 설정

### 5.1 의존성 추가

**Gradle (build.gradle)**:
```groovy
dependencies {
    implementation 'kr.go.smes:onepass-agency-sdk:1.0.0'
    // 런타임 의존성 ZERO — 추가 라이브러리 불필요
}
```

**Maven (pom.xml)**:
```xml
<dependency>
    <groupId>kr.go.smes</groupId>
    <artifactId>onepass-agency-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 5.2 클라이언트 생성

```java
// 기본 설정 (HttpURLConnection, JDK 내장)
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://ido.onepass.go.kr")   // OnePass IdO 서버 URL
    .apiKey("your-agency-api-key")           // 발급받은 API 키
    .agencyCode("AGENCY_001")                // 기관 코드
    .connectTimeoutMs(5_000)                 // 기본값: 5초
    .readTimeoutMs(30_000)                   // 기본값: 30초
    .build();
```

**HMAC 서명 활성화** (Sprint 17 Phase 4 이후 필수):
```java
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://ido.onepass.go.kr")
    .apiKey("your-agency-api-key")
    .agencyCode("AGENCY_001")
    .hmacSecret("your-hmac-shared-secret")  // 별도 발급받은 HMAC 키
    .signRequests(true)                      // X-Internal-Sig 헤더 자동 추가
    .build();
```

**Spring Bean 등록 예시**:
```java
@Configuration
public class OnePassConfig {
    
    @Value("${onepass.ido.base-url}")
    private String baseUrl;
    
    @Value("${onepass.agency.api-key}")
    private String apiKey;
    
    @Value("${onepass.agency.code}")
    private String agencyCode;
    
    @Bean
    public AgencyGatewayClient agencyGatewayClient() {
        return AgencyGatewayClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .agencyCode(agencyCode)
            .build();
    }
}
```

---

## 6. Handoff Ticket 검증 구현

> **Note**: 이 기능은 SDK `v1.1.0` 출시 예정. 현재는 직접 HTTP 호출로 구현.

### 6.1 흐름

```
사용자 브라우저
  │ ①로그인 완료
  ▼
OnePass IdO
  │ ②기관 콜백 URL 리다이렉트
  │ GET https://{기관}/callback?ticket=abc-123-def
  ▼
기관 서버 (아래 코드 구현)
  │ ③ POST /api/v1/handoff/verify  (SDK or 직접 호출)
  │ Body: {"ticketId": "abc-123-def", "agencyCode": "AGENCY_001"}
  ▼
IdO 서버
  │ ④ HandoffPayload 반환
  │ {"subject": "...", "identifierHash": "...", "expiresAt": "..."}
  ▼
기관 서버
  │ ⑤ 기관 세션 생성
  ▼
사용자 브라우저 (로그인 완료)
```

### 6.2 SDK v1.1.0 예정 코드

```java
// SDK v1.1.0 출시 후 사용 가능
HandoffVerifyClient handoffClient = HandoffVerifyClient.builder()
    .baseUrl("https://ido.onepass.go.kr")
    .apiKey("your-agency-api-key")
    .agencyCode("AGENCY_001")
    .build();

// 기관 콜백 컨트롤러
@GetMapping("/callback")
public String callback(@RequestParam String ticket, HttpSession session) {
    HandoffPayload payload = handoffClient.verify(ticket);
    
    // 사용자 정보로 기관 세션 생성
    session.setAttribute("userId", payload.getSubject());
    session.setAttribute("identifierHash", payload.getIdentifierHash());
    
    return "redirect:/main";
}
```

### 6.3 현재 직접 구현 (SDK v1.1.0 이전)

```java
@GetMapping("/callback")
public String callback(@RequestParam String ticket, HttpSession session) 
        throws Exception {
    
    // 직접 HTTP 호출
    String url = "https://ido.onepass.go.kr/api/v1/handoff/verify";
    String body = String.format(
        "{\"ticketId\":\"%s\",\"agencyCode\":\"%s\"}", 
        ticket, "AGENCY_001");
    
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("X-Agency-Key", apiKey);
    conn.setDoOutput(true);
    conn.setConnectTimeout(5_000);
    conn.setReadTimeout(10_000);
    
    try (OutputStream os = conn.getOutputStream()) {
        os.write(body.getBytes(StandardCharsets.UTF_8));
    }
    
    if (conn.getResponseCode() == 200) {
        String responseBody = readBody(conn.getInputStream());
        // JSON 파싱 (Jackson 등 사용)
        HandoffPayload payload = objectMapper.readValue(responseBody, HandoffPayload.class);
        session.setAttribute("userId", payload.getSubject());
        session.setAttribute("identifierHash", payload.getIdentifierHash());
    } else if (conn.getResponseCode() == 410) {
        // 티켓 만료 또는 이미 사용됨
        return "redirect:/login?error=ticket_expired";
    }
    
    return "redirect:/main";
}
```

### 6.4 HandoffPayload 응답 필드

```json
{
  "subject": "qim-user-uuid-...",        // OnePass Q-IM 사용자 ID
  "identifierHash": "a3f2c8d1...",       // SHA-256(CI), 회원 전환 키
  "agencyCode": "AGENCY_001",
  "issuedAt": "2026-05-17T10:00:00Z",
  "expiresAt": "2026-05-17T10:05:00Z",  // 발급 후 5분
  "nonce": "random-nonce-value"          // CSRF 방지용
}
```

> **⚠️ 보안**: HandoffTicket은 1회 사용 후 즉시 무효화된다. 재사용 시도 시 서버가 410 Gone을 반환한다.

---

## 7. 회원 전환 흐름 구현

### 7.1 5단계 전환 세션 (Q-IM 내부)

Q-IM 서버에서 관리하는 전환 상태 머신. 기관은 `lookup`/`link` API를 구현함으로써 이 흐름에 참여한다.

```
INITIATED
   │ Q-IM이 기관 lookup API 호출 → identifierHash로 회원 조회
   ▼
MEMBERS_FETCHED
   │ 사용자가 연결할 계정 선택
   ▼
ACCOUNT_SELECTED
   │ 전환 승인 처리
   ▼
LINKING
   │ Q-IM이 기관 link API 호출 → qim_user_id 저장
   ▼
COMPLETED
```

### 7.2 기관 관점의 전환 트리거

사용자가 전환을 원할 때 기관이 호출하는 인바운드 이벤트.

```java
// 회원 전환 이벤트 전송
InboundEvent conversionEvent = InboundEvent.builder()
    .eventType("MEMBER_CONVERSION_REQUEST")
    .agencyCode("AGENCY_001")
    .idempotencyKey(IdempotencyKeyGenerator.generateWithPrefix("AGENCY_001"))
    .payloadJson(buildConversionPayload(identifierHash, agencyUserId))
    .build();

GatewayResponse response = client.sendInbound(conversionEvent);

if (response.isSuccess()) {
    // 202 Accepted — 비동기 전환 시작됨
    log.info("회원 전환 요청 전송 완료: {}", response.getCorrelationId());
} else if (response.isIdempotencyConflict()) {
    // 409 Conflict — 이미 처리 중인 요청 (멱등성 키 중복)
    log.warn("이미 진행 중인 전환 요청: {}", response.getCorrelationId());
}

private String buildConversionPayload(String identifierHash, String agencyUserId) {
    return "{\"identifierHash\":\"" + identifierHash 
        + "\",\"agencyUserId\":\"" + agencyUserId + "\"}";
}
```

### 7.3 CI 수집 시점 처리

```java
// 사용자가 본인인증(NICE/PASS) 완료 시 CI 저장
@PostMapping("/identity-verify/callback")
public ResponseEntity<Void> identityVerifyCallback(
        @RequestBody NiceVerifyResult result, Principal principal) {
    
    String ci = result.getCi();  // NICE/PASS가 반환하는 CI값
    
    // SHA-256(CI) = identifierHash
    String ciHash = DigestUtils.sha256Hex(ci);
    
    // 기관 DB에 ci_hash 저장 (qim_user_id는 아직 null)
    agencyUserRepository.updateCiHash(principal.getName(), ciHash);
    
    return ResponseEntity.ok().build();
}

// Repository
@Modifying
@Query("UPDATE AgencyUser u SET u.ciHash = :ciHash WHERE u.userId = :userId")
void updateCiHash(@Param("userId") String userId, @Param("ciHash") String ciHash);
```

---

## 8. Agent 설치 및 토큰 추출 설정

### 8.1 Agent JVM 인수 추가

```bash
# Tomcat (setenv.sh)
JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/onepass/onepass-agent.jar=config=/etc/onepass/onepass-agent.properties"

# JBoss/WildFly (standalone.conf)
JAVA_OPTS="$JAVA_OPTS \
  -javaagent:/opt/onepass/onepass-agent.jar=config=/etc/onepass/onepass-agent.properties"
```

### 8.2 기본 설정 파일 (Authorization 헤더 기반)

```properties
# /etc/onepass/onepass-agent.properties

# 필수
onepass.agent.endpoint=https://ido.onepass.go.kr
onepass.agent.api-key=your-agency-api-key

# 선택 (기본값)
onepass.agent.enabled=true
onepass.agent.connect-timeout-ms=5000
onepass.agent.read-timeout-ms=10000
onepass.agent.max-retry=2
onepass.agent.log-level=INFO
```

### 8.3 자체 SSO 기관 — 쿠키 기반 토큰 추출 (SDK v1.1.0 예정)

자체 SSO 기관은 OnePass 토큰을 쿠키에 저장하는 경우가 많다.

```properties
# 쿠키에서 토큰 추출 (헤더 없으면 쿠키 fallback)
onepass.agent.token-source=header,cookie
onepass.agent.token-cookie-name=ONEPASS_TOKEN

# 자체 SSO 엔드포인트 바이패스
onepass.agent.bypass-uris=/actuator/**,/health,/sso/**,/saml/**,/login/**
```

> **현재(v1.0.0) 제약**: `token-source` 설정이 없어 Authorization 헤더만 지원. 자체 SSO 기관은 쿠키 대신 `Authorization: Bearer {token}` 헤더를 추가하거나, SSO 필터에서 해당 헤더를 삽입하는 우회 방법을 사용해야 한다.

### 8.4 현재(v1.0.0) 자체 SSO 기관 우회 방법

SSO 필터에서 OnePass 토큰을 Authorization 헤더로 변환하여 삽입:

```java
// 자체 SSO 기관의 필터 예시 (현재 v1.0.0 우회 방법)
public class SsoToOnePassBridgeFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest request = (HttpServletRequest) req;
        
        // SSO 세션에서 OnePass 토큰 추출
        HttpSession session = request.getSession(false);
        if (session != null) {
            String onePassToken = (String) session.getAttribute("ONEPASS_TOKEN");
            if (onePassToken != null) {
                // Authorization 헤더로 래핑 (Agent가 읽을 수 있도록)
                HttpServletRequestWrapper wrapper = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if ("Authorization".equalsIgnoreCase(name)) {
                            return "Bearer " + onePassToken;
                        }
                        return super.getHeader(name);
                    }
                };
                chain.doFilter(wrapper, res);
                return;
            }
        }
        
        chain.doFilter(req, res);
    }
}
```

---

## 9. identifierHash 처리 상세

### 9.1 SHA-256(CI) 계산

```java
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public static String computeIdentifierHash(String ci) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(ci.getBytes(StandardCharsets.UTF_8));
        
        // HEX 인코딩
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString(); // 64자리 소문자 16진수
    } catch (Exception e) {
        throw new RuntimeException("SHA-256 계산 실패", e);
    }
}
```

### 9.2 identifierHash 저장 타이밍

```
CI 수집 타이밍:
  ✅ 사용자가 원패스 전환 버튼 클릭 → 본인인증 팝업 → NICE/PASS CI 반환
  ✅ 신규 가입 시 본인인증 → CI 즉시 저장
  ✅ 기존 서비스 본인인증 기능에 ci_hash 저장 로직 추가
  
  ❌ 관리자가 기존 회원 CI 일괄 수집 (행정적 사전 동의 없이 불가)
  ❌ 타 시스템에서 CI 이관 (법적 검토 필요)
```

### 9.3 조회 쿼리 최적화

```sql
-- 인덱스가 없는 경우 lookup API 타임아웃 위험
-- 반드시 생성 후 운영

-- EXPLAIN으로 인덱스 사용 여부 확인
EXPLAIN SELECT * FROM agency_user WHERE agency_subject_id = 'a3f2c8d1...';

-- 파티셔닝이 없는 경우 1천만 건 이상 테이블에서 타임아웃 발생 가능
-- 인덱스 힌트 고려
SELECT /*+ INDEX(agency_user idx_agency_subject_id) */
    agency_user_id, status, last_login_at
FROM agency_user
WHERE agency_subject_id = ? AND status = 'ACTIVE';
```

---

## 10. 에러 처리 및 부분 실패 허용 패턴

### 10.1 Fail-Open 정책

Agent와 SDK 모두 **Fail-Open** 정책을 기본으로 한다. 즉, 토큰 검증에 실패하거나 오류가 발생해도 서비스 가용성을 우선하여 요청을 통과시킨다.

```java
// Agent 내부 동작 (GenericFilterAdvice.java)
@Advice.OnMethodEnter(suppress = Throwable.class) // ← 모든 예외 suppress
public static void onEnter(...) {
    try {
        String token = extractBearerToken(request);
        if (token == null || token.isEmpty()) return; // ← 토큰 없으면 통과
        // ...
    } catch (Throwable t) {
        // 예외 발생 시 로그만 남기고 통과
        if (log != null) log.println("[WARN] 토큰 검증 중 예외: " + t.getMessage());
    }
}
```

### 10.2 SDK 예외 계층

```java
// AgencySdkException — SDK 설정/파라미터 오류
try {
    GatewayResponse resp = client.sendInbound(event);
} catch (AgencyHttpException e) {
    // HTTP 4xx/5xx 응답 — 재시도 가능 여부 판단
    int status = e.getHttpStatus();
    if (status == 409) {
        // 멱등성 충돌 — 재시도 불필요 (이미 처리됨)
    } else if (status >= 500) {
        // 서버 오류 — 지수 백오프 후 재시도
    }
} catch (AgencySdkException e) {
    // SDK 내부 오류 — 설정 점검 필요
    log.error("SDK 오류: {}", e.getErrorCode(), e);
}
```

### 10.3 lookup API 타임아웃 처리

Q-IM은 15초 데드라인으로 모든 기관에 병렬 조회를 실행한다. 기관의 `lookup` API가 15초 이내에 응답하지 않으면, 해당 기관의 회원은 전환 실패로 처리되지만 다른 기관은 영향받지 않는다.

```java
// 기관 측 lookup API 타임아웃 처리 권장
@PostMapping("/lookup")
public ResponseEntity<MemberLookupResponse> lookup(@RequestBody MemberLookupRequest req) {
    try {
        // DB 조회에 명시적 타임아웃 설정 (10초 이내 권장)
        Optional<AgencyUser> user = agencyUserRepository
                .findByAgencySubjectIdWithTimeout(req.getIdentifierHash(), 10_000);
        // ...
    } catch (QueryTimeoutException e) {
        // 타임아웃 발생 → 빈 응답 반환 (서비스 오류가 아닌 빈 조회로 처리)
        log.warn("lookup 타임아웃: identifierHash={}", req.getIdentifierHash());
        return ResponseEntity.ok(MemberLookupResponse.notFound());
    }
}
```

---

## 11. 테스트 환경 및 통합 검증

### 11.1 로컬 테스트 환경 (Docker Compose)

```bash
# 전체 스택 시작
docker-compose up -d ido q-im agency-stub

# agency-stub는 SSO 시뮬레이션 포함
# Profile: bridge → MockSsoSessionController 활성화
```

### 11.2 agency-stub Mock API 테스트

```bash
# lookup API 테스트
curl -X POST http://localhost:8084/api/v1/members/lookup \
  -H "Content-Type: application/json" \
  -H "X-Agency-Key: stub-api-key-dev" \
  -d '{"identifierHash": "test-hash-001", "agencyCode": "AGENCY_STUB_001"}'

# Expected: {"found": true, "agencyUserId": "...", "status": "ACTIVE"}

# link API 테스트  
curl -X POST http://localhost:8084/api/v1/members/link \
  -H "Content-Type: application/json" \
  -H "X-Agency-Key: stub-api-key-dev" \
  -d '{"identifierHash": "test-hash-001", "qimUserId": "uuid-...", "agencyCode": "AGENCY_STUB_001"}'
```

### 11.3 SSO 세션 시뮬레이션 (bridge 프로필)

```bash
# SSO 세션 사전 등록
curl -X POST http://localhost:8084/mock/sso/pre-register \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "sso-session-001", "identifierHash": "test-hash-001"}'

# SSO 세션 활성화 (PRE_REGISTERED → ACTIVATED)
curl -X POST http://localhost:8084/mock/sso/activate \
  -d '{"sessionId": "sso-session-001"}'
```

---

## 12. API 레퍼런스 요약

### 12.1 SDK AgencyGatewayClient 메서드

| 메서드 | HTTP 메서드 | 경로 | 응답 |
|--------|-----------|------|------|
| `sendInbound(event)` | POST | `/api/v1/agency/gateway/inbound/event` | 202 Accepted |
| `triggerOutbound(request)` | PATCH | `/api/v1/agency/gateway/outbound/notify` | 200 OK |
| `getStatus(agencyCode)` | GET | `/api/v1/agency/gateway/status/{code}` | 200 OK |
| `verifyHandoff(ticketId)` *(v1.1.0)* | POST | `/api/v1/handoff/verify` | HandoffPayload |

### 12.2 기관 구현 API 스펙

| API | 메서드 | 경로 | 타임아웃 | 멱등성 |
|-----|--------|------|---------|--------|
| 회원 조회 | POST | `/api/v1/members/lookup` | 15초 이내 | Y |
| 회원 매핑 | POST | `/api/v1/members/link` | 30초 이내 | Y |

### 12.3 에러 코드

| 코드 | HTTP 상태 | 의미 | 조치 |
|------|----------|------|------|
| `SDK_NULL_PARAM` | - | null 파라미터 전달 | 파라미터 검증 추가 |
| `SDK_CONFIG_ERROR` | - | 필수 설정 누락 | baseUrl, apiKey 확인 |
| `SDK_MISSING_AGENCY` | - | agencyCode 미설정 | builder에 agencyCode() 추가 |
| `HANDOFF_TICKET_EXPIRED` | 410 | 티켓 만료/재사용 | 재로그인 유도 |
| `AGENCY_NOT_FOUND` | 404 | 기관 코드 미등록 | 관리자에 기관 등록 요청 |
| `HMAC_SIGNATURE_INVALID` | 401 | HMAC 서명 불일치 | hmacSecret 확인 |

---

*이 문서는 `agency-stub`, `onepass-agency-sdk`, `onepass-agent`, `ido`, `q-im` 소스코드 직접 분석을 기반으로 작성되었습니다.*

*문의: 플랫폼 연동팀 (내부 이슈 트래커: ONEPASS-DEV 프로젝트)*
