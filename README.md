# OnePass 통합인증 플랫폼 (Integration-SSO) PoC

**중소벤처기업부 중기원패스(OnePass) 통합인증 SSO 및 아이덴티티 관리 시스템** PoC 구현체입니다.  
**4+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe · agency-stub) 기반 EDA 아키텍처로 구성됩니다.

> **최신 버전: v1.9.3** — 기관 이벤트 폴링 API 완성 (`GET /api/v1/agency/events`)  
> **빌드 상태**: `./gradlew build -x test` → **BUILD SUCCESSFUL** (전 모듈)  
> **브랜치**: `genspark_ai_developer` → `main`

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [아키텍처 개요](#아키텍처-개요)
3. [모듈 책임 분리 요약](#모듈-책임-분리-요약)
4. [기술 스택](#기술-스택)
5. [모듈 구성](#모듈-구성)
6. [데이터베이스 구성](#데이터베이스-구성)
7. [Kafka 토픽](#kafka-토픽)
8. [보안 체계](#보안-체계)
9. [Flyway 마이그레이션 현황](#flyway-마이그레이션-현황)
10. [agency-stub: 유관기관 PoC 시뮬레이터](#agency-stub-유관기관-poc-시뮬레이터)
11. [빠른 시작](#빠른-시작)
12. [인프라 (Docker Compose)](#인프라-docker-compose)
13. [접속 URL](#접속-url)
14. [개발 환경 설정](#개발-환경-설정)
15. [빌드 & 실행](#빌드--실행)
16. [현재 구현 완성도](#현재-구현-완성도)
17. [문서 디렉토리](#문서-디렉토리)

---

## 버전 히스토리

| 버전 | PR | 주요 내용 |
|------|----|-----------| 
| **v1.9.3** | [#28](https://github.com/HipsterMIN/integration-sso/pull/28) | **기관 이벤트 폴링 API** — `GET /api/v1/agency/events` + `POST /{id}/read` 완전 구현 |
| **v1.9.2** | [#27](https://github.com/HipsterMIN/integration-sso/pull/27) | **P2 GAP 마감** — `InternalSsoHandoffStrategy`, `ApacheGateHandoffStrategy` 완성; GAP-QS-03 멱등 컨슈머; GAP-QIM-05 Snapshot 발행 |
| **v1.9.1** | [#26](https://github.com/HipsterMIN/integration-sso/pull/26) | **P1 GAP 마감** — DLQ 완전 구현, X-Internal-Sig 생성, Outbox 재시도 스케줄러 |
| **v1.9.0** | [#24](https://github.com/HipsterMIN/integration-sso/pull/24) | **P0/P1/P2 GAP 마감** — `auth_result` V10 확장, `broker_audit_log` 코드 연결, `ProviderRouter`, 동적 CircuitBreaker |
| v1.8.0 | [#22](https://github.com/HipsterMIN/integration-sso/pull/22) | Admin API, Redis Rate Limiter, HandoffStrategy 패턴, PKCE(RFC 7636), 모니터링 스택 |
| v1.7.0 | [#19](https://github.com/HipsterMIN/integration-sso/pull/19) | E2E 시뮬레이터 + X-Agency-Key SHA-256 검증 인터셉터 + 테스트 Web UI |
| v1.6.0 | [#18](https://github.com/HipsterMIN/integration-sso/pull/18) | agency-stub OIDC 클라이언트 완전 구현 — Webhook 수신(HMAC-SHA256), `IdoVerifyClient`(CB+Retry), 세션 관리 |
| v1.5.0 | [#17](https://github.com/HipsterMIN/integration-sso/pull/17) | 유관기관 외부망 Webhook 연동 전체 스택 |
| v1.4.x | [#16](https://github.com/HipsterMIN/integration-sso/pull/16) | Docker 컨테이너화 + 보안 강화 + 외부망 배치 원칙 |

---

## 아키텍처 개요

> **⚠️ 설계 원칙**: 모든 유관기관(기관 시스템)은 **외부망**에 위치합니다.  
> 기관은 내부 Kafka·DB에 직접 접근하지 않으며, **IdO 공개 API(HTTPS)** 만을 통해 통신합니다.  
> `agency-stub`은 이 외부 기관을 시뮬레이션하는 PoC 전용 컴포넌트입니다.

```
══════════════════════════════════════════════════════════════════════
  외부망 (External Network)
══════════════════════════════════════════════════════════════════════

  ┌──────────────────────────────────┐  ┌──────────────────────────────────┐
  │      최종 사용자 (브라우저 / 앱)     │  │    유관기관 시스템 (외부망)           │
  │                                  │  │                                  │
  │  [개발] React dev :3000           │  │  agency-stub :8084  ← PoC 전용  │
  │    webpack proxy → ido:8083      │  │                                  │
  │  [운영] Nginx :3001               │  │  ① POST /api/v1/handoff/issue   │
  │    /api/** → ido:8083            │  │  ② POST /api/v1/handoff/verify  │
  └──────────┬───────────────────────┘  │  ③ GET  /api/v1/agency/events   │
             │ HTTPS                    │  ④ POST /api/v1/webhook/inbound │
             │ /api/v1/fe-session/**    └──────────────┬───────────────────┘
             │ /api/v1/handoff/**                       │ HTTPS (공개 API만)
             │ /api/v1/oidc/**                          │
══════════════════════════════════════════════════════╪══════════════════════
  내부망 (Internal Network — onepass-net 172.20.0.0/24)│
══════════════════════════════════════════════════════╪══════════════════════
             │                                         │
             ▼                                         ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │  ido  :8083  정책 오케스트레이터 + FE BFF                              │
  │                                                                     │
  │  [FE BFF]                        [기관향 공개 API]                   │
  │  feSessionId 쿠키 발급/갱신/만료    POST /api/v1/handoff/issue         │
  │  ReturnUrl 화이트리스트 검증        POST /api/v1/handoff/verify        │
  │  platform.session.advisory 소비   GET  /api/v1/agency/events ★v1.9.3│
  │                                   ↑ HandoffAgencyKeyInterceptor      │
  │  [IdP 브로커]                      X-Agency-Key SHA-256 DB 검증       │
  │  /api/v1/broker/**                                                  │
  │  /api/v1/oidc/**                  [Webhook Push]                    │
  │  ProviderRouter (v1.9.0)          WebhookDispatcherService           │
  │  BrokerAuditLogService (v1.9.0)   WebhookDispatchOutboxRelay        │
  │                                                                     │
  │  [Admin]                          [Q-IM SP 수신]                    │
  │  /api/v1/admin/agencies/**        /api/qim/sp/v1/**                 │
  └──────────────────┬───────────────────────────────────────────────────┘
                     │ HTTP (내부망 전용)
         ┌───────────┴───────────┐
         ▼                       ▼
  ┌─────────────┐       ┌─────────────┐
  │ q-sign:8081 │       │  q-im:8082  │
  │  인증 SoR    │       │  식별 SoR    │
  │  Keycloak   │       │  회원 원장   │
  │  OIDC 브로커 │       │  CI 암호화   │
  └──────┬──────┘       └──────┬──────┘
         │  Outbox              │  Outbox
         └──────────┬───────────┘
                    ▼
    ┌───────────────────────────────────┐
    │           Apache Kafka            │
    │  qsign.auth.events                │
    │  ido.handoff.events               │
    │  qim.user.events (Compacted)      │
    │  platform.session.advisory        │
    │  platform.audit.log               │
    │  qim.sp.member.events             │
    │  + 각 토픽별 .dlq 토픽             │
    └───────────────────────────────────┘
```

---

## 모듈 책임 분리 요약

| 모듈 | SoR 역할 | 포트 | 핵심 책임 |
|------|---------|------|----------|
| `platform-common` | — | — | 공통 도메인·이벤트·에러코드 라이브러리 |
| `q-sign` | **인증 SoR** | 8081 | OIDC 브로커링, JWT 검증, PKCE, auth_result 저장 |
| `q-im` | **식별 SoR** | 8082 | qimUserId, CI AES-256-GCM, DI HMAC, 회원 원장 |
| `ido` | **정책 오케스트레이터** | 8083 | Handoff 발급/검증, Policy Engine, Webhook, FE BFF |
| `agency-stub` | — (PoC 전용) | 8084 | 유관기관 연동 E2E 시뮬레이터 |
| `onepass-fe` | — | 3000/3001 | React 18 SPA (TypeScript, Ant Design) |

### SoR 정의 (Source of Record)

| 데이터 | SoR |
|--------|-----|
| 인증 결과 (auth_result) | **Q-Sign** |
| 통합 식별자 (qimUserId, CI, DI) | **Q-IM** |
| 기관 세션 (agencySession) | **유관기관 시스템** |
| 정책 (policy, handoff ticket) | **IdO** |

> IdO의 티켓·정책·감사 로그·캐시는 **운영 상태**이지, Source of Record가 아닙니다.

---

## 기술 스택

### 백엔드 공통

| 기술 | 버전 | 적용 범위 |
|------|------|----------|
| Java | **21 LTS** | 전 모듈 |
| Spring Boot | **3.5.9** | q-sign, q-im, ido, agency-stub |
| Gradle | **9.5.0** | 멀티모듈 빌드 (`onepass-platform`) |
| Spring Data JPA | BOM 관리 | q-sign(PostgreSQL), q-im(MariaDB), ido(PostgreSQL) |
| Spring Kafka | BOM 관리 | 전 서비스 |
| Spring Data Redis | BOM 관리 | ido, q-im |
| Flyway | **11.8.0** | DB 마이그레이션 |
| Resilience4j | **2.2.0** | Circuit Breaker, Retry |
| JJWT | **0.12.6** | JWT 서명 검증 |
| MapStruct | **1.6.3** | DTO ↔ 도메인 매핑 |
| Lombok | 최신 안정 | 전 모듈 |

### 프론트엔드 (`onepass-fe/frontend/`)

| 기술 | 버전 |
|------|------|
| React | 18.3 |
| TypeScript | 5.4 |
| Webpack | 5.92 |
| Ant Design | 5.18 |
| TanStack Query | v5 |
| Zustand | 4.5 |
| Node.js (빌드) | 20.14 LTS |
| Yarn | 1.22 |

### 인프라

| 서비스 | 이미지 | 용도 |
|--------|--------|------|
| PostgreSQL | `postgres:16-alpine` | q-sign(qsign) + ido(ido) 스키마 |
| MariaDB | `mariadb:11.4` | q-im 전용 |
| Redis | `redis:7.2-alpine` | FE 세션, PKCE, 캐시, Rate Limit |
| Kafka | `confluentinc/cp-kafka:7.6.1` | 이벤트 버스 |
| Keycloak | `quay.io/keycloak/keycloak:24` | OIDC IdP 브로커 |
| Prometheus | `prom/prometheus:v2.51.2` | 메트릭 수집 |
| Grafana | `grafana/grafana:10.4.2` | 대시보드 |
| Loki | `grafana/loki:3.0.0` | 로그 집계 |

---

## 모듈 구성

```
onepass-platform/
├── platform-common/          # 공통 도메인·이벤트·에러코드
│   └── src/main/java/kr/go/smes/common/
│       ├── domain/           # AuthResult, HandoffPayload
│       ├── error/            # PlatformErrorCode
│       ├── event/            # AuthEvent, HandoffEvent, AuditLogEvent
│       └── exception/        # PlatformException
│
├── q-sign/                   # 인증 SoR (포트 8081)
│   └── src/main/java/kr/go/smes/qsign/
│       ├── broker/           # Keycloak OIDC 브로커
│       ├── kafka/            # Outbox + 멱등 컨슈머 (v1.9.2)
│       └── pkce/             # RFC 7636 PKCE
│
├── q-im/                     # 식별 SoR (포트 8082, MariaDB)
│   └── src/main/java/kr/go/smes/qim/
│       ├── crypto/           # CI AES-256-GCM + PII 마스킹
│       ├── identity/         # DI HMAC-SHA256 생성
│       ├── outbox/           # Outbox + Snapshot (v1.9.2)
│       └── user/             # 회원 등록·조회·상태
│
├── ido/                      # 정책 오케스트레이터 (포트 8083)
│   └── src/main/java/kr/go/smes/ido/
│       ├── admin/            # 기관 Admin API
│       ├── api/              # Handoff + 기관 이벤트 폴링 (v1.9.3)
│       ├── broker/           # IdP 브로커 + Provider 라우팅 (v1.9.0)
│       ├── config/           # Rate Limit, TraceparentFilter, PKCE
│       ├── crypto/           # AES 키 로테이션 스케줄러
│       ├── fe/               # FE 세션 관리
│       ├── handoff/          # Ticket 발급/검증 + Strategy 패턴
│       ├── kafka/            # 이벤트 컨슈머 4종
│       ├── policy/           # PolicyEngine (상태·수준·속성·점검)
│       ├── ratelimit/        # Redis Lua 슬라이딩 윈도우
│       └── webhook/          # Webhook Push + 기관 이벤트 폴링
│
├── agency-stub/              # 기관 시뮬레이터 (포트 8084, PoC 전용)
│   └── src/main/java/kr/go/smes/agency/
│       ├── client/           # IdoTicketClient, IdoVerifyClient
│       ├── health/           # AgencyHealthController
│       ├── session/          # AgencySessionService (AGSID 쿠키)
│       ├── simulator/        # AgencySimulatorController (E2E)
│       └── webhook/          # WebhookInboundController (HMAC 검증)
│
├── onepass-fe/               # React SPA (포트 3000/3001)
│   └── frontend/             # TypeScript, Ant Design, TanStack Query
│
└── infra/
    └── docker/               # docker-compose.yml + 설정 파일 전체
        ├── monitoring/       # Prometheus + Grafana + Loki + Promtail
        └── keycloak/         # realm-export.json
```

---

## 데이터베이스 구성

| 모듈 | DB 엔진 | 스키마 | 최신 Flyway 버전 |
|------|---------|--------|----------------|
| Q-Sign | PostgreSQL 16 | `qsign` | **V5** — auth_method 컬럼 |
| IdO | PostgreSQL 16 | `ido` | **V10** — auth_result 확장, provider_routing, broker_audit_log 인덱스 |
| Q-IM | **MariaDB 11.4** | `qim` | **V3** — CI 암호화 키 버전, user_status_history |
| agency-stub | PostgreSQL 16 | `agency_stub` | **V2** — webhook + api_key |

> Q-IM만 MariaDB를 사용합니다 (운영: NHN Cloud RDS for MariaDB).

---

## Kafka 토픽

| 토픽 | 파티션 | 보존 | 생산자 | 소비자 |
|------|--------|------|--------|--------|
| `qsign.auth.events` | 12 | 1h | Q-Sign | IdO |
| `qsign.auth.events.dlq` | 6 | 7d | IdO 에러핸들러 | 운영 |
| `ido.handoff.events` | 12 | 1y | IdO | IdO → Webhook |
| `ido.handoff.events.dlq` | 6 | 7d | IdO 에러핸들러 | 운영 |
| `platform.session.advisory` | 12 | 24h | IdO | IdO |
| `platform.session.advisory.dlq` | 6 | 7d | IdO 에러핸들러 | 운영 |
| `platform.audit.log` | 12 | 2y | IdO | 감사 시스템 |
| `qim.user.events` | 6 | Compacted | Q-IM | IdO, Q-Sign |
| `qim.user.events.dlq` | 3 | 7d | Q-IM 에러핸들러 | 운영 |
| `qim.user.snapshot` | 6 | Compacted | Q-IM | (확장 예정) |
| `qim.sp.member.events` | 6 | 30d | IdO | IdO |
| `qim.sp.member.events.dlt` | 3 | 7d | IdO 에러핸들러 | 운영 |

---

## 보안 체계

| 보안 항목 | 구현 방식 | 상태 |
|----------|---------|------|
| 기관 API 키 인증 | SHA-256 해시 + 상수시간 비교 | ✅ 완료 |
| Handoff Ticket 암호화 | AES-256-GCM | ✅ 완료 |
| Ticket 서명 | HMAC-SHA256 | ✅ 완료 |
| 내부 서비스 서명 (발신) | HMAC-SHA256 X-Internal-Sig | ✅ 완료 |
| 내부 서비스 서명 (수신 검증) | — | ⚠️ P1 미완 |
| Webhook 서명 | HMAC-SHA256 + ±5분 타임스탬프 | ✅ 완료 |
| PKCE (RFC 7636) | S256 code_challenge | ✅ 완료 |
| W3C traceparent 전파 | TraceparentFilter | ✅ 완료 |
| Rate Limiter | Redis Lua 슬라이딩 윈도우 | ✅ 완료 |
| Provider 단위 CB | Resilience4j 동적 생성 | ✅ 완료 |
| CI 암호화 | AES-256-GCM 버전 기반 | ✅ 완료 |
| agencySubjectId | HMAC-SHA256 + Base64URL | ✅ 완료 |
| DI 생성 | HMAC-SHA256 결정론적 | ✅ 완료 |
| 키 로테이션 스케줄러 | 90일 주기 Redis 분산 락 | ✅ 완료 |

---

## Flyway 마이그레이션 현황

### IdO (ido 스키마) — 최신: V10

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | `V1__create_schema.sql` | agency_meta, handoff_ticket 기본 스키마 |
| V2 | `V2__add_outbox.sql` | outbox_event |
| V3 | `V3__add_keycloak_auth.sql` | auth_result, auth_lock, provider_config |
| V4~V5 | `V4/V5__...` | oidc_session, broker_audit_log |
| V6 | `V6__add_broker_audit_log.sql` | broker_audit_log + provider_type 컬럼 |
| V7~V8 | `V7/V8__...` | maintenance, webhook_dispatch_outbox |
| V9 | `V9__add_crypto_key_registry_and_rate_limit.sql` | crypto_key_registry, agency_rate_limit_config |
| **V10** | `V10__extend_auth_result_and_provider_routing.sql` | ★ auth_result 4컬럼 추가, provider_circuit_config |

---

## agency-stub: 유관기관 PoC 시뮬레이터

`agency-stub`은 **실제 유관기관이 IdO와 연동하는 E2E 흐름**을 시뮬레이션합니다.

### 기동 후 Web UI 접속

```
http://localhost:8084/
```

### 주요 API

```
POST /api/v1/simulator/run        # 3단계 E2E 전체 흐름 (Ticket 발급 → 검증 → 세션)
GET  /api/v1/simulator/status     # DB + IdO 연결 상태
POST /api/v1/handoff/inbound      # Webhook 수신 (HMAC-SHA256 검증)
GET  /api/v1/events/poll          # 이벤트 폴링 (Pull 방식)
GET  /api/v1/health               # 헬스 진단 (UP/DEGRADED/DOWN)
```

### 3단계 E2E 흐름

```
[STEP 1] Ticket 발급
  agency-stub → POST {ido}/api/v1/handoff/issue
  Headers: X-Agency-Code, X-Agency-Key, Idempotency-Key
  Resilience4j: CircuitBreaker "ido-ticket"

[STEP 2] Ticket 검증
  agency-stub → POST {ido}/api/v1/handoff/verify
  Resilience4j: CB "ido-verify" + Retry 3회 (500ms → exponential)

[STEP 3] 기관 세션 생성
  SecureRandom 192-bit → SHA-256 저장 → AGSID 쿠키 발급
  Secure / HttpOnly / SameSite=Strict
```

---

## 빠른 시작

### 필수 소프트웨어

| 소프트웨어 | 최소 버전 |
|-----------|---------|
| JDK | **21 LTS** |
| Docker Desktop | **24+** |
| Docker Compose | **v2** (플러그인) |
| Node.js | **20.14 LTS** |
| Yarn | **1.22** |

### 기동 절차

```bash
# 1. 저장소 복제
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso
chmod +x gradlew

# 2. 인프라 기동 (PostgreSQL, MariaDB, Redis, Kafka, Zookeeper)
docker compose -f infra/docker/docker-compose.yml up -d

# 3. 컨테이너 정상 확인 (약 30~60초 대기)
docker compose -f infra/docker/docker-compose.yml ps

# 4. 백엔드 전체 빌드 (약 15초)
./gradlew :platform-common:build :q-sign:build :q-im:build :ido:build :agency-stub:build -x test

# 5. 서비스 기동 (터미널 4개)
./gradlew :q-sign:bootRun          # :8081
./gradlew :q-im:bootRun            # :8082
./gradlew :ido:bootRun             # :8083
./gradlew :agency-stub:bootRun     # :8084

# 6. 프론트엔드 기동
cd onepass-fe/frontend && yarn install && yarn dev  # :3000

# 7. Keycloak 필요 시
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d
```

---

## 인프라 (Docker Compose)

```bash
# 기본 인프라
docker compose -f infra/docker/docker-compose.yml up -d

# Keycloak 포함
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d

# 모니터링 (Prometheus + Grafana + Loki)
docker compose -f infra/docker/docker-compose.yml --profile monitoring up -d

# 개발 도구 (Kafka UI + pgAdmin + Adminer + Redis Insight)
docker compose -f infra/docker/docker-compose.yml --profile tools up -d
```

---

## 접속 URL

| 서비스 | URL | 비고 |
|--------|-----|------|
| Q-Sign | http://localhost:8081 | 인증 SoR |
| Q-IM | http://localhost:8082 | 식별 SoR |
| IdO | http://localhost:8083 | 오케스트레이터 |
| agency-stub | http://localhost:8084 | 기관 시뮬레이터 |
| agency-stub Web UI | http://localhost:8084/ | **E2E 시뮬레이터 브라우저** |
| onepass-fe (개발) | http://localhost:3000 | React SPA |
| onepass-fe (운영) | http://localhost:3001 | Nginx 서빙 |
| Keycloak | http://localhost:8085 | OIDC IdP (admin/admin) |
| Kafka UI | http://localhost:8090 | 토픽·메시지 조회 |
| pgAdmin | http://localhost:5050 | PostgreSQL 관리 |
| Adminer | http://localhost:8091 | MariaDB 관리 |
| Redis Insight | http://localhost:5540 | Redis 관리 |
| Prometheus | http://localhost:9090 | 메트릭 |
| Grafana | http://localhost:3002 | 대시보드 (admin/admin) |

---

## 개발 환경 설정

### 필수 환경변수

```bash
# IdO 암호화 / 서명 키 (운영 교체 필수)
IDO_HANDOFF_AES_KEY=<base64-32bytes>
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>
IDO_INTERNAL_SIG_SECRET=<32bytes+>
IDO_AGENCY_SUBJECT_SECRET=<32bytes+>

# Q-IM CI 암호화
QIM_CI_AES_KEY_V1=<base64-32bytes>
QIM_DI_SECRET=<32bytes+>

# Q-Sign Keycloak
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak Admin에서 발급>

# 공통 인프라
DB_HOST=localhost
DB_PORT=5432
DB_NAME=onepass
REDIS_HOST=localhost
KAFKA_SERVERS=localhost:9092
```

---

## 빌드 & 실행

```bash
# 전체 빌드 (테스트 제외)
./gradlew build -x test

# 특정 모듈 빌드
./gradlew :ido:build -x test

# 특정 모듈 실행
./gradlew :ido:bootRun

# 컴파일 오류만 확인
./gradlew :platform-common:compileJava :ido:compileJava

# 전체 클린 빌드
./gradlew clean build -x test

# Gradle 데몬 종료
./gradlew --stop
```

---

## 현재 구현 완성도

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비)
Q-Sign           ████████████████████  95%  (X-Internal-Sig 수신 검증 P1 미완)
Q-IM             ██████████████████░░  92%  (Selective Pull, auth_mean JPA 미완)
IdO              ████████████████████  99%  (DLQ 연결, Idempotency-Key P1 미완)
agency-stub      ████████████████████  90%  (Docker 격리 P2, mTLS P3)
onepass-fe       ████████████░░░░░░░░  60%  (회원 전환·관리 UI 미구현)
인프라/Docker    ████████████████████ 100%  (전 모듈 Dockerfile + Compose 완비)
보안             ██████████████████░░  93%  (X-Internal-Sig 수신 미완)
테스트           ░░░░░░░░░░░░░░░░░░░░   0%  (단위·통합 테스트 전무)
```

**전체 완성도**: 약 **86%** — PoC → 프리프로덕션 단계

---

## 문서 디렉토리

```
docs/
├── spec/                              # ★ 정밀 분석 기반 기술 명세 (v1.9.3, 신규)
│   ├── 00-index.md                   # 전체 조감도 + 빠른 참조 카드
│   ├── 01-system-overview.md         # 프로젝트 목적·범위·기술 스택
│   ├── 02-architecture.md            # 시스템 아키텍처·인증 흐름·EDA·ADR
│   ├── 03a-module-platform-common.md # 공통 도메인·이벤트·에러코드
│   ├── 03b-module-qsign.md          # Q-Sign 인증 SoR
│   ├── 03c-module-qim.md            # Q-IM 식별 SoR
│   ├── 03d-module-ido.md            # IdO 정책 오케스트레이터
│   ├── 03e-module-agency-stub.md    # agency-stub PoC 시뮬레이터
│   ├── 04-api-reference.md          # REST API 전체 명세
│   ├── 05-database-schema.md        # DB 스키마 전체 (Flyway V1~V10)
│   ├── 06-kafka-event-catalog.md    # Kafka 토픽·이벤트 구조
│   ├── 07-security.md               # 보안 구현 상세
│   ├── 08-infrastructure.md         # Docker Compose·환경변수·로컬 가이드
│   └── 09-gap-and-roadmap.md        # 미구현 현황·Sprint 계획·기술 부채
│
├── development/                       # 개발 진행 상태 문서 (v1.9.0 기준)
│   ├── 01-project-overview.md
│   ├── 02-architecture.md
│   ├── 03-module-ido.md
│   ├── ...
│   ├── 12-implementation-gaps.md     # 미구현 항목 우선순위별 상세
│   └── 13-development-history.md     # v1.0~v1.9.3 버전별 변경 이력
│
├── local-dev-guide.md                 # 로컬 개발 환경 구동 가이드 (v1.2.0)
├── handoff-note.md                    # v1.9.0 인수인계 패키지
├── eda-master-arch-gap-analysis-v0.8.md # EDA 마스터 아키텍처 GAP 분석
└── gap-analysis-v0.8.3-vs-project.md  # GAP 분석 v0.8.3 vs 프로젝트
```

---

## 코딩 컨벤션

- **패키지**: `kr.go.smes.{module}` (예: `kr.go.smes.ido.handoff`)
- **에러코드**: `PlatformErrorCode` 열거형 사용 — 하드코딩 문자열 금지
- **PII 보호**: CI 원문, 개인명 원문, 전화번호 원문 로그 금지
- **감사 로그**: `platform.audit.log` 토픽 사용 — PII 원문 미포함 JSON만 허용
- **Outbox 패턴**: 모든 Kafka 게시는 Transactional Outbox 경유
- **상수시간 비교**: 서명·해시 비교는 `MessageDigest.isEqual()` 사용

---

*GitHub: [HipsterMIN/integration-sso](https://github.com/HipsterMIN/integration-sso)*
