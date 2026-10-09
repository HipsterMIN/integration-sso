#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# PostgreSQL 복구 (플랜 §2.2 PR-G1-2, 시험 항목 G-5) — backup.sh 가 뜬 덤프를 DB 로 되살린다.
#
#   scripts/ops/restore.sh backups/idem-idem-20261008T050000Z.dump          # TARGET=compose, 운영 DB idem 을 새로 만들어 복구. 앱은 먼저 내린다:
#                                                                            #   docker compose -f infra/docker/compose.install.yml --env-file install.env stop idem-hub idem-gate idem-registry idem-authz idem-console-admin keycloak
#   TARGET_DB=idem_restore_check VERIFY_SOURCE_DB=idem scripts/ops/restore.sh <dump>
#                                                                            # 운영 DB 는 그대로 두고 새 DB 에 복구해 표·행 수를 대조(백업 유효성 검증 — CI 가 매 PR 이렇게 돈다)
#
# 절차: ① 체크섬·아카이브 목록 ② 대상 DB 에 붙은 접속 확인(있으면 중단 — FORCE=1 이면 끊는다) ③ DROP DATABASE → CREATE DATABASE(OWNER PGUSER)
#       ④ pg_restore(--no-owner) ⑤ ANALYZE ⑥ 요약(스키마별 표 수·주요 표 행 수) [⑦ VERIFY_SOURCE_DB 와 대조]
# 복구 뒤(운영 DB): docker compose … up -d → 설치 매뉴얼 §4 ①·②·②′(scripts/ci/install-smoke.sh 앞부분) + 기존 관리자 로그인 + 기존 기관 RP 로그인 = §6 판정
# 환경: TARGET=compose|docker|k8s|direct  CONTAINER=idem-postgres  NS=idem PG_WORKLOAD=deploy/postgres  PGUSER=idem  MAINT_DB=postgres(관리 접속용 DB)
#       TARGET_DB=idem  VERIFY_SOURCE_DB=(없음)  FORCE=0  YES=0 — 운영 DB(idem)를 지우기 전 확인. 터미널이 아니면 YES=1 이 필요하다
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
need() { for t in "$@"; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done; }

DUMP="${1:-}"
[ -n "$DUMP" ] && [ -f "$DUMP" ] || { echo "사용법: $0 <덤프 파일(.dump)>" >&2; exit 2; }
TARGET="${TARGET:-compose}"
PGUSER="${PGUSER:-idem}"
CONTAINER="${CONTAINER:-idem-postgres}"
NS="${NS:-idem}"
PG_WORKLOAD="${PG_WORKLOAD:-deploy/postgres}"
MAINT_DB="${MAINT_DB:-postgres}"
TARGET_DB="${TARGET_DB:-idem}"
VERIFY_SOURCE_DB="${VERIFY_SOURCE_DB:-}"
FORCE="${FORCE:-0}"
YES="${YES:-0}"

run_pg() {
  case "$TARGET" in
    compose|docker) docker exec -i "$CONTAINER" "$@" ;;
    k8s)            kubectl -n "$NS" exec -i "$PG_WORKLOAD" -- "$@" ;;
    direct)         "$@" ;;
    *) fail "TARGET 은 compose | docker | k8s | direct: $TARGET" ;;
  esac
}
sql() { run_pg psql -X -q -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$1" -Atc "$2"; }   # sql <db> <query>
case "$TARGET" in compose|docker) need docker ;; k8s) need kubectl ;; direct) need pg_restore psql ;; esac
case "$TARGET_DB" in *[!A-Za-z0-9_]*) fail "TARGET_DB 이름은 영문·숫자·_ 만: $TARGET_DB" ;; esac

echo "① 덤프 확인: $DUMP"
if [ -f "${DUMP%.dump}.sha256" ]; then
  ( cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "${DUMP%.dump}.sha256")" >/dev/null ) && ok "체크섬 일치" || fail "체크섬 불일치: ${DUMP%.dump}.sha256"
else
  echo "  (sha256 파일 없음 — 체크섬 검증 생략)"
fi
entries=$(run_pg pg_restore -l < "$DUMP" | grep -c 'TABLE DATA' || true)
[ "$entries" -gt 0 ] || fail "덤프 목록에 TABLE DATA 항목이 없습니다 — pg_dump -Fc 형식의 파일인지 확인"
ok "아카이브 목록: 표 데이터 $entries 항목"
server=$(sql "$MAINT_DB" "SHOW server_version") || fail "DB 에 접속하지 못했습니다 (TARGET=$TARGET, PGUSER=$PGUSER, MAINT_DB=$MAINT_DB)"
ok "PostgreSQL $server"

echo "② 대상 DB $TARGET_DB 의 접속"
exists=$(sql "$MAINT_DB" "SELECT count(*) FROM pg_database WHERE datname='$TARGET_DB'")
if [ "$exists" = "1" ]; then
  conns=$(sql "$MAINT_DB" "SELECT count(*) FROM pg_stat_activity WHERE datname='$TARGET_DB' AND pid <> pg_backend_pid()")
  if [ "$conns" != "0" ]; then
    if [ "$FORCE" = "1" ]; then
      sql "$MAINT_DB" "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname='$TARGET_DB' AND pid <> pg_backend_pid()" >/dev/null
      ok "접속 $conns 개를 끊었다 (FORCE=1)"
    else
      fail "DB $TARGET_DB 에 접속 $conns 개가 살아 있다 — 앱·Keycloak 을 먼저 내리거나(docker compose stop …) FORCE=1"
    fi
  else
    ok "살아 있는 접속 없음"
  fi
  if [ "$YES" != "1" ]; then
    [ -t 0 ] || fail "DB $TARGET_DB 를 지우고 다시 만든다 — 비대화형이면 YES=1 로 확인하세요"
    read -r -p "  DB '$TARGET_DB' 를 지우고 덤프로 되살립니다. 계속하려면 DB 이름을 입력: " answer
    [ "$answer" = "$TARGET_DB" ] || fail "중단"
  fi
else
  ok "DB 없음 — 새로 만든다"
fi

echo "③ DROP → CREATE DATABASE $TARGET_DB"
force_clause=""; [ "$FORCE" = "1" ] && force_clause=" WITH (FORCE)"
sql "$MAINT_DB" "DROP DATABASE IF EXISTS \"$TARGET_DB\"$force_clause"
sql "$MAINT_DB" "CREATE DATABASE \"$TARGET_DB\" OWNER \"$PGUSER\" ENCODING 'UTF8' TEMPLATE template0"
ok "만듦"

echo "④ pg_restore"
run_pg pg_restore -U "$PGUSER" -d "$TARGET_DB" --no-owner --no-privileges --exit-on-error < "$DUMP"
ok "복구 완료"

echo "⑤ ANALYZE (사용자 스키마의 표만 — 카탈로그는 슈퍼유저 몫)"
sql "$TARGET_DB" "SELECT 'ANALYZE ' || quote_ident(schemaname) || '.' || quote_ident(tablename) || ';' FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')" \
  | run_pg psql -X -q -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$TARGET_DB" -f - >/dev/null
ok "완료"

echo "⑥ 요약"
sql "$TARGET_DB" "SELECT schemaname || ': ' || count(*) || ' 표' FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') GROUP BY schemaname ORDER BY 1" | sed 's/^/  /'
KEY_TABLES="idem_hub.agency_meta idem_hub.admin_user idem_hub.audit_log idem_registry.qim_user idem_authz.authz_assignment keycloak.realm keycloak.client"
count_of() {   # count_of <db> <schema.table> — 없는 표는 '-' (CASE 안의 서브쿼리는 없는 표라도 파싱에서 실패하므로 두 단계로)
  if [ "$(sql "$1" "SELECT to_regclass('$2') IS NOT NULL")" = "t" ]; then sql "$1" "SELECT count(*) FROM $2"; else echo '-'; fi
}
for t in $KEY_TABLES; do echo "  $t: $(count_of "$TARGET_DB" "$t")"; done

if [ -n "$VERIFY_SOURCE_DB" ]; then
  echo "⑦ $VERIFY_SOURCE_DB 와 대조"
  src_tables=$(sql "$VERIFY_SOURCE_DB" "SELECT string_agg(schemaname || '=' || n, ',' ORDER BY schemaname) FROM (SELECT schemaname, count(*) n FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') GROUP BY schemaname) s")
  dst_tables=$(sql "$TARGET_DB" "SELECT string_agg(schemaname || '=' || n, ',' ORDER BY schemaname) FROM (SELECT schemaname, count(*) n FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema') GROUP BY schemaname) s")
  [ "$src_tables" = "$dst_tables" ] && ok "스키마별 표 수 일치: $dst_tables" || fail "스키마별 표 수 불일치 — 원본 $src_tables / 복구 $dst_tables"
  bad=0
  for t in $KEY_TABLES; do
    s=$(count_of "$VERIFY_SOURCE_DB" "$t"); d=$(count_of "$TARGET_DB" "$t")
    if [ "$t" = "idem_hub.audit_log" ] && [ "$s" != "-" ] && [ "$d" != "-" ]; then
      [ "$d" -le "$s" ] && ok "$t: 복구 $d ≤ 원본 $s (백업 뒤 감사가 더 쌓일 수 있다)" || { echo "  ❌ $t: 복구 $d > 원본 $s"; bad=1; }
    else
      [ "$s" = "$d" ] && ok "$t: $d" || { echo "  ❌ $t: 원본 $s / 복구 $d"; bad=1; }
    fi
  done
  [ "$bad" = "0" ] || fail "대조 실패"
fi
echo
if [ -z "$VERIFY_SOURCE_DB" ]; then
  echo "다음: 앱 기동 → 설치 매뉴얼 §4 ①·②·②′ + 기존 관리자 로그인 + 기존 기관 RP 로그인 (§6 복구 판정)"
fi
