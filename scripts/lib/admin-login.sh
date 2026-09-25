#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 관리자 로그인 (S7) — hub 관리 API 는 관리자 세션 + 2단계(TOTP) 뒤에 있다. 이 스크립트가 로그인해 세션 쿠키 값을 stdout 에 낸다.
#
#   SID=$(scripts/lib/admin-login.sh)
#   curl "$HUB_URL/api/v1/admin/agencies" -H "Cookie: idemAdminSid=$SID" -H 'X-Requested-With: cli'
#
#   HUB_URL                     hub 주소 (기본 http://localhost:8083)
#   IDEM_ADMIN_USERNAME         기본 admin
#   IDEM_ADMIN_PASSWORD         필수 (부트스트랩은 IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD 값)
#   IDEM_ADMIN_TOTP_SECRET      2단계 등록이 끝난 계정이면 그 비밀(base32). 첫 로그인은 서버가 새 비밀을 주고 이 스크립트가 등록한다
#   IDEM_ADMIN_TOTP_SECRET_FILE 있으면 등록된 비밀을 여기서 읽고/여기에 쓴다(0600). 개발용 — 운영 관리자는 인증 앱을 쓴다
#   IDEM_ADMIN_NEW_PASSWORD     첫 로그인에서 비밀번호 변경이 요구되면(E-IDO-137) 이 값으로 바꾼다. 없으면 실패한다
#
# 비밀번호·비밀은 로그에 남기지 않는다. 필요: curl, python3(TOTP 계산·JSON).
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

HUB_URL="${HUB_URL:-http://localhost:8083}"
USERNAME="${IDEM_ADMIN_USERNAME:-admin}"
PASSWORD="${IDEM_ADMIN_PASSWORD:?IDEM_ADMIN_PASSWORD 를 설정하세요}"
SECRET="${IDEM_ADMIN_TOTP_SECRET:-}"
SECRET_FILE="${IDEM_ADMIN_TOTP_SECRET_FILE:-}"
CSRF='X-Requested-With: admin-login.sh'
for t in curl python3; do command -v "$t" >/dev/null 2>&1 || { echo "필요한 명령이 없습니다: $t" >&2; exit 1; }; done

if [ -z "$SECRET" ] && [ -n "$SECRET_FILE" ] && [ -f "$SECRET_FILE" ]; then SECRET=$(cat "$SECRET_FILE"); fi

json() { python3 -c 'import json,sys; d=json.load(sys.stdin); v=d
for k in sys.argv[1:]:
    v = v.get(k, "") if isinstance(v, dict) else ""
print("" if v is None else v)' "$@"; }

totp() { python3 - "$1" <<'PY'
import base64, hmac, hashlib, struct, sys, time
s = sys.argv[1].strip().upper(); s += "=" * (-len(s) % 8)
key = base64.b32decode(s)
c = int(time.time()) // 30
h = hmac.new(key, struct.pack(">Q", c), hashlib.sha1).digest()
o = h[-1] & 0x0F
print("%06d" % (((h[o] & 0x7F) << 24 | (h[o+1] & 0xFF) << 16 | (h[o+2] & 0xFF) << 8 | (h[o+3] & 0xFF)) % 1000000))
PY
}

cookie_of() { sed -n 's/^[Ss]et-[Cc]ookie: idemAdminSid=\([^;]*\).*/\1/p' "$1" | head -1; }

hdr=$(mktemp); body=$(mktemp); trap 'rm -f "$hdr" "$body"' EXIT
req=$(python3 -c 'import json,sys; print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))' "$USERNAME" "$PASSWORD")
code=$(curl -s -o "$body" -D "$hdr" -w '%{http_code}' -X POST "$HUB_URL/api/v1/admin/auth/login" -H 'Content-Type: application/json' -H "$CSRF" -d "$req")
[ "$code" = "200" ] || { echo "관리자 로그인 실패 (HTTP $code): $(json code < "$body") $(json message < "$body")" >&2; exit 1; }
status=$(json status < "$body")
case "$status" in
  OK) SID=$(cookie_of "$hdr") ;;
  MFA_REQUIRED|MFA_ENROLL_REQUIRED)
    token=$(json mfaToken < "$body")
    if [ "$status" = "MFA_ENROLL_REQUIRED" ]; then
      SECRET=$(json secret < "$body")
      if [ -n "$SECRET_FILE" ]; then (umask 077; printf '%s' "$SECRET" > "$SECRET_FILE"); echo "2단계 비밀을 등록하고 $SECRET_FILE 에 저장했다 (0600)" >&2
      else echo "2단계 비밀을 등록했다 — 다음 로그인은 IDEM_ADMIN_TOTP_SECRET(또는 _FILE) 이 필요하다" >&2; fi
    elif [ -z "$SECRET" ]; then
      echo "2단계 인증이 필요한데 IDEM_ADMIN_TOTP_SECRET 이 없다 (username=$USERNAME)" >&2; exit 1
    fi
    req=$(python3 -c 'import json,sys; print(json.dumps({"mfaToken": sys.argv[1], "code": sys.argv[2]}))' "$token" "$(totp "$SECRET")")
    code=$(curl -s -o "$body" -D "$hdr" -w '%{http_code}' -X POST "$HUB_URL/api/v1/admin/auth/mfa" -H 'Content-Type: application/json' -H "$CSRF" -d "$req")
    [ "$code" = "200" ] || { echo "2단계 인증 실패 (HTTP $code): $(json code < "$body") $(json message < "$body")" >&2; exit 1; }
    SID=$(cookie_of "$hdr") ;;
  *) echo "알 수 없는 로그인 상태: $status" >&2; exit 1 ;;
esac
[ -n "$SID" ] || { echo "세션 쿠키를 받지 못했다" >&2; exit 1; }

if [ "$(json admin mustChangePassword < "$body")" = "True" ]; then
  [ -n "${IDEM_ADMIN_NEW_PASSWORD:-}" ] || { echo "첫 로그인 비밀번호 변경이 필요하다(E-IDO-137) — IDEM_ADMIN_NEW_PASSWORD 를 설정하세요" >&2; exit 1; }
  req=$(python3 -c 'import json,sys; print(json.dumps({"currentPassword": sys.argv[1], "newPassword": sys.argv[2]}))' "$PASSWORD" "$IDEM_ADMIN_NEW_PASSWORD")
  code=$(curl -s -o "$body" -w '%{http_code}' -X POST "$HUB_URL/api/v1/admin/auth/password" -H 'Content-Type: application/json' -H "$CSRF" \
         -H "Cookie: idemAdminSid=$SID" -d "$req")
  [ "$code" = "204" ] || { echo "비밀번호 변경 실패 (HTTP $code): $(json code < "$body") $(json message < "$body") $(json detail < "$body")" >&2; exit 1; }
  echo "첫 로그인 비밀번호를 바꿨다 (username=$USERNAME)" >&2
fi
printf '%s\n' "$SID"
