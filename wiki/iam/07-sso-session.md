# SSO 세션 및 등급 관리

> **문서 분류**: IAM / SSO 운영  
> **버전**: v1.0.0  
> **작성일**: 2026-05-19  
> **대상 독자**: 백엔드 개발자, 운영 담당자  
> **상위 문서**: [00-overview.md](./00-overview.md)

---

## 목차

1. [SSO 개요](#1-sso-개요)
2. [SSO On/Off 토글](#2-sso-onoff-토글)
3. [SSO 세션 라이프사이클](#3-sso-세션-라이프사이클)
4. [인증 등급과 SSO 세션 관계](#4-인증-등급과-sso-세션-관계)
5. [SLO (Single Logout)](#5-slo-single-logout)
6. [세션 구현 (Spring Boot + Redis)](#6-세션-구현-spring-boot--redis)
7. [등급별 세션 정책 설정](#7-등급별-세션-정책-설정)
8. [세션 모니터링 및 관리](#8-세션-모니터링-및-관리)
9. [보안 고려사항](#9-보안-고려사항)
10. [운영 이슈 및 대응](#10-운영-이슈-및-대응)

---

## 1. SSO 개요

### 1.1 Any-ID SSO 동작 원리

**SSO(Single Sign-On)**는 사용자가 한 번 인증하면 연계된 여러 서비스에서  
재인증 없이 이용할 수 있는 기능이다.

```
SSO 흐름 예시:
  1. 사용자 → 정부24 로그인 (카카오 간편인증, 2등급)
  2. 사용자 → Q-Net 방문
  3. Q-Net → ptl.anyid.go.kr에 "이 사용자 인증됐나요?" 확인
  4. Any-ID → "네, 2등급으로 인증 중" (SSO 세션 유효)
  5. Q-Net → 재인증 없이 자동 로그인

  SSO 세션 상태:
  ┌──────────────────────────────────────────────────┐
  │  ptl.anyid.go.kr SSO 세션                        │
  │  ci_hash:   ABCdef...                            │
  │  auth_level: 2 (간편인증)                         │
  │  instt:     5000000082 (Q-Net)                   │
  │  exp:       2026-05-19T18:00:00                  │
  │                                                  │
  │  연결된 서비스:                                    │
  │  - 정부24 (로그인 출처)                            │
  │  - Q-Net (SSO 수락)                              │
  └──────────────────────────────────────────────────┘
```

### 1.2 Any-ID SSO vs 기존 기관 SSO

| 비교 | Any-ID SSO | 기관 자체 SSO |
|------|-----------|-------------|
| 세션 저장 위치 | `ptl.anyid.go.kr` 서버 | 기관 서버 |
| 세션 공유 범위 | 참여 기관 전체 | 기관 내 서비스만 |
| 인증 주체 | 행안부 Any-ID | 기관 자체 |
| CI 기반 | ✅ 필수 | 선택 |
| SLO 지원 | ✅ | 기관별 상이 |

---

## 2. SSO On/Off 토글

### 2.1 개요

Any-ID 설치형은 기관이 **SSO 공유 여부를 서비스별로 선택**할 수 있다.

| 모드 | 설명 | 사용 사례 |
|------|------|---------|
| **SSO On** | Any-ID 범정부 SSO에 참여 | 정부24, 민원 서비스 등 일반 서비스 |
| **SSO Off** | 독립 인증 (SSO 세션 공유 안 함) | 금융 거래, 의료, 성인 인증 필요 서비스 |

### 2.2 SSO On 설정

```yaml
# application.yml
anyid:
  sso:
    enabled: true                    # SSO 참여
    session-sharing: true            # 타 기관 SSO 세션 수락
    session-timeout-hours: 8         # SSO 세션 유지 시간
    propagate-logout: true           # 로그아웃 시 타 서비스에도 전파
```

```
Authorization 요청 시 추가 파라미터:
  &prompt=none          ← 이미 SSO 세션 있으면 UI 없이 자동 로그인
  &max_age=28800        ← SSO 세션 최대 수락 나이 (초, 8시간)
```

### 2.3 SSO Off 설정 (항상 재인증)

```yaml
anyid:
  sso:
    enabled: false                   # SSO 미참여
    force-reauth: true               # 방문마다 새 인증 강제
```

```
Authorization 요청 시 추가 파라미터:
  &prompt=login         ← SSO 세션 무시, 항상 로그인 UI 표시
  &acr_values=2         ← 최소 2등급 인증 강제
```

### 2.4 Q-Net 사례: 서비스별 On/Off

Q-Net은 동일 기관 내에서도 서비스 유형에 따라 SSO를 구분 설정한다:

```
Q-Net 서비스별 SSO 정책 예시:
  srvcNo=5000000084 (시험 접수) → SSO Off (금전 거래, 항상 재인증)
  srvcNo=5000000085 (합격 확인) → SSO On  (단순 조회, SSO 허용)
  srvcNo=5000000086 (공고 열람) → SSO On  (비로그인도 가능)
```

---

## 3. SSO 세션 라이프사이클

### 3.1 세션 상태 전이

```
                 ┌──────────┐
                 │  없음     │
                 └────┬─────┘
                      │ 최초 로그인 성공
                      ▼
                 ┌──────────┐
  갱신 요청 ───── │  ACTIVE  │ ─────── 만료 시간 초과 ───▶ EXPIRED
                 └────┬─────┘
                      │ 로그아웃 (SLO)
                      ▼
                 ┌──────────┐
                 │  REVOKED │
                 └──────────┘
```

### 3.2 세션 만료 정책

| 세션 유형 | 기본 만료 | 설명 |
|---------|---------|------|
| **Any-ID SSO 세션** | 8시간 | ptl.anyid.go.kr 서버의 인증 세션 |
| **이용기관 앱 세션** | 30분 | 이용기관 서버의 로그인 세션 |
| **금융인증서 자동로그인** | 30일 | KFTC 자동로그인 쿠키 |
| **OIDC state 임시 저장** | 10분 | Authorization 요청 임시 값 |

### 3.3 세션 갱신 (Refresh)

```
OIDC refresh_token 기반 세션 갱신:

POST https://ptl.anyid.go.kr/oidc/token
  grant_type=refresh_token
  &refresh_token=REFRESH_TOKEN_VALUE
  &client_id=YOUR_CLIENT_ID
  &client_secret=YOUR_CLIENT_SECRET

응답:
  {
    "access_token": "새로운_access_token",
    "id_token":     "새로운_id_token",    ← CI 클레임 포함
    "expires_in":   3600,
    "refresh_token": "새로운_refresh_token"
  }
```

> **주의**: refresh_token은 DB에 암호화 저장하거나 서버 메모리에만 유지.  
> 클라이언트(브라우저)에 노출되면 안 됨.

---

## 4. 인증 등급과 SSO 세션 관계

### 4.1 등급별 SSO 수락 정책

이용기관은 **최소 등급을 설정**하여 낮은 등급 SSO 세션을 거부할 수 있다:

```
예시: min_auth_level=2 설정 기관 (Q-Net, 한국장학재단 등)

상황 1:
  사용자 → 정부24에서 3등급(민간ID 카카오 계정)으로 로그인
  사용자 → Q-Net 방문
  Q-Net → SSO 세션 확인: auth_level=3
  결과 → ❌ 수락 거부 (2등급 미달) → 재인증 요구

상황 2:
  사용자 → 정부24에서 2등급(카카오 간편인증)으로 로그인
  사용자 → Q-Net 방문
  Q-Net → SSO 세션 확인: auth_level=2
  결과 → ✅ SSO 자동 로그인 허용
```

### 4.2 등급 업그레이드 요청

이미 낮은 등급으로 SSO 세션이 있을 때 높은 등급 서비스 접근 시:

```
흐름:
  1. 사용자: 1등급 서비스 접근 시도
  2. 현재 SSO 세션: 2등급 (간편인증)
  3. 서비스: 1등급 필요 → "모바일 신분증으로 재인증" 요구

Authorization 요청:
  &acr_values=1          ← 1등급 이상 요구
  &prompt=login          ← 강제 재인증
  &auth_method=MOBILE_ID ← 모바일 신분증 지정
```

### 4.3 등급별 허용 기능 설계 예시

```java
// AuthorizationService.java — 기능별 등급 체크
@Component
public class AuthorizationService {

    // 기능별 요구 최소 등급 정의
    private static final Map<String, Integer> FEATURE_MIN_LEVEL = Map.of(
        "EXAM_APPLY",       2,  // 시험 접수: 2등급 이상
        "CERT_DOWNLOAD",    2,  // 자격증 다운로드: 2등급 이상
        "HIGH_VALUE_PAY",   1,  // 10만원↑ 결제: 1등급 이상 (모바일 신분증)
        "EXAM_RESULT",      2,  // 합격 결과 조회: 2등급
        "MY_PAGE",          2,  // 마이페이지: 2등급
        "PUBLIC_NOTICE",    0   // 공고 열람: 비로그인 가능
    );

    public void checkPermission(String feature, AnyIdClaims claims) {
        int required = FEATURE_MIN_LEVEL.getOrDefault(feature, 2);
        if (claims.getAuthLevel() < required) {
            throw new InsufficientAuthLevelException(
                feature, required, claims.getAuthLevel()
            );
        }
    }
}

// Controller에서 사용
@GetMapping("/exam/apply")
public ResponseEntity<?> applyExam(
    @AuthenticationPrincipal AnyIdClaims claims
) {
    authService.checkPermission("EXAM_APPLY", claims);
    // ... 시험 접수 로직
}
```

---

## 5. SLO (Single Logout)

### 5.1 SLO 개요

**SLO(Single Logout)**는 사용자가 한 서비스에서 로그아웃할 때  
연결된 **모든 서비스의 세션을 동시에 종료**하는 기능이다.

```
SLO 흐름:
  1. 사용자 → Q-Net 로그아웃 클릭
  2. Q-Net → ptl.anyid.go.kr/oidc/end_session 호출
  3. Any-ID → 해당 CI 기반 SSO 세션 전체 조회
  4. Any-ID → 연결된 서비스들에 back-channel logout 발송
                (정부24, 국민신문고, 건강보험공단 등)
  5. 각 서비스 → 로컬 세션 삭제
  6. Any-ID → 최종 응답 → Q-Net 로그아웃 완료 화면
```

### 5.2 SLO 구현 방식

#### Front-Channel SLO (리다이렉트 기반)

```
이용기관 로그아웃 처리:
  1. 이용기관 로컬 세션 삭제 (쿠키 만료)
  2. Any-ID end_session_endpoint로 리다이렉트:

GET https://ptl.anyid.go.kr/oidc/end_session
  ?id_token_hint=ID_TOKEN          ← 로그인 시 받은 id_token
  &post_logout_redirect_uri=https://your-service.go.kr/logout/complete
  &state=RANDOM_STATE
```

#### Back-Channel SLO (서버 간 직접 호출)

```
Any-ID가 이용기관의 logout endpoint를 직접 호출:

POST https://your-service.go.kr/anyid/back-channel-logout
Content-Type: application/x-www-form-urlencoded

logout_token=SIGNED_JWT_WITH_CI_HASH

(logout_token 클레임):
{
  "iss": "https://ptl.anyid.go.kr",
  "aud": "YOUR_CLIENT_ID",
  "iat": 1716123456,
  "jti": "unique-uuid",           ← 재사용 방지
  "events": {
    "http://schemas.openid.net/event/backchannel-logout": {}
  },
  "sub":  "anyid-uuid-xxxx",
  "sid":  "session-id-xxxx"       ← 종료할 세션 ID
}
```

### 5.3 Back-Channel SLO 수신 컨트롤러

```java
// AnyIdBackChannelLogoutController.java
@RestController
@RequiredArgsConstructor
@Slf4j
public class AnyIdBackChannelLogoutController {

    private final AnyIdSessionService sessionService;
    private final AnyIdJwtVerifier jwtVerifier;
    private final LogoutTokenValidator logoutTokenValidator;

    @PostMapping("/anyid/back-channel-logout")
    public ResponseEntity<Void> handleBackChannelLogout(
        @RequestParam("logout_token") String logoutToken
    ) {
        try {
            // 1. logout_token 서명 검증
            LogoutTokenClaims claims = logoutTokenValidator.validate(logoutToken);

            // 2. jti 재사용 방지 (5분 TTL Redis 저장)
            if (jtiCache.contains(claims.getJti())) {
                log.warn("logout_token 재사용 감지. jti={}", claims.getJti());
                return ResponseEntity.badRequest().build();
            }
            jtiCache.add(claims.getJti(), Duration.ofMinutes(5));

            // 3. 세션 종료 (sid 또는 sub 기반)
            if (claims.getSid() != null) {
                sessionService.revokeBySessionId(claims.getSid());
            } else {
                sessionService.revokeBySubject(claims.getSub());
            }

            log.info("SLO 처리 완료. sub={}, sid={}", claims.getSub(), claims.getSid());
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("SLO 처리 실패: {}", e.getMessage());
            // Any-ID 규격: 실패해도 200 반환 (재시도 방지)
            return ResponseEntity.ok().build();
        }
    }
}
```

### 5.4 로그아웃 컨트롤러

```java
// LogoutController.java
@Controller
@RequiredArgsConstructor
public class LogoutController {

    private final AnyIdSessionService sessionService;
    private final AnyIdProperties properties;

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        @CookieValue(name = "SESSION_ID", required = false) String sessionId,
        HttpServletResponse response
    ) {
        if (sessionId == null) {
            return redirectToHome();
        }

        // 1. 로컬 세션 조회
        AnyIdSession session = sessionService.findById(sessionId).orElse(null);

        // 2. 로컬 세션 삭제
        sessionService.revoke(sessionId);

        // 3. 세션 쿠키 만료
        expireSessionCookie(response);

        // 4. Any-ID SLO 리다이렉트
        if (session != null && session.getIdToken() != null) {
            String sloUrl = UriComponentsBuilder
                .fromHttpUrl(properties.getIssuerUri() + "/oidc/end_session")
                .queryParam("id_token_hint", session.getIdToken())
                .queryParam("post_logout_redirect_uri",
                    properties.getPostLogoutRedirectUri())
                .queryParam("state", generateSecureRandom(16))
                .build().toUriString();

            return ResponseEntity.status(302)
                .header("Location", sloUrl)
                .build();
        }

        return redirectToHome();
    }

    private void expireSessionCookie(HttpServletResponse response) {
        ResponseCookie expired = ResponseCookie.from("SESSION_ID", "")
            .httpOnly(true).secure(true).sameSite("Lax")
            .path("/").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
    }
}
```

---

## 6. 세션 구현 (Spring Boot + Redis)

### 6.1 Redis 기반 세션 서비스

```java
// AnyIdSessionService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class AnyIdSessionService {

    private final RedisTemplate<String, AnyIdSession> redisTemplate;
    private final AnyIdProperties properties;

    private static final String SESSION_PREFIX = "anyid:session:";

    // 세션 생성
    public String createSession(Member member, AnyIdClaims claims) {
        String sessionId = UUID.randomUUID().toString();

        AnyIdSession session = AnyIdSession.builder()
            .id(sessionId)
            .memberId(member.getId())
            .ciHash(member.getCiHash())
            .authMethod(claims.getAuthMethod())
            .authLevel(claims.getAuthLevel())
            .idToken(claims.getIdToken())     // SLO용 id_token 보관
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(
                properties.getSession().getTimeoutMinutes() * 60L
            ))
            .build();

        Duration ttl = Duration.ofMinutes(properties.getSession().getTimeoutMinutes());
        redisTemplate.opsForValue().set(SESSION_PREFIX + sessionId, session, ttl);

        log.debug("세션 생성. sessionId_prefix={}, ttl={}min",
            sessionId.substring(0, 8), ttl.toMinutes());
        return sessionId;
    }

    // 세션 조회
    public Optional<AnyIdSession> findById(String sessionId) {
        AnyIdSession session = redisTemplate.opsForValue()
            .get(SESSION_PREFIX + sessionId);
        return Optional.ofNullable(session);
    }

    // 세션 갱신 (Sliding Window)
    public void refresh(String sessionId) {
        AnyIdSession session = findById(sessionId)
            .orElseThrow(() -> new SessionNotFoundException(sessionId));

        Duration ttl = Duration.ofMinutes(properties.getSession().getTimeoutMinutes());
        redisTemplate.expire(SESSION_PREFIX + sessionId, ttl);
    }

    // 세션 폐기 (로그아웃)
    public void revoke(String sessionId) {
        redisTemplate.delete(SESSION_PREFIX + sessionId);
    }

    // Back-Channel SLO: sid로 세션 종료
    public void revokeBySessionId(String sid) {
        // sid는 Any-ID SSO 세션 ID (이용기관 sessionId와 다름)
        // 매핑 인덱스 필요: anyid:sso_sid:{sid} → local_session_id
        String localSessionId = (String) redisTemplate.opsForValue()
            .get("anyid:sso_sid:" + sid);
        if (localSessionId != null) {
            revoke(localSessionId);
            log.info("SLO로 세션 폐기. sid={}", sid);
        }
    }

    // 만료 세션 정리 (Redis TTL이 자동 처리하지만 명시적 확인)
    public boolean isSessionValid(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(SESSION_PREFIX + sessionId));
    }
}
```

### 6.2 세션 인터셉터

```java
// AnyIdSessionInterceptor.java
@Component
@RequiredArgsConstructor
public class AnyIdSessionInterceptor implements HandlerInterceptor {

    private final AnyIdSessionService sessionService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {
        String sessionId = getCookieValue(request, "SESSION_ID");

        if (sessionId == null || !sessionService.isSessionValid(sessionId)) {
            response.sendRedirect("/login");
            return false;
        }

        // Sliding Window 갱신
        sessionService.refresh(sessionId);

        // 세션 정보를 RequestAttribute에 저장
        AnyIdSession session = sessionService.findById(sessionId).orElseThrow();
        request.setAttribute("anyidSession", session);

        return true;
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
            .filter(c -> name.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }
}
```

---

## 7. 등급별 세션 정책 설정

### 7.1 인증 등급별 세션 시간 차등 적용

```yaml
# application.yml
anyid:
  session:
    # 등급별 세션 만료 시간
    auth-level-policy:
      level-1:
        timeout-minutes: 480    # 8시간 (모바일 신분증 — 높은 신뢰)
        idle-timeout-minutes: 60
      level-2:
        timeout-minutes: 60     # 1시간 (간편인증, 공동/금융인증서)
        idle-timeout-minutes: 30
      level-3:
        timeout-minutes: 30     # 30분 (민간ID — 최소 허용 시)
        idle-timeout-minutes: 15
```

```java
// 등급별 TTL 계산
public Duration calculateSessionTtl(int authLevel) {
    return switch (authLevel) {
        case 1  -> Duration.ofHours(8);
        case 2  -> Duration.ofHours(1);
        case 3  -> Duration.ofMinutes(30);
        default -> Duration.ofMinutes(30);
    };
}
```

### 7.2 민감 기능 접근 시 재인증 요구

```java
// StepUpAuthService.java — 인증 등급 업그레이드
@Service
public class StepUpAuthService {

    // 현재 세션의 인증 등급이 요구 수준 미달 시 재인증 유도
    public String buildStepUpUrl(String requiredMethod, HttpServletRequest request) {
        String state = generateSecureRandom(32);
        saveStateToRedis(state, request.getSession().getId());

        return UriComponentsBuilder
            .fromHttpUrl(anyIdProperties.getIssuerUri() + "/oidc/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", anyIdProperties.getClientId())
            .queryParam("redirect_uri", anyIdProperties.getRedirectUri())
            .queryParam("scope", "openid profile ci")
            .queryParam("state", state)
            .queryParam("nonce", generateSecureRandom(32))
            .queryParam("prompt", "login")             // 강제 재인증
            .queryParam("auth_method", requiredMethod) // 필요 수단 지정
            .queryParam("acr_values", "1")             // 1등급 요구 시
            .build().toUriString();
    }
}
```

---

## 8. 세션 모니터링 및 관리

### 8.1 세션 현황 메트릭

```java
// AnyIdSessionMetrics.java
@Component
@RequiredArgsConstructor
public class AnyIdSessionMetrics {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    // 활성 세션 수 게이지
    @Scheduled(fixedDelay = 60000)
    public void recordActiveSessionCount() {
        Set<String> keys = redisTemplate.keys("anyid:session:*");
        int count = keys != null ? keys.size() : 0;

        Gauge.builder("anyid.session.active", count, Integer::intValue)
            .description("활성 Any-ID 세션 수")
            .register(meterRegistry);
    }

    // 인증수단별 세션 분포
    @Scheduled(fixedDelay = 300000) // 5분마다
    public void recordAuthMethodDistribution() {
        // Redis Scan으로 세션 타입별 집계
        // Prometheus/Grafana로 모니터링
    }
}
```

### 8.2 관리자 세션 관리 API

```java
// AdminSessionController.java (관리자 전용, Role=ADMIN)
@RestController
@RequestMapping("/admin/sessions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSessionController {

    @GetMapping
    public Page<SessionSummary> listSessions(Pageable pageable) {
        return sessionService.findAllActive(pageable);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> forceLogout(@PathVariable String sessionId) {
        sessionService.revoke(sessionId);
        log.warn("관리자 강제 로그아웃. sessionId={}", sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/member/{memberId}/all")
    public ResponseEntity<Void> forceLogoutAllSessions(
        @PathVariable Long memberId
    ) {
        int count = sessionService.revokeAllByMemberId(memberId);
        log.warn("전체 세션 강제 종료. memberId={}, count={}", memberId, count);
        return ResponseEntity.ok().build();
    }
}
```

---

## 9. 보안 고려사항

### 9.1 세션 고정 공격 방어

```java
// 로그인 성공 후 반드시 새 세션 ID 발급 (Session Fixation 방어)
public String createFreshSession(Member member, AnyIdClaims claims) {
    // 이전 세션 삭제 (동일 회원의 중복 로그인 방지 선택)
    sessionService.revokeAllByMemberId(member.getId());

    // 새 세션 ID 생성
    return createSession(member, claims);
}
```

### 9.2 세션 쿠키 보안 속성

```java
ResponseCookie.from("SESSION_ID", sessionId)
    .httpOnly(true)     // JavaScript 접근 차단 (XSS 방어)
    .secure(true)       // HTTPS만 전송 (MITM 방어)
    .sameSite("Lax")    // CSRF 방어 (Strict면 SSO redirect 불가)
    .path("/")
    .maxAge(Duration.ofMinutes(sessionTimeoutMinutes))
    .build();
```

### 9.3 동시 세션 제한

```java
// 동일 회원의 최대 동시 세션 수 제한 (선택 사항)
private static final int MAX_CONCURRENT_SESSIONS = 3;

public String createSession(Member member, AnyIdClaims claims) {
    List<String> existingSessions = sessionIndex.findByMemberId(member.getId());

    // 초과 시 가장 오래된 세션 제거
    if (existingSessions.size() >= MAX_CONCURRENT_SESSIONS) {
        String oldest = existingSessions.get(0);
        sessionService.revoke(oldest);
        log.info("동시 세션 초과로 오래된 세션 제거. memberId={}", member.getId());
    }

    // 새 세션 생성
    return createNewSession(member, claims);
}
```

---

## 10. 운영 이슈 및 대응

### 10.1 Redis 장애 시 세션 처리

```
문제: Redis 장애 → 세션 조회 불가 → 전체 로그아웃 상태

대응:
  1. Redis Sentinel 또는 Cluster 구성으로 고가용성 확보
  2. 장애 시 응급 처치:
     - 세션 유지 불가 → 전체 재로그인 공지
     - Any-ID 재인증으로 빠른 세션 복구 가능

장애 감지 알림 설정:
  Redis 연결 실패 → PagerDuty/Slack 즉시 알림
```

### 10.2 Any-ID 플랫폼 장애 시

```
문제: ptl.anyid.go.kr 장애 → 신규 로그인 불가

대응:
  1. 기존 유효 세션 유지 (Redis 세션은 Any-ID와 독립)
     → 로그인한 사용자는 세션 만료 전까지 정상 이용 가능
  2. 신규 로그인 시도 → "인증 서비스 점검 중" 안내
  3. 긴급 폴백 (정책 결정 필요):
     - 비밀번호 로그인 임시 활성화
     - IP 기반 인증 (내부 서비스)

상태 체크 API:
  GET https://ptl.anyid.go.kr/actuator/health → HTTP 200 확인
  
  @Scheduled(fixedDelay = 30000) // 30초마다
  public void checkAnyIdHealth() {
      // health check 실패 3회 연속 → 알림 발송
  }
```

### 10.3 세션 만료 시 UX 처리

```java
// 세션 만료 감지 필터
public class SessionExpiryFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws Exception {
        String sessionId = getCookieValue(request, "SESSION_ID");

        if (sessionId != null && !sessionService.isSessionValid(sessionId)) {
            // AJAX 요청 → JSON 오류 반환
            if (isAjaxRequest(request)) {
                response.setStatus(401);
                response.setContentType("application/json");
                response.getWriter().write("""
                    {"error": "SESSION_EXPIRED", "message": "로그인이 만료되었습니다."}
                """);
                return;
            }
            // 일반 요청 → 로그인 페이지로 리다이렉트
            response.sendRedirect("/login?reason=session_expired");
            return;
        }

        chain.doFilter(request, response);
    }
}
```

---

## 관련 문서

| 문서 | 링크 |
|------|------|
| Any-ID 전체 개요 | [00-overview.md](./00-overview.md) |
| 설치형 연동 가이드 | [06-install-type-integration.md](./06-install-type-integration.md) |
| CI/DN 브로커링 | [05-ci-dn-brokering.md](./05-ci-dn-brokering.md) |
| 인증 등급 체계 | [00-overview.md#6-인증-등급-체계](./00-overview.md#6-인증-등급-체계) |

---

*최종 수정: 2026-05-19 | 작성: OnePass 플랫폼 개발팀*
