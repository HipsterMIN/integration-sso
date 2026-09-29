#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 설치 비밀 한 벌을 1회용 값으로 만든다 — infra/docker/install.env.example 의 "값을 채워야 하는" 키 전부.
# CI 설치본 스모크(.github/workflows/ci.yml smoke-test)와 K8s 실배포 리허설(scripts/k8s/rehearsal.sh)이 같은 생성기를 쓴다.
# 운영 값은 docs/install-inputs.md 의 규칙대로 따로 만들고 비밀 저장소에 둔다 — 이 스크립트 출력은 시험용이다.
#
#   scripts/lib/gen-install-env.sh > install.env          # stdout 으로 낸다 (파일 권한은 호출자가)
#   환경: IDEM_DB_PASSWORD(기본 idem) · IDEM_EDITION(core) · IDEM_PLUGINS_MOCK_AUTH_ENABLED(true)
#
# 예시 파일에 키가 늘면 여기도 늘어야 한다 — 빠진 키가 있으면 실패한다 (1.0.1 M13).
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXAMPLE="$ROOT/infra/docker/install.env.example"
[ -f "$EXAMPLE" ] || { echo "install.env.example 이 없습니다: $EXAMPLE" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "필요한 명령이 없습니다: openssl" >&2; exit 1; }

tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
openssl genpkey -algorithm ed25519 -out "$tmp/cast.pem" 2>/dev/null

out=$(cat <<ENV
IDEM_DB_PASSWORD=${IDEM_DB_PASSWORD:-idem}
KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -hex 16)
IDEM_HUB_INTERNAL_SIG_SECRET=$(openssl rand -base64 32)
IDEM_HUB_INTERNAL_API_KEY_GATE=$(openssl rand -hex 32)
IDEM_HUB_INTERNAL_API_KEY_RELAY=$(openssl rand -hex 32)
IDEM_REGISTRY_INTERNAL_API_KEY=$(openssl rand -hex 32)
IDEM_AUTHZ_INTERNAL_API_KEY=$(openssl rand -hex 32)
IDEM_GATE_KEYCLOAK_CLIENT_SECRET=$(openssl rand -hex 32)
KEYCLOAK_CLIENT_SECRET=$(openssl rand -hex 32)
KEYCLOAK_PROVISIONER_CLIENT_SECRET=$(openssl rand -hex 32)
KEYCLOAK_SESSION_MANAGER_CLIENT_SECRET=$(openssl rand -hex 32)
IDEM_HUB_HANDOFF_AES_KEY=$(openssl rand -base64 32)
IDEM_HUB_HANDOFF_HMAC_KEY=$(openssl rand -base64 32)
IDEM_HUB_WEBHOOK_SIGNING_SECRET=$(openssl rand -hex 32)
IDEM_REGISTRY_AES_SHARED_KEY=$(openssl rand -base64 32)
IDEM_REGISTRY_DI_SECRET=$(openssl rand -hex 32)
IDEM_REGISTRY_CI_AES_KEY_V1=$(openssl rand -base64 32)
IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD=Ci-$(openssl rand -hex 12)-Aa
IDEM_HUB_ADMIN_SECRET_KEY=$(openssl rand -base64 32)
IDEM_HUB_KMS_MASTER_KEY=$(openssl rand -base64 32)
IDEM_HUB_CAST_PRIVATE_KEY=$(openssl pkey -in "$tmp/cast.pem" -outform DER | base64 -w0)
IDEM_HUB_CAST_PUBLIC_KEY=$(openssl pkey -in "$tmp/cast.pem" -pubout -outform DER | base64 -w0)
IDEM_PLUGINS_MOCK_AUTH_ENABLED=${IDEM_PLUGINS_MOCK_AUTH_ENABLED:-true}
IDEM_EDITION=${IDEM_EDITION:-core}
ENV
)

missing=0
for k in $(grep -E '^[A-Z0-9_]+=' "$EXAMPLE" | cut -d= -f1); do
  grep -q "^$k=" <<<"$out" || { echo "install.env.example 의 키가 생성기에 없습니다: $k" >&2; missing=1; }
done
[ "$missing" -eq 0 ] || exit 1
printf '%s\n' "$out"
