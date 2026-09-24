// ══════════════════════════════════════════════════════════════════════════════
// idem-kr-hub — Idem KR Public Edition, hub 부트 모듈 (generalization-plan S8-a)
//
// 코어(idem-hub)가 모르는 SMES 회원 개념을 여기 둔다: NICE CI 조회(/api/v1/auth/nice/ci-check)·CI 토큰 교환·
// 기업인증 콜백·회원 전환(conversion)·기관 회원 조회(memberlookup)·구 벤더 프록시(/auth/nice|oacx/*)·
// 회원구분코드(mbrDvsnCd)·FE 회원전환 세션. 패키지는 io.github.hipstermin.idem.hub.kr.* — IdoApplication 의
// 컴포넌트 스캔 범위(io.github.hipstermin.idem.hub) 안이라 이 jar 가 클래스패스에 있으면 그대로 활성화된다.
//
// 코어 hub 는 이 모듈을 의존하지 않는다(가드: GeneralizationGuardTest). 에디션 = 어느 bootJar 를 실행하느냐.
//   코어 에디션: ./gradlew :idem-hub:bootJar          KR 에디션: ./gradlew :idem-kr-hub:bootJar
// ══════════════════════════════════════════════════════════════════════════════
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

springBoot {
    mainClass.set("io.github.hipstermin.idem.hub.IdoApplication")
}

dependencies {
    implementation(project(":idem-common"))
    implementation(project(":idem-hub"))

    // 코어의 implementation 의존은 컴파일 클래스패스에 오지 않는다 — 직접 쓰는 것만 선언
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework:spring-webflux")
    implementation("io.projectreactor.netty:reactor-netty-http")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation(testFixtures(project(":idem-hub")))   // IntegrationTestBase·테스트 프로파일 설정
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
    testImplementation("org.springframework:spring-jdbc")   // @WebMvcTest 슬라이스가 코어 JdbcTemplate 빈을 목으로 대체
}

// 통합 테스트(@Tag("integration"), Testcontainers) 는 코어 hub 와 같은 규약으로 분리 실행한다
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "KR 에디션 통합 테스트 (Testcontainers PostgreSQL/Redis + WireMock)"
    group       = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath       = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    if (System.getenv("DOCKER_UNAVAILABLE") == "true") {
        enabled = false
        logger.lifecycle("⚠️  DOCKER_UNAVAILABLE=true — integrationTest 비활성화")
    }
    systemProperty("spring.profiles.active", "integration-test")
}
