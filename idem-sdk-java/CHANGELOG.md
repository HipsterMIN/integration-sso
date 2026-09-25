# Changelog — onepass-agency-sdk

이 파일은 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) 형식을 따른다.
버전 번호는 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 준수한다.

---

## [Unreleased]

_(다음 릴리즈 예정 변경사항 없음)_

---

## [0.1.0-GAP-PATCH] — 2026-05-16 (통합 버전: v0.8.10 / PR #129 MERGED)

> **GAP-1~5 서버 정합성 수정 완료** — 36개 테스트 전체 통과 (0 failures)  
> **유관기관 개발자 사용 가이드** 신규 작성 (757줄, PR #130 MERGED) — [`docs/idem-sdk-java-usage-guide.md`](../../docs/idem-sdk-java-usage-guide.md)

### Fixed (갭 분석 기반 수정 — 서버 정합성 확보)

#### GAP-1 (P0) BREAKING: HMAC 서명 알고리즘 서버와 정합성 맞춤
- **수정 전** (`HmacSigner.sign`): `"{METHOD}\n{PATH}\n{TIMESTAMP_MS}\n{SHA256(BODY)}"` — 서버 미사용 알고리즘
- **수정 후** (`HmacSigner.sign`): `"{agencyCode}:{idempotencyKey}:{epochSeconds}"` — 서버 `HmacSignatureFilter.computeHmac()`와 완전 일치
- `AgencyGatewayClient`에서 `X-Timestamp` 헤더 전송 제거 (서버 `HmacSignatureFilter`가 읽지 않음)
- HMAC 서명 타임스탬프 단위: 밀리초(`ms`) → 초(`epochSeconds`)로 변경
- `HmacSigner.sign()` 메서드 시그니처 변경: `(method, path, timestampMs, body)` → `(agencyCode, idempotencyKey, epochSeconds)`

#### GAP-2 (P1): `triggerOutbound()` @Deprecated 처리
- `AgencyGatewayClient.triggerOutbound()` 메서드에 `@Deprecated` 어노테이션 추가
- 서버가 이벤트 수신 후 내부적으로 Webhook을 자동 발송하므로, 기관 측에서 직접 호출할 필요 없음
- 하위 호환성을 위해 메서드는 유지하되, 다음 Major 버전에서 제거 예정
- `README.md` §3.2, §5(API 메서드 섹션)에 Deprecated 경고 추가

#### GAP-3 (P0): X-Event-Type 헤더 전송 추가
- `AgencyGatewayClient.sendInbound()`가 `X-Event-Type` 헤더를 누락하여 서버가 항상 `eventType="CUSTOM"` 처리하던 버그 수정
- `InboundEvent.eventType`을 JSON body의 `"event_type"` 필드와 `X-Event-Type` 헤더 **양쪽에** 전송
- 서버 `AgencyGatewayController.receiveInbound()`는 `X-Event-Type` 헤더로 이벤트 라우팅

#### GAP-4 (P1): X-Correlation-Id → X-Correlation-ID 헤더명 통일
- 서버 `AgencyGatewayController`의 `HEADER_CORRELATION_ID = "X-Correlation-ID"` (대문자 D)와 일치하도록 수정
- HTTP/1.1은 헤더 이름이 대소문자 불감이지만 코드 일관성 확보

#### GAP-5 (P2): `GatewayResponse` 헬퍼 메서드 추가
- `GatewayResponse.getBodyField(String fieldName)` 신규 추가 — JSON 응답 본문에서 외부 라이브러리 없이 필드 값 추출
  - `fieldName`이 없거나 본문이 null이면 `null` 반환 (안전한 null-safe 처리)
  - 예: `response.getBodyField("agencyCode")` → `"AGENCY_STUB_001"`
- `GatewayResponse.isValidJson()` 신규 추가 — JSON 응답 본문 유효성 검사
  - 본문이 `{...}` 형태의 유효한 JSON 객체인지 검사 (외부 라이브러리 불필요)

#### GAP-7 (P2): Sprint 17 Phase 4 Breaking Change 경고 강화
- `Builder.signRequests()` Javadoc에 Phase 4 전환 시 영향 및 대응 방법 명시
- `IDO_HMAC_SIG_REQUIRED=true` 전환 시 X-Internal-Sig 없는 요청이 401로 거부됨을 안내

### Changed

#### README §3.2, §5 triggerOutbound @Deprecated 경고 추가 (GAP-2)
- §3.2 예시 코드에 `@Deprecated` 주석 및 Blockquote 경고 추가
- §5 API 메서드 목록에 Deprecated 표시 추가

#### README §5 API 레퍼런스 `getBodyField()`, `isValidJson()` 추가 (GAP-5)
- 새로운 헬퍼 메서드 사용 예시 코드 추가

#### README §7 HMAC 서명 가이드 전면 수정
- 서명 알고리즘 설명을 서버 실제 알고리즘(`{agencyCode}:{idempotencyKey}:{epochSeconds}`)으로 교체
- `hmacSecret`이 `apiKey`와 별개의 비밀키임을 명시
- `X-Timestamp` 헤더 관련 잘못된 안내 제거
- 서버 측 검증 예시 코드를 실제 서버 로직에 맞게 수정

### Tests

- `S16-T2`: `X-Event-Type` 헤더 포함 검증 추가 (GAP-3)
- `S16-T4`: `X-Timestamp` 헤더 미포함 검증 + HMAC 서명 알고리즘 검증 강화 (GAP-1)
- `S16-T5`: `idempotencyKey` 미지정 시 UUID v4 자동 생성 검증 (기존 유지)
- `S16-T8` (MockWebServer): `X-Event-Type` 헤더 전송 + `X-Timestamp` 미전송 검증 추가
- `HmacSigner`: `sign()` 시그니처 변경에 따른 테스트 수정
- `hmacSigner_matchesServerAlgorithm` (신규): 서버 `HmacSignatureFilter.computeHmac()`와 동일한 결과 생성 검증
- `triggerOutbound_deprecated` (신규): `@Deprecated` 어노테이션 존재 확인 (GAP-2)
- `getBodyField_extractsValue` (신규): JSON 응답에서 필드 추출 정확성 검증 (GAP-5)
- `isValidJson_detectsValidAndInvalid` (신규): 유효/무효 JSON 본문 판별 검증 (GAP-5)
- **총 36개 테스트 통과** (0 failures, 0 errors)

---

## [1.0.0] — 2026-09-26

- 제품 1.0 동결(S9 PR-4)에 맞춰 SDK 버전을 `1.0.0` 으로 올린다. API 변경 없음 — `0.1.0-SNAPSHOT` 과 같은 계약(Handoff 티켓 검증·HMAC 서명·이벤트 수신). 좌표 `io.github.hipstermin.idem:idem-sdk-java:1.0.0`.
- 알려진 것: 설정 키 `onepass.*`·헤더 `OnePass-Signature` 는 외부 계약이라 그대로 두었다(`docs/naming.md` §2.4). 새 이름은 SDK 2.0 에서.

## [0.1.0-SNAPSHOT] — 2026-05-14

**Sprint 16 완성 버전** — OnePass Agency SDK 최초 배포 후보

### Added

#### 코어 클라이언트
- `AgencyGatewayClient` — OnePass Gateway API 메인 클라이언트 (Builder 패턴, 스레드 안전)
  - `sendInbound(InboundEvent)` — `POST /api/v1/agency/gateway/inbound/event`
  - `triggerOutbound(OutboundNotifyRequest)` — `PATCH /api/v1/agency/gateway/outbound/notify`
  - `getStatus(String agencyCode)` — `GET /api/v1/agency/gateway/status/{agencyCode}`
  - Builder 필수 필드 검증 강화 (`baseUrl`, `apiKey` 미설정 시 `AgencySdkException`)
  - `signRequests=true` 시 `hmacSecret` 누락 검증 추가

#### 모델 클래스
- `InboundEvent` — 인바운드 이벤트 불변 모델 (Builder 패턴, 내장 JSON 직렬화)
  - 필수: `eventType`, `agencyCode`, `idempotencyKey`
  - 선택: `payloadJson` (raw JSON 문자열), `correlationId`
  - `toJsonString()` — 외부 라이브러리 없이 JSON 직렬화 (zero-dep)
- `OutboundNotifyRequest` — 아웃바운드 알림 요청 불변 모델 (Builder 패턴, 내장 JSON 직렬화)
  - 필수: `agencyCode`, `eventType`
  - 선택: `payload`, `idempotencyKey`, `correlationId`
- `GatewayResponse` — API 응답 래퍼
  - `getHttpStatus()`, `getBody()`, `getCorrelationId()`, `getRequestId()`
  - `isSuccess()` — 2xx 여부
  - `isIdempotencyConflict()` — 409 Conflict 여부

#### HTTP 어댑터
- `AgencyHttpAdapter` — HTTP 구현 완전 분리 인터페이스 (단일 추상 메서드)
- `HttpUrlConnectionAdapter` — JDK 내장, 외부 의존성 없음 (기본 구현체)
  - 연결/읽기 타임아웃 설정 가능 (기본: 5초/30초)
  - 리다이렉트 비활성화 (보안 정책)
  - `openConnection()` protected — 테스트에서 MockURLConnection 주입 가능
- `OkHttpAgencyAdapter` — OkHttp3 4.x 기반 (HTTP/2, 커넥션 풀링)
  - 기본 클라이언트 또는 커스텀 `OkHttpClient` 주입 선택
- `ApacheHttpAgencyAdapter` — Apache HttpClient 5.x 기반 (엔터프라이즈 환경)
  - 커넥션 풀 세밀한 제어
  - `ownsClient` 플래그: SDK 생성 클라이언트만 자동 close
  - `close()` 메서드 제공

#### 보안
- `HmacSigner` — HMAC-SHA256 서명 생성/검증
  - 서명 대상: `{METHOD}\n{PATH}\n{TIMESTAMP_MS}\n{SHA256(BODY)}`
  - `X-Internal-Sig` 헤더용 64자 HEX 출력
  - `verifySignature()` — `MessageDigest.isEqual()` 기반 상수시간 비교 (타이밍 공격 방지)
  - `sha256Hex()` — static 유틸리티 메서드

#### 멱등성 키
- `IdempotencyKeyGenerator` — 멱등성 키 생성 유틸리티 (모든 메서드 static)
  - `generate()` — UUID v4 (완전 랜덤)
  - `generateWithPrefix(String prefix)` — `{PREFIX}-{uuid4}` 형식
  - `generateSequential(String agencyCode)` — `{CODE}-{epochMs}-{seq10}` 형식 (동시성 안전)
  - `constantTimeEquals()` — 상수시간 문자열 비교

#### 예외 계층
- `AgencySdkException` — SDK 기본 예외 (`RuntimeException` 상속)
  - `getErrorCode()` — SDK 내부 오류 코드 (`SDK_HTTP_ERROR`, `SDK_CONFIG_ERROR` 등)
- `AgencyHttpException` — HTTP 통신 오류 (`AgencySdkException` 상속)
  - `getHttpStatus()` — HTTP 상태 코드 (-1 = 네트워크 오류)
  - `getResponseBody()` — 서버 응답 본문 (최대 200자)
  - `isClientError()` — 4xx 여부
  - `isServerError()` — 5xx 여부

#### 빌드/배포
- `build.gradle.kts` — Java 8 소스/바이너리 호환 빌드 설정
  - `maven-publish` 플러그인 + `publishing {}` 블록 (Maven Central / 내부 Nexus)
  - `signing {}` 블록 — GPG 서명 설정 (인메모리 키 또는 GPG 명령 선택)
  - `SKIP_SIGNING=true` 환경변수로 서명 건너뛰기 지원
  - `withSourcesJar()` + `withJavadocJar()` — Maven Central 필수 아티팩트
  - POM 메타정보: name, description, url, license, developer, scm 포함

#### 문서
- `README.md` — 배포 매뉴얼 포함 전체 사용 가이드
  - 빠른 시작 (Quick Start)
  - 의존성 추가 (Gradle/Maven)
  - 전체 API 레퍼런스
  - HTTP 어댑터 교체 가이드 (HttpURLConnection / OkHttp3 / Apache HC5 / Lambda)
  - HMAC 서명 설정 가이드 (클라이언트 서명 + 서버 측 Webhook 검증)
  - 에러 처리 패턴 (4xx/5xx/네트워크 오류, 멱등성 충돌)
  - 멱등성 키 전략 + Transactional Outbox 예시
  - Spring Boot 연동 예시 (`@Configuration`, `application.yml`)
  - 배포 절차 (Maven Central, 내부 Nexus, 로컬 테스트, 배포 체크리스트)
  - 빌드 및 테스트 가이드

#### 테스트 (Sprint 16 S16-T1 ~ S16-T8)
- `AgencyGatewayClientTest` — 단위/통합 테스트 13개
  - MockAdapter 기반 단위 테스트 (S16-T1 ~ T7)
  - MockWebServer 실제 HTTP 왕복 (S16-T8)
  - HmacSigner 독립 검증
  - Builder 유효성 검사 검증
  - InboundEvent JSON 직렬화 검증
  - IdempotencyKeyGenerator 3전략 검증

### Fixed
- `AgencyGatewayClient.Builder.build()` — `apiKey` 미설정 시 NPE 대신 명확한 `AgencySdkException` 발생하도록 수정
- `AgencyGatewayClient.Builder.build()` — `signRequests=true` + `hmacSecret` 미설정 조합 사전 검증 추가

### Technical Notes

- **JDK 버전 프리 설계**: Java 8 바이너리 출력, JDK 21로 빌드
- **Automatic-Module-Name**: `io.github.hipstermin.idem.sdk.agency` (JPMS 호환)
- **Spring BOM 격리**: 루트 프로젝트의 Spring Boot 3.x BOM이 SDK에 전이되지 않도록 `configurations` 블록으로 차단
- **Lombok 컴파일 타임 전용**: 런타임 의존성 없음

---

## 버전 정책

| 버전 유형 | 변경 조건 | 예시 |
|-----------|-----------|------|
| **MAJOR** (x.0.0) | 하위 호환 불가 API 변경 | 메서드 서명 변경, 클래스 이동 |
| **MINOR** (x.y.0) | 하위 호환 신규 기능 추가 | 새 API 메서드, 새 어댑터 추가 |
| **PATCH** (x.y.z) | 버그 수정, 성능 개선 | 예외 처리 개선, 타임아웃 기본값 조정 |
| **SNAPSHOT** | 개발 중 버전 | 0.1.0-SNAPSHOT |

---

[Unreleased]: https://github.com/HipsterMIN/integration-sso/compare/sdk-v0.1.0-gap-patch...HEAD
[0.1.0-GAP-PATCH]: https://github.com/HipsterMIN/integration-sso/compare/sdk-v0.1.0-snapshot...sdk-v0.1.0-gap-patch
[0.1.0-SNAPSHOT]: https://github.com/HipsterMIN/integration-sso/releases/tag/sdk-v0.1.0-snapshot
