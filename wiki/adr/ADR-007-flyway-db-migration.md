# ADR-007: Flyway 스키마 마이그레이션 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-007 |
| **제목** | Flyway를 DB 스키마 버전 관리 도구로 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2025-Q4 (Sprint 1) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `*/src/main/resources/db/migration/V*.sql` |

---

## 컨텍스트 (Context)

4개 독립 서비스가 각각 DB 스키마를 관리해야 한다. 수동 스크립트 실행 방식의 문제:
- 배포 시 스크립트 실행 누락 위험
- 서비스별 스키마 버전 불일치
- 롤백 기록 없음
- CI/CD 자동화 불가

---

## 결정 (Decision)

**Flyway**를 각 서비스의 DB 스키마 버전 관리 도구로 채택한다. 서비스 시작 시 자동 마이그레이션 실행.

### 버전 관리 원칙

#### 1. 파일 명명 규칙
```
V{버전}__{설명}.sql
예) V18__update_event_type_constraints.sql
```

#### 2. 절대 수정 금지
- 한번 배포된 `V*.sql` 파일은 절대 수정 불가
- 변경 필요 시 새 버전 파일 추가 (DROP + ADD 패턴)

#### 3. 충돌 방지 — 교훈 (PR #106)
```
문제: V10 번호 중복 → MigrationException 발생
  - V10__add_outbox_next_retry_at.sql (기존)
  - V10__extend_auth_result_and_provider_routing.sql (신규 충돌)
해결: git mv → V17__add_outbox_next_retry_at.sql (번호 조정)
```

**번호 충돌 방지 규칙**:
- PR 머지 전 팀 채널에 "V{N} 사용 선언"
- main 브랜치 마이그레이션 파일 목록 확인 의무화

#### 4. 데이터 마이그레이션 분리 원칙
```sql
-- 스키마 변경 (DDL) — Flyway V* 파일
ALTER TABLE ido.provisioning_outbox ADD COLUMN ...;

-- 데이터 시드 (DML) — 별도 V*__seed_*.sql 파일
INSERT INTO ido.agency_meta ...;
```

#### 5. 검증 쿼리 포함
```sql
-- V18 예시: 적용 후 수동 검증용 주석 포함
-- SELECT conname, consrc FROM pg_constraint
-- WHERE conrelid = 'ido.provisioning_outbox'::regclass AND contype = 'c';
```

### 서비스별 버전 현황

| 서비스 | 최신 버전 | 주요 마이그레이션 |
|--------|-----------|------------------|
| **IdO** | V18 | V15(Provisioning), V16(Gateway Audit), V17(next_retry_at), V18(CHECK 제약) |
| **Q-IM** | V6 | V3(CI 암호화), V5(탈퇴·동의·전환), V6(미성년·법인) |
| **Q-Sign** | V5 | V2(감사 로그), V3(OIDC 세션), V5(인증 수단) |
| **Agency-Stub** | V2 | V2(Webhook, API Key) |

---

## 결과 (Consequences)

### 긍정적 효과
- **자동 마이그레이션**: 서비스 시작 시 자동 적용 → 수동 실수 제거
- **버전 이력**: `flyway_schema_history` 테이블에 모든 적용 이력 보관
- **CI/CD 통합**: 배포 파이프라인에서 마이그레이션 성공 여부 확인 가능
- **다중 서비스 독립**: 서비스별 마이그레이션 충돌 없음

### 부정적 효과 / 주의사항
- **번호 충돌 위험**: 병렬 개발 시 동일 버전 번호 선택 → 팀 프로세스 필수
- **롤백 제한**: Flyway CE는 자동 롤백 미지원 → 수동 DDL 롤백 스크립트 준비
- **대용량 마이그레이션**: 운영 중 대용량 테이블 ALTER → 락 타임아웃 주의 (pt-online-schema-change 고려)

---

## 관련 ADR

- [ADR-005](ADR-005-postgresql-primary-store.md) — PostgreSQL (Flyway 대상 DB)
- [ADR-009](ADR-009-qim-outbox-spec-001.md) — V18 마이그레이션 (QIM-OUTBOX-SPEC-001)
