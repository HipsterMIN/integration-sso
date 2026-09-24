// ══════════════════════════════════════════════════════════════════════════════
// idem-kr-registry — Idem KR Public Edition, registry 부트 모듈 (generalization-plan S8-a)
//
// 코어(idem-registry)가 모르는 SMES 회원 개념: 기업회원 전환(biz_member, /api/v1/internal/biz-members)·
// 기관용 CI 조회(/api/v1/internal/member/lookup-by-ci). 패키지 io.github.hipstermin.idem.registry.kr.* 는
// QImApplication 의 스캔 범위 안이라 jar 가 있으면 엔티티·리포지토리·컨트롤러가 그대로 활성화된다.
// KR 전용 Flyway 마이그레이션(db/migration/kr/postgresql, V1000+) 은 KrRegistryEditionConfig 가 location 에 더한다.
//
//   코어 에디션: ./gradlew :idem-registry:bootJar     KR 에디션: ./gradlew :idem-kr-registry:bootJar
// ══════════════════════════════════════════════════════════════════════════════
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    java
}

springBoot {
    mainClass.set("io.github.hipstermin.idem.registry.QImApplication")
}

dependencies {
    implementation(project(":idem-common"))
    implementation(project(":idem-registry"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
}
