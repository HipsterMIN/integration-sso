import org.springframework.boot.gradle.tasks.bundling.BootJar
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

// ── 플러그인 버전 상수 ─────────────────────────────────────────────────────────
val owaspVersion   = "12.2.2"   // OWASP Dependency-Check
val sonarVersion   = "5.1.0.4882"  // SonarQube/SonarCloud

plugins {
    java
    id("org.springframework.boot")            version "3.5.9"       apply false
    id("io.spring.dependency-management")     version "1.1.7"       apply false
    // ── P3-04: 보안 스캔 & 코드 품질 플러그인 (루트 전용) ──────────────────────
    id("org.owasp.dependencycheck")           version "12.2.2"      apply true
    id("com.diffplug.spotless")               version "7.2.1"       apply false
    id("org.sonarqube")                       version "5.1.0.4882"  apply true
    jacoco
}

// ── 전체 공통 설정 ────────────────────────────────────────────────────────────
allprojects {
    group   = "io.github.hipstermin.idem"
    version = "1.0.1"   // 1.0.1: 3차 적대적 점검 후속 PR-A/B/C (릴리스 태그 v1.0.1)

    repositories {
        mavenCentral()
    }
}

// ── Mockito Agent 전용 Configuration (ADR-013 방법 B) ────────────────────────
// JDK 24에서 Dynamic Agent Loading 완전 차단 예정 (JEP 451/472).
// 방법 A(-XX:+EnableDynamicAgentLoading)는 임시 억제이고, 이 설정이 근본 해결책임.
// byte-buddy-agent JAR을 -javaagent로 명시하면 런타임 동적 로딩 없이 Agent가 동작.
//
// ※ mockitoAgent 설정은 루트에서 subprojects {} 블록 전에 정의해야
//    아래 tasks.withType<Test> 블록에서 configurations["mockitoAgent"]를 참조할 수 있음.
val mockitoAgentVersion = "1.17.8"  // mockito-core가 전이하는 byte-buddy-agent 버전과 동기화

// ── Windows/Linux 공통 강제 clean 설정 (루트 + 서브모듈) ───────────────────────
fun Project.configureForceCleanBuildDir() {
    tasks.register<Exec>("forceCleanBuildDir") {
        val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
        val osName = System.getProperty("os.name").lowercase()

        if (osName.contains("windows")) {
            // Windows 파일 잠금(lock) 타이밍 이슈 완화를 위해 1회 재시도
            commandLine(
                "cmd", "/c",
                "if exist \"$buildDirPath\" (rmdir /s /q \"$buildDirPath\" & if exist \"$buildDirPath\" timeout /t 1 /nobreak >nul & if exist \"$buildDirPath\" rmdir /s /q \"$buildDirPath\")"
            )
        } else {
            commandLine("rm", "-rf", buildDirPath)
        }
        isIgnoreExitValue = true
        description = "Deletes the build directory forcefully (cross-platform)."
    }

    tasks.named("clean") {
        dependsOn("forceCleanBuildDir")
        // forceCleanBuildDir에 위임 — Delete 태스크의 기본 삭제 동작 비활성화
        (this as? Delete)?.delete?.clear()
    }
}

// 루트 프로젝트 clean도 동일 정책 적용 (기존 실패 지점: :clean)
configureForceCleanBuildDir()

// ── 서브프로젝트 공통 설정 ─────────────────────────────────────────────────────
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    // ADR-013 방법 B: Mockito Agent 전용 Configuration
    // -javaagent로 byte-buddy-agent를 명시 주입 → JDK 24 Dynamic Agent Loading 금지 대비.
    //
    // ※ idem-sdk-java는 자체 build.gradle.kts에서 configurations.all { resolutionStrategy }를
    //   사용하므로, mockitoAgent를 생성한 뒤 resolutionStrategy 변경이 충돌함.
    //   SDK는 Spring 테스트 스택 없이 JUnit 5 + Mockito 직접 버전 명시 모듈이므로
    //   여기서는 SDK를 제외하고, SDK 자체 build.gradle.kts에서 별도 처리함.
    // idem-agent: 완전 독립 모듈 — Spring/Lombok/Testcontainers 비의존
    //               자체 byte-buddy shading(relocated) 사용하므로 mockitoAgent 제외
    if (project.name != "idem-sdk-java" && project.name != "idem-agent") {
        val mockitoAgent by configurations.creating {
            isCanBeResolved = true
            isCanBeConsumed = false
        }
        dependencies {
            // byte-buddy-agent: Mockito inline-mock-maker가 사용하는 ByteBuddy JVM Agent
            // transitive = false: byte-buddy-agent는 독립 JAR이므로 전이 의존성 불필요
            mockitoAgent("net.bytebuddy:byte-buddy-agent:$mockitoAgentVersion") { isTransitive = false }
        }
    }

    // Java 21 toolchain
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    // BOM import — 플러그인 apply 후 타입 캐스팅으로 접근
    configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.9")
        }
    }

    val testcontainersVersion = "1.20.4"

    // idem-agent는 Spring/Lombok/Testcontainers 비의존 완전 독립 모듈
    // 자체 build.gradle.kts에서 JUnit 5 직접 버전 명시로 처리
    if (project.name != "idem-agent") {
        dependencies {
            // Lombok
            "compileOnly"("org.projectlombok:lombok")
            "annotationProcessor"("org.projectlombok:lombok")
            "testCompileOnly"("org.projectlombok:lombok")
            "testAnnotationProcessor"("org.projectlombok:lombok")

            // Test
            "testImplementation"("org.springframework.boot:spring-boot-starter-test")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")

            // Testcontainers BOM + 모듈
            "testImplementation"(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
            "testImplementation"("org.testcontainers:junit-jupiter")
            "testImplementation"("org.testcontainers:postgresql")
            "testImplementation"("org.testcontainers:kafka")
            "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // JaCoCo 커버리지 데이터 생성 활성화
        finalizedBy(tasks.named("jacocoTestReport"))
        // ADR-013 방법 B: byte-buddy-agent를 -javaagent로 명시 주입 (근본 해결)
        // JDK 21+의 동적 Agent 로딩 경고를 JVM 레벨에서 원천 차단.
        // JEP 451(JDK 21 준비) / JEP 472(JDK 24 차단)에 대응.
        //
        // idem-sdk-java는 configurations.all { resolutionStrategy } 충돌로
        // mockitoAgent configuration 미생성 → 방법 A 플래그만 적용.
        // SDK 자체 build.gradle.kts에서 jvmArgs 직접 처리.
        val args = mutableListOf(
            "-XX:+EnableDynamicAgentLoading",  // 방법 A: JDK 버전 교차 환경 대응
            "-Djdk.instrument.traceUsage=false"
        )
        if (project.name != "idem-sdk-java" && project.name != "idem-agent") {
            // 방법 B: -javaagent 명시 (mockitoAgent configuration이 있는 모듈만)
            args.add(0, "-javaagent:${configurations["mockitoAgent"].asPath}")
        }
        jvmArgs(args)
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    // ── JaCoCo 커버리지 리포트 설정 ────────────────────────────────────────────
    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)    // SonarQube XML 입력용
            html.required.set(true)   // 인간이 읽는 HTML 리포트
            csv.required.set(false)
        }
        // 자동 생성 코드 제외
        classDirectories.setFrom(
            files(classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/Q*.class",           // QueryDSL Q클래스
                        "**/*Application.class", // 스프링 부트 메인
                        "**/*Config.class",      // 설정 클래스
                        "**/dto/**",             // DTO (비즈니스 로직 없음)
                    )
                }
            })
        )
    }

    // JaCoCo 커버리지 최소 임계값 (CI 게이트)
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        violationRules {
            rule {
                limit {
                    minimum = "0.50".toBigDecimal()  // 전체 라인 커버리지 50% 이상
                }
            }
        }
    }

    // 루트와 동일한 강제 clean 정책 적용
    configureForceCleanBuildDir()
}

// ── idem-common: 실행 JAR 불필요, plain JAR만 생성 ───────────────────────
project(":idem-common") {
    tasks.withType<BootJar> { enabled = false }
    tasks.withType<Jar>     { enabled = true  }
}

// ── idem-relay: 실행 JAR 생성 (Spring Boot 플러그인 적용) ─────────────
// ShedLock 분산 릴레이 배치 서비스 — 독립 배포 아티팩트
project(":idem-relay") {
    tasks.withType<Jar>     { enabled = true  }
}

// ── idem-sdk-java: Java 8 호환 라이브러리 — Spring Boot 플러그인/BOM 제외 ─
// SDK는 JDK 버전 프리 설계: Spring 의존성 전이 없음, 자체 build.gradle.kts에서 타겟 설정
project(":idem-sdk-java") {
    // Spring Boot 플러그인이 없으므로 BootJar 태스크가 존재하지 않음 — Jar만 활성화
    tasks.withType<Jar> { enabled = true }
    // Spring BOM 버전 관리는 테스트 의존성에만 적용 (junit-jupiter 버전 등)
    // 코어 런타임에는 Spring 의존성 없음
}

// ══════════════════════════════════════════════════════════════════════════════
// P3-04: OWASP Dependency-Check 설정
// ══════════════════════════════════════════════════════════════════════════════
//
// 실행: ./gradlew dependencyCheckAnalyze
// 설정: https://jeremylong.github.io/DependencyCheck/dependency-check-gradle/
//
// CI 환경변수:
//   NVD_API_KEY — NVD API 키 (설정 시 빠른 DB 업데이트)
//   DOCKER_UNAVAILABLE=true — Docker 없는 환경 자동 감지
// ──────────────────────────────────────────────────────────────────────────────
dependencyCheck {
    // ── 보고서 형식 ──────────────────────────────────────────────────────────
    formats = listOf("HTML", "JSON", "SARIF")
    outputDirectory = layout.buildDirectory.dir("reports/dependency-check")   // 12.2+: DirectoryProperty

    // ── CVSS 점수 7.0 이상 시 빌드 실패 (High/Critical 취약점) ───────────────
    // CI에서는 || true 로 실행하여 리포트만 수집; 운영 게이트는 별도 정책 적용
    failBuildOnCVSS = 7.0f

    // ── 오탐(False Positive) 억제 파일 ──────────────────────────────────────
    suppressionFiles = listOf("$rootDir/infra/owasp/suppressions.xml")

    // ── NVD 데이터 캐시 경로 (CI 캐시 키와 동기화) ──────────────────────────
    data {
        directory = "${System.getProperty("user.home")}/.gradle/dependency-check-data"
    }

    // ── NVD API 키 (환경변수에서 읽음) ──────────────────────────────────────
    nvd {
        val nvdApiKey = System.getenv("NVD_API_KEY") ?: ""
        apiKey = nvdApiKey
        // 키 보유 시 NVD 허용량은 50req/30s(≈600ms 간격). 무조건 4s를 걸면 ~190페이지 전체 동기화에
        // 지연만 13분+가 들어 키의 이점이 사라지고 30분 타임아웃을 유발한다(2026-09-02 야간 스캔 실측).
        delay  = if (nvdApiKey.isNotBlank()) 1000 else 4000
        maxRetryCount = 3
    }

    // ── 스캔 제외 경로 ────────────────────────────────────────────────────────
    skipConfigurations = listOf(
        "testImplementation",
        "testRuntimeOnly",
        "testCompileOnly",
        "annotationProcessor",
        "testAnnotationProcessor",
    )

    // ── 분석기 설정 ──────────────────────────────────────────────────────────
    analyzers {
        // Docker 없는 CI 환경 대응
        assemblyEnabled = false    // .NET Assembly 분석 비활성화
        nuspecEnabled   = false
        nugetconfEnabled = false
        // Node.js 분석 (FE 의존성) — 12.2+: nodeEnabled/nodeAuditEnabled 플랫 속성은 deprecated → 중첩 블록
        nodePackage { enabled = true }
        nodeAudit   { enabled = true }
        // 실험적 분석기 비활성화 (오탐률 감소)
        experimentalEnabled = false
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// P3-04: SonarQube / SonarCloud 정적 분석 설정
// ══════════════════════════════════════════════════════════════════════════════
//
// 실행: ./gradlew sonar
//   (환경변수 SONAR_TOKEN, SONAR_HOST_URL 필요)
//
// SonarCloud 프로젝트: https://sonarcloud.io/project/overview?id=HipsterMIN_integration-sso
// ──────────────────────────────────────────────────────────────────────────────
sonarqube {
    properties {
        // ── 프로젝트 식별 (SonarCloud 조직/키와 일치해야 함) ─────────────────
        property("sonar.projectKey",         "HipsterMIN_integration-sso")
        property("sonar.projectName",        "Idem Platform")
        property("sonar.organization",       "hipstermin")

        // ── 서버 주소 (환경변수 우선, 기본값 SonarCloud) ─────────────────────
        property("sonar.host.url",
            System.getenv("SONAR_HOST_URL") ?: "https://sonarcloud.io")

        // ── 소스 경로 ─────────────────────────────────────────────────────────
        property("sonar.sources",            "src/main/java")
        property("sonar.tests",              "src/test/java")

        // ── JaCoCo 커버리지 XML 경로 (멀티모듈 — glob 패턴) ──────────────────
        property("sonar.coverage.jacoco.xmlReportPaths",
            "**/build/reports/jacoco/test/jacocoTestReport.xml")

        // ── 테스트 결과 경로 ─────────────────────────────────────────────────
        property("sonar.junit.reportPaths",
            "**/build/test-results/test")

        // ── 분석 제외 경로 (자동 생성 코드 / SQL / 설정 클래스) ──────────────
        property("sonar.exclusions", listOf(
            "**/generated/**",          // QueryDSL 생성 코드
            "**/*.sql",                 // Flyway 마이그레이션 SQL
            "**/dto/**",                // DTO (비즈니스 로직 없음)
            "**/*Application.java",     // 스프링 부트 메인
            "**/config/**",             // 설정 클래스
            "editions/idem-kr-portal/**", // KR 포털 FE 코드 (별도 SonarCloud 프로젝트 가능)
            "idem-console-admin/**",      // 코어 관리 콘솔 FE (npm)
            "idem-tenant-sample/**",           // 테스트 스텁
            "infra/**",                 // 인프라 코드
        ).joinToString(","))

        // ── 커버리지 제외 (테스트 작성이 의미 없는 코드) ─────────────────────
        property("sonar.coverage.exclusions", listOf(
            "**/*Application.java",
            "**/config/**",
            "**/dto/**",
            "**/exception/**",
            "**/*Exception.java",
            "**/*ErrorCode.java",
        ).joinToString(","))

        // ── 중복 제외 ─────────────────────────────────────────────────────────
        property("sonar.cpd.exclusions", listOf(
            "**/dto/**",
            "**/*Exception.java",
        ).joinToString(","))

        // ── GitHub 연동 (PR 데코레이션) ──────────────────────────────────────
        property("sonar.pullrequest.provider", "GitHub")
        property("sonar.pullrequest.github.repository", "HipsterMIN/integration-sso")

        // ── 품질 게이트: Security Hotspot 0 허용 (정부24 보안 정책) ──────────
        property("sonar.qualitygate.wait", "false")  // CI 블로킹 비활성화 (비동기)

        // ── Java 소스 인코딩 ──────────────────────────────────────────────────
        property("sonar.sourceEncoding", "UTF-8")
    }
}

// ── OWASP 억제 파일 디렉토리 생성 (초기 실행 시) ─────────────────────────────
tasks.register("createOwaspSuppressionsDir") {
    description = "OWASP Dependency-Check 억제 파일 디렉토리 및 기본 파일 생성"
    group       = "verification"
    doLast {
        val suppressionDir  = file("$rootDir/infra/owasp")
        val suppressionFile = file("$rootDir/infra/owasp/suppressions.xml")
        if (!suppressionFile.exists()) {
            suppressionDir.mkdirs()
            suppressionFile.writeText(
                """<?xml version="1.0" encoding="UTF-8"?>
<suppressions xmlns="https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.3.xsd">
    <!--
        OWASP Dependency-Check 오탐(False Positive) 억제 설정
        ===========================================================
        형식:
          <suppress>
            <notes>억제 이유 — 담당자/날짜</notes>
            <packageUrl regex="true">^pkg:maven/group/artifact@.*$</packageUrl>
            <cve>CVE-YYYY-XXXXX</cve>
          </suppress>

        예시 (실제 오탐 발견 시 추가):
          <suppress>
            <notes>Spring Framework 6.x 미적용 CVE — 2024-01-15 확인 (홍길동)</notes>
            <packageUrl regex="true">^pkg:maven/org\.springframework/.*@6\..*$</packageUrl>
            <cve>CVE-2023-20860</cve>
          </suppress>
    -->
</suppressions>
"""
            )
            println("[OWASP] 억제 파일 생성: $suppressionFile")
        }
    }
}

// dependencyCheckAnalyze 실행 전 억제 파일 디렉토리 보장
tasks.named("dependencyCheckAnalyze") {
    dependsOn("createOwaspSuppressionsDir")
}

// ── JaCoCo 통합 커버리지 집계 (루트 레벨) ────────────────────────────────────
// ./gradlew jacocoAggregateReport — 모든 서브모듈 커버리지 합산
tasks.register<JacocoReport>("jacocoAggregateReport") {
    description = "모든 서브모듈 JaCoCo 커버리지 통합 리포트 생성"
    group       = "verification"

    dependsOn(subprojects.map { it.tasks.named("test") })

    executionData.setFrom(
        fileTree(rootDir) {
            include("**/build/jacoco/test.exec")
        }
    )
    sourceDirectories.setFrom(
        subprojects.map { it.file("src/main/java") }.filter { it.exists() }
    )
    classDirectories.setFrom(
        subprojects.flatMap { sub ->
            sub.fileTree(sub.layout.buildDirectory.dir("classes/java/main").get().asFile) {
                exclude(
                    "**/Q*.class",
                    "**/*Application.class",
                    "**/*Config.class",
                    "**/dto/**",
                )
            }
        }
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate"))
    }
}

// ── Spotless: 로컬 pre-commit 포맷팅 (2026-09-07) ─────────────────────────────
// CI 게이트가 아니라 .githooks/pre-commit 에서 spotlessApply 로 실행된다 (scripts/dev/install-git-hooks.sh).
// ratchetFrom("origin/main") — origin/main 대비 변경된 파일만 대상 → 기존 코드 일괄 재포맷 없이 점진 도입.
// 보수적 규칙만 적용(임포트 정리·미사용 임포트 제거·후행 공백·파일 끝 개행·탭→4칸). 전체 재포맷(palantirJavaFormat 등)은
// 코드베이스 합의 후 별도 도입.
subprojects {
    plugins.withId("java") {
        apply(plugin = "com.diffplug.spotless")
        extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            // check/build 에 spotlessCheck 를 묶지 않는다 — CI 의 얕은 checkout 에는 origin/main 이 없어
            // ratchetFrom 이 실패하고, 포맷 검사는 로컬 pre-commit 훅이 담당한다.
            isEnforceCheck = false
            ratchetFrom("origin/main")
            java {
                target("src/**/*.java")
                importOrder()
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
                leadingTabsToSpaces(4)
            }
        }
    }
}
