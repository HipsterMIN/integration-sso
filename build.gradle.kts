import org.springframework.boot.gradle.tasks.bundling.BootJar
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    java
    id("org.springframework.boot")         version "3.5.9" apply false
    id("io.spring.dependency-management")  version "1.1.7" apply false
}

// ── 전체 공통 설정 ────────────────────────────────────────────────────────────
allprojects {
    group   = "com.onepass"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// ── 서브프로젝트 공통 설정 ─────────────────────────────────────────────────────
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

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

    dependencies {
        // Lombok
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testCompileOnly"("org.projectlombok:lombok")
        "testAnnotationProcessor"("org.projectlombok:lombok")

        // Test
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}

// ── platform-common: 실행 JAR 불필요, plain JAR만 생성 ───────────────────────
project(":platform-common") {
    tasks.withType<BootJar> { enabled = false }
    tasks.withType<Jar>     { enabled = true  }
}
