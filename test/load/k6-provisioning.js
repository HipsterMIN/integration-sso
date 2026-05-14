/**
 * k6 부하 테스트 — F-20 프로비저닝 Virtual Thread 병렬 처리 (Sprint 17)
 *
 * 목적:
 *   68개 기관 동시 Virtual Thread HTTP 발행 시 처리량/지연 측정
 *
 * 실행 방법:
 *   # Staging 환경 (기본)
 *   k6 run test/load/k6-provisioning.js
 *
 *   # 운영 환경 (대규모)
 *   k6 run --vus 200 --duration 5m test/load/k6-provisioning.js \
 *     -e BASE_URL=https://ido.production.smes.go.kr
 *
 *   # 결과 InfluxDB 전송
 *   k6 run --out influxdb=http://influx:8086/k6 test/load/k6-provisioning.js
 *
 * SLO 목표 (Sprint 17 합격 기준):
 *   - p95 응답시간 < 2,000ms  (프로비저닝 트리거 API)
 *   - p99 응답시간 < 5,000ms
 *   - 에러율 < 0.1%
 *   - 처리량 > 50 req/s (68개 기관 × Virtual Thread)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── 커스텀 메트릭 ────────────────────────────────────────────────────────────
const provisioningErrors   = new Counter('provisioning_errors');
const provisioningSuccess  = new Counter('provisioning_success');
const provisioningDuration = new Trend('provisioning_duration_ms', true);
const errorRate            = new Rate('error_rate');

// ── 환경 설정 ────────────────────────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL   || 'http://localhost:8083';
const AGENCY_KEY = __ENV.AGENCY_KEY || 'test-agency-key-for-load-test';

// ── 부하 프로파일 (Stages) ────────────────────────────────────────────────────
export const options = {
  stages: [
    { duration: '30s', target: 10  },  // 워밍업: 10 VU
    { duration: '1m',  target: 50  },  // 증가: 50 VU
    { duration: '2m',  target: 100 },  // 피크: 100 VU (68개 기관 × 약 1.5배)
    { duration: '1m',  target: 50  },  // 감소
    { duration: '30s', target: 0   },  // 쿨다운
  ],
  thresholds: {
    // ── SLO 목표 ──────────────────────────────────────────────────────────
    'http_req_duration':           ['p(95)<2000', 'p(99)<5000'],
    'http_req_failed':             ['rate<0.001'],   // 에러율 0.1% 미만
    'provisioning_duration_ms':    ['p(95)<2000'],
    'error_rate':                  ['rate<0.001'],
  },
};

// ── 테스트 기관 코드 목록 (68개 시뮬레이션) ────────────────────────────────
const AGENCY_CODES = Array.from({ length: 68 }, (_, i) =>
  `AGENCY_${String(i + 1).padStart(3, '0')}`
);

// ── 이벤트 타입 목록 ──────────────────────────────────────────────────────────
const EVENT_TYPES = ['USER_REGISTERED', 'USER_UPDATED', 'USER_WITHDRAWN', 'BIZ_CONVERTED'];

// ── 메인 테스트 함수 ──────────────────────────────────────────────────────────
export default function () {
  const agencyCode    = AGENCY_CODES[Math.floor(Math.random() * AGENCY_CODES.length)];
  const idempotencyKey = generateUuidV4();
  const correlationId  = generateUuidV4();
  const eventType      = EVENT_TYPES[Math.floor(Math.random() * EVENT_TYPES.length)];

  // ── 1. 프로비저닝 트리거 (POST /api/v1/agency/gateway/inbound/event) ──────
  const inboundPayload = JSON.stringify({
    event_type:     eventType,
    agency_code:    agencyCode,
    qim_user_id:    `user_${Date.now()}`,
    timestamp:      new Date().toISOString(),
    metadata: {
      source: 'k6-load-test',
      version: '1.0',
    }
  });

  const inboundHeaders = {
    'Content-Type':      'application/json',
    'X-Agency-Code':     agencyCode,
    'X-Agency-Key':      AGENCY_KEY,
    'X-Idempotency-Key': idempotencyKey,
    'X-Correlation-ID':  correlationId,
    'X-Event-Type':      eventType,
  };

  const startTs  = Date.now();
  const response = http.post(
    `${BASE_URL}/api/v1/agency/gateway/inbound/event`,
    inboundPayload,
    { headers: inboundHeaders, timeout: '10s' }
  );
  const durationMs = Date.now() - startTs;

  provisioningDuration.add(durationMs);

  const ok = check(response, {
    'inbound: status 202 or 503(feature_disabled)': (r) =>
      r.status === 202 || r.status === 503,
    'inbound: response time < 2s': (r) =>
      r.timings.duration < 2000,
    'inbound: no 5xx server error': (r) =>
      r.status < 500,
  });

  if (!ok || response.status >= 500) {
    provisioningErrors.add(1);
    errorRate.add(1);
    console.error(`[k6] 인바운드 오류: status=${response.status} agencyCode=${agencyCode} ` +
                  `body=${response.body ? response.body.substring(0, 200) : 'empty'}`);
  } else {
    provisioningSuccess.add(1);
    errorRate.add(0);
  }

  // ── 2. 기관 상태 조회 (GET /api/v1/agency/gateway/status/{agencyCode}) ─────
  // 10% 확률로 상태 조회 (부하 분산)
  if (Math.random() < 0.1) {
    const statusResp = http.get(
      `${BASE_URL}/api/v1/agency/gateway/status/${agencyCode}`,
      {
        headers: {
          'X-Agency-Code': agencyCode,
          'X-Agency-Key':  AGENCY_KEY,
        },
        timeout: '5s',
      }
    );
    check(statusResp, {
      'status: response 200': (r) => r.status === 200,
      'status: response time < 1s': (r) => r.timings.duration < 1000,
    });
  }

  // ── 3. Actuator Feature Flags 조회 (1% 확률 — 운영 모니터링 시뮬레이션) ──
  if (Math.random() < 0.01) {
    const featuresResp = http.get(`${BASE_URL}/actuator/features`, { timeout: '3s' });
    check(featuresResp, {
      'actuator/features: 200': (r) => r.status === 200,
    });
  }

  sleep(0.1); // 100ms 간격
}

// ── 테스트 완료 후 요약 출력 ─────────────────────────────────────────────────
export function handleSummary(data) {
  const summary = {
    '테스트 결과': {
      'p95 응답시간 (ms)':   data.metrics.http_req_duration?.values?.['p(95)']?.toFixed(0),
      'p99 응답시간 (ms)':   data.metrics.http_req_duration?.values?.['p(99)']?.toFixed(0),
      '에러율':              `${(data.metrics.http_req_failed?.values?.rate * 100).toFixed(3)}%`,
      '총 요청수':           data.metrics.http_reqs?.values?.count,
      '성공 처리량 (req/s)': data.metrics.http_reqs?.values?.rate?.toFixed(1),
    },
    'SLO 합격 여부': {
      'p95 < 2000ms': (data.metrics.http_req_duration?.values?.['p(95)'] < 2000) ? '✅ PASS' : '❌ FAIL',
      'p99 < 5000ms': (data.metrics.http_req_duration?.values?.['p(99)'] < 5000) ? '✅ PASS' : '❌ FAIL',
      '에러율 < 0.1%': (data.metrics.http_req_failed?.values?.rate < 0.001) ? '✅ PASS' : '❌ FAIL',
    }
  };
  console.log('\n' + JSON.stringify(summary, null, 2));
  return { 'test/load/results/k6-provisioning-summary.json': JSON.stringify(data, null, 2) };
}

// ── 유틸리티 ─────────────────────────────────────────────────────────────────
function generateUuidV4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}
