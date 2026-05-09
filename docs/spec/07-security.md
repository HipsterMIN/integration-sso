# 07. 보안 구현 상세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **전체 보안 완성도**: 93%

---

## 1. 보안 계층 구조

```
Layer 1: 전송 암호화 (TLS 1.3)         ─ 운영 환경 Nginx/LB 레벨
Layer 2: 서비스 간 인증 (내부 서명)       ─ X-Internal-Sig (HMAC-SHA256)
Layer 3: 기관 API 키 인증               ─ X-Agency-Key (SHA-256 검증)
Layer 4: FE 세션 관리                   ─ feSessionId 쿠키 (Redis TTL)
Layer 5: 데이터 암호화                  ─ AES-256-GCM (CI, Handoff Ticket)
Layer 6: 메시지 서명                    ─ HMAC-SHA256 (Webhook, Handoff)
Layer 7: 레이트 리밋                    ─ Redis TPS + 일일 쿼터
Layer 8: 서킷브레이커                   ─ Resilience4j (공급자 단위)
```

---

## 2. 기관 API 키 인증 (HandoffAgencyKeyInterceptor)

### 구현 방식

```java
// SHA-256 해시 비교 (상수시간 — 타이밍 공격 방지)
byte[] requestHash  = sha256(rawApiKey);
byte[] storedHash   = hexDecode(agencyMeta.getApiKeyHash());
boolean valid = MessageDigest.isEqual(requestHash, storedHash);

// agencyMeta는 Redis 캐시 (TTL 60분) 조회
// Cache Miss → ido.agency_meta DB 조회
```

### 적용 경로

- `POST /api/v1/handoff/issue`
- `POST /api/v1/handoff/verify`
- `GET /api/v1/agency/events` (v1.9.3 신규)
- `POST /api/v1/agency/events/{id}/read` (v1.9.3 신규)

---

## 3. Handoff Ticket 암호화

### 암호화 (AES-256-GCM)

```
평문 페이로드 (JSON)
  → AES-256-GCM 암호화 (키: IDO_HANDOFF_AES_KEY)
  → Base64URL 인코딩
  → 암호화된 페이로드 저장 (ido.handoff_ticket.encrypted_payload)
```

### 서명 (HMAC-SHA256)

```
암호화된 페이로드 + ticketId + agencyCode
  → HMAC-SHA256 (키: IDO_HANDOFF_HMAC_SECRET)
  → Base64URL 인코딩
  → 서명 저장 (ido.handoff_ticket.signature)
```

### 키 로테이션 스케줄러 (HandoffKeyRotationScheduler)

```
- 로테이션 주기: 90일 (ido.ticket.key-rotation-days)
- 유예 기간: 24시간 (기존 키로 복호화 계속 허용)
- Redis 분산 락: ido:crypto:aes:rotate-lock (중복 실행 방지)
- 키 저장: ido.crypto_key_registry + Redis ido:crypto:aes:version:{vN}
```

---

## 4. 내부 서비스 서명 (X-Internal-Sig)

서비스 간 호출 시 무결성 보장.

### 발신 측 (서명 생성)

```java
// X-Internal-Sig: HMAC-SHA256(payload, IDO_INTERNAL_SIG_SECRET)
// X-Internal-Sig-Ts: {epoch-ms}  ← ±60초 타임스탬프
String sig = hmacSha256(payload, internalSigSecret);
headers.add("X-Internal-Sig", sig);
headers.add("X-Internal-Sig-Ts", String.valueOf(System.currentTimeMillis()));
```

### 수신 측 (GAP-QS-04, P1 미완)

```java
// 예정 구현 위치: OidcCompleteController, AuthController
// 1. X-Internal-Sig-Ts 추출 → |now - ts| <= 60s 검증
// 2. HMAC-SHA256 재계산 → 상수시간 비교
```

---

## 5. Webhook 서명 (HMAC-SHA256)

```java
// 발신 (WebhookDispatcherService):
String signature = hmacSha256(payloadJson, webhookSigningSecret);
headers.add("X-Webhook-Signature", signature);
headers.add("X-Webhook-Timestamp", String.valueOf(epochMs));

// 수신 (WebhookInboundController in agency-stub):
// 1. |now - timestamp| <= 300s 검증
// 2. HMAC-SHA256 재계산 → 상수시간 비교
// 3. 검증 실패 → 401 반환
```

---

## 6. W3C Trace Context (TraceparentFilter)

```java
@Component
public class TraceparentFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, ...) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent == null) {
            // 신규 생성: 00-{traceId-32hex}-{spanId-16hex}-01
            traceparent = generateTraceparent();
        }
        // MDC 설정 + 응답 헤더 전파 + 내부 호출 헤더 전달
        MDC.put("traceparent", traceparent);
        response.setHeader("traceparent", traceparent);
    }
}
```

**correlationId와의 관계**:
- `correlationId`: 업무 추적의 기본키 (모든 서비스 공통)
- `traceparent`: 분산 추적 보조 (OpenTelemetry 호환)
- 둘 다 유지. traceparent 추가해도 correlationId 제거 안 함.

---

## 7. PKCE (RFC 7636)

```
code_verifier   = 64 random bytes → Base64URL (86자)
code_challenge  = Base64URL(SHA-256(code_verifier))
method          = "S256" (plain 미지원)

Redis 저장: qsign:pkce:challenge:{state} (TTL 300s)

검증: 상수시간 비교 (MessageDigest.isEqual)
```

---

## 8. agencySubjectId 생성

```java
// agencySubjectId = Base64URL(HMAC-SHA256(qimUserId + ":" + agencyCode, IDO_AGENCY_SUBJECT_SECRET))
String agencySubjectId = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(hmacSha256(qimUserId + ":" + agencyCode, agencySubjectSecret));
```

**특성**:
- 기관별 비가역성 보장 (agencyCode 포함)
- 접두사 문자열 노출 금지
- `IDO_AGENCY_SUBJECT_SECRET` 변경 시 전체 재생성 필요

---

## 9. Rate Limiter (AgencyRateLimiter)

Redis Lua 스크립트 기반 원자적 처리.

```
TPS 체크: INCR ido:rl:tps:{agencyCode}:{epochSec}  → > tps_limit → 429
          EXPIRE 2s

일일 체크: INCR ido:rl:daily:{agencyCode}:{date}    → > daily_limit → 429
           EXPIRE 25h

기관별 한도: ido.agency_rate_limit_config 테이블
  default: tps_limit=200, daily_limit=1,000,000

Redis 오류 시: fail-open (허용)
```

---

## 10. Provider 단위 서킷브레이커 (v1.9.0)

```java
// ProviderCircuitBreakerConfig
// ido.provider_circuit_config 테이블에서 설정 로드
// 공급자 코드별 Resilience4j CircuitBreaker 인스턴스 동적 생성

resilience4j:
  circuitbreaker:
    instances:
      KAKAO_OIDC:
        slidingWindowSize: 20
        failureRateThreshold: 50
      PASS:
        slidingWindowSize: 10
        failureRateThreshold: 60
        waitDurationInOpenState: 60s
```

---

## 11. FE 세션 보안

```
feSessionId 쿠키:
  Secure=true       (HTTPS only)
  HttpOnly=true     (XSS 방어)
  SameSite=Lax      (CSRF 완화)
  
TTL:
  Sliding: 30분 (비활동 시 갱신)
  Absolute: 480분 (최대 세션 수명)

세션 무효화 트리거:
  - 명시적 로그아웃
  - SESSION_ADVISORY 이벤트 수신
  - Q-IM USER_SUSPENDED/WITHDRAWN 이벤트
```

---

## 12. CI 암호화 상세 (Q-IM)

```
암호화:
  AES-256-GCM
  키 버전: v{version}:{base64(IV + ciphertext + GCM tag)}
  IV: 12 byte random
  GCM tag: 16 byte
  
복호화:
  버전 파싱 → 해당 버전 키 선택
  IV 추출 → AES-GCM 복호화
  
키 교체:
  신규 버전 키 추가 → 새 데이터는 신규 버전으로 암호화
  기존 데이터는 이전 버전 키로 복호화 유지
```

---

## 13. 미구현 보안 항목 (P1)

| ID | 항목 | 우선순위 |
|----|------|---------|
| GAP-QS-04 / P1-03 | X-Internal-Sig 수신 측 검증 (Q-Sign AuthController, IdO OidcCompleteController) | P1 |
| GAP-IDO-09 | Kafka DLQ DeadLetterPublishingRecoverer 완전 연결 | P1 |
| P3 | mTLS 기관 인증 | P3 |
| DEBT-01 | HashiCorp Vault / AWS KMS 연동 (현재 환경변수) | v3.0 |
| DEBT-06 | OpenTelemetry 완전 연동 | v3.0 |

---

*다음 문서: [08-infrastructure.md](08-infrastructure.md)*
