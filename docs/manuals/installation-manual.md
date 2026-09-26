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
| 소프트웨어 버전 | Idem 1.0.1 (태그 `v1.0.1`), Keycloak 24.0, Spring Boot 3.5 / Java 21 (이미지 안) |

## 2. 입력값 준비

`docs/install-inputs.md` 의 표대로 비밀 20종을 만든다(`openssl rand`, CAST 키는 `openssl genpkey ed25519`). compose 는 `infra/docker/install.env`, Helm 은 Secret `idem-db-secret`·`idem-app-secrets`. 공개 URL(gate·hub·console) 을 정한다 — gate URL 이 표준 OIDC issuer 의 베이스(`{gate}/realms/idem`)다.

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

### 3.3 (C) 오프라인(폐쇄망) 설치

1. 인터넷이 되는 곳에서 이미지를 만들고 tar 로 뽑는다:
   ```bash
   IDEM_EDITION=core docker compose -f infra/docker/compose.install.yml build
   docker save idem-gate:latest idem-hub:latest-core idem-registry:latest-core idem-authz:latest idem-console-admin:latest \
               postgres:16-alpine redis:7.2-alpine quay.io/keycloak/keycloak:24.0 -o idem-1.0.1-images.tar
   ```
2. 반입: `idem-1.0.1-images.tar` + 저장소 `infra/`·`scripts/`·`docs/`(또는 `v1.0.1` 소스 tar) + Helm 은 `helm package infra/helm/idem`.
3. 폐쇄망에서 `docker load -i idem-1.0.1-images.tar` 뒤 §3.1/§3.2 와 같다(`--build` 없이). K8s 는 사설 레지스트리에 `docker push` 하고 `global.imageRegistry` 를 준다.

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
| PostgreSQL `idem`(스키마 `idem_hub`·`idem_gate`·`idem_registry`·`idem_authz`·`keycloak`) | `pg_dump -Fc -U idem idem > idem-YYYYMMDD.dump` (compose: `docker exec idem-postgres …`) | 앱 정지 → `pg_restore -c -d idem` → 기동. Flyway 이력이 덤프 안에 있으므로 재적용 없음 |
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
- ✅ (B) Helm: `helm lint`·`helm template`·kubeconform(CI `helm-lint` 잡). **실제 클러스터 배포는 아직 못 했다** — 첫 배포 때 §4 로 검증하고 여기에 기록한다.
- ⚠️ (C) 오프라인: 이미지 tar 절차는 표준 docker 명령이지만 이 저장소 환경에는 docker 가 없어 실행해 보지 못했다. GS 시험 환경 준비 때 실행하고 소요 시간·크기를 적는다.
- ⚠️ 백업·복구: `pg_dump/pg_restore` 절차는 아직 리허설하지 않았다(1.0.1 과제).
