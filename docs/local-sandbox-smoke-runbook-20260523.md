# 로컬 샌드박스/스모크 테스트 실행 문서 (2026-05-23)

이 문서는 2026-05-23(KST) 기준, Codex가 실제로 실행한 검증 절차를 로컬 Windows PowerShell에서 재현할 수 있도록 정리한 문서입니다.

---

## 0) 실행 환경

- OS: Windows (PowerShell 7.x)
- 작업 경로: `C:\Users\User\Projects\integration-sso`
- Compose 파일: `infra/docker/docker-compose.yml`

> 아래 모든 명령은 **PowerShell** 기준입니다.

---

## 1) 이번 검증에서 실제 사용한 명령 순서

아래는 이번 응답 생성 과정에서 실행한 명령을 순서대로 정리한 것입니다.

### 1-1. 기본 상태 확인

```powershell
docker compose -f infra/docker/docker-compose.yml ps
```

- 결과: 실패  
  - 원인: `QSIGN_KEYCLOAK_CLIENT_SECRET` 등 필수 환경변수 미설정

```powershell
git status --short --branch
```

- 결과: 성공 (브랜치/변경상태 확인)

### 1-2. 필수 환경변수 주입 + Compose 설정 유효성 확인

```powershell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:IDO_WEBHOOK_SIGNING_SECRET='poc-webhook-secret-change-in-production'; `
$env:IDO_INTERNAL_SIG_SECRET='change-me-32bytes-internal-sig-secret'; `
$env:QIM_INTERNAL_API_KEY='dev-qim-internal-api-key-change-me'; `
$env:IDO_HANDOFF_AES_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
$env:IDO_HANDOFF_HMAC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
docker compose -f infra/docker/docker-compose.yml --profile app --profile optionB --profile keycloak --profile monitoring config > $null; `
if ($LASTEXITCODE -eq 0) { 'compose-config-ok' }
```

- 결과: `compose-config-ok`

### 1-3. 인프라 Compose 기동

```powershell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:IDO_WEBHOOK_SIGNING_SECRET='poc-webhook-secret-change-in-production'; `
$env:IDO_INTERNAL_SIG_SECRET='change-me-32bytes-internal-sig-secret'; `
$env:QIM_INTERNAL_API_KEY='dev-qim-internal-api-key-change-me'; `
$env:IDO_HANDOFF_AES_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
$env:IDO_HANDOFF_HMAC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
docker compose -f infra/docker/docker-compose.yml up -d
```

- 결과: 성공 (postgres/mariadb/redis/kafka/vault 등 기동)

### 1-4. 인프라 상태/토픽/로그 확인

```powershell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:IDO_WEBHOOK_SIGNING_SECRET='poc-webhook-secret-change-in-production'; `
$env:IDO_INTERNAL_SIG_SECRET='change-me-32bytes-internal-sig-secret'; `
$env:QIM_INTERNAL_API_KEY='dev-qim-internal-api-key-change-me'; `
$env:IDO_HANDOFF_AES_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
$env:IDO_HANDOFF_HMAC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
docker compose -f infra/docker/docker-compose.yml ps
```

```powershell
docker logs --tail 120 onepass-kafka-init
```

```powershell
docker exec onepass-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

- 결과:
  - 토픽 생성/존재 확인
  - `kafka-init` 로그에 일부 스크립트 경고(`integer expression expected`) 존재

### 1-5. DB 스모크 확인

```powershell
docker exec onepass-postgres psql -U onepass -d onepass -tAc "select schema_name from information_schema.schemata where schema_name in ('keycloak','agency_stub','ido','qsign') order by schema_name;"
```

```powershell
docker exec onepass-mariadb mariadb -u root -proot -N -e "SHOW DATABASES LIKE 'qim';"
```

- 결과: 실패 (`root` 비밀번호 불일치)

```powershell
rg -n "mariadb|MYSQL_ROOT_PASSWORD|MARIADB_ROOT_PASSWORD|MARIADB_DATABASE" infra/docker/docker-compose.yml
```

```powershell
docker inspect onepass-mariadb --format "{{json .Config.Env}}"
```

```powershell
docker exec onepass-mariadb mariadb -u root -prootpass -N -e "SHOW DATABASES LIKE 'qim';"
```

- 결과: 성공 (`qim` 확인)

### 1-6. kafka-ui 비정상 원인 확인

```powershell
docker logs --tail 80 onepass-kafka-ui
```

```powershell
docker inspect onepass-kafka-ui --format "{{.State.Health.Status}}|{{range .State.Health.Log}}{{.ExitCode}} {{.Output}}{{end}}"
```

- 결과: `unhealthy`, 헬스체크에서 `curl not found`

### 1-7. 코드 핵심 스모크 테스트

```powershell
.\gradlew.bat :idem-gate:test :idem-registry:test :idem-hub:test
```

- 결과:
  - `q-sign`: PASS
  - `q-im`: FAIL (Flyway 마이그레이션 중복 인덱스)
  - `ido`: 미실행(앞 단계 실패로 중단)

```powershell
.\gradlew.bat :idem-hub:test
```

- 결과: FAIL (CAS 테스트/웹슬라이스 컨텍스트 로딩 이슈)

### 1-8. 실패 원인 grep(로그 근거 확보)

```powershell
rg -n "Duplicate key name|idx_status_history_user|V3__add_ci_encryption_and_status_history" idem-registry/build/test-results
```

```powershell
rg -n "NoSuchBeanDefinitionException|UnsatisfiedDependencyException|Caused by" "idem-hub/build/test-results/test/TEST-kr.go.smes.ido.memberlookup.MemberLookupControllerTest$AuditLogBehavior.xml"
```

```powershell
rg -n "UnrecognizedPropertyException|AssertionError|Caused by" "idem-hub/build/test-results/test/TEST-kr.go.smes.ido.infrastructure.TicketRepositoryImplTest$CasBranchHandling.xml"
```

### 1-9. 전체 프로파일(app 포함) 기동 재검증

```powershell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:IDO_WEBHOOK_SIGNING_SECRET='poc-webhook-secret-change-in-production'; `
$env:IDO_INTERNAL_SIG_SECRET='change-me-32bytes-internal-sig-secret'; `
$env:QIM_INTERNAL_API_KEY='dev-qim-internal-api-key-change-me'; `
$env:IDO_HANDOFF_AES_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
$env:IDO_HANDOFF_HMAC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
docker compose -f infra/docker/docker-compose.yml --profile app --profile optionB --profile keycloak --profile monitoring up -d
```

- 결과: 실패  
  - 원인: `onepass-qim` 이미지 pull 실패 (`pull access denied`)

### 1-10. 최종 상태 확인

```powershell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:KEYCLOAK_CLIENT_SECRET='change-me'; `
$env:IDO_WEBHOOK_SIGNING_SECRET='poc-webhook-secret-change-in-production'; `
$env:IDO_INTERNAL_SIG_SECRET='change-me-32bytes-internal-sig-secret'; `
$env:QIM_INTERNAL_API_KEY='dev-qim-internal-api-key-change-me'; `
$env:IDO_HANDOFF_AES_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
$env:IDO_HANDOFF_HMAC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; `
docker compose -f infra/docker/docker-compose.yml ps
```

---

## 2) 재현용 권장 실행 순서 (복붙용)

아래 순서대로 실행하면 이번 검증을 거의 동일하게 재현할 수 있습니다.

### Step A. 환경변수 1회 주입

```powershell
$env:QSIGN_KEYCLOAK_CLIENT_SECRET='change-me'
$env:KEYCLOAK_CLIENT_SECRET='change-me'
$env:IDO_WEBHOOK_SIGNING_SECRET='poc-webhook-secret-change-in-production'
$env:IDO_INTERNAL_SIG_SECRET='change-me-32bytes-internal-sig-secret'
$env:QIM_INTERNAL_API_KEY='dev-qim-internal-api-key-change-me'
$env:IDO_HANDOFF_AES_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
$env:IDO_HANDOFF_HMAC_KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
```

### Step B. Compose 유효성

```powershell
docker compose -f infra/docker/docker-compose.yml --profile app --profile optionB --profile keycloak --profile monitoring config > $null
if ($LASTEXITCODE -eq 0) { "compose-config-ok" }
```

### Step C. 인프라 기동 + 상태

```powershell
docker compose -f infra/docker/docker-compose.yml up -d
docker compose -f infra/docker/docker-compose.yml ps
```

### Step D. Kafka/DB 스모크

```powershell
docker exec onepass-kafka kafka-topics --bootstrap-server localhost:9092 --list
docker exec onepass-postgres psql -U onepass -d onepass -tAc "select schema_name from information_schema.schemata where schema_name in ('keycloak','agency_stub','ido','qsign') order by schema_name;"
docker exec onepass-mariadb mariadb -u root -prootpass -N -e "SHOW DATABASES LIKE 'qim';"
```

### Step E. 핵심 테스트

```powershell
.\gradlew.bat :idem-gate:test :idem-registry:test
.\gradlew.bat :idem-hub:test
```

### Step F. 전체 프로파일(app 포함) 기동 확인

```powershell
docker compose -f infra/docker/docker-compose.yml --profile app --profile optionB --profile keycloak --profile monitoring up -d
```

---

## 3) 이번 실행 기준 GO/NO-GO 판정 근거

- `GO` 조건 충족:
  - infra core 컨테이너 기동
  - Kafka 토픽/DB 기본 리소스 준비
- `NO-GO` 원인:
  - app profile 전체 기동 실패 (`onepass-qim` 이미지 pull 실패)
  - `q-im`, `ido` 테스트 실패
  - `kafka-ui` unhealthy

최종: **NO-GO**

