# 01. 시스템 개요 (System Overview)

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09

---

## 1. 프로젝트 목적

**OnePass Integration-SSO**는 **중소벤처기업부 중기원패스(OnePass) 플랫폼**의 통합인증 SSO 및 아이덴티티 관리 시스템 PoC 구현체다.

### 1.1 핵심 목표

| 목표 | 설명 |
|------|------|
| **통합 SSO 브로커링** | 카카오·네이버(OIDC 표준), PASS·금융인증서·GPKI(비OIDC) 등 다양한 외부 IdP를 단일 인터페이스로 추상화 |
| **유관기관 연동 단일 창구** | N개 정부·공공기관 시스템이 OnePass를 통해 인증을 위임 — 기관은 내부 IdP 구현 불필요 |
| **아이덴티티 통합 관리** | 개인/기업 회원의 통합 식별자(qimUserId), DI, CI 해시를 Q-IM 단일 원장으로 관리 |
| **보안 중심 설계** | AES-256-GCM 암호화, HMAC-SHA256 서명, PKCE(RFC 7636), Redis Rate Limit, W3C traceparent |
| **EDA 기반 느슨한 결합** | Transactional Outbox + Kafka로 서비스 간 직접 호출 최소화 |

### 1.2 시스템 경계

```
[사용자 브라우저]
     ↕ HTTPS
[onepass-fe (React SPA)]  ←→  [IdO (BFF + 오케스트레이터)]
                                     ↕ HTTP (내부망)
                           [Q-Sign (인증 SoR)]  [Q-IM (식별 SoR)]
                                     ↕ Kafka
                           [유관기관 시스템] ← Webhook Push 또는 Polling
```

**외부망 경계**: 유관기관은 IdO의 공개 HTTPS API만 접근 가능. Kafka·DB 직접 접근 불가.

---

## 2. 기술 스택

### 2.1 백엔드 공통

| 기술 | 버전 | 적용 범위 |
|------|------|----------|
| **Java** | 21 LTS | 전 모듈 |
| **Spring Boot** | 3.5.9 | q-sign, q-im, ido, agency-stub |
| **Gradle** | 9.5.0 | 멀티모듈 빌드 (`onepass-platform`) |
| **Spring Data JPA** | BOM 관리 | q-sign(PostgreSQL), q-im(MariaDB), ido(PostgreSQL) |
| **Spring Kafka** | BOM 관리 | 전 서비스 |
| **Spring Data Redis** | BOM 관리 | ido, q-im |
| **Flyway** | 11.8.0 | DB 마이그레이션 |
| **Resilience4j** | 2.2.0 | Circuit Breaker, Retry |
| **JJWT** | 0.12.6 | JWT 서명 검증 (Keycloak 콜백) |
| **Lombok** | 최신 안정 | 전 모듈 |

### 2.2 프론트엔드 (`idem-console/frontend/`)

| 기술 | 버전 |
|------|------|
| React | 18.3 |
| TypeScript | 5.4 |
| Webpack | 5.92 |
| Ant Design | 5.18 |
| React Router | v6 |
| TanStack Query | v5 |
| Axios | 1.7 |
| Zustand | 4.5 |
| Node.js (빌드) | 20.14 LTS |
| Yarn | 1.22 |

### 2.3 인프라

| 서비스 | 이미지 | 용도 |
|--------|--------|------|
| PostgreSQL | `postgres:16-alpine` | q-sign(qsign 스키마) + ido(ido 스키마) |
| MariaDB | `mariadb:11` | q-im 전용 |
| Redis | `redis:7.2-alpine` | FE 세션, Idempotency-Key, agencyMeta 캐시 |
| Kafka | `confluentinc/cp-kafka:7.6.1` | 전 서비스 이벤트 버스 |
| Zookeeper | `confluentinc/cp-zookeeper:7.6.1` | Kafka 코디네이터 |
| Keycloak | `quay.io/keycloak/keycloak:24` | OIDC IdP 브로커 |
| Nginx | `nginx:1.27-alpine` | onepass-fe 운영 서빙 |

---

## 3. 모듈 책임 분리 (4+1 축 모델)

### 3.1 모듈 개요

| 모듈 | 패키지 루트 | 포트 | Java 파일 수 | 역할 |
|------|------------|------|-------------|------|
| `platform-common` | `kr.go.smes.common` | — | 16 | 공통 도메인·이벤트·에러코드 라이브러리 |
| `q-sign` | `kr.go.smes.qsign` | 8081 | 35 | **인증 SoR** — IdP 연동, AuthResult 기록, 잠금 정책 |
| `q-im` | `kr.go.smes.qim` | 8082 | 41 | **식별 SoR** — 회원 원장, CI 암호화, Snapshot |
| `ido` | `kr.go.smes.ido` | 8083 | 106 | **정책 오케스트레이터 + FE BFF** |
| `agency-stub` | `kr.go.smes.agency` | 8084 | 15 | PoC 유관기관 시뮬레이터 |
| `onepass-fe` | (React) | 3000/3001 | — | 순수 React SPA |

### 3.2 platform-common 제공 요소

```
kr.go.smes.common/
├── domain/
│   ├── AuthResult          # 인증 결과 (authResultId, authLevel, qimUserId, ...)
│   ├── HandoffTicket       # Handoff 티켓 (ticketId, expiresAt, authLevel, ...)
│   ├── HandoffPayload      # 검증 결과 페이로드 (APPROVED/REJECTED/HOLD)
│   ├── IdOAuthInput        # 비OIDC 인증 입력
│   └── UserStatus          # 사용자 상태 enum (ACTIVE/SUSPENDED/WITHDRAWN/...)
├── event/
│   ├── DomainEvent         # 기본 이벤트 (eventId, eventType, occurredAt)
│   ├── AuthEvent           # qsign.auth.events 페이로드
│   ├── HandoffEvent        # ido.handoff.events 페이로드
│   ├── SessionAdvisoryEvent # platform.session.advisory 페이로드
│   ├── UserEvent           # qim.user.events 페이로드 (TYPE_SNAPSHOT 등 10종)
│   ├── WebhookDispatchEvent # Webhook 발송 이벤트
│   └── AuditLogEvent       # platform.audit.log 페이로드
├── error/
│   ├── PlatformErrorCode   # 플랫폼 전역 에러코드 (E-IDO-xxx, E-QS-xxx, E-QIM-xxx)
│   ├── PlatformException   # 표준 예외
│   └── ErrorResponse       # 표준 오류 응답 DTO
└── util/
    └── CorrelationIdHolder # ThreadLocal 기반 X-Correlation-Id 전파
```

### 3.3 Q-Sign 책임

- **인증 SoR**: 모든 인증 이벤트의 원천 기록 (`qsign.auth_result` 테이블)
- **Keycloak OIDC 브로커링**: 카카오·네이버 등 소셜 IdP → Keycloak → Q-Sign
- **PKCE 지원**: RFC 7636 코드 챌린지/검증
- **인증 잠금**: `qsign.auth_lock` — USER_SUSPENDED/WITHDRAWN 시 전 세션 잠금
- **멱등 이벤트 컨슈머**: `qsign.processed_event` + `qsign.last_event_version` (GAP-QS-03)

### 3.4 Q-IM 책임

- **식별 SoR**: 통합 회원 원장 (`qim.qim_user`, `qim.user_profile`, `qim.auth_mean_mapping`)
- **CI 암호화**: AES-256-CBC 키 버전 관리 (`qim.ci_encryption_key`)
- **PII 마스킹**: 이름·전화 등 개인정보 마스킹 출력
- **DI 생성**: 기관별 고유 DI (연동 식별자) 생성
- **Transactional Outbox**: `qim.outbox` → `qim.user.events` Kafka 발행
- **Snapshot 발행**: 10개 이벤트마다 `qim.user.snapshot` Compacted Topic 발행 (GAP-QIM-05)

### 3.5 IdO 책임 (가장 복잡한 모듈 — 106 Java 파일)

| 기능 영역 | 주요 클래스 | 설명 |
|-----------|------------|------|
| **Handoff 발급/검증** | `HandoffController`, `HandoffServiceImpl` | AES-256-GCM 암호화 Ticket, 1회성 소비 |
| **HandoffStrategy** | `HandoffStrategyFactory` + 4개 전략 | DIRECT/BRIDGE/INTERNAL_SSO/APACHE_GATE |
| **정책 엔진** | `PolicyEngine`, `PolicyEngineImpl` | 최소 인증 수준, 점검 시간대, Rate Limit |
| **FE BFF** | `FeSessionController`, `FeSessionServiceImpl` | feSessionId 쿠키, Redis TTL |
| **IdP 브로커** | `BrokerController`, `ProviderRouter` | OIDC + 비OIDC 통합 브로커 |
| **Keycloak 브로커** | `KeycloakOidcService`, `KeycloakCallbackController` | OIDC Authorization Code + PKCE |
| **비OIDC 브로커** | `NonOidcBrokerController`, `NonOidcAuthService` | PASS·금융인증서·GPKI |
| **Webhook Push** | `WebhookDispatcherService`, `WebhookDispatchOutboxRelay` | HMAC-SHA256 서명, 지수 백오프 재시도 |
| **이벤트 폴링** | `AgencyEventController`, `AgencyEventQueryServiceImpl` | P1-06 (v1.9.3) |
| **Q-IM SP 수신** | `QimSpReceiverController`, `QimSpReceiverService` | AES 복호화, instMbrId 매핑 |
| **Rate Limit** | `AgencyRateLimiter` | Redis 슬라이딩 윈도우 |
| **감사 로그** | `AuditLogPublisher` | `platform.audit.log` Kafka + `ido.audit_log` DB |
| **Admin API** | `AgencyAdminController` | 기관 CRUD, API Key 로테이션 |
| **기관 이벤트 폴링** | `AgencyEventController`, `AgencyEventQueryServiceImpl` | `webhook_dispatch_outbox` 기반 폴링 |

### 3.6 agency-stub 책임

유관기관(외부 기관 시스템)의 완전한 동작을 재현하는 PoC 전용 Spring Boot 서비스.

| 기능 | 클래스 |
|------|--------|
| Ticket 발급 HTTP 클라이언트 | `IdoTicketClient` (Resilience4j CB+Retry) |
| Ticket 검증 HTTP 클라이언트 | `IdoVerifyClient` (Resilience4j CB+Retry) |
| 기관 세션 관리 | `AgencySessionService` (192-bit SecureRandom, SHA-256 저장) |
| Webhook 수신·검증 | `WebhookInboundController` (HMAC-SHA256 + ±5분 타임스탬프) |
| E2E 시뮬레이터 | `AgencySimulatorController` (3단계 자동 실행) |
| 이벤트 폴링 | `AgencyEventPollingController` |
| 헬스 진단 | `AgencyHealthController` |

---

## 4. 인증 흐름 요약

### 4.1 정상 흐름 (카카오 OIDC 기준)

```
1. 사용자 → onepass-fe → GET /api/v1/oidc/{provider}/authorize  (Q-Sign)
   └─ PKCE 챌린지 생성, Keycloak 인가 URL 반환

2. 사용자 → Keycloak → 카카오 로그인
   └─ Keycloak 콜백: GET /api/v1/oidc/keycloak/callback  (Q-Sign)
   └─ JWKS 검증 → AuthResult 저장 → qsign.auth.events 발행

3. IdO QsignAuthEventConsumer → 이벤트 수신
   └─ Q-IM 사용자 상태 확인 (QimClient.getUserStatus)
   └─ Handoff Ticket 발급 준비

4. 기관 → POST /api/v1/handoff/issue  (IdO)  ← X-Agency-Key 인증
   └─ PolicyEngine 검사 → AES-256-GCM 암호화 → Ticket 발급
   └─ HandoffStrategy.postIssue() (DIRECT/BRIDGE/INTERNAL_SSO/APACHE_GATE)
   └─ ido.handoff.events Kafka 발행 → WebhookDispatcherService → Outbox

5. 기관 → POST /api/v1/handoff/verify  (IdO)  ← X-Agency-Key 인증
   └─ Ticket 복호화·서명 검증 → 1회성 소비 → HandoffPayload 반환

6. 기관 내부 세션 발급 (agency-stub: AgencySessionService)
```

### 4.2 비OIDC 흐름 (PASS·금융인증서 등)

```
1. Q-Sign → POST /api/v1/broker/{provider}/nonoidc  (IdO)
   └─ NonOidcBrokerController → BrokerService → IdpBrokerService
   └─ 외부 IdP REST 호출 → IdpBrokerResult

2. IdO → POST /api/internal/v1/oidc/complete  (X-Internal-Sig 검증)
   └─ InternalSigVerifier → OidcCompleteController
   └─ Q-IM 사용자 조회/등록 → HandoffTicket 발급
```

---

## 5. 버전 이력 요약

| 버전 | PR | 핵심 변경 |
|------|----|---------:|
| **v1.9.3** | #28 | P1-06 IdO 기관 이벤트 폴링 API 완성 |
| v1.9.2 | #27 | HandoffStrategy 4종 완성, GAP-QS-03 멱등 컨슈머, GAP-QIM-05 Snapshot |
| v1.9.1 | #26 | DLQ 완전 구현, X-Internal-Sig 검증, Outbox 재시도 스케줄러 |
| v1.9.0 | #25 | auth_result V10, broker_audit_log, ProviderRouter, 동적 CB |
| v1.8.0 | #22 | Admin API, Rate Limit, PKCE, 모니터링 스택 |
| v1.7.0 | #19 | agency-stub 완전 클라이언트, X-Agency-Key 인터셉터 |
| v1.6.0 | #18 | agency-stub OIDC 클라이언트, Webhook 수신 |
| v1.5.0 | #17 | Webhook 연동 전체 스택 |
| v1.4.x | #16 | Docker 컨테이너화, 보안 강화 |
| v1.3.0 | #11 | Q-IM MariaDB 전환, JPA 레이어 |
| v1.2.0 | #10 | Q-IM SP 수신 API, instMbrId 매핑 |
| v1.1.0 | #9 | Keycloak OIDC 브로커링 전환 |
| v1.0.0 | — | PoC 뼈대 구성 |

---

*다음: [02-architecture.md](02-architecture.md)*  
*인덱스: [00-index.md](00-index.md)*
