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
          AND v.serviceCode IS NULL
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
          AND v.serviceCode IS NULL
          AND v.effectiveAt <= :now
        ORDER BY v.consentType ASC, v.effectiveAt DESC
        """)
    List<ConsentVersionJpaEntity> findAllActive(@Param("now") Instant now);

    // ── 1.1 동의 카탈로그 (서비스 범위) ─────────────────────────────────────

    /** 서비스가 보는 카탈로그 — 플랫폼 공통(NULL) + 그 서비스 전용, ACTIVE·적용 중 */
    @Query("""
        SELECT v FROM ConsentVersionJpaEntity v
        WHERE v.status = 'ACTIVE'
          AND v.effectiveAt <= :now
          AND (v.serviceCode IS NULL OR v.serviceCode = :serviceCode)
        ORDER BY v.serviceCode ASC NULLS FIRST, v.consentType ASC, v.effectiveAt DESC
        """)
    List<ConsentVersionJpaEntity> findCatalog(@Param("serviceCode") String serviceCode, @Param("now") Instant now);

    /** 한 서비스의 모든 버전(이력 포함) */
    List<ConsentVersionJpaEntity> findByServiceCodeOrderByConsentTypeAscEffectiveAtDesc(String serviceCode);

    /** 플랫폼 공통의 모든 버전(이력 포함) */
    List<ConsentVersionJpaEntity> findByServiceCodeIsNullOrderByConsentTypeAscEffectiveAtDesc();

    /** 같은 범위·유형의 ACTIVE — 새 버전 발행 때 대체(SUPERSEDED)할 것 */
    List<ConsentVersionJpaEntity> findByServiceCodeAndConsentTypeAndStatus(String serviceCode, String consentType, String status);

    List<ConsentVersionJpaEntity> findByServiceCodeIsNullAndConsentTypeAndStatus(String consentType, String status);
}
