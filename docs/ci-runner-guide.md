# CI 러너 운영 가이드 — 사용량 절감과 자체 호스팅 러너

> 작성 2026-09-04 · 배경: 프라이빗 저장소 Actions 분 한도 초과로 main push 실행의 Docker Build 6종·k6·Sonar 잡이 러너 배정 없이 실패(run 33842068829)

## 1. 트리거 정리 (적용 완료)

| 워크플로 | 종전 | 현재 |
|---|---|---|
| `ci.yml` | push: main/develop/genspark_ai_developer/shipster + PR: main/develop | **push: main** + **PR: main** |
| `nogo-full.yml` | push: shipster + PR: main/develop | **삭제 (2026-09-08)** — build-and-test 와 완전 중복, 호스트 러너 9개 잡이 PR 마다 빨간 체크를 만들었음 |
| `nogo-quick.yml` | 수동 | **삭제 (2026-09-08)** — 5월 출시 판정용 일회성 검증. 해당 검증은 단위 테스트에 포함 |

효과: shipster 변경 1건당 실행이 4개(ci push, ci PR, nogo push, nogo PR)에서 2개로 줄고, 같은 ref 의 concurrency 취소로 진행 중 실행이 유실되는 문제가 사라진다. k6 스모크는 shipster push 가 없어졌으므로 PR 에서도 실행한다.

작업 절차는 그대로다: shipster 에 커밋·푸시 → PR 생성 시점에 CI 실행 → 머지 → main push 에서 Docker Build·GHCR 발행.

## 2. 무거운 잡을 자체 호스팅 러너로 (선택)

자체 호스팅 러너에서 실행된 시간은 Actions 분에 **계산되지 않는다**. `ci.yml` 의 다음 잡은 저장소 변수 `CI_HEAVY_RUNNER` 가 있으면 그 라벨의 러너에서, 없으면 GitHub 호스트 러너에서 돈다.

| 잡 | 필요 조건 |
|---|---|
| Build & Unit Test (PR 게이트) | JDK 는 `actions/setup-java` 가 내려받음. `run:` 스텝이 bash 문법이라 Linux/WSL2 권장 |
| k6 Smoke Test (PR 게이트) | `services:` 컨테이너(Redis·PostgreSQL·Kafka)를 쓰므로 **Docker 가 있는 Linux/WSL2 러너 필수**. k6 설치 스텝은 `apt` + `sudo` 를 쓰므로 러너 계정에 passwordless sudo 가 있거나 k6 를 미리 설치해 둔다(설치돼 있으면 스텝이 건너뜀) |
| ↳ 판정 기준 | `k6/scenarios/smoke.js` 의 `thresholds.checks: rate==1` — check 하나라도 ✗ 면 k6 종료 코드 ≠ 0 으로 잡 실패(2026-09-06). 그 전에는 `http_req_failed`/`http_req_duration` 임계값만 있어 `ci-check` check 가 매번 ✗ 인데도 잡이 초록으로 표시됐다 |
| Docker Build (Multistage) 6종 | Docker 데몬 + buildx. **Linux 또는 WSL2 Ubuntu** 권장 (Windows 네이티브 러너는 Linux 이미지 빌드 불가) |
| OWASP Dependency-Check (수동 전용) | JDK 21 + NVD API 도달 가능한 망. **2026-09-07 야간 cron 제거** — Actions 탭 → CI → Run workflow 로만 실행. 로컬 실행: `NVD_API_KEY=<키> ./gradlew dependencyCheckAggregate` (리포트 `build/reports/dependency-check/`). 일상 의존성 점검은 main push 의 Docker Build 안 Trivy 이미지 스캔이 담당 (PR 의 Trivy 저장소 스캔·Sonar 잡은 2026-09-08 삭제) |
| ↳ 스캔 범위 | `dependencyCheckAggregate` 로 서브프로젝트 전체 의존성을 한 리포트로 스캔(2026-09-07). 그 전의 `dependencyCheckAnalyze` 는 루트 프로젝트(의존성 0개)만 봐서 "0건" 이 무검사였다(run 34049253507). SARIF 업로드에는 잡 `permissions.security-events: write` 와 저장소 Code scanning 활성(프라이빗은 GHAS 필요)이 모두 필요하며, 저장소 변수 `CODE_SCANNING_ENABLED=true` 일 때만 업로드 스텝이 실행된다 |
| ↳ 캐시 전송 생략 | 자체 호스팅 러너(`runner.environment != 'github-hosted'`)에서는 setup-java 의 gradle 캐시, `Gradle 캐시`, `OWASP NVD 캐시`, `Docker 레이어 캐시` 스텝을 건너뛴다. `~/.gradle`, `~/.gradle/dependency-check-data`, `/tmp/.buildx-cache-*` 가 머신에 남아 있어 전송이 불필요하고, 느린 망에서는 500MB 전송이 잡을 수십 분 지연시켰다(2026-09-07, run 34079947660 Java 설정 단계 10분 이상 정지) |
| ↳ WSL2 러너 주의 | Java 가 IPv6 를 먼저 시도해 NVD·RetireJS 다운로드가 `Network is unreachable` 로 실패할 수 있어 OWASP 스텝은 `JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true` 를 건다(run 34075241830) |

즉 변수 하나로 PR 게이트 3종(Build & Unit Test·k6·Frontend Build) + 무거운 잡 2종(Docker Build·수동 OWASP)이 모두 자체 호스팅으로 간다(2026-09-08 정리). 호스트 러너에 남은 잡은 없다.

**2026-09-08 워크플로 정리**: 로컬 git 훅(`docs/local-dev-workflow.md`)이 포맷·변경 모듈 테스트를 맡게 되면서 CI 는 "다른 환경에서 재현되는가" 확인으로 축소했다. 삭제 — `nogo-full.yml`, `nogo-quick.yml`, `ci.yml` 의 `trivy-pr-scan`(리포트 전용, 이미지 스캔과 중복), `code-quality`(SONAR_TOKEN 없음). 이동 — `frontend-check` 를 자체 호스팅 러너로(`cache: yarn` 은 호스트 러너에서만). 남은 잡: PR = Build & Unit Test → k6 → Frontend Build, main push = + Docker Build 6종, 수동 = OWASP·CD.

### 2.1 러너 등록 (WSL2 Ubuntu 기준, 약 5분)

1. 저장소 → Settings → Actions → Runners → **New self-hosted runner** → Linux x64 선택.
2. 화면에 나오는 다운로드·설정 명령을 WSL2 셸에서 그대로 실행한다. `./config.sh` 실행 시 라벨은 기본값(`self-hosted, Linux, X64`)을 두면 된다.
3. 서비스로 상시 실행: `sudo ./svc.sh install && sudo ./svc.sh start`.
4. Docker Desktop 의 WSL2 통합을 켜서 WSL2 안에서 `docker info` 가 되는지 확인한다.
5. 저장소 → Settings → Secrets and variables → Actions → **Variables** → `CI_HEAVY_RUNNER` = `self-hosted` 추가.

이후 main push 의 Docker Build 와 수동 OWASP 가 그 머신에서 실행된다. 변수를 지우면 즉시 호스트 러너로 돌아간다.

### 2.2 주의

- 자체 호스팅 러너는 **프라이빗 저장소에서만** 쓴다. 저장소를 공개하면 포크 PR 이 러너에서 코드를 실행할 수 있으므로 변수를 제거하고 러너를 내린다.
- 러너 머신의 Docker 레이어·Gradle 캐시는 워크플로 밖에 남는다. 디스크가 차면 `docker system prune` 으로 정리한다.
- OWASP 잡의 프로세스 정리 스텝(`pkill -f org.gradle`)은 호스트 러너에서만 실행되도록 조건을 걸었다. 자체 호스팅 머신의 다른 Gradle 프로세스를 죽이지 않기 위해서다.

## 3. 공개 전환 시

퍼블릭 저장소는 Actions 분과 아티팩트·캐시 저장소가 무제한이라 1·2절이 모두 불필요해진다. 공개 준비 항목은 `docs/open-source-readiness.md` 참조.
