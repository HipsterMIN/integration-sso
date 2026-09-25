#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 설치본 스모크 (D3) — CI(.github/workflows/ci.yml smoke-test)와 로컬이 같은 스크립트를 쓴다.
#
# 전제: registry·authz·gate·hub 가 compose.install.yml 의 환경 계약으로 떠 있고, Keycloak(realm onepass, import 완료)이
#       gate 뒤에 있다(KC_HOSTNAME_URL = gate 공개 URL). hub 는 Mock 본인확인 플러그인이 켜져 있다.
#
# 검사: ① 4개 헬스 ② gate 를 통한 OIDC Discovery(issuer = gate 공개 URL) ③ OIDC_RP 프로파일 PUT → Keycloak client 생성·secret 회전
#       ④ gate 프런트가 Keycloak 로그인 화면을 프록시(client 존재·PKCE 사전검사) ⑤ Mock 본인확인 → registry 등록 라운드트립
#       ⑥ registry 이벤트 피드(Kafka 없는 상태 전파) ⑦ 코어 에디션이면 KR 전용 엔드포인트 404
#
#   HUB_URL GATE_URL REGISTRY_URL AUTHZ_URL   기본 localhost:8083/8081/8082/8086
#   ISSUER                                     기본 $GATE_URL/realms/onepass
#   QIM_INTERNAL_API_KEY                       있으면 ⑥ 을 검사한다
#   IDEM_EDITION                               core(기본) | kr
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

HUB_URL="${HUB_URL:-http://localhost:8083}"
GATE_URL="${GATE_URL:-http://localhost:8081}"
REGISTRY_URL="${REGISTRY_URL:-http://localhost:8082}"
AUTHZ_URL="${AUTHZ_URL:-http://localhost:8086}"
ISSUER="${ISSUER:-$GATE_URL/realms/onepass}"
SERVICE_CODE="${SERVICE_CODE:-SMOKE_RP}"
EDITION="${IDEM_EDITION:-core}"
CID="install-smoke-$(date +%s)"

ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
for t in curl jq; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done

echo "① 헬스"
for u in "$HUB_URL" "$GATE_URL" "$REGISTRY_URL" "$AUTHZ_URL"; do
  st=$(curl -sf "$u/actuator/health" | jq -r '.status' 2>/dev/null || echo "DOWN")
  [ "$st" = "UP" ] && ok "$u UP" || fail "$u 헬스 실패: $st"
done

echo "② Discovery (gate 프런트)"
disc=$(curl -sf "$GATE_URL/realms/onepass/.well-known/openid-configuration") || fail "discovery 응답 없음"
iss=$(echo "$disc" | jq -r '.issuer')
[ "$iss" = "$ISSUER" ] && ok "issuer = $iss" || fail "issuer 불일치: $iss (기대 $ISSUER)"
for k in authorization_endpoint token_endpoint jwks_uri end_session_endpoint; do
  v=$(echo "$disc" | jq -r ".$k")
  case "$v" in "$ISSUER"/*) ok "$k = $v" ;; *) fail "$k 가 issuer 아래가 아님: $v" ;; esac
done
echo "$disc" | jq -e '.code_challenge_methods_supported | index("S256")' >/dev/null && ok "PKCE S256" || fail "code_challenge_methods_supported 에 S256 없음"

echo "③ OIDC_RP 프로파일 PUT → Keycloak client 프로비저닝"
body=$(cat <<JSON
{"schemaVersion":1,
 "service":{"code":"$SERVICE_CODE","name":"설치 스모크 RP","status":"ACTIVE"},
 "protocol":{"type":"OIDC_RP","oidc":{"redirectUris":["https://rp.example.org/callback"],"postLogoutRedirectUris":["https://rp.example.org/"]}},
 "policy":{"minAuthLevel":"L1","session":{"idleMinutes":20,"absoluteMinutes":240,"concurrent":1}}}
JSON
)
code=$(curl -s -o /tmp/smoke-put.json -w '%{http_code}' -X PUT "$HUB_URL/api/v1/admin/services/$SERVICE_CODE/profile" \
       -H 'Content-Type: application/json' -H 'X-Admin-Id: install-smoke' -H "X-Correlation-Id: $CID" -d "$body")
[ "$code" = "200" ] || [ "$code" = "201" ] && ok "profile PUT $code" || fail "profile PUT $code: $(cat /tmp/smoke-put.json)"
status=$(curl -sf "$HUB_URL/api/v1/admin/services/$SERVICE_CODE/oidc-client" -H "X-Correlation-Id: $CID") || fail "oidc-client 조회 실패"
[ "$(echo "$status" | jq -r .clientId)" = "idem-svc-$SERVICE_CODE" ] && ok "clientId = idem-svc-$SERVICE_CODE" || fail "clientId 불일치: $status"
[ "$(echo "$status" | jq -r .provisioned)" = "true" ] && ok "Keycloak 에 client 있음(provisioned)" || fail "client 미프로비저닝: $status"
[ "$(echo "$status" | jq -r .issuer)" = "$ISSUER" ] && ok "client issuer = $ISSUER" || fail "client issuer 불일치: $status"
echo "$status" | jq -e '.redirectUris | index("https://rp.example.org/callback")' >/dev/null && ok "redirectUris 반영" || fail "redirectUris 미반영: $status"
secret=$(curl -sf -X POST "$HUB_URL/api/v1/admin/services/$SERVICE_CODE/oidc-client/secret" -H 'X-Admin-Id: install-smoke' -H "X-Correlation-Id: $CID" | jq -r '.clientSecret')
[ -n "$secret" ] && [ "$secret" != "null" ] && [ ${#secret} -ge 16 ] && ok "client secret 회전 (길이 ${#secret})" || fail "secret 회전 실패"

echo "④ gate 프런트 → Keycloak 로그인 화면 (client 존재·PKCE 사전검사)"
challenge=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n' | cut -c1-43)
auth_url="$GATE_URL/realms/onepass/protocol/openid-connect/auth?client_id=idem-svc-$SERVICE_CODE&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Frp.example.org%2Fcallback&state=smoke&code_challenge=$challenge&code_challenge_method=S256"
code=$(curl -s -o /tmp/smoke-auth.html -w '%{http_code}' "$auth_url")
[ "$code" = "200" ] && grep -qi "kc-form-login\|kc-login\|<form" /tmp/smoke-auth.html && ok "로그인 화면 200 (프록시)" || fail "auth 응답 $code (로그인 화면 아님): $(head -c 300 /tmp/smoke-auth.html)"
code=$(curl -s -o /dev/null -w '%{http_code}' "$GATE_URL/realms/onepass/protocol/openid-connect/auth?client_id=idem-svc-$SERVICE_CODE&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Frp.example.org%2Fcallback&state=smoke")
[ "$code" = "400" ] && ok "PKCE 없는 요청은 400" || fail "PKCE 없는 요청이 $code"
code=$(curl -s -o /dev/null -w '%{http_code}' "$GATE_URL/realms/onepass/protocol/openid-connect/auth?client_id=not-idem&response_type=code&scope=openid&redirect_uri=https%3A%2F%2Frp.example.org%2Fcallback&state=smoke&code_challenge=$challenge&code_challenge_method=S256")
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

if [ -n "${QIM_INTERNAL_API_KEY:-}" ]; then
  echo "⑥ registry 이벤트 피드 (Kafka 없는 상태 전파)"
  feed=$(curl -sf "$REGISTRY_URL/api/v1/internal/events?limit=5" -H "X-Internal-Api-Key: $QIM_INTERNAL_API_KEY" -H "X-Correlation-Id: $CID") || fail "이벤트 피드 실패"
  echo "$feed" | jq -e 'type == "array"' >/dev/null && ok "이벤트 피드 응답 (n=$(echo "$feed" | jq length))" || fail "이벤트 피드 형식: $feed"
else
  echo "⑥ (QIM_INTERNAL_API_KEY 없음 — 이벤트 피드 검사 생략)"
fi

if [ "$EDITION" = "core" ]; then
  echo "⑦ 코어 에디션: KR 전용 엔드포인트는 없다"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$HUB_URL/api/v1/auth/nice/ci-check" "${h[@]}" -d '{"ci":"","mbrDvsnCd":"A101"}')
  [ "$code" = "404" ] && ok "/api/v1/auth/nice/ci-check 404" || fail "KR 엔드포인트가 $code"
fi

echo "설치본 스모크 통과 — hub=$HUB_URL gate=$GATE_URL registry=$REGISTRY_URL authz=$AUTHZ_URL issuer=$ISSUER"
