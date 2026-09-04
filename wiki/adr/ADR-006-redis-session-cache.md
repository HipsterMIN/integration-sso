# ADR-006: Redis 세션·캐시 레이어 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-006 |
| **제목** | Redis를 세션 저장소·캐시·분산 락 레이어로 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2025-Q4 (Sprint 2) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `idem-hub/config/RedisConfig.java`, `idem-hub/config/RedissonConfig.java`, `idem-hub/auth/store/NiceAuthSessionStore.java`, `idem-hub/fe/session/FeSessionService.java` |

---

## 컨텍스트 (Context)

다음 요구사항이 단순 DB 저장으로는 해결하기 어려웠다:

1. **세션 TTL 자동 만료**: NICE 인증 세션(5분), FE 세션(30분), Handoff 티켓(10분) 등 각기 다른 TTL
2. **FE Advisory 브로드캐스트**: 세션 만료·강제 로그아웃 이벤트를 N개 인스턴스에 동시 전달
3. **Rate Limit 카운터**: 초당 요청 수 Redis INCR + TTL로 구현 (원자적 연산)
4. **분산 락**: 동일 사용자 중복 처리 방지 (Redisson)
5. **User Status Cache**: Q-IM 조회 결과 캐싱 (반복 호출 최소화)

---

## 결정 (Decision)

**Redis**를 세션·캐시·분산 락 레이어로 채택한다.

### 사용 목적별 설계

#### 1. FE 세션 (FeSessionService)
```java
// TTL: 30분 (AUTH_SESSION_TTL)
// Key: "fe:session:{sessionId}"
// Value: FeSession (직렬화)
redisTemplate.opsForValue().set(key, session, Duration.ofMinutes(30));
```

#### 2. NICE 인증 세션 (NiceAuthSessionStore)
```java
// TTL: 5분 (NICE_AUTH_TTL)
// Key: "nice:auth:{authKey}"
// Value: NiceAuthSession (JSON)
```

#### 3. Rate Limit 카운터 (AgencyRateLimiter, AuthRateLimitInterceptor)
```java
// Redis INCR + EXPIRE 원자적 연산
// Key: "ratelimit:{agencyCode}:{window}"
// 초당/분당 요청 수 제한
```

#### 4. 분산 락 (RedissonConfig)
```java
// Redisson RLock — 동일 사용자 중복 처리 방지
// Key: "lock:provision:{qimUserId}"
// TTL: 10초 (처리 완료 시 즉시 해제)
```

#### 5. FE Advisory Pub/Sub (SessionAdvisoryPublisher)
```java
// Redis Pub/Sub 채널: "fe:advisory"
// 세션 만료·강제 로그아웃 이벤트 브로드캐스트
// → FeAdvisoryConsumer (Kafka)로 수신
```

#### 6. User Status Cache (UserStatusCache)
```java
// TTL: 60초
// Key: "user:status:{qimUserId}"
// Q-IM 상태 조회 결과 캐싱
```

### Redis 설정

```yaml
# application.yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
```

### NoOp 모드 (개발 환경)
```java
// NoOpRedissonConfig.java — Redis 없는 개발 환경 지원
// Feature Flag: ido.redisson.enabled=false
```

---

## 결과 (Consequences)

### 긍정적 효과
- **TTL 자동 만료**: 세션·티켓 정리 로직 불필요
- **원자적 카운터**: Rate Limit 정확한 윈도우 카운팅
- **Pub/Sub 브로드캐스트**: 멀티 인스턴스 FE Advisory 일괄 전달
- **Redisson 분산 락**: 클러스터 환경에서도 정확한 상호 배제

### 부정적 효과 / 주의사항
- **Redis 장애 시 세션 유실**: Redis HA(Sentinel/Cluster) 필요
- **메모리 관리**: 세션 데이터 과적재 시 OOM 위험 → maxmemory + eviction policy 설정
- **개발 환경 의존성**: Redis 로컬 설치 또는 Docker 필요 (NoOpRedissonConfig로 부분 완화)

### 포기한 대안
- **Hazelcast**: 팀 러닝커브, Spring 통합 복잡
- **DB 세션**: TTL 자동 만료 구현 복잡, 성능 열위
- **메모리 내 세션**: 다중 인스턴스 환경 불가

---

## 관련 ADR

- [ADR-001](ADR-001-ido-microservice-architecture.md) — 마이크로서비스 (인스턴스 다중화 전제)
- [ADR-003](ADR-003-spring-boot-3.md) — Spring Data Redis 의존
