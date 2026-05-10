#!/bin/bash
# EzAuth 로컬 개발용 설정으로 전환
# 사용법: bash scripts/ezauth-to-local.sh

set -e

AUTHBIZ="public/ezauth/site/portal/authBiz.json"
WEBPACK="webpack.config.js"

echo "=== EzAuth 로컬 개발용 설정으로 전환 ==="
echo ""

# 1. authBiz.json: baseUrl을 상대 URL로 변경
if grep -q '"baseUrl": "https://www.smes.go.kr/"' "$AUTHBIZ"; then
  sed -i 's|"baseUrl": "https://www.smes.go.kr/"|"baseUrl": "/"|' "$AUTHBIZ"
  echo "[1/2] authBiz.json → 로컬 프록시용 URL로 변경 완료"
else
  echo "[1/2] authBiz.json → 이미 로컬 설정 (변경 불필요)"
fi

# 2. webpack.config.js: bizezauth-api-dev proxy 블록 추가
if grep -q "bizezauth-api-dev" "$WEBPACK"; then
  echo "[2/2] webpack.config.js → 이미 로컬 설정 (변경 불필요)"
else
  # '/api/ext' 블록의 마지막 }, 뒤에 proxy 블록 추가
  sed -i "/'\/api\/ext'/,/},/ {
    /},/ {
      a\\
\t\t\t'/bizezauth-api-dev': {\\
\t\t\t\ttarget: 'https://www.smes.go.kr',\\
\t\t\t\tchangeOrigin: true,\\
\t\t\t\tsecure: false,\\
\t\t\t\tonProxyReq(proxyReq) {\\
\t\t\t\t\tproxyReq.removeHeader('origin');\\
\t\t\t\t\tproxyReq.removeHeader('referer');\\
\t\t\t\t},\\
\t\t\t},
    }
  }" "$WEBPACK"
  echo "[2/2] webpack.config.js → proxy 추가 완료"
fi

echo ""
echo "=== 변경 결과 확인 ==="
echo ""
echo "--- authBiz.json (server.baseUrl) ---"
grep "baseUrl" "$AUTHBIZ" | head -1
echo ""
echo "--- webpack.config.js (proxy에 bizezauth 있어야 함) ---"
if grep -q "bizezauth-api-dev" "$WEBPACK"; then
  echo "✓ proxy 정상 추가됨"
else
  echo "⚠ 경고: proxy가 추가되지 않았습니다. 수동으로 확인하세요."
fi
echo ""
echo "=== 완료. webpack dev server를 재시작하세요 (yarn start) ==="
