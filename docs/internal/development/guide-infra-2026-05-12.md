# 인프라 / DevOps 팀 가이드

> **버전**: v3.0.0 (PR #77 반영 — 2026-05-12 GAP 패치 완료 기준)  
> **최종 수정**: 2026-05-12  
> **대상**: 인프라/DevOps 팀  
> **기술 스택**: Docker Compose / Kubernetes / Nginx / PostgreSQL / Redis / Kafka / Keycloak

---

## 목차

1. [v3.0 인프라 변경 사항](#1-v30-인프라-변경-사항)
2. [전체 서비스 구성도](#2-전체-서비스-구성도)
3. [로컬 개발 환경 기동 (Docker Compose)](#3-로컬-개발-환경-기동)
4. [환경변수 파일 관리 (`.env` 필수 항목)](#4-환경변수-파일-관리)
5. [Nginx 설정 — API 프록시 라우팅 (v3.0)](#5-nginx-설정)
6. [빌드 및 Docker 이미지 생성](#6-빌드-및-docker-이미지-생성)
7. [FE_AES_GCM_KEY 운영 키 관리 (B-1 신규)](#7-fe_aes_gcm_key-운영-키-관리)
8. [Keycloak 시크릿 관리 (B-6)](#8-keycloak-시크릿-관리)
9. [Kubernetes 배포 체크리스트](#9-kubernetes-배포-체크리스트)
10. [헬스체크 및 모니터링](#10-헬스체크-및-모니터링)
11. [배포 전 보안 점검 목록](#11-배포-전-보안-점검-목록)

---

## 1. v3.0 인프라 변경 사항

### 1.1 핵심 변경 요약

| 항목 | v2.x | v3.0 |
|------|------|------|
| **Nginx `/api/ext` 라우팅** | FE → Q-IM 직접 (별도 upstream) | **FE → ido → Q-IM** (ido가 forward proxy) |
| **`FE_AES_GCM_KEY`** | FE 빌드 시 번들 포함 (`AES_GCM_KEY`) | **ido 환경변수** (`FE_AES_GCM_KEY`) — 신규 설정 필요 |
| **`KEYCLOAK_CLIENT_SECRET`** | `change-me` 하드코딩 | `${KEYCLOAK_IDO_CLIENT_SECRET:-}` 환경변수화 |
| **`EXT_API_KEY`** | FE 빌드 환경변수 (`EXT_API_KEY`) | **ido 환경변수** (`IDO_QIM_EXT_API_KEY`) |
| **`AES_GCM_KEY`** | FE 빌드 환경변수 | **삭제** (ido `FE_AES_GCM_KEY`로 대체) |
| **`SKIP_AUTH`** | FE 빌드 환경변수 | **삭제** (코드에서 완전 제거) |

### 1.2 배포 전 즉시 조치 사항

```
1. ido 서버에 FE_AES_GCM_KEY 환경변수 추가 (반드시 운영 전 설정)
2. ido 서버에 IDO_QIM_EXT_API_KEY 환경변수 추가 (Q-IM 팀에서 발급)
3. KEYCLOAK_IDO_CLIENT_SECRET 환경변수 설정 (Keycloak 콘솔에서 발급)
4. FE 빌드 환경변수에서 AES_GCM_KEY, EXT_API_KEY, SKIP_AUTH 제거
5. Nginx /api/ext 라우팅: Q-IM upstream → ido upstream으로 변경
```

---

## 2. 전체 서비스 구성도

### 2.1 Docker Compose 서비스 목록

| 서비스 | 포트 | 역할 | 프로파일 |
|--------|------|------|---------|
| `postgres` | `5432` | ido / q-sign DB | default |
| `mariadb` | `3306` | Q-IM DB | default |
| `redis` | `6379` | 세션 / 캐시 | default |
| `kafka` | `9092` | 이벤트 스트리밍 | default |
| `zookeeper` | `2181` | Kafka 코디네이터 | default |
| `keycloak` | `8080` | IdP / OIDC | default |
| `onepass-ido` | `8083` | BE BFF + 오케스트레이터 | `app` |
| `onepass-qsign` | `8081` | 인증 SoR | `app` |
| `onepass-fe` | `3001` | React SPA (Nginx) | `app` |
| `agency-stub` | `8084` | 기관 시뮬레이터 | `app` |
| `kafka-ui` | `8090` | Kafka 관리 UI | 선택 |
| `pgadmin` | `5050` | DB 관리 UI | 선택 |

### 2.2 네트워크 구성

```
onepass-net (172.20.0.0/24)
├── postgres      172.20.0.10
├── mariadb       172.20.0.11
├── redis         172.20.0.12
├── kafka         172.20.0.13
├── zookeeper     172.20.0.14
├── keycloak      172.20.0.15
├── onepass-qsign 172.20.0.17
├── onepass-ido   172.20.0.19
├── onepass-fe    172.20.0.20
└── agency-stub   172.20.0.21
```

---

## 3. 로컬 개발 환경 기동

### 3.1 인프라 전체 기동 (권장 순서)

```bash
cd infra/docker

# 1단계: .env 파일 생성 (섹션 4 참고)
cp .env.example .env
# .env 편집 — 필수 시크릿 값 입력

# 2단계: 인프라 서비스만 기동 (앱 제외)
docker compose up -d postgres mariadb redis kafka zookeeper keycloak

# 3단계: 헬스체크 대기
docker compose ps
# STATUS: healthy 확인 후 진행

# 4단계: 앱 서비스 기동
docker compose --profile app up -d
```

### 3.2 개별 서비스 재시작

```bash
# ido만 재시작 (설정 변경 후)
docker compose restart onepass-ido

# 로그 확인
docker compose logs -f onepass-ido
docker compose logs -f onepass-qsign
docker compose logs -f onepass-fe
```

### 3.3 Gradle 직접 실행 (인프라만 Docker 사용)

```bash
# 인프라만 기동
cd infra/docker && docker compose up -d postgres redis kafka keycloak

# ido 로컬 실행
cd <project-root>
FE_AES_GCM_KEY="$(openssl rand -base64 32)" \
IDO_QIM_EXT_API_KEY="test-ext-api-key" \
IDO_QIM_INTERNAL_API_KEY="ido-internal" \
./gradlew :ido:bootRun --args='--spring.profiles.active=local'
```

### 3.4 헬스체크 스크립트

```bash
#!/bin/bash
# infra/scripts/healthcheck-local.sh

check_service() {
    local name=$1
    local url=$2
    if curl -sf "$url" > /dev/null 2>&1; then
        echo "✅ $name: OK"
    else
        echo "❌ $name: FAILED"
    fi
}

check_service "ido"      "http://localhost:8083/actuator/health"
check_service "q-sign"   "http://localhost:8081/actuator/health"
check_service "keycloak" "http://localhost:8080/health"
check_service "redis"    "http://localhost:6379"  # redis-cli ping
check_service "kafka"    "http://localhost:9092"
check_service "fe"       "http://localhost:3001"
```

---

## 4. 환경변수 파일 관리

### 4.1 `.env` 필수 항목 (v3.0)

```dotenv
# ============================================================
# infra/docker/.env — Docker Compose 환경변수
# v3.0 — 2026-05-12 GAP 패치 기준
# ============================================================

# ── ido 신규 환경변수 (v3.0 추가) ─────────────────────────
FE_AES_GCM_KEY=<openssl rand -base64 32 으로 생성>
KEYCLOAK_IDO_CLIENT_SECRET=<Keycloak 콘솔에서 발급>
IDO_QIM_EXT_API_KEY=<Q-IM 팀에서 발급>
IDO_QIM_INTERNAL_API_KEY=<Q-IM 팀에서 발급>

# ── ido 기존 환경변수 ──────────────────────────────────────
IDO_INTERNAL_SIG_SECRET=<32바이트 이상 랜덤 문자열>
IDO_HANDOFF_AES_KEY=<Handoff AES 키>
IDO_HANDOFF_HMAC_KEY=<Handoff HMAC 키>

# ── NICE / OACX ────────────────────────────────────────────
NICE_CLIENT_ID=<NICE 계약 후 발급>
NICE_CLIENT_SECRET=<NICE 계약 후 발급>

# ── Q-IM 공유키 ────────────────────────────────────────────
QIM_AES_SHARED_KEY=<Q-IM 팀과 협의 후 결정>
QIM_INBOUND_API_KEY_HASH=<Q-IM 관리 콘솔에서 생성>

# ── Q-Sign ─────────────────────────────────────────────────
IDO_INTERNAL_SIG_SECRET=<q-sign과 동일한 시크릿>

# ── FE 빌드 환경변수 (.env.prod) ───────────────────────────
# ⚠️ 아래 항목은 v3.0에서 삭제됨 — FE .env.prod에서 반드시 제거
# AES_GCM_KEY    → 삭제
# EXT_API_KEY    → 삭제
# EXT_API_ENDPOINT → 삭제
# SKIP_AUTH      → 삭제
```

### 4.2 FE 빌드 환경변수 v3.0 변경점

| 변수 | v2.x | v3.0 | 조치 |
|------|------|------|------|
| `AES_GCM_KEY` | FE 번들 포함 | **삭제** | `.env.prod`에서 제거 |
| `EXT_API_KEY` | FE 번들 포함 | **삭제** | `.env.prod`에서 제거 |
| `EXT_API_ENDPOINT` | FE Q-IM 직접 URL | **삭제** | `.env.prod`에서 제거 |
| `SKIP_AUTH` | `false` 설정 필요 | **삭제** | `.env.prod`에서 제거 |
| `BE_API_ENDPOINT` | ido URL | 유지 | 값 확인 |
| `QSIGN_REALM` | (신규) | `ucube-qsign` | 추가 필요 |
| `QSIGN_CLIENT_ID` | (신규) | `onepassCli` | 추가 필요 |

---

## 5. Nginx 설정

### 5.1 v3.0 라우팅 변경 (핵심)

```nginx
# /etc/nginx/conf.d/onepass.conf (v3.0)

upstream ido_backend {
    server onepass-ido:8083;
}

server {
    listen 80;
    server_name onepass.example.com;

    # ── React SPA ──────────────────────────────────────────
    root /usr/share/nginx/html;
    index index.html;

    # ── /api/ext/** → ido (B-5: ido가 Q-IM forward proxy) ─
    # v2.x: /api/ext → Q-IM 직접
    # v3.0: /api/ext → ido → Q-IM (ido가 X-Ext-Api-Key 주입)
    location /api/ext/ {
        proxy_pass http://ido_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-BE-API-Key $BE_API_KEY;
    }

    # ── /api/** → ido ──────────────────────────────────────
    location /api/ {
        proxy_pass http://ido_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-BE-API-Key $BE_API_KEY;
    }

    # ── SPA fallback ──────────────────────────────────────
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

> **v2.x 제거 항목**: `upstream qim_backend` 및 `/api/ext → Q-IM` 직접 라우팅 제거.  
> v3.0에서는 `/api/ext`도 **ido upstream** 하나로 통합됩니다.

### 5.2 보안 헤더 설정 (권장)

```nginx
# CSP 헤더 — FE 번들에 민감 정보 미포함이므로 강화 가능
add_header Content-Security-Policy
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';"
    always;

add_header X-Frame-Options "DENY" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

---

## 6. 빌드 및 Docker 이미지 생성

### 6.1 백엔드 (ido) 빌드

```bash
# 컴파일 + 테스트 검증
./gradlew :ido:compileJava :q-sign:compileJava --no-daemon
./gradlew :ido:test :q-sign:test --no-daemon

# JAR 빌드
./gradlew :ido:bootJar -x test

# Docker 이미지 빌드
docker build -f ido/Dockerfile -t onepass-ido:latest .
docker build -f q-sign/Dockerfile -t onepass-qsign:latest .
```

### 6.2 프론트엔드 (FE) 빌드

```bash
cd onepass-fe/frontend

# ⚠️ v3.0: AES_GCM_KEY, EXT_API_KEY, SKIP_AUTH 없는 .env.prod 사용
APP_ENV=prod npm run build

# Docker 이미지 빌드 (Nginx 포함)
cd ..
docker build -f Dockerfile.optionB -t onepass-react:latest .
```

### 6.3 빌드 결과 검증

```bash
# ido 이미지 시작 테스트
docker run --rm \
  -e FE_AES_GCM_KEY="$(openssl rand -base64 32)" \
  -e SPRING_PROFILES_ACTIVE=docker \
  onepass-ido:latest \
  java -jar app.jar --spring.profiles.active=docker &

sleep 15
curl -f http://localhost:8083/actuator/health && echo "✅ ido 기동 확인"

# FE 번들 보안 확인 — AES 키 미포함 검증
unzip -p onepass-fe/frontend/build/static/js/main.*.js | strings | grep -c "AES_GCM_KEY"
# → 0이어야 함 (키 미포함 확인)
```

---

## 7. FE_AES_GCM_KEY 운영 키 관리 (B-1 신규)

### 7.1 키 생성

```bash
# AES-256 키 생성 (32바이트, Base64 인코딩)
openssl rand -base64 32
# 예시 출력: dGVzdEtleUhlcmUzMmJ5dGVzUGFkZGluZy0t

# 생성된 키를 안전한 채널로 아래 담당자에게 공유:
# 1. ido 서버 팀 → FE_AES_GCM_KEY 환경변수로 설정
# 2. Q-IM 팀 → QIM_AES_SHARED_KEY 환경변수로 설정 (ido-Q-IM 간 공유키)
```

### 7.2 K8s Secret 설정 (운영 권장)

```bash
# ido Secret 생성
kubectl create secret generic ido-secrets \
  --from-literal=FE_AES_GCM_KEY="$(openssl rand -base64 32)" \
  --from-literal=KEYCLOAK_IDO_CLIENT_SECRET="<Keycloak에서 발급>" \
  --from-literal=IDO_QIM_EXT_API_KEY="<Q-IM 팀에서 발급>" \
  --from-literal=IDO_QIM_INTERNAL_API_KEY="<Q-IM 팀에서 발급>" \
  --from-literal=IDO_INTERNAL_SIG_SECRET="<랜덤 32바이트>" \
  -n onepass
```

```yaml
# k8s/ido-deployment.yaml (발췌)
env:
  - name: FE_AES_GCM_KEY
    valueFrom:
      secretKeyRef:
        name: ido-secrets
        key: FE_AES_GCM_KEY
  - name: KEYCLOAK_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: ido-secrets
        key: KEYCLOAK_IDO_CLIENT_SECRET
  - name: IDO_QIM_EXT_API_KEY
    valueFrom:
      secretKeyRef:
        name: ido-secrets
        key: IDO_QIM_EXT_API_KEY
```

### 7.3 키 교체 절차

```
1. 새 키 생성: openssl rand -base64 32
2. Q-IM 팀에 새 QIM_AES_SHARED_KEY 공유 (Q-IM도 동시 교체)
3. ido K8s Secret 업데이트: kubectl edit secret ido-secrets
4. ido Pod 롤링 재시작: kubectl rollout restart deployment/onepass-ido
5. Q-IM 서버 재시작 (Q-IM 팀 협조)
6. GET /api/v1/auth/provision/aes-gcm-key 정상 응답 확인
⚠️ 키 교체 중 Step3 본인인증 오류 발생 가능 — 유지보수 윈도우 중 진행 권장
```

---

## 8. Keycloak 시크릿 관리 (B-6)

### 8.1 변경 내용

```yaml
# docker-compose.yml (v3.0)
KEYCLOAK_CLIENT_SECRET: ${KEYCLOAK_IDO_CLIENT_SECRET:-}  # 환경변수화 완료
# 이전: KEYCLOAK_CLIENT_SECRET: change-me  ← 하드코딩 제거됨
```

### 8.2 Keycloak 클라이언트 시크릿 발급

```
1. Keycloak 관리 콘솔 접속 (http://keycloak:8080/admin)
2. Realm: onepass 선택
3. Clients → ido-client → Credentials 탭
4. 'Regenerate Secret' 클릭 → 시크릿 복사
5. KEYCLOAK_IDO_CLIENT_SECRET 환경변수로 설정
```

### 8.3 로컬 개발 설정

```bash
# infra/docker/.env
KEYCLOAK_IDO_CLIENT_SECRET=local-dev-ido-secret

# Keycloak 콘솔에서 ido-client Secret을 동일 값으로 설정
```

---

## 9. Kubernetes 배포 체크리스트

### 9.1 배포 전 확인

```
[ ] ido-secrets Secret에 모든 필수 키 포함 여부 확인
    → FE_AES_GCM_KEY, KEYCLOAK_IDO_CLIENT_SECRET
    → IDO_QIM_EXT_API_KEY, IDO_QIM_INTERNAL_API_KEY
    → IDO_INTERNAL_SIG_SECRET, IDO_HANDOFF_AES_KEY, IDO_HANDOFF_HMAC_KEY
    → NICE_CLIENT_ID, NICE_CLIENT_SECRET
    → QIM_AES_SHARED_KEY, QIM_INBOUND_API_KEY_HASH

[ ] Nginx 설정 업데이트
    → /api/ext upstream: Q-IM → ido 변경 (v3.0 핵심)
    → 보안 헤더 설정 확인

[ ] FE 빌드 환경변수 점검
    → AES_GCM_KEY 없음, EXT_API_KEY 없음, SKIP_AUTH 없음
    → QSIGN_REALM, QSIGN_CLIENT_ID 추가 여부 확인

[ ] ido Docker 이미지 버전 확인 (PR #77 포함 여부)
    → GET /api/v1/auth/provision/aes-gcm-key 엔드포인트 존재 확인
    → tempPassword 키 이름 확인 (password 아님)
```

### 9.2 롤링 배포 순서

```
1. Q-IM 서버 업데이트 (QIM_AES_SHARED_KEY 적용)
2. ido 서버 업데이트 (FE_AES_GCM_KEY, IDO_QIM_EXT_API_KEY 포함)
3. FE 빌드 및 Nginx 업데이트 (AES_GCM_KEY 제거된 빌드)
4. smoke test 수행
```

### 9.3 롤백 절차

```bash
# ido 이전 버전으로 롤백
kubectl rollout undo deployment/onepass-ido -n onepass

# FE 이전 버전으로 롤백
kubectl rollout undo deployment/onepass-fe -n onepass

# 상태 확인
kubectl rollout status deployment/onepass-ido -n onepass
```

---

## 10. 헬스체크 및 모니터링

### 10.1 헬스체크 엔드포인트

| 서비스 | 엔드포인트 | 예상 응답 |
|--------|-----------|---------|
| ido | `GET /actuator/health` | `{"status":"UP"}` |
| q-sign | `GET /actuator/health` | `{"status":"UP"}` |
| Keycloak | `GET /health` | `{"status":"UP"}` |

### 10.2 v3.0 신규 엔드포인트 모니터링

```bash
# FE_AES_GCM_KEY 설정 여부 확인 (200 응답 필수)
curl -H "X-BE-API-Key: $BE_API_KEY" \
     http://ido:8083/api/v1/auth/provision/aes-gcm-key
# → {"aesGcmKey":"..."} 이어야 함
# → 500이면 FE_AES_GCM_KEY 미설정

# temp-password 키 이름 확인
curl -H "X-BE-API-Key: $BE_API_KEY" \
     http://ido:8083/api/v1/auth/provision/temp-password
# → {"tempPassword":"..."} 이어야 함 (이전: "password")
```

### 10.3 알람 설정 권장

```
- GET /api/v1/auth/provision/aes-gcm-key → 500: CRITICAL (FE 본인인증 전체 불능)
- /api/ext/** → 502/503: HIGH (Q-IM forward proxy 장애)
- /api/v1/auth/nice/** → 5001: MEDIUM (NICE API 장애)
```

---

## 11. 배포 전 보안 점검 목록

```
[ ] FE 빌드 번들에 AES_GCM_KEY 미포함 확인
    → strings main.*.js | grep -i "aes.*key" → 0건

[ ] FE 빌드 번들에 EXT_API_KEY 미포함 확인
    → strings main.*.js | grep -i "ext.*key\|imk-" → 0건

[ ] SKIP_AUTH 코드 잔존 없음 확인
    → grep -r "SKIP_AUTH" onepass-fe/frontend/src/ → 0건

[ ] KEYCLOAK_CLIENT_SECRET 하드코딩 없음 확인
    → grep "change-me" infra/docker/docker-compose.yml → 0건

[ ] docker-compose.yml 시크릿 환경변수 확인
    → 모든 시크릿이 ${VAR_NAME} 형식으로 외부 주입

[ ] Nginx /api/ext → ido 라우팅 확인 (Q-IM 직접 아님)

[ ] OACX_DEBUG_MODE=false (운영 환경)

[ ] CSP 헤더 설정 여부 확인
```

---

*최종 수정: 2026-05-12 / PR #77 반영*  
*다음 업데이트 예정: K8s 매니페스트 완성 후*
