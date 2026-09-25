# SAML 2.0 (SAML_SP) 설계 노트 — 범용화 S6 PR-2 ⑤ (구현 아님)

> 상태: 설계만. 구현 우선순위는 S7·S9 뒤(GS 1.0 범위 밖). 표준 OIDC(OIDC_RP, ADR-014)가 먼저 정식화됐고, SAML 은 그 위에 같은 모양으로 얹는다.

## 1. 요구
기관 SSO 가 SAML 2.0 SP 로만 붙을 수 있는 경우(기존 상용 SSO·레거시 포털). Idem 은 **SAML IdP** 역할이어야 한다(기관 = SP).

## 2. 결정 초안
- **Keycloak 의 SAML client 를 쓴다** — OIDC_RP 와 같은 원칙: Keycloak 은 숨기고, client 는 Idem 이 프로파일에서 프로비저닝한다. 자체 SAML IdP 구현은 하지 않는다.
- Service Profile: `protocol.type=SAML_SP` + `protocol.saml { entityId, acsUrls[], sloUrl, nameIdFormat(persistent|transient|emailAddress), signAssertions, signatureAlgorithm, spCertificate(PEM), attributeMapping }`. 스키마 v1 에 `if/then` 으로 `saml` 필수.
- 프로비저닝: hub `OidcRpClientProvisioner` 를 `KeycloakClientProvisioner` 로 일반화 — `protocol=saml`, `clientId=entityId`, `redirectUris=acsUrls`, `attributes.saml.assertion.signature=true`, `saml_name_id_format`, `saml.signing.certificate` … Idem 클레임 매퍼는 SAML 속성(`idem_state`·`idem_subject`·`idem_roles`)으로.
- gate 프런트: `/realms/{realm}/protocol/saml/**` 투명 프록시(이미 `/realms/**` 가 지난다). IdP 메타데이터 `GET /realms/{realm}/protocol/saml/descriptor` 는 issuer 가 공개 URL 이라 그대로 쓸 수 있다.
- **정책 강제 지점이 문제다.** OIDC 는 토큰 교환(백채널)에서 hub 가 판정하지만, SAML Response 는 브라우저 POST 로 SP 에 직접 간다 — 가로챌 백채널이 없다. 선택지:
  1. Keycloak **authenticator SPI**(Java 확장)로 로그인 흐름 안에서 hub 판정 → 거부 시 로그인 화면 오류. OIDC 에도 심층 방어로 유익. 비용: Keycloak 버전에 묶인 확장 jar·커스텀 이미지.
  2. gate 가 `/protocol/saml` POST(SP → IdP 요청)와 Keycloak 의 Response(IdP → SP, 브라우저에 200 HTML 폼으로 내려감)를 프록시하면서 Response 본문을 파싱해 sub 를 뽑고 hub 판정 후 거부면 폼 대신 오류 페이지. 서명된 XML 을 읽기만 하므로 무결성은 유지된다. 비용: XML 파싱·상태 관리가 gate 에 들어온다.
  → **1 을 택한다.** SAML 을 하려면 어차피 Keycloak 확장이 필요하고(속성 매퍼 커스텀), OIDC 경로의 심층 방어도 같이 얻는다.
- SLO: SAML SLO(리다이렉트/POST 바인딩)는 Keycloak 이 처리, gate 의 Back-Channel Logout 수신기가 그대로 FE 세션을 정리한다(세션은 프로토콜 무관).
- tenant-sample: `protocol=SAML_SP` 경로는 Spring Security SAML2 RP(`spring-security-saml2-service-provider`)로 — OIDC 와 달리 손으로 짜지 않는다(XML 서명 검증을 직접 구현하지 않는다).

## 3. 범위 밖
IdP-initiated SSO, 암호화 어설션(선택), 아티팩트 바인딩, 메타데이터 자동 교환.

## 4. 선행 조건
S7 관리 콘솔의 프로파일 폼(인증서 업로드), D3 의 CI compose 실기동(Keycloak 확장 이미지 빌드 검증).
