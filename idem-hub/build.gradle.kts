plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
    `java-test-fixtures`   // S8-a: IntegrationTestBase·테스트 프로파일 설정을 KR 에디션(idem-kr-hub) 테스트와 공유
}

// ── 통합 테스트 소스 세트 분리 (S8-T4) ────────────────────────────────────
// `./gradlew :idem-hub:integrationTest` 로 별도 실행 가능
// CI에서는 Docker 사용 불가 시 SKIP 처리 (DOCKER_UNAVAILABLE 환경 변수)
sourceSets {
    create("integrationTest") {
        java.srcDir("src/test/java")
        resources.srcDir("src/test/resources")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output + sourceSets["testFixtures"].output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output + sourceSets["testFixtures"].output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("integrationTest") {
    description = "Testcontainers 기반 통합 테스트 실행 (DB + Redis + WireMock)"
    group       = "verification"

    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath       = sourceSets["integrationTest"].runtimeClasspath

    useJUnitPlatform {
        // @Tag("integration") 만 실행
        includeTags("integration")
    }

    // Docker 미사용 환경에서는 통합 테스트 스킵
    val dockerUnavailable = System.getenv("DOCKER_UNAVAILABLE") == "true"
    if (dockerUnavailable) {
        enabled = false
        logger.lifecycle("⚠️  DOCKER_UNAVAILABLE=true — integrationTest 비활성화")
    }

    systemProperty("spring.profiles.active", "integration-test")
}

// ── 단위 테스트 태스크: @Tag("integration") 제외 ─────────────────────────────
// `./gradlew :idem-hub:test` 실행 시 Docker 의존 통합 테스트를 자동으로 제외
// Docker 없는 CI/CD 환경(DOCKER_UNAVAILABLE=true)에서도 단위 테스트만 안전하게 실행
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation(project(":idem-common"))
    // 본인인증 SPI Mock 플러그인 — 클래스패스에는 항상 있지만 idem.plugins.mock-auth.enabled=true 일 때만 활성 (P1)
    runtimeOnly(project(":idem-plugin-mock-auth"))
    // D3: NICE/OACX·AnyID 벤더 플러그인은 코어가 번들하지 않는다 — editions/idem-kr-hub 가 runtimeOnly 로 싣는다

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Tenant Profile JSON Schema 검증 (범용화 S2) — 1.5.x 는 Jackson 2 계열
    implementation("com.networknt:json-schema-validator:1.5.9")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.kafka:spring-kafka")

    // Resilience4j — Circuit Breaker + Retry (설계서 11.6.5절, S9-T2)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Redisson — 분산 락 (S9-T1: NiceTokenStore ensureAccessToken() 다중 Pod 대응)
    // redisson-spring-boot-starter: Spring Boot 자동 설정 + RedissonClient 빈 자동 등록
    // 3.52.0: Spring Data Redis 3.5 어댑터(redisson-spring-data-35) 포함. 3.32.0(-33 어댑터)은 3.5.x 에서
    //         RedisTemplate.expire() 가 StackOverflowError 로 재귀하는 문제가 있어 S5a 에서 올림
    implementation("org.redisson:redisson-spring-boot-starter:3.52.0")

    // OpenTelemetry — 분산 추적 (S9-T4: auth 스팬)
    // Spring Boot Actuator + Micrometer Tracing: W3C TraceContext 전파 + OTLP 내보내기
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    runtimeOnly("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure")

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

    // Apache HttpClient 5 — mTLS 클라이언트 인증서 장착 RestTemplate (Sprint 17)
    // Spring Boot 3.x 기본 SimpleClientHttpRequestFactory는 mTLS 미지원
    // HttpComponentsClientHttpRequestFactory + PoolingConnectionManager 조합으로 mTLS 구현
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // 벤더 SDK jar 는 저장소에 없다 — 각 플러그인이 vendor-libs(-PvendorLibsDir / IDEM_VENDOR_LIBS / ~/.idem/vendor-libs)에서 읽는다 (S5a·S5b)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("com.h2database:h2")

    // ── Testcontainers (S8-T4: 통합 테스트) ──────────────────────────────
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Redis Testcontainer (공식 모듈 없음 → GenericContainer 사용)
    testImplementation("org.testcontainers:testcontainers")
    // WireMock: NICE 외부 API Mock 서버
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")

    // S8-a: 테스트 픽스처(src/testFixtures) — IntegrationTestBase 가 쓰는 것만. 소비자: 이 모듈의 test·integrationTest, idem-kr-hub
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.testcontainers:postgresql")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.wiremock:wiremock-standalone:3.10.0")
    testFixturesImplementation("org.springframework.kafka:spring-kafka")
    // S7: AdminTestSupport — 관리자 로그인(TestRestTemplate + Jackson)으로 IT 가 admin API 를 호출한다
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-web")
}
