# Idem 1.0 관리자 매뉴얼 (초안)

> 대상: 운영기관 관리자(SYSTEM_ADMIN · POLICY_ADMIN · AUDITOR). 관리 콘솔(`idem-console-admin`, 공개 URL `{console}`)의 화면과 그 뒤의 관리 API(`/api/v1/admin/**`)를 기능별로 적는다. 인증·인가 모델은 `docs/admin-auth.md`, 기관 온보딩 절차는 `docs/onboarding-guide.md`.

## 1. 로그인·2단계·비밀번호

| 화면 | 하는 일 | 규칙 |
|---|---|---|
| 로그인 | 사용자명·비밀번호 → 2단계 코드 | 실패 5회 → 15분 잠금(`E-IDO-133`). 세션 쿠키 `idemAdminSid`, 유휴 만료 |
| 첫 로그인 | 인증 앱으로 **QR 을 찍거나**(1.1.1 — `otpauth://` URI 를 브라우저 안에서 QR 로 그린다, 서버·네트워크로 다시 나가지 않는다) base32 비밀을 직접 입력 → 코드 입력 → **비밀번호 변경 강제**(`E-IDO-137`) | 비밀번호 정책: 10자 이상, 대/소문자·숫자·특수문자 중 3종, 사용자명 포함 금지 |
| 비밀번호 변경 | 현재 비밀번호 + 새 비밀번호 | 감사 `ADMIN_PASSWORD_CHANGED` |
| 로그아웃 | 세션 종료 | 이후 같은 쿠키는 401 |

첫 관리자(`admin`)는 설치 때 `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 로 만들어진다. 관리자가 한 명이라도 있으면 그 값은 더 쓰이지 않는다. 인증 앱을 잃은 관리자는 다른 SYSTEM_ADMIN 이 **2단계 초기화**(§6) 한다.

## 2. 서비스(연동기관) 목록·상세

- **목록**: 관리자의 Tenant 범위 안 서비스만 보인다(SYSTEM_ADMIN 은 전부). 코드·이름·상태(ACTIVE/INACTIVE)·프로토콜. **1.1.1**: 서버 페이징(한 페이지 50, 최대 200)과 검색(코드·이름 부분 일치, 대소문자 무시) — 입력 300ms 뒤에 검색하고 검색어가 바뀌면 1쪽으로 돌아간다.
- **상세**: 프로파일 JSON(스키마 기반 폼), 변경 이력, OIDC client 상태, 정책 시뮬레이션, 웹훅 서명 비밀(§9), 동의 항목(§13), 할당 관리(§14, 1.1.1).
- API: `GET /api/v1/admin/services/{code}/profile`, `GET …/profile-schema`, `GET /api/v1/admin/agencies?page&size&q` → `{items, page, size, total}`(1.1.1 — 종전의 배열 응답은 봉투로 바뀌었다).

## 3. 프로파일 작성·수정

콘솔 **새 서비스 / 편집** — 폼은 스키마(`profile-schema`)에서 만들어지며 필수 항목은 `schemaVersion`·`service`·`protocol`·`policy`. 저장(`PUT …/{code}/profile`)은 다음을 한 번에 한다:

1. Tenant 범위 확인(`403 E-IDO-131`)
2. 스키마 검증(위반이면 400 + 오류 목록, 저장 안 됨)
3. 저장 + 감사(변경 사유 `X-Change-Reason` 포함)
4. `protocol.type=OIDC_RP` 면 Keycloak 에 client `idem-svc-{code}` 프로비저닝 — 같은 트랜잭션이라 실패하면(`503 E-IDO-122`) 저장도 롤백된다

상태 `service.status`: `INACTIVE`(작성·시험 — Keycloak client 비활성, authorize 단계 400) / `ACTIVE`(운영). 생략하면 스키마 기본값 **ACTIVE** 다. 승인은 SYSTEM_ADMIN 이 ACTIVE 로 저장하는 것이다(`onboarding-guide.md` §4). 프로토콜 유형·주체 스킴 변경은 사용자 식별자가 바뀌므로 점검 시간에 기관과 합의해 한다.

## 4. OIDC client

- **상태 보기**(`GET …/{code}/oidc-client`): `provisioned`, `clientId`, issuer, discovery URL, redirect URI, `enabled`(= 서비스 ACTIVE 여부). secret 은 보이지 않는다.
- **secret 회전**(`POST …/{code}/oidc-client/secret`): 새 secret 이 응답에 **한 번만** 나온다. 기관에 안전한 경로로 전달한다. 회전 즉시 이전 secret 은 무효 — 기관과 시각을 맞춘다. 감사에 남는다.

## 5. 정책 시뮬레이션

`POST …/{code}/policy/simulate` `{authLevel, providerCode, userStatus, at, assigned}` → `{allowed, decisions[]}`. 저장된 프로파일로 "이런 요청이 오면 어느 규칙이 어떻게 판정하는가"를 실제 발급 없이 본다. 모든 규칙을 끝까지 평가하므로 거부 사유가 여럿이면 모두 보인다. 온보딩 검증(§2 of onboarding-guide)과 장애 문의("왜 거부됐나") 에 쓴다.

## 6. 관리자 관리 (SYSTEM_ADMIN, 전역만)

| 작업 | API | 비고 |
|---|---|---|
| 목록·조회 | `GET /api/v1/admin/admins`, `GET …/{id}` | |
| 추가 | `POST …` `{username, displayName, role, tenantCode?}` | 임시 비밀번호가 **한 번만** 표시 → 본인에게 전달, 첫 로그인에서 변경 강제 |
| 수정 | `PUT …/{id}` `{displayName?, role?, tenantCode?, status?}` | 마지막 활성 SYSTEM_ADMIN 은 강등·비활성 불가(`E-IDO-136`) |
| 비밀번호 초기화 | `POST …/{id}/reset-password` | 임시 비밀번호 1회 표시 |
| 잠금 해제 | `POST …/{id}/unlock` | 실패 5회 잠금 해제 |
| 2단계 초기화 | `POST …/{id}/reset-mfa` | 다음 로그인에서 다시 등록 |

역할: `SYSTEM_ADMIN`(전부) · `POLICY_ADMIN`(자기 Tenant 의 서비스 프로파일·시뮬레이션·secret 회전) · `AUDITOR`(읽기·감사만). 인가 매트릭스는 `docs/admin-auth.md` §4. 모든 거부는 `403 E-IDO-131` 로 감사(`ADMIN_ACCESS_DENIED`)된다.

## 7. 테넌트

여러 Realm(예: 본부/지방청)을 나눌 때 쓴다. `GET/PUT /api/v1/admin/tenants/{code}` `{name, status}`(쓰기는 SYSTEM_ADMIN). 서비스 프로파일의 `service.tenant` 와 관리자의 `tenantCode` 가 이 코드를 가리킨다. 단일 기관 설치는 `DEFAULT` 하나로 충분하다.

## 8. 감사 조회

`GET /api/v1/admin/audit?from&to&category&action&actorId&agencyCode&outcome&correlationId&page&size(≤200)`. 콘솔 **감사** 화면에서 기간·분류(`ADMIN`·인증·핸드오프 …)·행위자·기관 코드·결과로 검색한다. Tenant 관리자는 자기 기관(`agencyCode`)만. 감사 행은 삭제·수정 API 가 없다(INSERT 전용). 보존 기간과 외부 반출은 운영 정책(`docs/sso-im-operations-manual.md`).

자주 쓰는 검색: 기관별 관리 행위(`agencyCode=…&category=ADMIN`), 로그인 실패 추적(`action=ADMIN_LOGIN_FAILED`), 특정 요청 추적(`correlationId=…`).

## 9. 운영 작업

| 작업 | 절차 |
|---|---|
| 비밀 회전 | `docs/install-inputs.md` §2 의 "회전 영향" 열대로. Keycloak client secret 은 realm 쪽과 앱 쪽을 같이. 회전 불가 키(CI 키·DI 비밀·관리자 2단계 키)는 바꾸지 않는다 |
| 기관 웹훅 서명 비밀 회전 | `IDEM_HUB_WEBHOOK_SIGNING_SECRET` 변경 → hub 재기동 → 기관에 전달 |
| 점검 시간 | 프로파일 `policy.maintenance` 로 기관별. 전체 점검은 gate 앞 프록시에서 |
| 상태 확인 | `/actuator/health`(무인증) · `/actuator/prometheus`(무인증, 지표) · 그 밖의 actuator 는 SYSTEM_ADMIN 세션 |
| 장애 "로그인이 거부된다" | 시뮬레이션(§5) → 감사(§8, `correlationId`) → hub 로그 `[PolicyEngine]` |
| 장애 "client 프로비저닝 실패" | Keycloak 헬스 → `idem-provisioner` secret → hub 로그 `[OidcRpClientProvisioner]` → 프로파일 재저장 |
| 업그레이드·백업 | `installation-manual.md` §5·§6 |
- **웹훅 서명 비밀 회전 (1.1.1)**: 기관 상세 → "웹훅 서명 비밀" 카드 → **서명 비밀 회전** → 한 번만 보이는 새 비밀을 기관 수신기 담당자에게 전달한다. 서버에는 KMS 봉인값과 지문(해시 앞 8자)만 남는다. 구 비밀은 즉시 무효이므로 기관이 두 비밀을 잠시 함께 받도록 준비한 뒤 회전한다. 엔드포인트가 없는 기관(카드에 "웹훅 엔드포인트가 없습니다")은 기관 등록·수정 API 의 `webhookEndpoint` 로 먼저 둔다. 비밀이 없는 기관에는 웹훅이 나가지 않고 감사 `WEBHOOK_DISPATCH_FAILED(NO_SIGNING_SECRET)` 만 남는다.

## 10. 검증한 것 / 못 한 것

- ✅ §1~§8 의 흐름은 관리 콘솔 실기동 끝-끝(S7 PR-2: 첫 로그인 2단계 등록 → 강제 변경 → 온보딩 저장 → client 프로비저닝 → secret 회전 → 시뮬레이션 → 감사 → 관리자 추가 → 테넌트 → 로그아웃 → 재로그인)과 CI 설치본 스모크로 확인했다.
- ✅ 감사 이상 징후(§12)는 통합 테스트(`AuditAnomalyIntegrationTest`: 적재 → 점수 → 플래그 → 목록·통계 → 검토 → 감사)로 확인했다. 운영 기준선(3개월)은 설치 뒤 쌓인다.
- ⚠️ AI 운영 보조(§11)는 단위 테스트(스키마 검증·마스킹·가드)와 콘솔 빌드로만 확인했다 — 실제 LLM 컨테이너와의 끝-끝은 `--profile ai` 설치본에서 운영자가 한 번 돌려 본다(응답 품질은 모델에 달렸다).
- ⚠️ 콘솔에는 아직 없는 화면: 할당 관리(authz `assignments`)와 규칙 할당(1.1, authz `assignment-rules` — 지금은 내부 API 를 `X-Internal-Api-Key` 로 직접 호출: `POST /api/v1/internal/authz/assignment-rules {agencyCode, ruleType: GROUP|ATTRIBUTE, matchKey, matchValues[], expiresDays}`, `DELETE …/{id}`), SCIM 아웃바운드(1.1, 프로파일 `protocol.scim` 블록은 "JSON (전체 스키마)" 탭으로 편집; 상태·전체 동기화는 `GET/POST /api/v1/admin/services/{code}/scim/{status|sync}`), 기관 목록 페이징(500건 한 번에), TOTP QR 이미지(텍스트 URI 만). `post-1.0-plan.md` §2.3.

## 11. AI 운영 보조 (1.1, 선택)

설치본이 `IDEM_HUB_AI_ENABLED=true`(compose `--profile ai` 의 Ollama 컨테이너 또는 기관의 온프레미스 LLM, `docs/install-inputs.md`)로 켜져 있을 때만 보인다. 꺼져 있으면 버튼·메뉴가 없다(`GET /api/v1/admin/ai/status`). **인증 경로와 무관하다** — 관리 API 뒤에서만 돌고, 아무것도 저장하지 않는다.

| 어디서 | 무엇 | 보내는 것 | 역할 |
|---|---|---|---|
| 기관 상세 → 프로파일 폼 위 "AI 초안" | 자연어 요청 → 프로파일 JSON 초안. 서버가 스키마로 검증해 위반 목록을 함께 준다. "JSON 탭에 넣기" 뒤 **저장은 관리자가 검토해 누른다**(기존 기관이면 코드 고정, 테넌트 관리자는 자기 테넌트로 고정) | 요청 문장 + (선택) 현재 프로파일 + 스키마 | SYSTEM·POLICY |
| 감사 → "AI 요약" | 현재 필터의 기록을 집계(분류·결과·행위·기관별 건수, 실패 상위)해 요약 | **집계와 표본 40행만** — IP·metadata 는 빼고 행위자 ID 는 앞 두 글자만 | 전 역할(테넌트 관리자는 기관 코드 필수) |
| "AI 운영" 메뉴 → "지금 상태 요약" | 운영 스냅샷(health 구성요소, 웹훅·SCIM 아웃박스·SLO 재시도 큐의 상태별 건수·5분 초과 PENDING·24h FAILED·마지막 오류, 감사 FAILURE 1h/24h, 감사 유실·WAL 지표)을 읽고 정상/주의/장애 의심 판정과 확인 순서 | 건수·상태·지표만 | 전역 관리자 |

- 모든 호출은 감사 `ADMIN / AI_PROFILE_DRAFT · AI_AUDIT_SUMMARY · AI_INCIDENT_SUMMARY` 로 남는다 — 내용은 남기지 않고 모델·크기·위반 수만.
- LLM 출력은 참고다. 수치는 스냅샷·집계 표에서 직접 확인한다(화면에 같이 나온다). 사설망 밖 LLM 은 `IDEM_HUB_AI_ALLOW_PUBLIC_ENDPOINT=true` 를 명시해야 켜진다.
- 오류 코드: `E-IDO-140`(꺼짐, 404) · `E-IDO-141`(LLM 호출 실패, 502) · `E-IDO-142`(응답 해석 실패) · `E-IDO-143`(요청 오류).

## 12. 감사 이상 징후 — 관찰 모드 (1.1)

메뉴 "이상 징후". 감사 기록이 저장된 뒤 비동기로 규칙 5개(관리자 로그인 실패 버스트 · 새 출처 IP · 업무 외 시간 쓰기 · 기관 실패 버스트 · Handoff 티켓 재검증 반복)를 평가해 **플래그만** 남긴다 — 경보·차단은 없고 인증 경로와 무관하다. 규칙·임계·근거는 `docs/audit-anomaly.md`.

1. **검토**: 미검토 필터로 보고, 행을 펼쳐 근거(건수·창·기준선)·행위자·IP·correlationId 를 확인한 뒤 **정탐 / 오탐 / 모름** 을 누른다(메모 선택). 전 역할이 할 수 있다 — 감사자의 일이다. 검토는 감사 `ANOMALY_REVIEWED` 로 남는다.
2. **기준선 표**: 아래 표가 최근 90일 규칙별 플래그·정탐·오탐·정밀도와 "판단"(승격 후보 / 규칙 조정 / 관찰 계속)을 보인다. 설치 뒤 약 3개월, 규칙당 검토 20건 이상 모이면 그 판단으로 경보 승격을 정한다.
3. 테넌트 관리자는 기관 코드를 지정해야 하고 자기 기관의 플래그만 본다·검토한다.
4. 플래그가 하루에 수십 건이면 임계를 올린다(`IDEM_HUB_AUDIT_ANOMALY_*`). 야간 점검이 잦은 설치본은 업무 외 시간 규칙의 오탐이 많다 — 정밀도대로 조정한다.

## 13. 동의 카탈로그 (1.1)

서비스 이용에 필요한 동의 항목(이용약관·개인정보 처리방침·마케팅 수신 등)을 **버전**으로 관리하고, 코어 로그인 프런트(Handoff 브라우저 진입)가 발급 전에 미동의 항목을 묻는다. 항목은 registry 가 가진다 — 프로파일에는 켜고 끄는 스위치만 있다.

1. **플랫폼 공통 항목** — 메뉴 "동의 항목"(전역 관리자만). 유형(`TERMS_OF_SERVICE` 처럼 영대문자·숫자·`_`), 버전 태그, 제목(로그인 화면 문구), 전문 URL(http(s), "전문 보기" 링크), 필수 여부, 시행 시각(비우면 즉시)을 넣고 **발행**한다. 같은 유형의 이전 버전은 자동으로 종료(SUPERSEDED)되고, 사용자는 다음 로그인에서 새 버전에 다시 동의한다. **종료**는 더 묻지 않게 한다(동의 기록은 남는다).
2. **서비스 전용 항목** — 기관 상세의 "동의 항목 — 이 서비스 전용" 카드(테넌트 관리자는 자기 테넌트 기관만). 입력은 공통과 같다. 로그인 화면에는 공통 + 전용이 함께 보인다.
3. **켜기** — 프로파일 폼 "로그인 화면 동의 단계"(`consent.enabled`). "플랫폼 공통 항목 포함"(`consent.includePlatform`, 기본 켜짐)을 끄면 그 서비스는 전용 항목만 묻는다. Handoff 유형(DIRECT·BRIDGE·APACHE_GATE·INTERNAL_SSO)에만 적용되고, OIDC_RP 서비스는 기관 RP 화면이 동의를 받는다.
4. **동작** — 필수 미동의 항목이 있을 때만 동의 화면이 뜨고, 그때 선택 항목도 같이 보인다. 필수를 빼고 제출하면 다시 묻고, "동의하지 않음" 은 기관 콜백으로 `error=E-IDO-125` 를 보낸다. registry 가 응답하지 않으면 발급하지 않는다(오류 화면 `E-IDO-106`).
5. **감사** — 발행·종료는 `ADMIN/CONSENT_VERSION_PUBLISHED·CONSENT_VERSION_RETIRED`(범위·유형·버전), 사용자의 동의·거부는 `MEMBER/CONSENT_AGREED·CONSENT_DECLINED`(버전 ID 목록, 출처 IP). 동의 기록 자체는 registry `consent_record`(INSERT 전용, 경로 `LOGIN_FRONT:<서비스코드>`).
6. **권한** — 목록은 전 역할, 발행·종료는 SYSTEM_ADMIN·POLICY_ADMIN. 플랫폼 공통은 전역 관리자만(테넌트 관리자는 403).

## 14. 할당 관리 — 사용자·역할 (1.1.1)

서비스에 누가 들어갈 수 있는지(할당)와 그 안에서 어떤 역할(그룹)을 갖는지를 기관 상세의 "할당 관리" 카드에서 본다. 상태는 `idem-authz` 가 가진다 — hub 는 테넌트 범위를 검사하고 감사를 남기며 authz 내부 API 를 대신 부른다. authz 를 끈 설치본(`IDEM_HUB_AUTHZ_ENABLED=false`, SSO 단독)에서는 카드가 안내만 보인다(`503 E-IDO-116`).

1. **할당 목록** — 사용자 ID(registry `qimUserId`)·상태·출처(CONSOLE·SCIM·RULE …)·부여 시각/부여자·만료. 한 페이지 50, "이전/다음". API `GET /api/v1/admin/services/{code}/assignments?page&size` → `{items, page, size, total, hasNext}`.
2. **직접 할당** — 사용자 ID(영숫자·`_ . : -` 1~100자), 만료(비우면 무기한, 과거는 거부), 사유(선택). 이미 할당된 사용자는 authz 가 멱등 처리(만료·출처·사유 갱신)한다. API `POST …/assignments {qimUserId, expiresAt?, reason?}` → 201, 출처 `CONSOLE`, 부여자 = 관리자 사용자명. 감사 `ADMIN/ASSIGNMENT_GRANTED`.
3. **해제** — 행의 "해제"(사유 입력, 선택). API `DELETE …/assignments/{qimUserId}?reason` → 204. 감사 `ASSIGNMENT_REVOKED`. 해제는 기관 웹훅 `ASSIGNMENT_CHANGED{UNASSIGNED}` 로 전파된다(1.1 B-14).
4. **역할(그룹) 카탈로그** — 서비스 안의 역할 목록과 "역할 만들기"(코드·이름·설명). API `GET …/roles`, `POST …/roles {roleCode, name, description?}` → 201, 같은 코드가 있으면 `409 E-IDO-128`. 감사 `ROLE_CREATED`. 부여 가능(`assignable`)이 꺼진 역할은 부여할 수 없다(authz `E-AUTHZ-409-ROLE` → `409 E-IDO-128`).
5. **사용자 역할** — 행의 "역할" 을 누르면 그 사용자의 역할(상태·부여·만료·출처)과 부여 폼(카탈로그에서 고른다, 만료·사유 선택). API `GET …/assignments/{qimUserId}/roles`, `POST …/assignments/{qimUserId}/roles {roleCode, expiresAt?, reason?}` → 201, `DELETE …/assignments/{qimUserId}/roles/{roleCode}?reason` → 204. 감사 `ROLE_GRANTED`·`ROLE_REVOKED`. 부여된 역할은 authz `effective-roles`(SDK 의 `roles` 클레임)로 내려간다.
6. **오류** — 없는 서비스 `404 E-AGENCY-307`, 범위 밖 기관 `403`, 입력 형식 `400 E-IDO-129`, authz 가 모르는 사용자·역할 `404 E-IDO-127`, 중복·부여 불가 `409 E-IDO-128`, authz 장애 `503 E-IDO-117`. authz 의 원래 코드(`E-AUTHZ-*`)는 hub 로그에 남는다.
7. **권한** — 목록·역할·사용자 역할 GET 은 전 역할(AUDITOR 포함), 할당·해제·역할 생성·부여·회수는 SYSTEM_ADMIN·POLICY_ADMIN. 테넌트 관리자는 자기 테넌트 기관만.
