package kr.go.smes.qim.infrastructure.jpa.repository;

import kr.go.smes.qim.infrastructure.jpa.entity.QimUserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Q-IM 사용자 Spring Data JPA Repository
 * — identifierHash 기반 조회는 auth_mean_mapping JOIN 필요
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 */
public interface QimUserJpaRepository extends JpaRepository<QimUserJpaEntity, String> {

    /**
     * identifierHash 로 사용자 조회 (Q-Sign 인증 후 식별 단계)
     * auth_mean_mapping.identifier_hash → qim_user JOIN
     */
    @Query("""
        SELECT u FROM QimUserJpaEntity u
        JOIN u.authMeanMappings m
        WHERE m.identifierHash = :identifierHash
          AND m.status = 'ACTIVE'
        """)
    Optional<QimUserJpaEntity> findByIdentifierHash(@Param("identifierHash") String identifierHash);
}
