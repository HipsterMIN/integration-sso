# integration-sso 문서 디렉토리 안내

> **최종 정리일**: 2026-05-22
> **변경 사항**: Sprint α-1/α-2/α-3 결과 반영, `analysis/sso-im-readiness/` 추가, 시점 산출물·구버전 23건을 `_archive/2026-05-22/`로 이동
> **상위 README**: 루트 [`README.md`](../README.md)

---

## 디렉토리 구조

```
docs/
├── README.md                       ← 이 파일 (전체 안내)
├── project-overview.md             ← ★프로젝트 소개 (처음 접하는 분용, docx 배포본과 동일 내용)
│
├── analysis/                       ← 운영 적합성 심층 분석 (★최신)
│   └── sso-im-readiness/           │   Phase 1-7 + Sprint α-1/α-2/α-3 결과
│
├── deployment/                     ← 운영 배포 가이드 (최신본)
│   └── README.md                   │   2026-05-21
│
├── features/                       ← 기능 단위 명세 (F-01~F-27)
│
├── internal/                       ← integration-sso 내부 개발 문서
│   ├── architecture/               │   설계·아키텍처·ADR
│   ├── dataflow/                   │   로그인/회원전환/Handoff 등 흐름도
│   ├── development/                │   개발 가이드·구현 계획·인수인계
│   └── spec/                       │   모듈별 기술 명세서
│
├── proposal/                       ← 경영진·외부 제출용 제안서
├── smep-handover/                  ← SMEP 팀 전달용 문서 패키지
│
└── _archive/                       ← 보관 (시점 산출물·구버전)
    └── 2026-05-22/                 │   2026-05-22 정리분 (23건)
```

---

## ★ 최신 작업 — `analysis/sso-im-readiness/`

옵션 1(점진 수정/안전) 로드맵의 현재 진행 상태입니다.

| 문서 | 내용 |
|---|---|
| `00_INDEX.md` | 전체 색인 + 변경 이력 |
| `01_architecture_recon.md` | 아키텍처 정찰 |
| `02_authentication_flow.md` | 인증 흐름 분석 |
| `03_identity_mapping.md` | 정체성 매핑 분석 |
| `04_handoff_flow.md` | Handoff 흐름 분석 |
| `05_privacy_kms_audit.md` | 개인정보·KMS 감사 |
| `06_e2e_scenarios.md` | E2E 시나리오 |
| `07_risk_matrix_roadmap.md` | 위험 매트릭스 + 로드맵 (α/β 진행 표기) |
| `08_sprint_alpha1_kms_safety.md` | Sprint α-1 (KMS 안전망 F5.1+F5.2) |
| `09_sprint_alpha2_handoff_integrity.md` | Sprint α-2 (Handoff 무결성 F4.1+F4.5+F4.2) |
| `10_sprint_alpha3_perimeter_hardening.md` | Sprint α-3 (경계 영역 F4.3+F4.4+F4.6) |

**Sprint 진행 상태**

| Sprint | 대상 결함 | 머지 PR | 상태 |
|---|---|---|---|
| α-1 | F5.1 + F5.2 (KMS 안전망) | #176 → #177 | ✅ main 머지 |
| α-2 | F4.1 + F4.5 + F4.2 (Handoff 무결성) | #177 (`aa18aa8`) | ✅ main 머지 |
| α-3 | F4.3 + F4.4 + F4.6 (경계 영역 보안 강화) | #178 (`946f235`) | ✅ main 머지 |
| 부수 | onepass-support 모듈 뼈대 | #179 (`3d2b8b8`) | ✅ main 머지 |

---

## 운영 / 배포

| 파일 | 내용 |
|---|---|
| [`deployment/README.md`](deployment/README.md) | **운영 배포 가이드 (최신, 2026-05-21)** — develop 브랜치 기준 |
| [`OPERATION_INVENTORY.md`](OPERATION_INVENTORY.md) | 운영 관리 포인트 인벤토리 — 신규 운영자 온보딩 |
| [`RUNBOOK_SSO_METRICS.md`](RUNBOOK_SSO_METRICS.md) | SSO/IM 본질 메트릭 RUNBOOK (PR-B1-new 산출물) |
| [`SPRINT_B_PLAN.md`](SPRINT_B_PLAN.md) | Sprint B 축소 계획 (최소 메트릭 + 최소 알람) |
| [`phased-rollout-strategy.md`](phased-rollout-strategy.md) | 단계적 배포 전략 (Phase-Gate Rollout) |

---

## SDK / 유관기관 연동

| 파일 | 내용 |
|---|---|
| [`onepass-agency-sdk-usage-guide.md`](onepass-agency-sdk-usage-guide.md) | **현행 SDK 사용 가이드 (메인)** — Quick Start, API 레퍼런스, HMAC 서명, 에러 처리, Spring Boot 연동 |
| [`onepass-agent-index.md`](onepass-agent-index.md) | Agency Java Agent — 문서 인덱스 |
| [`onepass-agent-integration-guide.md`](onepass-agent-integration-guide.md) | Agency Java Agent — 통합 가이드 |
| [`onepass-agent-walkthrough.md`](onepass-agent-walkthrough.md) | Agency Java Agent — 설치·운영 워크스루 |
| [`onepass-agent-troubleshooting.md`](onepass-agent-troubleshooting.md) | Agency Java Agent — 트러블슈팅 |
| [`sso-agency-developer-guide.md`](sso-agency-developer-guide.md) | 자체 SSO 보유 기관 — 개발자 레퍼런스 |
| [`sso-agency-integration-guide.md`](sso-agency-integration-guide.md) | 자체 SSO 보유 기관 — 담당자용 |
| [`sso-agency-operations-guide.md`](sso-agency-operations-guide.md) | 자체 SSO 보유 기관 — 운영 가이드 |

---

## 인프라 / 통합

| 파일 | 내용 |
|---|---|
| [`ext_api_proxy_guide.md`](ext_api_proxy_guide.md) | `/api/ext/**` 프록시 구조 및 Q-IM 연동 가이드 |
| [`kafka_easy_guide_for_developers.md`](kafka_easy_guide_for_developers.md) | Kafka 가이드 — 개발자용 |
| [`kafka_easy_guide_for_managers.md`](kafka_easy_guide_for_managers.md) | Kafka 가이드 — 관리자/사무관용 |

---

## `internal/` — 내부 개발 문서

> integration-sso 프로젝트 팀 내부 작업 문서. 외부 공유 전 검토 필요.

### `internal/architecture/` — 아키텍처 설계

| 파일 | 내용 |
|---|---|
| `oidc-brokering-design.md` | OnePass OIDC 브로커링 전체 설계서 |
| `qim-ido-integration-architecture.md` | Q-IM ↔ IdO 연동 아키텍처 설계서 |
| `agency-external-arch-supplement.md` | 유관기관 외부망 배치 설계 보완 (ARCH-SUPP-001) |
| `FEATURE_FLAGS.md` | IdO 기능 플래그 완전 가이드 |
| `onepass-agent-architecture.md` | OnePass Agency Java Agent 아키텍처 |
| `jeus-sso-deep-dive.md` | JEUS SSO 심층 분석 |
| `EDA-2026-001-eda-architecture-proposal-analysis.md` | EDA 아키텍처 제안 분석 |
| `ADR-2026-004-internal-sso-integration-pattern.md` | ADR: 내부 SSO 통합 패턴 |
| `ADR-2026-005-outbox-scheduler-module.md` | ADR: Outbox Scheduler 모듈 |

### `internal/dataflow/` — 흐름도

| 파일 | 내용 |
|---|---|
| `01-login-flow.md` | 로그인 흐름 |
| `02-member-conversion-flow.md` | 회원 전환 흐름 |
| `03-member-update-withdraw-flow.md` | 회원 수정/탈퇴 흐름 |
| `04-handoff-flow.md` | Handoff 흐름 |
| `05-nice-oacx-auth-flow.md` | NICE/OACX 본인인증 흐름 |
| `06-auth-providers-flow.md` | 인증 공급자 흐름 |

각 문서는 `.docx` 동봉.

### `internal/development/` — 개발 가이드 (★ 최신본만 표기)

| 파일 | 내용 |
|---|---|
| **`guide-backend-ido-2026-05-12.md`** | **★현행** IdO 백엔드 가이드 v3.0.0 |
| **`guide-backend-qim-2026-05-12.md`** | **★현행** Q-IM 백엔드 가이드 v3.0.0 |
| **`guide-frontend-2026-05-12.md`** | **★현행** FE 가이드 v3.0.0 |
| **`guide-infra-2026-05-12.md`** | **★현행** 인프라/DevOps 가이드 v3.0.0 |
| `api-reference-2026-05-12.md` | 전체 API 레퍼런스 v3.0.0 |
| `api-auth-spec.md` | 본인인증 API 명세 |
| `qim-development-guide.md` | Q-IM 정본 개발 가이드 |
| `qim-sp-receiver-api-spec.md` | IdO Q-IM SP 수신 API 명세 |
| `local-dev-guide.md` | 로컬 개발 환경 구동 가이드 |
| `member-conversion-implementation-plan.md` | 회원 전환 구현 플랜 |
| `onepass-be-integration-plan.md` | onepass-be 통합 플랜 |
| `onepass-agent-developer-reference.md` | Agent 개발자 레퍼런스 |
| `sso-agency-integration-plan.md` | SSO 유관기관 통합 플랜 |
| `01~13-*.md` | 프로젝트 개요 / 아키텍처 / 모듈별 가이드 (시리즈) |
| `handoff-note.md` | integration-sso 인수인계 노트 |
| `README.md` | development 시리즈 색인 |

> 구버전(`guide-backend-ido.md` 등 v2.3.0)은 `_archive/2026-05-22/internal/development/`로 이동.

### `internal/spec/` — 기술 명세서

| 파일 | 내용 |
|---|---|
| `00-index.md` | 명세서 색인 |
| `01-system-overview.md` | 시스템 개요 |
| `02-architecture.md` | 전체 아키텍처 |
| `03a-module-platform-common.md` | platform-common 모듈 |
| `03b-module-qsign.md` | Q-Sign 모듈 |
| `03c-module-qim.md` | Q-IM 모듈 |
| `03d-module-ido.md` | IdO 모듈 |
| `03e-module-agency-stub.md` | agency-stub 모듈 |
| **`api-reference-2026-05-12.md`** | **★현행** 전체 API 레퍼런스 v3.0.0 |
| `05-database-schema.md` | DB 스키마 |
| `06-kafka-event-catalog.md` | Kafka 이벤트 카탈로그 |
| `07-security.md` | 보안 명세 |
| `08-infrastructure.md` | 인프라 명세 |
| `09-gap-and-roadmap.md` | 갭 분석 및 로드맵 |

> 구버전(`04-api-reference.md` v1.9.3)은 `_archive/2026-05-22/internal/spec/`로 이동.

### `internal/analysis/` (현재 비어있음)

기존 시점 분석 보고서는 모두 `_archive/2026-05-22/internal/analysis/`로 이동되었습니다.
**최신 분석은 [`docs/analysis/sso-im-readiness/`](analysis/sso-im-readiness/00_INDEX.md)** 를 참조하세요.

---

## `features/` — 기능 단위 명세 (F-01~F-27)

| 카테고리 | 파일 |
|---|---|
| 인증/감사 | F-01-auth-rate-limit · F-02-agency-rate-limit · F-03-audit-kafka · F-04-audit-db · F-05-auth-tracing |
| 분산 락/보안 | F-08-redisson-lock · F-10-security-headers · F-12-crypto-rotation |
| 보존/Outbox | F-11-retention · F-13-outbox-relay · F-14-webhook-relay |
| SP 감사 | F-18-sp-receiver-audit |
| 프로비저닝 | F-20-provisioning · F-21-provisioning-relay · F-22-provisioning-dry-run |
| Gateway | F-23-gateway-inbound · F-24-gateway-outbound · F-25-gateway-idempotency |
| HMAC/키 | F-26-hmac-sig · F-27-agency-key-audit |

상세는 [`features/README.md`](features/README.md).

---

## `proposal/` — 제안서

| 문서 번호 | 파일명 | 형식 | 수신자 |
|---|---|---|---|
| PROP-2026-001 | `proposal-integration-sso-adoption` | MD + DOCX | 경영진 |
| PROP-2026-001-A | `proposal-executive-summary` | MD + DOCX | 최고 의사결정권자 |
| PROP-2026-001-B | `proposal-technical-adoption` | MD + DOCX | CTO / 기술 책임자 |
| — | `development-plan-2026-05-13.md` | MD | 개발팀 인계용 실행 플랜 |
| — | `technical-evidence-2026-05-13.md` | MD | 기술 현황 검토 보고서 |
| — | `onepass-architecture-proposal-2026-05-13.pptx` | PPTX | 아키텍처 제안 슬라이드 |

---

## `smep-handover/` — SMEP 팀 전달용

| 문서 ID | 파일명 | 형식 | 수신자 |
|---|---|---|---|
| MIG-2026-001 | `smep-migration-analysis` | MD + DOCX | BE 팀장 |
| MIG-2026-002 v2 | `smep-fullstack-migration-analysis` | MD + DOCX | FE+BE 팀장 |
| PRP-2026-001 v2 | `smep-migration-persuasion` | MD + DOCX | PM / 의사결정권자 |
| IMPL-2026-001 | `smep-migration-implementation-guide` | MD + DOCX | 통합플랫폼 개발자 |

전달 순서: MIG-2026-001 → MIG-2026-002 → PRP-2026-001 → IMPL-2026-001.

---

## `_archive/` — 보관 영역

`_archive/2026-05-22/`는 시점 산출물과 구버전 23건을 보존합니다. 상세 매니페스트는 [`_archive/2026-05-22/README.md`](_archive/2026-05-22/README.md).

복구가 필요할 때:
```bash
git mv docs/_archive/2026-05-22/<path> docs/<path>
```

---

## 문서 분류 기준

| 카테고리 | 위치 | 외부 공유 |
|---|---|---|
| 최신 분석 | `docs/analysis/sso-im-readiness/` | ❌ 검토 후 |
| 운영/배포 | `docs/deployment/`, `docs/OPERATION_INVENTORY.md`, `docs/RUNBOOK_SSO_METRICS.md` | ❌ 검토 후 |
| 외부 개발자용 SDK | `docs/onepass-agency-sdk-usage-guide.md`, `docs/onepass-agent-*.md`, `docs/sso-agency-*.md` | ✅ 배포 가능 |
| 기능 명세 | `docs/features/` | ❌ 검토 후 |
| 내부 개발 문서 | `docs/internal/` | ❌ 검토 후 |
| SMEP 인계 | `docs/smep-handover/` | ✅ 직접 전달 |
| 의사결정용 제안서 | `docs/proposal/` | ✅ 배포 가능 |
| 보관/이력 | `docs/_archive/` | — |

---

## 변경 이력

| 날짜 | 변경 내용 |
|---|---|
| 2026-05-22 | **문서 정리 1차** — Sprint α-3 머지 완료 시점. 시점 산출물/구버전 23건을 `_archive/2026-05-22/`로 이동(`git mv` 이력 보존). `docs/analysis/sso-im-readiness/` 11건 신규 추가. 살아있는 문서의 링크 25건 갱신. |
| 2026-05-18 | onepass-be-release / onepass-release 심층 분석 보고서 작성 (PR #131) → 현재 archive |
| 2026-05-16 | `onepass-agency-sdk-usage-guide.md` 신규 (PR #130) |
| 2026-05-11 | 초기 디렉토리 구조화 — `internal/`, `smep-handover/`, `proposal/` 분류 체계 수립 |
