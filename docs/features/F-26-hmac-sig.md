# F-26: HMAC-SHA256 서명 필수화 (Sprint 17 구현 완료)

> **환경변수**: `IDEM_HUB_HMAC_SIG_REQUIRED`  
> **Phase**: Phase 4 (모든 기관 준비 완료 후 `true` 전환)  
> **기본값**: `false` (Phase 1~3: 소프트 검증 — 헤더 있으면 검증, 없으면 통과)  
> **헤더**: `X-Internal-Sig`  
> **관련 클래스**:
> - `HmacSignatureFilter.java` — 인바운드 서명 검증 필터 (Sprint 17 신규)
> - `AgencyHmacKeyStore.java` — 기관별 HMAC 키 저장소 (Sprint 17 신규)
> - `AgencyGatewayServiceImpl.java` — 아웃바운드 서명 생성

---

## 1. Sprint 17 구현 내용

Sprint 17에서 F-26 관련 인프라를 완성했습니다.

### 구현된 컴포넌트

| 파일 | 역할 |
|------|------|
| `HmacSignatureFilter.java` | `POST /inbound/event` 전용 OncePerRequestFilter |
| `AgencyHmacKeyStore.java` | K8s Secret 기반 기관별 HMAC 키 관리 |
| `AgencyGatewayServiceImpl.buildOutboundHmacSig()` | 아웃바운드 발송 시 서명 생성 |

### 현재 (F-26=false, Phase 1~3)

```
기관 → POST /inbound/event
  ├─ X-Internal-Sig 있음 → 검증 시도 (있는 것만 검사)
  └─ X-Internal-Sig 없음 → 통과 (경고 로그만)
```

### Phase 4 이후 (F-26=true)

```
기관 → POST /inbound/event
  ├─ X-Internal-Sig 없음      → 401 MISSING_HMAC_SIGNATURE
  ├─ 기관 키 미등록           → 401 HMAC_KEY_NOT_FOUND
  ├─ 서명 불일치              → 401 INVALID_HMAC_SIGNATURE
  └─ 서명 유효 (±60초 내)    → 202 Accepted (처리 계속)
```

---

## 2. 서명 페이로드 규칙

```
payload = "{agencyCode}:{idempotencyKey}:{epochSeconds}"
sig     = HMAC-SHA256(payload, sharedSecret) → 소문자 HEX 64자
```

> **epochSeconds**: Unix 타임스탬프(초). 서버는 ±60초 범위를 허용합니다.  
> 시계 편차가 60초를 초과하면 서명 검증이 실패합니다.

### 서명 생성 예시 (Java)

```java
// 서비스 기관 측에서 X-Internal-Sig 헤더 생성
String agencyCode     = "AGENCY_001";
String idempotencyKey = "01914bf9-1234-7000-0000-000000000001";
long   epochSeconds   = Instant.now().getEpochSecond();

String payload = agencyCode + ":" + idempotencyKey + ":" + epochSeconds;
// → "AGENCY_001:01914bf9-1234-7000-0000-000000000001:1715000000"

Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(sharedSecret.getBytes(UTF_8), "HmacSHA256"));
String sig = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(UTF_8)));
// → "a3f9c2e1b4d8..." (64자 HEX)

// HTTP 헤더 설정
headers.set("X-Internal-Sig", sig);
```

### 서명 생성 예시 (Python)

```python
import hmac, hashlib, time

agency_code     = "AGENCY_001"
idempotency_key = "01914bf9-1234-7000-0000-000000000001"
epoch_seconds   = int(time.time())
shared_secret   = b"your-32-char-secret-minimum!!!!!"

payload = f"{agency_code}:{idempotency_key}:{epoch_seconds}".encode()
sig = hmac.new(shared_secret, payload, hashlib.sha256).hexdigest()
# 헤더: X-Internal-Sig: {sig}
```

---

## 3. 기관별 HMAC 키 관리 (AgencyHmacKeyStore)

### K8s Secret 등록

```bash
# 기관별 독립 HMAC 키 Secret 생성
# 환경변수 명명 규칙: IDEM_HUB_GATEWAY_HMAC_KEY_{기관코드 대문자}
kubectl create secret generic ido-gateway-hmac-keys \
  --from-literal=AGENCY_001=$(openssl rand -base64 48) \
  --from-literal=AGENCY_002=$(openssl rand -base64 48) \
  --from-literal=MOIS=$(openssl rand -base64 48) \
  -n production

# 등록된 기관 확인
kubectl get secret ido-gateway-hmac-keys -n production \
  -o jsonpath='{.data}' | jq 'keys'
```

### 키 로테이션 (무중단)

```bash
# 1. 새 키로 Secret 업데이트 (기존 키 + 새 키 동시 유효 기간 권장: 1주)
kubectl patch secret ido-gateway-hmac-keys \
  --patch='{"stringData":{"AGENCY_001":"new-secret-min-32chars-abcde12345"}}' \
  -n production

# 2. Pod 재시작 (Stakater Reloader 없는 경우)
kubectl rollout restart deployment/ido -n production

# 3. Actuator로 기관 키 로드 상태 확인
curl -s http://ido-svc:8083/actuator/features | jq '.features.F-26_hmacSigRequired'

# 4. 기관에 새 키 전달 → 기관 측 업데이트 → 기존 키 삭제
kubectl patch secret ido-gateway-hmac-keys \
  --patch='{"data":{"AGENCY_001":null}}' \
  -n production
```

### 키 보안 요구사항

| 항목 | 요구사항 |
|------|---------|
| 최소 길이 | 32자 (256비트) 이상 |
| 생성 방법 | `openssl rand -base64 48` |
| 공유 방법 | 암호화된 채널(Vault, 인편, 암호화 이메일) |
| 저장 위치 | K8s Secret만 허용 — 코드/환경변수 파일 금지 |
| 로테이션 주기 | 6개월 (보안사고 발생 시 즉시) |

---

## 4. 왜 상수시간 비교인가?

```java
// ❌ 잘못된 방법 — 타이밍 공격 취약
if (expected.equals(received)) { ... }
// 앞 글자가 같을수록 비교 시간이 조금 더 걸림
// → 공격자가 수천 번 측정하여 올바른 서명 바이트를 한 자리씩 추측 가능

// ✅ 올바른 방법 (HmacSignatureFilter.java 적용)
MessageDigest.isEqual(expected.getBytes(UTF_8), received.getBytes(UTF_8));
// 배열 길이가 달라도 항상 같은 시간 소요 → 타이밍 공격 불가
```

---

## 5. ±60초 전수 검사 이유

서명 페이로드에 epochSeconds가 포함되지만, 수신 측은 발신 측이 사용한 정확한
epochSeconds를 알 수 없습니다. 따라서 ±60초 범위의 모든 후보를 재계산합니다.

```
현재 서버 시각: 1715001000
검사 범위: 1714999940 ~ 1715001060 (총 121개 후보)
→ 하나라도 일치 → 통과
→ 모두 불일치 → 401
```

이 방식은 다음을 허용합니다:
- 네트워크 지연 (수십 초)
- 기관 서버 ↔ IdO 서버 시계 편차 (±30초 이내 권장)

---

## 6. Phase 4 전환 절차

### 전환 전 체크리스트

```
□ 모든 연동 기관 SDK 버전 ≥ 1.3.0 업그레이드 완료
□ 기관별 HMAC 키 K8s Secret 등록 완료
  → kubectl get secret ido-gateway-hmac-keys -n production
□ 각 기관 Staging 환경 X-Internal-Sig 서명 검증 48시간 통과
□ k6 HMAC 검증 부하 테스트 SLO 통과
  → k6 run test/load/k6-hmac-verification.js
□ /actuator/features 에서 등록 기관 수 확인
  → curl http://ido-svc/actuator/features | jq '.features.F-26_hmacSigRequired'
□ 보안팀 검토 승인
□ 롤백 담당자 및 롤백 절차 확인
```

### Helm으로 Phase 4 전환

```bash
# Phase 4 전환 (HMAC 서명 필수화)
helm upgrade ido ./infra/helm/idem-hub \
  -n production \
  -f infra/helm/idem-hub/values-prod.yaml \
  --set phase=4

# 또는 개별 플래그 지정
helm upgrade ido ./infra/helm/idem-hub \
  -n production \
  -f infra/helm/idem-hub/values-prod.yaml \
  --set featureFlags.hmacSigRequired=true

# 전환 후 5분 이내 401 급증 여부 모니터링
watch -n 10 'kubectl logs -l app=ido -n production --tail=50 | grep "HMAC\|401"'
```

### kubectl로 빠른 전환 (임시)

```bash
# F-26 활성화
kubectl set env deployment/ido -n production IDEM_HUB_HMAC_SIG_REQUIRED=true
kubectl rollout restart deployment/ido -n production

# 서명 없이 요청 시 401 확인
curl -s -o /dev/null -w "%{http_code}" \
  -X POST https://ido.smes.go.kr/api/v1/agency/gateway/inbound/event \
  -H "X-Agency-Code: AGENCY_001" \
  -H "X-Agency-Key: {key}" \
  -H "Content-Type: application/json" \
  -d '{"event_type":"USER_REGISTERED"}'
# 예상 응답: 401
```

---

## 7. 긴급 롤백

기관 연동 장애 발생 시 즉시 비활성화:

```bash
# 즉시 롤백 (kubectl)
kubectl set env deployment/ido -n production IDEM_HUB_HMAC_SIG_REQUIRED=false
# → Pod 재시작 없이 약 30초 내 적용

# Helm 롤백
helm upgrade ido ./infra/helm/idem-hub -n production \
  -f infra/helm/idem-hub/values-prod.yaml \
  --set featureFlags.hmacSigRequired=false

# 이전 Helm 릴리스로 전체 롤백
helm rollback ido -n production
```

---

## 8. 모니터링 쿼리

```sql
-- 최근 1시간 HMAC 검증 실패 기관별 집계
SELECT
  request_headers->>'X-Agency-Code' AS agency_code,
  COUNT(*)                            AS fail_count,
  MAX(requested_at)                   AS last_failure
FROM ido.audit_log
WHERE error_code IN ('MISSING_HMAC_SIGNATURE', 'INVALID_HMAC_SIGNATURE', 'HMAC_KEY_NOT_FOUND')
  AND requested_at > NOW() - INTERVAL '1 hour'
GROUP BY 1
ORDER BY 2 DESC;

-- 기관별 서명 검증 성공/실패 비율 (7일)
SELECT
  agency_code,
  SUM(CASE WHEN error_code IS NULL THEN 1 ELSE 0 END) AS success,
  SUM(CASE WHEN error_code LIKE 'HMAC%' THEN 1 ELSE 0 END) AS hmac_fail,
  ROUND(100.0 * SUM(CASE WHEN error_code LIKE 'HMAC%' THEN 1 ELSE 0 END)
        / NULLIF(COUNT(*), 0), 2) AS fail_pct
FROM ido.gateway_inbound_audit
WHERE received_at > NOW() - INTERVAL '7 days'
GROUP BY 1
ORDER BY hmac_fail DESC;
```

---

## 9. 에러 코드 표

| error 값 | HTTP | 발생 조건 | 조치 |
|----------|------|-----------|------|
| `MISSING_HMAC_SIGNATURE` | 401 | F-26=true인데 X-Internal-Sig 헤더 없음 | SDK 버전 업그레이드 |
| `MISSING_AGENCY_CODE` | 401 | X-Agency-Code 헤더 없음 | 헤더 추가 |
| `HMAC_KEY_NOT_FOUND` | 401 | 해당 기관 HMAC 키 미등록 | K8s Secret에 키 추가 |
| `INVALID_HMAC_SIGNATURE` | 401 | 서명 불일치 또는 ±60초 초과 | 시계 동기화, 키 확인 |
| `HMAC_COMPUTE_ERROR` | 401 | 서버 내부 HMAC 계산 오류 | 서버 로그 확인 |

---

## 연관 문서

- [F-23 인바운드 API](F-23-gateway-inbound.md)
- [F-25 멱등성 방어](F-25-gateway-idempotency.md)
- [phased-rollout-strategy.md](../phased-rollout-strategy.md) — Phase 4 진입 기준
- [deployment-guide.md §7 보안](../_archive/2026-05-22/deployment-guide.md)
- SDK 서명 구현: `idem-sdk-java/.../HmacSigner.java`
- k6 HMAC 부하 테스트: `test/load/k6-hmac-verification.js`
