# 통합인증 플랫폼 EDA 마스터 아키텍처 설계서 v0.8.3 vs 프로젝트 코드 Gap 분석

> 작성일: 2026-05-07  
> 설계서 버전: v0.8.3 (통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.3_latest.docx)  
> 분석 기준 브랜치: `genspark_ai_developer` (커밋: e951e5a)

---

## 목차

1. [분석 개요 및 방법론](#1-분석-개요)
2. [설계서 기준 Gap 항목](#2-설계서-기준-gap-항목)
3. [프로젝트 코드 기준 Gap 항목](#3-프로젝트-코드-기준-gap-항목)
4. [컴포넌트별 현행화 상태 매트릭스](#4-컴포넌트별-현행화-상태-매트릭스)
5. [우선순위별 수정 플랜](#5-우선순위별-수정-플랜)
6. [설계서 수정 플랜](#6-설계서-수정-플랜)
7. [프로젝트 코드 수정 플랜](#7-프로젝트-코드-수정-플랜)

---

## 1. 분석 개요

### 1.1 분석 범위

| 모듈 | 경로 | 분석 대상 |
|------|------|-----------|
| platform-common | `platform-common/` | 공통 도메인·이벤트·오류코드 |
| Q-Sign | `q-sign/` | 인증 SoR, Keycloak 어댑터, Outbox |
| Q-IM | `q-im/` | 식별·매핑 SoR, JPA 구현, Outbox |
| IdO | `ido/` | 오케스트레이션, Handoff, 정책, 브로커 |
| agency-stub | `agency-stub/` | 기관 스텁 |
| infra | `infra/docker/` | Docker Compose, DB 초기화 |
| docs | `docs/` | 기존 문서 |

### 1.2 판정 기준

| 등급 | 기호 | 설명 |
|------|------|------|
| 완전 현행화 | ✅ | 설계서 내용과 코드가 일치 |
| 부분 현행화 | 🟡 | 핵심은 구현되었으나 일부 누락·불일치 |
| 미구현 | 🔴 | 설계서에 명시되었으나 코드에 없음 |
| 코드 초과 | 🟣 | 코드에는 있으나 설계서에 미반영 |
| 의도적 TODO | ⬜ | PoC 단계 플레이스홀더, 추후 구현 예정 |

---

## 2. 설계서 기준 Gap 항목

### 2.1 §9 Q-Sign 상세

#### [GAP-QS-01] 🔴 `auth_method` 저장 규칙 미적용 (§24.4.1)
- **설계서**: `auth_result.auth_method`가 `STANDARD_OIDC_*` / `SEMI_STANDARD_OIDC_*` / `NON_STANDARD_*` 규칙으로 저장되어야 함
- **코드 현황**: `AuthResult.java` / `AuthServiceImpl.java`에 `auth_method` 필드 자체가 없음. `providerCode`만 저장
- **영향**: §24.4.1 코드 레벨 필수 점검 항목 미충족 → 승인 게이트 미통과

#### [GAP-QS-02] 🟡 Q-Sign `AuthResult` 서명 실제 미적용 (§9.3, §11.8)
- **설계서**: `signature` 필드에 EdDSA / HMAC-SHA256 실제 서명값 필요
- **코드 현황**: `AuthResult.java`에 `signature` 필드는 존재하나 `AuthServiceImpl`에서 null로 설정됨
- **영향**: §11.8 `X-Internal-Sig` 검증 로직과 연동 불가

#### [GAP-QS-03] 🔴 Q-Sign `processed_event` / `last_event_version` 테이블 미존재 (§16.3)
- **설계서**: Q-Sign에도 멱등 컨슈머 계약 구현 필요 (§16.3.1)
- **코드 현황**: IdO의 `IdempotentEventStore`는 `ido.processed_event` 테이블 사용. Q-Sign DB 스키마(`qsign.processed_event`)는 migration 파일 없음
- **영향**: Q-Sign 컨슈머가 중복 이벤트 처리 가능성

#### [GAP-QS-04] 🟡 Q-Sign `AuthController` X-Internal-Sig 검증 부재 (§11.8)
- **설계서**: IdO → Q-Sign 구간 `X-Internal-Sig` 헤더 검증 필수 (mTLS + 내부 서명)
- **코드 현황**: `AuthController.java`에 서명 검증 주석만 존재(`// TODO: X-Internal-Sig 검증`)
- **영향**: 내부 API 보안 취약

---

### 2.2 §10 Q-IM 상세

#### [GAP-QIM-01] 🔴 Q-IM `needsSync=true` → Selective Pull 실제 호출 미구현 (§10.5.1, §24.4.1)
- **설계서**: `needsSync=true` 수신 시 기관이 Q-IM API를 실제 Pull 호출하여 최신화해야 함
- **코드 현황**: `QimEventConsumer.java`에 `log.info("Selective Pull 트리거")` 로그만 기록, 실제 API 호출 없음
- **영향**: §24.4.1 체크리스트 — "QimEventConsumer의 needsSync=true 경로가 실제 Q-IM selective pull 호출로 이어지는가" 미충족

#### [GAP-QIM-02] 🟡 Q-IM `UserController` API 경로 불일치 (§17.1)
- **설계서**: `GET /v1/users/{qimUserId}` (버전 prefix: `/v1`)
- **코드 현황**: `UserController.java` → `@RequestMapping("/api/v1/users")` (`/api/v1` prefix)
- **영향**: §17.1 API 계약 불일치. 외부 연동 시 경로 혼동 가능

#### [GAP-QIM-03] 🔴 Q-IM `addAuthMeanMapping` 미완성 (§10.3)
- **설계서**: 인증수단 추가 매핑 시 중복 검사 후 매핑 목록에 추가·저장·이벤트 발행
- **코드 현황**: `UserServiceImpl.addAuthMeanMapping()` → `// TODO: 실제 구현 시 매핑 목록에 추가 후 저장` 로그만 기록
- **영향**: 다중 인증수단 결합(§10.3) 핵심 기능 미구현

#### [GAP-QIM-04] 🟡 Q-IM Outbox `markFailed` 재시도 카운터 미적용 (§10.5.2)
- **설계서**: Outbox 실패 시 `retry_count` 증가, 최대 재시도 초과 시 FAILED 처리
- **코드 현황**: `OutboxServiceImpl.relayPendingEvents()` — 발행 실패 시 단순 로그만 기록, `markFailed()` 미호출. `OutboxRepositoryImpl.markFailed()`도 `retry_count` 증가 없이 단순 상태 변경
- **영향**: Outbox 이벤트 유실 시 FAILED 상태 미전환, 운영 모니터링 불가

#### [GAP-QIM-05] 🔴 Q-IM `snapshot_meta` 테이블 사용 로직 미구현 (§10.7, V2 migration)
- **설계서**: 스냅샷 메타 관리 → `snapshot_meta` 테이블 존재 (V2 migration)
- **코드 현황**: `snapshot_meta` 테이블은 V2 SQL로 생성되나 이를 사용하는 Java 코드 없음 (Repository·Service 없음)
- **영향**: 스냅샷 발행 기능 완전 미구현

---

### 2.3 §11 IdO 상세

#### [GAP-IDO-01] 🔴 IdO `Provider Registry` 런타임 분기 미구현 (§18.5.4, §24.4.1)
- **설계서**: `IDO_PROVIDER_REGISTRY`(`ido.provider_config`)를 기반으로 런타임에 브로커 어댑터 선택, FE 노출, Circuit Breaker 상태 참조
- **코드 현황**: `ido.provider_config` 테이블(V3 migration)은 존재하나, `BrokerService.java`·`BrokerController.java`에서 이 테이블을 조회하여 분기하는 로직 없음 (하드코딩 분기)
- **영향**: §24.4.1 — "Provider Registry 미등록 providerCode 요청 시 E-IDP-404가 즉시 반환되는가" 미충족

#### [GAP-IDO-02] 🔴 IdO Handoff `encryptedPayload` / `signature` 실제 암호화·서명 미적용 (§16.4, §24.4.1)
- **설계서**: `encryptedPayload` = AES-256-GCM 암호화, `signature` = HMAC-SHA256 서명
- **코드 현황**: `HandoffServiceImpl.issue()` → `encryptedPayload("TODO:ENCRYPTED")`, `signature("TODO:SIGNATURE")` 하드코딩
- **영향**: §24.4.1 — "Handoff 발급 시 encryptedPayload와 signature가 실제 암호문/서명값으로 채워지는가" 미충족

#### [GAP-IDO-03] 🔴 IdO `policyVersion` 하드코딩 (§16.5, §24.4.1)
- **설계서**: `policyVersion`은 발급 시점의 정책 원본을 반영해야 함 (AgencyMeta.policyVersion 참조)
- **코드 현황**: `PolicyEngineImpl.buildHandoffPayload()` → `policyVersion("1.0")` 하드코딩
- **영향**: §24.4.1 — "Verify 응답의 policyVersion이 하드코딩이 아니라 발급 시점 정책 원본을 반영하는가" 미충족

#### [GAP-IDO-04] 🔴 IdO `agencySubjectId` HMAC 미적용 (§5.3, §24.4.1)
- **설계서**: `agencySubjectId` = HMAC(qimUserId + agencyCode, secretKey) → Base64URL, 원본 user id 비노출
- **코드 현황**: `PolicyEngineImpl.generateAgencySubjectId()` → `"AGENCY_SUBJ_" + qimUserId.substring(0,8) + "_" + agencyCode` (원본 노출)
- **영향**: §24.4.1 — "agencySubjectId가 기관별 비가역 HMAC 값으로 생성되고 원본 user id 일부를 노출하지 않는가" 미충족

#### [GAP-IDO-05] 🟡 IdO `QimClient` 구현체 미존재 (§11.5.4)
- **설계서**: Q-IM 조회 실패 시 안전 우선 거부 (`E-IDO-106`)
- **코드 현황**: `QimClient` 인터페이스는 존재하나 구현체(`QimClientImpl`) 없음. `PolicyEngineImpl`이 직접 의존하므로 Spring 컨텍스트 로딩 실패 위험
- **영향**: 런타임 `NoSuchBeanDefinitionException` 발생 가능

#### [GAP-IDO-06] 🟡 IdO `UserStatusCache` / `LastEventVersionStore` 구현체 미존재 (§11.5)
- **설계서**: Redis 기반 TTL ≤5분 캐시, 이벤트 버전 저장
- **코드 현황**: `UserStatusCache`, `LastEventVersionStore` 인터페이스만 존재, Redis 구현체 없음
- **영향**: `PolicyEngineImpl`, `QimEventConsumer` 런타임 빈 주입 실패

#### [GAP-IDO-07] 🔴 IdO `TicketRepository` 구현체 미존재 (§16.2)
- **설계서**: Handoff Ticket 저장·소비·취소 기능 필요
- **코드 현황**: `TicketRepository` 인터페이스만 있고 구현체 없음. `HandoffServiceImpl`이 의존하므로 빈 주입 실패
- **영향**: Handoff Issue/Verify 런타임 전체 실패

#### [GAP-IDO-08] 🔴 IdO `AgencyMetaRepository` 구현체 미존재 (§11.2)
- **설계서**: 기관 메타 조회 기능
- **코드 현황**: `AgencyMetaRepository` 인터페이스만 존재, JPA 구현체 없음
- **영향**: `HandoffServiceImpl` 빈 주입 실패

#### [GAP-IDO-09] 🔴 IdO DLQ 전략 미적용 (§19.4, §24.4.1)
- **설계서**: DLQ 적재 시 `originalTopic`, `failureReason`, `failureCount`, `correlationId`, `eventId` 보존 필수
- **코드 현황**: `KafkaConsumerConfig.defaultErrorHandler()` → `DefaultErrorHandler(backOff)` 생성 시 DLQ `DeadLetterPublishingRecoverer` 미설정
- **영향**: 최대 재시도 초과 이벤트가 DLQ에 적재되지 않고 유실

#### [GAP-IDO-10] 🟡 IdO `processed_event` → PostgreSQL `ON CONFLICT` 구문 문제 (§16.3)
- **설계서**: 멱등 컨슈머 구현
- **코드 현황**: `IdempotentEventStore.markProcessed()` → PostgreSQL `ON CONFLICT` 구문 사용. IdO는 PostgreSQL 사용이므로 문법은 맞으나, 테이블명 `ido.processed_event`가 V1 migration에 미정의 (V2 migration에도 없음)
- **영향**: `processed_event` 테이블 없으면 런타임 SQL 오류

#### [GAP-IDO-11] 🔴 IdO `Circuit Breaker` providerCode별 독립 운영 미구현 (§11.6.5, §24.4.1)
- **설계서**: `providerCode` 단위로 Circuit Breaker를 독립 운영
- **코드 현황**: Resilience4j 설정에 `keycloak-client`, `qim-client` 단위 CB만 존재. Provider별 CB 없음
- **영향**: §24.4.1 — "providerCode별 Circuit Breaker OPEN 시 해당 사업자만 차단" 미충족

---

### 2.4 §17 표준 API 계약

#### [GAP-API-01] 🟡 API 버전 Prefix 불일치 (§17.5)
- **설계서**: URL prefix `v1`, `v2`로 분리 (`/v1/users/...`, `/v1/handoff/...`)
- **코드 현황**:
  - Q-IM: `/api/v1/users` ← `/api/` prefix 추가
  - IdO: `/api/v1/handoff`, `/api/v1/broker` ← `/api/` prefix 추가
  - 설계서: `/v1/handoff/issue`, `/v1/handoff/verify`
- **영향**: 설계서·API 명세서와 실제 endpoint 경로가 다름 → 기관 연동 개발 시 혼동

#### [GAP-API-02] 🔴 `Idempotency-Key` 헤더 미지원 (§17.5)
- **설계서**: `Idempotency-Key` 헤더를 issue/verify 양쪽에서 지원해야 함
- **코드 현황**: `HandoffController.java`에 `Idempotency-Key` 처리 로직 없음
- **영향**: 재시도 시 중복 Ticket 발급 가능성

#### [GAP-API-03] 🔴 `traceparent` 헤더 전파 미지원 (§17.5, §24.4.2)
- **설계서**: OpenTelemetry 연동을 위해 `traceparent` 헤더 전달 표준 권고
- **코드 현황**: `CorrelationIdHolder`만 있고 `traceparent` 헤더 전파 로직 없음
- **영향**: §24.4.2 — "traceparent와 correlationId가 FE → IdO → Q-Sign → 기관 로그에 일관되게 남는지" 검증 불가

#### [GAP-API-04] 🔴 `Retry-After` 헤더 미지원 (§17.5)
- **설계서**: `E-OPS-901` 응답에 `Retry-After` 헤더 포함 필수
- **코드 현황**: `GlobalExceptionHandler.java`에 `Retry-After` 헤더 설정 없음
- **영향**: 기관의 재시도 타이밍 판단 불가

---

### 2.5 §18 데이터 모델

#### [GAP-DM-01] 🔴 `Q_IM_CREDENTIAL_MAPPING` → 테이블명 불일치 (§18.4.2)
- **설계서**: 테이블명 `Q_IM_CREDENTIAL_MAPPING` (자격증명 매핑)
- **코드 현황**: `auth_mean_mapping` 테이블 사용 (V1 migration, `AuthMeanMappingJpaEntity`)
- **영향**: 설계서 데이터 모델과 실제 구현 명칭 불일치 → 문서 현행화 필요

#### [GAP-DM-02] 🔴 `IDO_PROVIDER_REGISTRY` 필드 불완전 (§18.5.4)
- **설계서**: `providerType` = `STANDARD_OIDC` / `SEMI_STANDARD_OIDC` / `NON_STANDARD` 고정 집합 필수
- **코드 현황**: `ido.provider_config` 테이블에 `provider_type` 컬럼 없음, `broker_mode` 컬럼만 존재
- **영향**: 런타임 분기 기준 컬럼 누락

#### [GAP-DM-03] 🔴 `IDO_BROKER_AUDIT_LOG` 미구현 (§18.5.5)
- **설계서**: `correlationId`, `providerCode`, `providerTxId`, `errorCode` 필수 필드 보존
- **코드 현황**: `ido.broker_audit_log` 테이블 migration 없음, Java 코드도 없음
- **영향**: 브로커 구간 감사 로그 전혀 없음

#### [GAP-DM-04] 🟡 `ido.processed_event` 테이블 migration 누락 (§18.5.6)
- **코드 현황**: `IdempotentEventStore`가 `ido.processed_event` 테이블 조회·삽입하나, V1~V4 migration 어디에도 테이블 정의 없음
- **영향**: 앱 기동 시 `ido.processed_event` SQL 오류

---

### 2.6 §19 운영·보안

#### [GAP-OPS-01] 🔴 `E-OPS-901` 오류 코드 미정의 (§19.9, §17.5)
- **설계서**: 외부 사업자 장애 시 `E-OPS-901` 코드 반환 + `Retry-After` 헤더
- **코드 현황**: `PlatformErrorCode`에 `E-OPS-901` 없음 (QS-003, IDP-401~404만 정의)
- **영향**: 외부 사업자 장애 오류 응답 표준화 불가

#### [GAP-OPS-02] 🟡 Micrometer 커스텀 메트릭 미구현 (§19.11)
- **설계서**: Handoff 성공률, Ticket 재사용, 브로커 Circuit Breaker 상태 등 커스텀 메트릭 필요
- **코드 현황**: Actuator + Prometheus 설정은 있으나 커스텀 `MeterRegistry` 계측 없음
- **영향**: SLO 위반 판정 불가 (§19.12)

---

### 2.7 §24 PoC 정합성 체크리스트 종합

| 체크 항목 | 설계서 §24.4.1 | 상태 |
|-----------|---------------|------|
| Provider Registry 미등록 providerCode → E-IDP-404 즉시 반환 | §24.4.1 | 🔴 미충족 |
| auth_method STANDARD_OIDC_*/SEMI_*/NON_STANDARD_* 규칙 저장 | §24.4.1 | 🔴 미충족 |
| encryptedPayload + signature 실제 암호문/서명값 | §24.4.1 | 🔴 미충족 |
| policyVersion 하드코딩 아닌 발급 시점 정책 원본 반영 | §24.4.1 | 🔴 미충족 |
| agencySubjectId 비가역 HMAC, 원본 id 비노출 | §24.4.1 | 🔴 미충족 |
| needsSync=true → 실제 Selective Pull API 호출 | §24.4.1 | 🔴 미충족 |
| DLQ 적재 시 6개 필드 보존 | §24.4.1 | 🔴 미충족 |
| providerCode별 CB OPEN 시 해당 사업자만 차단 | §24.4.1 | 🔴 미충족 |

---

## 3. 프로젝트 코드 기준 Gap 항목

> 코드에는 있으나 설계서에 명시되지 않았거나, 설계서 개정이 필요한 항목

### 3.1 아키텍처·구조 변경 사항 (설계서 미반영)

#### [GAP-CODE-01] 🟣 Strategy B — IdO가 AuthResult 직접 생성 (V3 migration)
- **코드 현황**: `KeycloakOidcService`가 `ido.auth_result` 테이블에 직접 저장 (Strategy B)
- **설계서 현황**: §9.3에 Q-Sign SoR로 AuthResult를 관리하는 것으로 기술. Strategy B 도입 근거·조건이 설계서에 명확하지 않음
- **수정 필요**: 설계서 §9.1 / §9.3에 "Keycloak 브로커 모드에서는 IdO가 AuthResult를 직접 생성" 조건 명시

#### [GAP-CODE-02] 🟣 `onepass-fe` BFF Spring Boot 제거 → FE Advisory Consumer IdO 이관
- **코드 현황**: `ido/config/KafkaConsumerConfig.java`에 `feAdvisoryConsumerFactory` 포함. FE Advisory 처리가 IdO로 이관됨
- **설계서 현황**: §12 Onepass FE 절에서 BFF 이관 여부 미언급
- **수정 필요**: 설계서 §12에 BFF 제거 사유 및 FE Advisory → IdO 이관 사실 명시

#### [GAP-CODE-03] 🟣 Q-IM SP 수신 API 3패턴 (QUERY/REGISTER/WITHDRAW) 구현
- **코드 현황**: `QimSpReceiverController`, `QimSpReceiverService`, `InstMbrIdMapping`, V4 migration 등 완전 구현
- **설계서 현황**: §10.6 Q-IM Core 노출 계약 계층 원칙에서 언급만, 구체적 엔드포인트 정의 없음
- **수정 필요**: 설계서 §10.6 또는 신규 §10.8에 SP 수신 API 3패턴 상세 기술

#### [GAP-CODE-04] 🟣 IdO `broker.mode` 설정 (`qsign` vs `keycloak`) 런타임 분기
- **코드 현황**: `application.yml`에 `ido.broker.mode` 설정 존재, `BrokerController`에서 모드 분기
- **설계서 현황**: §11.6 브로커 컴포넌트에 모드 전환 설정 키 미언급
- **수정 필요**: 설계서 §11.6.2에 `broker.mode` 설정 키와 전환 절차 명시

#### [GAP-CODE-05] 🟣 PoC docker-compose에 `onepass-mariadb` 컨테이너 추가
- **코드 현황**: `infra/docker/docker-compose.yml`에 MariaDB 11.4 컨테이너 정의 완료
- **설계서 현황**: §10.7 MariaDB 배치 원칙에 docker-compose 기동 순서 명시 요구 있으나, 실제 구성은 코드에서 먼저 구현
- **상태**: 코드 선행 구현, 설계서 반영 완료 필요 (local-dev-guide 업데이트 완료)

### 3.2 설계서 정오표 대상

#### [GAP-CODE-06] 🟣 IdO `processed_event` → `ido.processed_event` (PostgreSQL)
- **코드 현황**: `IdempotentEventStore`가 `ido.processed_event` 테이블 사용 (PostgreSQL `ON CONFLICT` 문법)
- **설계서 §24.2**: 멱등 이벤트 저장소가 Q-IM(`processed_event`) 기준으로만 기술됨
- **수정 필요**: 설계서 §16.3에 "IdO도 자체 `ido.processed_event` 테이블 운영" 명시

#### [GAP-CODE-07] 🟣 Q-IM `auth_mean_mapping` vs 설계서 `Q_IM_CREDENTIAL_MAPPING`
- **코드 현황**: 테이블명 `auth_mean_mapping`, 엔터티 `AuthMeanMappingJpaEntity`
- **설계서 §18.4.2**: `Q_IM_CREDENTIAL_MAPPING` 명칭 사용
- **수정 필요**: 설계서 §18.4.2를 `auth_mean_mapping`으로 현행화 (또는 코드 명칭 통일)

---

## 4. 컴포넌트별 현행화 상태 매트릭스

### 4.1 Q-Sign

| 항목 | 설계서 §절 | 상태 | GAP ID |
|------|-----------|------|--------|
| AuthResult 도메인 객체 | §9.3 | ✅ | - |
| OIDC / 비OIDC 인증 발급 | §9.4~9.5 | ✅ | - |
| 잠금/재시도 정책 | §9.6 | ✅ | - |
| Keycloak OIDC 어댑터 | §11.6 | ✅ | - |
| X-Internal-Sig 검증 | §11.8 | 🟡 | GAP-QS-04 |
| auth_method 저장 규칙 | §24.4.1 | 🔴 | GAP-QS-01 |
| signature 실제 서명 | §9.3 | 🟡 | GAP-QS-02 |
| processed_event 테이블 | §16.3 | 🔴 | GAP-QS-03 |
| Transactional Outbox | §10.5.2 | ✅ | - |

### 4.2 Q-IM

| 항목 | 설계서 §절 | 상태 | GAP ID |
|------|-----------|------|--------|
| QimUser 도메인 SoR | §10.1~10.2 | ✅ | - |
| 다중 인증수단 매핑 | §10.3 | 🟡 | GAP-QIM-03 |
| 사용자 조회 API | §17.1 | 🟡 | GAP-QIM-02 |
| Transactional Outbox | §10.5.2 | ✅ | - |
| Outbox 재시도/FAILED | §10.5.2 | 🟡 | GAP-QIM-04 |
| Selective Pull 호출 | §10.5.1 | 🔴 | GAP-QIM-01 |
| Snapshot 기능 | §10.7 | 🔴 | GAP-QIM-05 |
| JPA 엔터티 구현 | §18.4 | ✅ | - |
| MariaDB 전환 | §10.7 | ✅ | - |
| KafkaConsumerConfig | §10.5.2 | ✅ | - |
| RedisConfig | §11.5 | ✅ | - |
| GlobalExceptionHandler | §17.5 | 🟡 | GAP-API-04 |

### 4.3 IdO

| 항목 | 설계서 §절 | 상태 | GAP ID |
|------|-----------|------|--------|
| Handoff Issue / Verify | §16.1~16.5 | 🟡 | GAP-IDO-02,03,04 |
| Ticket 상태 모델 | §11.3 | ✅ | - |
| TicketRepository 구현체 | §16.2 | 🔴 | GAP-IDO-07 |
| AgencyMetaRepository 구현체 | §11.2 | 🔴 | GAP-IDO-08 |
| QimClient 구현체 | §11.5.4 | 🔴 | GAP-IDO-05 |
| UserStatusCache 구현체 | §11.5 | 🔴 | GAP-IDO-06 |
| LastEventVersionStore 구현체 | §11.5.3 | 🔴 | GAP-IDO-06 |
| PolicyEngine | §11.4 | 🟡 | GAP-IDO-03,04 |
| Provider Registry 분기 | §18.5.4 | 🔴 | GAP-IDO-01 |
| providerCode별 CB | §11.6.5 | 🔴 | GAP-IDO-11 |
| DLQ 전략 | §19.4 | 🔴 | GAP-IDO-09 |
| processed_event 테이블 | §16.3 | 🔴 | GAP-DM-04 |
| broker_audit_log | §18.5.5 | 🔴 | GAP-DM-03 |
| Keycloak OIDC 브로커 | §11.6 | ✅ | - |
| NonOIDC 브로커 어댑터 | §11.6 | ✅ (PoC) | - |
| Transactional Outbox | §10.5.2 | ✅ | - |
| QimEventConsumer | §11.5 | 🟡 | GAP-QIM-01 |
| IdempotentEventStore | §16.3 | 🟡 | GAP-IDO-10 |
| SP 수신 API | §10.6 | ✅ | - |
| Circuit Breaker 설정 | §11.6.5 | 🟡 | GAP-IDO-11 |

### 4.4 platform-common

| 항목 | 설계서 §절 | 상태 | GAP ID |
|------|-----------|------|--------|
| DomainEvent 베이스 | §10.5 | ✅ | - |
| UserEvent | §10.5 | ✅ | - |
| AuthEvent | §9.3 | ✅ | - |
| HandoffEvent | §16.1 | ✅ | - |
| SessionAdvisoryEvent | §14.11 | ✅ | - |
| HandoffTicket | §16.2 | ✅ | - |
| HandoffPayload | §16.5 | ✅ | - |
| AuthResult | §9.3 | 🟡 | GAP-QS-01 |
| PlatformErrorCode | §22.2 | 🟡 | GAP-OPS-01 |

---

## 5. 우선순위별 수정 플랜

### P0 — 런타임 빈 주입 실패 (즉시 수정 필요)

> 이 항목들이 해결되지 않으면 서버 기동 자체가 실패함

| ID | 대상 모듈 | 작업 내용 |
|----|---------|---------|
| GAP-IDO-05 | IdO | `QimClientImpl` 구현체 작성 (RestTemplate + Resilience4j) |
| GAP-IDO-06 | IdO | `UserStatusCacheImpl` (Redis TTL 5분), `LastEventVersionStoreImpl` (Redis) 구현 |
| GAP-IDO-07 | IdO | `TicketRepositoryImpl` 구현 (Redis 주 저장, DB 감사 이력) |
| GAP-IDO-08 | IdO | `AgencyMetaRepositoryImpl` 구현 (JPA + `ido.agency_meta`) |
| GAP-DM-04 | IdO DB | V2 or V5 migration에 `ido.processed_event` 테이블 추가 |

### P1 — 설계서 §24.4.1 승인 게이트 항목

| ID | 대상 모듈 | 작업 내용 |
|----|---------|---------|
| GAP-QS-01 | Q-Sign / platform-common | `AuthResult`에 `authMethod` 필드 추가, `STANDARD_OIDC_*` 규칙 적용 |
| GAP-IDO-02 | IdO | AES-256-GCM `encryptedPayload` + HMAC-SHA256 `signature` 실제 구현 |
| GAP-IDO-03 | IdO | `policyVersion` → `AgencyMeta.policyVersion` 참조로 교체 |
| GAP-IDO-04 | IdO | `agencySubjectId` → HMAC(qimUserId+agencyCode, key) Base64URL 구현 |
| GAP-QIM-01 | IdO | `needsSync=true` 시 `QimClient.getUserById()` 실제 호출 구현 |
| GAP-IDO-09 | IdO | `DefaultErrorHandler`에 `DeadLetterPublishingRecoverer` 설정 (6개 필드 보존) |
| GAP-IDO-11 | IdO | Resilience4j에 providerCode별 CB 동적 등록 (`CircuitBreakerRegistry`) |
| GAP-IDO-01 | IdO | `ido.provider_config` 조회로 브로커 어댑터 런타임 분기 구현 |

### P2 — API 계약 현행화

| ID | 대상 모듈 | 작업 내용 |
|----|---------|---------|
| GAP-API-01 | Q-IM / IdO | API 경로 설계서 기준(`/v1/...`)으로 통일 여부 결정 후 적용 |
| GAP-API-02 | IdO | `HandoffController`에 `Idempotency-Key` 헤더 처리 추가 |
| GAP-API-03 | 전체 | `traceparent` 헤더 전파 필터/인터셉터 추가 (OpenTelemetry) |
| GAP-API-04 | Q-IM / IdO | `GlobalExceptionHandler`에 `Retry-After` 헤더 추가 |
| GAP-OPS-01 | platform-common | `PlatformErrorCode`에 `E-OPS-901` 추가 |

### P3 — 데이터 모델 완성

| ID | 대상 모듈 | 작업 내용 |
|----|---------|---------|
| GAP-DM-02 | IdO DB | `ido.provider_config`에 `provider_type` 컬럼 추가 migration |
| GAP-DM-03 | IdO DB | `ido.broker_audit_log` 테이블 추가 migration + Java 구현 |
| GAP-QIM-03 | Q-IM | `addAuthMeanMapping()` 실제 JPA 저장 구현 |
| GAP-QIM-04 | Q-IM | Outbox `markFailed` + `retry_count` 증가 구현 |
| GAP-QS-03 | Q-Sign DB | `qsign.processed_event` migration + `IdempotentEventStore` 추가 |
| GAP-QS-04 | Q-Sign | `AuthController` `X-Internal-Sig` 검증 구현 |

### P4 — 운영·모니터링

| ID | 대상 모듈 | 작업 내용 |
|----|---------|---------|
| GAP-OPS-02 | 전체 | Micrometer 커스텀 메트릭 (Handoff 성공률, CB 상태, Ticket 재사용) |
| GAP-QIM-05 | Q-IM | Snapshot 발행 기능 구현 (`snapshot_meta` 활용) |

---

## 6. 설계서 수정 플랜

> 프로젝트 코드에 있으나 설계서에 미반영된 항목 — 설계서 개정 필요

### 6.1 즉시 반영 필요 (설계서 정오표)

| 절 | 수정 내용 |
|----|---------|
| §9.1 / §9.3 | Keycloak Strategy B에서 IdO가 AuthResult 직접 생성·저장하는 조건과 흐름 명시 |
| §12.1 | onepass-fe BFF(Spring Boot) 제거 사유, FE Advisory Consumer → IdO 이관 사실 명시 |
| §10.6 또는 신규 §10.8 | Q-IM SP 수신 API 3패턴(QUERY/REGISTER/WITHDRAW) 상세 기술 |
| §11.6.2 | `ido.broker.mode` 설정 키와 전환 절차(`qsign` ↔ `keycloak`) 명시 |
| §16.3 | IdO도 `ido.processed_event` 테이블을 자체 운영함을 명시 |
| §18.4.2 | `Q_IM_CREDENTIAL_MAPPING` → `auth_mean_mapping`으로 현행화 |
| §18.5.4 | `IDO_PROVIDER_REGISTRY` → `ido.provider_config` 현행 테이블명 명시, `provider_type` 컬럼 추가 기술 |
| §22.2 표준 오류 코드 표 | `E-IDP-404`의 의미를 "Provider Registry 미등록" → "Circuit Breaker OPEN"으로 재검토 및 `E-IDP-405` 추가 여부 결정 |

### 6.2 §24 PoC 정합성 체크리스트 업데이트

| 항목 | 현재 상태 | 업데이트 내용 |
|------|---------|-------------|
| §24.1 v0.8.3 반영 핵심 변화 | - | P0~P1 수정 완료 후 v2.5.0 기재 |
| §24.4.1 코드 레벨 필수 점검 | 8개 항목 미충족 | 수정 완료 항목 ✅ 표기 |

---

## 7. 프로젝트 코드 수정 플랜

### 7.1 P0 — 즉시 수정 (런타임 기동 전제)

#### 7.1.1 IdO 인프라 구현체 4종 신규 작성

**작업 목록**:
```
ido/src/main/java/kr/go/smes/ido/infrastructure/
├── QimClientImpl.java             # RestTemplate + CircuitBreaker + Retry
├── UserStatusCacheImpl.java       # Redis TTL ≤5분 (RedisTemplate)
├── LastEventVersionStoreImpl.java # Redis Hash 구조
└── jpa/
    ├── AgencyMetaJpaEntity.java
    ├── AgencyMetaJpaRepository.java
    └── AgencyMetaRepositoryImpl.java

ido/src/main/java/kr/go/smes/ido/handoff/
└── TicketRepositoryImpl.java      # Redis 주 저장 + DB 감사 이력
```

#### 7.1.2 IdO `processed_event` migration 추가

**파일**: `ido/src/main/resources/db/migration/V5__add_processed_event.sql`
```sql
CREATE TABLE ido.processed_event (
    event_id       VARCHAR(36)  NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    event_type     VARCHAR(80),
    result_code    VARCHAR(50),
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ido_processed_event PRIMARY KEY (event_id, consumer_group)
);
CREATE INDEX idx_ido_processed_event_at
    ON ido.processed_event (consumer_group, processed_at DESC);
```

---

### 7.2 P1 — 설계서 승인 게이트

#### 7.2.1 `AuthResult`에 `authMethod` 필드 추가

**파일**: `platform-common/src/main/java/kr/go/smes/common/domain/AuthResult.java`
- `authMethod` 필드 추가 (`STANDARD_OIDC_KAKAO`, `NON_STANDARD_PASS` 등)
- `AuthServiceImpl`, `KeycloakOidcService`, `NonOidcAuthService`에서 규칙 적용

#### 7.2.2 Handoff 암호화·서명 구현

**파일**: 신규 `ido/src/main/java/kr/go/smes/ido/handoff/crypto/HandoffCryptoService.java`
- AES-256-GCM 암호화 (`javax.crypto.Cipher`)
- HMAC-SHA256 서명 (`javax.crypto.Mac`)
- `HandoffServiceImpl.issue()`에서 호출

#### 7.2.3 `agencySubjectId` HMAC 구현

**파일**: `ido/src/main/java/kr/go/smes/ido/policy/PolicyEngineImpl.java`
- `generateAgencySubjectId()` → `HMAC-SHA256(qimUserId + ":" + agencyCode, secretKey)` → Base64URL

#### 7.2.4 DLQ 설정 추가

**파일**: `ido/src/main/java/kr/go/smes/ido/config/KafkaConsumerConfig.java`
- `defaultErrorHandler()`에 `DeadLetterPublishingRecoverer` 연결
- DLQ 토픽: `{원본토픽}.dlt`
- 헤더 보존: `originalTopic`, `failureReason`, `failureCount`, `correlationId`, `eventId`

#### 7.2.5 `E-OPS-901` 추가

**파일**: `platform-common/src/main/java/kr/go/smes/common/error/PlatformErrorCode.java`
```java
OPS_EXTERNAL_PROVIDER_DOWN("E-OPS-901", HttpStatus.SERVICE_UNAVAILABLE, "외부 사업자 장애.")
```

---

### 7.3 P2 — API 계약 현행화

#### 7.3.1 `GlobalExceptionHandler` Retry-After 추가

**파일**: `q-im/src/main/java/kr/go/smes/qim/api/GlobalExceptionHandler.java`
- `E-OPS-901` 응답 시 `Retry-After` 헤더 추가

#### 7.3.2 `HandoffController` Idempotency-Key 처리

**파일**: `ido/src/main/java/kr/go/smes/ido/api/HandoffController.java`
- `@RequestHeader(value="Idempotency-Key", required=false)` 파라미터 추가
- 중복 요청 감지 후 캐시된 응답 반환

---

### 7.4 P3 — 데이터 모델 완성

#### 7.4.1 `ido.broker_audit_log` migration 추가

**파일**: `ido/src/main/resources/db/migration/V6__add_broker_audit_log.sql`
- 필수 필드: `correlation_id`, `provider_code`, `provider_tx_id`, `error_code`

#### 7.4.2 `ido.provider_config` `provider_type` 컬럼 추가

**파일**: 기존 V3 또는 신규 V6 migration
```sql
ALTER TABLE ido.provider_config
    ADD COLUMN provider_type VARCHAR(30) NOT NULL DEFAULT 'STANDARD_OIDC'
    CHECK (provider_type IN ('STANDARD_OIDC','SEMI_STANDARD_OIDC','NON_STANDARD'));
```

#### 7.4.3 Q-IM `addAuthMeanMapping` 실제 구현

**파일**: `q-im/src/main/java/kr/go/smes/qim/application/UserServiceImpl.java`
- `addAuthMeanMapping()` 메서드에 실제 JPA 저장 로직 완성

#### 7.4.4 Q-IM Outbox `markFailed` + `retry_count` 구현

**파일**: `q-im/src/main/java/kr/go/smes/qim/outbox/OutboxServiceImpl.java`
- 발행 실패 시 `retry_count` 증가
- 최대 재시도 초과 시 `markFailed()` 호출

---

## 변경 이력

| 버전 | 날짜 | 내용 |
|------|------|------|
| v1.0.0 | 2026-05-07 | 초기 Gap 분석 문서 작성 (설계서 v0.8.3 vs 프로젝트 e951e5a 기준) |
