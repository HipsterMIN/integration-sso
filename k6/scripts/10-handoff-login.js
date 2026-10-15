/**
 * 10 | Handoff 브라우저 로그인 끝-끝 (1.1 코어 로그인 프런트, MOCK 제공자)
 *
 * 이용자가 기관 화면에서 "Idem 으로 로그인" 을 누른 뒤 기관 콜백으로 돌아오고, 기관 서버가 티켓을 검증하기까지:
 *   ① GET  /api/v1/handoff/login?service&callback&provider=MOCK&state   → 302 (MOCK initiate)
 *   ② GET  /api/v1/handoff/login/continue?req=&mockTxId=&name&phone      → MOCK complete → registry 확정 → FE 세션 → 발급 → 302 callback?ticketId
 *   ③ POST /api/v1/handoff/verify (X-Agency-Code/Key)                   → 200 HandoffPayload(state APPROVED|GUEST)
 * 반복마다 새 브라우저(쿠키 없음) — 매번 로그인부터 한다. VU 마다 다른 사람(첫 반복은 신규 등록, 이후는 기존 회원).
 *
 * 부하: 0→10 VU(30s) → 10 VU(60s) → 10→30 VU(30s) → 30 VU(60s) → 0(20s), 약 3분 20초. 반복 사이 0.3~0.7초 생각 시간.
 *
 * 실행 (scripts/perf/run-k6.sh 가 프로파일·기관 키를 만들어 환경변수로 넘긴다):
 *   PERF_AGENCY_KEY=… k6 run k6/scripts/10-handoff-login.js --env HUB_URL=http://localhost:8083
 *
 * 전제: hub 에 MOCK 제공자 플러그인(IDEM_PLUGINS_MOCK_AUTH_ENABLED=true, 기본 프로파일), 서비스 PERF_SERVICE 의 DIRECT 프로파일
 *       (callbackWhitelist 에 PERF_CALLBACK). 부하 발생기가 한 IP 이면 hub 의 IP 레이트리밋(기본 20 tps·100/분·1,000/일)이
 *       먼저 걸린다 — 시험 동안 IDEM_HUB_AUTH_RL_ENABLED=false 또는 한도를 올린다(성능 보고서 §3).
 */

import { check, sleep } from 'k6';
import { identityFor, mockLogin, verifyTicket, parseJson, cid } from '../lib/idem.js';

export const options = {
  scenarios: {
    browser_login: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '60s', target: 10 },
        { duration: '30s', target: 30 },
        { duration: '60s', target: 30 },
        { duration: '20s', target: 0  },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'handoff_login_continue_duration': ['p(95)<2000'],   // 로그인+발급 서버 처리 (SLO: Handoff p95 < 2s)
    'handoff_login_e2e_duration':      ['p(95)<3000'],   // 진입 → 콜백 두 홉
    'handoff_verify_duration':         ['p(95)<2000'],
    'http_req_failed':                 ['rate<0.01'],
    'checks':                          ['rate>0.99'],
  },
};

export default function () {
  const identity = identityFor(__VU);
  const login = mockLogin(identity, { state: `s-${__VU}-${__ITER}` });
  if (!login.ok) {
    sleep(1);
    return;
  }

  const v = verifyTicket(login.ticketId, cid('verify'));
  const payload = parseJson(v);
  check(v, {
    'verify: 200':                 (r) => r.status === 200,
    'verify: state APPROVED|GUEST': () => !!payload && (payload.state === 'APPROVED' || payload.state === 'GUEST'),
  });

  sleep(0.3 + Math.random() * 0.4);
}
