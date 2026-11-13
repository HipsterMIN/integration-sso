#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════
# 1.2 업그레이드 — Keycloak 을 26.8.2 로 올린 **기존** realm 에서 한 번 실행한다 (멱등: 다시 돌려도 된다).
#
# 왜: Keycloak 26.8.2 는 관리 REST API 를 두 군데 조였고, 마이그레이션은 Idem 의 서비스 계정 client 까지 맞춰 주지 않는다.
#   ① 관리 API 는 토큰에 client scope `kc-admin-api`(와 그 scope 가 넣는 audience `{공개 URL}/admin/realms/{realm}`)가 있어야 받는다(없으면 401).
#      마이그레이션은 이 scope 를 만들어 내장 client(admin-cli·security-admin-console)에만 붙인다 — Idem 의 둘은 빠진다:
#        · idem-provisioner     — hub 가 기관 OIDC client 를 프로비저닝 (없으면 OIDC_RP 프로파일 저장이 503 E-IDO-122 / 401)
#        · idem-session-manager — gate 가 단일 로그아웃 때 Keycloak 세션을 끊는다 (없으면 502 → hub 재시도 큐)
#   ② manage-realm 이 없는 관리자(= idem-provisioner, manage-clients 만 가진다)가 다룰 수 있는 protocol mapper 종류는 realm 속성
#      `allowedProtocolMapperTypesForClientAdministrators` 에 적힌 것뿐이다(비어 있으면 전부 거부 → 403). hub 는 기관 client 를 만들 때
#      mapper 둘(oidc-usersessionmodel-note-mapper = identity_provider, oidc-hardcoded-claim-mapper = idem_service)을 넣는다 —
#      이 속성이 없으면 **새 기관의 OIDC_RP 온보딩이 503 E-IDO-122 / 403** 이 된다(이미 만든 client 의 갱신·secret 회전은 된다).
# 이 스크립트: ① 두 client 에 `kc-admin-api` 를 기본 client scope 로 붙이고 ② realm 속성에 그 두 mapper 종류를 더한다(있던 값은 유지)
#             ③ 서비스 계정 토큰으로 실제 호출해 확인한다(비밀이 있을 때 — 시험 client 를 만들었다 지운다).
#
# 설치 realm 정리(1.2 PR-1.2-3a) — 1.1.x 까지의 realm import 가 넣던 것 가운데 운영 설치에 남으면 안 되는 것을 걷어 낸다.
# Keycloak 판과 무관하다(24 에 머문 1.1.x 설치본에서도 돌릴 수 있다 — 그때 ①~③ 은 건너뛴다):
#   ④ 기본 시험 계정 `poc-testuser` — **비밀번호가 저장소에 공개돼 있다**. 표준 로그인 화면에서 이 계정으로 OIDC_RP 서비스에 들어갈 수 있다.
#      기본은 비활성화 + 세션 종료(되돌릴 수 있다). TEST_USER_ACTION=delete 면 지운다. 시험에는 scripts/dev/kc-test-user.py 로 만든 사용자를 쓴다
#   ⑤ realm 표시 이름이 옛 제품명 "OnePass 통합인증" 그대로면 "Idem 통합인증" 으로, 국제화가 꺼져 있으면 켠다(ko 기본, en) — 로그인 화면이 한국어로 뜬다.
#      운영자가 바꿔 둔 값(다른 표시 이름·이미 켠 국제화)은 건드리지 않는다
#   ⑥ 자리표시자 client id(`your-…-client-id`)인 채로 켜져 있는 인증 제공자(기본 설치의 "카카오 로그인")를 끈다 — 눌러도 동작하지 않는 버튼이다
#   ⑦ `sslRequired` 는 알리기만 한다(새 설치의 기본은 external — 사설망 밖에서 오는 요청은 HTTPS 여야 한다). SSL_REQUIRED=external 을 주면 바꾼다
# 새 설치는 realm-export.json 이 처음부터 이 상태다(돌려도 전부 "이미 있음"·"없음").
#
#   compose:  set -a; . infra/docker/install.env; set +a
#             KC_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" scripts/upgrade/keycloak-realm-1.2.sh
#   K8s:      kubectl -n idem port-forward svc/idem-keycloak 8088:8080 &   → 위와 같이 (Secret 의 KEYCLOAK_ADMIN_PASSWORD)
#
#   KC_ADMIN_URL       Keycloak 주소 (기본 http://localhost:8088 — compose 의 관리 포트)
#   KC_ADMIN_USER      Keycloak 관리자 (기본 admin)      KC_ADMIN_PASSWORD  필수 (설치본의 KEYCLOAK_ADMIN_PASSWORD)
#   KC_ADMIN_REALM     관리자 계정의 realm (기본 master)  REALM  대상 realm (기본 idem)
#   CLIENTS            scope 를 붙일 client (기본 "idem-provisioner idem-session-manager")
#   MAPPER_TYPES       허용할 mapper 종류 (기본 "oidc-usersessionmodel-note-mapper oidc-hardcoded-claim-mapper" — hub 가 쓰는 둘)
#   KEYCLOAK_PROVISIONER_CLIENT_SECRET / KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET
#                      있으면 ③ 을 한다 (install.env 를 읽었으면 자동). VERIFY=0 이면 건너뛴다
#   TEST_USER          ④ 의 계정 이름 (기본 poc-testuser)      TEST_USER_ACTION  disable(기본) | delete | keep
#   SSL_REQUIRED       ⑦ 주면 그 값으로 바꾼다 (external | all | none). 안 주면 지금 값만 알린다
#   REALM_CLEANUP=0    ④~⑦ 을 건너뛴다
#
# 비밀번호·토큰은 출력하지 않는다. 필요: curl, python3.
# 끝난 뒤 hub·gate 는 재기동하지 않아도 된다 — 1.2 의 hub·gate 는 401 을 받으면 캐시한 토큰을 버리고 새로 받는다.
# ══════════════════════════════════════════════════════════════════════════
set -euo pipefail

KC="${KC_ADMIN_URL:-http://localhost:8088}"; KC="${KC%/}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:?KC_ADMIN_PASSWORD 를 설정하세요 (설치본의 KEYCLOAK_ADMIN_PASSWORD)}"
ADMIN_REALM="${KC_ADMIN_REALM:-master}"
REALM="${REALM:-idem}"
CLIENTS="${CLIENTS:-idem-provisioner idem-session-manager}"
MAPPER_TYPES="${MAPPER_TYPES:-oidc-usersessionmodel-note-mapper oidc-hardcoded-claim-mapper}"
VERIFY="${VERIFY:-1}"
TEST_USER="${TEST_USER:-poc-testuser}"
TEST_USER_ACTION="${TEST_USER_ACTION:-disable}"
SSL_REQUIRED="${SSL_REQUIRED:-}"
REALM_CLEANUP="${REALM_CLEANUP:-1}"
OLD_DISPLAY_NAME="OnePass 통합인증"
NEW_DISPLAY_NAME="Idem 통합인증"
SCOPE="kc-admin-api"
ATTR="allowedProtocolMapperTypesForClientAdministrators"

ok()   { echo "  ✅ $*"; }
info() { echo "  · $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
for t in curl python3; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# json <파이썬 식> — stdin 의 JSON 을 d 로 받아 식을 출력
json() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$1"; }

case "$TEST_USER_ACTION" in disable|delete|keep) ;; *) fail "TEST_USER_ACTION 은 disable | delete | keep 가운데 하나다: $TEST_USER_ACTION" ;; esac
case "$SSL_REQUIRED" in ""|external|all|none) ;; *) fail "SSL_REQUIRED 는 external | all | none 가운데 하나다: $SSL_REQUIRED" ;; esac

echo "Keycloak $KC — realm $REALM 을 1.2 의 계약에 맞춘다(26.8.2 의 관리 API 조건 + 설치 realm 정리)"
code=$(curl -s -o "$tmp/tok.json" -w '%{http_code}' -X POST "$KC/realms/$ADMIN_REALM/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$ADMIN_USER" --data-urlencode "password=$ADMIN_PASSWORD") || fail "Keycloak 에 닿지 않는다: $KC"
[ "$code" = "200" ] || fail "관리자 토큰 발급 실패($code) — KC_ADMIN_USER/KC_ADMIN_PASSWORD 를 확인하세요 (임시 관리자를 지웠다면 realm 관리 권한이 있는 계정으로)"
ADM=$(json 'd["access_token"]' < "$tmp/tok.json")
adm() { curl -s -H "Authorization: Bearer $ADM" -H 'Content-Type: application/json' "$@"; }

ver=$(adm "$KC/admin/serverinfo" | json 'd.get("systemInfo",{}).get("version","?")' 2>/dev/null || echo "?")
info "Keycloak 버전 $ver"

code=$(adm -o "$tmp/scopes.json" -w '%{http_code}' "$KC/admin/realms/$REALM/client-scopes")
[ "$code" = "200" ] || fail "client scope 목록 조회 실패($code) — realm '$REALM' 이 있는지, 계정에 realm 관리 권한이 있는지 확인하세요"
SCOPE_ID=$(json "next((s['id'] for s in d if s['name']=='$SCOPE'), '')" < "$tmp/scopes.json")
changed=0

# ── ④~⑦ 설치 realm 정리 — 운영자가 바꿔 둔 값은 건드리지 않는다(옛 기본값 그대로일 때만 바꾼다) ──────────────────────────
realm_cleanup() {
  if [ "$REALM_CLEANUP" != "1" ]; then info "REALM_CLEANUP=0 — 설치 realm 정리(④~⑦)는 건너뛴다"; return 0; fi
  local code uid enabled plan aliases alias now

  echo "④ 기본 시험 계정 '$TEST_USER' — 1.1.x 까지 realm import 에 들어 있던 계정(비밀번호가 저장소에 공개돼 있다)"
  code=$(adm -o "$tmp/users.json" -w '%{http_code}' -G "$KC/admin/realms/$REALM/users" --data-urlencode "username=$TEST_USER" -d exact=true)
  [ "$code" = "200" ] || fail "사용자 조회 실패($code)"
  uid=$(json "d[0]['id'] if d else ''" < "$tmp/users.json")
  enabled=$(json "d[0].get('enabled') if d else ''" < "$tmp/users.json")
  if [ -z "$uid" ]; then
    ok "없음"
  elif [ "$TEST_USER_ACTION" = "delete" ]; then
    code=$(adm -o /dev/null -w '%{http_code}' -X DELETE "$KC/admin/realms/$REALM/users/$uid")
    [ "$code" = "204" ] || fail "'$TEST_USER' 를 지우지 못했다($code)"
    ok "지웠다"; changed=$((changed + 1))
  elif [ "$TEST_USER_ACTION" = "keep" ]; then
    echo "  ⚠ 남겨 둔다(TEST_USER_ACTION=keep, enabled=$enabled) — 비밀번호가 공개된 계정이다. 운영 realm 이면 비밀번호를 바꾸거나 지운다"
  elif [ "$enabled" = "False" ]; then
    ok "이미 비활성 — 쓰지 않는 계정이면 TEST_USER_ACTION=delete 로 지운다"
  else
    # 사용자 표현을 통째로 읽어 enabled 만 바꿔 되쓴다(일부만 보내면 판에 따라 프로필 항목이 비워질 수 있다)
    code=$(adm -o "$tmp/user.json" -w '%{http_code}' "$KC/admin/realms/$REALM/users/$uid")
    [ "$code" = "200" ] || fail "'$TEST_USER' 조회 실패($code)"
    python3 - "$tmp/user.json" "$tmp/user-new.json" <<'PY'
import json, sys
u = json.load(open(sys.argv[1], encoding='utf-8'))
u['enabled'] = False
json.dump(u, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
PY
    code=$(adm -o "$tmp/user-put.txt" -w '%{http_code}' -X PUT "$KC/admin/realms/$REALM/users/$uid" --data-binary "@$tmp/user-new.json")
    [ "$code" = "204" ] || fail "'$TEST_USER' 를 비활성화하지 못했다($code): $(head -c 200 "$tmp/user-put.txt")"
    code=$(adm -o /dev/null -w '%{http_code}' -X POST "$KC/admin/realms/$REALM/users/$uid/logout")
    [ "$code" = "204" ] || info "세션 종료 응답 $code (계정은 비활성화됐다)"
    ok "비활성화하고 세션을 끊었다 — 로그인할 수 없다. 쓰지 않는 계정이면 TEST_USER_ACTION=delete 로 지운다"; changed=$((changed + 1))
  fi

  echo "⑤ 표시 이름·국제화${SSL_REQUIRED:+·sslRequired}"
  code=$(adm -o "$tmp/realm-now.json" -w '%{http_code}' "$KC/admin/realms/$REALM")
  [ "$code" = "200" ] || fail "realm 조회 실패($code)"
  # 보내는 것은 바꿀 항목뿐이다(attributes 는 보내지 않는다 — 보내면 속성 맵이 통째로 교체된다)
  plan=$(OLD="$OLD_DISPLAY_NAME" NEW="$NEW_DISPLAY_NAME" SSL="$SSL_REQUIRED" python3 - "$tmp/realm-now.json" "$tmp/realm-cleanup.json" <<'PY'
import json, os, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
out, notes = {}, []
name = r.get('displayName') or ''
if name == os.environ['OLD']:
    out['displayName'] = os.environ['NEW']; notes.append('표시 이름 "%s" → "%s"' % (name, os.environ['NEW']))
elif name and name != os.environ['NEW']:
    notes.append('KEEP 표시 이름 "%s"(운영자가 정한 값 — 그대로 둔다)' % name)
if not r.get('internationalizationEnabled'):
    out.update(internationalizationEnabled=True, supportedLocales=['ko', 'en'], defaultLocale='ko')
    notes.append('국제화 켬(ko 기본, en)')
ssl = os.environ['SSL']
if ssl and (r.get('sslRequired') or '').lower() != ssl:
    out['sslRequired'] = ssl; notes.append('sslRequired %s → %s' % (r.get('sslRequired'), ssl))
json.dump(out, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
print(('CHANGE' if out else 'SAME') + '|' + ' · '.join(n for n in notes if not n.startswith('KEEP')) + '|' + ' · '.join(n[5:] for n in notes if n.startswith('KEEP')))
PY
)
  [ -n "${plan##*|}" ] && info "${plan##*|}"
  if [ "${plan%%|*}" = "SAME" ]; then
    ok "이미 있음 (표시 이름 \"$(json "d.get('displayName')" < "$tmp/realm-now.json")\", 국제화 $(json "d.get('internationalizationEnabled')" < "$tmp/realm-now.json") 기본 $(json "d.get('defaultLocale')" < "$tmp/realm-now.json"))"
  else
    code=$(adm -o "$tmp/realm-put2.txt" -w '%{http_code}' -X PUT "$KC/admin/realms/$REALM" --data-binary "@$tmp/realm-cleanup.json")
    [ "$code" = "204" ] || fail "realm 설정을 쓰지 못했다($code): $(head -c 300 "$tmp/realm-put2.txt")"
    plan="${plan#*|}"; ok "바꿨다: ${plan%%|*}"; changed=$((changed + 1))
  fi

  echo "⑥ 자리표시자 인증 제공자 — client id 가 'your-…-client-id' 인 채로 켜져 있는 것"
  code=$(adm -o "$tmp/idps.json" -w '%{http_code}' "$KC/admin/realms/$REALM/identity-provider/instances")
  [ "$code" = "200" ] || fail "인증 제공자 목록 조회 실패($code)"
  aliases=$(json "' '.join(i['alias'] for i in d if i.get('enabled') and __import__('re').fullmatch(r'your-[a-z0-9-]+-client-id', (i.get('config') or {}).get('clientId') or ''))" < "$tmp/idps.json")
  if [ -z "$aliases" ]; then
    ok "없음"
  else
    for alias in $aliases; do
      code=$(adm -o "$tmp/idp.json" -w '%{http_code}' "$KC/admin/realms/$REALM/identity-provider/instances/$alias")
      [ "$code" = "200" ] || fail "인증 제공자 '$alias' 조회 실패($code)"
      python3 - "$tmp/idp.json" "$tmp/idp-new.json" <<'PY'
import json, sys
i = json.load(open(sys.argv[1], encoding='utf-8'))
i['enabled'] = False
json.dump(i, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
PY
      code=$(adm -o "$tmp/idp-put.txt" -w '%{http_code}' -X PUT "$KC/admin/realms/$REALM/identity-provider/instances/$alias" --data-binary "@$tmp/idp-new.json")
      [ "$code" = "204" ] || fail "인증 제공자 '$alias' 를 끄지 못했다($code): $(head -c 200 "$tmp/idp-put.txt")"
      ok "$alias — 껐다(로그인 화면에서 버튼이 사라진다). 실제 client id·secret 을 넣은 뒤 Keycloak 관리 콘솔에서 켠다"; changed=$((changed + 1))
    done
  fi

  now=$(adm "$KC/admin/realms/$REALM" | json "d.get('sslRequired')")
  echo "⑦ sslRequired — 지금 값 $now"
  case "$now" in
    none|NONE) info "HTTP 를 어디서나 받는다. 새 설치의 기본은 external(사설망·루프백 밖에서 오는 요청은 HTTPS 여야 한다) — 공개 주소가 HTTPS 면 SSL_REQUIRED=external 로 다시 실행해 맞춘다" ;;
    *) ok "$now" ;;
  esac
}

if [ -z "$SCOPE_ID" ]; then
  ok "realm 에 '$SCOPE' scope 가 없다 — Keycloak $ver 는 26.8.2 의 관리 API 검사를 하지 않는다. ①~③ 은 할 일 없음"
  realm_cleanup
  echo "끝 — 바꾼 곳 $changed."
  exit 0
fi

echo "① client scope '$SCOPE' — 서비스 계정 client 의 기본 scope"
for cid in $CLIENTS; do
  code=$(adm -o "$tmp/client.json" -w '%{http_code}' "$KC/admin/realms/$REALM/clients?clientId=$cid")
  [ "$code" = "200" ] || fail "client '$cid' 조회 실패($code)"
  CUUID=$(json "next((c['id'] for c in d if c['clientId']=='$cid'), '')" < "$tmp/client.json")
  [ -n "$CUUID" ] || fail "client '$cid' 가 realm '$REALM' 에 없다"
  has=$(adm "$KC/admin/realms/$REALM/clients/$CUUID/default-client-scopes" | json "any(s['name']=='$SCOPE' for s in d)")
  if [ "$has" = "True" ]; then
    ok "$cid — 이미 있음"
  else
    code=$(adm -o /dev/null -w '%{http_code}' -X PUT "$KC/admin/realms/$REALM/clients/$CUUID/default-client-scopes/$SCOPE_ID")
    [ "$code" = "204" ] || fail "$cid 에 scope 를 붙이지 못했다($code)"
    ok "$cid — 붙였다"; changed=$((changed + 1))
  fi
done

echo "② realm 속성 $ATTR — client 관리자가 쓸 수 있는 protocol mapper 종류"
# realm 갱신은 보낸 attributes 맵으로 속성을 **교체**한다(맵에서 빠진 속성은 지워진다) — 그래서 지금 속성을 전부 읽어 값 하나만 더한 맵을 보낸다.
# 다른 항목은 보내지 않는다(보내지 않은 항목은 그대로) — 바뀌는 것은 이 속성 하나다
code=$(adm -o "$tmp/realm.json" -w '%{http_code}' "$KC/admin/realms/$REALM")
[ "$code" = "200" ] || fail "realm 조회 실패($code)"
merged=$(MAPPER_TYPES="$MAPPER_TYPES" ATTR="$ATTR" python3 - "$tmp/realm.json" "$tmp/realm-new.json" <<'PY'
import json, os, sys
attrs = dict(json.load(open(sys.argv[1], encoding='utf-8')).get('attributes') or {})
have = [t.strip() for t in (attrs.get(os.environ['ATTR']) or '').split(',') if t.strip()]
want = os.environ['MAPPER_TYPES'].split()
missing = [t for t in want if t not in have]
attrs[os.environ['ATTR']] = ','.join(have + missing)
json.dump({'attributes': attrs}, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False)
print(('ADD ' + ' '.join(missing)) if missing else 'SAME')
PY
)
if [ "$merged" = "SAME" ]; then
  ok "이미 있음 ($(echo $MAPPER_TYPES | tr ' ' ','))"
else
  code=$(adm -o "$tmp/realm-put.txt" -w '%{http_code}' -X PUT "$KC/admin/realms/$REALM" --data-binary "@$tmp/realm-new.json")
  [ "$code" = "204" ] || fail "realm 속성을 쓰지 못했다($code): $(head -c 300 "$tmp/realm-put.txt")"
  now=$(adm "$KC/admin/realms/$REALM" | json "d.get('attributes',{}).get('$ATTR','')")
  ok "더했다: ${merged#ADD } → 지금 값 $now"; changed=$((changed + 1))
fi

# ③ 실제 호출로 확인 (비밀이 있을 때) — hub·gate 가 쓰는 서비스 계정 토큰 그대로
sa_token() {  # sa_token <client_id> <secret> → $tmp/sa.tok (없으면 1)
  local code
  code=$(curl -s -o "$tmp/sa.json" -w '%{http_code}' -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
    -d grant_type=client_credentials -d "client_id=$1" --data-urlencode "client_secret=$2")
  [ "$code" = "200" ] || fail "$1 서비스 계정 토큰 발급 실패($code) — client secret 을 확인하세요"
  json 'd["access_token"]' < "$tmp/sa.json" > "$tmp/sa.tok"
}
sa() { curl -s -H "Authorization: Bearer $(cat "$tmp/sa.tok")" -H 'Content-Type: application/json' "$@"; }
if [ "$VERIFY" = "1" ]; then
  echo "③ 확인 — 서비스 계정 토큰으로 관리 API 를 실제로 부른다"
  if [ -n "${KEYCLOAK_PROVISIONER_CLIENT_SECRET:-}" ] && case " $CLIENTS " in *" idem-provisioner "*) true ;; *) false ;; esac; then
    sa_token idem-provisioner "$KEYCLOAK_PROVISIONER_CLIENT_SECRET"
    code=$(sa -o /dev/null -w '%{http_code}' "$KC/admin/realms/$REALM/clients?first=0&max=1")
    [ "$code" = "200" ] && ok "idem-provisioner — client 조회 200" || fail "idem-provisioner 토큰으로 client 조회가 $code"
    # hub 가 기관 client 를 만들 때와 같은 mapper 종류로 시험 client 를 만들었다 지운다 (비활성·흐름 전부 끔 — 로그인에 쓸 수 없다)
    probe="idem-upgrade-check-$(date +%s)-$RANDOM"
    body=$(PROBE="$probe" python3 -c 'import json, os
print(json.dumps({"clientId": os.environ["PROBE"], "name": "Idem 1.2 upgrade check (지워도 된다)", "enabled": False, "protocol": "openid-connect",
  "publicClient": False, "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False,
  "fullScopeAllowed": False, "protocolMappers": [
    {"name": "check-note", "protocol": "openid-connect", "protocolMapper": "oidc-usersessionmodel-note-mapper",
     "config": {"user.session.note": "identity_provider", "claim.name": "identity_provider", "jsonType.label": "String", "access.token.claim": "true"}},
    {"name": "check-hardcoded", "protocol": "openid-connect", "protocolMapper": "oidc-hardcoded-claim-mapper",
     "config": {"claim.name": "idem_service", "claim.value": "UPGRADE_CHECK", "jsonType.label": "String", "access.token.claim": "true"}}]}))')
    code=$(sa -o "$tmp/create.txt" -D "$tmp/create.hdr" -w '%{http_code}' -X POST "$KC/admin/realms/$REALM/clients" -d "$body")
    [ "$code" = "201" ] || fail "idem-provisioner 토큰으로 client 생성(mapper 포함)이 $code — $(head -c 200 "$tmp/create.txt")"
    puuid=$(tr -d '\r' < "$tmp/create.hdr" | awk 'tolower($1) == "location:" { n = split($2, a, "/"); print a[n] }')
    code=$(sa -o /dev/null -w '%{http_code}' -X DELETE "$KC/admin/realms/$REALM/clients/$puuid")
    [ "$code" = "204" ] && ok "idem-provisioner — client 생성(mapper 둘 포함) 201 → 삭제 204" \
      || fail "시험 client '$probe' 를 지우지 못했다($code) — Keycloak 관리 콘솔에서 지우세요"
  else
    info "idem-provisioner — client secret 이 없어 호출 확인은 건너뛴다 (KEYCLOAK_PROVISIONER_CLIENT_SECRET)"
  fi
  if [ -n "${KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET:-}" ] && case " $CLIENTS " in *" idem-session-manager "*) true ;; *) false ;; esac; then
    sa_token idem-session-manager "$KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET"
    code=$(sa -o /dev/null -w '%{http_code}' "$KC/admin/realms/$REALM/users?first=0&max=1")
    [ "$code" = "200" ] && ok "idem-session-manager — 사용자 조회 200" || fail "idem-session-manager 토큰으로 사용자 조회가 $code"
  else
    info "idem-session-manager — client secret 이 없어 호출 확인은 건너뛴다 (KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET)"
  fi
fi

realm_cleanup

echo "끝 — 바꾼 곳 $changed. 이어서: 설치본 스모크(scripts/ci/install-smoke.sh ③·④′) 또는 관리 콘솔에서 OIDC_RP 프로파일 저장"
