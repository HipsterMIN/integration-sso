-- ============================================================
-- V9 (범용화 S4b): 사용자의 Tenant 소속 + 회원 전환(기관 팬아웃) 테이블 제거
--   docs/generalization-plan.md §2.0 · §3 S4b
-- ============================================================
--
-- ■ qim_user.tenant_code
--   사용자는 Tenant(Realm, 운영기관 디렉터리)에 속한다. Service(기관)에 속하지 않는다.
--   기존 행은 'DEFAULT'. hub ido.tenant.tenant_code 와 같은 값.
--
-- ■ conversion_session 삭제
--   플랫폼이 68개 기관 DB 를 돌며 회원을 조회·연결하던 흐름(registry conversion 패키지)은
--   범용 IdP 모델(디렉터리가 진실, 계정 연결은 Service 측 첫 로그인 시)에 맞지 않아 제거한다.
--   제품 흐름에서 호출된 적이 없는 테이블이므로 데이터 이관은 없다.
-- ============================================================

ALTER TABLE qim_user
    ADD COLUMN IF NOT EXISTS tenant_code VARCHAR(50) NOT NULL DEFAULT 'DEFAULT' COMMENT '소속 Tenant(Realm) — hub ido.tenant.tenant_code';

CREATE INDEX IF NOT EXISTS idx_qim_user_tenant ON qim_user (tenant_code);

DROP TABLE IF EXISTS conversion_session;
