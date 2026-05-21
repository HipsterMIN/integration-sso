# OnePass 통합인증 — 운영 배포 마스터 플랜 v1.0

> **작성일**: 2026-05-13  
> **현재 버전**: v3.1.0 (커밋 `d52c860`)  
> **목적**: 운영 환경 배포를 위한 전 스프린트 상세 계획  
> **최우선 목표**: 유관기관 SSO 완벽 동작 + 프로비저닝 양방향 통신 + SDK 배포

---

## 목차

1. [현재 상태 진단 (AS-IS)](#1-현재-상태-진단-as-is)
2. [목표 상태 (TO-BE)](#2-목표-상태-to-be)
3. [스프린트 로드맵 개요](#3-스프린트-로드맵-개요)
4. [Sprint 13 — Cross-Agency SSO 완성](#4-sprint-13--cross-agency-sso-완성)
5. [Sprint 14 — 전 기관 프로비저닝 (가입/전환 통합 배포)](#5-sprint-14--전-기관-프로비저닝-가입전환-통합-배포)
6. [Sprint 15 — 프로비저닝 양방향 API (Agency Gateway)](#6-sprint-15--프로비저닝-양방향-api-agency-gateway)
7. [Sprint 16 — 유관기관 SDK 설계 및 배포](#7-sprint-16--유관기관-sdk-설계-및-배포)
8. [Sprint 17 — 운영 보안 완성 + 인프라 강화](#8-sprint-17--운영-보안-완성--인프라-강화)
9. [Sprint 18 — 운영 배포 최종 검증](#9-sprint-18--운영-배포-최종-검증)
10. [운영 배포 체크리스트](#10-운영-배포-체크리스트)
11. [프로비저닝 API 제언 (양방향 설계)](#11-프로비저닝-api-제언-양방향-설계)
12. [유관기관 SDK 설계 제언](#12-유관기관-sdk-설계-제언)

---

## 1. 현재 상태 진단 (AS-IS)

### 1.1 완성된 핵심 컴포넌트

| 컴포넌트 | 구현 상태 | 운영 투입 가능 여부 |
|---------|---------|-----------------|
| Keycloak OIDC 브로커 (SSO 입구) | ✅ 완성 | ⚠️ 실 IdP 연동 미검증 |
| Handoff Ticket 발급/검증 | ✅ 완성 | ✅ 투입 가능 |
| 4종 HandoffStrategy (DIRECT/BRIDGE/APACHE_GATE/INTERNAL_SSO) | ✅ 완성 | ✅ 투입 가능 |
| GUEST 정책 (DI 없는 사용자 → 게스트 처리) | ✅ 완성 | ✅ 투입 가능 |
| 기관 API Key 인증 (PBKDF2 상수시간 비교) | ✅ 완성 | ✅ 투입 가능 |
| AES 키 로테이션 | ✅ 완성 | ✅ 투입 가능 |
| 보호자 인증 (P3-05) | ✅ 완성 | ✅ 투입 가능 |
| 기업회원 전환 (P3-06) | ✅ 완성 | ✅ 투입 가능 |
| GDPR 파기 (Fix 2 포함) | ✅ 완성 | ✅ 투입 가능 |
| Webhook 양방향 Push/Polling | ✅ 완성 | ⚠️ Relay 비활성 (FF) |
| Kafka Outbox + 멱등 컨슈머 | ✅ 완성 | ⚠️ DLQ 미완성 |
| Rate Limiting (기관별 TPS) | ✅ 완성 | ✅ 투입 가능 |
| Resilience4j CB/Retry | ✅ 완성 | ✅ 투입 가능 |
| Feature Flag 18개 | ✅ 완성 | ✅ 투입 가능 |

### 1.2 핵심 GAP — 운영 차단 항목

| GAP ID | 문제 | 심각도 | 해당 스프린트 |
|--------|------|--------|------------|
| **GAP-SSO-01** | 기관 A 로그인 후 기관 B 접속 시 재인증 없이 진입하는 Cross-Agency SSO 미구현 | 🔴 Critical | Sprint 13 |
| **GAP-SSO-02** | Keycloak 소셜 로그인 시 feSession만 생성되고 기관 세션 동기화 없음 | 🔴 Critical | Sprint 13 |
| **GAP-PROV-01** | 회원가입/전환 시 68개 기관 **전체** 대상 자동 프로비저닝 미구현 | 🔴 Critical | Sprint 14 |
| **GAP-PROV-02** | 기관 → OnePass 방향 (인바운드) 데이터 수신 API 미구현 | 🔴 High | Sprint 15 |
| **GAP-PROV-03** | OnePass → 기관 방향 (아웃바운드) 사용자 속성 동기화 미구현 | 🔴 High | Sprint 15 |
| **GAP-SDK-01** | 유관기관에 배포할 SDK (Java/Python/JS) 미개발 | 🟡 High | Sprint 16 |
| **GAP-SEC-01** | X-Internal-Sig 수신 측 검증 (IdO OidcCompleteController) 미구현 | 🔴 High | Sprint 17 |
| **GAP-SEC-02** | Kafka DLQ DeadLetterPublishingRecoverer 미연결 | 🟡 Medium | Sprint 17 |
| **GAP-OPS-01** | 운영 Keycloak realm 구성 문서화 없음 (실 Client ID/Secret) | 🔴 Critical | Sprint 13 |
| **GAP-OPS-02** | 기관별 실 API Key 발급 프로세스 없음 | 🔴 High | Sprint 17 |
| **GAP-OPS-03** | 68개 기관의 실제 API 엔드포인트 등록 DB 관리 없음 | 🔴 Critical | Sprint 14 |
| **GAP-TEST-01** | IdO/Q-Sign Testcontainers 통합 테스트 없음 | 🟡 Medium | Sprint 17 |
| **GAP-AGENT-01** | agency-stub Kafka 직접 구독 (망분리 원칙 위반) | 🟡 Medium | Sprint 17 |

### 1.3 Cross-Agency SSO 현재 문제 심층 분석

```
현재 흐름 (문제 있음):
  기관 A 로그인 → Keycloak → IdO → feSession(qimUserId) → 기관 A Handoff 발급
                                   ↕ feSession은 유지됨
  기관 B 접속 → HandoffController.issue() → feSession 존재 → OK (같은 브라우저)
  BUT: feSession 쿠키가 동일 도메인(ido:8083)에만 존재 → 기관 B가 /handoff/issue 직접 호출 시
       feSessionId 없으면 재인증 강요 → ❌ Cross-Agency SSO 실패

근본 원인:
  1. feSession은 HttpOnly 쿠키 → 브라우저가 ido.onepass.go.kr에만 전송
  2. 기관 B redirect 시 feSession 쿠키 포함 여부는 Same-Site 정책에 달림
  3. 기관이 /handoff/issue를 서버 사이드에서 호출하면 쿠키 없음 → 실패

해결 방향 (Sprint 13):
  → CrossAgency SSO Token (CAST) 패턴 구현
  → 기관 A가 Handoff 발급 후, 기관 B URL로 redirect 시 onepass_sso 쿼리 파라미터 추가
  → IdO가 onepass_sso 토큰을 검증하여 feSession 없이도 Handoff 재발급
```

---

## 2. 목표 상태 (TO-BE)

### 2.1 Cross-Agency SSO 완성 목표 흐름

```
════════════════════════════════════════════════════════════════════════
  Cross-Agency SSO 완성 흐름 (TO-BE)
════════════════════════════════════════════════════════════════════════

사용자 (브라우저)
    │
    │ ① 기관 A 접속 → 미인증 → OnePass 로그인 redirect
    ▼
IdO (Keycloak 브로커)
    │  SSO 인증 → feSession 발급 (qimUserId 보관)
    │  FeSession Cookie: onepass_session=xxx (HttpOnly, SameSite=Lax)
    │
    │ ② 기관 A Handoff 발급
    │  POST /api/v1/handoff/issue → ticketId=T_A
    │  기관 A 세션 발급 완료 (AGSID_A)
    │
    │ ③ 기관 A에서 기관 B로 이동 (SSO 연계 링크)
    │  Link: https://agency-b.go.kr/entry?onepass_sso=<CAST_TOKEN>
    │        CAST_TOKEN = JWT(qimUserId, exp=5min, sig=EdDSA)
    ▼
기관 B (브라우저 redirect)
    │
    │ ④ 기관 B → OnePass SSO 검증 요청 (CAST 검증)
    │  GET /api/v1/sso/cross-verify?token=<CAST_TOKEN>&agencyCode=AGENCY_B
    ▼
IdO (CrossAgencySsoController) ← [Sprint 13 신규]
    │  JWT 서명 검증 + 만료 확인 + 1회 소비(Redis SET NX)
    │  qimUserId 추출 → 기관 B Handoff 발급
    │  ticketId=T_B 반환
    ▼
기관 B (서버)
    │  /handoff/verify T_B → APPROVED + 속성
    │  기관 B 세션 발급 완료 (AGSID_B)
    ▼
사용자: 기관 A, B 모두 로그인 상태 ✅
════════════════════════════════════════════════════════════════════════
```

### 2.2 전 기관 프로비저닝 목표 흐름

```
════════════════════════════════════════════════════════════════════════
  전 기관 프로비저닝 흐름 (TO-BE — Sprint 14)
════════════════════════════════════════════════════════════════════════

사용자 회원가입 완료 / 기업회원 전환 완료
    │
    ▼
Q-IM (UserRegistrationService / BizMemberConversionService)
    │  Kafka 이벤트 발행: qim.user.events (USER_REGISTERED / BIZ_CONVERTED)
    ▼
IdO (QimUserEventConsumer) [Sprint 14 강화]
    │  이벤트 수신 → provisioningService.provisionToAll()
    │
    │  ┌─────────────────────────────────────────────────────────┐
    │  │  68개 기관 병렬 프로비저닝 (Virtual Thread Executor)      │
    │  │                                                         │
    │  │  For each active agency:                                │
    │  │    POST {agencyEndpoint}/api/v1/provision/users         │
    │  │    Body: { qimUserId, attributes, memberType, ... }     │
    │  │    Headers: X-Onepass-Key, X-Correlation-Id             │
    │  │                                                         │
    │  │  부분 실패 허용 (best-effort):                           │
    │  │    성공: provisioning_log COMPLETED                     │
    │  │    실패: provisioning_log FAILED → 재시도 큐 적재        │
    │  └─────────────────────────────────────────────────────────┘
    │
    ▼
provisioning_outbox 테이블 [Sprint 14 신규]
    │  실패 건 재시도 스케줄러 (지수 백오프 3회)
    │  3회 모두 실패 → DLQ + 관리자 알림
    ▼
완료: 사용자 68개 기관 전체 자동 등록
════════════════════════════════════════════════════════════════════════
```

### 2.3 프로비저닝 양방향 API 목표

```
════════════════════════════════════════════════════════════════════════
  프로비저닝 양방향 통신 (TO-BE — Sprint 15)
════════════════════════════════════════════════════════════════════════

  [아웃바운드: OnePass → 기관]              [인바운드: 기관 → OnePass]
  ─────────────────────────────           ──────────────────────────────

  1. 사용자 프로비저닝                      1. 사용자 정보 변경 수신
     POST /provision/users                    POST /api/v1/agency/inbound/users/{qimUserId}
                                               (기관이 사용자 정보 업데이트 통보)

  2. 사용자 속성 동기화                     2. 기관 자체 회원 데이터 Push
     PATCH /provision/users/{id}/attrs        POST /api/v1/agency/inbound/members
                                               (기관 내부 회원 데이터 OnePass 수신)

  3. 사용자 탈퇴 통보                       3. 서비스 이벤트 수신
     DELETE /provision/users/{id}             POST /api/v1/agency/inbound/events
                                               (기관이 서비스 이벤트를 OnePass에 보고)

  4. 상태 변경 알림 (Webhook)               4. 데이터 조회 요청
     POST {agencyWebhook}/status-change       GET /api/v1/agency/inbound/users/{qimUserId}
                                               (기관이 최신 사용자 정보 조회)

  5. 긴급 접근 차단                         5. 기관 자체 API 엔드포인트 등록/수정
     POST /provision/users/{id}/block         PUT /api/v1/agency/inbound/endpoints
                                               (기관 담당자가 자신의 API URL 등록)
════════════════════════════════════════════════════════════════════════
```

---

## 3. 스프린트 로드맵 개요

| Sprint | 기간 | 주제 | 핵심 딜리버블 |
|--------|------|------|-------------|
| **Sprint 13** | 2주 | Cross-Agency SSO 완성 | CAST 토큰, CrossAgency SSO API, Keycloak 실 연동 가이드 |
| **Sprint 14** | 2주 | 전 기관 프로비저닝 (가입/전환) | provisioningService, agency_endpoint_registry, 재시도 스케줄러 |
| **Sprint 15** | 2주 | 프로비저닝 양방향 API | Agency Gateway, 인바운드 API, 아웃바운드 동기화 |
| **Sprint 16** | 2주 | 유관기관 SDK | Java SDK, OpenAPI Spec, SDK 배포 파이프라인 |
| **Sprint 17** | 2주 | 운영 보안 완성 + 인프라 | X-Internal-Sig, DLQ, mTLS, K8s Helm, 부하테스트 |
| **Sprint 18** | 1주 | 운영 배포 최종 검증 | 스모크 테스트, 롤백 플랜, 운영 체크리스트 |

**총 일정**: 약 **11주** (2026-05-13 ~ 2026-07-31)

---

## 4. Sprint 13 — Cross-Agency SSO 완성

> **목표**: 기관 A 로그인 상태로 기관 B 접속 시 재인증 없이 SSO 동작  
> **PR**: `#86` 예정 | **브랜치**: `sprint/13-cross-agency-sso`

### 4.1 Cross-Agency SSO Token (CAST) 설계

#### CAST 토큰 구조

```java
// platform-common 신규
public record CastToken(
    String jti,          // UUID v7 — 1회 소비 보장
    String qimUserId,    // 인증된 사용자
    String sourceAgency, // 발급 기관 코드
    String targetAgency, // 대상 기관 코드 (optional, null=모든 기관 허용)
    Instant issuedAt,
    Instant expiresAt    // issuedAt + 5분 (고정)
)

// JWT Header: {"alg": "EdDSA", "typ": "CAST+JWT"}
// JWT Claims: CastToken 필드 + "iss": "onepass.ido"
// Signing Key: Ed25519 (PKCS#8 PEM, K8s Secret 관리)
```

#### CAST 토큰 생명주기

```
발급: POST /api/v1/sso/cast-issue
  → feSession 검증 (qimUserId 추출)
  → jti = UuidV7.generate()
  → Redis SET sso:cast:{jti} = "1" EX 300 (5분, NX)
  → JWT 서명 (EdDSA)
  → 반환: { castToken: "eyJ..." }

검증: GET /api/v1/sso/cross-verify?token=...&agencyCode=AGENCY_B
  → JWT 서명 검증
  → exp 확인
  → Redis GET sso:cast:{jti} → null이면 이미 소비됨 → 401
  → Redis DEL sso:cast:{jti} (1회 소비)
  → agencyCode로 Handoff 발급 → ticketId 반환
  → { ticketId: "...", agencyCode: "AGENCY_B", expiresAt: "..." }
```

### 4.2 신규 구현 파일 목록

#### platform-common

| 파일 | 유형 | 내용 |
|------|------|------|
| `common/domain/CastToken.java` | 신규 | Cross-Agency SSO 토큰 도메인 모델 |
| `common/error/PlatformErrorCode.java` | 수정 | `SSO_CAST_INVALID`, `SSO_CAST_EXPIRED`, `SSO_CAST_CONSUMED` 에러코드 추가 |

#### ido 모듈

| 파일 | 유형 | 내용 |
|------|------|------|
| `ido/sso/CastTokenService.java` | 신규 | CAST 토큰 발급/검증 서비스 인터페이스 |
| `ido/sso/CastTokenServiceImpl.java` | 신규 | EdDSA 서명, Redis 1회 소비, 5분 TTL |
| `ido/sso/CastTokenProperties.java` | 신규 | `ido.sso.cast.*` 설정 바인딩 (privateKeyPem, publicKeyPem, ttlSeconds) |
| `ido/api/CrossAgencySsoController.java` | 신규 | `POST /api/v1/sso/cast-issue`, `GET /api/v1/sso/cross-verify` |
| `ido/api/dto/CastIssueResponse.java` | 신규 | `{ castToken, expiresAt }` |
| `ido/api/dto/CrossVerifyResponse.java` | 신규 | `{ ticketId, agencyCode, expiresAt, qimUserId }` |
| `ido/config/CastKeyConfig.java` | 신규 | Ed25519 KeyPair Bean 등록 (PEM → KeyFactory) |

#### agency-stub 모듈 (SSO 수신 구현)

| 파일 | 유형 | 내용 |
|------|------|------|
| `agency/sso/CrossAgencySsoService.java` | 신규 | CAST 토큰 취득 후 OnePass SSO 검증 → ticketId → verify 흐름 |
| `agency/api/AgencySsoEntryController.java` | 신규 | `GET /sso-entry?onepass_sso=<token>` — 기관 B 진입점 |

### 4.3 Flyway 마이그레이션 (ido V14)

```sql
-- V14__add_cross_agency_sso.sql

-- CAST 토큰 감사 로그 (1회 소비 추적)
CREATE TABLE ido.cast_token_audit (
    jti              VARCHAR(36)   NOT NULL,
    source_agency    VARCHAR(50)   NOT NULL,
    target_agency    VARCHAR(50),
    qim_user_id      VARCHAR(36)   NOT NULL,
    issued_at        TIMESTAMPTZ   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    consumed_at      TIMESTAMPTZ,              -- null이면 미소비/만료 소멸
    consumed_by_ip   VARCHAR(50),
    outcome          VARCHAR(20)   NOT NULL DEFAULT 'ISSUED', -- ISSUED/CONSUMED/EXPIRED/REJECTED
    CONSTRAINT pk_cast_token_audit PRIMARY KEY (jti)
);

CREATE INDEX idx_cast_audit_qim ON ido.cast_token_audit (qim_user_id, issued_at DESC);
CREATE INDEX idx_cast_audit_agencies ON ido.cast_token_audit (source_agency, target_agency);

-- SSO 세션 링크 (한 사용자가 어느 기관에 세션을 갖는지 추적)
CREATE TABLE ido.sso_session_link (
    link_id          VARCHAR(36)   NOT NULL DEFAULT gen_random_uuid(),
    qim_user_id      VARCHAR(36)   NOT NULL,
    agency_code      VARCHAR(50)   NOT NULL,
    fe_session_id    VARCHAR(36)   NOT NULL,
    linked_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_activity_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_sso_session_link PRIMARY KEY (link_id),
    CONSTRAINT uq_sso_session_user_agency UNIQUE (qim_user_id, agency_code)
);

CREATE INDEX idx_sso_link_user ON ido.sso_session_link (qim_user_id) WHERE active = TRUE;
```

### 4.4 Keycloak 운영 연동 가이드

> **⚠️ 운영 투입 필수 설정 항목** (현재 모두 PoC 기본값)

#### Keycloak Realm 구성 체크리스트

```bash
# 1. Realm 생성
realm name: onepass
displayName: OnePass 통합인증

# 2. OIDC Client 생성 (ido-client)
clientId:           ido-client
clientProtocol:     openid-connect
accessType:         confidential
redirectUris:       https://ido.onepass.go.kr/api/v1/broker/callback
webOrigins:         https://ido.onepass.go.kr

# 3. 소셜 IdP 연동 (카카오)
providerId:         oidc
alias:              kakao
authorizationUrl:   https://kauth.kakao.com/oauth/authorize
tokenUrl:           https://kauth.kakao.com/oauth/token
clientId:           <카카오 앱 REST API 키>
clientSecret:       <카카오 앱 Client Secret>
defaultScopes:      openid profile
syncMode:           IMPORT

# 4. 소셜 IdP 연동 (네이버)
providerId:         oidc
alias:              naver
authorizationUrl:   https://nid.naver.com/oauth2.0/authorize
tokenUrl:           https://nid.naver.com/oauth2.0/token
clientId:           <네이버 앱 Client ID>
clientSecret:       <네이버 앱 Client Secret>
defaultScopes:      openid

# 5. Mapper 설정 (sub → qimUserId 매핑용)
type:               oidc-usermodel-attribute-mapper
tokenClaimName:     sub
userAttribute:      sub

# 6. Session 정책
ssoSessionMaxLifespan:      86400  (24시간)
ssoSessionIdleTimeout:      1800   (30분)
accessTokenLifespan:        300    (5분)
```

#### 운영 환경변수 (K8s Secret)

```yaml
# k8s/secrets/ido-secret.yaml (운영 배포 전 반드시 설정)
apiVersion: v1
kind: Secret
metadata:
  name: ido-secret
  namespace: onepass
type: Opaque
stringData:
  KEYCLOAK_CLIENT_SECRET: "<실 client secret — openssl rand -hex 32>"
  IDO_QIM_INTERNAL_API_KEY: "<32자 이상 랜덤 — openssl rand -hex 32>"
  IDO_HANDOFF_AES_KEY_V1: "<32바이트 — openssl rand -base64 32>"
  IDO_CAST_PRIVATE_KEY_PEM: |
    -----BEGIN PRIVATE KEY-----
    <Ed25519 PEM — openssl genpkey -algorithm ed25519>
    -----END PRIVATE KEY-----
  IDO_CAST_PUBLIC_KEY_PEM: |
    -----BEGIN PUBLIC KEY-----
    <Ed25519 공개키 PEM>
    -----END PUBLIC KEY-----
```

### 4.5 테스트 계획 (Sprint 13)

| 테스트 ID | 유형 | 시나리오 | 기댓값 |
|----------|------|---------|--------|
| S13-1 | E2E | 기관 A 로그인 → CAST 발급 → 기관 B 진입 | 기관 B Handoff 발급 성공 |
| S13-2 | E2E | 만료된 CAST 토큰 사용 | 401 SSO_CAST_EXPIRED |
| S13-3 | E2E | CAST 토큰 2회 사용 시도 | 401 SSO_CAST_CONSUMED |
| S13-4 | 단위 | CastTokenServiceImpl 서명/검증 | JWT 서명 일치 |
| S13-5 | 단위 | 잘못된 targetAgency CAST 사용 | 403 반환 |
| S13-6 | 통합 | Keycloak 카카오 로그인 → qimUserId 조회 | qimUserId 반환 (실 Keycloak 필요) |

---

## 5. Sprint 14 — 전 기관 프로비저닝 (가입/전환 통합 배포)

> **목표**: 회원가입 또는 전환 시 68개 기관 전체 자동 프로비저닝  
> **핵심 결정**: 비동기 + 부분 실패 허용 + 재시도 (at-least-once 보장)  
> **PR**: `#87` 예정 | **브랜치**: `sprint/14-full-provisioning`

### 5.1 설계 원칙

```
원칙 1: 비동기 비치명적 (Non-blocking, Best-effort)
  → 회원가입 자체는 즉시 완료. 프로비저닝은 백그라운드 비동기 처리.
  → 개별 기관 실패가 회원가입을 롤백시키지 않음.

원칙 2: At-least-once 보장
  → provisioning_outbox 테이블에 먼저 PENDING 상태로 저장
  → 성공 시 COMPLETED, 실패 시 FAILED + retry_count 증가
  → 최대 3회 재시도 (지수 백오프: 1분 → 5분 → 30분)
  → 3회 실패 → DEAD_LETTER + 관리자 알림

원칙 3: 기관별 독립 실패 격리
  → 기관 A 프로비저닝 실패가 기관 B에 영향 없음
  → 각 기관별 독립적 Circuit Breaker (Resilience4j 동적 생성)

원칙 4: 멱등성
  → 재시도 시 기관 측에서 중복 처리 방지 (X-Idempotency-Key 헤더)
  → OnePass 측에서도 provisioning_outbox에 중복 발송 방지 (UNIQUE 제약)
```

### 5.2 신규 DB 스키마 (ido V15 / q-im V7)

```sql
-- ido V15: agency_endpoint_registry + provisioning_outbox

-- 68개 기관 실제 API 엔드포인트 등록 테이블
CREATE TABLE ido.agency_endpoint_registry (
    agency_code          VARCHAR(50)   NOT NULL,
    endpoint_type        VARCHAR(50)   NOT NULL, -- PROVISION_USER, MEMBER_LOOKUP, SSO_SESSION, WEBHOOK
    endpoint_url         VARCHAR(500)  NOT NULL,
    auth_type            VARCHAR(20)   NOT NULL DEFAULT 'API_KEY', -- API_KEY, MTLS, OAUTH2
    api_key_hash         VARCHAR(300),           -- PBKDF2 해시 (아웃바운드 키)
    mtls_cert_thumbprint VARCHAR(200),           -- mTLS 클라이언트 인증서 지문
    timeout_ms           INTEGER       NOT NULL DEFAULT 5000,
    max_retries          SMALLINT      NOT NULL DEFAULT 3,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    health_check_url     VARCHAR(500),
    last_health_check_at TIMESTAMPTZ,
    health_status        VARCHAR(20)   NOT NULL DEFAULT 'UNKNOWN', -- HEALTHY, DEGRADED, DOWN, UNKNOWN
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agency_endpoint PRIMARY KEY (agency_code, endpoint_type)
);

-- 프로비저닝 Outbox (at-least-once 보장)
CREATE TABLE ido.provisioning_outbox (
    id               VARCHAR(36)   NOT NULL DEFAULT gen_random_uuid(),
    qim_user_id      VARCHAR(36)   NOT NULL,
    agency_code      VARCHAR(50)   NOT NULL,
    event_type       VARCHAR(50)   NOT NULL, -- USER_REGISTERED, USER_UPDATED, BIZ_CONVERTED, USER_WITHDRAWN
    payload          JSONB         NOT NULL,
    idempotency_key  VARCHAR(36)   NOT NULL, -- UuidV7 — 기관 측 중복 처리 방지
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING', -- PENDING, COMPLETED, FAILED, DEAD_LETTER
    retry_count      SMALLINT      NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_error       TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT pk_provisioning_outbox PRIMARY KEY (id),
    CONSTRAINT uq_prov_idempotency UNIQUE (idempotency_key, agency_code)
);

CREATE INDEX idx_prov_outbox_pending ON ido.provisioning_outbox (status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_prov_outbox_user ON ido.provisioning_outbox (qim_user_id, created_at DESC);
```

### 5.3 신규 구현 파일 목록

#### ido 모듈

| 파일 | 유형 | 내용 |
|------|------|------|
| `ido/provision/ProvisioningService.java` | 신규 | 프로비저닝 서비스 인터페이스 |
| `ido/provision/ProvisioningServiceImpl.java` | 신규 | 68개 기관 병렬 프로비저닝 (VThread) + Outbox 적재 |
| `ido/provision/ProvisioningOutboxRelay.java` | 신규 | PENDING/FAILED Outbox 재시도 스케줄러 (지수 백오프) |
| `ido/provision/AgencyProvisioningClient.java` | 신규 | 기관별 HTTP 클라이언트 (RestTemplate + CB + Retry) |
| `ido/provision/ProvisioningRequest.java` | 신규 | 프로비저닝 요청 DTO |
| `ido/provision/dto/UserProvisionPayload.java` | 신규 | 사용자 프로비저닝 페이로드 (속성 포함) |
| `ido/admin/AgencyEndpointAdminController.java` | 신규 | 기관 엔드포인트 등록/수정/삭제 관리 API |
| `ido/admin/dto/AgencyEndpointRequest.java` | 신규 | 엔드포인트 등록 요청 DTO |
| `ido/kafka/QimUserEventConsumer.java` | 수정 | USER_REGISTERED 이벤트 → ProvisioningService 호출 추가 |
| `ido/infrastructure/jpa/entity/AgencyEndpointJpaEntity.java` | 신규 | agency_endpoint_registry 엔티티 |
| `ido/infrastructure/jpa/entity/ProvisioningOutboxJpaEntity.java` | 신규 | provisioning_outbox 엔티티 |
| `ido/infrastructure/jpa/repository/AgencyEndpointJpaRepository.java` | 신규 | 엔드포인트 JPA |
| `ido/infrastructure/jpa/repository/ProvisioningOutboxJpaRepository.java` | 신규 | Outbox JPA |

#### q-im 모듈 (V7 마이그레이션)

```sql
-- q-im V7: provisioning_sync_status (Q-IM 관점 동기화 상태 추적)
CREATE TABLE qim.provisioning_sync_status (
    qim_user_id     VARCHAR(36)   NOT NULL,
    agency_code     VARCHAR(50)   NOT NULL,
    sync_type       VARCHAR(30)   NOT NULL, -- INITIAL, UPDATE, WITHDRAWAL
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    synced_at       TIMESTAMPTZ,
    CONSTRAINT pk_prov_sync PRIMARY KEY (qim_user_id, agency_code)
);
```

### 5.4 기관 API 규격 (OnePass → 기관 아웃바운드)

```http
POST {agencyEndpoint}/api/v1/onepass/provision/users
X-Onepass-Key: <PBKDF2 해시용 원문 키>
X-Idempotency-Key: <UuidV7>
X-Correlation-Id: <correlationId>
Content-Type: application/json

{
  "qimUserId": "01927b3c-...",
  "eventType": "USER_REGISTERED",   // USER_REGISTERED | BIZ_CONVERTED | USER_UPDATED | USER_WITHDRAWN
  "memberType": "INDIVIDUAL",       // INDIVIDUAL | BUSINESS
  "attributes": {
    "nameHash": "SHA-256(실명)",     // PII 최소화 — 해시만 전송
    "mobileHash": "SHA-256(010...)",
    "birthYear": 1990,
    "isMinor": false,
    "bizRegNo": null                 // 기업회원 전환 시에만 포함
  },
  "provisionedAt": "2026-05-13T12:00:00Z"
}

Response 200/201: { "agencyMemberId": "...", "status": "REGISTERED" }
Response 409:     { "code": "ALREADY_REGISTERED", "agencyMemberId": "..." }
Response 5xx:     → 재시도 큐 적재
```

### 5.5 테스트 계획 (Sprint 14)

| 테스트 ID | 유형 | 시나리오 | 기댓값 |
|----------|------|---------|--------|
| S14-1 | 통합 | USER_REGISTERED 이벤트 → 68개 기관 프로비저닝 | provisioning_outbox COMPLETED |
| S14-2 | 통합 | 기관 3개 실패 → 나머지 성공 | 실패 기관 FAILED, 성공 기관 COMPLETED |
| S14-3 | 통합 | 실패 Outbox 재시도 스케줄러 | 최대 3회 재시도 후 DEAD_LETTER |
| S14-4 | 단위 | 동일 idempotency_key 중복 발송 | UNIQUE 제약 → 스킵 |
| S14-5 | 단위 | 기업회원 전환 → BIZ_CONVERTED 프로비저닝 | bizRegNo 포함 페이로드 |
| S14-6 | 부하 | 100명 동시 가입 → 68개×100=6800 프로비저닝 | p99 < 5초 (비동기) |

---

## 6. Sprint 15 — 프로비저닝 양방향 API (Agency Gateway)

> **목표**: 기관 → OnePass(인바운드) + OnePass → 기관(아웃바운드) 완전 양방향 통신  
> **설계 원칙**: 기관은 단일 Gateway API를 통해 OnePass와 데이터를 주고받음  
> **PR**: `#88` 예정 | **브랜치**: `sprint/15-bidirectional-provisioning`

### 6.1 인바운드 API (기관 → OnePass)

```
기관이 OnePass에 데이터를 보내거나 조회하는 API 모음
Base Path: /api/v1/agency/gateway
인증: X-Agency-Code + X-Agency-Key (기존 HandoffAgencyKeyInterceptor 재사용)
```

#### 6.1.1 사용자 정보 변경 통보 (기관 → OnePass)

```http
PATCH /api/v1/agency/gateway/users/{qimUserId}/attributes
X-Agency-Code: AGENCY_STUB_001
X-Agency-Key: <API 키>
X-Correlation-Id: <correlationId>
Content-Type: application/json

{
  "agencyMemberId": "기관 내부 회원 ID",
  "changedAttributes": {
    "email": "hash:SHA-256(user@example.com)",  // 해시만 수신
    "phoneVerified": true,
    "lastLoginAt": "2026-05-13T10:00:00Z"
  },
  "changeReason": "SELF_UPDATE",  // SELF_UPDATE | ADMIN_UPDATE | MERGE
  "changedAt": "2026-05-13T10:00:00Z"
}

Response 200: { "accepted": true, "syncId": "..." }
Response 404: { "code": "USER_NOT_FOUND" }
Response 403: { "code": "ATTRIBUTE_NOT_ALLOWED" }  // allowed_attributes 정책
```

#### 6.1.2 기관 자체 이벤트 보고 (기관 → OnePass)

```http
POST /api/v1/agency/gateway/events
X-Agency-Code: AGENCY_STUB_001
X-Agency-Key: <API 키>
X-Idempotency-Key: <UUID>
Content-Type: application/json

{
  "eventType": "MEMBER_DEREGISTERED",  // 기관 내 탈퇴 이벤트
  "qimUserId": "01927b3c-...",
  "agencyMemberId": "기관 회원 ID",
  "payload": { "reason": "USER_REQUEST" },
  "occurredAt": "2026-05-13T10:00:00Z"
}

Response 202: { "received": true, "eventId": "..." }
```

#### 6.1.3 사용자 최신 정보 조회 (기관 → OnePass)

```http
GET /api/v1/agency/gateway/users/{qimUserId}
X-Agency-Code: AGENCY_STUB_001
X-Agency-Key: <API 키>

Response 200:
{
  "qimUserId": "01927b3c-...",
  "memberType": "INDIVIDUAL",
  "isMinor": false,
  "allowedAttributes": {             // agency_meta.allowed_attributes 필터링
    "birthYear": 1990,
    "isMinor": false,
    "memberType": "INDIVIDUAL"
  },
  "lastUpdatedAt": "2026-05-13T09:00:00Z",
  "status": "ACTIVE"
}
```

#### 6.1.4 기관 엔드포인트 자가 등록 (기관 → OnePass)

```http
PUT /api/v1/agency/gateway/endpoints
X-Agency-Code: AGENCY_STUB_001
X-Agency-Key: <API 키>
Content-Type: application/json

{
  "endpoints": [
    {
      "type": "PROVISION_USER",
      "url": "https://agency-a.go.kr/api/v1/onepass/provision/users",
      "timeoutMs": 5000
    },
    {
      "type": "WEBHOOK_RECEIVER",
      "url": "https://agency-a.go.kr/api/v1/webhook/onepass",
      "signingSecret": "webhook-secret-hash"
    }
  ]
}

Response 200: { "registered": 2, "endpoints": [...] }
```

### 6.2 아웃바운드 강화 (OnePass → 기관)

#### 6.2.1 사용자 속성 동기화 (OnePass → 기관)

```java
// ido/provision/OutboundSyncService.java (Sprint 15 신규)
public interface OutboundSyncService {
    // 단일 사용자 속성 동기화
    void syncUserAttributes(String qimUserId, String agencyCode, Map<String,Object> attrs);
    
    // 긴급 접근 차단 (탈퇴/정지 이벤트)
    void blockAccess(String qimUserId, String agencyCode, String reason);
    
    // 속성 변경 Webhook 전송
    void notifyAttributeChange(String qimUserId, List<String> changedFields);
}
```

#### 6.2.2 상태 변경 Webhook (OnePass → 기관)

```http
POST {agencyWebhookUrl}/onepass/status-change
X-Onepass-Signature: HMAC-SHA256(body, signingSecret)
X-Onepass-Timestamp: 1716595200
Content-Type: application/json

{
  "eventId": "01927b3c-...",
  "eventType": "USER_STATUS_CHANGED",
  "qimUserId": "01927b3c-...",
  "previousStatus": "ACTIVE",
  "newStatus": "WITHDRAWAL_SCHEDULED",
  "reason": "USER_REQUEST",
  "effectiveAt": "2026-05-13T12:00:00Z"
}
```

### 6.3 신규 구현 파일 목록 (Sprint 15)

#### ido 모듈

| 파일 | 유형 | 내용 |
|------|------|------|
| `ido/api/AgencyGatewayController.java` | 신규 | 기관 → OnePass 인바운드 통합 API |
| `ido/api/dto/gateway/AttributeUpdateRequest.java` | 신규 | 속성 변경 요청 DTO |
| `ido/api/dto/gateway/AgencyEventRequest.java` | 신규 | 기관 이벤트 보고 DTO |
| `ido/api/dto/gateway/EndpointRegistrationRequest.java` | 신규 | 엔드포인트 자가 등록 DTO |
| `ido/gateway/AgencyGatewayService.java` | 신규 | 인바운드 처리 서비스 인터페이스 |
| `ido/gateway/AgencyGatewayServiceImpl.java` | 신규 | 속성 변경 수신, 이벤트 Kafka 발행 |
| `ido/provision/OutboundSyncService.java` | 신규 | 아웃바운드 동기화 서비스 |
| `ido/provision/OutboundSyncServiceImpl.java` | 신규 | 속성 변경 Push + 긴급 차단 |

---

## 7. Sprint 16 — 유관기관 SDK 설계 및 배포

> **목표**: 68개 유관기관이 최소한의 코드로 OnePass SSO/프로비저닝을 연동할 수 있는 SDK 제공  
> **언어**: Java 17+, Python 3.9+, JavaScript/TypeScript (Node.js 18+)  
> **PR**: `#89` 예정 | **브랜치**: `sprint/16-agency-sdk`

### 7.1 SDK 아키텍처 설계

```
onepass-agency-sdk/
├── java/                        # Java SDK (Spring Boot 기관 대상)
│   ├── pom.xml                  # Maven 중앙 저장소 배포
│   └── src/
│       ├── OnePassClient.java   # 진입점 (Builder 패턴)
│       ├── auth/
│       │   ├── HandoffVerifier.java    # Ticket 검증
│       │   └── CastTokenClient.java    # Cross-Agency SSO
│       ├── provision/
│       │   ├── ProvisionReceiver.java  # 아웃바운드 수신 처리
│       │   └── ProvisionSender.java    # 인바운드 전송
│       ├── webhook/
│       │   └── WebhookVerifier.java    # HMAC-SHA256 서명 검증
│       ├── model/
│       │   ├── HandoffResult.java
│       │   ├── UserAttributes.java
│       │   └── ProvisionEvent.java
│       └── spring/              # Spring Boot AutoConfiguration
│           ├── OnePassAutoConfiguration.java
│           └── OnePassProperties.java
│
├── python/                      # Python SDK (Django/FastAPI 기관)
│   ├── pyproject.toml
│   └── onepass_sdk/
│       ├── __init__.py
│       ├── client.py
│       ├── auth.py              # Handoff 검증
│       ├── webhook.py           # HMAC 검증
│       └── provision.py
│
└── js/                          # JavaScript SDK (Express/Next.js 기관)
    ├── package.json
    └── src/
        ├── index.ts
        ├── handoff.ts
        ├── webhook.ts
        └── provision.ts
```

### 7.2 Java SDK — 핵심 API 설계

#### 7.2.1 Spring Boot AutoConfiguration

```java
// 기관 Spring Boot 앱에서 의존성 추가만으로 자동 설정
// build.gradle: implementation 'kr.go.smes:onepass-agency-sdk-spring:1.0.0'

// application.yml (기관 앱)
onepass:
  base-url: https://ido.onepass.go.kr
  agency-code: AGENCY_A_001
  api-key: <발급받은 API Key>
  webhook:
    signing-secret: <Webhook 서명 시크릿>
  handoff:
    verify-timeout-ms: 3000
  sso:
    cast-public-key: |   # IdO에서 배포하는 공개키 (PEM)
      -----BEGIN PUBLIC KEY-----
      ...
      -----END PUBLIC KEY-----
```

#### 7.2.2 Handoff 검증 (기관 서버 측)

```java
// 기관 컨트롤러에서 사용
@RestController
@RequiredArgsConstructor
public class AgencyEntryController {

    private final HandoffVerifier handoffVerifier;

    @GetMapping("/entry")
    public ResponseEntity<?> entry(@RequestParam String ticketId,
                                   @RequestHeader("X-Agency-Code") String agencyCode) {
        try {
            HandoffResult result = handoffVerifier.verify(ticketId, agencyCode);
            
            if (result.isApproved()) {
                String qimUserId = result.getQimUserId();
                Map<String, Object> attrs = result.getAllowedAttributes();
                // 기관 세션 생성 로직
                return ResponseEntity.ok(Map.of("status", "APPROVED", "memberId", qimUserId));
            } else if (result.isGuest()) {
                return ResponseEntity.ok(Map.of("status", "GUEST", "message", "회원가입이 필요합니다."));
            }
        } catch (HandoffExpiredException e) {
            return ResponseEntity.status(401).body("Ticket 만료");
        } catch (HandoffInvalidException e) {
            return ResponseEntity.status(400).body("유효하지 않은 Ticket");
        }
    }
}
```

#### 7.2.3 Cross-Agency SSO (기관 A → 기관 B 이동)

```java
// 기관 A에서 기관 B로 이동 링크 생성
@RestController
@RequiredArgsConstructor
public class AgencyNavigationController {

    private final CastTokenClient castTokenClient;

    @PostMapping("/navigate-to-agency-b")
    public ResponseEntity<?> navigateToAgencyB(
            @RequestHeader("Authorization") String sessionToken) {
        
        // feSessionId를 Authorization Bearer로 전달받아 CAST 발급 요청
        CastToken cast = castTokenClient.issue(sessionToken, "AGENCY_B_001");
        
        String redirectUrl = "https://agency-b.go.kr/sso-entry?onepass_sso=" + cast.getToken();
        return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    }
}
```

#### 7.2.4 Webhook 수신 (OnePass → 기관)

```java
// Spring Boot에서 @EnableOnePassWebhook 어노테이션으로 자동 등록
@OnePassWebhookHandler
public class MyWebhookHandler {

    @OnProvisionEvent(eventType = "USER_REGISTERED")
    public void onUserRegistered(ProvisionEvent event) {
        String qimUserId = event.getQimUserId();
        Map<String, Object> attrs = event.getAttributes();
        // 기관 DB에 회원 등록
    }

    @OnProvisionEvent(eventType = "USER_WITHDRAWN")
    public void onUserWithdrawn(ProvisionEvent event) {
        // 기관 DB에서 회원 비활성화
    }

    @OnStatusChange(fromStatus = "ACTIVE", toStatus = "SUSPENDED")
    public void onSuspended(StatusChangeEvent event) {
        // 기관 세션 강제 종료
    }
}
```

### 7.3 SDK 배포 파이프라인

```yaml
# .github/workflows/sdk-release.yml
name: SDK Release

on:
  push:
    tags:
      - 'sdk-v*'

jobs:
  build-java-sdk:
    runs-on: ubuntu-latest
    steps:
      - name: Build JAR
        run: cd onepass-agency-sdk/java && ./gradlew build
      - name: Publish to Maven Central
        run: cd onepass-agency-sdk/java && ./gradlew publishToMavenCentral
        env:
          SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
          SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
          GPG_KEY: ${{ secrets.GPG_KEY }}

  build-python-sdk:
    runs-on: ubuntu-latest
    steps:
      - name: Build and publish to PyPI
        run: cd onepass-agency-sdk/python && python -m build && twine upload dist/*
        env:
          TWINE_API_KEY: ${{ secrets.PYPI_API_KEY }}

  build-js-sdk:
    runs-on: ubuntu-latest
    steps:
      - name: Build and publish to npm
        run: cd onepass-agency-sdk/js && npm run build && npm publish
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

### 7.4 SDK 문서화 (기관 연동 가이드)

```
onepass-agency-sdk/
└── docs/
    ├── getting-started.md       # 5분 연동 가이드
    ├── java-integration.md      # Java 상세 가이드
    ├── python-integration.md    # Python 상세 가이드
    ├── js-integration.md        # JS 상세 가이드
    ├── cross-agency-sso.md      # Cross-Agency SSO 설명
    ├── provisioning-guide.md    # 프로비저닝 수신/발신 가이드
    ├── webhook-guide.md         # Webhook 서명 검증 가이드
    ├── api-reference/           # OpenAPI Spec 기반 자동 생성
    │   ├── openapi.yaml         # 기관 연동 API 전체 명세
    │   └── postman-collection.json
    └── migration/
        ├── from-smep-be.md      # 기존 smep-be → OnePass 마이그레이션
        └── faq.md
```

---

## 8. Sprint 17 — 운영 보안 완성 + 인프라 강화

> **목표**: 운영 투입 전 보안 취약점 제거 + 인프라 프로덕션 수준 강화  
> **PR**: `#90` 예정 | **브랜치**: `sprint/17-production-hardening`

### 8.1 보안 완성 항목

#### 8.1.1 X-Internal-Sig 수신 검증 (P1-03 — 오랜 미해결 GAP)

```java
// ido/config/InternalSigVerifyInterceptor.java (신규)
@Component
public class InternalSigVerifyInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, ...) {
        String sig = request.getHeader("X-Internal-Sig");
        String timestamp = request.getHeader("X-Internal-Timestamp");
        
        // 1. timestamp ±60초 검증
        long ts = Long.parseLong(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - ts) > 60) {
            throw new PlatformException(PlatformErrorCode.INTERNAL_SIG_TIMESTAMP_EXPIRED, "N/A");
        }
        
        // 2. HMAC-SHA256 재계산
        String expected = HmacSha256.compute(internalSigSecret, timestamp + ":" + body);
        if (!MessageDigest.isEqual(expected.getBytes(), sig.getBytes())) {
            throw new PlatformException(PlatformErrorCode.INTERNAL_SIG_INVALID, "N/A");
        }
        return true;
    }
}
// 적용 경로: /api/v1/internal/** (Q-IM → IdO 내부 호출 전용)
```

#### 8.1.2 Kafka DLQ 완전 구현

```java
// ido/kafka/config/KafkaConsumerConfig.java (수정)
@Bean
public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
    
    // 3회 재시도 (1s, 3s, 10s 지수 백오프)
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
    backOff.setInitialInterval(1_000L);
    backOff.setMultiplier(3.0);
    
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
    handler.addRetryableExceptions(RetryableException.class);
    handler.addNotRetryableExceptions(ValidationException.class, DeserializationException.class);
    return handler;
}
```

#### 8.1.3 mTLS 기관 인증 (고보안 기관 대상)

```nginx
# nginx/conf.d/agency-mtls.conf
server {
    listen 443 ssl;
    ssl_certificate       /certs/ido-server.crt;
    ssl_certificate_key   /certs/ido-server.key;
    ssl_client_certificate /certs/agency-ca.crt;   # 기관 CA 인증서
    ssl_verify_client      optional;               # optional: API Key도 허용
    
    location /api/v1/agency/gateway {
        # mTLS 인증 성공 시 X-Client-Cert-Fingerprint 헤더 추가
        proxy_set_header X-Client-Cert-Fingerprint $ssl_client_fingerprint;
        proxy_pass http://ido:8083;
    }
}
```

### 8.2 인프라 프로덕션화

#### 8.2.1 K8s Helm Chart 구조

```
helm/
├── onepass/
│   ├── Chart.yaml
│   ├── values.yaml              # 기본값
│   ├── values-prod.yaml         # 운영 오버라이드
│   └── templates/
│       ├── _helpers.tpl
│       ├── ido/
│       │   ├── deployment.yaml  # 3 replicas, podAntiAffinity
│       │   ├── service.yaml
│       │   ├── hpa.yaml         # CPU 70% 기준 2~10 replicas
│       │   ├── pdb.yaml         # maxUnavailable: 1
│       │   └── ingress.yaml     # TLS + rate limiting
│       ├── q-im/
│       ├── q-sign/
│       ├── agency-stub/         # PoC 환경에서만 배포
│       ├── kafka/
│       ├── redis/
│       └── secrets/
│           ├── ido-secret.yaml  # ExternalSecrets CRD 연동
│           └── qim-secret.yaml
```

#### 8.2.2 HPA 설정 (자동 스케일링)

```yaml
# helm/onepass/templates/ido/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ido-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ido
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
```

### 8.3 부하 테스트 (k6)

```javascript
// infra/k6/sso-load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '2m', target: 50 },   // 워밍업
    { duration: '5m', target: 200 },  // 목표 TPS
    { duration: '2m', target: 0 },    // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],  // p95<500ms, p99<1s
    http_req_failed: ['rate<0.01'],                   // 에러율 < 1%
  },
};

export default function() {
  // 시나리오 1: Handoff 발급/검증
  const issueRes = http.post('/api/v1/handoff/issue', JSON.stringify({
    agencyCode: 'AGENCY_STUB_001',
    correlationId: `load-${Date.now()}`,
  }), { headers: { 'X-Agency-Key': __ENV.AGENCY_KEY } });
  check(issueRes, { 'issue 200': (r) => r.status === 200 });

  // 시나리오 2: Cross-Agency SSO
  const castRes = http.get('/api/v1/sso/cross-verify?token=...');
  check(castRes, { 'cast 200': (r) => r.status === 200 });

  sleep(0.1);
}
```

---

## 9. Sprint 18 — 운영 배포 최종 검증

> **목표**: 실 운영 환경 배포 전 최종 검증 및 롤백 플랜 수립  
> **기간**: 1주  
> **브랜치**: `release/v4.0.0`

### 9.1 스모크 테스트 시나리오

```
✅ SMOKE-01: Keycloak 카카오 소셜 로그인 → qimUserId 생성 → feSession 발급
✅ SMOKE-02: Handoff Issue/Verify 정상 동작 (AGENCY_STUB_001)
✅ SMOKE-03: Cross-Agency SSO A→B (CAST 발급 → B Handoff)
✅ SMOKE-04: 회원가입 → 68개 기관 프로비저닝 Outbox PENDING 생성
✅ SMOKE-05: 프로비저닝 Outbox 재시도 스케줄러 동작
✅ SMOKE-06: Agency Gateway 인바운드 (기관 → OnePass 속성 업데이트)
✅ SMOKE-07: Webhook Push (상태 변경 → 기관 수신)
✅ SMOKE-08: Rate Limiting (기관 TPS 초과 → 429)
✅ SMOKE-09: 기관 API Key 만료/갱신 플로우
✅ SMOKE-10: Kafka DLQ 적재 → 관리자 알림 수신
✅ SMOKE-11: GDPR 탈퇴 → 68개 기관 USER_WITHDRAWN 프로비저닝
✅ SMOKE-12: 미성년자 보호자 인증 + SSO 진입
✅ SMOKE-13: 기업회원 전환 + SSO 진입
✅ SMOKE-14: HPA 스케일아웃 (부하 증가 → Pod 자동 증가)
✅ SMOKE-15: 롤백 (이전 버전 Helm rollback → feSession 유지 여부)
```

### 9.2 롤백 플랜

```bash
# 롤백 절차 (운영 이슈 발생 시)

# 1. Helm 롤백 (30초 내 가능)
helm rollback onepass -n onepass

# 2. Flyway 롤백 (필요 시)
# 주의: Flyway는 순방향만 지원 → 롤백용 별도 down migration 준비 필요
# V14__add_cross_agency_sso.sql → V14_undo__drop_cast_tables.sql

# 3. 기관에 공지 (슬랙/이메일 자동화)
# scripts/notify-agencies.sh
```

### 9.3 모니터링 알림 설정

```yaml
# infra/monitoring/prometheus/alerts/onepass-rules.yaml
groups:
  - name: onepass-critical
    rules:
      - alert: HandoffVerifyErrorRate
        expr: rate(http_server_requests_total{uri="/api/v1/handoff/verify",status="5xx"}[5m]) > 0.05
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Handoff 검증 에러율 5% 초과"

      - alert: ProvisioningDead
        expr: count(provisioning_outbox_status{status="DEAD_LETTER"}) > 10
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "프로비저닝 DEAD_LETTER {{ $value }}건 발생"

      - alert: CrossAgencySsoFailure
        expr: rate(cast_token_consumed_total{outcome="REJECTED"}[5m]) > 0.1
        for: 3m
        labels:
          severity: critical
        annotations:
          summary: "Cross-Agency SSO 거부율 10% 초과"

      - alert: KafkaDlqBuildup
        expr: kafka_consumer_group_lag{topic=~".*\\.dlq"} > 100
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Kafka DLQ 적체 {{ $value }}건"
```

---

## 10. 운영 배포 체크리스트

### 10.1 배포 D-7 (1주 전)

- [ ] Keycloak 운영 realm 구성 완료 (카카오/네이버 실 Client ID/Secret)
- [ ] 운영 DB 비밀번호 변경 (onepass → 32자 랜덤)
- [ ] 운영 AES/HMAC 키 교체 (change-me → openssl rand -base64 32)
- [ ] Ed25519 CAST 키 페어 생성 및 K8s Secret 등록
- [ ] 68개 기관 API 엔드포인트 실 URL 등록 (agency_endpoint_registry)
- [ ] 68개 기관별 API Key 발급 및 안전 전달 (암호화 이메일 or 직접 전달)
- [ ] SSL 인증서 유효성 확인 (운영 도메인: ido.onepass.go.kr)
- [ ] Grafana 알림 채널 설정 (슬랙 Webhook + 이메일)
- [ ] 부하 테스트 최종 통과 확인 (200 TPS, p99 < 1s)

### 10.2 배포 D-1 (하루 전)

- [ ] 스테이징 환경 스모크 테스트 15종 전체 통과
- [ ] Helm values-prod.yaml 최종 검토
- [ ] 롤백 스크립트 검증 (`helm rollback --dry-run`)
- [ ] DBA 최종 Flyway 마이그레이션 검토 (V14~V18)
- [ ] 유관 기관 담당자 배포 일정 공지
- [ ] 온콜 담당자 지정 (배포 후 24시간)

### 10.3 배포 당일

- [ ] `00:00` — 유지보수 공지 (3시간 예정)
- [ ] `00:05` — DB 백업 스냅샷
- [ ] `00:10` — Helm 배포 실행 (`helm upgrade onepass ./helm/onepass -f values-prod.yaml`)
- [ ] `00:30` — 스모크 테스트 실행
- [ ] `01:00` — 주요 기관 담당자와 SSO 동작 확인
- [ ] `02:00` — 모니터링 정상 확인 후 유지보수 해제
- [ ] `03:00` — 운영 안정화 모니터링 (4시간)

### 10.4 배포 후 1주일

- [ ] 일별 에러율 확인 (< 0.1% 목표)
- [ ] 프로비저닝 성공률 확인 (> 95% 목표)
- [ ] 기관별 SSO 동작 확인 (68개 기관 대표 사례)
- [ ] DLQ 적체 없음 확인
- [ ] SDK 사용 기관 피드백 수집

---

## 11. 프로비저닝 API 제언 (양방향 설계)

### 11.1 API 버전 관리 전략

```
/api/v1/agency/gateway/...    ← 현재 (v1, 안정화 이후 deprecation 없음)
/api/v2/agency/gateway/...    ← 향후 (Breaking change 시에만)

버전 협상: Accept: application/vnd.onepass.v1+json
```

### 11.2 인바운드 보안 계층

```
기관 → OnePass 인바운드 요청 보안 계층 (중요도 순):

Layer 1: TLS 1.3 (필수)
  → 모든 통신 암호화

Layer 2: X-Agency-Key 검증 (필수)
  → PBKDF2 상수 시간 비교 (기존 HandoffAgencyKeyInterceptor 재사용)

Layer 3: X-Idempotency-Key (상태 변경 API 필수)
  → UUID v7, 24시간 내 중복 요청 방지
  → Redis SET NX TTL 86400

Layer 4: Request Signing (고보안 기관 선택)
  → HMAC-SHA256(body + timestamp, agencySecret)
  → ±30초 타임스탬프 검증

Layer 5: mTLS (국방/금융 등 초고보안 기관)
  → 클라이언트 인증서 필수
  → Nginx에서 검증 후 fingerprint 헤더 전달
```

### 11.3 데이터 최소화 원칙 (인바운드 수신 시)

```
OnePass가 기관으로부터 수신하는 데이터 정책:

✅ 수신 가능:
  - agencyMemberId (기관 내부 식별자)
  - 해시값 (이메일/전화 SHA-256)
  - 이진 상태 (verified: true/false)
  - 이벤트 타임스탬프

❌ 수신 거부 (PII 최소화):
  - 실명
  - 주민번호/외국인등록번호
  - 실 이메일/전화번호 평문
  - 위치 정보
  - 금융 정보

처리: GlobalExceptionHandler에서 금지 필드 포함 시 422 Unprocessable Entity
```

### 11.4 기관 엔드포인트 상태 모니터링

```java
// 기관 API 엔드포인트 헬스체크 스케줄러 (Sprint 15 포함)
@Scheduled(fixedDelay = 300_000) // 5분마다
public void checkAgencyEndpointHealth() {
    agencyEndpointRegistry.getAllActive().parallelStream().forEach(endpoint -> {
        try {
            ResponseEntity<Void> resp = restTemplate.getForEntity(
                endpoint.getHealthCheckUrl(), Void.class);
            updateHealthStatus(endpoint.getAgencyCode(), 
                resp.getStatusCode().is2xxSuccessful() ? "HEALTHY" : "DEGRADED");
        } catch (Exception e) {
            updateHealthStatus(endpoint.getAgencyCode(), "DOWN");
            // Circuit Breaker 열기 → 해당 기관 프로비저닝 일시 중단
            circuitBreakerRegistry.circuitBreaker(endpoint.getAgencyCode()).transitionToOpenState();
        }
    });
}
```

---

## 12. 유관기관 SDK 설계 제언

### 12.1 SDK 연동 5분 퀵스타트 (Java)

```java
// 1. 의존성 추가 (build.gradle)
// implementation 'kr.go.smes:onepass-agency-sdk-spring:1.0.0'

// 2. application.yml
// onepass:
//   base-url: https://ido.onepass.go.kr
//   agency-code: AGENCY_A_001
//   api-key: ${ONEPASS_API_KEY}

// 3. 끝! Handoff 검증은 아래처럼:
@Autowired HandoffVerifier handoffVerifier;

HandoffResult result = handoffVerifier.verify(ticketId, agencyCode);
// result.isApproved() → true이면 로그인 처리
// result.getQimUserId() → 사용자 고유 ID
// result.getAllowedAttributes() → 허용된 속성만 필터링된 맵
```

### 12.2 SDK 오류 처리 설계

```java
// SDK 예외 계층 구조
OnePassException (기본)
├── HandoffExpiredException    → 재로그인 요청
├── HandoffInvalidException    → 잘못된 Ticket
├── HandoffAgencyMismatch      → 다른 기관 Ticket 사용
├── SsoCastExpiredException    → CAST 토큰 만료 → 재발급 요청
├── SsoCastConsumedExceptoin   → 이미 소비된 CAST → 재발급 요청
├── RateLimitExceededException → 429, 재시도 안내
└── OnePassUnavailableException → 503, Circuit Breaker Open
```

### 12.3 SDK 버전 정책

```
Semantic Versioning: MAJOR.MINOR.PATCH

MAJOR: 하위 호환 깨는 변경 (API 계약 변경, 데이터 구조 변경)
MINOR: 하위 호환 유지 신기능 (새 이벤트 타입, 새 필드 추가)
PATCH: 버그 수정

릴리즈 채널:
  stable:  maven central (기관 프로덕션 사용)
  preview: github packages (신기능 미리보기)

지원 정책:
  현재 major 버전: 24개월 지원
  이전 major 버전: 12개월 보안 패치만 지원
```

---

## 13. 추가 고려 사항 (심층 분석 결과)

### 13.1 기관 API Key 발급 프로세스 (운영 필수)

```
현재 문제: API Key 발급이 DB 직접 INSERT 방식 (운영 위험)

제안 프로세스:
  1. 기관 담당자 → OnePass 관리자 포털에서 신청
  2. 관리자 승인 → API Key 자동 생성 (AgencyAdminService.rotateKey())
  3. Key는 1회만 표시 (이후 해시만 저장) → 기관 담당자가 즉시 복사
  4. Key 만료 정책: 1년 (만료 30일 전 이메일 알림)
  5. Key 로테이션: 기관 요청 시 또는 유출 의심 시 즉시 교체

구현:
  - AgencyAdminController에 POST /api/v1/admin/agencies/{code}/rotate-key 추가
  - 신규 Key는 응답 바디에 1회만 포함 (이후 해시만 저장)
  - 이메일 알림: Spring Mail + 템플릿
```

### 13.2 feSession vs CAST 토큰 세션 관리 통합

```
현재 문제: 기관 A에서 기관 B로 이동 시 feSession 쿠키 전달 방식 불명확

제안 통합 전략:
  Option A (권장): CAST 토큰 방식 (Sprint 13 구현)
    → feSession 쿠키 없이도 CAST 토큰으로 Cross-Agency SSO 가능
    → 기관이 직접 /sso/cast-issue 호출 후 redirect URL 구성

  Option B: 공유 세션 도메인 (복잡도 높음, 미권장)
    → ido.onepass.go.kr 쿠키를 기관 도메인에서 접근 가능하게
    → CORS + SameSite=None + Secure 설정 필요
    → 보안 리스크 (XSS 취약)

  결론: Option A (CAST 토큰) 채택
```

### 13.3 SLO (Single Logout) 전파 완성

```
현재 문제: SLO 시 기관 세션 로그아웃이 Webhook으로만 동작 (비동기, 실패 가능)

강화 방안 (Sprint 17 포함):
  1. SLO 요청 → sso_session_link 조회 → 활성 세션 기관 목록
  2. 모든 기관에 동기 로그아웃 요청 (3초 타임아웃)
  3. 실패 기관은 Outbox 적재 → 재시도
  4. SLO 완료 감사 로그 (어느 기관까지 로그아웃됐는지)

구현 위치: SloServiceImpl + sso_session_link 테이블 연동
```

### 13.4 관리자 포털 (Admin Console) 로드맵

```
Sprint 17: 백엔드 Admin API 완성
  - 기관 등록/수정/비활성화
  - API Key 발급/로테이션/만료 관리
  - 프로비저닝 상태 모니터링 (기관별 성공/실패율)
  - DLQ 건별 재처리

Sprint 18 이후 (FE): Admin Console UI
  - React 기반 관리자 화면
  - 기관별 SSO 동작 로그 조회
  - 실시간 알림 (Webhook 실패, DLQ 증가)
```

---

*문서 작성: 2026-05-13 | 버전: v1.0 | 담당: GenSpark AI Developer*  
*기준 코드베이스: integration-sso v3.1.0 (fafc795 + d52c860)*
