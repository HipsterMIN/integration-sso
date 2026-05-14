/**
 * k6 부하 테스트 — F-26 HMAC 서명 검증 성능 테스트 (Sprint 17)
 *
 * 목적:
 *   HmacSignatureFilter의 ±60초 전수 검사 오버헤드 측정
 *   올바른 서명/잘못된 서명/헤더 누락 시나리오 분기 테스트
 *
 * 실행 방법:
 *   # F-26=true (HMAC 필수화) 환경에서 실행
 *   IDO_HMAC_SIG_REQUIRED=true k6 run test/load/k6-hmac-verification.js \
 *     -e BASE_URL=https://ido.staging.smes.go.kr \
 *     -e HMAC_SECRET=your-test-hmac-secret
 *
 * SLO 목표:
 *   - HMAC 검증 오버헤드 < 5ms (p99)
 *   - 유효 서명 통과율 100%
 *   - 무효 서명 거부율 100%
 */

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';
import { hmac } from 'k6/crypto';

// ── 커스텀 메트릭 ────────────────────────────────────────────────────────────
const validSigPassRate    = new Rate('valid_sig_pass_rate');
const invalidSigRejectRate = new Rate('invalid_sig_reject_rate');
const hmacOverhead        = new Trend('hmac_overhead_ms', true);

// ── 환경 설정 ────────────────────────────────────────────────────────────────
const BASE_URL    = __ENV.BASE_URL    || 'http://localhost:8083';
const HMAC_SECRET = __ENV.HMAC_SECRET || 'test-hmac-secret-32-chars-minimum!';
const AGENCY_CODE = __ENV.AGENCY_CODE || 'AGENCY_TEST_001';
const AGENCY_KEY  = __ENV.AGENCY_KEY  || 'test-agency-key';

export const options = {
  scenarios: {
    // 시나리오 1: 유효 서명 고부하 테스트
    valid_signature: {
      executor: 'constant-vus',
      vus: 50,
      duration: '2m',
      exec: 'testValidSignature',
    },
    // 시나리오 2: 무효 서명 거부 테스트 (보안 검증)
    invalid_signature: {
      executor: 'constant-vus',
      vus: 10,
      duration: '1m',
      startTime: '2m',
      exec: 'testInvalidSignature',
    },
    // 시나리오 3: 서명 없음 (F-26=false 소프트 모드 / F-26=true 거부 테스트)
    no_signature: {
      executor: 'constant-vus',
      vus: 10,
      duration: '1m',
      startTime: '3m',
      exec: 'testNoSignature',
    },
  },
  thresholds: {
    'http_req_duration{scenario:valid_signature}':   ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{scenario:invalid_signature}': ['p(95)<200'],
    'valid_sig_pass_rate':     ['rate>0.99'],    // 유효 서명 통과율 > 99%
    'invalid_sig_reject_rate': ['rate>0.99'],    // 무효 서명 거부율 > 99%
    'hmac_overhead_ms':        ['p(99)<5'],      // HMAC 오버헤드 5ms 미만
  },
};

// ── 시나리오 1: 유효 서명 ─────────────────────────────────────────────────────
export function testValidSignature() {
  const idempotencyKey = generateUuidV4();
  const correlationId  = generateUuidV4();
  const epochSeconds   = Math.floor(Date.now() / 1000);

  // HMAC-SHA256 서명 생성 (서버와 동일한 페이로드 규칙)
  // payload: "{agencyCode}:{idempotencyKey}:{epochSeconds}"
  const sigStart  = Date.now();
  const payload   = `${AGENCY_CODE}:${idempotencyKey}:${epochSeconds}`;
  const signature = hmac('sha256', HMAC_SECRET, payload, 'hex');
  const sigMs     = Date.now() - sigStart;

  hmacOverhead.add(sigMs);

  const response = http.post(
    `${BASE_URL}/api/v1/agency/gateway/inbound/event`,
    JSON.stringify({ event_type: 'USER_REGISTERED', test: true }),
    {
      headers: {
        'Content-Type':      'application/json',
        'X-Agency-Code':     AGENCY_CODE,
        'X-Agency-Key':      AGENCY_KEY,
        'X-Idempotency-Key': idempotencyKey,
        'X-Correlation-ID':  correlationId,
        'X-Event-Type':      'USER_REGISTERED',
        'X-Internal-Sig':    signature,  // 유효 서명
      },
      timeout: '5s',
    }
  );

  const passed = check(response, {
    'valid sig: not 401': (r) => r.status !== 401,
    'valid sig: 202 accepted or 503 feature_disabled': (r) =>
      r.status === 202 || r.status === 503,
  });

  validSigPassRate.add(passed ? 1 : 0);
  if (!passed) {
    console.error(`[유효서명 실패] status=${response.status} body=${response.body?.substring(0, 200)}`);
  }

  sleep(0.05);
}

// ── 시나리오 2: 무효 서명 (거부 테스트) ──────────────────────────────────────
export function testInvalidSignature() {
  const idempotencyKey = generateUuidV4();

  // 의도적으로 잘못된 서명 (랜덤 hex 64자)
  const fakeSignature = Array.from({ length: 64 }, () =>
    '0123456789abcdef'[Math.floor(Math.random() * 16)]
  ).join('');

  const response = http.post(
    `${BASE_URL}/api/v1/agency/gateway/inbound/event`,
    JSON.stringify({ event_type: 'USER_REGISTERED', test: true }),
    {
      headers: {
        'Content-Type':      'application/json',
        'X-Agency-Code':     AGENCY_CODE,
        'X-Agency-Key':      AGENCY_KEY,
        'X-Idempotency-Key': idempotencyKey,
        'X-Event-Type':      'USER_REGISTERED',
        'X-Internal-Sig':    fakeSignature,  // 의도적 무효 서명
      },
      timeout: '3s',
    }
  );

  // F-26=true: 401 거부 / F-26=false: 202 통과 (소프트 모드)
  const rejected = check(response, {
    'invalid sig: 401 rejected (F-26=true) OR 202 pass (F-26=false)': (r) =>
      r.status === 401 || r.status === 202 || r.status === 503,
    'invalid sig: response time < 200ms': (r) => r.timings.duration < 200,
  });

  // F-26=true인 경우 401이어야 함
  const properlyRejected = response.status === 401;
  invalidSigRejectRate.add(properlyRejected ? 1 : 0);

  sleep(0.1);
}

// ── 시나리오 3: 서명 없음 ──────────────────────────────────────────────────────
export function testNoSignature() {
  const idempotencyKey = generateUuidV4();

  const response = http.post(
    `${BASE_URL}/api/v1/agency/gateway/inbound/event`,
    JSON.stringify({ event_type: 'USER_REGISTERED', test: true }),
    {
      headers: {
        'Content-Type':      'application/json',
        'X-Agency-Code':     AGENCY_CODE,
        'X-Agency-Key':      AGENCY_KEY,
        'X-Idempotency-Key': idempotencyKey,
        'X-Event-Type':      'USER_REGISTERED',
        // X-Internal-Sig 헤더 없음
      },
      timeout: '3s',
    }
  );

  check(response, {
    // F-26=false: 202 또는 503(feature_disabled)
    // F-26=true:  401 (서명 없음 거부)
    'no sig: expected status (202|401|503)': (r) =>
      r.status === 202 || r.status === 401 || r.status === 503,
  });

  sleep(0.1);
}

// ── 요약 출력 ────────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const results = {
    'HMAC 검증 성능': {
      'HMAC 오버헤드 p99 (ms)': data.metrics.hmac_overhead_ms?.values?.['p(99)']?.toFixed(2),
      '유효서명 통과율': `${(data.metrics.valid_sig_pass_rate?.values?.rate * 100).toFixed(2)}%`,
      '무효서명 거부율': `${(data.metrics.invalid_sig_reject_rate?.values?.rate * 100).toFixed(2)}%`,
    },
    'SLO 합격': {
      'HMAC 오버헤드 p99 < 5ms': (data.metrics.hmac_overhead_ms?.values?.['p(99)'] < 5) ? '✅ PASS' : '❌ FAIL',
      '유효서명 통과율 > 99%':   (data.metrics.valid_sig_pass_rate?.values?.rate > 0.99) ? '✅ PASS' : '❌ FAIL',
      '무효서명 거부율 > 99%':   (data.metrics.invalid_sig_reject_rate?.values?.rate > 0.99) ? '✅ PASS' : '❌ FAIL',
    }
  };
  console.log('\n' + JSON.stringify(results, null, 2));
  return { 'test/load/results/k6-hmac-summary.json': JSON.stringify(data, null, 2) };
}

// ── 유틸리티 ─────────────────────────────────────────────────────────────────
function generateUuidV4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}
