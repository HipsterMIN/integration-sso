# integration-sso 전체 재분석 — 2026-06

> **분석 요청**: "integration-sso 프로젝트 분석을 다시 진행해줘. 처음부터 끝까지. 분석되는 과정의 모든 것을 문서로 작성해서 저장해줘. 깊게 생각해서 분석 진행해줘."
> **기준 시점**: 2026-06 (main HEAD `4f113e8`, PR #205 merge 직후)
> **분석자**: Claude AI 개발자 (요청자 지시 하에)
> **선행 분석**: `docs/analysis/sso-im-readiness/` (SSO/IM readiness 범위 한정) — 본 분석은 이보다 넓은 전체 재스캔

---

## 0. 이 분석이 하려는 것 vs 이미 존재하는 정본 문서

이 저장소에는 이미 `docs/internal/spec/00-index.md` ~ `09-gap-and-roadmap.md` 라는 **정본 스펙 세트**가 존재한다. 본 분석의 목적은 그것을 **다시 쓰는 것이 아니라**:

1. **정본이 커버하지 않는 신규 요소** — 특히 PR #205 로 도입된 **q-authz 모듈**과 그에 연결된 IdO 증분 4건 — 을 코드 근거로 문서화한다 (03g 급 상세).
2. **정본이 시간상 뒤로 밀린 사실** — `03a~e` 는 `46b1fe9` 기준, `03c-charter` 는 `a564f36` 기준, `03f` 는 `50954de` 기준 → 이후 PR #202/#203/#204/#205 의 변경을 **하나의 시점(2026-06/`4f113e8`)** 에서 정합화한다.
3. **정본이 모듈 단위로 흩어진 사실을 축(axis)별로 다시 얽기** — 데이터 흐름·보안 표면·이벤트 토폴로지·DB·인프라·부채 — 로 재정렬한다.
4. **모든 조사 과정 자체를 문서화** — 어떤 파일을 읽었고, 어떤 grep/find 를 돌렸고, 어떤 근거로 어떤 결론에 도달했는지를 **재현 가능**하게 남긴다 (§19 부록 명령 원장).

**따라서 이 분석 문서 세트는 정본을 대체하지 않고 정본에 **덮어씌우는 6월 스냅샷 + 신규 도메인 편입 문서**다.** 정본과 이 분석이 충돌하면, 정본 문서에 반영시키는 PR 로 후속 처리한다 (이 분석 안에서는 하지 않는다).

---

## 1. 문서 편성 (총 19편)

| # | 파일 | 커버 영역 | 주요 근거 |
|---|------|---------|---------|
| 00 | `00_INDEX.md` (이 문서) | 로드맵 / 편성 / 재현 지침 | — |
| 01 | `01_repository_landscape.md` | 저장소 top-level, 12개 Gradle 모듈, 문서 자산 인벤토리 | `settings.gradle.kts`, `find`, `ls` |
| 02 | `02_architecture_deep_dive.md` | 4+1 → 5+1 축, ADR-001~008 요약, EDA 위상 | `02-architecture.md`, ADR 문서, `EDA-2026-001` |
| 03 | `03_module_ido.md` | IdO 27 패키지 · 18 컨트롤러 · V19 마이그레이션 심층 | `03d`, `find ido/src`, 컨트롤러/마이그레이션 실물 |
| 04 | `04_module_qim.md` | Q-IM SoR · 6.5 UI 금지선 · 회색지대 · V3 스키마 | `03c`, `03c-charter`, `qim-development-guide.md` |
| 05 | `05_module_qsign.md` | Q-Sign OIDC 브로커 · PKCE · Outbox · 멱등 소비 | `03b`, V1~V5 마이그레이션 |
| 06 | `06_module_qauthz.md` ★신규 | **q-authz — 정본 스펙 부재. 코드 유일 원천 문서화** | `q-authz/src/main/java/**`, `V1__create_authz_schema.sql`, `application.yml`, PR #205 commits |
| 07 | `07_module_agency_stub.md` | agency-stub 9 컨트롤러 · E2E 시뮬레이터 · Webhook 수신 | `03e` |
| 08 | `08_module_onepass_fe.md` | React SPA · Nginx · axios 단일 채널 · 4계층 State | `03f`, PR #203/#204 변경 |
| 09 | `09_module_onepass_support.md` | onepass-support CS 백오피스 · FAQ/QnA · Keycloak JWT 인증 | PR #179, `docs/onepass-support/*` |
| 10 | `10_module_common_and_outbox_relay.md` | platform-common 도메인/이벤트 + `outbox-relay-batch` ShedLock | `03a`, ADR-005 |
| 11 | `11_module_onepass_agent_and_sdk.md` | onepass-agent (Java Agent) + onepass-agency-sdk | `onepass-agent-architecture.md`, `onepass-agent-developer-reference.md` |
| 12 | `12_data_flows_verified.md` | 6 정본 flow + 신규 authz roles 흐름 (총 7개) | `dataflow/01~06`, 신규 q-authz 코드 |
| 13 | `13_security_surface.md` | 8계층 보안 표면 + 신규 X-Authz-* 헤더 + 남은 GAP | `07-security.md`, `SEC-QIM-*`, `SEC-IDO-*`, PR α 시리즈 |
| 14 | `14_kafka_eda_topology.md` | 12토픽 위상 + Outbox 5출처 + DLQ + ShedLock | `06-kafka-event-catalog.md`, ADR-005, EDA-2026-001 |
| 15 | `15_db_schema_consolidated.md` | Postgres(qsign+ido+authz) + MariaDB(qim) + Redis 키 패턴 | `05-database-schema.md` + q-authz V1 |
| 16 | `16_infra_deployment_ops.md` | docker-compose · Helm/K8s · 모니터링 스택 · 포트맵 | `08-infrastructure.md`, `infra/**` |
| 17 | `17_gaps_debts_roadmap.md` | P0/P1/P2/P3/DEBT + FE Phase 2/3 + authz 미완 | `09-gap-and-roadmap.md` + 본 분석 신규 관찰 |
| 18 | `18_recent_changes_ledger.md` | PR #176~#205 (α-1~α-3, onepass-support, ADR-008, rename, q-authz) | `git log`, PR 본문 |
| 19 | `19_appendix_command_ledger.md` | 본 분석 재현용 명령·산출물 원장 | 실행 로그 |

---

## 2. 이번 스냅샷의 5가지 핵심 관찰 (Executive Summary)

각 항목의 상세는 해당 문서에서 근거와 함께 다룬다.

### 관찰 A — 축(axis) 모델이 4+1 → **5+1** 로 확장되었으나 정본이 미반영

원래 정본(`01-system-overview.md` §3):
```
Q-Sign / Q-IM / IdO / onepass-fe (+ agency-stub)  =  4+1
```

`4f113e8` 시점 실체 (`settings.gradle.kts` + `q-authz/**`):
```
Q-Sign / Q-IM / Q-Authz / IdO / onepass-fe  (+ agency-stub, onepass-support, outbox-relay-batch, onepass-agent, onepass-agency-sdk)  =  5+N
```

**q-authz** 는 "부여는 중앙, 해석은 지역" 원칙의 **연합 인가(Federated Authorization) SoR** 로, Q-IM(정체성) 옆에서 별도 SoR 로 서 있다. 정본 개편 필요.

→ 상세: **§06 (q-authz)**, **§02 (architecture)**, **§17 (gaps)**

### 관찰 B — ADR-008 헌법이 Phase 2 코드로 완결되었으나 fallback 잔재 존재

`onepass-fe` 는 **BE 노출 표면 = IdO 1점** 이라는 헌법(ADR-008)을 코드까지 강제 완료:
- 파일 `api/beInstance.ts` / `api/extInstance.ts` **삭제** (PR #203)
- 환경변수 `BE_API_*` **코드에서 제거** — 단 `process.env.BE_API_*` fallback 1페이즈 잔존 (의도된 마이그레이션 안전망)
- 문서 `03f` / `09` cross-ref **정합화** (PR #204)

남은 정리 대상 (§17 참조):
- `process.env.BE_API_*` fallback 완전 제거 (다음 페이즈)
- `webpack.config.*` 의 `BE_API_*` DefinePlugin alias 제거
- `SEC-IDO-10~15` — `onepass-admin` 도입 선행 조건 (API 키 스코핑 / CORS N-origin / 쿠키 도메인 격리 등)

→ 상세: **§08 (onepass-fe)**, **§13 (security)**, **§17 (gaps)**

### 관찰 C — q-authz 도입으로 **토큰 클레임 파이프라인**이 확장되었음

새로운 데이터 흐름:
```
(부여) 관리자/SCIM → q-authz L1 (grantRole) → authz_user_role 저장 → grant_audit 기록
                                          → (한시 권한이면) AuthzExpiryScheduler → EXPIRED 전이
                                          → SCIM 2.0 Groups (PUT/PATCH) 로 기관 프로비저닝 동기화

(발급) FE 로그인 완료 → IdO CastTokenServiceImpl.issue()
                     → QAuthzClient.getEffectiveRoles(qimUserId, targetAgency)  ← ★NEW
                     → JWT roles[] 클레임 임베드 → CAST 토큰 전달

(발급) IdO HandoffServiceImpl.buildPlainPayload()
                     → QAuthzClient.getEffectiveRoles(qimUserId, agencyCode)     ← ★NEW
                     → payload.put("roles", ...) → AES-256-GCM 암호화 → Handoff Ticket

(전파) IdO ExtProxyController (/api/ext/**)
                     → FE 세션 → qimUserId 해석
                     → QAuthzClient.getEffectiveRoles(qimUserId, "PLATFORM")     ← ★NEW
                     → X-Authz-User / X-Authz-Roles / X-Authz-Scope 헤더 Q-IM 으로 전파
                     → (anti-spoofing) FE 가 위조 주입한 X-Authz-* 는 forward 전 제거
```

핵심 정책 4가지:
- **fail-open**: q-authz 장애 시 빈 역할 반환 → SSO/Handoff 발급 자체는 계속 (인증 가용성 보호)
- **anti-spoofing**: FE 가 넘긴 `X-Authz-*` 는 절대 신뢰 안 함, ido 가 서버측 재주입
- **부여 중앙 / 해석 지역**: q-authz 는 "누가 어떤 역할" 만 SoR. "그 역할로 뭘 하는가" 는 기관 PEP 책임
- **의미 통일 금지**: role_code 는 기관별 불투명 문자열, 60+ 기관 권한 의미론을 통일하지 않음

→ 상세: **§06 (q-authz)**, **§12 (data flows)**, **§13 (security)**

### 관찰 D — 정본 스펙 커밋 기반이 파편화됨 (문서 정비 부채)

| 문서 | 명시 기준 커밋 | 현재 main HEAD | Delta |
|-----|--------------|-------------|------|
| `03a`~`03e` (모듈 정본) | `46b1fe9` | `4f113e8` | +58 commits (미반영) |
| `03c-charter` (Q-IM 헌장) | `a564f36` | `4f113e8` | +7 commits (미반영) |
| `03f` (onepass-fe) | `50954de` → 이후 PR #204 로 부분 갱신 | `4f113e8` | +q-authz 미반영 |
| `05`~`09` (DB/Kafka/보안/인프라/gap) | `46b1fe9` | `4f113e8` | +58 commits (미반영) |
| `api-reference-2026-05-12` | 2026-05-12 | 2026-06 | 1개월 이상 미갱신 |

특히 다음 실체가 정본에 없음:
- q-authz 서비스 자체 (포트 8086)
- Kafka 토픽 표에 q-authz 관련 항목 (현재 없음 — q-authz 는 HTTP 만 사용)
- broker_audit_log 인덱스 이후 마이그레이션 (V11~V19)
- `X-Authz-*` 헤더 3종
- FE `AES_GCM_KEY` 리스크의 최신 상태
- Sprint α-1~α-3 반영 (본 저장소 README 는 "α-3 완료" 언급하지만 스펙 09 표는 v1.9.3 까지만)

→ 상세: **§17 (roadmap)**, **§18 (change ledger)**

### 관찰 E — 테스트 커버리지가 여전히 최대 위험 지대

`09-gap-and-roadmap.md` §1 자기평가:
```
테스트           ░░░░░░░░░░░░░░░░░░░░   0%  (단위·통합 테스트 전무)
```

이번 스냅샷에서 재측정 (§19 부록 명령):
```
ido       test= 38 (+ authz 관련 4건 신규)
q-im      test= 14
q-sign    test=  5
q-authz   test=  5   ★NEW
agency-stub test= 13
onepass-support test= 5
```

즉 소스코드 대비 테스트 파일 비율이 여전히 **1/4~1/5 수준**. 특히 신규 q-authz 는 5개 테스트로 L1/L2 커버 시도 중이지만 통합 시나리오 (IdO ↔ q-authz ↔ FE) 는 없다. **P3-03 E2E 자동화 테스트 6종 시나리오는 여전히 미구현**.

→ 상세: **§13 (security)**, **§17 (gaps)**

---

## 3. 이 분석의 재현 방법 (Reproducibility)

본 분석을 다시 돌리려면:

```bash
# 0. 저장소 clone 후 최신 main
git fetch origin main
git checkout 4f113e8   # 이 분석의 기준 commit

# 1. 이 디렉토리 전체 읽기 (순서 준수 권장 = 00 → 19)
cd docs/analysis/full-scan-2026-06/

# 2. 각 문서의 "근거" 섹션에 있는 파일들을 직접 확인
#    (예: §06 은 q-authz/ 소스 전체, §12 는 dataflow/ 6종)

# 3. §19 부록의 명령 원장 재실행
bash <(sed -n '/^```bash/,/^```/p' 19_appendix_command_ledger.md | grep -v '^```')

# 4. 그 결과가 §1 의 통계표와 일치하는지 확인
#    (모듈별 소스/테스트 파일 수, 컨트롤러 수, 마이그레이션 수)
```

---

## 4. 이 분석 후 자연스러운 다음 단계 (제안, 본 분석 範圍 외)

1. **정본 갱신 PR 시리즈** (본 분석을 근거로):
   - `docs/internal/spec/03g-module-qauthz.md` 신설 (본 §06 을 정본 형식으로)
   - `01-system-overview.md` §3 축 모델 4+1 → 5+1 갱신
   - `05-database-schema.md` §7 에 authz 스키마 편입
   - `06-kafka-event-catalog.md` §1 표에 q-authz "발행 없음, 순수 HTTP" 명시
   - `07-security.md` §2 에 `X-Authz-*` 3종 헤더 정책 추가
   - `09-gap-and-roadmap.md` §9 버전 히스토리에 v0.8.11 / α-1~α-3 / q-authz L1/L2 편입

2. **fallback 잔재 완전 제거** (SEC-IDO-16 신설 후보):
   - `onepass-fe/frontend/src/api/idoInstance.ts` 의 `|| process.env.BE_API_*` 라인 제거
   - `webpack.config.*` DefinePlugin BE_API_* alias 제거
   - README / DEVELOPMENT.md 최종 grep 검증

3. **q-authz 강화** (본 §06 결론 참조):
   - Multi-instance 시 ShedLock 도입 (현재 단일 리더 가정 → 만료 감사 중복 위험)
   - Bearer 토큰 인증 (MVP 는 X-Internal-Api-Key + 상수시간 비교)
   - `qim.user.events` 컨슈머 도입 (사용자 탈퇴 시 자동 역할 회수)

4. **테스트 커버리지 최우선 상승**:
   - E2E 6 시나리오 (P3-03)
   - q-authz ↔ IdO ↔ FE 통합 테스트
   - k6 200 TPS 시나리오 정상 재실행

---

*최종 작성: 2026-06-XX / 본 분석 후 정본 문서 갱신 PR 은 별도 진행*
