# 인증 흐름 개선 플랜 (2026-05-26)

## 1. Keycloak 설정 보완
- [ ] `idem-console` 클라이언트에 audience mapper 추가
- [ ] `idem-support`를 리소스 서버로 등록 (JWKS URI 설정)

## 2. 백엔드 보안 계층 강화
- [ ] Spring Security Resource Server 도입
- [ ] JWT 검증 실패 시 익명 처리 로직 추가
- [ ] 비밀번호 검증 Rate Limit 구현 (5회/10분)

## 3. 프론트엔드 연동 개선
- [ ] axios 인터셉터 JWT 자동 주입 검증
- [ ] 비밀번호 모달 컴포넌트 개발
- [ ] 토큰 만료 시 자동 갱신 로직 추가

## 4. 테스트 시나리오
- [ ] 로그인/익명 혼용 테스트 케이스 작성
- [ ] JWKS 캐싱 동작 검증
- [ ] Rate Limit 동작 테스트

## 5. 상세 구현 가이드
### Keycloak 설정
```json
{
  "protocolMapper": "oidc-audience-mapper",
  "config": {
    "included.client.audience": "idem-support",
    "id.token.claim": "false",
    "access.token.claim": "true"
  }
}
```

### Spring Security 구성
```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) {
  return http
    .oauth2ResourceServer(oauth2 -> oauth2
      .jwt(jwt -> jwt.jwtAuthenticationConverter(supportJwtConverter()))
      .authenticationEntryPoint((req, res, e) -> {
        if (req.getHeader("Authorization") != null) {
          res.sendError(401, "INVALID_TOKEN");
        }
      })
    )
    .build();
}