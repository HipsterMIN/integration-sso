# ADR-008: Transactional Outbox 패턴 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-008 |
| **제목** | Transactional Outbox 패턴으로 분산 트랜잭션 일관성 보장 |
| **상태** | ✅ Accepted |
| **결정일** | 2025 (Sprint 5) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `ido/infrastructure/outbox/IdoOutboxRelay.java`, `ido/infrastructure/outbox/QimOutboxRelay.java`, `ido/provision/ProvisioningOutboxRelay.java`, `ido/webhook/WebhookDispatchOutboxRelay.java`, `q-im/outbox/OutboxServiceImpl.java` |

---

## 컨텍스트 (Context)

### 문제: 분산 트랜잭션의 2-phase commit 없는 일관성 보장

```
문제 시나리오:
  1. Q-IM: 회원 DB INSERT (성공)
  2. Q-IM → Kafka 이벤트 발행 (실패!)
  → 회원은 등록됐지만 IdO 프로비저닝 미실행 → 기관 미동기화
```

2-phase commit(2PC)은 분산 DB 트랜잭션으로 성능 저하와 단일 장애점 위험이 있어 채택하지 않기로 결정했다.

---

## 결정 (Decision)

**Transactional Outbox 패턴**을 채택한다. 이벤트를 DB에 먼저 저장하고, 별도 릴레이 프로세스가 Kafka/HTTP로 전송한다.

### 이중 Outbox 구조 (IdO 기준)

```
┌─────────────────────────────────────────────────────────────┐
│ ido DB                                                      │
│                                                             │
│  ido.outbox                    ido.provisioning_outbox      │
│  (qim.user.events 재발행용)    (기관 HTTP 발송용)            │
│       │                               │                     │
│       ▼                               ▼                     │
│  [IdoOutboxRelay]          [ProvisioningOutboxRelay]        │
│  [QimOutboxRelay]                                           │
│  Kafka 발행                    기관 HTTPS POST              │
└─────────────────────────────────────────────────────────────┘
```

### 전체 Outbox 목록

| 서비스 | 테이블 | 릴레이 클래스 | 전송 대상 |
|--------|--------|--------------|---------|
| Q-IM | `qim.outbox` | `OutboxRelay` (Q-IM) | Kafka `qim.user.events` |
| IdO | `ido.outbox` | `IdoOutboxRelay` | Kafka `ido.audit.events` |
| IdO | `ido.outbox` | `QimOutboxRelay` | Kafka `qim.*` 재발행 |
| IdO | `ido.provisioning_outbox` | `ProvisioningOutboxRelay` | 기관 HTTPS POST |
| IdO | `ido.webhook_dispatch_outbox` | `WebhookDispatchOutboxRelay` | 기관 Webhook |

### 릴레이 동작 방식

```java
// ProvisioningOutboxRelay 예시
@Scheduled(fixedDelayString = "${ido.provisioning.relay.interval-ms:5000}")
public void relay() {
    // 1. PENDING + next_retry_at <= NOW() 배치 조회
    List<ProvisioningOutboxRecord> batch = repository.findPendingBatch(batchSize);

    for (ProvisioningOutboxRecord record : batch) {
        try {
            // 2. 기관 HTTPS POST 시도
            httpClient.post(record.getEndpointUrl(), record.getPayloadJson());
            // 3. 성공 → COMPLETED 마킹
            repository.markCompleted(record.getId());
        } catch (Exception e) {
            // 4. 실패 → 재시도 카운트 증가 + next_retry_at 지수 백오프 계산
            repository.incrementRetryWithBackoff(record.getId());
            // 5. maxRetry 초과 → DEAD_LETTER
        }
    }
}
```

### 지수 백오프 전략
```
재시도 0회: 즉시
재시도 1회: +30초
재시도 2회: +120초
재시도 3회: +480초 (DEAD_LETTER 전환)
```

### Thundering Herd 방지 (V17 해결)
```sql
-- V17__add_outbox_next_retry_at.sql
ALTER TABLE ido.provisioning_outbox
    ADD COLUMN next_retry_at TIMESTAMPTZ DEFAULT NOW();

-- 조회 조건: status = 'PENDING' AND next_retry_at <= NOW()
-- 모든 PENDING이 동시에 처리 시도하는 문제 방지
```

### EXCLUDED_TOPICS 분리 (PR #105 해결)
```java
// IdoOutboxRelay — auth 이벤트 전용
// QimOutboxRelay — qim.user.events 전용 (분리)
// EXCLUDED_TOPICS: IdoOutboxRelay가 qim.user.events 처리 시도 차단
private static final Set<String> EXCLUDED_TOPICS = Set.of("qim.user.events");
```

---

## 결과 (Consequences)

### 긍정적 효과
- **at-least-once 보장**: DB INSERT 성공 = 이벤트 보존, 전송 실패 시 재시도
- **서비스 장애 격리**: Kafka 장애 시 outbox에 쌓이고 복구 후 전송
- **감사 가능성**: outbox 테이블에 모든 이벤트 기록 유지
- **동기 코드 유지**: 애플리케이션 코드는 DB INSERT만 → 단순성 유지

### 부정적 효과 / 주의사항
- **최소 지연**: DB INSERT → 릴레이 폴링 → 전송 (5초 내외 지연)
- **중복 전송 가능**: at-least-once → 멱등성 처리 필수 (IdempotentEventStore, V5)
- **폴링 오버헤드**: 릴레이 주기적 DB 조회 → 인덱스 최적화 필수
- **DEAD_LETTER 모니터링**: 최대 재시도 초과 레코드 알림 체계 필요

### 포기한 대안
- **Saga Pattern (Choreography)**: 보상 트랜잭션 복잡도 과도
- **2-Phase Commit**: 분산 DB 트랜잭션 → 성능 저하, 단일 장애점
- **Debezium CDC**: 인프라 복잡도 증가 (Kafka Connect 클러스터 추가 필요)

---

## 관련 ADR

- [ADR-004](ADR-004-kafka-eda.md) — Kafka (Outbox 전송 대상)
- [ADR-005](ADR-005-postgresql-primary-store.md) — PostgreSQL (Outbox 저장소)
- [ADR-009](ADR-009-qim-outbox-spec-001.md) — QIM-OUTBOX-SPEC-001 (이벤트 타입)
