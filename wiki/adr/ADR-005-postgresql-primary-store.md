# ADR-005: PostgreSQL 주 데이터 저장소 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-005 |
| **제목** | PostgreSQL을 모든 서비스의 주 데이터 저장소로 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2025-Q4 (Sprint 1) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `*/src/main/resources/db/migration/`, `*/application.yml` |

---

## 컨텍스트 (Context)

통합인증 플랫폼의 데이터 특성:

- **트랜잭션 무결성 필수**: 회원 등록 + Outbox 삽입은 단일 DB 트랜잭션으로 처리되어야 함
- **JSONB 필요**: 프로비저닝 페이로드, 이벤트 페이로드를 유연하게 저장
- **CHECK 제약 조건**: 이벤트 타입 유효성 DB 레벨 보장 (`chk_prov_event_type` 등)
- **UUID v7**: 시간 순 정렬 가능한 PK (PostgreSQL 네이티브 지원 없음 → 애플리케이션 생성)
- **스키마 분리**: `ido`, `qim`, `qsign` 스키마로 논리 격리
- **공공 기관 요건**: 국산·검증 DB 또는 PostgreSQL (공공 SI 표준 적합)

---

## 결정 (Decision)

**PostgreSQL 14+** 를 전 서비스 주 데이터 저장소로 채택한다.

### 스키마 격리 전략

```sql
-- 각 서비스별 독립 스키마
CREATE SCHEMA ido;    -- IdO 전용
CREATE SCHEMA qim;    -- Q-IM 전용 (실제로는 별도 DB 인스턴스 권장)
CREATE SCHEMA qsign;  -- Q-Sign 전용
```

### Flyway 마이그레이션 버전 현황 (IdO 기준)

| 버전 | 내용 |
|------|------|
| V1 | 기본 스키마 (outbox, ticket, fe_session 등) |
| V2 | FE 세션 테이블 |
| V3 | Keycloak 인증 결과 |
| V4 | Q-IM SP Receiver (멱등성 키) |
| V5 | Processed Event (Kafka 멱등성) |
| V6 | Broker Audit Log |
| V7 | Webhook + Gateway Audit |
| V8 | Agency API Key 시드 데이터 |
| V9 | Crypto Key Registry + Rate Limit |
| V10 | Auth Result 확장 + Provider Routing |
| V11 | SLO 보존 설정 |
| V12 | MFA AAL 스키마 |
| V13 | Agency 패턴 시나리오 시드 |
| V14 | CAST Token + SSO Session |
| V15 | Provisioning Outbox + Agency Endpoint |
| V16 | Gateway Inbound Audit |
| V17 | Outbox next_retry_at 컬럼 + 인덱스 |
| V18 | CHECK 제약 갱신 (QIM-OUTBOX-SPEC-001) |

### 핵심 설계 패턴

#### JSONB 활용
```sql
-- provisioning_outbox
payload_json JSONB NOT NULL;

-- webhook_dispatch_outbox
event_type_filter JSONB;  -- 이벤트 필터 유연 저장
```

#### CHECK 제약으로 이벤트 타입 유효성 보장
```sql
-- V18 기준
CONSTRAINT chk_prov_event_type CHECK (event_type IN (
    'PERSONAL_MEMBER_REGISTERED', 'PERSONAL_MEMBER_CONVERTED',
    'BIZ_MEMBER_REGISTERED', 'BIZ_MEMBER_CONVERTED', 'MEMBER_WITHDRAWN',
    'USER_REGISTERED', 'BIZ_CONVERTED', 'USER_UPDATED', 'USER_WITHDRAWN'
));
```

#### Outbox 인덱스 전략
```sql
-- next_retry_at 인덱스: Relay 폴링 성능 최적화
CREATE INDEX idx_prov_outbox_pending_retry
    ON ido.provisioning_outbox (status, next_retry_at)
    WHERE status = 'PENDING';
```

---

## 결과 (Consequences)

### 긍정적 효과
- **ACID 트랜잭션**: Outbox 패턴을 단일 트랜잭션으로 구현 가능 (핵심 요구사항)
- **JSONB**: 이벤트 페이로드 유연 저장, GIN 인덱스로 빠른 검색
- **CHECK 제약**: 애플리케이션 외부에서도 데이터 무결성 보장
- **Flyway 호환**: 스키마 버전 관리 완벽 지원
- **공공 기관 적합성**: 검증된 오픈소스, 조달 승인 이력 다수

### 부정적 효과
- **수평 확장 한계**: 쓰기 확장 시 Citus 또는 PgBouncer 필요
- **UUID v7 미지원**: 애플리케이션에서 생성 (`UlidCreator` 또는 Java UUID v7 라이브러리)

### 포기한 대안
- **MySQL/MariaDB**: JSONB 미지원, CHECK 제약 실행 방식 차이
- **MongoDB**: ACID 트랜잭션 제한, Outbox 패턴 구현 복잡
- **Oracle**: 라이선스 비용, 공공 오픈소스 정책 미부합

---

## 관련 ADR

- [ADR-007](ADR-007-flyway-db-migration.md) — Flyway 마이그레이션
- [ADR-008](ADR-008-transactional-outbox-pattern.md) — Transactional Outbox (DB 트랜잭션 의존)
