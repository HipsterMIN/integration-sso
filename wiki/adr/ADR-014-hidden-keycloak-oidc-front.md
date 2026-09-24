# ADR-014: Keycloak 을 숨긴 표준 OIDC 프런트 (OIDC_RP)

> **상태**: ✅ 채택 (범용화 S6, 2026-09-24)  
> **작성일**: 2026-09-24  
> **관련**: `docs/generalization-plan.md` §0 "Keycloak 유지"·S6, ADR-010(CAST), ADR-011(HMAC 내부 인증)

---

## 배경

Idem 의 기관 연계는 독자 프로토콜(Handoff 티켓·CAST·웹훅·Java Agent)에 편중돼 있었다. 표준은 Keycloak 브로커링(OIDC 소비)뿐이고,
gate 의 Discovery 문서는 Keycloak 의 URL 과 기능 목록(implicit·password·CIBA·HS256 …)을 그대로 내보냈다. "표준 OIDC 로 붙겠다" 는
기관에게는 Keycloak 콘솔에서 client 를 손으로 만들어야 했고, 그렇게 붙은 RP 는 Idem 의 정책(점검·인증수준·허용 제공자·사용자 상태·할당)을
지나지 않았다. GS·CC 시연에서 로그인이 독자 프로토콜이면 설명할 것이 하나 더 는다(v0.4 적대적 점검).

자체 IdP 를 만드는 선택지는 §0 에서 배제됐다(Keycloak 유지). 그렇다면 Keycloak 을 **설치본 내부 구성요소**로 완전히 숨기고, 표준 OIDC 의
공개 얼굴과 정책 강제 지점을 Idem 코드(gate·hub)에 두어야 한다.

## 결정

1. **gate 가 OIDC Provider 의 공개 얼굴이다.** issuer 는 `{gate 공개 URL}/realms/{realm}` 이고 Keycloak 의 `KC_HOSTNAME_URL` 을 같은 값으로
   고정한다(id_token `iss` 일치). gate 는 `/realms/{realm}/.well-known/openid-configuration` 에 **정직한** Discovery(실제 지원: code·PKCE S256·
   RS256·client_secret_basic/post·refresh_token) 를 내고, `/realms/**`·`/resources/**` 를 Keycloak 으로 투명 프록시한다(302·쿠키 통과,
   내부 주소 `Location` 재작성, `X-Forwarded-*` 공급). Keycloak 포트는 loopback 관리 콘솔 외에 열지 않는다.
2. **client 는 Idem 이 프로비저닝한다.** Service Profile `protocol.type=OIDC_RP` + `protocol.oidc{redirectUris, postLogoutRedirectUris,
   backchannelLogoutUri, clientAuthMethod}` 를 저장하면 hub 가 같은 트랜잭션에서 Keycloak client `idem-svc-{code}` 를 만들거나 맞춘다.
   고정 규칙(confidential·Authorization Code 만·PKCE S256·`fullScopeAllowed=false`·`identity_provider`/`idem_service` 매퍼)은 기관이 바꿀 수 없다.
   실패는 `E-IDO-122` 로 저장을 되돌린다. 유형이 바뀌면 client 는 삭제하지 않고 비활성으로 남긴다. secret 은 회전 응답에서만 한 번 보인다.
   프로비저닝 자격은 `realm-management: manage-clients/view-clients` 만 가진 서비스 계정(`idem-provisioner`)이며 관리자 비밀번호는 앱이 쓰지 않는다.
3. **정책 강제 지점은 토큰 교환이다.** gate 는 Keycloak 이 200 으로 토큰을 내준 직후 hub `POST /api/internal/v1/oidc-rp/access`(HMAC 내부 서명)에
   묻는다. hub 는 client → 서비스, `sub` → registry 사용자(찾거나 등록), 그리고 Handoff 발급과 **같은 `PolicyEngine`** 으로 규칙을 평가한다.
   거부면 gate 는 토큰을 돌려주지 않고(`403 access_denied`, `error_description` 첫 토큰 = `E-IDO-1xx`) Keycloak 세션을 refresh_token 로그아웃으로
   끊는다(최선 노력). hub 가 닿지 않으면 `503 temporarily_unavailable`(fail-closed). 허용 판정은 (client, sub) 로 Redis 에 캐시돼 userinfo 에
   `idem_service·idem_state·idem_subject·idem_subject_scheme·idem_roles·idem_assigned·idem_user_id` 를 보탠다 — Handoff 와 같은 어휘.
4. **Handoff 는 유지하되 OIDC_RP 기관에는 닫힌다** (`E-IDO-121`). 내부 client(`q-sign-client`·`ido-client`)는 공개 프런트를 지날 수 없다
   (`unauthorized_client`/`invalid_client`).

## 왜 Keycloak authenticator SPI 가 아닌가

정책을 Keycloak 안(커스텀 authenticator jar)에서 강제하는 방법도 있다. 그러나 (a) Keycloak 버전에 묶인 Java 확장을 빌드·배포해야 하고,
(b) CC 시점에 자체 IdP 로 바꿀 때 버려지는 코드이며, (c) Keycloak 이 네트워크에 노출되지 않는 한 gate 의 토큰 교환 지점으로 충분히
우회 불가능하다. 다만 authenticator 는 로그인 화면 단계에서 거부할 수 있어 UX 가 낫다 — 심층 방어로 후속 PR 후보로 남긴다.

## 결과

- 기관은 issuer·client_id·client_secret 셋만 받고 표준 라이브러리로 붙는다. 사람이 Keycloak 콘솔에 손대는 단계는 0.
- 정책 판정·식별자·역할 어휘가 Handoff 와 같아 콘솔·감사·문서가 두 프로토콜을 하나로 다룬다.
- 대가: gate 가 브라우저 경로(로그인 화면·정적 자원)까지 프록시한다. 트래픽이 커지면 리버스 프록시에서 `/resources/**` 를 Keycloak 으로 직접
  라우팅해도 된다(issuer 는 그대로).
- 남은 것: 표준 RP 샘플(tenant-sample OIDC 경로)과 실 Keycloak 끝-끝, Back-Channel Logout 수신 ↔ Idem SLO 연결, `IntegrationProtocol` SPI 로
  Handoff 전략 승격, `idp-hint-mapping` 의 프로파일화, SAML_SP 설계 문서.
