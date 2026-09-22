# Idem — CC(국내용)·GS 인증 갭 분석 (초안)

> **상태**: 초안 v0.1 · 작성 2026-09-08 · 기준 코드 `shipster` (PR #221, 커밋 875dbaa) · 작성 방식: 저장소 소스·설정·마이그레이션을 직접 확인해 기술 (추정은 ❔ 로 표시)
>
> **판정 요약**: 지금 상태로는 CC 평가 **착수 자체가 불가**하다. 관리자 식별·인증이 존재하지 않고, 감사 기록이 보호·검토되지 않으며, 검증필 암호모듈이 없고, TLS 가 어느 서비스에도 설정돼 있지 않다. GS 는 "설치 가능한 배포본 + 매뉴얼" 을 갖추면 먼저 도전할 수 있다.
>
> ⚠️ 본 문서의 요구사항 항목명은 CC Part 2/3 컴포넌트 명칭(FAU_GEN.1 등)으로 적었다. **국가용 보안요구사항 "통합인증(SSO)" 유형의 현행 판에서 실제로 선택된 컴포넌트·항목 번호는 IT보안인증사무국 배포 문서로 대조해 확정해야 한다** (§8 확인 목록).

---

## 0. 한눈에 보는 결론

| 구분 | 현재 | 결정적 갭 |
|---|---|---|
| 관리자 식별·인증 (FIA/FMT) | ❌ 없음 | 콘솔은 순수 React SPA, 서버 측 로그인·역할 검증 없음. `/api/v1/admin/**` 는 `X-Admin-Id` 헤더(기본값 `SYSTEM`)만 읽는 **무인증 API** |
| 보안감사 (FAU) | 🟡 생성만 | 무결성 보호 없음, INSERT-ONLY 미강제, 저장 실패 시 **조용히 유실**, 보존·보관 작업 없음, 검토 UI/API 없음, **관리자 행위 미감사** |
| 암호지원 (FCS) | ❌ JCE/BouncyCastle 만 | KCMVP 검증필 모듈 없음. 국산 알고리즘은 AnyID KMS 용 ARIA(BouncyCastle) 뿐 |
| 안전한 채널 (FTP) | ❌ 서비스 TLS 없음 | `server.ssl.*` 전무, DB 연결 `sslmode=disable` 고정. TLS 는 Nginx 에 위임 (TOE 밖) |
| TSF 보호 (FPT) | 🟡 부팅 가드만 | 무결성 자체시험·안전한 갱신 없음(Helm 이미지 `latest`), 시간원 보증 없음 |
| 보증 산출물 (ADV/AGD/ALC/ATE) | ❌ 대부분 없음 | 관리자 지침서·준비절차·기능명세·시험계획·보안시험 증적·LICENSE·제품 CHANGELOG 없음 |
| GS (ISO/IEC 25023) | 🟡 | 설치본·매뉴얼 미완, 결함 밀도 미측정. 통합 테스트가 2026-09-08 에야 처음 돌아 운영 결함 4건 발견 |

규모 감각: 개발 보완 **3~5개월(2~3명)**, 산출물 작성 **2~3개월(1~2명, 컨설팅 병행)**, 평가 **6~12개월**. GS 는 **2~4개월** 로 병행 가능.

---

## 1. 인증 제도 전제

### 1.1 국내 CC 인증
- 인증기관: 국가정보원 IT보안인증사무국. 평가기관: KOSYAS·TTA·KTL·KTC·KSEL·KOIST 등 (착수 시 견적 비교).
- 평가 기준: **국가용 보안요구사항 — 통합인증(SSO) 제품 유형**. 국가·공공기관 SSO 도입 시 사실상 필수. ❔ 현행 판 번호·발행일, SSO 유형 존치 여부, 대체 제도(보안기능 확인서) 적용 가능성은 사무국에 확인.
- 암호 기능은 **KCMVP 검증필 암호모듈** 사용이 전제다. 자체 구현·JCE·BouncyCastle 은 인정되지 않는다.
- 인증서는 **법인(신청기관·개발기관)** 명의. 개발환경·형상관리·배포 절차가 평가 대상(ALC).
- 오픈소스 공개와 병행 가능하나, 평가받은 **배포판(버전·해시)** 을 별도 고정해야 한다.

### 1.2 GS 인증
- 기관: TTA 소프트웨어시험인증연구소, KTL. 기준: ISO/IEC 25023(품질 측정)·25051(RUSP) 기반 시험 — 8개 품질 특성 + 제품 설명서·사용자 문서.
- 1등급/2등급. 효과: 조달청 종합쇼핑몰 등록, 공공기관 우선구매 대상.
- 필요물: 설치 가능한 제품(설치 매뉴얼 기준으로 시험원이 직접 설치), 사용자 매뉴얼, 제품 설명서, 시험용 계정·데이터.

### 1.3 순서 제안
GS 를 먼저 받는다. 배포본·매뉴얼이 고정되고 그 산출물이 CC 의 AGD 와 겹치며, 조달 등록으로 매출 경로가 먼저 열린다. CC 는 §6 로드맵의 Phase 1~2 를 끝낸 뒤 평가 계약한다.

---

## 2. TOE 범위 (제안)

| 구성요소 | 포함 | 근거·비고 |
|---|---|---|
| idem-hub (8083) | **TOE** | 정책·Handoff·FE 세션·SSO(CAST)·감사 발행의 중심 |
| idem-gate (8081) | **TOE** | 인증 결과 SoR, 잠금, Keycloak 콜백 |
| idem-registry (8082) | **TOE** | 회원·CI/DI 암호 저장·탈퇴 |
| idem-authz (8086) | **TOE** | 연합 역할, RLS 적용 감사 테이블 |
| idem-console (SPA) | **TOE (관리 인터페이스)** | 단, 서버 측 관리자 인증·권한이 새로 필요 (§3 FIA) |
| idem-relay (8090) | **TOE** | Outbox 릴레이·웹훅 서명 |
| idem-sdk-java / idem-agent | ❔ | 기관 측 배포물. 경계 밖(운영환경) 으로 두되 인터페이스(HMAC 서명·CAST 공개키)는 TSFI 로 기술 |
| plugins/idem-plugin-nice-oacx, AnyID 브로커 | **TOE 밖** | 벤더 SDK 재배포 불가(`docs/open-source-readiness.md` B2). SPI(`docs/identity-provider-spi.md`) 를 경계로 두고 Mock 플러그인으로 시험 |
| Nginx(TLS 종단), Keycloak, PostgreSQL/MariaDB, Redis, Kafka, Vault | **운영환경** | ST 의 운영환경 보안목적(OE)으로 기술. 단, TOE↔운영환경 채널은 TOE 가 TLS 로 보호해야 함(§3 FTP) |

플랫폼(코드 기준): JDK 21, Spring Boot 3.5.9, PostgreSQL 16, MariaDB 11.4, Redis 7.2, Kafka 7.6.1(CP), Keycloak 24, Vault 1.17, Node 20/Yarn 1.22 (`build.gradle.kts`, `infra/docker/docker-compose.yml`). 평가용으로는 **지원 플랫폼을 한 조합으로 고정**한다.

---

## 3. 보안기능 요구사항 갭

판정: ✅ 충족 · 🟡 부분(보완 필요) · ❌ 미충족 · ❔ 확인 필요. "현재" 열의 경로는 저장소 기준.

### 3.1 보안감사 (FAU)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FAU_GEN.1 감사데이터 생성 | 기동/종료, 인증 성공·실패, 관리 행위, 정책 변경 등 감사대상 사건 기록 + 일시·주체·결과 | `ido.audit_log`(V7), `qsign.auth_audit_log`(V2), `authz.authz_grant_audit`, `broker_audit_log`, `gateway_inbound_audit` 로 분산. 인증·Handoff·웹훅·탈퇴·SLO 이벤트 30여 종(`AuthAuditService`, `AuditLogPublisher` 호출부). **관리자 행위(기관 등록·키 회전·활성화) 미기록** (`AgencyAdminService` 에 감사 호출 없음). TOE 기동·종료 미기록 | 🟡 | 감사대상 사건 목록을 ST 에 정의하고 누락 사건(관리 행위, 기동·종료, 설정 변경, 감사 기능 on/off) 추가. 저장소를 하나로 통합하거나 통합 조회 계층 마련 |
| FAU_GEN.2 사용자 신원 연계 | 사건과 사용자 신원 연계 | actor_type/actor_id 있음. 관리자 행위는 `X-Admin-Id` 헤더값(검증 없음) | 🟡 | 관리자 I&A(§3.4) 도입 후 인증된 신원으로 대체 |
| FAU_SAR.1/.2/.3 감사 검토 | 권한 있는 관리자가 감사 기록을 읽고, 검색·정렬 가능 | 조회 API·UI **없음** (Java 소스에 `audit_log` SELECT 없음). Grafana/Loki 는 앱 로그용 | ❌ | 콘솔에 감사 검토 화면 + `/api/v1/admin/audit` 검색 API(기간·주체·사건·결과 필터) |
| FAU_STG.1 감사 저장소 보호 | 인가되지 않은 삭제·변조 방지, 변조 탐지 | INSERT ONLY 는 **주석뿐**(V7:168). `authz_grant_audit` 만 RLS 적용. 무결성 필드 없음(`audit_key` 는 중복 방지용 SHA-256) | ❌ | (1) DB 역할 분리: 앱 계정에 audit 테이블 UPDATE/DELETE 권한 회수 + RLS (2) 레코드 해시체인(`prev_hash`, HMAC 키는 KMS) 또는 주기적 서명 (3) 변조 검증 배치 + 알림 |
| FAU_STG.3/.4 저장 실패·고갈 대응 | 감사 저장 실패 시 경고, 임계치 도달 시 경고·감사 가능한 행위만 허용 등 | `AuditLogPublisher.publish` 가 `@Async` 로 **모든 예외를 삼킴**(`:98-102`), 폴백 없음, 알림 없음. 2026-09-08 확인: VARCHAR(36) 초과 값도 조용히 유실 | ❌ | 저장 실패 시 (1) 로컬 파일 폴백 큐 + 재전송 (2) 실패 카운터 메트릭·알림 (3) 설정 가능한 "감사 불가 시 서비스 거부" 모드. 용량 임계치 모니터링 |
| FAU_STG 보존·보관 | 보존 기간(관례 2년)·아카이브 | "2년" 주석만. 보관·분할·삭제 배치 없음 | ❌ | 파티셔닝 + 월 단위 아카이브(서명된 export) + 보존 만료 삭제 배치, 관리자 지침서에 절차 기술 |
| FPT_STM.1 신뢰 시간 | 신뢰할 수 있는 타임스탬프 | DB `NOW()`/`Instant.now()`. NTP 요구·검증 없음. HMAC 재전송 창(60초)이 시계에 의존 | 🟡 | 운영환경 가정(NTP)으로 ST 에 명시 + 기동 시 DB/앱 시각 편차 자체점검 |

### 3.2 암호지원 (FCS)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FCS_COP.1 암호 연산 | 검증필 암호모듈의 알고리즘으로 암·복호화·해시·MAC·서명 | 전부 JCE/BouncyCastle (부록 A). 국산 알고리즘은 ARIA(BouncyCastle, 리플렉션 로딩) 뿐 | ❌ | **KCMVP 검증필 모듈 도입**이 최대 개발 항목. `CryptoProvider` SPI 를 신설해 AES-GCM/HMAC/SHA-256/PBKDF2/서명을 모듈 경유로 교체. 모듈 후보·라이선스·JDK21 지원 확인(❔) |
| FCS_CKM.1/.2/.4 키 생성·분배·파기 | 검증필 모듈 난수로 키 생성, 안전한 분배, 사용 후 파기(제로화) | 키 생성 `SecureRandom`(`HandoffKeyRotationScheduler:141`). KMS 추상화(`KmsClient`: Local/NoOp/Vault/NHN/AnyID)와 `KeyVersionRegistry` 있음. **제로화 없음**(`Arrays.fill`/`destroy` 0건, 키를 `String` 으로 보관) | 🟡 | 키 생성을 모듈 DRBG 로, 키 재료는 `byte[]`+명시적 제로화, KMS 로부터 받은 키의 메모리 수명 관리. Vault 경로를 평가 구성으로 고정 |
| FCS_RBG 난수 | 검증필 DRBG | JDK `SecureRandom` | ❌ | 모듈 DRBG 로 교체(SPI 경유) |
| 비밀번호·API 키 저장 | 솔트+반복 해시 | `ApiKeyHashUtil` 는 PBKDF2(310k, 솔트) 이나 **실제 경로는 무염 SHA-256**(`AgencyAdminService:293`, `HandoffAgencyKeyInterceptor:134`). 내부 호출자 키는 평문 비교 | 🟡 | 기관 API 키 저장을 PBKDF2(또는 모듈 KDF)로 통일, 내부 키는 KMS/Vault 참조로 |
| 키 회전 | 정책에 따른 회전·유예 | Handoff 키 90일 자동 회전(`HandoffKeyRotationScheduler`), CI 키 v1/v2 수동 | 🟡 | CI 키 회전 절차·재암호화 배치, 관리자 지침서에 기술 |

### 3.3 사용자 데이터 보호 (FDP)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FDP_ACC/ACF SSO 접근통제 | 사용자→서비스(기관) 접근 정책, 최소 인증수준·허용 속성·유지보수 시간대 | `PolicyEngineImpl`(최소 인증수준, 점검시간, 사용자 상태), `agency_meta.allowed_attributes`, `callback_whitelist`(`CallbackUrlValidator`), 연합 역할(idem-authz) | 🟡 | 정책 규칙을 ST 의 SFP 로 명문화. `redirectUri` null 이면 화이트리스트 검증이 건너뛰어지는 경로(`HandoffController:135`) 제거. `allowed-return-urls` 의 `startsWith` 비교를 origin 비교로 |
| FDP_RIP 잔여정보 보호 | 자원 해제 시 이전 정보 접근 불가 | 제로화 없음. 평문 CI·키가 GC 에 맡겨짐 | ❌ | 민감 버퍼 `byte[]`/`char[]` 화 + 사용 후 제로화 유틸, 로그·예외 메시지에 평문 미포함 검증 |
| 저장 인증정보 보호 | 인증정보·개인정보 암호화 저장 | CI AES-256-GCM 컬럼 암호화(`CiCryptoServiceImpl`), 이름·전화 마스킹(`PiiMaskingService`). TDE 없음. 감사 로그의 `source_ip`·`user_agent` 원문 저장 | 🟡 | 암호화를 검증필 모듈로, 감사 로그 IP 마스킹 정책 적용 여부 결정 |
| 개인정보 파기 | 보존기간 경과 시 파기 | `PersonalDataRetentionScheduler` 가 **기본 비활성 + dry-run**, 보존 365일은 임시값 | 🟡 | 법무 검토로 기간 확정, 평가 구성에서 활성화, 파기 감사 기록 |

### 3.4 식별 및 인증 (FIA)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FIA_UID/UAU 관리자 식별·인증 | 관리자는 관리 행위 전 반드시 식별·인증 | **없음**. 콘솔 로그인 API 호출부만 있고 서버 구현 없음(`idem-console/frontend/src/api/user/login.ts` 등은 SigNoz 유래 스캐폴딩). Spring Security 미사용. `AdminAuthInterceptor` 는 주석에만 존재 | ❌ | 관리자 인증 서버 구현: (권장) Keycloak 관리자 realm OIDC + 서버 측 세션, 또는 자체 계정 저장소. `/api/v1/admin/**`·`DELETE /api/v1/handoff/{id}`·액추에이터에 인가 적용 |
| FIA_AFL 인증 실패 처리 | N회 실패 시 잠금, 관리자 해제 | 최종 사용자: 5회 잠금(`LockRepositoryImpl`). **버그 의심**: `isLocked(providerCode, providerCode)` 로 식별자 대신 provider 가 전달돼 잠금이 provider 전역(`idem-gate/.../AuthServiceImpl.java:58`). 관리자: 없음 | 🟡 | 잠금 키 버그 수정 + 테스트, 관리자 계정 잠금·해제 기능 |
| FIA_SOS 비밀 품질 | 비밀번호 길이·복잡도·이력·만료 | 없음(관리자 비밀번호 저장소 자체가 없음) | ❌ | 관리자 인증 방식에 따라 정책 구현(자체 계정이면 필수, OIDC 위임이면 Keycloak 정책을 OE 로) |
| FIA_UAU.5 다중 인증 메커니즘 | 지원 인증 방식과 선택 규칙 | NICE 휴대폰, NICE CI-check, OACX 간편인증, Keycloak OIDC, AnyID, 비OIDC 어댑터, Mock. 인증수준 `L1/L2/L3`(AuthResult) 와 `LOW/MEDIUM/HIGH`(CastToken) **두 어휘 혼재, 매핑 코드 없음** | 🟡 | 인증수준 단일 어휘로 통일(4b 와 함께), 방식별 수준 규칙 표를 ST·지침서에 |
| FIA_UAU.6 재인증 | 민감 행위·수준 상승 시 재인증 | 없음(CAST 는 명시적으로 재인증 생략) | ❌ | 인증수준 상승(step-up) 정책과 재인증 흐름 정의 |
| FIA_UAU.7 인증 피드백 보호 | 실패 사유 비노출 | hub 내부 호출자 경로는 동일 응답, registry `InternalApiKeyInterceptor` 는 "Missing" / "Invalid" 구분 응답 | 🟡 | 응답 통일 |
| 관리자 MFA | 관리자 2요소 | 없음. V12 MFA 스키마(`mfa_enrollment`, `aal_policy`)는 **미사용 코드 0건** | ❌ | 관리자 OTP/FIDO2(Keycloak 위임 시 realm 정책) |

### 3.5 보안관리 (FMT)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FMT_SMR 보안 역할 | 관리자 역할 정의·분리(정책관리자/감사관리자 등) | 프론트 상수 `ADMIN/VIEWER/EDITOR/AUTHOR` + React 라우트 가드(`SKIP_AUTH` 로 우회 가능). 서버 측 역할 검증 없음 | ❌ | 서버 측 RBAC(최소: 시스템관리자·정책관리자·감사관리자), 역할별 API 인가 매트릭스 |
| FMT_MOF/MSA/MTD 보안기능·속성·데이터 관리 | 관리 행위는 인가된 역할만 | 기관 정책·키 회전·기능 플래그(`FeatureFlags`) 변경이 무인증 | ❌ | 위 인가 적용 + 모든 관리 행위 감사 |
| FMT_MSA.3 안전한 기본값 | 보안 속성 기본값이 제한적 | 부팅 가드마다 우회 플래그(`allow-empty-*`, `allow-in-prod`, `security-headers.enabled`, `rate-limit.enabled`, `audit.db-save-enabled`) 존재. CAST 키 미설정 시 임시 키로 기동(`CastKeyConfig:96`) | 🟡 | 평가 구성(prod 프로파일)에서 우회 플래그를 **강제 비활성**·부팅 거부, CAST 키 미설정 시 prod 기동 거부 |

### 3.6 TSF 보호 (FPT)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FPT_TST.1 자체시험 | 기동·주기·요청 시 TSF/암호모듈 무결성 시험 | 설정값 부팅 가드만(`KeyVersionRegistry`, `CiCryptoServiceImpl`, `LocalKmsClient`, `WebhookDispatcherService`, `AuthCredentialsValidator`, `InternalCallerAuthInterceptor`). 바이너리·설정 무결성 검증 없음 | 🟡 | 배포 JAR/설정 해시 매니페스트 + 기동 시 검증, 검증필 모듈 자체시험 호출, 관리자 요청 시 자체시험 API |
| FPT_ITT/ITC 내부 전송 보호 | TOE 구성요소 간 전송 보호 | 서비스 간 HMAC 서명(`X-Internal-Sig`)은 있으나 **F-26 off 이면 서명 누락을 통과**(`HmacSignatureFilter:110-114`). 전송 암호화 없음 | ❌ | 구성요소 간 TLS(가능하면 mTLS) + 서명 강제 모드 기본화 |
| 안전한 갱신 | 갱신물 출처·무결성 검증 | Helm 이미지 `tag: latest`, 서명 검증 없음 | ❌ | 이미지 다이제스트 고정 + cosign 서명·검증, 릴리스 노트·해시 공표 |
| FPT_FLS 장애 시 안전 상태 | 장애 시 안전 유지 | 분산락 NoOp 이면 티켓·CAST 1회성 보장이 다중 Pod 에서 약화(`NoOpRedissonConfig`). Kafka 장애 시 Handoff 요청 60초 블로킹(2026-09-08 발견) | 🟡 | 평가 구성에서 Redisson 강제, Kafka 발행을 비동기·타임아웃 짧게 |

### 3.7 TOE 접근 (FTA)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FTA_SSL 세션 잠금·종료 | 유휴·절대 시간 초과 시 종료 | FE 세션 유휴 30분·절대 8시간(`FeSessionServiceImpl:34-38`), 쿠키 HttpOnly/Secure/SameSite=Lax. 관리자 세션 없음 | 🟡 | 관리자 세션 타임아웃(권장 유휴 10~15분), 값 설정 가능·문서화 |
| FTA_MCS 동시 세션 제한 | 사용자당 동시 세션 수 제한 | 역인덱스(`fe:user-sessions`)는 있으나 제한 미구현 | ❌ | 사용자·관리자 동시 세션 한도 정책 |
| FTA_TSE 세션 설정 제한 | 시간·위치 등 조건부 접근 | 기관 점검시간대(`maintenance_windows`) 있음. 관리자 접속 IP 제한 없음 | 🟡 | 관리자 접속 허용 IP 대역 |
| FTA_TAB 접근 배너 | 접속 시 경고 배너 | 없음 | ❌ | 콘솔 로그인 배너(설정 가능) |
| 세션 이력 | 마지막 접속 정보 표시 | 없음 | ❌ | 관리자 마지막 로그인 일시·IP 표시 |

### 3.8 안전한 경로/채널 (FTP)

| 항목 | 요구(요지) | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| FTP_TRP 관리자 안전한 경로 | 관리자↔TOE TLS | 서비스 TLS 없음(Nginx 위임) | ❌ | 서비스 `server.ssl.*` 로 TLS 1.2+ 직접 종단(암호 스위트를 검증필 모듈 기준으로 제한) 또는 Nginx 를 TOE 에 포함 |
| FTP_ITC 외부 IT 개체와 안전한 채널 | DB·Redis·Kafka·Keycloak·기관 웹훅·Vault | DB `sslmode=disable`·`useSSL=false` 고정, Redis/Kafka 평문, 기관 프로비저닝 mTLS 는 **키스토어 없으면 조용히 일반 TLS 로 폴백**(`IdoWebConfig:187-200`) | ❌ | 모든 외부 채널 TLS 강제, mTLS 폴백 제거(실패 시 기동 거부), 웹훅 HTTPS 만 허용 |

---

## 4. 보증 요구사항 갭 (ADV·AGD·ALC·ATE·AVA)

| 클래스 | 요구 산출물 | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| ASE 보안목표명세서(ST) | TOE 개요·위협·보안목적·SFR·TSS | 없음 | ❌ | §2 범위로 ST 작성(컨설팅 병행 권장) |
| ADV_FSP 기능명세 | TSFI 별 목적·파라미터·오류 | `docs/internal/spec/03*·04*` 모듈·API 명세는 있으나 TSFI 관점 아님. 인증·감사 파라미터·오류 코드 표 필요 | 🟡 | 기존 API 명세를 TSFI 표로 재구성(`PlatformErrorCode` 기준 오류 목록) |
| ADV_TDS 설계 | 서브시스템·모듈 설계 | `docs/internal/architecture/*`, `wiki/adr/ADR-001..013`, EDA 마스터 아키텍처 설계서(docx) | 🟡 | TOE 경계 기준으로 재정리, 비-TOE 구성요소 분리 |
| AGD_PRE 준비 절차 | 안전한 설치·초기 설정 | `wiki/ops/01-production-deployment-guide.md`, `docs/deployment/README.md`, Helm values | 🟡 | "평가 구성" 을 단일 설치 절차로 고정(우회 플래그 off, TLS, KMS, 관리자 초기 계정) |
| AGD_OPE 운영 지침 | 관리자·사용자 지침(보안기능별) | `docs/sso-im-operations-manual.md`(운영), 기관 개발자 가이드. **관리자 보안 지침·최종 사용자 지침 없음** | ❌ | 관리자 지침서(감사 검토·키 관리·계정 관리·자체시험), 사용자 지침서 |
| ALC_CMC/CMS 형상관리 | 형상 항목·버전·변경 통제 | Git + PR(`shipster→main`), Spotless·훅. 버전 `0.1.0-SNAPSHOT`, **제품 CHANGELOG 없음**(SDK 만 존재) | 🟡 | 제품 버전 체계·릴리스 태그·CHANGELOG, 형상항목 목록(소스·문서·빌드 스크립트·컨테이너) |
| ALC_DEL 배포 | 배포 무결성 | 이미지 서명 없음 | ❌ | 서명·해시 공표 절차 |
| ALC_DVS 개발보안 | 개발환경 접근통제 | 자체 호스팅 러너, 개인 WSL 환경 | 🟡 | 개발환경 보안 절차 문서(접근·백업·비밀관리) |
| ALC_FLR 결함 교정 | 결함 접수·수정·배포 절차 | 없음(이슈 트래커 관행만) | ❌ | 결함 교정 절차서 + 보안 취약점 공지 채널 |
| ATE_FUN/COV/DPT 시험 | 기능 시험 계획·결과·커버리지 | 단위 1,583건 + 통합 35건(2026-09-08 복구). 시험 계획서·TSFI 매핑·커버리지 리포트 없음. k6 보고서는 성능 전용 | 🟡 | 시험 계획서(TSFI↔테스트 매핑), JaCoCo 커버리지 리포트 산출·보관, 통합 테스트 CI 게이트 승격 |
| AVA_VAN 취약성 분석 | 취약점 진단·모의해킹 대응 | OWASP Dependency-Check(수동), Trivy 이미지 스캔. SAST·DAST·모의해킹 없음 | 🟡 | SAST(Semgrep/SpotBugs) 도입, 외부 모의해킹 1회, 조치 이력 |
| 라이선스·법무 | 제3자 컴포넌트 목록·라이선스 | `LICENSE` 파일 없음, Dockerfile 라벨 `Proprietary` | ❌ | 라이선스 결정(`open-source-readiness.md` C5), SBOM(CycloneDX) 생성 |

---

## 5. GS 인증 갭 (ISO/IEC 25023 기준)

| 품질 특성 | 시험 관점 | 현재 | 판정 | 조치 |
|---|---|---|---|---|
| 기능 적합성 | 제품 설명서의 기능이 매뉴얼대로 동작 | 기능은 있으나 **제품 설명서·사용자 매뉴얼 없음**. 관리 기능(콘솔)은 서버 미구현 | 🟡 | 제품 설명서(기능 목록)·사용자/관리자 매뉴얼 작성, 콘솔 관리 기능 완성 |
| 성능 효율성 | 응답시간·자원 | k6 보고서(2026-05-18) 존재 | ✅ | 최신 빌드로 재측정해 첨부 |
| 호환성 | 지원 OS·DB·브라우저 | 코드 기준만 존재 | 🟡 | 지원 조합 표 확정·시험 |
| 사용성 | 매뉴얼 일치·오류 메시지·접근성 | 콘솔 UI 는 SigNoz 유래 스캐폴딩 잔재(로그인·초대 등 미구현 화면) | ❌ | 미구현 화면 제거 또는 구현, 한국어 오류 메시지 정비 |
| 신뢰성 | 결함 밀도, 장애 회복 | 통합 테스트 첫 실행에서 운영 결함 4건(`docs/local-dev-workflow.md` §3) — 결함 밀도 미측정 | 🟡 | 전체 회귀 1회 후 결함 밀도 산출, 백업·복구 절차 문서 |
| 보안성 | 인증·권한·감사·암호 | §3 참조 | ❌ | 최소: 관리자 인증·권한, 감사 조회, TLS |
| 유지보수성 | 로그·설정·모듈성 | 양호(Actuator, Prometheus, 구조화 로그) | ✅ | — |
| 이식성 | 설치·제거 용이성 | Helm·Compose 있음. **시험원이 따라 할 단일 설치 매뉴얼 없음**, 제거 절차 없음 | 🟡 | 설치 매뉴얼(전제·절차·검증·제거), 오프라인 설치본(이미지 tar + 차트) |

---

## 6. 로드맵

| 단계 | 기간(안) | 내용 | 산출물 |
|---|---|---|---|
| **Phase 0 — 범위·명명 고정** | 1개월 | TOE 경계(§2) 확정, 개명 4b·5단계 완료(설정 키·환경변수·DB·Keycloak 이름), 지원 플랫폼 1조합 고정, 인증수준 어휘 통일, 법인·신청 주체·예산 결정, 사무국 사전 질의(§8) | TOE 범위서, 명명 확정, 질의 회신 |
| **Phase 1 — 보안기능 보완 A (차단 해소)** | 2~3개월 | 관리자 I&A + 서버 RBAC + 관리 행위 감사(§3.4·3.5), 감사 무결성·유실 방지·검토 API/UI(§3.1), 서비스·DB·내부 채널 TLS(§3.8), 우회 플래그 prod 강제 차단·CAST 임시키 금지(§3.5), 잠금 키 버그·`redirectUri` null 우회 수정 | 코드 + 통합 테스트 |
| **Phase 2 — 검증필 암호모듈** | 2~3개월(Phase 1 와 병행) | `CryptoProvider` SPI 신설, KCMVP 모듈 도입·교체(AES-GCM/HMAC/SHA-256/PBKDF2/DRBG/서명), 키 제로화, 키 회전 절차 | 코드 + 암호 인벤토리 갱신 |
| **Phase 3 — GS 인증** | 2~4개월(Phase 1 후반부터) | 설치 매뉴얼·사용자/관리자 매뉴얼·제품 설명서, 오프라인 설치본, 결함 밀도 측정, 시험 신청 | GS 인증서, 조달 등록 |
| **Phase 4 — CC 산출물·평가** | 산출물 2~3개월 + 평가 6~12개월 | ST, 기능명세(TSFI), 설계, 지침서, 형상·배포·결함교정 절차, 시험 계획·결과·커버리지, 취약성 분석(SAST·모의해킹), 평가 계약, 결함 교정 라운드 | CC 인증서 |

착수 전 결정 사항: 신청 법인, 예산(평가·컨설팅·검증필 모듈 라이선스·모의해킹), GS 선행 여부, 관리자 인증 방식(Keycloak 위임 vs 자체 계정).

---

## 7. 코드에서 바로 착수 가능한 작업 (우선순위순)

1. **관리자 인증·인가 뼈대**: `/api/v1/admin/**`, `DELETE /api/v1/handoff/{id}`, `/actuator/**` 에 인증 인터셉터, 서버 측 역할 검사, `X-Admin-Id` 제거.
2. **감사 유실 방지·무결성**: `AuditLogPublisher` 실패 폴백·알림·메트릭, VARCHAR(36) 컬럼 확장 또는 입력 길이 검증, `audit_log` 해시체인 + DB 권한 분리, 관리 행위 감사.
3. **감사 검토 API/UI**: 기간·주체·사건·결과 필터 조회, 콘솔 화면.
4. **암호 SPI**: `CryptoProvider` 인터페이스로 `HandoffCryptoService`·`CiCryptoServiceImpl`·`AesSharedKeyDecryptor`·`NiceCryptoUtil`·HMAC 서명·`ApiKeyHashUtil` 을 경유시키고 JCE 구현을 기본으로 두어, 검증필 모듈을 어댑터로 끼울 수 있게.
5. **prod 안전 기본값**: `allow-empty-*`·`allow-in-prod`·`security-headers.enabled=false`·`rate-limit.enabled=false`·`audit.db-save-enabled=false` 를 prod 프로파일에서 기동 거부, CAST 키 미설정 시 기동 거부, 기관 API 키 해시를 PBKDF2 로.
6. **TLS**: 각 서비스 `server.ssl.*` + DB/Redis/Kafka TLS 옵션, mTLS 폴백 제거, 내부 서명 강제 모드 기본화.
7. **버그**: `LockRepository.isLocked(providerCode, providerCode)`(idem-gate), `HandoffController` `redirectUri` null 시 화이트리스트 생략, `allowed-return-urls` prefix 비교.
8. **품질 증적**: 통합 테스트 CI 게이트 승격(Docker 있는 러너), JaCoCo 리포트 보관, SAST 도입, 제품 CHANGELOG·버전 태그, `LICENSE`·SBOM.

---

## 8. 확인 필요 사항 (사무국·평가기관·컨설팅 질의)

1. 국가용 보안요구사항 "통합인증(SSO)" 유형의 현행 판과 선택 컴포넌트 목록, 보증 수준.
2. 클라우드·컨테이너(Kubernetes) 배포 형태의 TOE 인정 범위와 운영환경 가정 작성 관례.
3. TLS 종단을 Nginx(운영환경)에 두는 구성의 허용 여부 vs TOE 자체 TLS 필수 여부.
4. KCMVP 모듈 후보(JDK 21·Spring Boot 3 지원, 컨테이너 배포 시 모듈 무결성 검증 방식, 라이선스 비용).
5. Keycloak 을 관리자 인증 제공자로 두는 경우 FIA 요구사항 충족 인정 범위.
6. 오픈소스 공개판과 평가판의 동일성 관리(버전·해시) 요건.
7. 평가 비용·기간 견적(평가기관 2곳 이상), 컨설팅 범위.
8. GS: 시험 신청 시 제출 문서 양식, 온프레미스 설치 시험 환경(시험원 제공 vs 신청기관 제공), 중소기업 감면.

---

## 부록 A — 암호 인벤토리 (현재, 전부 JCE/BouncyCastle)

> **2026-09-21 갱신**: 코어(common·gate·registry·hub·authz·relay)의 아래 항목은 전부 `idem-common` `CryptoProvider` SPI 를 경유하며 JCE 직접 호출은 `crypto/jca/JcaCryptoProvider` 한 곳뿐이다(`CryptoBoundaryGuardTest` 로 강제). 검증필 모듈 도입은 이 구현체 교체로 끝난다. SDK(`HmacSigner`)·에이전트(`OnePassHttpClient`)·플러그인(`NiceCryptoUtil`, AnyID)은 SPI 밖이다. `NiceCryptoUtil` 은 `plugins/idem-plugin-nice-oacx` 로, AnyID KMS 는 `plugins/idem-plugin-anyid` 로 이동했다.

| 용도 | 알고리즘 | 위치 |
|---|---|---|
| Handoff 티켓 암호화 | AES-256-GCM(96-bit IV, 128-bit 태그, AAD=ticketId) | `idem-hub/.../handoff/crypto/HandoffCryptoService.java` |
| Handoff 티켓 MAC | HMAC-SHA256 | 동일 |
| CI 저장 암호화 | AES-256-GCM(v1/v2 키 버전) | `idem-registry/.../crypto/CiCryptoServiceImpl.java` |
| Q-IM 공유키 복호화 | AES-GCM / AES-CBC | `idem-hub/.../qim/crypto/AesSharedKeyDecryptor.java` |
| NICE 결과 복호화 | PBKDF2-HMAC-SHA256 → AES-GCM + HMAC-SHA256 | `idem-hub/.../auth/util/NiceCryptoUtil.java` |
| CAST 토큰 서명 | Ed25519(EdDSA) JWT | `idem-hub/.../sso/CastTokenServiceImpl.java`, `CastKeyConfig.java` |
| Keycloak ID 토큰 검증 | RS256(JWKS) | `idem-gate/.../keycloak/KeycloakJwksVerifier.java` |
| 내부·게이트웨이·웹훅·에이전트·SDK 서명 | HMAC-SHA256 | `SignaturePayloadBuilder`, `InternalSigVerifier`(hub/gate), `WebhookDispatcherService`, `WebhookRelayJob`, `OnePassHttpClient`, `HmacSigner` |
| DI 생성 | HMAC-SHA256 | `idem-registry/.../identity/DiGenerationService.java` |
| PKCE | SecureRandom + SHA-256 | `idem-gate/.../pkce/PkceService.java` |
| 기관 API 키 해시 | SHA-256(무염) — 유틸은 PBKDF2 310k | `HandoffAgencyKeyInterceptor`, `AgencyAdminService` / `ApiKeyHashUtil` |
| AnyID KMS 키 언랩 | ARIA-CBC-256(BouncyCastle, 리플렉션) | `idem-hub/.../crypto/kms/AnyIdKmsClient.java` |
| 키 관리 | `KmsClient`(Local/NoOp/Vault/NHN/AnyID), `KeyVersionRegistry`, 90일 자동 회전 | `idem-hub/.../crypto/` |

## 부록 B — 관련 문서

- 오픈소스 공개 차단 항목(벤더 SDK·자격증명): `docs/open-source-readiness.md`
- 2026-09-08 통합 테스트 복구로 발견한 운영 결함 4건: `docs/local-dev-workflow.md` §3
- 명명 규칙·개명 단계: `docs/naming.md`
- 보안 설계 현황: `docs/internal/spec/07-security.md`
- 벤더 플러그인 분리 계획: `docs/vendor-plugin-plan.md`, `docs/identity-provider-spi.md`
