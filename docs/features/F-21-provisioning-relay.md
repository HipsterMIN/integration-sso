# F-21: Provisioning Outbox Relay (Sprint 14)

> **⚠️ 제거됨 (2026-09-10, 범용화 S4b)** — 전 기관 프로비저닝은 코어에서 삭제되었다. 플랫폼은 기관(Service)에 사용자를 등록·방송하지 않으며, 어설션·백채널 로그아웃·보안/감사 이벤트만 push 한다. 근거: `docs/generalization-plan.md` §1.2 C8 · §2.0 · §3 S4b. 아래 내용은 이력 참고용이다.


> **환경변수**: `IDEM_HUB_PROVISIONING_RELAY_ENABLED`  
> **Phase**: Phase 2-B (F-20 실제 발행 전환과 동시 활성화)  
> **기본값**: `false` (안전)  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/provision/ProvisioningOutboxRelay.java`

---

## 1. 이 기능은 무엇인가?

F-20 프로비저닝에서 기관 HTTP POST가 실패한 건을 **주기적으로 재시도**하는 스케줄러입니다.

```
provisioning_outbox 테이블
  ┌─────────────┬────────────┬───────────┐
  │ status      │ retry_count│ next_retry│
  ├─────────────┼────────────┼───────────┤
  │ PENDING     │ 1          │ 1분 후    │ ← 재시도 대상
  │ PENDING     │ 2          │ 5분 후    │ ← 재시도 대상
  │ COMPLETED   │ 0          │ -         │ ← 완료, 무시
  │ DEAD_LETTER │ 5          │ -         │ ← 수동 처리 필요
  └─────────────┴────────────┴───────────┘
         ↑
  30초마다 폴링 (IDEM_HUB_PROVISIONING_RELAY_INTERVAL_MS=30000)
```

---

## 2. F-20과의 관계

| 상황 | F-20 | F-21 | 결과 |
|------|:----:|:----:|------|
| Phase 1 (OFF) | false | false | 프로비저닝 없음 |
| Phase 2-A (dry-run) | true | false | 로그만, 재시도 없음 |
| Phase 2-B (실제) | true | **true** | 발행 + 실패 재시도 |
| F-20만 ON | true | false | 발행 O, 실패 재시도 X ⚠️ |

> **주의**: F-20을 true로 했다면 F-21도 반드시 true로 함께 활성화하세요.  
> F-21이 false인 상태에서 HTTP 실패가 발생하면 PENDING 건이 영구적으로 쌓입니다.

---

## 3. 동작 원리

### 폴링 주기

```java
@Scheduled(fixedDelayString = "${idem.hub.provisioning.relay-interval-ms:30000}")
@Transactional
public void relay() {
    if (!relayEnabled) { return; }  // F-21 가드
    // ...
}
```

- `fixedDelay`: 이전 실행 **완료 후** 대기 → 실행 중 중복 없음
- 기본 30초 (지수 백오프 최소 단위 1분보다 짧게 → 빠른 감지)

### FOR UPDATE SKIP LOCKED

```sql
-- 다중 Pod 환경에서 같은 레코드를 여러 Pod가 동시에 처리하지 않도록
SELECT * FROM idem_hub.provisioning_outbox
WHERE status = 'PENDING'
  AND next_retry_at <= NOW()
ORDER BY next_retry_at ASC
LIMIT 50
FOR UPDATE SKIP LOCKED
```

**예시**: Pod A와 Pod B가 동시에 폴링할 때
- Pod A: 레코드 1, 2, 3 잠금 획득 → 처리
- Pod B: 1, 2, 3 SKIP → 4, 5, 6 처리
- 중복 발송 없음 ✅

---

## 4. 지수 백오프 계산

```sql
-- ProvisioningOutboxRepositoryImpl — next_retry_at 계산
UPDATE idem_hub.provisioning_outbox
SET retry_count = retry_count + 1,
    next_retry_at = NOW() + CASE retry_count
        WHEN 0 THEN INTERVAL '1 minute'   -- 1차 실패 후 1분
        WHEN 1 THEN INTERVAL '5 minutes'  -- 2차 실패 후 5분
        WHEN 2 THEN INTERVAL '30 minutes' -- 3차 실패 후 30분
        ELSE         INTERVAL '30 minutes'
    END,
    last_error = :errorMessage
WHERE id = :id
```

---

## 5. DEAD_LETTER 처리

`retry_count >= max_retry` 조건이 되면 `DEAD_LETTER` 상태로 전환됩니다.

```bash
# DEAD_LETTER 확인
psql -c "
  SELECT id, agency_code, retry_count, last_error, created_at
  FROM idem_hub.provisioning_outbox
  WHERE status = 'DEAD_LETTER'
  ORDER BY created_at DESC;"

# 수동 재처리 (기관 엔드포인트 복구 후)
psql -c "
  UPDATE idem_hub.provisioning_outbox
  SET status = 'PENDING',
      retry_count = 0,
      next_retry_at = NOW(),
      last_error = 'Manual retry by operator'
  WHERE id = 'your-record-id';"
```

---

## 6. 모니터링 쿼리

```sql
-- 현재 상태 요약
SELECT status, count(*), max(created_at) as latest
FROM idem_hub.provisioning_outbox
WHERE created_at > now() - interval '24 hours'
GROUP BY status;

-- 기관별 PENDING 건수
SELECT agency_code, count(*) as pending_count
FROM idem_hub.provisioning_outbox
WHERE status = 'PENDING'
GROUP BY agency_code
ORDER BY pending_count DESC;
```

---

## 연관 문서
- [F-20 프로비저닝](F-20-provisioning.md)
- [배포 가이드 §8.1, §8.8](../_archive/2026-05-22/deployment-guide.md)
