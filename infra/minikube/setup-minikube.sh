#!/usr/bin/env bash
# ============================================================
# Minikube 로컬 테스트 환경 셋업 스크립트
#
# 역할: 통합인증 플랫폼 전체 스택을 Minikube에 배포한다.
#       개발자 로컬 K8s 검증 전용 (운영 배포 사용 금지)
#
# 전제 조건:
#   - minikube v1.32+ 설치
#   - helm v3.14+ 설치
#   - kubectl 설치
#   - Docker 실행 중
#
# 사용법:
#   ./infra/minikube/setup-minikube.sh [--clean]
#     --clean : 기존 onepass namespace 삭제 후 재배포
# ============================================================
set -euo pipefail

NAMESPACE="onepass-dev"
RELEASE_NAME="onepass"
CHART_DIR="$(cd "$(dirname "$0")/../helm/onepass" && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── 컬러 출력 헬퍼 ────────────────────────────────────────────
info()  { echo -e "\e[34m[INFO]\e[0m  $*"; }
ok()    { echo -e "\e[32m[OK]\e[0m    $*"; }
warn()  { echo -e "\e[33m[WARN]\e[0m  $*"; }
error() { echo -e "\e[31m[ERR]\e[0m   $*" >&2; }
die()   { error "$*"; exit 1; }

# ── 인자 파싱 ─────────────────────────────────────────────────
CLEAN=false
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=true ;;
    *) warn "알 수 없는 옵션: $arg" ;;
  esac
done

# ── 사전 조건 확인 ────────────────────────────────────────────
info "사전 조건 확인..."
command -v minikube >/dev/null 2>&1 || die "minikube가 설치되지 않았습니다."
command -v helm     >/dev/null 2>&1 || die "helm이 설치되지 않았습니다."
command -v kubectl  >/dev/null 2>&1 || die "kubectl이 설치되지 않았습니다."
ok "모든 도구 확인 완료"

# ── Minikube 시작 ─────────────────────────────────────────────
info "Minikube 상태 확인..."
if ! minikube status --format='{{.Host}}' 2>/dev/null | grep -q "Running"; then
  info "Minikube 시작 중... (2 CPU, 4GB RAM)"
  minikube start \
    --cpus=2 \
    --memory=4096 \
    --disk-size=20g \
    --driver=docker \
    --addons=ingress,metrics-server \
    --kubernetes-version=v1.29.0
  ok "Minikube 시작 완료"
else
  ok "Minikube 이미 실행 중"
fi

# Docker 이미지를 Minikube에 사용하기 위해 minikube docker-env 활성화
info "Minikube Docker 환경 설정..."
eval "$(minikube docker-env)"

# ── 앱 이미지 빌드 (프로젝트 루트에서) ───────────────────────
info "이미지 빌드 확인..."
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
pushd "$PROJECT_ROOT" > /dev/null

# Gradle 빌드가 완료된 경우에만 Docker 빌드
for svc in q-sign q-im ido agency-stub; do
  IMG_NAME="onepass-${svc//-/}"
  case "$svc" in
    q-sign)      IMG_NAME="onepass-qsign";;
    q-im)        IMG_NAME="onepass-qim";;
    ido)         IMG_NAME="onepass-ido";;
    agency-stub) IMG_NAME="onepass-agency-stub";;
  esac

  if docker image inspect "${IMG_NAME}:latest" &>/dev/null; then
    ok "이미지 존재: ${IMG_NAME}:latest"
  else
    warn "이미지 없음: ${IMG_NAME}:latest — 빌드를 시도합니다."
    if [ -f "${svc}/Dockerfile" ]; then
      docker build -f "${svc}/Dockerfile" -t "${IMG_NAME}:latest" .
      ok "빌드 완료: ${IMG_NAME}:latest"
    else
      warn "Dockerfile 없음: ${svc}/Dockerfile — 건너뜁니다."
    fi
  fi
done
popd > /dev/null

# ── Namespace 정리 (--clean 옵션) ─────────────────────────────
if $CLEAN; then
  warn "--clean 옵션: $NAMESPACE 네임스페이스를 삭제합니다..."
  kubectl delete namespace "$NAMESPACE" --ignore-not-found=true
  kubectl wait --for=delete namespace/"$NAMESPACE" --timeout=60s 2>/dev/null || true
  ok "네임스페이스 삭제 완료"
fi

# ── Namespace 생성 ────────────────────────────────────────────
info "Namespace 생성: $NAMESPACE"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# ── Secret 생성 ───────────────────────────────────────────────
# host.minikube.internal: Minikube 내부에서 호스트 OS 접근 시 사용
# 로컬 테스트이므로 최소 보안 값 사용 (운영에서는 절대 사용 금지)
info "Secret 생성 (로컬 개발용 더미값)..."

# Q-Sign Secret
kubectl create secret generic onepass-qsign-secret \
  --namespace="$NAMESPACE" \
  --from-literal=DB_HOST="host.minikube.internal" \
  --from-literal=DB_USERNAME="onepass" \
  --from-literal=DB_PASSWORD="onepass" \
  --from-literal=KEYCLOAK_URL="http://host.minikube.internal:8088" \
  --from-literal=QSIGN_KEYCLOAK_CLIENT_SECRET="change-me" \
  --from-literal=IDO_BASE_URL="http://onepass-ido:8083" \
  --from-literal=IDO_INTERNAL_SIG_SECRET="dev-sig-secret-32-bytes-minimum00" \
  --from-literal=REDIS_HOST="host.minikube.internal" \
  --from-literal=REDIS_PASSWORD="" \
  --from-literal=KAFKA_SERVERS="host.minikube.internal:9092" \
  --dry-run=client -o yaml | kubectl apply -f -

# Q-IM Secret
kubectl create secret generic onepass-qim-secret \
  --namespace="$NAMESPACE" \
  --from-literal=QIM_DB_HOST="host.minikube.internal" \
  --from-literal=QIM_DB_USERNAME="qim" \
  --from-literal=QIM_DB_PASSWORD="qim" \
  --from-literal=QIM_INTERNAL_API_KEY="dev-qim-internal-api-key-change-me-0" \
  --from-literal=QIM_CI_AES_KEY_V1="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
  --from-literal=QIM_CI_AES_KEY_V2="" \
  --from-literal=QIM_DI_SECRET="dev-di-secret-change-in-production-00" \
  --from-literal=REDIS_HOST="host.minikube.internal" \
  --from-literal=REDIS_PASSWORD="" \
  --from-literal=KAFKA_SERVERS="host.minikube.internal:9092" \
  --dry-run=client -o yaml | kubectl apply -f -

# IdO Secret
kubectl create secret generic onepass-ido-secret \
  --namespace="$NAMESPACE" \
  --from-literal=DB_HOST="host.minikube.internal" \
  --from-literal=DB_USERNAME="onepass" \
  --from-literal=DB_PASSWORD="onepass" \
  --from-literal=IDO_QIM_INTERNAL_API_KEY="dev-qim-internal-api-key-change-me-0" \
  --from-literal=QIM_INBOUND_API_KEY_HASH="" \
  --from-literal=QIM_AES_SHARED_KEY="" \
  --from-literal=IDO_INTERNAL_SIG_SECRET="dev-sig-secret-32-bytes-minimum00" \
  --from-literal=KEYCLOAK_BASE_URL="http://host.minikube.internal:8088" \
  --from-literal=KEYCLOAK_CLIENT_SECRET="change-me" \
  --from-literal=QIM_BASE_URL="http://onepass-qim:8082" \
  --from-literal=QSIGN_BASE_URL="http://onepass-qsign:8081" \
  --from-literal=IDO_HANDOFF_AES_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
  --from-literal=IDO_HANDOFF_HMAC_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
  --from-literal=IDO_WEBHOOK_SIGNING_SECRET="poc-webhook-secret-change-in-production" \
  --from-literal=REDIS_HOST="host.minikube.internal" \
  --from-literal=REDIS_PASSWORD="" \
  --from-literal=KAFKA_SERVERS="host.minikube.internal:9092" \
  --from-literal=CORS_ORIGIN_DEV="http://localhost:3000" \
  --from-literal=CORS_ORIGIN_PROD="http://localhost:3001" \
  --dry-run=client -o yaml | kubectl apply -f -

# Agency-Stub Secret
kubectl create secret generic onepass-agency-stub-secret \
  --namespace="$NAMESPACE" \
  --from-literal=DB_HOST="host.minikube.internal" \
  --from-literal=DB_USERNAME="onepass" \
  --from-literal=DB_PASSWORD="onepass" \
  --from-literal=REDIS_HOST="host.minikube.internal" \
  --from-literal=REDIS_PASSWORD="" \
  --from-literal=KAFKA_SERVERS="host.minikube.internal:9092" \
  --from-literal=IDO_BASE_URL="http://onepass-ido:8083" \
  --from-literal=AGENCY_API_KEY="stub-api-key-dev-001" \
  --from-literal=IDO_WEBHOOK_SIGNING_SECRET="poc-webhook-secret-change-in-production" \
  --dry-run=client -o yaml | kubectl apply -f -

ok "Secret 생성 완료"

# ── Helm 배포 ─────────────────────────────────────────────────
info "Helm 배포: $RELEASE_NAME → $NAMESPACE"
helm upgrade --install "$RELEASE_NAME" "$CHART_DIR" \
  -f "${CHART_DIR}/values.yaml" \
  -f "${CHART_DIR}/values-dev.yaml" \
  --namespace "$NAMESPACE" \
  --set global.namespace="$NAMESPACE" \
  --set infra.postgres.host="host.minikube.internal" \
  --set infra.mariadb.host="host.minikube.internal" \
  --set infra.redis.host="host.minikube.internal" \
  --set infra.kafka.bootstrapServers="host.minikube.internal:9092" \
  --wait \
  --timeout=5m

ok "Helm 배포 완료"

# ── 배포 상태 확인 ────────────────────────────────────────────
info "Pod 상태 확인..."
kubectl get pods -n "$NAMESPACE"

info "Service 확인..."
kubectl get svc -n "$NAMESPACE"

# ── Minikube 터널 안내 ────────────────────────────────────────
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Minikube 로컬 테스트 환경 준비 완료"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo " Ingress를 통한 외부 접근을 위해 별도 터미널에서 실행:"
echo "   minikube tunnel"
echo ""
echo " 또는 Port-forward로 직접 접근:"
echo "   kubectl port-forward svc/onepass-ido 8083:8083 -n $NAMESPACE"
echo "   kubectl port-forward svc/onepass-qim 8082:8082 -n $NAMESPACE"
echo "   kubectl port-forward svc/onepass-qsign 8081:8081 -n $NAMESPACE"
echo ""
echo " Smoke test 실행:"
echo "   ./infra/scripts/smoke-test.sh --base-url http://localhost:8083"
echo ""
echo " 로그 확인:"
echo "   kubectl logs -f deployment/onepass-ido -n $NAMESPACE"
echo ""
