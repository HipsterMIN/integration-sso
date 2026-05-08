# OnePass 통합인증 플랫폼 PoC

중기원패스(OnePass) 통합인증 플랫폼 — Gradle 멀티프로젝트 PoC 구현체입니다.  
**3+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe) 기반 EDA 아키텍처로 구성됩니다.

> **[아키텍처 변경]** `onepass-fe`는 **순수 React SPA** 모듈로 전환되었습니다.  
> 구 Spring Boot BFF 기능(FE 세션 관리, Kafka Advisory 소비, CORS)은 **`ido`** 모듈로 이관되었습니다.

---

## 목차

1. [아키텍처 개요](#아키텍처-개요)
2. [모듈 책임 분리 요약](#모듈-책임-분리-요약)
3. [기술 스택](#기술-스택)
4. [모듈 구성](#모듈-구성)
5. [패키지 규칙](#패키지-규칙)
6. [빠른 시작](#빠른-시작)
7. [인프라 (Docker Compose)](#인프라-docker-compose)
8. [Flyway 마이그레이션](#flyway-마이그레이션)
9. [Kafka 토픽 / 컨슈머 그룹](#kafka-토픽--컨슈머-그룹)
10. [onepass-fe: 순수 React SPA](#onepass-fe-순수-react-spa)
11. [개발 환경 설정](#개발-환경-설정)
12. [빌드 & 실행](#빌드--실행)
13. [접속 URL](#접속-url)
14. [코딩 컨벤션](#코딩-컨벤션)

---

## 아키텍처 개요

> **⚠️ 설계 원칙**: 모든 유관기관(기관 시스템)은 **외부망**에 위치합니다.  
> 기관은 내부 Kafka·DB에 직접 접근하지 않으며, **IdO 공개 API(HTTPS)** 만을 통해 통신합니다.  
> `agency-stub`은 이 외부 기관을 시뮬레이션하는 PoC 전용 컴포넌트입니다.

```
══════════════════════════════════════════════════════════════════════
  외부망 (External Network)
══════════════════════════════════════════════════════════════════════

  ┌──────────────────────────────────┐  ┌──────────────────────────────┐
  │      최종 사용자 (브라우저 / 앱)     │  │    유관기관 시스템 (외부망)       │
  │                                  │  │                              │
  │  [개발] React dev :3000           │  │  agency-stub :8084  ←PoC    │
  │    webpack proxy /api            │  │  (실제 기관 앱을 시뮬레이션)     │
  │  [운영] Nginx :3001               │  │                              │
  │    /api → ido:8083               │  │  ① POST /agency/entry        │
  └──────────┬───────────────────────┘  │     ticketId → IdO Verify API│
             │ HTTPS                   │  ② HTTPS: IdO :8083/handoff  │
             │ /api/v1/fe-session/**   │     verify (API Key 인증)      │
             │ /api/v1/handoff/**      │  ③ AGSID 쿠키 발급 (기관 세션)  │
             │                         └──────────────┬───────────────┘
             │                                        │ HTTPS (공개 API)
══════════════════════════════════════════════════════╪═════════════════
  내부망 (Internal Network — onepass-net 172.20.0.0/24)│
══════════════════════════════════════════════════════╪═════════════════
             │                                        │
             ▼                                        ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  ido  :8083  정책 오케스트레이터 + FE BFF                           │
  │                                                                  │
  │  [사용자향 BFF]                      [기관향 공개 API]               │
  │  - feSessionId 쿠키 발급/갱신/만료    - POST /handoff/issue          │
  │  - returnUrl 화이트리스트 검증        - POST /handoff/verify ◀ 기관  │
  │  - CORS: React SPA (3000/3001)      - DELETE /handoff/{id}        │
  │  - platform.session.advisory 소비   - X-Agency-Code + API Key 인증 │
  │    → FE 세션 무효화                  - Handoff REVOKED 시 Kafka 발행 │
  └──────────────────┬───────────────────────────────────────────────┘
                     │ HTTP (내부망 전용)
         ┌───────────┴───────────┐
         ▼                       ▼
  ┌─────────────┐       ┌─────────────┐
  │ q-sign:8081 │       │  q-im:8082  │
  │  인증 SoR    │       │   식별 SoR   │
  └──────┬──────┘       └──────┬──────┘
         │  Outbox              │  Outbox
         └──────────┬───────────┘
                    ▼
    ┌───────────────────────────────────┐
    │           Apache Kafka            │
    │  qsign.auth.events                │
    │  qim.user.events / snapshot       │
    │  ido.handoff.events      ──────►  │──► [기관은 직접 구독 불가]
    │  platform.session.advisory        │    기관 세션 무효화는 IdO가
    │  platform.audit.log  (+DLQ ×5)   │    Handoff REVOKED 이벤트를
    └─────────────────┬─────────────────┘    발행 → 기관이 Verify 실패로
                      │                       간접 감지 (Push/Webhook P1)
         ┌────────────┼────────────┐
         ▼            ▼            ▼
  PostgreSQL 16    Redis 7.2   MariaDB 11
  (qsign/ido)    (FE세션·캐시)   (qim 전용)
```

### 기관 연동 흐름 요약

```
[유관기관 시스템] ── HTTPS ──► [IdO :8083]
                               │
  ① 인증 완료 후 Handoff         │  /api/v1/handoff/verify
     Ticket 발급 (ido 내부)      │  - X-Agency-Code 헤더
  ② 기관이 ticketId 수신          │  - X-Agency-Key API Key
  ③ 기관 → IdO Verify 호출 ──►  │  - 1회성 Ticket 소비
  ④ IdO → HandoffPayload 반환   │  - agencySubjectId 포함
  ⑤ 기관 로컬 세션(AGSID) 발급    │

  ※ Kafka 직접 구독: 불가 (내부망 격리)
  ※ 세션 무효화 통지: Webhook/Push 방식으로 설계 예정 (§PoC: polling 허용)
```

---

## 모듈 책임 분리 요약

### 변경 전 (구 아키텍처)

| 책임 | 담당 모듈 |
|------|-----------|
| FE 세션 발급/확인/로그아웃 | `onepass-fe` (Spring Boot BFF) |
| `platform.session.advisory` Kafka 소비 | `onepass-fe` (SessionAdvisoryConsumer) |
| returnUrl 화이트리스트 검증 | `onepass-fe` |
| React SPA 정적 서빙 (Option A) | `onepass-fe` (Spring Boot) |
| CORS 설정 (`/api/**`) | `onepass-fe` (WebMvcConfig) |

### 변경 후 (현재 아키텍처)

| 책임 | 담당 모듈 | 비고 |
|------|-----------|------|
| FE 세션 발급/확인/로그아웃 | **`ido`** (`FeSessionController`) | `/api/v1/fe-session/**` |
| `platform.session.advisory` Kafka 소비 | **`ido`** (`FeAdvisoryConsumer`) | 그룹: `ido-fe-advisory-consumer` |
| returnUrl 화이트리스트 검증 | **`ido`** (`FeSessionServiceImpl`) | DB(`ido.fe_return_url_whitelist`) + yml |
| React SPA 정적 서빙 | **Nginx** (Option B) / **ido** static (Option A) | |
| CORS 설정 (`/api/**`) | **`ido`** (`IdoWebMvcConfig`) | Origin: 3000, 3001 |
| React SPA 소스 코드 | **`onepass-fe`** (순수 Node 모듈) | Spring Boot 완전 제거 |

### 4축 책임 모델 (현행)

| 축 | 서비스 | 책임 |
|----|--------|------|
| **Q-Sign** | `q-sign` :8081 | 인증 SoR — IdP 연동, 인증 결과 기록, 잠금 정책 |
| **Q-IM** | `q-im` :8082 | 식별 SoR — QIM 사용자 원장, 인증수단 매핑, Outbox 이벤트 |
| **IdO** | `ido` :8083 | 정책 오케스트레이터 + FE BFF — Handoff, 기관 메타, **FE 세션**, Advisory 소비 |
| **onepass-FE** | `onepass-fe` | 순수 React SPA — UI 로직만 담당, 백엔드 없음 |

---

## 기술 스택

### 백엔드

| 항목 | 버전 |
|------|------|
| JDK | Eclipse Temurin **21** |
| Spring Boot | **3.5.9** |
| Gradle | **9.5.0** |
| Spring Kafka | Spring Boot BOM 관리 |
| Flyway | **11.8.0** |
| Resilience4j | **2.2.0** |
| JJWT | **0.12.6** |
| PostgreSQL Driver | Spring Boot BOM 관리 |
| Lombok / MapStruct | 최신 안정 버전 |

### 프론트엔드 (`onepass-fe/frontend/`)

| 항목 | 버전 |
|------|------|
| React | **18.3** |
| TypeScript | **5.4** |
| Webpack | **5.92** |
| Ant Design | **5.18** |
| React Router | **v6** |
| TanStack Query | **v5** |
| Axios | **1.7** |
| Zustand | **4.5** |
| Node.js (빌드) | **20.14** (LTS) |
| Yarn | **1.22** |

### 인프라

| 서비스 | 이미지 |
|--------|--------|
| PostgreSQL | `postgres:16-alpine` |
| Redis | `redis:7.2-alpine` |
| Kafka | `confluentinc/cp-kafka:7.6.1` |
| Zookeeper | `confluentinc/cp-zookeeper:7.6.1` |
| Nginx | `nginx:1.27-alpine` (React SPA 서빙) |

---

## 모듈 구성

```
onepass-platform/                  ← Gradle 루트
├── platform-common/               # 공통 도메인 · 이벤트 · 에러코드
├── q-sign/                        # 인증 SoR (port 8081)
├── q-im/                          # 식별 SoR (port 8082)
├── ido/                           # 정책 오케스트레이터 + FE BFF (port 8083)
│   └── src/main/java/.../ido/
│       ├── api/                   # HandoffController
│       ├── fe/                    # ★ BFF 이관 코드
│       │   ├── api/               # FeSessionController, FeSessionCreateRequest
│       │   ├── session/           # FeSession, FeSessionService, FeSessionServiceImpl
│       │   ├── kafka/             # FeAdvisoryConsumer (platform.session.advisory)
│       │   └── config/            # IdoWebMvcConfig (CORS)
│       ├── config/                # KafkaConsumerConfig (feAdvisoryListenerFactory 포함)
│       └── ...
├── onepass-fe/                    # ★ 순수 React SPA (Spring Boot 없음)
│   ├── build.gradle.kts           # Node 전용 (com.github.node-gradle.node)
│   ├── Dockerfile.optionA         # React → ido static resource 빌드
│   ├── Dockerfile.optionB         # React → Nginx (2-stage)
│   └── frontend/                  # React SPA (TypeScript / Webpack5)
│       ├── src/api/
│       │   ├── client.ts          # Axios (proxy → ido:8083)
│       │   └── session.ts         # /api/v1/fe-session/** 호출
│       └── webpack.config.js      # devServer proxy: /api → localhost:8083
├── agency-stub/                   # ★PoC 전용: 외부 유관기관 시뮬레이터 (port 8084)
│   # ⚠️ 실 기관은 외부망에 위치 — Kafka 직접 구독 불가 (설계 제약)
└── infra/
    └── docker/
        ├── docker-compose.yml     # onepass-fe Spring Boot 제거, onepass-ido 추가
        ├── nginx/nginx.conf       # upstream: onepass-ido:8083
        └── ...
```

---

## 패키지 규칙

모든 Java 소스의 루트 패키지는 **`kr.go.smes`** 입니다.

| 모듈 | 패키지 |
|------|--------|
| platform-common | `kr.go.smes.common` |
| q-sign | `kr.go.smes.qsign` |
| q-im | `kr.go.smes.qim` |
| ido (기존) | `kr.go.smes.ido` |
| ido (FE BFF 이관) | `kr.go.smes.ido.fe` |
| agency-stub | `kr.go.smes.agency` |

> `kr.go.smes.fe` 패키지(구 `onepass-fe` BFF)는 완전 제거됨.

---

## 빠른 시작

### 사전 요구사항

- **JDK 21** (Eclipse Temurin 권장)
- **Docker 24+** / Docker Compose v2
- **Node.js 20 LTS** + **Yarn 1.22** (프론트엔드 개발 시)

### 1. 인프라 기동

```bash
# 기본 인프라 (PostgreSQL · Redis · Kafka · Zookeeper · Kafka-init)
docker compose -f infra/docker/docker-compose.yml up -d

# 모니터링 UI 포함
docker compose -f infra/docker/docker-compose.yml \
  --profile tools up -d
```

### 2. 전체 Java 빌드

```bash
./gradlew build -x test
# ※ onepass-fe 는 Node 모듈이므로 별도 yarn install 필요
```

### 3. 서비스 기동 (로컬 개발)

```bash
# Q-Sign (인증 SoR)
./gradlew :q-sign:bootRun

# Q-IM (식별 SoR)
./gradlew :q-im:bootRun

# IdO (정책 오케스트레이터 + FE BFF)
./gradlew :ido:bootRun

# agency-stub (외부 유관기관 시뮬레이터 — PoC 전용)
# ⚠️ 실 기관은 IdO HTTPS API 만 사용 (Kafka 직접 구독 불가)
./gradlew :agency-stub:bootRun

# React 개발서버 (별도 터미널, proxy → ido:8083)
cd onepass-fe/frontend && yarn install && yarn dev
# → http://localhost:3000
```

---

## 인프라 (Docker Compose)

### 서비스 목록

| 컨테이너 | 이미지 | 포트 | IP | Profile |
|----------|--------|------|----|---------| 
| `onepass-postgres` | postgres:16-alpine | **5432** | 172.20.0.10 | 기본 |
| `onepass-redis` | redis:7.2-alpine | **6379** | 172.20.0.11 | 기본 |
| `onepass-zookeeper` | cp-zookeeper:7.6.1 | **2181** | 172.20.0.12 | 기본 |
| `onepass-kafka` | cp-kafka:7.6.1 | **9092** | 172.20.0.13 | 기본 |
| `onepass-kafka-init` | cp-kafka:7.6.1 | — | — | 기본 (one-shot) |
| `onepass-kafka-ui` | kafka-ui:latest | **8090** | 172.20.0.15 | 기본 |
| `onepass-redis-insight` | redisinsight:latest | **5540** | 172.20.0.16 | 기본 |
| `onepass-schema-registry` | cp-schema-registry:7.6.1 | **8085** | 172.20.0.14 | `schema` |
| `onepass-pgadmin` | pgadmin4:latest | **5050** | 172.20.0.17 | `tools` |
| `onepass-ido` ★ | onepass-ido:latest | **8083** | 172.20.0.19 | `app` |
| `onepass-react` ★ | onepass-react:latest | **3001** | 172.20.0.20 | `optionB` |

> ★ `onepass-fe` Spring Boot 컨테이너는 제거됨. `onepass-ido` 가 BFF 역할 수행.

> ⚠️ **agency-stub 미포함**: `agency-stub`은 **외부 유관기관을 시뮬레이션**하므로 내부망(`onepass-net`)에 포함되지 않습니다.  
> PoC 로컬 실행 시 `localhost:8084`로 별도 기동하여 IdO `localhost:8083` 공개 API를 HTTPS로 호출합니다.  
> (실 운영에서 기관 시스템은 완전히 독립된 외부망 환경에서 운영됩니다.)

### Docker Compose 프로파일

```bash
# 기본 인프라만
docker compose -f infra/docker/docker-compose.yml up -d

# 앱 스택 (ido BFF + React Nginx)
docker compose -f infra/docker/docker-compose.yml \
  --profile app --profile optionB up -d

# 전체 (인프라 + 앱 + 모니터링)
docker compose -f infra/docker/docker-compose.yml \
  --profile app --profile optionB --profile tools up -d

# 스키마 레지스트리 포함
docker compose -f infra/docker/docker-compose.yml --profile schema up -d
```

### ido 환경변수 (Docker)

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_HOST` | `postgres` | PostgreSQL 호스트 |
| `REDIS_HOST` | `redis` | Redis 호스트 |
| `KAFKA_SERVERS` | `kafka:29092` | Kafka 브로커 |
| `QIM_BASE_URL` | `http://onepass-qim:8082` | Q-IM 서비스 URL |
| `QSIGN_BASE_URL` | `http://onepass-qsign:8081` | Q-Sign 서비스 URL |
| `CORS_ORIGIN_DEV` | `http://localhost:3000` | React dev server Origin |
| `CORS_ORIGIN_PROD` | `http://localhost:3001` | Nginx React Origin |

---

## Flyway 마이그레이션

각 서비스 `src/main/resources/db/migration/` 하위에 위치합니다.

| 모듈 | 파일 | 주요 테이블 |
|------|------|------------|
| **q-sign** | `V1__create_schema.sql` | `idp_provider`, `auth_result`, `auth_lock`, `outbox` |
| **q-sign** | `V2__add_audit_log.sql` | `auth_audit_log`, `lock_event_log`, `used_nonce` (리플레이 방어) |
| **q-im** | `V1__create_schema.sql` | `qim_user`, `auth_mean_mapping`, `user_profile`, `user_status_history`, `outbox` |
| **q-im** | `V2__add_idempotent_consumer.sql` | `last_event_version`, `processed_event`, `snapshot_meta` |
| **ido** | `V1__create_schema.sql` | `agency_meta`, `handoff_audit`, `policy_conflict_log`, `outbox` + 시드 데이터 |
| **ido** | `V2__add_fe_session.sql` ★ | `fe_session_audit`, `fe_return_url_whitelist` + PoC 화이트리스트 시드 |
| **agency-stub** | `V1__create_schema.sql` | `agency_user`, `agency_permission`, `agency_local_session`, `session_event_log` |

> ★ `V2__add_fe_session.sql`: onepass-fe BFF 이관으로 추가된 FE 세션 감사 및 returnUrl 화이트리스트 테이블.

### 스키마 분리

```
PostgreSQL DB: onepass  (내부망 — 172.20.0.10)
├── qsign.*        (q-sign 전용)
├── ido.*          (ido 전용 — fe_session_audit, fe_return_url_whitelist 포함)
└── agency_stub.*  (agency-stub PoC 전용 — PoC 한정, 실 기관은 자체 DB 보유)

MariaDB DB: qim  (내부망 — 172.20.0.21)
└── qim.*          (q-im 전용 — 운영: NHN Cloud RDS for MariaDB)
```

> ⚠️ **agency-stub DB 스키마** (`agency_stub.*`): PoC 시뮬레이션 전용.  
> 실 유관기관은 자신의 망 내 독립 DB를 사용하며, OnePass 내부 PostgreSQL에 접근하지 않습니다.

---

## Kafka 토픽 / 컨슈머 그룹

### 토픽 목록 (11개, DLQ 포함)

| 토픽 | 파티션 | Cleanup | 보관 기간 | 생산자 |
|------|--------|---------|----------|--------|
| `qsign.auth.events` | 6 | delete | **1년** | q-sign |
| `qim.user.events` | 12 | **compact** | 무기한 | q-im |
| `qim.user.snapshot` | 12 | **compact** | 무기한 | q-im |
| `ido.handoff.events` | 6 | delete | **1년** | ido |
| `platform.session.advisory` | 6 | delete | **24시간** | ido |
| `platform.audit.log` | 6 | delete | **2년** | 전 서비스 |
| `*.DLQ` (×5) | 3 | delete | **7일** | Error Handler |

### 컨슈머 그룹 (현행 — BFF 이관 후)

| 그룹 ID | 구독 토픽 | 모듈 | 위치 | 처리 내용 |
|---------|----------|------|------|-----------|
| `ido-qim-consumer` | `qim.user.events` | `ido` | 내부망 | 버전 검사 + 캐시 무효화 (Ordered Consumer) |
| `ido-qsign-consumer` | `qsign.auth.events` | `ido` | 내부망 | 인증 결과 처리 |
| `ido-fe-advisory-consumer` ★ | `platform.session.advisory` | `ido` | 내부망 | FE 세션 즉시 무효화 / Advisory 플래그 |
| `agency-stub-consumer-handoff` ⚠️ | `ido.handoff.events` | `agency-stub` | **PoC 한정** | REVOKED → 기관 세션 무효화 |
| `agency-stub-consumer-advisory` ⚠️ | `platform.session.advisory` | `agency-stub` | **PoC 한정** | qimUserId 기준 일괄 무효화 |

> ★ 구 `onepass-fe-consumer`(onepass-fe BFF) → `ido-fe-advisory-consumer`(ido) 로 이관.

> ⚠️ **PoC 한정 설계 주의**: `agency-stub`의 Kafka 직접 구독은 **PoC 시뮬레이션 전용**입니다.  
> 실 운영에서 유관기관은 내부 Kafka에 직접 접근할 수 없습니다.  
> 실 운영 기관 세션 무효화 연동 방식은 **Webhook Push** 또는 **Polling API** 로 설계합니다.  
> (상세: [agency-external-arch-supplement.md](docs/agency-external-arch-supplement.md))

### Outbox 패턴

| 서비스 | 방식 |
|--------|------|
| Q-Sign | `FOR UPDATE SKIP LOCKED` 배치 읽기 → 비동기 Kafka publish |
| Q-IM | `KafkaTransactionManager` (Exactly-once) |
| IdO | `ON CONFLICT DO NOTHING` 멱등 Insert |

---

## onepass-fe: 순수 React SPA

`onepass-fe`는 **Spring Boot가 없는 순수 React(TypeScript) 모듈**입니다.  
모든 백엔드 기능은 `ido`(port 8083)에서 제공합니다.

### 개발 구조

```
onepass-fe/
├── build.gradle.kts          # Node Gradle Plugin 전용 (Spring Boot 의존성 없음)
├── Dockerfile.optionA        # React → ido static resource 포함 단일 JAR
├── Dockerfile.optionB        # React → Nginx (2-stage: node:20 → nginx:1.27)
└── frontend/
    ├── package.json           React 18 / TypeScript / Webpack5 / Ant Design 5
    ├── webpack.config.js      devServer proxy: /api → ido:8083 ★
    ├── tsconfig.json          path alias (@/, @pages/, @api/ 등)
    └── src/
        ├── index.tsx          React 진입점 (QueryClient + BrowserRouter)
        ├── App.tsx             lazy 라우팅 (/login · /conversion/* · /error)
        ├── api/
        │   ├── client.ts      axios (proxy → /api → ido:8083) ★
        │   └── session.ts     /api/v1/fe-session/** 호출 ★ (구: /api/v1/session)
        ├── pages/
        │   ├── Login/         세션 체크 + 인증수단 선택 UI
        │   ├── Conversion/    7단계 인증 전환 흐름
        │   └── Error/         에러 코드별 메시지
        ├── types/index.ts     AuthLevel · SessionCheckResponse 등
        └── styles/global.scss
```

### API 엔드포인트 변경

| 구분 | 구 경로 (onepass-fe BFF) | 신 경로 (ido BFF) |
|------|--------------------------|-------------------|
| 세션 확인 | `GET /api/v1/session/check` | `GET /api/v1/fe-session/check` |
| 세션 로그아웃 | `POST /api/v1/session/logout` | `POST /api/v1/fe-session/logout` |
| 세션 발급 | (내부) | `POST /api/v1/fe-session` |

### Option A — React + ido 단일 JAR

React 빌드 산출물을 **ido** 정적 리소스에 포함시켜 하나의 컨테이너로 배포합니다.

```bash
# Docker 이미지 빌드 (3-stage: node → jdk21 → jre21, ido 기반)
docker build -f onepass-fe/Dockerfile.optionA \
  -t onepass-ido:latest .

docker run -p 8083:8083 onepass-ido:latest
# → http://localhost:8083 (React + BFF 단일 서버)
```

### Option B — React 독립 서버 + Nginx (권장: 개발)

```
[개발]
  브라우저 → React dev-server :3000
              ↓ /api/** (webpack proxy)
           ido :8083  (BFF)

[운영]
  브라우저 → Nginx :3001 → /api/** → ido :8083
              ↓ 정적 파일 (SPA fallback)
           React build dist/
```

```bash
# 개발
cd onepass-fe/frontend && yarn install && yarn dev
# → http://localhost:3000

# 프로덕션 Docker
docker build -f onepass-fe/Dockerfile.optionB \
  -t onepass-react:latest .
docker run -p 3001:80 onepass-react:latest
# → http://localhost:3001
```

### Gradle 태스크

| 태스크 | 명령어 | 설명 |
|--------|--------|------|
| 의존성 설치 | `./gradlew :onepass-fe:yarnInstall` | `yarn install` |
| 프로덕션 빌드 | `./gradlew :onepass-fe:build` | `yarn build:prod` → `dist/` |
| 개발서버 기동 | `./gradlew :onepass-fe:frontendDev` | `yarn dev` (port 3000) |
| Lint | `./gradlew :onepass-fe:lint` | ESLint |
| 테스트 | `./gradlew :onepass-fe:test` | Jest |

### React 스크립트

| 명령어 | 설명 |
|--------|------|
| `yarn dev` | 개발 서버 (port 3000, HMR, proxy → ido:8083) |
| `yarn build` | 프로덕션 빌드 (`dist/`) |
| `yarn build:dev` | 개발 환경 빌드 |
| `yarn build:prod` | 운영 환경 빌드 |
| `yarn lint` | ESLint 검사 |
| `yarn lint:fix` | ESLint 자동 수정 |
| `yarn test` | Jest 테스트 |
| `yarn jest:watch` | Jest watch 모드 |
| `yarn prettify` | Prettier 포맷팅 |

---

## ido: FE BFF 이관 코드 상세

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `FeSessionController` | `kr.go.smes.ido.fe.api` | `GET /check`, `POST /logout`, `POST /` (세션 발급) |
| `FeSessionCreateRequest` | `kr.go.smes.ido.fe.api` | 세션 발급 DTO |
| `FeSession` | `kr.go.smes.ido.fe.session` | FE 세션 객체 (Redis 저장 + advisoryFlag) |
| `FeSessionService` | `kr.go.smes.ido.fe.session` | FE 세션 CRUD 인터페이스 |
| `FeSessionServiceImpl` | `kr.go.smes.ido.fe.session` | Redis 구현체 (sliding TTL, 사용자별 세션 역인덱스) |
| `FeAdvisoryConsumer` | `kr.go.smes.ido.fe.kafka` | `platform.session.advisory` Kafka 소비 |
| `IdoWebMvcConfig` | `kr.go.smes.ido.fe.config` | CORS 설정 (`/api/v1/fe-session/**`, `/api/v1/handoff/**`) |

### Redis 키 구조 (FE 세션)

```
fe:session:{feSessionId}        → FeSession 객체 (sliding TTL 30분)
fe:user-sessions:{qimUserId}    → Set<feSessionId> (사용자별 역인덱스)
```

### ido application.yml 추가 설정

```yaml
ido:
  fe:
    session:
      sliding-ttl-minutes: 30         # §12.4 Sliding TTL
      absolute-timeout-minutes: 480   # §12.4 절대 만료 (8h)
    allowed-return-urls:              # §12.6 returnUrl 화이트리스트
      - https://agency-a.example.com
      - https://agency-b.example.com
      - http://localhost:8084          # agency-stub
      - http://localhost:3000          # React dev (개발)
      - http://localhost:3001          # Nginx React (운영)
  cors:
    enabled: true
    allowed-origins:
      - ${CORS_ORIGIN_DEV:http://localhost:3000}
      - ${CORS_ORIGIN_PROD:http://localhost:3001}
  kafka:
    consumer-group-fe-advisory: ido-fe-advisory-consumer
```

---

## 개발 환경 설정

### 환경변수 (ido — 로컬)

| 환경변수 | 기본값 | 설명 |
|----------|--------|------|
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `KAFKA_SERVERS` | `localhost:9092` | Kafka 브로커 |
| `DB_USERNAME` | `onepass` | PostgreSQL 사용자 |
| `DB_PASSWORD` | `onepass` | PostgreSQL 비밀번호 |
| `QIM_BASE_URL` | `http://localhost:8082` | Q-IM 서비스 URL |
| `QSIGN_BASE_URL` | `http://localhost:8081` | Q-Sign 서비스 URL |
| `CORS_ORIGIN_DEV` | `http://localhost:3000` | React dev server Origin |
| `CORS_ORIGIN_PROD` | `http://localhost:3001` | Nginx React Origin |

---

## 빌드 & 실행

### 전체 Java 빌드

```bash
# 테스트 제외 전체 빌드 (onepass-fe 는 Node 모듈이므로 별도)
./gradlew :platform-common:build :q-sign:build :q-im:build \
          :ido:build :agency-stub:build -x test

# onepass-fe React 빌드
./gradlew :onepass-fe:build   # yarn build:prod → dist/
```

### 개별 모듈 실행

```bash
./gradlew :platform-common:build    # 공통 라이브러리 (JAR만)
./gradlew :q-sign:bootRun           # 인증 SoR           :8081
./gradlew :q-im:bootRun             # 식별 SoR           :8082
./gradlew :ido:bootRun              # 정책 오케스트레이터+BFF :8083
./gradlew :agency-stub:bootRun      # 기관 스텁           :8084

# React 개발서버 (별도 터미널)
./gradlew :onepass-fe:frontendDev   # port 3000, proxy→ido:8083
# 또는
cd onepass-fe/frontend && yarn dev
```

### Gradle 주요 프로퍼티

| 프로퍼티 | 효과 |
|----------|------|
| `-x test` | 테스트 스킵 |
| `--no-daemon` | Gradle 데몬 비활성 |

---

## 접속 URL

| URL | 용도 | 비고 |
|-----|------|------|
| http://localhost:3000 | React HMR 개발서버 | webpack proxy → ido:8083 |
| http://localhost:3001 | Nginx React 빌드 | Option B 운영, proxy → ido:8083 |
| ~~http://localhost:8080~~ | ~~onepass-fe BFF~~ | **제거됨** |
| http://localhost:8081 | q-sign API | |
| http://localhost:8082 | q-im API | |
| **http://localhost:8083** | **ido API + FE BFF** | `/api/v1/fe-session/**` 포함 |
| http://localhost:8084 | agency-stub | ⚠️ 외부 기관 시뮬레이터 (PoC 전용, 내부망 외부에 위치) |
| http://localhost:8090 | Kafka UI | admin / admin |
| http://localhost:5540 | Redis Insight | |
| http://localhost:5050 | pgAdmin 4 | admin@onepass.local / admin (`--profile tools`) |
| http://localhost:8085 | Schema Registry | (`--profile schema`) |

---

## 코딩 컨벤션

### Java / Kotlin

- **패키지 루트**: `kr.go.smes.<모듈>`
- **클래스**: `PascalCase`
- **메서드 / 변수**: `camelCase`
- **상수**: `UPPER_SNAKE_CASE`
- **Lombok** 적극 활용 (`@Getter`, `@Builder`, `@RequiredArgsConstructor`)
- **커밋 메시지**: [Conventional Commits](https://www.conventionalcommits.org/) (`feat`, `fix`, `chore`, `refactor`, `docs`)

### TypeScript / React

- **인터페이스**: `PascalCase`
- **함수명**: `camelCase`
- **상수**: `UPPER_SNAKE_CASE`
- **컴포넌트 파일**: `PascalCase.tsx`
- **CSS 클래스명**: `camelCase`
- **Path alias**: `@/`, `@pages/`, `@api/`, `@components/` 등

### Git 브랜치 전략

| 브랜치 | 용도 |
|--------|------|
| `main` | 안정 릴리즈 |
| `develop` | 개발 작업 브랜치 |

---

## 라이선스

PoC 내부 개발용 프로젝트입니다.
