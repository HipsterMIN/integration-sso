-- D3 (docs/generalization-plan.md): 행 수준 보안 정책 제거
--
-- V1·V3 의 RLS 정책은 세션 GUC app.current_agency 가 설정될 때만 기관 행을 거르는데, 코드 어디서도 이 GUC 를
-- 설정하지 않아 정책은 항상 "전부 허용" 이었다(2차 적대적 점검). 동작하지 않는 보안 장치를 남겨 두면 심사에서
-- 있는 것으로 오해된다. 기관(테넌트) 스코프는 서비스 계층(S7 관리자 스코프)에서 강제한다.
DROP POLICY IF EXISTS rls_authz_role_tenant        ON authz.authz_role;
DROP POLICY IF EXISTS rls_authz_user_role_tenant   ON authz.authz_user_role;
DROP POLICY IF EXISTS rls_authz_grant_audit_tenant ON authz.authz_grant_audit;
DROP POLICY IF EXISTS rls_authz_assignment_tenant  ON authz.authz_assignment;
ALTER TABLE authz.authz_role        DISABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_user_role   DISABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_grant_audit DISABLE ROW LEVEL SECURITY;
ALTER TABLE authz.authz_assignment  DISABLE ROW LEVEL SECURITY;
