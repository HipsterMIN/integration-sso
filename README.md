# OnePass 통합인증 플랫폼 PoC

중기원패스(OnePass) 통합인증 플랫폼 — Gradle 멀티프로젝트 PoC 구현체입니다.  
**4+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe · agency-stub) 기반 EDA 아키텍처로 구성됩니다.

> **최신 버전: v1.7.0** — 실제 유관기관 완전 클라이언트 구성  
> [`PR #19`](https://github.com/HipsterMIN/integration-sso/pull/19) — E2E 시뮬레이터 + IdO X-Agency-Key 검증 인터셉터 + 테스트 Web UI

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [아키텍처 개요](#아키텍처-개요)
3. [모듈 책임 분리 요약](#모듈-책임-분리-요약)
4. [기술 스택](#기술-스택)
5. [모듈 구성](#모듈-구성)
6. [agency-stub: 유관기관 OIDC 클라이언트 스텁](#agency-stub-유관기관-oidc-클라이언트-스텁)
7. [유관기관 E2E 연동 흐름](#유관기관-e2e-연동-흐름)
8. [보안 체크리스트](#보안-체크리스트)
9. [Flyway 마이그레이션](#flyway-마이그레이션)
10. [Kafka 토픽 / 컨슈머 그룹](#kafka-토픽--컨슈머-그룹)
11. [빠른 시작](#빠른-시작)
12. [인프라 (Docker Compose)](#인프라-docker-compose)
13. [접속 URL](#접속-url)
14. [onepass-fe: 순수 React SPA](#onepass-fe-순수-react-spa)
15. [개발 환경 설정](#개발-환경-설정)
16. [빌드 & 실행](#빌드--실행)
17. [코딩 컨벤션](#코딩-컨벤션)

---

## 버전 히스토리

| 버전 | PR | 주요 내용 |
|------|----|-----------|
| **v1.7.0** | [#19](https://github.com/HipsterMIN/integration-sso/pull/19) | **실제 유관기관 완전 클라이언트** — `HandoffAgencyKeyInterceptor`(X-Agency-Key SHA-256 DB 검증), `IdoTicketClient`(Ticket 발급 HTTP 클라이언트), `AgencySimulatorController`(E2E 시뮬레이터), `AgencyHealthController`(진단 API), `AgencyDataInitializer`(기동 초기화), V8 마이그레이션, 테스트 Web UI |
| v1.6.0 | [#18](https://github.com/HipsterMIN/integration-sso/pull/18) | agency-stub OIDC 클라이언트 완전 구현 — Webhook 수신(HMAC-SHA256), `IdoVerifyClient`(CB+Retry), 세션 관리, V2 마이그레이션 |
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
  │  [개발] React dev :3000           │  │  agency-stub :8084  ← PoC       │
  │    webpack proxy /api → :8083    │  │  (실제 기관 앱을 시뮬레이션)         │
  │  [운영] Nginx :3001               │  │                                  │
  │    /api → ido:8083               │  │  ① POST /api/v1/simulator/run    │
  └──────────┬───────────────────────┘  │     IdoTicketClient.issue()      │
             │ HTTPS                    │     → ticketId 발급              │
             │ /api/v1/fe-session/**    │  ② IdoVerifyClient.verify()      │
             │ /api/v1/handoff/**       │     → HandoffPayload (APPROVED)  │
             │                          │  ③ AgencySessionService          │
             │                          │     → AGSID 쿠키 발급             │
             │                          └──────────────┬───────────────────┘
             │                                         │ HTTPS (공개 API만)
══════════════════════════════════════════════════════╪══════════════════════
  내부망 (Internal Network — onepass-net 172.20.0.0/24)│
══════════════════════════════════════════════════════╪══════════════════════
             │                                         │
             ▼                                         ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │  ido  :8083  정책 오케스트레이터 + FE BFF                              │
  │                                                                     │
  │  [사용자향 BFF]                      [기관향 공개 API]                  │
  │  - feSessionId 쿠키 발급/갱신/만료    - POST /api/v1/handoff/issue     │
  │  - returnUrl 화이트리스트 검증        - POST /api/v1/handoff/verify    │
  │  - CORS: React SPA (3000/3001)      ↑ HandoffAgencyKeyInterceptor   │
  │  - platform.session.advisory 소비     X-Agency-Key SHA-256 DB검증    │
  │    → FE 세션 무효화                    (issue + verify 양 경로 적용)   │
  │                                     - Webhook Push 발송 (HMAC-SHA256)│
  │                                     - Outbox Relay (재시도 보장)      │
  └──────────────────┬──────────────────────────────────────────────────┘
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
    │  ido.handoff.events               │──► [기관 직접 구독 불가]
    │  platform.session.advisory        │    세션 무효화 통지:
    │  platform.audit.log  (+DLQ ×5)   │    Webhook Push 또는
    └─────────────────┬─────────────────┘    Polling API 사용
         ┌────────────┼────────────┐
         ▼            ▼            ▼
  PostgreSQL 16    Redis 7.2   MariaDB 11
  (qsign/ido)    (FE세션·캐시)   (qim 전용)
```

---

## 모듈 책임 분리 요약

### 4+1 축 책임 모델 (현행)

| 축 | 서비스 | 포트 | 책임 |
|----|--------|------|------|
| **Q-Sign** | `q-sign` | 8081 | 인증 SoR — IdP 연동, 인증 결과 기록, 잠금 정책 |
| **Q-IM** | `q-im` | 8082 | 식별 SoR — QIM 사용자 원장, 인증수단 매핑, Outbox 이벤트 |
| **IdO** | `ido` | 8083 | 정책 오케스트레이터 + FE BFF — Handoff, 기관 메타, FE 세션, Advisory 소비, **X-Agency-Key 검증**, Webhook Push |
| **onepass-FE** | `onepass-fe` | 3000/3001 | 순수 React SPA — UI 로직만, Spring Boot 없음 |
| **agency-stub** ⊕ | `agency-stub` | 8084 | **유관기관 OIDC 클라이언트 스텁** — Ticket 발급/검증/세션, Webhook 수신, E2E 시뮬레이터 |

> ⊕ `agency-stub`은 외부망 유관기관을 시뮬레이션하는 PoC 전용 모듈입니다.

### ido 모듈 역할 분리

| 책임 | 담당 클래스 | 경로 |
|------|------------|------|
| FE 세션 발급/확인/로그아웃 | `FeSessionController` | `/api/v1/fe-session/**` |
| platform.session.advisory 소비 | `FeAdvisoryConsumer` | Kafka 그룹: `ido-fe-advisory-consumer` |
| **X-Agency-Key 검증** | **`HandoffAgencyKeyInterceptor`** | `/api/v1/handoff/issue`, `/api/v1/handoff/verify` |
| Handoff Ticket 발급/검증/취소 | `HandoffController` | `/api/v1/handoff/**` |
| Webhook 발송 (기관 → Push) | `WebhookDispatcherService` + `WebhookDispatchOutboxRelay` | 스케줄링 |
| CORS 설정 | `IdoWebMvcConfig` | Origin: 3000, 3001 |

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
| Lombok | 최신 안정 버전 |

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
onepass-platform/                        ← Gradle 루트
├── platform-common/                     # 공통 도메인 · 이벤트 · 에러코드
│   └── src/main/java/.../common/
│       ├── domain/                      # HandoffPayload, HandoffTicket, AuthResult
│       ├── event/                       # HandoffEvent, SessionAdvisoryEvent, WebhookDispatchEvent
│       └── util/                        # CorrelationIdHolder
│
├── q-sign/                              # 인증 SoR (port 8081)
│
├── q-im/                                # 식별 SoR (port 8082)
│
├── ido/                                 # 정책 오케스트레이터 + FE BFF (port 8083)
│   └── src/main/java/.../ido/
│       ├── api/                         # HandoffController (issue/verify/revoke)
│       │                                #   + GlobalExceptionHandler
│       ├── config/
│       │   ├── HandoffAgencyKeyInterceptor.java  ★ v1.7.0 신규
│       │   │   # /api/v1/handoff/issue + /verify 양 경로에 적용
│       │   │   # SHA-256(rawKey) vs agency_meta.api_key_hash
│       │   │   # MessageDigest.isEqual() 상수시간 비교
│       │   │   # 성공 시 validatedAgencyCode 를 Request Attribute 에 저장
│       │   └── IdoWebConfig.java        # RestTemplate(3종), ObjectMapper, Scheduler
│       ├── fe/                          # FE BFF
│       │   ├── api/                     # FeSessionController
│       │   ├── session/                 # FeSessionService, FeSessionServiceImpl
│       │   ├── kafka/                   # FeAdvisoryConsumer
│       │   └── config/                  # IdoWebMvcConfig (CORS + 인터셉터 등록)
│       ├── handoff/                     # HandoffService, HandoffServiceImpl
│       │                                #   AES-256-GCM 암호화, HMAC-SHA256 서명
│       │                                #   Idempotency-Key Redis TTL 1일
│       ├── webhook/                     # WebhookDispatcherService
│       │                                # WebhookDispatchOutboxRelay (스케줄 재시도)
│       ├── infrastructure/              # AgencyMetaRepository, TicketRepository, QimClient
│       └── db/migration/
│           ├── V1__create_schema.sql
│           ├── V2__add_fe_session.sql
│           ├── V3__add_keycloak_auth.sql
│           ├── V4__add_qim_sp_receiver.sql
│           ├── V5__add_processed_event.sql
│           ├── V6__add_broker_audit_log.sql
│           ├── V7__add_webhook_and_audit.sql
│           └── V8__seed_agency_api_key_and_fix_webhook.sql  ★ v1.7.0 신규
│
├── agency-stub/                         ★ 유관기관 OIDC 클라이언트 스텁 (port 8084)
│   └── src/main/java/.../agency/
│       ├── api/
│       │   ├── AgencyEntryController.java       # POST  /agency/entry
│       │   │                                    # GET   /agency/entry/session
│       │   │                                    # DELETE /agency/entry/session
│       │   ├── AgencyEventPollingController.java # GET  /api/v1/events/poll
│       │   │                                    # GET  /api/v1/events/pending-count
│       │   └── AgencyHealthController.java       # GET  /api/v1/health/**  ★ v1.7.0
│       ├── client/
│       │   ├── IdoVerifyClient.java              # POST /api/v1/handoff/verify (CB+Retry)
│       │   └── IdoTicketClient.java              # POST /api/v1/handoff/issue  ★ v1.7.0
│       │                                         #   CB: ido-ticket / Retry: ido-ticket
│       ├── config/
│       │   ├── AgencyApiKeyInterceptor.java      # 자체 X-Agency-Key 검증 (기관 자체)
│       │   └── AgencyWebConfig.java              # RestTemplate, ObjectMapper
│       ├── init/
│       │   └── AgencyDataInitializer.java        # 기동 시 API Key 시드 + 스키마 확인 ★ v1.7.0
│       ├── kafka/
│       │   ├── HandoffEventConsumer.java         # PoC 전용 Kafka 소비
│       │   └── KafkaConsumerConfig.java
│       ├── session/
│       │   ├── AgencyLocalSession.java
│       │   └── AgencySessionService.java         # 세션 생성/조회/갱신/무효화
│       ├── simulator/
│       │   └── AgencySimulatorController.java    # E2E 시뮬레이터 ★ v1.7.0
│       │                                         #   POST /api/v1/simulator/run
│       │                                         #   POST /api/v1/simulator/ticket
│       │                                         #   POST /api/v1/simulator/verify
│       │                                         #   GET  /api/v1/simulator/status
│       │                                         #   GET  /api/v1/simulator/sessions
│       │                                         #   DELETE /api/v1/simulator/sessions/{id}
│       │                                         #   GET  /api/v1/simulator/events
│       └── webhook/
│           └── WebhookInboundController.java     # POST /api/v1/webhook/inbound
│                                                 #   HMAC-SHA256 서명 검증 + 이벤트 라우팅
│
├── agency-stub/src/main/resources/
│   ├── application.yml
│   ├── static/index.html                ★ v1.7.0 — 브라우저 시뮬레이터 Web UI
│   └── db/migration/
│       ├── V1__create_schema.sql
│       └── V2__add_webhook_and_api_key.sql
│
├── onepass-fe/                          # 순수 React SPA (Spring Boot 없음)
│   ├── Dockerfile.optionA               # React → ido static (단일 JAR)
│   ├── Dockerfile.optionB               # React → Nginx (2-stage)
│   └── frontend/                        # React 18 / TypeScript / Webpack5
│
└── infra/
    └── docker/
        ├── docker-compose.yml           # 전체 인프라 + agency-stub 서비스 포함
        └── nginx/nginx.conf
```

---

## agency-stub: 유관기관 OIDC 클라이언트 스텁

`agency-stub`은 **실제 유관기관이 IdO와 연동하는 전체 흐름을 재현**하는 PoC 전용 Spring Boot 서비스입니다.  
v1.7.0 기준으로 Ticket 발급부터 세션 발급까지의 완전한 E2E 흐름을 단일 API 호출로 시뮬레이션합니다.

### 구현된 기능 전체 목록 (v1.7.0 기준)

| 기능 | 클래스 | 버전 | 설명 |
|------|--------|------|------|
| **Ticket 발급 클라이언트** | `IdoTicketClient` | ★ v1.7.0 | POST /api/v1/handoff/issue, CircuitBreaker+Retry(ido-ticket) |
| **Ticket 검증 클라이언트** | `IdoVerifyClient` | v1.6.0 | POST /api/v1/handoff/verify, CircuitBreaker+Retry(ido-verify) |
| **기관 진입 흐름** | `AgencyEntryController` | v1.6.0 | Verify → 세션 생성 → AGSID 쿠키 발급 |
| **세션 관리** | `AgencySessionService` | v1.6.0 | 생성/조회/갱신/무효화, SHA-256 저장, Session Fixation 방지 |
| **Webhook 수신** | `WebhookInboundController` | v1.5.0 | HMAC-SHA256 서명 검증, ±5분 타임스탬프, 이벤트 라우팅 |
| **이벤트 폴링** | `AgencyEventPollingController` | v1.5.0 | 우선순위 기반 Pull 배달 |
| **E2E 시뮬레이터** | `AgencySimulatorController` | ★ v1.7.0 | 3단계 E2E + 단독 Ticket/Verify + 세션/이벤트 조회 |
| **헬스 진단** | `AgencyHealthController` | ★ v1.7.0 | DB/IdO/Webhook/Queue 전체 상태 조회 |
| **기동 초기화** | `AgencyDataInitializer` | ★ v1.7.0 | API Key 자동 시드, 스키마 테이블 존재 확인 |
| **테스트 Web UI** | `static/index.html` | ★ v1.7.0 | 브라우저 기반 E2E 시뮬레이터 화면 |

### 전체 API 엔드포인트 목록

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  E2E 시뮬레이터 (/api/v1/simulator/**)         ★ v1.7.0
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST   /api/v1/simulator/run               # 3단계 E2E 전체 흐름
POST   /api/v1/simulator/ticket            # Step 1 단독 (Ticket 발급)
POST   /api/v1/simulator/verify?ticketId=  # Step 2 단독 (Ticket 검증)
GET    /api/v1/simulator/status            # DB + IdO 연결 상태 + 세션 통계
GET    /api/v1/simulator/sessions          # 최근 세션 목록
DELETE /api/v1/simulator/sessions/{id}     # 세션 강제 무효화
GET    /api/v1/simulator/events            # 이벤트 큐 조회

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  헬스 / 진단 (/api/v1/health/**)               ★ v1.7.0
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GET    /api/v1/health                      # 전체 요약 (UP / DEGRADED / DOWN)
GET    /api/v1/health/db                   # DB 연결 + 테이블별 레코드 수
GET    /api/v1/health/ido                  # IdO actuator/health + 응답시간
GET    /api/v1/health/webhook              # 최근 1h Webhook 수신 통계
GET    /api/v1/health/queue                # 이벤트 큐 미배달 현황

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  기관 진입 흐름 (/agency/entry/**)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST   /agency/entry?ticketId=             # Verify → 세션 → AGSID 쿠키 발급
GET    /agency/entry/session               # 세션 유효성 확인 + Sliding 연장
DELETE /agency/entry/session               # 로그아웃 (세션 무효화)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Webhook 수신 · 이벤트 폴링
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST   /api/v1/webhook/inbound             # IdO → 기관 Push 수신 (HMAC 검증)
GET    /api/v1/events/poll                 # 미배달 이벤트 조회 (Pull)
GET    /api/v1/events/pending-count        # 미배달 이벤트 수 확인
```

### 테스트 Web UI

agency-stub 기동 후 브라우저에서 바로 접속할 수 있는 시뮬레이터 UI를 제공합니다.

```
http://localhost:8084/
```

**제공 화면 탭**:

| 탭 | 기능 |
|----|------|
| ⚡ **E2E 전체 흐름** | 3단계 스텝별 실시간 진행 시각화 + 결과 JSON 표시 |
| 🎟 **Ticket 발급** | Step 1 단독 실행 (결과가 검증 탭에 자동 입력) |
| ✅ **Ticket 검증** | Step 2 단독 실행 (ticketId 직접 입력 가능) |
| 🩺 **헬스 / 진단** | DB, IdO 연결 상태, 응답시간 실시간 조회 |
| 🗂 **세션 목록** | 최근 세션 현황 + 강제 무효화 버튼 |
| 📬 **이벤트 큐** | 미배달 이벤트 목록 |
| 📡 **Webhook 현황** | 최근 1h 수신 통계 |
| ℹ **흐름 설명** | E2E 흐름도 + 보안 체크리스트 안내 |

---

## 유관기관 E2E 연동 흐름

### 전체 3단계 흐름 (v1.7.0)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  POST /api/v1/simulator/run  (또는 브라우저 UI에서 실행)                        │
│                                                                             │
│  ── [STEP 1] Ticket 발급 ────────────────────────────────────────────────── │
│                                                                             │
│    IdoTicketClient.issue(qimUserId, authResultId, authLevel, ...)           │
│      POST {ido}/api/v1/handoff/issue                                        │
│      Headers:                                                               │
│        X-Agency-Code:   AGENCY_STUB_001                                     │
│        X-Agency-Key:    stub-api-key-dev-001  ← 원문, 로그 미기록            │
│        X-Correlation-Id: {UUID}                                             │
│        Idempotency-Key:  {UUID}  ← 멱등 처리 (Redis TTL 1일)               │
│      Resilience4j: CircuitBreaker "ido-ticket" + Retry "ido-ticket"        │
│                                                                             │
│      ▼  IdO 수신 (HandoffAgencyKeyInterceptor)                              │
│        ① X-Agency-Code, X-Agency-Key 헤더 존재 확인                         │
│        ② SHA-256(rawKey) 계산                                               │
│        ③ SELECT api_key_hash FROM ido.agency_meta                          │
│             WHERE agency_code = ? AND active = TRUE                         │
│        ④ MessageDigest.isEqual() — 상수시간 비교 (타이밍 공격 방지)           │
│        ⑤ 성공 시 validatedAgencyCode → Request Attribute 저장               │
│      ▼                                                                      │
│      HandoffServiceImpl.issue()                                             │
│        정책 검사 (최소 인증 수준, 점검 시간대, 사용자 상태)                      │
│        AES-256-GCM 암호화 + HMAC-SHA256 서명                                │
│        → HandoffTicket { ticketId, expiresAt }                              │
│                                                                             │
│  ── [STEP 2] Ticket 검증 ────────────────────────────────────────────────── │
│                                                                             │
│    IdoVerifyClient.verify(ticketId, correlationId)                          │
│      POST {ido}/api/v1/handoff/verify                                       │
│      Headers: X-Agency-Code, X-Agency-Key, X-Correlation-Id                │
│      Resilience4j CircuitBreaker "ido-verify"                               │
│        슬라이딩 윈도우 10회, 실패율 50% → OPEN 10s                           │
│        슬로우 콜 4s 초과 → 실패로 간주 (80% 이상 시 CB OPEN)                 │
│      Resilience4j Retry "ido-verify"                                        │
│        5xx / 네트워크 오류 시 최대 3회 (500ms → 1000ms exponential)          │
│        4xx → 재시도 없음, 즉시 REJECTED 반환                                 │
│      ▼                                                                      │
│      에러 처리:                                                               │
│        401/403 → REJECTED(AUTH_FAILED)                                      │
│        404     → REJECTED(TICKET_NOT_FOUND)                                 │
│        409     → REJECTED(TICKET_ALREADY_CONSUMED)                          │
│        410     → REJECTED(TICKET_EXPIRED)                                   │
│        CB OPEN → HOLD (fallback: 일시 오류 안내)                             │
│      ▼                                                                      │
│      → HandoffPayload { APPROVED | REJECTED | HOLD }                        │
│                                                                             │
│  ── [STEP 3] 세션 생성 ──────────────────────────────────────────────────── │
│                                                                             │
│    Session Fixation 방지                                                     │
│      기존 AGSID 쿠키 → DB 무효화 → 쿠키 삭제                                 │
│    ▼                                                                        │
│    AgencySessionService.createSession()                                     │
│      SecureRandom 192-bit → rawAGSID (Base64URL)                           │
│      DB 저장: SHA-256(rawAGSID)  ← 원문 미저장                              │
│      agency_local_session INSERT                                             │
│      agency_user findOrCreate (agencySubjectId 기준)                        │
│      session_event_log INSERT (SESSION_CREATED)                             │
│    ▼                                                                        │
│    AGSID 쿠키 발급                                                           │
│      Secure=true / HttpOnly=true / SameSite=Strict                         │
│      maxAge = idle-timeout-minutes (기본 30분)                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Webhook 수신 흐름 (Push)

```
IdO WebhookDispatchOutboxRelay
  → POST /api/v1/webhook/inbound
      Headers: X-Webhook-Signature: HMAC-SHA256(payload, signingSecret)
               X-Webhook-Timestamp: Unix epoch (초)
               X-Correlation-Id, X-Source-System

WebhookInboundController (HMAC-SHA256 서명 검증)
  ① 타임스탬프 검증: ±5분 이내 (재전송 공격 방지)
  ② HMAC-SHA256 서명 검증 (상수시간 비교)
  ③ 중복 방지: (source_event_id, agency_code) UNIQUE 인덱스
  ④ webhook_inbound 저장 (status=PENDING)
  ⑤ 이벤트 라우팅 및 우선순위 큐 적재:
      HANDOFF_REVOKED     → 세션 무효화 + 우선순위 1 (최고)
      SESSION_ADVISORY    → 사용자 기준 일괄 무효화 + 우선순위 2
      MEMBER_WITHDRAWN    → 사용자 비활성화 + 우선순위 3
      MEMBER_REGISTERED   → 사용자 등록 + 우선순위 5
```

### 이벤트 폴링 흐름 (Pull)

```
GET /api/v1/events/poll?limit=20&eventType=HANDOFF_REVOKED&since=...
  → agency_event_queue
      WHERE agency_code = ? AND delivered = FALSE
      ORDER BY priority ASC, created_at ASC
      LIMIT ?
  → 반환 후 delivered=TRUE 업데이트 (단건 트랜잭션, 중복 배달 없음)
```

---

## 보안 체크리스트

| 항목 | 구현 위치 | 상태 |
|------|-----------|------|
| X-Agency-Key SHA-256 DB 검증 (기관 자체 API 보호) | `AgencyApiKeyInterceptor` | ✅ v1.6.0 |
| X-Agency-Key SHA-256 DB 검증 (IdO /issue 경로) | `HandoffAgencyKeyInterceptor` | ✅ v1.7.0 |
| X-Agency-Key SHA-256 DB 검증 (IdO /verify 경로) | `HandoffAgencyKeyInterceptor` | ✅ v1.7.0 |
| 상수시간 비교 (타이밍 공격 방지) | `MessageDigest.isEqual()` (양측 인터셉터) | ✅ |
| rawKey 절대 로그 미기록 | 양측 인터셉터 + `IdoTicketClient` + `IdoVerifyClient` | ✅ |
| 기관 비활성화(active=FALSE) 차단 | `HandoffAgencyKeyInterceptor` DB 쿼리 | ✅ |
| Resilience4j CircuitBreaker (Ticket 발급) | `IdoTicketClient` (ido-ticket CB) | ✅ v1.7.0 |
| Resilience4j CircuitBreaker (Ticket 검증) | `IdoVerifyClient` (ido-verify CB) | ✅ |
| Resilience4j Retry (5xx/네트워크만, 4xx 제외) | `IdoTicketClient`, `IdoVerifyClient` | ✅ |
| AGSID 192-bit SecureRandom | `AgencySessionService` | ✅ |
| DB 저장 SHA-256(rawAGSID) — 원문 미저장 | `AgencySessionService` | ✅ |
| Session Fixation 방지 | `AgencyEntryController`, `AgencySimulatorController` | ✅ |
| Secure / HttpOnly / SameSite=Strict 쿠키 | `AgencyEntryController` | ✅ |
| Webhook HMAC-SHA256 서명 검증 | `WebhookInboundController` | ✅ |
| Webhook 타임스탬프 재전송 방지 (±5분) | `WebhookInboundController` | ✅ |
| 중복 이벤트 방지 (UNIQUE 인덱스) | `webhook_inbound` 테이블 (source_event_id, agency_code) | ✅ |
| Handoff Ticket 1회성 소비 | `HandoffServiceImpl` | ✅ |
| AES-256-GCM Ticket 페이로드 암호화 | `HandoffCryptoService` | ✅ |
| Idempotency-Key 멱등 처리 | `HandoffController` (Redis TTL 1일) | ✅ |
| correlationId 전 계층 전파 | `CorrelationIdHolder` | ✅ |

---

## Flyway 마이그레이션

각 서비스 `src/main/resources/db/migration/` 하위에 위치합니다.

### ido 스키마 (PostgreSQL — `ido.*`)

| 파일 | 주요 내용 |
|------|-----------|
| `V1__create_schema.sql` | `agency_meta`, `handoff_audit`, `outbox` + AGENCY_STUB_001 시드 |
| `V2__add_fe_session.sql` | `fe_session_audit`, `fe_return_url_whitelist` |
| `V3__add_keycloak_auth.sql` | Keycloak OIDC 브로커 관련 |
| `V4__add_qim_sp_receiver.sql` | QIM SP 수신 관련 |
| `V5__add_processed_event.sql` | 멱등 이벤트 처리 |
| `V6__add_broker_audit_log.sql` | 브로커 감사 로그 |
| `V7__add_webhook_and_audit.sql` | `agency_webhook_config`, `webhook_dispatch_outbox` |
| **`V8__seed_agency_api_key_and_fix_webhook.sql`** | **★ v1.7.0** api_key_hash 시드, Webhook URL 수정, webhook_enabled 활성화 |

### agency-stub 스키마 (PostgreSQL — `agency_stub.*`)

| 파일 | 주요 내용 |
|------|-----------|
| `V1__create_schema.sql` | `agency_user`, `agency_permission`, `agency_local_session`, `session_event_log`, `processed_event` |
| `V2__add_webhook_and_api_key.sql` | `agency_api_key`, `webhook_inbound`, `agency_event_queue` |

### V8 마이그레이션 핵심 내용

```sql
-- 1. AGENCY_STUB_001 api_key_hash 시드 (V1 에서 NULL 이었던 값 설정)
-- rawKey = "stub-api-key-dev-001"
-- SHA-256(rawKey) = 8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df
UPDATE ido.agency_meta
SET api_key_hash = '8a5ad1ec5a18b326ed9ae616c9883e46bede28ed5b84d2912bb70263433749df',
    updated_at   = NOW()
WHERE agency_code = 'AGENCY_STUB_001';

-- 2. Webhook endpoint URL 수정 (V7 오류 수정)
--    이전: http://localhost:8084/webhook/handoff
--    이후: http://localhost:8084/api/v1/webhook/inbound
UPDATE ido.agency_webhook_config
SET endpoint_url        = 'http://localhost:8084/api/v1/webhook/inbound',
    signing_secret_hash = 'ad4bb1a5f1e0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6',
    updated_at          = NOW()
WHERE agency_code = 'AGENCY_STUB_001';

-- 3. webhook_enabled = TRUE 활성화
UPDATE ido.agency_meta
SET webhook_enabled  = TRUE,
    webhook_endpoint = 'http://localhost:8084/api/v1/webhook/inbound',
    updated_at       = NOW()
WHERE agency_code = 'AGENCY_STUB_001';
```

### 스키마 분리 원칙

```
PostgreSQL DB: onepass  (내부망 172.20.0.10)
├── qsign.*        ← q-sign 전용
├── ido.*          ← ido 전용 (fe_session_audit, agency_meta, agency_webhook_config 포함)
└── agency_stub.*  ← agency-stub PoC 전용 (실 기관은 자체 DB 보유)

MariaDB DB: qim  (내부망 172.20.0.21)
└── qim.*          ← q-im 전용
```

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

### 컨슈머 그룹

| 그룹 ID | 구독 토픽 | 모듈 | 처리 내용 |
|---------|----------|------|-----------|
| `ido-qim-consumer` | `qim.user.events` | `ido` | 버전 검사 + 캐시 무효화 |
| `ido-qsign-consumer` | `qsign.auth.events` | `ido` | 인증 결과 처리 |
| `ido-fe-advisory-consumer` | `platform.session.advisory` | `ido` | FE 세션 즉시 무효화 |
| `agency-stub-consumer` ⚠️ | `ido.handoff.events` | `agency-stub` | **PoC 전용** — REVOKED → 기관 세션 무효화 |

> ⚠️ **PoC 한정 설계 주의**: `agency-stub`의 Kafka 직접 구독은 **PoC 시뮬레이션 전용**입니다.  
> 실 운영에서 유관기관은 내부 Kafka에 직접 접근할 수 없습니다.  
> **실 운영 기관 세션 무효화 방법**: Webhook Push (`/api/v1/webhook/inbound`) 또는 Polling API (`/api/v1/events/poll`) 사용

---

## 빠른 시작

### 사전 요구사항

- **JDK 21** (Eclipse Temurin 권장)
- **Docker 24+** / Docker Compose v2
- **Node.js 20 LTS** + **Yarn 1.22** (프론트엔드 개발 시)

### 1. 인프라 기동

```bash
# 기본 인프라 (PostgreSQL · Redis · Kafka · Zookeeper · Kafka-init · Kafka-UI · Redis Insight)
docker compose -f infra/docker/docker-compose.yml up -d

# 모니터링 도구 포함
docker compose -f infra/docker/docker-compose.yml --profile tools up -d
```

### 2. 전체 Java 빌드 (테스트 제외)

```bash
./gradlew :platform-common:build :q-sign:build :q-im:build \
          :ido:build :agency-stub:build -x test
# → BUILD SUCCESSFUL (약 3초)
```

### 3. 서비스 기동 (로컬 개발)

```bash
# 터미널 1 — Q-Sign (인증 SoR)
./gradlew :q-sign:bootRun

# 터미널 2 — Q-IM (식별 SoR)
./gradlew :q-im:bootRun

# 터미널 3 — IdO (정책 오케스트레이터 + FE BFF)
./gradlew :ido:bootRun

# 터미널 4 — agency-stub (유관기관 OIDC 클라이언트 스텁)
./gradlew :agency-stub:bootRun
# → 브라우저 테스트 UI: http://localhost:8084/

# 터미널 5 — React 개발서버 (프론트엔드 작업 시만)
cd onepass-fe/frontend && yarn install && yarn dev
# → http://localhost:3000 (proxy → ido:8083)
```

### 4. E2E 시뮬레이션 실행

```bash
# ① 브라우저 UI 사용 (권장)
open http://localhost:8084/

# ② curl 직접 — E2E 전체 흐름
curl -s -X POST http://localhost:8084/api/v1/simulator/run \
  -H 'Content-Type: application/json' \
  -d '{
    "qimUserId":    "test-user-001",
    "authLevel":    "L2",
    "providerCode": "QSIGN_CERT"
  }' | jq .

# ③ 단계별 실행
# Step 1: Ticket 발급
curl -s -X POST http://localhost:8084/api/v1/simulator/ticket \
  -H 'Content-Type: application/json' \
  -d '{"authLevel":"L2","providerCode":"QSIGN_CERT"}' | jq .

# Step 2: Ticket 검증 (위에서 받은 ticketId 사용)
curl -s -X POST \
  "http://localhost:8084/api/v1/simulator/verify?ticketId={ticketId}" | jq .

# 전체 헬스 확인
curl -s http://localhost:8084/api/v1/health | jq .

# DB + 세션 통계
curl -s http://localhost:8084/api/v1/simulator/status | jq .
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
| `onepass-ido` | onepass-ido:latest | **8083** | 172.20.0.19 | `app` |
| `onepass-react` | onepass-react:latest | **3001** | 172.20.0.20 | `optionB` |
| **`onepass-agency-stub`** | onepass-agency-stub:latest | **8084** | **172.20.0.25** | `app` |

### Docker Compose 프로파일

```bash
# 기본 인프라만 (DB + Kafka + Redis)
docker compose -f infra/docker/docker-compose.yml up -d

# 앱 스택 (ido + agency-stub + React Nginx)
docker compose -f infra/docker/docker-compose.yml \
  --profile app --profile optionB up -d

# 전체 (인프라 + 앱 + 모니터링 도구)
docker compose -f infra/docker/docker-compose.yml \
  --profile app --profile optionB --profile tools up -d
```

### agency-stub 환경변수

| 변수 | 기본값 (로컬) | 설명 |
|------|-------------|------|
| `DB_HOST` | `localhost` → `postgres` (컨테이너) | PostgreSQL 호스트 |
| `REDIS_HOST` | `localhost` → `redis` (컨테이너) | Redis 호스트 |
| `KAFKA_SERVERS` | `localhost:9092` → `kafka:29092` (컨테이너) | Kafka 브로커 |
| `IDO_BASE_URL` | `http://localhost:8083` → `http://onepass-ido:8083` (컨테이너) | IdO 서비스 URL |
| `AGENCY_API_KEY` | `stub-api-key-dev-001` | 기관 API Key **(운영: Vault 주입)** |
| `IDO_WEBHOOK_SIGNING_SECRET` | `poc-webhook-secret-change-in-production` | Webhook 서명 비밀키 **(운영: Vault 주입)** |

---

## 접속 URL

| URL | 용도 | 비고 |
|-----|------|------|
| **http://localhost:8084/** | **agency-stub 테스트 Web UI** | ★ 브라우저 E2E 시뮬레이터 |
| http://localhost:8084/api/v1/simulator/run | E2E 시뮬레이션 API | POST |
| http://localhost:8084/api/v1/health | 헬스 진단 | GET |
| http://localhost:8083 | **ido API + FE BFF** | Handoff, FE세션, X-Agency-Key 검증 |
| http://localhost:8083/actuator/health | IdO 헬스체크 | GET |
| http://localhost:3000 | React HMR 개발서버 | webpack proxy → ido:8083 |
| http://localhost:3001 | Nginx React 빌드 | Option B 운영 |
| http://localhost:8081 | q-sign API | 인증 SoR |
| http://localhost:8082 | q-im API | 식별 SoR |
| http://localhost:8090 | Kafka UI | admin / admin |
| http://localhost:5540 | Redis Insight | |
| http://localhost:5050 | pgAdmin 4 | admin@onepass.local / admin (`--profile tools`) |
| http://localhost:8085 | Schema Registry | (`--profile schema`) |

---

## onepass-fe: 순수 React SPA

`onepass-fe`는 **Spring Boot가 없는 순수 React(TypeScript) 모듈**입니다.  
모든 백엔드 기능은 `ido`(port 8083)에서 제공합니다.

### 개발 구조

```
onepass-fe/
├── build.gradle.kts          # Node Gradle Plugin (Spring Boot 의존성 없음)
├── Dockerfile.optionA        # React → ido static resource 포함 단일 JAR
├── Dockerfile.optionB        # React → Nginx (2-stage: node:20 → nginx:1.27)
└── frontend/
    ├── webpack.config.js      devServer proxy: /api → ido:8083
    └── src/
        ├── api/
        │   ├── client.ts      Axios (proxy → /api → ido:8083)
        │   └── session.ts     /api/v1/fe-session/** 호출
        ├── pages/
        │   ├── Login/         세션 체크 + 인증수단 선택 UI
        │   ├── Conversion/    7단계 인증 전환 흐름
        │   └── Error/         에러 코드별 메시지
        └── types/index.ts     AuthLevel, SessionCheckResponse 등
```

### Gradle 태스크

| 태스크 | 명령어 | 설명 |
|--------|--------|------|
| 의존성 설치 | `./gradlew :onepass-fe:yarnInstall` | `yarn install` |
| 프로덕션 빌드 | `./gradlew :onepass-fe:build` | `yarn build:prod` → `dist/` |
| 개발서버 기동 | `./gradlew :onepass-fe:frontendDev` | `yarn dev` (port 3000) |

---

## 개발 환경 설정

### 환경변수 (ido — 로컬)

| 환경변수 | 기본값 | 설명 |
|----------|--------|------|
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `KAFKA_SERVERS` | `localhost:9092` | Kafka 브로커 |
| `DB_USERNAME` | `onepass` | PostgreSQL 사용자 |
| `DB_PASSWORD` | `onepass` | PostgreSQL 비밀번호 |
| `QIM_BASE_URL` | `http://localhost:8082` | Q-IM 서비스 URL |
| `QSIGN_BASE_URL` | `http://localhost:8081` | Q-Sign 서비스 URL |
| `CORS_ORIGIN_DEV` | `http://localhost:3000` | React dev server Origin |
| `CORS_ORIGIN_PROD` | `http://localhost:3001` | Nginx React Origin |

### 환경변수 (agency-stub — 로컬)

| 환경변수 | 기본값 | 설명 |
|----------|--------|------|
| `IDO_BASE_URL` | `http://localhost:8083` | IdO 서비스 URL |
| `AGENCY_API_KEY` | `stub-api-key-dev-001` | 기관 API Key |
| `IDO_WEBHOOK_SIGNING_SECRET` | `poc-webhook-secret-change-in-production` | Webhook 서명 비밀키 |
| `DB_HOST` | `localhost` | PostgreSQL 호스트 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `KAFKA_SERVERS` | `localhost:9092` | Kafka 브로커 |

### Resilience4j 설정 (agency-stub `application.yml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      ido-verify:           # Ticket 검증 CB
        sliding-window-size: 10
        failure-rate-threshold: 50          # 50% 실패 → OPEN
        wait-duration-in-open-state: 10s
        permitted-calls-in-half-open-state: 3
        slow-call-duration-threshold: 4s
        slow-call-rate-threshold: 80        # 슬로우콜 80% 이상 → OPEN
        record-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
          - java.net.SocketTimeoutException
          - java.io.IOException
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException
      ido-ticket:           # Ticket 발급 CB (★ v1.7.0)
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
  retry:
    instances:
      ido-verify:           # 최대 3회 (2회 재시도), 500ms → 1000ms exponential
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - org.springframework.web.client.HttpServerErrorException
          - org.springframework.web.client.ResourceAccessException
        ignore-exceptions:
          - org.springframework.web.client.HttpClientErrorException
      ido-ticket:           # 최대 3회 (★ v1.7.0)
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
```

---

## 빌드 & 실행

### 전체 Java 빌드

```bash
./gradlew :platform-common:build :q-sign:build :q-im:build \
          :ido:build :agency-stub:build -x test
# 예상 결과: BUILD SUCCESSFUL (약 3초)
```

### 개별 모듈 실행

```bash
./gradlew :platform-common:build    # 공통 라이브러리 (JAR만)
./gradlew :q-sign:bootRun           # 인증 SoR           :8081
./gradlew :q-im:bootRun             # 식별 SoR           :8082
./gradlew :ido:bootRun              # 정책 오케스트레이터+BFF :8083
./gradlew :agency-stub:bootRun      # 기관 OIDC 클라이언트  :8084

# React 개발서버 (별도 터미널)
./gradlew :onepass-fe:frontendDev   # port 3000, proxy → ido:8083
# 또는
cd onepass-fe/frontend && yarn dev
```

### Docker 이미지 빌드

```bash
# ido (BFF 포함)
docker build -f onepass-fe/Dockerfile.optionA -t onepass-ido:latest .

# React Nginx
docker build -f onepass-fe/Dockerfile.optionB -t onepass-react:latest .

# agency-stub
docker build -f agency-stub/Dockerfile -t onepass-agency-stub:latest .
```

---

## 코딩 컨벤션

### Java

- **패키지 루트**: `kr.go.smes.<모듈>`
- **클래스**: `PascalCase`
- **메서드 / 변수**: `camelCase`
- **상수**: `UPPER_SNAKE_CASE`
- **Lombok** 적극 활용 (`@Getter`, `@Builder`, `@RequiredArgsConstructor`)
- **커밋 메시지**: [Conventional Commits](https://www.conventionalcommits.org/) (`feat`, `fix`, `chore`, `refactor`, `docs`)

### TypeScript / React

- **인터페이스**: `PascalCase`
- **함수명**: `camelCase`
- **컴포넌트 파일**: `PascalCase.tsx`
- **Path alias**: `@/`, `@pages/`, `@api/`, `@components/`

### 패키지 규칙

| 모듈 | 루트 패키지 |
|------|------------|
| platform-common | `kr.go.smes.common` |
| q-sign | `kr.go.smes.qsign` |
| q-im | `kr.go.smes.qim` |
| ido | `kr.go.smes.ido` |
| ido FE BFF | `kr.go.smes.ido.fe` |
| agency-stub | `kr.go.smes.agency` |

### Git 브랜치 전략

| 브랜치 | 용도 |
|--------|------|
| `main` | 안정 릴리즈 |
| `genspark_ai_developer` | AI 개발 작업 브랜치 → PR → main |

---

## 라이선스

PoC 내부 개발용 프로젝트입니다.
