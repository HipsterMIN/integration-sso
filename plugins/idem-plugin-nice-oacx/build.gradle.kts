// ══════════════════════════════════════════════════════════════════════════════
// idem-plugin-nice-oacx — NICE 휴대폰 본인인증 + OACX 전자서명 + EzAuth 위젯 플러그인 (P2 골격)
//
// 상태(2026-09-10, S5a): NICE 휴대폰 본인인증 코드(NicePhoneService/NiceApiClient/암·복호화/저장소)와 OACX 어댑터가
// idem-hub 에서 이 모듈로 이동했다. 코어에는 벤더 클래스가 없다. EzAuth 정적 자산(FE public/ezauth)은 아직 콘솔에 있다.
//
// 벤더 SDK 공급 규칙
//   - 저장소에 벤더 jar 를 두지 않는다. Gradle 속성 -PvendorLibsDir=<경로> 또는 환경변수 IDEM_VENDOR_LIBS,
//     기본 $HOME/.idem/vendor-libs 에서 OACX-SDK-*.jar 를 compileOnly/runtimeOnly 로 읽는다.
//   - SDK 가 없으면 SDK 에 의존하는 소스(io.github.hipstermin.idem.plugin.niceoacx.oacx 패키지)를 컴파일에서 자동 제외한다.
//     → 코어 CI 는 벤더 없이 통과하고, SDK 가 있는 환경(자체 호스팅 러너·개발 PC)에서만 OACX 부분이 빌드된다.
// ══════════════════════════════════════════════════════════════════════════════
plugins {
    `java-library`
}

val vendorLibsDir: File = (findProperty("vendorLibsDir") as String?)
    ?.let { file(it) }
    ?: System.getenv("IDEM_VENDOR_LIBS")?.let { file(it) }
    ?: file("${System.getProperty("user.home")}/.idem/vendor-libs")

val oacxSdk = fileTree(vendorLibsDir) { include("OACX-SDK-*.jar") }
val oacxSdkPresent = oacxSdk.files.isNotEmpty()
logger.lifecycle("[idem-plugin-nice-oacx] vendorLibsDir=$vendorLibsDir, OACX SDK ${if (oacxSdkPresent) "발견: ${oacxSdk.files.map { it.name }}" else "없음 → oacx 패키지 컴파일 제외"}")

dependencies {
    api(project(":idem-common"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    // 코어(idem-hub)가 런타임에 제공하는 빈·라이브러리 — 플러그인은 컴파일 시에만 참조한다 (S5a)
    compileOnly("org.springframework:spring-webflux")                    // NiceApiClient(WebClient)
    compileOnly("io.projectreactor.netty:reactor-netty-http")
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")   // 토큰·세션 저장소
    compileOnly("org.redisson:redisson-spring-boot-starter:3.52.0")          // 토큰 갱신 분산 락
    compileOnly("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")    // @CircuitBreaker/@Retry
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    if (oacxSdkPresent) {
        compileOnly(oacxSdk)
        runtimeOnly(oacxSdk)
    }
    testImplementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.redisson:redisson-spring-boot-starter:3.52.0")
    testImplementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
}

sourceSets {
    main {
        java {
            if (!oacxSdkPresent) exclude("**/oacx/**")
        }
    }
}
