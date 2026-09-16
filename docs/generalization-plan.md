# Idem 범용화 리팩토링 플랜 — 구조 분석과 단계별 실행 계획

> 작성 2026-09-10 · 기준 `shipster` 4eb5704 · 상태: v0.2 (2026-09-10 개정 — Tenant/Service 계층·IdP 모델로 목표 구조 수정, S4b 신설, S8 재정의)
>
> 목표: **어느 운영기관이든 설치할 수 있고, 어떤 연동기관의 요구도 코드 수정 없이(설정) 또는 플러그인으로 수용하는 구조**로 Idem 을 재편한다.
> 인증(CC·GS) 실행 계획 [`execution-plan.md`](execution-plan.md) 과 벤더 분리 [`vendor-plugin-plan.md`](vendor-plugin-plan.md) 의 **구조적 전제**가 되는 문서다.

---

## 0. 요약

**범용성의 두 축**

| 축 | 뜻 | 지금 |
|---|---|---|
| A. 운영기관 설치 가능성 | 중소벤처기업부가 아닌 다른 기관·기업이 자기 환경에 설치·운영 | ❌ 고객 고유값(이름·코드·회원 유형·도메인)이 코어에 박혀 있음 |
| B. 연동기관 수용성 | 붙는 기관(서비스)마다 다른 프로토콜·속성·정책·보안 요구를 코드 수정 없이 수용 | 🟡 `agency_meta` 12컬럼 + 문자열 `integration_type` 4종. 정책·속성·IdP 선택·세션 정책은 전역 고정 |

**결합 지점 8가지** (§1 상세): 고객 고유값 · 벤더 코드 · 경직된 기관 모델 · 한국 고유 식별자(CI/DI) · 독자 프로토콜 편중 · 관리 면 부재 · SMES 특화 회원 전환 · **회원통합 브로커 모델(기관이 회원의 진실을 쥐고 플랫폼이 기관을 돌며 조회·등록)**.

**목표 모델** (§2, 2026-09-10 개정 v0.2): 범용 IdP/IAM 과 같은 계층 — **설치본 → Tenant(Realm, 운영기관·사용자 디렉터리) → Service(기관·클라이언트)**. 사용자는 Tenant 에 속하고, Tenant 에서 SSO 가 가능하면 그 안의 모든 Service 에 SSO 가 가능하며, Service 별로 무엇을 할 수 있는지는 IM 의 **할당·역할**이 정한다. Service 가 받는 것은 어설션(Handoff/OIDC/SAML)·백채널 로그아웃·보안/감사 이벤트뿐이고, 나머지는 로그인 시점에 pull 한다. 플랫폼이 기관 DB 를 돌며 회원을 조회·등록·방송하는 흐름은 코어에서 제거한다.

*Core(프로토콜·벤더·고객 중립) + Service Profile(기관별 선언적 설정) + Tenant(디렉터리·할당) + Edition Plugins(KR 공공 등) + Admin Console*.

**단계** (§3) — 앞 단계가 뒤 단계의 토대. 각 단계는 독립 PR, 동작 변화 없음(또는 호환 유지)이 원칙.

| 단계 | 내용 | 위험 | 규모 |
|---|---|---|---|
| **S1** ✅ | 매직 문자열 타입화 + 고객 고유 기본값 외부화 | 낮음 | 1주 |
| **S2** ✅ | Service Profile 도입 (버전 있는 선언적 기관 설정 — 당시 이름 Tenant Profile) | 중 | 2~3주 |
| **S3** ✅ | 정책 엔진 규칙화 (PolicyRule SPI, 기관별 규칙, 인증수준 어휘 통일) | 중 | 2주 |
| **S4** ✅ | 식별자·속성 계약 (SubjectScheme, AttributeCatalog, 기관별 매핑) | 중~높음 | 3주 |
| **S4b** | **Tenant/Service 계층 정립 + 회원통합 브로커 흐름 제거** (전환 팬아웃·전 기관 프로비저닝·기관 하드코딩 목록) | 중 | 2주 |
| **S5** | 벤더 엔드포인트 SPI 완전 이관 (`/auth/nice/*`·`/auth/oacx/*`·AnyID → `/auth/providers/{code}`) | 중 | 2~3주 (+OACX SDK) |
| **S6** | 프로토콜 확장 (표준 OIDC RP 파사드, Agent 를 프로토콜로, SAML 준비, 백채널 로그아웃·이벤트 스트림 정리) | 높음 | 4주 |
| **S7** | 관리 콘솔 + 관리자 인증 (Tenant·Service CRUD·프로파일 편집·감사 조회) | 중 | 4~6주 |
| **S8** | **할당·역할 모델 (IM 권한 관리)** — 사용자/그룹 ↔ Service 할당, Service 별 앱 역할, 발급 판정의 `ASSIGNMENT` 규칙, `idem-authz` PDP 정리. SMES 회원 유형·사업자·후견은 에디션 확장으로 | 높음 | 4주 |
| **S9** | 에디션 패키징·온보딩 가이드·요구사항 수용 체크리스트·일회성 회원 이관 도구(KR 에디션) | 낮음 | 2주 |

의존: S1 → S2 → S3 → S4 → S4b → (S5 ∥ S6) → S7 → S8 → S9. S7 은 `execution-plan.md` P1 의 관리자 인증과 같은 작업이다.

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

**C8. 회원통합 브로커 모델 — 기관이 회원의 진실을 쥔다는 전제** (2026-09-10 S4 후 심층 분석에서 확인)

| 흐름 | 위치 | 하는 일 | 문제 |
|---|---|---|---|
| 회원 전환(계정 연결) | registry `conversion` | 하드코딩 68개 기관에 `POST /members/lookup` 병렬 조회 → 선택 기관에 `POST /members/link` 통보 | 제품 흐름 어디에서도 호출되지 않음(hub `QimClient` 에 없음). **전역 SHA-256(CI)** 를 전 기관에 방송(기관 간 결합 가능, PAIRWISE 원칙 위배). 기관 실명이 코어에. 연결 결과가 영속되지 않아 Handoff 판정에 무관 |
| 전 기관 프로비저닝 | hub `provision` + relay `ProvisioningRelayJob` | 회원 가입·전환·탈퇴 이벤트마다 활성 기관 전체에 qimUserId 를 POST | `sha256(qimUserId:registeredAt)` 은 기관이 대조할 수 없는 값 → 실질은 플랫폼 ID 방송. 수신 구현이 SDK·샘플에 없음. `enabled=false`·dry-run 기본으로 한 번도 실발행된 적 없음 |
| GUEST 판정 | hub `PolicyEngineImpl` | "DI 없음 = 기관 매핑 없음 → GUEST" | registry `/di` 는 누구에게나 즉시 생성 → 사실상 전원 APPROVED. 접근 권한 개념이 없다 |

범용 IdP(Keycloak Realm/Client · Entra Tenant/Enterprise App · Okta Org/App · SAML IdP/SP · OIDC OP/RP)는 예외 없이 **디렉터리가 진실**이고, 서비스 접근은 **할당(assignment)** 으로 결정하며, 서비스에 push 하는 것은 백채널 로그아웃·보안 이벤트(CAEP/RISC)·SCIM(선택, 서비스별 opt-in) 뿐이다. 기존 계정 연결은 **서비스 측**이 첫 SSO 로그인 때 클레임으로 한다. 현재 모델은 "68개 기관 회원 DB 를 이어 붙이는 연결기" 로 출발한 흔적이며, 이 전제를 버리는 것이 S4b·S8 이다.

### 1.3 유지할 강점

Outbox 패턴(릴레이·멱등·재시도) · `IdentityVerificationProvider` SPI · `KmsClient` SPI · `HandoffStrategy` 팩토리 · 기관 정책 버전·이력(`agency_policy_history`) · SLO 체인 · CAST 1회성·Ed25519 · 기관별 Rate Limit · 감사 카테고리 체계 · SCIM v2 · Testcontainers 통합 테스트(2026-09-08 복구).

---

## 2. 목표 구조

### 2.0 계층 — 설치본 → Tenant → Service (v0.2, S4b)

```
설치본 (운영기관 1개 이상 — 다중 Tenant 는 Realm 단위 격리)
└─ Tenant (Realm)  ─ 사용자 디렉터리(registry) · 그룹 · 동의 · 관리자
   ├─ Service A (기관·클라이언트) ─ Service Profile: 프로토콜·엔드포인트·클레임 계약·정책·역할
   ├─ Service B
   └─ …
```

| 개념 | Idem 용어 | 표준 대응 | 진실이 있는 곳 |
|---|---|---|---|
| Tenant | `ido.tenant` (`tenant_code`) · registry `qim_user.tenant_code` | Keycloak Realm · Entra Tenant · Okta Org | hub(설정) + registry(사용자) |
| Service | `agency_meta` + **Service Profile**(`profile JSONB`) — 코드·API 는 `service`, DB 식별자 `agency_code` 는 4b 개명 전까지 유지 | Client · Relying Party · Enterprise App | hub |
| 사용자 | registry `qim_user`(Tenant 소속) | Directory user | registry |
| 접근 | 할당(사용자/그룹 ↔ Service) + Service 별 역할 (S8) | App assignment · App role | hub/authz |
| 어설션 | Handoff 페이로드 (S6 에서 OIDC/SAML 도 같은 계약) | id_token · SAML assertion | hub 가 발급, Service 가 pull |
| Service 로 push | 백채널 로그아웃 · 보안/감사 이벤트(웹훅 아웃박스) · SCIM(선택, S9) | Back-channel logout · CAEP/RISC · SCIM | hub 아웃박스 |

**IM 이 관리하는 것**: 디렉터리 · 할당·역할 · Service 별 동의·클레임 계약 · 정책 · 세션. **Service 가 하는 것**: 어설션 검증, 자기 계정과의 연결(첫 로그인 시 클레임으로), 자기 권한 집행(어설션의 역할 또는 authz PDP 조회). **하지 않는 것**: 플랫폼이 Service 회원 DB 를 조회·등록·동기화하는 일.

### 2.1 Service Profile (S2, S4b 에서 개명) — 기관별 선언적 설정의 단일 원천

```yaml
schemaVersion: 1
service: { code: AGENCY_A, name: "…", status: ACTIVE, tenant: DEFAULT }   # S4b: 루트 블록 tenant → service, 소속 Tenant 참조
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

`SubjectScheme`(idem-common) 하나가 두 자리에서 쓰인다.

| 자리 | 스킴 | 뜻 |
|---|---|---|
| registry 저장 키 (`user_profile.subject_scheme/subject_key`, 암호화) | `CI`(KR) · `EMAIL` · `PHONE` · `EXTERNAL_SUB` | 사람을 registry 안에서 유일하게 가리키는 값. 조회는 `auth_mean_mapping.identifier_hash` 로만 (`CI`/`EXTERNAL_SUB` 는 원문 SHA-256 호환, `EMAIL`/`PHONE` 은 `"EMAIL:"+정규화` 접두) |
| 기관향 식별자 (프로파일 `identity.subjectScheme`) | `PAIRWISE_HMAC`(기본, 현 DI) · `PLATFORM_ID` · `EMAIL` · `PHONE` · `EXTERNAL_SUB` | Handoff `subject.agencySubjectId` 의 종류. `CI` 는 registry 밖으로 평문이 나가지 않으므로 선택 불가. 스킴이 다른 사용자는 GUEST |

hub 는 `SubjectIdentifierScheme` SPI(스킴별 빈, 에디션이 확장) 로 해석하고, `AttributeCatalog`(정규 이름·타입·출처 TICKET/PROFILE/SUBJECT·민감도·기본 마스킹·종전 별칭) 의 부분집합을 기관이 `identity.attributes`(+`required`/`masking`) 와 `identity.attributeMapping` 으로 선언한다. 카탈로그 밖 이름은 프로파일 검증에서 거부되고, 별칭(camelCase)으로 선언한 기관은 출력 키도 별칭을 유지한다(기존 연동 호환). 본인인증 SPI 결과(`VerifiedIdentity.subjectScheme + subjectKey`) 는 `SubjectRegistrationService` 가 `POST /internal/users/register-subject` 로 registry 사용자로 확정한다 — CI 가 없는 제공자도 같은 경로.

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

### S2 — Tenant Profile → Service Profile (2~3주)

`tenant-profile.schema.json` v1 · `TenantProfile` 도메인(record) · `agency_meta.profile JSONB` 마이그레이션(기존 컬럼 → 프로파일 합성 백필) · `TenantProfileService`(읽기: 프로파일 우선, 없으면 컬럼 합성; 쓰기: 프로파일) · Admin API `PUT /api/v1/admin/tenants/{code}/profile`(스키마 검증) · `AgencyMeta` 는 프로파일의 뷰로 재구성 · 이력은 `agency_policy_history` 에 스냅샷.
완료 기준: 기존 API·Handoff 동작 동일, 새 기관을 프로파일만으로 온보딩하는 통합 테스트.

**진행 기록 (2026-09-10)** — S2 구현 PR:
- ✅ `tenant-profile/tenant-profile.v1.schema.json` (draft 2020-12, `additionalProperties:false`) · `TenantProfile` record 트리 · `TenantProfileValidator`(networknt 1.5.9)
- ✅ V21: `agency_meta.profile JSONB` + `profile_schema_version`, 기존 컬럼 → v1 백필(`jsonb_strip_nulls`), `protocol.type` 인덱스
- ✅ **단일 쓰기 원칙**: `TenantProfileService.put` 은 검증 → 컬럼 투영(`applyToEntity`) → 원문 저장 → `agency_meta_history` 스냅샷(이전에는 읽기만 있고 쓰는 코드가 없었음) → 감사. 레거시 쓰기 경로(`AgencyAdminService` 5곳, `AgencyMetaRepositoryImpl.save`)는 저장 직전 `syncProfileColumn` 으로 컬럼 → 프로파일을 맞추며, 프로파일 전용 항목(security·attributeMapping·allowedProviders·session·limits.tps·ui)은 보존
- ✅ Admin API `GET/PUT /api/v1/admin/tenants/{code}/profile`, `GET /api/v1/admin/tenants/profile-schema` (S4b 에서 `/admin/services/…` 로 개명). 미지 스키마 위반·코드 불일치 → 400 `E-IDO-113`. 기관이 없으면 PUT 이 생성(프로파일만으로 온보딩)
- ✅ `daily_lookup_limit` 이 JPA 엔티티에 매핑됨 — 이전에는 `AgencyCreateRequest.dailyLookupLimit` 이 받기만 하고 저장되지 않았다
- ✅ (S3·S4 에서 해소) 정책·식별자·속성 읽기는 프로파일로 이동했다. `AgencyMeta` 컬럼은 발급 경로의 기관 존재·활성·콜백·연동 유형 판정에만 남아 있고 S6 에서 프로파일로 옮긴다

### S3 — 정책 엔진 규칙화 (2주)

`PolicyRule` SPI(`evaluate(ctx) → Decision`) · 내장 규칙 `MIN_AUTH_LEVEL / USER_STATUS / MAINTENANCE / ALLOWED_PROVIDERS / REAUTH` · 기관 프로파일 `policy.rules` 로 조합 · `AuthLevel` 단일 어휘(`L1/L2/L3` 유지, `LOW/MEDIUM/HIGH` 제거, 플러그인 하드코딩 `L2` 를 SPI `level()` 로) · 정책 시뮬레이션 API(`POST /admin/tenants/{code}/policy/simulate`).
완료 기준: `PolicyEngineImpl` 의 하드코딩 규칙 0, 규칙별 단위 테스트, 시뮬레이션 통합 테스트.

**진행 기록 (2026-09-10)** — S3 구현 PR:
- ✅ `PolicyRule` SPI(`type/builtIn/order/evaluate(ctx, params)`) + `PolicyContext`(프로파일·요청 속성·지연 사용자 상태 공급자·시각) + `PolicyDecision`(ALLOW/DENY/SKIP, 감사 코드, 오류 코드) + `PolicyEvaluation`
- ✅ 내장 규칙 4종: `MAINTENANCE`(10) → `MIN_AUTH_LEVEL`(20) → `ALLOWED_PROVIDERS`(30) → `USER_STATUS`(90, Q-IM 조회라 마지막). 프로파일에 설정이 없으면 SKIP. `policy.rules[]` 로 내장 규칙 파라미터(예: USER_STATUS `deny`)·커스텀 규칙(에디션 플러그인 `PolicyRule` 빈) 지정. 미등록 유형 요구는 fail-closed
- ✅ `PolicyEngine.evaluate(ctx, stopAtFirstDenial)` — 발급 경로는 첫 거부에서 중단(뒤의 비싼 규칙 미호출), 시뮬레이션은 전부 평가. **정책 읽기가 프로파일로 이동**(`HandoffServiceImpl` 은 `AgencyMeta` 컬럼 대신 `TenantProfile.policy` 로 판정)
- ✅ 정책 시뮬레이션 API `POST /api/v1/admin/tenants/{code}/policy/simulate` — 규칙별 판정·사유를 실제 발급 없이 확인
- ✅ 인증수준 어휘 통일: `AuthLevel.parse/meets` (L1~L3 정규, LOW/MEDIUM/HIGH·acr 숫자 호환 해석). CAST 토큰은 정규 어휘만 싣고 검증 측은 둘 다 해석. 플러그인의 `level()` 은 SPI 계약대로 유지
- ✅ 부수 발견: 점검 시간대 판정이 `DayOfWeek.name()`("MONDAY")과 저장값("MON")을 그대로 비교해 **한 번도 걸리지 않던 상태** → 앞 3글자 비교(MON/MONDAY 모두 인식). 스키마도 `{3,9}` 자로
- ⏭ REAUTH(재인증) 규칙은 인증 시각이 요청 컨텍스트에 없어 S6(세션 정책)으로 이월

### S4 — 식별자·속성 계약 (3주)

`SubjectIdentifierScheme` SPI + registry `subject_key/scheme` 컬럼(백필: `ci`→`CI` 스킴) · `PAIRWISE_HMAC` 스킴으로 `DiGenerationService` 일반화 · `AttributeCatalog`(코어 정의, 마스킹 규칙 포함) · 기관 `identity.attributes/attributeMapping` 으로 `HandoffPayload.attributes` 구성 · `allowed_attributes` 는 프로파일로 흡수.
완료 기준: CI 없는 스킴(EMAIL)으로 Mock 인증→Handoff 통합 테스트 통과.

**진행 기록 (2026-09-10)** — S4 구현 PR:
- ✅ `SubjectScheme`(idem-common): registry 저장 스킴 `CI/EMAIL/PHONE/EXTERNAL_SUB` · 기관향 `PAIRWISE_HMAC(기본)/PLATFORM_ID/EMAIL/PHONE/EXTERNAL_SUB`. 정규화(이메일 소문자·전화 숫자만)·`identifierHash` 규칙을 한 곳에 둠 — hub·registry 가 같은 해시를 계산한다
- ✅ `AttributeCatalog` 13개 정의(TICKET 5 · PROFILE 5 · SUBJECT 3) + `MaskingRule`(NONE/PRESET/PARTIAL/LAST4/EMAIL_LOCAL). 프로파일 스키마 `identity.subjectScheme`, `identity.attributes[]` 는 문자열 또는 `{name, required, masking}`. 검증기가 카탈로그 밖 이름·중복·미선언 매핑 키를 E-IDO-113 으로 거부
- ✅ hub `SubjectIdentifierScheme` SPI + `SubjectIdentifierResolver`(미지원 스킴 fail-closed E-IDO-115, 중복 기동 거부) + 코어 구현 5종. `HandoffAttributeAssembler` — 출처별 지연 조회(프로필 1회·스킴별 1회), 마스킹, `attributeMapping`, `required` 결핍은 E-IDO-114(422, consume 전 거부라 티켓은 살아 있음). `PolicyEngineImpl.buildHandoffPayload` 는 프로파일이 진실(`AgencyMeta` 컬럼 읽기 제거). 페이로드 `subject.subjectScheme` 추가
- ✅ 본인인증 SPI 결과에 `VerifiedIdentity.subjectScheme`(기본 EXTERNAL_SUB, 종전 생성자 호환) · `POST /auth/providers/{code}/complete` 가 `SubjectRegistrationService` 로 registry 등록까지 하고 `{identity, registration:{qimUserId,newUser}}` 를 돌려줌 · Mock 제공자는 `email` 파라미터로 EMAIL 스킴
- ✅ registry V8: `user_profile.subject_scheme/subject_key`(암호화) + `ci` 백필. `POST /internal/users/register-subject`(스킴 중립, 본문은 종전 `UserRegisterRequest` 확장) · `GET /internal/users/{id}/subject?scheme=`(CI 400 · 불일치 404) · 응답에 `subjectScheme`, DI 응답에 `scheme=PAIRWISE_HMAC`. 탈퇴 PII 삭제에 `subject_key` 포함
- ✅ **부수 발견 2건(운영 결함)**: hub `QimClientImpl.registerUser/findByCi` 가 registry 에 **없는** `POST /internal/users/register`·`/find-by-ci` 를 호출하고 있었다 → NICE/OACX 인증 후 등록은 항상 503, CI 회원조회는 항상 "미등록". 각각 `register-subject(scheme=CI)`·`by-hash(CI 해시)` 로 연결. 그리고 `allowed_attributes` 가 NULL 이면 코드가 "전체 차단" 하는데 S2 스키마 설명은 "제한 없음" 이었다 → 최소 권한(전달 없음)으로 통일하고 설명 수정
- ✅ **부수 발견 3(운영 결함)**: Handoff `verify` 의 원자적 consume(Lua CAS)이 값 직렬화기(JSON)가 문자열을 `\"state\":\"ISSUED\"` 로 이스케이프해 저장하는 것을 몰라 항상 `PARSE_ERROR` → 500 이었다(verify 를 끝까지 타는 통합 테스트가 없어 미검출). 원문·이스케이프 마커 둘 다 인식하고 인자·결과를 문자열 직렬화기로 보내도록 수정 — 이번 통합 테스트가 최초의 issue→verify 끝-끝 검증
- ✅ 완료 기준 충족: `IdentityContractIntegrationTest` — Mock 인증(email) → `register-subject(EMAIL)` → Handoff verify 가 이메일을 `agencySubjectId` 로, `userNm`(매핑)·`mail`(마스킹 해제)·`birth_year`·`qimUserId`(별칭 유지) 를 속성으로 돌려준다. 기본 스킴 기관은 종전 DI 경로 그대로, 스킴 불일치는 GUEST, required 결핍은 422
- ⏭ registry 통합 테스트(MariaDB Testcontainers)는 이 환경에서 못 돌렸다 — V8 SQL(MariaDB `ADD COLUMN IF NOT EXISTS`)·엔티티 매핑은 로컬 `./gradlew :idem-registry:test` 로 확인 필요. `register-social` 은 아직 `subject_scheme` 을 쓰지 않는다(프로필 행이 없음) — S8 에서 EXTERNAL_SUB 로 정리. 이름 원문 보관("이름은 마스킹 없이")은 registry 저장 정책이라 S8 로 이월

### S4b — Tenant/Service 계층 정립 + 회원통합 브로커 흐름 제거 (2주)

| 작업 | 내용 |
|---|---|
| 계층 개명 | `TenantProfile*` → `ServiceProfile*`, 패키지 `hub.tenant` → `hub.serviceprofile`, Admin API `/api/v1/admin/tenants/{code}/…` → `/api/v1/admin/services/{code}/…`, 프로파일 루트 블록 `tenant` → `service`(+`service.tenant` 소속 참조), 스키마 파일 `service-profile.v1.schema.json`. 저장된 `profile` JSONB 의 키는 V22 가 이관 |
| Tenant 신설 | hub V22 `ido.tenant`(code·name·status) + 기본 행 `DEFAULT`, `agency_meta.tenant_code` FK(기본 DEFAULT). Admin API `GET/PUT /api/v1/admin/tenants/{code}`. registry V9 `qim_user.tenant_code`(기본 DEFAULT). 다중 Tenant 격리(사용자·관리자 분리)는 S7 |
| 팬아웃 제거 | registry `conversion` 패키지·`conversion_session` 테이블·`AgencyRegistry`(기관 68개 하드코딩)·`qim.agency.*` 설정 · hub `provision` 패키지·`provisioning_outbox`·`ido.provisioning.*`·mTLS 프로비저닝 RestTemplate · relay `ProvisioningRelayJob` · tenant-sample `/api/v1/members/lookup,link` · Kafka 소비자의 프로비저닝 트리거 |
| 대체 | 기존 계정 연결 = Service 측 첫 로그인 연결(가이드·샘플), 일회성 회원 이관 도구 = S9(KR 에디션), SCIM 아웃바운드 = S9 선택 옵션. hub `conversion`(기관 signed_request 전환 진입) 은 SMES 전환 UX 라 S8 에서 에디션으로 |
| 문서 | `sso-agency-integration-guide.md` §4.2·§5 를 "Service 측 계정 연결" 로 재작성, `features/F-20~22` 제거 표기, 가드 허용 목록에서 `AgencyRegistry` 삭제 |

완료 기준: 코어 어디에도 "플랫폼 → 기관 회원 DB 조회/등록/방송" 코드가 없고, 기관 목록의 진실이 `agency_meta`(+Service Profile) 하나이며, 전 모듈 테스트 통과.

**진행 기록 (2026-09-10)** — S4b 구현 PR:
- ✅ 제거: registry `conversion` 패키지 9파일 + `ConversionController` + `conversion_session`(V9 DROP) + `RestTemplateConfig`(`qim.agency.*`) · hub `provision` 패키지 9파일 + `provisioning_outbox`(V22 DROP) + `ido.provisioning.*` + mTLS 프로비저닝 RestTemplate + Kafka 소비자 트리거 + 게이트웨이 상태의 프로비저닝 카운트 · relay `ProvisioningRelayJob`(+`batch.relay.provisioning`) · tenant-sample `/api/v1/members/lookup,link` · 오류 코드 `IM_CONVERSION_*`. `AgencyCredentialStore` 는 기관 자격증명 조회(전환 JWT 검증)에 쓰여 `hub.infrastructure` 로 이동. 가드 허용 목록에서 `AgencyRegistry` 삭제
- ✅ 계층: `ServiceProfile*`(패키지 `hub.serviceprofile`), Admin API `/api/v1/admin/services/{code}/profile|profile-schema|policy/simulate`, 프로파일 루트 `service{code,name,status,tenant}`(`tenant` 생략 = DEFAULT, 미등록 Tenant 400). `ido.tenant` + `agency_meta.tenant_code`(V22, 저장된 `profile` JSONB 의 `tenant`→`service` 이관 포함), Tenant Admin API `GET/PUT /api/v1/admin/tenants[/{code}]`. registry `qim_user.tenant_code`(V9), 등록 계약(`register-subject`)에 `tenantCode`
- ✅ 부수 발견: registry `WithdrawalServiceImpl` 이 예약 탈퇴 취소 상태 오류에 전환 코드(`IM_CONVERSION_INVALID_STATE`)를 빌려 쓰고 있었다 → `IM_WITHDRAWAL_NOT_ALLOWED`
- ✅ 문서: `sso-agency-integration-guide.md` §4.2·§5 를 "Service 측 첫 로그인 계정 연결" 로 재작성(§6 Q&A·체크리스트 정합), `features/F-20~22` 제거 표기
- ⏭ `relay` 의 `BatchRestTemplateConfig`(provisioning* 빈)·`DeadLetterNotifier` 는 프로비저닝 전용이 아니어서 남겨 두었다(다음 아웃박스 정리 때 이름 정리). wiki/ops 의 프로비저닝 런북·ADR-009 는 이력으로 유지. hub `conversion`(signed_request 전환 진입)·`memberlookup` 은 S8 에서 KR 에디션으로. 다중 Tenant 격리(사용자·관리자 분리)는 S7

### S5 — 벤더 엔드포인트 SPI 완전 이관 (2~3주, OACX SDK 재수령 필요)

`AuthController` 벤더 경로 5개 → `IdentityVerificationController` 경유로 대체(구 경로는 1 릴리스 deprecated 프록시) · `NiceCryptoUtil`·`AuthWebClientConfig` NICE 부분 → `idem-plugin-nice-oacx` · `broker/anyid` → `idem-plugin-anyid`(`vendor-plugin-plan.md` P3) · `BrokerController /kakao` 제거, `idp-hint-mapping` 을 프로파일 `allowedProviders` 로 · FE `useEzAuth` → `useAuthWidget`.
완료 기준: 코어 `idem-hub` 에 벤더 클래스 0, Mock 플러그인만으로 CI 통과.

**진행 기록 (2026-09-10)** — S5a 구현 PR (NICE/OACX 플러그인 이관, S5b AnyID 는 다음 PR):
- ✅ 플러그인 측: `NiceAuthService`→`NicePhoneService`(`NicePhoneGateway` 구현, registry 등록·감사 없음, 실패는 `IdentityVerificationException(reasonCode 4000/5001/5002/5003/5000)`), `NiceApiClient`·`NiceCryptoUtil`·`NiceTokenStore`·`NiceAuthSessionStore`·`dto/nice/*`, `OacxClient`→`oacx/OacxClientAdapter` 를 `plugins/idem-plugin-nice-oacx` 로 이동. `NiceProperties(ido.auth.nice.*)`·`OacxProperties(ido.auth.oacx.*)`·`NiceWebClientConfig`·`NiceCredentialsValidator`(prod 프로파일 자격증명 검사) 신설. `NicePhoneIdentityVerificationProvider.complete` 는 CI 스킴 `VerifiedIdentity`(속성 `nationalInfo`·`di`), `OacxEasySignIdentityVerificationProvider` 실구현(SDK 있을 때만 빈 등록). `NiceOacxAutoConfiguration` 은 코어 빈(`RedisTemplate<String,String>`·`RedissonClient`·`ObjectMapper`)만 받아 기본 게이트웨이를 만든다
- ✅ hub 측: `NiceIdentityVerificationProvider`(P1 코어 어댑터)·`AuthCredentialsValidator`·`AuthWebClientConfig` NICE 빈·`AuthProperties.nice/oacx`·`AuthService` OACX 메서드 삭제. `AuthController` 의 벤더 5 경로는 `auth/legacy/LegacyVendorAuthController`(`@Deprecated`)로 옮겨 SPI(`IdentityProviderRegistry.find("NICE_PHONE"|"OACX_EASYSIGN")`) + `SubjectRegistrationService` 를 호출하는 **호환 프록시**가 됐다 — 응답 형식(2000/4000/5001/5003/5010, `di` 포함·CI 미노출)은 콘솔 훅(`useNicePhoneAuth`·`usePersonalEasyAuth`)·k6 스모크 계약 그대로. `AuthAuditService` 는 벤더 메서드 대신 `publishProviderInitiate/Complete(providerCode, …)`(`AUTH_PROVIDER_*` 액션), `AuthTracingAspect` 는 `IdentityVerificationController.initiate/complete` 스팬. `idem-hub/libs/OACX-SDK-v1.3.2.jar` 삭제(vendor-libs 외부 공급). `GeneralizationGuardTest.hubCoreContainsNoVendorTokens` 가 hub main 의 벤더 클래스·호스트 참조를 금지(허용: `auth/legacy/`, `broker/anyid/`, AnyID 자원)
- ✅ **부수 발견(운영 결함)**: Redisson 3.32.0 의 Spring Data 3.3 어댑터가 Spring Data Redis 3.5.x 에서 `RedisTemplate.expire()` 를 무한 재귀(StackOverflowError)시켰다 — NICE 토큰 캐시 저장·`FeSessionServiceImpl` 세션 TTL 이 모두 500 이었고, 종전 통합 테스트는 500 을 허용해 놓쳤다. `redisson-spring-boot-starter` 3.52.0(`redisson-spring-data-35`)으로 올리고 새 `NiceAuthIntegrationTest` 는 SPI initiate·레거시 `/nice/phone/url` 이 WireMock 을 거쳐 200 이어야 통과
- ⏭ S5b: `broker/anyid` → `idem-plugin-anyid`, `idem-hub/libs` AnyID jar 13개·`static/` 번들·`config/anyid/` 제거, `BrokerController /kakao`·`idp-hint-mapping` 정리. FE `useEzAuth`→`useAuthWidget` 과 `public/ezauth` 자산 이동, 콘솔 훅의 `/auth/providers/{code}` 전환(그 뒤 `auth/legacy` 삭제)은 S7 콘솔 작업과 묶는다. `ido.auth.nice.*` 키 이름은 4b 개명까지 유지

**진행 기록 (2026-09-11)** — S5b 구현 PR (AnyID 플러그인 이관):
- ✅ 코어 SPI(idem-common `spi/broker`): `DirectBrokerAdapter`(플러그인→코어: `id()`·`supports(code)`·`buildAuthorizationUrl`) 와 `BrokerAuthCompletion`(코어→플러그인: 벤더 인증 결과 → AuthResult 저장·감사·이벤트·FE 세션). hub `BrokerService` 는 `AnyIdBrokerAdapter` 직접 주입 대신 `List<DirectBrokerAdapter>` 를 받아 `provider_config.broker_mode == id()` → `supports()` 순으로 고른다(플러그인이 없으면 종전 비OIDC 경로). `DirectBrokerAuthCompletion`(hub `broker/nonoidc`)이 `NonOidcAuthService`+`FeSessionService` 를 잇는다
- ✅ `plugins/idem-plugin-anyid`: `AnyIdProperties`·`AnyIdBrokerAdapter`(SPI 구현)·`AnyIdController`(`/api/v1/anyid/*` 경로·응답 계약 그대로)·`SsobDecryptor`→`sdk/AnyIdSdkSsobDecryptor`(유일한 `kr.or.anyid` 참조, SDK 부재 시 컴파일 제외·엔드포인트 503)·`AnyIdSsob`·`AnyIdKmsClient`(코어 `KmsClient` 계약 밖 내부 컴포넌트). 기본값·`ANYID_*` 환경변수 매핑은 `AnyIdDefaultsEnvironmentPostProcessor` 가 올리는 `idem-plugin-anyid-defaults.yml` — 코어 `application.yml`·`application-local.yml` 의 `ido.anyid` 블록 삭제
- ✅ 저장소 정리: `idem-hub/libs/*.jar` 12개(AnyID SDK 6 + 동봉 공개 의존성 6), `static/anyid/**`(16MB 번들, RSA 키 블록 포함 `vendor.js`), `static/config/config.anyidc.json`, `config/anyid/*.json`, `sso-adaptor-conf-local.properties` 삭제 → vendor-libs 외부 공급(`~/.idem/vendor-libs/anyid/resources/**` 를 jar 루트로 복사). 공개 의존성은 Maven Central. hub Dockerfile 의 `libs/` COPY 제거. `BrokerController /kakao/authorize` 별칭 제거(콘솔·k6 미사용). 가드 테스트에 AnyID 토큰 11종 추가, 벤더 허용 목록은 `auth/legacy/`·`application.yml`(NICE base-url)·V19 시드만
- ✅ **보안 정리**: `application-local.yml` 에 남아 있던 AnyID KMS `client-info` 평문과 `config/anyid/*.json`·`sso-adaptor-conf-local.properties` 의 `app_key`·`client_info`·PID `clientId/Secret/ApiKey`·SSO `secret.code` 평문을 저장소에서 제거했다(값은 문서·채팅에 적지 않음). 히스토리에 잔존하므로 벤더 키 교체 요청 필요(open-source-readiness B1 잔여)
- ✅ 발견: 콘솔의 `useAnyIdAuth.ts`·`AnyIdLoginModal` 은 import 되는 곳이 없고 SDK 스크립트를 로드하는 곳도 없다 — AnyID FE 연동은 현재 죽은 코드. `VaultKmsHealthIndicator` 의 "anyid 후순위" 분기는 대상이 사라져 첫 번째(`@Primary`) 구현 사용으로 단순화
- ⏭ `idp-hint-mapping`(Keycloak IdP alias 매핑)은 고객값이 아니라 Keycloak 설정이라 S6 `OIDC_RP`(Keycloak client 프로비저닝)에서 프로파일로 옮긴다. FE `useEzAuth`→`useAuthWidget`·`public/ezauth` 이동, AnyID FE 훅 정리는 S7. `auth/legacy` 삭제는 콘솔 전환 뒤

### S6 — 프로토콜 확장 (4주)

`IntegrationProtocol` + `TenantProtocolHandler` SPI · 기존 3 전략 이식 · `OIDC_RP`: Keycloak client 프로비저닝 + 정책 강제 authenticator · Agent(`APACHE_GATE`) 를 프로토콜로 정식화 · `SAML_SP` 설계만.
완료 기준: tenant-sample 이 프로파일 `protocol.type` 변경만으로 4가지 방식 모두 통과.

### S7 — 관리 콘솔 + 관리자 인증 (4~6주, `execution-plan.md` P1 과 동일 작업)

관리자 I&A·RBAC(P1) 위에 기관 목록/온보딩/프로파일 편집(스키마 기반 폼)/정책 시뮬레이션/감사 조회. `idem-console` 의 SigNoz 잔재 정리 후 **관리 앱과 사용자 포털 분리**(`idem-console-admin`, `idem-portal`).

### S8 — 할당·역할 모델: IM 권한 관리 (4주, 위험 높음)

- **할당(assignment)**: 사용자/그룹 ↔ Service (직접 · 그룹 · 속성 규칙). 미할당이면 발급 거부 — 정책 엔진에 내장 규칙 `ASSIGNMENT` 추가(S3 SPI). GUEST 는 Service Profile 이 셀프 가입을 허용할 때만 허용.
- **역할**: Service 별 앱 역할(app role) 정의·부여, 어설션(Handoff/OIDC 클레임)에 싣기. 세밀 인가는 `idem-authz` 를 PDP 로 정리(fail-open 제거, SCIM Groups 와 연결).
- **DI/GUEST 정리**: PAIRWISE 식별자는 첫 발급 시 생성(표준 pairwise sub 와 동일) 으로 의미를 고정하고 "DI 없음 → GUEST" 서술 제거.
- SMES 회원 유형(개인/기업/후견)·사업자 전환·`mbrDvsnCd`·hub `conversion`(signed_request 전환 진입)·`memberlookup` 은 **KR 에디션 확장 모듈**로 이동(`idem-registry` 코어는 `user/identity/consent/withdrawal`). 확장 속성은 `extra_attributes JSONB` + 카탈로그.
완료 기준: 코어 registry 가 SMES 개념 없이 기동·테스트 통과, 미할당 사용자의 Handoff 가 거부되는 통합 테스트, KR 에디션에서 기존 시나리오 통과.

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
| "우리 기관 사용자만 / 특정 그룹만 접근" | 할당(사용자·그룹 ↔ Service) | S8 |
| "우리 서비스 안에서 역할(관리자·심사자)을 SSO 가 내려줬으면" | Service 별 앱 역할 → 어설션 클레임 | S8 |
| "기존 회원 계정과 자동으로 이어 달라" | Service 측 첫 로그인 연결(이메일·사번 클레임) + 일회성 이관 도구 | S4b·S9 |
| "회원 생성·삭제를 우리 쪽에도 동기화" | SCIM 2.0 아웃바운드(Service 별 opt-in) · 탈퇴 이벤트 웹훅 | S9 |
| "로그아웃하면 우리 세션도 끊어 달라" | 백채널 로그아웃 이벤트 | S6 |
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
