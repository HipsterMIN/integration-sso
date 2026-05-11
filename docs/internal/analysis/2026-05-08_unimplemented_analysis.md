# OnePass Integration-SSO PoC — 미구현 상세 분석 보고서

> **작성일**: 2026-05-08  
> **버전**: v1.7.0 기준  
> **문서 목적**: 표준 SSO/IM 서비스 대비 현재 PoC의 범주 판별 및 미구현 항목 상세 기록  
> **작성 기준**: 실제 소스코드(Java, SQL 마이그레이션, YAML 설정) 전수 분석 결과

---

## 1. 프로젝트 범주 판별

### 1.1 분류

| 구분 | 판별 결과 |
|------|-----------|
| **대분류** | Enterprise Federated SSO + Centralized Identity Management |
| **세부 유형 ①** | OIDC IdP 브로커 (부분 구현) |
| **세부 유형 ②** | Proprietary Handoff Token 기반 기관 연계 (핵심 구현 완료) |
| **세부 유형 ③** | 중앙 Identity Management — Q-IM (부분 구현) |
| **세부 유형 ④** | 다기관 연계 Policy Gateway — IdO (PoC 수준) |

### 1.2 유사 참조 시스템

- 정부 GPKI 연계 게이트웨이
- 금융보안원 인증서 허브
- 카카오/네이버 연합 인증 브로커
- OpenID Connect Federation (draft)

---

## 2. 표준 SSO/IM 패턴 대비 구현 수준

| 패턴 / 기능 | 표준 여부 | 구현 상태 | 비고 |
|---|---|---|---|
| OIDC Authorization Code (RP 역할) | RFC 6749 | ✅ 완료 | Keycloak/Q-Sign 브로커 |
| PKCE (Proof Key for Code Exchange) | RFC 7636 | ❌ 미구현 | 브로커 흐름에 필수 |
| SAML 2.0 Web SSO | OASIS | ❌ 미구현 | 일부 기관 요구 가능성 |
| Token Exchange | RFC 8693 | ❌ 미구현 | 기관 간 위임 시 필요 |
| Proprietary Handoff Token | 자체 설계 | ✅ 완료 | AES-256-GCM + HMAC-SHA256 |
| Backchannel Logout (Kafka) | 자체 설계 | ✅ 완료 | Advisory + Webhook |
| Front-channel Logout | OIDC Core | ❌ 미구현 | — |
| IdP Discovery (hint 기반) | OIDC | ⚠️ 제한적 | provider_config 테이블만 존재 |
| Session Management (BFF) | OIDC Session | ✅ 완료 | HttpOnly + SameSite-Strict |
| SCIM 2.0 (프로비저닝) | RFC 7644 | ❌ 미구현 | 사용자 동기화 없음 |
| JWT Bearer Token (기관 API) | RFC 7519 | ❌ 미구현 | 현재 Handoff Ticket 전용 |
| Webhook 발송 (이벤트 알림) | 자체 설계 | ✅ 완료 | HMAC-SHA256 서명 |
| Rate Limiting (기관별) | — | ❌ 미구현 | — |
| 관리자 Admin API | — | ❌ 미구현 | 기관 온보딩·정책 변경 불가 |

---

## 3. 모듈별 구현 현황 및 미구현 상세

### 3.1 Q-Sign (인증 SoR — 포트 8081)

#### ✅ 구현 완료
- DB 스키마: `qsign` 스키마, `auth_result`, `auth_lock`, `idp_provider`, `outbox` 테이블
- Redis 기반 OIDC 세션 상태 관리 (`KeycloakStateStore`)
- Keycloak OIDC 콜백 처리 (`KeycloakCallbackController`, `KeycloakCallbackService`)
- 인증 잠금 (`auth_lock`) 및 식별자 해시 (SHA-256)
- Kafka Transactional Outbox 발행 (`OutboxRelay`)
- 인증 수준 L1/L2/L3 enum 정의 (`AuthResult.AuthLevel`)
- IdO → Q-Sign 내부 API 호출 수신 (`IdOAuthInputRequest`)

#### ❌ 미구현 항목

| # | 항목 | 설명 | 우선순위 |
|---|------|------|----------|
| QS-01 | **PKCE 지원** | `code_verifier` / `code_challenge` 검증 로직 없음. OIDC 브로커 흐름 보안 취약 | Critical |
| QS-02 | **SAML 2.0 SP** | `idp_provider` 테이블에 SAML 타입 정의는 없으나, Spring Security SAML SP 구현 없음 | High |
| QS-03 | **외부 IdP 직접 연동** | KAKAO_OIDC, NAVER_OIDC, PASS, FINANCIAL_CERT, GPKI 등 `idp_provider` 초기 데이터만 존재. 실제 Authorization URL 생성 및 콜백 처리 없음 | High |
| QS-04 | **인증 결과 만료 처리** | `auth_result` 테이블에 TTL 컬럼 없음. 만료된 인증 결과 자동 정리 스케줄러 부재 | Medium |
| QS-05 | **auth_lock 해제 스케줄러** | `locked_until` 컬럼 존재하나 자동 해제 Job 없음 | Medium |
| QS-06 | **인증 방법 다중화** | `auth_result`의 `auth_method` 필드(V5 마이그레이션 추가)를 활용하는 비즈니스 로직 없음 | Medium |

---

### 3.2 Q-IM (식별 SoR — 포트 8082)

#### ✅ 구현 완료
- DB 스키마: `qim_user`, `auth_mean_mapping`, `user_profile`, `user_status_history`, `outbox` 테이블
- `QimUserJpaEntity`, `UserProfileJpaEntity` JPA 엔티티
- `user_profile` 테이블: `masked_name`, `masked_mobile`, `encrypted_ci`, `nationality_type` 컬럼 정의
- Outbox 패턴 (`OutboxService`, `OutboxRelay`) 구현
- `UserStatus` enum 정의 (ACTIVE, SUSPENDED, WITHDRAWN 등)
- MariaDB 스키마 호환성 (DATETIME(6), JSON 타입)

#### ❌ 미구현 항목

| # | 항목 | 설명 | 우선순위 |
|---|------|------|----------|
| QIM-01 | **CI 암호화/복호화 서비스** | `encrypted_ci` 컬럼 존재하나 실제 AES 암/복호화 서비스 클래스 없음 | Critical |
| QIM-02 | **PII 마스킹 로직** | `masked_name`, `masked_mobile` 컬럼에 저장하는 마스킹 알고리즘 없음 | Critical |
| QIM-03 | **DI(Duplicate Identity) 생성** | `di_map` JSON 컬럼 존재하나 DI 생성 로직 (기관별 해시 조합) 없음 | Critical |
| QIM-04 | **사용자 등록/탈퇴 API** | 인증 결과로부터 신규 사용자 자동 생성(가입) 플로우 없음 | High |
| QIM-05 | **회원 조회 API** | IdO `member_lookup_request` 테이블이 존재하나 Q-IM의 실제 조회 응답 엔드포인트 없음 | High |
| QIM-06 | **SCIM 2.0 엔드포인트** | 외부 기관의 사용자 프로비저닝/동기화 API 없음 | Medium |
| QIM-07 | **사용자 상태 전이 정책** | `user_status_history` 감사 로그 테이블 존재하나, 상태 전이 조건/승인 로직 없음 | Medium |
| QIM-08 | **관리자 콘솔 API** | 사용자 조회·정지·탈퇴 처리용 Admin API 없음 | Medium |
| QIM-09 | **auth_mean_mapping 관리** | 인증 수단 추가·삭제·비활성화 API 없음 | Low |

---

### 3.3 IdO (정책 오케스트레이터 — 포트 8083)

#### ✅ 구현 완료
- `HandoffService` (issue/verify/revoke), `HandoffServiceImpl`
- `HandoffCryptoService` (AES-256-GCM 암호화, HMAC-SHA256 서명)
- `HandoffAgencyKeyInterceptor` (X-Agency-Key SHA-256 해시 검증, Constant-Time 비교)
- `PolicyEngine` / `PolicyEngineImpl` (인증 수준, 점검 시간, 사용자 상태 확인)
- `AgencyMeta` 도메인 + JPA 엔티티 + 이력 테이블
- Resilience4j Circuit Breaker + Retry (qim-client, keycloak-client)
- Kafka Outbox Relay (`IdoOutboxRelay`)
- Webhook Dispatch Outbox (`webhook_dispatch_outbox` 테이블 + V7 마이그레이션)
- 감사 로그 테이블 (`audit_log`, V7) 구조
- `AuditLogPublisher` (비동기 발행)
- BFF 세션 (`FeSessionService`, Redis 기반)
- Keycloak IdP 브로커 (`KeycloakOidcService`)
- `provider_config` 테이블: KAKAO_OIDC, NAVER_OIDC, PASS 등 초기 데이터
- `policy_conflict_log` 테이블 (정책 충돌 기록)
- Traceparent 필터 (분산 추적)

#### ❌ 미구현 항목

| # | 항목 | 설명 | 우선순위 |
|---|------|------|----------|
| IDO-01 | **allowedAttributes 필터링** | `agency_meta.allowed_attributes` JSON 컬럼 존재, `buildHandoffPayload`에 TODO 주석만 존재. 실제 속성 필터 적용 없음 | Critical |
| IDO-02 | **Callback URL 화이트리스트 검증** | `agency_meta.callback_whitelist` 컬럼 존재하나 실제 검증 코드 없음 | Critical |
| IDO-03 | **DI ↔ agencySubjectId 연계** | `generateAgencySubjectId`에서 `qimUserId|agencyCode` HMAC 사용 중이나, 실제 Q-IM DI와 연계되지 않음 | Critical |
| IDO-04 | **member_lookup 서비스** | `member_lookup_request` 테이블 존재하나 조회 요청 처리 서비스 없음 | High |
| IDO-05 | **integration_type 분기 처리** | `agency_meta.integration_type` (DIRECT/APACHE_GATE/BRIDGE/INTERNAL_SSO) 컬럼 존재하나 분기 로직 없음 | High |
| IDO-06 | **기관 Admin API** | 기관 등록·수정·삭제·활성화 REST API 없음. 현재 Flyway SQL로만 데이터 변경 가능 | High |
| IDO-07 | **Webhook Dispatcher 서비스** | `webhook_dispatch_outbox` 테이블 존재하나 실제 HTTP 발송 스케줄러/서비스 없음 | High |
| IDO-08 | **Audit Log 호출 보강** | `AuditLogPublisher` 존재하나 handoff issue/verify 핵심 경로에서 실제 호출 없음 | High |
| IDO-09 | **AES 키 로테이션** | `HandoffCryptoService`에 단일 AES 키만 존재. 키 버전 관리·로테이션 없음 | High |
| IDO-10 | **policy_conflict_log 연동** | 테이블 존재하나 정책 충돌 감지·기록 비즈니스 로직 없음 | Medium |
| IDO-11 | **PKCE relay** | 브로커 흐름에서 PKCE 파라미터 전달 없음 | Medium |
| IDO-12 | **Rate Limiting (기관별)** | API 호출 횟수 제한 없음 (Resilience4j Rate Limiter 미적용) | Medium |
| IDO-13 | **Vault/KMS 연동** | AES·HMAC 키가 환경변수에만 존재. HashiCorp Vault 또는 AWS KMS 연동 없음 | Medium |
| IDO-14 | **maintenance_windows 고급 설정** | cron 표현식 또는 반복 설정 미지원, 단순 요일+시간 범위만 지원 | Low |

---

### 3.4 onepass-fe (React BFF 프론트엔드 — 포트 3000/3001)

#### ✅ 구현 완료
- React 18.3 + TypeScript 5.4 + Vite
- Nginx 정적 빌드 (`localhost:3001`)
- BFF 세션 API 연동 (`FeSessionController`)

#### ❌ 미구현 항목

| # | 항목 | 설명 | 우선순위 |
|---|------|------|----------|
| FE-01 | **IdP Discovery UI** | 사용자가 인증 수단을 선택하는 화면 없음 | High |
| FE-02 | **인증 진행 상태 표시** | OIDC 리다이렉트 중 로딩·에러 화면 미완성 | Medium |
| FE-03 | **관리자 대시보드** | 기관 관리·모니터링 UI 없음 | Medium |

---

### 3.5 agency-stub (외부 기관 시뮬레이터 — 포트 8084)

#### ✅ 구현 완료
- `IdoTicketClient` / `IdoVerifyClient` (Resilience4j CB + Retry)
- `AgencySimulatorController` (7개 엔드포인트: /run, /ticket, /verify, /status, /sessions, /sessions/{id}, /events)
- `WebhookInboundController` (HMAC-SHA256 서명 검증)
- `AgencyEventPollingController` (/poll, /pending-count)
- `AgencyApiKeyInterceptor` (인바운드 API Key 검증)
- `AgencyDataInitializer` (스키마 확인 + API Key 시드)
- E2E 시뮬레이터 UI (http://localhost:8084/)
- V8 Flyway 마이그레이션 (AGENCY_STUB_001 api_key_hash 시드)

#### ❌ 미구현 항목

| # | 항목 | 설명 | 우선순위 |
|---|------|------|----------|
| AS-01 | **다기관 시뮬레이션** | 현재 AGENCY_STUB_001 단일 기관만 지원. 복수 기관 코드 전환 UI 없음 | High |
| AS-02 | **Webhook 재전송 시뮬레이션** | 수신 실패 시나리오·재전송 테스트 기능 없음 | Medium |
| AS-03 | **인증 수준별 시나리오 테스트** | L1/L2/L3 인증 수준 강제 지정 테스트 없음 | Medium |

---

## 4. 보안 현황 분석

### 4.1 구현된 보안 항목 ✅

| 항목 | 구현 위치 |
|------|-----------|
| SHA-256 API Key 해시 (Constant-Time 비교) | `HandoffAgencyKeyInterceptor` |
| AES-256-GCM 티켓 암호화 | `HandoffCryptoService` |
| HMAC-SHA256 티켓 서명 | `HandoffCryptoService` |
| HMAC-SHA256 Webhook 서명 | `WebhookInboundController` |
| HttpOnly + SameSite=Strict 쿠키 | `application.yml` |
| OIDC Nonce 재사용 방지 | `oidc_nonce_used` 테이블 |
| Idempotency-Key 중복 방지 | `IdoTicketClient`, `processed_event` 테이블 |
| 세션 고정 공격 방지 | `FeSessionService` 세션 재생성 |
| 감사 로그 테이블 | `audit_log` (V7) |
| Replay 공격 방지 | `processed_event` + `IdempotentEventStore` |

### 4.2 미구현 보안 항목 ❌

| # | 항목 | 위험도 | 설명 |
|---|------|--------|------|
| SEC-01 | **PKCE** | Critical | 인가 코드 가로채기 공격 가능 |
| SEC-02 | **TLS 적용** | Critical | 현재 모든 서비스 간 통신 HTTP |
| SEC-03 | **Vault/KMS 키 관리** | High | AES·HMAC 키 환경변수 노출 위험 |
| SEC-04 | **키 로테이션** | High | 단일 AES 키 영구 사용 중 |
| SEC-05 | **PII 마스킹 로직** | High | 컬럼만 존재, 실제 마스킹 없음 |
| SEC-06 | **CI 암복호화** | High | 컬럼만 존재, 실제 암복호화 없음 |
| SEC-07 | **Callback URL 화이트리스트** | High | 검증 코드 없음 |
| SEC-08 | **allowedAttributes 필터** | High | PII 과잉 노출 위험 |
| SEC-09 | **Rate Limiting** | Medium | DoS/무차별 대입 방어 없음 |
| SEC-10 | **입력값 검증 강화** | Medium | Bean Validation 부분 적용 |

---

## 5. 운영 현황 분석

### 5.1 구현된 운영 항목 ✅

- Stateless 아키텍처 (Redis 세션)
- Kafka 이벤트 기반 비동기 처리
- Resilience4j Circuit Breaker + Retry
- Health Check 엔드포인트 (`/api/v1/health`, `/actuator/health`)
- Flyway DB 마이그레이션 (V1~V8)
- Idempotency 보장 (Kafka Consumer + 처리 이력)
- At-least-once 전달 (Outbox Pattern)
- Docker Compose 인프라 구성
- 수평 확장 설계 (Stateless + Shared Redis/Kafka)
- 분산 추적 (Traceparent Filter)
- 비동기 실행자 풀 (AsyncConfig)

### 5.2 미구현 운영 항목 ❌

| # | 항목 | 설명 | 우선순위 |
|---|------|------|----------|
| OPS-01 | **관리자 콘솔 (Admin UI)** | 기관 온보딩·정책 변경·모니터링 UI 없음 | High |
| OPS-02 | **자동 기관 온보딩** | 신규 기관 등록 시 SQL 직접 실행 필요 | High |
| OPS-03 | **ELK/로그 집계** | 각 모듈 로그 분산, 중앙 집계 없음 | Medium |
| OPS-04 | **Prometheus + Grafana** | Actuator 지표만 존재, 시각화 없음 | Medium |
| OPS-05 | **알림 (Alert)** | Circuit Breaker 오픈·에러 증가 시 알림 없음 | Medium |
| OPS-06 | **기관별 Rate Limit 대시보드** | 기관 호출량 모니터링 없음 | Low |
| OPS-07 | **Webhook 발송 재시도 모니터링** | `webhook_dispatch_outbox` 실패 건 추적 UI 없음 | Low |

---

## 6. 기관 연동 요구사항 충족 분석 (다수 기관 기준)

| 요구사항 | 충족 여부 | 비고 |
|---------|-----------|------|
| 기관별 독립 API Key (SHA-256 해시 검증) | ✅ | `HandoffAgencyKeyInterceptor` |
| 기관별 최소 인증 수준 정책 | ✅ | `PolicyEngine.meetsMinAuthLevel` |
| 기관별 점검 시간 관리 | ✅ | `PolicyEngine.isUnderMaintenance` |
| 사용자 세션 기관 간 격리 | ✅ | `FeSession` Redis 격리 |
| 이벤트 기반 세션 무효화 | ✅ | Kafka Advisory + Webhook |
| 기관별 속성 필터링 | ⚠️ | `allowed_attributes` 컬럼만 존재 |
| Callback URL 화이트리스트 | ⚠️ | 컬럼만 존재, 검증 코드 없음 |
| 연동 타입 분기 (DIRECT/BRIDGE 등) | ⚠️ | 컬럼만 존재, 분기 로직 없음 |
| PII 마스킹 / CI 암호화 | ⚠️ | 컬럼만 존재, 구현 없음 |
| 기관 온보딩 자동화 | ❌ | SQL 직접 실행만 가능 |
| 기관별 Rate Limiting | ❌ | 없음 |
| 다중 IdP (실 외부 연동) | ❌ | provider_config 데이터만 존재 |

---

## 7. 전체 완성도 요약

| 영역 | 완성도 | 비고 |
|------|--------|------|
| 핵심 SSO 흐름 | **85%** | Handoff Token 흐름 완성 |
| 보안 | **62%** | PKCE·TLS·키관리·PII 미구현 |
| Identity Management | **45%** | CI암호화·DI생성·회원API 없음 |
| 기관 연동 정책 | **60%** | 속성필터·화이트리스트·분기 없음 |
| 운영/관측성 | **65%** | 콘솔·모니터링·알림 없음 |
| 실제 IdP 연동 | **25%** | 설정 데이터만 존재 |
| **종합** | **≈ 57%** | PoC 검증 단계 완료 수준 |

> **결론**: 핵심 Handoff 흐름과 기관 인증 검증은 운영 수준에 근접하나, PII 보호(CI 암호화·마스킹·DI), 속성 필터링, Callback 화이트리스트, 실 IdP 연동, Admin API 등 Critical 항목 미해결 상태에서는 실 서비스 배포 불가. **최소 4~5 스프린트(8~10주)** 추가 개발 필요.

---

*문서 끝 — 다음 문서: `2026-05-08_production_development_plan.md`*
