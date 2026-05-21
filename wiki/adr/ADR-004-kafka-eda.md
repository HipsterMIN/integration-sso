# ADR-004: Apache Kafka EDA (이벤트 기반 아키텍처) 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-004 |
| **제목** | Apache Kafka 기반 EDA(Event-Driven Architecture) 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2025-Q4 (Sprint 2) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `q-sign/config/KafkaTopicConfig.java`, `ido/config/KafkaConsumerConfig.java`, `ido/kafka/QimEventConsumer.java` |

---

## 컨텍스트 (Context)

마이크로서비스 간 비동기 통신이 필요한 시나리오:

1. **Q-IM 회원 이벤트 → IdO**: 신규 가입·전환·탈퇴 시 모든 기관에 프로비저닝 필요
2. **Q-Sign 인증 완료 → IdO**: 인증 결과 전달 후 Handoff 처리
3. **감사 로그**: 모든 인증 이벤트 비동기 기록
4. **FE Advisory**: 세션 만료·강제 로그아웃 브로드캐스트

REST 동기 호출만으로는:
- 프로비저닝 68개 기관 발송 중 Q-IM 응답 지연 → 사용자 가입 UX 저하
- Q-Sign 장애 시 인증 결과 유실 위험
- 감사 로그 동기 기록 → 응답 지연

---

## 결정 (Decision)

**Apache Kafka**를 이벤트 브로커로 채택한다.

### Kafka 토픽 설계

| 토픽 | 방향 | 파티션 | 설명 |
|------|------|--------|------|
| `qim.user.events` | Q-IM → IdO | 3 | 회원 이벤트 5종 (QIM-OUTBOX-SPEC-001) |
| `qsign.auth.events` | Q-Sign → IdO | 3 | 인증 완료 결과 |
| `ido.handoff.events` | IdO → Agency-Stub | 3 | Handoff 이벤트 |
| `ido.audit.events` | IdO → 감사 DB | 1 | 감사 로그 (순서 보장) |
| `*.DLQ` | 각 토픽 | 1 | Dead Letter Queue (최대 5개) |

### 핵심 설계 원칙

#### 1. Compacted Topic (`qim.user.events`)
```java
// KafkaTopicConfig.java
@Bean
public NewTopic qimUserEventsTopic() {
    return TopicBuilder.name("qim.user.events")
        .partitions(3)
        .replicas(1)
        .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
        .build();
}
```
- 회원 ID를 키로 최신 상태만 보존 → 재처리 시 최신 이벤트 기준

#### 2. Consumer Group 분리
```java
// KafkaConsumerConfig.java (IdO)
// QIM 이벤트용 — qimMemberListenerContainerFactory
// QSign 이벤트용 — qsignAuthListenerContainerFactory
```

#### 3. Dead Letter Queue (DLQ)
- 최대 3회 재시도 후 `*.DLQ` 토픽으로 이동
- 운영팀 수동 재처리 또는 Dead Letter 분석 가능

#### 4. 멱등성 소비 (Idempotent Consumer)
```java
// IdempotentEventStore.java (ido, q-sign 공통)
// processed_event 테이블에 event_id 기록 → 중복 소비 방지
```

### 이벤트 타입 (QIM-OUTBOX-SPEC-001)

```
qim.user.events 토픽 이벤트 5종:
  PERSONAL_MEMBER_REGISTERED  — 개인 신규 가입
  PERSONAL_MEMBER_CONVERTED   — 개인 전환 (기존 기관 계정 → OnePass)
  BIZ_MEMBER_REGISTERED       — 기업 신규 가입
  BIZ_MEMBER_CONVERTED        — 기업 전환
  MEMBER_WITHDRAWN            — 회원 탈퇴
```

---

## 결과 (Consequences)

### 긍정적 효과
- **서비스 분리**: Q-IM 장애 시에도 Kafka에 이벤트 보관 → IdO가 복구 후 소비
- **at-least-once 보장**: Outbox + Kafka 조합으로 이벤트 유실 없음 (ADR-008)
- **비동기 프로비저닝**: 가입 완료 즉시 응답 → 68개 기관 발송은 백그라운드
- **감사 로그 분리**: 인증 응답 속도에 영향 없음

### 부정적 효과 / 트레이드오프
- **운영 복잡도**: Kafka 클러스터 관리 필요 (ZooKeeper → KRaft 전환 권장)
- **로컬 개발**: Kafka 기동 필요 → docker-compose 제공으로 완화
- **메시지 순서**: 파티션 내 순서 보장, 파티션 간 순서 미보장 (회원 ID 기준 파티셔닝으로 완화)
- **중복 소비**: at-least-once 특성 → 멱등성 처리 필수 (IdempotentEventStore)

### 포기한 대안
- **RabbitMQ**: 메시지 보존·리플레이 기능 약함, Compacted Topic 미지원
- **REST 폴링**: 지연 시간 증가, 68개 기관 폴링 오버헤드
- **gRPC Streaming**: 양방향 스트림 복잡도, 팀 역량 부족

---

## 관련 ADR

- [ADR-008](ADR-008-transactional-outbox-pattern.md) — Transactional Outbox
- [ADR-009](ADR-009-qim-outbox-spec-001.md) — QIM-OUTBOX-SPEC-001 이벤트 타입
