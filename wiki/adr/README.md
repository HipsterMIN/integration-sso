# ADR (Architecture Decision Records)

> **OnePass 통합인증 플랫폼** — 아키텍처 결정 기록 모음  
> 버전: v0.8.8 | 최종 갱신: 2026-05-15

기술 결정의 **배경(Context) → 결정(Decision) → 결과(Consequences)** 를 추적한다.  
모든 ADR은 `Accepted` 상태이며, 번호는 결정 시점 순서를 따른다.

---

## 목차

| # | ADR | 제목 | 카테고리 | 결정일 |
|:-:|-----|------|:--------:|--------|
| 1 | [ADR-001](#adr-001--ido-마이크로서비스-아키텍처) | IdO 마이크로서비스 아키텍처 채택 | 아키텍처 | 2025-Q4 |
| 2 | [ADR-002](#adr-002--jdk-21--virtual-threads) | JDK 21 LTS + Virtual Threads 채택 | 런타임 | 2025-Q4 |
| 3 | [ADR-003](#adr-003--spring-boot-32) | Spring Boot 3.2 / Spring Framework 6 채택 | 프레임워크 | 2025-Q4 |
| 4 | [ADR-004](#adr-004--apache-kafka-eda) | Apache Kafka 기반 EDA 채택 | 메시징 | 2025-Q4 |
| 5 | [ADR-005](#adr-005--postgresql-주-데이터-저장소) | PostgreSQL 주 데이터 저장소 채택 | 데이터베이스 | 2025-Q4 |
| 6 | [ADR-006](#adr-006--redis-세션--캐시--분산락) | Redis 세션·캐시·분산락 레이어 채택 | 인프라 | 2025-Q4 |
| 7 | [ADR-007](#adr-007--flyway-db-마이그레이션) | Flyway DB 스키마 버전 관리 채택 | 데이터베이스 | 2025-Q4 |
| 8 | [ADR-008](#adr-008--transactional-outbox-패턴) | Transactional Outbox 패턴 채택 | 패턴 | 2025 Sprint 5 |
| 9 | [ADR-009](#adr-009--qim-outbox-spec-001) | QIM-OUTBOX-SPEC-001 이벤트 타입 정합화 | 이벤트 | 2026 Sprint 14 |
| 10 | [ADR-010](#adr-010--cast-token-ed25519-cross-agency-sso) | Ed25519 CAST Token Cross-Agency SSO | 보안 | 2026 Sprint 12 |
| 11 | [ADR-011](#adr-011--hmac-sha256-gateway-인증) | HMAC-SHA256 Agency Gateway 인증 | 보안 | 2026 Sprint 13 |
| 12 | [ADR-012](#adr-012--react-fe-이중-axios-인스턴스) | React FE 이중 Axios 인스턴스 분리 | 프론트엔드 | 2026 Sprint 15 |

---

## ADR 상세

---

### ADR-001 · IdO 마이크로서비스 아키텍처

📄 [ADR-001-ido-microservice-architecture.md](./ADR-001-ido-microservice-architecture.md)

**결정 요약**  
모놀리식 단일 애플리케이션 대신 4개의 독립 마이크로서비스로 분리한다.

| 서비스 | 포트 | 역할 |
|--------|:----:|------|
| Q-Sign | 8081 | 인증 세션 관리, Keycloak OIDC, PKCE |
| Q-IM   | 8082 | 회원 정보 조회·등록·전환 |
| IdO    | 8083 | 인증 오케스트레이션, 프로비저닝, Handoff |
| Agency-Stub | 8090 | 기관 연동 PoC 시뮬레이터 |

**선택 이유**: 68개 기관 연동·다중 IdP(NICE, OACX, EzAuth, Keycloak) 중개 필요  
**트레이드오프**: 서비스 간 네트워크 복잡도 증가 ↔ 독립 배포·장애 격리 확보

---

### ADR-002 · JDK 21 + Virtual Threads

📄 [ADR-002-jdk21-virtual-threads.md](./ADR-002-jdk21-virtual-threads.md)

**결정 요약**  
JDK 21 LTS(Project Loom)의 Virtual Threads를 68개 기관 병렬 HTTP 처리에 채택한다.

```java
// ProvisioningServiceImpl.java
try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
    CompletableFuture.allOf(
        endpoints.stream()
            .map(ep -> CompletableFuture.runAsync(() -> dispatch(ep), vt))
            .toArray(CompletableFuture[]::new)
    ).join();
}
```

**선택 이유**: Platform Thread 대비 메모리 136배 절감 (68 스레드 기준 ~136MB → ~1MB)  
**관련 파일**: `idem-hub/provision/ProvisioningServiceImpl.java`, `idem-hub/build.gradle`

---

### ADR-003 · Spring Boot 3.2

📄 [ADR-003-spring-boot-3.md](./ADR-003-spring-boot-3.md)

**결정 요약**  
Spring Boot 3.2 / Spring Framework 6 을 채택하여 Jakarta EE 네임스페이스로 전환한다.

| 변경 사항 | 구(2.x) | 신(3.x) |
|-----------|---------|---------|
| 네임스페이스 | `javax.*` | `jakarta.*` |
| Virtual Thread | 미지원 | 네이티브 통합 |
| Security | 5.x | 6.x (`SecurityFilterChain` 람다) |
| Observability | 수동 | Micrometer Tracing 내장 |

**선택 이유**: JDK 21 Virtual Thread 네이티브 지원, LTS 보안 패치  
**관련 파일**: `*/build.gradle`, `*/application.yml`

---

### ADR-004 · Apache Kafka EDA

📄 [ADR-004-kafka-eda.md](./ADR-004-kafka-eda.md)

**결정 요약**  
마이크로서비스 간 비동기 이벤트 버스로 Apache Kafka를 채택한다.

**토픽 설계**:

| 토픽 | 발행자 | 소비자 | 용도 |
|------|--------|--------|------|
| `qim.user.events` | Q-IM | IdO | 회원 이벤트 (5종, QIM-OUTBOX-SPEC-001) |
| `qsign.auth.events` | Q-Sign | IdO | 인증 세션 이벤트 |
| `qim.user.events.DLQ` | Kafka | 운영팀 | Q-IM 이벤트 처리 실패 |
| `provisioning.DLQ` | IdO | 운영팀 | 프로비저닝 발행 실패 |
| _(+3 DLQ)_ | — | — | 서비스별 DLQ |

**선택 이유**: at-least-once 보장, Compacted Topic, Consumer Group 분리  
**관련 파일**: `idem-gate/config/KafkaTopicConfig.java`, `idem-hub/kafka/QimEventConsumer.java`

---

### ADR-005 · PostgreSQL 주 데이터 저장소

📄 [ADR-005-postgresql-primary-store.md](./ADR-005-postgresql-primary-store.md)

**결정 요약**  
모든 서비스의 주 데이터 저장소로 PostgreSQL을 채택하고, 서비스별 스키마를 격리한다.

| 스키마 | 소유 서비스 | 주요 테이블 |
|--------|-------------|-------------|
| `qsign` | Q-Sign | `auth_session`, `oidc_state` |
| `qim` | Q-IM | `members`, `outbox` |
| `ido` | IdO | `provisioning_outbox`, `gateway_inbound_audit` |

**핵심 채택 기능**: JSONB(페이로드 저장), CHECK 제약(이벤트 타입 검증), UUID v7(순서 보장)  
**Flyway 현황**: V1 ~ V18 (18단계)  
**관련 파일**: `*/src/main/resources/db/migration/`

---

### ADR-006 · Redis 세션 · 캐시 · 분산락

📄 [ADR-006-redis-session-cache.md](./ADR-006-redis-session-cache.md)

**결정 요약**  
Redis를 세션 저장소·캐시·분산 락의 단일 인메모리 레이어로 채택한다.

**용도별 TTL**:

| 용도 | 키 패턴 | TTL |
|------|---------|-----|
| FE 세션 | `fe:session:{id}` | 3,600s |
| NICE 인증 세션 | `nice:auth:{key}` | 180s |
| ciToken | `ci:token:{ref}` | 300s |
| CAST Token jti | `cast:jti:{jti}` | 15s |
| Rate Limit | `rl:auth:{ip}` | 60s |
| 분산 락 | `lock:member:convert:{id}` | 30s |

**NoOp 모드**: Redis 다운 시 분산락 없이 단일 인스턴스 동작 (개발 환경)  
**관련 파일**: `idem-hub/config/RedisConfig.java`, `idem-hub/auth/store/NiceAuthSessionStore.java`

---

### ADR-007 · Flyway DB 마이그레이션

📄 [ADR-007-flyway-db-migration.md](./ADR-007-flyway-db-migration.md)

**결정 요약**  
Flyway를 DB 스키마 버전 관리 도구로 채택하고, 서비스별 독립 마이그레이션 경로를 유지한다.

**V10 충돌 교훈** (PR #106):  
서로 다른 브랜치에서 동시에 `V10__*.sql`을 생성 → 병합 시 Flyway 체크섬 오류  
→ 해결: 충돌 파일을 `V17__*.sql`로 rename + **Flyway 번호 사전 협의 규칙** 도입

**번호 충돌 방지 규칙**:
```
PR 생성 전 Slack #db-migration 채널에서 다음 사용 번호 예약
예약 형식: "[V-Reserve] 서비스:IDO 번호:18 작업:이벤트타입_제약_갱신"
```

**관련 파일**: `*/src/main/resources/db/migration/V*.sql`

---

### ADR-008 · Transactional Outbox 패턴

📄 [ADR-008-transactional-outbox-pattern.md](./ADR-008-transactional-outbox-pattern.md)

**결정 요약**  
DB 트랜잭션과 Kafka 발행의 원자성을 보장하기 위해 Transactional Outbox 패턴을 채택한다.

**이중 Outbox 구조**:
```
Q-IM ─── qim.outbox ──────────────► Kafka: qim.user.events
                                              │
IdO  ─── ido.outbox ──────────────► Kafka: qsign.auth.events 등
     ─── provisioning_outbox ─────► 기관 HTTP × 68
     ─── webhook_outbox ───────────► Webhook 발송
```

**5개 Relay 클래스**: `IdoOutboxRelay`, `QimOutboxRelay`, `ProvisioningOutboxRelay`,  
`WebhookDispatchOutboxRelay`, `QimSpOutboxRelay`

**재시도**: 지수 백오프 `MIN(2^n × 5s, 300s)`, 최대 5회 후 DLQ  
**Thundering Herd 방지** (V17): `FOR UPDATE SKIP LOCKED`  
**관련 파일**: `idem-hub/provision/ProvisioningOutboxRelay.java`, `idem-registry/outbox/OutboxServiceImpl.java`

---

### ADR-009 · QIM-OUTBOX-SPEC-001

📄 [ADR-009-qim-outbox-spec-001.md](./ADR-009-qim-outbox-spec-001.md)

**결정 요약**  
`qim.user.events` 토픽의 이벤트 타입을 구 4종에서 신규 5종으로 정합화한다.

**이벤트 타입 2×2 매트릭스**:

| | `isCorporate=false` | `isCorporate=true` |
|---|---|---|
| **`isTransfer=false`** | `PERSONAL_MEMBER_REGISTERED` | `BIZ_MEMBER_REGISTERED` |
| **`isTransfer=true`** | `PERSONAL_MEMBER_CONVERTED` | `BIZ_MEMBER_CONVERTED` |

**+** `MEMBER_WITHDRAWN` (탈퇴, 독립 이벤트)

**구 타입 → Deprecated** (V18 하위 호환 유지):  
`USER_REGISTERED`, `BIZ_CONVERTED`, `USER_UPDATED`, `USER_WITHDRAWN`

**V18 마이그레이션**: `chk_prov_event_type` + `chk_gateway_inbound_event_type` CHECK 제약 갱신  
**관련 파일**: `ProvisioningEventType.java`, `QimEventConsumer.java`, `V18__update_event_type_constraints.sql`

---

### ADR-010 · CAST Token Ed25519 Cross-Agency SSO

📄 [ADR-010-cast-token-cross-agency-sso.md](./ADR-010-cast-token-cross-agency-sso.md)

**결정 요약**  
기관 간 재인증 없는 SSO를 위해 Ed25519 서명 기반 CAST Token을 채택한다.

**CAST Token 핵심 속성**:

| 속성 | 값 | 목적 |
|------|-----|------|
| 알고리즘 | EdDSA (Ed25519) | RSA 대비 32배 짧은 키 |
| TTL | **10초** | 최소 노출 시간 |
| 단발성 | jti + Redis SETNX | 리플레이 공격 방지 |
| CI 보호 | `ciRef` (SHA-256 해시) | CI 원문 토큰 미포함 |
| 키 식별 | `kid` 헤더 | 무중단 키 로테이션 |

**4가지 Handoff 전략**: `REDIRECT`, `POST_FORM`, `IFRAME`, `API_CALLBACK`  
**관련 파일**: `idem-hub/sso/CastTokenServiceImpl.java`, `idem-hub/sso/CrossAgencySsoController.java`

---

### ADR-011 · HMAC-SHA256 Gateway 인증

📄 [ADR-011-hmac-sha256-gateway-auth.md](./ADR-011-hmac-sha256-gateway-auth.md)

**결정 요약**  
기관 시스템 → IdO Gateway 요청의 무결성·출처 검증에 HMAC-SHA256을 채택한다.

**서명 방식**:
```
message   = timestamp + "." + nonce + "." + SHA-256(body)
signature = HMAC-SHA256(agencySecretKey, message)
헤더      = X-HMAC-Timestamp, X-HMAC-Nonce, X-HMAC-Signature
```

**타이밍 공격 방어**:
```java
// HmacSignatureFilter.java
boolean valid = MessageDigest.isEqual(expected, received);  // 상수 시간 비교
```

**Nonce 저장**: Redis TTL 300s (리플레이 방지)  
**관련 파일**: `idem-hub/gateway/HmacSignatureFilter.java`, `idem-hub/gateway/AgencyHmacKeyStore.java`

---

### ADR-012 · React FE 이중 Axios 인스턴스

📄 [ADR-012-react-fe-dual-instance.md](./ADR-012-react-fe-dual-instance.md)

**결정 요약**  
FE Axios 인스턴스를 `beInstance`(내부 API)와 `extInstance`(외부 CI API)로 분리한다.

**분리 이유 (CI Q3=B 보안 패치)**:

| 인스턴스 | 대상 | 인증 방식 | API Key 노출 |
|----------|------|-----------|:---:|
| `beInstance` | IdO/Q-IM 내부 API | 세션 쿠키 | ✗ |
| `extInstance` | `/api/ext/ci/**` → 외부 CI | ExtProxyController에서 서버사이드 주입 | **✗** |

**ExtProxyController**: FE에서 API Key 제거, 서버에서 주입 후 외부 전달  
`/api/ext/ci/**` 직접 외부 호출 → **403 차단**

**관련 파일**: `idem-console/src/api/beInstance.ts`, `idem-console/src/api/extInstance.ts`,  
`idem-hub/ext/ExtProxyController.java`

---

## 카테고리별 인덱스

### 🏗 아키텍처
- [ADR-001](./ADR-001-ido-microservice-architecture.md) — 마이크로서비스 분리 (4서비스)

### ⚙️ 런타임 · 프레임워크
- [ADR-002](./ADR-002-jdk21-virtual-threads.md) — JDK 21 Virtual Threads
- [ADR-003](./ADR-003-spring-boot-3.md) — Spring Boot 3.2 / Jakarta EE

### 📨 메시징 · 이벤트
- [ADR-004](./ADR-004-kafka-eda.md) — Apache Kafka EDA
- [ADR-008](./ADR-008-transactional-outbox-pattern.md) — Transactional Outbox 패턴
- [ADR-009](./ADR-009-qim-outbox-spec-001.md) — QIM-OUTBOX-SPEC-001 이벤트 정합화

### 🗄 데이터 저장
- [ADR-005](./ADR-005-postgresql-primary-store.md) — PostgreSQL (주 저장소)
- [ADR-006](./ADR-006-redis-session-cache.md) — Redis (세션·캐시·분산락)
- [ADR-007](./ADR-007-flyway-db-migration.md) — Flyway (DB 버전 관리)

### 🔐 보안
- [ADR-010](./ADR-010-cast-token-cross-agency-sso.md) — CAST Token Ed25519 (Cross-Agency SSO)
- [ADR-011](./ADR-011-hmac-sha256-gateway-auth.md) — HMAC-SHA256 (Gateway 인증)
- [ADR-012](./ADR-012-react-fe-dual-instance.md) — FE 이중 Axios 인스턴스 (CI 보안 분리)

---

## ADR 작성 규칙

### 파일명 규칙
```
ADR-{NNN}-{kebab-case-title}.md
예: ADR-013-some-new-decision.md
```

### 번호 예약 규칙
PR 생성 전 Slack `#db-migration` 또는 `#architecture` 채널에서 번호 예약:
```
[ADR-Reserve] 번호:013 제목:some-new-decision 담당:@name
```

### 문서 구조
```markdown
# ADR-NNN: {제목}

| 항목 | 내용 |
|------|------|
| **ID** | ADR-NNN |
| **제목** | ... |
| **상태** | ✅ Accepted / 🔄 Proposed / ❌ Deprecated |
| **결정일** | YYYY-MM or Sprint N |
| **결정자** | ... |
| **관련 파일** | ... |

## 컨텍스트 (Context)
## 결정 (Decision)
## 결과 (Consequences)
## 대안 검토 (Alternatives Considered)
```

### 상태 값
| 상태 | 의미 |
|------|------|
| `✅ Accepted` | 채택 완료, 현재 적용 중 |
| `🔄 Proposed` | 제안됨, 검토 중 |
| `⚠️ Deprecated` | 폐기됨, 더 이상 사용 안 함 |
| `🔁 Superseded` | 다른 ADR로 대체됨 |

---

## 관련 문서

| 문서 | 경로 |
|------|------|
| 위키 전체 인덱스 | [`../INDEX.md`](../INDEX.md) |
| 서비스 설계서 | [`../design/`](../design/) |
| 워크스루 | [`../walkthrough/`](../walkthrough/) |
| 산출물 마스터 인덱스 | [`../deliverables/DELIVERABLES.md`](../deliverables/DELIVERABLES.md) |
