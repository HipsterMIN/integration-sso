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

## 2. 커밋할 때 — Spotless (pre-commit)

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
| `idem-hub/**` 이고 Docker 가 있으면 | `:idem-hub:integrationTest` 추가 (Testcontainers) |
| `editions/idem-kr-portal/frontend/**` | `yarn typecheck` (yarn 있을 때) |
| `idem-console-admin/**` | `npm run typecheck && npm test` (node_modules 있을 때) |
| `docs/`, `k6/`, `infra/`, `.github/`, `idem-agent-testbed/` | 테스트 없음 — 바로 push |

- 실패하면 push 가 중단된다. 리포트: `<module>/build/reports/tests/test/index.html`.
- 건너뛰기: `SKIP_TESTS=1 git push …` / `git push --no-verify`. 통합 테스트만 제외: `SKIP_IT=1`.
- 계획만 확인: `PREPUSH_DRY_RUN=1 .githooks/pre-push`. 범위 지정 실행: `PREPUSH_RANGE=origin/main~3..HEAD .githooks/pre-push`.
- Testcontainers 테스트(`@Testcontainers(disabledWithoutDocker = true)`, `idem-registry` `QimLifecycleIntegrationTest`·`OutboxIntegrationTest` — D1 부터 PostgreSQL 컨테이너)는 Docker 가 있으면 일반 `test` 태스크 안에서 자동으로 돈다. CI 에서는 항상 skip 이므로 **로컬 push 전이 유일한 실행 지점**이다 (2026-09-07 Hibernate 6.6 `@MapsId` 결함이 넉 달간 묻혔던 이유).
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
