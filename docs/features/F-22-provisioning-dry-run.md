# F-22: 프로비저닝 Dry-Run 모드

> **환경변수**: `IDO_PROVISIONING_DRY_RUN`  
> **기본값**: `true` (안전 — 실제 HTTP 미발행)  
> **Spring 프로퍼티**: `ido.provisioning.dry-run`  
> **소스**: `idem-hub/src/main/java/kr/go/smes/idem-hub/provisioning/ProvisioningService.java`  
> **상위 플래그**: [F-20 전 기관 프로비저닝](F-20-provisioning.md)

---

## 1. 이 기능은 무엇인가?

F-20(전 기관 프로비저닝)의 **서브 플래그**로, 프로비저닝 실행 시 실제 HTTP POST를 발행하지 않고 **페이로드 생성 및 로그 출력만** 수행합니다.

```
F-20=true + F-22=true (Dry-Run 모드):
  ├─ 68개 기관 페이로드 생성 ✅
  ├─ 페이로드 로그 출력 ✅
  ├─ 실제 HTTP POST 미발행 ❌ (기관 서버 영향 없음)
  └─ provisioning_outbox INSERT ❌

F-20=true + F-22=false (실제 발행 모드):
  ├─ 68개 기관 페이로드 생성 ✅
  ├─ Virtual Thread로 병렬 HTTP POST ✅ (기관 서버에 실제 요청)
  └─ provisioning_outbox INSERT ✅
```

---

## 2. Phase-Gate 전환 순서

```
Phase 2-A: F-20=true + F-22=true
  → 2주간 Dry-Run 로그 관찰
  → 페이로드 형식, 기관 목록, 부하 추정 검증

Phase 2-B: F-20=true + F-22=false
  → 실제 HTTP 발행 시작
  → F-21(릴레이)도 함께 활성화 권장
```

```bash
# Phase 2-A: Dry-Run 관찰 모드
helm upgrade ido infra/helm/idem-hub --set phase=2a
# 또는
kubectl set env deployment/ido-gateway \
  IDO_PROVISIONING_ENABLED=true \
  IDO_PROVISIONING_DRY_RUN=true

# Phase 2-B: 실제 발행 전환 (2주 관찰 후)
helm upgrade ido infra/helm/idem-hub --set phase=2b
# 또는
kubectl set env deployment/ido-gateway \
  IDO_PROVISIONING_ENABLED=true \
  IDO_PROVISIONING_DRY_RUN=false \
  IDO_PROVISIONING_RELAY_ENABLED=true
```

---

## 3. Dry-Run 로그 형식

```
[Provisioning][DRY-RUN] agencyCode=AGCY001 | endpoint=https://agency001.example.go.kr/api/provisioning
  payload={
    "eventType": "MEMBER_PROVISIONED",
    "userId": "user-uuid",
    "agencyCode": "AGCY001",
    "timestamp": "2025-01-15T00:05:00Z"
  }
  → HTTP POST 미발행 (dry-run=true)

[Provisioning][DRY-RUN] agencyCode=AGCY002 | endpoint=https://agency002.example.go.kr/...
  → HTTP POST 미발행 (dry-run=true)
... (68개 기관 반복)

[Provisioning][DRY-RUN] 완료: 68개 기관 페이로드 생성, 실제 발행 없음
  소요시간: 42ms (Virtual Thread 병렬 페이로드 생성)
```

---

## 4. Dry-Run 체크리스트

Phase 2-A (Dry-Run) → Phase 2-B (실제 발행) 전환 전 확인:

- [ ] **기관 목록 검증**: Dry-Run 로그에서 68개 기관 모두 페이로드 생성 확인
- [ ] **페이로드 형식**: 각 기관의 엔드포인트·페이로드 형식 담당자 확인
- [ ] **부하 추정**: 로그의 소요시간으로 실제 HTTP 발행 시 예상 부하 계산
- [ ] **기관 서버 준비**: 68개 기관 Webhook 수신 서버 준비 완료 확인
- [ ] **F-21 릴레이 준비**: `IDO_PROVISIONING_RELAY_ENABLED=true` 설정 준비
- [ ] **롤백 계획**: `IDO_PROVISIONING_DRY_RUN=true` 복원 절차 팀 공유
- [ ] **법무팀/보안팀 승인**: 개인정보 전송 동의 확인

---

## 5. 동작 원리

```java
// ProvisioningService.java
public void provision(ProvisioningEvent event) {
    List<AgencyEndpoint> agencies = agencyMetaRepository.findAll();  // 68개

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        agencies.forEach(agency -> executor.submit(() -> {
            String payload = buildPayload(event, agency);

            if (featureFlags.isProvisioningDryRun()) {
                // F-22=true: 로그만
                log.info("[Provisioning][DRY-RUN] agencyCode={} payload={}",
                         agency.getAgencyCode(), payload);
                return;
            }

            // F-22=false: 실제 HTTP POST
            restTemplate.postForEntity(agency.getEndpoint(), payload, Void.class);
            provisioningOutboxRepository.save(/* ... */);
        }));
    }
}
```

---

## 6. FeatureFlags 경고 로그

```
[FeatureFlags] ℹ️ F-20+F-22: 프로비저닝 DRY-RUN 모드 — 로그만 출력, 실제 HTTP 미발행. 정상 Phase2-A 상태.
```

F-20=true + F-21=false + F-22=false 조합 시:
```
[FeatureFlags] ⚠️ F-20 ON + F-21 OFF: 프로비저닝 실패 시 재시도 릴레이 없음. IDO_PROVISIONING_RELAY_ENABLED=true 권장.
```

---

## 7. 연관 기능

| 기능 | 관계 |
|------|------|
| [F-20 전 기관 프로비저닝](F-20-provisioning.md) | 상위 플래그 — F-20=false면 F-22 무의미 |
| [F-21 Provisioning 릴레이](F-21-provisioning-relay.md) | F-22=false 전환 시 동시 활성화 권장 |
| [F-04 감사 DB](F-04-audit-db.md) | Dry-Run 이벤트도 audit_log에 기록 |

---

## 연관 문서
- [FeatureFlags.java](../../idem-hub/src/main/java/kr/go/smes/idem-hub/config/FeatureFlags.java)
- [Phase-Gate 배포 전략](../phased-rollout-strategy.md)
- [Helm Phase 프리셋]( ../../infra/helm/idem-hub/templates/_helpers.tpl)
