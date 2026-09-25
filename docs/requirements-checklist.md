# 기관 요구사항 수용 체크리스트

> S9 PR-3. `docs/generalization-plan.md` §4 의 수용 매트릭스를 **온보딩 때 하나씩 지워 나가는 체크리스트**로 옮겼다. 기관의 요구를 왼쪽에서 찾고, 프로파일 키(`docs/onboarding-guide.md`)로 적은 뒤, "확인" 열의 방법으로 시험한다. 프로파일로 안 되는 것은 §3 에 솔직하게 적었다.
> 원칙: 설정(프로파일) → 코어 규칙/핸들러 추가(마이너 버전) → 에디션 플러그인. **코어 코드 분기는 마지막 수단.**

## 1. 프로파일로 수용되는 요구

| ☐ | 기관 요구 | 프로파일 | 확인 |
|---|---|---|---|
| ☐ | "OIDC 로 붙겠다" | `protocol.type=OIDC_RP` + `protocol.oidc.*` | Discovery → RP 로그인 |
| ☐ | "Handoff(티켓) 방식으로 붙겠다" | `protocol.type=DIRECT\|BRIDGE\|APACHE_GATE\|INTERNAL_SSO`, `protocol.endpoints`, `protocol.security` | `idem-sdk-java` 샘플 · 시뮬레이션 |
| ☐ | "레거시 WAS 라 코드 수정이 어렵다" | `AGENT` — `idem-agent` 배포 + 위 Handoff 프로파일 | `docs/idem-agent-integration-guide.md` |
| ☐ | "본인인증은 NICE 만 / 간편인증도" | `policy.allowedProviders` (제공자 코드) | 시뮬레이션 `providerCode` |
| ☐ | "L3(전자서명) 필수" / "재인증 규칙" | `policy.minAuthLevel`, `policy.rules[]` | 시뮬레이션 `authLevel` |
| ☐ | "이름은 마스킹 없이, 전화번호는 뒷자리만" | `identity.attributes[{name, masking, required}]` | RP 가 받은 클레임 |
| ☐ | "우리 필드명은 `userNm`" | `identity.attributeMapping` | 클레임 키 |
| ☐ | "CI 를 못 받는다 / 이메일로 식별" | `identity.subjectScheme=EMAIL\|PHONE\|EXTERNAL_SUB` (CI 는 registry 밖으로 안 나간다) | `sub` 값 · 미등록 사용자는 GUEST |
| ☐ | "사용자 식별자를 다른 기관과 공유하지 말 것" | `identity.subjectScheme=PAIRWISE_HMAC`(기본) | 두 기관의 `sub` 가 다르다 |
| ☐ | "세션 30분, 동시 1개" | `policy.session.idleMinutes/absoluteMinutes/concurrent` | 두 브라우저 로그인 |
| ☐ | "점검 시간에는 차단" | `policy.maintenance[]` | 시뮬레이션 `at` |
| ☐ | "콜백은 우리 도메인만, mTLS, IP 제한" | `protocol.security` · `protocol.oidc.redirectUris` | 다른 redirect_uri 로 400 |
| ☐ | "초당 50건 제한" | `limits.tps`, `limits.daily` | 넘기면 429 |
| ☐ | "우리 기관 사용자만 / 특정 그룹만" | `policy.assignment.required=true` (+ `selfSignup`) → authz 할당 | 미할당 사용자 `E-IDO-120` |
| ☐ | "역할(관리자·심사자)을 SSO 가 내려줬으면" | authz 앱 역할 → 어설션/`idem_roles` 클레임 (S8-b) | 클레임 확인 |
| ☐ | "로그아웃하면 우리 세션도 끊어 달라" | `protocol.oidc.backchannelLogoutUri` / Handoff 는 웹훅 | RP 백채널 수신 로그 |
| ☐ | "로고·기관명 표시" | `ui.brandName`, `ui.logoUrl`, `ui.locale` | 로그인 화면 |
| ☐ | "우리 기관 관리자가 직접 설정하고 싶다" | Tenant 분리 + `POLICY_ADMIN` 계정(Tenant 범위) | `docs/admin-auth.md` |
| ☐ | "기존 회원 계정과 자동으로 이어 달라" | 기관 측 첫 로그인 연결(이메일·사번 클레임, `sso-agency-integration-guide.md` §5) · KR: 일회성 회원 이관 `scripts/kr-member-import` | 연결 뒤 같은 `sub` |
| ☐ | "특수 규칙(특정 회원 유형만)" | `policy.rules[{type=CUSTOM…}]` → `PolicyRule` SPI 플러그인 | 시뮬레이션 `decisions` |
| ☐ | "감사 로그를 우리 SIEM 으로" | 감사 검색 API(콘솔) 내보내기 · Audit Sink SPI(CC P1, 예정) | — |

## 2. 설치본 쪽에서 수용되는 요구

| ☐ | 요구 | 어디서 |
|---|---|---|
| ☐ | "Keycloak 화면을 보이지 말라" | 기본 — issuer 가 gate 공개 URL, Keycloak 은 숨김(S6) |
| ☐ | "Kafka 없이" | 기본(D1). `infra.kafka.enabled=false` |
| ☐ | "K8s 로 / compose 로" | `infra/helm/idem` / `infra/docker/compose.install.yml` — 같은 계약 |
| ☐ | "관리자 2단계 필수" | 기본(S7 TOTP) |
| ☐ | "본인확인 벤더가 다르다" | `IdentityVerificationProvider` 플러그인(`docs/identity-provider-spi.md`) — KR 에디션은 NICE/Any-ID |
| ☐ | "폐쇄망" | 이미지·차트 반입 + 벤더 API 만 아웃바운드 (`sso-agency-integration-guide.md` §9.3) |

## 3. 아직 안 되는 것 (프로파일 밖) — 솔직한 목록

| 요구 | 상태 | 대안 |
|---|---|---|
| SAML SP 로 붙겠다 | 설계만(S6 PR-2 문서) | OIDC 브리지(기관 쪽 SAML→OIDC 게이트웨이) 또는 1.x 로드맵 |
| SCIM 으로 회원을 우리 쪽에 동기화 | 미구현(S9 선택 옵션이었으나 1.0 범위 밖) | 탈퇴·상태변경 웹훅(아웃박스) + 기관 측 폴링 |
| 기관이 셀프서비스로 프로파일 신청 | 콘솔은 운영기관 관리자용 | 접수 양식 → `POLICY_ADMIN` 이 입력 |
| 동의 문구·항목을 기관마다 | 카탈로그 미구현(S8 남김) | 기관 화면에서 동의 후 로그인 진입 |
| Audit Sink SPI(실시간 SIEM 전송) | CC 1.x | 감사 검색 API 주기 조회 |
| 기관 관리자 승인 워크플로(ACTIVE 전환을 역할로 강제) | 운영 규칙으로만 | 1.0 API 동결(S9 PR-4) 때 결정 |

체크리스트에 없는 요구가 오면: 프로파일 스키마 확장(마이너 버전) → 코어 규칙/핸들러 → 그래도 안 되면 에디션 플러그인. 결정은 `docs/generalization-plan.md` §4 아래에 기록한다.
