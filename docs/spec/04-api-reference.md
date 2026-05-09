# 04. REST API 레퍼런스 (전체)

> **기준 버전**: v1.9.3 / 커밋 `46b1fe9`  
> **최종 갱신**: 2026-05-09

---

## 1. 공통 규약

### 1.1 기본 URL

| 모듈 | 로컬 기본 URL |
|------|-------------|
| IdO (FE BFF + 오케스트레이터) | `http://localhost:8083` |
| Q-Sign (인증 SoR) | `http://localhost:8081` |
| Q-IM (식별 SoR) | `http://localhost:8082` |
| agency-stub (기관 시뮬레이터) | `http://localhost:8084` |

### 1.2 공통 응답 형식

```json
// 성공
{
  "code": "SUCCESS",
  "data": { ... }
}

// 실패
{
  "code": "E-IDO-101",
  "message": "Handoff Ticket이 만료되었습니다.",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### 1.3 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `Content-Type: application/json` | ✅ | |
| `X-Correlation-Id` | 권장 | 없으면 서버가 UUID 생성 |
| `traceparent` | 선택 | W3C Trace Context |

---

## 2. IdO API (포트 8083)

### 2.1 FE 세션 API

#### POST /api/v1/fe-session/create

인증 완료 후 FE 세션 생성.

| 항목 | 내용 |
|------|------|
| 인증 | 없음 (Q-Sign → IdO 내부 호출) |

**요청 본문**:
```json
{
  "authResultId": "string",
  "correlationId": "string",
  "returnUrl": "https://example-agency.go.kr/callback"
}
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "feSessionId": "uuid",
    "returnUrl": "https://example-agency.go.kr/callback",
    "expiresAt": "2026-05-09T01:00:00Z"
  }
}
```

---

#### GET /api/v1/fe-session/validate

FE 세션 유효성 검증.

| 항목 | 내용 |
|------|------|
| 인증 | `feSessionId` 쿠키 |

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "valid": true,
    "expiresAt": "2026-05-09T01:00:00Z"
  }
}
```

---

### 2.2 Handoff API (기관 연동)

#### POST /api/v1/handoff/issue

Handoff Ticket 발급.

| 항목 | 내용 |
|------|------|
| 인증 | `X-Agency-Code` + `X-Agency-Key` (SHA-256 검증) |

**요청 헤더**:
```
X-Agency-Code: AGENCY_STUB_001
X-Agency-Key:  <raw-api-key>
X-Correlation-Id: <uuid>
Idempotency-Key: <uuid>
```

**요청 본문**:
```json
{
  "feSessionId": "string",
  "agencyCode": "AGENCY_STUB_001",
  "callbackUrl": "https://agency.go.kr/callback",
  "requestedAuthLevel": "L1"
}
```

**응답 200**:
```json
{
  "code": "SUCCESS",
  "data": {
    "ticketId": "uuid",
    "expiresAt": "2026-05-09T00:01:00Z"
  }
}
```

**에러 응답**:

| 상황 | 코드 | HTTP |
|------|------|------|
| 비활성 기관 | `E-AGENCY-301` | 403 |
| Rate Limit 초과 | `E-AGENCY-306` | 429 |
| 콜백 URL 위반 | `E-AGENCY-302` | 403 |
| 점검 시간 | `E-AGENCY-305` | 503 |
| 인증 수준 미달 | `E-AGENCY-304` | 403 |
| 사용자 정지 | `E-IM-202` | 403 |
| 사용자 탈퇴 | `E-IM-203` | 410 |

---

#### POST /api/v1/handoff/verify

Handoff Ticket 검증 및 사용자 정보 반환.

| 항목 | 내용 |
|------|------|
| 인증 | `X-Agency-Code` + `X-Agency-Key` |

**요청 본문**:
```json
{
  "ticketId": "uuid",
  "agencyCode": "AGENCY_STUB_001"
}
```

**응답 200 (APPROVED)**:
```json
{
  "code": "SUCCESS",
  "data": {
    "status": "APPROVED",
    "subject": {
      "qimUserId": "uuid",
      "agencySubjectId": "base64url",
      "identifierHash": "sha256hex",
      "instMbrId": "uuid"
    },
    "authContext": {
      "authResultId": "uuid",
      "authLevel": "L2",
      "authMethod": "PASS",
      "providerCode": "PASS",
      "providerType": "NON_STANDARD",
      "authenticatedAt": "2026-05-09T00:00:00Z",
      "expiresAt": "2026-05-09T01:00:00Z"
    },
    "policyInfo": {
      "policyVersion": "1.0",
      "agencyCode": "AGENCY_STUB_001",
      "needsSync": false,
      "syncReason": null
    },
    "traceContext": {
      "correlationId": "uuid",
      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
      "feSessionId": "uuid"
    }
  }
}
```

**에러 응답**:

| 상황 | 코드 | HTTP |
|------|------|------|
| Ticket 만료 | `E-IDO-101` | 410 |
| 이미 소비됨 | `E-IDO-102` | 409 |
| 재사용 시도 | `E-IDO-103` | 409 |
| 기관 불일치 | `E-IDO-104` | 403 |

---

### 2.3 기관 이벤트 폴링 API (v1.9.3)

#### GET /api/v1/agency/events

| 항목 | 내용 |
|------|------|
| 인증 | `X-Agency-Code` + `X-Agency-Key` |

**쿼리 파라미터**:

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `limit` | 선택 | 1~100, 기본 20 |
| `eventType` | 선택 | `HANDOFF_ISSUED`, `USER_UPDATED` 등 |
| `since` | 선택 | ISO-8601 커서 (`created_at > ?` 조건) |

**응답 200**:
```json
{
  "events": [
    {
      "dispatchId": "uuid",
      "eventType": "HANDOFF_ISSUED",
      "payload": { "ticketId": "uuid", "agencyCode": "..." },
      "status": "PENDING",
      "createdAt": "2026-05-09T00:00:00Z",
      "dispatchedAt": null
    }
  ],
  "count": 1,
  "hasMore": false,
  "polledAt": "2026-05-09T01:00:00Z",
  "queryInfo": { "limit": 20, "since": null, "eventType": null }
}
```

---

#### POST /api/v1/agency/events/{dispatchId}/read

이벤트 읽음 처리.

| 항목 | 내용 |
|------|------|
| 인증 | `X-Agency-Code` + `X-Agency-Key` |
| 응답 | 204 (성공) / 404 (없거나 권한 없음) |

---

### 2.4 Admin API

#### POST /api/v1/admin/agencies

기관 등록.

| 항목 | 내용 |
|------|------|
| 인증 | `X-Admin-Id` 헤더 |

**요청 본문**:
```json
{
  "agencyCode": "NEW_AGENCY_001",
  "officialName": "새 기관",
  "minAuthLevel": "L1",
  "callbackWhitelist": ["https://new-agency.go.kr/callback"],
  "integrationType": "DIRECT"
}
```

기타 Admin API:
- `GET /api/v1/admin/agencies` — 기관 목록
- `GET /api/v1/admin/agencies/{agencyCode}` — 기관 상세
- `PUT /api/v1/admin/agencies/{agencyCode}` — 기관 수정
- `POST /api/v1/admin/agencies/{agencyCode}/activate` — 활성화
- `POST /api/v1/admin/agencies/{agencyCode}/deactivate` — 비활성화
- `POST /api/v1/admin/agencies/{agencyCode}/rotate-key` — API 키 로테이션
- `GET /api/v1/admin/agencies/{agencyCode}/history` — 변경 이력
- `GET /api/v1/admin/agencies/{agencyCode}/stats` — 연동 통계

---

## 3. Q-Sign API (포트 8081)

### POST /api/v1/broker/authorize

OIDC 인증 시작 (사용자 브라우저 → Q-Sign).

**쿼리 파라미터**:

| 파라미터 | 설명 |
|---------|------|
| `providerCode` | `KAKAO_OIDC`, `NAVER_OIDC`, `PASS_OIDC` 등 |
| `returnUrl` | 인증 완료 후 리다이렉트 URL |
| `correlationId` | 추적 ID |

**응답**: Keycloak 인증 URL로 302 Redirect

---

### GET /api/v1/oidc/keycloak/callback

Keycloak OIDC 콜백 처리 (Keycloak → Q-Sign).

| 항목 | 내용 |
|------|------|
| Query | `code`, `state` |

---

## 4. Q-IM API (포트 8082)

### POST /api/qim/sp/v1/member/register

SP 회원 등록.

**요청 본문**:
```json
{
  "instMbrId": "uuid",
  "encCi": "v1:<base64-encrypted-ci>",
  "agencyCode": "AGENCY_STUB_001",
  "name": "홍*동",
  "mobile": "010-****-5678"
}
```

### GET /api/qim/sp/v1/member/{instMbrId}

SP 회원 조회.

---

## 5. agency-stub API (포트 8084)

### POST /api/v1/simulator/run

3단계 E2E 전체 흐름 시뮬레이션.

**요청 본문**:
```json
{
  "qimUserId": "uuid",
  "authResultId": "uuid",
  "authLevel": "L2",
  "agencyCode": "AGENCY_STUB_001"
}
```

**응답 200**:
```json
{
  "step1_ticket": { "ticketId": "uuid", "expiresAt": "..." },
  "step2_payload": { "status": "APPROVED", "subject": { ... } },
  "step3_session": { "sessionId": "sha256-hash", "agsid": "..." },
  "totalDurationMs": 342
}
```

---

## 6. Actuator 엔드포인트

모든 서비스에서 공통 제공:

```
GET /actuator/health    # UP / DOWN / DEGRADED
GET /actuator/metrics   # Micrometer 메트릭
GET /actuator/flyway    # Flyway 마이그레이션 이력
GET /actuator/env       # 환경변수 (민감 정보 마스킹)
```

---

*다음 문서: [05-database-schema.md](05-database-schema.md)*
