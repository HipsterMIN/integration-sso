# ADR-010: CAST Token (Ed25519) Cross-Agency SSO

| 항목 | 내용 |
|------|------|
| **ID** | ADR-010 |
| **제목** | Ed25519/EdDSA 서명 기반 CAST Token으로 Cross-Agency SSO 구현 |
| **상태** | ✅ Accepted |
| **결정일** | 2026 (Sprint 12) |
| **결정자** | 보안 위원회 + 아키텍처 위원회 |
| **관련 파일** | `ido/sso/CastTokenService.java`, `ido/sso/CastTokenServiceImpl.java`, `ido/sso/CastKeyConfig.java`, `ido/sso/CrossAgencySsoController.java` |

---

## 컨텍스트 (Context)

기관 A에서 인증한 사용자가 기관 B로 이동할 때, 재인증 없이 SSO를 구현해야 한다. 기존 방식의 문제:

- **세션 공유 불가**: 각 기관은 독립 도메인 → 브라우저 쿠키 공유 불가
- **SAML**: 구현 복잡도 높음, 기관 연동 SDK 미지원
- **단순 리다이렉트 토큰**: 위변조 가능, 리플레이 공격 취약

요구사항:
- **단발성 사용**: 토큰 1회 사용 후 무효화
- **서명 검증**: 위변조 불가
- **짧은 TTL**: 10초 이내 만료
- **기관 간 신뢰 체인**: IdO가 서명 → 수신 기관이 검증

---

## 결정 (Decision)

**Ed25519(EdDSA)** 서명 기반 **CAST Token(Cross-Agency Session Transfer Token)**을 채택한다.

### CAST Token 구조

```json
{
  "sub": "qimUserId:abc123",
  "iss": "onepass-ido",
  "aud": "agency:SMBA_001",
  "iat": 1716812345,
  "exp": 1716812355,        // TTL: 10초
  "jti": "uuid-v7-unique",  // 단발성 ID (nonce)
  "cast": {
    "sourceAgency": "SMBA_SRC",
    "targetAgency": "SMBA_001",
    "sessionRef": "fe-session-id"
  }
}
```

### Ed25519 선택 이유

| 알고리즘 | 키 크기 | 서명 크기 | 속도 | 보안 |
|---------|--------|---------|------|------|
| **Ed25519** | 32 bytes | 64 bytes | ★★★★★ | ★★★★★ |
| RSA-2048 | 256 bytes | 256 bytes | ★★☆☆☆ | ★★★★☆ |
| ECDSA-P256 | 32 bytes | 64 bytes | ★★★★☆ | ★★★★☆ |

- **소형 서명**: Redirect URL 파라미터 포함 시 URL 길이 최소화
- **고속 검증**: 기관 측 실시간 검증에 적합
- **안전한 랜덤**: Ed25519는 결정론적 서명 (난수 품질 의존 없음)

### 키 관리 (CastKeyConfig)

```java
@Configuration
public class CastKeyConfig {
    // Ed25519 키 쌍 생성/로드
    // 운영: K8s Secret에서 주입 (PEM 형식)
    // 개발: 자동 생성 (매 기동 시 새 키)
    @Bean
    public KeyPair castKeyPair() { ... }
}
```

### 키 로테이션 전략
```java
// HandoffKeyRotationScheduler.java
// @Scheduled: 매 24시간마다 키 로테이션
// KeyVersionRegistry: 현재 키 + 이전 키 (검증용 2세대 유지)
```

### 사용 흐름

```
1. 기관 A 인증 완료 → IdO에 CAST Token 발급 요청
2. IdO: CAST Token 생성 (Ed25519 서명) + jti DB 저장 (단발성)
3. 기관 A → Redirect → 기관 B (URL에 token 포함)
4. 기관 B → IdO: CAST Token 검증 요청
5. IdO: 서명 검증 + jti 중복 사용 체크 + TTL 검증
6. 검증 성공 → 기관 B 세션 발급
```

---

## 결과 (Consequences)

### 긍정적 효과
- **위변조 불가**: Ed25519 서명 → 개인키 없이 위조 불가능
- **리플레이 방지**: jti 단발성 사용 + 10초 TTL
- **기관 연동 단순**: 기관 측은 IdO의 공개키만 있으면 검증 가능
- **URL 친화적**: 64 bytes 서명 → Base64URL = ~86자 (URL 한도 여유)

### 부정적 효과 / 주의사항
- **시계 동기화**: TTL 10초 → 기관 서버와 IdO 시간 오차 ±5초 이내 필수 (NTP)
- **키 관리**: 개인키 유출 시 전체 CAST 무효화 → K8s Secret + 정기 로테이션 필수
- **단발성 저장**: jti를 Redis에 저장 → TTL 10초 후 자동 삭제

### 포기한 대안
- **SAML Assertion**: XML 기반 복잡도, 기관 SDK 부재
- **OAuth2 Authorization Code**: Redirect 2회 발생 → UX 저하
- **JWT RS256**: 서명 크기 크고 검증 느림

---

## 관련 ADR

- [ADR-006](ADR-006-redis-session-cache.md) — Redis (jti 단발성 저장)
- [ADR-011](ADR-011-hmac-sha256-gateway-auth.md) — HMAC (Gateway 인증, CAST와 병행)
