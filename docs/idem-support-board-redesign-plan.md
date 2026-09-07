# OnePass Support 게시판(고객센터) 재설계 보완 플랜

> 작성일: 2026-05-26
> 기준 기획안: `05.게시판.zip` (문의하기 목록/글쓰기/상세(타인글)/상세(본인글)/상세(관리자)/자주묻는질문 6장)
> 적용 범위: `idem-support` 모듈(백엔드) + `idem-console`(프론트엔드 신규 영역)

---

## 0. 핵심 전제 (사용자 지시 + 재해석)

### 0.1 두 단계 사용자 지시 정리

- **1차 지시 (`초기`)**: "idem-support는 SSO/IM과 별개 서비스. 사용자가 서비스를 이용할 때 로그인에 대해서는 신경을 쓰지 않아도 될 거 같아."
- **2차 지시 (`보완`)**: "이미지를 자세히 보면 로그인 후 진행하는 흐름이다. idem-console를 통해서 접근하는 서비스이고 로그인된 사용자를 어떻게 알아보는가? 유관기관처럼 클라이언트 등록이 필요한가? 익명도 함께 정리해줘."

### 0.2 통합 해석 (이번 문서의 작업 가설)

1. **`idem-support`는 SSO/IM과 "프로세스/서비스 경계"는 분리**되지만, **"신원 신뢰 기반"은 idem-console가 이미 보유한 Keycloak JWT를 그대로 받아 검증**한다. → 유관기관처럼 OIDC 브로커링이나 새 client_secret을 등록하지 **않는다**. 같은 Realm의 일반 백엔드 모듈로만 등록한다 (자세한 근거는 §2 참조).
2. **사용자 동작은 "로그인을 강요하지 않는다"**. 익명 사용자도 Q&A 작성/조회와 FAQ 조회가 가능하다.
3. **로그인 사용자가 와 있으면 "부드럽게 인지"한다**.
   - 글쓰기 시 작성자/이메일 자동 prefill
   - 마이페이지 "내 문의" 자동 매핑 (회원 ID 기반)
   - 비공개 글: 본인 확인을 "로그인 매칭" 또는 "비밀번호" 둘 중 빠른 쪽으로
4. **CS 백오피스(관리자)는 별도 게이트** — 기존 `X-CS-Agent-*` 헤더 모델 유지 (장기적으로 별도 Keycloak realm/client로 전환).
5. 기획 이미지의 화면명("게시판")에 얽매이지 말고 **전체 구성 의도**에 맞춘다.

> 💡 "로그인에 신경 쓰지 않아도 된다"는 **"로그인을 필수로 요구하지 않는다"**로 해석한다. "로그인 정보가 있어도 무시한다"는 아니다. 이미지에 로그인 상태가 분명히 나오고, 글쓰기에 작성자 자동 prefill이 가능해야 UX가 자연스럽기 때문이다.

---

## 1. 인증 모델 — 로그인 인식과 익명 처리 (2차 지시 보강 ⭐ 신설)

> 이 장은 "로그인된 사용자를 idem-support가 어떻게 알아보는가?" 와 "익명 사용자는 어떻게 다루는가?" 를 정리한다. 본 문서에서 가장 핵심적인 결정 영역.

### 1.1 결론 한 줄

**idem-support는 idem-console와 "같은 Keycloak realm을 공유하는 일반 백엔드 모듈"로 등록한다. 유관기관 OIDC 브로커링(ADR-2026-004) 패턴이 아니라, ido / q-im 과 동일한 "내부 마이크로서비스 + JWT Resource Server" 패턴을 따른다.**

### 1.2 왜 "유관기관 클라이언트 등록" 패턴이 아닌가?

현재 코드베이스에는 두 가지 인증 패턴이 공존한다:

| 패턴 | 대상 | 정체성 발급 | 정체성 검증 |
|---|---|---|---|
| **A. 내부 마이크로서비스** | `ido`, `q-im`, `q-sign`, `idem-agent`, (신규) `idem-support` | onepass Keycloak realm이 직접 발급한 JWT (access_token) | `KeycloakJwksVerifier` 등으로 같은 realm의 JWKS 서명 검증 |
| **B. 유관기관 OIDC 브로커링** (ADR-2026-004) | 자체 SSO를 가진 외부 기관 (벤처24, 소상365 등) | onepass Keycloak이 외부 기관 SSO를 IdP로 브로커링 후 자체 토큰 발급 | OnePass 게이트웨이를 통한 핸드오프 티켓 |

`idem-support`는 onepass 플랫폼의 **내장 모듈**이고 idem-console에서 직접 호출하므로 **패턴 A**가 자연스럽다. 패턴 B를 쓰면:
- 별도 client_id/client_secret 발급 필요 (운영 부담)
- IdO를 거치는 핸드오프 티켓 사이클 필요 (불필요한 hop)
- AgencyMeta 등록 필요 (기관도 아닌데)

반대로 패턴 A는:
- idem-console는 **이미 가지고 있는** Keycloak JWT (`Authorization: Bearer ${accessJwt}`)를 그대로 `/api/v1/support/**`에 보내면 됨
- idem-support는 Spring Security Resource Server + Keycloak JWKS 검증만 추가하면 됨 (ido의 `KeycloakJwksVerifier` 패턴 재사용)

### 1.3 idem-console → idem-support 호출 시퀀스

```
[User Browser]                       [idem-console]                    [Keycloak]              [idem-support :8085]
     │                                    │                              │                            │
     │  1. 로그인 (또는 SSO 자동 로그인)        │                              │                            │
     ├───────────────────────────────────►│                              │                            │
     │                                    │ 2. OIDC code flow / refresh   │                            │
     │                                    ├─────────────────────────────►│                            │
     │                                    │ 3. accessJwt(RS256) 반환       │                            │
     │                                    │◄─────────────────────────────┤                            │
     │                                    │  store.app.user.accessJwt       │                            │
     │                                    │  localStorage[AUTH_TOKEN]       │                            │
     │                                    │                                 │                            │
     │  4. /support/inquiry 접근            │                                 │                            │
     ├───────────────────────────────────►│                                 │                            │
     │                                    │ 5. GET /api/v1/support/qna       │                            │
     │                                    │    + Authorization: Bearer JWT  │                            │
     │                                    ├─────────────────────────────────┼───────────────────────────►│
     │                                    │                                 │                            │ 6. JWT 헤더 추출
     │                                    │                                 │                            │ 7. JWKS 조회(캐시)
     │                                    │                                 │◄───────────────────────────┤    /protocol/openid-connect/certs
     │                                    │                                 ├───────────────────────────►│ 8. RS256 서명 검증
     │                                    │                                 │                            │ 9. claims 추출
     │                                    │                                 │                            │    sub=mbrUuid, email, name, roles
     │                                    │                                 │                            │ 10. SupportRequester 생성
     │                                    │                                 │                            │     (authenticated=true)
     │                                    │ 11. 200 OK + QnA 목록            │                            │
     │                                    │◄─────────────────────────────────┼────────────────────────────┤
     │                                    │                                                              │
     │  12. 같은 화면을 "로그아웃 후" 다시 접근  │                                                              │
     ├───────────────────────────────────►│                                                              │
     │                                    │ 13. GET /api/v1/support/qna       (헤더 없음)                  │
     │                                    ├──────────────────────────────────────────────────────────────►│
     │                                    │                                                              │ 14. 헤더 없음 → 익명 처리
     │                                    │                                                              │     (authenticated=false)
     │                                    │ 15. 200 OK + 공개글 목록만        │                            │
     │                                    │◄──────────────────────────────────────────────────────────────┤
```

### 1.4 "같은 realm의 일반 모듈"로 등록한다는 것의 구체적 의미

#### 1.4.1 Keycloak 측 설정 (운영 작업)

Keycloak Admin Console 또는 IaC 스크립트:

```
realm: onepass
client_id: idem-console (이미 존재)
  - client_type: public (SPA)
  - valid_redirect_uris: https://onepass.go.kr/*
  - audience: idem-console + idem-support(추가)  ← 핵심
```

**"audience 추가"** 하나로 끝난다. idem-console의 토큰에 `aud: [idem-console, idem-support]` 클레임이 들어가도록 client scope에 audience mapper 1줄만 추가:

```json
{
  "name": "idem-support-audience",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-audience-mapper",
  "config": {
    "included.client.audience": "idem-support",
    "id.token.claim": "false",
    "access.token.claim": "true"
  }
}
```

새 client_secret 발급 **불필요**. idem-support는 검증만 하면 되므로 confidential client가 아니다.

#### 1.4.2 idem-support 측 설정

`application.yml`에 1블록 추가:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER:https://keycloak.onepass.go.kr/realms/onepass}
          jwk-set-uri: ${KEYCLOAK_JWKS:https://keycloak.onepass.go.kr/realms/onepass/protocol/openid-connect/certs}
          # audience 검증 (이중 안전장치)
          audiences: idem-support
```

`build.gradle.kts`에 의존성 2개 추가:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
```

Security 설정 클래스 1개 (`SupportSecurityConfig.java`):

```java
@Configuration
@EnableWebSecurity
public class SupportSecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 공개 API: 익명 허용
                .requestMatchers(HttpMethod.GET, "/api/v1/support/faq/**", "/api/v1/support/categories").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/support/qna", "/api/v1/support/qna/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/support/qna", "/api/v1/support/qna/*/verify-password").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/v1/support/qna/*").permitAll()
                // 관리자 API: CS 헤더 게이트는 인터셉터에서 (현행 유지)
                .requestMatchers("/api/v1/admin/support/**").permitAll()
                .anyRequest().authenticated()
            )
            // ★ 핵심: Bearer 토큰이 있으면 검증, 없으면 익명 통과
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(supportJwtConverter()))
                .authenticationEntryPoint((req, res, e) -> {
                    // 토큰 "있는데" 잘못된 경우만 401, "없으면" anonymous로 통과
                    if (req.getHeader("Authorization") != null) {
                        res.sendError(401, "INVALID_TOKEN");
                    }
                })
            )
            .anonymous(a -> a.principal("anonymousUser"))
            .build();
    }

    @Bean
    JwtAuthenticationConverter supportJwtConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setPrincipalClaimName("sub");  // mbrUuid
        c.setJwtGrantedAuthoritiesConverter(jwt -> {
            // realm_access.roles → SimpleGrantedAuthority("ROLE_" + role)
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            List<String> roles = realmAccess == null ? List.of()
                    : (List<String>) realmAccess.getOrDefault("roles", List.of());
            return roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                    .collect(toList());
        });
        return c;
    }
}
```

#### 1.4.3 SupportRequester 재정의

현재 `SupportRequester` 는 헤더(`X-User-Id`/`X-User-Role`)에서 신원을 받는다. 이걸 Spring Security 컨텍스트에서 받도록 바꾼다:

```java
public record SupportRequester(
    String userId,         // JWT.sub  (= mbrUuid). 익명이면 null
    String displayName,    // JWT.name 또는 preferred_username
    String email,          // JWT.email
    boolean authenticated,
    boolean admin
) {
    public static SupportRequester fromAuthentication(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return new SupportRequester(null, null, null, false, false);
        }
        Jwt jwt = (Jwt) auth.getPrincipal();
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_SUPPORT_ADMIN"));
        return new SupportRequester(
            jwt.getSubject(),
            jwt.getClaimAsString("name"),
            jwt.getClaimAsString("email"),
            true,
            admin
        );
    }
}
```

컨트롤러에서는:

```java
@GetMapping
public PageResponse<QnaSummary> list(
    Authentication authentication,
    @RequestParam ...
) {
    SupportRequester requester = SupportRequester.fromAuthentication(authentication);
    return qnaService.listForUser(requester, ...);
}
```

→ 더 이상 idem-console가 `X-User-Id`/`X-User-Role` 헤더를 직접 만들지 않는다. **Keycloak JWT 하나로 모든 신원이 흘러간다.**

### 1.5 익명 사용자 처리 정책 (구체)

| 시나리오 | idem-console 동작 | idem-support 동작 | DB 처리 |
|---|---|---|---|
| **A. 비로그인 사용자가 FAQ 조회** | Authorization 헤더 없이 호출 | Security가 anonymous 통과 → public API matcher 적용 → 200 | (조회만, 변경 없음) |
| **B. 비로그인 사용자가 Q&A 목록 조회** | 헤더 없이 호출 | anonymous + repo 쿼리: `secret_yn='N'`만 반환 | (조회만) |
| **C. 비로그인 사용자가 공개 Q&A 작성** | `POST /qna` (헤더 없음, body에 writerName/password/email) | requester.authenticated=false → writer_user_id=NULL, writer_nm/email은 폼 입력값 사용 | `writer_user_id=NULL`, `writer_nm=홍길동`, `writer_pwd_hash=BCrypt(pwd)` |
| **D. 비로그인 사용자가 비공개 Q&A 작성** | 동일 (비공개 체크) | 동일 (anonymous도 비공개 가능) | `secret_yn='Y'`, 나머지 동일 |
| **E. 비로그인 사용자가 자기 비공개 글 재조회** | 비밀번호 모달 → `POST /qna/{id}/verify-password` → 응답 토큰 sessionStorage 저장 → `GET /qna/{id}` with `X-Qna-Access-Token` | 비밀번호 BCrypt 검증 + Rate limit → HMAC 토큰 발급 → 토큰 검증 후 본문 응답 | `qna_access_attempt` 기록 |
| **F. 로그인 사용자가 FAQ 조회** | Bearer JWT 자동 부착 | JWT 검증 OK → requester.authenticated=true → public matcher (동일 응답) | (조회만) |
| **G. 로그인 사용자가 Q&A 목록 조회** | Bearer JWT 자동 부착 | requester.authenticated=true → repo 쿼리: `secret_yn='N' OR writer_user_id=:mbrUuid` | (조회만) |
| **H. 로그인 사용자가 Q&A 작성** | 폼 화면에 prefill (writerName=JWT.name, email=JWT.email). 비밀번호 필드는 UX 일관성을 위해 표시하되 **선택**. | requester.authenticated=true → `writer_user_id=jwt.sub`, writer_nm/email은 폼 입력값(편집 가능). 비밀번호 필드가 비어있으면 자동 생성된 임의 토큰을 해시 저장(차후 분실 시 로그인으로만 본인확인) | `writer_user_id=mbrUuid`, `writer_pwd_hash=BCrypt(랜덤 또는 사용자입력)` |
| **I. 로그인 사용자가 자기 글 재조회** | Bearer JWT 부착, 비밀번호 모달 없음 | requester.authenticated=true && `writer_user_id == jwt.sub` → 본인글로 즉시 인식 → 비밀번호 검증 스킵 | (조회만) |
| **J. 로그인 사용자가 자기 글 수정** | Bearer JWT + PUT /qna/{id} | 동일 본인확인 → 수정 허용 | `last_updusr_id=jwt.sub` |
| **K. 로그인 사용자가 "내 문의" 조회** | `GET /api/v1/support/qna/mine` | requester.authenticated 필수 → `writer_user_id=:mbrUuid` 쿼리 | (조회만) |
| **L. 로그인 사용자가 익명으로 작성하고 싶다고 선택** | 폼에 "익명으로 작성" 체크박스 (Phase 1.5) | 체크 시 `writer_user_id=NULL` 강제, writer_nm은 사용자 입력 | 시나리오 C와 동일 |
| **M. CS 관리자(`SUPPORT_ADMIN` role)** | 별도 CS 백오피스 화면 (이번 범위 외) | `X-CS-Agent-*` 헤더 게이트 유지 (현행). 추후 Keycloak `realm-management` role 또는 별도 realm으로 전환 | 현행 유지 |

### 1.6 정책 Q&A — 흔히 묻는 질문 미리 답하기

**Q1. "FAQ는 로그인 상태 체크해야 하나?"**
→ **하지 않는다.** FAQ는 모든 사용자에게 동일 응답. `permitAll()`. 다만 통계용으로 "로그인 사용자의 FAQ 조회수" 같은 분석이 필요하면 JWT가 있을 때만 별도 audit 이벤트 발행.

**Q2. "Q&A 목록은?"**
→ **항상 200 응답.** 단 결과 집합이 다르다. 비로그인은 `secret_yn='N'`만, 로그인은 거기에 `writer_user_id=mbrUuid`인 비공개 자기 글까지 포함.

**Q3. "비공개 글에 비밀번호 모델이 있는데, 로그인 사용자도 비밀번호 입력해야 하나?"**
→ **아니다.** 로그인 사용자가 자기 글이면 (`writer_user_id == jwt.sub`) 비밀번호 단계 스킵. 비밀번호는 "로그인이 없을 때의 본인확인 fallback"으로 동작한다.

**Q4. "로그인 사용자가 비밀번호 입력 안 했을 때 그 글을 비회원이 (다른 기기에서) 본다면?"**
→ 본인글이 아니므로 그 비회원에게는 비공개 글로 보임. 본인 외 누구도 못 본다. 다만 로그인 사용자도 본인 글을 "로그아웃 상태로 다시 보고 싶을 때"가 있을 수 있어서, **글 작성 시 비밀번호 필드를 (선택 사항이지만) 표시**한다. 비밀번호를 안 넣고 작성하면 "로그인 안 한 상태로는 이 글을 다시 볼 수 없다"는 안내 문구 노출.

**Q5. "유관기관 SSO 사용자(예: 자체 SSO로 들어온 사용자)도 동일 흐름인가?"**
→ **그렇다.** ADR-2026-004의 OIDC 브로커링을 거치면 결과적으로 onepass realm의 JWT를 발급받게 된다. 따라서 idem-support 입장에서는 "기관 사용자"든 "직접 가입 사용자"든 동일한 JWT 흐름.

**Q6. "JWT 검증이 운영 환경 Keycloak에 매번 RPC 호출하나? 성능은?"**
→ JWKS는 RSA 공개키 셋이라 ido와 동일하게 **Redis TTL 60분 캐시**한다. 첫 요청만 Keycloak에 가고, 이후 1시간은 캐시. JWT 서명 검증 자체는 로컬 CPU 연산(밀리초 이하).

**Q7. "Keycloak 장애 시 게시판이 같이 죽나?"**
→ JWKS는 캐시되어 있어 1시간 버틴다. 그 사이 Keycloak 복구되면 무중단. JWKS 캐시 만료 + Keycloak 다운 동시 발생 시에만 401. 다만 **익명 호출은 영향 없다** (검증 자체를 안 하니까) — 게시판 "읽기"는 계속 가능, "작성"만 일시 영향 (로그인 prefill만 불가, 비로그인 작성은 가능).

**Q8. "내부 마이크로서비스 간 호출(예: idem-support → ido로 회원 정보 조회)은?"**
→ 본 Phase 범위에서는 그런 호출이 없다. 만약 추가되면 ido가 이미 사용하는 "서비스 계정 토큰" 또는 "HMAC 게이트웨이 시그니처" 패턴을 그대로 채택한다. (별도 ADR 필요)

### 1.7 헤더 GNB와 본문 분리 정책 (헤더 인사말 "유상옥님 안녕하세요")

- 헤더의 사용자 인사말/로그아웃 버튼은 **idem-console의 공통 GNB 위젯**이 표시한다 (현재 코드에 이미 있음).
- 게시판 본문(Q&A/FAQ 페이지)은 **헤더 상태와 독립적으로 동작**한다.
- 즉: "로그아웃 상태로 게시판만 접근" 시나리오가 가능해야 한다 (기획 의도 일관성).
- 헤더 위젯이 본문 페이지 권한을 제어하지 **않는다**. 본문은 Spring Security가 단독 결정.

### 1.8 idem-support의 "클라이언트 등록" 여부 최종 답

| 질문 | 답 |
|---|---|
| idem-support가 유관기관처럼 Keycloak 클라이언트로 등록되어야 하나? | **반쯤 그렇다.** 단, OIDC client(confidential)가 아니라 **"resource server / audience"**로만 등록. 즉 `idem-console` 클라이언트가 발급하는 토큰의 audience에 `idem-support`를 추가하는 mapper 1개만 등록. 별도 client_secret 발급 없음. |
| client_secret 관리 / rotation이 필요한가? | **아니오.** confidential client가 아니기 때문에 secret 없음. JWKS 공개키만 사용. |
| AgencyMeta 등록이 필요한가? | **아니오.** 기관이 아니라 내부 모듈. |
| 핸드오프 티켓이 필요한가? | **아니오.** ido를 거치지 않고 idem-console → idem-support 직접 호출. |
| 사용자 식별 키는 무엇? | JWT의 `sub` claim (= `mbrUuid`). 본 DB에서는 `writer_user_id VARCHAR(64)`. |

---

## 2. 기획안 분석 결과 (이미지 6장 종합)

### 2.1 공통 레이아웃

- PC 전용 와이드, 좌측 카드형 사이드바 + 우측 본문
- 좌측 메뉴: `고객 센터` 카드 아래 `문의하기`, `자주묻는 질문` 2개
- 상단 헤더: 로고(`중기 통합회원`) / 사용자 인사말 / `로그아웃`·`고객문의`·`홈페이지돌아가기` 버튼
- 본문 상단 파란 배너: 브레드크럼(`홈 > 고객센터 > 문의하기`) + 페이지 제목
- 상단 안내 박스: "궁금한게 있으신가요? 먼저 자주 묻는 질문을 한번 살펴보세요." / "직접 상담원과 통화도 해보세요! (전화문의 : 000-000-0000)"
- 푸터: `개인정보처리방침`/`이용약관`/`중소기업통합플랫폼`/시스템 장애 문의/메일/주소/대표전화/copyright

> 👉 사용자 인사말("유상옥님 안녕하세요")이 있어 헤더에 로그인 상태 표시가 있지만, **본문 게시판 동작 자체는 비로그인 동작 가능**한 구조다. 헤더는 공통 GNB 위젯, 본문은 익명 가능 흐름으로 분리한다.

### 2.2 문의하기 목록 (이미지 1)

| 컬럼 | 비고 |
|---|---|
| 순번 | 게시글 번호(시퀀스 또는 DESC 인덱싱) |
| 구분 | 카테고리/채널 — `벤처24`, `기타`, `소상365`, `소상24` 등 |
| 제목 | 클릭 시 상세 진입. 비공개 글은 별도 처리(자물쇠/마스킹은 기획에서 미노출이지만 정책 필요) |
| 상태 | `작성중`, `답변완료` (2값 확인) |
| 작성일 | YYYY-MM-DD |
| 작성자 | 한글 이름 + 영문ID 혼재 — 익명/회원 모두 표시명 노출 |

- 검색 영역: `카테고리` 드롭다운 / `제목` 드롭다운(검색 필드 선택?) / `작성자` 드롭다운 + 검색어 입력창
- 상단: `검색결과 17건` / `전체` 필터 / `목록 표시 개수 10개`
- 페이지네이션: `< 이전 1 2 3 4 5 6 7 8 ... 99 다음 >`
- 우측 하단: `문의하기`(글쓰기) 큰 버튼

### 2.3 문의하기 글쓰기 (이미지 2)

| # | 필드 | 필수 | 타입 | placeholder | 비고 |
|---|---|---|---|---|---|
| 1 | 작성자 | ✓ | text | 이름을 입력해주세요 | |
| 2 | 비밀번호 | ✓ | password | 비밀번호를 입력해주세요 | **본인확인용 (게시판 표준)** |
| 3 | 이메일 | ✓ | text@text | 이메일을 입력해주세요 / 직접 입력 | local + domain 분리 |
| 4 | 공개 여부 | ✓ | radio | — | 공개/비공개 (기본 공개) |
| 5 | 문의 유형 | ✓ | select | 문의 유형을 선택해주세요 | 옵션 미공개(기관/주제 결합 가능성) |
| 6 | 제목 | ✓ | text | 문의 내용을 입력해 주세요 | |
| 7 | 내용 | ✓ | textarea | 내용을 입력해 주세요 | |
| 8 | 개인정보 수집·이용 동의 | ✓ | checkbox | 개인정보 수집 및 이용에 동의합니다 | |

- 하단 버튼: 좌측 `목록`, 우측 `저장`
- 첨부파일/SMS·이메일 알림 옵션은 **이번 기획안에 없음** → MVP 제외, Phase 2 옵션

### 2.4 상세(타인글/게스트) (이미지 3)

- 제목, 메타 정보(카테고리·공개·작성일·작성자)
- 본문 박스(읽기 전용)
- 첨부파일/답변 영역/이전·다음/댓글 → **없음**
- 하단 `목록` 버튼만

### 2.5 상세(본인글) (이미지 4)

> ⭐ **§1 인증 모델 보강 후**: "본인글" 판정에는 **2가지 경로**가 공존한다.
> 1. **로그인 사용자** — JWT `sub` 와 `writer_user_id` 일치 → 즉시 수정 버튼 노출 (비밀번호 입력 불필요)
> 2. **익명 사용자** — 비밀번호 검증 후 access token 발급 → 수정 진입
> 즉 본인글 식별은 "JWT 우선, 비밀번호 fallback" 정책이다.

- 타인글 화면과 거의 동일
- **차이점: 하단에 `목록` + `수정` 버튼 추가**
- 삭제 버튼은 캡처에 없음 → MVP에서는 본인글 **수정만 허용**, 삭제는 관리자 권한으로 한정 (또는 Phase 2)
- "본인글" 판정은 **(1) JWT `sub` 일치 → 즉시 식별**, **(2) JWT 없으면 비밀번호 인증 후 식별** (Hybrid)

### 2.6 상세(관리자) (이미지 5)

- 사용자 상세 화면 + **`답변` 라벨 + textarea(`답변 내용을 입력해 주세요`) + `저장` 버튼**
- 처리상태 토글(처리중 ↔ 답변완료) UI는 캡처에 명시 없음 → 답변 등록 시 자동 `답변완료` 전이로 단순화
- 작성자 정보 마스킹 없음, 평문 노출 (관리자 권한 전제)
- 비공개 글 처리도 관리자에게는 전부 보임

### 2.7 자주묻는 질문 (이미지 6)

- 검색: `카테고리` 드롭다운 / `제목` 드롭다운 + 검색창 + 돋보기
- 카테고리 가로 탭: `전체`, `라벨01~05`
- 우측: `전체` 필터 / `목록 표시 개수 10개`
- 본문: 아코디언 카드 리스트. 각 항목 좌측 `Q` 아이콘(파랑) + 질문 + 펼침 화살표
- 펼친 상태: 본문 카드 안에 `A` 아이콘(빨강) + 답변 텍스트
- 페이지네이션 동일 형태

---

## 3. 현재 구현(`idem-support`) 스냅샷

### 3.1 백엔드 도메인 (요약)

```
src/main/java/kr/go/smes/support/
├── api/
│   ├── QnaController.java                    # /api/v1/support/qna (GET/POST)
│   ├── FaqController.java                    # /api/v1/support/faqs (+ /groups)
│   ├── AdminSupportController.java           # /api/v1/admin/support (CS 백오피스)
│   └── dto/ (Create/Detail/Summary/Answer 등 12개 record)
├── application/
│   ├── QnaService.java                       # 본인글/비밀글 정책 + 익명 처리
│   ├── FaqService.java                       # 그룹별 노출
│   ├── SupportTicketService.java             # CS 티켓 큐
│   ├── SupportRequester / CsRequester        # X-User-* / X-CS-Agent-* 헤더 기반
└── domain/
    ├── QnaPostEntity / QnaPostRepository
    ├── QnaAnswerEntity
    ├── FaqEntity / FaqRepository
    ├── FaqGroupEntity / FaqGroupRepository
    └── SupportTicketEntity / Event / PhoneConsultationEntity
```

### 3.2 현 DB 컬럼 (qna_post)

```sql
qna_id              UUID PK
tenant_id           VARCHAR(64)
agency_id           VARCHAR(64)
qna_ttl             VARCHAR(200)
qna_cn              TEXT
qna_stts_cd         VARCHAR(20)  -- OPEN/ANSWERED/CLOSED
secret_yn           CHAR(1)
anonymous_yn        CHAR(1)
writer_user_id      VARCHAR(64)
anonymous_display_name  VARCHAR(80)
anonymous_contact_email VARCHAR(120)
use_yn / 메타 4컬럼
```

### 3.3 프론트엔드

- **`idem-console/frontend/src/` 트리에 support / faq / inquiry / qna / board / customer 디렉토리 0개**
- 라우트 상수(`src/constants/routes.ts`)에 SUPPORT/FAQ/QNA 키 0건
- API 클라이언트, 컨테이너, 페이지 컴포넌트 **전부 미구현**
- ✅ **단, JWT 인프라는 이미 존재** — `src/api/index.ts` axios request interceptor가 `store.app.user.accessJwt` 또는 `localStorage[AUTH_TOKEN]` 의 토큰을 모든 요청 헤더에 자동 주입함. → **support 추가 작업 시 별도 인증 코드 불필요, 그대로 재사용.**

> 결론: **백엔드는 60% 완성(인증 모듈 미적용), 프론트엔드는 0% (단 JWT 인프라는 100%)**, 그리고 백엔드도 기획안과 **모델/필드/플로우 불일치** 다수.

---

## 4. 갭 분석 (기획안 ↔ 현재 구현)

> ⚠ **§1 인증 모델 보강 반영와 함께 재작성** — "로그인 사용자 인식 + 익명 복존" 정책을 기준으로 갭을 재평가함.

### 4.1 데이터 모델 갭

| # | 항목 | 기획안 | 현재 | 갭/판정 |
|---|---|---|---|---|
| D1 | **비밀번호(본인확인)** | 필수 | 없음 | 🔴 **컬럼 신설 + 해시 저장 필요** |
| D2 | **카테고리/문의유형(=구분)** | 필수, 셀렉트 | `agency_id` 문자열만 | 🟠 카테고리 마스터 테이블 신설 권장 (`inquiry_category`) |
| D3 | **이메일(필수)** | local + domain 분리 | `anonymous_contact_email` (회원작성 시 NULL) | 🟠 모든 글에 필수로 승격 |
| D4 | **공개 여부(공개/비공개)** | radio | `secret_yn` | 🟢 매핑만, 정책 변경(누구나 비공개 가능) |
| D5 | **작성자(표시명)** | 필수 (회원/익명 무관) | `anonymous_display_name`만 익명 시 | 🟠 단일 `writer_name` 컬럼으로 통일 |
| D6 | **개인정보 동의** | 필수 체크 | 없음 | 🟡 `privacy_agreed_yn` + 동의 시각 컬럼 |
| D7 | **순번(번호)** | 표시 | 없음 (UUID 정렬만) | 🟠 `post_no BIGSERIAL` 추가 |
| D8 | **조회수** | 미표시이나 표준 | 없음 | 🟡 `view_cnt` 추가 (Phase 1.5) |
| D9 | **상태 라벨** | `작성중/답변완료` | `OPEN/ANSWERED/CLOSED` | 🟢 라벨 매핑(OPEN→작성중, ANSWERED→답변완료). `CLOSED`는 노출 X |
| D10 | **익명 여부 컬럼** | 불필요(전원 익명 모델) | `anonymous_yn` | 🟢 컬럼은 유지하되 항상 'Y' 또는 제거. **로그인 분리 정책에 맞춰 기본 'Y' 운영** |
| D11 | **첨부파일** | 기획안 없음 | 없음 | ⚪ MVP 제외, Phase 2 |
| D12 | **답변 1:1 관계** | 기획상 답변 1건만 노출 | 1:N (`@OneToMany`) | 🟢 1:N 구조 유지하되 UI는 최신 1건 표시 (운영 유연성 확보) |
| D13 | **FAQ 카테고리(라벨)** | 가로 탭 5개 + 전체 | `faq_group` | 🟢 `faq_group` 활용 가능, 명칭 매핑만 |
| D14 | **FAQ 검색(질문 본문)** | 검색창 + 카테고리 | 그룹 필터만 | 🟠 `faq_qstn_cn ILIKE` 추가 |

### 4.2 API/플로우 갭

| # | 항목 | 기획안 | 현재 | 갭 |
|---|---|---|---|---|
| A1 | 목록 조회 (검색·페이지) | 카테고리/제목/작성자 검색 + 페이지 | 전체 조회만 | 🔴 `Pageable` + 검색 파라미터 |
| A2 | 상세 조회(비공개) | 비밀번호 확인 후 접근 | 로그인 사용자 ID 매칭 | 🔴 비밀번호 검증 API 신설 (`POST /qna/{id}/verify`) 또는 본문 응답 시 비밀번호 헤더 검증 |
| A3 | 본인글 수정 | 수정 버튼 → 폼 → 저장 | API 없음 | 🔴 `PUT /api/v1/support/qna/{id}` + 비밀번호 검증 필수 |
| A4 | 본인글 삭제 | 캡처 없음 | API 없음 | ⚪ MVP 제외 (관리자만 삭제) |
| A5 | 익명 게시 시 헤더 | `X-User-Id` 없이 가능 | 가능(현재 anonymous 처리) | 🟢 유지 |
| A6 | 정책: 비밀글 작성 권한 | 누구나 가능 | 로그인 사용자만 가능 | 🔴 **`validateCreateRule` 제거/수정** |
| A7 | 관리자 답변 | 단일 textarea + 저장 | OK (현재 비밀답변 옵션 포함) | 🟢 익명 비밀답변 제약은 유지 |
| A8 | FAQ 검색 | 텍스트 검색 | 그룹 필터만 | 🟠 `q` 파라미터 |
| A9 | 페이지네이션 응답 형태 | `검색결과 17건` + page nav | `List<>` 단순 배열 | 🔴 `PageResponse<{items, total, page, size}>` |

### 4.3 보안/정책 갭

| # | 항목 | 현황 | 보완 |
|---|---|---|---|
| S1 | 비밀번호 저장 | 없음 | **BCrypt** 해시 저장, length(60) |
| S2 | 비밀번호 재시도 제한 | 없음 | IP + qna_id 기준 5회/10분 (Rate limit), 6회째 1시간 잠금 |
| S3 | 비밀번호 검증 결과 캐싱 | 없음 | 세션/쿠키에 짧은 `qna-access-token` (HMAC, 15분 TTL) 발급해 상세/수정 진입 |
| S4 | XSS | 본문 escape 정책 미정 | 글쓰기 시 `<script>` 등 위험 태그 sanitize (jsoup or OWASP HTML sanitizer) |
| S5 | 개인정보 동의 미보관 | 컬럼 없음 | `privacy_agreed_yn` + `privacy_agreed_at` + 동의 버전 |
| S6 | 익명 작성자 이메일 PII | 평문 저장 | 운영 환경에서 KMS 암호화(envelope) — 단 검색은 평문 필요 시 Phase 2 |
| S7 | 비공개 글 목록 노출 | 정책 미정 | 목록에는 제목 마스킹 X(노출), 본문만 비밀번호 후 공개 |
| S8 | 관리자 식별 | `X-CS-Agent-Role` 헤더 | Phase 2: 별도 Keycloak realm/client (이미 plan에 있음) |

### 4.4 프론트엔드 갭 (현재 0%)

> ✅ **JWT 인증 인프라 추가 구현 불필요** — `src/api/index.ts`의 기존 axios request interceptor가 `store.app.user.accessJwt` / `localStorage[AUTH_TOKEN]`을 읽어 `Authorization: Bearer <token>` 자동 첨부. **support 도메인 호출도 동일 인프라 재사용.**

| 영역 | 필요 산출물 |
|---|---|
| 라우트 | `/support/inquiry`, `/support/inquiry/new`, `/support/inquiry/:id`, `/support/inquiry/:id/edit`, `/support/inquiry/:id/verify`, `/support/inquiry/mine`(Phase 1.5), `/support/faq` |
| 메뉴 | 좌측 사이드 `고객 센터 > 문의하기/자주묻는 질문` 메뉴 추가 |
| 페이지 컴포넌트 | InquiryListPage / InquiryWritePage / InquiryDetailPage / InquiryEditPage / InquiryPasswordPage / InquiryMinePage(Phase 1.5) / FaqPage |
| 공통 컴포넌트 | SupportLayout(헤더배너+사이드+안내박스+푸터), SearchToolbar, StatusBadge, Pagination, CategoryTabs, Accordion |
| API 클라이언트 | `src/api/support/inquiry.ts`, `src/api/support/faq.ts` — **기존 axios instance 그대로 사용** (JWT 자동 주입) |
| 타입 | `src/types/api/support/*.ts` (Inquiry, Faq, FaqGroup, Page) |
| 로그인 상태 접근 | **`useCurrentUser()` 신설** — `useSelector(s => s.app.user)` wrapper. 글쓰기 폼 prefill, 내 글 버튼 노출 판정, 수정 버튼 노출 판정에 사용 |
| 폼/검증 | react-hook-form + zod: 작성자(2~20자), 비밀번호(4~16자, **익명 시만 필수** · 로그인 사용자 생략 가능), 이메일 RFC, 동의 필수 |
| i18n | `public/locales/ko/support.json` + en |
| 상태 라벨 | OPEN→`작성중`, ANSWERED→`답변완료` 매핑 utility |
| 접근성 | KRDS 톤(파랑 #1B6AC1 계열, 라운드 6px, 16px 행간) |

---

## 5. 권장 아키텍처 (수정안)

### 5.1 도메인 모델 변경

```
┌─────────────────────────────────────────────────────────┐
│  inquiry_category (신설)                                 │
│   - category_id PK                                       │
│   - category_cd UNIQUE (VENTURE24/SOSANG24/SOSANG365/ETC)│
│   - category_nm (벤처24/소상24/소상공인365/기타)          │
│   - sort_sn, use_yn, 메타                                │
└─────────────────────────────────────────────────────────┘
                          │ 1:N
                          ▼
┌─────────────────────────────────────────────────────────┐
│  qna_post (재정의)                                       │
│   - qna_id UUID PK                                       │
│   - post_no BIGSERIAL  ★신설 (순번 표시)                  │
│   - category_id FK     ★신설 (구분)                       │
│   - tenant_id, agency_id  유지                            │
│   - qna_ttl VARCHAR(200)                                  │
│   - qna_cn TEXT                                           │
│   - qna_stts_cd OPEN/ANSWERED                             │
│   - secret_yn (공개 여부 - 라벨만 변경)                    │
│   - writer_nm VARCHAR(80)   ★신설 (회원/익명 통합)         │
│   - writer_email VARCHAR(120) ★필수 승격                  │
│   - writer_pwd_hash VARCHAR(60) ★신설 (BCrypt)            │
│   - privacy_agreed_yn CHAR(1) ★신설                       │
│   - privacy_agreed_at TIMESTAMPTZ ★신설                    │
│   - view_cnt INTEGER DEFAULT 0  ★신설 (Phase 1.5)         │
│   - writer_user_id (선택)  → 추후 로그인 연동 시 매핑용      │
│   - anonymous_yn / anonymous_display_name / anonymous_contact_email │
│     → DEPRECATED, 마이그레이션 V4 에서 정리                  │
│   - use_yn, 메타 4컬럼                                     │
└─────────────────────────────────────────────────────────┘
                          │ 1:N
                          ▼
┌─────────────────────────────────────────────────────────┐
│  qna_answer (기존 유지, 변경 없음)                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  qna_access_attempt (신설 - 비밀번호 시도 제한)            │
│   - attempt_id PK                                        │
│   - qna_id FK                                            │
│   - client_ip                                            │
│   - success_yn                                           │
│   - tried_at                                             │
│   - INDEX (qna_id, client_ip, tried_at)                  │
└─────────────────────────────────────────────────────────┘
```

### 5.2 API 재정의 (사용자 영역)

> Spring Security 접근 제어 태그: 🟢 `permitAll` / 🔵 `authenticated` / 🟡 `permitAll — 조건부 인증 입력 수용`

```
# 공개 조회 (로그인 불필요)
🟢 GET    /api/v1/support/categories                        # 문의유형 옵션
🟢 GET    /api/v1/support/qna?categoryCd=&searchField=&q=&page=&size=
                                                              # PageResponse<QnaSummary>
🟡 GET    /api/v1/support/qna/{id}                           # · 공개글 → 즉시 200
                                                              # · 비공개 · JWT.sub==writer → 즉시 200
                                                              # · 비공개 · JWT 있으나 타인 → 403
                                                              # · 비공개 · X-Qna-Access-Token 있으면 200
                                                              # · 비공개 · 앨 아니면 401 PASSWORD_REQUIRED

# 글 등록/수정 (로그인/익명 모두 가능)
🟡 POST   /api/v1/support/qna                                # · JWT 있으면 writer_user_id 자동 설정, password 생략 가능
                                                              # · 익명이면 password BCrypt 필수
🟢 POST   /api/v1/support/qna/{id}/verify-password           # 익명 폴백용 — BCrypt 검증 → HMAC access token
🟡 PUT    /api/v1/support/qna/{id}                           # · JWT.sub==writer → 즉시 허용
                                                              # · 익명이면 X-Qna-Access-Token 필수

# 내 글 조회 (로그인 전용, Phase 1.5)
🔵 GET    /api/v1/support/qna/mine?page=&size=               # JWT.sub 기준 본인 작성 글 목록

# FAQ (로그인 불필요)
🟢 GET    /api/v1/support/faq/categories                     # FAQ 분류 라벨
🟢 GET    /api/v1/support/faq?categoryCd=&q=&page=&size=     # FAQ 검색/페이지
🟢 GET    /api/v1/support/faq/{id}
```

> 접근 제어는 `SupportSecurityConfig.userFilterChain` 의 `authorizeHttpRequests` 에 일괄 정의. 관리자 API(`/api/v1/admin/**`)는 **별도 filter chain** (Phase 2 의 별도 Keycloak realm 에 속함).

### 5.3 API (관리자 - 기존 유지 + 보완)

```
(유지)
GET    /api/v1/admin/support/qna                          # 페이지네이션 추가
POST   /api/v1/admin/support/qna/{id}/answer              # 답변 1건 + 자동 ANSWERED
PUT    /api/v1/admin/support/qna/{id}/answers/{ansId}     # 답변 수정 (Phase 1.5)
DELETE /api/v1/admin/support/qna/{id}                     # 게시글 삭제 (관리자만)

(추가)
GET    /api/v1/admin/support/categories                   # 카테고리 CRUD
POST   /api/v1/admin/support/categories
PUT    /api/v1/admin/support/categories/{id}

GET    /api/v1/admin/support/faqs                         # FAQ CRUD
POST   /api/v1/admin/support/faqs
PUT    /api/v1/admin/support/faqs/{id}
```

### 5.4 상세 접근 플로우 (로그인 · 익명 통합)

```
[사용자] 비공개 글 클릭
   │
   ▼
[GET /qna/{id}]  (axios interceptor가 JWT 자동 첨부)
   │
   ├─ (A) JWT 있고 sub==writer_user_id → 200 OK + 본문 + canEdit=true
   │    └── 수정 버튼 클릭 → [PUT /qna/{id}] (비밀번호 불필요)
   │
   ├─ (B) JWT 있고 타인 비공개 글 → 403 FORBIDDEN → 안내 토스트
   │
   └─ (C) JWT 없음(익명) · 비공개 글 → 401 PASSWORD_REQUIRED
        │
        ▼
   [비밀번호 모달] 입력
        │
        ▼
   [POST /qna/{id}/verify-password]
        │  body: { password }   rate-limit: IP+id 5회/10분
        ├── 200 → { accessToken, expiresIn: 900 } (HMAC, qnaId+exp 서명)
        └── 423 → LOCKED (6회째 잠금)
        │
        ▼
   [GET /qna/{id}] + header `X-Qna-Access-Token`
        │
        ▼ 200 OK + 본문 + canEdit=true
        │
        ▼ (수정 시)
   [PUT /qna/{id}] + same access token
```

### 5.5 응답 페이지네이션 표준

```json
{
  "items": [...],
  "page": 1,
  "size": 10,
  "total": 17,
  "totalPages": 2
}
```

---

## 6. 프론트엔드 설계

### 6.1 라우트

```typescript
// src/constants/routes.ts (추가)
SUPPORT_INQUIRY_LIST:        '/support/inquiry',
SUPPORT_INQUIRY_NEW:         '/support/inquiry/new',
SUPPORT_INQUIRY_DETAIL:      '/support/inquiry/:id',
SUPPORT_INQUIRY_EDIT:        '/support/inquiry/:id/edit',
SUPPORT_INQUIRY_PASSWORD:    '/support/inquiry/:id/verify',
SUPPORT_FAQ:                 '/support/faq',
```

### 6.2 디렉토리 구조

```
src/
├── pages/Support/
│   ├── InquiryList/
│   │   ├── index.tsx
│   │   ├── components/
│   │   │   ├── SearchToolbar.tsx
│   │   │   ├── InquiryTable.tsx
│   │   │   └── StatusBadge.tsx
│   ├── InquiryWrite/
│   │   ├── index.tsx
│   │   └── components/InquiryForm.tsx       # 등록/수정 공용
│   ├── InquiryDetail/
│   │   ├── index.tsx                         # 본인글/타인글/관리자 모두 분기
│   │   └── components/
│   │       ├── PasswordModal.tsx
│   │       └── AnswerSection.tsx
│   └── Faq/
│       ├── index.tsx
│       └── components/
│           ├── CategoryTabs.tsx
│           └── FaqAccordion.tsx
├── container/Support/
│   └── SupportLayout.tsx                     # 사이드 + 헤더 배너 + 안내박스
├── api/support/
│   ├── inquiry.ts
│   ├── faq.ts
│   └── categories.ts
├── types/api/support/
│   ├── inquiry.ts
│   ├── faq.ts
│   ├── category.ts
│   └── page.ts
├── hooks/support/
│   ├── useInquiryList.ts        # SWR/react-query
│   ├── useInquiryDetail.ts
│   ├── useFaqList.ts
│   ├── useQnaAccessToken.ts     # sessionStorage 관리 (익명 폴백)
│   └── useCurrentUser.ts        # ⭐ 신규 — useSelector(s => s.app.user) wrapper
└── utils/support/
    ├── statusLabel.ts            # OPEN→작성중
    └── maskEmail.ts
```

### 6.3 디자인 시스템 토큰 (기획안 추출)

```
Primary:        #1B6AC1   (헤더 배너, 주요 버튼)
Primary-hover:  #155595
Bg:             #F5F7FA   (페이지 배경)
Card:           #FFFFFF
Border:         #E5E8ED
Text-primary:   #1A1A1A
Text-secondary: #6B7280
Status-open:    #F59E0B   (작성중 - 주황)
Status-answered:#10B981   (답변완료 - 녹색)
Q icon (FAQ):   #1B6AC1
A icon (FAQ):   #DC2626
Radius:         6px (카드), 4px (입력), 9999px (badge)
```

### 6.4 폼 검증 (zod)

```typescript
// 로그인 여부에 따라 password 필수 여부를 동적으로 조정
const makeInquirySchema = (isLoggedIn: boolean) => z.object({
  writerName: z.string().min(2, '이름은 2자 이상').max(20),
  password: isLoggedIn
    ? z.string().max(16).optional()                                  // 로그인: 생략 가능
    : z.string().min(4, '비밀번호 4~16자').max(16)              // 익명: 필수
        .regex(/^[A-Za-z0-9!@#$%^&*]+$/, '특수문자 일부만 허용'),
  emailLocal: z.string().min(1).max(64),
  emailDomain: z.string().min(1).max(64),
  secret: z.boolean(),
  categoryCd: z.string().min(1, '문의 유형을 선택해주세요'),
  title: z.string().min(2).max(200),
  content: z.string().min(10).max(10000),
  privacyAgreed: z.literal(true, { errorMap: () => ({ message: '개인정보 수집·이용에 동의해주세요' }) }),
});

// 사용 예
const { user } = useCurrentUser();
const schema = useMemo(() => makeInquirySchema(!!user?.accessJwt), [user]);
```

### 6.5 로그인 상태 훅 (`useCurrentUser`)

```typescript
// src/hooks/support/useCurrentUser.ts
import { useSelector } from 'react-redux';

export function useCurrentUser() {
  const user = useSelector((state: RootState) => state.app.user);
  const isLoggedIn = !!user?.accessJwt;
  return {
    user,
    isLoggedIn,
    userId: user?.userId,
    displayName: user?.userName ?? user?.userId,
    email: user?.email,
  };
}
```

**사용 지점:**
- `InquiryWrite`: 로그인 시 `writerName`/`emailLocal`/`emailDomain` prefill + readonly
- `InquiryDetail`: `user.userId === inquiry.writerUserId` 때 수정 버튼 노출
- `InquiryList`: 로그인 시 "내 글" 버튼 노출 (Phase 1.5 mine 페이지 입구)

---

## 7. 작업 분할 (Phase별)

### Phase 1 — MVP (사용자 게시판 정공법)
**목표: 기획안 6개 화면을 그대로 동작하게**

#### Backend (`idem-support`)
- [ ] **B-01**: `V4__redesign_qna_for_board.sql` 마이그레이션
  - `inquiry_category` 테이블 생성 + 시드(`VENTURE24`/`SOSANG24`/`SOSANG365`/`ETC`)
  - `qna_post`에 `post_no BIGSERIAL`, `category_id FK`, `writer_nm`, `writer_email NOT NULL`, `writer_pwd_hash`, `privacy_agreed_yn`, `privacy_agreed_at` 추가
  - 기존 `anonymous_*` 컬럼은 유지하되 deprecated 주석
  - `qna_access_attempt` 테이블 생성
- [ ] **B-02**: `InquiryCategoryEntity`/`Repository` 추가
- [ ] **B-03**: `QnaPostEntity` 필드 추가, `CreateQnaRequest` 8필드로 재정의
- [ ] **B-04**: `QnaService.create()` 정책 변경 — 누구나 비공개 가능, 비밀번호 BCrypt 해시 저장, 동의 검증
- [ ] **B-05**: `QnaPasswordVerifyService` 신설 — BCrypt 검증 + Rate limit + HMAC access token 발급
- [ ] **B-06**: `QnaController` 엔드포인트 보강 (`/verify-password`, `PUT /{id}`, `GET /categories`)
- [ ] **B-07**: 목록 페이지네이션 + 검색 (`categoryCd`, `searchField` ∈ {title, writerNm}, `q`, `page`, `size`) — JPA `Specification` or `@Query` countQuery
- [ ] **B-08**: 상세 응답 — `secret=true && no access token`이면 401 + `error.code=E-PASSWORD-REQUIRED`
- [ ] **B-09**: FAQ 검색 보강 (`q`, `page`, `size`)
- [ ] **B-10**: `SupportRequester` 단순화 — 본인글 식별은 비밀번호로만 (서버사이드는 access token으로 판정)
- [ ] **B-11**: README 정책 갱신 (로그인 의존 제거)
- [ ] **B-12**: 단위 + 통합 테스트 (`QnaServiceIntegrationTest` 확장: 비밀번호 검증 happy/fail/lockout, 페이지/검색)

#### Frontend (`idem-console/frontend`)
- [ ] **F-01**: 라우트 6개 추가 + 권한 매핑(공개)
- [ ] **F-02**: `SupportLayout` 공통 컨테이너 (사이드/안내/푸터)
- [ ] **F-03**: 사이드 메뉴 `고객 센터 > 문의하기/자주묻는 질문` 추가
- [ ] **F-04**: API 클라이언트 4개 (`inquiry`, `faq`, `categories`, `password-verify`)
- [ ] **F-05**: 타입 정의 (Inquiry/Faq/Category/Page)
- [ ] **F-06**: `InquiryList` 페이지 — 검색바 + 테이블 + 페이지네이션 + `문의하기` 버튼
- [ ] **F-07**: `InquiryWrite` 페이지 — react-hook-form + zod, 카테고리 셀렉트 동적 로딩
- [ ] **F-08**: `InquiryDetail` 페이지 — 메타정보 + 본문 + 답변(있을 때만)
- [ ] **F-09**: `PasswordModal` — 비공개 글 진입 시 호출, sessionStorage에 access token 저장
- [ ] **F-10**: `InquiryEdit` 페이지 — `InquiryForm` 재사용 + access token 헤더 전송
- [ ] **F-11**: `Faq` 페이지 — 카테고리 탭 + 검색 + 아코디언
- [ ] **F-12**: i18n 사전 (`support.json`)
- [ ] **F-13**: 단위 테스트 (jest/RTL) — 폼 검증, 상태 라벨 매핑, 비밀번호 모달 흐름

### Phase 1.5 — 운영성 보강
- [ ] B-13: 조회수 (`view_cnt`) — `GET /qna/{id}` 시 atomic increment
- [ ] B-14: 답변 수정 API (`PUT /admin/support/qna/{id}/answers/{ansId}`)
- [ ] B-15: 카테고리 마스터 관리 API (관리자)
- [ ] B-16: FAQ 관리 API (관리자 CRUD)
- [ ] B-17: `GET /api/v1/support/qna/mine` — 로그인 사용자 본인 글 목록 (`authenticated` 권한)
- [ ] F-14: 본인 알림 — 답변 등록 시 이메일 발송 (SES/SMTP)
- [ ] F-15: 상태/카테고리 인디케이터 색상 — 디자인 토큰화
- [ ] F-16: `InquiryMinePage` — `/support/inquiry/mine` 라우트 + 로그인 시만 메뉴 노출

### Phase 2 — 고도화
- [ ] 첨부파일 (S3 presigned URL)
- [ ] 답변 다건/스레드형
- [ ] PII 암호화 (이메일/이름 envelope encryption)
- [ ] 알림 채널 — SMS / Web Push
- [ ] 감사 로그 확장 (`support_audit_log` 활용)
- [ ] CS 백오피스 별도 Keycloak realm (기존 plan 합류)
- [ ] 다국어 (영문 라벨, MOSS 외)

---

## 8. 마이그레이션 전략

### 8.1 데이터 호환

기존 운영 데이터가 있다면(없으면 패스):
1. `writer_nm` ← `anonymous_display_name`(NOT NULL 이전 데이터는 'UNKNOWN' fallback)
2. `writer_email` ← `anonymous_contact_email` (회원작성은 별도 lookup 필요)
3. `category_id` ← `agency_id` 매핑 시드 (`VENTURE24` 등)
4. `writer_pwd_hash` ← 회원작성 글에는 자동 임시 비밀번호 발급 + 이메일 통지(별도 배치)
5. 컬럼 추가는 nullable로 우선 적용 → 백필 → NOT NULL 승격(V5)

### 8.2 API 호환

기존 사용자 API(`POST /api/v1/support/qna`)는 **DTO 추가 필드를 옵셔널로 잠시 허용**한 뒤, 프론트엔드 배포 완료 후 `@NotBlank` 승격. 두 번에 나눠 릴리즈.

### 8.3 인증 계층 마이그레이션 (⭐ 신규)

1. **Step A (B-00-1)**: Keycloak 관리자에게 audience mapper 추가 요청 → `idem-console` 클라이언트의 client scope에 `idem-support` aud 추가. 핫템 필요 없음, downtime 0.
2. **Step B (B-00-2~5)**: `idem-support`에 Spring Security Resource Server 적용. 이 시점 `X-User-Id` 헤더도 계속 읽을 수 있도록 **dual-read** 옵션 (transition 기간).
3. **Step C (B-06)**: 프론트 코드가 `Authorization` 헤더 전송을 완전히 전환한 뒤 (이미 axios 인터셉터로 자동), `X-User-*` 헤더 읽기 코드 제거.
4. **Step D**: `X-User-*` 헤더 컴포넌트(`SupportRequester.of(...)`) 제거 완료 → `fromAuthentication(Authentication auth)` 단일 경로.

---

## 9. 리스크 / 결정 필요 사항

| # | 항목 | 옵션 | 권장 |
|---|---|---|---|
| R1 | 비밀번호 정책 | (a) 4~16 영문+숫자 (b) 8자 이상 복합 | (a) — 게시판 본인확인용, 정보가치 낮음 |
| R2 | access token 저장 | sessionStorage / httpOnly cookie | httpOnly cookie 권장 (XSS 안전), 단 도메인 동일 정책 필요 |
| R3 | 비밀번호 분실 | 분실 시 절차 없음 vs 이메일 재설정 | Phase 1: 절차 없음(공지문구), Phase 1.5: 이메일 재설정 링크 |
| R4 | 비공개 글 목록 | 제목 노출 vs 마스킹 | **제목 노출** (기획안 캡처 기준 자물쇠/마스킹 없음) |
| R5 | 카테고리 마스터 | enum 코드 / DB 테이블 | DB 테이블(`inquiry_category`) — 추후 관리자 화면에서 CRUD 가능 |
| R6 | 헤더 사용자 인사말 | 로그인 의존 없음 정책과 충돌 | **GNB 위젯 분리** — 게시판 본문은 헤더 상태와 독립 |
| R7 | 푸터 정보 | 기관/연락처 고정 텍스트 | i18n 사전 + 환경 변수로 분리 (배포 환경 별 조정) |
| R8 | 검색 인덱스 | 단순 ILIKE vs Postgres FTS | Phase 1: ILIKE + GIN trigram (`pg_trgm`) Phase 2: FTS |
| R9 | 본인글 삭제 | 캡처에 없음 — MVP 제외 vs 포함 | **MVP 제외**, 관리자 삭제만. Phase 1.5 재검토 |

---

## 10. 완료 정의 (Definition of Done)

### Phase 1 DoD
- [ ] **백엔드 — 인증 계층 (⭐ 신규)**
  - Spring Security Resource Server 동작 (JWKS 검증, `aud` 검증)
  - 유효 JWT → 200, 잘못된/만료 JWT → 401, Authorization 없을 시 → anonymous 처리 + 공개 라우트 200
  - 관리자 경로(`/admin/**`)는 별도 filter chain 유지
  - `SupportRequester.fromAuthentication(...)` 단위 테스트 통과
- [ ] **백엔드**
  - DB 마이그레이션 V4 적용 완료, 모든 테스트 그린
  - 사용자/관리자 API 통합 테스트 추가 (페이지/검색/비밀번호 happy+sad path)
  - 상세 3경로 분기 통합 테스트 (로그인 본인 / 로그인 타인 비공개 / 익명 비밀번호 fallback)
  - OpenAPI 문서(`/swagger-ui.html`)에서 모든 신규 엔드포인트 노출
  - 비밀번호 BCrypt 해시 검증 — 평문 저장 0건
  - Rate limit 동작 확인 (5회 실패 시 423)
- [ ] **프론트엔드**
  - 6개 화면 라우트 동작 (목록/글쓰기/상세/비밀번호모달/본인글수정/FAQ)
  - 로그인 사용자 글쓰기 폼 prefill + 비밀번호 필드 자동 숨김
  - 로그인 사용자가 본인글 상세 진입 시 비밀번호 모달 우회, 수정 버튼 즉시 노출
  - 익명 사용자가 비공개 글 진입 시 비밀번호 모달 정상 동작
  - 모든 폼 zod 검증, 에러 메시지 한글
  - 페이지네이션 + 검색 동작
  - i18n 한/영 사전 완비
  - jest 단위 테스트 ≥ 80% (페이지/훅/유틸 — `useCurrentUser` 포함)
  - actionlint/typecheck/lint 통과
- [ ] **문서**
  - 본 문서(이 파일) 갱신 — 완료 항목 체크
  - `docs/idem-support-cs-backoffice-plan.md` Phase 2 연결성 갱신
  - `idem-support/README.md` 정책 갱신 — "Keycloak JWT 리소스 서버 + 익명 공존"

---

## 11. 작업 순서 추천 (PR 분할)

PR을 작게 쪼개야 리뷰가 빠릅니다.

1. **PR A** (1d): 본 문서 + DB 마이그레이션 V4 + `InquiryCategoryEntity` + seed
2. **PR B0** (1d ⭐ 신규): **Spring Security Resource Server 도입** — `build.gradle.kts` deps + `application.yml` issuer + `SupportSecurityConfig` + `SupportRequester.fromAuthentication` + JwtAuthenticationConverter + 단위 테스트
3. **PR B** (2d): `QnaPostEntity` 확장 + `CreateQnaRequest` 재정의 + 정책 변경 (JWT/익명 분기) + 통합 테스트
4. **PR C** (1d): 비밀번호 검증/토큰 발급 (`QnaPasswordVerifyService`) + Rate limit (익명 폴백 전용)
5. **PR D** (1d): 페이지네이션 + 검색 + FAQ 검색
6. **PR E** (1d): `GET /qna/{id}` 3경로 분기 + `PUT /qna/{id}` 본인글 수정 (JWT 우선 / access token 폴백)
7. **PR F** (2d): 프론트 라우트/레이아웃/메뉴/API 클라이언트 + `useCurrentUser`
8. **PR G** (2d): 프론트 페이지 4개(List/Write/Detail/Edit) + 폼 (로그인 prefill / 익명 비밀번호 모달)
9. **PR H** (1d): 프론트 FAQ + i18n
10. **PR I** (1d): 단위 테스트 보강 + 디자인 토큰 적용 + 접근성 점검
11. **PR J** (0.5d): Phase 1.5 준비 (조회수/카테고리 관리 API 골격 + `GET /qna/mine`)

**총 예상**: 백엔드 6d + 프론트 6d + 테스트/문서 1d = **약 13 영업일 (2.6주)**, 페어 작업 시 1.7주

---

## 12. 비기획 의문점 (사용자 확인 요청)

> 기획안 캡처에서 식별 불가하여 결정이 필요한 항목

### 12.1 ✅ 인증 모델 — 2차 지시(`§1`)로 해결됨

- ~~Q-auth-1: 로그인 사용자 식별 방식~~ → **§1.1 결정**: 같은 Keycloak realm 리소스 서버 + audience mapper
- ~~Q-auth-2: 유관기관 client 등록 필요?~~ → **§1.2 결정**: 불필요. 내부 마이크로서비스 패턴(ido / q-im과 동일)
- ~~Q-auth-3: Q&A/FAQ 접근 시 로그인 체크?~~ → **§1.5 결정**: FAQ/Q&A 목록/등록은 `permitAll`. 비공개 글 상세만 조건부
- ~~Q-auth-4: 익명 사용자도 비밀글 작성 가능?~~ → **§1.6 Q1 결정**: 가능 (비밀번호 + 동의 필수)
- ~~Q-auth-5: 로그인 사용자가 익명으로 작성 가능?~~ → **§1.5 시나리오 L 결정**: Phase 1.5 옵션

### 12.2 여전히 결정 필요

1. **본인글 삭제 가능 여부** — MVP에서 제외 권장. 동의?
2. **첨부파일 필요 여부** — 기획안 없음. Phase 2로 미루는 게 맞나?
3. **답변 알림 채널** — 이메일만 vs SMS+이메일? (이메일이 필수 필드이므로 이메일 우선 권장)
4. **카테고리 옵션의 정식 명칭** — `벤처24/소상24/소상365/기타` 외 추가가 있는지? `소상공인365`로 통일?
5. **상태값** — `작성중/답변완료` 2개로 끝인지, `대기/처리중/답변완료/종결` 4단계로 갈지? (현재 DB는 OPEN/ANSWERED/CLOSED 3단계 → 사용자 UI는 2단계 매핑 권장)
6. **비밀번호 재설정 절차 (익명 폴백용)** — Phase 1에서는 비밀번호 분실 시 신규 등록 안내로 처리해도 되는지?
7. **공지사항 게시판** — 기획안에 없음. 별도 모듈로 가는지? (본 문서 범위 외)
8. **Keycloak audience mapper 적용 권한** — 운영 Keycloak 관리자에게 `idem-support` aud 추가 요청 권한 있나? (B-00-1 차단 요소)
9. **JWT 토큰 만료 처리** — `idem-support` API 호출 중 토큰 만료 시 idem-console의 refresh 흐름이 자동 작동하나? (axios 인터셉터 응답 401 처리 확인 필요)

---

## 13. 참고 자료

- 기획 이미지 6장 (압축 해제 위치: `/tmp/board-images/decoded/`)
- 현재 DB 스키마: `idem-support/src/main/resources/db/migration/V1~V3__*.sql`
- 현재 API: `idem-support/README.md`
- 기존 CS 백오피스 plan: `docs/idem-support-cs-backoffice-plan.md`
- 외부 유관기관 OIDC 브로커링(차별화 비교용): `docs/internal/architecture/ADR-2026-004-internal-sso-integration-pattern.md`
- JWKS 검증 패턴 참조: `idem-hub/src/main/java/kr/go/smes/idem-hub/broker/keycloak/KeycloakJwksVerifier.java`
- 프론트 JWT 인터셉터: `idem-console/frontend/src/api/index.ts`
- KRDS(공공기관 표준 디자인 시스템): https://www.krds.go.kr (참고)
- BCrypt: Spring Security `BCryptPasswordEncoder`
- Spring Security Resource Server: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html

---

## 변경 이력

- **2026-05-26 v1.0** — 최초 작성 (기획안 6장 분석 + 갭 식별 + Phase별 작업 분할)
- **2026-05-26 v1.1** — 2차 사용자 지시 반영: ⭐ **§1 인증 모델 챕터 신설** (Keycloak 동일 realm 리소스 서버 + JWKS RS256 + 익명 공존 정책 + 13개 시나리오 + 8개 Q&A). §4(갭 분석) / §5(아키텍처) / §6(프론트) / §7(Phase) / §8(마이그레이션) / §10(DoD) / §11(PR 분할) / §12(의문점)를 새 인증 모델과 정합. 챕터 번호 재정렬.
