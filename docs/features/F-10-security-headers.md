# F-10: 보안 응답 헤더 필터

> **환경변수**: `IDO_SECURITY_HEADERS_ENABLED`  
> **기본값**: `true` (운영 필수)  
> **Spring 프로퍼티**: `ido.security-headers.enabled`  
> **소스**: `idem-hub/src/main/java/kr/go/smes/idem-hub/config/SecurityHeadersFilter.java`

---

## 1. 이 기능은 무엇인가?

모든 HTTP 응답에 **보안 관련 응답 헤더**를 자동으로 삽입하는 `OncePerRequestFilter`입니다.  
XSS, 클릭재킹, MIME 스니핑, 평문 통신 등의 웹 취약점을 HTTP 헤더 레벨에서 차단합니다.

---

## 2. 삽입되는 보안 헤더

| 헤더 | 값 | 방어 대상 |
|------|-----|---------|
| `Content-Security-Policy` | `default-src 'self'; frame-ancestors 'none'` | XSS, 인라인 스크립트 |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | HTTP 다운그레이드 공격 |
| `X-Frame-Options` | `DENY` | 클릭재킹 |
| `X-Content-Type-Options` | `nosniff` | MIME 스니핑 |
| `X-XSS-Protection` | `1; mode=block` | 구형 브라우저 XSS |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Referrer 정보 누출 |
| `Permissions-Policy` | `geolocation=(), microphone=(), camera=()` | 브라우저 권한 남용 |
| `Cache-Control` | `no-store` (API 응답) | 민감 데이터 캐싱 |

---

## 3. 동작 원리

```java
// SecurityHeadersFilter.java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final FeatureFlags featureFlags;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        if (featureFlags.isSecurityHeaders()) {
            res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            res.setHeader("X-Frame-Options", "DENY");
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
            res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            res.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
            // API 응답은 캐시 금지
            if (req.getRequestURI().startsWith("/api/")) {
                res.setHeader("Cache-Control", "no-store");
                res.setHeader("Pragma", "no-cache");
            }
        }
        chain.doFilter(req, res);
    }
}
```

---

## 4. 필터 순서 및 Sprint 17 변경사항

Sprint 17에서 `HmacSignatureFilter` (Order=10)가 추가되었습니다.

| 필터 | `@Order` | 역할 |
|------|---------|------|
| `SecurityHeadersFilter` | `HIGHEST_PRECEDENCE + 1` | 보안 헤더 삽입 (최우선) |
| `HmacSignatureFilter` | `10` | HMAC 서명 검증 (F-26) |
| Spring Security 필터 체인 | 기본 | 인증·인가 |

---

## 5. false 설정 가능한 경우

**API Gateway(Kong, Envoy) 등 상위 레이어에서 이미 보안 헤더를 삽입**하는 경우에만 false 허용:
```bash
# 상위 API Gateway가 보안 헤더를 담당하는 경우
IDO_SECURITY_HEADERS_ENABLED=false
```

> ⚠️ **확인 없이 false로 설정하면 OWASP ZAP, Nessus 등 보안 스캐너에서 취약점이 감지됩니다.**

---

## 6. 헤더 검증 방법

```bash
# 응답 헤더 확인
curl -I https://api.onepass.go.kr/api/v1/auth/token | grep -i \
  -e "strict-transport" \
  -e "x-frame" \
  -e "x-content-type" \
  -e "content-security" \
  -e "referrer-policy"

# OWASP 헤더 점수 확인 (외부 서비스)
# https://securityheaders.com/?q=https://api.onepass.go.kr
```

---

## 7. Content-Security-Policy 커스터마이징

Actuator UI 또는 Swagger UI 활성화 시 CSP 정책 완화 필요:

```yaml
# application.yml
ido:
  security-headers:
    enabled: true
    csp: "default-src 'self'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
```

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-26 HMAC 서명](F-26-hmac-sig.md) | 같은 필터 체인, Order 상위에서 먼저 실행 |
| [F-05 OTel 추적](F-05-auth-tracing.md) | 보안 헤더 필터도 Span 생성 가능 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/kr/go/smes/idem-hub/config/FeatureFlags.java)
- [OWASP Secure Headers Project](https://owasp.org/www-project-secure-headers/)
