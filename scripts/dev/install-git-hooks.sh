#!/usr/bin/env bash
# 로컬 git 훅 설치 — .githooks/ 를 core.hooksPath 로 지정한다.
#   pre-commit : Spotless 포맷팅 (origin/main 대비 변경된 Java 파일만)
#   pre-push   : 변경된 모듈(과 의존 모듈)의 테스트만 실행
# 되돌리기: git config --unset core.hooksPath
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
chmod +x .githooks/pre-commit .githooks/pre-push
git config core.hooksPath .githooks
echo "설치 완료: core.hooksPath=$(git config core.hooksPath)"
echo "  pre-commit → ./gradlew spotlessApply (SKIP_SPOTLESS=1 로 건너뜀)"
echo "  pre-push   → 변경 모듈 테스트 (SKIP_TESTS=1 / SKIP_IT=1 로 건너뜀, PREPUSH_DRY_RUN=1 .githooks/pre-push 로 계획 확인)"
