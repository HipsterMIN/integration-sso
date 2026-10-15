## k6 성능 측정 요약 — 2026-10-15-sandbox

```
label: sandbox 4 vCPU·15 GB 단일 호스트(PG·Redis·Keycloak·registry·authz·gate·hub·k6 동거), 1.1.2 boot jar, http
date: 2026-10-15T02:10:25+00:00
k6: k6 v1.4.0 (commit/a9f9e3b28a, go1.25.4, linux/amd64)
hub: http://localhost:8083 (build 1.1.2 (main 809e215) + PR #272 수정(V32·TraceparentFilter))
gate: http://localhost:8081
service: PERF_HO (DIRECT) / PERF_RP (OIDC_RP)
generator: Linux 6.18.44-fc-v114 x86_64, 4 vCPU, 16093 MB
scenarios: 10 11 12
```

### 10 Handoff 브라우저 로그인 끝-끝 (MOCK)

- 환경: sandbox 4 vCPU·15 GB 단일 호스트(PG·Redis·Keycloak·registry·authz·gate·hub·k6 동거), 1.1.2 boot jar, http · 판정: **✅ PASS**
- HTTP 요청 19,221 건 (96.0 req/s), 반복 6,407, 실패율 0.00%, 체크 100.00% (51,256/51,256), 전체 http_req_duration p95 22.6 ms

| 지표 | 값 | 문턱 |
|---|---|---|
| `handoff_login_entry_duration` | avg 3.3 · p95 **6.5** · p99 11.3 · max 42.3 ms |  |
| `handoff_login_continue_duration` | avg 16.9 · p95 **27.4** · p99 38.9 · max 184.3 ms | ✓ p(95)<2000 |
| `handoff_login_e2e_duration` | avg 20.2 · p95 **33.5** · p99 48.1 · max 214.7 ms | ✓ p(95)<3000 |
| `handoff_verify_duration` | avg 12.5 · p95 **21.2** · p99 31.2 · max 113.4 ms | ✓ p(95)<2000 |

### 11 Handoff 발급·검증 API + 200 tps 한도

- 환경: sandbox 4 vCPU·15 GB 단일 호스트(PG·Redis·Keycloak·registry·authz·gate·hub·k6 동거), 1.1.2 boot jar, http · 판정: **✅ PASS**
- HTTP 요청 36,926 건 (180.1 req/s), 반복 18,826, 실패율 0.00%, 체크 99.97% (73,105/73,126), 전체 http_req_duration p95 645.0 ms

| 지표 | 값 | 문턱 |
|---|---|---|
| `handoff_issue_duration{phase:sustain}` | avg 9.6 · p95 **14.9** · p99 28.2 · max 99.3 ms | ✓ p(95)<2000 |
| `handoff_verify_duration{phase:sustain}` | avg 10.6 · p95 **16.2** · p99 29.1 · max 93.2 ms | ✓ p(95)<2000 |
| `handoff_issue_duration` | avg 100.2 · p95 **622.4** · p99 1,232.9 · max 2,816.3 ms |  |
| `handoff_verify_duration` | avg 79.7 · p95 **481.5** · p99 950.7 · max 2,115.6 ms |  |
| `handoff_issue_rate_limited{phase:sustain}` | 0.00% (0 / 12,001) | ✓ rate<0.001 |
| `handoff_issue_rate_limited{phase:limit}` | 31.51% (1,646 / 5,223) | ✓ rate>0.17 |
| `handoff_issue_rate_limited{phase:recovery}` | 0.00% (0 / 1,001) | ✓ rate<0.001 |
| `handoff_rate_limited_total` | 1,667 건 |  |
| `handoff_login_continue_duration` | avg 755.5 · p95 **1,604.9** · p99 2,119.1 · max 2,556.1 ms |  |
| `handoff_login_e2e_duration` | avg 1,064.6 · p95 **2,410.2** · p99 2,755.5 · max 3,009.5 ms |  |
| `handoff_login_entry_duration` | avg 309.2 · p95 **1,052.4** · p99 1,418.4 · max 1,573.2 ms |  |

### 12 표준 OIDC 토큰 교환 (gate+Keycloak+hub 판정)

- 환경: sandbox 4 vCPU·15 GB 단일 호스트(PG·Redis·Keycloak·registry·authz·gate·hub·k6 동거), 1.1.2 boot jar, http · 판정: **✅ PASS**
- HTTP 요청 6,525 건 (32.6 req/s), 반복 1,305, 실패율 0.00%, 체크 100.00% (9,135/9,135), 전체 http_req_duration p95 445.2 ms

| 지표 | 값 | 문턱 |
|---|---|---|
| `oidc_authorize_duration` | avg 19.6 · p95 **36.9** · p99 45.9 · max 101.7 ms |  |
| `oidc_login_duration` | avg 397.2 · p95 **614.9** · p99 709.2 · max 907.0 ms | ✓ p(95)<3000 |
| `oidc_token_duration` | avg 32.3 · p95 **48.0** · p99 66.8 · max 247.8 ms | ✓ p(95)<2000 |
| `oidc_userinfo_duration` | avg 7.1 · p95 **17.8** · p99 30.7 · max 84.2 ms |  |
| `oidc_e2e_duration` | avg 458.5 · p95 **685.8** · p99 778.7 · max 981.0 ms | ✓ p(95)<5000 |
| `oidc_token_denied_total` | 0 건 | ✓ count<1 |

종합: ✅ 전 시나리오 문턱 통과
