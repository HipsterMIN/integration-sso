/**
 * 11 | Handoff 발급·검증 API p95 + 기관 한도(200 tps) 검증
 *
 * 발급 서비스(HandoffService.issue — 기관·한도·화이트리스트·정책·KMS 암호화·서명·Redis·감사)와 verify 를 브라우저 없이
 * 정해진 도착률(arrival rate)로 친다. VU 는 첫 반복에서 MOCK 로그인으로 FE 세션 쿠키를 한 번 받고(로그인 요청은 지표에서
 * 분리 태깅) 이후 반복은 발급 → 검증만 한다.
 *
 *   warmup    20 rps × 30s
 *   sustain  100 rps × 120s  ★ p95 측정 구간 (한도 200 tps 이내 → 429 없어야 한다)
 *   limit    300 rps × 20s   ★ 한도 초과 → 429 E-AGENCY-306 이 약 1/3 나와야 한다 (초 단위 창, 설치본 기본 200 tps)
 *   recovery  50 rps × 20s   → 429 없이 회복
 *
 * 실행:
 *   PERF_AGENCY_KEY=… k6 run k6/scripts/11-handoff-issue-api.js --env HUB_URL=http://localhost:8083
 *   프로파일에 limits.tps 를 두면 --env TPS_LIMIT=<값> 으로 기대 429 비율 문턱을 맞춘다(기본 200).
 */

import { check, fail } from 'k6';
import { Rate } from 'k6/metrics';
import { identityFor, mockLogin, issueTicket, verifyTicket, parseJson, cid } from '../lib/idem.js';

const TPS_LIMIT  = Number(__ENV.TPS_LIMIT || 200);
const LIMIT_RATE = Number(__ENV.LIMIT_RPS || 300);
/** limit 구간에서 기대하는 최소 429 비율 — (300-200)/300 ≈ 0.33 의 절반을 문턱으로(초 경계·VU 부족 여유) */
const EXPECT_LIMITED = Math.max(0.05, ((LIMIT_RATE - TPS_LIMIT) / LIMIT_RATE) * 0.5).toFixed(2);

/** 발급 요청이 429 였는가 (phase 태그로 나눠 본다) */
const limitedRate = new Rate('handoff_issue_rate_limited');

let feSessionId = null;   // VU 마다(모듈 범위는 VU 별) — 첫 반복에 로그인

export const options = {
  scenarios: {
    warmup:   { executor: 'constant-arrival-rate', rate: 20,         timeUnit: '1s', duration: '30s',  preAllocatedVUs: 20,  maxVUs: 60,
                startTime: '0s',   env: { PHASE: 'warmup' },   tags: { phase: 'warmup' } },
    sustain:  { executor: 'constant-arrival-rate', rate: 100,        timeUnit: '1s', duration: '120s', preAllocatedVUs: 60,  maxVUs: 200,
                startTime: '35s',  env: { PHASE: 'sustain' },  tags: { phase: 'sustain' } },
    limit:    { executor: 'constant-arrival-rate', rate: LIMIT_RATE, timeUnit: '1s', duration: '20s',  preAllocatedVUs: 120, maxVUs: 400,
                startTime: '160s', env: { PHASE: 'limit' },    tags: { phase: 'limit' } },
    recovery: { executor: 'constant-arrival-rate', rate: 50,         timeUnit: '1s', duration: '20s',  preAllocatedVUs: 40,  maxVUs: 100,
                startTime: '185s', env: { PHASE: 'recovery' }, tags: { phase: 'recovery' } },
  },
  thresholds: {
    'handoff_issue_duration{phase:sustain}':       ['p(95)<2000'],      // SLO: Handoff 발급 p95 < 2s @100 rps
    'handoff_verify_duration{phase:sustain}':      ['p(95)<2000'],
    'handoff_issue_rate_limited{phase:sustain}':   ['rate<0.001'],      // 한도 이내 → 429 없음
    'handoff_issue_rate_limited{phase:recovery}':  ['rate<0.001'],
    'handoff_issue_rate_limited{phase:limit}':     [`rate>${EXPECT_LIMITED}`],   // 한도 초과 → 429 발생
    'http_req_failed':                             ['rate<0.01'],       // 429 는 기대 응답(expectedStatuses)이라 실패로 세지 않는다
    'checks':                                      ['rate>0.99'],
  },
};

export default function () {
  const phase = __ENV.PHASE || 'sustain';
  const tags  = { phase };

  if (!feSessionId) {
    const login = mockLogin(identityFor(__VU), { state: `s-${__VU}`, tags: { phase: 'login' } });
    if (!login.feSessionId) {
      fail(`VU ${__VU}: MOCK 로그인 실패 (${login.error || 'no session'})`);
    }
    feSessionId = login.feSessionId;
  }

  const correlationId = cid(`issue-${phase}`);
  const res = issueTicket(feSessionId, correlationId, tags);

  if (res.status === 429) {
    limitedRate.add(1, tags);
    check(res, { 'issue: 429 is E-AGENCY-306': (r) => (r.body || '').indexOf('E-AGENCY-306') >= 0 }, tags);
    return;
  }
  limitedRate.add(0, tags);

  const ticket = parseJson(res);
  const ok = check(res, {
    'issue: 200':      (r) => r.status === 200,
    'issue: ticketId': () => !!ticket && typeof ticket.ticketId === 'string',
  }, tags);
  if (!ok) return;

  const v = verifyTicket(ticket.ticketId, correlationId, tags);
  const payload = parseJson(v);
  check(v, {
    'verify: 200':                  (r) => r.status === 200,
    'verify: state APPROVED|GUEST': () => !!payload && (payload.state === 'APPROVED' || payload.state === 'GUEST'),
  }, tags);
}
