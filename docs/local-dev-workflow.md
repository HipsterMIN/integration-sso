# 로컬 개발 워크플로 — WSL2 · git 훅 · 변경 모듈 테스트

> 작성 2026-09-07 · 배경: 사무실 망(TLS 검사 프록시·IPv6 불가·저대역폭)에서 자체 호스팅 러너 CI 가 느리고 불안정해,
> 빌드·포맷·테스트를 **로컬에서 먼저** 끝내고 CI 는 PR 게이트로만 쓰기로 했다. Testcontainers 통합 테스트(CI 는
> `DOCKER_UNAVAILABLE=true` 로 항상 skip)도 로컬 push 전에 돈다.

## 1. 한 번만 하는 준비

| 단계 | 명령 | 비고 |
|---|---|---|
| 소스는 WSL 안에 | `git clone https://github.com/HipsterMIN/integration-sso.git ~/work/integration-sso` | `/mnt/c`, `/mnt/d` 에 두면 Gradle I/O 가 수십 배 느리고 `gradlew` 권한·줄바꿈 문제가 생긴다. Windows IDE 는 `\\wsl$\Ubuntu-22.04\home\<user>\work\…` 로 연다 |
| JDK 21 (SDKMAN) | `sdk install java 21.0.12-tem && sdk default java 21.0.12-tem` | 러너와 같은 Temurin 21 계열 |
| git 사용자 | `git config --global user.name "…" && git config --global user.email "…"` | |
| **훅 설치** | `scripts/dev/install-git-hooks.sh` | `core.hooksPath=.githooks` 로 지정. 되돌리기 `git config --unset core.hooksPath` |
| Docker | Docker Desktop WSL 통합 또는 Docker Engine | 없으면 Testcontainers 테스트는 자동 skip |

첫 빌드: `./gradlew build -x test --no-daemon` (Gradle 9.5 배포판·의존성 다운로드 수 분).

## 5. 관리 콘솔 화면 캡처 — 관리자 매뉴얼 그림 재생성 (1.1.1 G2-3)

`scripts/dev/console-screenshots.cjs` 가 실제 스택과 콘솔 앞에서 Playwright(Chromium)로 첫 로그인(2단계 등록 QR → 코드 → 비밀번호 변경) → 데모 데이터 시드(기관 3·동의 항목·역할·할당·테넌트·관리자, 관리 API) → 화면 18장을 `docs/manuals/images/console/` 에 쓴다.

```bash
# 스택: hub(8083)·registry(8082)·authz(8086) — 설치본(compose) 또는 CI smoke-test 잡의 기동 레시피. 콘솔: idem-console-admin 에서 npm run build && npm run preview (3001, /api → hub)
CONSOLE_URL=http://127.0.0.1:3001 IDEM_ADMIN_PASSWORD=<부트스트랩 비밀번호> IDEM_ADMIN_NEW_PASSWORD=<첫 로그인 뒤 값> \
IDEM_ADMIN_TOTP_SECRET_FILE=/tmp/idem-admin-totp NODE_PATH=$(npm root -g) node scripts/dev/console-screenshots.cjs
```

- 첫 실행이 2단계를 등록하고 비밀번호를 바꾼다(QR 캡처). 재실행은 `IDEM_ADMIN_PASSWORD` 가 틀리면 `IDEM_ADMIN_NEW_PASSWORD` 로 다시 로그인하고, 2단계 비밀은 `IDEM_ADMIN_TOTP_SECRET_FILE`(0600)에서 읽는다. 같은 30초 스텝의 코드 재사용 거부(1.0.1)는 다음 스텝 코드로 자동 재시도한다.
- `SEED=0` 이면 시드를 건너뛴다(이미 시드된 스택 — 동의 항목은 매번 새 버전이 발행되므로 재실행은 `SEED=0`).
- Playwright 패키지는 전역 설치(`NODE_PATH`)나 프로젝트 설치 어느 쪽이든 되고, Chromium 은 `PLAYWRIGHT_BROWSERS_PATH` 의 것을 쓴다. 비밀번호·2단계 비밀은 출력하지 않는다.
- **이용자 화면(사용자 매뉴얼)**: `scripts/dev/login-front-screenshots.cjs` — hub 에 Mock 제공자(`IDEM_PLUGINS_MOCK_AUTH_ENABLED=true`)와 두 번째 인증 방법(`IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS=keycloak`, 선택 화면용)이 있고 위 시드가 끝난 뒤 `NODE_PATH=$(npm root -g) node scripts/dev/login-front-screenshots.cjs`. 기관 콜백(`http://portal.example.go.kr:8444/idem/callback`, 시드가 화이트리스트에 넣는다)은 스크립트가 띄우는 예시 서버가 받고 호스트는 Chromium `host-resolver-rules` 로 127.0.0.1 에 맵한다 — DNS·TLS 불필요. 이 둘은 서버 단 테스트가 못 잡는 브라우저 결함(CSP·헤더 인코딩)을 잡았으니 설치본 검증 때도 한 번 돌린다.

## 2. 커밋할 때 — docs-lint + Spotless (pre-commit)

**docs-lint(1.1.1 G2)**: 스테이징에 `.md`·버전 파일(`build.gradle.kts`·Helm `Chart.yaml`·콘솔 `package.json`·hub `application.yml`·웹훅 디스패처)이 있으면 `python3 scripts/ci/docs-lint.py` 가 먼저 돈다 — 버전 단일 출처(루트 `build.gradle.kts` ↔ 차트·콘솔·SDK·웹훅 `platformVersion`·현재 버전 문구·매뉴얼), 시험 항목표 집계(표 행 수 = 제목 = 합계 = 그룹 합, docs/README·gs-kickoff 수치), 운영 문서의 오류 코드가 코드에 정의돼 있는지, 구 이름(`IDO_*`·`${ido.*}`), 상대 링크. CI 잡 `문서 정합성` 이 같은 스크립트를 돌린다. 건너뛰기 `SKIP_DOCS_LINT=1`, 특정 줄 제외는 줄 끝 `<!-- docs-lint:ignore -->`.

`.githooks/pre-commit` 이 스테이징된 Java 파일이 있을 때 `./gradlew spotlessApply` 를 돌리고, 포맷이 바뀐 **스테이징 파일만** 다시 `git add` 한다.

- 규칙(`build.gradle.kts` `subprojects { spotless { java { … } } }`): 임포트 정렬, 미사용 임포트 제거, 후행 공백 제거, 파일 끝 개행, 탭→4칸. 전체 재포맷(palantir/google-java-format)은 코드베이스 합의 후 별도 도입.
- `ratchetFrom("origin/main")` — **origin/main 대비 변경된 파일만** 검사·수정한다. 기존 코드가 한꺼번에 재포맷되지 않는다.
- 건너뛰기: `SKIP_SPOTLESS=1 git commit …` 또는 `git commit --no-verify`.
- 수동 실행: `./gradlew spotlessApply` (적용) / `./gradlew spotlessCheck` (검사만).

## 3. push 할 때 — 변경 모듈 테스트 (pre-push)

`.githooks/pre-push` 가 push 범위(`원격 sha..로컬 sha`, 신규 브랜치면 `origin/main..로컬`)의 변경 파일을 Gradle 프로젝트로 매핑해 **그 모듈과 의존 모듈의 테스트만** 실행한다.

| 변경 위치 | 실행 |
|---|---|
| `idem-<module>/**` | `:idem-<module>:test` |
| `plugins/idem-plugin-mock-auth/**` | `:idem-plugin-mock-auth:test` + `:idem-hub:test` (runtimeOnly 의존) |
| `plugins/idem-plugin-nice-oacx/**` | `:idem-plugin-nice-oacx:test` |
| `idem-common/**`, 루트 `build.gradle.kts`·`settings.gradle.kts`·`gradle/**` | **전체** 모듈 테스트 |
| `idem-hub/**`·`idem-gate/**`·`editions/idem-kr-hub/**`(또는 전체) 이고 Docker 가 있으면 | 그 모듈의 `integrationTest` 추가 (Testcontainers) — `:idem-hub:`·`:idem-gate:`·`:idem-kr-hub:integrationTest`. 1.2 PR-1.2-3b 전에는 hub 만 붙었다(gate·KR hub 의 `integrationTest` 는 훅도 CI 도 돌리지 않았다) |
| `editions/idem-kr-portal/frontend/**` | `yarn typecheck` (yarn 있을 때) |
| `idem-console-admin/**` | `npm run typecheck && npm test` (node_modules 있을 때) |
| `docs/`, `k6/`, `infra/`, `.github/` | 테스트 없음 — 바로 push |

- 실패하면 push 가 중단된다. 리포트: `<module>/build/reports/tests/test/index.html`.
- 건너뛰기: `SKIP_TESTS=1 git push …` / `git push --no-verify`. 통합 테스트만 제외: `SKIP_IT=1`.
- 계획만 확인: `PREPUSH_DRY_RUN=1 .githooks/pre-push`. 범위 지정 실행: `PREPUSH_RANGE=origin/main~3..HEAD .githooks/pre-push`.
- Testcontainers 테스트(`@Testcontainers(disabledWithoutDocker = true)`, `idem-registry` `QimLifecycleIntegrationTest`·`OutboxIntegrationTest` — D1 부터 PostgreSQL 컨테이너)는 Docker 가 있으면 일반 `test` 태스크 안에서 자동으로 돈다. CI 에서는 항상 skip 이므로 **로컬 push 전이 유일한 실행 지점**이다 (2026-09-07 Hibernate 6.6 `@MapsId` 결함이 넉 달간 묻혔던 이유).
- **이미지를 못 받을 때**: Docker Hub 가 익명 한도(HTTP 429)에 걸리면 미러에서 받아 원래 이름으로 태그해 둔다 — Testcontainers 는 로컬에 있는 이미지를 그대로 쓴다. 필요한 것은 `postgres:15-alpine`·`postgres:16-alpine`·`redis:7-alpine`(과 Testcontainers 가 스스로 받는 `testcontainers/ryuk`). 예: `docker pull mirror.gcr.io/library/postgres:16-alpine && docker tag mirror.gcr.io/library/postgres:16-alpine postgres:16-alpine`. 2026-11-13 샌드박스에서 이렇게 받아 전량(`./gradlew test integrationTest --continue`, 1,954건·16분)을 돌렸다.
- `idem-hub` `integrationTest`(PostgreSQL·Redis·WireMock) 도 마찬가지로 CI 에서 돌지 않는다. 2026-09-08 훅으로 처음 실행했을 때 35건 중 32건이 실패했고, 원인은 4a 개명이 아니라 누적된 미검출 결함이었다:
  - 테스트용 `application.yml` 이 main 의 것을 가려 `idem.hub.kms.enabled`·`idem.hub.auth.*`·`idem.hub.registry.aes-shared-key` 가 비어 컨텍스트 기동 실패 (Sprint α-1 이후).
  - `AgencyRateLimiter` 의 Lua ARGV 가 JSON 직렬화(`"\"5\""`)로 전달되어 스크립트가 실패하고 fail-open → **운영에서도 기관별 TPS·일일 한도가 전혀 걸리지 않던 결함**. 인자 직렬화기를 `StringRedisSerializer` 로 고정해 수정.
  - `AgencyMetaJpaEntity` 의 jsonb 컬럼 3개가 `varchar` 로 바인딩되어 PostgreSQL 에서 `agency_meta` JPA 저장이 42804 로 실패 → `@JdbcTypeCode(SqlTypes.JSON)`.
  - `AesSharedKeyDecryptor` 의 `@Value` 기본값(CHANGEME 플레이스홀더)이 Base64 가 아니어서 생성자에서 예외 → 빈 키로 대체하고 경고만 남기도록 완화.
  - `HandoffTicket`(idem-common) 이 `@Builder` 만 있어 Jackson 이 역직렬화하지 못함 → Redis 에 저장한 티켓을 `findById`/`consume` 이 항상 못 찾는 결함(**Handoff 검증·멱등 재조회가 운영에서 동작하지 않던 상태**). `@Jacksonized` 추가 + 왕복 단위 테스트.
  - (D1-b 이후) `idem.messaging.kafka.enabled` 기본 false 라 브로커 없이 기동한다 — `KafkaTemplate` 은 `DisabledKafkaTemplate`, 아웃박스는 프로세스 내 배달. 아래는 `IDEM_KAFKA_ENABLED=true` 로 켰을 때의 주의점이다.
  - 트랜잭셔널 Kafka 프로듀서(`idoProducerFactory`, 고정 `transaction-id-prefix`)는 브로커가 없으면 `@Transactional` 서비스 안의 `send()` 가 `initTransactions()` 에서 `max.block.ms`(기본 60초)만큼 요청 스레드를 붙잡는다. 통합 테스트는 `KafkaTemplate` 을 `@MockitoBean` 으로 대체. 운영에서는 Kafka 장애 시 Handoff 발급 API 지연으로 나타나므로 별도 개선 과제.
  - `handoff_audit`·`audit_log` 의 `correlation_id`/`qim_user_id`/`auth_result_id` 가 VARCHAR(36) 이라 36자를 넘는 값은 감사 이력 저장이 조용히 실패(경고 로그만). 테스트 데이터는 UUID 로 맞췄고, 클라이언트가 긴 `X-Correlation-Id` 를 보내는 경우는 별도 과제.
  - Handoff·NICE 통합 테스트 기대값이 P1(FE 세션 쿠키 필수)·Bean Validation(400) 도입 이전 상태 → FE 세션 쿠키 발급, `X-Agency-Key`(SHA-256 해시 저장), Q-IM 사용자 상태 WireMock 스텁을 갖춰 실제 200 경로를 검증하도록 갱신.

## 4. 일상 흐름

```bash
git checkout shipster && git pull
# … 코드 수정 …
./gradlew :idem-hub:test --no-daemon        # 필요하면 수동으로 먼저
git add -A && git commit -m "…"             # → pre-commit: spotless
git fetch origin main && git rebase origin/main
git push origin shipster                    # → pre-push: 변경 모듈 테스트
# PR shipster → main. CI 게이트(빌드·k6)는 러너에서, Docker Build 는 main 머지 후
```

## 5. 개발용 기관 시드 (D3)

운영 마이그레이션에는 기관 시드가 없다(V24 가 종전 `AGENCY_STUB_001`·패턴 시나리오 5개를 지웠다). 로컬 hub 를 띄운 뒤
`scripts/dev/seed-dev-agencies.sh`(기본 기관 1개, `--all` 이면 패턴 시나리오까지)가 관리 API 로 같은 기관을 만들고 API 키를 한 번 출력한다.
tenant-sample 은 `AGENCY_STUB_IDO_API_KEY=<출력값>` 으로 띄운다.

## 6. 사무실 망에서 알아 둘 것

- WSL 호스트에는 회사 프록시 CA 와 IPv4 우선(`/etc/gai.conf`)이 설정돼 있어 Gradle·curl·러너는 동작한다.
- SDKMAN 으로 받은 JDK 는 시스템 CA 를 쓰지 않으므로 Maven Central 에서 `PKIX path building failed` 가 나면
  `keytool -importcert -cacerts -storepass changeit -noprompt -alias corp-proxy-root -file /usr/local/share/ca-certificates/corp-proxy-root.crt`.
- **Docker 이미지 빌드는 사무실 망에서 실패한다**(컨테이너 안에는 회사 CA 가 없어 `apk add`·Maven 이 TLS 거부). 이미지는 집 망에서 main 런 재실행(`gh run rerun <id> --failed`)으로 만든다.
- 러너 관련 세부: `docs/ci-runner-guide.md`.

## 7. SLO(단일 로그아웃) 끝-끝 프로브 — 시험 항목 D-8 (G2-16)

`scripts/dev/slo-probe.py` 가 기관 RP 를 브라우저 없이 흉내 내 RP-Initiated Logout 을 끝까지 밟고 13 단계를 판정한다 — RP1 로그인(gate 프런트 → Keycloak 화면 → 토큰 교환) → RP2 는 같은 쿠키로 SSO(같은 sid) → RP1 이 `end_session_endpoint` 로 로그아웃 → RP2 의 `backchannelLogoutUri` 에 `logout_token` 수신(events·aud·sid 검사) → Keycloak 세션 0 → 옛 토큰 401. 표준 라이브러리만 쓰고 비밀·토큰은 출력하지 않는다.

```bash
# 스택: gate(8081)·hub(8083)·registry·authz + 실제 Keycloak. 기관 RP 프로파일 둘(OIDC_RP) — backchannelLogoutUri 는 이 스크립트의 수신기(BC_LISTEN) 주소
#   (프로파일 검증은 루프백·사설 주소를 거부하므로 공개 이름을 쓴다 — 샌드박스는 /etc/hosts 로 127.0.0.1 에 맵핑). client secret 은 관리 API 의 secret 회전 응답에서.
OIDC_CLIENT_ID=idem-svc-SLO_RP   OIDC_CLIENT_SECRET=…  OIDC_CLIENT_ID_2=idem-svc-SLO_RP2  OIDC_CLIENT_SECRET_2=… \
OIDC_REDIRECT_URI=https://rp.example.org/callback \
BC_LISTEN=0.0.0.0:9099 BC_PATH=/bc BC_PATH_2=/bc2 KC_ADMIN_URL=http://localhost:8088 KC_ADMIN_USER=admin KC_ADMIN_PASSWORD=… \
python3 -I scripts/dev/slo-probe.py
```

- **시험 사용자(1.2)**: 설치 realm 에는 기본 사용자가 없다(1.1.x 의 `poc-testuser` 는 비밀번호가 저장소에 있는 기본 계정이어서 뺐다). 프로브는 `KC_ADMIN_*` 로 임시 사용자 `idem-probe-<난수>` 를 만들어(무작위 비밀번호) 쓰고 끝나면 지운다. 이미 있는 사용자로 돌리려면 `OIDC_USER` + `OIDC_PASSWORD` 를 준다 — 그때는 만들거나 지우지 않는다. 손으로 만들고 지우려면 `scripts/dev/kc-test-user.py create|delete|status`(비밀번호는 `OIDC_PASSWORD_FILE` 의 0600 파일로).

- 판정은 RP2 로 한다(SLO 의 의미 — 다른 RP 의 세션이 끝나는가). Keycloak(24.0.5·26.8.2 에서 확인)은 로그아웃을 시작한 RP1 에도 보내는데 참고로만 찍는다. `OIDC_USER`·`OIDC_PASSWORD` 로 돌릴 때 `KC_ADMIN_*` 이 없으면 Keycloak 세션 확인은 건너뛴다.
- **프록시가 있는 환경**(샌드박스·사무실 망): Keycloak 의 아웃바운드(Back-Channel Logout)가 `HTTPS_PROXY` 로 가서 수신기에 닿지 않는다 — Keycloak 을 `NO_PROXY` 에 RP 호스트를 넣어(또는 프록시 없이) 띄운다. 2026-10-16 샌드박스에서 이 때문에 두 번 실패한 뒤 13/13.
- gate 로그 `[OIDC-FRONT] RP-Initiated Logout: … cacheEvicted=N feExpired=M`, hub 로그 `[IdpLogout] FE 세션 만료` 가 Idem 쪽 정리의 증거다(브로커 로그인 FE 세션이 없으면 `feExpired=0`).

## 8. Keycloak 중계 로그인 끝-끝 프로브 — 시험 항목 D-19 (1.2 PR-1.2-2)

`scripts/dev/broker-login-probe.py` 가 코어 로그인 프런트에 `provider=broker:<이름>` 으로 들어가 브라우저처럼 홉을 밟고 7 단계를 판정한다 — 진입 302 → 브로커 시작 302 → Keycloak 로그인 화면 200 → 폼 POST 302 → gate 콜백 → hub 바인드 → continue → 기관 콜백(`?ticketId=&state=`) → 서버 간 verify(`APPROVED`/`GUEST`) → 재검증 409 `E-IDO-102`. IT(`BrokerLoginIntegrationTest`)는 gate 를 WireMock 으로 바꾸므로, 실제 Keycloak·gate·hub 를 지나는 확인은 이 프로브다. HTTP 도우미는 §7 의 `slo-probe.py` 를 그대로 쓴다.

```bash
# 스택: §7 과 같다. hub 만 브로커 제공자를 켜서 띄운다(기본은 비어 있다):  IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS=keycloak
# 준비(설치본 스모크 ⑦b 와 같다): DIRECT 프로파일의 서비스 + 기관 API 키
#   PUT  $HUB_URL/api/v1/admin/services/BRK_HO/profile   {"schemaVersion":1,"service":{"code":"BRK_HO","name":"…","status":"ACTIVE"},
#        "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["https://agency.example.org/cb"]}},"identity":{"attributes":["name_masked"]},"policy":{"minAuthLevel":"L1"}}
#   POST $HUB_URL/api/v1/admin/agencies/BRK_HO/rotate-key  → newApiKey
SERVICE=BRK_HO AGENCY_KEY=… KC_ADMIN_URL=http://localhost:8088 KC_ADMIN_PASSWORD=… python3 -I scripts/dev/broker-login-probe.py   # 시험 사용자는 §7 과 같다
```

- 2026-11-12 샌드박스(Keycloak 26.8.2, 24.0.5 에서 올린 realm): 7/7, 2회. gate `auth_result`(`KEYCLOAK_OIDC`·SUCCESS)·hub `handoff_audit` `CONSUMED` 로 뒤를 확인했다. 2026-11-13(임시 사용자, 정리한 realm · 새 설치): 7/7.
- hub 관리자 로그인을 하는 도구(스모크·성능 실행기·프로파일 준비)를 잇달아 돌릴 때는 30초 넘게 띄운다 — 같은 TOTP 주기의 코드를 두 번 쓰면 hub 가 `E-IDO-134` 로 거부한다.
- **로그인 화면의 주소를 본다**: 프로브는 홉마다 주소를 찍고, Keycloak 로그인 화면이 gate 공개 URL 이 아니면 `[INFO]` 를 남긴다. 지금 gate 는 인가 URL 을 Keycloak 내부 주소(`KEYCLOAK_URL`)로 만든다 — 샌드박스·CI 처럼 그 주소가 브라우저에서 닿는 배치에서만 끝까지 간다(`docs/analysis/keycloak-26-assessment.md` §8.6 #7, 플랜 §5.5 의 3c).

