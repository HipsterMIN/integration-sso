# Idem 1.0 제품 설명서 (초안)

> 대상: 시험원(GS)·구매 담당. **제품이 무엇을 하고, 무엇으로 이루어졌고, 어디서 도는가.** 기능별 시험은 `test-items.md`, 설치는 `installation-manual.md`.

## 1. 제품 개요

**Idem(아이뎀)** 은 여러 기관(테넌트)의 회원을 동일인 기준으로 하나로 묶고, 기관 서비스에는 표준 OIDC 또는 1회용 암호화 티켓(Handoff)으로 로그인 결과를 전달하며, 역할·할당 기반의 연합 인가를 제공하는 **회원통합·연합인가 플랫폼**이다. 어느 운영기관이든 설치할 수 있고, 연동기관의 요구는 코드 수정 없이 **서비스 프로파일(설정)** 로, 그 밖은 플러그인으로 수용한다.

| 항목 | 값 |
|---|---|
| 제품명 / 버전 | Idem 1.0.1 (태그 `v1.0.1`, 2026-09-26 — 1.0.0 동결 뒤 3차 적대적 점검 후속) |
| 라이선스 | Apache-2.0 (저장소 공개) |
| 에디션 | **core** — Idem SSO + Idem IM · **kr** — 코어 + KR 에디션(SMES 회원 개념·NICE/Any-ID 본인확인 플러그인·회원 포털) |
| 제품 구성 | 제품 1 Idem SSO(`idem-gate`·`idem-hub`) · 제품 2 Idem IM(`idem-registry`·`idem-authz`) · 관리 콘솔 · 기관 연동 도구(SDK·에이전트·참조 앱) |

## 2. 기능 목록

### 2.1 인증·SSO (Idem SSO)

| # | 기능 | 설명 | 위치 |
|---|---|---|---|
| F1 | 표준 OIDC 제공(OIDC_RP) | 기관이 표준 Relying Party 로 붙는다. issuer 는 공개 gate URL, Authorization Code + PKCE S256 필수, Keycloak 은 숨김 | gate 프런트 + hub 정책 판정 |
| F2 | Handoff(1회용 암호화 티켓) | AES-256-GCM + HMAC 서명, 60초·1회 소비, 기관 SDK 로 검증 | hub |
| F3 | 본인확인 브로커(SPI) | `IdentityVerificationProvider` 플러그인 — Mock(설치 검증), KR: NICE OACX·Any-ID | hub + plugins |
| F4 | 인증 수준 L1/L2/L3 | 서비스 프로파일 `policy.minAuthLevel`, 재인증 규칙 | hub PolicyEngine |
| F5 | 세션·단일 로그아웃 | 세션 정책(유휴·절대·동시), SLO(Keycloak 세션 종료), OIDC Back-Channel Logout 송·수신 | gate·hub |
| F6 | 기관 간 SSO(CAST) | Ed25519 서명 토큰으로 A기관 → B기관 재로그인 없이 이동, 원자 소비 | hub |
| F7 | 레거시 WAS 연동 | `idem-agent`(-javaagent, JDK 8+) · `idem-sdk-java`(Java 8+, 의존성 0) | 도구 |

### 2.2 통합 회원(Idem IM)

| # | 기능 | 설명 |
|---|---|---|
| F8 | 동일인 식별·골든 레코드 | 주체 스킴(CI/EMAIL/PHONE/EXTERNAL_SUB/PLATFORM_ID)별 identifierHash 로 registerOrGet, 상태(ACTIVE/SUSPENDED/WITHDRAWAL_SCHEDULED/WITHDRAWN) |
| F9 | 기관별 가명 식별자 | `PAIRWISE_HMAC`(기본) — 기관 간 결합 불가. CI 는 registry 밖으로 나가지 않는다 |
| F10 | 속성 카탈로그·마스킹·매핑 | 프로파일 `identity.attributes/attributeMapping`, 필수 속성 없으면 거부(E-IDO-114) |
| F11 | 탈퇴·파기·보존 | 즉시/예약/기관요청/관리자 탈퇴, 개인정보 파기 스케줄, 상태 이력 |
| F12 | 상태변경 전파 | 아웃박스 → 이벤트 피드(DB 폴링, Kafka 없음) / 웹훅(서명) / Kafka(선택) |
| F13 | KR: 기업회원·CI 조회·회원전환 | `biz_member`, `/api/v1/internal/member/lookup-by-ci`, 회원 포털 |

### 2.3 연합 인가(Idem AuthZ)

| # | 기능 | 설명 |
|---|---|---|
| F14 | 역할 원장·할당 | 사용자·그룹 ↔ 서비스 할당, 앱 역할, 만료·회수 |
| F15 | 정책 판정 | 프로파일 `policy.assignment`(required/selfSignup) — 미할당 거부(E-IDO-120) 또는 GUEST |
| F16 | 클레임 전달 | 역할을 OIDC 클레임(`idem_*`)·Handoff 어설션으로 |

### 2.4 관리·운영

| # | 기능 | 설명 |
|---|---|---|
| F17 | 관리자 인증·인가 | 자체 계정 + TOTP 2단계, 역할 3종(SYSTEM_ADMIN/POLICY_ADMIN/AUDITOR), 테넌트 범위, 잠금(5회/15분), 비밀번호 정책, CSRF 헤더 |
| F18 | 관리 콘솔 | 서비스 목록·프로파일 폼(스키마 기반)·OIDC client·secret 회전·정책 시뮬레이션·테넌트·관리자·감사 검색 |
| F19 | 감사 | 감사 행(분류·행위·행위자·기관·결과·상관관계 ID) — API 로는 추가·검색만(수정·삭제 API 없음; 발행 표시 컬럼만 내부 갱신), 검색 API |
| F20 | 보안 기본값 | 내부 API 키·HMAC 서명·레이트리밋(IP·기관, TPS·일)·보안 헤더·fail-secure(필수 키 없으면 기동 거부, 의존 장애 시 거부) |
| F21 | 암호 | `CryptoProvider` SPI(교체 가능), AES-256-GCM·HMAC-SHA256·Ed25519·SHA-256, 키 버전·로테이션, Vault Transit(선택) |
| F22 | 관측 | `/actuator/health`(liveness·readiness), Prometheus 지표(`slo.*`·`personal.data.*`·`idem.kms.healthy`·`idem.outbox.*`; 1.0.1 부터 관리 포트. hub 의 `/actuator/prometheus` 는 1.0.x 미등록 — 알려진 제한), OTel 추적(선택) |

## 3. 구성과 인터페이스

| 구성요소 | 포트 | 역할 | 외부 인터페이스 |
|---|---|---|---|
| idem-gate | 8081 | 인증 관문·OIDC 파사드 | `/realms/idem/**`(OIDC), `/api/v1/oidc/**`, `/api/v1/auth/**` |
| idem-hub | 8083 | 오케스트레이션·정책·관리 API | `/api/v1/admin/**`(관리, 세션+2단계), `/api/v1/handoff/**`, `/api/v1/broker/**`, 웹훅 아웃바운드 |
| idem-registry | 8082 | 회원 원장 | `/api/v1/internal/**`(내부 API 키), 이벤트 피드 |
| idem-authz | 8086 | 인가 원장 | `/api/v1/internal/**` |
| idem-console-admin | 3001 | 관리 콘솔(정적 + `/api/v1/admin` 프록시) | 브라우저 |
| Keycloak 24 | 내부 | 토큰 발급·세션(숨김) | gate 뒤에서만 |
| PostgreSQL 16 · Redis 7 | — | 데이터·세션 | — |
| idem-relay(선택) | 8090 | 아웃박스 → Kafka | Kafka 배포만 |

## 4. 지원 플랫폼·요구사항

| 항목 | 지원 |
|---|---|
| 서버 OS | Linux x86-64 (컨테이너) |
| 배포 | Docker Compose(≥ 2.17) 단일 설치본 · Kubernetes Helm 차트(매니페스트는 K8s 1.29 스키마로 검증, Helm 3) · 오프라인(이미지 tar) |
| 런타임(이미지 안) | Java 21(Temurin), Spring Boot 3.5, Keycloak 24.0 |
| 데이터 | PostgreSQL 16(엔진 1종), Redis 7. Kafka 선택 |
| 브라우저(관리 콘솔·로그인 화면) | 최신 Chrome·Edge·Firefox·Safari (ES2020) |
| 기관 측 | 표준 OIDC 라이브러리 / Java 8+ SDK / JDK 8+ WAS 에이전트(Tomcat 8~10·Jetty·WildFly·Undertow·JEUS 검증 테스트베드) |
| 규모 | 기관(서비스) 수 제한 없음(프로파일 단위). 한도는 프로파일 `limits.tps/daily` 로 기관별(1.0.1 부터 적용; 없으면 설치본 기본 200 tps·1,000,000/일) |

## 5. 보안 기능 요약 (GS 보안성 항목 대응)

전송(TLS 는 프록시/Ingress 종료, 내부 서명·API 키), 저장(CI·주체 키 AES-256-GCM 암호화, 이름·전화 마스킹, DI HMAC), 인증(관리자 2단계, 잠금, 정책), 인가(역할·테넌트 범위·CSRF), 감사(INSERT 전용·검색), 안전 기본값(필수 비밀 없으면 기동 거부, 의존 장애 시 거부, Mock 기본 off, 시드 기관 없음), 암호 모듈 교체 가능(`CryptoProvider` — KCMVP 는 CC 단계).

## 6. 제한·알려진 것

- SAML SP·SCIM **아웃바운드**(Idem → 기관 프로비저닝)·동의 카탈로그·Audit Sink SPI 는 1.0 에 없다(`docs/requirements-checklist.md` §3). SCIM 2.0 Groups **인바운드**(`/scim/v2/Groups`, authz, 내부 키)는 있다.
- 오류 코드·API 경로는 1.0 에서 **동결**한다(`E-IDO-1xx`, `/api/v1/admin/agencies` 등 구 이름 포함). 개명은 2.0 에서.
- 실제 K8s 클러스터 배포·오프라인 설치·백업 복구 리허설은 아직(설치 매뉴얼 §8).
