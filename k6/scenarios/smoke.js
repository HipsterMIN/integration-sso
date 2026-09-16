/**
 * S8-T2 | 스모크 테스트 — 서버 기동 직후 최소 동작 확인
 *
 * 목적: CI/CD 파이프라인에서 배포 직후 핵심 엔드포인트가 살아있는지 확인.
 * 1 VU × 1회만 실행하므로 외부 의존성 오류는 허용된다.
 *
 * 게이트: `checks: rate==1` 임계값으로 check 하나라도 실패하면 k6 종료 코드 ≠ 0 → CI 잡 실패.
 * (이전에는 http_req_failed/http_req_duration 임계값만 있어 check 실패가 잡 실패로 이어지지 않았다.)
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
    'checks':            ['rate==1'],     // 모든 check 통과 — 실패 시 종료 코드 ≠ 0 (CI 게이트)
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

  // 1b. 본인인증 SPI — 제공자 목록에 MOCK 이 있고 initiate → complete 라운드트립이 된다 (P1 게이트)
  {
    const list = http.get(`${BASE_URL}/api/v1/auth/providers`, { headers: h });
    let codes = [];
    try { codes = JSON.parse(list.body).map((p) => p.code); } catch (_) { codes = []; }
    check(list, {
      'smoke: providers 200':        (r) => r.status === 200,
      'smoke: providers has MOCK':   () => codes.includes('MOCK'),
    });
    const init = http.post(
      `${BASE_URL}/api/v1/auth/providers/MOCK/initiate`,
      JSON.stringify({ returnUrl: 'https://fe.local/return', params: { name: 'k6', phone: '01099998888' } }),
      { headers: h }
    );
    let txId = null;
    try { txId = JSON.parse(init.body).txId; } catch (_) { txId = null; }
    check(init, {
      'smoke: MOCK initiate 200':    (r) => r.status === 200,
      'smoke: MOCK initiate txId':   () => typeof txId === 'string' && txId.startsWith('mock-'),
    });
    const done = http.post(
      `${BASE_URL}/api/v1/auth/providers/MOCK/complete`,
      JSON.stringify({ txId: txId, params: {} }),
      { headers: h }
    );
    check(done, {
      'smoke: MOCK complete 200':    (r) => r.status === 200,
      // S4 계약: { identity: VerifiedIdentity, registration: { qimUserId, newUser } } — registry(8082) 등록까지 끝나야 200
      'smoke: MOCK complete name':   (r) => { let b; try { b = JSON.parse(r.body); } catch (_) { return false; } return !!b && !!b.identity && b.identity.name === 'k6'; },
      'smoke: MOCK complete registration': (r) => { let b; try { b = JSON.parse(r.body); } catch (_) { return false; } return !!b && !!b.registration && typeof b.registration.qimUserId === 'string' && b.registration.qimUserId.length > 0; },
    });
  }

  // 2. CI-Check 파라미터 검증 (외부 의존성 없음)
  //    빈 ci 는 컨트롤러의 @Valid(@NotBlank/@Size) 에서 걸려 GlobalExceptionHandler 가
  //    400 + ErrorResponse{code:'E-IDO-400', message:'ci: …'} 를 돌려준다.
  //    서비스 레이어의 resultCode 4000 분기는 HTTP 로는 도달하지 않는다.
  {
    const res = http.post(
      `${BASE_URL}/api/v1/auth/nice/ci-check`,
      JSON.stringify({ ci: '', mbrDvsnCd: 'A101' }),
      { headers: h }
    );
    check(res, {
      'smoke: ci-check 400 (bean validation)': (r) => r.status === 400,
      'smoke: ci-check E-IDO-400 on ci':       (r) => {
        let b; try { b = JSON.parse(r.body); } catch (_) { return false; }
        return b && b.code === 'E-IDO-400' && typeof b.message === 'string' && b.message.startsWith('ci');
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
