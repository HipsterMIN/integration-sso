// idem-registry 스텁 — CI 스모크(k6) 전용. /api/v1/internal/users/register-subject 만 흉내 낸다.
// S4 부터 hub 의 /auth/providers/{code}/complete 가 registry 등록(register-subject)까지 마쳐야 200 을 돌려주므로,
// MariaDB·registry 를 띄우지 않는 스모크 잡에서는 이 스텁이 8082 를 맡는다. 실제 registry 계약은 idem-registry 통합 테스트가 검증한다.
'use strict';
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.REGISTRY_STUB_PORT || 8082);
const users = new Map(); // identifierHash|subjectKey → qimUserId

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    if (req.method === 'GET' && url.pathname === '/actuator/health') {
      return json(res, 200, { status: 'UP', stub: 'idem-registry' });
    }
    if (req.method === 'POST' && url.pathname === '/api/v1/internal/users/register-subject') {
      let body = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch (_) { return json(res, 400, { code: 'BAD_JSON' }); }
      const key = `${body.scheme || body.subjectScheme || 'CI'}|${body.identifierHash || body.subjectKey || body.rawCi || ''}`;
      const isNew = !users.has(key);
      if (isNew) users.set(key, crypto.randomUUID());
      const qimUserId = users.get(key);
      console.log(`[registry-stub] register-subject scheme=${body.scheme || body.subjectScheme} provider=${body.providerCode} isNew=${isNew} qimUserId=${qimUserId}`);
      return json(res, 200, { qimUserId, isNew, subjectScheme: body.scheme || body.subjectScheme || 'CI', status: 'ACTIVE' });
    }
    console.log(`[registry-stub] 404 ${req.method} ${url.pathname}`);
    return json(res, 404, { code: 'STUB_NOT_FOUND', path: url.pathname });
  });
});

server.listen(PORT, '0.0.0.0', () => console.log(`[registry-stub] listening on ${PORT}`));
