plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation(project(":idem-common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // ── DB 드라이버 ──────────────────────────────────────────────────────────
    // D1(2026-09-21): MariaDB → PostgreSQL. 코어 제품은 DB 엔진 1종(PostgreSQL 16, hub 와 같은 인스턴스의 qim 스키마).
    // MariaDB 드라이버·Flyway 플러그인은 기존 설치의 1 릴리스 호환(spring profile `mariadb`)을 위해 runtimeOnly 로만 남긴다.
    implementation("org.postgresql:postgresql")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    // ── Flyway ───────────────────────────────────────────────────────────────
    // 벤더별 스크립트: db/migration/{vendor} (postgresql = 기준선 V1, mariadb = 종전 V1~V9 이력)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.flywaydb:flyway-mysql")

    implementation("org.springframework.kafka:spring-kafka")

    // ── 테스트 ───────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // H2 는 PostgreSQL 호환 모드(MODE=PostgreSQL) — Testcontainers 없는 슬라이스 테스트용
    testImplementation("com.h2database:h2")
}
