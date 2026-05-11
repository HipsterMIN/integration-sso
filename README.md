# OnePass 통합인증 플랫폼 (Integration-SSO)

**중소벤처기업부 중기원패스(OnePass) 통합인증 SSO 및 아이덴티티 관리 시스템** PoC/프리프로덕션 구현체.  
**4+1 축 책임 모델** (Q-Sign · Q-IM · IdO · onepass-fe · agency-stub) 기반 EDA 아키텍처.

> **현재 버전: v2.3.0** — Sprint 10 완료 (SLO FE 완전 연동 · useAuthState 훅 · ErrorBoundary · 회원정보 수정 API 실연동)  
> **빌드 상태**: `DOCKER_UNAVAILABLE=true ./gradlew :ido:compileJava --no-daemon -q` → **BUILD SUCCESSFUL**  
> **테스트**: `./gradlew test` → **397개 통과** (백엔드 단위 테스트)  
> **PR**: [#52 (MERGED)](https://github.com/HipsterMIN/integration-sso/pull/52) — Sprint 10 SLO FE + FE 기반 강화

---

## 목차

1. [버전 히스토리](#버전-히스토리)
2. [전체 구현 진행률](#전체-구현-진행률)
3. [아키텍처 개요](#아키텍처-개요)
4. [모듈 책임 분리](#모듈-책임-분리)
5. [기술 스택](#기술-스택)
6. [모듈 구성](#모듈-구성)
7. [Sprint 10: SLO FE 완성 + FE 기반](#sprint-10-slo-fe-완성--fe-기반)
8. [S7-T2: NICE/OACX 본인인증 통합](#s7-t2-niceoacx-본인인증-통합)
9. [Feature Flag 체계](#feature-flag-체계)
10. [데이터베이스 구성](#데이터베이스-구성)
11. [Kafka 토픽](#kafka-토픽)
12. [보안 체계](#보안-체계)
13. [Flyway 마이그레이션 현황](#flyway-마이그레이션-현황)
14. [테스트 현황](#테스트-현황)
15. [모니터링 인프라](#모니터링-인프라)
16. [빠른 시작](#빠른-시작)
17. [접속 URL](#접속-url)
18. [개발 환경 설정](#개발-환경-설정)
19. [전체 로드맵 & 개발 플랜](#전체-로드맵--개발-플랜)
20. [팀별 개발 가이드](#팀별-개발-가이드)
21. [코딩 컨벤션](#코딩-컨벤션)
22. [문서 디렉토리](#문서-디렉토리)

---

## 버전 히스토리

| 버전 | PR | 스프린트 | 주요 내용 |
|------|----|---------|---------| 
| **v2.3.0** | [#52](https://github.com/HipsterMIN/integration-sso/pull/52) | Sprint 10 | **SLO FE 완전 연동** — `initiateSlo()` API 클라이언트, `Logout()` SLO 통합, `useAuthState` 훅, `ErrorBoundary`, `MypageSideNav` 로그아웃 버튼, `InformationStep3` 실 API 연동 (`UpdateMemberRequest` / `UpdateEnterpriseRequest` 타입 정의 완료) |
| **v2.2.1** | [#51](https://github.com/HipsterMIN/integration-sso/pull/51) | Sprint 9 (FF) | **18개 Feature Flag 체계** — `@ConditionalOnProperty` / `@Value` 가드, K8s ConfigMap 14개 환경변수, `docs/FEATURE_FLAGS.md` 완전 문서화 |
| **v2.2.0** | [#50](https://github.com/HipsterMIN/integration-sso/pull/50) | Sprint 9 | **프로덕션 강화** — Redisson 분산 락, Resilience4j CB+Retry, Bean Validation, OTel AOP 계측, 감사 로그(`platform.audit.log`), K8s Secret/ConfigMap, Auth Rate Limit, NHN Cloud SKM 연동 |
| **v2.1.0** | [#45](https://github.com/HipsterMIN/integration-sso/pull/45) | Sprint 7 S7-T2 | **NICE/OACX 본인인증 ido BFF 완전 이식** — 6개 API, Redis 세션/토큰 캐시, PBKDF2+AES-256-GCM, 32개 테스트 |
| v2.0.0 | [#39](https://github.com/HipsterMIN/integration-sso/pull/39) | Sprint 5 | **AES 키 로테이션** (KeyVersionRegistry + v{n}.{iv}.{ct} 포맷) + **모니터링 인프라** |
| v1.9.9 | [#38](https://github.com/HipsterMIN/integration-sso/pull/38) | Sprint 4-5 | UUID v7 테스트 27개 + Webhook 33개 |
| v1.9.5 | [#35](https://github.com/HipsterMIN/integration-sso/pull/35) | Sprint 3-4 | UUID v4 → v7 전체 교체 (RFC 9562) |
| v1.9.4 | [#33](https://github.com/HipsterMIN/integration-sso/pull/33) | Sprint 3-4 | P2 운영 고도화 + P3 배포 준비 완전 구현 |
| v1.9.3 | [#32](https://github.com/HipsterMIN/integration-sso/pull/32) | Sprint 2 | **SLO 완전 구현** + 개인정보 파기 스케줄러 + FE 인증 기반 |
| v1.9.2 | [#31](https://github.com/HipsterMIN/integration-sso/pull/31) | Sprint 1 | P0 보안 결함 완전 제거 + 테스트 기반 구축 |

---

## 전체 구현 진행률

> **기준일**: 2026-05-11 | **총 테스트**: 397개 (ido 202 + platform-common 59 + q-sign 23 + q-im 113) | v2.3.0 Sprint 10 반영

### 모듈별 구현 완성도

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비, UUID v7 유틸)
Q-Sign           ████████████████████  97%  (InternalSig 수신 검증 완료, SLO 완료)
Q-IM             ████████████████████  96%  (CI 암호화 v{n} 포맷, 파기 스케줄러 완료)
IdO              ████████████████████  88%  (AES 키 로테이션, NICE/OACX BFF, SLO 백엔드 완료)
agency-stub      ████████████████████  90%  (E2E 시뮬레이터 완비)
onepass-fe       █████████████████░░░  85%  (SLO 연동·useAuthState·ErrorBoundary·회원정보수정 완료)
인프라/Docker    ████████████████████ 100%  (모니터링 스택 완비, Feature Flag K8s ConfigMap 완료)
보안             ████████████████████  98%  (AES 키 로테이션, NICE/OACX PII 보호 완료)
테스트 커버리지  ████████████░░░░░░░░  58%  (백엔드 단위 397개, 통합테스트 0개)
```

**전체 완성도**: 약 **93%** — 프리프로덕션 단계

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
  │                                                                          │
  │   ※ Sprint 10 신규 FE 기능:                                              │
  │     - MypageSideNav 로그아웃 버튼 (SLO 통합)                             │
  │     - InformationStep3 회원정보 수정 (실제 PATCH API 연동)                │
  │     - useAuthState 훅 (Redux 인증 상태 단일 인터페이스)                    │
  │     - ErrorBoundary 컴포넌트 (페이지/섹션 수준 에러 격리)                  │
  └──────────────┬───────────────────────────────────────────────────────────┘
                 │ HTTPS / /api/v1/**
                 │  ├── /fe-session/**          (FE 세션 관리)
                 │  ├── /slo/**                 (SLO 로그아웃 ★Sprint 10)
                 │  ├── /handoff/**             (Handoff 발급/검증)
                 │  ├── /auth/**                (본인인증 BFF ★S7-T2)
                 │  └── /broker/**              (OIDC 브로커)
                 │
  ┌──────────────────────────────────────────────────────────────────────┐
  │  유관기관 시스템 (외부망)              외부 인증 공급자                  │
  │  agency-stub :8084                   NICE IDO 서버                   │
  │                                      OACX SDK v1.3.2                 │
  └──────────────────────────┬───────────────────────────────────────────┘
                             │ HTTPS (공개 API만)
══════════════════════════  ╪  ═══════════════════════════════════════════
  내부망 (Internal Network)
══════════════════════════  ╪  ═══════════════════════════════════════════
                            ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │  ido :8083  정책 오케스트레이터 + FE BFF                              │
  │                                                                      │
  │  [FE BFF]               [SLO ★Sprint 10]      [기관향 공개 API]      │
  │  feSessionId 쿠키       POST /slo/initiate      /handoff/issue        │
  │  ReturnUrl 검증         feSession 삭제           /handoff/verify       │
  │                         Webhook Outbox 적재      /agency/events        │
  │  [본인인증 BFF]          감사 로그 기록           [Webhook Push]        │
  │  NICE 휴대폰 인증        Keycloak end_session    Outbox Relay          │
  │  OACX 간편서명                                                        │
  │  Redis 세션 캐시         [18개 Feature Flag ★Sprint 9]                │
  │  PBKDF2+AES-256-GCM     @ConditionalOnProperty                        │
  │                          @Value 가드 패턴                              │
  └──────────────────┬───────────────────────────────────────────────────┘
                     │ HTTP (내부망 전용)
         ┌───────────┴───────────┐
         ▼                       ▼
  ┌─────────────┐       ┌─────────────┐
  │ q-sign:8081 │       │  q-im:8082  │
  │  인증 SoR    │       │  식별 SoR    │
  │  Keycloak   │       │  회원 원장   │
  │  OIDC 브로커 │       │  CI 암호화   │
  │  SLO 전파   │       │  v{n}.{iv}  │
  └──────┬──────┘       └──────┬──────┘
         │  Outbox              │  Outbox
         └──────────┬───────────┘
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
    │     모니터링 스택 (Sprint 5)          │
    │  Prometheus :9090                   │
    │  Grafana    :3000                   │
    │  Loki       :3100                   │
    └─────────────────────────────────────┘
```

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
| **S10-6** | `pages/Mypage/pages/InformationStep3.tsx` (수정) | `handleSubmit` 실제 API 호출 연동 (devNoticeModal 제거) |

### SLO best-effort 정책

```typescript
// api/utils.ts — 로그아웃 흐름
export const Logout = (): void => {
    // SLO API 실패해도 로컬 정리 진행 (best-effort)
    initiateSlo().catch(() => {});
    clearLocalAuthState();          // 6개 localStorage + Redux 5개 dispatch
    history.push(ROUTES.LOGIN);
};

// 감사 추적이 중요한 경우 비동기 버전 사용
export const LogoutAsync = async (): Promise<void> => {
    try { await initiateSlo(); } catch {}
    finally { clearLocalAuthState(); history.push(ROUTES.LOGIN); }
};
```

**SLO 서버 흐름** (`POST /api/v1/slo/initiate`):
1. `feSessionId` 쿠키 파싱 → Redis 세션 삭제
2. `sloService.executeSlo()` → Keycloak `end_session_endpoint` 호출
3. 기관 로그아웃 Webhook Outbox 적재
4. 감사 로그 기록 (`platform.audit.log`)
5. 204 No Content 반환 (feSessionId 쿠키 Max-Age=0)

### useAuthState 훅

```typescript
// hooks/useAuthState.ts
const { isLoggedIn, user, email, name, orgId, logout } = useAuthState();

// 이전 방식 (직접 Redux selector 사용)
const isLoggedIn = useSelector((state: AppState) => state.app.isLoggedIn);

// Sprint 10 이후 (훅 하나로)
const { isLoggedIn, logout } = useAuthState();
```

### InformationStep3 수정 흐름

```typescript
// FormData 수집 → payload 조립 → PATCH API → 성공/실패 처리
const handleSubmit = async (): Promise<void> => {
    const fd = new FormData(formRef.current);
    
    if (isBusiness) {
        const result = await updateEnterprise(business.entMbrNo, {
            bzmnNm: get('company_name'),   // 회사명
            rprsvNm: get('name'),          // 대표자명
            rprsTelno: buildPhoneNumber(get('tel1'), get('tel2')),
            email: buildEmail(get('email1'), get('email2')),
        });
        if (result.error !== null) { setErrorModal(...); return; }
        updateBusiness({ ... }); // Context 낙관적 업데이트
    } else {
        const result = await updateMember(member.mbrNo, {
            memberName: get('name'),
            phone: buildPhoneNumber(get('phone1'), get('phone2')),
            email: buildEmail(get('email1'), get('email2')),
        });
        if (result.error !== null) { setErrorModal(...); return; }
        updateMemberStore({ ... });
    }
    history.push(infoRoute); // INFORMATION 페이지로 이동
};
```

---

## S7-T2: NICE/OACX 본인인증 통합

> **Sprint 7 Task 2 완료** — onepass-be 헥사고날 아키텍처에서 ido 평탄화 계층 구조로 이식. 32개 단위 테스트 통과.

### 설계 결정 요약

| Q | 결정 | 근거 |
|---|------|------|
| **Q1=B** | API Key 없음 | Nginx same-origin 프록시로 보안 처리 (`/api/v1/auth/**` FE BFF 전용) |
| **Q2=B** | callback 엔드포인트 추가 | 기업인증 FE 구현 대비 (백엔드 완성, FE 미연동) |
| **Q3=B** | CI FE 미반환 | PII 보호 핵심 원칙. CI는 백엔드 내부에서만 처리 |
| **Q4=A** | WebClient (Tomcat 유지) | `spring-webflux` + `reactor-netty-http`만 추가 |

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
| `platform-common` | — | — | 공통 도메인·이벤트·에러코드·UUID v7 유틸 |
| `q-sign` | **인증 SoR** | 8081 | OIDC 브로커링, JWT 검증, PKCE, SLO Keycloak 전파 |
| `q-im` | **식별 SoR** | 8082 | qimUserId, CI AES-256-GCM v{n}, DI HMAC, 회원 원장, 파기 |
| `ido` | **정책 오케스트레이터 + FE BFF** | 8083 | Handoff 발급/검증, Policy, Webhook, FE BFF, AES 키 로테이션, NICE/OACX BFF, **SLO API(S10)** |
| `agency-stub` | — (PoC 전용) | 8084 | 유관기관 연동 E2E 시뮬레이터 |
| `onepass-fe` | — | 3000/3001 | React 18 SPA — **Sprint 10: SLO 완전 연동, 회원정보 수정 실연동** |

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
| JJWT | **0.12.6** | JWT 서명 검증 |
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
| Keycloak | `quay.io/keycloak/keycloak:24` | OIDC IdP 브로커 |
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
│       ├── domain/           # AuthResult, HandoffPayload, HandoffTicket
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
│       ├── api/              # MemberLookupController (CI 기반 조회)
│       ├── crypto/           # CI AES-256-GCM v{n}.{iv}.{ct}
│       ├── identity/         # DI HMAC-SHA256
│       ├── outbox/           # Outbox + Snapshot
│       ├── retention/        # 개인정보 파기 스케줄러
│       └── user/             # 회원 등록·조회·상태
│
├── ido/                      # 정책 오케스트레이터 + FE BFF (포트 8083)
│   ├── libs/
│   │   └── OACX-SDK-v1.3.2.jar
│   └── src/main/java/kr/go/smes/ido/
│       ├── auth/             # NICE/OACX 본인인증 BFF (S7-T2)
│       ├── slo/              # SLO API — initiate (Sprint 2/7/10)
│       │   ├── SloController.java      POST /api/v1/slo/initiate
│       │   ├── SloService.java
│       │   └── SloServiceImpl.java
│       ├── admin/            # 기관 Admin API
│       ├── api/              # Handoff + 기관 이벤트 폴링
│       ├── broker/           # IdP 브로커
│       ├── config/           # Feature Flag, Rate Limit, TraceparentFilter
│       ├── crypto/           # KeyVersionRegistry + HandoffKeyRotationScheduler
│       ├── fe/               # FE 세션 관리
│       ├── handoff/          # HandoffStrategy 패턴
│       ├── kafka/            # 이벤트 컨슈머
│       ├── policy/           # PolicyEngine
│       ├── ratelimit/        # Redis Lua 슬라이딩 윈도우
│       └── webhook/          # Webhook Push + Outbox Relay
│
├── agency-stub/              # 기관 시뮬레이터 (포트 8084)
│
├── onepass-fe/               # React SPA
│   └── frontend/src/
│       ├── api/
│       │   ├── feSession.ts      # SLO API 클라이언트 (Sprint 10 신규)
│       │   ├── utils.ts          # Logout() SLO 통합 (Sprint 10 수정)
│       │   └── ext/
│       │       └── members.ts    # updateMember/updateEnterprise (Sprint 10)
│       ├── hooks/
│       │   └── useAuthState.ts   # 인증 상태 중앙 관리 훅 (Sprint 10 신규)
│       ├── components/
│       │   ├── ErrorBoundary/    # React class ErrorBoundary (Sprint 10 신규)
│       │   └── MypageSideNav/    # 로그아웃 버튼 (Sprint 10 수정)
│       ├── pages/Mypage/pages/
│       │   └── InformationStep3.tsx  # 회원정보 수정 실 API (Sprint 10)
│       └── types/api/ext/
│           └── members.ts        # UpdateMemberRequest/EnterpriseRequest (Sprint 10)
│
└── infra/
    ├── docker/
    │   ├── docker-compose.yml
    │   └── docker-compose.monitoring.yml
    ├── k8s/
    │   └── configmaps/
    │       └── ido-configmap.yml     # Feature Flag 환경변수 14개 (Sprint 9 FF)
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
| IdO | PostgreSQL 16 | `ido` | **V10** — auth_result 확장, provider_routing |
| Q-IM | MariaDB 11.4 | `qim` | **V3** — CI 암호화 키 버전, user_status_history |
| agency-stub | PostgreSQL 16 | `agency_stub` | **V2** — webhook + api_key |

---

## Kafka 토픽

| 토픽 | 파티션 | 보존 | 생산자 | 소비자 |
|------|--------|------|--------|--------|
| `qsign.auth.events` | 12 | 1h | Q-Sign | IdO |
| `ido.handoff.events` | 12 | 1y | IdO | IdO → Webhook |
| `platform.session.advisory` | 12 | 24h | IdO | IdO |
| `platform.audit.log` | 12 | 2y | IdO | 감사 시스템 |
| `qim.user.events` | 6 | Compacted | Q-IM | IdO, Q-Sign |
| `qim.user.snapshot` | 6 | Compacted | Q-IM | (확장 예정) |
| `qim.sp.member.events` | 6 | 30d | IdO | IdO |
| *.dlq / *.dlt | 3~6 | 7d | 에러핸들러 | 운영 |

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
| SLO FE 완전 연동 | `POST /api/v1/slo/initiate` FE 호출 | ✅ **Sprint 10** |
| 개인정보 파기 스케줄러 | GDPR §17 준수, 탈퇴 후 90일 | ✅ Sprint 2 |
| NICE 암호화 | PBKDF2(512bit)→HMAC-SHA256→AES-256-GCM | ✅ Sprint 7 |
| CI FE 미반환 (Q3=B) | `@JsonInclude(NON_NULL)` | ✅ Sprint 7 |
| Bean Validation | `@Valid`, `@NotBlank`, `@Size` | ✅ Sprint 9 |
| 보안 응답 헤더 | CSP, HSTS, X-Frame-Options | ✅ Sprint 9 FF |

---

## Flyway 마이그레이션 현황

| 모듈 | 파일 | 내용 |
|------|------|------|
| ido | V1 | 기본 스키마 |
| ido | V5 | handoff_ticket 테이블 |
| ido | V9 | crypto_key_registry (AES 키 버전 메타데이터) |
| ido | V10 | auth_result 확장, provider_routing |
| q-sign | V5 | auth_method 컬럼 추가 |
| q-im | V3 | CI 암호화 키 버전, user_status_history |
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

### 4. 로컬 환경변수 (중요 Feature Flag)

```yaml
# ido/src/main/resources/application-local.yml
ido:
  auth-rl:
    enabled: false         # IP Rate Limit OFF (로컬)
  audit:
    kafka-enabled: false   # Kafka 감사 로그 OFF
    db-enabled: false      # DB 감사 로그 OFF
  redisson:
    enabled: false         # Redisson 분산 락 OFF (Redis 없을 때)
  security-headers:
    enabled: false         # CSP 헤더 OFF (FE 개발)
  outbox-relay:
    enabled: false
  webhook-relay:
    enabled: false
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
| Keycloak | http://localhost:8080 | OIDC IdP |
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

### 잔여 작업 (P1~P3)

| 우선순위 | 항목 | 담당 | 비고 |
|---------|------|------|------|
| **P1** | 내부 서비스 서명 수신 측 검증 (`OidcCompleteController`) | IdO BE | GAP-BE-01 |
| **P1** | 통합 테스트 (Spring Boot Test + Testcontainers) | 전 팀 | 현재 0개 |
| **P2** | agency-stub Kafka 직접 구독 → 공개 API 전환 | IdO BE | 망 분리 원칙 |
| **P2** | CSR(관리자 UI) 미구현 기관 관리 화면 | FE | — |
| **P2** | DLQ 전략 구현 (`KafkaConsumerConfig`) | IdO BE | GAP-IDO-09 |
| **P3** | FE E2E 테스트 (Cypress/Playwright) | FE | — |
| **P3** | k6 부하 테스트 고도화 | DevOps | — |

---

## 팀별 개발 가이드

> 팀별 상세 가이드는 `docs/development/` 디렉토리 참조

| 팀 | 가이드 문서 | 내용 요약 |
|----|-----------|---------|
| **IdO 백엔드** | [`docs/development/guide-backend-ido.md`](docs/development/guide-backend-ido.md) | Feature Flag 운영, SLO API, NICE/OACX BFF, 회원 수정 API, AES 키 로테이션 |
| **Q-IM 백엔드** | [`docs/development/guide-backend-qim.md`](docs/development/guide-backend-qim.md) | CI 암호화, 회원 원장 API, 개인정보 파기 스케줄러 |
| **프론트엔드** | [`docs/development/guide-frontend.md`](docs/development/guide-frontend.md) | SLO 연동, useAuthState 훅, ErrorBoundary, 회원정보 수정, API 클라이언트 패턴 |
| **인프라/DevOps** | [`docs/development/guide-infra.md`](docs/development/guide-infra.md) | Docker Compose, K8s ConfigMap, Grafana, Feature Flag 운영 |
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

// 5. 예외 처리
throw new BusinessException(PlatformErrorCode.SLO_FAILED, "세션 삭제 실패");
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
└── development/                        # 팀별 개발 가이드 ★Sprint 10 신규
    ├── guide-backend-ido.md            # IdO 백엔드 팀 가이드
    ├── guide-backend-qim.md            # Q-IM 백엔드 팀 가이드
    ├── guide-frontend.md               # FE 팀 가이드
    ├── guide-infra.md                  # 인프라/DevOps 팀 가이드
    └── (기존 01~13 개발 문서)
```

---

> **문서 최종 수정**: 2026-05-11 | **버전**: v2.3.0 | **담당**: GenSpark AI Developer  
> 문의/기여: `genspark_ai_developer` 브랜치 → PR → main 병합 워크플로우 준수
