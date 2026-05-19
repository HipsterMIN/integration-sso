# 설치형 연동 개발 전체 가이드

> **문서 분류**: IAM / 연동 개발 가이드  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 인프라 엔지니어, PM  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [설치형 연동 개요](#1-설치형-연동-개요)
2. [사전 준비: 기관 등록 절차](#2-사전-준비-기관-등록-절차)
3. [인프라 설정](#3-인프라-설정)
4. [Any-ID 모듈 설치](#4-any-id-모듈-설치)
5. [OIDC RP 설정 (application.yml)](#5-oidc-rp-설정-applicationyml)
6. [Spring Boot 통합 구현](#6-spring-boot-통합-구현)
7. [회원 매핑 설계](#7-회원-매핑-설계)
8. [테스트 및 검증](#8-테스트-및-검증)
9. [운영 전환 체크리스트](#9-운영-전환-체크리스트)
10. [FAQ](#10-faq)

---

## 1. 설치형 연동 개요

### 1.1 설치형이란

**설치형(On-Premise)**은 Any-ID 연동 모듈을 **이용기관 서버에 직접 설치**하는 방식이다.  
중계형(SaaS)과 달리 CI 등 민감 데이터가 기관 서버 내에서만 처리된다.

```
설치형 선택 기준 (Q-Net 기준):
  ✅ 월 수백만 건 이상 트래픽 (건당 과금 모델 비효율)
  ✅ 폐쇄망 또는 내부 보안 정책상 외부 경유 불가
  ✅ 기관 자체 커스터마이징 필요
  ✅ 장기적 TCO(총소유비용) 절감
```

### 1.2 설치형 구성 컴포넌트

```
기관 서버에 설치되는 컴포넌트:
┌─────────────────────────────────────────────────────┐
│  Any-ID Agent (WAR/JAR)                             │
│  ├── OIDC RP 기능 (Authorization Code Flow)         │
│  ├── SSO 세션 관리 (쿠키 발급·검증)                  │
│  ├── CI/DN 파싱 모듈                                 │
│  └── Any-ID 관리 콘솔 API                            │
│                                                     │
│  Any-ID Key Manager                                 │
│  ├── 서명키 관리 (JWK, RSA-2048+)                    │
│  └── 키 로테이션 자동화                               │
│                                                     │
│  Any-ID DB Schema (Flyway)                          │
│  ├── sso_session 테이블                              │
│  ├── oidc_state 테이블 (CSRF 방어)                   │
│  └── audit_log 테이블                                │
└─────────────────────────────────────────────────────┘
```

### 1.3 전체 연동 일정 (참고)

| 단계 | 작업 | 소요 기간 |
|------|------|---------|
| 1단계 | 기관 등록 신청 + 협약 체결 | 2~4주 |
| 2단계 | 모듈 수령 + 인프라 구성 | 1~2주 |
| 3단계 | 개발 연동 (OIDC RP 구현) | 2~4주 |
| 4단계 | 통합 테스트 + Any-ID 검증 | 1~2주 |
| 5단계 | 운영 전환 + 모니터링 | 1주 |
| **합계** | | **약 7~13주** |

---

## 2. 사전 준비: 기관 등록 절차

### 2.1 등록 신청

```
제출처: 행정안전부 디지털정부국 인증정보과
방법: 공문 또는 Any-ID 포털(https://ptl.anyid.go.kr) 기관신청

제출 서류:
  1. 이용신청서 (서식)
  2. 개인정보 처리방침 URL
  3. 서비스 목적 및 수집 CI 사용 계획서
  4. 정보보호 관리체계 인증서 (ISMS 또는 ISMS-P) 사본
  5. 기관장 인감증명서 (공공기관 제외 시)
```

### 2.2 발급 수령 항목

협약 완료 후 아래 정보를 수령한다:

```yaml
# Any-ID 기관 정보 (수령 예시)
client_id:      "anyid_client_XXXXXXXXXX"     # OIDC Client ID
client_secret:  "비밀값_절대_공개_금지"         # OIDC Client Secret
instt:          "5000000082"                  # 기관코드
srvc_no:        "5000000084"                  # 서비스번호

# 허용 scope 목록 (협약에 따라 상이)
scope:
  - openid
  - profile
  - phone
  - ci          # CI 수집 → 협약 필수

# 허용 redirect_uri 화이트리스트
redirect_uris:
  - "https://your-service.go.kr/callback/anyid"
  - "https://your-service.go.kr/callback/anyid-dev"  # (데모 환경)
```

### 2.3 환경별 엔드포인트

| 환경 | Base URL | 비고 |
|------|---------|------|
| **운영** | `https://ptl.anyid.go.kr` | 실서비스 |
| **데모/테스트** | `https://demo1.anyid.go.kr` | 개발·통합테스트 |
| **로컬 Mock** | `http://localhost:8099` | 단위 테스트 (자체 구성) |

---

## 3. 인프라 설정

### 3.1 네트워크 방화벽 오픈

```
아웃바운드 오픈 (이용기관 서버 → Any-ID):
  ptl.anyid.go.kr       : 443/TCP (OIDC Token, UserInfo, JWKS)
  mid.anyid.go.kr       : 443/TCP (모바일 신분증 VRS)
  easysign.anyid.go.kr  : 443/TCP (간편인증)
  crt.anyid.go.kr       : 443/TCP (공동/금융인증서)

인바운드 오픈 (Any-ID → 이용기관):
  필요 없음 (이용기관이 Callback URI 응답 처리)
```

### 3.2 HTTPS 설정

```
필수 요건:
  - TLS 1.2 이상 (TLS 1.0/1.1 금지)
  - 유효한 공인 인증서 (Let's Encrypt 또는 CA 발급)
  - redirect_uri는 반드시 HTTPS (HTTP 허용 안 됨)

Nginx 예시:
server {
    listen 443 ssl;
    server_name your-service.go.kr;

    ssl_certificate     /etc/nginx/ssl/cert.pem;
    ssl_certificate_key /etc/nginx/ssl/key.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    location /callback/anyid {
        proxy_pass http://localhost:8080;
    }
}
```

### 3.3 Redis 설정 (SSO 세션 + OIDC state 저장)

```yaml
# application.yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
```

---

## 4. Any-ID 모듈 설치

### 4.1 Maven 의존성 추가

```xml
<!-- pom.xml -->
<dependency>
    <groupId>kr.go.mois.anyid</groupId>
    <artifactId>anyid-spring-boot-starter</artifactId>
    <version>2.4.1</version>   <!-- 행안부 기술지원센터에서 수령 -->
</dependency>
<dependency>
    <groupId>kr.go.mois.anyid</groupId>
    <artifactId>anyid-key-manager</artifactId>
    <version>2.4.1</version>
</dependency>
```

### 4.2 DB 스키마 초기화 (Flyway)

```sql
-- V1__anyid_schema.sql
-- Any-ID SSO 세션 테이블
CREATE TABLE anyid_sso_session (
    id              VARCHAR(64) PRIMARY KEY,   -- 세션 ID (UUID)
    ci_hash         VARCHAR(64) NOT NULL,       -- SHA-256(CI + salt)
    auth_method     VARCHAR(50) NOT NULL,
    auth_level      INT NOT NULL,
    id_token        TEXT,                       -- 암호화 저장
    access_token    TEXT,
    refresh_token   TEXT,
    instt_cd        VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX idx_ci_hash (ci_hash),
    INDEX idx_expires (expires_at)
);

-- OIDC state/nonce 임시 저장 (1회 소비)
CREATE TABLE anyid_oidc_state (
    state           VARCHAR(64) PRIMARY KEY,
    nonce           VARCHAR(64) NOT NULL,
    pkce_verifier   VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL             -- 10분 후 자동 만료
);
```

---

## 5. OIDC RP 설정 (application.yml)

```yaml
# application.yml — Any-ID OIDC RP 설정

anyid:
  # 기관 정보 (행안부로부터 수령한 값)
  client-id:      ${ANYID_CLIENT_ID}          # 환경변수
  client-secret:  ${ANYID_CLIENT_SECRET}       # 환경변수 (절대 코드에 하드코딩 금지)
  instt:          "${ANYID_INSTT:5000000082}"  # 기관코드
  srvc-no:        "${ANYID_SRVC_NO:5000000084}" # 서비스번호

  # 엔드포인트 (운영: ptl.anyid.go.kr, 데모: demo1.anyid.go.kr)
  issuer-uri:     "${ANYID_ISSUER:https://ptl.anyid.go.kr}"
  redirect-uri:   "${ANYID_REDIRECT_URI:https://your-service.go.kr/callback/anyid}"

  # OIDC 설정
  scope:          "openid profile phone ci"
  response-type:  "code"
  pkce-enabled:   true                         # PKCE 강제 (권장)

  # CI 해시 설정
  ci:
    hash-salt:    ${CI_HASH_SALT}              # 환경변수 (비밀값)
    hash-algorithm: SHA-256

  # 세션 설정
  session:
    timeout-minutes: 30                        # 일반 세션
    sso-timeout-hours: 8                       # SSO 세션

  # 인증수단 허용 목록 (기관 정책)
  allowed-auth-methods:
    - MOBILE_ID        # 1등급
    - EASY_SIGN        # 2등급
    - JOINT_CERT       # 2등급
    - FINANCIAL_CERT   # 2등급

  # 최소 인증 등급 강제
  min-auth-level: 2    # 2등급 이상만 허용 (3등급 민간ID 차단)
```

---

## 6. Spring Boot 통합 구현

### 6.1 프로젝트 구조

```
src/main/java/kr/go/yourservice/
├── auth/
│   ├── controller/
│   │   ├── AnyIdAuthController.java     ← Authorization 요청 진입점
│   │   └── AnyIdCallbackController.java ← Callback 처리
│   ├── service/
│   │   ├── AnyIdOidcService.java        ← Token 교환 + ID Token 검증
│   │   └── AnyIdSessionService.java     ← SSO 세션 관리
│   ├── client/
│   │   └── AnyIdTokenClient.java        ← HTTP 클라이언트
│   ├── security/
│   │   └── AnyIdJwtVerifier.java        ← JWK 기반 서명 검증
│   └── model/
│       ├── AnyIdClaims.java             ← ID Token 클레임 모델
│       └── AnyIdTokenResponse.java      ← Token 교환 응답
└── member/
    └── service/
        └── MemberService.java           ← CI 기반 회원 조회·생성
```

### 6.2 Authorization 요청 컨트롤러

```java
// AnyIdAuthController.java
@Controller
@RequiredArgsConstructor
@Slf4j
public class AnyIdAuthController {

    private final AnyIdProperties properties;
    private final AnyIdStateStore stateStore;  // Redis 기반

    // 인증 진입점 — 인증수단 파라미터로 분기
    @GetMapping("/auth/anyid/initiate")
    public ResponseEntity<Void> initiateAuth(
        @RequestParam(defaultValue = "MOBILE_ID") String authMethod,
        @RequestParam(required = false) String easySignProvider,
        @RequestParam(defaultValue = "Y") String autoLogin,
        HttpServletRequest request
    ) {
        // 허용된 인증수단만 처리
        if (!properties.getAllowedAuthMethods().contains(authMethod)) {
            return ResponseEntity.badRequest().build();
        }

        // PKCE 생성
        String codeVerifier  = PkceUtil.generateCodeVerifier();
        String codeChallenge = PkceUtil.computeS256Challenge(codeVerifier);

        // State + Nonce 생성 (CSRF + Replay 방어)
        String state = generateSecureRandom(32);
        String nonce = generateSecureRandom(32);

        // Redis에 임시 저장 (10분 TTL)
        stateStore.save(new OidcStateEntry(state, nonce, codeVerifier));

        // Authorization URL 조립
        String authUrl = UriComponentsBuilder
            .fromHttpUrl(properties.getIssuerUri() + "/oidc/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", properties.getClientId())
            .queryParam("redirect_uri", properties.getRedirectUri())
            .queryParam("scope", properties.getScope())
            .queryParam("state", state)
            .queryParam("nonce", nonce)
            .queryParam("auth_method", authMethod)
            .queryParam("instt", properties.getInstt())
            .queryParam("srvcNo", properties.getSrvcNo())
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .queryParamIfPresent("easy_sign_provider",
                Optional.ofNullable(easySignProvider))
            .queryParamIfPresent("auto_login",
                "FINANCIAL_CERT".equals(authMethod)
                    ? Optional.of(autoLogin) : Optional.empty())
            .build().toUriString();

        log.info("Any-ID 인증 시작. method={}, state_prefix={}",
            authMethod, state.substring(0, 8));

        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, authUrl)
            .build();
    }
}
```

### 6.3 Callback 처리 컨트롤러

```java
// AnyIdCallbackController.java
@RestController
@RequiredArgsConstructor
@Slf4j
public class AnyIdCallbackController {

    private final AnyIdStateStore stateStore;
    private final AnyIdOidcService oidcService;
    private final MemberService memberService;
    private final AnyIdSessionService sessionService;
    private final AnyIdProperties properties;

    @GetMapping("/callback/anyid")
    public ResponseEntity<?> handleCallback(
        @RequestParam String code,
        @RequestParam String state,
        @RequestParam(required = false) String error,
        @RequestParam(required = false) String error_description,
        HttpServletResponse response
    ) {
        // 사용자 취소 처리
        if ("access_denied".equals(error)) {
            return ResponseEntity.status(302)
                .header("Location", "/login?error=cancelled")
                .build();
        }

        // 1. state 검증 + 1회 소비 (Redis에서 읽고 삭제)
        OidcStateEntry stateEntry = stateStore.consumeAndDelete(state)
            .orElseThrow(() -> {
                log.warn("state 불일치 또는 만료. state_prefix={}", state.substring(0, 8));
                return new SecurityException("Invalid state — CSRF 가능성");
            });

        try {
            // 2. Authorization Code → Token 교환
            AnyIdTokenResponse tokens = oidcService.exchangeToken(
                code, stateEntry.getPkceVerifier()
            );

            // 3. ID Token 검증 (서명 + nonce + aud + exp)
            AnyIdClaims claims = oidcService.verifyIdToken(
                tokens.getIdToken(),
                stateEntry.getNonce()
            );

            // 4. 최소 인증 등급 검증
            if (claims.getAuthLevel() < properties.getMinAuthLevel()) {
                log.warn("인증 등급 미달. level={}, required={}",
                    claims.getAuthLevel(), properties.getMinAuthLevel());
                return ResponseEntity.status(302)
                    .header("Location", "/login?error=insufficient_auth_level")
                    .build();
            }

            // 5. CI 기반 회원 조회 or 생성
            Member member = memberService.findOrCreateByCi(
                claims.getCi(), claims
            );

            // 6. 세션 발급 (HttpOnly 쿠키)
            String sessionId = sessionService.createSession(member, claims);
            addSessionCookie(response, sessionId);

            log.info("Any-ID 로그인 성공. ciHash_prefix={}, method={}, level={}",
                sha256Hex(claims.getCi()).substring(0, 8),
                claims.getAuthMethod(),
                claims.getAuthLevel()
            );

            return ResponseEntity.status(302)
                .header("Location", "/dashboard")
                .build();

        } catch (SecurityException e) {
            log.error("Any-ID 인증 보안 오류: {}", e.getMessage());
            return ResponseEntity.status(302)
                .header("Location", "/login?error=security_error")
                .build();
        }
    }

    private void addSessionCookie(HttpServletResponse response, String sessionId) {
        ResponseCookie cookie = ResponseCookie.from("SESSION_ID", sessionId)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofMinutes(properties.getSession().getTimeoutMinutes()))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
```

### 6.4 Token 교환 서비스

```java
// AnyIdOidcService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class AnyIdOidcService {

    private final AnyIdProperties properties;
    private final AnyIdTokenClient tokenClient;
    private final AnyIdJwtVerifier jwtVerifier;

    // Authorization Code → ID Token 교환
    public AnyIdTokenResponse exchangeToken(String code, String codeVerifier) {
        return tokenClient.exchange(
            AnyIdTokenRequest.builder()
                .grantType("authorization_code")
                .code(code)
                .redirectUri(properties.getRedirectUri())
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .codeVerifier(codeVerifier)  // PKCE
                .build()
        );
    }

    // ID Token 검증 + 클레임 파싱
    public AnyIdClaims verifyIdToken(String idToken, String expectedNonce) {
        return jwtVerifier.verify(idToken, expectedNonce);
    }
}

// AnyIdTokenClient.java
@Component
@RequiredArgsConstructor
public class AnyIdTokenClient {

    private final WebClient webClient;
    private final AnyIdProperties properties;

    public AnyIdTokenResponse exchange(AnyIdTokenRequest request) {
        String tokenEndpoint = properties.getIssuerUri() + "/oidc/token";

        return webClient.post()
            .uri(tokenEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .headers(h -> h.setBasicAuth(request.getClientId(), request.getClientSecret()))
            .body(BodyInserters.fromFormData(request.toMultiValueMap()))
            .retrieve()
            .onStatus(HttpStatusCode::isError, resp ->
                resp.bodyToMono(String.class)
                    .flatMap(body -> Mono.error(
                        new AnyIdTokenException("Token 교환 실패: " + body)
                    ))
            )
            .bodyToMono(AnyIdTokenResponse.class)
            .timeout(Duration.ofSeconds(10))
            .block();
    }
}
```

### 6.5 JWK 기반 서명 검증

```java
// AnyIdJwtVerifier.java
@Component
@Slf4j
public class AnyIdJwtVerifier {

    private final JWKSet jwkSet; // 1시간 캐시
    private final String issuer;
    private final String clientId;

    @Scheduled(fixedDelay = 3600000) // 1시간마다 JWK 갱신
    public void refreshJwkSet() {
        try {
            this.jwkSet = JWKSet.load(
                new URL(issuer + "/.well-known/jwks.json")
            );
            log.info("Any-ID JWK 갱신 완료. keyCount={}", jwkSet.getKeys().size());
        } catch (Exception e) {
            log.error("JWK 갱신 실패 (이전 키 유지): {}", e.getMessage());
        }
    }

    public AnyIdClaims verify(String idToken, String expectedNonce) {
        try {
            // JWT 파싱
            SignedJWT signedJWT = SignedJWT.parse(idToken);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            // issuer 검증
            if (!issuer.equals(claims.getIssuer())) {
                throw new SecurityException("issuer 불일치");
            }

            // audience 검증
            if (!claims.getAudience().contains(clientId)) {
                throw new SecurityException("audience 불일치");
            }

            // 만료 검증
            if (new Date().after(claims.getExpirationTime())) {
                throw new SecurityException("토큰 만료");
            }

            // nonce 검증
            if (!expectedNonce.equals(claims.getStringClaim("nonce"))) {
                throw new SecurityException("nonce 불일치");
            }

            // JWK 서명 검증
            JWSVerifier verifier = resolveVerifier(signedJWT.getHeader().getKeyID());
            if (!signedJWT.verify(verifier)) {
                throw new SecurityException("JWK 서명 검증 실패");
            }

            // CI 유효성 검증
            String ci = claims.getStringClaim("ci");
            if (ci == null || ci.length() != 88) {
                throw new SecurityException("CI 클레임 유효하지 않음");
            }

            return AnyIdClaims.fromJwtClaims(claims);

        } catch (ParseException | JOSEException e) {
            throw new SecurityException("JWT 파싱/검증 실패: " + e.getMessage(), e);
        }
    }
}
```

---

## 7. 회원 매핑 설계

### 7.1 DB 스키마

```sql
-- 회원 테이블 (CI 기반 식별)
CREATE TABLE member (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ci_hash     VARCHAR(64) UNIQUE NOT NULL,  -- SHA-256(CI + salt)
    name        VARCHAR(50),
    birthdate   VARCHAR(8),
    gender      CHAR(1),
    phone       VARCHAR(20),
    email       VARCHAR(100),
    auth_level  INT NOT NULL DEFAULT 2,       -- 마지막 인증 등급
    auth_method VARCHAR(50),                  -- 마지막 인증수단
    status      VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, SUSPENDED, WITHDRAWN
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login  TIMESTAMP
);

-- 인증 이력 테이블 (감사용)
CREATE TABLE member_auth_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id   BIGINT NOT NULL REFERENCES member(id),
    ci_hash     VARCHAR(64) NOT NULL,
    auth_method VARCHAR(50) NOT NULL,
    auth_level  INT NOT NULL,
    provider    VARCHAR(50),               -- KAKAO, NAVER 등
    session_id  VARCHAR(64),
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(500),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 인덱스
CREATE INDEX idx_member_ci_hash     ON member(ci_hash);
CREATE INDEX idx_auth_log_member_id ON member_auth_log(member_id);
CREATE INDEX idx_auth_log_created   ON member_auth_log(created_at);
```

### 7.2 회원 매핑 서비스 전체 코드

```java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class MemberService {

    private final MemberRepository memberRepository;
    private final AuthLogRepository authLogRepository;
    private final CiHashEncoder ciHashEncoder;

    public Member findOrCreateByCi(String ci, AnyIdClaims claims) {
        String ciHash = ciHashEncoder.encode(ci);

        return memberRepository.findByCiHash(ciHash)
            .map(member -> updateExistingMember(member, claims))
            .orElseGet(() -> createNewMember(ciHash, claims));
    }

    private Member updateExistingMember(Member member, AnyIdClaims claims) {
        // 이름, 전화번호 등 최신 정보로 갱신
        member.setName(claims.getName());
        if (StringUtils.hasText(claims.getPhoneNumber())) {
            member.setPhone(claims.getPhoneNumber());
        }
        member.setAuthLevel(claims.getAuthLevel());
        member.setAuthMethod(claims.getAuthMethod());
        member.setLastLogin(LocalDateTime.now());
        return memberRepository.save(member);
    }

    private Member createNewMember(String ciHash, AnyIdClaims claims) {
        Member member = Member.builder()
            .ciHash(ciHash)
            .name(claims.getName())
            .birthdate(claims.getBirthdate())
            .gender(claims.getGender())
            .phone(claims.getPhoneNumber())
            .authLevel(claims.getAuthLevel())
            .authMethod(claims.getAuthMethod())
            .lastLogin(LocalDateTime.now())
            .build();

        Member saved = memberRepository.save(member);
        log.info("신규 회원 생성. id={}, ciHash_prefix={}", 
            saved.getId(), ciHash.substring(0, 8));
        return saved;
    }

    // 인증 이력 기록
    public void recordAuthLog(Member member, AnyIdClaims claims,
                               String sessionId, String ip) {
        AuthLog log = AuthLog.builder()
            .memberId(member.getId())
            .ciHash(member.getCiHash())
            .authMethod(claims.getAuthMethod())
            .authLevel(claims.getAuthLevel())
            .provider(claims.getEasySignProvider())
            .sessionId(sessionId)
            .ipAddress(ip)
            .build();
        authLogRepository.save(log);
    }
}
```

---

## 8. 테스트 및 검증

### 8.1 단위 테스트 (Mock Any-ID)

```java
// AnyIdCallbackControllerTest.java
@SpringBootTest
@AutoConfigureMockMvc
class AnyIdCallbackControllerTest {

    @MockBean
    private AnyIdOidcService oidcService;
    @MockBean
    private AnyIdStateStore stateStore;
    @MockBean
    private MemberService memberService;

    @Test
    @DisplayName("정상 콜백 처리 — 세션 쿠키 발급")
    void testSuccessfulCallback() throws Exception {
        // Given
        String state = "test-state-1234";
        String code  = "test-auth-code";
        OidcStateEntry stateEntry = new OidcStateEntry(state, "test-nonce", "verifier");

        given(stateStore.consumeAndDelete(state))
            .willReturn(Optional.of(stateEntry));
        given(oidcService.exchangeToken(code, "verifier"))
            .willReturn(mockTokenResponse());
        given(oidcService.verifyIdToken(any(), eq("test-nonce")))
            .willReturn(mockClaims());
        given(memberService.findOrCreateByCi(any(), any()))
            .willReturn(mockMember());

        // When & Then
        mockMvc.perform(get("/callback/anyid")
                .param("code", code)
                .param("state", state))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/dashboard"))
            .andExpect(cookie().exists("SESSION_ID"))
            .andExpect(cookie().httpOnly("SESSION_ID", true))
            .andExpect(cookie().secure("SESSION_ID", true));
    }

    @Test
    @DisplayName("state 불일치 — 보안 오류 처리")
    void testStateMismatch() throws Exception {
        given(stateStore.consumeAndDelete("wrong-state"))
            .willReturn(Optional.empty());

        mockMvc.perform(get("/callback/anyid")
                .param("code", "some-code")
                .param("state", "wrong-state"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "/login?error=security_error"));
    }
}
```

### 8.2 통합 테스트 체크리스트

```
□ Any-ID 데모 환경(demo1.anyid.go.kr) 연동 확인
□ 인증수단 5종 각각 테스트 로그인
□ CI 동일성 검증 (같은 사람, 다른 수단 → 동일 CI 확인)
□ 세션 발급·검증·만료 정상 동작
□ PKCE code_challenge 검증 통과
□ state/nonce 재사용 불가 확인 (Redis 1회 소비)
□ 만료된 ID Token 거부 확인
□ 잘못된 audience 거부 확인
□ 인증 등급 미달 거부 확인 (min-auth-level)
□ Any-ID 기술지원 담당자 검수 요청
```

---

## 9. 운영 전환 체크리스트

```
인프라
  □ 운영 client_id/client_secret 환경변수 주입 확인
  □ 운영 redirect_uri ptl.anyid.go.kr 화이트리스트 등록
  □ TLS 인증서 유효기간 90일 이상 확인
  □ Redis 클러스터 고가용성 구성 (세션 손실 방지)
  □ 방화벽 운영 환경 오픈 확인

모니터링
  □ 로그인 성공/실패율 알림 설정
  □ Any-ID 서버 응답시간 모니터링 (p95 > 3초 시 알림)
  □ CI_HASH_SALT 키 볼트(Vault) 또는 KMS 보관 확인
  □ 인증서 만료 30일 전 알림 설정

보안
  □ CI 원문이 로그에 출력되지 않는지 확인
  □ client_secret이 소스코드에 없는지 확인
  □ DB ci_hash 컬럼 암호화 또는 해시 처리 확인
  □ 개인정보 처리방침에 CI 수집 항목 명시 확인
```

---

## 10. FAQ

**Q: 데모 환경에서 실제 CI가 발급되나요?**  
A: 데모 환경에서는 **테스트용 가상 CI**가 발급됩니다. 실제 주민번호와 연결되지 않으므로 운영 DB와 혼용하지 마세요.

**Q: 하나의 기관에서 여러 서비스(여러 srvcNo)를 등록할 수 있나요?**  
A: 가능합니다. 같은 기관코드(`instt`) 아래 여러 서비스번호(`srvcNo`)를 등록하면, 서비스별로 다른 scope/redirect_uri/UI를 설정할 수 있습니다.

**Q: Any-ID 장애 시 서비스가 중단되나요?**  
A: 설치형은 Any-ID 플랫폼 의존성이 있어 장애 시 로그인 불가입니다. 비밀번호 로그인 등 폴백 수단을 별도로 유지하는 것을 권장합니다.

**Q: CI 해시 알고리즘을 바꾸면 기존 회원이 로그아웃되나요?**  
A: 네, 해시 알고리즘이나 Salt 변경 시 전체 ci_hash가 달라져 기존 회원과 매핑이 끊어집니다. 변경 시 반드시 마이그레이션 계획을 수립해야 합니다.

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| CI/DN 브로커링 심층 분석 | [05-ci-dn-brokering.md](./05-ci-dn-brokering.md) |
| SSO 세션·등급 관리 | [07-sso-session.md](./07-sso-session.md) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
