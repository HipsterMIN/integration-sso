# SMEP 통합플랫폼 인증 심층 분석 및 integration-sso 마이그레이션 가이드

**문서 번호**: MIG-2026-001  
**작성일**: 2026-05-11  
**분석 대상**: `smep-be-develop.zip` — SMEP 통합플랫폼 백엔드 소스 (Spring Boot 3.x / Java 21 / MyBatis)  
**마이그레이션 목표**: integration-sso (OnePass 플랫폼 v2.3.0) 정식 연동  
**분석 파일 수**: 650개 (Java 560개), 인증 관련 집중 분석 26개 파일

---

## 목차

1. [분석 요약 (Executive Summary)](#1-분석-요약)
2. [SMEP 전체 코드 구조](#2-smep-전체-코드-구조)
3. [인증 아키텍처 심층 분석 — 시연용 vs 정식 설계](#3-인증-아키텍처-심층-분석)
4. [인증 계층별 상세 분석](#4-인증-계층별-상세-분석)
5. [Q-IM 연동 계층 상세 분석](#5-q-im-연동-계층-상세-분석)
6. [데이터베이스 스키마 분석](#6-데이터베이스-스키마-분석)
7. [아키텍처 충돌 및 갭 분석](#7-아키텍처-충돌-및-갭-분석)
8. [마이그레이션 경로 설계](#8-마이그레이션-경로-설계)
9. [팀별 작업 항목 및 API 변경 명세](#9-팀별-작업-항목-및-api-변경-명세)
10. [마이그레이션 위험 및 완화 전략](#10-마이그레이션-위험-및-완화-전략)

---

## 1. 분석 요약

### 1.1 핵심 발견사항

SMEP 통합플랫폼의 현재 인증 구조는 **시연용으로 의도적으로 제약된 상태**임을 코드에서 명확히 확인했다. 크게 세 가지 층위의 문제가 존재한다.

| 층위 | 문제 유형 | 심각도 | 운영 적합성 |
|------|-----------|--------|-------------|
| 로그인 API | 더미 토큰 하드코딩 | CRITICAL | ❌ 불가 |
| SecurityConfig | `/api/v1/**` 전체 `permitAll()` | CRITICAL | ❌ 불가 |
| SSO 콜백 | `@Profile("local")` 전용 컨트롤러 | HIGH | ❌ 불가 |
| id_token 서명 검증 | jwtSecret 미설정 시 Base64만 디코딩 | HIGH | ⚠️ 조건부 |
| State 검증 | Redis 기반이나 `local-` prefix 바이패스 | MEDIUM | ⚠️ 조건부 |

### 1.2 통합 전환의 핵심 충돌

```
현재 구조 (SMEP 시연용):
  SMEP ──────직접────► Q-IM /api/ciw-im/member/*

integration-sso 목표 구조 (ADR-001):
  SMEP ──► IdO ──► Agency Adapter ──► SMEP Q-IM Inbound

핵심 갭:
  IdO → SMEP 어댑터 코드 = 0건 (grep 결과 없음)
  SMEP → OnePass Handoff Ticket 수신 처리 = 미구현
```

### 1.3 마이그레이션 전환 판단

| 전환 항목 | 현재 상태 | 작업량 | 우선순위 |
|-----------|-----------|--------|---------|
| IdO → SMEP 어댑터 구현 | ❌ 미구현 | 중 (2~3주) | P0 |
| SMEP SecurityConfig 보안 강화 | ⚠️ 시연용 | 소 (3일) | P0 |
| Keycloak → OnePass(Q-Sign) 전환 | ⚠️ 부분 연동 | 중 (1~2주) | P0 |
| JWT secret 운영 설정 | ❌ 환경변수 미주입 | 소 (1일) | P0 |
| encCi AES-256-GCM 복호화 구현 | ⚠️ bypass 상태 | 중 (1주) | P1 |
| RefreshToken 처리 (Q-IM 연동 후) | ⚠️ dummy | 소 (2일) | P1 |

---

## 2. SMEP 전체 코드 구조

### 2.1 패키지 구조

```
io.github.hipstermin.idem/
├── account/                    # 인증·계정 도메인 (핵심)
│   ├── api/                    # 컨트롤러 계층
│   │   ├── AuthController.java          # ID/PW 로그인 (더미 토큰 반환)
│   │   ├── KeycloakController.java      # OnePass/Keycloak 콜백 처리
│   │   ├── MockAuthController.java      # @Profile("local") 전용 mock
│   │   ├── SsoAuthController.java       # @Profile("local") 전용 SSO 콜백
│   │   └── OidcController.java          # /api/me
│   ├── service/                # 서비스 계층
│   │   ├── AccountAuthService.java      # SSO 핵심 흐름
│   │   ├── AccountContextService.java   # 토큰 발급 컨텍스트
│   │   ├── KeycloakTokenService.java    # code→token 교환
│   │   ├── KeycloakLocalLoginService.java  # 로컬 회원 브리지
│   │   ├── KeycloakCallbackLocalLoginService.java
│   │   ├── KeycloakLogoutService.java   # 로그아웃 URL 조립
│   │   ├── KeycloakAccessTokenClaimExtractor.java # UUID claim 추출
│   │   └── SsoStateStore.java           # Redis 기반 state 관리
│   ├── jwt/                    # JWT 처리 계층
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── AccountJwtProvider.java      # SMEP 자체 JWT 생성/검증
│   │   └── AccountTokenContext.java     # 개인/기업 컨텍스트
│   ├── client/                 # SSO 클라이언트 계층
│   │   ├── SsoClient.java               # 인터페이스
│   │   ├── StandardSsoClient.java       # @Profile("!local")
│   │   └── MockSsoClient.java           # @Profile("local")
│   ├── config/
│   │   ├── SecurityConfig.java          # Spring Security 설정
│   │   └── SsoProperties.java           # SSO 설정 바인딩
│   └── service/impl/
│       └── AuthServiceImpl.java         # 더미 토큰 반환 (시연용)
├── qim/                        # Q-IM 연동 도메인
│   ├── api/
│   │   └── QimMemberController.java     # /api/ciw-im/member/*
│   └── service/
│       ├── QimInboundAuthenticationService.java # X-API-Key 검증
│       ├── QimIdentityDecoder.java       # encCi 복호화 (bypass 상태)
│       ├── QimMemberQueryService.java    # query 처리
│       ├── QimMemberRegisterService.java # register 처리
│       └── QimMemberWithdrawService.java # withdraw 처리
└── [기타 업무 도메인]           # 공모사업, 게시판, 정책금융 등
```

### 2.2 기술 스택

| 항목 | 사용 기술 |
|------|-----------|
| 프레임워크 | Spring Boot 3.x / Java 21 |
| ORM | MyBatis XML Mapper (JPA 미사용) |
| DB | PostgreSQL (`sc_mbrm` 스키마) |
| 인증 캐시 | Redis (state 관리, RefreshToken 저장) |
| SSO 프로토콜 | OIDC Authorization Code Flow (Keycloak) |
| JWT | JJWT (io.jsonwebtoken), HS256 |
| 빌드 | Gradle |
| 설정 분리 | Profile: local / dev / prd |

---

## 3. 인증 아키텍처 심층 분석

### 3.1 시연용 인증의 전체 구조

SMEP는 현재 **이중 인증 경로**를 가지고 있다.

```
┌─────────────────────────────────────────────────────────────┐
│                    SMEP 인증 경로 (현재)                      │
│                                                              │
│  경로 1 (시연용): 로컬 ID/PW 로그인                          │
│  FE ──POST /api/v1/auth/login──► AuthServiceImpl.login()    │
│                                  └─► "dummy-access-token"   │
│                                      (하드코딩된 더미)       │
│                                                              │
│  경로 2 (부분 구현): SSO Authorization Code Flow            │
│  FE ──GET /api/v1/auth/login-url──► AccountAuthService     │
│                                     └─► Keycloak 인가 URL  │
│  Keycloak ──callback──► @Profile("local") SsoAuthController│
│                          └─► postMessage to popup          │
│                                                              │
│  경로 2b (추가 구현): OnePass/Keycloak 직접 연동             │
│  FE ──code──► POST /api/v1/auth/keycloak/callback          │
│               └─► KeycloakTokenService.exchangeCodeForToken │
│               └─► KeycloakLocalLoginService.loginFromAccessToken│
│               └─► SMEP 자체 JWT 발급                       │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 시연용 증거 #1: 더미 토큰 반환 (AuthServiceImpl)

```java
// AuthServiceImpl.java (147줄)
@Override
public TokenResponse login(LoginRequest loginRequest) {
    // TODO: Keycloak과 연동하여 실제 인증을 처리하고 JWT를 발급받는 로직 구현
    // 지금은 임시로 더미 토큰을 반환합니다.
    return new TokenResponse("dummy-access-token", "dummy-refresh-token");
}
```

**영향**: `POST /api/v1/auth/login` 엔드포인트로 어떤 ID/PW를 보내도 항상 더미 토큰이 반환됨. 운영 불가.

### 3.3 시연용 증거 #2: 전체 API 오픈 (SecurityConfig)

```java
// SecurityConfig.java
.requestMatchers("/api/v1/**").permitAll()  // 전체 URL 오픈 (개발완료 및 운영반영시 수정)
.requestMatchers("/api/ciw-im/**").permitAll()  // Q-IM 인바운드 — API Key 검증으로 대체
```

**영향**: 더미 토큰 `"dummy-access-token"` 을 Authorization 헤더에 보내도 `JwtAuthenticationFilter`에서 `validateToken()` 실패 시 Security Context가 비어 있지만, 모든 `/api/v1/**` 경로는 그대로 통과됨. 인증 우회 완전 가능.

### 3.4 시연용 증거 #3: @Profile("local") SSO 컨트롤러

```java
// SsoAuthController.java
@Profile("local")  // 로컬 환경 전용!
@RestController
@RequestMapping("/api/v1/sso")
public class SsoAuthController {
    @GetMapping("/callback")  // GET /api/v1/sso/callback
    ...
}

// MockAuthController.java
@Profile("local")  // 로컬 환경 전용!
@RestController
@RequestMapping("/api/v1/auth")
public class MockAuthController {
    @GetMapping("/login-url")  // GET /api/v1/auth/login-url
    @GetMapping("/callback")   // GET /api/v1/auth/callback
    ...
}
```

**영향**: dev/prd 프로필에서는 `MockAuthController`와 `SsoAuthController`가 Bean으로 등록되지 않음. 대신 `KeycloakController`가 OnePass 직접 연동 경로를 담당하나, 프론트엔드가 어느 경로를 사용하는지 추가 확인 필요.

### 3.5 정식 설계 의도 (KeycloakController 분석)

`KeycloakController`는 `@Profile` 제한이 없어 **모든 환경에서 활성화**된다. OnePass(Q-Sign) Authorization Code Flow를 다음과 같이 처리한다:

```
POST /api/v1/auth/keycloak/callback         # code → Keycloak token 교환 (세션 저장)
POST /api/v1/auth/keycloak/callback/local-login  # code → 교환 + 로컬 로그인 원스텝
POST /api/v1/auth/keycloak/local-login      # 세션의 access_token으로 로컬 회원 식별
POST /api/v1/auth/keycloak/logout           # Keycloak logout URL 반환
```

**두 가지 케이스 처리 모델**:
- **케이스1**: FE가 `callback` → `local-login` 두 단계로 처리 (세션 브리지)
- **케이스2**: FE가 `callback/local-login` 원스텝으로 처리

### 3.6 State 검증 분석 (SsoStateStore)

```java
// SsoStateStore.java
private final StringRedisTemplate redisTemplate;
private static final String KEY_PREFIX = "smes:sso:state:";
private static final Duration EXPIRATION = Duration.ofMinutes(5);  // 5분 TTL

public boolean consume(String state) {
    // 로컬 개발 편의를 위해 "local-"로 시작하는 state는 허용
    if (state.startsWith("local-")) {
        return true;  // ⚠️ 로컬 바이패스 — 운영에서 제거 필요
    }
    // Redis에서 state 소비 (삭제하여 재사용 방지)
    String key = KEY_PREFIX + state;
    String value = redisTemplate.opsForValue().get(key);
    if (value == null) return false;
    redisTemplate.delete(key);  // 소비 후 삭제 (CSRF 방어)
    return true;
}
```

**평가**: Redis 기반 state 검증은 CSRF 방어로 적절하나, `local-` prefix 바이패스는 **운영 배포 전 반드시 제거**해야 한다.

---

## 4. 인증 계층별 상세 분석

### 4.1 JWT 계층 (AccountJwtProvider)

SMEP가 발급하는 **자체 JWT 토큰** 구조를 완전히 분석했다.

#### 4.1.1 SMEP JWT Claim 구조

```java
// AccountJwtProvider.createToken() 발췌
Jwts.builder()
    .setIssuer(issuer)           // account.jwt.issuer (기본: http://localhost:8080)
    .setSubject(String.valueOf(user.getId()))  // AccountUser.id (BIGSERIAL PK)
    .claim("name", user.getName())
    .claim("email", user.getEmail())
    .claim("phone", user.getPhone())
    .claim("ci", user.getCi())           // 연계정보 (중요)
    .claim("di", user.getDi())           // 중복가입확인정보
    .claim("member_type", user.getMemberType())  // IND / ENT
    .claim("roles", user.getRoles())     // ["USER"] 등
    .claim("company_id", ...)
    .claim("company_name", ...)
    .claim("business_reg_no", ...)
    .claim("industry", ...)
    .claim("size", ...)
    .claim("region", ...)
    .claim("type", context.type())       // INDIVIDUAL / CORPORATE
    .signWith(getSigningKey(), SignatureAlgorithm.HS256)
```

#### 4.1.2 세 가지 토큰 타입

| 토큰 타입 | 생성 메서드 | Subject | 용도 |
|-----------|-------------|---------|------|
| 개인 토큰 | `createToken(AccountUser)` | AccountUser.id | SSO 로그인 후 SMEP 세션 |
| 기업 토큰 | `createCompanyToken(Company, context)` | Company.id | 기업 컨텍스트 전환 |
| 실회원 토큰 | `createRealMemberToken(MemberLoginIdentity, context)` | member.memberNo | sc_mbrm 실회원 브리지 |

#### 4.1.3 토큰 만료 설정

```yaml
account:
  jwt:
    issuer: http://localhost:8080
    validity-seconds: 3600          # Access Token: 1시간
    refresh-validity-seconds: 1209600  # Refresh Token: 14일
```

#### 4.1.4 JWT 필터 동작 (JwtAuthenticationFilter)

```
요청 → Authorization: Bearer {token} 파싱
    → jwtProvider.validateToken(token)
    → Claims 추출: subject(userId), roles
    → UsernamePasswordAuthenticationToken 생성
    → SecurityContextHolder 설정
    → filterChain.doFilter (계속 진행)
```

**중요**: validateToken 실패 시에도 filterChain이 계속 진행됨 → `permitAll()` 경로는 인증 없이 접근 가능.

### 4.2 AccountTokenContext — 개인/기업 이중 컨텍스트

```java
// AccountTokenContext.java
public record AccountTokenContext(
    String type,              // INDIVIDUAL / CORPORATE
    Long contextCompanyId,    // 기업 컨텍스트 ID
    String contextRole,       // 기업 내 역할
    List<String> roles        // 권한 목록
) {
    public static AccountTokenContext individual() { ... }
    public static AccountTokenContext individualWithContext(Long companyId, ...) { ... }
    public static AccountTokenContext corporate(Long companyId, ...) { ... }
}
```

**영향**: integration-sso의 Handoff Ticket에도 `memberType`과 `companyContext`를 전달해야 SMEP의 기존 토큰 처리 로직과 호환된다.

### 4.3 Keycloak 서비스 계층 — OnePass 직접 연동 흐름

#### 4.3.1 KeycloakTokenService: code → token 교환

```java
// KeycloakTokenService.java
@Value("${keycloak.server-url}")  private String serverUrl;
@Value("${keycloak.realm}")       private String realm;
@Value("${keycloak.client-id}")   private String clientId;
@Value("${keycloak.client-secret}") private String clientSecret;
@Value("${keycloak.redirect-uri}") private String redirectUri;

// token endpoint: {serverUrl}/realms/{realm}/protocol/openid-connect/token
// grant_type: authorization_code
```

**현재 설정 (dev)**:
```yaml
keycloak:
  server-url: https://isso-dev.smes.go.kr/qsign
  realm: ucube-qsign
  client-id: smes-tipa-01
  client-secret: QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF
  redirect-uri: https://www.smes.go.kr/home-dev/sso
  logout-callback-uri: https://www.smes.go.kr/home-dev/sso-logout
```

#### 4.3.2 KeycloakAccessTokenClaimExtractor: UUID 클레임 추출

```java
// Keycloak access token에서 UUID claim 추출 (서명 검증 없이 Base64 디코딩)
public String extractUuid(String accessToken) {
    Map<String, Object> claims = parseClaims(accessToken);
    Object uuidValue = claims.get("UUID");  // Keycloak custom claim
    ...
}
```

**구조**: OnePass/Keycloak이 access token에 `UUID` claim을 포함시킴 → 이 UUID로 `sc_mbrm.tb_mbrm_mbr_m.uuid` 컬럼을 조회하여 로컬 회원 식별.

#### 4.3.3 KeycloakLocalLoginService: 로컬 회원 브리지

```java
// UUID → sc_mbrm 실회원 조회 → SMEP 실회원 토큰 발급
String uuid = keycloakAccessTokenClaimExtractor.extractUuid(keycloakAccessToken);
MemberLoginIdentity individualMember = memberLoginMapper.findIndividualByUuid(uuid);
MemberLoginIdentity corporateMember = memberLoginMapper.findCorporateByUuid(uuid);
// 둘 다 존재하면 DUPLICATE_RESOURCE 오류
MemberLoginIdentity member = corporateMember != null ? corporateMember : individualMember;
TokenResponse tokenResponse = accountContextService.issueRealMemberLogin(member);
```

**이것이 핵심**: OnePass 로그인 후 SMEP의 기존 `sc_mbrm` 회원 DB를 UUID로 조회하여 **로컬 JWT를 발급**하는 브리지 패턴이다. integration-sso 마이그레이션 시 이 흐름을 **Handoff Ticket 기반**으로 대체해야 한다.

---

## 5. Q-IM 연동 계층 상세 분석

### 5.1 현재 Q-IM 인바운드 구조

SMEP는 Q-IM으로부터 `/api/ciw-im/member/*` 를 **직접 수신**하고 있다.

```
Q-IM ──► POST /api/ciw-im/member/query
Q-IM ──► POST /api/ciw-im/member/register
Q-IM ──► POST /api/ciw-im/member/withdraw
```

### 5.2 인증: X-API-Key 단순 비교

```java
// QimInboundAuthenticationService.java
@Value("${idem.registry.inbound.api-key:}") private final String apiKey;

public boolean matches(String apiKeyHeader) {
    return apiKey != null && apiKey.equals(trimToNull(apiKeyHeader));
}
```

```yaml
# application-dev.yml
qim:
  inbound:
    api-key: ${QIM_INBOUND_API_KEY:imk-XRw22gijwk3uEQtAV-9wC93RHncDFBaRhryRqsQcZMA}
    aes-shared-key: ${QIM_INBOUND_AES_SHARED_KEY:KBXiNF4G2cCWah8z+NGUoMEk11bSk+Kgx8Cc+8tFp2Y=}
```

**상태**: API Key 기반 인증은 구현되어 있으나 `isConfigured()` 검증이 추가되어 있어, 환경변수 미설정 시 `500 QIM_AUTH_NOT_CONFIGURED` 반환.

### 5.3 encCi 복호화 현황 — 중요 미완성 항목

```java
// QimIdentityDecoder.java
public Optional<String> decodePersonalCi(String encCi) {
    ...
    // 제약: Q-IM 개발 배포 전까지는 필드명이 encCi여도 복호화된 CI가 그대로 들어온다는 회신을 받았다.
    // 영향: 실제 AES-256-GCM 암호문으로 전환되면 이 메서드 내부에서만 decrypt 구현으로 교체해야 한다.
    log.debug("QIM encCi plaintext bypass active: aesKeyConfigured={}", hasValidAes256Key());
    return Optional.of(ci);  // bypass: 복호화 없이 그대로 반환
}
```

**영향**: Q-IM이 실제 AES-256-GCM 암호화 encCi를 전송하기 시작하면 SMEP Q-IM inbound 처리가 즉시 실패한다. integration-sso의 `QimSpReceiverService`는 이미 AES 복호화를 구현했으므로, **IdO 경유 시 복호화를 IdO에서 담당**하는 것이 바람직하다.

### 5.4 Q-IM 서비스 처리 수준 — 완성도 평가

| 서비스 | 구현 상태 | 특기사항 |
|--------|-----------|----------|
| `QimMemberQueryService.query()` | ✅ 완성 | IND/ENT 분기, CI/UUID/ID fallback 처리 |
| `QimMemberRegisterService.register()` | ✅ 완성 | IND/ENT 신규/흡수 분기, 트랜잭션 완전 |
| `QimMemberWithdrawService.withdraw()` | ✅ 완성 | UUID 검증, 멱등 처리, 기업담당자 체크 |
| `QimIdentityDecoder.decodePersonalCi()` | ⚠️ bypass | AES-256-GCM 복호화 미구현 |

### 5.5 sc_mbrm DB 구조 요약

Q-IM 연동이 접근하는 핵심 테이블:

```sql
-- 회원 마스터
sc_mbrm.tb_mbrm_mbr_m          -- mbr_no (YYYYMMDD+8자리), mbr_type_cd(IND/ENT), mbr_stts_cd(A111=활성/A113=탈퇴), uuid
-- 개인회원 상세
sc_mbrm.tb_mbrm_indv_mbr_d    -- mbr_no, indv_eml_addr, indv_mbl_telno, brdt, use_yn
-- 기업회원 상세
sc_mbrm.tb_mbrm_ent_mbr_d     -- mbr_no, brno(사업자번호), eml_addr, rprs_telno, use_yn
-- 인증수단
sc_mbrm.tb_mbrm_mbr_cert_d    -- mbr_no, cert_mns_cd(A201=ID/PW, A203=본인인증), lgn_id, lgn_pswd, mbr_ci_cn
-- 인증이력
sc_mbrm.tb_mbrm_mbr_cert_h    -- (cert_d의 이력 테이블)
```

**중요**: `tb_mbrm_mbr_cert_d.mbr_ci_cn` 컬럼이 Q-IM CI 조회의 핵심 키다. encCi 복호화 후 이 컬럼과 비교한다.

---

## 6. 데이터베이스 스키마 분석

### 6.1 인증 관련 이중 스키마 구조

SMEP는 **두 개의 DB 계층**을 병행 사용한다.

```
1. account_user 테이블 (SSO 로그인용 — schema.sql로 정의)
   └─ SSO sub 기준 upsert, CI/DI 저장, 로컬 로그인 브리지

2. sc_mbrm 스키마 (실회원 DB — 기존 레거시 운영 DB)
   └─ tb_mbrm_mbr_m, tb_mbrm_indv_mbr_d, tb_mbrm_ent_mbr_d
   └─ tb_mbrm_mbr_cert_d (인증수단 — UUID 기준 조회)
```

### 6.2 account_user 테이블 (SSO 연동 전용)

```sql
CREATE TABLE account_user (
    id            BIGSERIAL PRIMARY KEY,
    sso_sub       VARCHAR(100) NOT NULL UNIQUE,  -- Keycloak/OnePass subject
    login_id      VARCHAR(100),
    password      VARCHAR(255),
    name          VARCHAR(100),
    email         VARCHAR(200),
    phone         VARCHAR(50),
    ci            VARCHAR(100),     -- 연계정보
    di            VARCHAR(100),     -- 중복가입확인정보
    member_type   VARCHAR(50),      -- IND / ENT
    roles         VARCHAR(500),
    company_id    VARCHAR(100),
    company_name  VARCHAR(200),
    company_reg_no VARCHAR(50),
    company_industry VARCHAR(100),
    company_size  VARCHAR(100),
    company_region VARCHAR(100),
    attributes_json TEXT            -- 기타 SSO 클레임 저장
);
```

**마이그레이션 주의**: `account_user.ci`와 `sc_mbrm.tb_mbrm_mbr_cert_d.mbr_ci_cn`이 동기화 되어야 한다. integration-sso 연동 후 CI 출처가 OnePass JWT → SMEP로 전달되는 흐름이 되어야 한다.

### 6.3 MemberLoginMapper 분석 — HASH_B64 함수 의존성

```sql
-- 비밀번호 검증 (PostgreSQL custom function 의존)
WHERE c.lgn_pswd = HASH_B64(71, #{password})
-- 컬럼 복호화 (DB function 의존)
CASE WHEN m.mbr_nm ~ '[+/=]' THEN fn_comm_dec_b64(m.mbr_nm) ELSE m.mbr_nm END
-- Q-IM mapper에서도 동일 패턴
WHEN m.mbr_nm ~ '[+/=]' THEN DEC_B64('KEY1', m.mbr_nm)
```

**영향**: SMEP는 PostgreSQL 전용 커스텀 함수(`HASH_B64`, `fn_comm_dec_b64`, `DEC_B64`)를 대량 사용한다. integration-sso 연동이 이 DB를 직접 접근할 수는 없으므로, **반드시 SMEP의 API 계층을 통해서만** 데이터에 접근해야 한다.

---

## 7. 아키텍처 충돌 및 갭 분석

### 7.1 핵심 아키텍처 충돌

```
┌──────────────────────────────────────────────────────────────┐
│               아키텍처 충돌 상세 비교                         │
├────────────────────┬─────────────────┬───────────────────────┤
│ 항목               │ 현재 SMEP       │ integration-sso 목표  │
├────────────────────┼─────────────────┼───────────────────────┤
│ Q-IM 연동 방향     │ Q-IM→SMEP 직접  │ Q-IM→IdO→SMEP        │
│ SSO 제공자         │ Keycloak(현재)  │ Q-Sign(OnePass)      │
│ 회원 식별 기준     │ Keycloak UUID   │ Handoff Ticket       │
│ encCi 복호화       │ SMEP 자체(bypass)│ IdO 경유            │
│ API Key 발행       │ 개별 관리       │ Q-IM이 IdO를 통해   │
│ 로그아웃           │ Keycloak logout │ Q-Sign logout        │
└────────────────────┴─────────────────┴───────────────────────┘
```

### 7.2 갭 목록 (코드 근거 포함)

#### GAP-001: IdO → SMEP 어댑터 미구현 (CRITICAL)

```bash
# grep 결과: 0건
grep -r "smep" /home/user/webapp/idem-hub/src/ 2>/dev/null | wc -l  # = 0
grep -r "smep-adapter" /home/user/webapp/ 2>/dev/null | wc -l   # = 0
```

integration-sso의 IdO 모듈에는 SMEP를 대상으로 하는 Agency Adapter가 **전혀 구현되어 있지 않다**. ADR-001에 따르면 Q-IM의 MEMBER_QUERY/REGISTER/WITHDRAW는 IdO를 거쳐야 하며, IdO가 SMEP의 `/api/ciw-im/member/*` 를 **대리 호출**해야 한다.

#### GAP-002: Handoff Ticket 수신 처리 미구현 (CRITICAL)

```
SMEP 측 미구현 항목:
  - POST /api/v1/sso/initiate (Handoff Ticket 수신 → SMEP 토큰 발급)
  - Handoff Ticket AES-256-GCM 복호화 로직
  - HMAC-SHA256 서명 검증 로직
```

integration-sso의 SLO(`POST /api/v1/slo/initiate`)에 해당하는 **SMEP 측 Handoff Ticket 수신 엔드포인트**가 없다. SMEP는 현재 Keycloak UUID 기반으로 로컬 회원을 식별하는데, OnePass 마이그레이션 후에는 Handoff Ticket의 클레임(CI, memberType 등)을 사용해야 한다.

#### GAP-003: encCi AES-256-GCM 복호화 미구현 (HIGH)

```java
// QimIdentityDecoder.java 현재 코드
return Optional.of(ci);  // AES-256-GCM 없이 평문 그대로 반환
```

Q-IM이 실제 암호화 encCi를 전송하기 시작하면 SMEP Q-IM 처리가 즉시 실패한다. IdO 경유 구조에서는 **IdO가 encCi 복호화를 담당**하고 SMEP에는 평문 CI를 전달해야 한다.

#### GAP-004: SecurityConfig permitAll 범위 (CRITICAL for production)

```java
.requestMatchers("/api/v1/**").permitAll()  // 운영 반영 시 수정 필요 (주석 명시)
```

운영 전환 시 각 엔드포인트별로 `hasRole(...)` 또는 `authenticated()` 로 교체해야 한다.

#### GAP-005: State 바이패스 코드 (MEDIUM)

```java
if (state.startsWith("local-")) {
    return true;  // 운영에서 제거 필요
}
```

### 7.3 충돌하지 않는 부분 (재사용 가능)

| 항목 | 상태 | 설명 |
|------|------|------|
| Q-IM inbound 서비스 로직 | ✅ 재사용 | query/register/withdraw 처리 로직 완성 |
| AccountJwtProvider | ✅ 재사용 | SMEP 자체 JWT 생성 로직 정상 |
| MemberLoginMapper SQL | ✅ 재사용 | UUID 기반 회원 조회 SQL 동작 |
| Redis State 관리 기반 | ✅ 재사용 | SsoStateStore 구조는 올바름 |
| KeycloakLogoutService | ✅ 재사용 | Q-Sign logout URL 조립 로직 동일 |
| QimMemberController 라우팅 | ✅ 재사용 | 컨트롤러 엔드포인트 구조 그대로 |

---

## 8. 마이그레이션 경로 설계

### 8.1 전환 전략 개요

**2단계 전환 전략**을 권고한다.

```
Phase 1 (SMEP 측 준비 — 2~3주):
  SMEP가 Handoff Ticket을 수신할 수 있는 신규 엔드포인트 구현
  SecurityConfig 보안 강화
  State 바이패스 코드 제거
  encCi 복호화 구현 (또는 IdO 위임 계약)

Phase 2 (integration-sso 측 어댑터 구현 — 2~3주):
  IdO → SMEP Agency Adapter 구현
  Q-IM → IdO → SMEP 라우팅 전환
  운영 환경 통합 테스트
```

### 8.2 Phase 1: SMEP 측 신규 엔드포인트 설계

#### 8.2.1 Handoff Ticket 수신 엔드포인트 (신규)

```
POST /api/v1/sso/handoff
Content-Type: application/json
Authorization: Bearer {handoffTicket}

요청 바디:
{
  "handoffTicket": "v1.{iv}.{ciphertext}",
  "redirectUrl": "https://smep.smes.go.kr/dashboard"
}

처리 흐름:
  1. Handoff Ticket AES-256-GCM 복호화
  2. HMAC-SHA256 서명 검증
  3. 클레임에서 ci, memberType, roles 추출
  4. sc_mbrm에서 CI 기준 회원 조회
  5. SMEP 자체 JWT 발급
  6. {redirectUrl}로 redirect (토큰 포함)

응답:
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "expiresIn": 3600
}
```

#### 8.2.2 Handoff Ticket 복호화 구현 가이드

integration-sso의 Handoff Ticket 포맷: `v{n}.{iv}.{ciphertext}` (AES-256-GCM + HMAC-SHA256)

```java
// SMEP 측 구현 예시 (HandoffTicketDecoder.java)
public HandoffTicketClaims decodeTicket(String ticket) {
    // 1. 파싱: "v1.{base64_iv}.{base64_ciphertext}"
    String[] parts = ticket.split("\\.");
    int version = Integer.parseInt(parts[0].substring(1));
    byte[] iv = Base64.getDecoder().decode(parts[1]);
    byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

    // 2. AES-256-GCM 복호화 (키는 integration-sso와 사전 공유)
    SecretKey key = getKeyByVersion(version);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
    byte[] plaintext = cipher.doFinal(ciphertext);

    // 3. JSON 파싱 → HandoffTicketClaims
    return objectMapper.readValue(plaintext, HandoffTicketClaims.class);
}
```

**공유가 필요한 값**: Handoff Ticket 암호화 키 (integration-sso의 `handoff.ticket.secret`)

### 8.3 Phase 2: IdO → SMEP Agency Adapter 구현

#### 8.3.1 SMEP Agency Adapter 신규 구현 (integration-sso ido 모듈)

```
위치: /idem-hub/src/main/java/…/agency/smep/SmepAgencyAdapter.java

구현 인터페이스:
  AgencyAdapter.query(QimMemberQueryRequest) → QimApiResult
  AgencyAdapter.register(QimMemberRegisterRequest) → QimApiResult
  AgencyAdapter.withdraw(QimMemberWithdrawRequest) → QimApiResult

내부 동작:
  RestTemplate or WebClient → SMEP /api/ciw-im/member/*
  X-API-Key: {smep.qim.inbound.api-key}  (SMEP에서 발급)
  encCi: IdO가 복호화 후 평문 CI 전달 (SMEP의 bypass 해소)
```

#### 8.3.2 Q-IM → IdO → SMEP 라우팅 전환 계획

```
현재:  Q-IM ─────────────────────────────────────────► SMEP
          POST /api/ciw-im/member/{query|register|withdraw}
          X-API-Key: {smep_api_key}

전환 후:
  Q-IM ──► IdO /api/v1/qim/member/{query|register|withdraw}
           X-API-Key: {ido_qim_api_key}
           IdO 내부에서 encCi 복호화
    ──► SMEP /api/ciw-im/member/*
           X-API-Key: {smep_api_key}
           encCi: 평문 CI (복호화 완료)
```

### 8.4 전환 후 전체 인증 흐름

```
[정식 전환 후 OnePass SSO 로그인 흐름]

사용자 브라우저
  │
  ├── (1) GET /api/v1/auth/keycloak/authorize-url
  │         → SMEP가 Q-Sign 인가 URL 조립 (client_id=smep-tipa, state=Redis 저장)
  │
  ├── (2) [팝업/redirect] Q-Sign 로그인 페이지
  │
  ├── (3) Q-Sign → SMEP /api/v1/auth/keycloak/callback
  │         → KeycloakTokenService.exchangeCodeForToken(code)
  │         → 세션에 access/refresh/id token 저장
  │
  ├── (4) POST /api/v1/auth/keycloak/local-login  (또는 callback/local-login)
  │         → access token에서 UUID 클레임 추출
  │         → sc_mbrm.uuid로 회원 조회
  │         → SMEP 실회원 JWT 발급
  │
  └── (5) 이후 API 호출: Authorization: Bearer {smep_jwt}
            → JwtAuthenticationFilter 검증
            → SecurityContext 설정
```

---

## 9. 팀별 작업 항목 및 API 변경 명세

### 9.1 SMEP 개발팀 작업 항목

#### 9.1.1 P0 — 보안 필수 (운영 전환 전 반드시 완료)

| # | 작업 항목 | 파일 | 예상 공수 |
|---|-----------|------|-----------|
| S-P0-1 | SecurityConfig `/api/v1/**` permitAll 범위 축소 | `SecurityConfig.java` | 0.5일 |
| S-P0-2 | SsoStateStore `local-` prefix 바이패스 제거 | `SsoStateStore.java` | 0.5일 |
| S-P0-3 | JWT Secret 운영 환경변수 주입 (`JWT_SECRET_KEY`) | 인프라 담당 | 0.5일 |
| S-P0-4 | AuthServiceImpl 더미 토큰 제거 | `AuthServiceImpl.java` | 1일 |
| S-P0-5 | StandardSsoClient jwtSecret 필수화 (서명 검증 강화) | `StandardSsoClient.java` | 1일 |

**S-P0-1 상세 — SecurityConfig 수정 지침**:

```java
// 현재 (시연용):
.requestMatchers("/api/v1/**").permitAll()

// 변경 후 (운영용):
.requestMatchers("/api/v1/auth/**").permitAll()            // 로그인/SSO callback은 허용
.requestMatchers("/api/v1/public/**").permitAll()           // 공개 API
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")       // 관리자 전용
.anyRequest().authenticated()                               // 나머지는 인증 필수
```

#### 9.1.2 P1 — 기능 완성 (통합 연동 전 완료)

| # | 작업 항목 | 파일 | 예상 공수 |
|---|-----------|------|-----------|
| S-P1-1 | Handoff Ticket 수신 엔드포인트 구현 | 신규 `HandoffController.java` | 3일 |
| S-P1-2 | HandoffTicketDecoder AES-256-GCM 복호화 구현 | 신규 `HandoffTicketDecoder.java` | 2일 |
| S-P1-3 | encCi AES-256-GCM 복호화 구현 (IdO 위임 아니면 직접) | `QimIdentityDecoder.java` | 2일 |
| S-P1-4 | Q-Sign 운영 keycloak 설정 확인 및 적용 | `application-prd.yml` | 1일 |

**S-P1-1 신규 API 명세**:

```
POST /api/v1/sso/handoff
Authorization: Bearer {handoffTicket}

Request Body:
{
  "handoffTicket": "v1.{base64_iv}.{base64_ciphertext}"
}

Response 200:
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 3600,
  "tokenType": "Bearer"
}

Response 401:
{
  "error": "INVALID_HANDOFF_TICKET",
  "message": "Handoff ticket signature verification failed"
}
```

#### 9.1.3 P2 — 정리 (안정화 단계)

| # | 작업 항목 | 파일 | 예상 공수 |
|---|-----------|------|-----------|
| S-P2-1 | `DEMO TEMP` 주석 SQL 검토 및 제거 | `MemberLoginMapper.xml` | 1일 |
| S-P2-2 | `@Profile("local")` 컨트롤러 정리 (local 환경 전용 명확화) | `MockAuthController.java`, `SsoAuthController.java` | 0.5일 |
| S-P2-3 | RefreshToken rotation 구현 (현재 더미 refresh token) | `AuthController.java` | 2일 |

### 9.2 integration-sso (IdO) 개발팀 작업 항목

#### 9.2.1 P0 — 핵심 어댑터 구현 (마이그레이션 전 필수)

| # | 작업 항목 | 위치 | 예상 공수 |
|---|-----------|------|-----------|
| I-P0-1 | SMEP Agency Adapter 신규 구현 | `idem-hub/…/agency/smep/` | 5일 |
| I-P0-2 | Q-IM → IdO → SMEP 라우팅 설정 | `idem-hub/…/config/AgencyRoutingConfig.java` | 2일 |
| I-P0-3 | SMEP용 X-API-Key 설정 관리 | `application.yml` / ConfigMap | 1일 |
| I-P0-4 | Handoff Ticket 발급 시 SMEP 클레임 포함 확인 | `QimSpReceiverService.java` | 1일 |

**I-P0-1 SMEP Agency Adapter 필수 구현 인터페이스**:

```java
// SmepAgencyAdapter.java (신규 구현)
@Component
public class SmepAgencyAdapter implements AgencyAdapter {
    
    @Value("${smep.qim.base-url}")
    private String smepBaseUrl;
    
    @Value("${smep.qim.api-key}")
    private String smepApiKey;

    @Override
    public QimApiResult<QimMemberQueryResponseData> query(QimMemberQueryRequest request) {
        // POST {smepBaseUrl}/api/ciw-im/member/query
        // Header: X-API-Key: {smepApiKey}
        // Body: request (encCi는 IdO가 이미 복호화한 평문 CI)
    }

    @Override
    public QimApiResult<QimMemberRegisterResponseData> register(QimMemberRegisterRequest request) {
        // POST {smepBaseUrl}/api/ciw-im/member/register
    }

    @Override
    public QimApiResult<QimMemberWithdrawResponseData> withdraw(QimMemberWithdrawRequest request) {
        // POST {smepBaseUrl}/api/ciw-im/member/withdraw
    }
}
```

#### 9.2.2 P1 — 검증 및 보안

| # | 작업 항목 | 위치 | 예상 공수 |
|---|-----------|------|-----------|
| I-P1-1 | SMEP Handoff Ticket 검증 공유 키 배포 | 인프라 | 1일 |
| I-P1-2 | SMEP Adapter 통합 테스트 작성 | `idem-hub/…/agency/smep/SmepAdapterTest.java` | 2일 |
| I-P1-3 | encCi 평문 변환 후 SMEP 전달 검증 | `QimSpReceiverService` 수정 | 1일 |

### 9.3 인프라/운영팀 작업 항목

| # | 작업 항목 | 예상 공수 |
|---|-----------|-----------|
| O-1 | `JWT_SECRET_KEY` 운영 Secret 생성 및 K8s Secret 등록 | 0.5일 |
| O-2 | `QIM_INBOUND_API_KEY` SMEP-IdO 간 공유 키 설정 | 0.5일 |
| O-3 | Handoff Ticket 암호화 키 (`HANDOFF_TICKET_SECRET`) K8s Secret 등록 | 0.5일 |
| O-4 | SMEP의 keycloak.server-url → Q-Sign 운영 URL 변경 | 0.5일 |
| O-5 | Q-IM inbound 엔드포인트를 IdO로 재지정 (방화벽/로드밸런서 설정) | 1일 |
| O-6 | Redis 연결 및 `REDIS_PW` 설정 확인 (state 관리 필수) | 0.5일 |

---

## 10. 마이그레이션 위험 및 완화 전략

### 10.1 위험 매트릭스

| 위험 | 심각도 | 발생 가능성 | 완화 전략 |
|------|--------|-------------|-----------|
| IdO 어댑터 개발 지연 | HIGH | 중 | SMEP Q-IM inbound 병렬 유지 기간 설정 (이중 경로) |
| sc_mbrm UUID 매핑 누락 | HIGH | 중 | Q-IM register 이후 UUID 동기화 배치 스크립트 준비 |
| Handoff Ticket 복호화 키 불일치 | CRITICAL | 낮음 | 사전 키 교환 프로토콜, 암호화 테스트 벡터 공유 |
| encCi 형식 변경 타이밍 | HIGH | 높음 | Q-IM팀과 encCi 형식 전환 날짜 사전 합의 |
| SecurityConfig 변경 후 API 접근 장애 | HIGH | 중 | 스테이징 환경에서 전체 API 경로 회귀 테스트 |
| JWT Secret 변경 시 기존 토큰 무효화 | MEDIUM | 높음 | 토큰 롤링 기간(1시간) 적용, FE 재로그인 안내 |
| Redis 연결 장애 시 SSO 로그인 불가 | HIGH | 낮음 | Redis Sentinel/Cluster 구성, 장애 시 대체 흐름 |

### 10.2 롤백 계획

마이그레이션 각 단계별 롤백 포인트:

```
Phase 1 완료 (SMEP 보안 강화):
  롤백: SecurityConfig 이전 버전 배포 (permitAll 복원)
  영향: 시연용 상태로 복귀 — 운영 데이터 영향 없음

Phase 2 완료 (IdO 어댑터 + Q-IM 라우팅 전환):
  롤백: Q-IM 엔드포인트를 SMEP로 직접 복원 (DNS/LB 설정 변경)
  영향: IdO 경유 없이 직접 연결 복귀 — 30분 이내 가능
```

### 10.3 검증 체크리스트

```
[ ] SMEP POST /api/v1/sso/handoff → 올바른 Handoff Ticket으로 JWT 발급 확인
[ ] SMEP GET /api/v1/me → JWT 검증 후 사용자 정보 반환 확인
[ ] IdO POST /api/v1/qim/member/query → SMEP 조회 결과 정상 relay 확인
[ ] IdO POST /api/v1/qim/member/register → SMEP 신규 등록 성공 확인
[ ] IdO POST /api/v1/qim/member/withdraw → SMEP 탈퇴 처리 성공 확인
[ ] Q-Sign 로그인 → SMEP JWT 발급 → 인증이 필요한 API 호출 성공 확인
[ ] Q-Sign 로그아웃 → SMEP 세션 무효화 확인
[ ] encCi 암호화 시나리오 → IdO 복호화 → SMEP 평문 CI 전달 확인
[ ] SsoStateStore local- prefix 바이패스 제거 후 CSRF 방어 확인
[ ] SecurityConfig 강화 후 모든 API 권한 정상 동작 확인
```

---

## 부록 A. 분석 완료 파일 목록

| 파일 경로 (io.github.hipstermin.idem 이후) | 분석 완료 | 핵심 발견 |
|------------------------------|-----------|-----------|
| `account/config/SecurityConfig.java` | ✅ | /api/v1/** permitAll, JWT 필터 체인 |
| `account/api/AuthController.java` 관련 | ✅ | (AuthServiceImpl에서 더미 토큰 확인) |
| `account/api/KeycloakController.java` | ✅ | 4개 엔드포인트, 두 케이스 처리 모델 |
| `account/api/MockAuthController.java` | ✅ | @Profile("local"), 팝업 콜백 |
| `account/api/SsoAuthController.java` | ✅ | @Profile("local"), 운영에서 미활성 |
| `account/service/AccountAuthService.java` | ✅ | SSO 핵심 흐름 (state→code→token→upsert→JWT) |
| `account/service/impl/AuthServiceImpl.java` | ✅ | 더미 토큰 반환 (시연용 증거) |
| `account/service/KeycloakTokenService.java` | ✅ | code→token 교환, RestTemplate |
| `account/service/KeycloakLocalLoginService.java` | ✅ | UUID→sc_mbrm 브리지 |
| `account/service/KeycloakCallbackLocalLoginService.java` | ✅ | 원스텝 콜백 처리 |
| `account/service/KeycloakLogoutService.java` | ✅ | logout URL 조립 |
| `account/service/KeycloakAccessTokenClaimExtractor.java` | ✅ | UUID claim 추출 (서명 검증 없음) |
| `account/service/SsoStateStore.java` | ✅ | Redis state 관리, local- 바이패스 |
| `account/jwt/JwtAuthenticationFilter.java` | ✅ | SMEP JWT 검증 필터 |
| `account/jwt/AccountJwtProvider.java` | ✅ | JWT 생성/검증, 3가지 토큰 타입 |
| `account/jwt/AccountTokenContext.java` | ✅ | 개인/기업 컨텍스트 레코드 |
| `account/client/StandardSsoClient.java` | ✅ | 서명 검증 선택적, @Profile("!local") |
| `account/client/MockSsoClient.java` | ✅ | @Profile("local") |
| `account/config/SsoProperties.java` | ✅ | SSO 설정 바인딩 |
| `qim/api/QimMemberController.java` | ✅ | /api/ciw-im/member/*, X-API-Key |
| `qim/service/QimInboundAuthenticationService.java` | ✅ | API Key 검증 |
| `qim/service/QimIdentityDecoder.java` | ✅ | encCi bypass (AES 미구현) |
| `qim/service/QimMemberQueryService.java` | ✅ | query 완성 |
| `qim/service/QimMemberRegisterService.java` | ✅ | register 완성 (IND/ENT 이중 경로) |
| `qim/service/QimMemberWithdrawService.java` | ✅ | withdraw 완성 |
| `resources/mappers/account/AccountUserMapper.xml` | ✅ | account_user 테이블 조작 |
| `resources/mappers/account/MemberLoginMapper.xml` | ✅ | sc_mbrm UUID/ID/PW 조회 |
| `resources/mappers/qim/QimMemberMapper.xml` | ✅ | CI/brno 기반 조회, member sequence |
| `resources/db/schema.sql` | ✅ | account_user/company/company_member |
| `resources/application.yml` | ✅ | jwt.secret, keycloak 설정 부재 확인 |
| `resources/application-dev.yml` | ✅ | dev SSO/Keycloak/Q-IM 설정값 |
| `resources/application-local.yml` | ✅ | local H2 DB, JWT fallback secret |

---

## 부록 B. integration-sso Handoff Ticket 클레임 구조 (참조)

integration-sso의 `QimSpReceiverService`에서 발급하는 Handoff Ticket의 페이로드 구조 (AES-256-GCM 복호화 후):

```json
{
  "sub": "{memberNo}",
  "ci": "{plaintext_CI}",
  "di": "{DI_value}",
  "memberType": "IND|ENT",
  "roles": ["USER"],
  "companyId": "{companyId}",
  "businessRegNo": "{bizNo}",
  "iat": 1234567890,
  "exp": 1234567890,
  "jti": "{uuid}",
  "iss": "integration-sso-ido"
}
```

SMEP의 `HandoffTicketDecoder`는 이 페이로드를 파싱하여 `AccountJwtProvider.createRealMemberToken()` 또는 `createToken()`에 필요한 데이터를 구성해야 한다.

---

**문서 작성**: AI Developer Agent (integration-sso 개발 지원)  
**검토 필요**: SMEP 개발팀, integration-sso IdO 개발팀, 인프라팀  
**다음 버전**: MIG-2026-002 (Phase 1 완료 후 Phase 2 설계 상세화)
