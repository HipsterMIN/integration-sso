# OnePass 운영 관리 포인트 인벤토리

> **목적**: SSO/IM 본질(인증·세션·기관연계·개인정보)에 집중하기 위해, 시스템 전체의
> 운영 관리 표면을 한 페이지에 가시화한다. 신규 운영자 온보딩 자료이자, 신규 기능을
> 추가할 때 "이미 관리 포인트가 너무 많지 않은가"를 자문하기 위한 체크리스트다.
>
> **작성 배경 (PR-A5)**: Sprint A 진행 중 운영 안정성 명목으로 관리 포인트가 누적되었다.
> "운영 가능한 시스템 = 장애가 안 나는 시스템"이 아니라 "장애가 나도 빨리 파악·복구
> 가능한 시스템"이라는 원칙을 다시 확인하고, 본 문서로 인벤토리를 동결한다.
>
> **최종 갱신**: 2026-05-21 (PR-A5)

---

## 1. SSO/IM 본질 — 운영 시 절대 흔들리면 안 되는 것

운영자가 새벽 3시에 전화를 받았을 때 다음 4가지 중 하나가 깨졌다면 즉시 대응해야 한다.

| 본질 | 책임 모듈 | 1차 지표 |
|------|----------|---------|
| **사용자가 로그인할 수 있는가** | q-sign | 인증 성공률, p95 응답시간 |
| **세션이 안전하게 유지되는가** | ido (sso, ticket) | 토큰 검증 성공률, Redis 가용성 |
| **기관 연계가 정상 작동하는가** | q-im, ido(handoff, gateway) | 핸드오프 성공률, 기관 API 응답시간 |
| **개인정보가 안전한가** | ido (crypto, audit) | KMS 가용성, 감사 로그 적재율 |

**모든 다른 모든 시스템은 위 4가지를 지원하기 위한 보조 수단**이다.

---

## 2. 외부 시스템 의존성 (8종)

운영 환경에서 OnePass가 의존하는 외부 시스템.

| # | 외부 시스템 | 용도 | 장애 시 영향 | 비고 |
|---|-----------|------|------------|------|
| 1 | **PostgreSQL** | ido / qsign 메인 DB | 즉시 인증 중단 | 단일 실패점 — HA 필수 |
| 2 | **MariaDB** | q-im (회원 DB) | 회원 조회 불가 → 인증 중단 | 단일 실패점 — HA 필수 |
| 3 | **Redis** | 세션, ShedLock | 세션 손실, 배치 중복 가능 | 캐시 — Sentinel 권장 |
| 4 | **Kafka** | 이벤트 릴레이 | 핸드오프 지연(데이터 손실 ✗) | Outbox 패턴으로 보호됨 |
| 5 | **HashiCorp Vault** | KMS Transit | 신규 암호화 불가, 기존 토큰 검증 가능 | 운영 환경 단일 |
| 6 | **Keycloak** | OIDC Broker | SSO 연계 일부 중단 | 기관 SSO에만 영향 |
| 7 | **NICE CI** | 본인확인 | 본인확인 흐름 중단 (등록만) | 외부 SaaS |
| 8 | **OTLP Collector** | 분산 추적 | 추적 데이터만 손실 | 비핵심 — 운영 정상 |

**관리 정책**:
- 1~3은 **HA 필수** (운영 SLO 직접 결정)
- 4~5는 **Outbox/캐시로 격리** (단일 실패 허용)
- 6~8은 **장애 격리 설계**되어 있음 (비핵심)

---

## 3. K8s 리소스 (Helm 템플릿 11개)

| 리소스 | 템플릿 | 운영 가치 | 유지 여부 |
|-------|--------|---------|---------|
| Deployment | `deployment-{qsign,qim,ido,batch}.yaml` | 핵심 워크로드 | ✅ 필수 |
| Service | `services.yaml` | 트래픽 라우팅 | ✅ 필수 |
| ConfigMap | `configmap.yaml` | 환경변수 (비밀 제외) | ✅ 필수 |
| Secret | `secrets.yaml` | 비밀 (DB pw, Vault token 등) | ✅ 필수 |
| **ServiceAccount** | `serviceaccount.yaml` | Vault K8s Auth (운영 prod) | ✅ 필수 — prod 한정 |
| **HPA** | `hpa.yaml` | 트래픽 폭증 대응 (prod 한정 활성) | ✅ 필수 — prod 한정 |
| **PDB** | `pdb.yaml` | 노드 드레인 보호 (prod 한정 활성) | ✅ 필수 — prod 한정 |
| **NetworkPolicy** | `networkpolicy.yaml` | 보안 (default-deny + allow) | ⚠️ 검토 — 운영 효과 vs 디버깅 비용 |

**dev/stage 정책**:
- HPA/PDB/NetworkPolicy 모두 **비활성** (`enabled: false`)이 기본
- 개발 효율 우선 — 운영 시뮬레이션은 stage에서만

---

## 4. 환경변수 인벤토리 (59개)

`values.yaml` `*.config` 섹션 기준.

| 서비스 | 개수 | 주요 영역 |
|-------|-----|----------|
| qsign | 9 | DB, Redis, Kafka, JWT |
| qim | 7 | DB(MariaDB), Kafka |
| ido | **26** | DB, Redis, Kafka, **KMS/Vault(10)**, OTLP, Slack, NICE, AnyID 등 |
| batch | 17 | 3-DB 멀티 DataSource, Redis, Kafka, ShedLock, Alert |

**ido가 비대한 이유**:
- KMS 환경변수만 10개 (PR-A2에서 추가)
- 외부 서비스 통합 (AnyID, NICE, Keycloak, NHN SKM)

**다음 점검 시 정리 후보**:
- 동시에 활성화될 수 없는 KMS 환경변수 일부 (예: AppRole용 ROLE_ID/SECRET_ID는 운영 prod에선 미사용)
- 미사용 NHN SKM 환경변수 (Vault 운영 결정 후 제거 가능)

---

## 5. Health Group 정책 (PR-A5 동결)

### Liveness (Pod 재시작 트리거)

| 서비스 | Liveness 그룹 | 포함 항목 |
|-------|--------------|---------|
| qsign / qim / ido | `livenessState` | 자체 결함만 |
| batch | `livenessState` | 자체 결함만 |

**원칙**: 외부 의존성은 절대 liveness에 넣지 않는다. (Pod 재시작이 외부 시스템 복구에 도움 안 됨 + 무한 재시작 루프 위험)

### Readiness (트래픽 차단 트리거)

| 서비스 | Readiness 그룹 | 포함 항목 | 사유 |
|-------|---------------|---------|-----|
| ido | `readinessState, db, redis, kms` | 핵심 의존성 | KMS 다운 시 ID 핸드오프 암호화 불가 — 차단 정당 |
| batch | `readinessState, db, redis` | 핵심 의존성 | DB/Redis 다운 시 잡 처리 불가 |
| qsign / qim | (Spring Boot 기본) | DB, Redis | 표준 |

**제외한 항목** (PR-A5에서 강등):
- `kafka` (batch): Kafka 단절 시 retry로 자체 복구. 트래픽 차단은 부적절.
- `outboxBacklog` (batch): Health → Metric으로 강등. 백로그 차단은 악순환.

**프로파일별 Health Group 동결** (PR-B4):
- `application.yml`의 health group은 dev/local에서 변경 실험이 가능하지만,
  `application-prod.yml`에 위 정책이 **명시 박힘** — 운영 환경은 dev 실험으로부터 격리됨.

### HealthIndicator 클래스 (운영 코드)

| 클래스 | 모듈 | Bean 이름 | 운영 가치 |
|--------|------|----------|---------|
| `VaultKmsHealthIndicator` | ido | `kms` | ✅ 유지 — KMS는 SSO 본질 |
| ~~`KafkaConnectionHealthIndicator`~~ | ~~batch~~ | ~~`kafka`~~ | ❌ **PR-A5 삭제** — Spring Boot 자동 KafkaHealthIndicator로 대체 |
| ~~`OutboxBacklogHealthIndicator`~~ | ~~batch~~ | ~~`outboxBacklog`~~ | ❌ **PR-A5 삭제** — `OutboxBacklogMetrics`로 강등 |

---

## 6. Prometheus 메트릭 인벤토리

### 기존 (운영 중)
- `batch.relay.{ido,qim,qsign}.kafka.{success,failure,dead_letter}` — Counter (per shard)
- `auth.success.total{provider, auth_level}` — Counter (q-sign AuthMetrics)
- `auth.failure.total{provider, reason}` — Counter (q-sign AuthMetrics)
- `auth.locked.total{provider}` — Counter (q-sign AuthMetrics)
- `auth.duration.seconds{provider, auth_level}` — Timer (q-sign AuthMetrics)
- `slo.{initiate,keycloak.success,keycloak.failure,webhook.enqueued}.total` — Counter (q-sign)
- Spring Boot 기본 메트릭 (`http_server_requests_seconds`, `jvm_*`, `process_*`)

### PR-A5 신규
- `onepass.outbox.pending.total{shard}` — Gauge (batch)
- `onepass.outbox.failed.total{shard}` — Gauge (batch)

### PR-B1-new 신규 (SSO 본질 메트릭 3종)
- `onepass.kms.healthy` — Gauge 1개 (ido) — `VaultKmsHealthIndicator` 재사용으로 Vault 호출 0회 추가
- (auth.success.rate / handoff.latency.p95는 **신규 코드 없이** 위 기존 메트릭 + Spring Boot 자동 메트릭으로 충족 — `docs/RUNBOOK_SSO_METRICS.md` 참조)

### 운영 가시화
- **운영자가 매일 보는 3 메트릭**: 인증 성공률(PromQL) / Handoff p95(Spring 자동) / KMS Healthy(Gauge 1개)
- 상세 PromQL 및 알람 임계값: `docs/RUNBOOK_SSO_METRICS.md`

---

## 7. Prometheus 알람 규칙 (PR-B2-new 축소 완료: 16개 → 8개)

### 7.1 현재 알람 구성 (8개)

**Critical 5개 (새벽 호출 1티어 — SSO/IM 본질)**
| 알람 | 본질 매핑 | 메트릭 |
|------|----------|--------|
| `IdoServiceDown` | 핸드오프 | `up{job="ido"}` (Actuator 자동) |
| `QSignServiceDown` | 인증 | `up{job="q-sign"}` |
| `QimServiceDown` | 식별·매핑 | `up{job="q-im"}` |
| `AuthSuccessRateLow` | 인증 성공률 < 90% | `auth_success_total` / `auth_failure_total` (q-sign AuthMetrics) |
| `KmsUnavailable` | KMS healthy=0 | `onepass_kms_healthy` (PR-B1-new) |

**Warning 3개 (Slack 채널만)**
| 알람 | 메트릭 |
|------|--------|
| `HandoffLatencyHigh` (p95 > 2s) | `http_server_requests_seconds_bucket{uri="/api/v1/handoff/issue"}` |
| `IdoHandoffErrorRateHigh` (5xx > 5%) | `http_server_requests_seconds_count{uri=~".*/handoff/issue.*",status=~"5.."}` |
| `RateLimitExceeded` (429 > 100/min) | `http_server_requests_seconds_count{status="429"}` |

### 7.2 폐기 사유 회고 (8개)

- **유령 알람 5개 (Redis/Kafka/Postgres exporter 5종)**: `prometheus.yml` 스크레이프 설정이 주석 처리되어 메트릭 수집 자체가 안 되는 상태에서 알람만 존재 → 영원히 발화되지 않으면서 "감시되고 있다"는 잘못된 안심 유발. **운영 배포 직전에 가장 위험한 패턴**. 별도 PR(B5-infra-exporter)에서 패키지로 부활.
- **중복 알람 1개 (`IdoHighErrorRate`)**: `IdoHandoffErrorRateHigh`와 본질 동일 → 통합
- **노이즈 알람 2개 (`*ApiLatencyHigh` 전체 endpoint p95, `WebhookDispatchFailureHigh`, `JvmHeapUsageHigh`)**: 본질에서 멀고 새벽 호출 정당성 없음 → 대시보드로 격하

### 7.3 축소 원칙
- "알람 피로(Alert Fatigue)" 방지 — 새벽 호출은 critical 1티어만
- warning은 Slack 채널 알림으로 격하 (전화/페이지 없음)
- **메트릭 출처가 실제 수집되는 알람만 유지** — 유령 알람 금지
- PR-B1-new 본질 메트릭 3종은 반드시 알람과 1:1 매핑
- 자세한 PromQL/임계값: `docs/RUNBOOK_SSO_METRICS.md §3`

---

## 8. 신규 기능 추가 시 자문 체크리스트

새 기능/모듈/리소스를 추가하기 전에 다음을 확인한다.

- [ ] 본 기능이 **§1의 SSO/IM 본질 4가지** 중 어디에 직접 기여하는가?
- [ ] 새 외부 시스템 의존성을 추가하는가? → 본 문서 §2에 새 행 추가가 정당한가?
- [ ] 새 환경변수가 5개 이상 추가되는가? → 기존 변수와 통합 가능한가?
- [ ] 새 K8s 리소스 종류를 추가하는가? → 정말 필요한가?
- [ ] 운영자가 새벽 3시에 본 기능 때문에 깨워질 시나리오가 있는가?
- [ ] 본 기능의 Health/Metric 노출 정책은 §5/§6 가이드를 따르는가?

**3개 이상 "아니오"가 나오면 기능 자체를 재검토**.

---

## 9. 변경 이력

| 일자 | PR | 변경 내용 |
|------|----|----|
| 2026-05-21 | PR-A5 | 최초 작성 — Sprint A 회고 결과 반영. Kafka/Outbox HealthIndicator 강등 |
| 2026-05-22 | PR-B4 | `application-prod.yml` 분리 (4개 모듈) — 로그 레벨/Tracing 샘플링/Actuator 노출/Health Group 운영 강제. Helm `SPRING_PROFILES_ACTIVE: k8s → prod` 갱신 |
| 2026-05-22 | PR-B1-new | SSO 본질 메트릭 3종 — KMS Gauge 1개 신규 + 운영 가이드(`RUNBOOK_SSO_METRICS.md`). auth/handoff는 기존 메트릭으로 충족 (코드 0줄 추가) |
| 2026-05-22 | PR-B2-new | Prometheus 알람 16개 → 8개 축소. critical 5개(본질 정렬) + warning 3개. 유령 알람 5종(Redis/Kafka/Postgres exporter 미배포) 제거, 중복/노이즈 3종 폐기. 신규 코드 0줄 |
