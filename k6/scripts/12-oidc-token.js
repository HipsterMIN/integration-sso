/**
 * 12 | 표준 OIDC(OIDC_RP) 로그인 — gate 프런트 + Keycloak + hub 정책 판정, 토큰 교환 p95
 *
 * 기관 RP 가 하는 Authorization Code + PKCE(S256) 흐름을 브라우저 없이 그대로 한다:
 *   ① GET  {gate}/realms/{realm}/protocol/openid-connect/auth?client_id=idem-svc-…&code_challenge…   → Keycloak 로그인 화면(200, gate 프록시)
 *   ② POST 로그인 폼(username/password)                                                             → 302 redirect_uri?code=
 *   ③ POST {gate}/realms/{realm}/protocol/openid-connect/token (code + code_verifier + client_secret) → 200 토큰 ★ hub 판정 포함
 *   ④ GET  userinfo (Bearer)                                                                          → idem_state·idem_subject 보강
 *   ⑤ POST logout (id_token_hint) — Keycloak 세션 정리, 측정 제외
 *
 * 부하: 0→5 VU(30s) → 5(60s) → 5→10(30s) → 10(60s) → 0(20s), 약 3분 20초. Keycloak 비밀번호 해시가 CPU 를 쓴다.
 *
 * 환경변수: GATE_URL(기본 http://localhost:8081) GATE_PUBLIC_URL(폼 action 이 공개 주소면 바꿔 친다) OIDC_REALM(idem)
 *          OIDC_CLIENT_ID(idem-svc-PERF_RP) OIDC_CLIENT_SECRET(export) OIDC_REDIRECT_URI(https://rp.example.org/callback — 프로파일 redirectUris)
 *          OIDC_USER / OIDC_PASSWORD(export) — Keycloak realm 의 시험 사용자. OIDC_LOGOUT=false 면 ⑤ 생략
 *
 * 전제: 부하 발생기가 한 IP 이면 gate OIDC 프런트의 IP 레이트리밋(기본 20/초·300/분, `/realms/**`)이 1분 안에 걸린다 —
 *       시험 동안 IDEM_GATE_FRONT_RL_ENABLED=false 또는 한도를 올린다(성능 보고서 §3). 기관 한도(200 tps)는 그대로 둔다.
 */

import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { cookieHeader, queryParam, parseJson, enc } from '../lib/idem.js';

const strip = (u) => (u || '').replace(/\/+$/, '');
const GATE_URL        = strip(__ENV.GATE_URL || 'http://localhost:8081');
const GATE_PUBLIC_URL = strip(__ENV.GATE_PUBLIC_URL || GATE_URL);
const REALM           = __ENV.OIDC_REALM || 'idem';
const CLIENT_ID       = __ENV.OIDC_CLIENT_ID || 'idem-svc-PERF_RP';
const CLIENT_SECRET   = __ENV.OIDC_CLIENT_SECRET || '';
const REDIRECT_URI    = __ENV.OIDC_REDIRECT_URI || 'https://rp.example.org/callback';
const USER            = __ENV.OIDC_USER || 'poc-testuser';
const PASSWORD        = __ENV.OIDC_PASSWORD || '';
const LOGOUT          = (__ENV.OIDC_LOGOUT || 'true') !== 'false';
const OIDC            = `${GATE_URL}/realms/${REALM}/protocol/openid-connect`;

const authorizeTrend = new Trend('oidc_authorize_duration', true);   // ① 로그인 화면까지 (gate 사전검사 + Keycloak 렌더링)
const loginTrend     = new Trend('oidc_login_duration', true);       // ② 자격 검증 (Keycloak)
const tokenTrend     = new Trend('oidc_token_duration', true);       // ③ 토큰 교환 ★ (Keycloak 토큰 + hub 정책 판정)
const userinfoTrend  = new Trend('oidc_userinfo_duration', true);    // ④ userinfo 보강
const e2eTrend       = new Trend('oidc_e2e_duration', true);         // ①~④
const tokenDenied    = new Counter('oidc_token_denied_total');       // 403 access_denied / 429 / 503

export const options = {
  scenarios: {
    rp_login: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 5  },
        { duration: '60s', target: 5  },
        { duration: '30s', target: 10 },
        { duration: '60s', target: 10 },
        { duration: '20s', target: 0  },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'oidc_token_duration':    ['p(95)<2000'],   // SLO: 토큰 교환 p95 < 2s
    'oidc_login_duration':    ['p(95)<3000'],
    'oidc_e2e_duration':      ['p(95)<5000'],
    'oidc_token_denied_total': ['count<1'],
    'http_req_failed':        ['rate<0.01'],
    'checks':                 ['rate>0.99'],
  },
};

function rebaseGate(url) {
  if (url && GATE_PUBLIC_URL !== GATE_URL && url.indexOf(GATE_PUBLIC_URL) === 0) return GATE_URL + url.substring(GATE_PUBLIC_URL.length);
  return url;
}

export default function () {
  // 새 브라우저: 요청마다 빈 jar — Keycloak 쿠키는 ①의 응답에서 읽어 ②에 직접 넣는다(Secure 쿠키를 http 대상에도 보내기 위해)
  const verifier  = crypto.hexEncode(crypto.randomBytes(32));
  const challenge = crypto.sha256(verifier, 'base64rawurl');
  const state     = crypto.hexEncode(crypto.randomBytes(8));
  const nonce     = crypto.hexEncode(crypto.randomBytes(8));
  const t0 = Date.now();

  // ① authorize → 로그인 화면
  const authUrl = `${OIDC}/auth?client_id=${enc(CLIENT_ID)}&response_type=code&scope=openid&redirect_uri=${enc(REDIRECT_URI)}`
                + `&state=${state}&nonce=${nonce}&code_challenge=${challenge}&code_challenge_method=S256`;
  const a = http.get(authUrl, { jar: new http.CookieJar(), redirects: 0, tags: { endpoint: 'oidc_authorize' } });
  authorizeTrend.add(a.timings.duration);
  let action = null;
  if (a.status === 200) {
    try { action = a.html().find('form#kc-form-login').attr('action'); } catch (_) { action = null; }
  }
  if (!check(a, { 'authorize: login form (200)': () => a.status === 200 && !!action })) { sleep(1); return; }

  // ② 로그인 폼 제출
  const l = http.post(rebaseGate(action), { username: USER, password: PASSWORD, credentialId: '' }, {
    jar: new http.CookieJar(), redirects: 0,
    headers: { Cookie: cookieHeader(a) },
    tags: { endpoint: 'oidc_login' },
    responseCallback: http.expectedStatuses(302),
  });
  loginTrend.add(l.timings.duration);
  const loc  = l.headers['Location'] || '';
  const code = queryParam(loc, 'code');
  if (!check(l, {
    'login: 302 to redirect_uri with code': () => l.status === 302 && loc.indexOf(REDIRECT_URI) === 0 && !!code,
    'login: state echoed':                   () => queryParam(loc, 'state') === state,
  })) { sleep(1); return; }

  // ③ 토큰 교환 ★ — gate 가 Keycloak 토큰을 받은 뒤 hub 정책 판정(점검·인증수준·제공자·상태·할당·한도)을 거친다
  const t = http.post(`${OIDC}/token`, {
    grant_type: 'authorization_code', code, redirect_uri: REDIRECT_URI,
    client_id: CLIENT_ID, client_secret: CLIENT_SECRET, code_verifier: verifier,
  }, { jar: new http.CookieJar(), tags: { endpoint: 'oidc_token' }, responseCallback: http.expectedStatuses(200, 403, 429, 503) });
  tokenTrend.add(t.timings.duration);
  const tok = parseJson(t);
  if (t.status !== 200) tokenDenied.add(1);
  if (!check(t, {
    'token: 200':               (r) => r.status === 200,
    'token: id_token present':  () => !!tok && typeof tok.id_token === 'string',
  })) { sleep(1); return; }

  // ④ userinfo — idem_* 클레임
  const u = http.get(`${OIDC}/userinfo`, {
    jar: new http.CookieJar(),
    headers: { Authorization: `Bearer ${tok.access_token}` },
    tags: { endpoint: 'oidc_userinfo' },
  });
  userinfoTrend.add(u.timings.duration);
  const ui = parseJson(u);
  check(u, {
    'userinfo: 200':                      (r) => r.status === 200,
    'userinfo: idem_state APPROVED|GUEST': () => !!ui && (ui.idem_state === 'APPROVED' || ui.idem_state === 'GUEST'),
  });
  e2eTrend.add(Date.now() - t0);

  // ⑤ 로그아웃 (측정 제외) — Keycloak 사용자 세션이 쌓이지 않게
  if (LOGOUT) {
    http.post(`${OIDC}/logout`, { id_token_hint: tok.id_token, client_id: CLIENT_ID }, {
      jar: new http.CookieJar(), redirects: 0, tags: { endpoint: 'oidc_logout' },
      responseCallback: http.expectedStatuses(200, 204, 302),
    });
  }

  sleep(0.3 + Math.random() * 0.4);
}
