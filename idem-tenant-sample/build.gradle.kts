plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation(project(":idem-common"))

    // Web / Validation / Actuator
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Data
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Kafka (PoC 전용 — 운영 시 제거 예정)
    implementation("org.springframework.kafka:spring-kafka")

    // Resilience4j — IdO Verify API Circuit Breaker / Retry
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Jackson (HMAC 서명 검증용 ObjectMapper)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")   // S6 PR-2: 표준 OIDC RP 테스트(OP 스텁)
}

description = "기관 연계 OIDC 클라이언트 스텁 — IdO Webhook 수신 / Verify API 호출 / 기관 세션 관리 (설계서 14~16장)"
