# F-20 / F-22: 전 기관 프로비저닝 (Sprint 14)

> **⚠️ 제거됨 (2026-09-10, 범용화 S4b)** — 전 기관 프로비저닝은 코어에서 삭제되었다. 플랫폼은 기관(Service)에 사용자를 등록·방송하지 않으며, 어설션·백채널 로그아웃·보안/감사 이벤트만 push 한다. 근거: `docs/generalization-plan.md` §1.2 C8 · §2.0 · §3 S4b. 아래 내용은 이력 참고용이다.


> **환경변수**: `IDO_PROVISIONING_ENABLED` / `IDO_PROVISIONING_DRY_RUN`  
> **Phase**: Phase 2 (Gate 1 통과 후 활성화)  
> **기본값**: `false` / `true` (안전)  
> **구현 Sprint**: Sprint 14  
> **소스**: `idem-hub/src/main/java/io/github/hipstermin/idem/idem-hub/provision/`

---

## 1. 이 기능은 무엇인가?

새로운 사용자가 OnePass에 가입할 때, **등록된 모든 유관기관(최대 68개)에 동시에** 가입 사실을 알리는 기능입니다.

```
사용자 가입 이벤트 발생
        ↓
Q-IM → Kafka 이벤트 발행
        ↓
ido Kafka Consumer 수신
        ↓
ProvisioningService.triggerProvisioning()
        ↓
[Virtual Thread Pool]
  ├─ 기관A HTTP POST → /provision → 200 OK → COMPLETED
  ├─ 기관B HTTP POST → /provision → 500 Error → PENDING (재시도 예약)
  ├─ 기관C HTTP POST → /provision → timeout  → PENDING (재시도 예약)
  └─ ...
```

---

## 2. 핵심 기술: Virtual Thread (JDK 21)

### 왜 Virtual Thread인가?

```java
// 기존 방식 (플랫폼 스레드) — 68개 동시 HTTP는 비용이 크다
ExecutorService pool = Executors.newFixedThreadPool(68);
// 각 스레드가 OS 스레드 1개를 점유 → 메모리 ~1MB × 68 = ~68MB

// Virtual Thread 방식 (JDK 21)
ExecutorService vThreadPool = Executors.newVirtualThreadPerTaskExecutor();
// 각 Virtual Thread는 ~수 KB → 68개 동시 HTTP도 문제없음
// HTTP I/O 대기 중 OS 스레드 반납 → 다른 Virtual Thread가 사용
```

### 실제 코드 흐름

```java
// ProvisioningServiceImpl.java (핵심 부분)

try (ExecutorService vThreadPool = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<ProvisioningResult>> futures = new ArrayList<>();

    for (AgencyEndpointRecord endpoint : targetEndpoints) {
        futures.add(vThreadPool.submit(
            () -> sendToAgency(endpoint, request, correlationId)
        ));
    }

    // 모든 기관의 응답을 기다린 후 결과 처리
    for (int i = 0; i < futures.size(); i++) {
        ProvisioningResult result = futures.get(i).get();
        // 성공: COMPLETED, 실패: PENDING (Outbox 재시도)
    }
}
```

---

## 3. F-22 Dry-Run 모드란?

### 왜 dry-run이 필요한가?

처음 `IDO_PROVISIONING_ENABLED=true`로 전환할 때, **실제 기관에 HTTP 요청을 보내지 않고** 어떤 기관에 어떤 데이터가 전송될지 미리 확인할 수 있습니다.

```
dry-run=true 일 때:
  페이로드 생성 ✅
  로그 출력     ✅
  HTTP 발행     ❌ (안 함)
  DB Outbox 기록 ❌ (안 함)

dry-run=false 일 때:
  페이로드 생성 ✅
  로그 출력     ✅
  HTTP 발행     ✅
  DB Outbox 기록 ✅
```

### dry-run 로그 확인 방법

```bash
# 로그에서 DRY-RUN 확인
kubectl logs -n smes deployment/ido | grep "DRY-RUN"

# 예상 출력:
# [Provisioning] DRY-RUN 모드 (IDO_PROVISIONING_DRY_RUN=true): 페이로드 생성 후 HTTP 미발행. qimUserId=xxx
# [Provisioning] DRY-RUN 완료: 대상 기관=3개, qimUserId=xxx, eventType=USER_REGISTERED. 실제 발행 없음.
# [Provisioning] DRY-RUN 대상: agencyCode=MOIS url=https://mois.go.kr/onepass/provision
# [Provisioning] DRY-RUN 대상: agencyCode=MSS url=https://mss.go.kr/onepass/provision
# [Provisioning] DRY-RUN 대상: agencyCode=KICO url=https://kico.or.kr/onepass/provision
```

---

## 4. Phase 2 전환 절차

### Phase 2-A: dry-run 관찰 (2주)

```bash
# 1단계: Gate 1 체크리스트 확인
#   □ provisioning_outbox 테이블 존재
#   □ agency_endpoint_registry에 테스트 기관 등록
#   □ agency-stub 서버 기동

# 2단계: Phase 2-A ConfigMap 적용
# (D3) infra/k8s 는 코어 저장소에서 제거됨 — 같은 값을 설치본 환경변수(install.env)로 준다. 종전: kubectl apply -f infra/k8s/configmaps/ido-configmap-phase2a.yml
kubectl rollout restart deployment/ido -n smes
kubectl rollout status deployment/ido -n smes

# 3단계: 기동 로그 확인
kubectl logs -n smes deployment/ido | grep "FeatureFlags"
# 예상: F-20 provisioning = true, F-22 provisioningDryRun = true

# 4단계: dry-run 로그 관찰 (사용자 가입 시 자동 트리거)
kubectl logs -n smes deployment/ido --follow | grep "DRY-RUN"
```

### Phase 2-B: 실제 발행

Gate 2-A 조건 충족 후:
- [ ] dry-run 로그에서 대상 기관 수 예상과 일치
- [ ] 페이로드 형식 기관 API 스펙 일치 확인
- [ ] 팀 전원 이해 확인

```bash
# Phase 2-B ConfigMap 적용
# (D3) infra/k8s 는 코어 저장소에서 제거됨 — 같은 값을 설치본 환경변수(install.env)로 준다. 종전: kubectl apply -f infra/k8s/configmaps/ido-configmap-phase2b.yml
kubectl rollout restart deployment/ido -n smes

# 발행 성공 확인
psql -h $DB_HOST -U onepass -d onepass -c "
  SELECT agency_code, status, count(*)
  FROM ido.provisioning_outbox
  WHERE created_at > now() - interval '1 hour'
  GROUP BY agency_code, status
  ORDER BY agency_code;"
```

---

## 5. 실패 시 동작: Outbox 패턴

HTTP POST가 실패하면 `provisioning_outbox` 테이블에 `PENDING` 상태로 기록됩니다.

### 지수 백오프 재시도

| 재시도 횟수 | 다음 시도 시간 |
|:----------:|:-------------:|
| 1차 실패 | 1분 후 |
| 2차 실패 | 5분 후 |
| 3차 실패 | 30분 후 |
| maxRetry 초과 | DEAD_LETTER (수동 처리 필요) |

```sql
-- 현재 PENDING/DEAD_LETTER 확인
SELECT agency_code, status, retry_count, last_error, next_retry_at
FROM ido.provisioning_outbox
WHERE status IN ('PENDING', 'DEAD_LETTER')
ORDER BY created_at DESC;
```

### FOR UPDATE SKIP LOCKED

여러 Pod가 동시에 PENDING을 처리할 때 중복 처리를 방지합니다.

```sql
-- ProvisioningOutboxRepositoryImpl 내부
SELECT * FROM ido.provisioning_outbox
WHERE status = 'PENDING'
  AND next_retry_at <= NOW()
ORDER BY next_retry_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED  -- 다른 Pod가 이미 처리 중인 행은 건너뜀
```

---

## 6. 긴급 중단

```bash
# 프로비저닝 즉시 중단 (재배포 없이)
kubectl set env deployment/ido -n smes \
  IDO_PROVISIONING_ENABLED=false \
  IDO_PROVISIONING_RELAY_ENABLED=false

# dry-run 모드로 일시 전환 (발행 중단, 로그만)
kubectl set env deployment/ido -n smes IDO_PROVISIONING_DRY_RUN=true

# Pod 재시작으로 설정 반영
kubectl rollout restart deployment/ido -n smes
```

---

## 7. 코드 읽기 순서

1. `ProvisioningService.java` — 인터페이스 (메서드 3개)
2. `ProvisioningServiceImpl.java` — 구현체 (Virtual Thread, dry-run, Outbox)
3. `ProvisioningOutboxRelay.java` — 스케줄러 (재시도, FOR UPDATE SKIP LOCKED)
4. `ProvisioningOutboxRepositoryImpl.java` — SQL (지수 백오프 CASE WHEN)
5. `ProvisioningIntegrationTest.java` — 통합 테스트

---

## 8. 관련 테이블

```sql
-- provisioning_outbox: 기관별 발행 이력
SELECT * FROM ido.provisioning_outbox LIMIT 5;

-- agency_endpoint_registry: 기관 엔드포인트 설정
SELECT * FROM ido.agency_endpoint_registry
WHERE endpoint_type = 'PROVISIONING';
```

---

## 연관 문서
- [F-21 Provisioning Relay](F-21-provisioning-relay.md)
- [Phase-Gate 전략](../phased-rollout-strategy.md)
- [배포 가이드 §8.1 프로비저닝 실패 시나리오](../_archive/2026-05-22/deployment-guide.md)
