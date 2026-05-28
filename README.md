# OnePass 통합인증 플랫폼 (integration-sso)

**중소벤처기업부 OnePass 통합인증 SSO 및 아이덴티티 관리 시스템** 프로젝트입니다. Spring Boot와 MSA 기반으로 견고한 인증 인프라를 구축하며, 유관기관 시스템의 SSO 연동을 위한 Java Agent와 SDK를 포함합니다.

> **현재 버전**: v0.8.11
> **최신 업데이트 (2026-05-27)**:
> - **`onepass-support` 모듈 추가**: 사용자 Q&A 및 CS 통합 티켓 관리 시스템 API 추가.
> - **보안 강화**: 유관기관 전환 플로우에 JWT Signed Request 기반 보안 인프라 적용 완료.
> - **문서 최신화**: 프로젝트 구조 및 모듈별 책임 상세 분석 후 README 업데이트.
>
> **문서 인덱스**: [`docs/README.md`](docs/README.md)
> **상세 아키텍처 분석**: [`docs/analysis/sso-im-readiness/00_INDEX.md`](docs/analysis/sso-im-readiness/00_INDEX.md)

---

## 목차
1. [아키텍처 개요](#아키텍처-개요)
2. [모듈 구성 및 역할](#모듈-구성-및-역할)
3. [주요 기능 및 아키텍처 심층 분석](#주요-기능-및-아키텍처-심층-분석)
    - [유관기관 SSO (v3.0)](#유관기관-sso-v30)
    - [유관기관 회원 전환](#유관기관-회원-전환)
    - [OnePass Agency Java Agent](#onepass-agency-java-agent)
    - [멀티 WAS 테스트베드](#멀티-was-테스트베드)
    - [Feature Flag 체계](#feature-flag-체계)
4. [기술 스택](#기술-스택)
5. [데이터베이스 및 Kafka](#데이터베이스-및-kafka)
6. [빠른 시작](#빠른-시작)
7. [버전 히스토리](#버전-히스토리)
8. [Wiki 및 상세 문서](#wiki-및-상세-문서)

---

## 버전 히스토리

| 버전 | PR | 주요 내용 |
|------|----|---------|
| **α-3 + onepass-support** | [#178](https://github.com/HipsterMIN/integration-sso/pull/178) (α-3) + [#179](https://github.com/HipsterMIN/integration-sso/pull/179) | **경계 영역 보안 강화 + 모듈 뼈대** — F4.3 Webhook 기본 시크릿 제거(`@PostConstruct` 부팅 가드 + `allow-empty-secret` escape hatch), F4.4 CAST URL 누출 방지(POST 자동 제출 form, 토큰 hidden field), F4.6 Q-IM 예외 구분(404→null·5xx→`IDO_QIM_UNREACHABLE`). 회귀 테스트 27건 신규. `onepass-support` 모듈 뼈대 추가. |
| **α-1 + α-2** | [#176](https://github.com/HipsterMIN/integration-sso/pull/176) → [#177](https://github.com/HipsterMIN/integration-sso/pull/177) | **KMS 안전망 + Handoff 무결성** — F5.1/F5.2 KMS 5-provider 부팅 검증 + AnyID PID 격리, F4.1/F4.5/F4.2 Handoff 무결성 (서명 검증·재생 방지·만료 정확화). |
| **v0.8.11** | [#131](https://github.com/HipsterMIN/integration-sso/pull/131) | **onepass-be-release / onepass-release 심층 분석 보고서** — BE 9건 + FE 7건 이슈 식별, 보안취약점 8건, 운영 배포 T+0~T+3 장애 시나리오, Q-Sign/Q-IM 연동 현황 상세 분석 (494줄) |
| **v0.8.10** | [#129 (MERGED)](https://github.com/HipsterMIN/integration-sso/pull/129) / [#130 (MERGED)](https://github.com/HipsterMIN/integration-sso/pull/130) | **SDK GAP-1~5 수정 + 유관기관 개발자 가이드** — GAP-1(HMAC 알고리즘 서버 정합성), GAP-2(triggerOutbound @Deprecated), GAP-3(X-Event-Type 헤더), GAP-4(X-Correlation-ID 대문자 D), GAP-5(getBodyField 헬퍼 + validateJson 강화) + 36개 테스트 통과 + 유관기관 개발자 사용 가이드(757줄) |
| **v0.8.9** | [#116](https://github.com/HipsterMIN/integration-sso/pull/116) / [#115](https://github.com/HipsterMIN/integration-sso/pull/115) | **Sprint 17 + 유관기관 전환 보안 강화** — ❌B-1 수정(`Step8 isSafeRedirectUri` 환경변수 기반) + ❌B-2 수정(`application.yml` 더미 URL → 환경변수 구조) + JWT Signed Request `POST /api/v1/conversion/init` + `PlatformErrorCode` E-CONV-601~603 신규 + GUIDE-001~004(가이드 문서 4편) + Sprint 17 `addAuthHeader()` API_KEY/HMAC/mTLS 구현 |
| **v0.8.8** | [#109](https://github.com/HipsterMIN/integration-sso/pull/109) / [#108](https://github.com/HipsterMIN/integration-sso/pull/108) / [#107](https://github.com/HipsterMIN/integration-sso/pull/107) | **QIM-OUTBOX-SPEC-001 정합화 + 전체 문서화** — V18 CHECK 제약(provisioning_outbox·gateway_inbound_audit), ProvisioningService Javadoc 갱신, wiki/ 전체 생성(ADR 12개·설계서 4개·워크스루 5개·DOCX 6개) |
| **v3.0.0** | [#82](https://github.com/HipsterMIN/integration-sso/pull/82) | **SSO 운영 보안 패치 P1~P3** — `V4__fix_social_sso.sql` UNIQUE 복합 키, `InternalApiKeyInterceptor` 구현, `HandoffController` redirectUri null 수정 |
| **v2.4.0** | [#81](https://github.com/HipsterMIN/integration-sso/pull/81) | **유관기관 SSO 완성** — Q-IM 소셜 계정 API(`find-by-social-sub` / `register-social`), GUEST 정책, HMAC fallback 완전 제거 |
| **v2.3.1** | [#80](https://github.com/HipsterMIN/integration-sso/pull/80) | **Keycloak identifierHash 수정** — SHA-256(sub) 올바른 계산, `KeycloakOidcService` P1/P2 보안 패치 |
| **v2.3.0** | [#52](https://github.com/HipsterMIN/integration-sso/pull/52) | **SLO FE 완전 연동** — `initiateSlo()` API 클라이언트, `useAuthState` 훅, `ErrorBoundary`, `InformationStep3` 실 API 연동 |
| **v2.2.1** | [#51](https://github.com/HipsterMIN/integration-sso/pull/51) | **18개 Feature Flag 체계** — `@ConditionalOnProperty` / `@Value` 가드, K8s ConfigMap 14개 환경변수 |
| **v2.2.0** | [#50](https://github.com/HipsterMIN/integration-sso/pull/50) | **프로덕션 강화** — Redisson 분산 락, Resilience4j CB+Retry, Bean Validation, OTel AOP, 감사 로그, K8s Secret/ConfigMap |
| **v2.1.0** | [#45](https://github.com/HipsterMIN/integration-sso/pull/45) | **NICE/OACX 본인인증 ido BFF 완전 이식** — 6개 API, Redis 세션/토큰 캐시, PBKDF2+AES-256-GCM, 32개 테스트 |
| v2.0.0 | [#39](https://github.com/HipsterMIN/integration-sso/pull/39) | AES 키 로테이션 + 모니터링 인프라 |
| v1.9.9 | [#38](https://github.com/HipsterMIN/integration-sso/pull/38) | UUID v7 테스트 27개 + Webhook 33개 |
| v1.9.5 | [#35](https://github.com/HipsterMIN/integration-sso/pull/35) | UUID v4 → v7 전체 교체 (RFC 9562) |
| v1.9.4 | [#33](https://github.com/HipsterMIN/integration-sso/pull/33) | P2 운영 고도화 + P3 배포 준비 완전 구현 |
| v1.9.3 | [#32](https://github.com/HipsterMIN/integration-sso/pull/32) | SLO 완전 구현 + 개인정보 파기 스케줄러 + FE 인증 기반 |
| v1.9.2 | [#31](https://github.com/HipsterMIN/integration-sso/pull/31) | P0 보안 결함 완전 제거 + 테스트 기반 구축 |



---

## 아키텍처 개요

> **⚠️ 설계 원칙**: 모든 유관기관(기관 시스템)은 **외부망**에 위치합니다.  
> 기관은 내부 Kafka·DB에 직접 접근하지 않으며, **IdO 공개 API(HTTPS)** 만을 통해 통신합니다.

```
══════════════════════════════════════════════════════════════════════════════════
  외부망 (External Network)
══════════════════════════════════════════════════════════════════════════════════

  ┌──────────────────────────────────────────────────────────────────────────┐
  │   최종 사용자 (브라우저)                                                   │
  │                                                                          │
  │   [개발] React dev :3000 → webpack proxy → ido:8083                     │
  │   [운영] Nginx :3001 → /api/** → ido:8083 (same-origin 보안)            │
  └──────────────┬───────────────────────────────────────────────────────────┘
                 │ HTTPS / /api/v1/**
                 │  ├── /fe-session/**          (FE 세션 관리)
                 │  ├── /slo/**                 (SLO 로그아웃)
                 │  ├── /handoff/**             (Handoff 발급/검증)
                 │  ├── /auth/**                (본인인증 BFF S7-T2)
                 │  └── /broker/**              (OIDC 브로커 ★SSO)
                 │
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  유관기관 시스템 (외부망)              외부 인증 공급자                     │
  │  agency-stub :8084                   Keycloak :8080 (OIDC IdP)          │
  │                                      NICE IDO 서버                       │
  │                                      OACX SDK v1.3.2                    │
  └──────────────────────────┬───────────────────────────────────────────────┘
                             │ HTTPS (공개 API만)
══════════════════════════  ╪  ═══════════════════════════════════════════════
  내부망 (Internal Network)
══════════════════════════  ╪  ═══════════════════════════════════════════════
                            ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │  ido :8083  정책 오케스트레이터 + FE BFF                                  │
  │                                                                          │
  │  [FE BFF]               [SLO]               [기관향 공개 API]            │
  │  feSessionId 쿠키       POST /slo/initiate   /handoff/issue              │
  │  ReturnUrl 검증         feSession 삭제        /handoff/verify             │
  │                         Webhook Outbox 적재   /agency/events              │
  │  [본인인증 BFF]                                                           │
  │  NICE 휴대폰 인증        [★SSO 브로커 v3.0]                               │
  │  OACX 간편서명           GET  /broker/authorize  Keycloak 인가 URL 생성   │
  │  Redis 세션 캐시         GET  /broker/callback   OIDC 콜백 처리           │
  │  PBKDF2+AES-256-GCM     sub → Q-IM qimUserId 조회/등록                   │
  │                         FeSession 생성 → Handoff 발급 준비                │
  │  [18개 Feature Flag]                                                     │
  │  @ConditionalOnProperty / @Value 가드                                    │
  └──────────────────┬───────────────────────────────────────────────────────┘
                     │ HTTP (내부망 전용, X-Internal-Api-Key 검증)
         ┌───────────┴───────────┐
         ▼                       ▼
  ┌─────────────┐       ┌─────────────────────────────────────┐
  │ q-sign:8081 │       │  q-im:8082  (MariaDB)               │
  │  인증 SoR    │       │  식별 SoR                           │
  │  Keycloak   │       │  회원 원장 · CI AES-256-GCM v{n}    │
  │  OIDC 브로커 │       │  ★SSO: find-by-social-sub           │
  │  SLO 전파   │       │  ★SSO: register-social              │
  └──────┬──────┘       │  InternalApiKeyInterceptor (P2)     │
         │              └──────────────────┬──────────────────┘
         │  Outbox                         │  Outbox
         └──────────────┬──────────────────┘
                        ▼
    ┌─────────────────────────────────────┐
    │           Apache Kafka              │
    │  qsign.auth.events                  │
    │  ido.handoff.events                 │
    │  qim.user.events (Compacted)        │
    │  platform.session.advisory          │
    │  platform.audit.log                 │
    │  + 각 토픽별 .dlq 토픽               │
    └─────────────────────────────────────┘

    ┌─────────────────────────────────────┐
    │     모니터링 스택                    │
    │  Prometheus :9090                   │
    │  Grafana    :3000                   │
    │  Loki       :3100                   │
    └─────────────────────────────────────┘
```

---

## 유관기관 SSO (v3.0)

> **Sprint 11 완료** (v2.4.0 → v3.0.0) — Keycloak 소셜 로그인 후 유관기관 Handoff까지 전 과정 완성.

### SSO 전체 흐름

```
사용자 브라우저
    │
    │ ① GET /api/v1/broker/authorize?provider=kakao&agencyCode=SMBA
    ▼
IdO (KeycloakCallbackController)
    │  state/nonce Redis 저장 (CSRF 방어)
    │  Keycloak Authorization URL 생성 (kc_idp_hint=kakao)
    │
    │ ② 302 redirect → Keycloak
    ▼
Keycloak :8080
    │  카카오 OIDC 연동 → 사용자 동의
    │
    │ ③ GET /api/v1/broker/callback?code=...&state=...
    ▼
IdO (KeycloakOidcService.handleCallback)
    │  state 검증 (1회 소비, CSRF 방어)
    │  code → token 교환 (Keycloak Token Endpoint)
    │  id_token JWKS 서명 검증 + nonce 검증
    │  SHA-256(sub) = identifierHash 생성
    │
    │ ④ POST /api/v1/internal/users/find-by-social-sub
    ▼                    {sub, providerCode}
Q-IM (UserController)
    │  findByIdentifierHashAndProviderCode(SHA-256(sub), providerCode)
    │  → 기존 사용자: qimUserId 반환 (200)
    │  → 신규 사용자: 404 반환
    │
    │ ⑤ [신규 사용자] POST /api/v1/internal/users/register-social
    ▼                    {sub, providerCode, identifierHash}
Q-IM (UserController.registerSocialUser)
    │  qim_user + auth_mean_mapping(identifierHash, providerCode) 생성
    │  UNIQUE(identifier_hash, provider_code) 복합 키 보장 (V4 마이그레이션)
    │  Kafka Outbox SOCIAL_USER_REGISTERED 이벤트 발행
    │  qimUserId 반환 (201)
    │
    │ ⑥ FeSession 생성 (실제 qimUserId 사용)
    ▼
IdO
    │  POST /api/v1/handoff/issue
    │  → feSession에서 서버 측 qimUserId 추출 (P1 보안)
    │  → redirectUri 화이트리스트 검증 (P3 수정)
    │  → PolicyEngine: Q-IM DI 조회
    │      DI 있음 → APPROVED + agencySubjectId
    │      DI 없음 → GUEST (HMAC fallback 없음)
    │
    │ ⑦ POST /agency/entry?ticketId=...
    ▼
agency-stub (AgencyEntryController)
    │  IdO Handoff verify (Resilience4j CB + Retry)
    │  APPROVED → 기관 세션(AGSID 쿠키) 발급
    │  GUEST    → 200 + 회원 가입 안내 메시지
    ▼
완료
```

### 소셜 계정 식별 전략

| 항목 | 내용 |
|------|------|
| **식별 키** | `identifierHash` = SHA-256(sub) + `providerCode` 복합 |
| **저장 테이블** | `auth_mean_mapping` (qim DB) |
| **UNIQUE 제약** | `UNIQUE(identifier_hash, provider_code)` — V4 마이그레이션 |
| **PII 원칙** | sub 원문 미저장, SHA-256 단방향 해시만 보관 |
| **경합 방어** | `registerSocialUser()` 내 재조회로 동시 요청 중복 방지 |

### 새로 구현된 파일 목록 (Sprint 11)

| 파일 | 구분 | 내용 |
|------|------|------|
| `q-im/.../api/UserController.java` | 수정 | `find-by-social-sub` + `register-social` 엔드포인트 추가 |
| `q-im/.../api/dto/SocialRegisterRequest.java` | 신규 | 소셜 등록 요청 DTO |
| `q-im/.../repository/QimUserJpaRepository.java` | 수정 | `findByIdentifierHashAndProviderCode()` JPQL 쿼리 추가 |
| `q-im/.../config/InternalApiKeyInterceptor.java` | **신규** | `X-Internal-Api-Key` 상수 시간 비교 검증 인터셉터 (P2) |
| `q-im/.../config/QimWebMvcConfig.java` | **신규** | `/api/v1/internal/**` 인터셉터 등록 (P2) |
| `q-im/.../db/migration/V4__fix_social_sso.sql` | **신규** | `uq_identifier_hash` DROP → 복합 UNIQUE 추가 (P1) |
| `q-im/.../resources/application.yml` | 수정 | `qim.security.internal-api-key` 설정 추가 (P2) |
| `ido/.../infrastructure/QimClientImpl.java` | 수정 | `findBySocialSub()` + `registerSocialUser()` HTTP 클라이언트 |
| `ido/.../broker/keycloak/KeycloakOidcService.java` | 수정 | `resolveQimUserIdFromSub()` — Q-IM 소셜 API 연동 |
| `ido/.../api/HandoffController.java` | 수정 | `.redirectUri(req.getCallbackUrl())` 누락 수정 (P3) |
| `ido/.../policy/PolicyEngineImpl.java` | 수정 | HMAC fallback 완전 제거, `tryResolveDi()` + GUEST 정책 |
| `platform-common/.../HandoffPayload.java` | 수정 | `HandoffState.GUEST` 추가 |
| `agency-stub/.../api/AgencyEntryController.java` | 수정 | `case GUEST` 분기 처리 추가 |

### 운영 배포 필수 환경변수

```bash
# IdO 서버
IDO_BROKER_MODE=keycloak              # 필수 — 기본값 qsign, 이 설정 없으면 SSO 경로 비활성
IDO_QIM_INTERNAL_API_KEY=<32자+>      # Q-IM 내부 API 인증키

# Q-IM 서버
QIM_INTERNAL_API_KEY=<동일 키>        # IDO_QIM_INTERNAL_API_KEY와 반드시 동일

# Keycloak 연동 (application.yml ido.keycloak.* 항목)
KEYCLOAK_BASE_URL=https://keycloak.example.com
KEYCLOAK_REALM=onepass
KEYCLOAK_CLIENT_ID=ido-client
KEYCLOAK_CLIENT_SECRET=<client secret>
KEYCLOAK_REDIRECT_URI=https://ido.example.com/api/v1/broker/callback
```

> **키 생성 방법**: `openssl rand -hex 32`

---

## 유관기관 회원 전환 (v0.8.9 신규)

> **Sprint 17+ 완료** — 유관기관이 자체 로그인 후 OnePass 회원 전환 플로우로 연결하는 전체 보안 인프라 구현.  
> 68개 유관기관 대상 범용 설계 (단일 기관 하드코딩 제거).

### 버그 수정 이력

| 분류 | 버그 ID | 증상 | 수정 내용 |
|------|---------|------|-----------|
| **❌ 틀린 것** | **B-1** | `Step8.tsx isSafeRedirectUri()` — `*.smes.go.kr` 하드코딩으로 68개 기관 중 smes.go.kr 외 도메인 전부 차단 | 환경변수 `REACT_APP_REDIRECT_ALLOWED_ORIGINS` 기반 + 와일드카드 지원으로 교체 |
| **❌ 틀린 것** | **B-2** | `application.yml allowed-return-urls` — PoC 더미 URL만 존재, 실제 기관 URL 전무 → returnUrl 검증 전부 실패 | `${ALLOWED_URL_SMES:...}` 환경변수 구조로 변경, 68개 기관 주입 가능 |

### JWT Signed Request 보안 인프라 (신규)

```
기관 서버 (외부망)
    │  signed_request = JWT HS256 { sub, mbrId, redirectUri, returnClient, userType, iat, exp, jti }
    │  기관 API Key 로 서명
    │
    │ 302 redirect → https://onepass.smes.go.kr/conversion/step1?signed_request=...&agencyCode=BIZINFO_001
    ▼
FE Step1.tsx
    │  POST /api/v1/conversion/init { signedRequest, agencyCode }
    ▼
IdO ConversionInitController
    │  ① agency_meta 조회 + active 체크  → AGENCY_NOT_FOUND(E-AGENCY-307)
    │  ② K8s Secret → API Key 조회       → AGENCY_KEY_INVALID(E-AGENCY-303)
    │  ③ JWT HS256 서명 검증 + sub=agencyCode 확인  → CONVERSION_SIGNATURE_INVALID(E-CONV-601)
    │  ④ JWT exp 검증 (5분 이내)          → CONVERSION_REQUEST_EXPIRED(E-CONV-602)
    │  ⑤ redirectUri → callback_whitelist DB 검증  → AGENCY_CALLBACK_BLOCKED(E-AGENCY-306)
    │  ⑥ ConversionSession → Redis TTL 30분 저장
    ▼
FE → 200 { conversion_session_id, user_type, expires_at }
    │  이후 step2~8: conversionSessionId 참조 (mbrId, redirectUri FE URL 미노출)
```

### Open Redirect 방어 3-레이어

| 레이어 | 위치 | 검증 방법 |
|--------|------|-----------|
| **L1** | FE `Step8.tsx isSafeRedirectUri()` | `REACT_APP_REDIRECT_ALLOWED_ORIGINS` 환경변수 와일드카드 매칭 |
| **L2** | BE `FeSessionServiceImpl.isValidReturnUrl()` | `allowed-return-urls` YAML 목록 (환경변수 `${ALLOWED_URL_*}`) |
| **L3** | BE `CallbackUrlValidator.validate()` | `agency_meta.callback_whitelist` DB JSONB — 완전일치/와일드카드/접두사 3단계 |

### 운영 배포 필수 환경변수 (유관기관 전환)

```bash
# FE (.env.production)
REACT_APP_REDIRECT_ALLOWED_ORIGINS=https://www.bizinfo.go.kr,https://www.sbiz.or.kr,...  # 68개 기관 URL

# IdO 서버 (K8s ConfigMap/Secret)
ALLOWED_URL_SMES=https://www.smes.go.kr
ALLOWED_URL_BIZINFO=https://www.bizinfo.go.kr
ALLOWED_URL_MSS=https://www.mss.go.kr
ALLOWED_URL_SBIZ=https://www.sbiz.or.kr
# ... 나머지 기관 (IDO_FE_ALLOWED_RETURN_URLS_EXTRA 로 추가 주입 가능)

# 기관별 API Key (K8s Secret)
SECRETS_AGENCY_BIZINFO_001_API_KEY=<기관별 HS256 서명 키, openssl rand -hex 32>
SECRETS_AGENCY_SMBA_001_API_KEY=<...>
# ... 나머지 기관
```

> **가이드 문서**: [`wiki/guide/01-agency-conversion-url-flow.md`](./wiki/guide/01-agency-conversion-url-flow.md)  
> **보안 대안 상세**: [`wiki/guide/02-conversion-param-security.md`](./wiki/guide/02-conversion-param-security.md)  
> **기관 오픈 샘플**: [`wiki/guide/03-conversion-launch-sample.md`](./wiki/guide/03-conversion-launch-sample.md)  
> **데이터 흐름 다이어그램**: [`wiki/guide/04-conversion-data-flow-diagram.md`](./wiki/guide/04-conversion-data-flow-diagram.md)

---

## Sprint 10: SLO FE 완성 + FE 기반

> **Sprint 10 완료** (v2.3.0) — 백엔드 SLO는 Sprint 2/7에서 완성됐으나 FE가 미연동 상태였던 문제 해결.  
> FE 전역 인증 상태 관리 기반 구축 및 InformationStep3 실 API 연동 포함.

### 완성된 기능 목록 (S10-1 ~ S10-6)

| 태스크 | 파일 | 내용 |
|--------|------|------|
| **S10-1** | `api/feSession.ts` (신규) | `initiateSlo()` — `POST /api/v1/slo/initiate`, `checkFeSession()` |
| **S10-2** | `api/utils.ts` (수정) | `clearLocalAuthState()` 분리, `Logout()` SLO 통합, `LogoutAsync()` 추가 |
| **S10-3** | `hooks/useAuthState.ts` (신규) | Redux 기반 인증 상태 + SLO 로그아웃 편의 훅 |
| **S10-4** | `components/ErrorBoundary/index.tsx` (신규) | React native class ErrorBoundary (fallback/onError props) |
| **S10-5** | `components/MypageSideNav/index.tsx` (수정) | `useAuthState` 훅 + 로그아웃 버튼 UI |
| **S10-6** | `api/ext/members.ts` (수정) | `updateMember(§5.3)`, `updateEnterprise(§5.4)` PATCH API 추가 |
| **S10-6** | `types/api/ext/members.ts` (수정) | `UpdateMemberRequest`, `UpdateEnterpriseRequest` 타입 정의 |
| **S10-6** | `pages/Mypage/pages/InformationStep3.tsx` (수정) | `handleSubmit` 실제 API 호출 연동 |

### SLO best-effort 정책

```typescript
// api/utils.ts — 로그아웃 흐름
export const Logout = (): void => {
    // SLO API 실패해도 로컬 정리 진행 (best-effort)
    initiateSlo().catch(() => {});
    clearLocalAuthState();          // 6개 localStorage + Redux 5개 dispatch
    history.push(ROUTES.LOGIN);
};
```

**SLO 서버 흐름** (`POST /api/v1/slo/initiate`):
1. `feSessionId` 쿠키 파싱 → Redis 세션 삭제
2. `sloService.executeSlo()` → Keycloak `end_session_endpoint` 호출
3. 기관 로그아웃 Webhook Outbox 적재
4. 감사 로그 기록 (`platform.audit.log`)
5. 204 No Content 반환 (feSessionId 쿠키 Max-Age=0)

---

## S7-T2: NICE/OACX 본인인증 통합

> **Sprint 7 Task 2 완료** — onepass-be 헥사고날 아키텍처에서 ido 평탄화 계층 구조로 이식. 32개 단위 테스트 통과.

### 본인인증 API 엔드포인트 (6개)

| 메서드 | 경로 | 용도 |
|--------|------|------|
| `GET` | `/api/v1/auth/nice/phone/url` | NICE 휴대폰 인증 URL 발급 |
| `POST` | `/api/v1/auth/nice/phone/result` | NICE 인증 결과 조회 (CI 미포함) |
| `POST` | `/api/v1/auth/nice/ci-check` | CI 기반 회원 확인 |
| `POST` | `/api/v1/auth/oacx/access-info` | OACX 접근정보 발급 |
| `POST` | `/api/v1/auth/oacx/easysign` | OACX 간편서명 결과 처리 |
| `POST` | `/api/v1/auth/callback` | 기업 간편인증 콜백 |

> 전체 명세: [`docs/api-auth-spec.md`](docs/api-auth-spec.md)

---

## Feature Flag 체계

> **Sprint 9 FF 완료** — 18개 Feature Flag으로 환경별 기능 On/Off 완전 제어.  
> 전체 가이드: [`docs/FEATURE_FLAGS.md`](docs/FEATURE_FLAGS.md)

### 빠른 참조표

| 플래그 | 환경변수 | 로컬 기본 | 운영 기본 | 제어 방식 |
|--------|---------|---------|---------|---------|
| F-01 IP Auth RL | `IDO_AUTH_RL_ENABLED` | `false` | `true` | `@Value` + guard |
| F-02 기관별 RL | `IDO_RATE_LIMIT_ENABLED` | `false` | `true` | `@Value` + guard |
| F-03 감사 로그 Kafka | `IDO_AUDIT_KAFKA_ENABLED` | `false` | `true` | `@Value` + guard |
| F-04 감사 로그 DB | `IDO_AUDIT_DB_ENABLED` | `false` | `true` | `@Value` + guard |
| F-05 OTel AOP | `IDO_TRACING_AUTH_ASPECT_ENABLED` | `false` | `true` | `@ConditionalOnProperty` |
| F-08 Redisson | `IDO_REDISSON_ENABLED` | `false` | `true` | `@ConditionalOnProperty` |
| F-10 보안 헤더 | `IDO_SECURITY_HEADERS_ENABLED` | `false` | `true` | `@ConditionalOnProperty` |
| F-11 파기 스케줄러 | `IDO_RETENTION_ENABLED` | `false` | `true` | `@Value` + guard |
| F-13 Outbox Relay | `IDO_OUTBOX_RELAY_ENABLED` | `false` | `true` | `@Value` + guard |
| F-14 Webhook Relay | `IDO_WEBHOOK_RELAY_ENABLED` | `false` | `true` | `@Value` + guard |

---

## 모듈 구성 및 역할

OnePass 플랫폼은 명확한 책임 분리 원칙에 따라 여러 마이크로서비스 및 라이브러리 모듈로 구성됩니다.

| 모듈 | 포트 | 주요 역할 및 책임 |
| :--- | :--- | :--- |
| **`platform-common`** | - | 공통 도메인, 이벤트, 에러 코드, 유틸리티 등 프로젝트 전반에서 사용되는 핵심 공통 라이브러리. |
| **`q-sign`** | 8081 | **인증 SoR(Source of Record)**. Keycloak을 이용한 OIDC 브로커링, JWT 검증, SLO 전파 등 인증의 핵심 상태를 관리. |
| **`q-im`** | 8082 | **식별 SoR(Source of Record)**. 사용자 식별 정보(CI/DI), 회원 원장, 소셜 계정 매핑 등 식별 정보의 상태를 관리. |
| **`ido`** | 8083 | **정책 오케스트레이터 및 BFF(Backend for Frontend)**. Handoff 발급/검증, 기관별 정책 적용, Webhook 전송, 본인인증 처리 등 복합 비즈니스 로직을 조정. |
| **`onepass-support`** | - | **고객 지원 도메인 API**. 사용자 대상 FAQ/Q&A 기능과 CS 상담원용 통합 티켓 관리 백오피스 시스템을 제공. |
| **`onepass-fe`** | 3000 | **프론트엔드**. React 기반의 사용자 인터페이스(SPA). |
| **`agency-stub`** | 8084 | **유관기관 시뮬레이터**. SSO 연동 개발 및 E2E 테스트를 위한 유관기관 시스템의 Mock 서버. |
| **`onepass-agency-sdk`**| - | **유관기관 연동 SDK**. 유관기관이 OnePass SSO를 쉽게 연동할 수 있도록 제공하는 Java 클라이언트 라이브러리. |
| **`onepass-agent`** | - | **OnePass Java Agent**. 소스 코드 수정 없이 JVM 옵션만으로 SSO를 적용할 수 있는 독립 fat-JAR 에이전트. |
| **`onepass-agent-testbed`**| - | **멀티 WAS 테스트베드**. Docker Compose를 사용하여 7개 이상의 WAS 환경에서 Java Agent의 동시 검증을 자동화. |
| **`infra`** | - | Docker, Kubernetes, 모니터링 스택(Prometheus, Grafana, Loki) 등 인프라 구성 관리. |

---

## 기술 스택

### 백엔드 공통

| 기술 | 버전 | 적용 범위 |
|------|------|----------|
| Java | **21 LTS** | 전 모듈 |
| Spring Boot | **3.5.9** | q-sign, q-im, ido, agency-stub |
| Gradle | **9.5.0** | 멀티모듈 빌드 |
| Spring Data JPA | BOM 관리 | q-sign, q-im, ido |
| Spring Kafka | BOM 관리 | 전 서비스 |
| Spring Data Redis | BOM 관리 | ido, q-im |
| Spring WebFlux | BOM 관리 | ido (WebClient 전용, Tomcat 유지) |
| Flyway | **11.8.0** | DB 마이그레이션 |
| Resilience4j | **2.2.0** | Circuit Breaker, Retry |
| JJWT | **0.12.6** | JWT 서명 검증 (Keycloak id_token) |
| Micrometer | BOM 관리 | Prometheus 메트릭 |
| BouncyCastle | **1.78.1** | NICE 암호화 (AES-256-GCM, PBKDF2) |
| OACX SDK | **v1.3.2** | OACX 전자서명 중계모듈 (로컬 libs/ JAR) |
| JUnit 5 + Mockito | BOM 관리 | 단위 테스트 (q-im 219개 통과 + 30 skipped) |

### 프론트엔드 (`onepass-fe/frontend/`)

| 기술 | 버전 | 비고 |
|------|------|------|
| React | 18.3 | |
| TypeScript | 5.4 | |
| Ant Design | 5.18 | |
| TanStack Query | v5 | |
| Redux (legacy_createStore + thunk) | — | `useAuthState` 훅으로 편의 인터페이스 제공 |
| Axios | — | `withCredentials: true` (feSessionId 쿠키) |
| Sentry | — | 최상위 ErrorBoundary (index.tsx), 페이지 수준은 자체 ErrorBoundary |

### 인프라

| 서비스 | 이미지 | 용도 |
|--------|--------|------|
| PostgreSQL | `postgres:16-alpine` | q-sign, ido 스키마 |
| MariaDB | `mariadb:11.4` | q-im 전용 |
| Redis | `redis:7.2-alpine` | 세션, PKCE, 캐시, Rate Limit, NICE 토큰/세션 |
| Kafka | `confluentinc/cp-kafka:7.6.1` | 이벤트 버스 |
| Keycloak | `quay.io/keycloak/keycloak:24` | OIDC IdP 브로커 (SSO) |
| Prometheus | `prom/prometheus:v2.51.2` | 메트릭 수집 |
| Grafana | `grafana/grafana-oss:10.4.2` | 대시보드 |
| Loki | `grafana/loki:2.9.6` | 로그 집계 |
| Promtail | `grafana/promtail:2.9.6` | 컨테이너 로그 수집 |

---

## 모듈 구성

```
integration-sso/
├── platform-common/
│   └── src/main/java/kr/go/smes/common/
│       ├── domain/           # AuthResult, HandoffPayload(+GUEST), HandoffTicket
│       ├── error/            # PlatformErrorCode
│       ├── event/            # AuthEvent, HandoffEvent, AuditLogEvent
│       └── util/             # UuidV7, ApiKeyHashValidator
│
├── q-sign/                   # 인증 SoR (포트 8081)
│   └── src/main/java/kr/go/smes/qsign/
│       ├── broker/           # Keycloak OIDC 브로커
│       ├── kafka/            # Outbox + 멱등 컨슈머
│       ├── pkce/             # RFC 7636 PKCE
│       └── slo/              # SLO Keycloak end_session 전파
│
├── q-im/                     # 식별 SoR (포트 8082, MariaDB)
│   └── src/main/java/kr/go/smes/qim/
│       ├── api/
│       │   ├── UserController.java          # ★SSO: find-by-social-sub, register-social
│       │   ├── dto/SocialRegisterRequest.java  # ★SSO: 소셜 등록 DTO
│       │   └── QimStatusController.java     # 상태 조회 (공개)
│       ├── config/
│       │   ├── InternalApiKeyInterceptor.java  # ★P2: X-Internal-Api-Key 검증
│       │   └── QimWebMvcConfig.java            # ★P2: /api/v1/internal/** 인터셉터 등록
│       ├── crypto/           # CI AES-256-GCM v{n}.{iv}.{ct}
│       ├── identity/         # DI HMAC-SHA256
│       ├── infrastructure/jpa/repository/
│       │   └── QimUserJpaRepository.java    # ★SSO: findByIdentifierHashAndProviderCode()
│       ├── outbox/           # Outbox + Snapshot
│       └── user/             # 회원 등록·조회·상태
│   └── src/main/resources/db/migration/
│       ├── V1__create_schema.sql
│       ├── V2__add_idempotent_consumer.sql
│       ├── V3__add_ci_encryption_and_status_history.sql
│       └── V4__fix_social_sso.sql            # ★P1: UNIQUE 복합 키 수정
│
├── ido/                      # 정책 오케스트레이터 + FE BFF (포트 8083)
│   ├── libs/
│   │   └── OACX-SDK-v1.3.2.jar
│   └── src/main/java/kr/go/smes/ido/
│       ├── auth/             # NICE/OACX 본인인증 BFF (S7-T2)
│       ├── broker/
│       │   └── keycloak/
│       │       ├── KeycloakCallbackController.java  # ★SSO: GET /broker/callback
│       │       ├── KeycloakOidcService.java          # ★SSO: OIDC 콜백 전 처리
│       │       ├── KeycloakJwksVerifier.java         # ★SSO: id_token 서명 검증
│       │       └── KeycloakProperties.java           # ★SSO: Keycloak 설정 바인딩
│       ├── api/
│       │   └── HandoffController.java       # ★P3: redirectUri null 수정
│       ├── infrastructure/
│       │   └── QimClientImpl.java           # ★SSO: findBySocialSub(), registerSocialUser()
│       ├── policy/
│       │   └── PolicyEngineImpl.java        # ★SSO: HMAC fallback 제거, GUEST 정책
│       ├── slo/              # SLO API (Sprint 2/7/10)
│       ├── admin/            # 기관 Admin API
│       ├── config/           # Feature Flag, Rate Limit, TraceparentFilter
│       ├── crypto/           # KeyVersionRegistry + HandoffKeyRotationScheduler
│       ├── fe/               # FE 세션 관리
│       ├── handoff/          # HandoffStrategy 패턴
│       ├── kafka/            # 이벤트 컨슈머
│       ├── ratelimit/        # Redis Lua 슬라이딩 윈도우
│       └── webhook/          # Webhook Push + Outbox Relay
│
├── agency-stub/              # 기관 시뮬레이터 (포트 8084)
│   └── src/main/java/kr/go/smes/agency/
│       └── api/AgencyEntryController.java   # ★SSO: GUEST case 분기 추가
│
├── onepass-fe/               # React SPA
│   └── frontend/src/
│       ├── api/
│       │   ├── feSession.ts      # SLO API 클라이언트
│       │   ├── utils.ts          # Logout() SLO 통합
│       │   └── ext/members.ts    # updateMember/updateEnterprise
│       ├── hooks/useAuthState.ts # 인증 상태 중앙 관리 훅
│       ├── components/
│       │   ├── ErrorBoundary/    # React class ErrorBoundary
│       │   └── MypageSideNav/    # 로그아웃 버튼
│       └── pages/Mypage/pages/InformationStep3.tsx
│
├── onepass-agent/                # 🆕 OnePass Agency Java Agent (독립 fat-JAR)
│   └── src/main/java/kr/go/smes/agent/
│       ├── core/OnePassAgentMain.java      # JVM 진입점 (premain/agentmain)
│       ├── config/AgentConfig.java         # 외부 설정 로더/검증기
│       ├── was/
│       │   ├── WasType.java               # 27개 WAS 유형 enum
│       │   └── WasDetector.java           # 6단계 WAS 자동 감지
│       ├── weaving/
│       │   ├── WeavingStrategyFactory.java # WasType → 전략 팩토리
│       │   ├── TomcatVersionedWeavingStrategy.java  # Tomcat 5~11 버전별
│       │   ├── LegacyJavassistWeavingStrategy.java  # JBoss/WebLogic/WebSphere 레거시
│       │   ├── GenericFilterWeavingStrategy.java    # Fallback (javax+jakarta)
│       │   ├── engine/JavassistWeavingEngine.java   # JDK 1.3+ 호환 위빙 엔진
│       │   └── jeus/                       # JEUS 버전별 전용 전략 4개
│       └── http/OnePassHttpClient.java     # 순수 JDK HttpURLConnection
│
├── onepass-agent-testbed/        # 🆕 멀티 WAS Docker Compose 테스트베드
│   ├── docker/
│   │   ├── docker-compose.yml             # 7개 WAS 컨테이너 정의
│   │   ├── Dockerfile.tomcat8/9/10        # Tomcat 버전별
│   │   ├── Dockerfile.wildfly             # WildFly (jakarta)
│   │   ├── Dockerfile.jetty               # Jetty
│   │   └── Dockerfile.undertow/springboot
│   ├── apps/
│   │   ├── mock-onepass-server/           # 순수 JDK Mock SSO 서버
│   │   └── sample-webapp/                 # 테스트 서블릿 (HealthServlet, ProtectedServlet)
│   ├── config/onepass-agent.properties    # Agent 설정 템플릿
│   ├── scripts/
│   │   ├── run-all-tests.sh               # 7개 WAS 자동화 검증
│   │   └── replace-agent.sh              # Agent JAR 교체 헬퍼
│   └── README.md                          # 테스트베드 사용 가이드
│
└── infra/
    ├── docker/
    │   ├── docker-compose.yml
    │   └── docker-compose.monitoring.yml
    ├── k8s/
    │   └── configmaps/
    │       └── ido-configmap.yml     # Feature Flag 환경변수 14개
    ├── monitoring/
    │   ├── prometheus/
    │   ├── grafana/
    │   ├── loki/
    │   └── promtail/
    └── k6/                          # 부하 테스트
```

---

## 🆕 OnePass Agency Java Agent

> **모듈**: `onepass-agent/` | **아티팩트**: `onepass-agent-{version}-all.jar` (~10MB fat-JAR)  
> **목적**: 유관기관 WAS에 **소스 코드 수정 없이** OnePass SSO를 적용하는 자바 에이전트  
> **JDK 지원**: JDK 1.5(JEUS 4/5) ~ JDK 21+(Tomcat 11, WildFly 28+)  
> **참고 문서**: [통합 가이드](./docs/onepass-agent-integration-guide.md) | [아키텍처](./docs/internal/architecture/onepass-agent-architecture.md) | [개발자 레퍼런스](./docs/internal/development/onepass-agent-developer-reference.md)

### Agent 핵심 특징

| 특징 | 설명 |
|------|------|
| **코드 수정 없음** | `-javaagent:` JVM 옵션만으로 SSO 적용 |
| **27개 WAS 지원** | JEUS 4~21, Tomcat 5~11, JBoss, WildFly, WebLogic, WebSphere, GlassFish, Resin, Jetty, Undertow |
| **이중 위빙 엔진** | JDK 1.5~7: Javassist 3.x / JDK 8+: byte-buddy 1.17.8 자동 선택 |
| **6단계 WAS 감지** | 클래스패스→시스템프로퍼티→환경변수→JVM인수→파일시스템→오버라이드 |
| **Fail-Open 정책** | 위빙 실패 시 WAS 기동 계속 (서비스 가용성 우선) |
| **javax/jakarta 이중** | Servlet 5.0 전환 WAS(Tomcat 10+, WildFly 27+)에서 자동 분기 |

### 빠른 설치 (Tomcat 9 예시)

```bash
# 1. Agent JAR 빌드
./gradlew :onepass-agent:agentJar
# → onepass-agent/build/libs/onepass-agent-0.1.0-SNAPSHOT-all.jar

# 2. 설정 파일 작성
cat > /opt/onepass/onepass-agent.properties << 'EOF'
onepass.agent.endpoint=https://onepass.go.kr
onepass.agent.api-key=<행정안전부 발급 API Key>
onepass.agent.enabled=true
EOF

# 3. Tomcat JVM 옵션 추가 (catalina.sh 또는 setenv.sh)
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/onepass/onepass-agent-0.1.0-SNAPSHOT-all.jar=config=/opt/onepass/onepass-agent.properties"

# 4. Tomcat 재시작 → 로그 확인
# [OnePassAgent] WAS 유형 감지: Tomcat 9.x (JDK 8+, Servlet 4.0)
# [OnePassAgent] 위빙 설치 완료: TomcatVersionedWeaving (TOMCAT_9)
```

### WAS별 지원 매트릭스

| WAS | 버전 | JDK | Servlet | 위빙 엔진 | WasType |
|-----|------|-----|---------|----------|---------|
| **JEUS** | 4/5 | 1.4~1.5 | 2.3~2.4 | Javassist | `JEUS_LEGACY` |
| **JEUS** | 6 | 1.5~1.7 | 2.5 | Javassist | `JEUS_6` |
| **JEUS** | 7/8 | 1.6~1.8 | 3.0~3.1 | JDK 분기 | `JEUS_7`, `JEUS_8` |
| **JEUS** | 8.5 | 8/11 | 4.0 | byte-buddy | `JEUS_8_5` |
| **JEUS** | 9/21 | 11+ | 5.0+ | byte-buddy+jakarta | `JEUS_9_PLUS` |
| **Tomcat** | 5.x/6.x | 5~6 | 2.4~2.5 | Javassist | `TOMCAT_LEGACY` |
| **Tomcat** | 7.x | 7 | 3.0 | Javassist/BB | `TOMCAT_7` |
| **Tomcat** | 8.x/8.5 | 8 | 3.1 | byte-buddy | `TOMCAT_8` |
| **Tomcat** | 9.x | 8+ | 4.0 | byte-buddy | `TOMCAT_9` |
| **Tomcat** | 10+/11 | 11+ | 5.0+ | byte-buddy+jakarta | `TOMCAT_10_PLUS` |
| **JBoss** | EAP 5/6 | 6~7 | 2.x~3.0 | Javassist | `JBOSS_LEGACY` |
| **JBoss** | EAP 7 | 8+ | 3.1 | byte-buddy | `JBOSS` |
| **WildFly** | 27+ | 11+ | 5.0+ | byte-buddy+jakarta | `WILDFLY` |
| **WebLogic** | 10.x/11g | 6~7 | 2.5~3.0 | Javassist | `WEBLOGIC_LEGACY` |
| **WebLogic** | 12c/14c | 8+ | 3.1~4.0 | byte-buddy | `WEBLOGIC` |
| **WebSphere** | 7/8 | 6~7 | 2.5~3.0 | Javassist | `WEBSPHERE_LEGACY` |
| **WebSphere** | Liberty | 8+ | 3.1~6.0 | byte-buddy | `WEBSPHERE` |
| **GlassFish** | 3/4/Payara | 7~8 | 3.0~3.1 | byte-buddy | `GLASSFISH` |
| **GlassFish** | 6+/Payara 6+ | 11+ | 5.0+ | byte-buddy+jakarta | `GLASSFISH_JAKARTA` |
| **Resin** | 3/4 | 6+ | 2.4~3.1 | byte-buddy | `RESIN` |
| **Jetty** | 7/8 | 7 | 3.0 | Javassist | `JETTY_LEGACY` |
| **Jetty** | 9~11 | 8~11 | 3.1~4.0 | byte-buddy | `JETTY` |
| **Jetty** | 12+ | 17+ | 6.0+ | byte-buddy+jakarta | `JETTY_JAKARTA` |
| **Undertow** | Standalone | 8+ | 3.x~5.x | byte-buddy | `UNDERTOW` |
| **기타** | — | 8+ | — | byte-buddy (Fallback) | `UNKNOWN` |

### WAS 수동 지정

WAS 자동 감지가 실패하는 경우:
```bash
# JVM 옵션에 추가
-Donepass.was.type=TOMCAT_9

# 지원 값: JEUS_LEGACY, JEUS_6, JEUS_7, JEUS_8, JEUS_8_5, JEUS_9_PLUS
#          TOMCAT_LEGACY, TOMCAT_7, TOMCAT_8, TOMCAT_9, TOMCAT_10_PLUS
#          JBOSS_LEGACY, JBOSS, WILDFLY, WEBLOGIC_LEGACY, WEBLOGIC
#          WEBSPHERE_LEGACY, WEBSPHERE, GLASSFISH, GLASSFISH_JAKARTA
#          RESIN, JETTY_LEGACY, JETTY, JETTY_JAKARTA, UNDERTOW, UNKNOWN
```

### Agent 관련 문서

| 문서 | 경로 | 설명 |
|------|------|------|
| 통합 가이드 | [`docs/onepass-agent-integration-guide.md`](./docs/onepass-agent-integration-guide.md) | 유관기관 개발자/관리자용 설치 가이드 |
| 워크스루 | [`docs/onepass-agent-walkthrough.md`](./docs/onepass-agent-walkthrough.md) | 단계별 설치·검증 워크스루 |
| 트러블슈팅 | [`docs/onepass-agent-troubleshooting.md`](./docs/onepass-agent-troubleshooting.md) | 문제 증상별 진단·해결 |
| 문서 인덱스 | [`docs/onepass-agent-index.md`](./docs/onepass-agent-index.md) | Agent 전체 문서 목차 |
| 아키텍처 설계서 | [`docs/internal/architecture/onepass-agent-architecture.md`](./docs/internal/architecture/onepass-agent-architecture.md) | 내부 아키텍처, 위빙 설계, 클래스로더 격리 |
| 개발자 레퍼런스 | [`docs/internal/development/onepass-agent-developer-reference.md`](./docs/internal/development/onepass-agent-developer-reference.md) | 새 WAS 추가, Javassist/byte-buddy 코딩 가이드 |

---

## 🆕 멀티 WAS 테스트베드

> **위치**: `onepass-agent-testbed/` | **목적**: Docker Compose로 7개 WAS에 Agent 동시 검증  
> **참고**: [테스트베드 README](./onepass-agent-testbed/README.md)

### 테스트베드 구성

```
onepass-agent-testbed/
├── docker/docker-compose.yml    ← 7개 WAS + Mock OnePass Server
├── apps/
│   ├── mock-onepass-server/     ← 순수 JDK HttpServer 기반 Mock SSO
│   └── sample-webapp/           ← 테스트 서블릿 (Health, Protected, Public)
├── config/onepass-agent.properties
└── scripts/
    ├── run-all-tests.sh         ← 7개 WAS 자동화 검증 (기동확인/위빙/인증/차단)
    └── replace-agent.sh         ← Agent JAR 핫 교체
```

### WAS 컨테이너 포트 매핑

| WAS | 포트 | JDK | Servlet | WasType |
|-----|------|-----|---------|---------|
| Tomcat 8 | 8081 | JDK 8 | 3.1 | `TOMCAT_8` |
| Tomcat 9 | 8082 | JDK 11 | 4.0 | `TOMCAT_9` |
| Tomcat 10 | 8083 | JDK 17 | 5.0 | `TOMCAT_10_PLUS` |
| WildFly 27 | 8084 | JDK 17 | 6.0 | `WILDFLY` |
| Jetty 11 | 8085 | JDK 11 | 4.0 | `JETTY` |
| Spring Boot (Undertow) | 8086 | JDK 17 | 5.0 | `UNDERTOW` |
| Spring Boot (Tomcat) | 8087 | JDK 17 | 5.0 | `TOMCAT_10_PLUS` |
| Mock OnePass Server | 9090 | JDK 11 | — | — |

### 빠른 시작

```bash
# 1. Agent JAR 빌드 및 테스트베드에 복사
./gradlew :onepass-agent:agentJar
cd onepass-agent-testbed && ./scripts/replace-agent.sh

# 2. 전체 WAS 기동
cd docker && docker compose up -d

# 3. 자동화 테스트 실행
cd .. && ./scripts/run-all-tests.sh

# 4. 특정 WAS만 테스트
./scripts/run-all-tests.sh --only tomcat9

# 5. 테스트 후 정리
cd docker && docker compose down
```

---

## 데이터베이스 구성

| 모듈 | DB 엔진 | 스키마 | 최신 Flyway 버전 |
|------|---------|--------|----------------|
| Q-Sign | PostgreSQL 16 | `qsign` | **V5** — auth_method 컬럼 |
| IdO | PostgreSQL 16 | `ido` | **V13** — agency pattern scenarios seed |
| Q-IM | MariaDB 11.4 | `qim` | **V4** — 소셜 SSO UNIQUE 복합 키 (`★신규`) |
| **onepass-support** | PostgreSQL 16 | `support` | **V3** (CS 티켓 스키마) |
| agency-stub | PostgreSQL 16 | `agency_stub` | **V2** — webhook + api_key |

---

## Kafka 토픽

| 토픽 | 파티션 | 보존 | 생산자 | 소비자 |
|------|--------|------|--------|--------|
| `qsign.auth.events` | 12 | 1h | Q-Sign, **IdO(SSO)** | IdO |
| `ido.handoff.events` | 12 | 1y | IdO | IdO → Webhook |
| `platform.session.advisory` | 12 | 24h | IdO | IdO |
| `platform.audit.log` | 12 | 2y | IdO | 감사 시스템 |
| `qim.user.events` | 6 | Compacted | Q-IM | IdO, Q-Sign |
| `qim.user.snapshot` | 6 | Compacted | Q-IM | (확장 예정) |
| `qim.sp.member.events` | 6 | 30d | IdO | IdO |
| *.dlq / *.dlt | 3~6 | 7d | 에러핸들러 | 운영 |

> **SSO 추가**: `qsign.auth.events` 토픽에 `AUTH_COMPLETED` 이벤트를 IdO(KeycloakOidcService)가 직접 발행 (Strategy B — Q-Sign 우회 없음).

---

## 보안 체계

| 보안 항목 | 구현 방식 | 상태 |
|----------|---------|------|
| 기관 API 키 인증 | PBKDF2-HMAC-SHA256 + 상수시간 비교 | ✅ Sprint 1 |
| AES 키 버전 로테이션 | `v{n}.{iv}.{ct}` 포맷, 90일 주기, Redis 분산 락 | ✅ Sprint 5 |
| Handoff Ticket 암호화 | AES-256-GCM + 버전 접두사 | ✅ Sprint 5 |
| CI 암호화 (Q-IM) | AES-256-GCM v{n}.{iv}.{ct} | ✅ v1.8.0 |
| 내부 서비스 서명 (X-Internal-Sig) | HMAC-SHA256 ±60s 검증 | ✅ Sprint 1 |
| Webhook 서명 | HMAC-SHA256 + ±5분 타임스탬프 | ✅ 완료 |
| PKCE (RFC 7636) | S256 code_challenge | ✅ 완료 |
| W3C traceparent 전파 | TraceparentFilter | ✅ 완료 |
| Rate Limiter (기관별) | Redis Lua 슬라이딩 윈도우 | ✅ 완료 |
| Rate Limiter (IP Auth) | `IDO_AUTH_RL_ENABLED` Feature Flag | ✅ Sprint 9 FF |
| Provider 단위 CB | Resilience4j 동적 생성 | ✅ 완료 |
| SLO Keycloak 전파 | end_session_endpoint 연동 | ✅ Sprint 2 |
| SLO FE 완전 연동 | `POST /api/v1/slo/initiate` FE 호출 | ✅ Sprint 10 |
| 개인정보 파기 스케줄러 | GDPR §17 준수, 탈퇴 후 90일 | ✅ Sprint 2 |
| NICE 암호화 | PBKDF2(512bit)→HMAC-SHA256→AES-256-GCM | ✅ Sprint 7 |
| CI FE 미반환 (Q3=B) | `@JsonInclude(NON_NULL)` | ✅ Sprint 7 |
| Bean Validation | `@Valid`, `@NotBlank`, `@Size` | ✅ Sprint 9 |
| 보안 응답 헤더 | CSP, HSTS, X-Frame-Options | ✅ Sprint 9 FF |
| **Q-IM InternalApiKeyInterceptor** | `MessageDigest.isEqual()` 상수 시간 비교 | ✅ **v3.0.0 P2** |
| **Keycloak id_token JWKS 서명 검증** | `KeycloakJwksVerifier` nonce·audience 검증 | ✅ **v3.0.0** |
| **Handoff redirectUri 화이트리스트** | `callbackUrlValidator.validate(redirectUri)` null 수정 | ✅ **v3.0.0 P3** |
| **소셜 sub PII 비보관** | SHA-256 단방향 해시만 저장 | ✅ **v3.0.0** |
| **CSRF 방어 (SSO)** | state + nonce Redis 1회 소비 | ✅ **v3.0.0** |
| **GDPR V6 완전 준수** | `deletePii()` guardian 컬럼 NULL 처리 (Fix 2) | ✅ **v3.1.0** |
| **GuardianConsent 입력 검증** | `@Valid @NotBlank` + `MethodArgumentNotValidException` E-IM-400 (Fix 3) | ✅ **v3.1.0** |
| **기업회원 중복 전환 방지** | `existsById()` + `existsByBizRegNo()` 선행 체크 (Fix 4) | ✅ **v3.1.0** |
| **correlationId 추적 정확성** | `getStatus()` 시그니처 수정 — qimUserId 혼용 버그 제거 (Fix 5) | ✅ **v3.1.0** |
| **Open Redirect 방어 3-레이어 (B-1 수정)** | `Step8 isSafeRedirectUri()` 환경변수 기반 + 와일드카드 — `*.smes.go.kr` 하드코딩 제거 | ✅ **v0.8.9** |
| **returnUrl 화이트리스트 정비 (B-2 수정)** | `allowed-return-urls` 더미 URL → `${ALLOWED_URL_*}` 환경변수 구조 — 68개 기관 지원 | ✅ **v0.8.9** |
| **JWT Signed Request (전환 보안)** | `POST /api/v1/conversion/init` — HMAC-SHA256 서명 검증 + ConversionSession Redis 보관 | ✅ **v0.8.9** |
| **E-CONV 에러코드** | `CONVERSION_SIGNATURE_INVALID(E-CONV-601)`, `CONVERSION_REQUEST_EXPIRED(E-CONV-602)`, `CONVERSION_SESSION_NOT_FOUND(E-CONV-603)` | ✅ **v0.8.9** |
| **addAuthHeader() 3-mode** | API_KEY / HMAC-SHA256 / mTLS 조건부 헤더 주입 — Sprint 17 BLOCKER 해소 | ✅ **v0.8.9** |

---

## Flyway 마이그레이션 현황

| 모듈 | 버전 | 내용 |
|------|------|------|
| ido | V1 | 기본 스키마 |
| ido | V5 | handoff_ticket 테이블 |
| ido | V9 | crypto_key_registry (AES 키 버전 메타데이터) |
| ido | V10 | auth_result 확장, provider_routing |
| ido | V13 | agency pattern scenarios seed |
| q-sign | V5 | auth_method 컬럼 추가 |
| q-im | V1 | 기본 스키마 (auth_mean_mapping, qim_user 등) |
| q-im | V3 | CI 암호화 키 버전, user_status_history |
| **q-im** | **V4** | **`★신규` 소셜 SSO UNIQUE 복합 키** — `uq_identifier_hash` DROP → `uq_identifier_hash_provider(identifier_hash, provider_code)` + 커버링 인덱스 |
| agency-stub | V2 | webhook + api_key |

---

## 테스트 현황

| 모듈 | 테스트 수 | 최근 추가 |
|------|---------|---------|
| `ido` | **202개** | Sprint 7: NICE/OACX 32개 |
| `platform-common` | **59개** | UUID v7 27개 |
| `q-sign` | **23개** | SLO + PKCE |
| `q-im` | **219개** (+ 30 skipped) | **Sprint 12**: isMinor 3종(Fix 7) + S8 4종 + S9 6종 통합(Fix 8) |
| **`onepass-agency-sdk`** | **36개** | **v0.8.10** SDK GAP-1~5 수정 (HMAC 알고리즘, X-Event-Type, X-Correlation-ID, getBodyField, validateJson) |
| **합계** | **539개 + 30 skipped** | — |

### Q-IM 테스트 상세 (v3.1.0)

| 테스트 분류 | 개수 | 내용 |
|------------|------|------|
| 단위 테스트 (서비스/리포지토리) | ~189개 | 기존 단위 테스트 |
| Fix 7: isMinor 저장 검증 | 3개 | `minorBirthYear`, `adultBirthYear`, `nullBirthYear` |
| Fix 8: S8 보호자 동의 시나리오 | 4개 | S8-1 보호자 동의 성공, S8-2 미성년자 아님, S8-3 이미 동의, S8-4 보호자 없음 |
| Fix 8: S9 기업회원 전환 시나리오 | 6개 | S9-1 전환 성공, S9-2 중복 qimUserId, S9-3 사업자번호 중복, S9-4 미성년자 전환 불가, S9-5 사업자번호 정규화, S9-6 GDPR 탈퇴 후 기업회원 데이터 검증 |
| Skipped (DOCKER_UNAVAILABLE) | 30개 | Testcontainers 통합 테스트 (Docker 미사용 환경) |

> **SSO 통합 테스트**: `KeycloakOidcService` + `QimClientImpl` 소셜 경로에 대한 단위 테스트 미작성 (잔여 과제).  
> **FE 테스트**: `ConversionLayout`, `KrdsModal` 단위 테스트 존재 (jest 환경 미완비로 tsc standalone에서 오류 — Vite 빌드 환경에서는 정상)  
> **Testcontainers 실행**: Docker 환경에서 `DOCKER_UNAVAILABLE` 미설정 시 30개 통합 테스트 자동 실행

---

## 모니터링 인프라

| 서비스 | 접속 | 계정 |
|--------|------|------|
| Grafana | http://localhost:3000 | admin / admin |
| Prometheus | http://localhost:9090 | — |
| Loki | http://localhost:3100 | — |
| Kafka UI | http://localhost:8090 | — |
| Redis Insight | http://localhost:5540 | — |
| pgAdmin | http://localhost:5050 | admin@onepass.go.kr / admin |

### Grafana 대시보드 (자동 프로비저닝)

- **OnePass Overview** — 서비스별 RPS, P95 지연, 에러율, JVM 힙
- **Kafka Lag Monitor** — 컨슈머 그룹별 lag, offset 진행
- **Redis Stats** — 메모리, 연결수, 명령 처리량

---

## 빠른 시작

### 1. 인프라 기동

```bash
# 기본 인프라 (PostgreSQL, MariaDB, Redis, Kafka, Keycloak)
docker compose -f infra/docker/docker-compose.yml up -d

# 모니터링 스택 (Prometheus, Grafana, Loki, Promtail)
docker compose -f infra/docker/docker-compose.monitoring.yml up -d
```

### 2. 백엔드 실행

```bash
# 전체 빌드 (테스트 제외)
./gradlew build -x test

# Docker 미사용 환경 (Feature Flag 비활성화)
export DOCKER_UNAVAILABLE=true

# 모듈별 실행
./gradlew :q-im:bootRun       # 식별 서비스 :8082
./gradlew :q-sign:bootRun     # 인증 서비스 :8081
./gradlew :ido:bootRun        # 정책 오케스트레이터 :8083
./gradlew :agency-stub:bootRun # 기관 시뮬레이터 :8084
```

### 3. 프론트엔드 실행

```bash
cd onepass-fe/frontend
npm install
npm run dev    # :3000 (webpack proxy → ido:8083)
```

### 4. 로컬 환경변수

```yaml
# ido/src/main/resources/application-local.yml
ido:
  broker:
    mode: keycloak             # ★SSO 활성화 (기본값 qsign)
  auth-rl:
    enabled: false             # IP Rate Limit OFF (로컬)
  audit:
    kafka-enabled: false       # Kafka 감사 로그 OFF
    db-enabled: false          # DB 감사 로그 OFF
  redisson:
    enabled: false             # Redisson 분산 락 OFF (Redis 없을 때)
  security-headers:
    enabled: false             # CSP 헤더 OFF (FE 개발)
  outbox-relay:
    enabled: false
  webhook-relay:
    enabled: false
```

```bash
# 환경변수 (로컬 개발 — docker-compose.yml에 설정 권장)
IDO_BROKER_MODE=keycloak
IDO_QIM_INTERNAL_API_KEY=local-dev-key-change-in-production
QIM_INTERNAL_API_KEY=local-dev-key-change-in-production
```

---

## 접속 URL

| 서비스 | URL | 비고 |
|--------|-----|------|
| onepass-fe (개발) | http://localhost:3000 | webpack dev server |
| onepass-fe (운영) | http://localhost:3001 | Nginx |
| ido API | http://localhost:8083 | FE BFF + 기관 API |
| q-sign API | http://localhost:8081 | 인증 SoR |
| q-im API | http://localhost:8082 | 식별 SoR |
| agency-stub | http://localhost:8084 | 기관 시뮬레이터 |
| Keycloak | http://localhost:8080 | OIDC IdP (SSO) |
| Grafana | http://localhost:3000 | 모니터링 |
| Kafka UI | http://localhost:8090 | 이벤트 브라우저 |

---

## 개발 환경 설정

### 필수 도구

| 도구 | 버전 | 용도 |
|------|------|------|
| JDK | 21 LTS | 백엔드 |
| Gradle | 9.5.0 (wrapper) | 빌드 |
| Node.js | 20+ | 프론트엔드 |
| npm | 10+ | 패키지 관리 |
| Docker Desktop | 4.x | 인프라 |
| Docker Compose | v2 | 스택 관리 |

### IntelliJ IDEA 설정

```
File → Project Structure → SDKs → JDK 21 (Temurin/Corretto)
Build, Execution, Deployment → Build Tools → Gradle → JVM: Project SDK
Annotation Processors: 활성화 (Lombok)
```

### VS Code (프론트엔드)

```json
// .vscode/settings.json
{
  "typescript.tsdk": "node_modules/typescript/lib",
  "editor.formatOnSave": true,
  "eslint.workingDirectories": ["onepass-fe/frontend"]
}
```

---

## 전체 로드맵 & 개발 플랜

### 잔여 작업

| 우선순위 | 항목 | 담당 | 비고 |
|---------|------|------|------|
| **P1** | SSO 단위/통합 테스트 작성 | IdO/Q-IM BE | `KeycloakOidcService`, `QimClientImpl` 소셜 경로 |
| **P1** | 통합 테스트 (Spring Boot Test + Testcontainers) | 전 팀 | 현재 0개 |
| **P1** | ConversionInit JTI 재사용 방지 (Redis 블랙리스트) | IdO BE | JWT replay attack 방어 — 단기 보안 과제 |
| **P1** | `agency_meta.callback_whitelist` DB 등록 | 운영/DevOps | 68개 기관 callback URL 등록 |
| **P2** | `REACT_APP_REDIRECT_ALLOWED_ORIGINS` FE 환경변수 설정 | 운영/FE | 68개 기관 URL 설정 (B-1 수정 후 필수) |
| **P2** | K8s ConfigMap `ALLOWED_URL_*` 실제 기관 URL 주입 | 운영/DevOps | B-2 수정 후 필수 |
| **P2** | K8s Secret `SECRETS_AGENCY_{CODE}_API_KEY` 기관별 등록 | 운영/DevOps | JWT Signed Request 인증 키 |
| **P2** | Keycloak realm 구성 문서화 | DevOps | social IDP 설정, ACR mapper |
| **P2** | agency-stub Kafka 직접 구독 → 공개 API 전환 | IdO BE | 망 분리 원칙 |
| **P2** | CSR(관리자 UI) 미구현 기관 관리 화면 | FE | — |
| **P2** | DLQ 전략 구현 (`KafkaConsumerConfig`) | IdO BE | GAP-IDO-09 |
| **P3** | FE E2E 테스트 (Cypress/Playwright) | FE | — |
| **P3** | k6 부하 테스트 고도화 | DevOps | SSO 경로 + 전환 플로우 포함 |

---

## 운영 배포 전 필수 확인사항

> **v0.8.9 기준 체크리스트** — 아래 항목 완료 후 운영 배포 가능.  
> 🔴 = 운영 배포 **불가** 차단 항목 | 🟡 = 배포 후 기능 제한 | ✅ = 코드 완료 (환경설정만 남음)

### 코드 완료 항목 (환경변수/DB 설정만 필요)

| # | 항목 | 비고 |
|---|------|------|
| ✅1 | B-1 수정: `Step8 isSafeRedirectUri()` 환경변수 기반 | `REACT_APP_REDIRECT_ALLOWED_ORIGINS` 미설정 시 FE에서 모든 redirect 차단됨 |
| ✅2 | B-2 수정: `allowed-return-urls` 환경변수 구조 | `ALLOWED_URL_*` 미설정 시 각 기관 returnUrl 검증 실패 → 전환 완료 불가 |
| ✅3 | `POST /api/v1/conversion/init` ConversionInit API | 기관별 K8s Secret API Key 미등록 시 전환 시작 불가 |
| ✅4 | `addAuthHeader()` 3-mode 구현 | 프로비저닝 연동 정상 |

### 운영 수동 작업 필수 항목

| # | 항목 | 담당 | 위험도 |
|---|------|------|--------|
| 🔴1 | **FE `.env.production`**: `REACT_APP_REDIRECT_ALLOWED_ORIGINS` 68개 기관 URL | FE/DevOps | 미설정 시 전환 완료 차단 |
| 🔴2 | **K8s ConfigMap**: `ALLOWED_URL_SMES`, `ALLOWED_URL_BIZINFO` 등 실제 기관 URL | DevOps | 미설정 시 returnUrl 검증 실패 |
| 🔴3 | **K8s Secret**: `SECRETS_AGENCY_{CODE}_API_KEY` 기관별 등록 | DevOps/보안 | 미설정 시 JWT 서명 검증 불가 |
| 🔴4 | **DB**: `agency_meta.callback_whitelist` 기관별 콜백 URL 등록 | DevOps/DB | 미등록 시 redirectUri 3-레이어 검증 실패 |
| 🟡5 | application.yml 로컬 개발 URL 운영 환경변수 격리 | DevOps | `AGENCY_STUB_URL`, `REACT_DEV_URL` 기본값이 localhost → 운영 환경변수 덮어쓰기 필수 |

### 보수적 심층 분석 결과 (2026-05-16)

| 항목 | 판정 | 근거 |
|------|------|------|
| `Instant.EPOCH` fallback (iat=null) | ✅ **안전** | EPOCH + 5분 ≪ now() → 항상 CONVERSION_REQUEST_EXPIRED 발생 |
| `RedisConfig` JavaTimeModule | ✅ **정상** | `Instant` 직렬화 지원 확인 — `ConversionSession` Redis 저장/조회 정상 |
| apiKey 로그 노출 | ✅ **없음** | `credentialRef` 경로만 로그, apiKey 원문 미출력 |
| ConversionSession Redis 키 충돌 | ✅ **극미** | UUID v4 랜덤 — 운영 수준 안전 |
| userType null claim | ✅ **허용 설계** | 기관이 지정 안 하면 null → FE 사용자 선택 (ConversionSession null 허용) |
| JTI 재사용 방지 | ⚠️ **미구현** | JWT replay attack 가능 — 단기 P1 과제로 등재 |
| localhost URL 운영 혼입 | ⚠️ **주의** | `AGENCY_STUB_URL`, `REACT_DEV_URL` 환경변수 미설정 시 localhost 기본값 → 운영 환경변수 주입 필수 |
| ConversionInitController 경로 충돌 | ✅ **없음** | `/api/v1/conversion` 신규 경로, 기존 경로와 중복 없음 |

---

## 팀별 개발 가이드

> 팀별 상세 가이드는 `docs/development/` 디렉토리 참조

| 팀 | 가이드 문서 | 내용 요약 |
|----|-----------|---------|
| **IdO 백엔드** | [`docs/internal/development/guide-backend-ido-2026-05-12.md`](docs/internal/development/guide-backend-ido-2026-05-12.md) | (v3.0.0) Feature Flag 운영, SSO 브로커 설정, SLO API, NICE/OACX BFF, AES 키 로테이션 |
| **Q-IM 백엔드** | [`docs/internal/development/guide-backend-qim-2026-05-12.md`](docs/internal/development/guide-backend-qim-2026-05-12.md) | (v3.0.0) CI 암호화, 소셜 SSO API, InternalApiKeyInterceptor, 회원 원장 API, 파기 스케줄러 |
| **프론트엔드** | [`docs/internal/development/guide-frontend-2026-05-12.md`](docs/internal/development/guide-frontend-2026-05-12.md) | (v3.0.0) SLO 연동, useAuthState 훅, ErrorBoundary, 회원정보 수정, API 클라이언트 패턴 |
| **인프라/DevOps** | [`docs/internal/development/guide-infra-2026-05-12.md`](docs/internal/development/guide-infra-2026-05-12.md) | (v3.0.0) Docker Compose, K8s ConfigMap, Keycloak realm 구성, Feature Flag 운영 |
| **Q-IM 상세** | [`docs/qim-development-guide.md`](docs/qim-development-guide.md) | Q-IM 전체 아키텍처 + 운영 피드백 |

---

## 코딩 컨벤션

### 백엔드 (Java/Spring)

```java
// 1. 패키지 구조: 기능별 평탄화 (헥사고날 미적용)
kr.go.smes.ido.{기능}/
    {기능}Controller.java
    {기능}Service.java
    {기능}ServiceImpl.java
    dto/

// 2. 빈 네이밍: 인터페이스 기반
@Service
public class SloServiceImpl implements SloService { ... }

// 3. Feature Flag Guard 패턴
@Value("${ido.slo.enabled:true}")
private boolean enabled;

public void execute() {
    if (!enabled) {
        log.debug("[SLO] 기능 비활성화 상태");
        return;
    }
    // 실제 로직
}

// 4. Lombok 필수
@Slf4j
@RequiredArgsConstructor
public class SloServiceImpl { ... }

// 5. 보안 비교는 상수 시간으로
// ❌ 금지
if (expected.equals(actual)) { ... }
// ✅ 권장
if (MessageDigest.isEqual(expected.getBytes(), actual.getBytes())) { ... }
```

### 프론트엔드 (TypeScript/React)

```typescript
// 1. API 클라이언트 반환 타입: SuccessResponse<T> | ErrorResponse
export const updateMember = async (
    mbrNo: string,
    body: UpdateMemberRequest,
): Promise<SuccessResponse<MemberResponse> | ErrorResponse> => { ... };

// 2. 에러 체크 패턴
const result = await updateMember(mbrNo, body);
if (result.error !== null) {
    // 에러 처리
    return;
}
// 성공 처리: result.payload

// 3. 로그아웃은 반드시 Logout() 또는 LogoutAsync() 사용 (SLO 포함)
import { Logout } from 'api/utils';

// 4. 인증 상태는 useAuthState() 훅 사용
const { isLoggedIn, user, logout } = useAuthState();

// 5. ErrorBoundary 적용 (페이지 수준)
<ErrorBoundary fallback={<ErrorPage />}>
    <MyPage />
</ErrorBoundary>
```

---

## 문서 디렉토리

> 전체 문서 카탈로그: [`docs/README.md`](docs/README.md) (2026-05-22 정리)

```
docs/
├── README.md                       # 문서 디렉토리 안내 (이 README와 함께 갱신)
│
├── analysis/sso-im-readiness/      # ★최신 — Phase 1-7 심층 분석 + Sprint α-1/α-2/α-3 결과
│   ├── 00_INDEX.md                 #   전체 색인
│   ├── 01_architecture_recon.md   ─ 07_risk_matrix_roadmap.md
│   ├── 08_sprint_alpha1_kms_safety.md
│   ├── 09_sprint_alpha2_handoff_integrity.md
│   └── 10_sprint_alpha3_perimeter_hardening.md
│
├── deployment/README.md            # ★최신 운영 배포 가이드 (2026-05-21)
├── OPERATION_INVENTORY.md          # 운영 관리 포인트 인벤토리
├── RUNBOOK_SSO_METRICS.md          # SSO/IM 본질 메트릭 RUNBOOK
├── SPRINT_B_PLAN.md                # Sprint B 축소 계획
├── phased-rollout-strategy.md      # 단계적 배포 전략 (Phase-Gate Rollout)
│
├── onepass-agency-sdk-usage-guide.md   # 현행 SDK 사용 가이드 (메인)
├── onepass-agent-*.md                  # Agency Java Agent 가이드 시리즈
├── sso-agency-*.md                     # 자체 SSO 보유 기관 가이드 (개발자/담당자/운영)
├── ext_api_proxy_guide.md              # /api/ext/** 프록시 가이드
├── kafka_easy_guide_for_*.md           # Kafka 가이드 (개발자/관리자)
│
├── features/                       # 기능 명세 (F-01 ~ F-27)
│
├── internal/                       # 내부 개발 문서
│   ├── architecture/               #   설계·아키텍처·ADR
│   ├── dataflow/                   #   로그인/회원전환/Handoff 등 흐름도
│   ├── development/                #   개발 가이드 (★현행: *-2026-05-12.md v3.0.0)
│   │   ├── guide-backend-ido-2026-05-12.md   # IdO 백엔드 가이드 v3.0.0
│   │   ├── guide-backend-qim-2026-05-12.md   # Q-IM 백엔드 가이드 v3.0.0
│   │   ├── guide-frontend-2026-05-12.md      # FE 가이드 v3.0.0
│   │   ├── guide-infra-2026-05-12.md         # 인프라/DevOps 가이드 v3.0.0
│   │   └── ... (api-reference / qim-development-guide / local-dev-guide 등)
│   └── spec/                       #   기술 명세서
│       └── api-reference-2026-05-12.md       # ★현행 전체 API 레퍼런스 v3.0.0
│
├── proposal/                       # 경영진·외부 제안서 (PROP-2026-001 시리즈)
├── smep-handover/                  # SMEP 팀 전달용 (MIG/PRP/IMPL 시리즈)
│
└── _archive/2026-05-22/            # 보관 — 시점 산출물·구버전 23건
    └── README.md                   #   카테고리 A~E 매니페스트
```

### 최신 분석 / 로드맵

- **[Sprint α-1: KMS 안전망](docs/analysis/sso-im-readiness/08_sprint_alpha1_kms_safety.md)** — F5.1/F5.2
- **[Sprint α-2: Handoff 무결성](docs/analysis/sso-im-readiness/09_sprint_alpha2_handoff_integrity.md)** — F4.1/F4.5/F4.2
- **[Sprint α-3: 경계 영역 보안 강화](docs/analysis/sso-im-readiness/10_sprint_alpha3_perimeter_hardening.md)** — F4.3/F4.4/F4.6
- **[위험 매트릭스 · 로드맵](docs/analysis/sso-im-readiness/07_risk_matrix_roadmap.md)** — α/β 진행 상태

### 보관 안내

2026-05-22 정리분 23건은 `docs/_archive/2026-05-22/`로 이동되었습니다(이력은 `git mv`로 보존). 대상·이유는 [`docs/_archive/2026-05-22/README.md`](docs/_archive/2026-05-22/README.md) 참조.

---

---

## Wiki 문서 목차

> 위키 전체 문서는 [`wiki/`](./wiki/) 디렉토리에 위치합니다.  
> 위키 마스터 인덱스: [`wiki/INDEX.md`](./wiki/INDEX.md)

### ADR (Architecture Decision Records)

> ADR 전체 목차: [`wiki/adr/README.md`](./wiki/adr/README.md)

| ADR | 제목 | 카테고리 |
|-----|------|:--------:|
| [ADR-001](./wiki/adr/ADR-001-ido-microservice-architecture.md) | IdO 마이크로서비스 아키텍처 채택 | 아키텍처 |
| [ADR-002](./wiki/adr/ADR-002-jdk21-virtual-threads.md) | JDK 21 LTS + Virtual Threads 채택 | 런타임 |
| [ADR-003](./wiki/adr/ADR-003-spring-boot-3.md) | Spring Boot 3.2 / Spring Framework 6 채택 | 프레임워크 |
| [ADR-004](./wiki/adr/ADR-004-kafka-eda.md) | Apache Kafka 기반 EDA 채택 | 메시징 |
| [ADR-005](./wiki/adr/ADR-005-postgresql-primary-store.md) | PostgreSQL 주 데이터 저장소 채택 | 데이터베이스 |
| [ADR-006](./wiki/adr/ADR-006-redis-session-cache.md) | Redis 세션·캐시·분산락 채택 | 인프라 |
| [ADR-007](./wiki/adr/ADR-007-flyway-db-migration.md) | Flyway DB 스키마 버전 관리 채택 | 데이터베이스 |
| [ADR-008](./wiki/adr/ADR-008-transactional-outbox-pattern.md) | Transactional Outbox 패턴 채택 | 패턴 |
| [ADR-009](./wiki/adr/ADR-009-qim-outbox-spec-001.md) | QIM-OUTBOX-SPEC-001 이벤트 타입 정합화 | 이벤트 |
| [ADR-010](./wiki/adr/ADR-010-cast-token-cross-agency-sso.md) | Ed25519 CAST Token Cross-Agency SSO | 보안 |
| [ADR-011](./wiki/adr/ADR-011-hmac-sha256-gateway-auth.md) | HMAC-SHA256 Agency Gateway 인증 | 보안 |
| [ADR-012](./wiki/adr/ADR-012-react-fe-dual-instance.md) | React FE 이중 Axios 인스턴스 분리 | 프론트엔드 |

### 서비스별 상세 설계서

| 서비스 | 포트 | 문서 |
|--------|:----:|------|
| IdO (Identity Orchestrator) | 8083 | [01-ido-service-design.md](./wiki/design/01-ido-service-design.md) |
| Q-IM (Query & Identity Manager) | 8082 | [02-qim-service-design.md](./wiki/design/02-qim-service-design.md) |
| Q-Sign (Auth Gateway) | 8081 | [03-qsign-service-design.md](./wiki/design/03-qsign-service-design.md) |
| Agency-Stub (PoC 시뮬레이터) | 8090 | [04-agency-stub-design.md](./wiki/design/04-agency-stub-design.md) |

### 워크스루 (전체 흐름 문서)

| 문서 | 설명 |
|------|------|
| [WT-001: 로그인](./wiki/walkthrough/01-login-walkthrough.md) | NICE·OACX·EzAuth·Keycloak 4경로, CI 보안, FE 이중 인스턴스 |
| [WT-002: 신규 가입](./wiki/walkthrough/02-member-register-walkthrough.md) | 개인·기업 가입, Outbox 발행, CI 데이터 경계, 멱등성 |
| [WT-003: 기관 전환](./wiki/walkthrough/03-member-conversion-walkthrough.md) | PERSONAL/BIZ_CONVERTED 이벤트, 분산락, 세션 갱신 |
| [WT-004: 프로비저닝](./wiki/walkthrough/04-provisioning-walkthrough.md) | QimEventConsumer 5종 필터, Virtual Thread 68기관 병렬, 지수 백오프 |
| [WT-005: Handoff SSO](./wiki/walkthrough/05-handoff-sso-walkthrough.md) | CAST Token, Ed25519, Handoff 4전략, 키 로테이션 |

### 유관기관 연동 가이드 (v0.8.9 신규)

| 문서 | 설명 |
|------|------|
| [GUIDE-001: URL 플로우 분석](./wiki/guide/01-agency-conversion-url-flow.md) | 유관기관 전환 URL 시퀀스 + 3레이어 검증 + ❌틀린것/⚠다른것/✅올바른것 |
| [GUIDE-002: 파라미터 보안](./wiki/guide/02-conversion-param-security.md) | JWT HS256 Signed Request 보안 대안 + FE/BE 구현 코드 + 개선 로드맵 |
| [GUIDE-003: 기관 오픈 샘플](./wiki/guide/03-conversion-launch-sample.md) | Node.js/Java/Python URL 생성 코드 샘플 + 기관 오픈 체크리스트 |
| [GUIDE-004: 데이터 흐름 다이어그램](./wiki/guide/04-conversion-data-flow-diagram.md) | ①~㉪ 순번 시퀀스 다이어그램 + ConversionContext 상태 추적 + Handoff 확대도 |

### 산출물 인덱스

- [DELIVERABLES.md](./wiki/deliverables/DELIVERABLES.md) — 전체 산출물 현황, PR 이력, DOCX 목록

---

> **문서 최종 수정**: 2026-05-22 (Sprint α-3 머지 + 문서 정리 1차) | **버전**: v0.8.11 + Sprint α 누적 | **담당**: GenSpark AI Developer
> **개발 워크플로우**: `shipster` 브랜치에서 작업 → 누적 후 `shipster → main` release PR (squash merge) → 머지 직후 shipster를 origin/main에 reset + force-push로 동기화
> 문서 카탈로그: [`docs/README.md`](docs/README.md) · 위키: [`wiki/INDEX.md`](wiki/INDEX.md)
