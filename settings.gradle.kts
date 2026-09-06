rootProject.name = "idem"

include(
    "idem-common",
    "idem-gate",
    "idem-registry",
    "idem-authz",             // 연합 인가(Federated Authorization) — 기관별 역할/권한 부여 SoR (L1 코어)
    "idem-hub",
    "idem-console",
    "idem-tenant-sample",
    "idem-sdk-java",
    "idem-relay",  // Transactional Outbox 분산 릴레이 배치 서비스 (ShedLock 기반)
    "idem-agent",       // Idem Java Agent — 유관기관 WAS 자동 연동 (-javaagent 배포)
    "idem-plugin-mock-auth"  // 본인인증 SPI Mock 플러그인 (plugins/ 하위, docs/vendor-plugin-plan.md P1)
)

// 플러그인 모듈은 plugins/ 디렉터리에 모으되 Gradle 경로는 평평하게 유지한다 (:idem-plugin-mock-auth)
project(":idem-plugin-mock-auth").projectDir = file("plugins/idem-plugin-mock-auth")
