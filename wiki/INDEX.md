# 통합인증 플랫폼 (OnePass) — Wiki 인덱스

> **버전**: v0.8.8 | **최종 갱신**: 2026-05-15 | **브랜치**: genspark_ai_developer

---

## 📁 문서 구조

```
wiki/
├── INDEX.md                        ← 이 파일 (전체 인덱스)
├── adr/                            ← Architecture Decision Records
│   ├── ADR-001-ido-microservice-architecture.md
│   ├── ADR-002-jdk21-virtual-threads.md
│   ├── ADR-003-spring-boot-3.md
│   ├── ADR-004-kafka-eda.md
│   ├── ADR-005-postgresql-primary-store.md
│   ├── ADR-006-redis-session-cache.md
│   ├── ADR-007-flyway-db-migration.md
│   ├── ADR-008-transactional-outbox-pattern.md
│   ├── ADR-009-qim-outbox-spec-001.md
│   ├── ADR-010-cast-token-cross-agency-sso.md
│   ├── ADR-011-hmac-sha256-gateway-auth.md
│   └── ADR-012-react-fe-dual-instance.md
├── design/                         ← 서비스별 상세 설계서
│   ├── 01-ido-service-design.md
│   ├── 02-qim-service-design.md
│   ├── 03-qsign-service-design.md
│   └── 04-agency-stub-design.md
├── walkthrough/                    ← 전체 흐름 워크스루
│   ├── 01-login-walkthrough.md
│   ├── 02-member-register-walkthrough.md
│   ├── 03-member-conversion-walkthrough.md
│   ├── 04-provisioning-walkthrough.md
│   └── 05-handoff-sso-walkthrough.md
└── deliverables/
    └── DELIVERABLES.md             ← 산출물 마스터 인덱스
```

---

## 🗂️ 빠른 참조

### Architecture Decision Records (ADR)

| ID | 제목 | 상태 | Sprint |
|----|------|------|--------|
| [ADR-001](adr/ADR-001-ido-microservice-architecture.md) | IdO 마이크로서비스 아키텍처 채택 | ✅ Accepted | Sprint 1 |
| [ADR-002](adr/ADR-002-jdk21-virtual-threads.md) | JDK 21 Virtual Threads 채택 | ✅ Accepted | Sprint 1 |
| [ADR-003](adr/ADR-003-spring-boot-3.md) | Spring Boot 3 / Spring Framework 6 채택 | ✅ Accepted | Sprint 1 |
| [ADR-004](adr/ADR-004-kafka-eda.md) | Apache Kafka EDA (이벤트 기반 아키텍처) 채택 | ✅ Accepted | Sprint 2 |
| [ADR-005](adr/ADR-005-postgresql-primary-store.md) | PostgreSQL 주 데이터 저장소 채택 | ✅ Accepted | Sprint 1 |
| [ADR-006](adr/ADR-006-redis-session-cache.md) | Redis 세션·캐시 레이어 채택 | ✅ Accepted | Sprint 2 |
| [ADR-007](adr/ADR-007-flyway-db-migration.md) | Flyway 스키마 마이그레이션 채택 | ✅ Accepted | Sprint 1 |
| [ADR-008](adr/ADR-008-transactional-outbox-pattern.md) | Transactional Outbox 패턴 채택 | ✅ Accepted | Sprint 5 |
| [ADR-009](adr/ADR-009-qim-outbox-spec-001.md) | QIM-OUTBOX-SPEC-001 이벤트 타입 정합화 | ✅ Accepted | Sprint 14 |
| [ADR-010](adr/ADR-010-cast-token-cross-agency-sso.md) | CAST Token (Ed25519) Cross-Agency SSO | ✅ Accepted | Sprint 12 |
| [ADR-011](adr/ADR-011-hmac-sha256-gateway-auth.md) | HMAC-SHA256 Agency Gateway 인증 | ✅ Accepted | Sprint 13 |
| [ADR-012](adr/ADR-012-react-fe-dual-instance.md) | React FE 이중 인스턴스 (beInstance/extInstance) | ✅ Accepted | Sprint 15 |

### 서비스별 상세 설계서

| 모듈 | 역할 | 포트 |
|------|------|------|
| [IdO](design/01-ido-service-design.md) | 통합 인증 오케스트레이터 | 8083 |
| [Q-IM](design/02-qim-service-design.md) | 회원 정보 관리 (Identity Manager) | 8082 |
| [Q-Sign](design/03-qsign-service-design.md) | 인증 세션 관리 (Auth Session) | 8081 |
| [Agency-Stub](design/04-agency-stub-design.md) | 기관 시스템 PoC 스텁 | 8090 |

### 워크스루 (서비스 흐름)

| # | 시나리오 | 핵심 컴포넌트 |
|---|---------|-------------|
| [WK-01](walkthrough/01-login-walkthrough.md) | 로그인 (NICE·OACX·EzAuth·Keycloak) | Q-Sign → IdO → Q-IM |
| [WK-02](walkthrough/02-member-register-walkthrough.md) | 회원 신규 가입 + Outbox 발행 | Q-IM → Kafka → IdO |
| [WK-03](walkthrough/03-member-conversion-walkthrough.md) | 회원 전환 (기존 기관 계정 → OnePass) | Q-IM → ConversionSession |
| [WK-04](walkthrough/04-provisioning-walkthrough.md) | 전 기관 프로비저닝 (QIM-OUTBOX-SPEC-001) | IdO → provisioning_outbox → 68 기관 |
| [WK-05](walkthrough/05-handoff-sso-walkthrough.md) | Handoff SSO (CAST Token 기반 기관 이동) | IdO → Agency-Stub |

### 산출물 인덱스

- [DELIVERABLES.md](deliverables/DELIVERABLES.md) — 모든 산출물 목록 및 상태

---

## 🏗️ 시스템 개요

```
┌─────────────────────────────────────────────────────────────┐
│                      OnePass 통합인증 플랫폼                   │
│                                                             │
│  [FE: onepass-fe]                                           │
│    beInstance(8083) + extInstance(8082 via proxy)           │
│         │                                                   │
│         ▼                                                   │
│  [Q-Sign :8081] ──── [IdO :8083] ──── [Q-IM :8082]         │
│   Auth Session       Orchestrator     Identity Manager      │
│       │                   │                  │              │
│       │              [Kafka EDA]             │              │
│       │         qim.user.events / auth.*     │              │
│       │                   │                  │              │
│       └───────────────────┴──────────────────┘             │
│                           │                                 │
│              [Agency-Stub :8090] (PoC 기관 시스템)           │
│                     68개 실 기관 (HTTPS POST)               │
└─────────────────────────────────────────────────────────────┘

Infrastructure: PostgreSQL · Redis · Kafka · Keycloak
```

---

## 📌 용어 정의

| 용어 | 설명 |
|------|------|
| **IdO** | Identity Orchestrator — 통합인증 오케스트레이터 (포트 8083) |
| **Q-IM** | Q Identity Manager — 회원 정보 관리 서비스 (포트 8082) |
| **Q-Sign** | Q Signature — 인증 세션·토큰 관리 서비스 (포트 8081) |
| **CAST Token** | Cross-Agency SSO Token (Ed25519/EdDSA 서명) |
| **Provisioning** | 회원 이벤트 발생 시 68개 기관에 병렬 HTTPS 알림 |
| **Outbox Pattern** | DB 트랜잭션 + Kafka 발행 일관성 보장 패턴 |
| **QIM-OUTBOX-SPEC-001** | Q-IM → IdO 방향 이벤트 타입 명세 (5종 신규) |
| **Handoff** | 인증 완료 후 기관으로 세션 이전 (CAST Token 포함) |
| **HMAC-SHA256** | Agency Gateway 인증 방식 (상수 시간 비교) |
| **Virtual Thread** | JDK 21 경량 스레드 (68 기관 병렬 HTTP에 활용) |
