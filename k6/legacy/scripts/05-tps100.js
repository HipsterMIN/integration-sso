/**
 * S8-T3 | TPS 100 목표 달성 검증 테스트
 *
 * 목적:
 *   OnePass ido 서비스가 TPS(Transaction Per Second) 100을 안정적으로 처리할 수 있는지
 *   constant-arrival-rate executor를 사용해 정밀하게 측정한다.
 *
 *   "TPS 100 달성" 기준:
 *     ✅ 100 RPS 구간에서 p95 응답시간 < 1,000ms
 *     ✅ 100 RPS 구간에서 p99 응답시간 < 2,000ms
 *     ✅ HTTP 오류율(5xx) < 1%
 *     ✅ 429(Rate Limit) 발생 없음 (한도 200 TPS 이내 운용)
 *
 * 테스트 구조 (총 약 5분):
 *   Phase 0 — Warmup   :  10 RPS × 30s  (캐시/커넥션 풀 준비)
 *   Phase 1 — Ramp-up  :  10 → 100 RPS  / 60s (점진 증가)
 *   Phase 2 — Sustain  : 100 RPS × 120s (목표 TPS 유지 — 주 측정 구간)
 *   Phase 3 — Spike    : 120 RPS × 30s  (10% 여유 마진 검증)
 *   Phase 4 — Cooldown :  20 RPS × 30s  (복구 확인)
 *
 * 실행:
 *   k6 run k6/scripts/05-tps100.js
 *   k6 run k6/scripts/05-tps100.js --env BASE_URL=http://localhost:8083
 *   k6 run k6/scripts/05-tps100.js --env BASE_URL=http://localhost:8083 --out json=reports/tps100-$(date +%Y%m%d-%H%M%S).json
 *
 * 실시간 대시보드:
 *   k6 run k6/scripts/05-tps100.js --out web-dashboard
 *   → http://127.0.0.1:5665
 *
 * Grafana+InfluxDB 연동:
 *   k6 run k6/scripts/05-tps100.js --out influxdb=http://localhost:8086/k6
 */

import http from 'k6/http';
import { check, group } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import {
  BASE_URL,
  jsonHeaders,
  internalHeaders,
  fakeCi,
  randomAgencyCode,
  rateLimitHitCounter,
} from '../lib/helpers.js';

// ── TPS 100 전용 커스텀 메트릭 ─────────────────────────────────────────────
/** 각 Phase별 성공률 */
const tpsWarmupOk    = new Rate('tps100_warmup_ok');
const tpsRampOk      = new Rate('tps100_ramp_ok');
const tpsSustainOk   = new Rate('tps100_sustain_ok');    // 핵심 — 이게 100%에 근접해야 함
const tpsSpikeOk     = new Rate('tps100_spike_ok');
const tpsCooldownOk  = new Rate('tps100_cooldown_ok');

/** Phase별 응답시간 트렌드 */
const sustainDuration = new Trend('tps100_sustain_duration', true);  // 주 측정값
const spikeDuration   = new Trend('tps100_spike_duration',   true);

/** TPS 측정 계량 */
const tps429Counter = new Counter('tps100_rate_limit_429');          // 0이어야 함 (한도 이내)
const tps5xxCounter = new Counter('tps100_server_error_5xx');        // 0 목표

// ── 부하 단계 ──────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    /**
     * Phase 0: Warmup — 10 RPS, 30초
     * JVM JIT 컴파일, DB 커넥션 풀, Redis 연결 등 준비
     */
    warmup: {
      executor:        'constant-arrival-rate',
      rate:            10,
      timeUnit:        '1s',
      duration:        '30s',
      preAllocatedVUs: 5,
      maxVUs:          20,
      tags:            { phase: 'warmup' },
      env:             { PHASE: 'warmup' },
    },

    /**
     * Phase 1: Ramp-up — 10 → 100 RPS 점진 증가, 60초
     * ramping-arrival-rate로 부드러운 증가
     */
    rampup: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit:  '1s',
      stages: [
        { duration: '60s', target: 100 },
      ],
      preAllocatedVUs: 30,
      maxVUs:          80,
      startTime:       '35s',   // warmup 종료 5초 후
      tags:            { phase: 'rampup' },
      env:             { PHASE: 'rampup' },
    },

    /**
     * Phase 2: Sustain — 100 RPS, 120초 ← 핵심 측정 구간
     * 목표 TPS를 2분간 유지하며 p95/p99 측정
     */
    sustain: {
      executor:        'constant-arrival-rate',
      rate:            100,
      timeUnit:        '1s',
      duration:        '120s',
      preAllocatedVUs: 50,
      maxVUs:          150,
      startTime:       '100s',  // rampup 종료 5초 후
      tags:            { phase: 'sustain' },
      env:             { PHASE: 'sustain' },
    },

    /**
     * Phase 3: Spike — 120 RPS, 30초 (목표 TPS의 120%)
     * 여유 마진 검증: TPS 100 달성 후에도 20% 오버헤드를 버티는가
     */
    spike: {
      executor:        'constant-arrival-rate',
      rate:            120,
      timeUnit:        '1s',
      duration:        '30s',
      preAllocatedVUs: 60,
      maxVUs:          180,
      startTime:       '225s', // sustain 종료 5초 후
      tags:            { phase: 'spike' },
      env:             { PHASE: 'spike' },
    },

    /**
     * Phase 4: Cooldown — 20 RPS, 30초
     * 피크 이후 정상 복구 확인
     */
    cooldown: {
      executor:        'constant-arrival-rate',
      rate:            20,
      timeUnit:        '1s',
      duration:        '30s',
      preAllocatedVUs: 10,
      maxVUs:          30,
      startTime:       '260s', // spike 종료 5초 후
      tags:            { phase: 'cooldown' },
      env:             { PHASE: 'cooldown' },
    },
  },

  // ── SLO 임계치 (TPS 100 달성 기준) ──────────────────────────────────────
  thresholds: {
    // ① Sustain 구간 p95 < 1,000ms — 핵심 SLO
    'tps100_sustain_duration{p:95}': ['p(95)<1000'],
    // ② Sustain 구간 p99 < 2,000ms
    'tps100_sustain_duration{p:99}': ['p(99)<2000'],
    // ③ Sustain 성공률 99% 이상
    'tps100_sustain_ok':             ['rate>0.99'],
    // ④ 전체 HTTP 오류율 1% 미만
    'http_req_failed':               ['rate<0.01'],
    // ⑤ Rate Limit 429 발생 없음 (TPS 200 한도 이내)
    'tps100_rate_limit_429':         ['count<1'],
    // ⑥ 서버 오류 5xx 없음
    'tps100_server_error_5xx':       ['count<5'],
    // ⑦ Spike 구간 p99 < 3,000ms (여유 마진)
    'tps100_spike_duration{p:99}':   ['p(99)<3000'],
    // ⑧ 전체 p95 < 2,000ms (글로벌 기준)
    'http_req_duration{p:95}':       ['p(95)<2000'],
  },
};

// ── 엔드포인트 호출 함수 ────────────────────────────────────────────────────

/**
 * 가장 가벼운 엔드포인트: CI-Check (파라미터 검증만, 외부 의존성 없음)
 * → Rate Limit 테스트에 적합, 빠른 응답 보장
 */
function callCiCheck(correlationId) {
  return http.post(
    `${BASE_URL}/api/v1/auth/nice/ci-check`,
    JSON.stringify({
      ci:         fakeCi(),
      mbrDvsnCd:  'A101',
      indvlMbrNm: '부하테스터',
      indvlMbrId: `tps-${__VU}-${__ITER}`,
    }),
    {
      headers: jsonHeaders(correlationId),
      tags:    { endpoint: 'ci_check' },
      timeout: '5s',
    }
  );
}

/**
 * 중간 무게 엔드포인트: Handoff Issue (DB+Redis 쓰기 포함)
 * → 실 서비스 TPS 검증에 더 적합
 */
function callHandoffIssue(correlationId) {
  return http.post(
    `${BASE_URL}/api/v1/handoff/issue`,
    JSON.stringify({
      agencyCode:      randomAgencyCode(),
      agencySubjectId: `tps100-${__VU}-${__ITER}-${Date.now()}`,
      returnUrl:       'https://www.smes.go.kr/tps-test',
      authResult: {
        di:   `DI_TPS_${__VU}_${__ITER}_${'x'.repeat(32)}`,
        name: '부하테스터',
      },
    }),
    {
      headers: internalHeaders(correlationId),
      tags:    { endpoint: 'handoff_issue' },
      timeout: '5s',
    }
  );
}

/**
 * Health Check: 서버 기동 상태 확인 (초경량)
 */
function callHealth(correlationId) {
  return http.get(
    `${BASE_URL}/actuator/health`,
    {
      headers: jsonHeaders(correlationId),
      tags:    { endpoint: 'health' },
      timeout: '3s',
    }
  );
}

// ── 공통 응답 처리 ──────────────────────────────────────────────────────────

function handleResponse(res, phase, rateMetric, durationMetric) {
  const ok = res.status >= 200 && res.status < 400;

  // 429 추적
  if (res.status === 429) {
    tps429Counter.add(1);
    rateLimitHitCounter.add(1);
  }

  // 5xx 추적
  if (res.status >= 500) {
    tps5xxCounter.add(1);
  }

  // Phase별 성공률 기록
  if (rateMetric) rateMetric.add(ok ? 1 : 0);

  // 응답시간 기록 (지정된 Phase만)
  if (durationMetric) durationMetric.add(res.timings.duration);

  // 공통 check
  check(res, {
    [`[${phase}] HTTP 응답 수신`]:      (r) => r.status !== 0,
    [`[${phase}] 5xx 없음`]:            (r) => r.status < 500,
    [`[${phase}] 429 없음 (한도 이내)`]: (r) => r.status !== 429,
  });

  return ok;
}

// ── 시나리오 본체 ──────────────────────────────────────────────────────────
export default function () {
  const phase = __ENV.PHASE || 'sustain';
  const correlationId = `k6-tps100-${phase}-${__VU}-${__ITER}`;

  switch (phase) {
    // ────────────────────────────────────────────────────────────────────
    case 'warmup': {
      group('Phase0: Warmup', () => {
        // Warmup은 health check로 가볍게
        const res = callHealth(correlationId);
        handleResponse(res, 'warmup', tpsWarmupOk, null);
      });
      break;
    }

    // ────────────────────────────────────────────────────────────────────
    case 'rampup': {
      group('Phase1: Ramp-up', () => {
        // CI-Check: 외부 의존성 없어 안정적 — ramp-up에 적합
        const res = callCiCheck(correlationId);
        handleResponse(res, 'rampup', tpsRampOk, null);
      });
      break;
    }

    // ────────────────────────────────────────────────────────────────────
    case 'sustain': {
      group('Phase2: Sustain @100 TPS', () => {
        // 핵심: CI-Check + Handoff Issue 혼합 (실 서비스 트래픽 패턴 모사)
        // 70% CI-Check (가벼운 read-like 요청)
        // 30% Handoff Issue (DB+Redis 쓰기 포함)
        const roll = Math.random();
        let res;

        if (roll < 0.70) {
          // 70%: CI-Check
          res = callCiCheck(correlationId + '-ci');
        } else {
          // 30%: Handoff Issue
          res = callHandoffIssue(correlationId + '-handoff');
        }

        handleResponse(res, 'sustain', tpsSustainOk, sustainDuration);
      });
      break;
    }

    // ────────────────────────────────────────────────────────────────────
    case 'spike': {
      group('Phase3: Spike @120 TPS', () => {
        // Spike 구간: CI-Check 집중 (빠른 응답으로 TPS 여유 마진 확인)
        const res = callCiCheck(correlationId + '-spike');
        handleResponse(res, 'spike', tpsSpikeOk, spikeDuration);
      });
      break;
    }

    // ────────────────────────────────────────────────────────────────────
    case 'cooldown': {
      group('Phase4: Cooldown', () => {
        const res = callCiCheck(correlationId + '-cool');
        handleResponse(res, 'cooldown', tpsCooldownOk, null);
      });
      break;
    }

    default: {
      const res = callCiCheck(correlationId);
      handleResponse(res, 'unknown', null, null);
    }
  }

  // constant-arrival-rate executor는 sleep 없이 사용
  // (arrival-rate가 TPS를 제어함 — sleep 추가 시 실제 TPS 낮아짐)
}
