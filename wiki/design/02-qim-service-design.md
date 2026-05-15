# Q-IM (Q Identity Manager) 서비스 상세 설계서

| 항목 | 내용 |
|------|------|
| **서비스명** | Q-IM (Q Identity Manager) |
| **포트** | 8082 |
| **역할** | 회원 정보 CRUD, CI 암호화, 전환 세션, 동의 관리, Outbox 발행 |
| **기술 스택** | Spring Boot 3.2, JDK 21, PostgreSQL, Redis, Kafka |
| **최종 갱신** | 2026-05-15 (v0.8.8) |

---

## 1. 개요

Q-IM은 **회원 정보의 단일 진실 원천(Single Source of Truth)**이다. 개인·기업 회원 정보를 관리하고, CI(연계정보) 기반 본인 확인, 기관 계정 전환, 동의 관리, 탈퇴 처리를 담당한다. 회원 이벤트는 Transactional Outbox를 통해 Kafka로 발행된다.

```
FE → IdO(:8083) → [Q-IM :8082]
                      │
                    qim.outbox
                      │
                    OutboxRelay → Kafka qim.user.events → IdO 소비
```

---

## 2. 패키지 구조

```
kr.go.smes.qim
├── api/                    # REST API 레이어
│   ├── ext/                # External API (FE 직접 호출 대상)
│   ├── internal/           # Internal API (IdO 전용)
│   └── dto/
├── user/                   # 사용자 등록 서비스
│   ├── UserRegistrationService.java
│   └── UserRegistrationServiceImpl.java
├── conversion/             # 기관 계정 전환 세션
│   ├── ConversionSessionService.java
│   ├── AgencyMemberLookupService.java
│   └── ConversionSessionServiceImpl.java
├── consent/                # 동의 관리
│   ├── ConsentService.java
│   └── ConsentServiceImpl.java
├── withdrawal/             # 탈퇴 처리
│   ├── WithdrawalService.java
│   └── WithdrawalServiceImpl.java
├── guardian/               # 미성년자 법정대리인 동의
│   └── GuardianConsentService.java
├── crypto/                 # CI 암호화
│   ├── CiCryptoService.java        ← CI AES-256 암호화·복호화
│   └── PiiMaskingService.java      ← PII 마스킹
├── outbox/                 # Transactional Outbox
│   ├── OutboxService.java
│   ├── OutboxServiceImpl.java
│   └── SnapshotService.java
├── domain/                 # 도메인 모델
│   ├── QimUser.java
│   ├── UserProfile.java
│   ├── AuthMeanMapping.java
│   └── MinorGuardianPolicy.java
├── identity/               # DI(대체식별자) 생성
│   └── DiGenerationService.java
└── infrastructure/         # JPA 엔티티·레포지토리
    ├── UserRepository.java
    └── jpa/entity/
```

---

## 3. 핵심 컴포넌트 상세

### 3.1 UserRegistrationService — 회원 등록

**등록 흐름**:
```
API 요청 → UserRegistrationServiceImpl.register()
  → CI 중복 체크 (CiCryptoService)
  → QimUser 도메인 생성
  → DB INSERT (qim_user, user_profile, auth_mean_mapping)
  → OutboxService.publishEvent(PERSONAL_MEMBER_REGISTERED or BIZ_MEMBER_REGISTERED)
     → qim.outbox INSERT (동일 트랜잭션)
  → 커밋
  → [비동기] OutboxRelay → Kafka qim.user.events
```

**resolveRegisterEventType 순수 함수 (2×2 매트릭스)**:

| isTransfer | isCorporate | 이벤트 타입 |
|-----------|-------------|------------|
| false | false | `PERSONAL_MEMBER_REGISTERED` |
| true | false | `PERSONAL_MEMBER_CONVERTED` |
| false | true | `BIZ_MEMBER_REGISTERED` |
| true | true | `BIZ_MEMBER_CONVERTED` |

### 3.2 CI 암호화 (CiCryptoService)

CI(연계정보)는 개인을 고유 식별하는 88자 문자열로, 주민등록번호에 준하는 민감 정보다.

```java
// CiCryptoServiceImpl.java
// CI 저장: AES-256-GCM 암호화 후 DB 저장 (평문 미저장)
// CI 조회: 복호화 후 사용 (사용 후 즉시 변수 제거 권장)
// FE 전송: IdO에서 AES-GCM 공유 키로 암호화된 CI만 수신 (Q3=B)
```

### 3.3 ConversionSessionService — 기관 계정 전환

기존 기관 계정(레거시)을 OnePass로 전환하는 세션 관리.

```
전환 흐름:
1. FE → Q-IM: 기관 계정 조회 요청
2. Q-IM: AgencyMemberLookupService → Agency-Stub 조회
3. CandidateMember 목록 반환
4. 사용자가 연결할 기관 계정 선택
5. ConversionSession 생성 (Redis, TTL 30분)
6. 전환 확정 → UserRegistrationService.register(isTransfer=true)
7. Outbox → PERSONAL_MEMBER_CONVERTED or BIZ_MEMBER_CONVERTED
```

### 3.4 Outbox 발행 (OutboxServiceImpl)

```java
@Transactional
public void publishEvent(String qimUserId, String eventType, String payload) {
    OutboxRecord record = OutboxRecord.builder()
        .id(UUID.randomUUID().toString())
        .aggregateId(qimUserId)
        .eventType(eventType)        // QIM-OUTBOX-SPEC-001 5종
        .payload(payload)
        .status("PENDING")
        .build();
    outboxRepository.insert(record); // 같은 트랜잭션
}
// → 커밋 후 OutboxRelay가 폴링 → Kafka qim.user.events 발행
```

### 3.5 WithdrawalService — 탈퇴 처리

```
탈퇴 흐름:
1. 탈퇴 요청 (soft delete)
2. qim_user.status = 'WITHDRAWN'
3. OutboxService.publishEvent(MEMBER_WITHDRAWN)
4. Kafka → IdO가 소비 → 기관별 탈퇴 처리 (provisioning_outbox)
5. 개인정보 보존 기간 후 PersonalDataRetentionScheduler가 물리 삭제
```

---

## 4. DB 스키마 (Q-IM — 주요 테이블)

| 테이블 | 용도 |
|--------|------|
| `qim.qim_user` | 회원 기본 정보 (UUID v7, CI hash) |
| `qim.user_profile` | 회원 프로필 (이름, 전화, 생년월일 — 암호화) |
| `qim.auth_mean_mapping` | 인증 수단 매핑 (Keycloak sub, NICE CI 등) |
| `qim.biz_member` | 법인 회원 추가 정보 (사업자번호) |
| `qim.consent_version` | 동의 버전 정의 |
| `qim.consent_record` | 개인별 동의 이력 |
| `qim.conversion_session` | 전환 세션 (Redis 미러) |
| `qim.outbox` | Kafka 발행 Outbox (QIM-OUTBOX-SPEC-001) |
| `qim.snapshot_meta` | Outbox 스냅샷 메타 |
| `qim.processed_event` | 멱등성 소비 기록 |

### Flyway 버전 현황 (Q-IM)

| 버전 | 내용 |
|------|------|
| V1 | 기본 스키마 (qim_user, user_profile, outbox 등) |
| V2 | Idempotent Consumer (processed_event) |
| V3 | CI 암호화 컬럼 + 상태 이력 |
| V4 | 소셜 SSO 수정 |
| V5 | 탈퇴·동의·전환 테이블 추가 |
| V6 | 미성년자·법정대리인·법인 회원 |

---

## 5. API 명세 (주요)

### External API (FE 호출 — ExtProxyController 경유)

| HTTP | 경로 | 설명 |
|------|------|------|
| POST | `/api/ext/register/individual` | 개인 회원 등록 |
| POST | `/api/ext/register/enterprise` | 기업 회원 등록 |
| GET  | `/api/ext/member/profile` | 회원 프로필 조회 |
| POST | `/api/ext/conversion/start` | 전환 세션 시작 |
| GET  | `/api/ext/conversion/candidates` | 전환 후보 기관 계정 조회 |

### Internal API (IdO 전용)

| HTTP | 경로 | 설명 |
|------|------|------|
| GET  | `/api/internal/member/{ci-hash}` | CI Hash로 회원 조회 |
| POST | `/api/internal/member/register` | 회원 등록 (IdO 경유) |
| POST | `/api/internal/withdrawal` | 탈퇴 처리 |

---

## 6. 보안 / PII 보호

| 항목 | 처리 방식 |
|------|----------|
| CI | AES-256-GCM 암호화 저장, 평문 미노출 |
| 이름·전화번호 | 암호화 저장 + 마스킹 조회 |
| 사업자번호 | 평문 저장 (공개 정보) |
| Outbox 페이로드 | identity_hash(SHA-256) 사용, CI 미포함 |
| 로그 | PiiMaskingService로 마스킹 처리 |
