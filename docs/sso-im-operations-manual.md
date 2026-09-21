# OnePass SSO / IM 운영 메뉴얼

> **문서 위치** : `docs/sso-im-operations-manual.md`
> **작성 기준** : 코드베이스(소스, application.yml, Flyway, docker-compose) 그대로
> **대상 독자** : OnePass 통합인증 플랫폼 운영자 / On-Call SRE
> **마지막 갱신** : 코드베이스 스캔본(브랜치 `shipster`)

본 메뉴얼은 별도 설계 문서나 위키를 참고하지 않고, 저장소의 다음 소스에서 직접
사실관계를 추출하여 작성되었습니다.

- `idem-registry/src/main/**` — Q-IM (식별·매핑 SoR)
- `idem-hub/src/main/**` — IdO (정책 오케스트레이터 + FE BFF)
- `idem-gate/src/main/**` — Q-Sign (인증 결과 SoR)
- `idem-relay/src/main/**` — Outbox 릴레이 배치
- `infra/docker/**` — 로컬·개발 인프라 정의
- `infra/monitoring/**`, `infra/k8s/**`, `infra/helm/**` — 모니터링·배포 자산

---

## 1. 시스템 개요

OnePass는 다음 5개 서비스로 구성된다(`infra/docker/docker-compose.yml`).

| 서비스 | 모듈 | 포트 | DB | 비고 |
|---|---|---|---|---|
| Q-Sign | `idem-gate/` | 8081 | PostgreSQL `onepass` (schema: `qsign`) | 인증 결과 SoR, Keycloak OIDC 클라이언트 |
| Q-IM | `idem-registry/` | 8082 | PostgreSQL 16 스키마 `qim` (D1: MariaDB 제거) | 식별·매핑 SoR (회원·동의·후견·CI) |
| IdO | `idem-hub/` | 8083 | PostgreSQL `onepass` (schema: `ido`) | 정책 오케스트레이터 + FE BFF + Webhook Dispatcher |
| Agency-Stub | `idem-tenant-sample/` | 8084 | PostgreSQL `onepass` | 유관기관 OIDC 클라이언트 시뮬레이터 |
| React SPA | `idem-console/` | 3001 (Nginx) | — | `Dockerfile.optionB` 사용 |

공통 인프라(`docker-compose.yml`):

| 컴포넌트 | 호스트:포트 | 내부 IP |
|---|---|---|
| PostgreSQL 16 | 5432 | 172.20.0.10 |
| Redis 7.2 | 6379 | 172.20.0.11 |
| Zookeeper | 2181 | 172.20.0.12 |
| Kafka (Confluent 7.6.1) | 9092 (외부) / 29092 (내부) | 172.20.0.13 |
| Keycloak 24.0 | 8088 | 172.20.0.18 |
| HashiCorp Vault 1.17 | 8200 | 172.20.0.20 |
| Kafka-UI | 8090 (`admin/admin`) | 172.20.0.15 |
| Redis Insight | 5540 | 172.20.0.16 |
| pgAdmin (profile: tools) | 5050 (`admin@onepass.local/admin`) | 172.20.0.17 |
| Adminer (profile: tools) | 8091 | 172.20.0.23 |
| Prometheus (profile: monitoring) | 9090 | 172.20.0.50 |
| Grafana (profile: monitoring) | 3002 (`admin/admin`) | 172.20.0.51 |
| Loki / Promtail | 3100 | 172.20.0.52 / .53 |

서브넷은 `172.20.0.0/24` (Docker bridge: `idem-net`).

---

## 2. 기동·정지

### 2.1 docker-compose 프로파일

`infra/docker/docker-compose.yml`에 정의된 프로파일:

- 기본(없음) : 인프라만 (DB / Redis / Kafka / Vault / Kafka-UI / Redis Insight)
- `app` : + Q-Sign / Q-IM / IdO / Agency-Stub
- `keycloak` : + Keycloak OIDC 브로커
- `optionB` : + React SPA (Nginx, port 3001)
- `tools` : + pgAdmin / Adminer
- `monitoring` : + Prometheus / Grafana / Loki / Promtail
- `schema` : + Schema Registry (8085)

### 2.2 기동 순서 (의존성 자동 처리)

```bash
# 인프라만
docker compose -f infra/docker/docker-compose.yml up -d

# 앱 포함 (가장 일반적인 개발 환경)
docker compose -f infra/docker/docker-compose.yml --profile app up -d

# 전체 (KC + 모니터링 + FE)
docker compose -f infra/docker/docker-compose.yml \
  --profile app --profile keycloak --profile monitoring --profile optionB up -d
```

`depends_on` + `condition: service_healthy` 가 모든 앱 서비스에 설정되어 있어
Postgres / Redis / Kafka / kafka-init 가 healthy 상태가 될 때까지
대기 후 기동된다. `kafka-init` 컨테이너는 `kafka/create-topics.sh` 를
한 번만 실행한 후 `service_completed_successfully` 상태로 종료된다.

### 2.3 graceful shutdown

세 앱 모두 동일하게 설정(`application.yml`):

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 20s
```

→ 컨테이너 SIGTERM 후 최대 20초 동안 인플라이트 트랜잭션을 마무리한다.
K8s 환경에서는 `terminationGracePeriodSeconds ≥ 30s` 권장.

---

## 3. 환경변수 (운영 필수 주입 항목)

코드상 default 가 비어 있거나 `?:` 로 fail-fast 처리되어 있으면 운영에서
**반드시 환경변수로 주입**해야 한다. 미주입 시 부팅 단계에서 차단된다.

### 3.1 Q-IM (`idem-registry/src/main/resources/application.yml`)

| 환경변수 | 용도 | 부팅 차단 여부 |
|---|---|---|
| `QIM_DB_HOST` / `QIM_DB_PORT` / `QIM_DB_NAME` / `QIM_DB_SCHEMA` / `QIM_DB_USERNAME` / `QIM_DB_PASSWORD` | PostgreSQL 접속 (기본 `localhost:5432/onepass`, 스키마 `qim`). 종전 MariaDB 설치는 프로파일 `mariadb` 로 1 릴리스 유지 → `scripts/registry-db-migrate/` 로 이관 | 연결 실패 시 Hikari 재시도 |
| `QIM_DB_SSL` | TLS 사용 여부 (`true`/`false`) | — |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis | — |
| `KAFKA_SERVERS` | Kafka bootstrap | — |
| `QIM_CI_AES_KEY_V1` | AES-256-GCM CI 키 v1 (Base64 32바이트) | **예** — `CiCryptoServiceImpl.validateKeyV1()` |
| `QIM_CI_AES_KEY_V2` | 키 로테이션용 v2 | 선택 |
| `QIM_CI_CURRENT_VERSION` | 현재 활성 키 버전 (기본 `v1`) | — |
| `QIM_DI_SECRET` | DI HMAC-SHA256 공유 비밀키 (≥32자) | **예** — `DiGenerationService.validateDiSecret()` |
| `QIM_INTERNAL_API_KEY` | IdO → Q-IM `X-Internal-Api-Key` 검증값 | — |

생성 예시:

```bash
openssl rand -base64 32   # QIM_CI_AES_KEY_V1 / V2 (32바이트)
openssl rand -hex 32      # QIM_DI_SECRET / QIM_INTERNAL_API_KEY
```

### 3.2 IdO (`idem-hub/src/main/resources/application.yml`)

| 환경변수 | 용도 | 부팅 차단 여부 |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL | — |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis | — |
| `KAFKA_SERVERS` | Kafka bootstrap | — |
| `IDO_HANDOFF_AES_KEY` / `IDO_HANDOFF_HMAC_KEY` | Handoff Ticket 폴백 키 (Base64 32바이트) | **예** — `KeyVersionRegistry.validateFallbackKeys()` |
| `IDO_WEBHOOK_SIGNING_SECRET` | Webhook HMAC-SHA256 서명 키 | **예** — `WebhookDispatcherService.validateSigningSecret()` |
| `KEYCLOAK_CLIENT_SECRET` | Keycloak ido-client 시크릿 | **예** — `KeycloakProperties.validateClientSecret()` |
| `IDO_INTERNAL_SIG_SECRET` | Q-Sign HMAC 공유키 (≥32자) | — |
| `IDO_QIM_INTERNAL_API_KEY` | Q-IM 내부 호출 키 (Q-IM과 동일값) | — |
| `IDO_QIM_EXT_API_KEY` | Q-IM 외부 API Key (FE 대리 호출) | — |
| `QIM_INBOUND_API_KEY_HASH` | Q-IM→IdO 인바운드 PBKDF2 해시 | — |
| `QIM_AES_SHARED_KEY` | Q-IM↔IdO AES-256 공유키 | — |
| `IDO_INTERNAL_API_KEY_QSIGN` | q-sign → ido 내부 호출 키 | — |
| `IDO_INTERNAL_API_KEY_OUTBOX` | outbox-relay → ido 내부 호출 키 | — |
| `IDO_INTERNAL_ALLOW_EMPTY_CALLERS` | 위 두 키 비었을 때 허용 (운영=false) | — |
| `NICE_CLIENT_ID` / `NICE_CLIENT_SECRET` / `NICE_RETURN_URL` | NICE 본인인증 | — |
| `OACX_PROVIDER_KEY_PATH` / `OACX_DEBUG_MODE` | OACX SDK | — |
| `INTEGRATION_AUTH_BASE_URL` | 기업 통합인증 서버 | — |
| `ANYID_*` | Any-ID 설치형 연동 (KMS / SSO / PID 등) | `ANYID_SSO_SECRET_CODE`, `ANYID_KMS_APP_KEY` 등 운영 필수 |
| `VAULT_ADDR` / `VAULT_TOKEN` / `VAULT_TRANSIT_PATH` / `VAULT_TRANSIT_KEY` / `VAULT_AUTH_METHOD` | KMS(Vault) | `IDO_KMS_ENABLED=true` 시 |
| `FE_AES_GCM_KEY` | FE↔IdO CI 토큰 교환 키 | CI 토큰 엔드포인트 사용 시 |

자세한 의미는 application.yml 주석을 참조한다.

### 3.3 docker-compose 의 `:?` 가드

다음 변수는 `docker-compose.yml` 에서 `${VAR:?오류 메시지}` 로 정의되어 있어
주입하지 않으면 컨테이너 생성 자체가 거부된다.

- `QSIGN_KEYCLOAK_CLIENT_SECRET`
- `KEYCLOAK_CLIENT_SECRET`
- `IDO_WEBHOOK_SIGNING_SECRET`
- `IDO_HANDOFF_AES_KEY`
- `IDO_HANDOFF_HMAC_KEY`

→ 로컬 PoC 는 `infra/docker/.env` 에서 주입한다 (git ignore 됨).
→ 운영은 K8s Secret 또는 Vault 동적 주입.

---

### 3.4 fail-secure 원칙과 탈출구 (D2, 2026-09-21)

**원칙**: 외부 의존(Redis·DB·authz·서명키·사업자 응답)이 실패하면 **거부(503/422)하고 감사 기록**을 남긴다. "장애 시 허용" 경로는 코어에서 제거했다.
아래 값이 비어 있으면 hub 는 **기동을 거부**한다(종전에는 경고만 남기고 떠서 사고가 런타임까지 숨었다).

| 필수 값 | 용도 | 생성 |
|---|---|---|
| `IDO_INTERNAL_SIG_SECRET` | gate ↔ hub 내부 서명 (gate 도 prod/stage 에서 필수) | `openssl rand -hex 32` |
| `IDO_CAST_PRIVATE_KEY` / `IDO_CAST_PUBLIC_KEY` | SSO 토큰(CAST) Ed25519 서명키 — 임시 키 자동 생성 없음 | `docs/install.md` §2 |
| `QIM_AES_SHARED_KEY` | registry ↔ hub CI 공유키 (32바이트) | `openssl rand -base64 32` |
| `IDO_QAUTHZ_INTERNAL_API_KEY` | hub → authz (`IDO_QAUTHZ_ENABLED=true` 일 때) | authz 의 `AUTHZ_INTERNAL_API_KEY` 와 동일 |
| `IDO_QAUTHZ_ENABLED` | authz 를 배포하지 않는 SSO 단독 설치는 **`false` 로 명시** (조용한 폴백 없음) | — |

**탈출구(escape hatch)** — 로컬·테스트 편의를 위한 플래그. `prod`/`stage` 프로파일에서는 어느 하나라도 켜져 있으면 `FailSecureBootGuard` 가 기동을 거부하고 위반 목록을 로그에 남긴다.

| 플래그 | 켜면 |
|---|---|
| `IDO_INTERNAL_ALLOW_EMPTY_SIG_SECRET` | 내부 서명키 없이 기동 |
| `IDO_INTERNAL_ALLOW_EMPTY_CALLERS`, `IDO_WEBHOOK_ALLOW_EMPTY_SECRET`, `ido.keycloak.allow-empty-client-secret`, `ido.ticket.allow-empty-fallback-keys` | 종전 탈출구 (그대로) |
| `IDO_BROKER_ALLOW_CILESS_IDENTITY` | CI 없는 인증 결과를 identifierHash 로 세션 발급 (종전 PoC 폴백) |
| `IDO_CAST_ALLOW_GENERATED_KEYS` | CAST 서명키 임시 생성 |
| `IDO_QIM_ALLOW_EMPTY_AES_KEY` | registry 공유키 없이 기동 (복호화는 실패) |
| `IDO_QAUTHZ_ALLOW_EMPTY_API_KEY` | authz 키 없이 기동 |
| `ido.kms.local.allow-in-prod`, `ido.kms.vault.allow-empty-token` | KMS 우회 |
| `IDEM_PLUGINS_MOCK_AUTH_ENABLED` | 무검증 Mock 본인확인 — 플러그인 자체가 `!prod & !stage` 프로파일에서만 로드된다 |

prod/stage 에서 **반드시 true** 여야 하는 것: `IDO_AUDIT_DB_ENABLED`, `IDO_SECURITY_HEADERS_ENABLED`, `IDO_AUTH_RL_ENABLED`, `IDO_RATE_LIMIT_ENABLED`, `IDO_REDISSON_ENABLED`.

**런타임 거부 코드** (`E-IDO-116` 의존 장애 · `E-IDO-117` authz 장애 · `E-IDO-118` 주체 미확인 · `E-IDO-119` 세션 저장소 장애): 감사 로그(`ido.audit_log`) 의 `RATE_LIMIT_BACKEND_UNAVAILABLE` 등 액션과 함께 §16 플레이북으로 대응한다. 인증 API 가 503 을 내면 먼저 Redis 를 본다.

## 4. 데이터베이스 운영

### 4.1 Q-IM (PostgreSQL, 스키마 `qim` — D1 부터. 종전 MariaDB 는 이관 대상)

마이그레이션 위치: `idem-registry/src/main/resources/db/migration/`

| 버전 | 파일 | 변경 요약 |
|---|---|---|
| V1 | `V1__create_schema.sql` | 초기 스키마 |
| V2 | `V2__add_idempotent_consumer.sql` | 멱등 컨슈머 테이블 |
| V3 | `V3__add_ci_encryption_and_status_history.sql` | CI 암호화 + 상태이력 |
| V4 | `V4__fix_social_sso.sql` | 소셜 SSO 보정 |
| V5 | `V5__withdrawal_consent_conversion.sql` | 탈퇴·동의·전환 |
| V6 | `V6__minor_guardian_biz_member.sql` | 미성년·후견·기업회원 |
| V7 | `V7__fix_datetime_timezone.sql` | 시간대(UTC) 정합화 |

Flyway 설정: `baseline-on-migrate=true`, `validate-on-migrate=true`,
`out-of-order=false`. Hibernate `ddl-auto: validate` 이므로 스키마 불일치 시
부팅 실패한다.

운영 메모:
- Dialect: `org.hibernate.dialect.PostgreSQLDialect` (Flyway `db/migration/postgresql/V1__baseline_registry.sql`)
- JDBC URL에 `serverTimezone=UTC&rewriteBatchedStatements=true` 포함
- HikariCP: pool 최대 20, idle 5, connection-test `SELECT 1`

### 4.2 IdO (PostgreSQL, schema=`ido`)

마이그레이션 위치: `idem-hub/src/main/resources/db/migration/`

| 버전 | 파일 |
|---|---|
| V1 | `V1__create_schema.sql` |
| V2 | `V2__add_fe_session.sql` |
| V3 | `V3__add_keycloak_auth.sql` |
| V4 | `V4__add_qim_sp_receiver.sql` |
| V5 | `V5__add_processed_event.sql` |
| V6 | `V6__add_broker_audit_log.sql` |
| V7 | `V7__add_webhook_and_audit.sql` |
| V8 | `V8__seed_agency_api_key_and_fix_webhook.sql` |
| V9 | `V9__add_crypto_key_registry_and_rate_limit.sql` |
| V10 | `V10__extend_auth_result_and_provider_routing.sql` |
| V11 | `V11__add_slo_retention_config.sql` |
| V12 | `V12__add_mfa_aal_schema.sql` |
| V13 | `V13__seed_agency_pattern_scenarios.sql` |
| V14 | `V14__add_cast_token_and_sso_session_link.sql` |
| V15 | `V15__add_provisioning_outbox_and_agency_endpoint.sql` |
| V16 | `V16__add_gateway_inbound_audit.sql` |
| V17 | `V17__add_outbox_next_retry_at.sql` |
| V18 | `V18__update_event_type_constraints.sql` |
| V19 | `V19__anyid_provider_config.sql` |

Flyway 옵션:
- `schemas: ido`, `default-schema: ido`
- `validate-on-migrate: false` (V1 멱등화 후 체크섬 변경 허용)
- `repair-on-migrate: true` — 체크섬 불일치 시 자동 repair 후 migrate
- Hibernate `ddl-auto: validate`

### 4.3 마이그레이션 점검 절차

```bash
# Actuator로 Flyway 적용 상태 확인 (Q-IM)
curl -s http://localhost:8082/actuator/flyway | jq .

# IdO
curl -s http://localhost:8083/actuator/flyway | jq .
```

각 응답의 `migrations[]` 에서 `state=SUCCESS` 인지, `version` 순서가
끊겨있지 않은지 확인한다.

---

## 5. Kafka 토픽 운영

> **D1-b (2026-09-21)**: Kafka 는 선택 의존이다. `IDEM_KAFKA_ENABLED`(`idem.messaging.kafka.enabled`) 기본 **false** — 브로커 없이 기동하고 hub 아웃박스는 프로세스 내 배달, 감사는 DB 만, gate·registry·relay 의 Kafka 릴레이는 정지한다(어느 흐름이 멈추는지는 `docs/install.md` §6). 이 절은 `true`(다중 인스턴스·외부 연동, `compose.sso-im*.yml`·Helm 기본) 일 때만 해당한다.

### 5.1 토픽 목록 (코드 참조)

Q-IM (`idem-registry/src/main/resources/application.yml`):
- `qim.user.events` — `cleanup.policy=compact`
- `qim.user.snapshot` — Compacted snapshot

IdO (`idem-hub/src/main/resources/application.yml`):
- `ido.handoff.events` — Handoff Ticket 이벤트
- `platform.session.advisory` — FE 세션 어드바이저리
- `qsign.auth.events` — 인증 이벤트 (default; `IDO_KAFKA_TOPIC_AUTH_EVENTS` 로 변경)
- `qim.user.events` (구독) — Q-IM 사용자 이벤트
- `qim.user.snapshot` (구독)
- `qim.agency.events` — 기관 이벤트 (파티션 3, 보존 365일 — 주석 명시)
- `qim.sp.member.events` — **deprecated** (마이그레이션 완료 후 제거 예정)

### 5.2 컨슈머 그룹

| 그룹 | 환경변수 / 키 | 구독 토픽 |
|---|---|---|
| `q-im-consumer` | `spring.kafka.consumer.group-id` (q-im) | q-im 자체 컨슈머 |
| `ido-qim-consumer` | `ido.kafka.consumer-group-qim` | `qim.user.events`, `qim.user.snapshot` |
| `ido-qsign-consumer` | `ido.kafka.consumer-group-qsign` | `qsign.auth.events` |
| `ido-fe-advisory-consumer` | `ido.kafka.consumer-group-fe-advisory` | `platform.session.advisory` |
| `ido-qim-member-consumer` | `ido.kafka.consumer-group-qim-member` | `qim.user.events` (SP 수신용) |
| `ido-handoff-consumer` | `ido.kafka.consumer-group-handoff` | `ido.handoff.events` → Webhook Dispatcher |
| `ido-qim-sp-member-consumer` | (deprecated) | `qim.sp.member.events` |

### 5.3 파티션·복제 설정

PoC 기본값(`docker-compose.yml`): 파티션 6, 복제 1, ISR 1.

운영 3-broker 전환 시 환경변수:
```
IDO_KAFKA_PARTITION_COUNT_MAIN=12
IDO_KAFKA_PARTITION_COUNT_DLQ=6
IDO_KAFKA_REPLICATION_FACTOR=3
IDO_KAFKA_MIN_INSYNC_REPLICAS=2
```

토픽 초기 생성은 `infra/docker/kafka/create-topics.sh` 가 담당한다(컴포즈
`kafka-init` 서비스에서 1회 실행).

### 5.4 Compacted 토픽 점검

`qim.user.events`, `qim.user.snapshot` 은 compact 정책이므로 Log Cleaner
스레드(`KAFKA_LOG_CLEANER_ENABLE=true`, `THREADS=2`)가 동작해야 한다.
Kafka-UI(8090) → `Topics → 토픽명 → Config` 에서 `cleanup.policy=compact`
및 `min.cleanable.dirty.ratio=0.5` 확인.

### 5.5 Producer 트랜잭션

- Q-IM: `transactional.id=q-im-tx-producer`
- IdO: `transactional.id=ido-tx-producer`
- 컨슈머 `isolation.level=read_committed`

→ 트랜잭션 깨진 메시지는 컨슈머가 읽지 않으므로 운영 중 transactional id 변경 시
기존 producer fence가 일어남에 주의.

---

## 6. Outbox 릴레이 운영

### 6.1 Q-IM Outbox

코드: `idem-registry/src/main/java/io/github/hipstermin/idem/registry/outbox/OutboxServiceImpl.java`

설정(`qim.outbox.*`):
- `relay-interval-ms: 500` — PENDING 폴링 주기
- `batch-size: 100`
- `max-retry: 5` (초과 시 영구 `FAILED`)
- `retry-back-off-ms: 1000`
- `retry-interval-ms: 30000` — FAILED 재시도 스케줄러 주기

핵심 메서드:
- `OutboxService.publishInTx(DomainEvent)` — 트랜잭션 내 INSERT
- `OutboxService.relayPendingEvents()` — PENDING → PUBLISHED
- `OutboxService.relayFailedEvents()` — FAILED 재시도

`SnapshotService` 가 임계치에 도달한 사용자에 대해 `qim.user.snapshot` 으로
스냅샷 이벤트를 추가 발행한다.

### 6.2 IdO Outbox & Webhook

설정(`ido.outbox.*`):
- `relay-enabled: true` — Kafka 미사용 환경에서는 false로 폴링 정지
- `relay-interval-ms: 500`, `batch-size: 100`, `max-retry: 3`

Q-IM 회원 이벤트 릴레이(`ido.qim-outbox.*`):
- `relay-enabled: true`
- `relay-interval-ms: 1000`
- `batch-size: 50`, `max-retry: 5`

Webhook 릴레이(`ido.webhook.*`):
- `relay-interval-ms: 500`
- `relay-batch-size: 50`
- `connect-timeout-ms: 3000`, `read-timeout-ms: 8000`
- `signing-secret`: `IDO_WEBHOOK_SIGNING_SECRET` (HMAC-SHA256)

### 6.3 잔여 PENDING 모니터링

Prometheus에서 다음 메트릭을 추적(앱 자체 메트릭, `/actuator/prometheus`):

- `outbox_pending_total` — PENDING 건수
- `outbox_failed_total` — FAILED 건수
- `outbox_relay_duration_seconds` — 발행 latency
- `webhook_dispatch_attempts_total{result="success|failure"}` — Webhook 결과

대시보드: `infra/monitoring/dashboards/idem-overview.json` (Grafana 진입 시 기본 로딩).

### 6.4 수동 재시도

운영 데이터베이스에서 다음 쿼리로 재시도 가능:

```sql
-- Q-IM (PostgreSQL, 스키마 qim)
UPDATE outbox SET status='PENDING', retry_count=0, error_message=NULL
WHERE event_id IN (...);

-- IdO (PostgreSQL, schema=ido)
UPDATE ido.outbox SET status='PENDING', retry_count=0, next_retry_at=NOW()
WHERE event_id IN (...);
```

`next_retry_at` 컬럼은 V17 마이그레이션으로 도입되었다.

---

## 7. Feature Flag 운영

IdO는 단계적 롤아웃을 위해 다수의 Feature Flag를 가진다. 모두 환경변수
오버라이드 가능. (`idem-hub/src/main/resources/application.yml` 참조)

| Flag (env) | 기본값 | 역할 |
|---|---|---|
| `IDO_AUTH_RL_ENABLED` | true | NICE/OACX 인증 IP rate limit |
| `IDO_RATE_LIMIT_ENABLED` | true | 기관별 TPS/Daily 제한 |
| `IDO_REDISSON_ENABLED` | true | Redisson 분산 락 (K8s 다중 Pod 시 필수) |
| `IDO_SECURITY_HEADERS_ENABLED` | true | 보안 응답 헤더 필터 |
| `IDO_AUTH_TRACING_ENABLED` | true | OTel 분산 추적 AOP |
| `IDO_RETENTION_ENABLED` | **false** | 개인정보 파기 스케줄러 (활성 전 dry-run 필수) |
| `IDO_RETENTION_DRY_RUN` | **true** | 실제 삭제 전 시뮬레이션 |
| `IDO_RETENTION_DAYS` | 365 | 보존기간 (법무 확정 필요) |
| `IDO_OUTBOX_RELAY_ENABLED` | true | IdO Outbox 폴링 |
| `IDO_QIM_OUTBOX_RELAY_ENABLED` | true | Q-IM 이벤트 릴레이 |
| `IDO_PROVISIONING_ENABLED` | **false** | F-20 전 기관 프로비저닝 (Phase 2↑) |
| `IDO_PROVISIONING_DRY_RUN` | true | F-22 시뮬레이션 |
| `IDO_PROVISIONING_RELAY_ENABLED` | false | F-21 릴레이 |
| `IDO_GATEWAY_INBOUND_ENABLED` | false | F-23 인바운드 이벤트 수신 (Phase 3-A↑) |
| `IDO_GATEWAY_OUTBOUND_ENABLED` | false | F-24 아웃바운드 발송 (Phase 3-B↑) |
| `IDO_GATEWAY_IDEMPOTENCY_ENABLED` | true | F-25 멱등 중복 방어 |
| `IDO_HMAC_SIG_REQUIRED` | false | F-26 HMAC 서명 필수화 (Phase 4↑) |
| `IDO_AGENCY_KEY_AUDIT_LOG` | true | F-27 API Key 감사 |
| `IDO_AUDIT_KAFKA_ENABLED` | true | 감사 Kafka 발행 (Kafka 없는 환경 false 권장) |
| `IDO_AUDIT_DB_ENABLED` | true | **운영에서 false 금지** |
| `IDO_KMS_ENABLED` | false | KMS 활성화 |
| `IDO_KMS_PROVIDER` | vault | vault / nhn / noop |

상태 확인: `GET /actuator/features` (Actuator exposure include 에 `features` 포함됨).

---

## 8. KMS (Vault) 운영

`ido.kms.*` 설정. 컨테이너: `vault` (`infra/docker/docker-compose.yml`).

### 8.1 Vault 초기 설정 (개발 환경)

Dev 모드(`-dev`)는 인메모리이며 재시작 시 초기화된다. 운영은 Raft 백엔드 + TLS 필수.

```bash
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=dev-root-token   # docker-compose .env의 VAULT_TOKEN

vault secrets enable transit
vault write -f transit/keys/ido-handoff-key
```

### 8.2 인증 방식

- `VAULT_AUTH_METHOD=token` — 개발용 (Root Token)
- `VAULT_AUTH_METHOD=approle` — 자체 호스팅
- `VAULT_AUTH_METHOD=kubernetes` — **K8s 운영 권장** (Pod ServiceAccount)

`ido.kms.vault.allow-empty-token=false` (기본) 이므로 토큰 미설정 시
부팅 차단된다.

### 8.3 헬스 체크

`management.endpoint.health.group.readiness.include` 에 `kms` 가 포함되어 있어
Vault 다운 시 readiness probe FAIL → K8s 가 트래픽을 차단한다.
(`VaultKmsHealthIndicator` → `@Component("kms")`).

### 8.4 키 로테이션

`ido.crypto.rotation-enabled=true` + `rotation-check-cron: "0 0 * * * *"`
(매 시 정각). Handoff Ticket 폴백 키는 `ido.ticket.aes-key` / `hmac-key`
이며 90일 주기(`ido.ticket.key-rotation-days`) 권장, grace period 24시간.

---

## 9. 본인인증(NICE/OACX) Resilience

`ido.auth.*` + `resilience4j.*` 에 Circuit Breaker / Retry / TimeLimiter 정의.

| 인스턴스 | Sliding window | Failure rate | Open wait | Retry | TimeLimiter |
|---|---|---|---|---|---|
| `qim-client` | 10 | 60% | 15s | 3회 / 300ms | 5s |
| `keycloak-client` | 10 | 50% | 30s | 3회 / 500ms | 5s |
| `nice-api-client` | 10 | 50% | 30s | 2회 / 500ms (Exp backoff x2) | 12s |
| `integration-auth-client` | 10 | 50% | 60s | 2회 / 500ms (Exp backoff x2) | 12s |

운영 시 NICE/통합인증 외부 점검 공지 발생 시 임시로
`IDO_AUTH_RL_ENABLED=false` 또는 Circuit Open 상태를 모니터링한다.

CB 상태 메트릭:
- `resilience4j_circuitbreaker_state{name="nice-api-client",state="open"}`

---

## 10. 기관(Agency) 운영

엔드포인트: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/admin/AgencyAdminController.java`

| Method | Path | 설명 |
|---|---|---|
| POST | `/admin/agencies` | 기관 생성 |
| GET | `/admin/agencies` | 기관 목록 (paging) |
| GET | `/admin/agencies/{agencyCode}` | 단건 조회 |
| PUT | `/admin/agencies/{agencyCode}` | 수정 |
| POST | `/admin/agencies/{agencyCode}/activate` | 활성화 |
| POST | `/admin/agencies/{agencyCode}/deactivate` | 비활성화 |
| POST | `/admin/agencies/{agencyCode}/rotate-key` | API 키 로테이션 (PBKDF2 해시 저장) |
| GET | `/admin/agencies/{agencyCode}/history` | 변경 이력 |
| GET | `/admin/agencies/{agencyCode}/stats` | 통계 |

호출 시 `X-Admin-Id` (또는 SecurityContext) 가 audit log 에 기록된다.

기관 → IdO 인바운드(F-23)는 `IDO_GATEWAY_INBOUND_ENABLED=true` 가 필요하며,
`X-Agency-Code` + HMAC 헤더(`IDO_HMAC_SIG_REQUIRED=true` 시) 로 인증한다.
키 검증은 `ido.gateway.AgencyHmacKeyStore` 가 담당.

---

## 11. Q-IM API 운영 진단

운영자가 데이터 정합성을 확인할 때 사용하는 주요 엔드포인트
(`idem-registry/src/main/java/io/github/hipstermin/idem/registry/api/`):

| Controller | Method | Path | 용도 |
|---|---|---|---|
| `UserController` | POST | `/api/v1/users` | 회원 등록/조회 |
| `UserController` | GET | `/api/v1/users/{qimUserId}` | 단건 조회 |
| `UserController` | GET | `/api/v1/users/by-hash` | CI 해시 기반 조회 |
| `UserController` | PUT | `/api/v1/users/{qimUserId}/status` | 상태 변경 |
| `UserController` | POST | `/api/v1/users/{qimUserId}/withdraw` | 탈퇴 |
| `UserController` | GET | `/api/v1/users/{qimUserId}/di` | DI 조회 |
| `MemberLookupController` | POST | `/api/v1/member-lookup/by-ci` | 평문 CI 기반 조회 |
| `MemberLookupController` | POST | `/api/v1/member-lookup/by-hash` | SHA-256 해시 조회 |
| `ConsentController` | POST | `/api/v1/consents/agree` | 동의 등록 |
| `ConsentController` | POST | `/api/v1/consents/withdraw` | 동의 철회 |
| `ConsentController` | GET | `/api/v1/consents/status` | 현재 동의 상태 |
| `ConversionController` | POST | `/api/v1/conversion/initiate` | 회원 전환 시작 |
| `ConversionController` | POST | `/api/v1/conversion/link` | 계정 연결 확정 |
| `GuardianConsentController` | POST | `/api/v1/guardian-consent` | 보호자 동의 |
| `WithdrawalController` | POST | `/api/v1/withdrawal` | 탈퇴 신청 |
| `WithdrawalController` | DELETE | `/api/v1/withdrawal/schedule` | 예약 탈퇴 취소 |
| `QimStatusController` | GET | `/api/v1/qim-status/{qimUserId}` | 통합 상태 조회 |

모든 호출은 `X-Internal-Api-Key: $QIM_INTERNAL_API_KEY` 필요 (IdO 호출 동일).

`GlobalExceptionHandler` 가 표준 에러 응답을 반환한다 — 추적 시 `correlationId`
와 `errorCode` 를 우선 확인한다.

---

## 12. 개인정보 파기 (Retention)

스케줄러: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/retention/PersonalDataRetentionScheduler.java`

기본 동작:
1. `executeRetentionPolicy()` 가 주기적으로 실행 (cron은 클래스 내 어노테이션 확인).
2. `findExpiredWithdrawnMembers(retentionCutoff)` 로 보존기간 만료된 탈퇴자 조회.
3. `purgePersonalData(instMbrId)` 로 개인정보 파기 (`IDO_RETENTION_DRY_RUN=true`
   시 실제 삭제 없이 로그만).
4. `publishRetentionAuditLog(...)` 로 감사 로그 발행.

운영 절차 (코드상 안전장치):
1. `IDO_RETENTION_DAYS` 를 법무팀 확정값으로 설정.
2. `IDO_RETENTION_DRY_RUN=true` 로 최소 2주 관찰 → `retention_audit_log` 검토.
3. 검토 완료 후 `IDO_RETENTION_DRY_RUN=false`, `IDO_RETENTION_ENABLED=true` 전환.
4. 매 실행 후 감사 로그(`retention_audit_log` 테이블 + Kafka 토픽) 자동 발행.

---

## 13. 헬스·관측 (Actuator)

### 13.1 노출 엔드포인트

세 서비스 모두 `management.endpoints.web.exposure.include` 에 다음 포함:

- `/actuator/health` (probes: liveness, readiness)
- `/actuator/info`
- `/actuator/metrics`
- `/actuator/prometheus` — Prometheus scrape
- `/actuator/flyway`
- (IdO 전용) `/actuator/features`

### 13.2 IdO Health Group

```yaml
management.endpoint.health.group:
  liveness:
    include: livenessState
  readiness:
    include: readinessState, db, redis, kms
```

→ Kafka 다운은 readiness FAIL 사유가 아님(메트릭으로만 추적).
→ KMS(Vault) 다운은 readiness FAIL 사유 → 트래픽 차단.

### 13.3 OTel 분산 추적

```yaml
management.tracing.sampling.probability: ${TRACING_SAMPLING_PROBABILITY:1.0}
management.otlp.tracing.endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

운영 권장 샘플링: **0.1 ~ 0.3** (10% ~ 30%). W3C TraceContext (`traceparent`)
헤더로 FE → IdO → Q-IM / Q-Sign 추적.

### 13.4 Prometheus & Grafana

- Prometheus 설정: `infra/monitoring/prometheus/prometheus.yml`
- 알람 규칙: `infra/monitoring/prometheus/alert_rules.yml`
- 대시보드: `infra/monitoring/dashboards/idem-overview.json`
- 로그: Loki + Promtail (컨테이너 label `logging=promtail` 인 서비스만 수집)

---

## 14. 보안 응답 헤더 / CORS

`IDO_SECURITY_HEADERS_ENABLED=true` 시 `SecurityHeadersFilter` 가 활성화된다.
CORS 는 `ido.cors.allowed-origins` 환경변수로 통제:

```yaml
ido.cors:
  enabled: true
  allowed-origins:
    - ${CORS_ORIGIN_DEV:http://localhost:3000}    # React dev
    - ${CORS_ORIGIN_PROD:http://localhost:3001}   # Nginx React
```

운영 배포 시 실제 도메인으로 변경. `ido.fe.allowed-return-urls` 도 동일하게
ConfigMap 또는 환경변수로 주입한다(기관 callback whitelist 보조).

기본 등록된 운영 URL:
- `https://www.smes.go.kr` / `https://www.bizinfo.go.kr` / `https://www.mss.go.kr`
- `https://www.sbiz.or.kr` / `https://www.fanfan.or.kr` / `https://www.kosmes.or.kr`

---

## 15. 인증 흐름 운영 메모

### 15.1 Broker 모드

`IDO_BROKER_MODE` (기본 `qsign`):
- `qsign` — Q-Sign 에 OIDC URL 발급 위임
- `keycloak` — IdO가 직접 Keycloak Authorization URL 생성

**비표준 OIDC(Any-ID, PASS 등)** 는 `BROKER_MODE` 와 무관하게 항상 IdO 직접 처리.
- `MOBILE_ID` / `EASY_SIGN` / `JOINT_CERT` / `FINANCIAL_CERT` / `PRIVATE_ID`
  → `AnyIdBrokerAdapter` (anyid.dev:1443 연동)
- 기타 → `NonOidcBrokerAdapter`

### 15.2 FE Session (BFF)

`ido.fe.session.*`:
- `sliding-ttl-minutes: 30` — 활동 시 갱신
- `absolute-timeout-minutes: 480` — 절대 만료 8시간
- 쿠키: `feSessionId`, `HttpOnly`, `Secure`, `SameSite=Lax`

### 15.3 Handoff Ticket

`ido.ticket.*`:
- `ttl-seconds: 60` — 1회성
- `encryption-algorithm: AES-256-GCM`
- `signing-algorithm: HMAC-SHA256`
- `key-rotation-days: 90`, `key-grace-period-hours: 24`
- `reuse-block-threshold: 3` — 재사용 시도 3회 시 차단

---

## 16. 장애 대응 플레이북

### 16.1 부팅 실패 (CrashLoopBackOff)

가장 빈번한 원인은 운영 필수 환경변수 누락이다. 다음을 순서대로 확인:

```bash
# 컨테이너 로그
docker logs idem-hub --tail=200 | grep -E "validate|empty|required|fail"
docker logs idem-registry --tail=200 | grep -E "validate|empty|required|fail"

# 검증 메서드 위치
# - q-im: CiCryptoServiceImpl.validateKeyV1(), DiGenerationService.validateDiSecret()
# - ido : KeyVersionRegistry.validateFallbackKeys(),
#         WebhookDispatcherService.validateSigningSecret(),
#         KeycloakProperties.validateClientSecret()
```

각 검증 클래스의 메시지를 보고 누락된 환경변수를 식별한다.

### 16.2 Kafka 발행 누락 (Outbox PENDING 증가)

1. `outbox_pending_total` 메트릭 알람.
2. Kafka-UI(8090) 에서 토픽 상태(파티션 leader, ISR) 확인.
3. Kafka 정상이면 앱 측 점검:
   - `IDO_OUTBOX_RELAY_ENABLED=true` 여부
   - 트랜잭션 ID 충돌 (producer fence)
   - Resilience4j CB Open 여부 (`kafka` 자체에는 CB 미설정)
4. 수동 재시도: §6.4 SQL.

### 16.3 Vault 다운 → 트래픽 차단

readiness probe FAIL 로 K8s가 자동으로 endpoint에서 제외한다. 단,
`ido.kms.enabled=false` 로 임시 우회 가능. **운영 우회는 보안 사고이므로
승인 절차 + 사후 키 재발급 필요**.

### 16.4 NICE/OACX 외부 점검

1. Resilience4j CB가 Open으로 전환되면서 fast-fail 시작.
2. FE에서는 인증 시작 시 사용자에게 점검 안내 메시지 표시 필요(별도 FE 작업).
3. 점검 종료 후 자동 Half-Open → Closed 복귀(`wait-duration-in-open-state`).

### 16.5 데이터 정합성 깨짐 (Q-IM ↔ IdO)

1. `qim.user.snapshot` 토픽의 최신 메시지로 IdO 캐시 재구축 가능.
2. 재구축이 필요한 사용자 ID에 대해 `SnapshotService.publishSnapshot(...)` 강제 호출
   (개발자/오퍼레이터 도구로 한정).
3. IdO `ido.qim.cache-ttl-seconds: 300` 만료 후 자동 재조회됨.

---

## 17. 정기 점검 체크리스트

### 일간
- [ ] Grafana `idem-overview` 대시보드 SLA 패널 (P95 latency, 5xx rate)
- [ ] `outbox_pending_total` < 100 (각 모듈)
- [ ] `webhook_dispatch_attempts_total{result="failure"}` 증가 추세 없음
- [ ] CB Open 카운트 0

### 주간
- [ ] Kafka 토픽별 lag 점검(특히 `ido-handoff-consumer`)
- [ ] Vault Transit 키 사용량 / 에러 로그
- [ ] `retention_audit_log` (dry-run 단계) 결과 리뷰
- [ ] 마이그레이션 추가 여부 (`git log --oneline idem-registry/src/main/resources/db idem-hub/src/main/resources/db`)

### 월간
- [ ] AES/HMAC 키 회전 일정 점검 (90일)
- [ ] 기관 API Key 회전 권고 (PBKDF2 해시 갱신: `/admin/agencies/{code}/rotate-key`)
- [ ] Keycloak / Any-ID 인증서 만료일 확인
- [ ] Docker 이미지 보안 패치 (postgres, kafka, keycloak, vault)

---

## 부록 A. 포트 빠른 참조

| 서비스 | 호스트 포트 | 비고 |
|---|---|---|
| Q-Sign | 8081 | |
| Q-IM | 8082 | |
| IdO | 8083 | |
| Agency-Stub | 8084 | |
| Schema Registry | 8085 | profile=schema |
| Keycloak | 8088 | profile=keycloak |
| Kafka-UI | 8090 | `admin/admin` |
| Adminer | 8091 | profile=tools |
| Kafka | 9092 (외부) | |
| JMX | 9999 | |
| Prometheus | 9090 | profile=monitoring |
| Grafana | 3002 | `admin/admin` |
| Loki | 3100 | |
| React SPA | 3001 | profile=optionB |
| React Dev (mount) | 3000 | idem-console/frontend |
| pgAdmin | 5050 | profile=tools |
| Redis Insight | 5540 | |
| PostgreSQL | 5432 | |
| Redis | 6379 | |
| Zookeeper | 2181 | |
| Vault | 8200 | |

## 부록 B. 환경변수 → 컴포넌트 매핑 요약

| Prefix | 컴포넌트 |
|---|---|
| `QIM_*` | Q-IM |
| `IDO_*` | IdO |
| `QSIGN_*` | Q-Sign |
| `ANYID_*` | Any-ID 연동 (IdO) |
| `KEYCLOAK_*` | Keycloak (IdO + Q-Sign) |
| `NICE_*` / `OACX_*` / `INTEGRATION_AUTH_*` | 본인인증 (IdO) |
| `VAULT_*` | Vault KMS (IdO) |
| `KAFKA_SERVERS` | 공통 |
| `REDIS_*` | 공통 |
| `DB_*` | PostgreSQL (Q-Sign, IdO, Agency-Stub) |
| `CORS_ORIGIN_*` | IdO BFF |
| `OTLP_ENDPOINT` / `TRACING_SAMPLING_PROBABILITY` | 공통 (OTel) |

## 부록 C. 참고 코드 위치

- 부팅 가드:
  - `idem-registry/src/main/java/io/github/hipstermin/idem/registry/crypto/CiCryptoServiceImpl.java`
  - `idem-registry/src/main/java/io/github/hipstermin/idem/registry/identity/DiGenerationService.java` (위치는 패키지 검색)
  - `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/crypto/KeyVersionRegistry.java`
  - `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/webhook/WebhookDispatcherService.java`
- Outbox:
  - `idem-registry/.../qim/outbox/OutboxServiceImpl.java`, `SnapshotServiceImpl.java`
  - `idem-hub/.../idem-hub/webhook/` (Webhook Outbox Relay)
- Admin:
  - `idem-hub/.../idem-hub/admin/AgencyAdminController.java`
  - `idem-hub/.../idem-hub/admin/AgencyAdminService.java`
- Retention:
  - `idem-hub/.../idem-hub/retention/PersonalDataRetentionScheduler.java`
- Health 그룹:
  - `idem-hub/src/main/resources/application.yml` (라인 ~842, `management.endpoint.health.group`)

> 본 메뉴얼은 코드 변경 시 함께 갱신되어야 한다. 환경변수·토픽·마이그레이션
> 버전을 변경한 PR 은 본 문서 동기화를 체크리스트에 포함하라.
