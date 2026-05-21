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
  내부망 (onepass-net 172.20.0.0/24)                  │
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

### ADR-002: onepass-fe 순수 React SPA 전환

- **결정**: Spring Boot BFF 제거 → ido가 BFF 역할 흡수
- **이유**: 배포 복잡성 감소, ido에서 CORS·feSession 통합 관리
- **영향**: onepass-fe는 정적 파일만 서빙 (Nginx or webpack-dev-server)

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

---

## 5. 네트워크 구성

### 5.1 Docker 네트워크

```
네트워크: onepass-net (bridge, 172.20.0.0/24)
  참여 서비스: postgres, mariadb, redis, zookeeper, kafka,
               keycloak, q-sign, q-im, ido, agency-stub,
               onepass-fe(nginx), prometheus, grafana, loki
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
| onepass-fe (nginx) | 3001 | 80 | |
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
