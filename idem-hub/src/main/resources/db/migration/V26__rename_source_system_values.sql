-- S9 PR-2 (개명 5단계): auth_result.source_system 값 ido-* → idem-hub-* (docs/naming.md §3)
-- V3 의 CHECK 를 새 값으로 바꾸고 기존 행을 옮긴다. 코드(KeycloakOidcService·NonOidcAuthService)는 새 값만 쓴다.
ALTER TABLE idem_hub.auth_result DROP CONSTRAINT IF EXISTS chk_ido_source_system;
UPDATE idem_hub.auth_result SET source_system = 'idem-hub-' || substring(source_system from 5) WHERE source_system LIKE 'ido-%';
ALTER TABLE idem_hub.auth_result ADD CONSTRAINT chk_ido_source_system
    CHECK (source_system IN ('idem-hub-keycloak','idem-hub-nonoidc','idem-hub-adapter'));
