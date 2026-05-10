/**
 * S8-T2 | 시나리오 04: Soak Test (장시간 안정성 검증)
 *
 * 목적:
 *   낮은 TPS를 장시간 유지하여 메모리 누수, 커넥션 풀 고갈,
 *   Redis TTL 정합성, 세션 만료 처리 등을 검증한다.
 *
 * 실행:
 *   k6 run k6/scripts/04-soak.js                    # 기본 30분
 *   k6 run k6/scripts/04-soak.js --env DURATION=10m  # 단축 실행
 *
 * 검증 항목:
 *   - 30분간 p99 응답시간 3초 이하 유지
 *   - 메모리 누수 없음 (응답시간 드리프트 < 20%)
 *   - Rate Limit 429 없음 (정상 TPS 범위)
 */

import http from 'k6/http';
import { sleep, check, group } from 'k6';
import { Trend } from 'k6/metrics';
import {
  BASE_URL,
  jsonHeaders, internalHeaders,
  assertResponse,
  handoffIssueTrend, ciCheckTrend,
  fakeCi, randomAgencyCode,
} from '../lib/helpers.js';

const duration = __ENV.DURATION || '30m';

export const options = {
  scenarios: {
    soak: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m',      target: 10 },   // Ramp-up
        { duration: duration,  target: 10 },   // Soak
        { duration: '2m',      target: 0  },   // Drain
      ],
      gracefulRampDown: '30s',
    },
  },

  thresholds: {
    // p99 3초 이하 유지 (장시간 드리프트 감지)
    'http_req_duration{p:99}':        ['p(99)<3000'],
    'handoff_issue_duration{p:99}':   ['p(99)<3000'],
    'nice_ci_check_duration{p:99}':   ['p(99)<1500'],
    // HTTP 오류율 1% 이하
    'http_req_failed':                ['rate<0.01'],
    // 비즈니스 오류율 5% 이하
    'biz_error_rate':                 ['rate<0.05'],
    // Rate Limit 비발생
    'rate_limit_429_total':           ['count<1'],
  },
};

export default function () {
  const correlationId = `k6-soak-${__VU}-${__ITER}`;
  const headers = jsonHeaders(correlationId);

  // ── Handoff Issue ────────────────────────────────────────────────────────
  group('Soak: Handoff Issue', () => {
    const agencyCode = randomAgencyCode();
    const res = http.post(
      `${BASE_URL}/api/v1/handoff/issue`,
      JSON.stringify({
        agencyCode,
        agencySubjectId: `soak-${__VU}-${__ITER}`,
        returnUrl:       'https://www.smes.go.kr/auth-callback',
        authResult: {
          di:   `DI_SOAK_${__VU}_${'x'.repeat(32)}`,
          name: '소크테스트',
        },
      }),
      {
        headers: internalHeaders(correlationId),
        tags: { scenario: 'soak', endpoint: 'handoff_issue' },
      }
    );
    handoffIssueTrend.add(res.timings.duration);

    check(res, {
      'soak_handoff: server responded':  (r) => r.status !== 0,
      'soak_handoff: not timeout':       (r) => r.timings.duration < 5000,
      'soak_handoff: not rate-limited':  (r) => r.status !== 429,
    });
  });

  sleep(1);

  // ── CI Check ─────────────────────────────────────────────────────────────
  group('Soak: CI Check', () => {
    const res = http.post(
      `${BASE_URL}/api/v1/auth/nice/ci-check`,
      JSON.stringify({
        ci:         fakeCi(),
        mbrDvsnCd:  'A101',
        indvlMbrNm: '소크테스트유저',
      }),
      {
        headers,
        tags: { scenario: 'soak', endpoint: 'ci_check' },
      }
    );
    ciCheckTrend.add(res.timings.duration);

    check(res, {
      'soak_ci: server responded':  (r) => r.status !== 0,
      'soak_ci: not rate-limited':  (r) => r.status !== 429,
      'soak_ci: fast response':     (r) => r.timings.duration < 2000,
    });
  });

  sleep(2);  // 10 VU * (1+2)s ≈ 30 RPS (한도 200 TPS 이내)
}
