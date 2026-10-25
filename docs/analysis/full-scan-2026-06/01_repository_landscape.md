# 01 · Repository Landscape — 저장소 지형도

> 스냅샷 기준: `git HEAD = 4f113e8 (Merge PR #205)` · 2026-06 · 브랜치 `docs/analysis-full-scan-2026-06`
> 이 문서는 저장소 표면의 **전수 목록**을 확립하고 이후 심층 문서(§03~§16)가 참조할 좌표계를 제공한다.

---

## 1. 저장소 정체성

| 항목 | 값 |
|---|---|
| rootProject.name | `onepass-platform` (settings.gradle.kts) |
| 상위 도메인 이름 | integration-sso (외부 배포명) |
| 최상위 저장소 명 | `HipsterMIN/webapp` (실제 원격 저장소는 GenSpark 미러) |
| 브랜치 정책 정본 | `shipster` = 개발 브랜치, `main` = 릴리스 (`CLAUDE.md`) |
| 정본 README 크기 | 75,006 bytes (`README.md`) |
| 릴리스 라벨(CLAUDE.md 명시) | v0.8.11 |
| 이 분석의 시점 커밋 | `4f113e8 Merge pull request #205 from HipsterMIN/shipster` |

---

## 2. 저장소 최상위 트리 (depth 1)

```
webapp/
├── CLAUDE.md                             # AI 협업 규칙 (shipster 브랜치 필수)
├── README.md                             # 정본 README (26+ 목차)
├── settings.gradle.kts                   # 12 모듈 (아래 §3)
├── build.gradle.kts                      # 루트 빌드 스크립트 (21,686 bytes)
├── gradlew / gradlew.bat / gradle/       # Gradle Wrapper (JDK 21)
│
├── platform-common/                      # M1 공통 도메인·이벤트·에러코드
├── q-sign/                               # M2 신원증명(OIDC 브로커 + PKCE)
├── q-im/                                 # M3 회원 SoR (MariaDB)
├── q-authz/                              # M4 ★신규 연합 인가 SoR (Postgres)
├── ido/                                  # M5 Identity DMZ Orchestrator (P/E/G/T)
├── onepass-support/                      # M6 CS 백오피스 + Q&A/FAQ
├── onepass-fe/                           # M7 React SPA (Single Channel)
├── agency-stub/                          # M8 유관기관 시뮬레이터 (E2E)
├── onepass-agency-sdk/                   # M9 Java SDK (기관 서버용)
├── outbox-relay-batch/                   # M10 Transactional Outbox 릴레이 (ShedLock)
├── onepass-agent/                        # M11 JVM Java Agent (자동 위빙)
│
├── onepass-agent-testbed/                # (모듈 아님) Agent E2E 테스트베드
├── infra/                                # docker / helm / k8s / monitoring / k6 / owasp / minikube
├── k6/                                   # 부하 테스트 스크립트 (별도 트리)
├── test/                                 # 부하 테스트 자산 (별도 트리)
├── docs/                                 # 정본 문서 트리 (§4)
├── wiki/                                 # 내부 위키(adr/deliverables/design/guide/iam/ops/walkthrough)
│
├── Outbox패턴_개발자가이드.md            # 120,497 bytes (개발자용)
├── Outbox패턴_비개발자용.md              # 23,594 bytes (비개발자용)
├── login-flow.html                       # 152,499 bytes 데모
├── outbox-diagram.html                   # 73,792 bytes 다이어그램
├── outbox-walkthrough.html               # 97,052 bytes 워크스루
└── 기능명세서_원패스통합인증플랫폼_v1.0.{md,xlsx}   # 초기 기능명세 (49KB 쌍)
```

**관찰 1**: 트리의 최상위에 **HTML/MD 자산이 100KB+ 스케일로 산재**한다 (login-flow.html 152KB, outbox-walkthrough.html 97KB). 이는 초기 프로토타이핑 시대의 잔재로, 실제 정본은 `docs/internal/` 로 이관되었음.

**관찰 2**: `onepass-agent-testbed/`는 settings.gradle.kts 에 include 되지 않은 **독립 테스트베드**다. 별도 프로파일로 기동한다.

---

## 3. 모듈 인벤토리 (12개, settings.gradle.kts 정본 순서)

| # | 모듈 | 소스 파일 수¹ | DB | 마이그레이션 수 | 최신 마이그레이션 | 포트² | 역할 요약 |
|---|---|---|---|---|---|---|---|
| M1 | `platform-common` | 23 | — | — | — | — | AuthResult / HandoffPayload / PlatformErrorCode / 이벤트 스키마 |
| M2 | `q-sign` | 53 | PostgreSQL | 5 | `V5__add_auth_method.sql` | 8081 | OIDC 브로커·PKCE·인증 결과 발행 |
| M3 | `q-im` | 111 | MariaDB (qim) | 7 | `V7__fix_datetime_timezone.sql` | 8082 | 회원 SoR·전환·탈퇴·미성년자 |
| M4 | `q-authz` ★ | 39 | PostgreSQL (authz) | 1 | `V1__create_authz_schema.sql` | 8086 | 연합 인가 SoR (L1) + SCIM Groups (L2) |
| M5 | `ido` | 270 | PostgreSQL (ido) | 19 | `V19__anyid_provider_config.sql` | 8083 | Identity DMZ Orchestrator (P/E/G/T) |
| M6 | `onepass-support` | 51 | PostgreSQL (support) | 3 | `V3__create_cs_backoffice_schema.sql` | 8085 | Q&A/FAQ + CS 백오피스 |
| M7 | `onepass-fe` | 0 (Java) / TS+TSX | — | — | — | 3000/443 | React SPA (Single Channel FE) |
| M8 | `agency-stub` | 38 | PostgreSQL (agency) | 2 | `V2__add_webhook_and_api_key.sql` | 8084 | 유관기관 시뮬레이터 (E2E) |
| M9 | `onepass-agency-sdk` | 13 | — | — | — | (SDK) | 기관 서버 Java SDK |
| M10 | `outbox-relay-batch` | 20 | (relay only, ShedLock) | — | — | 8088 | Outbox → Kafka 분산 릴레이 |
| M11 | `onepass-agent` | 25 | — | — | — | (agent) | JVM Java Agent (-javaagent, 자동 위빙) |

¹ `find <m>/src -type f \( -name '*.java' -o -name '*.sql' -o -name '*.yml' -o -name '*.yaml' \)` 결과.
² 정본 포트 (docs/internal/spec/08-infrastructure.md 기준). onepass-fe 는 Nginx/dev-server 이중 표기.

**IdO 서브 패키지 27개** (검증):
```
admin, api, audit, auth, broker, burst, config, conversion,
crypto, domain, ext, fe, gateway, handoff, infrastructure,
kafka, memberlookup, metrics, policy, provision, qim,
ratelimit, retention, slo, sso, webhook
```

**IdO 컨트롤러 인벤토리 (19개)** — grep `@RestController|@Controller`:
`AgencyAdminController`, `AgencyEventController`, `HandoffController`, `AuthController`, `BrokerController`, `OidcCompleteController`, `AnyIdController`, `KeycloakCallbackController`, `NonOidcBrokerController`, `ConversionInitController`, `ConversionSessionController`, **`ExtProxyController`**, **`FeSessionController`**, `AgencyGatewayController`, `MemberLookupController`, `QimSpReceiverController`, `SloController`, **`CrossAgencySsoController`**, `GlobalExceptionHandler`.

**IdO Kafka 컨슈머 (4개)**: `HandoffEventConsumer`, `IdempotentEventStore`, `QimEventConsumer`, `QsignAuthEventConsumer`.

**IdO Broker 서브패키지**: `anyid`, `dto`, `keycloak`, `nonoidc`, `provider`, `state` + `BrokerAuditLogService`, `InternalSigVerifier`, `IdpBrokerService`.

---

## 4. 문서 자산 인벤토리 (`docs/`)

### 4.1 `docs/internal/spec/` (정본 스펙)
```
00-index.md
01-system-overview.md
02-architecture.md
03a-module-platform-common.md          ← 정본 (commit 46b1fe9)
03b-module-qsign.md
03c-module-qim.md                      ← 구정본 (헌장 이전본)
03c-qim-responsibility-charter.md      ← 신정본 (§6.5 UI 금지 추가)
03d-module-ido.md
03e-module-agency-stub.md
03f-module-onepass-fe.md               ← PR #202/#203/#204 반영
05-database-schema.md
06-kafka-event-catalog.md
07-security.md
08-infrastructure.md
09-gap-and-roadmap.md
api-reference-2026-05-12.md
```

**결여**: q-authz 관련 `03g-module-qauthz.md` 미생성. → 본 분석의 §06 이 그 공백을 임시 대체한다(정본이 아님, 관찰).

### 4.2 `docs/internal/architecture/`
```
ADR-2026-004-internal-sso-integration-pattern.md    (자체 SSO 통합 4개 대안)
ADR-2026-005-outbox-scheduler-module.md             (outbox-scheduler PROPOSED)
EDA-2026-001-eda-architecture-proposal-analysis.md  (EDA 제안서 분석)
FEATURE_FLAGS.md                                    (F-01~F-18 18개 플래그)
agency-external-arch-supplement.md
jeus-sso-deep-dive.md                               (JEUS 위빙 상세)
oidc-brokering-design.md
onepass-agent-architecture.md
qim-ido-integration-architecture.md
```

**결여**: `ADR-001~003, 008` 은 별도 `wiki/adr/` 트리에 있음 → 이관 필요 (문서 파편화 신호).

### 4.3 `docs/internal/dataflow/` (Mermaid + docx 쌍)
```
01-login-flow.md / .docx                            (5가지 로그인 유형)
02-member-conversion-flow.md / .docx                (전환)
03-member-update-withdraw-flow.md / .docx           (변경/탈퇴)
04-handoff-flow.md / .docx                          (Handoff 발급/검증)
05-nice-oacx-auth-flow.md / .docx                   (NICE OACX)
06-auth-providers-flow.md / .docx                   (Provider 다중화)
```

### 4.4 `docs/internal/development/`
- (하위 파일 미상세) — `patch_design_doc.py` 스크립트 존재.

### 4.5 `docs/analysis/` (본 산출물 상위)
- `sso-im-readiness/` — 이전 SSO/IM 준비도 분석
- **`full-scan-2026-06/`** ← **본 분석 산출물 위치** (2026-06 스냅샷)

### 4.6 `docs/` 최상위
- `OPERATION_INVENTORY.md`, `RUNBOOK_SSO_METRICS.md`, `SPRINT_B_PLAN.md`
- `sso-agency-*-guide.md` (3종: developer/integration/operations)
- `onepass-agent-*.md` (4종: index/integration-guide/troubleshooting/walkthrough)
- `onepass-agency-sdk-usage-guide.md`
- `onepass-support-*-plan.md` (2종)
- `kafka_easy_guide_for_*.md` (developers/managers)
- `ext_api_proxy_guide.md`, `phased-rollout-strategy.md`
- `release-go-no-go-{20260524, main-flow-20260524}.md`
- `통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.7.docx` (마스터 아키텍처 설계서)

### 4.7 `wiki/`
```
adr/                # ADR-001 ~ ADR-008 (신규)
deliverables/       # 산출물
design/             # 설계 초안
docx/               # 임시 문서
guide/              # 운영 가이드
iam/                # IAM 상세
ops/                # 운영 절차
walkthrough/        # 워크스루
```

---

## 5. 인프라 자산

### 5.1 `infra/docker/`
| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | 정본 전체 스택 (프로파일: app/keycloak/optionB/tools/monitoring) |
| `compose.base.yml` | 최소 base (network + 볼륨) |
| `compose.sso-im.yml` | SSO/IM 인프라만 |
| `compose.sso-im-apps.yml` | + SSO/IM 앱 |
| `compose.support.yml` | onepass-support 독립 스택 |
| `compose.monitoring.yml` | monitoring 오버레이 |
| `compose.tools.yml` | pgAdmin/Adminer 등 |
| `docker-compose.monitoring.yml` | 별도 monitoring |
| `init-db.sql` | DB 초기화 SQL |
| 서브트리 | `kafka/`, `keycloak/`, `mariadb/`, `nginx/`, `postgres/`, `redis/` |

### 5.2 `infra/helm/`
- `ido/` — IdO Helm 차트
- `onepass/` — 통합 Helm 차트

### 5.3 `infra/k8s/`
- `configmaps/`, `deployments/`, `networkpolicies/`, `secrets/`

### 5.4 `infra/monitoring/`
- `dashboards/`, `grafana/`, `loki/`, `prometheus/`, `promtail/`

### 5.5 `infra/{k6, minikube, owasp, scripts}/`
- k6 부하 테스트, minikube 로컬 K8s, OWASP 설정, 유틸 스크립트

---

## 6. CI/CD 자산 (`.github/workflows/`)
| 워크플로 | 트리거 | 목적 |
|---|---|---|
| `ci.yml` | push (main/develop/genspark_ai_developer/shipster) + PR | Gradle 빌드 + 단위 테스트 + FE tsc + OWASP + (opt) SonarQube |
| `nogo-quick.yml` | 수동 / 이벤트 | 릴리스 NO-GO 빠른 검증 |
| `nogo-full.yml` | 수동 / 이벤트 | 릴리스 NO-GO 전체 검증 |

**환경변수 정본 (ci.yml)**:
- `JAVA_VERSION: 21`
- `GRADLE_OPTS: -Dorg.gradle.daemon=false -Dorg.gradle.parallel=false`
- `DOCKER_UNAVAILABLE: 'true'` (Testcontainers 자동 skip)

---

## 7. 분산 상수: 포트 매핑 원장 (docs/internal/spec/08 정본)

| 서비스 | 포트 | 프로파일 | 비고 |
|---|---|---|---|
| PostgreSQL 16 | 5432 | 기본 | qsign / ido / agency / authz / support / keycloak DB |
| MariaDB 11 | 3306 | 기본 | qim 전용 |
| Redis 7 | 6379 | 기본 | 세션/캐시/락 |
| Kafka | 9092 | 기본 | Broker |
| Kafka-UI | 8090 | 기본 | Web UI |
| Redis-Insight | 5540 | 기본 | Web UI |
| pgAdmin | 5050 | tools | |
| Adminer | 8091 | tools | MariaDB용 |
| Keycloak | 8180 | keycloak | OIDC IdP |
| Q-Sign | 8081 | app | |
| Q-IM | 8082 | app | |
| IdO | 8083 | app | |
| Agency-Stub | 8084 | app | |
| onepass-support | 8085 | app | |
| **q-authz** ★ | **8086** | app | (spec 09 미반영, 소스 확인) |
| outbox-relay-batch | 8088 | app | |
| onepass-fe (dev) | 3000 | optionB | React dev server |
| onepass-fe (nginx) | 443/80 | optionB | Nginx SPA 배포 |
| Prometheus | 9090 | monitoring | |
| Grafana | 3001 | monitoring | |
| Loki | 3100 | monitoring | |

---

## 8. 브랜치·릴리스 정책 (CLAUDE.md 요약)

| 항목 | 규칙 |
|---|---|
| 개발 브랜치 | `shipster` (모든 코드 변경 정본) |
| 릴리스 브랜치 | `main` (병합 대상) |
| 예외 | **docs-only** PR 은 `docs/*` 브랜치 허용 (PR #202/#203/#204 선례) |
| 본 분석의 브랜치 | `docs/analysis-full-scan-2026-06` — 위 예외 준수 |
| PR base | `main` (docs-only인 경우) 또는 `shipster` (코드 변경 시) |

---

## 9. 요약 진단

| 항목 | 상태 | 근거 |
|---|---|---|
| 모듈 정본 개수 | 12개 (신규 q-authz 편입 후 안정) | settings.gradle.kts |
| 스펙 문서 커버리지 | **q-authz 결여** — 11/12 (91.7%) | `docs/internal/spec/` 에 03g 부재 |
| 최상위 자산 파편화 | HTML/MD 대용량 산재 (초기 프로토타입 잔재) | 400KB+ 최상위 HTML/MD |
| ADR 위치 파편화 | `docs/internal/architecture/` vs `wiki/adr/` | 2군데 병존 |
| 마이그레이션 최신도 | IdO V19, qsign V5, qim V7, authz V1, agency V2, support V3 | `db/migration/` 실측 |
| CI 브랜치 커버리지 | main/develop/genspark_ai_developer/shipster | ci.yml on.push.branches |
| Testcontainers 조건부 | `DOCKER_UNAVAILABLE=true` → skip | ci.yml |

---

## 10. 이어지는 문서 참조

- 아키텍처 심층 → `02_architecture_deep_dive.md`
- IdO 심층 → `03_module_ido.md`
- q-authz 심층 (★신규) → `06_module_qauthz.md`
- 스펙 이관 필요 항목 → `17_gaps_debts_roadmap.md`

— (이 문서는 §00_INDEX.md 관찰 A/D 의 근거 표를 제공한다.)
