/**
 * OnePass k6 테스트 전용 Mock 서버 (Node.js)
 * Port: 8099
 *
 * 지원 엔드포인트 (01~05 스크립트 전체):
 *   GET  /actuator/health
 *   GET  /api/v1/auth/nice/phone/url
 *   POST /api/v1/auth/nice/phone/result
 *   POST /api/v1/auth/nice/ci-check
 *   POST /api/v1/auth/oacx/access-info
 *   POST /api/v1/auth/oacx/easysign
 *   POST /api/v1/auth/callback
 *   POST /api/v1/handoff/issue
 *   POST /api/v1/handoff/verify
 */

const http = require('http');
const crypto = require('crypto');

const PORT = 8099;

// ── Latency 시뮬레이션 (실 서비스 수준) ────────────────────────────────────
const LATENCY = {
  health:          { min: 1,   max: 3   },
  ciCheck:         { min: 8,   max: 40  },  // 파라미터 검증
  handoffIssue:    { min: 25,  max: 90  },  // DB+Redis 모사
  handoffVerify:   { min: 15,  max: 60  },  // Redis 조회
  nicePhoneUrl:    { min: 30,  max: 120 },  // 외부 API 모사
  nicePhoneResult: { min: 20,  max: 80  },
  oacxAccessInfo:  { min: 40,  max: 150 },  // 외부 SDK 모사
  oacxEasysign:    { min: 10,  max: 35  },
  authCallback:    { min: 50,  max: 200 },
  default:         { min: 10,  max: 30  },
};

function delay(min, max) {
  return new Promise(r => setTimeout(r, Math.floor(Math.random() * (max - min + 1)) + min));
}

// ── TPS 실시간 모니터 ────────────────────────────────────────────────────────
const stats = { total: 0, window: 0, peak: 0, history: [] };
let wStart = Date.now();

setInterval(() => {
  const elapsed = (Date.now() - wStart) / 1000;
  if (elapsed >= 1) {
    const tps = stats.window / elapsed;
    if (tps > stats.peak) stats.peak = tps;
    stats.history.push(parseFloat(tps.toFixed(1)));
    if (stats.history.length > 60) stats.history.shift();
    const avg = stats.history.reduce((a, b) => a + b, 0) / stats.history.length;
    process.stdout.write(
      `\r[TPS] 현재: ${tps.toFixed(1).padStart(7)} | 평균: ${avg.toFixed(1).padStart(7)} | 피크: ${stats.peak.toFixed(1).padStart(7)} | 총: ${stats.total.toString().padStart(8)}    `
    );
    stats.window = 0;
    wStart = Date.now();
  }
}, 500);

// ── 헬퍼 ────────────────────────────────────────────────────────────────────
function genId(prefix) {
  return prefix + '-' + crypto.randomBytes(6).toString('hex').toUpperCase();
}

function readBody(req) {
  return new Promise(resolve => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
      catch (_) { resolve({}); }
    });
    req.on('error', () => resolve({}));
  });
}

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(json),
    'Connection': 'keep-alive',
  });
  res.end(json);
}

// ── 라우터 ───────────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  stats.total++;
  stats.window++;
  const { method, url } = req;
  const path = url.split('?')[0];

  // ① Health
  if (method === 'GET' && path === '/actuator/health') {
    await delay(...Object.values(LATENCY.health));
    return send(res, 200, { status: 'UP', groups: ['liveness', 'readiness'] });
  }

  // ② NICE 인증 URL 발급
  if (method === 'GET' && path === '/api/v1/auth/nice/phone/url') {
    await delay(...Object.values(LATENCY.nicePhoneUrl));
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: {
        niceToken: genId('NICE'),
        authUrl: `https://nice.checkplus.co.kr/CheckPlusSafeModel/checkplus.cb?m=service&token=${genId('T')}`,
        expiredAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      },
    });
  }

  // ③ NICE 인증 결과 조회
  if (method === 'POST' && path === '/api/v1/auth/nice/phone/result') {
    await delay(...Object.values(LATENCY.nicePhoneResult));
    const body = await readBody(req);
    if (!body.requestNo && !body.webTransactionId) {
      return send(res, 200, { resultCode: '4000', resultMsg: 'requestNo는 필수입니다.', data: null });
    }
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: { ci: 'CI_MOCK_' + crypto.randomBytes(40).toString('hex'), name: '홍길동', mobileNo: '010-****-1234' },
    });
  }

  // ④ CI 확인
  if (method === 'POST' && path === '/api/v1/auth/nice/ci-check') {
    await delay(...Object.values(LATENCY.ciCheck));
    const body = await readBody(req);
    if (!body.ci || body.ci.length < 10) {
      return send(res, 200, { resultCode: '4000', resultMsg: 'CI 값이 유효하지 않습니다.', data: null });
    }
    const validCodes = ['A101', 'A102', 'A103'];
    if (!validCodes.includes(body.mbrDvsnCd)) {
      return send(res, 200, { resultCode: '4000', resultMsg: `mbrDvsnCd가 올바르지 않습니다: ${body.mbrDvsnCd}`, data: null });
    }
    if (body.mbrDvsnCd === 'A102' && !body.bizNo) {
      return send(res, 200, { resultCode: '4000', resultMsg: '법인 회원은 bizNo 필수입니다.', data: null });
    }
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: { ciVerified: true, mbrDvsnCd: body.mbrDvsnCd, indvlMbrNm: body.indvlMbrNm || '홍길동' },
    });
  }

  // ⑤ OACX 접근정보 조회
  if (method === 'POST' && path === '/api/v1/auth/oacx/access-info') {
    await delay(...Object.values(LATENCY.oacxAccessInfo));
    const body = await readBody(req);
    if (!body.agencyCode) {
      return send(res, 200, { resultCode: '4000', resultMsg: 'agencyCode는 필수입니다.', data: null });
    }
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: { accessToken: genId('OACX'), tokenType: 'Bearer', expiresIn: 3600 },
    });
  }

  // ⑥ OACX 간편서명
  if (method === 'POST' && path === '/api/v1/auth/oacx/easysign') {
    await delay(...Object.values(LATENCY.oacxEasysign));
    const body = await readBody(req);
    if (body.fn !== 'authComplete') {
      return send(res, 200, { resultCode: '4000', resultMsg: `fn이 올바르지 않습니다: ${body.fn}`, data: null });
    }
    if (!body.res || body.res.resultCode !== '200') {
      return send(res, 200, { resultCode: '4001', resultMsg: 'OACX 인증 실패', data: null });
    }
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: { signedData: genId('SIGN'), ci: 'CI_OACX_' + crypto.randomBytes(40).toString('hex') },
    });
  }

  // ⑦ Auth Callback
  if (method === 'POST' && path === '/api/v1/auth/callback') {
    await delay(...Object.values(LATENCY.authCallback));
    const body = await readBody(req);
    if (!body.code) {
      return send(res, 200, { resultCode: '4000', resultMsg: 'code는 필수입니다.', data: null });
    }
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: { accessToken: genId('AT'), refreshToken: genId('RT'), expiresIn: 3600 },
    });
  }

  // ⑧ Handoff Issue
  if (method === 'POST' && path === '/api/v1/handoff/issue') {
    await delay(...Object.values(LATENCY.handoffIssue));
    const body = await readBody(req);
    if (!body.agencyCode || !body.agencySubjectId) {
      return send(res, 200, { resultCode: '4000', resultMsg: 'agencyCode, agencySubjectId는 필수입니다.', data: null });
    }
    const ticketId = genId('TKT');
    // idempotency 간단 모사 (실제로는 Redis에서 관리)
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: {
        ticketId,
        agencyCode: body.agencyCode,
        returnUrl: body.returnUrl,
        expiredAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      },
    });
  }

  // ⑨ Handoff Verify
  if (method === 'POST' && path === '/api/v1/handoff/verify') {
    await delay(...Object.values(LATENCY.handoffVerify));
    const body = await readBody(req);
    if (!body.ticketId || !body.agencyCode) {
      return send(res, 200, { resultCode: '4000', resultMsg: 'ticketId, agencyCode는 필수입니다.', data: null });
    }
    // 만료 시뮬레이션: 랜덤 5%
    if (Math.random() < 0.05) {
      return send(res, 200, { resultCode: '4040', resultMsg: '티켓이 만료되었거나 존재하지 않습니다.', data: null });
    }
    return send(res, 200, {
      resultCode: '2000', resultMsg: 'OK',
      data: {
        ticketId: body.ticketId,
        agencyCode: body.agencyCode,
        authResult: { di: 'DI_VERIFIED_' + body.ticketId, name: '홍길동' },
        verifiedAt: new Date().toISOString(),
      },
    });
  }

  // 404
  send(res, 404, { resultCode: '4040', resultMsg: 'Not Found', path });
});

server.keepAliveTimeout = 65000;
server.headersTimeout   = 66000;

server.listen(PORT, '0.0.0.0', () => {
  console.log(`\n✅ OnePass Mock 서버 기동: http://0.0.0.0:${PORT}`);
  console.log('   지원 엔드포인트:');
  console.log('   GET  /actuator/health');
  console.log('   GET  /api/v1/auth/nice/phone/url');
  console.log('   POST /api/v1/auth/nice/phone/result');
  console.log('   POST /api/v1/auth/nice/ci-check');
  console.log('   POST /api/v1/auth/oacx/access-info');
  console.log('   POST /api/v1/auth/oacx/easysign');
  console.log('   POST /api/v1/auth/callback');
  console.log('   POST /api/v1/handoff/issue');
  console.log('   POST /api/v1/handoff/verify\n');
});
