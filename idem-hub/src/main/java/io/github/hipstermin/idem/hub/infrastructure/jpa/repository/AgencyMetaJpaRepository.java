package io.github.hipstermin.idem.hub.infrastructure.jpa.repository;

import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 기관 메타 Spring Data JPA Repository
 * 설계서 §11.2 기관 정책 조회
 *
 * [DB] PostgreSQL — idem_hub.agency_meta 테이블
 */
public interface AgencyMetaJpaRepository extends JpaRepository<AgencyMetaJpaEntity, String> {
    // PK = agency_code → findById(agencyCode) 사용 가능

    /** 1.1.1 G1-3 콘솔 목록 — 코드·이름 부분 일치(소문자 LIKE 패턴), 페이지·총계 */
    @Query("SELECT e FROM AgencyMetaJpaEntity e WHERE LOWER(e.agencyCode) LIKE :q OR LOWER(COALESCE(e.officialName, '')) LIKE :q")
    Page<AgencyMetaJpaEntity> search(@Param("q") String q, Pageable pageable);

    /** 테넌트 관리자용 — 자기 Tenant 의 Service 만 (DB 에서 거른다: 종전에는 페이지를 뽑은 뒤 걸러 페이지가 비는 일이 있었다) */
    @Query("SELECT e FROM AgencyMetaJpaEntity e WHERE e.tenantCode = :tenant AND (LOWER(e.agencyCode) LIKE :q OR LOWER(COALESCE(e.officialName, '')) LIKE :q)")
    Page<AgencyMetaJpaEntity> searchInTenant(@Param("tenant") String tenant, @Param("q") String q, Pageable pageable);
}
