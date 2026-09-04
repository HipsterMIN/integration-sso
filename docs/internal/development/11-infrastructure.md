# 11. 인프라 구성 (Infrastructure)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09

---

## 1. Docker Compose 구성

### 1.1 파일 경로

```
infra/docker/
├── docker-compose.yml        # 전체 스택 (기본 + profile 분리)
├── init-db.sql               # PostgreSQL 초기화 SQL
├── postgres/                 # PostgreSQL 설정
├── mariadb/                  # MariaDB 설정
├── kafka/
│   └── kafka-init.sh         # Kafka 토픽 초기화
├── keycloak/
│   └── realm-export.json     # Keycloak realm 설정
├── nginx/                    # Nginx 리버스 프록시 설정
└── redis/                    # Redis 설정
```

### 1.2 서비스 구성 (docker-compose.yml)

#### 기본 인프라 서비스 (프로파일 없음 — 항상 기동)

| 서비스 | 이미지 | 포트 | IP |
|--------|--------|------|-----|
| postgres | postgres:16 | 5432 | 172.20.0.10 |
| redis | redis:7-alpine | 6379 | 172.20.0.11 |
| zookeeper | confluentinc/cp-zookeeper:7.5 | 2181 | 172.20.0.12 |
| kafka | confluentinc/cp-kafka:7.5 | 9092 | 172.20.0.13 |
| kafka-init | confluentinc/cp-kafka:7.5 | - | - |
| schema-registry | confluentinc/cp-schema-registry | 8085 | 172.20.0.14 |
| kafka-ui | provectuslabs/kafka-ui | 8090 | 172.20.0.15 |
| redis-insight | redislabs/redisinsight | 5540 | 172.20.0.16 |
| pgadmin | dpage/pgadmin4 | 5050 | 172.20.0.17 |
| mariadb | mariadb:11.4 | 3306 | 172.20.0.21 |
| adminer | adminer | 8091 | 172.20.0.23 |

#### app 프로파일 (`--profile app`)

| 서비스 | 이미지 | 포트 | IP |
|--------|--------|------|-----|
| onepass-ido | onepass-ido:latest | 8083 | 172.20.0.19 |
| onepass-react | onepass-react:latest | 3001 | 172.20.0.20 |
| onepass-qim | onepass-qim:latest | 8082 | 172.20.0.22 |
| onepass-qsign | onepass-qsign:latest | 8081 | 172.20.0.24 |

#### keycloak 프로파일 (`--profile keycloak`)

| 서비스 | 이미지 | 포트 | IP |
|--------|--------|------|-----|
| keycloak | quay.io/keycloak/keycloak:24 | 8088 | 172.20.0.18 |

#### monitoring 프로파일 (`--profile monitoring`)

| 서비스 | 이미지 | 포트 |
|--------|--------|------|
| prometheus | prom/prometheus | 9090 |
| grafana | grafana/grafana | 3000 |
| loki | grafana/loki | 3100 |
| promtail | grafana/promtail | - |

---

## 2. 기동 절차

### 2.1 전체 스택 기동 (로컬 개발)

```bash
cd infra/docker

# ── 단계 1: 인프라 기동 ──────────────────────────────────
docker compose up -d postgres mariadb redis zookeeper kafka kafka-init
# kafka-init이 토픽을 생성할 때까지 약 30초 대기

# ── 단계 2: 애플리케이션 기동 ─────────────────────────────
docker compose --profile app up -d

# ── 단계 3 (선택): Keycloak ───────────────────────────────
docker compose --profile keycloak up -d

# ── 단계 4 (선택): 모니터링 ───────────────────────────────
docker compose --profile monitoring up -d
```

### 2.2 Docker 이미지 빌드

```bash
# JAR 빌드 (루트에서)
./gradlew :idem-gate:bootJar :idem-hub:bootJar :idem-registry:bootJar -x test

# Docker 이미지 빌드
docker build -f idem-gate/Dockerfile -t onepass-qsign:latest .
docker build -f idem-hub/Dockerfile     -t onepass-ido:latest .
docker build -f idem-registry/Dockerfile    -t onepass-qim:latest .
```

### 2.3 헬스체크

```bash
curl http://localhost:8081/actuator/health  # Q-Sign
curl http://localhost:8082/actuator/health  # Q-IM
curl http://localhost:8083/actuator/health  # IdO
curl http://localhost:8088/health/ready     # Keycloak
```

---

## 3. Dockerfile 구성

모든 Spring Boot 서비스는 **멀티스테이지 빌드** + **non-root 사용자** 패턴 적용:

```dockerfile
# idem-hub/Dockerfile 예시
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY . .
RUN ./gradlew :idem-hub:bootJar -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ido && adduser -S ido -G ido
USER ido
WORKDIR /app
COPY --from=builder /build/idem-hub/build/libs/idem-hub-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 4. Kafka 토픽 구성

### 4.1 kafka-init 스크립트

`infra/docker/kafka/kafka-init.sh`에서 Kafka 기동 완료 후 토픽 자동 생성.

### 4.2 토픽 상세 설정

| 토픽 | 파티션 | 복제팩터 | ISR | 보존기간 | 압축 |
|------|--------|---------|-----|---------|------|
| `qsign.auth.events` | 12 | 3 | 2 | 7일 | lz4 |
| `qim.user.events` | 6 | 3 | 2 | 7일 | lz4 |
| `ido.handoff.events` | 12 | 3 | 2 | 7일 | lz4 |
| `platform.session.advisory` | 12 | 3 | 2 | 1일 | lz4 |
| `platform.audit.log` | 6 | 3 | 2 | 180일 | snappy |
| `qim.sp.member.events` | 6 | 3 | 2 | 7일 | lz4 |

> **DLQ 토픽** (GAP-IDO-09 — 미구현):  
> `qsign.auth.events.dlt`, `qim.user.events.dlt`, `ido.handoff.events.dlt` 등  
> `DeadLetterPublishingRecoverer` 연결 후 활성화 예정

### 4.3 컨슈머 concurrency 설정

```yaml
spring:
  kafka:
    consumer:
      group-id: ido-consumer-group
    listener:
      concurrency: 6  # qsign.auth.events, ido.handoff.events
      # qim.user.events: 3
      # platform.advisory: 3
      # qim.sp.member.events: 2
```

---

## 5. Redis 구성

### 5.1 연결 설정

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
```

### 5.2 캐시 설정 (RedisConfig)

```java
// RedisConfig.java
@Bean
public CacheManager cacheManager(RedisConnectionFactory factory) {
    RedisCacheConfiguration defaultCfg = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofMinutes(30))
        .serializeValuesWith(SerializationPair.fromSerializer(
            new GenericJackson2JsonRedisSerializer()));

    RedisCacheConfiguration providerConfigCfg = defaultCfg.entryTtl(Duration.ofMinutes(60));
    RedisCacheConfiguration keycloakJwksCfg  = defaultCfg.entryTtl(Duration.ofMinutes(60));

    return RedisCacheManager.builder(factory)
        .cacheDefaults(defaultCfg)
        .withCacheConfiguration("provider-config", providerConfigCfg)  // ★ v1.9.0
        .withCacheConfiguration("keycloakJwks", keycloakJwksCfg)
        .build();
}
```

---

## 6. 모니터링 스택

### 6.1 Prometheus + Grafana

`--profile monitoring`으로 기동.

```yaml
# infra/monitoring/prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'ido'
    static_configs:
      - targets: ['onepass-ido:8083']
    metrics_path: /actuator/prometheus

  - job_name: 'q-sign'
    static_configs:
      - targets: ['onepass-qsign:8081']
    metrics_path: /actuator/prometheus

  - job_name: 'q-im'
    static_configs:
      - targets: ['onepass-qim:8082']
    metrics_path: /actuator/prometheus
```

**Spring Actuator 설정** (각 모듈 application.yml):
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

### 6.2 Loki + Promtail (로그 집계)

```yaml
# infra/monitoring/promtail-config.yml
scrape_configs:
  - job_name: 'ido-logs'
    static_configs:
      - targets: ['localhost']
        labels:
          service: 'ido'
          __path__: '/var/log/idem-hub/*.log'
    pipeline_stages:
      - json:
          expressions:
            level: level
            correlationId: correlationId
            agencyCode: agencyCode
```

**로그 레이블**: `service`, `agencyCode`, `correlationId`, `level`

### 6.3 주요 메트릭

| 메트릭 | 설명 | 구현 상태 |
|--------|------|----------|
| `handoff.issue.count` | Handoff 발급 횟수 | ❌ P3 (커스텀 메트릭 미구현) |
| `handoff.verify.count` | Handoff 검증 횟수 | ❌ P3 |
| `circuit.breaker.state` | CB 상태 | ✅ Resilience4j 자동 제공 |
| `webhook.dispatch.success` | Webhook 발송 성공률 | ❌ P3 |
| `jvm.*` | JVM 메트릭 | ✅ Actuator 자동 제공 |
| `http.server.requests` | HTTP 요청 메트릭 | ✅ Spring 자동 제공 |

---

## 7. 로컬 개발 (JAR 직접 실행)

PostgreSQL/MariaDB/Redis가 로컬에서 실행 중인 경우:

```bash
# Q-Sign
SPRING_PROFILES_ACTIVE=local \
  java -jar idem-gate/build/libs/q-sign-0.1.0-SNAPSHOT.jar &

# IdO
SPRING_PROFILES_ACTIVE=local \
  IDO_INTERNAL_SIG_SECRET=local-test-secret-32bytes-padding \
  java -jar idem-hub/build/libs/ido-0.1.0-SNAPSHOT.jar &

# Q-IM
SPRING_PROFILES_ACTIVE=local \
  java -jar idem-registry/build/libs/q-im-0.1.0-SNAPSHOT.jar &
```

---

## 8. 환경변수 참조

### 8.1 필수 환경변수 (운영 전 반드시 설정)

```bash
# IdO
IDO_HANDOFF_AES_KEY=<base64-32bytes>
IDO_HANDOFF_HMAC_SECRET=<base64-32bytes>
IDO_INTERNAL_SIG_SECRET=<32bytes-이상>
IDO_AGENCY_SUBJECT_SECRET=<32bytes-이상>
QIM_AES_SHARED_KEY=<base64-32bytes>

# Q-IM
QIM_CI_AES_KEY_V1=<base64-32bytes>
QIM_DI_SECRET=<base64-32bytes>

# Q-Sign
QSIGN_KEYCLOAK_CLIENT_SECRET=<Keycloak Admin 발급>

# 공통
SPRING_DATASOURCE_USERNAME=onepass
SPRING_DATASOURCE_PASSWORD=<secure-password>
```

### 8.2 선택 환경변수

```bash
IDO_PLATFORM_VERSION=1.0
IDO_DEFAULT_POLICY_VERSION=1.0
IDO_PROVIDER_CIRCUIT_CACHE_TTL=3600
IDO_BROKER_MODE=keycloak
```

---

*다음 문서: [12-implementation-gaps.md](12-implementation-gaps.md)*
