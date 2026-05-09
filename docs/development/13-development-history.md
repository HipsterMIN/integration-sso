# 13. 개발 이력 (Development History)

> **문서 버전**: v1.9.2  
> **최종 수정**: 2026-05-09  
> **브랜치**: `genspark_ai_developer` → `main`

---

## 1. Git 커밋 이력 (최신순)

```
(v1.9.2)  feat(v1.9.2): P2 GAP 마감 — HandoffStrategy 완성, GAP-QS-03, GAP-QIM-05 Snapshot
99ad8c6  feat(v1.9.1): P1 GAP 마감 — DLQ 완전 구현, X-Internal-Sig 검증, Outbox 재시도 스케줄러
3f243fa  feat(v1.9.0): P0/P1/P2 GAP 마감 — auth_result V10, broker_audit_log 코드 연결, ProviderRouter, 동적 CB
3ffb47c  Merge pull request #23 from HipsterMIN/genspark_ai_developer
6404347  docs+fix(v1.8.0): build error fix and local-dev-guide v1.2.0 update
f5f1b2d  Merge pull request #22 from HipsterMIN/genspark_ai_developer
754dcb8  feat(v1.8.0): production-ready implementation — admin, rate-limit, strategy, PKCE, monitoring
68e83a9  Merge pull request #21 from HipsterMIN/genspark_ai_developer
88904bc  docs: PoC 분석 및 실제 개발 전환 계획 문서 추가 (2026-05-08)
272e572  Merge pull request #20 from HipsterMIN/genspark_ai_developer
862f283  docs: README.md v1.7.0 전면 업데이트
6376007  Merge pull request #19 from HipsterMIN/genspark_ai_developer
58097b2  feat(v1.7.0): 실제 유관기관 완전 클라이언트 구성
0900a76  Merge pull request #18 from HipsterMIN/genspark_ai_developer
f324d6d  feat(agency-stub): v1.6.0 — OIDC 클라이언트 완전 구현 (Webhook 수신 / Verify API / 세션 관리)
be42920  Merge pull request #17 from HipsterMIN/genspark_ai_developer
f141009  feat(v1.5.0): 유관기관 외부망 Webhook 연동 전체 스택 구현
```

---

## 2. 버전별 상세 변경 이력

### v1.9.2 (2026-05-09) — PR #27 (예정)

**목적**: P2 GAP 마감 — HandoffStrategy 완성, GAP-QS-03 Q-Sign 멱등 컨슈머, GAP-QIM-05 Snapshot 발행

#### 신규 생성 파일

| 파일 | 설명 |
|------|------|
| `ido/.../handoff/strategy/InternalSsoHandoffStrategy.java` | INTERNAL_SSO 전략 — `POST {ssoDomain}/internal/sso-session` SSO 세션 사전 등록 |
| `ido/.../handoff/strategy/ApacheGateHandoffStrategy.java` | APACHE_GATE 전략 — Apache mod_auth_openidc 호환 헤더 (`X-Remote-User`, `X-Auth-Level`, `X-Handoff-Token`, `X-Session-Expiry`) 사전 Push |
| `q-sign/.../kafka/IdempotentEventStore.java` | Q-Sign 멱등 이벤트 저장소 — `qsign.processed_event` ON CONFLICT DO NOTHING + `qsign.last_event_version` 버전 추적 |
| `q-sign/.../kafka/QimUserEventConsumer.java` | Q-Sign Q-IM 이벤트 컨슈머 — `@KafkaListener(qim.user.events)` + 6단계 멱등 처리 + USER_SUSPENDED/WITHDRAWN → auth_lock 강제 잠금 |
| `q-im/.../entity/SnapshotMetaJpaEntity.java` | `snapshot_meta` 테이블 JPA 엔터티 (PUBLISHED/FAILED 상태) |
| `q-im/.../repository/SnapshotMetaJpaRepository.java` | 최신 스냅샷 조회, 중복 발행 방지 쿼리 |
| `q-im/.../outbox/SnapshotService.java` | 스냅샷 발행 서비스 인터페이스 |
| `q-im/.../outbox/SnapshotServiceImpl.java` | 스냅샷 발행 구현체 — N개 이벤트마다 `qim.user.snapshot` Compacted Topic 발행 |

#### 수정된 파일

| 파일 | 변경 내용 |
|------|---------|
| `ido/.../handoff/HandoffServiceImpl.java` | Ticket 발급 후 `strategyFactory.getStrategy(integrationType).postIssue()` 호출 블록 추가 (7단계) |
| `ido/.../domain/AgencyMeta.java` | `ssoDomain`, `apacheGateEndpoint` 필드 추가 |
| `ido/.../infrastructure/AgencyMetaRepositoryImpl.java` | `toDomain()` integrationType/ssoDomain/apacheGateEndpoint 매핑 수정; `toEntity()` bridgeEndpoint 분기 수정 |
| `q-im/.../outbox/OutboxServiceImpl.java` | `SnapshotService` 주입 + `triggerSnapshotIfNeeded()` — 이벤트 발행 성공 후 스냅샷 트리거 |
| `docs/development/12-implementation-gaps.md` | v1.9.2 완성도 업데이트, P2 완료 항목 반영 |
| `docs/development/13-development-history.md` | v1.9.2 이력 추가 |

#### GAP 해소 현황

| GAP ID | 항목 | 해소 방법 |
|--------|------|---------|
| **GAP-HandoffStrategy** | INTERNAL_SSO / APACHE_GATE 전략 미구현 | `InternalSsoHandoffStrategy`, `ApacheGateHandoffStrategy` 신규 구현 |
| **GAP-QS-03** | Q-Sign `processed_event` Java 미구현 | `IdempotentEventStore` + `QimUserEventConsumer` 신규 구현 |
| **GAP-QIM-05** | `snapshot_meta` Java 미구현 | `SnapshotMetaJpaEntity`, `SnapshotMetaJpaRepository`, `SnapshotService`, `SnapshotServiceImpl`, `OutboxServiceImpl` 수정 |

#### 설계 결정 사항

1. **INTERNAL_SSO ssoDomain 재사용**: `agency_meta.sso_domain` (VARCHAR 200) 컬럼 전용 사용.  
   기관 내부 SSO 엔드포인트 기본 도메인 (예: `https://sso.agency-a.go.kr`)을 저장.

2. **APACHE_GATE bridge_endpoint 재사용**: `agency_meta.bridge_endpoint` 컬럼을 integrationType에 따라 분기.  
   BRIDGE면 `bridgeEndpoint`, APACHE_GATE면 `apacheGateEndpoint`로 매핑.  
   장기적으로는 전용 컬럼 추가 필요 (DEBT 항목으로 분류).

3. **Q-Sign auth_lock 강제 잠금 방식**: Q-Sign에는 `qim_user_id` 직접 저장 컬럼이 없으므로  
   `USER_SUSPENDED`/`USER_WITHDRAWN` 이벤트 수신 시 현재 잠긴 `auth_lock` 레코드를 대상으로  
   경고 로그 + 잠금 유지 처리. 향후 `auth_result`에 `qim_user_id` 컬럼 추가 시 완전 연동 가능.

4. **스냅샷 발행 주기**: `qim.snapshot.interval-events` 설정값(기본 10) 이상의 이벤트 발행마다 트리거.  
   `OutboxServiceImpl.sendToKafka()` 성공 콜백에서 비동기 트리거.  
   스냅샷 실패는 비치명적 처리 — 이벤트 발행 흐름에 영향 없음.

---

### v1.9.1 (2026-05-09) — PR #26

**커밋**: `99ad8c6`  
**목적**: P1 GAP 마감 — DLQ 완전 구현, X-Internal-Sig 검증, Outbox 재시도 스케줄러

---

### v1.9.0 (2026-05-09) — PR #24

**커밋**: `3f243fa`  
**목적**: P0/P1/P2 GAP 마감 — EDA 마스터 아키텍처 설계서 v0.8.3 기준 승인 게이트 항목 완료

#### 신규 생성 파일

| 파일 | 설명 |
|------|------|
| `ido/src/main/resources/db/migration/V10__extend_auth_result_and_provider_routing.sql` | auth_result 4개 컬럼 추가 + provider_circuit_config 테이블 + broker_audit_log 인덱스 |
| `ido/src/main/java/kr/go/smes/ido/broker/BrokerAuditLogService.java` | broker_audit_log 비동기 INSERT 서비스 (REDIRECT/CALLBACK/COMPLETE/FAIL/TIMEOUT) |
| `ido/src/main/java/kr/go/smes/ido/broker/provider/ProviderConfig.java` | STANDARD_OIDC/SEMI_STANDARD_OIDC/NON_STANDARD 도메인 모델 |
| `ido/src/main/java/kr/go/smes/ido/broker/provider/ProviderConfigRepository.java` | provider_config DB 조회 + Redis @Cacheable (TTL 60분) |
| `ido/src/main/java/kr/go/smes/ido/broker/provider/ProviderRouter.java` | provider_type → KEYCLOAK_RELAY/DIRECT_BROKER 런타임 라우팅 |
| `ido/src/main/java/kr/go/smes/ido/broker/provider/ProviderCircuitBreakerConfig.java` | provider_code 단위 Resilience4j CB 동적 생성 |
| `docs/handoff-note.md` | v1.9.0 최종 인수인계 패키지 |

#### 수정된 파일

| 파일 | 변경 내용 |
|------|---------|
| `KeycloakOidcService.java` | saveAuthResult() V10 4컬럼 삽입 + BrokerAuditLogService COMPLETE 기록 |
| `NonOidcAuthService.java` | saveAuthResult() auth_method 삽입 + COMPLETE 기록 |
| `KeycloakCallbackController.java` | CALLBACK/FAIL 기록 연결 + clientIp 추출 |
| `AgencyAdminService.java` | policyVersion "1.0" → @Value 주입 |
| `AgencyMetaRepositoryImpl.java` | policyVersion "1.0" → @Value 주입 |
| `AgencyMetaJpaEntity.java` | DEFAULT_POLICY_VERSION 상수 추가 |
| `PolicyEngineImpl.java` | policyVersion "1.0" → @Value 주입 + AgencyMetaJpaEntity import |
| `WebhookDispatcherService.java` | platformVersion "1.0" → @Value 주입 |
| `WebhookDispatchOutboxRelay.java` | X-Platform-Version "1.0" → @Value 주입 |
| `RedisConfig.java` | provider-config 캐시(TTL 60분) 추가 |
| `application.yml` | platform-version, policy.default-version, provider.circuit-cache-ttl-seconds 추가 |

---

### v1.8.0 (2026-05-08) — PR #22, #23

**목적**: Production-ready 구현 — Admin API, Rate Limit, HandoffStrategy, PKCE, 모니터링

#### 주요 변경 사항

| 구분 | 내용 |
|------|------|
| **Admin API** | `AgencyAdminController`, `AgencyAdminService` — 기관 CRUD, 활성화/비활성화, Key 로테이션 |
| **Rate Limiting** | `AgencyRateLimiter` — Redis 슬라이딩 윈도우 TPS + 일별 쿼터 |
| **HandoffStrategy** | `HandoffStrategy` Pattern — DIRECT, BRIDGE 구현 |
| **CallbackUrlValidator** | Callback URL 화이트리스트 검증 |
| **PKCE** | `PkceService` — code_verifier/challenge 생성 (Q-Sign) |
| **CI 암호화** | `CiCryptoService`, `CiCryptoServiceImpl` (Q-IM) |
| **PII 마스킹** | `PiiMaskingService` (Q-IM) |
| **DI 생성** | `DiGenerationService` (Q-IM) |
| **사용자 등록** | `UserRegistrationService`, `UserRegistrationServiceImpl` (Q-IM) |
| **Q-IM V3** | `V3__add_ci_encryption_and_status_history.sql` |
| **모니터링** | Prometheus + Grafana + Loki docker-compose profile 추가 |
| **빌드 오류 수정** | 6개 빌드 오류 수정 (컴파일 에러 포함) |
| **local-dev-guide** | v1.2.0 전면 업데이트 (94KB) |

---

### v1.7.0 (2026-05-08) — PR #19, #20

**목적**: 실제 유관기관 완전 클라이언트 구성

#### 주요 변경 사항

| 구분 | 내용 |
|------|------|
| **agency-stub 고도화** | `AgencyEventPollingController`, `IdoTicketClient`, `IdoVerifyClient` 완전 구현 |
| **이벤트 폴링** | `AgencyEventPollingController.getEvents()` 실제 IdO 호출 |
| **Verify 실 구현** | `IdoVerifyClient` RestTemplate + Resilience4j |
| **README 업데이트** | v1.7.0 아키텍처 구성도 전면 개편 |

---

### v1.6.0 (2026-05-08) — PR #17

**목적**: agency-stub v1.6.0 — OIDC 클라이언트 완전 구현

#### 주요 변경 사항

| 구분 | 내용 |
|------|------|
| **Webhook 수신** | `WebhookInboundController` — HMAC-SHA256 서명 검증 포함 |
| **Verify API 실 구현** | `AgencyEntryController.callIdoVerify()` Stub → RestTemplate 실제 호출 |
| **기관 API Key 검증** | `AgencyApiKeyInterceptor` — X-Agency-Key 헤더 PBKDF2 검증 |
| **기관 로컬 세션** | `AgencySessionService` — AGSID 발급/관리 |

---

### v1.5.0 (2026-05-08) — PR #16

**목적**: 유관기관 외부망 Webhook 연동 전체 스택 구현

#### 주요 변경 사항

| 구분 | 내용 |
|------|------|
| **WebhookDispatcherService** | Outbox 적재 (HandoffEvent / MemberLookup / MemberWithdrawn) |
| **WebhookDispatchOutboxRelay** | 500ms 폴링 → HTTPS POST → 지수 백오프 재시도 (2s/4s/8s) |
| **HandoffEventConsumer** | `ido.handoff.events` 수신 → WebhookDispatcherService 호출 |
| **QsignAuthEventConsumer** | `qsign.auth.events` 수신 → Redis Pre-warming + Advisory |
| **AuthResultCacheService** | Redis `ido:auth_result:{correlationId}` TTL 300s (60k명 대응) |
| **SessionAdvisoryPublisher** | `platform.session.advisory` 발행 + Outbox 폴백 |
| **AuditLogPublisher** | DB 기록 후 `platform.audit.log` Kafka 비동기 발행 |
| **AsyncConfig** | `auditExecutor` (4/16/10000) / `webhookExecutor` (4/20/5000) |
| **V7 마이그레이션** | `agency_webhook_config`, `webhook_dispatch_outbox`, `audit_log`, `member_lookup_request` |

---

### v1.4.2 (2026-05-08)

**목적**: 아키텍처 원칙 보완 — 유관기관 외부망 배치 원칙 명확화

#### 주요 변경 사항

| 구분 | 내용 |
|------|------|
| **README 수정** | 아키텍처 구성도 전면 수정 (agency-stub 외부망 명시) |
| **신규 문서** | `docs/agency-external-arch-supplement.md` — 외부망 격리 설계 보완서 |
| **코드 주석 추가** | `HandoffEventConsumer.java` — PoC 전용 Javadoc |
| **P1-P2 이슈 등록** | agency-stub Kafka 직접 구독 PoC 경고 |

---

### v1.4.1 (2026-05-08) — PR #15

**목적**: 런타임 기동 오류 9개 수정 + Dockerfile 전 모듈 신규 작성

#### 수정된 버그

| 버그 | 모듈 | 내용 |
|------|------|------|
| BUG-01 | Q-Sign | V2 SQL `IMMUTABLE` 오류 (부분 인덱스 비결정적 함수) |
| BUG-02 | Q-Sign | `retryCount` 타입 불일치 (DB: SMALLINT, Java: int) |
| BUG-03 | Q-Sign | `AuthResultRepository` 빈 없음 → `AuthResultRepositoryImpl` + JPA 구현체 신규 |
| BUG-04 | Q-Sign | `LockRepository` 빈 없음 → `LockRepositoryImpl` + JPA 구현체 신규 |
| BUG-05 | Q-Sign | `auth_method` 컬럼 누락 → V5 migration 추가 |
| BUG-06 | IdO | `QimEventConsumer` 빈 이름 충돌 |
| BUG-07 | IdO | `cacheManager` 빈 중복 → `RedisConfig`에 통합 |
| BUG-08 | IdO | V4 마이그레이션 `IMMUTABLE` 오류 |
| BUG-09 | IdO | V5 마이그레이션 재실행 오류 |
| BUG-10 | IdO | `AesSharedKeyDecryptor` Base64 디코딩 오류 |

#### 신규 추가

| 항목 | 내용 |
|------|------|
| **Dockerfile (Q-Sign)** | 멀티스테이지 빌드, non-root `qsign` 사용자 |
| **Dockerfile (IdO)** | 멀티스테이지 빌드, non-root `ido` 사용자 |
| **Dockerfile (Q-IM)** | 멀티스테이지 빌드, non-root `qim` 사용자 |
| **docker-compose.yml** | `onepass-qsign` 서비스 추가 (172.20.0.24:8081) |
| **HMAC-SHA256 내부 서명** | `KeycloakCallbackService.buildInternalSig()` PoC → 실제 HMAC |
| **SHA-256 identifierHash** | `AuthServiceImpl` placeholder → SHA-256 |
| `application-local.yml` | 전 모듈 로컬 개발 프로파일 신규 작성 |

---

### v1.2.0 (2026-05-07)

**목적**: Q-IM SP 수신 API — IdO 완전 중재 패턴 구현

#### 주요 변경 사항

| 구분 | 내용 |
|------|------|
| **QimSpReceiverController** | `POST /api/qim/sp/v1/member/{query|register|withdraw}` 3종 |
| **QimSpReceiverService** | 멱등성·AES 복호화·instMbrId 매핑·Outbox 발행 |
| **InstMbrIdMappingRepository** | `ido.inst_mbr_id_mapping` CRUD |
| **SpReceiverIdempotencyStore** | `ido.sp_receiver_idempotency` TTL=7일 |
| **AesSharedKeyDecryptor** | AES-256-CBC, identifierHash SHA-256 |
| **QimSpMemberEventConsumer** | `qim.sp.member.events` Kafka 구독 |
| **V4 마이그레이션** | `inst_mbr_id_mapping`, `sp_receiver_idempotency`, `qim_sp_receiver_log` |

---

### v1.1.0

**목적**: Q-Sign Keycloak OIDC 브로커링 완전 구현

| 구분 | 내용 |
|------|------|
| **KeycloakAuthUrlController** | OIDC 인가 URL 생성 |
| **KeycloakCallbackService** | 콜백 처리, JWKS 검증, AuthResult 저장 |
| **KeycloakStateStore** | Redis state 관리 (TTL 300s) |
| **KeycloakJwksVerifier** | JWT RS256 서명 검증 |
| **realm-export.json** | Keycloak onepass realm + kakao IdP |

---

### v1.0.0 (초기 PoC)

**목적**: PoC 기반 코드 — 핵심 인프라 구축

| 구분 | 내용 |
|------|------|
| **멀티모듈 구조** | platform-common, q-sign, q-im, ido, agency-stub, onepass-fe |
| **기본 OIDC 흐름** | PoC 수준 인증 흐름 |
| **Transactional Outbox** | 전 모듈 Kafka 발행 기반 |
| **AgencyMeta** | 기관 메타 기본 구조 |
| **PolicyEngine** | PoC 수준 정책 엔진 |
| **V1~V3 마이그레이션** | IdO, Q-Sign, Q-IM 기본 스키마 |
| **docker-compose.yml** | 기본 인프라 (DB, Redis, Kafka, Keycloak) |

---

## 3. PR 이력

| PR # | 제목 | 상태 |
|------|------|------|
| #27 | feat(v1.9.2): P2 GAP 마감 — HandoffStrategy 완성, GAP-QS-03, GAP-QIM-05 | 🔄 예정 |
| #26 | feat(v1.9.1): P1 GAP 마감 — DLQ, X-Internal-Sig, Outbox retry | ✅ Open |
| #24 | feat(v1.9.0): P0/P1/P2 GAP 마감 | ✅ Open |
| #23 | Merge PR: v1.8.0 docs+fix | ✅ Merged |
| #22 | feat(v1.8.0): production-ready | ✅ Merged |
| #21 | docs: PoC 분석 문서 | ✅ Merged |
| #20 | docs: README v1.7.0 | ✅ Merged |
| #19 | feat(v1.7.0): 유관기관 완전 클라이언트 | ✅ Merged |
| #18 | Merge PR: v1.6.0 | ✅ Merged |
| #17 | feat(v1.6.0): agency-stub OIDC 완전 구현 | ✅ Merged |
| #16 | feat(v1.5.0): Webhook 연동 전체 스택 | ✅ Merged |
| #15 | feat(v1.4.1): 버그 수정 + Dockerfile | ✅ Merged |

---

## 4. 설계서 버전 이력

| 설계서 | 버전 | 작성일 |
|--------|------|--------|
| EDA 마스터 아키텍처 설계서 | v0.8.3 | 2026-05-07 |
| Gap 분석 (v0.8.3 vs 코드) | v1.0.0 | 2026-05-07 |
| EDA 마스터 아키텍처 GAP 분석 | v0.8 | 2026-05-07 |
| OIDC 브로커링 설계서 | - | 2026-05-07 |
| Q-IM ↔ IdO 연동 아키텍처 | v1.0.0 | 2026-05-07 |
| Q-IM SP 수신 API 명세서 | v1.0.0 | 2026-05-07 |
| 유관기관 외부망 배치 설계 보완서 | ARCH-SUPP-001 | 2026-05-08 |
| 운영 준비 분석 | v2.2 | 2026-05-08 |
| 실제 개발 전환 계획서 | v2.0.0-dev | 2026-05-08 |
| 미구현 분석 보고서 | v1.7.0 기준 | 2026-05-08 |
| 회원 전환 구현 플랜 | v1.2.0 | 2026-05-07 |
| 인수인계 패키지 | v1.9.0 | 2026-05-09 |

---

*이전 문서: [12-implementation-gaps.md](12-implementation-gaps.md)*  
*문서 목록: [README.md](README.md)*
