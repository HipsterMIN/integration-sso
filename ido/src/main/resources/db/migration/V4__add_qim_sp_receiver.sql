-- ============================================================
-- IdO Schema : Q-IM SP 수신 API 연동 테이블
-- Q-IM 유관기관 SP 개발자 API 명세서 v1.52 기반
-- IdO가 Q-IM의 SP 역할을 대리 수행하기 위한 인프라
-- ============================================================

-- ──────────────────────────────────────────────────────────────
-- 1. instMbrId 매핑 테이블
--    Q-IM이 요구하는 SP 내부 식별자(instMbrId) 관리
--    설계 원칙: instMbrId = qimUserId (1:1 UUID 매핑)
--    Q-IM은 이 값을 이후 모든 송수신에서 매핑 기준으로 사용
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.inst_mbr_id_mapping (
    inst_mbr_id          VARCHAR(36)   NOT NULL,          -- SP 내부 식별자 (= qimUserId)
    qim_user_id          VARCHAR(36)   NOT NULL,          -- Q-IM UUID (= inst_mbr_id)
    mbr_uuid             VARCHAR(36),                     -- Q-IM 발행 mbrUuid (등록 수신 시 저장)
    mbr_no               VARCHAR(50),                     -- Q-IM 발행 mbrNo
    identifier_hash      VARCHAR(300),                    -- SHA-256(CI) — 조회 최적화용
    member_type          VARCHAR(20)   NOT NULL DEFAULT 'PERSONAL', -- PERSONAL | CORPORATE
    status               VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    reg_mode             VARCHAR(20),                     -- NEW | TRANSFER
    registered_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    withdrawn_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_inst_mbr_id_mapping PRIMARY KEY (inst_mbr_id),
    CONSTRAINT uq_inst_mbr_qim_user   UNIQUE (qim_user_id),
    CONSTRAINT chk_inst_mbr_member_type
        CHECK (member_type IN ('PERSONAL','CORPORATE')),
    CONSTRAINT chk_inst_mbr_status
        CHECK (status IN ('ACTIVE','WITHDRAWN','SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_inst_mbr_qim_user_id    ON ido.inst_mbr_id_mapping (qim_user_id);
CREATE INDEX IF NOT EXISTS idx_inst_mbr_identifier     ON ido.inst_mbr_id_mapping (identifier_hash)
    WHERE identifier_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_inst_mbr_mbr_uuid       ON ido.inst_mbr_id_mapping (mbr_uuid)
    WHERE mbr_uuid IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_inst_mbr_status         ON ido.inst_mbr_id_mapping (status, registered_at DESC);

COMMENT ON TABLE  ido.inst_mbr_id_mapping              IS 'Q-IM SP 연동 — instMbrId(=qimUserId) 매핑 SoR';
COMMENT ON COLUMN ido.inst_mbr_id_mapping.inst_mbr_id  IS 'Q-IM에 반환하는 SP 내부 식별자 (= qimUserId UUID)';
COMMENT ON COLUMN ido.inst_mbr_id_mapping.mbr_uuid     IS 'Q-IM이 관리하는 회원 UUID (MEMBER_REGISTER 수신 시 저장)';
COMMENT ON COLUMN ido.inst_mbr_id_mapping.member_type  IS 'PERSONAL(개인회원) | CORPORATE(기업회원)';

-- ──────────────────────────────────────────────────────────────
-- 2. SP 수신 API 멱등성 테이블
--    Q-IM 명세 §3.4: 동일 Idempotency-Key 재호출 시 직전 응답 재생
--    저장소: DB (Redis TTL 기반으로 전환 가능)
--    보관 기간: 7일 (expires_at 기준 배치 삭제)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.sp_receiver_idempotency (
    idempotency_key      VARCHAR(200)  NOT NULL,
    endpoint             VARCHAR(20)   NOT NULL,          -- QUERY | REGISTER | WITHDRAW
    http_status          SMALLINT      NOT NULL DEFAULT 200,
    response_json        TEXT          NOT NULL,          -- 직렬화된 응답 JSON
    correlation_id       VARCHAR(36),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_sp_receiver_idempotency PRIMARY KEY (idempotency_key),
    CONSTRAINT chk_sp_endpoint
        CHECK (endpoint IN ('QUERY','REGISTER','WITHDRAW'))
);

CREATE INDEX IF NOT EXISTS idx_sp_idempotency_expires ON ido.sp_receiver_idempotency (expires_at);  -- 일반 인덱스 (partial index WHERE NOW() 는 IMMUTABLE 제약으로 불가)

COMMENT ON TABLE  ido.sp_receiver_idempotency               IS 'Q-IM SP 수신 API 멱등성 저장소 (TTL=7일)';
COMMENT ON COLUMN ido.sp_receiver_idempotency.response_json IS '재호출 시 그대로 반환할 응답 JSON 문자열';
COMMENT ON COLUMN ido.sp_receiver_idempotency.expires_at    IS '만료 후 배치 삭제 대상 (기본 7일)';

-- ──────────────────────────────────────────────────────────────
-- 3. Q-IM 아웃바운드 수신 감사 로그
--    모든 수신 호출의 전수 감사 기록
--    멱등 재호출도 기록 (is_replay=true)
-- ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ido.qim_sp_receiver_log (
    log_id               VARCHAR(36)   NOT NULL,
    idempotency_key      VARCHAR(200),
    endpoint             VARCHAR(20)   NOT NULL,
    qim_user_id          VARCHAR(36),
    inst_mbr_id          VARCHAR(36),
    correlation_id       VARCHAR(36),
    http_status          SMALLINT      NOT NULL,
    is_replay            BOOLEAN       NOT NULL DEFAULT FALSE, -- 멱등 재호출 여부
    error_code           VARCHAR(50),
    received_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_qim_sp_receiver_log PRIMARY KEY (log_id)
);

CREATE INDEX IF NOT EXISTS idx_sp_receiver_log_user    ON ido.qim_sp_receiver_log (qim_user_id, received_at DESC)
    WHERE qim_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sp_receiver_log_at      ON ido.qim_sp_receiver_log (received_at DESC);
CREATE INDEX IF NOT EXISTS idx_sp_receiver_log_idem    ON ido.qim_sp_receiver_log (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON TABLE ido.qim_sp_receiver_log IS 'Q-IM → IdO 아웃바운드 수신 전수 감사 로그';

-- ──────────────────────────────────────────────────────────────
-- 4. Outbox 토픽 확장: qim.sp.member.events
--    기존 ido.outbox 테이블 재사용
--    신규 topic 값: qim.sp.member.events
--    신규 event_type: QIM_MEMBER_REGISTERED | QIM_MEMBER_TRANSFERRED | QIM_MEMBER_WITHDRAWN
--    별도 테이블 불필요 — outbox.topic 컬럼으로 구분
-- ──────────────────────────────────────────────────────────────
-- (별도 테이블 없음 — ido.outbox 재사용, 코멘트만 추가)
COMMENT ON COLUMN ido.outbox.event_type IS
    'HANDOFF_ISSUED | HANDOFF_CONSUMED | HANDOFF_EXPIRED | SESSION_ADVISORY '
    '| QIM_MEMBER_REGISTERED | QIM_MEMBER_TRANSFERRED | QIM_MEMBER_WITHDRAWN';

-- ──────────────────────────────────────────────────────────────
-- 5. agency_meta 확장: Q-IM 연동 설정
--    Fallback URL 및 Q-IM 아웃바운드 알림 설정
-- ──────────────────────────────────────────────────────────────
ALTER TABLE ido.agency_meta
    ADD COLUMN IF NOT EXISTS fallback_login_url  VARCHAR(500),
    ADD COLUMN IF NOT EXISTS fallback_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS qim_sp_notified     BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN ido.agency_meta.fallback_login_url IS 'Circuit Breaker OPEN 시 유관시스템 임시 로그인 URL';
COMMENT ON COLUMN ido.agency_meta.fallback_enabled   IS 'Fallback 기능 활성화 여부';
COMMENT ON COLUMN ido.agency_meta.qim_sp_notified    IS 'Q-IM SP 수신 이벤트로 이 기관에 알림 발송 여부';
