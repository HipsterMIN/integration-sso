# OnePass 통합인증 플랫폼 — 운영 배포 가이드

> **대상 독자**: SI 배포 담당자, 운영 엔지니어  
> **브랜치**: `develop`  
> **최종 수정**: 2026-05-21

---

## 목차

1. [서비스 구성 개요](#1-서비스-구성-개요)
2. [고객사 환경에서 필요한 정보 목록](#2-고객사-환경에서-필요한-정보-목록)
3. [Secret 준비 목록](#3-secret-준비-목록)
4. [환경변수 전체 목록](#4-환경변수-전체-목록)
5. [Kafka Topic 목록](#5-kafka-topic-목록)
6. [DB 스키마 목록](#6-db-스키마-목록)
7. [로컬 테스트 환경 실행 (Docker Compose)](#7-로컬-테스트-환경-실행-docker-compose)
8. [Minikube 테스트 환경 실행](#8-minikube-테스트-환경-실행)
9. [Helm / K8s 운영 배포](#9-helm--k8s-운영-배포)
10. [Smoke Test 실행](#10-smoke-test-실행)
11. [운영 배포 전 체크리스트](#11-운영-배포-전-체크리스트)
12. [Rollback 방법](#12-rollback-방법)
13. [장애 대응 1차 확인 절차](#13-장애-대응-1차-확인-절차)

---

## 1. 서비스 구성 개요

```
┌──────────────────────────────────────────────────────────┐
│                   OnePass 통합인증 플랫폼                  │
├────────────────────┬─────────────────────────────────────┤
│ 서비스             │ 역할                                  │
├────────────────────┼─────────────────────────────────────┤
│ Q-Sign (:8081)     │ 인증 결과 SoR (OIDC/PKCE 처리)       │
│ Q-IM   (:8082)     │ 식별·매핑 SoR (CI/DI 관리)           │
│ IdO    (:8083)     │ 정책 오케스트레이터 + FE BFF          │
│ Agency-Stub(:8084) │ 유관기관 시뮬레이터 (PoC 전용)        │
├────────────────────┼─────────────────────────────────────┤
│ PostgreSQL (:5432) │ Q-Sign / IdO / Agency-Stub DB        │
│ PostgreSQL 스키마 qim │ Q-IM (D1: MariaDB 제거, 같은 인스턴스) │
│ Redis      (:6379) │ 세션/캐시/Rate Limit                 │
│ Kafka      (:9092) │ 이벤트 스트리밍                      │
│ Keycloak   (:8088) │ OIDC 브로커 (mode=keycloak 시)       │
└────────────────────┴─────────────────────────────────────┘
```

### 서비스 간 통신 흐름

```
기관 앱 → IdO(:8083)
               ↓ (내부 API, X-Internal-Api-Key)
          Q-IM(:8082)   Q-Sign(:8081)
               ↓
                PostgreSQL
               ↓
             Redis ← → Kafka
```

---

## 2. 고객사 환경에서 필요한 정보 목록

배포 전 고객사로부터 반드시 수집해야 할 정보입니다.

### 2.1 네트워크 / 도메인

| 항목 | 예시 | 비고 |
|------|------|------|
| 서비스 도메인 | `onepass.example.com` | TLS 인증서 발급 기준 |
| 내부 K8s Ingress 클래스 | `nginx` / `alb` | |
| 네임스페이스 | `onepass` | 사전 생성 여부 확인 |
| Resource Quota | CPU 16코어, MEM 32GB | 최소 요구사항 |
| 노드 수 / 스펙 | 3 × (4코어, 8GB) | HA 구성 최소 |

### 2.2 TLS 인증서

```
□ 도메인 인증서 PEM 파일 (cert.pem, key.pem)
□ 또는 cert-manager ACME 설정 허용 여부
□ 중간 CA 인증서 포함 여부
```

### 2.3 DB / 인프라 엔드포인트

| 항목 | 예시 |
|------|------|
| PostgreSQL endpoint | `rds-postgres.internal:5432` |
| PostgreSQL 자격증명 | user/password |
| Redis endpoint | `redis-cluster.internal:6379` |
| Redis 비밀번호 | (있는 경우) |
| Kafka bootstrap | `kafka1:9092,kafka2:9092,kafka3:9092` |

### 2.4 Keycloak / OIDC 설정 (mode=keycloak 시)

```
□ Keycloak 서버 URL: https://keycloak.example.com
□ Realm 이름 (기본: onepass)
□ q-sign-client client secret (Admin Console에서 생성)
□ ido-client client secret (Admin Console에서 생성)
□ Callback URI 등록 허용 여부
```

### 2.5 KMS / Secret Manager

```
□ Vault URL 또는 AWS Secrets Manager ARN
□ 서비스 계정(ServiceAccount) 또는 IAM 역할 ARN
□ 접근 가능한 secret path/key 목록
```

### 2.6 방화벽 정책

| 방향 | 소스 | 대상 | 포트 | 목적 |
|------|------|------|------|------|
| Inbound | 기관 앱 | IdO | 443 | API 호출 |
| Inbound | 기관 앱 | IdO | 443 | Webhook 수신 |
| Outbound | IdO | 기관 Webhook URL | 443 | HTTPS webhook push |
| Outbound | Q-Sign | Keycloak | 8080/443 | OIDC |
| Internal | IdO | Q-IM | 8082 | 내부 API |
| Internal | IdO | Q-Sign | 8081 | 내부 API |
| Internal | 모든 앱 | PostgreSQL | 5432 | DB |
| Internal | Q-IM | PostgreSQL(qim 스키마) | 5432 | DB |
| Internal | 모든 앱 | Redis | 6379 | 캐시 |
| Internal | 모든 앱 | Kafka | 9092 | 이벤트 |

---

## 3. Secret 준비 목록

> 운영 배포 시 아래 값을 모두 준비한 후 K8s Secret 또는 Vault에 등록합니다.

| Secret 키 | 설명 | 생성 방법 |
|-----------|------|-----------|
| `QIM_INTERNAL_API_KEY` | IdO → Q-IM 내부 API 인증 키 | `openssl rand -hex 32` |
| `QSIGN_KEYCLOAK_CLIENT_SECRET` | Q-Sign Keycloak 클라이언트 시크릿 | Keycloak Admin > q-sign-client > Credentials |
| `KEYCLOAK_CLIENT_SECRET` | IdO Keycloak 클라이언트 시크릿 | Keycloak Admin > ido-client > Credentials |
| `IDO_INTERNAL_SIG_SECRET` | Q-Sign ↔ IdO HMAC-SHA256 서명 키 | `openssl rand -hex 32` |
| `IDO_HANDOFF_AES_KEY` | Handoff 암호화 AES-256-GCM 키 | `openssl rand -base64 32` |
| `IDO_HANDOFF_HMAC_KEY` | Handoff HMAC-SHA256 서명 키 | `openssl rand -base64 32` |
| `QIM_CI_AES_KEY_V1` | CI(연계정보) AES-256 암호화 키 | KMS 또는 `openssl rand -base64 32` |
| `QIM_DI_SECRET` | DI(중복가입확인정보) 생성 비밀키 | `openssl rand -hex 32` |
| `IDO_WEBHOOK_SIGNING_SECRET` | 기관 Webhook HMAC-SHA256 서명 키 | `openssl rand -hex 32` |
| `QIM_INBOUND_API_KEY_HASH` | Q-IM SP 수신 API 검증 PBKDF2 해시 | `infra/scripts/generate-api-key-hash.sh` |
| DB 자격증명 | PostgreSQL user/password | 고객사 DBA에서 발급 |
| Redis 비밀번호 | Redis AUTH (없으면 빈값) | 고객사 인프라팀 |

**Secret 생성 예시 (kubectl)**:
```bash
kubectl create secret generic idem-gate-secret \
  --namespace=onepass \
  --from-literal=QIM_INTERNAL_API_KEY="$(openssl rand -hex 32)" \
  --from-literal=QSIGN_KEYCLOAK_CLIENT_SECRET="..." \
  --from-literal=IDO_INTERNAL_SIG_SECRET="$(openssl rand -hex 32)" \
  # ... 나머지 키
```

---

## 4. 환경변수 전체 목록

상세 내용은 [`infra/docker/.env.example`](../../infra/docker/.env.example) 참조.

### 핵심 필수값 (운영 전 반드시 변경)

| 환경변수 | 기본값(PoC) | 운영 요구사항 |
|----------|------------|--------------|
| `QIM_INTERNAL_API_KEY` | `dev-qim-internal-api-key-...` | 32자 이상 무작위 |
| `IDO_INTERNAL_SIG_SECRET` | `change-me-32bytes-...` | 32자 이상 무작위 |
| `IDO_HANDOFF_AES_KEY` | `AAAA...=` | openssl rand -base64 32 |
| `IDO_HANDOFF_HMAC_KEY` | `AAAA...=` | openssl rand -base64 32 |
| `QSIGN_KEYCLOAK_CLIENT_SECRET` | `change-me` | Keycloak에서 재생성 |
| `KEYCLOAK_CLIENT_SECRET` | `change-me` | Keycloak에서 재생성 |
| `QIM_CI_AES_KEY_V1` | `AAAA...=` | KMS 주입 필수 |
| `QIM_DI_SECRET` | `default-di-secret-...` | 32자 이상 무작위 |

---

## 5. Kafka Topic 목록

> D1-b: Kafka 는 선택 의존(`IDEM_KAFKA_ENABLED`, 기본 false). Kafka 없는 설치는 `docs/install.md`. 아래 토픽은 `true` 일 때만 필요하다.

| Topic 이름 | 파티션 | 역할 | 보존 기간 |
|-----------|--------|------|-----------|
| `qsign.auth.events` | 12 | Q-Sign 인증 완료 이벤트 → IdO Pre-warming | 1시간 |
| `ido.handoff.events` | 12 | Handoff 이벤트 → 기관 Webhook 트리거 | 1년 |
| `platform.session.advisory` | 12 | AUTH_LOCKED → FE 세션 강제 종료 | 1일 |
| `platform.audit.log` | 12 | 전역 감사 로그 (법적 보존 2년) | 2년 |
| `qim.user.events` | 12 | Q-IM 사용자 상태 변경 → IdO 캐시 갱신 | compact |
| `qim.user.snapshot` | 12 | Q-IM 사용자 스냅샷 (복원용) | compact |
| `qim.sp.member.events` | 6 | SP 회원 연동 → 기관 Webhook | 30일 |
| `*.dlt` | 6 | Dead Letter Topics (처리 실패 재처리) | 7일 |

**Topic 생성**:
```bash
# Docker Compose 환경: kafka-init 컨테이너가 자동 생성
docker compose -f infra/docker/docker-compose.yml up kafka-init

# 수동 생성 (운영 Kafka):
./infra/docker/kafka/create-topics.sh
# 또는
KAFKA_BROKER=kafka:29092 KAFKA_REPLICATION_FACTOR=3 \
  KAFKA_MIN_INSYNC_REPLICAS=2 \
  ./infra/docker/kafka/create-topics.sh
```

---

## 6. DB 스키마 목록

| DB 종류 | 스키마/DB | 서비스 | 초기화 방법 |
|---------|----------|--------|-------------|
| PostgreSQL | `qsign` | Q-Sign | Flyway 자동 마이그레이션 |
| PostgreSQL | `ido` | IdO | Flyway 자동 마이그레이션 |
| PostgreSQL | `agency_stub` | Agency-Stub | Flyway 자동 마이그레이션 |
| PostgreSQL | `keycloak` | Keycloak | `infra/docker/init-db.sql` |
| PostgreSQL | 스키마 `qim` | Q-IM | Flyway 자동 마이그레이션 (`db/migration/postgresql`) |

**스키마 초기화 (Docker Compose)**:
```bash
# PostgreSQL 스키마 생성 (init-db.sql이 자동 실행)
docker compose -f infra/docker/docker-compose.yml up postgres -d
# 앱 기동 시 Flyway가 마이그레이션 자동 실행
```

---

## 7. 로컬 테스트 환경 실행 (Docker Compose)

### 7.1 사전 준비

```bash
# 1. 환경변수 파일 준비
cp infra/docker/.env.example infra/docker/.env
# 필요한 경우 .env 파일 수정

# 2. 앱 이미지 빌드 (프로젝트 루트에서)
./gradlew :idem-gate:bootJar :idem-registry:bootJar :idem-hub:bootJar :idem-tenant-sample:bootJar -x test
docker build -f idem-gate/Dockerfile -t idem-gate:latest .
docker build -f idem-registry/Dockerfile -t idem-registry:latest .
docker build -f idem-hub/Dockerfile -t idem-hub:latest .
docker build -f idem-tenant-sample/Dockerfile -t idem-tenant-sample:latest .
```

### 7.2 인프라만 기동 (DB/Redis/Kafka)

```bash
docker compose -f infra/docker/docker-compose.yml up -d
# http://localhost:8090 → Kafka UI
# http://localhost:5540 → Redis Insight
```

### 7.3 앱 포함 기동

```bash
docker compose -f infra/docker/docker-compose.yml --profile app up -d
# http://localhost:8081 → Q-Sign
# http://localhost:8082 → Q-IM
# http://localhost:8083 → IdO
# http://localhost:8084 → Agency-Stub
```

### 7.4 전체 기동 (앱 + Keycloak + 모니터링)

```bash
docker compose -f infra/docker/docker-compose.yml \
  --profile app \
  --profile keycloak \
  --profile monitoring \
  --profile tools \
  up -d
# http://localhost:8088 → Keycloak (admin/admin)
# http://localhost:9090 → Prometheus
# http://localhost:3002 → Grafana (admin/admin)
# http://localhost:5050 → pgAdmin
```

### 7.5 프론트엔드 포함 (Option B)

```bash
# React 빌드 후 Nginx 컨테이너 기동
docker compose -f infra/docker/docker-compose.yml \
  --profile app --profile optionB up -d
# http://localhost:3001 → React SPA (Nginx)
```

---

## 8. Minikube 테스트 환경 실행

```bash
# 사전 조건: minikube, helm, kubectl 설치

# 전체 셋업 (최초)
./infra/minikube/setup-minikube.sh

# 클린 재배포
./infra/minikube/setup-minikube.sh --clean

# 포트 포워딩
kubectl port-forward svc/idem-hub 8083:8083 -n onepass-dev &
kubectl port-forward svc/idem-registry 8082:8082 -n onepass-dev &
kubectl port-forward svc/idem-gate 8081:8081 -n onepass-dev &

# 상태 확인
kubectl get pods -n onepass-dev
kubectl get svc -n onepass-dev

# 로그 확인
kubectl logs -f deployment/idem-hub -n onepass-dev
```

> **host.minikube.internal**: Minikube 내부에서 호스트 OS(로컬 개발 PC)의 Docker Compose 인프라에 접근할 때 사용합니다.  
> 예: DB/Redis/Kafka를 Docker Compose로 기동하고, 앱만 K8s에 배포하는 하이브리드 구성.

---

## 9. Helm / K8s 운영 배포

### 9.1 Secret 사전 생성

```bash
NAMESPACE=onepass
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# Q-Sign Secret
kubectl create secret generic idem-gate-secret \
  --namespace=$NAMESPACE \
  --from-literal=DB_HOST="<POSTGRES_ENDPOINT>" \
  --from-literal=DB_USERNAME="<POSTGRES_USER>" \
  --from-literal=DB_PASSWORD="<POSTGRES_PASSWORD>" \
  --from-literal=KEYCLOAK_URL="<KEYCLOAK_URL>" \
  --from-literal=QSIGN_KEYCLOAK_CLIENT_SECRET="<SECRET>" \
  --from-literal=IDO_BASE_URL="http://idem-hub:8083" \
  --from-literal=IDO_INTERNAL_SIG_SECRET="<SECRET_32CHARS>" \
  --from-literal=REDIS_HOST="<REDIS_ENDPOINT>" \
  --from-literal=REDIS_PASSWORD="<REDIS_PASSWORD>" \
  --from-literal=KAFKA_SERVERS="<KAFKA_BOOTSTRAP>"

# (나머지 Secret은 infra/helm/idem/templates/secrets.yaml 참조)
```

### 9.2 Helm 배포

```bash
# 스테이징
helm upgrade --install onepass ./infra/helm/idem \
  -f infra/helm/idem/values.yaml \
  -f infra/helm/idem/values-stage.yaml \
  --namespace onepass-stage \
  --create-namespace \
  --wait --timeout=10m

# 운영
helm upgrade --install onepass ./infra/helm/idem \
  -f infra/helm/idem/values.yaml \
  -f infra/helm/idem/values-prod.yaml \
  --namespace onepass \
  --create-namespace \
  --atomic \    # 실패 시 자동 롤백
  --wait --timeout=10m
```

### 9.3 Helm 렌더링 검증 (dry-run)

```bash
helm template onepass ./infra/helm/idem \
  -f infra/helm/idem/values.yaml \
  -f infra/helm/idem/values-prod.yaml \
  --validate > /dev/null && echo "OK"
```

### 9.4 K8s manifest dry-run

```bash
helm template onepass ./infra/helm/idem \
  -f infra/helm/idem/values.yaml \
  -f infra/helm/idem/values-stage.yaml \
  | kubectl apply --dry-run=server -f -
```

---

## 10. Smoke Test 실행

### Bash (Linux/macOS)

```bash
# 로컬 Docker Compose 환경
./infra/scripts/smoke-test.sh

# CI 모드 (인프라 체크 스킵)
./infra/scripts/smoke-test.sh --ci --skip-infra \
  --ido-url http://localhost:8083 \
  --qim-url http://localhost:8082 \
  --qsign-url http://localhost:8081

# K8s 환경 (포트 포워딩 후)
./infra/scripts/smoke-test.sh \
  --ido-url http://localhost:8083 \
  --qim-url http://localhost:8082 \
  --qsign-url http://localhost:8081 \
  --skip-monitoring

# 전체 옵션 도움말
./infra/scripts/smoke-test.sh --help
```

### PowerShell (Windows)

```powershell
# 로컬 Docker Compose 환경
.\infra\scripts\smoke-test.ps1

# CI 모드
.\infra\scripts\smoke-test.ps1 -Ci -SkipInfra `
    -IdoUrl "http://localhost:8083" `
    -QimUrl "http://localhost:8082" `
    -QSignUrl "http://localhost:8081"

# 모니터링 포함 전체 검증
.\infra\scripts\smoke-test.ps1 `
    -QimApiKey "실제-운영-api-key-값"
```

---

## 11. 운영 배포 전 체크리스트

```
배포 환경 준비
□ 고객사 환경 정보 수집 완료 (섹션 2 항목 전체)
□ K8s 네임스페이스 및 Resource Quota 확인
□ TLS 인증서 Secret 생성 완료

Secret / 환경변수
□ QIM_INTERNAL_API_KEY       → 운영값 (32자 이상) 설정
□ QSIGN_KEYCLOAK_CLIENT_SECRET → Keycloak에서 재생성
□ KEYCLOAK_CLIENT_SECRET     → Keycloak에서 재생성
□ IDO_INTERNAL_SIG_SECRET    → 운영값 설정
□ IDO_HANDOFF_AES_KEY        → 운영값 설정 (90일 rotation 정책)
□ IDO_HANDOFF_HMAC_KEY       → 운영값 설정
□ QIM_CI_AES_KEY_V1          → KMS 주입 확인
□ QIM_DI_SECRET              → 운영값 설정
□ IDO_WEBHOOK_SIGNING_SECRET → 운영값 설정
□ QIM_INBOUND_API_KEY_HASH   → generate-api-key-hash.sh로 생성
□ IDO_BROKER_MODE            → qsign 또는 keycloak (팀 협의)
□ .env 파일이 git에 포함되지 않았는지 확인

DB / 인프라
□ PostgreSQL 연결 확인 (qsign, ido, agency_stub, keycloak 스키마)
□ PostgreSQL qim 스키마 연결 확인 (QIM_DB_SCHEMA=qim)
□ Redis 연결 확인
□ Kafka 연결 및 topic 생성 확인 (8개 topic + 5개 DLT)
□ Keycloak realm-export.json과 client secret 일치 확인

이미지 / 빌드
□ 앱 이미지 태그가 latest가 아닌 고정 버전 태그 사용
□ 이미지 레지스트리 접근 권한 확인
□ 모든 Dockerfile 빌드 성공 확인

배포 검증
□ helm template 렌더링 오류 없음
□ kubectl apply --dry-run 통과
□ 배포 후 모든 Pod Running 상태
□ Smoke Test 전체 통과
□ Prometheus scrape target 3개 (q-sign, q-im, ido) health=up
□ Grafana 대시보드 메트릭 표시 확인

운영 준비
□ Alert Manager 연동 (Slack/이메일)
□ 로그 수집 (Promtail → Loki) 정상 동작
□ 모니터링 대시보드(Grafana) 공유 URL 전달
□ 긴급 연락처 및 에스컬레이션 프로세스 확인
□ rollback 절차 숙지 (섹션 12)
```

---

## 12. Rollback 방법

### Docker Compose 롤백

```bash
# 이전 이미지 태그로 복원
docker compose -f infra/docker/docker-compose.yml \
  --profile app down

# 이전 버전 이미지로 기동
docker tag idem-hub:previous idem-hub:latest
docker compose -f infra/docker/docker-compose.yml \
  --profile app up -d
```

### Helm 롤백

```bash
# 배포 이력 확인
helm history onepass -n onepass

# 직전 버전으로 롤백
helm rollback onepass -n onepass

# 특정 버전으로 롤백
helm rollback onepass 2 -n onepass --wait

# 롤백 확인
kubectl rollout status deployment/idem-hub -n onepass
```

### DB 롤백 (Flyway)

```bash
# Flyway 현재 버전 확인
kubectl exec deployment/idem-hub -n onepass -- \
  curl -s http://localhost:8083/actuator/flyway | jq .

# 주의: Flyway DDL rollback은 별도 Undo 마이그레이션 스크립트 필요
# V{N}__undo_*.sql 파일 작성 후 배포
```

---

## 13. 장애 대응 1차 확인 절차

### 13.1 서비스 응답 없음

```bash
# 1. Pod 상태 확인
kubectl get pods -n onepass
kubectl describe pod <pod-name> -n onepass

# 2. 최근 로그 확인
kubectl logs <pod-name> -n onepass --tail=100

# 3. 이전 컨테이너 로그 (재시작된 경우)
kubectl logs <pod-name> -n onepass --previous --tail=100

# 4. 이벤트 확인
kubectl get events -n onepass --sort-by='.lastTimestamp' | tail -20
```

### 13.2 DB 연결 실패

```bash
# Q-Sign / IdO: PostgreSQL 접속 확인
kubectl exec deployment/idem-hub -n onepass -- \
  curl -s http://localhost:8083/actuator/health | jq '.components.db'

# Q-IM: PostgreSQL qim 스키마 접속 확인
kubectl exec deployment/idem-registry -n onepass -- \
  curl -s http://localhost:8082/actuator/health | jq '.components.db'
```

**시각 저장은 timestamptz(UTC)** — JVM/DB 타임존과 무관
```
jdbc:postgresql://host:5432/onepass?currentSchema=qim
```

### 13.3 Kafka 연결 실패

```bash
# Kafka consumer group 상태 확인 (Kafka 컨테이너 내부)
kafka-consumer-groups --bootstrap-server kafka:29092 \
  --describe --all-groups 2>/dev/null | head -50

# topic 존재 확인
kafka-topics --bootstrap-server kafka:29092 --list | sort
```

### 13.4 IdO → Q-IM 인증 실패 (401/403)

```bash
# 증상: ido 로그에 "X-Internal-Api-Key authentication failed"
# 원인: QIM_INTERNAL_API_KEY ≠ IDO_QIM_INTERNAL_API_KEY

# 확인
kubectl get secret idem-registry-secret -n onepass -o jsonpath='{.data.QIM_INTERNAL_API_KEY}' | base64 -d
kubectl get secret idem-hub-secret -n onepass -o jsonpath='{.data.IDO_QIM_INTERNAL_API_KEY}' | base64 -d
# 두 값이 동일해야 함
```

### 13.5 Handoff 발급 실패

```bash
# 1. IdO readiness 확인
curl http://localhost:8083/actuator/health/readiness

# 2. Kafka ido.handoff.events topic 확인
kafka-topics --bootstrap-server kafka:29092 \
  --describe --topic ido.handoff.events

# 3. DLT 확인 (재처리 필요한 이벤트)
kafka-console-consumer --bootstrap-server kafka:29092 \
  --topic ido.handoff.events.dlt \
  --from-beginning --max-messages 10
```

### 13.6 모니터링 접근 불가

```bash
# Prometheus 재시작
kubectl rollout restart deployment/idem-prometheus -n onepass

# Grafana 재시작
kubectl rollout restart deployment/idem-grafana -n onepass

# Promtail (로그 수집) 재시작
kubectl rollout restart daemonset/idem-promtail -n onepass
```

---

## 부록: 파일 구조

```
infra/
├── docker/
│   ├── docker-compose.yml        # 로컬/개발 Docker Compose
│   ├── .env.example              # 환경변수 예시 (Git 추적)
│   ├── .env                      # 실제 환경변수 (Git 미추적)
│   ├── kafka/
│   │   └── create-topics.sh      # Kafka topic 초기화
│   ├── keycloak/
│   │   └── realm-export.json     # Keycloak realm 설정
│   ├── nginx/
│   │   └── nginx.conf            # Nginx 설정 (optionB용)
│   ├── postgres/                 # PostgreSQL 설정
│   ├── redis/                    # Redis 설정
├── helm/
│   └── onepass/
│       ├── Chart.yaml
│       ├── values.yaml           # 기본값
│       ├── values-dev.yaml       # 개발 환경 오버라이드
│       ├── values-stage.yaml     # 스테이징 오버라이드
│       ├── values-prod.yaml      # 운영 오버라이드
│       └── templates/
│           ├── _helpers.tpl
│           ├── configmap.yaml    # 비민감 설정 (ConfigMap)
│           ├── secrets.yaml      # 민감 설정 구조 (Secret)
│           ├── deployment-*.yaml # 서비스별 Deployment
│           ├── services.yaml     # K8s Service
│           └── networkpolicy.yaml
├── k8s/
│   ├── base/                     # Kustomize base
│   └── overlays/                 # 환경별 overlay
│       ├── dev/
│       ├── stage/
│       └── prod/
├── minikube/
│   └── setup-minikube.sh         # Minikube 셋업 스크립트
├── monitoring/
│   ├── prometheus/
│   │   ├── prometheus.yml        # Prometheus 설정
│   │   └── alert_rules.yml       # 알림 규칙
│   ├── grafana/                  # Grafana provisioning
│   ├── dashboards/               # Grafana 대시보드 JSON
│   ├── loki/                     # Loki 설정
│   └── promtail/                 # Promtail 설정
└── scripts/
    ├── smoke-test.sh             # Smoke Test (Bash)
    └── smoke-test.ps1            # Smoke Test (PowerShell)
```
