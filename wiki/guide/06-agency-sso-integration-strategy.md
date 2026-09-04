# SDK 탄력적 아키텍처 및 자체 SSO 통합 전략

> **버전**: v0.9.0 | **작성일**: 2026-05-17 | **대상**: 유관기관 개발팀 / OnePass 플랫폼 팀  
> **표준 참조**: RFC 7636 (PKCE), RFC 8693 (Token Exchange), OpenID Connect Core 1.0

---

## 목차

1. [SDK 탄력적 아키텍처 설계](#1-sdk-탄력적-아키텍처-설계)
2. [자체 SSO 보유 기관 통합 전략](#2-자체-sso-보유-기관-통합-전략)
3. [기관 유형별 통합 패턴](#3-기관-유형별-통합-패턴)
4. [기술 표준 준수 매핑](#4-기술-표준-준수-매핑)
5. [SDK 업그레이드 전략](#5-sdk-업그레이드-전략)
6. [구현 체크리스트](#6-구현-체크리스트)

---

## 1. SDK 탄력적 아키텍처 설계

### 1.1 설계 원칙

OnePass Agency SDK는 다음 원칙으로 설계되었습니다:

```
① JDK 버전 독립성: Java 8 바이트코드 → JDK 8/11/17/21 및 Android 모두 지원
② 런타임 의존성 ZERO: 기본 구현(HttpURLConnection)은 JDK 내장만 사용
③ 교체 가능 HTTP 레이어: AgencyHttpAdapter 인터페이스 기반 전략 패턴
④ 불변 값 객체: Builder 패턴 + final 필드로 스레드 안전 보장
⑤ 표준 헤더 준수: RFC 7235(인증), IETF draft(Idempotency-Key)
```

### 1.2 현재 SDK 구조

```
idem-sdk-java/
├── AgencyGatewayClient          ← 진입점 (Builder 패턴, thread-safe)
├── http/
│   ├── AgencyHttpAdapter        ← 핵심 SPI 인터페이스
│   ├── HttpUrlConnectionAdapter ← 기본 구현 (JDK 내장)
│   ├── OkHttpAgencyAdapter      ← OkHttp3 플러그인
│   └── ApacheHttpAgencyAdapter  ← Apache HC5 플러그인
├── security/
│   └── HmacSigner               ← HMAC-SHA256 서명
├── idempotency/
│   └── IdempotencyKeyGenerator  ← UUID v7 기반
└── model/
    ├── GatewayResponse
    ├── InboundEvent
    └── OutboundNotifyRequest
```

### 1.3 확장 포인트 — AgencyHttpAdapter SPI

`AgencyHttpAdapter`는 SDK의 핵심 교체 포인트입니다. 기관 측 HTTP 인프라에 맞게 구현만 교체하면 됩니다:

```java
// 기관이 이미 RestTemplate을 쓰는 경우
AgencyHttpAdapter springAdapter = (method, url, headers, body) -> {
    HttpHeaders httpHeaders = new HttpHeaders();
    headers.forEach(httpHeaders::add);
    HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
    ResponseEntity<String> resp = restTemplate.exchange(
        url, HttpMethod.resolve(method), entity, String.class);
    return GatewayResponse.of(resp.getStatusCode().value(), resp.getBody(), null, null);
};

// 기관이 WebClient(Reactive)를 쓰는 경우 (블로킹 래핑)
AgencyHttpAdapter reactiveAdapter = (method, url, headers, body) -> {
    String response = webClient.method(HttpMethod.resolve(method))
        .uri(url)
        .headers(h -> headers.forEach(h::add))
        .bodyValue(body != null ? body : "")
        .retrieve()
        .bodyToMono(String.class)
        .block(Duration.ofSeconds(30));  // 타임아웃 명시 필수
    return GatewayResponse.of(200, response, null, null);
};

// mTLS 필요 기관
AgencyHttpAdapter mtlsAdapter = (method, url, headers, body) -> {
    // 기관 자체 mTLS 키스토어로 구성된 클라이언트 사용
    return mtlsHttpClient.execute(method, url, headers, body);
};
```

### 1.4 미래 확장 — SDK v2 권장 구조

현재 SDK v1에서 v2로 진화할 때 하위 호환성을 유지하면서 확장하는 방법:

```java
// SDK v2: AgencyGatewayClient에 인터셉터 체인 추가 권장
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://onepass-gateway.go.kr")
    .apiKey(System.getenv("ONEPASS_API_KEY"))
    .agencyCode("AGENCY_001")
    .hmacSecret(System.getenv("ONEPASS_HMAC_SECRET"))
    .signRequests(true)
    // v2 확장 (현재 미지원, 로드맵):
    // .interceptor(new RetryInterceptor(3))         // 자동 재시도
    // .interceptor(new LoggingInterceptor())         // 요청/응답 로깅
    // .interceptor(new CircuitBreakerInterceptor())  // 서킷 브레이커
    // .telemetry(new MicrometerTelemetry(registry)) // 메트릭
    .build();
```

### 1.5 SDK 배포 전략

| 채널 | 적합 기관 | 장점 | 단점 |
|------|----------|------|------|
| **JAR 직접 배포** (현재) | 소규모 기관, 사내망 격리 | 단순, 버전 고정 | 업그레이드 수동 |
| **내부 Nexus/Artifactory** (권장) | 중간 규모 이상 | Maven/Gradle 표준 의존성 관리 | Nexus 서버 운영 필요 |
| **Maven Central** (장기) | 공개 기관 | 접근성 최고 | 공개 정책 검토 필요 |

**권장 명명 규칙** (Nexus 배포 시):
```xml
<dependency>
    <groupId>kr.go.smes.onepass</groupId>
    <artifactId>onepass-agency-sdk</artifactId>
    <version>1.0.0</version>  <!-- SemVer 준수 -->
</dependency>
```

---

## 2. 자체 SSO 보유 기관 통합 전략

### 2.1 문제 정의

68개 기관 중 상당수는 이미 자체 SSO 시스템을 운영 중입니다:

```
기관 SSO 유형 분류:
┌─────────────────────────────────────────────────────────────┐
│ 유형 A: 독립 계정 시스템       (기관 자체 ID/PW)           ~40%│
│ 유형 B: 기관 자체 OIDC/OAuth2  (Keycloak, Okta 등)         ~25%│
│ 유형 C: SAML 2.0 기반 SSO      (엔터프라이즈 레거시)        ~20%│
│ 유형 D: 드림시큐리티 등 상용 SSO                            ~15%│
└─────────────────────────────────────────────────────────────┘
```

각 유형별로 OnePass와의 통합 전략이 달라야 합니다.

### 2.2 통합 전략 — 3가지 패턴

#### 패턴 A: CAST Token 브릿지 (권장 — 기관 SSO 유지)

기관 SSO를 그대로 유지하면서 OnePass 인증 결과만 수신하는 패턴:

```
사용자 ──► OnePass(Q-Sign) 인증 완료
              │
              ▼
         CAST Token 생성 (Ed25519 서명)
         payload: { qimUserId, agencyCode, authLevel, exp }
              │
              ▼
         기관 Callback URL로 전달 (HTTPS POST)
              │
              ▼
         기관 SSO 세션 생성 (기존 SSO에 OnePass ID 연결)
              │
              ▼
         기관 기존 서비스 이용 (기관 SSO 세션 유지)
```

**구현 방법 (기관 측)**:
```java
// 1. CAST Token 수신 및 검증
@PostMapping("/onepass/callback")
public ResponseEntity<Void> handleOnePassCallback(
        @RequestHeader("X-Cast-Token") String castToken,
        @RequestHeader("X-Agency-Code") String agencyCode) {
    
    // SDK로 CAST Token 검증
    GatewayResponse verification = client.getStatus(agencyCode);
    
    // 2. 기관 자체 SSO 세션에 OnePass qimUserId 바인딩
    String qimUserId = extractQimUserIdFromCastToken(castToken);
    agencySsoSession.bindExternalId("ONEPASS", qimUserId);
    
    // 3. 기관 서비스 리다이렉트
    return ResponseEntity.status(302)
        .location(URI.create("/agency/dashboard"))
        .build();
}
```

#### 패턴 B: SSO 페더레이션 (OIDC Token Exchange)

기관 OIDC 서버가 OnePass를 외부 IdP로 신뢰하는 패턴 (RFC 8693 Token Exchange):

```
사용자 ──► 기관 SSO 로그인 URL
              │
              ▼
         기관 OIDC 서버 → OnePass 신뢰 여부 확인
              │
              ├─ OnePass 인증 완료 → CAST Token 수신
              │
              ▼
         RFC 8693 Token Exchange:
         POST /token
           grant_type=urn:ietf:params:oauth:grant-type:token-exchange
           subject_token={cast_token}
           subject_token_type=urn:ietf:params:oauth:token-type:access_token
           audience={agency_client_id}
              │
              ▼
         기관 OIDC 서버 → 자체 ID Token 발급 (OnePass 신원 기반)
              │
              ▼
         기관 서비스 이용 (기존 OIDC 흐름 그대로)
```

**적합한 경우**: 기관이 Keycloak, Okta 등 표준 OIDC 서버를 운영 중인 경우

#### 패턴 C: Account Linking (회원 전환)

기관 기존 계정과 OnePass 계정을 1:1 연결하는 패턴:

```
최초 연동 시:
  사용자 기관 로그인 → 기관 계정 존재 확인
  OnePass 인증 → qimUserId 획득
  기관 DB: agency_user.onepass_id = qimUserId  (1:1 매핑)

이후 OnePass 로그인 시:
  OnePass 인증 → qimUserId → 기관 DB 조회 → 기관 계정 식별
  → 기관 세션 직접 생성 (별도 인증 불필요)
```

### 2.3 기관 SSO 통합 의사결정 트리

```
기관 SSO 유형은?
│
├─ 자체 OIDC/OAuth2 서버 운영 중
│    └─ → [패턴 B: SSO 페더레이션] (RFC 8693)
│
├─ SAML 2.0 기반 레거시 SSO
│    └─ → [패턴 A: CAST Token 브릿지] + SAML SP에 OnePass 연동
│         (SAML IdP 측 수정 없이 SP 측만 수정)
│
├─ 상용 SSO (드림시큐리티 등)
│    └─ → GUIDE-005 참조 (드림시큐리티 SSO 연동 가이드)
│
└─ 독립 계정 시스템 (자체 ID/PW)
     └─ → [패턴 C: Account Linking]
          (qimUserId를 기관 계정과 DB에서 1:1 매핑)
```

### 2.4 회원 전환 정책 (OnePass 우선 vs 기관 계정 우선)

| 시나리오 | 권장 정책 | 주의사항 |
|---------|---------|---------|
| 기관 계정 없음 + OnePass 최초 인증 | OnePass로 신규 계정 생성 | 기관 서비스 권한 초기 설정 필요 |
| 기관 계정 있음 + OnePass 최초 연동 | 수동 계정 연결 (Account Linking) | 사용자 동의 절차 포함 |
| 기관 계정 있음 + 이미 연동됨 | OnePass 인증 → 기관 세션 자동 생성 | Replay Attack 방지 필수 |
| 기관 계정 비활성화 + OnePass 활성 | 기관 계정 재활성화 또는 접근 거부 | 기관 정책에 따라 결정 |

---

## 3. 기관 유형별 통합 패턴

### 3.1 패턴 분류 (agency-stub 기준)

현재 구현된 4가지 기관 패턴과 SSO 적합도:

| 패턴 | 설명 | SSO 통합 적합도 | 권장 SSO 전략 |
|------|------|:-----------:|------------|
| **Direct** | OnePass에서 직접 기관 서비스 이동 | ⭐⭐⭐⭐⭐ | 패턴 A (CAST Token 브릿지) |
| **Bridge** | 기관 중간 페이지 거쳐 이동 | ⭐⭐⭐⭐ | 패턴 A 또는 C |
| **Apache-Gate** | Apache 앞단 게이트웨이 존재 | ⭐⭐⭐ | 패턴 B (Token Exchange) |
| **SSO** | 기관 자체 SSO 운영 | ⭐⭐ | 패턴 B 또는 C |

### 3.2 드림시큐리티 SSO 통합 (GUIDE-005 연장)

GUIDE-005에서 다룬 내용 외 추가 고려사항:

```
드림시큐리티 SSO + OnePass 이중 인증 시나리오:

Option 1: OnePass 우선 (권장)
  OnePass 인증 완료 → CAST Token → 드림시큐리티 SP로 전달
  드림시큐리티 SP: CAST Token 검증 → 자체 세션 발급
  
Option 2: 드림시큐리티 우선
  드림시큐리티 인증 → 기관 서비스 이용 중
  특정 서비스에서 OnePass 추가 인증 필요 시 → Step-Up 인증
  OnePass 인증 완료 → 기관 세션에 추가 클레임 부여
```

### 3.3 Spring Security 통합 예시 (기관 스프링 기반 서버용)

```java
// SecurityConfig.java — OnePass CAST Token 검증 필터 추가
@Configuration
@EnableWebSecurity
public class AgencySecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, 
                                           CastTokenVerifier castTokenVerifier) throws Exception {
        return http
            .addFilterBefore(
                new OnePassCastTokenFilter(castTokenVerifier),
                UsernamePasswordAuthenticationFilter.class
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/onepass/callback").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}

// OnePassCastTokenFilter.java
public class OnePassCastTokenFilter extends OncePerRequestFilter {
    
    private final CastTokenVerifier verifier;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String castToken = request.getHeader("X-Cast-Token");
        if (castToken != null) {
            try {
                // SDK로 CAST Token 검증 (Ed25519 서명 검증)
                CastTokenClaims claims = verifier.verify(castToken);
                
                // Spring Security Context에 설정
                UsernamePasswordAuthenticationToken auth = 
                    new UsernamePasswordAuthenticationToken(
                        claims.getQimUserId(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ONEPASS_USER"))
                    );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (CastTokenException e) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid CAST Token");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

---

## 4. 기술 표준 준수 매핑

### 4.1 SDK가 준수하는 표준

| 기능 | 표준/RFC | 구현 위치 |
|------|---------|----------|
| 인증 키 헤더 | RFC 7235 §2 (Bearer 개념 유사) | `X-Agency-Key` |
| Idempotency-Key | IETF draft-ietf-httpapi-idempotency-key-header | `X-Idempotency-Key` |
| HMAC-SHA256 서명 | RFC 2104 | `HmacSigner` |
| UUID v7 키 생성 | RFC 9562 (UUID v7) | `IdempotencyKeyGenerator` |
| HTTP 메서드 | RFC 9110 §9 | POST/PATCH/GET |

### 4.2 CAST Token 표준 준수

| 항목 | 표준 | 구현 |
|------|------|------|
| 서명 알고리즘 | RFC 8037 (EdDSA), RFC 8032 (Ed25519) | `CastTokenService` |
| 토큰 만료 | RFC 7519 §4.1.4 (exp claim) | 기본 5분 |
| 발급자 검증 | RFC 7519 §4.1.1 (iss claim) | `iss=onepass-ido` |
| 수신자 검증 | RFC 7519 §4.1.3 (aud claim) | `aud={agencyCode}` |

### 4.3 기관 측 CAST Token 검증 코드 참고

```java
// CAST Token 검증 (기관 서버 구현 예시)
// Ed25519 공개키는 OnePass 관리자로부터 수령
public class CastTokenVerifier {
    
    private final PublicKey onepassPublicKey;  // Ed25519 공개키
    
    public CastTokenClaims verify(String castToken) {
        try {
            // 1. JWT 파싱 (nimbus-jose-jwt 권장)
            SignedJWT jwt = SignedJWT.parse(castToken);
            
            // 2. Ed25519 서명 검증
            JWSVerifier verifier = new Ed25519Verifier(
                (OctetKeyPair) JWK.parseFromPEMEncodedObjects(
                    System.getenv("ONEPASS_ED25519_PUBLIC_KEY")));
            if (!jwt.verify(verifier)) {
                throw new CastTokenException("서명 검증 실패");
            }
            
            // 3. 만료 시간 검증
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (new Date().after(claims.getExpirationTime())) {
                throw new CastTokenException("토큰 만료");
            }
            
            // 4. 발급자/수신자 검증
            if (!"onepass-ido".equals(claims.getIssuer())) {
                throw new CastTokenException("발급자 불일치");
            }
            
            return CastTokenClaims.of(
                claims.getStringClaim("qimUserId"),
                claims.getStringClaim("agencyCode"),
                claims.getStringClaim("authLevel")
            );
        } catch (ParseException | JOSEException | java.text.ParseException e) {
            throw new CastTokenException("토큰 검증 오류: " + e.getMessage());
        }
    }
}
```

---

## 5. SDK 업그레이드 전략

### 5.1 버전 호환성 정책

```
v1.x → v2.x (Minor): 하위 호환 유지
  - Builder에 새 메서드 추가 (기존 코드 컴파일 영향 없음)
  - 새 헤더/API 경로 추가 (기존 호출 영향 없음)
  
v1.x → v2.0 (Major): 하위 비호환 가능
  - AgencyHttpAdapter 메서드 시그니처 변경 시
  - GatewayResponse 필드 변경 시
  - 마이그레이션 가이드 필수 제공
```

### 5.2 기관 측 SDK 업그레이드 절차

```
1. 릴리즈 노트 확인 (CHANGELOG.md)
2. Staging 환경에서 새 버전 테스트
3. HMAC 서명 관련 변경사항 특별 검증
4. 운영 배포 (Blue-Green 권장)
5. 모니터링 (첫 1시간: 오류율 집중 관찰)
```

### 5.3 Sprint 17 Phase 4 HMAC 강제화 대비

```
현재 (Phase 3): signRequests=false 허용
↓
Phase 4 (예정): IDO_HMAC_SIG_REQUIRED=true 설정 시 강제화
  → X-Internal-Sig 헤더 없는 요청 → 401 거부

Phase 4 전환 체크리스트:
□ 기관별 hmacSecret 발급 완료
□ SDK .signRequests(true) 설정
□ Staging 환경 검증 (signRequests=true + 서버 REQUIRED=true)
□ 모든 기관 업그레이드 확인 후 서버 REQUIRED=true 전환
```

---

## 6. 구현 체크리스트

### 6.1 신규 기관 연동 체크리스트

```
□ 기관 SSO 유형 파악 (섹션 2.3 의사결정 트리 활용)
□ 통합 패턴 선택 (A/B/C)
□ API Key 발급 요청 (OnePass 운영팀)
□ HMAC Secret 발급 요청 (Phase 4 대비)
□ SDK 다운로드 및 의존성 추가
□ AgencyHttpAdapter 구현 (기관 HTTP 인프라에 맞춤)
□ CAST Token 검증 로직 구현 (섹션 4.3 참조)
□ 콜백 URL 등록 (OnePass 관리 포털)
□ K8s Secret 등록 (운영 배포 시)
□ Staging 테스트 완료
□ 운영 배포 및 모니터링
```

### 6.2 자체 SSO 연동 추가 체크리스트

```
□ 기존 SSO 서버 타입 확인 (OIDC/SAML/Custom)
□ 사용자 식별자 매핑 정의 (기관 userId ↔ qimUserId)
□ 계정 연결 UI/UX 설계 (최초 연동 시)
□ 세션 만료 정책 통일 (OnePass 세션 vs 기관 세션)
□ SLO(Single Logout) 연동 (GUIDE-005 참조)
□ 회원 탈퇴 시 연결 해제 처리 로직
□ 테스트 계정으로 전체 플로우 E2E 검증
```

### 6.3 보안 필수 검토 항목

```
□ CAST Token exp 검증 (만료 시간)
□ CAST Token iss 검증 (발급자 = "onepass-ido")
□ CAST Token aud 검증 (수신자 = 자기 기관 코드)
□ Replay Attack 방어 (nonce 또는 jti 클레임 활용)
□ 콜백 URL HTTPS 강제
□ API Key 환경변수 분리 (코드에 하드코딩 금지)
□ HMAC Secret 환경변수 분리
□ 기관 서버 IP 화이트리스트 설정 (OnePass 측에 요청)
```
