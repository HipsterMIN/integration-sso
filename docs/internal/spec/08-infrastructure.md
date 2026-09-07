# 08. 인프라 구성 및 로컬 구동 가이드

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09  
> **인프라 완성도**: 100%

---

## 1. Docker Compose 전체 구성

### 1.1 파일 위치

```
infra/docker/
├── docker-compose.yml          # 전체 인프라 + 서비스
├── init-db.sql                 # PostgreSQL 스키마 초기화
├── kafka/
│   └── kafka-topics-init.sh   # Kafka 토픽 자동 생성
├── keycloak/
│   └── realm-export.json      # onepass realm 설정
├── mariadb/
│   └── mariadb.cnf            # MariaDB 튜닝 설정
├── monitoring/
│   ├── prometheus/prometheus.yml
│   ├── prometheus/alert_rules.yml
│   ├── loki/loki-config.yml
│   ├── promtail/promtail-config.yml
│   └── grafana/provisioning/
│       ├── datasources/datasources.yml
│       └── dashboards/
└── nginx/nginx.conf
```

---

### 1.2 서비스 목록 및 포트

| 서비스 | 이미지 | 포트 | 역할 |
|--------|--------|------|------|
| `idem-postgres` | `postgres:16-alpine` | 5432 | Q-Sign + IdO DB |
| `idem-mariadb` | `mariadb:11.4` | 3306 | Q-IM 전용 DB |
| `idem-redis` | `redis:7.2-alpine` | 6379 | 세션 + 캐시 |
| `idem-zookeeper` | `confluentinc/cp-zookeeper:7.6.1` | 2181 | Kafka 코디네이터 |
| `idem-kafka` | `confluentinc/cp-kafka:7.6.1` | 9092 | 이벤트 버스 |
| `kafka-init` | (init 전용) | — | 토픽 자동 생성 |
| `idem-keycloak` | `quay.io/keycloak/keycloak:24` | 8085→8080 | OIDC IdP |
| `idem-nginx` | `nginx:1.27-alpine` | 3001→80 | FE 운영 서빙 |
| `idem-console` | (빌드) | 3000 | FE 개발 서버 |
| `idem-tenant-sample` | (빌드) | 8084 | 기관 시뮬레이터 |

### 1.3 모니터링 서비스 (profile: monitoring)

| 서비스 | 포트 | 역할 |
|--------|------|------|
| `prometheus` | 9090 | 메트릭 수집 (v2.51.2) |
| `grafana` | 3002 | 대시보드 (v10.4.2) |
| `loki` | 3100 | 로그 집계 (v3.0.0) |
| `promtail` | — | 로그 수집 에이전트 |

### 1.4 도구 서비스 (profile: tools)

| 서비스 | 포트 | 역할 |
|--------|------|------|
| `kafka-ui` | 8090 | Kafka 토픽 UI |
| `pgadmin` | 5050 | PostgreSQL 관리 |
| `adminer` | 8091 | MariaDB 관리 |
| `redis-insight` | 5540 | Redis 관리 |

---

## 2. Docker Compose Profile 정리

```bash
# 기본 인프라만 기동
docker compose -f infra/docker/docker-compose.yml up -d

# Keycloak 포함
docker compose -f infra/docker/docker-compose.yml --profile keycloak up -d

# 모니터링 스택 포함
docker compose -f infra/docker/docker-compose.yml --profile monitoring up -d

# 개발 도구 포함
docker compose -f infra/docker/docker-compose.yml --profile tools up -d

# 전체 인프라 + 앱 컨테이너 (optionB: Nginx 서빙)
docker compose -f infra/docker/docker-compose.yml \
  --profile keycloak --profile tools --profile monitoring \
  --profile optionB up -d
```

---

## 3. 로컬 개발 환경 빠른 시작

### 3.1 필수 소프트웨어

| 소프트웨어 | 버전 | 확인 명령 |
|-----------|------|---------|
| JDK | 21 LTS | `java -version` |
| Docker Desktop | 24+ | `docker --version` |
| Docker Compose | v2 (플러그인) | `docker compose version` |
| Node.js | 20.14 LTS | `node --version` |
| Yarn | 1.22 | `yarn --version` |

> `docker compose` (공백, v2)를 사용한다. `docker-compose` (하이픈, v1)와 다르다.

### 3.2 초기 기동 순서

```bash
# 1. 저장소 복제
git clone https://github.com/HipsterMIN/integration-sso.git
cd integration-sso
chmod +x gradlew

# 2. 인프라 기동
docker compose -f infra/docker/docker-compose.yml up -d

# 3. 컨테이너 헬스 확인 (30~60초 대기)
docker compose -f infra/docker/docker-compose.yml ps

# 4. Kafka 토픽 확인
docker exec -it idem-kafka kafka-topics --bootstrap-server localhost:9092 --list

# 5. 백엔드 빌드
./gradlew :idem-common:build :idem-gate:build :idem-registry:build :idem-hub:build :idem-tenant-sample:build -x test

# 6. 각 서비스 기동 (터미널 4개 사용)
./gradlew :idem-gate:bootRun          # 포트 8081
./gradlew :idem-registry:bootRun            # 포트 8082
./gradlew :idem-hub:bootRun             # 포트 8083
./gradlew :idem-tenant-sample:bootRun     # 포트 8084

# 7. 프론트엔드 기동 (별도 터미널)
cd idem-console/frontend
yarn install
yarn dev                           # 포트 3000
```

---

## 4. 환경변수 전체 목록

### 4.1 필수 운영 환경변수

```bash
# ── IdO 암호화 / 서명 ──
IDO_HANDOFF_AES_KEY=<base64-32bytes>          # AES-256 키 (Handoff Ticket 암호화)
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>      # HMAC-SHA256 키 (Ticket 서명)
IDO_INTERNAL_SIG_SECRET=<32bytes+>            # 내부 서비스 간 서명 키
IDO_AGENCY_SUBJECT_SECRET=<32bytes+>          # agencySubjectId HMAC 키

# ── Q-IM CI 암호화 ──
QIM_AES_SHARED_KEY=<base64-32bytes>           # 기관 → Q-IM CI 전송 암호화
QIM_CI_AES_KEY_V1=<base64-32bytes>            # CI 저장 암호화 키 (버전1)
QIM_DI_SECRET=<32bytes+>                      # DI 생성 HMAC 키

# ── Q-Sign Keycloak ──
QSIGN_KEYCLOAK_CLIENT_SECRET=<발급값>          # Keycloak Admin → Clients → q-sign-client

# ── 공통 인프라 ──
DB_HOST=localhost
DB_PORT=5432
DB_NAME=onepass
POSTGRES_PASSWORD=<운영용 강력한 비밀번호>
MARIADB_PASSWORD=<운영용 강력한 비밀번호>
QIM_DB_HOST=localhost
QIM_DB_PORT=3306
QIM_DB_NAME=onepass_qim
REDIS_HOST=localhost
REDIS_PORT=6379
KAFKA_SERVERS=localhost:9092
```

### 4.2 선택 환경변수

```bash
# 브로커 모드 전환
IDO_BROKER_MODE=qsign          # qsign (기본) | keycloak

# 정책 버전 (외부화됨)
IDO_POLICY_DEFAULT_VERSION=1.0
IDO_PLATFORM_VERSION=1.0

# Kafka 파티션 설정 (운영)
IDO_KAFKA_PARTITION_COUNT_MAIN=12
IDO_KAFKA_PARTITION_COUNT_DLQ=6
IDO_KAFKA_REPLICATION_FACTOR=3
```

---

## 5. 헬스 체크 엔드포인트

```bash
# 전체 서비스 일괄 확인
for port in 8081 8082 8083 8084; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | python3 -m json.tool | grep '"status"'
done

# Kafka 토픽 목록 (12개 예상)
docker exec -it idem-kafka kafka-topics --bootstrap-server localhost:9092 --list | wc -l

# PostgreSQL 스키마 확인
docker exec -it idem-postgres psql -U onepass -c "\dn"

# MariaDB Q-IM 스키마 확인
docker exec -it idem-mariadb mysql -u qim -p onepass_qim -e "SHOW TABLES;"

# Redis 세션 키 확인
docker exec -it idem-redis redis-cli KEYS "fe:session:*" | head -5
```

---

## 6. 유용한 Gradle 명령

```bash
# 특정 모듈 빌드
./gradlew :idem-hub:build -x test

# 특정 모듈 실행
./gradlew :idem-hub:bootRun

# 전체 클린 빌드
./gradlew clean build -x test

# 컴파일 오류만 확인
./gradlew :idem-common:compileJava :idem-hub:compileJava

# 의존성 트리
./gradlew :idem-hub:dependencies

# Gradle 데몬 종료
./gradlew --stop
```

---

## 7. 자주 발생하는 문제 해결

### 7.1 포트 충돌

```bash
# 사용 중인 프로세스 확인
lsof -i :5432    # PostgreSQL
lsof -i :6379    # Redis
lsof -i :9092    # Kafka
lsof -i :8081    # Q-Sign / Keycloak 충돌 주의!

# Keycloak은 8085 포트 사용 (Q-Sign 8081과 구분)
# docker-compose.yml: "8085:8080"
```

### 7.2 Keycloak realm 없음

```bash
# realm-export.json 존재 확인
ls -la infra/docker/keycloak/realm-export.json

# Keycloak 재시작
docker compose -f infra/docker/docker-compose.yml restart idem-keycloak

# realm 확인
curl -s http://localhost:8085/realms/onepass
```

### 7.3 Kafka 토픽 미생성

```bash
# kafka-init 컨테이너 로그 확인
docker logs kafka-init

# 수동 토픽 생성 (예시)
docker exec -it idem-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --create --topic qsign.auth.events \
  --partitions 12 --replication-factor 1
```

### 7.4 Flyway 마이그레이션 실패

```bash
# PostgreSQL Flyway 이력 확인
docker exec -it idem-postgres psql -U onepass -c \
  "SELECT version, description, success FROM ido.flyway_schema_history ORDER BY installed_rank;"

# 마이그레이션 재실행 (checksum 오류 시)
./gradlew :idem-hub:bootRun -Dspring.flyway.repair=true
```

### 7.5 Apple Silicon (M1/M2)

```bash
# Rosetta 2 설치
softwareupdate --install-rosetta --agree-to-license

# Docker Desktop: Enable "Use Rosetta for x86/amd64 emulation"

# ARM 이미지 명시
docker pull --platform linux/arm64 postgres:16-alpine
```

### 7.6 Windows WSL2

```bash
# gradlew 줄바꿈 문자 수정
sed -i 's/\r//' gradlew

# 긴 경로 활성화
git config core.longpaths true

# Gradle 실행
gradlew.bat :idem-hub:bootRun
```

---

## 8. 접속 URL 전체 목록

| 서비스 | URL | 비고 |
|--------|-----|------|
| Q-Sign | http://localhost:8081 | 인증 SoR |
| Q-IM | http://localhost:8082 | 식별 SoR |
| IdO | http://localhost:8083 | 오케스트레이터 |
| agency-stub | http://localhost:8084 | PoC 기관 시뮬레이터 |
| agency-stub Web UI | http://localhost:8084/ | 브라우저 E2E 시뮬레이터 |
| idem-console (개발) | http://localhost:3000 | React SPA |
| idem-console (운영) | http://localhost:3001 | Nginx 서빙 |
| Keycloak | http://localhost:8085 | OIDC IdP (admin/admin) |
| Kafka UI | http://localhost:8090 | 토픽·오프셋 조회 |
| pgAdmin | http://localhost:5050 | PostgreSQL 관리 |
| Adminer | http://localhost:8091 | MariaDB 관리 |
| Redis Insight | http://localhost:5540 | Redis 관리 |
| Prometheus | http://localhost:9090 | 메트릭 |
| Grafana | http://localhost:3002 | 대시보드 (admin/admin) |
| Loki | http://localhost:3100 | 로그 |

---

*다음 문서: [09-gap-and-roadmap.md](09-gap-and-roadmap.md)*
