# Idem 1.0 설치 매뉴얼 (초안)

> 대상: 설치자·시험원. 이 문서는 절차의 **순서와 완료 판정**만 적고, 값의 뜻·생성 규칙은 `docs/install-inputs.md`, compose 상세는 `docs/install.md`, Helm 상세는 `infra/helm/idem/README.md` 에 있다. 문서 안의 명령은 CI 설치본 스모크(`scripts/ci/install-smoke.sh`)와 로컬 리허설에서 그대로 실행한 것이다.

## 1. 전제

| 항목 | 요구 |
|---|---|
| 설치 형태 | (A) Docker Compose 단일 설치본 — 서버 1대 · (B) Kubernetes Helm 차트 — 바깥 PostgreSQL·Redis 필요 |
| OS / 런타임 | Linux x86-64. (A) Docker Engine 24+ 와 Docker Compose ≥ 2.17, (B) Kubernetes(매니페스트는 1.29 스키마로 검증) · Helm 3 |
| 자원 | (A) 4 vCPU · 8 GB RAM · 20 GB 디스크 이상. (B) 앱 5종 요청 합계 약 1.5 vCPU · 3.5 GB (`values.yaml` resources) |
| 데이터 | PostgreSQL 16 (compose 는 포함) · Redis 7 (포함). Kafka 없음 |
| 네트워크 | 브라우저 → gate(공개 URL, TLS 종료는 리버스 프록시/Ingress) · 기관 RP → gate · 기관 서버 ← hub 웹훅(아웃바운드) · KR 에디션: hub → 본인확인 벤더 API |
| 에디션 | core(Idem SSO + IM) / kr(코어 + KR 에디션 — SMES 회원·NICE/Any-ID 플러그인·회원 포털). kr 이미지는 벤더 SDK 를 빌드 때 넣는다 |
| 소프트웨어 버전 | Idem 1.1.2 (태그 `v1.1.2`), Keycloak 24.0, Spring Boot 3.5.16 / Java 21 (이미지 안) |

## 2. 입력값 준비

`docs/install-inputs.md` 의 표대로 비밀 21종을 만든다(`openssl rand`, CAST 키는 `openssl genpkey ed25519`). compose 는 `infra/docker/install.env`, Helm 은 Secret `idem-db-secret`·`idem-app-secrets`. 공개 URL(gate·hub·console) 을 정한다 — gate URL 이 표준 OIDC issuer 의 베이스(`{gate}/realms/idem`)다.

완료 판정: `grep -E '^[A-Z0-9_]+=$' install.env` 결과가 비어 있다(모든 키가 채워짐).

## 3. 설치

### 3.1 (A) Docker Compose

```bash
cp infra/docker/install.env.example infra/docker/install.env      # 값 채우기 (§2)
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml up -d --build
# KR 에디션: install.env 에 IDEM_EDITION=kr, IDEM_VENDOR_LIBS_DIR=<SDK jar 디렉터리> 를 넣고 --profile kr 를 더한다
```

기동 순서는 compose 가 헬스체크로 잡는다(postgres → redis → keycloak → registry·authz → gate → hub → console). 첫 기동은 Keycloak realm import 와 Flyway 마이그레이션 때문에 2~3분 걸린다.

### 3.2 (B) Kubernetes Helm

```bash
kubectl create ns idem
kubectl -n idem create secret generic idem-db-secret --from-literal=username=idem --from-literal=password='…'
kubectl -n idem create secret generic idem-app-secrets --from-env-file=install.env
helm upgrade --install idem infra/helm/idem -n idem -f my-values.yaml            # core
helm upgrade --install idem infra/helm/idem -n idem -f my-values.yaml -f infra/helm/idem/values-kr.yaml   # kr
```

`my-values.yaml` 은 공개 URL·PostgreSQL/Redis 주소·Ingress(차트 README 예). pre-install Job 이 스키마를 만들고, 앱은 첫 기동에서 Flyway 로 테이블을 만든다.

**리허설(1.1)**: `scripts/k8s/rehearsal.sh` 가 위 절차를 kind 클러스터(또는 `CLUSTER=existing` 으로 기관 클러스터)에서 처음부터 끝까지 돈다 — 비밀 한 벌·TLS Ingress·`helm install --wait` → §4 확인(스모크 ①~⑧, 관리 포트) → 운영 전환 `helm upgrade`(prod 프로파일·Mock off) → `helm rollback` → `helm uninstall`. CI `k8s-rehearsal` 잡이 차트·스크립트가 바뀐 PR 과 main push 마다 같은 스크립트를 돈다(`scripts/k8s/README.md`). 기관 클러스터에 처음 올릴 때 이 스크립트로 한 번 돌려 보고 그 결과(소요·문제)를 §8 에 보탠다.

### 3.3 (C) 오프라인(폐쇄망) 설치

1. 인터넷이 되는 곳에서 번들을 만든다 (`scripts/release/make-offline-bundle.sh`, 1.1.1 G1-2):
   ```bash
   git checkout v1.1.2
   VERSION=1.1.2 IDEM_EDITION=core scripts/release/make-offline-bundle.sh      # dist/idem-1.1.2-core/
   ```
   산출: `idem-1.1.2-core-images.tar`(Idem 이미지 5종 — compose 와 같은 이름·태그 `idem-hub:1.1.2-core` … + `postgres:16-alpine`·`redis:7.2-alpine`·`keycloak:24.0`), `idem-1.1.2-src.tar.gz`(소스 — `infra/`·`scripts/`·`docs/`, 비밀 없음), Helm 차트 `.tgz`(helm 이 있을 때), `MANIFEST.txt`(이미지 ID·크기·git sha), `SHA256SUMS`. 크기(코어, CI 측정 2026-10-08): 이미지 tar **1.4 GB**(gate 285·hub 315·registry 285·authz 279·console 46 MB + postgres 281·redis 37·keycloak 437 MB), 소스 tar 14 MB, 차트 24 KB — `docker save` 약 10초, 반입 `docker load` 약 15초. kr 에디션은 벤더 SDK 가 있는 곳에서 `IDEM_EDITION=kr`(회원 포털 이미지 포함).
2. 반입: 디렉터리 `dist/idem-1.1.2-core/` 통째로 (체크섬은 `SHA256SUMS`).
3. 폐쇄망에서 검증·적재 뒤 §3.1/§3.2 와 같다(`--build` 없이):
   ```bash
   EXTRACT_SOURCE=1 DEST=/opt scripts/release/load-offline-bundle.sh /media/idem-1.1.2-core   # SHA256 검증 → docker load → MANIFEST 대조 → 소스 풀기
   cd /opt/idem-1.1.2 && cp infra/docker/install.env.example install.env                     # IDEM_VERSION=1.1.2 IDEM_EDITION=core 를 넣는다
   docker compose -f infra/docker/compose.install.yml --env-file install.env up -d
   ```
   K8s 는 적재한 이미지를 사설 레지스트리에 `docker push` 하고 Helm `global.imageRegistry`·`global.imageTag=1.1.2` 을 준다.
   CI 는 스크립트가 바뀐 PR 마다 번들 생성 → 이미지 삭제 → 반입 스크립트로 복원을 돌린다(`offline-bundle-check` 잡). 실제 폐쇄망 반입은 §8.

## 4. 설치 확인 (완료 판정)

CI 와 같은 스크립트로 8단계를 확인한다:

```bash
IDEM_EDITION=core scripts/ci/install-smoke.sh     # HUB_URL·GATE_URL 등은 환경변수로, 기본 localhost
```

| 단계 | 확인 | 판정 |
|---|---|---|
| ① 헬스 | gate·hub·registry·authz `/actuator/health` | 4개 모두 `UP` |
| ② Discovery | `{gate}/realms/idem/.well-known/openid-configuration` | `issuer` = 공개 gate URL, PKCE S256 |
| ②′ 관리자 | 무인증 관리 API 401 → 첫 로그인(2단계 등록 + 비밀번호 변경) | 세션 발급, 감사 `ADMIN_LOGIN_SUCCESS` |
| ③ 프로파일 | OIDC_RP 프로파일 PUT | Keycloak 에 `idem-svc-*` client 생성, secret 회전 |
| ④ 로그인 화면 | gate 프런트 → Keycloak 로그인 화면 | 200, PKCE 없는 요청 400 |
| ⑤ 본인확인 | Mock 제공자 → registry 등록 | `qimUserId` 발급 |
| ⑥ 이벤트 | registry 이벤트 피드 | 응답 있음 |
| ⑦ 에디션 | core 에서 KR 엔드포인트 404 / kr 에서 존재 | 에디션 일치 |
| ⑧ 감사·로그아웃 | 감사 검색 → 로그아웃 204 → 세션 401 | 통과 |

Mock 본인확인(`IDEM_PLUGINS_MOCK_AUTH_ENABLED`)은 검증 동안 `IDEM_SPRING_PROFILE=default` 와 함께 켜고(1.0.1: `prod` 프로파일에서는 기동 거부), 확인 뒤 **반드시 false** 로 되돌린다. Helm 은 `helm get notes idem -n idem` 의 절차로 같은 항목을 본다.

## 5. 업그레이드

- **1.0 → 1.x**: 이미지 태그(`IDEM_VERSION` / `global.imageTag`)만 올리고 재기동. Flyway 가 마이그레이션을 적용한다. 되돌리기는 이전 태그 + DB 백업 복구.
- **S9 이전(0.x, OnePass 이름) → 1.0**: `docs/install.md` §7 — 앱·Keycloak 정지 → `scripts/upgrade/rename-db-1.0.sh`(DB `onepass→idem`·역할·스키마; `IDEM_DB_PASSWORD` 를 함께 준다) → `install.env` 변수명(`IDEM_HUB_*` …) → `keycloak-data` 볼륨 재생성(realm `idem` import) → 기동. 구 환경변수는 1 릴리스 동안 호환 계층이 `[Idem 개명]` 경고와 함께 받는다. Helm 은 db-init 훅이 구 스키마가 있으면 새 스키마를 만들지 않고, 앱이 첫 기동에서 옮긴다(1.0.1). 구·신 스키마가 둘 다 있고 새 쪽에 Flyway 이력이 없으면 앱이 기동을 거부한다 — 빈 새 스키마를 지운다.

## 6. 백업·복구

| 대상 | 백업 | 복구 |
|---|---|---|
| PostgreSQL `idem`(스키마 `idem_hub`·`idem_gate`·`idem_registry`·`idem_authz`·`keycloak`·`agency_stub`) | `scripts/ops/backup.sh` (1.1.1 G1-2) — 컨테이너 안의 `pg_dump -Fc`(compose 기본, `TARGET=k8s`·`direct` 도), `backups/idem-idem-<UTC>.dump` + `.sha256` + `.meta`(서버 버전·Flyway 최신 버전·주요 표 행 수). cron 에는 `KEEP=14` 로 보존 수 제한. 덤프 뒤 `pg_restore -l` 로 아카이브를 확인한다 | 앱·Keycloak 정지(`docker compose … stop idem-hub idem-gate idem-registry idem-authz idem-console-admin keycloak`) → `scripts/ops/restore.sh backups/<dump>` — 체크섬 확인, 살아 있는 접속이 있으면 중단(`FORCE=1` 이면 끊는다), DB 를 지우고 새로 만들어 `pg_restore --no-owner`, ANALYZE, 표·행 수 요약 → `up -d`. Flyway 이력이 덤프 안에 있으므로 재적용 없음. **백업 유효성 검증**(운영 DB 를 건드리지 않고): `TARGET_DB=idem_restore_check VERIFY_SOURCE_DB=idem scripts/ops/restore.sh <dump>` — CI 가 매 PR 스모크 뒤 이렇게 돈다 |
| Redis | 세션·캐시·레이트리밋 카운터만 — 백업 불필요(유실 시 재로그인) | — |
| `install.env` / Secret | 비밀 저장소에 사본. **`IDEM_REGISTRY_CI_AES_KEY_V1`·`IDEM_REGISTRY_DI_SECRET`·`IDEM_HUB_ADMIN_SECRET_KEY` 를 잃으면 CI·기관 식별자·관리자 2단계를 복구할 수 없다** | 같은 값으로 복원 |
| Keycloak | DB 덤프에 포함(`keycloak` 스키마). 기관 client 는 프로파일 재저장으로 재생성 가능 | 덤프 복구 또는 realm 재import + 프로파일 재저장 |

복구 판정: §4 의 ①·②·②′ 통과 + 기존 관리자 로그인(기존 비밀번호·인증 앱) + 기존 기관 RP 로그인.

## 7. 제거

```bash
docker compose --env-file infra/docker/install.env -f infra/docker/compose.install.yml down -v   # -v: 데이터 볼륨까지
helm uninstall idem -n idem && kubectl delete ns idem                                            # 바깥 PostgreSQL 은 별도
```

## 8. 이 문서에서 검증한 것 / 못 한 것

- ✅ (A) compose 절차와 §4 확인 8단계: CI(`k6 Smoke Test` 잡의 설치본 스모크)가 PR 마다 실기동으로 확인한다. 로컬 리허설(S9 PR-2)로 0.x → 1.0 업그레이드 3경로(DB 이름만 변경·업그레이드 스크립트·새 DB) 확인.
- ✅ (B) Helm: `helm lint`·`helm template`·kubeconform(CI `helm-lint` 잡) + **실제 클러스터 배포·업그레이드·롤백**(1.1 PR-6, CI `k8s-rehearsal` 잡 — kind 1노드에 코어 에디션 전부: ingress-nginx TLS, pre-install 스키마 Job, Keycloak production 모드 realm import, `helm install --wait` → §4 확인(스모크 ①~⑧ + 관리 포트 9090 비노출) → prod 프로파일·Mock off 로 `helm upgrade` → `helm rollback 1` → `helm uninstall`). 첫 통과 기록(PR #256, 2026-09-29, GitHub 호스팅 러너 4 vCPU/16 GB, kind v0.31 · K8s 1.35 · Helm 3.22): up 65s · install 84s(`helm install --wait` — 스키마 Job, Keycloak auto-build + realm import, 앱 4종 Flyway·기동) · 스모크 10s · 운영 전환 업그레이드 45s · 롤백 40s · 제거 13s, **합계 4분 17초**. 리허설이 드러낸 결함 1건 — Keycloak production 모드의 첫 기동 auto-build 가 종전 한도 1536Mi 에서 OOMKilled 되어 CrashLoop → 한도 2Gi + `keycloak.optimized`(차트 README). **기관 클러스터(운영 Ingress·바깥 DB)에서는 아직 안 돌렸다** — 첫 배포 때 `CLUSTER=existing` 으로 돌리고 여기에 적는다.
- ⚠️ (C) 오프라인: 이미지 tar 절차는 표준 docker 명령이지만 이 저장소 환경에는 docker 가 없어 실행해 보지 못했다. GS 시험 환경 준비 때 실행하고 소요 시간·크기를 적는다.
- 백업·복구(1.1.1 G1-2): CI 가 매 PR 설치본 스모크 뒤 `scripts/ops/backup.sh` → 새 DB 에 `restore.sh` → 스키마별 표 수·주요 표 행 수 대조를 돈다(검증됨). ⚠️ **운영 DB 복구**(앱 정지 → DB 재생성 → 복구 → 재기동 → §4 ①·②·②′ + 기존 관리자·기관 로그인)는 시험 환경에서 아직 리허설하지 않았다 — G1-1 수동 항목 G-5.
- 오프라인 설치본(1.1.1 G1-2): CI 가 스크립트 변경 PR 마다 번들 생성 → 이미지 삭제 → `load-offline-bundle.sh` 복원 → MANIFEST 대조를 돈다(검증됨). ⚠️ **실제 폐쇄망 반입·설치(§3.3, A-1~A-4)** 는 아직 하지 않았다 — G1-1 수동 항목 G-6.
