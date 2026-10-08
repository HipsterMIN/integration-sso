#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 오프라인 설치본 반입 (폐쇄망) — make-offline-bundle.sh 가 만든 디렉터리를 검증하고 이미지를 적재한다 (시험 항목 G-6).
#
#   scripts/release/load-offline-bundle.sh <번들 디렉터리>        # SHA256SUMS 검증 → docker load → MANIFEST 의 이미지가 전부 있는지 확인
#   EXTRACT_SOURCE=1 DEST=/opt/idem scripts/release/load-offline-bundle.sh <번들 디렉터리>   # 소스 tar 도 DEST 에 푼다
#
# 적재 뒤: 소스(또는 반입한 infra/·scripts/·docs/)의 install.env 에 MANIFEST 가 알려 주는 IDEM_VERSION·IDEM_EDITION 을 넣고
#          docker compose -f infra/docker/compose.install.yml --env-file install.env up -d   (--build 없이; 설치 매뉴얼 §3.1)
# K8s: 이미지를 사설 레지스트리에 push 하고 Helm global.imageRegistry·global.imageTag 를 준다(§3.2).
# 필요: docker · sha256sum · tar
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
need() { for t in "$@"; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done; }

DIR="${1:-}"
[ -n "$DIR" ] && [ -d "$DIR" ] || { echo "사용법: $0 <번들 디렉터리>" >&2; exit 2; }
DIR="$(cd "$DIR" && pwd)"
need docker sha256sum tar
[ -f "$DIR/SHA256SUMS" ] && [ -f "$DIR/MANIFEST.txt" ] || fail "SHA256SUMS·MANIFEST.txt 가 없습니다: $DIR"

echo "① 체크섬"
( cd "$DIR" && sha256sum -c SHA256SUMS ) || fail "체크섬 불일치 — 반입 중 손상됐거나 다른 번들의 파일이 섞였다"

echo "② docker load"
tar_file=$(ls "$DIR"/idem-*-images.tar 2>/dev/null | head -1)
[ -n "$tar_file" ] || fail "이미지 tar(idem-*-images.tar)가 없습니다"
docker load -i "$tar_file"

echo "③ MANIFEST 의 이미지 확인"
missing=0
while IFS=$'\t' read -r img _id _size; do
  [ -n "$img" ] || continue
  if docker image inspect "$img" >/dev/null 2>&1; then ok "$img"; else echo "  ❌ 없음: $img"; missing=1; fi
done < <(sed -n '/^\[images\]/,/^$/p' "$DIR/MANIFEST.txt" | grep -v '^\[' )
[ "$missing" = "0" ] || fail "MANIFEST 의 이미지가 전부 적재되지 않았다"

if [ "${EXTRACT_SOURCE:-0}" = "1" ]; then
  echo "④ 소스 풀기"
  src=$(ls "$DIR"/idem-*-src.tar.gz 2>/dev/null | head -1)
  [ -n "$src" ] || fail "소스 tar(idem-*-src.tar.gz)가 없습니다"
  DEST="${DEST:-.}"; mkdir -p "$DEST"
  tar -xzf "$src" -C "$DEST"
  ok "$DEST/$(tar -tzf "$src" | sed -n '1p')"   # head 는 파이프를 일찍 닫아 tar 가 write error 를 낸다
fi

echo
grep -E '^(version|edition|git|created)=' "$DIR/MANIFEST.txt" | sed 's/^/  /'
echo "  다음: install.env 에  $(grep -E '^install.env:' "$DIR/MANIFEST.txt" | sed 's/^install.env: //')  를 넣고  docker compose -f infra/docker/compose.install.yml --env-file install.env up -d  (--build 없이)"
