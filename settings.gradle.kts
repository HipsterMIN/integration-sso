rootProject.name = "idem"

// 제품 3개 + 제품 밖 (docs/generalization-plan.md §2.4, D1-b). Gradle 경로는 평평하게 유지한다.
include(
    // ── 공통 ──────────────────────────────────────────────────────────────────
    "idem-common",            // SPI(본인확인·브로커·정책)·이벤트·Kafka 선택 의존 자동 설정

    // ── 제품 1: Idem SSO — OIDC 발급·세션·SLO·인증수준·본인확인 SPI·기관 연계(Handoff/OIDC_RP), 숨긴 Keycloak ──
    "idem-hub",               // 정책 오케스트레이터 + FE BFF (:8083)
    "idem-gate",              // 인증 결과 SoR, Keycloak OIDC 어댑터 (:8081)

    // ── 제품 2: Idem IM — 사용자·식별자·자격증명·동의·생명주기·할당·역할 ──
    "idem-registry",          // 식별·매핑 SoR, PostgreSQL qim 스키마 (:8082)
    "idem-authz",             // 연합 인가(Federated Authorization) — 기관별 역할/권한 부여 SoR (:8086)

    // ── 제품 3: Idem KR Public Edition — 벤더 플러그인 (plugins/ 하위, docs/vendor-plugin-plan.md) ──
    "idem-plugin-mock-auth",  // 본인인증 SPI Mock 플러그인 (Core 검증용, 운영 금지)
    "idem-plugin-nice-oacx",  // NICE/OACX/EzAuth 플러그인 (P2·S5a). OACX SDK 는 vendor-libs 외부 공급, 부재 시 oacx 패키지 자동 제외
    "idem-plugin-anyid",      // 행안부 Any-ID 설치형 브로커 플러그인 (P3·S5b). SDK·자산은 vendor-libs 외부 공급, 부재 시 sdk 패키지 자동 제외
    "idem-kr-hub",            // KR 에디션 hub 부트 모듈 (S8-a): 코어 hub + SMES 회원 개념(CI 조회·기업인증·회원전환·회원조회). bootJar = KR 에디션 이미지
    "idem-kr-registry",       // KR 에디션 registry 부트 모듈 (S8-a): 코어 registry + 기업회원(biz_member)·CI 조회. Flyway KR 마이그레이션 동봉

    // ── 제품 밖: 운영 도구·샘플·SDK (GS 대상 아님) ──
    "idem-console",           // 관리 콘솔 프런트(React) — S7 에서 제품으로 편입
    "idem-relay",             // Transactional Outbox 분산 릴레이 배치 (ShedLock) — Kafka 배포 옵션 전용
    "idem-agent",             // Idem Java Agent — 유관기관 WAS 자동 연동 (-javaagent 배포)
    "idem-tenant-sample",     // 기관(Service) 시뮬레이터 샘플
    "idem-sdk-java"           // 기관용 Java SDK
)

// 플러그인 모듈은 plugins/ 디렉터리에 모으되 Gradle 경로는 평평하게 유지한다 (:idem-plugin-mock-auth)
project(":idem-plugin-mock-auth").projectDir = file("plugins/idem-plugin-mock-auth")
project(":idem-plugin-nice-oacx").projectDir = file("plugins/idem-plugin-nice-oacx")
project(":idem-plugin-anyid").projectDir = file("plugins/idem-plugin-anyid")

// KR 에디션 모듈은 editions/ 디렉터리에 모은다. 코어(idem-hub·idem-registry)는 이 모듈들을 모르고, 에디션이 코어를 의존한다
project(":idem-kr-hub").projectDir = file("editions/idem-kr-hub")
project(":idem-kr-registry").projectDir = file("editions/idem-kr-registry")
