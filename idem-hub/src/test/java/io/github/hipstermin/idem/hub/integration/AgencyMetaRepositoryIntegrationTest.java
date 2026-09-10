package io.github.hipstermin.idem.hub.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S8-T4 | AgencyMeta JPA Repository 통합 테스트
 *
 * <p>실제 PostgreSQL(Testcontainer)에서 Flyway 마이그레이션 후
 * 기관 메타 데이터 저장/조회를 검증한다.
 *
 * <p>테스트 항목:
 * <ul>
 *   <li>Flyway 마이그레이션 완료 후 기관 INSERT/SELECT 정상 동작</li>
 *   <li>존재하지 않는 기관 코드 조회 → Optional.empty()</li>
 *   <li>기관 메타 도메인 변환 검증 (AgencyMetaJpaEntity → AgencyMeta)</li>
 *   <li>AgencyMetaRepository.findByCode() 위임 검증</li>
 * </ul>
 */
@DisplayName("AgencyMeta Repository 통합 테스트 (PostgreSQL Testcontainer + Flyway)")
class AgencyMetaRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AgencyMetaRepository agencyMetaRepository;

    @Autowired
    private AgencyMetaJpaRepository jpaRepository;

    private static final String AGENCY_CODE = "TC_AGENCY_DB_001";

    @BeforeEach
    void cleanUp() {
        jpaRepository.deleteById(AGENCY_CODE);
    }

    @Test
    @DisplayName("Flyway 마이그레이션 완료 후 기관 저장/조회 성공")
    void saveAndFind_afterFlywayMigration_success() {
        // given: 기관 엔티티 생성
        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder()
                .agencyCode(AGENCY_CODE)
                .officialName("TC 통합테스트 기관")
                .minAuthLevel("L1")
                .policyVersion("1.0")
                .apiKeyHash("pbkdf2-hash-value")
                .callbackWhitelist("[\"https://tc-agency.example.com/cb\"]")
                .allowedAttributes("[\"name_masked\"]")
                .integrationType(IntegrationType.DIRECT)
                .active(true)
                .build();

        // when: 저장
        jpaRepository.save(entity);

        // then: 조회 성공
        Optional<AgencyMetaJpaEntity> found = jpaRepository.findById(AGENCY_CODE);
        assertThat(found).isPresent();
        assertThat(found.get().getOfficialName()).isEqualTo("TC 통합테스트 기관");
        assertThat(found.get().getMinAuthLevel()).isEqualTo("L1");
        assertThat(found.get().getPolicyVersion()).isEqualTo("1.0");
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 기관 코드 조회 → Optional.empty() 반환")
    void findById_nonExistentCode_returnsEmpty() {
        Optional<AgencyMetaJpaEntity> result = jpaRepository.findById("NON_EXISTENT_CODE");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AgencyMetaRepository.findByCode() — 도메인 변환 포함 조회 성공")
    void findByCode_domainConversion_success() {
        // given
        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder()
                .agencyCode(AGENCY_CODE)
                .officialName("도메인 변환 테스트 기관")
                .minAuthLevel("L2")
                .policyVersion("2.0")
                .integrationType(IntegrationType.DIRECT)
                .callbackWhitelist("[\"https://example.com/cb\"]")
                .allowedAttributes("[]")
                .active(true)
                .build();
        jpaRepository.save(entity);

        // when
        Optional<AgencyMeta> domain = agencyMetaRepository.findByCode(AGENCY_CODE);

        // then
        assertThat(domain).isPresent();
        assertThat(domain.get().getAgencyCode()).isEqualTo(AGENCY_CODE);
        assertThat(domain.get().getOfficialName()).isEqualTo("도메인 변환 테스트 기관");
        assertThat(domain.get().getMinAuthLevel()).isEqualTo(AuthResult.AuthLevel.L2);
        assertThat(domain.get().getPolicyVersion()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("AgencyMeta 저장 후 재조회 — 왕복 직렬화 무결성 검증")
    void saveViaRepository_andFindBack_roundTripIntegrity() {
        // given
        AgencyMeta agencyMeta = AgencyMeta.builder()
                .agencyCode(AGENCY_CODE)
                .officialName("왕복 직렬화 테스트 기관")
                .minAuthLevel(AuthResult.AuthLevel.L1)
                .policyVersion("1.5")
                .callbackWhitelist(java.util.List.of("https://roundtrip.example.com/cb"))
                .allowedAttributes(java.util.List.of("name_masked", "mobile_masked"))
                .build();

        // when
        agencyMetaRepository.save(agencyMeta);
        Optional<AgencyMeta> result = agencyMetaRepository.findByCode(AGENCY_CODE);

        // then
        assertThat(result).isPresent();
        AgencyMeta saved = result.get();
        assertThat(saved.getAgencyCode()).isEqualTo(AGENCY_CODE);
        assertThat(saved.getOfficialName()).isEqualTo("왕복 직렬화 테스트 기관");
        assertThat(saved.getMinAuthLevel()).isEqualTo(AuthResult.AuthLevel.L1);
        assertThat(saved.getPolicyVersion()).isEqualTo("1.5");
    }

    @Test
    @DisplayName("활성 기관 목록 조회 — findAll() 정상 동작")
    void findAll_returnsExistingAgencies() {
        // given
        AgencyMetaJpaEntity entity = AgencyMetaJpaEntity.builder()
                .agencyCode(AGENCY_CODE)
                .officialName("목록 조회 테스트")
                .minAuthLevel("L1")
                .policyVersion("1.0")
                .integrationType(IntegrationType.DIRECT)
                .active(true)
                .build();
        jpaRepository.save(entity);

        // when
        long count = jpaRepository.count();

        // then: 최소 1개 이상 (방금 삽입한 것 포함)
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    // ── S1 범용화: 연동 유형 타입화 + APACHE_GATE 전용 엔드포인트 컬럼 ────────────

    @Test
    @DisplayName("S1: APACHE_GATE / BRIDGE 기관은 각자 전용 엔드포인트 컬럼으로 왕복하고 서로 섞이지 않는다")
    void integrationType_endpointsRoundTrip_dedicatedColumns() {
        AgencyMeta gate = AgencyMeta.builder()
                .agencyCode("TC_S1_GATE").officialName("S1 게이트 기관")
                .minAuthLevel(AuthResult.AuthLevel.L1).policyVersion("1.0")
                .integrationType(IntegrationType.APACHE_GATE)
                .apacheGateEndpoint("https://gw.example.org/internal/sso-session")
                .active(true).build();
        AgencyMeta bridge = AgencyMeta.builder()
                .agencyCode("TC_S1_BRIDGE").officialName("S1 브리지 기관")
                .minAuthLevel(AuthResult.AuthLevel.L1).policyVersion("1.0")
                .integrationType(IntegrationType.BRIDGE)
                .bridgeEndpoint("https://bridge.example.org/api/handoff/push")
                .active(true).build();

        agencyMetaRepository.save(gate);
        agencyMetaRepository.save(bridge);

        AgencyMeta gateLoaded = agencyMetaRepository.findByCode("TC_S1_GATE").orElseThrow();
        assertThat(gateLoaded.getIntegrationType()).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(gateLoaded.getApacheGateEndpoint()).isEqualTo("https://gw.example.org/internal/sso-session");
        assertThat(gateLoaded.getBridgeEndpoint()).isNull();

        AgencyMeta bridgeLoaded = agencyMetaRepository.findByCode("TC_S1_BRIDGE").orElseThrow();
        assertThat(bridgeLoaded.getIntegrationType()).isEqualTo(IntegrationType.BRIDGE);
        assertThat(bridgeLoaded.getBridgeEndpoint()).isEqualTo("https://bridge.example.org/api/handoff/push");
        assertThat(bridgeLoaded.getApacheGateEndpoint()).isNull();

        // DB 컬럼도 분리돼 있어야 한다 (V20)
        AgencyMetaJpaEntity gateRow = jpaRepository.findById("TC_S1_GATE").orElseThrow();
        assertThat(gateRow.getApacheGateEndpoint()).isNotNull();
        assertThat(gateRow.getBridgeEndpoint()).isNull();
    }

    @Test
    @DisplayName("S1: 빌더에서 연동 유형을 지정하지 않으면 DEFAULT(DIRECT) 로 저장된다")
    void integrationType_unspecified_defaultsToDirect() {
        agencyMetaRepository.save(AgencyMeta.builder()
                .agencyCode("TC_S1_DEFAULT").officialName("S1 기본")
                .minAuthLevel(AuthResult.AuthLevel.L1).policyVersion("1.0").active(true).build());

        assertThat(agencyMetaRepository.findByCode("TC_S1_DEFAULT").orElseThrow().getIntegrationType())
                .isEqualTo(IntegrationType.DIRECT);
    }
}
