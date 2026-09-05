// ══════════════════════════════════════════════════════════════════════════════
// idem-plugin-mock-auth — 본인인증 SPI 의 개발·CI용 Mock 제공자 (docs/vendor-plugin-plan.md P1)
//
// 코어가 벤더 SDK 없이 기동·검증되기 위한 기본 플러그인. 항상 코어와 함께 빌드되지만
// 런타임 활성화는 idem.plugins.mock-auth.enabled=true 일 때만 (AutoConfiguration 조건).
// 운영 프로파일에서는 절대 켜지 않는다.
// ══════════════════════════════════════════════════════════════════════════════
plugins {
    `java-library`
}

dependencies {
    api(project(":idem-common"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter")   // @ConditionalOnProperty 등 컴파일 시에만
    testImplementation("org.springframework.boot:spring-boot-starter")
}
