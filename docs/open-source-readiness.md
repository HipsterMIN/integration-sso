# 오픈 소스 공개 준비 점검 (Open-Source Readiness)

> 작성 2026-09-04 · 스캔 도구: gitleaks 8.21.2 (전체 히스토리 187 커밋), 수작업 인벤토리 · 상태: **공개 불가 — 차단 항목 4건 해소 전**

> **진행 (2026-09-05, P0)**: B1 평문 자격증명을 환경변수 플레이스홀더로 교체(파일 기준 해소, 히스토리에는 잔존 → 스냅샷 이관 시 소멸, 벤더 키 교체 요청 필요) · B4 `idem-console/frontend/.env` untrack · B2 중 `xecure7.jar` 삭제. 나머지 B2·B3 는 `vendor-plugin-plan.md` P2·P3.

## 1. 결론

지금 상태로 저장소를 공개하면 **벤더 자격증명과 배포 권한이 없는 벤더 SDK가 그대로 노출**된다. 아래 차단 항목(§2)을 해소한 뒤, 히스토리를 버린 **새 공개 저장소로 스냅샷 이관**(§4)하는 방식을 권한다. 기존 프라이빗 저장소는 이력 보존용으로 유지한다.

## 2. 차단 항목 (반드시 해소)

| # | 항목 | 위치 | 조치 |
|---|---|---|---|
| B1 | **벤더 개발 자격증명이 파일에 직접 기재** — AnyID(client-id/secret/api-key, app-key), NICE(secret-code), AES 공유키, 기관 subject secret | `idem-hub/src/main/resources/application-local.yml` (7개 키) · 히스토리상 최초 커밋 3f243fa 이후 전 구간 | 즉시 환경변수/`.env`(gitignore)로 이동. 벤더에 **키 교체(rotate)** 요청. 히스토리에 남으므로 §4 필수 |
| B2 | **재배포 권한 없는 벤더 SDK 바이너리** — OACX-SDK, anyid-* 4종, xecure7, kdist-api, pid_api (+ 동봉된 commons/gson 등) | `idem-hub/libs/*.jar` (14개, 약 2.7MB) | 저장소에서 제거. 빌드는 `libs/` 부재 시에도 통과하도록 벤더 어댑터를 KR 에디션 플러그인(별도 프라이빗 아티팩트)으로 분리 |
| B3 | **벤더 프런트 자산** — AnyID JS 번들(RSA private key 블록 포함, 1MB), EzAuth 간편인증 위젯 109파일 + 가이드 PDF | `idem-hub/src/main/resources/static/anyid/**`, `idem-console/frontend/public/ezauth/**` | 제거 후 런타임 로드(벤더 CDN/사설 저장소)로 전환. 라이선스 확인 전에는 공개 불가 |
| B4 | **추적 중인 `.env`** — IDO_API_KEY, EXT_API_KEY, AES_GCM_KEY 등 로컬 개발값 | `idem-console/frontend/.env` | `git rm --cached`, `.env.example` 만 유지. 값이 개발용이라도 히스토리 정리 대상 |

## 3. 정리 항목 (공개 전 결정·정리)

| # | 항목 | 규모 | 조치 |
|---|---|---|---|
| C1 | 고객 고유 정보 — `*.smes.go.kr` 호스트 79파일, 중기원패스 50파일, 중소벤처기업부 29파일, 기관 시드 데이터(V13) | docs·wiki·infra·FE | 공개판에서는 사례 소개 수준만 남기고 호스트·기관명·내부 절차는 제거. 제안서·인수인계 문서(`docs/proposal/`, `docs/smep-handover/`, `outputs/`)는 프라이빗 저장소에만 둔다 |
| C2 | Slack Webhook URL 형식의 값 | `infra/docker/.env.monitoring.example:44` | 실제 URL이면 폐기·교체, 예시면 `https://hooks.slack.com/services/XXX` 플레이스홀더로 |
| C3 | 문서 예시 토큰·API 키 (gitleaks 146건 중 대부분) | `docs/**`, `README.md`, SDK README, 테스트 코드 | 예시임이 분명한 값이나, `EXAMPLE_…` 형태로 바꿔 스캐너 오탐을 없앤다 |
| C4 | PoC 기관 시드의 API 키 평문 주석 + 해시 | `idem-hub/.../V8__seed_agency_api_key_and_fix_webhook.sql` | 공개판 마이그레이션에서 시드 제거 또는 재생성 |
| C5 | 라이선스 부재 — `LICENSE` 없음, Dockerfile 라벨 `Proprietary` 5건 | 루트, `*/Dockerfile` | 라이선스 선택(권장 Apache-2.0), `LICENSE`·`NOTICE` 추가, 라벨 교체. 벤더 SDK 분리 후에만 가능 |
| C6 | 바이너리 문서 — docx 15건, pptx 2건 | `docs/**`, `wiki/docx` | 공개판에서 제외하거나 md 로 대체 |
| C7 | 자체 호스팅 러너 | `CI_HEAVY_RUNNER` 변수 | 공개 시 변수 제거·러너 해제 (포크 PR 코드 실행 위험) |
| C8 | 개명 4·5단계 미완 — Java 패키지 `kr.go.smes`, 런타임 식별자 | `docs/naming.md` §3 | 공개 자체의 차단 요소는 아니나 첫인상에 영향. 스냅샷 이관 전 완료 권장 |

## 4. 권장 절차 — 스냅샷 이관

히스토리 rewrite(`git filter-repo`)는 협업자 전원의 재클론과 PR 이력 단절이 따르고, 누락 시 그대로 유출된다. 대신:

1. 프라이빗 저장소에서 B1~B4, C1~C6 를 해소한 커밋을 만든다 (`shipster` → `main`).
2. 그 `main` 트리를 **히스토리 없이** 새 공개 저장소(예: `idem`)의 첫 커밋으로 올린다. 이 시점에 gitleaks 를 다시 돌려 0건을 확인한다.
3. 공개 저장소에서 GitHub Actions 를 켠다 (분·아티팩트 무제한). 야간 OWASP·Docker Build 도 그대로 이관된다.
4. 프라이빗 저장소는 KR 에디션 플러그인(벤더 SDK·기관 설정)과 이력 보관용으로 남긴다. 이후 개발은 공개 저장소에서 하고, KR 에디션은 공개 코어를 의존성으로 쓴다.

## 5. 스캔 요약 (gitleaks, 히스토리 전체)

| 규칙 | 건수 | 판정 |
|---|---|---|
| generic-api-key | 97 | 대부분 문서 예시·테스트 픽스처. **실제 값: B1, B4, C4** |
| curl-auth-header | 40 | 문서·스크립트의 curl 예시. 오탐 |
| private-key | 4 | 벤더 JS 번들 내 키(B3) + 배포 계획 문서 예시(현재 삭제된 파일) |
| jwt | 3 | 테스트 픽스처·설계 문서 예시. 오탐 |
| slack-webhook-url | 2 | C2 |

값은 본 문서에 기록하지 않았다. 원본 리포트는 작업 세션에만 존재하며 저장소에 커밋하지 않는다.
