# ADR-003: Spring Boot 3 / Spring Framework 6 채택

| 항목 | 내용 |
|------|------|
| **ID** | ADR-003 |
| **제목** | Spring Boot 3.x / Spring Framework 6.x 채택 |
| **상태** | ✅ Accepted |
| **결정일** | 2025-Q4 (Sprint 1) |
| **결정자** | 아키텍처 위원회 |
| **관련 파일** | `*/build.gradle`, `*/application.yml` |

---

## 컨텍스트 (Context)

기존 레거시 SMEP 시스템은 Spring Boot 2.x + Jakarta EE 8 기반이었다. 통합인증 플랫폼 신규 개발 시 기술 스택 선택 기준:

1. **JDK 21 Virtual Thread 지원**: Spring Boot 3.2+ 에서 공식 지원
2. **Jakarta EE 10 네임스페이스**: `javax.*` → `jakarta.*` 전환 (JDK 17+ 요구사항)
3. **GraalVM Native Image**: 향후 콜드 스타트 최적화 가능성
4. **Spring Security 6**: OAuth2·OIDC 개선, Method Security 강화
5. **장기 지원**: Spring Boot 3.x는 2025년 이후 LTS 제공

---

## 결정 (Decision)

**Spring Boot 3.2.x (Spring Framework 6.1.x)** 를 전 모듈에 공통 채택한다.

### 모듈별 핵심 Spring 의존성

```groovy
// build.gradle (공통)
plugins {
    id 'org.springframework.boot' version '3.2.x'
    id 'io.spring.dependency-management' version '1.1.x'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
}
```

### Virtual Thread 통합 설정
```yaml
spring:
  threads:
    virtual:
      enabled: true   # Spring Boot 3.2+ 공식 지원
```

### Spring Security 6 적용 사항
- `WebSecurityConfigurerAdapter` 제거 → `SecurityFilterChain` Bean 방식
- `HttpSecurity.authorizeRequests()` → `authorizeHttpRequests()`
- CSRF: SPA 구조상 JWT 기반 → CSRF 비활성화 (Stateless API)

### 주요 마이그레이션 포인트 (Spring Boot 2 → 3)
| 항목 | Boot 2 | Boot 3 |
|------|--------|--------|
| 네임스페이스 | `javax.servlet` | `jakarta.servlet` |
| Spring Data | `CrudRepository.findById()` | 동일 (호환) |
| Actuator 경로 | `/actuator/**` | `/actuator/**` (동일) |
| Observability | Sleuth + Zipkin | Micrometer Tracing (내장) |

---

## 결과 (Consequences)

### 긍정적 효과
- Virtual Thread 공식 통합 (ADR-002 시너지)
- Micrometer Tracing 내장 → SloMetrics 구현 단순화 (`SloController`, `SloService`)
- Spring Security 6 개선된 OIDC 지원 → Keycloak 연동 단순화
- Jakarta EE 10 호환으로 Java 21 LTS 완전 지원

### 부정적 효과 / 주의사항
- **javax → jakarta 마이그레이션**: 기존 레거시 라이브러리 호환성 검증 필요
- **Spring Security 6 Breaking Change**: 설정 방식 전면 변경 → 초기 보안 설정 공수 증가
- **Hibernate 6**: JPA 쿼리 일부 변경 (Hibernate ORM 5 → 6 Breaking Change 주의)

### 포기한 대안
- **Spring Boot 2.7.x (LTS)**: 2023년 지원 종료, Virtual Thread 미지원
- **Quarkus**: GraalVM Native 최적화 우수하나 팀 역량 부족, 생태계 협소

---

## 관련 ADR

- [ADR-002](ADR-002-jdk21-virtual-threads.md) — JDK 21 Virtual Threads
- [ADR-004](ADR-004-kafka-eda.md) — Kafka (spring-kafka 의존)
