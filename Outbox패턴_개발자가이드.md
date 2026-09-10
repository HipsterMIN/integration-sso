# Outbox 패턴 개발자 가이드
## — 팀별 구현 책임, INSERT/UPDATE 시점, 상태 전이 완전 참조서 —

> **대상 독자**: Q-Sign팀, Q-IM팀, IdO팀 개발자  
> **목적**: 각 팀이 Outbox 패턴을 어디서, 어떻게 구현하며, 어떤 상태 전이가 발생하는지 깊이 이해한다.

---

## 목차

1. [아키텍처 전체 조감도](#1-아키텍처-전체-조감도)
2. [Outbox 레코드 공통 스키마 및 상태 전이](#2-outbox-레코드-공통-스키마-및-상태-전이)
3. [Q-Sign팀 구현 가이드](#3-q-sign팀-구현-가이드)
4. [Q-IM팀 구현 가이드](#4-q-im팀-구현-가이드)
5. [IdO팀 구현 가이드 — ido.outbox](#5-ido팀-구현-가이드--idooutbox)
6. [IdO팀 구현 가이드 — webhook_dispatch_outbox](#6-ido팀-구현-가이드--webhook_dispatch_outbox)
7. [IdO팀 구현 가이드 — provisioning_outbox](#7-ido팀-구현-가이드--provisioning_outbox)
8. [Kafka Consumer 구현 패턴](#8-kafka-consumer-구현-패턴)
9. [IdempotentEventStore — 중복 방어 구현](#9-idempotent-eventstore--중복-방어-구현)
10. [Feature Flag 제어](#10-feature-flag-제어)
11. [장애 대응 및 운영 SQL](#11-장애-대응-및-운영-sql)
12. [테스트 전략](#12-테스트-전략)

---

## 1. 아키텍처 전체 조감도

### 1.1 전체 Writer → Relay → Consumer 매핑

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          OUTBOX 패턴 전체 구조                                    │
├────────────────────────┬───────────────────────────┬────────────────────────────┤
│       WRITER           │        OUTBOX TABLE         │    RELAY / CONSUMER        │
├────────────────────────┼───────────────────────────┼────────────────────────────┤
│ KeycloakOidcService    │                           │                            │
│ .saveOutboxEvent()     ├──► ido.outbox             ├──► IdoOutboxRelay          │
│ NonOidcAuthService     │    (PostgreSQL JSONB)      │    → Kafka qsign.auth.events│
├────────────────────────┼───────────────────────────┼────────────────────────────┤
│ AuthServiceImpl        │                           │                            │
│ (Q-Sign 내부)          ├──► qsign.outbox           ├──► OutboxRelay (Q-Sign)    │
│ .saveToOutbox()        │    (PostgreSQL JSONB)      │    → Kafka qsign.auth.events│
├────────────────────────┼───────────────────────────┼────────────────────────────┤
│ Q-IM UserRegService    │                           │                            │
│ Q-IM UserMgmtService   ├──► qim.outbox             ├──► OutboxServiceImpl (Q-IM)│
│ OutboxService          │    (MariaDB JSON)          │    → Kafka qim.user.events  │
│ .publishInTx()         │                           │    → qim.user.snapshot      │
├────────────────────────┼───────────────────────────┼────────────────────────────┤
│ WebhookDispatcherService│                          │                            │
│ .insertOutbox()        ├──► ido.webhook_dispatch_  ├──► WebhookDispatchOutboxRelay│
│                        │    outbox (PostgreSQL JSONB)│   → HTTPS POST 기관 endpoint│
├────────────────────────┼───────────────────────────┼────────────────────────────┤
│ ProvisioningServiceImpl│                           │                            │
│                        ├──► ido.provisioning_outbox├──► ProvisioningOutboxRelay │
│                        │    (PostgreSQL JSONB)      │    → HTTPS POST 기관 endpoint│
└────────────────────────┴───────────────────────────┴────────────────────────────┘

[Consumer 측 구독 관계]
  Kafka qsign.auth.events → QsignAuthEventConsumer (IdO)
                          → [향후] 추가 소비자
  Kafka qim.user.events   → QimUserEventConsumer (Q-Sign)
                          → QimSpMemberEventConsumer (IdO)
  Kafka ido.handoff.events → HandoffEventConsumer (IdO)
                           → [PoC 한정] HandoffEventConsumer (agency-stub)
                             ⚠️ 실 운영에서 외부 기관은 Kafka에 직접 접근 불가
                             → 운영 대체: IdO WebhookDispatchOutboxRelay → HTTPS POST (기관 endpoint)
                                Option A(권장): 기관 WebhookInboundController 수신
                                Option B(대안): 기관이 GET /api/v1/events/poll 폴링
```

### 1.2 HandoffServiceImpl — Outbox 미사용 예외 케이스

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.handoff.HandoffServiceImpl
// 실행 메서드: issueHandoffTicket(...)
// 실행 시점: 인증 완료 후 기관 이동(Handoff) 티켓 발급 요청 시
//            (HTTP POST /api/v1/handoff/issue 등)
// 트랜잭션: @Transactional — DB 저장 + Kafka 발행이 하나의 트랜잭션으로 묶임
// DB: IdO PostgreSQL (ido 스키마) — ido.handoff_ticket 테이블 INSERT
// Kafka: ido.handoff.events 토픽에 직접 발행 (Outbox 경유 없음)
// 특이사항: 이 경로는 Outbox 패턴을 사용하지 않는 예외 케이스
// ═══════════════════════════════════════════════════════════════════
@Transactional
public HandoffTicket issueHandoffTicket(...) {
    // ① IdO PostgreSQL DB — ido.handoff_ticket 테이블 INSERT
    HandoffTicket ticket = handoffRepository.save(...);

    // ② Kafka ido.handoff.events 토픽에 직접 발행
    //    (Outbox 테이블을 거치지 않고 KafkaTemplate 직접 호출)
    kafkaTemplate.send(TOPIC_HANDOFF, correlationId, event);

    return ticket;
    // 트랜잭션 커밋: ticket INSERT 확정
    // 주의: Kafka 발행은 트랜잭션 외부이므로 커밋 후 Kafka 실패 시
    //       at-least-once 보장이 Spring Kafka 재시도 설정에 의존함
}
// ⚠️ 중요: 이 경우 Kafka 발행 실패 시 트랜잭션 롤백되므로 at-least-once 보장은
//          Spring Kafka의 재시도 설정에 의존함 (Outbox 패턴과는 다른 전략)
```

---

## 2. Outbox 레코드 공통 스키마 및 상태 전이

### 2.1 공통 컬럼 구조

모든 Outbox 테이블은 아래 컬럼 구조를 기본으로 따릅니다.

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 이 DDL은 직접 실행하는 SQL이 아니라 공통 컬럼 구조 참조용 정의입니다.
-- 실제 DDL은 부록 A 참조 (스키마별로 다름)
--   · ido.outbox, qsign.outbox → PostgreSQL (JSONB 타입)
--   · qim.outbox               → MariaDB (JSON 타입, DATETIME(6))
-- ═══════════════════════════════════════════════════════════════════
event_id        VARCHAR(36)  NOT NULL  -- UUID v7, PK
event_type      VARCHAR(80)  NOT NULL  -- 이벤트 분류명 (예: AUTH_COMPLETED)
partition_key   VARCHAR(300) NOT NULL  -- Kafka 파티션 결정 키
aggregate_id    VARCHAR(36)  NOT NULL  -- 도메인 집합 ID (qimUserId, authResultId 등)
event_version   BIGINT       NOT NULL  -- 단조 증가 버전 (낙관적 락 + 순서 보장)
payload         JSONB/JSON   NOT NULL  -- 이벤트 전체 직렬화 (JSON)
topic           VARCHAR(200) NOT NULL  -- 발행 대상 Kafka 토픽명
status          VARCHAR(20)  NOT NULL  DEFAULT 'PENDING'
retry_count     SMALLINT     NOT NULL  DEFAULT 0
error_message   TEXT                   -- 실패 시 최대 500자
created_at      TIMESTAMP    NOT NULL  DEFAULT NOW()
published_at    TIMESTAMP              -- 발행 성공 시각 (NULL이면 미발행)
```

### 2.2 상태 전이 다이어그램

#### Kafka 발행용 Outbox (ido.outbox, qsign.outbox, qim.outbox)

```
                      INSERT
                        │
                        ▼
                   ┌─────────┐
                   │ PENDING │  ← 기본 초기 상태
                   └────┬────┘
                        │ Relay가 findPendingBatch() 로 읽음
                        │ (FOR UPDATE SKIP LOCKED)
              ┌─────────┴──────────┐
              │ Kafka send()       │
              │ .whenComplete()    │
              ▼                   ▼
         [성공 콜백]          [실패 콜백]
              │                   │
              ▼                   ▼
        ┌──────────┐    retryCount < maxRetry?
        │PUBLISHED │         │           │
        └──────────┘        YES           NO
                             │             │
                             ▼             ▼
                    retry_count++    ┌──────────┐
                    status 유지      │  FAILED  │
                    (PENDING)        └──────────┘
                             │
                    [Q-IM 한정: relayFailedEvents()]
                    30초 후 FAILED → PENDING 복구 시도
                    (maxRetry 미만인 것만)
```

#### Webhook 발송용 Outbox (webhook_dispatch_outbox)

```
                 INSERT
                   │
                   ▼
              ┌─────────┐
              │ PENDING │
              └────┬────┘
                   │ next_retry_at <= NOW() 조건 만족
                   │ fetchPendingBatch() 로 읽음
                   │ (FOR UPDATE OF w SKIP LOCKED)
          ┌────────┴────────┐
          │ HTTP POST       │
          ▼                 ▼
     [2xx 응답]      [비2xx 또는 예외]
          │                 │
          ▼                 ├── 404/410 또는 retryCount >= maxRetry
    ┌──────────┐            │        ▼
    │DISPATCHED│            │   ┌──────────┐
    └──────────┘            │   │  FAILED  │
                            │   └──────────┘
                            │
                            └── retryCount < maxRetry
                                     │
                                     ▼
                            next_retry_at = NOW() + 2^(retryCount+1)s
                            retry_count++
                            status = PENDING (유지)
```

#### 프로비저닝 Outbox (provisioning_outbox)

```
                 INSERT
                   │
                   ▼
              ┌─────────┐
              │ PENDING │
              └────┬────┘
                   │ findPendingBatch()
                   │ (FOR UPDATE SKIP LOCKED)
          ┌────────┴────────┐
          │ HTTP POST       │
          ▼                 ▼
     [2xx 응답]       [비2xx 또는 예외]
          │                 │
          ▼                 ├── retryCount < maxRetry
    ┌──────────┐            │        ▼
    │COMPLETED │            │   incrementRetryWithBackoff()
    └──────────┘            │   next_retry_at = 1분/5분/30분
                            │
                            └── retryCount >= maxRetry
                                     │
                                     ▼
                              ┌─────────────┐
                              │ DEAD_LETTER │
                              └─────────────┘
```

---

## 3. Q-Sign팀 구현 가이드

### 3.1 담당 Outbox 테이블: `qsign.outbox`

**DB**: PostgreSQL `qsign` 스키마  
**파티션 키**: `identifierHash` (SHA-256(CI)) → 동일 사용자 순서 보장  
**목적지**: Kafka `qsign.auth.events`

### 3.2 INSERT 시점 — AuthServiceImpl에서

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-Sign 서버 (포트 8082)
// 실행 클래스: io.github.hipstermin.idem.gate.auth.AuthServiceImpl
// 실행 메서드: processAuthentication(AuthRequest)
// 실행 시점: 사용자 인증 요청 처리 시
//            (HTTP POST /api/v1/auth/verify 또는 /api/v1/auth/callback 등)
// 트랜잭션: @Transactional — 아래 DB 작업 2개가 하나의 트랜잭션으로 묶임
// ═══════════════════════════════════════════════════════════════════
@Transactional
public AuthResult processAuthentication(AuthRequest request) {

    // ① Q-Sign PostgreSQL DB (qsign 스키마) — qsign.auth_result 테이블 INSERT
    AuthResult authResult = doAuthentication(request);
    authResultRepository.save(authResult);

    // ② Q-Sign PostgreSQL DB (qsign 스키마) — qsign.outbox 테이블 INSERT
    // ← 트랜잭션 커밋 시 ①②가 동시에 영속화됨 (Outbox 패턴 핵심)
    QSignOutboxRecord outbox = QSignOutboxRecord.builder()
        .eventId(UuidV7.generate())                    // UUID v7
        .eventType(AuthEvent.TYPE_AUTH_COMPLETED)      // "AUTH_COMPLETED"
        .partitionKey(authResult.getIdentifierHash())  // SHA-256(CI) — Kafka 파티션 키
        .aggregateId(authResult.getAuthResultId())     // PK 참조
        .eventVersion(authResult.getVersion())         // 낙관적 락 버전
        .payload(serialize(buildAuthEvent(authResult)))// JSON 직렬화
        .topic("qsign.auth.events")                    // 목적지 토픽
        .build();
    // 초기 status = "PENDING", retryCount = 0 (QSignOutboxRecord 생성자에서 설정)

    outboxRepository.save(outbox);  // qsign.outbox INSERT

    return authResult;
    // 트랜잭션 커밋: auth_result + qsign.outbox 동시에 영속화
}
```

> **⚠️ 중요**: `authResultRepository.save()`와 `outboxRepository.save()`는 **반드시 같은 `@Transactional` 메서드 안**에 있어야 합니다. 순서는 auth_result 먼저, outbox 나중입니다.

### 3.3 페이로드 구성 — AuthEvent

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-Sign 서버 (포트 8082)
// 실행 클래스: io.github.hipstermin.idem.gate.auth.AuthServiceImpl (또는 AuthEventBuilder 유틸)
// 실행 메서드: buildAuthEvent(AuthResult) — processAuthentication() 내부에서 호출
// 실행 시점: qsign.outbox INSERT 직전, payload JSON 직렬화 시
// 참조 클래스: idem-common/io.github.hipstermin.idem.common.event.AuthEvent
// ═══════════════════════════════════════════════════════════════════

// AuthEvent 빌드 — Outbox payload에 JSON으로 직렬화되어 저장됨
// 이후 OutboxRelay가 Kafka qsign.auth.events 토픽으로 발행
AuthEvent event = AuthEvent.builder()
    .eventId(outbox.getEventId())          // Outbox와 동일한 UUID
    .eventType(AuthEvent.TYPE_AUTH_COMPLETED)
    .sourceSystem("q-sign")
    .correlationId(request.getCorrelationId())
    .qimUserId(authResult.getQimUserId())   // Q-IM 사용자 ID
    .eventVersion(authResult.getVersion())
    // AuthEvent 전용 필드
    .authResultId(authResult.getAuthResultId())
    .authLevel(authResult.getAuthLevel())   // LEVEL1 / LEVEL2 / LEVEL3
    .providerCode(authResult.getProviderCode()) // PASS / KAKAO / IPIN 등
    .providerTxId(authResult.getProviderTxId())
    .verificationResult(authResult.getVerificationResult())
    .build();

// 실패 케이스: AUTH_FAILED
AuthEvent failEvent = AuthEvent.builder()
    .eventType(AuthEvent.TYPE_AUTH_FAILED)
    ...
    .build();

// 잠금 케이스: AUTH_LOCKED (5회 오류 등)
AuthEvent lockEvent = AuthEvent.builder()
    .eventType(AuthEvent.TYPE_AUTH_LOCKED)
    ...
    .build();
```

### 3.4 Relay 동작 — OutboxRelay.java

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-Sign 서버 (포트 8082)
// 실행 클래스: io.github.hipstermin.idem.gate.outbox.OutboxRelay
// 실행 메서드: relay()
// 실행 시점: Spring @Scheduled — 이전 실행 완료 후 500ms마다 자동 실행
//            (fixedDelay: 이전 실행이 끝나야 다음 대기 시작 → 중복 실행 방지)
// 트랜잭션: @Transactional — findPendingBatch의 FOR UPDATE SKIP LOCKED가
//           트랜잭션 내에서 유효해야 하므로 필수
// DB: Q-Sign PostgreSQL (qsign 스키마) — qsign.outbox 테이블 조회/UPDATE
// Kafka: qsign.auth.events 토픽으로 비동기 발행 (KafkaTemplate.send)
// ═══════════════════════════════════════════════════════════════════
@Scheduled(fixedDelayString = "${qsign.outbox.relay-interval-ms:500}")
@Transactional  // SKIP LOCKED를 트랜잭션 내에서 실행
public void relay() {
    // Step 1: Q-Sign PostgreSQL qsign.outbox에서 PENDING 배치 조회 (기본 100건)
    //         → 내부적으로 findPendingBatch SQL 실행 (FOR UPDATE SKIP LOCKED)
    List<QSignOutboxRecord> pending = outboxRepository.findPendingBatch(batchSize);
    if (pending.isEmpty()) return;

    for (QSignOutboxRecord record : pending) {
        Object payload = objectMapper.readValue(record.getPayload(), AuthEvent.class);

        // Step 2: Kafka qsign.auth.events 토픽으로 비동기 발행
        //         partitionKey(identifierHash) → 동일 사용자 파티션 순서 보장
        qsignKafkaTemplate.send(authEventsTopic, record.getPartitionKey(), payload)
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    // Step 3a: 발행 성공 → Q-Sign PostgreSQL qsign.outbox UPDATE
                    //          status = 'PUBLISHED', published_at = NOW()
                    outboxRepository.markPublished(record.getEventId());
                } else {
                    // Step 3b: 발행 실패 → retry_count 확인 후 분기
                    int nextRetry = record.getRetryCount() + 1;
                    if (nextRetry >= maxRetry) {
                        // maxRetry(=3) 초과 → status = 'FAILED', error_message 저장
                        outboxRepository.markFailed(record.getEventId(), ex.getMessage());
                        // 알람 발생 필요
                    } else {
                        // maxRetry 미만 → retry_count++, status = PENDING 유지
                        outboxRepository.incrementRetry(record.getEventId(), ex.getMessage());
                        // PENDING 유지, 다음 릴레이 사이클에서 재시도
                    }
                }
            });
    }
}
```

#### `findPendingBatch` SQL (Q-Sign)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-Sign 서버 / OutboxRelay.relay()
-- 실행 방식: JPA NativeQuery (QSignOutboxRepository.findPendingBatch)
-- 실행 DB:   Q-Sign PostgreSQL (qsign 스키마) — qsign.outbox 테이블
-- 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
-- 목적:      PENDING 상태 레코드 배치 조회 + 행 잠금
-- FOR UPDATE SKIP LOCKED: 다중 인스턴스 동시 실행 시 같은 레코드 중복 처리 방지
--            → 다른 인스턴스가 이미 잠근 행은 건너뜀(SKIP)
-- ═══════════════════════════════════════════════════════════════════
SELECT event_id, event_type, partition_key, aggregate_id,
       event_version, payload::text, topic, status,
       retry_count, error_message, created_at, published_at
FROM qsign.outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT ?
FOR UPDATE SKIP LOCKED;
```

#### `markPublished` SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-Sign 서버 / OutboxRelay.relay() → whenComplete 성공 콜백
-- 실행 방식: JPA NativeQuery (QSignOutboxRepository.markPublished)
-- 실행 DB:   Q-Sign PostgreSQL (qsign 스키마) — qsign.outbox 테이블
-- 실행 시점: Kafka 발행 성공 콜백(whenComplete) 수신 직후
-- 목적:      Kafka 발행 완료 처리 — status를 PUBLISHED로 변경
-- ═══════════════════════════════════════════════════════════════════
UPDATE qsign.outbox
SET status = 'PUBLISHED',
    published_at = NOW()
WHERE event_id = ?;
```

#### `markFailed` SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-Sign 서버 / OutboxRelay.relay() → whenComplete 실패 콜백
-- 실행 방식: JPA NativeQuery (QSignOutboxRepository.markFailed)
-- 실행 DB:   Q-Sign PostgreSQL (qsign 스키마) — qsign.outbox 테이블
-- 실행 시점: Kafka 발행 실패 + retry_count >= maxRetry(=3) 조건 만족 시
-- 목적:      최대 재시도 초과 → 최종 실패 처리, 운영팀 알람 발생 대상
-- ═══════════════════════════════════════════════════════════════════
UPDATE qsign.outbox
SET status = 'FAILED',
    error_message = ?,    -- 최대 500자 잘라서 저장
    retry_count = retry_count + 1
WHERE event_id = ?;
```

#### `incrementRetry` SQL (PENDING 유지 + 카운트만 증가)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-Sign 서버 / OutboxRelay.relay() → whenComplete 실패 콜백
-- 실행 방식: JPA NativeQuery (QSignOutboxRepository.incrementRetry)
-- 실행 DB:   Q-Sign PostgreSQL (qsign 스키마) — qsign.outbox 테이블
-- 실행 시점: Kafka 발행 실패 + retry_count < maxRetry(=3) 조건 만족 시
-- 목적:      재시도 횟수 증가, status는 PENDING 유지 → 500ms 후 재발행 대상
-- ═══════════════════════════════════════════════════════════════════
UPDATE qsign.outbox
SET retry_count = retry_count + 1,
    error_message = ?
WHERE event_id = ?;
-- status는 PENDING 그대로 유지 → 다음 relay() 사이클에서 다시 조회됨
```

### 3.5 Q-Sign이 수신하는 Kafka Consumer

Q-Sign은 **Q-IM 사용자 이벤트**를 수신해서 계정 잠금/해제 처리를 합니다.

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-Sign 서버 (포트 8082)
// 실행 클래스: io.github.hipstermin.idem.gate.kafka.QimUserEventConsumer
// 실행 메서드: onUserEvent(ConsumerRecord, Acknowledgment)
// 실행 시점: Kafka qim.user.events 토픽에 새 메시지 도착 시 자동 호출
//            (Kafka Consumer 그룹: q-sign-qim-consumer)
// 트랜잭션: @Transactional
// DB: Q-Sign PostgreSQL (qsign 스키마) — auth_lock 테이블 UPDATE
//     Q-Sign PostgreSQL — qsign.processed_event (중복 방어 테이블) INSERT
//     Q-Sign PostgreSQL — qsign.last_event_version (버전 추적) UPSERT
// ═══════════════════════════════════════════════════════════════════
@KafkaListener(topics = "qim.user.events", groupId = "q-sign-qim-consumer")
@Transactional
public void onUserEvent(ConsumerRecord<String, UserEvent> record, Acknowledgment ack) {
    UserEvent event = record.value();

    // 1. 중복 확인
    //    → Q-Sign PostgreSQL qsign.processed_event 테이블 조회
    if (idempotentEventStore.isAlreadyProcessed(event.getEventId(), CONSUMER_GROUP)) {
        ack.acknowledge();
        return;
    }

    // 2. 버전 역전 확인 (오래된 이벤트 무시)
    //    → Q-Sign PostgreSQL qsign.last_event_version 테이블 조회
    if (idempotentEventStore.isVersionOutdated(event.getQimUserId(), event.getEventVersion())) {
        // 이미 더 최신 버전을 처리했으므로 스킵 마킹 후 ACK
        idempotentEventStore.markProcessed(event.getEventId(), CONSUMER_GROUP, event.getEventType(), "SKIPPED");
        ack.acknowledge();
        return;
    }

    // 3. 비즈니스 로직 처리
    //    → Q-Sign PostgreSQL qsign.auth_lock 테이블 UPDATE
    switch (event.getEventType()) {
        case "USER_SUSPENDED", "USER_WITHDRAWN" -> lockAllByQimUserId(event.getQimUserId());
        case "USER_UPDATED" -> { if (event.isNeedsSync()) unlockAllByQimUserId(event.getQimUserId()); }
        case "USER_MERGED" -> unlockAllByQimUserId(event.getQimUserId());
    }

    // 4. 완료 마킹 + 버전 갱신 + ACK
    //    → Q-Sign PostgreSQL qsign.processed_event 테이블 INSERT
    idempotentEventStore.markProcessed(event.getEventId(), CONSUMER_GROUP, event.getEventType(), "OK");
    //    → Q-Sign PostgreSQL qsign.last_event_version 테이블 UPSERT
    idempotentEventStore.updateLastVersion(event.getQimUserId(), event.getEventId(), event.getEventVersion());
    ack.acknowledge();
}
```

---

## 4. Q-IM팀 구현 가이드

### 4.1 담당 Outbox 테이블: `qim.outbox`

**DB**: MariaDB `qim` 스키마  
**파티션 키**: `qimUserId` → 동일 사용자 이벤트 순서 보장  
**목적지**: Kafka `qim.user.events` (기본), `qim.user.snapshot` (스냅샷)  
**구현 방식**: JPA (OutboxJpaEntity + OutboxJpaRepository)

### 4.2 INSERT 시점 — OutboxService.publishInTx() 호출

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.user.UserRegService (회원 가입 예시)
// 실행 메서드: registerUser(RegisterRequest)
// 실행 시점: 회원 가입 API 처리 시 (HTTP POST /api/v1/users 등)
// 트랜잭션: @Transactional — 아래 DB 작업 2개가 하나의 트랜잭션으로 묶임
// ═══════════════════════════════════════════════════════════════════
@Transactional
public QimUser registerUser(RegisterRequest request) {

    // ① Q-IM MariaDB (qim 스키마) — qim.user 테이블 INSERT
    QimUser user = qimUserRepository.save(
        QimUser.builder()
            .qimUserId(UuidV7.generate())
            .identifierHash(computeIdentifierHash(request.getCi()))
            .status(UserStatus.ACTIVE)
            .eventVersion(1L)  // 최초 버전
            .build()
    );

    // ② [같은 트랜잭션] Outbox 적재
    //    OutboxService.publishInTx()는 PROPAGATION.REQUIRED로 현재 트랜잭션에 참여
    //    → Q-IM MariaDB (qim 스키마) — qim.outbox 테이블 INSERT
    DomainEvent event = UserRegisteredEvent.builder()
        .eventId(UuidV7.generate())
        .eventType("USER_REGISTERED")
        .sourceSystem("q-im")
        .correlationId(request.getCorrelationId())
        .qimUserId(user.getQimUserId())
        .eventVersion(user.getEventVersion())
        .userStatus(user.getStatus().name())
        .changeReason("신규 가입")
        .needsSync(false)
        .build();

    outboxService.publishInTx(event);  // qim.outbox INSERT (아래 메서드 참조)

    return user;
    // 트랜잭션 커밋: qim.user + qim.outbox 동시에 영속화
}

// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.outbox.OutboxServiceImpl
// 실행 메서드: publishInTx(DomainEvent)
// 실행 시점: UserRegService.registerUser() 또는 UserMgmtService 내 @Transactional 메서드 내부
//            PROPAGATION.REQUIRED → 호출자의 트랜잭션에 그대로 참여 (새 트랜잭션 시작 안 함)
// DB: Q-IM MariaDB (qim 스키마) — qim.outbox 테이블 INSERT (JPA)
// ═══════════════════════════════════════════════════════════════════
@Transactional  // PROPAGATION.REQUIRED — 호출자 트랜잭션에 참여
public void publishInTx(DomainEvent event) {
    OutboxRecord record = OutboxRecord.builder()
        .eventId(event.getEventId())
        .eventType(event.getEventType())
        .partitionKey(event.getQimUserId())   // Kafka 파티션 키 = qimUserId
        .eventVersion(event.getEventVersion())
        .payload(serialize(event))
        .status(OutboxRecord.OutboxStatus.PENDING)
        .build();

    // Q-IM MariaDB qim.outbox 테이블 INSERT (JPA save → OutboxJpaRepository)
    outboxRepository.save(record);
    // → OutboxJpaRepository.save(toEntity(record)) → MariaDB INSERT
}
```

### 4.3 JPA 엔티티 주요 설정

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.infrastructure.jpa.entity.OutboxJpaEntity
// 실행 메서드: onCreate() — JPA @PrePersist 콜백
// 실행 시점: outboxRepository.save() 호출 시 JPA가 INSERT 직전에 자동 호출
// DB: Q-IM MariaDB (qim 스키마) — qim.outbox 테이블
// 역할: INSERT 시 기본값 자동 설정 (null 체크 후 초기화)
// ═══════════════════════════════════════════════════════════════════
@Entity
@Table(name = "outbox")  // 스키마: qim
public class OutboxJpaEntity {

    @Column(name = "payload", columnDefinition = "JSON")  // MariaDB JSON 컬럼
    private String payload;

    @PrePersist
    protected void onCreate() {
        // qim.outbox INSERT 직전 JPA 콜백에서 자동 실행
        if (createdAt == null) createdAt = Instant.now();
        if (status == null)    status = "PENDING";       // 초기 상태 설정
        if (retryCount == null) retryCount = 0;          // 초기 재시도 횟수
        if (topic == null)     topic = "qim.user.events"; // 기본 발행 토픽
    }
}
```

### 4.4 Relay 동작 — OutboxServiceImpl

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.outbox.OutboxServiceImpl
//
// [메서드 1] relayPendingEvents()
// 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
//            이전 실행 완료 후 500ms 대기 → 중복 실행 방지
// DB: Q-IM MariaDB (qim 스키마) — qim.outbox 조회 (findPending)
//
// [메서드 2] relayFailedEvents()
// 실행 시점: @Scheduled fixedDelay=30초 마다 자동 실행
// DB: Q-IM MariaDB (qim 스키마) — qim.outbox FAILED 레코드 조회 + PENDING 복구
//
// Kafka: qim.user.events 토픽으로 비동기 발행 (KafkaTemplate.send)
// ═══════════════════════════════════════════════════════════════════

// [500ms 폴링] PENDING 이벤트 발행
@Scheduled(fixedDelayString = "${qim.outbox.relay-interval-ms:500}")
public void relayPendingEvents() {
    // Q-IM MariaDB qim.outbox에서 PENDING 레코드 최대 100건 조회
    // (내부적으로 findPending SQL 실행 — FOR UPDATE SKIP LOCKED)
    List<OutboxRecord> pending = outboxRepository.findPending(100);
    for (OutboxRecord record : pending) {
        sendToKafka(record);
    }
}

// [30초 폴링] FAILED 이벤트 재시도
@Scheduled(fixedDelayString = "${qim.outbox.retry-interval-ms:30000}")
public void relayFailedEvents() {
    // Q-IM MariaDB qim.outbox에서 FAILED + retry_count < maxRetry(=5) 레코드 조회
    List<OutboxRecord> retryable = outboxRepository.findRetryable(maxRetry, 50);
    for (OutboxRecord record : retryable) {
        // FAILED → PENDING 복구 (Q-IM MariaDB qim.outbox UPDATE)
        outboxRepository.markPending(record.getEventId());
        sendToKafka(record);  // 즉시 재발행 시도
    }
}

// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.outbox.OutboxServiceImpl
// 실행 메서드: sendToKafka(OutboxRecord)
// 실행 시점: relayPendingEvents() 또는 relayFailedEvents() 내부에서 호출
// Kafka: qim.user.events 토픽으로 비동기 발행
// DB: 성공 시 qim.outbox markPublished UPDATE
//     실패 시 qim.outbox markFailed UPDATE
// ═══════════════════════════════════════════════════════════════════
private void sendToKafka(OutboxRecord record) {
    // Kafka qim.user.events 토픽으로 비동기 발행
    // partitionKey = qimUserId → 동일 사용자 이벤트 순서 보장
    kafkaTemplate.send("qim.user.events", record.getPartitionKey(), record.getPayload())
        .whenComplete((result, ex) -> {
            if (ex == null) {
                // 발행 성공 → Q-IM MariaDB qim.outbox UPDATE (status=PUBLISHED)
                outboxRepository.markPublished(record.getEventId());
                // GAP-QIM-05: 스냅샷 발행 트리거 (10개 이벤트마다)
                triggerSnapshotIfNeeded(record);
            } else {
                String errorMsg = buildErrorMessage(ex);  // 최대 500자
                // 발행 실패 → Q-IM MariaDB qim.outbox UPDATE (status=FAILED)
                outboxRepository.markFailed(record.getEventId(), errorMsg);
                // maxRetry 도달 시 ERROR 로그 + 알람 필요
            }
        });
}
```

#### `findPending` SQL (Q-IM — JPA Native Query)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-IM 서버 / OutboxServiceImpl.relayPendingEvents()
-- 실행 방식: JPA NativeQuery (OutboxJpaRepository.findPendingNative)
-- 실행 DB:   Q-IM MariaDB (qim 스키마) — qim.outbox 테이블
-- 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
-- 목적:      PENDING 상태 레코드 배치 조회 + 행 잠금
-- FOR UPDATE SKIP LOCKED: 다중 인스턴스 동시 실행 시 중복 처리 방지
--            MariaDB 8.0+ 지원 (InnoDB)
-- ═══════════════════════════════════════════════════════════════════
SELECT event_id, event_type, partition_key, aggregate_id,
       event_version, payload, topic, status,
       retry_count, error_message, created_at, published_at
FROM qim.outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT :limit
FOR UPDATE SKIP LOCKED
```

#### `findRetryable` SQL (FAILED 재시도 대상)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-IM 서버 / OutboxServiceImpl.relayFailedEvents()
-- 실행 방식: JPA NativeQuery (OutboxJpaRepository.findRetryable)
-- 실행 DB:   Q-IM MariaDB (qim 스키마) — qim.outbox 테이블
-- 실행 시점: @Scheduled fixedDelay=30초 마다 자동 실행
-- 목적:      maxRetry(=5) 미만의 FAILED 레코드 조회 → PENDING 복구 후 재발행
-- ═══════════════════════════════════════════════════════════════════
SELECT * FROM qim.outbox
WHERE status = 'FAILED'
  AND retry_count < :maxRetry
ORDER BY created_at ASC
```

#### `markPending` SQL (FAILED → PENDING 복구)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: Q-IM 서버 / OutboxServiceImpl.relayFailedEvents()
-- 실행 방식: JPA NativeQuery (OutboxJpaRepository.markPending)
-- 실행 DB:   Q-IM MariaDB (qim 스키마) — qim.outbox 테이블
-- 실행 시점: findRetryable()로 조회한 각 FAILED 레코드에 대해
--            sendToKafka() 호출 직전 실행
-- 목적:      FAILED 상태를 PENDING으로 복구 → Relay가 재발행 시도
-- ═══════════════════════════════════════════════════════════════════
UPDATE qim.outbox
SET status = 'PENDING'
WHERE event_id = ?;
```

### 4.5 스냅샷 발행 (GAP-QIM-05)

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.outbox.OutboxServiceImpl
// 실행 메서드: triggerSnapshotIfNeeded(OutboxRecord)
// 실행 시점: sendToKafka() 내 Kafka 발행 성공 콜백(whenComplete) 수신 직후
// Kafka: 조건 충족 시 qim.user.snapshot 토픽으로 사용자 전체 상태 발행
// 조건: 마지막 스냅샷 이후 10개 이상 이벤트가 발행된 경우
// ═══════════════════════════════════════════════════════════════════
private void triggerSnapshotIfNeeded(OutboxRecord record) {
    String qimUserId = record.getPartitionKey();
    Long   version   = record.getEventVersion();

    // Q-IM MariaDB 조회 → 마지막 스냅샷 이후 이벤트 카운트 확인
    if (snapshotService.shouldPublishSnapshot(qimUserId, version)) {
        // Kafka qim.user.snapshot (Compacted Topic) 으로 사용자 전체 상태 발행
        snapshotService.publishSnapshot(qimUserId, version, null);
    }
    // 실패해도 비치명적 — 이벤트 발행 흐름에 영향 없음
}
```

### 4.6 이벤트 타입별 eventVersion 관리

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: Q-IM 서버 (포트 8081)
// 실행 클래스: io.github.hipstermin.idem.registry.user.UserMgmtService
// 실행 메서드: updateUser(String qimUserId, UpdateRequest)
// 실행 시점: 회원 정보 변경 API 처리 시
//            (HTTP PUT /api/v1/users/{qimUserId} 등)
// 트랜잭션: @Transactional — qim.user UPDATE + qim.outbox INSERT 원자적 처리
// DB: Q-IM MariaDB (qim 스키마) — qim.user UPDATE + qim.outbox INSERT
// ═══════════════════════════════════════════════════════════════════
@Transactional
public QimUser updateUser(String qimUserId, UpdateRequest request) {
    // Q-IM MariaDB qim.user 테이블 조회
    QimUser user = qimUserRepository.findById(qimUserId).orElseThrow();

    user.setStatus(request.getNewStatus());
    user.setEventVersion(user.getEventVersion() + 1);  // 반드시 증가 (단조 증가)
    // ... 변경 내용 적용
    // → Q-IM MariaDB qim.user 테이블 UPDATE (JPA dirty checking)

    DomainEvent event = UserEvent.builder()
        .eventVersion(user.getEventVersion())  // 새 버전 사용 (증가된 버전)
        .eventType("USER_UPDATED")
        .needsSync(request.isNeedsSync())
        ...
        .build();

    // Q-IM MariaDB qim.outbox 테이블 INSERT (같은 트랜잭션)
    outboxService.publishInTx(event);
    return user;
}
```

---

## 5. IdO팀 구현 가이드 — ido.outbox

### 5.1 담당 Outbox 테이블: `ido.outbox`

**DB**: PostgreSQL `ido` 스키마  
**파티션 키**: `identifierHash` (SHA-256(sub 또는 CI))  
**목적지**: Kafka `qsign.auth.events`  
**구현 방식**: JdbcTemplate (JSONB 타입)

### 5.2 INSERT 시점 — KeycloakOidcService.saveOutboxEvent()

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.broker.keycloak.KeycloakOidcService
// 실행 메서드: handleCallback(String code, String state) — 내부에서 saveOutboxEvent() 호출
// 실행 시점: Keycloak OIDC 인증 콜백 수신 시
//            (HTTP GET /callback?code=...&state=... — Keycloak 리디렉션)
// 트랜잭션: @Transactional — ido.auth_result INSERT + ido.outbox INSERT 원자적 처리
// DB: IdO PostgreSQL (ido 스키마) — ido.auth_result 테이블 + ido.outbox 테이블
// ═══════════════════════════════════════════════════════════════════
@Transactional
public CallbackResult handleCallback(String code, String state) {
    // ... (Step 1-9: 인증 처리, ido.auth_result 테이블 INSERT)

    // Step 10. Outbox 이벤트 저장 (auth_result 저장과 같은 트랜잭션)
    //          → saveOutboxEvent()가 IdO PostgreSQL ido.outbox에 INSERT
    saveOutboxEvent(authResultId, correlationId, authLevel, providerCode,
                    qimUserId, identifierHash);

    // ... (Step 11-13: FE 세션 생성, 로그)
}

// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.broker.keycloak.KeycloakOidcService
// 실행 메서드: saveOutboxEvent(...) — private 메서드
// 실행 시점: handleCallback() 의 @Transactional 내부 Step 10
// 실행 방식: JdbcTemplate.update() — 직접 SQL 실행 (JPA 미사용)
// DB: IdO PostgreSQL (ido 스키마) — ido.outbox 테이블 INSERT
// 주의: payload는 ?::jsonb 로 JSONB 타입 캐스팅 필수
// ═══════════════════════════════════════════════════════════════════
private void saveOutboxEvent(String authResultId, String correlationId,
                               String authLevel, String providerCode,
                               String qimUserId, String identifierHash) {
    String eventId = UuidV7.generate();

    // AuthEvent 빌드 — ido.outbox payload 컬럼에 JSON으로 저장됨
    AuthEvent authEvent = AuthEvent.builder()
        .eventId(eventId)
        .eventType(AuthEvent.TYPE_AUTH_COMPLETED)
        .sourceSystem(SOURCE_SYSTEM)       // "ido-keycloak"
        .correlationId(correlationId)
        .qimUserId(qimUserId)
        .eventVersion(1L)                  // Keycloak 경로는 단일 이벤트
        .authResultId(authResultId)
        .authLevel(AuthResult.AuthLevel.valueOf(authLevel))
        .providerCode(providerCode)
        .build();

    String payloadJson = objectMapper.writeValueAsString(authEvent);

    // IdO PostgreSQL ido.outbox 테이블 INSERT (JdbcTemplate 직접 실행)
    jdbcTemplate.update("""
        INSERT INTO ido.outbox (
            event_id, event_type, partition_key, aggregate_id,
            event_version, payload, topic, status, retry_count, created_at
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, NOW())
        """,
        eventId,
        AuthEvent.TYPE_AUTH_COMPLETED,
        identifierHash,     // partitionKey = identifierHash (SHA-256(sub 또는 CI))
        authResultId,       // aggregateId
        1L,                 // eventVersion
        payloadJson,        // payload — ::jsonb 캐스팅 필수!
        authEventsTopic     // "qsign.auth.events"
    );
}
```

> **⚠️ JSONB 캐스팅**: PostgreSQL JSONB 컬럼에 문자열을 INSERT할 때는 반드시 `?::jsonb` 형식으로 캐스팅해야 합니다. 누락 시 타입 불일치 오류.

### 5.3 IdoOutboxRelay — 핵심 구현

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.infrastructure.outbox.IdoOutboxRelay
// 실행 메서드: relay()
// 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
//            Feature Flag IDO_OUTBOX_RELAY_ENABLED=false 이면 즉시 return
// DB: IdO PostgreSQL (ido 스키마) — ido.outbox 조회/UPDATE (JdbcTemplate)
// Kafka: qsign.auth.events 토픽으로 비동기 발행 (KafkaTemplate.send)
// ═══════════════════════════════════════════════════════════════════
@Value("${ido.kafka.topic-auth-events:qsign.auth.events}")
private String authEventsTopic;

@Value("${IDO_OUTBOX_RELAY_ENABLED:true}")
private boolean relayEnabled;  // Feature Flag F-13

@Scheduled(fixedDelayString = "${ido.outbox.relay-interval-ms:500}")
public void relay() {
    // Feature Flag 체크 (IDO_OUTBOX_RELAY_ENABLED 환경변수)
    if (!relayEnabled) return;

    // IdO PostgreSQL ido.outbox에서 PENDING 배치 조회 (FOR UPDATE SKIP LOCKED)
    List<IdoOutboxRecord> pending = outboxRepository.findPendingBatch(batchSize);
    if (pending.isEmpty()) return;

    for (IdoOutboxRecord record : pending) {
        // Kafka qsign.auth.events 토픽으로 비동기 발행
        kafkaTemplate.send(record.getTopic(), record.getPartitionKey(), record.getPayload())
            .whenComplete((result, ex) -> {
                if (ex == null) {
                    // 성공 → IdO PostgreSQL ido.outbox UPDATE (status=PUBLISHED)
                    outboxRepository.markPublished(record.getEventId());
                } else {
                    if (record.getRetryCount() >= maxRetry) {
                        // 최대 재시도 초과 → ido.outbox UPDATE (status=FAILED)
                        outboxRepository.markFailed(record.getEventId(), ex.getMessage());
                    } else {
                        // 재시도 중 → ido.outbox UPDATE (retry_count++)
                        outboxRepository.incrementRetry(record.getEventId(), ex.getMessage());
                    }
                }
            });
    }
}
```

#### `findPendingBatch` SQL (ido.outbox)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / IdoOutboxRelay.relay()
-- 실행 방식: JdbcTemplate (IdoOutboxRepository.findPendingBatch)
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.outbox 테이블
-- 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
-- 목적:      PENDING 상태 레코드 배치 조회 + 행 잠금 (중복 발행 방지)
-- payload::text: JSONB 컬럼을 JdbcTemplate String으로 받기 위한 text 변환 필수
-- FOR UPDATE SKIP LOCKED: 다중 IdO 인스턴스 배포 시 중복 처리 방지
-- ═══════════════════════════════════════════════════════════════════
SELECT event_id, event_type, partition_key, aggregate_id,
       event_version, payload::text,  -- JSONB → text 변환 필수
       topic, status,
       retry_count, error_message, created_at, published_at
FROM ido.outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC
LIMIT ?
FOR UPDATE SKIP LOCKED;
```

> **⚠️ `payload::text`**: 조회 시 JSONB 컬럼은 `payload::text`로 읽어야 JdbcTemplate에서 String으로 받을 수 있습니다.

---

## 6. IdO팀 구현 가이드 — webhook_dispatch_outbox

### 6.1 INSERT 시점 — WebhookDispatcherService.insertOutbox()

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService
// 실행 메서드: insertOutbox(...) — private 메서드
//              enqueueForHandoffEvent() / enqueueForMemberLookupResult() 등에서 호출
// 실행 시점: HandoffEventConsumer.onHandoffEvent() 가 HANDOFF_ISSUED 이벤트 처리 시
//            → 각 대상 기관마다 1건씩 호출됨
// 트랜잭션: HandoffEventConsumer의 @Transactional 내에서 실행 (PROPAGATION.REQUIRED)
// DB: IdO PostgreSQL (ido 스키마) — ido.webhook_dispatch_outbox 테이블 INSERT
// ON CONFLICT DO NOTHING: 멱등성 보장 (Consumer 재실행 시 중복 INSERT 방지)
// ═══════════════════════════════════════════════════════════════════
private boolean insertOutbox(AgencyWebhookConfig config,
                              String sourceEventId, String sourceEventType,
                              String sourceTopic, String payloadJson,
                              String correlationId) {
    int updated = jdbcTemplate.update("""
        INSERT INTO ido.webhook_dispatch_outbox (
            dispatch_id, agency_code, endpoint_url,
            source_event_id, source_event_type, source_topic,
            correlation_id, payload, status,
            retry_count, max_retry, next_retry_at, created_at
        ) VALUES (?,?,?, ?,?,?, ?,?::jsonb,'PENDING', 0,?, NOW(),NOW())
        ON CONFLICT (source_event_id, agency_code) DO NOTHING
        -- ↑ (source_event_id, agency_code) UNIQUE 제약 — 동일 이벤트+기관 중복 INSERT 무시
        """,
        UuidV7.generate(),            // dispatch_id (PK)
        config.agencyCode(),
        config.endpointUrl(),
        sourceEventId,                // Handoff 이벤트 ID (ido.handoff.events 원본 ID)
        sourceEventType,              // HANDOFF_ISSUED 등
        sourceTopic,                  // ido.handoff.events
        correlationId,
        payloadJson,                  // ::jsonb 캐스팅 (PII 제외 페이로드)
        config.maxRetry()             // 기관별 설정 (기본 3)
    );

    // ON CONFLICT → 중복 INSERT 무시 (멱등성 보장)
    // updated=0: 이미 존재 (중복), updated=1: 신규 INSERT
    return updated > 0;
}
```

> **ON CONFLICT (source_event_id, agency_code) DO NOTHING**: HandoffEventConsumer가 재실행되더라도 동일 기관에 중복 webhook이 발송되지 않습니다.

### 6.2 WebhookDispatchOutboxRelay — 지수 백오프

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.webhook.WebhookDispatchOutboxRelay
// 실행 메서드: relay()
// 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
//            Feature Flag IDO_WEBHOOK_RELAY_ENABLED=false 이면 즉시 return
// DB: IdO PostgreSQL (ido 스키마) — ido.webhook_dispatch_outbox 조회/UPDATE
//     + ido.agency_webhook_config 조회 (기관 서명 키, 타임아웃 설정)
// 외부 통신: 기관 HTTPS 엔드포인트로 HTTP POST (RestTemplate 또는 HttpClient)
// ═══════════════════════════════════════════════════════════════════
private static final long BACKOFF_BASE_SEC = 2L;

@Scheduled(fixedDelayString = "${ido.webhook.relay-interval-ms:500}")
@Transactional
public void relay() {
    if (!relayEnabled) return;  // Feature Flag F-14 (IDO_WEBHOOK_RELAY_ENABLED)

    // IdO PostgreSQL ido.webhook_dispatch_outbox + ido.agency_webhook_config 조회
    // (fetchPendingBatch SQL 참조 — next_retry_at <= NOW() 조건 포함)
    List<WebhookDispatchRecord> batch = fetchPendingBatch();
    for (WebhookDispatchRecord record : batch) {
        dispatchWebhook(record);
    }
}

// ═══════════════════════════════════════════════════════════════════
// 실행 메서드: dispatchWebhook(WebhookDispatchRecord)
// 실행 시점: relay() 내부 루프에서 레코드별 호출
// 외부 통신: record.getEndpointUrl() 로 HTTPS POST
//            헤더: X-Webhook-Signature (HMAC-SHA256), X-OnePass-Version
// DB: 성공 → ido.webhook_dispatch_outbox markDispatched UPDATE
//     실패 → handleFailure() → scheduleRetry 또는 markFailed UPDATE
// ═══════════════════════════════════════════════════════════════════
private void dispatchWebhook(WebhookDispatchRecord record) {
    try {
        // HMAC-SHA256 서명 생성 (위변조 방지)
        String signature = webhookDispatcherService.computeHmacSignature(
            record.getPayload(), record.getSigningSecret());

        // 기관 HTTPS 엔드포인트로 POST 전송
        ResponseEntity<String> response = httpClient.post(
            record.getEndpointUrl(),
            record.getPayload(),
            Map.of("X-Webhook-Signature", signature,
                   "X-OnePass-Version", platformVersion)
        );

        if (response.getStatusCode().is2xxSuccessful()) {
            // 성공 → IdO PostgreSQL ido.webhook_dispatch_outbox UPDATE (status=DISPATCHED)
            markDispatched(record.getDispatchId());
        } else {
            handleFailure(record, "HTTP " + response.getStatusCode().value());
        }
    } catch (Exception e) {
        handleFailure(record, e.getMessage());
    }
}

// ═══════════════════════════════════════════════════════════════════
// 실행 메서드: handleFailure(WebhookDispatchRecord, String)
// 실행 시점: dispatchWebhook() 내 HTTP 실패 또는 예외 발생 시
// DB: retry 가능 → ido.webhook_dispatch_outbox scheduleRetry UPDATE
//     retry 초과  → ido.webhook_dispatch_outbox markFailed UPDATE
// 지수 백오프: 2^(retryCount+1) 초 (2초 → 4초 → 8초 → ...)
// ═══════════════════════════════════════════════════════════════════
private void handleFailure(WebhookDispatchRecord record, String error) {
    int nextRetry = record.getRetryCount() + 1;

    if (nextRetry >= record.getMaxRetry()) {
        // 최대 재시도 초과 → status=FAILED, 운영 알람 대상
        markFailed(record.getDispatchId(), error);
    } else {
        // 지수 백오프 계산: 2^(retryCount+1) 초 후 재시도
        long backoffSec = (long) Math.pow(BACKOFF_BASE_SEC, record.getRetryCount() + 1);
        // IdO PostgreSQL ido.webhook_dispatch_outbox UPDATE (next_retry_at 설정)
        scheduleRetry(record.getDispatchId(), backoffSec, error);
    }
}
```

#### `fetchPendingBatch` SQL (webhook_dispatch_outbox)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / WebhookDispatchOutboxRelay.relay() → fetchPendingBatch()
-- 실행 방식: JdbcTemplate (WebhookDispatchOutboxRepository.fetchPendingBatch)
-- 실행 DB:   IdO PostgreSQL (ido 스키마)
--            ido.webhook_dispatch_outbox w (메인 테이블)
--            ido.agency_webhook_config c (기관 설정 JOIN)
-- 실행 시점: @Scheduled fixedDelay=500ms 마다 자동 실행
-- 목적:      PENDING + next_retry_at 조건 만족 레코드 조회 + 행 잠금
-- next_retry_at <= NOW(): 지수 백오프 대기 시간이 지난 레코드만 조회
-- FOR UPDATE OF w SKIP LOCKED: w 테이블 행만 잠금 (c 테이블은 잠금 제외)
-- ═══════════════════════════════════════════════════════════════════
SELECT w.dispatch_id, w.agency_code, w.endpoint_url,
       w.source_event_id, w.source_event_type,
       w.correlation_id, w.payload::text,
       w.status, w.retry_count, w.max_retry,
       w.next_retry_at, w.created_at,
       c.signing_secret_hash, c.connect_timeout_ms, c.read_timeout_ms
FROM ido.webhook_dispatch_outbox w
LEFT JOIN ido.agency_webhook_config c
       ON c.agency_code = w.agency_code AND c.active = TRUE
WHERE w.status = 'PENDING'
  AND (w.next_retry_at IS NULL OR w.next_retry_at <= NOW())
ORDER BY w.created_at ASC
LIMIT ?
FOR UPDATE OF w SKIP LOCKED  -- w 테이블만 잠금 (c 테이블 제외)
```

#### `markDispatched` SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / WebhookDispatchOutboxRelay.markDispatched()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.webhook_dispatch_outbox 테이블
-- 실행 시점: 기관 HTTPS 엔드포인트에서 2xx 응답 수신 직후
-- 목적:      Webhook 발송 성공 처리 — status=DISPATCHED, dispatched_at 기록
-- ═══════════════════════════════════════════════════════════════════
UPDATE ido.webhook_dispatch_outbox
SET status = 'DISPATCHED',
    dispatched_at = NOW()
WHERE dispatch_id = ?;
```

#### `scheduleRetry` SQL (지수 백오프)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / WebhookDispatchOutboxRelay.handleFailure() → scheduleRetry()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.webhook_dispatch_outbox 테이블
-- 실행 시점: HTTP 실패 + retry_count < maxRetry 조건 만족 시
-- 목적:      지수 백오프 적용 — next_retry_at = NOW() + 2^(retryCount+1) 초
--            status는 PENDING 유지 → 지정된 시간 이후 fetchPendingBatch에서 재조회
-- ═══════════════════════════════════════════════════════════════════
UPDATE ido.webhook_dispatch_outbox
SET retry_count = retry_count + 1,
    next_retry_at = NOW() + INTERVAL '?' SECOND,  -- 백오프 초 (Java에서 계산하여 전달)
    error_message = ?
WHERE dispatch_id = ?;
-- status는 PENDING 유지 → next_retry_at 이후 재시도 대상
```

#### `markFailed` SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / WebhookDispatchOutboxRelay.handleFailure() → markFailed()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.webhook_dispatch_outbox 테이블
-- 실행 시점: HTTP 실패 + retry_count >= maxRetry 조건 만족 시
-- 목적:      최대 재시도 초과 → 최종 실패 처리, 운영팀 알람 발생 대상
-- ═══════════════════════════════════════════════════════════════════
UPDATE ido.webhook_dispatch_outbox
SET status = 'FAILED',
    retry_count = retry_count + 1,
    error_message = ?
WHERE dispatch_id = ?;
```

### 6.3 Webhook 페이로드 보안 요건

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.webhook.WebhookDispatcherService
// 실행 메서드: buildHandoffWebhookPayload(HandoffEvent, AgencyWebhookConfig, String)
// 실행 시점: insertOutbox() 호출 직전, 기관에 전달할 Webhook 페이로드 JSON 생성 시
// 주의: PII(개인식별정보) 포함 금지 — ticketId, eventType, correlationId만 포함
// ═══════════════════════════════════════════════════════════════════
private String buildHandoffWebhookPayload(HandoffEvent event,
                                           AgencyWebhookConfig config,
                                           String correlationId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventType",     event.getEventType());     // HANDOFF_ISSUED
    payload.put("ticketId",      event.getTicketId());      // 검증용 티켓 ID
    payload.put("agencyCode",    config.agencyCode());      // 수신 기관
    payload.put("correlationId", correlationId);
    payload.put("timestamp",     Instant.now().toEpochMilli());
    payload.put("version",       platformVersion);

    // ✅ 포함 가능: ticketId, eventType, correlationId, timestamp
    // ❌ 포함 금지: qimUserId 원본, CI/DN 원본값, 이름/연락처 등 PII

    return objectMapper.writeValueAsString(payload);
}
```

---

## 7. IdO팀 구현 가이드 — provisioning_outbox

### 7.1 담당 Outbox 테이블: `ido.provisioning_outbox`

**목적**: 신규 가입 사용자 정보를 연계된 기관(최대 68개)에 일괄 HTTP 등록  
**상태**: PENDING → COMPLETED / DEAD_LETTER  
**백오프**: 1분 → 5분 → 30분 (DB CASE WHEN으로 계산)

### 7.2 INSERT 시점 — 회원 가입 이벤트 수신 시

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: io.github.hipstermin.idem.hub.provision.ProvisioningServiceImpl
// 실행 메서드: provisionToAllAgencies(String qimUserId, String sourceEventId, String correlationId)
// 실행 시점: Q-IM qim.user.events 토픽에서 USER_REGISTERED 이벤트 수신 시
//            QimSpMemberEventConsumer 또는 별도 Consumer가 이 메서드 호출
// 트랜잭션: @Transactional — 최대 68건의 INSERT가 하나의 트랜잭션으로 처리됨
// DB: IdO PostgreSQL (ido 스키마) — ido.provisioning_outbox 테이블 INSERT (최대 68건)
//     ON CONFLICT (idempotency_key, agency_code) DO NOTHING: 멱등성 보장
// 외부 통신: 직접 없음 (ProvisioningOutboxRelay가 별도로 HTTP POST 처리)
// ═══════════════════════════════════════════════════════════════════
@Transactional
public void provisionToAllAgencies(String qimUserId, String sourceEventId,
                                    String correlationId) {
    // IdO PostgreSQL ido.agency_endpoint 테이블 조회 (PROVISIONING 타입 기관 목록)
    List<AgencyEndpoint> agencies = agencyEndpointRegistry
        .findAllByType("PROVISIONING");

    // Q-IM 사용자 정보 조회 → PII 최소화된 프로비저닝 페이로드 생성
    String payloadJson = buildProvisioningPayload(qimUserId);
    String idempotencyKey = UuidV7.generate();  // 기관 횡단 공유 키

    // 각 기관별로 1건씩 INSERT (최대 68건, 같은 트랜잭션)
    for (AgencyEndpoint agency : agencies) {
        jdbcTemplate.update("""
            INSERT INTO ido.provisioning_outbox (
                id, qim_user_id, agency_code, event_type,
                payload_json, idempotency_key, status,
                retry_count, max_retry, correlation_id,
                source_event_id, created_at
            ) VALUES (?,?,?, ?,?,?,'PENDING',
                      0, 3, ?, ?, NOW())
            ON CONFLICT (idempotency_key, agency_code) DO NOTHING
            """,
            UuidV7.generate(),          // id (PK)
            qimUserId,
            agency.getAgencyCode(),
            "USER_REGISTERED",
            payloadJson,
            idempotencyKey,             // (idempotency_key, agency_code) UNIQUE 키
            correlationId,
            sourceEventId               // Q-IM 원본 이벤트 ID 추적용
        );
    }
}
```

### 7.3 백오프 SQL (CASE WHEN 방식)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / ProvisioningOutboxRelay.relay()
--            → ProvisioningOutboxRepositoryImpl.incrementRetryWithBackoff()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.provisioning_outbox 테이블
-- 실행 시점: 기관 HTTPS POST 실패 + retry_count < maxRetry(=3) 조건 만족 시
-- 목적:      재시도 횟수 증가 + 다음 재시도 시각 설정 (단계별 백오프)
--            - 1회 실패: 1분 후 재시도
--            - 2회 실패: 5분 후 재시도
--            - 3회+ 실패: 30분 후 재시도 (이 이후는 DEAD_LETTER)
-- ═══════════════════════════════════════════════════════════════════
-- ProvisioningOutboxRepositoryImpl.incrementRetryWithBackoff()
UPDATE ido.provisioning_outbox
SET retry_count       = retry_count + 1,
    error_message     = ?,
    last_attempted_at = NOW(),
    next_retry_at = CASE
        WHEN retry_count = 0 THEN NOW() + INTERVAL '1 minute'   -- 1회 실패: 1분 후
        WHEN retry_count = 1 THEN NOW() + INTERVAL '5 minutes'  -- 2회 실패: 5분 후
        ELSE                      NOW() + INTERVAL '30 minutes' -- 3회+ 실패: 30분 후
    END
WHERE id = ?;
```

#### `markCompleted` SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / ProvisioningOutboxRelay.relay()
--            → ProvisioningOutboxRepositoryImpl.markCompleted()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.provisioning_outbox 테이블
-- 실행 시점: 기관 HTTPS POST 성공 (2xx 응답) 수신 직후
-- 목적:      프로비저닝 완료 처리 — status=COMPLETED, completed_at 기록
-- ═══════════════════════════════════════════════════════════════════
UPDATE ido.provisioning_outbox
SET status       = 'COMPLETED',
    completed_at = NOW(),
    last_attempted_at = NOW()
WHERE id = ?;
```

#### `markDeadLetter` SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: IdO 서버 / ProvisioningOutboxRelay.relay()
--            → ProvisioningOutboxRepositoryImpl.markDeadLetter()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — ido.provisioning_outbox 테이블
-- 실행 시점: 기관 HTTPS POST 실패 + retry_count >= maxRetry(=3) 조건 만족 시
-- 목적:      최대 재시도 초과 → DEAD_LETTER 처리, 수동 복구 또는 운영 알람 대상
-- ═══════════════════════════════════════════════════════════════════
UPDATE ido.provisioning_outbox
SET status        = 'DEAD_LETTER',
    error_message = ?,
    last_attempted_at = NOW()
WHERE id = ?;
```

---

## 8. Kafka Consumer 구현 패턴

### 8.1 표준 Consumer 구현 템플릿

```java
// ═══════════════════════════════════════════════════════════════════
// 이 코드는 Consumer 구현의 표준 템플릿입니다.
// 실제 적용 클래스:
//   · QsignAuthEventConsumer (IdO 서버) — qsign.auth.events 수신
//   · HandoffEventConsumer (IdO 서버) — ido.handoff.events 수신
//   · QimUserEventConsumer (Q-Sign 서버) — qim.user.events 수신
//   · QimSpMemberEventConsumer (IdO 서버) — qim.sp.member.events 수신
//
// 실행 시점: 해당 Kafka 토픽에 새 메시지 도착 시 Kafka Consumer 쓰레드가 자동 호출
// DB: idempotent_event 테이블 조회/INSERT (중복 방어)
//     processEvent() 내부에서 비즈니스 DB 처리
// ═══════════════════════════════════════════════════════════════════
@KafkaListener(
    topics           = "${topic-name}",
    groupId          = "${consumer-group}",
    containerFactory = "specificListenerContainerFactory"
)
public void consume(ConsumerRecord<String, EventType> record, Acknowledgment ack) {
    EventType event = record.value();

    // ① null 체크 (역직렬화 실패 또는 tombstone 메시지 처리)
    if (event == null) {
        log.warn("null 이벤트 수신 스킵: partition={} offset={}",
                 record.partition(), record.offset());
        ack.acknowledge();
        return;
    }

    String eventId = event.getEventId();

    try {
        // ② 멱등 체크 (중복 처리 방지)
        //    → {서비스} DB idempotent_event 테이블 조회 (SELECT COUNT > 0)
        if (idempotentEventStore.isAlreadyProcessed(eventId, CONSUMER_GROUP)) {
            log.debug("중복 이벤트 스킵: eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        // ③ 비즈니스 로직 처리
        //    → 서비스별로 다름 (Redis 캐시 업데이트, DB 상태 변경, Outbox INSERT 등)
        processEvent(event);

        // ④ 처리 완료 마킹
        //    → {서비스} DB idempotent_event 테이블 INSERT
        //    (ON CONFLICT DO NOTHING — 재진입 방어)
        idempotentEventStore.markProcessed(eventId, CONSUMER_GROUP,
                                            event.getEventType(), "OK");

    } catch (Exception e) {
        log.error("처리 실패: eventId={} error={}", eventId, e.getMessage(), e);
        throw e;  // Spring Kafka 재시도/DLQ에 위임
    } finally {
        ack.acknowledge();  // 항상 ACK (재시도는 Spring이 관리)
    }
}
```

### 8.2 Consumer 그룹별 처리 목적

| Consumer 클래스 | 그룹 ID | 구독 토픽 | 처리 목적 |
|----------------|--------|---------|---------|
| `QsignAuthEventConsumer` (IdO) | `ido-qsign-consumer` | `qsign.auth.events` | Auth 완료 → Redis Pre-warming |
| `HandoffEventConsumer` (IdO) | `ido-handoff-consumer` | `ido.handoff.events` | Handoff 발급 → 기관 Webhook 큐 등록 |
| `QimUserEventConsumer` (Q-Sign) | `q-sign-qim-consumer` | `qim.user.events` | 탈퇴/정지 → auth_lock 처리 |
| `QimSpMemberEventConsumer` (IdO) | `ido-qim-sp-consumer` | `qim.sp.member.events` | 회원 변경 → 기관 Webhook 전파 |
| `HandoffEventConsumer` (agency-stub) | `agency-stub-handoff` | `ido.handoff.events` | **⚠️ PoC 전용** — 내부 Kafka 직접 구독(운영 불가). 실 운영에서 외부 기관은 Kafka에 접근할 수 없으며, IdO가 HTTPS Webhook(Option A) 또는 폴링 API(Option B)로 대체 |

### 8.3 이벤트 타입별 필드 참조

#### AuthEvent (`qsign.auth.events` 발행)

```java
// ═══════════════════════════════════════════════════════════════════
// 이 구조체는 발행 측(Q-Sign 서버 / IdO 서버)이 Outbox payload에 JSON으로 저장하고,
// 수신 측(IdO 서버 QsignAuthEventConsumer)이 역직렬화하여 처리합니다.
// 클래스 위치: platform-common / io.github.hipstermin.idem.common.event.AuthEvent
// ═══════════════════════════════════════════════════════════════════

// 공통 DomainEvent 필드
String eventId;          // UUID v7 — 고유 식별자
String eventType;        // AUTH_COMPLETED / AUTH_FAILED / AUTH_LOCKED
String sourceSystem;     // "q-sign" 또는 "ido-keycloak"
String correlationId;    // 요청 추적 ID (로그 연계용)
String qimUserId;        // Q-IM 사용자 ID
Long   eventVersion;     // 단조 증가 버전

// AuthEvent 전용 필드
String authResultId;     // qsign.auth_result PK
AuthLevel authLevel;     // LEVEL1 / LEVEL2 / LEVEL3
String providerCode;     // PASS / KAKAO / NAVER / IPIN / CERT 등
String providerTxId;     // 인증 수단 제공사 트랜잭션 ID
VerificationResult verificationResult; // SUCCESS / FAIL / LOCKED
```

#### UserEvent (`qim.user.events` 발행)

```java
// ═══════════════════════════════════════════════════════════════════
// 발행 위치: Q-IM 서버 OutboxServiceImpl → Kafka qim.user.events
// 수신 위치: Q-Sign 서버 QimUserEventConsumer
//            IdO 서버 QimSpMemberEventConsumer
// 클래스 위치: platform-common / io.github.hipstermin.idem.common.event.UserEvent
// ═══════════════════════════════════════════════════════════════════

// DomainEvent 공통 필드 +
String userStatus;           // ACTIVE / SUSPENDED / WITHDRAWN
String changeReason;         // 변경 사유 (설명용)
boolean needsSync;           // true → 기관 재동기화 필요 신호
String mergedIntoQimUserId;  // MERGED 이벤트 시 대상 ID
```

#### HandoffEvent (`ido.handoff.events` 발행)

```java
// ═══════════════════════════════════════════════════════════════════
// 발행 위치: IdO 서버 HandoffServiceImpl → Kafka ido.handoff.events (직접 발행, Outbox 미사용)
// 수신 위치: IdO 서버 HandoffEventConsumer
//            [PoC 전용] agency-stub HandoffEventConsumer
// 클래스 위치: platform-common / io.github.hipstermin.idem.common.event.HandoffEvent
// ═══════════════════════════════════════════════════════════════════

// DomainEvent 공통 필드 +
String ticketId;       // Handoff 티켓 ID
String agencyCode;     // 대상 기관 코드
String authResultId;   // 연결된 인증 결과 ID
String ticketState;    // ISSUED / CONSUMED / EXPIRED / REVOKED
String revokeReason;   // REVOKED 시: compromise / qim_suspend 등
```

---

## 9. IdempotentEventStore — 중복 방어 구현

### 9.1 테이블 구조

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 이 DDL은 실제 생성 SQL입니다.
-- 실행 위치(DDL): DBA 또는 Flyway/Liquibase 마이그레이션 스크립트
-- 첫 번째 테이블: IdO 서버가 사용하는 중복 방어 테이블
--   → IdO PostgreSQL (ido 스키마)
-- 두 번째 테이블: Q-Sign 서버가 사용하는 중복 방어 테이블
--   → Q-Sign PostgreSQL (qsign 스키마)
-- 세 번째 테이블: Q-Sign 서버가 사용하는 버전 추적 테이블
--   → Q-Sign PostgreSQL (qsign 스키마)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE ido.idempotent_event (  -- Q-Sign: qsign.processed_event
    event_id       VARCHAR(36)   NOT NULL,
    consumer_group VARCHAR(80)   NOT NULL,
    event_type     VARCHAR(80),
    result_code    VARCHAR(20),
    processed_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);

-- 버전 추적 테이블 (Q-Sign 한정)
CREATE TABLE qsign.last_event_version (
    qim_user_id     VARCHAR(36) NOT NULL PRIMARY KEY,
    last_version    BIGINT      NOT NULL,
    last_event_id   VARCHAR(36) NOT NULL,
    updated_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);
```

### 9.2 사용 패턴

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: 각 서버의 Kafka Consumer 클래스 내부
//   · IdO 서버: QsignAuthEventConsumer, HandoffEventConsumer
//   · Q-Sign 서버: QimUserEventConsumer
// 실행 클래스: io.github.hipstermin.idem.{서비스}.event.IdempotentEventStore (서비스별 구현체)
// 실행 시점: Kafka 메시지 수신 시 Consumer 내부에서 순서대로 호출
// DB: 서비스별 idempotent_event 테이블 조회/INSERT
//     Q-Sign 한정: last_event_version 테이블도 조회/UPSERT
// ═══════════════════════════════════════════════════════════════════

// [1단계] 중복 확인
//   → {서비스} DB {스키마}.idempotent_event 테이블 SELECT (isAlreadyProcessed SQL)
boolean duplicate = idempotentEventStore.isAlreadyProcessed(eventId, consumerGroup);

// [3단계] 처리 완료 마킹 (비즈니스 로직 처리 후)
//   → {서비스} DB {스키마}.idempotent_event 테이블 INSERT (markProcessed SQL)
idempotentEventStore.markProcessed(eventId, consumerGroup, eventType, "OK");

// [2단계-a] 버전 역전 확인 (Q-Sign 서버 QimUserEventConsumer 한정)
//   → Q-Sign PostgreSQL qsign.last_event_version 테이블 SELECT (isVersionOutdated SQL)
//   qimUserId의 마지막 처리 버전 < 현재 이벤트 버전이면 정상 (false 반환)
boolean outdated = idempotentEventStore.isVersionOutdated(qimUserId, eventVersion);

// [4단계] 버전 갱신 (Q-Sign 서버 한정)
//   → Q-Sign PostgreSQL qsign.last_event_version 테이블 UPSERT (updateLastVersion SQL)
idempotentEventStore.updateLastVersion(qimUserId, eventId, eventVersion);
```

### 9.3 구현 SQL

```sql
-- ═══════════════════════════════════════════════════════════════════
-- [SQL 1] markProcessed
-- 실행 주체: 각 서버의 Kafka Consumer / IdempotentEventStore.markProcessed()
-- 실행 방식: JdbcTemplate 또는 JPA
-- 실행 DB:
--   · IdO 서버 → IdO PostgreSQL ido.idempotent_event 테이블
--   · Q-Sign 서버 → Q-Sign PostgreSQL qsign.processed_event 테이블
-- 실행 시점: Consumer 비즈니스 로직 처리 완료 직후
-- 목적:      처리 완료 기록 → 중복 수신 시 isAlreadyProcessed()가 true 반환
-- ON CONFLICT DO NOTHING: 동시 처리 시 중복 INSERT 무시 (멱등성 보장)
-- ═══════════════════════════════════════════════════════════════════
INSERT INTO ido.idempotent_event (event_id, consumer_group, event_type, result_code)
VALUES (?, ?, ?, ?)
ON CONFLICT (event_id, consumer_group) DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════
-- [SQL 2] isAlreadyProcessed
-- 실행 주체: 각 서버의 Kafka Consumer / IdempotentEventStore.isAlreadyProcessed()
-- 실행 방식: JdbcTemplate 또는 JPA
-- 실행 DB:
--   · IdO 서버 → IdO PostgreSQL ido.idempotent_event 테이블
--   · Q-Sign 서버 → Q-Sign PostgreSQL qsign.processed_event 테이블
-- 실행 시점: Consumer 메시지 수신 직후 가장 먼저 실행 (비즈니스 로직 전)
-- 목적:      이미 처리된 이벤트인지 확인 → true 이면 즉시 ACK 후 스킵
-- ═══════════════════════════════════════════════════════════════════
SELECT COUNT(1) > 0
FROM ido.idempotent_event
WHERE event_id = ? AND consumer_group = ?;

-- ═══════════════════════════════════════════════════════════════════
-- [SQL 3] isVersionOutdated (Q-Sign 서버 전용)
-- 실행 주체: Q-Sign 서버 / QimUserEventConsumer → IdempotentEventStore.isVersionOutdated()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   Q-Sign PostgreSQL (qsign 스키마) — qsign.last_event_version 테이블
-- 실행 시점: isAlreadyProcessed() 확인 후, 비즈니스 로직 실행 전
-- 목적:      현재 이벤트 버전이 이미 처리한 버전보다 낮으면 무시 (역전 방지)
--            COALESCE(…, FALSE): 처음 수신 시 last_event_version 레코드 없음 → FALSE 반환
-- ═══════════════════════════════════════════════════════════════════
SELECT COALESCE(
    (SELECT last_version > ? FROM qsign.last_event_version WHERE qim_user_id = ?),
    FALSE
);

-- ═══════════════════════════════════════════════════════════════════
-- [SQL 4] updateLastVersion (Q-Sign 서버 전용)
-- 실행 주체: Q-Sign 서버 / QimUserEventConsumer → IdempotentEventStore.updateLastVersion()
-- 실행 방식: JdbcTemplate
-- 실행 DB:   Q-Sign PostgreSQL (qsign 스키마) — qsign.last_event_version 테이블
-- 실행 시점: 비즈니스 로직 처리 완료 + markProcessed() 호출 직후
-- 목적:      처리한 최신 버전 기록 → 이후 이전 버전 이벤트는 자동 스킵
-- WHERE 조건: 현재 저장된 버전보다 새 버전일 때만 UPDATE (동시성 안전)
-- ═══════════════════════════════════════════════════════════════════
INSERT INTO qsign.last_event_version (qim_user_id, last_version, last_event_id)
VALUES (?, ?, ?)
ON CONFLICT (qim_user_id) DO UPDATE
    SET last_version  = EXCLUDED.last_version,
        last_event_id = EXCLUDED.last_event_id,
        updated_at    = NOW()
WHERE qsign.last_event_version.last_version < EXCLUDED.last_version;
```

---

## 10. Feature Flag 제어

### 10.1 Outbox 관련 Feature Flag 목록

| Flag 환경변수 | 기본값 | 영향 범위 | 용도 |
|-------------|-------|---------|-----|
| `IDO_OUTBOX_RELAY_ENABLED` | `true` | IdoOutboxRelay | IdO→Kafka 릴레이 ON/OFF |
| `IDO_WEBHOOK_RELAY_ENABLED` | `true` | WebhookDispatchOutboxRelay | 기관 Webhook 릴레이 ON/OFF |
| `IDO_PROVISIONING_RELAY_ENABLED` | `true` | ProvisioningOutboxRelay | 프로비저닝 릴레이 ON/OFF |

### 10.2 적용 방법

```java
// ═══════════════════════════════════════════════════════════════════
// 실행 위치: IdO 서버 (포트 8080)
// 실행 클래스: 모든 Relay 클래스
//   · IdoOutboxRelay, WebhookDispatchOutboxRelay, ProvisioningOutboxRelay
// 실행 메서드: relay() — @Scheduled 메서드
// 실행 시점: @Scheduled 타이머 발동 시마다 가장 먼저 Feature Flag 체크
// 환경변수: 서버 기동 시 @Value로 주입 (동적 변경 불가 — 재기동 필요)
// ═══════════════════════════════════════════════════════════════════

// Feature Flag 적용 패턴 (모든 Relay 동일)
@Value("${IDO_OUTBOX_RELAY_ENABLED:true}")
private boolean relayEnabled;

@Scheduled(fixedDelayString = "...")
public void relay() {
    if (!relayEnabled) {
        log.trace("[Relay] DISABLED — 스킵");
        return;  // 즉시 return, 다음 사이클에도 동일하게 체크
    }
    // ... 실제 처리
}
```

### 10.3 점진적 배포 시나리오

```bash
# ═══════════════════════════════════════════════════════════════════
# 이 스크립트는 IdO 서버의 환경변수 설정 예시입니다.
# 실행 주체: 운영팀 / CI/CD 파이프라인
# 실행 위치: IdO 서버 배포 환경 (Kubernetes env, Docker 등)
# 주의: 환경변수 변경 후 서버 재기동 필요 (@Value는 기동 시 1회만 로딩)
# ═══════════════════════════════════════════════════════════════════

# 1. 새 버전 배포 전: 릴레이 OFF
export IDO_OUTBOX_RELAY_ENABLED=false
export IDO_WEBHOOK_RELAY_ENABLED=false

# 2. 새 버전 배포 (PENDING 레코드들이 DB에 쌓이지만 발행 안 됨)

# 3. 검증 후 릴레이 ON (IdO 서버 재기동)
export IDO_OUTBOX_RELAY_ENABLED=true
# PENDING 레코드들이 자동으로 재처리됨

# 4. Webhook 릴레이도 ON
export IDO_WEBHOOK_RELAY_ENABLED=true
```

---

## 11. 장애 대응 및 운영 SQL

### 11.1 현황 모니터링 쿼리

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: 운영팀 DBA / 모니터링 시스템 (Grafana 쿼리 등)
-- 실행 방식: 직접 DB 접속 또는 모니터링 도구에서 주기적 실행
-- 실행 시점: 정기 점검 또는 이상 감지 시
-- ═══════════════════════════════════════════════════════════════════

-- [IdO PostgreSQL] ido.outbox 상태 현황
-- → IdO PostgreSQL (ido 스키마)에서 직접 실행
SELECT status, COUNT(*) as cnt,
       MIN(created_at) as oldest,
       MAX(created_at) as newest
FROM ido.outbox
GROUP BY status;

-- [IdO PostgreSQL] Webhook Outbox 기관별 현황
-- → IdO PostgreSQL (ido 스키마)에서 직접 실행
-- 기관별 FAILED/PENDING 수가 급증하면 해당 기관 엔드포인트 점검 필요
SELECT status, agency_code, COUNT(*) as cnt
FROM ido.webhook_dispatch_outbox
GROUP BY status, agency_code
ORDER BY status, cnt DESC;

-- [IdO PostgreSQL] 프로비저닝 기관별 현황
-- → IdO PostgreSQL (ido 스키마)에서 직접 실행
SELECT status, agency_code, COUNT(*) as cnt
FROM ido.provisioning_outbox
GROUP BY status, agency_code
ORDER BY status, cnt DESC;

-- [Q-IM MariaDB] qim.outbox 상태 현황
-- → Q-IM MariaDB (qim 스키마)에서 직접 실행
SELECT status, COUNT(*) as cnt,
       MIN(created_at) as oldest,
       MAX(retry_count) as max_retry
FROM qim.outbox
GROUP BY status;

-- [Q-Sign PostgreSQL] qsign.outbox 상태 현황
-- → Q-Sign PostgreSQL (qsign 스키마)에서 직접 실행
SELECT status, COUNT(*) as cnt,
       MIN(created_at) as oldest
FROM qsign.outbox
GROUP BY status;
```

### 11.2 FAILED 레코드 수동 복구

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: 운영팀 DBA
-- 실행 방식: 직접 DB 접속 후 수동 실행 (장애 복구 작업)
-- 실행 시점: 장애 원인 해소 후 (Kafka 복구, 기관 엔드포인트 복구 등)
-- 주의: 실행 전 반드시 장애 원인이 해소됐는지 확인할 것
--       PENDING 복구 즉시 Relay가 재발행 시도함
-- ═══════════════════════════════════════════════════════════════════

-- [IdO PostgreSQL] ido.outbox FAILED → PENDING 복구 (IdoOutboxRelay가 재시도)
-- → IdO PostgreSQL (ido 스키마)에서 직접 실행
UPDATE ido.outbox
SET status = 'PENDING',
    retry_count = 0,
    error_message = NULL
WHERE status = 'FAILED'
  AND created_at > NOW() - INTERVAL '24 hours';  -- 최근 24시간만

-- [Q-IM MariaDB] qim.outbox FAILED → PENDING 복구 (OutboxServiceImpl.relayFailedEvents가 재시도)
-- → Q-IM MariaDB (qim 스키마)에서 직접 실행
UPDATE qim.outbox
SET status = 'PENDING',
    retry_count = 0,
    error_message = NULL
WHERE status = 'FAILED'
  AND retry_count < 5;

-- [IdO PostgreSQL] 특정 기관의 Webhook FAILED 레코드 재시도
-- → IdO PostgreSQL (ido 스키마)에서 직접 실행
-- AGENCY_CODE_HERE 자리에 실제 기관 코드 입력
UPDATE ido.webhook_dispatch_outbox
SET status = 'PENDING',
    retry_count = 0,
    next_retry_at = NOW(),
    error_message = NULL
WHERE status = 'FAILED'
  AND agency_code = 'AGENCY_CODE_HERE';

-- [IdO PostgreSQL] 특정 사용자의 프로비저닝 DEAD_LETTER 재시도
-- → IdO PostgreSQL (ido 스키마)에서 직접 실행
-- QIM_USER_ID_HERE 자리에 실제 qimUserId 입력
UPDATE ido.provisioning_outbox
SET status = 'PENDING',
    retry_count = 0,
    next_retry_at = NOW(),
    error_message = NULL
WHERE status = 'DEAD_LETTER'
  AND qim_user_id = 'QIM_USER_ID_HERE';
```

### 11.3 오래된 PUBLISHED 레코드 정리

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: DBA 또는 정기 배치 작업 (Batch Job / Cron)
-- 실행 방식: 스케줄러 또는 수동 실행
-- 실행 시점: 정기 배치 (예: 매일 새벽 3시)
-- 주의: PUBLISHED/DISPATCHED/COMPLETED 레코드 삭제 전 아카이브 테이블 이동 검토
--       감사 로그 목적으로 보관 기간 연장이 필요할 수 있음
-- ═══════════════════════════════════════════════════════════════════

-- [IdO PostgreSQL] ido.outbox PUBLISHED 레코드 30일 후 삭제
-- → IdO PostgreSQL (ido 스키마)에서 실행
DELETE FROM ido.outbox
WHERE status = 'PUBLISHED'
  AND published_at < NOW() - INTERVAL '30 days';

-- [Q-Sign PostgreSQL] qsign.outbox PUBLISHED 레코드 30일 후 삭제
-- → Q-Sign PostgreSQL (qsign 스키마)에서 실행
DELETE FROM qsign.outbox
WHERE status = 'PUBLISHED'
  AND published_at < NOW() - INTERVAL '30 days';

-- [Q-IM MariaDB] qim.outbox PUBLISHED 레코드 30일 후 삭제
-- → Q-IM MariaDB (qim 스키마)에서 실행 (MariaDB 문법: INTERVAL 30 DAY)
DELETE FROM qim.outbox
WHERE status = 'PUBLISHED'
  AND published_at < NOW() - INTERVAL 30 DAY;

-- [IdO PostgreSQL] webhook_dispatch_outbox DISPATCHED/FAILED 레코드 30일 후 삭제
-- → IdO PostgreSQL (ido 스키마)에서 실행
DELETE FROM ido.webhook_dispatch_outbox
WHERE status IN ('DISPATCHED', 'FAILED')
  AND created_at < NOW() - INTERVAL '30 days';
```

### 11.4 특정 이벤트 추적 (correlationId 기반)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: 운영팀 / 개발팀 (이슈 추적 시)
-- 실행 방식: 직접 DB 접속
-- 실행 시점: 특정 요청의 처리 흐름 추적 필요 시
-- 목적: 하나의 correlationId로 전체 Outbox → Kafka → Consumer 흐름 확인
-- ═══════════════════════════════════════════════════════════════════

-- 1. [Q-Sign PostgreSQL] Q-Sign Outbox 확인
--    → Q-Sign PostgreSQL (qsign 스키마)에서 실행
SELECT event_id, event_type, status, retry_count, error_message, created_at, published_at
FROM qsign.outbox
WHERE payload::text LIKE '%correlationId-here%';

-- 2. [IdO PostgreSQL] IdO Outbox 확인
--    → IdO PostgreSQL (ido 스키마)에서 실행
SELECT event_id, event_type, status, retry_count, error_message, created_at, published_at
FROM ido.outbox
WHERE payload::text LIKE '%correlationId-here%';

-- 3. [IdO PostgreSQL] Webhook Dispatch 확인
--    → IdO PostgreSQL (ido 스키마)에서 실행
SELECT dispatch_id, agency_code, source_event_type, status,
       retry_count, error_message, created_at
FROM ido.webhook_dispatch_outbox
WHERE correlation_id = 'correlationId-here';

-- 4. [IdO PostgreSQL] 처리 완료 확인 (IdempotentEventStore)
--    → IdO PostgreSQL (ido 스키마)에서 실행
SELECT event_id, consumer_group, event_type, result_code, processed_at
FROM ido.idempotent_event
WHERE event_id IN (
    SELECT event_id FROM ido.outbox
    WHERE payload::text LIKE '%correlationId-here%'
);
```

### 11.5 장애 패턴별 체크리스트

#### 패턴 A: "기관이 Webhook을 못 받고 있음"

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: 운영팀 DBA (장애 대응 시)
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — 모든 쿼리
-- 실행 순서: 1→2→3 순서로 확인 후, 기관 서버 복구 확인 후 4 실행
-- ═══════════════════════════════════════════════════════════════════

-- 1. 해당 기관의 PENDING 확인
SELECT dispatch_id, status, retry_count, error_message, next_retry_at
FROM ido.webhook_dispatch_outbox
WHERE agency_code = '기관코드'
  AND status = 'PENDING'
ORDER BY created_at;

-- 2. FAILED 확인 (최대 재시도 초과 레코드)
SELECT COUNT(*), MAX(error_message)
FROM ido.webhook_dispatch_outbox
WHERE agency_code = '기관코드'
  AND status = 'FAILED';

-- 3. 기관 Webhook 엔드포인트 설정 확인
SELECT * FROM ido.agency_webhook_config
WHERE agency_code = '기관코드';

-- 4. 복구: 기관 서버 복구 확인 후 실행 (PENDING + FAILED 모두 재시도)
UPDATE ido.webhook_dispatch_outbox
SET status = 'PENDING', retry_count = 0, next_retry_at = NOW()
WHERE agency_code = '기관코드' AND status IN ('PENDING', 'FAILED');
```

#### 패턴 B: "Kafka에 이벤트가 안 쌓임"

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: 운영팀 DBA (장애 대응 시)
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — 쿼리 1,2,4
--            Q-Sign PostgreSQL (qsign 스키마) — 쿼리 1,2
-- 실행 순서: 1→2→3→4 순서로 원인 파악
-- ═══════════════════════════════════════════════════════════════════

-- 1. [IdO/Q-Sign PostgreSQL] 각 Outbox에 PENDING 있는지 확인
SELECT COUNT(*), MIN(created_at) FROM ido.outbox WHERE status = 'PENDING';
SELECT COUNT(*), MIN(created_at) FROM qsign.outbox WHERE status = 'PENDING';

-- 2. [IdO PostgreSQL] Relay가 살아있는지 확인 (최근 PUBLISHED 시각)
--    최근 PUBLISHED가 없으면 Relay가 중단된 것
SELECT MAX(published_at) FROM ido.outbox WHERE status = 'PUBLISHED';

-- 3. Feature Flag 확인
-- IdO 서버 애플리케이션 로그에서 "[Relay] DISABLED" 메시지 확인
-- 또는 환경변수 IDO_OUTBOX_RELAY_ENABLED 값 확인

-- 4. [IdO PostgreSQL] FAILED 레코드의 error_message 확인 (원인 파악)
SELECT event_id, error_message, retry_count
FROM ido.outbox WHERE status = 'FAILED'
ORDER BY created_at DESC LIMIT 10;
```

#### 패턴 C: "같은 이벤트가 중복 처리됨"

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: 운영팀 DBA (중복 처리 의심 시)
-- 실행 DB:   IdO PostgreSQL (ido 스키마) — 모든 쿼리
-- 실행 순서: 1→2 순서로 원인 파악
-- ═══════════════════════════════════════════════════════════════════

-- 1. IdempotentEventStore에 기록됐는지 확인
SELECT * FROM ido.idempotent_event
WHERE event_id = '문제_이벤트_ID';
-- 없으면: Consumer의 isAlreadyProcessed() 로직 버그 → 코드 확인
-- 있으면: 정상 중복 방어 작동 중

-- 2. 중복 이벤트 발생 원인 추적 (FOR UPDATE SKIP LOCKED 누락 가능성)
SELECT COUNT(*) FROM ido.outbox
WHERE aggregate_id = '동일_authResultId'
  AND status = 'PUBLISHED';
-- 2 이상이면 OutboxRelay가 중복 발행 → SKIP LOCKED 설정 누락 가능성 점검
```

---

## 12. 테스트 전략

### 12.1 Outbox INSERT 검증 (단위 테스트)

```java
// ═══════════════════════════════════════════════════════════════════
// 테스트 실행 위치: Q-Sign 서버 테스트 환경 (로컬 또는 CI)
// 테스트 클래스: io.github.hipstermin.idem.gate.auth.AuthServiceImplTest (예시)
// 테스트 목적: processAuthentication() 호출 시 qsign.outbox에 PENDING 레코드가
//              정상적으로 INSERT 되는지 검증
// 사용 DB: H2 인메모리 DB 또는 Testcontainers PostgreSQL
// 실행 방법: ./gradlew :idem-gate:test 또는 IDE에서 직접 실행
// ═══════════════════════════════════════════════════════════════════
@Test
@Transactional
void 인증_완료_시_outbox_레코드가_생성된다() {
    // given
    AuthRequest request = buildAuthRequest();

    // when: Q-Sign AuthServiceImpl.processAuthentication() 실행
    //       내부적으로 qsign.auth_result INSERT + qsign.outbox INSERT 발생
    authService.processAuthentication(request);

    // then: 같은 트랜잭션에서 조회 (아직 커밋 안 됨)
    List<QSignOutboxRecord> records = outboxRepository.findAll();
    assertThat(records).hasSize(1);
    assertThat(records.get(0).getStatus()).isEqualTo("PENDING");
    assertThat(records.get(0).getEventType()).isEqualTo("AUTH_COMPLETED");
    assertThat(records.get(0).getPartitionKey()).isNotNull();
    // partitionKey = identifierHash (SHA-256 결과) → null이면 안 됨
}
```

### 12.2 Relay 발행 검증 (통합 테스트)

```java
// ═══════════════════════════════════════════════════════════════════
// 테스트 실행 위치: Q-Sign 서버 테스트 환경 (로컬 또는 CI)
// 테스트 클래스: io.github.hipstermin.idem.gate.outbox.OutboxRelayIntegrationTest
// 테스트 목적:
//   [테스트 1] PENDING 레코드 → Kafka 발행 → qsign.outbox PUBLISHED 상태 전이 검증
//   [테스트 2] Kafka 브로커 장애 시 → retry_count 증가, PENDING 유지 검증
// 사용 DB: Testcontainers PostgreSQL
// 사용 Kafka: @EmbeddedKafka (인메모리 Kafka 브로커)
// 실행 방법: ./gradlew :idem-gate:test --tests "*OutboxRelayIntegrationTest"
// ═══════════════════════════════════════════════════════════════════
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"qsign.auth.events"})
class OutboxRelayIntegrationTest {

    @Test
    void PENDING_레코드가_Kafka로_발행된다() throws Exception {
        // given: qsign.outbox에 PENDING 레코드 직접 INSERT (테스트 픽스처)
        insertPendingOutbox("test-event-id", "AUTH_COMPLETED");

        // when: OutboxRelay.relay() 직접 호출 (스케줄러 대기 없이)
        //       → qsign.outbox SELECT → Kafka 발행 → qsign.outbox UPDATE
        outboxRelay.relay();

        // then: EmbeddedKafka qsign.auth.events 토픽에서 메시지 수신 확인
        ConsumerRecord<String, String> received = KafkaTestUtils.getSingleRecord(
            consumer, "qsign.auth.events", 5000);
        assertThat(received.value()).contains("AUTH_COMPLETED");

        // then: qsign.outbox DB 상태 PUBLISHED로 변경됐는지 확인
        QSignOutboxRecord updated = outboxRepository.findById("test-event-id").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PUBLISHED");
        assertThat(updated.getPublishedAt()).isNotNull();
    }

    @Test
    void Kafka_실패_시_FAILED_상태로_전환된다() {
        // given: Kafka 브로커를 MockProducer로 교체하여 강제 실패 시뮬레이션
        doThrow(new RuntimeException("Kafka down")).when(kafkaTemplate).send(any(), any(), any());
        insertPendingOutbox("fail-event-id", "AUTH_COMPLETED");

        // when: OutboxRelay.relay() 실행 → Kafka 발행 실패 → whenComplete 실패 콜백
        outboxRelay.relay();

        // then: qsign.outbox retry_count 증가, maxRetry 미만이면 PENDING 유지
        QSignOutboxRecord record = outboxRepository.findById("fail-event-id").orElseThrow();
        assertThat(record.getRetryCount()).isEqualTo((short) 1);
        // maxRetry(=3) 미만 → status는 PENDING 유지
        assertThat(record.getStatus()).isEqualTo("PENDING");
    }
}
```

### 12.3 멱등성 검증

```java
// ═══════════════════════════════════════════════════════════════════
// 테스트 실행 위치: IdO 서버 테스트 환경 (로컬 또는 CI)
// 테스트 클래스: io.github.hipstermin.idem.hub.kafka.QsignAuthEventConsumerTest (예시)
// 테스트 목적: 동일 이벤트를 2번 수신해도 비즈니스 로직(Redis Pre-warming)이
//              1번만 실행되는지 검증 (at-least-once + 멱등성 보장)
// 사용 DB: Testcontainers PostgreSQL — ido.idempotent_event 테이블
// 실행 방법: ./gradlew :idem-hub:test --tests "*QsignAuthEventConsumerTest"
// ═══════════════════════════════════════════════════════════════════
@Test
void 동일_이벤트_두번_처리해도_결과가_같다() {
    // given: AUTH_COMPLETED 이벤트 (동일 eventId)
    AuthEvent event = buildAuthEvent("duplicate-event-id");

    // when: Kafka Consumer.consume() 동일 이벤트 2회 수신
    //       1회차: isAlreadyProcessed() → false → 비즈니스 로직 실행 → markProcessed()
    //       2회차: isAlreadyProcessed() → true → 즉시 ACK (비즈니스 로직 스킵)
    consumer.consume(buildRecord(event), ack);
    consumer.consume(buildRecord(event), ack);  // 중복

    // then: Redis Pre-warming(authResultCacheService.preWarm)은 1번만 실행됨
    verify(authResultCacheService, times(1)).preWarm(any());

    // then: IdO PostgreSQL ido.idempotent_event에 1건만 기록됨
    assertThat(idempotentEventStore.isAlreadyProcessed("duplicate-event-id", GROUP))
        .isTrue();
}
```

### 12.4 FOR UPDATE SKIP LOCKED 검증

```java
// ═══════════════════════════════════════════════════════════════════
// 테스트 실행 위치: Q-Sign 서버 테스트 환경 (로컬 또는 CI)
// 테스트 클래스: io.github.hipstermin.idem.gate.outbox.OutboxSkipLockedTest (예시)
// 테스트 목적: 다중 인스턴스 시뮬레이션 — 2개의 Relay 쓰레드가 동시 실행 시
//              동일 레코드를 각각 1번씩만 처리하는지 검증
// 사용 DB: Testcontainers PostgreSQL (실제 FOR UPDATE SKIP LOCKED 동작 확인 필수)
//          ⚠️ H2는 FOR UPDATE SKIP LOCKED 미지원 → 반드시 실제 PostgreSQL 사용
// 실행 방법: ./gradlew :idem-gate:test --tests "*OutboxSkipLockedTest"
// ═══════════════════════════════════════════════════════════════════
@Test
void 다중_인스턴스에서_동일_레코드_중복_처리_안됨() throws Exception {
    // given: qsign.outbox에 1건의 PENDING 레코드
    insertPendingOutbox("shared-event-id", "AUTH_COMPLETED");

    // when: 2개의 별도 트랜잭션(쓰레드)에서 동시에 findPendingBatch() 실행
    //       FOR UPDATE SKIP LOCKED → 한 쓰레드가 잠금 → 다른 쓰레드는 0건 조회
    CountDownLatch latch = new CountDownLatch(2);
    List<String> processed = Collections.synchronizedList(new ArrayList<>());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    for (int i = 0; i < 2; i++) {
        executor.submit(() -> {
            // 각 쓰레드가 독립적인 트랜잭션으로 findPendingBatch() 실행
            List<QSignOutboxRecord> batch = outboxRepository.findPendingBatch(10);
            batch.forEach(r -> processed.add(r.getEventId()));
            latch.countDown();
        });
    }
    latch.await(5, TimeUnit.SECONDS);

    // then: "shared-event-id"는 2개 쓰레드 중 1개에서만 조회됨
    assertThat(processed.stream().filter(id -> id.equals("shared-event-id")).count())
        .isEqualTo(1);
}
```

---

## 부록 A: Outbox 테이블 DDL 요약

### PostgreSQL (ido.outbox, qsign.outbox)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: DBA 또는 Flyway/Liquibase 마이그레이션 스크립트
-- 실행 DB:
--   · {schema} = ido → IdO PostgreSQL (ido 스키마)
--   · {schema} = qsign → Q-Sign PostgreSQL (qsign 스키마)
-- 실행 시점: 서비스 최초 배포 또는 DB 마이그레이션 시
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE {schema}.outbox (
    event_id      VARCHAR(36)  NOT NULL PRIMARY KEY,
    event_type    VARCHAR(80)  NOT NULL,
    partition_key VARCHAR(300) NOT NULL,
    aggregate_id  VARCHAR(36)  NOT NULL,
    event_version BIGINT       NOT NULL,
    payload       JSONB        NOT NULL,
    topic         VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count   SMALLINT     NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at  TIMESTAMP
);
CREATE INDEX idx_{schema}_outbox_status ON {schema}.outbox (status, created_at);
```

### MariaDB (qim.outbox)

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: DBA 또는 Flyway 마이그레이션 스크립트
-- 실행 DB:   Q-IM MariaDB (qim 스키마)
-- 실행 시점: Q-IM 서비스 최초 배포 또는 DB 마이그레이션 시
-- 특이사항: PostgreSQL과 달리 JSON 타입(검색 불가) 사용,
--           DATETIME(6) (마이크로초 정밀도)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE qim.outbox (
    event_id      VARCHAR(36)  NOT NULL PRIMARY KEY,
    event_type    VARCHAR(80)  NOT NULL,
    partition_key VARCHAR(36)  NOT NULL,
    aggregate_id  VARCHAR(36)  NOT NULL,
    event_version BIGINT       NOT NULL,
    payload       JSON         NOT NULL,
    topic         VARCHAR(200) NOT NULL DEFAULT 'qim.user.events',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count   SMALLINT     NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at  DATETIME(6)
);
CREATE INDEX idx_qim_outbox_status ON qim.outbox (status, created_at);
```

### ido.webhook_dispatch_outbox

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: DBA 또는 Flyway/Liquibase 마이그레이션 스크립트
-- 실행 DB:   IdO PostgreSQL (ido 스키마)
-- 실행 시점: IdO 서비스 최초 배포 또는 DB 마이그레이션 시
-- 특이사항: UNIQUE (source_event_id, agency_code) — ON CONFLICT DO NOTHING 기반
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE ido.webhook_dispatch_outbox (
    dispatch_id       VARCHAR(36)  NOT NULL PRIMARY KEY,
    agency_code       VARCHAR(20)  NOT NULL,
    endpoint_url      VARCHAR(500) NOT NULL,
    source_event_id   VARCHAR(36)  NOT NULL,
    source_event_type VARCHAR(80)  NOT NULL,
    source_topic      VARCHAR(200) NOT NULL,
    correlation_id    VARCHAR(36),
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    max_retry         INT          NOT NULL DEFAULT 3,
    next_retry_at     TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    dispatched_at     TIMESTAMP,
    error_message     TEXT,
    CONSTRAINT uq_webhook_dispatch UNIQUE (source_event_id, agency_code)
);
```

### ido.provisioning_outbox

```sql
-- ═══════════════════════════════════════════════════════════════════
-- 실행 주체: DBA 또는 Flyway/Liquibase 마이그레이션 스크립트
-- 실행 DB:   IdO PostgreSQL (ido 스키마)
-- 실행 시점: IdO 서비스 최초 배포 또는 DB 마이그레이션 시
-- 특이사항: UNIQUE (idempotency_key, agency_code) — ON CONFLICT DO NOTHING 기반
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE ido.provisioning_outbox (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    qim_user_id      VARCHAR(36)  NOT NULL,
    agency_code      VARCHAR(20)  NOT NULL,
    event_type       VARCHAR(80)  NOT NULL,
    payload_json     JSONB        NOT NULL,
    idempotency_key  VARCHAR(36)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count      INT          NOT NULL DEFAULT 0,
    max_retry        INT          NOT NULL DEFAULT 3,
    next_retry_at    TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_attempted_at TIMESTAMP,
    completed_at     TIMESTAMP,
    error_message    TEXT,
    correlation_id   VARCHAR(36),
    source_event_id  VARCHAR(36),
    CONSTRAINT uq_prov_idempotency UNIQUE (idempotency_key, agency_code)
);
```

---

## 부록 B: 팀별 체크리스트

### Q-Sign팀

- [ ] `@Transactional` 메서드 내에서 `auth_result` 저장 → `qsign.outbox` 저장 순서 확인
- [ ] `partitionKey = identifierHash` 설정 확인
- [ ] `eventVersion`이 단조 증가하는지 확인
- [ ] `AUTH_COMPLETED / AUTH_FAILED / AUTH_LOCKED` 모두 Outbox에 기록되는지 확인
- [ ] `QimUserEventConsumer`에서 `isAlreadyProcessed` + `isVersionOutdated` 확인
- [ ] `maxRetry = 3` 설정 및 FAILED 알람 연결 확인

### Q-IM팀

- [ ] `OutboxService.publishInTx()`를 항상 `@Transactional` 메서드 내에서 호출
- [ ] `partitionKey = qimUserId` 설정 확인
- [ ] `eventVersion` 갱신 로직 (UPDATE 시마다 +1) 확인
- [ ] `relayFailedEvents()` 30초 주기 설정 확인 (`maxRetry = 5`)
- [ ] 스냅샷 발행 임계값 (`qim.snapshot.interval-events = 10`) 확인
- [ ] MariaDB `FOR UPDATE SKIP LOCKED` 네이티브 쿼리 동작 확인

### IdO팀

- [ ] `ido.outbox`: `payload::text` 조회, `?::jsonb` INSERT 확인
- [ ] `WebhookDispatcherService.insertOutbox()`: `ON CONFLICT DO NOTHING` 확인
- [ ] `WebhookDispatchOutboxRelay`: 지수 백오프 `2^(retryCount+1)` 계산 확인
- [ ] `ProvisioningOutboxRelay`: CASE WHEN 백오프 SQL 확인
- [ ] Feature Flag 3개 (`IDO_OUTBOX_RELAY_ENABLED`, `IDO_WEBHOOK_RELAY_ENABLED`, `IDO_PROVISIONING_RELAY_ENABLED`) 환경변수 설정
- [ ] `HandoffEventConsumer`에서 `idempotentEventStore` 사용 확인
- [ ] Webhook payload에 PII 미포함 확인 (`qimUserId`, `CI/DN` 원본 제외)

---

*이 문서는 코드 기반 분석으로 작성된 개발 내부 참조용 가이드입니다.*  
*코드 변경 시 이 문서도 함께 갱신해 주세요.*
