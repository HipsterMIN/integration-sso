/**
 * onepass-agent — OnePass Agency Java Agent
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  설계 원칙                                                               │
 * │                                                                         │
 * │  1. 완전 독립 (No Spring / No Lombok / No platform-common 의존)          │
 * │     - 유관기관 WAS 클래스로더와 충돌 방지                                  │
 * │     - JDK 8 이상 모든 환경 지원 (소스 호환성 1.8)                         │
 * │                                                                         │
 * │  2. byte-buddy 번들링                                                    │
 * │     - fat-JAR에 byte-buddy 클래스를 포함                                  │
 * │     - shadowJar Gradle 9 호환 이슈 → 표준 Jar 태스크로 직접 번들링        │
 * │     - 완전한 패키지 relocate는 운영 배포 시 maven-shade-plugin 사용        │
 * │                                                                         │
 * │  3. fat-JAR (agentJar 커스텀 태스크)                                     │
 * │     - 배포 아티팩트: onepass-agent-{version}-all.jar                     │
 * │     - MANIFEST: Premain-Class, Agent-Class, Can-Redefine-Classes 포함   │
 * │                                                                         │
 * │  4. HTTP 클라이언트: java.net.HttpURLConnection 전용                      │
 * │     - 외부 HTTP 라이브러리 의존 없음                                       │
 * │     - 순수 JDK 내장 API만 사용                                            │
 * │                                                                         │
 * │  5. 루트 subprojects {} 설정에서 Spring BOM / Lombok 상속받지 않음         │
 * │     - apply(plugin = "io.spring.dependency-management") 미적용            │
 * │     - Lombok annotation processor 제외                                   │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

plugins {
    java
}

// ── 루트 설정 오버라이드: Spring BOM / DependencyManagement 제거 ─────────────
configurations.all {
    exclude(group = "org.springframework.boot")
    exclude(group = "org.springframework")
    exclude(group = "org.projectlombok")
}

// ── Java 소스 호환성: JDK 8 최저 지원 ──────────────────────────────────────
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// ── 의존성 ──────────────────────────────────────────────────────────────────
val byteBuddyVersion  = "1.17.8"
// Javassist 3.29.x: JDK 1.3+ 호환 — JDK 1.5 레거시 JEUS (4/5/6) 위빙에 사용
// byte-buddy가 JDK 8 런타임을 요구하는 것과 달리, Javassist는 JDK 1.3+에서 동작.
// agentJar fat-JAR에 함께 번들링되어 런타임에 동적으로 선택된다.
val javassistVersion  = "3.30.2-GA"

dependencies {
    // byte-buddy: JDK 8+ 환경 바이트코드 위빙 핵심 엔진 (JEUS 7+, Tomcat, JBoss 등)
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")

    // Javassist: JDK 1.3~1.7 레거시 환경 바이트코드 위빙 엔진 (JEUS 4/5/6)
    // - JDK 1.5 premain(JSR-163) 환경에서 byte-buddy 대신 사용
    // - fat-JAR에 번들링하여 유관기관 WAS 클래스패스와 충돌 방지
    implementation("org.javassist:javassist:$javassistVersion")

    // JUnit 5 직접 버전 명시 (Spring BOM 없으므로)
    // junit-jupiter 5.12.x → junit-platform 1.12.x 필요 (버전 동기화)
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
    testImplementation("org.mockito:mockito-core:5.16.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

// ── agentJar: fat-JAR (Agent MANIFEST + byte-buddy 번들) ───────────────────
//
// shadowJar 플러그인(johnrengelman / gradleup) 은 Gradle 9.x + byte-buddy 1.17.x
// 조합에서 ASM Remapper.mapValue() 충돌 이슈가 있음.
// 표준 Gradle Jar 태스크 + zipTree 언팩 방식으로 fat-JAR을 직접 생성.
//
// 패키지 Relocate 전략:
//   - 현재 빌드: byte-buddy 원래 패키지(net.bytebuddy)로 번들 포함
//   - 완전한 shading(net.bytebuddy → kr.go.smes.agent.shaded.bytebuddy)은
//     실제 운영 배포 CI에서 maven-shade-plugin 스테이지로 수행
//   - 유관기관 WAS에 기존 byte-buddy가 없는 경우(대부분) 충돌 없이 동작
// ──────────────────────────────────────────────────────────────────────────────
val agentJar by tasks.registering(Jar::class) {
    group       = "build"
    description = "OnePass Agent fat-JAR (byte-buddy bundled + Agent MANIFEST)"

    archiveClassifier.set("all")
    archiveVersion.set(project.version.toString())

    dependsOn(tasks.named("compileJava"), tasks.named("processResources"))

    // ── MANIFEST 설정 ──────────────────────────────────────────────────────
    manifest {
        attributes(
            "Premain-Class"           to "kr.go.smes.agent.core.OnePassAgentMain",
            "Agent-Class"             to "kr.go.smes.agent.core.OnePassAgentMain",
            "Can-Redefine-Classes"    to "true",
            "Can-Retransform-Classes" to "true",
            "Boot-Class-Path"         to "",
            "Implementation-Title"    to "OnePass Agency Java Agent",
            "Implementation-Version"  to project.version.toString()
        )
    }

    // ── Agent 자체 클래스 + 리소스 ──────────────────────────────────────────
    from(sourceSets["main"].output)

    // ── byte-buddy + Javassist JAR 언팩 포함 ────────────────────────────────
    // runtimeClasspath에서 byte-buddy / javassist 관련 JAR을 필터링하여 클래스 파일 포함.
    // - byte-buddy:  JDK 8+ WAS (JEUS 7+, Tomcat, JBoss 등) 위빙 엔진
    // - javassist:   JDK 1.5 레거시 WAS (JEUS 4/5/6) 위빙 엔진
    from({
        configurations["runtimeClasspath"]
            .filter { f -> f.name.contains("byte-buddy") || f.name.contains("javassist") }
            .map    { f -> zipTree(f) }
    }) {
        // 서명 파일 + 모듈 정보 제거
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("module-info.class")
    }

    duplicatesStrategy = DuplicatesStrategy.WARN
}

// ── assemble 시 agentJar 포함 ─────────────────────────────────────────────
tasks.named("assemble") {
    dependsOn(agentJar)
}

// ── 테스트 설정 ──────────────────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "-Djdk.instrument.traceUsage=false"
    )
}

// ── Java 컴파일 설정 ─────────────────────────────────────────────────────────
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.compilerArgs.addAll(listOf("--release", "8"))
}
