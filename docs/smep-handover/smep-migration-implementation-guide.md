# SMEP → 통합플랫폼 인증 마이그레이션 구현 가이드

**문서 ID**: IMPL-2026-001  
**버전**: v1.0  
**작성일**: 2026-05-11  
**작성 목적**: 통합플랫폼 개발자가 현재 SMEP 코드를 기준으로 어떤 파일을 어떻게 수정·보완해야 하는지 코드 레벨로 안내  
**선행 문서**: MIG-2026-002 (SMEP 풀스택 마이그레이션 분석), PRP-2026-001 (마이그레이션 제안서)

---

## 목차

1. [사전 분석: 현재 SMEP BE 인증 구조 전체 지도](#1-사전-분석-현재-smep-be-인증-구조-전체-지도)
2. [OIDC 연동 방식 심층 분석: Adapter vs Spring Security Native](#2-oidc-연동-방식-심층-분석-adapter-vs-spring-security-native)
3. [AccountContextService 분석: FE 호출 경로의 실제 구현](#3-accountcontextservice-분석-fe-호출-경로의-실제-구현)
4. [Phase 1 — 핵심 더미 교체: AuthServiceImpl + SecurityConfig](#4-phase-1--핵심-더미-교체-authserviceimpl--securityconfig)
5. [Phase 2 — FE 보안 강화: CSRF 복원 + ProtectedRoute 구현](#5-phase-2--fe-보안-강화-csrf-복원--protectedroute-구현)
6. [Phase 3 — 설정 파일 교체 및 인프라 연결](#6-phase-3--설정-파일-교체-및-인프라-연결)
7. [Phase 4 — 통합 검증: E2E 흐름 테스트](#7-phase-4--통합-검증-e2e-흐름-테스트)
8. [공수 요약 및 리스크 매트릭스](#8-공수-요약-및-리스크-매트릭스)

---

## 1. 사전 분석: 현재 SMEP BE 인증 구조 전체 지도

### 1.1 인증 경로 3개 전체 흐름

SMEP의 인증은 세 개의 독립된 경로로 구성되어 있다. 마이그레이션 범위를 정확히 파악하기 위해 전체 지도를 먼저 확인한다.

```
┌──────────────────────────────────────────────────────────────────────┐
│                    SMEP 인증 경로 전체 지도                            │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  [경로 1] ID/PW 직접 로그인 ← 이번 마이그레이션의 주요 대상           │
│  FE Login.jsx                                                        │
│    │ POST /api/v1/auth/login                                         │
│    ▼                                                                 │
│  AccountContextController.login()                                    │
│    │                                                                 │
│    ▼                                                                 │
│  AccountContextService.login()                                       │
│    ├─ [H2 환경] loginIndividualLegacy() → AccountUserMapper (H2)     │
│    └─ [PostgreSQL] loginIndividual() → MemberLoginMapper (sc_mbrm)  │
│         │ findIndividualByLoginIdAndPassword()                       │
│         ▼                                                            │
│       issueRealMemberLogin() → AccountJwtProvider.createRealMemberToken()
│         │                                                            │
│         └─ TokenResponse(accessToken, refreshToken)                 │
│                                                                      │
│  [경로 2] OnePass (Keycloak OIDC) 로그인                             │
│  FE Header.jsx - OnePass 버튼                                        │
│    │ keycloakGetAuthCode.js → Keycloak 인가 코드 요청                │
│    ▼                                                                 │
│  Keycloak (integration-sso q-sign) → 인가 코드 콜백                  │
│    │                                                                 │
│    ▼                                                                 │
│  KeycloakController.callbackLocalLogin()                             │
│  POST /api/v1/auth/keycloak/callback/local-login                    │
│    │                                                                 │
│    ▼                                                                 │
│  KeycloakCallbackLocalLoginService.completeLocalLogin()             │
│    ├─ KeycloakTokenService.exchangeCodeForToken() [RestTemplate]    │
│    └─ KeycloakLocalLoginService.loginFromAccessToken()              │
│         │ KeycloakAccessTokenClaimExtractor → UUID 추출             │
│         │ MemberLoginMapper.findIndividualByUuid() / findCorporateByUuid()
│         └─ AccountContextService.issueRealMemberLogin()             │
│                                                                      │
│  [경로 3] 통합로그인 팝업 (로컬 개발 전용 @Profile("local"))          │
│  FE Header.jsx - handleIntegratedLogin()                             │
│    │ GET /api/v1/auth/login-url (@Profile local만 존재)              │
│    ▼                                                                 │
│  SsoAuthController.getLoginUrl() → MockSsoClient / StandardSsoClient│
│    │ 팝업 창 오픈 → SSO 서버 인증 → GET /api/v1/auth/callback       │
│    └─ window.opener.postMessage(토큰)                               │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 1.2 완성된 인프라 vs 미완성 코드 현황

| 컴포넌트 | 파일 | 상태 | 비고 |
|---------|------|------|------|
| JWT 발급 | `AccountJwtProvider.java` | ✅ 완성 | 3가지 토큰 타입 (일반/실회원/기업) |
| JWT 검증 필터 | `JwtAuthenticationFilter.java` | ✅ 완성 | Bearer 추출 → SecurityContext 등록 |
| 실회원 로그인 서비스 | `AccountContextService.java` | ✅ 완성 | PostgreSQL + H2 이중 분기 완성 |
| 회원 DB 조회 | `MemberLoginMapper.java` | ✅ 완성 | UUID/ID-PW/회원번호 조회 완성 |
| OnePass 토큰 교환 | `KeycloakTokenService.java` | ✅ 완성 | RestTemplate 직접 POST |
| OnePass 실회원 매핑 | `KeycloakLocalLoginService.java` | ✅ 완성 | UUID → 실회원 → JWT 발급 |
| 비밀번호 관리 | `AccountPasswordService.java` | ✅ 완성 | 검증 + 변경 + 이력 저장 |
| 계정 정보 조회 | `AccountMeController.java` | ✅ 완성 | 3가지 컨텍스트 분기 완성 |
| **더미 AuthService** | **`AuthServiceImpl.java`** | ❌ 미완성 | **더미 토큰 반환** |
| **Security 설정** | **`SecurityConfig.java`** | ⚠️ 임시 | **전체 API 오픈 상태** |
| **FE CSRF 보호** | `keycloakGetAuthCode.js` | ⚠️ 임시 | **state 검증 주석 처리** |
| **FE 라우트 보호** | `staticRoutes.jsx` | ⚠️ 임시 | **ProtectedRoute 없음** |

---

## 2. OIDC 연동 방식 심층 분석: Adapter vs Spring Security Native

> **이 섹션은 통합플랫폼 담당 개발자 요청 사항입니다.** SMEP BE가 Keycloak Adapter를 사용하는지, Spring Security Native를 사용하는지를 코드 레벨로 확인하고, JDK 21 + Spring Boot 3.5.x 환경에서의 권장 방식을 분석합니다.

### 2.1 결론 먼저

> **SMEP BE는 Keycloak Adapter(Legacy)를 전혀 사용하지 않습니다.**  
> **Spring Security 6.x의 Native OAuth2/OIDC 방식으로 구현되어 있습니다.**  
> **JDK 17 + Spring Boot 3.5.8을 사용합니다 (JDK 21이 아님).**

이 결론은 `build.gradle.kts`의 의존성 분석으로 확정됩니다.

### 2.2 의존성 분석 (build.gradle.kts 전체 확인 결과)

```kotlin
// build.gradle.kts — 실제 의존성 목록 (관련 항목만 발췌)

// ✅ 있는 것 (Spring Security Native 방식)
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
implementation("io.jsonwebtoken:jjwt-api:0.11.5")

// ❌ 없는 것 (Adapter 방식이면 반드시 있어야 함)
// implementation("org.keycloak:keycloak-spring-boot-starter")         ← 없음
// implementation("org.keycloak:keycloak-spring-security-adapter")     ← 없음
// implementation("org.keycloak.bom:keycloak-adapter-bom")             ← 없음
```

**핵심 확인 사항:**
- `keycloak-spring-boot-starter` / `keycloak-spring-security-adapter` — **존재하지 않음**
- `spring-boot-starter-oauth2-resource-server` — **있음** (JWT Bearer 검증용)
- `spring-boot-starter-oauth2-client` — **있음** (OidcUser 주입, OAuth2 클라이언트 흐름용)

### 2.3 두 방식의 구조적 차이

#### Keycloak Adapter 방식 (Legacy — SMEP에 없음)

```java
// Adapter 방식일 경우 이런 코드가 존재해야 함 (SMEP에 없음)
@KeycloakConfiguration
public class SecurityConfig extends KeycloakWebSecurityConfigurerAdapter {
    
    @Autowired
    public void configureGlobal(AuthenticationManagerBuilder auth) {
        KeycloakAuthenticationProvider provider = keycloakAuthenticationProvider();
        auth.authenticationProvider(provider);
    }
    
    @Bean
    @Override
    protected SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new RegisterSessionAuthenticationStrategy(new SessionRegistryImpl());
    }
}

// application.properties (Adapter 방식)
keycloak.auth-server-url=https://keycloak-server/auth
keycloak.realm=my-realm
keycloak.resource=my-client
keycloak.public-client=true
```

**Adapter 방식의 문제점:**
1. **Spring Boot 3.x 미지원**: Keycloak Adapter는 Spring Boot 2.x까지만 공식 지원
2. **Jakarta EE 미지원**: Spring Boot 3.x는 `javax.*` → `jakarta.*` 네임스페이스 변경
3. **Keycloak 21+ Deprecated**: Keycloak 21부터 Spring Adapter 공식 Deprecated 선언
4. **JDK 21 미대응**: 레거시 Adapter가 JDK 21 Virtual Thread와 충돌 가능성 있음

#### Spring Security 6.x Native 방식 (SMEP 실제 사용 방식)

```java
// SMEP의 실제 SecurityConfig.java 구조 (단순화)
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/**").permitAll()  // ← 현재 임시 설정
                .anyRequest().authenticated()
            )
            // oauth2Login()은 주석 처리됨 (아래 주석 참조)
            // .oauth2Login(oauth2 -> oauth2.userInfoEndpoint(...))
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtProvider),
                UsernamePasswordAuthenticationFilter.class
            );
        return http.build();
    }
}
```

```java
// SMEP의 JwtAuthConverter.java — Spring Security Native JWT 방식
@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter 
        = new JwtGrantedAuthoritiesConverter();
    
    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        Collection<GrantedAuthority> authorities = Stream.concat(
            jwtGrantedAuthoritiesConverter.convert(jwt).stream(),
            extractResourceRoles(jwt).stream()
        ).collect(Collectors.toSet());
        return new JwtAuthenticationToken(jwt, authorities, getPrincipalClaimName(jwt));
    }
    
    // Keycloak realm_access.roles 클레임 파싱 (Adapter 없이 직접 구현)
    private Collection<? extends GrantedAuthority> extractResourceRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) return Set.of();
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) realmAccess.get("roles");
        if (roles == null) return Set.of();
        return roles.stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toSet());
    }
}
```

```java
// SMEP의 KeycloakTokenService.java — RestTemplate으로 직접 토큰 교환
@Service
public class KeycloakTokenService {
    @Value("${keycloak.server-url}") private String serverUrl;
    @Value("${keycloak.realm}")      private String realm;
    @Value("${keycloak.client-id}")  private String clientId;
    @Value("${keycloak.client-secret}") private String clientSecret;
    @Value("${keycloak.redirect-uri}")  private String redirectUri;
    
    public KeycloakTokenResponse exchangeCodeForToken(String code) {
        // ↓ Keycloak Adapter의 헬퍼 메서드 없이 직접 HTTP POST
        String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        RestTemplate restTemplate = new RestTemplate();
        
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("redirect_uri", redirectUri);
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        
        return restTemplate.postForObject(tokenUrl, 
            new HttpEntity<>(params, headers), KeycloakTokenResponse.class);
    }
}
```

### 2.4 JDK 21 + Spring Boot 3.5.x 환경에서의 권장 방식 분석

> 통합플랫폼이 JDK 21 + Spring Boot 3.5.x를 사용한다면, 아래 권장 방식을 따라야 합니다.

#### 권장 방식: Spring Security 6.x Native (SMEP과 동일 방향)

```xml
<!-- Maven — Spring Boot 3.5.x 환경 권장 의존성 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-client</artifactId>
</dependency>
```

```kotlin
// Gradle Kotlin DSL (SMEP build.gradle.kts 방식)
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
```

#### Spring Boot 3.5.x에서의 SecurityConfig 완성형 패턴

```java
// 통합플랫폼 권장 SecurityConfig — Spring Boot 3.5.x + JDK 21 환경
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;  // 커스텀 JWT 필터
    private final JwtAuthConverter jwtAuthConverter;               // Keycloak roles 파싱

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // JWT 기반 Stateless 설정 — JDK 21 Virtual Thread와 호환
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // REST API에서는 CSRF 불필요 (Stateless + Bearer Token 방식)
            .csrf(AbstractHttpConfigurer::disable)
            
            // CORS 설정 (FE 오리진 허용)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // 권한 규칙 — 마이그레이션 완료 후 이 부분 반드시 세분화
            .authorizeHttpRequests(auth -> auth
                // 인증 불필요 경로
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/keycloak/**",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                // 그 외 모든 경로는 인증 필요
                .anyRequest().authenticated()
            )
            
            // 커스텀 JWT 필터 등록 (SMEP 방식 그대로 유지)
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            )
            
            // Resource Server 설정 (Keycloak JWT 검증용 — JwtAuthConverter 연결)
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
            );
        
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
            "https://www.smes-tipa.go.kr",
            "http://localhost:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

#### Spring Boot 3.5.x OIDC 직접 연결 설정 (통합플랫폼 적용 방안)

```yaml
# application.yml — Spring Boot 3.5.x OIDC 직접 연결 방식
# Keycloak Adapter 방식의 keycloak.* 설정을 사용하지 않음

spring:
  security:
    oauth2:
      # Resource Server — Keycloak이 발급한 JWT 검증
      resourceserver:
        jwt:
          # integration-sso q-sign의 OpenID Connect Discovery URL
          issuer-uri: https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign
          # 또는 직접 JWK Set URI 지정
          jwk-set-uri: https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/protocol/openid-connect/certs
      
      # OAuth2 Client — 인가 코드 흐름 (Authorization Code Flow)
      client:
        registration:
          keycloak:
            client-id: smes-tipa-01
            client-secret: ${KEYCLOAK_CLIENT_SECRET}  # 환경변수로 주입
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/api/v1/auth/keycloak/callback"
            scope: openid, profile, email
        provider:
          keycloak:
            issuer-uri: https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign
            user-name-attribute: preferred_username

# SMEP 방식의 커스텀 keycloak.* 설정 (토큰 직접 교환용 — 유지 권장)
keycloak:
  server-url: https://www.smes.go.kr/isso-dev/qsign
  realm: ucube-qsign
  client-id: smes-tipa-01
  client-secret: ${KEYCLOAK_CLIENT_SECRET}
  redirect-uri: https://www.smes-tipa.go.kr/home-dev/sso
  logout-callback-uri: https://www.smes-tipa.go.kr/home-dev/sso-logout
```

### 2.5 Adapter 방식 대비 Native 방식의 실질적 장단점

| 항목 | Keycloak Adapter | Spring Security 6.x Native |
|------|-----------------|---------------------------|
| Spring Boot 3.x 호환 | ❌ 공식 미지원 (Deprecated) | ✅ 완벽 지원 |
| JDK 21 Virtual Thread | ⚠️ 미검증 | ✅ 완벽 지원 |
| Jakarta EE 네임스페이스 | ❌ javax.* 기반 (호환 불가) | ✅ jakarta.* 기반 |
| Keycloak 서버 종속성 | ⚠️ Adapter 버전 ↔ Keycloak 버전 강결합 | ✅ 표준 OIDC 스펙만 의존 |
| 설정 복잡도 | 낮음 (Auto-config) | 중간 (명시적 설정 필요) |
| 커스터마이징 자유도 | 낮음 (Adapter 제약) | 높음 (자유로운 구현) |
| 타 IdP 전환 용이성 | 낮음 (Keycloak 전용) | ✅ 높음 (표준 OIDC) |
| SMEP 현재 방식 | ❌ 사용 안 함 | ✅ **SMEP 실제 방식** |

> **결론**: 통합플랫폼이 JDK 21 + Spring Boot 3.5.x 환경이라면, **반드시 Spring Security 6.x Native 방식**을 사용해야 합니다. SMEP이 이미 이 방향으로 설계되어 있으므로, SMEP 코드를 그대로 참조할 수 있습니다.

---

## 3. AccountContextService 분석: FE 호출 경로의 실제 구현

### 3.1 FE Login.jsx → BE 전체 호출 체인 최종 확인

```
FE Login.jsx
  POST /api/v1/auth/login
  Body: { id: "사용자입력ID", password: "사용자입력PW", type: "INDIVIDUAL" | "CORPORATE" }
    ↓
AccountContextController.login(ContextLoginRequest request)
    ↓
AccountContextService.login(ContextLoginRequest request)
    │
    ├─ 유효성 검사: type, id, password 필수값 체크
    │
    ├─ type == "INDIVIDUAL"
    │     ↓
    │   loginIndividual(request)
    │     ├─ [H2 환경] loginIndividualLegacy() → AccountUserMapper.findByLoginId() + 평문 비밀번호 비교
    │     └─ [PostgreSQL 환경] MemberLoginMapper.findIndividualByLoginIdAndPassword()
    │           ↓ (결과 없으면 INVALID_CREDENTIALS 예외)
    │         issueRealMemberLogin(member)
    │           ├─ memberTypeCode == "IND"
    │           │     → AccountJwtProvider.createRealMemberToken(member, AccountTokenContext.individual())
    │           └─ memberTypeCode == "ENT" → ...
    │
    └─ type == "CORPORATE"
          ↓
        loginCorporate(request)
          ├─ [H2 환경] loginCorporateLegacy() → CompanyMapper
          └─ [PostgreSQL 환경] MemberLoginMapper.findCorporateByLoginIdAndPassword()
                ↓
              issueRealMemberLogin(member)
                └─ AccountJwtProvider.createRealMemberToken(member, AccountTokenContext.corporate())

    결과: TokenResponse(accessToken, refreshToken)
    ↓
FE: localStorage.setItem("auth_token", accessToken)
       → GET /api/v1/account/me 자동 호출 → 사용자 정보 저장
```

### 3.2 useLegacyAccountStore() 분기 로직 이해

```java
// AccountContextService.java:283-285
private boolean useLegacyAccountStore() {
    return accountDatasourceUrl != null && accountDatasourceUrl.startsWith("jdbc:h2:");
}
```

이 분기의 의미:
- `account.datasource.url`이 `jdbc:h2:`로 시작하면 → **H2 메모리 DB** (레거시 로컬 개발 환경)
- 그 외 (PostgreSQL) → **sc_mbrm 실회원 DB** 연동

```yaml
# application-local.yml
account:
  datasource:
    url: jdbc:h2:mem:accountdb;  # H2 → useLegacyAccountStore() = true

# application-dev.yml / application.yml
# account.datasource.url이 없거나 postgresql → useLegacyAccountStore() = false
```

> **마이그레이션 시 확인 사항**: PostgreSQL 환경에서 `account.datasource.url`에 H2 URL이 설정되어 있으면 실 DB 연동이 우회됩니다. 설정 파일을 반드시 점검하세요.

### 3.3 실회원 토큰(REAL_MEMBER_DB)의 클레임 구조

`AccountContextServiceIssueRealMemberLoginTest.java` 테스트 코드로 확인된 실제 JWT 클레임:

```json
// 개인회원(IND) 토큰 클레임
{
  "sub": "2026042112314600",          // member_no
  "auth_source": "REAL_MEMBER_DB",   // 인증 소스 (중요: SSO 경로와 구분)
  "member_no": "2026042112314600",
  "type": "INDIVIDUAL",
  "member_type": "IND",
  "login_id": "indv1",
  "name": "개인회원",
  "email": "indv1@example.com",
  "phone_number": "010-1234-5678",
  "roles": ["USER"]
}

// 기업회원(ENT) 토큰 클레임
{
  "sub": "2026042112314601",
  "auth_source": "REAL_MEMBER_DB",
  "member_no": "2026042112314601",
  "type": "CORPORATE",
  "member_type": "ENT",
  "context_role": "OWNER",
  "login_id": "ucube1",
  "name": "유큐브1",
  "company_name": "유큐브1",
  "business_reg_no": "2288105280",
  "brno": "2288105280",
  "roles": ["USER", "COMPANY_OWNER"]
}
```

이 클레임 구조는 `AccountMeController.java`의 3가지 컨텍스트 분기(`REAL_MEMBER_DB`, `SSO`, `CORPORATE`)에서 `auth_source` 필드를 기준으로 분기됩니다.

---

## 4. Phase 1 — 핵심 더미 교체: AuthServiceImpl + SecurityConfig

### 4.1 AuthServiceImpl — 더미 코드 제거

> **우선순위**: 최고  
> **예상 소요 시간**: 2~4시간  
> **영향 범위**: `AuthService` 인터페이스를 주입하는 모든 컨트롤러

#### 현재 코드 (더미 상태)

```java
// 파일 위치: src/main/java/io/github/hipstermin/idem/account/service/impl/AuthServiceImpl.java
// 현재 상태: 더미 토큰 반환으로 항상 로그인 성공

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    @Override
    public TokenResponse login(LoginRequest loginRequest) {
        // TODO: Keycloak과 연동하여 실제 인증을 처리하고 JWT를 발급받는 로직 구현
        return new TokenResponse("dummy-access-token", "dummy-refresh-token");
    }
    
    // scenarioLogin 등 기타 메서드...
}
```

> ⚠️ **중요 발견**: `AuthServiceImpl.login()`은 `AuthController`의 `POST /api/v1/account/login`과 연결된다. FE `Login.jsx`가 호출하는 경로는 `POST /api/v1/auth/login` (AccountContextController)이므로, 두 경로의 관계를 명확히 구분해야 한다.

#### 두 로그인 경로의 차이 (혼동 주의)

| 항목 | AuthController (`/api/v1/account/login`) | AccountContextController (`/api/v1/auth/login`) |
|------|------------------------------------------|------------------------------------------------|
| 서비스 | `AuthServiceImpl.login()` → **더미** | `AccountContextService.login()` → **실 DB 연동** |
| FE 호출 여부 | `AuthController`의 `/scenario-login`용 | **FE Login.jsx가 직접 호출하는 실제 경로** |
| 마이그레이션 우선순위 | 중간 (시연 목적) | **높음 (프로덕션 필수)** |

#### 수정 방법 1: AuthServiceImpl을 AccountContextService에 위임 (권장)

```java
// 수정 후 AuthServiceImpl.java
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AccountContextService accountContextService;  // 주입 추가

    @Override
    public TokenResponse login(LoginRequest loginRequest) {
        // LoginRequest → ContextLoginRequest 변환
        ContextLoginRequest contextRequest = ContextLoginRequest.builder()
            .id(loginRequest.getLoginId())
            .password(loginRequest.getPassword())
            .type(loginRequest.getType() != null ? loginRequest.getType() : "INDIVIDUAL")
            .build();
        
        return accountContextService.login(contextRequest);
    }
    
    // 시연용 scenarioLogin은 별도 DB (H2) 조회로 유지 가능
    @Override
    public TokenResponse scenarioLogin(ScenarioLoginRequest request) {
        // 기존 로직 유지 (시연용 더미 DB)
        return accountContextService.login(/* 변환 */);
    }
}
```

#### 수정 방법 2: AuthController 자체를 AccountContextController로 통합 (선택적)

```java
// AuthController에서 직접 AccountContextService 사용
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
public class AuthController {

    private final AccountContextService accountContextService;  // AuthService 대신 직접 사용

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody ContextLoginRequest request) {
        // AuthServiceImpl 우회, 직접 AccountContextService 호출
        return ResponseEntity.ok(accountContextService.login(request));
    }
    
    // ... 나머지 메서드
}
```

### 4.2 SecurityConfig — 전체 오픈 설정 해제

> **우선순위**: 최고  
> **예상 소요 시간**: 4~8시간 (전체 API 엔드포인트 목록 정리 필요)  
> **현재 위험**: 인증 없이 모든 `/api/v1/**` 접근 가능

#### 현재 코드 (위험 상태)

```java
// 파일 위치: src/main/java/io/github/hipstermin/idem/account/config/SecurityConfig.java
// 현재 위험: 전체 API 오픈

.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/**").permitAll()  // ← 개발완료 및 운영반영시 수정 주석
    .anyRequest().authenticated()
)
```

#### 수정 후 코드 (운영 적용 가능한 최소 권한 원칙)

```java
// 수정 후 SecurityConfig.java
.authorizeHttpRequests(auth -> auth
    // 1. 인증 없이 접근 가능한 공개 API
    .requestMatchers(HttpMethod.POST, 
        "/api/v1/auth/login",           // ID/PW 로그인
        "/api/v1/auth/refresh"          // 토큰 갱신
    ).permitAll()
    .requestMatchers(
        "/api/v1/auth/keycloak/**",     // OnePass OIDC 콜백
        "/api/v1/auth/login-url",       // SSO 팝업 URL (local 전용)
        "/api/v1/auth/callback"         // SSO 팝업 콜백 (local 전용)
    ).permitAll()
    
    // 2. Actuator (헬스체크만 오픈)
    .requestMatchers("/actuator/health").permitAll()
    
    // 3. API 문서 (개발 환경에서만 오픈 — 운영 시 별도 처리 권장)
    .requestMatchers(
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    ).permitAll()
    
    // 4. 그 외 모든 API는 인증 필요
    .anyRequest().authenticated()
)
```

#### SecurityConfig 전체 수정본

```java
// src/main/java/io/github/hipstermin/idem/account/config/SecurityConfig.java (완성형)
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AccountJwtProvider jwtProvider;
    // JwtAuthConverter는 oauth2ResourceServer 설정 시 필요 (현재 SMEP은 커스텀 필터 방식)
    // private final JwtAuthConverter jwtAuthConverter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // ─── 인증 불필요 경로 (명시적으로 열어둔 것만) ───
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh"
                ).permitAll()
                .requestMatchers(
                    "/api/v1/auth/keycloak/**",
                    "/api/v1/auth/login-url",
                    "/api/v1/auth/callback",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                ).permitAll()
                // ─── 그 외 모든 경로는 인증 필요 ───
                .anyRequest().authenticated()
            )
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtProvider),
                UsernamePasswordAuthenticationFilter.class
            );
        
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
            "https://www.smes-tipa.go.kr",
            "https://www.smes-tipa.go.kr:*",
            "http://localhost:*"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
```

---

## 5. Phase 2 — FE 보안 강화: CSRF 복원 + ProtectedRoute 구현

### 5.1 keycloakGetAuthCode.js — CSRF state 복원

> **위험 등급**: 높음 (CSRF 공격 취약)  
> **파일 위치**: `src/utils/keycloakGetAuthCode.js`

#### 현재 코드 (취약 상태)

```javascript
// keycloakGetAuthCode.js — 현재 state 생성/검증 주석 처리됨
export const getKeycloakAuthCode = (config) => {
  const { clientId, redirectUri, authorizationEndpoint, realm } = config;
  
  // CSRF 방지용 state 파라미터 — 현재 주석 처리 (취약!)
  // const state = crypto.randomUUID();
  // sessionStorage.setItem('oauth_state', state);
  
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    // state: state,  ← 주석 처리됨
  });
  
  window.location.href = `${authorizationEndpoint}?${params}`;
};
```

#### 수정 후 코드 (CSRF 보호 복원)

```javascript
// keycloakGetAuthCode.js — CSRF state 검증 복원
export const getKeycloakAuthCode = (config) => {
  const { clientId, redirectUri, authorizationEndpoint, realm } = config;
  
  // ✅ CSRF 방지: state 파라미터 생성 및 저장
  const state = crypto.randomUUID();
  const nonce = crypto.randomUUID();
  sessionStorage.setItem('oauth_state', state);
  sessionStorage.setItem('oauth_nonce', nonce);
  
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    state: state,   // ✅ 복원
    nonce: nonce,   // ✅ 재전송 공격 방지
  });
  
  window.location.href = `${authorizationEndpoint}?${params}`;
};
```

### 5.2 OnePassSsoCallback.jsx — state 검증 복원

> **파일 위치**: `src/pages/OnePassSsoCallback.jsx`

#### 현재 코드 (state 검증 우회 상태)

```javascript
// OnePassSsoCallback.jsx — 현재 state 검증 우회
useEffect(() => {
  const { code, state } = parseQueryParams();
  
  // state 검증 — 현재 주석 처리 (취약!)
  // const savedState = sessionStorage.getItem('oauth_state');
  // if (state !== savedState) {
  //   setError('CSRF state mismatch');
  //   return;
  // }
  
  // stateValidationBypassed: true 플래그로 우회
  processCallback(code, { stateValidationBypassed: true });
}, []);
```

#### 수정 후 코드 (state 검증 복원)

```javascript
// OnePassSsoCallback.jsx — state 검증 복원
useEffect(() => {
  const params = new URLSearchParams(window.location.search);
  const code = params.get('code');
  const state = params.get('state');
  const error = params.get('error');
  
  // ✅ 에러 응답 처리
  if (error) {
    console.error('[SSO Callback] OAuth error:', error, params.get('error_description'));
    navigate('/service/login?error=sso_failed');
    return;
  }
  
  if (!code) {
    navigate('/service/login?error=no_code');
    return;
  }
  
  // ✅ CSRF state 검증 복원
  const savedState = sessionStorage.getItem('oauth_state');
  sessionStorage.removeItem('oauth_state');  // 일회성: 사용 후 즉시 삭제
  sessionStorage.removeItem('oauth_nonce');
  
  if (!savedState || state !== savedState) {
    console.error('[SSO Callback] CSRF state mismatch. Expected:', savedState, 'Got:', state);
    navigate('/service/login?error=csrf_failed');
    return;
  }
  
  // ✅ 검증 통과 후 서버에 코드 전달
  handleCallback(code);
}, []);

const handleCallback = async (code) => {
  try {
    const response = await axios.post('/api/v1/auth/keycloak/callback/local-login', { code });
    const { accessToken, refreshToken } = response.data;
    
    // 토큰 저장 (LoginService와 동일한 방식)
    localStorage.setItem('auth_token', accessToken);
    localStorage.setItem('refresh_token', refreshToken);
    
    navigate('/');
  } catch (err) {
    console.error('[SSO Callback] Token exchange failed:', err);
    navigate('/service/login?error=token_exchange_failed');
  }
};
```

### 5.3 staticRoutes.jsx — ProtectedRoute 구현

> **파일 위치**: `src/routes/staticRoutes.jsx`  
> **현재 문제**: 인증되지 않은 사용자도 모든 내부 페이지 접근 가능

#### ProtectedRoute 컴포넌트 신규 구현

```javascript
// src/components/auth/ProtectedRoute.jsx — 신규 생성
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';

/**
 * 인증이 필요한 라우트를 보호하는 컴포넌트
 * 미인증 시 /service/login으로 리다이렉트 (redirect 파라미터 포함)
 */
const ProtectedRoute = ({ children, requiredRole = null }) => {
  const { isAuthenticated, user } = useAuthStore();
  const location = useLocation();
  
  // 인증 상태 확인
  if (!isAuthenticated) {
    // 로그인 후 원래 페이지로 돌아올 수 있도록 현재 경로 저장
    return (
      <Navigate 
        to={`/service/login`} 
        state={{ from: location.pathname }}
        replace 
      />
    );
  }
  
  // 역할 기반 접근 제어 (필요한 경우)
  if (requiredRole && !user?.roles?.includes(requiredRole)) {
    return <Navigate to="/unauthorized" replace />;
  }
  
  return children;
};

export default ProtectedRoute;
```

#### staticRoutes.jsx 수정

```javascript
// src/routes/staticRoutes.jsx — ProtectedRoute 적용
import ProtectedRoute from '@/components/auth/ProtectedRoute';

// ─── 수정 전 (현재 상태) ───
{
  path: '/my-business',
  element: <SubpageLayoutWithMenu />,
  children: [
    { path: 'dashboard', element: <UI_USR_R_480 /> },
    { path: 'member', element: <CompanyDetail /> },
    { path: 'password', element: <PasswordChange /> },
    // ... 모든 내부 페이지 보호 없음
  ]
}

// ─── 수정 후 ───
{
  path: '/my-business',
  element: (
    <ProtectedRoute>
      <SubpageLayoutWithMenu />
    </ProtectedRoute>
  ),
  children: [
    { path: 'dashboard', element: <UI_USR_R_480 /> },
    { path: 'member', element: <CompanyDetail /> },
    { 
      path: 'password', 
      element: (
        // 비밀번호 변경은 REAL_MEMBER_DB 인증 소스만 허용
        <ProtectedRoute requiredRole="USER">
          <PasswordChange />
        </ProtectedRoute>
      )
    },
    // ... 기타 내부 페이지
  ]
}
```

### 5.4 App.jsx — AI API Key 하드코딩 제거

> **현재 문제**: App.jsx에 AI API Key 2개 하드코딩  
> **위험**: 소스 코드 노출 시 API Key 유출 → 즉각적인 금전적 피해 가능

```javascript
// App.jsx — 현재 위험 상태 (예시)
const OPENAI_API_KEY = 'sk-...hardcoded-key...';  // ← 절대 안 됨
const ANTHROPIC_API_KEY = 'sk-ant-...hardcoded...';

// 수정 후: 환경변수로 분리
const OPENAI_API_KEY = import.meta.env.VITE_OPENAI_API_KEY;
const ANTHROPIC_API_KEY = import.meta.env.VITE_ANTHROPIC_API_KEY;
```

```bash
# .env.local (git ignore에 포함되어야 함)
VITE_OPENAI_API_KEY=sk-...실제키...
VITE_ANTHROPIC_API_KEY=sk-ant-...실제키...
```

```
# .gitignore에 반드시 추가
.env
.env.local
.env.*.local
```

> ⚠️ **긴급 조치 필요**: 현재 키가 이미 git 히스토리에 커밋되어 있다면, git history 전체 rewrite 후 해당 API Key를 즉시 폐기하고 새 Key를 발급해야 합니다.

---

## 6. Phase 3 — 설정 파일 교체 및 인프라 연결

### 6.1 application.yml — JWT Secret 환경변수화

#### 현재 상태

```yaml
# application.yml
jwt:
  secret: ${JWT_SECRET_KEY}  # 이미 환경변수 참조 (올바른 방식)
  issuer: http://localhost:8081
  validity-seconds: 3600
  refresh-validity-seconds: 1209600
```

#### 운영 환경 설정 추가 필요 사항

```yaml
# application-prod.yml (신규 작성 필요)
server:
  port: 8081
  
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver

jwt:
  secret: ${JWT_SECRET_KEY}       # 최소 256bit (32바이트) 이상 랜덤 문자열
  issuer: https://www.smes-tipa.go.kr
  validity-seconds: 3600          # 1시간
  refresh-validity-seconds: 1209600  # 14일

keycloak:
  server-url: ${KEYCLOAK_SERVER_URL}    # https://www.smes.go.kr/isso/qsign
  realm: ${KEYCLOAK_REALM}             # ucube-qsign
  client-id: ${KEYCLOAK_CLIENT_ID}     # smes-tipa-01
  client-secret: ${KEYCLOAK_CLIENT_SECRET}
  redirect-uri: ${KEYCLOAK_REDIRECT_URI}  # https://www.smes-tipa.go.kr/sso
  logout-callback-uri: ${KEYCLOAK_LOGOUT_CALLBACK_URI}

redis:
  host: ${REDIS_HOST}
  port: ${REDIS_PORT:6379}
  password: ${REDIS_PASSWORD:}
```

### 6.2 application-local.yml — H2 → PostgreSQL 전환

```yaml
# application-local.yml 현재 상태
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smep
    username: smep_user
    password: smep_pass

account:
  datasource:
    url: jdbc:h2:mem:accountdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE  # ← H2 레거시

# account.datasource.url이 H2로 설정되면 useLegacyAccountStore() = true
# → MemberLoginMapper(실 DB) 대신 AccountUserMapper(H2) 사용
# → 마이그레이션 테스트 시 반드시 이 값 변경 필요
```

```yaml
# application-local.yml 수정 후 (PostgreSQL 전환)
account:
  datasource:
    url: jdbc:postgresql://localhost:5432/smep  # ← H2에서 PostgreSQL로 변경
    username: smep_user
    password: smep_pass
    
# 이렇게 하면 useLegacyAccountStore() = false
# → MemberLoginMapper.findIndividualByLoginIdAndPassword() 실제 호출
```

### 6.3 integration-sso 연결 확인 체크리스트

현재 `application-local.yml`의 `keycloak.server-url`은 이미 integration-sso q-sign 엔드포인트를 가리키고 있습니다. 하지만 실제 통합 연결 전에 다음을 반드시 확인해야 합니다.

#### integration-sso 모듈별 포트 및 엔드포인트

| 모듈 | 포트 | 역할 | SMEP과의 연결 지점 |
|------|------|------|-------------------|
| q-sign | 8081 | OIDC 인증 (Keycloak 호환) | `keycloak.server-url` |
| q-im | 8082 | Identity Management | 미연결 (추후 연동 고려) |
| ido | 8083 | IdO 중재 (ADR-001) | SMEP 직접 호출 안 함 |
| agency-stub | 8084 | 기관 스텁 (테스트용) | 로컬 테스트 시 사용 |

#### Keycloak OIDC Discovery 엔드포인트 확인

```bash
# integration-sso q-sign의 OIDC Discovery 엔드포인트 확인
# 성공하면 JSON 응답에 authorization_endpoint, token_endpoint, jwks_uri 등이 포함됨

curl https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/.well-known/openid-configuration

# 예상 응답 (일부)
{
  "issuer": "https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign",
  "authorization_endpoint": "https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/protocol/openid-connect/auth",
  "token_endpoint": "https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/protocol/openid-connect/token",
  "jwks_uri": "https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/protocol/openid-connect/certs",
  ...
}
```

#### KeycloakTokenService URL 구조 확인

```java
// KeycloakTokenService.java에서 조립되는 URL
// serverUrl = https://www.smes.go.kr/isso-dev/qsign
// realm = ucube-qsign
// tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token"
// = https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/protocol/openid-connect/token

// ✅ 확인: integration-sso q-sign이 이 경로에서 토큰을 발급하는지 테스트 필요
curl -X POST \
  https://www.smes.go.kr/isso-dev/qsign/realms/ucube-qsign/protocol/openid-connect/token \
  -d 'grant_type=client_credentials' \
  -d 'client_id=smes-tipa-01' \
  -d 'client_secret=QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF'
```

### 6.4 KeycloakAccessTokenClaimExtractor — 서명 검증 추가 권장

> **현재 보안 취약점**: SMEP의 `KeycloakAccessTokenClaimExtractor`는 Keycloak access token을 서명 검증 없이 Base64 decode만 하여 UUID를 추출합니다.

```java
// 현재 취약한 구현
public class KeycloakAccessTokenClaimExtractor {
    public String extractUuid(String accessToken) {
        // ⚠️ 서명 검증 없이 Base64 decode만 수행
        String[] parts = accessToken.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonObject claims = JsonParser.parseString(payload).getAsJsonObject();
        return claims.get("sub").getAsString();
    }
}
```

```java
// 권장하는 개선 방법: Spring Security의 NimbusJwtDecoder 사용
@Component
public class KeycloakAccessTokenClaimExtractor {
    
    private final JwtDecoder jwtDecoder;
    
    public KeycloakAccessTokenClaimExtractor(
            @Value("${keycloak.server-url}") String serverUrl,
            @Value("${keycloak.realm}") String realm) {
        // JWK Set URI로 서명 검증 JwtDecoder 생성
        String jwksUri = serverUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
        this.jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
    
    public String extractUuid(String accessToken) {
        // ✅ 서명 검증 + Claims 추출
        Jwt jwt = jwtDecoder.decode(accessToken);
        return jwt.getSubject();  // sub 클레임 = Keycloak UUID
    }
}
```

---

## 7. Phase 4 — 통합 검증: E2E 흐름 테스트

### 7.1 테스트 시나리오 체크리스트

#### 시나리오 1: ID/PW 로그인 (경로 1)

```
테스트 전제:
- SMEP BE 기동 (application-dev 또는 -local 프로파일)
- PostgreSQL에 sc_mbrm 스키마 및 실회원 데이터 존재
- account.datasource.url이 postgresql (H2가 아님)

테스트 단계:
1. FE Login.jsx 접속 (/service/login)
2. 개인회원 탭 선택 → 실 회원 ID/PW 입력
3. 로그인 버튼 클릭

예상 네트워크:
  POST /api/v1/auth/login  →  200 OK  { accessToken, refreshToken }
  GET /api/v1/account/me   →  200 OK  { auth_source: "REAL_MEMBER_DB", ... }

확인 사항:
  ✅ localStorage에 auth_token 저장됨
  ✅ auth_source가 "REAL_MEMBER_DB"임
  ✅ 홈 화면으로 리다이렉트됨
  ✅ 잘못된 ID/PW 입력 시 401 응답 수신

```

#### 시나리오 2: OnePass (Keycloak OIDC) 로그인 (경로 2)

```
테스트 전제:
- integration-sso q-sign 모듈 기동 중 (포트 8081 또는 원격)
- Keycloak realm(ucube-qsign)에 테스트 사용자 등록됨
- keycloak.* 설정이 정확히 구성됨

테스트 단계:
1. 헤더의 OnePass 버튼 클릭
2. Keycloak 로그인 페이지로 리다이렉트됨
3. Keycloak에서 로그인 완료
4. redirect-uri(SMEP)로 콜백 수신

예상 네트워크:
  GET  /api/v1/auth/keycloak/callback/local-login?code=xxx  →  200 OK  { accessToken, refreshToken }
  GET  /api/v1/account/me  →  200 OK  { auth_source: "SSO", ... }

확인 사항:
  ✅ CSRF state 검증 통과 (sessionStorage oauth_state 일치)
  ✅ auth_source가 "SSO"임
  ✅ sc_mbrm UUID 매핑이 정상 (findIndividualByUuid 성공)

실패 시 확인 사항:
  ❌ state mismatch → keycloakGetAuthCode.js의 state 저장/전달 확인
  ❌ UUID not found → sc_mbrm.mem_info 테이블에 Keycloak UUID 매핑 데이터 확인
  ❌ token endpoint 오류 → KeycloakTokenService URL 구조 확인
```

#### 시나리오 3: 토큰 갱신 (Refresh Token Rotation)

```
테스트 단계:
1. 로그인 후 accessToken 수동 만료 (JWT exp 조작 또는 1시간 대기)
2. 인증이 필요한 API 호출 시도

예상 동작:
  401 수신 → FE Axios Interceptor → POST /api/v1/auth/refresh
  → 새 accessToken/refreshToken 발급 → 재시도 성공

확인 사항:
  ✅ refreshToken Rotation (이전 refreshToken이 Redis에서 삭제됨)
  ✅ 새 accessToken으로 원래 요청 재시도 성공
```

#### 시나리오 4: 보호된 라우트 접근 제어

```
테스트 단계:
1. 로그아웃 상태에서 /my-business/dashboard 직접 접근
2. 로그인 후 /my-business/dashboard 접근

예상 동작:
  [미인증] → ProtectedRoute → Navigate to /service/login?from=/my-business/dashboard
  [인증됨] → 정상 렌더링

확인 사항:
  ✅ 미인증 시 로그인 페이지로 리다이렉트됨
  ✅ 로그인 후 원래 페이지(from 파라미터)로 복귀됨
```

### 7.2 통합 테스트 코드 (Spring Boot Test)

```java
// src/test/java/io/github/hipstermin/idem/account/integration/AuthIntegrationTest.java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class AuthIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("개인회원 ID/PW 로그인 → 실 JWT 발급 성공")
    void individualLogin_shouldReturnRealJwt() {
        // Given — 테스트 DB에 실회원 데이터 사전 삽입 필요
        ContextLoginRequest request = new ContextLoginRequest();
        request.setId("testuser");
        request.setPassword("TestPass123!");
        request.setType("INDIVIDUAL");
        
        // When
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
            "/api/v1/auth/login", request, TokenResponse.class);
        
        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAccessToken())
            .isNotBlank()
            .doesNotContain("dummy");  // 더미 토큰이 아님을 확인
    }
    
    @Test
    @DisplayName("더미 토큰 차단 확인 — AuthServiceImpl 교체 후")
    void authServiceImpl_shouldNotReturnDummyToken() {
        // AuthServiceImpl이 더미를 반환하는지 확인하는 테스트
        // 마이그레이션 완료 후 이 테스트가 통과해야 함
        ContextLoginRequest request = new ContextLoginRequest();
        request.setId("any");
        request.setPassword("any");
        request.setType("INDIVIDUAL");
        
        ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
            "/api/v1/auth/login", request, TokenResponse.class);
        
        // 실 DB 조회 실패로 401이거나, 성공했다면 더미가 아닌 실 JWT여야 함
        assertThat(response.getBody().getAccessToken())
            .isNotEqualTo("dummy-access-token");
    }
    
    @Test
    @DisplayName("SecurityConfig 수정 후 — 인증 없이 /api/v1/account/me 접근 차단")
    void protectedEndpoint_withoutToken_shouldReturn401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
            "/api/v1/account/me", String.class);
        
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

---

## 8. 공수 요약 및 리스크 매트릭스

### 8.1 단계별 공수 요약

| Phase | 작업 내용 | 예상 공수 | 담당 | 비고 |
|-------|---------|---------|------|------|
| Phase 1-A | AuthServiceImpl 더미 교체 | 2~4h | BE | AccountContextService 위임으로 단순 |
| Phase 1-B | SecurityConfig 권한 규칙 세분화 | 4~8h | BE | 전체 API 엔드포인트 목록 정리 필요 |
| Phase 2-A | FE CSRF state 복원 | 2~3h | FE | 주석 해제 + 검증 로직 추가 |
| Phase 2-B | ProtectedRoute 구현 | 4~6h | FE | 컴포넌트 신규 생성 + routes 수정 |
| Phase 2-C | App.jsx API Key 환경변수화 | 1h | FE | 즉시 조치 권장 |
| Phase 3-A | application.yml 운영 프로파일 작성 | 2~4h | DevOps/BE | 환경변수 목록 인프라팀 협의 필요 |
| Phase 3-B | H2 → PostgreSQL 전환 (로컬) | 1~2h | BE | account.datasource.url 변경 |
| Phase 3-C | integration-sso 연결 E2E 검증 | 4~8h | BE+인프라 | q-sign 엔드포인트 curl 테스트 포함 |
| Phase 4 | 통합 검증 (4개 시나리오) | 1~2일 | FE+BE | 시나리오 체크리스트 순차 진행 |
| **합계** | | **약 1~1.5주** | | 병렬 진행 가능 |

### 8.2 리스크 매트릭스

| 위험 항목 | 심각도 | 발생 가능성 | 대응 방안 |
|---------|-------|-----------|---------|
| AuthServiceImpl 더미 토큰 → 프로덕션 배포 | 🔴 치명적 | 중간 | CI/CD에 더미 토큰 문자열 grep 차단 추가 |
| SecurityConfig 전체 오픈 → 운영 반영 | 🔴 치명적 | 중간 | 배포 전 `/api/v1/**` permitAll 존재 여부 자동 검사 |
| FE API Key 하드코딩 → git 노출 | 🔴 치명적 | 높음 | 즉시 키 폐기 + 환경변수 전환 |
| CSRF state 미검증 → CSRF 공격 | 🟠 높음 | 중간 | Phase 2-A 완료 전 OnePass 기능 비활성화 |
| H2 useLegacyAccountStore 분기 → 실 DB 미연동 | 🟠 높음 | 중간 | account.datasource.url 검증 스크립트 |
| integration-sso UUID 매핑 데이터 누락 | 🟡 중간 | 중간 | sc_mbrm UUID 칼럼 데이터 사전 동기화 |
| KeycloakAccessTokenClaimExtractor 서명 미검증 | 🟡 중간 | 낮음 | Phase 3 이후 NimbusJwtDecoder 전환 |
| ProtectedRoute 미구현 → 페이지 직접 접근 | 🟡 중간 | 낮음 | BE SecurityConfig 세분화로 1차 방어 가능 |

### 8.3 즉시 조치 권장 사항 (마이그레이션 전에도 해야 할 것)

다음 3가지는 마이그레이션 일정과 무관하게 **지금 당장** 조치해야 하는 보안 이슈입니다:

1. **App.jsx AI API Key 즉시 폐기 및 환경변수화** — git 히스토리에 키가 있다면 해당 키 무효화 처리
2. **application-local.yml의 client-secret 로테이션 고려** — 문서에 공개된 `QyEn0EKMz3lsGNgPkw9TxPUvdMUQ4KPF` 값이 개발 환경 전용인지 확인 후 운영 환경은 반드시 별도 키 사용
3. **AuthServiceImpl 더미 코드에 `@Profile("local")` 또는 Feature Flag 추가** — 실수로 운영 배포 시 더미 토큰이 발급되는 상황 방지

```java
// 즉시 적용 가능한 임시 안전장치
@Service
@Profile("!prod")  // 운영 환경에서는 이 빈 자체를 등록 안 함
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    @Override
    public TokenResponse login(LoginRequest loginRequest) {
        return new TokenResponse("dummy-access-token", "dummy-refresh-token");
    }
}

// 운영 환경용 별도 구현체 (나중에 교체)
@Service
@Profile("prod")
@RequiredArgsConstructor
public class ProdAuthServiceImpl implements AuthService {
    private final AccountContextService accountContextService;
    
    @Override
    public TokenResponse login(LoginRequest loginRequest) {
        // 실 DB 연동
        ContextLoginRequest req = /* 변환 */;
        return accountContextService.login(req);
    }
}
```

---

## 부록 A — 파일별 수정 작업 목록

| 파일 경로 | 수정 유형 | 우선순위 | 섹션 참조 |
|---------|---------|--------|---------|
| `src/main/java/.../service/impl/AuthServiceImpl.java` | 더미 교체 | 🔴 즉시 | §4.1 |
| `src/main/java/.../config/SecurityConfig.java` | 권한 규칙 세분화 | 🔴 즉시 | §4.2 |
| `src/main/resources/application-prod.yml` | 신규 작성 | 🔴 즉시 | §6.1 |
| `src/main/resources/application-local.yml` | H2 → PostgreSQL | 🟠 높음 | §6.2 |
| `src/main/java/.../service/KeycloakAccessTokenClaimExtractor.java` | 서명 검증 추가 | 🟡 중간 | §6.4 |
| `src/utils/keycloakGetAuthCode.js` | CSRF state 복원 | 🟠 높음 | §5.1 |
| `src/pages/OnePassSsoCallback.jsx` | state 검증 복원 | 🟠 높음 | §5.2 |
| `src/routes/staticRoutes.jsx` | ProtectedRoute 적용 | 🟡 중간 | §5.3 |
| `src/components/auth/ProtectedRoute.jsx` | 신규 생성 | 🟡 중간 | §5.3 |
| `src/App.jsx` | API Key 환경변수화 | 🔴 즉시 | §5.4 |
| `.env.local` | 신규 생성 | 🔴 즉시 | §5.4 |
| `.gitignore` | .env 추가 | 🔴 즉시 | §5.4 |

## 부록 B — integration-sso 연결 설정 Quick Reference

```yaml
# SMEP BE 현재 설정 → integration-sso 연결 매핑
keycloak:
  server-url: https://www.smes.go.kr/isso-dev/qsign    # q-sign 모듈 베이스 URL
  realm: ucube-qsign                                    # Keycloak Realm 이름
  client-id: smes-tipa-01                               # OAuth2 Client ID
  client-secret: [환경변수로 분리 필요]                  # Client Secret
  redirect-uri: https://www.smes-tipa.go.kr/home-dev/sso  # 콜백 URI

# KeycloakTokenService가 조립하는 URL
token-endpoint: {server-url}/realms/{realm}/protocol/openid-connect/token
auth-endpoint:  {server-url}/realms/{realm}/protocol/openid-connect/auth
logout-endpoint: {server-url}/realms/{realm}/protocol/openid-connect/logout
jwks-endpoint:  {server-url}/realms/{realm}/protocol/openid-connect/certs
discovery:      {server-url}/realms/{realm}/.well-known/openid-configuration
```

---

*문서 끝 — IMPL-2026-001 v1.0*
