# SSO/IM 본질 메트릭 운영 가이드 (RUNBOOK)

> 운영자가 **매일 보는 3개 메트릭**으로 SSO/IM 시스템 건강을 가시화한다.
> 본 문서는 PR-B1-new (SSO 본질 메트릭 3종) 산출물이다.

**관련 문서**:
- `docs/OPERATION_INVENTORY.md` §1 SSO/IM 본질 4가지
- `docs/SPRINT_B_PLAN.md` PR-B1-new
- `docs/OPERATION_INVENTORY.md` §6 Prometheus 메트릭 인벤토리

---

## 1. SSO/IM 본질 메트릭 3종

### 1.1 인증 성공률 — `auth.success.rate` (PromQL 계산)

| 항목 | 값 |
|------|-----|
| 모듈 | q-sign |
| 원천 메트릭 | `auth_success_total{provider, auth_level}`, `auth_failure_total{provider, reason}` |
| 구현 | **신규 코드 없음** — `q-sign/.../metrics/AuthMetrics.java` 기존 자산 재활용 |
| 본질 매핑 | "사용자가 로그인할 수 있는가" |

**PromQL — 5분 윈도우 전체 인증 성공률**:
```promql
sum(rate(auth_success_total[5m]))
  /
( sum(rate(auth_success_total[5m])) + sum(rate(auth_failure_total[5m])) )
```

**PromQL — Provider별 성공률** (PASS/GPKI/Keycloak 비교):
```promql
sum by (provider) (rate(auth_success_total[5m]))
  /
( sum by (provider) (rate(auth_success_total[5m])) + sum by (provider) (rate(auth_failure_total[5m])) )
```

**알람 권장 임계값**:
| 심각도 | 조건 | 지속 시간 | 대응 |
|--------|------|----------|------|
| **critical** | 성공률 < 90% | 5분 | 새벽 호출 — IdP 장애 가능성 즉시 조사 |
| **warning** | 성공률 < 95% | 10분 | Slack 채널 알림 — provider별 분포 확인 |

---

### 1.2 핸드오프 지연 p95 — `handoff.latency.p95` (Spring Boot 자동)

| 항목 | 값 |
|------|-----|
| 모듈 | ido (`HandoffController`) |
| 원천 메트릭 | `http_server_requests_seconds{uri,method,status,quantile}` |
| 구현 | **신규 코드 없음** — Spring Boot Actuator가 자동 생성 (Prometheus exposure에 포함됨) |
| 본질 매핑 | "기관 연계가 정상 작동하는가" |

**전제**: PR-A5/PR-B4에서 확정한 actuator exposure에 `prometheus`가 포함되어 있어 자동 노출됨.

**PromQL — Handoff Issue p95**:
```promql
histogram_quantile(0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{
      uri="/api/v1/handoff/issue",
      application="ido"
    }[5m])
  )
)
```

**PromQL — Handoff Verify p95**:
```promql
histogram_quantile(0.95,
  sum by (le) (
    rate(http_server_requests_seconds_bucket{
      uri="/api/v1/handoff/verify",
      application="ido"
    }[5m])
  )
)
```

**알람 권장 임계값** (SLO p95 < 2s):
| 심각도 | 조건 | 지속 시간 | 대응 |
|--------|------|----------|------|
| **critical** | p95 > 5s | 5분 | KMS 응답성 + DB/Redis 확인 |
| **warning** | p95 > 2s | 10분 | Slack — 트래픽 증가/네트워크 확인 |

**비고**: Spring Boot의 `http_server_requests_seconds`는 기본적으로 percentile histogram을 발행하지 않는다. 정확한 p95가 필요하면 `application.yml`에 다음을 추가한다 (현재 미설정 — 운영 검증 후 필요 시 추가):
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```
→ **본 PR에서는 추가하지 않음**. 운영 검증 결과 PromQL `histogram_quantile()`가 부족할 경우 별도 PR로 검토.

---

### 1.3 KMS 가용성 — `onepass_kms_healthy` (신규 Gauge)

| 항목 | 값 |
|------|-----|
| 모듈 | ido (`infrastructure/health/VaultKmsHealthIndicator` 재활용) |
| 원천 메트릭 | `onepass_kms_healthy` (Gauge: UP=1, DOWN/UNKNOWN=0) |
| 구현 | `ido/.../metrics/KmsHealthMetrics.java` (신규 — 약 80 LOC) |
| 본질 매핑 | "개인정보가 안전한가" |

**구현 원리**:
`VaultKmsHealthIndicator`가 이미 5초 캐시 + Vault Transit API 호출 + 다중 KmsClient 빈 처리를 모두 수행하므로, `KmsHealthMetrics`는 그 결과를 0/1로 변환만 한다. **새 health 로직 0줄 추가**.

**PromQL — KMS UP 여부**:
```promql
onepass_kms_healthy
```

**PromQL — KMS 다운 시간 비율 (24시간)**:
```promql
1 - avg_over_time(onepass_kms_healthy[24h])
```

**알람 권장 임계값**:
| 심각도 | 조건 | 지속 시간 | 대응 |
|--------|------|----------|------|
| **critical** | `onepass_kms_healthy == 0` | 1분 | 새벽 호출 — ID 핸드오프 암호화 불가 상태, Vault/KMS 즉시 복구 |
| **warning** | (별도 없음) | — | Critical 단일화 |

---

## 2. Grafana 대시보드 권장 구성

`docs/SPRINT_B_PLAN.md`의 "운영자가 매일 보는 단 3개의 메트릭" 원칙에 따라 단일 대시보드에 **3 패널만** 둔다.

```
┌────────────────────────────────────┬────────────────────────────────────┐
│  Panel 1: 인증 성공률              │  Panel 2: Handoff p95 (s)          │
│  Stat panel, threshold 90/95%      │  Time series, threshold 2s/5s      │
│  PromQL: 1.1                       │  PromQL: 1.2                       │
├────────────────────────────────────┴────────────────────────────────────┤
│  Panel 3: KMS Healthy (UP=1/DOWN=0)                                     │
│  Stat panel, red if 0                                                    │
│  PromQL: onepass_kms_healthy                                            │
└─────────────────────────────────────────────────────────────────────────┘
```

**의도적으로 제외**:
- Pod 메모리/CPU 패널 → K8s 인프라 대시보드 (별도)
- JVM 힙 패널 → JVM 대시보드 (별도)
- DB connection pool 패널 → Spring Boot 자동 메트릭 대시보드 (별도)

→ **이 대시보드 = SSO/IM 본질만**. 인프라 디테일은 별도 화면.

---

## 3. 알람 규칙 통합 (PR-B2-new 예고)

본 3개 메트릭으로 알람 룰을 통합하면 `infra/monitoring/prometheus/alert_rules.yml`의 16개 알람 중 다음을 폐기/통합 가능:

| 폐기 대상 (예고) | 사유 |
|----------------|------|
| `IdoHighErrorRate` (개별 endpoint) | 1.1 (auth.success.rate)로 통합 |
| `HandoffSlowResponse` (별도 지연 알람) | 1.2 (handoff p95)로 통합 |
| `VaultUnreachable` / `KmsDown` (중복) | 1.3 (kms.healthy)로 통합 |

PR-B2-new에서 알람 16개 → 8개 축소 시 본 문서 §1의 임계값을 참조한다.

---

## 4. 운영자 체크리스트 (매일)

새벽 3시 호출 받기 전에 매일 아침 다음 3개를 확인한다:

```bash
# 1. 인증 성공률 (지난 1시간)
curl -s 'http://prom:9090/api/v1/query?query=sum(rate(auth_success_total[1h]))/(sum(rate(auth_success_total[1h]))+sum(rate(auth_failure_total[1h])))'

# 2. Handoff p95 (지난 1시간)
curl -s 'http://prom:9090/api/v1/query?query=histogram_quantile(0.95,sum%20by%20(le)(rate(http_server_requests_seconds_bucket{uri=%22/api/v1/handoff/issue%22}[1h])))'

# 3. KMS 가용성 (현재)
curl -s 'http://prom:9090/api/v1/query?query=onepass_kms_healthy'
```

세 값이 정상이면 SSO/IM 본질은 건강한 상태다. 인프라/JVM 메트릭은 별도 시점에 확인한다.

---

## 5. 변경 이력

| 일자 | PR | 변경 |
|------|----|------|
| 2026-05-22 | PR-B1-new | 최초 작성 — SSO 본질 메트릭 3종 (PromQL + Gauge 1개 신규) |
