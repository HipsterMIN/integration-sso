# Idem 설치 가이드 — 단일 설치본

> 범용화 플랜 D1 산출물 (`docs/generalization-plan.md` D1). 대상: 새 환경에 **Idem SSO + Idem IM** 을 한 번에 올리는 설치자.
> 목표: 이 문서 한 장으로 30분 안에 설치하고 로그인 흐름까지 확인한다. Kafka·Vault·모니터링은 필요 없다.

## 1. 무엇이 설치되는가

| 컨테이너 | 제품 | 역할 | 포트(호스트, 127.0.0.1 만) |
|---|---|---|---|
| `idem-hub` | Idem SSO | 정책 오케스트레이터·FE BFF·본인확인 SPI·Handoff | 8083 |
| `idem-gate` | Idem SSO | 인증 결과 SoR, Keycloak OIDC 어댑터 | 8081 |
| `keycloak` | Idem SSO (숨김) | 표준 OIDC 발급·세션. realm `idem` 자동 import | 8088 (설치자 전용) |
| `idem-registry` | Idem IM | 사용자·식별자·동의·생명주기 SoR (PostgreSQL `qim` 스키마) | 8082 |
| `idem-authz` | Idem IM | 역할·할당 SoR (PostgreSQL `authz` 스키마) | 8086 |
| `idem-console-admin` | 관리 콘솔 (S7) | React + Nginx, `/api/v1/admin` → hub 같은 출처. 관리자 로그인(2단계)·기관 온보딩·OIDC client·감사·관리자 관리 | 3001 |
| `idem-kr-portal` | KR 에디션 회원 포털 (구 `idem-console`, `--profile kr`) | 회원전환·본인확인 위젯·마이페이지 | 3002 |
| `postgres` | 데이터 | PostgreSQL 16 — DB 1개(`idem`), 스키마 `idem_hub`·`idem_gate`·`idem_registry`·`idem_authz`·`keycloak` | 5432 |
| `redis` | 데이터 | 세션·레이트리밋·캐시 | — |

**에디션 (S8-a).** `install.env` 의 `IDEM_EDITION` 이 `core`(기본)면 Idem SSO + IM 코어만 올라간다 — SMES 회원 개념(NICE CI 조회·기업인증 콜백·회원전환·기관 회원조회·기업회원)이 없고 해당 경로는 404 다. `kr` 이면 `editions/idem-kr-hub`·`idem-kr-registry` 의 KR 에디션 bootJar 로 이미지를 빌드하고(태그 `-kr`) registry 에 KR 마이그레이션(`biz_member`, V1000.1)이 추가로 적용된다. 값을 바꾸면 `--build` 로 다시 빌드한다. 벤더 플러그인(NICE·AnyID)은 에디션과 별개로 `IDEM_PLUGINS_*_ENABLED` 로 켠다.

**Kafka 는 없다.** 모든 앱이 `IDEM_KAFKA_ENABLED=false` 로 뜨며, 아웃박스는 hub 가 DB 를 폴링해 같은 프로세스 안의 핸들러로 배달하고 감사 로그는 DB 에만 남는다(§6). 다중 인스턴스·외부 시스템 연동이 필요해지면 Kafka 를 붙이고 스위치를 `true` 로 바꾼다.

> **K8s 로 설치하려면** 같은 계약의 Helm 차트 `infra/helm/idem` (README) 를 쓴다 — 컴포넌트·환경변수·비밀 키 이름이 이 문서와 같다. 준비해야 하는 값 전부와 회전 영향은 `docs/install-inputs.md`. KR 에디션은 `IDEM_EDITION=kr`(compose) / `values-kr.yaml`(Helm).

## 2. 준비

- Docker Engine 24+ 와 **Docker Compose ≥ 2.17** (`docker compose version` — `build.additional_contexts` 를 쓴다). 메모리 8 GB, CPU 4 vCPU 권장(앱 5개 + Keycloak).
- 호스트에서 비어 있어야 하는 포트: 8081·8082·8083·8086·8088·3001·5432 (모두 `install.env` 로 바꿀 수 있다).
- 소스 체크아웃 (이미지는 설치 시 빌드한다. 첫 빌드 10~20분, Gradle 의존성 다운로드 포함).

```bash
git clone https://github.com/HipsterMIN/integration-sso.git idem && cd idem
cp infra/docker/install.env.example infra/docker/install.env
```

`install.env` 의 "생성" 항목을 전부 채운다. 예:

```bash
# base64 32바이트 (AES·HMAC·내부 서명)
openssl rand -base64 32
# hex 32 (API 키·client secret)
openssl rand -hex 32
```

| 변수 | 용도 |
|---|---|
| `IDEM_DB_PASSWORD` | PostgreSQL `idem` 사용자 — Keycloak·모든 앱 공용 |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak 관리 콘솔(설치자 전용) |
| `IDEM_HUB_INTERNAL_SIG_SECRET`, `IDEM_HUB_INTERNAL_API_KEY_GATE`, `IDEM_HUB_INTERNAL_API_KEY_RELAY`, `IDEM_REGISTRY_INTERNAL_API_KEY`, `IDEM_AUTHZ_INTERNAL_API_KEY` | 서비스 간 인증 |
| `IDEM_GATE_KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_CLIENT_SECRET` | Keycloak client secret — realm import 와 앱이 같은 값을 읽는다 |
| `IDEM_HUB_HANDOFF_AES_KEY`, `IDEM_HUB_HANDOFF_HMAC_KEY`, `IDEM_HUB_WEBHOOK_SIGNING_SECRET` | Handoff 티켓·웹훅 서명 |
| `IDEM_REGISTRY_AES_SHARED_KEY` | registry ↔ hub CI 전달 공유키 (base64 32바이트) |
| `IDEM_REGISTRY_DI_SECRET`, `IDEM_REGISTRY_CI_AES_KEY_V1` | registry 기관별 식별자(DI) HMAC 비밀(hex 32)·저장 CI 암호화 키(base64 32바이트). (D3) 종전 예시에 빠져 registry 가 기동을 거부했다 — 바꾸면 기존 식별자·CI 를 잃는다 |
| `KEYCLOAK_PROVISIONER_CLIENT_SECRET`, `KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET` | hub 의 OIDC client 프로비저닝 서비스 계정 · gate 의 단일 로그아웃 서비스 계정 |
| `IDEM_HUB_CAST_PRIVATE_KEY`, `IDEM_HUB_CAST_PUBLIC_KEY` | SSO 토큰(CAST) Ed25519 서명키 — 아래 명령으로 생성. (D2) 없으면 hub 가 기동을 거부한다 |
| `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD`, `IDEM_HUB_ADMIN_SECRET_KEY` | (S7) 첫 관리자(`admin`, SYSTEM_ADMIN)의 초기 비밀번호(10자 이상·3종 문자·`admin` 포함 금지, 첫 로그인에서 변경 요구) · 관리자 2단계(TOTP) 비밀 봉인 키(base64 32바이트). 관리 API 는 관리자 로그인 뒤에만 열린다 — `docs/admin-auth.md` |

```bash
openssl genpkey -algorithm ed25519 -out cast.pem
echo "IDEM_HUB_CAST_PRIVATE_KEY=$(openssl pkey -in cast.pem -outform DER | base64 -w0)"
echo "IDEM_HUB_CAST_PUBLIC_KEY=$(openssl pkey -in cast.pem -pubout -outform DER | base64 -w0)"
```

**fail-secure (D2)**: 위 값이 하나라도 비면 해당 컨테이너는 기동하지 않는다. 로컬 개발용 탈출구(`*_ALLOW_EMPTY_*`, `IDEM_HUB_CAST_ALLOW_GENERATED_KEYS` 등)는 이 설치본에서 쓰지 않으며, `prod`/`stage` 프로파일에서는 켜져 있으면 기동을 거부한다(`docs/sso-im-operations-manual.md` §3.4).

`install.env` 는 `.gitignore` 에 있다. 값을 채팅·티켓·문서에 붙여넣지 않는다.

**이름 규칙 (S9, 2026-09-25)**: 환경변수는 `IDEM_<모듈>_<이름>` — `IDEM_HUB_*`(hub)·`IDEM_GATE_*`(gate)·`IDEM_REGISTRY_*`(registry)·`IDEM_AUTHZ_*`(authz)·`IDEM_RELAY_*`(relay), 설치본 공통은 `IDEM_*`(`IDEM_PUBLIC_URL_*`·`IDEM_PORT_*`·`IDEM_EDITION`·`IDEM_PLUGINS_*`), 데이터·Keycloak·벤더는 제 이름(`DB_*`·`REDIS_*`·`KEYCLOAK_*`·`NICE_*`). **S9 이전 설치본의 `install.env`(`IDO_*`·`QIM_*`·`QSIGN_*`·`AUTHZ_*`·`IDEM_ADMIN_*`)는 한 릴리스 동안 그대로 동작한다** — 앱이 구 이름을 새 이름으로 비춰 읽고 기동 로그에 `[Idem 개명] 구 이름 N개…` WARN 을 남긴다. 대응표는 `docs/naming.md` §3.1. 업그레이드 때 Redis 를 비운다(키 접두 `ido:*` → `idem:*`, 사용자·관리자 세션은 재로그인).

## 3. 기동

```bash
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml up -d --build
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml ps
```

기동 순서는 compose 가 `depends_on` 으로 보장한다: postgres·redis → keycloak(realm import) → registry·authz·gate → hub → console. KR 에디션의 회원 포털은 `docker compose --profile kr … up -d` 로 함께 올린다.
모든 컨테이너가 `healthy` 가 되면(첫 기동 2~3분) 다음으로 간다. 오래 걸리면 `logs -f idem-hub` 로 본다.

## 4. 확인

```bash
curl -s http://localhost:8083/actuator/health            # {"status":"UP"}
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8086/actuator/health
curl -s http://localhost:8083/api/v1/auth/providers       # 활성 본인확인 제공자 목록
```

hub 기동 로그에 다음 줄이 있어야 한다: `[Idem] Kafka 비활성 (idem.messaging.kafka.enabled=false …)` 와 `F-31 kafkaEnabled = OFF`,
그리고 첫 기동이면 `[AdminBootstrap] 첫 SYSTEM_ADMIN 'admin' 생성`.

### 4.1 관리자 로그인 (S7 — 관리 API 는 로그인 뒤에만 열린다)

`/api/v1/admin/**` 은 관리자 세션(비밀번호 + 2단계 TOTP) 없이는 `401 E-IDO-130` 이다. 첫 로그인은 (1) `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 로 로그인 →
(2) 서버가 준 TOTP 비밀(`secret`/`otpauthUri`)을 인증 앱에 등록하고 코드 제출 → (3) 비밀번호 변경. 이 흐름을 그대로 하는 스크립트가 있다:

```bash
SID=$(IDEM_ADMIN_PASSWORD="$IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD" IDEM_ADMIN_NEW_PASSWORD='<새 비밀번호>' \
      IDEM_ADMIN_TOTP_SECRET_FILE=~/.idem/admin-totp-secret scripts/lib/admin-login.sh)
#   → 2단계 비밀을 등록하고 ~/.idem/admin-totp-secret 에 저장했다 (0600)   ← 운영에서는 이 값을 인증 앱에 옮기고 파일은 지운다
#   → 첫 로그인 비밀번호를 바꿨다 (username=admin)
ADM=(-H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: install')          # 쓰기 요청은 X-Requested-With 가 없으면 403
curl -s http://localhost:8083/api/v1/admin/auth/me "${ADM[@]}"                 # {"username":"admin","role":"SYSTEM_ADMIN",…}
```

아래 §5.1 의 관리 API 호출은 모두 `"${ADM[@]}"` 를 붙인다. 세션은 유휴 15분·절대 8시간·동시 1개다. 역할·잠금·비밀번호 정책·추가 관리자 생성은 `docs/admin-auth.md`.

**브라우저로는 관리 콘솔** `http://localhost:3001` (S7 PR-2) — 같은 계정으로 로그인하면 2단계 비밀(첫 로그인)과 비밀번호 변경을 화면이 안내하고, 기관 온보딩·OIDC client secret·감사 조회·관리자 관리를 curl 없이 한다. 아래 §5.1 의 1·2·5 단계는 콘솔의 "기관 → 새 기관 온보딩" 폼과 기관 상세의 "표준 OIDC client"·감사 화면과 같다.

## 5. 첫 로그인 흐름 확인 (Mock 제공자)

벤더 플러그인 없이 코어 흐름(본인확인 → registry 등록 → Handoff 티켓)을 확인한다. **설치 검증 뒤에는 반드시 끈다.**

1. `install.env` 에 `IDEM_PLUGINS_MOCK_AUTH_ENABLED=true` 와 **`IDEM_SPRING_PROFILE=default`** 를 두고 `up -d idem-hub` 로 hub 만 재기동. (1.0.1 부터 앱은 기본 `prod` 프로파일로 뜨는데, `prod`/`stage` 에서 Mock 제공자는 fail-secure 가드가 기동을 거부한다 — 검증 동안만 기본 프로파일)
2. `k6/scenarios/smoke.js` 와 같은 순서로 호출한다 (k6 가 있으면 `k6 run k6/scenarios/smoke.js --env BASE_URL=http://localhost:8083 --env AGENCY_CODE=AGENCY001 --env INTERNAL_API_KEY=<IDEM_HUB_INTERNAL_API_KEY_GATE>`):
   - `POST /api/v1/auth/providers/MOCK/initiate` → `POST /api/v1/auth/providers/MOCK/complete` (응답에 `identity.name`, `registration.qimUserId`)
   - `POST /api/v1/handoff/issue` → 티켓 발급. 몇 초 뒤 `idem_hub.outbox` 의 해당 `HANDOFF_ISSUED` 행이 `PUBLISHED` 로 바뀌면 프로세스 내 배달이 도는 것이다:
     ```sql
     SELECT event_type, topic, status, retry_count FROM idem_hub.outbox ORDER BY created_at DESC LIMIT 5;
     ```
3. `IDEM_PLUGINS_MOCK_AUTH_ENABLED=false` 로 되돌리고 `IDEM_SPRING_PROFILE` 줄을 지운 뒤(=`prod`) hub 재기동.

### 5.1 표준 OIDC 로 기관 붙이기 (S6 — Keycloak 은 보이지 않는다)

기관이 표준 OIDC Relying Party 로 붙는 경로다. 사람이 Keycloak 콘솔에 들어가는 단계는 없다.

1. 기관 프로파일을 `protocol.type=OIDC_RP` 로 저장한다 — hub 가 같은 트랜잭션에서 Keycloak client `idem-svc-{code}` 를 만든다(실패하면 저장도 되돌린다, `E-IDO-122`):
   ```bash
   curl -X PUT http://localhost:8083/api/v1/admin/services/AGENCY_B/profile -H 'Content-Type: application/json' "${ADM[@]}" -d '{
     "schemaVersion":1, "service":{"code":"AGENCY_B","name":"기관 B","status":"ACTIVE"},
     "protocol":{"type":"OIDC_RP","oidc":{"redirectUris":["https://b.example.org/login/oauth2/code/idem"],
                                          "postLogoutRedirectUris":["https://b.example.org/"]}},
     "policy":{"minAuthLevel":"L1"}}'
   ```
2. client secret 을 한 번 받아 기관에 전달한다(Idem 은 저장하지 않는다 — 다시 보려면 다시 회전):
   ```bash
   curl -X POST http://localhost:8083/api/v1/admin/services/AGENCY_B/oidc-client/secret "${ADM[@]}"
   # → {"clientId":"idem-svc-AGENCY_B","clientSecret":"…","issuer":"http://localhost:8081/realms/idem","discoveryUrl":"…/.well-known/openid-configuration"}
   ```
3. 로그아웃까지 표준으로 끝난다(S6 PR-2): 기관 RP 가 `end_session_endpoint` 로 RP-Initiated Logout 을 보내거나 Idem 쪽에서 세션을 끊으면, Keycloak 이 프로파일 `protocol.oidc.backchannelLogoutUri` 로 Back-Channel Logout 을 보낸다. Idem 자신의 FE 세션은 gate 의 `/api/v1/oidc/backchannel-logout` 수신기가 정리한다(realm import 의 내부 client 가 가리킴 — `IDEM_PUBLIC_URL_GATE` 가 아니라 컨테이너 내부 주소 `http://idem-gate:8081` 이다). 세션 종료용 서비스 계정 비밀 `KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET` 이 없으면 FE 세션은 끝나지만 Keycloak 세션은 남는다(gate 로그 `[KeycloakLogout]`).
4. 기관 샘플(`idem-tenant-sample`)로 확인: `AGENCY_PROTOCOL=OIDC_RP AGENCY_OIDC_CLIENT_ID=idem-svc-AGENCY_B AGENCY_OIDC_CLIENT_SECRET=… IDEM_OIDC_ISSUER={IDEM_PUBLIC_URL_GATE}/realms/idem` 로 띄우고 `/agency/oidc/login` → 로그인 → `/agency/oidc/logout`. 프로파일의 `redirectUris` 는 `http://<샘플>/agency/oidc/callback`, `backchannelLogoutUri` 는 `http://<샘플>/agency/oidc/backchannel-logout`.
5. 감사 조회(S7): 방금 한 관리 행위와 로그인이 남아 있다 — `curl -s 'http://localhost:8083/api/v1/admin/audit?agencyCode=AGENCY_B' "${ADM[@]}"`, `…/audit?category=ADMIN`.
6. 기관에는 **issuer·client_id·client_secret** 셋만 준다. 기관 RP 는 `{issuer}/.well-known/openid-configuration` 으로 나머지를 찾는다. Authorization Code + PKCE(S256) 만 허용되며, 토큰 교환 시 hub 가 Idem 정책(점검·인증수준·허용 제공자·사용자 상태·할당)을 판정해 거부하면 토큰이 나가지 않는다(`access_denied`, 사유 `E-IDO-1xx`). userinfo 에는 `idem_service·idem_state·idem_subject·idem_roles·idem_assigned` 가 실린다.

issuer 는 `{IDEM_PUBLIC_URL_GATE}/realms/idem` 다. gate 가 `/realms/**`·`/resources/**` 를 Keycloak 으로 투명 프록시하므로 리버스 프록시는 gate 하나만 공개하면 된다. Keycloak 콘솔(`http://localhost:8088`, admin / `KEYCLOAK_ADMIN_PASSWORD`)은 설치자의 진단용이며, **Idem 이 만든 client(`idem-svc-*`)를 콘솔에서 고치지 않는다** — 다음 프로파일 저장이 덮어쓴다.

`IDEM_HUB_BROKER_MODE=keycloak` 은 hub 의 브라우저 로그인(FE 세션) 을 Keycloak 브로커로 돌리는 별개 설정이다.

## 6. Kafka 없이 무엇이 어떻게 도는가

| 흐름 | Kafka 있음 | Kafka 없음(기본) |
|---|---|---|
| hub 인증 이벤트(`idem.gate.auth.events`) | outbox → Kafka → `QsignAuthEventConsumer` | outbox → `IdoOutboxRelay` 가 같은 프로세스의 `QsignAuthEventConsumer.handle()` 호출 |
| 세션 advisory(`platform.session.advisory`) | Kafka → `FeAdvisoryConsumer` | outbox → `FeAdvisoryConsumer.handle()` |
| Handoff 이벤트(`idem.hub.handoff.events`) | 직접 발행 → `HandoffEventConsumer` → 웹훅 아웃박스 | outbox(티켓 트랜잭션과 원자적) → `HandoffEventConsumer.handle()` → 웹훅 아웃박스 |
| 기관 웹훅 | HTTP 릴레이(F-14) | 동일 |
| 감사 로그 | DB + Kafka(F-03) | DB 만 (F-04) |
| registry `idem.registry.user.events` (hub·gate 캐시 무효화·탈퇴 잠금 전파) | Kafka | **흐르지 않는다** — `idem_registry.outbox` 에 PENDING 으로 남는다. hub 는 registry 를 TTL 캐시(≤5분)로 읽으므로 반영이 최대 TTL 만큼 늦다 |
| gate 아웃박스(`idem.gate.auth.events`, gate 발행분) | Kafka | **흐르지 않는다** — `qsign` 아웃박스에 PENDING 으로 남는다 |
| `idem-relay`·`idem-tenant-sample` 컨슈머 | Kafka | 정지 (제품 밖) |

멈춘 두 경로는 같은 PostgreSQL 안에 있으므로 다음 단계(D1-c 후보)에서 hub 가 직접 폴링하도록 만들 수 있다. 그 전까지는 **단일 인스턴스 + 이 설치본** 이 기본이고, 다중 인스턴스는 Kafka 경로(`IDEM_KAFKA_ENABLED=true`, `compose.sso-im.yml` + `compose.sso-im-apps.yml` 또는 Helm)를 쓴다.

## 7. 운영 전환 전 체크리스트

- [ ] `IDEM_PLUGINS_MOCK_AUTH_ENABLED=false` — 그리고 `IDEM_SPRING_PROFILE` 을 지워 `prod` 로(Mock 이 켜진 채 `prod` 면 hub 가 기동을 거부한다)
- [ ] `IDEM_PUBLIC_URL_HUB/GATE/CONSOLE` 를 실제 공개 주소(리버스 프록시·TLS)로. 앱 포트는 127.0.0.1 바인딩이므로 프록시가 필요하다. 관리 콘솔(3001)은 관리자 망에만 공개하고, TLS 뒤에 둔다(관리 세션 쿠키가 Secure). **리버스 프록시는 `/actuator` 를 밖으로 내보내지 않는다** — compose 는 actuator 가 앱 포트에 같이 있다(Helm 은 관리 포트 9090 으로 분리). 앱은 기본 `prod` 프로파일(`IDEM_SPRING_PROFILE`)로 떠서 health 상세·flyway 가 비노출이지만 `metrics`·`prometheus` 는 열려 있다
- [ ] `IDEM_PUBLIC_URL_GATE` 를 바꿨으면 keycloak(`KC_HOSTNAME_URL`)·gate·hub 를 함께 재기동 — 표준 OIDC issuer 가 이 값이다. 기관 OIDC client 의 redirect URI 는 프로파일(`protocol.oidc.redirectUris`) 로 관리한다(콘솔 수정 금지). 내부 client(`idem-gate`·`idem-hub`) 의 `redirectUris` 만 `realm-export.json` 첫 import 값이다
- [ ] **S6 이전 설치본 주의**: 종전 `realm-export.json` 의 secret 자리표시자(`${env.X:change-me}`)는 Keycloak 24 가 치환하지 않아 `idem-gate`·`idem-hub` 의 실제 secret 이 문자 그대로 `change-me` 였다(앱 쪽 값과 불일치). S6 에서 `${X}` 로 고쳤지만 realm import 는 첫 기동에만 적용되므로, 기존 설치본은 `keycloak-data` 볼륨을 지우고 다시 import 하거나(권장) 콘솔에서 세 client(`idem-gate`·`idem-hub`·`idem-provisioner`)의 secret 을 `install.env` 값으로 한 번 맞춘다
- [ ] (S7) 부트스트랩 관리자의 첫 로그인(비밀번호 변경·2단계 등록)을 마쳤고, `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 는 더 쓰이지 않는다(관리자가 있으면 무시된다). 운영 관리자는 인증 앱을 쓴다 — `admin-login.sh` 의 비밀 파일은 설치 확인용
- [ ] (S7) 관리자 계정을 역할별로 나눈다(`SYSTEM_ADMIN` 최소 2명 — 한 명이 2단계를 잃으면 다른 한 명이 `reset-mfa`, `POLICY_ADMIN`, `AUDITOR`). `IDEM_HUB_ADMIN_COOKIE_SECURE=true`, `IDEM_HUB_ADMIN_MFA_REQUIRED=true` 가 기본이며 `prod`/`stage` 에서 false 면 기동 거부
- [ ] (S9) `install.env` 의 변수명을 새 이름(`IDEM_HUB_*` …)으로 옮겼다 — 구 이름 호환은 한 릴리스뿐이다. 기동 로그에 `[Idem 개명]` WARN 이 없으면 끝난 것
- [ ] (S9 5단계) **S9 이전 설치본을 올리는 경우**: 앱·Keycloak 을 내리고 `scripts/upgrade/rename-db-1.0.sh`(DB `onepass`→`idem`, 역할, 스키마 `ido/qsign/qim/authz`→`idem_hub/idem_gate/idem_registry/idem_authz`)를 postgres 에 실행한다 — `docker compose … exec -e PGUSER=onepass -e PGPASSWORD=$IDEM_DB_PASSWORD -e IDEM_DB_PASSWORD=$IDEM_DB_PASSWORD postgres bash -s < scripts/upgrade/rename-db-1.0.sh`. 1.0.1 부터 실행 사용자가 `onepass` 여도 된다(임시 슈퍼유저를 만들어 역할을 옮기고 지운다) 하고, `IDEM_DB_PASSWORD` 를 주면 역할 rename 으로 지워질 수 있는 MD5 비밀번호를 다시 설정한다. 스키마는 앱이 첫 기동에서 자동으로도 옮기지만(`[Idem 개명] 스키마 …` WARN 뒤 체크섬 불일치만 1회 repair), DB 이름은 앱 밖에서만 바꿀 수 있다. 구 스키마와 새 스키마가 **둘 다** 있고 새 쪽에 Flyway 이력이 없으면(새 스키마가 먼저 만들어진 상태) 스크립트와 앱이 멈춘다 — 빈 새 스키마를 DROP 한 뒤 다시. 업그레이드가 끝나면 `IDEM_NAMING_LEGACY_REPAIR=false` 로 두어 이후의 체크섬 불일치는 기동 거부가 되게 한다. Keycloak realm `onepass`→`idem` 은 import 로만 되므로 `keycloak-data` 볼륨을 지우고 다시 올린다 — 기관 OIDC client 는 프로파일을 다시 저장하면 hub 가 다시 만든다(secret 은 새로 회전·전달). issuer 가 `…/realms/idem` 으로 바뀌므로 기관 RP 설정도 함께 바꾼다. 관리자 계정은 DB 와 함께 옮겨지므로 기존 비밀번호·인증 앱 그대로다
- [ ] `install.env` 백업을 비밀 저장소에. 키 교체 절차는 `docs/sso-im-operations-manual.md`
- [ ] KR 에디션이 필요하면 `IDEM_EDITION=kr` 로 재빌드. 벤더 플러그인은 `~/.idem/vendor-libs` 공급 후 이미지 재빌드 (`plugins/*/README.md`)
- [ ] 백업: `pg-data` 볼륨(스키마 5개), `keycloak-data`

## 8. 제거

```bash
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml down        # 데이터 유지
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml down -v     # 데이터까지 삭제
```

## 9. 이 문서에서 검증한 것 / 못 한 것

- **CI 가 매 PR 마다 설치본을 실기동한다** (`.github/workflows/ci.yml` smoke-test, D3): `install.env.example` 의 필수 키를 1회용 값으로 채워
  `compose.install.yml` 을 `config` 로 렌더링하고, compose 와 같은 이미지·realm import·hostname 의 Keycloak 컨테이너와 registry·authz·gate·hub
  부트 jar 를 compose 의 환경변수 이름 그대로 띄운 뒤 `scripts/ci/install-smoke.sh` 가 §4·§5.1 을 자동으로 수행한다 — 4개 헬스 · gate 를 통한
  Discovery(issuer = `{IDEM_PUBLIC_URL_GATE}/realms/idem`) · OIDC_RP 프로파일 PUT → Keycloak client 생성·secret 회전 · gate 프런트의 로그인
  화면 프록시(PKCE 사전검사, 타 client 거부) · Mock 본인확인 → registry 등록 · registry 이벤트 피드(Kafka 없는 상태 전파) · 코어 에디션의 KR
  엔드포인트 404 · (S7) 관리자 로그인(2단계 등록·첫 비밀번호 변경)과 무인증 관리 API/actuator 401·CSRF 403 · 감사 조회(로그인·비밀번호 변경·거부·기관 관리 행위) → 로그아웃.
  이어서 k6 스모크가 hub + 실제 registry 로 돈다(종전에는 registry 가 Node 스텁이었다).
- 같은 스크립트를 로컬(PostgreSQL 16·Redis·Keycloak 24.0.5 + 부트 jar 4개)에서 돌려 2026-09-25 전 항목 통과를 확인했다. 그 과정에서 registry
  부팅 가드가 요구하는 `IDEM_REGISTRY_DI_SECRET`·`IDEM_REGISTRY_CI_AES_KEY_V1` 이 compose·예시 env 에 빠져 있던 것을 잡아 §2 에 넣었다.
- S7(2026-09-25): 같은 로컬 스택에서 관리자 로그인이 포함된 스모크 전 항목과 `scripts/dev/seed-dev-agencies.sh`(첫 실행 2단계 등록 → 두 번째 실행 저장된 비밀 재사용) 를 확인했다.
- **못 한 것**: `docker compose up --build` 로 이미지 5개를 빌드해 올리는 것 자체는 PR 게이트에서 돌리지 않는다(이미지 빌드 시간). Dockerfile
  빌드는 main 의 docker-build 잡이, 실행 환경 계약은 위 스모크가 검증하므로 남는 차이는 컨테이너 네트워크(서비스 이름 `idem-*`)와 볼륨뿐이다.
  첫 설치자가 §3~§5 를 수행한 결과(소요 시간·막힌 지점)를 이 절에 기록한다.
