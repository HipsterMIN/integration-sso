# 유관기관 회원·사용자 통합(IM 확대) 마스터 플랜

> **버전** v0.1 (DRAFT) · **작성일** 2026-06-25 · **대상 브랜치** `shipster`
> **목표** SSO(인증 연계)를 넘어, 60~68개 유관기관에 흩어진 회원·사용자를 **OnePass 통합회원(단일 `qimUserId`)으로 수렴**하고 기관 회원원장을 연결한다.
> **성격** 본 문서는 *실행 계획*이며, 현 코드베이스(q-im / ido / q-authz / outbox-relay-batch)의 **실제 자산과 격차에 근거**한다. 일반론이 아니라 "지금 있는 것 위에서 무엇을 연결·자동화·정비할지"를 기술한다.

---

## 0. 한눈에 보기 (Executive Summary)

- **핵심 명제**: *부여/식별의 정본은 중앙(OnePass), 해석·집행은 기관*이라는 기존 설계 원칙을 **회원 식별 영역으로 확장**한다. 즉 "동일인 → 단일 `qimUserId`"라는 **불변식**을 전 기관·전 인증수단에 걸쳐 강제한다.
- **현 상태 요약**: 식별·연동 인프라는 **대부분 존재**하나, 동일인을 자동으로 하나로 묶는 **식별 해소(identity resolution) 자동화가 비어 있다**. 그 결과 같은 사람이 인증수단(소셜 sub vs 실명 CI)·기관별로 **복수 `qimUserId`로 분산**된다.
- **통합의 본질**: ① 인증수단 간 동일인 병합(소셜↔실명), ② 기관 회원원장 ↔ `qimUserId` 매핑 적재(대량 백필), ③ 병합 시 권한·세션·핸드오프·기관 통지의 **연쇄 재이행(fan-out)**, ④ 개인정보 법규(PIPA·본인확인기관 기준) 준수.
- **접근**: **결정론적 CI 매칭을 1차 앵커**로, 확률적 매칭은 *수동 검토 큐*로만 보조. 무차별 자동 병합 금지(오결합은 보안사고).
- **단계**: Phase 0(기반 정비) → 1(런타임 동일인 자동연결) → 2(병합 오퍼레이션 정식화) → 3(기관 회원 대량 통합) → 4(정합성·운영).

---

## 목차
1. [현황 진단 (As-Is)](#1-현황-진단-as-is)
2. [목표 모델 (To-Be)](#2-목표-모델-to-be)
3. [식별 해소 전략](#3-식별-해소-전략-identity-resolution)
4. [단계별 통합 플랜](#4-단계별-통합-플랜)
5. [데이터 모델 변경](#5-데이터-모델-변경)
6. [병합 연쇄 재이행 (Fan-out)](#6-병합-연쇄-재이행-fan-out)
7. [기관별 회원 통합 런북](#7-기관별-회원-통합-런북-per-agency-runbook)
8. [개인정보·법적 준수](#8-개인정보법적-준수)
9. [매칭 알고리즘 상세](#9-매칭-알고리즘-상세)
10. [리스크 & 완화](#10-리스크--완화)
11. [마일스톤·KPI·산출물](#11-마일스톤kpi산출물)
12. [열린 의사결정 항목](#12-열린-의사결정-항목-decisions-needed)

---

## 1. 현황 진단 (As-Is)

### 1.1 식별자 계층 (코드 근거)

| 식별자 | 범위 | 유일성 | 생성/저장 | 현재 용도 |
|--------|------|--------|-----------|-----------|
| `qimUserId` | 플랫폼 전역 | 1人 1ID(목표) | UUID, `qim_user.qim_user_id` | 통합 회원 마스터 키 |
| **CI**(연계정보) | **전국** | 1人 1CI | NICE/공동인증 → `user_profile.ci` (AES-256-GCM `v{n}.{iv}.{ct}`) | **중복가입 확인 정본** |
| `identifierHash` | 조회 키 | (hash, provider) 복합 | `SHA-256(CI)` 또는 `SHA-256(sub)` → `auth_mean_mapping.identifier_hash` | 인증수단 매핑 조회 |
| **DI**(내부) | 기관별 | (agency, qimUserId) 결정론적 | `HMAC-SHA256(agencyCode:qimUserId)` → `user_profile.di_map` JSON | 기관 전달용 `agencySubjectId` |
| 소셜 `sub` | provider별 | (provider, sub) | Keycloak/소셜 IdP | 소셜 계정 식별 |

> ⚠️ **중요 구분**: 현재 `agencySubjectId`로 기관에 내려가는 "DI"는 NICE가 발급하는 **국가표준 DI가 아니라**, 플랫폼 내부 `HMAC(agencyCode:qimUserId)`다(`DiGenerationService`). 따라서 **DI는 `qimUserId`에 종속** — `qimUserId`가 통합되어야 기관 간 동일인 인식이 성립한다. (국가표준 DI를 중복판정/연계에 별도로 쓸지는 [§12 결정 항목].)

### 1.2 이미 존재하는 통합 자산 (재사용 대상)

- **CI 기반 조회/중복탐지**: `MemberLookupController` — `POST /api/v1/internal/member/lookup-by-ci` → `SHA-256(CI)`로 기존 `qimUserId` 반환.
- **계정 전환 세션**: `conversion_session`(q-im V5) — `candidate_members_json` / `selected_agency_codes_json` / `linked_agency_codes_json` + 상태기계(INITIATED→…→COMPLETED). 다기관 계정연결 골격 **이미 존재**.
- **병합 이벤트 골격**: `UserEvent.TYPE_MERGED` + `mergedIntoQimUserId` 필드. Q-Sign `QimUserEventConsumer`가 이를 소비해 잠금 해제까지 함 — 단 **발행(트리거) 로직이 없음**.
- **기관 연동**: `agency_meta`(68개 설계), `agency_endpoint_registry`(PROVISIONING/CAST_VERIFY/WEBHOOK/STATUS), `provisioning_outbox`(V15, at-least-once, 멱등, 재시도/DLQ), `gateway_inbound_audit`/`gateway_outbound_audit`(F-23/24 양방향).
- **기관-회원 매핑**: `inst_mbr_id_mapping`(qimUserId ↔ 기관 `mbrUuid`, Q-IM SP 수신), `user_profile.di_map`.
- **전환 시 CI 자동연결**: 기관 전환(conversion) 경로는 본인인증 후 **CI 기반 자동 연결**(2026-05-15 지침)을 이미 수행.
- **권한 멀티테넌트**: q-authz `(qim_user_id, agency_code, role_code)` — 단일 회원이 N개 기관 역할 보유. 병합 시 `qim_user_id` 재귀속으로 권한 통합 가능.
- **이벤트/배치 인프라**: 트랜잭셔널 아웃박스 + `outbox-relay-batch`(ShedLock + `FOR UPDATE SKIP LOCKED`, 7개 Job), Compacted `qim.user.snapshot`(캐시/동기화 재구성 가능).

### 1.3 핵심 격차 (Gap)

| # | 격차 | 근거 | 영향 |
|---|------|------|------|
| **G1** | **인증수단 간 동일인 미연결** — 소셜 가입은 `SHA-256(sub)`로 **별도 `qimUserId`** 생성, CI 정본과 미조정 | `KeycloakOidcService.resolveQimUserIdFromSub` (소셜 등록 시 CI 조회 없음) | 동일인 다중 계정 → 기관별 식별 분산 |
| **G2** | **자동 병합 트리거 부재** — 소셜 사용자가 후에 실명인증해도 자동 병합 없음 | `NiceAuthService` 등록 경로에 merge 호출 없음 | 고아 계정 누적, 수동 개입 필요 |
| **G3** | **병합 오퍼레이션 미구현** — `USER_MERGED` 상수만 존재, 발행/재이행 로직 없음 | `UserEvent.TYPE_MERGED` 정의만 | 병합 후 권한/세션/핸드오프/기관 통지 미정합 |
| **G4** | **기관 회원 대량 통합 배치 부재** — full backfill Job 없음 | EDA 분석 문서 | 67개 기관 회원원장 미연결 |
| **G5** | **SHA-256 인코딩 불일치(추정)** — 일부 hex / 일부 Base64URL | `docs/analysis/sso-im-readiness/03_identity_mapping.md`(Critical), `UserController.computeSha256Hex`=hex | CI 기반 조회 실패 가능 → **선검증 필수** |
| **G6** | **감사 로그 평문 식별자** — `actor_id=qimUserId` 평문 | `05_privacy_kms_audit.md` | 가명정보 위험(PIPA) |
| **G7** | **운영 기관 seed 부재** — `AGENCY_STUB_001`만 등록 | agency_meta seed | 68개 기관 온보딩 선행 필요 |
| **G8** | **기관→플랫폼 인바운드가 log-only** — 게이트웨이 인바운드가 수신·감사·멱등까지 되나 라우팅이 로그만 남기고 **원장 미반영** | `AgencyGatewayServiceImpl.java:248-270` | 기관발 회원 변경이 통합원장에 반영 안 됨 → 데이터 루프 반쪽([01 §2.3]) |
| **G9** | **프로비저닝 `identity_hash` 신호가치 0** — 페이로드 매칭키가 **자기참조 해시**라 기관 원장과 매칭 불가 | `01-agency-heterogeneity-and-operability.md §2.3` | 기관이 수신해도 자기 회원과 연결 불가 → 재설계 필요 |

> **G8·G9는 `01-agency-heterogeneity-and-operability.md`(§2.3 회원 데이터 루프)에서 도출**했으며, 본 마스터 플랜 **Phase 3(기관 회원 대량 통합)의 선행 조건**이다 — [01번 문서](./01-agency-heterogeneity-and-operability.md) §2.3·§6.1 Track B 참조.

---

## 2. 목표 모델 (To-Be)

### 2.1 불변식 (Invariants)
1. **1인 1 `qimUserId`** — 동일 자연인은 전 기관·전 인증수단에 걸쳐 정확히 하나의 마스터 `qimUserId`로 수렴한다.
2. **CI = 결정론적 앵커** — CI가 확보된 두 레코드가 같으면 반드시 동일인이며 자동 병합한다. CI가 다르면 자동 병합하지 않는다.
3. **불가역 식별자 비노출** — CI 평문/원본은 FE·기관·Kafka에 절대 전파하지 않는다(암호화 저장, 해시/내부 DI만 외부화).
4. **병합은 단방향·감사가능** — `from → into` 단방향, append-only 감사, 되돌리기는 명시적 보상 트랜잭션으로만.

### 2.2 통합 식별 그래프

```text
                        ┌──────────────────────────────┐
                        │  qimUserId (MASTER, golden)  │
                        └───┬─────────────┬────────────┘
        인증수단(N)         │             │        기관 회원(N)
   ┌────────────────┐       │             │     ┌───────────────────────────┐
   │ auth_mean_mapping│◀────┘             └────▶│ inst_mbr_id_mapping        │
   │  (sub/CI hash)  │                          │  (agencyCode, mbrUuid)     │
   └────────────────┘                          │ + di_map{agency:DI}        │
   CI(user_profile.ci, 암호화) ── 결정론적 앵커  └───────────────────────────┘
```

- **마스터 레코드(golden record)** = `qim_user` + `user_profile`.
- **인증수단 N개** = `auth_mean_mapping`(소셜/PASS/금융인증서 …).
- **기관 회원 N개** = `inst_mbr_id_mapping`(+ `di_map`).
- **권한 N개** = q-authz `authz_user_role` (agency별).

### 2.3 통합 후 동작
- 어느 인증수단으로 로그인하든 같은 마스터 `qimUserId` 도출.
- 기관 핸드오프 `agencySubjectId = DI(agency, MASTER)` — 기관 간 동일인 일관.
- 권한·세션·감사·기관통지가 마스터 기준으로 일원화.

---

## 3. 식별 해소 전략 (Identity Resolution)

### 3.1 결정론적 1차 (Deterministic) — 채택
- **키**: `SHA-256(CI)` (인코딩 통일 후 — [§G5]). CI는 전국 유일 → exact match = 동일인.
- **적용 지점**: (a) 실명인증 완료 시, (b) 기관 전환/연동에서 CI 수신 시, (c) 기관 회원 백필에서 기관이 CI(또는 CI 해시)를 제공할 때.
- **행동**: 매칭되는 기존 마스터가 있으면 현재(예: 소셜) `qimUserId`를 마스터로 **병합**; 없으면 현재를 마스터로 승격.

### 3.2 보조 (Probabilistic) — 수동 검토 큐로만
- CI가 한쪽에만 있거나 양쪽 모두 없을 때(소셜 전용 사용자 등) 자동 병합 **금지**.
- 후보 생성: `{name_norm, birth_year, gender, mobile_hash}` 다중 신호 일치 → **검토 큐 적재**(자동 병합 아님). 운영자/사용자 확인 후에만 병합.
- 이유: 이름 변형·로마자 표기·동명이인 → 오결합 위험. 오결합은 타인 정보 노출 = 보안사고.

### 3.3 결정 트리

```text
신규/로그인 이벤트 (인증수단 X로 도착)
  │
  ├─ CI 확보됨?
  │     ├─ 예 → SHA-256(CI)로 마스터 조회
  │     │        ├─ 매칭 → 현재 qimUserId 를 마스터로 병합 (자동, 감사)
  │     │        └─ 없음 → 현재를 마스터로 승격 + CI 저장
  │     └─ 아니오(소셜 전용) → sub 기반 qimUserId 유지
  │                              + (이름/생년/연락처 후보 매칭 → 검토 큐, 자동병합 X)
  └─ 이후 실명인증 시 → CI 확보 → 위 결정론적 경로로 사후 병합
```

---

## 4. 단계별 통합 플랜

> 각 Phase는 독립 배포 가능하며, `FEATURE FLAG` + `DRY_RUN`으로 점진 적용한다.

### Phase 0 — 기반 정비 (Prerequisites) ★선행 필수
**목표**: 병합을 안전하게 만들기 위한 정합성·법규 토대.
- **P0-1 SHA-256 인코딩 통일** [G5]: 전 경로 `hex`로 통일(또는 단일 표준 확정) + CI 조회 성공 회귀 테스트. *플랜의 다른 모든 단계가 이 키 정합성에 의존하므로 최우선.*
- **P0-2 감사 식별자 가명화** [G6]: `audit_log.actor_id = SHA-256(qimUserId)` + 원본 매핑 분리·권한 격리.
- **P0-3 병합 토대 스키마**: `identity_merge_log`, `identity_link_review`(검토 큐), `inst_mbr_id_mapping` 확장([§5]).
- **P0-4 멱등·잠금**: 병합/백필용 멱등키 규약, `outbox-relay-batch` 패턴 재사용 확인.
- **산출물**: 정비 PR + 회귀 테스트, `docs/.../03_identity_mapping.md` 갱신.

### Phase 1 — 런타임 동일인 자동연결 [G1,G2]
**목표**: *새로 발생하는* 분산을 즉시 차단(미래 유입 봉쇄).
- **P1-1 소셜→CI 사후 연결**: 소셜 사용자가 실명인증(NICE/OACX)하면 `lookup-by-ci`로 마스터 조회 → 있으면 소셜 `qimUserId`를 마스터로 병합, 없으면 마스터 승격 + CI 부착.
- **P1-2 등록 경로 가드**: `KeycloakOidcService` 소셜 등록 시 가능한 한 CI 연결 시도(없으면 보류). `NiceAuthService` 등록 직전 CI 매칭 훅 삽입.
- **P1-3 FE 세션 리베이스**: 병합 시 `FeSession`/CAST의 `qimUserId`를 마스터로 교체(재로그인 없이 핸드셰이크).
- **플래그**: `IM_AUTOLINK_ENABLED`, `IM_AUTOLINK_DRY_RUN`(로그만).

### Phase 2 — 병합 오퍼레이션 정식화 [G3]
**목표**: 병합을 1급 도메인 연산으로 구현 + 연쇄 재이행.
- **P2-1 Merge API**: `POST /api/v1/internal/member/merge { fromQimUserId, intoQimUserId, reason, correlationId }` (멱등, 감사). 규칙: into=CI 보유/선등록, from=흡수.
- **P2-2 트랜잭셔널 재귀속**: `auth_mean_mapping`, `user_profile.di_map`, `inst_mbr_id_mapping`, `consent_record`, `biz_member` 등 `from→into` 이전(충돌 해결 규칙 [§6]).
- **P2-3 `USER_MERGED` 발행**: 아웃박스로 발행(파티션 키: `from` + dual-emit `into`). 다운스트림 재이행 트리거.
- **P2-4 표준 ADR**: `QIM-OUTBOX-SPEC-002`(병합 이벤트 계약) 제정.

### Phase 3 — 기관 회원 대량 통합 (Bulk) [G4,G7,G8,G9]
**목표**: 67개 기관 회원원장을 마스터에 연결(존재하는 회원들의 일괄 통합).
- **P3-0 선행 격차 해소 [G8,G9]**: **인바운드 라우팅 원장반영**(현재 log-only, `AgencyGatewayServiceImpl` 라우팅부) + **`identity_hash` 재설계**(자기참조 → 기관 매칭 가능 키). 두 격차는 [01번 문서](./01-agency-heterogeneity-and-operability.md) §2.3 Track B에서 상세. **P3 착수의 선결.**
- **P3-1 기관 온보딩**: `agency_meta` + `agency_endpoint_registry` 적재, API Key/엔드포인트 등록(파일럿 10개 → 전체).
- **P3-2 회원 데이터 수신**: 기관별 회원 키(우선순위: CI 또는 CI 해시 > 국가표준 DI > 기관 회원ID+속성). 채널: `gateway_inbound_audit` 실시간 또는 일괄 파일/배치.
- **P3-3 매칭·연결 배치**: `MemberConsolidationBackfillJob`(아웃박스/relay 패턴 재사용, `SKIP LOCKED`+ShedLock, DRY_RUN). 결정론 매칭→`inst_mbr_id_mapping` 적재 + 필요 시 마스터 생성/병합. 비매칭→검토 큐/게스트.
- **P3-4 역방향 프로비저닝**: 통합 결과를 `provisioning_outbox`로 기관에 통지(멱등, 재시도/DLQ). **[G9] 재설계된 매칭키 사용.**
- **순서**: 기관 위험도·규모 순 웨이브(파일럿→소규모→대규모), 각 웨이브 DRY_RUN→검증→실행.

### Phase 4 — 정합성·운영 (Reconcile & Operate)
- **P4-1 정합성 스윕**: 중복 CI/고아 계정/미연결 회원 주기 탐지(리포트).
- **P4-2 모니터링**: 매칭률·중복률·수동검토 적체·병합 실패 메트릭(Prometheus/Grafana).
- **P4-3 롤백 런북**: 병합 보상 트랜잭션·이벤트 역발행 절차.
- **P4-4 캐시 재구성**: `qim.user.snapshot` Compacted 토픽으로 다운스트림 상태 일괄 재구성.

---

## 5. 데이터 모델 변경

> Q-IM은 MariaDB, ido/q-authz는 PostgreSQL(분산 TX 없음 → 아웃박스+멱등 의존).

### 5.1 신규 `identity_merge_log` (q-im) — 병합 감사(불변)
| 컬럼 | 타입 | 설명 |
|------|------|------|
| `merge_id` | VARCHAR(36) PK | UUIDv7 |
| `from_qim_user_id` | VARCHAR(36) | 흡수되는 계정 |
| `into_qim_user_id` | VARCHAR(36) | 마스터 |
| `match_basis` | VARCHAR(30) | `CI_EXACT`/`MANUAL_REVIEW`/`AGENCY_KEY` |
| `moved_summary_json` | JSON | 이전된 매핑·동의·권한 건수 |
| `actor` / `reason` | VARCHAR | 수행자/사유 |
| `correlation_id` | VARCHAR(36) | 추적 |
| `created_at` | DATETIME(6) | UTC |

### 5.2 신규 `identity_link_review` (q-im) — 확률 매칭 검토 큐
| 컬럼 | 타입 | 설명 |
|------|------|------|
| `review_id` | VARCHAR(36) PK | |
| `candidate_a` / `candidate_b` | VARCHAR(36) | 후보 `qimUserId` 쌍 |
| `signals_json` | JSON | 일치 신호(이름/생년/성별/연락처 해시) |
| `score` | DECIMAL | 가중 점수 |
| `status` | VARCHAR(20) | `PENDING`/`MERGED`/`REJECTED` |
| `decided_by`/`decided_at` | | 검토 결과 |

### 5.3 `inst_mbr_id_mapping` 확장 — 기관 회원 연결 메타
- 추가: `link_basis`(CI_EXACT/DI/AGENCY_KEY/MANUAL), `linked_at`, `source_batch_id`, `status`(LINKED/UNLINKED).
- 유일성: `(agency_code, agency_member_id)` UNIQUE + `(qim_user_id, agency_code)` 조회 인덱스.

### 5.4 인덱스/제약
- `auth_mean_mapping`: 기존 `(identifier_hash, provider_code)` UNIQUE 유지.
- `user_profile.ci` 조회용 보조: `identifier_hash`(=SHA-256(CI)) 컬럼/인덱스 분리 검토(현재 매핑 테이블 경유).

---

## 6. 병합 연쇄 재이행 (Fan-out)

`USER_MERGED(from, into)` 1건이 유발하는 후속 처리:

```text
USER_MERGED(from→into)
  ├─ q-im      : auth_mean_mapping / di_map / inst_mbr_id_mapping / consent / biz_member 재귀속
  ├─ q-authz   : UPDATE authz_user_role SET qim_user_id=into WHERE qim_user_id=from
  │              + 충돌(같은 agency 다른 role) → 규칙 해소 → AuthorizationEvent.granted 재발행
  ├─ ido       : from 기준 Handoff Ticket 전부 REVOKED(보안) + FeSession/CAST 리베이스
  ├─ q-sign    : QimUserEventConsumer → from 계정 인증잠금 해제(이미 구현)
  ├─ session   : SessionAdvisoryEvent(보안 종료 권고) → 기관/세션 캐시 무효화
  └─ agency    : provisioning_outbox → 영향 기관에 "회원 통합/식별자 변경" 통지(멱등)
```

**충돌 해결 규칙(권한 등)**: 같은 `agency`에서 from/into가 상이 역할이면 — 기본 **상위 권한 보존**(또는 정책상 보수적 하향), 한시(JIT) 권한은 만료 짧은 쪽 우선, 모든 변경은 감사. 자동 판단이 모호하면 검토 큐.

**식별자 변경 통지**: 병합으로 `agencySubjectId(DI)`가 바뀌는 기관에는 **구→신 DI 매핑**을 통지(또는 DI를 `qimUserId` 비종속 키로 재설계할지 [§12]).

---

## 7. 기관별 회원 통합 런북 (per-agency runbook)

각 기관 웨이브마다 동일 절차 반복:

```text
① 온보딩      agency_meta + endpoint_registry 등록, API Key(K8s Secret), 콜백 화이트리스트
② 키 합의     기관 회원 매칭 키 결정: CI(해시) > 국가표준 DI > (회원ID + 본인확인 속성)
③ 수집        gateway_inbound(실시간) 또는 일괄 추출(배치) — PII 최소·해시 우선
④ DRY RUN     MemberConsolidationBackfillJob --dry-run → 매칭률/충돌/중복 리포트
⑤ 검증        리포트 검토, 임계 미달 시 키/속성 보정, 검토 큐 표본 점검
⑥ 실행        배치 실행 → inst_mbr_id_mapping 적재 + 필요 병합 + USER_MERGED 발행
⑦ 통지        provisioning_outbox → 기관에 결과/식별자 통지
⑧ 검증·컷오버  정합성 스윕 통과 후 해당 기관 "통합 완료" 표시
```

- **웨이브 순서**: 파일럿(스텁/협조 기관) → 소규모 → 대규모. 동시 1~2개 웨이브.
- **안전장치**: 모든 단계 멱등, DRY_RUN 기본, 기관별 즉시 비활성 플래그.

---

## 8. 개인정보·법적 준수

| 항목 | 요구 | 본 플랜 반영 |
|------|------|--------------|
| **본인확인기관 기준**(행안부 고시 2021-68 등) | CI/DI 취급 규정 | CI 암호화 저장, 원문 비전파, 내부 DI는 HMAC |
| **PIPA 최소수집** | 필요한 정보만 | 매칭은 해시/내부키 우선, 속성 최소 |
| **제3자 제공/연계 동의** | 기관 간 회원 연계 동의 | `consent_record`에 연계 동의 유형 추가, 병합/연결 전 동의 확인 |
| **가명정보** | 식별 위험 최소 | 감사 `actor_id` 해시화([P0-2]) |
| **보존·파기** | 법정 보존, 파기 | 병합 감사 장기보존, 탈퇴 시 GDPR 파기 흐름 연계 |
| **본인 통제권** | 열람·연결해제 | 병합/연결 이력 열람, 연결해제 절차([§12]) |

> **핵심 준수 포인트**: ① 기관 회원을 OnePass 회원과 연계하려면 **연계 동의 근거**가 선행되어야 한다(전환 동의 vs 일괄 통합 동의의 법적 차이는 [§12 결정]). ② CI 기반 매칭은 **본인확인기관 위탁 범위** 내에서만. ③ 대량 백필 전 **개인정보 영향평가(PIA)** 권고.

---

## 9. 매칭 알고리즘 상세

### 9.1 결정론 매칭 (자동 병합)
```
key = SHA-256_hex(normalize(CI))
master = SELECT qim_user_id FROM auth_mean_mapping
         WHERE identifier_hash = key AND provider_code IN ('PASS', 'CI') AND status='ACTIVE'
if master exists and master != current: MERGE(current → master, basis=CI_EXACT)
else: PROMOTE(current as master) + attach CI
```

### 9.2 확률 매칭 (검토 큐, 자동 병합 금지)
- 신호: `name_norm`(공백/표기 정규화), `birth_year`, `gender`, `mobile_hash`, 기관 회원 속성.
- 점수: 가중합(연락처 해시 일치 高가중). 임계 이상 → `identity_link_review(PENDING)` 적재.
- 결정: 운영자/사용자 확인 → `MERGE(basis=MANUAL_REVIEW)` 또는 `REJECTED`.

### 9.3 고아/충돌 처리
- **소셜 전용(CI 없음)**: 마스터 승격 보류 가능, 후속 실명인증까지 단독 유지.
- **CI 충돌**(같은 사람 추정인데 CI 상이): 자동 병합 금지 → 검토 큐 + 본인확인 재요청.
- **역병합 요청**: 보상 트랜잭션(merge_log 기반 복원) — 가능 범위/정책 [§12].

---

## 10. 리스크 & 완화

| 리스크 | 영향 | 완화 |
|--------|------|------|
| **오결합(false merge)** | 타인 정보 노출(보안사고) | 결정론 매칭만 자동, 나머지 수동; DRY_RUN; 감사·롤백 |
| **미결합(false split)** | 중복 계정 잔존 | 정합성 스윕·검토 큐·KPI 추적 |
| **DI 재생성 영향** | 기관 식별자 변경 | 구→신 DI 통지, 또는 DI 비종속 재설계([§12]) |
| **토큰/세션 정합** | 병합 중 권한 혼선 | Handoff 무효화 + 세션 종료 권고 + CAST 리베이스 |
| **대량 부하** | Kafka lag, 기관 API 한도 | 파티션 12, 웨이브·배치·백오프, virtual thread |
| **멀티 DB 정합**(MariaDB↔PG) | 분산 TX 부재 | 아웃박스+멱등키, 모듈 내 단일 TX 경계 |
| **법적 동의 부재** | 통합 위법 | 연계 동의 선행, PIA, 본인확인 위탁범위 준수 |
| **인코딩 불일치 잔존**([G5]) | 매칭 실패/오류 | P0 선행, 회귀 테스트 게이트 |

---

## 11. 마일스톤·KPI·산출물

### 11.1 마일스톤(증분)
- **M0 (Phase 0)**: 인코딩 통일 + 감사 가명화 + 병합 스키마. *게이트: CI 조회 회귀 100%.*
- **M1 (Phase 1)**: 런타임 자동연결(DRY_RUN→실가동). *게이트: 신규 분산 0.*
- **M2 (Phase 2)**: Merge API + `USER_MERGED` 재이행 + ADR. *게이트: 병합 후 권한/세션/기관 정합.*
- **M3 (Phase 3)**: 파일럿 10개 기관 백필 → 전체 웨이브. *게이트: 웨이브별 매칭률·중복률 임계.*
- **M4 (Phase 4)**: 정합성 스윕·모니터링·롤백 런북 상시화.

### 11.2 KPI
- 동일인 중복률(↓), 결정론 매칭률(↑), 수동검토 적체(↓), 병합 실패율(↓), 기관 통합 완료율(↑), 회수/식별자변경 전파 지연(↓).

### 11.3 산출물(문서)
- 본 마스터 플랜(이 문서) + 하위:
  - `QIM-OUTBOX-SPEC-002`(병합 이벤트 계약)
  - 백필 Job 설계서 + 운영 런북
  - PIA(개인정보 영향평가) / 연계 동의 문안
  - 기관 온보딩 체크리스트

---

## 12. 열린 의사결정 항목 (Decisions Needed)

> 플랜 확정 전 비즈/법무/아키텍처 합의가 필요한 항목. **이 결정들이 설계 분기를 바꾼다.**

1. **연계 동의 모델**: 기관 회원 일괄 통합을 *옵트인(사용자 전환 동의)* 으로 할지, *근거 기반 일괄 연계 + 사후 고지/옵트아웃* 으로 할지 — 법적 근거·UX가 크게 달라짐.
2. **CI 매칭 권한 범위**: 본인확인기관 위탁범위에서 CI 해시 기반 대량 매칭이 허용되는지(법무 확인). 불가 시 국가표준 DI 또는 기관 제공 키로 대체.
3. **DI 재설계 여부**: 현재 `DI=HMAC(agency:qimUserId)`는 `qimUserId` 종속 → 병합 시 변경. `DI=HMAC(agency:CI)` 등 **불변 키 기반**으로 바꿔 병합 영향 제거할지.
4. **소셜 전용(CI 미보유) 사용자**: 영구 단독 유지 vs 실명인증 유도 정책.
5. **역병합(되돌리기)** 허용 범위/정책.
6. **기관 회원 수신 채널 표준**: 실시간(gateway_inbound) vs 일괄 추출 — 기관별 역량 차이 수용 방식.
7. **마스터 선택 규칙**: 두 실명 계정(상이 CI는 아님) 충돌 시 정본 선택 우선순위.

---

### 부록 A. 근거 코드/문서 (선별)
- 식별: `q-im .../crypto/CiCryptoServiceImpl`, `.../identity/DiGenerationService`, `.../api/MemberLookupController`, `.../api/UserController(find-by-social-sub/register-social)`
- 연동: `ido .../broker/keycloak/KeycloakOidcService`, `.../policy/PolicyEngineImpl`, `.../infrastructure/QimClientImpl`, `auth/service/NiceAuthService`
- 스키마: `q-im V1·V5·V6`(qim_user/user_profile/auth_mean_mapping/conversion_session/biz_member), `ido V1·V4·V15·V16`(agency_meta/inst_mbr_id_mapping/provisioning_outbox/gateway_*audit)
- 이벤트: `platform-common .../event/UserEvent(TYPE_MERGED)`, `qsign .../kafka/QimUserEventConsumer`
- 분석: `docs/analysis/sso-im-readiness/03_identity_mapping.md`, `05_privacy_kms_audit.md`, `wiki/iam/05-ci-dn-brokering.md`, `wiki/walkthrough/03-member-conversion-walkthrough.md`

> **주의(검증 필요)**: 일부 사실(특히 [G5] SHA-256 인코딩 불일치, DI의 국가표준/내부 구분)은 조사 보고 간 상이가 있어 **Phase 0 착수 시 코드 재확인**을 전제로 한다.
