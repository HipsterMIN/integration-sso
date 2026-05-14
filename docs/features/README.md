# OnePass Feature Flags — 기능 문서 목록

> **이 폴더에 대하여**: 각 Feature Flag의 목적, 동작 원리, 코드 위치, On/Off 방법을 개발자가 이해할 수 있도록 상세히 설명합니다.

---

## 빠른 참조

| ID | 파일 | 환경변수 | 현재 Phase 기본값 | 설명 |
|----|------|---------|:-----------------:|------|
| F-01 | [F-01-auth-rate-limit.md](F-01-auth-rate-limit.md) | `IDO_AUTH_RL_ENABLED` | `true` | IP Rate Limit |
| F-02 | [F-02-agency-rate-limit.md](F-02-agency-rate-limit.md) | `IDO_RATE_LIMIT_ENABLED` | `true` | 기관 Rate Limit |
| F-03 | [F-03-audit-kafka.md](F-03-audit-kafka.md) | `IDO_AUDIT_KAFKA_ENABLED` | `true` | 감사 Kafka |
| F-04 | [F-04-audit-db.md](F-04-audit-db.md) | `IDO_AUDIT_DB_ENABLED` | `true` ⚠️ | 감사 DB |
| F-08 | [F-08-redisson-lock.md](F-08-redisson-lock.md) | `IDO_REDISSON_ENABLED` | `true` | 분산 락 |
| F-11 | [F-11-retention.md](F-11-retention.md) | `IDO_RETENTION_ENABLED` | `false` ⚠️ | 개인정보 파기 |
| F-13 | [F-13-outbox-relay.md](F-13-outbox-relay.md) | `IDO_OUTBOX_RELAY_ENABLED` | `true` | Outbox 릴레이 |
| **F-20** | [F-20-provisioning.md](F-20-provisioning.md) | `IDO_PROVISIONING_ENABLED` | **`false`** 🔴Phase2 | 전 기관 프로비저닝 |
| **F-21** | [F-21-provisioning-relay.md](F-21-provisioning-relay.md) | `IDO_PROVISIONING_RELAY_ENABLED` | **`false`** 🔴Phase2B | Outbox 릴레이 |
| **F-23** | [F-23-gateway-inbound.md](F-23-gateway-inbound.md) | `IDO_GATEWAY_INBOUND_ENABLED` | **`false`** 🔴Phase3A | 인바운드 API |
| **F-24** | [F-24-gateway-outbound.md](F-24-gateway-outbound.md) | `IDO_GATEWAY_OUTBOUND_ENABLED` | **`false`** 🔴Phase3B | 아웃바운드 API |
| **F-25** | [F-25-gateway-idempotency.md](F-25-gateway-idempotency.md) | `IDO_GATEWAY_IDEMPOTENCY_ENABLED` | `true` ✅ | 멱등성 방어 |
| **F-26** | [F-26-hmac-sig.md](F-26-hmac-sig.md) | `IDO_HMAC_SIG_REQUIRED` | `false` 🔴Phase4 | HMAC 필수화 |

---

## Phase-Gate 상태 빠른 확인

```bash
# 전체 Feature Flag 상태 조회
curl -s http://localhost:8083/actuator/features | jq '.features'

# Phase 1 정상 상태 확인 (신규 기능 모두 false)
curl -s http://localhost:8083/actuator/features | jq '
  .features | {
    F20_provisioning: .["F-20_provisioning"].enabled,
    F23_inbound: .["F-23_gatewayInbound"].enabled,
    F24_outbound: .["F-24_gatewayOutbound"].enabled,
    F26_hmac: .["F-26_hmacSigRequired"].enabled
  }'
# Phase 1 예상 출력: 모두 false
```

---

## 관련 문서
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
- [배포 가이드](../deployment-guide.md)
- [FeatureFlags 소스 코드](../../ido/src/main/java/kr/go/smes/ido/config/FeatureFlags.java)
- [K8s ConfigMap](../../infra/k8s/configmaps/)
