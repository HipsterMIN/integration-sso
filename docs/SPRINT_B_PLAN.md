# Sprint B 축소 계획 — "최소 메트릭 + 최소 알람"

> **목적**: Sprint A 회고 결과(`docs/OPERATION_INVENTORY.md`)를 반영하여
> Sprint B 작업 범위를 SSO/IM 본질에 직접 기여하는 것으로 축소한다.
>
> **원칙**: 새 메트릭/알람을 만들 때마다 "이게 정말 새벽 3시에 운영자를 깨울
> 가치가 있는가"를 자문한다.

---

## 기존 Sprint B 계획 (보류/폐기)

기존 안은 PR을 4개로 분할하여 광범위한 메트릭/알람 시스템을 구축하는 안이었으나,
관리 포인트 과잉 우려로 **축소 또는 폐기**한다.

| 원안 PR | 원안 범위 | 재평가 |
|--------|---------|------|
| ~~PR-B1: 4종 메트릭 클래스~~ | HandoffMetrics / KmsMetrics / ProvisioningMetrics / GatewayMetrics | ❌ **폐기** — 본질 3개 메트릭(B1-new)으로 통합 |
| ~~PR-B2: Prometheus 알람 확장~~ | 기존 16개 → 25개+ | ✅ **B2-new로 대체 완료** — 16개를 8개로 축소 (2026-05-22) |
| ~~PR-B3: Slack/PagerDuty 활성화~~ | 모든 알람 자동 전송 | ⚠️ **보류** — 알람 노이즈 줄인 후 (B2-new 완료 후 재검토) |
| **PR-B4: application-prod.yml 분리** | 운영 전용 설정 격리 | ✅ **유지** — 보안 위생, 필수 |

---

## 새 Sprint B 계획 (확정)

### PR-B1-new: SSO 본질 메트릭 3종 (P1) ✅ **완료 (2026-05-22)**

**범위**: 운영자가 매일 보는 단 3개의 메트릭으로 SSO/IM 건강 상태를 가시화.

| 메트릭 | 종류 | 정의 | 실현 방법 |
|--------|------|------|----------|
| 인증 성공률 (5분 윈도우) | PromQL 계산 | `rate(auth.success.total) / (rate(success) + rate(failure))` | **신규 코드 0줄** — 기존 `idem-gate/.../AuthMetrics.java` 재활용 |
| Handoff 지연 p95 | Spring Boot 자동 | `http_server_requests_seconds{uri="/api/v1/handoff/issue"}` | **신규 코드 0줄** — Spring Boot Actuator 자동 메트릭 (Prometheus exposure에 포함됨) |
| `onepass.kms.healthy` | Gauge (UP=1/DOWN=0) | KMS 가용성 (Vault Transit) | `idem-hub/.../metrics/KmsHealthMetrics.java` 신규 — `VaultKmsHealthIndicator` 재사용 (Vault 호출 0회 추가) |

**의도적 제외**:
- 개별 API endpoint 메트릭 (Spring Boot 기본 `http_server_requests`로 충분)
- Provisioning/Webhook 별도 메트릭 (기존 `batch.relay.*` Counter로 충분)
- Gateway 메트릭 (Spring Cloud Gateway 기본 메트릭으로 충분)
- `auth.success.rate` Gauge 신규 (회고 정책 §8 자문 체크리스트 통과: 기존 변수와 통합 가능)
- `handoff.latency.p95` Timer 신규 (Spring Boot 자동 메트릭으로 충족)

**실제 작업량**: 1개 클래스 (~80 LOC) + 가이드 문서 (`docs/RUNBOOK_SSO_METRICS.md` ~190 LOC)

**산출물**:
- `idem-hub/.../metrics/KmsHealthMetrics.java` — Gauge 1개
- `docs/RUNBOOK_SSO_METRICS.md` — PromQL 예시 + 알람 임계값 + Grafana 대시보드 구성

---

### PR-B2-new: 알람 룰 축소 16개 → 8개 (P1) ✅ **완료 (2026-05-22)**

**범위**: `infra/monitoring/prometheus/alert_rules.yml`을 본질 메트릭(PR-B1-new) 정렬 + 유령 알람 제거 관점에서 재작성. **신규 코드 0줄, 알람 정의만 재구성**.

#### 실제 적용된 Critical 5개 (새벽 호출 1티어)
| 알람 | 조건 | for | 본질 매핑 |
|------|------|-----|----------|
| `IdoServiceDown` | `up{job="ido"}==0` | **2m** (롤링 노이즈 방지) | 핸드오프 |
| `QSignServiceDown` | `up{job="q-sign"}==0` | 2m | 인증 |
| `QimServiceDown` | `up{job="q-im"}==0` | 2m | 식별·매핑 |
| `AuthSuccessRateLow` | 인증 성공률 < 90% | 5m | **PR-B1-new §1.1** |
| `KmsUnavailable` | `onepass_kms_healthy==0` | 2m | **PR-B1-new §1.3** |

#### 실제 적용된 Warning 3개 (Slack 채널만)
| 알람 | 조건 |
|------|------|
| `HandoffLatencyHigh` | handoff issue p95 > 2s for 10m (**PR-B1-new §1.2**) |
| `IdoHandoffErrorRateHigh` | handoff 5xx > 5% for 5m (critical → warning 강등, AuthSuccessRateLow가 본질 커버) |
| `RateLimitExceeded` | 분당 429 > 100건 for 1m (보안 신호) |

#### 폐기된 알람 8개
**유령 알람 5개 (가장 위험)** — `prometheus.yml`에 exporter scrape 미설정 상태로 알람만 존재
- `RedisDown`, `RedisMemoryHigh` (redis-exporter 미배포)
- `KafkaDown`, `KafkaConsumerLagHigh` (kafka-exporter 미배포)
- `PostgresDown` (postgres-exporter 미배포)

**중복/노이즈 알람 3개**
- `IdoHighErrorRate` → `IdoHandoffErrorRateHigh`와 중복
- `IdoApiLatencyHigh`, `QimApiLatencyHigh` → 전체 endpoint p95는 의미 약함 (handoff 한정으로 재정의)
- `QimHighErrorRate`, `WebhookDispatchFailureHigh`, `JvmHeapUsageHigh` → 대시보드/ServiceDown으로 충분

**작업량**: `alert_rules.yml` 247 LOC → 169 LOC (-78), 문서 3개 갱신, 신규 코드 0줄

**산출물**:
- `infra/monitoring/prometheus/alert_rules.yml` — 8개 알람으로 재작성
- `docs/RUNBOOK_SSO_METRICS.md` §3 (예고 → 확정)
- `docs/OPERATION_INVENTORY.md` §7 갱신 (유령 알람 회고)

**후속 작업**: 인프라 가시성은 별도 PR(`B5-infra-exporter`)에서 exporter 배포 + scrape 활성화 + 알람 재도입을 패키지로 진행. 본 PR에서 분리한 것은 회고 정책 §8 ("새 외부 시스템 의존성을 추가하는가?")에 정합.

---

### PR-B3-new: Slack 단일 채널 통합 (P2 — 우선순위 하향)

**전제 조건**: PR-B2-new 완료 후 1주일간 알람 노이즈가 줄어든 것을 확인한 후 진행.

**범위**:
- `#onepass-alerts` 단일 채널만 사용 (critical/warning 모두)
- PagerDuty는 **별도 PR로 분리** — 처음부터 도입하지 않는다 (알람 노이즈 검증 우선)

---

### PR-B4: application-prod.yml 분리 (P1 — 보안 위생) ✅ **완료 (2026-05-22)**

**범위**: 운영 환경 전용 설정을 별도 파일로 분리하여 dev/stage 설정과 격리.

대상 모듈: ido, qsign, q-im, outbox-relay-batch (각각 `application-prod.yml`)

**격리 대상**:
- 로그 레벨 (운영은 INFO/WARN, 개발은 DEBUG)
- 트레이싱 샘플링 비율 (운영 0.1, 개발 1.0)
- 보안 헤더 강제 (운영 한정 HSTS, CSP) — ido만 해당 (`ido.security-headers.enabled: true`)
- Actuator 노출 범위 (운영은 health/info/metrics/prometheus만, flyway/features 제외)
- Actuator health show-details: never (의존성 상세 비공개)
- Health Group 동결 (PR-A5 정책 — db/redis [+kms in ido])

**실제 작업량**: 4개 yml = 약 195 LOC (ido 76 / batch 56 / qsign 47 / qim 38)

**Helm 변경**: `values-prod.yaml`의 `SPRING_PROFILES_ACTIVE: k8s → prod` (4개 모듈)
— 기존 `k8s` 프로파일은 `application-k8s.yml` 파일이 부재한 빈 이름이었으므로 정리.

---

## Sprint B 최종 작업 순서 (확정)

```
PR-B4 (application-prod.yml 분리)              ← 가장 먼저 (보안 위생)
  ↓
PR-B1-new (SSO 본질 메트릭 3종)                ← SSO 본질에 직접 기여
  ↓
PR-B2-new (알람 룰 16개 → 8개 축소) ✅완료     ← B1 메트릭과 통합
  ↓
[1주일 운영 관찰] — 알람 노이즈 측정 (← 현재 단계)
  ↓
PR-B3-new (Slack 단일 채널) — 필요 시에만 진행
```

---

## Sprint C/D — 일단 동결

**기존 계획**:
- ~~PR-C1~C4: NICE CI, PASS, 보안 기능~~
- ~~PR-D1~D2: 배포 문서 + 스모크 테스트~~

**현 상태**: Sprint A 회고 결과, **사용자 우선순위 재확인이 필요**하다.

다음 항목에 대한 사용자 결정 요청:
1. NICE CI / PASS 신규 연계가 운영 우선순위인가?
2. 기존 기관 연계 안정화가 더 우선인가?
3. 신규 기능보다 기술 부채(예: 환경변수 정리, KMS 구현체 단일화) 정리가 우선인가?

---

## 신규 기능 추가 시 적용할 원칙

`docs/OPERATION_INVENTORY.md` §8 체크리스트를 통과한 기능만 Sprint B 이후에 추가한다.
특히:

- 새 메트릭 ≤ 3개/PR (현재 정책: PR당 메트릭 1~3개)
- 새 알람 ≤ 2개/PR
- 새 K8s 리소스 종류 0개 (기존 11종 외 추가 시 별도 사용자 승인)
- 새 환경변수 ≤ 5개/PR

---

## 변경 이력

| 일자 | PR | 변경 |
|------|----|----|
| 2026-05-21 | PR-A5 | 최초 작성. 기존 Sprint B 안 폐기, 축소 안으로 대체 |
| 2026-05-22 | PR-B4 | 완료 — `application-prod.yml` 4개 + Helm `SPRING_PROFILES_ACTIVE: prod` |
| 2026-05-22 | PR-B1-new | 완료 — KMS Gauge 1개 + 운영 가이드. auth/handoff는 기존 메트릭 재활용 (코드 0줄) |
| 2026-05-22 | PR-B2-new | 완료 — 알람 16개 → 8개. 본질 메트릭 정렬 + 유령 알람 5종 제거 + 중복/노이즈 3종 폐기 (신규 코드 0줄) |
