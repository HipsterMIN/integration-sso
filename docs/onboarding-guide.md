# 기관(서비스) 온보딩 가이드 — 프로파일 작성 → 검증 → 시험 → 승인

> S9 PR-3. **운영기관 관리자**가 새 연동기관(Service)을 Idem 에 붙이는 절차다. 기관 담당자용 설명은 `docs/sso-agency-integration-guide.md`, 관리자 인증·역할은 `docs/admin-auth.md`, 프로파일 스키마는 관리 콘솔의 "스키마" 또는 `GET /api/v1/admin/services/profile-schema`.
> 원칙: **코드 수정 없이 프로파일(설정)로 수용한다.** 프로파일로 안 되는 요구는 `docs/requirements-checklist.md` 의 "안 되는 것" 표에서 먼저 확인한다.

## 0. 한눈에

| 단계 | 누가 | 어디서 | 결과 |
|---|---|---|---|
| 1 작성 | POLICY_ADMIN(또는 SYSTEM_ADMIN) | 관리 콘솔 → 서비스 → 새 서비스 / `PUT /api/v1/admin/services/{code}/profile` | 프로파일 저장(`service.status=INACTIVE`) |
| 2 검증 | 같은 사람 | 저장 응답 + 정책 시뮬레이션 + OIDC client 상태 | 스키마 오류 0, 시뮬레이션 기대대로, client 프로비저닝됨 |
| 3 시험 | 기관 개발팀 + 운영기관 | 시험용 issuer/client 로 기관 RP(또는 `idem-tenant-sample`) 로그인 | 로그인·속성·로그아웃·거부 사례 확인 |
| 4 승인 | SYSTEM_ADMIN | `service.status=ACTIVE` 로 저장(사유 기록) | 실사용자 트래픽 허용, 감사에 남음 |

상태는 두 가지뿐이다: `INACTIVE`(작성·시험 중, 실 로그인 거부) / `ACTIVE`(운영). 별도 "승인 대기" 상태는 두지 않고 **ACTIVE 전환 권한을 SYSTEM_ADMIN 에게만** 둔다(역할은 §5).

## 1. 작성

### 1.1 기관에서 받아 둘 것 (`sso-agency-integration-guide.md` §4)

- 연동 방식: 표준 OIDC(`OIDC_RP`, 권장) / Handoff 계열(`DIRECT`·`BRIDGE`·`APACHE_GATE`·`INTERNAL_SSO`) / 레거시 WAS 는 `idem-agent`
- `OIDC_RP`: redirect URI 목록, 로그아웃 뒤 돌아갈 URI, 백채널 로그아웃 수신 URI(선택), client 인증 방식
- 필요한 사용자 속성과 기관 필드명(`identity.attributes` · `attributeMapping`), 주체 식별자 스킴(`identity.subjectScheme`, 기본 `PAIRWISE_HMAC` = 기관별 가명)
- 인증 수준(`policy.minAuthLevel` L1/L2/L3), 허용 본인확인 제공자, 세션(시간·동시 수), 점검 시간, 접근 대상(전원 / 할당된 사용자만 `policy.assignment`)
- 표시 정보(`ui.brandName`·`logoUrl`), 호출 한도(`limits.tps`·`daily`)
- 소속 Tenant(운영기관이 여러 Realm 을 두는 경우)

### 1.2 콘솔에서

관리 콘솔(`{IDEM_PUBLIC_URL_CONSOLE}`) → **서비스 → 새 서비스**. 폼은 스키마에서 만들어지므로 필수 항목(`schemaVersion`·`service`·`protocol`·`policy`)이 비면 저장되지 않는다. **`service.status` 는 `INACTIVE` 로 둔다.** 저장 사유(`X-Change-Reason`)에 접수 번호·담당자를 적는다.

### 1.3 API 로 (자동화·대량 온보딩)

```bash
# 관리자 세션 (2단계 포함) — scripts/lib/admin-login.sh 가 쿠키를 돌려준다
SID=$(IDEM_HUB_URL=https://hub.example.org scripts/lib/admin-login.sh)
curl -sS -X PUT "https://hub.example.org/api/v1/admin/services/AGENCY01/profile" \
  -H "Cookie: idemAdminSid=$SID" -H "X-Requested-With: XMLHttpRequest" \
  -H "Content-Type: application/json" -H "X-Change-Reason: 온보딩 접수 2026-0042" \
  --data-binary @agency01.profile.json
```

최소 `OIDC_RP` 프로파일 예:

```json
{
  "schemaVersion": "1",
  "service":  { "code": "AGENCY01", "name": "기관01 민원포털", "status": "INACTIVE", "tenant": "DEFAULT" },
  "protocol": { "type": "OIDC_RP",
                "oidc": { "redirectUris": ["https://portal.agency01.example/login/callback"],
                          "postLogoutRedirectUris": ["https://portal.agency01.example/"],
                          "backchannelLogoutUri": "https://portal.agency01.example/bc-logout",
                          "clientAuthMethod": "CLIENT_SECRET_BASIC" } },
  "identity": { "subjectScheme": "PAIRWISE_HMAC", "attributes": ["name", "mobile"], "attributeMapping": { "name": "userNm" } },
  "policy":   { "minAuthLevel": "L2", "allowedProviders": ["MOCK"], "session": { "idleMinutes": 30, "absoluteMinutes": 480, "concurrent": 1 },
                "maintenance": [ { "dayOfWeek": "SUN", "startTime": "02:00", "endTime": "04:00" } ] },
  "limits":   { "tps": 50, "daily": 100000 },
  "ui":       { "brandName": "기관01" }
}
```

키 이름과 허용값은 스키마가 기준이다(콘솔 "스키마" 탭). 위 예의 `session`·`limits` 값은 기관 요구대로.

## 2. 검증

| 검사 | 방법 | 통과 기준 |
|---|---|---|
| 스키마 | `PUT` 응답 | `200` + 저장된 프로파일. `400`(스키마 위반) 이면 응답의 오류 목록대로 고친다. 모르는 키는 저장은 되지만 **효과가 없다** — 오타를 의심한다 |
| Tenant 범위 | `PUT` 응답 | `403 E-IDO-131` 이면 관리자의 Tenant 범위 밖 — SYSTEM_ADMIN 이 하거나 Tenant 를 바꾼다 |
| OIDC client | 콘솔 서비스 상세 → OIDC client / `GET …/{code}/oidc-client` | `provisioned=true`, `clientId=idem-svc-{code}`, issuer 가 공개 gate URL. 저장 때 `503 E-IDO-122` 면 Keycloak 프로비저닝 실패 — 프로파일은 저장되지 않는다(fail-closed). Keycloak·`KEYCLOAK_PROVISIONER_CLIENT_SECRET` 을 확인하고 다시 저장 |
| client secret | `POST …/{code}/oidc-client/secret` (회전) | 응답에 **한 번만** 나온다. 기관에 안전한 경로로 전달하고 기록하지 않는다 |
| 정책 시뮬레이션 | 콘솔 서비스 상세 → 시뮬레이션 / `POST …/{code}/policy/simulate` body `{authLevel, providerCode, userStatus, at, assigned}` | 기대 사례가 `allowed=true`, 거부 사례(낮은 인증 수준·점검 시간·미할당·허용 외 제공자)가 `allowed=false` 이고 `decisions` 에 그 규칙이 보인다 |
| Discovery | `curl {gate}/realms/idem/.well-known/openid-configuration` | `issuer` 가 공개 gate URL. 기관 RP 에 이 주소를 준다 |

## 3. 시험

1. **기관 RP 설정**: issuer `{gate}/realms/idem`, `client_id=idem-svc-{code}`, secret, PKCE S256 필수. 표준 라이브러리(Spring Security OAuth2 Client 등)면 그대로 된다. 참고 구현은 `idem-tenant-sample`(표준 RP).
2. **시험 사용자**: 설치본 검증 단계처럼 Mock 본인확인 제공자(`IDEM_PLUGINS_MOCK_AUTH_ENABLED=true`, 시험 환경에서만)를 `policy.allowedProviders` 에 넣어 로그인한다. KR 에디션은 NICE 시험 계정.
3. **확인 항목**
   - 로그인 → RP 가 받은 `sub`(`subjectScheme` 대로: 가명 / 이메일 / 외부 sub)와 `idem_*` 클레임(요청한 속성만, 마스킹 규칙대로)
   - 거부: `INACTIVE` 상태에서는 로그인이 `403 access_denied` 로 끝나야 한다. 인증 수준 미달·미할당(`policy.assignment` 사용 시)도 같은 방식으로 거부
   - 로그아웃: RP 로그아웃 → `end_session_endpoint` → Idem 세션·Keycloak 세션 종료(SLO), 백채널 로그아웃 URI 가 있으면 수신 확인
   - 한도: `limits.tps` 를 넘기면 `429`
   - 감사: 콘솔 감사 → 서비스 코드로 검색하면 위 행위가 모두 있다(`ADMIN_*` 와 인증 이벤트)
4. 시험 뒤 Mock 제공자를 끄고(운영 환경에 켜지 않는다), 시험 중 만든 client secret 은 승인 전에 **한 번 더 회전**한다.

## 4. 승인

SYSTEM_ADMIN 이 콘솔에서 `service.status=ACTIVE` 로 바꿔 저장한다(사유: 승인 회의·접수 번호). 이 저장이 곧 승인 기록이다 — 감사 `ADMIN_*` 이벤트에 누가·언제·무엇을 바꿨는지 남고, `GET …/{code}/profile` 로 현재 프로파일을 언제든 본다. 승인 뒤 바뀌는 것:

- 기관 RP 의 실 로그인이 허용된다(`INACTIVE` 거부 해제)
- 기관에 통보: issuer·client_id·(회전한) secret·백채널 로그아웃 동작·문의 창구

프로파일을 나중에 고칠 때도 같은 `PUT` 이다. 큰 변경(주체 스킴·프로토콜 유형)은 사용자 식별자가 바뀌므로 기관과 합의한 뒤 **점검 시간(`policy.maintenance`)** 안에 한다.

## 5. 역할

| 역할 | 할 수 있는 것 |
|---|---|
| `SYSTEM_ADMIN` | 전부 — 승인(ACTIVE 전환), Tenant·관리자 관리, 모든 Tenant 의 서비스 |
| `POLICY_ADMIN` | 자기 Tenant 의 서비스 프로파일 작성·수정·시뮬레이션·client secret 회전. ACTIVE 전환은 운영 규칙으로 SYSTEM_ADMIN 에게 넘긴다(현재 코드는 역할로 막지 않는다 — 1.0 API 동결 때 강제 여부 결정) |
| `AUDITOR` | 읽기·감사 조회만 |

## 6. 흔한 문제

| 증상 | 원인 | 조치 |
|---|---|---|
| 저장 `503 E-IDO-122` | Keycloak 에 client 를 못 만듦 | Keycloak 기동·`idem-provisioner` secret·hub 로그 `[OidcRpClientProvisioner]` |
| RP 에서 `invalid_client` | client_id 가 `idem-svc-{code}` 가 아니거나 Idem 이 만든 client 가 아님(`E-IDO-123`) | 콘솔 OIDC client 상태 확인 |
| RP 에서 `access_denied` | 서비스 `INACTIVE`, 인증 수준 미달, 미할당, 점검 시간 | 시뮬레이션으로 어느 규칙인지 확인 |
| `sub` 가 `GUEST` | `subjectScheme` 이 EMAIL/PHONE/EXTERNAL_SUB 인데 그 스킴으로 등록되지 않은 사용자 | 스킴을 `PAIRWISE_HMAC` 으로 두거나 기관 측 첫 로그인 연결 안내 |
| 속성이 비어 있다 | `identity.attributes` 에 없거나 사용자에게 값이 없음(`E-IDO-114`) | 필수 속성을 줄이거나 제공자를 바꾼다 |
