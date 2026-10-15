/**
 * Idem 1.1 부하 시나리오 공통 — 코어 로그인 프런트(MOCK 제공자) 로그인, Handoff 발급·검증, 헤더, 지표.
 *
 * 환경변수 (k6 는 프로세스 환경변수와 `--env` 를 모두 `__ENV` 로 본다 — 비밀은 export 로 넘기고 `--env` 에 적지 않는다):
 *   HUB_URL          hub 주소 (기본 http://localhost:8083)
 *   HUB_PUBLIC_URL   hub 의 공개 주소(IDEM_PUBLIC_URL_HUB). 로그인 프런트의 Location 이 이 주소로 오면 HUB_URL 로 바꿔 친다 (기본 = HUB_URL)
 *   PERF_SERVICE     Handoff(DIRECT) 시험 서비스 코드 (기본 PERF_HO — scripts/perf/run-k6.sh 가 프로파일을 만든다)
 *   PERF_AGENCY_KEY  그 서비스의 기관 API 키 원문 (rotate-key 응답) — verify·API 발급의 X-Agency-Key
 *   PERF_CALLBACK    프로파일 callbackWhitelist 안의 콜백 (기본 https://agency.example.org/cb)
 *
 * 1.1 계약(2026-05 의 OnePass 스크립트와 다른 점):
 *   - 기관 API 는 X-Internal-Api-Key 가 아니라 X-Agency-Code + X-Agency-Key(SHA-256 이 agency_meta.api_key_hash) 로 인증한다
 *   - POST /api/v1/handoff/issue 는 FE 세션 쿠키(feSessionId) 가 있어야 한다 — 로그인 사실 없이는 발급되지 않는다
 *   - 응답은 HandoffTicket / HandoffPayload 그대로다 — resultCode 봉투가 없다
 *   - KR 전용 엔드포인트(/api/v1/auth/nice/**, oacx/**) 는 코어 에디션에 없다(404)
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// ── 환경 ──────────────────────────────────────────────────────────────────
const strip = (u) => (u || '').replace(/\/+$/, '');
export const HUB_URL        = strip(__ENV.HUB_URL || __ENV.BASE_URL || 'http://localhost:8083');
export const HUB_PUBLIC_URL = strip(__ENV.HUB_PUBLIC_URL || HUB_URL);
export const SERVICE_CODE   = __ENV.PERF_SERVICE || 'PERF_HO';
export const AGENCY_KEY     = __ENV.PERF_AGENCY_KEY || '';
export const CALLBACK_URL   = __ENV.PERF_CALLBACK || 'https://agency.example.org/cb';

// ── 공통 지표 ──────────────────────────────────────────────────────────────
/** 로그인 프런트 ① 진입(GET /api/v1/handoff/login → 302 제공자) */
export const loginEntryTrend    = new Trend('handoff_login_entry_duration', true);
/** 로그인 프런트 ③ 복귀(MOCK complete → registry 확정 → FE 세션 → 발급 → 302 콜백) — 로그인+발급의 서버 처리 시간 */
export const loginContinueTrend = new Trend('handoff_login_continue_duration', true);
/** 진입부터 콜백 ticketId 까지(두 홉 합) */
export const loginE2eTrend      = new Trend('handoff_login_e2e_duration', true);
/** POST /api/v1/handoff/issue (200 만) */
export const issueTrend         = new Trend('handoff_issue_duration', true);
/** POST /api/v1/handoff/verify (200 만) */
export const verifyTrend        = new Trend('handoff_verify_duration', true);
/** 429 E-AGENCY-306 (프로파일 limits.tps / 설치본 기본 200 tps) */
export const rateLimited        = new Counter('handoff_rate_limited_total');

// ── 헤더·유틸 ──────────────────────────────────────────────────────────────
export const enc = encodeURIComponent;

/** X-Correlation-Id — hub 규격([A-Za-z0-9._:-] 1~36자) 안에서. 넘기면 hub 가 새 id 로 바꾼다(TraceparentFilter) */
export function cid(prefix) {
  return `k6-${prefix}-${__VU}-${__ITER}`.substring(0, 36);
}

export function jsonHeaders(correlationId) {
  return {
    'Content-Type':     'application/json',
    'Accept':           'application/json',
    'X-Correlation-Id': correlationId || cid('req'),
  };
}

/** 기관 서버 간 호출 헤더 — verify·API 발급 */
export function agencyHeaders(correlationId) {
  return Object.assign(jsonHeaders(correlationId), {
    'X-Agency-Code': SERVICE_CODE,
    'X-Agency-Key':  AGENCY_KEY,
  });
}

/** 공개 주소로 온 Location 을 부하 대상 주소로 */
export function rebase(location) {
  if (location && HUB_PUBLIC_URL !== HUB_URL && location.startsWith(HUB_PUBLIC_URL)) {
    return HUB_URL + location.substring(HUB_PUBLIC_URL.length);
  }
  return location;
}

/** 응답이 심은 쿠키를 Cookie 헤더 값으로 — Secure 쿠키는 http 대상에서 k6 jar 가 보내지 않으므로 직접 넣는다 */
export function cookieHeader(res) {
  const parts = [];
  const cookies = res.cookies || {};
  Object.keys(cookies).forEach((name) => {
    const arr = cookies[name];
    if (arr && arr.length > 0) parts.push(`${name}=${arr[0].value}`);
  });
  return parts.join('; ');
}

export function queryParam(url, name) {
  const m = (url || '').match(new RegExp(`[?&]${name}=([^&#]+)`));
  return m ? decodeURIComponent(m[1]) : null;
}

export function parseJson(res) {
  try { return JSON.parse(res.body); } catch (_) { return null; }
}

/** VU 마다 다른 사람 — MOCK 은 name|birthDate|phone 해시로 주체를 정하므로 같은 VU 는 같은 사람(두 번째부터 기존 회원) */
export function identityFor(vu) {
  const n = 10000000 + (vu % 90000000);
  return { name: `k6-u${vu}`, phone: `010${String(n).slice(-8)}`, birthDate: '19900101' };
}

// ── 코어 로그인 프런트(1.1) — 브라우저가 하는 두 홉을 그대로 ──────────────────
/**
 * ① GET /api/v1/handoff/login?service&callback&provider=MOCK&state → 302 …/login/continue?req=&mockTxId=
 * ② GET 그 continue URL (+ MOCK 콜백 파라미터 name·phone·birthDate — 콜백 값이 우선이라 VU 별 다른 사람이 된다)
 *    → MOCK complete → registry 확정 → FE 세션 + Set-Cookie feSessionId → 발급 → 302 callback?ticketId=&state=
 *
 * @returns {{ok:boolean, ticketId:string|null, error:string|null, feSessionId:string|null, entry:Object, cont:Object}}
 */
export function mockLogin(identity, opts = {}) {
  const state = opts.state || `s-${__VU}-${__ITER}`;
  const tags  = Object.assign({}, opts.tags || {});
  const jar   = new http.CookieJar();   // 새 브라우저 — 이전 반복의 FE 세션 쿠키를 가져가지 않는다
  const correlationId = cid('login');

  const entryUrl = `${HUB_URL}/api/v1/handoff/login?service=${enc(SERVICE_CODE)}&callback=${enc(CALLBACK_URL)}`
                 + `&provider=MOCK&state=${enc(state)}`;
  const entry = http.get(entryUrl, {
    jar, redirects: 0,
    headers: { 'X-Correlation-Id': correlationId },
    tags: Object.assign({ endpoint: 'login_entry' }, tags),
    responseCallback: http.expectedStatuses(302),
  });
  loginEntryTrend.add(entry.timings.duration, tags);
  const hop = rebase(entry.headers['Location']);
  const entryOk = check(entry, {
    'login: entry 302': (r) => r.status === 302,
    'login: entry → continue(mockTxId)': () => !!hop && hop.indexOf('/api/v1/handoff/login/continue?') > 0 && hop.indexOf('mockTxId=') > 0,
  });
  if (!entryOk) {
    return { ok: false, ticketId: null, error: `entry ${entry.status}`, feSessionId: null, entry, cont: null };
  }

  const contUrl = `${hop}&name=${enc(identity.name)}&phone=${enc(identity.phone)}&birthDate=${enc(identity.birthDate)}`;
  const cont = http.get(contUrl, {
    jar, redirects: 0,
    headers: { 'X-Correlation-Id': correlationId },
    tags: Object.assign({ endpoint: 'login_continue' }, tags),
    responseCallback: http.expectedStatuses(302),
  });
  loginContinueTrend.add(cont.timings.duration, tags);
  loginE2eTrend.add(entry.timings.duration + cont.timings.duration, tags);

  const location    = cont.headers['Location'] || '';
  const ticketId    = queryParam(location, 'ticketId');
  const error       = queryParam(location, 'error');
  const feCookie    = cont.cookies && cont.cookies.feSessionId;
  const feSessionId = feCookie && feCookie.length > 0 ? feCookie[0].value : null;
  if (error === 'E-AGENCY-306') rateLimited.add(1, tags);

  check(cont, {
    'login: continue 302 → callback': (r) => r.status === 302 && location.indexOf(CALLBACK_URL) === 0,
    'login: callback has ticketId':   () => !!ticketId,
    'login: state echoed':            () => queryParam(location, 'state') === state,
    'login: feSessionId cookie':      () => !!feSessionId,
  });
  return { ok: !!ticketId, ticketId, error, feSessionId, entry, cont };
}

// ── 기관 서버 간 API ───────────────────────────────────────────────────────
/** POST /api/v1/handoff/verify — 1회 소비. 200 HandoffPayload */
export function verifyTicket(ticketId, correlationId, tags = {}) {
  const res = http.post(`${HUB_URL}/api/v1/handoff/verify`, JSON.stringify({ ticketId }), {
    headers: agencyHeaders(correlationId),
    tags: Object.assign({ endpoint: 'handoff_verify' }, tags),
    responseCallback: http.expectedStatuses(200),
  });
  if (res.status === 200) verifyTrend.add(res.timings.duration, tags);
  return res;
}

/**
 * POST /api/v1/handoff/issue — 로그인된 FE 세션(쿠키) + 기관 키. 200 HandoffTicket / 429 E-AGENCY-306(한도)
 * (브라우저 없이 발급 서비스만 반복 호출하는 측정용 — 기관 연동 코드는 이 API 를 직접 쓰지 않고 로그인 프런트로 들어온다)
 */
export function issueTicket(feSessionId, correlationId, tags = {}) {
  const body = JSON.stringify({
    agencyCode:   SERVICE_CODE,
    authResultId: `k6-${__VU}-${__ITER}`,
    authLevel:    'L1',
    providerCode: 'MOCK',
    callbackUrl:  CALLBACK_URL,
  });
  const res = http.post(`${HUB_URL}/api/v1/handoff/issue`, body, {
    headers: Object.assign(agencyHeaders(correlationId), { Cookie: `feSessionId=${feSessionId}` }),
    tags: Object.assign({ endpoint: 'handoff_issue' }, tags),
    responseCallback: http.expectedStatuses(200, 429),
  });
  if (res.status === 200) issueTrend.add(res.timings.duration, tags);
  if (res.status === 429) rateLimited.add(1, tags);
  return res;
}
