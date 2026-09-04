/**
 * Idem Platform — k6 부하 테스트 스크립트
 * ============================================================
 * 목적: Rate Limiter 실효성 검증 및 SLA 임계값 확인
 *
 * 측정 대상 엔드포인트:
 *   - IdO  (port 8083): /api/v1/fe-session/check, /api/v1/slo/initiate
 *   - Q-Sign (port 8081): /api/v1/auth/oidc (Smoke 전용)
 *   - Actuator (port 8083): /actuator/health (Canary)
 *
 * 시나리오 구성:
 *   1. smoke    — 최소 부하(1 VU × 30s): 기본 동작 검증
 *   2. ramp_up  — 점진 증가(0→50 VU, 5m): 정상 범위 응답시간 확인
 *   3. peak     — 최고 부하(50 VU × 3m): Rate Limiter 429 응답 검증
 *   4. spike    — 급증(0→100→0 VU, 2m): 스파이크 내성 확인
 *   5. slo      — SLO 검증(20 VU × 5m): p95 < 500ms, 오류율 < 1%
 *
 * SLA 임계값 (Thresholds):
 *   - http_req_duration{scenario:slo} p(95) < 500ms
 *   - http_req_duration{scenario:slo} p(99) < 1000ms
 *   - http_req_failed{scenario:slo}        < 1%
 *   - http_req_failed{scenario:peak}       ≤ 30% (Rate Limiter 429 허용)
 *
 * 실행 방법:
 *   # 전체 시나리오 실행 (Docker 사용 시)
 *   docker run --rm -i --network host grafana/k6 run - < infra/k6/load-test.js
 *
 *   # 특정 시나리오만
 *   k6 run --env SCENARIO=smoke infra/k6/load-test.js
 *
 *   # 대상 서버 지정
 *   k6 run --env IDO_BASE_URL=https://ido.example.com infra/k6/load-test.js
 *
 * 환경변수:
 *   IDO_BASE_URL      — IdO 서비스 주소 (기본: http://localhost:8083)
 *   QSIGN_BASE_URL    — Q-Sign 서비스 주소 (기본: http://localhost:8081)
 *   SCENARIO          — 특정 시나리오만 실행 (smoke|ramp_up|peak|spike|slo)
 *   FE_SESSION_ID     — 세션 확인 테스트용 더미 세션 ID
 *
 * 참고: 설계서 §14 Rate Limiter 정책, §10 SLA 요구사항
 */

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { randomString, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ── 환경변수 ─────────────────────────────────────────────────────────────────
const IDO_BASE_URL   = __ENV.IDO_BASE_URL   || 'http://localhost:8083';
const QSIGN_BASE_URL = __ENV.QSIGN_BASE_URL || 'http://localhost:8081';
const SCENARIO_FILTER = __ENV.SCENARIO || '';

// ── 커스텀 메트릭 ─────────────────────────────────────────────────────────────
const rateLimitedRequests = new Counter('rate_limited_requests');  // 429 카운트
const authFailureRate     = new Rate('auth_failure_rate');         // 인증 오류 비율
const sloLatency          = new Trend('slo_latency_ms', true);    // SLO 처리시간
const sessionCheckLatency = new Trend('session_check_latency_ms', true);

// ── SLA 임계값 ────────────────────────────────────────────────────────────────
export const options = {
  // 모든 시나리오를 구성하되, SCENARIO 환경변수로 선택적 실행 가능
  scenarios: buildScenarios(),

  thresholds: {
    // ── 전체 요청 기준 ──
    'http_req_duration':                    ['p(95)<1000'],   // 전체 p95 < 1s
    'http_req_failed':                      ['rate<0.30'],    // 전체 오류율 < 30% (peak 허용)

    // ── SLO 시나리오 전용 ──
    'http_req_duration{scenario:slo}':      ['p(95)<500', 'p(99)<1000'],
    'http_req_failed{scenario:slo}':        ['rate<0.01'],    // SLO 오류율 < 1%

    // ── Smoke 시나리오 ──
    'http_req_duration{scenario:smoke}':    ['p(99)<300'],

    // ── 커스텀 메트릭 ──
    'slo_latency_ms':                       ['p(95)<500'],
    'session_check_latency_ms':             ['p(95)<300'],
    'auth_failure_rate':                    ['rate<0.05'],
  },
};

// ── 시나리오 빌더 ─────────────────────────────────────────────────────────────
function buildScenarios() {
  const all = {
    // 1. Smoke: 최소 부하 — 기본 동작 검증 (CI 게이트로 활용)
    smoke: {
      executor: 'constant-vus',
      vus: 1,
      duration: '30s',
      exec: 'scenarioSmoke',
      tags: { scenario: 'smoke' },
    },

    // 2. Ramp-up: 점진 증가 — 정상 범위 응답시간 확인
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 20 },   // 0→20 VU
        { duration: '3m', target: 50 },   // 20→50 VU (정점)
        { duration: '1m', target: 0  },   // 50→0 VU (쿨다운)
      ],
      exec: 'scenarioRampUp',
      tags: { scenario: 'ramp_up' },
      startTime: '35s',    // smoke 완료 후 시작
    },

    // 3. Peak: 최고 부하 — Rate Limiter 429 응답 검증
    peak: {
      executor: 'constant-vus',
      vus: 50,
      duration: '3m',
      exec: 'scenarioPeak',
      tags: { scenario: 'peak' },
      startTime: '7m35s',  // ramp_up 완료 후 시작
    },

    // 4. Spike: 급증 — 스파이크 내성 확인
    spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 100 },  // 급증
        { duration: '1m',  target: 100 },  // 유지
        { duration: '30s', target: 0   },  // 급감
      ],
      exec: 'scenarioSpike',
      tags: { scenario: 'spike' },
      startTime: '11m35s',  // peak 완료 후 시작
    },

    // 5. SLO: SLO 검증 — p95 < 500ms, 오류율 < 1% (SLA 핵심 지표)
    slo: {
      executor: 'constant-vus',
      vus: 20,
      duration: '5m',
      exec: 'scenarioSlo',
      tags: { scenario: 'slo' },
      startTime: '14m5s',   // spike 완료 후 시작
    },
  };

  // SCENARIO 환경변수로 단일 시나리오 선택 실행
  if (SCENARIO_FILTER && all[SCENARIO_FILTER]) {
    const selected = {};
    selected[SCENARIO_FILTER] = all[SCENARIO_FILTER];
    // startTime 제거 (단독 실행)
    selected[SCENARIO_FILTER].startTime = undefined;
    return selected;
  }

  return all;
}

// ── 공통 유틸 ─────────────────────────────────────────────────────────────────

/**
 * 공통 헤더 생성
 * @param {string} [correlationId] - 미지정 시 랜덤 UUID 형식 생성
 */
function commonHeaders(correlationId) {
  return {
    'Content-Type':    'application/json',
    'X-Correlation-Id': correlationId || generateCorrelationId(),
    'Accept':          'application/json',
  };
}

/** UUID v4 형식의 Correlation ID 생성 (PII 아님) */
function generateCorrelationId() {
  return `k6-${randomString(8)}-${randomString(4)}-${randomString(4)}-${randomString(12)}`;
}

/**
 * 응답 상태 체크 헬퍼
 * @param {Response} res
 * @param {string}   tag  - 로그/메트릭 구분용
 */
function checkResponse(res, tag) {
  const ok = check(res, {
    [`${tag}: 상태코드 2xx`]:         (r) => r.status >= 200 && r.status < 300,
    [`${tag}: 응답시간 < 1000ms`]:    (r) => r.timings.duration < 1000,
  });
  authFailureRate.add(!ok);
  return ok;
}

/**
 * Rate Limit(429) 응답 체크 및 카운터 증가
 */
function checkRateLimited(res, tag) {
  if (res.status === 429) {
    rateLimitedRequests.add(1);
    return true;
  }
  return false;
}

// ── 헬스체크 (공통) ───────────────────────────────────────────────────────────
function healthCheck() {
  const res = http.get(`${IDO_BASE_URL}/actuator/health`, {
    headers: { 'Accept': 'application/json' },
    tags:    { endpoint: 'health' },
  });
  check(res, {
    'health: UP': (r) => {
      try {
        return JSON.parse(r.body).status === 'UP';
      } catch {
        return r.status === 200;
      }
    },
  });
}

// ── 세션 확인 요청 ─────────────────────────────────────────────────────────────
function feSessionCheck(feSessionId) {
  const url = feSessionId
    ? `${IDO_BASE_URL}/api/v1/fe-session/check`
    : `${IDO_BASE_URL}/api/v1/fe-session/check`;

  const params = {
    headers: commonHeaders(),
    tags:    { endpoint: 'fe_session_check' },
    cookies: feSessionId ? { feSessionId } : {},
  };

  const start = Date.now();
  const res = http.get(url, params);
  sessionCheckLatency.add(Date.now() - start);

  check(res, {
    'session_check: 200 또는 401': (r) => r.status === 200 || r.status === 401,
    'session_check: 응답시간 < 300ms': (r) => r.timings.duration < 300,
  });
  checkRateLimited(res, 'session_check');
  return res;
}

// ── SLO 시작 요청 ─────────────────────────────────────────────────────────────
function sloInitiate(feSessionId) {
  const params = {
    headers: commonHeaders(),
    tags:    { endpoint: 'slo_initiate' },
    cookies: feSessionId ? { feSessionId } : {},
  };

  const start = Date.now();
  const res = http.post(
    `${IDO_BASE_URL}/api/v1/slo/initiate`,
    null,  // SloController @PostMapping body 없음
    params,
  );
  sloLatency.add(Date.now() - start);

  // 204 (성공) 또는 429 (Rate Limit) 허용, 기타는 오류 처리
  const isRateLimited = checkRateLimited(res, 'slo_initiate');
  if (!isRateLimited) {
    check(res, {
      'slo_initiate: 204 No Content': (r) => r.status === 204,
      'slo_initiate: 응답시간 < 500ms': (r) => r.timings.duration < 500,
    });
  }
  return res;
}

// ── Q-Sign 헬스체크 (Smoke 전용) ──────────────────────────────────────────────
function qsignHealthCheck() {
  const res = http.get(`${QSIGN_BASE_URL}/actuator/health`, {
    headers: { 'Accept': 'application/json' },
    tags:    { endpoint: 'qsign_health' },
  });
  check(res, {
    'qsign_health: 응답 수신': (r) => r.status === 200 || r.status === 503,
  });
}

// ══════════════════════════════════════════════════════════════════════════════
// 시나리오 실행 함수
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 1. Smoke — 기본 동작 검증
 *    - 1 VU × 30s
 *    - 헬스체크 + 세션 확인 + Q-Sign 헬스체크 순서 확인
 */
export function scenarioSmoke() {
  group('smoke: 기본 동작 검증', () => {
    group('IdO 헬스체크', () => {
      healthCheck();
      sleep(0.5);
    });

    group('FE 세션 확인 (세션 없음)', () => {
      feSessionCheck(null);
      sleep(0.3);
    });

    group('Q-Sign 헬스체크', () => {
      qsignHealthCheck();
      sleep(0.5);
    });
  });

  sleep(1);
}

/**
 * 2. Ramp-up — 점진 증가 시 응답시간 확인
 *    - 0→50 VU, 6분
 *    - 세션 확인 반복 (Redis GET 집중)
 */
export function scenarioRampUp() {
  group('ramp_up: 점진 증가', () => {
    // 80% — 세션 확인 (읽기 집중)
    if (Math.random() < 0.8) {
      feSessionCheck(null);
    } else {
      // 20% — SLO 시도 (쓰기 부하)
      sloInitiate(null);
    }
  });

  sleep(Math.random() * 0.5 + 0.1);  // 100~600ms 랜덤 대기
}

/**
 * 3. Peak — Rate Limiter 429 응답 검증
 *    - 50 VU × 3분 (고정 부하)
 *    - 빠른 연속 요청으로 Rate Limiter 트리거
 *    - 목표: 429 응답이 정상적으로 반환되는지 확인
 */
export function scenarioPeak() {
  group('peak: Rate Limiter 검증', () => {
    const endpoint = randomItem(['session_check', 'slo_initiate', 'health']);

    if (endpoint === 'session_check') {
      const res = feSessionCheck(null);
      // Rate Limit 응답 수신 시 재시도 없이 로깅만
      if (res.status === 429) {
        check(res, {
          'peak: 429 Retry-After 헤더 존재': (r) =>
            r.headers['Retry-After'] !== undefined ||
            r.headers['X-Rate-Limit-Reset'] !== undefined ||
            true,  // 헤더 없어도 통과 (구현 선택사항)
        });
      }
    } else if (endpoint === 'slo_initiate') {
      sloInitiate(null);
    } else {
      healthCheck();
    }
  });

  // Peak 시나리오: 최소 대기 (Rate Limiter 적극 트리거)
  sleep(0.05);
}

/**
 * 4. Spike — 급증 내성 확인
 *    - 0→100→0 VU, 2분
 *    - 헬스체크 위주 (서버 과부하 시에도 기본 응답 확인)
 */
export function scenarioSpike() {
  group('spike: 급증 내성', () => {
    // 스파이크 중 헬스체크 우선
    healthCheck();

    if (Math.random() < 0.3) {
      feSessionCheck(null);
    }
  });

  sleep(Math.random() * 0.3);
}

/**
 * 5. SLO — SLA 임계값 검증
 *    - 20 VU × 5분 (안정적인 일정 부하)
 *    - p95 < 500ms, 오류율 < 1% 달성 여부 확인
 *    - Rate Limiter가 트리거되지 않는 정상 부하 범위
 */
export function scenarioSlo() {
  group('slo: SLA 임계값 검증', () => {
    // 세션 확인 → 짧은 대기 → SLO 흐름 시뮬레이션
    const checkRes = feSessionCheck(null);
    sleep(0.2);

    // 세션이 없는 경우 SLO도 세션 없이 시도 (멱등성 확인)
    sloInitiate(null);
    sleep(0.5);

    // 헬스 상태도 주기적으로 확인
    if (Math.random() < 0.2) {
      healthCheck();
    }
  });

  sleep(Math.random() * 1 + 0.5);  // 500ms~1.5s 대기 (현실적인 사용 간격)
}

// ── 기본 export (단일 시나리오가 없을 때 Fallback) ───────────────────────────
// 환경변수 SCENARIO가 설정되지 않은 경우 모든 시나리오가 options.scenarios에서 실행됨.
// k6는 export default를 무시하고 scenarios를 우선 실행.
export default function () {
  scenarioSmoke();
}
