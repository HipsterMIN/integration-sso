# 인프라/DevOps 팀 개발 가이드

> **버전**: v2.3.0 (Sprint 10 기준)  
> **최종 수정**: 2026-05-11  
> **대상**: 인프라/DevOps 팀  
> **기술 스택**: Docker Compose / Kubernetes / Kafka / Redis / Prometheus / Grafana / Loki

---

## 목차

1. [인프라 아키텍처 개요](#1-인프라-아키텍처-개요)
2. [로컬 개발 환경 구성](#2-로컬-개발-환경-구성)
3. [Docker 이미지 빌드 및 관리](#3-docker-이미지-빌드-및-관리)
4. [Kubernetes 배포 구성](#4-kubernetes-배포-구성)
5. [ConfigMap과 Secrets 관리](#5-configmap과-secrets-관리)
6. [Feature Flag 운영 가이드](#6-feature-flag-운영-가이드)
7. [Kafka 토픽 관리](#7-kafka-토픽-관리)
8. [Redis 운영 가이드](#8-redis-운영-가이드)
9. [데이터베이스 운영 가이드](#9-데이터베이스-운영-가이드)
10. [모니터링 스택 (Prometheus + Grafana + Loki)](#10-모니터링-스택)
11. [배포 절차 및 롤백](#11-배포-절차-및-롤백)
12. [보안 운영 가이드](#12-보안-운영-가이드)
13. [장애 대응 런북 (Runbook)](#13-장애-대응-런북)
14. [성능 튜닝 가이드](#14-성능-튜닝-가이드)
15. [인프라 확장 가이드](#15-인프라-확장-가이드)

---

## 1. 인프라 아키텍처 개요

### 1.1 전체 구성도

```
[인터넷]
    │
    ▼
[Nginx / K8s Ingress]  (SSL 종단, 라우팅)
    │
    ├─→ :3001  onepass-fe    (React SPA)
    └─→ :8083  IdO           (공개 BFF — 유일한 외부 노출 서비스)
                   │
                   ├─→ :8082  Q-IM   (내부 전용 — X-Internal-Api-Key)
                   ├─→ :8081  Q-Sign (내부 전용 — X-Internal-Api-Key)
                   └─→ :8088  Keycloak (IdP)

[공유 인프라]
    ├── PostgreSQL :5432   (IdO, Q-Sign — qsign 스키마)
    ├── MariaDB :3306      (Q-IM — qim 스키마)
    ├── Redis :6379        (feSession TTL, 캐시, Redisson 분산 락)
    └── Kafka :9092        (비동기 이벤트 버스)

[모니터링]
    ├── Prometheus :9090   (메트릭 수집)
    ├── Grafana :3000      (시각화 대시보드)
    └── Loki :3100         (로그 집계)
```

### 1.2 서비스별 포트 및 역할

| 서비스 | 포트 | 외부 노출 | 역할 |
|--------|------|---------|------|
| onepass-fe | 3001 | ✅ | SPA 정적 파일 서빙 |
| IdO | 8083 | ✅ | 외부 BFF + 정책 오케스트레이터 |
| Q-IM | 8082 | ❌ | 식별 SoR (내부 전용) |
| Q-Sign | 8081 | ❌ | 인증 SoR (내부 전용) |
| Keycloak | 8088 | ❌ (어드민만) | IdP |
| PostgreSQL | 5432 | ❌ | RDBMS (IdO, Q-Sign) |
| MariaDB | 3306 | ❌ | RDBMS (Q-IM) |
| Redis | 6379 | ❌ | 캐시 + 세션 + 분산 락 |
| Kafka | 9092 | ❌ | 이벤트 스트리밍 |

> 🔒 **보안 원칙**: Q-IM(:8082), Q-Sign(:8081), DB, Redis, Kafka는 절대 외부에서 직접 접근 불가.  
> NetworkPolicy 또는 방화벽으로 반드시 차단.

---

## 2. 로컬 개발 환경 구성

### 2.1 필수 도구

```bash
# 버전 확인
docker --version      # 24.x+
docker compose version # v2.x (v1 docker-compose 아님)
kubectl version       # 1.28+
java --version        # OpenJDK 21+
node --version        # v20.x+
```

### 2.2 인프라 기동 (단계별)

```bash
cd infra/docker

# ── 단계 1: 기반 인프라 ──────────────────────────────────────────────
docker compose up -d postgres mariadb redis zookeeper kafka kafka-init

# kafka-init이 토픽을 생성할 때까지 대기 (약 30초)
docker compose logs -f kafka-init  # READY 메시지 확인 후 Ctrl+C

# ── 단계 2: 관리 도구 (선택) ──────────────────────────────────────────
docker compose up -d kafka-ui redis-insight pgadmin adminer
# kafka-ui:   http://localhost:8090
# redis-insight: http://localhost:5540
# pgadmin:    http://localhost:5050
# adminer:    http://localhost:8091 (MariaDB 관리)

# ── 단계 3: 애플리케이션 ──────────────────────────────────────────────
docker compose --profile app up -d

# ── 단계 4: Keycloak (선택) ────────────────────────────────────────────
docker compose --profile keycloak up -d

# ── 단계 5: 모니터링 (선택) ────────────────────────────────────────────
docker compose --profile monitoring up -d
# prometheus: http://localhost:9090
# grafana:    http://localhost:3000 (admin/admin)
```

### 2.3 로컬 개발 시 Gradle 직접 실행

Docker 없이 JAR 직접 실행 (DB/Redis/Kafka만 Docker로 실행):

```bash
# 백엔드 빌드 (Docker unavailable 환경)
DOCKER_UNAVAILABLE=true ./gradlew :idem-hub:bootRun \
  --args='--spring.profiles.active=local' &

DOCKER_UNAVAILABLE=true ./gradlew :idem-registry:bootRun \
  --args='--spring.profiles.active=local' &

DOCKER_UNAVAILABLE=true ./gradlew :idem-gate:bootRun \
  --args='--spring.profiles.active=local' &

# 프론트엔드
cd idem-console/frontend && npm run dev &
```

### 2.4 헬스체크 스크립트

```bash
#!/bin/bash
# scripts/health-check.sh

services=(
    "Q-Sign:http://localhost:8081/actuator/health"
    "Q-IM:http://localhost:8082/actuator/health"
    "IdO:http://localhost:8083/actuator/health"
)

for entry in "${services[@]}"; do
    name="${entry%%:*}"
    url="${entry#*:}"
    status=$(curl -s -o /dev/null -w "%{http_code}" "$url")
    if [ "$status" = "200" ]; then
        echo "✅ $name — UP"
    else
        echo "❌ $name — DOWN (HTTP $status)"
    fi
done
```

---

## 3. Docker 이미지 빌드 및 관리

### 3.1 멀티스테이지 Dockerfile 구조

모든 Spring Boot 서비스는 **멀티스테이지 빌드 + non-root 사용자** 패턴을 적용합니다.

```dockerfile
# idem-hub/Dockerfile
# ── Stage 1: Build ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY . .
RUN ./gradlew :idem-hub:bootJar -x test --no-daemon

# ── Stage 2: Runtime ────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="smes-devops@smes.go.kr"
LABEL version="2.3.0"

# non-root 사용자 (보안)
RUN addgroup -S ido && adduser -S ido -G ido
USER ido

WORKDIR /app
COPY --from=builder /build/idem-hub/build/libs/idem-hub-*.jar app.jar

# JVM 최적화
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8083
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### 3.2 이미지 빌드 절차

```bash
# 1. Gradle 빌드 (JAR 생성)
./gradlew :idem-gate:bootJar :idem-hub:bootJar :idem-registry:bootJar -x test --no-daemon

# 2. Docker 이미지 빌드 (멀티스테이지)
docker build -f idem-gate/Dockerfile -t onepass-qsign:2.3.0 -t onepass-qsign:latest .
docker build -f idem-hub/Dockerfile     -t onepass-ido:2.3.0   -t onepass-ido:latest .
docker build -f idem-registry/Dockerfile    -t onepass-qim:2.3.0   -t onepass-qim:latest .

# FE 이미지
cd onepass-fe
docker build -t onepass-react:2.3.0 -t onepass-react:latest .

# 3. 컨테이너 레지스트리 푸시
docker tag onepass-ido:2.3.0 registry.smes.go.kr/onepass/ido:2.3.0
docker push registry.smes.go.kr/onepass/ido:2.3.0
```

### 3.3 이미지 보안 스캔

```bash
# Trivy로 취약점 스캔 (CI/CD 파이프라인에 포함 권장)
trivy image --severity HIGH,CRITICAL onepass-ido:latest

# Docker Scout (대안)
docker scout cves onepass-ido:latest
```

---

## 4. Kubernetes 배포 구성

### 4.1 디렉토리 구조

```
infra/k8s/
├── configmaps/
│   ├── ido-configmap.yml       # IdO 비민감 환경변수 (Feature Flags 포함)
│   └── ...
├── deployments/
│   └── ido-deployment.yml      # IdO K8s Deployment
├── secrets/
│   └── ido-secrets-template.yml # Secrets 템플릿 (실제 값 없음 — Git 안전)
└── README.md
```

### 4.2 IdO Deployment 핵심 설정

```yaml
# infra/k8s/deployments/ido-deployment.yml (핵심 부분)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ido
  namespace: smes
  labels:
    app: ido
    version: "2.3.0"
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0    # Zero-downtime 배포
      maxSurge: 1

  template:
    spec:
      # ── 보안 컨텍스트 ─────────────────────────────────────
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000

      containers:
        - name: ido
          image: onepass-ido:2.3.0  # ← 배포 시 버전 업데이트
          ports:
            - containerPort: 8083

          # ── 환경변수 (ConfigMap + Secrets 분리) ─────────────
          envFrom:
            - configMapRef:
                name: ido-config     # 비민감 설정
            - secretRef:
                name: ido-secrets    # 민감 정보

          # ── 리소스 제한 ──────────────────────────────────────
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "1000m"

          # ── 헬스프로브 ────────────────────────────────────────
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8083
            initialDelaySeconds: 30
            periodSeconds: 10
            failureThreshold: 3

          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8083
            initialDelaySeconds: 60
            periodSeconds: 30
            failureThreshold: 3

      # ── 멀티 AZ 분산 ──────────────────────────────────────────
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: ido
```

### 4.3 배포 명령어

```bash
# ConfigMap 적용
kubectl apply -f infra/k8s/configmaps/ido-configmap.yml -n smes

# Deployment 적용
kubectl apply -f infra/k8s/deployments/ido-deployment.yml -n smes

# 롤링 업데이트 (이미지 태그 변경)
kubectl set image deployment/ido ido=onepass-ido:2.3.0 -n smes

# 배포 상태 확인
kubectl rollout status deployment/ido -n smes

# 파드 상태 확인
kubectl get pods -n smes -l app=ido
```

---

## 5. ConfigMap과 Secrets 관리

### 5.1 분리 원칙

```
ConfigMap  → 비민감 설정 (Feature Flags, URL, 타임아웃 등)
             Git 커밋 가능, kubectl get으로 평문 확인 가능
             파일: infra/k8s/configmaps/*.yml

Secrets    → 민감 정보 (비밀번호, API 키, AES 키 등)
             Git 커밋 금지, Base64 인코딩 저장
             파일: infra/k8s/secrets/*-template.yml (실제 값 없이 구조만)
```

### 5.2 IdO ConfigMap 전체 항목 (Sprint 10 기준)

```yaml
# infra/k8s/configmaps/ido-configmap.yml
data:
  # 서버 기본
  SERVER_PORT: "8083"
  SPRING_PROFILES_ACTIVE: "prod"

  # NICE API (비민감)
  NICE_RETURN_URL: "https://smes.go.kr/auth/nice/callback"
  NICE_TIMEOUT_SECONDS: "10"

  # OACX
  OACX_DEBUG_MODE: "false"
  OACX_PROVIDER_KEY_PATH: "/app/config/oacx"

  # Q-IM 연동
  QIM_BASE_URL: "http://q-im-service:8082"
  QIM_AES_IV_LENGTH: "12"
  QIM_AES_TRANSFORMATION: "AES/GCM/NoPadding"
  QIM_RECEIVER_AUDIT: "true"
  QIM_SP_MEMBER_TOPIC: "platform.qim.sp-member"

  # 통합인증 서버
  INTEGRATION_AUTH_BASE_URL: "https://auth.smes.go.kr"
  INTEGRATION_AUTH_TIMEOUT_SECONDS: "10"

  # Keycloak
  KEYCLOAK_BASE_URL: "https://keycloak.smes.go.kr"
  KEYCLOAK_REALM: "smes"
  KEYCLOAK_REDIRECT_URI: "https://smes.go.kr/auth/callback"

  # CORS
  CORS_ORIGIN_DEV: "http://localhost:3000"
  CORS_ORIGIN_PROD: "https://smes.go.kr"

  # OpenTelemetry (S9-T4)
  OTLP_ENDPOINT: "http://otel-collector:4318/v1/traces"
  TRACING_SAMPLING_PROBABILITY: "0.1"
  IDO_TRACING_AUTH_ASPECT_ENABLED: "true"  # F-05

  # Feature Flags (S9-T6, S10)
  IDO_RATE_LIMIT_ENABLED: "true"            # F-02
  IDO_RATE_LIMIT_DEFAULT_TPS: "20"
  IDO_RATE_LIMIT_DEFAULT_DAILY: "1000"
  IDO_AUTH_RL_ENABLED: "true"              # F-01
  IDO_AUDIT_KAFKA_ENABLED: "true"          # F-03
  IDO_AUDIT_DB_ENABLED: "true"             # F-04
  IDO_AUDIT_EXECUTOR_CORE: "2"
  IDO_AUDIT_EXECUTOR_MAX: "8"
  IDO_AUDIT_EXECUTOR_QUEUE: "200"
```

### 5.3 Secrets 생성 방법

```bash
# ✅ kubectl 명령어로 직접 생성 (파일 미생성)
kubectl create secret generic ido-secrets \
  --from-literal=IDO_HANDOFF_AES_KEY=<base64-32bytes> \
  --from-literal=IDO_HANDOFF_HMAC_SECRET=<base64-32bytes> \
  --from-literal=IDO_INTERNAL_SIG_SECRET=<32bytes-이상> \
  --from-literal=IDO_AGENCY_SUBJECT_SECRET=<32bytes-이상> \
  --from-literal=QIM_AES_SHARED_KEY=<base64-32bytes> \
  --from-literal=SPRING_DATASOURCE_PASSWORD=<password> \
  -n smes

kubectl create secret generic qim-secrets \
  --from-literal=QIM_CI_AES_KEY_V1=<base64-32bytes> \
  --from-literal=QIM_DI_SECRET=<base64-32bytes> \
  --from-literal=SPRING_DATASOURCE_PASSWORD=<password> \
  -n smes

# ✅ Secrets 확인 (마스킹)
kubectl get secret ido-secrets -n smes -o yaml | grep -v "  [A-Z].*: "

# ❌ 금지: YAML 파일에 실제 시크릿 값 포함 후 Git 커밋
```

### 5.4 ConfigMap 업데이트 절차

```bash
# 1. 파일 수정
vi infra/k8s/configmaps/ido-configmap.yml

# 2. 적용
kubectl apply -f infra/k8s/configmaps/ido-configmap.yml -n smes

# 3. 파드 재시작 (환경변수 적용)
kubectl rollout restart deployment/ido -n smes

# 4. 상태 확인
kubectl rollout status deployment/ido -n smes
```

---

## 6. Feature Flag 운영 가이드

### 6.1 Feature Flag 목록 (Sprint 10 기준, 총 14개)

| Flag 이름 | 기본값 | 설명 | 제어 방식 |
|---------|------|------|---------|
| `IDO_TRACING_AUTH_ASPECT_ENABLED` | `true` | 인증 흐름 분산 추적 | `@Value` + Guard |
| `IDO_RATE_LIMIT_ENABLED` | `true` | 기관별 API Rate Limit | `@ConditionalOnProperty` |
| `IDO_RATE_LIMIT_DEFAULT_TPS` | `20` | 기관별 초당 요청 한도 | `@Value` |
| `IDO_RATE_LIMIT_DEFAULT_DAILY` | `1000` | 기관별 일일 요청 한도 | `@Value` |
| `IDO_AUTH_RL_ENABLED` | `true` | IP 기반 Auth Rate Limit | `@ConditionalOnProperty` |
| `IDO_AUDIT_KAFKA_ENABLED` | `true` | 감사 로그 Kafka 발행 | `@ConditionalOnProperty` |
| `IDO_AUDIT_DB_ENABLED` | `true` | 감사 로그 DB 저장 | `@ConditionalOnProperty` |
| `IDO_AUDIT_EXECUTOR_CORE` | `2` | 감사 로그 스레드 풀 코어 | `@Value` |
| `IDO_AUDIT_EXECUTOR_MAX` | `8` | 감사 로그 스레드 풀 최대 | `@Value` |
| `IDO_AUDIT_EXECUTOR_QUEUE` | `200` | 감사 로그 큐 크기 | `@Value` |
| `IDO_RETENTION_ENABLED` | `false` | 개인정보 파기 스케줄러 | `@ConditionalOnProperty` |
| `IDO_RETENTION_DRY_RUN` | `false` | 파기 DRY_RUN 모드 | `@Value` |
| `IDO_RETENTION_DAYS` | `1095` | PII 보유 기간 (일) | `@Value` |
| `QIM_RECEIVER_AUDIT` | `true` | Q-IM 감사 수신 활성화 | `@Value` |

### 6.2 Flag 변경 절차

```bash
# 1. ConfigMap 수정
kubectl edit configmap ido-config -n smes
# 또는 파일 수정 후 apply

# 2. 파드 재시작 필요한 Flag (@ConditionalOnProperty)
kubectl rollout restart deployment/ido -n smes

# 3. 런타임 적용 Flag (@Value — 재시작 불필요, 단 actuator refresh 필요)
curl -X POST http://ido-service:8083/actuator/refresh

# 4. 적용 확인
kubectl logs -l app=ido --tail=50 -n smes | grep "Feature Flag"
```

### 6.3 긴급 Feature Flag 비활성화

장애 발생 시 특정 기능을 즉시 비활성화하는 절차:

```bash
# 예: Rate Limit 비활성화 (DDoS 우회 중 서비스 영향 최소화)
kubectl patch configmap ido-config -n smes \
  --type merge \
  -p '{"data":{"IDO_RATE_LIMIT_ENABLED":"false"}}'
kubectl rollout restart deployment/ido -n smes

# 예: 감사 로그 Kafka 발행 중단 (Kafka 장애 시)
kubectl patch configmap ido-config -n smes \
  --type merge \
  -p '{"data":{"IDO_AUDIT_KAFKA_ENABLED":"false"}}'
kubectl rollout restart deployment/ido -n smes
```

---

## 7. Kafka 토픽 관리

### 7.1 토픽 상세 설정

| 토픽 | 파티션 | RF | ISR | 보존기간 | 압축 | 설명 |
|------|--------|----|----|---------|------|------|
| `qsign.auth.events` | 12 | 3 | 2 | 7일 | lz4 | 인증 이벤트 |
| `qim.user.events` | 6 | 3 | 2 | 7일 | lz4 | 사용자 이벤트 |
| `ido.handoff.events` | 12 | 3 | 2 | 7일 | lz4 | Handoff 이벤트 |
| `platform.session.advisory` | 12 | 3 | 2 | 1일 | lz4 | 세션 어드바이저리 |
| `platform.audit.log` | 6 | 3 | 2 | 180일 | snappy | 감사 로그 |
| `qim.sp.member.events` | 6 | 3 | 2 | 7일 | lz4 | SP 회원 이벤트 |

> **RF**: Replication Factor (복제 팩터)  
> **ISR**: In-Sync Replicas (최소 동기 복제본 수)

### 7.2 토픽 관리 명령어

```bash
# 토픽 목록 확인
kafka-topics.sh --bootstrap-server kafka:9092 --list

# 토픽 상세 확인
kafka-topics.sh --bootstrap-server kafka:9092 \
  --describe --topic qim.user.events

# 새 토픽 생성
kafka-topics.sh --bootstrap-server kafka:9092 \
  --create \
  --topic platform.new.events \
  --partitions 6 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config compression.type=lz4

# 토픽 보존 기간 변경 (운영 주의)
kafka-configs.sh --bootstrap-server kafka:9092 \
  --entity-type topics \
  --entity-name platform.audit.log \
  --alter \
  --add-config retention.ms=15552000000  # 180일

# 컨슈머 그룹 Lag 확인 (★ 장애 모니터링 핵심)
kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group ido-consumer-group
```

### 7.3 Consumer Lag 모니터링

```bash
# 스크립트: 모든 컨슈머 그룹 Lag 합산
kafka-consumer-groups.sh --bootstrap-server kafka:9092 --list | \
  xargs -I{} kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
  --describe --group {} 2>/dev/null | \
  awk 'NR>1 {sum += $5} END {print "Total LAG:", sum}'

# Lag이 1000 초과 시 알림 설정 (Prometheus AlertRule 예시)
# alert: KafkaConsumerLagHigh
# expr: kafka_consumer_lag_sum > 1000
# for: 5m
```

### 7.4 Dead Letter Topic (DLQ) 설정 (미구현 — GAP-IDO-09)

```bash
# DLQ 토픽 생성 (향후 구현 시 사전 생성)
for topic in qsign.auth.events qim.user.events ido.handoff.events; do
    kafka-topics.sh --bootstrap-server kafka:9092 \
      --create \
      --topic "${topic}.dlt" \
      --partitions 3 \
      --replication-factor 3 \
      --config retention.ms=2592000000  # 30일 보존
done

# Spring Boot에서 DLQ 연결 (개발팀 작업 필요):
# @Bean DeadLetterPublishingRecoverer recoverer(KafkaTemplate<?, ?> template) {
#     return new DeadLetterPublishingRecoverer(template);
# }
```

---

## 8. Redis 운영 가이드

### 8.1 주요 키 패턴

| 키 패턴 | TTL | 설명 |
|---------|-----|------|
| `fe-session:{sessionId}` | 30분 슬라이딩 (절대 8시간) | FE 세션 |
| `nice-session:{sessionId}` | 10분 | NICE 본인인증 세션 |
| `provider-config:{provider}` | 60분 | OAuth Provider 설정 캐시 |
| `keycloakJwks:{realm}` | 60분 | Keycloak JWKS 캐시 |
| `rate-limit:{agencyCode}:{bucket}` | 1일 | Rate Limit 버킷 |
| `redisson-lock:{resource}` | 최대 5초 | Redisson 분산 락 |

### 8.2 Redis 모니터링

```bash
# Redis CLI 접속 (로컬)
redis-cli -h localhost -p 6379

# 메모리 사용량 확인
redis-cli info memory | grep -E "used_memory_human|maxmemory"

# 키 통계
redis-cli info keyspace

# 만료 키 확인
redis-cli --scan --pattern "fe-session:*" | wc -l

# 특정 세션 조회
redis-cli hgetall "fe-session:{sessionId}"

# 수동 세션 삭제 (긴급)
redis-cli del "fe-session:{sessionId}"
```

### 8.3 Redis 운영 설정 권장값

```yaml
# redis/redis.conf (운영 환경)
maxmemory 2gb
maxmemory-policy allkeys-lru   # 메모리 초과 시 LRU 방식 제거

# 스냅샷 설정
save 900 1     # 15분마다 1개 이상 변경 시 저장
save 300 10    # 5분마다 10개 이상 변경 시 저장
save 60 10000  # 1분마다 10000개 이상 변경 시 저장

# AOF (Append Only File) — 데이터 손실 최소화
appendonly yes
appendfsync everysec
```

### 8.4 SLO 후 Redis 키 정리 확인

SLO API 호출 후 feSession이 Redis에서 삭제되었는지 확인:

```bash
# SLO 전 세션 확인
redis-cli exists "fe-session:{sessionId}"
# → (integer) 1

# SLO API 호출 (IdO가 처리)
# POST /api/v1/slo/initiate

# SLO 후 확인
redis-cli exists "fe-session:{sessionId}"
# → (integer) 0 (삭제 확인)
```

---

## 9. 데이터베이스 운영 가이드

### 9.1 스키마 구성

| DB | 포트 | 스키마 | 담당 모듈 |
|----|------|--------|---------|
| PostgreSQL | 5432 | `qsign` | Q-Sign |
| PostgreSQL | 5432 | `ido` (감사로그 등) | IdO |
| MariaDB | 3306 | `qim` | Q-IM |

### 9.2 Flyway 마이그레이션 관리

```bash
# 마이그레이션 현황 확인 (로컬)
./gradlew :idem-registry:flywayInfo --args='--spring.profiles.active=local'
./gradlew :idem-gate:flywayInfo --args='--spring.profiles.active=local'

# 마이그레이션 실행 (자동 — Spring Boot 시작 시)
# spring.flyway.enabled=true (기본값)

# 운영 환경 체크섬 검증
./gradlew :idem-registry:flywayValidate --args='--spring.profiles.active=prod'

# ⚠️ 체크섬 오류 발생 시 (로컬만)
./gradlew :idem-registry:flywayRepair --args='--spring.profiles.active=local'
# 운영에서는 절대 사용 금지!
```

### 9.3 데이터베이스 백업 절차

```bash
# PostgreSQL 백업
pg_dump -h localhost -U onepass -d qsign \
  --format=custom \
  --file=/backup/qsign-$(date +%Y%m%d).dump

# MariaDB 백업
mysqldump -h localhost -u qim -p qim \
  --single-transaction \
  --routines \
  --triggers \
  > /backup/qim-$(date +%Y%m%d).sql

# K8s에서 CronJob으로 자동 백업 설정 권장
# (현재 미구현 — GAP)
```

### 9.4 슬로우 쿼리 모니터링

```sql
-- PostgreSQL 슬로우 쿼리 (1초 이상)
SELECT pid, now() - pg_stat_activity.query_start AS duration,
       query, state
FROM pg_stat_activity
WHERE (now() - pg_stat_activity.query_start) > INTERVAL '1 second'
  AND state != 'idle';

-- MariaDB 슬로우 쿼리 로그 활성화
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;  -- 1초 이상
```

---

## 10. 모니터링 스택

### 10.1 Prometheus 스크레이핑 설정

```yaml
# infra/monitoring/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'ido'
    static_configs:
      - targets: ['onepass-ido:8083']
    metrics_path: /actuator/prometheus
    scrape_interval: 10s

  - job_name: 'q-sign'
    static_configs:
      - targets: ['onepass-qsign:8081']
    metrics_path: /actuator/prometheus

  - job_name: 'q-im'
    static_configs:
      - targets: ['onepass-qim:8082']
    metrics_path: /actuator/prometheus

  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka:9308']  # JMX Exporter 포트

  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']
```

### 10.2 핵심 알림 규칙 (AlertManager)

```yaml
# infra/monitoring/alerts.yml
groups:
  - name: onepass-critical
    rules:
      # 서비스 다운
      - alert: ServiceDown
        expr: up{job=~"ido|q-sign|q-im"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.job }} 서비스 다운"
          description: "{{ $labels.instance }} 가 1분 이상 응답하지 않습니다."

      # API 오류율 5% 초과
      - alert: HighErrorRate
        expr: |
          rate(http_server_requests_seconds_count{status=~"5.."}[5m]) /
          rate(http_server_requests_seconds_count[5m]) > 0.05
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.job }} API 오류율 5% 초과"

      # Kafka Consumer Lag 급증
      - alert: KafkaConsumerLagHigh
        expr: kafka_consumer_lag_sum > 5000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Kafka Consumer Lag {{ $value }} 초과"

      # Redis 메모리 80% 초과
      - alert: RedisMemoryHigh
        expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Redis 메모리 사용률 80% 초과"

      # JVM Heap 85% 초과
      - alert: JvmHeapHigh
        expr: |
          jvm_memory_used_bytes{area="heap"} /
          jvm_memory_max_bytes{area="heap"} > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.job }} JVM 힙 사용률 85% 초과"
```

### 10.3 Grafana 대시보드 구성

권장 대시보드 목록:

| 대시보드 | 패널 | 참고 |
|---------|------|------|
| **OnePass 개요** | 서비스 상태, 요청 TPS, 오류율, 응답시간 P95 | 커스텀 작성 |
| **JVM 모니터링** | Heap/Non-Heap, GC 빈도, Thread Pool | Grafana #4701 |
| **Kafka** | Consumer Lag, 메시지 TPS, 파티션 | Grafana #7589 |
| **Redis** | 메모리, 키 수, 명령어 TPS | Grafana #763 |
| **PostgreSQL** | 연결 수, 슬로우 쿼리, 트랜잭션/초 | Grafana #9628 |

### 10.4 Loki 로그 쿼리 예시

```logql
# IdO 오류 로그
{service="ido"} |= "ERROR"

# SLO 처리 로그
{service="ido"} |= "SLO" | json | level="INFO"

# 특정 기관 관련 로그
{service="ido"} | json | agencyCode="AGENCY_001"

# 응답 시간 500ms 초과
{service="ido"} | json | duration > 500

# 인증 실패 감사 로그
{service="ido"} |~ "AUDIT.*AUTH_FAIL"
```

---

## 11. 배포 절차 및 롤백

### 11.1 표준 배포 절차

```bash
# 1. 이미지 빌드 & 푸시 (CI/CD에서 자동화)
VERSION="2.3.0"
docker build -f idem-hub/Dockerfile -t onepass-ido:${VERSION} .
docker push registry.smes.go.kr/onepass/ido:${VERSION}

# 2. Deployment 이미지 업데이트
kubectl set image deployment/ido ido=onepass-ido:${VERSION} -n smes

# 3. 롤아웃 모니터링
kubectl rollout status deployment/ido -n smes --timeout=5m

# 4. 배포 후 헬스체크
for i in $(seq 1 5); do
    curl -s http://ido-service:8083/actuator/health | python3 -m json.tool
    sleep 10
done

# 5. 배포 확인
kubectl get pods -n smes -l app=ido
# → 모든 파드가 Running 상태, READY 2/2 확인
```

### 11.2 롤백 절차

```bash
# 즉시 롤백 (직전 버전으로)
kubectl rollout undo deployment/ido -n smes

# 특정 버전으로 롤백
kubectl rollout history deployment/ido -n smes  # 이력 확인
kubectl rollout undo deployment/ido --to-revision=3 -n smes

# 롤백 상태 확인
kubectl rollout status deployment/ido -n smes

# 롤백 후 헬스체크
curl http://ido-service:8083/actuator/health
```

### 11.3 Blue-Green 배포 (권장 — 중요 릴리즈 시)

```bash
# 1. Green 환경 배포 (새 버전)
kubectl apply -f ido-deployment-green.yml -n smes

# 2. Green 헬스체크
kubectl port-forward svc/ido-green 8083:8083 -n smes
curl http://localhost:8083/actuator/health

# 3. 트래픽 전환 (Service 셀렉터 변경)
kubectl patch service ido-service -n smes \
  -p '{"spec":{"selector":{"version":"green"}}}'

# 4. Blue 환경 제거 (안정화 확인 후)
kubectl delete deployment ido-blue -n smes
```

---

## 12. 보안 운영 가이드

### 12.1 네트워크 정책 (NetworkPolicy)

```yaml
# Q-IM: IdO에서만 접근 허용
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: qim-allow-from-ido
  namespace: smes
spec:
  podSelector:
    matchLabels:
      app: q-im
  policyTypes:
    - Ingress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: ido
      ports:
        - protocol: TCP
          port: 8082
```

### 12.2 TLS 인증서 관리

```bash
# cert-manager를 통한 Let's Encrypt 인증서 발급
kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: onepass-tls
  namespace: smes
spec:
  secretName: onepass-tls-secret
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  commonName: smes.go.kr
  dnsNames:
    - smes.go.kr
    - auth.smes.go.kr
EOF
```

### 12.3 AES 키 로테이션 절차 (90일 주기)

```bash
# 1. 새 AES 키 생성
NEW_KEY=$(openssl rand -base64 32)
echo "새 키: ${NEW_KEY}"  # 안전한 곳에 별도 보관

# 2. Secret 업데이트
kubectl create secret generic ido-secrets \
  --from-literal=IDO_HANDOFF_AES_KEY=${NEW_KEY} \
  --dry-run=client -o yaml | kubectl apply -f - -n smes

# 3. 애플리케이션 재시작 (새 키 적용)
kubectl rollout restart deployment/ido -n smes

# 4. 재시작 완료 확인
kubectl rollout status deployment/ido -n smes

# 5. 키 로테이션 감사 로그 확인
kubectl logs -l app=ido -n smes | grep "KEY_ROTATION"
```

### 12.4 접근 감사 로그 보존

```yaml
# infra/k8s/configmaps/ido-configmap.yml
data:
  IDO_AUDIT_KAFKA_ENABLED: "true"   # Kafka에 감사 로그 발행
  IDO_AUDIT_DB_ENABLED: "true"      # DB에도 이중 저장

# platform.audit.log 토픽: 180일 보존 (법적 보관 의무)
```

---

## 13. 장애 대응 런북 (Runbook)

### Runbook 1: IdO 서비스 응답 없음

```
증상: IdO :8083 응답 없음, FE에서 API 타임아웃 발생

1. 파드 상태 확인
   kubectl get pods -n smes -l app=ido

2. 파드 로그 확인
   kubectl logs -l app=ido --tail=100 -n smes | grep -E "ERROR|Exception"

3. OOM 확인
   kubectl describe pods -l app=ido -n smes | grep -A5 "OOMKilled"
   → 메모리 부족: limits 증가 또는 JVM 힙 조정

4. 재시작
   kubectl rollout restart deployment/ido -n smes

5. 원인 분석
   kubectl logs -l app=ido --previous -n smes  # 이전 파드 로그
```

### Runbook 2: Kafka Consumer Lag 급증

```
증상: kafka-ui에서 Consumer Lag이 급격히 증가

1. 원인 파악
   kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
     --describe --group ido-consumer-group

2. Outbox 레코드 확인 (DB)
   SELECT status, COUNT(*) FROM outbox_record GROUP BY status;

3. 임시 조치: 컨슈머 재시작
   kubectl rollout restart deployment/ido -n smes

4. Kafka 브로커 상태 확인
   kafka-broker-api-versions.sh --bootstrap-server kafka:9092

5. 파티션 재할당 (극단적 조치)
   kafka-reassign-partitions.sh ...
```

### Runbook 3: feSession Redis 삭제 실패 (SLO 오류)

```
증상: SLO 후에도 Redis에 feSession 키 잔존

1. 수동 확인
   redis-cli --scan --pattern "fe-session:*" | head -20

2. 수동 삭제
   redis-cli del "fe-session:{sessionId}"

3. 전체 잔존 세션 삭제 (극단적)
   redis-cli --scan --pattern "fe-session:*" | xargs redis-cli del

4. 로그 확인 (SLO 실패 원인)
   kubectl logs -l app=ido -n smes | grep "SLO.*ERROR"
```

### Runbook 4: DB 연결 풀 고갈

```
증상: HikariPool Timeout, DB 연결 불가

1. 현재 연결 수 확인 (PostgreSQL)
   SELECT count(*) FROM pg_stat_activity WHERE state != 'idle';

2. 슬로우 쿼리 확인
   SELECT pid, query, now() - query_start AS duration
   FROM pg_stat_activity
   WHERE state != 'idle' AND now() - query_start > '5 seconds';

3. 장시간 실행 쿼리 종료
   SELECT pg_terminate_backend({pid});

4. HikariCP 설정 조정 (임시)
   # application.yml
   spring.datasource.hikari.maximum-pool-size: 20  # 기본 10 → 20
```

---

## 14. 성능 튜닝 가이드

### 14.1 JVM 튜닝

```bash
# K8s 컨테이너 환경 최적화 (Dockerfile/deployment)
JAVA_OPTS="-XX:+UseContainerSupport \
           -XX:MaxRAMPercentage=75.0 \
           -XX:InitialRAMPercentage=50.0 \
           -XX:+UseG1GC \
           -XX:MaxGCPauseMillis=200 \
           -XX:+HeapDumpOnOutOfMemoryError \
           -XX:HeapDumpPath=/tmp/oom-dump.hprof \
           -Djava.security.egd=file:/dev/./urandom"
```

### 14.2 Kafka 컨슈머 성능

```yaml
# 고처리량 토픽 (qsign.auth.events, ido.handoff.events)
spring:
  kafka:
    listener:
      concurrency: 6       # 파티션 수의 절반
      ack-mode: batch      # 배치 ACK

    consumer:
      fetch-min-size: 1    # byte
      fetch-max-wait: 500ms
      max-poll-records: 500
```

### 14.3 Redis 연결 풀

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 16    # 최대 연결 수
          max-idle: 8       # 유휴 최대
          min-idle: 4       # 유휴 최소
          max-wait: 2000ms  # 연결 대기 타임아웃
```

---

## 15. 인프라 확장 가이드

### 15.1 새 서비스 추가 절차

새 Spring Boot 서비스를 시스템에 추가하는 경우:

```
1. Gradle 모듈 추가
   settings.gradle: include 'new-service'
   new-service/build.gradle 작성

2. Dockerfile 작성
   new-service/Dockerfile (멀티스테이지 + non-root)

3. docker-compose.yml 추가
   infra/docker/docker-compose.yml에 서비스 블록 추가

4. K8s 매니페스트 작성
   infra/k8s/configmaps/new-service-configmap.yml
   infra/k8s/deployments/new-service-deployment.yml
   infra/k8s/secrets/new-service-secrets-template.yml

5. 모니터링 추가
   prometheus.yml에 scrape_config 추가
   필요 시 Grafana 대시보드 패널 추가

6. 문서 업데이트
   docs/development/에 새 서비스 모듈 문서 추가
   README.md 아키텍처 섹션 업데이트
```

### 15.2 Kafka 파티션 증가

```bash
# 파티션 수 증가 (감소는 불가)
kafka-topics.sh --bootstrap-server kafka:9092 \
  --alter \
  --topic qim.user.events \
  --partitions 12  # 6 → 12

# ⚠️ 주의: 파티션 증가 후 컨슈머 그룹 리밸런싱 발생
# → 서비스 재시작 or 컨슈머 concurrency 조정 필요
```

### 15.3 Redis 클러스터 전환

현재는 단일 Redis 구성입니다. 트래픽 증가 시 클러스터 전환:

```yaml
# application.yml — 클러스터 전환
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-1:6379
          - redis-2:6379
          - redis-3:6379
        max-redirects: 3
```

---

*이전 문서: [guide-frontend.md](guide-frontend.md)*  
*관련 문서: [11-infrastructure.md](11-infrastructure.md) · [FEATURE_FLAGS.md](../FEATURE_FLAGS.md)*
