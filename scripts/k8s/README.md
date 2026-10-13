# K8s 실배포 리허설 (`scripts/k8s/rehearsal.sh`)

Helm 차트 `infra/helm/idem` 을 **실제 클러스터**에 올려 설치 → 검증 → 운영 전환 업그레이드 → 롤백 → 제거까지 한 번에 돌린다 (1.1 PR-6, `docs/post-1.0-plan.md` §5 #5). CI `k8s-rehearsal` 잡이 kind 클러스터에서 같은 스크립트를 돈다 — 차트·스크립트·워크플로가 바뀐 PR 과 main push 마다.

| 단계 | 하는 일 | 확인 |
|---|---|---|
| `up` | kind 클러스터(호스트 80/443 → 노드) + ingress-nginx + 네임스페이스 + `idem-db-secret` + 리허설용 PostgreSQL 16·Redis 7(`rehearsal/infra.yaml`, emptyDir) | Pod Ready |
| `install` | 이미지 반입 → 비밀 한 벌(`scripts/lib/gen-install-env.sh` → Secret `idem-app-secrets`) → 자체 CA 로 TLS Secret `idem-tls` → `helm upgrade --install --wait` (`rehearsal/values-rehearsal.yaml` + 생성된 `values-env.yaml`) | 리비전 1 deployed · Pod 전부 Ready · hub 프로파일 default · Service 에 관리 포트 없음(M7) · Ingress 호스트 3개(TLS) · 관리 포트 health UP |
| `smoke` | TLS Ingress(gate·hub·console) + port-forward(registry·authz·관리 포트)로 `scripts/ci/install-smoke.sh` ①~⑧ (`*_MGMT_URL`) | 관리 콘솔 200 · HTTP→HTTPS 리다이렉트 · 스모크 통과 · 감사 WAL 볼륨 |
| `upgrade` | `rehearsal/values-prod-switch.yaml`(prod 프로파일 · Mock off) 로 `helm upgrade --wait` — 설치 매뉴얼 §5 "확인 뒤 되돌린다" 를 그대로 | 리비전 2 · hub 프로파일 prod · MOCK 제공자 없음 · Discovery issuer · pre-upgrade 훅 Job 정리 |
| `rollback` | `helm rollback 1 --wait` | 리비전 3 · 프로파일 default · MOCK 제공자 복귀 |
| `down` | `helm uninstall --wait` → Idem Pod 0 → 네임스페이스·kind 클러스터 삭제, 비밀 파일 삭제 | |

```bash
# 로컬 (Docker · kind · kubectl · helm 3 · openssl · curl · jq · python3). 코어 이미지 5종을 먼저 만든다
for m in idem-gate idem-authz idem-console-admin; do DOCKER_BUILDKIT=1 docker build -t $m:pr -f $m/Dockerfile .; done
for m in idem-hub idem-registry; do DOCKER_BUILDKIT=1 docker build --build-arg IDEM_EDITION=core -t $m:pr-core -f $m/Dockerfile .; done
scripts/k8s/rehearsal.sh                       # 전부 (80/443 을 못 쓰면 HTTP_PORT=8080 HTTPS_PORT=8443)
scripts/k8s/rehearsal.sh up install smoke      # 일부만. KEEP=1 … down 이면 kind 클러스터를 남긴다

# GHCR 이미지로 (main 에 push 된 :<sha> 또는 :latest)
IMAGES=registry IMAGE_REGISTRY=ghcr.io/hipstermin IMAGE_TAG=latest REGISTRY_USER=<github id> REGISTRY_TOKEN=<PAT read:packages> scripts/k8s/rehearsal.sh

# 운영기관 클러스터 (Ingress 컨트롤러가 있고 HOST_* 가 그 주소로 풀린다. 바깥 PG·Redis 를 쓰려면 values-rehearsal.yaml 의 infra.* 를 바꾼 사본을 HERE 에 두거나 up 을 건너뛴다)
CLUSTER=existing IMAGES=registry IMAGE_TAG=1.1.2 HOST_GATE=sso.example.org HOST_HUB=hub.example.org HOST_CONSOLE=console.example.org ADD_HOSTS=0 scripts/k8s/rehearsal.sh
```

- 결과: 단계별 소요가 `OUT_DIR/timings.txt`(기본 `/tmp/idem-rehearsal`) 와 CI 잡 요약에 남는다. 실패하면 Pod·이벤트·describe·로그를 `OUT_DIR/logs/` 에 모은다(CI 는 아티팩트 `k8s-rehearsal-logs`).
- 비밀: `install.env`(관리자 초기 비밀번호·내부 키)는 `OUT_DIR` 에 `0600` 으로 있다가 `down` 이 지운다. 로그·아티팩트에는 들어가지 않는다.
- 운영 기본값과 다른 점은 `rehearsal/values-rehearsal.yaml` 에 이유와 함께 적혀 있다(복제본 1, 자원 축소, startupProbe 300s, default 프로파일 + Mock — 스모크용, NetworkPolicy off — kindnet 은 집행하지 않는다). KR 에디션은 벤더 SDK 이미지가 있는 환경에서만 — 이 스크립트는 코어만 다룬다.
- 스모크의 첫 관리자 로그인이 비밀번호를 바꾸므로 `smoke` 는 설치 1회당 한 번만 돈다. 다시 돌리려면 `down` 뒤 처음부터.

## 결과 기록

| 실행 | 환경 | up | install | smoke | upgrade | rollback | down | 합계 | 비고 |
|---|---|---|---|---|---|---|---|---|---|
| 2026-09-29 PR #256 (CI 첫 통과) | GitHub 호스팅 러너 4 vCPU/16 GB · kind v0.31 · K8s 1.35 · Helm 3.22 · 코어 5종 `idem-*:pr` | 65s | 84s | 10s | 45s | 40s | 13s | 4m17s | 앞선 3회 실패: ① Keycloak auto-build OOMKilled(1536Mi → 2Gi, 차트 수리) ② 릴리스 검사가 종료 중인 옛 Pod 를 셈(Deployment 단위로) ③ port-forward·providers 조회가 롤링 갱신 직후 옛 Pod·엔드포인트 교체 창에 걸림(Ready Pod 선택·재시도) |
| (미실시) 기관 클러스터 `CLUSTER=existing` | 운영 Ingress · 바깥 PostgreSQL·Redis | | | | | | | | 첫 배포 때 돌리고 여기와 설치 매뉴얼 §8 에 적는다 |
