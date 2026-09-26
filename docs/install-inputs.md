# Idem 설치 입력값 목록

> S9 PR-3. 설치자가 **준비해야 하는 값 전부**를 한 표에 모았다. compose 단일 설치본(`infra/docker/install.env.example`)과 Helm 차트(`infra/helm/idem`)는 같은 키 이름을 쓴다 — compose 는 `install.env`, Helm 은 Secret `idem-app-secrets`(앱 비밀) + `idem-db-secret`(DB 사용자) + values.
> 절차는 `docs/install.md`, Helm 은 `infra/helm/idem/README.md`.

## 1. 생성 규칙

| 표기 | 만드는 법 | 쓰임 |
|---|---|---|
| **b64-32** | `openssl rand -base64 32` | AES-256 키 · HMAC 키 · 내부 서명 |
| **hex-32** | `openssl rand -hex 32` | API 키 · Keycloak client secret |
| **pw** | 사람이 정하거나 `openssl rand -base64 18` | 비밀번호 (관리자는 정책: 10자 이상, 대/소문자·숫자·특수문자 중 3종, 사용자명 포함 금지) |
| **ed25519** | `openssl genpkey -algorithm ed25519 -out cast.pem` → DER Base64 (§3) | CAST(SSO 토큰) 서명키 쌍 |

## 2. 비밀 (Secret) — 회전하면 무엇이 깨지는가

| 키 | 규칙 | 누가 읽나 | 회전 영향 |
|---|---|---|---|
| `IDEM_DB_PASSWORD` (Helm: `idem-db-secret`/password) | pw | PostgreSQL 사용자 `idem` · Keycloak · 모든 앱 | DB 쪽과 같이 바꾸고 전부 재기동 |
| `KEYCLOAK_ADMIN_PASSWORD` | pw | Keycloak 관리 콘솔(설치자만) | 없음 |
| `IDEM_HUB_INTERNAL_SIG_SECRET` | 32자 이상 문자열(base64 디코딩 없이 그대로 HMAC 키) | gate ↔ hub 내부 서명 | 둘 다 재기동. 진행 중 요청 실패 |
| `IDEM_HUB_INTERNAL_API_KEY_GATE` | hex-32 | hub(내부 호출자 키 목록 — gate 는 1.0 에서 서명(`IDEM_HUB_INTERNAL_SIG_SECRET`)만 쓰고 이 키는 부르지 않는다; 필수 검사 때문에 값은 있어야 한다) | hub 재기동 |
| `IDEM_HUB_INTERNAL_API_KEY_RELAY` | hex-32 | hub(내부 호출자 키 목록 — relay 는 1.0 에서 이 키를 쓰지 않는다; 필수 검사 때문에 값은 있어야 한다) | hub 재기동 |
| `IDEM_REGISTRY_INTERNAL_API_KEY` | hex-32 | registry(검증) · hub(`IDEM_HUB_REGISTRY_INTERNAL_API_KEY`) · 회원 이관 도구 | 둘 다 재기동 |
| `IDEM_AUTHZ_INTERNAL_API_KEY` | hex-32 | authz(검증) · hub(`IDEM_HUB_AUTHZ_INTERNAL_API_KEY`) | 둘 다 재기동. 비면 hub 가 기동 거부(D2) |
| `IDEM_GATE_KEYCLOAK_CLIENT_SECRET` | hex-32 | Keycloak client `idem-gate` · gate | realm 재import 또는 Keycloak 콘솔에서 같이 변경 |
| `KEYCLOAK_CLIENT_SECRET` | hex-32 | Keycloak client `idem-hub` · hub | 같음 |
| `KEYCLOAK_PROVISIONER_CLIENT_SECRET` | hex-32 | Keycloak client `idem-provisioner` · hub(기관 OIDC client 프로비저닝) | 같음 |
| `KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET` | hex-32 | Keycloak client `idem-session-manager` · gate(단일 로그아웃) | 같음 |
| `IDEM_HUB_HANDOFF_AES_KEY` | b64-32 | hub Handoff 티켓 암호화 | 발급된 티켓(수 분) 무효 |
| `IDEM_HUB_HANDOFF_HMAC_KEY` | b64-32 | hub Handoff 티켓 서명 | 같음 |
| `IDEM_HUB_WEBHOOK_SIGNING_SECRET` | hex-32 | hub(·relay) 기관 웹훅 서명 | 기관에 새 값 전달 |
| `IDEM_REGISTRY_AES_SHARED_KEY` | b64-32 | hub 만 읽는다(registry 로 보내는 CI 봉인; registry 쪽은 `IDEM_REGISTRY_CI_AES_KEY_V1`) | hub 재기동 |
| `IDEM_REGISTRY_DI_SECRET` | hex-32 | registry 기관별 식별자(DI) HMAC | **바꾸면 모든 기관 식별자가 바뀐다 — 사실상 회전 불가** |
| `IDEM_REGISTRY_CI_AES_KEY_V1` | b64-32 | registry 저장 CI 암호화 키 v1 | **바꾸면 기존 CI 를 복호화하지 못한다** — 키 버전을 올리는 절차(F-12)로만 |
| `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` | pw(정책) | hub 첫 관리자 생성 | 관리자가 한 명이라도 있으면 더 쓰이지 않는다 |
| `IDEM_HUB_ADMIN_SECRET_KEY` | b64-32 | hub 관리자 2단계(TOTP) 비밀 봉인 | **바꾸면 모든 관리자가 2단계를 다시 등록한다** |
| `IDEM_HUB_KMS_MASTER_KEY` (1.0.1) | b64-32 | hub 회전된 Handoff 키 재료 봉인(`IDEM_HUB_KMS_PROVIDER=local`, 설치본 기본). Vault 를 쓰면 불필요 | **바꾸면 회전된 키 재료(v2+)를 복호화하지 못한다** — 유예 기간 안의 티켓 검증 실패. v1 키는 환경변수라 영향 없음 |
| `IDEM_HUB_CAST_PRIVATE_KEY` / `IDEM_HUB_CAST_PUBLIC_KEY` | ed25519 | hub CAST 서명·검증(기관 간 SSO) | 상대 플랫폼에 공개키 재전달 |

## 3. CAST 키 만들기

```bash
openssl genpkey -algorithm ed25519 -out cast.pem
IDEM_HUB_CAST_PRIVATE_KEY=$(openssl pkey -in cast.pem -outform DER | base64 -w0)
IDEM_HUB_CAST_PUBLIC_KEY=$(openssl pkey -in cast.pem -pubout -outform DER | base64 -w0)
# cast.pem 은 비밀 저장소로 옮기고 작업 디렉터리에서 지운다
```

## 4. 비민감 설정

| 항목 | compose (`install.env`) | Helm (`values.yaml`) | 기본 | 뜻 |
|---|---|---|---|---|
| 에디션 | `IDEM_EDITION` | `global.edition` (+ `values-kr.yaml`) | core | core / kr. hub·registry 이미지 `<tag>-<edition>` |
| 벤더 SDK(kr) | `IDEM_VENDOR_LIBS_DIR` | 이미지 빌드 시 `--build-context vendor-libs=` | 없음 | OACX·Any-ID SDK jar 디렉터리 |
| 공개 URL | `IDEM_PUBLIC_URL_GATE` / `_HUB` / `_CONSOLE` | `global.publicUrl.gate/hub/console/krPortal` | localhost | gate URL = 표준 OIDC issuer 베이스(`…/realms/idem`). Keycloak `KC_HOSTNAME_URL` 과 같아야 한다 |
| 포트 | `IDEM_PORT_*` | Service 고정 | 8081~ | compose 만 |
| 시간대 | `IDEM_TZ` | `global.timezone` | Asia/Seoul | 일 단위 제한·점검 시간·파기 스케줄 기준 |
| 플랫폼 코드 | `IDEM_PLATFORM_CODE` | `global.platformCode` | IDEM | 기관 간 SSO(CAST) sourceAgency |
| 브로커 모드 | `IDEM_HUB_BROKER_MODE` | `hub.brokerMode` | qsign | qsign(본인확인 SPI) / keycloak(표준 OIDC 로그인) |
| Mock 제공자 | `IDEM_PLUGINS_MOCK_AUTH_ENABLED` | `hub.config.IDEM_PLUGINS_MOCK_AUTH_ENABLED` | false | 설치 검증에만 true, 운영 전 false |
| authz 사용 | 고정 true | `hub.config.IDEM_HUB_AUTHZ_ENABLED` | true | false 면 authz 를 배포하지 않아도 되지만 할당·역할 정책이 없다 |
| Kafka | 없음 | `infra.kafka.enabled` + `bootstrapServers` | false | 켜면 relay 도 배포(`relay.enabled`) |
| 관리자 첫 계정 | `IDEM_HUB_ADMIN_BOOTSTRAP_USERNAME` | `hub.adminBootstrapUsername` | admin | |
| 관리 쿠키 Secure | `IDEM_HUB_ADMIN_COOKIE_SECURE` | `hub.adminCookieSecure` | true | TLS 없는 비-localhost 에서만 false |
| 스프링 프로파일 | `IDEM_SPRING_PROFILE` | `appDefaults.springProfile` | prod | 1.0.1: 설치본은 `application-prod.yml`(WARN 로깅·health 상세 비공개·actuator flyway/features 비노출·보안 헤더)로 뜬다. 진단 때만 `default` |
| actuator 관리 포트 | (앱 포트에 같이 — 프록시가 `/actuator` 를 막는다) | `appDefaults.managementPort` | 9090 | 1.0.1: Helm 은 `IDEM_MANAGEMENT_PORT` 로 actuator 를 관리 포트에 두고 Service·Ingress 는 앱 포트만 내보낸다. 프로브도 관리 포트 |
| DB TLS | `DB_SSLMODE`(compose 내부 PG 는 disable) | `infra.postgres.sslMode` | disable | JDBC `sslmode`(gate·hub·authz; 앱별 `IDEM_GATE_DB_SSLMODE` 등이 우선). registry 는 `IDEM_REGISTRY_DB_SSL`(disable 이 아니면 true) |
| KR 벤더 플러그인 | `IDEM_PLUGINS_NICE_OACX_ENABLED` / `IDEM_PLUGINS_ANYID_ENABLED` | `plugins.niceOacx` / `plugins.anyid` | false | 1.0.1: compose 도 install.env 로 켠다(종전 false 고정). kr 이미지에서만 뜻이 있다 |
| 개명 repair | `IDEM_NAMING_LEGACY_REPAIR` | `hub.config` 등 | true | 0.x → 1.0 업그레이드 뒤 false 로 — 이후 Flyway 체크섬 불일치는 repair 대신 기동 거부 |
| gate 프런트 레이트리밋 | `IDEM_GATE_FRONT_RL_ENABLED` / `_PER_SECOND` / `_PER_MINUTE` / `_TRUST_XFF` | `gate.config.IDEM_GATE_FRONT_RL_*` (env 그대로) | true / 20 / 300 / false | 1.0.1: 공개 OIDC 엔드포인트(`/realms/**`) IP 당 한도. Ingress·리버스 프록시 뒤에서는 `_TRUST_XFF=true`(X-Forwarded-For 마지막 홉이 클라이언트). Redis 장애 시 503 |
| DB 주소 | compose 내부 postgres | `infra.postgres.host/port/database/sslMode` | idem | Helm 은 바깥 PostgreSQL |
| Redis 주소 | compose 내부 redis | `infra.redis.host/port(+existingSecret)` | | Helm 은 바깥 Redis |
| Keycloak | compose 내부 | `keycloak.enabled` / `externalUrl` / `adminUrl` | 차트가 올림 | 밖의 Keycloak 은 realm `idem` + client 4개가 있어야 한다 |
| Ingress | 없음(리버스 프록시 직접) | `ingress.*` | 끔 | TLS 종료는 Ingress. 관리 콘솔 host 는 IP 제한 권장 |

## 5. 기관에 전달하는 값 (설치 뒤)

| 값 | 어디서 | 비고 |
|---|---|---|
| OIDC issuer | `{IDEM_PUBLIC_URL_GATE}/realms/idem` | Discovery `…/.well-known/openid-configuration` |
| client_id / client_secret | 관리 콘솔 서비스 상세 → OIDC client (secret 은 회전 때 한 번만 보인다) | `docs/onboarding-guide.md` |
| Handoff 공개키·웹훅 서명 비밀 | 운영 매뉴얼 | Handoff/AGENT 연동 기관만 |
| CAST 공개키 | `IDEM_HUB_CAST_PUBLIC_KEY` | 기관 간 SSO 상대 플랫폼만 |

## 6. 점검

- `install.env` 의 모든 키가 채워졌는지: CI 설치본 스모크와 같은 방식 — `grep -E '^[A-Z0-9_]+=$' install.env` 가 비어야 한다.
- Helm: `helm template … | grep -c secretKeyRef` 로 참조되는 키가 Secret 에 다 있는지 `kubectl get secret idem-app-secrets -o json | jq '.data | keys'` 와 대조.
- 비밀은 저장소·채팅·티켓에 붙여 넣지 않는다. 로그에도 값이 남지 않도록 앱은 키 이름만 찍는다.
