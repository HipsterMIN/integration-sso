# 13. 개발 이력 (Development History)

> **문서 버전**: v3.1.0  
> **최종 수정**: 2026-05-13  
> **브랜치**: `shipster` → `main`

---

## 1. Git 커밋 이력 (최신순)

```
fafc795  feat(p3): P3-01/05/06 AgencyLookup·보호자인증·기업회원전환 + 운영 버그 수정 8종  (v3.1.0)
5b39626  Merge pull request #84 from HipsterMIN/shipster
52cc28a  feat(q-im): P2 회원 생명주기 완성 — 탈퇴 4종·동의 스키마·ConversionSession 상태 기계  (v2.0.0)
7456517  test(sso): S7-T6 완료 반영 Javadoc 정리 + NiceAuthService/HandoffController 단위 테스트 추가  (v2.1.0)
fda29ee  Merge pull request #83 from HipsterMIN/genspark_ai_developer
(v3.0.0)  feat(p1~p3): SSO 운영 보안 패치 P1~P3 — UNIQUE 복합 키, InternalApiKeyInterceptor, redirectUri 수정  (PR #82)
(v2.4.0)  feat(sso): 유관기관 SSO 완성 — Q-IM 소셜 계정 API, GUEST 정책  (PR #81)
(v2.3.1)  fix(keycloak): identifierHash SHA-256 수정, P1/P2 보안 패치  (PR #80)
(v2.3.0)  feat(fe): SLO FE 연동, useAuthState, ErrorBoundary  (PR #52)
(v2.2.1)  feat(ff): 18개 Feature Flag 체계  (PR #51)
(v2.2.0)  feat(prod): Redisson, Resilience4j, Bean Validation, OTel AOP, K8s  (PR #50)
(v2.1.0)  feat(q-im): NICE/OACX BFF, S7-T6 CI→Q-IM  (PR #45)
(v1.9.3)  feat(v1.9.3): P1-06 완성 — IdO 기관 이벤트 폴링 API (GET /api/v1/agency/events)
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

### v3.1.0 (2026-05-13) — PR [#85](https://github.com/HipsterMIN/integration-sso/pull/85)

**커밋**: `fafc795`  
**브랜치**: `shipster`  
**목적**: P3-01/05/06 운영 코드 심층 분석 후 발견된 결함 8종 순차 수정 (Sprint 12)

**배경**:  
P3-05(14세 미만 보호자 인증)/P3-06(기업회원 전환) 구현 후 운영 투입 관점에서 심층 코드 리뷰를 진행.  
GDPR §17 위반, JPA 1차 캐시 오염, correlationId 혼용 버그, 입력 검증 누락 등 8종 결함 식별 및 수정.

**빌드 결과**: `DOCKER_UNAVAILABLE=true ./gradlew :idem-registry:clean :idem-registry:test --no-daemon`
- Total: **219 tests**, Failures: 0, Errors: 0, Skipped: **30**

**수정 파일 목록**:

| 파일 | 유형 | Fix | 내용 |
|------|------|-----|------|
| `idem-registry/.../agency/AgencyMemberLookupServiceImpl.java` | **수정** | Fix 1 | Virtual Thread Executor `try-finally shutdown()` + 30s 절대 데드라인 |
| `idem-registry/.../withdrawal/WithdrawalServiceImpl.java` | **수정** | Fix 2 | `deletePii()` — `guardian_qim_user_id`, `guardian_consent_at` NULL 처리 추가 |
| `idem-registry/.../user/UserRegistrationServiceImpl.java` | **수정** | Fix 2 | 동일 GDPR V6 컬럼 NULL 처리 추가 |
| `idem-registry/.../api/GuardianConsentController.java` | **수정** | Fix 3/5 | `@Valid @RequestBody`, `X-Correlation-Id` 헤더 추출 + `getStatus()` 호출 수정 |
| `idem-registry/.../api/BizMemberConversionController.java` | **수정** | Fix 3/4 | `@Valid @RequestBody`, `HttpStatus.CREATED` 반환 |
| `idem-registry/.../api/GlobalExceptionHandler.java` | **수정** | Fix 3 | `MethodArgumentNotValidException` 핸들러 추가 (E-IM-400) |
| `idem-registry/.../biz/BizMemberConversionServiceImpl.java` | **수정** | Fix 4 | `existsById(qimUserId)` + `existsByBizRegNo()` 중복 전환 방지 체크 |
| `idem-registry/.../guardian/GuardianConsentService.java` | **수정** | Fix 5 | `getStatus()` 시그니처 변경: `(qimUserId)` → `(qimUserId, correlationId)` |
| `idem-registry/.../guardian/GuardianConsentServiceImpl.java` | **수정** | Fix 5 | `PlatformException` 인수 순서 수정 (qimUserId → correlationId) |
| `idem-registry/.../jpa/repository/UserProfileJpaRepository.java` | **수정** | Fix 6 | `@Modifying(clearAutomatically=true, flushAutomatically=true)` 추가 |
| `idem-registry/.../guardian/GuardianConsentServiceImplTest.java` | **수정** | Fix 5 | `getStatus()` 호출 4곳 CID 파라미터 추가 |
| `idem-registry/.../user/UserRegistrationServiceImplTest.java` | **수정** | Fix 7 | isMinor 저장 검증 테스트 3종 추가 (동적 연도 계산) |
| `idem-registry/.../integration/QimLifecycleIntegrationTest.java` | **수정** | Fix 8 | S8(보호자 동의 4종) + S9(기업회원 전환 6종) E2E 시나리오 추가 |

**Fix별 상세**:

| Fix | 문제 | 해결 | 영향 |
|-----|------|------|------|
| **Fix 1** | Virtual Thread Executor shutdown() 누락 → 스레드 누수 | try-finally + 30s 데드라인 | 운영 안정성 |
| **Fix 2** | deletePii() V6 컬럼 누락 → GDPR §17 위반 | guardian 컬럼 2개 NULL 처리 (2파일) | 법적 준수 |
| **Fix 3** | 컨트롤러 입력 미검증 → 빈 필드 서비스 호출 가능 | @Valid/@NotBlank + 400 핸들러 | 안전성 |
| **Fix 4** | 동일 qimUserId 재전환 가능 + 201 누락 | existsById 체크 + CREATED 반환 | 데이터 무결성 |
| **Fix 5** | getStatus()에서 qimUserId가 correlationId 자리 전달 | 시그니처 변경 + 헤더 추출 | 오류 추적성 |
| **Fix 6** | JPQL UPDATE 후 1차 캐시 오염 | clearAutomatically=true | 데이터 정합성 |
| **Fix 7** | isMinor 저장 검증 테스트 없음 | 동적 연도 계산 3종 테스트 | 회귀 방지 |
| **Fix 8** | V6 통합 테스트 없음 | S8(4종)/S9(6종) Testcontainers E2E | 운영 신뢰성 |

**설계 결정 사항**:

1. **Virtual Thread (JDK 21)**: `Executors.newVirtualThreadPerTaskExecutor()`는 무제한 생성이 가능하므로 반드시 `try-finally`로 `shutdown()` 보장. 절대 데드라인 30초로 행잉 방지.

2. **GDPR 완전 준수**: `deletePii()` 호출 지점이 2곳(`WithdrawalServiceImpl`, `UserRegistrationServiceImpl`)임을 확인. 양쪽 모두 V6 컬럼 추가 필요.

3. **PlatformException 인수 순서**: `PlatformException(PlatformErrorCode, String correlationId)` — 두 번째 인수는 correlationId여야 함. qimUserId를 전달하면 분산 추적 시스템에서 correlationId로 잘못 해석됨.

4. **@Modifying 캐시 정책**: `clearAutomatically=true`는 UPDATE 후 영속성 컨텍스트 1차 캐시를 자동 비우고, `flushAutomatically=true`는 UPDATE 실행 전 pending 변경사항을 flush하여 순서 보장.

5. **Testcontainers 조건부 실행**: `@DisabledIfEnvironmentVariable(named="DOCKER_UNAVAILABLE")`으로 CI/CD 환경(Docker 없음)에서도 빌드 성공 보장. Docker 환경에서는 자동 실행.

6. **rebase 충돌 해결**: `ConversionSessionServiceImpl` add/add 충돌 발생 시 `--theirs` (remote 우선) 전략 적용.

---

### v3.0.0 (2026-05-13) — PR [#82](https://github.com/HipsterMIN/integration-sso/pull/82)

**목적**: 유관기관 SSO 운영 보안 패치 P1~P3

| 패치 | 내용 |
|------|------|
| **P1** | `V4__fix_social_sso.sql` — `uq_identifier_hash` DROP → `uq_identifier_hash_provider(identifier_hash, provider_code)` UNIQUE 복합 키 |
| **P2** | `InternalApiKeyInterceptor` — `MessageDigest.isEqual()` 상수 시간 비교, `/api/v1/internal/**` 전체 보호 |
| **P3** | `HandoffController` — `redirectUri` null 수정 (`.redirectUri(req.getCallbackUrl())` 누락) |

---

### v2.4.0 (2026-05-13) — PR [#81](https://github.com/HipsterMIN/integration-sso/pull/81)

**목적**: 유관기관 SSO 완성 — Keycloak OIDC 브로커 + 소셜 계정 식별 + GUEST 정책

| 파일 | 내용 |
|------|------|
| `idem-registry/.../api/UserController.java` | `find-by-social-sub` + `register-social` 엔드포인트 |
| `idem-hub/.../broker/keycloak/KeycloakOidcService.java` | OIDC 콜백 처리, SHA-256(sub) identifierHash |
| `idem-hub/.../policy/PolicyEngineImpl.java` | HMAC fallback 제거, GUEST 정책 |
| `idem-common/.../HandoffPayload.java` | `HandoffState.GUEST` 추가 |
| `idem-tenant-sample/.../api/AgencyEntryController.java` | `case GUEST` 분기 처리 |

---

### v1.9.3 (2026-05-09) — PR #28 (예정)

**목적**: P1-06 완성 — 유관기관 HTTP 이벤트 폴링 API

**배경**:
유관기관은 Kafka에 직접 접속할 수 없다. `WebhookDispatcherService`가 이미 `webhook_dispatch_outbox`에
마스킹·저장한 이벤트를 기관이 HTTP 폴링으로 가져갈 수 있는 엔드포인트가 전혀 없었다.
이번 버전에서 `GET /api/v1/agency/events` 전체 스택을 완성하였다.

**주요 구현 내용**:

| 파일 | 유형 | 설명 |
|------|------|------|
| `idem-hub/.../api/dto/AgencyEventResponse.java` | **신규** | 이벤트 단건 DTO — `dispatchId`, `eventType`, `payload`, `status`, `createdAt`, `dispatchedAt` |
| `idem-hub/.../api/dto/AgencyEventListResponse.java` | **신규** | 폴링 목록 래퍼 — `events[]`, `count`, `hasMore`, `polledAt`, `queryInfo` |
| `idem-hub/.../webhook/AgencyEventQueryService.java` | **신규** | 폴링 서비스 인터페이스 — `queryEvents()`, `markAsRead()` |
| `idem-hub/.../webhook/AgencyEventQueryServiceImpl.java` | **신규** | `webhook_dispatch_outbox` JdbcTemplate 동적 SQL 조회, JSONB payload 역직렬화, PENDING→DISPATCHED mark |
| `idem-hub/.../api/AgencyEventController.java` | **신규** | `GET /api/v1/agency/events` + `POST /{dispatchId}/read` — since 커서·eventType 필터·limit 검증 |
| `idem-hub/.../fe/config/IdoWebMvcConfig.java` | **수정** | `/api/v1/agency/**` 인터셉터(X-Agency-Key) + CORS 등록 |

**API 설계**:
```
GET /api/v1/agency/events
  Header:  X-Agency-Code, X-Agency-Key (HandoffAgencyKeyInterceptor 검증)
  Query:   limit(1~100, 기본20), eventType(선택), since(ISO-8601, 선택)
  Response 200: { events:[...], count:N, hasMore:bool, polledAt:"...", queryInfo:{...} }

POST /api/v1/agency/events/{dispatchId}/read
  Header:  X-Agency-Code, X-Agency-Key
  Response 204: 읽음 처리 성공
  Response 404: 존재하지 않거나 권한 없음 (보안: 정보 노출 방지)
```

**설계 결정**:
- **데이터 소스**: `webhook_dispatch_outbox` 재활용 — 신규 테이블 없이 기존 Outbox 활용
- **status 필터**: `PENDING + DISPATCHED` 반환 — FAILED/SKIPPED는 기관 불필요
- **since 커서**: `created_at ASC` 정렬 + `created_at > ?` 조건으로 연속 폴링 중복 방지
- **X-Agency-Key 재사용**: `HandoffAgencyKeyInterceptor` 기존 검증 로직 그대로 활용, `/api/v1/agency/**` 경로 추가
- **소유권 검증**: `markAsRead()`에서 `agency_code = ?` AND 조건으로 타 기관 레코드 변경 방지
- **멱등**: 이미 DISPATCHED인 레코드 re-mark 시 0 rows updated → 404 반환 (비치명적)
- **payload**: JSONB → `Map<String,Object>` 역직렬화, 실패 시 원문 문자열 반환 (비치명적)

**빌드 결과**: `./gradlew build -x test` → BUILD SUCCESSFUL

---

### v1.9.2 (2026-05-09) — PR #27 (예정)

**목적**: P2 GAP 마감 — HandoffStrategy 완성, GAP-QS-03 Q-Sign 멱등 컨슈머, GAP-QIM-05 Snapshot 발행

#### 신규 생성 파일

| 파일 | 설명 |
|------|------|
| `idem-hub/.../handoff/strategy/InternalSsoHandoffStrategy.java` | INTERNAL_SSO 전략 — `POST {ssoDomain}/internal/sso-session` SSO 세션 사전 등록 |
| `idem-hub/.../handoff/strategy/ApacheGateHandoffStrategy.java` | APACHE_GATE 전략 — Apache mod_auth_openidc 호환 헤더 (`X-Remote-User`, `X-Auth-Level`, `X-Handoff-Token`, `X-Session-Expiry`) 사전 Push |
| `idem-gate/.../kafka/IdempotentEventStore.java` | Q-Sign 멱등 이벤트 저장소 — `qsign.processed_event` ON CONFLICT DO NOTHING + `qsign.last_event_version` 버전 추적 |
| `idem-gate/.../kafka/QimUserEventConsumer.java` | Q-Sign Q-IM 이벤트 컨슈머 — `@KafkaListener(idem.registry.user.events)` + 6단계 멱등 처리 + USER_SUSPENDED/WITHDRAWN → auth_lock 강제 잠금 |
| `idem-registry/.../entity/SnapshotMetaJpaEntity.java` | `snapshot_meta` 테이블 JPA 엔터티 (PUBLISHED/FAILED 상태) |
| `idem-registry/.../repository/SnapshotMetaJpaRepository.java` | 최신 스냅샷 조회, 중복 발행 방지 쿼리 |
| `idem-registry/.../outbox/SnapshotService.java` | 스냅샷 발행 서비스 인터페이스 |
| `idem-registry/.../outbox/SnapshotServiceImpl.java` | 스냅샷 발행 구현체 — N개 이벤트마다 `idem.registry.user.snapshot` Compacted Topic 발행 |

#### 수정된 파일

| 파일 | 변경 내용 |
|------|---------|
| `idem-hub/.../handoff/HandoffServiceImpl.java` | Ticket 발급 후 `strategyFactory.getStrategy(integrationType).postIssue()` 호출 블록 추가 (7단계) |
| `idem-hub/.../domain/AgencyMeta.java` | `ssoDomain`, `apacheGateEndpoint` 필드 추가 |
| `idem-hub/.../infrastructure/AgencyMetaRepositoryImpl.java` | `toDomain()` integrationType/ssoDomain/apacheGateEndpoint 매핑 수정; `toEntity()` bridgeEndpoint 분기 수정 |
| `idem-registry/.../outbox/OutboxServiceImpl.java` | `SnapshotService` 주입 + `triggerSnapshotIfNeeded()` — 이벤트 발행 성공 후 스냅샷 트리거 |
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

4. **스냅샷 발행 주기**: `idem.registry.snapshot.interval-events` 설정값(기본 10) 이상의 이벤트 발행마다 트리거.  
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
| `idem-hub/src/main/resources/db/migration/V10__extend_auth_result_and_provider_routing.sql` | auth_result 4개 컬럼 추가 + provider_circuit_config 테이블 + broker_audit_log 인덱스 |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/broker/BrokerAuditLogService.java` | broker_audit_log 비동기 INSERT 서비스 (REDIRECT/CALLBACK/COMPLETE/FAIL/TIMEOUT) |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/broker/provider/ProviderConfig.java` | STANDARD_OIDC/SEMI_STANDARD_OIDC/NON_STANDARD 도메인 모델 |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/broker/provider/ProviderConfigRepository.java` | provider_config DB 조회 + Redis @Cacheable (TTL 60분) |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/broker/provider/ProviderRouter.java` | provider_type → KEYCLOAK_RELAY/DIRECT_BROKER 런타임 라우팅 |
| `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/broker/provider/ProviderCircuitBreakerConfig.java` | provider_code 단위 Resilience4j CB 동적 생성 |
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
| **HandoffEventConsumer** | `idem.hub.handoff.events` 수신 → WebhookDispatcherService 호출 |
| **QsignAuthEventConsumer** | `idem.gate.auth.events` 수신 → Redis Pre-warming + Advisory |
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
| **docker-compose.yml** | `idem-gate` 서비스 추가 (172.20.0.24:8081) |
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
| **QimSpMemberEventConsumer** | `idem.registry.sp.member.events` Kafka 구독 |
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
| **멀티모듈 구조** | platform-common, q-sign, q-im, ido, agency-stub, idem-console |
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
| #28 | feat(v1.9.3): P1-06 완성 — IdO 기관 이벤트 폴링 API | 🔄 예정 |
| #27 | feat(v1.9.2): P2 GAP 마감 — HandoffStrategy 완성, GAP-QS-03, GAP-QIM-05 | ✅ Open |
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
