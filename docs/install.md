# Idem 설치 가이드 — 단일 설치본

> 범용화 플랜 D1 산출물 (`docs/generalization-plan.md` D1). 대상: 새 환경에 **Idem SSO + Idem IM** 을 한 번에 올리는 설치자.
> 목표: 이 문서 한 장으로 30분 안에 설치하고 로그인 흐름까지 확인한다. Kafka·Vault·모니터링은 필요 없다.

## 1. 무엇이 설치되는가

| 컨테이너 | 제품 | 역할 | 포트(호스트, 127.0.0.1 만) |
|---|---|---|---|
| `idem-hub` | Idem SSO | 정책 오케스트레이터·FE BFF·본인확인 SPI·Handoff | 8083 |
| `idem-gate` | Idem SSO | 인증 결과 SoR, Keycloak OIDC 어댑터 | 8081 |
| `keycloak` | Idem SSO (숨김) | 표준 OIDC 발급·세션. realm `onepass` 자동 import | 8088 (설치자 전용) |
| `idem-registry` | Idem IM | 사용자·식별자·동의·생명주기 SoR (PostgreSQL `qim` 스키마) | 8082 |
| `idem-authz` | Idem IM | 역할·할당 SoR (PostgreSQL `authz` 스키마) | 8086 |
| `idem-console` | 관리 콘솔 | React + Nginx, `/api` → hub | 3001 |
| `postgres` | 데이터 | PostgreSQL 16 — DB 1개(`onepass`), 스키마 `ido`·`qsign`·`qim`·`authz`·`keycloak` | 5432 |
| `redis` | 데이터 | 세션·레이트리밋·캐시 | — |

**에디션 (S8-a).** `install.env` 의 `IDEM_EDITION` 이 `core`(기본)면 Idem SSO + IM 코어만 올라간다 — SMES 회원 개념(NICE CI 조회·기업인증 콜백·회원전환·기관 회원조회·기업회원)이 없고 해당 경로는 404 다. `kr` 이면 `editions/idem-kr-hub`·`idem-kr-registry` 의 KR 에디션 bootJar 로 이미지를 빌드하고(태그 `-kr`) registry 에 KR 마이그레이션(`biz_member`, V1000.1)이 추가로 적용된다. 값을 바꾸면 `--build` 로 다시 빌드한다. 벤더 플러그인(NICE·AnyID)은 에디션과 별개로 `IDEM_PLUGINS_*_ENABLED` 로 켠다.

**Kafka 는 없다.** 모든 앱이 `IDEM_KAFKA_ENABLED=false` 로 뜨며, 아웃박스는 hub 가 DB 를 폴링해 같은 프로세스 안의 핸들러로 배달하고 감사 로그는 DB 에만 남는다(§6). 다중 인스턴스·외부 시스템 연동이 필요해지면 Kafka 를 붙이고 스위치를 `true` 로 바꾼다.

## 2. 준비

- Docker Engine 24+ 와 Compose v2 (`docker compose version`). 메모리 8 GB, CPU 4 vCPU 권장(앱 5개 + Keycloak).
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
| `IDEM_DB_PASSWORD` | PostgreSQL `onepass` 사용자 — Keycloak·모든 앱 공용 |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak 관리 콘솔(설치자 전용) |
| `IDO_INTERNAL_SIG_SECRET`, `IDO_INTERNAL_API_KEY_QSIGN`, `IDO_INTERNAL_API_KEY_OUTBOX`, `QIM_INTERNAL_API_KEY`, `AUTHZ_INTERNAL_API_KEY` | 서비스 간 인증 |
| `QSIGN_KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_CLIENT_SECRET` | Keycloak client secret — realm import 와 앱이 같은 값을 읽는다 |
| `IDO_HANDOFF_AES_KEY`, `IDO_HANDOFF_HMAC_KEY`, `IDO_WEBHOOK_SIGNING_SECRET` | Handoff 티켓·웹훅 서명 |
| `QIM_AES_SHARED_KEY` | registry ↔ hub CI 전달 공유키 (base64 32바이트) |
| `IDO_CAST_PRIVATE_KEY`, `IDO_CAST_PUBLIC_KEY` | SSO 토큰(CAST) Ed25519 서명키 — 아래 명령으로 생성. (D2) 없으면 hub 가 기동을 거부한다 |

```bash
openssl genpkey -algorithm ed25519 -out cast.pem
echo "IDO_CAST_PRIVATE_KEY=$(openssl pkey -in cast.pem -outform DER | base64 -w0)"
echo "IDO_CAST_PUBLIC_KEY=$(openssl pkey -in cast.pem -pubout -outform DER | base64 -w0)"
```

**fail-secure (D2)**: 위 값이 하나라도 비면 해당 컨테이너는 기동하지 않는다. 로컬 개발용 탈출구(`*_ALLOW_EMPTY_*`, `IDO_CAST_ALLOW_GENERATED_KEYS` 등)는 이 설치본에서 쓰지 않으며, `prod`/`stage` 프로파일에서는 켜져 있으면 기동을 거부한다(`docs/sso-im-operations-manual.md` §3.4).

`install.env` 는 `.gitignore` 에 있다. 값을 채팅·티켓·문서에 붙여넣지 않는다.

## 3. 기동

```bash
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml up -d --build
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml ps
```

기동 순서는 compose 가 `depends_on` 으로 보장한다: postgres·redis → keycloak(realm import) → registry·authz·gate → hub → console.
모든 컨테이너가 `healthy` 가 되면(첫 기동 2~3분) 다음으로 간다. 오래 걸리면 `logs -f idem-hub` 로 본다.

## 4. 확인

```bash
curl -s http://localhost:8083/actuator/health            # {"status":"UP"}
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8086/actuator/health
curl -s http://localhost:8083/api/v1/auth/providers       # 활성 본인확인 제공자 목록
```

hub 기동 로그에 다음 줄이 있어야 한다: `[Idem] Kafka 비활성 (idem.messaging.kafka.enabled=false …)` 와 `F-31 kafkaEnabled = OFF`.

## 5. 첫 로그인 흐름 확인 (Mock 제공자)

벤더 플러그인 없이 코어 흐름(본인확인 → registry 등록 → Handoff 티켓)을 확인한다. **설치 검증 뒤에는 반드시 끈다.**

1. `install.env` 에 `IDEM_PLUGINS_MOCK_AUTH_ENABLED=true` 를 두고 `up -d idem-hub` 로 hub 만 재기동.
2. `k6/scenarios/smoke.js` 와 같은 순서로 호출한다 (k6 가 있으면 `k6 run k6/scenarios/smoke.js --env BASE_URL=http://localhost:8083 --env AGENCY_CODE=AGENCY001 --env INTERNAL_API_KEY=<IDO_INTERNAL_API_KEY_QSIGN>`):
   - `POST /api/v1/auth/providers/MOCK/initiate` → `POST /api/v1/auth/providers/MOCK/complete` (응답에 `identity.name`, `registration.qimUserId`)
   - `POST /api/v1/handoff/issue` → 티켓 발급. 몇 초 뒤 `ido.outbox` 의 해당 `HANDOFF_ISSUED` 행이 `PUBLISHED` 로 바뀌면 프로세스 내 배달이 도는 것이다:
     ```sql
     SELECT event_type, topic, status, retry_count FROM ido.outbox ORDER BY created_at DESC LIMIT 5;
     ```
3. `IDEM_PLUGINS_MOCK_AUTH_ENABLED=false` 로 되돌리고 hub 재기동.

### 5.1 표준 OIDC 로 기관 붙이기 (S6 — Keycloak 은 보이지 않는다)

기관이 표준 OIDC Relying Party 로 붙는 경로다. 사람이 Keycloak 콘솔에 들어가는 단계는 없다.

1. 기관 프로파일을 `protocol.type=OIDC_RP` 로 저장한다 — hub 가 같은 트랜잭션에서 Keycloak client `idem-svc-{code}` 를 만든다(실패하면 저장도 되돌린다, `E-IDO-122`):
   ```bash
   curl -X PUT http://localhost:8083/api/v1/admin/services/AGENCY_B/profile -H 'Content-Type: application/json' -H 'X-Admin-Id: installer' -d '{
     "schemaVersion":1, "service":{"code":"AGENCY_B","name":"기관 B","status":"ACTIVE"},
     "protocol":{"type":"OIDC_RP","oidc":{"redirectUris":["https://b.example.org/login/oauth2/code/idem"],
                                          "postLogoutRedirectUris":["https://b.example.org/"]}},
     "policy":{"minAuthLevel":"L1"}}'
   ```
2. client secret 을 한 번 받아 기관에 전달한다(Idem 은 저장하지 않는다 — 다시 보려면 다시 회전):
   ```bash
   curl -X POST http://localhost:8083/api/v1/admin/services/AGENCY_B/oidc-client/secret -H 'X-Admin-Id: installer'
   # → {"clientId":"idem-svc-AGENCY_B","clientSecret":"…","issuer":"http://localhost:8081/realms/onepass","discoveryUrl":"…/.well-known/openid-configuration"}
   ```
3. 기관에는 **issuer·client_id·client_secret** 셋만 준다. 기관 RP 는 `{issuer}/.well-known/openid-configuration` 으로 나머지를 찾는다. Authorization Code + PKCE(S256) 만 허용되며, 토큰 교환 시 hub 가 Idem 정책(점검·인증수준·허용 제공자·사용자 상태·할당)을 판정해 거부하면 토큰이 나가지 않는다(`access_denied`, 사유 `E-IDO-1xx`). userinfo 에는 `idem_service·idem_state·idem_subject·idem_roles·idem_assigned` 가 실린다.

issuer 는 `{IDEM_PUBLIC_URL_GATE}/realms/onepass` 다. gate 가 `/realms/**`·`/resources/**` 를 Keycloak 으로 투명 프록시하므로 리버스 프록시는 gate 하나만 공개하면 된다. Keycloak 콘솔(`http://localhost:8088`, admin / `KEYCLOAK_ADMIN_PASSWORD`)은 설치자의 진단용이며, **Idem 이 만든 client(`idem-svc-*`)를 콘솔에서 고치지 않는다** — 다음 프로파일 저장이 덮어쓴다.

`IDO_BROKER_MODE=keycloak` 은 hub 의 브라우저 로그인(FE 세션) 을 Keycloak 브로커로 돌리는 별개 설정이다.

## 6. Kafka 없이 무엇이 어떻게 도는가

| 흐름 | Kafka 있음 | Kafka 없음(기본) |
|---|---|---|
| hub 인증 이벤트(`qsign.auth.events`) | outbox → Kafka → `QsignAuthEventConsumer` | outbox → `IdoOutboxRelay` 가 같은 프로세스의 `QsignAuthEventConsumer.handle()` 호출 |
| 세션 advisory(`platform.session.advisory`) | Kafka → `FeAdvisoryConsumer` | outbox → `FeAdvisoryConsumer.handle()` |
| Handoff 이벤트(`ido.handoff.events`) | 직접 발행 → `HandoffEventConsumer` → 웹훅 아웃박스 | outbox(티켓 트랜잭션과 원자적) → `HandoffEventConsumer.handle()` → 웹훅 아웃박스 |
| 기관 웹훅 | HTTP 릴레이(F-14) | 동일 |
| 감사 로그 | DB + Kafka(F-03) | DB 만 (F-04) |
| registry `qim.user.events` (hub·gate 캐시 무효화·탈퇴 잠금 전파) | Kafka | **흐르지 않는다** — `qim.outbox` 에 PENDING 으로 남는다. hub 는 registry 를 TTL 캐시(≤5분)로 읽으므로 반영이 최대 TTL 만큼 늦다 |
| gate 아웃박스(`qsign.auth.events`, gate 발행분) | Kafka | **흐르지 않는다** — `qsign` 아웃박스에 PENDING 으로 남는다 |
| `idem-relay`·`idem-tenant-sample` 컨슈머 | Kafka | 정지 (제품 밖) |

멈춘 두 경로는 같은 PostgreSQL 안에 있으므로 다음 단계(D1-c 후보)에서 hub 가 직접 폴링하도록 만들 수 있다. 그 전까지는 **단일 인스턴스 + 이 설치본** 이 기본이고, 다중 인스턴스는 Kafka 경로(`IDEM_KAFKA_ENABLED=true`, `compose.sso-im.yml` + `compose.sso-im-apps.yml` 또는 Helm)를 쓴다.

## 7. 운영 전환 전 체크리스트

- [ ] `IDEM_PLUGINS_MOCK_AUTH_ENABLED=false`
- [ ] `IDEM_PUBLIC_URL_HUB/GATE/CONSOLE` 를 실제 공개 주소(리버스 프록시·TLS)로. 앱 포트는 127.0.0.1 바인딩이므로 프록시가 필요하다
- [ ] `IDEM_PUBLIC_URL_GATE` 를 바꿨으면 keycloak(`KC_HOSTNAME_URL`)·gate·hub 를 함께 재기동 — 표준 OIDC issuer 가 이 값이다. 기관 OIDC client 의 redirect URI 는 프로파일(`protocol.oidc.redirectUris`) 로 관리한다(콘솔 수정 금지). 내부 client(`q-sign-client`·`ido-client`) 의 `redirectUris` 만 `realm-export.json` 첫 import 값이다
- [ ] **S6 이전 설치본 주의**: 종전 `realm-export.json` 의 secret 자리표시자(`${env.X:change-me}`)는 Keycloak 24 가 치환하지 않아 `q-sign-client`·`ido-client` 의 실제 secret 이 문자 그대로 `change-me` 였다(앱 쪽 값과 불일치). S6 에서 `${X}` 로 고쳤지만 realm import 는 첫 기동에만 적용되므로, 기존 설치본은 `keycloak-data` 볼륨을 지우고 다시 import 하거나(권장) 콘솔에서 세 client(`q-sign-client`·`ido-client`·`idem-provisioner`)의 secret 을 `install.env` 값으로 한 번 맞춘다
- [ ] `install.env` 백업을 비밀 저장소에. 키 교체 절차는 `docs/sso-im-operations-manual.md`
- [ ] KR 에디션이 필요하면 `IDEM_EDITION=kr` 로 재빌드. 벤더 플러그인은 `~/.idem/vendor-libs` 공급 후 이미지 재빌드 (`plugins/*/README.md`)
- [ ] 백업: `pg-data` 볼륨(스키마 5개), `keycloak-data`

## 8. 제거

```bash
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml down        # 데이터 유지
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml down -v     # 데이터까지 삭제
```

## 9. 이 문서에서 검증한 것 / 못 한 것

- hub 가 Kafka 없이 기동해 스모크(k6)를 통과하는 것은 CI(`.github/workflows/ci.yml` smoke-test, Kafka 서비스 없음)가 매 PR 확인한다.
- 작성 환경(Docker 없음, 로컬 PostgreSQL·Redis)에서 hub 부트 jar 를 Kafka 없이 기동해 §4·§5 를 수행했다: 16초 기동, Mock 흐름 200, `ido.outbox` PENDING 309건이 프로세스 내 배달로 전부 PUBLISHED.
- **compose.install.yml 의 실제 기동은 Docker 가 없는 작성 환경에서 돌려보지 못했다.** 첫 설치자가 §3~§5 를 수행한 결과(소요 시간·막힌 지점)를 이 절에 기록한다.
