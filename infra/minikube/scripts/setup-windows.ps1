# =============================================================================
# setup-windows.ps1 — OnePass Minikube 로컬 K8s 시뮬레이션 (Windows)
#
# 사전 요구사항:
#   - Docker Desktop for Windows (WSL2 백엔드, 실행 중)
#   - winget 또는 Chocolatey
#   - PowerShell 7+ 권장 (Windows PowerShell 5.1도 동작)
#   - 프로젝트 루트에서 실행:
#       .\infra\minikube\scripts\setup-windows.ps1 [-Mode setup]
#
# 실행 모드:
#   setup      : Minikube 초기화 + 인프라 기동 + ido 배포 (기본값)
#   infra-only : Docker Compose 인프라만 기동
#   deploy     : ido 이미지 빌드 + K8s 배포만
#   teardown   : 전체 정리
#   status     : 현재 상태 출력
#   logs       : ido Pod 로그 스트리밍
#
# 사용법:
#   .\infra\minikube\scripts\setup-windows.ps1
#   .\infra\minikube\scripts\setup-windows.ps1 -Mode deploy
#   .\infra\minikube\scripts\setup-windows.ps1 -Mode teardown
#
# 실행 정책 오류 시:
#   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
# =============================================================================

param(
    [ValidateSet("setup","infra-only","deploy","teardown","status","logs")]
    [string]$Mode = "setup"
)

$ErrorActionPreference = "Stop"

# ── 색상 출력 ──────────────────────────────────────────────────────────────────
function Write-Info    { Write-Host "[INFO]  $args" -ForegroundColor Cyan }
function Write-Success { Write-Host "[OK]    $args" -ForegroundColor Green }
function Write-Warn    { Write-Host "[WARN]  $args" -ForegroundColor Yellow }
function Write-Err     { Write-Host "[ERROR] $args" -ForegroundColor Red }
function Write-Step    { Write-Host "`n━━━ $args ━━━" -ForegroundColor Blue }

# ── 설정값 ─────────────────────────────────────────────────────────────────────
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = (Resolve-Path "$ScriptDir\..\..\..").Path
$InfraDir    = Join-Path $ProjectRoot "infra"
$DockerDir   = Join-Path $InfraDir "docker"
$K8sDir      = Join-Path $InfraDir "k8s"
$HelmDir     = Join-Path $InfraDir "helm"
$OverlayDir  = Join-Path $InfraDir "minikube\overlays\local"

$MinikubeProfile    = "onepass"
$Namespace          = "smes"
$IdoImage           = "smes/ido"
$IdoTag             = "local"
$MinikubeMemory     = "4096"
$MinikubeCpus       = "2"
$MinikubeDisk       = "20g"
$MinikubeK8sVersion = "v1.29.0"

# ── 유틸 함수 ──────────────────────────────────────────────────────────────────
function Test-Command {
    param([string]$Cmd, [string]$InstallHint)
    if (-not (Get-Command $Cmd -ErrorAction SilentlyContinue)) {
        Write-Err "$Cmd 이(가) 설치되어 있지 않습니다."
        Write-Host "  설치: $InstallHint"
        exit 1
    }
}

function Wait-ForPods {
    param([string]$Label, [int]$TimeoutSec = 180)
    Write-Info "Pod 준비 대기 중 (label: $Label, timeout: ${TimeoutSec}s)..."
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $ready = kubectl get pods -n $Namespace -l $Label `
            --no-headers 2>$null | Where-Object { $_ -match "Running" }
        if ($ready) {
            Write-Success "Pod 준비 완료"
            return $true
        }
        Start-Sleep -Seconds 5
        Write-Host "." -NoNewline
    }
    Write-Host ""
    Write-Warn "Pod 준비 타임아웃. 현재 상태:"
    kubectl get pods -n $Namespace -l $Label
    return $false
}

function Invoke-WithRetry {
    param([scriptblock]$ScriptBlock, [int]$MaxRetries = 3, [int]$DelaySeconds = 5)
    $attempt = 0
    while ($attempt -lt $MaxRetries) {
        try {
            & $ScriptBlock
            return
        } catch {
            $attempt++
            if ($attempt -eq $MaxRetries) { throw }
            Write-Warn "실패 (시도 $attempt/$MaxRetries). ${DelaySeconds}초 후 재시도..."
            Start-Sleep -Seconds $DelaySeconds
        }
    }
}

# ── 사전 요구사항 확인 ─────────────────────────────────────────────────────────
function Test-Prerequisites {
    Write-Step "사전 요구사항 확인"

    Test-Command "docker"    "winget install Docker.DockerDesktop"
    Test-Command "minikube"  "winget install Kubernetes.minikube"
    Test-Command "kubectl"   "winget install Kubernetes.kubectl"
    Test-Command "helm"      "winget install Helm.Helm"

    # Docker 실행 확인
    try {
        docker info 2>&1 | Out-Null
    } catch {
        Write-Err "Docker Desktop이 실행 중이지 않습니다. 실행 후 다시 시도하세요."
        exit 1
    }

    # 프로젝트 루트 확인
    if (-not (Test-Path "$ProjectRoot\settings.gradle.kts") -and
        -not (Test-Path "$ProjectRoot\settings.gradle")) {
        Write-Err "프로젝트 루트를 찾을 수 없습니다: $ProjectRoot"
        exit 1
    }

    Write-Success "모든 요구사항 충족"
}

# ── Minikube 기동 ──────────────────────────────────────────────────────────────
function Start-Minikube {
    Write-Step "Minikube 기동"

    $status = minikube status -p $MinikubeProfile 2>$null
    if ($status -match "Running") {
        Write-Success "Minikube '$MinikubeProfile' 이미 실행 중"
        return
    }

    Write-Info "Minikube 클러스터 생성 (메모리: ${MinikubeMemory}MB, CPU: ${MinikubeCpus}개)..."
    minikube start `
        --profile=$MinikubeProfile `
        --driver=docker `
        --memory=$MinikubeMemory `
        --cpus=$MinikubeCpus `
        --disk-size=$MinikubeDisk `
        --kubernetes-version=$MinikubeK8sVersion `
        --addons=metrics-server `
        --addons=ingress

    kubectl config use-context $MinikubeProfile
    Write-Success "Minikube 기동 완료"
}

# ── Docker Compose 인프라 기동 ─────────────────────────────────────────────────
function Start-Infra {
    Write-Step "Docker Compose 인프라 기동"

    Push-Location $DockerDir
    try {
        $running = docker compose ps --status running --services 2>$null
        if ($running -and ($running | Measure-Object -Line).Lines -gt 0) {
            Write-Success "인프라 컨테이너 이미 실행 중"
            return
        }

        Write-Info "인프라 컨테이너 기동 중..."
        docker compose `
            -f docker-compose.yml `
            -f docker-compose.monitoring.yml `
            up -d

        # PostgreSQL 준비 대기
        Write-Info "PostgreSQL 준비 대기 중..."
        $retries = 30
        while ($retries -gt 0) {
            $result = docker compose exec -T postgres pg_isready -U onepass -d onepass 2>$null
            if ($LASTEXITCODE -eq 0) { break }
            Start-Sleep -Seconds 2
            $retries--
        }
        if ($retries -eq 0) {
            Write-Err "PostgreSQL 기동 타임아웃"
            exit 1
        }

        Write-Success "인프라 기동 완료"
    } finally {
        Pop-Location
    }
}

# ── ido 이미지 빌드 ─────────────────────────────────────────────────────────────
function Build-IdoImage {
    Write-Step "ido 애플리케이션 이미지 빌드"

    Push-Location $ProjectRoot
    try {
        # Minikube Docker 환경으로 전환
        Write-Info "Minikube Docker 환경으로 전환..."
        $minikubeEnv = minikube -p $MinikubeProfile docker-env --shell powershell
        Invoke-Expression ($minikubeEnv -join "`n")

        # Gradle 빌드
        Write-Info "Gradle bootJar 빌드 중..."
        if (Test-Path ".\gradlew.bat") {
            .\gradlew.bat :idem-hub:bootJar -x test --quiet
        } elseif (Test-Path ".\gradlew") {
            bash .\gradlew :idem-hub:bootJar -x test --quiet
        } else {
            Write-Err "gradlew를 찾을 수 없습니다"
            exit 1
        }

        # Docker 빌드
        Write-Info "Docker 이미지 빌드 중: ${IdoImage}:${IdoTag}"
        docker build `
            -f ido\Dockerfile `
            -t "${IdoImage}:${IdoTag}" `
            --build-arg SPRING_PROFILES_ACTIVE=local `
            .

        Write-Success "이미지 빌드 완료: ${IdoImage}:${IdoTag}"
    } finally {
        # Docker 환경 원복
        $resetEnv = minikube -p $MinikubeProfile docker-env --shell powershell --unset 2>$null
        if ($resetEnv) { Invoke-Expression ($resetEnv -join "`n") }
        Pop-Location
    }
}

# ── K8s 리소스 배포 ────────────────────────────────────────────────────────────
function Deploy-ToK8s {
    Write-Step "K8s 리소스 배포"

    # namespace 생성
    kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f -
    Write-Success "Namespace '$Namespace' 준비 완료"

    # ConfigMap 적용
    $localConfigMap = Join-Path $OverlayDir "ido-configmap-local.yml"
    if (Test-Path $localConfigMap) {
        kubectl apply -f $localConfigMap
    } else {
        kubectl apply -f (Join-Path $K8sDir "configmaps\ido-configmap.yml")
    }
    Write-Success "ConfigMap 적용 완료"

    # Secret 생성 (로컬 개발용)
    $secretArgs = @(
        "create", "secret", "generic", "ido-secrets",
        "--namespace=$Namespace",
        "--from-literal=DB_HOST=host.minikube.internal",
        "--from-literal=DB_PORT=5432",
        "--from-literal=DB_NAME=onepass",
        "--from-literal=DB_USERNAME=onepass",
        "--from-literal=DB_PASSWORD=onepass",
        "--from-literal=REDIS_HOST=host.minikube.internal",
        "--from-literal=REDIS_PORT=6379",
        "--from-literal=REDIS_PASSWORD=",
        "--from-literal=KAFKA_SERVERS=host.minikube.internal:9092",
        "--from-literal=IDO_HANDOFF_AES_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "--from-literal=IDO_HANDOFF_HMAC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "--from-literal=IDO_WEBHOOK_SIGNING_SECRET=poc-webhook-secret-local",
        "--from-literal=IDO_INTERNAL_SIG_SECRET=poc-internal-secret-local",
        "--from-literal=IDO_AGENCY_SUBJECT_SECRET=poc-agency-secret-local",
        "--from-literal=NICE_CLIENT_ID=dummy-local",
        "--from-literal=NICE_CLIENT_SECRET=dummy-local",
        "--from-literal=NHN_SKM_APPKEY=dummy-local",
        "--from-literal=NHN_SKM_MODE=secret",
        "--from-literal=NHN_SKM_AES_KEY_ID=dummy-local",
        "--from-literal=NHN_SKM_HMAC_KEY_ID=dummy-local",
        "--from-literal=KEYCLOAK_CLIENT_ID=ido-client",
        "--from-literal=KEYCLOAK_CLIENT_SECRET=dummy-local",
        "--from-literal=QIM_AES_SHARED_KEY=dummy-local",
        "--from-literal=QIM_INBOUND_API_KEY_HASH=dummy-local",
        "--save-config", "--dry-run=client", "-o", "yaml"
    )
    & kubectl @secretArgs | kubectl apply -f -
    Write-Success "Secret 생성 완료"

    # Helm 배포
    Write-Info "Helm으로 ido 배포 중..."
    helm upgrade --install ido (Join-Path $HelmDir "ido") `
        --namespace=$Namespace `
        --set "image.repository=$IdoImage" `
        --set "image.tag=$IdoTag" `
        --set "image.pullPolicy=Never" `
        --set "replicaCount=1" `
        --set "spring.profilesActive=local" `
        --set "namespace=$Namespace" `
        --set "autoscaling.enabled=false" `
        --set "topologySpread.enabled=false" `
        --timeout=120s `
        --wait=$false

    Write-Success "Helm 배포 완료"
    Wait-ForPods "app=ido" 180 | Out-Null
}

# ── 포트 포워딩 ────────────────────────────────────────────────────────────────
function Start-PortForward {
    Write-Step "포트 포워딩 설정"

    # 기존 포트 포워딩 종료
    Get-Process -Name "kubectl" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "port-forward" } |
        Stop-Process -Force -ErrorAction SilentlyContinue

    Write-Info "ido 포트 포워딩: localhost:8083 → ido-service:8083"
    $job = Start-Job -ScriptBlock {
        kubectl port-forward service/ido-service 8083:8083 -n $using:Namespace
    }
    $job.Id | Out-File -FilePath "$env:TEMP\idem-pf-hub.txt" -Encoding utf8

    Start-Sleep -Seconds 3

    # 헬스체크
    $retries = 15
    while ($retries -gt 0) {
        try {
            $resp = Invoke-WebRequest -Uri "http://localhost:8083/actuator/health" `
                -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            if ($resp.StatusCode -eq 200) {
                Write-Success "ido 헬스체크 통과: http://localhost:8083/actuator/health"
                return
            }
        } catch { }
        Start-Sleep -Seconds 3
        $retries--
    }
    Write-Warn "헬스체크 미통과 — 아직 시작 중일 수 있습니다."
    Write-Warn "수동 확인: Invoke-WebRequest http://localhost:8083/actuator/health"
}

# ── 상태 출력 ──────────────────────────────────────────────────────────────────
function Show-Status {
    Write-Step "현재 상태"

    Write-Host "`n[ Minikube 클러스터 ]" -ForegroundColor Bold
    minikube status -p $MinikubeProfile 2>$null

    Write-Host "`n[ K8s Pods — namespace: $Namespace ]" -ForegroundColor Bold
    kubectl get pods -n $Namespace -o wide 2>$null

    Write-Host "`n[ K8s Services ]" -ForegroundColor Bold
    kubectl get svc -n $Namespace 2>$null

    Write-Host "`n[ Docker Compose 인프라 ]" -ForegroundColor Bold
    Push-Location $DockerDir
    docker compose ps 2>$null
    Pop-Location

    Write-Host "`n[ 접속 정보 ]" -ForegroundColor Bold
    Write-Host "  ido API    : http://localhost:8083"
    Write-Host "  Actuator   : http://localhost:8083/actuator/health"
    Write-Host "  Grafana    : http://localhost:3000  (admin / onepass-admin)"
    Write-Host "  Prometheus : http://localhost:9090"
    Write-Host "  Kafka UI   : http://localhost:8090  (admin / admin)"
    Write-Host "  pgAdmin    : http://localhost:5050"
}

# ── 로그 스트리밍 ──────────────────────────────────────────────────────────────
function Get-Logs {
    Write-Info "ido Pod 로그 스트리밍 (Ctrl+C로 종료)"
    kubectl logs -f -l app=ido -n $Namespace --all-containers=$true 2>$null
}

# ── 전체 정리 ──────────────────────────────────────────────────────────────────
function Invoke-Teardown {
    Write-Step "전체 환경 정리"

    # 포트 포워딩 종료
    if (Test-Path "$env:TEMP\idem-pf-hub.txt") {
        $jobId = Get-Content "$env:TEMP\idem-pf-hub.txt"
        Stop-Job -Id $jobId -ErrorAction SilentlyContinue
        Remove-Job -Id $jobId -ErrorAction SilentlyContinue
        Remove-Item "$env:TEMP\idem-pf-hub.txt" -ErrorAction SilentlyContinue
    }

    # Helm 릴리스 삭제
    helm uninstall ido -n $Namespace 2>$null
    Write-Success "Helm 릴리스 삭제"

    kubectl delete namespace $Namespace --ignore-not-found=$true
    Write-Success "Namespace 삭제"

    $confirm = Read-Host "Minikube 클러스터를 삭제하시겠습니까? (y/N)"
    if ($confirm -match "^[Yy]$") {
        minikube delete -p $MinikubeProfile
        Write-Success "Minikube 클러스터 삭제"
    } else {
        minikube stop -p $MinikubeProfile
        Write-Success "Minikube 클러스터 중지 (데이터 보존)"
    }

    $confirm2 = Read-Host "Docker Compose 인프라도 종료하시겠습니까? (y/N)"
    if ($confirm2 -match "^[Yy]$") {
        Push-Location $DockerDir
        docker compose -f docker-compose.yml -f docker-compose.monitoring.yml down
        Pop-Location
        Write-Success "Docker Compose 종료"
    }

    Write-Success "정리 완료"
}

# ── 메인 ───────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "╔══════════════════════════════════════════════════════╗" -ForegroundColor Blue
Write-Host "║   OnePass Minikube 로컬 K8s 시뮬레이션 (Windows)    ║" -ForegroundColor Blue
Write-Host "╚══════════════════════════════════════════════════════╝" -ForegroundColor Blue
Write-Host ""
Write-Host "  MODE    : $Mode"
Write-Host "  PROJECT : $ProjectRoot"
Write-Host "  PROFILE : $MinikubeProfile"
Write-Host ""

switch ($Mode) {
    "setup" {
        Test-Prerequisites
        Start-Minikube
        Start-Infra
        Build-IdoImage
        Deploy-ToK8s
        Start-PortForward
        Show-Status
        Write-Host ""
        Write-Success "=== 설정 완료 ==="
        Write-Host "  ido API    : http://localhost:8083/actuator/health"
        Write-Host "  로그 확인  : .\infra\minikube\scripts\setup-windows.ps1 -Mode logs"
        Write-Host "  상태 확인  : .\infra\minikube\scripts\setup-windows.ps1 -Mode status"
        Write-Host "  정리       : .\infra\minikube\scripts\setup-windows.ps1 -Mode teardown"
    }
    "infra-only" {
        Test-Prerequisites
        Start-Infra
    }
    "deploy" {
        Test-Prerequisites
        Build-IdoImage
        Deploy-ToK8s
        Start-PortForward
    }
    "teardown" { Invoke-Teardown }
    "status"   { Show-Status }
    "logs"     { Get-Logs }
}
