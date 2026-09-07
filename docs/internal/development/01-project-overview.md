# 01. 프로젝트 개요 (Project Overview)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **작성 기준**: `genspark_ai_developer` 브랜치 커밋 `3f243fa`

---

## 1. 프로젝트 목적

**OnePass Integration-SSO**는 중소벤처기업부 산하 **중기원패스(OnePass)** 플랫폼의 **통합인증 SSO 및 아이덴티티 관리(IM) 시스템**이다.

### 1.1 핵심 목표

| 목표 | 설명 |
|------|------|
| **통합 SSO 브로커링** | 카카오·네이버(OIDC), PASS·금융인증서·GPKI(비OIDC) 등 다양한 IdP를 단일 인터페이스로 추상화 |
| **유관기관 연동 단일 창구** | N개 정부·기관 시스템이 OnePass를 통해 인증을 위임 — 기관은 내부 구현 불필요 |
| **아이덴티티 통합 관리** | 개인/기업 회원의 통합 식별자(qimUserId), DI, CI 해시를 단일 저장소(Q-IM)에서 관리 |
| **보안 중심 설계** | AES-256-GCM 암호화, HMAC-SHA256 서명, PKCE(RFC 7636), Redis 기반 Rate Limit, W3C traceparent |

### 1.2 프로젝트 범위

```
인증 흐름 전체:
  사용자 → idem-console → Q-Sign(인증 SoR) → Keycloak(OIDC 브로커)
         → IdO(오케스트레이터) → Q-IM(식별 SoR) → 유관기관(Handoff Ticket)
```

---

## 2. 기술 스택

### 2.1 백엔드

| 기술 | 버전 | 용도 |
|------|------|------|
| Java | 21 (LTS) | 메인 언어 |
| Spring Boot | 3.x | 서비스 프레임워크 |
| Gradle | 9.5 | 멀티모듈 빌드 |
| Spring Data JPA / Hibernate | 6.x | ORM |
| Flyway | 10.x | DB 스키마 버전 관리 |
| Spring Security | 6.x | 보안 필터 체인 |
| Resilience4j | 2.x | Circuit Breaker / Retry / Rate Limiter |
| Spring Kafka | 3.x | 이벤트 기반 아키텍처 |
| Spring Data Redis | 3.x | 캐시 / 세션 / Rate Limit |
| Micrometer + Prometheus | - | 메트릭 수집 |

### 2.2 데이터베이스

| DB | 용도 | 모듈 |
|----|------|------|
| PostgreSQL 16 | 주 관계형 DB | Q-Sign, IdO |
| MariaDB 11.4 | Q-IM 전용 DB | Q-IM |
| Redis 7 | 캐시 / 세션 / 분산 잠금 | 전 모듈 |

### 2.3 메시지 브로커

| 기술 | 버전 | 토픽 |
|------|------|------|
| Apache Kafka | 3.x | qsign.auth.events, qim.user.events, ido.handoff.events, platform.session.advisory, platform.audit.log |
| Zookeeper | 3.8 | Kafka 코디네이터 |

### 2.4 인프라

| 기술 | 용도 |
|------|------|
| Docker / Docker Compose | 로컬 개발 환경 |
| Keycloak 24 | OIDC 프로토콜 브로커 (카카오·네이버 IdP 연동) |
| Prometheus + Grafana + Loki + Promtail | 모니터링 스택 |
| Nginx | 리버스 프록시 (선택) |

### 2.5 프론트엔드

| 기술 | 버전 | 용도 |
|------|------|------|
| React 18 | - | idem-console SPA |
| Vite | - | 빌드 도구 |
| Node.js | 20+ | 개발 서버 |

---

## 3. 프로젝트 구조 (멀티모듈)

```
integration-sso/
├── idem-common/          # 공통 도메인·이벤트·에러 코드 (공유 라이브러리)
├── idem-gate/                   # Q-Sign: 인증 SoR (port 8081)
├── idem-registry/                     # Q-IM: 식별 SoR (port 8082)
├── idem-hub/                      # IdO: 오케스트레이터 (port 8083)
├── idem-tenant-sample/              # 유관기관 시뮬레이터 (port 8084)
├── idem-console/               # React SPA 프론트엔드 (port 3001)
├── infra/
│   └── docker/               # Docker Compose 구성
│       ├── docker-compose.yml
│       ├── postgres/          # PostgreSQL 초기화
│       ├── mariadb/           # MariaDB 초기화
│       ├── kafka/             # Kafka 토픽 초기화
│       ├── keycloak/          # Keycloak realm 설정
│       ├── nginx/             # Nginx 설정
│       └── redis/             # Redis 설정
├── docs/                     # 프로젝트 문서
│   ├── development/          # 개발 진행 상태 문서 (본 문서 위치)
│   ├── handoff-note.md       # v1.9.0 인수인계 패키지
│   ├── local-dev-guide.md    # 로컬 개발 가이드
│   └── ...                   # 기타 설계·분석 문서
├── build.gradle.kts          # 루트 빌드 스크립트
└── settings.gradle.kts       # 멀티모듈 설정
```

---

## 4. 모듈별 역할

### 4.1 platform-common

모든 모듈이 공유하는 **공통 도메인 객체, 이벤트 클래스, 에러 코드 라이브러리**.

- `AuthResult`, `HandoffTicket`, `HandoffPayload`, `IdOAuthInput`, `UserStatus`
- `AuthEvent`, `HandoffEvent`, `UserEvent`, `SessionAdvisoryEvent`, `AuditLogEvent`, `WebhookDispatchEvent`
- `PlatformErrorCode`, `PlatformException`, `ErrorResponse`
- `CorrelationIdHolder` — W3C traceparent 상관관계 ID 관리

### 4.2 Q-Sign (port 8081)

**인증 SoR(Source of Record)** — 인증 결과의 단일 진실 저장소.

- Keycloak OIDC 브로커링 (카카오, 네이버, PASS, GPKI)
- AuthResult 생성·저장 (qsign.auth_result)
- PKCE(RFC 7636) 구현
- Transactional Outbox → `qsign.auth.events` Kafka 발행
- DB: PostgreSQL (`qsign` 스키마), V1~V5 Flyway 마이그레이션

### 4.3 Q-IM (port 8082)

**식별 SoR(Source of Record)** — 통합 회원 식별자의 단일 진실 저장소.

- 회원 등록·조회·상태 관리 (ACTIVE → SUSPENDED → WITHDRAWN)
- DI(Duplicate Identity) 생성 (HMAC-SHA256)
- CI AES-256-GCM 암호화
- PII 마스킹 서비스
- Transactional Outbox → `qim.user.events` Kafka 발행
- DB: MariaDB (`qim` 스키마), V1~V3 Flyway 마이그레이션

### 4.4 IdO (port 8083)

**오케스트레이터** — 인증·정책·Handoff·브로커링·기관 연동을 총괄.

- Handoff Ticket Issue/Verify/Revoke (AES-256-GCM + HMAC-SHA256)
- PolicyEngine: 기관 정책·속성 필터링·agencySubjectId 생성
- Keycloak OIDC 브로커 어댑터 (IDO_BROKER_MODE=keycloak)
- 비OIDC 브로커 어댑터 (PASS, GPKI)
- Q-IM SP 수신 API 완전 중재 (MEMBER_QUERY/REGISTER/WITHDRAW)
- Webhook 디스패처 (유관기관 외부 알림)
- ProviderRouter: ProviderType 기반 런타임 라우팅
- BrokerAuditLog: 브로커 구간 감사 로그
- AgencyRateLimiter: Redis 기반 슬라이딩 윈도우 Rate Limit
- DB: PostgreSQL (`ido` 스키마), V1~V10 Flyway 마이그레이션

### 4.5 agency-stub (port 8084)

**유관기관 시뮬레이터** — PoC 데모 및 개발 검증용.

- IdO Verify API 호출 시뮬레이션
- 기관 로컬 세션(AGSID) 관리
- Webhook 수신 처리
- 이벤트 폴링 API

### 4.6 idem-console (port 3001)

**React SPA 프론트엔드** — 사용자 인증 UI.

- 인증 흐름 UI (provider 선택, 리디렉션)
- FeSession 기반 상태 관리
- IdO BFF API 연동

---

## 5. 버전 이력 요약

| 버전 | 주요 내용 | PR |
|------|----------|-----|
| v1.0~v1.3 | PoC 기반 코드, 기본 OIDC 흐름 | - |
| v1.4.1 | 런타임 빈 주입 오류 9개 수정, Dockerfile 전 모듈 신규 | PR #15 |
| v1.4.2 | 아키텍처 원칙 보완 (외부망 격리 명문화) | - |
| v1.5.0 | Webhook 디스패처, Redis Pre-warming, 감사 로그, V7 마이그레이션 | PR #16 |
| v1.6.0 | agency-stub Webhook/Verify 실 구현, 기관 API Key 인터셉터 | PR #17 |
| v1.7.0 | 실제 유관기관 완전 클라이언트 구성 | PR #18~19 |
| v1.8.0 | Admin API, Rate Limit, HandoffStrategy, PKCE, 모니터링 | PR #22~23 |
| **v1.9.0** | **P0/P1/P2 GAP 마감: V10 마이그레이션, broker_audit_log 코드 연결, ProviderRouter, 동적 CB** | **PR #24** |

---

## 6. 현재 구현 완성도 (v1.9.0 기준)

| 영역 | 완성도 | 상태 |
|------|--------|------|
| Q-Sign 인증 SoR (JPA 완전 구현) | **100%** | ✅ 완료 |
| Q-Sign Keycloak OIDC 흐름 | **100%** | ✅ 완료 |
| Q-Sign DB 마이그레이션 (V1~V5) | **100%** | ✅ 완료 |
| IdO 정책 오케스트레이터 | **97%** | 🟡 X-Internal-Sig 수신 측 검증 미구현 |
| IdO DB 마이그레이션 (V1~V10) | **100%** | ✅ 완료 |
| IdO Handoff (암호화·서명·전략) | **100%** | ✅ 완료 |
| IdO 브로커 감사 로그 | **100%** | ✅ 완료 (v1.9.0) |
| IdO ProviderRouter 런타임 라우팅 | **100%** | ✅ 완료 (v1.9.0) |
| IdO Resilience4j providerCode 단위 CB | **100%** | ✅ 완료 (v1.9.0) |
| Q-IM 사용자 SoR | **90%** | 🟡 전환·탈퇴 고급 흐름 P2 |
| Q-IM DB 마이그레이션 (V1~V3) | **100%** | ✅ 완료 |
| Docker 컨테이너화 | **100%** | ✅ Dockerfile 전 모듈 완비 |
| 보안 (서명/암호화/해시/PKCE) | **97%** | 🟡 X-Internal-Sig 수신 측 검증 P1 |
| 모니터링 스택 | **80%** | 🟡 커스텀 메트릭 P2 |
| 테스트 커버리지 | **미측정** | ❌ 단위·통합 테스트 미작성 |

---

*다음 문서: [02-architecture.md](02-architecture.md)*
