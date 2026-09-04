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
    "idem-agent"        // OnePass Agency Java Agent — 유관기관 WAS 자동 연동 (-javaagent 배포)
)
