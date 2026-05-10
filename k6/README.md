# OnePass Platform — k6 부하 테스트

> **S8-T2** | k6 JavaScript 기반 부하 테스트 스위트  
> 대상 서비스: `ido` (8083) — Handoff / Auth / Rate Limiter 엔드포인트

---

## 목차

1. [디렉토리 구조](#-디렉토리-구조)
2. [사전 요구사항](#-사전-요구사항)
3. [환경 변수](#-환경-변수)
4. [스크립트별 실행 방법](#-스크립트별-실행-방법)
5. [SLO 기준](#-slo-기준service-level-objective)
6. [커스텀 메트릭 목록](#-커스텀-메트릭-목록)
7. [CI/CD 연동](#-cicd-연동)
8. [결과 출력 및 리포트](#-결과-출력-및-리포트)
9. [문제 해결 FAQ](#-문제-해결-faq)

---

## 📁 디렉토리 구조

```
k6/
├── lib/
│   └── helpers.js          # 공용 상수·커스텀 메트릭·헬퍼 함수
├── scripts/
│   ├── 01-handoff.js       # Handoff 발급/검증 부하 테스트
│   ├── 02-auth.js          # Auth 6개 엔드포인트 검증
│   ├── 03-rate-limit.js    # AgencyRateLimiter 집중 검증
│   └── 04-soak.js          # 30분 장시간 안정성 테스트
├── scenarios/
│   ├── smoke.js            # CI/CD 배포 직후 스모크 테스트
│   └── stress.js           # 시스템 한계 탐색 스트레스 테스트
└── README.md               # 이 파일
```

---

## ⚙️ 사전 요구사항

### k6 설치

```bash
# macOS (Homebrew)
brew install k6

# Ubuntu / Debian
sudo gpg -k
sudo gpg --no-default-keyring \
  --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 \
  --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Windows (Chocolatey)
choco install k6

# Docker
docker run --rm -i grafana/k6 run - < k6/scenarios/smoke.js
```

### 대상 서버 실행

```bash
# ido 서비스 (포트 8083) 실행 필요
./gradlew :ido:bootRun

# 또는 Docker Compose (권장 — Redis 포함)
docker compose -f infra/docker/docker-compose.yml up -d postgres redis
./gradlew :ido:bootRun
```

---

## 🌐 환경 변수

| 환경 변수 | 기본값 | 설명 |
|-----------|--------|------|
| `BASE_URL` | `http://localhost:8083` | 대상 ido 서버 기본 URL |
| `AGENCY_CODE` | `AGENCY001` | 기관 코드 (Rate Limiter 키 생성에 사용) |
| `INTERNAL_API_KEY` | `test-internal-key` | `X-Internal-Api-Key` 헤더값 (Handoff 발급용) |

```bash
# 환경 변수 지정 예시
k6 run k6/scripts/01-handoff.js \
  --env BASE_URL=http://10.0.0.100:8083 \
  --env AGENCY_CODE=AGENCY999 \
  --env INTERNAL_API_KEY=secret-key-here
```

---

## 🚀 스크립트별 실행 방법

### `scenarios/smoke.js` — 스모크 테스트 *(CI/CD 자동 실행)*

배포 직후 핵심 엔드포인트 정상 동작 확인용. **1 VU × 1 iteration** (약 5~10초).

```bash
k6 run k6/scenarios/smoke.js
k6 run k6/scenarios/smoke.js --env BASE_URL=http://localhost:8083
```

**검증 항목:**
- `GET  /actuator/health` → HTTP 200
- `POST /api/v1/auth/nice/ci-check` (빈 CI) → 4000 (입력 검증 정상)
- `POST /api/v1/auth/oacx/easysign` (빈 fn) → 4000 (입력 검증 정상)
- `POST /api/v1/handoff/issue` → 서버 응답 여부

---

### `scripts/01-handoff.js` — Handoff 부하 테스트

Handoff Ticket 발급/검증 + Idempotency 검증 + Rate Limit 확인.

```bash
k6 run k6/scripts/01-handoff.js
```

**부하 단계 (총 약 5분 30초):**

| 단계 | 시간 | VU 수 | 목적 |
|------|------|-------|------|
| Ramp-up | 30s | 0 → 10 | 점진 증가 |
| Warm-up | 60s | 10 | 안정 구간 |
| Scale-up | 30s | 10 → 50 | 부하 증가 |
| Peak | 120s | 50 | 피크 부하 |
| Spike | 15s | 50 → 200 | Rate Limit 발동 확인 |
| Recovery | 30s | 200 → 10 | 복구 확인 |
| Drain | 30s | 10 → 0 | 종료 |

---

### `scripts/02-auth.js` — Auth 엔드포인트 검증

6개 인증 엔드포인트 + 입력 검증 오류 집중 검증.

```bash
k6 run k6/scripts/02-auth.js
```

**2개 시나리오 병렬 실행:**
- `auth_steady`: ramping-vus (0→20 VU, 160초)
- `ci_check_spike`: ramping-arrival-rate (10→80 RPS, Rate Limit spike 포함)

**외부 API 미연결 환경**: NICE/OACX 5xxx 응답은 정상으로 간주 (서버 입력 검증만 측정).

---

### `scripts/03-rate-limit.js` — Rate Limiter 집중 검증

`AgencyRateLimiter` (Redis Sliding Window, TPS 200 / 일 1,000,000건) 동작 검증.

```bash
k6 run k6/scripts/03-rate-limit.js
```

**3단계 검증:**

| Phase | RPS | 기대 결과 |
|-------|-----|-----------|
| Phase 1 (정상) | ~50 RPS | 모든 요청 200 응답 |
| Phase 2 (초과) | ~300 RPS | 429 응답 발생 (Rate Limit 동작 확인) |
| Phase 3 (복구) | ~50 RPS | 429 없이 200 복구 |

**SLO:**
- Phase 1: 429 발생 없음 (`rate_limit_normal_success` 100%)
- Phase 2: 429 발생 필수 (`rate_limit_overload_429` > 0)
- Phase 3: 200 복구 (`rate_limit_recovery_ok` > 95%)

---

### `scripts/04-soak.js` — Soak 안정성 테스트

30분 지속 부하로 메모리 누수·커넥션 풀 고갈 등 장시간 이슈 탐지.

```bash
# 실행 시간: 30분 (주의: 장시간 소요)
k6 run k6/scripts/04-soak.js
```

**설정:** 10 VU, 약 30 RPS, 30분  
**SLO:** p99 < 3000ms, HTTP 오류율 < 1%, Rate Limit 발생 없음 (count < 1)

---

### `scenarios/stress.js` — 스트레스 테스트

시스템 한계점(Breaking Point) 탐색.

```bash
k6 run k6/scenarios/stress.js
```

**부하 단계:** 50 → 100 → 200 → 300 → 400 → 0 VU (점진 증가 후 복구)

---

## 📊 SLO 기준 (Service Level Objective)

| 지표 | 기준값 | 적용 스크립트 |
|------|--------|--------------|
| Handoff Issue p95 응답시간 | < 2,000ms | 01-handoff, stress |
| Handoff Verify p95 응답시간 | < 1,500ms | 01-handoff |
| CI-Check p95 응답시간 | < 1,000ms | 02-auth |
| HTTP 오류율 (4xx/5xx, 429 제외) | < 1% | 01-handoff, 04-soak |
| 비즈니스 오류율 (`resultCode != 2000`) | < 5% | 01-handoff, 02-auth |
| Soak p99 응답시간 | < 3,000ms | 04-soak |
| Rate Limit 정상 구간 429 발생 | 0건 | 03-rate-limit, 04-soak |

---

## 📈 커스텀 메트릭 목록

| 메트릭 이름 | 타입 | 설명 |
|------------|------|------|
| `biz_error_rate` | Rate | 비즈니스 오류율 (HTTP 200이지만 resultCode ≠ 2000) |
| `handoff_issue_duration` | Trend | `POST /api/v1/handoff/issue` 응답시간 (히스토그램) |
| `handoff_verify_duration` | Trend | `POST /api/v1/handoff/verify` 응답시간 |
| `nice_phone_url_duration` | Trend | `GET /api/v1/auth/nice/phone/url` 응답시간 |
| `nice_phone_result_duration` | Trend | `POST /api/v1/auth/nice/phone/result` 응답시간 |
| `nice_ci_check_duration` | Trend | `POST /api/v1/auth/nice/ci-check` 응답시간 |
| `oacx_access_info_duration` | Trend | `POST /api/v1/auth/oacx/access-info` 응답시간 |
| `oacx_easysign_duration` | Trend | `POST /api/v1/auth/oacx/easysign` 응답시간 |
| `rate_limit_429_total` | Counter | Rate Limit(429) 총 발생 건수 |

---

## 🔄 CI/CD 연동

GitHub Actions에서 배포 직후 `smoke.js`를 자동 실행합니다.

```yaml
# .github/workflows/ci.yml 의 smoke-test job 참조
# build-and-test job 성공 후 자동 실행
```

**smoke 테스트 실패 시**: 워크플로우가 실패로 종료되어 배포가 중단됩니다.

**로컬 CI 시뮬레이션:**
```bash
# 서버 실행 확인 후 스모크 테스트 실행
curl -sf http://localhost:8083/actuator/health && \
  k6 run k6/scenarios/smoke.js --env BASE_URL=http://localhost:8083
```

---

## 📋 결과 출력 및 리포트

### 터미널 출력 (기본)

k6 실행 완료 시 터미널에 메트릭 요약이 자동 출력됩니다.

### JSON 리포트 저장

```bash
k6 run k6/scripts/01-handoff.js --out json=reports/handoff-$(date +%Y%m%d-%H%M%S).json
```

### Grafana + InfluxDB 연동 (권장)

```bash
# infra/docker/docker-compose.monitoring.yml 실행 후
k6 run k6/scripts/01-handoff.js \
  --out influxdb=http://localhost:8086/k6
```

Grafana 대시보드: `http://localhost:3000` → "k6 Load Testing Results" 대시보드 임포트  
(대시보드 ID: [2587](https://grafana.com/grafana/dashboards/2587))

### HTML 리포트 (k6-reporter 플러그인)

```bash
# k6-reporter 설치 후
k6 run --out web-dashboard k6/scripts/01-handoff.js
# http://127.0.0.1:5665 에서 실시간 대시보드 확인
```

---

## ❓ 문제 해결 FAQ

### Q. `ECONNREFUSED` 오류가 발생합니다

`ido` 서버(8083 포트)가 실행 중인지 확인하세요:

```bash
curl http://localhost:8083/actuator/health
# 응답: {"status":"UP"} 이어야 합니다
```

### Q. `429 Too Many Requests` 응답이 예상보다 빨리 발생합니다

`AgencyRateLimiter` TPS 200 제한이 적용된 정상 동작입니다.  
`AGENCY_CODE`를 여러 개 사용하거나 Redis TTL을 확인하세요:

```bash
redis-cli keys "ido:rl:tps:*"
redis-cli ttl "ido:rl:tps:AGENCY001:<epochSecond>"
```

### Q. `handoff/issue` 에서 CI 필드 오류가 발생합니다

테스트용 `fakeCi()` 함수는 88자 더미 CI를 생성하지만, 실제 암호화/서명은 포함되지 않습니다.  
NICE 검증 없이 입력 처리 로직만 검증할 경우 `mbrDvsnCd` + 빈 CI 조합을 사용하세요.

### Q. 스모크 테스트가 CI에서 실패합니다

GitHub Actions의 `smoke-test` job은 `build-and-test` 완료 후 실행됩니다.  
서버가 시작되기까지 최대 60초 대기합니다. 헬스체크 실패 시 `actuator/health` 응답을 확인하세요.

---

> 작성일: 2026-05-10 | S8-T2 — OnePass Platform 부하 테스트 스위트
