# F-08: Redisson 분산 락 (기존 기능)

> **환경변수**: `IDO_REDISSON_ENABLED`  
> **기본값**: `true` (운영 필수)  
> **소스**: `ido/src/main/java/kr/go/smes/ido/config/RedissonConfig.java` / `NoOpRedissonConfig.java`

---

## 1. 이 기능은 무엇인가?

K8s 다중 Pod 환경에서 **NICE 본인인증 토큰 발급**이 중복으로 처리되지 않도록 분산 락을 제공합니다.

```
Pod A: 사용자 xxx의 NICE 토큰 요청
Pod B: 같은 사용자 xxx의 NICE 토큰 요청 (동시)

F-08=true (Redisson 분산 락):
  Pod A: Redis 락 획득 → NICE API 호출 → 토큰 발급 → 락 해제
  Pod B: Redis 락 대기 → Pod A 완료 후 → 캐시에서 토큰 반환

F-08=false (NoOp 락):
  Pod A: JVM synchronized → NICE API 호출
  Pod B: 다른 JVM → 동시 호출 → NICE API 중복 요청 ⚠️
```

---

## 2. false 설정 가능한 경우

**단일 Pod 환경** (개발/테스트)에서만 false 허용:
```bash
# 로컬 개발 (Docker Compose, 단일 Pod)
IDO_REDISSON_ENABLED=false  # NoOpRedissonConfig 활성화
```

> ⚠️ **운영(K8s 다중 Pod) 환경에서 false는 절대 금지**

---

## 3. false 시 폴백 동작

```java
// NoOpRedissonConfig.java — 락을 항상 성공으로 위장
// K8s 다중 Pod에서는 의미 없지만 단일 Pod에서는 JVM synchronized와 동일 효과
```

---

## 연관 문서
- [Phase-Gate 전략](../phased-rollout-strategy.md)
