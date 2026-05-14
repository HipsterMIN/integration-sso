# OnePass 플랫폼: Kafka 완전 정복 가이드 (개발자용)

## 1. 들어가며: Kafka, 왜 쓸까요? (어렵지 않습니다!)

개발팀에서 Kafka를 어렵고 두렵게 느끼는 이유는, Kafka가 제공하는 방대한 기능(파티션, 리밸런싱, 스트림즈 등)을 모두 알아야 한다고 생각하기 때문입니다.

하지만 우리 프로젝트에서 Kafka는 아주 단순한 역할, 즉 **"사내 게시판"**의 역할을 합니다.

- **Q-Sign 팀**: "인증 완료!"라고 게시판에 글을 올립니다. (Producer)
- **Q-IM 팀**: "회원 가입/탈퇴!"라고 게시판에 글을 올립니다. (Producer)
- **IdO 팀**: 게시판에 새로 올라온 글들을 읽고 후속 처리를 수행합니다. **동시에, 자신의 처리 결과를 새 글로 게시판에 올리기도 합니다.** (Consumer이자 Producer)

이렇게 하면 각 팀(서비스)은 서로에게 직접 전화(HTTP API 직접 호출)할 필요 없이, 게시판에 글만 남기면 되므로 **한 팀의 서버가 잠시 멈춰도 다른 팀의 업무에 전혀 영향을 주지 않습니다.** 이것이 우리가 Kafka를 사용하는 유일하고 가장 중요한 이유, 즉 **"느슨한 결합(Loose Coupling)"**입니다.

---

## 2. 핵심 원리: "Transactional Outbox 패턴" — 절대 실패하지 않는 안전한 우편 발송법

> **"개발자는 Kafka에 직접 이벤트를 보내지 않습니다. 그냥 '보낼 편지함(Outbox)'에 넣어두기만 하면 됩니다."**

이것이 우리 시스템의 핵심 원칙입니다. Kafka에 직접 메시지를 보내려다 실패하면 데이터가 누락되는 '배달 사고'가 발생할 수 있습니다. 우리는 이 배달 사고를 원천 차단하기 위해 **Transactional Outbox 패턴**을 사용합니다.

1.  **[DB 작업]** 당신이 처리해야 할 중요한 업무 서류(`qim_user` 테이블의 회원 정보 수정)가 있습니다.
2.  **[Outbox 작업]** 이 서류 작업을 완료했다는 사실을 다른 팀에 알리기 위해, 알림 메모(`outbox` 테이블에 이벤트 내용 저장)를 작성합니다.
3.  **[원자적(Atomic) 처리]** 당신은 **업무 서류와 알림 메모를 하나의 서류 봉투에 함께 넣고 봉인**합니다. 이것이 바로 **`@Transactional`** 입니다.
4.  **[Relay 역할]** 이제 당신의 일은 끝났습니다. **"Relay"** 라는 이름의 우편 담당 직원이 주기적으로 당신의 '보낼 편지함(Outbox)'을 확인하고, 봉인된 서류 봉투에서 알림 메모를 꺼내 사내 게시판(Kafka)에 대신 붙여줍니다.

**결론: 개발자는 1, 2, 3번 — `@Transactional` 안에서 비즈니스 로직과 Outbox 저장까지만 책임지면 됩니다. 4번(Kafka로의 실제 전송)은 시스템이 알아서, 그리고 안전하게 처리합니다.**

### 💻 실제 코드 예시 (Q-IM 팀 기준)

**잘못된 예시 (Kafka에 직접 전송 시도 — 절대 금지!)**
```java
@Transactional
public void registerUser(UserDto dto) {
    // 1. DB에 사용자 저장
    userRepository.save(new User(dto));
    
    // 2. Kafka에 직접 전송 (위험!)
    // 만약 여기서 네트워크 오류로 실패하면? DB에는 저장됐는데 이벤트는 안 날아갑니다.
    kafkaTemplate.send("qim.user.events", dto.getId(), "UserRegistered"); 
}
```

**올바른 예시 (Outbox 패턴 사용)**
```java
@Transactional // ★ 핵심: DB 저장과 Outbox 저장을 하나의 트랜잭션으로 묶음
public void registerUser(UserDto dto) {
    // 1. DB에 사용자 저장
    userRepository.save(new User(dto));
    
    // 2. Outbox에 이벤트 저장
    // 네트워크 오류가 나도 안전합니다. 스케줄러가 나중에 알아서 보내줍니다.
    UserEvent event = new UserEvent("USER_REGISTERED", dto.getId());
    outboxService.publishInTx(event); 
}
```

---

## 3. 우리 프로젝트의 Kafka 운영 방식 (팀별 역할)

### 3.1. `q-sign` (인증 담당)

- **역할**: **Producer (생산자)**
- **언제?**: 사용자가 ID/PW나 소셜 로그인을 통해 인증 성공/실패/잠금 이벤트가 발생했을 때.
- **무엇을?**: `AUTH_COMPLETED`, `AUTH_FAILED`, `AUTH_LOCKED` 이벤트를 Outbox를 통해 발행합니다.
- **어디로?**: `qsign.auth.events` 토픽으로.
- **누가 듣나?**: `IdO`가 이 이벤트들을 수신하여 각 타입에 맞는 후속 처리를 시작합니다.

> **q-sign 개발팀이 할 일**: **거의 없습니다.** 이미 `KeycloakCallbackService` 내에 `@Transactional`과 Outbox 저장 로직이 구현되어 있습니다. 새로운 인증 수단을 추가할 때 이 패턴을 그대로 유지하기만 하면 됩니다.

> **⚠️ 특이 사항 — Keycloak Strategy B**: Keycloak OIDC 콜백은 Q-Sign이 아닌 **IdO(`KeycloakOidcService`)가 직접 수신**하여 `qsign.auth.events` 토픽에 발행합니다. 이 경우 IdO가 이 토픽의 Producer 역할도 겸합니다.

---

### 3.2. `q-im` (회원정보 담당)

- **역할**: **Producer (생산자)**
- **언제?**: **회원 가입, 기업 전환, 상태 변경(정지/정상화), 탈퇴** 등 사용자 신상에 변화가 생겼을 때.
- **무엇을?**: `USER_REGISTERED`, `BIZ_CONVERTED`, `USER_STATUS_CHANGED` 등의 이벤트를 Outbox를 통해 발행합니다.
- **어디로?**: `qim.user.events` 토픽으로.
- **누가 듣나?**: `IdO`와 `q-sign`이 이 이벤트를 수신합니다.

> **q-im 개발팀이 할 일**: `@Transactional` 어노테이션이 붙은 서비스 메소드 안에서, 비즈니스 로직(DB 저장)과 함께 `outboxService.publishInTx()`를 호출하는 것. 이것이 전부입니다.

---

### 3.3. `ido` (중재자 및 오케스트레이터)

IdO는 단순한 Consumer가 아닙니다. **여러 토픽을 동시에 소비(Consumer)하면서, 자신의 처리 결과를 새 토픽에 발행(Producer)하는 오케스트레이터**입니다.

#### Consumer 역할 — 읽는 토픽 4개

| 구독 토픽 | 담당 Consumer 클래스 | 처리 내용 |
|---------|---------------------|---------|
| `qsign.auth.events` | `QsignAuthEventConsumer` | **AUTH_COMPLETED**: 인증 결과를 Redis에 Pre-warming (Handoff 즉시 처리 준비)<br>**AUTH_FAILED**: 실패 감사 로그 기록<br>**AUTH_LOCKED**: 세션 강제 종료 Advisory 발행 |
| `qim.user.events` | `QimEventConsumer` | 사용자 캐시 무효화 + Selective Pull(최신 정보 재조회)<br>**USER_REGISTERED / BIZ_CONVERTED**: 68개 기관에 프로비저닝 트리거 |
| `ido.handoff.events` | `HandoffEventConsumer` | **HANDOFF_ISSUED**: 기관 Webhook 발송 Outbox에 적재<br>**HANDOFF_CONSUMED**: 인증 결과 캐시 무효화<br>**HANDOFF_REVOKED**: 기관에 보안 취소 즉시 통보 |
| `platform.session.advisory` | `FeAdvisoryConsumer` | **MANDATORY**: FE 세션 즉시 일괄 무효화<br>**ADVISORY**: 다음 요청 시 로그아웃 안내 플래그 설정 |
| `qim.sp.member.events` | `QimSpMemberEventConsumer` | SP 수신 회원 가입/탈퇴 처리 → 기관 Webhook 트리거 |

#### Producer 역할 — 직접 발행하는 토픽 3개

| 발행 토픽 | 발행 클래스 | 언제 발행하나 |
|---------|------------|-------------|
| `ido.handoff.events` | `HandoffServiceImpl` | Handoff 티켓 발급/소비/만료/취소 시 |
| `platform.session.advisory` | `SessionAdvisoryPublisher` | AUTH_LOCKED 수신 → 세션 강제 종료 명령 |
| `platform.audit.log` | `AuditLogPublisher` | 모든 주요 이벤트의 감사 로그 기록 |
| `qsign.auth.events` | `KeycloakOidcService` | *(Keycloak 전용)* OIDC 콜백 수신 후 인증 이벤트 발행 |

---

## 4. IdO의 핵심 이벤트 처리 흐름 상세 설명

### 4.1 AUTH_COMPLETED 수신 — "Redis Pre-warming, Handoff 티켓을 즉시 발급하는 게 아닙니다"

> ⚠️ 자주 오해하는 부분: "AUTH_COMPLETED 이벤트를 받으면 바로 Handoff 티켓을 발급한다"는 것은 **틀립니다.**

실제 동작:

```
[Q-Sign] AUTH_COMPLETED 발행
    └─► [QsignAuthEventConsumer] 수신
            ├─ AuthResultCacheService.preWarm(event)
            │     → Redis에 인증 결과 미리 저장 (TTL 300초)
            └─ AuditLogPublisher.publish("AUTH_COMPLETED_RECEIVED")
                   → 감사 로그 비동기 기록
```

Handoff 티켓 발급은 **별도의 HTTP API 호출**로 이루어집니다:

```
[FE 브라우저]
    → HTTP POST /api/v1/handoff/issue (사용자가 기관 이동 버튼 클릭 시)
    → [HandoffServiceImpl.issue()]
            ├─ Redis Pre-warming 캐시 확인 → 인증 결과 즉시 반환 (DB 조회 없음)
            ├─ 정책 검증 (기관 활성 여부, 인증 수준, 사용자 상태 등)
            ├─ Handoff 티켓 생성 + DB 저장
            └─ ido.handoff.events 발행 (HANDOFF_ISSUED)
```

**Pre-warming의 목적**: 동시 6만 명 인증 완료 시 Handoff 요청이 몰려도 DB 조회 없이 Redis에서 즉시 응답. DB 부하 폭발 방지.

---

### 4.2 AUTH_LOCKED 수신 — "세션 강제 종료 이벤트 체인"

```
[Q-Sign] AUTH_LOCKED 발행 (비밀번호 5회 오류, 의심 IP 등)
    └─► [QsignAuthEventConsumer] 수신
            ├─ AuthResultCacheService.invalidate(correlationId)
            │     → 진행 중인 Handoff 캐시 즉시 무효화 (보안)
            └─ SessionAdvisoryPublisher.publishAuthLocked()
                   └─► Kafka: platform.session.advisory (MANDATORY_SECURITY_TERMINATE)
                           └─► [FeAdvisoryConsumer]
                                   └─ FeSessionService.invalidateByQimUserId()
                                          → FE 세션 즉시 일괄 무효화
```

---

### 4.3 USER_REGISTERED / BIZ_CONVERTED 수신 — "68개 기관 프로비저닝"

Q-IM에서 신규 회원 가입 또는 기업 전환 이벤트를 수신하면, IdO는 연동된 **68개 기관 모두에 회원 정보를 병렬로 전달**합니다.

```
[Q-IM] USER_REGISTERED 발행
    └─► [QimEventConsumer] 수신
            ├─ UserStatusCache.invalidate(qimUserId) → 캐시 무효화
            ├─ QimClient.getUserById() → 최신 정보 조회 (Selective Pull)
            └─ ProvisioningService.triggerProvisioning()
                   └─► Virtual Thread 68개 병렬 실행
                           → 각 기관 서버에 HTTP POST
                           → 실패 기관은 provisioning_outbox PENDING 저장
                                   → ProvisioningOutboxRelay 재시도
```

> **F-22 Dry-Run 모드**: `IDO_PROVISIONING_DRY_RUN=true`이면 실제 HTTP 발송 없이 로그만 출력. Phase 2-A 관찰 기간에 사용.

---

### 4.4 HANDOFF_ISSUED → 기관 Webhook 체인

기관들은 내부망 Kafka에 직접 접속할 수 없습니다. 따라서 IdO가 **HTTPS Webhook**으로 기관에 알립니다.

```
[HandoffServiceImpl] ido.handoff.events 발행 (HANDOFF_ISSUED)
    └─► [HandoffEventConsumer] 수신 (같은 IdO 서비스 내부)
            └─ WebhookDispatcherService.enqueueForHandoffEvent()
                   └─► webhook_dispatch_outbox INSERT (PENDING)
                           └─► [WebhookDispatchOutboxRelay] 30초마다 실행
                                   → HTTPS POST 기관 Webhook URL
                                   → 실패 시 지수 백오프 재시도
```

---

## 5. 개발팀이 정말 '겁먹지 않아도 되는' 이유

- **Kafka Connect / Debezium**: **우리 프로젝트는 사용하지 않습니다.** 클라우드 Managed DB 환경의 제약으로 인해, 우리는 애플리케이션이 직접 폴링하는 더 단순하고 유연한 방식을 채택했습니다.
- **Kafka 서버 장애**: **Outbox 패턴이 완벽하게 막아줍니다.** Kafka가 다운되어도 이벤트는 각 서비스의 `outbox` 테이블에 `PENDING` 상태로 남습니다. 복구되면 Relay가 자동으로 재발행합니다.
- **메시지 순서 보장**: 동일 사용자에 대한 이벤트는 `qimUserId`를 파티션 키로 사용하여, **항상 동일한 파티션에 순서대로 쌓입니다.** `QimEventConsumer`는 추가로 `eventVersion` 기반 순서 검증도 수행합니다.
- **메시지 중복 처리**: 모든 Consumer(`QimEventConsumer`, `QsignAuthEventConsumer`, `HandoffEventConsumer` 등)는 `IdempotentEventStore`를 통해 **이벤트 ID 기준으로 중복 처리를 방어**합니다.
- **DLQ(Dead Letter Queue)**: 재시도 임계치 초과 시 각 토픽의 `.dlq` 토픽으로 이동. 운영팀이 수동으로 검토 후 재처리 가능.

---

## 6. 토픽 전체 목록 및 파티션 설계

| 토픽 | 파티션 수 | Retention | Producer | Consumer | 파티션 키 |
|------|---------|----------|---------|---------|---------|
| `qsign.auth.events` | 12 | 1시간 | Q-Sign Outbox Relay<br>*IdO (Keycloak 전용)* | IdO QsignAuthEventConsumer | identifierHash |
| `qim.user.events` | 12 | 설정값 | Q-IM Outbox Relay | IdO QimEventConsumer<br>Q-Sign QimUserEventConsumer | qimUserId |
| `ido.handoff.events` | 12 | 1년 | IdO HandoffServiceImpl | IdO HandoffEventConsumer | correlationId |
| `platform.session.advisory` | 12 | 24시간 | IdO SessionAdvisoryPublisher | IdO FeAdvisoryConsumer | qimUserId |
| `platform.audit.log` | 12 | 2년 | IdO AuditLogPublisher | SIEM 외부 시스템 | agencyCode |
| `qim.sp.member.events` | 6 | 30일 | IdO QimSpReceiverService Outbox | IdO QimSpMemberEventConsumer | instMbrId |

> **60,000명 동시 대응**: 핵심 토픽 12파티션 × concurrency 6 = 초당 최대 1,200건 처리. 최악 시나리오(1분 내 6만 건 = 1,000건/초)에도 대응 가능. 확장 필요 시 파티션 24 + concurrency 12로 무중단 스케일아웃.

---

## 7. 용어집 (Glossary)

- **Kafka**: 대용량 메시지를 실시간으로 처리하기 위한 분산 메시징 시스템. 우리 프로젝트에서는 "사내 게시판" 역할.
- **Producer (생산자)**: Kafka 토픽에 메시지를 게시하는 주체. (`q-sign`, `q-im`, **`ido` 일부 역할**)
- **Consumer (소비자)**: Kafka 토픽을 구독하여 새로운 메시지를 읽어가는 주체. (**`ido`가 주요 Consumer**)
- **Topic (토픽)**: 메시지를 구분하기 위한 카테고리. (예: `qim.user.events`는 '회원 소식' 게시판)
- **Partition (파티션)**: 하나의 토픽을 여러 개로 나눈 것. 동일한 파티션 내에서는 메시지 순서가 보장됨.
- **Transactional Outbox Pattern**: DB 업데이트와 이벤트 발행을 하나의 트랜잭션으로 묶어 데이터 정합성을 보장하는 아키텍처 패턴.
- **Outbox Relay**: `outbox` 테이블을 주기적으로 읽어 Kafka에 메시지를 대신 전달해주는 스케줄러. IdO는 `IdoOutboxRelay`, Q-IM은 자체 Relay 보유.
- **Pre-warming**: 인증 완료 직후 Redis에 인증 결과를 미리 적재하여, 이후 Handoff 요청 시 DB 조회 없이 즉시 응답하는 기법. 6만 명 동시 처리의 핵심.
- **Idempotency (멱등성)**: 동일한 요청을 여러 번 수행해도 결과가 단 한 번 수행한 것과 같은 특성. `IdempotentEventStore`로 구현.
- **DLQ (Dead Letter Queue)**: 재시도 임계치를 초과한 메시지를 보관하는 별도 토픽. 운영팀 수동 처리 대상.
- **Selective Pull**: Consumer가 이벤트를 수신한 후 필요 시 Producer API를 호출하여 최신 전체 정보를 조회하는 패턴. 이벤트 페이로드를 최소화하면서 정합성 유지.
- **Webhook**: 기관 서버에 대한 HTTPS POST 알림. 기관들은 내부 Kafka에 직접 접속 불가하므로, IdO가 Webhook으로 변환하여 전달.
