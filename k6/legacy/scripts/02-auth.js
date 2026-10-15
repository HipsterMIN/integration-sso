/**
 * S8-T2 | 시나리오 02: 본인인증 엔드포인트 부하 테스트
 *
 * 검증 항목:
 *   - GET  /api/v1/auth/nice/phone/url     → 2000 또는 외부 의존성 오류 허용
 *   - POST /api/v1/auth/nice/phone/result  → 파라미터 검증 오류(4000) 확인
 *   - POST /api/v1/auth/nice/ci-check      → 정상(2000) + 파라미터 오류(4000) 확인
 *   - POST /api/v1/auth/oacx/access-info   → 외부 SDK 의존성 허용
 *   - POST /api/v1/auth/oacx/easysign      → fn 검증 오류(4000) 확인
 *   - POST /api/v1/auth/callback           → 내부 오류 허용 (외부 의존성)
 *
 * 실행:
 *   k6 run k6/scripts/02-auth.js
 *   k6 run k6/scripts/02-auth.js --env BASE_URL=http://localhost:8083
 *
 * 주의:
 *   NICE/OACX 외부 API가 연결되지 않은 환경에서는 5xxx 응답이 정상.
 *   이 테스트는 "ido 서버 자체의 응답성 및 입력 검증"을 검증하며
 *   외부 API 성공 여부는 SLO 집계에서 제외된다.
 */

import http from 'k6/http';
import { sleep, group, check } from 'k6';
import {
  BASE_URL,
  jsonHeaders,
  assertResponse,
  assertRateLimited,
  niceUrlTrend, niceResultTrend, ciCheckTrend,
  oacxAccessTrend, oacxEasysignTrend,
  bizErrorRate, rateLimitHitCounter,
  fakeCi,
} from '../lib/helpers.js';

// ── 부하 단계 정의 ─────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // 정상 부하 (외부 API 미연결 환경 — 입력 검증 및 서버 응답성 측정)
    auth_steady: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 5  },
        { duration: '60s', target: 20 },
        { duration: '60s', target: 20 },
        { duration: '20s', target: 0  },
      ],
      gracefulRampDown: '10s',
    },
    // CI 체크 집중 부하 (파라미터 검증 로직)
    ci_check_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 20,
      maxVUs: 50,
      stages: [
        { duration: '30s', target: 10 },  // 10 RPS
        { duration: '60s', target: 30 },  // 30 RPS
        { duration: '15s', target: 80 },  // Spike — Rate Limit 검증
        { duration: '30s', target: 10 },
        { duration: '10s', target: 0  },
      ],
    },
  },

  thresholds: {
    // CI-check: p95 1초 이하 (파라미터 검증만이므로 빠름)
    'nice_ci_check_duration{p:95}':       ['p(95)<1000'],
    // 전반 HTTP 오류율 (4xx 검증 오류 제외)
    'http_req_failed':                    ['rate<0.05'],
  },
};

// ── 시나리오 본체 ──────────────────────────────────────────────────────────
export default function () {
  const correlationId = `k6-auth-${__VU}-${__ITER}`;
  const headers = jsonHeaders(correlationId);

  // ── 1. NICE 인증 URL 발급 (외부 의존성 — 오류 허용) ─────────────────────
  group('NICE phone URL', () => {
    const res = http.get(
      `${BASE_URL}/api/v1/auth/nice/phone/url?returnUrl=https://example.com/callback`,
      { headers, tags: { endpoint: 'nice_phone_url' } }
    );
    niceUrlTrend.add(res.timings.duration);

    // 외부 NICE API 없는 환경 → 5xxx 허용, 서버 자체는 응답해야 함
    check(res, {
      'nice_url: server responded': (r) => r.status !== 0,
      'nice_url: not timeout':       (r) => r.timings.duration < 5000,
    });
  });

  sleep(0.1);

  // ── 2. NICE 인증 결과 조회 — 파라미터 오류 케이스 ───────────────────────
  group('NICE phone result (validation)', () => {
    // request_no 누락 → 4000 기대
    const res = http.post(
      `${BASE_URL}/api/v1/auth/nice/phone/result`,
      JSON.stringify({ webTransactionId: 'web-txn-test' }),  // requestNo 없음
      { headers, tags: { endpoint: 'nice_phone_result' } }
    );
    niceResultTrend.add(res.timings.duration);

    check(res, {
      'nice_result: server responded': (r) => r.status !== 0,
      'nice_result: 4xx or 5xx validation': (r) =>
        r.status === 400 || r.status === 200, // resultCode=4000 반환
    });

    if (res.status === 200) {
      let body;
      try { body = JSON.parse(res.body); } catch (_) {}
      check(res, {
        'nice_result: resultCode=4000 (request_no 누락)': () =>
          body && body.resultCode === '4000',
      });
    }
  });

  sleep(0.1);

  // ── 3. CI 확인 — 정상 케이스 (A101 개인회원) ────────────────────────────
  group('CI check - individual', () => {
    const ci = fakeCi();
    const res = http.post(
      `${BASE_URL}/api/v1/auth/nice/ci-check`,
      JSON.stringify({
        ci:          ci,
        mbrDvsnCd:   'A101',
        indvlMbrNm:  '홍길동',
        indvlMbrId:  `user-${__VU}`,
      }),
      { headers, tags: { endpoint: 'nice_ci_check' } }
    );
    ciCheckTrend.add(res.timings.duration);

    if (res.status === 429) {
      assertRateLimited(res, 'ci_check_individual');
    } else {
      // 2000(성공) 또는 5010(Q-IM 미연결) 허용
      check(res, {
        'ci_check: server responded':     (r) => r.status === 200,
        'ci_check: duration < 2000ms':    (r) => r.timings.duration < 2000,
        'ci_check: resultCode 2xxx/5xxx': (r) => {
          let body;
          try { body = JSON.parse(r.body); } catch (_) { return false; }
          return body && (body.resultCode.startsWith('2') || body.resultCode.startsWith('5'));
        },
      });
    }
  });

  sleep(0.1);

  // ── 4. CI 확인 — 파라미터 오류 케이스 ──────────────────────────────────
  group('CI check - validation errors', () => {
    // 케이스 A: CI 누락
    {
      const res = http.post(
        `${BASE_URL}/api/v1/auth/nice/ci-check`,
        JSON.stringify({ ci: '', mbrDvsnCd: 'A101' }),
        { headers, tags: { endpoint: 'nice_ci_check_err' } }
      );
      check(res, {
        'ci_check_err: ci_blank → 4000': (r) => {
          if (r.status !== 200) return false;
          let body; try { body = JSON.parse(r.body); } catch (_) { return false; }
          return body && body.resultCode === '4000';
        },
      });
    }

    // 케이스 B: 잘못된 mbrDvsnCd
    {
      const res = http.post(
        `${BASE_URL}/api/v1/auth/nice/ci-check`,
        JSON.stringify({ ci: fakeCi(), mbrDvsnCd: 'X999' }),
        { headers, tags: { endpoint: 'nice_ci_check_err' } }
      );
      check(res, {
        'ci_check_err: invalid_mbrDvsnCd → 4000': (r) => {
          if (r.status !== 200) return false;
          let body; try { body = JSON.parse(r.body); } catch (_) { return false; }
          return body && body.resultCode === '4000';
        },
      });
    }

    // 케이스 C: 기업회원(A102) bizno 누락
    {
      const res = http.post(
        `${BASE_URL}/api/v1/auth/nice/ci-check`,
        JSON.stringify({ ci: fakeCi(), mbrDvsnCd: 'A102' }),  // bizno 없음
        { headers, tags: { endpoint: 'nice_ci_check_err' } }
      );
      check(res, {
        'ci_check_err: A102_no_bizno → 4000': (r) => {
          if (r.status !== 200) return false;
          let body; try { body = JSON.parse(r.body); } catch (_) { return false; }
          return body && body.resultCode === '4000';
        },
      });
    }
  });

  sleep(0.1);

  // ── 5. OACX 간편서명 — fn 검증 오류 케이스 (외부 SDK 불필요) ───────────
  group('OACX easysign (fn validation)', () => {
    // fn이 authComplete가 아닌 경우 → 4000
    const res = http.post(
      `${BASE_URL}/api/v1/auth/oacx/easysign`,
      JSON.stringify({ fn: 'INVALID_FN', status: 'success', res: {} }),
      { headers, tags: { endpoint: 'oacx_easysign' } }
    );
    oacxEasysignTrend.add(res.timings.duration);

    check(res, {
      'oacx_easysign: fn_invalid → 4000': (r) => {
        if (r.status !== 200) return false;
        let body; try { body = JSON.parse(r.body); } catch (_) { return false; }
        return body && body.resultCode === '4000';
      },
    });
  });

  sleep(0.1);

  // ── 6. OACX 간편서명 — resultCode 검증 오류 케이스 ─────────────────────
  group('OACX easysign (resultCode validation)', () => {
    // fn=authComplete, OACX resultCode=400 → 4001
    const res = http.post(
      `${BASE_URL}/api/v1/auth/oacx/easysign`,
      JSON.stringify({
        fn: 'authComplete',
        status: 'error',
        res: { resultCode: '400' },
      }),
      { headers, tags: { endpoint: 'oacx_easysign_rc' } }
    );
    oacxEasysignTrend.add(res.timings.duration);

    check(res, {
      'oacx_easysign: bad_resultCode → 4001': (r) => {
        if (r.status !== 200) return false;
        let body; try { body = JSON.parse(r.body); } catch (_) { return false; }
        return body && body.resultCode === '4001';
      },
    });
  });

  sleep(Math.random() * 0.3 + 0.1);
}
