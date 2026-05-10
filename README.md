# OnePass 통합인증 플랫폼 (Integration-SSO)

**중소벤처기업부 중기원패스(OnePass) 통합인증 SSO 및 아이덴티티 관리 시스템** PoC/프리프로덕션 구현체.  
**4+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe · agency-stub) 기반 EDA 아키텍처.

> **현재 버전: v2.0.0** — Sprint 5 완료 (AES 키 로테이션 + 모니터링 인프라 + 테스트 365개)  
> **빌드 상태**: `./gradlew build -x test` → **BUILD SUCCESSFUL** (전 모듈)  
> **테스트**: `./gradlew test` → **365개 통과**, 0 failures  
> **PR**: [#39 (OPEN)](https://github.com/HipsterMIN/integration-sso/pull/39) — Sprint 5 T4+T5

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [전체 구현 진행률](#전체-구현-진행률)
3. [아키텍처 개요](#아키텍처-개요)
4. [모듈 책임 분리](#모듈-책임-분리)
5. [기술 스택](#기술-스택)
6. [모듈 구성](#모듈-구성)
7. [데이터베이스 구성](#데이터베이스-구성)
8. [Kafka 토픽](#kafka-토픽)
9. [보안 체계](#보안-체계)
10. [Flyway 마이그레이션 현황](#flyway-마이그레이션-현황)
11. [테스트 현황](#테스트-현황)
12. [모니터링 인프라](#모니터링-인프라)
13. [빠른 시작](#빠른-시작)
14. [접속 URL](#접속-url)
15. [개발 환경 설정](#개발-환경-설정)
16. [전체 로드맵 & 잔여 작업](#전체-로드맵--잔여-작업)
17. [코드 품질 분석 및 제언](#코드-품질-분석-및-제언)
18. [코딩 컨벤션](#코딩-컨벤션)
19. [문서 디렉토리](#문서-디렉토리)

---

## 버전 히스토리

| 버전 | PR | 스프린트 | 주요 내용 |
|------|----|---------|---------| 
| **v2.0.0** | [#39](https://github.com/HipsterMIN/integration-sso/pull/39) | Sprint 5 | **AES 키 로테이션** (KeyVersionRegistry + v{n}.{iv}.{ct} 포맷) + **모니터링 인프라** (Prometheus/Grafana/Loki Docker Compose + 대시보드) |
| v1.9.9 | [#38](https://github.com/HipsterMIN/integration-sso/pull/38) | Sprint 4-5 | **UuidV7Test 27개** (v7 포맷·단조증가·고유성·스레드안전) + **WebhookDispatcherServiceTest 33개** |
| v1.9.8 | [#37](https://github.com/HipsterMIN/integration-sso/pull/37) | Sprint 4 | **HandoffServiceImplTest 18개** — issue/verify/revoke 단위 테스트 |
| v1.9.5 | [#35](https://github.com/HipsterMIN/integration-sso/pull/35) | Sprint 3-4 | **UUID v4 → v7 전체 교체** (RFC 9562) + **단위 테스트 기반** (7파일 신규) |
| v1.9.4 | [#33](https://github.com/HipsterMIN/integration-sso/pull/33) | Sprint 3-4 | **P2 운영 고도화** + **P3 배포 준비** 완전 구현 |
| v1.9.3 | [#32](https://github.com/HipsterMIN/integration-sso/pull/32) | Sprint 2 | **SLO 완전 구현** + 개인정보 파기 스케줄러 + FE 인증 기반 |
| v1.9.2 | [#31](https://github.com/HipsterMIN/integration-sso/pull/31) | Sprint 1 | **P0 보안 결함** 완전 제거 + 테스트 기반 구축 |
| v1.9.1 | [#28](https://github.com/HipsterMIN/integration-sso/pull/28) | — | 기관 이벤트 폴링 API 완성 |
| v1.9.0 | [#24](https://github.com/HipsterMIN/integration-sso/pull/24) | — | P0/P1/P2 GAP 마감, ProviderRouter, 동적 CircuitBreaker |
| v1.8.0 | [#22](https://github.com/HipsterMIN/integration-sso/pull/22) | — | Admin API, Redis Rate Limiter, HandoffStrategy 패턴, PKCE |

---

## 전체 구현 진행률

> **기준일**: 2026-05-10 | **총 테스트**: 365개 (ido 170 + platform-common 59 + q-sign 23 + q-im 113)

### 모듈별 구현 완성도

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비, UUID v7 유틸)
Q-Sign           ████████████████████  97%  (InternalSig 수신 검증 완료, SLO 완료)
Q-IM             ████████████████████  96%  (CI 암호화 v{n} 포맷, 파기 스케줄러 완료)
IdO              ████████████████████  98%  (AES 키 로테이션 추가, Micrometer P2 대기)
agency-stub      ████████████████████  90%  (E2E 시뮬레이터 완비, 단위 테스트 미작성)
onepass-fe       ████████████████░░░░  78%  (인증 기반 완비, 관리자 UI·CSP 미완)
인프라/Docker    ████████████████████ 100%  (모니터링 스택 완비, CI/CD P3 대기)
보안             ████████████████████  98%  (AES 키 로테이션 완료, 기본값 강화 완료)
테스트 커버리지  ████████████░░░░░░░░  55%  (ido+q-im+q-sign 커버, agency-stub 미완)
```

**전체 완성도**: 약 **93%** — 프리프로덕션 단계

### Sprint별 완료 현황

| Sprint | 목표 | 상태 | 완료 항목 |
|--------|------|------|-----------|
| **Sprint 1** | P0 보안 결함 | ✅ **완료** | API Key PBKDF2, 기본 시크릿 제거, X-Internal-Sig |
| **Sprint 2** | P1 SLO + 개인정보 | ✅ **완료** | SLO Keycloak 전파, SP 로그아웃 Webhook, 파기 스케줄러 |
| **Sprint 3** | P2 운영 고도화 | ✅ **완료** | UUID v7, Micrometer 기초, 구조화 로깅, FE 상태관리 |
| **Sprint 4** | 테스트 기반 | ✅ **완료** | HandoffServiceImpl 18개, Webhook 33개, UuidV7 27개 |
| **Sprint 5** | 암호화 + 모니터링 | ✅ **완료** | AES 키 로테이션, Prometheus/Grafana/Loki, 대시보드 |
| **Sprint 6** | 잔여 테스트 | 🔲 **미시작** | agency-stub 테스트, 통합 테스트 |
| **Sprint 7** | FE 완성 | 🔲 **미시작** | 관리자 UI, CSP, ErrorBoundary |
| **Sprint 8** | CI/CD + 부하테스트 | 🔲 **미시작** | GitHub Actions, k6, OWASP |

---

## 아키텍처 개요

> **⚠️ 설계 원칙**: 모든 유관기관(기관 시스템)은 **외부망**에 위치합니다.  
> 기관은 내부 Kafka·DB에 직접 접근하지 않으며, **IdO 공개 API(HTTPS)** 만을 통해 통신합니다.

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
  │                                   GET  /api/v1/agency/events        │
  │  [IdP 브로커]                                                        │
  │  /api/v1/broker/**                [Webhook Push]                    │
  │  /api/v1/oidc/**                  WebhookDispatcherService           │
  │  ProviderRouter                   WebhookDispatchOutboxRelay        │
  │                                                                     │
  │  [보안]                           [암호화 — Sprint 5]                │
  │  AES-256-GCM (v{n}.{iv}.{ct})    KeyVersionRegistry                │
  │  HMAC-SHA256 서명                  HandoffKeyRotationScheduler       │
  │  X-Agency-Key SHA-256             Redis 분산 락 (90일 주기)          │
  └──────────────────┬───────────────────────────────────────────────────┘
                     │ HTTP (내부망 전용)
         ┌───────────┴───────────┐
         ▼                       ▼
  ┌─────────────┐       ┌─────────────┐
  │ q-sign:8081 │       │  q-im:8082  │
  │  인증 SoR    │       │  식별 SoR    │
  │  Keycloak   │       │  회원 원장   │
  │  OIDC 브로커 │       │  CI 암호화   │
  │  SLO 전파   │       │  v{n}.{iv}  │
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
    │  + 각 토픽별 .dlq 토픽             │
    └───────────────────────────────────┘

    ┌───────────────────────────────────┐
    │     모니터링 스택 (Sprint 5)        │
    │  Prometheus :9090                 │
    │  Grafana    :3000                 │
    │  Loki       :3100                 │
    │  Promtail   :9080                 │
    │  + Redis/Kafka/PG Exporter        │
    └───────────────────────────────────┘
```

---

## 모듈 책임 분리

| 모듈 | SoR 역할 | 포트 | 핵심 책임 |
|------|---------|------|----------|
| `platform-common` | — | — | 공통 도메인·이벤트·에러코드·UUID v7 유틸 |
| `q-sign` | **인증 SoR** | 8081 | OIDC 브로커링, JWT 검증, PKCE, SLO Keycloak 전파 |
| `q-im` | **식별 SoR** | 8082 | qimUserId, CI AES-256-GCM v{n}, DI HMAC, 회원 원장, 파기 |
| `ido` | **정책 오케스트레이터** | 8083 | Handoff 발급/검증, Policy, Webhook, FE BFF, AES 키 로테이션 |
| `agency-stub` | — (PoC 전용) | 8084 | 유관기관 연동 E2E 시뮬레이터 |
| `onepass-fe` | — | 3000/3001 | React 18 SPA (TypeScript, Ant Design) |

---

## 기술 스택

### 백엔드 공통

| 기술 | 버전 | 적용 범위 |
|------|------|----------|
| Java | **21 LTS** | 전 모듈 |
| Spring Boot | **3.5.9** | q-sign, q-im, ido, agency-stub |
| Gradle | **9.5.0** | 멀티모듈 빌드 |
| Spring Data JPA | BOM 관리 | q-sign, q-im, ido |
| Spring Kafka | BOM 관리 | 전 서비스 |
| Spring Data Redis | BOM 관리 | ido, q-im |
| Flyway | **11.8.0** | DB 마이그레이션 |
| Resilience4j | **2.2.0** | Circuit Breaker, Retry |
| JJWT | **0.12.6** | JWT 서명 검증 |
| Micrometer | BOM 관리 | Prometheus 메트릭 |
| JUnit 5 + Mockito | BOM 관리 | 단위 테스트 (365개) |

### 프론트엔드 (`onepass-fe/frontend/`)

| 기술 | 버전 |
|------|------|
| React | 18.3 |
| TypeScript | 5.4 |
| Ant Design | 5.18 |
| TanStack Query | v5 |
| Zustand | 4.5 |

### 인프라

| 서비스 | 이미지 | 용도 |
|--------|--------|------|
| PostgreSQL | `postgres:16-alpine` | q-sign, ido 스키마 |
| MariaDB | `mariadb:11.4` | q-im 전용 |
| Redis | `redis:7.2-alpine` | 세션, PKCE, 캐시, Rate Limit |
| Kafka | `confluentinc/cp-kafka:7.6.1` | 이벤트 버스 |
| Keycloak | `quay.io/keycloak/keycloak:24` | OIDC IdP 브로커 |
| Prometheus | `prom/prometheus:v2.51.2` | 메트릭 수집 |
| Grafana | `grafana/grafana-oss:10.4.2` | 대시보드 |
| Loki | `grafana/loki:2.9.6` | 로그 집계 |
| Promtail | `grafana/promtail:2.9.6` | 컨테이너 로그 수집 |

---

## 모듈 구성

```
onepass-platform/
├── platform-common/
│   └── src/main/java/kr/go/smes/common/
│       ├── domain/           # AuthResult, HandoffPayload, HandoffTicket
│       ├── error/            # PlatformErrorCode
│       ├── event/            # AuthEvent, HandoffEvent, AuditLogEvent
│       └── util/             # UuidV7, ApiKeyHashValidator
│
├── q-sign/                   # 인증 SoR (포트 8081)
│   └── src/main/java/kr/go/smes/qsign/
│       ├── broker/           # Keycloak OIDC 브로커
│       ├── kafka/            # Outbox + 멱등 컨슈머
│       ├── pkce/             # RFC 7636 PKCE
│       └── slo/              # SLO Keycloak end_session 전파
│
├── q-im/                     # 식별 SoR (포트 8082, MariaDB)
│   └── src/main/java/kr/go/smes/qim/
│       ├── crypto/           # CI AES-256-GCM v{n}.{iv}.{ct}
│       ├── identity/         # DI HMAC-SHA256
│       ├── outbox/           # Outbox + Snapshot
│       ├── retention/        # 개인정보 파기 스케줄러 (Sprint 2)
│       └── user/             # 회원 등록·조회·상태
│
├── ido/                      # 정책 오케스트레이터 (포트 8083)
│   └── src/main/java/kr/go/smes/ido/
│       ├── admin/            # 기관 Admin API
│       ├── api/              # Handoff + 기관 이벤트 폴링
│       ├── broker/           # IdP 브로커 + Provider 라우팅
│       ├── config/           # Rate Limit, TraceparentFilter
│       ├── crypto/           # KeyVersionRegistry + HandoffKeyRotationScheduler ★Sprint5
│       ├── fe/               # FE 세션 관리
│       ├── handoff/
│       │   ├── crypto/       # HandoffCryptoService (v{n}.{iv}.{ct}) ★Sprint5
│       │   └── strategy/     # HandoffStrategy 패턴
│       ├── kafka/            # 이벤트 컨슈머 4종
│       ├── policy/           # PolicyEngine
│       ├── ratelimit/        # Redis Lua 슬라이딩 윈도우
│       └── webhook/          # Webhook Push + Outbox Relay
│
├── agency-stub/              # 기관 시뮬레이터 (포트 8084)
│
├── onepass-fe/               # React SPA
│
└── infra/
    ├── docker/
    │   ├── docker-compose.yml              # 기본 인프라
    │   └── docker-compose.monitoring.yml   # 모니터링 스택 ★Sprint5
    ├── monitoring/
    │   ├── prometheus/                     # prometheus.yml + alert_rules.yml
    │   ├── grafana/
    │   │   ├── dashboards/                 # onepass-overview.json ★Sprint5
    │   │   └── provisioning/              # datasources + dashboards 프로비저닝
    │   ├── loki/                           # loki-config.yml
    │   └── promtail/                       # promtail-config.yml
    ├── k6/                                 # 부하 테스트 (P3 예정)
    └── owasp/                              # 취약점 스캔 설정
```

---

## 데이터베이스 구성

| 모듈 | DB 엔진 | 스키마 | 최신 Flyway 버전 |
|------|---------|--------|----------------|
| Q-Sign | PostgreSQL 16 | `qsign` | **V5** — auth_method 컬럼 |
| IdO | PostgreSQL 16 | `ido` | **V10** — auth_result 확장, provider_routing |
| Q-IM | MariaDB 11.4 | `qim` | **V3** — CI 암호화 키 버전, user_status_history |
| agency-stub | PostgreSQL 16 | `agency_stub` | **V2** — webhook + api_key |

> **V9 (ido)**: `crypto_key_registry` — AES/HMAC 키 버전 메타데이터 (Sprint 5에서 활용)

---

## Kafka 토픽

| 토픽 | 파티션 | 보존 | 생산자 | 소비자 |
|------|--------|------|--------|--------|
| `qsign.auth.events` | 12 | 1h | Q-Sign | IdO |
| `ido.handoff.events` | 12 | 1y | IdO | IdO → Webhook |
| `platform.session.advisory` | 12 | 24h | IdO | IdO |
| `platform.audit.log` | 12 | 2y | IdO | 감사 시스템 |
| `qim.user.events` | 6 | Compacted | Q-IM | IdO, Q-Sign |
| `qim.user.snapshot` | 6 | Compacted | Q-IM | (확장 예정) |
| `qim.sp.member.events` | 6 | 30d | IdO | IdO |
| *.dlq / *.dlt | 3~6 | 7d | 에러핸들러 | 운영 |

---

## 보안 체계

| 보안 항목 | 구현 방식 | 상태 |
|----------|---------|------|
| 기관 API 키 인증 | PBKDF2-HMAC-SHA256 + 상수시간 비교 | ✅ Sprint 1 |
| **AES 키 버전 로테이션** | `v{n}.{iv}.{ct}` 포맷, 90일 주기, Redis 분산 락 | ✅ **Sprint 5** |
| Handoff Ticket 암호화 | AES-256-GCM + 버전 접두사 | ✅ Sprint 5 |
| CI 암호화 (Q-IM) | AES-256-GCM v{n}.{iv}.{ct} | ✅ v1.8.0 |
| Ticket 서명 | HMAC-SHA256 | ✅ 완료 |
| 내부 서비스 서명 (X-Internal-Sig) | HMAC-SHA256 ±60s 검증 | ✅ Sprint 1 |
| Webhook 서명 | HMAC-SHA256 + ±5분 타임스탬프 | ✅ 완료 |
| PKCE (RFC 7636) | S256 code_challenge | ✅ 완료 |
| W3C traceparent 전파 | TraceparentFilter | ✅ 완료 |
| Rate Limiter | Redis Lua 슬라이딩 윈도우 | ✅ 완료 |
| Provider 단위 CB | Resilience4j 동적 생성 | ✅ 완료 |
| SLO Keycloak 전파 | end_session_endpoint 연동 | ✅ Sprint 2 |
| 개인정보 파기 스케줄러 | GDPR §17 준수, 탈퇴 후 90일 | ✅ Sprint 2 |

---

## Flyway 마이그레이션 현황

### IdO (ido 스키마) — 최신: V10

| 버전 | 내용 |
|------|------|
| V1 | agency_meta, handoff_ticket |
| V2~V6 | outbox, keycloak, oidc_session, broker_audit_log |
| V7~V8 | maintenance, webhook_dispatch_outbox |
| **V9** | `crypto_key_registry`, agency_rate_limit_config ★Sprint 5 활용 |
| **V10** | auth_result 4컬럼 추가, provider_circuit_config |

---

## 테스트 현황

### 모듈별 테스트 수 (2026-05-10 기준)

| 모듈 | 테스트 파일 | 테스트 케이스 | 주요 내용 |
|------|-----------|-------------|---------|
| `ido` | 8개 | **170개** | HandoffServiceImpl 18, WebhookDispatcher 15, OutboxRelay 18, HandoffCryptoService 51, InternalSigVerifier 11, MemberLookup 17, AesDecryptor 12, CallbackUrlValidator 28 |
| `platform-common` | 2개 | **59개** | UuidV7Test 27, (기타 32) |
| `q-sign` | 1개 | **23개** | InternalSigVerifier 11, (기타 12) |
| `q-im` | 5개 | **113개** | CiCryptoService, IdentityService, AuditLog 등 |
| `agency-stub` | 0개 | **0개** | ⚠️ Sprint 6 목표 |
| **합계** | **16개** | **365개** | — |

### 커버리지 목표 vs 현황

```
ido              ████████████████░░░░  ~65% (핵심 서비스 커버, 인프라 레이어 미완)
platform-common  ████████████████████  ~85% (UUID v7, 공통 유틸)
q-sign           ████████████░░░░░░░░  ~50% (InternalSig 커버, OIDC 플로우 미완)
q-im             ████████████████░░░░  ~70% (암호화·식별 커버)
agency-stub      ░░░░░░░░░░░░░░░░░░░░   0%  (Sprint 6 목표)
```

---

## 모니터링 인프라

### 기동 방법

```bash
# 기본 인프라 + 모니터링 스택 함께 기동
docker compose -f infra/docker/docker-compose.yml \
               -f infra/docker/docker-compose.monitoring.yml up -d

# 모니터링만 별도 기동 (기본 인프라 실행 중인 경우)
docker compose -f infra/docker/docker-compose.monitoring.yml up -d
```

### 구성 서비스

| 서비스 | URL | 계정 | 역할 |
|--------|-----|------|------|
| **Prometheus** | http://localhost:9090 | — | 메트릭 수집 (30일 보존) |
| **Grafana** | http://localhost:3000 | admin / onepass-admin | 대시보드 + 알림 |
| **Loki** | http://localhost:3100 | — | 로그 집계 (31일 보존) |
| Redis Exporter | :9121 | — | Redis 메트릭 |
| Kafka Exporter | :9308 | — | Consumer Lag |
| PG Exporter | :9187 | — | PostgreSQL 메트릭 |

### Grafana 대시보드 패널 구성

`infra/monitoring/grafana/dashboards/onepass-overview.json` — 자동 프로비저닝

| Row | 패널 |
|-----|------|
| 서비스 가용성 | q-sign / q-im / ido / agency-stub UP/DOWN Stat |
| Handoff 티켓 | 처리량 & 오류율, P50/P95/P99 응답시간 |
| AES 키 로테이션 | 암호화 오류 수, encrypt/decrypt 처리량 |
| Rate Limit | 429 응답 추이, 서비스별 처리량 |
| Webhook Relay | 성공/실패/DLQ 추이 |
| 인프라 메트릭 | Redis 메모리, Kafka Lag, JVM Heap |
| 감사 로그 (Loki) | AUDIT 레벨 / ERROR 로그 |

---

## 빠른 시작

### 필수 소프트웨어

| 소프트웨어 | 최소 버전 |
|-----------|---------|
| JDK | **21 LTS** |
| Docker Desktop | **24+** |
| Docker Compose | **v2** (플러그인) |
| Node.js | **20.14 LTS** |

### 기동 절차

```bash
# 1. 저장소 복제
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso && chmod +x gradlew

# 2. 인프라 기동
docker compose -f infra/docker/docker-compose.yml up -d

# 3. 모니터링 스택 기동 (선택)
docker compose -f infra/docker/docker-compose.monitoring.yml up -d

# 4. 백엔드 전체 빌드
./gradlew build -x test

# 5. 전체 테스트 실행 (365개)
./gradlew test

# 6. 서비스 기동 (터미널 4개)
./gradlew :q-sign:bootRun      # :8081
./gradlew :q-im:bootRun        # :8082
./gradlew :ido:bootRun         # :8083
./gradlew :agency-stub:bootRun # :8084

# 7. 프론트엔드
cd onepass-fe/frontend && yarn install && yarn dev  # :3000
```

---

## 접속 URL

| 서비스 | URL | 비고 |
|--------|-----|------|
| Q-Sign | http://localhost:8081 | 인증 SoR |
| Q-IM | http://localhost:8082 | 식별 SoR |
| IdO | http://localhost:8083 | 오케스트레이터 |
| agency-stub | http://localhost:8084 | 기관 시뮬레이터 |
| onepass-fe | http://localhost:3000 | React SPA |
| Keycloak | http://localhost:8088 | OIDC IdP (admin/admin) |
| Kafka UI | http://localhost:8090 | 토픽·메시지 조회 |
| Prometheus | http://localhost:9090 | 메트릭 |
| **Grafana** | **http://localhost:3000** | **대시보드 (admin/onepass-admin)** |
| Loki | http://localhost:3100 | 로그 집계 |
| pgAdmin | http://localhost:5050 | PostgreSQL 관리 |
| Redis Insight | http://localhost:5540 | Redis 관리 |

> ⚠️ Grafana와 onepass-fe 개발 서버가 동일한 포트(3000)를 사용합니다.  
> 동시 기동 시 Grafana는 `--profile monitoring` 없이 `docker-compose.monitoring.yml`로 별도 포트 조정 필요.

---

## 개발 환경 설정

### 필수 환경변수

```bash
# IdO 암호화 / 서명 키 (32바이트 Base64, 운영 교체 필수)
IDO_HANDOFF_AES_KEY=<base64-32bytes>
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>
IDO_INTERNAL_SIG_SECRET=<32bytes+>

# Q-IM CI 암호화
QIM_CI_AES_KEY_V1=<base64-32bytes>
QIM_DI_SECRET=<32bytes+>

# Q-Sign Keycloak
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak Admin에서 발급>

# 공통 인프라
DB_HOST=localhost  DB_PORT=5432  DB_NAME=onepass
REDIS_HOST=localhost  KAFKA_SERVERS=localhost:9092

# 모니터링 (선택)
GF_SECRET_KEY=<32bytes+ Grafana 시크릿>
```

---

## 전체 로드맵 & 잔여 작업

### Sprint 6 (2주) — 테스트 커버리지 완성

| ID | 항목 | 우선순위 | 예상 공수 |
|----|------|---------|---------|
| S6-T1 | `agency-stub` 단위 테스트 작성 (AgencySimulator, WebhookInbound) | 🟠 HIGH | 1일 |
| S6-T2 | `q-sign` OIDC 플로우 + SLO 통합 테스트 | 🟠 HIGH | 1일 |
| S6-T3 | Testcontainers 기반 통합 테스트 (DB + Redis) | 🟡 MED | 2일 |
| S6-T4 | `platform-common` ApiKeyHashValidator + PlatformException 테스트 | 🟡 MED | 0.5일 |
| S6-T5 | Micrometer 비즈니스 메트릭 추가 (handoff/webhook/crypto 카운터) | 🟡 MED | 1일 |

### Sprint 7 (2주) — FE 완성

| ID | 항목 | 우선순위 | 예상 공수 |
|----|------|---------|---------|
| S7-T1 | FE `ErrorBoundary` 컴포넌트 | 🟠 HIGH | 0.5일 |
| S7-T2 | FE CSP 헤더 (Nginx + Spring Security) | 🟠 HIGH | 0.5일 |
| S7-T3 | 관리자 대시보드 UI (기관 관리, Rate Limit 조회) | 🟡 MED | 3일 |
| S7-T4 | FE Jest 단위 테스트 (`useAuth`, `useSession` Hook) | 🟡 MED | 1일 |
| S7-T5 | FE 로그아웃 UI + SLO 플로우 연동 | 🟡 MED | 1일 |

### Sprint 8 (2주) — CI/CD + 배포 준비

| ID | 항목 | 우선순위 | 예상 공수 |
|----|------|---------|---------|
| S8-T1 | GitHub Actions CI/CD (PR 빌드 + 테스트 자동화) | 🟠 HIGH | 1일 |
| S8-T2 | k6 부하 테스트 스크립트 완성 (Rate Limiter 검증) | 🟡 MED | 1일 |
| S8-T3 | OWASP Dependency-Check Gradle 플러그인 | 🟡 MED | 0.5일 |
| S8-T4 | SonarQube 연동 (정적 분석) | 🟢 LOW | 1일 |
| S8-T5 | Docker 멀티스테이지 빌드 최적화 | 🟢 LOW | 1일 |
| S8-T6 | Grafana 알림 채널 설정 (Slack/이메일) | 🟢 LOW | 0.5일 |

---

## 코드 품질 분석 및 제언

> **비교 기준**: Keycloak 25 (오픈소스 OIDC SoR), Spring Authorization Server 1.3, NIST SP 800-63B

### 1. 아키텍처 수준 평가

#### 잘 된 부분 ✅

| 항목 | 평가 | 근거 |
|------|------|------|
| **SoR 책임 분리** | ⭐⭐⭐⭐⭐ | Q-Sign/Q-IM/IdO 3축 분리는 Keycloak의 단일 서버 모델보다 확장성 우월 |
| **Outbox 패턴** | ⭐⭐⭐⭐☆ | Transactional Outbox → Kafka 이중 쓰기 문제 방지. 상용 수준 |
| **AES 키 버전 로테이션** | ⭐⭐⭐⭐☆ | `v{n}.{iv}.{ct}` 포맷 + 3계층 캐시는 HashiCorp Vault의 Transit Secret Engine 설계와 유사 |
| **Rate Limiter (Redis Lua)** | ⭐⭐⭐⭐☆ | 슬라이딩 윈도우 Lua 스크립트는 Stripe/GitHub API 수준의 정밀도 |
| **EDA 토픽 설계** | ⭐⭐⭐⭐☆ | DLQ 분리, Compacted 토픽, 보존 기간 차별화 — 상용 패턴 적용 |

#### 개선 필요 사항 ⚠️

**[가장 시급] 운영 키 관리 아키텍처**

현재 방식:
```java
// ❌ KeyVersionRegistry — DB key_material_encrypted 컬럼에 키 재료 직접 저장
// 운영 환경에서 KMS 없이 사용 시 DB 유출 = 암호화 무력화
"[ENCRYPTED_BY_KMS:" + keyBase64.substring(0, 8) + "...]"
```

상용 수준 목표 (HashiCorp Vault Transit / AWS KMS):
```
애플리케이션 → Vault Transit API → 봉인된(envelope) 키 재료 반환
DB에는 키 재료 절대 미저장 — KMS가 유일한 키 소지자
```

**권고사항**: `key_material_encrypted` 컬럼을 실제 KMS envelope encryption으로 대체. Vault Java SDK 또는 AWS KMS SDK 연동이 프로덕션 Go-Live 조건.

---

**[중요] 테스트 전략 — 상용 프레임워크 대비**

| 구분 | 현황 | Spring Authorization Server 기준 | 목표 |
|------|------|----------------------------------|------|
| 단위 테스트 | 365개 (16파일) | 2,000개+ | 600개+ |
| 통합 테스트 | 0개 | Testcontainers 100개+ | 50개+ |
| E2E 테스트 | 수동 agency-stub | Playwright/Selenium | 자동화 필요 |
| 커버리지 | ~55% | ~80% | 70%+ |
| 뮤테이션 테스트 | 없음 | PIT Mutation 적용 | 선택적 적용 |

---

**[중요] 관측 가능성(Observability) — SRE 관점**

현재 Micrometer 메트릭은 `AgencyRateLimiter`에만 적용. 상용 수준을 위해 필요한 핵심 메트릭:

```java
// 필요한 비즈니스 메트릭 (Micrometer Counter/Timer)
handoff.issue.total{result="success"|"policy_fail"|"rate_limit"}
handoff.verify.total{result="success"|"expired"|"tampered"}
handoff.crypto.encrypt.total{version="v1"|"v2"}   // Sprint 5에서 준비만, 미등록
webhook.dispatch.total{status="success"|"failed"|"dlq"}
qim.ci.lookup.duration_seconds{result="hit"|"miss"}
```

**권고사항**: Sprint 6 T5에서 핵심 5개 메트릭 등록. Grafana 대시보드의 `handoff_crypto_encrypt_total` 패널은 이 메트릭 등록 후 즉시 활성화됨.

---

### 2. 코드 수준 평가

#### 잘 된 부분 ✅

| 패턴 | 현황 | 평가 |
|------|------|------|
| **상수시간 비교** | `MessageDigestUtil.safeEquals()` 전 서명 비교에 적용 | Timing attack 방어 — NIST 권고 준수 |
| **GCM AAD 바인딩** | `ticketId`를 AAD로 GCM에 바인딩 | 재전송 공격 방지 — 상용 수준 |
| **UUID v7** | RFC 9562 준수, `TimeBasedEpochGenerator` | 단조증가 + 스레드 안전 — PostgreSQL 인덱스 최적화 |
| **PolicyEngine 분리** | 상태·수준·속성·점검 4가지 정책 분리 | Spring Authorization Server의 `AuthorizationManager` 패턴과 유사 |
| **전략 패턴 (HandoffStrategy)** | `DIRECT`, `L1`, `L2` 전략 분리 | OCP 준수, 신규 전략 추가 시 기존 코드 무변경 |

#### 개선 필요 사항 ⚠️

**[아키텍처] `HandoffCryptoService` → `KeyVersionRegistry` 의존 방향**

현재:
```
HandoffCryptoService → KeyVersionRegistry → Redis + DB
```
문제: `KeyVersionRegistry`가 Redis/DB를 직접 다루어 단위 테스트 시 Mock 필요.

상용 대안 (Spring Authorization Server 방식):
```
CryptoProvider (interface) ← HandoffCryptoService
    ↑ VaultCryptoProvider (운영)
    ↑ EnvCryptoProvider   (개발/테스트)
```
**권고**: `CryptoKeyProvider` 인터페이스 도입으로 교체 가능성(swappability) 확보.

---

**[보안] Grace Period 복호화 허용 로직**

```java
// 현재: active=FALSE 후 grace_until 기간 동안 복호화 허용
"WHERE key_type = ? AND key_version = ? " +
"AND (active = TRUE OR (grace_until IS NOT NULL AND grace_until > NOW()))"
```

문제: `grace_until` 만료 즉시 해당 키로 암호화된 기존 티켓 복호화 불가 → 사용자 오류.

**권고**: 티켓 발급 시각 기준 Grace Period 계산 필요:
```sql
-- 티켓 TTL(3시간)과 Grace Period(24시간) 중 더 긴 쪽 선택
grace_until = MAX(ticket.issued_at + 3h, key.deactivated_at + 24h)
```

---

**[운영] 분산 환경 키 캐시 무효화**

```java
// KeyVersionRegistry.evictCache() — 인메모리 캐시만 초기화
// 문제: 다중 IdO 인스턴스 환경에서 다른 인스턴스의 캐시는 무효화 안 됨
```

**권고**: Redis Pub/Sub으로 캐시 무효화 이벤트 브로드캐스트:
```java
// 로테이션 완료 후
redisTemplate.convertAndSend("ido:crypto:cache-evict", newVersion);
// 각 인스턴스 @EventListener로 evictCache() 호출
```

---

**[FE] TypeScript 타입 안전성**

현재 `api/client.ts`의 제네릭 사용이 일부 `any` 타입으로 처리됨. 상용 수준 FE(카카오, 토스)는 Zod schema validation + strict TypeScript(`strict: true`)를 사용.

**권고**: API 응답 타입에 Zod 스키마 도입:
```typescript
const HandoffTicketSchema = z.object({
  ticketId: z.string().uuid(),
  expiresAt: z.string().datetime(),
  // ...
});
```

---

### 3. 종합 상용화 준비도 평가

| 항목 | 현재 수준 | 상용 기준 | 갭 |
|------|----------|---------|-----|
| **보안 핵심** | ⭐⭐⭐⭐☆ | ⭐⭐⭐⭐⭐ | KMS 연동, mTLS |
| **가용성** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | 다중 인스턴스 캐시 무효화, Health API 완성 |
| **관측 가능성** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | 비즈니스 메트릭 미등록 |
| **테스트** | ⭐⭐⭐☆☆ | ⭐⭐⭐⭐⭐ | 통합 테스트 0개, 커버리지 55% |
| **배포 자동화** | ⭐⭐☆☆☆ | ⭐⭐⭐⭐⭐ | CI/CD 없음, 수동 배포 |
| **컴플라이언스** | ⭐⭐⭐⭐☆ | ⭐⭐⭐⭐⭐ | GDPR 파기 완료, PCI-DSS 키 관리 부족 |
| **아키텍처 품질** | ⭐⭐⭐⭐☆ | ⭐⭐⭐⭐⭐ | 전략 패턴, Outbox, EDA 상용 수준 |

**총평**: 아키텍처 설계와 핵심 보안 구현은 상용 수준에 근접. 테스트 자동화, KMS 연동, CI/CD 구축이 프로덕션 Go-Live를 위한 3대 필수 과제.

---

## 코딩 컨벤션

- **패키지**: `kr.go.smes.{module}` (예: `kr.go.smes.ido.handoff`)
- **에러코드**: `PlatformErrorCode` 열거형 사용 — 하드코딩 문자열 금지
- **PII 보호**: CI 원문, 개인명 원문, 전화번호 원문 로그 금지
- **감사 로그**: `platform.audit.log` 토픽 — PII 원문 미포함 JSON만 허용
- **Outbox 패턴**: 모든 Kafka 게시는 Transactional Outbox 경유
- **상수시간 비교**: 서명·해시 비교는 `MessageDigestUtil.safeEquals()` 사용
- **암호화 출력**: 항상 버전 접두사 `v{n}.{iv}.{ct}` 형식 사용
- **UUID**: 비즈니스 ID는 반드시 `UuidV7.generate()` 사용 (UUID v4 금지)

---

## 문서 디렉토리

```
docs/
├── spec/                              # 정밀 분석 기반 기술 명세 (v1.9.3)
│   ├── 00-index.md                   # 전체 조감도 + 빠른 참조 카드
│   ├── 01~09-*.md                    # 시스템·아키텍처·모듈·API·DB·Kafka·보안
│   └── 09-gap-and-roadmap.md         # 미구현 현황·Sprint 계획·기술 부채
│
├── development/                       # 개발 진행 상태 문서
│   ├── 12-implementation-gaps.md     # 미구현 항목 우선순위별 상세
│   └── 13-development-history.md     # v1.0~v2.0.0 버전별 변경 이력
│
├── 2026-05-09_v194_gap_implementation_plan.md  # Sprint 1~5 상세 플랜
└── 2026-05-08_production_development_plan.md   # 운영 전환 계획
```

---

*GitHub: [HipsterMIN/integration-sso](https://github.com/HipsterMIN/integration-sso)*  
*PR #39 (OPEN): [Sprint 5 — AES 키 로테이션 + 모니터링 인프라](https://github.com/HipsterMIN/integration-sso/pull/39)*
