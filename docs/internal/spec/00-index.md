# OnePass 통합인증 플랫폼 — 기술 문서 인덱스

> **기준 버전**: v1.9.3  
> **기준 커밋**: `46b1fe9` (`genspark_ai_developer` 브랜치)  
> **최종 갱신**: 2026-05-09  
> **문서 디렉토리**: `docs/spec/` ← 코드베이스 정밀 분석 기반 재작성

---

## 문서 구성

| 번호 | 파일 | 핵심 내용 | 대상 독자 |
|------|------|-----------|-----------|
| **00** | [00-index.md](00-index.md) | 이 파일 — 전체 조감도 | 전체 |
| **01** | [01-system-overview.md](01-system-overview.md) | 프로젝트 목적·범위·기술 스택·모듈 책임 | 신규 진입자 |
| **02** | [02-architecture.md](02-architecture.md) | 시스템 아키텍처·인증 흐름·EDA·ADR | 아키텍트·시니어 |
| **03-A** | [03a-module-platform-common.md](03a-module-platform-common.md) | 공통 도메인·이벤트·에러코드 | 전 모듈 개발자 |
| **03-B** | [03b-module-qsign.md](03b-module-qsign.md) | Q-Sign 인증 SoR — OIDC, PKCE, Outbox | Q-Sign 담당 |
| **03-C** | [03c-module-qim.md](03c-module-qim.md) | Q-IM 식별 SoR — 회원원장, CI암호화, Snapshot | Q-IM 담당 |
| **03-C-Charter** | [03c-qim-responsibility-charter.md](03c-qim-responsibility-charter.md) | Q-IM 책임 헌장 — DO / DO NOT, 인접 모듈과의 계약 (정본) | 전 모듈 오너 |
| **03-D** | [03d-module-ido.md](03d-module-ido.md) | IdO 정책 오케스트레이터 — 전 기능 상세 | IdO 담당 |
| **03-E** | [03e-module-agency-stub.md](03e-module-agency-stub.md) | agency-stub PoC 시뮬레이터 | 연동 개발자 |
| **03-F** | [03f-module-idem-console.md](03f-module-idem-console.md) | idem-console 데이터 흐름 정본 — 진입/상태/송신 4계층, CI 처리 경로 | 프론트·연동·보안 검토자 |
| **04** | [04-api-reference.md](../../_archive/2026-05-22/internal/spec/04-api-reference.md) | 전체 REST API 명세 (모듈별) | 프론트·연동팀 |
| **05** | [05-database-schema.md](05-database-schema.md) | DB 스키마 전체 (Flyway V1~최신) | DBA·백엔드 |
| **06** | [06-kafka-event-catalog.md](06-kafka-event-catalog.md) | Kafka 토픽·이벤트 구조·컨슈머 그룹 | 백엔드·인프라 |
| **07** | [07-security.md](07-security.md) | 보안 구현 상세 — 암호화·인증·Rate Limit | 보안 검토자 |
| **08** | [08-infrastructure.md](08-infrastructure.md) | Docker Compose·환경변수·로컬 구동 가이드 | DevOps·개발자 |
| **09** | [09-gap-and-roadmap.md](09-gap-and-roadmap.md) | 미구현 현황·Sprint 계획·기술 부채 | PM·리드 개발자 |

---

## 빠른 참조 카드

### 서비스 포트 맵

| 서비스 | 포트 | 역할 |
|--------|------|------|
| `q-sign` | **8081** | 인증 SoR (Keycloak OIDC 브로커) |
| `q-im` | **8082** | 식별 SoR (회원 원장, CI 관리) |
| `ido` | **8083** | 정책 오케스트레이터 + FE BFF |
| `agency-stub` | **8084** | 유관기관 PoC 시뮬레이터 |
| `idem-console` | **3000** (dev) / **3001** (nginx) | React SPA |
| PostgreSQL | **5432** | q-sign · ido 스키마 |
| MariaDB | **3306** | q-im 전용 |
| Redis | **6379** | FE 세션 · 캐시 |
| Kafka | **9092** | 내부 이벤트 버스 |
| Keycloak | **8085** (호스트→8081) | OIDC IdP |

### Kafka 토픽 전체 목록

| 토픽 | 생산자 | 주요 소비자 |
|------|--------|------------|
| `qsign.auth.events` | Q-Sign Outbox | IdO `QsignAuthEventConsumer` |
| `qsign.auth.events.dlq` | IdO 에러핸들러 | 운영 모니터링 |
| `ido.handoff.events` | IdO Outbox | IdO `HandoffEventConsumer` → Webhook |
| `ido.handoff.events.dlq` | IdO 에러핸들러 | 운영 모니터링 |
| `platform.session.advisory` | IdO `SessionAdvisoryPublisher` | IdO `FeAdvisoryConsumer` |
| `platform.session.advisory.dlq` | IdO 에러핸들러 | 운영 모니터링 |
| `platform.audit.log` | IdO `AuditLogPublisher` | 감사 시스템 |
| `qim.user.events` | Q-IM Outbox | IdO `QimEventConsumer`, Q-Sign `QimUserEventConsumer` |
| `qim.user.events.dlq` | Q-IM 에러핸들러 | 운영 모니터링 |
| `qim.user.snapshot` | Q-IM `SnapshotServiceImpl` | (Compacted — 구독자 확장 예정) |
| `qim.sp.member.events` | IdO `QimSpReceiverController` | IdO `QimSpMemberEventConsumer` |
| `qim.sp.member.events.dlt` | IdO 에러핸들러 | 운영 모니터링 |

### DB 스키마 현황

| 모듈 | DB 종류 | 스키마 | 최신 마이그레이션 |
|------|---------|--------|-----------------|
| q-sign | PostgreSQL | `qsign` | **V5** (auth_method 컬럼) |
| ido | PostgreSQL | `ido` | **V10** (auth_result 확장 + provider_routing) |
| q-im | MariaDB | `qim` | **V3** (CI 암호화 + 상태 이력) |
| agency-stub | PostgreSQL | `agency_stub` | **V2** (webhook + api_key) |

### 핵심 환경변수 (운영 필수)

```bash
# IdO 암호화 / 서명 키
IDO_HANDOFF_AES_KEY=<base64-32bytes>
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>
IDO_INTERNAL_SIG_SECRET=<32bytes+>
IDO_AGENCY_SUBJECT_SECRET=<32bytes+>

# Q-IM CI 암호화
QIM_AES_SHARED_KEY=<base64-32bytes>
QIM_CI_AES_KEY_V1=<base64-32bytes>

# Q-Sign Keycloak
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak Admin에서 발급>

# 공통 인프라
POSTGRES_PASSWORD=<운영용 강력한 비밀번호>
MARIADB_PASSWORD=<운영용 강력한 비밀번호>
KAFKA_SERVERS=<kafka:9092>
REDIS_HOST=<redis>
```

### 현재 구현 완성도 (v1.9.3)

```
platform-common  ████████████████████ 100%  (16 Java files)
Q-Sign           ████████████████████  95%  (35 Java files)
Q-IM             ██████████████████░░  92%  (41 Java files)
IdO              ████████████████████  99%  (106 Java files)
agency-stub      ████████████████████  90%  (15 Java files)
idem-console       ████████████░░░░░░░░  60%  (React SPA)
인프라/Docker    ████████████████████ 100%  (Compose 완비)
보안             ██████████████████░░  93%  (X-Internal-Sig 수신 미완)
테스트           ░░░░░░░░░░░░░░░░░░░░   0%  (단위·통합 미작성)
```

---

## 관련 문서 (구 docs/development/ 계속 유효)

| 문서 | 설명 |
|------|------|
| [`docs/development/12-implementation-gaps.md`](../development/12-implementation-gaps.md) | 미구현 항목 우선순위별 상세 |
| [`docs/development/13-development-history.md`](../development/13-development-history.md) | v1.0.0 → v1.9.3 버전별 변경 이력 |
| [`docs/local-dev-guide.md`](../local-dev-guide.md) | 로컬 개발 환경 구동 가이드 |
| [`docs/handoff-note.md`](../handoff-note.md) | v1.9.0 인수인계 패키지 |

---

*다음 문서: [01-system-overview.md](01-system-overview.md)*
