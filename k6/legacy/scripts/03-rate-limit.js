/**
 * S8-T2 | 시나리오 03: Rate Limiter 집중 검증 테스트
 *
 * 목적:
 *   AgencyRateLimiter (Redis Sliding Window) 동작을 고강도 부하로 검증한다.
 *   - TPS 200 한도 초과 시 429 반환 확인
 *   - 한도 내 요청은 정상 처리 확인
 *   - Rate Limit 해제 후 정상 복구 확인 (1초 Window 만료)
 *
 * 실행:
 *   k6 run k6/scripts/03-rate-limit.js
 *   k6 run k6/scripts/03-rate-limit.js --env BASE_URL=http://localhost:8083 \
 *       --env AGENCY_CODE=AGENCY001
 *
 * 단계:
 *   1. 정상 부하 (50 RPS — 한도 이내)     → 429 없어야 함
 *   2. 과부하 (300 RPS — 한도 초과)       → 429 발생해야 함
 *   3. 복구 (50 RPS — 1초 윈도우 초기화)  → 429 없어야 함
 */

import http from 'k6/http';
import { sleep, check, group } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import {
  BASE_URL, AGENCY_CODE,
  jsonHeaders, internalHeaders,
  rateLimitHitCounter,
  fakeCi,
} from '../lib/helpers.js';

// ── 커스텀 메트릭 ──────────────────────────────────────────────────────────
const normalSuccessRate = new Rate('rate_limit_normal_success');  // 정상 구간 성공률
const overloadSuccessRate = new Rate('rate_limit_overload_429');  // 과부하 구간 429 발생률
const recoverySuccessRate = new Rate('rate_limit_recovery_ok');   // 복구 구간 성공률

// ── 부하 단계 ──────────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // Phase 1: 정상 부하 (TPS 한도 이내)
    normal_load: {
      executor: 'constant-arrival-rate',
      rate: 50,           // 50 RPS — 기본 TPS 한도(200) 이내
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 30,
      maxVUs: 60,
      tags: { phase: 'normal' },
    },
    // Phase 2: 과부하 (TPS 한도 초과)
    overload: {
      executor: 'constant-arrival-rate',
      rate: 300,          // 300 RPS — 기본 TPS 한도(200) 초과
      timeUnit: '1s',
      duration: '20s',
      startTime: '35s',   // normal_load 종료 5초 후 시작
      preAllocatedVUs: 100,
      maxVUs: 200,
      tags: { phase: 'overload' },
    },
    // Phase 3: 복구 확인 (Rate Limit 윈도우 초기화 후)
    recovery: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '20s',
      startTime: '60s',   // overload 종료 5초 후 시작 (1초 슬라이딩 윈도우 만료 대기)
      preAllocatedVUs: 20,
      maxVUs: 40,
      tags: { phase: 'recovery' },
    },
  },

  thresholds: {
    // 정상 구간: 성공률 95% 이상
    'rate_limit_normal_success':  ['rate>0.95'],
    // 과부하 구간: 429 발생률 50% 이상 (Rate Limit이 동작해야 함)
    'rate_limit_overload_429':    ['rate>0.50'],
    // 복구 구간: 성공률 90% 이상
    'rate_limit_recovery_ok':     ['rate>0.90'],
  },
};

// ── 시나리오 본체 ──────────────────────────────────────────────────────────
export default function (data) {
  const phase = __ENV.K6_SCENARIO_TAGS_phase || 'normal';
  const correlationId = `k6-rl-${phase}-${__VU}-${__ITER}`;
  const headers = jsonHeaders(correlationId);

  // CI-check 엔드포인트: 파라미터 검증만 수행 → 외부 의존성 없음
  // Rate Limit은 API Gateway 레이어 또는 HandoffController에서 적용됨
  // 여기서는 Handoff Issue로 Rate Limit 검증
  group(`Phase: ${phase}`, () => {
    const payload = JSON.stringify({
      ci:          fakeCi(),
      mbrDvsnCd:   'A101',
      indvlMbrNm:  '테스트유저',
      indvlMbrId:  `user-rl-${__VU}`,
    });

    const res = http.post(
      `${BASE_URL}/api/v1/auth/nice/ci-check`,
      payload,
      {
        headers,
        tags: { phase },
      }
    );

    if (phase === 'overload') {
      // 과부하 구간: 429 또는 서버 오류 기대 (Rate Limit 동작 확인)
      const is429 = res.status === 429;
      overloadSuccessRate.add(is429 ? 1 : 0);
      if (is429) rateLimitHitCounter.add(1);

      check(res, {
        'overload: responded (not timeout)': (r) => r.status !== 0,
      });
    } else if (phase === 'recovery') {
      // 복구 구간: 정상 응답 기대
      const isOk = res.status === 200;
      recoverySuccessRate.add(isOk ? 1 : 0);
      check(res, {
        'recovery: 200 OK': (r) => r.status === 200,
        'recovery: fast response': (r) => r.timings.duration < 1500,
      });
    } else {
      // 정상 구간: 정상 응답 기대 (4000 포함 — 검증 오류도 정상 처리)
      const isOk = res.status === 200;
      normalSuccessRate.add(isOk ? 1 : 0);
      check(res, {
        'normal: 200 OK': (r) => r.status === 200,
        'normal: not rate-limited': (r) => r.status !== 429,
        'normal: fast response':    (r) => r.timings.duration < 1000,
      });
    }
  });

  // 초당 요청 수 제어는 arrival-rate executor가 담당
  // VU 내부 슬립 없음 (정확한 RPS 유지)
}
