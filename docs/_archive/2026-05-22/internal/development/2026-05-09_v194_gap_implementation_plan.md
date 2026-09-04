# OnePass Integration-SSO — v1.9.4 기준 미반영 항목 종합 개발 플랜

> **작성일**: 2026-05-09  
> **기준 버전**: v1.9.4 (commit: 524b878, PR #30)  
> **문서 목적**: 7개 분석 문서(01~06 + frontend_refactoring_plan)를 v1.9.4 코드베이스와 정밀 대조하여 미반영/수정보완 항목을 도출하고 우선순위별 구현 플랜 수립  
> **분석자**: AI 코드 리뷰 (실제 코드 기반 정밀 분석)

---

## 0. 분석 방법론

7개 분석 문서에서 지적한 항목들을 아래 기준으로 현재 코드와 대조:

| 판정 | 기준 |
|------|------|
| ✅ **완료** | 코드 내에 실제로 구현되어 있음 |
| ⚠️ **부분 구현** | 구조/인터페이스만 있고 핵심 로직 미흡 |
| ❌ **미구현** | 코드 어디에도 존재하지 않음 |
| 🔴 **TODO 방치** | 코드 내 명시적 TODO 주석 존재 |

---

## 1. 코드베이스 현황 대조표 (문서 지적 항목 vs v1.9.4)

### 1.1 보안 영역

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **SP 연동 API Key 해시 검증** | `QimSpReceiverService.isValidApiKey()` — 단순 문자열 비교 | `inboundApiKeyHash.equals(apiKey) \|\| "CHANGEME".equals(inboundApiKeyHash)` — CHANGEME 시 검증 완전 우회 | 🔴 **TODO 방치** |
| **AES Transformation 확정** | `AesSharedKeyDecryptor.TRANSFORMATION` 하드코딩 | `private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";` 에 `TODO: Q-IM 팀 확인 후 확정` 주석 존재 | 🔴 **TODO 방치** |
| **하드코딩 Default Secret** | `ido-internal-secret` 기본값 위험 | `@Value("${ido.qsign.internal-sig-secret:ido-internal-secret}")` 3곳 이상 존재 | ⚠️ **부분 구현** |
| **QIM AES Key 기본값** | `CHANGEME_32BYTES_BASE64_PLACEHOLDER=` | `@Value("${ido.qim.aes-shared-key:CHANGEME_32BYTES_BASE64_PLACEHOLDER=}")` — 32바이트 경고 로그만 출력 | ⚠️ **부분 구현** |
| **CI 암호화 AES-256-GCM** | q-im 내 CI 저장 시 암호화 | `CiCryptoService` 별도 구현되어 있음 (V3 마이그레이션 적용) | ✅ **완료** |
| **HMAC-SHA256 내부 서명 검증** | X-Internal-Sig 전수 검증 | `InternalSigVerifier` — ±60s timestamp 검증 포함, idem-gate/ido 양쪽 구현 완료 | ✅ **완료** |
| **PKCE S256** | q-sign에서 code_challenge 검증 | `PkceService` 구현 완료 | ✅ **완료** |
| **HandoffTicket AES-256-GCM** | Handoff Ticket 암호화 | `HandoffCryptoService` — AES-GCM 구현 완료 | ✅ **완료** |

### 1.2 세션/SLO 영역

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **SLO — ido 로그아웃 엔드포인트** | `POST /api/v1/fe-session/logout` 구현 | `FeSessionController.logout()` — feSessionId 쿠키만 만료. **Keycloak 세션 종료 전파 없음** | ⚠️ **부분 구현** |
| **SLO — Keycloak 세션 종료 전파** | q-sign으로 Keycloak end_session 전파 | `AuthServiceImpl`에 `logout`, `signout` 키워드 있으나 Keycloak end_session_endpoint 호출 코드 없음 | ❌ **미구현** |
| **SLO — 기관(SP) 로그아웃 Webhook** | 연계 기관 전체에 로그아웃 이벤트 전파 | `WebhookDispatcherService.enqueueForMemberWithdrawn()` 존재하나, **로그아웃 전용 Webhook** 없음 | ❌ **미구현** |
| **FE 세션 기본 관리** | feSessionId 쿠키 기반 세션 | `FeSessionService` + Redis TTL 구현 완료 | ✅ **완료** |
| **FE 세션 만료 감지 (401 → 로그인)** | 401 인터셉터 → 로그인 리다이렉트 | `api/client.ts` interceptor 구현 완료 | ✅ **완료** |

### 1.3 개인정보 보호/컴플라이언스

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **개인정보 파기 스케줄러** | 탈퇴 후 N년 경과 시 PII/CI 영구 삭제 | `QimSpMemberEventHandler.onMemberWithdrawn()` 내 `// ④ [Phase 3 TODO]` 주석만 존재 | 🔴 **TODO 방치** |
| **PII 마스킹** | PII 저장/조회 시 마스킹 | `PiiMaskingService` 구현 완료 | ✅ **완료** |
| **DI 생성** | 기관별 중복 식별자(DI) 생성 | `DiGenerationService` 구현 완료 | ✅ **완료** |

### 1.4 테스트 코드

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **단위 테스트 (전체 모듈)** | JUnit5 + Mockito — 보안 로직 우선 | `src/test/java` 디렉토리 존재하나 **모든 모듈 0개 Java 파일** | ❌ **미구현** |
| **통합 테스트 (Testcontainers)** | DB/Kafka 연동 통합 테스트 | 전무 | ❌ **미구현** |
| **FE 테스트 (Jest)** | `useAuth` Hook 등 단위 테스트 | package.json에 Jest 설정 존재하나 실제 테스트 파일 없음 | ❌ **미구현** |

### 1.5 프론트엔드 (`onepass-fe`)

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **API 클라이언트 (withCredentials)** | `axios withCredentials: true` 설정 | `client.ts`에 `withCredentials: true` 구현 완료 | ✅ **완료** |
| **401 인터셉터 (세션 만료 → 로그인)** | 응답 인터셉터 구현 | `client.ts` interceptor 구현 완료 | ✅ **완료** |
| **전역 상태 관리 (Zustand)** | Zustand 또는 Context API 도입 | `src/store/` 폴더 존재하나 **파일 없음** | ❌ **미구현** |
| **폴더 구조 표준화** | features/hooks/api/store 구조 | `src/hooks/`, `src/store/` 폴더 생성되었으나 **모두 비어있음** | ⚠️ **부분 구현** |
| **OIDC 로그인 리다이렉션** | `window.location.href` 이동 | `LoginPage.tsx`에 구현 완료 (handleKakaoLogin 등) | ✅ **완료** |
| **Callback/Loading 페이지** | 인증 후 콜백 처리 컴포넌트 | 없음 — `/conversion/*` 라우트가 대신 처리 | ⚠️ **부분 구현** |
| **SLO 로그아웃 버튼** | 백엔드 SLO 엔드포인트 호출 | `session.ts`에 `logout()` API 존재하나 **UI 컴포넌트 없음** | ⚠️ **부분 구현** |
| **ErrorBoundary** | React 컴포넌트 트리 크래시 방지 | 없음 | ❌ **미구현** |
| **CSP 설정** | Content Security Policy 헤더 | 없음 | ❌ **미구현** |
| **Code Splitting / Lazy Loading** | Webpack 최적화 | `App.tsx`에 `lazy()` 적용 완료 | ✅ **완료** |

### 1.6 운영 고도화

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **Kafka DLQ (DefaultErrorHandler)** | 최대 재시도 후 DLQ 전송 | `KafkaConsumerConfig` — q-sign, ido, agency-stub 모두 구현 완료 | ✅ **완료** |
| **Outbox Max Retry 초과 시 알림** | Slack/이메일 Alert 발송 | `OutboxServiceImpl`에 `MAX_RELAY_RETRY=5` 적용. **알림 발송 코드 없음** | ⚠️ **부분 구현** |
| **Micrometer 메트릭 노출** | Prometheus + Grafana 연동 | `AgencyRateLimiter`에만 `MeterRegistry` 사용. 핵심 비즈니스 메트릭 없음 | ⚠️ **부분 구현** |
| **BrokerAuditLog ELK/Loki 연동** | JSON 콘솔 로그 → 수집기 포워딩 | `BrokerAuditLogService` 구현 완료. ELK 연동 준비 없음 | ⚠️ **부분 구현** |
| **MFA/AAL 스키마 확장** | `AuthResult`에 AAL 필드 예약 | V10 마이그레이션에 auth_method 추가됨. AAL/MFA 전용 필드 없음 | ⚠️ **부분 구현** |

### 1.7 인프라/배포

| 항목 | 문서 지적 | v1.9.4 실제 코드 상태 | 판정 |
|------|-----------|----------------------|------|
| **GitHub Actions CI/CD** | PR 발생 시 자동 빌드 + 테스트 | `.github/` 디렉토리 자체 없음 | ❌ **미구현** |
| **MariaDB q-im 완전 전환** | 드라이버/Flyway 플러그인 전환 | V3 마이그레이션까지 완료. 실제 DB 연결 확인 필요 | ⚠️ **부분 구현** |
| **부하 테스트 (k6/JMeter)** | Rate Limiter 정상 작동 검증 | 없음 | ❌ **미구현** |
| **OWASP Dependency-Check** | 의존성 취약점 스캔 | 없음 | ❌ **미구현** |
| **SonarQube 정적 분석** | 코드 보안/품질 점검 | 없음 | ❌ **미구현** |
| **Docker Compose 개발 환경** | PostgreSQL/MariaDB/Redis/Kafka/Keycloak | `infra/docker-compose.yml` 구성 완료 | ✅ **완료** |

---

## 2. 미반영 항목 우선순위 분류 (P0 → P3)

### 🔴 P0 — 치명적 보안 결함 (운영 투입 절대 불가)

| ID | 항목 | 영향 | 파일 위치 |
|----|------|------|---------|
| **P0-01** | `QimSpReceiverService.isValidApiKey()` — PBKDF2/BCrypt 검증 미적용, CHANGEME 완전 우회 | SP 연동 API 무인증 접근 가능 | `idem-hub/.../qim/sp/service/QimSpReceiverService.java` |
| **P0-02** | `AesSharedKeyDecryptor.TRANSFORMATION` — Q-IM 팀과 미합의 상태 (`AES/CBC/PKCS5Padding` 임시) | 운영 시 CI 복호화 전면 실패 | `idem-hub/.../qim/crypto/AesSharedKeyDecryptor.java` |
| **P0-03** | `ido-internal-secret` 기본값 — 환경변수 미설정 시 노출 | X-Internal-Sig 완전 우회 가능 | `idem-hub/.../broker/InternalSigVerifier.java`, `idem-gate/.../api/InternalSigVerifier.java` |
| **P0-04** | `CHANGEME_32BYTES_BASE64_PLACEHOLDER=` AES Key 기본값 — 암호화 동작 불가 | CI 데이터 암호화/복호화 실패 | `idem-hub/src/main/resources/application.yml` |
| **P0-05** | 테스트 코드 0% — 모든 모듈 `src/test/java` 파일 없음 | 회귀 버그 즉각 감지 불가 | 전 모듈 |

### 🟠 P1 — 핵심 SSO 기능 미구현 (서비스 기본 동작 미완)

| ID | 항목 | 영향 | 파일 위치 |
|----|------|------|---------|
| **P1-01** | **SLO — Keycloak 세션 종료 전파** 미구현 | 사용자 로그아웃 후 Keycloak 세션 잔존 | `idem-gate/.../AuthServiceImpl.java` (신규 구현) |
| **P1-02** | **SLO — 기관(SP) 로그아웃 Webhook** 미구현 | 연계 기관 세션이 계속 유효하게 남음 | `idem-hub/.../webhook/WebhookDispatcherService.java` (확장) |
| **P1-03** | **SLO — ido 오케스트레이션 엔드포인트** 미구현 | 완전한 SLO 흐름 트리거 불가 | `idem-hub/.../fe/api/` (신규 Controller) |
| **P1-04** | **개인정보 파기 스케줄러** Phase 3 TODO | 개인정보보호법 위반 소지 (탈퇴 후 미삭제) | `idem-hub/.../qim/sp/kafka/QimSpMemberEventHandler.java` |
| **P1-05** | **FE 전역 상태 관리 (Zustand)** 미구현 | 로그인 상태 전역 관리 불가, UX 불완전 | `idem-console/frontend/src/store/` (신규) |
| **P1-06** | **FE 로그아웃 UI 컴포넌트** 미구현 | 사용자가 로그아웃 불가 | `idem-console/frontend/src/` (신규 컴포넌트) |

### 🟡 P2 — 운영 고도화 (안정 운영에 필요)

| ID | 항목 | 영향 | 파일 위치 |
|----|------|------|---------|
| **P2-01** | **Micrometer 비즈니스 메트릭** 미구현 | 로그인 성공/실패율, IdP 응답시간 불가시화 | 각 모듈 Service 클래스 |
| **P2-02** | **Outbox Max Retry 초과 알림** 미구현 | Outbox 장애 시 운영자 무감지 | `idem-registry/.../outbox/OutboxServiceImpl.java` |
| **P2-03** | **MFA/AAL 스키마 확장 포인트** 미설계 | 향후 FIDO/OTP 도입 시 스키마 변경 필요 | Flyway V11 (신규) |
| **P2-04** | **FE ErrorBoundary** 미구현 | React 컴포넌트 크래시 시 전체 화면 빈 화면 | `idem-console/frontend/src/` |
| **P2-05** | **FE CSP 헤더** 미구현 | XSS 공격 방어 체계 부재 | Nginx/Spring Boot 설정 |
| **P2-06** | **BrokerAuditLog JSON 구조화 로깅** | ELK/Loki 연동 준비 부재 | `logback-spring.xml` 설정 |
| **P2-07** | **관리자 API (Provider/기관 관리)** 미구현 | 운영 중 설정 변경 시 재배포 필요 | `idem-hub/.../admin/` (신규) |

### 🟢 P3 — 배포 준비 (운영 환경 Go-Live 전)

| ID | 항목 | 영향 | 파일 위치 |
|----|------|------|---------|
| **P3-01** | **GitHub Actions CI/CD** 미구현 | 수동 배포 의존, 품질 게이트 없음 | `.github/workflows/` (신규) |
| **P3-02** | **MariaDB q-im 실제 연결 검증** | 운영 DB 마이그레이션 신뢰성 | `idem-registry/src/main/resources/application.yml` |
| **P3-03** | **부하 테스트 스크립트 (k6)** | Rate Limiter 실효성 검증 불가 | `infra/k6/` (신규) |
| **P3-04** | **OWASP Dependency-Check + SonarQube** | 의존성 취약점 미스캔 | `build.gradle.kts` 플러그인 추가 |

---

## 3. Sprint별 구현 플랜

### Sprint 1 (2주) — P0 보안 결함 완전 제거

#### [P0-01] QimSpReceiverService API Key 해시 검증 강화

**파일**: `idem-hub/src/main/java/kr/go/smes/idem-hub/qim/sp/service/QimSpReceiverService.java`

**현재 코드** (문제):
```java
public boolean isValidApiKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) return false;
    // TODO: 운영 전 PBKDF2 해시 검증으로 강화 (현재: 단순 문자열 비교)
    return inboundApiKeyHash.equals(apiKey) || "CHANGEME".equals(inboundApiKeyHash);
}
```

**목표 코드**:
```java
// 의존성 추가: platform-common에 CryptoHashUtil 구현
public boolean isValidApiKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) return false;
    if ("CHANGEME".equals(inboundApiKeyHash)) {
        log.error("[QIM-SP] 운영 환경에서 CHANGEME API Key가 설정됨! 즉각 변경 필요");
        return false; // 개발 우회 제거
    }
    // PBKDF2-HMAC-SHA256 검증: hash(apiKey, salt) == inboundApiKeyHash
    return ApiKeyHashValidator.verify(apiKey, inboundApiKeyHash);
}
```

**추가 파일**: `idem-common/.../util/ApiKeyHashValidator.java`
- PBKDF2-HMAC-SHA256 (iterations=310000, salt=16bytes, hash=32bytes)
- 상수 시간 비교(`MessageDigest.isEqual`)로 타이밍 공격 방지

**설정 변경**:
- `idem-hub/src/main/resources/application.yml`: `QIM_INBOUND_API_KEY_HASH` — PBKDF2 해시값으로 변경 (`CHANGEME` 기본값 제거)
- 운영 키 생성 스크립트: `scripts/generate-api-key-hash.sh`

**예상 공수**: 0.5일

---

#### [P0-02] AesSharedKeyDecryptor Transformation 확정

**파일**: `idem-hub/src/main/java/kr/go/smes/idem-hub/qim/crypto/AesSharedKeyDecryptor.java`

**현재 코드** (문제):
```java
// TODO: Q-IM 팀 확인 후 정확한 transformation 문자열 확정 [합의 필요 #1]
private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
```

**해결 방법**:
1. Q-IM 팀과 정확한 암호화 스펙 합의 (`application.yml` 환경변수화)
2. 스펙 확정 전까지: `AES/CBC/PKCS5Padding` 유지 (현재값) + 경고 로그 강화
3. 스펙 확정 후: TODO 제거 + 환경변수로 주입 가능하도록 구조화

```yaml
# idem-hub/src/main/resources/application.yml
ido:
  qim:
    aes-transformation: ${QIM_AES_TRANSFORMATION:AES/CBC/PKCS5Padding}
    aes-iv-mode: ${QIM_AES_IV_MODE:PREPEND}  # PREPEND | SEPARATE
```

**예상 공수**: 0.5일 + Q-IM 팀 협의 시간

---

#### [P0-03] 하드코딩 기본 Secret 제거

**영향 파일**:
- `idem-gate/src/main/resources/application.yml`: `internal-sig-secret: ${IDO_INTERNAL_SIG_SECRET:ido-internal-secret}`
- `idem-hub/src/main/resources/application.yml`: `internal-sig-secret: ${IDO_INTERNAL_SIG_SECRET:ido-internal-secret}`

**해결 방법**:
```yaml
# 기본값 제거 → 미설정 시 기동 실패
ido:
  qsign:
    internal-sig-secret: ${IDO_INTERNAL_SIG_SECRET}  # 기본값 없음
```

`@PostConstruct`에서 비어있으면 `IllegalStateException` throw:
```java
@PostConstruct
void validateSecrets() {
    if (internalSigSecret == null || internalSigSecret.isBlank()
            || "ido-internal-secret".equals(internalSigSecret)) {
        throw new IllegalStateException(
            "IDO_INTERNAL_SIG_SECRET 환경변수가 설정되지 않았거나 기본값 사용 중. 운영 배포 불가.");
    }
}
```

**예상 공수**: 0.5일

---

#### [P0-05] 테스트 코드 기반 구축 (최소 핵심 로직 커버)

**목표**: 핵심 보안 로직 5개 테스트 클래스 작성 (커버리지 목표: 핵심 로직 70%)

**추가 Gradle 의존성** (`build.gradle.kts`):
```kotlin
testImplementation("org.testcontainers:postgresql:1.19.3")
testImplementation("org.testcontainers:kafka:1.19.3")
testImplementation("org.testcontainers:mariadb:1.19.3")
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.mockito:mockito-core")
testImplementation("io.rest-assured:rest-assured:5.4.0")
```

**우선 작성 테스트 목록**:

| 테스트 파일 | 모듈 | 검증 항목 |
|------------|------|---------|
| `CiCryptoServiceTest.java` | q-im | AES-256-GCM 암복호화 라운드트립, 잘못된 키 예외 |
| `PkceServiceTest.java` | q-sign | code_challenge/verifier 검증, S256 해시 일치 |
| `HandoffCryptoServiceTest.java` | ido | Ticket 암호화/복호화, HMAC 서명 검증 |
| `AesSharedKeyDecryptorTest.java` | ido | AES-CBC 복호화, IV 길이 검증, 예외 처리 |
| `ApiKeyHashValidatorTest.java` | platform-common | PBKDF2 해시 생성/검증, 타이밍 일관성 |
| `OutboxIntegrationTest.java` | q-im | Testcontainers Kafka + MariaDB, Outbox 발행/수신 |

**예상 공수**: 3일

---

### Sprint 2 (2주) — SLO 완전 구현

#### [P1-01, P1-02, P1-03] Single Logout (SLO) 전체 흐름 구현

**SLO 완전 흐름도**:
```
사용자 → [FE 로그아웃 버튼] → POST /api/v1/slo/initiate
    → IdO SloController
        ① feSession 만료 (Redis 삭제)
        ② Q-Sign으로 Keycloak 세션 종료 요청
            → POST /api/v1/internal/session/logout
            → KeycloakLogoutService.revokeSession(sub, accessToken)
               → Keycloak admin API: DELETE /admin/realms/{realm}/sessions/{sessionId}
        ③ WebhookDispatcherService.enqueueForLogout(qimUserId, instMbrId, ...)
            → webhook_dispatch_outbox INSERT (event_type=USER_LOGOUT)
                → WebhookDispatchOutboxRelay → 기관별 HTTPS POST
        ④ 감사 로그 기록
    → 204 No Content
FE: 전역 상태 초기화 → /login 리다이렉트
```

**신규 파일 목록**:

| 파일 | 역할 |
|------|------|
| `idem-hub/src/main/java/kr/go/smes/idem-hub/slo/SloController.java` | `POST /api/v1/slo/initiate` 엔드포인트 |
| `idem-hub/src/main/java/kr/go/smes/idem-hub/slo/SloService.java` | SLO 오케스트레이션 서비스 인터페이스 |
| `idem-hub/src/main/java/kr/go/smes/idem-hub/slo/SloServiceImpl.java` | 세션 만료 + Q-Sign 전파 + Webhook 발송 |
| `idem-gate/src/main/java/kr/go/smes/qsign/api/InternalSessionController.java` | `POST /api/v1/internal/session/logout` |
| `idem-gate/src/main/java/kr/go/smes/qsign/keycloak/KeycloakLogoutService.java` | Keycloak Admin API end_session |

**`SloController.java` 핵심 구현**:
```java
@PostMapping("/api/v1/slo/initiate")
public ResponseEntity<Void> initiateSlo(
        @CookieValue(name = "feSessionId", required = false) String feSessionId,
        @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
        HttpServletResponse response) {

    String cid = resolveCorrelationId(correlationId);
    
    if (feSessionId != null) {
        FeSession session = feSessionService.findById(feSessionId).orElse(null);
        if (session != null) {
            sloService.executeSlo(session, cid);
        }
        feSessionService.expire(feSessionId);
    }
    
    // feSessionId 쿠키 제거
    clearSessionCookie(response);
    
    return ResponseEntity.noContent().build();
}
```

**`SloServiceImpl.java` 핵심 구현**:
```java
@Override
@Transactional
public void executeSlo(FeSession session, String correlationId) {
    String qimUserId = session.getQimUserId();
    
    // ① Q-Sign → Keycloak 세션 종료
    try {
        qSignClient.revokeKeycloakSession(qimUserId, correlationId);
        log.info("[SLO] Keycloak 세션 종료 요청 완료: qimUserId={}", qimUserId);
    } catch (Exception e) {
        log.warn("[SLO] Keycloak 세션 종료 실패 (비치명적): qimUserId={} cause={}", 
                 qimUserId, e.getMessage());
    }
    
    // ② 연계 기관 로그아웃 Webhook 발송 (Outbox)
    try {
        String instMbrId = resolveInstMbrId(qimUserId);
        webhookDispatcherService.enqueueForUserLogout(instMbrId, qimUserId, correlationId);
        log.info("[SLO] 기관 로그아웃 Webhook 발송 Outbox 적재: qimUserId={}", qimUserId);
    } catch (Exception e) {
        log.error("[SLO] 기관 로그아웃 Webhook 발송 실패 (비치명적): qimUserId={} cause={}",
                  qimUserId, e.getMessage());
    }
    
    // ③ 감사 로그
    auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
            .eventCategory(AuditLogEvent.CATEGORY_SESSION)
            .eventAction("SLO_INITIATED")
            .actorType(AuditLogEvent.ACTOR_USER)
            .actorId(qimUserId)
            .outcome(AuditLogEvent.OUTCOME_SUCCESS)
            .correlationId(correlationId)
            .build());
}
```

**DB 마이그레이션** — `idem-hub/src/main/resources/db/migration/V11__add_slo_support.sql`:
```sql
-- webhook_event_type에 USER_LOGOUT 추가
ALTER TABLE ido.webhook_dispatch_outbox 
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(50) DEFAULT 'MEMBER_LOOKUP';

-- SLO 감사 로그 인덱스
CREATE INDEX IF NOT EXISTS idx_broker_audit_log_slo 
    ON ido.broker_audit_log(actor_id, event_action) 
    WHERE event_action = 'SLO_INITIATED';
```

**예상 공수**: 3일

---

#### [P1-04] 개인정보 파기 스케줄러 구현

**파일**: `idem-hub/src/main/java/kr/go/smes/idem-hub/qim/sp/schedule/PersonalDataRetentionScheduler.java` (신규)

**구현 방법** (Spring `@Scheduled` 기반):
```java
@Component
@RequiredArgsConstructor
@Slf4j
public class PersonalDataRetentionScheduler {

    private final InstMbrIdMappingRepository mappingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuditLogPublisher auditLogPublisher;

    // 매일 새벽 2시 실행
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    @Transactional
    public void executeRetentionPolicy() {
        Instant retentionCutoff = Instant.now().minus(365, ChronoUnit.DAYS);
        
        log.info("[RetentionScheduler] 개인정보 파기 스케줄 실행: cutoff={}", retentionCutoff);
        
        // 탈퇴 후 1년 경과 회원 조회
        List<String> expiredInstMbrIds = findExpiredWithdrawnMembers(retentionCutoff);
        
        int count = 0;
        for (String instMbrId : expiredInstMbrIds) {
            try {
                purgePersonalData(instMbrId, retentionCutoff);
                count++;
            } catch (Exception e) {
                log.error("[RetentionScheduler] 파기 실패: instMbrId={} cause={}", 
                          instMbrId, e.getMessage());
            }
        }
        
        log.info("[RetentionScheduler] 개인정보 파기 완료: 처리={}", count);
    }
    
    private void purgePersonalData(String instMbrId, Instant cutoff) {
        // identifierHash 및 개인식별 가능 필드 null 처리
        jdbcTemplate.update(
            "UPDATE ido.inst_mbr_id_mapping " +
            "SET identifier_hash = NULL, mbr_uuid = NULL, " +
            "    purged_at = NOW(), status = 'PURGED' " +
            "WHERE inst_mbr_id = ? AND withdrawn_at < ?",
            instMbrId, cutoff
        );
        
        // 감사 로그
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(AuditLogEvent.CATEGORY_MEMBER)
                .eventAction("PERSONAL_DATA_PURGED")
                .resourceId(instMbrId)
                .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                .correlationId(CorrelationIdHolder.generate())
                .build());
    }
}
```

**DB 마이그레이션** — V11에 추가:
```sql
ALTER TABLE ido.inst_mbr_id_mapping
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retention_expires_at TIMESTAMPTZ 
        GENERATED ALWAYS AS (withdrawn_at + INTERVAL '1 year') STORED;
```

**예상 공수**: 1.5일

---

#### [P1-05, P1-06] FE 전역 상태 관리 + 로그아웃 UI

**파일 신규 목록**:

**`idem-console/frontend/src/store/authStore.ts`**:
```typescript
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface AuthState {
  isAuthenticated: boolean;
  qimUserId: string | null;
  authLevel: string | null;
  setAuthenticated: (qimUserId: string, authLevel: string) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      isAuthenticated: false,
      qimUserId: null,
      authLevel: null,
      setAuthenticated: (qimUserId, authLevel) =>
        set({ isAuthenticated: true, qimUserId, authLevel }),
      clearAuth: () =>
        set({ isAuthenticated: false, qimUserId: null, authLevel: null }),
    }),
    { name: 'auth-storage', partialize: (s) => ({ qimUserId: s.qimUserId }) }
  )
);
```

**`idem-console/frontend/src/hooks/useAuth.ts`**:
```typescript
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { checkSession, logout as apiLogout } from '@/api/session';

export const useAuth = () => {
  const { isAuthenticated, qimUserId, setAuthenticated, clearAuth } = useAuthStore();
  const navigate = useNavigate();

  const hydrateSession = async () => {
    try {
      const session = await checkSession();
      if (session.valid && session.qimUserId) {
        setAuthenticated(session.qimUserId, session.authLevel ?? 'L1');
      } else {
        clearAuth();
      }
    } catch {
      clearAuth();
    }
  };

  const logout = async () => {
    try {
      await apiLogout();           // POST /api/v1/fe-session/logout (기존)
      // TODO: SLO 완성 시 → POST /api/v1/slo/initiate 로 교체
    } finally {
      clearAuth();
      navigate('/login');
    }
  };

  return { isAuthenticated, qimUserId, hydrateSession, logout };
};
```

**`idem-console/frontend/src/components/LogoutButton.tsx`**:
```typescript
import React from 'react';
import { Button } from 'antd';
import { LogoutOutlined } from '@ant-design/icons';
import { useAuth } from '@/hooks/useAuth';

export const LogoutButton: React.FC = () => {
  const { logout } = useAuth();
  return (
    <Button icon={<LogoutOutlined />} onClick={logout} danger>
      로그아웃
    </Button>
  );
};
```

**`idem-console/frontend/src/components/ErrorBoundary.tsx`**:
```typescript
import React, { Component, ErrorInfo, ReactNode } from 'react';
import { Result, Button } from 'antd';

interface Props { children: ReactNode; }
interface State { hasError: boolean; error?: Error; }

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <Result status="500" title="오류 발생"
          subTitle="예상치 못한 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
          extra={<Button onClick={() => window.location.reload()}>새로고침</Button>} />
      );
    }
    return this.props.children;
  }
}
```

**예상 공수**: 1.5일

---

### Sprint 3 (2주) — 운영 고도화

#### [P2-01] Micrometer 비즈니스 메트릭 구현

**의존성** (`build.gradle.kts`): 이미 `spring-boot-starter-actuator` 포함 확인 필요

**핵심 메트릭 추가 위치**:

| 메트릭명 | 유형 | 위치 |
|---------|------|------|
| `sso.login.success` (tags: provider, method) | Counter | `OidcCompleteController.complete()` |
| `sso.login.failure` (tags: provider, error_code) | Counter | `GlobalExceptionHandler` |
| `sso.handoff.issued` (tags: agency_code) | Counter | `HandoffController.issueHandoff()` |
| `sso.slo.initiated` | Counter | `SloController.initiateSlo()` |
| `sso.session.active` | Gauge | `FeSessionService` — Redis key count |
| `sso.webhook.dispatch.latency` | Timer | `WebhookDispatcherService` |
| `sso.circuit_breaker.state` (tags: provider) | Gauge | `ProviderCircuitBreakerConfig` |

**Prometheus 설정** (`idem-hub/src/main/resources/application.yml`):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
      env: ${APP_ENV:dev}
```

**예상 공수**: 1.5일

---

#### [P2-02] Outbox Max Retry 초과 알림

**파일 수정**: `idem-registry/src/main/java/kr/go/smes/qim/outbox/OutboxServiceImpl.java`

```java
private static final int ALERT_THRESHOLD = 5;

private void checkAndAlert(OutboxRecord record) {
    if (record.getRetryCount() >= ALERT_THRESHOLD) {
        log.error("[OUTBOX-ALERT] 최대 재시도 초과 — 관리자 확인 필요: " +
                  "eventId={} eventType={} retryCount={} lastError={}",
                  record.getEventId(), record.getEventType(),
                  record.getRetryCount(), record.getErrorMessage());
        // TODO: AlertService.sendSlack() 또는 AlertService.sendEmail() 연동
        // 현재: error 레벨 로그 → 로그 수집기(Loki/ELK)에서 Alert Rule 설정 권장
        meterRegistry.counter("outbox.alert.retry_exceeded",
                "event_type", record.getEventType()).increment();
    }
}
```

**예상 공수**: 0.5일

---

#### [P2-03] MFA/AAL 스키마 확장 포인트

**DB 마이그레이션** — `idem-hub/src/main/resources/db/migration/V12__add_mfa_aal_schema.sql`:
```sql
-- =========================================================
-- IdO V12: MFA / AAL 확장 포인트 스키마
-- FIDO, OTP 등 다중 인증 도입 시 호환성 보장
-- =========================================================

-- auth_result: AAL 필드 추가
ALTER TABLE ido.auth_result
    ADD COLUMN IF NOT EXISTS aal SMALLINT DEFAULT 1,
    ADD COLUMN IF NOT EXISTS mfa_methods TEXT[],      -- ['FIDO2', 'TOTP']
    ADD COLUMN IF NOT EXISTS mfa_completed_at TIMESTAMPTZ;

COMMENT ON COLUMN ido.auth_result.aal IS 'Authenticator Assurance Level (RFC 9068): 1=단일, 2=다중, 3=하드웨어';
COMMENT ON COLUMN ido.auth_result.mfa_methods IS '완료된 MFA 수단 목록';

-- oidc_state: MFA 진행 상태 추적
ALTER TABLE ido.oidc_broker_state
    ADD COLUMN IF NOT EXISTS required_aal SMALLINT DEFAULT 1,
    ADD COLUMN IF NOT EXISTS mfa_step_completed BOOLEAN DEFAULT FALSE;

COMMENT ON COLUMN ido.oidc_broker_state.required_aal IS '해당 인증 세션에 요구되는 최소 AAL';
```

**예상 공수**: 0.5일

---

#### [P2-04, P2-05] FE ErrorBoundary + CSP

**`App.tsx` 수정** (ErrorBoundary 래핑):
```typescript
const App: React.FC = () => (
  <ErrorBoundary>
    <Suspense fallback={<PageLoader />}>
      <Routes>
        {/* 기존 라우트 유지 */}
      </Routes>
    </Suspense>
  </ErrorBoundary>
);
```

**CSP 설정** (`idem-hub/src/main/java/kr/go/smes/idem-hub/config/SecurityHeadersConfig.java`):
```java
@Bean
public FilterRegistrationBean<OncePerRequestFilter> securityHeadersFilter() {
    FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
    bean.setFilter(new OncePerRequestFilter() {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            res.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'nonce-{nonce}'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; " +
                "connect-src 'self' https://kauth.kakao.com; " +
                "frame-ancestors 'none'");
            res.setHeader("X-Frame-Options", "DENY");
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            chain.doFilter(req, res);
        }
    });
    bean.addUrlPatterns("/api/*");
    return bean;
}
```

**예상 공수**: 0.5일

---

### Sprint 4 (2주) — 배포 준비

#### [P3-01] GitHub Actions CI/CD 파이프라인

**파일**: `.github/workflows/ci.yml`

```yaml
name: OnePass SSO CI

on:
  push:
    branches: [main, genspark_ai_developer]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: onepass
          POSTGRES_USER: onepass
          POSTGRES_PASSWORD: onepass
        ports: ['5432:5432']
      mariadb:
        image: mariadb:10.11
        env:
          MYSQL_DATABASE: qim
          MYSQL_USER: qim
          MYSQL_PASSWORD: qim
          MYSQL_ROOT_PASSWORD: root
        ports: ['3306:3306']
    
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Build (skip tests)
        run: ./gradlew build -x test --no-daemon
      
      - name: Run unit tests
        run: ./gradlew test --no-daemon
        env:
          IDO_INTERNAL_SIG_SECRET: ${{ secrets.IDO_INTERNAL_SIG_SECRET_TEST }}
          QIM_INBOUND_API_KEY_HASH: ${{ secrets.QIM_INBOUND_API_KEY_HASH_TEST }}
      
      - name: OWASP Dependency Check
        run: ./gradlew dependencyCheckAnalyze --no-daemon
      
      - name: Upload test results
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: test-results
          path: '**/build/test-results/**/*.xml'
```

**파일**: `.github/workflows/quality.yml` (SonarQube):
```yaml
name: SonarQube Analysis
on:
  push:
    branches: [main]
jobs:
  sonar:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - name: SonarQube Scan
        run: ./gradlew sonar --no-daemon
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_HOST_URL: ${{ secrets.SONAR_HOST_URL }}
```

**`build.gradle.kts` 플러그인 추가**:
```kotlin
plugins {
    id("org.sonarqube") version "4.4.1.3373"
    id("org.owasp.dependencycheck") version "9.0.9"
}

sonar {
    properties {
        property("sonar.projectKey", "onepass-integration-sso")
        property("sonar.sources", "src/main/java")
        property("sonar.tests", "src/test/java")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
    }
}
```

**예상 공수**: 1일

---

#### [P3-03] 부하 테스트 스크립트 (k6)

**파일**: `infra/k6/sso_load_test.js`

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    // 60,000명 동시 접속 시나리오
    peak_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 1000 },   // 워밍업
        { duration: '5m', target: 5000 },   // 증가
        { duration: '10m', target: 10000 }, // 최대 부하
        { duration: '2m', target: 0 },      // 감소
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'], // 95%ile < 2s
    http_req_failed: ['rate<0.01'],                   // 오류율 < 1%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';

export default function () {
  // 1. 세션 확인
  const sessionCheck = http.get(`${BASE_URL}/api/v1/fe-session/check`);
  check(sessionCheck, { 'session check 200': (r) => r.status === 200 });
  
  // 2. 브로커 인증 시작
  const brokerStart = http.get(`${BASE_URL}/api/v1/broker/kakao/authorize?returnUrl=/`);
  check(brokerStart, { 'broker redirect 302': (r) => r.status === 302 });
  
  sleep(1);
}
```

**예상 공수**: 0.5일

---

## 4. 전체 일정 요약

| Sprint | 기간 | 주요 완료 항목 | P0/P1/P2/P3 |
|--------|------|--------------|-------------|
| **Sprint 1** | 2주 | P0-01~P0-05 (보안 결함 제거 + 테스트 기반) | P0 완결 |
| **Sprint 2** | 2주 | P1-01~P1-06 (SLO + 개인정보파기 + FE 기반) | P1 완결 |
| **Sprint 3** | 2주 | P2-01~P2-07 (메트릭 + MFA 스키마 + ErrorBoundary) | P2 완결 |
| **Sprint 4** | 2주 | P3-01~P3-04 (CI/CD + 부하테스트 + OWASP) | P3 완결 |

**총 예상 공수**: 8주 (2인 기준 → 4주 단축 가능)

---

## 5. 즉각 실행 가능 체크리스트 (Sprint 1 준비)

다음 항목들은 코드 변경 없이 즉시 처리 가능:

- [ ] `IDO_INTERNAL_SIG_SECRET` 운영 환경변수 값 생성 및 배포 설정에 주입
- [ ] `QIM_INBOUND_API_KEY_HASH` PBKDF2 해시값 생성 스크립트 작성
- [ ] `QIM_AES_SHARED_KEY` 운영 키 생성 및 안전한 보관 (AWS KMS 또는 HashiCorp Vault)
- [ ] Q-IM 팀과 `AES Transformation` 스펙 합의 미팅 일정 수립
- [ ] `Testcontainers` Gradle 의존성 추가 (빌드 설정만 변경)
- [ ] `.github/workflows/` 디렉토리 생성 및 기본 CI 워크플로 추가

---

## 6. 리스크 및 주의사항

| 리스크 | 영향도 | 대응 방안 |
|--------|--------|---------|
| Q-IM 팀 AES Transformation 합의 지연 | HIGH | 임시로 `AES/CBC/PKCS5Padding` 유지 + 운영 투입 차단 |
| SLO 구현 중 Keycloak Admin API 권한 문제 | MEDIUM | Keycloak Service Account 권한(`realm-management:manage-users`) 사전 확인 |
| 개인정보 파기 스케줄러 — 법적 보존 기간 확인 | HIGH | 법무팀 검토 후 `365일` 기준 확정 (현재 1년 임시 설정) |
| FE Zustand 도입 시 기존 `useQuery` 세션 관리와 충돌 | LOW | `checkSession` + Zustand hydration 패턴으로 이중화 방지 |
| MariaDB q-im 전환 완전성 검증 | MEDIUM | 스테이징 환경에서 V1~V3 마이그레이션 전체 실행 + 쿼리 실행계획 확인 |

---

## 7. 참조 문서

| 문서 | 위치 |
|------|------|
| 프로젝트 개요 심층 분석 | `/home/user/uploaded_files/01_project_overview.md` |
| 아키텍처 및 보안 심층 분석 | `/home/user/uploaded_files/02_architecture_and_security.md` |
| 현재 상태 및 GAP 분석 | `/home/user/uploaded_files/03_current_status_and_gaps.md` |
| 코드 기반 심층 분석 | `/home/user/uploaded_files/04_code_only_analysis.md` |
| SSO/IM 심층 분석 및 액션 플랜 | `/home/user/uploaded_files/05_sso_im_deep_analysis_and_action_plan.md` |
| 완성 체크리스트 | `/home/user/uploaded_files/06_sso_im_completion_checklist.md` |
| 프론트엔드 리팩토링 플랜 | `/home/user/uploaded_files/frontend_refactoring_plan.md` |
| 실제 개발 전환 계획서 (구) | `docs/2026-05-08_production_development_plan.md` |
| 미구현 분석 (구) | `docs/2026-05-08_unimplemented_analysis.md` |
