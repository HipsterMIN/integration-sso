// ══════════════════════════════════════════════════════════════════════════════
// idem-plugin-anyid — 행안부 Any-ID 설치형 브로커 플러그인 (S5b, docs/vendor-plugin-plan.md P3)
//
// 코어(idem-hub)에는 AnyID 클래스가 없다. 이 모듈이 AnyID 인증 시작 URL(DirectBrokerAdapter)·
// /api/v1/anyid/* 엔드포인트·SDK ssob 복호화·AnyID KMS 클라이언트를 맡고, 인증 결과 확정은
// 코어가 구현한 BrokerAuthCompletion 포트로 넘긴다.
//
// 벤더 SDK·자산 공급 규칙 (저장소에 두지 않는다)
//   vendorLibsDir = -PvendorLibsDir=<경로> | $IDEM_VENDOR_LIBS | $HOME/.idem/vendor-libs
//   - SDK jar: anyid-*.jar, kdist-api-*.jar, pid_api-*.jar  → compileOnly/runtimeOnly
//     (SDK 의 공개 의존성 commons-codec/configuration/lang·gson·zxing 은 Maven Central 에서 받는다)
//   - 자산·설정: <vendorLibsDir>/anyid/resources/** 를 jar 루트에 그대로 복사
//       static/anyid/**                (AnyID JS 번들·CSS·폰트 — /anyid/** 로 서빙)
//       static/config/config.anyidc.json
//       config/anyid/{kdist-local.json, pid_api.json, config.anyidc.json, sso-adaptor-conf-local.properties}
//   - SDK 가 없으면 sdk 패키지(kr.or.anyid 참조)를 컴파일에서 제외한다. 코어 CI 는 벤더 없이 통과하고,
//     그 경우 ssob 복호화 엔드포인트는 503(SDK 없음) 을 돌려준다.
// ══════════════════════════════════════════════════════════════════════════════
plugins {
    `java-library`
}

val vendorLibsDir: File = (findProperty("vendorLibsDir") as String?)
    ?.let { file(it) }
    ?: System.getenv("IDEM_VENDOR_LIBS")?.let { file(it) }
    ?: file("${System.getProperty("user.home")}/.idem/vendor-libs")

val anyidSdk = fileTree(vendorLibsDir) { include("anyid-*.jar", "kdist-api-*.jar", "pid_api-*.jar") }
val anyidSdkPresent = anyidSdk.files.isNotEmpty()
val anyidResources = file("$vendorLibsDir/anyid/resources")
logger.lifecycle("[idem-plugin-anyid] vendorLibsDir=$vendorLibsDir, AnyID SDK ${if (anyidSdkPresent) "발견: ${anyidSdk.files.map { it.name }.sorted()}" else "없음 → sdk 패키지 컴파일 제외"}, 자산 ${if (anyidResources.isDirectory) "발견" else "없음"}")

dependencies {
    api(project(":idem-common"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    if (anyidSdkPresent) {
        compileOnly(anyidSdk)
        runtimeOnly(anyidSdk)
        // SDK 가 동봉해 배포하던 공개 의존성 — 저장소 대신 Maven Central 에서
        runtimeOnly("commons-codec:commons-codec:1.15")
        runtimeOnly("commons-configuration:commons-configuration:1.10")
        runtimeOnly("commons-lang:commons-lang:2.6")
        runtimeOnly("com.google.code.gson:gson:2.8.6")
        runtimeOnly("com.google.zxing:core:3.3.0")
        runtimeOnly("com.google.zxing:javase:3.3.0")
    }
    testImplementation("org.springframework.boot:spring-boot-starter")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}

sourceSets {
    main {
        java {
            if (!anyidSdkPresent) exclude("**/sdk/**")
        }
        resources {
            if (anyidResources.isDirectory) srcDir(anyidResources)
        }
    }
}
