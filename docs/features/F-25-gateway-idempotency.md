# F-25: Gateway 멱등성 중복 방어 (Sprint 15)

> **환경변수**: `IDEM_HUB_GATEWAY_IDEMPOTENCY_ENABLED`  
> **Phase**: 항상 ON (보안 필수)  
> **기본값**: `true`  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/gateway/GatewayIdempotencyStore.java`

---

## 1. 이 기능은 무엇인가?

같은 요청이 **두 번 처리되지 않도록** Redis와 DB 두 레이어로 방어합니다.

```
동일한 X-Idempotency-Key로 두 번 요청이 오는 상황:

요청 1 (첫 번째) ──→ Redis SET NX 성공 → DB INSERT OK → 202 Accepted
요청 2 (중복)    ──→ Redis SET NX 실패 → 즉시 409 Conflict 반환

Redis 장애 시:
요청 1 (첫 번째) ──→ Redis 실패 → DB INSERT OK → 202 Accepted
요청 2 (중복)    ──→ Redis 실패 → DB INSERT → ON CONFLICT DO NOTHING → 409 Conflict
```

---

## 2. 이중 방어 구조

### 1차 방어: Redis SET NX

```java
// GatewayIdempotencyStore.java
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent(
        "gateway:inbound:idempotent:" + idempotencyKey,
        "1",
        Duration.ofHours(24)  // 24시간 TTL
    );

if (!Boolean.TRUE.equals(acquired)) {
    // Redis가 이미 이 키를 알고 있음 → 중복 요청
    return false;
}
```

### 2차 방어: DB ON CONFLICT DO NOTHING

```sql
-- GatewayInboundRepository.insert()
INSERT INTO idem_hub.gateway_inbound_audit
  (idempotency_key, agency_code, event_type, ...)
VALUES
  (:idempotencyKey, :agencyCode, :eventType, ...)
ON CONFLICT (idempotency_key) DO NOTHING
```

**왜 두 레이어인가?**

| 상황 | Redis 방어 | DB 방어 |
|------|:----------:|:-------:|
| Redis 정상 작동 | ✅ 차단 | 도달 안 함 |
| Redis 일시 장애 | ❌ 통과 | ✅ 차단 |
| Redis 완전 다운 | ❌ 통과 | ✅ 차단 |
| DB 장애 | ✅ 차단 | ❌ 불필요 |

---

## 3. 멱등성 키 형식

```
인바운드:  gateway:inbound:idempotent:{X-Idempotency-Key}
아웃바운드: gateway:outbound:idempotent:{X-Idempotency-Key}:{agencyCode}
```

아웃바운드에 `agencyCode`를 포함하는 이유:
- 같은 idempotency_key로 다른 기관에는 발송할 수 있어야 함
- 기관별로 독립적인 멱등성 관리

---

## 4. X-Idempotency-Key 생성 가이드

```java
// idem-sdk-java에서 제공하는 생성기

// 1. UUID v4 (가장 단순)
String key1 = IdempotencyKeyGenerator.generate();
// 출력: "550e8400-e29b-41d4-a716-446655440000"

// 2. 기관 코드 접두사 (추적 용이)
String key2 = IdempotencyKeyGenerator.generateWithPrefix("MOIS");
// 출력: "MOIS-550e8400-e29b-41d4-a716-446655440000"

// 3. 시퀀스 기반 (순서 보장 필요 시)
String key3 = IdempotencyKeyGenerator.generateSequential("MOIS");
// 출력: "MOIS-1715687400000-00001"
```

---

## 5. 자주 묻는 질문

**Q: 409가 오류인가요?**  
A: 아닙니다. 409 Conflict는 "이미 정상 처리된 요청"을 의미합니다. 기관 SDK의 `isIdempotencyConflict()` 메서드로 판별 후 무시하면 됩니다.

**Q: X-Idempotency-Key를 매번 바꿔야 하나요?**  
A: 새로운 이벤트마다 새로운 키를 생성하세요. 같은 이벤트를 재시도할 때는 같은 키를 사용해야 합니다.

**Q: 24시간이 지나면?**  
A: Redis TTL이 만료되어 같은 키로 다시 요청이 가능해집니다. DB UNIQUE 제약은 영구적이므로 DB 레벨 중복은 계속 차단됩니다.

---

## 연관 문서
- [F-23 인바운드 API](F-23-gateway-inbound.md)
- [F-24 아웃바운드 API](F-24-gateway-outbound.md)
- [배포 가이드 §6 멱등성 처리 가이드](../_archive/2026-05-22/deployment-guide.md)
