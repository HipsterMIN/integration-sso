package io.github.hipstermin.idem.registry.kr.biz;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * KR 에디션 회귀 테스트 — Hibernate 6.6 에서 {@code @MapsId} 자식(biz_member)을 Spring Data {@code save()} 로
 * 신규 저장하는 경로 (코어 {@code MapsIdPersistRegressionTest} 의 biz_member 케이스, S8-a 에서 이동).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kr_biz_mapsid;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class KrBizMemberMapsIdPersistTest {

    @Autowired QimUserJpaRepository  userRepository;
    @Autowired BizMemberJpaRepository bizMemberRepository;
    @Autowired TestEntityManager     em;

    private static QimUserJpaEntity newUser(String id) {
        QimUserJpaEntity user = new QimUserJpaEntity();
        user.setQimUserId(id);
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEventVersion(1L);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }

    @Test
    @DisplayName("기존 회원에 @MapsId biz_member 를 JPA save() 로 저장 — BizMemberConversionServiceImpl 경로")
    void saveNewBizMemberForExistingUser() {

        em.persistAndFlush(newUser("mapsid-u2"));
        em.clear();

        QimUserJpaEntity managed = userRepository.findById("mapsid-u2").orElseThrow();
        BizMemberJpaEntity biz = BizMemberJpaEntity.builder()
                .qimUserId("mapsid-u2").user(managed)
                .bizRegNo("1234567890").companyName("테스트(주)").bizStatus("ACTIVE")
                .build();

        bizMemberRepository.saveAndFlush(biz);
        em.clear();

        assertThat(bizMemberRepository.findById("mapsid-u2")).get()
                .extracting(BizMemberJpaEntity::getCompanyName).isEqualTo("테스트(주)");
    }

}
