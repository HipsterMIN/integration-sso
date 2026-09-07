# 12. 미구현 항목 및 후속 계획 (Implementation Gaps)

> **문서 버전**: v3.1.0  
> **최종 수정**: 2026-05-13  
> **기준 분석 문서**: `docs/_archive/2026-05-22/internal/analysis/2026-05-08_unimplemented_analysis.md`, `docs/_archive/2026-05-22/internal/analysis/gap-analysis-v0.8.3-vs-project.md`  
> **v1.9.2 변경**: P2 GAP 항목 전체 구현 완료 (HandoffStrategy 완성, GAP-QS-03, GAP-QIM-05)  
> **v1.9.3 변경**: P1-06 구현 완료 — IdO `GET /api/v1/agency/events` 기관 이벤트 폴링 API  
> **v2.0.0 변경**: P2 회원 생명주기 완성 — 탈퇴 4종 · 개인정보 동의 · ConversionSession 상태 기계 구현  
> **v2.1.0 변경**: P3-01 AgencyMemberLookupService 실제 연동 + P3-02 E2E Testcontainers 통합 테스트 + P3-05 14세 미만 보호자 인증 + P3-06 기업회원 전환  
> **v3.0.0 변경**: 유관기관 SSO 완성 — Keycloak OIDC 브로커, 소셜 계정 식별(SHA-256 sub), GUEST 정책, P1~P3 보안 패치  
> **v3.1.0 변경**: P3 운영 버그 수정 8종 완료 — GDPR V6 컬럼, 입력 검증, 중복 방지, correlationId 버그, @Modifying, isMinor 테스트, V6 E2E 통합 테스트(S8/S9)

---

## 1. 현재 완성도 요약

v3.1.0 기준 전체 구현 완성도: **약 96%** (프리프로덕션 단계)

| 모듈 | 완성도 | 비고 |
|------|--------|------|
| platform-common | **100%** | 도메인·이벤트·에러코드 완비 (E-IM-212~217 보호자/기업 에러코드 추가) |
| Q-Sign | **95%** | GAP-QS-03 멱등 컨슈머 완성; X-Internal-Sig 수신 검증 미구현 |
| Q-IM | **99%** | 탈퇴 4종 · 동의 스키마 · ConversionSession · 보호자 인증 · 기업회원 전환 + Fix 1~8 운영 버그 수정 완성 |
| IdO | **99%** | P1-06 기관 폴링 API 완성; HandoffStrategy 완전 구현; Keycloak SSO 완성 |
| agency-stub | **90%** | Docker 격리 미완성, mTLS P3 |
| idem-console | **60%** | 회원 전환·관리 UI 미구현 |
| 인프라/Docker | **100%** | 전 모듈 Dockerfile + docker-compose 완비 |
| 보안 | **99%** | GDPR V6 완전 준수, InternalApiKeyInterceptor, redirectUri 검증, UNIQUE 복합 키 |
| 테스트 | **65%** | q-im 219개 통과 + 30 skipped · S1~S9 시나리오 (V6 E2E 포함) 완성 |

---

## 1-A. v3.1.0 운영 버그 수정 내역 (Sprint 12)

> **배경**: P3-05(보호자 인증)/P3-06(기업회원 전환) 구현 후 심층 운영 관점 분석에서 발견된 결함 8종 순차 수정.  
> **빌드**: `DOCKER_UNAVAILABLE=true ./gradlew :idem-registry:clean :idem-registry:test --no-daemon` → **219 tests, 0 failures, 30 skipped**  
> **커밋**: `fafc795` | **PR**: [#85](https://github.com/HipsterMIN/integration-sso/pull/85)

### Fix 1 — Virtual Thread Executor 정리 누락 (AgencyMemberLookupServiceImpl)

| 항목 | 내용 |
|------|------|
| **문제** | `Executors.newVirtualThreadPerTaskExecutor()` 사용 후 `shutdown()` 미호출 → 스레드 누수 가능 |
| **영향** | 운영 환경에서 68개 유관시스템 동시 조회 시 Virtual Thread 무제한 생성 위험 |
| **해결** | `try-finally` 블록으로 `executor.shutdown()` 보장 + `Duration.ofSeconds(30)` 절대 데드라인 타임아웃 |
| **수정 파일** | `idem-registry/.../agency/AgencyMemberLookupServiceImpl.java` |

```java
// 수정 후 패턴
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
try {
    // ... 68개 기관 병렬 조회
} finally {
    executor.shutdown();
    executor.awaitTermination(30, TimeUnit.SECONDS); // 절대 데드라인
}
```

### Fix 2 — GDPR Right to be Forgotten V6 컬럼 누락

| 항목 | 내용 |
|------|------|
| **문제** | `deletePii()`에서 V6 신규 컬럼(`guardian_qim_user_id`, `guardian_consent_at`) NULL 처리 누락 → GDPR §17 위반 |
| **영향** | 탈퇴한 사용자의 보호자 정보가 DB에 영구 잔존 |
| **해결** | 두 파일 모두 UPDATE 쿼리에 V6 컬럼 2개 NULL 처리 추가 |
| **수정 파일** | `WithdrawalServiceImpl.java`, `UserRegistrationServiceImpl.java` (2파일) |

```sql
-- 수정 후 쿼리
UPDATE user_profile
SET name_masked           = NULL,
    mobile_masked         = NULL,
    ci                    = NULL,
    di_map                = NULL,
    extra_attributes      = NULL,
    guardian_qim_user_id  = NULL,   -- ← V6 추가
    guardian_consent_at   = NULL,   -- ← V6 추가
    updated_at            = NOW(6)
WHERE qim_user_id = ?
```

### Fix 3 — 입력 검증 미적용 (컨트롤러 @Valid/@NotBlank 누락)

| 항목 | 내용 |
|------|------|
| **문제** | `GuardianConsentRequest`, `BizConvertApiRequest`의 필수 필드에 Bean Validation 미적용 → 빈 문자열/null로 서비스 호출 가능 |
| **영향** | NPE 또는 잘못된 DB 조작 발생 가능 |
| **해결** | `@NotBlank` + `@Valid @RequestBody` + `MethodArgumentNotValidException` 핸들러 등록 |
| **수정 파일** | `GuardianConsentController.java`, `BizMemberConversionController.java`, `GlobalExceptionHandler.java` |

```java
// GlobalExceptionHandler 추가
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .reduce((a, b) -> a + "; " + b)
            .orElse("입력값 검증에 실패했습니다.");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse.builder().code("E-IM-400").message(message).build());
}
```

### Fix 4 — 기업회원 중복 전환 미방지 + HTTP 201 누락

| 항목 | 내용 |
|------|------|
| **문제** | 동일 `qimUserId`로 기업회원 재전환 가능; POST 리소스 생성 응답이 200 OK (REST 표준 위반) |
| **영향** | 중복 데이터 삽입 가능성; HTTP 클라이언트 캐싱 오동작 |
| **해결** | `existsById(qimUserId)` 선행 체크 + `existsByBizRegNo()` 중복 체크 + `HttpStatus.CREATED` 반환 |
| **수정 파일** | `BizMemberConversionServiceImpl.java`, `BizMemberConversionController.java` |

### Fix 5 — correlationId 버그 (qimUserId 혼용)

| 항목 | 내용 |
|------|------|
| **문제** | `GuardianConsentServiceImpl.getStatus()`에서 `PlatformException(errorCode, qimUserId)` — `qimUserId`가 `correlationId` 자리에 전달됨 → 오류 추적 불가 |
| **영향** | 운영 로그에서 correlationId 대신 qimUserId가 기록되어 분산 추적 단절 |
| **해결** | 서비스 인터페이스/구현 시그니처 변경: `getStatus(String qimUserId)` → `getStatus(String qimUserId, String correlationId)` |
| **수정 파일** | `GuardianConsentService.java`, `GuardianConsentServiceImpl.java`, `GuardianConsentController.java`, `GuardianConsentServiceImplTest.java` |

```java
// 수정 전 (버그)
.orElseThrow(() -> new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, qimUserId));
// 수정 후
.orElseThrow(() -> new PlatformException(PlatformErrorCode.IM_USER_NOT_FOUND, correlationId));
```

### Fix 6 — @Modifying JPA 1차 캐시 오염

| 항목 | 내용 |
|------|------|
| **문제** | `UserProfileJpaRepository.updateGuardianConsent()` JPQL UPDATE 후 1차 캐시 미무효화 → 이후 조회 시 갱신 전 데이터 반환 |
| **영향** | 보호자 동의 처리 직후 상태 확인 시 이전 상태(null) 반환 가능 |
| **해결** | `@Modifying(clearAutomatically = true, flushAutomatically = true)` 적용 |
| **수정 파일** | `UserProfileJpaRepository.java` |

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
    UPDATE UserProfileJpaEntity p
    SET p.guardianQimUserId = :guardianQimUserId,
        p.guardianConsentAt = :consentAt
    WHERE p.qimUserId = :qimUserId AND p.isMinor = true
    """)
int updateGuardianConsent(...);
```

### Fix 7 — isMinor 저장 검증 테스트 부재

| 항목 | 내용 |
|------|------|
| **문제** | `UserRegistrationServiceImpl`의 `isMinor` 판별 로직(14세 기준)에 대한 단위 테스트 없음 → 회귀 위험 |
| **영향** | 미성년자 보호자 인증 분기 로직 변경 시 버그 미검출 |
| **해결** | 연도 동적 계산 방식으로 3종 테스트 추가 |
| **수정 파일** | `UserRegistrationServiceImplTest.java` |

```java
// 동적 연도 계산 패턴
short minorBirthYear = (short)(Year.now().getValue() - 13);  // 만 13세 → isMinor=true
short adultBirthYear = (short)(Year.now().getValue() - 20);  // 만 20세 → isMinor=false
```

| 테스트명 | 기댓값 |
|----------|--------|
| `registerOrGet_minorBirthYear_isMinorTrue()` | isMinor = true |
| `registerOrGet_adultBirthYear_isMinorFalse()` | isMinor = false |
| `registerOrGet_nullBirthYear_isMinorFalse()` | isMinor = false (null 방어) |

### Fix 8 — V6 통합 테스트 부재 (QimLifecycleIntegrationTest)

| 항목 | 내용 |
|------|------|
| **문제** | P3-05(보호자 인증)/P3-06(기업회원 전환) V6 기능에 대한 E2E 통합 테스트 없음 |
| **영향** | DB 스키마·서비스·레포지토리 연동 오류가 런타임 전까지 미발견 |
| **해결** | Testcontainers 기반 `QimLifecycleIntegrationTest`에 S8(4종) + S9(6종) 시나리오 추가 |
| **수정 파일** | `QimLifecycleIntegrationTest.java` |

**추가된 시나리오**:

| ID | 분류 | 시나리오 | 검증 |
|----|------|----------|------|
| S8-1 | 보호자 동의 | 미성년자+보호자 정상 동의 | `guardian_qim_user_id`, `guardian_consent_at` DB 저장 확인 |
| S8-2 | 보호자 동의 | 성인 대상 동의 시도 | `IM_GUARDIAN_MINOR_REQUIRED` 예외 |
| S8-3 | 보호자 동의 | 이미 동의된 미성년자 재동의 | `IM_GUARDIAN_ALREADY_CONSENTED` 예외 |
| S8-4 | 보호자 동의 | 존재하지 않는 보호자 | `IM_USER_NOT_FOUND` 예외 |
| S9-1 | 기업회원 전환 | 정상 전환 + 201 Created | `biz_member` 테이블 저장 확인 |
| S9-2 | 기업회원 전환 | 동일 qimUserId 재전환 | `IM_BIZ_REG_DUPLICATE` 예외 |
| S9-3 | 기업회원 전환 | 사업자번호 중복 (다른 qimUserId) | `IM_BIZ_REG_DUPLICATE` 예외 |
| S9-4 | 기업회원 전환 | 미성년자 전환 시도 | `IM_BIZ_REG_MINOR_NOT_ALLOWED` 예외 |
| S9-5 | 기업회원 전환 | 사업자번호 정규화 (`-` 제거) | 정규화 후 저장 확인 |
| S9-6 | GDPR | 탈퇴 후 기업회원 데이터 파기 | guardian_qim_user_id, guardian_consent_at NULL 확인 |

**수정 내역**:
- `@Import`: `GuardianConsentServiceImpl`, `BizMemberConversionServiceImpl`, `PiiMaskingService` 추가
- `@Autowired`: `UserProfileJpaRepository`, `BizMemberJpaRepository`, `GuardianConsentService`, `BizMemberConversionService` 추가
- `cleanUp()`: `bizMemberRepository.deleteAll()` + `profileRepository.deleteAll()` 추가
- 헬퍼 `createUserWithProfile(qimUserId, birthYear, isMinor)` 신규 추가

---

## 2. P0 — 즉시 처리 필요 (운영 차단)

| ID | 항목 | 담당 모듈 | 설명 |
|----|------|---------|------|
| P0-01 | Kakao OAuth 실제 Client Secret 설정 | Q-Sign | Keycloak Admin에서 `q-sign-client` Secret 발급 후 `.env` 설정 |
| P0-02 | 운영 DB 비밀번호 변경 | 전체 | 기본값 `onepass` → 운영용 강력한 비밀번호 |
| P0-03 | 운영 AES/HMAC 키 교체 | IdO | 기본값 `change-me-*` → 운영용 32바이트 이상 랜덤 키 |

---

## 3. P1 — 다음 스프린트 우선 처리

### 3.1 보안

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| P1-03 | X-Internal-Sig 수신 측 검증 | IdO | `OidcCompleteController`에서 HMAC-SHA256 재계산 + `±60초` 타임스탬프 검증 |
| GAP-QS-04 | Q-Sign X-Internal-Sig 수신 검증 | Q-Sign | `AuthController` 헤더 검증 구현 |
| GAP-IDO-09 | Kafka DLQ `DeadLetterPublishingRecoverer` | IdO | `KafkaConsumerConfig.defaultErrorHandler()`에 DLQ 연결 (6개 필드 보존) |

### 3.2 Q-IM 기능 완성

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| GAP-QIM-01 | `needsSync=true` → Selective Pull 실제 호출 | IdO/Q-IM | `QimEventConsumer`에서 `QimClient.getUserById()` 실제 호출 |
| GAP-QIM-03 | `addAuthMeanMapping()` JPA 저장 구현 | Q-IM | TODO 주석 제거 후 실제 저장 로직 완성 |
| GAP-QIM-04 | Outbox `markFailed()` + `retry_count` 증가 | Q-IM | `OutboxServiceImpl` 실패 처리 완성 |

### 3.3 API 계약

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| GAP-API-02 | `Idempotency-Key` 헤더 | IdO | `HandoffController`에 중복 Ticket 발급 방지 |
| GAP-API-04 | `Retry-After` 헤더 | 전체 | `GlobalExceptionHandler`에 `E-OPS-901` + `Retry-After` 추가 |

### 3.4 기관 연동

| ID | 항목 | 담당 모듈 | 작업 내용 | 상태 |
|----|------|---------|---------|------|
| ~~P1-06~~ | ~~agency-stub 이벤트 폴링 API 완성~~ | ~~IdO~~ | ~~`GET /api/v1/agency/events` 완전 구현~~ | ✅ **완료** (v1.9.3) |

**P1-06 구현 파일** (v1.9.3):
- `idem-hub/.../api/dto/AgencyEventResponse.java` — 이벤트 단건 응답 DTO
- `idem-hub/.../api/dto/AgencyEventListResponse.java` — 목록 응답 래퍼 (hasMore 커서 포함)
- `idem-hub/.../webhook/AgencyEventQueryService.java` — 폴링 조회 서비스 인터페이스
- `idem-hub/.../webhook/AgencyEventQueryServiceImpl.java` — `webhook_dispatch_outbox` JdbcTemplate 조회 + `markAsRead()`
- `idem-hub/.../api/AgencyEventController.java` — `GET /api/v1/agency/events` + `POST /{dispatchId}/read`
- `idem-hub/.../fe/config/IdoWebMvcConfig.java` — `/api/v1/agency/**` 인터셉터·CORS 등록

---

## 4. P2 — 중기 구현 대상

### 4.1 Q-IM 회원 생명주기

| ID | 항목 | 설명 | 상태 |
|----|------|------|------|
| ~~-~~ | ~~CI값 기반 68개 유관시스템 회원 조회~~ | ~~`AgencyMemberLookupService` (PPTX 2.1 프로세스)~~ | ✅ **완료** (P3-01, v2.1.0) |
| ~~-~~ | ~~통합계정 UUID 생성 및 연결 대상 선택~~ | ~~ConversionSession 상태 기계~~ | ✅ **완료** (v2.0.0) |
| ~~-~~ | ~~기업회원 전환 (사업자등록번호 기반)~~ | ~~Q-IM 기업회원 지원~~ | ✅ **완료** (P3-06, v2.1.0) |
| ~~-~~ | ~~14세 미만 보호자 인증 분기~~ | ~~미성년자 보호자 인증 흐름~~ | ✅ **완료** (P3-05, v2.1.0) |
| ~~-~~ | ~~개인정보 동의 기록 (제3자 정보제공 동의)~~ | ~~`consent_record`, `consent_version`~~ | ✅ **완료** (v2.0.0) |
| ~~-~~ | ~~회원 탈퇴 4종 전체 구현~~ | ~~IMMEDIATE/SCHEDULED/AGENCY_REQUESTED/ADMIN_FORCED~~ | ✅ **완료** (v2.0.0) |
| ~~-~~ | ~~논리적 삭제 + 보존기간 만료 영구파기~~ | ~~GDPR Right to be Forgotten~~ | ✅ **완료** (v2.0.0, SCHEDULED 스케줄러) |
| ~~-~~ | ~~GDPR V6 컬럼 NULL 처리~~ | ~~guardian_qim_user_id, guardian_consent_at 파기~~ | ✅ **완료** (Fix 2, v3.1.0) |

**v2.0.0 구현 파일**:
- `idem-registry/.../withdrawal/WithdrawalType.java` — 탈퇴 유형 enum (4종)
- `idem-registry/.../withdrawal/WithdrawalService.java` / `WithdrawalServiceImpl.java` — 탈퇴 4종 + 예약 취소 + 만료 스케줄러 (Fix 2: V6 컬럼 추가)
- `idem-registry/.../withdrawal/WithdrawalRequest.java` / `WithdrawalResponse.java` — 탈퇴 요청/응답 DTO
- `idem-registry/.../api/WithdrawalController.java` — `POST /withdrawal`, `DELETE /withdrawal/schedule`
- `idem-registry/.../entity/ConsentVersionJpaEntity.java` — 동의 버전 테이블 매핑
- `idem-registry/.../entity/ConsentRecordJpaEntity.java` — 동의 이력 테이블 매핑 (이력 보존 INSERT 전용)
- `idem-registry/.../consent/ConsentService.java` / `ConsentServiceImpl.java` — 동의 기록/철회/조회
- `idem-registry/.../api/ConsentController.java` — 동의 4종 API
- `idem-registry/.../entity/ConversionSessionJpaEntity.java` — 전환 세션 테이블 매핑
- `idem-registry/.../conversion/ConversionSessionState.java` — 상태 전이 enum (canTransitionTo)
- `idem-registry/.../conversion/ConversionSessionService.java` / `ConversionSessionServiceImpl.java` — 상태 기계 5단계
- `idem-registry/.../api/ConversionController.java` — 전환 세션 6종 API
- `idem-registry/resources/db/migration/V5__withdrawal_consent_conversion.sql` — DB 스키마 마이그레이션
- `idem-registry/resources/db/migration/V6__guardian_biz_member.sql` — 보호자/기업회원 스키마 (★v3.1.0)
- `idem-common/.../UserStatus.java` — WITHDRAWAL_SCHEDULED 상태 추가
- `idem-common/.../PlatformErrorCode.java` — E-IM-205~217 에러코드 추가

### 4.2 Handoff 전략 완성 ✅ v1.9.2 완료

| ID | 항목 | 설명 | 상태 |
|----|------|------|------|
| ~~-~~ | ~~INTERNAL_SSO HandoffStrategy~~ | ~~`sso_domain` 기반 쿠키 세션 발급~~ | ✅ **완료** (v1.9.2) |
| ~~-~~ | ~~APACHE_GATE HandoffStrategy~~ | ~~Apache mod_auth 호환 헤더 주입~~ | ✅ **완료** (v1.9.2) |

**구현 파일**:
- `idem-hub/.../handoff/strategy/InternalSsoHandoffStrategy.java` — `POST {ssoDomain}/internal/sso-session`
- `idem-hub/.../handoff/strategy/ApacheGateHandoffStrategy.java` — Apache `X-Remote-User`, `X-Auth-Level`, `X-Handoff-Token` 헤더 Push

### 4.3 인프라

| ID | 항목 | 설명 |
|----|------|------|
| P2-07 | agency-stub Kafka 직접 구독 제거 | PoC 코드 정리 → Webhook/폴링 방식으로 교체 |
| P2-06 | agency-stub Docker 격리 | 별도 네트워크 또는 host 모드 |
| ~~GAP-QS-03~~ | ~~`qsign.processed_event` migration + IdempotentEventStore~~ | ~~Q-Sign 멱등 컨슈머~~ | ✅ **완료** (v1.9.2) |
| ~~GAP-QIM-05~~ | ~~`snapshot_meta` 사용 로직 구현~~ | ~~Q-IM Snapshot 발행 기능~~ | ✅ **완료** (v1.9.2) |

**GAP-QS-03 구현 파일**:
- `idem-gate/.../kafka/IdempotentEventStore.java` — `qsign.processed_event` + `qsign.last_event_version` ON CONFLICT 패턴
- `idem-gate/.../kafka/QimUserEventConsumer.java` — `@KafkaListener` + 6단계 멱등 처리 + USER_SUSPENDED/WITHDRAWN → auth_lock 잠금

**GAP-QIM-05 구현 파일**:
- `idem-registry/.../entity/SnapshotMetaJpaEntity.java` — `snapshot_meta` 테이블 JPA 매핑
- `idem-registry/.../repository/SnapshotMetaJpaRepository.java` — 최신 스냅샷 조회, 중복 방지
- `idem-registry/.../outbox/SnapshotService.java` / `SnapshotServiceImpl.java` — 10개 이벤트마다 스냅샷 발행
- `idem-registry/.../outbox/OutboxServiceImpl.java` — `relayPendingEvents()` 스냅샷 트리거 분기 추가

### 4.4 프론트엔드

| ID | 항목 | 설명 |
|----|------|------|
| - | 회원 가입/전환 UI | PPTX 프로세스 매핑 |
| - | 개인정보 동의 UI | 제3자 제공 동의 |
| - | 회원정보 관리 UI | ID/PW 찾기, 정보 수정 |

---

## 5. P3 — 장기 구현 대상

| ID | 항목 | 설명 | 상태 |
|----|------|------|------|
| ~~P3-01~~ | ~~AgencyMemberLookupService 실제 연동~~ | ~~CI값 기반 68개 기관 병렬 조회~~ | ✅ **완료** (v2.1.0, Fix 1 보완) |
| ~~P3-05~~ | ~~14세 미만 보호자 인증~~ | ~~Guardian 동의 플로우~~ | ✅ **완료** (v2.1.0, Fix 3/5/6/7/8 보완) |
| ~~P3-06~~ | ~~기업회원 전환~~ | ~~사업자등록번호 기반 전환~~ | ✅ **완료** (v2.1.0, Fix 3/4/8 보완) |
| - | Micrometer 커스텀 메트릭 | Handoff 성공률, CB 상태, Ticket 재사용 | 미완성 |
| - | Admin Console UI | React 기반 기관 관리 대시보드 | 미완성 |
| - | E2E 자동화 테스트 확장 | IdO/Q-Sign Testcontainers 추가 (현재 Q-IM S1~S9 완성) | 진행중 |
| - | mTLS 기관 인증 | Nginx/Gateway 레벨 클라이언트 인증서 검증 | 미완성 |
| - | 네이버 OIDC 실 연동 | NaverOidcService 구현 | 미완성 |
| - | 카카오 OIDC 실 연동 테스트 | 실 Client ID/Secret 필요 | 미완성 |
| - | 부하 테스트 | k6/Gatling, 목표: 200 TPS, p99 < 150ms | 미완성 |
| - | 보안 스캔 | OWASP ZAP | 미완성 |

---

## 6. 기술 부채 (v3.1 이후)

| ID | 항목 | 설명 |
|----|------|------|
| DEBT-01 | HashiCorp Vault / AWS KMS 연동 | 환경변수 키 관리 → 전용 KMS 이전 |
| DEBT-02 | SAML 2.0 SP 구현 | 일부 공공기관 SAML 요구 대응 |
| DEBT-03 | SCIM 2.0 엔드포인트 | 외부 IdM 시스템 사용자 동기화 |
| DEBT-04 | JWT Bearer Token (기관 API) | Handoff 외 일반 API 인증 |
| DEBT-05 | DB 스키마 완전 격리 | 현재 단일 PostgreSQL → 기관별 schema 격리 |
| DEBT-06 | OpenTelemetry 완전 연동 | TraceparentFilter → OTel SDK 전환 |
| DEBT-07 | 다중 기관 CI/CD | 기관별 독립 배포 파이프라인 |
| DEBT-08 | 쿠버네티스 Helm Chart | K8s 기반 운영 배포 |
| DEBT-09 | Q-IM 전체 모듈 통합 테스트 확장 | 현재 S1~S9 완성; S10+ 비즈니스 복합 시나리오 추가 필요 |

---

## 7. Sprint 계획 (현재 → 운영 전환)

```
Sprint 1~11  (완료)  기반 구현
  - SSO, 보안, 테스트, 운영 강화, 유관기관 SSO

Sprint 12  (완료 v3.1.0)  P3 운영 버그 수정
  - Fix 1: Virtual Thread Executor 정리
  - Fix 2: GDPR V6 컬럼 NULL 처리 (2파일)
  - Fix 3: @Valid/@NotBlank 입력 검증 + MethodArgumentNotValidException 핸들러
  - Fix 4: 기업회원 중복 전환 방지 + HTTP 201 Created
  - Fix 5: correlationId 버그 수정 (qimUserId 혼용 제거)
  - Fix 6: @Modifying(clearAutomatically=true, flushAutomatically=true)
  - Fix 7: isMinor 저장 검증 테스트 3종
  - Fix 8: S8(4종) + S9(6종) Testcontainers E2E 시나리오

Sprint 13  (예정)  운영 보안 완성
  - X-Internal-Sig 수신 측 검증 (P1-03)
  - Kafka DLQ 완전 구현 (GAP-IDO-09)
  - Outbox markFailed + retry_count (GAP-QIM-04)

Sprint 14  (예정)  테스트 확장
  - IdO Testcontainers 통합 테스트
  - SSO 경로 단위 테스트 (KeycloakOidcService, QimClientImpl)
  - 부하 테스트 (k6, 200 TPS 목표)
```

---

## 8. Q-IM 팀 협의 필요 사항

v1.9.0 기준 미합의 사항 (Q-IM SP 연동 관련):

| 항목 | 현재 가정 | Q-IM 확인 필요 | 우선순위 |
|------|---------|--------------|---------|
| encCi 알고리즘/패딩 | AES-256-CBC 예상 | 정확한 모드/패딩/IV 전달 방식 | 🔴 P0 |
| AES 공유키 회전 정책 | 수동 교체 가능 | 회전 주기, 유예기간, 무중단 교체 방식 | 🔴 P0 |
| Idempotency-Key 보관 기간 | 7일 예정 | Q-IM 측 재판단 기간과 일치 여부 | 🔴 P0 |
| instMbrId 정책 | qimUserId와 동일 UUID | Q-IM이 다른 형식을 요구하는지 | 🔴 P0 |
| SP 수신 endpoint URL | `/api/qim/sp/v1/member/*` | Q-IM 콘솔 등록 전 URL 확정 | 🔴 P0 |
| 412 재시도 상한 | 3회 + Outbox fallback | Q-IM 측 최대 잠금 유지 시간 | 🟡 P1 |
| CONVERSION/PROVISION API 의무 여부 | 현재 미포함 | 선택 API 구현 필요 여부 | 🟡 P1 |

---

*다음 문서: [13-development-history.md](13-development-history.md)*
