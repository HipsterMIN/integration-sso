/**
 * S8-T2 | 시나리오 01: Handoff Ticket 발급/검증 부하 테스트
 *
 * 검증 항목:
 *   - POST /api/v1/handoff/issue   → 200, resultCode 확인
 *   - POST /api/v1/handoff/verify  → 200, resultCode 확인
 *   - Idempotency-Key 중복 발급 방지 (동일 key로 2회 호출 시 동일 ticketId 반환)
 *   - Rate Limit 적용 검증 (TPS 초과 시 429 반환)
 *
 * 실행:
 *   k6 run k6/scripts/01-handoff.js
 *   k6 run k6/scripts/01-handoff.js --env BASE_URL=http://localhost:8083
 *
 * 단계별 부하:
 *   ramp-up  0→10 VU  (30s) → steady 10 VU (1m) → ramp-up 10→50 VU (30s)
 *   → steady 50 VU (2m) → spike 50→200 VU (15s) → recovery 200→10 VU (30s)
 *   → drain  10→0 VU   (30s)
 */

import http from 'k6/http';
import { sleep, group } from 'k6';
import {
  BASE_URL, AGENCY_CODE, INTERNAL_API_KEY,
  jsonHeaders, internalHeaders,
  assertResponse, assertRateLimited,
  handoffIssueTrend, handoffVerifyTrend, rateLimitHitCounter,
  fakeCi, randomAgencyCode,
} from '../lib/helpers.js';

// ── 부하 단계 정의 ─────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    handoff_normal: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10  },  // Ramp-up
        { duration: '60s', target: 10  },  // Warm-up steady
        { duration: '30s', target: 50  },  // Scale-up
        { duration: '120s', target: 50 },  // Peak load
        { duration: '15s', target: 200 },  // Spike (Rate Limit 발동 확인)
        { duration: '30s', target: 10  },  // Recovery
        { duration: '30s', target: 0   },  // Drain
      ],
      gracefulRampDown: '10s',
    },
  },

  // 성능 기준 (SLO)
  thresholds: {
    // p95 응답시간 2초 이하
    'handoff_issue_duration{p:95}':  ['p(95)<2000'],
    'handoff_verify_duration{p:95}': ['p(95)<2000'],
    // HTTP 오류율 1% 이하 (429 제외)
    'http_req_failed':               ['rate<0.01'],
    // 비즈니스 오류율 5% 이하
    'biz_error_rate':                ['rate<0.05'],
  },
};

// ── 시나리오 본체 ──────────────────────────────────────────────────────────
export default function () {
  const agencyCode     = randomAgencyCode();
  const correlationId  = `k6-handoff-${__VU}-${__ITER}`;
  const idempotencyKey = `idem-${__VU}-${__ITER}-${Date.now()}`;

  group('Handoff Issue', () => {
    const payload = JSON.stringify({
      agencyCode:     agencyCode,
      agencySubjectId: `subj-${__VU}-${__ITER}`,
      returnUrl:      'https://www.smes.go.kr/auth-callback',
      authResult: {
        di:   `DI_${__VU}_${__ITER}_${'x'.repeat(32)}`,
        name: '홍길동',
      },
    });

    const res = http.post(
      `${BASE_URL}/api/v1/handoff/issue`,
      payload,
      {
        headers: Object.assign(internalHeaders(correlationId), {
          'Idempotency-Key': idempotencyKey,
        }),
        tags: { endpoint: 'handoff_issue' },
      }
    );

    handoffIssueTrend.add(res.timings.duration);

    // 429(Rate Limit)은 별도 카운팅, 200/201 정상 검증
    if (res.status === 429) {
      assertRateLimited(res, 'handoff_issue');
    } else {
      const ok = assertResponse(res, 'handoff_issue', { maxDurationMs: 2000 });

      // Idempotency 검증: 동일 key로 재호출 시 동일 ticketId
      if (ok) {
        group('Idempotency Re-issue', () => {
          const res2 = http.post(
            `${BASE_URL}/api/v1/handoff/issue`,
            payload,
            {
              headers: Object.assign(internalHeaders(correlationId + '-retry'), {
                'Idempotency-Key': idempotencyKey,
              }),
              tags: { endpoint: 'handoff_issue_idempotency' },
            }
          );

          if (res2.status === 200) {
            let body1, body2;
            try { body1 = JSON.parse(res.body);  } catch (_) {}
            try { body2 = JSON.parse(res2.body); } catch (_) {}

            import { check } from 'k6';
            check(res2, {
              'idempotency: same ticketId returned': () =>
                body1 && body2 && body1.ticketId === body2.ticketId,
            });
          }
        });

        // Verify 테스트: 발급된 Ticket을 검증
        group('Handoff Verify', () => {
          let body;
          try { body = JSON.parse(res.body); } catch (_) { return; }
          if (!body || !body.ticketId) return;

          const verifyPayload = JSON.stringify({
            ticketId:   body.ticketId,
            agencyCode: agencyCode,
          });

          const vRes = http.post(
            `${BASE_URL}/api/v1/handoff/verify`,
            verifyPayload,
            {
              headers: internalHeaders(correlationId + '-verify'),
              tags: { endpoint: 'handoff_verify' },
            }
          );

          handoffVerifyTrend.add(vRes.timings.duration);

          if (vRes.status === 429) {
            assertRateLimited(vRes, 'handoff_verify');
          } else {
            assertResponse(vRes, 'handoff_verify', { maxDurationMs: 1500 });
          }
        });
      }
    }
  });

  // VU 간 간격 (과부하 방지)
  sleep(Math.random() * 0.5 + 0.1);
}
