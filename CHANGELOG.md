# Idem — 변경 이력

형식: [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/). 버전은 루트 `build.gradle.kts` 와 태그(`vX.Y.Z`)를 따른다. SDK 는 `idem-sdk-java/CHANGELOG.md`.

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
