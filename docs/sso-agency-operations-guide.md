# Idem 자체 SSO 기관 연동 — 운영 가이드

> **명칭 안내** — 제품명은 **Idem**(구 OnePass·원패스, 2026-09-04 개명)이다. API 경로·오류 코드(`E-IDO-1xx`, `E-AGENCY-3xx`)는 1.0 에서 **동결**됐고 개명은 2.0 이다(`CHANGELOG.md` [1.0.0]). 대응표: [docs/naming.md](naming.md) §3.

> **버전**: v2.0 (2026-09-27, Idem 1.0.1 기준 전면 재작성). v1.0(2026-05-17)의 회원 조회·매핑 API(lookup/link) 운영, `ci_hash`·`qim_user_id` 지표, Java Agent 배포 절차는 **0.x 설계**이며 1.0 에는 없다(Agent 는 §10).
> **대상 독자**: 연동기관 운영 개발자·인프라·SRE. 운영기관(설치본 운영) 쪽 절차는 `docs/manuals/administrator-manual.md`·`docs/sso-im-operations-manual.md`.
> **전제**: 개발자 레퍼런스(`docs/sso-agency-developer-guide.md`) 숙지

---

## 목차

1. [배포 토폴로지와 네트워크](#1-배포-토폴로지와-네트워크)
2. [기관 측 배포와 설정 외재화](#2-기관-측-배포와-설정-외재화)
3. [비밀·설정 회전 절차](#3-비밀설정-회전-절차)
4. [모니터링 — 지표와 알람](#4-모니터링--지표와-알람)
5. [장애 시나리오와 대응](#5-장애-시나리오와-대응)
6. [배포·업그레이드](#6-배포업그레이드)
7. [보안 운영](#7-보안-운영)
8. [로그 분석](#8-로그-분석)
9. [긴급 대응 런북](#9-긴급-대응-런북)
10. [Java Agent — 1.0 에서의 상태](#10-java-agent--10-에서의-상태)

---

## 1. 배포 토폴로지와 네트워크

### 1.1 토폴로지

```
┌────────────────────────── 기관 인프라 ──────────────────────────┐
│  기관 SSO (기존)    기관 애플리케이션 서버                          │
│                     ├── OIDC 라이브러리 (옵션 C)  또는              │
│                     ├── Handoff verify 클라이언트 (옵션 A·B)        │
│                     ├── 웹훅 수신기 (선택)   /idem/webhook          │
│                     ├── BCL 수신기 (옵션 C, 선택)                   │
│                     └── 이벤트 피드 폴러 (선택)                     │
└───────────────┬──────────────────────────────▲──────────────────┘
                │ HTTPS 아웃바운드                │ HTTPS 인바운드 (선택)
                ▼                              │
┌───────────────────────────── Idem 설치본 (운영기관) ─────────────┐
│  idem-gate  {IDEM_PUBLIC_URL_GATE}:443   OIDC issuer · 로그인 · BCL 송신 │
│  idem-hub   {IDEM_PUBLIC_URL_HUB}:443    verify · 이벤트 피드 · 웹훅 송신  │
│  (Keycloak · registry · authz · PostgreSQL · Redis — 내부, 기관 비노출) │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2 방화벽 규칙

| 방향 | 출발 → 목적 | 포트 | 옵션 |
|---|---|---|---|
| 아웃바운드 | 기관 앱 → `{gate}` | 443 | C: Discovery·token·userinfo·JWKS·end_session |
| 아웃바운드 | 기관 앱 → `{hub}` | 443 | A·B: verify · 이벤트 피드 · CAST · 게이트웨이 |
| 인바운드 | idem-hub → 기관 웹훅 URL | 443 | 선택 |
| 인바운드 | idem-gate/Keycloak → BCL URI | 443 | C 선택. **공개 http(s) 호스트만** 등록 가능(내부 IP·localhost 는 프로파일 저장 시 거부) |
| 인바운드 | idem-hub → `{bridge}` · `{apacheGate}` · `{ssoDomain}` | 443 | BRIDGE·APACHE_GATE·INTERNAL_SSO 만 |
| 브라우저 | 사용자 → `{gate}` | 443 | 로그인 화면 |

기관 → Idem 방향은 운영기관 리버스 프록시의 공개 URL 2개(gate·hub)뿐이다. Idem → 기관 방향은 운영기관 hub 의 아웃바운드 IP 를 허용 목록에 넣는다. Idem 이 기관 DB·회원 API 를 호출하는 일은 없다.

### 1.3 버전

| 컴포넌트 | 버전 | 비고 |
|---|---|---|
| Idem 설치본 | 1.0.1 | 운영기관이 관리. 1.0 → 1.x 는 이미지 태그 교체 |
| `idem-sdk-java` | 1.0.1 | Java 8+, 선택 |
| `idem-tenant-sample` | 1.0.1 | 참조·시험용, 운영 배포 안 함 |

---

## 2. 기관 측 배포와 설정 외재화

### 2.1 설정

```yaml
# 기관 앱 application.yml (예)
idem:
  gate-url: ${IDEM_GATE_URL:https://idem-gate.example.go.kr}      # 옵션 C issuer 베이스
  hub-url:  ${IDEM_HUB_URL:https://idem-hub.example.go.kr}        # 옵션 A verify
  agency:
    code: ${IDEM_AGENCY_CODE:AGENCY_001}
    api-key: ${IDEM_AGENCY_API_KEY}                                # 옵션 A·이벤트 피드·SDK
    webhook-secret: ${IDEM_WEBHOOK_SECRET}                        # 웹훅 수신 시
  oidc:
    client-id: idem-svc-AGENCY_001
    client-secret: ${IDEM_OIDC_CLIENT_SECRET}                     # 옵션 C
  verify:
    connect-timeout-ms: 3000
    read-timeout-ms: 5000
    circuit-breaker: { failure-rate: 50, open-seconds: 10 }         # 참조 구현 값
```

비밀 3종(API 키·client secret·웹훅 비밀)은 환경변수·Vault·Secrets Manager 로 주입하고 파일이면 600 권한. 소스·Git·메신저 금지.

옵션 A(DIRECT) 의 브라우저 진입(1.1): 로그인 버튼은 `{hub-url}/api/v1/handoff/login?service={code}&callback={우리 콜백}&state=…` 로 보내고, 콜백 URL 은 운영기관 프로파일의 `callbackWhitelist` 에 등록돼 있어야 한다(밖이면 사용자에게 Idem 오류 화면 403 이 보이고 우리 콜백은 호출되지 않는다). 콜백은 `ticketId` 외에 `error=E-IDO-…` 로 올 수 있으니 거부 화면을 준비한다.

### 2.2 배포 전 확인

```bash
# 옵션 C — Discovery 가 공개 gate URL 을 issuer 로 내는지
curl -s https://idem-gate.example.go.kr/realms/idem/.well-known/openid-configuration | jq '.issuer, .token_endpoint'
# → "https://idem-gate.example.go.kr/realms/idem" 이어야 한다 (내부 Keycloak 주소면 운영기관 설정 오류)

# 옵션 A·SDK — 기관 키가 살아 있는지 (401 이면 키·기관 상태 문제)
curl -s https://idem-hub.example.go.kr/api/v1/agency/gateway/status/AGENCY_001 \
  -H "X-Agency-Code: AGENCY_001" -H "X-Agency-Key: ${IDEM_AGENCY_API_KEY}" | jq '.active'

# verify 경로 — 잘못된 티켓으로 401/404 가 아닌 4xx 계약 응답이 오는지
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://idem-hub.example.go.kr/api/v1/handoff/verify \
  -H "Content-Type: application/json" -H "X-Agency-Code: AGENCY_001" -H "X-Agency-Key: ${IDEM_AGENCY_API_KEY}" \
  -d '{"ticketId":"00000000-0000-0000-0000-000000000000"}'      # 410/409/404 계열이면 인증은 통과한 것

# 웹훅 수신기 — 서명 검증이 실제로 거부하는지
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://www.xxxx.go.kr/idem/webhook \
  -H "X-Webhook-Signature: sha256=00" -H "X-Webhook-Timestamp: $(date +%s)" -d '{}'      # 401 기대
```

운영기관 관리 콘솔의 **정책 시뮬레이션**(`POST /api/v1/admin/services/{code}/policy/simulate`)으로 인증수준·점검 시간·할당 규칙이 기대대로 판정되는지 함께 확인한다.

---

## 3. 비밀·설정 회전 절차

| 대상 | 발급·회전 주체 | 절차 | 주의 |
|---|---|---|---|
| 기관 API 키 | 운영기관 `POST /api/v1/admin/agencies/{code}/rotate-key` | 응답에 새 키가 **한 번만** 나온다. 서버는 해시만 저장하므로 다시 볼 수 없다 | 구 키는 즉시 무효 — **병행 기간 없음**. 기관 배포 창을 맞춘 뒤 회전하고, 기관은 새 키를 받는 즉시 재기동 |
| OIDC client secret | 운영기관 `POST /api/v1/admin/services/{code}/oidc-client/secret` | 응답에 secret 한 번만. 기관 설정 교체 후 재기동 | 시험 중 만든 secret 은 승인 전에 한 번 더 회전한다(온보딩 가이드) |
| 웹훅 서명 비밀 | 운영기관 | 1.0.x 에는 회전 관리 API 가 없다 — 운영기관 DB 작업(`idem_hub.agency_webhook_config`) | 기관 수신기는 신·구 두 비밀을 잠시 함께 받도록 만들면 무중단 |
| 콜백·redirect URI | 운영기관 프로파일 PUT (`protocol.oidc.redirectUris` / `protocol.endpoints.callbackWhitelist`) | `X-Change-Reason` 헤더로 사유 기록 | OIDC_RP 는 저장 시 Keycloak client 가 갱신된다 |
| CAST 공개키 | 운영기관 `IDEM_HUB_CAST_PRIVATE_KEY` 교체 | 기관은 `GET /api/v1/agency/cast/public-key` 재조회 | 캐시하면 TTL 을 짧게 |

로테이션 주기 권장: API 키·client secret 6개월, 웹훅 비밀 12개월, 유출 의심 시 즉시(§9 런북 D).

---

## 4. 모니터링 — 지표와 알람

### 4.1 기관 측 지표

| 지표 | 임계치 | 조치 |
|---|---|---|
| verify P95 응답시간 | > 2초 WARN / > 5초 CRITICAL | 네트워크·Idem 상태 확인. 티켓 60초 안에 끝나야 한다 |
| verify 오류율 (5xx·타임아웃) | > 1% WARN / > 5% CRITICAL | 서킷브레이커 OPEN 여부, 운영기관 연락 |
| verify 401 (`INVALID_AGENCY_CREDENTIALS`) | 1건이라도 | 키 회전 누락·기관 비활성 |
| verify 409/410 비율 | 급증 | 재사용·중복 콜백 호출(프런트 이중 제출) 점검 |
| OIDC 토큰 교환 403 `access_denied` 비율 | 급증 | `E-IDO-120` 미할당 · `E-AGENCY-305` 점검 · 사용자 상태 |
| 429 (`E-AGENCY-306` / `temporarily_unavailable`) | 발생 | 프로파일 `limits.tps/daily` 상향 협의 |
| 웹훅 서명 실패·타임스탬프 편차 | 발생 | 비밀 불일치, 서버 시각 동기화(NTP) |
| 웹훅 미수신 (`USER_LOGOUT` 등) | 로그아웃 뒤 세션 잔존 신고 | 운영기관 `webhookEndpoint`·아웃박스 상태 |
| 이벤트 피드 `hasMore=true` 연속 | 5회 이상 | 폴링 주기·`limit` 상향 |
| BCL 수신 실패 | 발생 | URI 공개 호스트 여부, `sid` 매핑 |
| 계정 연결 수동 처리 비율 | > 20% | 연결 속성(이메일 등) 프로파일 요청·기관 DB 품질 |

### 4.2 Prometheus 계측 예 (Spring Boot Actuator)

```java
// verify 호출 계측
Timer.Sample s = Timer.start(registry);
int status = idemVerifyClient.verify(ticketId, cid);
s.stop(Timer.builder("idem.handoff.verify.duration")
        .tag("status", String.valueOf(status)).register(registry));
if (status >= 500) registry.counter("idem.handoff.verify.error", "status", String.valueOf(status)).increment();
// 웹훅
registry.counter("idem.webhook.received", "eventType", eventType, "result", valid ? "ok" : "bad_signature").increment();
```

### 4.3 알람 예 (Alertmanager)

```yaml
groups:
- name: idem-agency-alerts
  rules:
  - alert: IdemVerifyHighLatency
    expr: histogram_quantile(0.95, rate(idem_handoff_verify_duration_seconds_bucket[5m])) > 2
    for: 2m
    labels: { severity: warning }
    annotations: { summary: "Idem verify P95 2초 초과" }
  - alert: IdemVerifyErrorRate
    expr: rate(idem_handoff_verify_error_total[5m]) / rate(idem_handoff_verify_duration_seconds_count[5m]) > 0.05
    for: 3m
    labels: { severity: critical }
    annotations: { summary: "Idem verify 오류율 5% 초과 — 운영기관 상태 확인" }
  - alert: IdemAgencyKeyRejected
    expr: increase(idem_handoff_verify_duration_seconds_count{status="401"}[5m]) > 0
    labels: { severity: critical }
    annotations: { summary: "기관 API 키 거부 — 키 회전 누락 또는 기관 비활성" }
  - alert: IdemWebhookBadSignature
    expr: increase(idem_webhook_received_total{result="bad_signature"}[10m]) > 0
    labels: { severity: warning }
    annotations: { summary: "웹훅 서명 실패 — 비밀 불일치 또는 시각 편차" }
```

### 4.4 Idem 측에서 볼 수 있는 것 (운영기관)

- 관리 포트(Helm 기본 9090)의 `/actuator/health`(liveness·readiness). gate·registry·authz 는 `/actuator/prometheus`(`slo.*`·`idem.kms.healthy`·`idem.outbox.*`), hub 는 1.0.x 미등록(알려진 제한). 1.1 부터 hub 감사 경로 지표 `audit.wal.pending.lines`(DB 장애로 WAL 에 대기 중인 감사 항목, 0 이 정상)·`audit.lost.total`(0 이어야 한다).
- 관리 콘솔 감사 검색(`GET /api/v1/admin/audit?agencyCode=…`) — 로그인·거부·관리 행위가 `correlationId` 와 함께 남는다.
- 기관 통계 `GET /api/v1/admin/agencies/{code}/stats`.

---

## 5. 장애 시나리오와 대응

### 5.1 Idem 전체 장애 (gate 또는 hub 다운)

**영향**: 옵션 C 는 authorize 단계에서 실패, 옵션 A 는 verify 5xx·타임아웃(→ 서킷브레이커 OPEN → `HOLD` 처리). **이미 만든 기관 세션은 유지된다**(기관이 세션을 소유). Idem 은 fail-closed 라 통과시키는 경로가 없다.

```bash
# 1. 상태 확인 (30초)
curl -sI https://idem-gate.example.go.kr/realms/idem/.well-known/openid-configuration | head -1
curl -sI https://idem-hub.example.go.kr/api/v1/agency/gateway/status/AGENCY_001 -H "X-Agency-Code: AGENCY_001" -H "X-Agency-Key: ${IDEM_AGENCY_API_KEY}" | head -1
# 2. 기관 화면: "Idem 로그인 일시 중단 — 기관 아이디로 로그인하세요" 배너 (기능 토글)
# 3. 운영기관 On-call 연락, correlationId 와 시각 전달
# 4. 복구 후: 실제 계정으로 로그인 E2E, 서킷브레이커 CLOSED 확인, 웹훅 밀린 것(재시도) 수신 확인
```

### 5.2 verify 401 — `INVALID_AGENCY_CREDENTIALS`

원인: API 키 회전 뒤 미반영, 기관 비활성(`deactivate`), 헤더 이름 오타(`X-Agency-Code`/`X-Agency-Key`). 조치: 운영기관에 기관 활성 상태와 마지막 회전 시각 확인 → 새 키 수령 → 재기동. `E-IDO-108`(티켓 서명 실패)은 키가 아니라 티켓 위변조·설치본 키 교체 직후이므로 운영기관에 알린다.

### 5.3 특정 사용자만 로그인 실패

| 증상 | 원인 | 조치 |
|---|---|---|
| OIDC `403 access_denied E-IDO-120` / Handoff `GUEST` | 이 서비스에 미할당 | 운영기관 할당 등록, 규칙 할당(1.1: 인증수준·제공자 조건 또는 다른 서비스의 역할 보유로 자동 할당 — authz `assignment-rules`) 또는 프로파일 `policy.assignment.selfSignup` |
| 어제는 됐는데 오늘 `E-IDO-120` | 규칙 할당(`source=RULE`)이 재평가에서 불일치(예: 오늘은 L1 로 로그인) 또는 규칙 비활성화 → 회수됨. 기관에는 `ASSIGNMENT_CHANGED{UNASSIGNED}` 가 갔다 | 운영기관 감사(`AUTHZ`, `UNASSIGN` 사유 "규칙 재평가 불일치"/"규칙 비활성화")로 확인 |
| `E-IDO-114` | 필수 속성 없음 | 사용자 프로필 보완 또는 프로파일 `identity.attributes.required` 조정 |
| `REJECTED` / `E-AGENCY-305` | 사용자 상태(정지·탈퇴) / 점검 시간 | 운영기관 확인 |
| 인증수준 미달 | `policy.minAuthLevel` 보다 낮은 방법으로 로그인 | 사용자에게 L2 이상 방법 안내 |
| 연결 실패(수동 연결 화면 반복) | 기관 DB 에 연결 속성 없음·중복 | §7 연결 기준 재검토 |

### 5.4 429 — 한도 초과

프로파일 `limits.tps/daily`(1.0.1 부터 실제 적용, 기본 200 tps·1,000,000/일). `Retry-After` 를 존중하고, 실제 트래픽이 크면 운영기관에 상향을 요청한다. 프런트 이중 제출로 verify 가 두 번 나가면 두 번째는 409 이므로 한도와 구분한다.

### 5.5 웹훅 서명 실패 / 미수신

```bash
# 서버 시각 (±5분 밖이면 거부)
timedatectl | grep synchronized
# 비밀 확인 — 운영기관과 같은 값인지 (첫 8자 해시 비교 등, 원문은 주고받지 않는다)
# 미수신: 운영기관에 webhookEndpoint·active 확인 요청. hub 는 실패를 재시도하고 아웃박스에 남긴다
# 대안: 이벤트 피드 폴링으로 보완 (GET /api/v1/agency/events)
```

### 5.6 로그아웃 전파 안 됨 (옵션 C)

1.1 부터 Idem 쪽 SLO 의 Keycloak 세션 종료가 실패하면 hub 가 재시도 큐(`slo_idp_logout_retry`)에 넣어 최대 5회(10s~160s 백오프) 다시 시도하고, 끝내 실패하면 감사 `SLO_IDP_LOGOUT_FAILED` 가 남는다. 운영기관 감사 조회에서 이 행위가 보이면 Keycloak 상태를 확인한다.

`backchannelLogoutUri` 가 프로파일에 없거나 내부 호스트라 저장 시 거부됐을 수 있다(공개 http(s) 만 허용). Idem 쪽 세션이 남는 현상은 운영기관 설정(`KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET`) 문제로 gate 로그 `[KeycloakLogout]` 에 남는다.

---

## 6. 배포·업그레이드

### 6.1 기관 앱

verify·OIDC 콜백은 무상태이므로 일반 롤링 배포로 충분하다. 세션 저장소가 인스턴스 로컬이면 절대 만료(기본 8시간) 안에 재로그인이 생길 수 있다. 카나리 지표는 §4.1 의 verify 오류율·403 비율.

### 6.2 Idem 설치본 (운영기관)

1.0 → 1.x 는 이미지 태그만 올리면 Flyway 가 적용된다. 0.x → 1.0 은 `scripts/upgrade/rename-db-1.0.sh` + 첫 기동 자동 이름 변경(`docs/manuals/installation-manual.md` §5). issuer 가 `…/realms/idem` 으로 바뀌는 업그레이드는 기관 RP 설정도 함께 바꿔야 하므로 운영기관이 사전 공지한다.

### 6.3 SDK

```bash
./gradlew dependencies | grep idem-sdk-java          # 현재 버전
# CHANGELOG(idem-sdk-java/CHANGELOG.md) 의 BREAKING 확인 → 스테이징 → 롤링
```

---

## 7. 보안 운영

```
✅ 권장
  - 비밀 3종은 Vault/Secrets Manager 또는 환경변수. 파일이면 600
  - X-Agency-Key·client secret 은 로그·오류 화면·APM 에 절대 출력하지 않는다
  - 모든 Idem 호출에 X-Correlation-Id, 사용자 오류 화면에도 표시
  - 웹훅: 서명 + 타임스탬프 ±5분 + eventId 중복 제거, 상수 시간 비교
  - verify 는 서버 간 호출만. 브라우저에서 hub 를 직접 부르지 않는다
  - TLS 1.2+ 만, 인증서 만료 30일 전 갱신

❌ 금지
  - 소스·Git 에 비밀 커밋
  - qimUserId 저장 (연결 키는 agencySubjectId)
  - 토큰·티켓을 URL 쿼리에 남기기 (CAST 는 POST 폼 자동 제출)

정기 점검 (분기)
  □ API 키·client secret 회전 (§3)
  □ 방화벽 허용 목록 — Idem hub 아웃바운드 IP 변경 여부
  □ BCL·웹훅 URI 가 여전히 공개 호스트인지
  □ idem_subject_id 유니크 제약·인덱스 존재
  □ 운영기관 감사 조회로 우리 기관의 거부 코드 분포 확인
```

---

## 8. 로그 분석

### 8.1 기관 측

```bash
# verify 결과 분포 (참조 구현 로그 태그 [AgencyEntry]·[IdoVerify])
grep -E "\[AgencyEntry\].*(APPROVED|GUEST|HOLD|거부)" app.log | awk '{print $NF}' | sort | uniq -c
# 특정 사용자 문의 → correlationId 로 추적
grep "correlationId=8d5c…" app.log
# 웹훅 수신
grep "\[WebhookInbound\]" app.log | grep -v "signatureValid=true"
```

### 8.2 Idem 측 (운영기관에 요청)

| hub 로그 태그 | 뜻 |
|---|---|
| `[HandoffAgencyKeyInterceptor] API Key 검증 실패` | 401 원인 |
| `[HandoffServiceImpl]` / `E-IDO-10x` | 티켓 상태 |
| `[OidcRpAccess]` / `[OidcRpClientProvisioner]` | 토큰 교환 판정 / client 생성 |
| `[WebhookDispatcher]` · `[WebhookRelay]` | 웹훅 적재·발송·재시도 |
| gate `[KeycloakProxy]` · `[KeycloakLogout]` | 프록시 차단(400) · 세션 종료 |

감사 조회: `GET /api/v1/admin/audit?agencyCode=AGENCY_001&category=AUTHZ` (관리자 세션 필요).

---

## 9. 긴급 대응 런북

### 런북 A: Idem 로그인 전체 불가

```
1. 어느 옵션인지 확인 — C: authorize 응답코드 / A: verify 응답코드 (30초)
2. Idem 상태 — §5.1 의 두 curl. 5xx·타임아웃이면 Idem 장애 → 운영기관 On-call
   200 인데 실패면 기관 측:
   - C: redirect_uri 가 프로파일 redirectUris 와 정확히 같은가, client secret 최근 회전?
   - A: 콜백 URL 이 callbackWhitelist 에 있는가, API 키 401?
   - TLS 만료: openssl s_client -connect www.xxxx.go.kr:443 </dev/null | openssl x509 -noout -dates
3. 기관 화면 배너 + 자체 로그인 유지
4. 복구 후 E2E, correlationId 로 인시던트 기록 (24시간 내)
```

### 런북 B: 일부 사용자만 실패

```
1. 사용자 오류 화면의 correlationId 수집
2. 응답 코드 분류 — E-IDO-120 (미할당) / E-IDO-114 (속성) / REJECTED / 인증수준 / GUEST
3. 운영기관 감사 조회로 판정 사유 확인 → 할당·프로파일·사용자 상태 조치 (§5.3)
4. 계정 연결 문제면 idem_subject_id 매핑 확인 — NULL 이면 연결 화면 재안내, 다른 값이면 운영기관과 스킴 변경 여부 확인
```

### 런북 C: 로그아웃·탈퇴가 기관에 반영 안 됨

```
1. 웹훅 수신 로그 — 도착했는데 서명 실패인지, 아예 없는지 (§5.5)
2. 없으면 운영기관에 webhookEndpoint·active·아웃박스 상태 확인 요청
3. 임시: 이벤트 피드 폴링으로 USER_LOGOUT·MEMBER_WITHDRAWN 을 가져와 세션·계정 반영
4. 옵션 C 면 BCL URI 등록 여부 (§5.6)
```

### 런북 D: API 키·secret 유출 의심

```
1. 운영기관에 즉시 회전 요청 — rotate-key / oidc-client/secret (구 값 즉시 무효)
2. 새 값 주입 → 기관 앱 재기동 (병행 기간 없음, 배포 창 조율)
3. 유출 창 동안의 verify·userinfo 호출을 운영기관 감사 조회로 점검 (우리 기관 코드로 낯선 IP·시각)
4. 웹훅 비밀도 함께 바꾼다 (§3)
5. 인시던트 기록, 비밀 보관 경로 재점검 (§7)
```

---

## 10. Java Agent — 1.0 에서의 상태

`idem-agent` 가 호출하는 검증 API(`/api/v1/agency/token/verify`)는 Idem 1.0.x 어느 서버에도 없다. 따라서 이 가이드 v1.0 에 있던 Agent 배포·JVM 옵션·롤링 재시작·바이패스 절차는 1.0 운영에 해당하지 않는다. 레거시 WAS 는 콜백 서블릿 + verify(옵션 A) 또는 옵션 C 로 붙이고, 에이전트는 1.x 에서 hub 검증 API 가 생긴 뒤 다시 다룬다. 테스트베드(`idem-agent-testbed`)는 mock 서버 검증용이다.

---

*운영 이슈 접수: 운영기관 연동 지원 창구(설치본마다 다름). 긴급 연락: 운영기관 On-call 스케줄.*
