# 02 · Architecture Deep Dive — 아키텍처 심층 재구성

> 스냅샷 기준: 2026-06 · HEAD `4f113e8`
> 목적: SoR 축·모듈 군·데이터 흐름·ADR 계보를 **관찰된 코드/문서 그대로** 재구성한다. 정본 스펙(03a~f, 07, 09)은 참조만 하고, 결여(q-authz)는 소스로 보완한다.

---

## 1. 통합 아키텍처 원칙 (Six Pillars)

정본 스펙(`02-architecture.md`) + ADR 계보(001~008) + q-authz 신설(PR #205) 을 통합하면 아키텍처는 **6개 원칙**으로 요약된다.

| # | 원칙 | 정본 근거 | 관찰 |
|---|---|---|---|
| P1 | **SoR 분리 (Single Source of Record)** | ADR-001, spec 02, 03a | 회원=Q-IM, 인증=Q-Sign, 통합자=IdO, **인가=q-authz(신규)** |
| P2 | **DMZ Orchestration** | spec 03d | IdO 가 P/E/G/T 4역할(Portal/External/Gateway/Token) 겸직 |
| P3 | **Single Channel FE ↔ IdO** | **ADR-008** (5fff02a) | onepass-fe 는 IdO 이외로 직접 호출 금지 |
| P4 | **Transactional Outbox → Kafka** | ADR-003, spec 06 | qsign / qim / ido / authz(예정) 각자 Outbox 테이블 보유 |
| P5 | **부여는 중앙, 해석은 지역** | q-authz Javadoc (QAuthzApplication) | 역할 부여는 q-authz, `role_code` 의미는 각 기관이 해석 |
| P6 | **자체 SSO 기관 통합** | ADR-2026-004 | Keycloak Identity Brokering + 4개 통합 대안 |

---

## 2. 축 모델 확장 — 4+1 → **5+1**

### 2.1 정본 축 모델 (spec 02)
```
축 1: 회원 (Q-IM)                — MariaDB SoR
축 2: 인증 (Q-Sign)               — Postgres, OIDC 브로커
축 3: 통합·중개 (IdO)             — Postgres, P/E/G/T
축 4: 외부 접점 (agency-stub/SDK) — 유관기관 시뮬레이터·SDK
+1: 공통 계약 (platform-common)   — Cross-cut
```

### 2.2 관찰된 신 축 (PR #205 이후)
```
축 5 (신설): 인가 (q-authz)       — Postgres RLS, 역할 부여 SoR
             ├─ L1 코어: 역할 CRUD / 사용자-역할 부여
             ├─ L2: SCIM 2.0 Groups
             └─ 배치: JIT 만료 스케줄러 (@Scheduled fixedDelay 60s)
```

**결정적 근거**:
- `q-authz/src/main/java/kr/go/smes/authz/QAuthzApplication.java` Javadoc:
  > "부여는 중앙, 해석은 지역 — role_code 는 기관별 불투명 문자열이며, IdO/기관은 해석 책임을 진다."
- `V1__create_authz_schema.sql`: 3 테이블(`authz_role`, `authz_user_role`, `authz_grant_audit`) + RLS 정책 3개.
- `settings.gradle.kts`:
  > `"q-authz",             // 연합 인가(Federated Authorization) — 기관별 역할/권한 부여 SoR (L1 코어)`

### 2.3 축 간 관계 다이어그램 (재구성)

```
                                ┌────────────────────────┐
                                │   onepass-fe (SPA)     │
                                │   Single Channel (P3)  │
                                └───────────┬────────────┘
                                            │  HTTPS + Cookie(fe-session-id)
                                            ▼
       ┌────────────────────────────────────────────────────────────────┐
       │                        IdO (축 3, P/E/G/T)                     │
       │  ┌────────┐ ┌───────────┐ ┌──────────┐ ┌───────────────────┐  │
       │  │Portal  │ │ /api/ext  │ │ Gateway  │ │ Token (Handoff /  │  │
       │  │(FE 앞) │ │(PEP: ExtP)│ │(기관→IdO)│ │  Cast / SSO)       │  │
       │  └───┬────┘ └────┬──────┘ └────┬─────┘ └─────────┬─────────┘  │
       └──────┼───────────┼─────────────┼─────────────────┼────────────┘
              │           │             │                 │
              ▼           ▼             ▼                 ▼
     ┌──────────┐  ┌──────────┐  ┌──────────┐   ┌────────────────┐
     │ Q-Sign   │  │ Q-IM     │  │ q-authz  │   │ 외부 기관 (SP) │
     │ (축 2)   │  │ (축 1)   │  │ (축 5)★  │   │ (축 4)         │
     │OIDC 브로 │  │회원 SoR  │  │역할 SoR  │   │ Handoff 검증   │
     └────┬─────┘  └────┬─────┘  └────┬─────┘   └────────────────┘
          │             │             │
          ▼             ▼             ▼
    ┌────────────────────────────────────────┐
    │  Kafka (12 토픽) + Outbox Relay Batch  │  ← 축 +1 (공통 이벤트 백본)
    └────────────────────────────────────────┘
          ▲
          │
      platform-common (계약: AuthResult, HandoffPayload, ErrorCode)
```

**관찰 A (§00_INDEX 참조)**: 정본 스펙 `02-architecture.md` 는 축 5(q-authz) 미반영. → 스펙 갱신 PR 필요.

---

## 3. IdO 4역할 (P/E/G/T) — 정본 정리

`docs/internal/spec/03d-module-ido.md` + 코드 관찰:

| 역할 | 코드 진입점 (컨트롤러) | 대상 | 프로토콜 |
|---|---|---|---|
| **P (Portal)** | `HandoffController`, `FeSessionController`, `AuthController`, `CrossAgencySsoController` | onepass-fe → IdO | 쿠키(fe-session-id) + JSON |
| **E (External)** | **`ExtProxyController` `/api/ext/**`** | onepass-fe → 기관 API (프록시) | JSON + X-Authz-* 헤더 재주입 |
| **G (Gateway)** | `AgencyGatewayController`, `QimSpReceiverController` | 기관 → IdO (inbound) | HMAC 서명, 감사 로그 |
| **T (Token)** | `HandoffServiceImpl`, `CastTokenServiceImpl`, `BrokerController`, `OidcCompleteController` | 토큰 발급/검증 | JWT (Handoff, Cast) + OIDC state |

**핵심 안티스푸핑 (관찰 B/C)**:
- `ExtProxyController` 는 요청 헤더 중 `SPOOFABLE_AUTHZ_HEADERS = { X-Authz-User, X-Authz-Roles, X-Authz-Scope }` 를 **제거 후 서버가 재주입**한다.
- 재주입 값의 원천은 `QAuthzClient.getEffectiveRoles(qimUserId, "PLATFORM", tenantAgencyCode)` 결과.
- `AUTHZ_SCOPE = "PLATFORM"` 은 상수. 기관 스코프는 향후 확장 예약(관찰).

---

## 4. ADR 계보 (연대순 재구성)

| ADR | 결정 요지 | 상태 | 이 분석의 코드 검증 |
|---|---|---|---|
| ADR-001 | DB 분리: MariaDB(qim) + Postgres(qsign/ido) | 정착 | ✔ compose 파일 실측 |
| ADR-002 | Q-Sign OIDC 브로커화 | 정착 | ✔ `q-sign/keycloak/` 존재 |
| ADR-003 | Transactional Outbox 채택 | 정착 | ✔ `q-{sign,im}/outbox/` + IdO `provision/*Outbox*` + `outbox-relay-batch` |
| ADR-004 | React SPA 전환 | 정착 | ✔ `onepass-fe/frontend/` |
| ADR-005 | Handoff 4개 전략(DIRECT/BRIDGE/INTERNAL_SSO/APACHE_GATE) | 정착 | ✔ `ido/handoff/strategy/` |
| ADR-006 | 감사 로그·이벤트 표준(6-필드 DLQ) | 정착 | ✔ spec 06 §DLQ |
| ADR-007 | 기관 온보딩 SDK/Stub 분리 | 정착 | ✔ agency-stub + onepass-agency-sdk |
| **ADR-008** | **FE 군 ↔ IdO 단일 채널 헌법화** | **정착 (PR #202, 2026-06)** | ✔ ExtProxyController, ADR-008 문서 (5fff02a) |
| ADR-2026-004 | 자체 SSO 기관 통합 = Keycloak Identity Brokering | PROPOSED | 문서만, 코드 미착수 |
| ADR-2026-005 | outbox-scheduler 독립 모듈 | PROPOSED | 문서만 |
| (암묵) ADR-2026-006 | **연합 인가 SoR (q-authz)** | ★신설되었으나 ADR 미작성 | 코드 정착, ADR 결여 (관찰) |

**관찰 (§00_INDEX C)**: q-authz 는 **ADR 없이 코드 먼저 착지**한 케이스 → 사후 ADR 작성 필요. 헌법(ADR-008)-정본 흐름을 따르지 않은 유일 사례.

---

## 5. 데이터 계층 아키텍처

### 5.1 DB 인스턴스 4종
```
PostgreSQL 16 (5432)
 ├─ qsign     (schema)     — Q-Sign 인증 로그·OIDC 세션 (V1~V5)
 ├─ ido       (schema)     — IdO 감사·Outbox·CastToken·SessionLink (V1~V19)
 ├─ agency    (schema)     — agency-stub 시뮬레이터 데이터 (V1~V2)
 ├─ authz     (schema) ★   — 역할 부여·감사 (V1) + RLS
 ├─ support   (schema)     — onepass-support Q&A/FAQ/CS (V1~V3)
 └─ keycloak  (DB)         — Keycloak IdP 저장소

MariaDB 11 (3306)
 └─ qim       (DB)         — Q-IM 회원 SoR (V1~V7)

Redis 7 (6379)             — 세션/캐시/락 (Redisson) — 스키마리스
```

### 5.2 RLS (Row-Level Security)
- **q-authz 만** RLS 사용. `V1__create_authz_schema.sql` 에 3 정책 정의.
- 정책 파라미터: `authz_role.agency_code`, `authz_user_role.agency_code`, `authz_grant_audit.agency_code` 를 `current_setting('authz.agency_code')` 와 비교.
- 세션 컨텍스트: JPA 트랜잭션 인터셉터에서 `SET LOCAL authz.agency_code = :code` 를 SET (기대되는 흐름 — 코드 확인 필요).

### 5.3 Outbox 원천 5개
| 원천 | 위치 | 릴레이 |
|---|---|---|
| Q-Sign Outbox | `q-sign/src/main/java/.../outbox/` | outbox-relay-batch/job/qsign |
| Q-IM Outbox | `q-im/src/main/java/.../outbox/` | outbox-relay-batch/job/qim |
| IdO Provisioning Outbox | `ido/.../provision/ProvisioningOutbox*` | outbox-relay-batch/job/ido |
| IdO Webhook Outbox | (F-14 플래그) | 동일 |
| q-authz Outbox | ★향후 필요 (grant/revoke 이벤트) | 미착수 |

---

## 6. 이벤트 백본 (Kafka 12 토픽)

정본 `06-kafka-event-catalog.md` 기준:
```
qsign.auth.events           (+ .dlq)      Q-Sign → IdO
ido.handoff.events          (+ .dlq)      IdO → 감사/알림
platform.session.advisory   (+ .dlq)      SSO/SLO 조언
platform.audit.log          (+ .dlq)      전 계열 → 감사 스토리지
qim.user.events             (+ .dlq)      Q-IM → SP
qim.user.snapshot           (+ .dlq)      Q-IM 스냅샷
qim.sp.member.events        (+ .dlq)      SP → Q-IM 역방향
```
= 7개 기본 × (본체 + DLQ) = **12 토픽 상당** (실제 세부는 §14 참조).

**컨슈머 그룹 6개**: `ido-handoff-cg`, `ido-qim-cg`, `ido-qsign-cg`, `qim-sp-cg`, `audit-cg`, `advisory-cg`.

---

## 7. FE ↔ IdO 단일 채널 헌법 (ADR-008)

**3단 명제** (PR #202 `5fff02a`):
1. **책임 종류** — FE 는 사용자 표시(UI) 만, 서버 상태·인가·감사는 IdO.
2. **모듈 군** — onepass-fe 는 다른 백엔드(Q-Sign/Q-IM/기관)로 직접 호출 금지.
3. **단일 게이트웨이** — 모든 데이터·부작용 트랜잭션은 `IdO` 를 경유. 특히 `/api/ext/**` 는 IdO 가 프록시.

**Phase 2 rename (PR #203/#204)**: FE 코드 내 `BE_*` 심볼 → `IDO_*` 로 전량 치환. 이유: "BE"는 다중 백엔드 착시 유발.

**anti-spoofing 정책** (관찰):
- FE가 `X-Authz-User/Roles/Scope` 헤더를 위조 주입 → IdO가 forward 전 제거 → q-authz 조회 결과로 재주입.
- 즉, "FE 는 서버에게 '내가 누구다'를 주장할 수 없다" — 서버가 세션(fe-session-id)로 신원 판단.

---

## 8. 인가 파이프라인 (신설, 관찰 C 심층)

**3중 주입점** — 하나의 사실(role) 이 세 경로로 확산:

```
  q-authz.effectiveRoleCodes(qimUserId, "PLATFORM", agency)
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
  ① CastToken   ② HandoffPayload  ③ X-Authz-* 헤더
   (JWT claim)   (내부 페이로드)   (FE 프록시 상류)
  CLAIM_ROLES   payload.roles     req header re-inject
   (108라인)     (340~360라인)     (ExtProxyController)
```

**결정적 관찰**:
- 3개 주입점 모두 **원천이 `QAuthzClient` 단 하나** → SoR 원칙(P1) 준수.
- IdO가 q-authz 조회 실패 시 `fail-open` (빈 배열 반환) — 가용성 우선. 반대 정책(fail-closed)은 §13 리스크로 후속.

---

## 9. Handoff 4개 전략 (ADR-005 재검증)

`ido/handoff/strategy/` 하위:
| 전략 | 대상 시나리오 | 페이로드 암호화 |
|---|---|---|
| **DIRECT** | 표준 유관기관 (SDK/Stub 사용) | AES-256-GCM |
| **BRIDGE** | 기관 프론트가 IdO를 직접 개입 (SPA↔IdO↔SP) | AES-256-GCM + HMAC |
| **INTERNAL_SSO** | 기관 내부 SSO 보유 (Keycloak Brokering 대안) | 향후 확장 (ADR-2026-004) |
| **APACHE_GATE** | Apache 앞단 검증 (라이센스 서버 등 레거시) | mod_auth 스타일 |

각 전략은 `payload.put("roles", ...)` 를 공통 통과 (증분 2, `2329200`).

---

## 10. 관찰 정리 (§00_INDEX 관찰 A/B/C/D 근거)

| 관찰 | 이 문서의 근거 §참조 |
|---|---|
| A. 축 4+1 → 5+1 미반영 | §2.2, §2.3, §4 (ADR-2026-006 결여) |
| B. ADR-008 코드 완결 + fallback 잔재 | §3, §7 (ExtProxyController 재주입) |
| C. 3중 주입점 파이프라인 확장 | §8 (Cast/Handoff/PEP 3 경로) |
| D. 정본 문서 커밋 시점 파편화 | §4 (03g 결여, wiki/adr 병존) |

---

## 11. 다음 문서 링크

- 이 축 5 심층 → `06_module_qauthz.md`
- IdO P/E/G/T 심층 → `03_module_ido.md`
- 데이터 흐름 재검증 → `12_data_flows_verified.md`
- 보안 표면 → `13_security_surface.md`
- 로드맵 → `17_gaps_debts_roadmap.md`
