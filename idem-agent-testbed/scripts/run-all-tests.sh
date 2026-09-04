#!/usr/bin/env bash
##############################################################################
# run-all-tests.sh — OnePass Agent 멀티 WAS 자동화 검증 스크립트
#
# 목적: 모든 WAS 컨테이너에 대해 Agent 위빙 여부, SSO 검증 흐름을 자동 테스트
#
# 사용법:
#   ./scripts/run-all-tests.sh [옵션]
#
# 옵션:
#   --only=tomcat-9,tomcat-10    특정 WAS만 테스트
#   --skip=wildfly               특정 WAS 제외
#   --no-rebuild                 이미지 재빌드 건너뜀
#   --keep-running               테스트 후 컨테이너 유지
#   --report=<경로>              결과 리포트 저장 경로 (기본: ./test-results/)
#   --timeout=<초>               단일 테스트 타임아웃 (기본: 120)
#
# 종료 코드:
#   0 — 모든 테스트 통과
#   1 — 일부 테스트 실패 (상세 내용은 ./test-results/ 참조)
#   2 — 환경 오류 (Docker, Agent JAR 없음 등)
##############################################################################

set -euo pipefail

# ─── 색상 출력 ────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ─── 경로 설정 ────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTBED_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$TESTBED_DIR/docker"
CONFIG_DIR="$TESTBED_DIR/config"
REPORT_DIR="${REPORT_DIR:-$TESTBED_DIR/test-results}"
AGENT_JAR_DIR="$TESTBED_DIR/agent"
COMPOSE_FILE="$DOCKER_DIR/docker-compose.yml"

# ─── 기본 설정 ────────────────────────────────────────────────────────────────
TIMEOUT=${TIMEOUT:-120}
REBUILD=true
KEEP_RUNNING=false
SKIP_WAS=""
ONLY_WAS=""
MOCK_URL="http://localhost:18080"

# ─── WAS 목록 (서비스명 → 포트) ──────────────────────────────────────────────
declare -A WAS_PORTS=(
    ["tomcat-8"]="18083"
    ["tomcat-9"]="18084"
    ["tomcat-10"]="18085"
    ["wildfly"]="18086"
    ["jetty"]="18087"
    ["springboot-embedded"]="18088"
    ["unknown-fallback"]="18089"
)

declare -A WAS_DESC=(
    ["tomcat-8"]="Tomcat 8.5 + JDK 8  (Servlet 3.1, javax)"
    ["tomcat-9"]="Tomcat 9   + JDK 11 (Servlet 4.0, javax)"
    ["tomcat-10"]="Tomcat 10  + JDK 17 (Servlet 6.0, jakarta)"
    ["wildfly"]="WildFly 27 + JDK 17 (Jakarta EE 10)"
    ["jetty"]="Jetty 11   + JDK 11 (Servlet 5.0, jakarta)"
    ["springboot-embedded"]="Spring Boot 3.x Embedded Tomcat"
    ["unknown-fallback"]="Unknown/Fallback (GenericFilter)"
)

# ─── 인수 파싱 ────────────────────────────────────────────────────────────────
for arg in "$@"; do
    case "$arg" in
        --no-rebuild)       REBUILD=false ;;
        --keep-running)     KEEP_RUNNING=true ;;
        --only=*)           ONLY_WAS="${arg#--only=}" ;;
        --skip=*)           SKIP_WAS="${arg#--skip=}" ;;
        --report=*)         REPORT_DIR="${arg#--report=}" ;;
        --timeout=*)        TIMEOUT="${arg#--timeout=}" ;;
        --help|-h)
            sed -n '3,20p' "$0" | sed 's/^# //' | sed 's/^#//'
            exit 0
            ;;
    esac
done

# ─── 유틸리티 함수 ────────────────────────────────────────────────────────────
log_info()    { echo -e "${BLUE}[INFO ]${NC} $*"; }
log_ok()      { echo -e "${GREEN}[PASS ]${NC} $*"; }
log_fail()    { echo -e "${RED}[FAIL ]${NC} $*"; }
log_warn()    { echo -e "${YELLOW}[WARN ]${NC} $*"; }
log_section() { echo -e "\n${BOLD}${CYAN}━━━ $* ━━━${NC}"; }

# ─── 전제 조건 확인 ───────────────────────────────────────────────────────────
check_prerequisites() {
    log_section "전제 조건 확인"

    local errors=0

    # Docker 확인
    if ! command -v docker &>/dev/null; then
        log_fail "Docker가 설치되어 있지 않습니다."
        ((errors++))
    else
        log_ok "Docker: $(docker --version | head -1)"
    fi

    # Docker Compose 확인
    if ! docker compose version &>/dev/null && ! command -v docker-compose &>/dev/null; then
        log_fail "Docker Compose가 설치되어 있지 않습니다."
        ((errors++))
    else
        log_ok "Docker Compose: $(docker compose version 2>/dev/null || docker-compose --version)"
    fi

    # curl 확인
    if ! command -v curl &>/dev/null; then
        log_fail "curl이 설치되어 있지 않습니다."
        ((errors++))
    else
        log_ok "curl: $(curl --version | head -1)"
    fi

    # Agent JAR 확인
    local agent_jar
    agent_jar=$(find "$AGENT_JAR_DIR" -name "idem-agent-*-all.jar" 2>/dev/null | sort -V | tail -1)
    if [[ -z "$agent_jar" ]]; then
        log_warn "Agent JAR를 찾을 수 없습니다: $AGENT_JAR_DIR"
        log_warn "다음 명령으로 빌드하세요:"
        log_warn "  cd $(dirname "$TESTBED_DIR") && ./gradlew :idem-agent:agentJar"
        log_warn "  cp idem-agent/build/libs/idem-agent-*-all.jar $AGENT_JAR_DIR/"
        log_warn "※ Agent JAR 없이 컨테이너 기동만 테스트합니다."
    else
        log_ok "Agent JAR: $(basename "$agent_jar")"
    fi

    # config 확인
    if [[ ! -f "$CONFIG_DIR/onepass-agent.properties" ]]; then
        log_warn "설정 파일이 없습니다. 기본값으로 생성합니다..."
        cp "$CONFIG_DIR/onepass-agent.properties.template" "$CONFIG_DIR/onepass-agent.properties" 2>/dev/null || true
    fi

    if [[ $errors -gt 0 ]]; then
        log_fail "전제 조건 오류 $errors개. 환경을 확인하세요."
        exit 2
    fi
}

# ─── WAS 목록 필터링 ──────────────────────────────────────────────────────────
get_target_was_list() {
    local all_was=("tomcat-8" "tomcat-9" "tomcat-10" "wildfly" "jetty" "springboot-embedded" "unknown-fallback")
    local targets=()

    for was in "${all_was[@]}"; do
        # --only 필터
        if [[ -n "$ONLY_WAS" ]] && ! echo "$ONLY_WAS" | grep -q "$was"; then
            continue
        fi
        # --skip 필터
        if [[ -n "$SKIP_WAS" ]] && echo "$SKIP_WAS" | grep -q "$was"; then
            log_info "건너뜀: $was"
            continue
        fi
        targets+=("$was")
    done

    echo "${targets[@]}"
}

# ─── 컨테이너 기동 ────────────────────────────────────────────────────────────
start_containers() {
    local targets=("$@")
    log_section "컨테이너 기동"

    local compose_cmd
    if docker compose version &>/dev/null; then
        compose_cmd="docker compose"
    else
        compose_cmd="docker-compose"
    fi

    cd "$DOCKER_DIR"

    if [[ "$REBUILD" == "true" ]]; then
        log_info "이미지 빌드 중 (이 작업은 처음에 수 분 소요될 수 있습니다)..."
        $compose_cmd build --no-cache mock-onepass-server "${targets[@]}" 2>&1 | \
            grep -E "(Step|Successfully|Error|error)" || true
    fi

    log_info "서비스 기동: mock-onepass-server + ${targets[*]}"
    $compose_cmd up -d mock-onepass-server "${targets[@]}"

    # Mock 서버 헬스체크
    log_info "Mock OnePass 서버 헬스체크 대기 중..."
    local retries=30
    until curl -sf "$MOCK_URL/health" &>/dev/null || [[ $retries -eq 0 ]]; do
        sleep 2
        ((retries--))
    done

    if curl -sf "$MOCK_URL/health" &>/dev/null; then
        log_ok "Mock OnePass 서버 준비 완료"
    else
        log_fail "Mock OnePass 서버가 응답하지 않습니다."
        exit 2
    fi
}

# ─── WAS 준비 대기 ────────────────────────────────────────────────────────────
wait_for_was() {
    local was_name="$1"
    local port="$2"
    local timeout="$3"

    local elapsed=0
    local interval=3

    while [[ $elapsed -lt $timeout ]]; do
        if curl -sf "http://localhost:$port/" &>/dev/null || \
           curl -sf "http://localhost:$port/health" &>/dev/null || \
           curl -sf "http://localhost:$port/index.jsp" &>/dev/null; then
            return 0
        fi
        # TCP 연결 가능 여부도 확인
        if (echo >/dev/tcp/localhost/$port) &>/dev/null; then
            return 0
        fi
        sleep $interval
        ((elapsed += interval))
    done
    return 1
}

# ─── 개별 WAS 테스트 ──────────────────────────────────────────────────────────
test_was() {
    local was_name="$1"
    local port="${WAS_PORTS[$was_name]}"
    local desc="${WAS_DESC[$was_name]}"
    local result_file="$REPORT_DIR/${was_name}.log"
    local pass=0
    local fail=0

    log_section "테스트: $was_name ($desc)"
    mkdir -p "$REPORT_DIR"
    echo "=== $was_name 테스트 결과 ===" > "$result_file"
    echo "시작: $(date '+%Y-%m-%d %H:%M:%S')" >> "$result_file"

    # 1. WAS 기동 대기
    log_info "[$was_name] WAS 준비 대기 (최대 ${TIMEOUT}초)..."
    if wait_for_was "$was_name" "$port" "$TIMEOUT"; then
        log_ok "[$was_name] WAS 기동 확인 (port $port)"
        echo "PASS: WAS 기동 확인" >> "$result_file"
        ((pass++))
    else
        log_fail "[$was_name] WAS가 ${TIMEOUT}초 이내에 응답하지 않음"
        echo "FAIL: WAS 기동 타임아웃 (${TIMEOUT}s)" >> "$result_file"
        ((fail++))
        echo "RESULT: FAIL (pass=$pass, fail=$fail)" >> "$result_file"
        return 1
    fi

    # 2. Agent 로그에서 위빙 성공 확인
    log_info "[$was_name] Agent 위빙 로그 확인..."
    local compose_cmd
    compose_cmd=$(docker compose version &>/dev/null && echo "docker compose" || echo "docker-compose")

    local agent_log
    agent_log=$(cd "$DOCKER_DIR" && $compose_cmd logs "$was_name" 2>&1 | head -200)

    if echo "$agent_log" | grep -q "OnePass Agent"; then
        log_ok "[$was_name] Agent 초기화 확인"
        echo "PASS: Agent 초기화 로그 감지" >> "$result_file"
        ((pass++))
    else
        log_warn "[$was_name] Agent 로그 미감지 (JAR 미탑재이거나 로그 레벨 문제)"
        echo "WARN: Agent 초기화 로그 없음" >> "$result_file"
    fi

    if echo "$agent_log" | grep -qiE "weaving|install.*strategy|WasDetector"; then
        log_ok "[$was_name] 위빙 전략 설치 확인"
        echo "PASS: 위빙 전략 설치 로그 감지" >> "$result_file"
        ((pass++))
    else
        log_warn "[$was_name] 위빙 전략 로그 미감지"
        echo "WARN: 위빙 전략 로그 없음" >> "$result_file"
    fi

    # 3. 인증 없는 요청 → 401 또는 302 리다이렉트 확인
    log_info "[$was_name] 인증 없는 요청 차단 테스트..."
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        "http://localhost:$port/sample-webapp/protected" \
        --max-time 10 2>/dev/null || echo "000")

    if [[ "$http_code" == "401" || "$http_code" == "302" || "$http_code" == "403" ]]; then
        log_ok "[$was_name] 미인증 요청 차단 확인 (HTTP $http_code)"
        echo "PASS: 미인증 요청 차단 (HTTP $http_code)" >> "$result_file"
        ((pass++))
    elif [[ "$http_code" == "404" ]]; then
        log_warn "[$was_name] 샘플 앱 미배포 (HTTP 404) — 위빙 검증 건너뜀"
        echo "WARN: 샘플 앱 없음 (HTTP 404)" >> "$result_file"
    else
        log_fail "[$was_name] 예상 외 응답 코드: HTTP $http_code"
        echo "FAIL: 예상 외 응답 (HTTP $http_code)" >> "$result_file"
        ((fail++))
    fi

    # 4. 유효 토큰으로 요청 → 200 확인
    log_info "[$was_name] 유효 토큰 인증 테스트..."
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        "http://localhost:$port/sample-webapp/protected" \
        -H "Authorization: Bearer test-token-001" \
        --max-time 10 2>/dev/null || echo "000")

    if [[ "$http_code" == "200" ]]; then
        log_ok "[$was_name] 유효 토큰 인증 통과 (HTTP 200)"
        echo "PASS: 유효 토큰 인증 (HTTP 200)" >> "$result_file"
        ((pass++))
    elif [[ "$http_code" == "404" ]]; then
        log_warn "[$was_name] 샘플 앱 미배포 — 토큰 인증 테스트 건너뜀"
        echo "WARN: 샘플 앱 없음, 토큰 테스트 스킵" >> "$result_file"
    else
        log_fail "[$was_name] 유효 토큰 요청 실패 (HTTP $http_code)"
        echo "FAIL: 유효 토큰 인증 실패 (HTTP $http_code)" >> "$result_file"
        ((fail++))
    fi

    # 5. 무효 토큰으로 요청 → 401/302 확인
    log_info "[$was_name] 무효 토큰 거부 테스트..."
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        "http://localhost:$port/sample-webapp/protected" \
        -H "Authorization: Bearer invalid-token-xyz" \
        --max-time 10 2>/dev/null || echo "000")

    if [[ "$http_code" == "401" || "$http_code" == "302" || "$http_code" == "403" ]]; then
        log_ok "[$was_name] 무효 토큰 거부 확인 (HTTP $http_code)"
        echo "PASS: 무효 토큰 거부 (HTTP $http_code)" >> "$result_file"
        ((pass++))
    elif [[ "$http_code" == "404" ]]; then
        log_warn "[$was_name] 샘플 앱 미배포 — 무효 토큰 테스트 건너뜀"
        echo "WARN: 샘플 앱 없음, 무효 토큰 스킵" >> "$result_file"
    else
        log_fail "[$was_name] 무효 토큰에 대한 예상 외 응답 (HTTP $http_code)"
        echo "FAIL: 무효 토큰 처리 이상 (HTTP $http_code)" >> "$result_file"
        ((fail++))
    fi

    # 6. Mock 서버 통계에서 해당 WAS의 호출 확인
    log_info "[$was_name] Mock 서버 검증 호출 통계 확인..."
    local stats
    stats=$(curl -sf "$MOCK_URL/admin/stats" 2>/dev/null || echo "{}")
    if echo "$stats" | grep -q '"totalRequests"'; then
        log_ok "[$was_name] Mock 서버 통계 확인 가능"
        echo "INFO: Mock 서버 통계: $stats" >> "$result_file"
    fi

    # 결과 요약
    echo "" >> "$result_file"
    echo "종료: $(date '+%Y-%m-%d %H:%M:%S')" >> "$result_file"
    echo "RESULT: pass=$pass, fail=$fail" >> "$result_file"

    if [[ $fail -eq 0 ]]; then
        log_ok "[$was_name] ✅ 모든 테스트 통과 (pass=$pass)"
        return 0
    else
        log_fail "[$was_name] ❌ 실패 $fail건 (pass=$pass, fail=$fail)"
        return 1
    fi
}

# ─── 컨테이너 정리 ────────────────────────────────────────────────────────────
cleanup_containers() {
    if [[ "$KEEP_RUNNING" == "false" ]]; then
        log_section "컨테이너 정리"
        local compose_cmd
        compose_cmd=$(docker compose version &>/dev/null && echo "docker compose" || echo "docker-compose")
        cd "$DOCKER_DIR"
        $compose_cmd down --remove-orphans
        log_ok "컨테이너 종료 완료"
    else
        log_warn "컨테이너 유지 중 (--keep-running). 수동 종료: docker compose down"
    fi
}

# ─── 리포트 생성 ──────────────────────────────────────────────────────────────
generate_report() {
    local pass_count="$1"
    local fail_count="$2"
    local total="$3"
    local report_file="$REPORT_DIR/summary.txt"

    log_section "테스트 결과 요약"

    {
        echo "============================================="
        echo " OnePass Agent 멀티 WAS 테스트 결과"
        echo " $(date '+%Y-%m-%d %H:%M:%S')"
        echo "============================================="
        echo ""
        echo " 전체: $total | 통과: $pass_count | 실패: $fail_count"
        echo ""
        echo " WAS별 결과:"
        for was in "${!WAS_PORTS[@]}"; do
            local log_file="$REPORT_DIR/${was}.log"
            if [[ -f "$log_file" ]]; then
                local result
                result=$(grep "^RESULT:" "$log_file" | tail -1)
                echo "   $was: $result"
            fi
        done
        echo ""
        echo " 상세 로그: $REPORT_DIR/"
        echo "============================================="
    } | tee "$report_file"

    if [[ $fail_count -eq 0 ]]; then
        echo -e "\n${GREEN}${BOLD}✅ 모든 WAS 테스트 통과!${NC}\n"
    else
        echo -e "\n${RED}${BOLD}❌ $fail_count개 WAS에서 테스트 실패${NC}\n"
    fi
}

# ─── 메인 ────────────────────────────────────────────────────────────────────
main() {
    echo -e "${BOLD}${CYAN}"
    echo "  ╔══════════════════════════════════════════════════════════╗"
    echo "  ║      OnePass Agent 멀티 WAS 자동화 테스트베드            ║"
    echo "  ║      $(date '+%Y-%m-%d %H:%M:%S')                            ║"
    echo "  ╚══════════════════════════════════════════════════════════╝"
    echo -e "${NC}"

    mkdir -p "$REPORT_DIR"

    check_prerequisites

    # 테스트 대상 WAS 목록 결정
    IFS=' ' read -r -a targets <<< "$(get_target_was_list)"

    if [[ ${#targets[@]} -eq 0 ]]; then
        log_warn "테스트할 WAS가 없습니다."
        exit 0
    fi

    log_info "테스트 대상: ${targets[*]}"

    # 컨테이너 기동
    start_containers "${targets[@]}"

    # 각 WAS 테스트 실행
    local pass_count=0
    local fail_count=0
    declare -A was_results

    for was_name in "${targets[@]}"; do
        if test_was "$was_name"; then
            was_results["$was_name"]="PASS"
            ((pass_count++))
        else
            was_results["$was_name"]="FAIL"
            ((fail_count++))
        fi
        echo ""
    done

    # 정리
    cleanup_containers

    # 리포트
    generate_report "$pass_count" "$fail_count" "${#targets[@]}"

    # 종료 코드
    if [[ $fail_count -gt 0 ]]; then
        exit 1
    fi
}

main "$@"
