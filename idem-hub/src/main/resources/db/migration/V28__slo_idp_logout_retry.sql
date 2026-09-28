-- ============================================================================
-- V28 (1.1): SLO IdP(Keycloak) 세션 종료 재시도 큐
--   · SloServiceImpl 의 ① 단계(gate /api/v1/internal/session/logout)가 실패하면(비 2xx·예외·X-Idp-Logout-Outcome=FAILED)
--     여기 적재하고 SloIdpLogoutRetryRelay 가 지수 백오프로 재시도한다. max_retry 초과는 FAILED + 감사(SLO_IDP_LOGOUT_FAILED).
--   · 종전(1.0)에는 WARN 로그만 남고 Keycloak 세션이 살아 있을 수 있었다 (D2 남긴 것: "SLO 2단계 실패 재시도 큐").
-- ============================================================================

CREATE TABLE IF NOT EXISTS idem_hub.slo_idp_logout_retry (
    retry_id        VARCHAR(36)   NOT NULL,
    fe_session_id   VARCHAR(64)   NOT NULL,
    qim_user_id     VARCHAR(64)   NOT NULL,
    idp_sub         VARCHAR(128),
    idp_sid         VARCHAR(128),
    correlation_id  VARCHAR(64),
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',   -- PENDING / DONE / FAILED
    retry_count     SMALLINT      NOT NULL DEFAULT 0,
    max_retry       SMALLINT      NOT NULL DEFAULT 5,
    next_retry_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    done_at         TIMESTAMPTZ,
    CONSTRAINT pk_slo_idp_logout_retry PRIMARY KEY (retry_id)
);

CREATE INDEX IF NOT EXISTS idx_slo_idp_logout_retry_due
    ON idem_hub.slo_idp_logout_retry (status, next_retry_at);

COMMENT ON TABLE idem_hub.slo_idp_logout_retry IS '1.1: SLO IdP 세션 종료 재시도 큐 — gate 호출 실패 시 적재, SloIdpLogoutRetryRelay 가 지수 백오프 재시도';
