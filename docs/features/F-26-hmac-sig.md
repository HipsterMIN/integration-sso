# F-26: HMAC-SHA256 서명 필수화 (Sprint 17 예정)

> **환경변수**: `IDO_HMAC_SIG_REQUIRED`  
> **Phase**: Phase 4 (Sprint 17, Gate 3 통과 후)  
> **기본값**: `false` (현재: 선택적 검증)  
> **헤더**: `X-Internal-Sig`  
> **소스**: `onepass-agency-sdk/src/main/java/kr/go/smes/sdk/agency/security/HmacSigner.java`

---

## 1. 현재 상태 vs 완성 상태

### 현재 (Sprint 16, F-26=false)
```
기관 → OnePass: X-Internal-Sig 헤더가 있으면 검증, 없어도 통과
```

### Phase 4 이후 (F-26=true)
```
기관 → OnePass: X-Internal-Sig 헤더 없으면 401 Unauthorized
                X-Internal-Sig 검증 실패해도 401 Unauthorized
```

---

## 2. HMAC-SHA256 서명이란?

API Key 외에 **요청 내용 자체를 서명**하여 위·변조를 방지합니다.

```
서명 생성 (기관 측):
  서명 대상 = "POST\n/api/v1/agency/gateway/inbound/event\n{timestampMs}\n{sha256(body)}"
  X-Internal-Sig = HMAC-SHA256(서명 대상, sharedSecret) → HEX 64자

검증 (OnePass 측):
  같은 방식으로 서명 재생성 → MessageDigest.isEqual() 비교 (상수시간)
```

### 왜 상수시간 비교인가?

```java
// ❌ 잘못된 방법
if (expectedSig.equals(actualSig)) { ... }
// 문제: 앞 글자가 같을수록 비교 시간이 길어짐
// → 공격자가 비교 시간을 측정해 서명을 추측 가능 (타이밍 공격)

// ✅ 올바른 방법 (HmacSigner.java)
MessageDigest.isEqual(expectedSig.getBytes(), actualSig.getBytes());
// 항상 같은 시간 소요 → 타이밍 공격 불가
```

---

## 3. SDK에서 서명 활성화

```java
// onepass-agency-sdk 사용
AgencyGatewayClient client = AgencyGatewayClient.builder()
    .baseUrl("https://onepass.go.kr")
    .apiKey("your-api-key")
    .agencyCode("MOIS")
    .hmacSecret("your-shared-secret")  // ← HMAC 비밀키 설정
    .signRequests(true)                // ← 서명 활성화
    .build();

// 이후 모든 요청에 X-Internal-Sig 헤더 자동 추가
client.sendInbound(event);
```

---

## 4. 서명 생성 (SDK 미사용 시 직접 구현)

```java
// HmacSigner.java 참조
public String sign(String httpMethod, String path, long timestampMs, String requestBody) {
    String bodyHash = sha256Hex(requestBody);
    String message = httpMethod + "\n" + path + "\n" + timestampMs + "\n" + bodyHash;

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secretKeyBytes, "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
}
```

---

## 5. Phase 4 전환 전 체크리스트

> **이 플래그를 true로 변경하기 전에 반드시 확인하세요:**

```
□ 모든 연동 기관이 X-Internal-Sig 헤더 포함 배포 완료
□ 각 기관에 shared secret 안전하게 전달 완료
□ 기관별 서명 테스트 성공 확인
□ 보안팀 검토 완료
□ 롤백 계획 수립 (문제 시 즉시 false로 복귀)
```

### 전환 후 즉시 확인

```bash
# 서명 없이 요청 → 401 Unauthorized 확인
curl -X POST https://onepass.go.kr/api/v1/agency/gateway/inbound/event \
  -H "X-Agency-Code: MOIS" -H "X-Agency-Key: {key}" \
  # X-Internal-Sig 없음
  -d '{}'
# 예상: 401 Unauthorized

# 서명 있는 요청 → 202 Accepted 확인
# (SDK 또는 직접 구현으로 X-Internal-Sig 포함)
```

---

## 6. 긴급 완화

기관 연동 장애 발생 시 즉시 완화:

```bash
kubectl set env deployment/ido -n smes IDO_HMAC_SIG_REQUIRED=false
kubectl rollout restart deployment/ido -n smes
```

---

## 연관 문서
- [F-23 인바운드 API](F-23-gateway-inbound.md)
- [배포 가이드 §7 보안 요구사항](../deployment-guide.md)
- [HmacSigner 소스](../../onepass-agency-sdk/src/main/java/kr/go/smes/sdk/agency/security/HmacSigner.java)
