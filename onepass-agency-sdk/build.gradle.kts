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
 *
 * ── 배포 설정 ─────────────────────────────────────────────────────────────
 * Maven Central 또는 내부 Nexus 배포:
 *   ./gradlew :onepass-agency-sdk:publish
 *
 * 내부 Nexus 전용 배포 (서명 생략):
 *   SKIP_SIGNING=true ./gradlew :onepass-agency-sdk:publish
 *
 * 필수 환경변수 (Maven Central 배포 시):
 *   ORG_GRADLE_PROJECT_signingKey       — GPG 개인키 (ASCII Armor)
 *   ORG_GRADLE_PROJECT_signingPassword  — GPG 키 패스프레이즈
 *   OSSRH_USERNAME                      — Sonatype OSSRH 사용자명
 *   OSSRH_PASSWORD                      — Sonatype OSSRH 비밀번호
 *
 * 내부 Nexus 배포 시 추가 환경변수:
 *   NEXUS_URL                           — Nexus 서버 URL
 *   NEXUS_USERNAME                      — Nexus 사용자명
 *   NEXUS_PASSWORD                      — Nexus 비밀번호
 */
plugins {
    `java-library`
    `maven-publish`
    signing
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

// ══════════════════════════════════════════════════════════════════════════════
// Maven Publishing 설정
// ══════════════════════════════════════════════════════════════════════════════
//
// 배포 대상:
//   1. Maven Central (Sonatype OSSRH) — 공개 배포
//   2. 내부 Nexus Repository — 정부망 내부 배포
//
// 배포 명령:
//   ./gradlew :onepass-agency-sdk:publish                 (전체 저장소)
//   ./gradlew :onepass-agency-sdk:publishToMavenLocal     (로컬 ~/.m2 테스트)
//
// ──────────────────────────────────────────────────────────────────────────────

// SDK 아티팩트 메타데이터
val sdkGroup      = "kr.go.smes"
val sdkArtifactId = "onepass-agency-sdk"
val sdkVersion    = project.version.toString()    // 루트 build.gradle.kts: "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            // ── 아티팩트 좌표 ────────────────────────────────────────────────
            groupId    = sdkGroup
            artifactId = sdkArtifactId
            version    = sdkVersion

            // ── 기본 JAR + sources + javadoc 포함 ───────────────────────────
            from(components["java"])   // java 컴포넌트 = 메인 JAR + sources + javadoc

            // ── 루트에서 주입된 Spring Boot BOM을 POM에서 제거 ────────────────
            // SDK는 런타임 의존성 ZERO: 사용자 POM에 Spring BOM 전이 없어야 함
            pom.withXml {
                val root = asNode()
                val depMgmt = root.children().filterIsInstance<groovy.util.Node>()
                    .find { it.name().toString().contains("dependencyManagement") }
                if (depMgmt != null) root.remove(depMgmt)
            }

            // ── POM 메타정보 ─────────────────────────────────────────────────
            pom {
                name.set("OnePass Agency SDK")
                description.set(
                    "OnePass 기관 연동 Java SDK — 기관 시스템이 OnePass Gateway API를 " +
                    "호출하기 위한 경량 클라이언트 라이브러리. Java 8+, 런타임 의존성 없음."
                )
                url.set("https://github.com/HipsterMIN/integration-sso")
                inceptionYear.set("2025")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("onepass-team")
                        name.set("OnePass Platform Team")
                        email.set("onepass@smes.go.kr")
                        organization.set("중소벤처기업부 (MSSB)")
                        organizationUrl.set("https://www.mss.go.kr")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/HipsterMIN/integration-sso.git")
                    developerConnection.set("scm:git:ssh://github.com/HipsterMIN/integration-sso.git")
                    url.set("https://github.com/HipsterMIN/integration-sso")
                    tag.set("HEAD")
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/HipsterMIN/integration-sso/issues")
                }
            }
        }
    }

    repositories {
        // ── 1. Maven Central (Sonatype OSSRH) ───────────────────────────────
        // SNAPSHOT 버전은 snapshots, RELEASE 버전은 releases로 자동 라우팅
        maven {
            name = "OSSRH"
            val releasesUrl  = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (sdkVersion.endsWith("-SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = System.getenv("OSSRH_USERNAME") ?: ""
                password = System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }

        // ── 2. 내부 Nexus Repository (정부망 내부 배포) ───────────────────────
        // NEXUS_URL 환경변수가 설정된 경우에만 활성화
        val nexusUrl = System.getenv("NEXUS_URL")
        if (!nexusUrl.isNullOrBlank()) {
            maven {
                name = "Nexus"
                url = uri(nexusUrl)
                credentials {
                    username = System.getenv("NEXUS_USERNAME") ?: ""
                    password = System.getenv("NEXUS_PASSWORD") ?: ""
                }
                isAllowInsecureProtocol = nexusUrl.startsWith("http://")  // HTTP 허용 (내부망)
            }
        }

        // ── 3. 로컬 Maven 저장소 (개발/검증용) ──────────────────────────────
        mavenLocal()
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// GPG 서명 설정 (Maven Central 배포 필수 요건)
// ══════════════════════════════════════════════════════════════════════════════
//
// 서명 방법 선택:
//   A. 환경변수 방식 (CI/CD 권장):
//      export ORG_GRADLE_PROJECT_signingKey="$(gpg --export-secret-keys --armor KEY_ID)"
//      export ORG_GRADLE_PROJECT_signingPassword="패스프레이즈"
//
//   B. gradle.properties 방식 (로컬 개발용):
//      ~/.gradle/gradle.properties 에 추가:
//        signing.keyId=KEY_ID_마지막8자리
//        signing.password=패스프레이즈
//        signing.secretKeyRingFile=/home/user/.gnupg/secring.gpg
//
//   SKIP_SIGNING=true 환경변수로 서명 건너뛰기 (내부 Nexus 배포 시)
//
// ──────────────────────────────────────────────────────────────────────────────

val skipSigning = System.getenv("SKIP_SIGNING")?.toBoolean() ?: false

signing {
    if (!skipSigning) {
        // 환경변수 방식 (CI: ORG_GRADLE_PROJECT_signingKey / signingPassword)
        val signingKey      = findProperty("signingKey")      as String?
        val signingPassword = findProperty("signingPassword") as String?

        if (!signingKey.isNullOrBlank()) {
            // 환경변수로 제공된 인메모리 키 사용 (파일 없음, CI 친화적)
            useInMemoryPgpKeys(signingKey, signingPassword ?: "")
        } else {
            // gradle.properties의 signing.keyId / signing.secretKeyRingFile 사용 (로컬)
            useGpgCmd()
        }

        sign(publishing.publications["mavenJava"])
    }
}

// ── 서명 태스크가 publish보다 먼저 실행되도록 순서 보장 ──────────────────────────
tasks.withType<Sign>().configureEach {
    onlyIf { !skipSigning }
}
