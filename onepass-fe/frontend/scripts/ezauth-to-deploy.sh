#!/bin/bash
# EzAuth 배포용 설정으로 전환
# 사용법: bash scripts/ezauth-to-deploy.sh

set -e

AUTHBIZ="public/ezauth/site/portal/authBiz.json"
WEBPACK="webpack.config.js"

echo "=== EzAuth 배포용 설정으로 전환 ==="
echo ""

# 1. authBiz.json: baseUrl을 절대 URL로 변경
if grep -q '"baseUrl": "/"' "$AUTHBIZ"; then
  sed -i 's|"baseUrl": "/"|"baseUrl": "https://www.smes.go.kr/"|' "$AUTHBIZ"
  echo "[1/2] authBiz.json → 배포용 URL로 변경 완료"
else
  echo "[1/2] authBiz.json → 이미 배포용 설정 (변경 불필요)"
fi

# 2. webpack.config.js: bizezauth-api-dev proxy 블록 제거
if grep -q "bizezauth-api-dev" "$WEBPACK"; then
  # proxy 블록 전체 제거 ('/bizezauth-api-dev' 부터 다음 }, 까지)
  sed -i "/'\/bizezauth-api-dev'/,/^\t\t\t},$/d" "$WEBPACK"
  echo "[2/2] webpack.config.js → proxy 제거 완료"
else
  echo "[2/2] webpack.config.js → 이미 배포용 설정 (변경 불필요)"
fi

echo ""
echo "=== 변경 결과 확인 ==="
echo ""
echo "--- authBiz.json (server.baseUrl) ---"
grep "baseUrl" "$AUTHBIZ" | head -1
echo ""
echo "--- webpack.config.js (proxy에 bizezauth 없어야 함) ---"
if grep -q "bizezauth-api-dev" "$WEBPACK"; then
  echo "⚠ 경고: proxy가 아직 남아있습니다. 수동으로 확인하세요."
else
  echo "✓ proxy 정상 제거됨"
fi
echo ""
echo "=== 완료 ==="
