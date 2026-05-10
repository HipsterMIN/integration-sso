/**
 * S8-T2 | 스트레스 테스트 — 시스템 한계점 탐색
 *
 * 목적: VU를 점진적으로 올려 시스템이 견딜 수 있는 최대 동시 접속자 수와
 *       붕괴(Breakpoint) 지점을 탐색한다.
 *
 * 실행:
 *   k6 run k6/scenarios/stress.js
 *
 * 분석 포인트:
 *   - 어느 VU 수에서 p99 > 5s (응답 저하) 시작하는가
 *   - 어느 VU 수에서 5xx 오류율이 급증하는가
 *   - Rate Limit(429)이 얼마나 빨리 발동하는가
 */

import http from 'k6/http';
import { sleep, check, group } from 'k6';
import {
  BASE_URL,
  jsonHeaders, internalHeaders,
  handoffIssueTrend, ciCheckTrend,
  rateLimitHitCounter,
  fakeCi, randomAgencyCode,
} from '../lib/helpers.js';

export const options = {
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m',  target: 50  },
        { duration: '3m',  target: 100 },
        { duration: '2m',  target: 200 },
        { duration: '2m',  target: 300 },
        { duration: '1m',  target: 400 },
        { duration: '3m',  target: 0   },  // Recovery
      ],
    },
  },

  thresholds: {
    // 스트레스: 임계치를 더 느슨하게 (한계 탐색 목적)
    'http_req_duration{p:99}': ['p(99)<10000'],
    'http_req_failed':         ['rate<0.10'],  // 10% 오류 허용
  },
};

export default function () {
  const correlationId = `k6-stress-${__VU}-${__ITER}`;

  group('Stress: mixed workload', () => {
    // Handoff Issue (DB+Redis 의존)
    const issueRes = http.post(
      `${BASE_URL}/api/v1/handoff/issue`,
      JSON.stringify({
        agencyCode:      randomAgencyCode(),
        agencySubjectId: `stress-${__VU}-${__ITER}`,
        returnUrl:       'https://www.smes.go.kr/callback',
        authResult:      { di: `DI_STRESS_${__VU}`, name: '스트레스' },
      }),
      {
        headers: internalHeaders(correlationId),
        tags: { endpoint: 'handoff_issue', scenario: 'stress' },
      }
    );
    handoffIssueTrend.add(issueRes.timings.duration);
    if (issueRes.status === 429) rateLimitHitCounter.add(1);

    check(issueRes, {
      'stress_handoff: responded': (r) => r.status !== 0,
    });

    // CI-Check (파라미터 검증만 — 가벼운 작업)
    const ciRes = http.post(
      `${BASE_URL}/api/v1/auth/nice/ci-check`,
      JSON.stringify({ ci: fakeCi(), mbrDvsnCd: 'A101', indvlMbrNm: '스트레스유저' }),
      {
        headers: jsonHeaders(correlationId + '-ci'),
        tags: { endpoint: 'ci_check', scenario: 'stress' },
      }
    );
    ciCheckTrend.add(ciRes.timings.duration);
    if (ciRes.status === 429) rateLimitHitCounter.add(1);

    check(ciRes, {
      'stress_ci: responded': (r) => r.status !== 0,
    });
  });

  sleep(Math.random() * 0.2);
}
