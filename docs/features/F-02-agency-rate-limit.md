# F-02: 기관별 Rate Limiting

> **환경변수**: `IDO_RATE_LIMIT_ENABLED`  
> **기본값**: `true` (운영 필수)  
> **Spring 프로퍼티**: `ido.rate-limit.enabled`  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/gateway/AgencyRateLimiter.java`

---

## 1. 이 기능은 무엇인가?

**기관(agencyCode)** 단위로 Gateway API 요청 횟수를 제한합니다.  
F-01(IP 기반 Auth RL)과 달리 기관 식별자(`agencyCode`)를 키로 사용하므로, 특정 기관이 다른 기관의 처리 성능에 영향을 주지 못하도록 격리합니다.

```
기관 A (agencyCode=AGCY001): TPS 버킷 10 req/s
기관 B (agencyCode=AGCY002): TPS 버킷 10 req/s  ← 독립, 상호 영향 없음
기관 C (agencyCode=AGCY003): TPS 버킷 10 req/s
```

---

## 2. 동작 원리

```
인바운드/아웃바운드 Gateway 요청
  │
  ▼
AgencyRateLimiter.consume(agencyCode)
  │
  ├─ [F-02=false] → 즉시 통과
  │
  └─ [F-02=true]
       │
       ├─ agencyCode를 키로 기관별 Bucket4j 버킷 조회 (없으면 생성)
       ├─ tryConsume(1) 시도
       ├─ 성공 → 처리 계속
       └─ 실패 → RateLimitExceededException → 429 응답
```

---

## 3. 환경변수 독립성 원칙

> **중요**: F-01의 `IDO_AUTH_RL_ENABLED`와 **반드시 분리**된 환경변수를 사용합니다.  
> 과거 버전의 공유 환경변수 버그는 수정 완료 — 각 플래그를 독립적으로 제어 가능합니다.

```bash
# 운영 표준 설정
IDO_AUTH_RL_ENABLED=true     # F-01: Auth 엔드포인트 IP RL (독립)
IDO_RATE_LIMIT_ENABLED=true  # F-02: Gateway 기관별 RL (독립)

# 기관별 RL만 비활성 (테스트용 — 권장하지 않음)
IDO_AUTH_RL_ENABLED=true
IDO_RATE_LIMIT_ENABLED=false
```

---

## 4. 버킷 설정

```yaml
# application.yml
ido:
  rate-limit:
    enabled: true
    agency:
      tps: 10          # 기관당 초당 최대 요청 수
      per-day: 50000   # 기관당 일별 최대 요청 수
      burst: 20        # 버스트 허용 (일시적 초과)
```

---

## 5. false 설정 가능한 경우

**부하 테스트** 또는 **긴급 장애 대응** 시에만 일시적으로 false 허용:
```bash
# Helm 운영 오버라이드
helm upgrade ido infra/helm/idem-hub \
  --set env.IDO_RATE_LIMIT_ENABLED=false  # ⚠️ 일시적, 반드시 복구
```

> ⚠️ **운영에서 false 유지 시, 단일 기관의 과부하가 전체 Gateway 성능에 영향을 줄 수 있습니다.**

---

## 6. false 시 동작

```java
// AgencyRateLimiter.consume()
if (!featureFlags.isAgencyRateLimit()) {
    return RateLimitResult.ALLOWED;  // 버킷 조회 없이 즉시 허용
}
```

---

## 7. 기관별 임계값 커스터마이징

특정 기관에 대한 임계값 예외 설정 (K8s ConfigMap 또는 DB 기반):

```bash
# 환경변수로 특정 기관 TPS 오버라이드
IDO_RATE_LIMIT_AGCY001_TPS=50      # 기관 AGCY001: 50 TPS 허용
IDO_RATE_LIMIT_AGCY002_TPS=5       # 기관 AGCY002: 5 TPS 제한
```

---

## 8. 응답 형식

**초과 시**:
```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 1

{
  "code": "AGENCY_RATE_LIMIT_EXCEEDED",
  "message": "기관 요청 한도를 초과했습니다.",
  "agencyCode": "AGCY001",
  "retryAfterSeconds": 1
}
```

---

## 9. 모니터링

```sql
-- 기관별 429 응답 빈도 (최근 1시간)
SELECT
    agency_code,
    COUNT(*) AS total_requests,
    SUM(CASE WHEN result_code = '429' THEN 1 ELSE 0 END) AS rate_limited,
    ROUND(SUM(CASE WHEN result_code = '429' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS rl_rate_pct
FROM ido.audit_log
WHERE created_at >= NOW() - INTERVAL 1 HOUR
  AND agency_code IS NOT NULL
GROUP BY agency_code
HAVING rate_limited > 0
ORDER BY rate_limited DESC;
```

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-01 IP Rate Limit](F-01-auth-rate-limit.md) | 독립 환경변수, Auth 경로 전용 |
| [F-23 Gateway 인바운드](F-23-gateway-inbound.md) | 인바운드 요청에 F-02 적용 |
| [F-24 Gateway 아웃바운드](F-24-gateway-outbound.md) | 아웃바운드 요청에 F-02 적용 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
