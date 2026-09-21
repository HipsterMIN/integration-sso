# 공개 전 점검 (D0) — 저장소 Public 전환

> 2026-09-21. 결정: Actions 결제 문제로 저장소를 예정(S9)보다 앞당겨 **Public** 으로 전환하고 라이선스는 **Apache-2.0**.
> 이 문서는 전환 전에 한 일과 **사용자가 결정·실행해야 할 것**을 나눈다. 시크릿 값은 어디에도 적지 않는다.

## 1. 이 PR 에서 한 것

| 항목 | 내용 |
|---|---|
| 라이선스 | `LICENSE`(Apache-2.0 원문), `NOTICE`(저작권·SigNoz 파생 고지), `THIRD-PARTY-NOTICES.md`(직접 의존성·런타임 구성요소·벤더 SDK 고지) |
| 정책 문서 | `SECURITY.md`(취약점 신고 경로), `CONTRIBUTING.md`(브랜치·훅·DCO·금지 사항) |
| 메타데이터 | Dockerfile 5개 `licenses="Proprietary"` → `Apache-2.0`, 콘솔 `package.json` `license: ISC` → `Apache-2.0`, README 배지·라이선스 절 |
| 라이선스 충돌 제거 | 콘솔의 `@grafana/data`(AGPL-3.0) — 소스에서 미사용이라 의존성에서 제거(yarn.lock 갱신) |
| 추적 해제 | `dump.rdb`(Redis 로컬 덤프) — 저장소에서 제거하고 `.gitignore` |
| 비밀 스캔 | gitleaks 8.21 — 현재 트리 65건(빌드 산출물 제외) 전수 확인: 전부 문서 예시값·테스트 전용 키(주석으로 "실제 키 아님" 명시)·placeholder·해시. **살아 있는 시크릿은 현재 트리에 없다** |

## 2. 사용자가 해야 할 것 — 전환 전

### 2.1 자격증명 교체 (필수)

git **히스토리**에는 과거 커밋의 실제 벤더 자격증명이 남아 있다(S5b 에서 파일은 제거했지만 히스토리는 그대로). Public 전환은 곧 히스토리 공개이므로 **전환 전에 교체**해야 한다.

| 무엇 | 어디에 있었나 (히스토리 경로) | 조치 |
|---|---|---|
| Any-ID client API key · KMS `app_key` · `secret-code` | `ido/src/main/resources/config/anyid/*.json`, `ido/src/main/resources/sso-adaptor-conf-local.properties`, `ido/src/main/resources/application-local.yml` | 행안부 Any-ID 운영기관 창구에 **재발급 요청** |
| Any-ID 벤더 JS 에 박힌 키 | `ido/src/main/resources/static/anyid/js/vendor.js` | 위와 같은 재발급에 포함 |
| NICE 본인확인 client id/secret · OACX provider key | `ido/src/main/resources/application-local.yml` 등 | NICE 평가정보 담당자에게 **재발급 요청** |
| 개발용 기관 API 키 시드 | `idem-hub/.../V8__seed_agency_api_key_and_fix_webhook.sql` (해시만) — 원문은 옛 문서에 있었음 | 운영 DB 에서 시드 기관(AGENCY001 등) 키를 **재발급**하거나 시드 행 삭제 |
| Slack/PagerDuty 웹훅 | `.env.monitoring.example` 은 placeholder — 실제 값은 로컬 `.env` 에만 있었는지 확인 | 실제 URL 이 커밋된 적 있으면 재발급 |

교체 완료를 이 절 아래에 날짜와 함께 기록한다(값은 적지 않는다).

### 2.2 히스토리 정리 — 결정 필요

자격증명을 교체하면 유출된 값은 무효가 된다. 그래도 히스토리에서 지우고 싶다면 `git filter-repo` 로 아래 경로를 제거하고 **force-push + 모든 clone 재생성**이 필요하다(PR 링크·커밋 해시가 바뀐다). 교체만 하고 히스토리는 두는 것도 합리적 선택이다.

```bash
pip install git-filter-repo
git filter-repo --invert-paths \
  --path ido/src/main/resources/config/anyid \
  --path ido/src/main/resources/sso-adaptor-conf-local.properties \
  --path ido/src/main/resources/application-local.yml \
  --path ido/src/main/resources/static/anyid \
  --path onepass-fe/frontend/.env \
  --path onepass-agent-testbed/config/onepass-agent.properties
```

### 2.3 고객·내부 문서 — 결정 필요

아래는 시크릿은 아니지만 **첫 적용 고객(중소벤처기업부·SMES) 맥락의 제안서·인수인계·내부 설계 산출물**이다. 공개 저장소에 둘지, 비공개 저장소로 옮길지 결정한다. 이 PR 은 삭제하지 않았다.

| 경로 | 내용 | 크기 |
|---|---|---|
| `docs/proposal/` | 고객 제안서(pptx·docx·md), 개발 계획 | 336K |
| `docs/smep-handover/` | SMEP 이관 분석·설득 자료(docx·md) | 288K |
| `docs/internal/` | 내부 개발 가이드·API 레퍼런스·데이터플로 docx | 1.9M |
| `docs/_archive/` | 2026-05-22 이전 문서 보관 | 748K |
| `wiki/docx/`, `wiki/design/` | v0.8.8 상세 설계서 docx·ADR | (wiki 980K) |
| README·문서 곳곳의 `smes.go.kr`·중기부 언급 66개 파일 | 고객 고유값 — S9(개명 마무리)에서 정리 예정 | — |

권고: `docs/proposal`·`docs/smep-handover` 는 비공개로 옮기고, 나머지는 두되 S9 에서 고객 고유값을 걷어낸다.

### 2.4 GitHub 설정 (전환 직후)

- Settings → Code security: **Secret scanning + Push protection** 켜기 (Public 은 무료)
- Settings → Actions: 기본 `GITHUB_TOKEN` 권한 read-only, fork PR 워크플로 승인 필요로
- Branch protection(`main`): PR 필수 + `Build & Unit Test`·`k6 Smoke Test` 필수 체크
- 자체 호스팅 러너는 **Public 저장소에서 쓰지 않는다**(fork PR 코드가 러너에서 실행될 수 있다) — `CI_HEAVY_RUNNER` 변수는 비워 둔다

## 3. 하지 않은 것

- 소스 파일별 SPDX 헤더 — Apache-2.0 에 필수 아님. 원하면 Spotless `licenseHeader` 로 일괄 적용 가능(전 파일 재포맷이라 별도 PR)
- `CODEOWNERS`·이슈/PR 템플릿·Dependabot — S9 패키징에서
- 벤더 SDK 는 원래부터 저장소 밖(`~/.idem/vendor-libs`) — 변경 없음

## 4. 라이선스 메모

- MariaDB Connector/J(LGPL-2.1) 는 `mariadb` 레거시 프로파일 런타임 의존이며 S9 에서 제거한다. Javassist 는 삼중 라이선스 중 Apache-2.0 으로 사용.
- Redis 이미지는 7.2(BSD-3) 로 고정 — 7.4 이후는 RSALv2/SSPL. k6 는 AGPL 이지만 CI 도구로만 실행되고 제품에 포함되지 않는다.
- 플러그인은 코어 SPI 에 링크되며 Apache-2.0 아래서는 사설(KR 에디션) 플러그인도 문제없다.
