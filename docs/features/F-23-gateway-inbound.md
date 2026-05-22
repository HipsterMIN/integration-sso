# F-23: Agency Gateway 인바운드 API (Sprint 15)

> **환경변수**: `IDO_GATEWAY_INBOUND_ENABLED`  
> **Phase**: Phase 3-A (Gate 2 통과 후 활성화)  
> **기본값**: `false` (안전)  
> **엔드포인트**: `POST /api/v1/agency/gateway/inbound/event`  
> **소스**: `ido/src/main/java/kr/go/smes/ido/gateway/`

---

## 1. 이 기능은 무엇인가?

유관기관(Agency)이 OnePass 플랫폼에 이벤트를 전송하는 **인바운드 API**입니다.

```
유관기관 서버                          OnePass (ido)
     │                                      │
     │  POST /api/v1/agency/gateway/inbound/event
     │  X-Agency-Code: MOIS
     │  X-Agency-Key: {sha256 key}
     │  X-Idempotency-Key: uuid-v7
     │  X-Event-Type: USER_ACTION
     │  Body: {"action": "login", "userId": "..."}
     │ ──────────────────────────────────────→
     │                               ① API Key 검증 (HandoffAgencyKeyInterceptor)
     │                               ② Redis 멱등성 체크 (F-25)
     │                               ③ DB ON CONFLICT DO NOTHING
     │                               ④ 이벤트 라우팅 (routeInboundEvent)
     │  202 Accepted                        │
     │ ←──────────────────────────────────────
```

---

## 2. 보안 계층

### 2-1. X-Agency-Code + X-Agency-Key 검증

```java
// HandoffAgencyKeyInterceptor.java
// 기관 코드 조회 → API Key를 SHA-256 해시 후 상수시간 비교
MessageDigest.isEqual(
    storedHashBytes,     // DB에 저장된 SHA-256 해시
    MessageDigest.getInstance("SHA-256").digest(submittedKey.getBytes())
);
// 상수시간 비교 → 타이밍 공격 방지
```

### 2-2. 이중 멱등성 방어 (F-25)

```
1차 방어: Redis SET NX
  key: gateway:inbound:idempotent:{idempotency_key}
  TTL: 24시간
  → 같은 key로 24시간 내 재요청 → 즉시 409 반환

2차 방어: DB UNIQUE 제약
  table: gateway_inbound_audit
  UNIQUE(idempotency_key)
  → Redis 장애 시에도 DB에서 중복 차단
  → ON CONFLICT DO NOTHING으로 멱등 삽입
```

### 2-3. F-26 HMAC 서명 (현재 선택적, Phase 4에서 필수화)

```bash
# X-Internal-Sig 헤더 형식:
# HMAC-SHA256("POST\n/api/v1/...\n{timestampMs}\n{sha256(body)}")
```

---

## 3. F-23이 false일 때 동작

```bash
# false 상태에서 POST 요청
curl -X POST https://onepass.go.kr/api/v1/agency/gateway/inbound/event \
  -H "X-Agency-Code: MOIS" -H "X-Agency-Key: key" ...

# 응답: 503 Service Unavailable
{
  "error":   "FEATURE_DISABLED",
  "message": "Gateway 인바운드 API가 현재 비활성화 상태입니다. (IDO_GATEWAY_INBOUND_ENABLED=false)",
  "phase":   "Phase 3-A 진입 후 활성화 예정"
}
```

---

## 4. Phase 3-A 전환 절차

### Gate 2 체크리스트 확인 후

```bash
# 1단계: gateway_inbound_audit 테이블 존재 확인
psql -c "SELECT count(*) FROM ido.gateway_inbound_audit;"
# → 오류 없이 실행되면 OK

# 2단계: Redis 연결 확인
redis-cli -h $REDIS_HOST ping
# → PONG

# 3단계: 테스트 기관 API Key 등록 확인
psql -c "
  SELECT agency_code, endpoint_type, is_active
  FROM ido.agency_endpoint_registry
  WHERE agency_code = 'TEST_AGENCY';"

# 4단계: Phase 3-A ConfigMap 적용
kubectl apply -f infra/k8s/configmaps/ido-configmap-phase3a.yml
kubectl rollout restart deployment/ido -n smes

# 5단계: 기능 활성화 확인
curl -s http://localhost:8083/actuator/features | jq '.features["F-23_gatewayInbound"]'
# 예상: {"enabled": true, "env": "IDO_GATEWAY_INBOUND_ENABLED", ...}
```

### 테스트 시나리오

```bash
IDEMPOTENCY_KEY=$(uuidgen)

# 정상 요청 (202 Accepted 예상)
curl -X POST https://onepass.go.kr/api/v1/agency/gateway/inbound/event \
  -H "X-Agency-Code: TEST_AGENCY" \
  -H "X-Agency-Key: {발급된-키}" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H "X-Event-Type: USER_ACTION" \
  -H "Content-Type: application/json" \
  -d '{"test": true}'

# 중복 요청 (409 Conflict 예상 — 정상 동작)
curl -X POST https://onepass.go.kr/api/v1/agency/gateway/inbound/event \
  -H "X-Agency-Code: TEST_AGENCY" \
  -H "X-Agency-Key: {발급된-키}" \
  -H "X-Idempotency-Key: $IDEMPOTENCY_KEY" \  # ← 같은 키
  -H "X-Event-Type: USER_ACTION" \
  -H "Content-Type: application/json" \
  -d '{"test": true}'

# DB 확인
psql -c "
  SELECT idempotency_key, agency_code, event_type, status, created_at
  FROM ido.gateway_inbound_audit
  ORDER BY created_at DESC LIMIT 5;"
```

---

## 5. 멱등성 충돌 409 — 정상인가요?

네, **의도된 동작**입니다.

```java
// GatewayResponse.isIdempotencyConflict() 사용 예
GatewayResponse response = client.sendInbound(event);

if (response.isIdempotencyConflict()) {
    // 이미 처리된 이벤트 → 무시해도 됨
    log.info("이미 처리된 이벤트: {}", event.getIdempotencyKey());
    return; // 오류로 처리하지 않음
} else if (!response.isSuccess()) {
    // 진짜 오류 → 처리 필요
    throw new RuntimeException("Gateway 오류: " + response.getHttpStatus());
}
```

---

## 6. SDK 연동 예시 (Java 8+)

```java
// onepass-agency-sdk 사용
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://onepass.go.kr")
    .apiKey("your-api-key")
    .agencyCode("MOIS")
    .build();

InboundEvent event = InboundEvent.builder()
    .eventType("USER_ACTION")
    .idempotencyKey(IdempotencyKeyGenerator.generateWithPrefix("MOIS"))
    .agencyCode("MOIS")
    .payloadJson("{\"action\": \"login\"}")
    .build();

GatewayResponse response = client.sendInbound(event);
```

---

## 연관 문서
- [F-24 아웃바운드](F-24-gateway-outbound.md)
- [F-25 멱등성 방어](F-25-gateway-idempotency.md)
- [F-26 HMAC 서명](F-26-hmac-sig.md)
- [배포 가이드 §4 유관기관 연동](../_archive/2026-05-22/deployment-guide.md)
