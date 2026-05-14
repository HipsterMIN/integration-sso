# Changelog — onepass-agency-sdk

이 파일은 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/) 형식을 따른다.
버전 번호는 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 준수한다.

---

## [Unreleased]

### Changed
- 내부 개선 및 문서화 작업 진행 중

---

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
- **Automatic-Module-Name**: `kr.go.smes.sdk.agency` (JPMS 호환)
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

[Unreleased]: https://github.com/HipsterMIN/integration-sso/compare/sdk-v0.1.0...HEAD
[0.1.0-SNAPSHOT]: https://github.com/HipsterMIN/integration-sso/releases/tag/sdk-v0.1.0-snapshot
