# F-03: 감사 로그 Kafka 비동기 발행

> **환경변수**: `IDO_AUDIT_KAFKA_ENABLED`  
> **기본값**: `true`  
> **Spring 프로퍼티**: `ido.audit.kafka-publish-enabled`  
> **소스**: `ido/src/main/java/kr/go/smes/ido/audit/AuditLogPublisher.java`  
> **Kafka 토픽**: `platform.audit.log`

---

## 1. 이 기능은 무엇인가?

감사 로그를 **Kafka `platform.audit.log` 토픽에 비동기로 발행**하여 외부 SIEM·감사 수집 시스템이 소비할 수 있도록 합니다.  
F-04(DB 저장)와 **독립적으로 동작**하므로, Kafka 장애 시에도 DB 저장(F-04)은 정상 유지됩니다.

```
감사 이벤트 발생
  │
  ├─→ [F-04=true] DB 저장 (ido.audit_log) — 동기, 트랜잭션 내
  │
  └─→ [F-03=true] Kafka 발행 (platform.audit.log) — 비동기, 트랜잭션 외
```

---

## 2. F-04(감사 DB)와의 차이

| 항목 | F-03 Kafka 발행 | F-04 DB 저장 |
|------|----------------|-------------|
| 저장 위치 | Kafka `platform.audit.log` 토픽 | `ido.audit_log` 테이블 |
| 처리 방식 | 비동기 (트랜잭션 외부) | 동기 (트랜잭션 내부) |
| 장애 시 영향 | Kafka 장애 → 발행 실패, DB 영향 없음 | DB 장애 → 요청 전체 실패 |
| 주요 용도 | 외부 SIEM, 실시간 감사 스트리밍 | 내부 조회, 컴플라이언스 감사 |
| OFF 가능 여부 | 조건부 허용 (Kafka 장애 대응) | **OFF 금지** ⚠️ |

---

## 3. 동작 원리

```
AuditLogPublisher.publishAsync(AuditEvent event)
  │
  ├─ [F-03=false] → 즉시 반환 (Kafka 미발행)
  │
  └─ [F-03=true]
       │
       ├─ @Async 비동기 실행 (별도 스레드 풀)
       ├─ AuditEvent → JSON 직렬화
       ├─ KafkaTemplate.send("platform.audit.log", agencyCode, payload)
       └─ 실패 시 로컬 로그 경고 (요청 처리에는 영향 없음)
```

---

## 4. Kafka 메시지 형식

**토픽**: `platform.audit.log`  
**파티션 키**: `agencyCode` (동일 기관 이벤트의 순서 보장)

```json
{
  "eventId":    "550e8400-e29b-41d4-a716-446655440000",
  "timestamp":  "2025-01-15T09:30:00.123Z",
  "eventType":  "INBOUND_RECEIVED",
  "agencyCode": "AGCY001",
  "userId":     "user-uuid-here",
  "endpoint":   "/api/v1/agency/gateway/inbound/event",
  "result":     "SUCCESS",
  "ipAddress":  "203.0.113.42",
  "traceId":    "6f9c1d4a8b2e3f7a"
}
```

---

## 5. false 설정 가능한 경우

**Kafka 클러스터 장애 또는 점검 중** 일시적으로 false 허용:
```bash
# Kafka 점검 중 발행 중지 (DB 저장은 계속)
IDO_AUDIT_KAFKA_ENABLED=false
IDO_AUDIT_DB_ENABLED=true   # DB 저장은 반드시 유지
```

Kafka 복구 후 즉시 true로 복원:
```bash
kubectl set env deployment/ido-gateway IDO_AUDIT_KAFKA_ENABLED=true
```

> ⚠️ **false 기간 동안 발행되지 못한 감사 로그는 DB에는 보존되지만 Kafka 토픽에는 소급 발행되지 않습니다.**

---

## 6. false 시 동작

```java
// AuditLogPublisher.publishAsync()
if (!featureFlags.isAuditKafka()) {
    log.debug("[Audit] Kafka publish skipped (F-03=false)");
    return;  // DB 저장에는 영향 없음
}
```

---

## 7. 모니터링

```bash
# Kafka 토픽 컨슈머 랙 확인
kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --describe \
  --group audit-siem-consumer

# 최근 발행된 메시지 샘플링
kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic platform.audit.log \
  --from-beginning \
  --max-messages 10
```

```sql
-- DB와 Kafka 발행 수 비교 (F-03=true 기간 기준)
SELECT
    DATE(created_at)     AS dt,
    COUNT(*)             AS db_saved_count
FROM ido.audit_log
WHERE created_at >= NOW() - INTERVAL 7 DAY
GROUP BY dt
ORDER BY dt DESC;
-- Kafka Consumer 측 소비 수와 비교하여 유실 여부 확인
```

---

## 8. Outbox 패턴 고려사항

현재 F-03는 `@Async` 직접 발행 방식입니다. Kafka 장애 시 메시지 유실이 발생할 수 있습니다.  
신뢰성이 중요한 감사 이벤트는 F-04(DB 저장)를 1차 보장으로, F-03 Kafka를 2차 전달 경로로 운영하는 것을 권장합니다.

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-04 감사 DB](F-04-audit-db.md) | 동기 DB 저장, OFF 금지 — F-03의 1차 백업 |
| [F-13 Outbox 릴레이](F-13-outbox-relay.md) | ido.outbox → Kafka 릴레이 (별개 토픽) |
| [F-18 SP 수신 감사](F-18-sp-receiver-audit.md) | Q-IM 수신 이벤트도 F-03으로 Kafka 발행 |

---

## 연관 문서
- [FeatureFlags.java](../../ido/src/main/java/kr/go/smes/ido/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
