#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 개명 5단계(S9 PR-2, 1.0) 업그레이드 — S9 이전 설치본의 PostgreSQL 이름을 새 이름으로 옮긴다. docs/naming.md §3
#
#   DB       onepass → idem            (앱 밖에서만 가능 — 연결이 없어야 한다)
#   역할     onepass → idem            (선택, 기본 수행. --keep-role 로 건너뜀)
#   스키마   ido/qsign/qim/authz → idem_hub/idem_gate/idem_registry/idem_authz
#            (앱이 첫 기동에서 자동으로 옮기지만, 여기서 미리 옮겨도 된다 — 둘 다 멱등)
#   Keycloak realm onepass → idem 은 realm import 로만 — keycloak-data 볼륨을 지우고 다시 import 한다(§ install.md §7)
#
#   사용: 모든 Idem 컨테이너를 내린 뒤(postgres 만 켠 채)
#     PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=... scripts/upgrade/rename-db-1.0.sh [--keep-role] [--dry-run]
#   compose 설치본이면:  docker compose ... stop idem-hub idem-gate idem-registry idem-authz idem-console-admin keycloak
#                        docker compose ... exec -e PGUSER=onepass -e PGPASSWORD=$IDEM_DB_PASSWORD -e IDEM_DB_PASSWORD=$IDEM_DB_PASSWORD postgres bash -s < scripts/upgrade/rename-db-1.0.sh
#
#   1.0.1 (3차 점검 H8): 실행 세션의 사용자가 바로 onepass 면 PostgreSQL 이 "session user cannot be renamed" 로 거부한다 — 스크립트가 임시 슈퍼유저를
#   만들어 그 접속으로 역할을 옮기고 지운다(실행 사용자가 슈퍼유저여야 한다 — compose 의 POSTGRES_USER 는 슈퍼유저). MD5 비밀번호는 역할 rename 때
#   지워지므로(SCRAM 은 유지) IDEM_DB_PASSWORD 가 있으면 rename 뒤 비밀번호를 다시 설정한다 — 없으면 경고.
#   또 구·신 스키마가 둘 다 있고 새 쪽에 Flyway 이력이 없으면(새 스키마가 먼저 생긴 상태, H7) 멈춘다.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
KEEP_ROLE=false; DRY=false
for a in "$@"; do case "$a" in --keep-role) KEEP_ROLE=true ;; --dry-run) DRY=true ;; *) echo "알 수 없는 옵션: $a" >&2; exit 1 ;; esac; done
command -v psql >/dev/null 2>&1 || { echo "psql 이 필요합니다" >&2; exit 1; }
run() { if $DRY; then echo "  (dry) $*"; else psql -v ON_ERROR_STOP=1 -qtAc "$*" postgres; fi; }
q() { psql -tAc "$1" "${2:-postgres}"; }
has_db() { [ "$(q "SELECT 1 FROM pg_database WHERE datname='$1'")" = "1" ]; }
has_role() { [ "$(q "SELECT 1 FROM pg_roles WHERE rolname='$1'")" = "1" ]; }
has_schema() { [ "$(q "SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname='$1'" "$2")" = "1" ]; }   # pg_namespace: information_schema 는 권한 있는 스키마만 보인다
has_table() { [ "$(q "SELECT 1 FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='$1' AND t.relname='$2'" "$3")" = "1" ]; }

echo "① DB onepass → idem"
if has_db idem; then echo "  이미 idem 이 있다 — 건너뜀"
elif has_db onepass; then
  n=$(q "SELECT count(*) FROM pg_stat_activity WHERE datname='onepass'")
  [ "$n" = "0" ] || { echo "  ❌ onepass 에 연결 $n개가 남아 있다 — 앱·Keycloak 을 먼저 내리세요" >&2; exit 1; }
  run "ALTER DATABASE onepass RENAME TO idem"; $DRY || echo "  ✅ 옮김"
else echo "  onepass 도 idem 도 없다 — 새 설치본이면 할 일 없음"; fi

if ! $KEEP_ROLE; then
  echo "② 역할 onepass → idem"
  if has_role idem; then echo "  이미 idem 이 있다 — 건너뜀"
  elif has_role onepass; then
    me=$(q "SELECT current_user")
    if $DRY; then echo "  (dry) ALTER ROLE onepass RENAME TO idem  (실행 사용자=$me$( [ "$me" = onepass ] && echo ', 임시 슈퍼유저 경유'))"
    else
      if [ "$me" = onepass ]; then
        # 세션 사용자는 자기 자신을 rename 할 수 없다 — 임시 슈퍼유저로 접속해 옮긴다
        [ "$(q "SELECT rolsuper FROM pg_roles WHERE rolname=current_user")" = "t" ] || { echo "  ❌ onepass 가 슈퍼유저가 아니라 역할을 옮길 수 없다 — PGUSER=postgres 로 실행하거나 --keep-role" >&2; exit 1; }
        tmp="idem_upgrade_$(date +%s)"; tmppw=$(head -c 24 /dev/urandom | base64 | tr -d '/+=')
        psql -v ON_ERROR_STOP=1 -qc "CREATE ROLE \"$tmp\" LOGIN SUPERUSER PASSWORD '$tmppw'" postgres
        trap 'PGUSER=idem PGPASSWORD="${IDEM_DB_PASSWORD:-$PGPASSWORD}" psql -qc "DROP ROLE IF EXISTS \"'"$tmp"'\"" postgres 2>/dev/null || PGUSER=onepass psql -qc "DROP ROLE IF EXISTS \"'"$tmp"'\"" postgres 2>/dev/null || true' EXIT
        PGUSER="$tmp" PGPASSWORD="$tmppw" psql -v ON_ERROR_STOP=1 -qc "ALTER ROLE onepass RENAME TO idem" postgres
        if [ -n "${IDEM_DB_PASSWORD:-}" ]; then PGUSER="$tmp" PGPASSWORD="$tmppw" psql -v ON_ERROR_STOP=1 -qc "ALTER ROLE idem PASSWORD '$IDEM_DB_PASSWORD'" postgres; fi
        # 임시 역할은 자기 세션에서 지울 수 없다 — 옮겨진 idem 으로 접속해 지운다 (비밀번호: IDEM_DB_PASSWORD, 없으면 종전 PGPASSWORD — SCRAM 이면 그대로다)
        export PGUSER=idem; [ -n "${IDEM_DB_PASSWORD:-}" ] && export PGPASSWORD="$IDEM_DB_PASSWORD"
        psql -qc "DROP ROLE \"$tmp\"" postgres || echo "  ⚠️  임시 역할 $tmp 을 지우지 못했다 — 비밀번호를 설정한 뒤 DROP ROLE \"$tmp\" 하세요"
        trap - EXIT
      else
        run "ALTER ROLE onepass RENAME TO idem"
        if [ -n "${IDEM_DB_PASSWORD:-}" ]; then run "ALTER ROLE idem PASSWORD '$IDEM_DB_PASSWORD'"; fi
      fi
      if [ -n "${IDEM_DB_PASSWORD:-}" ]; then echo "  ✅ 옮김 (비밀번호를 IDEM_DB_PASSWORD 로 다시 설정 — install.env 의 IDEM_DB_PASSWORD 유지, DB_USERNAME 기본값은 idem)"
      else
        pw=$(q "SELECT left(coalesce(rolpassword,''),3) FROM pg_authid WHERE rolname='idem'" 2>/dev/null || echo "?")
        if [ "$pw" = "md5" ] || [ "$pw" = "" ]; then echo "  ⚠️  옮겼지만 비밀번호가 MD5 였거나 없어 지워졌을 수 있다 — ALTER ROLE idem PASSWORD '…' 로 install.env 의 IDEM_DB_PASSWORD 를 다시 설정하세요 (IDEM_DB_PASSWORD 환경변수를 주면 자동)"
        else echo "  ✅ 옮김 (SCRAM 비밀번호는 그대로 — install.env 의 IDEM_DB_PASSWORD 유지, DB_USERNAME 기본값은 idem)"; fi
      fi
    fi
  else echo "  onepass 역할 없음 — 건너뜀"; fi
fi

echo "③ 스키마 (idem DB 안)"
SDB=""; if has_db idem; then SDB=idem; elif $DRY && has_db onepass; then SDB=onepass; fi   # dry-run 은 아직 안 옮긴 onepass 를 들여다본다
if [ -n "$SDB" ]; then
  for pair in ido:idem_hub qsign:idem_gate qim:idem_registry authz:idem_authz; do
    old=${pair%%:*}; new=${pair##*:}
    o=false; nw=false; has_schema "$old" "$SDB" && o=true; has_schema "$new" "$SDB" && nw=true
    if $o && $nw; then
      if has_table "$new" flyway_schema_history "$SDB"; then echo "  ⚠️  $old 와 $new 가 둘 다 있고 $new 에 Flyway 이력이 있다 — 이관이 끝났으면 DROP SCHEMA $old (확인 뒤)"
      else echo "  ❌ $old 와 $new 가 둘 다 있고 $new 에 Flyway 이력이 없다 — 새 스키마가 먼저 만들어진 상태(구 데이터 고아 위험). $new 가 비어 있으면 DROP SCHEMA $new 뒤 다시 실행하세요" >&2; exit 1; fi
    elif $nw; then echo "  $new 있음 — 건너뜀"
    elif $o; then if $DRY; then echo "  (dry) ALTER SCHEMA $old RENAME TO $new"; else psql -v ON_ERROR_STOP=1 -qc "ALTER SCHEMA $old RENAME TO $new" idem; echo "  ✅ $old → $new"; fi
    else echo "  $old 없음 — 건너뜀"; fi
  done
fi
echo "완료. 다음: install.env 의 DB_USERNAME(=idem)·변수명(IDEM_HUB_* …) 확인 → keycloak-data 볼륨 재생성(realm idem import) → 앱 기동."
echo "      앱은 첫 기동에서 Flyway 검증이 체크섬 불일치(개명 전 적용분)만 보고하면 1회 repair 한다. 스키마를 여기서 안 옮겼다면 [Idem 개명] 경고와 함께 앱이 옮긴다."
