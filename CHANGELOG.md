# Idem — 변경 이력

형식: [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/). 버전은 루트 `build.gradle.kts` 와 태그(`vX.Y.Z`)를 따른다. SDK 는 `idem-sdk-java/CHANGELOG.md`.

## [Unreleased] — 1.0.1

3차 적대적 점검(`docs/analysis/adversarial-review-1.0.md`) 후속. PR-A(보안) → PR-B(설치본) → PR-C(기능·문서) 순.

### 보안 (PR-A)
- **관리 API 인증 우회 수정 (H1)**: `AdminAuthFilter` 가 원본 URI 를 정규화(`RequestPath`)해 보호 경로를 판정하고, 경로 파라미터(`;x`)·퍼센트 인코딩(`%61`)·점 세그먼트·중복 슬래시로 위장한 요청은 세션과 무관하게 403 + 감사(`non-canonical path`). 모든 관리 엔드포인트(읽기 포함)와 Handoff 강제 취소가 `AdminPrincipal` 인자를 받아 필터를 지나쳐도 401 로 끝난다.
- **gate 프록시 경로 이탈 수정 (H2)**: `KeycloakProxy` 가 정규형 경로만 전달하고 설정된 realm 아래·`/resources/**` 만 허용 — `..`/`%2e%2e` 로 Keycloak 관리 콘솔·master realm·admin REST 에 닿을 수 없다(400 `invalid_request`). Location 재작성은 호스트·포트 비교(루프백 별칭 포함)로 내부 URL 누출을 막는다.
- **relay Flyway 제거 (H3)**: relay 가 `idem_hub` 에 `repair()` 를 돌려 hub 마이그레이션 이력을 지우던 결함. `BatchFlywayConfig`·relay `V19` 삭제, `shedlock` 은 hub `V27` 이 만든다.
- **관리자 역할·테넌트 변경 시 세션 종료 (M2)**, **TOTP 코드 1회 사용 (M3, RFC 6238 §5.2)** — 같은 스텝의 코드 재사용은 `E-IDO-134` "이미 사용한 2단계 인증 코드"(Redis `idem:admin:totp:{adminId}:{step}`), `scripts/lib/admin-login.sh` 는 다음 스텝으로 재시도.
- **Back-Channel Logout 검증 강화 (M4)**: `aud` 는 원소 정확 일치(`idem-gate-foo` 거부), `iat`·`jti` 필수, `exp`/최대 수명 검사, `jti` 재사용 거부(`idem:gate:bc-logout:jti:*`).
- **`backchannelLogoutUri` 검증 (M15)**: 공개 http(s) 호스트만 — 루프백·사설망·링크로컬·`.local/.internal`·이름만인 호스트(컨테이너 이름) 거부(SSRF).
- **gate 공개 OIDC 프런트 IP 레이트리밋 (M17)**: `/realms/**` 에 IP 당 20/s·300/min(`IDEM_GATE_FRONT_RL_*`), 초과 429 `rate_limited`, Redis 장애 시 503(fail-closed). `X-Forwarded-For` 는 `trust-forwarded-for=true` 일 때 마지막 홉만.
- hub: 지원하지 않는 메서드·Content-Type 은 500 이 아니라 405 `E-IDO-405`·415 `E-IDO-415`. authz: 없는 경로·메서드·미디어 타입도 플랫폼 본문(`E-AUTHZ-404/405/415`, 경로 반사 없음).
- 주석 정정: registry `QimWebMvcConfig`(상태 API 는 내부 키 필수), 레이트리밋 Redis 키 접두 `idem:*`, relay ShedLock 표 위치.

## [1.0.0] — 2026-09-26

첫 동결 릴리스. 2026-09-10 부터의 범용화(`docs/generalization-plan.md` S1~S9·D1~D3)를 마감한다.

### 제품
- 제품 3개 재편: Idem SSO(`idem-gate`·`idem-hub`) · Idem IM(`idem-registry`·`idem-authz`) · KR 에디션(`editions/`, 플러그인). 코어는 에디션을 모른다(가드 테스트).
- 개명 OnePass → Idem 완료: 패키지 `io.github.hipstermin.idem.*`, 설정 키 `idem.*`, 환경변수 `IDEM_*`, DB `idem`·스키마 `idem_*`, Keycloak realm `idem`·client `idem-gate`/`idem-hub`. 구 이름은 1 릴리스 호환 계층(`LegacyNames`·`LegacySchemaRename`).
- 표준 OIDC 제공(OIDC_RP): issuer 는 공개 gate URL, Keycloak 은 숨김, client 자동 프로비저닝, PKCE 필수, 토큰 교환 시 정책 판정, SLO·Back-Channel Logout.
- 서비스 프로파일(JSON 스키마 v1): 프로토콜·주체 스킴·속성 카탈로그/마스킹/매핑·정책(인증 수준·제공자·세션·점검·규칙·할당)·한도·UI. 정책 시뮬레이션.
- 연합 인가: 역할 원장·할당(사용자·그룹 ↔ 서비스)·만료·회수, 미할당 거부 또는 GUEST.
- 관리자 인증·인가: 자체 계정 + TOTP 2단계, 역할 3종, 테넌트 범위, 잠금·비밀번호 정책·CSRF, 감사 검색. 관리 콘솔 `idem-console-admin`.
- 본인확인 SPI(`IdentityVerificationProvider`) 와 플러그인(Mock · KR: NICE OACX·Any-ID). 벤더 SDK·자격증명은 저장소 밖.
- registry: 없는 경로는 500 이 아니라 404 표준 오류 본문(`E-IM-404`)으로 답한다 — KR 전용 경로를 코어에 부른 경우를 도구가 구분할 수 있다.
- fail-secure: 필수 비밀 없으면 기동 거부, 의존 장애 시 거부, 시드 기관 없음, Mock 기본 off. `CryptoProvider` SPI 로 암호 모듈 교체 가능.

### 설치본
- Docker Compose 단일 설치본(PostgreSQL·Redis·숨긴 Keycloak·앱·콘솔, Kafka 없음) — CI 가 PR 마다 실기동 스모크 8단계.
- Helm 차트 `infra/helm/idem` — 같은 계약, `global.edition` core/kr, 비밀 한 벌, pre-install 스키마 Job. 이미지 `<tag>-<edition>`.
- 0.x → 1.0 업그레이드: `scripts/upgrade/rename-db-1.0.sh` + 첫 기동 자동 스키마 rename·Flyway repair.
- KR 회원 일회성 이관 도구 `scripts/kr-member-import`.

### 문서
- 설치·입력값·온보딩·요구사항 체크리스트·관리자 인증·개명 대응표, 1.0 매뉴얼 초안 4종(`docs/manuals/`), GS 착수 문서.

### 알려진 제한
- SAML SP·SCIM 아웃바운드·동의 카탈로그·Audit Sink SPI 없음. API 경로·오류 코드(`E-IDO-1xx`, `/admin/agencies`)와 SDK 설정 키(`onepass.*`)는 1.0 에서 동결, 개명은 2.0.
- 실제 K8s 배포·오프라인 설치·백업 복구는 리허설 전(`docs/manuals/installation-manual.md` §8).

[1.0.0]: https://github.com/HipsterMIN/integration-sso/releases/tag/v1.0.0
