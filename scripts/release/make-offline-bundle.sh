#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 오프라인(폐쇄망) 설치본 만들기 (플랜 §2.2 PR-G1-2, 시험 항목 G-6) — 인터넷이 되는 곳에서 한 번 돌려 반입할 꾸러미를 만든다.
#
#   scripts/release/make-offline-bundle.sh                                   # VERSION = HEAD 의 v* 태그(v1.1.2 → 1.1.2), 없으면 build.gradle.kts
#   VERSION=1.1.2 IDEM_EDITION=core IMAGES=build scripts/release/make-offline-bundle.sh
#
# 산출물 (OUT_DIR, 기본 dist/idem-<VERSION>-<EDITION>/):
#   idem-<VERSION>-<EDITION>-images.tar   Idem 이미지 — compose.install.yml 의 이름·태그 그대로(idem-gate:<V> · idem-hub:<V>-<ED> · idem-registry:<V>-<ED>
#                                         · idem-authz:<V> · idem-console-admin:<V> [· idem-kr-portal:<V>]) + 서드파티(postgres·redis·keycloak — compose 와 같은 태그)
#   idem-<VERSION>-src.tar.gz             소스(git archive — infra/·scripts/·docs/ 포함, 비밀 없음)            SOURCE=0 이면 생략
#   idem-<차트 버전>.tgz                   Helm 차트(helm 이 있을 때)                                            HELM=0 이면 생략
#   MANIFEST.txt · SHA256SUMS             버전·git sha·생성 시각·이미지 ID/크기·파일 크기 / 체크섬
#
# 환경 (전부 선택):
#   VERSION          이미지 태그 = install.env 의 IDEM_VERSION. 기본: HEAD 의 v* 태그, 없으면 루트 build.gradle.kts 의 version
#   IDEM_EDITION     core(기본) | kr — kr 은 벤더 SDK(~/.idem/vendor-libs)가 있는 곳에서만 빌드된다
#   IMAGES           build(기본): compose.install.yml 로 빌드 | local: 이미 있는 로컬 이미지(이름·태그 일치) | pull: IMAGE_REGISTRY 에서 받아 로컬 이름으로 retag
#   IMAGE_REGISTRY   pull 일 때 (예: ghcr.io/hipstermin)
#   ENV_FILE         build 일 때 compose 가 읽을 env 파일(기본 infra/docker/install.env; 없으면 scripts/lib/gen-install-env.sh 로 1회용 값을 만든다 — 빌드에만 쓰인다)
#   INCLUDE_THIRD_PARTY=1   postgres·redis·keycloak 포함(없으면 pull)      INCLUDE_AI=0   ollama(--profile ai) 포함
#   SOURCE=1  HELM=1  OUT_DIR=dist/idem-<V>-<ED>  DRY_RUN=0 (1 이면 docker 없이 계획만 출력한다)
#
# 반입 뒤: scripts/release/load-offline-bundle.sh <OUT_DIR>   (체크섬 검증 → docker load → install.env 의 IDEM_VERSION 안내)
# 필요: docker(compose 플러그인) · git · tar · sha256sum. 디스크: 이미지 tar 약 1.4 GB(코어 — CI 측정: Idem 5종 1.2 GB + postgres·redis·keycloak 0.75 GB, 저장 시 압축 없음).
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="$ROOT/infra/docker/compose.install.yml"

say()  { echo; echo "━━ $*"; }
ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
need() { for t in "$@"; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done; }

EDITION="${IDEM_EDITION:-core}"
case "$EDITION" in core|kr) ;; *) fail "IDEM_EDITION 은 core 또는 kr: $EDITION" ;; esac
IMAGES="${IMAGES:-build}"
case "$IMAGES" in build|local|pull) ;; *) fail "IMAGES 는 build | local | pull: $IMAGES" ;; esac
INCLUDE_THIRD_PARTY="${INCLUDE_THIRD_PARTY:-1}"
INCLUDE_AI="${INCLUDE_AI:-0}"
SOURCE="${SOURCE:-1}"
HELM="${HELM:-1}"
DRY_RUN="${DRY_RUN:-0}"

# ── 버전: HEAD 의 v* 태그 → 없으면 build.gradle.kts ─────────────────────────
if [ -z "${VERSION:-}" ]; then
  tag=$(git -C "$ROOT" describe --tags --exact-match --match 'v*' 2>/dev/null || true)
  if [ -n "$tag" ]; then VERSION="${tag#v}"
  else VERSION=$(grep -m1 -E '^\s*version\s*=\s*"' "$ROOT/build.gradle.kts" | sed -E 's/.*"([^"]+)".*/\1/'); fi
fi
[ -n "$VERSION" ] || fail "VERSION 을 정하지 못했습니다 — VERSION=1.1.2 처럼 지정하세요"
GIT_SHA=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")
SRC_REF="HEAD"
git -C "$ROOT" rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null 2>&1 && SRC_REF="v$VERSION"

OUT_DIR="${OUT_DIR:-$ROOT/dist/idem-$VERSION-$EDITION}"
IMAGES_TAR="idem-$VERSION-$EDITION-images.tar"
SRC_TGZ="idem-$VERSION-src.tar.gz"

# ── 이미지 목록: compose.install.yml 과 같은 이름·태그 ─────────────────────────
IDEM_IMAGES=( "idem-gate:$VERSION" "idem-hub:$VERSION-$EDITION" "idem-registry:$VERSION-$EDITION" "idem-authz:$VERSION" "idem-console-admin:$VERSION" )
[ "$EDITION" = "kr" ] && IDEM_IMAGES+=( "idem-kr-portal:$VERSION" )
compose_image() { grep -m1 -E "^\s+image:\s*$1" "$COMPOSE" | awk '{print $2}'; }
THIRD_PARTY=()
if [ "$INCLUDE_THIRD_PARTY" = "1" ]; then
  for pat in 'postgres:' 'redis:' 'quay.io/keycloak/keycloak:'; do
    img=$(compose_image "$pat"); [ -n "$img" ] || fail "compose.install.yml 에서 $pat 이미지를 찾지 못했습니다"
    THIRD_PARTY+=( "$img" )
  done
fi
if [ "$INCLUDE_AI" = "1" ]; then
  img=$(compose_image 'ollama/ollama:'); [ -n "$img" ] || fail "compose.install.yml 에서 ollama 이미지를 찾지 못했습니다"
  THIRD_PARTY+=( "$img" )
fi

say "오프라인 설치본 계획"
echo "  버전 $VERSION · 에디션 $EDITION · git $GIT_SHA (소스 ref $SRC_REF) · 이미지 출처 $IMAGES"
echo "  산출 디렉터리 $OUT_DIR"
echo "  Idem 이미지: ${IDEM_IMAGES[*]}"
[ ${#THIRD_PARTY[@]} -gt 0 ] && echo "  서드파티: ${THIRD_PARTY[*]}"
echo "  소스 tar: $([ "$SOURCE" = "1" ] && echo "$SRC_TGZ ($SRC_REF)" || echo 생략) · Helm 차트: $([ "$HELM" = "1" ] && echo 포함 || echo 생략)"
if [ "$DRY_RUN" = "1" ]; then ok "DRY_RUN — 여기까지"; exit 0; fi

need docker git tar sha256sum
mkdir -p "$OUT_DIR"

# ── 이미지 준비 ─────────────────────────────────────────────────────────────
say "Idem 이미지 ($IMAGES)"
case "$IMAGES" in
  build)
    ENV_FILE="${ENV_FILE:-$ROOT/infra/docker/install.env}"
    if [ ! -f "$ENV_FILE" ]; then
      tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
      "$ROOT/scripts/lib/gen-install-env.sh" > "$tmp/install.env"   # 빌드에서는 compose 의 ${X:?} 검사를 지나기 위한 1회용 값 — 이미지에 들어가지 않는다
      ENV_FILE="$tmp/install.env"
      echo "  install.env 없음 — 1회용 값으로 빌드 (이미지에는 들어가지 않는다)"
    fi
    profile=(); [ "$EDITION" = "kr" ] && profile=(--profile kr)
    IDEM_VERSION="$VERSION" IDEM_EDITION="$EDITION" docker compose --env-file "$ENV_FILE" -f "$COMPOSE" "${profile[@]}" build
    ;;
  pull)
    [ -n "${IMAGE_REGISTRY:-}" ] || fail "IMAGES=pull 에는 IMAGE_REGISTRY 가 필요합니다"
    for img in "${IDEM_IMAGES[@]}"; do docker pull "$IMAGE_REGISTRY/$img"; docker tag "$IMAGE_REGISTRY/$img" "$img"; done
    ;;
  local) ;;
esac
for img in "${IDEM_IMAGES[@]}"; do docker image inspect "$img" >/dev/null 2>&1 || fail "이미지가 없습니다: $img (IMAGES=$IMAGES)"; ok "$img"; done
if [ ${#THIRD_PARTY[@]} -gt 0 ]; then
  say "서드파티 이미지"
  for img in "${THIRD_PARTY[@]}"; do docker image inspect "$img" >/dev/null 2>&1 || docker pull "$img"; ok "$img"; done
fi

# ── docker save ────────────────────────────────────────────────────────────
say "docker save → $IMAGES_TAR"
docker save -o "$OUT_DIR/$IMAGES_TAR" "${IDEM_IMAGES[@]}" "${THIRD_PARTY[@]}"
ok "$(du -h "$OUT_DIR/$IMAGES_TAR" | cut -f1)"

# ── 소스 · Helm ─────────────────────────────────────────────────────────────
FILES=( "$IMAGES_TAR" )
if [ "$SOURCE" = "1" ]; then
  say "소스 tar ($SRC_REF)"
  git -C "$ROOT" archive --format=tar.gz --prefix="idem-$VERSION/" -o "$OUT_DIR/$SRC_TGZ" "$SRC_REF"
  FILES+=( "$SRC_TGZ" ); ok "$SRC_TGZ $(du -h "$OUT_DIR/$SRC_TGZ" | cut -f1) — 추적되지 않은 파일(install.env·vendor-libs)은 들어가지 않는다"
fi
if [ "$HELM" = "1" ]; then
  if command -v helm >/dev/null 2>&1; then
    say "Helm 차트"
    chart=$(helm package "$ROOT/infra/helm/idem" -d "$OUT_DIR" | sed -E 's/.*: //')
    FILES+=( "$(basename "$chart")" ); ok "$(basename "$chart")"
  else
    echo "  helm 이 없어 차트 패키지는 생략한다 (K8s 설치는 소스 tar 의 infra/helm/idem 으로 helm package)"
  fi
fi

# ── MANIFEST · SHA256SUMS ──────────────────────────────────────────────────
say "MANIFEST.txt · SHA256SUMS"
{
  echo "Idem offline bundle"
  echo "version=$VERSION"
  echo "edition=$EDITION"
  echo "git=$GIT_SHA ($SRC_REF)"
  echo "created=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "docker=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?')"
  echo "install.env: IDEM_VERSION=$VERSION IDEM_EDITION=$EDITION"
  echo
  echo "[images]"
  for img in "${IDEM_IMAGES[@]}" "${THIRD_PARTY[@]}"; do
    printf '%s\t%s\t%s\n' "$img" "$(docker image inspect --format '{{.Id}}' "$img" | cut -c8-19)" "$(docker image inspect --format '{{.Size}}' "$img" | awk '{printf "%.0fMB", $1/1048576}')"
  done
  echo
  echo "[files]"
  for f in "${FILES[@]}"; do printf '%s\t%s\n' "$f" "$(du -h "$OUT_DIR/$f" | cut -f1)"; done
} > "$OUT_DIR/MANIFEST.txt"
( cd "$OUT_DIR" && sha256sum "${FILES[@]}" MANIFEST.txt > SHA256SUMS )
cat "$OUT_DIR/MANIFEST.txt"
ok "SHA256SUMS ($(wc -l < "$OUT_DIR/SHA256SUMS") 항목)"
echo
echo "다음: 디렉터리 $OUT_DIR 를 반입하고 폐쇄망에서  scripts/release/load-offline-bundle.sh <디렉터리>  (설치 매뉴얼 §3.3)"
