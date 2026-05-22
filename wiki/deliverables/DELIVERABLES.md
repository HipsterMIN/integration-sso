# 산출물 마스터 인덱스 — OnePass 통합인증 플랫폼

| 항목 | 내용 |
|------|------|
| **프로젝트** | OnePass 통합인증 플랫폼 (integration-sso) |
| **버전** | v0.9.2 (genspark_ai_developer: v0.8.11) |
| **최종 갱신** | 2026-05-18 |
| **관리 브랜치** | `shipster` / `genspark_ai_developer` |
| **위키 PR** | #131 (genspark_ai_developer → main: 분석 보고서) / #122 (shipster: mTLS) |
| **테스트 상태** | ✅ 950 tests PASS (0 failures, 0 errors, 0 skipped) — onepass-agency-sdk 36개 포함 |

---

## 목차

1. [산출물 현황 요약](#1-산출물-현황-요약)
2. [아키텍처 문서](#2-아키텍처-문서)
3. [ADR (Architecture Decision Record)](#3-adr-architecture-decision-record)
4. [서비스별 상세 설계서](#4-서비스별-상세-설계서)
5. [워크스루 문서](#5-워크스루-문서)
6. [DB 마이그레이션 이력](#6-db-마이그레이션-이력)
7. [코드 변경 이력](#7-코드-변경-이력)
8. [형식별 산출물 목록](#8-형식별-산출물-목록)
9. [산출물 완성도 체크리스트](#9-산출물-완성도-체크리스트)

---

## 1. 산출물 현황 요약

| 분류 | 총 문서 수 | 완료 | 진행 중 | 미착수 |
|------|:---:|:---:|:---:|:---:|
| 아키텍처 문서 | 1 | 1 | 0 | 0 |
| ADR | 13 (ADR-013 신규) | 12 | 1 | 0 |
| 서비스 상세 설계서 | 4 | 4 | 0 | 0 |
| 워크스루 | 5 | 5 | 0 | 0 |
| DB 마이그레이션 | 19 (V1~V19) | 19 | 0 | 0 |
| **유관기관 연동 가이드** | **6** | **6** | **0** | **0** |
| **운영 가이드 (ops/)** | **3** | **3** | **0** | **0** |
| **합계** | **51+** | **50+** | **1** | **0** |

---

## 2. 아키텍처 문서

| 문서명 | 경로 | 형식 | 설명 |
|--------|------|------|------|
| 위키 마스터 인덱스 | `wiki/INDEX.md` | Markdown | 전체 위키 구조, ADR/설계서 표, 시스템 개요 다이어그램, 용어 정의 |
| EDA 마스터 아키텍처 설계서 | `통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.8.docx` | DOCX | Kafka EDA 전체 아키텍처, 토픽 설계, 이벤트 흐름 |

### 시스템 아키텍처 개요

```
[외부 IdP]           [OnePass 플랫폼]              [기관 시스템 × 68]
  NICE ────────────►
  OACX ────────────► Q-Sign(:8081) ──► IdO(:8083) ──► 기관_001
  EzAuth ──────────►     │               │  │          기관_002
  Keycloak ────────►     │          Q-IM(:8082)  ►  ...
                         │               │          기관_068
                    Kafka (qim.user.events, qsign.auth.events)
                    PostgreSQL (qsign, qim, ido 스키마)
                    Redis (세션, 캐시, 분산락)
```

---

## 3. ADR (Architecture Decision Record)

| ADR | 제목 | 상태 | 결정일 | 경로 |
|-----|------|------|--------|------|
| ADR-001 | IdO 마이크로서비스 아키텍처 | ✅ Accepted | 2025 Q3 | `wiki/adr/ADR-001-ido-microservice-architecture.md` |
| ADR-002 | JDK 21 + Virtual Threads | ✅ Accepted | 2025 Q4 | `wiki/adr/ADR-002-jdk21-virtual-threads.md` |
| ADR-003 | Spring Boot 3.2 | ✅ Accepted | 2025 Q4 | `wiki/adr/ADR-003-spring-boot-3.md` |
| ADR-004 | Apache Kafka EDA | ✅ Accepted | 2025 Q3 | `wiki/adr/ADR-004-kafka-eda.md` |
| ADR-005 | PostgreSQL 주 데이터 저장소 | ✅ Accepted | 2025 Q3 | `wiki/adr/ADR-005-postgresql-primary-store.md` |
| ADR-006 | Redis 세션·캐시·분산락 | ✅ Accepted | 2025 Q3 | `wiki/adr/ADR-006-redis-session-cache.md` |
| ADR-007 | Flyway 스키마 버전 관리 | ✅ Accepted | 2025 Q3 | `wiki/adr/ADR-007-flyway-db-migration.md` |
| ADR-008 | Transactional Outbox 패턴 | ✅ Accepted | 2025 Q4 | `wiki/adr/ADR-008-transactional-outbox-pattern.md` |
| ADR-009 | QIM-OUTBOX-SPEC-001 이벤트 정합화 | ✅ Accepted | 2026 (Sprint 14) | `wiki/adr/ADR-009-qim-outbox-spec-001.md` |
| ADR-010 | CAST Token Ed25519 Cross-Agency SSO | ✅ Accepted | 2026 (Sprint 12) | `wiki/adr/ADR-010-cast-token-cross-agency-sso.md` |
| ADR-011 | HMAC-SHA256 Gateway 인증 | ✅ Accepted | 2025 Q4 | `wiki/adr/ADR-011-hmac-sha256-gateway-auth.md` |
| ADR-012 | React FE 이중 Axios 인스턴스 | ✅ Accepted | 2026 (Sprint 11) | `wiki/adr/ADR-012-react-fe-dual-instance.md` |

### ADR 결정 타임라인

```
2025 Q3  ─── ADR-001 (마이크로서비스)
         ─── ADR-004 (Kafka EDA)
         ─── ADR-005 (PostgreSQL)
         ─── ADR-006 (Redis)
         ─── ADR-007 (Flyway)

2025 Q4  ─── ADR-002 (JDK 21)
         ─── ADR-003 (Spring Boot 3)
         ─── ADR-008 (Outbox 패턴)
         ─── ADR-011 (HMAC-SHA256)

2026     ─── ADR-009 (QIM-OUTBOX-SPEC-001)
         ─── ADR-010 (CAST Token)
         ─── ADR-012 (FE 이중 인스턴스)
```

---

## 4. 서비스별 상세 설계서

| 서비스 | 포트 | 문서 경로 | 형식 | 핵심 내용 |
|--------|------|-----------|------|-----------|
| IdO (Identity Orchestrator) | 8083 | `wiki/design/01-ido-service-design.md` | Markdown | 패키지 구조, 인증 오케스트레이션, HmacSignatureFilter, ProvisioningServiceImpl, Handoff 4전략, DB 18테이블 |
| Q-IM (Query & Identity Manager) | 8082 | `wiki/design/02-qim-service-design.md` | Markdown | 회원 등록/전환, 2×2 이벤트 결정표, CI 암호화, Outbox 발행, DB 스키마 |
| Q-Sign (Auth Gateway) | 8081 | `wiki/design/03-qsign-service-design.md` | Markdown | 인증 세션 5단계, Keycloak OIDC, PKCE S256, AuthMetrics, DB 스키마 |
| Agency-Stub (PoC 스텁) | 8090 | `wiki/design/04-agency-stub-design.md` | Markdown | 기관 연동 시뮬레이터, 4종 Handoff 수신, 이벤트 폴링 |

### 서비스 간 의존성

```
onepass-fe ──────────► IdO(:8083)  ◄──── Kafka ────► Q-IM(:8082)
                            │                              │
                            ▼                              ▼
                       Q-Sign(:8081)               PostgreSQL (qim)
                            │                       Redis
                       Keycloak(OIDC)
                            │
                       PostgreSQL (ido, qsign)
                       Redis
                       Kafka
                       Agency Systems × 68
```

---

## 5. 워크스루 문서

| 문서 | 경로 | 설명 |
|------|------|------|
| WT-001: 로그인 전체 흐름 | `wiki/walkthrough/01-login-walkthrough.md` | NICE·OACX·EzAuth·Keycloak 4개 경로, CI 보안 처리, FE 이중 인스턴스 |
| WT-002: 신규 회원 가입 | `wiki/walkthrough/02-member-register-walkthrough.md` | 개인·기업 가입, Outbox 발행, CI 데이터 경계, 멱등성 |
| WT-003: 기관 계정 전환 | `wiki/walkthrough/03-member-conversion-walkthrough.md` | PERSONAL/BIZ_CONVERTED 이벤트, 분산락, 세션 갱신 |
| WT-004: 프로비저닝 흐름 | `wiki/walkthrough/04-provisioning-walkthrough.md` | QimEventConsumer 필터, Virtual Thread 68기관 병렬, 지수 백오프, Thundering Herd 방지 |
| WT-005: Handoff SSO | `wiki/walkthrough/05-handoff-sso-walkthrough.md` | CAST Token, Ed25519 서명, 4가지 Handoff 전략, 키 로테이션 |

---

## 6. DB 마이그레이션 이력

### IdO 마이그레이션 (V1~V18)

| 버전 | 파일명 | 내용 | 상태 |
|------|--------|------|------|
| V1 | `V1__create_initial_schema.sql` | ido 스키마 초기 생성 | ✅ |
| V2 | `V2__add_auth_session.sql` | auth_session 테이블 | ✅ |
| V3 | `V3__add_agency_config.sql` | agency_config 테이블 | ✅ |
| V4~V9 | _(생략)_ | 점진적 스키마 확장 | ✅ |
| V10 | → V17로 rename (PR #106) | 충돌 해결 | ✅ |
| V11~V14 | _(생략)_ | 기능별 스키마 추가 | ✅ |
| V15 | `V15__add_provisioning_outbox_and_agency_endpoint.sql` | provisioning_outbox, agency_endpoint 생성 | ✅ |
| V16 | `V16__add_gateway_inbound_audit.sql` | gateway_inbound_audit 생성 | ✅ |
| V17 | `V17__add_outbox_thundering_herd_prevention.sql` | FOR UPDATE SKIP LOCKED 인덱스 추가 | ✅ |
| V18 | `V18__update_event_type_constraints.sql` | QIM-OUTBOX-SPEC-001 CHECK 제약 갱신 | ✅ |

**V10 충돌 교훈** (ADR-007):
- V10이 두 개 존재하는 브랜치 병합 충돌 → V10 rename to V17
- 교훈: Flyway 번호는 merge 전 항상 slack/PR 사전 협의

---

## 7. 코드 변경 이력

### PR 이력 (주요)

| PR | 제목 | 포함 변경 | 상태 |
|----|------|-----------|------|
| #106 | fix(flyway): V10 충돌 해결 + ProvisioningEventType 정합화 | V10→V17 rename, ProvisioningEventType 신규 5종 추가 | ✅ Merged |
| #107 | fix(provisioning)+docs(fe): V18 CHECK 제약 + Javadoc + ciCheck.ts | V18 SQL, ProvisioningService Javadoc, ProvisioningOutboxRecord Javadoc, ciCheck.ts TODO | ✅ Merged |
| **#115** | feat(provision): Sprint 17 — `addAuthHeader()` API_KEY/HMAC/mTLS 구현 (BLOCKER 해소) | `AgencyProvisioningClient.addAuthHeader()` 3-mode 지원, 인증 분기 로직 | **✅ Merged** |
| **#116** | feat(conversion): 유관기관 전환 URL 보안 강화 + 버그 수정 + 가이드 문서 | B-1/B-2 뺄그 수정, JWT Signed Request ConversionInit API, PlatformErrorCode E-CONV-601~603, GUIDE-001~004 | **현재 OPEN** |
| **#117** | docs(dreamsecurity): 드림시큐리티 SSO 연동 종합 가이드 + 운영 심층 분석 | README.md v0.8.9, DELIVERABLES/INDEX 갱신, GUIDE-005 드림시큐리티 SSO 연동 가이드 신규 | **현재 OPEN** |
| **#122** | feat(outbox-relay-batch): mTLS RestTemplate 완성 + 운영 배포 주의사항 문서 | BatchRestTemplateConfig mTLS, ProvisioningRelayJob mTLS 선택, OPS-001, INDEX v0.9.0 | ✅ Merged |
| **#123** | feat(shipster): 심층 분석 + 전체 테스트 수정 + wiki 전면 업데이트 | ProvisioningServiceTest 6파라미터, HandoffControllerTest deprecated 수정, 939→1017 tests, ADR-013, GUIDE-006, OPS-002~003 | **현재 OPEN** |
| **#129** | feat(sdk): onepass-agency-sdk GAP-1~5 수정 + 36개 테스트 통과 | GAP-1(HMAC 알고리즘), GAP-2(@Deprecated), GAP-3(X-Event-Type), GAP-4(X-Correlation-ID), GAP-5(getBodyField+validateJson) | ✅ **MERGED** |
| **#130** | docs(sdk-guide): 유관기관 개발자 SDK 사용 가이드 작성 | `docs/onepass-agency-sdk-usage-guide.md` 신규 757줄 — Quick Start, API 레퍼런스, HMAC 서명, 에러 처리, Spring Boot 연동, 배포 절차 | ✅ **MERGED** |
| **#131** | docs(analysis): onepass-be-release / onepass-release 심층 분석 보고서 | `docs/_archive/2026-05-22/internal/analysis/onepass-release-analysis.md` 신규 494줄 — BE 9건 + FE 7건 이슈, 보안취약점 8건, Q-Sign/Q-IM 연동 현황 | **현재 OPEN** |

### 파일 수정 이력 (v0.9.2 Sprint 18 기준)

| 파일 | 수정 내용 | PR |
|------|-----------|-----|
| **`build.gradle.kts`** | ADR-013 방법 B: `mockitoAgent` Configuration + `-javaagent` 명시 (JDK 24 대비 근본 해결) | #123 |
| **`onepass-agency-sdk/build.gradle.kts`** | `sdkMockitoAgentConf` + SDK 전용 `-javaagent` 설정 (루트 충돌 회피) | #123 |
| **`ido/.../auth/service/AuthService.java`** | TODO(S7-T6) Javadoc 정리 — 구현 완료 상태 명시 | #123 |
| **`outbox-relay-batch/.../ProvisioningRelayJobTest.java`** | **신규** — 13개 단위 테스트 (B-01~13: enabled/empty/인증방식/오류처리/백오프) | #123 |
| **`outbox-relay-batch/.../BatchRestTemplateConfigTest.java`** | **신규** — 7개 단위 테스트 (C-01~07: mTLS fallback/정상생성/타임아웃) | #123 |
| **`wiki/adr/ADR-013-java-agent-migration.md`** | 상태 🔶 Proposed → ✅ 방법 B 적용 완료 | #123 |
| **`wiki/INDEX.md`** | v0.9.2, 1017 tests, ADR-013 상태 갱신, ops/ 디렉토리 트리 추가 | #123 |
| **`wiki/deliverables/DELIVERABLES.md`** | v0.9.2, 1017 tests, ADR-013 등재, PR #122/#123, 53+ 산출물 | #123 |

### 파일 수정 이력 (v0.8.11 genspark_ai_developer 기준)

| 파일 | 수정 내용 | PR |
|------|-----------|-----|
| **`docs/onepass-agency-sdk-usage-guide.md`** | **신규 생성** — 유관기관 개발자 SDK 사용 가이드 (757줄): Quick Start, API 레퍼런스, HMAC 서명, 에러 처리, Spring Boot 연동, 배포 절차 | #130 |
| **`docs/_archive/2026-05-22/internal/analysis/onepass-release-analysis.md`** | **신규 생성** — onepass-be-release / onepass-release 심층 분석 보고서 (494줄): BE 9건 + FE 7건 이슈 식별, 보안취약점 8건, Q-Sign/Q-IM 연동 현황 | #131 |
| **`onepass-agency-sdk/src/.../HmacSigner.java`** | GAP-1: 서명 알고리즘 `{agencyCode}:{idempotencyKey}:{epochSeconds}`로 서버 정합성 수정 | #129 |
| **`onepass-agency-sdk/src/.../AgencyGatewayClient.java`** | GAP-2: `triggerOutbound()` @Deprecated 추가, GAP-3: X-Event-Type 헤더 전송, GAP-4: X-Correlation-ID 대문자 D | #129 |
| **`onepass-agency-sdk/src/.../GatewayResponse.java`** | GAP-5: `getBodyField(String)` + `isValidJson()` 헬퍼 메서드 추가 | #129 |
| **`onepass-agency-sdk/CHANGELOG.md`** | GAP-1~5 수정 이력 반영, [0.1.0-GAP-PATCH] 버전 섹션 생성 | #129 |

### 파일 수정 이력 (v0.8.9 기준)

| 파일 | 수정 내용 | PR |
|------|-----------|-----|
| `ido/.../ProvisioningService.java` | QIM-OUTBOX-SPEC-001 Javadoc 갱신, 데이터 흐름 다이어그램 추가 | #107 |
| `ido/.../ProvisioningOutboxRecord.java` | eventType Javadoc 구 4종 → 신규 5종 갱신 | #107 |
| `ido/.../V18__update_event_type_constraints.sql` | provisioning_outbox + gateway_inbound_audit CHECK 제약 재정의 | #107 |
| `onepass-fe/.../ciCheck.ts` | 미연결 상태 TODO 주석 문서화 | #107 |
| **`onepass-fe/.../ConversionSteps/member/Step8.tsx`** | **B-1 버그 수정** — `isSafeRedirectUri()` `*.smes.go.kr` 하드코딩 → `REACT_APP_REDIRECT_ALLOWED_ORIGINS` 환경변수 + 와일드카드 | #116 |
| **`ido/.../resources/application.yml`** | **B-2 버그 수정** — `allowed-return-urls` PoC 더미 URL → `${ALLOWED_URL_*}` 환경변수 구조 + `ido.conversion.*` 설정 추가 | #116 |
| **`platform-common/.../PlatformErrorCode.java`** | 신규 에러코드 4개 추가: `AGENCY_NOT_FOUND(E-AGENCY-307)`, `CONVERSION_SIGNATURE_INVALID(E-CONV-601)`, `CONVERSION_REQUEST_EXPIRED(E-CONV-602)`, `CONVERSION_SESSION_NOT_FOUND(E-CONV-603)` | #116 |
| **`ido/.../conversion/ConversionInitController.java`** | 신규 생성 — `POST /api/v1/conversion/init` 엔드포인트 | #116 |
| **`ido/.../conversion/ConversionInitService.java`** | 신규 생성 — JWT 서명 검증 + Redis 세션 | #116 |
| **`ido/.../conversion/ConversionSession.java`** | 신규 생성 — Redis 저장 도메인 객체 (TTL 30분) | #116 |
| **`ido/.../conversion/dto/ConversionInitRequest.java`** | 신규 생성 — `signedRequest` + `agencyCode` DTO | #116 |
| **`ido/.../conversion/dto/ConversionInitResponse.java`** | 신규 생성 — `conversionSessionId` + `userType` + `expiresAt` DTO | #116 |
| **`wiki/guide/01-agency-conversion-url-flow.md`** | 신규 생성 — URL 플로우 분석 + 3레이어 검증 + ❌틀린것/⚠다른것/✅올바른것 분류 | #116 |
| **`wiki/guide/02-conversion-param-security.md`** | 신규 생성 — JWT Signed Request 상세 구현 + 로드맵 | #116 |
| **`wiki/guide/03-conversion-launch-sample.md`** | 신규 생성 — Node.js/Java/Python 기관 오픈 URL 샘플 + 체크리스트 | #116 |
| **`wiki/guide/04-conversion-data-flow-diagram.md`** | 신규 생성 — ①~㉪ 순번 시퀀스 다이어그램 + ConversionContext 상태 추적 | #116 |
| **`wiki/INDEX.md`** | 수정 — `guide/` 섹션 추가, 알려진 버그 B-1/B-2 목록 등재 | #116 |
| **`wiki/guide/05-dreamsecurity-sso-integration.md`** | **신규** — 드림시큐리티 SSO 연동 종합 가이드: 갭 분석 7항목, INTERNAL_SSO 어댑터, 식별자 매핑, SLO 동기화, 회원 전환 정책, 도입 매트릭스 | #117 |
| **`wiki/deliverables/DELIVERABLES.md`** | 수정 — v0.8.9 갱신, PR #117, GUIDE-005 등재, 산출물 45+ | #117 |

---

## 8. 형식별 산출물 목록

### Markdown 산출물 (위키 업로드용)

```
wiki/
├── INDEX.md                              ← 위키 마스터 인덱스
├── adr/
│   ├── ADR-001-ido-microservice-architecture.md
│   ├── ADR-002-jdk21-virtual-threads.md
│   ├── ADR-003-spring-boot-3.md
│   ├── ADR-004-kafka-eda.md
│   ├── ADR-005-postgresql-primary-store.md
│   ├── ADR-006-redis-session-cache.md
│   ├── ADR-007-flyway-db-migration.md
│   ├── ADR-008-transactional-outbox-pattern.md
│   ├── ADR-009-qim-outbox-spec-001.md
│   ├── ADR-010-cast-token-cross-agency-sso.md
│   ├── ADR-011-hmac-sha256-gateway-auth.md
│   └── ADR-012-react-fe-dual-instance.md
├── design/
│   ├── 01-ido-service-design.md
│   ├── 02-qim-service-design.md
│   ├── 03-qsign-service-design.md
│   └── 04-agency-stub-design.md
├── walkthrough/
│   ├── 01-login-walkthrough.md
│   ├── 02-member-register-walkthrough.md
│   ├── 03-member-conversion-walkthrough.md
│   ├── 04-provisioning-walkthrough.md
│   └── 05-handoff-sso-walkthrough.md
├── guide/                                ← ★ v0.8.9 신규 — 유관기관 연동 가이드
│   ├── 01-agency-conversion-url-flow.md
│   ├── 02-conversion-param-security.md
│   ├── 03-conversion-launch-sample.md
│   ├── 04-conversion-data-flow-diagram.md
│   ├── 05-dreamsecurity-sso-integration.md  ← ★★ Turn 6 신규
│   └── 06-agency-sso-integration-strategy.md ← ★★ Sprint 17 신규
├── ops/                                  ← ★★ Sprint 17 신규
│   ├── 01-production-deployment-guide.md
│   ├── 02-project-completion-status.md
│   └── 03-agency-support-runbook.md
└── deliverables/
    └── DELIVERABLES.md                   ← 이 문서
```

**총 Markdown 파일**: 29개 (v0.8.11 기준, guide/ 6편 + ops/ 3편 + ADR-013 + SDK 가이드 + 분석 보고서 포함)  
**총 추정 분량**: 약 1,000~1,100 페이지 (A4 기준)

---

### DOCX 산출물 (공식 문서 배포용)

| 파일명 | 소스 | 대상 독자 | 우선순위 |
|--------|------|-----------|----------|
| `통합인증_플랫폼_EDA_마스터_아키텍처_설계서_v0.8.8.docx` | 기존 생성 완료 | 아키텍처 위원회, PM | ✅ 완료 |
| `IdO_서비스_상세_설계서_v0.8.9.docx` | `design/01-ido-service-design.md` | 개발팀, 보안 팀 | 🔜 변환 예정 |
| `QIM_서비스_상세_설계서_v0.8.8.docx` | `design/02-qim-service-design.md` | 개발팀 | 🔜 변환 예정 |
| `QSign_서비스_상세_설계서_v0.8.8.docx` | `design/03-qsign-service-design.md` | 개발팀 | 🔜 변환 예정 |
| `프로비저닝_워크스루_v0.8.8.docx` | `walkthrough/04-provisioning-walkthrough.md` | 운영팀, 기관 담당자 | 🔜 변환 예정 |
| `HandoffSSO_워크스루_v0.8.8.docx` | `walkthrough/05-handoff-sso-walkthrough.md` | 보안팀, 기관 담당자 | 🔜 변환 예정 |
| `유관기관_전환가이드_v0.8.9.docx` | `guide/01~04` 종합 | 기관 개발팀, 운영팀 | 🔜 변환 예정 |

---

### 기타 산출물

| 파일명 | 형식 | 설명 |
|--------|------|------|
| `OnePass_qim_outbox_해이브명세_20260514.xlsx` | Excel | QIM Outbox 이벤트 명세서 (기존 생성) |
| `member-flow.html` | HTML | 회원 흐름 다이어그램 (시각화용) |

---

## 9. 산출물 완성도 체크리스트

### 문서화

- [x] 위키 마스터 인덱스 (`wiki/INDEX.md`)
- [x] ADR-001 ~ ADR-012 (12개 완료)
- [x] 서비스 상세 설계서 4개 (IdO, Q-IM, Q-Sign, Agency-Stub)
- [x] 워크스루 5개 (로그인, 가입, 전환, 프로비저닝, Handoff SSO)
- [x] **유관기관 연동 가이드 5편** (GUIDE-001~005) ★ v0.8.9 신규
  - GUIDE-005: 드림시큐리티 SSO 연동 종합 가이드 (갭 분석 7항목 + 4가지 대책)
- [x] **onepass-agency-sdk 유관기관 개발자 사용 가이드** ★ v0.8.10 신규 (PR #130 MERGED)
  - `docs/onepass-agency-sdk-usage-guide.md` — 757줄, Quick Start + 전체 API + Spring Boot 연동
- [x] **onepass-be-release / onepass-release 심층 분석 보고서** ★ v0.8.11 (PR #131 OPEN)
  - `docs/_archive/2026-05-22/internal/analysis/onepass-release-analysis.md` — 494줄, BE 9건 + FE 7건 이슈 + 보안취약점 8건
- [x] 산출물 마스터 인덱스 (이 문서)
- [ ] DOCX 변환 (상세 설계서 4개 + 워크스루 2개 + 유관기관 가이드 1개)

### 코드 품질

- [x] V18 DB 마이그레이션 (QIM-OUTBOX-SPEC-001 CHECK 제약)
- [x] ProvisioningService.java Javadoc 갱신
- [x] ProvisioningOutboxRecord.java Javadoc 갱신
- [x] ciCheck.ts 미연결 TODO 문서화
- [x] **B-1 수정**: `Step8 isSafeRedirectUri()` 환경변수 기반 ★ v0.8.9
- [x] **B-2 수정**: `application.yml allowed-return-urls` 환경변수 구조 ★ v0.8.9
- [x] **JWT Signed Request**: `ConversionInit` API 구현 (`POST /api/v1/conversion/init`) ★ v0.8.9
- [x] **E-CONV 에러코드**: `PlatformErrorCode` 4개 추가 ★ v0.8.9
- [x] **Sprint 17 addAuthHeader()**: API_KEY/HMAC/mTLS 3-mode 구현 ★ v0.8.9
- [x] **SDK GAP-1**: HMAC 서명 알고리즘 서버 정합성 수정 ★ v0.8.10 (PR #129 MERGED)
- [x] **SDK GAP-2**: `triggerOutbound()` @Deprecated 처리 ★ v0.8.10
- [x] **SDK GAP-3**: `X-Event-Type` 헤더 전송 추가 ★ v0.8.10
- [x] **SDK GAP-4**: `X-Correlation-ID` 헤더명 대문자 D 통일 ★ v0.8.10
- [x] **SDK GAP-5**: `getBodyField()` + `isValidJson()` 헬퍼 추가 ★ v0.8.10
- [ ] **JTI 재사용 방지**: JWT replay attack 방어 Redis 블랙리스트 (단기 P1)
- [ ] ci-check FE 연결 (별도 Sprint, Q2=B PoC 완료 후)

### 운영 준비

- [x] Flyway V1~V18 마이그레이션 스크립트
- [x] provisioning_outbox CHECK 제약 (V18)
- [ ] **`REACT_APP_REDIRECT_ALLOWED_ORIGINS`** FE .env.production 설정 (68개 기관 URL) ⚠ **운영 배포 필수**
- [ ] **K8s ConfigMap** `ALLOWED_URL_*` 실제 기관 URL 주입 ⚠ **운영 배포 필수**
- [ ] **K8s Secret** `SECRETS_AGENCY_{CODE}_API_KEY` 기관별 등록 ⚠ **운영 배포 필수**
- [ ] **DB** `agency_meta.callback_whitelist` 기관별 콜백 URL 등록 ⚠ **운영 배포 필수**
- [ ] 운영 모니터링 대시보드 설정 (Grafana)
- [ ] DLQ 알림 설정 (Slack/PagerDuty)
- [ ] CAST Token 키 로테이션 절차서

---

## 관련 링크

| 항목 | 링크 |
|------|------|
| 위키 인덱스 | `wiki/INDEX.md` |
| ADR 디렉토리 | `wiki/adr/` |
| 설계서 디렉토리 | `wiki/design/` |
| 워크스루 디렉토리 | `wiki/walkthrough/` |
