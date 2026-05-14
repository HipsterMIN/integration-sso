/**
 * onepass-agency-sdk — OnePass 기관 연동 Java SDK
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  JDK 버전 프리 설계                                                      │
 * │  ─────────────────────────────────────────────────────────────────────  │
 * │  • sourceCompatibility = Java 8 → Android / 레거시 Spring Boot 2.x 호환  │
 * │  • 코어 의존성 ZERO (JDK 표준 API만 사용)                                  │
 * │  • HttpURLConnection (JDK 1.1+) 기본 구현체                              │
 * │  • OkHttp3 / Apache HttpClient 어댑터는 선택적 (compileOnly)             │
 * │  • Lombok은 컴파일 타임 전용 (런타임 의존성 없음)                            │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
plugins {
    `java-library`
}

// ── JDK 버전 프리: Java 8 소스/바이너리 호환 ──────────────────────────────────
// Toolchain은 빌드 JDK(21)를 사용하되, 출력 바이트코드는 Java 8 타겟
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    // Maven Central 배포를 위한 소스/Javadoc JAR 활성화
    withSourcesJar()
    withJavadocJar()
}

// ── 루트 subprojects 블록에서 상속된 Spring Boot BOM을 SDK에서 제외 ────────────
// SDK는 Spring 의존성 없음: 루트의 dependencyManagement → spring-boot-starter-test 등
// Java 8 타겟과 충돌하는 Spring Boot 3.x (JVM 17+) 의존성 전이 차단
configurations.all {
    resolutionStrategy.eachDependency {
        // 루트 subprojects 블록이 주입하는 Spring Boot Testcontainers 의존성 강제 제외
        if (requested.group == "org.springframework.boot" &&
            requested.name == "spring-boot-testcontainers") {
            useVersion("0") // 버전 0 → 실제로는 아래 exclude로 제거
        }
    }
}

// 루트 subprojects에서 상속된 불필요한 테스트 의존성 제거
configurations.named("testImplementation") {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-test")
    exclude(group = "org.springframework.boot", module = "spring-boot-testcontainers")
    exclude(group = "org.testcontainers")
}
configurations.named("testRuntimeOnly") {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-test")
    exclude(group = "org.springframework.boot", module = "spring-boot-testcontainers")
    exclude(group = "org.testcontainers")
}

// ── 루트 toolchain 재정의: SDK는 명시적으로 Java 8 컴파일 타겟 ─────────────────
// 루트 build.gradle.kts의 toolchain(21) 설정보다 sourceCompatibility 우선 적용
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf(
        "-Xlint:-options",      // Java 8 obsolete 경고 억제 (JDK 21 빌드에서 허용)
        "-Xlint:-deprecation",  // HttpURLConnection.URL(String) deprecation 억제 (JDK 20+)
        "-Xlint:-serial",
        "-parameters"
    ))
}

// ── 명시적 버전 선언: Spring BOM 없이 독립적으로 의존성 버전 관리 ─────────────────
val junitVersion        = "5.11.4"
val mockitoVersion      = "5.12.0"
val assertjVersion      = "3.26.3"
val okhttpVersion       = "4.12.0"

dependencies {
    // ── 런타임 코어 의존성 ZERO ──────────────────────────────────────────────
    // SDK 사용자에게 의존성 전이 없음

    // ── 컴파일 타임 전용: Lombok (런타임 무영향) ────────────────────────────
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ── 선택적 HTTP 어댑터: OkHttp3 (compileOnly — 사용 시 직접 추가) ───────
    // 사용자가 OkHttp를 클래스패스에 추가하면 OkHttpAgencyHttpAdapter 활성화
    compileOnly("com.squareup.okhttp3:okhttp:$okhttpVersion")

    // ── 선택적 HTTP 어댑터: Apache HttpClient 5.x ───────────────────────────
    compileOnly("org.apache.httpcomponents.client5:httpclient5:5.3.1")

    // ── 테스트 의존성: Spring BOM 없이 직접 버전 명시 ────────────────────────
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // JUnit 5 — 명시적 버전 (Spring Boot BOM 불사용)
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")

    // Mockito — 명시적 버전
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")

    // AssertJ — 명시적 버전
    testImplementation("org.assertj:assertj-core:$assertjVersion")

    // OkHttp MockWebServer (HttpUrlConnectionAdapter 실제 HTTP 왕복 테스트용)
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
    testImplementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
}

// ── BootJar: SDK 모듈은 Spring Boot 플러그인이 없으므로 설정 불필요 ─────────────
// (루트 build.gradle.kts의 onepass-agency-sdk 블록에서 Jar enabled=true 설정)
tasks.withType<Jar>().configureEach {
    enabled = true
    // 아티팩트 메타정보
    manifest {
        attributes(
            "Implementation-Title"   to "OnePass Agency SDK",
            "Implementation-Version" to project.version,
            "Build-Jdk-Spec"        to "8+",   // Java 8 이상 호환 명시
            "Automatic-Module-Name" to "kr.go.smes.sdk.agency"
        )
    }
}

// ── Javadoc: 한국어 인코딩 + Java 8 호환 경고 억제 ──────────────────────────
tasks.withType<Javadoc>().configureEach {
    options {
        encoding("UTF-8")
        (this as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:none", "-quiet")
            addStringOption("encoding", "UTF-8")
            addStringOption("charset", "UTF-8")
        }
    }
}

// ── 테스트 태스크: JUnit 5 플랫폼 명시 ────────────────────────────────────────
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
