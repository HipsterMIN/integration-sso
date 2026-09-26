# Idem 1.0 시험 항목표 (초안, 1.0.1 재집계)

> 대상: 시험원(GS 기능 적합성)·QA. 기능 번호는 `product-spec.md` §2. "자동" 열은 저장소의 어느 검사가 이 항목을 **실제로** 돌리는지다 — **CI 스모크** `scripts/ci/install-smoke.sh`(PR 마다 boot jar 실기동), **CI prod** 단계(1.0.1: prod 프로파일·관리 포트), **UT** 단위 테스트(`Build & Unit Test`), **IT** Testcontainers 통합 테스트(로컬 `git push` 전에만 돈다 — CI 는 `DOCKER_UNAVAILABLE=true`), **helm-lint**. `수동` 은 저장소에 자동 검사가 없는 항목이다(3차 점검에서 "E2E 헤드리스 브라우저" 표기가 실제 자동화가 아님을 확인해 1.0.1 에서 재집계). 수동 항목은 GS 시험 때 이 표 순서대로 한다.

전제: 설치 매뉴얼 §3 으로 설치된 core 에디션(kr 항목은 kr 에디션), Mock 본인확인 켬(`IDEM_SPRING_PROFILE=default` 와 함께), 관리자 첫 로그인 완료.

## A. 설치·기동

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| A-1 | 헬스 | 4개 앱 `/actuator/health` | 모두 `UP` | CI 스모크 ① |
| A-2 | 필수 비밀 없이 기동 | `IDEM_HUB_CAST_PRIVATE_KEY` 를 비우고 hub 기동 | 기동 거부(로그에 키 이름), 헬스 없음 | 수동 |
| A-3 | Discovery | `{gate}/realms/idem/.well-known/openid-configuration` | issuer = 공개 gate URL, PKCE S256 | CI 스모크 ② |
| A-4 | 에디션 | core 에서 `/api/v1/auth/nice/ci-check` | 404 (kr 에서는 존재) | CI 스모크 ⑦ |
| A-5 | 업그레이드(0.x→1.0) | 구 DB 이름·스키마로 기동 | 자동 rename + 체크섬 불일치만 1회 repair, 로그 `[Idem 개명]`; 구·신 스키마 공존 시 기동 거부 | 리허설(수동, PR-2·1.0.1 PR-B 기록) |
| A-6 | Helm 렌더 | `helm lint` · `helm template` core/kr | 오류 0, kubeconform 통과, 숫자 UID·관리 포트·레지스트리 접두 규칙 | CI helm-lint |
| A-7 | prod 프로파일 | hub 를 `SPRING_PROFILES_ACTIVE=prod` + 로컬 KMS + 관리 포트로 기동 | 앱 포트에 actuator 없음, 관리 포트 health 200·flyway 404, 관리 API 401 | CI prod 단계 |

## B. 관리자 인증·인가 (F17)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| B-1 | 무인증 관리 API | 세션 없이 `GET /api/v1/admin/agencies` | 401 `E-IDO-130` | CI 스모크 ②′ |
| B-2 | 첫 로그인 2단계 등록 | 부트스트랩 비밀번호 로그인 → `MFA_ENROLL_REQUIRED` → 코드 | 세션 발급, `mustChangePassword=true` | CI 스모크(`admin-login.sh`) |
| B-3 | 비밀번호 변경 강제 | 변경 전 다른 API 호출 | 403 `E-IDO-137` | IT(로컬, `AdminAuthIntegrationTest`) |
| B-4 | 비밀번호 정책 | 9자·사용자명 포함 | 400 `E-IDO-135` + 사유 | UT |
| B-5 | 잠금 | 틀린 비밀번호 5회 | 423 `E-IDO-133`, 15분 뒤 해제 / unlock API | IT(로컬) |
| B-6 | CSRF | `X-Requested-With` 없이 PUT | 403 | CI 스모크 |
| B-7 | 역할 | AUDITOR 로 프로파일 PUT | 403 `E-IDO-131`, 감사 `ADMIN_ACCESS_DENIED` | UT·CI 스모크 |
| B-8 | 테넌트 범위 | 다른 Tenant 의 서비스 조회 | 403 / 목록에서 제외 | 수동 (3차 점검 A7 로 실측) |
| B-9 | 로그아웃 | 로그아웃 뒤 같은 쿠키 | 401 | CI 스모크 ⑧ |
| B-10 | 마지막 SYSTEM_ADMIN 보호 | 유일 SYSTEM_ADMIN 강등 | 409 `E-IDO-136` | UT |
| B-11 | 위장 경로(1.0.1) | `/api/v1/admin;x/admins`, `/api/v1/%61dmin/admins` | 403 `E-IDO-131`, 감사 `non-canonical path` | UT(`AdminAuthFilterTest`) |
| B-12 | TOTP 재사용(1.0.1) | 같은 스텝 코드로 두 번째 로그인 | 401 `E-IDO-134`, 실패 카운터 유지 | UT(`AdminAuthServiceTest`) |

## C. 서비스 프로파일·온보딩 (F18, F1)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| C-1 | 스키마 위반 | `protocol.type` 오타로 PUT | 400 + 오류 목록, 저장 안 됨 | UT |
| C-2 | OIDC_RP 저장 → client | 유효 프로파일 PUT | 200, Keycloak 에 `idem-svc-{code}`(provisioned) | CI 스모크 ③ |
| C-3 | 프로비저닝 실패 | Keycloak 정지 후 PUT | 503 `E-IDO-122`, 프로파일 미저장 | UT |
| C-4 | secret 회전 | `POST …/oidc-client/secret` 두 번 | 각각 새 secret 1회 표시, 이전 secret 으로 토큰 요청 실패 | CI 스모크 ③ |
| C-5 | 시뮬레이션 | `authLevel=L1` vs `minAuthLevel=L2` | `allowed=false`, decisions 에 규칙 | UT |
| C-6 | INACTIVE 거부 | 상태 INACTIVE 서비스로 RP 로그인 | authorize 단계 400(Client disabled); 정책 판정도 거부(2차 방어) | UT(`OidcRpAccessService`, 2차 방어) · 수동(authorize 400) |
| C-7 | 승인 | SYSTEM_ADMIN 이 ACTIVE 저장 | 감사에 변경·사유, RP 로그인 허용 | 수동(콘솔) |
| C-8 | backchannelLogoutUri 검증(1.0.1) | 내부 주소(`http://idem-hub:8083/…`)로 PUT | 400(공개 호스트만) | UT(`ServiceProfileValidatorTest`) |

## D. 로그인 흐름 (F1~F5)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| D-1 | gate 프런트 로그인 화면 | `…/protocol/openid-connect/auth?client_id=idem-svc-X&code_challenge…` | 200 로그인 화면(Keycloak 주소 노출 없음) | CI 스모크 ④ |
| D-2 | PKCE 없음 | code_challenge 없이 | 400 | CI 스모크 ④ |
| D-3 | Idem 이 만들지 않은 client | `client_id=idem-hub` | 400 `unauthorized_client` | CI 스모크 ④·UT |
| D-4 | 본인확인 → 등록 | Mock initiate → 완료 | registry `qimUserId`, 이름 전달 | CI 스모크 ⑤ |
| D-5 | 토큰 교환 시 정책 판정 | 정책 거부 사용자로 code 교환 | 403 `access_denied`, 토큰 폐기 | UT |
| D-6 | userinfo 클레임 | 프로파일 `identity.attributes=[name]` | `idem_*` 클레임에 name 만, 마스킹 규칙대로 | UT |
| D-7 | 가명 식별자 | 두 서비스로 같은 사용자 로그인 | `sub` 가 서로 다르다(PAIRWISE_HMAC) | UT |
| D-8 | SLO | RP 로그아웃 → end_session | Idem·Keycloak 세션 종료, 백채널 URI 수신 | 수동 (UT 는 D-9) |
| D-9 | Back-Channel Logout 수신 | Keycloak → `/api/v1/oidc/backchannel-logout` | 200, hub 세션 무효; aud 부분일치·jti 재사용·iat 없음은 400 (1.0.1) | UT |
| D-10 | Handoff 티켓 | 발급 → 검증 → 재검증 | 2회째 409 `E-IDO-102`, 60초 뒤 410 | UT·IT |
| D-11 | 레이트리밋(1.0.1) | 프로파일 `limits.tps=1` 로 연속 요청 | Handoff 429 `E-AGENCY-306` · OIDC 토큰 교환 429 `temporarily_unavailable` | UT(`HandoffServiceImplTest`·`OidcRpAccessServiceTest`·`OidcFrontControllerTest`) |
| D-12 | CAST 기관 간 SSO | A 기관 토큰으로 B 진입 | 1회 소비, 재사용 거부 | UT |
| D-13 | 프록시 경로 이탈(1.0.1) | `/resources/../admin/master/console/`, `/realms/idem/../master/…` | 400 `invalid_request`, Keycloak 미도달 | UT(`OidcFrontControllerTest`) |
| D-14 | 공개 프런트 레이트리밋(1.0.1) | `/realms/idem/…` IP 당 초당 20 초과 | 429 `rate_limited` | UT(`OidcFrontRateLimitFilterTest`) |

## E. 회원 원장·전파 (F8~F13)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| E-1 | 동일인 병합 | 같은 CI 로 두 번 등록 | 같은 `qimUserId`, `isNew=false` | IT(로컬)·이관 도구 리허설 |
| E-2 | CI 비반출 | `GET …/subject?scheme=CI` | 400 `SUBJECT_SCHEME_NOT_SELECTABLE` | UT |
| E-3 | 탈퇴·파기 | 즉시 탈퇴 → 파기 스케줄 | 상태 WITHDRAWN, PII 삭제 단일 경로 | IT(로컬) |
| E-4 | 이벤트 피드 | 상태 변경 뒤 피드 조회 | 이벤트 존재(Kafka 없이) | CI 스모크 ⑥ |
| E-5 | 웹훅 서명 | 기관 콜백 수신 | HMAC 서명 검증 통과(SDK) | UT(SDK) |
| E-6 | KR 기업회원 | `member_type=BIZ` 이관 | `biz_member` 생성, 재실행 `exists` | 리허설(수동, `scripts/kr-member-import/README.md`) |

## F. 인가 (F14~F16)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| F-1 | 미할당 거부 | `policy.assignment.required=true`, 미할당 사용자 | 403 `E-IDO-120` | UT |
| F-2 | selfSignup | `selfSignup=true` | GUEST 로 발급 | UT |
| F-3 | 역할 클레임 | 할당된 역할 | `idem_roles` 클레임 | UT |
| F-4 | authz 장애 | authz 정지 | 거부 `E-IDO-117`(fail-secure) | UT |

## G. 감사·운영 (F19~F22)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| G-1 | 감사 검색 | `agencyCode`·`category=ADMIN` | 위 행위가 모두 있다(n≥6) | CI 스모크 ⑧ |
| G-2 | 감사 불변 | 감사 행 UPDATE/DELETE API | 없음(404/405) | 설계 |
| G-3 | 지표 | 관리 포트 `/actuator/prometheus` (gate·registry·authz) | `slo.*`·`personal.data.*`·`idem.kms.healthy`·`idem.outbox.*` 지표; hub 는 1.0.x 미등록(알려진 제한) | 수동 |
| G-4 | 보안 헤더 | 응답 헤더 | HSTS·CSP·X-Frame-Options 등 | UT(F-10) |
| G-5 | 백업·복구 | `pg_dump` → 복구 → A-1·B-2 재확인 | 통과 | **수동, 미실시** |
| G-6 | 오프라인 설치 | 이미지 tar 반입 | A-1~A-4 통과 | **수동, 미실시** |
| G-7 | 405/415·authz 404(1.0.1) | `DELETE /api/v1/admin/tenants/X`, `text/plain` 로그인, authz 없는 경로 | 405 `E-IDO-405`·415 `E-IDO-415`·404 `E-AUTHZ-404` | UT |

## H. 성능(참고)

k6 스모크(CI `k6 Smoke Test`)가 Discovery·헬스·로그인 화면을 짧게 친다. 부하 목표(Handoff p95 < 2000ms 등)는 `k6/` 시나리오로 GS 시험 환경에서 재측정한다(`RUNBOOK_SSO_METRICS.md`).

## 집계

| 구분 | 항목 수 | 자동 | 그중 CI 에서 도는 것 | 로컬 IT 만 | 수동·설계·리허설 |
|---|---|---|---|---|---|
| A~G (1.0 원표) | 51 | 41 | 37 | 4 (B-3, B-5, E-1, E-3) | 10 (A-2, A-5, B-8, C-7, D-8, E-6, G-2, G-3, G-5, G-6) |
| 1.0.1 추가 | 7 (A-7, B-11, B-12, C-8, D-13, D-14, G-7) | 7 | 7 | 0 | 0 |

3차 점검(2026-09-26) 이전 표는 "자동 47" 로 적혀 있었다 — E2E 헤드리스 브라우저(B-2·B-3·C-7·D-8)는 S7 PR-2 의 1회성 수동 실행이었고, A-2·B-8·E-6·G-3 도 자동 검사가 없었다. 위 수치가 실제다.

결함 밀도 산출(GS 요구)은 이 표를 GS 시험 환경에서 한 번 완주한 결과와 CI 이력(`Build & Unit Test` 전 모듈 — 실행 기준 약 1,900 테스트, `@Test` 어노테이션 기준 약 1,400)으로 낸다.
