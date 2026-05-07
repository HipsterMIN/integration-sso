plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

dependencies {
    implementation(project(":platform-common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // ── DB 드라이버 ──────────────────────────────────────────────────────────
    // [변경] PostgreSQL → MariaDB
    // 운영: NHN Cloud RDS for MariaDB
    // 로컬 PoC: Docker self-hosted MariaDB 11.x
    implementation("org.mariadb.jdbc:mariadb-java-client")

    // ── Flyway ───────────────────────────────────────────────────────────────
    // [변경] flyway-database-postgresql → flyway-mysql (MariaDB는 mysql 플러그인 사용)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    implementation("org.springframework.kafka:spring-kafka")

    // ── 테스트 ───────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    // H2는 MariaDB 호환 모드로 사용 (MODE=MariaDB)
    testImplementation("com.h2database:h2")
}
