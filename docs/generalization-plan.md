# Idem 범용화 리팩토링 플랜 — 구조 분석과 단계별 실행 계획

> 작성 2026-09-10 · 기준 `shipster` 4eb5704 · 상태: 초안 v0.1
>
> 목표: **어느 운영기관이든 설치할 수 있고, 어떤 연동기관의 요구도 코드 수정 없이(설정) 또는 플러그인으로 수용하는 구조**로 Idem 을 재편한다.
> 인증(CC·GS) 실행 계획 [`execution-plan.md`](execution-plan.md) 과 벤더 분리 [`vendor-plugin-plan.md`](vendor-plugin-plan.md) 의 **구조적 전제**가 되는 문서다.

---

## 0. 요약

**범용성의 두 축**

| 축 | 뜻 | 지금 |
|---|---|---|
| A. 운영기관 설치 가능성 | 중소벤처기업부가 아닌 다른 기관·기업이 자기 환경에 설치·운영 | ❌ 고객 고유값(이름·코드·회원 유형·도메인)이 코어에 박혀 있음 |
| B. 연동기관 수용성 | 붙는 기관(테넌트)마다 다른 프로토콜·속성·정책·보안 요구를 코드 수정 없이 수용 | 🟡 `agency_meta` 12컬럼 + 문자열 `integration_type` 4종. 정책·속성·IdP 선택·세션 정책은 전역 고정 |

**결합 지점 7가지** (§1 상세): 고객 고유값 · 벤더 코드 · 경직된 기관 모델 · 한국 고유 식별자(CI/DI) · 독자 프로토콜 편중 · 관리 면 부재 · SMES 특화 회원 전환.

**목표 구조** (§2): *Core(프로토콜·벤더·고객 중립) + Tenant Profile(기관별 선언적 설정) + Edition Plugins(KR 공공 등) + Admin Console*.

**단계** (§3) — 앞 단계가 뒤 단계의 토대. 각 단계는 독립 PR, 동작 변화 없음(또는 호환 유지)이 원칙.

| 단계 | 내용 | 위험 | 규모 |
|---|---|---|---|
| **S1** | 매직 문자열 타입화 + 고객 고유 기본값 외부화 | 낮음 | 1주 |
| **S2** | Tenant Profile 도입 (버전 있는 선언적 기관 설정) | 중 | 2~3주 |
| **S3** | 정책 엔진 규칙화 (PolicyRule SPI, 기관별 규칙, 인증수준 어휘 통일) | 중 | 2주 |
| **S4** | 식별자·속성 계약 (SubjectIdentifier 스킴, AttributeCatalog, 기관별 매핑) | 중~높음 | 3주 |
| **S5** | 벤더 엔드포인트 SPI 완전 이관 (`/auth/nice/*`·`/auth/oacx/*`·AnyID → `/auth/providers/{code}`) | 중 | 2~3주 (+OACX SDK) |
| **S6** | 프로토콜 확장 (표준 OIDC RP 파사드, Agent 를 프로토콜로, SAML 준비) | 높음 | 4주 |
| **S7** | 관리 콘솔 + 관리자 인증 (기관 CRUD·프로파일 편집·감사 조회) | 중 | 4~6주 |
| **S8** | 회원 모델 일반화 (SMES 회원 유형·사업자·후견을 에디션 확장으로) | 높음 | 4주 |
| **S9** | 에디션 패키징·온보딩 가이드·요구사항 수용 체크리스트 | 낮음 | 2주 |

의존: S1 → S2 → S3 → S4 → (S5 ∥ S6) → S7 → S8 → S9. S7 은 `execution-plan.md` P1 의 관리자 인증과 같은 작업이다.

---

## 1. 현재 구조 분석

### 1.1 모듈 지도

| 모듈 | 역할 | 크기·비고 |
|---|---|---|
| `idem-hub` (8083) | 정책 오케스트레이터 + FE BFF + Handoff + 브로커 + 웹훅 | **26개 패키지**. `auth` 38 · `broker` 29 · `infrastructure` 21 · `gateway` 14 · `qim` 12 · `config` 12 · `handoff` 11 · `provision` 9 · `fe` 9 · `crypto` 8 · `conversion` 7 · `api` 7 … 사실상 모놀리스 |
| `idem-gate` (8081) | 인증 결과 SoR, Keycloak 콜백, PKCE, 잠금 | 9 패키지 |
| `idem-registry` (8082) | 회원·CI/DI·동의·전환·후견·탈퇴 (MariaDB) | `identity guardian user conversion crypto withdrawal biz consent` — **SMES 회원통합 도메인 그대로** |
| `idem-authz` (8086) | 연합 역할·SCIM v2 Groups | 표준 지향(SCIM) — 좋은 예 |
| `idem-relay` (8090) | Outbox 릴레이·웹훅 재전송 | 3개 DB 접속 |
| `idem-console` | **최종 사용자 포털**(Login·SignUp·Mypage·ConversionSteps·OacxTest) | SigNoz 스캐폴딩 잔재. **관리 콘솔이 아님** |
| `idem-sdk-java` / `idem-agent` / `idem-tenant-sample` | 기관 측 배포물·Java Agent(APACHE_GATE)·샘플 | 기관 연동 3형태의 실물 |
| `plugins/` | mock-auth, nice-oacx(골격) | SPI 분리 시작 |

### 1.2 결합 지점 (증거)

**C1. 고객(중소벤처기업부·중기원패스) 고유값이 코어에 있음** — main 소스·설정 파일 수(테스트·docs 제외)

| 값 | 파일 수 | 대표 위치 |
|---|---|---|
| `onepass` (DB명·계정·Keycloak realm 기본값) | 113 | `idem-*/application.yml` `DB_NAME:onepass`, `KEYCLOAK_REALM:onepass`, `KeycloakProperties.realm="onepass"` |
| `smes.go.kr` 호스트 | 22 | 콜백·리다이렉트·CORS 기본값 |
| `중소벤처`·`기업마당` | 30·10 | `AnyIdProperties.agencyName="중소벤처24기업마당"` |
| AnyID `srvcNo/agencyCode=1000001157` | 12 | `AnyIdProperties`, `application.yml` |
| `mbrDvsnCd A101/A102` (개인/기업 회원구분) | 10 | `CiCheckRequest @Pattern("^A10[12]$")`, `CiTokenExchangeRequest` |
| `BRNO`/사업자 | 28 | `idem-registry biz/*`, `BizMemberJpaEntity` |
| `중기원패스` | 33 | 문구·로그·화면 |
| V13 기관 시드 | 1 | `V13__seed_agency_pattern_scenarios.sql` (PoC 기관 5종) |

**C2. 벤더 코드가 코어 컨트롤러에 노출** — `AuthController` 의 8개 엔드포인트 중 5개가 벤더 경로(`/nice/phone/url`, `/nice/phone/result`, `/nice/ci-check`, `/oacx/access-info`, `/oacx/easysign`), `broker/anyid` 29파일 중 8개 엔드포인트, `BrokerController /kakao/authorize`, `idp-hint-mapping: kakao/naver` 기본값, `NiceCryptoUtil` 코어 위치. SPI(`IdentityVerificationProvider` + `/api/v1/auth/providers/{code}/initiate|complete`) 는 있으나 **구 경로가 병존**.

**C3. 기관 연동 모델이 경직** —
- `agency_meta` 12컬럼(`agency_code official_name min_auth_level policy_version api_key_hash callback_whitelist allowed_attributes maintenance_windows integration_type bridge_endpoint sso_domain active`) + ALTER 5회로 증식. 새 요구가 올 때마다 컬럼 추가.
- `integration_type` 이 **문자열 4종**(`DIRECT/APACHE_GATE/BRIDGE/INTERNAL_SSO`) 이고 `bridge_endpoint` 컬럼을 유형에 따라 다른 뜻으로 재사용(`AgencyMetaRepositoryImpl:54-88`). 미지 값은 `DIRECT` 로 조용히 폴백(`HandoffStrategyFactory:45`).
- 기관별로 고를 수 없는 것: 허용 IdP·인증방식, 속성 매핑(이름·형식), 세션 정책(TTL·동시), 동의 정책, 재인증 규칙, 감사 수준, 알림 채널, 브랜딩. 전부 전역 설정 또는 코드.
- `FeatureFlags` 20개가 **전역**. 기관별 on/off 불가.
- `PolicyEngineImpl` 규칙 3개(최소 인증수준 `ordinal` 비교, 점검시간대, 사용자 상태)가 코드에 고정. 속성 필터는 `allowed_attributes` 키 목록뿐이고 **속성 카탈로그(정의·형식·민감도)가 없음**.

**C4. 식별자 모델이 한국 고유** — `VerifiedIdentity.subjectKey` 로 추상화는 시작됐으나 registry 는 `ci`, `di_map`, `nationality_type`, `DiGenerationService`(HMAC) 중심. CI 가 없는 환경(해외·민간·이메일 기반)은 붙일 수 없음. DI(기관별 가명 식별자) 개념은 좋으나 OIDC pairwise `sub` 와 대응시키지 않음.

**C5. 프로토콜이 독자 방식에 편중** — Handoff 티켓(독자)·CAST(독자 JWT)·웹훅(HMAC)·Java Agent 가 1급, 표준은 Keycloak 브로커링(OIDC 소비)과 SCIM(authz)뿐. 기관이 "표준 OIDC RP 로 붙겠다"고 하면 Keycloak 을 직접 열어 줘야 하고 Idem 정책(최소 인증수준·속성 필터)이 우회됨. SAML 없음.

**C6. 관리 면 부재** — 관리자 인증·역할 없음(`execution-plan.md` P1), 기관 관리 API(`/api/v1/admin/agencies`) 무인증, 관리 UI 없음. 기관 온보딩은 SQL 시드 또는 무인증 API.

**C7. 회원 전환·조회 흐름이 SMES 특화** — `conversion` 7파일·`memberlookup`·`qim` 12파일이 "중기원패스 → 통합 회원 전환" 시나리오 전용. 다른 운영기관은 이 흐름이 필요 없거나 다른 형태.

### 1.3 유지할 강점

Outbox 패턴(릴레이·멱등·재시도) · `IdentityVerificationProvider` SPI · `KmsClient` SPI · `HandoffStrategy` 팩토리 · 기관 정책 버전·이력(`agency_policy_history`) · SLO 체인 · CAST 1회성·Ed25519 · 기관별 Rate Limit · 감사 카테고리 체계 · SCIM v2 · Testcontainers 통합 테스트(2026-09-08 복구).

---

## 2. 목표 구조

```
┌──────────────────────────────── Admin Console (S7) ────────────────────────────────┐
│  기관 온보딩 · Tenant Profile 편집(스키마 검증) · 정책 시뮬레이션 · 감사 조회 · 관리자 I&A │
└─────────────────────────────────────────────────────────────────────────────────────┘
┌──────────────────────────── Idem Core (프로토콜·벤더·고객 중립) ─────────────────────────┐
│  Tenant Profile (S2)   ─ 버전 있는 선언적 기관 설정, JSON Schema 검증, 이력             │
│  Policy Engine (S3)    ─ PolicyRule SPI, 기관별 규칙 집합, 인증수준 단일 어휘            │
│  Identity Contract(S4) ─ SubjectIdentifier 스킴, AttributeCatalog, 기관별 AttributeMapper │
│  Protocol SPI (S6)     ─ HANDOFF_DIRECT · HANDOFF_BRIDGE · AGENT · WEBHOOK · OIDC_RP · SAML_SP│
│  Provider SPI (S5)     ─ IdentityVerificationProvider · IdpBrokerAdapter (기존)          │
│  Crypto/KMS SPI        ─ CryptoProvider(CC P2) · KmsClient(기존)                         │
│  Audit Sink SPI        ─ DB · Kafka · 파일 폴백 (CC P1)                                  │
└─────────────────────────────────────────────────────────────────────────────────────┘
┌──────────── Edition Plugins ────────────┐   ┌──────────── Tenant-side ──────────────┐
│ KR Public: NICE/OACX · AnyID · CI/DI 스킴 │   │ idem-sdk-java · idem-agent · 샘플       │
│ · SMES 회원 유형(개인/기업/후견) · 전환 흐름 │   │ (프로토콜별 참조 구현)                   │
│ Core 기본: Mock 인증 · 이메일/전화 스킴    │   └──────────────────────────────────────┘
└─────────────────────────────────────────┘
```

### 2.1 Tenant Profile (S2) — 기관별 선언적 설정의 단일 원천

```yaml
schemaVersion: 1
tenant: { code: AGENCY_A, name: "…", status: ACTIVE }
protocol:                # S6 에서 확장
  type: HANDOFF_DIRECT   # HANDOFF_DIRECT | HANDOFF_BRIDGE | AGENT | WEBHOOK | OIDC_RP | SAML_SP
  endpoints: { callback: [...], bridge: "...", webhook: "..." }
  security: { apiKeyHashAlg: PBKDF2, mtls: { required: false }, ipAllowlist: [] }
identity:                # S4
  subjectScheme: PAIRWISE_HMAC   # 기관별 가명 식별자(현 DI). 대안: OIDC_SUB, EMAIL, PHONE
  attributes: [ { name: name_masked, required: true }, { name: mobile_masked }, … ]
  attributeMapping: { name_masked: "user.name" }   # 기관 측 필드명 매핑
policy:                  # S3
  minAuthLevel: L2
  allowedProviders: [ NICE, OACX_EASYSIGN ]
  reauth: { onLevelUp: true, maxAgeSeconds: 3600 }
  session: { idleMinutes: 30, absoluteMinutes: 480, concurrent: 1 }
  maintenance: [ { dayOfWeek: MON, start: "02:00", end: "04:00" } ]
  rules: [ { type: USER_STATUS, allow: [ACTIVE] }, { type: CUSTOM, ref: "…" } ]
limits: { tps: 200, daily: 1000000 }
audit:  { level: STANDARD }
ui:     { brandName: "…", logoUrl: "…", locale: ko }
```

- 저장: `agency_meta.profile JSONB` + `profile_schema_version`. 기존 12컬럼은 **읽기 호환 기간** 동안 프로파일로 합성해 읽고, 쓰기는 프로파일로만. 이력은 기존 `agency_policy_history` 재사용.
- 검증: JSON Schema(`tenant-profile.schema.json`) 를 저장소에 두고 Admin API·콘솔·마이그레이션이 같은 스키마로 검증.
- 기관 요구사항은 대부분 이 문서의 필드로 표현된다(§4).

### 2.2 프로토콜 SPI (S6)

`IntegrationProtocol` enum + `TenantProtocolHandler` SPI(`issue`, `deliver`, `verify`, `logout`). 현 `HandoffStrategy` 3종은 `HANDOFF_*`·`AGENT` 핸들러로 승격. `OIDC_RP` 는 Keycloak 에 기관별 client 를 **Idem 이 프로비저닝**하고 Idem 정책을 Keycloak authenticator/mapper 로 강제(표준 OIDC 로 붙어도 정책 우회 불가). `SAML_SP` 는 Keycloak SAML 로 같은 방식(후순위).

### 2.3 식별자·속성 계약 (S4)

`SubjectIdentifierScheme` SPI: `CI`(KR 플러그인) · `PAIRWISE_HMAC`(현 DI 일반화 = OIDC pairwise sub) · `EMAIL` · `PHONE` · `EXTERNAL_SUB`. registry 는 `subject_key` + `scheme` 컬럼으로 저장하고 `ci` 는 KR 스킴의 값이 된다. `AttributeCatalog`(이름·타입·민감도·마스킹 규칙·출처) 를 코어에 정의하고 기관은 카탈로그 부분집합 + 매핑만 선언.

### 2.4 에디션

- **Idem Core**: Mock 인증·이메일/전화 스킴·표준 프로토콜. 공개 저장소.
- **Idem KR Public Edition**: NICE/OACX·AnyID·CI/DI·SMES 회원 유형·전환 흐름·기관 시드. 사설 저장소(`vendor-plugin-plan.md` P5).
- 운영기관 고유값은 전부 **설치 시 입력**(Helm values·환경변수·Tenant Profile), 코드·마이그레이션 기본값에는 남기지 않는다.

---

## 3. 단계별 실행 계획

원칙: (1) 단계마다 PR 1개, 동작 변화 없음 또는 호환 유지 (2) 통합 테스트 통과가 게이트 (3) 4b 개명·CC P1 과 겹치는 작업은 한 번만 한다.

### S1 — 매직 문자열 타입화 + 고객 고유 기본값 외부화 (1주, 위험 낮음)

| 작업 | 파일 |
|---|---|
| `IntegrationType` enum(`DIRECT, BRIDGE, APACHE_GATE, INTERNAL_SSO`) 도입, 문자열 비교 제거, 미지 값 폴백 → **거부**(기동·API 400) | `domain/AgencyMeta`, `AgencyMetaRepositoryImpl`, `HandoffStrategy*`, `AgencyAdminService`, `AgencyCreateRequest`, `AgencyMetaJpaEntity` |
| `bridge_endpoint` 이중 의미 해소 — `apache_gate_endpoint` 컬럼 분리(마이그레이션 + 데이터 복사) | `V__split_bridge_endpoint.sql`, 위 파일 |
| 고객 고유 기본값 제거: `AnyIdProperties.srvcNo/agencyCode/agencyName`, `KeycloakProperties.realm`, `DB_NAME`, `idp-hint-mapping`, `smes.go.kr` 호스트 기본값 → **기본값 없음 + 부팅 검증**(에디션 설정에서 주입) | `AnyIdProperties`, `KeycloakProperties`(hub/gate), `application*.yml`, Helm values |
| `mbrDvsnCd` 검증 정규식을 설정(`idem.registry.member-division-codes`)으로 | `CiCheckRequest`, `CiTokenExchangeRequest`, 테스트 |
| V13 시드를 `application-kr-poc` 전용 Flyway location 으로 분리(코어 기동 시 미실행) | `db/migration/` → `db/seed-kr/`, `spring.flyway.locations` |
| 인벤토리 테스트: 코어 main 에 `smes.go.kr`·`1000001157`·`중소벤처` 문자열 0건 검사 | `ArchUnit` 또는 `GeneralizationGuardTest` |

완료 기준: 통합 35건·단위 전부 통과, 코어 소스 고객 문자열 0건, `IntegrationType` 외 문자열 비교 0건.

**진행 기록 (2026-09-10)** — S1 구현 PR:
- ✅ `IntegrationType` enum · 전략 팩토리 fail-fast(누락·중복 유형은 기동 거부, 미지 값 DIRECT 폴백 제거) · JPA `@Enumerated` · Admin API 미지 값 400(`E-IDO-111`)
- ✅ V20: `apache_gate_endpoint` 컬럼 신설·데이터 이동, `AgencyMetaRepositoryImpl` 의 컬럼 이중 의미 제거
- ✅ AnyID 운영기관 식별자(srvc-no·agency-code·agency-name) 코드·설정 기본값 제거 → 미설정 시 브로커 진입에서 503(`E-IDO-112`)
- ✅ `mbrDvsnCd` 허용 목록을 `ido.qim.member-division-codes` / `corporate-division-codes` 로 외부화(`MemberDivisionPolicy`, `@MemberDivisionCode`)
- ✅ `GeneralizationGuardTest` — 코어 6모듈 main 에 고객 토큰 0건을 CI 로 강제(허용 목록: 적용된 시드 V8·V13, AnyID 벤더 자산, registry `AgencyRegistry`)
- ✅ 부수 발견: `ido.fe.allowed-return-urls` 가 YAML 시퀀스라 `@Value List` 바인딩이 비어 **모든 returnUrl 이 거부되던 상태** → 쉼표 구분 스칼라로 전환, 빈 항목 무시
- ⏸ 항목 7(V13 시드 이동)은 **개명 5단계(DB 재구축)로 이월** — 이미 적용된 Flyway 이력을 옮기면 기존 DB 가 `validate` 에서 실패한다. 가드 허용 목록에 사유를 적어 둠

### S2 — Tenant Profile (2~3주)

`tenant-profile.schema.json` v1 · `TenantProfile` 도메인(record) · `agency_meta.profile JSONB` 마이그레이션(기존 컬럼 → 프로파일 합성 백필) · `TenantProfileService`(읽기: 프로파일 우선, 없으면 컬럼 합성; 쓰기: 프로파일) · Admin API `PUT /api/v1/admin/tenants/{code}/profile`(스키마 검증) · `AgencyMeta` 는 프로파일의 뷰로 재구성 · 이력은 `agency_policy_history` 에 스냅샷.
완료 기준: 기존 API·Handoff 동작 동일, 새 기관을 프로파일만으로 온보딩하는 통합 테스트.

### S3 — 정책 엔진 규칙화 (2주)

`PolicyRule` SPI(`evaluate(ctx) → Decision`) · 내장 규칙 `MIN_AUTH_LEVEL / USER_STATUS / MAINTENANCE / ALLOWED_PROVIDERS / REAUTH` · 기관 프로파일 `policy.rules` 로 조합 · `AuthLevel` 단일 어휘(`L1/L2/L3` 유지, `LOW/MEDIUM/HIGH` 제거, 플러그인 하드코딩 `L2` 를 SPI `level()` 로) · 정책 시뮬레이션 API(`POST /admin/tenants/{code}/policy/simulate`).
완료 기준: `PolicyEngineImpl` 의 하드코딩 규칙 0, 규칙별 단위 테스트, 시뮬레이션 통합 테스트.

### S4 — 식별자·속성 계약 (3주)

`SubjectIdentifierScheme` SPI + registry `subject_key/scheme` 컬럼(백필: `ci`→`CI` 스킴) · `PAIRWISE_HMAC` 스킴으로 `DiGenerationService` 일반화 · `AttributeCatalog`(코어 정의, 마스킹 규칙 포함) · 기관 `identity.attributes/attributeMapping` 으로 `HandoffPayload.attributes` 구성 · `allowed_attributes` 는 프로파일로 흡수.
완료 기준: CI 없는 스킴(EMAIL)으로 Mock 인증→Handoff 통합 테스트 통과.

### S5 — 벤더 엔드포인트 SPI 완전 이관 (2~3주, OACX SDK 재수령 필요)

`AuthController` 벤더 경로 5개 → `IdentityVerificationController` 경유로 대체(구 경로는 1 릴리스 deprecated 프록시) · `NiceCryptoUtil`·`AuthWebClientConfig` NICE 부분 → `idem-plugin-nice-oacx` · `broker/anyid` → `idem-plugin-anyid`(`vendor-plugin-plan.md` P3) · `BrokerController /kakao` 제거, `idp-hint-mapping` 을 프로파일 `allowedProviders` 로 · FE `useEzAuth` → `useAuthWidget`.
완료 기준: 코어 `idem-hub` 에 벤더 클래스 0, Mock 플러그인만으로 CI 통과.

### S6 — 프로토콜 확장 (4주)

`IntegrationProtocol` + `TenantProtocolHandler` SPI · 기존 3 전략 이식 · `OIDC_RP`: Keycloak client 프로비저닝 + 정책 강제 authenticator · Agent(`APACHE_GATE`) 를 프로토콜로 정식화 · `SAML_SP` 설계만.
완료 기준: tenant-sample 이 프로파일 `protocol.type` 변경만으로 4가지 방식 모두 통과.

### S7 — 관리 콘솔 + 관리자 인증 (4~6주, `execution-plan.md` P1 과 동일 작업)

관리자 I&A·RBAC(P1) 위에 기관 목록/온보딩/프로파일 편집(스키마 기반 폼)/정책 시뮬레이션/감사 조회. `idem-console` 의 SigNoz 잔재 정리 후 **관리 앱과 사용자 포털 분리**(`idem-console-admin`, `idem-portal`).

### S8 — 회원 모델 일반화 (4주, 위험 높음)

registry 의 `biz`·`guardian`·`mbrDvsnCd`·`conversion`·`memberlookup` 을 **KR 에디션 확장 모듈**로 이동(`idem-registry` 코어는 `user/identity/consent/withdrawal` 만). 확장 속성은 `extra_attributes JSONB` + 카탈로그. 전환 흐름은 에디션 기능 플래그.
완료 기준: 코어 registry 가 SMES 개념 없이 기동·테스트 통과, KR 에디션에서 기존 시나리오(S1~S9 라이프사이클) 통과.

### S9 — 에디션 패키징·온보딩 가이드 (2주)

Core/KR 이미지·Helm values 분리(`vendor-plugin-plan.md` P4) · 기관 온보딩 가이드(프로파일 작성 → 검증 → 시험 → 승인) · **요구사항 수용 체크리스트**(§4) · 설치 시 입력값 목록(운영기관 고유값 전부).

---

## 4. 기관 요구사항 수용 매트릭스

기관이 흔히 요구하는 것과, 목표 구조에서 어디로 흡수되는지. "설정" 이면 코드 수정 없이 수용.

| 요구 | 수용 방식 | 단계 |
|---|---|---|
| "우리는 OIDC 로 붙겠다" / "SAML 만 된다" | 프로파일 `protocol.type` | S6 |
| "레거시 WAS 라 코드 수정이 어렵다" | `AGENT`(idem-agent) | S6 |
| "본인인증은 NICE 만 / 간편인증도" | `policy.allowedProviders` | S3·S5 |
| "우리 서비스는 L3(전자서명) 필수" | `policy.minAuthLevel`, 재인증 규칙 | S3 |
| "이름은 마스킹 없이, 전화번호는 뒷자리만" | `identity.attributes` + 카탈로그 마스킹 규칙 | S4 |
| "우리 필드명은 `userNm`" | `identity.attributeMapping` | S4 |
| "CI 를 못 받는다 / 이메일로 식별" | `identity.subjectScheme` | S4 |
| "기관 사용자 식별자를 다른 기관과 공유하지 말 것" | `PAIRWISE_HMAC`(기본) | S4 |
| "세션 30분, 동시 1개" | `policy.session` | S3 |
| "점검 시간에는 차단" | `policy.maintenance` | 현재 |
| "콜백은 우리 도메인만, mTLS 필수, IP 제한" | `protocol.security` | S2 |
| "초당 50건 제한" | `limits` | 현재 |
| "감사 로그를 우리 SIEM 으로" | Audit Sink SPI + 웹훅 | CC P1 |
| "로고·기관명 표시" | `ui` | S2·S7 |
| "동의 문구·항목이 다르다" | `consent` 프로파일(S8 에서 카탈로그화) | S8 |
| "우리 기관 관리자가 직접 설정하고 싶다" | 콘솔 기관 관리자 역할 | S7 |
| "특수 규칙(예: 특정 회원 유형만)" | `policy.rules[type=CUSTOM]` → 플러그인 규칙 | S3 |

체크리스트에 없는 요구가 오면: 프로파일 스키마 확장(마이너 버전) → 코어 규칙/핸들러 추가 → 그래도 안 되면 에디션 플러그인. **코어 코드 분기는 마지막 수단.**

---

## 5. 호환·마이그레이션 원칙

- 스키마 변경은 항상 **추가 → 백필 → 이중 읽기 → 쓰기 전환 → 구 컬럼 제거(다음 릴리스)**.
- API 는 구 경로를 1 릴리스 동안 deprecated 프록시로 유지, 응답 헤더 `Deprecation` 명시.
- 프로파일 스키마는 `schemaVersion` 으로 마이그레이터 체인.
- 각 단계는 Testcontainers 통합 테스트에 "기존 시나리오 + 새 시나리오" 를 추가한 뒤 머지.
- 4b 개명(`agency→tenant`, `ido.*→idem.*`)은 S2 에서 새 API·프로파일 키에 **새 이름만** 쓰고, 구 이름은 호환 계층에만 남긴다.

## 6. 리스크

| 위험 | 대응 |
|---|---|
| S4·S8 이 registry 데이터 모델을 건드려 운영 데이터 이관 필요 | 백필 마이그레이션 + 이중 읽기, 이관 리허설을 Testcontainers 로 자동화 |
| OIDC_RP 에서 Idem 정책을 Keycloak 이 우회 | Keycloak client 는 Idem 만 프로비저닝, 정책 authenticator 필수, 직접 등록 금지 |
| 프로파일 스키마가 과도하게 커짐 | v1 은 §2.1 범위로 제한, 확장은 마이너 버전 + 마이그레이터 |
| 벤더 SDK 미수령으로 S5 검증 지연 | Mock 플러그인으로 코어 검증, 벤더 플러그인은 계약 테스트만 |
| CC P1(관리자 인증) 과 S7 의 중복 작업 | 같은 작업으로 취급, `execution-plan.md` P1 을 S7 로 링크 |
| 단계가 길어 shipster 에 미완 작업이 쌓임 | 단계마다 PR·머지, 기능 플래그로 미완 경로 격리 |

---

## 부록 — S1 작업 지시서 (착수용)

1. `idem-hub/.../domain/IntegrationType.java` enum 신설, `AgencyMeta.integrationType` 타입 변경, `AgencyMetaJpaEntity` 는 `@Enumerated(STRING)`.
2. `HandoffStrategy.getIntegrationType()` → enum, `HandoffStrategyFactory` 미지 값 `IllegalStateException`.
3. `V17__agency_meta_split_endpoints.sql`: `apache_gate_endpoint` 컬럼 추가, `integration_type='APACHE_GATE'` 행의 `bridge_endpoint` 복사, `CHECK (integration_type IN (...))`.
4. `AgencyAdminService`/`AgencyCreateRequest`: enum 검증, 400 응답.
5. 고객 기본값 제거: `AnyIdProperties`(srvcNo·agencyCode·agencyName 기본값 삭제 + `@PostConstruct` 필수 검증, 플러그인 활성 시에만), `KeycloakProperties.realm` 기본값 삭제(hub·gate), `application.yml` 의 `DB_NAME:onepass`·`KEYCLOAK_REALM:onepass`·`smes.go.kr` 기본값 → placeholder 없는 `${…}` 로, Helm `values-*.yaml` 에 값 이동.
6. `CiCheckRequest`/`CiTokenExchangeRequest` `mbrDvsnCd` 검증을 `MemberDivisionCodeValidator`(설정 목록) 로.
7. V13 시드를 `db/seed-kr/` 로 이동, `spring.flyway.locations` 를 프로파일별로.
8. `GeneralizationGuardTest`: 코어 main 리소스·소스에서 고객 문자열 grep 0건.
9. 통합 테스트: `AgencyMetaRepositoryIntegrationTest` 에 enum 라운드트립·미지 값 거부 케이스 추가.
