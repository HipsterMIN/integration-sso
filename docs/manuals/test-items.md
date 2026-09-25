# Idem 1.0 시험 항목표 (초안)

> 대상: 시험원(GS 기능 적합성)·QA. 기능 번호는 `product-spec.md` §2. "자동" 열은 저장소의 어느 검사가 이 항목을 이미 돌리는지 — **CI 스모크** `scripts/ci/install-smoke.sh`(PR 마다 실기동), **E2E** 콘솔 헤드리스 브라우저(S7 PR-2), **IT** Testcontainers 통합 테스트, **UT** 단위 테스트. 수동 항목은 GS 시험 때 이 표 순서대로 한다.

전제: 설치 매뉴얼 §3 으로 설치된 core 에디션(kr 항목은 kr 에디션), Mock 본인확인 켬, 관리자 첫 로그인 완료.

## A. 설치·기동

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| A-1 | 헬스 | 4개 앱 `/actuator/health` | 모두 `UP` | CI 스모크 ① |
| A-2 | 필수 비밀 없이 기동 | `IDEM_HUB_CAST_PRIVATE_KEY` 를 비우고 hub 기동 | 기동 거부(로그에 키 이름), 헬스 없음 | UT(BootGuard) |
| A-3 | Discovery | `{gate}/realms/idem/.well-known/openid-configuration` | issuer = 공개 gate URL, PKCE S256 | CI 스모크 ② |
| A-4 | 에디션 | core 에서 `/api/v1/auth/nice/ci-check` | 404 (kr 에서는 존재) | CI 스모크 ⑦ |
| A-5 | 업그레이드(0.x→1.0) | 구 DB 이름·스키마로 기동 | 자동 rename + Flyway repair, 로그 `[Idem 개명]` | 리허설(PR-2) |
| A-6 | Helm 렌더 | `helm lint` · `helm template` core/kr | 오류 0, kubeconform 통과 | CI helm-lint |

## B. 관리자 인증·인가 (F17)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| B-1 | 무인증 관리 API | 세션 없이 `GET /api/v1/admin/agencies` | 401 `E-IDO-130` | CI 스모크 ②′ |
| B-2 | 첫 로그인 2단계 등록 | 부트스트랩 비밀번호 로그인 → `MFA_ENROLL_REQUIRED` → 코드 | 세션 발급, `mustChangePassword=true` | CI 스모크·E2E |
| B-3 | 비밀번호 변경 강제 | 변경 전 다른 API 호출 | 403 `E-IDO-137` | E2E |
| B-4 | 비밀번호 정책 | 9자·사용자명 포함 | 400 `E-IDO-135` + 사유 | UT |
| B-5 | 잠금 | 틀린 비밀번호 5회 | 423 `E-IDO-133`, 15분 뒤 해제 / unlock API | IT |
| B-6 | CSRF | `X-Requested-With` 없이 PUT | 403 | CI 스모크 |
| B-7 | 역할 | AUDITOR 로 프로파일 PUT | 403 `E-IDO-131`, 감사 `ADMIN_ACCESS_DENIED` | UT·CI 스모크 |
| B-8 | 테넌트 범위 | 다른 Tenant 의 서비스 조회 | 403 / 목록에서 제외 | UT |
| B-9 | 로그아웃 | 로그아웃 뒤 같은 쿠키 | 401 | CI 스모크 ⑧ |
| B-10 | 마지막 SYSTEM_ADMIN 보호 | 유일 SYSTEM_ADMIN 강등 | 409 `E-IDO-136` | UT |

## C. 서비스 프로파일·온보딩 (F18, F1)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| C-1 | 스키마 위반 | `protocol.type` 오타로 PUT | 400 + 오류 목록, 저장 안 됨 | UT |
| C-2 | OIDC_RP 저장 → client | 유효 프로파일 PUT | 200, Keycloak 에 `idem-svc-{code}`(provisioned) | CI 스모크 ③ |
| C-3 | 프로비저닝 실패 | Keycloak 정지 후 PUT | 503 `E-IDO-122`, 프로파일 미저장 | UT |
| C-4 | secret 회전 | `POST …/oidc-client/secret` 두 번 | 각각 새 secret 1회 표시, 이전 secret 으로 토큰 요청 실패 | CI 스모크 ③ |
| C-5 | 시뮬레이션 | `authLevel=L1` vs `minAuthLevel=L2` | `allowed=false`, decisions 에 규칙 | UT |
| C-6 | INACTIVE 거부 | 상태 INACTIVE 서비스로 RP 로그인 | 403 `access_denied` | UT(OidcRpAccessService) |
| C-7 | 승인 | SYSTEM_ADMIN 이 ACTIVE 저장 | 감사에 변경·사유, RP 로그인 허용 | E2E |

## D. 로그인 흐름 (F1~F5)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| D-1 | gate 프런트 로그인 화면 | `…/protocol/openid-connect/auth?client_id=idem-svc-X&code_challenge…` | 200 로그인 화면(Keycloak 주소 노출 없음) | CI 스모크 ④ |
| D-2 | PKCE 없음 | code_challenge 없이 | 400 | CI 스모크 ④ |
| D-3 | Idem 이 만들지 않은 client | `client_id=idem-hub` | 400/401 `invalid_client` | CI 스모크 ④·UT |
| D-4 | 본인확인 → 등록 | Mock initiate → 완료 | registry `qimUserId`, 이름 전달 | CI 스모크 ⑤ |
| D-5 | 토큰 교환 시 정책 판정 | 정책 거부 사용자로 code 교환 | 403 `access_denied`, 토큰 폐기 | UT |
| D-6 | userinfo 클레임 | 프로파일 `identity.attributes=[name]` | `idem_*` 에 name 만, 마스킹 규칙대로 | UT |
| D-7 | 가명 식별자 | 두 서비스로 같은 사용자 로그인 | `sub` 가 서로 다르다(PAIRWISE_HMAC) | UT |
| D-8 | SLO | RP 로그아웃 → end_session | Idem·Keycloak 세션 종료, 백채널 URI 수신 | E2E(e2e-slo) |
| D-9 | Back-Channel Logout 수신 | Keycloak → `/api/v1/oidc/backchannel-logout` | 200, hub 세션 무효 | UT |
| D-10 | Handoff 티켓 | 발급 → 검증 → 재검증 | 2회째 409 `E-IDO-102`, 60초 뒤 410 | UT·IT |
| D-11 | 레이트리밋 | `limits.tps=1` 로 연속 요청 | 429 | UT(F-01/F-02) |
| D-12 | CAST 기관 간 SSO | A 기관 토큰으로 B 진입 | 1회 소비, 재사용 거부 | UT |

## E. 회원 원장·전파 (F8~F13)

| ID | 항목 | 절차 | 기대 결과 | 자동 |
|---|---|---|---|---|
| E-1 | 동일인 병합 | 같은 CI 로 두 번 등록 | 같은 `qimUserId`, `isNew=false` | IT·이관 도구 리허설 |
| E-2 | CI 비반출 | `GET …/subject?scheme=CI` | 400 `SUBJECT_SCHEME_NOT_SELECTABLE` | UT |
| E-3 | 탈퇴·파기 | 즉시 탈퇴 → 파기 스케줄 | 상태 WITHDRAWN, PII 삭제 단일 경로 | IT |
| E-4 | 이벤트 피드 | 상태 변경 뒤 피드 조회 | 이벤트 존재(Kafka 없이) | CI 스모크 ⑥ |
| E-5 | 웹훅 서명 | 기관 콜백 수신 | HMAC 서명 검증 통과(SDK) | UT(SDK) |
| E-6 | KR 기업회원 | `member_type=BIZ` 이관 | `biz_member` 생성, 재실행 `exists` | 이관 도구 리허설 |

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
| G-2 | 감사 불변 | 감사 행 UPDATE/DELETE API | 없음(404) | 설계 |
| G-3 | 지표 | `/actuator/prometheus` | `idem_*` 지표, 무인증 | CI 스모크 |
| G-4 | 보안 헤더 | 응답 헤더 | HSTS·CSP·X-Frame-Options 등 | UT(F-10) |
| G-5 | 백업·복구 | `pg_dump` → 복구 → A-1·B-2 재확인 | 통과 | **수동, 미실시** |
| G-6 | 오프라인 설치 | 이미지 tar 반입 | A-1~A-4 통과 | **수동, 미실시** |

## H. 성능(참고)

k6 스모크(CI `k6 Smoke Test`)가 Discovery·헬스·로그인 화면을 짧게 친다. 부하 목표(Handoff p95 < 2000ms 등)는 `k6/` 시나리오로 GS 시험 환경에서 재측정한다(`RUNBOOK_SSO_METRICS.md`).

## 집계

| 구분 | 항목 수 | 자동(CI 스모크/E2E/IT/UT) | 수동 |
|---|---|---|---|
| A~G | 51 | 47 | 4 (A-5 업그레이드 리허설 기록, G-2 설계 확인, G-5 백업·복구, G-6 오프라인 설치) |

결함 밀도 산출(GS 요구)은 이 표를 GS 시험 환경에서 한 번 완주한 결과와 CI 이력(`Build & Unit Test` 전 모듈 약 1,900 테스트)으로 낸다.
