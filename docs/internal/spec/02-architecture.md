# 02. 시스템 아키텍처 (Architecture)

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09

---

## 1. 전체 시스템 구성도

```
══════════════════════════════════════════════════════════════════
  외부망 (External Network)
══════════════════════════════════════════════════════════════════

  ┌────────────────────────────────┐  ┌──────────────────────────────────┐
  │    최종 사용자 (브라우저 / 앱)    │  │     유관기관 시스템 (외부망)           │
  │                                │  │                                  │
  │  [개발] React dev :3000         │  │  agency-stub :8084  ← PoC 전용   │
  │    webpack proxy → ido:8083    │  │  (실제 유관기관을 시뮬레이션)        │
  │  [운영] Nginx :3001             │  │                                  │
  │    /api/** → ido:8083          │  │  ① POST /api/v1/handoff/issue    │
  └──────────┬─────────────────────┘  │  ② POST /api/v1/handoff/verify   │
             │ HTTPS                  │  ③ GET  /api/v1/agency/events    │
             │ /api/v1/fe-session/**  │  ④ POST /api/v1/webhook/inbound  │
             │ /api/v1/handoff/**     └──────────────┬───────────────────┘
             │ /api/v1/oidc/**                        │ HTTPS (공개 API만)
             │                                        │
══════════════════════════════════════════════════════╪══════════════════
  내부망 (idem-net 172.20.0.0/24)                  │
══════════════════════════════════════════════════════╪══════════════════
             │                                         │
             ▼                                         ▼
  ┌──────────────────────────────────────────────────────────────────┐
  │  ido  :8083  정책 오케스트레이터 + FE BFF                          │
  │                                                                  │
  │  [FE BFF]                          [기관향 공개 API]              │
  │  /api/v1/fe-session/**             /api/v1/handoff/**            │
  │  feSessionId 쿠키 발급/갱신          /api/v1/agency/events        │
  │  ReturnUrl 화이트리스트 검증          ↑ HandoffAgencyKeyInterceptor │
  │  platform.session.advisory 소비     X-Agency-Key SHA-256 검증     │
  │                                                                  │
  │  [IdP 브로커]                       [Q-IM SP 수신]               │
  │  /api/v1/broker/**                 /api/qim/sp/v1/**            │
  │  /api/v1/oidc/**                   AES 복호화, instMbrId 매핑    │
  │  /api/internal/v1/oidc/complete                                  │
  │                                                                  │
  │  [Webhook Push]                    [Admin]                       │
  │  WebhookDispatcherService          /api/v1/admin/agencies/**     │
  │  WebhookDispatchOutboxRelay                                      │
  └──────────────────┬───────────────────────────────────────────────┘
                     │ HTTP (내부망)
         ┌───────────┴──────────────┐
         ▼                          ▼
  ┌─────────────┐          ┌────────────────┐
  │ q-sign:8081 │          │   q-im:8082    │
  │             │          │                │
  │ 인증 SoR    │          │   식별 SoR     │
  │ Keycloak    │          │  qim_user      │
  │ OIDC/PKCE  │          │  CI 암호화     │
  │ AuthResult  │          │  Outbox        │
  └──────┬──────┘          └───────┬────────┘
         │ Outbox Relay             │ Outbox Relay
         └──────────┬──────────────┘
                    ▼
    ┌─────────────────────────────────────────────────┐
    │                  Apache Kafka                    │
    │                                                  │
    │  qsign.auth.events          (.dlq)               │
    │  ido.handoff.events         (.dlq)               │
    │  platform.session.advisory  (.dlq)               │
    │  platform.audit.log                              │
    │  qim.user.events            (.dlq)               │
    │  qim.user.snapshot          (Compacted)          │
    │  qim.sp.member.events       (.dlt)               │
    └──────────────┬──────────────────────────────────┘
                   │
    ┌──────────────┼──────────────┐
    ▼              ▼              ▼
PostgreSQL 16   MariaDB 11    Redis 7.2
(qsign + ido)  (qim 전용)    (세션·캐시)

    ┌──────────────┐
    │  Keycloak 24 │ → 카카오/네이버 OIDC 연동
    └──────────────┘
```

---

## 2. EDA (Event-Driven Architecture) 설계

### 2.1 Transactional Outbox 패턴

모든 서비스는 DB 트랜잭션 내에서 Outbox 테이블에 이벤트를 적재하고,  
별도 Relay 스케줄러가 Kafka로 at-least-once 발행한다.

```
[서비스 로직] ──트랜잭션──┐
                          ├─► DB 비즈니스 레코드 INSERT
                          └─► Outbox 테이블 INSERT
                                     ↓ (별도 스케줄, 500ms)
                              Outbox Relay
                                     ↓ Kafka.send()
                              Kafka 토픽
                                     ↓
                              소비자 (다른 서비스)
```

| 서비스 | Outbox 테이블 | Relay 클래스 |
|--------|--------------|------------|
| Q-Sign | `qsign.outbox` | `OutboxRelay` (@Scheduled 500ms) |
| Q-IM | `qim.outbox` | `OutboxServiceImpl` + `OutboxRepositoryImpl` |
| IdO | `ido.ido_outbox` | `IdoOutboxRelay` (@Scheduled) |
| IdO | `ido.webhook_dispatch_outbox` | `WebhookDispatchOutboxRelay` (@Scheduled 500ms) |

### 2.2 이벤트 흐름 전체

```
[Q-Sign] 인증 완료
    └─► qsign.auth.events
             └─► [IdO] QsignAuthEventConsumer
                       └─► Handoff 발급 트리거
                       └─► qim.user 상태 확인 (QimClient HTTP)

[IdO] Handoff 발급
    └─► ido.handoff.events
             └─► [IdO] HandoffEventConsumer
                       └─► WebhookDispatcherService
                             └─► webhook_dispatch_outbox INSERT
                                       └─► WebhookDispatchOutboxRelay
                                               └─► HTTPS POST → 기관

[Q-IM] 회원 이벤트 (상태 변경, 정지, 탈퇴)
    └─► qim.user.events
             ├─► [IdO] QimEventConsumer
             │         └─► UserStatusCache 갱신 (Redis)
             │         └─► needsSync=true → QimClient.getUserStatus() Selective Pull
             └─► [Q-Sign] QimUserEventConsumer
                          └─► 6단계 멱등 처리
                          └─► USER_SUSPENDED/WITHDRAWN → auth_lock 잠금
                          └─► USER_UPDATED(needsSync) → auth_lock 해제

[Q-IM] 스냅샷 발행 (10개 이벤트마다)
    └─► qim.user.snapshot (Compacted Topic)
             └─► (소비자 확장 예정)

[IdO] 세션 Advisory
    └─► platform.session.advisory
             └─► [IdO] FeAdvisoryConsumer
                       └─► feSessionId Redis 무효화

[IdO] 감사 로그
    └─► platform.audit.log
             + ido.audit_log DB 이중 저장 (법적 2년 보존)
```

---

## 3. 보안 아키텍처

### 3.1 계층별 인증

```
클라이언트               IdO                      내부 서비스
─────────               ───                      ──────────
사용자 브라우저  ─feSessionId 쿠키─► FeSessionController
유관기관 시스템  ─X-Agency-Key ──► HandoffAgencyKeyInterceptor
                                   → SHA-256(rawKey) vs DB
                                   → 상수시간 비교 (MessageDigest.isEqual)
Q-Sign 내부 ──X-Internal-Sig ──► InternalSigVerifier
                                   → HMAC-SHA256 + ±60초 타임스탬프
```

### 3.2 Handoff Ticket 암호화

```
발급 (HandoffCryptoService):
  plaintext = JSON(payload)
  key = AES-256-GCM 키 (IDO_HANDOFF_AES_KEY)
  ciphertext = AES-256-GCM-Encrypt(plaintext, key, randomIV)
  signature = HMAC-SHA256(ciphertext, IDO_HANDOFF_HMAC_SECRET)
  ticket = Base64(ciphertext + iv + signature)

검증:
  signature 검증 → ciphertext 복호화 → payload 파싱
  1회성 소비: Redis SET NX (ticketId → consumed)
```

### 3.3 HandoffStrategy 4종

```
DIRECT:       Ticket만 발급, 기관이 직접 verify API 호출
BRIDGE:       POST {bridgeEndpoint} — Bridge 서버 경유
INTERNAL_SSO: POST {ssoDomain}/internal/sso-session — SSO 쿠키 사전 등록
APACHE_GATE:  POST {apacheGateEndpoint} — mod_auth_openidc 헤더 사전 주입
              Headers: X-Remote-User, X-Auth-Level, X-Handoff-Token, X-Session-Expiry
```

---

## 4. 아키텍처 결정 기록 (ADR)

### ADR-001: Q-IM DB → MariaDB 선택

- **결정**: Q-IM은 PostgreSQL 대신 MariaDB 사용
- **이유**: 기존 정부기관 시스템 호환성, 운영 팀 숙련도
- **영향**: IdO·Q-Sign은 PostgreSQL 유지, Q-IM만 별도 MariaDB

### ADR-002: idem-console 순수 React SPA 전환

- **결정**: Spring Boot BFF 제거 → ido가 BFF 역할 흡수
- **이유**: 배포 복잡성 감소, ido에서 CORS·feSession 통합 관리
- **영향**: idem-console는 정적 파일만 서빙 (Nginx or webpack-dev-server)

### ADR-003: Transactional Outbox 전 서비스 적용

- **결정**: 모든 Kafka 발행은 Outbox 테이블 경유
- **이유**: DB 트랜잭션과 Kafka 발행의 원자성 보장 (at-least-once)
- **트레이드오프**: 지연 증가(500ms), 중복 발행 가능 → 소비자 측 멱등 처리 필수

### ADR-004: 유관기관 외부망 격리

- **결정**: 유관기관은 Kafka·내부 DB 직접 접근 불가, IdO 공개 API만 사용
- **이유**: 보안 경계 명확화, 기관별 독립성 보장
- **구현**: Webhook Push + HTTP 폴링 이중 채널 제공

### ADR-005: HandoffStrategy 전략 패턴 (OCP)

- **결정**: 연동 유형별 전략을 별도 Spring Bean으로 분리
- **이유**: 새 연동 유형 추가 시 기존 코드 수정 없이 Bean 추가만으로 확장
- **구현**: `HandoffStrategyFactory`가 `List<HandoffStrategy>` 자동 수집

### ADR-006: DLQ (Dead Letter Queue) 전 토픽 적용

- **결정**: 모든 Kafka 컨슈머에 DLQ 토픽 연결 (`*. dlq` 또는 `*.dlt`)
- **이유**: 처리 실패 이벤트 유실 방지, 운영 재처리 경로 확보
- **구현**: `KafkaTopicConfig.defaultErrorHandler()` + `DeadLetterPublishingRecoverer`

### ADR-008: FE 군(群) ↔ IdO 단일 채널 원칙

> **본 ADR은 ADR-002 의 확장본이다.**
> ADR-002 가 "BFF 역할을 IdO 가 흡수한다" 를 선언했다면,
> ADR-008 은 "BFF 뿐 아니라 게이트웨이까지 IdO 가 단일하게 전담한다" 와
> "프론트엔드는 1 개로 한정되지 않고 군(群) 으로 확장될 수 있다" 를 함께 헌법화한다.

#### 3단 명제 (전제)

본 아키텍처의 모듈 책임 분해는 다음 3 단으로 읽는다.

1. **책임의 종류** — 시스템에는 두 종류의 "사용자-대상 화면" 책임이 존재한다.
   - (a) **end-user 화면**: 일반 신청자/대표자/실무자가 사용하는 발급·열람·동의 화면
   - (b) **운영·관리 화면**: 운영자·고객지원·감사 담당이 사용하는 모니터링·정책·심사 화면
   - (단, Q-IM 자체의 관리 콘솔은 03-C §6.5 에 따라 **영구 금지** 됨 — 본 ADR 의 (b) 는
     Q-IM 외부에서, IdO 가 부여한 권한 등급 하에 별도 FE 가 호스트한다.)
2. **모듈 군(群)** — 위 책임은 단일 모듈이 아닌 **프론트엔드 군(group)** 으로 구현된다.
   - 현재: `idem-console` (end-user 화면 담당)
   - 향후 도입 가능: `onepass-admin`, `idem-support`, `onepass-audit` 등
   - (※ "현재 idem-console 가 유일한 FE 다" 는 **현시점 사실**일 뿐, **설계상 제약이 아니다**.
     설계는 처음부터 N 개 FE 를 전제로 한다.)
3. **단일 게이트웨이** — 군에 속한 모든 FE 는 **IdO 단일 채널** 로만 백엔드와 통신한다.
   - IdO 가 BFF + Gateway + Orchestrator 역할을 통합 수행한다.
   - Q-IM / Q-Sign / agency-stub 은 **어떤 FE 에서도 직접 호출 불가**.

#### 결정 (Decision)

- **결정**: 모든 프론트엔드 모듈(현재 `idem-console`, 향후 도입될 `onepass-admin` 등 일체)은
  **IdO 단일 채널** 을 통해서만 백엔드(Q-IM / Q-Sign / agency-stub / Keycloak 등)와 통신한다.
  FE 에서 Q-IM 등 백엔드를 직접 호출하는 어떠한 경로도 허용하지 않는다.
- **단일성의 정의**:
  - FE 의 HTTP 클라이언트(예: axios) `baseURL` 인스턴스는 **정확히 1 개** (IdO) 만 존재한다.
  - 구 `extInstance` (Q-IM 직접 호출) 는 `@deprecated` 처리되어 있으며,
    Phase 2 에서 코드 레벨 제거 + 환경 변수 rename(`BE_API_*` → `IDO_API_*`) 수행 예정.

#### 이유 (Rationale)

- **(a) Q-IM 책임 헌장 정합성**: 03-C §6 (사용자-대상 화면 제거) 과 §6.5 (관리자 페이지 영구 금지)
  를 시스템 경계 차원에서 강제하는 유일한 방법은 "FE 가 Q-IM 을 직접 호출하지 않는" 구조이다.
- **(b) 단일 정책 적용점**: 인증·인가·감사 로그·rate limit·CORS·CSRF·세션 관리·외부키 보호 등
  보안·정책 통제를 **1 개 모듈(IdO)** 에서 일관되게 적용 가능. N 개 FE 가 N 개 백엔드를 직접
  호출한다면 정책 적용점이 N×M 개로 폭발한다.
- **(c) BE 보호 불변식 (Invariant under N-FE expansion)**: FE 가 1 개에서 N 개로 늘어나도
  Q-IM·Q-Sign 의 노출 표면은 변하지 않는다. 신규 FE 도입은 IdO 에 "Origin 추가 + API 키 발급 +
  쿠키 도메인 정책" 만 적용하면 되고, BE 코드는 무영향.
- **(d) 보안 키 누출 방지**: Q-IM 의 `X-Ext-Api-Key` 등 외부 호출 키는 IdO 가 서버 측에서
  주입(`ExtProxyController`)하며, FE 번들에는 절대 노출되지 않는다.

#### 영향 (Impact)

- **FE 측 제약**:
  - axios baseURL = 1 개 (IdO) — 다중 baseURL 금지.
  - `EXT_API_*` / `Q_IM_*` / `Q_SIGN_*` 등 BE 모듈을 직접 가리키는 환경변수 FE 에 두지 않음.
  - 모든 외부 호출은 `/api/**` (IdO) → 필요 시 `/api/ext/**` (IdO 의 forward proxy) 경유.
- **IdO 측 책임 확장**:
  - **BFF**: `feSessionId` 쿠키 발급/검증, returnUrl 화이트리스트 (`FeSessionController`).
  - **Gateway**: `/api/ext/**` → Q-IM forward proxy, 서버측 `X-Ext-Api-Key` 주입,
    Q3=B 컴플라이언스(`/api/ext/ci/**` 차단) (`ExtProxyController`).
  - **Orchestrator**: 다단 흐름(`q-sign-init → quick-status → cert-status → handoff`)을
    IdO 가 일괄 지휘 (03-D 참조).
- **운영 측 효과**:
  - 신규 FE 추가 비용: BE 변경 0, IdO 에 CORS Origin 1 줄 + API 키 1 개 추가만으로 가능.
  - 모니터링 단일점: 모든 FE 트래픽이 IdO 를 거치므로 access log·감사 로그가 일원화.

#### 인증 모델 — Option A (현재 기본) / Option B (확장 경로)

| 항목 | Option A: feSession + API Key 단일 모델 (현재) | Option B: 인증 등급 분리 (향후 onepass-admin 도입 시) |
|------|-----------------------------------------------|---------------------------------------------------|
| 대상 FE | idem-console (end-user) | idem-console + onepass-admin (+ ...) |
| FE→IdO 인증 | `feSessionId` 쿠키 + `X-BE-API-Key` | (end-user) feSession 유지 / (admin) Keycloak admin-realm OIDC + mTLS + step-up MFA |
| API 키 스코프 | 단일 키 | FE 별 분리 키, 스코프(read/write/admin) 차등 |
| 경로 분리 | `/api/v1/**` | `/api/v1/**` (end-user) ↔ `/api/admin/v1/**` (admin) |
| 감사 로그 등급 | 표준 | admin 호출은 상시 100% 감사 + 알람 임계치 별도 |
| 이행 트리거 | — | `onepass-admin` 모듈 첫 도입 PR 의 선행 조건 |

> Option A → Option B 전환은 **IdO 내부 정책 추가**만으로 가능하며, **FE 군 단일 채널 원칙은 불변**.
> 즉 본 ADR 은 인증 정책의 진화에 대해 **forward-compatible** 하다.

#### onepass-admin (또는 임의 신규 FE) 도입 시 체크리스트

향후 운영·관리 FE 가 도입될 때, **IdO 측** 에 다음을 사전 적용해야 한다.
(BE 측 변경은 원칙적으로 없음 — 본 ADR 의 "BE 보호 불변식" 효과)

1. **API 키 스코핑** — idem-console 와 분리된 별도 API 키 발급, 스코프(`admin:*`) 차등 부여.
2. **CORS N-origin** — IdO `cors.allowed-origins` 에 신규 FE Origin 추가
   (`onepass-admin.smes.go.kr` 등). 와일드카드 금지.
3. **쿠키 도메인 정책** — admin 쿠키는 `Domain=admin.smes.go.kr; Path=/; SameSite=Strict`
   로 end-user 쿠키와 **물리적 격리**. 공유 도메인 금지.
4. **admin path prefix 분리** — IdO 내 `/api/admin/v1/**` 컨트롤러군 신설.
   end-user 경로와 동일 컨트롤러 재사용 금지(권한 등급 혼선 방지).
5. **인증 등급 분리** — Option B 채택. Keycloak `admin-realm` + step-up MFA + (선택) mTLS.
6. **감사 로그 강화** — admin 경로 호출은 100% 감사 + 비정상 패턴 실시간 알람.
   (현재 표본 감사로 충분한 end-user 와 별도 정책)

#### 연계 ADR / 참조

- **ADR-002 (idem-console 순수 React SPA)** — 본 ADR 의 직접 선조. 본 ADR 은 ADR-002 를
  "BFF 흡수" 에서 "BFF + Gateway 흡수, 그리고 FE 의 N 개화" 로 확장한다.
- **03-C §6, §6.5 (Q-IM 책임 헌장 / 관리자 페이지 영구 금지)** — 본 ADR 이 시스템 경계로
  강제하는 대상.
- **03-F (idem-console 데이터 흐름 정본)** — 본 ADR 의 idem-console 측 구현 단면.
- **`ExtProxyController` / `FeSessionController`** — 본 ADR 의 IdO 측 구현 단면.

---

## 5. 네트워크 구성

### 5.1 Docker 네트워크

```
네트워크: idem-net (bridge, 172.20.0.0/24)
  참여 서비스: postgres, mariadb, redis, zookeeper, kafka,
               keycloak, q-sign, q-im, ido, agency-stub,
               idem-console(nginx), prometheus, grafana, loki
```

### 5.2 서비스 포트 맵 (호스트:컨테이너)

| 서비스 | 호스트 포트 | 컨테이너 포트 | 비고 |
|--------|-----------|-------------|------|
| MariaDB | 3306 | 3306 | q-im DB |
| PostgreSQL | 5432 | 5432 | qsign + ido DB |
| Redis | 6379 | 6379 | 세션·캐시 |
| Zookeeper | 2181 | 2181 | |
| Kafka | 9092 | 9092 | |
| Kafka JMX | 9999 | 9999 | |
| Keycloak | 8085 | 8080 | onepass realm |
| Keycloak Admin | 8090 | 8080 | |
| Kafka UI | 5540 | 5540 | |
| pgAdmin | 5050 | 80 | |
| Kafka Connect | 8088 | 8080 | |
| Q-Sign | 8081 | 8081 | |
| Q-IM | 8082 | 8082 | |
| Kafdrop | 8091 | 8080 | |
| IdO | 8083 | 8083 | |
| idem-console (nginx) | 3001 | 80 | |
| agency-stub | 8084 | 8084 | |
| Prometheus | 9090 | 9090 | |
| Grafana | 3002 | 3000 | |
| Loki | 3100 | 3100 | |

---

## 6. 모니터링 스택

```
애플리케이션 → Spring Actuator (/actuator/prometheus)
                      ↓
              Prometheus (9090) ← scrape
                      ↓
              Grafana (3002)
                      │
              Loki (3100) ← Promtail (로그 수집)
```

| 구성요소 | 역할 |
|----------|------|
| Prometheus | 메트릭 수집 (Actuator + JVM + Kafka) |
| Grafana | 대시보드 시각화 |
| Loki | 로그 집계 |
| Promtail | 컨테이너 로그 → Loki 전송 |

---

*다음: [03a-module-platform-common.md](03a-module-platform-common.md)*  
*인덱스: [00-index.md](00-index.md)*
