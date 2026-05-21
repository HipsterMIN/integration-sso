# 06. Kafka 이벤트 카탈로그

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09

---

## 1. 토픽 전체 목록

| 토픽 | 타입 | 파티션 | 보존 | 생산자 | 주요 소비자 |
|------|------|--------|------|--------|-----------|
| `qsign.auth.events` | 일반 | 12 | 1h | Q-Sign Outbox | IdO `QsignAuthEventConsumer` |
| `qsign.auth.events.dlq` | DLQ | 6 | 7d | IdO 에러핸들러 | 운영 모니터링 |
| `ido.handoff.events` | 일반 | 12 | 1y | IdO Outbox | IdO `HandoffEventConsumer` → Webhook |
| `ido.handoff.events.dlq` | DLQ | 6 | 7d | IdO 에러핸들러 | 운영 모니터링 |
| `platform.session.advisory` | 일반 | 12 | 24h | IdO `SessionAdvisoryPublisher` | IdO `FeAdvisoryConsumer` |
| `platform.session.advisory.dlq` | DLQ | 6 | 7d | IdO 에러핸들러 | 운영 모니터링 |
| `platform.audit.log` | 일반 | 12 | 2y | IdO `AuditLogPublisher` | 감사 시스템 |
| `qim.user.events` | Compacted | 6 | — | Q-IM Outbox | IdO `QimEventConsumer`, Q-Sign `QimUserEventConsumer` |
| `qim.user.events.dlq` | DLQ | 3 | 7d | Q-IM 에러핸들러 | 운영 모니터링 |
| `qim.user.snapshot` | Compacted | 6 | — | Q-IM `SnapshotServiceImpl` | (향후 확장) |
| `qim.sp.member.events` | 일반 | 6 | 30d | IdO `QimSpReceiverController` | IdO `QimSpMemberEventConsumer` |
| `qim.sp.member.events.dlt` | DLQ | 3 | 7d | IdO 에러핸들러 | 운영 모니터링 |

> **파티션 설계**: PoC 기준. 운영에서는 복제 인수 3, min ISR 2 설정 필요.

---

## 2. 이벤트 스키마 상세

### 2.1 AUTH_COMPLETED (`qsign.auth.events`)

```json
{
  "eventId": "uuid",
  "eventType": "AUTH_COMPLETED",
  "eventVersion": "1.0",
  "sourceSystem": "q-sign",
  "occurredAt": "2026-05-09T00:00:00Z",
  "correlationId": "uuid",
  "data": {
    "authResultId": "uuid",
    "providerCode": "KAKAO_OIDC",
    "authLevel": "L1",
    "authMethod": "KAKAO_OIDC",
    "identifierHash": "sha256hex",
    "authenticatedAt": "2026-05-09T00:00:00Z"
  }
}
```

**소비자 처리** (`QsignAuthEventConsumer`):
1. `authResultId` 기반 멱등 확인
2. FE 세션 생성 트리거
3. IdO `ido.auth_result` 동기화

---

### 2.2 AUTH_LOCKED (`qsign.auth.events`)

```json
{
  "eventType": "AUTH_LOCKED",
  "data": {
    "identifierHash": "sha256hex",
    "providerCode": "KAKAO_OIDC",
    "failureCount": 5,
    "lockedUntil": "2026-05-09T00:30:00Z"
  }
}
```

---

### 2.3 HANDOFF_ISSUED (`ido.handoff.events`)

```json
{
  "eventType": "HANDOFF_ISSUED",
  "data": {
    "ticketId": "uuid",
    "agencyCode": "AGENCY_STUB_001",
    "correlationId": "uuid",
    "identifierHash": "sha256hex",
    "authLevel": "L2",
    "issuedAt": "2026-05-09T00:00:00Z",
    "expiresAt": "2026-05-09T00:01:00Z"
  }
}
```

---

### 2.4 HANDOFF_CONSUMED (`ido.handoff.events`)

```json
{
  "eventType": "HANDOFF_CONSUMED",
  "data": {
    "ticketId": "uuid",
    "agencyCode": "AGENCY_STUB_001",
    "correlationId": "uuid",
    "consumedAt": "2026-05-09T00:00:30Z"
  }
}
```

**소비자 처리** (`HandoffEventConsumer` → `WebhookDispatcherService`):
1. `webhook_dispatch_outbox` INSERT
2. Webhook Push (`WebhookDispatchOutboxRelay`)

---

### 2.5 HANDOFF_REUSE_ATTEMPT (`ido.handoff.events`)

보안 이벤트. 재사용 시도 감지 시 즉시 게시.

```json
{
  "eventType": "HANDOFF_REUSE_ATTEMPT",
  "data": {
    "ticketId": "uuid",
    "agencyCode": "AGENCY_STUB_001",
    "attemptedAt": "2026-05-09T00:00:45Z",
    "clientIp": "192.168.1.100"
  }
}
```

---

### 2.6 SESSION_ADVISORY (`platform.session.advisory`)

FE 세션 강제 무효화 지시.

```json
{
  "eventType": "SESSION_INVALIDATE",
  "data": {
    "feSessionId": "uuid",
    "reason": "USER_SUSPENDED",
    "mandatory": true
  }
}
```

**소비자 처리** (`FeAdvisoryConsumer`):
- `mandatory=true`: 즉시 FE 세션 삭제 (Redis)
- `mandatory=false`: advisory 플래그 설정

---

### 2.7 USER_UPDATED (`qim.user.events`)

Q-IM 사용자 상태 변경 이벤트 (Compacted Topic, key = `qimUserId`).

```json
{
  "eventType": "USER_UPDATED",
  "key": "qim-user-uuid",
  "data": {
    "qimUserId": "uuid",
    "status": "SUSPENDED",
    "reason": "ADMIN_ACTION",
    "changedAt": "2026-05-09T00:00:00Z",
    "needsSync": true,
    "syncReason": "STATUS_CHANGED"
  }
}
```

**소비자 처리**:
- IdO `QimEventConsumer`: `needsSync=true` 시 Q-IM 선택적 Pull (현재 TODO), Advisory 발행
- Q-Sign `QimUserEventConsumer`: USER_SUSPENDED → auth_lock 잠금, USER_WITHDRAWN → 영구 잠금

---

### 2.8 USER_WITHDRAWN (`qim.user.events`)

Tombstone 메시지 (value = null, key = `qimUserId`).

---

### 2.9 SNAPSHOT (`qim.user.snapshot`)

Q-IM Snapshot 이벤트 (Compacted Topic, key = `qimUserId`).

```json
{
  "eventType": "USER_SNAPSHOT",
  "key": "qim-user-uuid",
  "data": {
    "qimUserId": "uuid",
    "status": "ACTIVE",
    "primaryName": "홍*동",
    "authMeans": ["KAKAO_OIDC", "PASS"],
    "snapshotAt": "2026-05-09T00:00:00Z"
  }
}
```

---

### 2.10 AuditLog (`platform.audit.log`)

2년 보존. PII 원문 금지.

```json
{
  "eventType": "AUDIT_LOG",
  "data": {
    "eventCategory": "HANDOFF",
    "eventAction": "TICKET_ISSUED",
    "actorType": "SYSTEM",
    "actorId": "ido",
    "resourceType": "HANDOFF_TICKET",
    "resourceId": "ticket-uuid",
    "agencyCode": "AGENCY_STUB_001",
    "sourceSystem": "ido",
    "sourceIp": "172.20.0.1",
    "outcome": "SUCCESS",
    "outcomeDetail": null,
    "metadataJson": "{\"authLevel\":\"L2\",\"providerCode\":\"PASS\"}"
  }
}
```

---

## 3. 컨슈머 그룹 목록

| 컨슈머 그룹 | 소비 토픽 | 담당 클래스 |
|-----------|---------|-----------|
| `ido-qsign-consumer` | `qsign.auth.events` | `QsignAuthEventConsumer` |
| `ido-handoff-consumer` | `ido.handoff.events` | `HandoffEventConsumer` |
| `ido-fe-advisory-consumer` | `platform.session.advisory` | `FeAdvisoryConsumer` |
| `ido-qim-consumer` | `qim.user.events` | `QimEventConsumer` |
| `ido-qim-sp-consumer` | `qim.sp.member.events` | `QimSpMemberEventConsumer` |
| `q-sign-consumer` | `qim.user.events` | `QimUserEventConsumer` |

---

## 4. Transactional Outbox 패턴

모든 서비스는 Kafka 직접 호출 대신 **Transactional Outbox** 패턴을 사용한다.

```
1. 비즈니스 로직 실행 (DB 저장 + outbox INSERT) → 같은 트랜잭션
2. OutboxRelayScheduler (500ms 주기) → PENDING 레코드 조회
3. KafkaTemplate.send() → PUBLISHED 상태 업데이트
4. 실패 시 retry_count 증가 → 최대 3회 → FAILED
```

**장점**:
- DB 저장과 Kafka 게시의 원자성 보장
- 서비스 재시작 시 미발행 이벤트 자동 복구
- DLQ 연동 시 추적 가능

---

## 5. DLQ 설계

### DLQ 메시지 보존 필드 (6개 필수)

```json
{
  "originalTopic": "qsign.auth.events",
  "originalPartition": 3,
  "originalOffset": 12345,
  "originalKey": "auth-uuid",
  "originalValue": "{ ... }",
  "errorMessage": "NullPointerException at QimEventConsumer.java:45",
  "failedAt": "2026-05-09T00:00:00Z"
}
```

> **GAP-IDO-09** (P1 미완): `KafkaConsumerConfig`에 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 완전 연결 필요.

---

*다음 문서: [07-security.md](07-security.md)*
