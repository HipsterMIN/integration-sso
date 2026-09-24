-- S6 표준 프로토콜 (docs/generalization-plan.md): 연동 유형 OIDC_RP 추가
--
-- OIDC_RP 기관은 Handoff 대신 표준 OIDC(gate 가 앞에 선 Keycloak)로 붙는다. Keycloak client 는 Idem 이
-- Service Profile(protocol.oidc)에서 프로비저닝하며, 컬럼은 늘리지 않는다(OIDC 설정은 profile JSONB 에만 있다).
-- 허용값 집합은 Java IntegrationType 열거형과 항상 같아야 한다.

ALTER TABLE ido.agency_meta DROP CONSTRAINT IF EXISTS chk_integration_type;
ALTER TABLE ido.agency_meta
    ADD CONSTRAINT chk_integration_type
    CHECK (integration_type IN ('DIRECT','APACHE_GATE','BRIDGE','INTERNAL_SSO','OIDC_RP'));
