plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

// ── 로컬 libs 디렉토리 (OACX SDK, BouncyCastle 등 Maven Central 미등록 JAR) ──
configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation(project(":platform-common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.kafka:spring-kafka")

    // Resilience4j — Circuit Breaker (설계서 11.6.5절)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // JWT (Handoff Ticket 서명, 설계서 16.4절)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // ── NICE/OACX 본인인증 연동 (S7-T2) ──────────────────────────────────
    // WebClient: NICE 휴대폰 본인인증 · 통합인증 서버 HTTP 클라이언트
    // spring-webflux + reactor-netty-http 만 추가 → Tomcat 서블릿 컨테이너 유지
    // (spring-boot-starter-webflux 가 아닌 개별 모듈 추가로 Netty 서버 전환 없음)
    // Spring Boot WebApplicationType.deduceFromClasspath(): DispatcherServlet 존재 시 SERVLET 반환
    implementation("org.springframework:spring-webflux")
    implementation("io.projectreactor.netty:reactor-netty-http")

    // BouncyCastle: NICE 인증 결과 복호화 (PBKDF2WithHmacSHA256 / AES-GCM)
    // JDK 11+ 기본 JCE로 처리 가능하나 bcprov를 명시적 추가하여 일관성 보장
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // OACX SDK (전자서명 중계모듈) — Maven Central 미등록 → 로컬 libs/ 디렉토리
    // 버전: v1.3.2 (onepass-be/libs/OACX-SDK-v1.3.2.jar 에서 복사)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2")
}
