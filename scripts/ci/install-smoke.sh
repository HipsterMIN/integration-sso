#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 설치본 스모크 (D3) — CI(.github/workflows/ci.yml smoke-test)와 로컬이 같은 스크립트를 쓴다.
#
# 전제: registry·authz·gate·hub 가 compose.install.yml 의 환경 계약으로 떠 있고, Keycloak(realm idem, import 완료)이
#       gate 뒤에 있다(KC_HOSTNAME_URL = gate 공개 URL). hub 는 Mock 본인확인 플러그인이 켜져 있다.
#
# 검사: ① 4개 헬스 ② gate 를 통한 OIDC Discovery(issuer = gate 공개 URL) ②′ 관리자 로그인(2단계, 무인증 관리 API 401)
#       ③ OIDC_RP 프로파일 PUT → Keycloak client 생성·secret 회전 ④ gate 프런트가 Keycloak 로그인 화면을 프록시(client 존재·PKCE 사전검사)
#       ⑤ Mock 본인확인 → registry 등록 라운드트립 ⑥ registry 이벤트 피드(Kafka 없는 상태 전파) ⑦ 코어 에디션이면 KR 전용 엔드포인트 404
#       ⑧ 감사 조회(관리 행위가 남는다) → 로그아웃
#
#   HUB_URL GATE_URL REGISTRY_URL IDEM_AUTHZ_URL   기본 localhost:8083/8081/8082/8086
#   ISSUER                                     기본 $GATE_URL/realms/idem
#   IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD              관리자(admin) 비밀번호 (S7). IDEM_ADMIN_PASSWORD 가 있으면 그것을 쓴다
#   IDEM_ADMIN_NEW_PASSWORD                    첫 로그인 비밀번호 변경이 요구되면 이 값으로(없으면 1회용 값을 만든다 — 다시 로그인할 수 없다)
#   IDEM_ADMIN_TOTP_SECRET                     이미 2단계 등록된 관리자면 그 비밀 (첫 로그인은 스크립트가 등록한다)
#   IDEM_REGISTRY_INTERNAL_API_KEY                       있으면 ⑥ 을 검사한다
#   IDEM_EDITION                               core(기본) | kr
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

HUB_URL="${HUB_URL:-http://localhost:8083}"
GATE_URL="${GATE_URL:-http://localhost:8081}"
REGISTRY_URL="${REGISTRY_URL:-http://localhost:8082}"
IDEM_AUTHZ_URL="${IDEM_AUTHZ_URL:-http://localhost:8086}"
ISSUER="${ISSUER:-$GATE_URL/realms/idem}"
SERVICE_CODE="${SERVICE_CODE:-SMOKE_RP}"
EDITION="${IDEM_EDITION:-core}"
CID="install-smoke-$(date +%s)"

ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
for t in curl jq python3; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done
LIB="$(cd "$(dirname "$0")/../lib" && pwd)"

echo "① 헬스"
for u in "$HUB_URL" "$GATE_URL" "$REGISTRY_URL" "$IDEM_AUTHZ_URL"; do
  st=$(curl -sf "$u/actuator/health" | jq -r '.status' 2>/dev/null || echo "DOWN")
  [ "$st" = "UP" ] && ok "$u UP" || fail "$u 헬스 실패: $st"
done

echo "② Discovery (gate 프런트)"
disc=$(curl -sf "$GATE_URL/realms/idem/.well-known/openid-configuration") || fail "discovery 응답 없음"
iss=$(echo "$disc" | jq -r '.issuer')
[ "$iss" = "$ISSUER" ] && ok "issuer = $iss" || fail "issuer 불일치: $iss (기대 $ISSUER)"
for k in authorization_endpoint token_endpoint jwks_uri end_session_endpoint; do
  v=$(echo "$disc" | jq -r ".$k")
  case "$v" in "$ISSUER"/*) ok "$k = $v" ;; *) fail "$k 가 issuer 아래가 아님: $v" ;; esac
done
echo "$disc" | jq -e '.code_challenge_methods_supported | index("S256")' >/dev/null && ok "PKCE S256" || fail "code_challenge_methods_supported 에 S256 없음"

echo "②′ 관리자 로그인 (S7 — 관리 API 는 세션 + 2단계 뒤에 있다)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$HUB_URL/api/v1/admin/agencies" -H 'X-Admin-Id: install-smoke')
[ "$code" = "401" ] && ok "무인증(종전 X-Admin-Id) 관리 API 는 401" || fail "무인증 관리 API 가 $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$HUB_URL/actuator/flyway")
[ "$code" = "401" ] && ok "무인증 actuator 는 401" || fail "무인증 actuator 가 $code"
export IDEM_ADMIN_PASSWORD="${IDEM_ADMIN_PASSWORD:-${IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD:-}}"
[ -n "$IDEM_ADMIN_PASSWORD" ] || fail "IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD(또는 IDEM_ADMIN_PASSWORD) 가 없다"
export IDEM_ADMIN_NEW_PASSWORD="${IDEM_ADMIN_NEW_PASSWORD:-Smoke-$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 12)-1}"
SID=$("$LIB/admin-login.sh") || fail "관리자 로그인 실패"
adm=(-H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: install-smoke' -H "X-Correlation-Id: $CID")
me=$(curl -sf "$HUB_URL/api/v1/admin/auth/me" "${adm[@]}") || fail "auth/me 실패"
[ "$(echo "$me" | jq -r .role)" = "SYSTEM_ADMIN" ] && ok "로그인 username=$(echo "$me" | jq -r .username) role=SYSTEM_ADMIN" || fail "역할 불일치: $me"
[ "$(echo "$me" | jq -r .mustChangePassword)" = "false" ] && ok "첫 로그인 비밀번호 변경 완료" || fail "비밀번호 변경 요구가 남아 있다: $me"
code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$HUB_URL/api/v1/admin/tenants/SMOKE_T" -H "Cookie: idemAdminSid=$SID" -H 'Content-Type: application/json' -d '{"name":"x"}')
[ "$code" = "403" ] && ok "X-Requested-With 없는 쓰기는 403 (CSRF)" || fail "CSRF 헤더 없는 쓰기가 $code"

echo "③ OIDC_RP 프로파일 PUT → Keycloak client 프로비저닝"
body=$(cat <<JSON
{"schemaVersion":1,
 "service":{"code":"$SERVICE_CODE","name":"설치 스모크 RP","status":"ACTIVE"},
 "protocol":{"type":"OIDC_RP","oidc":{"redirectUris":["https://rp.example.org/callback"],"postLogoutRedirectUris":["https://rp.example.org/"]}},
 "policy":{"minAuthLevel":"L1","session":{"idleMinutes":20,"absoluteMinutes":240,"concurrent":1}}}
JSON
)
code=$(curl -s -o /tmp/smoke-put.json -w '%{http_code}' -X PUT "$HUB_URL/api/v1/admin/services/$SERVICE_CODE/profile" \
       -H 'Content-Type: application/json' "${adm[@]}" -d "$body")
[ "$code" = "200" ] || [ "$code" = "201" ] && ok "profile PUT $code" || fail "profile PUT $code: $(cat /tmp/smoke-put.json)"
status=$(curl -sf "$HUB_URL/api/v1/admin/services/$SERVICE_CODE/oidc-client" "${adm[@]}") || fail "oidc-client 조회 실패"
[ "$(echo "$status" | jq -r .clientId)" = "idem-svc-$SERVICE_CODE" ] && ok "clientId = idem-svc-$SERVICE_CODE" || fail "clientId 불일치: $status"
[ "$(echo "$status" | jq -r .provisioned)" = "true" ] && ok "Keycloak 에 client 있음(provisioned)" || fail "client 미프로비저닝: $status"
[ "$(echo "$status" | jq -r .issuer)" = "$ISSUER" ] && ok "client issuer = $ISSUER" || fail "client issuer 불일치: $status"
echo "$status" | jq -e '.redirectUris | index("https://rp.example.org/callback")' >/dev/null && ok "redirectUris 반영" || fail "redirectUris 미반영: $status"
secret=$(curl -sf -X POST "$HUB_URL/api/v1/admin/services/$SERVICE_CODE/oidc-client/secret" "${adm[@]}" | jq -r '.clientSecret')
[ -n "$secret" ] && [ "$secret" != "null" ] && [ ${#secret} -ge 16 ] && ok "client secret 회전 (길이 ${#secret})" || fail "secret 회전 실패"

echo "④ gate 프런트 → Keycloak 로그인 화면 (client 존재·PKCE 사전검사)"
challenge=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' | cut -c1-43)
auth_url="$GATE_URL/realms/idem/protocol/openid-connect/auth?client_id=idem-svc-$SERVICE_CODE&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Frp.example.org%2Fcallback&state=smoke&code_challenge=$challenge&code_challenge_method=S256"
code=$(curl -s -o /tmp/smoke-auth.html -w '%{http_code}' "$auth_url")
[ "$code" = "200" ] && grep -qi "kc-form-login\|kc-login\|<form" /tmp/smoke-auth.html && ok "로그인 화면 200 (프록시)" || fail "auth 응답 $code (로그인 화면 아님): $(head -c 300 /tmp/smoke-auth.html)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$GATE_URL/realms/idem/protocol/openid-connect/auth?client_id=idem-svc-$SERVICE_CODE&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Frp.example.org%2Fcallback&state=smoke")
[ "$code" = "400" ] && ok "PKCE 없는 요청은 400" || fail "PKCE 없는 요청이 $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$GATE_URL/realms/idem/protocol/openid-connect/auth?client_id=not-idem&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Frp.example.org%2Fcallback&state=smoke&code_challenge=$challenge&code_challenge_method=S256")
[ "$code" = "400" ] || [ "$code" = "403" ] && ok "Idem 이 만들지 않은 client 는 $code" || fail "타 client 요청이 $code"

echo "⑤ Mock 본인확인 → registry 등록 라운드트립"
h=(-H 'Content-Type: application/json' -H "X-Correlation-Id: $CID")
providers=$(curl -sf "$HUB_URL/api/v1/auth/providers" "${h[@]}") || fail "providers 조회 실패"
echo "$providers" | jq -e '[.[].code] | index("MOCK")' >/dev/null && ok "providers 에 MOCK" || fail "MOCK 제공자 없음 (IDEM_PLUGINS_MOCK_AUTH_ENABLED=true?): $providers"
init=$(curl -sf -X POST "$HUB_URL/api/v1/auth/providers/MOCK/initiate" "${h[@]}" -d '{"returnUrl":"https://fe.local/return","params":{"name":"smoke","phone":"01099998888"}}') || fail "MOCK initiate 실패"
tx=$(echo "$init" | jq -r .txId); [ -n "$tx" ] && [ "$tx" != "null" ] && ok "MOCK initiate txId=$tx" || fail "txId 없음: $init"
done_=$(curl -sf -X POST "$HUB_URL/api/v1/auth/providers/MOCK/complete" "${h[@]}" -d "{\"txId\":\"$tx\",\"params\":{}}") || fail "MOCK complete 실패"
qim=$(echo "$done_" | jq -r '.registration.qimUserId'); [ -n "$qim" ] && [ "$qim" != "null" ] && ok "registry 등록 qimUserId=$qim" || fail "registration 없음: $done_"
[ "$(echo "$done_" | jq -r '.identity.name')" = "smoke" ] && ok "identity.name 전달" || fail "identity 불일치: $done_"

if [ -n "${IDEM_REGISTRY_INTERNAL_API_KEY:-}" ]; then
  echo "⑥ registry 이벤트 피드 (Kafka 없는 상태 전파)"
  feed=$(curl -sf "$REGISTRY_URL/api/v1/internal/events?limit=5" -H "X-Internal-Api-Key: $IDEM_REGISTRY_INTERNAL_API_KEY" -H "X-Correlation-Id: $CID") || fail "이벤트 피드 실패"
  echo "$feed" | jq -e 'type == "array"' >/dev/null && ok "이벤트 피드 응답 (n=$(echo "$feed" | jq length))" || fail "이벤트 피드 형식: $feed"
else
  echo "⑥ (IDEM_REGISTRY_INTERNAL_API_KEY 없음 — 이벤트 피드 검사 생략)"
fi

if [ "$EDITION" = "core" ]; then
  echo "⑦ 코어 에디션: KR 전용 엔드포인트는 없다"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HUB_URL/api/v1/auth/nice/ci-check" "${h[@]}" -d '{"ci":"","mbrDvsnCd":"A101"}')
  [ "$code" = "404" ] && ok "/api/v1/auth/nice/ci-check 404" || fail "KR 엔드포인트가 $code"
fi

echo "⑧ 감사 조회 → 로그아웃"
sleep 1   # 감사 발행은 비동기
audit=$(curl -sf "$HUB_URL/api/v1/admin/audit?category=ADMIN&size=50" "${adm[@]}") || fail "감사 조회 실패"
for a in ADMIN_LOGIN_SUCCESS ADMIN_PASSWORD_CHANGED ADMIN_ACCESS_DENIED; do
  echo "$audit" | jq -e --arg a "$a" '[.items[].action] | index($a)' >/dev/null && ok "감사에 $a" || fail "감사에 $a 없음: $(echo "$audit" | jq -c '[.items[].action]')"
done
audit=$(curl -sf "$HUB_URL/api/v1/admin/audit?agencyCode=$SERVICE_CODE&size=50" "${adm[@]}") || fail "감사(기관) 조회 실패"
[ "$(echo "$audit" | jq -r .total)" -ge 1 ] && ok "기관 $SERVICE_CODE 의 관리 행위가 감사에 있다 (n=$(echo "$audit" | jq -r .total))" || fail "기관 관리 행위 감사 없음"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HUB_URL/api/v1/admin/auth/logout" "${adm[@]}")
[ "$code" = "204" ] && ok "로그아웃 204" || fail "로그아웃 $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$HUB_URL/api/v1/admin/auth/me" "${adm[@]}")
[ "$code" = "401" ] && ok "로그아웃 뒤 세션은 401" || fail "로그아웃 뒤 세션이 $code"

echo "설치본 스모크 통과 — hub=$HUB_URL gate=$GATE_URL registry=$REGISTRY_URL authz=$IDEM_AUTHZ_URL issuer=$ISSUER"
