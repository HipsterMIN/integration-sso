rootProject.name = "onepass-platform"

include(
    "platform-common",
    "q-sign",
    "q-im",
    "ido",
    "onepass-support",
    "onepass-fe",
    "agency-stub",
    "onepass-agency-sdk",
    "outbox-relay-batch",  // Transactional Outbox 분산 릴레이 배치 서비스 (ShedLock 기반)
    "onepass-agent"        // OnePass Agency Java Agent — 유관기관 WAS 자동 연동 (-javaagent 배포)
)
