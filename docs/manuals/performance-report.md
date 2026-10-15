# Idem 성능 시험 보고서 — 양식 + 1.1.2 샌드박스 측정

> 1.1.2 G2-8 (`docs/post-1.0-plan.md` §3 "성능 보고"). 측정 자산은 `k6/`(시나리오)·`scripts/perf/run-k6.sh`(실행기), 결과 원본은
> `k6/reports/<일시>/`(요약 `summary.md`, k6 요약 JSON, 로그, `environment.txt`). **GS 시험 환경에서 같은 실행기로 다시 돌려 §4.2 를 채우면
> 제출본이 된다.** 여기 적힌 수치는 샌드박스(단일 호스트) 참고치다 — §2.2.

## 0. 요약

| 항목 | 기준(SLO) | 샌드박스 2026-10-15 (§4.1) | 시험 환경 (§4.2) |
|---|---|---|---|
| Handoff 브라우저 로그인 — 로그인+발급 서버 처리 p95 (`handoff_login_continue_duration`) | < 2,000 ms | **27.4 ms** (p99 38.9) ✅ | 기입 |
| Handoff 발급 API p95 @100 rps (`handoff_issue_duration{phase:sustain}`) | < 2,000 ms, 429 없음 | **14.9 ms** (p99 28.2), 429 0 ✅ | 기입 |
| Handoff 검증 API p95 @100 rps (`handoff_verify_duration{phase:sustain}`) | < 2,000 ms | **16.2 ms** (p99 29.1) ✅ | 기입 |
| 기관 한도 200 tps — 300 rps 로 넘길 때 429 `E-AGENCY-306` 비율, 회복 뒤 0 | ≥ 17 % (기대 ≈ 33 %) / 0 | **31.5 %** (1,646 / 5,223) / 0 ✅ | 기입 |
| 표준 OIDC 토큰 교환 p95 (`oidc_token_duration`, hub 정책 판정 포함) | < 2,000 ms | **48.0 ms** (p99 66.8), 거부 0 ✅ | 기입 |
| 공통: HTTP 실패율 / 체크 통과율 | < 1 % / > 99 % | 0.00 % / 100 · 99.97 · 100 % ✅ | 기입 |

측정 중 **결함 2건**(감사 이력·기관 이벤트 유실, §5.2)을 찾아 같은 PR 에서 고쳤고, 위 수치는 **수정본**으로 다시 잰 값이다.

## 1. 측정 항목 ↔ 시나리오 ↔ 문턱

| 플랜 §3 항목 | 시나리오 | 무엇을 재는가 | 지표 | 문턱 |
|---|---|---|---|---|
| Handoff p95 | `k6/scripts/10-handoff-login.js` | 이용자가 기관 화면에서 "Idem 으로 로그인" → 코어 로그인 프런트 진입(①) → MOCK 본인확인 복귀(②: complete → registry 확정 → FE 세션 → 발급 → 302 콜백) → 기관 서버 verify(③). 반복마다 새 브라우저, VU 마다 다른 사람 | `handoff_login_entry_duration`(①) · `handoff_login_continue_duration`(②) · `handoff_login_e2e_duration`(①+②) · `handoff_verify_duration`(③) | ② p95 < 2,000 · ①+② < 3,000 · ③ < 2,000 ms |
| Handoff p95 (발급 서비스) | `k6/scripts/11-handoff-issue-api.js` | 로그인된 FE 세션으로 `POST /api/v1/handoff/issue` → `verify` 를 도착률 고정으로: warmup 20 rps 30 s → **sustain 100 rps 120 s** → limit 300 rps 20 s → recovery 50 rps 20 s | `handoff_issue_duration{phase:sustain}` · `handoff_verify_duration{phase:sustain}` | p95 < 2,000 ms, sustain·recovery 429 = 0 |
| 200 tps | 〃 limit 구간 | 기관 한도(`AgencyRateLimiter`, 초 단위 창, 프로파일 `limits.tps` 없으면 설치본 기본 200)를 넘기면 429 `E-AGENCY-306` | `handoff_issue_rate_limited{phase:limit}` | ≥ 17 %(= 기대 (300−200)/300 의 절반, 초 경계 여유) |
| 토큰 교환 p95 | `k6/scripts/12-oidc-token.js` | 기관 RP 의 Authorization Code + PKCE: gate 프런트 authorize(사전검사) → Keycloak 로그인 화면·자격 검증 → **토큰 교환(gate → Keycloak 토큰 → hub 정책 판정 → 허용)** → userinfo(`idem_state`) → 로그아웃(측정 제외). 5 → 10 VU | `oidc_token_duration` ★ · `oidc_login_duration` · `oidc_authorize_duration` · `oidc_userinfo_duration` · `oidc_e2e_duration` | 토큰 교환 p95 < 2,000 · 로그인 < 3,000 · 끝-끝 < 5,000 ms, 거부 0 |
| (게이트) | `k6/scenarios/smoke.js` | CI `k6 Smoke Test` — 헬스·제공자 목록·MOCK 라운드트립·에디션 404·발급 응답 | checks | 100 % |

모든 시나리오에 `http_req_failed < 1 %`·`checks > 99 %` 를 같이 건다. 11 번의 429 는 기대 응답(`expectedStatuses`)이라 실패율에 넣지 않는다. 문턱은 `RUNBOOK_SSO_METRICS.md` §1.2 의 운영 경보(p95 > 2 s warning)와 2026-05 보고서의 SLO 를 그대로 가져왔다.

## 2. 환경

### 2.1 구성 (양식 — 시험 환경 열을 채운다)

| 항목 | 샌드박스 2026-10-15 | 시험 환경 |
|---|---|---|
| 대상 버전 | 1.1.2 (main 809e215) boot jar + PR #272 의 수정(V32·`TraceparentFilter`) = **1.1.3** 코드 상태 | |
| 배포 형태 | boot jar 직접 기동 — `infra/docker/compose.install.yml` 과 같은 환경변수 계약(CI `k6 Smoke Test` 잡의 기동 레시피) | compose / Helm (`docs/manuals/installation-manual.md`) |
| 호스트 | **1 대**, 4 vCPU, 15 GB, Linux 6.18 — PostgreSQL·Redis·Keycloak·registry·authz·gate·hub·k6 **동거** | 앱 / DB / 발생기 분리 권장 |
| PostgreSQL / Redis | 16 / 7 (로컬 인스턴스) | |
| Keycloak | 24.0.5 `start-dev`(PG 스키마 `keycloak`, realm `idem` import, `KC_HOSTNAME_URL` = gate) | 설치본 이미지 `quay.io/keycloak/keycloak:24.0` |
| 네트워크 | loopback, http (TLS 없음) | https, 실 네트워크 RTT |
| 부하 발생기 | k6 v1.4.0, 대상과 같은 호스트 | 별도 호스트의 k6 v1.x |
| 대상 설정 차이 | hub `IDEM_PLUGINS_MOCK_AUTH_ENABLED=true` + 기본 프로파일(설치 검증과 같다, `docs/install.md` §5) · hub `IDEM_HUB_AUTH_RL_ENABLED=false` · gate `IDEM_GATE_FRONT_RL_ENABLED=false`(발생기가 한 IP — IP 레이트리밋만 끈다, 기관 한도 200 tps 는 그대로) · Kafka 없음(outbox 경로) | 같은 네 가지, 측정 뒤 되돌림 |
| 시험 서비스 | `PERF_HO`(DIRECT, `callbackWhitelist` 1개, `limits` 없음 = 200 tps) · `PERF_RP`(OIDC_RP, `redirectUris` 1개, client `idem-svc-PERF_RP`) — 실행기가 만든다 | 〃 |
| 시험 사용자 | MOCK: VU 마다 `k6-u<n>` / OIDC: realm import 의 `poc-testuser` | OIDC: 시험용 realm 사용자 |

### 2.2 샌드박스 값을 참고치로만 보는 이유

- 발생기와 다섯 JVM·DB·Redis·Keycloak 이 4 vCPU 를 나눠 쓴다 — 11 번 limit 구간(300 rps)과 12 번 로그인(Keycloak 비밀번호 해시)에서 CPU 가 포화된다. **sustain 구간의 p95** 가 발급 서비스의 값이고, 전 구간 합산 p95 는 포화 구간이 섞인 값이다(§4.1 의 두 줄을 구분해 읽는다).
- loopback·http 라 네트워크 RTT·TLS 핸드셰이크가 없다. 실 환경은 +수 ms~수십 ms.
- 본인확인은 MOCK 제공자다 — 실 제공자(NICE 등)의 외부 응답 시간은 Idem 의 성능이 아니므로 측정에서 뺀다(`RUNBOOK_SSO_METRICS.md` 의 Handoff p95 도 hub 처리 시간이다).
- 장시간(soak)·한계 탐색(stress)은 하지 않았다 — 2026-05 의 `k6/legacy/` 결과만 있다(Mock 서버 기준). 시험 환경에서 필요하면 11 번의 sustain 을 `DURATION` 없이 길게 돌린다(§6).

## 3. 절차 (시험 환경에서 그대로)

1. 설치본을 띄우고(`docs/manuals/installation-manual.md`) 대상 쪽 준비 네 가지를 적용한다 — `k6/README.md` §2 "시험 대상 쪽 준비" 표(MOCK 제공자·기본 프로파일, hub·gate IP 레이트리밋 off, Keycloak 시험 사용자).
2. `IDEM_EDITION=core scripts/ci/install-smoke.sh` 가 통과하는지 본다(⑦b Handoff 브라우저 진입·③ OIDC client 프로비저닝이 성능 시나리오의 전제다).
3. 발생기에서:
   ```bash
   export IDEM_ADMIN_PASSWORD=… IDEM_ADMIN_TOTP_SECRET=…   # 관리자(2단계 등록 완료)
   export OIDC_PASSWORD=…                                   # Keycloak 시험 사용자 (OIDC_USER 기본 poc-testuser)
   export HUB_URL=https://<hub> GATE_URL=https://<gate>     # 공개 주소와 다르면 HUB_PUBLIC_URL / GATE_PUBLIC_URL 도
   PERF_LABEL="GS 시험 환경 — <호스트 사양>" PERF_BUILD=1.1.x scripts/perf/run-k6.sh
   ```
   먼저 `PERF_DRY_RUN=1` 로 1 VU × 1 반복을 돌려 자격·계약을 확인하면 11 분을 아낀다. 실행기 종료 코드 0 = 전 시나리오 문턱 통과, 2 = 미달 시나리오 있음.
4. `k6/reports/<일시>/summary.md` 의 값을 §4.2 와 §0 의 '시험 환경' 열에 옮기고, 결과 디렉터리(요약 JSON·로그·`environment.txt`, 모두 작다)를 커밋한다.
5. 되돌린다: MOCK 제공자 off + `prod` 프로파일, IP 레이트리밋 on, 시험 서비스 `PERF_HO`·`PERF_RP` 는 콘솔에서 `INACTIVE`(또는 삭제).

## 4. 결과

### 4.1 샌드박스 2026-10-15 (수정본, `k6/reports/2026-10-15-sandbox/`)

실행 2026-10-15 02:10 UTC, k6 v1.4.0, hub 1.1.2(main 809e215) + PR #272 수정. 세 시나리오 모두 문턱 통과(`summary.md`). hub 로그: 발급 25,693 건에 감사 이력·outbox 적재 실패 **0 건**(수정 전 첫 실행은 발급마다 실패 — §5.2), 429 `E-AGENCY-306` 1,667 건(전부 limit 구간).

| 시나리오 | 요청 수 | 실패율 | 체크 | 지표 | avg | p95 (ms) | p99 (ms) | max | 문턱 | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|
| 10 Handoff 브라우저 로그인 (0→10→30 VU, 3분 20초) | 19,221 (96.0 req/s) | 0.00 % | 100 % (51,256/51,256) | ① 진입 `handoff_login_entry_duration` | 3.3 | 6.5 | 11.3 | 42.3 | — | — |
| 〃 | | | | ② 로그인+발급 `handoff_login_continue_duration` | 16.9 | **27.4** | 38.9 | 184.3 | < 2,000 | ✅ |
| 〃 | | | | ①+② `handoff_login_e2e_duration` | 20.2 | **33.5** | 48.1 | 214.7 | < 3,000 | ✅ |
| 〃 | | | | ③ `handoff_verify_duration` | 12.5 | **21.2** | 31.2 | 113.4 | < 2,000 | ✅ |
| 11 발급·검증 API (20 → **100** → 300 → 50 rps) | 36,926 (180.1 req/s) | 0.00 % | 99.97 % (73,105/73,126) | sustain 100 rps 발급 `handoff_issue_duration{phase:sustain}` | 9.6 | **14.9** | 28.2 | 99.3 | < 2,000 | ✅ |
| 〃 | | | | sustain 검증 `handoff_verify_duration{phase:sustain}` | 10.6 | **16.2** | 29.1 | 93.2 | < 2,000 | ✅ |
| 〃 | | | | sustain 429 비율 | — | 0 / 12,001 | | | 0 | ✅ |
| 〃 | | | | **limit 300 rps 429 비율** `handoff_issue_rate_limited{phase:limit}` | — | 31.5 % (1,646 / 5,223) | | | ≥ 17 % | ✅ |
| 〃 | | | | recovery 50 rps 429 비율 | — | 0 / 1,001 | | | 0 | ✅ |
| 〃 | | | | (참고) 전 구간 발급 p95 — limit 구간의 CPU 포화 포함 | 100.2 | 622.4 | 1,232.9 | 2,816.3 | — | — |
| 12 표준 OIDC 토큰 교환 (0→5→10 VU, 3분 20초) | 6,525 (32.6 req/s) | 0.00 % | 100 % (9,135/9,135) | ① authorize(gate 사전검사+Keycloak 로그인 화면) `oidc_authorize_duration` | 19.6 | 36.9 | 45.9 | 101.7 | — | — |
| 〃 | | | | ② Keycloak 자격 검증 `oidc_login_duration` | 397.2 | **614.9** | 709.2 | 907.0 | < 3,000 | ✅ |
| 〃 | | | | ③ **토큰 교환(hub 판정 포함)** `oidc_token_duration` | 32.3 | **48.0** | 66.8 | 247.8 | < 2,000 | ✅ |
| 〃 | | | | ④ userinfo `oidc_userinfo_duration` | 7.1 | 17.8 | 30.7 | 84.2 | — | — |
| 〃 | | | | ①~④ `oidc_e2e_duration` | 458.5 | **685.8** | 778.7 | 981.0 | < 5,000 | ✅ |
| 〃 | | | | 토큰 거부(403/429/503) `oidc_token_denied_total` | — | 0 건 | | | 0 | ✅ |

읽는 법: 11 번의 "전 구간" 줄은 300 rps 구간(발생기·다섯 JVM 이 4 vCPU 를 나눠 쓰며 포화)과 VU 첫 반복의 로그인이 섞인 값이라 참고만 한다 — 발급 서비스의 값은 sustain 줄이다. 체크 미달 21 건(0.03 %)은 limit 구간에 새로 투입된 VU 의 첫 로그인(VU 당 1회)이 기관 한도에 걸려 콜백이 `error=E-AGENCY-306` 으로 돌아온 것(`login: callback has ticketId` 21/460) — 한도 동작의 일부이지 결함이 아니다.

### 4.2 시험 환경 (기입란)

| 시나리오 | 요청 수 | 실패율 | 체크 | 지표 | p95 (ms) | p99 (ms) | 문턱 | 판정 |
|---|---|---|---|---|---|---|---|---|
| 10 Handoff 로그인 | | | | `handoff_login_continue_duration` | | | < 2,000 | |
| 〃 | | | | `handoff_verify_duration` | | | < 2,000 | |
| 11 발급 API | | | | `handoff_issue_duration{phase:sustain}` | | | < 2,000 | |
| 〃 | | | | `handoff_verify_duration{phase:sustain}` | | | < 2,000 | |
| 11 한도 | | | | `handoff_issue_rate_limited{phase:limit}` (429 비율) | | | ≥ 17 % | |
| 12 토큰 교환 | | | | `oidc_token_duration` | | | < 2,000 | |
| 〃 | | | | `oidc_login_duration` | | | < 3,000 | |

환경(호스트·vCPU·네트워크·버전)은 §2.1 의 '시험 환경' 열에, 결과 디렉터리 경로는 여기에 적는다: `k6/reports/________/`.

## 5. 판정과 관찰

### 5.1 샌드박스 판정

| 항목 | 기준 | 샌드박스 | 판정 |
|---|---|---|---|
| Handoff p95 (로그인+발급 서버 처리 / 발급 API @100 rps) | < 2,000 ms | 27.4 ms / 14.9 ms | ✅ 기준의 1/70 이하 |
| 토큰 교환 p95 (hub 판정 포함) | < 2,000 ms | 48.0 ms | ✅ |
| 200 tps 한도 | 넘기면 429, 이내·회복은 0 | 300 rps 에서 31.5 % 429(기대 33 %), 100·50 rps 에서 0 | ✅ |
| 실패율 / 체크 | < 1 % / > 99 % | 0.00 % / 100·99.97·100 % | ✅ |

단일 호스트·loopback 값이므로 절대값보다 **기준 대비 여유(수십 배)** 와 한도 동작을 보는 용도다. 시험 환경에서는 네트워크 RTT·TLS 가 더해지고 발생기가 분리되므로 p95 는 커지되 CPU 포화 구간의 꼬리는 줄어들 것으로 본다.

### 5.2 측정 중 발견한 결함 (PR #272 에서 수정)

1. **코어 로그인 프런트 발급의 감사 이력·기관 이벤트 유실 (HIGH)** — 10 번 첫 실행에서 발급마다 hub 로그에 `감사 이력 저장 실패 (무시)` + `outbox 적재 실패 (비치명적)` 가 남았다. 원인은 `handoff_audit.auth_result_id VARCHAR(36)`: 1.1 로그인 프런트는 본인확인 제공자의 txId 를 FE 세션의 `authResultId` 로 쓰는데 MOCK 은 `mock-`+UUID(41자), SPI 제공자는 형식이 자유라 INSERT 가 "value too long" 으로 실패하고, 같은 `@Transactional` 안의 outbox 적재(HANDOFF_ISSUED)까지 "current transaction is aborted" 로 따라 실패했다. 발급 자체는 Redis 라 성공으로 끝나 CI 스모크(⑦b)·IT 가 잡지 못했다(1.1.0 부터). → Flyway **V32** 로 100자로 넓혔고 `HandoffLoginIntegrationTest` 가 감사 행·outbox 행을 확인한다.
2. **호출자 `X-Correlation-Id` 무검증 → 감사 유실 (HIGH, 보안)** — 11 번 첫 실행의 상관 id 가 37자를 넘자 `handoff_audit`·`audit_log` 의 `correlation_id`(VARCHAR(36)) INSERT 가 실패했고, 감사는 WAL 로 갔다가 재생 때 제약 위반으로 폐기(유실), outbox 도 실패했다 — 기관 키를 가진 외부 호출자가 헤더 하나로 감사를 비울 수 있었다. → `TraceparentFilter` 가 규격(`[A-Za-z0-9._:-]` 1~36자) 밖이면 새 id 로 바꾸고 응답 헤더·컨트롤러·감사가 같은 값을 본다(`TraceparentFilterTest`, `HandoffIntegrationTest`). k6 쪽도 규격 안의 id 를 쓴다.
3. (측정 절차) 첫 12 번 실행은 gate OIDC 프런트의 IP 레이트리밋(20/초·300/분)에 걸려 44 % 가 429 였다 — 결함이 아니라 단일 IP 발생기의 전제 누락. 준비표(§2.1 "대상 설정 차이")에 넣었다.

### 5.3 잔여·다음

- 시험 환경 재측정이 남아 있다(§4.2). 발생기는 대상과 다른 호스트에서, https 로.
- 장시간(soak) 측정은 하지 않았다 — 메모리·커넥션 풀 드리프트는 시험 환경에서 11 번 sustain 을 30분으로 늘려 본다(`k6 run … --env` 로 바꾸지 않고 스크립트의 `duration` 을 조정).
- Keycloak 로그인(비밀번호 해시)이 12 번 끝-끝 시간의 대부분이다 — Idem 의 몫(토큰 교환 판정)은 `oidc_token_duration` 으로 따로 본다.

## 6. 이력

| 일자 | 내용 |
|---|---|
| 2026-10-15 | 최초 작성(G2-8, PR #272) — 1.1 계약 시나리오 3종·실행기·샌드박스 측정, 결함 2건 수정 후 재측정 |
