#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 성능 측정 실행기 (G2 성능 보고) — 떠 있는 Idem 설치본(hub·registry·authz, 선택 gate+Keycloak) 앞에서 k6/scripts/1x-*.js 를
# 차례로 돌리고 결과(k6 요약 JSON + 마크다운 표)를 한 디렉터리에 남긴다. 같은 스크립트를 샌드박스·CI·GS 시험 환경에서 쓴다.
#
#   scripts/perf/run-k6.sh                       # 10·11·12 전부 (12 는 GATE_URL 이 살아 있을 때만)
#   PERF_SCENARIOS="10 11" scripts/perf/run-k6.sh # 일부만
#
# 준비(한 번): 관리자 로그인 → 시험 서비스 프로파일 PUT(DIRECT PERF_HO, 필요하면 OIDC_RP PERF_RP) → 기관 키·client secret 회전.
# 대상 쪽 준비(k6/README.md §2): hub MOCK 제공자 + 기본 프로파일, 발생기가 한 IP 이면 hub IDEM_HUB_AUTH_RL_ENABLED=false 와
# gate IDEM_GATE_FRONT_RL_ENABLED=false (IP 레이트리밋 — 기관 한도 200 tps 는 그대로), Keycloak 시험 사용자.
# 비밀(기관 키·client secret·시험 사용자 비밀번호)은 환경변수로만 k6 에 넘긴다(--env 는 프로세스 인자에 남는다). 어디에도 출력하지 않는다.
#
# 환경변수:
#   HUB_URL GATE_URL                 기본 http://localhost:8083 / http://localhost:8081
#   HUB_PUBLIC_URL GATE_PUBLIC_URL   앱이 아는 공개 주소(IDEM_PUBLIC_URL_HUB/GATE)가 부하 대상 주소와 다를 때 (Location 재작성)
#   IDEM_ADMIN_PASSWORD(또는 IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD) · IDEM_ADMIN_TOTP_SECRET[_FILE] · IDEM_ADMIN_NEW_PASSWORD
#                                    scripts/lib/admin-login.sh 의 입력 (관리 API 는 관리자 세션 + 2단계 뒤에 있다)
#   PERF_SERVICE  (PERF_HO)          Handoff(DIRECT) 시험 서비스 코드 — 프로파일은 이 스크립트가 PUT 한다(있으면 덮어쓴다)
#   PERF_CALLBACK (https://agency.example.org/cb)   그 프로파일의 callbackWhitelist 겸 시나리오 콜백
#   PERF_TPS_LIMIT                   있으면 프로파일 limits.tps 로 둔다(없으면 설치본 기본 200 tps). 시나리오 11 의 기대 429 비율에 반영
#   PERF_RP (PERF_RP)                OIDC_RP 시험 서비스 코드 (시나리오 12) — client_id = idem-svc-PERF_RP
#   OIDC_REDIRECT_URI (https://rp.example.org/callback)   그 프로파일의 redirectUris 겸 시나리오 redirect_uri
#   OIDC_USER / OIDC_PASSWORD        Keycloak realm 의 시험 사용자 (기본 realm-export.json 의 poc-testuser — 운영 realm 에는 없다)
#   PERF_SCENARIOS ("10 11 12")      돌릴 스크립트 접두 번호
#   K6_BIN (k6)  K6_EXTRA_ARGS       k6 바이너리·추가 인자(예: "--out json=…" — 크다)
#   PERF_DRY_RUN=1                   시나리오마다 1 VU × 1 반복만(k6 가 scenarios 를 CLI 로 덮는다) — 계약·자격 확인용, 측정값 아님
#   OUT_DIR (k6/reports/<YYYYMMDD-HHMMSS>)   결과 디렉터리
#   PERF_LABEL                       결과 표에 적을 환경 이름(예: "GS 시험 환경", "sandbox 4vCPU")
#   PERF_BUILD                       environment.txt 에 적을 대상 빌드(예: 1.1.2) — 없으면 hub /actuator/info 의 build.version
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

HUB_URL="${HUB_URL:-http://localhost:8083}"
GATE_URL="${GATE_URL:-http://localhost:8081}"
PERF_SERVICE="${PERF_SERVICE:-PERF_HO}"
PERF_CALLBACK="${PERF_CALLBACK:-https://agency.example.org/cb}"
PERF_RP="${PERF_RP:-PERF_RP}"
OIDC_REDIRECT_URI="${OIDC_REDIRECT_URI:-https://rp.example.org/callback}"
OIDC_USER="${OIDC_USER:-poc-testuser}"
PERF_SCENARIOS="${PERF_SCENARIOS:-10 11 12}"
K6_BIN="${K6_BIN:-k6}"
PERF_LABEL="${PERF_LABEL:-$(hostname)}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/k6/reports/$(date +%Y%m%d-%H%M%S)}"
CID="perf-$(date +%s)"

ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
for t in curl jq python3 "$K6_BIN"; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done
mkdir -p "$OUT_DIR"
echo "결과: $OUT_DIR"

echo "① 헬스"
for u in "$HUB_URL"; do
  st=$(curl -sf "$u/actuator/health" | jq -r '.status' 2>/dev/null || echo "DOWN")
  [ "$st" = "UP" ] && ok "$u UP" || fail "$u 헬스 $st"
done
WITH_GATE=0
if [[ " $PERF_SCENARIOS " == *" 12 "* ]]; then
  gst=$(curl -sf "$GATE_URL/actuator/health" | jq -r '.status' 2>/dev/null || echo "DOWN")
  if [ "$gst" = "UP" ]; then WITH_GATE=1; ok "$GATE_URL UP (시나리오 12 포함)"; else echo "  ⚠ gate 헬스 $gst — 시나리오 12 생략"; fi
fi

echo "② 관리자 로그인"
export IDEM_ADMIN_PASSWORD="${IDEM_ADMIN_PASSWORD:-${IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD:-}}"
[ -n "$IDEM_ADMIN_PASSWORD" ] || fail "IDEM_ADMIN_PASSWORD(또는 IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD) 가 없다"
SID=$(HUB_URL="$HUB_URL" "$ROOT/scripts/lib/admin-login.sh") || fail "관리자 로그인 실패"
adm=(-H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: run-k6' -H "X-Correlation-Id: $CID")
ok "로그인"

echo "③ Handoff 시험 서비스 $PERF_SERVICE (DIRECT, callbackWhitelist=$PERF_CALLBACK${PERF_TPS_LIMIT:+, limits.tps=$PERF_TPS_LIMIT})"
limits=""
[ -n "${PERF_TPS_LIMIT:-}" ] && limits=",\"limits\":{\"tps\":$PERF_TPS_LIMIT}"
body=$(cat <<JSON
{"schemaVersion":1,
 "service":{"code":"$PERF_SERVICE","name":"성능 측정 Handoff","status":"ACTIVE"},
 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["$PERF_CALLBACK"]}},
 "identity":{"attributes":["name_masked"]},
 "policy":{"minAuthLevel":"L1"}$limits}
JSON
)
code=$(curl -s -o "$OUT_DIR/.put-ho.json" -w '%{http_code}' -X PUT "$HUB_URL/api/v1/admin/services/$PERF_SERVICE/profile" \
       -H 'Content-Type: application/json' "${adm[@]}" -d "$body")
{ [ "$code" = "200" ] || [ "$code" = "201" ]; } && ok "프로파일 PUT $code" || fail "프로파일 PUT $code: $(cat "$OUT_DIR/.put-ho.json")"
PERF_AGENCY_KEY=$(curl -sf -X POST "$HUB_URL/api/v1/admin/agencies/$PERF_SERVICE/rotate-key" "${adm[@]}" | jq -r '.newApiKey')
[ -n "$PERF_AGENCY_KEY" ] && [ "$PERF_AGENCY_KEY" != "null" ] && ok "기관 API 키 회전" || fail "rotate-key 실패"
export PERF_AGENCY_KEY PERF_SERVICE PERF_CALLBACK HUB_URL
[ -n "${HUB_PUBLIC_URL:-}" ] && export HUB_PUBLIC_URL
[ -n "${PERF_TPS_LIMIT:-}" ] && export TPS_LIMIT="$PERF_TPS_LIMIT"
rm -f "$OUT_DIR/.put-ho.json"

if [ "$WITH_GATE" = 1 ]; then
  echo "④ OIDC_RP 시험 서비스 $PERF_RP (redirectUris=$OIDC_REDIRECT_URI) → Keycloak client idem-svc-$PERF_RP"
  body=$(cat <<JSON
{"schemaVersion":1,
 "service":{"code":"$PERF_RP","name":"성능 측정 OIDC RP","status":"ACTIVE"},
 "protocol":{"type":"OIDC_RP","oidc":{"redirectUris":["$OIDC_REDIRECT_URI"],"postLogoutRedirectUris":["$OIDC_REDIRECT_URI"]}},
 "policy":{"minAuthLevel":"L1"}}
JSON
)
  code=$(curl -s -o "$OUT_DIR/.put-rp.json" -w '%{http_code}' -X PUT "$HUB_URL/api/v1/admin/services/$PERF_RP/profile" \
         -H 'Content-Type: application/json' "${adm[@]}" -d "$body")
  { [ "$code" = "200" ] || [ "$code" = "201" ]; } && ok "프로파일 PUT $code" || fail "프로파일 PUT $code: $(cat "$OUT_DIR/.put-rp.json")"
  rm -f "$OUT_DIR/.put-rp.json"
  status=$(curl -sf "$HUB_URL/api/v1/admin/services/$PERF_RP/oidc-client" "${adm[@]}") || fail "oidc-client 조회 실패"
  [ "$(echo "$status" | jq -r .provisioned)" = "true" ] && ok "client 프로비저닝 $(echo "$status" | jq -r .clientId)" || fail "client 미프로비저닝: $status"
  OIDC_CLIENT_SECRET=$(curl -sf -X POST "$HUB_URL/api/v1/admin/services/$PERF_RP/oidc-client/secret" "${adm[@]}" | jq -r '.clientSecret')
  [ -n "$OIDC_CLIENT_SECRET" ] && [ "$OIDC_CLIENT_SECRET" != "null" ] && ok "client secret 회전" || fail "secret 회전 실패"
  export OIDC_CLIENT_SECRET OIDC_CLIENT_ID="idem-svc-$PERF_RP" OIDC_REDIRECT_URI OIDC_USER GATE_URL
  [ -n "${GATE_PUBLIC_URL:-}" ] && export GATE_PUBLIC_URL
  [ -n "${OIDC_PASSWORD:-}" ] || echo "  ⚠ OIDC_PASSWORD 가 비어 있다 — 시나리오 12 의 로그인이 실패할 것이다"
fi

echo "⑤ 환경 기록"
{
  echo "label: $PERF_LABEL"
  echo "date: $(date -Iseconds)"
  echo "k6: $("$K6_BIN" version 2>/dev/null | head -1)"
  echo "hub: $HUB_URL (build ${PERF_BUILD:-$(curl -s "$HUB_URL/actuator/info" "${adm[@]}" | jq -r '.build.version // "?"' 2>/dev/null)})"
  [ "$WITH_GATE" = 1 ] && echo "gate: $GATE_URL" || echo "gate: (없음 — 시나리오 12 생략)"
  echo "service: $PERF_SERVICE (DIRECT${PERF_TPS_LIMIT:+, limits.tps=$PERF_TPS_LIMIT})${WITH_GATE:+ / $PERF_RP (OIDC_RP)}"
  echo "generator: $(uname -srm), $(nproc) vCPU, $(free -m 2>/dev/null | awk '/^Mem:/{print $2" MB"}')"
  echo "scenarios: $PERF_SCENARIOS${PERF_DRY_RUN:+ (DRY RUN)}"
} | tee "$OUT_DIR/environment.txt"

echo "⑥ k6${PERF_DRY_RUN:+ (DRY RUN — 1 VU × 1 반복, 측정값 아님)}"
declare -A SCRIPT=( [10]=10-handoff-login [11]=11-handoff-issue-api [12]=12-oidc-token )
dry=""; [ -n "${PERF_DRY_RUN:-}" ] && dry="--vus 1 --iterations 1"
failed=0
for n in $PERF_SCENARIOS; do
  name="${SCRIPT[$n]:-}"
  [ -n "$name" ] || { echo "  ⚠ 알 수 없는 시나리오 $n"; continue; }
  if [ "$n" = "12" ] && [ "$WITH_GATE" != 1 ]; then continue; fi
  echo "── $name"
  set +e
  # shellcheck disable=SC2086
  "$K6_BIN" run --summary-export "$OUT_DIR/$name.summary.json" --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" \
    $dry ${K6_EXTRA_ARGS:-} "$ROOT/k6/scripts/$name.js" 2>&1 | tee "$OUT_DIR/$name.log"
  rc=${PIPESTATUS[0]}
  set -e
  if [ "$rc" = 0 ]; then ok "$name 문턱 통과"; else echo "  ❌ $name 종료 코드 $rc (문턱 미달 또는 오류)"; failed=$((failed+1)); fi
done

echo "⑦ 요약"
python3 "$ROOT/scripts/perf/summarize-k6.py" "$OUT_DIR" --label "$PERF_LABEL" | tee "$OUT_DIR/summary.md"
echo
echo "결과 디렉터리: $OUT_DIR (요약 summary.md, 시나리오별 *.summary.json·*.log, environment.txt)"
[ "$failed" = 0 ] || { echo "문턱 미달 시나리오 $failed 개 — summary.md 의 ✗ 행을 보고 보고서 §5 에 적는다"; exit 2; }
