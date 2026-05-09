# OnePass Platform — k6 부하 테스트

> **목적**: Rate Limiter 실효성 검증 및 SLA 임계값 달성 확인  
> **설계 참조**: 설계서 §14 Rate Limiter 정책, §10 SLA 요구사항

## 시나리오 구성

| 시나리오 | VU | 시간 | 목적 |
|---------|-----|------|------|
| `smoke` | 1 | 30s | 기본 동작 검증 (CI 게이트) |
| `ramp_up` | 0→50 | 6m | 점진 증가 시 응답시간 확인 |
| `peak` | 50 | 3m | Rate Limiter 429 응답 검증 |
| `spike` | 0→100→0 | 2m | 급증 내성 확인 |
| `slo` | 20 | 5m | p95 < 500ms, 오류율 < 1% |

## SLA 임계값 (Thresholds)

| 메트릭 | 조건 |
|--------|------|
| `http_req_duration{scenario:slo}` p(95) | < 500ms |
| `http_req_duration{scenario:slo}` p(99) | < 1000ms |
| `http_req_failed{scenario:slo}` | < 1% |
| `slo_latency_ms` p(95) | < 500ms |
| `session_check_latency_ms` p(95) | < 300ms |

## 실행 방법

### 사전 요구사항
```bash
# k6 설치 (macOS)
brew install k6

# k6 설치 (Ubuntu/Debian)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] \
  https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Docker 사용
docker pull grafana/k6
```

### 전체 시나리오 실행
```bash
# 로컬 서버 대상
k6 run infra/k6/load-test.js

# Docker로 실행 (호스트 네트워크 사용)
docker run --rm -i --network host grafana/k6 run - < infra/k6/load-test.js
```

### 특정 시나리오만 실행
```bash
# Smoke (CI 빠른 검증)
k6 run --env SCENARIO=smoke infra/k6/load-test.js

# SLO 검증만
k6 run --env SCENARIO=slo infra/k6/load-test.js

# Peak (Rate Limiter 집중 검증)
k6 run --env SCENARIO=peak infra/k6/load-test.js
```

### 대상 서버 지정
```bash
k6 run \
  --env IDO_BASE_URL=https://ido.example.com \
  --env QSIGN_BASE_URL=https://qsign.example.com \
  infra/k6/load-test.js
```

### 결과 출력 옵션
```bash
# JSON 리포트 저장
k6 run --out json=results/k6-result.json infra/k6/load-test.js

# InfluxDB + Grafana 연동 (실시간 대시보드)
k6 run --out influxdb=http://localhost:8086/k6 infra/k6/load-test.js

# CSV 저장
k6 run --out csv=results/k6-result.csv infra/k6/load-test.js
```

## CI 연동 (GitHub Actions)

`.github/workflows/ci.yml`의 `build-and-test` Job에 선택적 통합:

```yaml
- name: k6 Smoke Test (서버 기동 시)
  if: env.DOCKER_UNAVAILABLE != 'true'
  run: |
    docker run --rm --network host grafana/k6 run \
      --env SCENARIO=smoke \
      --env IDO_BASE_URL=http://localhost:8083 \
      - < infra/k6/load-test.js
```

## 커스텀 메트릭

| 메트릭 | 타입 | 설명 |
|--------|------|------|
| `rate_limited_requests` | Counter | 429 응답 누적 횟수 |
| `auth_failure_rate` | Rate | 인증 실패 비율 |
| `slo_latency_ms` | Trend | SLO /initiate 처리시간 (p50/p95/p99) |
| `session_check_latency_ms` | Trend | 세션 확인 처리시간 |

## 테스트 대상 엔드포인트

| 서비스 | 엔드포인트 | 설명 |
|--------|-----------|------|
| IdO (8083) | `GET /actuator/health` | 헬스체크 |
| IdO (8083) | `GET /api/v1/fe-session/check` | FE 세션 유효성 확인 |
| IdO (8083) | `POST /api/v1/slo/initiate` | SLO 시작 |
| Q-Sign (8081) | `GET /actuator/health` | 헬스체크 |
