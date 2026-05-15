# ADR-009: QIM-OUTBOX-SPEC-001 이벤트 타입 정합화

| 항목 | 내용 |
|------|------|
| **ID** | ADR-009 |
| **제목** | QIM-OUTBOX-SPEC-001: Q-IM → IdO 이벤트 타입 5종 신규 정의 및 정합화 |
| **상태** | ✅ Accepted |
| **결정일** | 2026-05 (Sprint 14) |
| **결정자** | 플랫폼 팀 |
| **관련 파일** | `ido/provision/dto/ProvisioningEventType.java`, `ido/kafka/QimEventConsumer.java`, `ido/qim/sp/service/QimSpReceiverService.java`, `V18__update_event_type_constraints.sql` |

---

## 컨텍스트 (Context)

### 기존 이벤트 타입의 문제

Sprint 14 이전까지 `qim.user.events` 토픽에서 사용하던 이벤트 타입:

```
구 타입 (문제)
  USER_REGISTERED  — 가입 (개인/기업 구분 없음)
  BIZ_CONVERTED    — 기업 전환 (개인 전환 없음)
  USER_UPDATED     — 정보 수정
  USER_WITHDRAWN   — 탈퇴
```

**문제점**:
1. **개인/기업 신규 가입 구분 불가**: `USER_REGISTERED` 하나로 개인·기업 가입 처리 → 기관별 정책 적용 불가
2. **전환 이벤트 불완전**: `BIZ_CONVERTED`는 있으나 `PERSONAL_CONVERTED`(개인 전환) 없음
3. **2×2 매트릭스 미완성**: `resolveRegisterEventType(isTransfer, isCorporate)` 함수가 4종 반환해야 하나 타입 정의 누락
4. **DB CHECK 제약 불일치**: V15 `chk_prov_event_type`이 구 타입만 허용 → 신규 이벤트 INSERT 시 DB 오류

---

## 결정 (Decision)

**QIM-OUTBOX-SPEC-001**을 제정하여 이벤트 타입 5종을 신규 정의하고, 구 타입은 `@Deprecated(forRemoval=true)`로 마킹한다.

### 신규 이벤트 타입 5종

```java
// ProvisioningEventType.java
public enum ProvisioningEventType {
    // QIM-OUTBOX-SPEC-001 신규 5종
    PERSONAL_MEMBER_REGISTERED,  // isTransfer=false, isCorporate=false
    PERSONAL_MEMBER_CONVERTED,   // isTransfer=true,  isCorporate=false
    BIZ_MEMBER_REGISTERED,       // isTransfer=false, isCorporate=true
    BIZ_MEMBER_CONVERTED,        // isTransfer=true,  isCorporate=true
    MEMBER_WITHDRAWN,            // 회원 탈퇴

    // USER_UPDATED (하위 호환 유지 — @Deprecated 제외)
    USER_UPDATED,

    // @Deprecated(forRemoval=true) 구 3종
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    USER_REGISTERED,
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    BIZ_CONVERTED,
    @Deprecated(since = "QIM-OUTBOX-SPEC-001", forRemoval = true)
    USER_WITHDRAWN;
}
```

### 2×2 이벤트 결정 함수

```java
// QimSpReceiverService.resolveRegisterEventType()
private String resolveRegisterEventType(boolean isTransfer, boolean isCorporate) {
    if (!isTransfer && !isCorporate) return "PERSONAL_MEMBER_REGISTERED";
    if ( isTransfer && !isCorporate) return "PERSONAL_MEMBER_CONVERTED";
    if (!isTransfer &&  isCorporate) return "BIZ_MEMBER_REGISTERED";
    if ( isTransfer &&  isCorporate) return "BIZ_MEMBER_CONVERTED";
    throw new IllegalArgumentException("unreachable");
}
```

### 데이터 흐름 (완전한 경로)

```
QimSpReceiverService.resolveRegisterEventType(isTransfer, isCorporate)
  → publishToOutbox(eventType)          ← qim.outbox 삽입
    → OutboxRelay (Q-IM)                ← Kafka 발행
      → qim.user.events 토픽
        → QimEventConsumer (IdO)
          → isProvisioningTriggerEvent()  ← 4종 문자열 비교
            [PERSONAL_MEMBER_REGISTERED, PERSONAL_MEMBER_CONVERTED,
             BIZ_MEMBER_REGISTERED, BIZ_MEMBER_CONVERTED]
          → triggerProvisioning(qimUserId, eventType, ...)
            → ProvisioningServiceImpl.insertOutbox(eventType)
              → provisioning_outbox.event_type
                ← V18 CHECK 제약 (신규 5종 + 구 4종 동시 허용)
```

### V18 DB CHECK 제약 갱신

```sql
-- V18__update_event_type_constraints.sql
ALTER TABLE ido.provisioning_outbox
    DROP CONSTRAINT IF EXISTS chk_prov_event_type;
ALTER TABLE ido.provisioning_outbox
    ADD CONSTRAINT chk_prov_event_type
        CHECK (event_type IN (
            'PERSONAL_MEMBER_REGISTERED', 'PERSONAL_MEMBER_CONVERTED',
            'BIZ_MEMBER_REGISTERED', 'BIZ_MEMBER_CONVERTED', 'MEMBER_WITHDRAWN',
            -- 하위 호환 (구 타입)
            'USER_REGISTERED', 'BIZ_CONVERTED', 'USER_UPDATED', 'USER_WITHDRAWN'
        ));
```

### 프로비저닝 트리거 이벤트 (4종)
> `MEMBER_WITHDRAWN`은 탈퇴 이벤트로 프로비저닝 트리거 제외 (별도 처리)

```java
// QimEventConsumer.isProvisioningTriggerEvent()
return "BIZ_MEMBER_CONVERTED".equals(eventType)
    || "BIZ_MEMBER_REGISTERED".equals(eventType)
    || "PERSONAL_MEMBER_CONVERTED".equals(eventType)
    || "PERSONAL_MEMBER_REGISTERED".equals(eventType);
```

---

## 마이그레이션 계획

| 단계 | 내용 | 상태 |
|------|------|------|
| Phase 1 | 신규 타입 추가 + V18 CHECK 제약 (구 타입 병행 허용) | ✅ 완료 (PR #107) |
| Phase 2 | 구 타입 코드 제거 + @Deprecated 코드 삭제 | ⏳ V19 예정 |
| Phase 3 | V19 CHECK 제약에서 구 타입 제거 | ⏳ V19 예정 |

---

## 결과 (Consequences)

### 긍정적 효과
- 개인·기업, 신규·전환 4종 완전 구분 → 기관별 차별화 정책 적용 가능
- `resolveRegisterEventType` 순수 함수 완성 → 테스트 용이
- DB CHECK 제약으로 잘못된 이벤트 타입 영구 차단

### 부정적 효과
- 구 타입 병행 허용 기간 동안 코드 혼재 (임시)
- Consumer에서 구 타입/신규 타입 모두 처리 필요 (Phase 2 완료까지)

---

## 관련 ADR

- [ADR-004](ADR-004-kafka-eda.md) — Kafka EDA
- [ADR-007](ADR-007-flyway-db-migration.md) — Flyway V18
- [ADR-008](ADR-008-transactional-outbox-pattern.md) — Outbox Pattern
