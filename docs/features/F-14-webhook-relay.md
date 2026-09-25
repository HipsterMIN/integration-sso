# F-14: Webhook Outbox HTTP 릴레이

> **환경변수**: `IDEM_HUB_WEBHOOK_RELAY_ENABLED`  
> **기본값**: `true`  
> **Spring 프로퍼티**: `idem.hub.webhook.relay-enabled`  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/webhook/WebhookRelayScheduler.java`  
> **대상 테이블**: `webhook_dispatch_outbox`

---

## 1. 이 기능은 무엇인가?

`webhook_dispatch_outbox` 테이블의 `PENDING` 상태 레코드를 주기적으로 조회하여, 등록된 **기관 Webhook URL로 HTTP POST 요청을 발송**합니다.  
이벤트 발행 시 DB에 먼저 기록(Outbox 패턴)하고, 이 릴레이가 실제 HTTP 발송을 담당하므로 **정확히 한 번 이상(at-least-once) 전달을 보장**합니다.

---

## 2. F-13(Outbox 릴레이)과의 차이

| 항목 | F-14 Webhook 릴레이 | F-13 Outbox 릴레이 |
|------|--------------------|--------------------|
| 소스 테이블 | `webhook_dispatch_outbox` | `ido.outbox` |
| 전달 대상 | **기관 Webhook HTTP URL** | **Kafka** `platform.events` 토픽 |
| 발송 방식 | HTTP POST (기관 서버) | KafkaTemplate.send() |
| 재시도 정책 | 지수 백오프 (1분→5분→30분) | 고정 30초 간격 |
| 보안 | HMAC-SHA256 서명 (F-26) | Kafka 내부 인증 |

---

## 3. 동작 원리

```
[이벤트 발생]
  → webhook_dispatch_outbox INSERT (PENDING, retry_count=0)

[WebhookRelayScheduler — 30초마다]
  │
  SELECT * FROM webhook_dispatch_outbox
  WHERE status = 'PENDING'
    AND next_retry_at <= NOW()
  ORDER BY created_at
  LIMIT 100
  FOR UPDATE SKIP LOCKED     ← 다중 Pod 중복 방지
  │
  ├─ HTTP POST 발송 (기관 Webhook URL)
  │   ├─ 성공 → status = 'SENT', sent_at = NOW()
  │   └─ 실패 → retry_count++, next_retry_at 계산
  │             retry_count=1: +1분
  │             retry_count=2: +5분
  │             retry_count=3: +30분
  │             retry_count>=4: status = 'DEAD', 알림
  │
  └─ 감사 로그 기록 (F-04)
```

---

## 4. Webhook 요청 형식

```http
POST https://agency.example.go.kr/webhook/onepass
Content-Type: application/json
X-OnePass-Event: MEMBER_STATUS_CHANGED
X-OnePass-Timestamp: 1736932200
X-Internal-Sig: a3f8c2d1...   ← F-26 HMAC 서명 (F-26=true 시)
X-Idempotency-Key: evt-550e8400

{
  "eventType": "MEMBER_STATUS_CHANGED",
  "agencyCode": "AGCY001",
  "userId": "user-uuid",
  "changedAt": "2025-01-15T09:30:00Z",
  "payload": { ... }
}
```

---

## 5. false 설정 가능한 경우

**Webhook 기관이 아직 없는 경우** 또는 **릴레이 스케줄러 임시 중지** 시:
```bash
IDEM_HUB_WEBHOOK_RELAY_ENABLED=false  # webhook_dispatch_outbox 레코드 누적됨
```

> ⚠️ **false 기간 중 `webhook_dispatch_outbox`에 레코드가 누적됩니다. true 복원 시 일괄 재발송됩니다.**  
> 누적량이 많을 경우, 복원 직후 기관 서버에 과부하가 발생할 수 있습니다 → `--throttle` 모드 사용 권장.

---

## 6. DEAD 상태 레코드 처리

재시도 4회 실패 시 `DEAD` 상태로 전환:
```sql
-- DEAD 상태 레코드 조회
SELECT id, agency_code, event_type, retry_count, last_error, created_at
FROM webhook_dispatch_outbox
WHERE status = 'DEAD'
ORDER BY created_at DESC;

-- 수동 재시도 (last_error 확인 후 기관 URL 복구 시)
UPDATE webhook_dispatch_outbox
SET status = 'PENDING', retry_count = 0, next_retry_at = NOW()
WHERE id IN (?, ?, ?)
  AND status = 'DEAD';
```

---

## 7. 모니터링

```sql
-- Webhook 발송 현황 (최근 1시간)
SELECT
    agency_code,
    status,
    COUNT(*) AS cnt,
    MAX(created_at) AS latest
FROM webhook_dispatch_outbox
WHERE created_at >= NOW() - INTERVAL 1 HOUR
GROUP BY agency_code, status
ORDER BY agency_code, status;

-- 과도한 재시도 기관 탐지
SELECT agency_code, AVG(retry_count) AS avg_retry, MAX(retry_count) AS max_retry
FROM webhook_dispatch_outbox
WHERE created_at >= NOW() - INTERVAL 24 HOUR
GROUP BY agency_code
HAVING avg_retry > 2
ORDER BY avg_retry DESC;
```

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-13 Outbox 릴레이](F-13-outbox-relay.md) | 동일 Outbox 패턴, Kafka 전달 담당 |
| [F-26 HMAC 서명](F-26-hmac-sig.md) | Webhook HTTP POST에 X-Internal-Sig 서명 |
| [F-08 Redisson 분산 락](F-08-redisson-lock.md) | FOR UPDATE SKIP LOCKED로 대체 (락 불필요) |
| [F-04 감사 DB](F-04-audit-db.md) | 발송 성공/실패 감사 기록 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
