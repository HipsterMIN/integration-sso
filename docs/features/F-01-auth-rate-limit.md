# F-01: IP 기반 Auth Rate Limiting

> **환경변수**: `IDEM_HUB_AUTH_RL_ENABLED`  
> **기본값**: `true` (운영 필수)  
> **Spring 프로퍼티**: `idem.hub.auth.rate-limit.enabled`  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/auth/AuthRateLimitInterceptor.java`

---

## 1. 이 기능은 무엇인가?

`/api/v1/auth/**` 경로에 대해 **요청 발신 IP 주소** 단위로 TPS·분당·일별 요청 횟수를 제한합니다.  
Bucket4j 라이브러리를 사용하여 토큰 버킷 알고리즘으로 동작하며, 인증 브루트포스 공격을 방어합니다.

```
클라이언트 IP: 203.0.113.42
  ├─ TPS 버킷: 5 req/s → 초과 시 429 Too Many Requests
  ├─ 분당 버킷: 30 req/min
  └─ 일별 버킷: 200 req/day
```

---

## 2. 동작 원리

```
요청 도착 (/api/v1/auth/**)
  │
  ▼
AuthRateLimitInterceptor.preHandle()
  │
  ├─ [F-01=false] → 버킷 확인 스킵, 즉시 통과
  │
  └─ [F-01=true]
       │
       ├─ IP 주소 추출 (X-Forwarded-For 우선, RemoteAddr 폴백)
       ├─ Bucket4j 버킷 조회 또는 신규 생성 (ConcurrentHashMap)
       ├─ tryConsume(1) → 성공: 통과, 실패: 429 반환
       └─ X-RateLimit-Remaining 헤더 삽입
```

---

## 3. 환경변수 독립성 원칙

> **중요**: F-01의 `IDEM_HUB_AUTH_RL_ENABLED`와 F-02의 `IDEM_HUB_RATE_LIMIT_ENABLED`는 **반드시 분리된 환경변수**를 사용합니다.  
> 이전 버전에서 두 플래그가 동일 환경변수를 공유하던 버그가 수정되었습니다.

```bash
# 올바른 설정 예시 — 독립 제어 가능
IDEM_HUB_AUTH_RL_ENABLED=true       # F-01: IP 기반 Auth RL
IDEM_HUB_RATE_LIMIT_ENABLED=true    # F-02: 기관별 RL (별개 변수)

# 잘못된 예시 (이전 버전 버그 패턴) — 현재는 불가
IDEM_HUB_RATE_LIMIT_ENABLED=true    # F-01과 F-02를 같은 변수로 제어 ❌
```

---

## 4. 제한 임계값 설정

```yaml
# application.yml
ido:
  auth:
    rate-limit:
      enabled: true
      tps: 5           # 초당 최대 요청 수
      per-minute: 30   # 분당 최대 요청 수
      per-day: 200     # 일별 최대 요청 수
```

K8s ConfigMap 또는 환경변수로 오버라이드 가능:
```bash
IDEM_HUB_AUTH_RL_ENABLED=true
IDEM_HUB_AUTH_RL_TPS=5
IDEM_HUB_AUTH_RL_PER_MINUTE=30
IDEM_HUB_AUTH_RL_PER_DAY=200
```

---

## 5. false 설정 가능한 경우

**로컬 개발 / 통합 테스트** 환경에서만 false 허용:
```bash
IDEM_HUB_AUTH_RL_ENABLED=false  # 부하 테스트, 통합 테스트 시
```

> ⚠️ **운영 환경에서 false 설정 시 브루트포스 공격에 무방비 상태가 됩니다.**

---

## 6. false 시 동작

```java
// AuthRateLimitInterceptor.preHandle()
if (!featureFlags.isAuthRateLimit()) {
    return true;  // 즉시 통과 — 버킷 생성/조회 없음
}
```

---

## 7. 응답 형식

**정상 (버킷 여유 있음)**:
```http
HTTP/1.1 200 OK
X-RateLimit-Remaining: 4
```

**초과 시**:
```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json

{
  "code": "AUTH_RATE_LIMIT_EXCEEDED",
  "message": "요청 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.",
  "retryAfterSeconds": 1
}
```

---

## 8. 모니터링

```sql
-- 최근 1시간 Auth 엔드포인트 요청 분포 (감사 로그 기준)
SELECT
    DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS minute,
    COUNT(*)                                   AS request_count,
    SUM(CASE WHEN result_code = '429' THEN 1 ELSE 0 END) AS rate_limited
FROM idem_hub.audit_log
WHERE endpoint LIKE '/api/v1/auth/%'
  AND created_at >= NOW() - INTERVAL 1 HOUR
GROUP BY minute
ORDER BY minute DESC;
```

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-02 기관별 Rate Limit](F-02-agency-rate-limit.md) | 독립 환경변수, 별개 버킷 |
| [F-04 감사 DB](F-04-audit-db.md) | 429 응답도 감사 로그에 기록 |
| [F-08 Redisson 분산 락](F-08-redisson-lock.md) | K8s 다중 Pod 환경의 버킷 공유 방법 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
