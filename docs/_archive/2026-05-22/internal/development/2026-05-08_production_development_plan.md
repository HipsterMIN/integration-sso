# OnePass Integration-SSO — 실제 개발 전환 계획서

> **작성일**: 2026-05-08  
> **버전**: v2.0.0-dev (PoC → Production 전환)  
> **문서 목적**: PoC 단계 종료 후 실제 운영 가능한 SSO/IM 서비스로의 전환 개발 계획 수립  
> **참조 문서**: `2026-05-08_unimplemented_analysis.md`

---

## 0. 전환 원칙

1. **기존 PoC 코드를 기반**으로 점진적 보강 — 전면 재작성 금지
2. **Critical Gap 우선** — 보안/데이터 보호 미구현 항목을 Sprint 1~2에 집중
3. **기관 연동 확장성 보장** — 모든 신규 기능은 N개 기관 지원 가능하도록 설계
4. **테스트 코드 동시 작성** — 각 기능 PR에 단위 테스트 + 통합 테스트 포함
5. **문서화 병행** — API 스펙(OpenAPI 3.1), ERD, 시퀀스 다이어그램 최신 유지

---

## 1. 전체 로드맵 (Sprint 계획)

| Sprint | 기간 | 주제 | 핵심 산출물 |
|--------|------|------|------------|
| **Sprint 1** | 2주 | Critical 보안 — PII 보호 & 속성 필터 | CI 암복호화, 마스킹, allowedAttributes 필터 |
| **Sprint 2** | 2주 | Critical 보안 — 인증 강화 & 화이트리스트 | PKCE, Callback URL 검증, DI 연계 |
| **Sprint 3** | 2주 | Q-IM 핵심 서비스 | 회원 등록/탈퇴, 조회 API, member_lookup |
| **Sprint 4** | 2주 | IdO 정책 고도화 | integration_type 분기, Admin API, Webhook Dispatcher |
| **Sprint 5** | 2주 | 운영 기반 | 키 로테이션, Rate Limiting, 모니터링 연동 |
| **Sprint 6** | 2주 | 실 IdP 연동 & 검증 | 외부 IdP 연동, E2E 테스트, 부하 테스트 |

---

## 2. Sprint 1 — Critical 보안: PII 보호 & 속성 필터링 (2주)

### 2.1 목표
- 개인정보(CI, 이름, 전화번호)의 암호화·마스킹 실구현
- 기관별 `allowedAttributes` 필터링 활성화

### 2.2 작업 목록

#### [QIM-01] CI 암호화/복호화 서비스 구현
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/crypto/CiCryptoService.java`
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/crypto/CiCryptoServiceImpl.java`
- **구현 내용**:
  - AES-256-GCM 암호화 (키: 환경변수 `QIM_CI_AES_KEY`)
  - 암호화 시 IV 랜덤 생성, Base64URL 저장 포맷: `{iv}.{ciphertext}`
  - 복호화 메서드: `decrypt(String encryptedCi): String`
  - 키 버전 접두사 지원: `v1.{iv}.{ciphertext}` 형태로 미래 로테이션 대비
- **연동**: `QimUserJpaEntity` 저장 시 자동 암호화, 조회 시 자동 복호화
- **테스트**: 암복호화 라운드트립, 잘못된 키 예외, 빈 값 처리

```java
// 인터페이스 설계
public interface CiCryptoService {
    String encrypt(String rawCi);
    String decrypt(String encryptedCi);
    boolean isEncrypted(String value);
}
```

#### [QIM-02] PII 마스킹 서비스 구현
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/crypto/PiiMaskingService.java`
- **구현 내용**:
  - 이름 마스킹: `홍길동` → `홍*동`, 외국인명 `John Doe` → `J*** D**`
  - 전화번호 마스킹: `01012345678` → `010-****-5678`
  - 이메일 마스킹: `user@example.com` → `us**@example.com`
  - 마스킹 규칙: 앞 1자·뒤 1자 보존, 중간 `*` 처리
- **연동**: `user_profile` 저장 전처리에서 마스킹 적용
- **테스트**: 각 마스킹 유형별 경계값 테스트

#### [QIM-03] DI(Duplicate Identity) 생성 서비스 구현
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/identity/DiGenerationService.java`
- **구현 내용**:
  - DI = HMAC-SHA256(`{siteCode}:{qimUserId}:{serviceSecret}`)
  - 기관별 DI 독립 생성 (기관 코드를 siteCode로 사용)
  - `di_map` JSON 컬럼에 `{"AGENCY_CODE": "di_value"}` 형태로 저장
  - 기존 DI 재사용 (중복 기관 연동 시 동일 DI 반환)
- **환경변수**: `QIM_DI_SECRET` (서비스 공통 비밀키)
- **테스트**: 동일 기관 동일 사용자 DI 일치, 다른 기관 DI 불일치

#### [IDO-01] allowedAttributes 필터링 구현
- **파일 수정**: `ido/src/main/java/kr/go/smes/ido/policy/PolicyEngineImpl.java`
- **구현 내용**:
  - `buildHandoffPayload` 내 TODO 주석 제거 후 실구현
  - `AgencyMeta.allowedAttributes` 목록 기준으로 `HandoffPayload.attributes` Map 필터링
  - 허용 속성 외 키는 응답에서 제거
  - `null` 또는 빈 리스트인 경우 기본값(최소 속성 세트) 반환
- **테스트**: 속성 필터 적용 전후 payload 비교, 빈 허용 목록 처리

```java
// 구현 위치: PolicyEngineImpl.buildHandoffPayload
Map<String, String> filteredAttributes = rawAttributes.entrySet().stream()
    .filter(e -> agencyMeta.getAllowedAttributes().contains(e.getKey()))
    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
```

### 2.3 DB 마이그레이션

#### Q-IM V3 마이그레이션 추가
```sql
-- q-im/src/main/resources/db/migration/V3__add_ci_crypto_version.sql
ALTER TABLE qim.user_profile
    ADD COLUMN ci_key_version VARCHAR(10) DEFAULT 'v1' NOT NULL COMMENT 'CI 암호화 키 버전';

ALTER TABLE qim.user_profile
    ADD COLUMN di_map_updated_at DATETIME(6) COMMENT 'DI Map 최종 갱신 시각';
```

### 2.4 완료 기준 (Definition of Done)
- [ ] CI 암복호화 단위 테스트 커버리지 ≥ 90%
- [ ] PII 마스킹 전체 케이스 테스트 통과
- [ ] DI 생성 결정론적 일치성 테스트 통과
- [ ] `allowedAttributes` 필터 통합 테스트: 허용 속성 포함·미포함 시나리오
- [ ] `user_profile` CRUD 시 마스킹·암호화 자동 적용 확인

---

## 3. Sprint 2 — Critical 보안: 인증 강화 & 화이트리스트 (2주)

### 3.1 목표
- PKCE 적용으로 인가 코드 가로채기 공격 방지
- Callback URL 화이트리스트 검증 활성화
- agencySubjectId와 Q-IM DI 연계

### 3.2 작업 목록

#### [QS-01] PKCE (RFC 7636) 구현
- **파일 수정**: `q-sign/src/main/java/kr/go/smes/qsign/keycloak/KeycloakAuthUrlController.java`
- **파일 신규**: `q-sign/src/main/java/kr/go/smes/qsign/pkce/PkceService.java`
- **구현 내용**:
  - `code_verifier` 생성: 43~128자 랜덤 문자열 (Base64URL)
  - `code_challenge` = BASE64URL(SHA256(ASCII(code_verifier)))
  - `code_challenge_method=S256` Authorization URL에 추가
  - `KeycloakStateEntry`에 `codeVerifier` 필드 추가
  - 콜백 시 `code_verifier` 전달 및 검증
- **IdO 브로커 연동**: `BrokerService.buildKeycloakAuthorizationUrl`에 PKCE 파라미터 추가
- **테스트**: code_challenge 생성 정확성, 잘못된 verifier 거부

#### [IDO-02] Callback URL 화이트리스트 검증 구현
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/handoff/CallbackUrlValidator.java`
- **파일 수정**: `ido/src/main/java/kr/go/smes/ido/api/HandoffController.java`
- **구현 내용**:
  - `HandoffIssueRequest`에 `redirectUri` 필드 추가 (선택적)
  - `AgencyMeta.callbackWhitelist` 목록과 요청 URL 비교
  - 완전 일치 또는 도메인 접두사 일치 방식 지원
  - 불일치 시 HTTP 400 + `CALLBACK_URL_NOT_ALLOWED` 에러 코드
- **테스트**: 화이트리스트 일치·불일치·빈 목록·와일드카드 케이스

```java
// CallbackUrlValidator 인터페이스
public interface CallbackUrlValidator {
    void validate(String requestedUrl, List<String> whitelist);
}
```

#### [IDO-03] agencySubjectId ↔ Q-IM DI 연계
- **파일 수정**: `ido/src/main/java/kr/go/smes/ido/policy/PolicyEngineImpl.java`
- **파일 수정**: `ido/src/main/java/kr/go/smes/ido/infrastructure/QimClientImpl.java`
- **구현 내용**:
  - Q-IM에 DI 조회 API 추가 (`GET /api/v1/internal/users/{qimUserId}/di?agencyCode=`)
  - `QimClient` 인터페이스에 `getDi(qimUserId, agencyCode)` 메서드 추가
  - `PolicyEngineImpl.generateAgencySubjectId`에서 Q-IM DI를 우선 사용
  - DI가 없는 경우(신규 기관 연동) Q-IM에 DI 생성 요청 후 사용
- **테스트**: DI 조회 성공, DI 없을 때 자동 생성, Q-IM 장애 시 Fallback

#### [SEC-07] Callback URL 화이트리스트 — agency_meta 데이터 보강
- **파일 신규**: `ido/src/main/resources/db/migration/V9__add_callback_validation_flag.sql`
```sql
ALTER TABLE ido.agency_meta
    ADD COLUMN callback_validation_enabled BOOLEAN DEFAULT TRUE NOT NULL;

COMMENT ON COLUMN ido.agency_meta.callback_validation_enabled
    IS '콜백 URL 화이트리스트 검증 활성화 여부 (false: PoC 하위호환)';
```

### 3.3 완료 기준
- [ ] PKCE 적용 후 Keycloak 인가 흐름 E2E 테스트 통과
- [ ] Callback URL 화이트리스트 검증 단위·통합 테스트 통과
- [ ] DI 연계 Q-IM 내부 API 단위 테스트 통과
- [ ] agency-stub 시뮬레이터에서 PKCE 포함 전체 흐름 성공

---

## 4. Sprint 3 — Q-IM 핵심 서비스 (2주)

### 4.1 목표
- 사용자 생명주기(등록·조회·탈퇴) API 실구현
- IdO member_lookup 서비스 연동

### 4.2 작업 목록

#### [QIM-04] 사용자 등록 서비스
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/user/UserRegistrationService.java`
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/api/UserRegistrationController.java`
- **구현 내용**:
  - `POST /api/v1/internal/users` — 인증 결과로부터 사용자 자동 생성
  - 요청: `authResultId`, `identifierHash`, `rawCi` (암호화 후 저장), `maskedName`, `maskedMobile`
  - 중복 `identifier_hash` 존재 시 기존 사용자 반환 (Upsert 방식)
  - 신규 사용자 생성 시 `UserEvent` Kafka 발행
  - `auth_mean_mapping` 자동 생성 (provider_code + identifier_hash 매핑)
- **DB 마이그레이션**: V3 (위 Sprint 1 포함)

#### [QIM-05] 사용자 조회 API
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/api/UserQueryController.java`
- **구현 내용**:
  - `GET /api/v1/internal/users/{qimUserId}` — 사용자 상세 조회
  - `GET /api/v1/internal/users/by-hash?identifierHash=` — 해시 기반 조회
  - `GET /api/v1/internal/users/{qimUserId}/di?agencyCode=` — DI 조회/생성
  - 응답에 CI 복호화 없이 마스킹 데이터만 포함 (CI는 별도 내부 API)
  - 내부 API 키 인증 (`X-Internal-Api-Key` 헤더)

#### [QIM-07] 사용자 상태 전이 서비스
- **파일 신규**: `q-im/src/main/java/kr/go/smes/qim/user/UserStatusService.java`
- **구현 내용**:
  - `ACTIVE` → `SUSPENDED` → `WITHDRAWN` 단방향 상태 전이
  - 탈퇴(`WITHDRAWN`) 시 `user_profile` PII 즉시 삭제 (Right to be forgotten)
  - 각 상태 전이 `user_status_history` 감사 로그 기록
  - 상태 변경 이벤트 Kafka 발행 (`UserEvent.USER_STATUS_CHANGED`)

#### [IDO-04] member_lookup 서비스 구현
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/memberlookup/MemberLookupService.java`
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/memberlookup/MemberLookupController.java`
- **구현 내용**:
  - `POST /api/v1/member-lookup` — 기관이 CI/DI로 회원 조회 요청
  - `member_lookup_request` 테이블에 요청 기록
  - Q-IM 조회 후 `allowedAttributes` 필터링 적용
  - 조회 결과 `audit_log` 기록 (민감 데이터 접근 추적)
  - 기관별 일일 조회 한도 적용 (초기: 10,000건)

### 4.3 DB 마이그레이션
```sql
-- q-im V4: 탈퇴 사용자 PII 삭제 이력
-- ido V9: member_lookup 한도 컬럼 추가
ALTER TABLE ido.agency_meta
    ADD COLUMN daily_lookup_limit INT DEFAULT 10000 NOT NULL;
```

### 4.4 완료 기준
- [ ] 사용자 등록 → 조회 → 상태 변경 → 탈퇴 전체 라이프사이클 E2E 테스트
- [ ] PII 탈퇴 삭제 검증 (GDPR Right to be Forgotten)
- [ ] member_lookup API Postman 컬렉션 작성
- [ ] Q-IM → IdO DI 조회 통합 테스트

---

## 5. Sprint 4 — IdO 정책 고도화 & Admin API (2주)

### 5.1 목표
- `integration_type` 분기 처리 완성
- 기관 Admin REST API 구현
- Webhook Dispatcher 서비스 완성
- Audit Log 호출 보강

### 5.2 작업 목록

#### [IDO-05] integration_type 분기 처리
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/handoff/strategy/HandoffStrategy.java` (인터페이스)
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/handoff/strategy/DirectHandoffStrategy.java`
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/handoff/strategy/BridgeHandoffStrategy.java`
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/handoff/strategy/InternalSsoHandoffStrategy.java`
- **구현 내용**:
  - Strategy 패턴으로 `integration_type`별 발급/검증 로직 분리
  - `DIRECT`: 현재 구현 유지
  - `BRIDGE`: `bridge_endpoint`로 티켓 포워딩
  - `INTERNAL_SSO`: `sso_domain` 기반 쿠키 세션 발급
  - `APACHE_GATE`: Apache mod_auth 호환 헤더 주입
- **파일 수정**: `HandoffServiceImpl` → Strategy 패턴 위임

#### [IDO-06] 기관 Admin API
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/admin/AgencyAdminController.java`
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/admin/AgencyAdminService.java`
- **엔드포인트 목록**:

| Method | Path | 설명 |
|--------|------|------|
| POST | `/admin/v1/agencies` | 신규 기관 등록 |
| GET | `/admin/v1/agencies` | 기관 목록 조회 (페이징) |
| GET | `/admin/v1/agencies/{agencyCode}` | 기관 상세 조회 |
| PUT | `/admin/v1/agencies/{agencyCode}` | 기관 정보 수정 |
| POST | `/admin/v1/agencies/{agencyCode}/activate` | 기관 활성화 |
| POST | `/admin/v1/agencies/{agencyCode}/deactivate` | 기관 비활성화 |
| POST | `/admin/v1/agencies/{agencyCode}/rotate-key` | API Key 로테이션 |
| GET | `/admin/v1/agencies/{agencyCode}/history` | 기관 변경 이력 |
| GET | `/admin/v1/agencies/{agencyCode}/stats` | 기관 연동 통계 |

- **보안**: 별도 `X-Admin-Token` 헤더 + IP 화이트리스트 (`HandoffAgencyKeyInterceptor`와 분리)
- **감사**: 모든 Admin API 호출 `audit_log` 기록 필수

#### [IDO-07] Webhook Dispatcher 서비스
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/webhook/WebhookDispatchService.java`
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/webhook/WebhookDispatchScheduler.java`
- **구현 내용**:
  - `@Scheduled(fixedDelay = 500ms)` 폴링 방식 (`webhook_dispatch_outbox` PENDING 건 처리)
  - HMAC-SHA256 서명 헤더 `X-Webhook-Signature` 포함
  - 최대 3회 재시도, 지수 백오프 (1s, 4s, 16s)
  - 3회 실패 시 상태 `DEAD_LETTER` 로 변경 + 알림 로그
  - 배치 크기: 최대 50건 동시 처리
- **DB**: `webhook_dispatch_outbox.status` 에 `DEAD_LETTER` 상태 추가

#### [IDO-08] Audit Log 호출 보강
- **파일 수정**: `HandoffServiceImpl.issue()`, `HandoffServiceImpl.verify()`
- **구현 내용**:
  - `issue()`: 티켓 발급 성공/실패 감사 로그 (`HANDOFF_ISSUED`, `HANDOFF_ISSUE_FAILED`)
  - `verify()`: 티켓 검증 성공/실패/재사용 시도 감사 로그
  - `revoke()`: 티켓 취소 감사 로그
  - 감사 로그 필드: `actor`(agencyCode), `resource`(ticketId), `outcome`, `metadata`(JSON)

### 5.3 DB 마이그레이션
```sql
-- V10: Admin API 보안 + Webhook 상태 추가
ALTER TABLE ido.agency_webhook_config
    ADD COLUMN max_retry_count INT DEFAULT 3 NOT NULL,
    ADD COLUMN retry_backoff_multiplier INT DEFAULT 4 NOT NULL;

ALTER TABLE ido.webhook_dispatch_outbox
    ALTER COLUMN status TYPE VARCHAR(20);
-- DEAD_LETTER 상태 추가 (CHECK 제약 갱신)
```

### 5.4 완료 기준
- [ ] Admin API Postman 컬렉션 + OpenAPI 3.1 스펙 작성
- [ ] Webhook 재전송 3회 실패 → DEAD_LETTER 전환 테스트
- [ ] BRIDGE 연동 타입 E2E 테스트 (mock bridge endpoint)
- [ ] Audit Log handoff issue/verify 경로 100% 기록 확인

---

## 6. Sprint 5 — 운영 기반 (2주)

### 6.1 목표
- AES 키 로테이션 자동화
- 기관별 Rate Limiting 적용
- 모니터링 대시보드 연동

### 6.2 작업 목록

#### [IDO-09] AES 키 로테이션
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/crypto/KeyVersionRegistry.java`
- **파일 수정**: `HandoffCryptoService`
- **구현 내용**:
  - 키 버전 접두사: 암호화 결과 = `v{n}.{base64url(iv)}.{base64url(ciphertext)}`
  - 복호화 시 버전 파싱 후 해당 버전 키 사용
  - 환경변수 배열: `IDO_HANDOFF_AES_KEY_V1`, `IDO_HANDOFF_AES_KEY_V2`, ...
  - 신규 암호화는 항상 최신 버전 키 사용
  - 로테이션 Admin API: `POST /admin/v1/keys/rotate`
- **Q-IM CI 암호화도 동일 패턴 적용**: `CiCryptoService`

#### [IDO-12] Rate Limiting (기관별)
- **의존성 추가**: `resilience4j-ratelimiter` (이미 Resilience4j 도입됨)
- **파일 신규**: `ido/src/main/java/kr/go/smes/ido/config/AgencyRateLimiterConfig.java`
- **구현 내용**:
  - `agency_meta.daily_lookup_limit` 기반 동적 Rate Limiter 생성
  - `HandoffAgencyKeyInterceptor` 또는 별도 Filter에서 적용
  - 초과 시 HTTP 429 + `Retry-After` 헤더
  - Redis 기반 분산 카운터 (기관 코드 키)
- **application.yml 추가**:
```yaml
resilience4j:
  ratelimiter:
    instances:
      default-agency:
        limit-for-period: 1000
        limit-refresh-period: 1s
        timeout-duration: 0s
```

#### [OPS-04] Prometheus + Grafana 연동
- **파일 수정**: 각 모듈 `application.yml` actuator 설정
- **Docker Compose 추가**: `infra/docker/docker-compose.yml`에 Prometheus, Grafana 서비스 추가
- **파일 신규**: `infra/monitoring/prometheus.yml`
- **파일 신규**: `infra/monitoring/grafana/dashboards/onepass-overview.json`
- **주요 메트릭**:
  - Handoff 티켓 발급/검증 TPS
  - Circuit Breaker 상태 (OPEN/CLOSED)
  - Webhook 발송 성공률
  - 기관별 API 호출량

#### [OPS-03] 로그 집계 (Loki + Grafana)
- **Docker Compose 추가**: Loki, Promtail 서비스
- **파일 신규**: `infra/monitoring/loki-config.yml`
- **파일 신규**: `infra/monitoring/promtail-config.yml`
- **로그 레이블**: `service`, `agencyCode`, `correlationId`, `level`

### 6.3 환경변수 추가 목록

```bash
# 키 로테이션
IDO_HANDOFF_AES_KEY_V1=<base64-32bytes>
IDO_HANDOFF_AES_KEY_V2=<base64-32bytes>   # 로테이션 시 추가
IDO_CURRENT_KEY_VERSION=v1

# Rate Limiting
IDO_RATE_LIMITER_ENABLED=true
IDO_DEFAULT_RATE_PER_SECOND=100

# Q-IM CI 암호화
QIM_CI_AES_KEY_V1=<base64-32bytes>
QIM_DI_SECRET=<base64-32bytes>

# Admin API
IDO_ADMIN_TOKEN_HASH=<sha256-of-admin-token>
IDO_ADMIN_ALLOWED_IPS=127.0.0.1,10.0.0.0/8
```

### 6.4 완료 기준
- [ ] 키 버전 v1 → v2 로테이션 후 기존 v1 티켓 복호화 성공
- [ ] 기관별 Rate Limit 초과 시 HTTP 429 확인
- [ ] Grafana 대시보드: 핵심 메트릭 5종 이상 시각화
- [ ] 부하 테스트: 100 TPS에서 Circuit Breaker 미동작, p99 < 200ms

---

## 7. Sprint 6 — 실 IdP 연동 & 검증 (2주)

### 7.1 목표
- 실제 외부 IdP (카카오, 네이버) OIDC 연동
- 전체 E2E 시나리오 자동화 테스트
- 부하 테스트 및 성능 튜닝

### 7.2 작업 목록

#### [QS-03] 카카오 OIDC 연동
- **파일 신규**: `q-sign/src/main/java/kr/go/smes/qsign/idp/kakao/KakaoOidcService.java`
- **파일 신규**: `q-sign/src/main/java/kr/go/smes/qsign/idp/kakao/KakaoProperties.java`
- **구현 내용**:
  - 카카오 Authorization URL 생성 (PKCE 포함)
  - 카카오 Token Endpoint 호출
  - 카카오 JWKS 검증 (`kid` 기반 키 선택)
  - 카카오 UserInfo 클레임 매핑 → `AuthResult` 저장
- **환경변수**: `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI`

#### [QS-03] 네이버 OIDC 연동
- 카카오와 동일 패턴, 네이버 API 스펙 차이점 처리
- **파일 신규**: `q-sign/src/main/java/kr/go/smes/qsign/idp/naver/NaverOidcService.java`

#### E2E 자동화 테스트
- **파일 신규**: `tests/e2e/` 디렉토리 (Playwright 또는 RestAssured)
- **시나리오**:
  1. 기관 시뮬레이터 → IdO Ticket 발급 → Verify → 세션 생성
  2. Webhook 수신 → 이벤트 처리 → 세션 무효화
  3. 인증 수준 미달 → 거부 응답
  4. 기관 API Key 오류 → 401 응답
  5. Rate Limit 초과 → 429 응답
  6. Circuit Breaker OPEN → Fallback 응답

#### 부하 테스트
- **도구**: k6 또는 Gatling
- **파일 신규**: `tests/load/handoff-load-test.js`
- **목표 지표**:
  - Handoff issue: 200 TPS, p99 < 150ms
  - Handoff verify: 200 TPS, p99 < 100ms
  - 오류율: < 0.1%

### 7.3 완료 기준
- [ ] 카카오 OIDC 실 계정 로그인 E2E 성공
- [ ] E2E 자동화 시나리오 6종 모두 통과
- [ ] 부하 테스트 200 TPS 목표 달성
- [ ] 보안 취약점 스캔 (OWASP ZAP) 주요 항목 0건

---

## 8. 기술 부채 및 장기 개선 항목 (v3.0 이후)

| # | 항목 | 설명 | 시기 |
|---|------|------|------|
| DEBT-01 | HashiCorp Vault / AWS KMS 연동 | 환경변수 키 관리 → 전용 KMS 이전 | v3.0 |
| DEBT-02 | SAML 2.0 SP 구현 | 일부 공공기관 SAML 요구 대응 | v3.0 |
| DEBT-03 | SCIM 2.0 엔드포인트 | 외부 IdM 시스템 사용자 동기화 | v3.0 |
| DEBT-04 | JWT Bearer Token (기관 API) | Handoff 외 일반 API 인증 | v3.0 |
| DEBT-05 | DB 스키마 완전 격리 | 현재 단일 PostgreSQL → 기관별 schema 격리 | v3.0 |
| DEBT-06 | 관리자 UI (Admin Console) | React 기반 기관 관리 대시보드 | v3.0 |
| DEBT-07 | OpenTelemetry 완전 연동 | Traceparent Filter → OTel SDK 전환 | v3.0 |
| DEBT-08 | 다중 기관 CI/CD | 기관별 독립 배포 파이프라인 | v3.0 |

---

## 9. 테스트 전략

### 9.1 테스트 피라미드

```
          ┌───────────┐
          │  E2E (6종) │  ← Sprint 6
         ┌┴───────────┴┐
         │ 통합 테스트   │  ← 각 Sprint PR 필수
        ┌┴─────────────┴┐
        │  단위 테스트    │  ← 커버리지 ≥ 80%
        └───────────────┘
```

### 9.2 테스트 커버리지 목표

| 모듈 | 현재 | 목표 |
|------|------|------|
| q-sign | 미측정 | ≥ 80% |
| q-im | 미측정 | ≥ 85% |
| ido | 미측정 | ≥ 85% |
| agency-stub | 미측정 | ≥ 70% |
| platform-common | 미측정 | ≥ 90% |

### 9.3 PR 체크리스트 (전 Sprint 공통)

- [ ] 단위 테스트 신규 작성 및 통과
- [ ] 통합 테스트 (Testcontainers 사용) 작성 및 통과
- [ ] Checkstyle / SpotBugs 경고 0건
- [ ] API 변경 시 OpenAPI 스펙 업데이트
- [ ] DB 마이그레이션 스크립트 포함 (해당 시)
- [ ] 환경변수 추가 시 README.md 업데이트
- [ ] Audit Log 기록 여부 확인 (민감 경로)

---

## 10. 아키텍처 변경 사항 요약

### 10.1 신규 추가 컴포넌트

```
platform-common
└── crypto/
    ├── KeyVersionedCryptoUtil.java    (키 버전 관리 유틸)
    └── PkceUtil.java                  (PKCE 유틸)

q-im
└── crypto/
    ├── CiCryptoService.java
    ├── CiCryptoServiceImpl.java
    └── PiiMaskingService.java
└── identity/
    └── DiGenerationService.java
└── user/
    ├── UserRegistrationService.java
    └── UserStatusService.java
└── api/
    ├── UserRegistrationController.java
    └── UserQueryController.java

q-sign
└── pkce/
    └── PkceService.java
└── idp/
    ├── kakao/KakaoOidcService.java
    └── naver/NaverOidcService.java

ido
└── handoff/
    ├── strategy/HandoffStrategy.java
    ├── strategy/DirectHandoffStrategy.java
    ├── strategy/BridgeHandoffStrategy.java
    └── CallbackUrlValidator.java
└── memberlookup/
    ├── MemberLookupService.java
    └── MemberLookupController.java
└── webhook/
    ├── WebhookDispatchService.java
    └── WebhookDispatchScheduler.java
└── admin/
    ├── AgencyAdminController.java
    └── AgencyAdminService.java
└── crypto/
    └── KeyVersionRegistry.java

infra/monitoring/
├── prometheus.yml
├── loki-config.yml
├── promtail-config.yml
└── grafana/dashboards/onepass-overview.json
```

### 10.2 DB 마이그레이션 계획

| 마이그레이션 | 모듈 | Sprint | 내용 |
|-------------|------|--------|------|
| Q-IM V3 | q-im | S1 | ci_key_version, di_map_updated_at 컬럼 |
| IDO V9 | ido | S2 | callback_validation_enabled, daily_lookup_limit |
| IDO V10 | ido | S4 | webhook retry 설정, DEAD_LETTER 상태 |
| IDO V11 | ido | S5 | key_version 컬럼 (handoff_audit) |

---

## 11. 환경별 배포 구성

### 11.1 로컬 개발 (현재 유지)
```bash
docker compose -f infra/docker/docker-compose.yml up -d
./gradlew :q-sign:bootRun :q-im:bootRun :ido:bootRun :agency-stub:bootRun
```

### 11.2 개발 서버 (Sprint 5 이후)
```bash
docker compose -f infra/docker/docker-compose.yml \
               -f infra/docker/docker-compose.monitoring.yml up -d
```

### 11.3 스테이징 (Sprint 6 이후)
- Kubernetes Helm Chart 기반 배포 (별도 계획 수립)
- TLS 인증서 적용 (Let's Encrypt 또는 내부 CA)
- Vault Agent Injector로 AES/HMAC 키 주입

---

*문서 끝 — 참조 분석 문서: `2026-05-08_unimplemented_analysis.md`*
