# integration-sso 문서 디렉토리 안내

> **최종 정리일**: 2026-05-11  
> **전체 문서 수**: 64개 파일 (MD 57 + DOCX 7)

---

## 디렉토리 구조

```
docs/
├── README.md                  ← 이 파일 (전체 안내)
│
├── internal/                  ← integration-sso 내부 개발 문서
│   ├── architecture/          │   설계·아키텍처 설계서
│   ├── development/           │   개발 가이드·구현 계획·인수인계
│   ├── spec/                  │   모듈별 기술 명세서
│   └── analysis/              │   갭 분석·운영 준비도 분석
│
├── smep-handover/             ← SMEP 팀 전달용 문서 패키지 (외부 배포)
│
└── proposal/                  ← 경영진·외부 제출용 제안서
```

---

## 디렉토리별 설명

### `internal/` — 내부 개발 문서

integration-sso 프로젝트 팀 내부에서 사용하는 문서입니다.  
외부 공유 전 검토가 필요합니다.

#### `internal/architecture/` — 아키텍처 설계

| 파일 | 내용 |
|------|------|
| `oidc-brokering-design.md` | OnePass OIDC 브로커링 전체 설계서 |
| `qim-ido-integration-architecture.md` | Q-IM ↔ IdO 연동 아키텍처 설계서 |
| `agency-external-arch-supplement.md` | 유관기관 외부망 배치 설계 보완서 (ARCH-SUPP-001) |
| `FEATURE_FLAGS.md` | IdO 기능 플래그 완전 가이드 (v2.2.0 Sprint 9) |

#### `internal/development/` — 개발 가이드 및 구현 계획

| 파일 | 내용 |
|------|------|
| `01~13-*.md` | 프로젝트 개요 / 아키텍처 / 모듈별 개발 가이드 (시리즈) |
| `guide-backend-ido.md` | IdO 백엔드 개발 가이드 |
| `guide-backend-qim.md` | Q-IM 백엔드 개발 가이드 |
| `guide-frontend.md` | 프론트엔드 개발 가이드 |
| `guide-infra.md` | 인프라 구성 가이드 |
| `local-dev-guide.md` | 로컬 개발 환경 구동 가이드 (전체 스택) |
| `qim-development-guide.md` | Q-IM 팀 내부 정본 개발 가이드 |
| `api-auth-spec.md` | 본인인증 API 명세서 (onepass-fe 대상) |
| `qim-sp-receiver-api-spec.md` | IdO Q-IM SP 수신 API 명세서 |
| `member-conversion-implementation-plan.md` | 중기원패스 회원 전환 구현 플랜 |
| `onepass-be-integration-plan.md` | onepass-be → integration-sso 통합 플랜 |
| `2026-05-08_production_development_plan.md` | 실제 개발 전환 계획서 (2026-05-08) |
| `2026-05-09_v194_gap_implementation_plan.md` | v1.9.4 기준 미반영 항목 종합 개발 플랜 |
| `handoff-note.md` | integration-sso 인수인계 노트 (v1.9.0) |
| `README.md` | development 시리즈 문서 색인 |

#### `internal/spec/` — 기술 명세서

| 파일 | 내용 |
|------|------|
| `00-index.md` | 명세서 전체 색인 |
| `01-system-overview.md` | 시스템 개요 |
| `02-architecture.md` | 전체 아키텍처 |
| `03a~03e-module-*.md` | 모듈별 상세 명세 (platform-common / q-sign / q-im / ido / agency-stub) |
| `04-api-reference.md` | API 레퍼런스 |
| `05-database-schema.md` | 데이터베이스 스키마 |
| `06-kafka-event-catalog.md` | Kafka 이벤트 카탈로그 |
| `07-security.md` | 보안 명세 |
| `08-infrastructure.md` | 인프라 명세 |
| `09-gap-and-roadmap.md` | 갭 분석 및 로드맵 |

#### `internal/analysis/` — 분석 보고서

| 파일 | 내용 |
|------|------|
| `eda-master-arch-gap-analysis-v0.8.md` | EDA 마스터 아키텍처 v0.8 갭 분석 및 수정 보완 요청서 |
| `gap-analysis-v0.8.3-vs-project.md` | EDA 설계서 v0.8.3 vs 프로젝트 코드 Gap 분석 |
| `operational-readiness-analysis-v2.md` | Operational Readiness Analysis v2.2 |
| `2026-05-08_unimplemented_analysis.md` | PoC 미구현 상세 분석 보고서 (2026-05-08) |

---

### `smep-handover/` — SMEP 팀 전달용 문서 패키지

> **용도**: SMEP(중소기업 통합플랫폼) 팀에 전달할 마이그레이션 관련 문서 일체  
> **전달 방법**: DOCX 파일을 직접 전달하거나, 이 디렉토리를 ZIP으로 압축하여 전달  
> **전달 순서**: MIG-2026-001 → MIG-2026-002 → PRP-2026-001 → IMPL-2026-001

| 문서 ID | 파일명 | 형식 | 내용 | 수신자 |
|---------|--------|------|------|--------|
| MIG-2026-001 | `smep-migration-analysis` | MD + DOCX | SMEP BE 인증 코드 분석 (공수 산정 근거) | BE 팀장 |
| MIG-2026-002 v2 | `smep-fullstack-migration-analysis` | MD + DOCX | SMEP FE+BE 풀스택 마이그레이션 분석 (로그인 흐름 3경로 확정) | FE+BE 팀장 |
| PRP-2026-001 v2 | `smep-migration-persuasion` | MD + DOCX | 마이그레이션 설득 문서 (재사용 자산 강조, 공수 요약) | PM / 의사결정권자 |
| IMPL-2026-001 | `smep-migration-implementation-guide` | MD + DOCX | **마이그레이션 구현 가이드** (코드 레벨 Before/After, OIDC 분석 포함) | 통합플랫폼 개발자 |

#### 전달 시 권장 ZIP 생성 명령

```bash
cd docs/
zip -r smep-handover-package-$(date +%Y%m%d).zip smep-handover/*.docx
```

---

### `proposal/` — 경영진·외부 제출용 제안서

> **용도**: Integration-SSO 정식 채택을 위한 의사결정권자 대상 제안서  
> **작성일**: 2026-05-11  
> **문서 번호 체계**: PROP-2026-001 시리즈

| 문서 번호 | 파일명 | 형식 | 내용 | 수신자 |
|-----------|--------|------|------|--------|
| PROP-2026-001 | `proposal-integration-sso-adoption` | MD + DOCX | 정식 채택 제안서 (종합) | 경영진 |
| PROP-2026-001-A | `proposal-executive-summary` | MD + DOCX | 의사결정 요약 (1~2페이지 요약본) | 최고 의사결정권자 |
| PROP-2026-001-B | `proposal-technical-adoption` | MD + DOCX | 기술 채택 근거서 (기술 상세) | CTO / 기술 책임자 |

---

## 문서 분류 기준

| 카테고리 | 기준 | 외부 공유 |
|----------|------|-----------|
| `internal/` | integration-sso 팀 내부 작업 문서 | ❌ 검토 후 공유 |
| `smep-handover/` | SMEP 팀에 전달하기 위해 작성된 문서 | ✅ 직접 전달 가능 |
| `proposal/` | 경영진·외부 의사결정권자 대상 제안서 | ✅ 배포 가능 |

---

## 변경 이력

| 날짜 | 변경 내용 |
|------|---------|
| 2026-05-11 | 초기 디렉토리 구조화 — `internal/`, `smep-handover/`, `proposal/` 분류 체계 수립 |
| 2026-05-11 | docs 루트 전체 정리 완료 — 기존 루트 산재 파일 전량 하위 디렉토리로 이동, 총 64개 파일 분류 완료 |
