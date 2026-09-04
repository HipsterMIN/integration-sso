-- ============================================================
-- Q-Sign Schema : 인증 SoR (Source of Record)
-- 설계서 §9 Q-Sign 책임 범위 기반
-- ============================================================

CREATE SCHEMA IF NOT EXISTS qsign;

-- ──────────────────────────────────────────────────────────────
-- 1. 인증 공급자 (IdP) 등록 정보
--    외부 IdP(카카오·네이버·PASS·금융인증서·GPKI 등)의
--    연동 메타를 관리한다.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qsign.idp_provider (
    provider_code        VARCHAR(50)   NOT NULL,
    provider_name        VARCHAR(200)  NOT NULL,
    auth_level           VARCHAR(10)   NOT NULL,          -- L1 / L2 / L3
    protocol             VARCHAR(20)   NOT NULL,          -- OIDC / SAML / PROPRIETARY
    issuer_url           VARCHAR(500),
    jwks_uri             VARCHAR(500),
    client_id            VARCHAR(200),
    client_secret_ref    VARCHAR(200),                    -- Vault / Secret Manager 참조 경로
    scope_default        VARCHAR(500)  DEFAULT 'openid profile',
    extra_params         JSONB,                           -- provider별 확장 파라미터
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_idp_provider PRIMARY KEY (provider_code),
    CONSTRAINT chk_auth_level CHECK (auth_level IN ('L1','L2','L3'))
);

COMMENT ON TABLE  qsign.idp_provider                IS '외부 인증 공급자(IdP) 등록 및 연동 메타';
COMMENT ON COLUMN qsign.idp_provider.provider_code  IS '고유 식별 코드 (KAKAO_OIDC, PASS, GPKI 등)';
COMMENT ON COLUMN qsign.idp_provider.auth_level      IS '해당 공급자가 보증하는 인증 수준 (L1/L2/L3)';
COMMENT ON COLUMN qsign.idp_provider.client_secret_ref IS 'Secret Manager 참조 경로 – 평문 저장 금지';

-- ──────────────────────────────────────────────────────────────
-- 2. 인증 결과 (AuthResult)
--    §9.3 AuthResult JSON 스키마에 대응하는 영속 레코드.
--    Q-Sign 이 발급한 인증 결과의 단일 진실 공급원(SoR).
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qsign.auth_result (
    auth_result_id       VARCHAR(36)   NOT NULL,          -- UUIDv4
    correlation_id       VARCHAR(36)   NOT NULL,          -- 트랜잭션 추적 ID
    auth_level           VARCHAR(10)   NOT NULL,          -- L1 / L2 / L3
    provider_code        VARCHAR(50)   NOT NULL,
    provider_tx_id       VARCHAR(300),                    -- 외부 IdP 발급 트랜잭션 ID
    identifier_hash      VARCHAR(300)  NOT NULL,          -- SHA-256(CI or idToken sub)
    verification_result  VARCHAR(20)   NOT NULL,          -- SUCCESS / FAIL / BLOCKED
    fail_reason          VARCHAR(100),
    requested_at         TIMESTAMPTZ   NOT NULL,
    authenticated_at     TIMESTAMPTZ,
    claims               JSONB,                           -- IdP 반환 클레임 (PII 마스킹 후 저장)
    internal_signature   TEXT,                            -- HMAC-SHA256(payload, internalKey)
    session_ref          VARCHAR(36),                     -- FE 세션 참조 (nullable)
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_auth_result PRIMARY KEY (auth_result_id),
    CONSTRAINT fk_auth_result_provider
        FOREIGN KEY (provider_code) REFERENCES qsign.idp_provider(provider_code),
    CONSTRAINT chk_auth_result_level CHECK (auth_level IN ('L1','L2','L3')),
    CONSTRAINT chk_auth_result_status
        CHECK (verification_result IN ('SUCCESS','FAIL','BLOCKED'))
);

CREATE INDEX idx_auth_result_identifier_hash ON qsign.auth_result (identifier_hash);
CREATE INDEX idx_auth_result_correlation_id  ON qsign.auth_result (correlation_id);
CREATE INDEX idx_auth_result_provider_tx     ON qsign.auth_result (provider_code, provider_tx_id)
    WHERE provider_tx_id IS NOT NULL;
CREATE INDEX idx_auth_result_created_at      ON qsign.auth_result (created_at DESC);

COMMENT ON TABLE  qsign.auth_result                     IS '§9 Q-Sign 발급 인증 결과 SoR';
COMMENT ON COLUMN qsign.auth_result.identifier_hash     IS 'SHA-256(CI) 또는 SHA-256(idToken.sub) – 역방향 복호화 불가';
COMMENT ON COLUMN qsign.auth_result.internal_signature  IS '내부 위변조 검증용 HMAC-SHA256 서명';
COMMENT ON COLUMN qsign.auth_result.claims              IS 'IdP 반환 클레임 – 이름/연락처 등 PII 마스킹 필수';

-- ──────────────────────────────────────────────────────────────
-- 3. 잠금 / 재시도 (AuthLock)
--    §9.6 잠금·재시도 정책 SoR.
--    Redis 가 주 캐시이지만 DB 가 단일 진실 공급원.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qsign.auth_lock (
    lock_key             VARCHAR(400)  NOT NULL,          -- {identifierHash}:{providerCode}
    attempt_count        SMALLINT      NOT NULL DEFAULT 0,
    max_attempts         SMALLINT      NOT NULL DEFAULT 5,
    locked               BOOLEAN       NOT NULL DEFAULT FALSE,
    locked_at            TIMESTAMPTZ,
    unlock_at            TIMESTAMPTZ,
    last_attempt_at      TIMESTAMPTZ,
    last_fail_reason     VARCHAR(100),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_auth_lock PRIMARY KEY (lock_key),
    CONSTRAINT chk_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_auth_lock_locked ON qsign.auth_lock (locked, unlock_at)
    WHERE locked = TRUE;

COMMENT ON TABLE  qsign.auth_lock           IS '§9.6 인증 수단별 잠금·재시도 카운터 SoR';
COMMENT ON COLUMN qsign.auth_lock.lock_key  IS '{identifierHash}:{providerCode} 복합 키';

-- ──────────────────────────────────────────────────────────────
-- 4. Transactional Outbox (Q-Sign → Kafka)
--    §9.3 인증 이벤트를 Kafka qsign.auth.events 로 발행.
--    Outbox Relay 배치가 PENDING 레코드를 발행 후 PUBLISHED 처리.
-- ──────────────────────────────────────────────────────────────
CREATE TABLE qsign.outbox (
    event_id             VARCHAR(36)   NOT NULL,          -- UUIDv4 (멱등 키)
    event_type           VARCHAR(80)   NOT NULL,          -- AUTH_SUCCESS / AUTH_FAIL / AUTH_LOCKED
    partition_key        VARCHAR(300)  NOT NULL,          -- identifierHash (Kafka partition key)
    aggregate_id         VARCHAR(36)   NOT NULL,          -- auth_result_id
    event_version        BIGINT        NOT NULL DEFAULT 1,
    payload              JSONB         NOT NULL,
    topic                VARCHAR(200)  NOT NULL DEFAULT 'qsign.auth.events',
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    retry_count          SMALLINT      NOT NULL DEFAULT 0,
    error_message        TEXT,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    published_at         TIMESTAMPTZ,
    CONSTRAINT pk_qsign_outbox PRIMARY KEY (event_id),
    CONSTRAINT chk_qsign_outbox_status
        CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);

CREATE INDEX idx_qsign_outbox_status     ON qsign.outbox (status, created_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_qsign_outbox_aggregate  ON qsign.outbox (aggregate_id);

COMMENT ON TABLE  qsign.outbox               IS '§9.3 Transactional Outbox – qsign.auth.events 발행용';
COMMENT ON COLUMN qsign.outbox.partition_key IS 'Kafka 파티션 키 = identifierHash';
COMMENT ON COLUMN qsign.outbox.status        IS 'PENDING → PUBLISHED | FAILED';

-- ──────────────────────────────────────────────────────────────
-- 5. Flyway 메타 전용 샘플 데이터 (IdP 공급자 초기값)
-- ──────────────────────────────────────────────────────────────
INSERT INTO qsign.idp_provider
    (provider_code, provider_name, auth_level, protocol, active)
VALUES
    ('KAKAO_OIDC',      '카카오 소셜 로그인',        'L1', 'OIDC',        TRUE),
    ('NAVER_OIDC',      '네이버 소셜 로그인',        'L1', 'OIDC',        TRUE),
    ('PASS',            'PASS 인증 (통신사)',         'L2', 'PROPRIETARY', TRUE),
    ('FINANCIAL_CERT',  '금융인증서',                'L3', 'PROPRIETARY', TRUE),
    ('GPKI',            '행정전자서명(GPKI)',         'L3', 'SAML',        TRUE)
ON CONFLICT (provider_code) DO NOTHING;
