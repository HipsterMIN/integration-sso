-- V6 (1.1.4 G2-10, Keycloak 26 시험 중 발견): Keycloak 중계 로그인이 만드는 provider_code 의 idp_provider 행
--
-- gate 는 Keycloak ID Token 의 identity_provider 별칭("social-<name>")에서 "<NAME>_OIDC" 를, 별칭이 없는 Keycloak 계정 직접 로그인
-- (코어 로그인 프런트의 provider=broker:keycloak)에서는 "KEYCLOAK_OIDC" 를 만들어 auth_result.provider_code 에 넣는다
-- (KeycloakCallbackService.resolveProviderCode). V1 시드는 KAKAO_OIDC·NAVER_OIDC 만 있어 그 밖의 코드는 FK(fk_auth_result_provider)
-- 위반으로 인증 결과 저장이 실패했다 — 설치본 realm(realm-export.json)의 IdP 별칭 social-pass·social-gpki 와 직접 로그인에 해당하는 행을 더한다.
INSERT INTO idem_gate.idp_provider
    (provider_code, provider_name, auth_level, protocol, active)
VALUES
    ('KEYCLOAK_OIDC', 'Keycloak 계정 (직접 로그인)',          'L1', 'OIDC', TRUE),
    ('PASS_OIDC',     'PASS 인증 (Keycloak IdP 중계)',        'L2', 'OIDC', TRUE),
    ('GPKI_OIDC',     '행정전자서명(GPKI) (Keycloak IdP 중계)', 'L3', 'OIDC', TRUE)
ON CONFLICT (provider_code) DO NOTHING;
