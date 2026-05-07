# 통합인증 플랫폼 EDA 마스터 아키텍처 설계서 v0.8 — 갭 분석 및 수정 보완 요청서

> **문서 분류**: 아키텍처 갭 분석 / 수정 보완 요청서  
> **버전**: v2.4.0  
> **작성일**: 2026-05-07  
> **분석 기준 문서**: 통합인증 플랫폼 EDA 마스터 아키텍처 설계서 v0.8 (2026-05-07)  
> **비교 대상**: 현행 PoC 코드베이스 (genspark_ai_developer branch)  
> **작성자**: 아키텍트 (AI-assisted Deep Analysis)  
> **대상 독자**: 아키텍트, 개발팀 리더, Q-IM/Q-Sign 협의 담당자

---

## 목차

1. [분석 개요](#1-분석-개요)
2. [현행 구현 상태 요약](#2-현행-구현-상태-요약)
3. [갭 분석 요약 매트릭스](#3-갭-분석-요약-매트릭스)
4. [영역별 상세 갭 분석](#4-영역별-상세-갭-분석)
   - [4.1 오류 코드 체계](#41-오류-코드-체계)
   - [4.2 DB 스키마 — 미구현 엔터티](#42-db-스키마--미구현-엔터티)
   - [4.3 인증 흐름 — providerType 분기](#43-인증-흐름--providertype-분기)
   - [4.4 Handoff Ticket — 암호화·서명·페이로드](#44-handoff-ticket--암호화서명페이로드)
   - [4.5 EDA / Kafka 설계](#45-eda--kafka-설계)
   - [4.6 보안 — Circuit Breaker·Gate 서명·mTLS](#46-보안--circuit-breakergate-서명mtls)
   - [4.7 운영 모니터링 KPI / SLO](#47-운영-모니터링-kpi--slo)
   - [4.8 SoR 경계 및 SubjectIdentifier 모델](#48-sor-경계-및-subjectidentifier-모델)
   - [4.9 Q-IM SP 수신 API 완전 중재 패턴](#49-q-im-sp-수신-api-완전-중재-패턴)
   - [4.10 마이그레이션 / 기관 연계 패턴](#410-마이그레이션--기관-연계-패턴)
5. [수정 보완 항목 상세 명세](#5-수정-보완-항목-상세-명세)
6. [우선순위별 구현 로드맵](#6-우선순위별-구현-로드맵)
7. [Q-IM 팀과의 합의 필요 항목](#7-q-im-팀과의-합의-필요-항목)
8. [Q-IM DB — MariaDB 전환 상세](#8-q-im-db--mariadb-전환-상세)
9. [변경 이력](#9-변경-이력)

---

## 1. 분석 개요

### 1.1 분석 방법

본 문서는 **통합인증 플랫폼 EDA 마스터 아키텍처 설계서 v0.8** (이하 "설계서 v0.8")의 전체 내용을 현행 PoC 코드베이스와 항목별로 정밀 대조 분석한 결과물이다.

**코드베이스 분석 범위**:

```
ido/src/main/java/kr/go/smes/ido/         ← IdO 전체 Java 소스 (약 60개 파일)
  ├─ api/              HandoffController, BrokerController, OidcCompleteController 등
  ├─ broker/           IdpBrokerService, NonOidcBrokerAdapter 등
  ├─ config/           KafkaConsumerConfig, IdoWebConfig 등
  ├─ domain/           AgencyMeta, IdOAuthInput 등
  ├─ handoff/          HandoffServiceImpl, HandoffService
  ├─ infrastructure/   AgencyMetaRepository, QimClient, TicketRepository, UserStatusCache 등
  ├─ kafka/            QimEventConsumer, QsignAuthEventConsumer, FeAdvisoryConsumer 등
  ├─ policy/           PolicyEngine, PolicyEngineImpl
  └─ qim/              QimSpReceiverController, QimSpReceiverService 등

ido/src/main/resources/
  ├─ application.yml                       ← 런타임 설정 전체
  └─ db/migration/V1~V4__*.sql            ← Flyway DB 마이그레이션

platform-common/src/main/java/kr/go/smes/common/
  ├─ domain/           HandoffPayload, HandoffTicket, AuthResult, IdOAuthInput 등
  ├─ error/            PlatformErrorCode, PlatformException
  └─ event/            UserEvent, AuthEvent, HandoffEvent, SessionAdvisoryEvent

q-im/src/main/java/kr/go/smes/qim/       ← Q-IM 서비스 (MariaDB 기반 독립 서비스)
  ├─ api/              UserController 등
  ├─ application/      UserService, UserServiceImpl
  ├─ config/           KafkaProducerConfig, KafkaTopicConfig
  ├─ domain/           QimUser, AuthMeanMapping, UserProfile
  ├─ infrastructure/   UserRepository
  └─ outbox/           OutboxRecord, OutboxService, OutboxServiceImpl

q-im/src/main/resources/
  ├─ application.yml                       ← MariaDB datasource (jdbc:mariadb, MariaDBDialect)
  └─ db/migration/V1~V2__*.sql            ← MariaDB 전용 Flyway 마이그레이션

q-im/build.gradle.kts
  ├─ org.mariadb.jdbc:mariadb-java-client  ← MariaDB JDBC 드라이버
  └─ org.flywaydb:flyway-mysql             ← Flyway MariaDB 플러그인

infra/docker/
  ├─ docker-compose.yml                   ← MariaDB 11.4 (onepass-mariadb, 172.20.0.21:3306)
  └─ mariadb/mariadb.cnf                  ← MariaDB PoC 튜닝 설정 (utf8mb4, UTC, InnoDB)

agency-stub/                              ← 기관 스텁

docs/
  ├─ qim-ido-integration-architecture.md  (v1.0.0)
  ├─ oidc-brokering-design.md
  ├─ qim-sp-receiver-api-spec.md
  ├─ member-conversion-implementation-plan.md
  └─ local-dev-guide.md
```

### 1.2 분석 기준 설계서 v0.8 주요 신규/변경 사항

설계서 v0.8은 기존 설계 대비 다음 항목이 추가·변경되었다:

| 구분 | 내용 | 영향 영역 |
|------|------|-----------|
| 신규 | `IDO_PROVIDER_REGISTRY` 엔터티 및 `providerType` 4분류 체계 | DB, 브로커 |
| 신규 | `IDO_BROKER_AUDIT_LOG` 엔터티 (E-IDP 계열 전용 감사) | DB, 오류 처리 |
| 신규 | Handoff JSON `trace.traceparent` (W3C Trace Context) 필수화 | API 계약 |
| 신규 | Advisory 이벤트 3종 세분화 (`MANDATORY_QIM_SUSPEND` 등) | EDA |
| 신규 | `qim.user.events` Kafka Compacted Topic 명시 | Kafka |
| 신규 | DLQ 페이로드 표준 (`originalTopic`, `failureCount` 등) | EDA |
| 변경 | `E-IDP-401~499` 오류 코드 체계 세분화 | 오류 처리 |
| 변경 | `ido.auth_result.auth_method` 컬럼 추가 요구 | DB 스키마 |
| 변경 | `HandoffPayload.subject.needsSync` / `syncReason` 필수화 | API 계약 |
| 변경 | agencySubjectId 생성 = HMAC(qimUserId + agencyCode) 명시 | 보안 |
| 변경 | Circuit Breaker를 providerCode 단위로 독립 운영 명시 | 보안/운영 |
| 변경 | Ticket AEAD(AES-256-GCM) 암호화 + 서명 적용 필수화 | 보안 |
| **신규** | **Q-IM DB 엔진을 NHN Cloud RDS for MariaDB로 확정** | **DB / 인프라** |
| **신규** | **PoC 시연 환경에서 self-hosted MariaDB 11.x 컨테이너 사용** | **인프라** |

---

## 2. 현행 구현 상태 요약

### 2.1 구현 완료 항목 ✅

| 구분 | 구현 내용 | 근거 파일 |
|------|-----------|-----------|
| OIDC 브로커 | Keycloak OIDC 어댑터 (`KeycloakOidcService`) | `ido/broker/` |
| 비OIDC 브로커 | `NonOidcBrokerAdapter`, `NonOidcAuthService` | `ido/broker/nonoidc/` |
| FE 세션 | Redis 슬라이딩 TTL 30분 / 절대 만료 8시간 | `application.yml` |
| Handoff Issue/Verify | `HandoffServiceImpl` — 1회성, TTL 60초, consumeOnce | `ido/handoff/` |
| Handoff 구조체 | `HandoffPayload` — policyVersion, SubjectIdentifier, AuthContext | `platform-common/domain/` |
| Q-IM 캐시 무효화 | `QimEventConsumer` — Ordered Consumer, 버전 검증, Selective Pull | `ido/kafka/` |
| Transactional Outbox | `IdoOutboxRelay` — 500ms 릴레이, 배치 100 | `ido/infrastructure/` |
| Q-IM SP 수신 API | 완전 중재 패턴 3종 (QUERY/REGISTER/WITHDRAW) | `ido/qim/sp/` |
| Kafka EDA 구조 | 4개 토픽, 4개 컨슈머 그룹, MANUAL_IMMEDIATE ACK | `KafkaConsumerConfig` |
| 지수 백오프 재시도 | DefaultErrorHandler, 최대 3회 재시도 | `KafkaConsumerConfig` |
| DB 스키마 (IdO) | V1~V4 마이그레이션 (agency_meta, handoff_audit, fe_session, auth_result, qim_sp 관련 3종) | `ido/db/migration/` |
| **Q-IM DB — MariaDB 전환** | **`build.gradle.kts` MariaDB 드라이버·flyway-mysql 적용, `application.yml` MariaDB datasource·Dialect 완전 전환, V1~V2 마이그레이션 MariaDB 문법 전면 재작성** | **`q-im/`** |
| **인프라 — MariaDB 컨테이너** | **`docker-compose.yml` MariaDB 11.4 서비스(`onepass-mariadb`) 추가, `mariadb/mariadb.cnf` 튜닝 설정 작성, `init-db.sql`에서 Q-IM 섹션 제거** | **`infra/docker/`** |
| **Q-IM JPA 구현 레이어** | **JPA 엔터티 4종(`QimUserJpaEntity`, `AuthMeanMappingJpaEntity`, `UserProfileJpaEntity`, `OutboxJpaEntity`), Spring Data Repository 2종, 구현체 2종(`UserRepositoryImpl`, `OutboxRepositoryImpl`)** | **`q-im/infrastructure/jpa/`** |
| **Q-IM 설정 클래스** | **`KafkaConsumerConfig`(MANUAL_IMMEDIATE, concurrency 3), `RedisConfig`(캐시 TTL 300s), `GlobalExceptionHandler` 신규 작성** | **`q-im/config/`, `q-im/api/`** |
| 오류 코드 | E-QS-xxx, E-IDP-4xx, E-IM-2xx, E-IDO-1xx, E-AGENCY-3xx | `PlatformErrorCode.java` |
| Circuit Breaker | qim-client, keycloak-client (Resilience4j) | `application.yml` |
| 멱등 처리 | `idempotentEventStore` — 모든 이벤트 컨슈머 적용 | `ido/kafka/` |

### 2.2 부분 구현 ⚠️

| 구분 | 구현 내용 | 미흡 사항 |
|------|-----------|-----------|
| `ido.auth_result` 스키마 | V3에 기본 필드 구현 | `auth_method`, `issued_at`, `expires_at`, `raw_id_token` 컬럼 누락 |
| `HandoffTicket` 암호화 | 도메인 객체에 `encryptedPayload`, `signature` 필드 선언 | `HandoffServiceImpl`에서 `"TODO:ENCRYPTED"`, `"TODO:SIGNATURE"` 플레이스홀더만 설정 |
| `HandoffPayload` Trace | 기본 필드 구현 | `traceparent`, `providerTrace`, 구간별 타임스탬프 누락 |
| `PolicyEngineImpl.buildHandoffPayload` | 기본 구조 구현 | `agencySubjectId` 생성이 `"AGENCY_SUBJ_" + prefix` 임시 코드, `policyVersion`도 하드코딩 "1.0" |
| `QimEventConsumer` Selective Pull | 로그 기록까지 구현 | 실제 Q-IM API pull 호출 코드 없음 (`// PoC: 로그만 기록` 주석) |
| Circuit Breaker | qim-client, keycloak-client 구현 | providerCode 단위(PASS, GPKI, KAKAO 등) Circuit Breaker 미구현 |

### 2.3 미구현 항목 ❌

| 구분 | 설명 |
|------|------|
| `IDO_PROVIDER_REGISTRY` 테이블 | providerCode 메타 및 providerType 분류 DB 없음 |
| `IDO_BROKER_AUDIT_LOG` 테이블 | E-IDP 계열 전용 감사 로그 DB 없음 |
| Kafka Compacted Topic 설정 | `qim.user.events` 일반 토픽으로 운영 |
| DLQ 토픽 | Kafka DLQ 토픽 미설정 |
| Advisory 이벤트 세분화 | `MANDATORY_QIM_SUSPEND` 등 3종 분기 미구현 |
| Apache Gate 헤더 서명 | X-Sig / X-Sig-Alg / X-Sig-KeyId / X-Sig-Ts 미구현 |
| W3C Trace Context | `traceparent` 헤더 전파 미구현 |
| Micrometer 메트릭 | Handoff Issue/Verify p95/p99 SLO 측정 미구현 |

---

## 3. 갭 분석 요약 매트릭스

| ID | 영역 | 설계서 v0.8 기준 | 현행 코드 상태 | 우선순위 | 위험도 |
|----|------|------------------|----------------|----------|--------|
| **GAP-001** | 오류 코드 | E-AUTH-002 (Locked 423), E-IDP 의미론 조정 | `PlatformErrorCode`에 E-AUTH-002 누락, E-IDP 매핑 부분 불일치 | **P0** | 표준 오류 응답 불일치 |
| **GAP-002** | DB 스키마 | `IDO_PROVIDER_REGISTRY` 엔터티 | DB에 없음 | **P0** | Provider 분류 런타임 불가 |
| **GAP-003** | DB 스키마 | `ido.auth_result`에 `auth_method`, `issued_at`, `expires_at`, `raw_id_token` | 컬럼 미존재 | **P0** | AuthResult SoR 불완전 |
| **GAP-004** | 인증 흐름 | `providerType` 기반 요청별 동적 경로 분기 | 환경변수 전체 모드 전환 방식 | **P1** | 신규 IdP 추가 불가 |
| **GAP-005** | Handoff | Ticket AEAD(AES-256-GCM) 암호화 + HMAC-SHA256 서명 실제 적용 | `"TODO:ENCRYPTED"` 플레이스홀더 | **P1** | Ticket 기밀성·무결성 미보장 |
| **GAP-006** | Handoff | `HandoffPayload`에 `providerTrace`, `needsSync`, `syncReason`, `traceparent`, 구간 타임스탬프 | 일부 필드 누락 | **P1** | Verify 응답 계약 불일치 |
| **GAP-007** | EDA | `qim.user.events` Kafka Compacted Topic (`cleanup.policy=compact`) | 일반 토픽 | **P1** | 동기화 효율 저하, tombstone 처리 불가 |
| **GAP-008** | EDA | `platform.session.advisory` — 3종 Mandatory 이벤트 세분화 | `MANDATORY_SECURITY` 단일 처리 | **P1** | 보안 이벤트 종류별 처리 누락 |
| **GAP-009** | EDA | Kafka DLQ 토픽 및 페이로드 표준 | DLQ 토픽 없음 | **P2** | 반복 실패 이벤트 소실 |
| **GAP-010** | 보안 | providerCode 단위 독립 Circuit Breaker | qim-client/keycloak-client 전역 2개만 | **P2** | 특정 IdP 장애 전파 |
| **GAP-011** | 보안 | Apache Gate 헤더 서명 (X-Sig 계열) | 미구현 | **P2** | Apache Gate 패턴 지원 불가 |
| **GAP-012** | 보안 | W3C `traceparent` 헤더 전파 | X-Correlation-Id만 구현 | **P2** | OpenTelemetry 분산 추적 불가 |
| **GAP-013** | DB 스키마 | `IDO_BROKER_AUDIT_LOG` 엔터티 | 미구현 | **P2** | 브로커 장애 원인 분석 어려움 |
| **GAP-014** | 보안 | `agencySubjectId` 생성 = HMAC(qimUserId + agencyCode, secretKey) | 임시 문자열 접합 | **P2** | 역추적 가능성, 기관별 격리 미보장 |
| **GAP-015** | 운영 | Micrometer `@Timed` — Handoff p95/p99 SLO 측정 | 메트릭 노출 없음 | **P2** | SLA 위반 탐지 불가 |
| **GAP-016** | Handoff | `PolicyEngineImpl.buildHandoffPayload` — policyVersion AgencyMeta 참조 | 하드코딩 "1.0" | **P3** | 정책 버전 감사 추적 오류 |
| **GAP-017** | EDA | `QimEventConsumer` — needsSync=true 시 실제 Q-IM API pull 호출 | 로그 기록만 (`// PoC`) | **P3** | Selective Pull 기능 미완성 |

> **우선순위 기준**  
> - **P0**: PoC 시연 차단 위험 — 즉시 수정 필수  
> - **P1**: 설계서 v0.8 핵심 계약 불일치 — Sprint 내 수정  
> - **P2**: 운영·보안 보완 필요 — 다음 Sprint  
> - **P3**: 문서화·고도화 — Backlog

---

## 4. 영역별 상세 갭 분석

### 4.1 오류 코드 체계

#### 설계서 v0.8 §6.3 vs `PlatformErrorCode.java` 정밀 비교

| 설계서 코드 | 의미 | HTTP | 현행 코드 | 판정 |
|-------------|------|------|-----------|------|
| E-AUTH-001 | 인증 실패 | 401 | `QS_AUTH_FAILED("E-QS-001", 401)` | ⚠️ 코드 불일치 (E-AUTH vs E-QS) |
| **E-AUTH-002** | **계정 잠금** | **423 Locked** | **없음** | ❌ **GAP-001** |
| E-IM-201 | 사용자 없음 | 404 | `IM_USER_NOT_FOUND("E-IM-201", 404)` | ✅ 일치 |
| E-IM-202 | 정지 사용자 | 403 | `IM_USER_SUSPENDED("E-IM-202", 403)` | ✅ 일치 |
| E-IM-203 | 탈퇴 사용자 | 410 | `IM_USER_WITHDRAWN("E-IM-203", 410)` | ✅ 일치 |
| E-IM-204 | 매핑 충돌 | 409 | `IM_IDENTIFIER_CONFLICT("E-IM-204", 409)` | ✅ 일치 |
| E-IDO-101 | Ticket 만료 | 410 | `IDO_TICKET_EXPIRED("E-IDO-101", 410)` | ✅ 일치 |
| E-IDO-102 | Ticket 재사용 | 409 | `IDO_TICKET_CONSUMED("E-IDO-102", 409)` | ✅ 일치 |
| E-IDO-103 | Ticket 취소 | 410 | `IDO_TICKET_REVOKED("E-IDO-103", 410)` | ✅ 일치 |
| E-AGENCY-301 | 미등록 기관 | 403 | `AGENCY_NOT_REGISTERED("E-AGENCY-301", 403)` | ✅ 일치 |
| E-AGENCY-302 | 콜백 URL 위반 | 403 | `AGENCY_CODE_MISMATCH("E-AGENCY-302", 403)` | ⚠️ 의미 불일치 (코드 재사용) |
| E-IDP-401 | IdP 통신 실패 | 502 | `IDP_PROVIDER_UNAVAILABLE("E-IDP-401", 502)` | ✅ 일치 |
| E-IDP-402 | 서명 검증 실패 | 422 | `IDP_RESPONSE_INVALID("E-IDP-402", 502)` | ⚠️ HTTP 상태 불일치 (502 vs 422) |
| E-IDP-403 | 정규화 실패 | 422 | `IDP_SIGNATURE_MISMATCH("E-IDP-403", 422)` | ⚠️ 의미 불일치 |
| E-IDP-404 | 미등록 providerCode | 404 | `IDP_CIRCUIT_OPEN("E-IDP-404", 503)` | ⚠️ 의미·HTTP 불일치 |
| E-OPS-901 | 외부 사업자 일시 장애 | 503 + Retry-After | 없음 | ❌ **GAP-001** |

**핵심 문제 요약**:
1. **E-AUTH-002 (423 Locked)** 완전 누락 — `QsignAuthEventConsumer.handleAuthLocked()`가 이벤트를 수신하지만, 대외 API에서 423 응답을 반환할 오류 코드가 없음
2. **E-AGENCY-302 의미 혼용** — 현행 코드는 "기관 코드 불일치"이나 설계서는 "콜백 URL 화이트리스트 위반"을 의미 (별도 코드로 분리 필요)
3. **E-IDP-402, 403, 404 의미 불일치** — 설계서의 E-IDP 체계가 현행 `PlatformErrorCode`와 의미론적으로 다르게 매핑됨
4. **E-OPS-901 누락** — Circuit Breaker OPEN 시 외부 사업자 장애를 503 + Retry-After로 반환하는 코드 없음

---

### 4.2 DB 스키마 — 미구현 엔터티

#### 4.2.1 전체 엔터티 매핑 현황

| 설계서 §18 엔터티 | 현행 DB 테이블 | 상태 |
|-------------------|----------------|------|
| `Q_SIGN_AUTH_RESULT` | `ido.auth_result` (V3) | ⚠️ 부분 구현 (컬럼 누락) |
| `Q_IM_USER` | Q-IM 내부 (IdO 비보유) | ✅ 격리 원칙 준수 |
| `Q_IM_CREDENTIAL_MAPPING` | Q-IM 내부 (IdO 비보유) | ✅ 격리 원칙 준수 |
| `IDO_HANDOFF_TICKET` | `ido.handoff_audit` (V1) | ⚠️ `auth_method`, `policy_version` 누락 |
| `IDO_POLICY` | `ido.agency_meta` (V1) 통합 | ⚠️ 설계서는 독립 테이블 권고 |
| **`IDO_PROVIDER_REGISTRY`** | **없음** | ❌ **GAP-002** |
| **`IDO_BROKER_AUDIT_LOG`** | **없음** | ❌ **GAP-013** |
| `IDO_OUTBOX_EVENT` | `ido.outbox` (V1) | ✅ 구현됨 |
| `INST_MBR_ID_MAPPING` | `ido.inst_mbr_id_mapping` (V4) | ✅ 구현됨 |
| `SP_RECEIVER_IDEMPOTENCY` | `ido.sp_receiver_idempotency` (V4) | ✅ 구현됨 |
| `QIM_SP_RECEIVER_LOG` | `ido.qim_sp_receiver_log` (V4) | ✅ 구현됨 |

#### 4.2.2 `ido.auth_result` 상세 비교 (GAP-003)

```sql
-- 현행 ido.auth_result (V3 마이그레이션)
auth_result_id      VARCHAR(36)  NOT NULL  -- ✅ authResultId
correlation_id      VARCHAR(36)            -- ✅ correlationId
auth_level          VARCHAR(10)            -- ✅ L1/L2/L3
provider_code       VARCHAR(50)            -- ✅ providerCode
provider_tx_id      VARCHAR(200)           -- ✅ providerTxId
identifier_hash     VARCHAR(64)            -- ✅ SHA-256 해시
verification_result VARCHAR(20)            -- ⚠️ 단순 문자열 (설계서: JSON 구조체)
source_system       VARCHAR(50)            -- ⚠️ 설계서에 없는 내부 필드
authenticated_at    TIMESTAMPTZ            -- ✅ 인증 시각

-- ❌ 누락 컬럼 (설계서 §18.3.1 요구)
auth_method         VARCHAR(60)    -- NON_STANDARD_OIDC_PASS / STANDARD_OIDC_KAKAO 등
issued_at           TIMESTAMPTZ    -- AuthResult 발급 시각
expires_at          TIMESTAMPTZ    -- AuthResult 만료 시각 (기본 10분)
raw_id_token        TEXT           -- 표준 OIDC id_token 원본 (비OIDC는 NULL)
```

#### 4.2.3 `ido.handoff_audit` 상세 비교

```sql
-- 현행 ido.handoff_audit (V1 마이그레이션)
ticket_id           VARCHAR(36)    -- ✅
correlation_id      VARCHAR(36)    -- ✅
agency_code         VARCHAR(20)    -- ✅
qim_user_id         VARCHAR(36)    -- ✅ (설계서: subjectRef, opaque)
auth_result_id      VARCHAR(36)    -- ✅
auth_level          VARCHAR(10)    -- ✅
state               VARCHAR(20)    -- ⚠️ ISSUED/CONSUMED/EXPIRED/REVOKED
                                   --    설계서: CREATED도 포함

-- ❌ 누락 컬럼 (설계서 §16.2 요구)
policy_version      VARCHAR(30)    -- 발급 시점 적용 정책 버전
auth_method         VARCHAR(60)    -- 인증 수단 코드
```

---

### 4.3 인증 흐름 — providerType 분기

#### 설계서 v0.8 §9 / §18.5.4 요구 vs 현행 구현

**설계서 요구**: 모든 인증 요청은 `providerCode` → `IDO_PROVIDER_REGISTRY` 조회 → `providerType` 기반으로 처리 경로를 동적 결정한다.

```
STANDARD_OIDC      → KeycloakOidcService (표준 OIDC 플로우)
SEMI_STANDARD_OIDC → SemiStandardOidcBroker (변형 OIDC)
NON_STANDARD       → NonOidcBrokerAdapter (PASS, GPKI, 금융인증서 등)
```

**현행 구현**:
```yaml
# application.yml
ido:
  broker:
    mode: ${IDO_BROKER_MODE:qsign}   # 전체 시스템 단일 모드 전환
```

```java
// IdpBrokerService.java — 인터페이스 주석에서도 확인
// "비OIDC / 반표준 인증 정규화 브로커"로 단일 어댑터만 존재
public interface IdpBrokerService {
    IdpBrokerResult initiateAuth(String providerCode, ...);
    IdOAuthInput normalizeResponse(String providerCode, ...);
}
```

**갭 원인**: `IDO_PROVIDER_REGISTRY` 테이블 자체가 없어 런타임에 `providerType`을 조회할 수 없다. 신규 IdP(KAKAOPAY, FINANCIAL_CERT 등) 추가 시 코드 수정 및 재배포가 필요한 구조다.

**위험**: 설계서 §18.5.4가 명시하는 "정책 엔진, 브로커 어댑터, 운영 대시보드는 동일 `providerType` 분류값을 사용해야 하며 임의 문자열 허용 불가" 원칙을 현행 코드가 보장하지 못한다.

---

### 4.4 Handoff Ticket — 암호화·서명·페이로드

#### 4.4.1 Ticket 암호화·서명 (GAP-005)

**설계서 v0.8 §16.4 요구**:
- Ticket 본문: AEAD (AES-256-GCM) 암호화
- 서명: HMAC-SHA256 또는 EdDSA
- 키 회전: 90일 주기, grace period 최소 24시간

**현행 코드** (`HandoffServiceImpl.java`):
```java
HandoffTicket ticket = HandoffTicket.builder()
    .ticketId(UUID.randomUUID().toString())
    // ...
    .encryptedPayload("TODO:ENCRYPTED")  // ← 플레이스홀더
    .signature("TODO:SIGNATURE")         // ← 플레이스홀더
    .build();
```

`HandoffTicket` 도메인 객체(`platform-common`)에는 `encryptedPayload`, `signature` 필드가 이미 선언되어 있으나, `HandoffServiceImpl`에서 실제 암호화/서명 로직이 구현되지 않았다.

`application.yml`에는 이미 다음 설정이 정의되어 있다:
```yaml
ido:
  ticket:
    ttl-seconds: 60
    encryption-algorithm: AES-256-GCM
    signing-algorithm: HMAC-SHA256
    key-rotation-days: 90
    key-grace-period-hours: 24
```

즉, **설정은 완비되었으나 실제 암호화·서명 구현 클래스(`TicketCryptoService`)가 없다.**

#### 4.4.2 Handoff Verify 응답 페이로드 (GAP-006)

**설계서 v0.8 §16.5 표준 Verify 응답 vs 현행 `HandoffPayload`**:

| 필드 경로 | 설계서 요구 | 현행 구현 | 판정 |
|-----------|-------------|-----------|------|
| `subject.agencySubjectId` | HMAC 기반 불가역 해시 | `"AGENCY_SUBJ_" + prefix + "_" + agencyCode` 임시 코드 | ⚠️ **GAP-014** |
| `subject.qimUserId` | 포함 (내부 참조용) | `HandoffPayload.SubjectIdentifier.qimUserId` | ✅ 구현 |
| `subject.needsSync` | boolean — Selective Pull 신호 | **없음** | ❌ **GAP-006** |
| `subject.syncReason` | `List<String>` | **없음** | ❌ **GAP-006** |
| `auth.authResultId` | 필수 | `AuthContext.authResultId` | ✅ 구현 |
| `auth.authLevel` | 필수 | `AuthContext.authLevel` | ✅ 구현 |
| `auth.providerTrace.providerCode` | 감사용 | **없음** | ❌ **GAP-006** |
| `auth.providerTrace.providerTxId` | 감사용 | **없음** | ❌ **GAP-006** |
| `trace.correlationId` | 필수 | `HandoffPayload.correlationId` | ✅ 구현 |
| `trace.traceparent` | W3C Trace Context | **없음** | ❌ **GAP-012** |
| `trace.qsignAuthenticatedAt` | Q-Sign 인증 완료 시각 | **없음** | ❌ **GAP-006** |
| `trace.idoIssuedAt` | IdO Ticket 발급 시각 | `issuedAt` (최상위 필드) | ⚠️ 위치 불일치 |
| `signature.alg` | HMAC-SHA256 또는 EdDSA | **없음** | ❌ **GAP-005** |
| `signature.keyId` | 키 ID | **없음** | ❌ **GAP-005** |
| `signature.value` | Base64 서명값 | **없음** | ❌ **GAP-005** |
| `policyVersion` | AgencyMeta에서 참조 | 하드코딩 `"1.0"` | ⚠️ **GAP-016** |

---

### 4.5 EDA / Kafka 설계

#### 4.5.1 토픽 현황 비교

| 토픽명 | 역할 | 설계서 요구 타입 | 현행 구현 | 갭 |
|--------|------|-----------------|-----------|-----|
| `qim.user.events` | Q-IM 사용자 변경 전파 | **Compacted Topic** (key=qimUserId) | 일반 토픽 | **GAP-007** |
| `platform.session.advisory` | 세션 권고 이벤트 | 일반 토픽 | ✅ 구현 | — |
| `ido.handoff.events` | Handoff 감사 이벤트 | 일반 토픽 | ✅ 구현 | — |
| `qsign.auth.events` | Q-Sign 인증 결과 전파 | 일반 토픽 | ✅ 구현 | — |
| `qim.sp.member.events` | Q-IM SP 수신 내부 전파 | 일반 토픽 | ✅ 구현 | — |
| `*.dlq` | Dead Letter Queue | **별도 DLQ 토픽** | **없음** | **GAP-009** |

#### 4.5.2 GAP-007: Compacted Topic 미설정 상세

설계서 §11.5.6 요구:
```
qim.user.events는 key=qimUserId 기준 Log Compaction 기반으로 운영
- MERGED/WITHDRAWN 이벤트: tombstone(null payload) 또는 상태 전이 이벤트로 표현
- 컨슈머는 최신 상태만 읽어 불필요한 재처리 방지
```

`QimEventConsumer.java`의 `@KafkaListener`에서 참조하는 설정 키가 `qim.kafka.topic-user-events`인데, 이는 ido의 `ido.kafka` 네임스페이스가 아닌 `qim.kafka`를 참조하고 있어 **설정 키 불일치**도 존재한다.

```java
// QimEventConsumer.java 현행
@KafkaListener(
    topics = "${qim.kafka.topic-user-events:qim.user.events}",  // ← 키 불일치
    groupId = "${ido.kafka.consumer-group-qim:ido-qim-consumer}",
    ...
)
```

#### 4.5.3 GAP-008: Advisory Event 세분화 미구현

설계서 §14.11.1 Mandatory 이벤트 3종 vs 현행 `FeAdvisoryConsumer`:

| 이벤트 타입 | 트리거 조건 | 기관 의무 동작 | 현행 처리 |
|-------------|-------------|----------------|-----------|
| `MANDATORY_ACCOUNT_COMPROMISE` | 계정 도용 의심 | 즉시 모든 세션 파기 | `MANDATORY_SECURITY`에 통합 처리 |
| `MANDATORY_QIM_SUSPEND` | Q-IM status=SUSPENDED/WITHDRAWN | 로컬 세션 즉시 종료 | `MANDATORY_SECURITY`에 통합 처리 |
| `MANDATORY_HIGH_RISK_REVOKE` | 보안 사고로 ticketId 무효화 | 진행 중 세션 즉시 중단 | `MANDATORY_SECURITY`에 통합 처리 |
| `SESSION_HINT_LOGOUT` | 소프트 로그아웃 권고 | 세션 플래그 설정 | ✅ 별도 처리 가능 |

현행 `FeAdvisoryConsumer`가 모든 Mandatory 이벤트를 `MANDATORY_SECURITY` 단일 분기로 처리하므로, 향후 이벤트 타입별 추가 동작(감사 로그 분류, 알림 채널 구분 등)을 구현할 수 없다.

#### 4.5.4 GAP-009: DLQ 설정 미구현

설계서 §19.4 DLQ 페이로드 표준:
```json
{
  "originalTopic": "qim.user.events",
  "originalPartition": 2,
  "originalOffset": 1047,
  "failureReason": "AGENCY_CONSUMER_TIMEOUT",
  "failureCount": 5,
  "firstFailedAt": "2026-05-07T09:00:00Z",
  "lastFailedAt": "2026-05-07T09:05:00Z",
  "eventId": "EVT-abc123",
  "correlationId": "CORR-xyz456",
  "originalPayload": "{...}"
}
```

현행 `KafkaConsumerConfig.java`는 `DefaultErrorHandler`에 지수 백오프만 설정하고, 최대 재시도(3회) 초과 시 이벤트가 소실된다:
```java
// 현행 — DLQ 목적지 없음
ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
backOff.setInitialInterval(1_000L);
backOff.setMultiplier(3.0);
factory.setCommonErrorHandler(new DefaultErrorHandler(backOff));
// ← DeadLetterPublishingRecoverer 미설정
```

---

### 4.6 보안 — Circuit Breaker·Gate 서명·mTLS

#### 4.6.1 GAP-010: providerCode 단위 Circuit Breaker 미구현

**설계서 §11.6.5 요구**:
```
providerCode별 독립 Circuit Breaker:
- 최근 5분 실패율 ≥ 30% 또는 연속 20건 실패 → OPEN 상태
- OPEN 시: FE에서 해당 인증 수단 "점검 중" 표기
- 회복: 10분 후 HALF_OPEN, 성공률 ≥ 99% 또는 수동 승인 → CLOSED
```

**현행 `application.yml`**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      qim-client:      # Q-IM 클라이언트 전역
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
      keycloak-client: # Keycloak 클라이언트 전역
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

providerCode 단위(PASS, GPKI, KAKAO, NAVER 등)의 독립 Circuit Breaker 인스턴스가 없어, 특정 IdP 장애 시 다른 IdP까지 영향을 받을 수 있다. 또한 `IDO_PROVIDER_REGISTRY.circuit_state` 컬럼과 연동한 상태 동기화 로직도 없다.

#### 4.6.2 GAP-011: Apache Gate 헤더 서명 미구현

**설계서 §15.2.1 / §23.1.1 요구**:
```
X-Sig:     Base64(HMAC-SHA256(shared_key, method + path + X-User-Id + X-Auth-Level + X-Correlation-Id + X-Sig-Ts))
X-Sig-Alg: HMAC-SHA256
X-Sig-KeyId: gate-key-2026-q2
X-Sig-Ts:  2026-05-05T09:00:00+09:00
```

현행 구현: `DIRECT` 패턴만 지원하며, `APACHE_GATE`, `BRIDGE`, `INTERNAL_SSO` 패턴의 핵심인 헤더 서명 검증 로직이 없다. `ido.agency_meta.integration_type` 컬럼은 4패턴을 지원하도록 설계되어 있으나 실제 분기 처리 코드가 없다.

#### 4.6.3 GAP-012: W3C Trace Context (`traceparent`) 미구현

**설계서 §17 공통 헤더 요구**:
```
traceparent: 00-{traceId}-{spanId}-{flags}   (W3C Trace Context)
X-Correlation-Id: CORR-{uuid}                 (플랫폼 내부 추적)
```

현행: `X-Correlation-Id`만 구현됨. `HandoffController`, `QimSpReceiverController` 등에서 `traceparent` 헤더를 수신/전파하는 코드가 없다. OpenTelemetry 분산 추적 연동이 불가능한 상태다.

---

### 4.7 운영 모니터링 KPI / SLO

#### 설계서 §19.10 SLO 기준 vs 현행 구현

| 대상 | SLA 가용성 | p95 목표 | p99 목표 | 에러율 | 현행 계측 |
|------|-----------|---------|---------|--------|-----------|
| IdO Issue (Handoff) | 99.95% | ≤ 150ms | ≤ 350ms | ≤ 0.1% | ❌ 없음 |
| IdO Verify (Handoff) | 99.95% | ≤ 200ms | ≤ 400ms | ≤ 0.1% | ❌ 없음 |
| Q-IM 사용자 조회 | 99.95% | ≤ 100ms | ≤ 250ms | ≤ 0.1% | ❌ 없음 |
| Ticket 재사용 시도율 | — | — | — | ≤ 0.1% | ❌ 없음 |
| Verify 성공률 | 99.5% | — | — | — | ❌ 없음 |

현행 코드에서 Micrometer `@Timed`, `Timer`, `Counter`, `MeterRegistry` 사용 흔적이 발견되지 않는다. `QimSpReceiverService` 주석에 "응답 SLA 500ms 이하"라고 기술되어 있으나 실제 계측 코드가 없다.

Spring Boot Actuator + Micrometer가 의존성에 포함되어 있다면 (`spring-boot-starter-actuator`) 기본 JVM/HTTP 메트릭은 노출되지만, Handoff Issue/Verify 전용 비즈니스 KPI 메트릭은 별도 구현이 필요하다.

---

### 4.8 SoR 경계 및 SubjectIdentifier 모델

#### 설계서 v0.8 §5 SoR 4축 vs 현행 코드 대조

| SoR 축 | 설계서 정의 | 현행 코드 | 판정 |
|--------|-------------|-----------|------|
| 인증 SoR = Q-Sign | AuthResult는 Q-Sign이 발급 | `ido.auth_result`에 Strategy B로 저장 (Keycloak/비OIDC) | ✅ 원칙 준수 |
| 식별·매핑 SoR = Q-IM | IdO는 Q-IM 사용자 정본 미보유 | `QimClient.getUserStatus()`로만 참조 | ✅ 원칙 준수 |
| 정책 SoR = IdO | AgencyMeta, PolicyEngine | `ido.agency_meta`, `PolicyEngineImpl` | ✅ 구현됨 |
| 권한·세션 SoR = 기관 | 기관이 자체 세션 관리 | `agency-stub`에서 분리 | ✅ 원칙 준수 |

#### GAP-014: `agencySubjectId` 생성 로직 미비

**설계서 §5.3 요구**:
> `agencySubjectId` = HMAC(qimUserId + agencyCode, secretKey)  
> - 불가역: qimUserId를 역추적 불가  
> - 기관별 격리: 동일 qimUserId라도 기관 A와 B의 agencySubjectId는 다름

**현행 `PolicyEngineImpl.generateAgencySubjectId()`**:
```java
// TODO: HMAC(qimUserId + agencyCode, secretKey) → Base64URL
return "AGENCY_SUBJ_" + qimUserId.substring(0, 8) + "_" + agencyCode;
```

이 임시 구현은:
1. **역추적 가능** — qimUserId 앞 8자리가 그대로 노출됨
2. **격리 보장 불가** — agencyCode가 포함되어 있으나 secretKey 없이 단순 문자열 접합
3. **설계서 §5.3 보안 요건 미충족**

---

### 4.9 Q-IM SP 수신 API 완전 중재 패턴

#### 현행 구현 상태 (v1.2.0 기준)

| 항목 | 설계서 요구 | 현행 구현 | 판정 |
|------|-------------|-----------|------|
| SP 수신 3종 API | POST /query, /register, /withdraw | ✅ `QimSpReceiverController` 구현 | 일치 |
| 완전 중재 원칙 | Q-IM Core는 외부에 직접 노출 안 됨 | ✅ 모든 외부 요청 IdO 경유 | 일치 |
| AES-256-CBC 복호화 | `QIM_AES_SHARED_KEY` 환경변수 | ✅ 구현됨 | 일치 |
| API Key 검증 | `inbound-api-key-hash` 환경변수 | ⚠️ 개발용 단순 비교, 운영 시 PBKDF2 필요 | 보완 필요 |
| 멱등성 (7일 TTL) | `sp_receiver_idempotency` 테이블 | ✅ 구현됨 | 일치 |
| instMbrId 매핑 | `inst_mbr_id_mapping` 테이블 | ✅ 구현됨 | 일치 |
| Kafka 내부 전파 | `qim.sp.member.events` | ✅ `QimSpMemberEventConsumer` | 일치 |
| 감사 로그 | `qim_sp_receiver_log` | ✅ `receiver-audit-enabled` 설정 | 일치 |

**이 영역은 설계서 v0.8의 완전 중재 패턴을 가장 충실히 구현한 부분이다.**

**유일한 운영 보완 사항**: `QimSpReceiverService.isValidApiKey()`의 API Key 검증 로직을 운영 배포 전 PBKDF2 기반으로 교체해야 한다. (Q-IM 팀과 합의 필요 → §7 참조)

---

### 4.10 마이그레이션 / 기관 연계 패턴

#### 설계서 §15 기관 연계 4패턴 현황

| 패턴 | 설명 | 현행 구현 | 갭 |
|------|------|-----------|-----|
| **Direct** | 기관이 IdO Handoff API 직접 구현 | ✅ `agency-stub`에서 검증 | 없음 (PoC 완료) |
| **Apache Gate** | Apache가 Verify 대행, 헤더 변환·서명 | ❌ 미구현 | GAP-011 연계 |
| **Bridge** | 레거시 프로토콜 → IdO 변환 브리지 | ❌ 미구현 | 별도 서비스 필요 |
| **Internal SSO** | 기관 자체 SSO와 IdO Handoff 연결 | ❌ 미구현 | 기관별 구현 |

> **PoC 범위 명확화**: 설계서 §20.3 병행 운영 정책에 따르면 PoC 단계는 **Direct 패턴만 필수**이며, Apache Gate / Bridge / Internal SSO는 운영 단계 로드맵이다. 다만 `ido.agency_meta.integration_type` 컬럼이 이미 4패턴 `CHECK` 제약으로 설계되어 있어 추후 확장 경로는 열려 있다.

---

## 5. 수정 보완 항목 상세 명세

### GAP-001: 오류 코드 체계 재정렬 [P0]

**문제 요약**: E-AUTH-002 누락, E-AGENCY-302 의미 혼용, E-IDP-402/403/404 의미론 불일치, E-OPS-901 누락

**수정 방안 — `platform-common/src/main/java/kr/go/smes/common/error/PlatformErrorCode.java` 수정**:

```java
// ── 인증 잠금 (신규 추가) ──────────────────────────────────────────────────
E_AUTH_LOCKED("E-AUTH-002", HttpStatus.LOCKED, "계정이 일시 잠금되었습니다."),
// ※ HttpStatus.LOCKED = 423

// ── 기관 연계 오류 재정렬 ──────────────────────────────────────────────────
AGENCY_NOT_REGISTERED       ("E-AGENCY-301", HttpStatus.FORBIDDEN, "등록되지 않은 기관 코드입니다."),
AGENCY_CALLBACK_VIOLATION   ("E-AGENCY-302", HttpStatus.FORBIDDEN, "허용되지 않은 콜백 URL입니다."),  // 의미 수정
AGENCY_CODE_MISMATCH        ("E-AGENCY-303", HttpStatus.FORBIDDEN, "기관 코드 불일치."),              // 번호 변경
AGENCY_KEY_INVALID          ("E-AGENCY-304", HttpStatus.UNAUTHORIZED, "기관 API Key 인증 실패."),
AGENCY_CALLBACK_BLOCKED     ("E-AGENCY-305", HttpStatus.FORBIDDEN, "허용되지 않은 콜백 URL."),
AGENCY_MAINTENANCE          ("E-AGENCY-306", HttpStatus.SERVICE_UNAVAILABLE, "기관 점검 시간입니다."),

// ── 외부 IdP 브로커 오류 재정렬 (의미론 정확화) ──────────────────────────────
IDP_PROVIDER_UNAVAILABLE    ("E-IDP-401", HttpStatus.BAD_GATEWAY, "인증 사업자 연결 불가."),
IDP_SIGNATURE_MISMATCH      ("E-IDP-402", HttpStatus.UNPROCESSABLE_ENTITY, "사업자 응답 서명 불일치."), // 422로 수정
IDP_NORMALIZATION_FAILED    ("E-IDP-403", HttpStatus.UNPROCESSABLE_ENTITY, "응답 정규화 실패."),         // 422
IDP_PROVIDER_NOT_REGISTERED ("E-IDP-404", HttpStatus.NOT_FOUND, "지원되지 않는 인증수단입니다."),        // 404로 수정
IDP_CIRCUIT_OPEN            ("E-IDP-405", HttpStatus.SERVICE_UNAVAILABLE, "Circuit Breaker OPEN 상태."),// 번호 변경

// ── 외부 사업자 일시 장애 (신규 추가) ──────────────────────────────────────
EXTERNAL_PROVIDER_UNAVAILABLE(
    "E-OPS-901", HttpStatus.SERVICE_UNAVAILABLE,
    "외부 인증기관이 일시 점검 중입니다. 잠시 후 다시 시도해 주세요."),
// ※ 응답 헤더에 Retry-After: 60 함께 반환
```

> **⚠️ 주의**: E-AGENCY-302 의미 변경 및 E-AGENCY-303/304/305 번호 변경은 기존 API 계약에 영향을 준다. Q-IM 팀 및 기관 스텁 담당자와 합의 후 적용한다.

---

### GAP-002: `IDO_PROVIDER_REGISTRY` 테이블 추가 [P0]

**수정 방안 — DB 마이그레이션 V5 추가**:

```sql
-- V5__add_provider_registry_and_broker_audit.sql

-- §18.5.4 외부 IdP 메타 / providerType 분류 정책 SoR
CREATE TABLE ido.provider_registry (
    provider_code           VARCHAR(50)   NOT NULL,
    provider_type           VARCHAR(30)   NOT NULL,
    provider_name           VARCHAR(100)  NOT NULL,
    display_name_ko         VARCHAR(100),
    client_id               VARCHAR(200),
    redirect_uri            VARCHAR(500),
    jwks_uri                VARCHAR(500),
    token_endpoint          VARCHAR(500),
    userinfo_endpoint       VARCHAR(500),
    supported_scopes        VARCHAR(500),
    custom_params           JSONB,
    claim_mappings          JSONB,         -- 외부 claim → IdOAuthInput 표준 필드 매핑
    is_active               BOOLEAN        NOT NULL DEFAULT TRUE,
    circuit_state           VARCHAR(20)    NOT NULL DEFAULT 'CLOSED',
    failure_count           INTEGER        NOT NULL DEFAULT 0,
    last_failure_at         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_provider_registry  PRIMARY KEY (provider_code),
    CONSTRAINT chk_provider_type     CHECK (provider_type IN (
        'STANDARD_OIDC', 'SEMI_STANDARD_OIDC', 'NON_STANDARD')),
    CONSTRAINT chk_circuit_state     CHECK (circuit_state IN (
        'CLOSED', 'OPEN', 'HALF_OPEN'))
);

COMMENT ON TABLE  ido.provider_registry               IS '§18.5.4 외부 IdP 메타 / providerType SoR';
COMMENT ON COLUMN ido.provider_registry.circuit_state IS '§11.6.5 providerCode 단위 Circuit Breaker 상태';
COMMENT ON COLUMN ido.provider_registry.claim_mappings IS '외부 claim → IdOAuthInput 표준 필드 매핑 규칙 JSON';

-- 초기 데이터 (PoC용)
INSERT INTO ido.provider_registry
    (provider_code, provider_type, provider_name, display_name_ko, is_active)
VALUES
    ('KAKAO',           'STANDARD_OIDC',      'Kakao Login',      '카카오 로그인',     TRUE),
    ('NAVER',           'STANDARD_OIDC',      'Naver Login',      '네이버 로그인',     TRUE),
    ('GOOGLE',          'STANDARD_OIDC',      'Google Login',     '구글 로그인',       TRUE),
    ('KAKAOPAY',        'SEMI_STANDARD_OIDC', 'KakaoPay Auth',    '카카오페이 인증',   FALSE),
    ('PASS',            'NON_STANDARD',       'PASS Identity',    'PASS 본인확인',     FALSE),
    ('GPKI',            'NON_STANDARD',       'GPKI Certificate', 'GPKI 인증서',       FALSE),
    ('FINANCIAL_CERT',  'NON_STANDARD',       'Financial Cert',   '금융인증서',        FALSE);
```

**연계 Java 신규 클래스**:

```java
// ido/src/main/java/kr/go/smes/ido/domain/ProviderRegistry.java (신규)
@Entity @Table(name = "provider_registry", schema = "ido")
public class ProviderRegistry {
    @Id private String providerCode;
    @Enumerated(EnumType.STRING) private ProviderType providerType;
    private String providerName;
    private boolean isActive;
    @Enumerated(EnumType.STRING) private CircuitState circuitState;
    // ... 기타 필드
    
    public enum ProviderType { STANDARD_OIDC, SEMI_STANDARD_OIDC, NON_STANDARD }
    public enum CircuitState  { CLOSED, OPEN, HALF_OPEN }
}

// ido/src/main/java/kr/go/smes/ido/infrastructure/ProviderRegistryRepository.java (신규)
public interface ProviderRegistryRepository extends JpaRepository<ProviderRegistry, String> {
    Optional<ProviderRegistry> findByProviderCodeAndIsActiveTrue(String providerCode);
}
```

---

### GAP-003: `ido.auth_result` 스키마 보완 [P0]

**수정 방안 — V5 마이그레이션에 포함**:

```sql
-- V5__add_provider_registry_and_broker_audit.sql (계속)

ALTER TABLE ido.auth_result
    ADD COLUMN auth_method   VARCHAR(60),
    ADD COLUMN issued_at     TIMESTAMPTZ,
    ADD COLUMN expires_at    TIMESTAMPTZ,
    ADD COLUMN raw_id_token  TEXT;

COMMENT ON COLUMN ido.auth_result.auth_method  IS
    '§9.3 인증 방식: NON_STANDARD_OIDC_PASS / STANDARD_OIDC_KAKAO / CERT_GPKI 등';
COMMENT ON COLUMN ido.auth_result.issued_at    IS 'AuthResult 발급 시각 (null이면 authenticated_at과 동일)';
COMMENT ON COLUMN ido.auth_result.expires_at   IS 'AuthResult 만료 (기본 10분)';
COMMENT ON COLUMN ido.auth_result.raw_id_token IS '표준 OIDC 경로의 원본 id_token. 비OIDC/반표준은 NULL';
```

**연계 Java 수정**:
- `NonOidcAuthService.processAuth()` — `auth_method` 값 설정 (`NON_STANDARD_OIDC_` + providerCode)
- `KeycloakOidcService` — `auth_method` 값 설정 (`STANDARD_OIDC_` + providerCode), `raw_id_token` 저장

---

### GAP-004: `providerType` 기반 동적 브로커 분기 [P1]

**수정 방안 — `IdpBrokerService` 팩토리 패턴 도입**:

```java
// ido/src/main/java/kr/go/smes/ido/broker/IdpBrokerFactory.java (신규)
@Component
@RequiredArgsConstructor
public class IdpBrokerFactory {

    private final ProviderRegistryRepository providerRegistry;
    private final KeycloakOidcService        keycloakOidcService;
    private final NonOidcBrokerAdapter       nonOidcBrokerAdapter;
    // private final SemiStandardBroker      semiStandardBroker; // 미래 확장

    public IdpBrokerResult initiateAuth(String providerCode, String correlationId, String callbackUrl) {
        ProviderRegistry provider = providerRegistry
            .findByProviderCodeAndIsActiveTrue(providerCode)
            .orElseThrow(() -> new PlatformException(
                PlatformErrorCode.IDP_PROVIDER_NOT_REGISTERED, correlationId));

        return switch (provider.getProviderType()) {
            case STANDARD_OIDC      -> keycloakOidcService.initiateAuth(providerCode, correlationId, callbackUrl);
            case SEMI_STANDARD_OIDC -> nonOidcBrokerAdapter.initiateAuth(providerCode, correlationId, callbackUrl);
            case NON_STANDARD       -> nonOidcBrokerAdapter.initiateAuth(providerCode, correlationId, callbackUrl);
        };
    }
}
```

---

### GAP-005: Handoff Ticket 암호화·서명 구현 [P1]

**수정 방안 — `TicketCryptoService` 신규 구현**:

```java
// ido/src/main/java/kr/go/smes/ido/handoff/TicketCryptoService.java (신규)
@Component
public class TicketCryptoService {

    private static final String AES_GCM_ALGO = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN   = 12;
    private static final int    GCM_TAG_BITS = 128;
    private static final String HMAC_ALGO    = "HmacSHA256";

    @Value("${ido.ticket.enc-key-base64}")
    private String encKeyBase64;

    @Value("${ido.ticket.sig-key-base64}")
    private String sigKeyBase64;

    @Value("${ido.ticket.key-id:ido-key-2026-q2}")
    private String keyId;

    /**
     * Ticket JSON → AES-256-GCM 암호화
     * @return "IV_BASE64.CIPHERTEXT_BASE64"
     */
    public String encrypt(String plainJson) throws Exception {
        byte[] key  = Base64.getDecoder().decode(encKeyBase64);
        byte[] iv   = generateSecureRandom(GCM_IV_LEN);
        SecretKeySpec  keySpec = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        Cipher cipher = Cipher.getInstance(AES_GCM_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] ct = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().encodeToString(iv)
             + "." + Base64.getUrlEncoder().encodeToString(ct);
    }

    /**
     * HMAC-SHA256 서명
     * @return "HMAC_BASE64|keyId"
     */
    public String sign(String ticketId, String agencyCode, Instant expiresAt) throws Exception {
        byte[] sigKey = Base64.getDecoder().decode(sigKeyBase64);
        Mac    mac    = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(sigKey, HMAC_ALGO));
        String data = ticketId + "|" + agencyCode + "|" + expiresAt.toEpochMilli();
        byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().encodeToString(hmac) + "|" + keyId;
    }

    private byte[] generateSecureRandom(int len) {
        byte[] buf = new byte[len];
        new SecureRandom().nextBytes(buf);
        return buf;
    }
}
```

**`HandoffServiceImpl.java` 수정** (`issue()` 메서드):
```java
// 5. Ticket 발급 (AEAD 암호화 + 서명 실제 적용)
String ticketJson   = objectMapper.writeValueAsString(ticketMetadata);
String encPayload   = ticketCryptoService.encrypt(ticketJson);         // AES-256-GCM
String sig          = ticketCryptoService.sign(ticketId, agencyCode, expiresAt); // HMAC-SHA256

HandoffTicket ticket = HandoffTicket.builder()
    // ...
    .encryptedPayload(encPayload)   // "TODO:ENCRYPTED" 제거
    .signature(sig)                 // "TODO:SIGNATURE" 제거
    .build();
```

---

### GAP-006: `HandoffPayload` 필드 보완 [P1]

**수정 방안 — `platform-common/src/main/java/kr/go/smes/common/domain/HandoffPayload.java` 수정**:

```java
@Getter @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HandoffPayload {

    private final String       ticketId;
    private final String       correlationId;
    private final String       agencyCode;
    private final String       policyVersion;
    private final HandoffState state;
    private final SubjectIdentifier subject;
    private final AuthContext       authContext;
    private final Map<String, Object> attributes;
    private final Instant issuedAt;
    private final Instant expiresAt;

    // ── [GAP-006 추가] 추적 정보 ─────────────────────────────────────────
    private final TraceContext trace;          // traceparent, 구간별 타임스탬프

    // ── [GAP-005 추가] 서명 정보 ─────────────────────────────────────────
    private final SignatureInfo signature;

    @Getter @Builder
    public static class SubjectIdentifier {
        private final String     agencySubjectId;
        private final String     qimUserId;
        private final UserStatus status;
        // [GAP-006 추가]
        private final boolean        needsSync;    // Selective Pull 신호
        private final List<String>   syncReason;   // 동기화 필요 이유 목록
    }

    @Getter @Builder
    public static class AuthContext {
        private final AuthResult.AuthLevel authLevel;
        private final String               authResultId;
        private final Instant              authenticatedAt;
        // [GAP-006 추가]
        private final ProviderTrace        providerTrace;  // 감사용 IdP 추적
    }

    @Getter @Builder
    public static class ProviderTrace {
        private final String providerCode;
        private final String providerTxId;
    }

    // [GAP-006 신규]
    @Getter @Builder
    public static class TraceContext {
        private final String  correlationId;
        private final String  traceparent;            // W3C Trace Context (GAP-012)
        private final Instant qsignAuthenticatedAt;   // Q-Sign 인증 완료 시각
        private final Instant qimResolvedAt;          // Q-IM 조회 완료 시각
        private final Instant idoIssuedAt;            // IdO Ticket 발급 시각
    }

    // [GAP-005 신규]
    @Getter @Builder
    public static class SignatureInfo {
        private final String alg;    // "HMAC-SHA256" 또는 "EdDSA"
        private final String keyId;  // "ido-key-2026-q2"
        private final String value;  // Base64URL(HMAC 또는 서명값)
    }

    public enum HandoffState { APPROVED, HOLD, REJECTED, MANUAL_REVIEW }
}
```

---

### GAP-007: Kafka Compacted Topic 설정 [P1]

**수정 방안 — `KafkaTopicConfig.java` 신규 생성 또는 `KafkaConsumerConfig.java` 수정**:

```java
// ido/src/main/java/kr/go/smes/ido/kafka/KafkaTopicConfig.java (신규)
@Configuration
public class KafkaTopicConfig {

    /** §11.5.6 qim.user.events — Log Compaction (key=qimUserId) */
    @Bean
    public NewTopic qimUserEventsTopic() {
        return TopicBuilder.name("qim.user.events")
            .partitions(3)
            .replicas(1)
            .config(TopicConfig.CLEANUP_POLICY_CONFIG,
                    TopicConfig.CLEANUP_POLICY_COMPACT)
            .config(TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG,  "60000")    // 1분
            .config(TopicConfig.DELETE_RETENTION_MS_CONFIG,    "86400000") // 24시간
            .config(TopicConfig.SEGMENT_MS_CONFIG,             "3600000")  // 1시간
            .build();
    }

    /** DLQ — Q-IM 이벤트 실패 격리 (§19.4) */
    @Bean
    public NewTopic qimUserEventsDlqTopic() {
        return TopicBuilder.name("qim.user.events.dlq")
            .partitions(1)
            .replicas(1)
            .build();
    }

    /** DLQ — Handoff 이벤트 실패 격리 */
    @Bean
    public NewTopic handoffEventsDlqTopic() {
        return TopicBuilder.name("ido.handoff.events.dlq")
            .partitions(1).replicas(1).build();
    }
}
```

**`QimEventConsumer` 설정 키 수정**:
```java
// 현행 (오류)
topics = "${qim.kafka.topic-user-events:qim.user.events}"

// 수정 후
topics = "${ido.kafka.topic-qim-user-events:qim.user.events}"
```

**`application.yml` 추가**:
```yaml
ido:
  kafka:
    topic-qim-user-events: ${QIM_USER_EVENTS_TOPIC:qim.user.events}   # 키 추가
```

---

### GAP-008: Advisory Event Mandatory 세분화 [P1]

**수정 방안 — `FeAdvisoryConsumer.java` 수정**:

```java
private void handleAdvisoryEvent(SessionAdvisoryEvent event, Acknowledgment ack) {
    String eventType = event.getEventType();
    String qimUserId = event.getQimUserId();

    switch (eventType) {
        // §14.11.1 Mandatory 이벤트 — 즉시 세션 파기
        case "MANDATORY_ACCOUNT_COMPROMISE":
        case "MANDATORY_QIM_SUSPEND":
        case "MANDATORY_HIGH_RISK_REVOKE":
        case "MANDATORY_SECURITY":   // 하위 호환성 유지
            log.warn("[FeAdvisory] Mandatory 보안 이벤트: type={} qimUserId={}",
                eventType, qimUserId);
            feSessionService.invalidateByQimUserId(qimUserId, "MANDATORY:" + eventType);
            break;

        // Advisory 이벤트 — 플래그 설정 (soft)
        case "SESSION_HINT_LOGOUT":
        case "AGENCY_LOGOUT":
        case "ADVISORY_REAUTH":
            log.info("[FeAdvisory] Advisory 이벤트: type={} qimUserId={}", eventType, qimUserId);
            feSessionService.markAdvisoryFlag(qimUserId, eventType);
            break;

        default:
            log.warn("[FeAdvisory] 미지원 이벤트: type={}", eventType);
    }
}
```

---

### GAP-009: Kafka DLQ 설정 구현 [P2]

**수정 방안 — `KafkaConsumerConfig.java` 수정**:

```java
@Bean
public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
        KafkaTemplate<String, Object> kafkaTemplate) {
    return new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        (record, ex) -> new TopicPartition(
            record.topic() + ".dlq", record.partition() % 1)
    );
}

@Bean
public DefaultErrorHandler defaultErrorHandler(
        DeadLetterPublishingRecoverer recoverer) {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(1_000L);
    backOff.setMultiplier(3.0);
    backOff.setMaxInterval(10_000L);
    return new DefaultErrorHandler(recoverer, backOff);  // recoverer 연결
}
```

---

### GAP-010: providerCode 단위 Circuit Breaker [P2]

**수정 방안 — `application.yml` 확장**:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      # 기존 유지
      qim-client:
        sliding-window-size: 10
        failure-rate-threshold: 60
        wait-duration-in-open-state: 15s
      keycloak-client:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s

      # §11.6.5 providerCode 단위 Circuit Breaker (추가)
      idp-KAKAO:
        sliding-window-size: 20
        failure-rate-threshold: 30        # 30% 실패율 → OPEN
        wait-duration-in-open-state: 600s # 10분 OPEN
        permitted-number-of-calls-in-half-open-state: 5
        minimum-number-of-calls: 10
      idp-PASS:
        sliding-window-size: 20
        failure-rate-threshold: 30
        wait-duration-in-open-state: 600s
        minimum-number-of-calls: 10
      idp-GPKI:
        sliding-window-size: 20
        failure-rate-threshold: 30
        wait-duration-in-open-state: 600s
        minimum-number-of-calls: 10
```

**Java 연계**: `IdpBrokerFactory`에서 `@CircuitBreaker(name = "idp-" + providerCode)` 동적 적용 또는 `CircuitBreakerRegistry`를 통한 런타임 Circuit Breaker 생성.

---

### GAP-011: Apache Gate 헤더 서명 [P2]

**수정 방안 — `HandoffController.verify()` 검증 로직 추가**:

```java
// ido/src/main/java/kr/go/smes/ido/security/GateSignatureVerifier.java (신규)
@Component
public class GateSignatureVerifier {

    @Value("${ido.gate.shared-key-base64:}")
    private String sharedKeyBase64;

    /**
     * Apache Gate 헤더 서명 검증
     * X-Sig = HMAC-SHA256(method + path + X-User-Id + X-Auth-Level + X-Correlation-Id + X-Sig-Ts)
     */
    public boolean verify(HttpServletRequest request) {
        String sig        = request.getHeader("X-Sig");
        String sigAlg     = request.getHeader("X-Sig-Alg");
        String sigTs      = request.getHeader("X-Sig-Ts");
        if (sig == null || sigTs == null) return false;

        String data = request.getMethod()
            + request.getRequestURI()
            + Optional.ofNullable(request.getHeader("X-User-Id")).orElse("")
            + Optional.ofNullable(request.getHeader("X-Auth-Level")).orElse("")
            + Optional.ofNullable(request.getHeader("X-Correlation-Id")).orElse("")
            + sigTs;

        byte[] key = Base64.getDecoder().decode(sharedKeyBase64);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String expected = Base64.getUrlEncoder().encodeToString(
                mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

### GAP-012: W3C `traceparent` 헤더 전파 [P2]

**수정 방안 — 필터 추가**:

```java
// ido/src/main/java/kr/go/smes/ido/config/TraceContextFilter.java (신규)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter implements Filter {

    private static final String TRACEPARENT_HEADER = "traceparent";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        String traceparent = httpReq.getHeader(TRACEPARENT_HEADER);
        if (traceparent == null) {
            // 신규 traceparent 생성: "00-{traceId}-{spanId}-01"
            traceparent = "00-" + generateHex(16) + "-" + generateHex(8) + "-01";
        }
        // MDC 등록 (로그 추적)
        MDC.put("traceparent", traceparent);
        TraceContextHolder.set(traceparent);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove("traceparent");
            TraceContextHolder.clear();
        }
    }
    private String generateHex(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
```

---

### GAP-013: `IDO_BROKER_AUDIT_LOG` 테이블 추가 [P2]

**수정 방안 — V5 마이그레이션에 포함**:

```sql
-- §18.8 E-IDP 계열 전용 감사 로그
CREATE TABLE ido.broker_audit_log (
    log_id              BIGSERIAL     NOT NULL,
    correlation_id      VARCHAR(36)   NOT NULL,
    provider_code       VARCHAR(50)   NOT NULL,
    provider_tx_id      VARCHAR(200),
    error_code          VARCHAR(30),
    endpoint            VARCHAR(500),
    timeout_ms          INTEGER,
    verification_step   VARCHAR(50),
    request_summary     TEXT,
    response_summary    TEXT,
    schema_version      VARCHAR(10)   DEFAULT '1.0',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_broker_audit_log PRIMARY KEY (log_id)
);
CREATE INDEX idx_broker_audit_corr ON ido.broker_audit_log (correlation_id);
CREATE INDEX idx_broker_audit_prov ON ido.broker_audit_log (provider_code, created_at DESC);

COMMENT ON TABLE ido.broker_audit_log IS '§18.8 E-IDP 계열 브로커 오류 전용 감사 로그';
```

---

### GAP-014: `agencySubjectId` HMAC 구현 [P2]

**수정 방안 — `PolicyEngineImpl.generateAgencySubjectId()` 수정**:

```java
// application.yml에 추가
// ido.security.agency-subject-hmac-key: ${AGENCY_SUBJECT_HMAC_KEY}

@Value("${ido.security.agency-subject-hmac-key}")
private String agencySubjectHmacKeyBase64;

private String generateAgencySubjectId(String qimUserId, String agencyCode) {
    try {
        byte[] key  = Base64.getDecoder().decode(agencySubjectHmacKeyBase64);
        Mac    mac  = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String data = qimUserId + ":" + agencyCode;
        byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
    } catch (Exception e) {
        throw new PlatformException(PlatformErrorCode.IDO_POLICY_REJECTED,
            "agencySubjectId 생성 실패");
    }
}
```

---

### GAP-015: Micrometer 메트릭 구현 [P2]

**수정 방안 — `HandoffServiceImpl.java` 계측 추가**:

```java
// HandoffServiceImpl.java 수정
@RequiredArgsConstructor
public class HandoffServiceImpl implements HandoffService {

    private final MeterRegistry meterRegistry;

    // Issue 성공/실패 카운터
    private Counter issueSuccessCounter;
    private Counter issueFailCounter;

    // Verify p95/p99 타이머
    private Timer verifyTimer;

    @PostConstruct
    void initMetrics() {
        issueSuccessCounter = Counter.builder("ido.handoff.issue")
            .tag("result", "success").register(meterRegistry);
        issueFailCounter = Counter.builder("ido.handoff.issue")
            .tag("result", "failure").register(meterRegistry);
        verifyTimer = Timer.builder("ido.handoff.verify.duration")
            .publishPercentiles(0.95, 0.99)
            .register(meterRegistry);
    }

    @Override @Transactional
    public HandoffTicket issue(HandoffIssueCommand cmd) {
        try {
            HandoffTicket ticket = doIssue(cmd);
            issueSuccessCounter.increment();
            return ticket;
        } catch (Exception e) {
            issueFailCounter.increment();
            throw e;
        }
    }

    @Override @Transactional
    public HandoffPayload verify(String ticketId, String agencyCode, String correlationId) {
        return verifyTimer.record(() -> doVerify(ticketId, agencyCode, correlationId));
    }
}
```

---

### GAP-016: `policyVersion` 하드코딩 제거 [P3]

**수정 방안 — `PolicyEngineImpl.buildHandoffPayload()` 수정**:

```java
// 현행 (제거)
.policyVersion("1.0")

// 수정 후 — AgencyMeta에서 참조
.policyVersion(agency.getPolicyVersion())  // AgencyMeta.policyVersion (이미 컬럼 존재)
```

`HandoffIssueCommand`에 `agencyMeta`를 전달하거나, `buildHandoffPayload(HandoffTicket, AgencyMeta, String)` 시그니처로 변경한다.

---

### GAP-017: `QimEventConsumer` Selective Pull 구현 [P3]

**수정 방안 — `QimEventConsumer.java` 수정**:

```java
// ④ needsSync=true → 실제 Q-IM Selective Pull 호출
if (Boolean.TRUE.equals(event.isNeedsSync())) {
    log.info("[QimEventConsumer] Selective Pull: qimUserId={} reason={}",
        qimUserId, event.getEventType());
    try {
        UserStatus freshStatus = qimClient.getUserStatus(qimUserId, eventId);
        userStatusCache.put(qimUserId, freshStatus);    // 캐시 선제 갱신
        log.info("[QimEventConsumer] Selective Pull 완료: qimUserId={} status={}",
            qimUserId, freshStatus);
    } catch (Exception e) {
        log.warn("[QimEventConsumer] Selective Pull 실패 (캐시 무효화 유지): qimUserId={}", qimUserId, e);
        // 실패 시 캐시 무효화 상태 유지 → 다음 Handoff 요청 시 재조회
    }
}
```

---

## 6. 우선순위별 구현 로드맵

### Sprint 1 — P0 즉시 수정 (1주)

| 순서 | 항목 | 담당 | 예상 공수 |
|------|------|------|-----------|
| 1 | **GAP-003** DB 마이그레이션 V5 — `ido.auth_result` 컬럼 추가 | DBA / 백엔드 | 0.5일 |
| 2 | **GAP-002** DB 마이그레이션 V5 — `IDO_PROVIDER_REGISTRY` 테이블 | DBA / 백엔드 | 1일 |
| 3 | **GAP-002** `ProviderRegistry` 도메인 / Repository / 초기 데이터 | 백엔드 | 0.5일 |
| 4 | **GAP-001** `PlatformErrorCode` 재정렬 (E-AUTH-002, E-IDP 의미론) | 백엔드 | 0.5일 |

### Sprint 2 — P1 핵심 계약 수정 (2주)

| 순서 | 항목 | 담당 | 예상 공수 |
|------|------|------|-----------|
| 5 | **GAP-005** `TicketCryptoService` 구현 (AES-256-GCM + HMAC) | 백엔드 | 2일 |
| 6 | **GAP-006** `HandoffPayload` 필드 보완 (needsSync, providerTrace, trace) | 백엔드 | 1일 |
| 7 | **GAP-007** `KafkaTopicConfig` 신규, Compacted Topic + DLQ 설정 | 백엔드/DevOps | 1일 |
| 8 | **GAP-007** `QimEventConsumer` 설정 키 수정 (`qim.kafka.` → `ido.kafka.`) | 백엔드 | 0.5일 |
| 9 | **GAP-008** `FeAdvisoryConsumer` Mandatory 이벤트 세분화 | 백엔드 | 0.5일 |
| 10 | **GAP-004** `IdpBrokerFactory` — providerType 동적 분기 | 백엔드 | 1일 |

### Sprint 3 — P2 운영·보안 보완 (2주)

| 순서 | 항목 | 담당 | 예상 공수 |
|------|------|------|-----------|
| 11 | **GAP-013** V5 — `IDO_BROKER_AUDIT_LOG` 테이블 추가 | DBA / 백엔드 | 0.5일 |
| 12 | **GAP-014** `generateAgencySubjectId()` HMAC 구현 | 백엔드 | 0.5일 |
| 13 | **GAP-009** `DeadLetterPublishingRecoverer` 연결 | 백엔드 | 0.5일 |
| 14 | **GAP-010** providerCode 단위 Circuit Breaker (application.yml + 팩토리 연동) | 백엔드/DevOps | 1일 |
| 15 | **GAP-012** `TraceContextFilter` — traceparent 생성/전파 | 백엔드 | 1일 |
| 16 | **GAP-015** Micrometer 메트릭 — Handoff Issue/Verify KPI | 백엔드 | 1일 |
| 17 | **GAP-011** `GateSignatureVerifier` 구현 | 백엔드 | 1.5일 |

### Sprint 4 — P3 고도화 (Backlog)

| 순서 | 항목 | 담당 |
|------|------|------|
| 18 | **GAP-016** `policyVersion` 하드코딩 제거 — AgencyMeta 참조 | 백엔드 |
| 19 | **GAP-017** `QimEventConsumer` Selective Pull 실제 Q-IM API 호출 구현 | 백엔드 |
| 20 | Apache Gate / Bridge 패턴 구현 (운영 단계 로드맵) | 백엔드/인프라 |

---

## 7. Q-IM 팀과의 합의 필요 항목

다음 항목은 코드 수정 전 **Q-IM 팀과의 공식 합의**가 필요하다:

### A. 오류 코드 번호 재매핑 합의

| 현행 | 변경 후 | 사유 |
|------|---------|------|
| `E-AGENCY-302` = 기관 코드 불일치 | `E-AGENCY-303`으로 번호 변경 | E-AGENCY-302를 "콜백 URL 화이트리스트 위반"으로 설계서에 맞게 재정의 |
| `E-IDP-402` HTTP 502 | `E-IDP-402` HTTP 422로 변경 | 서명 검증 실패는 4xx(클라이언트/데이터 오류) |
| `E-IDP-404` = Circuit Breaker OPEN | `E-IDP-405`로 번호 변경 | E-IDP-404를 "미등록 providerCode"로 재정의 |

### B. `qim.user.events` Compacted Topic 전환 협의

`qim.user.events` 토픽을 Compacted Topic으로 전환하려면 **Q-IM 팀이 Kafka Producer 설정을 변경**해야 한다:
- Producer가 `key=qimUserId`로 메시지를 발행하는지 확인 (현행 Q-IM `KafkaTopicConfig` 확인 필요)
- `WITHDRAWN` / `MERGED` 이벤트 처리 시 tombstone 전략 합의

### C. SP 수신 API Key 검증 방식 합의

현행 `QimSpReceiverService`의 API Key 검증이 단순 문자열 비교인지 PBKDF2 해시 비교인지 확인하고, 운영 배포 전 PBKDF2 방식으로 통일한다.

### D. Advisory 이벤트 신규 타입 발행 여부 확인

`MANDATORY_QIM_SUSPEND` 이벤트를 Q-IM이 실제로 발행하는지, 발행 시점·조건·페이로드 형식을 `SessionAdvisoryEvent` 도메인 객체와 정확히 맞춰야 한다.

### E. `auth_method` 코드 목록 합의

`ido.auth_result.auth_method` 컬럼에 들어갈 값 목록을 Q-Sign/Q-IM 팀과 사전 확정한다:

| 예시 값 | 인증 경로 |
|---------|-----------|
| `STANDARD_OIDC_KAKAO` | Keycloak → 카카오 |
| `STANDARD_OIDC_NAVER` | Keycloak → 네이버 |
| `SEMI_STANDARD_OIDC_KAKAOPAY` | 반표준 카카오페이 |
| `NON_STANDARD_OIDC_PASS` | 비OIDC PASS 본인확인 |
| `NON_STANDARD_GPKI` | 비OIDC GPKI 인증서 |

---

## 8. Q-IM DB — MariaDB 전환 상세

### 8.1 배경 및 결정 사항

설계서 v0.8에 따르면 **Q-IM(식별·매핑 SoR)은 NHN Cloud RDS for MariaDB를 사용**하기로 확정되었다.  
PoC 시연 환경에서는 동일한 MariaDB를 **Docker self-hosted MariaDB 11.x**로 구성하여 운영 환경과 동일한 드라이버·방언·SQL 문법을 검증한다.

**DB 분리 구조 (확정)**:

| 서비스 | DB 엔진 | PoC 접속 | 운영 접속 |
|--------|---------|----------|-----------|
| Q-Sign | PostgreSQL 16 | `localhost:5432/onepass (schema=qsign)` | 내부 PostgreSQL |
| **Q-IM** | **MariaDB 11** | **`localhost:3306/qim`** | **NHN Cloud RDS for MariaDB** |
| IdO | PostgreSQL 16 | `localhost:5432/onepass (schema=ido)` | 내부 PostgreSQL |
| agency-stub | PostgreSQL 16 | `localhost:5432/onepass (schema=agency_stub)` | 내부 PostgreSQL |
| Keycloak | PostgreSQL 16 | `localhost:5432/onepass (schema=keycloak)` | 내부 PostgreSQL |

### 8.2 변경된 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `q-im/build.gradle.kts` | `org.postgresql:postgresql`, `flyway-database-postgresql` 제거 → `org.mariadb.jdbc:mariadb-java-client`, `flyway-mysql` 추가 |
| `q-im/src/main/resources/application.yml` | `jdbc:postgresql` → `jdbc:mariadb`, `PostgreSQLDialect` → `MariaDBDialect`, Flyway `schemas` 제거, 환경변수 `QIM_DB_*` 분리 |
| `q-im/src/main/resources/db/migration/V1__create_schema.sql` | PostgreSQL 문법 → MariaDB 문법 전면 재작성 |
| `q-im/src/main/resources/db/migration/V2__add_idempotent_consumer.sql` | PostgreSQL 문법 → MariaDB 문법 전면 재작성 |
| `infra/docker/docker-compose.yml` | MariaDB 11.4 컨테이너(`onepass-mariadb`, 172.20.0.21) 추가, `onepass-qim` 서비스 추가, Adminer UI(`8091`) 추가 |
| `infra/docker/init-db.sql` | Q-IM 스키마/테이블 항목 제거 (MariaDB로 이관) |
| `infra/docker/mariadb/mariadb.cnf` | MariaDB PoC 튜닝 설정 신규 작성 |

### 8.3 PostgreSQL → MariaDB 문법 전환 상세

Q-IM `db/migration` SQL에 적용된 주요 문법 변환:

| PostgreSQL 문법 | MariaDB 문법 | 비고 |
|----------------|-------------|------|
| `TIMESTAMPTZ` | `DATETIME(6)` | 앱 레벨 UTC 보장 (`serverTimezone=UTC`) |
| `JSONB` | `JSON` | MariaDB 10.2+ 지원, 동등한 JSON 저장/검색 |
| `CREATE SCHEMA qim` | 제거 | MariaDB는 DB 자체가 스키마 — `qim` DB로 분리 |
| `CONSTRAINT chk_xxx CHECK(...)` | 제거 | NHN RDS 버전 호환을 위해 앱 레벨 검증으로 대체 |
| `WHERE` 조건부 인덱스 | 일반 인덱스로 대체 | MariaDB 미지원 (partial index 없음) |
| `DEFAULT NOW()` | `DEFAULT CURRENT_TIMESTAMP(6)` | MariaDB 표준 함수명 |
| `ON UPDATE CURRENT_TIMESTAMP(6)` | 동일 | `updated_at` 자동 갱신 |
| `ENGINE=` 미지정 | `ENGINE=InnoDB` 명시 | ACID 보장 필수 |
| 인코딩 미지정 | `DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` | 한글/이모지 저장 안전 |

### 8.4 application.yml 환경변수 변경

Q-IM 서비스는 PostgreSQL 공용 환경변수(`DB_HOST`, `DB_NAME` 등)와 **별도 네임스페이스**로 분리되었다:

```yaml
# 변경 전 (PostgreSQL 공용)
DB_HOST: postgres
DB_PORT: 5432
DB_NAME: onepass

# 변경 후 (Q-IM 전용 MariaDB)
QIM_DB_HOST: mariadb          # PoC: Docker / 운영: NHN RDS 엔드포인트
QIM_DB_PORT: 3306
QIM_DB_NAME: qim
QIM_DB_USERNAME: qim
QIM_DB_PASSWORD: qim
QIM_DB_SSL: "false"           # PoC: false / 운영: true (NHN RDS SSL 필수)
```

### 8.5 PoC 시연 기동 순서

```bash
# 1. 인프라 기동 (MariaDB + PostgreSQL + Redis + Kafka)
docker compose -f infra/docker/docker-compose.yml up -d \
  mariadb postgres redis zookeeper kafka kafka-init

# 2. MariaDB 헬스체크 확인
docker compose -f infra/docker/docker-compose.yml ps mariadb
# STATUS: healthy

# 3. Q-IM 서비스 기동 (Flyway가 자동으로 qim DB에 V1~V2 마이그레이션 실행)
docker compose -f infra/docker/docker-compose.yml --profile app up -d onepass-qim

# 4. 관리 UI 접속 (선택)
docker compose -f infra/docker/docker-compose.yml --profile tools up -d adminer
# http://localhost:8091 (서버: mariadb, 사용자: qim, 암호: qim, DB: qim)
```

### 8.6 운영 전환 시 추가 고려 사항

| 항목 | 내용 |
|------|------|
| NHN Cloud RDS SSL | `QIM_DB_SSL=true`, 인증서 번들 마운트 또는 `trustServerCertificate=false` + CA 파일 지정 |
| 연결 문자열 | `jdbc:mariadb://<RDS_ENDPOINT>:3306/qim?useSSL=true&serverSslCert=/etc/ssl/rds-ca.pem&...` |
| HikariCP `maximum-pool-size` | RDS 인스턴스 사양에 맞게 조정 (기본 20 → 운영 권고 10~30) |
| Flyway 실행 권한 | RDS 사용자에 `CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX` 권한 부여 필요 |
| `innodb_strict_mode` | NHN RDS 기본값 확인 후 일치 여부 검증 |
| 백업/복구 | NHN RDS 자동 백업 정책 설정 (일 1회 이상 권장) |
| 모니터링 | NHN Cloud 모니터링 + Prometheus `mysqld_exporter` 연동 |

---

## 9. 변경 이력

| 버전 | 날짜 | 작성자 | 변경 내용 |
|------|------|--------|-----------|
| v1.0.0 | 2026-05-07 | AI-assisted | 초안 작성 — 기본 갭 식별 (GAP-001~017 초기 목록) |
| v2.0.0 | 2026-05-07 | AI-assisted | 완성본 — 코드베이스 정밀 대조 분석 완료, 현행 구현 상태 확정, 각 GAP별 코드 수준 수정 방안 완성, E-IDP 의미론 재정렬, QimEventConsumer 설정 키 오류 추가 발견 |
| v2.1.0 | 2026-05-07 | AI-assisted | **Q-IM DB MariaDB 전환 반영** — `q-im/build.gradle.kts`, `application.yml`, DB 마이그레이션 V1~V2, `docker-compose.yml`, `init-db.sql`, `mariadb/mariadb.cnf` 수정 완료. §8 MariaDB 전환 상세 추가 |
| v2.2.0 | 2026-05-07 | AI-assisted | **문서 정합성 보완** — 목차에 §8 링크·§9 변경이력 항목 추가, §1.1 코드베이스 범위에 `q-im/` 서비스 파일 목록 및 `infra/docker/mariadb/` 추가, §1.2 신규/변경 사항 테이블에 MariaDB 전환 결정 행 추가, §2.1 구현 완료 항목에 Q-IM MariaDB 전환 완료 행 및 인프라 MariaDB 컨테이너 추가, §8 제목 앵커 목차 일치 수정 |
| v2.3.0 | 2026-05-07 | AI-assisted | **코드베이스 전수 검증 및 보완** — `q-im/build/resources/main/` 3개 파일(application.yml, V1 SQL, V2 SQL) PostgreSQL→MariaDB 구버전 캐시 교체. `docs/local-dev-guide.md` 전면 MariaDB 반영: 기동 순서·컨테이너 목록(`onepass-mariadb`)·정상 상태 예시·§4.2 MariaDB 연결 확인 신규 추가·PostgreSQL 스키마 구조에서 `qim.*` 제거·서비스 포트표에 MariaDB(3306)·Adminer(8091) 추가·§6.5 `QIM_DB_*` 환경변수 예시 추가·§9 DB 엔진별 스키마 구조 분리·§11.2 MariaDB 트러블슈팅 섹션 신규 추가·§11.x 번호 전체 재정렬·부록 B 환경변수표 `QIM_DB_*` 행 추가 |
| v2.4.0 | 2026-05-07 | AI-assisted | **Q-IM JPA 구현 레이어 전면 신규 작성** — JPA 엔터티 4종(`QimUserJpaEntity`·`AuthMeanMappingJpaEntity`·`UserProfileJpaEntity`·`OutboxJpaEntity`), Spring Data JPA Repository 2종(`QimUserJpaRepository`·`OutboxJpaRepository`), 도메인 구현체 2종(`UserRepositoryImpl`·`OutboxRepositoryImpl`), 설정 클래스 3종(`KafkaConsumerConfig`·`RedisConfig`·`GlobalExceptionHandler`) 신규 작성. `OutboxServiceImpl` `@Qualifier("qimKafkaTemplate")` 의존성 명시 수정. `./gradlew :q-im:build -x test` 빌드 성공 확인. §2.1 구현 완료 항목 2행 추가 |

---

*본 문서는 설계서 v0.8 및 현행 코드베이스(2026-05-07 기준)를 바탕으로 작성되었습니다.*  
*코드 수정 이후 각 GAP 항목의 완료 여부를 이 문서에 체크하여 이력을 관리해 주세요.*
