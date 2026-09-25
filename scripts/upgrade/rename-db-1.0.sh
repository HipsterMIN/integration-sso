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
#                        docker compose ... exec -e PGUSER=onepass -e PGPASSWORD=$IDEM_DB_PASSWORD postgres bash -s < scripts/upgrade/rename-db-1.0.sh
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
KEEP_ROLE=false; DRY=false
for a in "$@"; do case "$a" in --keep-role) KEEP_ROLE=true ;; --dry-run) DRY=true ;; *) echo "알 수 없는 옵션: $a" >&2; exit 1 ;; esac; done
command -v psql >/dev/null 2>&1 || { echo "psql 이 필요합니다" >&2; exit 1; }
run() { if $DRY; then echo "  (dry) $*"; else psql -v ON_ERROR_STOP=1 -qtAc "$*" postgres; fi; }
has_db() { [ "$(psql -tAc "SELECT 1 FROM pg_database WHERE datname='$1'" postgres)" = "1" ]; }
has_role() { [ "$(psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$1'" postgres)" = "1" ]; }

echo "① DB onepass → idem"
if has_db idem; then echo "  이미 idem 이 있다 — 건너뜀"
elif has_db onepass; then
  n=$(psql -tAc "SELECT count(*) FROM pg_stat_activity WHERE datname='onepass'" postgres)
  [ "$n" = "0" ] || { echo "  ❌ onepass 에 연결 $n개가 남아 있다 — 앱·Keycloak 을 먼저 내리세요" >&2; exit 1; }
  run "ALTER DATABASE onepass RENAME TO idem"; $DRY || echo "  ✅ 옮김"
else echo "  onepass 도 idem 도 없다 — 새 설치본이면 할 일 없음"; fi

if ! $KEEP_ROLE; then
  echo "② 역할 onepass → idem"
  if has_role idem; then echo "  이미 idem 이 있다 — 건너뜀"
  elif has_role onepass; then run "ALTER ROLE onepass RENAME TO idem"; $DRY || echo "  ✅ 옮김 (비밀번호는 그대로 — install.env 의 IDEM_DB_PASSWORD 유지, DB_USERNAME 기본값은 idem)"
  else echo "  onepass 역할 없음 — 건너뜀"; fi
fi

echo "③ 스키마 (idem DB 안)"
SDB=""; if has_db idem; then SDB=idem; elif $DRY && has_db onepass; then SDB=onepass; fi   # dry-run 은 아직 안 옮긴 onepass 를 들여다본다
if [ -n "$SDB" ]; then
  for pair in ido:idem_hub qsign:idem_gate qim:idem_registry authz:idem_authz; do
    old=${pair%%:*}; new=${pair##*:}
    o=$(psql -tAc "SELECT 1 FROM information_schema.schemata WHERE schema_name='$old'" "$SDB"); nw=$(psql -tAc "SELECT 1 FROM information_schema.schemata WHERE schema_name='$new'" "$SDB")
    if [ "$nw" = "1" ]; then echo "  $new 있음 — 건너뜀"
    elif [ "$o" = "1" ]; then if $DRY; then echo "  (dry) ALTER SCHEMA $old RENAME TO $new"; else psql -v ON_ERROR_STOP=1 -qc "ALTER SCHEMA $old RENAME TO $new" idem; echo "  ✅ $old → $new"; fi
    else echo "  $old 없음 — 건너뜀"; fi
  done
fi
echo "완료. 다음: install.env 의 DB_USERNAME(=idem)·변수명(IDEM_HUB_* …) 확인 → keycloak-data 볼륨 재생성(realm idem import) → 앱 기동."
echo "      앱은 첫 기동에서 Flyway 이력을 repair 한다(로그 'Repairing Schema History'). 스키마를 여기서 안 옮겼다면 [Idem 개명] 경고와 함께 앱이 옮긴다."
