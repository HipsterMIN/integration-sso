# 감사 로그 이상 탐지 — 관찰 모드 (1.1 PR-8)

> 플랜 `docs/post-1.0-plan.md` §5 #7. **감사 기록이 DB 에 실린 뒤** 비동기로 규칙을 평가해 **플래그만** 남긴다. 경보·차단·인증 경로 개입은 없다.
> 3개월 기준선을 모은 뒤 규칙별 정밀도(검토 결과)로 경보 승격을 결정한다(§4). 코드: `idem-hub/.../audit/anomaly/`.

## 1. 데이터 흐름

```
AuditLogPublisher ──INSERT──▶ idem_hub.audit_log (불변)
                                      │  커서(audit_anomaly_cursor.last_audit_id, UUIDv7 순서) 뒤의 행을 30초마다 최대 200행
                                      ▼
                             AuditAnomalyScorer ──규칙 5개(AnomalyRules)──▶ idem_hub.audit_anomaly_flag
                                      │                                         │ 관리자가 검토(정탐/오탐/모름)
                                      ▼                                         ▼
                             지표 audit.anomaly.{scanned,flagged}.total   GET/POST /api/v1/admin/anomalies/** · 콘솔 "이상 징후"
```

- 점수기는 별도 스케줄 스레드다. 실패해도 감사 기록에는 영향이 없고, `audit_log` 에는 읽기만 한다.
- 복제본이 여럿이어도 커서 행 잠금(`FOR UPDATE SKIP LOCKED`)으로 한 번에 하나만 돈다.
- **첫 실행은 현재 최대 `audit_id` 에 커서를 맞춘다** — 과거 기록은 소급하지 않는다. `lag-seconds`(5초)보다 새 행은 다음 주기에 본다(비동기 발행 지연으로 `audit_id` 순서와 커밋 순서가 어긋나는 창). WAL 로 뒤늦게 재삽입된 행(1.1 PR-2)은 커서가 지나간 뒤라 점수를 내지 않는다.
- 규칙의 기준 시각은 **행의 `occurred_at`** 이다(지금 시각이 아니라). 밀린 배치를 나중에 내도 같은 답이 나온다.

## 2. 규칙 (전부 결정적·설명 가능 — 근거가 `details` 에 남는다)

| 규칙 | 방아쇠 행 | 창·기준선 | 임계(기본) | 점수 | 축(subject) |
|---|---|---|---|---|---|
| `ADMIN_LOGIN_FAILURE_BURST` | `ADMIN_LOGIN_FAILED`·`ADMIN_MFA_FAILED` | 10분 | 같은 계정 5건 | 50 + 10×초과 (최대 100) | 관리자 계정 |
| `ADMIN_NEW_SOURCE_IP` | `ADMIN_LOGIN_SUCCESS` | 30일 안 그 계정이 그 IP 에서 로그인한 적 없음 · 첫 로그인은 제외 | — | 40 (MEDIUM) | 계정@IP (24시간 1회) |
| `ADMIN_OFF_HOURS_WRITE` | ADMIN 분류 SUCCESS 중 쓰기(로그인·로그아웃·2단계·비밀번호 변경·AI·검토 제외) | `idem.hub.zone` 기준 22시~07시 또는 주말 | — | 30 (LOW) | 관리자 계정 (1시간 1회) |
| `AGENCY_FAILURE_BURST` | `outcome ≠ SUCCESS` 이고 `agency_code` 있음 | 10분 창 vs 7일 창 평균(기준선) | 20건 이상 **그리고** 기준선의 3배 이상 | 50 + 비율×5 (최대 100) | 기관 |
| `TICKET_REPLAY` | `HANDOFF_VERIFIED` FAILURE `TICKET_CONSUMED` | 10분 | 같은 기관 3건 | 70 (HIGH) | 기관 |

심각도: 점수 70 이상 HIGH, 40 이상 MEDIUM, 그 아래 LOW. 한 버스트는 같은 축에 창 안 플래그 하나(`flagExists`). 같은 감사 행·규칙은 한 번만(UNIQUE).

설정 (`idem.hub.audit.anomaly.*`, 환경변수 `IDEM_HUB_AUDIT_ANOMALY_*`): `enabled`(true) · `interval-ms`(30000) · `batch-size`(200) · `lag-seconds`(5) · `window-minutes`(10) · `baseline-days`(7) · `new-ip-baseline-days`(30) · `off-hours-start/end`(22/7) · `weekend-off-hours`(true) · `thresholds.login-failure-burst`(5) · `agency-failure-burst`(20) · `agency-failure-burst-ratio`(3.0) · `ticket-replay`(3). `mode` 는 `observe` 만 있다(`alert` 예약).

## 3. 보는 곳

- 콘솔 "이상 징후" — 플래그 목록(규칙·심각도·검토 상태·기관 필터), 행을 펼치면 근거(`details`)·행위자·IP·correlationId, 검토 버튼(정탐/오탐/모름 + 메모). 아래 표는 최근 90일 규칙별 건수·검토 결과·정밀도·§4 판단.
- API — `GET /api/v1/admin/anomalies?from&to&rule&agencyCode&severity&review=UNREVIEWED|REVIEWED|TRUE_POSITIVE|FALSE_POSITIVE|UNSURE&page&size≤200`, `GET …/stats?days=90[&agencyCode]`, `POST …/{flagId}/review {verdict, note}`. 전 역할이 읽고 검토한다(AUDITOR 포함 — 검토는 감사자의 일). 테넌트 관리자는 `agencyCode` 필수(자기 기관). 검토는 감사 `ADMIN/ANOMALY_REVIEWED` 로 남는다.
- 지표 — `audit.anomaly.scanned.total`, `audit.anomaly.flagged.total{rule}`. AI 장애 요약(1.1 PR-7)의 운영 스냅샷에 24시간 규칙별 플래그 수가 들어간다.

## 4. 3개월 기준선 뒤 경보 승격 결정

관찰 모드의 산출물은 **규칙별 정밀도** = 정탐 / (정탐 + 오탐) 이다. 운영자는 플래그를 주 1회 검토한다(미검토 필터). 기준선이 쌓이면(설치 뒤 약 3개월) 규칙마다:

| 검토 표본 | 정밀도 | 판단 |
|---|---|---|
| 20건 미만 | — | 관찰 계속 (표본 부족) |
| 20건 이상 | 0.7 이상 | **승격 후보** — `mode=alert` 설계(웹훅·이메일·콘솔 배지)를 다음 릴리스 범위로. 승격해도 차단은 하지 않는다 |
| 20건 이상 | 0.3 ~ 0.7 | 관찰 계속 — 임계·창을 조정하고 다시 센다 |
| 20건 이상 | 0.3 미만 | **규칙 조정** 또는 제거 — 그대로 경보로 올리면 운영자가 무시하게 된다 |

콘솔 통계 표의 "판단" 열이 이 표를 그대로 계산한다. 볼륨도 본다: 하루 플래그가 운영자가 검토할 수 있는 양(규칙당 수 건)을 넘으면 정밀도와 무관하게 임계를 올린다.

## 5. 한계·검증

- 규칙은 다섯 개뿐이고 통계 모델이 아니다 — 새로운 패턴은 잡지 못한다. 기준선은 기관별 7일 평균 하나(계절성 없음).
- `ADMIN_OFF_HOURS_WRITE` 는 설계상 오탐이 많다(야간 점검 작업). 정밀도가 낮게 나오면 §4 대로 조정·제거한다.
- 검증: 단위 테스트(`AnomalyRulesTest` 규칙·창·중복 억제·시간대, `AnomalyAdminControllerTest`), 통합 테스트(`AuditAnomalyIntegrationTest` — 적재 → 커서·배치 → 플래그 → 목록·통계 → 검토 → 정밀도 → 감사). 실제 운영 기준선은 설치 뒤 쌓인다.
