# OnePass Feature Flags — 기능 문서 목록

> **이 폴더에 대하여**: 각 Feature Flag의 목적, 동작 원리, 코드 위치, On/Off 방법을 개발자가 이해할 수 있도록 상세히 설명합니다.

---

## 빠른 참조

| ID | 파일 | 환경변수 | 현재 Phase 기본값 | 설명 |
|----|------|---------|:-----------------:|------|
| F-01 | [F-01-auth-rate-limit.md](F-01-auth-rate-limit.md) | `IDEM_HUB_AUTH_RL_ENABLED` | `true` | IP Rate Limit |
| F-02 | [F-02-agency-rate-limit.md](F-02-agency-rate-limit.md) | `IDEM_HUB_RATE_LIMIT_ENABLED` | `true` | 기관 Rate Limit |
| F-03 | [F-03-audit-kafka.md](F-03-audit-kafka.md) | `IDEM_HUB_AUDIT_KAFKA_ENABLED` | `true` | 감사 Kafka |
| F-04 | [F-04-audit-db.md](F-04-audit-db.md) | `IDEM_HUB_AUDIT_DB_ENABLED` | `true` ⚠️ | 감사 DB |
| F-05 | [F-05-auth-tracing.md](F-05-auth-tracing.md) | `IDEM_HUB_AUTH_TRACING_ENABLED` | `true` | OTel 분산 추적 |
| F-08 | [F-08-redisson-lock.md](F-08-redisson-lock.md) | `IDEM_HUB_REDISSON_ENABLED` | `true` | 분산 락 |
| F-10 | [F-10-security-headers.md](F-10-security-headers.md) | `IDEM_HUB_SECURITY_HEADERS_ENABLED` | `true` | 보안 응답 헤더 |
| F-11 | [F-11-retention.md](F-11-retention.md) | `IDEM_HUB_RETENTION_ENABLED` | `false` ⚠️ | 개인정보 파기 |
| F-12 | [F-12-crypto-rotation.md](F-12-crypto-rotation.md) | `IDEM_HUB_CRYPTO_ROTATION_ENABLED` | `true` | AES 키 로테이션 |
| F-13 | [F-13-outbox-relay.md](F-13-outbox-relay.md) | `IDEM_HUB_OUTBOX_RELAY_ENABLED` | `true` | Outbox 릴레이 |
| F-14 | [F-14-webhook-relay.md](F-14-webhook-relay.md) | `IDEM_HUB_WEBHOOK_RELAY_ENABLED` | `true` | Webhook 릴레이 |
| F-18 | [F-18-sp-receiver-audit.md](F-18-sp-receiver-audit.md) | `IDEM_HUB_REGISTRY_RECEIVER_AUDIT` | `true` | Q-IM SP 수신 감사 |
| ~~F-20~~ | [F-20-provisioning.md](F-20-provisioning.md) | ~~`IDEM_HUB_PROVISIONING_ENABLED`~~ | **제거(S4b)** | 전 기관 프로비저닝 — 범용화 S4b 에서 삭제 |
| ~~F-21~~ | [F-21-provisioning-relay.md](F-21-provisioning-relay.md) | ~~`IDEM_HUB_PROVISIONING_RELAY_ENABLED`~~ | **제거(S4b)** | 프로비저닝 릴레이 — 삭제 |
| ~~F-22~~ | [F-22-provisioning-dry-run.md](F-22-provisioning-dry-run.md) | ~~`IDEM_HUB_PROVISIONING_DRY_RUN`~~ | **제거(S4b)** | 프로비저닝 Dry-Run — 삭제 |
| **F-23** | [F-23-gateway-inbound.md](F-23-gateway-inbound.md) | `IDEM_HUB_GATEWAY_INBOUND_ENABLED` | **`false`** 🔴Phase3A | 인바운드 API |
| **F-24** | [F-24-gateway-outbound.md](F-24-gateway-outbound.md) | `IDEM_HUB_GATEWAY_OUTBOUND_ENABLED` | **`false`** 🔴Phase3B | 아웃바운드 API |
| **F-25** | [F-25-gateway-idempotency.md](F-25-gateway-idempotency.md) | `IDEM_HUB_GATEWAY_IDEMPOTENCY_ENABLED` | `true` ✅ | 멱등성 방어 |
| **F-26** | [F-26-hmac-sig.md](F-26-hmac-sig.md) | `IDEM_HUB_HMAC_SIG_REQUIRED` | `false` 🔴Phase4 | HMAC 필수화 |

---

## 운영 위험 등급

| 등급 | 설명 | 해당 플래그 |
|------|------|------------|
| ⚠️ **OFF 금지** | 비활성화 시 컴플라이언스 위반 또는 보안 사고 | F-04 |
| ⚠️ **OFF 주의** | 비활성화 시 운영 장애 위험 | F-01, F-02, F-08, F-10, F-25 |
| 🟡 **조건부 OFF** | 특정 조건에서만 비활성화 허용 | F-03, F-05, F-12, F-14, F-18 |
| 🔴 **Phase 관리** | Phase-Gate 전략에 따라 단계적 활성화 | F-20~F-24, F-26 |
| ⚠️ **OFF 기본** | 기본값 false — 신중한 전환 필요 | F-11, F-20, F-21, F-23, F-24, F-26 |

---

## Phase-Gate 상태 빠른 확인

```bash
# 전체 Feature Flag 상태 조회
curl -s http://localhost:8083/actuator/features | jq '.features'

# Phase 1 정상 상태 확인 (신규 기능 모두 false)
curl -s http://localhost:8083/actuator/features | jq '
  .features | {
    F20_provisioning: .["F-20_provisioning"].enabled,
    F21_relay: .["F-21_provisioningRelay"].enabled,
    F22_dryRun: .["F-22_provisioningDryRun"].enabled,
    F23_inbound: .["F-23_gatewayInbound"].enabled,
    F24_outbound: .["F-24_gatewayOutbound"].enabled,
    F26_hmac: .["F-26_hmacSigRequired"].enabled
  }'
# Phase 1 예상 출력: F20~F24, F26 = false / F22 = true
```

---

## Phase 전환 명령어 (Helm)

```bash
# Phase 2-A: 프로비저닝 Dry-Run 시작
helm upgrade ido infra/helm/idem-hub --set phase=2a

# Phase 2-B: 프로비저닝 실제 발행
helm upgrade ido infra/helm/idem-hub --set phase=2b

# Phase 3-A: Gateway 인바운드 활성화
helm upgrade ido infra/helm/idem-hub --set phase=3a

# Phase 3-B: Gateway 아웃바운드 활성화
helm upgrade ido infra/helm/idem-hub --set phase=3b

# Phase 4: HMAC 서명 필수화 (Sprint 17)
helm upgrade ido infra/helm/idem-hub --set phase=4
```

---

## 관련 문서
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
- [배포 가이드](../_archive/2026-05-22/deployment-guide.md)
- [FeatureFlags 소스 코드](../../idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/config/FeatureFlags.java)
- K8s ConfigMap — D3 에서 코어 저장소에서 제거(운영기관 전용 매니페스트). 설치본은 [`infra/docker/compose.install.yml`](../../infra/docker/compose.install.yml)
- [Helm Chart](../../infra/helm/idem-hub/)
