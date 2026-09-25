plugins {
    `java-library`
}

dependencies {
    // S9 PR-2: 개명 5단계 스키마 자동 rename(FlywayMigrationStrategy) — Flyway 를 쓰는 앱에서만 활성 (compileOnly)
    compileOnly("org.flywaydb:flyway-core")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.kafka:spring-kafka")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    api("org.apache.commons:commons-lang3")

    // UUID v7 — 시간 정렬 가능 ID (RFC 9562). Java 21 환경에서는 JDK 미지원이므로 라이브러리 사용
    // idem-common에 api로 선언 → 모든 서브프로젝트에서 추가 의존성 없이 사용 가능
    api("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
