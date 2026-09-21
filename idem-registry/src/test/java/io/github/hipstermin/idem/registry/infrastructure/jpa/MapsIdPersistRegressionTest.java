package io.github.hipstermin.idem.registry.infrastructure.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.registry.domain.QimUser;
import io.github.hipstermin.idem.registry.domain.UserProfile;
import io.github.hipstermin.idem.registry.infrastructure.UserRepository;
import io.github.hipstermin.idem.registry.infrastructure.UserRepositoryImpl;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.BizMemberJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.UserProfileJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.BizMemberJpaRepository;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.UserProfileJpaRepository;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

/**
 * 회귀 테스트 — Hibernate 6.6 에서 {@code @MapsId} 자식(user_profile, biz_member)을
 * Spring Data {@code save()}(= merge) 로 신규 저장하면 {@code StaleObjectStateException} 이 난다.
 *
 * <p>Hibernate 6.6 의 {@code DefaultMergeEventListener.entityIsDetached} 는 DB 에 행이 없고
 * 퍼시스터가 "transient 아님" 을 단정할 수 있으면 예외를 던진다. {@code @MapsId} 파생 식별자는
 * 항상 "transient 아님" 으로 판정되므로 신규 자식의 merge 가 예외가 된다. 부모(qim_user)는
 * 할당 ID + 버전 없음이라 판정 불가 → transient 로 취급되어 통과한다.
 *
 * <p>Testcontainers 없이(H2, MariaDB 모드) 재현·회귀 검증한다. 이 로직은 Hibernate 내부라
 * DB 종류와 무관하다.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:qim_mapsid;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserRepositoryImpl.class)
class MapsIdPersistRegressionTest {

    @Autowired QimUserJpaRepository     userRepository;
    @Autowired UserProfileJpaRepository profileRepository;
    @Autowired BizMemberJpaRepository   bizMemberRepository;
    @Autowired UserRepository           domainUserRepository;
    @Autowired TestEntityManager        em;

    private static QimUserJpaEntity newUser(String id) {
        QimUserJpaEntity user = new QimUserJpaEntity();
        user.setQimUserId(id);
        user.setStatus(UserStatus.ACTIVE.name());
        user.setEventVersion(1L);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return user;
    }

    private static UserProfileJpaEntity newProfile(QimUserJpaEntity user) {
        UserProfileJpaEntity p = new UserProfileJpaEntity();
        p.setQimUserId(user.getQimUserId());
        p.setUser(user);
        p.setNameMasked("홍*동");
        p.setNationalityType("DOMESTIC");
        p.setIsMinor(false);
        p.setUpdatedAt(Instant.now());
        return p;
    }

    @Test
    @DisplayName("신규 qim_user + @MapsId user_profile 을 JPA save() 로 저장 — UserRegistrationServiceImpl.registerNew 경로")
    void saveNewUserWithProfile() {
        QimUserJpaEntity user = newUser("mapsid-u1");
        user.setProfile(newProfile(user));

        userRepository.saveAndFlush(user);
        em.clear();

        assertThat(profileRepository.findById("mapsid-u1")).isPresent();
        assertThat(userRepository.findById("mapsid-u1")).get()
                .extracting(u -> u.getProfile().getNameMasked()).isEqualTo("홍*동");
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

    @Test
    @DisplayName("이미 저장된(managed) 회원에 새 프로필을 도메인 UserRepository.save 로 추가 — UserRepositoryImpl 경로")
    void addProfileToManagedUserViaDomainRepository() {
        em.persistAndFlush(newUser("mapsid-u3"));
        em.clear();

        QimUser loaded = domainUserRepository.findById("mapsid-u3").orElseThrow();
        QimUser domain = QimUser.builder()
                .qimUserId(loaded.getQimUserId())
                .status(loaded.getStatus())
                .authMeanMappings(loaded.getAuthMeanMappings())
                .eventVersion(loaded.getEventVersion())
                .createdAt(loaded.getCreatedAt())
                .updatedAt(loaded.getUpdatedAt())
                .profile(UserProfile.builder().nameMasked("김*수").nationalityType("DOMESTIC").build())
                .build();

        domainUserRepository.save(domain);
        em.flush();
        em.clear();

        assertThat(profileRepository.findById("mapsid-u3")).get()
                .extracting(UserProfileJpaEntity::getNameMasked).isEqualTo("김*수");
    }
}
