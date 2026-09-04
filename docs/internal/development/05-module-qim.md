# 05. Q-IM 모듈 구현 상태 (v1.9.0)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09  
> **모듈 경로**: `idem-registry/`  
> **포트**: 8082  
> **DB**: MariaDB (`qim` 스키마, V1~V3)

---

## 1. 모듈 개요

Q-IM(Q-Identity Management)은 **식별 SoR(Source of Record)**으로, 통합 회원 식별자 및 회원 생명주기를 관리한다.

### 1.1 패키지 구조

```
idem-registry/src/main/java/kr/go/smes/qim/
├── QImApplication.java
├── api/                           # 외부/내부 API
│   ├── GlobalExceptionHandler.java
│   ├── MemberLookupController.java
│   ├── QimStatusController.java
│   ├── UserController.java
│   └── dto/
├── application/                   # 비즈니스 로직
│   ├── UserService.java
│   └── UserServiceImpl.java
├── config/                        # 설정
│   ├── KafkaConsumerConfig.java
│   ├── KafkaProducerConfig.java
│   ├── KafkaTopicConfig.java
│   └── RedisConfig.java
├── crypto/                        # CI 암호화 ★ v1.8.0 신규
│   ├── CiCryptoService.java
│   └── CiCryptoServiceImpl.java
│   └── PiiMaskingService.java     ★ v1.8.0 신규
├── domain/                        # 도메인 모델
│   ├── AuthMeanMapping.java
│   ├── QimUser.java
│   └── UserProfile.java
├── identity/                      # DI 생성 ★ v1.8.0 신규
│   └── DiGenerationService.java
├── infrastructure/                # JPA 구현체
│   ├── UserRepository.java
│   ├── UserRepositoryImpl.java
│   └── jpa/
│       ├── entity/
│       │   ├── AuthMeanMappingJpaEntity.java
│       │   ├── OutboxJpaEntity.java
│       │   ├── QimUserJpaEntity.java
│       │   └── UserProfileJpaEntity.java
│       └── repository/
│           ├── OutboxJpaRepository.java
│           └── QimUserJpaRepository.java
├── outbox/                        # Transactional Outbox
│   ├── OutboxRecord.java
│   ├── OutboxRepository.java
│   ├── OutboxRepositoryImpl.java
│   ├── OutboxService.java
│   └── OutboxServiceImpl.java
└── user/                          # 사용자 등록 서비스 ★ v1.8.0 신규
    ├── UserRegistrationService.java
    └── UserRegistrationServiceImpl.java
```

---

## 2. 핵심 기능별 구현 상태

### 2.1 회원 도메인 (QimUser, UserProfile, AuthMeanMapping)

**상태**: ✅ 완전 구현

| 도메인 | JPA 엔티티 | 테이블 | 상태 |
|--------|-----------|--------|------|
| QimUser | QimUserJpaEntity | `qim.qim_user` | ✅ |
| UserProfile | UserProfileJpaEntity | `qim.user_profile` | ✅ |
| AuthMeanMapping | AuthMeanMappingJpaEntity | `qim.auth_mean_mapping` | ✅ (구조) |

### 2.2 회원 등록 서비스

**상태**: ✅ 완전 구현 (v1.8.0)

```java
// UserRegistrationService.register()
// POST /api/ext/v1/member/register
// - identifierHash 기반 중복 확인
// - CI AES-256-GCM 암호화 후 저장
// - PII 마스킹 (이름, 전화번호, 이메일)
// - qimUserId(UUID) 생성
// - Outbox → qim.user.events 발행
```

### 2.3 회원 상태 전이

**상태**: ✅ 완전 구현 (기존)

```
ACTIVE → SUSPENDED → WITHDRAWN
```

- `UserServiceImpl.updateStatus()` — 단방향 상태 전이 강제
- 상태 변경 시 `UserEvent.USER_STATUS_CHANGED` 발행
- WITHDRAWN 처리 시 PII 즉시 삭제 (부분 구현)

### 2.4 CI 암호화 (AES-256-GCM)

**상태**: ✅ 완전 구현 (v1.8.0)

```java
// CiCryptoServiceImpl
public String encrypt(String rawCi) {
    // AES-256-GCM, 랜덤 IV
    // 저장 포맷: v1.{base64url(iv)}.{base64url(ciphertext)}
}
public String decrypt(String encryptedCi) {
    // 버전 파싱 후 해당 버전 키 사용
}
```

### 2.5 PII 마스킹

**상태**: ✅ 완전 구현 (v1.8.0)

```java
// PiiMaskingService
// 이름: 홍길동 → 홍*동
// 전화번호: 01012345678 → 010-****-5678
// 이메일: user@example.com → us**@example.com
```

### 2.6 DI(Duplicate Identity) 생성

**상태**: ✅ 완전 구현 (v1.8.0)

```java
// DiGenerationService
// DI = HMAC-SHA256(siteCode:qimUserId:serviceSecret)
// 기관별 독립 생성 (기관 코드를 siteCode로 사용)
// di_map JSON 컬럼: {"AGENCY_CODE": "di_value"}
```

### 2.7 사용자 조회 API

**상태**: ✅ 구현 (일부)

| 엔드포인트 | 상태 |
|-----------|------|
| `GET /api/v1/users/{qimUserId}` | ✅ |
| `GET /api/v1/users/by-hash?identifierHash=` | ✅ |
| `GET /api/v1/users/{qimUserId}/di?agencyCode=` | ✅ |

### 2.8 Transactional Outbox

**상태**: 🟡 부분 구현 (기본 발행은 동작, retry_count 미적용)

```java
// OutboxServiceImpl.relayPendingEvents()
// PENDING → Kafka(qim.user.events) 발행 → PUBLISHED
// ⚠️ 실패 시 markFailed() 미호출, retry_count 증가 미적용 (GAP-QIM-04)
```

### 2.9 V3 마이그레이션 (CI 암호화 버전 관리)

**상태**: ✅ 완전 구현 (v1.8.0)

```sql
-- V3__add_ci_encryption_and_status_history.sql
ALTER TABLE qim.user_profile
    ADD COLUMN ci_key_version VARCHAR(10) DEFAULT 'v1' NOT NULL;
ALTER TABLE qim.user_profile
    ADD COLUMN di_map_updated_at DATETIME(6);
-- user_status_history 테이블 추가
```

---

## 3. Flyway 마이그레이션 현황

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | `V1__create_schema.sql` | qim 스키마 기본 테이블 (qim_user, user_profile, auth_mean_mapping, outbox_record) |
| V2 | `V2__add_idempotent_consumer.sql` | snapshot_meta, processed_event, last_event_version 테이블 |
| V3 | `V3__add_ci_encryption_and_status_history.sql` | ci_key_version, di_map_updated_at 컬럼, user_status_history 테이블 ★ v1.8.0 |

---

## 4. API 엔드포인트

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/api/ext/v1/member/register` | 회원 등록 (IdO→Q-IM) | X-Internal-Api-Key |
| POST | `/api/ext/v1/member/query` | 회원 조회 (IdO→Q-IM) | X-Internal-Api-Key |
| POST | `/api/ext/v1/member/withdraw` | 회원 탈퇴 (IdO→Q-IM) | X-Internal-Api-Key |
| GET | `/api/v1/users/{qimUserId}` | 사용자 상세 조회 | X-Internal-Api-Key |
| GET | `/api/v1/users/by-hash` | 해시 기반 조회 | X-Internal-Api-Key |
| GET | `/api/v1/users/{qimUserId}/di` | DI 조회/생성 | X-Internal-Api-Key |
| GET | `/actuator/health` | 헬스체크 | 없음 |

---

## 5. 잔여 미구현 항목

| ID | 항목 | 우선순위 | 비고 |
|----|------|---------|------|
| GAP-QIM-01 | `needsSync=true` → 실제 Selective Pull API 호출 | P2 | QimEventConsumer 로그만 기록 중 |
| GAP-QIM-03 | `addAuthMeanMapping()` 실제 JPA 저장 구현 | P2 | TODO 주석만 존재 |
| GAP-QIM-04 | Outbox markFailed() + retry_count 증가 | P2 | 실패 시 단순 로그만 |
| GAP-QIM-05 | snapshot_meta 테이블 사용 로직 구현 | P3 | 테이블 있으나 Java 코드 없음 |
| - | 기업회원 전환 (사업자등록번호 기반) | P1 | 미구현 |
| - | 14세 미만 보호자 인증 분기 | P2 | 미구현 |
| - | 회원 탈퇴 4종 전체 구현 | P3 | 기본 탈퇴만 부분 구현 |
| - | 단위 테스트 작성 | P2 | 미작성 |

---

## 6. 환경 변수 목록

```yaml
qim:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
  ci:
    aes-key-v1: ${QIM_CI_AES_KEY_V1}
  di:
    secret: ${QIM_DI_SECRET}
  kafka:
    bootstrap-servers: ${KAFKA_SERVERS:localhost:9092}
```

---

*다음 문서: [06-module-agency-stub.md](06-module-agency-stub.md)*
