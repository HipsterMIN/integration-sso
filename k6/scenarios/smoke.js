/**
 * S8-T2 | 스모크 테스트 — 서버 기동 직후 최소 동작 확인
 *
 * 목적: CI/CD 파이프라인에서 배포 직후 핵심 엔드포인트가 살아있는지 확인.
 * 1 VU × 1회만 실행하므로 외부 의존성 오류는 허용된다.
 *
 * 실행:
 *   k6 run k6/scenarios/smoke.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, jsonHeaders, internalHeaders, fakeCi } from '../lib/helpers.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    'http_req_duration': ['p(95)<5000'],  // 스모크: 5초 이하
    'http_req_failed':   ['rate<1'],      // 타임아웃 없을 것
  },
};

export default function () {
  const cid = 'k6-smoke-001';
  const h = jsonHeaders(cid);

  // 1. Actuator Health (서버 기동 확인)
  {
    const res = http.get(`${BASE_URL}/actuator/health`, { headers: h });
    check(res, {
      'smoke: actuator health 200': (r) => r.status === 200,
    });
  }

  // 2. CI-Check 파라미터 검증 (외부 의존성 없음)
  {
    const res = http.post(
      `${BASE_URL}/api/v1/auth/nice/ci-check`,
      JSON.stringify({ ci: '', mbrDvsnCd: 'A101' }),
      { headers: h }
    );
    check(res, {
      'smoke: ci-check responds': (r) => r.status === 200,
      'smoke: ci-check 4000':     (r) => {
        let b; try { b = JSON.parse(r.body); } catch (_) { return false; }
        return b && b.resultCode === '4000';
      },
    });
  }

  // 3. OACX fn 검증 (외부 의존성 없음)
  {
    const res = http.post(
      `${BASE_URL}/api/v1/auth/oacx/easysign`,
      JSON.stringify({ fn: 'INVALID', status: 'success', res: {} }),
      { headers: h }
    );
    check(res, {
      'smoke: oacx-easysign responds': (r) => r.status === 200,
      'smoke: oacx-easysign 4000':     (r) => {
        let b; try { b = JSON.parse(r.body); } catch (_) { return false; }
        return b && b.resultCode === '4000';
      },
    });
  }

  // 4. Handoff Issue (내부 API 연결 확인)
  {
    const res = http.post(
      `${BASE_URL}/api/v1/handoff/issue`,
      JSON.stringify({
        agencyCode:      'AGENCY001',
        agencySubjectId: 'smoke-test-subject',
        returnUrl:       'https://www.smes.go.kr/callback',
        authResult:      { di: `DI_SMOKE_${'x'.repeat(40)}`, name: '스모크테스터' },
      }),
      { headers: internalHeaders(cid) }
    );
    // 외부 DB 연결 없는 환경에서 5xxx 허용, 서버가 응답했다는 사실만 확인
    check(res, {
      'smoke: handoff-issue responds': (r) => r.status !== 0,
    });
  }
}
