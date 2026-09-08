package io.github.hipstermin.idem.registry.infrastructure.jpa.repository;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.ConsentVersionJpaEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 동의 버전 Spring Data JPA Repository
 */
public interface ConsentVersionJpaRepository
        extends JpaRepository<ConsentVersionJpaEntity, String> {

    /**
     * 특정 유형의 최신 ACTIVE 버전 조회
     * (effective_at DESC → 가장 최근 적용 버전 반환)
     */
    @Query("""
        SELECT v FROM ConsentVersionJpaEntity v
        WHERE v.consentType = :consentType
          AND v.status      = 'ACTIVE'
          AND v.effectiveAt <= :now
        ORDER BY v.effectiveAt DESC
        LIMIT 1
        """)
    Optional<ConsentVersionJpaEntity> findLatestActive(
            @Param("consentType") String consentType,
            @Param("now")         Instant now);

    /**
     * 모든 유형의 현재 ACTIVE 버전 목록 (회원가입 동의 화면 로딩용)
     */
    @Query("""
        SELECT v FROM ConsentVersionJpaEntity v
        WHERE v.status = 'ACTIVE'
          AND v.effectiveAt <= :now
        ORDER BY v.consentType ASC, v.effectiveAt DESC
        """)
    List<ConsentVersionJpaEntity> findAllActive(@Param("now") Instant now);
}
