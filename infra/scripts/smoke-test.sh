#!/usr/bin/env bash
# ============================================================
# OnePass 통합인증 플랫폼 — Smoke Test (Bash)
#
# 사용법:
#   ./infra/scripts/smoke-test.sh [옵션]
#
# 옵션:
#   --qsign-url   URL    Q-Sign base URL (기본: http://localhost:8081)
#   --qim-url     URL    Q-IM base URL   (기본: http://localhost:8082)
#   --ido-url     URL    IdO base URL    (기본: http://localhost:8083)
#   --agency-url  URL    Agency-Stub URL (기본: http://localhost:8084)
#   --fe-url      URL    Frontend(Nginx) URL (기본: http://localhost:3001)
#   --prometheus-url URL Prometheus URL (기본: http://localhost:9090)
#   --grafana-url URL    Grafana URL    (기본: http://localhost:3002)
#   --kafka-host  HOST   Kafka 브로커 HOST:PORT (기본: localhost:9092)
#   --redis-host  HOST   Redis HOST     (기본: localhost)
#   --redis-port  PORT   Redis PORT     (기본: 6379)
#   --db-host     HOST   PostgreSQL HOST (기본: localhost)
#   --db-port     PORT   PostgreSQL PORT (기본: 5432)
#   --db-user     USER   PostgreSQL user (기본: onepass)
#   --db-pass     PASS   PostgreSQL pass (기본: onepass)
#   --mariadb-host HOST  MariaDB HOST   (기본: localhost)
#   --mariadb-port PORT  MariaDB PORT   (기본: 3306)
#   --mariadb-user USER  MariaDB user   (기본: qim)
#   --mariadb-pass PASS  MariaDB pass   (기본: qim)
#   --qim-api-key KEY    QIM internal API key (기본: dev-qim-internal-api-key-change-me)
#   --skip-infra         인프라 체크(DB/Redis/Kafka) 건너뜀
#   --skip-kafka         Kafka 체크 건너뜀
#   --skip-monitoring    모니터링 체크 건너뜀
#   --ci                 CI 모드 (색상 없음, 종료코드 엄격)
#   --timeout SEC        요청 타임아웃 초 (기본: 10)
#   -h, --help           도움말
#
# CI 사용 예:
#   ./infra/scripts/smoke-test.sh --ci --skip-monitoring \
#     --ido-url http://ido-service:8083 \
#     --qim-url http://qim-service:8082 \
#     --qsign-url http://qsign-service:8081
#
# 종료코드:
#   0 = 전체 통과
#   1 = 1개 이상 실패
# ============================================================
# set -e 비활성화: 개별 체크 실패 시에도 전체 실행 계속
set -uo pipefail

# ── 기본값 ────────────────────────────────────────────────────
QSIGN_URL="http://localhost:8081"
QIM_URL="http://localhost:8082"
IDO_URL="http://localhost:8083"
AGENCY_URL="http://localhost:8084"
FE_URL="http://localhost:3001"
PROMETHEUS_URL="http://localhost:9090"
GRAFANA_URL="http://localhost:3002"
KAFKA_HOST="localhost:9092"
REDIS_HOST="localhost"
REDIS_PORT="6379"
DB_HOST="localhost"
DB_PORT="5432"
DB_USER="onepass"
DB_PASS="onepass"
MARIADB_HOST="localhost"
MARIADB_PORT="3306"
MARIADB_USER="qim"
MARIADB_PASS="qim"
QIM_API_KEY="dev-qim-internal-api-key-change-me"
SKIP_INFRA=false
SKIP_KAFKA=false
SKIP_MONITORING=false
CI_MODE=false
TIMEOUT=10

# ── 인자 파싱 ─────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --qsign-url)      QSIGN_URL="$2";      shift 2;;
    --qim-url)        QIM_URL="$2";        shift 2;;
    --ido-url)        IDO_URL="$2";        shift 2;;
    --agency-url)     AGENCY_URL="$2";     shift 2;;
    --fe-url)         FE_URL="$2";         shift 2;;
    --prometheus-url) PROMETHEUS_URL="$2"; shift 2;;
    --grafana-url)    GRAFANA_URL="$2";    shift 2;;
    --kafka-host)     KAFKA_HOST="$2";     shift 2;;
    --redis-host)     REDIS_HOST="$2";     shift 2;;
    --redis-port)     REDIS_PORT="$2";     shift 2;;
    --db-host)        DB_HOST="$2";        shift 2;;
    --db-port)        DB_PORT="$2";        shift 2;;
    --db-user)        DB_USER="$2";        shift 2;;
    --db-pass)        DB_PASS="$2";        shift 2;;
    --mariadb-host)   MARIADB_HOST="$2";   shift 2;;
    --mariadb-port)   MARIADB_PORT="$2";   shift 2;;
    --mariadb-user)   MARIADB_USER="$2";   shift 2;;
    --mariadb-pass)   MARIADB_PASS="$2";   shift 2;;
    --qim-api-key)    QIM_API_KEY="$2";    shift 2;;
    --skip-infra)     SKIP_INFRA=true;     shift;;
    --skip-kafka)     SKIP_KAFKA=true;     shift;;
    --skip-monitoring) SKIP_MONITORING=true; shift;;
    --ci)             CI_MODE=true;        shift;;
    --timeout)        TIMEOUT="$2";        shift 2;;
    -h|--help)
      sed -n '2,50p' "$0" | grep '^#' | sed 's/^# \?//'
      exit 0;;
    *)
      echo "알 수 없는 옵션: $1" >&2
      exit 1;;
  esac
done

# ── 컬러 설정 ─────────────────────────────────────────────────
if $CI_MODE; then
  RED="" GREEN="" YELLOW="" BLUE="" BOLD="" RESET=""
else
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  BLUE='\033[0;34m'
  BOLD='\033[1m'
  RESET='\033[0m'
fi

# ── 결과 카운터 ───────────────────────────────────────────────
PASS=0
FAIL=0
SKIP=0
FAILURES=()

# ── 헬퍼 함수 ─────────────────────────────────────────────────
pass() {
  PASS=$((PASS + 1))
  echo -e "${GREEN}[PASS]${RESET} $1"
}

fail() {
  FAIL=$((FAIL + 1))
  FAILURES+=("$1")
  echo -e "${RED}[FAIL]${RESET} $1"
  if [[ -n "${2:-}" ]]; then
    echo -e "       ${YELLOW}→ $2${RESET}"
  fi
}

skip() {
  SKIP=$((SKIP + 1))
  echo -e "${YELLOW}[SKIP]${RESET} $1"
}

section() {
  echo ""
  echo -e "${BLUE}${BOLD}══ $1 ══${RESET}"
}

# HTTP GET 헬스체크
check_http() {
  local name="$1"
  local url="$2"
  local expected="${3:-200}"
  local hint="${4:-}"

  local status
  status=$(curl -sf -o /dev/null -w "%{http_code}" \
    --connect-timeout "$TIMEOUT" \
    --max-time "$TIMEOUT" \
    "$url" 2>/dev/null) || status="000"

  if [[ "$status" == "$expected" ]]; then
    pass "$name ($url → HTTP $status)"
    return 0
  else
    fail "$name" "HTTP $status ≠ $expected | URL: $url${hint:+ | 힌트: $hint}"
    return 1
  fi
}

# JSON body에 특정 키가 있는지 확인
check_http_json_key() {
  local name="$1"
  local url="$2"
  local json_key="$3"  # 포함되어야 할 문자열
  local extra_headers="${4:-}"
  local hint="${5:-}"

  local body
  body=$(curl -sf \
    --connect-timeout "$TIMEOUT" \
    --max-time "$TIMEOUT" \
    ${extra_headers:+-H "$extra_headers"} \
    "$url" 2>/dev/null) || body=""

  if echo "$body" | grep -q "$json_key"; then
    pass "$name ($json_key 확인)"
    return 0
  else
    fail "$name" "응답에 '$json_key' 없음 | URL: $url${hint:+ | 힌트: $hint}"
    return 1
  fi
}

# TCP 포트 연결 확인
check_tcp() {
  local name="$1"
  local host="$2"
  local port="$3"
  local hint="${4:-}"

  if timeout "$TIMEOUT" bash -c "echo >/dev/tcp/$host/$port" 2>/dev/null; then
    pass "$name ($host:$port TCP 연결)"
    return 0
  else
    fail "$name" "$host:$port TCP 연결 실패${hint:+ | 힌트: $hint}"
    return 1
  fi
}

# ── 검사 시작 ─────────────────────────────────────────────────
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD} OnePass 통합인증 플랫폼 — Smoke Test${RESET}"
echo -e "${BOLD} $(date '+%Y-%m-%d %H:%M:%S')${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

# ── [1] 인프라 접속 확인 ──────────────────────────────────────
if $SKIP_INFRA; then
  section "인프라 접속 [SKIP]"
  skip "인프라 체크 건너뜀 (--skip-infra)"
else
  section "인프라 접속"

  # PostgreSQL
  if command -v pg_isready &>/dev/null; then
    if pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "onepass" -t "$TIMEOUT" &>/dev/null; then
      pass "PostgreSQL 접속 ($DB_HOST:$DB_PORT)"
    else
      fail "PostgreSQL 접속" \
        "pg_isready 실패 | 확인: POSTGRES_* 환경변수, 방화벽 정책, 서버 기동 여부"
    fi
  elif command -v psql &>/dev/null; then
    PGPASSWORD="$DB_PASS" psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "onepass" \
      -c "SELECT 1;" -t --connect-timeout="$TIMEOUT" &>/dev/null \
      && pass "PostgreSQL 접속 ($DB_HOST:$DB_PORT)" \
      || fail "PostgreSQL 접속" "psql 연결 실패 | 확인: DB_HOST, DB_PORT, DB_USER, DB_PASSWORD"
  else
    check_tcp "PostgreSQL TCP" "$DB_HOST" "$DB_PORT" \
      "pg_isready/psql 미설치 — TCP 포트만 확인"
  fi

  # MariaDB
  if command -v mysql &>/dev/null; then
    mysql -h "$MARIADB_HOST" -P "$MARIADB_PORT" \
      -u "$MARIADB_USER" -p"$MARIADB_PASS" \
      --connect-timeout="$TIMEOUT" \
      -e "SELECT 1;" qim &>/dev/null \
      && pass "MariaDB 접속 ($MARIADB_HOST:$MARIADB_PORT)" \
      || fail "MariaDB 접속" "mysql 연결 실패 | 확인: QIM_DB_HOST, QIM_DB_USERNAME, QIM_DB_PASSWORD"
  else
    check_tcp "MariaDB TCP" "$MARIADB_HOST" "$MARIADB_PORT" \
      "mysql 클라이언트 미설치 — TCP 포트만 확인"
  fi

  # Redis
  if command -v redis-cli &>/dev/null; then
    PONG=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" \
      --no-auth-warning ping 2>/dev/null) || PONG=""
    if [[ "$PONG" == "PONG" ]]; then
      pass "Redis 접속 ($REDIS_HOST:$REDIS_PORT)"
    else
      fail "Redis 접속" \
        "PING 응답: '$PONG' | 확인: REDIS_HOST, REDIS_PORT, Redis 서버 기동 여부"
    fi
  else
    check_tcp "Redis TCP" "$REDIS_HOST" "$REDIS_PORT" \
      "redis-cli 미설치 — TCP 포트만 확인"
  fi

  # Kafka
  if ! $SKIP_KAFKA; then
    KAFKA_H="${KAFKA_HOST%%:*}"
    KAFKA_P="${KAFKA_HOST##*:}"
    check_tcp "Kafka 브로커 TCP" "$KAFKA_H" "$KAFKA_P" \
      "Kafka 기동 여부, 네트워크 정책, KAFKA_SERVERS 설정 확인"

    # Kafka topic 존재 확인 (kafka-topics 명령 있는 경우)
    if command -v kafka-topics &>/dev/null; then
      REQUIRED_TOPICS=(
        "qsign.auth.events"
        "ido.handoff.events"
        "platform.session.advisory"
        "platform.audit.log"
        "qim.user.events"
        "qim.sp.member.events"
        "ido.handoff.events.dlt"
        "platform.session.advisory.dlt"
      )
      EXISTING_TOPICS=$(kafka-topics --bootstrap-server "$KAFKA_HOST" --list 2>/dev/null) || EXISTING_TOPICS=""
      for t in "${REQUIRED_TOPICS[@]}"; do
        if echo "$EXISTING_TOPICS" | grep -qx "$t"; then
          pass "Kafka topic 존재: $t"
        else
          fail "Kafka topic 없음: $t" \
            "infra/docker/kafka/create-topics.sh 실행 여부 확인"
        fi
      done
    else
      skip "Kafka topic 목록 확인 (kafka-topics 미설치)"
    fi
  else
    skip "Kafka 체크 건너뜀 (--skip-kafka)"
  fi
fi

# ── [2] 앱 서비스 헬스 확인 ───────────────────────────────────
section "앱 서비스 Actuator Health"

check_http "Q-Sign actuator/health" \
  "${QSIGN_URL}/actuator/health" "200" \
  "q-sign 미기동 또는 포트(8081) 확인. spring.profiles.active=docker|k8s 확인"

check_http "Q-IM actuator/health" \
  "${QIM_URL}/actuator/health" "200" \
  "q-im 미기동 또는 포트(8082) 확인. MariaDB 접속 여부 먼저 확인"

check_http "IdO actuator/health" \
  "${IDO_URL}/actuator/health" "200" \
  "ido 미기동 또는 포트(8083) 확인. Q-IM/Q-Sign 모두 healthy 상태여야 함"

check_http "Agency-Stub actuator/health" \
  "${AGENCY_URL}/actuator/health" "200" \
  "agency-stub 미기동 또는 포트(8084) 확인 (PoC 전용 서비스)"

# ── [3] Actuator Prometheus 메트릭 엔드포인트 ─────────────────
section "Prometheus 메트릭 엔드포인트"

check_http_json_key "Q-Sign /actuator/prometheus" \
  "${QSIGN_URL}/actuator/prometheus" \
  "http_server_requests_seconds" "" \
  "spring.management.endpoints.prometheus 활성화 여부 확인"

check_http_json_key "Q-IM /actuator/prometheus" \
  "${QIM_URL}/actuator/prometheus" \
  "http_server_requests_seconds"

check_http_json_key "IdO /actuator/prometheus" \
  "${IDO_URL}/actuator/prometheus" \
  "http_server_requests_seconds"

# ── [4] IdO → Q-IM 내부 API 인증 확인 ────────────────────────
section "내부 API 인증"

# Q-IM internal API: 올바른 키로 호출 시 400/404/200 (인증 통과, 비즈니스 오류는 무관)
# 잘못된 키로 호출 시 401/403
QIM_INTERNAL_HEALTH=$(curl -sf -o /dev/null -w "%{http_code}" \
  --connect-timeout "$TIMEOUT" \
  --max-time "$TIMEOUT" \
  -H "X-Internal-Api-Key: ${QIM_API_KEY}" \
  "${QIM_URL}/actuator/health" 2>/dev/null) || QIM_INTERNAL_HEALTH="000"

# actuator/health는 인증 없이도 200이므로, 내부 API path를 테스트
# Q-IM의 /api/v1/internal/* 경로 호출 — 인증은 통과, 404는 정상 (엔드포인트 없어도 인증 성공)
QIM_AUTH_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
  --connect-timeout "$TIMEOUT" \
  --max-time "$TIMEOUT" \
  -H "X-Internal-Api-Key: ${QIM_API_KEY}" \
  -H "Content-Type: application/json" \
  "${QIM_URL}/api/v1/internal/health-probe" 2>/dev/null) || QIM_AUTH_STATUS="000"

if [[ "$QIM_AUTH_STATUS" != "401" && "$QIM_AUTH_STATUS" != "403" && "$QIM_AUTH_STATUS" != "000" ]]; then
  pass "Q-IM 내부 API 인증 통과 (HTTP $QIM_AUTH_STATUS — 401/403 아님)"
elif [[ "$QIM_AUTH_STATUS" == "401" || "$QIM_AUTH_STATUS" == "403" ]]; then
  fail "Q-IM 내부 API 인증 실패 (HTTP $QIM_AUTH_STATUS)" \
    "QIM_INTERNAL_API_KEY vs IDO_QIM_INTERNAL_API_KEY 값 불일치 확인. .env 파일의 QIM_INTERNAL_API_KEY 확인"
else
  fail "Q-IM 내부 API 호출 불가 (HTTP $QIM_AUTH_STATUS)" \
    "Q-IM 서비스 기동 여부 확인"
fi

# 잘못된 키로 Q-IM 호출 시 401/403 반환 확인
BAD_AUTH_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
  --connect-timeout "$TIMEOUT" \
  --max-time "$TIMEOUT" \
  -H "X-Internal-Api-Key: invalid-key-should-fail" \
  "${QIM_URL}/api/v1/internal/health-probe" 2>/dev/null) || BAD_AUTH_STATUS="000"

if [[ "$BAD_AUTH_STATUS" == "401" || "$BAD_AUTH_STATUS" == "403" ]]; then
  pass "Q-IM 내부 API 잘못된 키 거부 (HTTP $BAD_AUTH_STATUS — 보안 정상)"
elif [[ "$BAD_AUTH_STATUS" == "000" ]]; then
  skip "Q-IM 내부 API 보안 검증 (Q-IM 미응답)"
else
  fail "Q-IM 내부 API 보안 문제" \
    "잘못된 키에 HTTP $BAD_AUTH_STATUS 반환 — 인증 미들웨어 확인 필요"
fi

# ── [5] IdO 핵심 API 최소 검증 ───────────────────────────────
section "IdO 핵심 API"

# actuator/health 상세 (liveness/readiness probe)
check_http "IdO liveness probe" \
  "${IDO_URL}/actuator/health/liveness" "200"

check_http "IdO readiness probe" \
  "${IDO_URL}/actuator/health/readiness" "200" \
  "readiness 실패 시 DB/Redis/Kafka 연결 상태 actuator/health 상세 확인"

# ── [6] Frontend/Nginx 확인 ───────────────────────────────────
section "Frontend/Nginx"

FE_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
  --connect-timeout "$TIMEOUT" \
  --max-time "$TIMEOUT" \
  "$FE_URL" 2>/dev/null) || FE_STATUS="000"

if [[ "$FE_STATUS" == "200" ]]; then
  pass "Frontend(Nginx) 접근 ($FE_URL → HTTP $FE_STATUS)"
elif [[ "$FE_STATUS" == "000" ]]; then
  skip "Frontend(Nginx) 접근 불가 — optionB 프로파일 미기동일 수 있음"
else
  fail "Frontend(Nginx) 접근" \
    "HTTP $FE_STATUS | onepass-react 컨테이너 상태, Dockerfile.optionB 빌드 확인"
fi

# Nginx health 엔드포인트 (nginx.conf에 /nginx-health 설정된 경우)
if [[ "$FE_STATUS" != "000" ]]; then
  check_http "Nginx /nginx-health" \
    "${FE_URL}/nginx-health" "200" \
    "nginx.conf에 /nginx-health 경로 설정 확인"
fi

# ── [7] Prometheus/Grafana ────────────────────────────────────
if $SKIP_MONITORING; then
  section "모니터링 [SKIP]"
  skip "모니터링 체크 건너뜀 (--skip-monitoring)"
else
  section "Prometheus / Grafana"

  # Prometheus
  PROM_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
    --connect-timeout "$TIMEOUT" \
    --max-time "$TIMEOUT" \
    "$PROMETHEUS_URL" 2>/dev/null) || PROM_STATUS="000"

  if [[ "$PROM_STATUS" == "200" || "$PROM_STATUS" == "302" ]]; then
    pass "Prometheus 접근 ($PROMETHEUS_URL → HTTP $PROM_STATUS)"
    # 스크레이프 타겟 up 여부 확인
    TARGETS=$(curl -sf \
      --connect-timeout "$TIMEOUT" \
      "${PROMETHEUS_URL}/api/v1/targets" 2>/dev/null) || TARGETS=""
    for job in "q-sign" "q-im" "ido"; do
      if echo "$TARGETS" | grep -q "\"job\":\"$job\""; then
        # health 확인
        HEALTH=$(echo "$TARGETS" | python3 -c "
import sys, json
d = json.load(sys.stdin)
jobs = [t for t in d.get('data',{}).get('activeTargets',[]) if t.get('labels',{}).get('job','')=='$job']
print(jobs[0]['health'] if jobs else 'unknown')
" 2>/dev/null) || HEALTH="unknown"
        if [[ "$HEALTH" == "up" ]]; then
          pass "Prometheus scrape: $job (health=up)"
        else
          fail "Prometheus scrape: $job (health=$HEALTH)" \
            "앱 서비스 기동 여부 및 /actuator/prometheus 노출 확인"
        fi
      else
        fail "Prometheus scrape target 없음: $job" \
          "prometheus.yml의 scrape_configs 타겟 및 onepass-net 네트워크 확인"
      fi
    done
  elif [[ "$PROM_STATUS" == "000" ]]; then
    skip "Prometheus 미기동 (--profile monitoring 포함 여부 확인)"
  else
    fail "Prometheus 접근" "HTTP $PROM_STATUS"
  fi

  # Grafana
  GRAFANA_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
    --connect-timeout "$TIMEOUT" \
    --max-time "$TIMEOUT" \
    "$GRAFANA_URL" 2>/dev/null) || GRAFANA_STATUS="000"

  if [[ "$GRAFANA_STATUS" == "200" || "$GRAFANA_STATUS" == "302" ]]; then
    pass "Grafana 접근 ($GRAFANA_URL → HTTP $GRAFANA_STATUS)"
  elif [[ "$GRAFANA_STATUS" == "000" ]]; then
    skip "Grafana 미기동 (--profile monitoring 포함 여부 확인)"
  else
    fail "Grafana 접근" "HTTP $GRAFANA_STATUS"
  fi
fi

# ── 결과 요약 ─────────────────────────────────────────────────
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e " 결과 요약: ${GREEN}PASS=$PASS${RESET}  ${RED}FAIL=$FAIL${RESET}  ${YELLOW}SKIP=$SKIP${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

if [[ $FAIL -gt 0 ]]; then
  echo ""
  echo -e "${RED}${BOLD}실패 항목 목록:${RESET}"
  for f in "${FAILURES[@]}"; do
    echo -e "  ${RED}✗${RESET} $f"
  done
  echo ""
  echo -e "${RED}Smoke Test 실패 ($FAIL개 항목) — 운영 배포 전 반드시 해결하세요.${RESET}"
  exit 1
else
  echo ""
  echo -e "${GREEN}${BOLD}모든 Smoke Test 통과 — 배포 준비 완료.${RESET}"
  exit 0
fi
