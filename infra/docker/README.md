# Docker Compose 조합 가이드

이 디렉터리는 로컬/개발용 Docker Compose를 서비스 경계별로 조합할 수 있게 나눈다.
기존 `docker-compose.yml`은 호환성 확인용 레거시 전체 스택 파일로 유지하고, 신규 작업은 아래 분리 파일을 우선 사용한다.

## 파일 구조

| 파일 | 목적 |
| --- | --- |
| `compose.base.yml` | 공통 네트워크만 정의한다. 모든 조합의 첫 번째 파일로 사용한다. |
| `compose.support.yml` | `onepass-support` 전용 PostgreSQL과 support 앱을 정의한다. |
| `compose.sso-im.yml` | SSO/IM 인프라와 Keycloak/Schema Registry를 정의한다. 앱 secret이 없어도 config 검증 가능하다. |
| `compose.sso-im-apps.yml` | q-sign, q-im, ido, agency-stub, onepass-fe 컨테이너를 정의한다. 앱 secret이 필요하다. |
| `compose.tools.yml` | pgAdmin, Adminer, Kafka UI, Redis Insight 등 도구를 필요할 때만 붙인다. |
| `compose.monitoring.yml` | Prometheus, Grafana, Loki, exporter를 필요할 때만 붙인다. |
| `docker-compose.yml` | 기존 전체 스택 호환성 파일이다. 신규 분리 검증에는 사용하지 않는다. |

## Support 단독

Support는 SSO/IM 데이터베이스와 분리된 PostgreSQL을 사용한다.

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.support.yml `
  up -d support-postgres
```

호스트에서 `onepass-support`를 직접 실행할 때:

```powershell
$env:SUPPORT_DB_HOST='localhost'
$env:SUPPORT_DB_PORT='5433'
$env:SUPPORT_DB_NAME='onepass_support'
$env:SUPPORT_DB_USERNAME='onepass_support'
$env:SUPPORT_DB_PASSWORD='onepass_support'
.\gradlew.bat :onepass-support:bootRun
```

Support 앱까지 컨테이너로 실행할 때:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.support.yml `
  --profile support-app `
  up -d
```

## SSO/IM 단독

기본 실행은 DB, Redis, Kafka, Vault 등 인프라만 띄운다.

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.sso-im.yml `
  up -d
```

앱과 Keycloak까지 포함할 때:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.sso-im.yml `
  -f infra/docker/compose.sso-im-apps.yml `
  --profile app `
  --profile keycloak `
  up -d
```

프론트 Nginx 컨테이너까지 붙일 때:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.sso-im.yml `
  -f infra/docker/compose.sso-im-apps.yml `
  --profile app `
  --profile keycloak `
  --profile optionB `
  up -d
```

## Support + SSO/IM 연계 검증

Support가 SSO/IM DB를 공유하지 않더라도 같은 Docker network에서 연계 시나리오를 확인할 수 있다.

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.sso-im.yml `
  -f infra/docker/compose.sso-im-apps.yml `
  -f infra/docker/compose.support.yml `
  --profile app `
  --profile keycloak `
  --profile support-app `
  up -d
```

이 조합에서도 Support DB는 `onepass-support-postgres`, SSO/IM DB는 `onepass-postgres`로 분리된다.

## 도구와 모니터링

SSO/IM 관리 도구:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.sso-im.yml `
  -f infra/docker/compose.tools.yml `
  --profile sso-im-tools `
  up -d
```

Support DB용 pgAdmin:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.support.yml `
  -f infra/docker/compose.tools.yml `
  --profile support-tools `
  up -d support-pgadmin
```

기본 모니터링:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.monitoring.yml `
  up -d
```

Support PostgreSQL exporter까지 포함:

```powershell
docker compose `
  -f infra/docker/compose.base.yml `
  -f infra/docker/compose.support.yml `
  -f infra/docker/compose.monitoring.yml `
  --profile support-exporters `
  up -d
```

## 포트

| 대상 | 포트 |
| --- | --- |
| SSO/IM PostgreSQL | `localhost:5432` |
| Support PostgreSQL | `localhost:5433` |
| onepass-support | `localhost:8085` |
| q-sign | `localhost:8081` |
| q-im | `localhost:8082` |
| ido | `localhost:8083` |
| agency-stub | `localhost:8084` |
| Keycloak | `localhost:8088` |
| pgAdmin | `localhost:5050` |
| Support pgAdmin | `localhost:5051` |
| Grafana | `localhost:3002` |
| Prometheus | `localhost:9090` |

## 검증 명령

```powershell
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.support.yml config
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.sso-im.yml config
docker compose -f infra/docker/compose.base.yml -f infra/docker/compose.sso-im.yml -f infra/docker/compose.sso-im-apps.yml -f infra/docker/compose.support.yml --profile app --profile keycloak --profile support-app config
```
