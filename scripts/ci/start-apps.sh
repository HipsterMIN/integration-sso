#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# registry·authz·gate·hub 를 boot jar 로 띄운다 — CI 설치본 스모크(ci.yml smoke-test 잡)의 첫 기동과 "운영 DB 복구 리허설"(시험 항목 G-5)의
# 재기동이 같은 레시피를 쓴다. 환경변수 이름은 compose.install.yml 과 같다(D3 — 설치본 계약). 비밀(IDEM_REGISTRY_INTERNAL_API_KEY ·
# IDEM_AUTHZ_INTERNAL_API_KEY · Keycloak client secret …)은 잡 환경에서 그대로 받는다.
#
#   LOG_DIR=/tmp/idem   WAIT=90(×3초)   JAR_DIR 는 저장소 루트(각 모듈 build/libs)
#   hub 만 기본 프로파일: 스모크가 Mock 제공자(IDEM_PLUGINS_MOCK_AUTH_ENABLED=true)를 쓰는데 prod 는 fail-secure 가드가 Mock 을 거부한다.
#   hub 의 prod 프로파일은 ci.yml 의 "hub prod 프로파일 기동" 단계가 Mock 없이 따로 확인한다.
# ═══════════════════════════════════════════════════════════════════════════
set -uo pipefail
LOG_DIR="${LOG_DIR:-/tmp/idem}"
WAIT="${WAIT:-90}"
mkdir -p "$LOG_DIR"
jar() { find "$1/build/libs" -name "$1-*.jar" -not -name "*-plain.jar" -not -name "*-test-fixtures.jar" | head -1; }
for m in idem-registry idem-authz idem-gate idem-hub; do [ -n "$(jar "$m")" ] || { echo "❌ $m 의 boot jar 가 없습니다 (build/libs)"; exit 1; }; done

# x-app-common
export DB_HOST="${DB_HOST:-localhost}" DB_PORT="${DB_PORT:-5432}" DB_NAME="${DB_NAME:-idem}" DB_USERNAME="${DB_USERNAME:-idem}" DB_PASSWORD="${DB_PASSWORD:-idem}" DB_SSLMODE="${DB_SSLMODE:-disable}"
export REDIS_HOST="${REDIS_HOST:-localhost}" REDIS_PORT="${REDIS_PORT:-6379}" IDEM_KAFKA_ENABLED="${IDEM_KAFKA_ENABLED:-false}" TZ="${TZ:-Asia/Seoul}" SPRING_PROFILES_ACTIVE=prod   # 1.0.1: 설치본과 같은 prod 프로파일
# registry
IDEM_REGISTRY_DB_HOST="$DB_HOST" IDEM_REGISTRY_DB_PORT="$DB_PORT" IDEM_REGISTRY_DB_NAME="$DB_NAME" IDEM_REGISTRY_DB_SCHEMA=idem_registry IDEM_REGISTRY_DB_USERNAME="$DB_USERNAME" IDEM_REGISTRY_DB_PASSWORD="$DB_PASSWORD" IDEM_REGISTRY_DB_SSL=false \
  nohup java -Xmx512m -jar "$(jar idem-registry)" --server.port=8082 > "$LOG_DIR/registry.log" 2>&1 &
# authz
nohup java -Xmx384m -jar "$(jar idem-authz)" --server.port=8086 > "$LOG_DIR/authz.log" 2>&1 &
# gate
IDEM_HUB_BASE_URL=http://localhost:8083 KEYCLOAK_URL=http://localhost:8088 KEYCLOAK_REALM=idem IDEM_GATE_KEYCLOAK_CLIENT_ID=idem-gate \
  IDEM_GATE_KEYCLOAK_REDIRECT_URI=http://localhost:8081/api/v1/oidc/keycloak/callback IDEM_PUBLIC_URL_GATE=http://localhost:8081 \
  nohup java -Xmx512m -jar "$(jar idem-gate)" --server.port=8081 > "$LOG_DIR/gate.log" 2>&1 &
# hub
IDEM_REGISTRY_BASE_URL=http://localhost:8082 IDEM_GATE_BASE_URL=http://localhost:8081 IDEM_HUB_AUTHZ_BASE_URL=http://localhost:8086 \
  IDEM_HUB_REGISTRY_INTERNAL_API_KEY="${IDEM_REGISTRY_INTERNAL_API_KEY:-}" IDEM_HUB_AUTHZ_INTERNAL_API_KEY="${IDEM_AUTHZ_INTERNAL_API_KEY:-}" \
  IDEM_HUB_BROKER_MODE=qsign KEYCLOAK_BASE_URL=http://localhost:8088 KEYCLOAK_REALM=idem KEYCLOAK_CLIENT_ID=idem-hub \
  KEYCLOAK_REDIRECT_URI=http://localhost:8083/api/v1/broker/callback IDEM_PUBLIC_URL_GATE=http://localhost:8081 \
  IDEM_PLATFORM_CODE=IDEM IDEM_PLUGINS_NICE_OACX_ENABLED=false IDEM_PLUGINS_ANYID_ENABLED=false \
  IDEM_HUB_KMS_ENABLED=true IDEM_HUB_KMS_PROVIDER=local \
  CORS_ORIGIN_DEV=http://localhost:3001 CORS_ORIGIN_PROD=http://localhost:3001 \
  SPRING_PROFILES_ACTIVE=default \
  nohup java -Xmx768m -jar "$(jar idem-hub)" --server.port=8083 > "$LOG_DIR/hub.log" 2>&1 &

# 연결 거부(curl 종료 7)가 스크립트를 끝내지 않도록 || true
for i in $(seq 1 "$WAIT"); do
  r=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/actuator/health || true)
  a=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8086/actuator/health || true)
  g=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health || true)
  h=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health || true)
  if [ "$r$a$g$h" = "200200200200" ]; then echo "✅ registry·authz·gate·hub UP (${i}x3s)"; exit 0; fi
  sleep 3
done
echo "❌ 기동 실패: registry=$r authz=$a gate=$g hub=$h"
for n in registry authz gate hub; do echo "──── $n ────"; grep -m5 -iE "error|exception|Caused by" "$LOG_DIR/$n.log" | cut -c1-400; done
exit 1
