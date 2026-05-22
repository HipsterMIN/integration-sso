# onepass-be-release / onepass-release 심층 분석 보고서

> **작성일**: 2026-05-18  
> **분석 대상**: `onepass-be-release` (IdO 기반 백엔드) · `onepass-release` (React 프론트엔드)  
> **비교 기준**: `integration-sso` 멀티모듈 레포지토리 (`ido/` 모듈 + `onepass-fe/` 모듈)  
> **작성자**: AI 기술 리뷰어 (수정 없이 문서만 작성)

---

## 목차

1. [분석 개요 및 전제](#1-분석-개요-및-전제)
2. [백엔드(onepass-be-release) 분석](#2-백엔드onepass-be-release-분석)
   - 2.1 [무엇인가 — 마이그레이션 RFC의 구현체](#21-무엇인가--마이그레이션-rfc의-구현체)
   - 2.2 [integration-sso/ido 와의 구조 차이](#22-integration-ssoidо-와의-구조-차이)
   - 2.3 [잘못된 점 및 누락 사항](#23-잘못된-점-및-누락-사항)
3. [프론트엔드(onepass-release) 분석](#3-프론트엔드onepass-release-분석)
   - 3.1 [무엇인가 — 분기된 클론 레포지토리](#31-무엇인가--분기된-클론-레포지토리)
   - 3.2 [integration-sso/onepass-fe 와의 차이](#32-integration-ssoonerpass-fe-와의-차이)
   - 3.3 [잘못된 점 및 누락 사항](#33-잘못된-점-및-누락-사항)
4. [통합 관점 비교 분석](#4-통합-관점-비교-분석)
   - 4.1 [FE ↔ BE 계약(Contract) 불일치](#41-fe--be-계약contract-불일치)
   - 4.2 [보안 취약점 요약](#42-보안-취약점-요약)
   - 4.3 [운영 가시성(Observability) 격차](#43-운영-가시성observability-격차)
5. [저쪽 프로젝트가 성공하려면 — 제언](#5-저쪽-프로젝트가-성공하려면--제언)
   - 5.1 [즉시 해결해야 할 P0 이슈](#51-즉시-해결해야-할-p0-이슈)
   - 5.2 [단기(1~2스프린트) P1 과제](#52-단기12스프린트-p1-과제)
   - 5.3 [중기(3~6스프린트) P2 과제](#53-중기36스프린트-p2-과제)
   - 5.4 [전략적 제언 — 아키텍처 방향](#54-전략적-제언--아키텍처-방향)
6. [요약 테이블](#6-요약-테이블)

---

## 1. 분석 개요 및 전제

### 두 레포지토리의 정체

| 레포 | 역할 | 관계 |
|------|------|------|
| `onepass-be-release` | `identity-orchestrator`를 `integration-sso/ido` 아키텍처로 **재작성하는 과도기 산물** | `integration-sso/ido`에서 비즈니스 자산(NICE, OACX)을 포팅하되, 핵심 운영 인프라(gateway, handoff, broker, provision 등)는 **누락** |
| `onepass-release` | `integration-sso/onepass-fe`의 **포크(fork) 클론**. 코드베이스는 거의 동일하나 환경 변수 설정과 `extInstance` 보안 패치 미적용이 차이점 | `integration-sso/onepass-fe`의 develop 분기 시점 스냅샷 |

### 분석 전제

- 이 문서는 **수정 없이 현황 기록**이 목적이다.
- "저쪽 개발팀"의 의도와 진행 맥락을 최대한 존중하되, 기술적 사실 기반으로만 기술한다.
- `integration-sso`는 성숙한 운영 인프라를 갖춘 **기준 레포**로 간주한다.

---

## 2. 백엔드(onepass-be-release) 분석

### 2.1 무엇인가 — 마이그레이션 RFC의 구현체

`onepass-be-release`는 2026-05-13 작성된 RFC(docs/2026-05-13_ido-migration-rfc.md)를 따라 `identity-orchestrator` 레포지토리를 ido 아키텍처로 전환하는 **중간 단계의 구현체**다. RFC가 제시한 5단계 중 Phase 1~3에 해당하는 작업을 마친 상태로 판단된다.

**수행된 작업 (RFC 기준)**:
- ✅ Phase 1: 레포 재명명, `legacy/` 격리, `integration-sso/platform-common` 소스 vendoring
- ✅ Phase 2: Spring Boot 3.5.9 적용, MyBatis 의존성 제거, `application.yml` 정리
- ✅ Phase 3: FE 호환 컨트롤러 6개 엔드포인트 노출 (`/api/v1/auth/**`)
- ⚠️ Phase 4 (부분): NICE 슬라이스 포팅 완료, OACX 슬라이스 포팅 완료, CI 토큰 서비스 추가
- ❌ Phase 5: `legacy/` 삭제 미완료

**Spring Boot 버전 불일치 주의**: `build.gradle.kts`에 `3.5.9`가 명시되어 있으나, 이는 현재 GA 릴리즈에 존재하지 않는 버전이다. 현행 Spring Boot 최신 안정 버전은 3.4.x / 3.3.x 라인이다. 의도적인 것인지(integration-sso와 동일 버전 맞춤 시도) 오기재인지 확인이 필요하다.

### 2.2 integration-sso/ido 와의 구조 차이

아래 표는 `integration-sso/ido`에 **존재하지만 `onepass-be-release`에 없는** 핵심 영역을 정리한 것이다.

| 영역 | integration-sso/ido | onepass-be-release | 비고 |
|------|--------------------|--------------------|------|
| **기관 게이트웨이** | `gateway/` (AgencyGatewayController, HmacSignatureFilter, GatewayIdempotencyStore 등 12개 파일) | **없음** | 유관기관 inbound/outbound API 전체 부재 |
| **Handoff** | `handoff/` (HandoffService, 4종 Strategy, CallbackUrlValidator 등 11개 파일) | **없음** | Handoff Ticket 발급·검증 로직 전체 부재 |
| **OIDC 브로커** | `broker/` (Keycloak, NonOidc, ProviderRouter, InternalSigVerifier 등 25개 파일) | **없음** | Keycloak 기반 OIDC/비표준 브로커 전체 부재 |
| **기관 어드민** | `admin/` (AgencyAdminController, AgencyAdminService) | **없음** | 기관 메타 CRUD 관리 API 부재 |
| **정책 엔진** | `policy/PolicyEngine` | **없음** | 기관별 인증 레벨 정책 판단 로직 부재 |
| **Provisioning** | `provision/` (6개 파일) | `provision/` (6개 파일) — 동일 이름, 내용 복사본 | ✅ 있음 |
| **Q-IM SP 수신** | `qim/sp/` (12개 파일) | `qim/crypto/AesSharedKeyDecryptor.java` 만 존재 | ⚠️ SP 수신 API 컨트롤러·서비스 부재 |
| **Webhook Dispatcher** | `webhook/` (WebhookDispatcherService, AgencyEventQueryService 등) | 테스트 파일만 존재 (`WebhookDispatcherServiceTest.java`) | ⚠️ 구현체 없이 테스트만 존재 |
| **SLO** | `slo/` (SloController, SloService) | `metrics/SloMetrics.java` 만 존재 | ⚠️ SLO API 부재 |
| **레이트리밋** | `ratelimit/` (AgencyRateLimiter, AuthRateLimitInterceptor) | **없음** | TPS/Daily 제한 로직 전무 |
| **CrossAgency SSO** | `sso/` (CastTokenService, CrossAgencySsoController) | **없음** | 기관 간 SSO 전환 부재 |
| **개인정보 파기** | `retention/PersonalDataRetentionScheduler` | **없음** | 개인정보 파기 스케줄러 부재 |
| **암호화 키 로테이션** | `crypto/HandoffKeyRotationScheduler, KmsClient` | **없음** | 운영 키 로테이션 인프라 부재 |
| **Member Lookup** | `memberlookup/MemberLookupController` | 테스트 파일만 (`MemberLookupControllerTest.java`) | ⚠️ 구현체 없이 테스트만 존재 |
| **FE 세션** | `fe/` (FeSessionController, FeSessionServiceImpl, FeAdvisoryConsumer) | `fe/` — **동일** | ✅ RFC Phase 3에서 포팅 완료 |
| **NICE 인증** | `auth/` 내 NiceAuthService, NiceApiClient, NiceCryptoUtil | `nice/` 슬라이스로 분리 — **재작성** | ✅ RFC Phase 4 포팅 완료 |
| **OACX 전자서명** | `auth/` 내 OacxService, OacxClient | `oacx/` 슬라이스로 분리 — **재작성** | ✅ RFC Phase 4 포팅 완료 |
| **DB 스키마** | V1~V13 마이그레이션 | V1~V13 마이그레이션 — **동일** | ✅ 동일 (V13까지 포함) |

**핵심 요약**: `onepass-be-release`는 IdO의 전체 기능 중 **FE 세션 관리**, **NICE 본인인증**, **OACX 전자서명**, **CI 토큰 교환**, **DB 스키마**, **기본 인프라** 만 갖춘 상태다. 유관기관 연동을 위한 `gateway`, 인증 흐름 핵심인 `handoff`, OIDC 표준 브로커인 `broker` 등 플랫폼 핵심 기능이 모두 누락되어 있다.

**추가된 것 (integration-sso에는 없는 것)**:
- `proxy/` — Q-IM API 서버 사이드 프록시 (`ido.proxy.enabled=false` 기본값으로 비활성)
- `qim/ci/` — CI 토큰 교환 서비스 (`CiTokenService`, `CiEncryptUtil`) — integration-sso `auth/` 내 `CiTokenExchangeRequest`만 있고 서비스 없음
- `board/` — `legacy/` 에서 BoardController가 부분 이관된 상태 (RFC에서는 폐기 대상으로 명시)
- `legacy/` 디렉터리 전체 — RFC Phase 5에서 삭제 예정이나 아직 존재

### 2.3 잘못된 점 및 누락 사항

#### ❌ ISSUE-BE-1: DB 자동구성 일괄 비활성화 — 스키마와 코드 불일치

`application.yml`에 DataSource, JPA, Flyway 자동구성을 **전부 exclude** 해 놓았다.

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - ...HibernateJpaAutoConfiguration
      - ...FlywayAutoConfiguration
```

그런데 `build.gradle.kts`에는 `spring-boot-starter-data-jpa`, `flyway-core`, `flyway-database-postgresql`, `postgresql` 드라이버가 모두 `implementation` 의존성으로 선언되어 있고, V1~V13 마이그레이션 SQL도 모두 존재한다. 이는 "DB 의존성은 빌드에 포함시키되, 런타임에는 꺼 놓은" 상태로 — **의도적인 임시 조치**라고 주석에 명시되어 있으나, 이 상태로는 Flyway 마이그레이션이 실행되지 않아 DB 스키마가 초기화되지 않는다.

결과: 현재 상태에서는 ido가 DB를 전혀 사용하지 않으며, `WebhookDispatcherServiceTest`, `MemberLookupControllerTest`, `HandoffServiceImplTest` 등 DB 의존 테스트가 연결 실패로 깨진다.

#### ❌ ISSUE-BE-2: 테스트 파일이 `src/main`에 혼재

```
src/main/java/kr/go/smes/ido/broker/InternalSigVerifierTest.java
src/main/java/kr/go/smes/ido/handoff/HandoffServiceImplTest.java
src/main/java/kr/go/smes/ido/handoff/crypto/HandoffCryptoServiceTest.java
src/main/java/kr/go/smes/ido/handoff/validate/CallbackUrlValidatorTest.java
src/main/java/kr/go/smes/ido/memberlookup/MemberLookupControllerTest.java
src/main/java/kr/go/smes/ido/webhook/WebhookDispatchOutboxRelayTest.java
src/main/java/kr/go/smes/ido/webhook/WebhookDispatcherServiceTest.java
src/main/java/kr/go/smes/ido/qim/crypto/AesSharedKeyDecryptorTest.java
```

8개의 테스트 파일이 `src/main/java`에 위치해 있다. 테스트 클래스는 반드시 `src/test/java`에 있어야 한다. 이 상태에서는:
- 프로덕션 빌드에 테스트 코드가 포함되어 JAR/배포 파일 사이즈가 불필요하게 증가한다.
- 테스트 클래스에서 import하는 `spring-boot-starter-test`, `mockito` 등이 `testImplementation`으로 선언되어 있어 **컴파일 오류** 혹은 런타임 NoClassDefFoundError가 발생한다.
- 이 중 `HandoffServiceImplTest`, `InternalSigVerifierTest` 등은 테스트 대상 클래스(`HandoffService`, `InternalSigVerifier`)가 `onepass-be-release`에 **존재하지 않는다**. 즉, 구현체 없이 테스트 파일만 복사한 상태다.

#### ❌ ISSUE-BE-3: `FeApiKeyInterceptor` 보안 수준 저하

`integration-sso/ido`의 `config/HandoffAgencyKeyInterceptor.java`는 API Key를 **SHA-256 해시**로 DB에 저장하고 비교한다. 반면 `onepass-be-release`의 `FeApiKeyInterceptor`는 raw key를 환경변수에서 읽어 **평문 비교**한다 (MessageDigest.isEqual은 상수시간 비교이나 hash는 아님). 주석에도 "향후 spec 방향 정착 시 제거 예정"이라고 명시되어 있어 임시방편임을 인지하고 있다. 문제는 이 인터셉터가 **운영 환경에서 유일한 보호막**이라는 점이다. PBKDF2/bcrypt 기반 해시 검증으로 전환해야 한다.

#### ❌ ISSUE-BE-4: `legacy/` 미정리로 인한 패키지 충돌 가능성

`legacy/src/main/java/kr/ucube/integratedauth/identityorchestrator/` 패키지가 루트 `settings.gradle.kts`의 단일 프로젝트에 포함되어 있다. `legacy/build.gradle.kts`가 별도로 존재하지만 루트 `settings.gradle.kts`에 `include("legacy")`가 없다면 별도 서브프로젝트로 격리된 것이 아니다. 이 경우 `legacy/`의 Java 소스가 루트 컴파일 대상에 포함될 수 있으며, Spring Boot 4.0.6 의존성을 갖는 legacy 코드와 3.5.9를 사용하는 신규 코드 사이에 충돌이 발생한다.

#### ⚠️ ISSUE-BE-5: `board/` 도메인 잔존 — RFC 명시 폐기 대상

RFC §2 확정 사항에 "board 도메인: 폐기 (샘플 코드 명시)"라고 명확히 기재되어 있다. 그러나 `onepass-be-release`에 `ido/board/` 디렉터리(BoardController, BoardClient, BoardProperties)가 그대로 존재한다. legacy에서 신규 패키지로 이관만 된 상태다. 기술 부채가 될 수 있다.

#### ⚠️ ISSUE-BE-6: `ONEPASS_AUTH_INCOMING_KEYS` 미설정 시 전체 차단

`FeApiKeyInterceptor` 생성자:
```java
public FeApiKeyInterceptor(@Value("${onepass.auth.incoming-keys:}") List<String> incomingKeys)
```
`isValid()`는 `validKeyBytes.isEmpty()`이면 `false`를 반환한다. 즉, 환경변수 `ONEPASS_AUTH_INCOMING_KEYS`가 설정되지 않으면 **모든 FE 요청이 401 Unauthorized**가 된다. `application.yml`에 기본값이 비어 있어 개발 환경에서 별도 설정 없이 기동하면 FE가 아무것도 동작하지 않는다.

#### ⚠️ ISSUE-BE-7: `application.yml`에 AES GCM 키 기본값 플레이스홀더만 존재

`ido.crypto.handoff-aes-key: ${IDO_HANDOFF_AES_KEY:AAAAAAA...}` — 기본값이 43개의 'A'로 된 더미값이다. Handoff Ticket 암호화가 실제로 수행되는 코드가 `onepass-be-release`에는 없으므로 현재 문제는 없지만, 향후 `handoff/` 슬라이스가 이관될 때 이 기본값을 그대로 운영에 배포하면 심각한 보안 취약점이 된다.

#### ⚠️ ISSUE-BE-8: `jjwt 0.6.0` 하드코딩 — RFC-COMPAT-TEMP 미해결

주석에 `[RFC-COMPAT-TEMP]`로 표시된 임시 의존성이다. OACX-SDK-v1.3.2.jar가 `io.jsonwebtoken.impl.DefaultClaims`의 protected 생성자에 접근하는 방식이 jjwt 0.12.x에서 차단되어 0.6.0을 강제 사용한다. 0.6.0은 다수의 CVE가 보고된 **8년 된 버전**이다. OACX SDK 교체 또는 최신 jjwt로의 마이그레이션이 필요하다.

#### ⚠️ ISSUE-BE-9: `WebhookDispatcherServiceTest` / `WebhookDispatchOutboxRelayTest` — 구현체 부재

`src/main/java`에 위치한 이 테스트들은 `WebhookDispatcherService`, `WebhookDispatchOutboxRelay` 클래스를 테스트하는데, 이 구현체들이 `onepass-be-release`에 존재하지 않는다. integration-sso에서 테스트 코드만 복사되어 온 상황이다.

---

## 3. 프론트엔드(onepass-release) 분석

### 3.1 무엇인가 — 분기된 클론 레포지토리

`onepass-release`는 `integration-sso/onepass-fe/frontend`와 **소스 코드가 거의 동일**하다. `beInstance.ts`는 두 파일이 완전히 일치(identical)한다. 분기 이후 독자적인 진화가 거의 없는 상태로, 주로 `.env` 파일의 차이가 핵심이다.

**소스 차이 요약**:
- `.env` 파일: `BE_API_TARGET=https://onepass-ido-dev.smes.go.kr` (9292 포트 타겟 → 실제 개발 서버)
- `integration-sso/.env`: `BE_API_TARGET=http://localhost:8083` (로컬 표준 포트)
- `extInstance.ts`: `integration-sso`에는 존재하나 `onepass-release`에는 **없음** (중요 보안 패치 누락, 하단 참조)

### 3.2 integration-sso/onepass-fe 와의 차이

| 구분 | integration-sso/onepass-fe | onepass-release | 영향 |
|------|---------------------------|-----------------|------|
| `src/api/extInstance.ts` | **존재함** — B-5 보안 패치: EXT_API_KEY를 FE 번들에서 제거하고 ido 프록시 경유 | **없음** | 보안 패치 미적용 |
| `.env` - `BE_API_TARGET` | `http://localhost:8083` | `https://onepass-ido-dev.smes.go.kr` | 개발환경 차이 |
| `.env` - `EXT_API_KEY` | `imk-7ddb59c9deea699c55efa93b6925d6d9` (로컬용) | `imk-7ddb59c9deea699c55efa93b6925d6d9` (동일) | 동일 키 사용 |
| `.env.prod` - `SKIP_AUTH` | 미확인(동일 예상) | `SKIP_AUTH=false` | 동일 의도 |
| `docs/api-doc/` | 없음 | 있음 — API 명세 문서 3건, 프롬프트 파일 포함 | 추가됨 |
| `mockup-doc-analysis.html` | 없음 | 있음 | 추가됨 |
| `mockup-service-status.html` | 없음 | 있음 | 추가됨 |

### 3.3 잘못된 점 및 누락 사항

#### ❌ ISSUE-FE-1: `extInstance.ts` 보안 패치 누락 — EXT_API_KEY 번들 노출

`integration-sso/onepass-fe`에는 다음과 같은 B-5 보안 패치가 적용되어 있다:

```typescript
// integration-sso/onepass-fe/frontend/src/api/extInstance.ts
// B-5 보안 패치:
//   - 기존: EXT_API_ENDPOINT(Q-IM 직접) + EXT_API_KEY FE 번들 노출
//   - 변경: ido(8083) /api/ext/** forward proxy 경유
//           서버사이드에서 X-Ext-Api-Key 주입 (FE 번들 미포함)
```

`onepass-release`에는 이 `extInstance.ts`가 없다. `.env` 파일에 `EXT_API_KEY="imk-7ddb59c9deea699c55efa93b6925d6d9"`가 그대로 남아 있으며, webpack 빌드 시 이 값이 **JavaScript 번들에 포함**된다. 브라우저의 "소스 보기" 또는 DevTools에서 이 API 키를 추출할 수 있다.

이는 Q-IM 외부 API에 대한 인증 수단이 클라이언트에 노출된다는 의미로, **심각한 보안 취약점**이다.

#### ❌ ISSUE-FE-2: `.env` 및 `.env.prod`에 AES-GCM 키 평문 포함

```
AES_GCM_KEY=DL5vnfsm01CfLlycU6k8NvCDy3tpO5/MXMuqV0uMCv8=
```

이 키가 **개발 환경(`.env`)과 운영 환경(`.env.prod`) 동일**하다. webpack 빌드 시 `process.env.AES_GCM_KEY`가 번들에 인라인되어 전달되므로, 브라우저에서 CI 암호화에 사용하는 AES-256-GCM 키를 누구나 볼 수 있다. 이 키로 CI(연계정보)를 임의 암호화하여 Q-IM API를 직접 호출하는 공격이 가능하다.

**근본 원인**: CI 암호화는 브라우저에서 수행하도록 설계되었으나, 암호화 키가 번들에 포함되면 의미가 없다. CI 암호화는 서버사이드(ido)에서 수행하거나, 공개키 기반으로 설계를 바꿔야 한다.

#### ❌ ISSUE-FE-3: `.env` 파일이 git 관리 대상에 포함

`.gitignore`를 확인하지 못했으나, `.env`/`.env.dev`/`.env.prod` 파일이 레포지토리 zip에 포함되어 있다는 사실이 이미 문제다. API 키와 암호화 키가 버전 관리 시스템에 커밋되어 있으면, 해당 커밋 히스토리를 통해 영구적으로 노출된다.

#### ❌ ISSUE-FE-4: BE_API_KEY 평문 번들 포함

```
BE_API_KEY="bek-6d1816b0cd5bc5a5a1d3429063c58b99"
```

`beInstance.ts`에서:
```typescript
headers: {
    'X-BE-API-Key': process.env.BE_API_KEY || '',
},
```

FE↔BE 인증에 사용하는 `X-BE-API-Key`가 webpack 번들에 평문으로 포함된다. 개발 환경에서는 SKIP_AUTH=true로 우회되지만, 운영 환경에서 이 키가 노출되면 공격자가 BE API를 직접 호출할 수 있다.

`integration-sso/onepass-fe`도 동일한 구조를 가지고 있어 이는 양측 공통 문제이나, `onepass-release` 측에서도 해결이 필요하다.

#### ⚠️ ISSUE-FE-5: `docs/api-doc/` 내 프롬프트 파일 포함

```
docs/api-doc/PROMPT_BE_개인회원_CI토큰_프록시_프로비저닝.md
docs/api-doc/PROMPT_FE_개인회원_전환_Step5_프로비저닝.md
```

AI 프롬프트 파일이 레포지토리에 포함되어 있다. 이 파일들에는 시스템 설계 상세, API 스펙, 구현 방향이 기술되어 있어 공개 레포 운용 시 내부 설계가 노출된다.

#### ⚠️ ISSUE-FE-6: `mockup-doc-analysis.html` / `mockup-service-status.html` 미정리

개발 과정의 목업 파일이 레포지토리 루트에 존재한다. 이들이 빌드 결과물에 포함되지 않더라도, 레포지토리를 오염시키고 신규 개발자 혼선을 유발한다.

#### ⚠️ ISSUE-FE-7: `node` 엔진 요구사항 과소 명시

```json
"engines": { "node": ">=16.15.0" }
```

React 18, Webpack 5, TypeScript 5 기반 프로젝트에서 Node.js 16을 허용하는 것은 과소 명시다. Node.js 16은 EOL(2023-09-11)이 지난 버전으로, 보안 패치가 중단되었다. 최소 Node.js 20 LTS(LTS 2026-04-30)를 명시해야 한다.

---

## 4. 통합 관점 비교 분석

### 4.1 FE ↔ BE 계약(Contract) 불일치

FE(`onepass-release`)가 호출하는 API 엔드포인트와 BE(`onepass-be-release`)가 실제 노출하는 엔드포인트를 대조한다.

| FE 호출 경로 | BE 구현 여부 | 비고 |
|------------|------------|------|
| `POST /api/v1/auth/callback` | ✅ `AuthCallbackController` | 구현됨 |
| `GET /api/v1/auth/nice/phone/url` | ✅ `NiceController` | 구현됨 |
| `POST /api/v1/auth/nice/phone/result` | ✅ `NiceController` | 구현됨 |
| `POST /api/v1/auth/nice/ci-check` | ✅ `NiceController` | 구현됨 |
| `POST /api/v1/auth/oacx/access-info` | ✅ `OacxController` | 구현됨 |
| `POST /api/v1/auth/oacx/easysign` | ✅ `OacxController` | 구현됨 |
| `POST /api/v1/auth/ci-token` | ✅ `CiTokenController` | 구현됨 |
| `GET /api/v1/ext/auth-status` | ❌ **없음** | integration-sso의 `ext/ExtProxyController`에 있으나 BE에 없음 |
| `GET /api/v1/ext/auth-result/{txId}` | ❌ **없음** | 동일 |
| `POST /api/v1/ext/register/individual` | ❌ **없음** | Q-IM provisioning API, BE에 없음 |
| `POST /api/v1/ext/register/enterprise` | ❌ **없음** | 동일 |
| `GET/POST /api/v1/fe-session/**` | ✅ `FeSessionController` | 구현됨 |
| `/api/v1/ext/**` (Q-IM proxy) | ⚠️ `ProxyController` — `ido.proxy.enabled=false` | 기본값 비활성 |

**결론**: FE 회원 전환 흐름(ConversionSteps)에서 호출하는 `ext/register/**`, `ext/auth-status`, `ext/auth-result` 엔드포인트가 BE에 없어 **회원 전환 기능 전체가 동작하지 않는다**.

### 4.2 보안 취약점 요약

| 번호 | 취약점 | 위치 | 심각도 |
|------|--------|------|--------|
| S-1 | AES-GCM 키 FE 번들 포함 | `.env`, `aesGcm.ts` | 🔴 Critical |
| S-2 | EXT_API_KEY FE 번들 포함 (B-5 패치 미적용) | `.env`, `extInstance.ts 부재` | 🔴 Critical |
| S-3 | BE_API_KEY FE 번들 포함 | `.env`, `beInstance.ts` | 🟠 High |
| S-4 | API 키 git 이력 포함 | `.env` 파일들 | 🟠 High |
| S-5 | `jjwt 0.6.0` 다수 CVE | `build.gradle.kts` | 🟠 High |
| S-6 | FeApiKeyInterceptor raw key 비교 | `FeApiKeyInterceptor.java` | 🟡 Medium |
| S-7 | AES 기본값 `AAAAAAA...` | `application.yml` | 🟡 Medium (현재는 미사용) |
| S-8 | `ONEPASS_AUTH_INCOMING_KEYS` 미설정 시 전체 차단 | `application.yml` | 🟡 Medium |

### 4.3 운영 가시성(Observability) 격차

`integration-sso/ido`에 있는 다음 운영 인프라가 `onepass-be-release`에 없다:

- **`AuditLogPublisher`**: 감사 로그 Kafka 발행 — 규제 요구사항 충족 불가
- **`BrokerAuditLogService`**: 브로커 이벤트 감사 — 인증 흐름 추적 불가
- **`SloController/SloService`**: SLO 집계 API — 가용성 지표 확인 불가 (`SloMetrics`만 있음)
- **`AuthTracingAspect`**: 인증 흐름 분산 추적 — 장애 분석 어려움
- **`FeatureFlags/FeaturesEndpoint`**: Feature Flag 런타임 조회 — 점진적 배포 제어 불가

---

## 5. 저쪽 프로젝트가 성공하려면 — 제언

### 5.1 즉시 해결해야 할 P0 이슈

> P0 = 현재 또는 가까운 배포 시 서비스 장애·보안 사고를 유발하는 이슈

#### P0-1: AES-GCM 키 / EXT_API_KEY / BE_API_KEY 를 환경 변수에서 제거하고 서버 사이드로 이동

**문제**: CI 암호화에 쓰이는 `AES_GCM_KEY`, Q-IM API 인증에 쓰이는 `EXT_API_KEY`, BE 보호에 쓰이는 `BE_API_KEY`가 모두 webpack 빌드 번들에 인라인된다.

**해결 방향**:
```
CI 암호화 경로 변경:
  현재: FE(aesGcm.ts) → AES-GCM 암호화 → BE
  권장: FE(평문 CI) → BE(CiTokenService) → AES-GCM 암호화 → Q-IM
```
`beInstance.ts`에서 `AES_GCM_KEY`를 읽어 클라이언트에서 암호화하는 현재 설계를 폐기하고, CI 평문을 HTTPS를 통해 BE에 전달하면 BE가 서버 사이드에서 암호화하도록 변경한다. 이 경우 `CiTokenService`의 `CiEncryptUtil`이 서버에서 암호화를 수행하므로 키가 번들에 포함될 필요가 없다.

`EXT_API_KEY`는 `integration-sso/onepass-fe`의 `extInstance.ts` 패치처럼 서버 사이드 프록시(`/api/ext/**`)를 통해 BE에서 헤더를 주입하도록 전환한다.

`.env.*` 파일을 git 추적에서 완전히 제외(`git rm --cached`)하고, 예시 파일(`example.env`)만 유지한다.

#### P0-2: 테스트 파일을 `src/test/java`로 즉시 이동

구현체 없이 테스트만 있는 파일 8개가 `src/main/java`에 있다. 이는 빌드 오류·JAR 오염을 유발한다. 즉시 `src/test/java`로 이동하고, 구현체 없는 테스트는 `@Disabled` 처리하거나 삭제한다.

#### P0-3: Flyway 자동구성 비활성화 해제 (또는 명시적 DB 의존 제거)

`application.yml`의 autoconfigure.exclude 블록을 제거하고 DB를 활성화하거나, DB 의존성(`spring-boot-starter-data-jpa`, `flyway-*`, `postgresql`)을 `build.gradle.kts`에서도 제거하여 코드와 설정의 일관성을 확보해야 한다. 현재 상태는 "DB 코드는 있고, DB는 없는" 불완전한 상태다.

### 5.2 단기(1~2스프린트) P1 과제

#### P1-1: `legacy/` 디렉터리 정리

RFC Phase 5 작업이다. `legacy/` 서브프로젝트가 루트 컴파일에 포함되지 않도록 먼저 확인한다. 만약 포함된다면 `settings.gradle.kts`에서 명시적으로 격리하거나 즉시 삭제한다. RFC 문서에 명시된 대로 dev 환경 전체 회귀 검증 후 삭제한다.

#### P1-2: FE ↔ BE 계약 갭 해소 — `ext/` 엔드포인트 구현

회원 전환 흐름에 필요한 다음 엔드포인트를 BE에 구현해야 한다:
- `GET /api/v1/ext/auth-status`
- `GET /api/v1/ext/auth-result/{txId}`
- `POST /api/v1/ext/register/individual`
- `POST /api/v1/ext/register/enterprise`

이들은 `integration-sso/ido`의 `ext/ExtProxyController` 및 `provision/ProvisioningService`에 구현되어 있으므로, RFC Phase 4 방식으로 해당 슬라이스를 포팅한다.

#### P1-3: `FeApiKeyInterceptor` 보안 강화

raw key 평문 비교에서 PBKDF2 해시 비교로 전환한다. `integration-sso/ido`의 `config/HandoffAgencyKeyInterceptor.java`가 SHA-256 기반 비교 패턴을 이미 구현하고 있으므로 이를 참조한다. 장기적으로는 Rate Limit + Origin 검증 + CSRF state 기반 보호 체계로 전환한다.

#### P1-4: `jjwt` 버전 업그레이드 계획 수립

OACX-SDK-v1.3.2.jar 의 `jjwt 0.6.0` 강제 의존을 해소하기 위해 두 가지 경로를 검토한다:
1. OACX SDK 제공사에 최신 jjwt 호환 버전 요청
2. Reflection 을 통해 접근하는 `DefaultClaims` 코드 부분을 wrapper 클래스로 우회

어느 쪽이든 단기 내 CVE 보고서 검토 및 리스크 평가를 수행해야 한다.

#### P1-5: Node.js 엔진 요구사항 현실화

`package.json`의 `engines.node`를 `>=20.0.0`으로 변경하고 CI에서 Node.js 20 LTS를 사용하도록 통일한다.

### 5.3 중기(3~6스프린트) P2 과제

#### P2-1: 핵심 누락 슬라이스 이관 — 우선순위 순

RFC가 이미 훌륭한 이관 계획을 담고 있다. RFC를 기준으로 아래 순서로 이관을 진행할 것을 제언한다.

```
1순위 (서비스 기본 동작에 필수):
  - ext/ ExtProxyController (회원 전환 흐름)
  - provision/ ProvisioningService (회원 등록)
  - qim/sp/ QimSpReceiverController (Q-IM SP 수신 API)

2순위 (플랫폼 핵심 기능):
  - handoff/ HandoffService, 4종 Strategy (유관기관 인증 흐름)
  - gateway/ AgencyGatewayController (기관 inbound/outbound)

3순위 (보안·운영 성숙도):
  - broker/ KeycloakOidcService, NonOidcBrokerController
  - ratelimit/ AgencyRateLimiter
  - audit/ AuditLogPublisher
  
4순위 (운영 가시성):
  - slo/ SloController
  - crypto/ HandoffKeyRotationScheduler
  - retention/ PersonalDataRetentionScheduler
```

#### P2-2: `platform-common` 별도 레포 + Maven 패키지 배포

현재 `common/` 패키지가 소스 vendoring 방식으로 포함되어 있다. 두 레포(integration-sso, onepass-be-release)가 같은 `common/` 코드를 독립적으로 관리하면 분기가 심화된다. RFC 후속 작업으로 명시된 대로, `platform-common`을 별도 레포로 분리하고 사내 Maven 저장소(Nexus/Gitea Packages)에 배포하여 양측이 같은 버전을 의존하도록 한다.

```groovy
// 목표 상태
implementation("kr.go.smes:platform-common:1.0.0")
```

#### P2-3: CI/CD 파이프라인 정비

현재 `onepass-be-release`에는 CI 파이프라인 파일(`Dockerfile`은 있으나 `.github/workflows`나 `.gitlab-ci.yml` 미확인)이 확인되지 않는다. 다음을 갖춰야 한다:
- `./gradlew test` 자동 실행 (PR 머지 블로킹)
- JaCoCo 커버리지 50% 미만 빌드 실패 (`jacocoTestCoverageVerification` 활성화)
- Docker 이미지 빌드 및 레지스트리 푸시 자동화
- 운영 환경 시크릿은 Vault/KMS에서 주입, 절대 git에 커밋하지 않는 정책 강제

#### P2-4: 인수인계 포인트 — integration-sso와의 동기화 전략 수립

현재 `onepass-be-release`와 `integration-sso/ido`는 다른 git 히스토리를 가진 별도 레포지토리다. 시간이 갈수록 두 레포의 코드 기반이 벌어진다. 중기 내에 다음 중 하나를 결정해야 한다:

**Option A (권장)**: `integration-sso/ido` 를 truth source로 삼고, `onepass-be-release`에서 진행한 NICE/OACX/CI 토큰 슬라이스 작업을 `integration-sso`에 역병합한 뒤, `onepass-be-release` 레포를 `integration-sso/ido`로 수렴시킨다.

**Option B**: 두 레포를 독립적으로 유지하면서 정기적으로 `integration-sso/ido`의 패치를 체리픽한다. 인력이 부족하면 현실적으로 동기화가 끊길 가능성이 높다.

### 5.4 전략적 제언 — 아키텍처 방향

#### 제언 1: RFC 문서를 실행 계획으로 격상시켜라

`docs/2026-05-13_ido-migration-rfc.md`는 품질 높은 문서다. 작성된 5단계 계획이 현실적이며 위험·완화 항목도 충실하다. 이 RFC를 스프린트 이슈로 분해하여 팀 내 공유하고 진행 상황을 추적하라.

#### 제언 2: "현재 동작하는 것"의 범위를 명확히 정의하고 FE와 합의하라

현재 `onepass-be-release`에서 실제로 동작하는 시나리오는:
- NICE 휴대폰 본인인증 (URL 발급 → 결과 수신)
- OACX EasySign 간편인증 (access-info → easysign)
- EasySign 콜백 처리 (auth/callback)
- CI 토큰 교환 (ci-token)
- FE 세션 관리 (fe-session)

회원 전환(ConversionSteps), 기관 연동(gateway), 핸드오프(handoff) 는 **현재 동작하지 않는다**. FE팀에 이를 명확히 공유하여 QA 범위와 기대치를 맞춰야 한다.

#### 제언 3: AES-GCM 키 교체를 계획하라

현재 `.env`/`.env.dev`/`.env.prod` 모두에 동일한 `AES_GCM_KEY=DL5vnfsm...`가 존재한다. 이 키가 git에 커밋되었고 배포 파일에도 포함된 만큼, 이미 노출된 것으로 간주해야 한다. Q-IM 팀과 협의하여 키 교체 절차를 진행하고, 새 키는 서버 사이드에서만 관리해야 한다.

#### 제언 4: 하나의 담당팀이 두 레포를 모두 실질적으로 관리하고 있다면, 단일 레포 전략으로 수렴하라

`onepass-be-release`와 `integration-sso`를 모두 관리하는 인력이 같다면, 두 레포의 이중 관리 비용(PR 두 곳, 코드 리뷰 두 곳, 배포 두 곳)이 장기적으로 프로젝트를 느리게 만든다. 가능하다면 `identity-orchestrator` 레포에서 새 브랜치를 만들어 `integration-sso/ido` 전체를 이식하는 RFC 방안(Option A)이 기술 부채를 줄이는 가장 직접적인 경로다.

#### 제언 5: 테스트 커버리지를 팀 합의 기준으로 관리하라

`onepass-be-release`의 JaCoCo 설정:
```kotlin
minimum = "0.50".toBigDecimal()
```
50% 최소 커버리지를 설정했으나, 현재 테스트 파일 8개가 `src/main`에 잘못 배치되어 있고 구현체 없이 테스트만 있는 케이스도 있어 실제 커버리지 측정이 정확하지 않다. 정리 후 실제 커버리지를 측정하고, `integration-sso` 수준(74% instruction, 60% branch)을 목표로 점진적으로 올려야 한다.

---

## 6. 요약 테이블

### 백엔드(onepass-be-release)

| 항목 | 현황 | 심각도 |
|------|------|--------|
| DB 자동구성 비활성화로 스키마 미적용 | ❌ 임시 조치 — 정식화 필요 | 🔴 |
| 테스트 8개 `src/main` 잘못 배치 | ❌ 빌드 오염 · 컴파일 오류 | 🔴 |
| gateway/handoff/broker/admin/policy 전체 미구현 | ❌ 플랫폼 핵심 기능 부재 | 🔴 |
| ext/ provisioning 엔드포인트 미구현 (FE 계약 미이행) | ❌ 회원 전환 불동작 | 🔴 |
| FeApiKeyInterceptor raw key 비교 | ⚠️ 보안 강화 필요 | 🟠 |
| jjwt 0.6.0 CVE 다수 | ⚠️ 계획 수립 필요 | 🟠 |
| ONEPASS_AUTH_INCOMING_KEYS 미설정 시 서비스 불가 | ⚠️ 문서화·기본값 개선 필요 | 🟠 |
| legacy/ 미정리 | ⚠️ RFC Phase 5 이행 필요 | 🟡 |
| board/ 도메인 잔존 | ⚠️ RFC 명시 폐기 대상 미정리 | 🟡 |
| Spring Boot 3.5.9 비존재 버전 | ⚠️ 버전 확인 필요 | 🟡 |

### 프론트엔드(onepass-release)

| 항목 | 현황 | 심각도 |
|------|------|--------|
| AES-GCM 키 번들 포함 | ❌ CI 암호화 키 노출 | 🔴 |
| extInstance.ts 보안 패치 미적용 (EXT_API_KEY 노출) | ❌ Q-IM API 키 노출 | 🔴 |
| .env 파일 git 포함 (API 키 커밋 이력) | ❌ 키 영구 노출 위험 | 🔴 |
| BE_API_KEY 번들 포함 | ⚠️ FE→BE 인증 키 노출 | 🟠 |
| Node.js 16 EOL 버전 허용 | ⚠️ 보안 패치 미지원 환경 | 🟡 |
| AI 프롬프트 파일 레포 포함 | ⚠️ 내부 설계 노출 | 🟡 |
| 목업 HTML 파일 루트 잔존 | ⚠️ 정리 필요 | ⬜ |

---

> 이 문서는 분석 시점(2026-05-18) 기준이며, 실제 개발 환경 배포 및 실행을 통한 동적 분석은 수행하지 않았다. 위의 모든 지적 사항은 소스 코드 정적 분석 결과이다.
