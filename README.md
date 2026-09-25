# Idem — 회원통합·연합인가 플랫폼 (integration-sso)

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)

**Idem(아이뎀)** 은 이기종 회원원장을 가진 여러 기관(테넌트)의 회원을 동일인 기준으로 하나로 묶고, 기관에는 가명 ID만 전달하며, 연합 인가(역할·권한)와 세션 핸드오프를 제공하는 **IdP 위에 얹는 회원통합·연합인가 계층**입니다.
첫 적용 사례는 중소벤처기업부 유관기관 통합회원(중기원패스, OnePass)이며, 저장소는 2026-09-04 **Idem** 으로 개명되었습니다 — 개명 범위·매핑·미변경 항목은 [`docs/naming.md`](docs/naming.md) 참조.

| 모듈 | 역할 | 포트 |
|---|---|---|
| `idem-gate` | 인증 관문 — 로그인 프론트, OIDC 파사드(Keycloak 프록시), PKCE | 8081 |
| `idem-registry` | 회원 원장 — 골든 레코드, 가명 ID(DI), 탈퇴 | 8082 |
| `idem-hub` | 오케스트레이션 — 세션 핸드오프, 프로비저닝, 웹훅, KMS, 본인인증 브로커 | 8083 |
| `editions/idem-kr-hub` · `editions/idem-kr-registry` | KR 에디션 부트 모듈 — 코어 + SMES 회원 개념(CI 조회·기업인증·회원전환·회원조회·기업회원). `IDEM_EDITION=kr` | 8083 · 8082 |
| `idem-authz` | 연합 인가 — 역할 원장(SoR), SCIM 2.0 Groups, 만료·회수 전파 | 8086 |
| `idem-relay` | Transactional Outbox 분산 릴레이 배치 (ShedLock) | 8090 |
| `idem-console-admin` | 관리 콘솔 (React + Vite, S7) — 관리자 로그인(2단계)·기관 온보딩·OIDC client·감사·관리자 관리 | 3001 |
| `editions/idem-kr-portal` | KR 에디션 회원 포털 (구 `idem-console`, React SPA) | 3002 |
| `idem-sdk-java` | 테넌트(기관)측 Java 8+ SDK — 핸드오프 티켓 검증, HMAC | — |
| `idem-agent` | 레거시 WAS용 Java Agent (`-javaagent`) | — |
| `idem-tenant-sample` | 참조 테넌트 앱 (PoC·E2E용) | 8084 |
| `idem-common` | 공통 라이브러리 | — |

> **최신 상태 (2026-06-25)** — **연합 인가(Federated Authorization) 평면 신설** — `idem-authz` 모듈(역할 부여 SoR, L1+L2) + 토큰 `roles[]` 클레임(CAST·Handoff) + `/api/ext` 게이트웨이 PEP 속성 전파 + SCIM 2.0 Groups + 한시 권한 만료 스케줄러 + 회수 이벤트 전파(`idem.authz.assignment.events`)
> **현재 버전**: v0.8.11 + 연합 인가 (authz-1 / authz-2) + Sprint α 누적
> **빌드 상태**: `./gradlew :idem-agent:agentJar` → **BUILD SUCCESSFUL** (`idem-agent-0.1.0-SNAPSHOT-all.jar`, ~10MB)
> **테스트 (참고)**: `./gradlew :idem-agent:test` 131개, `:idem-sdk-java:test` 36개. Sprint α-1~α-3 신규 회귀 테스트 합산은 별도 검증 필요.
> **최근 머지**: [#205](https://github.com/HipsterMIN/integration-sso/pull/205) (연합 인가 L1~L4 — q-authz·roles 클레임·PEP·만료·SCIM) → [#206](https://github.com/HipsterMIN/integration-sso/pull/206) (회수 이벤트 전파 — `main` 병합 대기)
> **최신 분석/로드맵**: [`docs/analysis/sso-im-readiness/00_INDEX.md`](docs/analysis/sso-im-readiness/00_INDEX.md)
> **문서 안내**: [`docs/README.md`](docs/README.md) — 2026-05-22 정리 결과 반영

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [전체 구현 진행률](#전체-구현-진행률)
3. [아키텍처 개요](#아키텍처-개요)
4. [연합 인가 (Federated Authorization)](#연합-인가-federated-authorization)
5. [모듈 책임 분리](#모듈-책임-분리)
6. [기술 스택](#기술-스택)
7. [모듈 구성](#모듈-구성)
8. [유관기관 SSO (v3.0)](#유관기관-sso-v30)
9. [Sprint 10: SLO FE 완성 + FE 기반](#sprint-10-slo-fe-완성--fe-기반)
10. [S7-T2: NICE/OACX 본인인증 통합](#s7-t2-niceoacx-본인인증-통합)
11. [Feature Flag 체계](#feature-flag-체계)
12. [🆕 OnePass Agency Java Agent](#-onepass-agency-java-agent)
13. [🆕 멀티 WAS 테스트베드](#-멀티-was-테스트베드)
14. [데이터베이스 구성](#데이터베이스-구성)
15. [Kafka 토픽](#kafka-토픽)
16. [보안 체계](#보안-체계)
17. [Flyway 마이그레이션 현황](#flyway-마이그레이션-현황)
18. [테스트 현황](#테스트-현황)
19. [모니터링 인프라](#모니터링-인프라)
20. [빠른 시작](#빠른-시작)
21. [접속 URL](#접속-url)
22. [개발 환경 설정](#개발-환경-설정)
23. [전체 로드맵 & 개발 플랜](#전체-로드맵--개발-플랜)
24. [팀별 개발 가이드](#팀별-개발-가이드)
25. [코딩 컨벤션](#코딩-컨벤션)
26. [문서 디렉토리](#문서-디렉토리)
27. [Wiki 문서 목차](#wiki-문서-목차)

---

## 버전 히스토리

| 버전 | PR | 주요 내용 |
|------|----|---------|
| **authz-2** | [#206](https://github.com/HipsterMIN/integration-sso/pull/206) | **연합 인가 — 인가 이벤트 전파(회수 무효화)** — q-authz 트랜잭셔널 아웃박스(`authz.authz_outbox`, V2) → `outbox-relay-batch`가 `idem.authz.assignment.events` Kafka 토픽으로 릴레이(`FOR UPDATE SKIP LOCKED` + ShedLock, 파티션 키 `qimUserId`). `GRANTED`/`REVOKED`/`EXPIRED` 이벤트를 기관 게이트웨이·세션 캐시·ido가 구독 → **토큰 만료 전 역할 회수 전파**(연합 인가 회수 지연 약점 해소). |
| **authz-1** | [#205](https://github.com/HipsterMIN/integration-sso/pull/205) | **연합 인가 평면 신설(L1+L2)** — `q-authz` 모듈 신규(역할 부여 SoR, 포트 8086, 스키마 `authz`, RLS 테넌트 격리) + 토큰 `roles[]` 클레임 주입(CAST JWT + Handoff 암호화 payload, q-authz `effective-roles` 조회 fail-open) + `/api/ext` 게이트웨이 PEP 속성 전파(`X-Authz-User/Scope/Roles`, 클라이언트 헤더 anti-spoofing, 비강제) + 한시 권한 만료 전이 스케줄러(ACTIVE→EXPIRED) + SCIM 2.0 Groups 프로비저닝(`/scim/v2/Groups`). |
| **α-3 + onepass-support** | [#178](https://github.com/HipsterMIN/integration-sso/pull/178) (α-3) + [#179](https://github.com/HipsterMIN/integration-sso/pull/179) | **경계 영역 보안 강화 + 모듈 뼈대** — F4.3 Webhook 기본 시크릿 제거(`@PostConstruct` 부팅 가드 + `allow-empty-secret` escape hatch), F4.4 CAST URL 누출 방지(POST 자동 제출 form, 토큰 hidden field), F4.6 Q-IM 예외 구분(404→null·5xx→`IDEM_HUB_REGISTRY_UNREACHABLE`). 회귀 테스트 27건 신규. `onepass-support` 모듈 뼈대 추가. |
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

## 전체 구현 진행률

> **기준일**: 2026-05-18 | **총 테스트**: 219개 통과 + 30개 skipped (q-im 기준; 전체 모듈 포함 시 ido 202 + platform-common 59 + q-sign 23 + **onepass-agency-sdk 36**) | v0.8.11 반영

### 모듈별 구현 완성도

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비, UUID v7, HandoffPayload.GUEST, E-CONV-601~603 ★신규)
Q-Sign           ████████████████████  97%  (InternalSig 수신 검증 완료, SLO 완료)
Q-IM             ████████████████████  98%  (소셜 SSO API, CI 암호화 v{n}, 파기 스케줄러, InternalApiKeyInterceptor)
IdO              ████████████████████  95%  (Keycloak OIDC 브로커, SSO 소셜 계정 연동, AES 키 로테이션, NICE/OACX BFF, ConversionInit API ★신규, addAuthHeader() ★신규)
q-authz          ██████████████████░░  90%  (L1 역할부여 SoR + roles[] 클레임 + /api/ext PEP + 만료 스케줄러 + SCIM 2.0 Groups + 회수 아웃박스 완료 🆕; 다운스트림 이벤트 소비자 미구현)
agency-stub      ████████████████████  90%  (E2E 시뮬레이터, GUEST 정책 처리 완비)
onepass-fe       █████████████████░░░  87%  (SLO 연동·useAuthState·ErrorBoundary·회원정보수정·Step8 isSafeRedirectUri B-1수정 ★신규)
인프라/Docker    ████████████████████ 100%  (모니터링 스택 완비, Feature Flag K8s ConfigMap 완료)
보안             ████████████████████  99%  (InternalApiKeyInterceptor, redirectUri 검증, UNIQUE 복합 키)
테스트 커버리지  █████████████░░░░░░░  65%  (q-im 219개 통과+30 skipped, S8/S9 V6 E2E 통합 10종)
```

**전체 완성도**: 약 **96%** — 운영 배포 환경변수 설정 후 즉시 가동 가능 (v0.8.11 반영 — SDK GAP-1~5 수정, 분석 보고서 추가)

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
| **Sprint 17** | **프로비저닝 addAuthHeader()** | ✅ **완료** | `addAuthHeader()` API_KEY/HMAC/mTLS 3-mode 지원 — BLOCKER 해소 |
| **Sprint 17+** | **유관기관 전환 보안** | ✅ **완료** | B-1/B-2 버그 수정, JWT Signed Request ConversionInit API, E-CONV 에러코드, GUIDE 4편 |

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
  │ q-sign:8081 │       │  q-im:8082  (PostgreSQL qim)        │
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
    │  idem.gate.auth.events                  │
    │  idem.hub.handoff.events                 │
    │  idem.registry.user.events (Compacted)        │
    │  platform.session.advisory          │
    │  platform.audit.log                 │
    │  idem.authz.assignment.events            │
    │  + 각 토픽별 .dlq 토픽               │
    └─────────────────────────────────────┘

    ┌─────────────────────────────────────┐
    │     모니터링 스택                    │
    │  Prometheus :9090                   │
    │  Grafana    :3000                   │
    │  Loki       :3100                   │
    └─────────────────────────────────────┘
```

> **연합 인가 평면 (🆕)**: 위 다이어그램에 `q-authz`(:8086, PostgreSQL `authz`, 역할 부여 SoR)가 추가됩니다. 내부망에서 IdO가 `q-authz`의 `effective-roles`를 조회해 토큰 `roles[]` 클레임·`/api/ext` 속성 전파를 수행하고, `q-authz` 부여 변경은 트랜잭셔널 아웃박스 → `outbox-relay-batch` → `idem.authz.assignment.events` 토픽으로 회수 전파됩니다. 상세: [연합 인가](#연합-인가-federated-authorization).

---

## 연합 인가 (Federated Authorization)

> **설계 원칙**: **부여**는 중앙 SoR(`q-authz`)에 집중하고, **해석·집행(decision/enforcement)**은 각 기관 지역에 위임한다 — 단일 멀티테넌트(`agency_code` + PostgreSQL RLS)로 테넌트를 격리하며, 역할 코드(`role_code`)는 기관별로 불투명(opaque)하다.

연합 인가 증분 1~5 (커밋 `aa375ed`→`ceca160`). PR [#205](https://github.com/HipsterMIN/integration-sso/pull/205)가 증분 1~4 전달, 증분 5(Part A/B)는 `shipster`에 커밋되어 PR [#206](https://github.com/HipsterMIN/integration-sso/pull/206)으로 `main` 병합 대기.

| 증분 | 기능 | 모듈 |
|------|------|------|
| 1 (L1 코어) | 역할 부여 SoR 모듈 신설 | `q-authz` |
| 2 | 토큰 `roles[]` 클레임 주입 (CAST + Handoff) | `ido` |
| 2b | `/api/ext` PEP 인가 속성 전파 | `ido` |
| 3 | 한시 권한 만료 전이 스케줄러 | `q-authz` |
| 4 (L2) | SCIM 2.0 Groups 프로비저닝 | `q-authz` |
| 5 A/B | 트랜잭셔널 아웃박스 → `idem.authz.assignment.events` 릴레이 (회수 전파) | `q-authz`, `outbox-relay-batch` |

### q-authz 모듈 (L1 코어)

| 항목 | 값 |
|------|-----|
| 서비스명 | `q-authz` (`spring.application.name`) |
| 포트 | **8086** (graceful shutdown) |
| 베이스 패키지 | `io.github.hipstermin.idem.authz` |
| DB / 스키마 | PostgreSQL · `authz` (`currentSchema=authz`, Hibernate `default_schema`) |
| JPA | `ddl-auto: validate`, `open-in-view: false`, UTC |
| Flyway | `classpath:db/migration` (V1, V2) |

**테이블** (스키마 `authz`)

| 테이블 | 엔티티 | 역할 |
|--------|--------|------|
| `authz_role` | `AuthzRoleEntity` | 기관별 역할 카탈로그 — 복합 PK `(agency_code, role_code)`, `is_assignable`; `agency_code='PLATFORM'` = 글로벌 역할 |
| `authz_user_role` | `AuthzUserRoleEntity` | **역할 부여 중앙 SoR** — PK UUID, unique `(qim_user_id, agency_code, role_code)`, `expires_at` nullable(한시/JIT), `status` 기본 `ACTIVE`, `source` 기본 `API` |
| `authz_grant_audit` | `AuthzGrantAuditEntity` | append-only 감사 로그 (event·actor·actor_ip·reason·correlation_id·at) |
| `authz_outbox` | `AuthzOutboxEntity` | 트랜잭셔널 아웃박스 — PK `event_id`(UUIDv7), `topic` 기본 `idem.authz.assignment.events`, `status` 기본 `PENDING`; RLS 면제(시스템 릴레이 전 테넌트 폴링) |

> **Enum** — `AssignmentStatus`(ACTIVE/REVOKED/EXPIRED) · `GrantSource`(CONSOLE/SCIM/API/AGENCY_PUSH) · `AuditEvent`(GRANT/REVOKE/EXPIRE/ROLE_CREATED). DB CHECK 제약으로 미러링.

**내부 인가 API** — `AuthzInternalController`, base `/api/v1/internal/authz`

| Method | Path | 용도 |
|--------|------|------|
| `POST` | `/roles` | 역할 카탈로그 생성 (201, `X-Actor` 선택) |
| `GET` | `/roles?agencyCode=` | 기관 역할 목록 |
| `POST` | `/grants` | 사용자 역할 부여 (멱등, 201, `X-Correlation-Id` 선택) |
| `DELETE` | `/grants?qimUserId=&agencyCode=&roleCode=&revokedBy=&reason=` | 역할 회수 (204) |
| `GET` | `/users/{qimUserId}/roles?agencyCode=` | 사용자 부여 목록 |
| `GET` | `/users/{qimUserId}/effective-roles?agencyCode=` | 유효 역할 — 토큰 `roles[]` 클레임 소스 |

> **보안** — `InternalApiKeyInterceptor`가 `X-Internal-Api-Key`를 `MessageDigest.isEqual` 상수시간 비교로 검증. 서버 키(`idem.authz.security.internal-api-key`) 미설정/공백 시 모든 보호 요청 **401 fail-closed**. 적용 경로: `/api/v1/internal/**`, `/scim/v2/**`(actuator/api-docs/swagger 제외). RLS는 심층 방어로 `app.current_agency` GUC 기반.

**요청/응답 예시** (모든 내부 API는 `X-Internal-Api-Key` 필수)

```bash
# ① 역할 카탈로그 생성
curl -sS -X POST http://q-authz:8086/api/v1/internal/authz/roles \
  -H "X-Internal-Api-Key: $IDEM_AUTHZ_INTERNAL_API_KEY" -H "X-Actor: admin@onepass" \
  -H "Content-Type: application/json" \
  -d '{"agencyCode":"GOV_SMES","roleCode":"MANAGER","name":"기관 관리자","description":"승인 권한"}'

# ② 사용자 역할 부여(멱등) — expiresAt 지정 시 한시(JIT) 부여
curl -sS -X POST http://q-authz:8086/api/v1/internal/authz/grants \
  -H "X-Internal-Api-Key: $IDEM_AUTHZ_INTERNAL_API_KEY" -H "X-Correlation-Id: cid-abc" \
  -H "Content-Type: application/json" \
  -d '{"qimUserId":"u-1024","agencyCode":"GOV_SMES","roleCode":"MANAGER","grantedBy":"admin@onepass","expiresAt":"2026-12-31T23:59:59Z","source":"API","reason":"분기 승인"}'

# ③ 유효 역할 조회 — ido가 토큰 roles[] 클레임 발급 시 호출
curl -sS "http://q-authz:8086/api/v1/internal/authz/users/u-1024/effective-roles?agencyCode=GOV_SMES" \
  -H "X-Internal-Api-Key: $IDEM_AUTHZ_INTERNAL_API_KEY"

# ④ 역할 회수 → idem.authz.assignment.events 회수 이벤트 발행
curl -sS -X DELETE "http://q-authz:8086/api/v1/internal/authz/grants?qimUserId=u-1024&agencyCode=GOV_SMES&roleCode=MANAGER&revokedBy=admin@onepass&reason=offboarding" \
  -H "X-Internal-Api-Key: $IDEM_AUTHZ_INTERNAL_API_KEY"
```

```json
// POST /grants → 201 (UserRoleResponse)
{ "id": "01997f3a-...-uuid", "qimUserId": "u-1024", "agencyCode": "GOV_SMES",
  "roleCode": "MANAGER", "status": "ACTIVE", "grantedAt": "2026-06-25T01:02:03Z",
  "grantedBy": "admin@onepass", "expiresAt": "2026-12-31T23:59:59Z", "source": "API" }

// GET .../effective-roles → 200 (EffectiveRolesResponse) — 만료 미경과 ACTIVE 역할만, 정렬
{ "qimUserId": "u-1024", "agencyCode": "GOV_SMES", "roles": ["MANAGER", "VIEWER"] }
```

### 토큰 역할 클레임 전파

부여된 유효 역할은 **토큰 발급 시점에** `q-authz`에서 조회되어 토큰 내부에 실린다 — 검증 시 재조회 없음.

- **CAST 토큰** (`CastTokenServiceImpl`) — 발급 시 `effective-roles` 결과를 서명 JWT의 `roles` 클레임(`CastToken.CLAIM_ROLES`)으로 주입. 검증 시 `extractRoles(Claims)`가 `List<String>`만 필터(부재 시 `List.of()`). (`TTL` 300s)
- **Handoff 토큰** (`HandoffServiceImpl`) — `buildPlainPayload(...)`가 `"roles"` JSON 키로 유효 역할을 평문 페이로드에 삽입한 뒤 **AES-256-GCM 암호화**(ticketId AAD) + HMAC 서명. 역할은 암호화된 `encryptedPayload` 내부에 존재.
- **QAuthzClient fail-open** (`io.github.hipstermin.idem.hub.infrastructure.QAuthzClient`) — `GET {base-url}/api/v1/internal/authz/users/{qimUserId}/effective-roles?agencyCode=`. **절대 null 미반환**(항상 `emptyList`). q-authz 다운/타임아웃/non-2xx 등 모든 예외는 캐치되어 빈 역할로 fail-open → SSO/Handoff 발급 지속(인가 가용성과 인증 가용성 분리). `X-Internal-Api-Key`(비공백 시)·`X-Correlation-Id` 전송, 전용 RestTemplate(connect 3000ms / read 5000ms).

### 게이트웨이 속성 전파 (PEP)

`ExtProxyController` (`@RequestMapping("/api/ext")`) — FE → Q-IM 포워드 프록시. `injectAuthzHeaders(...)`가 인가 **속성을 전파만** 하고 경로별 인가를 **강제하지 않는다**(집행은 Q-IM/기관 PEP). 흐름: 쿠키 `feSessionId` → `qimUserId` → `effective-roles(scope=PLATFORM)`.

| 다운스트림 헤더 | 값 |
|------|-----|
| `X-Authz-User` | `qimUserId` |
| `X-Authz-Scope` | `PLATFORM` |
| `X-Authz-Roles` | `String.join(",", roles)` (빈 문자열 = 역할 없음) |

> **Anti-spoofing** — 클라이언트가 보낸 `X-Authz-*` 헤더(`x-authz-user/roles/scope`)는 포워드 헤더 구성 시 제거하고 서버 측에서만 재주입(`x-ext-api-key`도 동일). **Non-enforcing / fail-open** — 세션 없음 또는 q-authz 실패 시 헤더 없이 그대로 프록시.

### SCIM 2.0 Groups 동기화

`ScimGroupController`, base `/scim/v2/Groups`. **SCIM Group = q-authz 역할** (`id = "{agencyCode}:{roleCode}"`, `member.value = qimUserId`).

| Method | Path | 용도 |
|--------|------|------|
| `GET` | `/scim/v2/Groups/{id}` | 단일 그룹(역할) + 멤버 조회 |
| `GET` | `/scim/v2/Groups?agencyCode=` | 기관 범위 그룹 목록 (SCIM ListResponse) |
| `POST` | `/scim/v2/Groups` | 그룹(역할) + 초기 멤버 생성 (201) |
| `PUT` | `/scim/v2/Groups/{id}` | 전체 멤버 교체(reconcile) — 동기화 핵심 |
| `PATCH` | `/scim/v2/Groups/{id}` | `ScimPatchOp`로 멤버 추가/제거 |

> 인증은 내부 API와 동일하게 `X-Internal-Api-Key` 인터셉터(`/scim/v2/**`) 적용. 실제 grant/revoke는 `AuthzService`에 위임(출처 `SCIM`)되어 감사·멱등 일관 적용.

**SCIM 요청/응답 예시**

```json
// GET /scim/v2/Groups/GOV_SMES:MANAGER → 200
{ "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
  "id": "GOV_SMES:MANAGER", "displayName": "GOV_SMES:MANAGER",
  "members": [ { "value": "u-1024" }, { "value": "u-2048" } ] }

// PUT /scim/v2/Groups/GOV_SMES:MANAGER  (멤버 전체 교체 = reconcile)
{ "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"],
  "members": [ { "value": "u-1024" }, { "value": "u-3072" } ] }

// PATCH /scim/v2/Groups/GOV_SMES:MANAGER  (멤버 추가/제거)
{ "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [ { "op": "add", "path": "members", "value": [ { "value": "u-9000" } ] } ] }
```

> `PUT`은 현재 멤버 집합과 목표 집합의 차집합을 계산해 누락분은 grant, 초과분은 revoke(reconcile). 각 멤버 변경은 내부 `AuthzService.grantRole/revokeRole`로 위임되어 동일한 감사·아웃박스 이벤트 경로를 탄다. `PATCH remove`는 `members[value eq "u-9000"]` 경로 필터도 지원.

### 한시 권한 만료 + 이벤트 전파

**만료 스케줄러** (`AuthzExpiryScheduler`) — `expires_at`이 지난 `ACTIVE` 부여를 `EXPIRED`로 전이. 사이클당 `batch-size`까지 처리. `@Scheduled(fixedDelay=scan-interval-ms:60000, initialDelay=30000)`, `@ConditionalOnProperty(idem.authz.expiry.enabled, matchIfMissing=true)`. 예외는 흡수되어 다음 사이클 차단 안 함. (다중 인스턴스 동시 스캔 시 EXPIRE 감사 중복 가능 — ShedLock 권장, 현재 단일 리더 가정.)

**이벤트 전파 (회수 전파)** — 토큰 자연 만료 전에 역할 회수를 다운스트림(기관 게이트웨이·세션 캐시·ido)에 전파해 연합 인가의 회수 지연 약점을 해소.

- 부여/회수/만료 시 `AuthorizationEvent`(`IDEM_AUTHZ_GRANTED`/`IDEM_AUTHZ_REVOKED`/`IDEM_AUTHZ_EXPIRED`, `SOURCE_SYSTEM="q-authz"`)를 `AuthzOutboxService.publishInTx`로 **비즈니스 TX와 동일 트랜잭션**에 INSERT(REQUIRED 전파). 직렬화 실패 시 TX 롤백(이벤트 유실 방지).
- 이벤트는 **실제 상태 전이 시에만** 발행 — 멱등 no-op(이미 ACTIVE / 이미 REVOKED)에는 미발행(감사는 항상 기록).
- `AuthzKafkaRelayJob`(`outbox-relay-batch`)이 PENDING 행을 `FOR UPDATE SKIP LOCKED`(인스턴스 간) + **ShedLock**(Pod 간) 이중 보호로 폴링해 `idem.authz.assignment.events`로 릴레이. ShedLock 락 2종: `authz-kafka-relay`(주 릴레이), `authz-kafka-relay-failed`(실패 복구). 성공 → `PUBLISHED`, 실패 → retry 증가, `max-retry` 도달 시 `FAILED`. **q-authz는 Kafka 의존성 없음**(아웃박스 INSERT만).

```text
grant / revoke / expire (q-authz AuthzService)
        │  같은 TX
        ▼
authz_outbox (PENDING, topic=idem.authz.assignment.events)   ← q-authz는 INSERT만
        │  FOR UPDATE SKIP LOCKED + ShedLock
        ▼
AuthzKafkaRelayJob (outbox-relay-batch, @SchedulerLock)
        │  send(topic, partitionKey=qimUserId, payload)
        ▼
Kafka  idem.authz.assignment.events
        │
        ▼
downstream (기관 게이트웨이 · 세션 캐시 · ido) → 역할 무효화 (토큰 만료 전 회수 전파)
```

**이벤트 페이로드** (`AuthorizationEvent`, `@JsonInclude(NON_NULL)` — null 필드 생략)

```json
// idem.authz.assignment.events — REVOKED 예시 (Kafka partition key = qimUserId)
{ "eventId": "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b",
  "eventType": "IDEM_AUTHZ_REVOKED", "sourceSystem": "q-authz",
  "correlationId": "cid-abc", "qimUserId": "u-1024",
  "occurredAt": "2026-06-25T01:05:00Z",
  "agencyCode": "GOV_SMES", "roleCode": "MANAGER",
  "actor": "admin@onepass", "reason": "offboarding" }
```

> `GRANTED` 이벤트는 `expiresAt`·`source`를 추가 포함하고, `EXPIRED`는 `actor="SYSTEM"`. `eventVersion`은 authz 부여에 optimistic-lock 버전이 없어 항상 생략된다.

### 전체 시퀀스 (부여 → 토큰 → 집행 → 회수)

```text
[부여]   onepass-admin/SCIM ──▶ q-authz POST /grants ──▶ authz_user_role(ACTIVE) + authz_outbox(GRANTED)
[발급]   사용자 SSO ──▶ ido ──GET effective-roles──▶ q-authz
                          └─▶ CAST/Handoff 토큰에 roles[] 클레임 임베드 (fail-open: q-authz 다운 시 빈 역할)
[프록시] FE ──▶ ido /api/ext ──(X-Authz-User/Scope/Roles, anti-spoofing)──▶ Q-IM/기관 PEP가 집행
[만료]   AuthzExpiryScheduler ──ACTIVE & expires_at<now──▶ EXPIRED + authz_outbox(EXPIRED)
[회수]   q-authz DELETE /grants ──▶ REVOKED + authz_outbox(REVOKED)
                          └─▶ outbox-relay-batch ──▶ Kafka idem.authz.assignment.events ──▶ 다운스트림 무효화
```

> 토큰은 짧은 TTL(CAST 300s)로 발급되고, 회수 이벤트가 다운스트림 캐시를 능동 무효화하여 **TTL 만료 이전 회수 전파**를 달성한다(다운스트림 컨슈머는 후속 과제 — 잔여 작업 참조).

### 데이터 모델: `authz_user_role` (부여 SoR)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | UUID (PK) | 부여 식별자 |
| `qim_user_id` | varchar | 대상 사용자 (unique 키 1) |
| `agency_code` | varchar | 테넌트 (unique 키 2, RLS 기준) |
| `role_code` | varchar | 역할 (unique 키 3, 기관 불투명) |
| `status` | varchar | `ACTIVE`/`REVOKED`/`EXPIRED` (CHECK) |
| `granted_at` / `granted_by` | timestamptz / varchar | 부여 시각·행위자 |
| `expires_at` | timestamptz (nullable) | 한시/JIT 만료 — NULL=영구 |
| `revoked_at` / `revoked_by` | timestamptz / varchar | 회수 시각·행위자 |
| `source` | varchar | `CONSOLE`/`SCIM`/`API`/`AGENCY_PUSH` |

> 유효 역할 = `status==ACTIVE && (expires_at == null || expires_at > now)`. unique `(qim_user_id, agency_code, role_code)`로 멱등 부여 보장.

### 새로 구현된 파일 목록

| 모듈 | 파일 | 증분 |
|------|------|------|
| `q-authz` | `domain/{AuthzRoleEntity,AuthzUserRoleEntity,AuthzGrantAuditEntity,AssignmentStatus,GrantSource,AuditEvent}` | 1 |
| `q-authz` | `application/AuthzService` · `application/AuthzAuditService` | 1 |
| `q-authz` | `api/AuthzInternalController` + `api/dto/*` · `config/InternalApiKeyInterceptor` | 1 |
| `q-authz` | `db/migration/V1__create_authz_schema.sql` (역할/부여/감사 + RLS) | 1 |
| `ido` | `infrastructure/QAuthzClient` (fail-open) · CAST/Handoff `roles[]` 클레임 주입 | 2 |
| `ido` | `ExtProxyController` (`/api/ext` PEP 속성 전파 + anti-spoofing) | 2b |
| `q-authz` | `application/AuthzExpiryScheduler` (만료 전이) | 3 |
| `q-authz` | `api/scim/{ScimGroupController,ScimGroup,ScimMember,ScimListResponse,ScimPatchOp,ScimGroupId}` · `application/ScimGroupService` | 4 |
| `platform-common` | `event/AuthorizationEvent` | 5A |
| `q-authz` | `infrastructure/{AuthzOutboxEntity,AuthzOutboxRepository}` · `application/AuthzOutboxService` · `db/migration/V2__create_authz_outbox.sql` | 5A |
| `outbox-relay-batch` | `job/authz/AuthzKafkaRelayJob` · `config/BatchDataSourceConfig`(authz DS) · `config/BatchTransactionConfig`(authzTxMgr) | 5B |

### 운영 배포 필수 환경변수 (연합 인가)

```bash
# ── q-authz (포트 8086) ──────────────────────────────────────────────
# 내부 API 키 — 미설정/공백 시 /api/v1/internal/**, /scim/v2/** 전체 401 (fail-closed)
IDEM_AUTHZ_INTERNAL_API_KEY=<강력한 랜덤 키, openssl rand -hex 32>

# q-authz DB (미설정 시 DB_* → localhost:5432/onepass 폴백)
IDEM_AUTHZ_DB_HOST=<pg-host>
IDEM_AUTHZ_DB_PORT=5432
IDEM_AUTHZ_DB_NAME=onepass
IDEM_AUTHZ_DB_SSLMODE=require           # 운영 권장

# 한시 권한 만료 스케줄러
IDEM_AUTHZ_EXPIRY_ENABLED=true
IDEM_AUTHZ_EXPIRY_SCAN_INTERVAL_MS=60000
IDEM_AUTHZ_EXPIRY_BATCH_SIZE=500

# ── ido → q-authz 연동 ───────────────────────────────────────────────
IDEM_HUB_AUTHZ_BASE_URL=http://q-authz:8086
IDEM_HUB_AUTHZ_INTERNAL_API_KEY=<IDEM_AUTHZ_INTERNAL_API_KEY와 동일>   # 미설정 시 q-authz 401 → fail-open 빈 역할

# ── outbox-relay-batch (authz 아웃박스 → Kafka 릴레이) ───────────────
IDEM_AUTHZ_DB_USERNAME=onepass
IDEM_AUTHZ_DB_PASSWORD=<pg-password>
IDEM_RELAY_AUTHZ_KAFKA_RELAY_ENABLED=true
IDEM_AUTHZ_ASSIGNMENT_EVENTS_TOPIC=idem.authz.assignment.events
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
| `idem-registry/.../api/UserController.java` | 수정 | `find-by-social-sub` + `register-social` 엔드포인트 추가 |
| `idem-registry/.../api/dto/SocialRegisterRequest.java` | 신규 | 소셜 등록 요청 DTO |
| `idem-registry/.../repository/QimUserJpaRepository.java` | 수정 | `findByIdentifierHashAndProviderCode()` JPQL 쿼리 추가 |
| `idem-registry/.../config/InternalApiKeyInterceptor.java` | **신규** | `X-Internal-Api-Key` 상수 시간 비교 검증 인터셉터 (P2) |
| `idem-registry/.../config/QimWebMvcConfig.java` | **신규** | `/api/v1/internal/**` 인터셉터 등록 (P2) |
| `idem-registry/.../db/migration/V4__fix_social_sso.sql` | **신규** | `uq_identifier_hash` DROP → 복합 UNIQUE 추가 (P1) |
| `idem-registry/.../resources/application.yml` | 수정 | `idem.registry.security.internal-api-key` 설정 추가 (P2) |
| `idem-hub/.../infrastructure/QimClientImpl.java` | 수정 | `findBySocialSub()` + `registerSocialUser()` HTTP 클라이언트 |
| `idem-hub/.../broker/keycloak/KeycloakOidcService.java` | 수정 | `resolveQimUserIdFromSub()` — Q-IM 소셜 API 연동 |
| `idem-hub/.../api/HandoffController.java` | 수정 | `.redirectUri(req.getCallbackUrl())` 누락 수정 (P3) |
| `idem-hub/.../policy/PolicyEngineImpl.java` | 수정 | HMAC fallback 완전 제거, `tryResolveDi()` + GUEST 정책 |
| `idem-common/.../HandoffPayload.java` | 수정 | `HandoffState.GUEST` 추가 |
| `idem-tenant-sample/.../api/AgencyEntryController.java` | 수정 | `case GUEST` 분기 처리 추가 |

### 운영 배포 필수 환경변수

```bash
# IdO 서버
IDEM_HUB_BROKER_MODE=keycloak              # 필수 — 기본값 qsign, 이 설정 없으면 SSO 경로 비활성
IDEM_HUB_REGISTRY_INTERNAL_API_KEY=<32자+>      # Q-IM 내부 API 인증키

# Q-IM 서버
IDEM_REGISTRY_INTERNAL_API_KEY=<동일 키>        # IDEM_HUB_REGISTRY_INTERNAL_API_KEY와 반드시 동일

# Keycloak 연동 (application.yml idem.hub.keycloak.* 항목)
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
# ... 나머지 기관 (IDEM_HUB_FE_ALLOWED_RETURN_URLS_EXTRA 로 추가 주입 가능)

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
| F-01 IP Auth RL | `IDEM_HUB_AUTH_RL_ENABLED` | `false` | `true` | `@Value` + guard |
| F-02 기관별 RL | `IDEM_HUB_RATE_LIMIT_ENABLED` | `false` | `true` | `@Value` + guard |
| F-03 감사 로그 Kafka | `IDEM_HUB_AUDIT_KAFKA_ENABLED` | `false` | `true` | `@Value` + guard |
| F-04 감사 로그 DB | `IDEM_HUB_AUDIT_DB_ENABLED` | `false` | `true` | `@Value` + guard |
| F-05 OTel AOP | `IDEM_HUB_TRACING_AUTH_ASPECT_ENABLED` | `false` | `true` | `@ConditionalOnProperty` |
| F-08 Redisson | `IDEM_HUB_REDISSON_ENABLED` | `false` | `true` | `@ConditionalOnProperty` |
| F-10 보안 헤더 | `IDEM_HUB_SECURITY_HEADERS_ENABLED` | `false` | `true` | `@ConditionalOnProperty` |
| F-11 파기 스케줄러 | `IDEM_HUB_RETENTION_ENABLED` | `false` | `true` | `@Value` + guard |
| F-13 Outbox Relay | `IDEM_HUB_OUTBOX_RELAY_ENABLED` | `false` | `true` | `@Value` + guard |
| F-14 Webhook Relay | `IDEM_HUB_WEBHOOK_RELAY_ENABLED` | `false` | `true` | `@Value` + guard |

---

## 모듈 책임 분리

| 모듈 | SoR 역할 | 포트 | 핵심 책임 |
|------|---------|------|----------|
| `platform-common` | — | — | 공통 도메인·이벤트·에러코드·UUID v7 유틸, `HandoffPayload.GUEST` |
| `q-sign` | **인증 SoR** | 8081 | OIDC 브로커링, JWT 검증, PKCE, SLO Keycloak 전파 |
| `q-im` | **식별 SoR** | 8082 | qimUserId, CI AES-256-GCM v{n}, DI HMAC, 회원 원장, 소셜 계정 SSO API, InternalApiKeyInterceptor |
| `q-authz` | **인가/역할부여 SoR** | 8086 | 기관별 역할 카탈로그·사용자 역할 부여(한시/JIT 만료) 중앙 SoR, `effective-roles` 토큰 `roles[]` 소스, SCIM 2.0 Groups, 인가 이벤트 아웃박스(`idem.authz.assignment.events`), RLS 테넌트 격리·`X-Internal-Api-Key` fail-closed |
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
| JUnit 5 + Mockito | BOM 관리 | 단위 테스트 (q-im 219개 통과 + 30 skipped) |

### 프론트엔드 (`editions/idem-kr-portal/frontend/` — KR 회원 포털. 관리 콘솔 `idem-console-admin/` 은 React 19 + Vite + TypeScript, UI 라이브러리 없음)

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
├── idem-common/
│   └── src/main/java/io/github/hipstermin/idem/common/
│       ├── domain/           # AuthResult, HandoffPayload(+GUEST), HandoffTicket
│       ├── error/            # PlatformErrorCode
│       ├── event/            # AuthEvent, HandoffEvent, AuditLogEvent
│       └── util/             # UuidV7, ApiKeyHashValidator
│
├── idem-gate/                   # 인증 SoR (포트 8081)
│   └── src/main/java/io/github/hipstermin/idem/gate/
│       ├── broker/           # Keycloak OIDC 브로커
│       ├── kafka/            # Outbox + 멱등 컨슈머
│       ├── pkce/             # RFC 7636 PKCE
│       └── slo/              # SLO Keycloak end_session 전파
│
├── idem-registry/                     # 식별 SoR (포트 8082, PostgreSQL 스키마 qim)
│   └── src/main/java/io/github/hipstermin/idem/registry/
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
├── idem-hub/                      # 정책 오케스트레이터 + FE BFF (포트 8083)
│   ├── libs/
│   │   └── OACX-SDK-v1.3.2.jar
│   └── src/main/java/io/github/hipstermin/idem/idem-hub/
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
├── idem-authz/                  # 🆕 연합 인가(Federated Authorization) — 역할 부여 SoR (포트 8086, PostgreSQL authz)
│   └── src/main/java/io/github/hipstermin/idem/authz/
│       ├── QAuthzApplication.java          # @SpringBootApplication + @EnableScheduling
│       ├── api/
│       │   ├── AuthzInternalController.java # /api/v1/internal/authz (roles·grants·effective-roles)
│       │   └── scim/
│       │       └── ScimGroupController.java # 🆕 SCIM 2.0 Groups (/scim/v2/Groups)
│       ├── application/
│       │   ├── AuthzService.java            # 부여/회수 + 아웃박스 이벤트 발행
│       │   ├── AuthzExpiryScheduler.java    # 🆕 한시 권한 만료 전이(ACTIVE→EXPIRED)
│       │   ├── ScimGroupService.java        # 🆕 SCIM Group=역할 reconcile
│       │   └── AuthzOutboxService.java      # 🆕 트랜잭셔널 아웃박스 적재(회수 전파)
│       ├── domain/          # AuthzRole, AuthzUserRole, AuthzGrantAudit, AssignmentStatus
│       ├── infrastructure/  # JPA 리포지토리 + AuthzOutboxEntity
│       └── config/          # InternalApiKeyInterceptor(fail-closed), AuthzWebMvcConfig
│   └── src/main/resources/db/migration/
│       ├── V1__create_authz_schema.sql      # authz 스키마 + 역할/부여/감사 + RLS
│       └── V2__create_authz_outbox.sql      # 🆕 authz_outbox (회수 전파 아웃박스)
│   # NOTE: idem.authz.assignment.events Kafka 릴레이는 idem-relay/job/authz/AuthzKafkaRelayJob
│
├── idem-tenant-sample/              # 기관 시뮬레이터 (포트 8084)
│   └── src/main/java/io/github/hipstermin/idem/tenant/
│       └── api/AgencyEntryController.java   # ★SSO: GUEST case 분기 추가
│
├── idem-console-admin/         # 관리 콘솔 (React + Vite, S7 PR-2)
├── editions/idem-kr-portal/    # KR 회원 포털 (구 idem-console, React SPA)
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
├── idem-agent/                # 🆕 OnePass Agency Java Agent (독립 fat-JAR)
│   └── src/main/java/io/github/hipstermin/idem/agent/
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
├── idem-agent-testbed/        # 🆕 멀티 WAS Docker Compose 테스트베드
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
    ├── monitoring/
    │   ├── prometheus/
    │   ├── grafana/
    │   ├── loki/
    │   └── promtail/
    └── k6/                          # 부하 테스트
```

---

## 🆕 OnePass Agency Java Agent

> **모듈**: `idem-agent/` | **아티팩트**: `onepass-agent-{version}-all.jar` (~10MB fat-JAR)  
> **목적**: 유관기관 WAS에 **소스 코드 수정 없이** OnePass SSO를 적용하는 자바 에이전트  
> **JDK 지원**: JDK 1.5(JEUS 4/5) ~ JDK 21+(Tomcat 11, WildFly 28+)  
> **참고 문서**: [통합 가이드](./docs/idem-agent-integration-guide.md) | [아키텍처](./docs/internal/architecture/idem-agent-architecture.md) | [개발자 레퍼런스](./docs/internal/development/idem-agent-developer-reference.md)

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
./gradlew :idem-agent:agentJar
# → idem-agent/build/libs/onepass-agent-0.1.0-SNAPSHOT-all.jar

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
| 통합 가이드 | [`docs/idem-agent-integration-guide.md`](./docs/idem-agent-integration-guide.md) | 유관기관 개발자/관리자용 설치 가이드 |
| 워크스루 | [`docs/idem-agent-walkthrough.md`](./docs/idem-agent-walkthrough.md) | 단계별 설치·검증 워크스루 |
| 트러블슈팅 | [`docs/idem-agent-troubleshooting.md`](./docs/idem-agent-troubleshooting.md) | 문제 증상별 진단·해결 |
| 문서 인덱스 | [`docs/idem-agent-index.md`](./docs/idem-agent-index.md) | Agent 전체 문서 목차 |
| 아키텍처 설계서 | [`docs/internal/architecture/idem-agent-architecture.md`](./docs/internal/architecture/idem-agent-architecture.md) | 내부 아키텍처, 위빙 설계, 클래스로더 격리 |
| 개발자 레퍼런스 | [`docs/internal/development/idem-agent-developer-reference.md`](./docs/internal/development/idem-agent-developer-reference.md) | 새 WAS 추가, Javassist/byte-buddy 코딩 가이드 |

---

## 🆕 멀티 WAS 테스트베드

> **위치**: `idem-agent-testbed/` | **목적**: Docker Compose로 7개 WAS에 Agent 동시 검증  
> **참고**: [테스트베드 README](./idem-agent-testbed/README.md)

### 테스트베드 구성

```
idem-agent-testbed/
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
./gradlew :idem-agent:agentJar
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
| Q-IM | PostgreSQL 16 | 스키마 `qim` | **V1 기준선**(D1, 종전 MariaDB V1~V9 통합) |
| agency-stub | PostgreSQL 16 | `agency_stub` | **V2** — webhook + api_key |
| **q-authz** | PostgreSQL 16 | `authz` | **V2** — authz_outbox (회수 전파 아웃박스) (`🆕`) |

---

## Kafka 토픽

| 토픽 | 파티션 | 보존 | 생산자 | 소비자 |
|------|--------|------|--------|--------|
| `idem.gate.auth.events` | 12 | 1h | Q-Sign, **IdO(SSO)** | IdO |
| `idem.hub.handoff.events` | 12 | 1y | IdO | IdO → Webhook |
| `platform.session.advisory` | 12 | 24h | IdO | IdO |
| `platform.audit.log` | 12 | 2y | IdO | 감사 시스템 |
| `idem.registry.user.events` | 6 | Compacted | Q-IM | IdO, Q-Sign |
| `idem.registry.user.snapshot` | 6 | Compacted | Q-IM | (확장 예정) |
| `idem.registry.sp.member.events` | 6 | 30d | IdO | IdO |
| `idem.authz.assignment.events` | 12 | — | q-authz(아웃박스) → outbox-relay-batch | 기관 게이트웨이·세션 캐시·IdO (확장 예정) |
| *.dlq / *.dlt | 3~6 | 7d | 에러핸들러 | 운영 |

> **SSO 추가**: `idem.gate.auth.events` 토픽에 `AUTH_COMPLETED` 이벤트를 IdO(KeycloakOidcService)가 직접 발행 (Strategy B — Q-Sign 우회 없음).
>
> **연합 인가 추가 (`🆕`)**: `idem.authz.assignment.events` — q-authz가 인가 부여/회수/만료(`IDEM_AUTHZ_GRANTED`/`IDEM_AUTHZ_REVOKED`/`IDEM_AUTHZ_EXPIRED`)를 트랜잭셔널 아웃박스(`authz.authz_outbox`)에 적재하고 `outbox-relay-batch`(`AuthzKafkaRelayJob`)가 릴레이 발행. 파티션 키 = `qimUserId`. 다운스트림이 구독해 **토큰 자연 만료 이전에 역할 회수를 전파**. 토픽 자체는 아직 명시적 `NewTopic` 미선언(확장 예정).

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
| Rate Limiter (IP Auth) | `IDEM_HUB_AUTH_RL_ENABLED` Feature Flag | ✅ Sprint 9 FF |
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
| **q-authz** | **V1** | **`🆕` create_authz_schema** — `authz` 스키마 + `authz_role`/`authz_user_role`/`authz_grant_audit` + 인덱스 + RLS(테넌트 격리 GUC `app.current_agency`) |
| **q-authz** | **V2** | **`🆕` create_authz_outbox** — `authz_outbox` 트랜잭셔널 아웃박스(+ status 인덱스), 회수 전파용 (RLS 미적용 — 시스템 릴레이 전 테넌트 폴링) |

---

## 테스트 현황

| 모듈 | 테스트 수 | 최근 추가 |
|------|---------|---------|
| `ido` | **202개** | Sprint 7: NICE/OACX 32개 |
| `platform-common` | **59개** | UUID v7 27개 |
| `q-sign` | **23개** | SLO + PKCE |
| `q-im` | **219개** (+ 30 skipped) | **Sprint 12**: isMinor 3종(Fix 7) + S8 4종 + S9 6종 통합(Fix 8) |
| **`onepass-agency-sdk`** | **36개** | **v0.8.10** SDK GAP-1~5 수정 (HMAC 알고리즘, X-Event-Type, X-Correlation-ID, getBodyField, validateJson) |
| **`q-authz`** | **29개** (`🆕`) | **연합 인가**: AuthzService 10 / ScimGroupService 7 / Internal·Scim 컨트롤러 8 / ExpiryScheduler 2 / OutboxService 2 |
| **`outbox-relay-batch`** | **23개** (`🆕`) | **연합 인가**: `authz.authz_outbox` → `idem.authz.assignment.events` 릴레이 (FOR UPDATE SKIP LOCKED + ShedLock) |
| **합계** | **591개 + 30 skipped** | — |

> **※ 집계 기준**: `q-authz` 29 · `outbox-relay-batch` 23은 **현재 빌드 기준**(`./gradlew :idem-authz:test :idem-relay:test`, 0 실패). 상단 기존 모듈 행(`ido`·`platform-common`·`q-sign`·`q-im`)은 직전 스냅샷이며 현 빌드와 차이가 있을 수 있음 — 참고로 현재 빌드 기준 `platform-common`은 378, `ido`는 417로 증가(연합 인가 외 누적 반영). 합계 591은 표의 행 값 합.

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

> **설치해서 써 보려면** `docs/install.md` — compose 하나(`infra/docker/compose.install.yml`)로 PostgreSQL·Redis·Keycloak·SSO·IM 을 올린다. **Kafka 는 필요 없다**(`IDEM_KAFKA_ENABLED=false` 기본, D1-b). 아래는 개발자용 절차다.

### 1. 인프라 기동

```bash
# 개발용 전체 인프라 (PostgreSQL, Redis, Kafka, Keycloak) — 앱은 IDEM_KAFKA_ENABLED=true 로 Kafka 경로
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
./gradlew :idem-registry:bootRun       # 식별 서비스 :8082
./gradlew :idem-gate:bootRun     # 인증 서비스 :8081
./gradlew :idem-hub:bootRun        # 정책 오케스트레이터 :8083
./gradlew :idem-tenant-sample:bootRun # 기관 시뮬레이터 :8084
```

### 3. 프론트엔드 실행

```bash
cd idem-console-admin && npm ci && npm run dev      # 관리 콘솔 :3001 (/api → hub:8083)
cd editions/idem-kr-portal/frontend && yarn && yarn dev   # KR 회원 포털 (webpack proxy → hub:8083)
```

### 4. 로컬 환경변수

```yaml
# idem-hub/src/main/resources/application-local.yml
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
IDEM_HUB_BROKER_MODE=keycloak
IDEM_HUB_REGISTRY_INTERNAL_API_KEY=local-dev-key-change-in-production
IDEM_REGISTRY_INTERNAL_API_KEY=local-dev-key-change-in-production
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
  "eslint.workingDirectories": ["editions/idem-kr-portal/frontend"]
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
| **P2** | 연합 인가 다운스트림 컨슈머 (세션·게이트웨이 캐시 무효화) | IdO/게이트웨이 BE | `idem.authz.assignment.events` 구독 → 토큰 만료 이전 역할 회수 반영 (회수 전파 루프 완성) |
| **P2** | `idem.authz.assignment.events` 명시적 `NewTopic` 선언 | IdO/DevOps | 현재 미선언 — 파티션 12 / 키 `qimUserId` 명시 |
| **P2** | SCIM 2.0 Bearer 토큰 인증 | q-authz BE | 현재 `X-Internal-Api-Key`만 — 표준 SCIM Bearer 추가 |
| **P2** | onepass-admin PAP 콘솔 UI (역할 부여/회수) | FE/admin | q-authz Internal API 연동 관리 화면 |
| **P2** | 만료 스케줄러 ShedLock 적용 (다중 인스턴스) | q-authz BE | 현재 단일 리더 가정 — 동시 스캔 시 EXPIRE 감사 중복 방지 |
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
io.github.hipstermin.idem.hub.{기능}/
    {기능}Controller.java
    {기능}Service.java
    {기능}ServiceImpl.java
    dto/

// 2. 빈 네이밍: 인터페이스 기반
@Service
public class SloServiceImpl implements SloService { ... }

// 3. Feature Flag Guard 패턴
@Value("${idem.hub.slo.enabled:true}")
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
├── idem-sdk-java-usage-guide.md   # 현행 SDK 사용 가이드 (메인)
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

## 라이선스

Idem 은 [Apache License 2.0](LICENSE) 으로 배포됩니다. 제3자 구성요소 고지는 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), 저작권 고지는 [`NOTICE`](NOTICE) 에 있습니다.
관리 콘솔 프런트엔드는 SigNoz 프런트엔드(MIT)에서 파생했습니다. 벤더 SDK(NICE·OACX·Any-ID)는 이 저장소에 포함되지 않으며 각 사업자 조건을 따릅니다.
보안 취약점 신고는 [`SECURITY.md`](SECURITY.md), 기여 안내는 [`CONTRIBUTING.md`](CONTRIBUTING.md) 를 보십시오.

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
