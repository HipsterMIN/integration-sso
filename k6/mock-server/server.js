/**
 * TPS 100 테스트용 Mock 서버 (Node.js)
 * Port: 8099
 *
 * ido 서비스의 핵심 엔드포인트를 흉내냄:
 *   GET  /actuator/health
 *   POST /api/v1/auth/nice/ci-check
 *   POST /api/v1/handoff/issue
 *
 * 특징:
 *   - 실 서비스 수준의 latency 시뮬레이션 (ci-check: 5~30ms, handoff: 20~80ms)
 *   - 초당 TPS 콘솔 출력
 *   - 고성능 Node.js HTTP (연결 keepalive 지원)
 */

const http = require('http');
const crypto = require('crypto');

const PORT = 8099;

// ── Latency 시뮬레이션 설정 ─────────────────────────────────────────────────
const LATENCY = {
  health:        { min: 1,  max: 3   },  // ms
  ciCheck:       { min: 5,  max: 30  },  // ms
  handoffIssue:  { min: 20, max: 80  },  // ms
};

function delay(min, max) {
  return new Promise(resolve =>
    setTimeout(resolve, Math.floor(Math.random() * (max - min + 1)) + min)
  );
}

// ── TPS 실시간 모니터 ────────────────────────────────────────────────────────
let counts = { total: 0, window: 0 };
let windowStart = Date.now();
let peakTps = 0;
let tpsHistory = [];

setInterval(() => {
  const now = Date.now();
  const elapsed = (now - windowStart) / 1000;

  if (elapsed >= 1) {
    const tps = counts.window / elapsed;
    tpsHistory.push(parseFloat(tps.toFixed(1)));
    if (tpsHistory.length > 30) tpsHistory.shift();  // 최근 30초 유지
    if (tps > peakTps) peakTps = tps;

    const avg = tpsHistory.length > 0
      ? (tpsHistory.reduce((a, b) => a + b, 0) / tpsHistory.length).toFixed(1)
      : '0.0';

    process.stdout.write(
      `\r[TPS] 현재: ${tps.toFixed(1).padStart(7)} TPS | ` +
      `평균: ${avg.padStart(7)} TPS | ` +
      `피크: ${peakTps.toFixed(1).padStart(7)} TPS | ` +
      `총 요청: ${counts.total.toString().padStart(8)}    `
    );

    counts.window = 0;
    windowStart = now;
  }
}, 1000);

// ── 더미 ID 생성 ─────────────────────────────────────────────────────────────
function genTicketId() {
  return 'TKT-' + crypto.randomBytes(8).toString('hex').toUpperCase();
}

// ── 요청 본문 파싱 ───────────────────────────────────────────────────────────
function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
      catch (_) { resolve({}); }
    });
    req.on('error', () => resolve({}));
  });
}

// ── JSON 응답 전송 ───────────────────────────────────────────────────────────
function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type':   'application/json',
    'Content-Length': Buffer.byteLength(json),
    'Connection':     'keep-alive',
  });
  res.end(json);
}

// ── HTTP 서버 ────────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  counts.total++;
  counts.window++;

  const { method, url } = req;

  // Health
  if (method === 'GET' && url === '/actuator/health') {
    await delay(LATENCY.health.min, LATENCY.health.max);
    return send(res, 200, { status: 'UP' });
  }

  // CI-Check
  if (method === 'POST' && url === '/api/v1/auth/nice/ci-check') {
    await delay(LATENCY.ciCheck.min, LATENCY.ciCheck.max);
    const body = await readBody(req);

    if (!body.ci || body.ci.length < 10) {
      return send(res, 200, {
        resultCode: '4000',
        resultMsg:  'CI 값이 유효하지 않습니다.',
        data:       null,
      });
    }

    return send(res, 200, {
      resultCode: '2000',
      resultMsg:  'OK',
      data: {
        ciVerified:  true,
        mbrDvsnCd:   body.mbrDvsnCd || 'A101',
        indvlMbrNm:  body.indvlMbrNm || '홍길동',
      },
    });
  }

  // Handoff Issue
  if (method === 'POST' && url === '/api/v1/handoff/issue') {
    await delay(LATENCY.handoffIssue.min, LATENCY.handoffIssue.max);
    const body = await readBody(req);

    if (!body.agencyCode || !body.agencySubjectId) {
      return send(res, 200, {
        resultCode: '4000',
        resultMsg:  'agencyCode, agencySubjectId는 필수입니다.',
        data:       null,
      });
    }

    return send(res, 200, {
      resultCode: '2000',
      resultMsg:  'OK',
      data: {
        ticketId:   genTicketId(),
        agencyCode: body.agencyCode,
        expiredAt:  new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      },
    });
  }

  // 404
  send(res, 404, { resultCode: '4040', resultMsg: 'Not Found' });
});

// keepAlive 설정
server.keepAliveTimeout = 65000;
server.headersTimeout   = 66000;

server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n✅ Mock 서버 기동: http://0.0.0.0:${PORT}`);
  console.log('   GET  /actuator/health');
  console.log('   POST /api/v1/auth/nice/ci-check');
  console.log('   POST /api/v1/handoff/issue\n');
});
