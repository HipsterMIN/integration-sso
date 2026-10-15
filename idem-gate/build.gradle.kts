plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation(project(":idem-common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")   // 1.1.1 G1-4: /actuator/prometheus (종전에는 노출 설정만 있고 레지스트리가 없어 404)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.kafka:spring-kafka")

    // Resilience4j — Circuit Breaker (외부 IdP 연동, 설계서 11.6.5절)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // JWT 검증: Java 표준 라이브러리만 사용 (java.security.Signature, java.security.KeyFactory)
    // Keycloak JWKS RS256 검증 시 외부 JWT 라이브러리 불필요 — jjwt 의존성 제거됨

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")   // S6: OIDC 프런트(Keycloak·hub 스텁)
}

// ── 1.1.4 G2-10: Testcontainers 통합 테스트(PostgreSQL, @Tag("integration")) — 단위 테스트(test)에서 제외,
//    `./gradlew :idem-gate:integrationTest` 로 실행(Docker 필요). hub 의 integrationTest 와 같은 규칙(CI 는 돌리지 않고 로컬 push 전에)
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Testcontainers 기반 통합 테스트 실행 (PostgreSQL — auth_result jsonb 바인딩)"
    group       = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath       = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    if (System.getenv("DOCKER_UNAVAILABLE") == "true") {
        enabled = false
        logger.lifecycle("⚠️  DOCKER_UNAVAILABLE=true — integrationTest 비활성화")
    }
}
