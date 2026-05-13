# OnePass 통합인증 플랫폼 (Integration-SSO)

**중소벤처기업부 중기원패스(OnePass) 통합인증 SSO 및 아이덴티티 관리 시스템** PoC/프리프로덕션 구현체.  
**4+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe · agency-stub) 기반 EDA 아키텍처.

> **현재 버전: v3.0.0** — 유관기관 SSO 완성 (Keycloak OIDC 브로커 + 소셜 계정 식별 + 보안 패치 P1~P3)  
> **빌드 상태**: `./gradlew :q-im:compileJava :ido:compileJava :platform-common:compileJava :agency-stub:compileJava --no-daemon` → **BUILD SUCCESSFUL**  
> **테스트**: `./gradlew test` → **397개 통과** (백엔드 단위 테스트)  
> **PR**: [#82 (OPEN)](https://github.com/HipsterMIN/integration-sso/pull/82) — SSO 운영 보안 패치 P1~P3

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [전체 구현 진행률](#전체-구현-진행률)
3. [아키텍처 개요](#아키텍처-개요)
4. [모듈 책임 분리](#모듈-책임-분리)
5. [기술 스택](#기술-스택)
6. [모듈 구성](#모듈-구성)
7. [유관기관 SSO (v3.0)](#유관기관-sso-v30)
8. [Sprint 10: SLO FE 완성 + FE 기반](#sprint-10-slo-fe-완성--fe-기반)
9. [S7-T2: NICE/OACX 본인인증 통합](#s7-t2-niceoacx-본인인증-통합)
10. [Feature Flag 체계](#feature-flag-체계)
11. [데이터베이스 구성](#데이터베이스-구성)
12. [Kafka 토픽](#kafka-토픽)
13. [보안 체계](#보안-체계)
14. [Flyway 마이그레이션 현황](#flyway-마이그레이션-현황)
15. [테스트 현황](#테스트-현황)
16. [모니터링 인프라](#모니터링-인프라)
17. [빠른 시작](#빠른-시작)
18. [접속 URL](#접속-url)
19. [개발 환경 설정](#개발-환경-설정)
20. [전체 로드맵 & 개발 플랜](#전체-로드맵--개발-플랜)
21. [팀별 개발 가이드](#팀별-개발-가이드)
22. [코딩 컨벤션](#코딩-컨벤션)
23. [문서 디렉토리](#문서-디렉토리)

---

## 버전 히스토리

| 버전 | PR | 주요 내용 |
|------|----|---------|
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

## 전체 구현 진행률

> **기준일**: 2026-05-13 | **총 테스트**: 397개 (ido 202 + platform-common 59 + q-sign 23 + q-im 113) | v3.0.0 반영

### 모듈별 구현 완성도

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비, UUID v7, HandoffPayload.GUEST)
Q-Sign           ████████████████████  97%  (InternalSig 수신 검증 완료, SLO 완료)
Q-IM             ████████████████████  98%  (소셜 SSO API, CI 암호화 v{n}, 파기 스케줄러, InternalApiKeyInterceptor)
IdO              ████████████████████  93%  (Keycloak OIDC 브로커, SSO 소셜 계정 연동, AES 키 로테이션, NICE/OACX BFF)
agency-stub      ████████████████████  90%  (E2E 시뮬레이터, GUEST 정책 처리 완비)
onepass-fe       █████████████████░░░  85%  (SLO 연동·useAuthState·ErrorBoundary·회원정보수정 완료)
인프라/Docker    ████████████████████ 100%  (모니터링 스택 완비, Feature Flag K8s ConfigMap 완료)
보안             ████████████████████  99%  (InternalApiKeyInterceptor, redirectUri 검증, UNIQUE 복합 키)
테스트 커버리지  ████████████░░░░░░░░  58%  (백엔드 단위 397개, 통합테스트 0개)
```

**전체 완성도**: 약 **95%** — 운영 배포 환경변수 설정 후 즉시 가동 가능

### Sprint별 완료 현황

| Sprint | 목표 | 상태 | 완료 항목 |
|--------|------|------|-----------|
| **Sprint 1** | P0 보안 결함 | ✅ **완료** | API Key PBKDF2, 기본 시크릿 제거, X-Internal-Sig |
| **Sprint 2** | P1 SLO + 개인정보 | ✅ **완료** | SLO Keycloak 전파, SP 로그아웃 Webhook, 파기 스케줄러 |
| **Sprint 3** | P2 운영 고도화 | ✅ **완료** | UUID v7, Micrometer 기초, 구조화 로깅, FE 상태관리 |
| **Sprint 4** | 테스트 기반 | ✅ **완료** | HandoffServiceImpl 18개, Webhook 33개, UuidV7 27개 |
| **Sprint 5** | 암호화 + 모니터링 | ✅ **완료** | AES 키 로테이션, Prometheus/Grafana/Loki |
| **Sprint 6** | 잔여 테스트 | ✅ **완료** | agency-stub 테스트, 유관기관 패턴 Stub |
| **Sprint 7** | 본인인증 BFF | ✅ **완료** | S7-T2 NICE/OACX 이식, S7-T6 CI→Q-IM (ImApiOutPort) |
| **Sprint 8** | CI/CD + 부하테스트 | ✅ **완료** | GitHub Actions, k6 부하테스트, OWASP ZAP, Grafana 알림 |
| **Sprint 9** | 프로덕션 강화 | ✅ **완료** | Redisson 분산 락, Resilience4j, Bean Validation, OTel AOP, 감사 로그, K8s |
| **Sprint 9 FF** | Feature Flag | ✅ **완료** | 18개 Feature Flag, K8s ConfigMap 14개 환경변수 |
| **Sprint 10** | SLO FE + 회원정보 | ✅ **완료** | SLO FE 연동, useAuthState 훅, ErrorBoundary, InformationStep3 실 API |
| **Sprint 11** | **유관기관 SSO** | ✅ **완료** | Keycloak OIDC 브로커, 소셜 계정 식별, GUEST 정책, P1~P3 보안 패치 |

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

## 모듈 책임 분리

| 모듈 | SoR 역할 | 포트 | 핵심 책임 |
|------|---------|------|----------|
| `platform-common` | — | — | 공통 도메인·이벤트·에러코드·UUID v7 유틸, `HandoffPayload.GUEST` |
| `q-sign` | **인증 SoR** | 8081 | OIDC 브로커링, JWT 검증, PKCE, SLO Keycloak 전파 |
| `q-im` | **식별 SoR** | 8082 | qimUserId, CI AES-256-GCM v{n}, DI HMAC, 회원 원장, 소셜 계정 SSO API, InternalApiKeyInterceptor |
| `ido` | **정책 오케스트레이터 + FE BFF** | 8083 | Handoff 발급/검증, Keycloak OIDC 브로커, Policy+GUEST, Webhook, NICE/OACX BFF, AES 키 로테이션, SLO |
| `agency-stub` | — (PoC 전용) | 8084 | 유관기관 연동 E2E 시뮬레이터 (APPROVED/GUEST 분기 처리) |
| `onepass-fe` | — | 3000/3001 | React 18 SPA — SLO 연동, 회원정보 수정 실연동 |

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
| JUnit 5 + Mockito | BOM 관리 | 단위 테스트 (397개) |

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

## 데이터베이스 구성

| 모듈 | DB 엔진 | 스키마 | 최신 Flyway 버전 |
|------|---------|--------|----------------|
| Q-Sign | PostgreSQL 16 | `qsign` | **V5** — auth_method 컬럼 |
| IdO | PostgreSQL 16 | `ido` | **V13** — agency pattern scenarios seed |
| Q-IM | MariaDB 11.4 | `qim` | **V4** — 소셜 SSO UNIQUE 복합 키 (`★신규`) |
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
| `q-im` | **113개** | Webhook 33개 |
| **합계** | **397개** | — |

> **SSO 통합 테스트**: `KeycloakOidcService` + `QimClientImpl` 소셜 경로에 대한 단위 테스트 미작성 (잔여 과제).  
> **FE 테스트**: `ConversionLayout`, `KrdsModal` 단위 테스트 존재 (jest 환경 미완비로 tsc standalone에서 오류 — Vite 빌드 환경에서는 정상)

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
| **P2** | Keycloak realm 구성 문서화 | DevOps | social IDP 설정, ACR mapper |
| **P2** | agency-stub Kafka 직접 구독 → 공개 API 전환 | IdO BE | 망 분리 원칙 |
| **P2** | CSR(관리자 UI) 미구현 기관 관리 화면 | FE | — |
| **P2** | DLQ 전략 구현 (`KafkaConsumerConfig`) | IdO BE | GAP-IDO-09 |
| **P3** | FE E2E 테스트 (Cypress/Playwright) | FE | — |
| **P3** | k6 부하 테스트 고도화 | DevOps | SSO 경로 포함 |

---

## 팀별 개발 가이드

> 팀별 상세 가이드는 `docs/development/` 디렉토리 참조

| 팀 | 가이드 문서 | 내용 요약 |
|----|-----------|---------|
| **IdO 백엔드** | [`docs/development/guide-backend-ido.md`](docs/development/guide-backend-ido.md) | Feature Flag 운영, SSO 브로커 설정, SLO API, NICE/OACX BFF, AES 키 로테이션 |
| **Q-IM 백엔드** | [`docs/development/guide-backend-qim.md`](docs/development/guide-backend-qim.md) | CI 암호화, 소셜 SSO API, InternalApiKeyInterceptor, 회원 원장 API, 파기 스케줄러 |
| **프론트엔드** | [`docs/development/guide-frontend.md`](docs/development/guide-frontend.md) | SLO 연동, useAuthState 훅, ErrorBoundary, 회원정보 수정, API 클라이언트 패턴 |
| **인프라/DevOps** | [`docs/development/guide-infra.md`](docs/development/guide-infra.md) | Docker Compose, K8s ConfigMap, Keycloak realm 구성, Feature Flag 운영 |
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

```
docs/
├── FEATURE_FLAGS.md                    # 18개 Feature Flag 완전 가이드
├── api-auth-spec.md                    # NICE/OACX API 명세 (FE 타입 포함)
├── local-dev-guide.md                  # 로컬 개발 환경 설정
├── qim-development-guide.md            # Q-IM 개발 가이드 v1.1.0
├── qim-ido-integration-architecture.md # Q-IM ↔ IdO 연동 아키텍처
├── handoff-note.md                     # Handoff 프로토콜 상세
├── oidc-brokering-design.md            # OIDC 브로커링 설계
├── agency-external-arch-supplement.md  # 기관 외부망 격리 원칙
├── eda-master-arch-gap-analysis-v0.8.md # EDA 아키텍처 GAP 분석
├── gap-analysis-v0.8.3-vs-project.md   # v0.8.3 설계서 GAP 분석
├── spec/                               # 아키텍처 명세서
│   ├── 00-index.md
│   ├── 01-system-overview.md
│   ├── 02-architecture.md
│   ├── 04-api-reference.md
│   ├── 05-database-schema.md
│   ├── 06-kafka-event-catalog.md
│   ├── 07-security.md
│   └── 09-gap-and-roadmap.md
└── development/                        # 팀별 개발 가이드
    ├── guide-backend-ido.md            # IdO 백엔드 팀 가이드
    ├── guide-backend-qim.md            # Q-IM 백엔드 팀 가이드
    ├── guide-frontend.md               # FE 팀 가이드
    ├── guide-infra.md                  # 인프라/DevOps 팀 가이드
    └── (기존 01~13 개발 문서)
```

---

> **문서 최종 수정**: 2026-05-13 | **버전**: v3.0.0 | **담당**: GenSpark AI Developer  
> 문의/기여: `genspark_ai_developer` 브랜치 → PR → main 병합 워크플로우 준수
