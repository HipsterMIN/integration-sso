# Idem — k6 성능·부하 시나리오 (1.1)

> 대상: Idem 1.1.x 설치본(코어 에디션) — hub·registry·authz(+ gate·Keycloak). 실행기 `scripts/perf/run-k6.sh`, 보고서 양식·측정 결과
> `docs/manuals/performance-report.md`. 2026-05 의 OnePass·Mock 서버 스위트는 `legacy/`(참고용, 1.1 실서버에 맞지 않음).

## 1. 무엇을 재는가 (GS 성능 항목 ↔ 시나리오)

| 플랜 §3 "성능 보고" 항목 | 시나리오 | 지표(k6 Trend) | 문턱(SLO) |
|---|---|---|---|
| Handoff p95 | `scripts/10-handoff-login.js` 브라우저 로그인 끝-끝 (진입 → MOCK 본인확인 → 발급 → 콜백 → 기관 verify) | `handoff_login_continue_duration`(로그인+발급 서버 처리) · `handoff_login_e2e_duration` · `handoff_verify_duration` | p95 < 2,000 ms (e2e < 3,000) |
| Handoff p95 (발급 서비스만) | `scripts/11-handoff-issue-api.js` 발급·검증 API, 100 rps 2분 | `handoff_issue_duration{phase:sustain}` · `handoff_verify_duration{phase:sustain}` | p95 < 2,000 ms, 429 없음 |
| 200 tps 한도 | 〃 `limit` 구간 300 rps 20초 → `recovery` 50 rps | `handoff_issue_rate_limited{phase:limit}`(429 E-AGENCY-306 비율) | limit 구간 429 ≥ 17 %(기대 ≈ 33 %), sustain·recovery 0 |
| 토큰 교환 p95 | `scripts/12-oidc-token.js` 표준 OIDC code+PKCE: gate 프런트 → Keycloak 로그인 → **토큰 교환(hub 정책 판정 포함)** → userinfo | `oidc_token_duration` ★ · `oidc_login_duration` · `oidc_e2e_duration` | 토큰 교환 p95 < 2,000 ms |
| (CI 게이트) | `scenarios/smoke.js` — 헬스·제공자 목록·MOCK 라운드트립·에디션 404·발급 응답 | checks 100 % | `k6 Smoke Test` 잡 |

모든 시나리오는 `http_req_failed < 1 %`, `checks > 99 %` 를 함께 건다. 429 는 11 번에서 기대 응답으로 다루고(`expectedStatuses`), 실패율에 넣지 않는다.

## 2. 실행

```bash
# 설치본이 떠 있고(hub·registry·authz, 12 번은 gate+Keycloak 도), 관리자 로그인 정보가 있다
export IDEM_ADMIN_PASSWORD=…            # 또는 IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD
export IDEM_ADMIN_TOTP_SECRET=…         # 2단계 등록이 끝난 관리자의 비밀 (또는 IDEM_ADMIN_TOTP_SECRET_FILE)
export OIDC_PASSWORD=…                  # 12 번: Keycloak realm 의 시험 사용자 비밀번호 (OIDC_USER 기본 poc-testuser)
export HUB_URL=https://idem.example.org GATE_URL=https://sso.example.org   # 기본 localhost:8083 / 8081
PERF_LABEL="GS 시험 환경" scripts/perf/run-k6.sh                 # 10·11·12 → k6/reports/<일시>/
PERF_SCENARIOS="10 11" scripts/perf/run-k6.sh                   # gate·Keycloak 없이
PERF_DRY_RUN=1 scripts/perf/run-k6.sh                           # 1 VU × 1 반복 — 자격·계약 확인(측정값 아님)
```

실행기가 하는 일: ① 헬스 → ② 관리자 로그인(`scripts/lib/admin-login.sh`) → ③ Handoff 시험 서비스 `PERF_HO` 프로파일 PUT(DIRECT, `callbackWhitelist`) + 기관 키 회전 → ④ (12 번) OIDC_RP 시험 서비스 `PERF_RP` PUT → Keycloak client `idem-svc-PERF_RP` 프로비저닝 + secret 회전 → ⑤ 환경 기록 → ⑥ k6 (`--summary-export`) → ⑦ `scripts/perf/summarize-k6.py` 로 `summary.md`. 비밀(기관 키·client secret·비밀번호)은 환경변수로만 k6 에 넘기고 어디에도 쓰지 않는다. 결과 디렉터리의 `*.summary.json` 은 작아서(수 KB) 커밋해 근거로 남긴다(`--out json` 원시 출력은 `.gitignore`).

시나리오를 직접 돌릴 때는 환경변수를 손으로 준다:

```bash
PERF_AGENCY_KEY=… k6 run k6/scripts/10-handoff-login.js --env HUB_URL=http://localhost:8083
OIDC_CLIENT_SECRET=… OIDC_PASSWORD=… k6 run k6/scripts/12-oidc-token.js --env GATE_URL=http://localhost:8081 --env OIDC_CLIENT_ID=idem-svc-PERF_RP
```

### 시험 대상(설치본) 쪽 준비

| 항목 | 값 | 이유 |
|---|---|---|
| hub `IDEM_PLUGINS_MOCK_AUTH_ENABLED=true` + 기본 프로파일(`IDEM_SPRING_PROFILE=default`) | 10·11 번이 MOCK 제공자로 로그인한다 | `docs/install.md` §5 의 설치 검증 절차와 같다(측정 뒤 되돌린다). 실 제공자(NICE 등)는 외부 응답시간이 섞이므로 부하 측정에 쓰지 않는다 |
| hub `IDEM_HUB_AUTH_RL_ENABLED=false` (또는 `IDEM_HUB_AUTH_RL_TPS`·`_PER_MIN`·`_DAILY` 상향) | 부하 발생기가 **한 IP** 라 `/api/v1/handoff/login*`·`/api/v1/auth/**` 의 IP 레이트리밋(기본 20 tps·100/분·1,000/일)이 먼저 걸린다 | 기관 한도(`AgencyRateLimiter`, 200 tps)는 그대로 둔다 — 11 번이 그것을 잰다 |
| gate `IDEM_GATE_FRONT_RL_ENABLED=false` (또는 `IDEM_GATE_FRONT_RL_PER_SECOND`·`_PER_MINUTE` 상향) | 12 번: gate OIDC 프런트(`/realms/**`)의 IP 레이트리밋(기본 20/초·300/분)이 한 IP 의 발생기를 1분 안에 막는다(429 `rate_limited`) | 기관 한도는 OIDC 토큰 교환에도 걸리므로(`E-AGENCY-306` → 429 `temporarily_unavailable`) 그대로 둔다 |
| Keycloak realm 에 시험 사용자 | 12 번의 `OIDC_USER`/`OIDC_PASSWORD` | `infra/docker/keycloak/realm-export.json` 의 `poc-testuser` 는 설치 검증용 realm 에만 있다 |
| 프로파일 `limits.tps` | 비우면 설치본 기본 200 | 다른 값이면 `PERF_TPS_LIMIT=<값>` 으로 실행기에 알린다(11 번의 기대 429 비율) |

### 부하 발생기

k6 v1.x(`k6 version`). 설치는 [k6 설치 문서](https://grafana.com/docs/k6/latest/set-up/install-k6/) 또는 GitHub Release 바이너리(`k6-v1.4.0-linux-amd64.tar.gz`). 발생기는 대상과 **다른 호스트**가 바람직하다 — 같은 호스트면 CPU 를 나눠 쓴 값이다(샌드박스 측정이 그렇다).

## 3. 디렉터리

```
k6/
├── lib/idem.js                 # 1.1 공통 — MOCK 로그인(두 홉), 기관 헤더, 발급·검증, 지표
├── lib/helpers.js              # smoke.js 가 쓰는 종전 헬퍼
├── scenarios/smoke.js          # CI 게이트 (k6 Smoke Test 잡)
├── scripts/10-handoff-login.js # Handoff 브라우저 로그인 끝-끝
├── scripts/11-handoff-issue-api.js  # 발급·검증 API p95 + 200 tps 한도
├── scripts/12-oidc-token.js    # 표준 OIDC 토큰 교환 p95 (gate+Keycloak+hub 판정)
├── reports/<일시>/             # 실행기 결과: summary.md · *.summary.json · *.log · environment.txt
└── legacy/                     # 2026-05 OnePass·Mock 서버 스위트와 그 보고서 (참고용)
```

## 4. 시나리오 설계 메모

- **10 번**은 반복마다 새 브라우저(빈 cookie jar)다. VU 마다 다른 사람(`name/phone` 을 MOCK 콜백 파라미터로 — 콜백 값이 우선) → 첫 반복은 registry 신규 등록, 이후는 기존 회원 조회. `continue` 홉 하나에 MOCK complete → registry 확정 → FE 세션 → 발급(정책·한도·KMS 암호화·서명·Redis·감사)이 다 들어 있어 "로그인+발급" 의 서버 처리 시간이다.
- **11 번**은 VU 당 한 번만 로그인해 FE 세션 쿠키를 받고(로그인 요청은 `phase:login` 으로 분리) 이후 `POST /api/v1/handoff/issue` → `verify` 만 반복한다. 도착률(arrival-rate) 실행기라 VU 수가 아니라 **초당 요청 수**를 고정한다. 발급은 기관별 초 단위 창(`idem:rl:tps:<기관>:<초>`)으로 200 건을 넘기면 429 다 — 300 rps 면 약 1/3 이 429 여야 한다.
- **12 번**은 Keycloak 쿠키를 jar 에 맡기지 않고 응답에서 읽어 직접 보낸다(http 대상이면 Secure 쿠키를 jar 가 보내지 않는다). 토큰 교환 응답 뒤 `userinfo` 로 `idem_state` 를 확인하고 `logout`(측정 제외)으로 Keycloak 세션을 정리한다. 같은 사용자로 반복하므로 registry 는 첫 교환에서만 등록한다.
- Location 이 공개 주소(`IDEM_PUBLIC_URL_HUB`/`GATE`)로 오고 부하 대상 주소가 다르면 `HUB_PUBLIC_URL`/`GATE_PUBLIC_URL` 로 알려 준다(스크립트가 바꿔 친다).

## 5. 결과 읽기

`summary.md` 의 시나리오마다 요청 수·실패율·체크·지표별 avg/p95/p99/max 와 문턱 판정(✓/✗)이 있다. 보고서에는 `docs/manuals/performance-report.md` §4 표의 형식으로 옮긴다 — 값 옆에 환경(호스트·vCPU·네트워크)을 반드시 적는다. k6 종료 코드가 0 이 아니면 문턱 미달이다(실행기는 계속 돌고 마지막에 2 로 끝난다).

## 6. CI

`.github/workflows/ci.yml` 의 `k6 Smoke Test` 잡이 PR 마다 `scenarios/smoke.js` 를 실기동 스택(registry·authz·gate·hub + Keycloak) 앞에서 돌린다. 부하 시나리오(10~12)는 CI 에서 돌리지 않는다 — 러너는 측정 환경이 아니다.
