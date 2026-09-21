-- ============================================================
-- Q-IM Schema : 사용자 식별 SoR (Source of Record)
-- 설계서 §10 Q-IM 책임 범위 기반
--
-- [DB 변경] PostgreSQL → MariaDB
--   운영: NHN Cloud RDS for MariaDB
--   로컬 PoC: Docker self-hosted MariaDB 11.x
--
-- MariaDB 문법 변환 요약:
--   TIMESTAMPTZ         → DATETIME(6)  (UTC 저장, app 레벨 UTC 보장)
--   JSONB               → JSON  (MariaDB 10.2+ JSON 타입 지원)
--   SMALLINT            → SMALLINT  (동일)
--   CREATE SCHEMA       → 제거 (MariaDB는 DB=스키마, qim DB로 분리)
--   CONSTRAINT chk_xxx  → 제거 후 ENUM 또는 앱 레벨 검증
--                          (MariaDB 10.2.1+ CHECK 지원하나 RDS 버전 호환 위해 앱 레벨 사용)
--   WHERE partial index → 제거 (MariaDB 미지원, 대신 일반 인덱스)
--   DEFAULT NOW()       → DEFAULT CURRENT_TIMESTAMP(6)
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. 사용자 (qim_user)
--    §10.2 사용자 오브젝트 SoR.
--    qim_user_id 는 플랫폼 전역 고유 식별자 (UUIDv4).
--    status 전이: ACTIVE → SUSPENDED → WITHDRAWN (역방향 불가 — 앱 레벨 강제)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS qim_user (
    qim_user_id       VARCHAR(36)   NOT NULL COMMENT 'UUIDv4, 플랫폼 내 불변 식별자',
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED / WITHDRAWN',
    withdrawal_reason VARCHAR(200),
    event_version     BIGINT        NOT NULL DEFAULT 1 COMMENT 'Kafka ordering 기준 + 낙관적 락 버전',
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    withdrawn_at      DATETIME(6),
    CONSTRAINT pk_qim_user PRIMARY KEY (qim_user_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§10.2 사용자 오브젝트 SoR – 플랫폼 전역 qimUserId 기준';

CREATE INDEX idx_qim_user_status     ON qim_user (status);
CREATE INDEX idx_qim_user_updated_at ON qim_user (updated_at DESC);

-- ──────────────────────────────────────────────────────────────
-- 2. 인증 수단 매핑 (auth_mean_mapping)
--    §10.3 identifierHash → qimUserId 단방향 매핑.
--    하나의 identifierHash 는 반드시 하나의 qimUserId 에만 귀속.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS auth_mean_mapping (
    mapping_id        VARCHAR(36)   NOT NULL,
    qim_user_id       VARCHAR(36)   NOT NULL,
    provider_code     VARCHAR(50)   NOT NULL,
    identifier_hash   VARCHAR(300)  NOT NULL COMMENT 'SHA-256(CI / idToken.sub)',
    status            VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / REVOKED',
    linked_at         DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at        DATETIME(6),
    revoke_reason     VARCHAR(200),
    CONSTRAINT pk_auth_mean_mapping  PRIMARY KEY (mapping_id),
    CONSTRAINT uq_identifier_hash    UNIQUE (identifier_hash),
    CONSTRAINT fk_mapping_qim_user   FOREIGN KEY (qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§10.3 identifierHash → qimUserId 단방향 매핑 SoR';

CREATE INDEX idx_mapping_qim_user_id     ON auth_mean_mapping (qim_user_id);
CREATE INDEX idx_mapping_identifier_hash ON auth_mean_mapping (identifier_hash);
CREATE INDEX idx_mapping_provider        ON auth_mean_mapping (provider_code, status);

-- ──────────────────────────────────────────────────────────────
-- 3. 사용자 프로필 / 속성 (user_profile)
--    §10.4 사용자 속성 저장. PII 마스킹 필수.
--    기관은 이 테이블을 직접 참조하지 않고 IdO Verify API 경유.
--
--    [변경] JSONB → JSON (MariaDB)
--           di_map JSON 칼럼: {agencyCode: DI} 기관별 DI 맵
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_profile (
    qim_user_id       VARCHAR(36)   NOT NULL,
    name_masked       VARCHAR(100)  COMMENT '예: 홍*동 — 개인정보보호법 §24 마스킹',
    mobile_masked     VARCHAR(20)   COMMENT '예: 010-****-5678',
    nationality_type  VARCHAR(10)   COMMENT 'DOMESTIC / FOREIGN',
    ci                VARCHAR(300)  COMMENT '연계정보 — AES-256-GCM 암호화 저장',
    di_map            JSON          COMMENT '{agencyCode: DI} 기관별 개별 식별자',
    birth_year        SMALLINT      COMMENT '출생 연도 (일/월 제외)',
    gender            VARCHAR(10)   COMMENT 'MALE / FEMALE / UNKNOWN',
    extra_attributes  JSON          COMMENT '확장 속성 (provider별)',
    updated_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_profile   PRIMARY KEY (qim_user_id),
    CONSTRAINT fk_profile_qim_user FOREIGN KEY (qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§10.4 사용자 속성 저장 – PII 마스킹 필수';

-- ──────────────────────────────────────────────────────────────
-- 4. 사용자 상태 이력 (user_status_history)
--    §10.5 상태 전이 감사 이력. 불변 로그 (DELETE/UPDATE 금지).
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_status_history (
    history_id        VARCHAR(36)   NOT NULL,
    qim_user_id       VARCHAR(36)   NOT NULL,
    status_before     VARCHAR(20)   NOT NULL,
    status_after      VARCHAR(20)   NOT NULL,
    changed_by        VARCHAR(100)  NOT NULL COMMENT '시스템 / 관리자 ID / 본인',
    change_reason     VARCHAR(300),
    correlation_id    VARCHAR(36),
    occurred_at       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_status_history PRIMARY KEY (history_id),
    CONSTRAINT fk_status_history_user FOREIGN KEY (qim_user_id)
        REFERENCES qim_user (qim_user_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§10.5 사용자 상태 전이 감사 이력 – DELETE/UPDATE 금지';

CREATE INDEX idx_status_history_user ON user_status_history (qim_user_id, occurred_at DESC);

-- ──────────────────────────────────────────────────────────────
-- 5. Transactional Outbox (Q-IM → Kafka)
--    §10.5.2 qim.user.events (compact) / qim.user.snapshot 발행.
--    partition_key = qimUserId (동일 사용자 이벤트 순서 보장).
--
--    [변경] JSONB → JSON, WHERE partial index → 일반 인덱스
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS outbox (
    event_id          VARCHAR(36)   NOT NULL COMMENT 'UUIDv4 (멱등 키)',
    event_type        VARCHAR(80)   NOT NULL COMMENT 'USER_REGISTERED / USER_STATUS_CHANGED / MAPPING_ADDED / MAPPING_REVOKED / USER_SNAPSHOT',
    partition_key     VARCHAR(36)   NOT NULL COMMENT 'qimUserId = Kafka 파티션 키',
    aggregate_id      VARCHAR(36)   NOT NULL COMMENT 'qimUserId',
    event_version     BIGINT        NOT NULL COMMENT 'qim_user.event_version 와 동기',
    payload           JSON          NOT NULL,
    topic             VARCHAR(200)  NOT NULL COMMENT 'qim.user.events(compact) 또는 qim.user.snapshot',
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PUBLISHED / FAILED',
    retry_count       SMALLINT      NOT NULL DEFAULT 0,
    error_message     TEXT,
    created_at        DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at      DATETIME(6),
    CONSTRAINT pk_qim_outbox PRIMARY KEY (event_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='§10.5.2 Transactional Outbox – Kafka 발행 큐';

-- MariaDB는 WHERE partial index 미지원 → status 전체 인덱스로 대체
-- Relay 쿼리: SELECT ... WHERE status = 'PENDING' ORDER BY created_at → 인덱스 활용
CREATE INDEX idx_qim_outbox_status    ON outbox (status, created_at);
CREATE INDEX idx_qim_outbox_partition ON outbox (partition_key, event_version);
