#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# PostgreSQL 백업 (플랜 §2.2 PR-G1-2, 시험 항목 G-5) — DB idem 전체(스키마 idem_hub·idem_gate·idem_registry·idem_authz·keycloak·agency_stub)를
# pg_dump 사용자 정의 형식(-Fc)으로 뜬다. Flyway 이력이 덤프 안에 있어 복구 뒤 재적용은 없다.
#
#   scripts/ops/backup.sh                                  # TARGET=compose: 컨테이너 idem-postgres 안의 pg_dump (클라이언트 버전 불일치 없음)
#   TARGET=k8s NS=idem scripts/ops/backup.sh               # kubectl exec deploy/postgres (리허설 infra.yaml; 운영은 PG_WORKLOAD=<statefulset/…>)
#   TARGET=direct PGHOST=db PGUSER=idem PGPASSWORD=… scripts/ops/backup.sh   # 호스트의 pg_dump — 서버와 같은 메이저 버전이어야 한다
#
# 산출물 (OUT_DIR, 기본 backups/): idem-<DB>-<UTC 시각>.dump + .sha256 + .meta(서버·pg_dump 버전, 스키마, Flyway 최신 버전, 주요 표 행 수)
# 환경: DB=idem  CONTAINER=idem-postgres  NS=idem PG_WORKLOAD=deploy/postgres  PGUSER=idem  OUT_DIR=backups
#       KEEP=0 — N 이면 OUT_DIR 의 덤프를 최근 N 개만 남기고 지운다 (cron 용)
# 들어가지 않는 것: install.env / Secret(비밀 — 따로 비밀 저장소에), Redis(세션·캐시 — 유실 시 재로그인), 감사 WAL 볼륨(DB 복귀 뒤 재삽입용 임시), AI 모델 캐시.
# 복구: scripts/ops/restore.sh <dump>   (설치 매뉴얼 §6)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
need() { for t in "$@"; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done; }

TARGET="${TARGET:-compose}"
DB="${DB:-idem}"
PGUSER="${PGUSER:-idem}"
CONTAINER="${CONTAINER:-idem-postgres}"
NS="${NS:-idem}"
PG_WORKLOAD="${PG_WORKLOAD:-deploy/postgres}"
OUT_DIR="${OUT_DIR:-backups}"
KEEP="${KEEP:-0}"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)

# pg 도구를 대상 안에서 실행한다 (stdin/stdout 그대로)
run_pg() {
  case "$TARGET" in
    compose|docker) docker exec -i "$CONTAINER" "$@" ;;
    k8s)            kubectl -n "$NS" exec -i "$PG_WORKLOAD" -- "$@" ;;
    direct)         "$@" ;;
    *) fail "TARGET 은 compose | docker | k8s | direct: $TARGET" ;;
  esac
}
sql() { run_pg psql -X -q -v ON_ERROR_STOP=1 -U "$PGUSER" -d "$DB" -Atc "$1"; }

case "$TARGET" in compose|docker) need docker ;; k8s) need kubectl ;; direct) need pg_dump psql ;; esac
need sha256sum
mkdir -p "$OUT_DIR"
base="$OUT_DIR/idem-$DB-$STAMP"

echo "① 접속 확인 ($TARGET, DB $DB)"
server=$(sql "SHOW server_version" 2>/dev/null) || fail "DB 에 접속하지 못했습니다 (TARGET=$TARGET, DB=$DB, PGUSER=$PGUSER)"
ok "PostgreSQL $server"

echo "② pg_dump -Fc → $base.dump"
run_pg pg_dump -U "$PGUSER" -d "$DB" -Fc --no-owner --no-privileges > "$base.dump"
[ -s "$base.dump" ] || fail "덤프가 비었습니다"
entries=$(run_pg pg_restore -l < "$base.dump" | grep -c 'TABLE DATA' || true)
[ "$entries" -gt 0 ] || fail "덤프 목록에 TABLE DATA 항목이 없습니다 — 아카이브 손상 또는 빈 DB"
ok "$(du -h "$base.dump" | cut -f1), 표 데이터 $entries 항목"

echo "③ 체크섬·메타"
( cd "$OUT_DIR" && sha256sum "$(basename "$base.dump")" > "$(basename "$base.sha256")" )
{
  echo "db=$DB"
  echo "created=$STAMP"
  echo "target=$TARGET"
  echo "server_version=$server"
  echo "pg_dump_version=$(run_pg pg_dump --version | awk '{print $3}')"
  echo "schemas=$(sql "SELECT string_agg(nspname, ',' ORDER BY nspname) FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema'")"
  echo "tables=$(sql "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')")"
  for s in $(sql "SELECT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname='flyway_schema_history' ORDER BY 1"); do
    echo "flyway.$s=$(sql "SELECT version FROM \"$s\".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
  done
  for t in idem_hub.agency_meta idem_hub.admin_user idem_hub.audit_log idem_registry.qim_user idem_authz.authz_assignment keycloak.realm keycloak.client; do
    if [ "$(sql "SELECT to_regclass('$t') IS NOT NULL")" = "t" ]; then n=$(sql "SELECT count(*) FROM $t"); else n='-'; fi
    echo "rows.$t=$n"
  done
} > "$base.meta"
cat "$base.meta" | sed 's/^/  /'
ok "$(basename "$base.sha256") · $(basename "$base.meta")"

if [ "$KEEP" -gt 0 ] 2>/dev/null; then
  echo "④ 보존 (최근 $KEEP 개)"
  ls -1t "$OUT_DIR"/idem-"$DB"-*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do
    rm -f "$old" "${old%.dump}.sha256" "${old%.dump}.meta"; echo "  삭제: $(basename "$old")"
  done
fi
echo
echo "백업: $base.dump  (install.env / Secret 은 따로 비밀 저장소에 — 덤프에 없다)"
