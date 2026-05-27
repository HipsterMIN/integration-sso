# 03-C-Charter. Q-IM 책임 헌장 (Responsibility Charter)

> **상태**: 정본 (Source of Truth)
> **작성일**: 2026-05-26
> **기준 커밋**: `a564f36` (main, PR #199 머지 후)
> **선행 문서**: [03c-module-qim.md](03c-module-qim.md), [01-system-overview.md](01-system-overview.md)
> **목적**: Q-IM 모듈이 "무엇을 책임지고 무엇을 책임지지 않는가"를 명문화. 인접 모듈(IdO / Q-Sign / agency-stub / onepass-fe / onepass-support / onepass-agent) 팀이 Q-IM에 요구해서는 안 되는 것과 반드시 요구해야 하는 것을 단일 페이지로 합의한다.

---

## 0. TL;DR — 한 페이지 요약

| 구분 | Q-IM 책임 (DO) | Q-IM 비책임 (DO NOT) |
|------|-------------|-------------|
| **데이터** | `qim` 스키마 단일 원장 (qim_user, auth_mean_mapping, user_profile, consent_*, conversion_session, guardian_*, biz_member, outbox, snapshot_meta) | Q-Sign·IdO·agency 도메인 테이블 |
| **식별자** | `qimUserId`(UUIDv4) 발급·전 생애 관리, `identifierHash` ↔ `qimUserId` 단방향 매핑 | Keycloak `sub`, IdO `handoffTicketId`, agency `instMbrId` 원본 |
| **암호 자산** | CI AES-256-GCM 암복호화, DI HMAC-SHA256 결정론 생성, PII 마스킹 | JWT 서명/검증, Handoff Ticket 암호화, mTLS, 세션 토큰 |
| **이벤트** | `qim.user.events` / `qim.user.snapshot` **발행만** (Outbox 기반) | Q-IM이 다른 모듈 토픽 발행 금지. Q-IM은 외부 토픽 **소비도 원칙 금지** (예외 §3.3 참조) |
| **API 표면** | `/api/v1/internal/**` (서비스 메쉬 내부) + `/api/v1/users/{id}` 한 건만 외부 노출 | 공개 인터넷 / FE 직접 호출 / agency 직접 호출 (모두 IdO 경유) |
| **트랜잭션** | DB ⇄ Outbox 같은 트랜잭션, At-Least-Once 발행 보장 | At-Most-Once / 분산 트랜잭션 / 2PC |
| **인증·인가** | 자기 API 호출자 식별 (호출자=IdO 만 허용) | 사용자 비밀번호/로그인 검증, OIDC 토큰 발급, RBAC 결정 |
| **UI** | **영구히 없음** — Q-IM은 어떠한 사람-대상 화면도 갖지 않는다 (§6.5 절대 금지선) | Q-IM 관리자 페이지·어드민 콘솔·운영 대시보드·"한 번만" 임시 화면 (전부 ❌) |
| **운영 데이터 노출** | `processed_event`, `outbox_record.status`, `snapshot_meta` 등 자기 메트릭 | 다른 모듈의 운영 메트릭 통합 대시보드 |

> **황금 규칙 (Golden Rule)**
> *"qimUserId 가 등장하는 모든 진실은 Q-IM 에서 나온다. 그 외 모든 것은 Q-IM 의 일이 아니다."*

---

## 1. SoR 경계 — Source of Record Boundary

### 1.1 Q-IM이 SoR인 것 (이것이 깨지면 Q-IM 책임)

| 자산 | 저장 위치 | 외부 접근 방법 | 정합성 보장 |
|------|---------|------------|------------|
| `qimUserId` | `qim_user.qim_user_id` (PK) | 발급 시 응답 / 이벤트 / `GET /api/v1/internal/users/{qimUserId}` | UUIDv4, 한 번 발급되면 불변 |
| CI 암호문 | `qim_user.enc_ci` (AES-256-GCM, 버전 헤더) | **원문 노출 금지**. 내부에서도 복호화는 §2.1 조건부 | 키 회전 시 무중단 (§2.2) |
| CI 해시 | `qim_user.ci_hash` (SHA-256) | `POST /api/v1/internal/member/lookup-by-ci` | 결정론, 충돌 시 동일 회원 단언 |
| DI | 응답 시 즉시 계산 (저장 안 함) | `GET /api/v1/internal/users/{qimUserId}/di?agencyCode=` | `HMAC-SHA256(SHA256(CI)+":"+agencyCode, QIM_DI_SECRET)` |
| `identifierHash` 매핑 | `auth_mean_mapping` (provider × identifierHash → qimUserId) | `POST /api/v1/internal/users/find-by-social-sub` | 동일 hash는 동일 qimUserId 단언 |
| 회원 상태 | `qim_user.status` (ACTIVE / SUSPENDED / WITHDRAWAL_SCHEDULED / WITHDRAWN) + `user_status_history` | `PATCH /api/v1/internal/users/{qimUserId}/status` | 단방향 전이 (역방향 거부) |
| 동의 이력 | `consent_record` (사용자 × consent_version 시계열) | `/api/v1/internal/users/{qimUserId}/consents` | append-only, **정정·삭제 금지** |
| 전환 세션 상태 | `conversion_session` (FSM: STARTED → CANDIDATES_FETCHED → SELECTED → LINKED / EXPIRED / CANCELLED) | `/api/v1/internal/conversion/{sessionId}/*` | TTL 만료 후 자동 EXPIRED |
| 보호자 동의 | `guardian_consent_*` (V6) | `/api/v1/internal/guardian/*` | 14세 미만 가입 전 100% 선행 |
| 기업 회원 매핑 | `biz_member` (V6) | `/api/v1/internal/biz-members/*` | 사업자번호 ↔ qimUserId 1:N |

### 1.2 Q-IM이 SoR가 **아닌** 것 (다른 모듈에 위임 — Q-IM이 저장하면 안 됨)

| 자산 | SoR 위치 | Q-IM의 입장 |
|------|--------|-----------|
| 인증 결과 (success/fail, providerSub) | **Q-Sign** `qsign.auth_result` | 이벤트로 통보받음 — Q-IM은 사본 보관 금지 |
| Keycloak `sub` 원본 | **Keycloak** | Q-IM은 `SHA-256(sub)` 해시만 `auth_mean_mapping.identifier_hash`에 저장 |
| Handoff Ticket | **IdO** `ido.handoff_ticket` | Q-IM은 ticket 발급/검증에 관여하지 않음 |
| 기관 세션 (agency session) | **agency-stub** / 각 기관 | Q-IM은 세션 ID를 모름 |
| 사용자 비밀번호 / OTP / 생체정보 | **Keycloak** | Q-IM 스키마에 패스워드 컬럼 없음. 절대 만들지 않음 |
| 기관별 회원 ID (instMbrId 의 외부 의미) | **각 기관 / agency-stub** | Q-IM은 SP 수신 이벤트로 통보받아 `auth_mean_mapping` 에만 보관 |
| FE 세션 / 쿠키 / CSRF 토큰 | **IdO** `feSessionId` (Redis) | Q-IM은 FE 세션의 존재를 모른다 |
| Webhook 발송 이력 / 폴링 큐 | **IdO** `webhook_dispatch_outbox` | Q-IM은 자기 이벤트만 발행, 외부 통보 책임 없음 |
| Rate Limit 카운터 / Circuit Breaker 상태 | **IdO** Redis | Q-IM 자기 보호용 RL 은 가능하나, 글로벌 RL 결정자 아님 |
| 감사 로그 (`platform.audit.log`) | **IdO** `AuditLogPublisher` | Q-IM은 자기 로그를 IdO 감사 토픽에 *직접* 발행하지 않음. 표준 Kafka 이벤트로만 전달 |

---

## 2. 암호 자산 책임 — Crypto Ownership

### 2.1 CI 암복호화

**책임 범위**:
- **암호화 알고리즘**: AES-256-GCM 고정 (다른 알고리즘 도입은 ADR 필요)
- **포맷**: `v{version}:{base64(IV(12B) || ciphertext || tag(16B))}`
- **인터페이스**: `CiCryptoService.encrypt(rawCi): String` / `decrypt(encCi): String`

**복호화가 허용되는 시점 (3가지만)**:
1. SP 수신 이벤트 처리 (`qim.sp.member.events` 컨슈머에서 비교용)
2. DI 생성 요청 응답 (`SHA256(ci)` 계산 후 즉시 메모리 폐기)
3. 운영 감사 명령 (`MANUAL` audit, 2인 승인 + 감사 로그)

**복호화가 금지된 시점**:
- API 응답 본문에 평문 CI 포함 (어떤 경우에도 ❌)
- 로그/예외 메시지에 평문 CI 노출 (로깅 필터 강제)
- 다른 모듈에 평문 CI 전달 (전달은 항상 암호문 또는 해시)

### 2.2 키 회전 (Key Rotation)

| 항목 | 정책 |
|------|------|
| 키 보관 | `application.yml: qim.ci-encryption.keys[]` (운영은 NHN KMS) |
| 버전 헤더 | 모든 암호문 앞단에 `v{n}:` 접두 — 복호화 시 어떤 키를 쓸지 결정 |
| 회전 방식 | **무중단**. 신 버전 키 추가 → `current-version` 변경 → 신규는 신키로 암호화, 기존은 옛 키로 복호화 가능 |
| 백필 (rewrap) | 정책에 따라 별도 배치. **Q-IM의 자기 책임**, 다른 모듈은 관여 불가 |
| 침해 대응 | 손상된 키 버전 발견 시 즉시 `current-version` 변경 + 영향 회원 재발급. 외부 통보는 IdO 감사 채널 |

### 2.3 DI 생성 (DiGenerationService)

- **공식**: `Base64URL(HMAC-SHA256(SHA-256(CI) + ":" + agencyCode, QIM_DI_SECRET))`
- **결정론**: 동일 (CI, agencyCode) → 동일 DI 영구 보장
- **저장 금지**: DI는 절대 DB에 저장하지 않음 (요청 시 계산해서 응답만)
- **`QIM_DI_SECRET` 손상 시**: 모든 DI가 변경됨 → 운영 사고. 분기당 1회 회전 권장하나 회전 시 BE 전체 영향평가 선행

### 2.4 PII 마스킹

- **노출 컬럼**: `qim_user.primary_name` 은 이미 마스킹된 값을 저장 (예: `홍*동`)
- **API 응답**: 원본 PII가 응답에 들어가는 모든 필드는 `PiiMaskingService` 통과 필수
- **운영자 조회**: 어드민 콘솔이라도 마스킹된 값이 기본. 평문 조회는 별도 2인 승인 API (`/api/v1/internal/users/{id}/unmask`, 미구현)

---

## 3. API 표면 — Public Contract

### 3.1 API 표면의 두 등급

| 등급 | URI 패턴 | 호출자 | 인증 | 비고 |
|------|---------|------|------|-----|
| **PUBLIC** | `/api/v1/users/{qimUserId}` | IdO (UserStatusCache 갱신용) | Service Mesh Token (TBD) | 상태 조회만. 다른 PII 노출 ❌ |
| **INTERNAL** | `/api/v1/internal/**` | **IdO 만** | Service Mesh Token (TBD) + 네트워크 ACL | 등록/조회/탈퇴/전환/동의/보호자/기업/SP 수신 |

### 3.2 호출 허용 매트릭스

| 호출자 | INTERNAL API | PUBLIC API | 직접 DB | Kafka 토픽 발행 | Kafka 토픽 구독 |
|------|------------|-----------|--------|---------------|--------------|
| **IdO** | ✅ 전체 | ✅ | ❌ | ❌ | ✅ `qim.user.events`, `qim.user.snapshot` |
| **Q-Sign** | ❌ | ❌ | ❌ | ❌ | ✅ `qim.user.events` (탈퇴 → 잠금) |
| **agency-stub / 기관** | ❌ | ❌ | ❌ | ❌ | ❌ |
| **onepass-fe** | ❌ | ❌ | ❌ | ❌ | ❌ |
| **onepass-support** | ❌ | ❌ | ❌ | ❌ | ❌ (IdO 가 변환해서 노출) |
| **onepass-agent** | ❌ | ❌ | ❌ | ❌ | ❌ |
| **outbox-relay-batch** | ❌ | ❌ | ⚠️ Q-IM Outbox 테이블 read-only (별도 합의) | ❌ | ❌ |

> **모든 ❌는 코드/네트워크 양쪽에서 차단**해야 한다. 현재 enforcement gap → §6 참조.

### 3.3 Q-IM 이 외부 토픽을 소비하는 유일한 예외

명세서 §3.4 와 V5 SQL 헤더에 의하면 Q-IM 은 **발행자**다. 그러나 코드 검사 결과 다음 1건의 예외가 식별되어 있다 (확장 시 본 문서 갱신 필수):

| 토픽 | 컨슈머 | 사유 |
|------|------|-----|
| (없음 — 2026-05-26 기준 q-im 패키지에서 `@KafkaListener` 부재 확인) | — | Q-IM은 순수 발행자 |

**향후 추가 금지** — Q-IM이 다른 모듈 토픽을 소비하기 시작하면 SoR 경계가 흐려진다. 추가 필요시 ADR 필수.

### 3.4 Q-IM이 외부 시스템을 **직접 호출하는 것 — 회색지대** ⚠️

코드 검사 결과 다음이 발견되었다:

```
q-im/src/main/java/kr/go/smes/qim/conversion/AgencyMemberLookupServiceImpl.java
  → agency-stub(`/api/agency/member/lookup`) RestTemplate 직접 호출
  → X-Qim-Internal-Key 헤더 인증
```

**원칙**:
- 명세서 §3.4 의 SoR 정의(`외부에서는 IdO를 통해서만 접근`)는 *수신* 방향. *송신* 방향은 명시 없음.
- 그러나 **§3.5 IdO 책임**의 `Q-IM SP 수신`(`QimSpReceiverController`)이 존재한다는 것은 *기관과의 송수신은 IdO 가 담당*한다는 원래 의도였음을 시사.

**결정 (당분간)**:
- 전환 세션의 `fetch-candidates` 단계는 Q-IM의 도메인 로직(여러 기관 병렬 lookup 후보 수집)이므로 **Q-IM이 직접 호출하는 것을 한시 허용**.
- 단, 다음 가드레일 추가 (별도 PR):
  - [ ] `qim.agency.lookup-timeout-ms` / `qim.agency.total-lookup-timeout-ms` 운영값 명문화
  - [ ] 호출 시 Circuit Breaker (Resilience4j) 적용
  - [ ] `X-Qim-Internal-Key` 회전 절차 문서화
  - [ ] 향후 IdO 의 agency 호출 stack 으로 통합하는 마이그레이션 ADR

---

## 4. 이벤트 책임 — Event Ownership

### 4.1 Q-IM이 발행하는 토픽 (4종)

| 토픽 | 종류 | 키 | 페이로드 | At-Least-Once 보장 |
|------|------|----|-------|---------------|
| `qim.user.events` | Compacted | `qimUserId` | USER_REGISTERED / USER_UPDATED / USER_WITHDRAWN(tombstone) | ✅ Outbox |
| `qim.user.events.dlq` | DLQ | — | 발행 실패 원본 | retry 7d 후 운영자 처리 |
| `qim.user.snapshot` | Compacted | `qimUserId` | 현재 상태 스냅샷 (10 이벤트마다) | GAP-QIM-05 (v1.9.2) |
| `qim.sp.member.events.dlt` | DLT | — | (실제로는 IdO 가 발행 — Q-IM은 컨슈머도 아님). **이 줄은 카탈로그 오류 추정** — `06-kafka-event-catalog.md` §1 표에서 Producer 가 "IdO QimSpReceiverController" 로 정정됨 |

### 4.2 Q-IM이 책임지지 않는 이벤트

- `qsign.auth.events` — Q-Sign 발행. Q-IM은 IdO 를 통해서만 결과를 받는다.
- `ido.handoff.events` — IdO 발행. Q-IM은 모름.
- `platform.audit.log` — IdO `AuditLogPublisher`가 발행. Q-IM은 자기 도메인 이벤트만 발행.
- `qim.sp.member.events` — **이름은 qim. 이지만 발행자는 IdO** (`QimSpReceiverController`). Q-IM의 책임이 아니다.

### 4.3 Outbox 운영 책임

- **At-Least-Once**: `qim.outbox_record` 와 도메인 트랜잭션이 **같은 RDBMS 트랜잭션**.
- **순서**: 같은 `qimUserId` 안에서만 보장 (Compacted Topic key=qimUserId). 다른 사용자 간 순서는 보장 ❌.
- **재시도**: GAP-QIM-04 `markFailed()` + `retry_count` 미구현. 현재는 예외 시 같은 row 재시도 — **상한 없음**. P1 으로 해결 필요.
- **스냅샷**: GAP-QIM-05 (v1.9.2 구현). `snapshot_meta` 로 중복 발행 방지.

### 4.4 컨슈머 측에 강제하는 계약

- **멱등 처리 필수**: 같은 (topic, partition, offset) 재처리 가능해야 함. Q-IM 은 At-Least-Once 만 보장.
- **헤더 표준** (`platform-common.EventEnvelope`):
  - `eventId` (UUID)
  - `eventVersion` (long, monotonic per qimUserId)
  - `occurredAt` (ISO8601 UTC)
  - `correlationId` (요청 추적용)
- **하위 호환**: 스키마 변경 시 **추가만 허용** (필드 삭제/타입 변경 금지). 메이저 변경은 새 토픽 + 듀얼 발행 기간 90일.

---

## 5. DB 책임 — Database Ownership

### 5.1 `qim` 스키마 소유

- **단일 쓰기자**: Q-IM 애플리케이션만 `qim` 스키마에 `INSERT/UPDATE/DELETE` 가능.
- **읽기 허용 외부**: 운영 모니터링/감사 도구만 read-only 계정 (`qim_read`). 다른 마이크로서비스의 직접 SELECT 금지.
- **Flyway 마이그레이션**: V1~V7 모두 Q-IM 리포지토리 내 `src/main/resources/db/migration/` 만 권한 있음. 다른 모듈이 `qim` 스키마에 Flyway 마이그레이션 추가하는 것 금지.

### 5.2 마이그레이션 정책

- **불가역 마이그레이션 금지**: V_n 은 항상 다음 형태 — `ADD COLUMN ... NULL` → 코드 배포 → 데이터 백필 → `ALTER ... NOT NULL` (3단계 배포).
- **enum 확장**: 기존 status enum 확장은 OK, 삭제는 ❌.
- **PII 컬럼 추가 시**: 반드시 §2.4 PII 마스킹 통과 후 저장.
- **소급 적용 금지**: 운영 DB에 손 마이그레이션 (SQL 직접 실행) 절대 금지 — 모든 변경은 Flyway 로.

### 5.3 운영자가 Q-IM 에게 요구하면 **안 되는** 것

- "회원 데이터 임시로 수정 가능?" → ❌ (정합성 깨짐, audit log 누락)
- "이 사용자 CI 평문 좀 보여줘" → ❌ (§2.1)
- "탈퇴된 사용자 복원 가능?" → ❌ (`WITHDRAWN` 은 종결 상태, 신규 가입으로 새 qimUserId 발급)
- "qim 스키마에 컬럼 하나만 추가해줘" → ⚠️ (영향 평가 + Flyway PR + 3단계 배포 의무)
- "다른 모듈이 qim_user 테이블 직접 SELECT 하면 안 될까?" → ❌ (§5.1)

### 5.4 운영자가 Q-IM 에게 요구해도 **되는** 것

- 회원 상태 변경 요청 → ✅ `PATCH /api/v1/internal/users/{id}/status` + 감사 사유
- 탈퇴 예약 → ✅ `POST /api/v1/internal/users/{id}/withdrawal`
- DI 재발급 요청 → ✅ `GET /api/v1/internal/users/{id}/di?agencyCode=`
- 동의 이력 조회 → ✅ `GET /api/v1/internal/users/{id}/consents`
- 스냅샷 재발행 요청 (incident 복구) → ⚠️ 운영자 수동 트리거 API 미존재. P2 후보.

---

## 6. 보안 경계 — 현재 갭과 시정 계획 🔴

### 6.1 식별된 갭

코드 검사 결과 (2026-05-26):

| ID | 갭 | 위험 | 현재 상태 |
|----|----|------|--------|
| SEC-QIM-01 | Spring Security/HttpSecurity 설정 **없음** | 🔴 HIGH | 네트워크 ACL 에만 의존. Pod 간 trust 만으로 INTERNAL API 호출 가능 |
| SEC-QIM-02 | 호출자 식별 헤더 표준 부재 | 🔴 HIGH | IdO 가 보낸 요청인지 확인하는 코드 없음 |
| SEC-QIM-03 | CORS 설정 부재 | 🟡 MED | 현재는 FE 직접 호출 0건이라 무해. FE 접근 시도 차단 명시 필요 |
| SEC-QIM-04 | 감사 로그 통합 부재 | 🟡 MED | Q-IM 내부 로그는 있으나 IdO `platform.audit.log` 와 미연계 |
| SEC-QIM-05 | `X-Qim-Internal-Key` 평문 설정 | 🟡 MED | `application.yml: qim-internal-key-dev-001` 디폴트값 — 운영 분리 필요 |
| SEC-QIM-06 | INTERNAL API 의 인증 토큰 표준 미정 | 🔴 HIGH | mTLS / JWT / API-Key 중 결정 안 됨 |

### 6.2 시정 계획 (별도 PR 시리즈)

```
[ ] PR-QIM-SEC-1: SecurityFilterChain 추가
                  - /api/v1/internal/** 은 X-Caller=ido 헤더 + Service Mesh Token 필수
                  - /api/v1/users/** 은 별도 정책
                  - actuator/health 는 public

[ ] PR-QIM-SEC-2: 호출자 인증 표준 도입
                  - Spring Cloud 환경이면 mTLS
                  - Service Mesh (Istio/Linkerd) 환경이면 SPIFFE ID 검증
                  - 그 외는 IdO ↔ Q-IM JWT (platform-common 의 EventEnvelope JWS 와 동일 키)

[ ] PR-QIM-SEC-3: CORS 정책 명시 (전체 차단)
                  - allowedOrigins = []
                  - 위반 요청은 403 + 감사 로그

[ ] PR-QIM-SEC-4: 감사 이벤트를 platform.audit.log 로 발행
                  - 회원 상태 변경/탈퇴/CI 복호화/관리자 조회 시 audit 이벤트

[ ] PR-QIM-SEC-5: X-Qim-Internal-Key 운영 분리
                  - 디폴트 제거, Vault/KMS 강제
                  - 회전 절차 RUNBOOK
```

---

## 6.5 절대 금지선 — Q-IM 관리자 페이지·UI·콘솔 금지 🚫

> **본 섹션은 협상 불가(non-negotiable) 정책이다.**
> 위반 시도가 발견되면 어떤 사유에서든 PR 즉시 reject 한다.
> 본 섹션의 결론을 뒤집으려면 **헌장 폐기 + 신규 헌장 + 전 모듈 오너 합의**가 필요하다.

### 6.5.1 금지의 한 줄

**Q-IM 은 영구히, 어떠한 사람-대상(UI) 표면도 갖지 않는다.**

여기서 "사람-대상 UI" 란:
- 관리자 페이지 / 어드민 콘솔 / 백오피스
- 운영 대시보드 / 모니터링 화면 (헬스/메트릭 *수집 엔드포인트*는 OK, *렌더링 화면*은 ❌)
- 디버그 페이지 / 개발자 도구 / DB 조회 화면
- "한 번만 임시로" 만드는 내부 페이지
- Swagger UI / Actuator HTML view / H2 Console
- 운영자가 "버튼" 으로 회원 데이터를 조작할 수 있는 모든 표면

### 6.5.2 왜 절대 금지인가 — 7가지 논증

| # | 논증 | 핵심 한 줄 |
|---|------|---------|
| **1** | SoR 원칙 파괴 | UI ↔ DB 직접 수정 경로 발생 → Outbox 우회 → 정합성 붕괴 |
| **2** | 암호 자산 노출 폭탄 | CI 평문 복호화 능력 + DI 생성 + 전 회원 PII 가 화면에 표시될 가능성 |
| **3** | 감사 추적성 분기 | IdO `platform.audit.log` 와 Q-IM 자체 UI 로그 두 갈래 → 사고 조사 시 양쪽 어긋남 |
| **4** | 위협 모델 전면 변경 | CORS·세션·CSRF·XSS — 현재 모두 무관한데 UI 가 생기면 4개 다시 작업 |
| **5** | 단순성 파괴 | "IdO 만 ✅, 나머지 ❌" 매트릭스(§3.2) 가 깨지고 호출자 종류·인증 수단 폭증 |
| **6** | 인접 모듈 책임 경계 붕괴 | §7.4 가 무너지고 onepass-support → IdO 어드민 API 경로가 우회됨 |
| **7** | 운영 비용 폭증 | 새 코드베이스·CI·배포·RBAC·운영팀 모두 추가 — Q-IM이 *"식별의 진실"* 한 가지에만 집중 못 함 |

### 6.5.3 자주 시도되는 우회로와 차단

| 시도 | 그럴듯한 이유 | 차단 근거 |
|------|----------|---------|
| "운영자가 빠르게 회원 조회해야 해서 페이지 하나만" | 운영 편의 | onepass-support 가 IdO 어드민 API 호출하는 화면을 만들면 됨. §7.4 |
| "Swagger UI 만 띄우자, 개발자만 봄" | 개발 편의 | OpenAPI **스펙(.yaml/.json)** 은 OK, **렌더링 UI** 는 ❌. 스펙은 IdO/외부 도구로 import |
| "Actuator HTML 뷰만 켜자" | 운영 진단 | `/actuator/health`, `/actuator/prometheus` JSON 만 허용. `management.endpoints.web.exposure` 에서 UI 관련 엔드포인트 명시 차단 |
| "H2 Console (로컬 개발만)" | 디버그 | 운영 build profile 에서 자동 제외 강제. 로컬에서도 default OFF |
| "Spring Boot Admin 등록" | 모니터링 통합 | Q-IM 은 등록되지 않는다. 메트릭은 Prometheus scrape → 외부 Grafana |
| "에러 페이지 커스터마이즈" | UX | JSON 에러 응답만. HTML 에러 페이지 (`error.html`, Whitelabel) 비활성 |
| "GraphQL Playground / GraphiQL" | API 탐색 | GraphQL 자체를 도입 안 함. REST 만. |
| "비상시 한 번만 임시 UI" | 사고 대응 | 헌장 §6.5.4 비상 대응 매트릭스 참조 — UI 없이도 해결 가능 |

### 6.5.4 인정되는 비-UI 운영 표면 (정의된 것만 허용)

GUI 가 없으면 운영자가 손도 못 댄다는 우려를 차단하기 위해, **다음 4가지 비-UI 형태만** 운영 인터페이스로 허용한다:

| 도구 | 허용 형태 | 금지 형태 |
|------|---------|---------|
| **헬스 체크** | `GET /actuator/health` (JSON) | `/actuator/*` 의 HTML view, Admin UI |
| **메트릭 수집** | `GET /actuator/prometheus` (수집 endpoint) → 외부 Grafana | Q-IM 내장 차트·대시보드 |
| **비상 운영 명령** | `kubectl exec` + 사전 정의된 read-only 스크립트 (`scripts/ops/*.sh`) | `psql/mariadb` GUI 클라이언트 운영 DB 접속, phpMyAdmin |
| **민감 조작 API** | `POST /api/v1/internal/admin/*` + 2인 승인 헤더 (`X-Approver-A`, `X-Approver-B`) + audit 강제 | 같은 기능의 "버튼" 이 있는 화면 |

**핵심 원칙**:
> **"사람이 클릭할 수 있는 표면을 Q-IM 위에 만들지 않는다."**
>
> 모든 운영 행위는 (a) **명시적 API 호출** 또는 (b) **승인된 스크립트 실행** 으로 한다.
> 행위의 흔적은 audit log 와 git history 양쪽에 남아야 한다.

### 6.5.5 enforcement 체크리스트

본 §6.5 가 코드 수준에서 위반되지 않도록 다음을 강제한다 (SEC-QIM-07 신규 백로그):

```yaml
# application.yml — 운영 build profile 기본값으로 강제
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus      # info 도 제외 권장
        exclude: heapdump, env, beans, mappings, configprops
  endpoint:
    health:
      show-details: never                 # PII 누설 방지

spring:
  h2:
    console:
      enabled: false                      # 어떤 profile 에서도 false 가 기본
  mvc:
    pathmatch:
      matching-strategy: ant_path_matcher

springdoc:                                # Swagger 관련 라이브러리 자체를 의존성에서 제외
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

추가 enforcement:
- `q-im/build.gradle.kts` 에 `springdoc-openapi`, `spring-boot-admin-*`, `h2`, `spring-boot-starter-thymeleaf`, `spring-boot-starter-freemarker`, `spring-boot-starter-mustache` 의존성 **추가 금지** — CI 에서 dependency-check 로 검사
- `q-im/src/main/resources/static/` 디렉토리 생성 금지 (CI 검사)
- `q-im/src/main/resources/templates/` 디렉토리 생성 금지 (CI 검사)
- `@Controller`(View 반환용) 어노테이션 금지 — `@RestController` 만 허용 (정적 코드 분석 또는 ArchUnit 테스트로 강제)

### 6.5.6 본 정책의 폐기 절차

본 §6.5 는 **헌장의 다른 어떤 조항보다 강한 효력**을 가진다. 이를 폐기 또는 약화하려면:

1. 신규 RFC/ADR 작성 — *"왜 §6.5 를 폐기해야 하는가"* 의 7가지 논증(§6.5.2) 각각에 대한 반박
2. 전 모듈 오너(IdO / Q-Sign / onepass-fe / onepass-support / onepass-agent / 보안팀) 의 **만장일치 합의** (다수결 불가)
3. 폐기 후 발생할 위협 모델 변화 분석 + 신규 시정 PR 목록
4. 폐기 발효일로부터 **최소 30일 cooldown** (그 동안 추가 의견 수렴)
5. 헌장 신규 버전 발행 + git tag `qim-charter-v2`

**단순한 PR 로는 §6.5 를 변경할 수 없다.**

---

## 7. 인접 모듈과의 계약 — Contracts

### 7.1 IdO ↔ Q-IM

| 방향 | 인터페이스 | Q-IM 의 책임 | IdO 의 책임 |
|------|---------|-----------|-----------|
| IdO → Q-IM | `POST /api/v1/internal/users/register` | 신규 qimUserId 발급, CI 암호화 저장, Outbox 발행 | 호출자 인증, retry 정책, ConflictException 처리 |
| IdO → Q-IM | `GET /api/v1/internal/users/{id}` | 마스킹 응답 | 캐싱 (UserStatusCache) |
| IdO → Q-IM | `POST /api/v1/internal/users/find-by-ci` | ci_hash 인덱스 조회 | CI 평문 전달 시 자체 보호 |
| IdO → Q-IM | `PATCH /api/v1/internal/users/{id}/status` | 상태 전이 검증 + 이벤트 발행 | 변경 사유 (감사용) 포함 |
| IdO → Q-IM (수신) | `qim.user.events` 구독 | 이벤트 정상 발행 | 멱등 처리, DLT 모니터링 |

### 7.2 Q-Sign ↔ Q-IM

| 방향 | 인터페이스 | 비고 |
|------|---------|-----|
| Q-IM → Q-Sign (간접) | `qim.user.events` 발행 | Q-Sign 의 `QimUserEventConsumer` 가 잠금 처리 |
| Q-Sign → Q-IM | **직접 호출 없음** | 모든 요청은 IdO 경유 |

### 7.3 onepass-fe ↔ Q-IM

**현재 상태 (2026-05-26 PR #199 후 확인)**:
- onepass-fe 의 `/api/v1/internal/**` 직접 호출: **0건** ✅
- onepass-fe 는 모두 IdO BFF (`/api/v1/ext/**`, `/api/v1/handoff/**`) 만 호출

**유지해야 할 정책**:
- onepass-fe 에서 `q-im` / `/api/v1/internal/` 문자열이 등장하면 그 자체로 코드 리뷰 reject
- PR #198 §1 인증 모델은 FE → IdO 까지만 다룸. Q-IM 은 IdO 의 backend, FE 의 backend 가 아니다.

### 7.4 onepass-support ↔ Q-IM

**현재 상태**:
- onepass-support → Q-IM 직접 호출: **0건** ✅
- onepass-support 는 모두 IdO 어드민 API 호출

**유지해야 할 정책**:
- 어드민 화면에서 회원 상세 조회/상태 변경/탈퇴 처리 시 IdO 어드민 API 만 사용
- IdO 가 Q-IM API 를 어떻게 호출하든 onepass-support 는 알 필요 없음
- 만약 onepass-support 가 Q-IM 의 새 기능을 필요로 한다면 → **IdO 에 어드민 API 를 추가 요청**하는 것이 정도. Q-IM 직접 호출 우회 ❌

### 7.5 onepass-agent ↔ Q-IM

- **직접 호출 0건** ✅
- onepass-agent 는 기관 측 서비스. IdO 의 외부 API (`/api/v1/handoff`, `/api/v1/agency`) 만 호출.
- Q-IM 은 기관의 존재를 모르며, 기관 인증/인가에 관여하지 않는다.

### 7.6 agency-stub / 실제 기관 ↔ Q-IM

- **수신 방향**: 기관 → IdO `QimSpReceiverController` → Kafka → Q-IM 컨슈머 (예정, 현재는 IdO 가 발행 후 자체 처리 패턴). Q-IM 이 직접 기관의 요청을 받지 않음.
- **송신 방향 (회색지대)**: §3.4 — 전환 세션의 후보 lookup 시 Q-IM 이 기관을 직접 호출. 가드레일 §3.4 참조.

---

## 8. "다른 팀이 Q-IM에게 자꾸 요구하는데 거절해야 할 것" 체크리스트

> 실무에서 자주 들어오는 요청과 표준 답변. 이 표를 본 문서 링크와 함께 회신할 것.

| 요청 | 답변 | 대안 |
|------|-----|------|
| "qim 스키마에 비밀번호 컬럼 하나만…" | ❌ §1.2 | Keycloak 에 저장. Q-IM 은 비밀번호 스키마 영구 미보유 |
| "이 사용자 CI 평문 좀 보여줘" | ❌ §2.1 | 마스킹된 PII 또는 ci_hash 만. 평문은 2인 승인 unmask API (미구현) |
| "Q-IM 이 SMS/이메일 보내줘" | ❌ 책임 영역 외 | IdO 의 알림 서비스 또는 별도 노티 서비스 |
| "FE 가 빨라서 Q-IM 직접 호출하면 안 될까?" | ❌ §3.2 / §7.3 | IdO BFF 의 캐시(UserStatusCache) 최적화 |
| "탈퇴된 사용자 복원" | ❌ §5.3 | 신규 가입으로 새 qimUserId 발급 (CI 동일이면 매핑 충돌 — 정책 결정 필요) |
| "QIM_DI_SECRET 좀 알려줘" | ❌ §2.3 | 응답으로 받는 DI 값을 그대로 사용. secret 은 운영자 외 접근 불가 |
| "Q-IM 토픽에 우리 모듈 메시지 좀 끼워서…" | ❌ §4.2 | 자기 모듈 토픽 생성. `qim.*` 네임스페이스는 Q-IM 전용 |
| "운영 DB에 직접 UPDATE 한 번만…" | ❌ §5.2 | Flyway PR + 3단계 배포 |
| "감사 로그를 platform.audit.log 가 아니라 따로 받고 싶어" | ⚠️ 거절은 아니지만 IdO 와 협의 | IdO 의 감사 토픽 구독, 필터링은 컨슈머 측 책임 |
| "기관 X 호출 좀 추가해줘 (직접 Q-IM 에서)" | ⚠️ §3.4 가드레일 통과 시만 | 가능하면 IdO 에 추가. Q-IM 의 agency 직접 호출은 전환 세션 한정 |
| "onepass-support 에 Q-IM 데이터 노출 필요" | ❌ §7.4 | IdO 에 어드민 API 추가 요청 |
| "Q-IM 에 UI 한 페이지만…" | 🚫 **절대 금지 §6.5** (헌장 개정 없이는 영구 불가) | onepass-support 가 IdO 어드민 API 호출하는 화면 |
| "Q-IM 관리자 콘솔·어드민 대시보드 하나만 열어줘" | 🚫 **절대 금지 §6.5** | onepass-support / 별도 운영 모듈에서 IdO 경유로 구현 |
| "운영 편의용 임시 화면 — 딱 한 번만, 곧 지울게" | 🚫 **절대 금지 §6.5.3** (한 번 열리면 영구화됨) | `kubectl exec` + `scripts/ops/*.sh` (2인 승인) |
| "Swagger UI / H2 Console / Spring Boot Admin 만이라도 켜두자" | 🚫 **절대 금지 §6.5.3** (bypass 시도로 간주) | OpenAPI **JSON** (`/v3/api-docs`)을 IdO·문서 사이트가 소비 |
| "JWT 검증 좀 Q-IM 이 해주면 안 돼?" | ❌ Q-Sign / IdO 책임 | Q-IM 은 자기 INTERNAL API 호출자 인증만 (§6.2) |
| "이번 한 번만 qim DB 에서 데이터 추출해 줘" | ⚠️ 감사 로그 남기고 진행. 정기화 시도 ❌ | 정기 통계는 별도 read-only replica + 분석 시스템 |

> **§6.5 관련 행 처리 원칙**: 🚫 표시 항목은 **거절 사유 회신에 본 문서 링크 + §6.5 앵커를 반드시 포함**할 것. 헌장 개정(§6.5.6) 없이는 어떠한 우회·예외·"한 번만"도 받지 않는다.

---

## 9. 변경 절차 — 본 헌장 갱신

본 문서를 갱신해야 하는 트리거:

1. **새 INTERNAL API 추가** → §3 표 갱신 + PR 리뷰어에 Q-IM 오너 지정
2. **새 Kafka 토픽 발행** → §4.1 갱신 + `06-kafka-event-catalog.md` 동시 갱신
3. **새 스키마 마이그레이션 (V8+)** → §5.1 추적, `05-database-schema.md` 동시 갱신
4. **외부 시스템 직접 호출 추가** → §3.4 가드레일 통과 + ADR 작성
5. **보안 갭 시정 (PR-QIM-SEC-*)** → §6.1 표에서 상태 갱신
6. **§6.5 절대 금지선 변경 (UI/관리자 페이지 도입)** → §6.5.6 의 RFC + 전 오너 만장일치 + 30일 쿨다운 + `qim-charter-v2` 태그 절차를 따라야 함 (일반 PR 로는 변경 불가)

---

## 10. 부록 — 본 문서 작성 시 검사한 사실들

| 검사 | 결과 |
|------|-----|
| `/api/v1/internal/**` 엔드포인트 수 | 9개 컨트롤러, 23개 매핑 |
| `@RestController` 외부 노출 (PUBLIC `/api/v1/users`) | 1개 (`QimStatusController`) |
| Q-IM 패키지 내 `@KafkaListener` | **0건** (= 외부 토픽 소비 없음) |
| Q-IM Spring Security 설정 | **0건** (= SEC-QIM-01 갭) |
| Q-IM → 외부 직접 HTTP 호출 | 1건 (`AgencyMemberLookupServiceImpl`, §3.4) |
| onepass-fe → `/api/v1/internal/` 호출 | **0건** ✅ |
| onepass-support → `q-im` / `QimClient` | **0건** ✅ |
| onepass-agent → `q-im` / `QimClient` | **0건** ✅ |
| Q-IM Flyway 마이그레이션 | V1~V7 (qim 스키마 단독 소유) |
| Q-IM 발행 Kafka 토픽 | `qim.user.events`, `qim.user.events.dlq`, `qim.user.snapshot` (+`qim.sp.member.events` 는 IdO 발행) |

---

*다음 단계*:
1. 본 헌장을 인접 모듈 오너(IdO / Q-Sign / onepass-fe / onepass-support / onepass-agent) 에게 회람 후 합의 서명
2. §6 SEC 갭 시정 PR 5건 백로그 등록
3. §3.4 회색지대 (Q-IM → agency 직접 호출) 가드레일 PR 1건
4. CODEOWNERS 에 `q-im/**` 와 `docs/internal/spec/03c-*.md` 에 Q-IM 오너 지정
