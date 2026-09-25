# Idem 개명 대응표 (Naming)

> **확정일** 2026-09-04 · **제품명** OnePass → **Idem**(아이뎀) · **상태** 2·3단계 완료, 4·5단계 예정

## 1. 이름의 뜻과 원칙

- **Idem**: 라틴어 "같은 것". 이 플랫폼의 핵심인 **동일인 식별**(여러 기관의 계정을 한 사람으로 묶음)을 한 단어로 표현한다. ID + EM으로도 읽힌다.
- 포지셔닝: "범용 SSO"가 아니라 **어떤 IdP 위에든 얹는 회원통합·연합인가 계층(Federation Layer)**.
- 원칙: 제품명 하나 + **역할 기반 모듈명**. 모듈명에 고객(smes), 국가(kr), 암호 기술(q-)을 담지 않는다.
- 코어/에디션 분리: 한국 공공 종속 요소(CI/DI, NICE/OACX, AnyID)는 **Idem KR Public Edition** 으로 묶는다. OnePass·중기원패스는 첫 적용 사례(고객 서비스명)로만 남긴다.

## 2. 대응표

### 2.1 모듈 디렉터리 · Gradle 프로젝트 (3단계, 완료)

| 구명 | 신명 | 역할 |
|---|---|---|
| `platform-common` | `idem-common` | 공통 라이브러리 |
| `q-sign` | `idem-gate` | 인증 관문 — 로그인 프론트, OIDC 파사드(Keycloak 프록시), PKCE |
| `q-im` | `idem-registry` | 회원 원장 — 골든 레코드, 가명 ID(DI), 탈퇴 |
| `ido` | `idem-hub` | 오케스트레이션 — 세션 핸드오프, 프로비저닝, 웹훅, KMS, 본인인증 브로커 |
| `q-authz` | `idem-authz` | 연합 인가 — 역할 원장, SCIM, 만료·회수 전파 |
| `outbox-relay-batch` | `idem-relay` | Transactional Outbox 릴레이 배치 |
| `onepass-fe` | `idem-console` | 관리·사용자 웹 (React SPA) |
| `onepass-agency-sdk` | `idem-sdk-java` | 테넌트(기관)측 Java SDK |
| `onepass-agent` | `idem-agent` | 레거시 WAS용 Java Agent |
| `agency-stub` | `idem-tenant-sample` | 참조 테넌트 앱 |
| `onepass-agent-testbed` | `idem-agent-testbed` | 에이전트 테스트베드 (settings 미포함) |
| 루트 프로젝트 `onepass-platform` | `idem` | |

Gradle 태스크 경로는 그대로 따라간다 (`:ido:bootJar` → `:idem-hub:bootJar`). 산출물 파일명도 프로젝트명을 따르므로 `ido-0.1.0-SNAPSHOT.jar` → `idem-hub-0.1.0-SNAPSHOT.jar`, `onepass-agent-…-all.jar` → `idem-agent-…-all.jar`.

### 2.2 빌드·배포 산출물 (3단계, 완료)

| 항목 | 구명 | 신명 |
|---|---|---|
| Docker 이미지 (GHCR, compose) | `onepass-ido`, `onepass-qsign`, `onepass-qim`, `onepass-q-authz`, `onepass-batch`, `onepass-agency-stub`, `onepass-react` | `idem-hub`, `idem-gate`, `idem-registry`, `idem-authz`, `idem-relay`, `idem-tenant-sample`, `idem-console` |
| Helm 차트 | `infra/helm/onepass`, `infra/helm/ido` | `infra/helm/idem`, `infra/helm/idem-hub` |
| compose 네트워크·컨테이너 접두 | `onepass-*` | `idem-*` |
| Grafana 대시보드 파일 | `onepass-overview.json` | `idem-overview.json` |
| CI 워크플로 매트릭스·아티팩트 경로 | 구 디렉터리 기준 | 신 디렉터리 기준 |

### 2.3 용어

| 구 | 신 | 비고 |
|---|---|---|
| 기관 (agency) | 테넌트 (tenant) | 4단계에서 코드·API·DB로 확장. 기관 고유 속성은 KR 에디션 확장으로 분리 |
| OnePass / 중기원패스 | (제품명 아님) | 첫 적용 사례의 서비스명. 문서에서 사례로만 언급 |

## 3. 런타임 식별자 (4b 완료 · 5단계 남음)

아래는 **런타임 식별자**라 코드·설정·데이터를 함께 옮겨야 해 3단계에서 제외했던 것이다. **4b 는 S9 PR-1(2026-09-25) 에서 끝났다** — 표의 ✅ 행. 구 이름은 `idem-common` 의 `LegacyNames`/`LegacyNamesEnvironmentPostProcessor` 가 **1 릴리스 동안** 새 이름으로 비춰 준다(구 환경변수·구 설정 키가 있으면 기동 시 WARN 으로 나열). 코드·설정·설치본에 구 이름이 다시 들어오면 `NamingGuardTest` 가 막는다.

### 3.1 대응표 (4b)

| 구분 | 구 이름 | 새 이름 |
|---|---|---|
| 설정 키 접두 | `ido.*` · `qsign.*` · `qim.*` · `authz.*` · `batch.*` · `agency-stub.*` | `idem.hub.*` · `idem.gate.*` · `idem.registry.*` · `idem.authz.*` · `idem.relay.*` · `idem.sample.*` |
| 모듈 안의 구 모듈 지칭 | `ido.qim.*` · `ido.q-authz.*` · `ido.qsign.*` · `ido.qim-outbox.*` · `ido.qim-events.*` · `ido.kafka.topic-qim-*` · `ido.internal.callers.q-sign`/`outbox-relay` · `qsign.ido.*` · `batch.datasource.{qsign,qim,ido}` · `batch.relay.*` | `idem.hub.registry.*` · `idem.hub.authz.*` · `idem.hub.gate.*` · `idem.hub.registry-outbox.*` · `idem.hub.registry-events.*` · `idem.hub.kafka.topic-registry-*` · `idem.hub.internal.callers.idem-gate`/`idem-relay` · `idem.gate.hub.*` · `idem.relay.datasource.{gate,registry,hub}` · `idem.relay.jobs.*` |
| 환경변수 접두 | `IDO_*` · `QSIGN_*` · `QIM_*` · `AUTHZ_*` · `BATCH_*` | `IDEM_HUB_*` · `IDEM_GATE_*` · `IDEM_REGISTRY_*` · `IDEM_AUTHZ_*` · `IDEM_RELAY_*` |
| 환경변수 별칭 | `IDO_QIM_*` · `IDO_QAUTHZ_*` · `IDO_INTERNAL_API_KEY_QSIGN`/`_OUTBOX` · `QAUTHZ_BASE_URL` · `BATCH_{IDO,QIM,QSIGN}_*` · `IDEM_ADMIN_*`(S7 임시) | `IDEM_HUB_REGISTRY_*` · `IDEM_HUB_AUTHZ_*` · `IDEM_HUB_INTERNAL_API_KEY_GATE`/`_RELAY` · `IDEM_HUB_AUTHZ_BASE_URL` · `IDEM_RELAY_{HUB,REGISTRY,GATE}_*` · `IDEM_HUB_ADMIN_*` |
| 그대로 두는 환경변수 | `DB_*` · `REDIS_*` · `KAFKA_*` · `KEYCLOAK_*`(Keycloak 자체 이름) · `NICE_*`/`OACX_*`/`VAULT_*`/`NHN_*`(벤더) · `IDEM_PUBLIC_URL_*`/`IDEM_PORT_*`/`IDEM_EDITION`/`IDEM_PLUGINS_*`/`IDEM_KAFKA_ENABLED`(설치본 공통) | — |
| `spring.application.name` | `q-sign` · `ido` · `q-im` · `q-authz` · `outbox-relay-batch` · `agency-stub` | `idem-gate` · `idem-hub` · `idem-registry` · `idem-authz` · `idem-relay` · `idem-tenant-sample` (Micrometer `application` 태그, Prometheus `job`) |
| 호출자·소스 시스템 | `X-Internal-Caller: q-sign`/`ido` · `X-Source-System: ido` · `X-Outbound-Source: onepass-ido` · 감사 `source_system` `ido`/`q-sign`/`q-im`/`q-authz` | `idem-gate`/`idem-hub` · `idem-hub` · `idem-hub` · `idem-hub`/`idem-gate`/`idem-registry`/`idem-authz` |
| Kafka | client `q-sign-producer`·`ido-producer`·`q-im-producer`, group `q-sign-consumer`·`ido-qim-consumer`·`q-im-consumer`·`agency-stub-consumer`, 토픽 `qsign.auth.events`·`qim.user.events`·`qim.agency.events`·`qim.sp.member.events`·`qim.user.snapshot`·`ido.handoff.events`·`authz.assignment.events` | `idem-gate-producer`·`idem-hub-producer`·`idem-registry-producer`, `idem-gate-consumer`·`idem-hub-registry-consumer`·`idem-registry-consumer`·`idem-tenant-sample-consumer`, `idem.gate.auth.events`·`idem.registry.user.events`·`idem.registry.agency.events`·`idem.registry.sp.member.events`·`idem.registry.user.snapshot`·`idem.hub.handoff.events`·`idem.authz.assignment.events` (컨슈머 그룹이 바뀌므로 오프셋은 새로 시작) |
| Redis 키 접두 | `ido:*` · `qsign:*` · `fe:*` | `idem:*` · `idem:gate:*` · `idem:fe:*` (업그레이드 시 Redis 를 비운다 — 세션은 재로그인) |
| Prometheus | `job="ido"`·`q-sign`·`q-im`, 메트릭 `onepass_outbox_*` | `job="idem-hub"`·`idem-gate`·`idem-registry`, `idem_outbox_*` |
| Helm | values `qsign:`·`qim:`·`ido:`·`batch:`·`agencyStub:`, 리소스 `ido-service`·`ido-config`… | `gate:`·`registry:`·`hub:`·`relay:`·`sample:`, `idem-hub-*` (helm lint 는 이 환경에 없어 텍스트 치환만 — 배포 전 `helm template` 확인) |
| Vault transit 키 기본값 | `ido-handoff-key` | `idem-handoff-key` |

### 3.2 목록 (원본, 상태 표시)

| 구분 | 현재 값 | 비고 |
|---|---|---|
| Java 패키지 | ~~`kr.go.smes.{qsign,qim,ido,authz,agency,batch,sdk,agent,common}`~~ → **`io.github.hipstermin.idem.{gate,registry,hub,authz,tenant,relay,sdk,agent,common,plugin}` (4a 완료 2026-09-08)** | 578개 파일 + 참조 116개 파일. 사전 점검 결과: Kafka 는 타입 헤더 없음(안전), Redis 캐시 값은 `@class` FQCN 포함 → 배포 시 flush 필요, 자동설정 imports·`Class.forName`·`trusted.packages`·로깅 키 함께 갱신 |
| ✅ `spring.application.name` | ~~`q-sign`, `q-im`, `ido`, `q-authz`, `agency-stub`~~ → `idem-*` | 4b 완료 |
| ✅ 서비스 간 호출자 ID | ~~`X-Source-System: q-sign`, `X-Outbound-Source: onepass-ido`, `ido.internal.api-keys.q-sign`~~ → `idem-gate`/`idem-hub`, `idem.hub.internal.callers.idem-gate` | 4b 완료 |
| ✅ Redis 키 접두 | ~~`ido:ticket:*`, `ido:rl:*`, `ido:idempotency:*`~~ → `idem:*` | 4b 완료. 업그레이드 시 flush |
| ✅ 환경변수 접두 | ~~`IDO_*`, `QIM_*`~~ → `IDEM_HUB_*`, `IDEM_REGISTRY_*` … (`KEYCLOAK_*` 는 Keycloak 자체 이름이라 유지, `ONEPASS_*` 는 agent/SDK 외부 계약이라 별도) | 4b 완료 |
| Keycloak | realm `onepass`, client `q-sign-client`, `ido-client` | **5단계(S9 PR-2)** — realm export 포함 |
| DB | PostgreSQL `onepass`, 스키마 `ido`·`qsign`·`qim`·`authz`, Flyway 이력 | **5단계(S9 PR-2)**. 운영 데이터 없는 지금이 적기 |
| ✅ k8s 런타임 이름 | ~~Service `ido-service`, ConfigMap `ido-config`~~ → `idem-hub-*` (Helm 텍스트 치환, lint 미검증) | 4b 완료 |
| ✅ Prometheus job / 알림 라벨 | ~~`job="ido"`, `job="q-sign"`~~ → `idem-hub`, `idem-gate` | 4b 완료 |
| FE 소스 | `editions/idem-kr-portal/frontend/**` (패키지명 `onepass`, 에셋 `assets/onepass`, 외부 호스트) | KR 에디션 포털 — 에디션 과제 |
| Java 소스 내 주석·문자열 | `onepass-fe`, `q-sign` 등 | 4단계 패키지 이동 시 일괄 |
| ✅ Helm values 키 | ~~`qsign:`, `qim:`, `ido:`, `batch:`, `agencyStub:`~~ → `gate:`, `registry:`, `hub:`, `relay:`, `sample:` | 4b 완료 (텍스트 치환, 배포 전 `helm template` 확인) |
| 기존 문서 본문 | `docs/`, `wiki/` 의 Q-Sign·IdO·OnePass 표기 | 작성 시점 기록으로 유지. 경로 참조만 갱신 |

### 2.4 문서 파일명·본문 정리 (2026-09-07)

`docs/` 에서 3단계까지 끝난 이름을 아직 구명으로 적고 있던 곳을 정리했다.

- 파일명 11개: `onepass-agent-*.md` → `idem-agent-*.md`, `onepass-agency-sdk-usage-guide.md` → `idem-sdk-java-usage-guide.md`, `onepass-support-*-plan.md` → `idem-support-*-plan.md`, `internal/architecture/onepass-agent-architecture.md`, `internal/development/onepass-agent-developer-reference.md`·`onepass-be-integration-plan.md`, `internal/spec/03f-module-onepass-fe.md` → `03f-module-idem-console.md`. 저장소 전체의 링크를 함께 갱신했다.
- 본문: compose 컨테이너·이미지(`onepass-ido` 등) → `idem-*`, `onepass-fe` → `idem-console`, `onepass-agency-sdk` → `idem-sdk-java`, `onepass-agent-…-all.jar` → `idem-agent-…-all.jar`, `infra/helm/onepass` → `infra/helm/idem`, 계획 문서의 `onepass-support` → `idem-support`.
- **그대로 둔 것 (4·5단계 대상)**: `onepass.agent.*` 설정 키, `onepass-agent.properties`, `ONEPASS_*`, `OnePass-Signature` 헤더, `OnePassAgent*` 클래스, Maven 좌표 `kr.go.smes:onepass-agency-sdk`, Keycloak `onepass-realm.json`, k8s `onepass-dev/-secrets/-tls`, 고객 도메인 `onepass*.smes.go.kr`, `_archive/`. 해당 문서 상단에 "명칭 안내 (2026-09-07)" 블록을 넣어 구명 유지 사유를 밝혔다.

## 4. 단계

1. ~~이름 확정·가용성 확인~~ (Idem 확정. 상표(KIPRIS)·도메인·GitHub org·Maven 그룹 확인은 소유 주체가 수행)
2. ~~저장소·루트 프로젝트·문서 표제~~
3. ~~모듈 디렉터리·Gradle·Dockerfile·CI·Helm·이미지명~~
4. Java 패키지 이동(**4a 완료 2026-09-08** — `io.github.hipstermin.idem.*`, 동작 변화 없음) + 런타임 식별자(설정 키·헤더 값·Redis 접두·환경변수·k8s 이름·Prometheus 라벨) (**4b 완료 2026-09-25, S9 PR-1** — §3.1 대응표, `LegacyNames` 호환 계층 1 릴리스). agency→tenant **용어** 개명(API 경로·프로파일 키)은 1.0 API 동결과 함께 별도 판단
5. DB명·스키마명·Keycloak realm
