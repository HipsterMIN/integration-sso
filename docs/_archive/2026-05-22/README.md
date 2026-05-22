# 문서 아카이브 — 2026-05-22

> **목적**: 옵션 1(점진 수정/안전) 로드맵 진행 중 **시점 산출물**과 **최신 버전에 의해 대체된 문서**를 보존합니다.
> 이 디렉토리의 문서들은 **이력 보존용**이며, 운영/개발의 1차 참조 대상이 아닙니다.

## 분류

### 카테고리 A — 날짜 접미사로 대체된 구버전
같은 주제의 더 최신 버전(`*-2026-05-12.md` v3.0.0)이 살아있어서 보관만 합니다.

| 아카이브 경로 | 대체된 위치 (최신본) |
|---|---|
| `internal/development/guide-backend-ido.md` | `docs/internal/development/guide-backend-ido-2026-05-12.md` (v3.0.0) |
| `internal/development/guide-backend-qim.md` | `docs/internal/development/guide-backend-qim-2026-05-12.md` (v3.0.0) |
| `internal/development/guide-frontend.md` | `docs/internal/development/guide-frontend-2026-05-12.md` (v3.0.0) |
| `internal/development/guide-infra.md` | `docs/internal/development/guide-infra-2026-05-12.md` (v3.0.0) |

### 카테고리 B — 더 최신 명세에 의해 대체된 spec
| 아카이브 경로 | 대체된 위치 (최신본) |
|---|---|
| `internal/spec/04-api-reference.md` (v1.9.3, 2026-05-09) | `docs/internal/spec/api-reference-2026-05-12.md` (v3.0.0) |

### 카테고리 C — 시점 분석 보고서 (최신 분석 시리즈로 대체)
모든 문서가 `docs/analysis/sso-im-readiness/` (2026-05-22 작성, Phase 1-7 심층 분석 + Sprint α 결과)에 의해 대체되었습니다.

| 아카이브 경로 | 작성일 | 비고 |
|---|---|---|
| `internal/analysis/2026-05-08_unimplemented_analysis.md` | 2026-05-08 | PoC 미구현 상세 분석 (v1.7.0 기준) |
| `internal/analysis/20260512_171617_cross_analysis_vs_prior_report.md` | 2026-05-12 | 교차 검증 보고서 v1 |
| `internal/analysis/20260512_cross_analysis_second_review.md` | 2026-05-12 | 교차 검증 보고서 2차 |
| `internal/analysis/20260515_operational_readiness_analysis_v3.md` | 2026-05-15 | 운영 준비도 v3.0 |
| `internal/analysis/20260516_comprehensive_analysis_v1.md` | 2026-05-16 | 종합 분석 보고서 v1 |
| `internal/analysis/operational-readiness-analysis-v2.md` | 2026-05-08 | 운영 준비도 v2.2 |
| `internal/analysis/code-completeness-analysis.md` (+ docx) | 2026-05-11 | 코드 완성도 분석 |
| `internal/analysis/eda-master-arch-gap-analysis-v0.8.md` | 2026-05-07 | EDA 마스터 v0.8 갭 분석 |
| `internal/analysis/gap-analysis-v0.8.3-vs-project.md` | 2026-05-07 | EDA v0.8.3 vs 코드 갭 분석 |
| `internal/analysis/onepass-release-analysis.md` | 2026-05-18 | onepass-be-release 심층 분석 |

### 카테고리 D — 종료된 시점 플랜
종료/완료된 Sprint 단위 플랜. 후속 결과는 `docs/analysis/sso-im-readiness/07_risk_matrix_roadmap.md` 참조.

| 아카이브 경로 | 작성일 | 후속 |
|---|---|---|
| `internal/development/2026-05-08_production_development_plan.md` | 2026-05-08 | Sprint 1~6 종료 |
| `internal/development/2026-05-09_v194_gap_implementation_plan.md` | 2026-05-09 | v1.9.4 GAP 반영 종료 |
| `internal/development/2026-05-13_production_deployment_plan.md` | 2026-05-13 | Sprint α-1/α-2/α-3로 대체 |

### 카테고리 E — docs 직속 시점 산출물
| 아카이브 경로 | 작성일 | 대체 |
|---|---|---|
| `integration-plan-fe-be-2026-05-12.md` | 2026-05-12 | FE/BE 통합 완료 |
| `integration-sdk-guide-v2.md` (v2.0) | 2026-05-16 | `docs/onepass-agency-sdk-usage-guide.md` (2026-05-18, 메인 SDK 가이드) |
| `sprint17-release-note.md` | 2026-05-14 | 단발 릴리스 노트 (Sprint 17) |
| `deployment-guide.md` | 2026-05-14 | `docs/deployment/README.md` (2026-05-21, 최신) |

## 복구 / 재참조

특정 문서를 다시 활성 위치로 되돌려야 할 경우:

```bash
git mv docs/_archive/2026-05-22/<path> docs/<path>
# 그리고 docs/README.md / 루트 README.md 인덱스 갱신
```

`git log --follow <archived-path>` 로 이력 추적 가능합니다 (`git mv`로 이동했기 때문).
