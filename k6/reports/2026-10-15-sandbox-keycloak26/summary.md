## k6 성능 측정 요약 — 2026-10-15-sandbox-keycloak26

```
label: sandbox 4 vCPU·15 GB 단일 호스트(PG·Redis·Keycloak 26.8.1·registry·authz·gate·hub·k6 동거), 1.1.3 boot jar + G2-10, http
date: 2026-10-15T08:27:59+00:00
k6: k6 v1.4.0 (commit/a9f9e3b28a, go1.25.4, linux/amd64)
hub: http://localhost:8083 (build ?)
gate: http://localhost:8081
service: PERF_HO (DIRECT) / PERF_RP (OIDC_RP)
generator: Linux 6.18.44-fc-v114 x86_64, 4 vCPU, 16093 MB
scenarios: 12
```

### 12 표준 OIDC 토큰 교환 (gate+Keycloak+hub 판정)

- 환경: sandbox 4 vCPU·15 GB 단일 호스트(PG·Redis·Keycloak 26.8.1·registry·authz·gate·hub·k6 동거), 1.1.3 boot jar + G2-10, http · 판정: **✅ PASS**
- HTTP 요청 9,045 건 (45.2 req/s), 반복 1,809, 실패율 0.00%, 체크 100.00% (12,663/12,663), 전체 http_req_duration p95 101.4 ms

| 지표 | 값 | 문턱 |
|---|---|---|
| `oidc_authorize_duration` | avg 30.9 · p95 **59.9** · p99 90.3 · max 155.8 ms |  |
| `oidc_login_duration` | avg 81.6 · p95 **189.6** · p99 262.5 · max 376.1 ms | ✓ p(95)<3000 |
| `oidc_token_duration` | avg 47.0 · p95 **76.8** · p99 102.4 · max 173.3 ms | ✓ p(95)<2000 |
| `oidc_userinfo_duration` | avg 7.6 · p95 **16.1** · p99 40.9 · max 72.8 ms |  |
| `oidc_e2e_duration` | avg 169.4 · p95 **303.6** · p99 392.8 · max 482.0 ms | ✓ p(95)<5000 |
| `oidc_token_denied_total` | 0 건 | ✓ count<1 |

종합: ✅ 전 시나리오 문턱 통과
