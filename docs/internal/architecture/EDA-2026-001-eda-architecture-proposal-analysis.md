# EDA-2026-001 — 중기원패스 EDA 아키텍처 제안서 분석

> **문서 ID**: EDA-2026-001  
> **작성일**: 2026-05-16  
> **작성자**: genspark_ai_developer  
> **분석 대상**: `중기원패스_EDA_아키텍처_제안_20260515(폰트포함).pptx` (11슬라이드)  
> **분류**: 아키텍처 검토 / 내부 참조  
> **관련 문서**: ADR-2026-004, ADR-2026-005, ANALYSIS-2026-0516-001, `docs/internal/통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.7.docx`  
> **개정 이력**: v1.1 (2026-05-16) — §2.1 Outbox Relay 분산 구조 문제 추가, §3.3 갭 항목 보완, §8 outbox-scheduler 신규 모듈 권고 추가

---

## 목차

1. [제안서 핵심 주장 요약](#1-제안서-핵심-주장-요약)
2. [현재 구현 현황 (코드 기반 확인)](#2-현재-구현-현황-코드-기반-확인)
3. [제안서 vs 현재 구현 갭 분석](#3-제안서-vs-현재-구현-갭-분석)
4. [아키텍처 정합성 평가](#4-아키텍처-정합성-평가)
5. [Phase별 마이그레이션 현실성 평가](#5-phase별-마이그레이션-현실성-평가)
6. [인프라 사양 적정성 평가](#6-인프라-사양-적정성-평가)
7. [ISMS-P 대응 현황](#7-isms-p-대응-현황)
8. [권고 사항](#8-권고-사항)
9. [판정 요약표](#9-판정-요약표)

---

## 1. 제안서 핵심 주장 요약

### 1.1 문제 정의 (슬라이드 3~4)

제안서는 현행 동기 처리 방식의 3가지 문제를 식별한다.

| 우선순위 | 문제 | 영향 |
|---------|------|------|
| P0 | Q-IM → 68개 기관 **순차** 동기 호출 → 응답 지연 13.6초+ | 사용자 이탈, 단일장애전파 |
| P1 | 1개 기관 실패 시 전체 트랜잭션 영향, 자원 고갈 | 부분 실패 처리 불가, Connection Pool 소진 |
| P2 | 재처리 메커니즘 부재, 기관 확장 시 선형 증가 | 운영 복잡도, 확장성 한계 |

### 1.2 제안 아키텍처 (슬라이드 5~7)

```
[사용자 전환 요청]
      ↓
[Q-IM] ─ DB 저장 + Outbox INSERT (단일 TX)
      ↓
[Outbox Relay] → Kafka: member.converted (제안 토픽)
      ↓
[BFF Consumer Group: bff-member-sync]
      ↓ 병렬 (CompletableFuture / Virtual Thread)
[68개 기관 REST API] ─ 성공 → 완료
                     └ 실패 → DLQ (3회 재시도 + Exponential Backoff)
      ↓ (Kafka 발행 즉시)
[Q-IM → FE: 202 Accepted] + Redis jobId 상태 추적
```

**핵심 성능 목표**: 13.6초 → **300ms** 응답 (사용자 체감 기준)

---

## 2. 현재 구현 현황 (코드 기반 확인)

코드베이스를 직접 확인한 결과를 구성요소별로 정리한다.

### 2.1 Transactional Outbox 패턴

**구현 상태**: ⚠️ **INSERT는 구현됨, Relay 분리는 미완료**

#### 2.1.1 제안서가 말하는 핵심 의도

제안서의 Outbox 패턴에서 실질적으로 가장 중요한 아이디어는 성능 수치(13.6초→300ms)가 아니라 다음이다:

> **각 서비스(Q-IM, Q-Sign, IdO)는 Outbox 테이블에 INSERT만 하면 되고,  
> 별도 Scheduler 서비스가 Kafka 발행을 전담한다.  
> 각 개발팀은 Kafka를 전혀 신경 쓰지 않아도 된다.**

#### 2.1.2 제안서가 의도한 구조

```
[Q-IM]    비즈니스 로직 + outbox INSERT ──┐
[Q-Sign]  비즈니스 로직 + outbox INSERT ──┼──► [outbox-scheduler] ──► Kafka
[IdO]     비즈니스 로직 + outbox INSERT ──┘     (별도 독립 서비스)

각 서비스:
  - Kafka 라이브러리 의존성 없음
  - KafkaTemplate 없음
  - @Scheduled Relay 없음
  - Kafka 브로커 설정 없음
  → DB outbox INSERT 한 줄로 이벤트 발행 완료
```

#### 2.1.3 현재 실제 구현 구조 (코드 확인)

```
[Q-IM]
  OutboxServiceImpl.java  ← @Scheduled(500ms) Relay 자체 보유
  KafkaProducerConfig.java ← KafkaTemplate 직접 의존
  → spring-kafka 라이브러리 직접 의존 (build.gradle.kts)

[Q-Sign]
  OutboxRelay.java        ← @Scheduled(500ms) Relay 자체 보유
  KafkaProducerConfig.java ← KafkaTemplate 직접 의존
  → spring-kafka 라이브러리 직접 의존

[IdO]
  IdoOutboxRelay.java     ← @Scheduled(500ms) IdO 자체 이벤트 Relay
  QimOutboxRelay.java     ← @Scheduled(1000ms) Q-IM 이벤트까지 대신 발행 (혼재)
  ProvisioningOutboxRelay.java ← @Scheduled 기관 HTTP 재시도
  WebhookDispatchOutboxRelay.java ← @Scheduled Webhook 재시도
  → spring-kafka 라이브러리 직접 의존
```

**결론**: 각 서비스가 Relay를 자체 내장한 분산 구조. 제안서 의도와 **반대** 방향.

> **참고**: `QimOutboxRelay` javadoc에 "Q-IM / Q-Sign 은 Kafka Producer 로직을 직접 구현하기  
> 어려운 상황에서 Outbox 테이블에 INSERT만 수행하고, IdO 측 폴링 스케줄러가 대신 Kafka 발행을  
> 담당하는 방식을 채택하였다"고 명시되어 있으나, 실제로는 Q-IM과 Q-Sign 모두 자체 Relay를  
> 보유하고 있어 이 문서와 코드 사이에 불일치가 존재한다.

### 2.2 Kafka Consumer 레이어

**구현 상태**: ✅ **두 Consumer 병렬 운영 중**

#### Consumer A — `QimEventConsumer` (ido-qim-consumer 그룹)
```java
// ido/src/main/java/kr/go/smes/ido/kafka/QimEventConsumer.java
// 역할: UserEvent 소비 → Redis 캐시 무효화 + needsSync시 Q-IM API pull
//       + ProvisioningService.triggerProvisioning() 호출
@KafkaListener(topics = "qim.user.events", groupId = "ido-qim-consumer")
```

#### Consumer B — `QimSpMemberEventConsumer` (ido-qim-member-consumer 그룹)
```java
// ido/src/main/java/kr/go/smes/ido/qim/sp/kafka/QimSpMemberEventConsumer.java
// 역할: BIZ/PERSONAL_MEMBER_CONVERTED/REGISTERED/WITHDRAWN 처리
//       → QimSpMemberEventHandler → WebhookDispatcherService → 기관 webhook Outbox
@KafkaListener(topics = "qim.user.events", groupId = "ido-qim-member-consumer")
```

### 2.3 기관 병렬 호출 레이어

**구현 상태**: ✅ **Virtual Thread 기반 병렬 구현 완료** (단, Feature Flag = OFF)

```java
// ido/.../provision/ProvisioningServiceImpl.java
// JDK 21 Virtual Thread 병렬 HTTP — 제안서 핵심 설계와 동일
try (ExecutorService vThreadPool = Executors.newVirtualThreadPerTaskExecutor()) {
    // 최대 68개 기관 동시 HTTP POST
    futures.add(vThreadPool.submit(() -> sendToAgency(endpoint, request, correlationId)));
}

// Feature Flag 제어
@Value("${ido.provisioning.enabled:${IDO_PROVISIONING_ENABLED:false}}")
private boolean provisioningEnabled;  // ← 현재 false (Phase 2 전환 필요)

@Value("${ido.provisioning.dry-run:${IDO_PROVISIONING_DRY_RUN:true}}")
private boolean provisioningDryRun;   // ← 현재 true (HTTP 미발행 관찰 중)
```

### 2.4 DLQ 및 재처리

**구현 상태**: ✅ **ProvisioningOutboxRelay 재시도 + DLQ 구조 구현됨**

- 성공: `outboxRepository.markCompleted()` → COMPLETED
- 실패: PENDING 상태 유지 → `ProvisioningOutboxRelay` 지수 백오프 재시도
- `qim.user.events.dlq` 토픽 정의됨

### 2.5 기관 폴링 API (HTTP Pull 방식)

**구현 상태**: ✅ **`AgencyEventController` HTTP 폴링 API 구현됨**

```java
// ido/.../api/AgencyEventController.java
// GET  /api/v1/agency/events        — 이벤트 폴링 (30~60초 주기 권장)
// POST /api/v1/agency/events/{id}/read — 읽음 처리
// 데이터 소스: ido.webhook_dispatch_outbox
// 인증: X-Agency-Code + X-Agency-Key (HandoffAgencyKeyInterceptor)
```

### 2.6 Redis jobId 전환 상태 추적

**구현 상태**: ⚠️ **부분 구현** (전환 흐름 내 Redis 키 패턴 별도 코드 미확인)

- `QimEventConsumer` 내 `UserStatusCache` Redis 무효화는 구현됨
- 제안서의 "jobId 초기화 → 진행율 폴링" 패턴 전용 구현은 별도 확인 필요

---

## 3. 제안서 vs 현재 구현 갭 분석

### 3.1 토픽 레이어 매핑

| 구분 | 제안서 | 현재 구현 | 평가 |
|------|--------|----------|------|
| 전환 이벤트 토픽 | `member.converted` | `qim.user.events` (이벤트 타입으로 세분화) | **사실상 동일 레이어, 명칭 차이만 존재** |
| Consumer Group | `bff-member-sync` | `ido-qim-consumer` + `ido-qim-member-consumer` | **제안보다 Consumer 분리 더 세밀함 — 개선** |
| Producer | Q-IM Outbox Relay | Q-IM OutboxRelay (`UserServiceImpl`) | **동일** |
| 병렬 기관 호출 | BFF Consumer (제안) | `ProvisioningServiceImpl` Virtual Thread | **동일 패턴, 컴포넌트 위치는 IdO** |
| 기관 알림 수신 | Push (Webhook) | Webhook(`QimSpMemberEventHandler`) + Pull(`AgencyEventController`) | **제안보다 강함 — Push+Pull 이중화** |

### 3.2 아키텍처 레이어 차이 (핵심)

```
[제안서 아키텍처]
Q-IM → Kafka(member.converted) → BFF Consumer → 68개 기관 API 병렬
                                     ↑
                              (단일 컴포넌트 BFF가 담당)

[현재 구현 아키텍처]
Q-IM → Kafka(qim.user.events) ─┬→ QimEventConsumer (캐시 무효화 + ProvisioningService 호출)
                                │     └→ ProvisioningServiceImpl (Virtual Thread 병렬 HTTP)
                                └→ QimSpMemberEventConsumer (기관 Webhook 적재)
                                      └→ WebhookDispatcherService → webhook_dispatch_outbox
                                              └→ WebhookDispatchOutboxRelay → 기관 HTTPS
                                              └→ AgencyEventController (폴링 API for 기관)
```

**판정**: 현재 구현이 제안서보다 **레이어가 더 세밀하게 분리**되어 있다.  
제안서의 단일 "BFF Consumer" 역할이 현재는 `QimEventConsumer` + `QimSpMemberEventConsumer` + `ProvisioningServiceImpl` + `WebhookDispatcherService`로 적절히 분리되었음.

### 3.3 미구현 / 차이 항목

| 항목 | 제안서 | 현재 | 갭 심각도 |
|------|--------|------|---------|
| **Relay 분리 구조** | 별도 Scheduler 서비스가 전담 | 각 서비스 내 @Scheduled 분산 (6개) | 🔴 HIGH |
| F-20 Feature Flag | 즉시 활성화 전제 | `IDO_PROVISIONING_ENABLED=false` | 🔴 BLOCKER (운영 전 활성화 필수) |
| Kafka 의존성 격리 | 각 서비스 Kafka 미의존 | q-im/q-sign/ido 모두 spring-kafka 직접 의존 | 🟠 HIGH |
| 기관 API 인증 | 명시 없음 | API_KEY/HMAC/mTLS 미구현 (`REQUIRES_MANUAL`) | 🔴 BLOCKER (Sprint 17) |
| Kafka 페이로드 암호화 | ISMS-P 필수 강조 | **미구현** (CI는 AES-256-GCM 암호화, but Kafka 페이로드 자체는 평문) | 🟠 HIGH |
| Redis jobId 전환 진행율 | 6단계 상태 추적 | 별도 전용 구현 미확인 | 🟡 MEDIUM |
| Schema Registry | Phase 4 목표 | 미구현 | 🟢 LOW (Phase 4 이후) |
| `member.converted` 토픽명 | 제안 | `qim.user.events` (실제 구현) | 🟢 LOW (이름만 다름, 기능 동일) |

---

## 4. 아키텍처 정합성 평가

### 4.1 긍정 평가 — 제안서가 현재 구현과 일치하는 항목 (8개)

1. **Transactional Outbox 패턴** — DB 저장 + Outbox INSERT 단일 TX (`UserServiceImpl.outboxService.publishInTx()`)
2. **Kafka Relay 비동기 발행** — OutboxRelay 500ms 폴링 발행 (at-least-once)
3. **Virtual Thread 병렬 기관 호출** — `ProvisioningServiceImpl` JDK 21 구현 완료
4. **멱등 처리** — `IdempotentEventStore` 중복 eventId 스킵
5. **지수 백오프 재시도** — `ProvisioningOutboxRelay` PENDING 재처리
6. **PII 최소화** — `identityHash = SHA-256(qimUserId + ":" + epoch)` 기관 전송 (실명/전화 평문 금지)
7. **202 즉시 반환 패턴** — 아키텍처 구조상 지원 (Kafka 발행 후 즉시 응답)
8. **Consumer Group 분리** — `ido-qim-consumer` + `ido-qim-member-consumer` 독립 운영

### 4.2 Relay 분산 구조의 운영 문제점

현재 6개 Relay가 3개 서비스에 분산되어 있어 다음 문제가 발생한다.

**문제 1 — 장애 추적 복잡도**
```
"Kafka 이벤트가 안 나간다" 신고 수신 시 확인 대상:
  q-im:   OutboxServiceImpl (@Scheduled 500ms)
  q-sign: OutboxRelay       (@Scheduled 500ms)
  ido:    IdoOutboxRelay    (@Scheduled 500ms)  ← qsign.auth.events
          QimOutboxRelay    (@Scheduled 1000ms) ← qim.user.events
          ProvisioningOutboxRelay (@Scheduled)  ← 기관 HTTP
          WebhookDispatchOutboxRelay (@Scheduled) ← Webhook
  → 6곳 중 어디가 문제인지 분산 로그에서 추적 필요
```

**문제 2 — Q-IM 이벤트 발행 주체 이중화**
```
qim.user.events 발행 경로가 두 개 존재:
  경로 A: Q-IM OutboxServiceImpl → Kafka 직접 발행
  경로 B: QimOutboxRelay (IdO 내) → ido.outbox 폴링 → Kafka 발행
→ 동일 이벤트 중복 발행 가능성, 책임 소재 불명확
```

**문제 3 — 서비스 장애 시 Relay 동반 중단**
```
Q-IM Pod 전체 장애 → OutboxServiceImpl @Scheduled 중단
  → qim.outbox PENDING 레코드 무한 누적
  → Q-IM 복구까지 이벤트 발행 불가

별도 outbox-scheduler 서비스였다면:
  → Q-IM Pod 장애와 무관하게 DB의 PENDING 레코드 계속 발행 가능
```

**문제 4 — 각 서비스 팀의 Kafka 의존성 불가피**
```
현재: Q-IM 팀이 KafkaProducerConfig, KafkaTemplate, @Scheduled, 재시도 로직 관리 책임
의도: Q-IM 팀은 outboxRepository.save(record) 한 줄만 작성하면 끝
```

### 4.3 제안서 대비 현재 구현의 **개선점**

제안서는 단일 "BFF Consumer" 구조를 제안하였으나, 현재 구현은 더 나은 설계를 채택하였다.

| 항목 | 제안서 | 현재 (더 나음) |
|------|--------|--------------|
| 기관 알림 방식 | Kafka Consumer → REST Push 단방향 | Push Webhook + Pull HTTP 폴링 이중화 |
| Consumer 역할 | BFF Consumer 단일 담당 | 역할별 Consumer 분리 (캐시/프로비저닝/webhook 독립) |
| 기관 연결 방식 | 단순 HTTP POST | DIRECT/BRIDGE/APACHE_GATE/INTERNAL_SSO 4패턴 지원 |
| 인증 방식 | 명시 없음 | API_KEY/HMAC/mTLS 3종 분기 구조 (구현 예정) |

### 4.4 설계 일관성 위험 — AgencyEventController Pull vs Push 이중화



```
현재: 기관은 두 가지 방법으로 이벤트를 수신 가능
  ① WebhookDispatcherService → 기관 HTTPS 엔드포인트로 Push
  ② AgencyEventController.GET /api/v1/agency/events → 기관이 Pull

설계 문제: Kafka 직접 접속 불가 기관(방화벽/보안 제약)을 위해 Pull API가 필요하지만,
         Push + Pull이 동시에 동작할 경우 동일 이벤트 **이중 처리** 위험이 있음.
```

> **권고**: AgencyEventController가 webhook_dispatch_outbox를 데이터 소스로 사용하고,  
> WebhookDispatcherService도 동일 outbox를 기반으로 동작하므로 **mark-as-read** 후  
> webhook 발송 제외 로직 명시 확인 필요. 현재 코드에서 명시적 연결이 불분명함.

---

## 5. Phase별 마이그레이션 현실성 평가

제안서 슬라이드 9의 4단계 마이그레이션을 현재 구현 기준으로 평가한다.

### Phase 1 — 인프라 구성 (Kafka 클러스터 구축)

| 항목 | 제안 | 현재 상태 | 판정 |
|------|------|----------|------|
| Kafka 클러스터 설치 | NHN Cloud K8s 내 | 코드에 Kafka 설정 존재 (운영 환경 별도) | ✅ 설계 완료 |
| `qim.user.events` 토픽 생성 | 신규 생성 | `KafkaTopicConfig` 이미 정의됨 | ✅ **기완료** |
| DLQ 토픽 생성 | 신규 생성 | `qim.user.events.dlq` 정의됨 | ✅ **기완료** |
| Schema Registry | Phase 4 | 미구현 | 🟢 향후 |
| RF=3 설정 | 운영 환경 | `replication-factor:1` (PoC), 운영 변경 필요 | 🟡 주의 |

> **현재 `replication-factor` 기본값이 1** — 운영 Kafka 3-broker 구성 시  
> `IDO_KAFKA_REPLICATION_FACTOR=3`, `IDO_KAFKA_MIN_INSYNC_REPLICAS=2` 환경변수 주입 필수.

### Phase 2 — 파일럿 (일부 기관 전환)

| 항목 | 제안 | 현재 상태 | 판정 |
|------|------|----------|------|
| Outbox Relay 활성화 | Q-IM → Kafka 발행 시작 | ✅ 구현 완료 |  활성화 가능 |
| BFF Consumer 배포 | 이벤트 소비 시작 | ✅ `QimEventConsumer` + `QimSpMemberEventConsumer` 구현 완료 | 배포 가능 |
| 병렬 HTTP 파일럿 (10개 기관) | DRY-RUN → 실발행 전환 | `IDO_PROVISIONING_DRY_RUN=false` + `IDO_PROVISIONING_ENABLED=true` 환경변수만 필요 | ✅ 즉시 가능 |
| **기관 API 인증 구현** | API_KEY/HMAC/mTLS | ⚠️ **`REQUIRES_MANUAL` — 미구현** | 🔴 **BLOCKER** |
| Redis jobId 상태 UI | 전환 진행율 표시 | 별도 구현 미확인 | 🟡 확인 필요 |

> **Phase 2 진입 전 필수**: Sprint 17 `addAuthHeader()` 구현 완료 없이는  
> `REQUIRES_MANUAL_[agencyCode]` placeholder가 기관 API에 전달되어 **전체 인증 실패** 발생.

### Phase 3 — 전면 전환 (68개 기관)

| 항목 | 판정 | 전제 조건 |
|------|------|----------|
| 기존 동기 호출 코드 제거 | 🟡 Sprint 계획 필요 | Phase 2 안정화 후 |
| 68개 기관 API 인증 정보 K8s Secret 주입 | 🔴 **BLOCKER** | Sprint 17 auth 구현 완료 |
| 모니터링 대시보드 (DLQ 알림) | 🟡 미구현 | Phase 2~3 사이 구성 |
| `qim.user.events` → `member.converted` 토픽명 변경 | 🟢 선택 | 이름만 다름, 기능 동일 — 변경 불필요 |

### Phase 4 — 완전 전환

| 항목 | 판정 |
|------|------|
| Schema Registry (Confluent/Apicurio) | 🟢 장기 로드맵 |
| 이벤트 소싱 완전 전환 | 🟢 장기 로드맵 |
| 구버전 토픽 폐기 (`qim.sp.member.events`) | `@Deprecated(forRemoval=true)` 이미 표시됨 |

---

## 6. 인프라 사양 적정성 평가

제안서 슬라이드 11 (TO-BE 인프라)의 NHN Cloud K8s 사양을 분석한다.

### 6.1 K8s 노드 사양

| 항목 | 제안 사양 | 평가 |
|------|----------|------|
| 운영 초기 노드 수 | 9노드 | ✅ 60,000 사용자 기준 적정 |
| 노드 규격 | 8vCPU / 32GB RAM | ✅ JVM Spring Boot 6개 컴포넌트 + Kafka 운영 충분 |
| 개발 환경 | 2노드 | ✅ PoC 수준 적정 |

**처리량 계산 (제안서 기준 검증)**:
```
qim.user.events 12파티션 × Consumer concurrency 6 = 초당 ~900건 처리
60,000명 ÷ 600초(10분 분산) = 초당 100건 → 9배 여유
최악 시나리오(1분): 초당 1,000건 → concurrency 증설 필요
```

> ⚠️ **현재 `qim.user.events` 파티션 수가 6으로 설정됨** (제안서는 12 권장).  
> `KafkaTopicConfig.qimUserEventsTopic()` 파티션을 12로 증설 권고.  
> (토픽 파티션 증설은 무중단 가능, 감소는 불가능)

### 6.2 RDS MariaDB 사양

| 구성 | 제안 | 평가 |
|------|------|------|
| SSO DB | Primary + Standby | ✅ HA 구성 필수 — 적정 |
| IM DB | Primary + Standby | ✅ Outbox 패턴 DB 쓰기 집중 → 스탠바이 필수 |
| DB 암호화 | 명시됨 | ✅ ISMS-P §5.1 준수 필수 |
| DB 접근제어 | 명시됨 | ✅ 적정 |

### 6.3 Kafka 클러스터 사양

| 항목 | 제안 | 평가 |
|------|------|------|
| 브로커 수 | Kafka-prd#1~n (n 미정) | ⚠️ **최소 3 브로커 명시 필요** (RF=3, ISR=2 전제) |
| 스토리지 | NAS 4TB | ✅ 토픽 보존 정책 고려 시 적정 (audit 2년 포함) |
| 압축 | lz4 (현재 구현) | ✅ 제안서와 일치 |

> **권고**: 제안서가 Kafka 브로커 수를 "Kafka-prd#1~n"으로 추상화했으나,  
> RF=3 보장을 위해 **최소 3 브로커** 명시가 필요하며 운영 환경 K8s StatefulSet 구성 계획 수립 권고.

### 6.4 Redis

| 항목 | 평가 |
|------|------|
| 단일 Redis (제안) | ⚠️ Sentinel/Cluster 구성 명시 없음 — 단일 장애점 |
| 현재 코드 | `UserStatusCache` Redis 의존 높음 |

> **권고**: Redis Sentinel (최소) 또는 Redis Cluster 구성 명시 필요.  
> 운영 초기에는 NHN Cloud Redis (관리형) 사용 권장.

### 6.5 SSL VPN / 보안 구성

| 항목 | 제안 | 평가 |
|------|------|------|
| SSL VPN | 명시됨 | ✅ 기관 연동 내부망 접속 필수 |
| 모니터링 | 명시됨 | ✅ Kafka DLQ 알림 연계 필요 |
| 백업 | 명시됨 | ✅ DB + NAS 백업 정책 수립 필요 |

---

## 7. ISMS-P 대응 현황

제안서 슬라이드 9에서 ISMS-P를 별도 주의사항으로 강조한 항목을 확인한다.

### 7.1 Kafka 페이로드 개인정보 암호화

| 항목 | ISMS-P 요건 | 현재 구현 | 판정 |
|------|------------|----------|------|
| Kafka 페이로드 내 실명 | 암호화 또는 불포함 필수 | `ProvisioningServiceImpl`: `identityHash = SHA-256(qimUserId:epoch)` — **실명 없음** | ✅ 준수 |
| Kafka 페이로드 내 전화번호 | 암호화 또는 불포함 필수 | `ProvisioningRequest`: `qimUserId` + `identityHash` + `eventType` — 전화번호 없음 | ✅ 준수 |
| Kafka 페이로드 내 CI | 암호화 필수 | `qim.user.events` payload 내 CI 직접 포함 여부 미확인 | ⚠️ **확인 필요** |
| 기관 전송 payload | PII 최소화 | `addAuthHeader` 주석: "실명/전화 평문 절대 포함 금지" 명시 | ✅ 정책 명시 |

### 7.2 CI 암호화 확인 필요 사항

```java
// UserServiceImpl.java — outboxService.publishInTx(buildUserEvent(...))
// → qim.user.events payload에 CI 원문이 포함되는지 확인 필요

// 현재 확인된 암호화 범위:
// ✅ DB 저장 시: CiCryptoServiceImpl (AES-256-GCM) — UserProfileJpaEntity
// ❓ Kafka 전송 시: UserEvent.identifierHash → SHA-256(CI)인지 CI 암호화값인지 불명확
```

### 7.3 ISMS-P 기타 항목

| 항목 | 현재 상태 | 판정 |
|------|----------|------|
| DB 암호화 | `UserProfileJpaEntity` AES-256-GCM 적용 확인 | ✅ |
| DB 접근제어 | RDS IAM 인증 + VPC 격리 (인프라 계획) | ✅ |
| 개인정보 파기 스케줄 | `MEMBER_WITHDRAWN` 이벤트 처리 시 Phase 3 구현 예정 | 🟡 미구현 |
| 감사 로그 | `platform.audit.log` 토픽 2년 보존 | ✅ |
| 전송 구간 암호화 | Kafka TLS + 기관 HTTPS webhook | ✅ (TLS 설정 확인 필요) |

---

## 8. 권고 사항

### 8.0 [신규 — 최우선] outbox-scheduler 독립 모듈 신설 (🔴 HIGH)

#### 권고-00: `outbox-scheduler` 신규 Gradle 서브모듈 신설

제안서의 핵심 의도를 실현하기 위해 **모든 서비스의 Relay 스케줄러를 단일 독립 서비스로 분리**한다.  
상세 설계: `docs/internal/architecture/ADR-2026-005-outbox-scheduler-module.md` 참조.

**목표 구조**:
```
[Q-IM]    outbox INSERT만  ─────────────────┐
[Q-Sign]  outbox INSERT만  ─────────────────┤
[IdO]     outbox INSERT만  ─────────────────┤
                                            ▼
                               [outbox-scheduler 서비스]
                                 - 모든 outbox 테이블 폴링
                                 - Kafka 발행 전담
                                 - 재시도/DLQ 중앙 관리
                                 - 각 서비스와 DB 공유
```

**각 서비스 팀에서 제거 가능한 것들**:
```
Q-IM:   OutboxServiceImpl @Scheduled 메서드 제거, KafkaTemplate 의존성 제거
Q-Sign: OutboxRelay.java 전체 제거, KafkaTemplate 의존성 제거
IdO:    IdoOutboxRelay, QimOutboxRelay 제거 (ProvisioningOutboxRelay는 HTTP 재시도라 유지)
```

**Sprint 반영**: Sprint 17 설계 확정 → Sprint 18 구현

### 8.1 즉시 채택 권고 (이미 구현됨, 활성화만 필요)

#### 권고-01: F-20 Feature Flag 활성화 로드맵 수립 (🔴 BLOCKER)

```
Sprint 17 완료 후 (기관 API 인증 구현 후):
  환경변수: IDO_PROVISIONING_ENABLED=true
           IDO_PROVISIONING_DRY_RUN=false (파일럿 기관 10개 대상)
  검증: ProvisioningIntegrationTest 통과 확인
  모니터링: DLQ 알림 구성 후 전면 전환
```

#### 권고-02: `qim.user.events` 파티션 12로 증설

```java
// KafkaTopicConfig.qimUserEventsTopic()
// 현재: .partitions(6)
// 권고: .partitions(12)  ← 60k 사용자 최악 시나리오 대응
// 또는 환경변수: IDO_KAFKA_PARTITION_COUNT_MAIN=12
```

#### 권고-03: Kafka RF=3 운영 환경 적용

```yaml
# K8s ConfigMap 또는 환경변수
IDO_KAFKA_REPLICATION_FACTOR: "3"
IDO_KAFKA_MIN_INSYNC_REPLICAS: "2"
QIM_KAFKA_REPLICATION_FACTOR: "3"
```

### 8.2 Sprint 17 필수 구현 (🔴 BLOCKER)

#### 권고-04: 기관 API 인증 구현 (`addAuthHeader`)

```java
// ProvisioningServiceImpl.addAuthHeader() 현재 상태:
// ⚠️ "REQUIRES_MANUAL_[agencyCode]" placeholder 전송 중 → 기관 API 인증 실패
// Sprint 17 구현 사항:
case "API_KEY" -> {
    String apiKey = secretManagerClient.getSecret(endpoint.getAuthCredentialRef());
    headers.set("X-Api-Key", apiKey);
}
case "HMAC" -> {
    String signature = hmacSha256(payloadJson + ":" + Instant.now().getEpochSecond(), secretKey);
    headers.set("X-Signature", signature);
}
case "MTLS" -> {
    // RestTemplate에 클라이언트 KeyStore 설정
}
```

### 8.3 ISMS-P 필수 보완 (🟠 HIGH)

#### 권고-05: Kafka 페이로드 CI 포함 여부 감사

```
조치: UserEvent.java payload 필드 전체 검토
     → CI 원문 또는 복호화 가능 값이 포함된 경우
       identifierHash(SHA-256)만 포함하도록 수정
감사 일정: Sprint 17 이전 (Phase 2 파일럿 전)
```

#### 권고-06: Kafka TLS 설정 명시화

```yaml
# application.yml — 운영 환경
spring.kafka.properties:
  security.protocol: SSL
  ssl.truststore.location: /etc/kafka/ssl/truststore.jks
  ssl.keystore.location: /etc/kafka/ssl/keystore.jks
  # K8s Secret으로 주입
```

### 8.4 중기 보완 권고 (🟡 MEDIUM)

#### 권고-07: Push/Pull 이중화 이벤트 중복 처리 정책 명문화

현재 `AgencyEventController`(Pull) + `WebhookDispatcherService`(Push)가 동일 이벤트를 이중으로 기관에 전달할 가능성이 있다. 다음 중 하나를 선택하여 문서화 필요:
- **Option A**: Push 전용 — `AgencyEventController`는 Kafka 직접 접근 불가 기관 전용, webhook 등록 기관은 Pull API 미사용
- **Option B**: Pull 전용 — webhook 미지원 기관 대상만 `AgencyEventController` 노출

#### 권고-08: Redis HA 구성 명시

```
현재: Redis 단일 인스턴스 추정
권고: NHN Cloud Redis (Sentinel or Cluster) 전환
     UserStatusCache TTL: 5분 유지
     jobId 상태 추적 TTL: 24시간 (전환 완료 후 만료)
```

#### 권고-09: `member.converted` 토픽명 도입 여부 결정

제안서의 `member.converted` 토픽명은 현재 `qim.user.events`와 기능이 동일하다.  
**재명명 권고하지 않음** — 이미 Consumer Group, Relay, DLQ 전체가 `qim.user.events` 기준으로 구현되었으며, 토픽 재생성 없이 파티션 증설이 더 실용적이다.

### 8.5 장기 로드맵 (Phase 4, 🟢 LOW)

#### 권고-10: Schema Registry 도입

```
시점: Phase 4 (안정화 후)
대상: qim.user.events, ido.handoff.events
도구: Apicurio Registry (오픈소스) 또는 Confluent Schema Registry
이점: 이벤트 스키마 버전 관리, Consumer 하위 호환성 보장
```

---

## 9. 판정 요약표

### 9.1 제안서 채택/보완/기각 분류

| 제안 항목 | 판정 | 사유 |
|----------|------|------|
| Transactional Outbox INSERT | ✅ **이미 구현** | `UserServiceImpl.publishInTx()` 등 모든 서비스 구현 |
| Relay 독립 서비스 분리 | ❌ **미구현** | 각 서비스에 @Scheduled Relay 분산 내장 — 제안서 핵심 의도와 반대 |
| Kafka 비동기 이벤트 발행 | ⚠️ **부분 구현** | 토픽/발행 자체는 동작하나 Relay가 서비스 내 분산 |
| Consumer Group 분리 | ✅ **이미 구현 (개선됨)** | 제안보다 더 세밀한 역할 분리 |
| Virtual Thread 병렬 기관 호출 | ✅ **이미 구현 (비활성)** | F-20 Flag 활성화 필요 |
| DLQ + 재처리 | ✅ **이미 구현** | `ProvisioningOutboxRelay` + DLQ 토픽 |
| 202 즉시 반환 | ✅ **구조적 지원** | Kafka 발행 후 즉시 응답 패턴 |
| `member.converted` 토픽명 | 🔄 **보완** | `qim.user.events`로 유지 (재명명 불필요) |
| BFF Consumer 단일화 | 🔄 **보완** | 역할 분리 개선 채택 |
| Redis jobId 진행율 | 🟡 **확인 필요** | 전용 구현 여부 추가 확인 |
| Kafka 파티션 12개 | 🔄 **보완 채택** | 현재 6 → 12 증설 권고 |
| RF=3, ISR=2 | 🔄 **보완 채택** | 운영 환경 변수 주입 필요 |
| ISMS-P 페이로드 암호화 | ⚠️ **부분 준수** | CI 포함 여부 감사 필요 |
| Schema Registry | 📅 **Phase 4** | 장기 로드맵 |
| Redis HA | 🔄 **보완 권고** | Sentinel/Cluster 전환 필요 |

### 9.2 스프린트 반영 우선순위

| 우선순위 | 항목 | 스프린트 | 담당 |
|---------|------|---------|------|
| 🔴 P0 | **`outbox-scheduler` 독립 모듈 신설** | Sprint 17 설계 / Sprint 18 구현 | Arch팀 + BE팀 |
| 🔴 P0 | 기관 API 인증 구현 (`addAuthHeader`) | Sprint 17 | BE팀 |
| 🔴 P0 | `qim.user.events` 파티션 12 증설 | Sprint 17 | Infra팀 |
| 🔴 P0 | Kafka RF=3 운영 환경 설정 | Sprint 17 | Infra팀 |
| 🟠 P1 | Kafka 페이로드 CI 포함 여부 감사 | Sprint 17 | BE팀 |
| 🟠 P1 | Kafka TLS 운영 설정 명시화 | Sprint 17 | Infra팀 |
| 🟠 P1 | F-20 활성화 (파일럿 10개 기관) | Sprint 18 | BE팀 + 기관팀 |
| 🟡 P2 | Redis HA 전환 | Sprint 18 | Infra팀 |
| 🟡 P2 | Push/Pull 이중화 정책 문서화 | Sprint 17 | Arch팀 |
| 🟡 P2 | Redis jobId 진행율 구현 확인 | Sprint 17 | BE팀 |
| 🟢 P3 | Schema Registry 도입 | Phase 4 | 전체 |

---

## 부록 — 참조 파일 목록

| 파일 | 역할 |
|------|------|
| `q-im/.../UserServiceImpl.java` | Outbox 발행 진입점 |
| `q-im/.../KafkaTopicConfig.java` | `qim.user.events` 토픽 정의 |
| `platform-common/.../UserEvent.java` | Kafka 이벤트 스키마 |
| `ido/.../QimEventConsumer.java` | Consumer A — 캐시 무효화 + Provisioning 트리거 |
| `ido/.../qim/sp/kafka/QimSpMemberEventConsumer.java` | Consumer B — 기관 Webhook 적재 |
| `ido/.../provision/ProvisioningServiceImpl.java` | Virtual Thread 병렬 기관 HTTP 발행 |
| `ido/.../api/AgencyEventController.java` | 기관 HTTP 폴링 API |
| `ido/.../config/KafkaTopicConfig.java` | IdO 토픽 정의 (handoff, session, audit) |
| `docs/internal/architecture/ADR-2026-004-*.md` | INTERNAL_SSO 패턴 결정 |

---

*EDA-2026-001 — 최초 작성 2026-05-16 | 다음 검토 Sprint 17 종료 후*
