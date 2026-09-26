# Idem 1.0 관리자 매뉴얼 (초안)

> 대상: 운영기관 관리자(SYSTEM_ADMIN · POLICY_ADMIN · AUDITOR). 관리 콘솔(`idem-console-admin`, 공개 URL `{console}`)의 화면과 그 뒤의 관리 API(`/api/v1/admin/**`)를 기능별로 적는다. 인증·인가 모델은 `docs/admin-auth.md`, 기관 온보딩 절차는 `docs/onboarding-guide.md`.

## 1. 로그인·2단계·비밀번호

| 화면 | 하는 일 | 규칙 |
|---|---|---|
| 로그인 | 사용자명·비밀번호 → 2단계 코드 | 실패 5회 → 15분 잠금(`E-IDO-133`). 세션 쿠키 `idemAdminSid`, 유휴 만료 |
| 첫 로그인 | 인증 앱에 TOTP 비밀 등록(otpauth URI) → 코드 입력 → **비밀번호 변경 강제**(`E-IDO-137`) | 비밀번호 정책: 10자 이상, 대/소문자·숫자·특수문자 중 3종, 사용자명 포함 금지 |
| 비밀번호 변경 | 현재 비밀번호 + 새 비밀번호 | 감사 `ADMIN_PASSWORD_CHANGED` |
| 로그아웃 | 세션 종료 | 이후 같은 쿠키는 401 |

첫 관리자(`admin`)는 설치 때 `IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD` 로 만들어진다. 관리자가 한 명이라도 있으면 그 값은 더 쓰이지 않는다. 인증 앱을 잃은 관리자는 다른 SYSTEM_ADMIN 이 **2단계 초기화**(§6) 한다.

## 2. 서비스(연동기관) 목록·상세

- **목록**: 관리자의 Tenant 범위 안 서비스만 보인다(SYSTEM_ADMIN 은 전부). 코드·이름·상태(ACTIVE/INACTIVE)·프로토콜.
- **상세**: 프로파일 JSON(스키마 기반 폼), 변경 이력, OIDC client 상태, 정책 시뮬레이션.
- API: `GET /api/v1/admin/services/{code}/profile`, `GET …/profile-schema`.

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

## 10. 검증한 것 / 못 한 것

- ✅ §1~§8 의 흐름은 관리 콘솔 실기동 끝-끝(S7 PR-2: 첫 로그인 2단계 등록 → 강제 변경 → 온보딩 저장 → client 프로비저닝 → secret 회전 → 시뮬레이션 → 감사 → 관리자 추가 → 테넌트 → 로그아웃 → 재로그인)과 CI 설치본 스모크로 확인했다.
- ⚠️ 콘솔에는 아직 없는 화면: 할당 관리(authz `assignments`), 기관 목록 페이징(500건 한 번에), TOTP QR 이미지(텍스트 URI 만). 1.0.x 과제(`generalization-plan.md` S7 PR-2 남긴 것).
