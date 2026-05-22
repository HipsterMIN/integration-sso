# 중기원패스(OnePass) 유관기관 연동 SDK 가이드 v2.0

**문서 버전**: 2.0.0  
**작성일**: 2026-05-16  
**대상 독자**: 유관기관 개발자, 시스템 운영자  
**갱신 이력**: v1.x(이전 통합플랫폼) → v2.0(IdO 기반 표준화)

---

## 목차

1. [개요](#1-개요)
2. [연동 유형 선택](#2-연동-유형-선택)
3. [사전 준비](#3-사전-준비)
4. [DIRECT 패턴 — API 직접 연동](#4-direct-패턴--api-직접-연동)
5. [APACHE_GATE 패턴 — Apache mod_auth_openidc](#5-apache_gate-패턴--apache-mod_auth_openidc)
6. [BRIDGE 패턴 — Bridge 서버 배포](#6-bridge-패턴--bridge-서버-배포)
7. [INTERNAL_SSO 패턴 — 자체 SSO 보유 기관](#7-internal_sso-패턴--자체-sso-보유-기관)
8. [공통: 멱등성 처리](#8-공통-멱등성-처리)
9. [공통: 오류 코드](#9-공통-오류-코드)
10. [보안 체크리스트](#10-보안-체크리스트)

---

## 1. 개요

중기원패스(OnePass)는 중소벤처기업부 68개 유관기관의 통합 인증 허브입니다.  
사용자는 OnePass 단일 계정으로 모든 유관기관 서비스를 이용합니다.

### 1.1 핵심 개념

| 용어 | 설명 |
|------|------|
| **IdO** | Identity Orchestrator — OnePass 인증 허브 핵심 서버 |
| **Handoff** | 유관기관 → OnePass 전환 시 발급하는 일회성 인증 티켓(JWT) |
| **CI** | 연계정보 — 주민등록번호 대체 식별자 (AES-256-GCM 암호화 전송) |
| **agencyCode** | 기관 식별코드 (IdO 관리자에게 발급 요청) |
| **X-Agency-Key** | 기관 API 인증 키 (PBKDF2 해시 검증) |
| **provisioningToken** | 전환 완료 후 발급되는 자동 로그인 토큰 |

### 1.2 전환 흐름 요약

```
[유관기관 사용자]
       │ ① 전환 요청 (기관 포털에서 "OnePass 전환" 클릭)
       ▼
[유관기관 서버]
       │ ② POST /api/v1/handoff/issue → Handoff Ticket 발급
       │ ③ 사용자를 OnePass 전환 URL로 리다이렉트
       │    https://onepass.smes.go.kr/conversion?ticket={ticketId}
       ▼
[OnePass FE]
       │ ④ 전환 흐름 진행 (약관 동의 → 본인인증 → 계정 생성)
       │ ⑤ 전환 완료 → callbackUrl로 리다이렉트
       │    https://agency.go.kr/onepass/callback?onepass_token={token}
       ▼
[유관기관 서버]
       │ ⑥ POST /api/v1/handoff/verify → 전환 결과 검증
       ▼
[로그인 완료]
```

---

## 2. 연동 유형 선택

| 유형 | 적합한 기관 | 구현 복잡도 |
|------|------------|------------|
| **DIRECT** | 자체 백엔드 서버 보유 기관 | 중간 |
| **APACHE_GATE** | Apache 웹서버 사용 기관 | 낮음 |
| **BRIDGE** | 레거시 시스템, 별도 서버 배포 가능 기관 | 높음 |
| **INTERNAL_SSO** | 자체 SSO(OIDC/SAML) 운영 기관 | 높음 |

> **기관 코드 및 연동 유형 신청**: IdO 관리자 (onepass-admin@smes.go.kr) 에게 문의

---

## 3. 사전 준비

### 3.1 기관 정보 등록 (IdO 관리자 요청 사항)

관리자에게 다음 정보를 제공해야 합니다:

```json
{
  "agencyCode": "AGENCY_A",
  "officialName": "중소기업진흥공단",
  "integrationType": "DIRECT",
  "callbackWhitelist": [
    "https://portal.kotech.or.kr/onepass/callback",
    "https://dev-portal.kotech.or.kr/onepass/callback"
  ],
  "minAuthLevel": "L1",
  "allowedAttributes": ["name_masked", "mobile_masked"]
}
```

### 3.2 발급 항목

기관 등록 후 관리자로부터 다음을 발급받습니다:

| 항목 | 설명 | 보관 방법 |
|------|------|---------|
| `AGENCY_CODE` | 기관 식별코드 | 환경변수 |
| `AGENCY_KEY` | API 인증 키 (평문) | **K8s Secret 또는 Vault** |
| `IDO_HOST` | IdO 서버 호스트 | 환경변수 |

> ⚠️ **AGENCY_KEY를 소스코드, `.env` 파일, 버전관리 시스템에 포함하지 마십시오.**

### 3.3 네트워크 요구사항

| 방향 | 포트 | 설명 |
|------|------|------|
| 기관 서버 → IdO | HTTPS 443 | API 호출 |
| IdO → 기관 callbackUrl | HTTPS 443 | 콜백 전달 |
| IdO → 기관 webhookUrl (선택) | HTTPS 443 | 이벤트 알림 |

---

## 4. DIRECT 패턴 — API 직접 연동

### 4.1 Step 1: Handoff Ticket 발급

전환 시작 시 유관기관 서버가 IdO에 Handoff Ticket을 발급받습니다.

**Request**:
```http
POST {IDO_HOST}/api/v1/handoff/issue
Content-Type: application/json
X-Agency-Code: {AGENCY_CODE}
X-Agency-Key: {AGENCY_KEY}
Idempotency-Key: {UUID v4}      ← 재시도 안전성 보장 (필수 권장)
X-Correlation-Id: {UUID v4}     ← 로그 추적 (선택)

{
  "agencyCode": "{AGENCY_CODE}",
  "authResultId": "{사용자 기관 세션 ID}",
  "authLevel": "L1",
  "providerCode": "AGENCY_AUTH",
  "callbackUrl": "https://agency.go.kr/onepass/callback"
}
```

**Response** (200 OK):
```json
{
  "ticketId": "hdp-019e0bf7-3a2c-7e4d-8f9b-1234567890ab",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresAt": 1716090000,
  "issuedAt": 1716086400
}
```

**파라미터 설명**:

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `agencyCode` | ✅ | 발급받은 기관 식별코드 |
| `authResultId` | ✅ | 기관 내부 인증 결과 ID (로그 추적용) |
| `authLevel` | ✅ | 기관 측 인증 수준 (L1/L2/L3) |
| `providerCode` | ✅ | 기관 인증 수단 코드 (AGENCY_AUTH 권장) |
| `callbackUrl` | 선택 | 전환 완료 후 리다이렉트 URL (미입력 시 기관 등록 기본값 사용) |

### 4.2 Step 2: 사용자를 OnePass 전환 URL로 리다이렉트

```
https://onepass.smes.go.kr/conversion?ticket={ticketId}&userType={IND or ENT}
```

**파라미터**:

| 파라미터 | 필수 | 값 |
|---------|------|-----|
| `ticket` | ✅ | 4.1에서 발급한 `ticketId` |
| `userType` | 선택 | `IND`(개인), `ENT`(기업), 미입력 시 사용자가 선택 |

> ⚠️ **v1.x 호환성 주의**: 기존 `mbrId`, `return_client` URL 파라미터 방식은 **보안 취약** (평문 사용자 ID 노출)으로 v2.0에서 Ticket 방식으로 변경됩니다. 신규 연동은 반드시 Ticket 방식을 사용하십시오.

### 4.3 Step 3: 콜백 수신 및 전환 결과 검증

전환 완료 후 OnePass가 `callbackUrl`로 리다이렉트:

```
GET https://agency.go.kr/onepass/callback
  ?onepass_token={JWT}
  &state={state}          ← Step 1 발급 시 state 포함 시 그대로 반환
```

기관 서버는 `onepass_token`을 IdO에 검증 요청:

**Request**:
```http
POST {IDO_HOST}/api/v1/handoff/verify
Content-Type: application/json
X-Agency-Code: {AGENCY_CODE}
X-Correlation-Id: {UUID v4}

{
  "ticketId": "{ticketId}"
}
```

**Response** (200 OK):
```json
{
  "mbrId": "user-abc123",
  "agencyCode": "AGENCY_A",
  "authLevel": "L1",
  "providerCode": "NICE_PHONE",
  "policyVersion": "1.0",
  "attributes": {
    "name_masked": "홍*동",
    "mobile_masked": "010-****-5678"
  },
  "issuedAt": 1716086400,
  "expiresAt": 1716086700
}
```

> `attributes`는 기관 등록 시 `allowedAttributes`에 포함된 항목만 반환됩니다.

### 4.4 Java 예시 코드

```java
// IdOHandoffClient.java
@Service
public class IdOHandoffClient {
    
    private final RestTemplate restTemplate;
    private final String idoHost;
    private final String agencyCode;
    private final String agencyKey;  // K8s Secret에서 주입
    
    public HandoffTicket issueTicket(String authResultId, String callbackUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agency-Code", agencyCode);
        headers.set("X-Agency-Key", agencyKey);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, String> body = Map.of(
            "agencyCode", agencyCode,
            "authResultId", authResultId,
            "authLevel", "L1",
            "providerCode", "AGENCY_AUTH",
            "callbackUrl", callbackUrl
        );
        
        ResponseEntity<HandoffTicket> response = restTemplate.exchange(
            idoHost + "/api/v1/handoff/issue",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            HandoffTicket.class
        );
        
        return response.getBody();
    }
    
    public HandoffPayload verifyTicket(String ticketId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agency-Code", agencyCode);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, String> body = Map.of("ticketId", ticketId);
        
        ResponseEntity<HandoffPayload> response = restTemplate.exchange(
            idoHost + "/api/v1/handoff/verify",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            HandoffPayload.class
        );
        
        return response.getBody();
    }
}
```

---

## 5. APACHE_GATE 패턴 — Apache mod_auth_openidc

OnePass의 Keycloak(Q-Sign)을 OIDC IdP로 사용하는 Apache 설정입니다.

### 5.1 설치 요구사항

```bash
# Ubuntu/Debian
apt-get install libapache2-mod-auth-openidc
a2enmod auth_openidc

# RHEL/CentOS
yum install mod_auth_openidc
```

### 5.2 Apache 설정

```apache
# /etc/apache2/sites-available/agency-portal.conf

<VirtualHost *:443>
    ServerName portal.agency.go.kr

    # SSL 설정 (생략)

    # ── OnePass OIDC 설정 ──────────────────────────────────────────
    OIDCProviderMetadataURL https://onepass.smes.go.kr/realms/smeg/.well-known/openid-configuration
    OIDCClientID {KEYCLOAK_CLIENT_ID}          # IdO 관리자에게 발급 요청
    OIDCClientSecret {KEYCLOAK_CLIENT_SECRET}  # K8s Secret 또는 환경변수로 주입
    OIDCRedirectURI https://portal.agency.go.kr/onepass/callback
    OIDCCryptoPassphrase {RANDOM_32BYTE_HEX}   # 세션 암호화 키 (랜덤 생성)

    OIDCScope "openid profile"
    OIDCRemoteUserClaim preferred_username
    OIDCSessionType server-cache
    OIDCCacheType memcache
    OIDCMemCacheServers "localhost:11211"       # 클러스터 지원을 위해 Memcached 사용

    # 보호 경로 설정
    <Location /protected>
        AuthType openid-connect
        Require valid-user
    </Location>

    # 콜백 경로 (인증 없이 접근 허용)
    <Location /onepass/callback>
        AuthType none
        Require all granted
    </Location>

    # ── 로그아웃 설정 ──────────────────────────────────────────────
    <Location /logout>
        AuthType openid-connect
        Require valid-user
        OIDCUnAuthAction auth
    </Location>
</VirtualHost>
```

### 5.3 사용자 정보 접근

Apache 인증 후 환경변수로 사용자 정보 접근 가능:

```python
# Python/WSGI 예시
import os

def application(environ, start_response):
    # OnePass에서 전달된 사용자 정보
    user_id = environ.get('REMOTE_USER', '')         # mbrId
    user_name = environ.get('HTTP_OIDC_CLAIM_NAME', '')  # 이름
    
    # ...
```

---

## 6. BRIDGE 패턴 — Bridge 서버 배포

레거시 시스템이나 직접 OIDC 연동이 어려운 경우 사용합니다.

### 6.1 Bridge 서버 최소 사양

```
- 언어: Java 17+ / Node.js 18+ / Python 3.11+
- 세션 저장소: Redis (인메모리 금지)
- HTTPS: 필수 (유효한 인증서)
- 가용성: 99.9% 이상 (LoadBalancer 권장)
```

### 6.2 Bridge 필수 엔드포인트

```
GET  /bridge/login          → OnePass 전환 시작 (Handoff Issue → 리다이렉트)
GET  /bridge/callback       → OnePass 전환 완료 수신 (verify → 내부 세션 발급)
POST /bridge/logout         → 세션 종료
GET  /bridge/healthcheck    → 상태 확인 (200 OK 반환)
```

### 6.3 Node.js Bridge 예시

```javascript
// bridge-server.js
const express = require('express');
const axios = require('axios');
const { v4: uuidv4 } = require('uuid');
const redis = require('redis');

const app = express();
const redisClient = redis.createClient({ url: process.env.REDIS_URL });

const IDO_HOST = process.env.IDO_HOST;
const AGENCY_CODE = process.env.AGENCY_CODE;
const AGENCY_KEY = process.env.AGENCY_KEY;  // K8s Secret에서 주입

// Step 1: 전환 시작
app.get('/bridge/login', async (req, res) => {
    const idempotencyKey = uuidv4();
    
    try {
        const { data } = await axios.post(`${IDO_HOST}/api/v1/handoff/issue`, {
            agencyCode: AGENCY_CODE,
            authResultId: req.session?.userId || 'anonymous',
            authLevel: 'L1',
            providerCode: 'AGENCY_AUTH',
            callbackUrl: 'https://bridge.agency.go.kr/bridge/callback'
        }, {
            headers: {
                'X-Agency-Code': AGENCY_CODE,
                'X-Agency-Key': AGENCY_KEY,
                'Idempotency-Key': idempotencyKey
            }
        });
        
        // ticketId를 Redis에 저장 (state 연결용, TTL 10분)
        await redisClient.setEx(
            `bridge:ticket:${data.ticketId}`,
            600,
            JSON.stringify({ originalUrl: req.query.return_url || '/' })
        );
        
        res.redirect(`https://onepass.smes.go.kr/conversion?ticket=${data.ticketId}`);
    } catch (err) {
        res.status(500).json({ error: 'Handoff 발급 실패' });
    }
});

// Step 3: 전환 완료 콜백
app.get('/bridge/callback', async (req, res) => {
    const { ticket } = req.query;
    
    try {
        const { data } = await axios.post(`${IDO_HOST}/api/v1/handoff/verify`, {
            ticketId: ticket
        }, {
            headers: { 'X-Agency-Code': AGENCY_CODE }
        });
        
        // 내부 세션 발급
        req.session.onepassUser = {
            mbrId: data.mbrId,
            name: data.attributes?.name_masked
        };
        
        // 원래 페이지로 복귀
        const stored = await redisClient.get(`bridge:ticket:${ticket}`);
        const { originalUrl } = stored ? JSON.parse(stored) : { originalUrl: '/' };
        
        res.redirect(originalUrl);
    } catch (err) {
        res.status(401).json({ error: '인증 실패' });
    }
});

app.get('/bridge/healthcheck', (req, res) => res.json({ status: 'ok' }));
```

---

## 7. INTERNAL_SSO 패턴 — 자체 SSO 보유 기관

ADR-2026-004 참조. 자체 SSO를 Keycloak Identity Provider로 등록합니다.

### 7.1 기관 SSO 측 구현 요구사항

기관 SSO가 제공해야 하는 항목:

| 항목 | 설명 | 필수 여부 |
|------|------|---------|
| OIDC Discovery URL | `/.well-known/openid-configuration` | ✅ |
| Authorization Endpoint | 인증 요청 엔드포인트 | ✅ |
| Token Endpoint | 토큰 교환 엔드포인트 | ✅ |
| UserInfo Endpoint | 사용자 정보 조회 | ✅ |
| JWKS Endpoint | 공개키 엔드포인트 | ✅ |
| Back-channel Logout Endpoint | SLO 처리 | 강력 권장 |

### 7.2 Back-channel Logout 구현 (기관 SSO 측)

```java
// 기관 SSO 서버 — Back-channel Logout Endpoint
@PostMapping("/onepass/backchannel_logout")
public ResponseEntity<Void> backchannelLogout(
        @RequestParam("logout_token") String logoutToken) {
    
    // 1. logout_token 검증 (OnePass 공개키로 JWT 서명 검증)
    JwtClaims claims = verifyLogoutToken(logoutToken);
    
    // 2. "events" 클레임 확인
    if (!claims.hasClaim("http://schemas.openid.net/event/backchannel-logout")) {
        return ResponseEntity.badRequest().build();
    }
    
    // 3. 세션 종료
    String sessionId = claims.getStringClaimValue("sid");
    sessionStore.invalidate(sessionId);
    
    return ResponseEntity.ok().build();
}
```

---

## 8. 공통: 멱등성 처리

**네트워크 오류 시 재시도 안전성 보장**을 위해 `Idempotency-Key` 헤더를 사용합니다.

```java
// 재시도 예시 (Retry with Idempotency-Key)
String idempotencyKey = UUID.randomUUID().toString();

for (int attempt = 1; attempt <= 3; attempt++) {
    try {
        return issueHandoffWithKey(idempotencyKey);  // 동일 키 재사용
    } catch (HttpServerErrorException e) {
        if (attempt == 3) throw e;
        Thread.sleep(1000L * attempt);  // 지수 백오프
    }
}
```

**IdO 보장사항**:
- 동일 `Idempotency-Key`로 재요청 시 동일 `ticketId` 반환
- TTL: 24시간 (이후 새 키 사용)
- 티켓이 이미 소비된 경우: 새로 발급

---

## 9. 공통: 오류 코드

| HTTP | 오류 코드 | 의미 | 처리 방법 |
|------|----------|------|---------|
| 400 | `IDO_INVALID_REQUEST` | 요청 파라미터 오류 | 파라미터 확인 |
| 401 | `IDO_AGENCY_AUTH_FAILED` | X-Agency-Key 인증 실패 | AGENCY_KEY 확인 |
| 403 | `IDO_CALLBACK_MISMATCH` | callbackUrl이 화이트리스트에 없음 | 관리자에게 URL 등록 요청 |
| 404 | `IDO_TICKET_NOT_FOUND` | ticketId 없음 또는 만료 | 사용자에게 재시도 안내 |
| 409 | `IDO_TICKET_ALREADY_USED` | 이미 소비된 Ticket | 새 Ticket 발급 후 재시도 |
| 410 | `IDO_SESSION_NOT_FOUND` | 사용자 인증 세션 없음 | 사용자에게 재로그인 안내 |
| 422 | `IDO_AUTH_LEVEL_REQUIRED` | 기관 최소 인증 수준 미충족 | 더 높은 인증 수단 사용 안내 |
| 429 | `IDO_RATE_LIMIT_EXCEEDED` | API 호출 한도 초과 | 지수 백오프 후 재시도 |
| 503 | `IDO_MAINTENANCE` | 점검 시간 | 점검 종료 후 재시도 |

---

## 10. 보안 체크리스트

기관 개발자 필수 확인 사항:

```
✅ AGENCY_KEY를 소스코드/버전관리에 포함하지 않는다
✅ AGENCY_KEY를 K8s Secret, Vault, AWS Secrets Manager로 관리한다
✅ callbackUrl을 HTTPS로만 등록한다 (HTTP 불가)
✅ callbackUrl에서 onepass_token 수신 후 반드시 /verify로 검증한다
✅ Idempotency-Key를 사용하여 재시도 안전성을 확보한다
✅ 로그에 AGENCY_KEY, onepass_token을 기록하지 않는다
✅ X-Correlation-Id를 사용하여 IdO 팀과 로그 추적이 가능하도록 한다
✅ Bridge/DIRECT 패턴: 인메모리 세션 사용 금지 (Redis 사용)
✅ INTERNAL_SSO: Back-channel Logout 엔드포인트 구현
```

---

## 부록: 환경별 엔드포인트

| 환경 | IDO_HOST | OnePass FE |
|------|---------|-----------|
| 개발 | `https://ido-dev.smes.go.kr` | `https://onepass-dev.smes.go.kr` |
| 운영 | `https://ido.smes.go.kr` | `https://onepass.smes.go.kr` |

> 개발 환경 AGENCY_KEY는 별도 발급됩니다. 운영 환경 키와 혼용 금지.

---

**문의**: integration-sso 개발팀 (Slack #onepass-integration, onepass-admin@smes.go.kr)
