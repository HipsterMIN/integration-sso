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
    // 1.1.2: UI 스타터(swagger-ui 5.21 webjar — 번들 DOMPurify 3.2.4 의 XSS CVE 20건, OWASP run 38062705804)를 API 전용 스타터로.
    //        OpenAPI JSON(/api-docs)은 그대로, Swagger UI 화면은 내지 않는다(운영 매뉴얼·스모크·콘솔 어디서도 쓰지 않았다).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.17")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}
