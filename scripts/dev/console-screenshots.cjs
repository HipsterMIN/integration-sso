#!/usr/bin/env node
/* 관리 콘솔 화면 캡처 (1.1.1 G2 — 관리자 매뉴얼 스크린샷 + 시험 항목 B-2·B-16 E2E 기록).
 *
 * 실제 스택(hub + registry + authz, 또는 설치본)과 콘솔(vite preview/nginx) 앞에서 Playwright(Chromium) 로 첫 로그인(2단계 등록 QR →
 * 코드 → 비밀번호 변경) → 데모 데이터 시드(관리 API, 같은 세션) → 화면별 PNG 를 docs/manuals/images/console/ 에 남긴다.
 *
 *   CONSOLE_URL=http://127.0.0.1:3001 IDEM_ADMIN_PASSWORD=<부트스트랩 비밀번호> IDEM_ADMIN_NEW_PASSWORD=<첫 로그인 뒤 바꿀 값> \
 *   NODE_PATH=$(npm root -g) node scripts/dev/console-screenshots.cjs
 *
 *   IDEM_ADMIN_TOTP_SECRET   이미 2단계가 등록된 관리자면 그 비밀(base32). 첫 로그인은 화면의 비밀로 등록한다(QR 캡처)
 *   IDEM_ADMIN_TOTP_SECRET_FILE  있으면 등록한 비밀을 여기에(0600) 쓰고, 다음 실행은 여기서 읽는다 (admin-login.sh 와 같은 규약)
 *   OUT_DIR                  기본 docs/manuals/images/console
 *   SEED=0                   데모 데이터(기관 3·동의 항목·역할·할당·테넌트·관리자) 시드 생략
 *   PLAYWRIGHT_BROWSERS_PATH 샌드박스/CI 의 Chromium 위치 (playwright 패키지는 전역 설치도 된다 — NODE_PATH)
 *
 * 비밀번호·2단계 비밀은 출력하지 않는다. 화면의 값은 전부 1회용 로컬 스택 값이다.
 */
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { chromium } = require('playwright');

const CONSOLE_URL = process.env.CONSOLE_URL || 'http://127.0.0.1:3001';
const USERNAME = process.env.IDEM_ADMIN_USERNAME || 'admin';
const PASSWORD = process.env.IDEM_ADMIN_PASSWORD;
const NEW_PASSWORD = process.env.IDEM_ADMIN_NEW_PASSWORD;
const SECRET_FILE = process.env.IDEM_ADMIN_TOTP_SECRET_FILE || '';
let TOTP_SECRET = process.env.IDEM_ADMIN_TOTP_SECRET || (SECRET_FILE && fs.existsSync(SECRET_FILE) ? fs.readFileSync(SECRET_FILE, 'utf8').trim() : '');
const OUT = process.env.OUT_DIR || path.join(__dirname, '..', '..', 'docs', 'manuals', 'images', 'console');
const SEED = process.env.SEED !== '0';
if (!PASSWORD) { console.error('IDEM_ADMIN_PASSWORD 를 설정하세요'); process.exit(2); }
fs.mkdirSync(OUT, { recursive: true });

function totp(secretB32, offset = 0) {
  const s = secretB32.trim().toUpperCase().replace(/=+$/, '');
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const ch of s) { const v = alphabet.indexOf(ch); if (v < 0) throw new Error('base32 아님'); bits += v.toString(2).padStart(5, '0'); }
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Math.floor(Date.now() / 1000 / 30) + offset;
  const msg = Buffer.alloc(8); msg.writeBigUInt64BE(BigInt(counter));
  const h = crypto.createHmac('sha1', key).update(msg).digest();
  const o = h[h.length - 1] & 0x0f;
  const code = ((h[o] & 0x7f) << 24 | (h[o + 1] & 0xff) << 16 | (h[o + 2] & 0xff) << 8 | (h[o + 3] & 0xff)) % 1000000;
  return String(code).padStart(6, '0');
}

const shots = [];
async function shot(target, name, opts = {}) {
  const file = path.join(OUT, name + '.png');
  await target.screenshot({ path: file, ...opts });
  shots.push(name); console.log('  📷', name);
}
const card = (page, title) => page.locator('section.card', { hasText: title }).first();

(async () => {
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 }, locale: 'ko-KR', deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  page.setDefaultTimeout(20000);
  page.on('dialog', (d) => d.accept());

  // ── 1. 로그인 → 2단계 등록(QR) → 비밀번호 변경 ───────────────────────
  await page.goto(CONSOLE_URL + '/#/services');
  await page.locator('input[autocomplete="username"]').fill(USERNAME);
  await shot(page, '01-login');
  await page.locator('input[autocomplete="current-password"]').fill(PASSWORD);
  await page.getByRole('button', { name: '로그인' }).click();
  const qr = page.locator('img[alt="인증 앱 등록 QR"]');
  const codeInput = page.locator('input[inputmode="numeric"]');
  const loginError = page.locator('.alert-error');
  await Promise.race([qr.waitFor({ state: 'visible' }), codeInput.waitFor({ state: 'visible' }), loginError.waitFor({ state: 'visible' })]);
  let usedPassword = PASSWORD;
  if (await loginError.count() && !(await qr.count()) && !(await codeInput.count())) {
    // 부트스트랩 비밀번호가 이미 바뀐 설치(재실행) — 바꾼 값으로 다시
    if (!NEW_PASSWORD) throw new Error('로그인 실패: ' + (await loginError.innerText()));
    await page.locator('input[autocomplete="current-password"]').fill(NEW_PASSWORD);
    await page.getByRole('button', { name: '로그인' }).click();
    usedPassword = NEW_PASSWORD;
    await Promise.race([qr.waitFor({ state: 'visible' }), codeInput.waitFor({ state: 'visible' })]);
  }
  if (await qr.count()) {
    await shot(page.locator('main.login'), '02-mfa-enroll-qr');
    TOTP_SECRET = (await page.locator('code.secret-value').first().innerText()).trim();
    if (SECRET_FILE) fs.writeFileSync(SECRET_FILE, TOTP_SECRET, { mode: 0o600 });
    console.log('  2단계 비밀을 화면에서 등록했다 (값은 출력하지 않는다' + (SECRET_FILE ? ', 파일에 저장' : '') + ')');
  } else if (!TOTP_SECRET) {
    throw new Error('2단계 인증이 필요한데 IDEM_ADMIN_TOTP_SECRET 이 없다');
  }
  await codeInput.fill(totp(TOTP_SECRET));
  await page.getByRole('button', { name: '확인' }).click();
  const pwNew = page.locator('input[autocomplete="new-password"]').first();
  const nav = page.locator('header.topbar nav');
  await Promise.race([pwNew.waitFor({ state: 'visible' }), nav.waitFor({ state: 'visible' }), loginError.waitFor({ state: 'visible' })]);
  if (!(await pwNew.count()) && !(await nav.count())) {
    // 같은 30초 스텝의 코드는 한 번만 받는다(1.0.1, RFC 6238 §5.2) — 대기 토큰이 소비됐으니 처음부터, 다음 스텝 코드로
    console.log('  2단계 코드 거부(같은 스텝 재사용) — 다음 스텝 코드로 다시 로그인');
    await page.locator('input[autocomplete="username"]').fill(USERNAME);
    await page.locator('input[autocomplete="current-password"]').fill(usedPassword);
    await page.getByRole('button', { name: '로그인' }).click();
    await codeInput.waitFor({ state: 'visible' });
    await codeInput.fill(totp(TOTP_SECRET, 1));
    await page.getByRole('button', { name: '확인' }).click();
    await Promise.race([pwNew.waitFor({ state: 'visible' }), nav.waitFor({ state: 'visible' })]);
  }
  if (await pwNew.count()) {
    if (!NEW_PASSWORD) throw new Error('첫 로그인 비밀번호 변경이 요구되는데 IDEM_ADMIN_NEW_PASSWORD 가 없다');
    await shot(page, '03-password-change-forced');
    await page.locator('input[autocomplete="current-password"]').fill(usedPassword);
    const news = page.locator('input[autocomplete="new-password"]');
    await news.nth(0).fill(NEW_PASSWORD); await news.nth(1).fill(NEW_PASSWORD);
    await page.getByRole('button', { name: '변경', exact: true }).click();
    await nav.waitFor({ state: 'visible' });
    console.log('  첫 로그인 비밀번호를 바꿨다');
  }

  // ── 2. 데모 데이터 (관리 API, 브라우저 세션과 같은 쿠키) ───────────────
  // Secure 쿠키는 http URL 로 거르면 빠진다 — 전체 쿠키에서 세션 쿠키만 고른다 (브라우저는 localhost 를 보안 문맥으로 봐서 UI 는 된다)
  const cookieHeader = (await ctx.cookies()).filter((c) => c.name === 'idemAdminSid').map((c) => `${c.name}=${c.value}`).join('; ');
  if (!cookieHeader) throw new Error('관리자 세션 쿠키(idemAdminSid)를 찾지 못했다');
  const api = async (method, p, body, extra = {}) => {
    const r = await page.request.fetch(CONSOLE_URL + '/api/v1/admin' + p, {
      method, headers: { 'X-Requested-With': 'idem-console-admin', 'Content-Type': 'application/json', Cookie: cookieHeader, ...extra },
      data: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok() && r.status() !== 409) throw new Error(`${method} ${p} → ${r.status()} ${await r.text()}`);
    return r;
  };
  const profile = (code, name, type, cb, level, extra = {}) => ({
    schemaVersion: 1, service: { code, name, status: 'ACTIVE' },
    protocol: { type, endpoints: { callbackWhitelist: [cb] } },
    identity: { attributes: ['name_masked'] }, policy: { minAuthLevel: level }, ...extra,
  });
  if (SEED) {
    console.log('  데모 데이터 시드');
    await api('PUT', '/services/DEMO_PORTAL/profile', profile('DEMO_PORTAL', '시민 포털', 'DIRECT', 'https://portal.example.go.kr/idem/callback', 'L1',
      { consent: { enabled: true, includePlatform: true } }), { 'X-Change-Reason': encodeURIComponent('데모 온보딩') });
    await api('PUT', '/services/DEMO_HR/profile', profile('DEMO_HR', '인사 시스템', 'DIRECT', 'https://hr.example.go.kr/sso/callback', 'L2'), { 'X-Change-Reason': encodeURIComponent('데모 온보딩') });
    await api('PUT', '/services/DEMO_LIB/profile', profile('DEMO_LIB', '도서관 서비스', 'DIRECT', 'https://lib.example.go.kr/cb', 'L1'), { 'X-Change-Reason': encodeURIComponent('데모 온보딩') });
    await api('PUT', '/agencies/DEMO_PORTAL', { agencyCode: 'DEMO_PORTAL', officialName: '시민 포털', minAuthLevel: 'L1', integrationType: 'DIRECT',
      callbackWhitelist: ['https://portal.example.go.kr/idem/callback'], webhookEndpoint: 'https://portal.example.go.kr/idem/hook', webhookEnabled: true });
    await api('POST', '/consents', { consentType: 'TERMS_OF_SERVICE', versionTag: '2026-10', title: 'Idem 이용약관', contentUrl: 'https://idem.example.go.kr/terms', required: true });
    await api('POST', '/consents', { consentType: 'PRIVACY_POLICY', versionTag: '2026-10', title: '개인정보 처리방침', contentUrl: 'https://idem.example.go.kr/privacy', required: true });
    await api('POST', '/services/DEMO_PORTAL/consents', { consentType: 'MARKETING', versionTag: '2026-10', title: '포털 소식 수신(선택)', required: false });
    await api('POST', '/services/DEMO_PORTAL/roles', { roleCode: 'VIEWER', name: '열람', description: '민원 조회' });
    await api('POST', '/services/DEMO_PORTAL/roles', { roleCode: 'EDITOR', name: '편집', description: '민원 처리·답변' });
    await api('POST', '/services/DEMO_PORTAL/assignments', { qimUserId: 'user-1001', reason: '신규 직원' });
    await api('POST', '/services/DEMO_PORTAL/assignments', { qimUserId: 'user-1002' });
    await api('POST', '/services/DEMO_PORTAL/assignments/user-1001/roles', { roleCode: 'VIEWER' });
    await api('PUT', '/tenants/GOV', { name: '중앙부처', status: 'ACTIVE' });
    await api('POST', '/admins', { username: 'auditor1', displayName: '감사 담당', role: 'AUDITOR', tenantCode: null });
  }

  // ── 3. 화면 ───────────────────────────────────────────────────────────
  await page.goto(CONSOLE_URL + '/#/services'); await page.reload();
  await page.locator('tbody tr.click').first().waitFor();
  await page.getByPlaceholder('코드·이름 검색').fill('demo');
  await page.waitForTimeout(700);
  await shot(page, '04-services-list');

  await page.goto(CONSOLE_URL + '/#/services/DEMO_PORTAL');
  await card(page, '정책 시뮬레이션').waitFor();
  await page.waitForTimeout(800);
  await shot(page, '05-service-detail-profile');
  await shot(card(page, '상태·API 키'), '06-status-api-key');
  const wh = card(page, '웹훅 서명 비밀');
  await wh.getByRole('button', { name: '서명 비밀 회전' }).click();
  await wh.locator('code.secret-value').waitFor();
  await shot(wh, '07-webhook-secret');
  const sim = card(page, '정책 시뮬레이션');
  await sim.getByRole('button', { name: '판정' }).click();
  await sim.locator('table').waitFor();
  await shot(sim, '08-policy-simulation');
  await shot(card(page, '동의 항목 — 이 서비스 전용'), '09-consent-service');
  const asg = card(page, '할당 관리');
  await asg.locator('tbody tr', { hasText: 'user-1001' }).getByRole('button', { name: '역할' }).click();
  await asg.locator('.subcard', { hasText: 'user-1001' }).locator('tbody tr').first().waitFor();
  await shot(asg, '10-assignments');
  await shot(card(page, '변경 이력'), '11-history');

  await page.goto(CONSOLE_URL + '/#/services/new'); await card(page, '새 기관 온보딩').waitFor(); await page.waitForTimeout(300); await shot(page, '12-onboarding-new', { fullPage: true });
  await page.goto(CONSOLE_URL + '/#/tenants'); await page.locator('tbody tr').first().waitFor(); await shot(page, '13-tenants');
  await page.goto(CONSOLE_URL + '/#/audit'); await page.locator('tbody tr').first().waitFor(); await shot(page, '14-audit');
  await page.goto(CONSOLE_URL + '/#/anomalies'); await page.locator('section.card').first().waitFor(); await page.waitForTimeout(800); await shot(page, '15-anomalies');
  await page.goto(CONSOLE_URL + '/#/consents'); await page.locator('tbody tr').first().waitFor(); await shot(page, '16-consents-platform');
  await page.goto(CONSOLE_URL + '/#/admins'); await page.locator('tbody tr').first().waitFor(); await shot(page, '17-admins');
  await page.goto(CONSOLE_URL + '/#/password'); await page.locator('input[autocomplete="new-password"]').first().waitFor(); await shot(page, '18-password');

  await browser.close();
  console.log(`완료 — ${shots.length}장 → ${OUT}`);
})().catch((e) => { console.error('실패:', e.message); process.exit(1); });
