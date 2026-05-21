#!/usr/bin/env bash
##############################################################################
# replace-agent.sh — 실행 중인 테스트베드 컨테이너의 Agent JAR를 교체
#
# 사용법:
#   ./scripts/replace-agent.sh [WAS_서비스명] [JAR_경로]
#
# 예시:
#   # 최신 빌드 JAR를 모든 컨테이너에 배포
#   ./scripts/replace-agent.sh
#
#   # 특정 WAS에만 배포
#   ./scripts/replace-agent.sh tomcat-9
#
#   # 특정 JAR 지정
#   ./scripts/replace-agent.sh tomcat-9 /path/to/onepass-agent-1.0.0-all.jar
#
# 동작:
#   1. 지정 JAR(또는 최신 빌드 JAR)를 ./agent/ 디렉토리에 복사
#   2. 심볼릭 링크 onepass-agent-current.jar 갱신
#   3. 대상 컨테이너 재시작
##############################################################################

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'
BOLD='\033[1m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTBED_DIR="$(dirname "$SCRIPT_DIR")"
DOCKER_DIR="$TESTBED_DIR/docker"
AGENT_DIR="$TESTBED_DIR/agent"
PROJECT_ROOT="$(dirname "$TESTBED_DIR")"

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_ok()   { echo -e "${GREEN}[OK  ]${NC} $*"; }
log_fail() { echo -e "${RED}[FAIL]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

# 인수 파싱
TARGET_WAS="${1:-all}"
CUSTOM_JAR="${2:-}"

# Docker Compose 명령어 확인
COMPOSE_CMD="docker compose"
if ! docker compose version &>/dev/null 2>&1; then
    if command -v docker-compose &>/dev/null; then
        COMPOSE_CMD="docker-compose"
    else
        log_fail "Docker Compose를 찾을 수 없습니다."
        exit 1
    fi
fi

# ─── Agent JAR 경로 결정 ──────────────────────────────────────────────────────
find_agent_jar() {
    if [[ -n "$CUSTOM_JAR" ]]; then
        if [[ ! -f "$CUSTOM_JAR" ]]; then
            log_fail "지정한 JAR 파일이 없습니다: $CUSTOM_JAR"
            exit 1
        fi
        echo "$CUSTOM_JAR"
        return
    fi

    # onepass-agent 빌드 결과에서 최신 fat-JAR 탐색
    local build_jar
    build_jar=$(find "$PROJECT_ROOT/onepass-agent/build/libs" \
        -name "onepass-agent-*-all.jar" 2>/dev/null | sort -V | tail -1)

    if [[ -n "$build_jar" ]]; then
        echo "$build_jar"
        return
    fi

    # ./agent/ 디렉토리에서 탐색
    local agent_jar
    agent_jar=$(find "$AGENT_DIR" -name "onepass-agent-*-all.jar" 2>/dev/null | sort -V | tail -1)

    if [[ -n "$agent_jar" ]]; then
        echo "$agent_jar"
        return
    fi

    echo ""
}

# ─── JAR 배포 ─────────────────────────────────────────────────────────────────
deploy_jar() {
    local source_jar="$1"

    mkdir -p "$AGENT_DIR"

    # agent/ 디렉토리로 복사
    local jar_name
    jar_name=$(basename "$source_jar")
    local dest_jar="$AGENT_DIR/$jar_name"

    if [[ "$source_jar" != "$dest_jar" ]]; then
        cp "$source_jar" "$dest_jar"
        log_ok "JAR 복사: $jar_name → $AGENT_DIR/"
    fi

    # 심볼릭 링크 갱신
    local symlink="$AGENT_DIR/onepass-agent-current.jar"
    ln -sf "$jar_name" "$symlink"
    log_ok "심볼릭 링크 갱신: onepass-agent-current.jar → $jar_name"

    echo "$jar_name"
}

# ─── 컨테이너 재시작 ──────────────────────────────────────────────────────────
restart_containers() {
    local target="$1"
    local all_was=("tomcat-8" "tomcat-9" "tomcat-10" "wildfly" "jetty" "springboot-embedded" "unknown-fallback")

    cd "$DOCKER_DIR"

    local targets_to_restart=()
    if [[ "$target" == "all" ]]; then
        targets_to_restart=("${all_was[@]}")
    else
        # 콤마 구분 목록 지원
        IFS=',' read -r -a targets_to_restart <<< "$target"
    fi

    log_info "재시작 대상: ${targets_to_restart[*]}"

    for was in "${targets_to_restart[@]}"; do
        # 실행 중인지 확인
        local state
        state=$($COMPOSE_CMD ps --status running "$was" 2>/dev/null | grep "$was" | wc -l)

        if [[ $state -gt 0 ]]; then
            log_info "재시작 중: $was"
            $COMPOSE_CMD restart "$was"
            log_ok "$was 재시작 완료"
        else
            log_warn "$was 컨테이너가 실행 중이지 않습니다. (기동: $COMPOSE_CMD up -d $was)"
        fi
    done
}

# ─── 메인 ────────────────────────────────────────────────────────────────────
main() {
    echo -e "${BOLD}OnePass Agent 교체 스크립트${NC}"
    echo "대상: $TARGET_WAS"
    echo ""

    # JAR 탐색
    local source_jar
    source_jar=$(find_agent_jar)

    if [[ -z "$source_jar" ]]; then
        log_warn "Agent JAR를 찾을 수 없습니다."
        log_warn "먼저 빌드를 실행하세요:"
        log_warn "  cd $(realpath "$PROJECT_ROOT") && ./gradlew :onepass-agent:agentJar"
        log_warn "  또는 JAR 경로를 직접 지정:"
        log_warn "  $0 all /path/to/onepass-agent-xxx-all.jar"
        exit 1
    fi

    log_info "사용할 JAR: $source_jar"
    log_info "크기: $(du -h "$source_jar" | cut -f1)"

    # 배포
    local jar_name
    jar_name=$(deploy_jar "$source_jar")

    # 컨테이너 재시작
    restart_containers "$TARGET_WAS"

    echo ""
    log_ok "Agent 교체 완료: $jar_name"
    echo ""
    echo -e "${BLUE}로그 확인:${NC}"
    echo "  cd $DOCKER_DIR && $COMPOSE_CMD logs -f [WAS명]"
    echo ""
    echo -e "${BLUE}테스트 실행:${NC}"
    echo "  $SCRIPT_DIR/run-all-tests.sh --no-rebuild --keep-running"
}

main "$@"
