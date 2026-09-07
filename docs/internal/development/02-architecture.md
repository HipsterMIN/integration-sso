# 02. 시스템 아키텍처 (Architecture)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09

---

## 1. 전체 시스템 구성도

```
═══════════════════════════════════════════════════════════════════════
  외부망 (External Network)
═══════════════════════════════════════════════════════════════════════

  ┌──────────────────────────────────────────────────────────────┐
  │  사용자 (End User)                                             │
  │  - 브라우저 / 모바일 앱                                          │
  └──────────────────────────────┬───────────────────────────────┘
                                 │ HTTPS
  ┌──────────────────────────────▼───────────────────────────────┐
  │  idem-console (React SPA, port 3001)                            │
  │  - 인증 UI (provider 선택, redirect)                           │
  │  - FeSession 기반 상태 관리                                     │
  └──────────────────────────────┬───────────────────────────────┘
                                 │ BFF API / REST
  ┌──────────────────────────────▼───────────────────────────────┐
  │  유관기관 시스템 (agency-stub PoC / 실 기관 시스템)               │
  │  - 자체 로컬 세션(AGSID) 관리                                   │
  │  - Handoff Ticket으로 IdO 호출 → 사용자 정보 수령               │
  └──────────────────────────────┬───────────────────────────────┘
                                 │ HTTPS (POST /api/v1/handoff/verify)
═══════════════════════════════════════════════════════════════════════
  내부망 (Internal Network — idem-net)
═══════════════════════════════════════════════════════════════════════
                                 │
                  ┌──────────────▼──────────────┐
                  │  IdO (port 8083)              │
                  │  오케스트레이터                 │
                  │  - Handoff Issue/Verify       │
                  │  - PolicyEngine               │
                  │  - BrokerAuditLog             │
                  │  - ProviderRouter             │
                  │  - Webhook Dispatcher         │
                  │  - Q-IM SP 수신 중재           │
                  │  - AgencyRateLimiter          │
                  └──────┬──────────┬────────────┘
                         │          │
           ┌─────────────┘          └────────────┐
           │ REST (내부)                          │ Kafka
           ▼                                     ▼
  ┌─────────────────┐              ┌─────────────────────────┐
  │ Q-Sign :8081    │              │  Apache Kafka            │
  │ 인증 SoR         │              │  - qsign.auth.events     │
  │ - OIDC 브로커링  │              │  - qim.user.events       │
  │ - AuthResult    │              │  - ido.handoff.events    │
  │ - PKCE          │◄────Kafka───►│  - platform.session.advisory│
  └────────┬────────┘              │  - platform.audit.log    │
           │                       │  - qim.sp.member.events  │
           ▼                       └──────────────┬───────────┘
  ┌─────────────────┐                             │
  │ Keycloak :8088  │                             ▼
  │ OIDC 브로커     │              ┌─────────────────────────┐
  │ - kakao IdP     │              │  Q-IM :8082              │
  │ - naver IdP     │              │  식별 SoR                 │
  └─────────────────┘              │  - 회원 등록/조회/탈퇴    │
                                   │  - DI/CI 관리            │
  ┌─────────────────┐              │  - PII 마스킹             │
  │  PostgreSQL     │              └──────────────────────────┘
  │  (qsign/ido)    │
  └─────────────────┘              ┌─────────────────────────┐
  ┌─────────────────┐              │  MariaDB (qim)           │
  │  Redis          │              └─────────────────────────┘
  │  - 세션/캐시     │
  │  - Rate Limit   │
  └─────────────────┘
```

---

## 2. 인증 흐름 시퀀스

### 2.1 Keycloak OIDC 흐름 (카카오/네이버)

```
사용자          idem-console       IdO :8083        Q-Sign :8081      Keycloak :8088
   │                 │               │                  │                  │
   │ 인증 시작        │               │                  │                  │
   │────────────────►│               │                  │                  │
   │                 │ BrokerService │                  │                  │
   │                 │──────────────►│                  │                  │
   │                 │               │ 인가 URL 생성      │                  │
   │                 │               │ (PKCE + state)   │                  │
   │                 │               │─────────────────►│                  │
   │                 │               │                  │ state 저장(Redis) │
   │                 │◄──────────────│◄─────────────────│                  │
   │ redirect to Keycloak           │                  │                  │
   │──────────────────────────────────────────────────────────────────────►│
   │                               │                  │                  │
   │ Keycloak 로그인 완료             │                  │                  │
   │◄──────────────────────────────────────────────────────────────────────│
   │ callback (code + state)        │                  │                  │
   │                 │               │                  │                  │
   │ GET /oidc/keycloak/callback     │                  │                  │
   │────────────────────────────────────────────────────►│                 │
   │                 │               │                  │ Token 교환         │
   │                 │               │                  │─────────────────►│
   │                 │               │                  │◄─────────────────│
   │                 │               │                  │ JWT Claims 파싱   │
   │                 │               │                  │ JWKS 검증         │
   │                 │               │                  │ AuthResult 저장   │
   │                 │               │◄─────────────────│ (ido.auth_result) │
   │                 │               │ PolicyEngine       │                  │
   │                 │               │ (속성 필터, DI 생성)│                  │
   │                 │               │ FeSession 발급    │                  │
   │                 │               │ BrokerAuditLog    │                  │
   │                 │◄──────────────│ COMPLETE 기록     │                  │
   │◄────────────────│ feSessionId   │                  │                  │
```

### 2.2 비OIDC 흐름 (PASS/GPKI/금융인증서)

```
사용자          IdO :8083           NonOidcBrokerAdapter      Q-IM :8082
   │               │                      │                      │
   │ 인증 요청      │                      │                      │
   │──────────────►│                      │                      │
   │               │ NonOidcBrokerService  │                      │
   │               │─────────────────────►│                      │
   │               │                      │ 비OIDC 인증 처리       │
   │               │                      │ (PASS/GPKI/공동인증서) │
   │               │◄─────────────────────│                      │
   │               │ CI 복호화(AES-256-CBC) │                      │
   │               │ identifierHash 생성   │                      │
   │               │ AuthResult 저장       │                      │
   │               │                      │ Q-IM 회원 조회/등록   │
   │               │─────────────────────────────────────────────►│
   │               │◄─────────────────────────────────────────────│
   │               │ FeSession 발급        │                      │
   │◄──────────────│                      │                      │
```

### 2.3 Handoff Ticket 흐름

```
사용자          IdO :8083          유관기관 시스템
   │               │                    │
   │ 기관 서비스 진입 (feSessionId 포함)  │
   │──────────────►│                    │
   │               │ HandoffService.issue()
   │               │ - 속성 필터링        │
   │               │ - AES-256-GCM 암호화│
   │               │ - HMAC-SHA256 서명  │
   │               │ - ticketId 발급     │
   │◄──────────────│ ticketId + redirect │
   │                                    │
   │ ticketId 전달 (redirect + query param)
   │───────────────────────────────────►│
   │                                    │ POST /api/v1/handoff/verify
   │                                    │──────────────────────────►│
   │                                    │◄──────────────────────────│
   │                                    │ HandoffPayload (속성 복호화)│
   │                                    │ AGSID 발급 (기관 로컬 세션)  │
   │◄───────────────────────────────────│                           │
```

---

## 3. 아키텍처 결정 (ADR)

### ADR-001: IdO 완전 중재 패턴 (Full Mediation Pattern)

**결정**: Q-IM 명세서(v1.52)가 요구하는 SP 역할(수신 API 3종)을 **IdO가 대리 수행**한다.

**이유**:
- Q-IM 설계 원칙(외부 단절)을 지키면서 Q-IM 명세 충족
- IdO가 이미 보유한 AgencyMeta, PolicyEngine, Outbox, FeSession 재사용
- Q-IM 개발팀 변경 최소화 (Q-IM은 IdO를 하나의 SP로만 인식)

### ADR-002: Strategy B — IdO가 AuthResult 직접 생성

**결정**: Keycloak 브로커 모드에서 IdO가 `ido.auth_result` 테이블에 직접 AuthResult를 저장한다.

**이유**:
- Keycloak 콜백을 IdO가 직접 수신하므로 중간 단계 없이 즉시 저장 가능
- Q-Sign의 AuthResult SoR 역할과 병행 (각각의 auth_result 테이블 독립 운용)

### ADR-003: ProviderType 기반 런타임 라우팅

**결정**: `provider_config.provider_type` 컬럼 값에 따라 브로커 어댑터를 런타임에 선택한다.

```
STANDARD_OIDC → KEYCLOAK_RELAY
SEMI_STANDARD_OIDC → KEYCLOAK_RELAY
NON_STANDARD → DIRECT_BROKER
```

### ADR-004: 유관기관 외부망 격리 원칙

**결정**: 모든 유관기관은 외부망에 위치하며, 내부 Kafka·내부 서비스에 직접 접근할 수 없다.

**통신 채널**: IdO 공개 API(HTTPS)만 허용 + Webhook Push

---

## 4. 네트워크 구성

### 4.1 Docker 네트워크 (로컬 개발)

| 서비스 | 컨테이너 IP | 포트 | 프로파일 |
|--------|-----------|------|---------|
| postgres | 172.20.0.10 | 5432 | default |
| redis | 172.20.0.11 | 6379 | default |
| zookeeper | 172.20.0.12 | 2181 | default |
| kafka | 172.20.0.13 | 9092 | default |
| schema-registry | 172.20.0.14 | 8085 | default |
| kafka-ui | 172.20.0.15 | 8090 | default |
| redis-insight | 172.20.0.16 | 5540 | default |
| pgadmin | 172.20.0.17 | 5050 | default |
| keycloak | 172.20.0.18 | 8088 | keycloak |
| idem-hub | 172.20.0.19 | 8083 | app |
| idem-console | 172.20.0.20 | 3001 | app |
| mariadb | 172.20.0.21 | 3306 | default |
| idem-registry | 172.20.0.22 | 8082 | app |
| adminer | 172.20.0.23 | 8091 | default |
| idem-gate | 172.20.0.24 | 8081 | app |

### 4.2 Kafka 토픽 구성

| 토픽 | 파티션 | RF | ISR | 생산자 | 소비자 |
|------|--------|-----|-----|--------|--------|
| `qsign.auth.events` | 12 | 3 | 2 | Q-Sign | IdO |
| `qim.user.events` | 6 | 3 | 2 | Q-IM | IdO |
| `ido.handoff.events` | 12 | 3 | 2 | IdO | IdO(내부), Webhook |
| `platform.session.advisory` | 12 | 3 | 2 | IdO | IdO(FE Advisory) |
| `platform.audit.log` | 6 | 3 | 2 | IdO | 감사 시스템 |
| `qim.sp.member.events` | 6 | 3 | 2 | IdO | agency-adapter |

---

## 5. 보안 아키텍처

### 5.1 계층별 보안

```
[계층 1 — 네트워크]
  - 유관기관 ↔ IdO: HTTPS only
  - 내부 서비스 간: 내부망 (idem-net) 격리

[계층 2 — 인증/인가]
  - 유관기관 → IdO: X-Agency-Code + X-Agency-Key (PBKDF2 해시 검증)
  - Q-IM → IdO: X-API-Key (inbound key hash 검증)
  - IdO → Q-IM: X-Internal-Caller + X-Internal-Sig (HMAC-SHA256)
  - 내부 서비스 간: X-Internal-Sig (HMAC-SHA256)

[계층 3 — 암호화]
  - Handoff Payload: AES-256-GCM 암호화
  - Handoff 서명: HMAC-SHA256
  - CI(연계정보): AES-256-CBC 복호화 (Q-IM AES 공유키)
  - 키 버전 관리: v{n}.{iv}.{ciphertext} 형태

[계층 4 — 개인정보 보호]
  - CI 저장: 비저장 (identifierHash = SHA-256(CI)만 보관)
  - 사용자 속성: PII 마스킹 (이름, 전화번호, 이메일)
  - agencySubjectId: 비가역 (기관 코드별 독립 식별자)

[계층 5 — 운영 보안]
  - PKCE (RFC 7636): Authorization Code 가로채기 방어
  - Nonce 검증: Replay Attack 방어
  - CSRF State 검증: OAuth CSRF 방어
  - Rate Limiting: 기관별 TPS + 일별 쿼터
  - Circuit Breaker: provider_code 단위 독립 운용
```

### 5.2 Redis 키 구조

| 키 패턴 | TTL | 용도 |
|---------|-----|------|
| `ido:fe-session:{sessionId}` | 30분 슬라이딩, 8시간 절대 | FE 세션 |
| `ido:oidc:state:{state}` | 300초 | OIDC state 검증 |
| `ido:auth_result:{correlationId}` | 300초 | Auth Result 캐시 |
| `ido:rate-limit:{agencyCode}:tps` | 1초 | TPS 카운터 |
| `ido:rate-limit:{agencyCode}:daily` | 24시간 | 일별 쿼터 |
| `ido:pkce:{state}` | 600초 | PKCE code_verifier |
| `provider-config:{providerCode}` | 60분 | Provider 설정 캐시 |

---

## 6. 이벤트 기반 아키텍처 (EDA)

### 6.1 Outbox 패턴

모든 Kafka 발행은 **Transactional Outbox** 패턴을 통해 at-least-once 보장.

```
[비즈니스 트랜잭션] ─── (원자적) ───► [Outbox 테이블 INSERT]
                                                  │
                                                  ▼ (주기적 폴링, 500ms)
                                         [OutboxRelay]
                                                  │
                                                  ▼
                                          [Kafka Producer]
                                                  │
                                       PUBLISHED 상태 업데이트
```

### 6.2 멱등 컨슈머

`ido.processed_event` / `qsign.processed_event` 테이블로 이벤트 중복 처리 방지.

```sql
-- 이벤트 처리 전 중복 확인
INSERT INTO ido.processed_event (event_id, consumer_group, event_type, result_code, processed_at)
VALUES (:eventId, :consumerGroup, :eventType, :resultCode, NOW())
ON CONFLICT (event_id, consumer_group) DO NOTHING;
```

---

*다음 문서: [03-module-ido.md](03-module-ido.md)*
