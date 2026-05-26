# 03-C. Q-IM 모듈 상세 명세

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **모듈 경로**: `q-im/`  
> **포트**: 8082  
> **DB**: MariaDB 11 (`qim` 스키마, V1~V3)  
> **완성도**: 92%
>
> 📜 **함께 읽기**: [03c-qim-responsibility-charter.md](03c-qim-responsibility-charter.md) — Q-IM 책임 헌장 (DO / DO NOT, 인접 모듈과의 계약). 본 문서가 "어떻게 구현됐는지"를 다룬다면 헌장은 "무엇을 책임지고 무엇을 책임지지 않는지"의 정본이다.

---

## 1. 모듈 역할

Q-IM(Q Identity Manager)은 **식별(Identification) Source of Record**다. 사용자의 통합 식별자(`qimUserId`), CI(연계정보), DI(중복가입확인정보)를 단일 원장으로 관리하며, 외부에서는 **IdO를 통해서만** 접근 가능하다.

### 1.1 SoR 경계

```
Q-IM 담당 (Source of Record)          Q-IM 비담당
─────────────────────────────────    ────────────────────
qimUserId (UUID) 부여 및 보관          인증 결과 (→ Q-Sign)
CI AES-256-GCM 암호화 저장             Handoff 처리 (→ IdO)
DI HMAC-SHA256 결정론적 생성            유관기관 세션 (→ agency)
회원 상태 이력 (ACTIVE/SUSPENDED/...)
SP(Service Provider) 수신 API 처리
사용자 등록·조회·탈퇴 API
```

---

## 2. 패키지 구조

```
q-im/src/main/java/kr/go/smes/qim/
├── QImApplication.java
├── api/
│   ├── MemberLookupController.java     # CI 기반 회원 조회
│   ├── QimStatusController.java        # 서비스 상태
│   ├── UserController.java             # 회원 등록·조회·상태변경
│   └── dto/
│       ├── DiResponse.java
│       ├── UserRegisterRequest.java
│       ├── UserResponse.java
│       └── UserStatusUpdateRequest.java
├── crypto/
│   ├── CiCryptoService.java            # CI AES-256-GCM 암호화 인터페이스
│   ├── CiCryptoServiceImpl.java        # 버전 기반 키 선택 + AES-256-GCM
│   └── PiiMaskingService.java          # 이름·전화·이메일 마스킹
├── identity/
│   └── DiGenerationService.java        # DI = HMAC-SHA256(qimUserId+agencyCode, secret)
├── entity/
│   └── SnapshotMetaJpaEntity.java      # ★ v1.9.2 GAP-QIM-05
├── outbox/
│   ├── OutboxServiceImpl.java          # Outbox 릴레이 스케줄러
│   ├── SnapshotService.java            # ★ v1.9.2
│   └── SnapshotServiceImpl.java        # ★ v1.9.2 스냅샷 발행
├── repository/
│   └── SnapshotMetaJpaRepository.java  # ★ v1.9.2
└── user/
    ├── UserRegistrationService.java
    └── UserRegistrationServiceImpl.java
```

---

## 3. CI 암호화 (CiCryptoService)

AES-256-GCM 방식의 버전 기반 암호화.

```java
// 암호화: AES-256-GCM, 버전 헤더 접두 포함
String encCi = ciCryptoService.encrypt(rawCi);
// 포맷: "v{version}:{base64(iv+ciphertext+tag)}"

// 복호화: 버전 파싱 → 해당 버전 키로 복호화
String rawCi = ciCryptoService.decrypt(encCi);
```

**설정**:

```yaml
qim:
  ci-encryption:
    current-version: 1
    keys:
      - version: 1
        key: ${QIM_CI_AES_KEY_V1}    # base64 인코딩된 32바이트 키
```

---

## 4. DI 생성 (DiGenerationService)

```java
// DI = Base64URL(HMAC-SHA256(SHA-256(CI) + ":" + agencyCode, QIM_DI_SECRET))
String di = diGenerationService.generateDi(ciHash, agencyCode);
```

- 결정론적이므로 동일 CI + 기관에서 항상 동일한 DI 반환  
- `QIM_DI_SECRET`이 다르면 다른 DI 생성  
- CI 원문은 절대 저장하지 않음 (SHA-256 해시만 저장)

---

## 5. PII 마스킹 (PiiMaskingService)

```java
String maskedName  = pii.maskName("홍길동");     // "홍*동"
String maskedPhone = pii.maskPhone("01012345678"); // "010-****-5678"
String maskedEmail = pii.maskEmail("test@example.com"); // "te**@example.com"
```

감사 로그 및 응답에서 PII 노출 방지용.

---

## 6. Snapshot 발행 (GAP-QIM-05, v1.9.2)

N개(기본 10)의 이벤트마다 Compacted Topic(`qim.user.snapshot`)에 스냅샷을 게시하여 소비자가 전체 이벤트 재생 없이 최신 상태를 복원할 수 있도록 한다.

```java
// OutboxServiceImpl.relayPendingEvents() 내부
if (relayedCount % snapshotInterval == 0) {
    snapshotService.publishSnapshot(qimUserId);
}

// SnapshotServiceImpl
public void publishSnapshot(String qimUserId) {
    // 1. 중복 발행 방지 (SnapshotMetaJpaRepository)
    // 2. 현재 사용자 상태 조회
    // 3. qim.user.snapshot Compacted Topic으로 게시
    // 4. snapshot_meta 상태 PUBLISHED로 업데이트
}
```

---

## 7. SP 수신 API (IdO → Q-IM)

유관기관 회원 정보를 Q-IM이 외부 SP로부터 수신하는 인터페이스.

```
POST /api/qim/sp/v1/member/register    # 회원 등록 (AES 복호화 → CI 저장)
POST /api/qim/sp/v1/member/status      # 회원 상태 변경
GET  /api/qim/sp/v1/member/{instMbrId} # 개별 회원 조회
```

**수신 흐름**:
1. IdO `QimSpReceiverController`가 HTTP 요청 수신
2. `qim.sp.member.events` Kafka 토픽으로 게시 (Transactional Outbox)
3. Q-IM `QimSpMemberEventConsumer`가 소비 → DB 저장

---

## 8. DB 스키마 (qim 스키마 / MariaDB)

> MariaDB 문법 주의: `TIMESTAMPTZ` → `DATETIME(6)`, `JSONB` → `JSON`, `CREATE SCHEMA` 사용 안 함, Partial Index 미지원

| Flyway 버전 | 파일 | 주요 내용 |
|------------|------|---------|
| V1 | `V1__create_schema.sql` | `qim_user`, `auth_mean_mapping`, `outbox_record`, `idempotent_consumer` 기본 스키마 (MariaDB 문법) |
| V2 | `V2__add_idempotent_consumer.sql` | `last_event_version`, `processed_event`, `snapshot_meta` 테이블 추가 |
| V3 | `V3__add_ci_encryption_and_status_history.sql` | `ci_encryption_key_version` 컬럼, `user_status_history` 테이블 추가 |

### qim_user 핵심 컬럼

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `qim_user_id` | VARCHAR(36) PK | UUID 통합 식별자 |
| `ci_hash` | VARCHAR(64) NOT NULL | SHA-256(CI) |
| `enc_ci` | TEXT NOT NULL | AES-256-GCM 암호화 CI |
| `status` | VARCHAR(20) | ACTIVE / SUSPENDED / WITHDRAWN |
| `primary_name` | VARCHAR(100) | 마스킹 처리된 이름 저장 |
| `inst_mbr_id` | VARCHAR(36) | Q-IM instMbrId (SP 연동용) |
| `created_at` | DATETIME(6) | 등록 시각 |
| `updated_at` | DATETIME(6) | 최종 수정 시각 |

### auth_mean_mapping 핵심 컬럼

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `mapping_id` | VARCHAR(36) PK | UUID |
| `qim_user_id` | VARCHAR(36) FK | Q-IM 사용자 |
| `provider_code` | VARCHAR(50) | 인증 수단 코드 |
| `identifier_hash` | VARCHAR(64) | SHA-256(sub 또는 CI) |
| `first_mapped_at` | DATETIME(6) | 최초 연결 시각 |

---

## 9. DB 엔진 선택 이유

| 항목 | Q-IM | 기타 모듈 |
|------|------|----------|
| DB 엔진 | **MariaDB 11** | PostgreSQL 16 |
| 운영 | NHN Cloud RDS for MariaDB | 자체 관리 PostgreSQL |
| PoC | self-hosted MariaDB 11.x 컨테이너 | docker-compose PostgreSQL |

**이유**: Q-IM 운영팀이 NHN Cloud RDS를 사용하는 정책에 따른 결정. MariaDB는 JSONB 대신 JSON(LONGTEXT alias)을 사용한다.

---

## 10. 미구현 항목

| ID | 항목 | 우선순위 |
|----|------|---------|
| GAP-QIM-01 | `needsSync=true` 시 `QimClient.getUserById()` 실제 호출 (현재 TODO 주석) | P1 |
| GAP-QIM-03 | `addAuthMeanMapping()` JPA 저장 구현 (현재 TODO) | P1 |
| GAP-QIM-04 | `OutboxServiceImpl.markFailed()` + `retry_count` 증가 구현 | P1 |
| - | 회원 탈퇴 4종 전체 구현 (IMMEDIATE/SCHEDULED/AGENCY_REQUESTED/ADMIN_FORCED) | P2 |
| - | ConversionSession 상태 기계 (회원 전환 흐름) | P2 |
| - | 14세 미만 보호자 인증 분기 | P2 |

---

## 11. Q-IM 팀 협의 필요 사항

| 항목 | 현재 가정 | 확인 필요 | 우선순위 |
|------|---------|----------|---------|
| encCi 알고리즘/패딩 | AES-256-GCM | 정확한 모드·패딩·IV 전달 방식 | 🔴 P0 |
| AES 공유키 회전 정책 | 수동 교체 가능 | 회전 주기, 무중단 교체 방식 | 🔴 P0 |
| Idempotency-Key 보관 기간 | 7일 | Q-IM 재판단 기간 일치 여부 | 🔴 P0 |
| instMbrId 정책 | qimUserId와 동일 UUID | Q-IM 다른 형식 요구 여부 | 🔴 P0 |
| SP 수신 endpoint URL | `/api/qim/sp/v1/member/*` | Q-IM 콘솔 등록 전 URL 확정 | 🔴 P0 |
| 412 재시도 상한 | 3회 + Outbox fallback | Q-IM 최대 잠금 유지 시간 | 🟡 P1 |

---

*다음 문서: [03d-module-ido.md](03d-module-ido.md)*
