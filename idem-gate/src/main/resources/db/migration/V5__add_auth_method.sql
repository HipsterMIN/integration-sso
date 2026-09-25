-- ============================================================
-- Q-Sign V5 : auth_method 컬럼 추가
-- 설계서 §24.4.1 GAP-QS-01 — authMethod 저장 규칙
-- ============================================================

ALTER TABLE idem_gate.auth_result
    ADD COLUMN IF NOT EXISTS auth_method VARCHAR(30);

COMMENT ON COLUMN idem_gate.auth_result.auth_method
    IS '§24.4.1 인증 방법 분류 코드 (STANDARD_OIDC_* / SEMI_STANDARD_OIDC_* / NON_STANDARD_*)';
