# F-12: Handoff AES 키 로테이션 스케줄러

> **환경변수**: `IDO_CRYPTO_ROTATION_ENABLED`  
> **기본값**: `true`  
> **Spring 프로퍼티**: `ido.crypto.rotation-enabled`  
> **소스**: `ido/src/main/java/kr/go/smes/ido/crypto/CastKeyRotationScheduler.java`  
> **연관 설정**: `ido/src/main/java/kr/go/smes/ido/crypto/CastKeyConfig.java`

---

## 1. 이 기능은 무엇인가?

OnePass ↔ 기관 간 **Handoff 토큰 암호화에 사용하는 AES-256 키**를 **90일 주기로 자동 로테이션**합니다.  
키 로테이션은 무중단으로 진행되며, 구버전 키로 암호화된 Handoff 토큰은 `grace-period` 동안 계속 복호화 가능합니다.

```
Day  0: KeyA 생성 → 신규 토큰 암호화에 KeyA 사용
Day 90: KeyB 생성 → 신규 토큰은 KeyB로 암호화
         KeyA는 grace-period(14일) 동안 복호화에만 사용
Day104: KeyA 폐기
```

---

## 2. 동작 원리

```java
// CastKeyRotationScheduler.java
@Component
@ConditionalOnProperty("ido.crypto.rotation-enabled")  // F-12=false → 스케줄러 미등록
@RequiredArgsConstructor
public class CastKeyRotationScheduler {

    private final CastKeyConfig castKeyConfig;
    private final FeatureFlags featureFlags;

    // 매일 00:05 UTC 실행 (K8s 다중 Pod → Redisson 분산 락으로 단일 실행 보장)
    @Scheduled(cron = "0 5 0 * * *")
    public void rotateIfDue() {
        if (!featureFlags.isCryptoRotation()) return;

        LocalDate lastRotated = castKeyConfig.getLastRotatedDate();
        if (ChronoUnit.DAYS.between(lastRotated, LocalDate.now()) >= 90) {
            castKeyConfig.rotate();   // 신규 키 생성, 구버전 키 grace-period 등록
            log.info("[F-12] Handoff AES 키 로테이션 완료: keyId={}", castKeyConfig.getCurrentKeyId());
        }
    }
}
```

---

## 3. 키 관리 구조

```
CastKeyConfig
  ├─ currentKey: AES-256 (현재 암호화에 사용)
  ├─ previousKeys: List<CastKey> (복호화 전용, grace-period 내)
  └─ keyStore: K8s Secret "ido-cast-keys"
```

**K8s Secret 구조**:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ido-cast-keys
data:
  current-key-id: "key-2025-01-15"
  key-2025-01-15: "<base64-AES-256>"
  key-2024-10-17: "<base64-AES-256>"  # grace-period 중
```

---

## 4. 로테이션 절차 (자동)

```
1. CastKeyRotationScheduler 트리거 (90일 경과)
2. Redisson 분산 락 획득 (다중 Pod 중 하나만 실행)
3. 새 AES-256 키 생성 (SecureRandom)
4. K8s Secret 업데이트 (kubectl patch 또는 K8s API)
5. CastKeyConfig.refresh() 호출 (메모리 캐시 갱신)
6. 감사 로그 기록 (key-id, rotation-timestamp)
7. 분산 락 해제
```

---

## 5. 수동 로테이션 (긴급 키 폐기 시)

키 유출이 의심될 때 즉시 로테이션:
```bash
# 1. 즉시 새 키 생성 (Actuator 엔드포인트)
curl -X POST http://localhost:8083/actuator/crypto/rotate \
  -H "Content-Type: application/json" \
  -d '{"reason": "EMERGENCY_ROTATION", "gracePeriodDays": 0}'

# 2. 또는 환경변수 직접 변경 후 Pod 재시작
kubectl create secret generic ido-cast-keys \
  --from-literal=current-key-id="key-emergency-$(date +%Y%m%d)" \
  --from-literal="key-emergency-$(date +%Y%m%d)=$(openssl rand -base64 32)" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart deployment/ido-gateway
```

---

## 6. false 설정 가능한 경우

**수동 키 관리** 정책을 사용하는 경우에만 false 허용:
```bash
IDO_CRYPTO_ROTATION_ENABLED=false  # 수동으로 K8s Secret 업데이트
```

> ⚠️ **false 상태에서 90일 이상 경과 시, 키 로테이션을 수동으로 수행해야 합니다.**  
> 키 미로테이션 기간을 모니터링하는 알림 설정을 권장합니다.

---

## 7. 모니터링

```sql
-- 마지막 키 로테이션 시점 확인
SELECT created_at, extra_json
FROM ido.audit_log
WHERE event_type = 'CRYPTO_KEY_ROTATED'
ORDER BY created_at DESC
LIMIT 5;
```

```bash
# 현재 키 ID 및 만료까지 남은 일수 확인
curl http://localhost:8083/actuator/crypto/status | jq '{
  currentKeyId: .currentKeyId,
  rotatedAt: .lastRotatedAt,
  daysUntilNextRotation: .daysUntilNextRotation
}'
```

---

## 연관 기능

| 기능 | 관계 |
|------|------|
| [F-08 Redisson 분산 락](F-08-redisson-lock.md) | 다중 Pod 환경에서 단일 로테이션 보장 |
| [F-04 감사 DB](F-04-audit-db.md) | 로테이션 이벤트 감사 로그 기록 |
| [F-26 HMAC 서명](F-26-hmac-sig.md) | HMAC 키 관리와 유사 패턴 (별개 키) |

---

## 연관 문서
- [FeatureFlags.java](../../ido/src/main/java/kr/go/smes/ido/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
