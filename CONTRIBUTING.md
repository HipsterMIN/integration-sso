# Contributing

Idem 에 기여해 주셔서 감사합니다. 짧게 지켜 주실 것만 적습니다.

## 라이선스

기여물은 Apache License 2.0 §5 에 따라 프로젝트와 같은 조건으로 제공되는 것으로 봅니다. 별도 CLA 는 없습니다.
커밋에 `Signed-off-by:`(DCO, `git commit -s`) 를 붙여 주시면 고맙습니다.

## 브랜치·PR

- 개발 브랜치는 `shipster`, 대상은 `main` 입니다 (`CLAUDE.md`, `docs/local-dev-workflow.md`).
- 훅 설치: `scripts/dev/install-git-hooks.sh` — pre-commit 이 Spotless 포맷을, pre-push 가 변경 모듈 테스트를 돌립니다.
- PR 은 한 단계(플랜의 S/D 항목 하나)씩. 무엇을 검증했고 무엇을 못 했는지 본문에 적어 주십시오.

## 하지 말 것

- 벤더 SDK jar·자격증명·고객 고유값을 커밋하지 않습니다. 벤더 코드는 `plugins/` 아래 플러그인으로만 넣습니다 (`docs/vendor-plugin-plan.md`).
- 코어(`idem-hub`·`idem-gate`·`idem-registry`·`idem-authz`·`idem-common`)에 벤더·고객 문자열을 넣으면 `GeneralizationGuardTest` 가 막습니다.
- "장애 시 허용" 코드를 넣지 않습니다. 실패는 거부 + 감사입니다 (`docs/generalization-plan.md` D2).

## 보안 문제

`SECURITY.md` 를 따라 비공개로 알려 주십시오.
