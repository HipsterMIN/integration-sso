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

## 보안·운영 (1.0.1)

- Pod 는 `runAsNonRoot` + 숫자 UID(`appDefaults.runAsUser: 1001`, Keycloak `keycloak.runAsUser: 1000`) — 이미지의 `USER` 도 1001 이라 kubelet 이 통과시킨다(3차 점검 H4).
- `global.imageRegistry` 는 Idem 이미지(짧은 이름)에만 붙는다. Keycloak·postgres 같은 서드파티 이미지는 `image.registry: ""` — 미러를 쓰면 그 값을 준다(H5).
- 앱은 `SPRING_PROFILES_ACTIVE=prod`(`appDefaults.springProfile`)로 뜨고 actuator 는 관리 포트 9090(`appDefaults.managementPort`)에 있다 — Service·Ingress 는 앱 포트만 내보내므로 `/actuator` 는 클러스터 밖에서 닿지 않는다. 프로브와 Prometheus 수집은 관리 포트로(M6·M7).
- Keycloak 관리 콘솔 주소 기본값은 `http://localhost:8088`(NOTES 의 port-forward). `keycloak.replicaCount > 1` 은 `extraEnv` 에 `KC_CACHE_STACK` 이 없으면 렌더링을 거부한다(M9).
- 내부 client(`idem-gate`·`idem-hub`)의 redirect URI·webOrigins 는 `global.publicUrl` 을 realm import 때 읽는다(첫 import 에만, M10).
- 업그레이드(0.x → 1.0): db-init 훅은 구 스키마(`ido` 등)가 있으면 새 스키마를 만들지 않는다 — 앱이 첫 기동에서 옮긴다. 구·신 스키마가 둘 다 있고 새 쪽에 Flyway 이력이 없으면 앱이 기동을 거부한다(H7). 업그레이드가 끝나면 `hub.config` 등에 `IDEM_NAMING_LEGACY_REPAIR: "false"`.

## 비밀

앱 비밀은 Secret 한 벌(`secrets.existingSecret`)이고 Pod 마다 **필요한 키만** `secretKeyRef` 로 받는다(전체 envFrom 아님). `secrets.create=true` 로 values 에서 만들 수도 있지만 개발·시험용이다 — 운영은 External Secrets·Sealed Secrets·Vault Agent 등으로 만든다. 회전 영향은 `docs/install-inputs.md`.

## 검증

이 저장소 환경에는 클러스터가 없어 `helm lint` + `helm template`(core·kr) 로 렌더링과 스키마를 확인했고, CI `helm-lint` 잡이 같은 검사와 `files/*` 사본이 `infra/docker` 원본과 같은지 대조하며, 1.0.1 부터 위 보안·운영 항목(레지스트리 접두·UID·관리 포트·admin URL·replica 가드)도 렌더링으로 회귀 검사한다. 실제 클러스터 배포는 아직 해 보지 못했다 — 첫 배포 때 `docs/install.md` §4~§5 확인 절차와 NOTES 를 따라 검증하고 여기에 기록한다.
