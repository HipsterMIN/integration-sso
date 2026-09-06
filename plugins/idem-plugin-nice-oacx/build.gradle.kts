// ══════════════════════════════════════════════════════════════════════════════
// idem-plugin-nice-oacx — NICE 휴대폰 본인인증 + OACX 전자서명 + EzAuth 위젯 플러그인 (P2 골격)
//
// 상태(2026-09-06): 골격만. 실제 코드(NiceAuthService/NiceApiClient/OacxClient/dto/nice, EzAuth 자산)는
// 아직 idem-hub 에 있으며 README.md 의 이동표에 따라 P2 본작업에서 옮긴다.
//
// 벤더 SDK 공급 규칙
//   - 저장소에 벤더 jar 를 두지 않는다. Gradle 속성 -PvendorLibsDir=<경로> 또는 환경변수 IDEM_VENDOR_LIBS,
//     기본 $HOME/.idem/vendor-libs 에서 OACX-SDK-*.jar 를 compileOnly/runtimeOnly 로 읽는다.
//   - SDK 가 없으면 SDK 에 의존하는 소스(kr.go.smes.plugin.niceoacx.oacx 패키지)를 컴파일에서 자동 제외한다.
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
    compileOnly("org.springframework.boot:spring-boot-starter-web")     // 정적 자산 서빙·컨트롤러(P2 본작업)
    compileOnly("org.springframework:spring-webflux")                    // NiceApiClient(WebClient) 이동 대비
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    if (oacxSdkPresent) {
        compileOnly(oacxSdk)
        runtimeOnly(oacxSdk)
    }
    testImplementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}

sourceSets {
    main {
        java {
            if (!oacxSdkPresent) exclude("**/oacx/**")
        }
    }
}
