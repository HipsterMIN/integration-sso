# Idem 시험 항목표 (초안 — 1.1.1 집계: 1.0 원표 51 + 1.0.1 추가 7 + 1.1 추가 14 + 1.1.1 추가 3 = 75항목)

> 대상: 시험원(GS 기능 적합성)·QA. 기능 번호는 `product-spec.md` §2. "자동" 열은 저장소의 어느 검사가 이 항목을 **실제로** 돌리는지다 — **CI 스모크** `scripts/ci/install-smoke.sh`(PR 마다 boot jar 실기동), **CI prod** 단계(1.0.1: prod 프로파일·관리 포트), **UT** 단위 테스트(`Build & Unit Test`), **IT** Testcontainers 통합 테스트(로컬 `git push` 전에만 돈다 — CI 는 `DOCKER_UNAVAILABLE=true`), **helm-lint**. `수동` 은 저장소에 자동 검사가 없는 항목이다(3차 점검에서 "E2E 헤드리스 브라우저" 표기가 실제 자동화가 아님을 확인해 1.0.1 에서 재집계). 수동 항목은 GS 시험 때 이 표 순서대로 한다.

전제: 설치 매뉴얼 §3 으로 설치된 core 에디션(kr 항목은 kr 에디션), Mock 본인확인 켬(`IDEM_SPRING_PROFILE=default` 와 함께), 관리자 첫 로그인 완료.

## A. 설치·기동

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| A-1 | 헬스 | 4개 앱 `/actuator/health` | 모두 `UP` | CI 스모크 ① | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| A-2 | 필수 비밀 없이 기동 | `IDEM_HUB_CAST_PRIVATE_KEY` 를 비우고 hub 기동 | 기동 거부(로그에 키 이름), 헬스 없음 | 수동 | 미실행 — G1-1(사용자 환경) |
| A-3 | Discovery | `{gate}/realms/idem/.well-known/openid-configuration` | issuer = 공개 gate URL, PKCE S256 | CI 스모크 ② | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| A-4 | 에디션 | core 에서 `/api/v1/auth/nice/ci-check` | 404 (kr 에서는 존재) | CI 스모크 ⑦ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| A-5 | 업그레이드(0.x→1.0) | 구 DB 이름·스키마로 기동 | 자동 rename + 체크섬 불일치만 1회 repair, 로그 `[Idem 개명]`; 구·신 스키마 공존 시 기동 거부 | 리허설(수동, PR-2·1.0.1 PR-B 기록) | 미실행 — G1-1(사용자 환경) |
| A-6 | Helm 렌더 | `helm lint` · `helm template` core/kr | 오류 0, kubeconform 통과, 숫자 UID·관리 포트·레지스트리 접두 규칙 | CI helm-lint | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| A-7 | prod 프로파일 | hub 를 `SPRING_PROFILES_ACTIVE=prod` + 로컬 KMS + 관리 포트로 기동 | 앱 포트에 actuator 없음, 관리 포트 health 200·flyway 404, 관리 API 401 | CI prod 단계 | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| A-8 | K8s 실배포(1.1) | `scripts/k8s/rehearsal.sh` — kind 에 차트 설치(TLS Ingress·비밀 Secret·스키마 Job) → A-1·A-3·B·C·D 스모크 ①~⑧ → prod 전환 `helm upgrade` → `helm rollback` → `helm uninstall` | 리비전 1 Pod 전부 Ready, Service 에 관리 포트 없음, Ingress 호스트 3(TLS), 스모크 통과; 리비전 2 는 prod 프로파일·MOCK 없음; 롤백(리비전 3)은 MOCK 복귀; 제거 뒤 Pod 0 | CI `k8s-rehearsal`(차트·스크립트 변경 PR·main) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |

## B. 관리자 인증·인가 (F17)

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| B-1 | 무인증 관리 API | 세션 없이 `GET /api/v1/admin/agencies` | 401 `E-IDO-130` | CI 스모크 ②′ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-2 | 첫 로그인 2단계 등록 | 부트스트랩 비밀번호 로그인 → `MFA_ENROLL_REQUIRED` → 코드 | 세션 발급, `mustChangePassword=true`. 1.1.1: 콘솔 등록 화면에 `otpauth://` QR(브라우저 안 생성, `otpauth://totp/` 아니면 그리지 않음) | CI 스모크(`admin-login.sh`) · 콘솔 vitest(`qr.test.ts`) — QR 을 인증 앱으로 찍는 끝-끝은 수동 | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| B-3 | 비밀번호 변경 강제 | 변경 전 다른 API 호출 | 403 `E-IDO-137` | IT(로컬, `AdminAuthIntegrationTest`) | 로컬 IT — G1-1 `git push` 훅 기록으로 확정 |
| B-4 | 비밀번호 정책 | 9자·사용자명 포함 | 400 `E-IDO-135` + 사유 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-5 | 잠금 | 틀린 비밀번호 5회 | 423 `E-IDO-133`, 15분 뒤 해제 / unlock API | IT(로컬) | 로컬 IT — G1-1 `git push` 훅 기록으로 확정 |
| B-6 | CSRF | `X-Requested-With` 없이 PUT | 403 | CI 스모크 | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-7 | 역할 | AUDITOR 로 프로파일 PUT | 403 `E-IDO-131`, 감사 `ADMIN_ACCESS_DENIED` | UT·CI 스모크 | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-8 | 테넌트 범위 | 다른 Tenant 의 서비스 조회 | 403 / 목록에서 제외 | 수동 (3차 점검 A7 로 실측) | 미실행 — G1-1(사용자 환경) |
| B-9 | 로그아웃 | 로그아웃 뒤 같은 쿠키 | 401 | CI 스모크 ⑧ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-10 | 마지막 SYSTEM_ADMIN 보호 | 유일 SYSTEM_ADMIN 강등 | 409 `E-IDO-136` | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-11 | 위장 경로(1.0.1) | `/api/v1/admin;x/admins`, `/api/v1/%61dmin/admins` | 403 `E-IDO-131`, 감사 `non-canonical path` | UT(`AdminAuthFilterTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-12 | TOTP 재사용(1.0.1) | 같은 스텝 코드로 두 번째 로그인 | 401 `E-IDO-134`, 실패 카운터 유지 | UT(`AdminAuthServiceTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-13 | 할당 정책 단일 해석(1.1) | 프로파일 `policy.assignment.required=true` + `rules[ASSIGNMENT].params.required=false` 로 미할당 로그인 | 발급 거부 `E-IDO-120`(규칙 파라미터로 풀리지 않음), prod 에서 `IDEM_HUB_AUTHZ_ENABLED=false` 는 기동 거부 | UT(`AssignmentPolicyResolverTest`·`PolicyRulesTest`·`FailSecureBootGuardTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-14 | 할당 변경 전파(1.1) | authz `DELETE /assignments` → hub 폴링 | 기관 웹훅 `ASSIGNMENT_CHANGED{change:UNASSIGNED, agencySubjectId}` 적재, `qimUserId` 없음, 감사 `ASSIGNMENT_CHANGED`, 재폴링에 멱등 | UT(`AuthzServiceTest`·`AuthzEventsControllerTest`·`AuthzEventPollerTest`·`AuthzEventConsumerTest`·`WebhookDispatcherServiceTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| B-15 | 웹훅 서명 비밀 회전(1.1.1) | 기관 등록(`webhookEndpoint`) → `POST /api/v1/admin/agencies/{code}/webhook/rotate-secret` → `GET …/webhook` → 이벤트 발송 | 비밀은 응답에 1회, DB 는 KMS 봉인값 + SHA-256(지문), 상태 응답에 원문 없음, 발송 `X-Webhook-Signature` 가 새 비밀로 검증, 엔드포인트 없는 기관 404 `E-IDO-126`, 비밀 없는 기관은 발송 FAILED(`NO_SIGNING_SECRET`), 1.0.x 원문 행은 첫 기동에 봉인, 감사 `WEBHOOK_SECRET_ROTATED` | IT(`WebhookSecretIntegrationTest`) · UT(`WebhookSigningSecretsTest`·`AgencyAdminServiceTest`·`WebhookDispatchOutboxRelayTest`) · CI 스모크 ⑧b | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT ✅ 2026-10-09 (`WebhookSecretIntegrationTest`, 샌드박스 PG·Redis) |
| B-16 | 할당 관리(1.1.1) | 기관 상세 "할당 관리": `POST /api/v1/admin/services/{code}/assignments` → 목록 → `POST …/roles` → `POST …/assignments/{u}/roles` → `GET …/roles` → `DELETE …/roles/{r}` → `DELETE …/assignments/{u}` | 201(ACTIVE, source=CONSOLE)·목록 봉투 `{items,page,size,total,hasNext}`·역할 201(재실행 `409 E-IDO-128`)·부여 201·회수 204·해제 204, 없는 서비스 `404 E-AGENCY-307`, 범위 밖 기관 403, AUDITOR 쓰기 403, authz 꺼짐 `503 E-IDO-116`, 감사 `ADMIN/ASSIGNMENT_GRANTED·ASSIGNMENT_REVOKED·ROLE_CREATED·ROLE_GRANTED·ROLE_REVOKED` | IT(`AssignmentAdminIntegrationTest`, authz WireMock) · UT(`AssignmentAdminServiceTest`·`AssignmentAdminControllerTest`·`QAuthzClientAdminTest`·`AdminAuthorizationTest`) · 콘솔 vitest(`assignments.test.ts`) · CI 스모크 ⑧c(실제 authz) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT ✅ 2026-10-09 (`AssignmentAdminIntegrationTest`) |
| B-17 | 기관 목록 페이징·검색(1.1.1) | `GET /api/v1/admin/agencies?page=0&size=1`, `?q=<코드 일부 소문자>&size=10`, `size=999` | 봉투 `{items,page,size,total}`, size 는 1~200 으로 잘림, `q` 는 코드·이름 부분 일치(대소문자 무시), 테넌트 관리자는 자기 테넌트만(DB 에서 거른 뒤 셈) | UT(`AgencyAdminServiceTest.listAgencies_pagingAndSearch`) · IT(`AssignmentAdminIntegrationTest`) · CI 스모크 ⑧c | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT ✅ 2026-10-09 (`AssignmentAdminIntegrationTest`) |

## C. 서비스 프로파일·온보딩 (F18, F1)

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| C-1 | 스키마 위반 | `protocol.type` 오타로 PUT | 400 + 오류 목록, 저장 안 됨 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| C-2 | OIDC_RP 저장 → client | 유효 프로파일 PUT | 200, Keycloak 에 `idem-svc-{code}`(provisioned) | CI 스모크 ③ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| C-3 | 프로비저닝 실패 | Keycloak 정지 후 PUT | 503 `E-IDO-122`, 프로파일 미저장 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| C-4 | secret 회전 | `POST …/oidc-client/secret` 두 번 | 각각 새 secret 1회 표시, 이전 secret 으로 토큰 요청 실패 | CI 스모크 ③ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| C-5 | 시뮬레이션 | `authLevel=L1` vs `minAuthLevel=L2` | `allowed=false`, decisions 에 규칙 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| C-6 | INACTIVE 거부 | 상태 INACTIVE 서비스로 RP 로그인 | authorize 단계 400(Client disabled); 정책 판정도 거부(2차 방어) | UT(`OidcRpAccessService`, 2차 방어) · 수동(authorize 400) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| C-7 | 승인 | SYSTEM_ADMIN 이 ACTIVE 저장 | 감사에 변경·사유, RP 로그인 허용 | 수동(콘솔) | 미실행 — G1-1(사용자 환경) |
| C-8 | backchannelLogoutUri 검증(1.0.1) | 내부 주소(`http://idem-hub:8083/…`)로 PUT | 400(공개 호스트만) | UT(`ServiceProfileValidatorTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |

## D. 로그인 흐름 (F1~F5)

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| D-1 | gate 프런트 로그인 화면 | `…/protocol/openid-connect/auth?client_id=idem-svc-X&code_challenge…` | 200 로그인 화면(Keycloak 주소 노출 없음) | CI 스모크 ④ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-2 | PKCE 없음 | code_challenge 없이 | 400 | CI 스모크 ④ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-3 | Idem 이 만들지 않은 client | `client_id=idem-hub` | 400 `unauthorized_client` | CI 스모크 ④·UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-4 | 본인확인 → 등록 | Mock initiate → 완료 | registry `qimUserId`, 이름 전달 | CI 스모크 ⑤ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-5 | 토큰 교환 시 정책 판정 | 정책 거부 사용자로 code 교환 | 403 `access_denied`, 토큰 폐기 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-6 | userinfo 클레임 | 프로파일 `identity.attributes=[name]` | `idem_*` 클레임에 name 만, 마스킹 규칙대로 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-7 | 가명 식별자 | 두 서비스로 같은 사용자 로그인 | `sub` 가 서로 다르다(PAIRWISE_HMAC) | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-8 | SLO | RP 로그아웃 → end_session | Idem·Keycloak 세션 종료, 백채널 URI 수신 | 수동 (UT 는 D-9) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| D-9 | Back-Channel Logout 수신 | Keycloak → `/api/v1/oidc/backchannel-logout` | 200, hub 세션 무효; aud 부분일치·jti 재사용·iat 없음은 400 (1.0.1) | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-10 | Handoff 티켓 | 발급 → 검증 → 재검증 | 2회째 409 `E-IDO-102`, 60초 뒤 410 | UT·IT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT 부분 — G1-1 기록 |
| D-11 | 레이트리밋(1.0.1) | 프로파일 `limits.tps=1` 로 연속 요청 | Handoff 429 `E-AGENCY-306` · OIDC 토큰 교환 429 `temporarily_unavailable` | UT(`HandoffServiceImplTest`·`OidcRpAccessServiceTest`·`OidcFrontControllerTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-12 | CAST 기관 간 SSO | A 기관 토큰으로 B 진입 | 1회 소비, 재사용 거부 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-13 | 프록시 경로 이탈(1.0.1) | `/resources/../admin/master/console/`, `/realms/idem/../master/…` | 400 `invalid_request`, Keycloak 미도달 | UT(`OidcFrontControllerTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-14 | 공개 프런트 레이트리밋(1.0.1) | `/realms/idem/…` IP 당 초당 20 초과 | 429 `rate_limited` | UT(`OidcFrontRateLimitFilterTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-16 | Handoff 브라우저 진입(1.1 코어 로그인 프런트) | 브라우저로 `GET {hub}/api/v1/handoff/login?service=X&callback=…&state=s` → 로그인 → 기관 콜백 | 콜백에 `ticketId`·`state` 복귀, `feSessionId` 쿠키 발급, 기관 verify APPROVED, 재검증 409; 화이트리스트 밖 콜백은 403 오류 화면(리다이렉트 없음); OIDC_RP 서비스는 400 `E-IDO-121`; 정책 거부는 콜백 `?error=E-IDO-120` | IT(`HandoffLoginIntegrationTest`, Mock 제공자) · UT(`HandoffLoginControllerTest`) · CI 스모크 ⑦b | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT 부분 — G1-1 기록 |
| D-17 | 발급 API 기관 바인딩(1.1) | 기관 A 키로 본문 `agencyCode=B` 발급 | 403 `E-AGENCY-302` | UT(`HandoffControllerTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| D-18 | 동의 카탈로그(1.1) | 프로파일 `consent.enabled`, 카탈로그에 필수(공통)·선택(전용) 버전 → 브라우저 진입 → 로그인 → 동의 화면 → 필수 빼고 제출 → 전부 제출 → 콜백; 같은 브라우저로 다시 진입 → 거부 | 발급 대신 동의 화면 200(CSP `form-action 'self'`, 전문 링크는 http(s) 만), 필수 누락은 재표시·기록 없음, 제출 시 registry 기록(`LOGIN_FRONT:<서비스>`)·`callback?ticketId&state`, 같은 제출 재요청 410, 거부는 `callback?error=E-IDO-125`, 감사 `MEMBER/CONSENT_AGREED·CONSENT_DECLINED`; 관리 API 발행 201·범위 밖 종료 404 `E-IM-207`·테넌트 403, 플랫폼 공통은 전역 관리자만 | IT(`HandoffLoginIntegrationTest.consentFlow`) · UT(`HandoffLoginControllerTest`·`ConsentAdminControllerTest`·`ConsentRegistryClientTest`·`SecurityHeadersFilterTest`·`ServiceProfileValidatorTest`·`AdminAuthorizationTest`) · registry UT(`ConsentControllerTest`·`ConsentServiceImplTest`) · 콘솔 UT(`consent.test.ts`·`profile.test.ts`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT ✅ 2026-10-08 (`HandoffLoginIntegrationTest.consentFlow`) |
| D-15 | SLO IdP 재시도(1.1) | Keycloak 이 세션 종료를 거부하는 상태에서 SLO | gate 502 `X-Idp-Logout-Outcome: FAILED`, hub `slo_idp_logout_retry` 적재 → 백오프 재시도 → DONE, 초과 시 FAILED + 감사 `SLO_IDP_LOGOUT_FAILED` | UT(`InternalSessionControllerTest`·`SloServiceImplIdpTest`·`SloIdpLogoutRetryRelayTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |

## E. 회원 원장·전파 (F8~F13)

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| E-1 | 동일인 병합 | 같은 CI 로 두 번 등록 | 같은 `qimUserId`, `isNew=false` | IT(로컬)·이관 도구 리허설 | 로컬 IT — G1-1 `git push` 훅 기록으로 확정 · 수동 부분 미실행 — G1-1(사용자 환경) |
| E-2 | CI 비반출 | `GET …/subject?scheme=CI` | 400 `SUBJECT_SCHEME_NOT_SELECTABLE` | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| E-3 | 탈퇴·파기 | 즉시 탈퇴 → 파기 스케줄 | 상태 WITHDRAWN, PII 삭제 단일 경로 | IT(로컬) | 로컬 IT — G1-1 `git push` 훅 기록으로 확정 |
| E-4 | 이벤트 피드 | 상태 변경 뒤 피드 조회 | 이벤트 존재(Kafka 없이) | CI 스모크 ⑥ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| E-5 | 웹훅 서명 | 기관 콜백 수신 | HMAC 서명 검증 통과(SDK) | UT(SDK) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| E-6 | KR 기업회원 | `member_type=BIZ` 이관 | `biz_member` 생성, 재실행 `exists` | 리허설(수동, `scripts/kr-member-import/README.md`) | 미실행 — G1-1(사용자 환경) |

## F. 인가 (F14~F16)

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| F-1 | 미할당 거부 | `policy.assignment.required=true`, 미할당 사용자 | 403 `E-IDO-120` | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| F-2 | selfSignup | `selfSignup=true` | GUEST 로 발급 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| F-3 | 역할 클레임 | 할당된 역할 | `idem_roles` 클레임 | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| F-4 | authz 장애 | authz 정지 | 거부 `E-IDO-117`(fail-secure) | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| F-5 | 규칙 할당 실체화(1.1) | `POST /assignment-rules{ATTRIBUTE authLevel in [L2,L3]}` 뒤 미할당 사용자가 L2 로 로그인 | 발급 통과, authz `access.assignmentSource=RULE`, `AUTHZ_ASSIGNED` 피드 → 기관 웹훅 `ASSIGNMENT_CHANGED{ASSIGNED}`, 감사 `ASSIGN`; L1 로그인은 여전히 `E-IDO-120` | UT(`AssignmentRuleServiceTest`·`AssignmentRuleControllerTest`·`QAuthzClientTest`) + 로컬 실기동 확인(2026-09-28) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| F-7 | SCIM 아웃바운드(1.1) | 프로파일 `protocol.scim` 켜고 토큰 주입 → 역할 부여 | 기관 SCIM 서버에 `GET /Users?filter` → `POST /Users`(externalId=agencySubjectId) → `POST /Groups` → `PATCH members add`, 아웃박스 DISPATCHED, 감사 `SCIM_DISPATCHED`; 기관 5xx 는 백오프 재시도(PENDING·next_retry_at), 같은 이벤트 재적재는 멱등; 프로파일에 토큰을 넣으려 하면 400 | IT(`ScimOutboundIntegrationTest`) · UT(`ScimClientTest`·`ScimOutboxServiceTest`·`ScimOutboxRelayTest`·`ScimUserLifecycleHandlerTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT — G1-1 `git push` 훅 기록으로 확정 |
| F-8 | SCIM 되돌이 방지·정책(1.1) | 기관이 authz 인바운드 SCIM 으로 부여한 역할; `onUnassign=NONE` | 아웃바운드 적재 없음; 해제 시 사용자 변경 없음 | UT(`ScimOutboxServiceTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| F-6 | 규칙 비활성화 회수(1.1) | `DELETE /assignment-rules/{id}` | 응답 `{revoked:n}`, 그 규칙의 ACTIVE 할당 모두 REVOKED + `UNASSIGNED` 전파, 감사 `RULE_DISABLED`·`UNASSIGN`; 직접 할당(CONSOLE 등)은 영향 없음 | UT + 로컬 실기동 확인 | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |

## G. 감사·운영 (F19~F22)

| ID | 항목 | 절차 | 기대 결과 | 자동 | 실행 결과 (1.1.1) |
|---|---|---|---|---|---|
| G-1 | 감사 검색 | `agencyCode`·`category=ADMIN` | 위 행위가 모두 있다(n≥6) | CI 스모크 ⑧ | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| G-2 | 감사 불변 | 감사 행 UPDATE/DELETE API | 없음(404/405) | 설계 | 미실행 — G1-1(사용자 환경) |
| G-3 | 지표 | 관리 포트 `/actuator/prometheus` (hub·gate·registry·authz) | 200 + `jvm_*`·`slo.*`·`personal.data.*`·`idem.kms.healthy`·`idem.outbox.*`·`audit.anomaly.*` 지표. **정정(1.1.1)**: 1.0.x 는 네 앱 모두 Prometheus 레지스트리가 없어 404 였다(종전 "gate·registry·authz 는 됨" 은 잘못) — G1-4 에서 네 앱에 추가 | CI 스모크 ⑧a(네 앱 200 + jvm 지표) · CI prod 단계(hub 관리 포트 200) · K8s 리허설(hub 관리 포트) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| G-4 | 보안 헤더 | 응답 헤더 | HSTS·CSP·X-Frame-Options 등 | UT(F-10) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| G-5 | 백업·복구 | `scripts/ops/backup.sh`(pg_dump -Fc + sha256 + meta) → 앱 정지 → `scripts/ops/restore.sh`(DB 재생성·pg_restore·ANALYZE) → 기동 → A-1·B-2 재확인 | 복구 DB 의 스키마별 표 수·주요 표 행 수가 백업 시점과 같다, A-1·B-2 통과 | 자동 부분: CI 스모크 "백업·복구" 단계(백업 → 새 DB `idem_restore_check` 에 복구 → `VERIFY_SOURCE_DB` 대조) · 운영 DB 복구(앱 정지→재기동)는 **수동, 리허설 미실시** | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| G-6 | 오프라인 설치 | `scripts/release/make-offline-bundle.sh`(이미지 tar·소스 tar·Helm·SHA256SUMS·MANIFEST) 반입 → `scripts/release/load-offline-bundle.sh` → `up -d`(`--build` 없이) | 체크섬 일치, MANIFEST 의 이미지 전부 적재, A-1~A-4 통과 | 자동 부분: CI `offline-bundle-check`(번들 생성 → 이미지 삭제 → 반입 스크립트 복원 → 대조, 스크립트 변경 PR) · 폐쇄망 반입·설치는 **수동, 미실시** | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| G-7 | 405/415·authz 404(1.0.1) | `DELETE /api/v1/admin/tenants/X`, `text/plain` 로그인, authz 없는 경로 | 405 `E-IDO-405`·415 `E-IDO-415`·404 `E-AUTHZ-404` | UT | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| G-8 | 감사 WAL 폴백(1.1) | PostgreSQL 을 멈춘 채 로그인 시도 → 재기동 | 시도 중 hub 는 예외 없이 응답, `IDEM_HUB_AUDIT_WAL_DIR/audit-wal.jsonl` 에 줄 추가(`audit.wal.appended.total`); DB 복구 후 60초 내 `audit_log` 에 원래 `occurred_at` 으로 재삽입되고 WAL 파일 삭제(`audit.wal.replayed.total`); 중복 없음 | UT(`AuditWalTest`·`AuditLogPublisherWalTest`·`FailSecureBootGuardTest`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) |
| G-9 | AI 운영 보조(1.1, 선택) | `IDEM_HUB_AI_ENABLED=true` + LLM 컨테이너. 콘솔 AI 초안 → JSON 탭 → 저장, 감사 "AI 요약", "AI 운영" 요약; 꺼진 설치본 | 초안은 스키마 위반을 함께 보이고 저장 전에는 반영 없음; 요약 요청에 IP·metadata 가 가지 않고 행위자는 `ab***`; 테넌트 관리자는 기관 코드 없이 403, 장애 요약은 전역만; 꺼지면 `/api/v1/admin/ai/*` 404 `E-IDO-140`, 공개 호스트는 명시 없이 안 켜짐; 감사 `AI_*` 남음 | UT(`AiAssistantServiceTest`·`LlmClientTest`·`AuditDigestTest`·`AiAdminControllerTest`) · 콘솔 UT(`ai.test.ts`) · LLM 끝-끝은 수동 | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 수동 부분 미실행 — G1-1(사용자 환경) |
| G-10 | 감사 이상 탐지(1.1, 관찰 모드) | 같은 관리자 계정 로그인 실패 5건+, 같은 기관 티켓 재검증 실패 3건 → 점수기 1배치 → 콘솔 "이상 징후" → 검토 | 플래그 규칙당 1건(축당 창 안 하나), 심각도·근거(`count`·`threshold`·`windowMinutes`), `audit_log` 변경 없음, 목록·통계(정밀도 null → 검토 뒤 1.0), 검토는 감사 `ANOMALY_REVIEWED`, 잘못된 verdict 400, 테넌트 관리자는 기관 코드 없이 403 | IT(`AuditAnomalyIntegrationTest`) · UT(`AnomalyRulesTest`·`AnomalyAdminControllerTest`·`AdminAuthorizationTest`) · 콘솔 UT(`anomaly.test.ts`) | ✅ [CI #37926324522](https://github.com/HipsterMIN/integration-sso/actions/runs/37926324522) (2026-10-09, PR #264 1.1.1 버전 커밋 — 전체 CI) · 로컬 IT — G1-1 `git push` 훅 기록으로 확정 |

## H. 성능(참고)

k6 스모크(CI `k6 Smoke Test`)가 Discovery·헬스·로그인 화면을 짧게 친다. 부하 목표(Handoff p95 < 2000ms 등)는 `k6/` 시나리오로 GS 시험 환경에서 재측정한다(`RUNBOOK_SSO_METRICS.md`).

## 집계

| 구분 | 항목 수 | 자동 | 그중 CI 에서 도는 것 | 로컬 IT 만 | 수동·설계·리허설 |
|---|---|---|---|---|---|
| A~G (1.0 원표) | 51 | 42 | 38 | 4 (B-3, B-5, E-1, E-3) | 9 (A-2, A-5, B-8, C-7, D-8, E-6, G-2, G-5, G-6) — G-3 은 1.1.1 에서 자동 |
| 1.0.1 추가 | 7 (A-7, B-11, B-12, C-8, D-13, D-14, G-7) | 7 | 7 | 0 | 0 |
| 1.1 추가 | 14 (A-8, B-13, B-14, D-15, D-16, D-17, D-18, F-5, F-6, F-7, F-8, G-8, G-9, G-10) | 14 | 11 (A-8, B-13, B-14, D-15, D-16, D-17, F-5, F-6, F-8, G-8, G-9) | 3 (D-18, F-7, G-10) | 0 (G-9 의 LLM 끝-끝은 수동) |
| 1.1.1 추가 | 3 (B-15, B-16, B-17) | 3 | 3 (B-15, B-16, B-17) | 0 | 0 (B-2 의 QR 끝-끝은 B-2 수동 부분) |
| **합계** | **75** | **66** | **59** | **7** | **9** |

1.1.1 G2 재집계(2026-10-09): 1.1 추가 행은 9 가 아니라 **14** 였다 — B-13·B-14·D-15·F-5·F-6 이 "(1.1)" 로 표에는 있었으나 집계에 빠져 있었다. 종전 "68항목"·"70항목" 표기는 표 행 수와 달랐고, 이제 집계는 표에서 세어 낸 값이다(`grep -c "^| [A-G]-" test-items.md` = 75).

**실행 결과 열(1.1.1, G2 산출물)**: 자동 항목은 1.1.1 버전 커밋의 전체 CI(PR #264 run 37926324522 — Build & Unit Test·k6 스모크(설치본 스모크 ①~⑧c·백업/복구·prod 단계)·Helm lint·docker build 9종·K8s 리허설·오프라인 설치본 검증)와 이 세션에서 돌린 로컬 IT 로 채웠다. 수동 항목과 나머지 로컬 IT 는 G1-1(사용자 환경·`git push` 훅)에서 채운다 — GS 시험 때는 이 열을 시험원 실행 결과로 바꾼다.

3차 점검(2026-09-26) 이전 표는 "자동 47" 로 적혀 있었다 — E2E 헤드리스 브라우저(B-2·B-3·C-7·D-8)는 S7 PR-2 의 1회성 수동 실행이었고, A-2·B-8·E-6·G-3 도 자동 검사가 없었다. 위 수치가 실제다.

결함 밀도 산출(GS 요구)은 이 표를 GS 시험 환경에서 한 번 완주한 결과와 CI 이력(`Build & Unit Test` 전 모듈 — 실행 기준 약 1,900 테스트, `@Test` 어노테이션 기준 약 1,400)으로 낸다.
