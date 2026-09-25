# Idem Helm 차트

단일 설치본(`infra/docker/compose.install.yml`, `docs/install.md`)과 **같은 계약**을 Kubernetes 로 옮긴 차트다. 컴포넌트·환경변수·비밀 키 이름이 compose 와 같으므로 설치 문서의 확인 절차(§4~§5)를 그대로 쓴다.

| 컴포넌트 | 기본 | 비고 |
|---|---|---|
| `idem-keycloak` | 켬 | 숨긴 Keycloak(S6). realm `idem` 을 `files/realm-export.json` 으로 import. 밖의 Keycloak 을 쓰면 `keycloak.enabled=false` + `externalUrl` |
| `idem-gate` · `idem-hub` · `idem-registry` · `idem-authz` | 켬 | hub·registry 이미지는 `<tag>-<edition>` |
| `idem-console-admin` | 켬 | 관리 콘솔 (Nginx, `/api/v1/admin/` → hub) |
| `idem-kr-portal` | KR 만 | `values-kr.yaml` |
| `idem-relay` | 끔 | Kafka 를 쓰는 배포에서만(`infra.kafka.enabled`) |
| PostgreSQL · Redis | 바깥 | `infra.postgres` / `infra.redis`. 스키마는 pre-install Job 이 `files/init-db.sql` 로 만든다 |

## 설치

```bash
# 1. DB 사용자 Secret (키 username/password) 과 앱 비밀 한 벌 (키 이름은 docs/install-inputs.md)
kubectl -n idem create secret generic idem-db-secret --from-literal=username=idem --from-literal=password='…'
kubectl -n idem create secret generic idem-app-secrets --from-env-file=install.env   # install.env.example 의 키를 채운 파일

# 2. 값 파일: 공개 URL · PostgreSQL/Redis 주소 · Ingress
cat > my-values.yaml <<'EOF'
global:
  imageRegistry: ghcr.io/hipstermin
  imageTag: "1.0.0"
  publicUrl: { gate: https://sso.example.org, hub: https://hub.example.org, console: https://console.example.org }
infra:
  postgres: { host: pg.example.internal, port: 5432, database: idem, existingSecret: idem-db-secret }
  redis:    { host: redis.example.internal, port: 6379 }
ingress: { enabled: true, className: nginx, tlsSecretName: idem-tls }
EOF

# 3. 코어 에디션
helm upgrade --install idem infra/helm/idem -n idem --create-namespace -f my-values.yaml
# 3'. KR 에디션 (hub·registry 이미지 <tag>-kr, 회원 포털, KR 플러그인)
helm upgrade --install idem infra/helm/idem -n idem --create-namespace -f my-values.yaml -f infra/helm/idem/values-kr.yaml
```

설치 뒤 `helm get notes idem -n idem` 의 확인 절차를 따른다. 첫 관리자 로그인·2단계 등록은 `docs/admin-auth.md`.

## 에디션

`global.edition` 이 `core | kr` 를 정한다. 이미지 태그 규칙은 compose 와 같다: `idem-hub:<tag>-core`, `idem-hub:<tag>-kr` (CI `docker-build` 가 두 변형을 다 민다). KR 이미지는 빌드 때 벤더 SDK(`vendor-libs`)를 `--build-context` 로 넣는다 — `idem-hub/Dockerfile` 머리말. 벤더 자격증명은 이미지가 아니라 Secret 에 두고 `hub.extraEnv` 로 넣는다(플러그인 문서).

## 비밀

앱 비밀은 Secret 한 벌(`secrets.existingSecret`)이고 Pod 마다 **필요한 키만** `secretKeyRef` 로 받는다(전체 envFrom 아님). `secrets.create=true` 로 values 에서 만들 수도 있지만 개발·시험용이다 — 운영은 External Secrets·Sealed Secrets·Vault Agent 등으로 만든다. 회전 영향은 `docs/install-inputs.md`.

## 검증

이 저장소 환경에는 클러스터가 없어 `helm lint` + `helm template`(core·kr) 로 렌더링과 스키마를 확인했고, CI `helm-lint` 잡이 같은 검사와 `files/*` 사본이 `infra/docker` 원본과 같은지 대조한다. 실제 클러스터 배포는 아직 해 보지 못했다 — 첫 배포 때 `docs/install.md` §4~§5 확인 절차와 NOTES 를 따라 검증하고 여기에 기록한다.
