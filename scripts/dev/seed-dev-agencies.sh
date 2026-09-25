#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# 개발용 기관 시드 (D3) — 운영 마이그레이션에서 뺀 PoC 기관을 로컬 hub 에 관리 API 로 만든다.
#
#   scripts/dev/seed-dev-agencies.sh            # AGENCY_STUB_001 (tenant-sample 기본 기관)
#   scripts/dev/seed-dev-agencies.sh --all      # + BRIDGE_001 · APACHEGATE_001 · SSO_001 · STRICT_L3 · CHAOS_001 (패턴 시나리오)
#
#   HUB_URL      hub 주소 (기본 http://localhost:8083)
#   SAMPLE_URL   tenant-sample 주소 (기본 http://localhost:8084) — 콜백·엔드포인트에 쓴다
#   ADMIN_ID     X-Admin-Id (기본 dev-seed; 관리자 인증은 S7)
#
# 각 기관의 API 키는 hub 가 새로 만들어 이 스크립트가 한 번 출력한다(저장하지 않는다).
# tenant-sample 은 AGENCY_STUB_IDO_API_KEY=<출력값> 으로 띄운다 (종전 고정 키 stub-api-key-dev-001 은 없다).
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

HUB_URL="${HUB_URL:-http://localhost:8083}"
SAMPLE_URL="${SAMPLE_URL:-http://localhost:8084}"
ADMIN_ID="${ADMIN_ID:-dev-seed}"
ALL=false
[ "${1:-}" = "--all" ] && ALL=true

need() { command -v "$1" >/dev/null 2>&1 || { echo "필요한 명령이 없습니다: $1" >&2; exit 1; }; }
need curl

put_profile() { # code json
  local code="$1" body="$2" out status
  out=$(curl -sS -o /tmp/seed-$code.json -w '%{http_code}' -X PUT "$HUB_URL/api/v1/admin/services/$code/profile" \
        -H 'Content-Type: application/json' -H "X-Admin-Id: $ADMIN_ID" -H 'X-Change-Reason: dev seed' -d "$body")
  status="$out"
  if [ "$status" != "200" ] && [ "$status" != "201" ]; then
    echo "✗ $code 프로파일 PUT 실패 (HTTP $status): $(cat /tmp/seed-$code.json)" >&2; exit 1
  fi
  echo "✓ $code 프로파일 저장"
}

rotate_key() { # code
  local code="$1" key
  key=$(curl -sS -X POST "$HUB_URL/api/v1/admin/agencies/$code/rotate-key" -H "X-Admin-Id: $ADMIN_ID" \
        | sed -n 's/.*"newApiKey"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  if [ -z "$key" ]; then echo "✗ $code API 키 발급 실패" >&2; exit 1; fi
  echo "  API 키 ($code, 지금만 표시): $key"
}

attrs='["name_masked","mobile_masked","nationality_type","birth_year"]'

# 1. AGENCY_STUB_001 — tenant-sample 기본 기관 (DIRECT)
put_profile AGENCY_STUB_001 "$(cat <<JSON
{"schemaVersion":1,
 "service":{"code":"AGENCY_STUB_001","name":"테스트 기관 (개발 stub)","status":"ACTIVE"},
 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["$SAMPLE_URL/entry","$SAMPLE_URL/agency/callback"],
                                          "ssoEntry":"$SAMPLE_URL/sso-entry"}},
 "identity":{"attributes":$attrs},
 "policy":{"minAuthLevel":"L1","policyVersion":"1.0"},
 "limits":{"tps":200,"daily":1000000}}
JSON
)"
rotate_key AGENCY_STUB_001

if $ALL; then
  put_profile AGENCY_BRIDGE_001 "$(cat <<JSON
{"schemaVersion":1,"service":{"code":"AGENCY_BRIDGE_001","name":"BRIDGE 패턴 기관","status":"ACTIVE"},
 "protocol":{"type":"BRIDGE","endpoints":{"callbackWhitelist":["$SAMPLE_URL/entry"],"bridge":"$SAMPLE_URL/bridge/handoff"}},
 "identity":{"attributes":$attrs},"policy":{"minAuthLevel":"L1","policyVersion":"1.0"}}
JSON
)"; rotate_key AGENCY_BRIDGE_001
  put_profile AGENCY_APACHEGATE_001 "$(cat <<JSON
{"schemaVersion":1,"service":{"code":"AGENCY_APACHEGATE_001","name":"APACHE_GATE 패턴 기관","status":"ACTIVE"},
 "protocol":{"type":"APACHE_GATE","endpoints":{"callbackWhitelist":["$SAMPLE_URL/entry"],"apacheGate":"$SAMPLE_URL/gw/internal/sso-session"}},
 "identity":{"attributes":$attrs},"policy":{"minAuthLevel":"L1","policyVersion":"1.0"}}
JSON
)"; rotate_key AGENCY_APACHEGATE_001
  put_profile AGENCY_SSO_001 "$(cat <<JSON
{"schemaVersion":1,"service":{"code":"AGENCY_SSO_001","name":"INTERNAL_SSO 패턴 기관","status":"ACTIVE"},
 "protocol":{"type":"INTERNAL_SSO","endpoints":{"callbackWhitelist":["$SAMPLE_URL/entry"],"ssoDomain":"localhost"}},
 "identity":{"attributes":$attrs},"policy":{"minAuthLevel":"L1","policyVersion":"1.0"}}
JSON
)"; rotate_key AGENCY_SSO_001
  put_profile AGENCY_STRICT_L3 "$(cat <<JSON
{"schemaVersion":1,"service":{"code":"AGENCY_STRICT_L3","name":"DIRECT + L3 고보안 기관","status":"ACTIVE"},
 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["$SAMPLE_URL/entry"]}},
 "identity":{"attributes":$attrs},"policy":{"minAuthLevel":"L3","policyVersion":"1.0"}}
JSON
)"; rotate_key AGENCY_STRICT_L3
  put_profile AGENCY_CHAOS_001 "$(cat <<JSON
{"schemaVersion":1,"service":{"code":"AGENCY_CHAOS_001","name":"CHAOS 시나리오 기관","status":"ACTIVE"},
 "protocol":{"type":"DIRECT","endpoints":{"callbackWhitelist":["$SAMPLE_URL/entry"]}},
 "identity":{"attributes":$attrs},"policy":{"minAuthLevel":"L1","policyVersion":"1.0"},"limits":{"tps":5,"daily":100}}
JSON
)"; rotate_key AGENCY_CHAOS_001
fi
echo "완료 — hub: $HUB_URL"
