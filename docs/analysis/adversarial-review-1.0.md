# Idem 1.0 적대적 점검 (3차) — 2026-09-26

> **대상**: `main` f2392e8 (= `v1.0.0` 78592f8 + CI 수정 #242). **범위**: S6~S9·1.0 동결에서 만든 것 전부 — Helm/compose 설치본, CI, 개명 호환 계층, 관리자 인증, 표준 OIDC 프런트, 프로비저너, KR 회원 이관 도구, 1.0 매뉴얼·온보딩·입력값·시험 항목표.
> **방법**: 리뷰어 4 (① 설치본·CI ② 문서 vs 코드 ③ 보안 코드 ④ 실기동 공격) 를 병렬로 돌리고, HIGH 는 전부 내가 다시 재현했다(재현 출력은 본문). 앞선 점검: v0.5 2차(2026-09-24, `generalization-plan.md`).
> **판정**: **`v1.0.0` 그대로는 GS 신청·운영 배포 불가.** 인증 우회 2건(실기동 재현)과 설치본 결함 6건이 있다. → **1.0.1 보안·설치본 릴리스**가 먼저다(§5).

## 0. 한눈에

| 등급 | 건수 | 대표 |
|---|---|---|
| **HIGH** | 10 | 관리 API 인증 우회(`;`·퍼센트 인코딩), gate 프록시로 Keycloak 관리 콘솔 노출, relay 기동이 hub Flyway 이력 파괴, Helm Pod 전부 기동 불가(runAsNonRoot), 콘솔 이미지 빌드 불가, K8s 업그레이드 시 구 데이터 고아, 프로파일 `limits` 미적용 |
| MED | 17 | `flyway.repair()` 매 기동, 관리자 강등 시 세션 유지, TOTP 재사용, Back-channel aud 부분일치, 구 설정 키 무시, prod 프로파일 미설정, 공개 host actuator, 문서 허위 다수 |
| LOW | 20 | 가드 사각, 접두 별칭 과다, 잠금 레이스, 이관 도구 CSV, Compose 버전 표기 … |

리뷰어 ④(실기동 공격)의 결과는 §1.11 과 §4 에 합쳤다. 설치 스모크 ①~⑧ 은 1.0.0 jar 로 **녹색**이 기준선이었고, 공격은 그 위에서 했다.

## 1. HIGH — 전부 재현됨

### H1. 관리 API 인증 우회 — `;` 경로 파라미터·퍼센트 인코딩 (hub)
- **재현** (1.0 jar, 세션 없음):
  ```
  GET /api/v1/admin/admins        → 401 E-IDO-130
  GET /api/v1/admin;x/admins      → 200 [{"adminId":"01a0…","username":"admin",…}]   ← 관리자 명부 전체
  GET /api/v1/%61dmin/admins      → 200 (같음)
  GET /api/v1/admin;x/tenants     → 200 [{"code":"DEFAULT",…}]
  GET /actuator/flyway            → 401
  GET /actuator;x/flyway          → 200 {"contexts":{"idem-hub":{"flywayBeans":…      ← 마이그레이션 이력
  ```
- **원인**: `AdminAuthFilter.shouldNotFilter`·`doFilterInternal` 이 `request.getRequestURI()`(원본, 디코딩·경로 파라미터 제거 전) 를 `AntPathMatcher` 로 `/api/v1/admin/**`·`/actuator/**` 와 비교한다(`idem-hub/.../admin/auth/AdminAuthFilter.java:55-58, 64`). Tomcat 은 매핑 때 `;x` 를 떼고 `%61` 을 디코딩하므로 핸들러는 실행되는데 필터는 "보호 경로 아님" 으로 본다.
- **영향**: `AdminPrincipal` 파라미터가 없는 모든 관리·actuator 엔드포인트가 무인증·CSRF 없이 열린다 — 재현된 것: `GET /api/v1/admin/admins`(관리자 명부·역할·테넌트·잠금 상태), `/admins/{id}`, `/tenants`, `/tenants/{code}`, `/actuator/flyway`(마이그레이션 이력), 같은 방식으로 `/actuator/metrics|features`. `AdminPrincipal` 을 받는 엔드포인트는 `AdminPrincipalArgumentResolver` 가 막는다(우회 경로로 `PUT …/profile` → 401 확인). `DELETE /api/v1/handoff/{id}` 는 정상·우회 경로 모두 403 이어서 이 결함으로 열리지는 않는다(별도 검사) — 미확인 항목으로 둔다.
- **수정**: 정규화된 경로(`getServletPath()+getPathInfo()` 또는 `ServletRequestPathUtils`)로 비교하고, `;`·`%2e`·`%2f`·`..`·`//` 가 든 URI 는 매칭 전에 400 으로 거부(StrictHttpFirewall 의미). 관리·Handoff 엔드포인트 전부에 `AdminPrincipal`(또는 명시적 인증) 을 붙인다. 회귀 테스트: 위 4개 요청.

### H2. gate 의 Keycloak 프록시가 dot-segment 로 Keycloak 관리 콘솔을 내보낸다
- **재현** (gate 8081):
  ```
  GET /resources/../admin/master/console/          → 200 <!DOCTYPE html>… (Keycloak 관리 콘솔)
  GET /resources/%2e%2e/admin/master/console/      → 200
  GET /realms/idem/../../admin/master/console/     → 200
  GET /admin/master/console/                       → 404 (직접 경로는 막힘)
  ```
- **추가 재현** (리뷰어 ④, 같은 stack): `GET /realms/idem/../master/.well-known/openid-configuration` → **200 master realm 의 OIDC 설정**(직접 `/realms/master/…` 는 404 — realm 가드가 있으나 우회됨), `%2e%2e` 형태도 200(브라우저는 `%2e%2e` 를 정규화하지 않아 링크 하나로 유발 가능) · `GET /realms/idem/../../admin/` → **302 Location: http://localhost:8180/admin/master/console/**(내부 URL 누출 — `KeycloakProxy.responseHeaders` 의 Location 재작성이 `127.0.0.1:8180` 접두만 보고 `localhost` 를 못 잡음) · `GET /realms/idem/../../admin/realms/idem/users` → 401(Keycloak **Admin REST** 도착 — 토큰은 필요하지만 표면이 공개됨) · `POST /realms/idem/../idem/protocol/openid-connect/token` + `client_id=idem-gate` → Keycloak 의 맨 401 — **gate 의 client 허용 목록·hub 정책 판정이 통째로 건너뛰어진다**(정상 경로는 프런트의 `invalid_client`). `..%2f` 는 Tomcat 이 400 으로 막지만 맨 `..` 와 `%2e%2e` 는 통과.
- **원인**: `KeycloakProxy.java:67` 이 `baseUrl + request.getRequestURI()` 를 그대로 Keycloak 에 보낸다. `/resources/**`·`/realms/{realm}/**` 핸들러에 걸린 뒤 원본 URI 의 `..` 가 Keycloak 쪽에서 정규화된다.
- **영향**: "Keycloak 은 숨긴다"(S6) 가 깨진다. 공개 SSO host 로 Keycloak 관리 콘솔·모든 경로가 노출되고, Helm 의 `exposeKeycloakAdmin=false`·NetworkPolicy 도 gate Pod 를 통해 우회된다.
- **수정**: 상류 경로를 정규화된 servlet path 에서 만들고 `..`·`;`·`%2e`·`%2f`·`//` 거부, 허용 하위 경로를 명시(`/realms/{realm}/protocol/openid-connect/*`, `/login-actions/*`, `/broker/*`, `/resources/<ver>/**`). Keycloak 쪽 `KC_HOSTNAME_STRICT=true` + 별도 admin hostname. 회귀 테스트: 위 3개 요청 → 404.

### H3. idem-relay 기동이 hub 의 Flyway 이력을 파괴한다
- **근거**: `idem-relay/.../config/BatchFlywayConfig.java:53-64` — relay 의 Flyway 는 `schemas("idem_hub")` + relay 자기 위치(`V19__add_shedlock_table.sql` 하나) 로 `LegacySchemaRename.migrate()` 를 부르고, 그 안의 `flyway.repair()`(`LegacySchemaRename.java:49`) 는 무조건 실행된다. 리뷰어 실험(로컬 PG): hub 26행 적용 뒤 relay 기동 → V19 행이 relay 의 "add shedlock table" 로 재정렬(checksum 변경), **V1~V18 은 DELETED 표시**, shedlock 테이블은 만들어지지 않음. 다음 hub 기동: `Detected resolved migration not applied to database: 1 … 18` → **hub 기동 실패**.
- **이전과 차이**: S9 PR-2 전에는 relay 가 `migrate()` 만 해서 validate 실패로 relay 만 안 떴다. 지금은 hub 를 망가뜨린다. relay 는 compose 설치본에 없지만 Helm `relay.enabled` 와 dev compose 에 있다.
- **수정**: relay 에서 `repair()` 를 절대 부르지 않는다. relay 가 자기 이력 테이블(`table("flyway_schema_history_relay")` + `ignoreMigrationPatterns("*:missing")`)을 쓰거나, shedlock DDL 을 hub V27 로 옮기고 relay 의 Flyway 를 없앤다(권장).

### H4. Helm — 앱 Pod 전부 `CreateContainerConfigError`
- **근거**: `_helpers.tpl:131` `runAsNonRoot: true`(runAsUser 없음). 이미지는 이름 사용자(`idem-gate/Dockerfile:99 USER qsign`, hub `USER idem-hub`, registry `USER qim`, authz `USER qauthz`, relay `USER batch`). kubelet 은 이름 사용자를 검증할 수 없어 "container has runAsNonRoot and image has non-numeric user" 로 거부한다. Keycloak(`USER 1000`)만 뜬다.
- **수정**: Dockerfile 에서 숫자 UID(`adduser -u 1001 …`, `USER 1001`) 로 통일하고 차트에 `runAsUser` 를 명시.

### H5. Helm — `global.imageRegistry` 가 Keycloak·postgres 이미지에도 붙는다
- **재현**: `helm template … --set global.imageRegistry=ghcr.io/hipstermin` → `image: ghcr.io/hipstermin/quay.io/keycloak/keycloak:24.0`, `ghcr.io/hipstermin/postgres:16-alpine`. `cd.yml` 은 항상 이 값을 준다 → db-init 훅이 ImagePullBackOff, `--wait` 실패.
- **수정**: `idem.image` 에서 repository 에 `/` 가 있으면 접두를 붙이지 않거나, keycloak/dbInit 에 별도 `registry` 값.

### H6. 관리 콘솔·KR 포털 이미지는 빌드된 적이 없고, 빌드될 수도 없다
- **근거**: `.dockerignore:64 editions/idem-kr-portal/frontend/`, `:66 idem-console-admin/` 이 컨텍스트에서 제외하는데 `idem-console-admin/Dockerfile:5,7,13` 과 `editions/idem-kr-portal/Dockerfile.optionB:26,30,42` 는 바로 그 경로를 COPY 한다 → `"/idem-console-admin/package.json": not found`. `compose.install.yml` 은 두 이미지를 `context: ../..` 로 빌드하므로 **`docker compose up --build` 가 콘솔에서 실패**한다. CI 는 어떤 잡도 이 두 이미지를 빌드하지 않고(`docker-build`·`docker-build-check` 매트릭스에 없음), `cd.yml` 은 GHCR 의 `idem-console-admin:<sha>` 를 배포하려 한다(존재하지 않음). S7 PR-2 의 콘솔 검증은 `vite preview` 였다.
- **수정**: `.dockerignore` 예외(`!idem-console-admin/**`, `!editions/idem-kr-portal/frontend/**`, `!infra/docker/nginx/nginx.conf`) + 두 이미지를 CI 매트릭스(PR 검사·main 빌드)에 추가.

### H7. Helm 업그레이드가 구 데이터를 고아로 만든다
- **근거**: `job-db-init.yaml` 훅이 `pre-install,pre-upgrade` 에서 `init-db.sql` 로 `idem_hub`·`idem_gate`·`idem_registry` 를 **먼저** 만든다. `LegacySchemaRename.renameIfLegacy`(`:58`) 는 새 스키마가 있으면 조용히 건너뛰고, `repair()+migrate()` 가 빈 새 스키마에 새 테이블을 만든다 → `ido/qsign/qim` 의 데이터는 그대로 남고 시스템은 비어 있다. `rename-db-1.0.sh:45` 도 같은 침묵 스킵. compose 는 `docker-entrypoint-initdb.d` 가 첫 볼륨 생성 때만 돌아 이 경로를 타지 않는다(리허설 A/C 가 통과한 이유).
- **수정**: `init-db.sql` 의 `CREATE SCHEMA` 를 "구 스키마가 없을 때만" 으로 감싸고(DO 블록), `LegacySchemaRename`·스크립트는 구·신 스키마가 **둘 다** 있고 새 쪽에 `flyway_schema_history` 가 없으면 **기동 거부 + 안내**.

### H8. `rename-db-1.0.sh` 의 역할 rename 은 문서대로 실행하면 실패한다
- **근거**: `:14` 는 `PGUSER=onepass` 로 실행하라 하고 `:35` 는 `ALTER ROLE onepass RENAME TO idem` → PostgreSQL `session user cannot be renamed`. `set -e` 라 ① DB rename 뒤에 중단 → 반쯤 이관. compose 설치본에는 `POSTGRES_USER` 외 슈퍼유저가 없다. 또 MD5 비밀번호는 역할 rename 시 지워진다(SCRAM 은 유지) — "비밀번호 그대로" 는 조건부.
- **수정**: 스크립트 안에서 임시 슈퍼유저를 만들어 재접속해 rename(또는 `CREATE ROLE idem` + `REASSIGN OWNED`), 실패 시 `--keep-role` 안내. 내 리허설 C 는 `postgres` 슈퍼유저 + `--keep-role` 이었다 — 문서의 경로는 리허설하지 않았다.

### H9. 프로파일 `limits.tps`/`limits.daily` 는 적용되지 않는다 (문서 4곳이 허위)
- **근거**: `HandoffServiceImpl.java:87` → `rateLimiter.tryAcquire(agencyCode)` → `AgencyRateLimiter.java:133` `tryAcquire(code, null, null)` → 설치본 기본값 200 tps / 1,000,000 일(`:92-95`). `ServiceProfileMapper` 는 `daily` 를 `daily_lookup_limit` 에 저장하고(`:132`) `tps` 는 JSON 에만 두지만(`:83`) 어느 것도 한도 계산에 쓰이지 않는다. OIDC_RP 경로엔 서비스별 한도 자체가 없다.
- **허위 문서**: `onboarding-guide.md`(133·191), `requirements-checklist.md`(106), `product-spec.md`(163), `test-items.md` D-11.
- **수정**: `tryAcquire(code, profile.limits().tps(), profile.limits().daily())` 로 배선하고 OIDC 프런트 토큰 교환에도 적용 + 429 테스트. 그 전까지 문서는 "설치본 기본 한도만" 으로 고친다.

### H10. 온보딩 가이드의 최소 프로파일 예시는 스키마에 거부된다
- **근거**: 예시 `"schemaVersion": "1"`(문자열). 스키마는 `{"const": 1}`. networknt 검증 결과 `$.schemaVersion: must be the constant value '1'`. 스모크·테스트 픽스처 39개는 정수 `1`.
- **수정**: `"schemaVersion": 1`. 같은 문서의 "모르는 키는 저장되지만 효과 없음" 도 허위 — 모든 객체가 `additionalProperties:false` 라 400.

### 1.11. 실기동 공격 결과 (리뷰어 ④) — 기준선 스모크 ①~⑧ 녹색

| 공격 | 결과 | 판정 |
|---|---|---|
| A1 actuator 무인증(hub) | `/actuator/health`(그룹만, 상세 없음)·`/info`(`{}`) 만 200, `/actuator`·`/metrics`·`/flyway`·`/features`·`/env`·`/beans`·`/threaddump`·`/heapdump`·`/mappings` 401, `/prometheus` 404(미등록) | 정상 — 단 H1 의 `;x` 우회로 `/flyway` 등이 열린다 |
| A2 CSRF | 세션 있어도 `X-Requested-With` 없으면 403 E-IDO-131, 아무 값이면 통과 | 정상(SameSite=Strict + 사전요청 강제) |
| A3 잠금 | 틀린 비밀번호 5회 → 401 E-IDO-132(계정 존재 노출 없음), 6회째 **맞는** 비밀번호 → 423 E-IDO-133, DB LOCKED | 정상 |
| A4 mfaToken 재사용 | 두 번째 사용 → 401 E-IDO-134 | 정상(1회용) |
| A5 쿠키 | `idemAdminSid=…; Path=/; Secure; HttpOnly; SameSite=Strict` | 정상 |
| A6 AUDITOR | GET 200, PUT profile·POST admins·secret 회전 → 403 E-IDO-131, 감사 `ADMIN_ACCESS_DENIED` 3건 | 정상 |
| A7 테넌트 범위(POLICY_ADMIN@T2) | DEFAULT 서비스 GET/PUT 403, T2 서비스 200, 본문 `service.tenant=DEFAULT` 로 위장 → 403 | 정상 |
| A8 테넌트 관리자 감사 검색 | `agencyCode` 없음 → 403, 범위 밖 → 403, 범위 안 → 200 | 정상 |
| B1~B4 gate | issuer 정확, `client_id` ∈ {idem-hub, idem-gate, idem-provisioner, 무작위, 빈값} 전부 400 `unauthorized_client`, PKCE 없음/`plain` 400, `response_type=token` 400, 미등록 redirect_uri → Keycloak 400 오류 페이지(Location 없음 — open redirect 없음) | 정상 |
| B5 경로 traversal | 직접 `/admin/**` 404, `..%2f` 400 — **맨 `..`·`%2e%2e` 는 통과 → H2** | **HIGH** |
| B6 INACTIVE | 상태 INACTIVE 저장 → Keycloak client `enabled=false` → authorize 단계에서 Keycloak "Client disabled" 400(토큰 교환 전에 차단, 정책 판정은 2차 방어) | 정상(온보딩 가이드의 "403 access_denied" 는 부정확 — 실제는 authorize 400) |
| B7 `limits.tps=1` | 12·25회 연속 → 0×429 → **H9** | **HIGH(기능)** |
| B8 gate 프런트 레이트리밋 | 25회 연속 200 → **M17** | MED |
| C 내부 API(registry·authz) | 키 없음 401, 모르는 경로+틀린 키 401(경로 열거 불가), 맞는 키+모르는 경로 404 `E-IM-404`(registry) / Spring 기본 404(authz), `GET /api/v1/users/{id}` 는 키 필요·`{status, updatedAt, qimUserId}` 만 | 정상(authz 404 계약 LOW) |
| D 개명 우선순위 | `IDO_ADMIN_BOOTSTRAP_PASSWORD` 와 `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 동시 → **새 이름이 이긴다**(구 값 401), Redis 키 16개 전부 `idem:*`, `ido:*` 0 | 정상 |
| E LegacySchemaRename | `idem_hub` 옆에 빈 `ido` 를 두고 기동 → rename 없음·정상 기동·로그 없음; 매 기동 "Successfully repaired schema history" → M1 | 정상(침묵은 LOW) |
| F 이관 도구(core registry) | BIZ 행 → "biz 404 — registry 가 KR 에디션이 아닙니다", 쉼표·따옴표 subject_key 정상 인용, 매핑 CSV·stdout·stderr 어디에도 CI 없음(`--verify` 포함), `--rps 0` 정상 | 정상 |

## 2. MED

| # | 항목 | 근거 | 수정 |
|---|---|---|---|
| M1 | `flyway.repair()` 를 매 기동·모든 앱에서 호출 → 체크섬 검증 영구 무력(변조된 마이그레이션 통과), 실패한 비트랜잭션 마이그레이션 매번 재실행 | `LegacySchemaRename.java:49`, `LegacySchemaRenameAutoConfiguration` | `validate()` 를 먼저 하고 체크섬 불일치가 **개명 전 적용분**일 때만 1회 repair(WARN) — 1.1 에서 제거 |
| M2 | 관리자 역할·테넌트 변경 시 기존 세션 유지(강등된 SYSTEM_ADMIN 이 최대 8h 전권) | `AdminUserService.java:86-92` 는 `status != ACTIVE` 만 세션 삭제; 역할은 세션 캐시 | 역할/테넌트 변경 시 `sessions.deleteAllOf(adminId)` |
| M3 | TOTP 코드 재사용 가능(±1 step, 마지막 사용 step 미기록) | `TotpService.java` verify | 관리자별 마지막 step 저장, `<=` 거부 |
| M4 | Back-channel logout: aud 부분일치(`contains`), `jti` 재사용 방지 없음, `exp` 선택 | `BackchannelLogoutController.java:59,64` | 각 aud 정확 일치, `iat/exp` 필수, `jti` 를 `exp` 까지 캐시 |
| M5 | 개명 호환 계층: 구 **설정 키**는 패키지 `application.yml` 이 새 키를 이미 정의하면(거의 항상) 조용히 무시된다(경고 없음). 환경변수 별칭은 정상 | `LegacyNamesEnvironmentPostProcessor.java:203` `containsProperty(target)` 가 하위 소스까지 봄. 실측: `ido.admin.cookie.secure=false` 무시 | 구 키 소스보다 우선순위가 같거나 높은 소스에 새 키가 있을 때만 건너뛰고, 항상 WARN |
| M6 | `SPRING_PROFILES_ACTIVE` 를 compose·Helm 어디서도 안 준다 → `application-prod.yml`(WARN 로깅·health `show-details: never`·flyway/features 노출 제거)과 gate `InternalSigVerifier` 의 hardened 판단이 설치본에 적용 안 됨. 필수 비밀 검사는 프로파일과 무관(`FailSecureBootGuardTest` "어떤 프로파일이든") | grep 결과 0건 | `x-app-common`·`idem.commonEnv` 에 `SPRING_PROFILES_ACTIVE=prod` |
| M7 | gate 공개 host 에 무인증 actuator `health,info,metrics,prometheus,flyway`(gate 는 인증 필터 없음); hub 도 `prometheus`·`info` 무인증 | `idem-gate/application.yml:233`, Ingress `/` Prefix | `management.server.port` 분리(Ingress 밖) 또는 Ingress 에서 `/actuator` 차단 |
| M8 | compose 가 `IDEM_PLUGINS_NICE_OACX_ENABLED: "false"` 하드코딩 → KR compose 설치본은 벤더 플러그인을 켤 수 없다(문서와 모순) | `compose.install.yml:222-223` | `${IDEM_PLUGINS_NICE_OACX_ENABLED:-false}` + `install.env.example` |
| M9 | Keycloak `KC_HOSTNAME_ADMIN_URL` 기본 `http://idem-keycloak:8080` → NOTES 의 port-forward 콘솔이 안 열린다(관리 콘솔은 admin URL 로 리다이렉트) | `deployment-keycloak.yaml:54-55` | 기본 `http://localhost:8088`; replica>1 이면 `KC_CACHE_STACK=kubernetes` 필요(현재 미설정) |
| M10 | realm-export 의 `idem-gate`/`idem-hub` redirect URI 가 `localhost`·컨테이너 이름 고정 → 공개 URL 이 localhost 가 아닌 모든 배포에서 gate 자체 Keycloak 코드 흐름이 `invalid redirect_uri` | `realm-export.json:29-32,65-68`; `install.md:169` 는 "첫 import 값" 이라고만 | `${env.IDEM_PUBLIC_URL_GATE:http://localhost:8081}/…` 자리표시자(이미 client secret 에 같은 방식) + compose/Helm 이 Keycloak 에 그 env 전달 |
| M11 | `service.status` 생략 → **ACTIVE**(온보딩 가이드의 "INACTIVE 면 403 access_denied" 도 부정확 — 실제는 Keycloak client 비활성으로 authorize 단계 400)(스키마 기본값, `isActive()` null→true, Keycloak client enabled) — 온보딩 가이드는 INACTIVE 가 자연스러운 결과처럼 서술 | schema default, `ServiceProfile.java:237`, `OidcRpClientProvisioner.java:133` | 문서에 명시하거나 스키마 기본값을 INACTIVE 로(1.0 API 동결 판단) |
| M12 | 시험 항목표 자동화 과장: 47 → 실제 **41**(엄격 40). 존재하지 않는 자동화: A-2(CAST 키 부팅 테스트 없음), B-8(테넌트 범위 테스트 없음), C-7·B-2·B-3·D-8 "E2E 헤드리스 브라우저" 는 저장소에 없음(S7 PR-2 의 1회성 수동 실행), D-11(H9), E-6(수동 리허설), G-3. IT 행 전부 CI 에서 안 돎(`DOCKER_UNAVAILABLE=true`). 라벨 오류 12행 | 리뷰어 ② 표 | 표 재작성 + gs-kickoff "자동 47" 수정 |
| M13 | `install.env` 완료 판정 regex `^[A-Z_]+=$` 가 `IDEM_REGISTRY_CI_AES_KEY_V1`(숫자) 를 못 본다 — CI `ci.yml:382` 키 커버리지 루프도 같은 버그 | 두 문서 + CI | `^[A-Z0-9_]+=$` |
| M14 | "Prometheus 지표 `idem_*`" 은 허위 — 실제 `slo.*`, `personal.data.*`, `idem.kms.healthy`, `idem.outbox.*` | `SloMetrics.java`, `KmsHealthMetrics.java` | 문서 정정(또는 MeterFilter 접두) |
| M15 | `backchannelLogoutUri` 미검증 → 테넌트 관리자가 Keycloak 에 내부 주소(`http://idem-hub:8083/…`)로 POST 시키는 SSRF | `ServiceProfileValidator.java:123-131` 은 redirect/postLogout 만 | https + redirect URI 와 같은 규칙 |
| M17 | gate OIDC 프런트(`/realms/idem/protocol/openid-connect/auth` 등)에 레이트리밋이 없다 — 25회 연속 200, 0×429. hub 의 `AuthRateLimitInterceptor`(IP 기준 20 tps) 는 hub `/api/v1/auth/**`·`/api/v1/admin/auth/{login,mfa}` 에만 등록(`IdoWebMvcConfig.java:70`, hub 에서 30회 → 20 성공/10×429 확인). 공개 관문에서 client_id 열거(200 vs 400 오라클)·Keycloak 로그인 화면 무제한 프록시 | 리뷰어 ④ 실측 | gate 프런트 경로에 IP 레이트리밋 필터(같은 Redis Lua) |
| M16 | 문서 세부 허위 묶음: `install-inputs.md` 읽는 쪽 3건(`IDEM_HUB_INTERNAL_API_KEY_GATE` 는 gate 가 안 씀, `_RELAY` 는 relay 가 안 씀, `IDEM_REGISTRY_AES_SHARED_KEY` 는 hub 만), `IDEM_HUB_INTERNAL_SIG_SECRET` 은 base64 디코딩 없음(32자 이상 문자열); admin manual PUT 순서(테넌트 → 스키마 → 저장 → Keycloak 같은 tx); 온보딩 `IDEM_HUB_URL` → 스크립트는 `HUB_URL`·`IDEM_ADMIN_PASSWORD`; CHANGELOG "SDK `onepass.*`" → 에이전트 키; "감사 INSERT 전용" → `kafka_published` UPDATE 있음(API 만 없음); "CI 가 compose 실기동" → jar 실기동; "K8s 1.27+·Helm 3.12+" 근거 없음(kubeconform 1.29 만); 주체 스킴에 `PLATFORM_ID` 누락; authz 에 inbound SCIM Groups(`/scim/v2/Groups`) 존재(README 와 제품설명서 모순); 스모크 D-3 은 `unauthorized_client` 400; "약 1,900 테스트" 는 어노테이션 1,418 | 리뷰어 ② | 각 문서 정정 |

## 3. LOW

relay 는 `idem_hub` 만 rename 하고 gate/registry/authz 스키마는 읽기만(기동 순서 의존) · `information_schema.schemata` 는 권한 있는 스키마만 보여 외부 PG 에서 구 스키마를 "없음" 으로 오판할 수 있음(`pg_namespace` 사용) · `additional_contexts` 는 Compose ≥ 2.17(문서는 "v2") · CI `helm-lint` 잡 끝에 Trivy 단계가 잘못 붙어 `idem/:latest` 를 스캔하고 항상 통과(원래 docker-build 의 마지막 단계였는데 PR-3 에서 잡을 사이에 끼워 넣으며 밀려남) · `infra.postgres.sslMode` 는 registry 만 반영(authz `IDEM_AUTHZ_DB_SSLMODE` 미배선, gate/hub 는 `sslmode=disable` 고정) · `idem.host` 가 경로·포트 있는 URL 을 못 다룸 · NamingGuardTest 사각: `idem-console-admin/`·`editions/idem-kr-portal/` 미스캔, `.ts/.py/Dockerfile/.env` 미스캔, `AUTHZ_`/`BATCH_` 접두 미검사 → 실제 잔재 `editions/idem-kr-portal/frontend/src/api/idoInstance.ts`(`IDO_API_KEY`, `X-IDO-API-Key`), `…/RegisterSteps/minor/Step4.tsx`(`QSIGN_REALM`, `'ucube-qsign'`, `'onepassCli'`), `scripts/ci/install-smoke.sh:26` `AUTHZ_URL` · `AUTHZ_*`/`BATCH_*`·`authz.`/`batch.` 별칭이 무관한 변수까지 매핑 · `AdminAuthFilter.clientIp` 가 `X-Forwarded-For` 첫 홉 신뢰(감사·잠금 IP 위조) · 잠금 카운터 read-modify-write 레이스 · `import_members.py` 가 registry 오류 본문을 매핑 CSV 에 그대로 기록(현재 CI 노출 없음이나 화이트리스트 필요)·`source_id` 수식 주입 · 구·신 스키마/DB 둘 다 있을 때 침묵 스킵(H7 과 같은 뿌리) · `IDEM_TZ` 기본은 hub 자체 UTC · gate `SecurityHeadersFilter` 도 원본 URI 매칭(헤더 생략만) · hub 가 지원하지 않는 HTTP 메서드(`DELETE /api/v1/admin/tenants/X`) 에 405 대신 **500 E-IDO-500** + 스택 로그(`GlobalExceptionHandler` 가 `HttpRequestMethodNotSupportedException`·`HttpMediaTypeNotSupportedException` 을 catch-all 로 보냄 — 5xx 지표 오염) · authz 의 404 본문은 Spring 기본(`path` 반사, 플랫폼 코드 없음)으로 registry 의 `E-IM-404` 와 계약이 다름 · 오래된 주석: registry `QimWebMvcConfig` javadoc 이 `/api/v1/users/**` 를 공개 경로로 적음(실제는 키 필요 — D2 수정), `AuthRateLimitInterceptor`·`AgencyRateLimiter` javadoc 의 Redis 키 `ido:…`(실제 `idem:…`), `LegacySchemaRename` 이 구·신 스키마 공존을 로그로 알리지 않음.

## 4. 확인되어 정상인 것 (요약)

compose ↔ Helm 환경변수 계약 일치(누락은 M8·M9 뿐) · 필수 비밀 집합이 `install.env.example`·compose `:?`·Helm `secrets.create` 필수 목록에서 동일 · `files/*` 사본 동일(CI 대조) · 프로브 경로 5개 앱 모두 존재 · Keycloak 24 production 모드 옵션(`start`, `--import-realm`, health 8080, `KC_PROXY=edge`) 유효 · 이미지 태그 규칙 CI↔Helm 일치 · NetworkPolicy 는 db-init·Keycloak→PG 를 막지 않음(단 기본 `enabled=false`) · 비밀은 Secret 에만, Pod 는 필요한 키만 · 관리자 로그인: 세션 고정 방지(로그인마다 새 id·이전 세션 축출), 쿠키 HttpOnly·SameSite=Strict·Secure 기본, mfaToken GETDEL 1회용·300s, 비밀번호 정책·이력(PBKDF2 310k), CSRF 헤더는 로그인/mfa 포함 모든 쓰기에 요구, 마지막 SYSTEM_ADMIN 보호 · 테넌트 범위: 프로파일·기관 쓰기 전부 검사, 감사 검색은 정확 일치·200 상한 · registry 404 핸들러는 경로 미노출, 내부 API 키 없는 요청은 404 보다 401 이 먼저(경로 열거 불가), 상수 시간 비교 · OIDC 프런트: issuer 정확 일치, `idem-svc-` 접두 강제, `response_type=code`·PKCE S256 강제, redirect_uri 는 Keycloak 이 정확 일치 검증, 정책 거부 시 토큰 미반환 + refresh 폐기 · 프로비저너: confidential·standard flow 만, implicit/direct/service-account/device/CIBA off, `fullScopeAllowed=false`, INACTIVE → `enabled=false`, `idem-provisioner` 는 `manage-clients`·`view-clients` 만 · 이관 도구: API 키 env 만, TLS 검증, CI 미기록, 멱등·resume·dry-run 정상 · `rename-db-1.0.sh` 식별자 고정·연결 가드 · 1.0 문서의 버전·포트·오류 코드·API 경로·잠금/비밀번호 수치·관리 API 본문은 정확(리뷰어 ② 검증 목록) · gitleaks: 1.0 범위 커밋에 실제 비밀 없음(문서 예시·테스트 고정값·미추적 `.env` 뿐).

## 5. 조치 — 1.0.1 보안·설치본 릴리스 (제안)

`v1.0.0` 태그는 그대로 두고(이동 금지) 아래를 `1.0.1` 로 낸다. 순서는 위험 순.

| PR | 내용 | 회귀 테스트 |
|---|---|---|
| **A 보안** | H1 필터 경로 정규화·거부 + 관리/Handoff 엔드포인트 principal 필수 · H2 프록시 경로 정규화·허용 목록 · H3 relay Flyway 분리(shedlock → hub V27) · M2 세션 무효화 · M3 TOTP step · M4 aud/jti/exp · M15 backchannel URI 검증 · M7 actuator 관리 포트 분리 · M17 gate 프런트 IP 레이트리밋 · 405/415 핸들러·authz 404 계약 | `;`·`%61`·`..`·`%2e%2e` 요청 4+6건(master discovery·admin·token 우회 포함), relay+hub 연속 기동 IT, TOTP 재사용, aud `idem-gate-foo`, gate 프런트 25회 → 429 |
| **B 설치본** | H4 숫자 UID · H5 registry 접두 규칙 · H6 `.dockerignore` + 콘솔/포털 이미지 CI 매트릭스 · H7 init-db 가드 + 양쪽 존재 시 기동 거부 · H8 스크립트 임시 슈퍼유저 · M1 repair 1회 조건 · M6 `SPRING_PROFILES_ACTIVE=prod` · M8 플러그인 플래그 · M9 admin URL · M10 redirect URI 자리표시자 · LOW(Trivy 위치, sslMode, Compose ≥2.17) | `helm template --set global.imageRegistry`, 콘솔 이미지 PR 빌드, 업그레이드 리허설 A/C/**Helm 훅 시뮬레이션**, 문서 경로대로 `rename-db-1.0.sh` |
| **C 기능·문서** | H9 `limits` 배선(+ OIDC 프런트) 또는 문서 축소 · H10 예시 수정 · M11 status 기본값 명시 · M12 시험 항목표 재작성(41/51, IT 는 로컬) · M13 regex · M14 지표 이름 · M16 묶음 · NamingGuard 사각(kr-portal `.ts`, `.py`, `AUTHZ_`/`BATCH_`) + 잔재 3건 | 429 테스트, 스키마 검증 테스트에 온보딩 예시 포함, 가드 확장 |

1.0.1 완료 기준: 위 회귀 테스트 CI 통과, 실기동 공격 §1.1·1.2 재실행 → 401/404, Helm `template` 렌더 + (가능하면) kind 클러스터 1회 설치, 문서 시험 항목표의 "자동" 이 실제와 일치.

## 6. 리뷰어가 못 한 것 / 미검증

실제 K8s 클러스터·docker 가 이 환경에 없어 Helm 은 렌더링·kubeconform 까지, Docker 는 CI 결과까지 · Keycloak 의 `..` 정규화는 gate 실기동으로 확인(H2, master realm·admin REST·token 우회까지), Helm 의 `KC_HOSTNAME_STRICT` 조합은 미실행 · `DELETE /api/v1/handoff/{id}` 의 보호 경로(H1 영향 여부) 미확인 · Flyway repair 의 두 Pod 동시 실행 잠금 여부 · 오프라인 설치·백업 복구 절차(설치 매뉴얼 §8 그대로) · 벤더 SDK 가 든 KR 이미지 · 성능 수치.
