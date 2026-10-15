# k6 legacy — 2026-05 OnePass 시절 스위트 (참고용, 1.1 실서버에 맞지 않음)

S8-T2/T3(2026-05-18, PR #134) 때 **Node.js Mock 서버**(`mock-server/`) 앞에서 돌린 스크립트와 그 결과 보고서다. 당시 계약을 그대로 쓰므로
Idem 1.1.x 실서버에는 **그대로 쓸 수 없다**:

| 당시 전제 | 1.1.x |
|---|---|
| Handoff 발급이 `X-Internal-Api-Key` 헤더 + `agencySubjectId/returnUrl/authResult` 본문 | `X-Agency-Code`+`X-Agency-Key`(기관 키) + **FE 세션 쿠키**(로그인 사실) + `authResultId/authLevel/providerCode/callbackUrl` — `HandoffController` |
| 응답 봉투 `resultCode=2000/4000` | `HandoffTicket`/`HandoffPayload` 그대로, 오류는 `E-IDO-*`/`E-AGENCY-*` 코드의 `ErrorResponse` |
| `/api/v1/auth/nice/ci-check`·`oacx/*` 로 부하 | KR 에디션 전용 — 코어 에디션은 404. 한 IP 에서 20 tps 넘으면 IP 레이트리밋(429) |
| 기관 한도 TPS 200 은 Mock 에 없음 | `AgencyRateLimiter`(Redis) — 발급·OIDC 토큰 교환에 적용, 프로파일 `limits.tps` 우선 |

현재 스위트는 `k6/scripts/1x-*.js`(`k6/README.md`), 실행기는 `scripts/perf/run-k6.sh`, 결과·보고서 양식은 `docs/manuals/performance-report.md`.
이 디렉터리는 2026-05 측정 기록(`reports/`)의 근거로만 남긴다 — CI 는 쓰지 않는다.
