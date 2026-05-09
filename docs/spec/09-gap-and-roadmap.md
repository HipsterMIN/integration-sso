# 09. 미구현 현황, Sprint 계획, 기술 부채 및 로드맵

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **전체 완성도**: 약 86% (PoC → 프리프로덕션 단계)

---

## 1. 전체 완성도 요약

```
platform-common  ████████████████████ 100%  (도메인·이벤트·에러코드 완비)
Q-Sign           ████████████████████  95%  (X-Internal-Sig 수신 검증 미완)
Q-IM             ██████████████████░░  92%  (회원 전환·탈퇴 흐름 미완)
IdO              ████████████████████  99%  (DLQ 연결, Idempotency-Key 미완)
agency-stub      ████████████████████  90%  (Docker 격리 미완, mTLS P3)
onepass-fe       ████████████░░░░░░░░  60%  (회원 전환·관리 UI 미구현)
인프라/Docker    ████████████████████ 100%  (전 모듈 Dockerfile + Compose 완비)
보안             ██████████████████░░  93%  (X-Internal-Sig 수신 미완)
테스트           ░░░░░░░░░░░░░░░░░░░░   0%  (단위·통합 테스트 전무)
```

---

## 2. 운영 차단 항목 (P0 — 즉시 처리)

운영 전환 전 반드시 조치해야 하는 환경/보안 설정.

| ID | 항목 | 담당 모듈 | 처리 방법 |
|----|------|---------|---------|
| P0-01 | Kakao OAuth 실제 Client Secret 설정 | Q-Sign | Keycloak Admin → `q-sign-client` → Credentials |
| P0-02 | 운영 DB 비밀번호 변경 | 전체 | 기본값 `onepass` → 운영용 강력한 비밀번호 + .env 관리 |
| P0-03 | 운영 AES/HMAC 키 교체 | IdO | `change-me-*` → 운영용 32바이트+ 랜덤 키 |
| P0-04 | Q-IM 팀 협의 완료 | Q-IM/IdO | encCi 알고리즘, Idempotency-Key TTL, SP endpoint URL |

---

## 3. 다음 스프린트 우선 처리 항목 (P1)

### 3.1 보안 완성

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| P1-03 | X-Internal-Sig 수신 측 검증 | IdO | `OidcCompleteController`에 HMAC-SHA256 + ±60s 타임스탬프 검증 |
| GAP-QS-04 | Q-Sign X-Internal-Sig 수신 검증 | Q-Sign | `AuthController` 헤더 검증 구현 |
| GAP-IDO-09 | Kafka DLQ `DeadLetterPublishingRecoverer` | IdO | `KafkaConsumerConfig`에 DLQ 연결 (6개 필드 보존) |

### 3.2 Q-IM 기능 완성

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| GAP-QIM-01 | `needsSync=true` → Selective Pull 실제 호출 | IdO/Q-IM | `QimEventConsumer`에서 `QimClient.getUserById()` 실제 구현 |
| GAP-QIM-03 | `addAuthMeanMapping()` JPA 저장 구현 | Q-IM | TODO 주석 제거 후 실제 저장 로직 |
| GAP-QIM-04 | Outbox `markFailed()` + `retry_count` 증가 | Q-IM | `OutboxServiceImpl` 실패 처리 |

### 3.3 API 계약 완성

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| GAP-API-02 | `Idempotency-Key` 헤더 | IdO | `HandoffController`에 중복 Ticket 발급 방지 |
| GAP-API-04 | `Retry-After` 헤더 | 전체 | `GlobalExceptionHandler`에 `E-OPS-901` + `Retry-After` |

---

## 4. 중기 구현 대상 (P2)

### 4.1 Q-IM 회원 생명주기

| 항목 | 설명 |
|------|------|
| CI값 기반 68개 유관시스템 회원 조회 | `AgencyMemberLookupService` |
| 통합계정 UUID 생성 및 연결 대상 선택 | `ConversionSession` 상태 기계 |
| 기업회원 전환 (사업자등록번호 기반) | Q-IM 기업회원 지원 |
| 14세 미만 보호자 인증 분기 | 미성년자 보호자 인증 흐름 |
| 개인정보 동의 기록 (제3자 정보제공 동의) | `ido.consent_record`, `ido.consent_version` |
| 회원 탈퇴 4종 | IMMEDIATE/SCHEDULED/AGENCY_REQUESTED/ADMIN_FORCED |
| 논리적 삭제 + 보존기간 만료 영구파기 | GDPR Right to be Forgotten |

### 4.2 인프라 정리

| ID | 항목 | 설명 |
|----|------|------|
| P2-07 | agency-stub Kafka 직접 구독 제거 | Webhook/폴링 방식으로 전환 |
| P2-06 | agency-stub Docker 격리 | 별도 네트워크 또는 host 모드 |

### 4.3 프론트엔드 완성

| 항목 | 설명 |
|------|------|
| 회원 가입/전환 UI | PPTX 프로세스 매핑 |
| 개인정보 동의 UI | 제3자 제공 동의 |
| 회원정보 관리 UI | ID/PW 찾기, 정보 수정 |

---

## 5. 장기 구현 대상 (P3)

| ID | 항목 | 설명 |
|----|------|------|
| P3-01 | Micrometer 커스텀 메트릭 | Handoff 성공률, CB 상태, Ticket 재사용 감지 |
| P3-02 | Admin Console UI | React 기반 기관 관리 대시보드 |
| P3-03 | E2E 자동화 테스트 | Playwright 또는 RestAssured (6종 시나리오) |
| P3-04 | mTLS 기관 인증 | Nginx/Gateway 레벨 클라이언트 인증서 |
| P3-05 | 네이버 OIDC 실 연동 | `NaverOidcService` 구현 |
| P3-06 | 카카오 OIDC 실 연동 테스트 | 실 Client ID/Secret 필요 |
| P3-07 | 부하 테스트 | k6/Gatling, 목표: 200 TPS, p99 < 150ms |
| P3-08 | 보안 취약점 스캔 | OWASP ZAP |

---

## 6. 기술 부채 (v3.0 이후)

| ID | 항목 | 설명 |
|----|------|------|
| DEBT-01 | HashiCorp Vault / AWS KMS 연동 | 환경변수 키 관리 → 전용 KMS |
| DEBT-02 | SAML 2.0 SP 구현 | 일부 공공기관 SAML 요구 대응 |
| DEBT-03 | SCIM 2.0 엔드포인트 | 외부 IdM 시스템 사용자 동기화 |
| DEBT-04 | JWT Bearer Token (기관 API) | Handoff 외 일반 API 인증 |
| DEBT-05 | DB 스키마 완전 격리 | 단일 PostgreSQL → 기관별 schema 격리 |
| DEBT-06 | OpenTelemetry 완전 연동 | TraceparentFilter → OTel SDK 전환 |
| DEBT-07 | 다중 기관 CI/CD | 기관별 독립 배포 파이프라인 |
| DEBT-08 | 쿠버네티스 Helm Chart | K8s 기반 운영 배포 |

---

## 7. Sprint 계획 (PoC → 운영 전환)

```
Sprint 1~2  (2주)  보안 완성
  ✅ 착수 가능
  - X-Internal-Sig 수신 검증 (P1-03, GAP-QS-04)
  - DLQ DeadLetterPublishingRecoverer (GAP-IDO-09)
  - Outbox markFailed + retry_count (GAP-QIM-04)
  - addAuthMeanMapping JPA 저장 (GAP-QIM-03)

Sprint 3  (2주)  Q-IM 핵심 기능
  - Selective Pull 실제 구현 (GAP-QIM-01)
  - 개인정보 동의 스키마
  - ConversionSession 상태 기계 기초

Sprint 4  (2주)  API 계약 완성
  - Idempotency-Key 처리 (GAP-API-02)
  - Retry-After 헤더 (GAP-API-04)
  - 회원 탈퇴 4종 구현

Sprint 5  (2주)  운영 기반
  - 부하 테스트 (200 TPS 목표)
  - 커스텀 Micrometer 메트릭
  - agency-stub 격리 (P2-06)

Sprint 6  (2주)  실 IdP 연동 & 검증
  - 카카오/네이버 실 Client Secret 적용
  - E2E 자동화 테스트 6종
  - OWASP ZAP 보안 스캔
```

---

## 8. Q-IM 팀 협의 필요 사항

| 항목 | 현재 가정 | Q-IM 확인 필요 | 우선순위 |
|------|---------|----------|---------|
| encCi 알고리즘/패딩 | AES-256-GCM | 정확한 모드·패딩·IV 전달 방식 | 🔴 P0 |
| AES 공유키 회전 정책 | 수동 교체 | 회전 주기·무중단 교체 방식 | 🔴 P0 |
| Idempotency-Key 보관 기간 | 7일 | Q-IM 재판단 기간 일치 여부 | 🔴 P0 |
| instMbrId 정책 | qimUserId와 동일 UUID | Q-IM이 다른 형식 요구 여부 | 🔴 P0 |
| SP 수신 endpoint URL | `/api/qim/sp/v1/member/*` | Q-IM 콘솔 등록 전 URL 확정 | 🔴 P0 |
| 412 재시도 상한 | 3회 + Outbox fallback | Q-IM 최대 잠금 유지 시간 | 🟡 P1 |
| CONVERSION/PROVISION API | 현재 미포함 | 선택 API 구현 필요 여부 | 🟡 P1 |

---

## 9. 구현 완료 항목 기념 목록 (v1.0~v1.9.3)

| 버전 | 주요 구현 내용 |
|------|--------------|
| v1.0~v1.3 | 기본 OIDC 브로커, Q-Sign auth_result, PostgreSQL 스키마 |
| v1.4 | Docker 컨테이너화 + 보안 강화 |
| v1.5 | 유관기관 외부망 Webhook 연동 전체 스택 |
| v1.6 | agency-stub OIDC 클라이언트 완전 구현 |
| v1.7 | E2E 시뮬레이터 + IdO X-Agency-Key 검증 인터셉터 + Web UI |
| v1.8 | Admin API, Rate Limiter, HandoffStrategy 패턴, PKCE, 모니터링 스택 |
| v1.9.0 | P0/P1/P2 GAP 마감 — auth_result V10, broker_audit_log, ProviderRouter, 동적 CB |
| v1.9.1 | P1 GAP 마감 — DLQ 완전 구현, X-Internal-Sig, Outbox 재시도 스케줄러 |
| v1.9.2 | P2 GAP 마감 — HandoffStrategy 완성, GAP-QS-03 멱등 컨슈머, GAP-QIM-05 Snapshot |
| v1.9.3 | P1-06 완성 — 기관 이벤트 폴링 API `GET /api/v1/agency/events` |

---

*이전 문서: [08-infrastructure.md](08-infrastructure.md)*  
*인덱스: [00-index.md](00-index.md)*
