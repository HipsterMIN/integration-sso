# ADR-2026-005 — outbox-scheduler 독립 모듈 신설

> **문서 ID**: ADR-2026-005  
> **작성일**: 2026-05-16  
> **작성자**: genspark_ai_developer  
> **상태**: PROPOSED  
> **결정권자**: 아키텍처 리뷰 위원회  
> **관련 문서**: EDA-2026-001, ADR-2026-004  
> **Sprint 계획**: 설계 Sprint 17 / 구현 Sprint 18

---

## 목차

1. [배경 및 동기](#1-배경-및-동기)
2. [현재 구조의 문제](#2-현재-구조의-문제)
3. [결정 사항](#3-결정-사항)
4. [outbox-scheduler 모듈 설계](#4-outbox-scheduler-모듈-설계)
5. [각 서비스 변경 사항](#5-각-서비스-변경-사항)
6. [마이그레이션 전략](#6-마이그레이션-전략)
7. [고려한 대안](#7-고려한-대안)
8. [리스크 및 대응](#8-리스크-및-대응)

---

## 1. 배경 및 동기

### 1.1 EDA 제안서의 핵심 의도

`중기원패스_EDA_아키텍처_제안_20260515` 제안서가 Outbox 패턴에서 실질적으로 강조한 핵심은 다음이다:

> **각 서비스(Q-IM, Q-Sign, IdO)는 Outbox 테이블에 INSERT만 하면 되고,  
> Kafka 이벤트 발행은 별도 스케줄러 서비스가 전담한다.  
> 각 개발팀은 Kafka를 전혀 신경 쓰지 않아도 된다.**

이 의도는 단순히 응답 시간 단축(13.6초→300ms)보다 더 근본적인 아키텍처 원칙이다:

- **관심사 분리**: 비즈니스 로직(서비스) ↔ 이벤트 발행 인프라(스케줄러) 완전 분리
- **장애 격리**: 서비스 장애가 이벤트 발행 장애로 전파되지 않음
- **개발 단순화**: 각 팀이 Kafka 라이브러리, 직렬화, 재시도, 브로커 설정을 알 필요 없음
- **운영 일원화**: 이벤트 발행 장애 조사 경로가 단일화됨

### 1.2 현재 구현과의 괴리

현재 코드는 Outbox INSERT는 올바르게 구현되어 있으나, **Relay가 각 서비스 내부에 분산**되어 있다.

---

## 2. 현재 구조의 문제

### 2.1 Relay 분산 현황

```
┌──────────────────────────────────────────────────────────┐
│ Q-IM 서비스                                               │
│  ├─ UserServiceImpl.outboxService.publishInTx() ← INSERT  │
│  ├─ OutboxServiceImpl @Scheduled(500ms)  ← Relay 자체 보유 │
│  └─ KafkaProducerConfig, KafkaTemplate   ← Kafka 직접 의존 │
├──────────────────────────────────────────────────────────┤
│ Q-Sign 서비스                                             │
│  ├─ outboxRepository.save(record)         ← INSERT        │
│  ├─ OutboxRelay @Scheduled(500ms)        ← Relay 자체 보유 │
│  └─ KafkaProducerConfig, KafkaTemplate   ← Kafka 직접 의존 │
├──────────────────────────────────────────────────────────┤
│ IdO 서비스                                                │
│  ├─ ido.outbox INSERT                     ← INSERT        │
│  ├─ IdoOutboxRelay @Scheduled(500ms)     ← Relay 자체 보유 │
│  ├─ QimOutboxRelay @Scheduled(1000ms)    ← Q-IM 이벤트 대신 발행 (혼재!) │
│  ├─ ProvisioningOutboxRelay @Scheduled   ← 기관 HTTP 재시도 │
│  ├─ WebhookDispatchOutboxRelay @Scheduled ← Webhook 재시도 │
│  └─ KafkaTemplate                         ← Kafka 직접 의존 │
└──────────────────────────────────────────────────────────┘
```

**총 Kafka 관련 @Scheduled Relay: 6개, 3개 서비스에 분산**

### 2.2 문제 1 — "Kafka를 신경 안 써도 된다"가 불성립

| 현재 각 팀의 책임 | 제안서 의도 |
|----------------|------------|
| KafkaProducerConfig 작성 및 유지 | 없음 |
| KafkaTemplate 빈 관리 | 없음 |
| @Scheduled Relay 메서드 관리 | 없음 |
| 재시도 로직 구현 | 없음 |
| Kafka 브로커 bootstrap-servers 설정 | 없음 |
| Kafka 장애 시 자팀 Relay 모니터링 | 없음 |

### 2.3 문제 2 — Q-IM 이벤트 발행 주체 이중화

```
qim.user.events 발행 경로 A: Q-IM OutboxServiceImpl → kafkaTemplate.send()
qim.user.events 발행 경로 B: IdO QimOutboxRelay → ido.outbox 폴링 → kafkaTemplate.send()

→ 동일 이벤트가 두 경로에서 발행될 수 있음
→ 어느 경로로 발행됐는지 Consumer가 구분 불가
→ 책임 소재 불명확: Q-IM 팀? IdO 팀?
```

### 2.4 문제 3 — 서비스 장애 시 이벤트 파이프라인 중단

```
시나리오: Q-IM Pod 전체 OOM Crash
현재 결과:
  → Q-IM OutboxServiceImpl @Scheduled 중단
  → qim.outbox PENDING 레코드 무한 누적
  → Q-IM 복구 전까지 모든 회원 이벤트 미발행
  → 기관 동기화 지연

outbox-scheduler가 있었다면:
  → Q-IM Pod 장애와 무관하게 스케줄러가 DB 폴링 지속
  → PENDING 레코드 자동 발행 유지
  → 장애 격리 완성
```

### 2.5 문제 4 — 운영 장애 조사 경로 분산

```
"왜 이벤트가 안 나갔나?" 조사 시 확인 경로:
  □ Q-IM OutboxServiceImpl 로그
  □ Q-Sign OutboxRelay 로그  
  □ IdO IdoOutboxRelay 로그
  □ IdO QimOutboxRelay 로그
  □ IdO ProvisioningOutboxRelay 로그
  □ IdO WebhookDispatchOutboxRelay 로그
  □ Kafka 브로커 상태
  → 6개 위치 + Kafka = 7개 동시 확인 필요
```

---

## 3. 결정 사항

### 채택: `outbox-scheduler` 독립 Spring Boot 모듈 신설

```
settings.gradle.kts 에 추가:
  include("outbox-scheduler")
```

**원칙**:
1. Q-IM, Q-Sign, IdO는 outbox 테이블에 **INSERT만** 수행한다
2. Kafka 발행 책임은 **outbox-scheduler 전담**이다
3. 각 서비스에서 Kafka 관련 Relay 코드를 **단계적으로 제거**한다
4. outbox-scheduler는 **모든 서비스의 DB에 read 권한**을 갖는다 (별도 read-replica 권장)

---

## 4. outbox-scheduler 모듈 설계

### 4.1 모듈 위치 및 구조

```
onepass-platform/
├── platform-common/
├── q-sign/
├── q-im/
├── ido/
├── outbox-scheduler/          ← 신규
│   ├── build.gradle.kts
│   └── src/main/java/kr/go/smes/scheduler/
│       ├── OutboxSchedulerApplication.java
│       ├── config/
│       │   ├── KafkaProducerConfig.java     ← Kafka 설정 단일화
│       │   ├── DataSourceConfig.java        ← 멀티 DataSource (qim, qsign, ido)
│       │   └── SchedulerProperties.java
│       ├── relay/
│       │   ├── OutboxRelayCoordinator.java  ← 전체 조율
│       │   ├── QimOutboxRelay.java          ← qim.outbox 전담
│       │   ├── QSignOutboxRelay.java        ← qsign.outbox 전담
│       │   └── IdoOutboxRelay.java          ← ido.outbox 전담
│       ├── repository/
│       │   ├── QimOutboxPollingRepository.java
│       │   ├── QSignOutboxPollingRepository.java
│       │   └── IdoOutboxPollingRepository.java
│       └── monitor/
│           └── OutboxHealthIndicator.java   ← PENDING 레코드 수 모니터링
└── settings.gradle.kts
```

### 4.2 핵심 동작 원리

```
[outbox-scheduler (독립 프로세스)]

@Scheduled(fixedDelay = 500ms)
QimOutboxRelay.relay():
  1. SELECT * FROM qim.outbox
        WHERE status = 'PENDING'
          AND (next_retry_at IS NULL OR next_retry_at <= NOW())
        ORDER BY created_at ASC
        LIMIT 100
        FOR UPDATE SKIP LOCKED      ← 멀티 Pod 안전
  2. kafkaTemplate.send('qim.user.events', partitionKey, payload)
  3. 성공 → UPDATE status = 'PUBLISHED', published_at = NOW()
     실패 → incrementRetryWithBackoff() 또는 markFailed()

@Scheduled(fixedDelay = 500ms)
QSignOutboxRelay.relay():
  → qsign.outbox → 'qsign.auth.events' 발행

@Scheduled(fixedDelay = 500ms)
IdoOutboxRelay.relay():
  → ido.outbox → topic 컬럼 기준 발행 (ido.handoff.events 등)
```

### 4.3 DataSource 구성 전략

outbox-scheduler는 각 서비스의 DB에서 outbox 테이블만 읽어야 한다.

**Option A — 서비스별 별도 DataSource (권장)**
```yaml
# outbox-scheduler application.yml
scheduler:
  datasources:
    qim:
      url: jdbc:mariadb://qim-db-read-replica:3306/qim
      username: ${QIM_OUTBOX_DB_USER}     # outbox 테이블 SELECT/UPDATE 권한만
      password: ${QIM_OUTBOX_DB_PASS}
    qsign:
      url: jdbc:mariadb://qsign-db-read-replica:3306/qsign
      username: ${QSIGN_OUTBOX_DB_USER}
      password: ${QSIGN_OUTBOX_DB_PASS}
    ido:
      url: jdbc:mariadb://ido-db-read-replica:3306/ido
      username: ${IDO_OUTBOX_DB_USER}
      password: ${IDO_OUTBOX_DB_PASS}
```

**Option B — 공유 DB 스키마 (단기 구현)**
```yaml
# 초기 단계: 각 서비스 DB에 별도 계정으로 직접 연결
# 장기: Read Replica 분리
```

### 4.4 DB 계정 권한 최소화

```sql
-- outbox-scheduler 전용 DB 계정 (각 서비스 DB)
-- qim DB
CREATE USER 'outbox_scheduler'@'%' IDENTIFIED BY '...';
GRANT SELECT, UPDATE ON qim.outbox TO 'outbox_scheduler'@'%';
-- qsign DB
GRANT SELECT, UPDATE ON qsign.outbox TO 'outbox_scheduler'@'%';
-- ido DB
GRANT SELECT, UPDATE ON ido.outbox TO 'outbox_scheduler'@'%';
-- 비즈니스 테이블에는 접근 권한 없음 (최소 권한 원칙)
```

### 4.5 멀티 인스턴스 안전성

```
outbox-scheduler Pod 2개 이상 운영 시 중복 발행 방지:
  → FOR UPDATE SKIP LOCKED 사용 (각 Relay 동일 적용)
  → 동일 레코드는 반드시 하나의 Pod만 처리
  → Consumer IdempotentEventStore가 최후 방어선
```

### 4.6 모니터링

```java
@Component
public class OutboxHealthIndicator implements HealthIndicator {
    // PENDING 레코드 수 > 임계값(1000) → WARNING
    // FAILED 레코드 존재 → CRITICAL 알람
    // 각 서비스 별 PENDING 레코드 수를 Prometheus 메트릭으로 노출
}
```

```
메트릭 예시:
  outbox_pending_count{service="qim"}   = 0
  outbox_pending_count{service="qsign"} = 0
  outbox_pending_count{service="ido"}   = 3
  outbox_failed_count{service="qim"}    = 0
```

### 4.7 build.gradle.kts

```kotlin
// outbox-scheduler/build.gradle.kts
dependencies {
    implementation(project(":platform-common"))  // DomainEvent 등 공통 타입

    // Kafka — 이제 이 모듈만 Kafka 의존성을 가짐
    implementation("org.springframework.kafka:spring-kafka")

    // DB — 멀티 DataSource
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.mariadb.jdbc:mariadb-java-client")

    // 스케줄링
    implementation("org.springframework.boot:spring-boot-starter")

    // 모니터링
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

---

## 5. 각 서비스 변경 사항

### 5.1 Q-IM — 제거 대상

```
제거:
  q-im/build.gradle.kts
    - implementation("org.springframework.kafka:spring-kafka")  ← 삭제
  q-im/.../KafkaProducerConfig.java                            ← 삭제
  q-im/.../outbox/OutboxServiceImpl.java
    - relayPendingEvents() 메서드 제거 (publishInTx는 유지)
    - relayFailedEvents() 메서드 제거
    - KafkaTemplate 의존성 제거
  
유지:
  q-im/.../outbox/OutboxService.java
    - publishInTx() 인터페이스 유지
  q-im/.../outbox/OutboxServiceImpl.java
    - publishInTx() 구현 유지 (DB INSERT 로직)
    - OutboxRepository 의존성 유지
```

변경 후 `OutboxServiceImpl`:
```java
// Before: Kafka 의존성 + @Scheduled Relay 포함
// After: DB INSERT만 담당, Kafka 코드 완전 제거
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void publishInTx(DomainEvent event) {
        OutboxRecord record = OutboxRecord.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType())
                .partitionKey(event.getQimUserId())
                .eventVersion(event.getEventVersion())
                .payload(serialize(event))
                .status(OutboxRecord.OutboxStatus.PENDING)
                .build();
        outboxRepository.save(record);
        // 끝. Kafka는 outbox-scheduler가 처리.
    }

    // relayPendingEvents() 제거
    // relayFailedEvents() 제거
    // KafkaTemplate 의존성 제거
}
```

### 5.2 Q-Sign — 제거 대상

```
제거:
  q-sign/build.gradle.kts
    - implementation("org.springframework.kafka:spring-kafka")  ← 삭제
  q-sign/.../KafkaProducerConfig.java                          ← 삭제
  q-sign/.../outbox/OutboxRelay.java                           ← 전체 삭제

유지:
  q-sign/.../outbox/QSignOutboxRepository.java   (INSERT 로직)
  q-sign/.../outbox/QSignOutboxRecord.java
```

### 5.3 IdO — 일부 제거, 일부 유지

```
제거 (Kafka 발행 Relay):
  ido/.../infrastructure/outbox/IdoOutboxRelay.java    ← 삭제
  ido/.../infrastructure/outbox/QimOutboxRelay.java    ← 삭제

유지 (HTTP 발행 Relay — outbox-scheduler 범위 아님):
  ido/.../provision/ProvisioningOutboxRelay.java       ← 유지 (기관 HTTP POST 재시도)
  ido/.../webhook/WebhookDispatchOutboxRelay.java      ← 유지 (Webhook HTTP POST 재시도)

이유: ProvisioningOutboxRelay와 WebhookDispatchOutboxRelay는
     Kafka 발행이 아닌 기관 HTTP POST 재시도이므로
     outbox-scheduler 범위(Kafka 전담)에 해당하지 않음.
     단, 이 두 Relay도 장기적으로 별도 worker 서비스로 분리 검토 가능.
```

### 5.4 변경 후 각 서비스 책임 요약

| 서비스 | 변경 전 | 변경 후 |
|--------|--------|--------|
| Q-IM | INSERT + Kafka Relay | **INSERT만** |
| Q-Sign | INSERT + Kafka Relay | **INSERT만** |
| IdO | INSERT + Kafka Relay × 2 + HTTP Relay × 2 | INSERT + HTTP Relay × 2 (Kafka Relay 제거) |
| outbox-scheduler | 없음 | **Kafka Relay 전담** (qim/qsign/ido) |

---

## 6. 마이그레이션 전략

### Phase 1 — outbox-scheduler 모듈 신설 (Sprint 18)

```
1. outbox-scheduler 모듈 신설 (settings.gradle.kts 추가)
2. QimOutboxRelay, QSignOutboxRelay, IdoOutboxRelay 구현
3. 각 서비스 DB에 outbox_scheduler 전용 계정 생성 (권한 최소화)
4. outbox-scheduler K8s Deployment 배포 (독립 Pod)
5. 기존 서비스 Relay는 Feature Flag(RELAY_ENABLED=false)로 비활성화
   → 이 시점에서 outbox-scheduler가 단독 발행 주체
```

### Phase 2 — 각 서비스 Kafka 코드 제거 (Sprint 19)

```
순서: Q-Sign → Q-IM → IdO 순으로 제거
  (영향 범위가 작은 서비스부터)

Q-Sign:
  - OutboxRelay.java 삭제
  - KafkaProducerConfig.java 삭제
  - build.gradle.kts kafka 의존성 삭제
  - @EnableScheduling 제거 (다른 @Scheduled 없는 경우)

Q-IM:
  - OutboxServiceImpl의 relayPendingEvents/relayFailedEvents 제거
  - KafkaProducerConfig.java 삭제
  - KafkaTemplate 의존성 제거
  - build.gradle.kts kafka 의존성 삭제

IdO:
  - IdoOutboxRelay.java 삭제
  - QimOutboxRelay.java 삭제
  (ProvisioningOutboxRelay, WebhookDispatchOutboxRelay는 유지)
```

### Phase 3 — 안정화 및 최적화 (Sprint 20)

```
- Read Replica 분리 (outbox-scheduler만 read-replica 사용)
- PENDING 레코드 수 Prometheus 알람 설정
- outbox-scheduler HPA(수평 자동 확장) 설정
- 각 서비스에서 kafka 의존성 완전 제거 검증
```

---

## 7. 고려한 대안

### 대안 A — 현행 유지 (채택 안 함)

각 서비스에 Relay를 두는 현재 구조를 유지한다.

**기각 이유**: 제안서의 핵심 의도(각 팀이 Kafka 신경 안 써도 됨)를 달성하지 못하며, 운영 복잡도가 높고 장애 격리가 불완전하다.

### 대안 B — IdO 내 Relay 통합 (채택 안 함)

Q-IM, Q-Sign의 Relay를 모두 IdO 내부로 이동하여 IdO 하나에서 전체 관리한다.

**기각 이유**: 
- IdO가 비즈니스 서비스(인증/연동)와 인프라 서비스(이벤트 발행)를 동시에 담당하는 비대한 서비스가 됨
- IdO 배포/재시작 시 이벤트 발행도 중단되는 단일 장애점 문제 동일
- 실제로 `QimOutboxRelay`가 이미 IdO에 있는데, 이것 자체가 이 대안의 문제를 보여줌

### 대안 C — Debezium CDC (채택 안 함)

별도 Relay 스케줄러 없이, Debezium이 DB의 outbox 테이블 변경을 캡처하여 Kafka로 직접 발행한다.

**기각 이유**:
- NHN Cloud RDS MariaDB에서 Debezium CDC를 위한 binlog 활성화 및 계정 설정 필요 → 운영팀 추가 작업
- Debezium Connector 별도 관리 부담
- 현재 스프린트 일정상 도입 검토 시간 부족
- 단, Phase 4 장기 로드맵으로 재검토 가능

### 대안 D — Kafka Streams 또는 Kafka Connect (채택 안 함)

같은 이유로 현재 시점 기각. 인프라 복잡도 증가.

---

## 8. 리스크 및 대응

| 리스크 | 가능성 | 영향 | 대응 |
|--------|--------|------|------|
| outbox-scheduler 장애 시 전체 이벤트 파이프라인 중단 | 중 | 고 | K8s `replicas: 2` + FOR UPDATE SKIP LOCKED 멀티 Pod 지원 + HPA |
| Phase 1 (scheduler ON) + 기존 Relay 미비활성화 → 중복 발행 | 중 | 중 | Feature Flag으로 기존 Relay 완전 비활성화 먼저 확인 후 scheduler 기동 |
| outbox-scheduler의 서비스 DB 직접 접근 → 보안 우려 | 낮 | 중 | 전용 DB 계정 최소 권한(outbox 테이블 SELECT/UPDATE만), VPC 내부 통신 |
| 각 서비스 Kafka 의존성 제거 후 예상치 못한 컴파일 오류 | 낮 | 낮 | Phase 2에서 서비스별 순차 제거, PR별 CI 빌드 검증 |
| Read Replica 지연으로 PENDING 레코드 미조회 | 낮 | 낮 | Phase 3까지 Primary DB 직접 연결 유지, Phase 3에서 Read Replica 전환 |

---

## 부록 — 현재 vs 목표 비교

### 현재 (Before)

```
Q-IM 팀이 알아야 하는 것:
  - OutboxRepository (INSERT) ← 비즈니스 로직
  - KafkaProducerConfig (Kafka 브로커 설정)
  - OutboxServiceImpl.relayPendingEvents() (폴링 주기, 배치 크기)
  - OutboxServiceImpl.relayFailedEvents() (재시도 횟수, 백오프)
  - KafkaTemplate.send() 비동기 결과 처리
  - spring-kafka 라이브러리 버전 관리

Q-Sign 팀이 알아야 하는 것:
  - 동일 (별도로 중복 구현)

IdO 팀이 알아야 하는 것:
  - IdoOutboxRelay (IdO 이벤트)
  - QimOutboxRelay (Q-IM 이벤트까지 대신)
  - ProvisioningOutboxRelay
  - WebhookDispatchOutboxRelay
  → 4개 Relay 동시 관리
```

### 목표 (After)

```
Q-IM 팀이 알아야 하는 것:
  - OutboxRepository (INSERT) ← 비즈니스 로직만

Q-Sign 팀이 알아야 하는 것:
  - OutboxRepository (INSERT) ← 비즈니스 로직만

IdO 팀이 알아야 하는 것:
  - OutboxRepository (INSERT) ← 비즈니스 로직
  - ProvisioningOutboxRelay  ← HTTP 재시도 (Kafka 아님, 유지)
  - WebhookDispatchOutboxRelay ← HTTP 재시도 (Kafka 아님, 유지)

outbox-scheduler 팀(인프라/플랫폼 팀)이 알아야 하는 것:
  - 전체 Kafka 발행 로직
  - 모든 outbox 테이블 폴링
  - 재시도/DLQ 정책
  - Kafka 브로커 설정
```

---

*ADR-2026-005 — 최초 작성 2026-05-16 | 상태: PROPOSED → 아키텍처 리뷰 후 ACCEPTED/REJECTED*
