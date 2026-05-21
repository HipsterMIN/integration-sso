#!/usr/bin/env bash
# =============================================================================
# vault-init.sh — HashiCorp Vault Transit Engine 초기 설정 스크립트
#
# 실행 조건: Vault 컨테이너 기동 후 1회만 실행 (Dev 모드 재시작 시 재실행 필요)
#
# 사용법:
#   # 기본 (로컬 개발 — Dev 모드)
#   VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=dev-root-token ./infra/scripts/vault-init.sh
#
#   # 커스텀 키 이름
#   VAULT_TRANSIT_KEY=my-handoff-key ./infra/scripts/vault-init.sh
#
# 환경변수:
#   VAULT_ADDR         Vault 주소 (기본: http://localhost:8200)
#   VAULT_TOKEN        루트 또는 초기 설정용 토큰 (기본: dev-root-token)
#   VAULT_TRANSIT_PATH Transit 엔진 마운트 경로 (기본: transit)
#   VAULT_TRANSIT_KEY  Transit 키 이름 (기본: ido-handoff-key)
#   VAULT_KMS_POLICY   KMS 정책 이름 (기본: ido-kms-policy)
#   VAULT_IDO_ROLE     AppRole 이름 (기본: ido)
# =============================================================================
set -euo pipefail

VAULT_ADDR="${VAULT_ADDR:-http://localhost:8200}"
VAULT_TOKEN="${VAULT_TOKEN:-dev-root-token}"
VAULT_TRANSIT_PATH="${VAULT_TRANSIT_PATH:-transit}"
VAULT_TRANSIT_KEY="${VAULT_TRANSIT_KEY:-ido-handoff-key}"
VAULT_KMS_POLICY="${VAULT_KMS_POLICY:-ido-kms-policy}"
VAULT_IDO_ROLE="${VAULT_IDO_ROLE:-ido}"

export VAULT_ADDR VAULT_TOKEN

# ── 색상 출력 ──────────────────────────────────────────────────────────────
GREEN="\033[0;32m"; YELLOW="\033[1;33m"; RED="\033[0;31m"; NC="\033[0m"
ok()   { echo -e "${GREEN}[OK]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }
info() { echo -e "      $*"; }

echo ""
echo "=============================================="
echo " OnePass IDO — Vault KMS 초기화"
echo " VAULT_ADDR:         $VAULT_ADDR"
echo " VAULT_TRANSIT_PATH: $VAULT_TRANSIT_PATH"
echo " VAULT_TRANSIT_KEY:  $VAULT_TRANSIT_KEY"
echo "=============================================="
echo ""

# ── 1. Vault 연결 확인 ─────────────────────────────────────────────────────
info "Step 1: Vault 연결 확인..."
for i in $(seq 1 10); do
    if vault status -format=json 2>/dev/null | grep -q '"initialized": true'; then
        ok "Vault 응답 확인 (initialized)"
        break
    fi
    if [ "$i" -eq 10 ]; then
        fail "Vault 응답 없음. VAULT_ADDR=$VAULT_ADDR 확인"
    fi
    warn "Vault 대기 중... ($i/10)"
    sleep 2
done

# ── 2. Transit 시크릿 엔진 활성화 ─────────────────────────────────────────
info "Step 2: Transit 시크릿 엔진 활성화..."
if vault secrets list -format=json | grep -q "\"${VAULT_TRANSIT_PATH}/\""; then
    warn "Transit 엔진 이미 활성화됨: $VAULT_TRANSIT_PATH/"
else
    vault secrets enable -path="${VAULT_TRANSIT_PATH}" transit
    ok "Transit 엔진 활성화: ${VAULT_TRANSIT_PATH}/"
fi

# ── 3. IDO Handoff 키 생성 ─────────────────────────────────────────────────
info "Step 3: Transit 키 생성 (AES-256-GCM96)..."
if vault read "${VAULT_TRANSIT_PATH}/keys/${VAULT_TRANSIT_KEY}" >/dev/null 2>&1; then
    warn "Transit 키 이미 존재: ${VAULT_TRANSIT_KEY}"
else
    # AES-256-GCM96 (기본값) — FIPS 140-2 Level 1 인증 알고리즘
    vault write -f "${VAULT_TRANSIT_PATH}/keys/${VAULT_TRANSIT_KEY}"
    ok "Transit 키 생성: ${VAULT_TRANSIT_KEY} (AES-256-GCM96)"
fi

# ── 4. 키 정책 설정 ───────────────────────────────────────────────────────
info "Step 4: Transit 키 정책 설정..."
vault write "${VAULT_TRANSIT_PATH}/keys/${VAULT_TRANSIT_KEY}/config" \
    min_decryption_version=1 \
    deletion_allowed=false \
    auto_rotate_period=2160h   # 90일
ok "키 정책 설정: min_decryption_version=1, auto_rotate=90일"

# ── 5. 최소 권한 정책 생성 ─────────────────────────────────────────────────
info "Step 5: Vault 정책 생성 (${VAULT_KMS_POLICY})..."
vault policy write "${VAULT_KMS_POLICY}" - <<EOF
# IDO KMS 정책 — Handoff 키 암/복호화 전용
path "${VAULT_TRANSIT_PATH}/encrypt/${VAULT_TRANSIT_KEY}" {
  capabilities = ["update"]
}
path "${VAULT_TRANSIT_PATH}/decrypt/${VAULT_TRANSIT_KEY}" {
  capabilities = ["update"]
}
path "sys/health" {
  capabilities = ["read"]
}
EOF
ok "정책 생성: ${VAULT_KMS_POLICY}"

# ── 6. AppRole 인증 설정 (CI/CD용) ────────────────────────────────────────
info "Step 6: AppRole 인증 설정..."
if vault auth list -format=json | grep -q '"approle/"'; then
    warn "AppRole 인증 이미 활성화됨"
else
    vault auth enable approle
    ok "AppRole 인증 활성화"
fi

vault write "auth/approle/role/${VAULT_IDO_ROLE}" \
    policies="${VAULT_KMS_POLICY}" \
    token_ttl=1h \
    token_max_ttl=4h \
    secret_id_ttl=0   # 만료 없음 (운영에서는 주기적 교체 권장)
ok "AppRole 역할 설정: ${VAULT_IDO_ROLE}"

# AppRole 자격증명 출력
ROLE_ID=$(vault read -field=role_id "auth/approle/role/${VAULT_IDO_ROLE}/role-id")
SECRET_ID=$(vault write -f -field=secret_id "auth/approle/role/${VAULT_IDO_ROLE}/secret-id")

echo ""
echo "=============================================="
echo " AppRole 자격증명 (.env 또는 K8s Secret에 저장)"
echo "=============================================="
echo " VAULT_ROLE_ID=${ROLE_ID}"
echo " VAULT_SECRET_ID=${SECRET_ID}"
echo "=============================================="

# ── 7. Kubernetes 인증 설정 (K8s 운영용) ─────────────────────────────────
info "Step 7: Kubernetes 인증 설정 (K8s 환경에서만 유효)..."
if vault auth list -format=json | grep -q '"kubernetes/"'; then
    warn "Kubernetes 인증 이미 활성화됨 — 스킵"
else
    if [ -f /var/run/secrets/kubernetes.io/serviceaccount/token ]; then
        vault auth enable kubernetes
        vault write auth/kubernetes/config \
            kubernetes_host="https://kubernetes.default.svc"
        vault write "auth/kubernetes/role/${VAULT_IDO_ROLE}" \
            bound_service_account_names="${VAULT_IDO_ROLE}" \
            bound_service_account_namespaces=onepass \
            policies="${VAULT_KMS_POLICY}" \
            ttl=1h
        ok "Kubernetes 인증 설정: role=${VAULT_IDO_ROLE}, namespace=onepass"
    else
        warn "K8s 환경이 아님 — Kubernetes 인증 설정 스킵"
    fi
fi

# ── 8. 동작 검증 ──────────────────────────────────────────────────────────
info "Step 8: Transit 암/복호화 동작 검증..."
TEST_PLAIN="SGVsbG9LTVMh"  # "HelloKMS!" Base64
TEST_CIPHER=$(vault write -field=ciphertext \
    "${VAULT_TRANSIT_PATH}/encrypt/${VAULT_TRANSIT_KEY}" \
    plaintext="${TEST_PLAIN}")
TEST_DECRYPTED=$(vault write -field=plaintext \
    "${VAULT_TRANSIT_PATH}/decrypt/${VAULT_TRANSIT_KEY}" \
    ciphertext="${TEST_CIPHER}")

if [ "${TEST_DECRYPTED}" = "${TEST_PLAIN}" ]; then
    ok "Transit 동작 검증 성공: encrypt → decrypt 일치"
    info "  ciphertext: ${TEST_CIPHER:0:40}..."
else
    fail "Transit 동작 검증 실패: 복호화 결과 불일치"
fi

# ── 완료 ──────────────────────────────────────────────────────────────────
echo ""
echo "=============================================="
echo " Vault KMS 초기화 완료"
echo ""
echo " 다음 단계 — docker-compose.yml에 IDO 환경변수 추가:"
echo "   IDO_KMS_ENABLED=true"
echo "   IDO_KMS_PROVIDER=vault"
echo "   VAULT_ADDR=http://vault:8200"
echo "   VAULT_AUTH_METHOD=token"
echo "   VAULT_TOKEN=dev-root-token  (개발용)"
echo ""
echo " 또는 AppRole 방식:"
echo "   VAULT_AUTH_METHOD=approle"
echo "   VAULT_ROLE_ID=${ROLE_ID}"
echo "   VAULT_SECRET_ID=${SECRET_ID}"
echo "=============================================="
echo ""
