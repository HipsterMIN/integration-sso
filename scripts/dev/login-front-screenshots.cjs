#!/usr/bin/env node
/* 코어 로그인 프런트(이용자 화면) 캡처 — 사용자 매뉴얼 그림 (1.1.1 G2-4).
 *
 * hub 의 Handoff 브라우저 진입(GET /api/v1/handoff/login)을 Mock 본인확인 제공자로 끝까지 밟으며 이용자가 보는 화면을 PNG 로 남긴다:
 * 인증 방법 선택 → (Mock 인증) → 서비스 이용 동의(동의하지 않음 / 동의하고 계속) → 서비스 콜백 복귀 → 이미 로그인된 재진입 → 오류 화면 3종.
 * 기관 콜백은 이 스크립트가 띄우는 작은 HTTP 서버(CALLBACK 의 호스트를 Chromium host-resolver-rules 로 127.0.0.1 에 맵)가 받아
 * "서비스로 돌아온 화면"을 흉내 낸다 — 실제 기관 화면은 기관이 만든다. 리다이렉트된 요청은 Playwright route 로 가로챌 수 없어서 진짜 서버를 두고,
 * 샌드박스 Chromium 이 호스트 이름 + TLS 조합을 거부해(IP+TLS·호스트+HTTP 는 된다) 캡처용 콜백은 평문 HTTP 다 — 운영 콜백은 https 다.
 *
 *   HUB_URL=http://localhost:8083 SERVICE=DEMO_PORTAL CALLBACK=http://portal.example.go.kr:8444/idem/callback \
 *   NODE_PATH=$(npm root -g) node scripts/dev/login-front-screenshots.cjs
 *
 *   CALLBACK 은 SERVICE 프로파일의 callbackWhitelist 에 있어야 한다(console-screenshots.cjs 의 시드가 넣는다). 호스트는 공개 도메인 모양이어야
 *   한다(프로파일 검증기가 localhost·사설 IP 를 거부) — 이름 풀이는 host-resolver-rules 가 맡으니 DNS 는 필요 없다.
 *
 * 전제: hub 에 Mock 제공자(IDEM_PLUGINS_MOCK_AUTH_ENABLED=true)와 두 번째 인증 방법(선택 화면을 보려면
 * IDEM_HUB_HANDOFF_LOGIN_BROKER_PROVIDERS=keycloak 처럼)이 있고, SERVICE 는 consent.enabled 프로파일 + 필수 플랫폼 동의 항목이 있다
 * (scripts/dev/console-screenshots.cjs 의 시드가 만든다). OUT_DIR 기본 docs/manuals/images/login.
 */
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const HUB = (process.env.HUB_URL || 'http://localhost:8083').replace(/\/$/, '');
const SERVICE = process.env.SERVICE || 'DEMO_PORTAL';
const CALLBACK = process.env.CALLBACK || 'http://portal.example.go.kr:8444/idem/callback';
const http = require('http');
const OUT = process.env.OUT_DIR || path.join(__dirname, '..', '..', 'docs', 'manuals', 'images', 'login');
fs.mkdirSync(OUT, { recursive: true });
const entry = (q = {}) => HUB + '/api/v1/handoff/login?' + new URLSearchParams({ service: SERVICE, callback: CALLBACK, state: 'demo-state', ...q });

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
function agencyPage(url) {
  const u = new URL(url);
  const rows = [...u.searchParams.entries()].map(([k, v]) => `<tr><th>${esc(k)}</th><td>${esc(v)}</td></tr>`).join('');
  const shown = u.pathname + u.search;
  const err = u.searchParams.get('error');
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>시민 포털 (기관 화면 예시)</title>
<style>body{font-family:sans-serif;max-width:720px;margin:48px auto;color:#1d2230}h1{font-size:22px}table{border-collapse:collapse}th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #dde2ea;font-family:monospace;font-size:13px}
.ok{background:#e9f6ee;color:#1b7f4a;padding:10px 14px;border-radius:6px}.bad{background:#fdecea;color:#b3261e;padding:10px 14px;border-radius:6px}.muted{color:#5f6b7a;font-size:13px}</style></head>
<body><h1>시민 포털 — Idem 에서 돌아왔습니다</h1>
<p class="${err ? 'bad' : 'ok'}">${err ? `로그인이 완료되지 않았습니다 (오류 ${esc(err)}). ${esc(u.searchParams.get('error_description') || '')}` : '로그인 티켓을 받았습니다 — 포털 서버가 Idem 에 verify 한 뒤 세션을 엽니다.'}</p>
<table>${rows}</table>
<p class="muted">이 화면은 기관(서비스)이 만드는 콜백 페이지의 예시입니다. Idem 이 돌려보낸 경로: <code>${esc(shown)}</code></p></body></html>`;
}

const shots = [];
async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, name + '.png') });
  shots.push(name); console.log('  📷', name);
}
async function newPage(browser) {
  const ctx = await browser.newContext({ viewport: { width: 900, height: 640 }, locale: 'ko-KR' });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  return { ctx, page };
}

/** 기관 콜백 예시 서버 — CALLBACK 의 호스트:포트로 뜬다(호스트는 host-resolver-rules 로 127.0.0.1) */
function startAgencyServer() {
  const u = new URL(CALLBACK);
  if (u.protocol !== 'http:') throw new Error('캡처용 CALLBACK 은 http:// 여야 한다 (파일 머리 설명)');
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(agencyPage(u.origin + req.url));
  });
  return new Promise((resolve) => server.listen(Number(u.port || 80), '127.0.0.1', () => resolve(server)));
}

(async () => {
  const agency = await startAgencyServer();
  const cbHost = new URL(CALLBACK).hostname;
  // 콜백 호스트를 로컬 서버로; 샌드박스·CI 의 프록시 환경변수가 브라우저에 적용되지 않도록 프록시 없이(모든 대상이 로컬이다)
  const browser = await chromium.launch({ args: [`--host-resolver-rules=MAP ${cbHost} 127.0.0.1`, '--no-proxy-server'] });

  // ── B. 동의하지 않음 ──────────────────────────────────────────────────
  {
    const { ctx, page } = await newPage(browser);
    await page.goto(entry());
    await page.getByRole('heading', { name: 'Idem 로그인' }).waitFor();
    await shot(page, '01-login-chooser');
    await page.getByRole('link', { name: /^MOCK/ }).click();
    await page.getByRole('heading', { name: '서비스 이용 동의' }).waitFor();
    await shot(page, '02-consent');
    await page.getByRole('button', { name: '동의하지 않음' }).click();
    await page.waitForURL(CALLBACK + '*');
    await shot(page, '03-callback-declined');
    await ctx.close();
  }
  // ── A. 동의하고 계속 → 콜백 → 재진입(이미 로그인) ───────────────────────
  {
    const { ctx, page } = await newPage(browser);
    await page.goto(entry());
    await page.getByRole('link', { name: /^MOCK/ }).click();
    await page.getByRole('heading', { name: '서비스 이용 동의' }).waitFor();
    // 필수만 빼고 제출하면 다시 묻는다
    await page.getByRole('button', { name: '동의하고 계속' }).click();
    await page.locator('strong').first().waitFor();
    await shot(page, '04-consent-required-missing');
    for (const cb of await page.locator('input[name="agree"]').all()) await cb.check();
    await page.getByRole('button', { name: '동의하고 계속' }).click();
    await page.waitForURL(CALLBACK + '*');
    await shot(page, '05-callback-ticket');
    await page.goto(entry({ state: 'second-visit' }));
    await page.waitForURL(CALLBACK + '*');
    await shot(page, '06-callback-already-logged-in');
    await ctx.close();
  }
  // ── 오류 화면 ─────────────────────────────────────────────────────────
  {
    const { ctx, page } = await newPage(browser);
    await page.goto(entry({ service: 'NOPE_SVC' }));
    await page.getByRole('heading', { name: '로그인을 진행할 수 없습니다' }).waitFor();
    await shot(page, '07-error-unknown-service');
    await page.goto(entry({ callback: 'https://evil.example.org/cb' }));
    await page.getByRole('heading', { name: '로그인을 진행할 수 없습니다' }).waitFor();
    await shot(page, '08-error-callback-not-allowed');
    await page.goto(HUB + '/api/v1/handoff/login/start?req=00000000000000000000000000000000&provider=MOCK');
    await page.getByRole('heading', { name: '로그인을 진행할 수 없습니다' }).waitFor();
    await shot(page, '09-error-expired');
    await ctx.close();
  }
  await browser.close();
  agency.close();
  console.log(`완료 — ${shots.length}장 → ${OUT}`);
})().catch((e) => { console.error('실패:', e.message); process.exit(1); });
