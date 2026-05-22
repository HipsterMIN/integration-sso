# OnePass Integration-SSO — 개발 진행 상태 문서

> **버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **브랜치**: `genspark_ai_developer` (커밋: `3f243fa`)

---

## 문서 목록

| 번호 | 파일 | 내용 |
|------|------|------|
| 01 | [01-project-overview.md](01-project-overview.md) | 프로젝트 목적, 범위, 기술 스택, 모듈별 역할, 현재 완성도 |
| 02 | [02-architecture.md](02-architecture.md) | 전체 시스템 아키텍처, 인증 흐름 시퀀스, ADR, 네트워크 구성, EDA |
| 03 | [03-module-ido.md](03-module-ido.md) | IdO 모듈 — 패키지 구조, Handoff, PolicyEngine, Broker, Rate Limit, Webhook |
| 04 | [04-module-qsign.md](04-module-qsign.md) | Q-Sign 모듈 — Keycloak OIDC, PKCE, AuthResult JPA, Flyway V1~V5 |
| 05 | [05-module-qim.md](05-module-qim.md) | Q-IM 모듈 — 회원 SoR, CI 암호화, PII 마스킹, DI 생성, Flyway V1~V3 |
| 06 | [06-module-agency-stub.md](06-module-agency-stub.md) | agency-stub — PoC 시뮬레이터, Webhook 수신, Verify API |
| 07 | [07-module-frontend.md](07-module-frontend.md) | onepass-fe — React SPA, FeSession 관리, 미구현 UI |
| 08 | [08-database-schema.md](08-database-schema.md) | DB 스키마 전체 (qsign V1~V5, ido V1~V10, qim V1~V3) |
| 09 | [09-api-spec.md](09-api-spec.md) | REST API 명세 (IdO, Q-Sign, Q-IM, Webhook 이벤트) |
| 10 | [10-security.md](10-security.md) | 보안 구현 현황, 암호화 상세, 키 관리, Rate Limit, Circuit Breaker |
| 11 | [11-infrastructure.md](11-infrastructure.md) | Docker Compose, Kafka 토픽, Redis 캐시, 모니터링 스택 |
| 12 | [12-implementation-gaps.md](12-implementation-gaps.md) | 현재 미구현 항목, 우선순위별 후속 계획, Sprint 계획 |
| 13 | [13-development-history.md](13-development-history.md) | v1.0.0 → v1.9.0 버전별 변경 이력, PR 이력 |

---

## 빠른 참조

### 현재 구현 완성도 (v1.9.0)

```
platform-common  ████████████████████ 100%
Q-Sign           ██████████████████░░  92%
Q-IM             █████████████████░░░  85%
IdO              ████████████████████  97%
agency-stub      ██████████████████░░  90%
onepass-fe       ████████████░░░░░░░░  60%
인프라/Docker    ████████████████████ 100%
보안             ██████████████████░░  93%
테스트           ░░░░░░░░░░░░░░░░░░░░   0%
```

### 주요 마이그레이션 이력

| 스키마 | 현재 버전 | 최신 내용 |
|--------|---------|---------|
| ido | V10 | auth_result 4개 컬럼 추가 + provider_circuit_config 테이블 (v1.9.0) |
| qsign | V5 | auth_method 컬럼 추가 (v1.4.1) |
| qim | V3 | CI 암호화 키 버전 + 상태 이력 테이블 (v1.8.0) |

### 핵심 환경변수 (운영 필수)

```bash
IDO_HANDOFF_AES_KEY=<base64-32bytes>
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>
IDO_INTERNAL_SIG_SECRET=<32bytes+>
IDO_AGENCY_SUBJECT_SECRET=<32bytes+>
QIM_AES_SHARED_KEY=<base64-32bytes>
QIM_CI_AES_KEY_V1=<base64-32bytes>
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak 발급값>
```

---

## 관련 문서

| 문서 | 설명 |
|------|------|
| [docs/handoff-note.md](../handoff-note.md) | v1.9.0 최종 인수인계 패키지 |
| [docs/local-dev-guide.md](../local-dev-guide.md) | 로컬 개발 환경 가이드 (v1.2.0) |
| [docs/2026-05-08_unimplemented_analysis.md](../_archive/2026-05-22/internal/analysis/2026-05-08_unimplemented_analysis.md) | v1.7.0 기준 미구현 분석 보고서 |
| [docs/2026-05-08_production_development_plan.md](../_archive/2026-05-22/internal/development/2026-05-08_production_development_plan.md) | 실제 개발 전환 계획서 (Sprint 1~6) |
| [docs/gap-analysis-v0.8.3-vs-project.md](../_archive/2026-05-22/internal/analysis/gap-analysis-v0.8.3-vs-project.md) | EDA 마스터 아키텍처 v0.8.3 vs 코드 GAP 분석 |
| [docs/qim-ido-integration-architecture.md](../qim-ido-integration-architecture.md) | Q-IM ↔ IdO 연동 아키텍처 설계서 |
| [docs/qim-sp-receiver-api-spec.md](../qim-sp-receiver-api-spec.md) | Q-IM SP 수신 API 명세서 (v1.52 기준) |
| [docs/agency-external-arch-supplement.md](../agency-external-arch-supplement.md) | 유관기관 외부망 배치 설계 보완서 |
| [docs/operational-readiness-analysis-v2.md](../_archive/2026-05-22/internal/analysis/operational-readiness-analysis-v2.md) | 운영 준비 분석 v2.2 |
| [docs/member-conversion-implementation-plan.md](../member-conversion-implementation-plan.md) | 회원 전환 구현 플랜 v1.2.0 |
