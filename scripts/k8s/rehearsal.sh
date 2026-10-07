#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# K8s 실배포 리허설 (1.1 PR-6, 플랜 §5 #5) — Helm 차트 infra/helm/idem 을 실제 클러스터에 배포·검증·업그레이드·롤백·제거한다.
#
# 단계(기본은 전부, 인자로 고를 수 있다):
#   up        kind 클러스터 + ingress-nginx + 네임스페이스 + DB Secret + 리허설용 PostgreSQL·Redis(infra.yaml)
#   install   비밀 한 벌(scripts/lib/gen-install-env.sh) → Secret · 자체 CA 로 TLS Secret · helm install --wait → 리비전 1 검사
#   smoke     Ingress(TLS)·port-forward 로 설치본 스모크(scripts/ci/install-smoke.sh) + 관리 포트·Ingress 검사
#   upgrade   values-prod-switch.yaml 로 운영 전환(prod 프로파일·Mock off) — helm upgrade --wait → 리비전 2 검사
#   rollback  helm rollback 1 --wait → Mock 다시 켜졌는지(리비전 1 설정으로 돌아왔는지) 검사
#   down      helm uninstall → Pod 0 확인 → 네임스페이스 삭제 → kind 클러스터 삭제 (KEEP=1 이면 클러스터는 남긴다)
#
# 환경 (전부 선택):
#   CLUSTER=kind|existing     kind(기본): 클러스터를 만든다. existing: 현재 kubeconfig 컨텍스트를 쓴다 — Ingress 컨트롤러가 있고 HOST_* 가 그 주소로 풀려야 한다
#   KIND_CLUSTER=idem-rehearsal  HTTP_PORT=80  HTTPS_PORT=443   (kind) 호스트 포트 — 80/443 이 막혀 있으면 8080/8443 같은 값으로
#   INGRESS_NGINX_MANIFEST=<URL|파일>  kind 용 ingress-nginx 매니페스트 (기본 controller-v1.12.1, 폐쇄망은 파일로)
#   NS=idem  RELEASE=idem
#   IMAGES=local|archive|registry   local(기본): 로컬 docker 이미지를 kind 에 싣는다 (idem-gate:$IMAGE_TAG, idem-hub:$IMAGE_TAG-core …)
#                                   archive: IMAGE_ARCHIVE_DIR 의 *.tar 를 kind 에 싣는다 (CI PR — docker-build-check 산출물)
#                                   registry: IMAGE_REGISTRY/<이름>:<IMAGE_TAG>[-core] 를 클러스터가 내려받는다 (REGISTRY_USER/REGISTRY_TOKEN 이 있으면 pull Secret)
#   IMAGE_TAG=pr  IMAGE_REGISTRY=ghcr.io/hipstermin  IMAGE_ARCHIVE_DIR=/tmp/images
#   HOST_GATE=sso.idem.local HOST_HUB=hub.idem.local HOST_CONSOLE=console.idem.local   공개 호스트 (kind: /etc/hosts 에 127.0.0.1 로 — ADD_HOSTS=1 기본, sudo)
#   OUT_DIR=/tmp/idem-rehearsal   install.env(비밀)·CA·values-env.yaml·logs/·timings.txt
#   KEEP=1                     down 에서 kind 클러스터를 지우지 않는다
#
# 필요: kubectl · helm 3 · kind(CLUSTER=kind) · openssl · curl · jq · python3. 이미지는 코어 에디션 5종
# (idem-gate · idem-hub-core · idem-registry-core · idem-authz · idem-console-admin). KR 에디션은 벤더 SDK 이미지가 있는 곳에서만 — 여기서는 다루지 않는다.
# 결과: 단계별 소요 시간을 OUT_DIR/timings.txt 와(CI 면) GITHUB_STEP_SUMMARY 에 적는다. 실패하면 Pod·이벤트·로그를 OUT_DIR/logs 에 모은다.
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/scripts/k8s/rehearsal"
CHART="$ROOT/infra/helm/idem"

CLUSTER="${CLUSTER:-kind}"
KIND_CLUSTER="${KIND_CLUSTER:-idem-rehearsal}"
HTTP_PORT="${HTTP_PORT:-80}"
HTTPS_PORT="${HTTPS_PORT:-443}"
INGRESS_NGINX_MANIFEST="${INGRESS_NGINX_MANIFEST:-https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/kind/deploy.yaml}"
NS="${NS:-idem}"
RELEASE="${RELEASE:-idem}"
IMAGES="${IMAGES:-local}"
IMAGE_TAG="${IMAGE_TAG:-pr}"
IMAGE_REGISTRY="${IMAGE_REGISTRY:-ghcr.io/hipstermin}"
IMAGE_ARCHIVE_DIR="${IMAGE_ARCHIVE_DIR:-/tmp/images}"
HOST_GATE="${HOST_GATE:-sso.idem.local}"
HOST_HUB="${HOST_HUB:-hub.idem.local}"
HOST_CONSOLE="${HOST_CONSOLE:-console.idem.local}"
ADD_HOSTS="${ADD_HOSTS:-1}"
OUT_DIR="${OUT_DIR:-/tmp/idem-rehearsal}"
KEEP="${KEEP:-0}"
HELM_TIMEOUT="${HELM_TIMEOUT:-12m}"

PORT_SUFFIX=""; [ "$HTTPS_PORT" != "443" ] && PORT_SUFFIX=":$HTTPS_PORT"
GATE_PUBLIC="https://$HOST_GATE$PORT_SUFFIX"
HUB_PUBLIC="https://$HOST_HUB$PORT_SUFFIX"
CONSOLE_PUBLIC="https://$HOST_CONSOLE$PORT_SUFFIX"

mkdir -p "$OUT_DIR/logs"
LOGS="$OUT_DIR/logs"
TIMINGS="$OUT_DIR/timings.txt"

say()  { echo; echo "━━ $*"; }
ok()   { echo "  ✅ $*"; }
fail() { echo "  ❌ $*" >&2; exit 1; }
need() { for t in "$@"; do command -v "$t" >/dev/null 2>&1 || fail "필요한 명령이 없습니다: $t"; done; }
kc()   { kubectl -n "$NS" "$@"; }

PHASE_START=0
phase_begin() { PHASE_START=$(date +%s); say "$1"; }
phase_end()   { local d=$(( $(date +%s) - PHASE_START )); echo "  ⏱ $1: ${d}s"; printf '%s\t%ss\n' "$1" "$d" >> "$TIMINGS"; }

# 실패 진단 — Pod·이벤트·설명·로그를 OUT_DIR/logs 에 (비밀은 안 들어간다: values-env 에는 URL·이미지만)
diagnose() {
  echo "  ⚠ 실패 — 진단을 $LOGS 에 모읍니다" >&2
  { helm -n "$NS" status "$RELEASE" 2>&1 || true; helm -n "$NS" history "$RELEASE" 2>&1 || true; } > "$LOGS/helm.txt"
  kc get pods -o wide > "$LOGS/pods.txt" 2>&1 || true
  kc get events --sort-by=.lastTimestamp > "$LOGS/events.txt" 2>&1 || true
  kc get ingress,svc,job,secret -o wide > "$LOGS/objects.txt" 2>&1 || true
  for p in $(kc get pods -o name 2>/dev/null); do
    n=${p#pod/}
    kc describe "$p" > "$LOGS/describe-$n.txt" 2>&1 || true
    kc logs "$p" --all-containers --tail=300 > "$LOGS/log-$n.txt" 2>&1 || true
    kc logs "$p" --all-containers --previous --tail=100 > "$LOGS/log-prev-$n.txt" 2>&1 || rm -f "$LOGS/log-prev-$n.txt"
  done
  if [ "$CLUSTER" = "kind" ]; then kubectl -n ingress-nginx get pods -o wide > "$LOGS/ingress-nginx.txt" 2>&1 || true; fi
  cat "$LOGS/pods.txt" >&2 || true
  tail -30 "$LOGS/events.txt" >&2 || true
  # 준비 안 된 Pod 는 상태(종료 코드·OOMKilled 등)와 로그 꼬리를 잡 출력에도 낸다 — 아티팩트를 못 받는 환경에서도 원인이 보이도록
  for p in $(kc get pods -o name 2>/dev/null); do
    local n=${p#pod/} ready
    ready=$(kc get "$p" -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null || echo "")
    [ "$ready" = true ] && continue
    { echo "──── $n  state=$(kc get "$p" -o jsonpath='{.status.containerStatuses[0].state}' 2>/dev/null)  lastState=$(kc get "$p" -o jsonpath='{.status.containerStatuses[0].lastState}' 2>/dev/null)"
      echo "── logs (current, tail 80)"; kc logs "$p" --all-containers --tail=80 2>&1 || true
      echo "── logs (previous, tail 80)"; kc logs "$p" --all-containers --previous --tail=80 2>&1 || true; } >&2
  done
}
trap 'rc=$?; if [ $rc -ne 0 ]; then diagnose; fi; kill_port_forwards; exit $rc' EXIT

PF_PIDS=()
kill_port_forwards() { for pid in "${PF_PIDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done; PF_PIDS=(); }
# ready_pod <app> — Ready 이고 종료 중이 아닌 Pod 하나 (deploy/ 로 port-forward 하면 롤링 갱신 직후 종료 중인 옛 Pod 에 붙을 수 있다)
ready_pod() {
  kc get pods -l "app=$1" -o json | jq -r '[.items[] | select(.metadata.deletionTimestamp == null) | select([.status.conditions[]? | select(.type=="Ready" and .status=="True")] | length > 0)][0].metadata.name // empty'
}
# port_forward <app> <local>:<remote> [<local>:<remote>…] — Ready Pod 에 붙고, 열릴 때까지 기다린다
port_forward() {
  local target=$1; shift
  local pod; pod=$(ready_pod "$target"); [ -n "$pod" ] || fail "Ready 인 $target Pod 가 없다"
  kc port-forward "pod/$pod" "$@" > "$LOGS/pf-$target.txt" 2>&1 &
  PF_PIDS+=($!)
  local first=${1%%:*}
  for _ in $(seq 1 30); do (echo > "/dev/tcp/127.0.0.1/$first") 2>/dev/null && return 0; sleep 1; done
  fail "port-forward 가 열리지 않습니다: $target $*"
}

wait_ready() {   # wait_ready <label-selector> <timeout-s> — Pod 준비
  local sel=$1 to=$2 i
  for i in $(seq 1 "$to"); do
    if kc get pods -l "$sel" -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null | grep -q True; then return 0; fi
    sleep 1
  done
  fail "Pod 가 준비되지 않습니다: $sel (${to}s)"
}

image_ref() {   # image_ref <이름> <에디션 여부>
  local name=$1 ed=$2 tag=$IMAGE_TAG
  [ "$ed" = 1 ] && tag="$tag-core"
  if [ "$IMAGES" = registry ]; then echo "$IMAGE_REGISTRY/$name:$tag"; else echo "$name:$tag"; fi
}

# ── up ────────────────────────────────────────────────────────────────────
phase_up() {
  phase_begin "up — 클러스터·Ingress 컨트롤러·네임스페이스·리허설 인프라"
  need kubectl helm openssl
  if [ "$CLUSTER" = kind ]; then
    need kind
    if kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER"; then
      ok "kind 클러스터 $KIND_CLUSTER 가 이미 있다 — 재사용"
    else
      sed -e "s/\${KIND_CLUSTER}/$KIND_CLUSTER/" -e "s/\${HTTP_PORT}/$HTTP_PORT/" -e "s/\${HTTPS_PORT}/$HTTPS_PORT/" "$HERE/kind-config.yaml" > "$OUT_DIR/kind-config.yaml"
      kind create cluster --config "$OUT_DIR/kind-config.yaml" --wait 120s
      ok "kind 클러스터 $KIND_CLUSTER (host $HTTP_PORT/$HTTPS_PORT → node 80/443)"
    fi
    kubectl config use-context "kind-$KIND_CLUSTER" >/dev/null
    kubectl apply -f "$INGRESS_NGINX_MANIFEST" > "$LOGS/ingress-nginx-apply.txt"
    for _ in $(seq 1 60); do kubectl -n ingress-nginx get deploy ingress-nginx-controller >/dev/null 2>&1 && break; sleep 2; done
    kubectl -n ingress-nginx rollout status deploy/ingress-nginx-controller --timeout=240s >/dev/null
    # admission webhook 이 Ingress 생성을 거부하지 않도록 준비를 기다린다
    kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=120s >/dev/null
    ok "ingress-nginx 준비"
    if [ "$ADD_HOSTS" = 1 ]; then
      for h in "$HOST_GATE" "$HOST_HUB" "$HOST_CONSOLE"; do
        if ! getent hosts "$h" >/dev/null; then
          if [ "$(id -u)" = 0 ]; then echo "127.0.0.1 $h" >> /etc/hosts; else sudo sh -c "echo '127.0.0.1 $h' >> /etc/hosts"; fi
          ok "/etc/hosts: 127.0.0.1 $h"
        fi
      done
    fi
  else
    kubectl cluster-info >/dev/null || fail "현재 kubeconfig 컨텍스트에 닿지 않습니다"
    ok "기존 클러스터: $(kubectl config current-context)"
  fi
  for h in "$HOST_GATE" "$HOST_HUB" "$HOST_CONSOLE"; do getent hosts "$h" >/dev/null || fail "$h 가 풀리지 않습니다 — /etc/hosts 또는 DNS 에 Ingress 주소로 등록하세요"; done
  echo "  K8s: $(kubectl version 2>/dev/null | grep Server | head -1)  helm: $(helm version --short)"

  kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create ns "$NS" >/dev/null
  if ! kc get secret idem-db-secret >/dev/null 2>&1; then
    local dbpw; dbpw=$(openssl rand -hex 16)
    kc create secret generic idem-db-secret --from-literal=username=idem --from-literal=password="$dbpw" >/dev/null
    ok "Secret idem-db-secret (username/password)"
  fi
  kc apply -f "$HERE/infra.yaml" > "$LOGS/infra-apply.txt"
  wait_ready app=postgres 120; wait_ready app=redis 60
  ok "리허설 PostgreSQL 16 · Redis 7 준비 (같은 네임스페이스의 Service postgres · redis)"
  phase_end up
}

# ── install ───────────────────────────────────────────────────────────────
load_images() {
  local names=("idem-gate:0" "idem-hub:1" "idem-registry:1" "idem-authz:0" "idem-console-admin:0")
  case "$IMAGES" in
    local)
      [ "$CLUSTER" = kind ] || fail "IMAGES=local 은 kind 에서만 — 기존 클러스터는 IMAGES=registry 로"
      need docker
      for e in "${names[@]}"; do local ref; ref=$(image_ref "${e%%:*}" "${e##*:}"); docker image inspect "$ref" >/dev/null 2>&1 || fail "로컬 이미지가 없습니다: $ref (docker build -f ${e%%:*}/Dockerfile …)"; kind load docker-image "$ref" --name "$KIND_CLUSTER" >/dev/null; done
      ok "로컬 이미지 5종을 kind 에 실었다 (태그 $IMAGE_TAG)";;
    archive)
      [ "$CLUSTER" = kind ] || fail "IMAGES=archive 는 kind 에서만"
      local n=0
      for t in "$IMAGE_ARCHIVE_DIR"/*.tar; do [ -f "$t" ] || continue; kind load image-archive "$t" --name "$KIND_CLUSTER" >/dev/null; n=$((n+1)); done
      [ "$n" -ge 5 ] || fail "이미지 아카이브가 5개 미만입니다: $IMAGE_ARCHIVE_DIR ($n)"
      ok "이미지 아카이브 $n 개를 kind 에 실었다 (태그 $IMAGE_TAG)";;
    registry)
      if [ -n "${REGISTRY_TOKEN:-}" ]; then
        kc delete secret idem-registry-pull --ignore-not-found >/dev/null
        kc create secret docker-registry idem-registry-pull --docker-server="${IMAGE_REGISTRY%%/*}" --docker-username="${REGISTRY_USER:-token}" --docker-password="$REGISTRY_TOKEN" >/dev/null
        ok "pull Secret idem-registry-pull (${IMAGE_REGISTRY%%/*})"
      fi
      ok "이미지: $IMAGE_REGISTRY/<이름>:$IMAGE_TAG (클러스터가 내려받는다)";;
    *) fail "IMAGES 는 local|archive|registry";;
  esac
}

write_values_env() {
  local reg="" pull=""
  [ "$IMAGES" = registry ] && reg="$IMAGE_REGISTRY"
  [ "$IMAGES" = registry ] && [ -n "${REGISTRY_TOKEN:-}" ] && pull="  imagePullSecrets: [{ name: idem-registry-pull }]"
  cat > "$OUT_DIR/values-env.yaml" <<YAML
# 리허설 환경값 — scripts/k8s/rehearsal.sh 가 만든다 (비밀 없음)
global:
  imageRegistry: "$reg"
  imageTag: "$IMAGE_TAG"
$pull
  publicUrl:
    gate: $GATE_PUBLIC
    hub: $HUB_PUBLIC
    console: $CONSOLE_PUBLIC
YAML
}

helm_apply() {   # helm_apply <설명> [추가 -f …]
  local what=$1; shift
  helm upgrade --install "$RELEASE" "$CHART" -n "$NS" -f "$HERE/values-rehearsal.yaml" -f "$OUT_DIR/values-env.yaml" "$@" \
    --wait --timeout "$HELM_TIMEOUT" > "$LOGS/helm-$what.txt" 2>&1 || { cat "$LOGS/helm-$what.txt" >&2; fail "helm $what 실패"; }
}

check_release() {   # check_release <기대 리비전> <기대 프로파일>
  local rev=$1 profile=$2
  local cur; cur=$(helm -n "$NS" list -o json | jq -r ".[] | select(.name==\"$RELEASE\") | .revision")
  [ "$cur" = "$rev" ] && ok "리비전 $cur, status $(helm -n "$NS" list -o json | jq -r ".[] | select(.name==\"$RELEASE\") | .status")" || fail "리비전 기대 $rev, 실제 $cur"
  # Deployment 단위로 본다 — 롤링 갱신 직후에는 종료 중인 옛 Pod(JVM 은 SIGTERM 에 143 으로 끝나 Error 로 보인다)가 잠시 남는다
  local notready="" total=0 d want ready updated
  for d in $(kc get deploy -l "app.kubernetes.io/instance=$RELEASE" -o name); do
    want=$(kc get "$d" -o jsonpath='{.spec.replicas}'); ready=$(kc get "$d" -o jsonpath='{.status.readyReplicas}'); updated=$(kc get "$d" -o jsonpath='{.status.updatedReplicas}')
    total=$((total + want))
    [ "${ready:-0}" = "$want" ] && [ "${updated:-0}" = "$want" ] || notready="$notready ${d#deployment.apps/}(ready=${ready:-0}/$want updated=${updated:-0})"
  done
  [ -z "$notready" ] && ok "Deployment 전부 Ready·최신: Pod $total개" || fail "준비 안 된 Deployment:$notready"
  local p; p=$(kc get deploy idem-hub -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="SPRING_PROFILES_ACTIVE")].value}')
  [ "$p" = "$profile" ] && ok "hub SPRING_PROFILES_ACTIVE=$p" || fail "hub 프로파일 기대 $profile, 실제 $p"
  # 1.0.1 M7: Service 는 앱 포트만 — 관리 포트 9090 은 밖으로 나가지 않는다
  local ports; ports=$(kc get svc idem-hub -o jsonpath='{.spec.ports[*].port}')
  [ "$ports" = "8083" ] && ok "Service idem-hub 포트 = $ports (관리 포트 9090 비노출)" || fail "Service idem-hub 포트: $ports"
  local hosts; hosts=$(kc get ingress idem -o jsonpath='{.spec.rules[*].host}')
  [ "$hosts" = "$HOST_GATE $HOST_HUB $HOST_CONSOLE" ] && ok "Ingress 호스트: $hosts (TLS $(kc get ingress idem -o jsonpath='{.spec.tls[0].secretName}'))" || fail "Ingress 호스트: $hosts"
}

mgmt_health() {   # 관리 포트(9090) health — Ready Pod 에 port-forward 로
  port_forward idem-hub 19093:9090
  local st=DOWN i
  for i in 1 2 3 4 5; do st=$(curl -sf http://127.0.0.1:19093/actuator/health | jq -r .status 2>/dev/null || echo DOWN); [ "$st" = UP ] && break; sleep 2; done
  kill_port_forwards
  [ "$st" = UP ] && ok "hub 관리 포트 /actuator/health = UP" || fail "hub 관리 포트 health: $st"
}

providers() {   # 본인확인 제공자 목록(JSON) — Ingress 경유. 롤링 갱신 직후 엔드포인트 교체 동안 잠시 실패할 수 있어 재시도한다
  local i code body
  for i in $(seq 1 15); do
    body=$(curl -s --cacert "$OUT_DIR/ca.crt" -w '\n%{http_code}' "$HUB_PUBLIC/api/v1/auth/providers" 2>/dev/null) || body=$'\n000'
    code=${body##*$'\n'}; body=${body%$'\n'*}
    if [ "$code" = 200 ]; then printf '%s' "$body"; return 0; fi
    sleep 2
  done
  fail "providers 조회 실패 ($HUB_PUBLIC → HTTP $code): $(printf '%s' "$body" | head -c 300)"
}

phase_install() {
  phase_begin "install — 비밀·TLS·helm install"
  need kubectl helm openssl curl jq
  load_images
  if ! kc get secret idem-app-secrets >/dev/null 2>&1; then
    ( umask 077; "$ROOT/scripts/lib/gen-install-env.sh" > "$OUT_DIR/install.env" )
    kc create secret generic idem-app-secrets --from-env-file="$OUT_DIR/install.env" >/dev/null
    ok "Secret idem-app-secrets — $(grep -c '=' "$OUT_DIR/install.env")개 키 (scripts/lib/gen-install-env.sh, $OUT_DIR/install.env 는 리허설 뒤 지운다)"
  else
    [ -f "$OUT_DIR/install.env" ] || fail "Secret idem-app-secrets 는 있는데 $OUT_DIR/install.env 가 없다 — 스모크가 관리자 비밀번호를 못 읽는다. Secret 을 지우고 다시"
    ok "Secret idem-app-secrets 재사용"
  fi
  if ! kc get secret idem-tls >/dev/null 2>&1; then
    ( cd "$OUT_DIR" && umask 077 \
      && openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 -subj "/CN=Idem rehearsal CA" -keyout ca.key -out ca.crt 2>/dev/null \
      && openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=$HOST_GATE" -keyout tls.key -out tls.csr 2>/dev/null \
      && printf 'subjectAltName=DNS:%s,DNS:%s,DNS:%s\n' "$HOST_GATE" "$HOST_HUB" "$HOST_CONSOLE" > san.cnf \
      && openssl x509 -req -in tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile san.cnf -out tls.crt 2>/dev/null )
    kc create secret tls idem-tls --cert="$OUT_DIR/tls.crt" --key="$OUT_DIR/tls.key" >/dev/null
    ok "TLS Secret idem-tls (자체 CA, SAN $HOST_GATE·$HOST_HUB·$HOST_CONSOLE)"
  fi
  write_values_env
  helm_apply install
  check_release 1 default
  mgmt_health
  phase_end install
}

# ── smoke ─────────────────────────────────────────────────────────────────
phase_smoke() {
  phase_begin "smoke — Ingress(TLS) + port-forward 로 설치본 스모크"
  need curl jq python3
  [ -f "$OUT_DIR/install.env" ] || fail "$OUT_DIR/install.env 가 없다 (install 단계를 먼저)"
  # 내부 서비스(registry·authz)와 관리 포트는 Ingress 밖에 있다 — port-forward
  port_forward idem-registry 18082:8082 19092:9090
  port_forward idem-authz    18086:8086 19096:9090
  port_forward idem-gate     19091:9090
  port_forward idem-hub      19093:9090
  local admin_pw reg_key
  admin_pw=$(grep '^IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD=' "$OUT_DIR/install.env" | cut -d= -f2-)
  reg_key=$(grep '^IDEM_REGISTRY_INTERNAL_API_KEY=' "$OUT_DIR/install.env" | cut -d= -f2-)
  # Ingress → gate·hub·console (TLS, 자체 CA). 리다이렉트·Secure 쿠키·KC_PROXY=edge 가 실제 조건으로 검증된다
  local code
  code=$(curl -s --cacert "$OUT_DIR/ca.crt" -o /dev/null -w '%{http_code}' "$CONSOLE_PUBLIC/")
  [ "$code" = 200 ] && ok "관리 콘솔 $CONSOLE_PUBLIC → 200" || fail "관리 콘솔이 $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://$HOST_GATE:$HTTP_PORT/realms/idem/.well-known/openid-configuration")
  case "$code" in 308|301|302) ok "HTTP → HTTPS 리다이렉트 ($code)";; *) echo "  ℹ HTTP 요청 응답 $code (컨트롤러 설정에 따라 다르다)";; esac
  CURL_CA_BUNDLE="$OUT_DIR/ca.crt" \
  HUB_URL="$HUB_PUBLIC" GATE_URL="$GATE_PUBLIC" ISSUER="$GATE_PUBLIC/realms/idem" \
  REGISTRY_URL=http://127.0.0.1:18082 IDEM_AUTHZ_URL=http://127.0.0.1:18086 \
  HUB_MGMT_URL=http://127.0.0.1:19093 GATE_MGMT_URL=http://127.0.0.1:19091 REGISTRY_MGMT_URL=http://127.0.0.1:19092 IDEM_AUTHZ_MGMT_URL=http://127.0.0.1:19096 \
  IDEM_HUB_ADMIN_BOOTSTRAP_PASSWORD="$admin_pw" IDEM_REGISTRY_INTERNAL_API_KEY="$reg_key" IDEM_EDITION=core SERVICE_CODE=K8S_RP \
    "$ROOT/scripts/ci/install-smoke.sh" > "$LOGS/smoke.txt" 2>&1 && rc=0 || rc=$?
  sed 's/^/  /' "$LOGS/smoke.txt"
  [ "$rc" -eq 0 ] || fail "설치본 스모크 실패"
  kill_port_forwards
  # 감사 WAL 볼륨(1.1 PR-2) 이 마운트되어 있는지
  local wal; wal=$(kc get deploy idem-hub -o jsonpath='{.spec.template.spec.containers[0].volumeMounts[?(@.name=="audit-wal")].mountPath}')
  [ -n "$wal" ] && ok "hub 감사 WAL 볼륨 $wal" || fail "hub 감사 WAL 볼륨 없음"
  phase_end smoke
}

# ── upgrade / rollback ────────────────────────────────────────────────────
phase_upgrade() {
  phase_begin "upgrade — 운영 전환 (prod 프로파일 · Mock off) 리비전 2"
  need curl jq
  helm_apply upgrade -f "$HERE/values-prod-switch.yaml"
  check_release 2 prod
  mgmt_health
  local prov; prov=$(providers)
  if echo "$prov" | jq -e '[.[].code] | index("MOCK")' >/dev/null; then fail "prod 전환 뒤에도 MOCK 제공자가 있다: $prov"; fi
  ok "MOCK 제공자 없음 (prod 프로파일 + IDEM_PLUGINS_MOCK_AUTH_ENABLED=false)"
  local iss; iss=$(curl -sf --cacert "$OUT_DIR/ca.crt" "$GATE_PUBLIC/realms/idem/.well-known/openid-configuration" | jq -r .issuer)
  [ "$iss" = "$GATE_PUBLIC/realms/idem" ] && ok "Discovery issuer = $iss" || fail "issuer: $iss"
  kc get job idem-db-init >/dev/null 2>&1 && echo "  ℹ pre-upgrade Job idem-db-init 이 아직 남아 있다" || ok "pre-upgrade 훅 Job 실행·정리 (hook-succeeded 삭제)"
  phase_end upgrade
}

phase_rollback() {
  phase_begin "rollback — 리비전 1 로 (Mock on · default 프로파일)"
  need curl jq
  helm -n "$NS" rollback "$RELEASE" 1 --wait --timeout "$HELM_TIMEOUT" > "$LOGS/helm-rollback.txt" 2>&1 || { cat "$LOGS/helm-rollback.txt" >&2; fail "helm rollback 실패"; }
  check_release 3 default
  mgmt_health
  local prov; prov=$(providers)
  echo "$prov" | jq -e '[.[].code] | index("MOCK")' >/dev/null && ok "MOCK 제공자 복귀 (리비전 1 의 설정)" || fail "롤백 뒤 MOCK 제공자가 없다: $prov"
  helm -n "$NS" history "$RELEASE" | sed 's/^/  /'
  phase_end rollback
}

# ── down ──────────────────────────────────────────────────────────────────
phase_down() {
  phase_begin "down — 제거"
  if helm -n "$NS" status "$RELEASE" >/dev/null 2>&1; then
    helm -n "$NS" uninstall "$RELEASE" --wait --timeout 5m > "$LOGS/helm-uninstall.txt" 2>&1 || { cat "$LOGS/helm-uninstall.txt" >&2; fail "helm uninstall 실패"; }
    for _ in $(seq 1 60); do [ "$(kc get pods -l app.kubernetes.io/part-of=idem --no-headers 2>/dev/null | wc -l)" = 0 ] && break; sleep 2; done
    [ "$(kc get pods -l app.kubernetes.io/part-of=idem --no-headers 2>/dev/null | wc -l)" = 0 ] && ok "릴리스 제거 — Idem Pod 0" || fail "Pod 가 남아 있다: $(kc get pods -l app.kubernetes.io/part-of=idem --no-headers)"
  fi
  rm -f "$OUT_DIR/install.env" "$OUT_DIR/ca.key" "$OUT_DIR/tls.key" "$OUT_DIR/tls.csr" "$OUT_DIR/san.cnf" "$OUT_DIR"/*.srl
  if [ "$CLUSTER" = kind ]; then
    if [ "$KEEP" = 1 ]; then ok "KEEP=1 — kind 클러스터 $KIND_CLUSTER 유지 (네임스페이스 $NS 삭제)"; kubectl delete ns "$NS" --wait=false >/dev/null 2>&1 || true
    else kind delete cluster --name "$KIND_CLUSTER" >/dev/null 2>&1; ok "kind 클러스터 $KIND_CLUSTER 삭제"; fi
  else
    kubectl delete ns "$NS" --wait=false >/dev/null 2>&1 || true; ok "네임스페이스 $NS 삭제 요청 (바깥 DB·Redis 는 그대로)"
  fi
  phase_end down
}

# ── main ──────────────────────────────────────────────────────────────────
PHASES=("$@"); [ ${#PHASES[@]} -eq 0 ] && PHASES=(up install smoke upgrade rollback down)
: > "$TIMINGS"
T0=$(date +%s)
echo "K8s 실배포 리허설 — 차트 $(grep '^version:' "$CHART/Chart.yaml" | awk '{print $2}') · 클러스터 $CLUSTER · 이미지 $IMAGES/$IMAGE_TAG · 네임스페이스 $NS · 단계: ${PHASES[*]}"
for p in "${PHASES[@]}"; do
  case "$p" in up|install|smoke|upgrade|rollback|down) "phase_$p";; *) fail "알 수 없는 단계: $p";; esac
done
TOTAL=$(( $(date +%s) - T0 ))
printf 'total\t%ss\n' "$TOTAL" >> "$TIMINGS"
say "결과 — 단계별 소요"
awk -F'\t' '{printf "  %-10s %s\n", $1, $2}' "$TIMINGS"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### K8s 실배포 리허설 (kind) — 차트 $(grep '^version:' "$CHART/Chart.yaml" | awk '{print $2}'), 이미지 $IMAGES/$IMAGE_TAG"; echo; echo '| 단계 | 소요 |'; echo '|---|---|'; awk -F'\t' '{print "| "$1" | "$2" |"}' "$TIMINGS"; } >> "$GITHUB_STEP_SUMMARY"
fi
