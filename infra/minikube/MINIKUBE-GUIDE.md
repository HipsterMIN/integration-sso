# OnePass — Minikube 로컬 K8s 시뮬레이션 가이드

> **목적**: 실제 운영 K8s 환경(NHN Cloud / AWS EKS)을 로컬에서 재현하여
> Deployment / ConfigMap / Secret / HPA / Rolling Update 등 K8s 동작을 검증한다.

---

## 목차

1. [아키텍처 개요](#1-아키텍처-개요)
2. [OS별 빠른 시작](#2-os별-빠른-시작)
3. [디렉토리 구조](#3-디렉토리-구조)
4. [설정 수정 방법](#4-설정-수정-방법)
5. [시뮬레이션 시나리오](#5-시뮬레이션-시나리오)
6. [트러블슈팅](#6-트러블슈팅)
7. [실제 운영 환경 배포 시 주의사항](#7-실제-운영-환경-배포-시-주의사항)
8. [참고 명령어 치트시트](#8-참고-명령어-치트시트)

---

## 1. 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────┐
│                         로컬 호스트                              │
│                                                                 │
│  ┌───────────────────────────┐   ┌───────────────────────────┐  │
│  │   Minikube (K8s 클러스터) │   │  Docker Compose (인프라)  │  │
│  │   profile: onepass        │   │                           │  │
│  │                           │   │  postgres  :5432          │  │
│  │  namespace: smes          │   │  redis     :6379          │  │
│  │  ┌─────────────────────┐  │   │  kafka     :9092          │  │
│  │  │  ido Pod (x1~2)     │──┼───│  prometheus:9090          │  │
│  │  │  Spring Boot 8083   │  │   │  grafana   :3000          │  │
│  │  └─────────────────────┘  │   │  loki      :3100          │  │
│  │                           │   └───────────────────────────┘  │
│  │  ConfigMap / Secret       │                                  │
│  │  HPA / PDB                │   포트 포워딩                     │
│  │  Helm Release: ido        │   kubectl port-forward           │
│  │                           │   localhost:8083 ──→ ido-service  │
│  └───────────────────────────┘                                  │
│                                                                 │
│  접속: http://localhost:8083/actuator/health                     │
└─────────────────────────────────────────────────────────────────┘
```

### 핵심 설계 원칙

| 구성 요소 | 실행 위치 | 이유 |
|-----------|-----------|------|
| `ido` (Spring Boot) | **Minikube K8s** | K8s 동작 시뮬레이션 대상 |
| `postgres`, `redis`, `kafka` | **Docker Compose** | 인프라 재사용, 볼륨 관리 용이 |
| `prometheus`, `grafana` | **Docker Compose** | 모니터링 스택 통합 유지 |
| `host.minikube.internal` | 자동 DNS | Minikube → 호스트 Docker Compose 접근 |

---

## 2. OS별 빠른 시작

### 사전 요구사항 요약

| 도구 | macOS | Windows | Linux |
|------|-------|---------|-------|
| Docker | Docker Desktop | Docker Desktop (WSL2) | Docker Engine |
| minikube | `brew install minikube` | `winget install Kubernetes.minikube` | 스크립트 자동 설치 |
| kubectl | `brew install kubectl` | `winget install Kubernetes.kubectl` | 스크립트 자동 설치 |
| helm | `brew install helm` | `winget install Helm.Helm` | 스크립트 자동 설치 |
| Java 17+ | 프로젝트 기존 환경 | 프로젝트 기존 환경 | 프로젝트 기존 환경 |

---

### macOS

```bash
# 1. 필요 도구 설치 (Homebrew)
brew install minikube kubectl helm

# 2. Docker Desktop 실행 확인
docker info

# 3. 프로젝트 루트에서 실행
cd ~/Projects/integration-sso
bash infra/minikube/scripts/setup-mac.sh setup

# 4. 상태 확인
bash infra/minikube/scripts/setup-mac.sh status

# 5. 로그 확인
bash infra/minikube/scripts/setup-mac.sh logs

# 6. 재배포 (코드 변경 후)
bash infra/minikube/scripts/setup-mac.sh deploy

# 7. 종료
bash infra/minikube/scripts/setup-mac.sh teardown
```

---

### Windows (PowerShell)

```powershell
# 1. 실행 정책 설정 (최초 1회)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser

# 2. 필요 도구 설치
winget install Kubernetes.minikube
winget install Kubernetes.kubectl
winget install Helm.Helm

# 3. Docker Desktop 실행 확인 (WSL2 백엔드 설정 필요)
docker info

# 4. 프로젝트 루트에서 실행
cd C:\Users\User\Projects\integration-sso
.\infra\minikube\scripts\setup-windows.ps1 -Mode setup

# 5. 상태 확인
.\infra\minikube\scripts\setup-windows.ps1 -Mode status

# 6. 로그 확인
.\infra\minikube\scripts\setup-windows.ps1 -Mode logs

# 7. 재배포
.\infra\minikube\scripts\setup-windows.ps1 -Mode deploy

# 8. 종료
.\infra\minikube\scripts\setup-windows.ps1 -Mode teardown
```

> **Windows 주의사항**:
> - Docker Desktop → Settings → General → "Use WSL 2 based engine" 활성화 필수
> - Hyper-V 또는 WSL2 중 하나 활성화 필요
> - `gradlew.bat`이 없으면 WSL2에서 `bash gradlew`로 폴백

---

### Linux (Ubuntu/Debian/RHEL)

```bash
# 1. 도구 자동 설치 (최초 1회)
bash infra/minikube/scripts/setup-linux.sh install

# docker 그룹 적용 (재로그인 없이)
newgrp docker

# 2. 전체 환경 기동
bash infra/minikube/scripts/setup-linux.sh setup

# 3. 상태 확인
bash infra/minikube/scripts/setup-linux.sh status

# 4. 정리
bash infra/minikube/scripts/setup-linux.sh teardown
```

> **Linux 주의사항**:
> - `host.minikube.internal`이 지원되지 않는 구버전 minikube에서는
>   Docker 게이트웨이 IP(`172.17.0.1`)로 자동 폴백됨
> - Docker Compose의 포트가 `0.0.0.0`에 바인딩되어 있어야 Minikube에서 접근 가능
>   (`docker-compose.yml`의 포트는 이미 정상 설정됨)

---

## 3. 디렉토리 구조

```
infra/
├── minikube/
│   ├── MINIKUBE-GUIDE.md          # 이 파일
│   ├── scripts/
│   │   ├── setup-mac.sh           # macOS 전용 스크립트
│   │   ├── setup-windows.ps1      # Windows PowerShell 스크립트
│   │   └── setup-linux.sh         # Linux 전용 스크립트
│   └── overlays/
│       └── local/
│           └── ido-configmap-local.yml  # 로컬 전용 ConfigMap 오버레이
│
├── k8s/
│   ├── deployments/
│   │   └── ido-deployment.yml     # K8s Deployment + Service + SA
│   ├── configmaps/
│   │   ├── ido-configmap.yml      # 운영 ConfigMap (Phase 1)
│   │   ├── ido-configmap-phase*.yml  # 각 Phase별 ConfigMap
│   │   └── kms-config.yml         # KMS 설정
│   └── secrets/
│       └── ido-secrets-template.yml  # Secret 구조 템플릿
│
└── helm/
    └── ido/
        ├── Chart.yaml
        ├── values.yaml            # 기본값 (개발/스테이징)
        ├── values-prod.yaml       # 운영 오버라이드
        └── templates/             # K8s 리소스 템플릿
```

---

## 4. 설정 수정 방법

### 4.1 환경변수 변경 (ConfigMap)

로컬 환경에서는 `overlays/local/ido-configmap-local.yml`을 수정하고
`deploy` 모드로 재배포한다.

```bash
# 1. ConfigMap 수정
vim infra/minikube/overlays/local/ido-configmap-local.yml

# 2. 반영 (Pod 재시작 포함)
kubectl apply -f infra/minikube/overlays/local/ido-configmap-local.yml
kubectl rollout restart deployment/ido -n smes

# 3. 반영 확인
kubectl rollout status deployment/ido -n smes
```

### 4.2 Secret 값 변경

```bash
# 특정 키만 업데이트
kubectl create secret generic ido-secrets \
  --namespace=smes \
  --from-literal=DB_PASSWORD=new-password \
  --save-config \
  --dry-run=client -o yaml | kubectl apply -f -

# Pod 재시작
kubectl rollout restart deployment/ido -n smes
```

### 4.3 이미지 태그 변경 (재빌드 없이)

```bash
# 특정 태그로 전환
eval $(minikube -p onepass docker-env)
docker tag smes/ido:local smes/ido:v2.3.1
helm upgrade ido infra/helm/ido \
  --namespace=smes \
  --set image.tag=v2.3.1 \
  --reuse-values
```

### 4.4 Feature Flag 변경

```bash
# Phase 2a 활성화
kubectl apply -f infra/k8s/configmaps/ido-configmap-phase2a.yml
kubectl rollout restart deployment/ido -n smes

# 또는 Helm으로 직접 설정
helm upgrade ido infra/helm/ido \
  --namespace=smes \
  --set featureFlags.provisioning=true \
  --set featureFlags.provisioningDryRun=true \
  --reuse-values
```

### 4.5 Replicas 조정

```bash
# replicas 2로 증가 (Rolling Update 테스트)
kubectl scale deployment ido -n smes --replicas=2

# HPA 활성화 (metrics-server 애드온 필요)
helm upgrade ido infra/helm/ido \
  --namespace=smes \
  --set autoscaling.enabled=true \
  --set autoscaling.minReplicas=1 \
  --set autoscaling.maxReplicas=3 \
  --reuse-values
```

---

## 5. 시뮬레이션 시나리오

### 5.1 Rolling Update 시뮬레이션

```bash
# 1. 새 이미지 빌드
eval $(minikube -p onepass docker-env)
./gradlew :ido:bootJar -x test
docker build -f ido/Dockerfile -t smes/ido:v2-new .

# 2. Rolling Update 시작 (maxUnavailable=0, maxSurge=1 설정됨)
kubectl set image deployment/ido ido=smes/ido:v2-new -n smes

# 3. 진행 상황 실시간 관찰
kubectl rollout status deployment/ido -n smes -w

# 4. 롤백 (문제 발생 시)
kubectl rollout undo deployment/ido -n smes

# 5. 히스토리 확인
kubectl rollout history deployment/ido -n smes
```

### 5.2 ConfigMap 핫리로드 시뮬레이션

```bash
# Feature Flag 변경 → Pod 재시작 없이 확인
kubectl edit configmap ido-config -n smes
# IDO_RATE_LIMIT_ENABLED 값을 false로 변경 후 저장

# ConfigMap 변경은 Pod 재시작 필요 (Spring Boot는 자동 감지 미지원)
kubectl rollout restart deployment/ido -n smes
kubectl rollout status deployment/ido -n smes
```

### 5.3 HPA (오토스케일링) 시뮬레이션

```bash
# metrics-server 확인
kubectl top pods -n smes

# HPA 생성
kubectl autoscale deployment ido -n smes \
  --cpu-percent=50 --min=1 --max=4

# 부하 발생 (k6 사용)
k6 run infra/k6/load-test.js

# HPA 동작 확인
kubectl get hpa -n smes -w
```

### 5.4 Pod 장애 복구 시뮬레이션

```bash
# Pod 강제 종료 → K8s 자동 재생성 확인
kubectl delete pod -l app=ido -n smes

# 재생성 과정 관찰
kubectl get pods -n smes -w

# readinessProbe 실패 시나리오
# (actuator health를 임시로 DOWN으로 만들면 트래픽에서 제외됨)
```

### 5.5 Secret 로테이션 시뮬레이션

```bash
# 새 Secret 값으로 교체
kubectl create secret generic ido-secrets \
  --namespace=smes \
  --from-literal=IDO_HANDOFF_AES_KEY=$(openssl rand -base64 32) \
  --from-literal=IDO_HANDOFF_HMAC_KEY=$(openssl rand -base64 32) \
  --save-config --dry-run=client -o yaml | kubectl apply -f -

# 무중단 재시작 (rollingUpdate 전략 사용)
kubectl rollout restart deployment/ido -n smes
```

### 5.6 리소스 제한 OOMKill 시뮬레이션

```bash
# 메모리 제한을 매우 낮게 설정
helm upgrade ido infra/helm/ido \
  --namespace=smes \
  --set resources.limits.memory=256Mi \
  --reuse-values

# Pod 상태에서 OOMKilled 관찰
kubectl get pods -n smes -w
kubectl describe pod -l app=ido -n smes | grep -A5 "OOMKilled"
```

---

## 6. 트러블슈팅

### 6.1 `ImagePullBackOff` — 이미지를 찾을 수 없음

**증상**:
```
NAME         READY   STATUS             RESTARTS
ido-xxx      0/1     ImagePullBackOff   0
```

**원인 및 해결**:
```bash
# 원인 1: Minikube Docker 환경 밖에서 이미지를 빌드함
# 해결: Minikube Docker 환경으로 전환 후 다시 빌드
eval $(minikube -p onepass docker-env)
docker build -f ido/Dockerfile -t smes/ido:local .

# 원인 2: imagePullPolicy가 Always로 설정됨
# 해결: Never로 변경
helm upgrade ido infra/helm/ido \
  --namespace=smes \
  --set image.pullPolicy=Never \
  --reuse-values

# 확인
kubectl describe pod -l app=ido -n smes | grep -A5 "Events"
```

---

### 6.2 Pod가 `CrashLoopBackOff` 상태

**증상**:
```
NAME         READY   STATUS             RESTARTS
ido-xxx      0/1     CrashLoopBackOff   5
```

**진단**:
```bash
# 1. 로그 확인 (종료된 Pod 포함)
kubectl logs -l app=ido -n smes --previous
kubectl logs -l app=ido -n smes

# 2. 이벤트 확인
kubectl describe pod -l app=ido -n smes | tail -30

# 3. 환경변수 확인
kubectl exec -it deployment/ido -n smes -- env | grep -E "DB_|REDIS_|KAFKA_"
```

**자주 발생하는 원인**:

| 원인 | 로그 패턴 | 해결 방법 |
|------|-----------|-----------|
| DB 연결 실패 | `Connection refused: postgres` | `DB_HOST=host.minikube.internal` 확인 |
| Redis 연결 실패 | `Connection refused: redis` | `REDIS_HOST` 확인 |
| Kafka 연결 실패 | `LEADER_NOT_AVAILABLE` | Docker Compose Kafka 기동 확인 |
| Secret 누락 | `Required key 'XXX' not found` | Secret 재생성 |
| OOM | `java.lang.OutOfMemoryError` | `resources.limits.memory` 증가 |
| Flyway 실패 | `FlywayException` | DB 스키마 확인, 볼륨 초기화 |

---

### 6.3 `host.minikube.internal` 접근 불가 (Linux)

**증상**: Pod 로그에서 `Connection refused: host.minikube.internal`

**진단**:
```bash
# Minikube 내부에서 DNS 해상도 테스트
minikube -p onepass ssh "getent hosts host.minikube.internal"

# 수동으로 게이트웨이 IP 확인
ip addr show docker0 | grep "inet "
# 또는
docker network inspect bridge | grep Gateway
```

**해결**:
```bash
# 방법 1: Secret에서 IP를 직접 지정
GATEWAY_IP=$(ip addr show docker0 | grep "inet " | awk '{split($2,a,"/"); print a[1]}')
kubectl create secret generic ido-secrets \
  --namespace=smes \
  --from-literal=DB_HOST=${GATEWAY_IP} \
  --from-literal=REDIS_HOST=${GATEWAY_IP} \
  --from-literal=KAFKA_SERVERS=${GATEWAY_IP}:9092 \
  --save-config --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart deployment/ido -n smes

# 방법 2: ConfigMap에서 고정 IP 사용 (overlays/local/ido-configmap-local.yml 수정)
# QIM_BASE_URL: "http://172.17.0.1:8082"
```

---

### 6.4 포트 포워딩이 끊김

**증상**: `curl http://localhost:8083` 타임아웃

**해결**:
```bash
# 포트 포워딩 재시작
pkill -f "kubectl port-forward" 2>/dev/null; sleep 1
kubectl port-forward service/ido-service 8083:8083 -n smes &

# 또는 스크립트로 재기동
bash infra/minikube/scripts/setup-mac.sh deploy  # macOS
```

> **팁**: 포트 포워딩은 임시 연결입니다. 장시간 테스트 시 `minikube tunnel` 또는
> `minikube service ido-service -n smes --url` 사용을 권장합니다.

---

### 6.5 Minikube 디스크/메모리 부족

**증상**: Pod가 `Pending` 상태, 노드 `NotReady`

```bash
# 리소스 확인
kubectl describe node | grep -A10 "Allocated resources"
minikube -p onepass ssh "df -h"

# 미사용 이미지 정리
eval $(minikube -p onepass docker-env)
docker system prune -af

# Minikube 재생성 (메모리 증가)
minikube delete -p onepass
minikube start -p onepass --driver=docker --memory=6144 --cpus=4
```

---

### 6.6 Helm 배포 실패 — `UPGRADE FAILED`

```bash
# 상태 확인
helm status ido -n smes
helm history ido -n smes

# 롤백
helm rollback ido -n smes

# 강제 재설치
helm uninstall ido -n smes
helm install ido infra/helm/ido \
  --namespace=smes \
  --set image.tag=local \
  --set image.pullPolicy=Never \
  --set replicaCount=1
```

---

### 6.7 Windows: `gradlew` 빌드 실패

**증상**: `.\gradlew.bat` 실행 오류

**해결**:
```powershell
# Java 버전 확인
java -version  # 17+ 필요

# JAVA_HOME 설정
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# WSL2에서 실행 (대안)
wsl bash -c "cd /mnt/c/Users/User/Projects/integration-sso && ./gradlew :ido:bootJar -x test"

# WSL2에서 빌드 후 이미지를 Minikube에 로드
wsl bash -c "eval \$(minikube -p onepass docker-env) && docker build -f ido/Dockerfile -t smes/ido:local ."
```

---

### 6.8 macOS Apple Silicon (M1/M2/M3) 이슈

**증상**: 이미지 아키텍처 불일치 (`exec format error`)

```bash
# arm64 플랫폼으로 빌드
docker build \
  --platform linux/amd64 \
  -f ido/Dockerfile \
  -t smes/ido:local .

# 또는 Dockerfile에 플랫폼 명시
# FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
```

---

## 7. 실제 운영 환경 배포 시 주의사항

### 7.1 Secret 관리 — 가장 중요

**로컬 vs 운영 차이점**:

| 항목 | 로컬 Minikube | 운영 (NHN Cloud / K8s) |
|------|--------------|------------------------|
| Secret 저장 | `kubectl create secret` (평문) | Vault / ExternalSecret Operator |
| 암호화 키 | 더미값 (AAAA...) | 실제 랜덤 32바이트 |
| DB 비밀번호 | `onepass` | 강력한 랜덤값 |
| KMS appKey | `dummy-local` | NHN SKM 실제 Appkey |

**운영 배포 전 필수 체크리스트**:
```bash
# 절대 운영에 들어가면 안 되는 값들 확인
grep -r "dummy\|AAAAAAA\|poc-\|change-me\|local" \
  infra/k8s/secrets/ infra/helm/ido/values-prod.yaml

# Sealed Secrets 사용 예시 (kubeseal 설치 필요)
kubeseal --cert=seal.pem -o yaml \
  < infra/k8s/secrets/ido-secrets-template.yml \
  > infra/k8s/secrets/ido-sealed-secrets.yml
```

---

### 7.2 이미지 레지스트리

**로컬**: `imagePullPolicy: Never` (로컬 이미지 직접 사용)

**운영 배포 전**:
```bash
# 1. 이미지를 레지스트리에 푸시
docker tag smes/ido:2.3.0 registry.example.com/smes/ido:2.3.0
docker push registry.example.com/smes/ido:2.3.0

# 2. values-prod.yaml 수정
# image:
#   repository: registry.example.com/smes/ido
#   tag: "2.3.0"
#   pullPolicy: IfNotPresent

# 3. imagePullSecrets 설정 (프라이빗 레지스트리)
kubectl create secret docker-registry harbor-secret \
  --docker-server=registry.example.com \
  --docker-username=robot\$ido \
  --docker-password=TOKEN \
  -n smes
```

---

### 7.3 리소스 설정 차이

**로컬 Minikube (설정된 값)**:
```yaml
resources:
  requests: { memory: 512Mi, cpu: 250m }
  limits:   { memory: 1Gi,   cpu: 1000m }
```

**운영 환경 권장값** (`values-prod.yaml` 참고):
- 운영 트래픽에 맞게 `requests`/`limits` 조정 필수
- JVM 힙이 limits의 75%를 사용하므로 메모리 여유분 확보
- CPU throttling 방지를 위해 limits.cpu를 requests의 4배 이하로 설정

---

### 7.4 네트워크 정책

**로컬**: 네트워크 정책 없음 (Pod 간 자유 통신)

**운영 적용 시 추가 필요**:
```yaml
# 예시: ido만 postgres에 접근 허용
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ido-to-postgres
  namespace: smes
spec:
  podSelector:
    matchLabels:
      app: ido
  policyTypes:
    - Egress
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres
      ports:
        - protocol: TCP
          port: 5432
```

---

### 7.5 topologySpreadConstraints

**로컬**: `topologySpread.enabled=false` (단일 노드이므로 비활성)

**운영**: `topologySpread.enabled=true` 필수
- Multi-AZ 배포에서 Zone당 최대 1개 Pod skew 허용
- `whenUnsatisfiable: ScheduleAnyway`로 설정되어 있어 Zone 부족 시에도 스케줄됨
- 실제 3-AZ 환경에서는 `replicas: 3` 이상 권장

---

### 7.6 Flyway 마이그레이션 — 운영 배포 시 주의

**문제**: 운영 DB에서 Flyway가 이미 적용된 마이그레이션을 다시 실행하려 할 때 오류 발생

**예방**:
```yaml
# application-prod.yml
spring:
  flyway:
    validate-on-migrate: true    # 운영: 체크섬 검증 활성화
    repair-on-migrate: false     # 운영: 자동 repair 비활성화
    baseline-on-migrate: false   # 운영: 기존 DB에 새로 적용 금지
    out-of-order: false          # 운영: 순서 외 마이그레이션 금지
```

**배포 전 검증**:
```bash
# 마이그레이션 드라이런
./gradlew :ido:flywayInfo -Dflyway.url=jdbc:postgresql://prod-db:5432/onepass
```

---

### 7.7 HPA (오토스케일링) 운영 적용 시

**로컬 확인 후 운영 적용**:
```bash
# metrics-server가 운영 클러스터에 설치되어 있는지 확인
kubectl top nodes
kubectl top pods -n smes

# CPU 기반 HPA (현재 설정)
# 트래픽 패턴에 따라 targetCPUUtilizationPercentage 조정 필요
# 보통 70% 이상에서 스케일아웃
```

**Kafka Consumer 고려사항**:
- `ido`는 Kafka Consumer(`ido-handoff-consumer`)를 포함
- Pod 수 증가 시 Consumer Group Rebalance 발생 → 일시적 메시지 처리 지연
- `group.initial.rebalance.delay.ms` 설정 확인 (현재 100ms)

---

### 7.8 PodDisruptionBudget 운영 영향

현재 설정: `minAvailable: 1`
- 배포/노드 유지보수 중에도 최소 1개 Pod 유지 보장
- `replicas: 1`인 경우 배포 시 PDB가 Pod 종료를 차단할 수 있음
- 운영: `replicas: 2` 이상 유지 권장

```bash
# PDB 상태 확인
kubectl get pdb -n smes
kubectl describe pdb ido-pdb -n smes
```

---

### 7.9 OpenTelemetry 운영 설정

**로컬**: OTel 비활성화 (`overlays/local/ido-configmap-local.yml`)

**운영**:
```yaml
# ido-configmap.yml
OTLP_ENDPOINT: "http://otel-collector:4318/v1/traces"
TRACING_SAMPLING_PROBABILITY: "0.1"  # 운영: 10% 샘플링 (100%는 성능 영향)
IDO_TRACING_AUTH_ASPECT_ENABLED: "true"
```

Jaeger/Grafana Tempo가 K8s 클러스터에 배포되어 있어야 함.

---

## 8. 참고 명령어 치트시트

### Minikube

```bash
# 프로필 목록
minikube profile list

# 특정 프로필로 컨텍스트 전환
kubectl config use-context onepass

# Minikube 대시보드 (웹 UI)
minikube dashboard -p onepass

# Minikube SSH 접속
minikube -p onepass ssh

# 내부 서비스 URL 확인
minikube service ido-service -n smes --url -p onepass

# 애드온 목록
minikube addons list -p onepass
```

### kubectl

```bash
# Pod 상태 실시간 모니터링
watch kubectl get pods -n smes

# Pod 내부 접속
kubectl exec -it deployment/ido -n smes -- /bin/sh

# 환경변수 확인
kubectl exec deployment/ido -n smes -- env | sort

# ConfigMap 내용 확인
kubectl get configmap ido-config -n smes -o yaml

# Secret 내용 확인 (base64 디코딩)
kubectl get secret ido-secrets -n smes -o jsonpath='{.data.DB_HOST}' | base64 -d

# 이벤트 확인 (오류 분석)
kubectl get events -n smes --sort-by='.lastTimestamp'

# 리소스 사용량
kubectl top pods -n smes
kubectl top nodes

# Deployment 상태
kubectl describe deployment ido -n smes

# 로그 (이전 컨테이너 포함)
kubectl logs -l app=ido -n smes --previous --tail=100

# 멀티 컨테이너 로그
kubectl logs -l app=ido -n smes --all-containers=true -f
```

### Helm

```bash
# 배포된 값 확인
helm get values ido -n smes

# 렌더링된 템플릿 확인 (배포 전 검증)
helm template ido infra/helm/ido \
  --namespace=smes \
  --set image.tag=local \
  --set replicaCount=1

# 차이 확인 (helm-diff 플러그인 필요)
helm diff upgrade ido infra/helm/ido \
  --namespace=smes \
  --set image.tag=v2-new \
  --reuse-values

# 릴리스 히스토리
helm history ido -n smes

# 롤백
helm rollback ido 1 -n smes  # revision 1로 롤백
```

### 포트 포워딩

```bash
# ido API
kubectl port-forward service/ido-service 8083:8083 -n smes &

# pgAdmin (로컬 DB 관리)
kubectl port-forward service/pgadmin 5050:80 -n smes &

# Grafana
kubectl port-forward service/grafana 3000:3000 -n smes &

# 모든 포트 포워딩 종료
pkill -f "kubectl port-forward"
```

---

## 관련 문서

- [infra/k8s/deployments/ido-deployment.yml](../k8s/deployments/ido-deployment.yml) — Deployment 정의
- [infra/helm/ido/values.yaml](../helm/ido/values.yaml) — Helm 기본값
- [infra/helm/ido/values-prod.yaml](../helm/ido/values-prod.yaml) — 운영 오버라이드
- [infra/k8s/secrets/ido-secrets-template.yml](../k8s/secrets/ido-secrets-template.yml) — Secret 템플릿
- [infra/docker/docker-compose.yml](../docker/docker-compose.yml) — 로컬 인프라

---

*최종 업데이트: 2026-05-20 | OnePass DevOps*
