# 12. 미구현 항목 및 후속 계획 (Implementation Gaps)

> **문서 버전**: v1.9.3  
> **최종 수정**: 2026-05-09  
> **기준 분석 문서**: `docs/2026-05-08_unimplemented_analysis.md`, `docs/gap-analysis-v0.8.3-vs-project.md`  
> **v1.9.2 변경**: P2 GAP 항목 전체 구현 완료 (HandoffStrategy 완성, GAP-QS-03, GAP-QIM-05)  
> **v1.9.3 변경**: P1-06 구현 완료 — IdO `GET /api/v1/agency/events` 기관 이벤트 폴링 API

---

## 1. 현재 완성도 요약

v1.9.3 기준 전체 구현 완성도: **약 86%** (PoC → 프리프로덕션 단계)

| 모듈 | 완성도 | 비고 |
|------|--------|------|
| platform-common | **100%** | 도메인·이벤트·에러코드 완비 |
| Q-Sign | **95%** | GAP-QS-03 멱등 컨슈머 완성; X-Internal-Sig 수신 검증 미구현 |
| Q-IM | **92%** | GAP-QIM-05 Snapshot 완성; 고급 전환·탈퇴 흐름 미완성 |
| IdO | **99%** | P1-06 기관 폴링 API 완성; HandoffStrategy 완전 구현 |
| agency-stub | **90%** | Docker 격리 미완성, mTLS P3 |
| onepass-fe | **60%** | 회원 전환·관리 UI 미구현 |
| 인프라/Docker | **100%** | 전 모듈 Dockerfile + docker-compose 완비 |
| 보안 | **93%** | DLQ, X-Internal-Sig 수신 검증 미완성 |
| 테스트 | **0%** | 단위·통합 테스트 미작성 |

---

## 2. P0 — 즉시 처리 필요 (운영 차단)

| ID | 항목 | 담당 모듈 | 설명 |
|----|------|---------|------|
| P0-01 | Kakao OAuth 실제 Client Secret 설정 | Q-Sign | Keycloak Admin에서 `q-sign-client` Secret 발급 후 `.env` 설정 |
| P0-02 | 운영 DB 비밀번호 변경 | 전체 | 기본값 `onepass` → 운영용 강력한 비밀번호 |
| P0-03 | 운영 AES/HMAC 키 교체 | IdO | 기본값 `change-me-*` → 운영용 32바이트 이상 랜덤 키 |

---

## 3. P1 — 다음 스프린트 우선 처리

### 3.1 보안

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| P1-03 | X-Internal-Sig 수신 측 검증 | IdO | `OidcCompleteController`에서 HMAC-SHA256 재계산 + `±60초` 타임스탬프 검증 |
| GAP-QS-04 | Q-Sign X-Internal-Sig 수신 검증 | Q-Sign | `AuthController` 헤더 검증 구현 |
| GAP-IDO-09 | Kafka DLQ `DeadLetterPublishingRecoverer` | IdO | `KafkaConsumerConfig.defaultErrorHandler()`에 DLQ 연결 (6개 필드 보존) |

### 3.2 Q-IM 기능 완성

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| GAP-QIM-01 | `needsSync=true` → Selective Pull 실제 호출 | IdO/Q-IM | `QimEventConsumer`에서 `QimClient.getUserById()` 실제 호출 |
| GAP-QIM-03 | `addAuthMeanMapping()` JPA 저장 구현 | Q-IM | TODO 주석 제거 후 실제 저장 로직 완성 |
| GAP-QIM-04 | Outbox `markFailed()` + `retry_count` 증가 | Q-IM | `OutboxServiceImpl` 실패 처리 완성 |

### 3.3 API 계약

| ID | 항목 | 담당 모듈 | 작업 내용 |
|----|------|---------|---------|
| GAP-API-02 | `Idempotency-Key` 헤더 | IdO | `HandoffController`에 중복 Ticket 발급 방지 |
| GAP-API-04 | `Retry-After` 헤더 | 전체 | `GlobalExceptionHandler`에 `E-OPS-901` + `Retry-After` 추가 |

### 3.4 기관 연동

| ID | 항목 | 담당 모듈 | 작업 내용 | 상태 |
|----|------|---------|---------|------|
| ~~P1-06~~ | ~~agency-stub 이벤트 폴링 API 완성~~ | ~~IdO~~ | ~~`GET /api/v1/agency/events` 완전 구현~~ | ✅ **완료** (v1.9.3) |

**P1-06 구현 파일** (v1.9.3):
- `ido/.../api/dto/AgencyEventResponse.java` — 이벤트 단건 응답 DTO
- `ido/.../api/dto/AgencyEventListResponse.java` — 목록 응답 래퍼 (hasMore 커서 포함)
- `ido/.../webhook/AgencyEventQueryService.java` — 폴링 조회 서비스 인터페이스
- `ido/.../webhook/AgencyEventQueryServiceImpl.java` — `webhook_dispatch_outbox` JdbcTemplate 조회 + `markAsRead()`
- `ido/.../api/AgencyEventController.java` — `GET /api/v1/agency/events` + `POST /{dispatchId}/read`
- `ido/.../fe/config/IdoWebMvcConfig.java` — `/api/v1/agency/**` 인터셉터·CORS 등록

---

## 4. P2 — 중기 구현 대상

### 4.1 Q-IM 회원 생명주기

| ID | 항목 | 설명 |
|----|------|------|
| - | CI값 기반 68개 유관시스템 회원 조회 | `AgencyMemberLookupService` (PPTX 2.1 프로세스) |
| - | 통합계정 UUID 생성 및 연결 대상 선택 | ConversionSession 상태 기계 |
| - | 기업회원 전환 (사업자등록번호 기반) | Q-IM 기업회원 지원 |
| - | 14세 미만 보호자 인증 분기 | 미성년자 보호자 인증 흐름 |
| - | 개인정보 동의 기록 (제3자 정보제공 동의) | `ido.consent_record`, `ido.consent_version` |
| - | 회원 탈퇴 4종 전체 구현 | IMMEDIATE/SCHEDULED/AGENCY_REQUESTED/ADMIN_FORCED |
| - | 논리적 삭제 + 보존기간 만료 영구파기 | GDPR Right to be Forgotten |

### 4.2 Handoff 전략 완성 ✅ v1.9.2 완료

| ID | 항목 | 설명 | 상태 |
|----|------|------|------|
| ~~-~~ | ~~INTERNAL_SSO HandoffStrategy~~ | ~~`sso_domain` 기반 쿠키 세션 발급~~ | ✅ **완료** (v1.9.2) |
| ~~-~~ | ~~APACHE_GATE HandoffStrategy~~ | ~~Apache mod_auth 호환 헤더 주입~~ | ✅ **완료** (v1.9.2) |

**구현 파일**:
- `ido/.../handoff/strategy/InternalSsoHandoffStrategy.java` — `POST {ssoDomain}/internal/sso-session`
- `ido/.../handoff/strategy/ApacheGateHandoffStrategy.java` — Apache `X-Remote-User`, `X-Auth-Level`, `X-Handoff-Token` 헤더 Push

### 4.3 인프라

| ID | 항목 | 설명 |
|----|------|------|
| P2-07 | agency-stub Kafka 직접 구독 제거 | PoC 코드 정리 → Webhook/폴링 방식으로 교체 |
| P2-06 | agency-stub Docker 격리 | 별도 네트워크 또는 host 모드 |
| ~~GAP-QS-03~~ | ~~`qsign.processed_event` migration + IdempotentEventStore~~ | ~~Q-Sign 멱등 컨슈머~~ | ✅ **완료** (v1.9.2) |
| ~~GAP-QIM-05~~ | ~~`snapshot_meta` 사용 로직 구현~~ | ~~Q-IM Snapshot 발행 기능~~ | ✅ **완료** (v1.9.2) |

**GAP-QS-03 구현 파일**:
- `q-sign/.../kafka/IdempotentEventStore.java` — `qsign.processed_event` + `qsign.last_event_version` ON CONFLICT 패턴
- `q-sign/.../kafka/QimUserEventConsumer.java` — `@KafkaListener` + 6단계 멱등 처리 + USER_SUSPENDED/WITHDRAWN → auth_lock 잠금

**GAP-QIM-05 구현 파일**:
- `q-im/.../entity/SnapshotMetaJpaEntity.java` — `snapshot_meta` 테이블 JPA 매핑
- `q-im/.../repository/SnapshotMetaJpaRepository.java` — 최신 스냅샷 조회, 중복 방지
- `q-im/.../outbox/SnapshotService.java` / `SnapshotServiceImpl.java` — 10개 이벤트마다 스냅샷 발행
- `q-im/.../outbox/OutboxServiceImpl.java` — `relayPendingEvents()` 스냅샷 트리거 분기 추가

### 4.4 프론트엔드

| ID | 항목 | 설명 |
|----|------|------|
| - | 회원 가입/전환 UI | PPTX 프로세스 매핑 |
| - | 개인정보 동의 UI | 제3자 제공 동의 |
| - | 회원정보 관리 UI | ID/PW 찾기, 정보 수정 |

---

## 5. P3 — 장기 구현 대상

| ID | 항목 | 설명 |
|----|------|------|
| - | Micrometer 커스텀 메트릭 | Handoff 성공률, CB 상태, Ticket 재사용 |
| - | Admin Console UI | React 기반 기관 관리 대시보드 |
| - | E2E 자동화 테스트 | Playwright 또는 RestAssured (6종 시나리오) |
| - | mTLS 기관 인증 | Nginx/Gateway 레벨 클라이언트 인증서 검증 |
| - | 네이버 OIDC 실 연동 | NaverOidcService 구현 |
| - | 카카오 OIDC 실 연동 테스트 | 실 Client ID/Secret 필요 |
| - | 부하 테스트 | k6/Gatling, 목표: 200 TPS, p99 < 150ms |
| - | 보안 스캔 | OWASP ZAP |

---

## 6. 기술 부채 (v3.0 이후)

| ID | 항목 | 설명 |
|----|------|------|
| DEBT-01 | HashiCorp Vault / AWS KMS 연동 | 환경변수 키 관리 → 전용 KMS 이전 |
| DEBT-02 | SAML 2.0 SP 구현 | 일부 공공기관 SAML 요구 대응 |
| DEBT-03 | SCIM 2.0 엔드포인트 | 외부 IdM 시스템 사용자 동기화 |
| DEBT-04 | JWT Bearer Token (기관 API) | Handoff 외 일반 API 인증 |
| DEBT-05 | DB 스키마 완전 격리 | 현재 단일 PostgreSQL → 기관별 schema 격리 |
| DEBT-06 | OpenTelemetry 완전 연동 | TraceparentFilter → OTel SDK 전환 |
| DEBT-07 | 다중 기관 CI/CD | 기관별 독립 배포 파이프라인 |
| DEBT-08 | 쿠버네티스 Helm Chart | K8s 기반 운영 배포 |

---

## 7. Sprint 계획 (PoC → 운영 전환)

```
Sprint 1~2  (2주)  보안 완성
  - X-Internal-Sig 수신 검증
  - DLQ DeadLetterPublishingRecoverer
  - Outbox markFailed + retry_count
  - addAuthMeanMapping JPA 저장

Sprint 3  (2주)  Q-IM 핵심 기능
  - 사용자 등록/조회/탈퇴 API 완성
  - 개인정보 동의 스키마
  - ConversionSession 상태 기계

Sprint 4  (2주)  API 계약 완성
  - Idempotency-Key 처리
  - Retry-After 헤더
  - INTERNAL_SSO/APACHE_GATE Strategy

Sprint 5  (2주)  운영 기반
  - 부하 테스트 (200 TPS 목표)
  - 커스텀 메트릭 구현
  - agency-stub 격리

Sprint 6  (2주)  실 IdP 연동 & 검증
  - 카카오/네이버 실 Client Secret 적용
  - E2E 자동화 테스트 6종
  - 보안 취약점 스캔
```

---

## 8. Q-IM 팀 협의 필요 사항

v1.9.0 기준 미합의 사항 (Q-IM SP 연동 관련):

| 항목 | 현재 가정 | Q-IM 확인 필요 | 우선순위 |
|------|---------|--------------|---------|
| encCi 알고리즘/패딩 | AES-256-CBC 예상 | 정확한 모드/패딩/IV 전달 방식 | 🔴 P0 |
| AES 공유키 회전 정책 | 수동 교체 가능 | 회전 주기, 유예기간, 무중단 교체 방식 | 🔴 P0 |
| Idempotency-Key 보관 기간 | 7일 예정 | Q-IM 측 재판단 기간과 일치 여부 | 🔴 P0 |
| instMbrId 정책 | qimUserId와 동일 UUID | Q-IM이 다른 형식을 요구하는지 | 🔴 P0 |
| SP 수신 endpoint URL | `/api/qim/sp/v1/member/*` | Q-IM 콘솔 등록 전 URL 확정 | 🔴 P0 |
| 412 재시도 상한 | 3회 + Outbox fallback | Q-IM 측 최대 잠금 유지 시간 | 🟡 P1 |
| CONVERSION/PROVISION API 의무 여부 | 현재 미포함 | 선택 API 구현 필요 여부 | 🟡 P1 |

---

*다음 문서: [13-development-history.md](13-development-history.md)*
