# F-24: Agency Gateway 아웃바운드 API (Sprint 15)

> **환경변수**: `IDO_GATEWAY_OUTBOUND_ENABLED`  
> **Phase**: Phase 3-B (F-23 인바운드 안정화 후 활성화)  
> **기본값**: `false` (안전)  
> **엔드포인트**: `PATCH /api/v1/agency/gateway/outbound/notify`  
> **소스**: `ido/src/main/java/kr/go/smes/ido/gateway/AgencyGatewayController.java`

---

## 1. 이 기능은 무엇인가?

OnePass가 유관기관에 이벤트를 **수동으로 발송**하는 아웃바운드 API입니다.

```
내부 서비스(운영 대시보드)          OnePass (ido)          유관기관 서버
        │                              │                         │
        │  PATCH /outbound/notify      │                         │
        │  Body: {agency_code, event}  │                         │
        │ ─────────────────────────→   │                         │
        │                              │ ① 엔드포인트 조회 (WEBHOOK)
        │                              │ ② HTTP POST → Webhook URL
        │                              │ ─────────────────────→  │
        │                              │           200 OK ←──────│
        │  200 OK {http_status: 200}   │                         │
        │ ←─────────────────────────── │                         │
```

> **주의**: 이 엔드포인트는 **내부 전용**입니다. 유관기관이 직접 호출하는 것이 아니라, OnePass 운영자나 내부 배치 작업이 호출합니다.

---

## 2. F-23과의 차이점

| | F-23 인바운드 | F-24 아웃바운드 |
|---|---|---|
| 방향 | 기관 → OnePass | OnePass → 기관 |
| 엔드포인트 | POST /inbound/event | PATCH /outbound/notify |
| 호출자 | 유관기관 | 내부 서비스 |
| 인증 | X-Agency-Key | 내부 토큰 (Sprint 17) |
| 활성화 Phase | Phase 3-A | Phase 3-B |

---

## 3. 아웃바운드 흐름

```java
// AgencyGatewayServiceImpl.sendOutbound()
// 1. Redis 멱등성 키 획득 (F-25)
// 2. agency_endpoint_registry에서 WEBHOOK 엔드포인트 조회
// 3. HTTP POST → 기관 Webhook URL
// 4. gateway_outbound_audit에 결과 기록 (payload_hash)
```

### payload_hash 목적

```java
// 전송 페이로드의 SHA-256 해시를 DB에 저장
// → 나중에 "어떤 데이터를 보냈는지" 감사 가능
// → 분쟁 발생 시 증거 자료
String payloadHash = sha256Hex(request.getPayloadJson());
outboundRepository.insert(agencyCode, eventType, idempotencyKey,
                           endpointUrl, httpStatus, payloadHash,
                           correlationId, delivered);
```

---

## 4. Phase 3-B 전환 절차

F-23 인바운드가 1주 이상 안정적으로 동작한 후:

```bash
# 아웃바운드 ConfigMap 적용
kubectl apply -f infra/k8s/configmaps/ido-configmap-phase3b.yml
kubectl rollout restart deployment/ido -n smes

# 기능 확인
curl -s http://localhost:8083/actuator/features | jq '.features["F-24_gatewayOutbound"]'

# 테스트: 내부에서 아웃바운드 발송
curl -X PATCH http://localhost:8083/api/v1/agency/gateway/outbound/notify \
  -H "Content-Type: application/json" \
  -d '{
    "agency_code": "TEST_AGENCY",
    "event_type": "NOTIFY_USER",
    "payload": "{\"message\": \"test\"}",
    "idempotency_key": "'$(uuidgen)'"
  }'
# 예상: 200 OK {"http_status": 200, ...}
```

---

## 5. 연동 상태 조회

```bash
# GET /api/v1/agency/gateway/status/{agencyCode}
curl https://onepass.go.kr/api/v1/agency/gateway/status/TEST_AGENCY

# 응답 예시:
{
  "agencyCode": "TEST_AGENCY",
  "agencyName": "테스트 기관",
  "active": true,
  "activeEndpoints": 2,
  "pendingProvisioning": 0,
  "deadLetterProvisioning": 0,
  "unprocessedInbound": 0,
  "lastInboundAt": "2026-05-14T10:30:00Z",
  "lastOutboundAt": "2026-05-14T10:31:00Z",
  "queriedAt": "2026-05-14T10:35:00Z"
}
```

---

## 연관 문서
- [F-23 인바운드](F-23-gateway-inbound.md)
- [F-25 멱등성 방어](F-25-gateway-idempotency.md)
