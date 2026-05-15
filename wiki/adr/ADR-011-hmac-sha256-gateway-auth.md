# ADR-011: HMAC-SHA256 Agency Gateway 인증

| 항목 | 내용 |
|------|------|
| **ID** | ADR-011 |
| **제목** | HMAC-SHA256 상수 시간 비교 기반 Agency Gateway 인증 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2026 (Sprint 13) |
| **결정자** | 보안 위원회 |
| **관련 파일** | `ido/gateway/HmacSignatureFilter.java`, `ido/gateway/AgencyHmacKeyStore.java`, `ido/gateway/AgencyGatewayController.java` |

---

## 컨텍스트 (Context)

기관 시스템이 IdO Gateway를 호출할 때(Inbound), 요청의 무결성·출처 검증이 필요하다.

요구사항:
- 요청 바디 위변조 감지
- 기관별 개별 인증 키 관리
- 타이밍 공격(Timing Attack) 방어
- 리플레이 공격 방어 (Timestamp + Nonce)
- API Key 단순 비교 방식의 보안 취약성 극복

보안 위협 모델 (STRIDE):
- **Spoofing**: 출처 기관 위장 공격 → HMAC으로 방어
- **Tampering**: 요청 바디 변조 → HMAC 서명에 바디 포함
- **Timing Attack**: 서명 비교 시간 차이로 키 유추 → 상수 시간 비교 필수

---

## 결정 (Decision)

**HMAC-SHA256** 서명 + **MessageDigest.isEqual()** 상수 시간 비교를 Agency Gateway 인증 방식으로 채택한다.

### HMAC 서명 생성 (기관 측)

```
서명 대상 = HTTP_METHOD + "\n"
           + REQUEST_URI + "\n"
           + TIMESTAMP + "\n"  // Unix epoch (초)
           + NONCE + "\n"       // UUID v4
           + BODY_SHA256        // 요청 바디 SHA-256 hex
```

```
X-Hmac-Signature: HMAC-SHA256(signTarget, agencySecretKey)
X-Hmac-Timestamp: 1716812345
X-Hmac-Nonce: uuid-v4
```

### HMAC 검증 로직 (HmacSignatureFilter)

```java
// HmacSignatureFilter.java
@Component
public class HmacSignatureFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(...) {
        String receivedSignature = request.getHeader("X-Hmac-Signature");
        String agencyCode = extractAgencyCode(request);

        // 1. Timestamp 유효성 검사 (±5분 허용)
        validateTimestamp(request.getHeader("X-Hmac-Timestamp"));

        // 2. Nonce 중복 검사 (Redis, TTL 10분)
        checkNonce(request.getHeader("X-Hmac-Nonce"), agencyCode);

        // 3. 서명 재계산
        String expectedSignature = computeHmac(
            agencySecretKey,
            method, uri, timestamp, nonce, bodySha256
        );

        // 4. 상수 시간 비교 — 타이밍 공격 방어 핵심
        if (!MessageDigest.isEqual(
                receivedSignature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            sendUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }
}
```

### MessageDigest.isEqual() — 타이밍 공격 방어

```java
// ❌ 위험: String.equals() — 첫 불일치 문자에서 즉시 반환 (타이밍 차이 발생)
if (expected.equals(received)) { ... }

// ✅ 안전: MessageDigest.isEqual() — 항상 전체 바이트 비교 (상수 시간)
if (MessageDigest.isEqual(expected.getBytes(), received.getBytes())) { ... }
```

### 기관별 키 관리 (AgencyHmacKeyStore)

```java
// AgencyHmacKeyStore.java
// DB: ido.agency_meta.hmac_secret (AES-256 암호화 저장)
// 캐시: Redis TTL 60초 (키 로테이션 반영 주기)
// 키 로테이션: HandoffKeyRotationScheduler와 연동
```

### 리플레이 공격 방어

```
Timestamp: 요청 시각 ± 5분 허용 → 오래된 캡처 재사용 불가
Nonce: UUID v4 → Redis에 10분간 저장 → 같은 Nonce 2회 거부
```

---

## 결과 (Consequences)

### 긍정적 효과
- **타이밍 공격 방어**: `MessageDigest.isEqual()` 상수 시간 비교
- **요청 무결성**: 바디 SHA-256 포함으로 중간자 변조 감지
- **리플레이 방어**: Timestamp + Nonce 이중 방어
- **기관별 격리**: 기관마다 다른 HMAC 키 → 한 기관 키 유출이 타 기관에 영향 없음

### 부정적 효과 / 주의사항
- **키 배포 복잡도**: 기관에 HMAC 키 안전 전달 방법 필요 (Out-of-band 전달)
- **시계 동기화**: ±5분 허용 → 기관 서버 NTP 동기화 필수
- **Redis 의존**: Nonce 저장에 Redis 사용 → Redis 장애 시 nonce 검증 불가 (fallback 정책 필요)

### 포기한 대안
- **API Key 단순 비교**: 타이밍 공격 취약, 요청 바디 무결성 미보장
- **mTLS**: 기관별 인증서 관리 복잡도 과도 (68개 기관)
- **OAuth2 Client Credentials**: 기관 시스템 OAuth2 클라이언트 구현 부담

---

## 관련 ADR

- [ADR-006](ADR-006-redis-session-cache.md) — Redis (Nonce 저장)
- [ADR-010](ADR-010-cast-token-cross-agency-sso.md) — CAST Token (SSO, HMAC과 병행)
