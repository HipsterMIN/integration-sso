#!/usr/bin/env bash
# =============================================================================
# setup-linux.sh — OnePass Minikube 로컬 K8s 시뮬레이션 (Linux)
#
# 지원 배포판: Ubuntu 20.04+, Debian 11+, RHEL/CentOS 8+, Fedora 38+
#
# 사전 요구사항:
#   - Docker Engine (Docker Desktop 불필요)
#   - 현재 사용자가 docker 그룹에 속해 있어야 함 (sudo 없이 docker 사용)
#     → sudo usermod -aG docker $USER && newgrp docker
#   - 프로젝트 루트에서 실행:
#       bash infra/minikube/scripts/setup-linux.sh [MODE]
#
# 실행 모드:
#   setup      : Minikube 초기화 + 인프라 기동 + ido 배포 (기본값)
#   infra-only : Docker Compose 인프라만 기동
#   deploy     : ido 이미지 빌드 + K8s 배포만
#   teardown   : 전체 정리
#   status     : 현재 상태 출력
#   logs       : ido Pod 로그 스트리밍
#   install    : 필요 도구 자동 설치 (apt/yum 기반)
#
# 사용법:
#   bash infra/minikube/scripts/setup-linux.sh
#   bash infra/minikube/scripts/setup-linux.sh setup
#   bash infra/minikube/scripts/setup-linux.sh install && \
#     bash infra/minikube/scripts/setup-linux.sh setup
# =============================================================================

set -euo pipefail

# ── 색상 출력 ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log_info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_success() { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error()   { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()    { echo -e "\n${BOLD}${BLUE}━━━ $* ━━━${NC}"; }

# ── 설정값 ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
INFRA_DIR="${PROJECT_ROOT}/infra"
DOCKER_DIR="${INFRA_DIR}/docker"
K8S_DIR="${INFRA_DIR}/k8s"
HELM_DIR="${INFRA_DIR}/helm"
MINIKUBE_DIR="${INFRA_DIR}/minikube"
OVERLAY_DIR="${MINIKUBE_DIR}/overlays/local"

MINIKUBE_PROFILE="onepass"
NAMESPACE="smes"
IDO_IMAGE="smes/ido"
IDO_TAG="local"
MINIKUBE_MEMORY="4096"
MINIKUBE_CPUS="2"
MINIKUBE_DISK="20g"
MINIKUBE_K8S_VERSION="v1.29.0"

# Linux: Docker 드라이버 사용 (Docker Desktop 불필요)
MINIKUBE_DRIVER="docker"

# Linux에서 호스트 접근 방법 감지
# Docker Compose가 동일 호스트에서 실행되므로 gateway IP 사용
HOST_GATEWAY=""

MODE="${1:-setup}"

# ── 유틸 함수 ──────────────────────────────────────────────────────────────────
detect_os() {
    if [[ -f /etc/os-release ]]; then
        . /etc/os-release
        echo "${ID}"
    elif command -v lsb_release &>/dev/null; then
        lsb_release -si | tr '[:upper:]' '[:lower:]'
    else
        echo "unknown"
    fi
}

detect_host_gateway() {
    # Minikube 컨테이너에서 호스트(Docker Compose)에 접근할 IP
    # docker0 브리지 또는 Minikube 네트워크 게이트웨이
    local gw
    gw=$(ip route show default 2>/dev/null | awk '/default/ {print $3; exit}')
    if [[ -z "${gw}" ]]; then
        # docker0 인터페이스 IP 사용
        gw=$(ip addr show docker0 2>/dev/null | awk '/inet / {split($2,a,"/"); print a[1]}')
    fi
    if [[ -z "${gw}" ]]; then
        gw="172.17.0.1"  # Docker 기본 게이트웨이 폴백
    fi
    echo "${gw}"
}

check_command() {
    if ! command -v "$1" &>/dev/null; then
        log_error "$1 이(가) 설치되어 있지 않습니다."
        echo "  힌트: bash infra/minikube/scripts/setup-linux.sh install"
        echo "  수동 설치: $2"
        exit 1
    fi
}

wait_for_pods() {
    local label="$1"
    local timeout="${2:-180}"
    log_info "Pod 준비 대기 중 (label: ${label}, timeout: ${timeout}s)..."
    if ! kubectl wait pod -l "${label}" -n "${NAMESPACE}" \
        --for=condition=Ready --timeout="${timeout}s" 2>/dev/null; then
        log_warn "Pod 준비 타임아웃. 현재 상태:"
        kubectl get pods -n "${NAMESPACE}" -l "${label}"
        kubectl describe pods -n "${NAMESPACE}" -l "${label}" | tail -30
        return 1
    fi
    log_success "Pod 준비 완료"
}

# ── 도구 자동 설치 (install 모드) ────────────────────────────────────────────
install_tools() {
    log_step "필요 도구 자동 설치"

    local os
    os=$(detect_os)
    log_info "감지된 OS: ${os}"

    case "${os}" in
        ubuntu|debian)
            sudo apt-get update -qq
            sudo apt-get install -y curl apt-transport-https ca-certificates gnupg lsb-release

            # Docker
            if ! command -v docker &>/dev/null; then
                log_info "Docker Engine 설치 중..."
                curl -fsSL https://get.docker.com | sudo sh
                sudo usermod -aG docker "${USER}"
                log_warn "docker 그룹 적용: 'newgrp docker' 또는 재로그인 필요"
            fi

            # kubectl
            if ! command -v kubectl &>/dev/null; then
                log_info "kubectl 설치 중..."
                curl -fsSL "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
                    -o /tmp/kubectl
                sudo install -o root -g root -m 0755 /tmp/kubectl /usr/local/bin/kubectl
            fi

            # minikube
            if ! command -v minikube &>/dev/null; then
                log_info "minikube 설치 중..."
                curl -fsSL https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 \
                    -o /tmp/minikube
                sudo install /tmp/minikube /usr/local/bin/minikube
            fi

            # helm
            if ! command -v helm &>/dev/null; then
                log_info "Helm 설치 중..."
                curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
            fi
            ;;

        rhel|centos|fedora|rocky|almalinux)
            sudo dnf install -y curl ca-certificates

            if ! command -v docker &>/dev/null; then
                log_info "Docker Engine 설치 중 (RHEL 계열)..."
                sudo dnf -y install dnf-plugins-core
                sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
                sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
                sudo systemctl enable --now docker
                sudo usermod -aG docker "${USER}"
                log_warn "docker 그룹 적용: 'newgrp docker' 또는 재로그인 필요"
            fi

            if ! command -v kubectl &>/dev/null; then
                curl -fsSL "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
                    -o /tmp/kubectl
                sudo install -o root -g root -m 0755 /tmp/kubectl /usr/local/bin/kubectl
            fi

            if ! command -v minikube &>/dev/null; then
                curl -fsSL https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 \
                    -o /tmp/minikube
                sudo install /tmp/minikube /usr/local/bin/minikube
            fi

            if ! command -v helm &>/dev/null; then
                curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
            fi
            ;;

        *)
            log_warn "자동 설치 미지원 OS: ${os}"
            log_warn "수동으로 docker, kubectl, minikube, helm을 설치하세요."
            ;;
    esac

    log_success "도구 설치 완료"
    log_warn "docker 그룹 변경이 있었다면 'newgrp docker' 실행 후 스크립트를 다시 실행하세요."
}

# ── 사전 요구사항 확인 ─────────────────────────────────────────────────────────
check_prerequisites() {
    log_step "사전 요구사항 확인"

    check_command "docker"   "bash infra/minikube/scripts/setup-linux.sh install"
    check_command "minikube" "bash infra/minikube/scripts/setup-linux.sh install"
    check_command "kubectl"  "bash infra/minikube/scripts/setup-linux.sh install"
    check_command "helm"     "bash infra/minikube/scripts/setup-linux.sh install"

    # Docker 실행 확인
    if ! docker info &>/dev/null; then
        log_error "Docker 데몬이 실행 중이지 않습니다."
        log_error "실행: sudo systemctl start docker"
        log_error "현재 사용자가 docker 그룹에 없다면: sudo usermod -aG docker \$USER && newgrp docker"
        exit 1
    fi

    # docker 그룹 확인
    if ! groups | grep -q docker; then
        log_warn "현재 사용자가 docker 그룹에 없습니다."
        log_warn "sudo usermod -aG docker \$USER && newgrp docker 실행 후 재시도하세요."
    fi

    # 프로젝트 루트 확인
    if [[ ! -f "${PROJECT_ROOT}/settings.gradle.kts" && ! -f "${PROJECT_ROOT}/settings.gradle" ]]; then
        log_error "프로젝트 루트를 찾을 수 없습니다: ${PROJECT_ROOT}"
        exit 1
    fi

    # 호스트 게이트웨이 감지
    HOST_GATEWAY=$(detect_host_gateway)
    log_info "호스트 게이트웨이 감지: ${HOST_GATEWAY}"

    log_success "모든 요구사항 충족"
}

# ── Minikube 기동 ──────────────────────────────────────────────────────────────
start_minikube() {
    log_step "Minikube 기동"

    if minikube status -p "${MINIKUBE_PROFILE}" 2>/dev/null | grep -q "Running"; then
        log_success "Minikube '${MINIKUBE_PROFILE}' 이미 실행 중"
        return 0
    fi

    log_info "Minikube 클러스터 생성..."
    minikube start \
        --profile="${MINIKUBE_PROFILE}" \
        --driver="${MINIKUBE_DRIVER}" \
        --memory="${MINIKUBE_MEMORY}" \
        --cpus="${MINIKUBE_CPUS}" \
        --disk-size="${MINIKUBE_DISK}" \
        --kubernetes-version="${MINIKUBE_K8S_VERSION}" \
        --addons=metrics-server \
        --addons=ingress

    kubectl config use-context "${MINIKUBE_PROFILE}"
    log_success "Minikube 기동 완료"

    # Linux: host.minikube.internal 해상도 확인
    log_info "host.minikube.internal 라우팅 확인..."
    local host_check
    host_check=$(minikube -p "${MINIKUBE_PROFILE}" ssh "ping -c 1 host.minikube.internal" 2>/dev/null || true)
    if echo "${host_check}" | grep -q "1 received"; then
        log_success "host.minikube.internal 접근 가능"
    else
        log_warn "host.minikube.internal 미접근. /etc/hosts에 수동 추가가 필요할 수 있습니다."
        log_warn "미니큐브 IP: $(minikube -p "${MINIKUBE_PROFILE}" ip)"
    fi
}

# ── Docker Compose 인프라 기동 ─────────────────────────────────────────────────
start_infra() {
    log_step "Docker Compose 인프라 기동"

    cd "${DOCKER_DIR}"

    if docker compose ps --services 2>/dev/null | grep -q "postgres"; then
        local running
        running=$(docker compose ps --status running --services 2>/dev/null | wc -l | tr -d ' ')
        if [[ "${running}" -gt 0 ]]; then
            log_success "인프라 컨테이너 이미 실행 중 (${running}개)"
            return 0
        fi
    fi

    log_info "인프라 컨테이너 기동 중..."
    docker compose \
        -f docker-compose.yml \
        -f docker-compose.monitoring.yml \
        up -d

    log_info "PostgreSQL 준비 대기 중..."
    local retries=30
    while [[ $retries -gt 0 ]]; do
        if docker compose exec -T postgres pg_isready -U onepass -d onepass &>/dev/null; then
            break
        fi
        sleep 2
        ((retries--))
    done
    [[ $retries -eq 0 ]] && { log_error "PostgreSQL 기동 타임아웃"; exit 1; }

    log_success "인프라 기동 완료"
    cd "${PROJECT_ROOT}"
}

# ── ido 이미지 빌드 ─────────────────────────────────────────────────────────────
build_ido_image() {
    log_step "ido 애플리케이션 이미지 빌드"

    cd "${PROJECT_ROOT}"

    log_info "Minikube Docker 환경으로 전환..."
    eval "$(minikube -p "${MINIKUBE_PROFILE}" docker-env)"

    log_info "Gradle bootJar 빌드 중..."
    if [[ -f "./gradlew" ]]; then
        chmod +x ./gradlew
        ./gradlew :ido:bootJar -x test --quiet
    else
        log_error "gradlew를 찾을 수 없습니다"
        exit 1
    fi

    log_info "Docker 이미지 빌드 중: ${IDO_IMAGE}:${IDO_TAG}"
    docker build \
        -f ido/Dockerfile \
        -t "${IDO_IMAGE}:${IDO_TAG}" \
        --build-arg SPRING_PROFILES_ACTIVE=local \
        .

    log_success "이미지 빌드 완료: ${IDO_IMAGE}:${IDO_TAG}"
    eval "$(minikube -p "${MINIKUBE_PROFILE}" docker-env --unset)"
}

# ── K8s 리소스 배포 ────────────────────────────────────────────────────────────
deploy_to_k8s() {
    log_step "K8s 리소스 배포"

    # Linux: host.minikube.internal 대신 실제 게이트웨이 IP 사용 (접근 불가 시 폴백)
    local db_host="host.minikube.internal"
    if ! minikube -p "${MINIKUBE_PROFILE}" ssh \
        "getent hosts host.minikube.internal" &>/dev/null; then
        db_host="${HOST_GATEWAY}"
        log_warn "host.minikube.internal 미지원 — 게이트웨이 IP 사용: ${db_host}"
    fi

    kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
    log_success "Namespace '${NAMESPACE}' 준비 완료"

    # ConfigMap 적용
    if [[ -f "${OVERLAY_DIR}/ido-configmap-local.yml" ]]; then
        kubectl apply -f "${OVERLAY_DIR}/ido-configmap-local.yml"
    else
        kubectl apply -f "${K8S_DIR}/configmaps/ido-configmap.yml"
    fi
    log_success "ConfigMap 적용 완료"

    # Secret 생성
    kubectl create secret generic ido-secrets \
        --namespace="${NAMESPACE}" \
        --from-literal=DB_HOST="${db_host}" \
        --from-literal=DB_PORT=5432 \
        --from-literal=DB_NAME=onepass \
        --from-literal=DB_USERNAME=onepass \
        --from-literal=DB_PASSWORD=onepass \
        --from-literal=REDIS_HOST="${db_host}" \
        --from-literal=REDIS_PORT=6379 \
        --from-literal=REDIS_PASSWORD="" \
        --from-literal=KAFKA_SERVERS="${db_host}:9092" \
        --from-literal=IDO_HANDOFF_AES_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
        --from-literal=IDO_HANDOFF_HMAC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
        --from-literal=IDO_WEBHOOK_SIGNING_SECRET=poc-webhook-secret-local \
        --from-literal=IDO_INTERNAL_SIG_SECRET=poc-internal-secret-local \
        --from-literal=IDO_AGENCY_SUBJECT_SECRET=poc-agency-secret-local \
        --from-literal=NICE_CLIENT_ID=dummy-local \
        --from-literal=NICE_CLIENT_SECRET=dummy-local \
        --from-literal=NHN_SKM_APPKEY=dummy-local \
        --from-literal=NHN_SKM_MODE=secret \
        --from-literal=NHN_SKM_AES_KEY_ID=dummy-local \
        --from-literal=NHN_SKM_HMAC_KEY_ID=dummy-local \
        --from-literal=KEYCLOAK_CLIENT_ID=ido-client \
        --from-literal=KEYCLOAK_CLIENT_SECRET=dummy-local \
        --from-literal=QIM_AES_SHARED_KEY=dummy-local \
        --from-literal=QIM_INBOUND_API_KEY_HASH=dummy-local \
        --save-config \
        --dry-run=client -o yaml | kubectl apply -f -
    log_success "Secret 생성 완료"

    log_info "Helm으로 ido 배포 중..."
    helm upgrade --install ido "${HELM_DIR}/ido" \
        --namespace="${NAMESPACE}" \
        --set image.repository="${IDO_IMAGE}" \
        --set image.tag="${IDO_TAG}" \
        --set image.pullPolicy=Never \
        --set replicaCount=1 \
        --set spring.profilesActive=local \
        --set namespace="${NAMESPACE}" \
        --set autoscaling.enabled=false \
        --set topologySpread.enabled=false \
        --timeout=120s \
        --wait=false

    log_success "Helm 배포 완료"
    wait_for_pods "app=ido" 180 || log_warn "Pod 준비 미완료 — 로그를 확인하세요"
}

# ── 포트 포워딩 ────────────────────────────────────────────────────────────────
setup_port_forward() {
    log_step "포트 포워딩 설정"

    pkill -f "kubectl port-forward.*ido" 2>/dev/null || true
    sleep 1

    log_info "ido 포트 포워딩: localhost:8083 → ido-service:8083"
    kubectl port-forward service/ido-service 8083:8083 \
        -n "${NAMESPACE}" &>/dev/null &
    echo $! > /tmp/onepass-pf-ido.pid

    sleep 2

    local retries=15
    while [[ $retries -gt 0 ]]; do
        if curl -sf http://localhost:8083/actuator/health &>/dev/null; then
            log_success "ido 헬스체크 통과: http://localhost:8083/actuator/health"
            return 0
        fi
        sleep 3
        ((retries--))
    done
    log_warn "헬스체크 미통과 — 아직 시작 중일 수 있습니다."
}

# ── 상태 출력 ──────────────────────────────────────────────────────────────────
print_status() {
    log_step "현재 상태"

    echo ""
    echo -e "${BOLD}[ Minikube 클러스터 ]${NC}"
    minikube status -p "${MINIKUBE_PROFILE}" 2>/dev/null || echo "  (비활성)"

    echo ""
    echo -e "${BOLD}[ K8s Pods — namespace: ${NAMESPACE} ]${NC}"
    kubectl get pods -n "${NAMESPACE}" -o wide 2>/dev/null || echo "  (없음)"

    echo ""
    echo -e "${BOLD}[ K8s Services ]${NC}"
    kubectl get svc -n "${NAMESPACE}" 2>/dev/null || echo "  (없음)"

    echo ""
    echo -e "${BOLD}[ Docker Compose 인프라 ]${NC}"
    docker compose -f "${DOCKER_DIR}/docker-compose.yml" ps 2>/dev/null || echo "  (없음)"

    echo ""
    echo -e "${BOLD}[ 호스트 게이트웨이 ]${NC}"
    echo "  ${HOST_GATEWAY:-$(detect_host_gateway)}"

    echo ""
    echo -e "${BOLD}[ 접속 정보 ]${NC}"
    echo "  ido API    : http://localhost:8083"
    echo "  Actuator   : http://localhost:8083/actuator/health"
    echo "  Grafana    : http://localhost:3000  (admin / onepass-admin)"
    echo "  Prometheus : http://localhost:9090"
    echo "  Kafka UI   : http://localhost:8090  (admin / admin)"
}

# ── 로그 스트리밍 ──────────────────────────────────────────────────────────────
stream_logs() {
    log_info "ido Pod 로그 스트리밍 (Ctrl+C로 종료)"
    kubectl logs -f -l app=ido -n "${NAMESPACE}" --all-containers=true 2>/dev/null
}

# ── 전체 정리 ──────────────────────────────────────────────────────────────────
teardown() {
    log_step "전체 환경 정리"

    [[ -f /tmp/onepass-pf-ido.pid ]] && kill "$(cat /tmp/onepass-pf-ido.pid)" 2>/dev/null || true
    rm -f /tmp/onepass-pf-ido.pid

    helm uninstall ido -n "${NAMESPACE}" 2>/dev/null && log_success "Helm 릴리스 삭제" || true
    kubectl delete namespace "${NAMESPACE}" --ignore-not-found=true

    read -r -p "Minikube 클러스터를 삭제하시겠습니까? (y/N): " confirm
    if [[ "${confirm}" =~ ^[Yy]$ ]]; then
        minikube delete -p "${MINIKUBE_PROFILE}"
        log_success "Minikube 삭제"
    else
        minikube stop -p "${MINIKUBE_PROFILE}"
        log_success "Minikube 중지"
    fi

    read -r -p "Docker Compose 인프라도 종료하시겠습니까? (y/N): " confirm2
    if [[ "${confirm2}" =~ ^[Yy]$ ]]; then
        cd "${DOCKER_DIR}"
        docker compose -f docker-compose.yml -f docker-compose.monitoring.yml down
        cd "${PROJECT_ROOT}"
        log_success "Docker Compose 종료"
    fi

    log_success "정리 완료"
}

# ── 메인 ───────────────────────────────────────────────────────────────────────
main() {
    echo -e "${BOLD}${BLUE}"
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║   OnePass Minikube 로컬 K8s 시뮬레이션 (Linux)      ║"
    echo "╚══════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    echo "  MODE    : ${MODE}"
    echo "  PROJECT : ${PROJECT_ROOT}"
    echo "  OS      : $(detect_os)"
    echo ""

    case "${MODE}" in
        setup)
            check_prerequisites
            start_minikube
            start_infra
            build_ido_image
            deploy_to_k8s
            setup_port_forward
            print_status
            echo ""
            log_success "=== 설정 완료 ==="
            echo "  ido API    : http://localhost:8083/actuator/health"
            echo "  로그 확인  : bash infra/minikube/scripts/setup-linux.sh logs"
            echo "  상태 확인  : bash infra/minikube/scripts/setup-linux.sh status"
            echo "  정리       : bash infra/minikube/scripts/setup-linux.sh teardown"
            ;;
        infra-only)
            check_prerequisites
            start_infra
            ;;
        deploy)
            check_prerequisites
            build_ido_image
            deploy_to_k8s
            setup_port_forward
            ;;
        install)
            install_tools
            ;;
        teardown)
            teardown
            ;;
        status)
            HOST_GATEWAY=$(detect_host_gateway)
            print_status
            ;;
        logs)
            stream_logs
            ;;
        *)
            log_error "알 수 없는 모드: ${MODE}"
            echo "사용법: $0 [setup|infra-only|deploy|teardown|status|logs|install]"
            exit 1
            ;;
    esac
}

main "$@"
