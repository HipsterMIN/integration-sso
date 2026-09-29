# Idem — 변경 이력

형식: [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/). 버전은 루트 `build.gradle.kts` 와 태그(`vX.Y.Z`)를 따른다. SDK 는 `idem-sdk-java/CHANGELOG.md`.

## [Unreleased] — 1.1 (shipster)

`docs/post-1.0-plan.md` §5. 1.0.x 패치는 `release/1.0`.

### 1.1 PR-1 · 연합 인가 정합성 + 할당 변경 전파 + SLO 재시도 (S8-b PR-2·D2 "남긴 것")

- **할당 정책 단일 해석기 (`AssignmentPolicyResolver`)**: Handoff 페이로드·OIDC 토큰 교환·CAST·`AssignmentRule` 이 같은 답을 낸다. 프로파일 `policy.rules[{type:ASSIGNMENT, params}]` 의 파라미터는 블록 `policy.assignment` 을 **조이기만** 한다(`required` OR, `selfSignup` AND). 종전에는 규칙 파라미터 `required=false` 로 규칙은 통과시키면서 상태는 할당 기준으로 계산되는 불일치가 있었다. **동작 변경**: 규칙 파라미터로 할당 필수를 풀거나 셀프 가입을 열 수 없다.
- **운영에서 authz 비활성 금지**: `FailSecureBootGuard` 가 prod/stage 에서 `idem.hub.authz.enabled=false`(항상 빈 역할) 를 기동 거부한다 — 코어 = SSO + IM. `application.yml` 의 "fail-open(빈 역할)" 주석 잔재 정정.
- **할당 변경 이벤트 전파**: authz 가 `assign`(신규·재활성)·`unassign`(상태 변경 시)·할당 만료에 아웃박스 이벤트 `AUTHZ_ASSIGNED`·`AUTHZ_UNASSIGNED`·`AUTHZ_ASSIGNMENT_EXPIRED` 를 적재한다(종전에는 역할 부여·회수·만료만). 새 읽기 전용 피드 `GET /api/v1/internal/authz/events`(키셋 `(createdAt,eventId)`, `X-Internal-Api-Key`). hub `AuthzEventPoller`(`IDEM_HUB_AUTHZ_EVENTS_POLL_ENABLED`, 기본 true, Kafka 유무 무관) → `AuthzEventConsumer` 가 `processed_event` 멱등으로 소비해 기관 웹훅 **`ASSIGNMENT_CHANGED`**(`change`=ASSIGNED·UNASSIGNED·ASSIGNMENT_EXPIRED·ROLE_GRANTED·ROLE_REVOKED·ROLE_EXPIRED, `agencySubjectId`, `roleCode`) 를 적재하고 감사(`AUTHZ/ASSIGNMENT_CHANGED`) 한다. 페이로드에 `qimUserId` 없음. authz V5: 아웃박스 topic 기본값을 코드와 맞추고 피드 인덱스 추가.
- **SLO IdP 단계 재시도 큐**: gate `POST /api/v1/internal/session/logout` 은 Keycloak 실패를 204 로 감추지 않고 **502**(+`X-Idp-Logout-Outcome: FAILED`) 로 낸다. hub `IdpSessionRevoker` 가 비 2xx·예외·FAILED 헤더를 실패로 판정해 `idem_hub.slo_idp_logout_retry`(V28) 에 적재하고 `SloIdpLogoutRetryRelay` 가 지수 백오프(10s·20s·40s·80s·160s, `IDEM_HUB_SLO_RETRY_*`) 로 재시도, 초과 시 FAILED + 감사 `SLO_IDP_LOGOUT_FAILED`. 종전에는 WARN 만 남고 Keycloak 세션이 살아 있을 수 있었다.

### 1.1 PR-5 · SCIM 2.0 아웃바운드 — Idem → 기관 프로비저닝 (플랜 §5 #3)

- **서비스별 opt-in**: 프로파일 `protocol.scim {enabled, baseUrl, credentialRef, groups, onUnassign, onWithdraw}`. 토큰은 프로파일에 없다 — `credentialRef=secrets/agency/{code}/scim-token` 이 hub 환경변수 `SECRETS_AGENCY_{CODE}_SCIM_TOKEN`(K8s Secret) 을 가리킨다(`AgencyCredentialStore`). `baseUrl` 은 백채널 로그아웃과 같은 SSRF 규칙(루프백·사설망 거부, 폐쇄망은 `IDEM_HUB_SCIM_ALLOW_PRIVATE_HOSTS=true`).
- **무엇이 가는가**: 기관향 식별자(agencySubjectId)를 SCIM `externalId`·`userName` 으로 — qimUserId·PII 는 가지 않는다. 할당 → `ENSURE_USER`(있으면 active=true, 없으면 POST /Users), 해제·만료 → `onUnassign`(DEACTIVATE 기본 / DELETE / NONE), 역할 부여·회수 → SCIM Group(displayName=roleCode) 멤버 add/remove(`groups`), registry 정지 → active=false, 탈퇴 → `onWithdraw`(DELETE 기본). 기관이 authz 인바운드 SCIM(`/scim/v2/Groups`) 으로 만든 변경(actor=SCIM)은 되돌이 방지로 밀어내지 않는다.
- **경로**: authz 이벤트(`AuthzEventConsumer`)·registry 정지·탈퇴(`QimEventConsumer` → `ScimUserLifecycleHandler`, 할당된 기관만) → `idem_hub.scim_outbox`(V29, 웹훅 아웃박스와 분리 — 그 표는 릴레이 둘·기관 피드가 읽는다) → `ScimOutboxRelay`(SKIP LOCKED, 10s·20s·40s·80s·160s 백오프, 400/401/403/501 은 즉시 FAILED, `IDEM_HUB_SCIM_*`) → `ScimClient`(RFC 7644 부분집합: `filter=externalId eq`, POST, PATCH replace active / members add·remove, DELETE, Bearer). 감사 `SCIM/SCIM_DISPATCHED·SCIM_DISPATCH_FAILED·SCIM_SYNC_REQUESTED`.
- **관리 API**: `GET /api/v1/admin/services/{code}/scim/status`(아웃박스 집계·마지막 오류), `POST …/scim/sync`(전체 동기화 — ACTIVE 할당 전부를 ENSURE_USER + 역할 그룹으로 적재; 도입·재조정용). authz 내부 API `GET /users/{id}/assignments`, `GET /agencies/{code}/assignments?page&size`. 콘솔 화면은 아직 없다(JSON 탭으로 블록 편집, 할당·규칙 화면과 같은 과제).
- **샘플 기관**: `idem-tenant-sample` 에 SCIM 서버 참조 구현(`/scim/v2/Users`·`/Groups`, 메모리, `AGENCY_SCIM_TOKEN`).
- **결함 수리**: 1.1 PR-1 의 `ASSIGNMENT_CHANGED` 감사가 쓰는 분류 `AUTHZ` 가 `audit_log` CHECK 에 없어 INSERT 가 조용히 실패하고 있었다(WAL 도입 뒤에는 재시도 반복) → V29 가 `AUTHZ`·`SCIM` 을 허용. WAL 재생기는 제약 위반 행을 재시도하지 않고 폐기·`audit.lost.total` 로 센다.

### 1.1 PR-4 · Java 에이전트 저장소 분리 (플랜 §5 #1, 사용자 결정 2026-09-29)

- `idem-agent`·`idem-agent-testbed` 와 에이전트 문서 7건(`docs/idem-agent-*.md`, `internal/architecture/idem-agent-architecture.md`·`jeus-sso-deep-dive.md`, `internal/development/idem-agent-developer-reference.md`)을 모노레포에서 **제거**하고 별도 저장소로 옮긴다. 에이전트가 부르는 검증 API(`/api/v1/agency/token/verify`)는 1.0.x 서버에 없어 1.0 연동 수단이 아니었고(제품 설명서 F7), 살리려면 hub 검증 API·브라우저 토큰 발급 설계가 먼저라 모노레포 밖에서 따로 다룬다.
- 정리: `settings.gradle.kts` include, 루트 `build.gradle.kts` 의 에이전트 제외 분기, 서비스 Dockerfile 6개의 `COPY idem-agent/build.gradle.kts`, `.githooks/pre-push` 대상 목록, `THIRD-PARTY-NOTICES.md`(Byte Buddy agent·Javassist 행), 문서 링크. **CI·설치본·런타임 동작 변경 없음**(어느 모듈도 에이전트를 의존하지 않았다).
- 새 저장소 씨앗: 단일 커밋 번들(코드·테스트베드·문서·README·settings·.gitignore, jar·비밀 없음). 이력은 끌고 가지 않는다 — 옛 `onepass-agent.properties` 의 비밀값 정리 이력(`docs/public-release-checklist.md`)이 모노레포 이력에 있어 새 저장소에 복제하지 않기 위해서다.

### 1.1 PR-3 · 코어 로그인 프런트 — Handoff 브라우저 진입 (플랜 §5 #2) + FE 세션 쿠키 수리

- **코어 로그인 프런트 (hub)**: Handoff 유형(DIRECT·BRIDGE·APACHE_GATE·INTERNAL_SSO) 서비스의 브라우저 진입을 core 가 제공한다. 기관 화면의 "Idem 으로 로그인" 은 **`GET {hub}/api/v1/handoff/login?service=&callback=[&provider=][&level=][&state=]`** 로 보내면 된다. hub 가 기관·활성·연동 유형·`callbackWhitelist` 를 검사하고(밖이면 오류 화면 — 콜백으로 되돌리지 않는다), 본인인증 SPI 제공자(플러그인)로 로그인(제공자가 하나면 자동, 여럿이면 선택 화면 `/start`, 브로커 `broker:<name>` 은 `IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS` 로 허용) → `/continue` 에서 registry 확정·FE 세션·`feSessionId` 쿠키 → **hub 안에서 발급**(API 발급과 같은 정책·레이트리밋·감사) → `302 callback?ticketId=…[&state=…]`. 정책 거부는 `302 callback?error=E-IDO-120&error_description=…[&state=…]`. 기관 API 키는 브라우저 어디에도 없고 기관은 종전대로 서버 간 `verify` 만 한다. 진입 상태는 Redis(`idem:handoff:login:*`, 10분, 1회)에만 있어 쿼리로 기관·콜백을 바꿀 수 없다. 화면은 CSP(`default-src 'none'`) 안에서 링크만 쓰는 최소 HTML — 운영기관이 자기 화면을 원하면 같은 URL 계약으로 대체한다. 진입 경로는 IP 레이트리밋(`/api/v1/auth/**` 와 같은 규칙) 대상.
- **FE 세션 쿠키 이름 불일치 수리 (결함)**: 발급 쪽(Keycloak·NonOidc 콜백, `/oidc/complete`, `/api/v1/fe-session`, KR 전환)은 `feSessionId` 를 쓰고 Handoff 발급 API 와 CAST 는 `Fe-Session-Id` 를 읽어 **브라우저에서 API 발급은 항상 `E-IDO-107`** 이었다(단위·통합 테스트가 쿠키를 직접 심어 드러나지 않았다). `FeSessionCookie.NAME`(`feSessionId`) 하나로 통일. **동작 변경**: `POST /api/v1/handoff/issue` 와 `POST /api/v1/agency/cast/issue` 가 읽는 쿠키 이름이 `feSessionId` 다(문서도 정정).
- **발급 API 기관 바인딩 (결함)**: `POST /api/v1/handoff/issue` 가 `X-Agency-Code`(키 검증된 기관) 과 본문 `agencyCode` 를 비교하지 않아 어느 기관 키로든 다른 기관 티켓을 발급할 수 있었다 → 불일치는 `403 E-AGENCY-302`.
- **qsign 모드 FE 세션 쿠키 전달 (결함)**: gate 콜백 → hub `/api/internal/v1/oidc/complete` 응답의 쿠키를 gate 가 전달하지 않아 설치본 기본(qsign) 모드에서 브라우저에 FE 세션 쿠키가 닿지 않았다. hub 가 1회용 바인드 코드 URL(`{public-url}/api/v1/fe-session/bind?code=…`, 60초, `idem:fe:bind:*`) 을 redirectUrl 로 돌려주고 브라우저가 거기서 쿠키를 받은 뒤 returnUrl 로 간다.
- **본인인증 SPI 절차 공유**: `IdentityLoginService`(initiate·complete·registry 확정·감사) 를 API(`/api/v1/auth/providers/**`)와 로그인 프런트가 함께 쓴다(동작 동일).
- **설정**: `idem.hub.public-url`(`IDEM_PUBLIC_URL_HUB`, 기본 `http://localhost:8083`) — hub 가 브라우저를 되돌릴 자기 주소(compose·Helm 반영). `idem.hub.handoff.login.broker-providers`.
- **샘플 기관 (`idem-tenant-sample`)**: `GET /agency/login` → hub 로그인 프런트 → `GET /agency/callback?ticketId&state`(state 쿠키 검증, 서버 간 verify, AGSID 세션) — 브라우저가 올 수 있는 Handoff 콜백 참조 구현(종전 `POST /agency/entry` 는 기관 키가 필요해 브라우저가 직접 올 수 없었다). `idem.sample.public-url`(`AGENCY_STUB_URL`), `idem.sample.ido.public-url`(`IDEM_PUBLIC_URL_HUB`).
- **설치본 스모크 ⑦b**: DIRECT 프로파일 PUT → `rotate-key` → 진입 → MOCK → 발급 → 콜백(ticketId·state·`feSessionId` 쿠키) → verify → 재검증 409 → 화이트리스트 밖 403. 종전 개발자 가이드의 "브라우저 경로가 시뮬레이터·D-10 으로 검증돼 있다" 는 서술은 부정확했다(시뮬레이터는 서버 간 발급만, D-10 은 API 만) — 정정.

### 1.1 PR-2 · 감사 WAL 폴백 + 그룹·속성 규칙 할당 (S8-b PR-2·H-16 "남긴 것" 마감)

- **감사 WAL 폴백 (hub)**: `idem_hub.audit_log` INSERT 가 실패하면 항목을 버리지 않고 로컬 JSON Lines WAL(`AuditWal`, `IDEM_HUB_AUDIT_WAL_DIR`, 기본 `./data/audit-wal`, append + fsync)에 남긴다. `AuditWalReplayer` 가 주기(`IDEM_HUB_AUDIT_WAL_REPLAY_INTERVAL_MS`, 기본 60초)마다 세그먼트를 회전해 `ON CONFLICT (audit_id) DO NOTHING` 으로 재삽입(원래 `occurred_at` 보존, 부분 실패 시 남은 줄만 다시 씀). 재삽입 행은 `kafka_published=false` 라 기존 Kafka 재발행 스케줄러가 이어받는다. 종전(1.0.1)에는 WARN 한 줄 남기고 감사 항목이 사라졌다. 지표 `audit.wal.appended.total`·`audit.wal.replayed.total`·`audit.wal.pending.lines`·`audit.lost.total`(WAL 마저 실패). prod/stage 에서 `IDEM_HUB_AUDIT_WAL_ENABLED=false` 는 기동 거부. compose 는 named volume `hub-audit-wal`, Helm 은 `hub.auditWal.{dir,existingClaim}`(기본 emptyDir) 에 둔다 — 파드 삭제까지 버티려면 PVC.
- **그룹·속성 규칙 할당 (authz + hub)**: authz V6 `authz_assignment_rule`(`GROUP`: `agencyCode:roleCode` 역할이 유효한 사용자 / `ATTRIBUTE`: 발급 컨텍스트 속성 `authLevel`·`providerCode` 가 허용 값 목록에 있는 사용자, `*` = 값 있으면 통과, `expiresDays` = 실체화 할당 유효 일수) + `authz_assignment.rule_id`. 규칙은 hub 발급 경로의 **접근 평가 시점에 실체화**된다: 직접 할당이 없으면 규칙을 순서대로 보고 첫 일치에서 `source=RULE` 할당을 만든다(`AUTHZ_ASSIGNED` 전파·감사 `ASSIGN`). `source=RULE` 할당은 매 평가마다 그 규칙을 재확인해 꺼졌거나 불일치면 회수(`UNASSIGNED` 전파) 후 다른 규칙을 본다. 규칙 비활성화(`DELETE /assignment-rules/{id}`)는 그 규칙이 만든 ACTIVE 할당을 즉시 회수한다. 직접 할당(CONSOLE·SCIM·API·SELF_SIGNUP·ROLE_GRANT)이 있으면 규칙은 보지 않고, 명시 회수된 직접 할당은 규칙이 되살리지 않는다(거부가 이긴다; 만료된 한시 할당은 규칙 대상). 내부 API `POST/GET /api/v1/internal/authz/assignment-rules`, `DELETE /assignment-rules/{id}`(→ `{revoked}`), **`POST /users/{id}/access`**(`{agencyCode, attributes}`, hub Handoff 발급·OIDC 토큰 교환·CAST 가 호출; verify 경로의 `GET /access` 는 읽기 전용 그대로). hub 는 `AssignmentContext` 의 두 키(`authLevel`·`providerCode`)만 보낸다 — 프로파일 identity 속성(PII)은 인가 서비스로 가지 않는다. 감사 `RULE_CREATED`·`RULE_DISABLED`, 오류 `E-AUTHZ-404-RULE`. 관리 콘솔 화면은 아직 없다(할당 관리 화면과 함께, `post-1.0-plan` §2.3).

## [1.0.1] — 2026-09-26

3차 적대적 점검(`docs/analysis/adversarial-review-1.0.md`) 후속. PR-A(보안, #244) → PR-B(설치본, #245) → PR-C(기능·문서). 태그 `v1.0.1`.

### 보안 (PR-A)
- **관리 API 인증 우회 수정 (H1)**: `AdminAuthFilter` 가 원본 URI 를 정규화(`RequestPath`)해 보호 경로를 판정하고, 경로 파라미터(`;x`)·퍼센트 인코딩(`%61`)·점 세그먼트·중복 슬래시로 위장한 요청은 세션과 무관하게 403 + 감사(`non-canonical path`). 모든 관리 엔드포인트(읽기 포함)와 Handoff 강제 취소가 `AdminPrincipal` 인자를 받아 필터를 지나쳐도 401 로 끝난다.
- **gate 프록시 경로 이탈 수정 (H2)**: `KeycloakProxy` 가 정규형 경로만 전달하고 설정된 realm 아래·`/resources/**` 만 허용 — `..`/`%2e%2e` 로 Keycloak 관리 콘솔·master realm·admin REST 에 닿을 수 없다(400 `invalid_request`). Location 재작성은 호스트·포트 비교(루프백 별칭 포함)로 내부 URL 누출을 막는다.
- **relay Flyway 제거 (H3)**: relay 가 `idem_hub` 에 `repair()` 를 돌려 hub 마이그레이션 이력을 지우던 결함. `BatchFlywayConfig`·relay `V19` 삭제, `shedlock` 은 hub `V27` 이 만든다.
- **관리자 역할·테넌트 변경 시 세션 종료 (M2)**, **TOTP 코드 1회 사용 (M3, RFC 6238 §5.2)** — 같은 스텝의 코드 재사용은 `E-IDO-134` "이미 사용한 2단계 인증 코드"(Redis `idem:admin:totp:{adminId}:{step}`), `scripts/lib/admin-login.sh` 는 다음 스텝으로 재시도.
- **Back-Channel Logout 검증 강화 (M4)**: `aud` 는 원소 정확 일치(`idem-gate-foo` 거부), `iat`·`jti` 필수, `exp`/최대 수명 검사, `jti` 재사용 거부(`idem:gate:bc-logout:jti:*`).
- **`backchannelLogoutUri` 검증 (M15)**: 공개 http(s) 호스트만 — 루프백·사설망·링크로컬·`.local/.internal`·이름만인 호스트(컨테이너 이름) 거부(SSRF).
- **gate 공개 OIDC 프런트 IP 레이트리밋 (M17)**: `/realms/**` 에 IP 당 20/s·300/min(`IDEM_GATE_FRONT_RL_*`), 초과 429 `rate_limited`, Redis 장애 시 503(fail-closed). `X-Forwarded-For` 는 `trust-forwarded-for=true` 일 때 마지막 홉만.
- hub: 지원하지 않는 메서드·Content-Type 은 500 이 아니라 405 `E-IDO-405`·415 `E-IDO-415`. authz: 없는 경로·메서드·미디어 타입도 플랫폼 본문(`E-AUTHZ-404/405/415`, 경로 반사 없음).
- 주석 정정: registry `QimWebMvcConfig`(상태 API 는 내부 키 필수), 레이트리밋 Redis 키 접두 `idem:*`, relay ShedLock 표 위치.

### 설치본 (PR-B)
- **Helm Pod 기동 (H4)**: 모든 Idem 이미지가 숫자 UID/GID 1001(`USER 1001:1001`)로 바뀌고 차트가 `runAsUser/runAsGroup/fsGroup`(앱 1001, Keycloak 1000)을 명시한다 — 종전에는 `runAsNonRoot` 가 이름 사용자를 거부해 앱 Pod 전부 `CreateContainerConfigError`.
- **`global.imageRegistry` 접두 (H5)**: Idem 이미지(짧은 이름)에만 붙는다. Keycloak·postgres 는 `image.registry: ""`.
- **관리 콘솔·KR 포털 이미지 (H6)**: `.dockerignore` 예외로 두 Dockerfile 이 빌드된다. CI `docker-build`(main, GHCR)·`docker-build-check`(PR) 매트릭스에 `idem-console-admin`·`idem-kr-portal` 추가 — `cd.yml` 이 배포하던 이미지가 이제 실제로 만들어진다.
- **업그레이드 데이터 고아 방지 (H7)**: `init-db.sql` 은 구 스키마(`ido`·`qsign`·`qim`·`authz`)가 있으면 새 스키마를 만들지 않는다(Helm 훅·compose 공통). `LegacySchemaRename` 과 `rename-db-1.0.sh` 는 구·신 스키마가 둘 다 있고 새 쪽에 Flyway 이력이 없으면 멈춘다(이력이 있으면 "구 스키마 남음" 경고). 스키마 존재는 `pg_namespace` 로 본다.
- **`rename-db-1.0.sh` (H8)**: 실행 사용자가 `onepass` 여도 임시 슈퍼유저를 만들어 역할을 옮긴다. `IDEM_DB_PASSWORD` 가 있으면 rename 뒤 비밀번호를 다시 설정(MD5 비밀번호는 rename 으로 지워진다), 없으면 경고.
- **Flyway repair 1회 (M1)**: 매 기동 `repair()` 대신 `validate` 를 먼저 하고 체크섬 불일치만 있을 때 1회 repair. 빠진·실패한 마이그레이션은 검증 오류로 드러난다. `IDEM_NAMING_LEGACY_REPAIR=false` 면 repair 하지 않는다.
- **`SPRING_PROFILES_ACTIVE=prod` (M6)**: compose(`IDEM_SPRING_PROFILE`)·Helm(`appDefaults.springProfile`)·CI 설치 스모크 모두 prod 프로파일로 뜬다. 켜 보니 hub 가 기동을 거부했다 — `prod` 에서는 KMS Off(평문 키 재료)가 금지되는데 설치본에 Vault 가 없다. 새 KMS provider **`local`**(`LocalMasterKeyKmsClient`): 설치본 비밀 `IDEM_HUB_KMS_MASTER_KEY`(base64 32바이트)로 회전된 Handoff 키 재료를 AES-256-GCM 봉인(`local:v1:` 접두). 1.0 이 남긴 평문 재료는 WARN 과 함께 읽는다(`IDEM_HUB_KMS_LOCAL_ACCEPT_LEGACY`). compose·Helm·CI 기본이 `local`, Vault 는 `provider=vault`.
- **actuator 관리 포트 (M7)**: `management.server.port=${IDEM_MANAGEMENT_PORT:${server.port}}`. Helm 은 9090 으로 분리하고 Service·Ingress 는 앱 포트만 내보낸다(프로브도 관리 포트). compose 는 앱 포트 그대로 — 리버스 프록시가 `/actuator` 를 막는다(문서).
- **compose 플러그인 플래그 (M8)**: `IDEM_PLUGINS_NICE_OACX_ENABLED`·`IDEM_PLUGINS_ANYID_ENABLED` 를 `install.env` 로 켠다.
- **Keycloak (M9·M10)**: Helm `KC_HOSTNAME_ADMIN_URL` 기본 `http://localhost:8088`(port-forward), `replicaCount>1` 은 `KC_CACHE_STACK` 없이는 렌더링 거부. realm-export 의 내부 client redirect URI·webOrigins 는 `${IDEM_PUBLIC_URL_GATE}`·`_HUB`·`_CONSOLE` 자리표시자 — compose·Helm·CI 가 Keycloak 에 그 값을 준다.
- **프로파일 `limits.tps/daily` 적용 (H9, PR-C)**: Handoff 발급(`HandoffServiceImpl`)과 표준 OIDC 토큰 교환(`OidcRpAccessService`)이 프로파일 한도를 `AgencyRateLimiter` 에 전달한다(없으면 설치본 기본 200 tps·1,000,000/일). 초과는 Handoff `429 E-AGENCY-306`, OIDC 토큰 교환 `429 temporarily_unavailable` + `Retry-After`(gate).
- **문서 정정 (PR-C)**: 온보딩 예시 `schemaVersion: 1`(정수)·모르는 키는 400·`INACTIVE` 는 authorize 단계 400·`status` 생략 시 ACTIVE·admin-login 환경변수 이름; 관리자 매뉴얼 PUT 순서(테넌트 → 스키마 → 저장 → Keycloak, 같은 트랜잭션); `install-inputs` 읽는 쪽 3건·`IDEM_HUB_INTERNAL_SIG_SECRET` 규칙·완료 판정 regex `[A-Z0-9_]`(CI 도); 제품 설명서 지표 이름(`slo.*` 등, hub prometheus 미등록)·감사 표현·K8s/Helm 버전 근거·주체 스킴 `PLATFORM_ID`·SCIM 인바운드; 시험 항목표 자동화 재집계(51 중 자동 41 = CI 37 + 로컬 IT 4, 수동 10)와 GS 착수 문서.
- **개명 잔재·가드 (PR-C)**: KR 포털 FE 의 `IDO_API_*`·`X-IDO-API-Key`·`ucube-qsign`·`onepassCli`·`QSIGN_*`, 스모크의 `AUTHZ_URL` 정리. `NamingGuardTest` 가 FE 소스(`.ts/.tsx`)·`.py`·Dockerfile·`AUTHZ_/BATCH_` 접두도 본다.
- 버전 1.0.1 (루트 build, 콘솔 package, Helm Chart, 매뉴얼).
- LOW: Trivy 스캔이 `docker-build` 의 실제 이미지를 스캔한다(종전 `helm-lint` 끝에서 존재하지 않는 이미지를 스캔하고 항상 통과). `DB_SSLMODE`/`IDEM_*_DB_SSLMODE` 로 gate·hub·authz 도 JDBC TLS 를 켤 수 있다(Helm `infra.postgres.sslMode`). Helm `idem.host` 가 경로·포트 있는 URL 을 다룬다. 문서: Docker Compose ≥ 2.17.

## [1.0.0] — 2026-09-26

첫 동결 릴리스. 2026-09-10 부터의 범용화(`docs/generalization-plan.md` S1~S9·D1~D3)를 마감한다.

### 제품
- 제품 3개 재편: Idem SSO(`idem-gate`·`idem-hub`) · Idem IM(`idem-registry`·`idem-authz`) · KR 에디션(`editions/`, 플러그인). 코어는 에디션을 모른다(가드 테스트).
- 개명 OnePass → Idem 완료: 패키지 `io.github.hipstermin.idem.*`, 설정 키 `idem.*`, 환경변수 `IDEM_*`, DB `idem`·스키마 `idem_*`, Keycloak realm `idem`·client `idem-gate`/`idem-hub`. 구 이름은 1 릴리스 호환 계층(`LegacyNames`·`LegacySchemaRename`).
- 표준 OIDC 제공(OIDC_RP): issuer 는 공개 gate URL, Keycloak 은 숨김, client 자동 프로비저닝, PKCE 필수, 토큰 교환 시 정책 판정, SLO·Back-Channel Logout.
- 서비스 프로파일(JSON 스키마 v1): 프로토콜·주체 스킴·속성 카탈로그/마스킹/매핑·정책(인증 수준·제공자·세션·점검·규칙·할당)·한도·UI. 정책 시뮬레이션.
- 연합 인가: 역할 원장·할당(사용자·그룹 ↔ 서비스)·만료·회수, 미할당 거부 또는 GUEST.
- 관리자 인증·인가: 자체 계정 + TOTP 2단계, 역할 3종, 테넌트 범위, 잠금·비밀번호 정책·CSRF, 감사 검색. 관리 콘솔 `idem-console-admin`.
- 본인확인 SPI(`IdentityVerificationProvider`) 와 플러그인(Mock · KR: NICE OACX·Any-ID). 벤더 SDK·자격증명은 저장소 밖.
- registry: 없는 경로는 500 이 아니라 404 표준 오류 본문(`E-IM-404`)으로 답한다 — KR 전용 경로를 코어에 부른 경우를 도구가 구분할 수 있다.
- fail-secure: 필수 비밀 없으면 기동 거부, 의존 장애 시 거부, 시드 기관 없음, Mock 기본 off. `CryptoProvider` SPI 로 암호 모듈 교체 가능.

### 설치본
- Docker Compose 단일 설치본(PostgreSQL·Redis·숨긴 Keycloak·앱·콘솔, Kafka 없음) — CI 가 PR 마다 실기동 스모크 8단계.
- Helm 차트 `infra/helm/idem` — 같은 계약, `global.edition` core/kr, 비밀 한 벌, pre-install 스키마 Job. 이미지 `<tag>-<edition>`.
- 0.x → 1.0 업그레이드: `scripts/upgrade/rename-db-1.0.sh` + 첫 기동 자동 스키마 rename·Flyway repair.
- KR 회원 일회성 이관 도구 `scripts/kr-member-import`.

### 문서
- 설치·입력값·온보딩·요구사항 체크리스트·관리자 인증·개명 대응표, 1.0 매뉴얼 초안 4종(`docs/manuals/`), GS 착수 문서.

### 알려진 제한
- SAML SP·SCIM 아웃바운드·동의 카탈로그·Audit Sink SPI 없음. API 경로·오류 코드(`E-IDO-1xx`, `/admin/agencies`)와 에이전트 설정 키(`onepass.agent.*`, 외부 계약)는 1.0 에서 동결, 개명은 2.0.
- 실제 K8s 배포·오프라인 설치·백업 복구는 리허설 전(`docs/manuals/installation-manual.md` §8).

[1.0.0]: https://github.com/HipsterMIN/integration-sso/releases/tag/v1.0.0
