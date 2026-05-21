# ADR-002: JDK 21 Virtual Threads 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-002 |
| **제목** | JDK 21 LTS + Virtual Threads(Project Loom) 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2025-Q4 (Sprint 1) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `ido/build.gradle`, `ProvisioningServiceImpl.java` |

---

## 컨텍스트 (Context)

통합인증 플랫폼의 핵심 병목은 **I/O 대기 시간**이다.

- 68개 기관에 동시 HTTPS POST 발송 (프로비저닝)
- NICE API 호출 (~300ms 평균)
- OACX 간편인증 SDK 호출
- PostgreSQL 쿼리 다수

기존 Tomcat 스레드 모델(기본 200 스레드)로는 68개 기관 동시 발송 시 스레드 고갈 위험이 있었다. WebFlux(리액티브)로 전환하는 방안도 검토했으나, 코드 복잡도와 팀 러닝커브가 과도했다.

---

## 결정 (Decision)

**JDK 21 LTS**를 채택하고, I/O 집약 구간에 **Virtual Threads(Project Loom)**를 적용한다.

### 적용 범위

#### 1. 프로비저닝 병렬 HTTP (핵심)
```java
// ProvisioningServiceImpl.java
try (ExecutorService vThreadPool = Executors.newVirtualThreadPerTaskExecutor()) {
    // 68개 기관 동시 HTTPS POST
    // OS 스레드 블로킹 없이 수만 개 Virtual Thread 동시 실행 가능
    for (AgencyEndpointRecord endpoint : targetEndpoints) {
        futures.add(vThreadPool.submit(() -> sendToAgency(endpoint, request, correlationId)));
    }
}
```

#### 2. Spring Boot Virtual Thread 활성화
```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true  # Tomcat → Virtual Thread Executor 전환
```

#### 3. 비교: Platform Thread vs Virtual Thread

| 항목 | Platform Thread | Virtual Thread |
|------|----------------|----------------|
| 메모리 | ~1MB/thread | ~수KB/thread |
| 68 기관 병렬 | 68 OS 스레드 블로킹 | ~68 VT, OS 스레드 소수 |
| 코드 스타일 | 동기 (기존 유지) | 동기 (그대로) |
| 리액티브 전환 | 불필요 | 불필요 |

---

## 결과 (Consequences)

### 긍정적 효과
- 68개 기관 동시 HTTPS 발송: OS 스레드 블로킹 없이 처리
- 기존 동기 코드 스타일 유지 → 팀 생산성 유지
- Tomcat 스레드 풀 고갈 위험 제거
- JDK 21 LTS: 2026 이후 장기 지원 보장

### 주의사항 / 제약
- **ThreadLocal 주의**: Virtual Thread는 ThreadLocal 사용 시 메모리 누수 위험 없으나, `InheritableThreadLocal` 상속 동작 변경 확인 필요
- **synchronized 핀닝**: `synchronized` 블록에서 I/O 블로킹 시 carrier thread 핀닝 발생 → `ReentrantLock` 권장
- **DB 커넥션 풀**: Virtual Thread 증가 시 DB 커넥션 풀(HikariCP) 설정 재검토 필요 (max-pool-size 조정)
- **maxParallelAgencies 상한선**: 안전을 위해 `ido.provisioning.max-parallel-agencies=100` 설정으로 상한 제한

### 포기한 대안
- **Spring WebFlux (Project Reactor)**: 코드 전면 개편 필요, 팀 역량 부족
- **JDK 17 LTS 유지**: Virtual Thread 미지원, 병렬 처리 복잡성 해결 불가
- **Kotlin Coroutine**: 언어 전환 비용 과다

---

## 관련 ADR

- [ADR-001](ADR-001-ido-microservice-architecture.md) — 마이크로서비스 구조
- [ADR-008](ADR-008-transactional-outbox-pattern.md) — Outbox Relay (Virtual Thread 사용)
