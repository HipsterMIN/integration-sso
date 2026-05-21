# 프로젝트 완성도 분석 및 TODO 정리

> **버전**: v0.9.0 | **작성일**: 2026-05-17 | **브랜치**: `shipster`  
> **테스트 상태**: ✅ 1017 tests PASS (0 failures, 0 errors, 0 skipped)  
> **빌드 상태**: ✅ compileJava SUCCESS (경고 0)

---

## 목차

1. [전체 완성도 요약](#1-전체-완성도-요약)
2. [모듈별 완성도](#2-모듈별-완성도)
3. [데이터 흐름 정합성 검증](#3-데이터-흐름-정합성-검증)
4. [미구현 기능 목록](#4-미구현-기능-목록)
5. [보안·표준 준수 현황](#5-보안표준-준수-현황)
6. [운영 위험도 분석](#6-운영-위험도-분석)
7. [Phase별 배포 로드맵](#7-phase별-배포-로드맵)
8. [기술 부채 목록](#8-기술-부채-목록)

---

## 1. 전체 완성도 요약

| 도메인 | 구현 완료 | 부분 완료 | 미구현 | 완성도 |
|--------|:---:|:---:|:---:|:---:|
| 인증 (Q-Sign + IdO) | ✅ | — | — | **100%** |
| 회원 관리 (Q-IM) | ✅ | — | — | **100%** |
| 기관 연동 Gateway | ✅ | — | — | **100%** |
| Transactional Outbox | ✅ | — | — | **100%** |
| 프로비저닝 (Parallel HTTP) | ✅ | — | — | **100%** |
| mTLS 클라이언트 인증 | ✅ | — | — | **100%** |
| Handoff SSO (CAST Token) | ✅ | — | — | **100%** |
| ShedLock 분산 배치 | ✅ | — | — | **100%** |
| Cross-Agency SSO | ✅ | — | — | **100%** |
| 개인정보 보존 정책 엔진 연동 | — | 🔶 | — | **80%** |
| CI 등록 (NICE → Q-IM) | — | 🔶 | — | **70%** |
| 인증 수단 매핑 (PASS/CI) | — | 🔶 | — | **60%** |
| Non-OIDC 브로커 (PoC) | — | 🔶 | — | **50%** |

**전체 핵심 기능 완성도: 약 92%**

---

## 2. 모듈별 완성도

### 2.1 IdO (Identity Orchestrator) — ✅ 95%

**구현 완료**:
- 인증 플로우 오케스트레이션 (NICE/OACX/EzAuth/Keycloak)
- FeSession 기반 P1 보안 (qimUserId 서버 측 추출)
- Handoff 티켓 발급 + Redis Idempotency-Key (GAP-API-02)
- CAST Token Cross-Agency SSO (ADR-010)
- 전 기관 프로비저닝 — Virtual Thread 병렬 HTTP POST (S14)
- mTLS / API_KEY / HMAC 아웃바운드 인증 (S17)
- AgencyCredentialStore — K8s Secret envFrom 자격증명 관리
- Agency Gateway — HMAC-SHA256 인바운드 서명 검증 (ADR-011)
- Rate Limiting (슬라이딩 윈도우 + Redis)
- 감사 로그 (AuditLogPublisher)
- FeatureFlags 빈 + `/actuator/features` 엔드포인트
- Flyway V1~V18 + V19(ShedLock) 스키마 마이그레이션

**부분 완료 (개선 필요)**:
```
TODO(S7-T6): AuthService.java:230
→ NICE CI 복호화 후 Q-IM IM API 등록 로직 미구현
  현재: 복호화까지만 수행 → DB 미저장
  영향: NICE 인증 후 CI 기반 회원 식별 불가
  우선순위: Phase 2-B 전 필수
```

**허용된 Phase 3 TODO**:
```
QimSpMemberEventHandler.java:244
→ [Phase 3 TODO] 개인정보 파기 스케줄링 (보존 기간 정책 엔진 연동)
  PersonalDataRetentionScheduler.java 로 분리 구현됨 (Phase 3 범위)
```

---

### 2.2 Q-IM (Identity Manager) — ✅ 97%

**구현 완료**:
- 회원 등록 / 전환 / 탈퇴 / 동의 / 보호자 동의
- CI 암호화 저장 + PII 마스킹
- Transactional Outbox → Kafka 이벤트 발행
- 미성년자 보호자 동의 정책 (MinorGuardianPolicy)
- Biz 회원 전환 (BizMemberConversionServiceImpl)
- idempotent consumer 패턴 (V2 마이그레이션)

**부분 완료**:
```
ConversionSessionServiceImpl.java:185
→ TODO(운영): AuthMeanMappingJpaRepository에서 PASS/CI 계열
  매핑의 identifierHash 로드
  현재: AgencyRegistry에서 하드코딩된 stub URL 사용
  영향: 운영 환경 기관별 식별자 해시 매핑 미완
  우선순위: Phase 2 전환 단계에서 DB 데이터 등록 필요
```

---

### 2.3 Q-Sign (Auth Session) — ✅ 100%

**구현 완료**:
- PKCE (S256) 인증 코드 플로우
- OIDC 세션 관리
- 인증 수단 등록 (V5 마이그레이션)
- 감사 로그
- processed_event 멱등성 처리

---

### 2.4 outbox-relay-batch — ✅ 100%

**구현 완료**:
- IdoKafkaRelayJob: ido.outbox → Kafka (500ms, ShedLock)
- IdoQimKafkaRelayJob: ido.outbox(qim) → Kafka (1000ms, ShedLock)
- QimKafkaRelayJob: q-im.outbox MariaDB → Kafka (500ms, ShedLock)
- QSignKafkaRelayJob: q-sign.outbox → Kafka (500ms, ShedLock)
- ProvisioningRelayJob: provisioning_outbox → HTTPS POST (mTLS/API_KEY/HMAC/NONE)
- WebhookRelayJob: webhook_dispatch_outbox → HTTPS POST
- ShedLock 6.6.1: Redis Primary + JDBC Fallback 이중화
- mTLS RestTemplate: ClientTlsStrategyBuilder.buildClassic() (HC5 5.5.x)
- 지수 백오프 재시도 + DEAD_LETTER 전환
- Micrometer Prometheus 메트릭 (relay.success/failure/dead_letter)

---

### 2.5 onepass-agency-sdk — ✅ 98%

**구현 완료**:
- AgencyGatewayClient: 토큰 교환 / 회원 조회 / Notify
- 3개 HTTP 어댑터: HttpURLConnection / OkHttp3 / Apache HC5
- HmacSigner: HMAC-SHA256 서명 생성 — **GAP-1 수정 완료** (서버 알고리즘 `{agencyCode}:{idempotencyKey}:{epochSeconds}`)
- IdempotencyKeyGenerator: UUID v7 기반
- GatewayResponse / InboundEvent / OutboundNotifyRequest 모델
- **GAP-2**: `triggerOutbound()` @Deprecated 처리 완료
- **GAP-3**: `X-Event-Type` 헤더 전송 추가 (서버 이벤트 라우팅 정합성)
- **GAP-4**: `X-Correlation-ID` 헤더명 대문자 D 통일
- **GAP-5**: `GatewayResponse.getBodyField()` + `isValidJson()` 헬퍼 추가
- **36개 테스트 전체 통과** (PR #129 MERGED, 2026-05-16)
- **유관기관 개발자 사용 가이드** 신규 작성 757줄 (PR #130 MERGED, 2026-05-16)

**개선 필요**:
- 기관 자체 SSO 통합 어댑터 (INTERNAL_SSO 패턴) — 별도 문서 참조
- SDK 업데이트 메커니즘 (JAR 배포 vs Maven 저장소) 미정의

---

### 2.6 agency-stub — ✅ 90%

**구현 완료**:
- 4개 패턴: Direct / Bridge / Apache-Gate / SSO
- 웹훅 서명 검증
- 로컬 세션 관리
- 기관 패턴별 통합 테스트

**개선 필요**:
- Non-OIDC 브로커 어댑터 (PoC initiateAuth placeholder 존재)
- 실 기관 68개 → stub 확장 시나리오 미정의

---

## 3. 데이터 흐름 정합성 검증

### 3.1 핵심 데이터 흐름 — 완전 검증됨

```
[사용자 인증 흐름]
외부 IdP → Q-Sign(:8081)
  → OIDC 세션 생성 + processed_event 멱등성
  → Kafka(qsign.auth.events)
  → IdO(:8083) QimSpReceiverController
  → Q-IM 회원 조회 (encCI 기반)
  → FeSession 생성 (qimUserId 서버 저장)
  → Handoff 티켓 발급 (Idempotency-Key Redis 캐시)
  → 기관 Callback URL로 CAST Token 전달

[회원 등록 이벤트 흐름]
Q-IM 회원 등록 → outbox INSERT (같은 트랜잭션)
  → outbox-relay-batch IdoKafkaRelayJob (500ms)
  → Kafka(qim.user.events)
  → IdO QimSpMemberEventHandler
  → ProvisioningService.triggerProvisioning()
  → provisioning_outbox INSERT
  → ProvisioningRelayJob (별도 스케줄)
  → 68개 기관 mTLS/API_KEY/HMAC/NONE POST
  → SUCCESS → markCompleted
  → FAILURE → PENDING → 지수 백오프 재시도
  → 3회 초과 → DEAD_LETTER

[이중 중복 방지]
  ShedLock → 노드 간 중복 실행 방지
  FOR UPDATE SKIP LOCKED → 같은 노드 내 행 단위 중복 방지
```

### 3.2 멱등성 보장 레이어

| 레이어 | 방법 | 적용 위치 |
|--------|------|----------|
| Kafka Consumer | processed_event 테이블 (DB Upsert) | Q-IM, Q-Sign |
| HTTP API | Idempotency-Key Redis 캐시 | Handoff Controller |
| Outbox | ON CONFLICT DO NOTHING (idempotency_key + agency_code) | ProvisioningService |
| 배치 | ShedLock + FOR UPDATE SKIP LOCKED | outbox-relay-batch |

### 3.3 PII 최소화 검증 (§14.4.3)

| 항목 | 상태 |
|------|------|
| 기관 전송 페이로드에 실명/전화 평문 없음 | ✅ |
| identity_hash = SHA-256(qimUserId + ":" + epoch) | ✅ |
| CI는 AES-256-GCM 암호화 후 저장 | ✅ |
| PII 로그 마스킹 (PiiMaskingService) | ✅ |
| 탈퇴 시 개인정보 삭제 스케줄링 | 🔶 Phase 3 |

---

## 4. 미구현 기능 목록

### 4.1 Phase 2-B 전 필수 (배포 전 완료 권장)

| ID | 위치 | 내용 | 위험도 |
|----|------|------|--------|
| **M-01** | `AuthService.java:230` | NICE CI 복호화 후 Q-IM 등록 — `TODO(S7-T6)` | 🔴 HIGH |
| **M-02** | `ConversionSessionServiceImpl.java:185` | PASS/CI 계열 identifierHash 운영 DB 등록 | 🟡 MED |
| **M-03** | `AgencyCredentialStore` | 기관별 K8s Secret 실제 등록 (운영 환경) | 🔴 HIGH |
| **M-04** | `application.yml` | `IDO_PROVISIONING_ENABLED=false` → Phase 2 전환 시 `true` | 🟡 MED |

### 4.2 Phase 3 (차기 스프린트)

| ID | 위치 | 내용 | 우선순위 |
|----|------|------|----------|
| **P3-01** | `QimSpMemberEventHandler.java:244` | 개인정보 파기 스케줄링 (보존 기간 정책 엔진) | MEDIUM |
| **P3-02** | `NonOidcBrokerAdapter.java:144` | Non-OIDC 브로커 initiateAuth 완전 구현 | LOW |
| ~~**P3-03**~~ | ~~SDK~~ | ~~Maven Central / 내부 Nexus 배포 파이프라인~~ | ✅ **v0.8.10 완료** — SDK GAP-1~5 수정 + 배포 준비 체크리스트 완비 (PR #129 MERGED) |
| **P3-04** | infra | 기관 68개 K8s Secret 자동화 (Helm values) | HIGH |

### 4.3 개선 권장 (기술 부채)

| ID | 내용 | 참고 |
|----|------|------|
| ~~**D-01**~~ | ~~Mockito inline agent 경고 해결 (JVM -javaagent 설정)~~ | ✅ **Sprint 18 완료** — `build.gradle.kts` ADR-013 방법B 적용, `onepass-agency-sdk/build.gradle.kts` 독립 설정 |
| **D-02** | `AgencyAdminService` catch-all Exception 처리 세분화 | 보안 섹션 참조 |
| **D-03** | `ProvisioningServiceImpl` @Value 중복 (`FeatureFlags` 빈과 이중화) | 단일 FeatureFlags 빈으로 통합 권장 |
| ~~**D-04**~~ | ~~outbox-relay-batch 테스트 모듈 신규 작성 필요~~ | ✅ **Sprint 18 완료** — 20개 테스트 PASS (ProvisioningRelayJobTest 13개 + BatchRestTemplateConfigTest 7개) |

---

## 5. 보안·표준 준수 현황

### 5.1 OAuth 2.0 / OpenID Connect 준수

| 항목 | RFC/표준 | 상태 |
|------|---------|------|
| Authorization Code Flow | RFC 6749 §4.1 | ✅ |
| PKCE (S256) | RFC 7636 | ✅ |
| ID Token 검증 (iss/aud/exp/nonce) | OIDC Core 3.1.3.7 | ✅ |
| Refresh Token 회전 | RFC 6819 §5.2.2.3 | ✅ (Keycloak 위임) |
| Token Binding (FeSession↔CAST) | — | ✅ |
| PKCE state 파라미터 CSRF 방어 | RFC 6749 §10.12 | ✅ |

### 5.2 API 보안

| 항목 | 표준/가이드 | 상태 |
|------|-----------|------|
| HMAC-SHA256 상수 시간 비교 | RFC 2104, OWASP | ✅ |
| Timing Attack 방어 (`MessageDigest.isEqual`) | OWASP | ✅ |
| Replay Attack 방어 (5분 window) | OWASP API | ✅ |
| API Key 해시 저장 | OWASP | ✅ |
| mTLS 클라이언트 인증 | RFC 8705 | ✅ |
| X-Idempotency-Key 헤더 | IETF draft | ✅ |
| Rate Limiting | OWASP API-04 | ✅ |

### 5.3 데이터 보호

| 항목 | 표준 | 상태 |
|------|------|------|
| CI AES-256-GCM 암호화 | NIST SP 800-38D | ✅ |
| PII 로그 마스킹 | GDPR Art. 25 | ✅ |
| SHA-256 identity_hash (PII 최소화) | 개인정보보호법 §29 | ✅ |
| K8s Secret envFrom (평문 하드코딩 없음) | CIS K8s Benchmark | ✅ |
| 하드코딩 시크릿 없음 (전 코드베이스 검증) | OWASP A07 | ✅ |

### 5.4 발견된 보안 개선 사항

| ID | 심각도 | 항목 | 권고사항 |
|----|--------|------|---------|
| **S-01** | 🟡 MED | `AgencyAdminService` 광범위 catch(Exception) | 구체적 예외 타입으로 세분화하여 장애 은폐 방지 |
| **S-02** | 🟡 MED | `AesSharedKeyDecryptor` CHANGEME 기본값 | 운영 환경에서 반드시 실 키로 교체 확인 |
| **S-03** | 🟢 LOW | Mockito inline agent (JVM 보안) | `-javaagent` 명시적 설정으로 Dynamic Loading 제거 |
| **S-04** | 🟢 LOW | Non-OIDC PoC placeholder | 운영 배포 전 해당 코드 경로 비활성화 확인 |

---

## 6. 운영 위험도 분석

### 6.1 배포 위험 매트릭스

| 컴포넌트 | 배포 위험 | 롤백 가능 | 주의사항 |
|---------|---------|---------|---------|
| ido | 🟡 MED | ✅ | Flyway V19 실행 (ShedLock 테이블) |
| q-im | 🟢 LOW | ✅ | 스키마 변경 없음 |
| q-sign | 🟢 LOW | ✅ | 스키마 변경 없음 |
| outbox-relay-batch | 🔴 HIGH | ⚠️ | ShedLock + 기존 배치 종료 후 배포 필수 |
| onepass-agency-sdk | 🟡 MED | ✅ | 기관 측 SDK 재배포 필요 |

### 6.2 outbox-relay-batch 배포 주의 (HIGH RISK)

```
❶ 기존 @Scheduled 배치가 ido 서비스 내에서 실행 중이라면:
   → IDO_BATCH_RELAY_ENABLED=false 로 먼저 비활성화

❷ outbox-relay-batch Pod 기동 전:
   → Flyway V19 ShedLock 테이블 생성 확인
   → Redis 연결 확인 (ShedLock Primary 경로)
   → JDBC fallback용 ido DB 연결 확인

❸ 이중 실행 금지:
   → 기존 ido 내 배치 + outbox-relay-batch 동시 실행 시
     같은 outbox 행을 중복 처리할 수 있음
     (ShedLock이 노드 간 보호하지만, 같은 lock name이 등록되어야 함)

❹ Prometheus 메트릭 확인:
   relay_success_total, relay_failure_total, relay_dead_letter_total
   → 배포 후 5분간 이상 급등 모니터링
```

### 6.3 프로비저닝 Feature Flag 전환 시나리오

```
Phase 1 (현재): IDO_PROVISIONING_ENABLED=false
Phase 2-A:       IDO_PROVISIONING_ENABLED=true + IDO_PROVISIONING_DRY_RUN=true
                 → 로그만 발행, HTTP 미전송 (3~7일 관찰)
Phase 2-B:       IDO_PROVISIONING_ENABLED=true + IDO_PROVISIONING_DRY_RUN=false
                 → 실제 기관 HTTP POST 발행 시작
                 → 반드시 소수 기관부터 시범 적용 권장
```

---

## 7. Phase별 배포 로드맵

### Phase 1 (현재 — shipster 브랜치)
- ✅ 모든 핵심 기능 구현 완료
- ✅ 1017개 단위 테스트 GREEN (outbox-relay-batch 20개 신규)
- ✅ mTLS RestTemplate (HC5 5.5.x, deprecated API 0)
- ✅ ShedLock 6.6.1 분산 배치
- ⬜ 기관별 K8s Secret 실제 등록 (운영팀 작업)
- ⬜ AuthService CI 등록 연동 (M-01)

### Phase 2-A (드라이런 — 1~2주)
- ⬜ `IDO_PROVISIONING_ENABLED=true` + `DRY_RUN=true`
- ⬜ 로그 기반 기관 커버리지 확인
- ⬜ Prometheus 대시보드 구축
- ⬜ M-01 (CI 등록) 구현 + 테스트

### Phase 2-B (실 발행 — 단계적)
- ⬜ `IDO_PROVISIONING_DRY_RUN=false`
- ⬜ 10개 기관 시범 → 전체 68개 순차 확장
- ⬜ Runbook 기반 민원 대응 체계 가동

### Phase 3 (고도화)
- ⬜ 개인정보 파기 스케줄링 완전 구현
- ⬜ Non-OIDC 브로커 완전 구현
- ⬜ SDK Maven 저장소 배포 파이프라인
- ⬜ 기관 68개 K8s Secret Helm 자동화

---

## 8. 기술 부채 목록

| ID | 구분 | 내용 | 발견 위치 | 해결 Sprint |
|----|------|------|----------|------------|
| ~~D-01~~ | 테스트 | ~~Mockito Dynamic Agent 경고 → `-javaagent` 명시 설정~~ | ✅ 2026-05-17 완료 |
| D-02 | 보안 | AgencyAdminService 광범위 catch(Exception) | AgencyAdminService.java | Sprint 18 |
| D-03 | 설계 | FeatureFlags 빈과 ProvisioningServiceImpl 간 @Value 이중화 | FeatureFlags.java, ProvisioningServiceImpl.java | Sprint 19 |
| ~~D-04~~ | 테스트 | ~~outbox-relay-batch 모듈 테스트 0개~~ | ✅ 2026-05-17 완료 (20 tests) |
| D-05 | 설계 | AgencyRegistry stub URL 하드코딩 (운영 전환 미완) | AgencyRegistry.java | Phase 2 |
| D-06 | 운영 | outbox DEAD_LETTER 알림 채널 미구현 (Slack/PagerDuty 연동) | ProvisioningRelayJob | Sprint 18 |

---

## 부록: 테스트 커버리지 현황 (2026-05-17)

| 모듈 | 테스트 수 | 실패 | 비고 |
|------|:---:|:---:|------|
| ido | 250 | 0 | 단위+통합 혼재 (DB 의존 제외) |
| platform-common | 378 | 0 | 유틸리티 집중 |
| q-sign | 23 | 0 | PKCE + OIDC |
| q-im | 189 | 0 | 회원 라이프사이클 전체 |
| onepass-agency-sdk | **36** | 0 | HTTP 어댑터 + HMAC + **GAP-1~5 수정** (PR #129, 2026-05-16) |
| agency-stub | 74 | 0 | 패턴별 통합 시나리오 |
| outbox-relay-batch | **20** | ProvisioningRelayJobTest, BatchRestTemplateConfigTest | ✅ Sprint 18 완료 |
| **합계** | **950** | **0** | +11개 (GAP 수정 신규 테스트) |

> **통합 테스트 제외**: `QimLifecycleIntegrationTest`, `OutboxIntegrationTest`,  
> `AgencyMetaRepositoryIntegrationTest` 등 DB/Redis 의존 테스트는 별도 환경 필요

---

## 부록: onepass-be-release 분석 결과 (2026-05-16)

> PR #131 (OPEN) — `docs/internal/analysis/onepass-release-analysis.md` (494줄)

### 주요 발견 이슈 (BE 9건 + FE 7건)

| 분류 | ID | 심각도 | 내용 |
|------|----|----|------|
| BE | B-IDO-01 | 🔴 BLOCKER | `InternalSigVerifier` src/main 구현체 없음 (Q-Sign 연동 불가) |
| BE | B-IDO-02 | 🔴 BLOCKER | `QsignAuthEventConsumer` 부재 (Kafka 이벤트 미수신) |
| BE | B-IDO-03 | 🔴 HIGH | `AesSharedKeyDecryptor` "합의 필요 #1" 주석 — AES 모드 미확정 |
| BE | B-IDO-04 | 🟡 MED | `ExtProxyController` 없음 — Q-IM ext API B-5 보안 패치 미반영 |
| BE | B-IDO-05 | 🟡 MED | `FeApiKeyInterceptor` ONEPASS_AUTH_INCOMING_KEYS 미설정 시 전체 401 |
| FE | F-FE-01 | 🟡 MED | realm: 'ucube-qsign' 하드코딩 (ConversionStep3, RegisterStep3) |
| FE | F-FE-02 | 🟡 MED | `/api/v1/ext/**` 직접 호출 (서버사이드 EXT_API_KEY 주입 미적용) |
| FE | F-FE-03 | 🟢 LOW | 키 번들 노출 위험 (`/api/v1/ext/terms/bundle`) |

### 운영 배포 위험도 (T+0~T+3 시나리오)

| 시점 | 위험 | 영향 |
|------|------|------|
| **T+0** | ONEPASS_AUTH_INCOMING_KEYS 미설정 | 전체 API 401 → 서비스 불가 |
| **T+1** | DB 테이블 없음 (Flyway 미실행) | NullPointerException 다발 |
| **T+2** | Q-Sign InternalSigVerifier MISSING | Q-Sign 인증 연동 전면 불가 |
| **T+3** | AES 모드 미합의 | SP 수신 데이터 복호화 실패 |
