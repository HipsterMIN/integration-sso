<#
.SYNOPSIS
    OnePass 통합인증 플랫폼 — Smoke Test (PowerShell)

.DESCRIPTION
    배포 후 핵심 서비스 동작을 자동 검증합니다.
    Windows PowerShell 5.1 / PowerShell Core 7+ 모두 지원.

.PARAMETER QSignUrl
    Q-Sign base URL (기본: http://localhost:8081)

.PARAMETER QimUrl
    Q-IM base URL (기본: http://localhost:8082)

.PARAMETER IdoUrl
    IdO base URL (기본: http://localhost:8083)

.PARAMETER AgencyUrl
    Agency-Stub URL (기본: http://localhost:8084)

.PARAMETER FeUrl
    Frontend(Nginx) URL (기본: http://localhost:3001)

.PARAMETER PrometheusUrl
    Prometheus URL (기본: http://localhost:9090)

.PARAMETER GrafanaUrl
    Grafana URL (기본: http://localhost:3002)

.PARAMETER RedisHost
    Redis 호스트 (기본: localhost)

.PARAMETER RedisPort
    Redis 포트 (기본: 6379)

.PARAMETER DbHost
    PostgreSQL 호스트 (기본: localhost)

.PARAMETER DbPort
    PostgreSQL 포트 (기본: 5432)

.PARAMETER QimApiKey
    Q-IM 내부 API 키 (기본: dev-qim-internal-api-key-change-me)

.PARAMETER SkipInfra
    인프라(DB/Redis/Kafka) 체크 건너뜀

.PARAMETER SkipKafka
    Kafka 체크 건너뜀

.PARAMETER SkipMonitoring
    모니터링(Prometheus/Grafana) 체크 건너뜀

.PARAMETER Ci
    CI 모드 (색상 없음, 종료코드 엄격)

.PARAMETER TimeoutSec
    HTTP 요청 타임아웃 초 (기본: 10)

.EXAMPLE
    # 기본 실행 (모든 서비스 로컬)
    .\infra\scripts\smoke-test.ps1

.EXAMPLE
    # CI 파이프라인 (인프라 체크 스킵)
    .\infra\scripts\smoke-test.ps1 -Ci -SkipInfra `
        -IdoUrl "http://ido-service:8083" `
        -QimUrl "http://qim-service:8082" `
        -QSignUrl "http://qsign-service:8081"

.EXAMPLE
    # 스테이징 환경 전체 검증
    .\infra\scripts\smoke-test.ps1 `
        -QSignUrl "http://qsign.stage.example.com" `
        -QimUrl "http://qim.stage.example.com" `
        -IdoUrl "http://ido.stage.example.com"
#>

[CmdletBinding()]
param(
    [string]$QSignUrl      = "http://localhost:8081",
    [string]$QimUrl        = "http://localhost:8082",
    [string]$IdoUrl        = "http://localhost:8083",
    [string]$AgencyUrl     = "http://localhost:8084",
    [string]$FeUrl         = "http://localhost:3001",
    [string]$PrometheusUrl = "http://localhost:9090",
    [string]$GrafanaUrl    = "http://localhost:3002",
    [string]$RedisHost     = "localhost",
    [int]   $RedisPort     = 6379,
    [string]$DbHost        = "localhost",
    [int]   $DbPort        = 5432,
    [string]$DbUser        = "onepass",
    [string]$DbPass        = "onepass",
    [string]$MariadbHost   = "localhost",
    [int]   $MariadbPort   = 3306,
    [string]$QimApiKey     = "dev-qim-internal-api-key-change-me",
    [switch]$SkipInfra,
    [switch]$SkipKafka,
    [switch]$SkipMonitoring,
    [switch]$Ci,
    [int]   $TimeoutSec    = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'SilentlyContinue'

# ── 결과 카운터 ───────────────────────────────────────────────
$script:Pass     = 0
$script:Fail     = 0
$script:SkipCnt  = 0
$script:Failures = @()

# ── 출력 헬퍼 ─────────────────────────────────────────────────
function Write-Pass {
    param([string]$Message)
    $script:Pass++
    if ($Ci) { Write-Host "[PASS] $Message" }
    else     { Write-Host "[PASS] $Message" -ForegroundColor Green }
}

function Write-Fail {
    param([string]$Message, [string]$Hint = "")
    $script:Fail++
    $script:Failures += $Message
    if ($Ci) {
        Write-Host "[FAIL] $Message"
        if ($Hint) { Write-Host "       → $Hint" }
    } else {
        Write-Host "[FAIL] $Message" -ForegroundColor Red
        if ($Hint) { Write-Host "       → $Hint" -ForegroundColor Yellow }
    }
}

function Write-Skip {
    param([string]$Message)
    $script:SkipCnt++
    if ($Ci) { Write-Host "[SKIP] $Message" }
    else     { Write-Host "[SKIP] $Message" -ForegroundColor Yellow }
}

function Write-Section {
    param([string]$Title)
    Write-Host ""
    if ($Ci) { Write-Host "== $Title ==" }
    else     { Write-Host "══ $Title ══" -ForegroundColor Cyan }
}

# ── HTTP GET 헬스체크 ─────────────────────────────────────────
function Test-HttpHealth {
    param(
        [string]$Name,
        [string]$Url,
        [int]   $Expected = 200,
        [string]$Hint     = ""
    )
    try {
        $resp = Invoke-WebRequest -Uri $Url `
            -Method GET `
            -TimeoutSec $TimeoutSec `
            -UseBasicParsing `
            -ErrorAction Stop
        if ($resp.StatusCode -eq $Expected) {
            Write-Pass "$Name ($Url → HTTP $($resp.StatusCode))"
            return $true
        } else {
            Write-Fail "$Name" "HTTP $($resp.StatusCode) ≠ $Expected | URL: $Url$( if ($Hint) { ' | 힌트: ' + $Hint } )"
            return $false
        }
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -and $statusCode -eq $Expected) {
            Write-Pass "$Name ($Url → HTTP $statusCode)"
            return $true
        } elseif ($statusCode) {
            Write-Fail "$Name" "HTTP $statusCode ≠ $Expected | URL: $Url$( if ($Hint) { ' | 힌트: ' + $Hint } )"
        } else {
            Write-Fail "$Name" "연결 실패: $($_.Exception.Message) | URL: $Url$( if ($Hint) { ' | 힌트: ' + $Hint } )"
        }
        return $false
    }
}

# ── HTTP 응답 Body에 특정 문자열 포함 확인 ────────────────────
function Test-HttpBodyContains {
    param(
        [string]$Name,
        [string]$Url,
        [string]$Contains,
        [hashtable]$Headers = @{},
        [string]$Hint = ""
    )
    try {
        $resp = Invoke-WebRequest -Uri $Url `
            -Method GET `
            -Headers $Headers `
            -TimeoutSec $TimeoutSec `
            -UseBasicParsing `
            -ErrorAction Stop
        if ($resp.Content -match [regex]::Escape($Contains)) {
            Write-Pass "$Name ($Contains 확인)"
            return $true
        } else {
            Write-Fail "$Name" "응답에 '$Contains' 없음$( if ($Hint) { ' | 힌트: ' + $Hint } )"
            return $false
        }
    } catch {
        Write-Fail "$Name" "연결 실패: $($_.Exception.Message)$( if ($Hint) { ' | 힌트: ' + $Hint } )"
        return $false
    }
}

# ── HTTP 상태코드 반환 (예외 포함) ────────────────────────────
function Get-HttpStatusCode {
    param([string]$Url, [hashtable]$Headers = @{})
    try {
        $resp = Invoke-WebRequest -Uri $Url `
            -Method GET `
            -Headers $Headers `
            -TimeoutSec $TimeoutSec `
            -UseBasicParsing `
            -ErrorAction Stop
        return $resp.StatusCode
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code) { return $code }
        return 0
    }
}

# ── TCP 포트 연결 확인 ─────────────────────────────────────────
function Test-TcpPort {
    param([string]$Name, [string]$Host, [int]$Port, [string]$Hint = "")
    try {
        $tcp = [System.Net.Sockets.TcpClient]::new()
        $ar  = $tcp.BeginConnect($Host, $Port, $null, $null)
        $ok  = $ar.AsyncWaitHandle.WaitOne($TimeoutSec * 1000, $false)
        if ($ok -and $tcp.Connected) {
            $tcp.Close()
            Write-Pass "$Name ($Host`:$Port TCP 연결)"
            return $true
        } else {
            $tcp.Close()
            Write-Fail "$Name" "$Host`:$Port TCP 연결 실패$( if ($Hint) { ' | 힌트: ' + $Hint } )"
            return $false
        }
    } catch {
        Write-Fail "$Name" "TCP 연결 오류: $($_.Exception.Message)$( if ($Hint) { ' | 힌트: ' + $Hint } )"
        return $false
    }
}

# ────────────────────────────────────────────────────────────────────────────
# 검사 시작
# ────────────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor $(if ($Ci) { 'White' } else { 'Cyan' })
Write-Host " OnePass 통합인증 플랫폼 — Smoke Test"
Write-Host " $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor $(if ($Ci) { 'White' } else { 'Cyan' })

# ── [1] 인프라 접속 확인 ──────────────────────────────────────
if ($SkipInfra) {
    Write-Section "인프라 접속 [SKIP]"
    Write-Skip "인프라 체크 건너뜀 (-SkipInfra)"
} else {
    Write-Section "인프라 접속"

    # PostgreSQL TCP 확인
    $null = Test-TcpPort "PostgreSQL TCP" $DbHost $DbPort `
        "DB_HOST, DB_PORT 확인. PostgreSQL 서버 기동 여부 확인"

    # MariaDB TCP 확인
    $null = Test-TcpPort "MariaDB TCP" $MariadbHost $MariadbPort `
        "QIM_DB_HOST, QIM_DB_PORT 확인. MariaDB 서버 기동 여부 확인"

    # Redis TCP 확인
    $null = Test-TcpPort "Redis TCP" $RedisHost $RedisPort `
        "REDIS_HOST, REDIS_PORT 확인. Redis 서버 기동 여부 확인"

    # Kafka TCP 확인 (SkipKafka 아닌 경우)
    if (-not $SkipKafka) {
        $null = Test-TcpPort "Kafka 브로커 TCP" "localhost" 9092 `
            "KAFKA_SERVERS 환경변수, kafka-init 컨테이너 완료 여부 확인"
    } else {
        Write-Skip "Kafka 체크 건너뜀 (-SkipKafka)"
    }
}

# ── [2] 앱 서비스 헬스 확인 ───────────────────────────────────
Write-Section "앱 서비스 Actuator Health"

$null = Test-HttpHealth "Q-Sign actuator/health" `
    "$QSignUrl/actuator/health" 200 `
    "q-sign 미기동 또는 포트(8081) 확인. SPRING_PROFILES_ACTIVE=docker|k8s 확인"

$null = Test-HttpHealth "Q-IM actuator/health" `
    "$QimUrl/actuator/health" 200 `
    "q-im 미기동 또는 포트(8082) 확인. MariaDB 접속 여부 먼저 확인"

$null = Test-HttpHealth "IdO actuator/health" `
    "$IdoUrl/actuator/health" 200 `
    "ido 미기동 또는 포트(8083) 확인. Q-IM/Q-Sign 모두 healthy 상태여야 함"

$agencyStatus = Get-HttpStatusCode "$AgencyUrl/actuator/health"
if ($agencyStatus -eq 200) {
    Write-Pass "Agency-Stub actuator/health ($AgencyUrl → HTTP $agencyStatus)"
} elseif ($agencyStatus -eq 0) {
    Write-Skip "Agency-Stub 미기동 (PoC 전용 서비스 — 운영에서는 비활성)"
} else {
    Write-Fail "Agency-Stub actuator/health" "HTTP $agencyStatus"
}

# ── [3] Actuator Prometheus 메트릭 ───────────────────────────
Write-Section "Prometheus 메트릭 엔드포인트"

$null = Test-HttpBodyContains "Q-Sign /actuator/prometheus" `
    "$QSignUrl/actuator/prometheus" `
    "http_server_requests_seconds" `
    @{} `
    "management.endpoints.web.exposure.include에 prometheus 포함 여부 확인"

$null = Test-HttpBodyContains "Q-IM /actuator/prometheus" `
    "$QimUrl/actuator/prometheus" `
    "http_server_requests_seconds"

$null = Test-HttpBodyContains "IdO /actuator/prometheus" `
    "$IdoUrl/actuator/prometheus" `
    "http_server_requests_seconds"

# ── [4] IdO → Q-IM 내부 API 인증 확인 ────────────────────────
Write-Section "내부 API 인증"

# 올바른 키로 호출 → 인증 통과 (401/403 아님)
$authStatus = Get-HttpStatusCode "$QimUrl/api/v1/internal/health-probe" `
    @{ "X-Internal-Api-Key" = $QimApiKey; "Content-Type" = "application/json" }

if ($authStatus -ne 0 -and $authStatus -ne 401 -and $authStatus -ne 403) {
    Write-Pass "Q-IM 내부 API 인증 통과 (HTTP $authStatus — 401/403 아님)"
} elseif ($authStatus -eq 401 -or $authStatus -eq 403) {
    Write-Fail "Q-IM 내부 API 인증 실패 (HTTP $authStatus)" `
        "QIM_INTERNAL_API_KEY vs IDO_QIM_INTERNAL_API_KEY 불일치 확인. .env 파일 QIM_INTERNAL_API_KEY 값 확인"
} else {
    Write-Fail "Q-IM 내부 API 호출 불가" "Q-IM 서비스 기동 여부 확인"
}

# 잘못된 키로 호출 → 401/403 반환 확인 (보안 검증)
$badAuthStatus = Get-HttpStatusCode "$QimUrl/api/v1/internal/health-probe" `
    @{ "X-Internal-Api-Key" = "invalid-key-should-fail" }

if ($badAuthStatus -eq 401 -or $badAuthStatus -eq 403) {
    Write-Pass "Q-IM 내부 API 잘못된 키 거부 (HTTP $badAuthStatus — 보안 정상)"
} elseif ($badAuthStatus -eq 0) {
    Write-Skip "Q-IM 내부 API 보안 검증 (Q-IM 미응답)"
} else {
    Write-Fail "Q-IM 내부 API 보안 문제" `
        "잘못된 키에 HTTP $badAuthStatus 반환 — 인증 미들웨어(InternalApiKeyFilter) 확인"
}

# ── [5] IdO 핵심 Probe ───────────────────────────────────────
Write-Section "IdO Liveness / Readiness Probe"

$null = Test-HttpHealth "IdO liveness probe" `
    "$IdoUrl/actuator/health/liveness" 200

$null = Test-HttpHealth "IdO readiness probe" `
    "$IdoUrl/actuator/health/readiness" 200 `
    "readiness 실패 시 actuator/health 상세 확인 — DB/Redis/Kafka 연결 상태"

# ── [6] Frontend/Nginx 확인 ───────────────────────────────────
Write-Section "Frontend/Nginx"

$feStatus = Get-HttpStatusCode $FeUrl
if ($feStatus -eq 200) {
    Write-Pass "Frontend(Nginx) 접근 ($FeUrl → HTTP $feStatus)"
    $null = Test-HttpHealth "Nginx /nginx-health" "$FeUrl/nginx-health" 200 `
        "nginx.conf에 /nginx-health 경로 설정 확인"
} elseif ($feStatus -eq 0) {
    Write-Skip "Frontend(Nginx) 미기동 — optionB 프로파일 포함 여부 확인"
} else {
    Write-Fail "Frontend(Nginx) 접근" `
        "HTTP $feStatus | onepass-react 컨테이너 상태 및 Dockerfile.optionB 빌드 확인"
}

# ── [7] Prometheus/Grafana ────────────────────────────────────
if ($SkipMonitoring) {
    Write-Section "모니터링 [SKIP]"
    Write-Skip "모니터링 체크 건너뜀 (-SkipMonitoring)"
} else {
    Write-Section "Prometheus / Grafana"

    $promStatus = Get-HttpStatusCode $PrometheusUrl
    if ($promStatus -eq 200 -or $promStatus -eq 302) {
        Write-Pass "Prometheus 접근 ($PrometheusUrl → HTTP $promStatus)"

        # 스크레이프 타겟 상태 확인
        try {
            $targets = Invoke-RestMethod -Uri "$PrometheusUrl/api/v1/targets" `
                -TimeoutSec $TimeoutSec -ErrorAction Stop
            foreach ($job in @("q-sign", "q-im", "ido")) {
                $target = $targets.data.activeTargets | Where-Object {
                    $_.labels.job -eq $job
                } | Select-Object -First 1
                if ($target) {
                    if ($target.health -eq "up") {
                        Write-Pass "Prometheus scrape: $job (health=up)"
                    } else {
                        Write-Fail "Prometheus scrape: $job (health=$($target.health))" `
                            "앱 서비스 기동 및 /actuator/prometheus 노출 확인"
                    }
                } else {
                    Write-Fail "Prometheus scrape target 없음: $job" `
                        "prometheus.yml scrape_configs 타겟 설정 확인"
                }
            }
        } catch {
            Write-Skip "Prometheus 타겟 상세 확인 실패: $($_.Exception.Message)"
        }
    } elseif ($promStatus -eq 0) {
        Write-Skip "Prometheus 미기동 (--profile monitoring 포함 여부 확인)"
    } else {
        Write-Fail "Prometheus 접근" "HTTP $promStatus"
    }

    $grafanaStatus = Get-HttpStatusCode $GrafanaUrl
    if ($grafanaStatus -eq 200 -or $grafanaStatus -eq 302) {
        Write-Pass "Grafana 접근 ($GrafanaUrl → HTTP $grafanaStatus)"
    } elseif ($grafanaStatus -eq 0) {
        Write-Skip "Grafana 미기동 (--profile monitoring 포함 여부 확인)"
    } else {
        Write-Fail "Grafana 접근" "HTTP $grafanaStatus"
    }
}

# ── 결과 요약 ─────────────────────────────────────────────────
Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host " 결과 요약: PASS=$($script:Pass)  FAIL=$($script:Fail)  SKIP=$($script:SkipCnt)"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if ($script:Fail -gt 0) {
    Write-Host ""
    if (-not $Ci) { Write-Host "실패 항목 목록:" -ForegroundColor Red }
    else          { Write-Host "실패 항목 목록:" }
    foreach ($f in $script:Failures) {
        if (-not $Ci) { Write-Host "  ✗ $f" -ForegroundColor Red }
        else          { Write-Host "  x $f" }
    }
    Write-Host ""
    if (-not $Ci) { Write-Host "Smoke Test 실패 ($($script:Fail)개 항목) — 운영 배포 전 반드시 해결하세요." -ForegroundColor Red }
    else          { Write-Host "Smoke Test 실패 ($($script:Fail)개 항목)" }
    exit 1
} else {
    Write-Host ""
    if (-not $Ci) { Write-Host "모든 Smoke Test 통과 — 배포 준비 완료." -ForegroundColor Green }
    else          { Write-Host "모든 Smoke Test 통과." }
    exit 0
}
