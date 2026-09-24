# Idem 범용화 리팩토링 플랜 — 구조 분석과 단계별 실행 계획

> 작성 2026-09-10 · 기준 `main` 504d9e2 (PR #222 머지) · 상태: **v0.4 (2026-09-24 개정 — 적대적 점검 결과 반영: S8 을 S8-a(빼기)·S8-b(더하기)로 나누고 S6(표준 프로토콜)을 S7(콘솔) 앞으로. 순서: D1 → D2 → S8-a → S8-b → S6 → S7 → S9)**
> 이전: v0.2 (2026-09-10 — Tenant/Service 계층·IdP 모델, S4b 신설, S8 재정의) · v0.1 (2026-09-10)
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

**결정 (2026-09-16, v0.3)**

| 결정 | 내용 | 영향 |
|---|---|---|
| **GS 우선** | 1차 목표는 GS 인증(TTA)과 조달 등록. CC 는 KCMVP 암호모듈·관리자 보안기능·fail-secure 가 갖춰진 1.x 릴리스로 별도 프로젝트 | S9 에서 1.0 동결 → GS 문서 작업. `execution-plan.md` P3 이 P4 보다 앞 |
| **Keycloak 유지** | 표준 OIDC 발급·세션은 Keycloak 을 쓰되 설치본 안에 완전히 숨긴다(설치자·기관은 Keycloak 을 보지 않는다). 토큰 발급·세션 경계는 Idem 코드 뒤에 두어 CC 시점에 자체 IdP 로 바꿀 수 있게 한다 | S6 은 "Keycloak client 프로비저닝 정식화" 로 축소. 자체 IdP 는 범위 밖 |
| **IM 을 얇게** | IM 코어 = 사용자·식별자·자격증명·동의·생명주기·할당·역할. 기관 업무 개념(회원 유형·사업자 전환·등급)은 KR 에디션 확장 또는 서비스 측 | S8 이 S6·S7 보다 앞 |
| **다이어트 먼저** | 새 기능 전에 설치 복잡도를 줄인다: DB 엔진 1종, Kafka 선택, 제품 3개로 재편 | D1·D2 신설, S6~S9 앞에 둠 |

**진행 현황 (2026-09-16)** — S1~S5 완료(main 504d9e2). "코어에서 고객값·벤더를 걷어내는 일" 은 끝났고 "다른 기관이 설치해 쓰는 제품" 까지는 약 40%. 남은 넷(IM 모델·관리 콘솔·표준 프로토콜·패키징)이 지금까지보다 크다. 설치 복잡도는 오히려 늘었다(모듈 10 + 플러그인 3, DB 2종, Kafka·Redis·Keycloak, vendor-libs).

**공개 (2026-09-21)** — GitHub Actions 결제 문제로 저장소를 S9 보다 앞당겨 **Public·Apache-2.0** 으로 전환한다. D0(공개 전 점검) 은 `docs/public-release-checklist.md`. 벤더 자격증명 교체는 전환 전 필수, 고객 문서·히스토리 정리는 사용자 결정.

**단계** (§3) — 앞 단계가 뒤 단계의 토대. 각 단계는 독립 PR, 동작 변화 없음(또는 호환 유지)이 원칙. v0.3 부터 순서는 아래 표의 순서다.

| 순서 | 단계 | 내용 | 위험 | 규모 |
|---|---|---|---|---|
| 1 | **S1** ✅ | 매직 문자열 타입화 + 고객 고유 기본값 외부화 | 낮음 | 1주 |
| 2 | **S2** ✅ | Service Profile 도입 (버전 있는 선언적 기관 설정) | 중 | 2~3주 |
| 3 | **S3** ✅ | 정책 엔진 규칙화 (PolicyRule SPI, 인증수준 어휘 통일) | 중 | 2주 |
| 4 | **S4** ✅ | 식별자·속성 계약 (SubjectScheme, AttributeCatalog) | 중~높음 | 3주 |
| 5 | **S4b** ✅ | Tenant/Service 계층 정립 + 회원통합 브로커 흐름 제거 | 중 | 2주 |
| 6 | **S5** ✅ | 벤더 코드 플러그인 이관 (nice-oacx·anyid), 코어 브로커 SPI, SDK·번들·자격증명 저장소 제거 | 중 | 2~3주 |
| 7 | **D1** | **다이어트** — registry 를 PostgreSQL 로, Kafka 선택 의존화(DB 만으로 아웃박스·감사 완결), 제품 3개(SSO·IM·KR 에디션)로 재편, 단일 설치본 | 중 | 2~3주 |
| 8 | **D2** | **fail-secure 전수 점검 + 암호 경계 단일화** — 모든 fail-open·PoC 폴백 제거, `CryptoProvider` SPI 로 JCA 호출 집약(KCMVP 교체 자리) | 중 | 1~2주 |
| 9 | **S8-a** | **IM 을 얇게 (빼기)** — SMES 회원 개념(CI 조회·기업인증·회원전환·회원조회·기업회원·회원구분코드)을 `editions/idem-kr-hub`·`idem-kr-registry` 로 이동, 코어 hub·registry 는 SMES 없이 기동·통과, KR 가드 | 중 | 1~2주 |
| 10 | **S8-b** | **할당·역할 모델 (더하기)** — 사용자/그룹 ↔ Service 할당, 앱 역할, `ASSIGNMENT` 규칙, DI/GUEST 정리, hub `qim/sp` 정리 | 높음 | 2~3주 |
| 11 | **S6** | **표준 프로토콜** — `OIDC_RP` 를 Keycloak client 프로비저닝으로 정식화(Keycloak 은 숨김), Handoff 는 KR 연계 방식으로 격하, SAML 설계만. **v0.4 에서 S7 앞으로** — 시연·심사 로그인이 독자 프로토콜이면 안 된다 | 중 | 3~4주 |
| 12 | **S7** | **관리자 인증 + 최소 관리 콘솔** — 관리자 I&A(2단계)·보안관리자/감사자 분리·온보딩·프로파일 편집·감사 조회 (`execution-plan.md` P1) | 중 | 4~6주 |
| 13 | **S9** | **개명 마무리(4b·5) + 에디션 패키징 + 1.0 동결** — 설정 키·DB 이름 idem 화, Core/KR 이미지·Helm 분리, 온보딩 가이드, 요구사항 체크리스트 → GS 문서 착수 | 중 | 3~4주 |
| 14 | — | 플랫폼 소개서 재작성 (1.0 동결 후, 제품 그대로) | 낮음 | 1주 |

의존: S1 → … → S5 → **D1 → D2 → S8-a → S8-b → S6 → S7 → S9** → 소개서. 합계 약 4~5개월. S7 은 `execution-plan.md` P1 과 같은 작업이고, S9 의 1.0 동결이 `execution-plan.md` P3(GS) 의 입력이다.

**왜 이 순서인가**: D1·D2 를 앞에 두는 것은 뒤 단계 전부가 그 위에 쌓이기 때문이다(S8 을 먼저 하면 MariaDB 위에 할당 모델을 짓고 다시 옮긴다). **v0.4 (2026-09-24, 적대적 점검)**: hub 가 31k LOC·커버리지 38% 인 상태라 기능(할당·역할)을 얹기 전에 SMES 개념을 먼저 빼야 한다(S8-a → S8-b). S6 을 S7 앞으로 당긴 이유는 점검에서 드러난 사실 때문이다 — gate 의 discovery 문서가 Keycloak URL 을 그대로 내보내고 기관 연동 가이드의 표준 OIDC/SAML 경로가 "향후 지원" 이라, 콘솔 시연의 로그인이 독자 Handoff 로 남으면 심사원에게 설명할 것이 하나 더 는다. 콘솔(S7)은 표준 경로 위에 짓는다.

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

### 2.4 에디션과 제품 구성 (v0.3)

**제품 3개** (D1 에서 재편):

| 제품 | 모듈 | 책임 |
|---|---|---|
| **Idem SSO** | `idem-hub` + `idem-gate` (+ 숨긴 Keycloak) | OIDC/SAML 발급·세션·SLO·인증수준 승격·본인확인 SPI·기관 연계(Handoff/OIDC_RP) |
| **Idem IM** | `idem-registry` + `idem-authz` | 사용자·식별자·자격증명·동의·생명주기·할당·역할·SCIM |
| **Idem KR Public Edition** | `editions/idem-kr-hub`·`editions/idem-kr-registry`(S8-a) + `plugins/idem-plugin-nice-oacx`·`idem-plugin-anyid`·KR 시드 | 본인확인 벤더·AnyID·CI/DI·SMES 회원 개념(CI 조회·기업인증·회원전환·회원조회·기업회원)·한국 정책(휴면·파기) |

`idem-relay`·`idem-agent`·`idem-tenant-sample`·`idem-sdk-java` 는 **제품 밖**(운영 도구·샘플·SDK)으로 표시하고 GS 대상에서 뺀다.

- **Idem Core** = SSO + IM, 플러그인 0(Mock 인증)·이메일/전화 스킴·표준 프로토콜. 공개 저장소. bootJar: `:idem-hub`·`:idem-registry`.
- **KR Public Edition** = Core + KR 확장 모듈(`editions/`, 코어를 의존하며 코어는 에디션을 모른다) + KR 플러그인. bootJar: `:idem-kr-hub`·`:idem-kr-registry`, 설치본 `IDEM_EDITION=kr`. 에디션 = 어느 bootJar 를 실행하느냐. 벤더 SDK·자격증명만 사설(`vendor-plugin-plan.md` P5).
- 운영기관 고유값은 전부 **설치 시 입력**(Helm values·환경변수·Service Profile), 코드·마이그레이션 기본값에는 남기지 않는다.
- **Keycloak 은 설치본 내부 구성요소**다. 설치자에게 노출되는 것은 Idem 설정뿐이며, Keycloak realm·client 는 Idem 이 프로비저닝한다(S6). CC 로 갈 때 자체 IdP 로 교체할 수 있도록 토큰 발급·세션 경계는 Idem 코드 뒤에 둔다.

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

### S5 ✅ — 벤더 코드 플러그인 이관 (S5a NICE/OACX · S5b AnyID, PR #222)

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

### D1 — 다이어트: 설치 복잡도 줄이기 (2~3주, v0.3 신설)

새 기능 없이 줄이기만 한다. GS 시험기관이 설치 단계에서 시간을 다 쓰지 않게 하는 것이 목적이다.

- **DB 엔진 1종**: `idem-registry` 를 MariaDB → PostgreSQL 로 이관(Flyway 스크립트 재작성, JPA 방언·`IF NOT EXISTS` 구문 정리, Testcontainers 를 PostgreSQL 로). hub 와 같은 인스턴스의 별도 스키마(`qim`)를 기본으로.
- **Kafka 선택 의존**: 아웃박스 릴레이·감사 발행·FE 세션 advisory 가 Kafka 없이 DB 폴링만으로 완결되게 하고, `idem.messaging.kafka.enabled=false` 가 기본. Kafka 는 다중 인스턴스 배포 옵션.
- **제품 3개로 재편**(§2.4): Gradle 그룹·이미지·Helm 차트를 SSO / IM / KR 에디션으로 정리. relay·agent·tenant-sample·sdk-java 는 `tools/`·`samples/` 로 옮기거나 "제품 밖" 으로 표시.
- **단일 설치본**: 컴포즈 하나(hub·gate·registry·authz·PostgreSQL·Redis·Keycloak) 로 처음부터 로그인까지. Keycloak realm 은 설치 시 자동 프로비저닝.
- Redisson·Lettuce 이중 Redis 클라이언트 정리, 헬스체크 `readiness` 항목을 db·redis·keycloak 으로 고정.
완료 기준: 새 환경에서 `docs/install.md` 한 장으로 30분 안에 설치·로그인. registry 통합 테스트가 PostgreSQL Testcontainers 로 CI 에서 돈다. Kafka 없이 hub·registry 가 기동해 스모크 통과.

**진행 기록 (2026-09-21)** — D1-a 구현 PR (registry PostgreSQL 이관):
- ✅ `idem-registry` 를 PostgreSQL 16 으로: hub 와 같은 인스턴스(`onepass`)의 스키마 `qim`(`QIM_DB_SCHEMA`, JDBC `currentSchema`, Flyway `schemas/create-schemas`). Flyway 스크립트를 벤더별 디렉터리(`db/migration/{vendor}`)로 나누고 `postgresql/V1__baseline_registry.sql` 에 MariaDB V1~V9 최종 스키마를 단일 기준선으로 옮겼다(DATETIME(6)→timestamptz(6), JSON→jsonb, TINYINT(1)→boolean, 부분 인덱스 `outbox(status='PENDING')`). 종전 MariaDB 는 spring profile `mariadb` 로 1 릴리스 유지(`ddl-auto=none`)
- ✅ 코드: JSON 컬럼 3개에 `@JdbcTypeCode(SqlTypes.JSON)`(hub 와 같은 방식), 네이티브 SQL 의 `NOW(6)`→`CURRENT_TIMESTAMP`. `idem-relay` 의 qim DataSource 도 PostgreSQL(`QIM_DB_URL`/`QIM_DB_DRIVER` 로 MariaDB 호환). 드라이버·flyway-mysql·testcontainers:mariadb 는 runtimeOnly/제거
- ✅ **부수 발견(운영 결함)**: `user_status_history` INSERT 가 PK `history_id` 를 넣지 않아 MariaDB 에서도 항상 실패하고 있었다(예외를 warn 으로 삼켜 "비치명적" 처리 → 상태 전이 이력이 한 건도 남지 않았음). Java 에서 UUID 를 넣고 PostgreSQL 기준선은 `DEFAULT gen_random_uuid()` 도 둔다
- ✅ 테스트: `OutboxIntegrationTest`·`QimLifecycleIntegrationTest` 를 PostgreSQL Testcontainers 로(MariaDB `DATABASE()` 단정은 `current_schema()`), `MapsIdPersistRegressionTest` H2 는 `MODE=PostgreSQL`. registry 245건·relay 10건 통과(샌드박스 로컬 PostgreSQL 에 기준선 적용·`ddl-auto=validate` 통과)
- ✅ 인프라·문서: compose 4종에서 MariaDB 서비스·볼륨·Adminer 제거, `init-db.sql` 에 `qim` 스키마, registry 컨테이너 env 를 postgres 로. 운영 매뉴얼·배포 README·개요·README 갱신. 이관 도구 `scripts/registry-db-migrate/`(테이블별 복사 + 행수·PK 순 행 해시 대조, `--verify-only`)
- ⏭ **MariaDB 실데이터 리허설은 이 환경(MariaDB 없음)에서 못 했다** — 스테이징에서 `README.md` 절차 3 을 먼저 수행. D1 나머지(Kafka 선택 의존·제품 3개 재편·단일 설치본·`docs/install.md`)는 다음 PR

**진행 기록 (2026-09-21)** — D1-b 구현 PR (Kafka 선택 의존 · 제품 3개 재편 · 단일 설치본):
- ✅ **마스터 스위치** `idem.messaging.kafka.enabled` (`IDEM_KAFKA_ENABLED`, **기본 false**). `idem-common` 자동 설정(`messaging/`): 꺼지면 `KafkaTemplate` 자리에 `DisabledKafkaTemplate`(프로듀서를 만들지 않고 실패 Future — 브로커 대기 0초), 모든 `@KafkaListener` 컨테이너 팩토리 `autoStartup=false`(빈 후처리), `EnvironmentPostProcessor` 가 파생 기본값(리스너·토픽 검사 해제, gate·registry·relay 의 Kafka 릴레이와 hub F-30·F-03 정지)을 env 바로 아래 우선순위로 주입. hub·gate·registry 의 `KafkaTopicConfig`/`KafkaProducerConfig` 는 `havingValue="true"` 조건
- ✅ **hub 는 아웃박스를 프로세스 내로 배달**: `IdoOutboxRelay`(F-13) 가 Kafka 대신 `InProcessOutboxDispatcher`(REQUIRES_NEW) 로 토픽별 컨슈머 진입점 `handle()` 을 호출 — `qsign.auth.events`→`QsignAuthEventConsumer`, `platform.session.advisory`→`FeAdvisoryConsumer`, `ido.handoff.events`→`HandoffEventConsumer`. 멱등 처리(`processed_event`)·백오프 재시도는 그대로. `SessionAdvisoryPublisher` 는 아웃박스 직행, Handoff 는 새 `HandoffEventPublisher` 가 아웃박스에 적재(티켓 트랜잭션과 원자적 — 종전 Kafka 직접 발행보다 유실이 적다). 감사는 DB 저장(F-04)만. `FeatureFlags` F-31 로 상태 노출
- ✅ **부수 발견(결함 3건, 모두 "Kafka 컨슈머 경로가 한 번도 실제로 돈 적이 없다" 는 뜻)**: ① `DomainEvent` 하위 7종이 Jackson 으로 역직렬화될 수 없었다(생성자만 있고 creator 없음) — `JsonDeserializer`·`convertValue` 가 애초에 실패한다 → `@Jacksonized` + `DomainEventJacksonRoundTripTest` 가드. ② `IdempotentEventStore.markProcessed` 가 `java.time.Instant` 를 JDBC 파라미터로 넘겨 pgjdbc 가 "Can't infer the SQL type" 로 항상 실패 — 모든 멱등 컨슈머가 처리 뒤 완료 마킹에서 예외 → `NOW()` 로. ③ `idem-gate`·`idem-registry`·`idem-authz` Dockerfile 이 S5b 이후 `plugins/idem-plugin-anyid/build.gradle.kts` 를 복사하지 않아 이미지 빌드 불가 → 수정
- ✅ **샌드박스 실기동 검증**: hub 부트 jar 를 Kafka 없이(로컬 PostgreSQL·Redis, Mock 제공자) 기동 → 16초 만에 UP, 브로커 접속 시도 0건, `MOCK initiate→complete→registry 등록` 200, 통합 테스트가 남긴 `ido.outbox` PENDING 309건(`ido.handoff.events`)이 릴레이의 프로세스 내 배달로 8초 안에 전부 PUBLISHED, `processed_event` 309건 기록. 단위·통합 테스트: common 30·hub 460+IT 42·gate·registry 245·relay 10·tenant 84·authz 29·플러그인 54 모두 통과
- ✅ **CI smoke 잡에서 Kafka 서비스 제거** — 브로커 없는 hub 기동 + k6 스모크가 PR 게이트. `AesSharedKeyDecryptorTest` 의 1/256 확률 패딩 플레이크도 결정적으로
- ✅ **단일 설치본** `infra/docker/compose.install.yml`(postgres·redis·keycloak·gate·registry·hub·authz·console, Kafka 없음, 앱 포트 127.0.0.1 바인딩) + `install.env.example` + **`docs/install.md`**. Keycloak realm import 의 client secret 을 `${env.…}` 로 바꿔 설치 시 값 주입. 기존 전체 스택(compose·Helm)은 `IDEM_KAFKA_ENABLED=true` 명시
- ✅ **제품 3개 재편**은 `settings.gradle.kts` 그룹·주석으로만(SSO: hub·gate / IM: registry·authz / KR: plugins / 제품 밖: console·relay·agent·tenant-sample·sdk). 디렉터리 이동·이미지·Helm 분리는 S9 패키징에서
- ⏭ **Kafka 꺼진 배포에서 흐르지 않는 것** (`docs/install.md` §6): registry→hub/gate `qim.user.events`(캐시 무효화·탈퇴 잠금 전파; `qim.outbox` PENDING 잔류, hub 는 TTL 캐시로 완화), gate 발행분 `qsign.auth.events`(gate 아웃박스 PENDING 잔류), hub F-30, relay 잡, tenant-sample 컨슈머. 같은 PostgreSQL 이므로 **D1-c 후보: hub 가 `qim.outbox`·gate 아웃박스를 직접 폴링**. 단일 인스턴스 기본, 다중 인스턴스는 Kafka=true
- ⏭ Redisson·Lettuce 이중 클라이언트 정리, readiness 항목 고정(db·redis·keycloak)은 미착수. **`compose.install.yml` 실제 기동은 Docker 없는 이 환경에서 못 했다** — 첫 설치자가 `docs/install.md` §9 에 기록

### D2 — fail-secure 전수 점검 + 암호 경계 단일화 (1~2주, v0.3 신설)

CC·GS 보안 항목 모두 여기서 걸린다. TSF 는 안전하게 실패해야 한다.

- **fail-open 제거**: `AuthRateLimitInterceptor`(Redis 오류 시 통과 + Lua 인자 직렬화 결함), AnyID 서버 장애 시 로컬 팝업 URL 폴백, FE 세션 생성 실패 시 임시 ID, `idem-authz` fail-open, 기타 "PoC 환경 허용" 분기 전수 조사. 원칙은 "외부 의존 실패 = 거부 + 감사 기록".
- **암호 경계**: AES/HMAC/서명/PII 암호화/해시 호출을 `CryptoProvider` SPI 하나로 모은다(`KmsClient` 는 키 보관, `CryptoProvider` 는 연산). 지금은 JCA 구현, `execution-plan.md` P2 에서 KCMVP 모듈로 교체. JCA 직접 호출 0건을 가드 테스트로.
- 벤더 자격증명 히스토리 잔존 → 키 교체 요청 완료 확인(문서에 기록).
완료 기준: fail-open 목록이 비고, `GeneralizationGuardTest` 급의 `CryptoBoundaryGuardTest` 가 코어의 `javax.crypto`·BouncyCastle 직접 참조를 막는다.

**진행 기록 (2026-09-21)** — D2-a 구현 PR (fail-secure 전수 점검). 조사: 코어·플러그인 main 에서 fail-open/PoC 관용 경로 45건, 암호 호출 지점 62곳(→ D2-b).
- ✅ **거부로 바꾼 것 (P0·P1)**: `AuthRateLimitInterceptor` — Lua 인자 JSON 직렬화 결함으로 **상시 무력화**되어 있던 IP 레이트리밋을 고치고 Redis 장애는 503+감사(`RATE_LIMIT_BACKEND_UNAVAILABLE`); `AgencyRateLimiter` Redis 장애 503; `NonOidcBrokerAdapter` "PoC: 항상 통과" 응답 검증 → `NonOidcProviderVerifier` SPI 필수(검증기 없는 사업자는 시작부터 503, 실패는 서명 불일치); `OidcCompleteController` CI 없는 요청의 identifierHash→qimUserId 폴백 → 422(`IDO_IDENTITY_UNRESOLVED`); `PolicyEngineImpl` 상태 조회 실패 시 ACTIVE 가정 → 정본 조회·실패 시 거부; `CallbackUrlValidator` 화이트리스트 없음 → 거부; `AgencyMetaRepositoryImpl` `min_auth_level` 오류 → L3, JSON 손상 → 예외; `DirectBrokerAuthCompletion` 가짜 세션 ID → 503(SPI 계약 수정); `QAuthzClient` 장애 시 빈 역할 → 503(`IDO_AUTHZ_UNAVAILABLE`), authz 없는 설치는 `ido.q-authz.enabled=false` **명시**; `ExtProxyController` 세션·인가 조회 실패 시 헤더 없이 프록시 → 503; 웹훅 `event_type_filter` 손상 → 발송 보류; 게이트웨이 아웃바운드 무서명 발송 → 예외; `BrokerService`·`SloServiceImpl` 빈/더미 서명 → 예외; registry `/api/v1/users/**` 무인증 → 내부 키 필수, `di_map` 손상 → 예외; anyid 플러그인 서버 장애 시 로컬 팝업 폴백 → 502
- ✅ **부팅 가드**: hub `FailSecureBootGuard` — 내부 서명키 없으면 모든 프로파일에서 기동 거부, `prod`/`stage` 에서는 탈출구 12개 중 하나라도 켜져 있거나 감사·보안헤더·레이트리밋·락이 꺼져 있으면 기동 거부(위반 목록 출력). `CastKeyConfig` 임시 키 생성·`AesSharedKeyDecryptor` placeholder 부팅 통과·`QAuthzClient` 키 누락은 각각 명시적 탈출구(기본 false)로만 허용. gate·registry·authz 는 prod/stage 에서 서명키·내부 키 미설정 시 기동 거부. mock-auth 플러그인은 `!prod & !stage` 프로파일 전용. gate 의 하드코딩 서명 기본값(`ido-internal-secret`) 제거, dead config `internal-sig-strict-mode` 삭제
- ✅ 새 에러 코드 E-IDO-116~119. 테스트: 기존 7건 갱신 + `FailSecureBootGuardTest`·`AuthRateLimitInterceptorTest`·`NonOidcBrokerAdapterTest`. hub 475·IT 42·gate 80·registry 245·authz 29·플러그인 54 통과. 설치본·개발 스택·CI·Helm 에 새 필수 값(CAST 키·registry 공유키·authz 키/스위치) 반영, 운영 매뉴얼 §3.4
- ⏭ **남긴 것**: 감사 경로 자체의 신뢰성(H-16: DB 실패 시 WAL 폴백, 동기 기록) — S7 관리 콘솔·감사 조회와 함께; `KeyVersionRegistry` 버전 폴백; SLO 2단계 실패 재시도 큐; `HandoffAgencyKeyInterceptor` 솔트 없는 SHA-256 → PBKDF2 이관(D2-b); `ProviderRouter` suffix 휴리스틱; 기관 사업자번호 체크섬; **CryptoProvider SPI + 62곳 이관 + `CryptoBoundaryGuardTest` 는 D2-b(다음 PR)** — 조사 결과의 SPI 설계(키 참조 기반, `SealedValue` 3포맷 호환, jjwt 파사드, 플러그인 제외)를 그대로 쓴다

**진행 기록 (2026-09-21, D2-b)** — CryptoProvider SPI + 호출부 이관 PR. **D2 완료.**
- ✅ **`CryptoProvider` SPI** (idem-common `crypto/`): `sha256*`·`hmacSha256*`·`aesGcmEncrypt/Decrypt`(ct||tag, 128-bit 태그)·`aesCbcEncrypt/Decrypt`(레거시 Q-IM 공유키)·`pbkdf2HmacSha256`·`randomBytes/Token/Hex/Int`·`constantTimeEquals`·`generateKeyPair/decodePrivateKey/decodePublicKey/rsaPublicKey`·`sign/verify`. **키 재료는 호출자가 `byte[]` 로 넘긴다** — 조사 때의 키 참조(KeyRef) 설계는 버렸다. KMS·`KeyVersionRegistry` 가 이미 키를 바이트로 들고 있고, KCMVP 모듈 어댑터도 키 바이트를 받는 인터페이스라 참조 계층이 하나 더 있을 이유가 없었다. 포맷·버전(`SealedValue` 3포맷, v1/v2 키 버전, `sha256=` 접두)은 호출부에 그대로 둔다
- ✅ 기본 구현 `jca/JcaCryptoProvider` 가 **JCA 를 호출하는 유일한 자리**. `CryptoAutoConfiguration` 이 빈으로 등록하고 `CryptoProviders.install()` 로 정적 접근자(`CryptoProviders.current()`)에 꽂는다 — 인터셉터·정적 유틸(`ApiKeyHashUtil`, `SignaturePayloadBuilder`)·`@Configuration` 초기화 시점에서도 쓰기 위해. KCMVP 교체는 `CryptoProvider` 빈 하나를 바꾸면 끝(`@ConditionalOnMissingBean`)
- ✅ **호출부 이관 63곳** (common 3 · hub 26 · gate 6 · registry 5 · authz 1 · relay 1 · mock 플러그인 1): `HandoffCryptoService`(중첩 `MessageDigestUtil` 제거), `AesSharedKeyDecryptor`(GCM/CBC 변환 문자열 화이트리스트 → 부팅 시 검증), `CiCryptoServiceImpl`, `CastKeyConfig`(Ed25519), `KeycloakJwksVerifier`(hub/gate, RSA n/e → `rsaPublicKey`·`verify("SHA256withRSA")`), `PkceService`, `DiGenerationService`, 내부 서명·API 키 인터셉터 전부, 상태/nonce 생성(`randomHex`), 세션·토큰 생성(`randomToken`). `ConversionInitService` 의 jjwt 키는 JWT 계층 소유로 두고 타입 참조만 제거
- ✅ **`CryptoBoundaryGuardTest`** (idem-common): common·gate·registry·hub·authz·relay 의 `src/main/java` 에서 `javax.crypto.*`·`java.security.{MessageDigest,SecureRandom,Signature,KeyFactory,KeyPairGenerator,spec.*}`·`org.bouncycastle`·`*.getInstance(` 를 금지, 허용은 `common/crypto/jca/` 뿐. `JcaCryptoProviderTest` 는 SHA-256/HMAC 알려진 벡터(FIPS 180-4 "abc", RFC 4231 case 2)·GCM/CBC 왕복·PBKDF2·Ed25519/RSA 서명·`install` 교체를 검사
- ✅ 검증: common 420 · hub 475 + IT 42 · gate 81 · registry 245(IT 29 포함) · authz 29 · relay · 플러그인 54 통과(`--rerun-tasks`)
- ⏭ **범위 밖으로 둔 것**: `idem-sdk-java`(`HmacSigner`, 무의존 SDK 라 idem-common 을 못 끌어옴)·`idem-agent`(`OnePassHttpClient`, 자바에이전트)·`idem-tenant-sample`(기관 측 예제)·플러그인(`NiceCryptoUtil` 벤더 규격, `AnyIdBrokerAdapter`) — 인벤토리에 남기고 KCMVP 도입 시 SDK 는 별도 판단. `HandoffAgencyKeyInterceptor`·`AgencyAdminService` 의 **무염 SHA-256 API 키 해시 → PBKDF2** 는 저장 포맷 변경(기존 키 재발급 또는 이중 검증기)이 필요해 S7 관리 콘솔의 기관 키 재발급과 함께. relay `WebhookRelayJob` 의 "서명키 없으면 무서명 발송" 은 제품 밖 모듈이라 그대로(D2-a 원칙 적용은 relay 정리 때)

### S8-a — IM 을 얇게 (빼기): SMES 회원 개념을 KR 에디션으로 (1~2주, v0.4 에서 분할)

hub 31k LOC 위에 기능을 얹기 전에 뺀다. SMES 회원 유형(개인/기업)·회원구분코드(`mbrDvsnCd`)·CI 조회·기업인증 콜백·회원전환(`conversion`)·기관 회원조회(`memberlookup`)·기업회원(`biz_member`)을 **`editions/idem-kr-hub`·`editions/idem-kr-registry`** 로 옮기고, 코어의 registry 계약을 스킴 중립으로 바꾼다.
완료 기준: 코어 hub·registry 가 SMES 개념 없이 기동·테스트 통과(KR 엔드포인트 404), KR 에디션에서 종전 시나리오 통과, 코어에 SMES 토큰이 다시 못 들어오는 가드.

**진행 기록 (2026-09-24, S8-a)** — 구현 PR.
- ✅ **에디션 모듈**: `editions/idem-kr-hub`(`:idem-kr-hub`)·`editions/idem-kr-registry`(`:idem-kr-registry`) — Spring Boot 부트 모듈로 코어(`:idem-hub`·`:idem-registry`)를 의존하고 mainClass 는 코어의 것을 쓴다. 패키지 `io.github.hipstermin.idem.hub.kr.*`·`registry.kr.*` 는 코어 앱의 스캔 범위 안이라 jar 만 있으면 활성화된다. **코어는 에디션을 모른다**(빌드 의존 단방향, 가드로 강제). 에디션 = 어느 bootJar 를 실행하느냐 — `IDEM_EDITION=core|kr` 로 Dockerfile·설치본이 고른다
- ✅ **hub 에서 뺀 것 (35개 파일)**: `auth` 패키지 전체(CI 조회 `AuthController/AuthService`, `CiCheck*`·`CiTokenExchange*`·`AuthCallback*`·NICE 모양 `AuthResult`, 기업인증 `IntegrationAuthClient`, `ImApiOutPort/Adapter`, 구 벤더 프록시 `auth/legacy`, `MemberDivisionCode*`·`MemberDivisionPolicy`), `conversion`, `memberlookup`, FE 회원전환 세션(`/api/v1/fe-session/conversion`). 코어에 남는 본인확인 SPI 경로는 `identity/spi`·`identity/audit`·`identity/tracing` 로 옮겼다
- ✅ **코어 계약 스킴 중립화**: `QimClient` 의 CI 전용 `registerUser(AuthResult)`·`findByCi(ci, memberType)` 를 없애고 `registerSubject(SubjectRegistration)`·`findByIdentifierHash(hash)` 만 남겼다. `QimMemberInfo` 는 `qimUserId`·`status` 만(개인/기업 회원 ID 는 registry 가 돌려준 적이 없었다 — 항상 null 이던 것을 계약에서 지웠다). `OidcCompleteRequest` 에 `subjectScheme/subjectKey` 를 추가하고 구 `ci` 는 CI 스킴 별칭으로 받는다(gate 호환). KR 어댑터가 CI 해시·`SubjectRegistration(scheme=CI)` 변환을 맡는다
- ✅ **registry 에서 뺀 것 (8개 파일)**: `biz/*`·`BizMemberJpaEntity/Repository`·`BizMemberConversionController`·`MemberLookupController`(lookup-by-ci). `biz_member` DDL 은 V1 베이스라인에서 빼고 KR 마이그레이션 `db/migration/kr/postgresql/V1000_1__kr_biz_member.sql`(버전 1000+ 대역, `FlywayConfigurationCustomizer` 로 location 추가)로. **후견·미성년 동의는 코어에 남겼다** — 연령 기반 보호자 동의는 KR 만의 개념이 아니다(COPPA·GDPR-K)
- ✅ **설정**: 코어 `application.yml` 에서 `ido.conversion.*`·`ido.fe-aes-gcm-key`·`ido.auth.integration.*`·`integration-auth-client` resilience 인스턴스를 빼고 KR 은 `KrHubEditionEnvironmentPostProcessor`(spring.factories, 최하위 우선순위) 기본값으로 공급. retry 인스턴스는 정의하지 않는다(resilience4j 2.2 의 "intervalFunction configured twice" 회피)
- ✅ **가드**: `GeneralizationGuardTest` 에 KR 가드 2건 — 코어 main 에 SMES 토큰(`mbrDvsnCd`·`bizno`·`cmpMbrId`·`indvlMbrId`·`entMbrNo`·`biz_member`·`BizMember`·`MemberLookupService`·`ConversionInit`·`hub.kr.` 등) 금지, 코어 `build.gradle.kts` 가 에디션 모듈을 의존하지 않을 것. 허용 목록(줄어야 하는 것): hub `qim/sp`(SMES SP 수신기 — S8-b), 적용된 Flyway 이력, MariaDB 구 이력(S9 제거)
- ✅ **테스트 인프라**: hub `IntegrationTestBase`·테스트 프로파일 설정을 `java-test-fixtures`(`src/testFixtures`)로 옮겨 KR 에디션 테스트가 공유. 테스트 기본 설정은 `application-hub-test-base.yml` 을 각 모듈의 `src/test/resources/application.yml` 이 `spring.config.import` 로 가져온다(클래스패스 순서에 따라 코어 main yml 이 섞여 들어오던 문제를 이걸로 잡았다). 이동 테스트 6개 + 신규 `KrAuthIntegrationTest`(레거시 프록시·CI-check·콜백·KR 엔드포인트 존재)·`KrBizMemberLifecycleIntegrationTest`(코어 V1 뒤 KR V1000.1 적용 확인)·`KrBizMemberMapsIdPersistTest`. 코어 `NiceAuthIntegrationTest` 는 KR 엔드포인트 7개가 404 인지 확인
- ✅ **코어 결함 2건 수정(점검 중 발견)**: hub `GlobalExceptionHandler` 가 없는 경로(`NoResourceFoundException`)와 필수 헤더·파라미터 누락·본문 파싱 실패를 모두 500 으로 바꾸고 있었다 → 404 `E-IDO-404`·400 `E-IDO-400`. 종전 테스트가 "4xx 또는 5xx" 로 이를 덮고 있었다
- ✅ **패키징·CI**: hub·registry Dockerfile `ARG IDEM_EDITION=core|kr`(모듈 선택), `compose.install.yml` 빌드 인자 + 이미지 태그 `-core|-kr`, `install.env.example` `IDEM_EDITION`, 4개 Dockerfile 에 editions 빌드 파일 COPY, CI k6 스모크는 코어 jar 로 `IDEM_EDITION=core`(KR 엔드포인트 404 검사), pre-push 훅에 에디션 모듈·의존 매핑
- ✅ 검증: 코어 hub 444 + IT 39 · KR hub 32 + IT 5 · registry 218(IT 24) · KR registry 28(IT 6) · gate 81 · authz 29 · common 418 · 플러그인 54 · relay 10 · tenant-sample 84 통과. 실제 bootJar 기동: KR jar 는 `/api/v1/auth/nice/ci-check` 400·`/conversion/init` 400, 코어 jar 는 전부 404, 공통 `/auth/providers` 200
- ⏭ **남긴 것**: hub `qim/sp`(SMES SP 수신기, mbrUuid/entMbrNo·BIZ/PERSONAL — `WebhookDispatcherService`·`SloServiceImpl` 결합) → S8-b 에서 범용 기관회원매핑으로 정리하거나 KR 로; `PlatformErrorCode` 의 `IM_BIZ_*`·`CONVERSION_*` 코드는 공용 카탈로그에 남김; k6 KR 시나리오(`k6/scripts/02-auth.js` 등)는 KR 에디션 jar 로 수동 실행; 콘솔 프런트의 conversion·ci-check 훅은 S7 콘솔 분리 때; 관련 문서 다수(`docs/internal/*`)가 여전히 구 패키지 경로를 적고 있다 — S9 문서 정리

### S8-b — 할당·역할 모델 (더하기) (2~3주, 위험 높음)

- **할당(assignment)**: 사용자/그룹 ↔ Service (직접 · 그룹 · 속성 규칙). 미할당이면 발급 거부 — 정책 엔진에 내장 규칙 `ASSIGNMENT` 추가(S3 SPI). GUEST 는 Service Profile 이 셀프 가입을 허용할 때만 허용.
- **역할**: Service 별 앱 역할(app role) 정의·부여, 어설션(Handoff/OIDC 클레임)에 싣기. 세밀 인가는 `idem-authz` 를 PDP 로 정리(fail-open 제거, SCIM Groups 와 연결). 기존 `authz_role`/`authz_user_role` 위에 Service 단위로 최소 구현.
- **DI/GUEST 정리**: PAIRWISE 식별자는 첫 발급 시 생성(표준 pairwise sub 와 동일) 으로 의미를 고정하고 "DI 없음 → GUEST" 서술 제거.
- S8-a 잔여: hub `qim/sp` 정리, tenant·ServiceProfile·agency 세 개념 정돈(코어에 "기관" 105개 파일).
완료 기준: 미할당 사용자의 Handoff/OIDC 발급이 거부되는 통합 테스트, KR 에디션에서 기존 시나리오 통과.

**진행 기록 (2026-09-24, S8-b PR-1: 할당 모델 + `ASSIGNMENT` 규칙)** — 구현 PR.
- ✅ **할당 모델(authz)**: `authz.authz_assignment`(사용자 ↔ Service(agency_code), `status ACTIVE/REVOKED/EXPIRED`, `source CONSOLE/SCIM/API/AGENCY_PUSH/ROLE_GRANT/SELF_SIGNUP`, `expires_at`, 부여·회수 주체) — `V3__assignment.sql`. 기존 `authz_user_role` 의 ACTIVE 행을 `ROLE_GRANT` 할당으로 **백필**하므로 이미 역할이 있는 사용자는 마이그레이션 직후에도 할당 상태다. 역할 부여(`grantRole`)는 할당을 자동 보장하고, 만료 배치는 할당도 만료시킨다(감사 `ASSIGN/UNASSIGN`). 내부 API: `POST/DELETE /api/v1/internal/authz/assignments`, `GET /users/{id}/access?agencyCode=` → `{assigned, assignmentSource, roles}`(멱등)
- ✅ **정책 규칙 `ASSIGNMENT`(순서 95, `USER_STATUS` 뒤)**: Service Profile `policy.assignment { required, selfSignup }`(스키마 v1 확장, `rules[]` 파라미터로도 덮어씀). 의미 — `required` 가 아니면 ALLOW(기본, 기존 프로파일 무변경) · 할당됨 → ALLOW(`APPROVED`) · 미할당 + `selfSignup` → ALLOW 하되 상태 `GUEST`(주체 ID 는 그대로 실린다) · 미할당 → DENY **`E-IDO-120`**(403, 감사 `ASSIGNMENT_REQUIRED`) · `required` 인데 authz 비활성 → DENY `E-IDO-117`(fail-closed). authz 조회는 `PolicyContext.serviceAccess` 공급자로 지연·메모이즈되어 규칙이 요구할 때만 한 번 호출된다. CAST(hub 간 SSO)도 대상 프로파일이 `required` 면 미할당을 거부한다
- ✅ **역할을 어설션에**: `HandoffPayload.roles`(최상위)·`subject.assigned`. 종전에는 역할이 암호화 티켓 안에만 있고 `verify` 응답에는 없었다(결함) — `ServiceAccess` 한 번 조회로 Payload·plain 응답 모두에 싣는다. 시뮬레이션 API 요청에 `assigned` 추가
- ✅ 검증: hub 460 + IT 43(신규 `AssignmentIntegrationTest` 4 — 403/E-IDO-120 · GUEST 셀프가입 · APPROVED+roles · authz 5xx→503) · authz 41 · common 418 · gate 81 · registry 218 · kr-hub 32 · kr-registry 28 · 플러그인 54 · relay 10 · tenant-sample 84. V1→V3 마이그레이션을 로컬 PostgreSQL 에서 빈 스키마·기존 데이터 양쪽에 적용해 백필 확인
- 배운 것: `ServiceProfile.Policy` 레코드에 컴포넌트를 더할 때 `ServiceProfileMapper.fromEntity` 병합이 새 항목을 잃는다(테스트로 고정) · 레코드 접근자에 `is*`/`get*` 이름 + `@JsonIgnore` 를 붙이면 Jackson 이 본 속성까지 지운다(비게터 이름으로) · 레코드 호환 생성자는 Jackson 이 생성자를 고를 때 모호해지므로 두지 않는다
- ⏭ **남긴 것(S8-b PR-2)**: 그룹·속성 규칙 할당(현재 직접 할당만) · 할당 변경 이벤트 전파(아웃박스, SCIM Groups 연결) · authz fail-open 잔여 정리(`isEnabled=false` 경로) · hub `qim/sp` 정리 · tenant·ServiceProfile·agency 개념 정돈 · DI/GUEST 서술 정리(GUEST = "미할당 셀프가입" 으로 재정의됨, 가이드·운영 문서 반영) · 콘솔 할당 화면은 S7

### S6 — 표준 프로토콜: Keycloak 을 숨긴 OIDC_RP 정식화 (3~4주, v0.3 에서 축소, v0.4 에서 S7 앞으로)

Keycloak 유지 결정(§0)에 따라 자체 IdP 는 만들지 않는다. `IntegrationProtocol` SPI · `OIDC_RP`: Service Profile 의 `protocol.type=OIDC_RP` 만으로 Idem 이 Keycloak client 를 프로비저닝하고 정책 강제 authenticator 를 붙인다 · Keycloak 관리 UI 는 설치자에게 노출하지 않는다 · Handoff 는 "KR 기관 연계 방식" 으로 격하하되 유지 · Agent(`APACHE_GATE`) 는 제품 밖 도구로 · `SAML_SP` 는 설계만 · `idp-hint-mapping` 을 프로파일로.
완료 기준: tenant-sample 이 `protocol.type` 변경만으로 Handoff·OIDC_RP 두 방식 통과, Keycloak 에 사람이 손대는 단계 0.

### S7 — 관리자 인증 + 최소 관리 콘솔 (4~6주, `execution-plan.md` P1 과 동일 작업, v0.4 에서 S6 뒤로)

관리자 I&A(2단계 인증)·보안관리자/감사자 권한 분리·세션 잠금·패스워드 정책(P1) 위에 기관 목록/온보딩/프로파일 편집(스키마 기반 폼)/정책 시뮬레이션/감사 조회. `idem-console` 의 SigNoz 잔재 정리 후 **관리 앱과 사용자 포털 분리**(`idem-console-admin`, `idem-portal`). 사용자 포털은 뒤로 미루고, 콘솔의 벤더 훅(EzAuth·AnyID, 일부 죽은 코드)은 `useAuthWidget` 로 정리하거나 제거.
완료 기준: GS 시연 시나리오(설치 → 관리자 로그인 → 기관 온보딩 → 로그인 → 감사 조회)를 콘솔만으로 수행.

### S9 — 개명 마무리 + 에디션 패키징 + 1.0 동결 (3~4주)

- **개명 4b·5**(`naming.md`): 설정 키 `ido.*`/`qim.*` → `idem.*`, 헤더·Redis 접두·환경변수·DB/Keycloak 이름. 구 키는 1 릴리스 호환 계층.
- Core/KR 이미지·Helm values 분리(`vendor-plugin-plan.md` P4) · 기관 온보딩 가이드(프로파일 작성 → 검증 → 시험 → 승인) · **요구사항 수용 체크리스트**(§4) · 설치 시 입력값 목록 · 일회성 회원 이관 도구(KR 에디션).
- **1.0 동결**: 릴리스 브랜치, 설치 매뉴얼·관리자 매뉴얼·기능 명세·시험 항목표 → `execution-plan.md` P3(GS) 착수.
완료 기준: 1.0 태그, GS 시험 신청 서류 초안 완성.

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
- 4b 개명(`agency→tenant`, `ido.*→idem.*`)은 S2 에서 새 API·프로파일 키에 **새 이름만** 쓰고, 구 이름은 호환 계층에만 남긴다. 마무리는 S9.
- D1 의 registry DB 이관은 **데이터 이관 도구 + 리허설**을 같이 낸다(MariaDB 덤프 → PostgreSQL 적재 → 행수·해시 대조). 이관 전까지 MariaDB 경로는 삭제하지 않고 프로파일로 남긴다.

## 6. 리스크

| 위험 | 대응 |
|---|---|
| S4·S8 이 registry 데이터 모델을 건드려 운영 데이터 이관 필요 | 백필 마이그레이션 + 이중 읽기, 이관 리허설을 Testcontainers 로 자동화 |
| OIDC_RP 에서 Idem 정책을 Keycloak 이 우회 | Keycloak client 는 Idem 만 프로비저닝, 정책 authenticator 필수, 직접 등록 금지 |
| 프로파일 스키마가 과도하게 커짐 | v1 은 §2.1 범위로 제한, 확장은 마이너 버전 + 마이그레이터 |
| 벤더 SDK 미수령으로 S5 검증 지연 | Mock 플러그인으로 코어 검증, 벤더 플러그인은 계약 테스트만 |
| CC P1(관리자 인증) 과 S7 의 중복 작업 | 같은 작업으로 취급, `execution-plan.md` P1 을 S7 로 링크 |
| 단계가 길어 shipster 에 미완 작업이 쌓임 | 단계마다 PR·머지, 기능 플래그로 미완 경로 격리 |
| D1 DB 이관이 운영 registry 데이터를 깨뜨림 | 이관 도구·리허설·대조 자동화, MariaDB 경로를 1 릴리스 유지 |
| Kafka 를 빼면서 다중 인스턴스 정합성이 깨짐 | 단일 인스턴스 기본 + DB 폴링, 다중 인스턴스는 Kafka 옵션으로 문서화 |
| Keycloak 을 숨긴 채 유지하다 CC 시점에 TOE 경계가 안 잡힘 | 토큰 발급·세션 경계를 Idem 인터페이스 뒤에 두고(S6), CC 착수 전 자체 IdP 전환 비용을 재산정 |
| fail-secure 전환으로 PoC 환경이 불편해짐 | Mock 플러그인·로컬 프로파일로만 완화, 운영 프로파일에서는 escape hatch 금지 |

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
