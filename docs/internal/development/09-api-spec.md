# 09. REST API 명세 (API Specification)

> **문서 버전**: v1.9.0  
> **최종 수정**: 2026-05-09

---

## 1. 공통 규약

### 1.1 기본 URL

| 모듈 | 기본 URL |
|------|----------|
| IdO | `http://localhost:8083` |
| Q-Sign | `http://localhost:8081` |
| Q-IM | `http://localhost:8082` |
| agency-stub | `http://localhost:8084` |

### 1.2 공통 응답 형식

```json
// 성공
{
  "code": "SUCCESS",
  "data": { ... }
}

// 실패
{
  "code": "ERROR_CODE",
  "message": "오류 메시지",
  "traceId": "correlationId"
}
```

### 1.3 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `Content-Type: application/json` | ✅ | JSON 요청 |
| `X-Correlation-Id` | 권장 | 추적 ID (없으면 서버 생성) |
| `traceparent` | 선택 | W3C Trace Context |

---

## 2. IdO API (port 8083)

### 2.1 Handoff API (기관 연동)

#### POST /api/v1/handoff/issue

**설명**: Handoff Ticket 발급

**인증**: FeSession 기반 (쿠키 또는 Authorization 헤더)

**요청**:
```json
{
  "feSessionId": "string",
  "agencyCode": "string",
  "redirectUri": "string (optional)"
}
```

**응답**:
```json
{
  "ticketId": "uuid",
  "expiresAt": "2026-05-09T12:00:00Z",
  "redirectUri": "https://agency.example.com/callback?ticketId=..."
}
```

#### POST /api/v1/handoff/verify

**설명**: Handoff Ticket 검증 및 소비 (기관이 호출)

**인증**: `X-Agency-Code` + `X-Agency-Key` 헤더

**요청**:
```json
{
  "ticketId": "uuid",
  "agencyCode": "string"
}
```

**응답**:
```json
{
  "agencySubjectId": "HMAC_BASE64URL",
  "attributes": {
    "name": "홍*동",
    "phone": "010-****-5678",
    "authLevel": "2",
    "providerCode": "kakao"
  },
  "policyVersion": "1.0",
  "issuedAt": "2026-05-09T11:59:00Z"
}
```

**오류 코드**:
| 코드 | HTTP | 설명 |
|------|------|------|
| `TICKET_NOT_FOUND` | 404 | 티켓 없음 |
| `TICKET_EXPIRED` | 410 | 티켓 만료 |
| `TICKET_ALREADY_CONSUMED` | 409 | 이미 소비된 티켓 |
| `AGENCY_NOT_AUTHORIZED` | 403 | 기관 인증 실패 |

#### DELETE /api/v1/handoff/{ticketId}

**설명**: Handoff Ticket 취소 (긴급 revoke)

**인증**: `X-Agency-Code` + `X-Agency-Key` 헤더

---

### 2.2 Broker API (인증 흐름)

#### POST /api/v1/broker/init

**설명**: 인증 흐름 시작 (ProviderType 기반 라우팅)

**요청**:
```json
{
  "providerCode": "kakao",
  "correlationId": "uuid",
  "returnUrl": "https://onepass.go.kr/callback"
}
```

**응답**:
```json
{
  "redirectUrl": "https://keycloak.../auth?...",
  "correlationId": "uuid",
  "brokerRoute": "KEYCLOAK_RELAY"
}
```

#### GET /api/v1/oidc/keycloak/callback (IdO 직접 수신 모드)

**설명**: Keycloak OIDC 콜백 수신

**파라미터**:
| 파라미터 | 설명 |
|---------|------|
| `code` | Authorization Code |
| `state` | CSRF State |

---

### 2.3 FE Session API

#### POST /api/v1/fe-session

**설명**: FE 세션 생성

**요청**:
```json
{
  "correlationId": "uuid",
  "qimUserId": "uuid",
  "providerCode": "kakao",
  "authLevel": "2"
}
```

**응답**:
```json
{
  "sessionId": "uuid",
  "expiresAt": "2026-05-09T20:00:00Z"
}
```

#### GET /api/v1/fe-session/{sessionId}

**설명**: FE 세션 조회

#### DELETE /api/v1/fe-session/{sessionId}

**설명**: FE 세션 무효화 (로그아웃)

---

### 2.4 Admin API

**인증**: `X-Admin-Token` 헤더 + IP 화이트리스트

| Method | 경로 | 설명 |
|--------|------|------|
| POST | `/admin/v1/agencies` | 신규 기관 등록 |
| GET | `/admin/v1/agencies` | 기관 목록 조회 (페이징) |
| GET | `/admin/v1/agencies/{code}` | 기관 상세 조회 |
| PUT | `/admin/v1/agencies/{code}` | 기관 정보 수정 |
| POST | `/admin/v1/agencies/{code}/activate` | 기관 활성화 |
| POST | `/admin/v1/agencies/{code}/deactivate` | 기관 비활성화 |
| POST | `/admin/v1/agencies/{code}/rotate-key` | API Key 로테이션 |

**기관 등록 요청 (POST /admin/v1/agencies)**:
```json
{
  "agencyCode": "AGENCY_001",
  "agencyName": "테스트 기관",
  "integrationType": "DIRECT",
  "allowedAttributes": ["name", "phone", "authLevel"],
  "callbackWhitelist": ["https://agency.example.com"],
  "webhookEnabled": true,
  "webhookEndpoint": "https://agency.example.com/webhook",
  "dailyLookupLimit": 10000
}
```

---

### 2.5 Q-IM SP 수신 API (Q-IM → IdO)

**인증**: `X-API-Key` 헤더 (Q-IM 발급 키)

| Method | 경로 | 설명 |
|--------|------|------|
| POST | `/api/qim/sp/v1/member/query` | 회원 조회 수신 |
| POST | `/api/qim/sp/v1/member/register` | 회원 등록 수신 |
| POST | `/api/qim/sp/v1/member/withdraw` | 회원 탈퇴 수신 |

**회원 등록 수신 요청**:
```json
{
  "mbrNo": "QIM-20260507-000001",
  "mbrUuid": "uuid",
  "regMode": "NEW",
  "encCi": "AES암호화된CI",
  "mbrNm": "홍길동",
  "phone": "01012345678"
}
```

**회원 등록 수신 응답**:
```json
{
  "success": true,
  "data": {
    "instMbrId": "uuid",
    "registeredAt": "2026-05-09T12:00:00+09:00"
  },
  "message": "등록 완료"
}
```

---

### 2.6 Member Lookup API

#### POST /api/v1/member-lookup

**설명**: 기관 회원 조회 (CI/DI 해시 기반)

**인증**: `X-Agency-Code` + `X-Agency-Key`

**요청**:
```json
{
  "identifierHash": "sha256hex...",
  "agencyCode": "AGENCY_001"
}
```

---

### 2.7 Agency Events API (Webhook 대안 폴링)

#### GET /api/v1/agency/events

**설명**: 기관이 주기적으로 이벤트를 폴링

**인증**: `X-Agency-Key`

**파라미터**:
| 파라미터 | 설명 |
|---------|------|
| `since` | ISO-8601 타임스탬프 (이후 이벤트 조회) |
| `agencyCode` | 기관 코드 |

**응답**:
```json
{
  "events": [
    {
      "type": "TICKET_REVOKED",
      "ticketId": "uuid",
      "occurredAt": "2026-05-09T12:00:00Z"
    },
    {
      "type": "USER_ADVISORY",
      "qimUserId": "uuid",
      "advisoryType": "MANDATORY_SECURITY_TERMINATE",
      "occurredAt": "2026-05-09T12:01:00Z"
    }
  ]
}
```

---

## 3. Q-Sign API (port 8081)

### 3.1 OIDC 인증 API

| Method | 경로 | 설명 |
|--------|------|------|
| GET | `/api/v1/oidc/{provider}/auth-url` | 인가 URL 생성 |
| GET | `/api/v1/oidc/keycloak/callback` | Keycloak 콜백 |

#### GET /api/v1/oidc/{provider}/auth-url

**파라미터**:
| 파라미터 | 설명 |
|---------|------|
| `correlationId` | 상관관계 ID |
| `returnUrl` | 완료 후 리디렉션 URL |

**응답**:
```json
{
  "authorizationUrl": "https://keycloak.../auth?client_id=...&code_challenge=...&state=...",
  "state": "random-state-value"
}
```

### 3.2 내부 인증 API

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/api/v1/auth/from-ido` | IdO로부터 인증 입력 | X-Internal-Sig |

---

## 4. Q-IM API (port 8082)

### 4.1 사용자 조회 API

| Method | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/api/v1/users/{qimUserId}` | 사용자 상세 조회 | X-Internal-Api-Key |
| GET | `/api/v1/users/by-hash` | 해시 기반 조회 | X-Internal-Api-Key |
| GET | `/api/v1/users/{qimUserId}/di` | DI 조회/생성 | X-Internal-Api-Key |

### 4.2 외부 연동 API (IdO → Q-IM)

| Method | 경로 | 설명 |
|--------|------|------|
| POST | `/api/ext/v1/member/register` | 회원 등록 |
| POST | `/api/ext/v1/member/query` | 회원 조회 |
| POST | `/api/ext/v1/member/withdraw` | 회원 탈퇴 |
| GET | `/api/ext/v1/member/sync` | 상태 동기화 |

---

## 5. 오류 코드 목록

| 코드 | HTTP | 설명 |
|------|------|------|
| `E-IDP-401` | 401 | IdP 인증 실패 |
| `E-IDP-404` | 404 | Provider Registry 미등록 |
| `E-IDO-101` | 400 | 잘못된 Handoff 요청 |
| `E-IDO-106` | 503 | Q-IM 연결 불가 |
| `E-OPS-901` | 503 | 외부 사업자 장애 (Retry-After 포함) |
| `QS-003` | 429 | Rate Limit 초과 |
| `SP_AUTH_INVALID` | 401 | SP API Key 인증 실패 |
| `SP_DECRYPT_FAILED` | 422 | AES 복호화 실패 |
| `SP_MEMBER_NOT_FOUND` | 404 | 회원 없음 |

---

## 6. Webhook 이벤트 명세

IdO → 유관기관 Webhook (HTTPS POST)

**헤더**:
```http
Content-Type: application/json
X-OnePass-Signature: HMAC-SHA256({payload}, hmacSecret)
X-Correlation-Id: {uuid}
```

**TICKET_REVOKED 이벤트**:
```json
{
  "eventType": "TICKET_REVOKED",
  "ticketId": "uuid",
  "agencyCode": "AGENCY_001",
  "revokedAt": "2026-05-09T12:00:00Z",
  "reason": "AUTH_LOCKED"
}
```

**USER_ADVISORY 이벤트**:
```json
{
  "eventType": "USER_ADVISORY",
  "qimUserId": "uuid",
  "advisoryType": "MANDATORY_SECURITY_TERMINATE",
  "occurredAt": "2026-05-09T12:00:00Z"
}
```

---

*다음 문서: [10-security.md](10-security.md)*
